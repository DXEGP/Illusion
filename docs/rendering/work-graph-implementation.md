# 原版 Work Graph 实施记录

本页跟踪 [架构方案](vanilla-work-graph-plan.md) 的实际实施状态。范围限定光栅、mesh、AA/SR 与呈现；目标设计不能作为实现完成的证据。

## 分工与依赖

| 工作包 | 执行者 | 修改边界 | 状态 |
| --- | --- | --- | --- |
| A1 提交、回调、退出 | 6.1sol `wg_submission` / `checked-dlss-shutdown`，主代理集成 | encoder 提交与关闭、readback 完成队列、native 提交等待 | checked device/surface teardown、Present 完整收尾与失败传播已接线；生产函数失败注入通过；最新 native Release 构建通过 |
| A2 上传、描述符、格式 | 6.1sol `wg_resources`，主代理集成 | transient memory、native 描述符分配、格式与 footprint | upload fence 退休、descriptor 扩页与格式修复已接线；compute 720 像素、graphics 600 像素实际探针通过；完整场景/并发验收仍未完成 |
| B 真实 SPI 批量入口 | 6.1sol `wg_batch` / `bindings`，主代理集成 | RenderPassBackend、native draw 与批次录制 | 四组 SPI 已接入 bulk ABI；绑定数组复用；Naga manifest、b0/space1 root constants、FFM 和 foreign-state 恢复已接线，GPU 红/绿 draw 通过 |
| C 帧协调与业务迁移 | 6.1sol `frame` / `frame-repair` / `scheduler-next`，主代理统筹 | 帧输入、执行器、重建与呈现边界 | 显式帧 ABI、context 独立状态和真实帧号已接入；SMAA/TAA/SR/DLSS world-post 使用生产资源声明；全帧 raster/terrain/UI/Present 资源导入仍未交付 |
| D WG 运行与编译 | 主代理协调共享接口，`gpu-next` / `gpu-input-repair` | capability、program/instance、backing memory、编译与派发 | CPU-record 与安全 buffer-handle GPU-input、根布局、总 backing 预算和 GPU event 已实现；独立 harness 的实际 GPU producer→consumer 全量读回通过，不能等同游戏路径已采用 GPU-input |
| E 地形工作图 | 6.1sol `terrain`，主代理集成 | 范围输入、可见表、间接 mesh 绘制 | 生产五节点、GPU culling、ExecuteIndirect/visible MS、零可见和提前释放租约探针通过；最终 DLL 重跑通过；场景验收与性能测量仍待完成 |
| F 帧准备工作图 | 6.1sol `frameprep` / `reconstruction-next`，native 租约代理与主代理集成 | tile 分类、完整 guides、AA/SR/FG 输入契约 | reactive/responsive 三节点、真实帧历史、相机与近似实体平移、保守 VRS 深度快照及 SDK 专属源租约已接线；完整物体/变形 motion、composition/VRS WG 输出和 SDK 硬件验收未完成 |
| G 缓存与性能整理 | 主代理统筹 | 编译缓存、状态/绑定减少、测量与文档 | DXIL 缓存损坏恢复探针通过；BindingPacket 复用及 foreign-state 重绑已接线；统一预热与实际游戏性能测量未完成 |

## 协作规则

- 保留本轮开始前已有的源码修改；不创建提交，不重置工作树。
- `MCDR_ABI.kt`、公共 C ABI、模块接口和工程编译项由主代理统一协调，子代理先报告所需签名和状态字段。
- 同一 native 设备文件按提交、描述符、draw 函数范围分别修改，禁止整文件覆盖。
- 每项交付报告实际接线、编译/探针结果及仍未验证的运行行为，不以声明或空壳类判定完成。
- 全量 JVM/native 构建由主代理串行协调；使用本机 JDK 和现有 VS2026/依赖，不下载工作区 JDK。
- shader 编译、布局、边界/容量与生命周期的验证随实现完成；游戏画面和 PIX/Nsight 性能结论必须有对应实测。

