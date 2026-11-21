# DLSS / DLAA 专项

DLSS 由 native 的 NGX/Streamline adapter 执行；DLAA 使用同一 provider，以显示尺寸直接做时域抗锯齿。共同输入见 [超分总览](README.md)，配置发布见 [渲染配置](../rendering/render-configuration.md)。

## 配置与准备

`DeepLearningSuperSampling` 提供开关、质量、preset、状态与持久化入口；用户操作更新 requested。渲染线程先 FG Prepare、再 SR Prepare，形成真实候选，组合 Commit 成功才发布世界附件和 applied，并同步 Option/UI；完整 Published 更新 persistent，save 通过 settingsToSave 读取它。尚未 ready 时请求留在 pending；同尺寸准备失败保留原配置。

SR Prepare 返回 int32 typed status 和独立 outToken。152 字节的 SR_DISPATCH 包含真实的 256 字节 RASTER_CAMERA 指针；Streamline 候选使用独立 frame0 allocation token，正式帧通过 ++gReflex.frameCounter 从 1 开始编号。未知完成结果保留候选 owner 和资源；完整状态及关闭顺序见 [配置契约](../rendering/render-configuration.md#sr-prepare-与关闭)。

主菜单选择 SR 返回 AwaitingWorld，结束 UI 回调并保留 requested/pending，进入世界后继续应用；成功后才保存。resize 会先确保新窗口有可渲染的临时 NONE 配置，同尺寸 SDK 拒绝后暂停自动重试。小窗口可按 caps 最小输入抬高 Applied 的实际比例，用户质量档位保持原值。

| options.txt 键 | 含义 |
| --- | --- |
| `dxrMcDlss` | DLSS 开关 |
| `dxrMcDlssQuality` | 质量偏好 |
| `dxrMcDlssPreset` | 模型 preset |
| `dxrMcAaMode` | 3 为 DLAA |

这些值通过 settingsToSave 保存 persistent：首次读取来自批量加载后的磁盘配置，之后只由完整 Published 更新，临时 resize NONE 配置不覆盖它。DLAA 与 DLSS-G 对 DLSS 的要求、TAA 排除和比例规则由 RenderConfiguration 归一化，不保留逐帧互锁 setter、旧 DLL 兼容模式或门面间交叉写入。

## 质量与 preset

| 配置档位 | 名字 | 比例 |
| --- | --- | --- |
| 0 | ultra_performance | 34% |
| 1 | performance | 50% |
| 2 | balanced | 59% |
| 3 | quality | 67% |
| DLAA | native AA | 100% |

34% 避免整数取整低于 Ultra Performance 的三分之一最小宽度；59% 与 67% 同样需要按真实像素尺寸校验。DLAA 的 SDK 档位由准备入口映射，不能把配置下标当成 NGX 枚举直接透传。

| UI 顺序 | preset | native 值 |
| --- | --- | --- |
| 0 | default（推荐 M） | 13 |
| 1 | J | 10 |
| 2 | K | 11 |
| 3 | L | 12 |
| 4 | M | 13 |

`default` 现在由项目明确选择 M，native 值从 SDK 自选的 `0` 改为 `13`。UI 下标、`dxrMcDlssPreset` 键和磁盘中的 `default` 名称保持不变；显式 J/K/L/M 仍分别使用 `10/11/12/13`，读取旧数字配置时 `0` 仍表示 default、`13` 仍表示显式 M。推荐项与显式 M 共用同一个模型值，候选准备和冻结帧都通过 `DeepLearningSuperSampling.renderPreset` 映射。

本版运行库的真实离线 DLAA 重放中，J/K 对部分颜色变化长时间保留旧色，L/M 能较快跟上，因此项目推荐 M。L/M 的 GPU 耗时取舍需按实际显卡、分辨率与场景比较。

preset 是 feature 创建输入，修改后随新候选准备和 Commit 生效；失败保留现役 feature。普通 SR 的候选 Streamline viewport 使用 2..4，现役 viewport 随候选 Commit 发布，FG 使用 viewport1。SR tags 与 FG tags 在各自 viewport 和对应真实帧中管理。

## 每帧输入和输出

世界绘制前冻结 VanillaFrameInputs；LevelRenderer 和 AfterLevel 完成后，JVM `VanillaWorldOutput` 分配世界输出所需资源，提交颜色、重建输入拥有的同源 depth/motion pair、尺寸、身份与后置锐化参数。pair 的云/OIT 规则见 [超分总览](README.md)。native `prepareWorldOutput` 选择本帧 consumer，生成曝光并发布 PreparedFrame，通过统一 SR registry 执行 DLSS，随后输出 SceneDisplay。

native 从冻结帧生成 NGX/Streamline 常量。current/previous 使用同一当前相机参考原点，原点差先以 double 计算；jitter 为像素值 `(jx,-jy)`，motion 为去 jitter 的 current→previous 像素位移，scale=1。缺失真实 previous、motion 或镜头元数据时不伪造 SDK 输入。

普通 vanilla 使用编码 LDR、preExposure=1；曝光为实际 `1×1 R32_FLOAT` 纹理。K 模型不支持 current-color bias，生产路径已移除该输入。相机跳变、世界/版本变化和帧不连续的历史判定由 native 统一拥有，SDK 成功后才推进重建历史。

输出纹理归 `VanillaWorldOutput`，具有显示尺寸、与世界颜色兼容的格式和 STORAGE/纹理读取/拷贝/渲染用途。SDK 输出与 SceneDisplay 是独立纹理；原生把 DLSS 结果转入 SceneDisplay 时执行后置锐化，强度为 0 时普通 blit。GUI 只在独立 UiDisplay 绘制，DLSS 不再直接写 GUI，也没有 DLSSOutput 交接 owner。

纹理和 provider 的退休遵循实际命令与 SDK 租约：先结束对应读取、清标签和释放 provider，再关闭所属资源；fence 超时保留 owner。resize、关闭、换世界和取消沿同一生命周期处理。

## 可用性与验证范围

native 能力查询给出适配器、运行库和 provider 的真实可用性及原因；设置界面读取该结果。DLSS/Streamline DLL 不随发行包分发，开发环境可从本地 SDK/runtime 取得；发行环境缺少运行库时如实显示不可用。XeSS、D3D12Core、DXC 和 DXIL 的打包约束见项目打包文档。

逐帧状态查询读取设备创建时的 RTX 能力结果和当前 DLSS 会话，不重置能力缓存、不重跑 `GetFeatureRequirements`。该接口在本机可能持续返回无法确定；实际启用继续由当前运行库与所选 provider 的准备结果决定。查询失败不会触发每帧重新登记运行库目录或重复输出设备初始化日志。

DLSS/SR 启用、resize 后的动态运动质量和性能仍按对应实际场景确认。

## 文件导航

路径相对 `src/main/kotlin/io/github/dxegp/`。

| 文件 | 职责 |
| --- | --- |
| `render/reconstruction/dlss/DLSS.kt` | DLSS/DLAA 设置、质量、preset 与状态 |
| `render/config/RenderConfiguration.kt` | 唯一配置状态与归一化 |
| `render/config/PreparedRenderConfiguration.kt` | SR/FG 真实候选与组合发布 |
| `render/frame/vanilla/VanillaWorldOutput.kt` | 输出纹理、曝光资源与事实 |
| `render/presentation/D3D12SceneDisplay.kt` | 世界输出与晚期场景 |
| `render/temporal/D3D12Temporal.kt` | 实际投影 jitter 与冻结相机来源 |
| `d3d12/ffi/MCDR_ABI.kt` | 唯一 FFM 绑定及 typed SR Prepare 结果 |

native 对应 `src/render/reconstruction/d3d12_dlss.cpp`、`d3d12_sr.cpp`、`d3d12_prepared_reconstruction.cpp` 与 `src/render/vanilla/d3d12_vanilla_frame.cpp`。
