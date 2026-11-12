# Redstone Plan：FSR Redstone 调查与接入计划

本页是接入设计，RR/RC 尚未在当前源码中实现。核对基线为 AMD FSR SDK `v2.4.0`（2026-10-28）：Upscaling 4.1.2、Frame Generation 4.0.2、FG Swapchain 4.0.0、Ray Regeneration 1.2.1、Radiance Caching 0.9.0 Technical Preview。
当前 native FSR SR/FG 使用 FidelityFX 2.3 系列接口；不能把已接超分等同于已接 Redstone 全套。

## 1. 业务位置与组合

| 功能 | 所属模块 | 作用与连接 |
| --- | --- | --- |
| FSR RC / Radiance Caching | TransportModule | 路径追踪生成训练样本与查询，网络预测后续间接辐射；与 NRC 同类，独立 provider |
| FSR RR / Ray Regeneration | ReconstructionModule 的降噪阶段 | 对分开的光照信号降噪；不负责超分、TAA 或消除 jitter |
| FSR Upscaling | ReconstructionModule 的时域重建阶段 | 合成世界 HDR 后重建到显示尺寸，消除 jitter |
| FSR Frame Generation / Swapchain | PresentationModule | 消费真实帧及其 guides，生成中间帧并统一调度 UI/Present |

集中式图增加明确的 `FSR RR + SR` 组合，原有 NRD 与 DLSS RR 组合各自保留。
FSR RR 与 NRD 不能重复处理同一路信号；DLSS RR 仍作为联合降噪与重建节点，不再串另一轮 SR。
FSR RC 在这些重建组合上游，不能放进现有 `SuperResolution` 后端枚举或复用 `mcdr_sr_dispatch` 表示训练。
能否选用某个组合由实际硬件、effect 版本及语义契约共同决定，不按“Redstone 总开关”启用所有效果。

```text
世界增量 / PBR / 大气 / 光源
  → DDA + BVH + Mega Geometry 查询
  → Builtin Work Graph 路径追踪
      → RC 训练样本 + 辐射查询
      → FSR RC 推理与训练
      → 已追踪贡献 + throughput × RC 预测
  → LightingSignals + Guides
      → FSR RR → 分项重调制/合成 → FSR SR → 世界颜色
      → NRD    → 分项重调制/合成 → 所选 SR → 世界颜色
      → DLSS RR                            → 世界颜色
  → 所选 FG / UI 合成 / Present
```

上图是互斥的重建分支，不能把三行串行执行。AMD 推荐 RR 搭配 FSR 4；其接口本身分离降噪与超分。
架构允许声明其他 SR 组合，只有实际适配并验证的组合进入可选 profile。

## 2. FSR RR：从信号语义接入

按需要创建包含 direct diffuse、direct specular、indirect diffuse、indirect specular 的上下文。
dominant light visibility、AO 和 specular occlusion 是独立可选信号；1.2.1 支持后两者单独降噪。
不能从最终合成颜色可靠地还原这些分量，必须由输运阶段直接输出。

| 输入 | 必须保证的语义 |
| --- | --- |
| linearDepth | 有符号的线性视空间深度；不能直接传 reverse-Z device depth |
| motionVectors | XY 表示当前到前帧的运动，经过 scale 转成 UV；Z 为前帧减当前的有符号线性深度差 |
| normals | RG 为八面体法线编码，B 为线性 roughness，A 为 SDK 的材质类别编码 |
| diffuse/specular albedo | 第一跳重调制因子；specular albedo 要按 BRDF 语义生成，不能拿 base color 顶替 |
| radiance signals | 去掉第一跳 albedo 调制；间接漫反射/镜面 RGB 为辐射，A 按接口提供 hit distance |
| 帧参数 | current/previous 矩阵、统一 jitter、真实渲染尺寸与历史 reset |

默认 albedo 使用软件 sqrt 编码；选择 `FFX_DENOISER_DISPATCH_NON_ENCODED_ALBEDO` 时提供未编码值。
编码仅在 AMD adapter 边界完成，禁止叠加 sRGB 与 sqrt，也禁止把该布局设为全渲染器公共材质格式。
SDK 的材质类别只有四类，不等于 Minecraft 的完整材质 ID；由 adapter 把业务类别映射到有限的历史隔离类别。

RR 输出后由合成阶段乘回第一跳 albedo，再合成发光与其他独立信号，最后由 SR/TAA 处理 jitter。
当前已有的 SR 深度快照和二维运动缓冲可以复用资源管理逻辑，但不足以直接作为 RR 的完整 guides。
静态地形、动态实体、变形、材质动画和透明表面分别定义运动与有效覆盖；仅相机运动不能替代物体运动。

切世界、相机跳变、历史不兼容、信号布局变化时传 `FFX_DENOISER_DISPATCH_RESET`。
历史只有 dispatch 成功并在帧图中发布后才能提交；未加载覆盖、反射/透射命中等事实保留到 adapter，避免把未知信号当作可靠黑色。