## 验证记录

主代理最新 native Release x64 构建 `integration-native-checked-shutdown-final.log` 通过，包含 checked FG/DLSS teardown、设备所属 Streamline shutdown 回调及 Present 最后一次 surface 访问后的完成发布。生产 DLL、generated resources 与 run 目录产物 SHA256 一致，为 `1894bc4e12d8e29caa49c236ed4d764057a136c1fc48eba87514203e69229ed9`。Kotlin/Java 联合编译、processor staging 与配套 ABI 5 已验证。Naga processor 全套测试/Release 构建是本轮已有记录，并非每次 native 重建均重跑。

游戏实测已覆盖 SMAA 原版世界、地形/手部/UI、RTSS、窗口缩放及正常保存退出。Streamline DLSS SR 已实际创建并出图；首次缩放暴露 JVM 把 native 的成功清除返回值 `0` 错当失败，已修正 `DLSSOutput.release` 并调整原先使用错误假定的探针，修正后运行记录单列。F3+T/ESC 自动输入没有得到可靠确认，不能据此宣称资源重载/暂停测试通过。PIX timing 捕获停在收尾，只留下未验证的部分文件；GPU attach 返回 `PIXTOOL17`，要求以 GPU capture 模式启动。没有有效 PIX/Nsight 分析或同场景性能比较结论。

### 无世界菜单黑屏修复（2026-11-12）

此前世界出图的证据没有覆盖标题界面。`VanillaFrameCoordinator.beforeGui()` 在 `IDLE` 分支直接返回，跳过了 `D3D12GUISplit.prepareForGui()`；主目标附件受渲染缩放影响时，panorama、GUI 和 blur 已通过 mixin 绘制到窗口尺寸的 GUI 目标，但 `preparedThisFrame` 没有置位，最终 Present 仍选中被清成黑色的主目标。RTSS 位于 Present 叠加路径，因而黑屏时仍然可见。

修复使无世界帧在清理重建输入后同样执行 GUI 准备，再交给原版 `GuiRenderer.render()`。菜单帧不创建世界帧身份，也不运行世界 AA/SR 或进入 native world-post 协议；GUI 准备、实际绘制目标和最终呈现源在同一帧内保持一致。

- 相同 50% 渲染缩放、DLSS 关闭配置下做修复前后实机对照：主目标 `427×240`、GUI 目标 `854×480`；旧代码持续全黑，修复后标题背景、文字和按钮正常。
- 修复后的同一会话覆盖设置页 blur、多次窗口尺寸变化与 GUI target 重建，正常关闭并完成 surface/device teardown，Gradle 返回 `BUILD SUCCESSFUL`。Kotlin/Java 编译及当前 native staging 随运行任务完成；本修复不修改 native。
- 本地证据：`game-menu-scale50-before-fix.log/.png`、`game-menu-scale50-fixed.log/.png`、`game-menu-scale50-resize-settled.png`，均位于 `build/reports/workgraph/`。测试前的 options/config 已备份，测试后按原文件恢复并核对 hash。
- 恢复本次测试开始时的原配置（DLSS 开启、普通渲染缩放记忆为 100%）后，再次不带 quick-play 启动，标题界面正常出图；`game-menu-final-original-config.log/.png` 记录画面及正常关闭，Gradle 返回 `BUILD SUCCESSFUL`。
- 另一次进入世界的日志 `game-client-menu-original-config.log` 在 `selected post adapter did not execute` 后出现 `GUI phase is out of order or stale`。该 world-post 失败仍是独立待修问题，不属于本次菜单修复的通过项，不能宣称完整世界切换/SDK 矩阵通过。

