# MCDR 的 FFM ABI 层

本文只讲一件事：**JVM 侧怎么安全地调用 MCDR 这个 C++ DLL**，以及调用时必须遵守的用法与边界约束。

编译器另外使用 `illusion_shader.dll` 的三函数 C ABI，由 `MCDR_ABI.D3D12ConvertShader` 经内部 `NagaLibrary` 调用；
JSON/manifest 归 shader 业务适配层所有，FFM 只传输数据。必需资源解包、版本和内存所有权见 [着色器接口](../shaders/README.md#4-资源绑定)。
该库不属于 MCDR 导出计数，也不修改 `MCDR_Layout` 的结构体布局；`mcdr_binding_slot.texelFormat=-1` 表示 RAW SRV。

三个参与者：

| 层 | 文件 | 角色 |
| --- | --- | --- |
| 契约 | `MCDR\include\mcdr_abi.h` | 唯一真相：`extern "C"` 导出、结构体定义、错误码约定 |
| 布局 | `Illusion/src/main/kotlin/io/github/dxegp/d3d12/ffi/MCDR_Layout.kt` | 结构体的 `MemoryLayout` 镜像 + ABI 校验 |
| 句柄 | `Illusion/src/main/kotlin/io/github/dxegp/d3d12/ffi/MCDR_ABI.kt` | `downcallHandle` 绑定与每个导出的调用封装 |

适用范围：Java 25 / Minecraft release-26.1 起 FFM 已退出预览。**本项目禁止 JNI**（`System.loadLibrary`、`external fun`、`RegisterNatives` 一律不许出现），native 侧也不许再提供 `JNIEXPORT` 入口。

导出数量随实现变化，不以历史计数作为 ABI 一致性的证据。校验对象是当前头文件、JVM 绑定与运行中 DLL 的版本和布局。

当前源码 ABI 为 31。`mcdr_dx12CreateOitLaneOperationPipelineV1` 创建通过源码认证的原版 depth copy、bounds copy 和 bounds cull PSO。调用同步读取 pipeline descriptor、操作类型、程序代际和 cull 的实际 Projection binding index。PSO 保存认证结果。CPU 结构布局保持原样。该导出仍存在，但正常帧没有调用方，属于待删的残留面。

`mcdr_dx12BindTerrainTileEnvelopeV1` 在注册地形 stage 前，使用同一路由的根签名创建 envelope compute PSO。DXIL 在该调用内同步消费，JVM 用 confined Arena 按实际字节数分配。route 持有 PSO，录制租约保留 route 到提交 fence。

`mcdr_dx12VanillaRegisterWorldDepthV1` 在世界帧开始后、首个世界深度 clear 前同步登记实际 D32 纹理。`mcdr_dx12VanillaArmWorldDepthSeedV1` 接收同一 context、事件上限和共享预算。`mcdr_dx12VanillaSealWorldDepthSeedV1` 在 `executeOit` 入口封口。封口返回 1 表示 journal 可读，0 表示未开启，负值报告具名失败。这两个 depth seed 导出仍存在，但 JVM 已不再 arm 或 seal，正常帧恒为「未开启」。

OIT records 的 CPU 描述只有 capture 与 resolve 两条：Resolve 使用 schema 3，terrain Begin/metadata/draw 使用 schema 2。执行流（V1/V2）、operation receipt、terrain stage receipt/read set、lane 管线与 normalization 的导出与布局镜像已在当前源码中删除，`OitResolveRecordsV4` 也一并移除。Resolve V3 和世界输出 schema 3 保持原布局。`copyNative` 通过 `-PmcdrNativeRoot=<MCDR 工程目录>` 选择编译产物，通过 `-PmcdrDependencyRoot=<SDK 依赖目录>` 选择运行库和许可证来源。打包时核对内嵌 DLL 与所选 Release 输出的 SHA256。

FFM 不读取业务配置。JVM 计时开关由诊断域的 `RenderProfiling.enabled` 读取，命令和批次路径保留动态配置读取；配置到 native 的发布仍由 `D3D12Settings.pushToNative()` 显式执行。

## 1 三层结构

```
MCDR\include\mcdr_abi.h            契约：extern "C" 平铺导出 / 结构体 / 错误码
        │
        │  SymbolLookup.libraryLookup(dllPath, ARENA)
        │  mcdrABIVersion()  ── 版本握手
        │  mcdrABILayout()   ── 运行中 DLL 的字段偏移表
        ▼
MCDR_Layout.kt                     布局：MemoryLayout 镜像 + byteSize 断言 + 偏移比对
        │
        ▼
MCDR_ABI.kt                        句柄：downcallHandle + 每导出的封装
        │
        ▼
MCDR_PipelineDesc.kt               打包器：把 Kotlin 对象铺进 arena
MCDR_MeshPipelineDesc.kt
```

### 1.1 契约层：`mcdr_abi.h`

`mcdr_dx12PrepareFramePrograms(context, generation, flags, candidateOut)` 为同代 frameprep/display/OIT 构造共同候选，成功返回 `1` 和非空独占句柄，失败保留运行态且将输出置空。`mcdr_dx12CommitFramePrograms(context, candidate)` 先验证三个域，再交换它们的 owner；成功消费候选，失败保留候选供调用者关闭。`mcdr_dx12AbortFramePrograms(candidate)` 释放未发布候选，接受空句柄，关闭后不再访问 context。非空句柄只交还一次。

这三个入口使用 ABI 15 的具名结果分类；JVM 的 `FrameProgramsException.failure` 区分请求无效、编译失败、忙碌、候选过期、context 不符和提交结果未知，未识别的返回值归为 ABI 契约错误。`mcdrLastError()` 仅提供诊断内容。同一 context 只有一个控制调用者，prepare 与 commit 之间不录制新工作；重开 command list、录制业务状态变化或其他候选发布都会撤销旧候选。已提交 GPU 工作由录制租约持有程序与资源直到对应 fence 完成。这次新增入口不改变世界/显示输入 schema 或结构体布局。

OIT Resolve CPU 描述使用 schema 3，terrain Begin/metadata/draw 使用 schema 2，其余 OIT CPU 描述保持 schema 1，capture shader ABI 为 5，各自独立演进。`mcdr_dx12OitShaderPreludeV1` 返回 native 唯一 HLSL 声明、shader ABI 和 hash，字符串查询的 required 包含末尾 NUL；普通与 mesh capture 工厂核对同一契约和编译 generation。capture 要求设备支持 `SV_Barycentrics`，由设备初始化时查询一次 `BarycentricsSupported`；不支持时通过 `capture.barycentrics` 拒绝创建 capture 管线。JVM 镜像 CPU 描述字段，不计算 GPU record stride。`OitRecordRows` 表达不可变来源、surface、primitive 与 previous triangle，`OitRecordFrames` 负责唯一业务打包与 `Begin → PublishMetadata → Bind/Unbind → Seal → ResolveRecords → Abort/Reset` 调用。其异常按具名结果区分契约错误、不支持、预算不足、忙碌、过期和提交结果未知；提交未知不自动重试。

shader ABI 5 保持 144 B 私有 record 与 8 B pixel 状态，新增 terrain section 路由；完整 head 与打包 count/status 各占一个 uint32。record 保存 geometry/material previous UV、预期深度、未加权材质、当前 fog/chunk 参数和稳定 RGB，参与计算的值继续使用 FP32。普通 geometry 用真实 primitive 的透视重心插值 previous clip；材质沿几何点或旧 UV 平面求对应位置，各自检查有限值和有效位。previous clip 使用已呈现前帧的 jittered raster projection，与历史 records 的像素坐标一致；不套用 SDK motion 的去 jitter 补偿。indexed cloud/weather 适配器核对帧、scope serial、已编译 profile/generation 和真实索引/实例范围，在 capture PSO 绑定前发布旁表，实际 draw 绑定 lease；Unbind 只接受被真实 native draw 消费的 lease，空 draw 清绑定后中止帧。surface 只有在 draw、Unbind、附件/PSO 恢复与外围调用都成功后才进入待提交历史。Begin 的 program generation 由 OIT 域从当帧冻结且已发布的 resolve program 读取，调用方只提交 limits，不能自行拼接另一代程序。

terrain 经 `OitPublishTerrainMetadataV2 → OitBindTerrainDrawV2 → ExecuteIndirect → OitUnbindDrawV1` 接入现有 Work Graph。CPU 只提交有界 section 表与真实 VBO 租约；同代静态顶点使用冻结的 previous camera/origin 生成 previous clip，几何替换撤销 geometry/material previous，纹理或材质变化只撤销 material previous。GPU section、capture 常量与描述符布局由 native shader 契约独占；Naga 原 VS 返回结构保持原样，MS 输出通过独立结构逐字段复制。纹理上传和复制通过三个 Checked 导出返回实际结果，JVM 确认成功后才记录 texture generation，失败沿 FFM 错误出口抛出。

terrain 只保留 `OitPublishTerrainMetadataV2 → OitBindTerrainDrawV2 → ExecuteIndirect → OitUnbindDrawV1` 这条 records 路径：native 用有界 section 表与真实 VBO 租约记录 chunk 混合前材质，不再需要 stage token、receipt、read set 或回放计划，这些入口已删除。恢复 tile 执行时必须重新引入它们，契约见 [tile 读取范围注册](../rendering/transmittance-tile-execution.md#同-recording-的读取范围注册)。

正常帧没有 execution stream：JVM 只调用 `D3D12OitSealFrameV1` 封口 native recording，然后由 `D3D12OitResolveRecordsV3` 取得 corrected transparency。`MCDRLayout` / `MCDR_ABI` 只镜像实际存在的导出，缺符号会让加载阶段直接失败。重建 tile 执行流时按 [V2 事件流与 Composite 边界](../rendering/transmittance-tile-execution.md#完整-v2-事件流与-composite-边界) 重新定义导出，不要恢复已删除的名字。

旁表已用容量不足时，native 在校验完整 metadata 后返回专用 `MCDR_OIT_RESULT_METADATA_CAPACITY_EXCEEDED`（-7），不改变旁表。JVM 将其转成 `MetadataCapacityExceeded`，该 draw 继续使用原颜色与未记录覆盖 PSO；受影响像素不读取透明历史。共享预算不足、资源分配失败（-3）、无效契约和绑定失败仍中止帧，禁止把分配失败当作容量拒绝，禁止重放已录制 draw。

ResolveRecords 执行 GPU 索引整理、完整性检查和逐 surface 的材质 RGB 历史解析，用当前 alpha、透射、fog 与 chunk 参数重组贡献。frame/metadata token 绑定实际 command recording，Abort/Reset 撤销 lookup，GPU 资源继续由 fence 租约持有。`OitReconstructionInputs` 拥有 Begin/Resolve/退休入口；主循环在 Solid 关闭后冻结 opaque 并 Begin，在原 Composite 创建前 Seal/Resolve，再重建 opaque 并合成透明一次。显示帧先退休 records 再 reset native 帧，中止时的清理留在统一 try/finally 内，保留原错误及释放错误。Abort 接受尚未 reset 的已提交录制，JVM 通过 `contextControl` 退休，不为关闭动作重开 command list；提交结果未知时保留资源并报告失败。历史仅在实际 Present 成功后发布。TRANSMITTANCE alpha/coverage AA 和其余 producer 的真实 records 仍有缺口；用户已在旧 ABI21 terrain 客户端、DLAA Preset M、FG 关闭和正常运行条件下确认水、玻璃、云的固定留屏消失。全部 CPU 结构和状态常量以 `mcdr_abi.h` 为准，字段偏移由同一份 `MCDRLayout.describe/verify` 检查。

`mcdr_dx12OitResolveRecordsV3` 的 x64 描述为 120 字节，接收同尺寸 RGBA16_FLOAT `currentTransparency`、R8_UNORM `unrecordedCoverage`，输出独立 RGBA16_FLOAT `correctedTransparency` 和 R32_UINT `statusOutput`。普通未捕获的 ACCUMULATE draw 保留原片段结果、discard 和深度，额外把存活片元覆盖写为 1；覆盖值非零的像素标记 unknown。缺少可用覆盖 PSO、WG 或翻转方向的实际 draw 通过原 draw 事务登记 `UNAVAILABLE`，FFM 写入唯一 ABI 掩码 1，native 将整帧标为 unknown，包含 EMPTY 像素。unknown、overflow、invalid 和 EMPTY 均保留完整当前透明色，不复活旧贡献。覆盖资源每帧清零，归 OIT owner 与 encoder 生命周期管理；失败或空 draw 不提交缺失事实。Resolve V1/V2 类型和导出已删除，JVM/DLL 必须配套。

头文件开头把设计规则写成了七条，逐条对应到实现：

| 规则 | 含义 | 落地位置 |
| --- | --- | --- |
| 只有 C 链接 | `extern "C"`，无名字修饰，无生成的头文件 | 全部公共导出 |
| 每个聚合体只有一份定义 | Java 侧 1:1 `MemoryLayout`，不手写偏移、不 packed blob | `MCDR_Layout.kt` |
| 句柄是不透明指针 | `mcdr_object*` 等，不是"让 Java 猜含义的整数" | §2.1 |
| 字符串由调用方分配 | UTF-8 缓冲，被调方返回所需字节数（含 NUL） | §2.3 |
| 异常不跨界 | 布尔入口返回 `int32_t` 1/0；错误在 `mcdrLastError()` | §2.5 |
| 数组是 (pointer, count) | 从不用 JNI 数组 | §2.4 |
| 单一 upcall 类型 | 只带整数句柄，native 不持 JVM 对象 | §3.4 |

两个容易忽略的细节：

- **调用约定是 `__cdecl`**。`MCDR_CALL` 在 Windows 上展开成 `__cdecl`；`MCDR_API` 在 native 编译时是 `__declspec(dllexport)`，在消费方是 `dllimport`。x64 上只有一种调用约定，但这个宏让头文件在 32 位下也不会错。
- **MSVC 的 `long` 是 32 位**。头文件为此专门写了一条注释：凡是写成 `int32_t` 的参数，Java 侧必须是 `JAVA_INT`；只有 `int64_t` / `size_t` / 指针才是 `JAVA_LONG` / `JAVA_ADDRESS`。这一条是 §5 里"类型不逐字匹配"坑的根源。

原版帧、显示帧、PreparedFrame 和 provider mask schema 为 2，世界输出 schema 为 3；native 宏、运行时 `mcdrABIVersion()` 与 JVM 期望版本必须一致。必需的 `mcdr_dx12SetAdditionalColorTarget(context, target)` 用于真实 OIT producer 的附加颜色附件；其引入保持原结构体布局。mesh 描述符含 `rasterMotion` 与对齐字段 `_pad4`，x64 大小为 104 字节。世界开始提交完整冻结帧，WorldReady/GuiReady 仅提交身份；真实输入生产完成后发布一次 PreparedFrame，AA/SR 评价只提交身份、consumer、reset 请求和输出绑定。早期 opaque 路径从 `Recording → OpaqueReady → OpaquePost`，其后继续实际透明和晚期绘制，真正 WorldReady 后进入 WorldPost；完整世界路径仍在 WorldReady 后重建。`sourceStage` 只在世界输出描述声明，禁止借修改颜色绑定静默切换语义。显示帧在 GUI 前冻结场景和 guides 输入，GUI 后补齐 UI 并裁决 FG。native 同步复制宿主数据并保留实际资源租约。旧的独立 SR/DLSS/TAA 每帧参数、DLSS/FG 相机常量与早期 DLSS→FG 标签出口已删除，JVM 与 DLL 必须配套部署。

| C 结构 | x64 字节数 | 用途 |
| --- | --- | --- |
| `mcdr_mesh_pipeline_desc` | 104 | MS/AS/PS 字节码、附件/绑定与显式 raw MRT 标记 |
| `mcdr_frame_identity` | 32 | 世界、真实帧、配置和资源版本 |
| `mcdr_raster_camera` | 256 | double 原点、实际投影/旋转、jitter 与可选镜头参数 |
| `mcdr_frame_settings` | 56 | 本帧选定的 AA、SR、FG 和 TAA 参数 |
| `mcdr_vanilla_frame_input` | 696 | 完整输入，含当前与可选相容前帧 |
| `mcdr_prepared_binding` | 16 | 实际 texture/view 与精确 subresource 范围 |
| `mcdr_prepared_frame` | 184 | 同身份的已生产输入、coverage 和 reset 原因 |
| `mcdr_provider_mask_input` | 200 | 分类事实、历史映射和 FSR/XeSS mask 输出 |
| `mcdr_world_output_input` | 360 | schema 3，世界输入/输出绑定、内嵌 mask 事实与 sourceStage |
| `mcdr_reconstruction_request` | 64 | 一次评价请求与实际输出 |
| `mcdr_display_layer_input` | 584 | 世界或手部层的真实深度、运动、覆盖、相机与合成语义 |
| `mcdr_display_post_input` | 144 | 颜色处理、显式 UV 变换或未提供映射的后处理 |
| `mcdr_oit_execution_operation_begin_v1` | 144 | 一次普通 graphics operation 的身份、来源和可选期望值 |
| `mcdr_oit_execution_operation_receipt_v1` | 192 | native draw 的实际命令族、PSO、generation、资源读集和记录状态 |
| `mcdr_display_frame_input` | 672 | Prepare 冻结同身份 SceneDisplay、显示相机与层/后处理；Submit 保持冻结值并补 UiDisplay/backdrop |
| `mcdr_present_receipt_v1` | 112 | schema 1，实际提交、Present、退休结果及 native 历史发布标记 |
| `mcdr_oit_begin_v2` | 272 | schema 2，内嵌原 Begin 与 terrain 配额、previous 投影和 double 原点 |
| `mcdr_oit_terrain_section_v2` | 72 | 稳定 surface/material 身份、真实 section/primitive/vertex 范围 |
| `mcdr_oit_terrain_metadata_v2` | 80 | schema 2，来源、section 表与真实 VBO 范围 |
| `mcdr_oit_terrain_draw_v2` | 16 | schema 2，本次 terrain producer scope serial |

全部字段及内联矩阵/数组参与现有双向布局校验；C 编译同时检查实际大小。原版当前发布 mip0、单层、单采样纹理，绑定范围 first0/count1；无效 view、尺寸、格式、缺少必需输入、重复评价和意外别名在 SDK 调用前拒绝。NONE 仅要求真实颜色；TAA 要求颜色与真实运动；SR/DLSS 还要求深度、曝光及有效透视参数。DLSS/DLAA 不提交当前模型不支持的 current-color bias。首次缺少可用前帧/运动时发布 NONE，由世界呈现路径显示。

mesh 描述符的 `rasterMotion` 只接受 `0` 或 `1`。设为 `1` 时要求真实深度、有效 PS 和两张颜色输出，末张必须是全通道写入的 `RGBA32_FLOAT` raw target。normal/raw PSO 共用原 mesh 根签名；raw 片段包装不新增 binding 或顶点插值量。录制时按实际 pass 的 motion 附件选择 PSO，native 继续校验附件数量和深度，禁止从普通双 MRT 布局推测 raw 语义。

`mcdr_dx12BeginDisplayFrame(context)` 在本帧清理 UI 前完成上一帧 Present 与 SDK 输入读取等待，并准备 provider/交换链。`beforeGui` 完成现有 GuiReady 后调用一次 `mcdr_dx12PrepareDisplayFrame(context, input)`，不提供 UI/backdrop；native 在返回前深复制 scene、layers、posts 与相机值，保留资源租约，录制显示 guides，并提前提交世界与 guides 命令，保存准确的 context `guideFence` 后切换 command-list ring 继续录制。该中间提交不接受 SDK 输入，也不创建最终 Present ticket。Prepare 返回 `1` 表示已提交 guides，`0` 表示已记录本帧输入不可用、最终阶段将跳过 FG，负值表示失败。

CPU 随后录制 GUI；`afterGui` 调用一次 `mcdr_dx12SubmitDisplayFrame(context, input)`，复用冻结输入只补 UI/backdrop，native 校验所有冻结值保持一致，再等待该帧准确的 `guideFence`、读取 validity。通过有效性裁决才向 SDK 提交，缺少输入或 GUI 含场景背景时跳过；返回 `1` 表示 SDK 接受，`0` 表示跳过，负值表示协议或运行失败。两次 FFM 调用的临时 arena 各自只需存活到调用返回，接受、最终命令提交与实际 Present 成功分别记账。

`mcdr_dx12AbortDisplayFrame(context)` 用于本帧没有呈现或渲染中止的清理：结算异步提交/Present 后取消匹配 context 的 FG ticket，再清理 vanilla 帧状态。正常 `VanillaResetFrame` 保留已排队的 Present 回执，两者用途不同。成功返回 `1`，失败保留相关 owner 并传播错误。

`mcdr_dx12SettleDisplayFrameV1` 在下一显示帧开始、投影冻结及配置应用前结算上次实际 Present。入队只建立候选；JVM 从回执校验真实身份、fence 和 `S_OK`，随后发布世界及 viewmodel previous；OIT 还要求 native 已发布历史标记。结算结果区分 Empty、Completed、TimedOut 和 SubmissionUnknown。超时、提交未知或退休信号未知保留 native owner 与未消费回执，禁止提前释放资源或当作成功历史；录制完成的世界帧另存，不混用 presented previous。

显示工作图在同一显示投影下合并世界和手部的 depth/MV/validity/provenance，XeSS 对完整 tuple 做 3×3 dilation。世界与手部各自提供独立 `R8_UNORM` coverage，字节值只允许 0、1、3，分别表示未覆盖、已覆盖但无有效运动、已覆盖且运动有效；shader 写入时编码为字节值除以 255。世界运动使用有限值 `RG16_FLOAT`，显示工作图消费世界及手部 coverage。不把 NaN 或 `65504` 当作运动有效性标记，`65504` 只用于 RG16_FLOAT 数值范围检查。任一最终 tuple 无有效运动即跳过本帧 FG，未声明的 coverage 值直接报错。提前提交将 guide 执行与 CPU GUI 录制重叠，最终阶段仍须同帧等待/回读；等待缩短和吞吐收益待实测，独立 coverage 不补足多运动透明来源。

VRS 的候选输出与两份列主序 clip 映射仍在调用内复制，`pipelineFlags` 保持 offset44、结构体96字节。`mcdr_sr_dispatch` 保留配置候选准备所需的 JVM 镜像；逐帧重建参数与 DLSS/FG 相机常量由 native 从冻结帧生成，JVM 不再逐帧调用独立 SDK 入口。异步关闭成功后才能释放仍在消费的资源。

`mcdr_dx12MultiDrawBound` 为新增必需导出，沿用现有 40 字节 `mcdr_binding_slot`，不修改结构体布局。draw 参数统一使用 first/count/base 三元组；共享绑定、覆盖槽索引和逐 draw 完整覆盖绑定在调用内消费。Kotlin 分别使用四个独立 scratch 槽编码参数、共享绑定、覆盖索引和覆盖绑定，任一槽扩容不使其他槽失效；大目标、高对齐或嵌套调用改由同一次调用的 confined arena 持有。native 保序录制，使用原有 descriptors/draw 入口，失败不重放已录制命令；调用返回后不保留宿主数组。新 JVM 需要包含此符号的当前 DLL，版本号相同的旧产物仍不可混用。

`mcdr_dx12VanillaBeginRasterPass(context, tokenOut)` 是当前 JVM 的另一个必需导出。调用发生于成功的 native `BeginRenderPass` 之后，native 从实际附件范围和当前帧冻结的 `resourceVersion` 创建父声明。成功返回 `1` 并写入非空 token，JVM 在 `EndRenderPass` 后通过现有 finish 入口结算；失败沿用 abort/reset 和唯一 native 错误出口。空附件集合也有有效父声明。

附件声明和实际 transition 使用同一 mip/slice/plane 范围，结束时验证 `COMMON`。实际 CBV/SRV/VB/IB/indirect 输入由 native draw 消费边界导入，JVM 不为逐 draw 增加声明 downcall；bulk 按全部实际覆盖绑定冻结兼容读取并集。普通父/输入声明 effects 为 0，专用外部 pass 仍声明自己的 graphics/descriptor effects。native foreign-state 恢复会重绑成功保存的实际 IA 视图，随后使 descriptor 快照失效，不要求 JVM 补建一份 IA manager。

未进入当前生产调用的 bundle/pool、旧 executor、预过渡、frame-slot 与分区 descriptor 的 JVM 句柄、包装及必需符号查找已删除，`D3D12AsyncContext` 和三个无效 bundle 配置也已移除。native 公开 ABI7 导出和实际实现保留给现有 C 消费方，当前 JVM 不实例化这套 owner。真实 `Init/DestroyAsyncRenderer`、录制/提交握手、context fence 和已保留的 AsyncFence 包装继续独立工作；不能把删除闲置包装解释为关闭异步提交。

### 1.2 布局层：`MCDR_Layout.kt`

这一层的三条硬规则：

1. **`MemoryLayout.structLayout` 完全不插入 padding**。它只是按顺序拼接成员，C 编译器会插的每一个 pad 字节都必须显式写出来 —— 要么命名成 `_padN`，要么用 `MemoryLayout.paddingLayout(n)`。
2. **尾部 padding 决定 `byteSize()` 能不能当数组 stride**。C 的 `sizeof` 会把结构体补到对齐的整数倍；漏掉尾部 pad，`byteSize()` 就比 `sizeof` 小，按它算 stride 写数组时每一项都会错位。
3. **不写字面量偏移**。所有偏移都经 `offset(layout, field)`（`layout.byteOffset(PathElement.groupElement(field))`）解析一次，存成 `@JvmField val XXX_YYY: Long`。

变长旁表和输出缓冲通过 `FfmAllocation.allocateFfmArray` 按真实元素布局分配：先用 `Math.multiplyExact(count.toLong(), element.byteSize())` 计算字节数，再按元素对齐分配；元素数为零时传 `MemorySegment.NULL`，旧入口明确需要非空地址时显式选择 `FfmEmptyAllocation.NON_NULL`。结构体只按对应 `MemoryLayout` 分配，数组 stride 包含尾部 padding，不能按固定尺寸猜测不同目标的占用。

同一线程的 scratch 只允许最外层 FFM 作用域使用缓存槽；作用域嵌套时自动切换到本次调用的 confined arena，避免内层 ABI 辅助调用覆盖外层尚未消费的地址。大于缓存上限或对齐要求更高的目标也使用本次调用 arena，返回后立即关闭。native 若要跨调用保留数据，必须复制内容并取得真实资源租约，不能保存这些 JVM 地址。

作用域已经选择临时 arena 时，该作用域内的小参数也从这个 arena 分配；分配函数不能只凭单次请求大小重新选回线程缓存。push constants、VRS 矩阵与回读结果同样进入作用域，回读值复制到 JVM 数组后才结束作用域。

热路径中的编码作用域、`recordCommands`、staging writer 和短分配包装显式使用 Kotlin `inline`，避免高阶调用生成逐 draw 的闭包；需要禁止非局部返回的边界使用 `crossinline`。分配器主体、业务调度和 SDK 调用保持普通函数，内联只覆盖同步编码边界，不改变资源寿命。Mixin 调用业务登记的边界保持普通函数，避免 Kotlin 把辅助类内联复制进 Mixin 包。

几个具体的"坑位"已经写在代码注释里，值得记住它们的形状：

| 结构体 | 现象 | 原因 |
| --- | --- | --- |
| `mcdr_input_element_desc` | 指针落在 32 而不是 28 | C 侧 24 处一个 `_pad0`，编译器再补 4；Kotlin 必须**多写一个** `paddingLayout(4)` |
| `mcdr_depth_state_desc` | 尾部 2 字节是"承重"的 | 成员和 6，C 补齐到 8；这 2 字节同时保证 `PIPELINE_DESC.topology` 落在 56 |
| `mcdr_fg_dispatch` | 末尾是 `_pad2[2]` 而不是一个 int32 | 两个位置都要显式填，只留一个会多出 4 B 尾填充，`sizeof` 变成 92 而不是 96 |
| `mcdr_sr_caps` / `mcdr_fg_caps` | `char note[28]` 要写成**一个**带名字的 `sequenceLayout(28, I8)` | 拆成 28 个成员后 `describe()` 会枚举出 28 个键，和 native 的字段表对不上 |

**互操作要求**：标量布局（`I32`/`I64`/`F32`/`F64`/`I8`/`PTR`）与所有偏移常量必须是 `@JvmField`。普通 `val` 会生成 `MCDRLayout.INSTANCE.getI32()` 这样的访问器，Java 侧的 static import 解析不到（混编工程里仍有 Java 调用方）。

**两级 ABI 校验，分工明确**：

| 检查 | 触发时机 | 需要 DLL | 覆盖内容 |
| --- | --- | --- | --- |
| `EXPECTED_C_SIZES` 断言 | `MCDRLayout` 类初始化 | 否 | 每个结构体的 `byteSize()` 与手写常量 |
| `describe()` vs `mcdrABILayout()` | `MCDR_ABI` 初始化、`verifyABI()` | 是 | 每个字段的名字、offset、size |

第一级在**任何 native 调用之前**就能发现漂移，连 DLL 没编出来都能查：

```kotlin
arrayOf<Any>("mcdr_binding_slot", BINDING_SLOT, 40L),
```

第二级是 `mcdrABILayout(&fields, &count)` 返回一张 `mcdr_abi_field{ name, offset, size }` 表，名字形如 `"mcdr_binding_slot.buffer"`。`verify()` 做**双向**比对，三类问题分别报：

- `xxx: offset java=A native=B` / `size java=A native=B` —— 字段错位；
- `xxx: missing from the native layout report` —— Java 有、native 没报（native 少登记）；
- `xxx: reported by native but absent from the Java mirror` —— native 有、Java 没镜像（Kotlin 少一个字段，或者新结构体忘了登记进 `STRUCTS`）。

任何一条都抛 `IllegalStateException`，后面列出全部问题，**不是只报第一个**。

`STRUCTS` 这张表存在的意义是"枚举一致性"：`describe()` 和 `verify()` 都从它出发，所以新结构体只要登记进去就自动被两个方向检查，不会出现"加了结构体但没人校验"。

SDK 私有翻译结构已退出 JVM 镜像与 `mcdrABILayout`；完整帧、prepared 输入和评价请求的每个字段均参与双向校验。

### 1.3 句柄层：`MCDR_ABI.kt`

一个 Kotlin `object`，内部保存方法句柄字段：

```kotlin
private var MH_createdevice: MethodHandle? = null
```

三个刻意的选择：

- **可空**：可选导出缺了就是 `null`，调用方靠 `?:` 降级。
- **非 `final`**：初始化失败不能变成 `ExceptionInInitializerError` —— 那会让整个类变成 `NoClassDefFoundError`，后端连"报告不可用"都做不到。
- **只链接一次**：`downcallHandle` 在 `bindAll()` 里做完，逐帧路径只 `invokeExact`。

两种绑定的语义差别是本层的核心设计：

| 绑定 | 缺失时 | 用于 |
| --- | --- | --- |
| `skip(name, desc)` | 抛 `IllegalStateException("MCDR export not found: ...")` | 没有它整个库没有意义：版本、布局报告、错误串、日志开关 |
| `skipOptional(name, desc)` | 返回 `null`，连 `Throwable` 也吞 | 单个功能（SR / FG / DLSS / PIX / VRS…），旧 DLL 上只是那个功能不可用 |

**可选绑定不能绕过 ABI 版本握手**。只有版本兼容的 DLL 才进入功能查询；版本不匹配首先拒绝初始化。必需的 Work Graph 与批量 draw 入口按强制绑定处理，具体 SDK 是否可用仍由能力查询判断。

描述符助手两个：

```kotlin
private fun fd(ret: MemoryLayout, vararg args: MemoryLayout) = FunctionDescriptor.of(ret, *args)
private fun fdv(vararg args: MemoryLayout) = FunctionDescriptor.ofVoid(*args)
```

### 1.4 封装层：每个导出的四种形态

`MCDR_ABI` 的公开函数具有**四种明确区分的失败语义**：

| 形态 | 返回 | 缺句柄时 | 抛异常时 | 例 |
| --- | --- | --- | --- | --- |
| 状态码 | `Int` | `Int.MIN_VALUE` | `Int.MIN_VALUE` + WARN | `D3D12SetDlssQuality`、`D3D12SetHdrMode` |
| 谓词 | `Boolean` | `false` | `false` + WARN | `D3D12SetPipeline`、`AsyncFenceSignal` |
| 句柄 | `Long` | `0L` | 包成 `IllegalStateException` | `D3D12CreateSurface`、`D3D12CreateCommandEncoder` |
| 命令 | `Unit` | 静默返回 | 包成 `IllegalStateException` | `D3D12PresentSurface`、`ID3D12DestroyResource` |

`Int.MIN_VALUE` 是一个**哨兵**，含义是"本地 DLL 没有这个导出"，与"调用失败"（其它负值）区分开。少数查询函数有自己更合适的默认值：`D3D12DlssAvailable` 缺导出返回 **1**（"未知 = 可用"，避免旧 DLL 上把 DLSS 面板藏掉）；`D3D12ReflexAvailable` 缺导出返回 **0**；`D3D12HdrAppliedMode` 缺导出返回 **-1**。

创建类函数的统一形状：

```kotlin
fun D3D12CreateSurface(hwnd: Long): Long {
    requireLoaded()
    return try {
        addr(MH_createsurface!!.invokeExact(hwnd) as MemorySegment)
    } catch (t: Throwable) {
        throw unexpected(t)
    }
}
```

`unexpected(t)` 一律是 `IllegalStateException("MCDR native call failed: $t", t)`。需要带 native 错误串的地方用 `check(value, what)`：它调 `D3D12LastError()`，拼成 `"$what failed: $detail"` 再抛。

### 1.5 描述符打包器

`MCDR_PipelineDesc` / `MCDRMeshPipelineDesc` 是布局层的延伸：把 Kotlin 侧的 builder 对象铺进一块 arena，得到一个 `mcdr_pipeline_desc*` / `mcdr_mesh_pipeline_desc*`。

```kotlin
MCDRPipelineDesc().apply { /* 填字段 */ }.pack(arena)   // 返回的段只在 arena 生命周期内有效
```

设计要点：

- **builder 是单次的**：填完字段直接交给 `D3D12CreatePipeline`，不缓存、不复用。
- **嵌套类是 static nested**（Kotlin 默认），不能写 `inner class` —— Java 侧要能用 `McdrPipelineDesc.ColorTarget(...)` 构造。
- **字段偏移在类初始化时解析一次**（`off(MCDRLayout.COLOR_TARGET, "srcColor")`），从不写字面量。
- **`semanticName` 跟着元素走**：它是 `InputElement` 自己的字段，不得另设一个按位置关联的名字数组（"第 i 个名字给第 i 个元素"），两边数量不等会静默错配。
- **未使用的颜色槽用 `format = -1`**（`DXGI_FORMAT_UNKNOWN`），不用额外的 sentinel 计数。
- **空 shader 也分配 1 字节**，让指针永远不为 `NULL`：native 校验器拒绝"`NULL` 指针 + 非零长度"，但接受"有效指针 + 长度 0"。
- mesh 版只有一处语义差别：三个 blob 是 **DXIL** 字节码而不是 HLSL 源码，且没有 input layout / 没有 topology（mesh 管线没有输入装配阶段）。

### 1.6 新增一个导出函数：要改哪几处

按顺序做，缺一处就是"能编译、能启动、运行到才炸"。

1. **`MCDR\include\mcdr_abi.h`**：加声明。签名里只用 `int32_t` / `int64_t` / `void*` / 已有结构体指针，不要出现别的模块拥有的 C++ 类型（那会让修饰名变成模块私有名）。放对段落（`abi_device.cpp` / `abi_surface.cpp` / … 对应的功能段）。
2. **结构体有变就升版本**：宏 `MCDR_ABI_VERSION` 加一，并在注释里写清"加了什么、哪些旧入口被降级成转发"。
3. **native 实现**：在对应 `src\c_abi\abi_*.cpp` 里定义，导出函数必须与声明处在同一模块单元内。
4. **`MCDR_Layout.kt`**：新结构体写 `structLayout`（pad 全部显式），加一行 `EXPECTED_C_SIZES`，若是新类型还要登记进 `STRUCTS`（漏登记 = 该结构体完全不被校验）。
5. **`MCDR_ABI.kt` 加字段**：`private var MH_xxx: MethodHandle? = null`，命名统一小写。
6. **`bindAll()` 的对应 `bindXxx()`** 里加一行：必需用 `skip`，功能级用 `skipOptional`，描述符用 `fd` / `fdv` 逐字写对每个参数宽度。
7. **写公开封装**：按 §1.4 选一种失败语义；文档注释里写清返回值的哨兵含义。
8. **跑 ABI 校验**：启动一次客户端。`MCDR_ABI.bindAll()` 会在设备创建前比对
   `MCDR_ABI_VERSION` 与 `MCDR_Layout.EXPECTED_C_SIZES`，不匹配直接抛
   `ABI version mismatch` 或结构体布局错误，不会走到首次绘制。**不需要 GPU 自检脚本**：
   校验发生在加载期，失败信息里带两端版本号与不一致的字段。
9. **编译两边**：`cd Illusion; .\gradlew.bat compileKotlin`，native 侧 `MSBuild MCDR\MCDR.vcxproj /p:Configuration=Release /p:Platform=x64`。

## 2 调用约定与内存

### 2.1 类型映射

| C | ValueLayout | Kotlin 侧读 | 备注 |
| --- | --- | --- | --- |
| `int32_t` / `bool` / MSVC `long` / `float` | `I32` / `F32` | `as Int` / `as Float` | 布尔入口一律 `int32_t` 1/0 |
| `int64_t` / `size_t` / `uint64_t` | `I64` | `as Long` | 需要按无符号解释时自己转 |
| `void*` / `mcdr_xxx*` | `PTR` | `as MemorySegment` → `.address()` | 句柄在 Kotlin 里存 `Long` |
| `uint8_t` | `I8` | `as Byte` | 结构体字段用，不作为参数 |
| `char*`（UTF-8 出参） | `PTR` | 二次调用取串 | §2.3 |
| `void` | — | — | 用 `fdv(...)` 而不是 `fd(VOID, ...)` |

**句柄约定**：所有句柄在 Kotlin 里都是 `Long`，只在调用边界用 `MemorySegment.ofAddress(handle)` 转成段。反向用 `addr(seg) = seg?.address() ?: 0L`。这样上层代码（渲染后端、录制器）完全不碰 `MemorySegment`，也不必 import `java.lang.foreign`。

### 2.2 Arena：谁分配、谁释放

| Arena | 位置 | 生命周期 | 装什么 |
| --- | --- | --- | --- |
| `ARENA = Arena.ofShared()` | `MCDR_ABI` 字段 | 进程 | `libraryLookup` 的库句柄、upcall stub（**永不关闭**） |
| `Arena.ofConfined().use { }` | 单个函数体内 | 本次调用 | 一次性的路径串、长度出参、临时结构体 |
| `SCRATCH`（ThreadLocal，4 个 auto slot） | 每线程 | 有界复用，GC 回收 | 小型数组与结构体打包 |
| `FfmScratch.withScope` 的 confined arena | 单次 downcall | 编码、native 同步消费和结果复制结束即关闭 | 大目标、高对齐目标或嵌套作用域内的全部参数 |
| 调用方传入的 arena（`pack(arena)`） | 描述符 | 跟随调用方 | 管线描述符 + 嵌套 shader blob |

两条纪律：

- **共享 Arena 只装"活到进程结束"的东西**。库句柄和 upcall stub 都必须如此 —— stub 被回收就是悬空函数指针，库句柄被关就是 DLL 被卸载。
- **confined Arena 绝不跨线程**。这是 API 语义：`Arena.ofConfined()` 的段只能在分配它的线程上访问，跨线程是运行时异常。小型复用段虽采用 `Arena.ofAuto()`，仍由 `ThreadLocal` 限定所有者；它们与调用内的大段都不交给 native 提交线程延期读取。

指针参数的有效期：头文件没有逐条声明，但代码的用法是**只在本次调用期间有效** —— `pack()` 的返回值在同一段 `use { }` 里直接传给 downcall，随后 arena 关闭。native 侧负责把需要留存的数据拷进 D3D12 对象。

### 2.3 字符串

所有跨边界的字符串都是 **UTF-8 + NUL**，由调用方分配：

```kotlin
private fun cstringUtf8(arena: Arena, s: String): MemorySegment {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    val seg = arena.allocate(bytes.size + 1L)          // +1 给 NUL
    MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.size)
    seg.set(ValueLayout.JAVA_BYTE, bytes.size, 0)
    return seg
}
```

**出参字符串是两阶段协议**，`mcdrLastError` 是最好的样板：

```c
// 返回所需字节数（含 NUL）；buf == NULL / cap == 0 时只做长度查询
int32_t mcdrLastError(char* buf, int32_t cap);
```

Kotlin 侧因此永远是"先问长度、再分配、再读"：

```kotlin
val need = MH_lasterror!!.invokeExact(MemorySegment.NULL, 0) as Int
if (need <= 0) return ""
val buf = arena.allocate(need.toLong())
val written = MH_lasterror!!.invokeExact(buf, need) as Int
return if (written > 0) buf.getString(0) else ""
```

同样的形状用在设备摘要、适配器列表、各 SDK 的能力查询上（`mcdr_dx12QueryDlss`、`mcdr_dx12QueryMeshSupport` 等）。**不要假设一次调用就够**：native 返回的是"必需长度"，不是"写入了多少"。

`getString(0)` 默认按 UTF-8 解码并以 NUL 结尾，正合约定；但它要求段的可见长度足够，所以对 native 返回的裸地址必须先 `reinterpret` 一个明确长度（不能一路用 `Long.MAX_VALUE`，除非确实要读到 NUL 为止，像 `mcdrABILayout` 里的字段名那样）。

### 2.4 数组与结构体数组

数组参数一律是 **(pointer, count)**：

```kotlin
withScratch(checkedArrayBytes(count, MCDRLayout.PTR), MCDRLayout.PTR.byteAlignment()) { temporary ->
    val handles = scratchArray(0, count, MCDRLayout.PTR, temporary)
    for (index in objects.indices) handles.setAtIndex(MCDRLayout.PTR, index.toLong(), ptr(objects[index]))
    MH_prepareobjects!!.invokeExact(ptr(ctx), handles, count)
}
```

结构体数组用 `arena.allocateFfmArray(count, layout)`，然后按 `layout.byteSize()` 逐项写：

```kotlin
val array = arena.allocateFfmArray(targets.size, MCDRLayout.COLOR_TARGET)
val stride = MCDRLayout.COLOR_TARGET.byteSize()      // 这就是尾部 padding 必须算对的原因
```

FFM 变长目标的缓存规则由 `FfmScratch` 拥有：每线程四个独立槽，单槽最多缓存 64 KiB，从 256 B 起按两倍扩容，对齐上限 256 B。缓存保存容量，`allocate` 每次返回精确到本次请求字节数的 slice；因此同一槽先后服务不同大小目标时，调用方看到的边界不会带入上一次的容量。新段分配成功后才替换旧段，分配失败保留原缓存；替换后的旧段和线程退出后的小缓存由 GC 回收。超过大小或对齐上限的请求使用本次调用的 confined arena，不把峰值留在线程缓存中。多数组调用的作用域共同覆盖所有参数编码、同一次 downcall 和返回值复制，`withScope` 保持短路径内联但以 `crossinline` 禁止把非局部返回带入 Arena 关闭边界。一个槽扩容不会撤销其它槽的地址。

调用方的 `maximumBytes` 只是缓存选择的上界提示，不是安全前提；若实际目标后来超过大小或对齐阈值，`allocate` 会在当前作用域内惰性创建 confined arena，直到该作用域结束。惰性创建后，后续的小目标也在这个 arena 分配，保留先前已经编码的缓存地址。这样不同大小的目标可以共用一条内联编码路径，不需要为“小目标”和“大目标”复制整段业务代码。

terrain Work Graph prepare 和普通 Work Graph dispatch 的 bindings 上限为 32，句柄、offset、length 分别使用三个 scratch 槽，大小和对齐按 `PTR` / `I64` 布局计算。terrain prepare 的 `bindingCount` 表示本次有效项数，复用数组的容量可更大；只校验、复制和提交有效前缀，不能把上次管线留下的尾部当作绑定。offset 与 length 从原始 `LongArray` 直接复制，避免 vararg 展开再复制数组；普通 dispatch 的零绑定明确传三个 NULL。native 在 downcall 返回前读取这些数组并保存实际资源租约，CPU 编码内存随即可以复用；GPU 资源继续按 recording fence 持有。

普通 compute、SRI 和三纹理 compute 共用内联的常量编码作用域：0～8 个 dword 按实际数量从 scratch 分配并一次复制，零常量传 `NULL`。三个入口的 dispatch lambda 各展开一次，小参数调用不再逐次新建 confined arena；嵌套调用仍使用独立 arena。

DXIL、Work Graph 节点代码和寄存器数组按真实字节数直接分配后复制，禁止使用 `allocateFrom(..., *array)`：Kotlin 的 spread 会先制造一份额外 JVM 数组，大 DXIL 会增加复制和峰值内存。空寄存器数组继续传 `NULL`，非空数组才进入本次 confined arena。

显式 `inline` 用于编码作用域、短包装和 staging writer。作用域的业务 lambda 只在源码中调用一次，再按大小选择其 arena；把业务 lambda 分别写进小缓存和大分配分支会让 Kotlin 复制整段批量编码。分配器主体和整段业务调度保持普通函数。结构体按实际 `byteSize()` 和 `byteAlignment()` 分配；不能用对齐值代替长度，也不能按指针大小估算指向的数据。

CPU 调用参数和 GPU 上传具有不同寿命。GPU 上传继续由 `SubmissionUploadArena` 按页切片，并保留到实际 submission fence 完成；大页的回收由上传池预算决定，不能在 FFM 调用返回时释放。DXIL、字节串和固定常量按各自字节长度分配，不能套用结构体数组 stride。以上阈值是当前缓存预算选择，尚无本轮 CPU 帧时间或 GC 性能实测。

**索引必须按"实际起始偏移"算**：索引"每槽一份"的类型数组时用 `startDraw` 之后的绝对下标，不要用 slot 下标 —— 后者只在 `startDraw == 0` 的 worker 上正确，其余 worker 抛 `ArrayIndexOutOfBoundsException`，整条并行录制退化成串行。

### 2.5 返回码与错误串

| 出口 | 约定 |
| --- | --- |
| 布尔语义 | `int32_t` 1 = 成功/真，0 = 失败/假 |
| 三态 | 1 = 成功，0 = 未启用/已关闭，-1 = 参数或加载失败（如 PIX 加载、日志镜像） |
| 长度查询 | 返回所需字节数（含 NUL），0 表示"无内容" |
| 指针 | 非 `NULL` 表示有效，`NULL` 表示失败（失败原因去 `mcdrLastError()`） |
| 错误串 | `mcdrLastError()` 取**调用线程**的最近一条错误 |

`mcdrLastError()` 是线程局部的 —— 这也是它不用全局变量的原因：渲染线程与 worker 各自报自己的错，不会互相覆盖。

### 2.6 对齐与 padding

- 头文件**没有** `#pragma pack`，所以布局就是 MSVC x64 的自然对齐：`int64_t`/指针 8 字节对齐，`int32_t`/`float` 4 字节，`uint8_t` 1 字节，结构体整体对齐到其成员的最大对齐。
- 每个"编译器会插"的位置在 C 侧都写了一个具名 `_padN`，Java 侧必须有对应成员；C 侧没命名、只能靠编译器补的位置（如 `INPUT_ELEMENT` 里 28→32 的 4 字节），Java 侧必须补 `paddingLayout(n)`。
- **arena 分配的地址**：数组按元素布局对齐，固定常量的 `scratch()` 默认 16 字节；更高对齐的请求不能复用不满足要求的段。

## 3 生命周期与线程

### 3.1 加载顺序

`MCDR_ABI` 的 `init { }` 是唯一的加载入口，顺序不可换：

1. `extractLibrary()`：目录 `Path.of(System.getProperty("user.dir"), "dxrmc")` → `createDirectories` → **先** `extractVendorResources(dir)`（第三方运行库必须比 DLL 更早落位）→ 从 JAR 资源 `/MCDR.dll` 读字节、**逐字节比较**决定是否覆盖本地副本 → 返回 DLL 绝对路径。
2. `SymbolLookup.libraryLookup(dllPath, ARENA)` —— 失败就把异常记进 `LOAD_FAILURE`，**不抛**。
3. `bindAll()`：`bindFenceStub()` → `bindMetaAndDevice()` → `bindCommand()` → `bindPipelineSurfaceDraw()` → `bindAsync()`，然后推两个启动期开关（日志详细度、关机期进度行）。
4. `verifyABI()`：版本握手 + 布局逐字段比对。
5. 全部成功才打三行 INFO（库版本、各结构体 `byteSize`、启动日志级别）。

任何一步失败都只写 `LOAD_FAILURE`，让类**继续可用**（只是 `isAvailable() == false`）。

### 3.2 `dxrmc\` 目录与"必须同目录"

- DLL 与所有第三方运行库都解到 `<user.dir>\dxrmc\`。`user.dir` 是游戏进程的工作目录，也就是整合包/实例目录。
- 解压是**内容比较**而不是时间戳比较：JAR 条目的时间戳固定在 1980 纪元，按 mtime 判断会让陈旧的本地副本遮住新构建。
- vendor 解压**从不抛异常**：这些 SDK 是可选的，缺一个必须降级成"该 SDK 报不可用"，而不是让模组起不来。日志只报一行 `SDK runtime DLLs: N extracted, M already current`。
- 清单由 `vendor/manifest.txt` 驱动（构建期生成）。不硬编码名单的理由：JAR 目录不可枚举，硬编码会漂移。
- 随发行包发布的是 XeSS 与 Microsoft 的 D3D12Core / DXC；DLSS 与 FidelityFX 按许可证要求不进发行包，只在开发目录里出现。发行版因此会如实报告 DLSS / FSR 不可用。

**为什么必须和第三方运行库同目录**：Windows 的 DLL 搜索顺序里，PATH 排在很后面，把目录追加到 PATH 对"被隐式加载的依赖"并不可靠；而同一目录是加载器与 native 侧都能预期的唯一定位点。具体到本项目，这些运行库都是 native 在运行时按名字去找的，所以必须落在 `MCDR.dll` 旁边。代码里能确认的只有"解压到同一目录、且在 `libraryLookup` 之前完成"这个顺序保证；更细的搜索路径语义代码中未明确。

### 3.3 版本握手与 `isAvailable()`

```kotlin
fun isAvailable(): Boolean = LOAD_FAILURE == null
fun unavailableReason(): String? = LOAD_FAILURE?.toString()
```

`LOAD_FAILURE == null` 意味着**三件事都成立**：DLL 找到了、必需句柄全绑上了、`mcdrABIVersion()` 与 `MCDR_ABI_VERSION` 相等且布局逐字段一致。它不等价于"显卡能用" —— 设备创建失败是另一层的事。

版本不匹配直接抛 `MCDR ABI version mismatch: java=5, native=4`。这是硬失败：版本不同意味着结构体或函数签名可能不同，继续调用无法保证内存安全。

### 3.4 线程约束

| 场景 | 约束 |
| --- | --- |
| 绑定（`bindAll`） | 只在类初始化时、单线程跑一次 |
| 主渲染线程 | 独占一条命令列表；`SCRATCH` 槽只它用 |
| native 提交线程 | 只消费已移交的 context/命令与 native 租约，不访问 JVM confined scratch |
| upcall（围栏回调） | 从 native 自己的 `std::thread` 进来，**不是** JVM 线程 |
| confined arena | 绝不跨线程传递段 |

upcall 这条要单独说：`onFenceComplete` 在 native 线程上执行，异常一旦逃出去会**直接终止 JVM**（不是抛到某处可以捕获）。所以它自己 `catch (Throwable)`，只打一条 WARN，不依赖任何 `catchException` 包装。

## 4 失败处理

### 4.1 五类失败，五种处理

| 失败 | 检测点 | 处理 | 用户可见后果 |
| --- | --- | --- | --- |
| 库缺失 / 加载失败 | `extractLibrary()` / `libraryLookup` | 记 `LOAD_FAILURE`，不抛 | 后端整体不可用，报原因 |
| 必需导出缺失 | `skip()` | 抛 → 记 `LOAD_FAILURE` | 同上（新 JVM 配老 DLL 的典型形态） |
| 可选导出缺失 | `skipOptional()` 返回 `null` | 该函数返回哨兵值 | 单个功能报不可用 |
| 布局/版本不匹配 | `verifyABI()` | 抛 → 记 `LOAD_FAILURE` | 同上，日志里有逐字段明细 |
| 调用抛异常 | 各封装的 `catch` | 硬失败接口包 `unexpected`；查询接口返回哨兵 | 一条 WARN + 降级 |

### 4.2 显式失败 vs 静默降级

分界线是"这个调用的失败会不会让后续状态不可信"：

- **硬失败**（抛 `IllegalStateException`）：创建资源/管线/命令列表、销毁、present、`D3D12LastError()` —— 继续跑下去只会积累不一致状态。
- **静默降级**（哨兵值）：所有开关类（DLSS 质量、Reflex 模式、VRS、PIX、HDR）、所有能力查询。理由很实际：这些函数大多在"配置被读取"的时刻调用，那时 DLL 是可选的，抛异常等于让玩家改不了配置。

### 4.3 错误信息长什么样

- 绑定失败：`MCDR export not found: mcdr_dx12CreateSurface`
- 库缺失：`MCDR.dll is neither packaged in the JAR nor present at ... — build the MCDR native project first`
- 版本：`MCDR ABI version mismatch: java=5, native=4`
- 布局：`MCDR ABI layout mismatch (N field(s)); ...` 后面逐行列出
- 尺寸：`MCDR ABI struct size mismatch for mcdr_fg_dispatch: java=92 c=96 — fix the mirror ...`
- 调用：`MCDR native call failed: <原始异常>`
- 业务：`<what> failed: <mcdrLastError()>`

### 4.4 跨边界硬约束

- native 侧**不抛 Java 异常**，C++ 异常也不得穿过 FFM 边界。
- 布尔 / 三态入口一律返回 `int32_t`（1 = 成功，0 = 失败或未启用，-1 = 参数或加载失败），失败详情只从 `mcdrLastError()` 读，不要另开错误通道。
- 需要硬失败时由 JVM 侧封装包成 `IllegalStateException`；native 侧不得感知 JVM 的存在。
- `FunctionDescriptor` 与 `invokeExact` 的类型必须逐字匹配。返回 `int32_t` 时用 `I32` 和 `as Int`，返回 `int64_t` 或 `size_t` 时用 `I64` 和 `as Long`，返回指针时用 `PTR` 和 `as MemorySegment`。在 Lambda 里调用返回 `void` 的句柄时，让调用落在语句位置，例如调用后单写 `Unit`。如果 Kotlin 将调用当作 Lambda 的返回表达式，可能生成返回 `Object` 的签名，第一次调用就会抛 `WrongMethodTypeException`。

## 5 常见坑（用法约束）

1. **编码**：一律 UTF-8 加显式 NUL；`String.toByteArray()` 不带字符集取的是平台默认编码（中文 Windows 上是 GBK），native 收到的是乱码路径。
2. **字节序**：`MemorySegment.asByteBuffer()` 默认是 **BIG_ENDIAN**，而 x86 是 little；拿到 `ByteBuffer` 立刻 `.order(ByteOrder.nativeOrder())`，否则所有 UBO 里的矩阵都被静默转置。
3. **对齐**：`structLayout` 不插 padding（§1.2）。宁可多写一个具名 `_padN` 并在 C 侧也命名它，也不要指望"反正编译器会补"。
4. **尾部 padding 决定 stride**：拿 `byteSize()` 当 stride 之前，先确认 `EXPECTED_C_SIZES` 那一行和 C 的 `sizeof` 一致。
5. **跨线程 Arena**：`Arena.ofConfined()` 的段只能在本线程用；要跨线程就传值 / 传句柄，不要传段，逐帧缓冲用 `ThreadLocal` 槽。
6. **`invokeExact` 必须逐字匹配**：`int32_t` 参数写 `I32`、调用点写 `as Int`；写成 `I64` 或 `as Long` 会在运行时抛 `WrongMethodTypeException`，而且可能在**第一次调用**才暴露 —— 新封装必须真的调一次做冒烟。
7. **`MemorySegment.NULL` 与 `ofAddress(0)`**：两者等价，比较时用 `.address() == 0L`。
8. **数组索引按绝对位置**：并行录制里"每槽一份"和"每 worker 一份"的数组不是同一种索引，混用只在部分 worker 上炸（§2.4）。
9. **upcall 里不能抛**：逃出去的异常直接杀 JVM（§3.4）。
10. **`reinterpret` 要写长度**：native 返回的裸地址没有可见长度，`getString` / `get` 之前必须给出准确长度，否则是越界读。
11. **不要凭名字猜语义**：`mcdr_dx12CreateDevice(char* buf, int32_t cap)` 名字里带 Create，实际返回 `int32_t` 并只往 `buf` 里写设备摘要（两阶段字符串），设备的创建是这个调用的副作用 —— 看签名，不要看名字。
12. **导出名不统一**：绝大多数是 `mcdr_dx12*`，但命令编码器那条是 `MCDR_D3D12_create_command_encoder`（大写 + 下划线），抄别的绑定行时别照抄命名。

## 6 文件导航

相对 `Illusion\src\main\kotlin\io\github\dxegp\`：

| 文件 | 职责 |
| --- | --- |
| `d3d12/ffi/MCDR_ABI.kt` | 唯一入口：绑定方法句柄、封装每个导出、维护 `isAvailable()`；资源解包由 NativeLibraryResources 承担 |
| `d3d12/ffi/MCDR_Layout.kt` | 结构体的 `MemoryLayout` 镜像、尺寸断言、与运行中 DLL 的字段偏移比对 |
| `d3d12/ffi/MCDR_PipelineDesc.kt` | 图形管线描述符 → `mcdr_pipeline_desc` 的 arena 打包 |
| `d3d12/ffi/MCDR_MeshPipelineDesc.kt` | mesh 管线描述符（DXIL）→ `mcdr_mesh_pipeline_desc` 的 arena 打包 |
| `diagnostics/IllusionLogger.kt` | FFM 调用失败时的唯一日志出口（`infoMessage` / `warnMessage`） |
| `config/D3D12Settings.kt` | 配置 → 启动期快照，再经 `MCDR_ABI` 的 `Set*` 入口推给 native |
| `config/IllusionConfig.kt` | 所有开关的定义处（本层不读环境变量，只读这里） |

本层之外但必须一起看：

| 路径 | 职责 |
| --- | --- |
| `MCDR\include\mcdr_abi.h` | 契约本体：导出声明、结构体、错误码、版本宏 |
| `MCDR_ABI.kt` 的 `bindAll()` | 加载期校验：版本号 + 每个结构体的 `EXPECTED_C_SIZES` |

## 7 文档维护

- **本文的数字来自头文件与 `MCDR_Layout.kt` 的字段定义**。改了导出数量、结构体字段或版本号，先按 §1.6 的步骤核对两端镜像，再同步开头的表格。
- **判定标准是"代码里能读到的"**。凡是头文件注释没写、Kotlin 侧也没实现的，写"代码中未明确"，不要补合理推测。
- **不要在这里复制源码**。要引代码就引签名与一行关键逻辑，实现细节以源码注释为准 —— 两处都写必然会漂移，而漂移的注释比没有注释更贵。
- **只写当下**。不写设计史与方案对比；必须遵守的写法写成祈使句，已经发现的缺陷单独列成警告清单。
- **新增坑位就加进 §5**，并尽量给出"触发条件 + 失败形式"，因为这一节的价值在于让人一眼认出自己正踩的是哪一条。

## native 资源所有者

`d3d12.ffi.NativeLibraryResources` 负责读取 vendor 清单、按内容刷新运行库与 MCDR.dll。
`MCDR_ABI` 先调用解包，再用自身共享 Arena 完成唯一 libraryLookup、绑定与布局校验；资源组件不反向初始化 ABI。