## 3. FSR RC：路径追踪与在线学习共同接入

RC 不遍历场景，也不代替 RTXDI、RTXGI、DDA、BVH 或 Mega Geometry。
路径追踪器负责生成有效训练目标，网络用这些数据估计路径后续的辐射。
NRC 与 FSR RC 共用业务层的缓存请求/结果语义，各自保留 SDK 要求的训练布局和生命周期。

### 3.1 训练、查询与回填

宿主分配四个 structured buffer：训练输入、训练目标、查询输入、查询输出，以及两个 uint32 样本计数器。
官方输入结构为 `position(float3) + normal(float2) + viewDir(float2) + albedo(float3) + roughness(float)`，输出为 RGB radiance。
位置按缓存域归一化，法线与视线使用 SDK 的极坐标编码；buffer stride 与容量在 adapter 中验证。

执行顺序：

1. 清理本帧计数器，追踪选定训练路径并生成去调制目标，同时记录有效查询与累计 throughput。
2. 在达到路径足迹/散射条件时提交查询；强镜面链继续传播到合适散射点，不能固定“第一跳必查”。
3. 给 GPU 写入建立 UAV/资源状态依赖，调用 `ffxDispatchDescRadianceCache`。
4. SDK 同次调用先推理、后训练；预测与已追踪贡献按 throughput 回填到对应光照信号。
5. 需要 self-training 时采用官方两次调用：训练路径尾部 query-only → 组装目标 → 正式查询 + training → 回填。

训练样本预算初始按官方建议的渲染像素数 1%–5% 配置，并明确区分训练路径数与产生的训练顶点数。
每次原子分配后检查容量；交给 SDK 的计数不能超过已分配数组。推理、训练及网络状态跨帧不能无序重叠。
`RESET` 在推理/训练前执行且不会清计数器；清计数器 flags 在推理/训练后执行，不能混淆两者。
默认 learning rate 0.002、weight smoothing 0.99 是 SDK 起点，质量预算应同时控制训练数、查询足迹和追踪长度。

### 3.2 Minecraft 世界与光谱边界

- 使用稳定的有限缓存域和 `worldEpoch/cacheEpoch`。相机相对原点变化只改变坐标变换；不能每帧改变归一化包围盒却继续使用旧网络参数。
- 离开缓存域触发明确的域切换/重置。区块编辑、材质/光照突变和 LoD 表示变化带版本，由缓存策略判断重训或重置；此域只限定模型支持范围，不声明世界在域外为空。
- `unknown` 表示尚无可靠几何覆盖。训练路径遇到 unknown 不可把黑色或环境近似伪装成真值；排除无效训练样本，并在查询结果中保留估计来源和覆盖置信度。
- 环境近似可以参与逐路合成降级，但与真实天空命中和网络训练真值分开。缓存不能自动修复缺失几何导致的光泄漏。
- 当前 RC 接口预测 RGB，并不预测随波长变化的光谱辐射。神经光谱输运必须明确 RGB 投影/重建的近似边界；要求光谱正确的路径不能把 RC RGB 当作无偏光谱结果。

保留关闭缓存、查看无降噪信号和训练覆盖的诊断模式，用于区分输运误差、缓存偏差与降噪残影；它们不改变正式 profile 的能力要求。

## 4. native 接线与 SM6.9 / Work Graph

MCDR 统一加载 AMD loader 与独立 effect DLL，为 SR、RR、RC、FG 分别管理 context。
使用 `ffxQueryDescGetVersions` 按 device/effect 枚举支持版本，记录最终选中版本；失败时给出具体能力缺项。
不从显卡厂商字符串或“DLL 存在”推断可用，也不静默选择语义不同的效果。

RR 的运行库是 `amd_fidelityfx_denoiser_dx12.dll`，RC 是 `amd_fidelityfx_radiancecache_dx12.dll`，配合 `amd_fidelityfx_loader_dx12.dll`。
2.4.0 loader 支持在自身目录旁寻找 effect DLL，适合项目现有版本化 native 解包目录；加载使用受控绝对路径。
当前普通包/本地测试包的 vendor 分发规则继续适用；本设计不自动把开发运行库加入发布包。

每个 adapter 只接收所属领域的图资源与不可变帧参数，负责 SDK 结构封装、状态转换、descriptor heap 恢复和错误映射。
RenderPipeline 统一编排训练/推理/降噪/重建/呈现；JVM 仍只通过 `MCDR_ABI` 提交事实与配置，不增加逐 SDK 的 JNI/FFM 调度。
context 重建先准备候选，在帧边界成功发布；旧 context、模型和输入输出资源按实际 GPU 完成 fence 释放。

内置 HLSL 的 Work Graph 负责路径任务、训练/查询记录生成和回填任务；AMD SDK 调用位于图执行之间的 host 调度节点。
不能在 HLSL node 内调用宿主 `ffxDispatch`。跨 WG/SDK 共享缓冲时显式表达 UAV 屏障、计数器容量和执行顺序。
Cooperative Vectors 与 SM6.9 用于本项目的自有神经着色/输运优化，AMD RC 的公开宿主着色器要求是 CS_6_6；这不意味着 SDK 内部网络会因为宿主改用 SM6.9 自动改变实现。