- `NodeLibraryProbe.java`：通过真实 DLL/FFM 编译 `lib_6_9` node library，无 `main` 入口要求，DXIL 容器校验通过。
- `WorkGraphProbe.java`：本机适配器报告 WG Tier 1.0；创建 state object、派发两条输入、拷贝并等待 fence 后读回正确值。程序句柄在提交前释放，录制租约保持 program/backing memory 到消费完成。
- `TerrainNodesGpuProbe.java`：生产五节点、初始化/终结 compute、ExecuteIndirect 与 visible MS16 在本机 GPU 上通过；验证 cull、零可见、间接参数、输入/批次/程序提交前释放。
- Kotlin world-pass 和实体采集 mixin 编译通过，handler 参数与实际字节码签名一致；实际客户端成功织入并进入世界，不据此证明动态实体 motion 画质。
- ABI 提升至 5：异步渲染关闭改为返回成功状态，并新增 WG 与批量 draw 出口，JVM/native 必须配套部署。
- WG backing memory 按 program 全部在途实例合计限制预算；资源录制租约同时固定 native 对象，防止 COM 保活期间被旧 buffer pool 回收复用。
- E 接口及主调用集成后，Kotlin/Java 与 native Release 联合编译通过。生产 WG 五个节点及 visible MS 的 16/32/64 变体经 DXC 编译通过；真实 GPU 节点与间接光栅探针通过；实际世界地形可见。透明、水体、动态实体及完整相机路径仍需场景矩阵验收。
- `UploadArenaProbe.kt` 使用生产分配器，验证同录制超容量扩页、在途页不可复用、fence 完成后复用、usage 分离、大页退休、对齐溢出和关闭后不查询 context；全部通过。
- `DescriptorGpuProbe.java`：两个同时录制的 context 各绑定 90 个独立 UAV，反序提交后 720 个像素正确，覆盖 compute descriptor 扩页和紧凑行回读；graphics descriptor 使用独立的 600 像素探针验证。
- `FrameContractProbe.java` 修复后通过：独立 context、不可变配置/尺寸、未完成帧拒绝覆盖、AA 关闭时 GUI 转移、reset 隔离。
- `FrameprepGpuProbe.java` 通过 1×1、17×19、129×131 的全部像素、空 tile、边缘 tile 与提交前输入/输出/owner 释放。探针同时发现并促成修复紧凑 R8 奇数行宽被错误拒绝的问题。
- `DxilCacheProbe.java` 通过截断缓存、错误容器长度、越界 part 表的重新编译和原子恢复；无临时文件遗留。
- `RootConstantsGpuProbe.java` 通过 b0/space1 非零常量红/绿两次实际绘制、foreign compute 后状态恢复、短数据与超限声明拒绝；Naga 新增声明/反射/冲突测试通过。
- `SchedulerProbe.ps1` 与 `SchedulerExecutorProbe.ps1` 通过：前者验证依赖模型，后者抽取实际生产声明/实现并以 fake backend 验证适配器、精确 subresource 状态、失败毒化和状态恢复。它们不是 GPU barrier 或实际 SDK 的硬件证明；生产模块已随最终 native 构建集成。
- `PresentationLeaseProbe.py` 与 `StreamlineInputProbe.py` 最终日志通过；测试编译实际源函数，以确定性的 SDK/GPU fakes 检查租约、延迟读取、错误/取消/销毁和 volatile-copy 顺序。不能写成 FSR/XeSS/Streamline SDK 硬件验收通过。
- 独立 `MCDRProbe.dll` 的 GPU-input 探针实际验证 GPU 写出的 24 字节描述符、14 条记录、每个 consumer buffer 的全部 64 words 和 sentinel、zero-record、两个 context 反序提交、同一 graph 及输入/输出/producer 提交前释放；50 个生产 ABI 与 18 个 private bridge 无效输入分别通过。该 harness 使用同源 device/WG 实现和私有 bridge，生产 DLL 不含 test export。
- graphics descriptor 提前释放曾在 command-list Close 触发驱动 AV；隔离为 graphics pipeline 生命周期问题。native 为每个 recording 去重持有 pipeline wrapper/root/PSO 引用至 slot fence 退休后释放，修复实际早释悬空。此前生产构建已验证 600 像素；最终 DLL 的 `graphics-descriptor-final.log` 再次通过 304 slots/context、双 context 反序提交、8 次 foreign compute 状态恢复及 pipeline/资源提前释放。
- 最终生产 DLL 的 `terrain-nodes-gpu-final.log` 与 `frame-contract-final.log` 通过：前者覆盖 init→WG→finalize→ExecuteIndirect→visible MS、cull/zero-visible/early release；后者覆盖独立 context、不可变身份、重复/乱序拒绝和 reset 隔离。地形相邻重复 UAV barrier 已移除，初始化/终结边界保留；未据此推断性能收益。
- `PresentRetirementProbe.py` 抽取实际生产 Present 收尾及等待函数，确定性暂停在 post-Present Signal：关闭不能越过最后一次 surface 访问，停止标志不能伪造完成，失败 Signal 不发布 fence 且 checked wait 返回失败。
- `CheckedDlssShutdownProbe.py` 抽取实际 feature replacement、checked/legacy shutdown、device close 和创建前置检查，覆盖清标签、FG disable、feature/parameters 释放、NGX shutdown、Streamline shutdown/回调缺失与重试。失败保留尚未释放的 SDK/纹理/设备所有权，不重建替代设备或重复释放已完成阶段。SDK 行为为确定性 fakes；实际队列完成仍需实机证据。
- 最新 `1894bc4e…` 生产 DLL 再次通过 `graphics-descriptor-checked-shutdown-final.log`：600 像素、双 context/反序提交、foreign compute 状态恢复、pipeline/资源提前释放与真实 device teardown。

## 图形常量契约

使用 [图形绑定契约](graphics-bindings.md)。native/JVM ABI 为 5，必须配套当前 Naga processor；不使用旧 DLL 的静默兼容路径。

## 尚未完成的目标

### 当前推进顺序（2026-11-12）

| 工作包 | 实施代理 | 交付边界 | 依赖与验收 |
| --- | --- | --- | --- |
| H 声明式资源调度 | 6.1sol `scheduler-next`，主代理集成 | world-post 的 `PassResourceUse`、精确范围依赖/UAV 顺序、外部状态恢复已进入生产 SMAA/TAA/SR/DLSS；模型与生产源 executor 探针通过，最终 native 编译通过 | 继续导入 raster/terrain、输入准备、UI/Present 与 provider 私有资源；仍需实际运行的 barrier/版本验证 |
| I 重建输入与历史租约 | 6.1sol `reconstruction-next`、presentation/Streamline 代理与主代理 | 真实帧时钟/身份、历史 reset、相机+近似实体平移、VRS 快照、SDK 专属 copies/源读取租约已接入；focused Kotlin 与生产源 fake 探针通过 | 缺精确物体/变形 motion、composition/VRS WG 输出、SDK 硬件生命周期验收；不能把相机/平移近似称为完整 guides |
| J GPU 输入与绑定压力 | 6.1sol `gpu-next` / `gpu-input-repair`，主代理构建 | 实际 GPU 描述符/记录/完整 consumer 与 zero-record 验证通过；50+18 无效用例通过；pipeline 所有权修复后 graphics 600 像素最终生产 DLL 重跑通过 | GPU-input 接口信任 native GPU producer 写出的内部地址/数量/stride；并非任意 GPU 数据沙箱；游戏生产使用与性能仍待测 |
| K 集成与游戏验证 | 主代理，6.1sol 独立集成审查 | native Release、JVM/staging、生产 GPU 探针及退出失败注入通过；SMAA 世界/resize/退出实测；DLSS 实际出图及清除返回值修复后退出实测；无世界菜单黑屏已修复，50% 缩放对照及菜单/blur/resize/退出实测 | world-post adapter 失败后的 GuiReady 拒绝仍需修复；完整 reload/world/SDK 矩阵与 PIX/Nsight 比较仍需单列结果 |