## 5. 支持范围与实施依赖

| 功能 | 本次核对的公开支持要求 |
| --- | --- |
| FSR RR 1.2.1 | RX 9000 系列或更新；当前 SDK 的 RR 不支持 Vulkan |
| FSR RC 0.9.0 Preview | GPUOpen 产品页要求 RX 9000 系列或更新；当前 RC 不支持 Vulkan；实际仍按 SDK 查询/创建结果判断 |
| ML FG 4.0.2 | Windows 11、RX 9000 系列或更新；按文档核对 D3D12 Agility/驱动 |
| FG driver-assisted pacing | RX 9000 系列或更新、Adrenalin 26.10.1+ |
| FSR Upscaling 4.1.2 | 支持范围已扩展到 RX 6000 系列和部分 APU，不能套用 RR/RC 的统一硬件门槛 |

工程实施按依赖完成：统一 LightingSignals/Guides 和 SDK 生命周期 → 接 RR 与现有 SR 合成边界；路径追踪的训练/查询/有效覆盖契约 → 接 RC；统一真实帧、UI 与呈现所有权 → 接 ML FG/新 swapchain。
RC 与 NRC 同属高优先级输运工作。先定义全部接口，再按依赖实现；不把 SDK 输入契约推迟到画面出问题后补充。
验收包括真实相机/物体运动、世界切换、动态分辨率、透明与发光、unknown 覆盖和缓存域切换。分项记录 GPU 时间、训练/查询数和显存；不能只凭 FPS 或一帧静态图判断。

## 6. 官方依据

- [FSR SDK v2.4.0 release](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/tag/v2.4.0)
- [Ray Regeneration 1.2.1：接口、分离降噪、guides、硬件要求](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/docs/techniques/denoising.md)
- [Radiance Caching：训练、查询、数据布局、执行顺序](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/docs/techniques/radiance-cache.md)
- [Radiance Cache 0.9.0 API](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/radiancecache/include/ffx_radiancecache.h)
- [GPUOpen Radiance Caching 产品与硬件要求](https://gpuopen.com/amd-fsr-radiancecaching/)
- [FSR API：effect DLL 与版本选择](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/docs/getting-started/ffx-api.md)
- [ML Frame Generation](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/docs/techniques/frame-interpolation-ml.md)

## 7. 当前源码导航

| 入口 | 当前职责 |
| --- | --- |
| `render/reconstruction/SuperResolution.kt` | 已实现的 SR 业务；RR/RC 不能直接并入其后端枚举 |
| `render/reconstruction/ReconstructionFrameInputs.kt` | 当前 SR 共享输入与捕获资源 |
| 相邻 MCDR `src/render/reconstruction/d3d12_fsr.cpp` | 当前 FSR SR adapter，可复用 runtime/资源包装经验 |
| 相邻 MCDR `src/render/presentation/d3d12_fsffg.cpp` | 当前 FSR FG/presentation 接线 |

## 8. 实施工作包

| 工作包 | 明确交付 | 依赖与验收 |
| --- | --- | --- |
| AMD 运行时与能力目录 | loader/effect 的统一加载、按设备查询版本、配置所需能力和确定错误；SR/RR/RC/FG 各自 context | 记录实际 SDK/driver provider 版本；显式选择不支持的组合时拒绝启用，不替换算法 |
| 光照与 guides 契约 | 分项 LightingSignals、线性深度、2.5D motion、材质类别与去调制因子 | Minecraft 采集和输运保留业务语义；AMD 私有编码只在 adapter 边界生成 |
| RR 重建分支 | RR adapter、重调制/合成、所选 SR、独立历史与失效处理 | 与 NRD / DLSS RR 互斥，世界与 UI 分层，动态分辨率与真实物体运动验证 |
| RC 输运分支 | 训练与查询记录、容量限制、推理/训练 dispatch、throughput 回填、缓存域版本 | 与 NRC 共用缓存业务契约；unknown 不进入真值训练；GPU fence 管理模型和缓冲寿命 |
| ML FG 与呈现 | 真实帧标识、输入租约、UI 合成、swapchain/pacing adapter | 一个呈现 owner；交换链切换和退出前结算所有消费；生成帧不推进模拟历史 |
| 性能与质量诊断 | 每 effect 的 GPU 时间/显存，训练查询计数，分项信号和缓存禁用诊断 | 所有计时明确 CPU 录制、提交完成与 GPU 完成区别；异步采样不得为取结果串行化每帧 |

原版 D3D12 的架构与基础缺口另见 [原版路径架构审查](../misc/vanilla-d3d12-architecture.md)。
共享的命令提交、资源状态和租约基础先满足这些契约，Redstone adapter 不再复制各自的提交/等待/呈现状态机。