H/I/J 代码已交接并由主代理集成；旧 worker 报告中的“等待共享签名/未 build/未实测”仅描述当时的检查点，不覆盖本页列出的更新证据。阶段顺序用于管理依赖，完整目标契约保持主方案定义。

当前工作树包含可执行的地形与 reactive/responsive mask 工作图、生产 world-post 声明调度、真实帧输入历史和 SDK 专属源租约。剩余全帧导入、完整 guides、composition/VRS WG 输出、预热和场景/性能验收见下文；不能把这些剩余项写成已经完成，也不能把已交付边界整体写成缺失。
- 任务正文通过本机 Codex CLI 的 6.1sol 执行通道传递；任务、共享契约、各代理状态与日志位于 `build/reports/workgraph/`。该目录是本地构建产物，长期完成状态以本页为准。

### A2 当前接线

- `D3D12TransientMemory` 使用按提交 fence 封存的可增长 upload page；映射 UBO 按 256 字节对齐，纹理 staging 按 512 字节对齐，单次大于页大小的分配不回绕覆盖旧页。
- native draw/compute descriptor page 在当前 command context 内按需增长；旧 page 通过 `recordingLeases` 保留到对应 slot fence 完成，SRV 表缓存同时校验 view 序列和 CBV 布局，容量不足在绑定前明确失败。
- 深度格式字节分类、`GetCopyableFootprints` 行距和紧凑 CPU 行到合法 D3D12 footprint 的 scratch copy 已接线；深度/模板部分 plane 拷贝在当前 SPI 无法表达时明确拒绝。
- 集成构建及 compute/graphics descriptor 实际 readback 已通过；匹配 Agility debug layer/GPU validation 是否开启须由对应日志确认，当前不作开启声明。实际场景由主代理验证；原始分工细节见 `build/reports/workgraph/resources-status.md`。
- texture-to-texture 完整 depth/stencil subresource 遍历全部 plane；buffer 拷贝仍拒绝当前接口无法表达的多 plane 数据。紧凑 R8/RG 等非四字节行宽通过合法 scratch footprint 加逐字节 buffer copy 实现，不额外要求行宽为四的倍数。

## H 生产调度范围与全帧导入边界

native `src/render/vanilla/d3d12_vanilla_frame.cpp` 的 `executeDeclaredWorldPost` 已执行资源声明规划，而非仅提供独立模型。SMAA/SR/DLSS 的既有出口由 adapter 生成声明，TAA 使用明确 scene/motion 参数。调度器处理重叠范围 RAW/WAR/WAW、需要的 UAV 顺序、精确 mip/layer/plane 范围、外部 final-state adoption 和 graphics/descriptor/VRS 缓存失效；旧 render pass 结算后重新查询实际状态。transition/adopt/end 失败会使帧不可继续，必须 reset。

身份是录制租约固定的实际 `ID3D12Resource*` 加本帧冻结 `resourceVersion`，不是新增的全局 backend generation。当前 outer-resource 调度覆盖 world-post 已知输入/输出；provider 私有历史、scratch、SDK 分配仍是不透明边界。地形与普通 raster pass、输入准备、UI、呈现尚未全部转成统一 `RasterPassPacket`/`PassResourceUse` 导入，不能声称整个原版 FrameGraph 已由声明调度器拥有。下一步应逐个导入这些真实调用点及其资源版本/副作用，而非重复实现第二套调度模型。

## I 输入语义、VRS 与 SDK 租约

- jitter/history 只在真实世界投影帧推进；重复/生成 Present 不推进模拟历史。world、尺寸、资源/配置版本和相机切换触发 reset，捕获身份冻结并拒绝重复消费。相机差值在 double 中计算再转 float；实体 dispatcher 捕获最终抽取位置，使用连续真实渲染帧位置配对。当前运动为相机重投影加近似实体平移，不含完整 part/bone、旋转/缩放、CPU-baked 顶点或变形历史及精确像素归属。
- 设备深度为 reversed D3D [0,1]；SDK jitter 为 `(jx,-jy)`，motion 为去 jitter 的 current→previous 像素位移，scale=1。移除了与该语义冲突的 provider jitter-cancellation 标志。当前 preExposure=1、无 exposure texture，不等于交付独立测光。FSR reactive 与 XeSS responsive 明确分离，未声称提供通用 composition mask。
- VRS 在 world 完成且 GUI/清深度前复制实际格式/尺寸的深度，在同一 shared context 上供下一真实帧读取。world、sequence、history generation、精确 camera/view/projection、尺寸/format 不兼容时强制 1×1；motion 缺失时使用明确的 depth-only 路径。它是保守的现有 compute SRI 路径，尚未交付 frameprep WG 的 VRS 输出，也不会影响已经完成的本帧光栅。
- native UI registry 和每次 recording 持有 wrapper/pool pin 与 COM 引用；raw-DLSS→FG 只传 color/depth/motion 三项精确 wrapper 租约，不复制整个 `recordingLeases`，避免把无关地形/WG/upload/descriptor 保留至 SDK 消费。应用 direct fence、CPU submit handshake 和 Present 返回分别处理，不能相互代替。
- XeSSFG 的所有应用输入（含 HUDLess/UI）使用 `ONLY_NOW`，由 SDK 在提供的列表录制拷贝，源租约保留至该列表完成。FSRFG 固定 `allowAsyncWorkloads=false`；本地 SDK 源码表明 HUDLess 插值读取和 UI 内部 double-buffer 拷贝在 gameQueue 上，应用源租约以 Present 之后 signal 的 gameQueue fence 退休。此 fence 只覆盖已证明的源读取，不证明 SDK composition/presentation 内部队列结束；disable、SDK drain 和 context Destroy 分别检查，失败保留 owner 供重试。
- Streamline SR 使用 local tags；成功 SR 写出后才发布 FG depth/motion/HUDLess 的 `eOnlyValidNow` copies，显式尺寸与实际状态配套。失败 tag/options/clear/disable 会使输入失效并阻止 Present；output 替换、world/no-world、resize、surface/device close 使用 checked invalidation。四个 caller wrapper、外部 output override 与 private fallback 均按实际录制保活。SDK copy/标签顺序已由实际源 fake probe 验证；实际 SDK GPU 执行和各内部呈现队列 drain 仍需硬件验证。
- 设备销毁通过 checked shutdown 统一清标签、释放 feature/parameters、NGX shutdown 与设备 owner 的 `slShutdown`；成功后才清纹理/COM、adapter API 缓存及 `DeviceContext`。每一步失败保留剩余 owner 供重试，旧 void wrapper 不作为销毁成功证明。world/no-world invalidation 在转换点先等待上一 CPU Present 完整收尾，避免并发修改 Streamline 当前帧标签。

## J GPU-input 信任边界与 pipeline 修复

生产 `dispatchGpuBuffers` 及 handle ABI 校验 schema、device/context、input buffer/offset/span、alignment、binding 范围、UAV 能力及 input/output alias，并将资源固定到录制完成；ABI 不接受任意 GPU virtual address。GPU 写入的 NODE_GPU_INPUT 内部记录地址、count 和 stride 属于可信 native GPU producer 契约。CPU span 校验无法读取或隔离未回读的 GPU 描述符，50+18 无效用例验证的是 host-visible 边界，不是恶意 GPU descriptor 的安全性。

GPU-input 最终通过的关键修复是 harness 缺少与生产相同的 side-by-side `D3D12Core.dll`：旧路径实际仅 SM6.8，stage 正确 runtime 后同一 shader/schema 即通过。测试 targets 现自动 staging；state-object 失败诊断包含实际 HRESULT/SM 和可用 InfoQueue 信息。独立 harness 与生产源码共用实现，但其 PASS 不表示生产游戏已经把某项业务切换为 GPU-input。

graphics 提前释放缺陷的根因是录制中的 `currentPipeline` 及驱动所需 root/PSO 生命周期先于消费终止。修复使用 pipeline 的引用计数和每个 recording 的去重 lease；资源释放遵循 slot fence 完成和 command context 销毁顺序。实际 600 像素压力探针验证了修复，不据此推断 FPS 或 descriptor 分配性能。

## 精确剩余交付与下一依赖

1. **提交/退出场景验收（A/K）**：独立审查发现的 Present 收尾悬空访问窗口与 void DLSS shutdown 失败未传播均已修复并通过生产源失败注入。实机继续覆盖 SDK tag/disable/shutdown 故障、设备移除与重试；应用队列 fence 不代表 display-controller 或 SDK 内部呈现队列结束。
2. **全帧资源导入（B/C/H）**：在已运行的 world-post planner 上依次接入实际 raster/terrain、guides/mask、UI/Present 和 adapter-private 资源声明；明确版本、范围与 SDK 副作用，实际检查 barrier 和接续 draw。不可把 outer-resource 调度当作完整图编译器交付。
3. **完整 guides 与 frameprep 输出（F/I）**：先建立稳定 entity/part/draw 身份、previous/current transform/变形及 raster depth/coverage 关联，再实现精确动态物体 motion；独立定义曝光、composition、VRS WG 输出的 provider 契约、唯一写入及中性值，保留完整像素初始化。
4. **SDK/跨帧验证（F/I/K）**：在真实 FSR/XeSS/Streamline 配置上覆盖 no-world、换世界、resize、output/UI 替换、tag/disable/drain/Destroy 失败和重试；特别检查异步 Present 与主线程 invalidation 的串行关系。现有 fake probes 验证应用侧契约，不能替代这组 SDK 硬件验证。
5. **预热与性能（G/K）**：源/manifest/layout/DXIL/PSO/WG 版本化预热仍需交付。先完成固定场景、相机路径、配置与资源版本的正确性基线，再采集 CPU p95/p99、阶段 GPU 时间、上传字节/容量及 PIX/Nsight 证据。当前没有性能收益结论。

## 当前验证边界

已确认的构建、探针和场景见本页验证记录。SMAA 世界/缩放/正常退出、DLSS SR 出图及清除返回值修复后正常退出、无世界菜单/blur/窗口调整已有实机证据。world-post adapter 失败后的 GuiReady 拒绝、完整资源重载、世界切换和各家 FG/超分矩阵仍须分别验证。没有完整计划完成、完整 SDK 硬件生命周期通过或性能提升声明。生产 DLL、先前 DLL 与 standalone GPU-input harness 的产物分别记录。

本地证据（`build/` 为忽略产物，本页正文独立描述结论和限制）：[最终 native 构建](../../build/reports/workgraph/integration-native-final.log)、[最终 JVM/staging](../../build/reports/workgraph/integration-jvm-stage-final.log)、[地形 GPU](../../build/reports/workgraph/terrain-nodes-gpu-final.log)、[帧契约](../../build/reports/workgraph/frame-contract-final.log)、[graphics 600 像素](../../build/reports/workgraph/graphics-descriptor-final.log)、[GPU-input harness 报告](../../build/reports/workgraph/gpu-input-repair-status.md)、[presentation fake probe](../../build/reports/workgraph/presentation-lease-probe-final.log)、[Streamline fake probe](../../build/reports/workgraph/streamline-input-probe-final.log)、[集成审查请求](../../build/reports/workgraph/final-integration-review-requests.md)。
