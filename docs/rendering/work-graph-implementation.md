# 原版 Work Graph 实施记录

本页跟踪 [架构方案](vanilla-work-graph-plan.md) 的实际实施状态。范围限定光栅、mesh、AA/SR 与呈现；完整 A–K 目标尚未完成。已实现接线、具体实现缺口与尚未运行确认的场景分别记录，最新配套产物的构建和用户确认集中在[当前构建与运行](#当前构建与运行)。

## 透明记录追加与正常帧接线（2026-11-20）

正常帧不再走四子样本 tile 重放：`VanillaPipelineCompiler` 只创建普通图形 PSO，
`OitReconstructionInputs` 去掉 opaque depth seed 的 arm/seal、去掉 execution stream V2 与 `executeTiles`，
`OitRecordFrames.seal` 只封 native recording。启动期那批 `Depth PSO creation failed: HRESULT 0x80070057`
来自 tile 变体的 depth-only PSO，随变体一起消失；transparency 由当帧 per-draw records 的 V3 resolve 产出。

帧时间热点是 `rebuild\MCDR\include\mcdr_oit_shader.h` 的 `AppendV1` 全局分配器 compare-exchange 重试循环：
每个透明片元都 CAS 同一个 uint32。改为单次 `InterlockedAdd` 后，隔离世界 854×480 从 108.5 ms 降到 31 ms、
2560×1334 从 411 ms 降到 32.5 ms，GPU 利用率 100% → 13%。记录预算饱和（`maxRecords` 4096）的对照实验也停在
27.8 ms，证明其余 resolve/history 计算不是瓶颈。逐像素计数 CAS、链表头交换和 144 B 记录写入保留原样。

## 光栅 Work Graph 增量（2026-11-18）

`D3D12TerrainWorkGraph.prepare` 要求渲染线程调用，batch、上传事实和原始 command recording 使用同一线程和 fence。`Prepared.recordPreparedFacts` 对每批事实只回调一次。uniform 到 graphics binding slot 的弱缓存只保存名称与 slot。

terrain packet 的内容复制遵守 `ContentPolicy`：只有完整执行器绑定 `FreezeBindings` 且 tile adapter 已注册时才冻结 VBO、atlas、纹理和其它绑定；默认 `FactsOnly` 只保留命令事实，避免在当前 V2 执行器仍 unavailable 时复制不会被消费的资源。冻结范围的 buffer offset、长度和纹理子资源必须与原 draw 一致，`length == 0` 也不放宽起始 offset 匹配。

capture/prepared-facts 路径还在第一次写 section table 时同步保存 meshlet descriptor offset，构造 `TerrainPreparedFacts` 直接复用这组偏移；普通 terrain raster 不分配这组数组。这样去掉了同一 draw 列表的第二次遍历和 descriptor map 查找，`D3D12MeshletResidency` 的 generation、read lease 和 fence 退休保持原语义。

普通 terrain prepare 路径按渲染线程复用 `ThreadLocal` binding scratch；每次只遍历本批的实际 `bindingCount`，FFM 同样只校验、编码并提交这个有效前缀。切换到绑定更少的管线时，缓存容量和尾部内容不进入 native。capture 与 prepared-facts 路径继续使用独立数组，避免事实对象引用后续批次的可变 scratch。

修正已有 tile envelope 的 64 B 写入顺序：`pixelBounds → identity → draw → properties`，`forceAllTiles` 现在写入 shader 读取的 `properties.x`。本机 DXC 已编译 TileEnvelope、Initialize、Prefix、Scatter 的 SM6.9 入口。native OIT seed 校验删除了没有读取方的 `opaqueSeedPackets` 重复 vector，继续直接读取 Frame journal；journal、packet 和 recording lease 的 fence 生命周期不变。native ABI 保持 26，完整四 lane OIT/tile executor 仍明确返回 unavailable，不把资源准备或 shader 编译通过当成执行完成。

native Work Graph dispatch 还把每次调用创建的 binding 地址和资源状态去重表改为固定 32 槽数组；program 创建阶段已有 `bindingCount <= 32` 约束，因此不改变 ABI、地址顺序、资源去重或状态冲突校验。terrain prepare 根据 Work Graph 的 `uniformCount <= 28` 上限和固定的三项 terrain 输入，把 `bindingCount + 1` 个地址及 `bindingCount + 2` 个声明放进固定数组，避免每次 prepare 的两次堆分配；C ABI 两个 host/GPU 派发入口也用 32 槽数组承接句柄，去掉每次调用的临时堆分配。Work Graph command-list ring 现在在 encoder 创建、槽位切换、abort 重建和销毁时缓存 `ID3D12GraphicsCommandList10`，dispatch 不再每次调用 `QueryInterface`，接口缺失仍返回原 unavailable。`drawTerrainWorkGraph` 在当前录制线程复用动态声明容器的容量，仍按 `graphicsInputRanges` 实际数量增长，不跨线程共享声明内容。mesh 与普通 raster draw 都复用同一 `CommandContext` 中 `pushDescriptors` 已展开的 graphics binding read ranges；命中前校验 root、binding 类型和数量，根签名切换、绑定追加、render pass 附件变化、新 command list 和 push 失败都会清效，cache hit 只合并额外的 vertex/index/indirect 范围，带 layout miss 的路径保留完整重算。`D3D12RenderPassBackend.pushConstants` 在已有 snapshot 容量足够时复用大缓冲，pipeline 在不同常量块大小之间切换不再重复分配；`D3D12SceneDisplay.pushSourceMapping` 复用渲染线程独占的 8 B heap buffer，不再为每次深度传递创建 direct buffer。

terrain draw 的 pass declarations 使用当前录制线程的动态容器，按本次 `graphicsInputRanges` 扩容并在每次录制前清空。输入范围没有固定上限，不能改成固定数组；声明只同步传给 `beginDeclaredPass`，随后由原版侧复制。`CommandContext`、`TerrainProgram` 的 batch/signature 缓存和 D3D12 command list 都遵守单线程录制约束；容器不能跨线程共享或重入。

terrain packet 在原 mesh draw 前准备冻结范围。`FreezeBindings`、已注册 tile adapter 和同一 recording 的 route lease 都齐备时才调用 `retainPacketContent`。vertex、globals、atlas 和材质按实际读取范围保留。visible 和 arguments 使用原 Work Graph `TerrainBatch` 的强租约，由录制 fence 保活，避免两次 GPU 复制。`Texture2D` 和 `Cube` 按 SRV 展开 mip 和 array layer，`MipLevels == UINT32_MAX` 取资源剩余 mip。零长度 buffer binding 取 offset 到资源尾部。默认 `FactsOnly` 只保存 packet 事实。sealed depth、cull、normalization 和 composite 仍待完整执行器接入。

`validateTerrainTileReplay` 校验单阶段的 shader 路由和绑定。`prepareTerrainTilePacketV2Impl` 可以构建阶段计划，消费结果仍为 `UnsupportedContent`。完整执行计划需要录制 opaque seed、water/cloud depth、cull、所有阶段和四 lane 归一化，再发布 `Consumed`。

当前构建和检查结果见[当前构建与运行](#当前构建与运行)。`tile_binning` 已通过 26 个 GPU prefix 用例，报告位于 `build/reports/workgraph/tile-binning/20261118-185430/`。该组用例只检查 prefix。

## Composite 内容租约与 normalization 来源（2026-11-16）

普通 graphics observer 默认只登记命令事实，不触发逐 draw 内容复制。native `ContentPolicy::FreezeBindings` 是执行器的显式请求：只有 adapter 已经准备好、并且同 recording 会消费冻结绑定时，才允许复制精确 buffer 范围或取得 sampled texture COW；冻结失败沿 recording failure 返回。COM 对象租约只保活资源，不能把 `Unfrozen` 当成可重放内容。

Composite 输入在原 render pass 创建前交给 records.seal。native Frame 生成 captureId 并持有真实 sampled-content lease；JVM 使用 64 B 元数据编码实际 texture、owner/version 和 subresource 范围。journal 完成后追加 224 B normalization V2 与实际读取资源，main depth 使用自己的角色，query 与 manifest 按实际 resourceCount 核对。terminal 事件不要求 open pass；旧 normalization V1 保留原 payload 语义。该批接线使用 ABI23，当前版本见[当前构建与运行](#当前构建与运行)。

主 Composite 的实际 PSO 通过专用入口同次绑定不可变来源认证，V2 按 pipeline owner 核对完整 generation、targetCount、版本与三组 hash。JVM 在编译时缓存解析后的 hash 和 contractIdentity 摘要；实际 compiled entries 与 native descriptor 都核对两个 CBV 和实际 SRV 数量，允许跨 shader stage 交错排列。无消费者的 capture 在 seal Unavailable、缺少 sealed 执行流、abort/reset 和部分捕获失败时撤销自己的子资源引用计数，避免原 Composite 写入触发无用全屏复制；共享 read 与已经录制的资源保留各自寿命。

资源分配、状态码与寿命规则见 [FFM ABI](../misc/ffm-abi.md#24-数组与结构体数组)，完整执行契约与缺项见 [Composite 边界](transmittance-tile-execution.md#完整-v2-事件流与-composite-边界)。完整四 lane executor、operation adapters 与 opaque seed executor 尚未实现；已接入的 seed journal 只保存并验证事实，不发布执行成功。当前配套产物与验证见[当前构建与运行](#当前构建与运行)。

## Kotlin 热路径与 FFM 分配整理（2026-11-16）

`FfmScratch` 拥有小型参数缓存和大目标调用作用域，大小、对齐与生命周期规则统一见 [FFM ABI](../misc/ffm-abi.md#24-数组与结构体数组)。批量 draw、描述符、查询和附件数组都按真实布局计算长度；同次调用的全部指针一起覆盖 native 同步消费和返回值复制。`recordCommands`、编码作用域和 staging writer 显式内联，分配器主体保持普通函数；作用域只调用一次业务 lambda，避免内联把整个批量编码复制到大小两条分支。查询结果拒绝短输出数组，不静默截断。OIT 事件链按实际 pass 资源和事件内 native sequence 的最小/最大区间校验，区间交错时保持 unavailable。

原版按设备限制拆分 indirect command 时，`D3D12MeshDrawCapture` 接受同 owner 内合法、非空且按 20 B command 对齐的连续子范围，保持原顺序并缓存对应 batch。prepared facts 在 native batch 分配前完成构建，防止事实构建失败遗留句柄。地形生产 shader 保持五节点与现有布局。

普通世界输出现在返回实际 sampling grid，SceneDisplay 据此选择深度搬运映射；显示目标首次分配时即登记 encoder 关闭资源，resize 候选创建失败则释放部分附件并保留旧目标。

terrain 透明采集移除没有完整执行流消费者的逐 draw 全屏四 lane scratch 分配，只保存实际 CPU facts。Composite 先校验全部纹理布局，再采集内容身份；版本化结构按真实布局分配。native 录制失败与 stale 返回 JVM 的对应错误，不能吞成普通输入缺失。深度内容保存的支持范围和缺口见 [tile 执行方案](transmittance-tile-execution.md#完整-v2-事件流与-composite-边界)。

native 的 normalization 描述按实际字段为 288 B，C++ 静态断言和 JVM 预期尺寸已同步修正；sampled capture 的 C ABI 单元补齐 device 模块导入，normalization 租约修正意外添加的 pointee const。terrain draw receipt 和 tile 录制失败标记为 recording failure，避免误判为已提交未知。旧 BatchV2/V3 继续拒绝无法表达的完整 tile 执行，完整事件 adapter 与四 lane executor 仍有明确实现缺口。

验证：native Release/x64 与 JVM `compileKotlin compileJava` 通过；本轮 DLL SHA256 为 `ACE4ADCCDDC3BAF8238C2EEAFA5059D4B8A103933EC41A772BA366FECB49E83B`，native 构建日志位于 MCDR 的 `target/inline-ffm-native-build.log`。`src/probes/ffi_allocation/` 使用实际 JDK FFM 与生产分配器，已检查跨槽扩容、小缓存边界、大目标及高对齐目标的作用域、异常释放、空数组策略和 normalization 布局；测试不进入发布产物。`javap` 确认 bulk 方法只含一次正确签名的 `invokeExact`，没有 scratch lambda 调度；FFM 和 JVM 日志位于 `build/reports/workgraph/ffm-allocation-check/` 与 `inline-ffm-jvm-build.log`。terrain command 子范围的独立检查也已通过。本轮没有运行游戏、GPU 深度保存检查或性能采样。

## Work Graph 派发状态与别名校验（2026-11-17）

CPU-record 派发现在先完成设备归属、stride/对齐、记录范围、heap/UAV 能力和资源别名检查，再登记录制租约或改资源状态；同一底层资源的 CBV/SRV 读绑定合并为读状态，UAV 与读绑定冲突直接拒绝，重复 UAV 允许合并，零记录保持无副作用。地形 Work Graph 的 initialize/finalize 会暂时使用 compute PSO，正式 `ExecuteIndirect` 前按当前深度路径恢复 graphics PSO，并同步 `cachedPso`。

Work Graph 通用资源状态和租约使用 `src/probes/workgraph_reuse/run.ps1` 验证，tile prefix 使用 `src/probes/tile_binning/run.ps1` 验证。FFM 分配和 DLL 布局使用 `src/probes/ffi_allocation/run.ps1` 验证。

native seed 预检现在只读检查已确认的 graphics packet：它核对 recording/fence/frame/program/resource 身份、首个完整深度清除、D32 DSV、真实深度 attachment、PSO/pipeline generation、command family，以及 packet 的 content/adapter/route 状态。seal 阶段不会冻结内容、创建 descriptor 或清除缺失掩码；任何缺项继续返回 `Unavailable`，不会伪造 sealed executor。该变更随上述 DLL 构建通过，未把只读事实检查当作完整 OIT executor。

本轮把 FFM 的短输出和 render-pass 描述编码接入同一套 Kotlin `inline` scratch 作用域：提交/结算回执、声明 pass 和 raster pass 只在同步 downcall 内复用独立槽；预计超过缓存上限、要求更高对齐或发生嵌套时仍由 confined arena 承担，作用域内不会回退到线程缓存。返回值在 arena 关闭前复制成 JVM 值，native 不保留临时地址。ABI 仍为 26，未接入没有实际消费者的 stage-plan 入口。FFM 分配探针与 JVM 编译已通过，native Release|x64 产物 SHA256 为 `2EA9422272B26CCFDF9D5828DB210D0E2A9C91BDB6AD0745EA942BC8ED451A56`。

本轮 JVM `compileKotlin --no-configuration-cache --rerun-tasks` 通过；8 条 Kotlin 警告仍来自既有代码。`FfmScratch` 的线程槽只复用小目标，超过 `64 KiB`、要求更高对齐或发生嵌套时使用本次调用的 confined arena；每个数组按 `count × layout.byteSize()` 和 `layout.byteAlignment()` 分配，空数组的 NULL/NON_NULL 语义由调用点声明。短编码作用域和分配包装使用显式 `inline`，业务调度、分配器主体和 SDK 调用保持普通函数。`git diff --check` 通过。

JVM 显示帧退休的状态提交点已移到 native reset/abort 成功之后。若退休调用失败，`displayFrameStarted` 保持为活动状态，后续关闭流程仍可尝试 abort，不会在 native owner 尚未释放时提前进入下一帧。修复后 `./gradlew.bat compileKotlin --no-configuration-cache --rerun-tasks` 通过；现有 8 条 Kotlin 警告均来自本轮之外的既有代码。本次仍未运行游戏，动态画面由用户确认。

mesh CPU 热路径移除了 `D3D12SectionGeometry.captureUpload` 在构造 `LayerUpload` 前的额外 `built.meshlets.toList()`。`LayerUpload` 继续负责唯一一次不可变快照，meshlet 顺序、内容以及 encoder/fence owner 语义不变；同一编译命令复核通过。

`D3D12MeshDrawCapture` 进一步移除 `Batch`/`terrainDrawFacts` 的二次不可变列表复制，并让 duplicate allocation lease 只在实际发现重复 allocation 时创建临时列表；capture 与 non-capture 的顺序、精确 read set 和 fence owner 不变。该修改已通过同一 Kotlin 编译和 diff 检查。

VRS frameprep 的 `D3D12ShadingRateImage` 不再为 `depthTex` 创建未被任何 dispatch 或绑定消费的 `depthView`。native `produceVrs` 自行建立深度 SRV，JVM 只保留深度纹理、copy、候选图和共享 encoder 的 fence owner；尺寸变化与资源释放路径保持原语义。

## 分工与依赖

| 工作包 | 执行者 | 修改边界 | 状态 |
| --- | --- | --- | --- |
| A1 提交、回调、退出 | 6.1sol `wg_submission` / `checked-dlss-shutdown`，主代理集成 | encoder 提交与关闭、readback 完成队列、native 提交等待 | checked device/surface teardown、Present 完整收尾与失败传播已接线；velocity 管线误用资源销毁入口的两处 JVM 调用已修正，实际 XeSS 世界/缩放/正常退出通过 |
| A2 上传、描述符、格式 | 6.1sol `wg_resources`，主代理集成 | transient memory、native 描述符分配、格式与 footprint | upload fence 退休、descriptor 扩页与格式修复已接线；UPLOAD/READBACK heap 保持固定状态，缩放世界已持续出图；完整场景/并发验收仍未完成 |
| B 真实 SPI 批量入口 | 6.1sol `wg_batch` / `bindings` / `raster_motion_remaining` / `raster_exact_motion`，主代理集成 | RenderPassBackend、native draw 与批次录制 | 四组 SPI 与 motion 分支均使用 bulk ABI；异构逐 draw 参数/sidecar 保序传输，共享 PSO 与绑定准备；root constants、FFM 和 foreign-state 恢复已接线 |
| C 帧协调与业务迁移 | 6.1sol `frame` / `frame-repair` / `scheduler-next` / `raster_exact_motion` / `raster_motion_remaining` / `particle_motion_producer`，主代理统筹 | 帧输入、执行器、重建与呈现边界 | 显式帧 ABI、context 独立状态和真实帧号已接入；普通 raster 的实际附件与 CBV/SRV/VB/IB/indirect 读取声明已接线，bulk 保持一次 FFM 和完整读取并集；compact scheduler 使用实际 subresource 访问前沿；新 DLL 的世界、尺寸变化与正常退出已运行 |
| D WG 运行与编译 | 主代理协调共享接口，`gpu-next` / `gpu-input-repair` | capability、program/instance、backing memory、编译与派发 | CPU-record、buffer-handle 输入、根布局和总 backing 预算已实现；GPU-input 仍按显式生产者契约使用，不能把独立验证结果当成游戏路径已全面切换 |
| E 地形工作图 | 6.1sol `terrain`，主代理集成 | 范围输入、可见表、间接 mesh 绘制 | 生产保留五节点与已有 ExecuteIndirect/visible MS/租约接线；三节点候选 80 次真实 GPU 输出检查通过，但单列表计时长尾使其不进入正式资源；驻留编码优化已构建 |
| F 帧准备工作图 | 6.1sol `frameprep` / `reconstruction-next` / `frameprep_wg_production` / `raster_exact_motion` / `raster_exposure` / `particle_motion_producer`，主代理集成 | tile 分类、光栅 motion、曝光、AA/SR/FG 输入契约 | reactive/composition/responsive 和 VRS 工作图进入生产路径；entity model、第三人称普通持物及已知 opaque quad particle 顶点来源与原着色器 MRT 已接线；LDR unity 与线性 HDR 测光分开；完整 guides 与画质尚未验收 |
| G 缓存与性能整理 | 6.1sol 编译代理与主代理集成 | 编译缓存、状态/绑定减少、测量与文档 | Naga 完成缓存与设备源缓存有界；真实 ShaderManager 重载统一撤销版本并预热 normal/flipY、terrain、设备支持的 frameprep WG、曝光管线与 DisplayFrameGuides；frontend/device 独立租约已接线；BindingPacket、multi-draw host arrays 与 meshlet descriptor staging 均复用；完整重载场景及实际性能测量分列验收 |

## 协作规则

- 保留本轮开始前已有的源码修改；不创建提交，不重置工作树。
- `MCDR_ABI.kt`、公共 C ABI、模块接口和工程编译项由主代理统一协调，子代理先报告所需签名和状态字段。
- 同一 native 设备文件按提交、描述符、draw 函数范围分别修改，禁止整文件覆盖。
- 每项交付报告实际接线、相关构建结果及仍未验证的运行行为，不以声明或空壳类判定完成。
- 全量 JVM/native 构建由主代理串行协调；使用本机 JDK 和现有 VS2026/依赖，不下载工作区 JDK。
- 验证针对实际修改和明确风险选择，不以增加探针数量作为交付目标；游戏画面和 PIX/Nsight 性能结论必须有对应实测。
- 业务源码只保留运行时契约、资源状态和生命周期错误处理；必要的独立行为验证放在 `src/probes/`，不随游戏启动、tick 或重载执行，也不进入生产 JAR。测试替身、计数器、故障注入与验收专用开关均不得进入生产模块。
- 动态画质由用户在具体运行场景中确认；代理负责构建、日志、契约、生命周期和性能数据。静态截图不证明拖影、闪烁或运动稳定性，未确认项目按实际状态记录。

## 验证记录

以下历史构建和运行记录对应各自注明的产物。最新配置/世界输出重构的构建、包检查与客户端覆盖见 [当前构建与运行](#当前构建与运行)。

### 半透明固定残影确认

用户已确认运行中的客户端“残影消失了”。固定留屏问题按这次动态反馈记为已恢复；下文各版本的未解决记录描述各轮当时的结果。该反馈不用于验证尚未编译部署的新 native 内容冻结或完整 tile executor。

### ABI21 TRANSMITTANCE tile route 接线（2026-11-15）

四子样本 TRANSMITTANCE 的正式生产验证已覆盖 7 个真实 OIT profile、DEPTH_BOUNDS/TRANSMITTANCE/ACCUMULATE、normal/flipY、36 个 IA route，以及 terrain 16/32/64 mesh route；DXC 已实际编译 `vs_6_9`、`gs_6_9`、`ps_6_9`、`ms_6_9`、`lib_6_9` 和 `cs_6_9`，并核对 viewport/array index、原始 varyings 与 Work Graph 入口。验证脚本和结果见 `build/reports/workgraph/CompileTileShaderProduction.ps1` 与 `tile-production-validation-result.md`。

JVM `TerrainPipelineCompiler` 现在在真实 16/32/64 meshlet 生成点消费同一 Candidate 对象，保存 Candidate/typed Unsupported 及 route 生命周期描述；native 新增了版本化的 tile root、DispatchMesh indirect signature 和强 route lease 注册 ABI。普通 terrain draw 没有改走这条路。

native 的 tile replay 接线已经存在：terrain binning 按 initialize → Work Graph → prefix → scatter 四个 declared pass 录制，scatter 结束前把 commands/control 从 UAV 转为 INDIRECT_ARGUMENT；三个 replay stage 也分别声明实际输入、RT/DSV 和最终 COMMON 状态。stage 输出按原版语义检查：`DEPTH_BOUNDS` 只写原 bounds，`TRANSMITTANCE` 写实际 1/2/4 个 coefficient targets，`ACCUMULATE` 只写 Q；cull、terrain/cloud depth 与 normalization 各自属于独立执行事件。

当前仍未发布可消费的 tile packet。`BatchV2` 还没有表达 seal 后的 depth/cull/normalization 事件。native terrain capture 按精确范围冻结 buffer、Texture2D/Cube 子资源与独立 SRV。支持范围、所有权和运行开销限制集中在 [TRANSMITTANCE tile 执行契约](transmittance-tile-execution.md#内容冻结与失败语义)。完整执行流使用真实 allocation owner，避免逐 draw 镜像静态 VBO/atlas。`UnsupportedRoute` 保留实际 route 缺失的语义。V2 normalization 的 `recordingSerial` 必须与当前 OIT recording 相等。V2 seal 从真实地形 receipt 和已登记的读取范围构建有序 selection，供完整执行器创建 resident batch 的 replay plan。旧 V3 按历史 ordinal 准备的 plan 不参与 V2 selection。完整 replay 仍为 unavailable。

`compileKotlin compileJava processResources` 已通过。2026-11-16 的事件采集在实际成功 draw 点保存 depth copy、bounds copy/cull 和 composite 的输入输出，不再靠 pipeline 名称分类或外层方法返回宣告完成。tile envelope 原有 HLSL 80 B / CPU 64 B 错位已统一为 64 B；DXC 编译 node、initialize、prefix、scatter 均通过，node/scatter 反射确认 stride=64。tile 录制后失败新增 `RecordingFailed`，JVM 按 Rejected 终止本帧，避免误走 unavailable。

原 draw packet、四 lane 和 resident tile batch 的身份已分离；JVM 不再固定四个 packet 槽，也不把 lane/index 重写为原 draw ordinal。同 packet/lane 的重复或重叠区域拒绝发布，各 packet 的四 lane 区域必须齐备。旧 resolve V4 无法表示完整聚合，JVM 在任何 tile GPU 录制之前返回 `EXECUTION_STREAM_UNAVAILABLE`，删除 `size == 4` 静默弃用和错误重编号；完整 event-stream executor 仍是明确的实现缺口。

配套的 `compileKotlin compileJava` 与依赖方向检查已通过，日志为 `build/reports/workgraph/tile-packet-identity-jvm-build.log`。本次没有打包或部署 DLL，也没有运行客户端；新 native 冻结能力的编译状态见下一段。

Windows SDK 与 MSVC 安装已恢复。最新 native `Release|x64` 已完成编译和链接，0 个警告、0 个错误；日志在 native 工作树的 `build-native-final-2.log`。async 类型改为独立 opaque C handles，`correctedStride` 定义归回 bundle 模块，DEFAULT/纹理冻结能力和私有 owner 均包含在该构建中。没有部署 DLL、启动游戏或采集；用户“残影消失”的反馈对应此前客户端产物。

native execution stream V1 已有 begin/query/receipt/frontier/append/seal 六个公共入口。Frame 持有 builder、成功 terrain draw receipt 和事件资源 lease；terrain receipt 在真实 `ExecuteIndirect` 与 `finishDeclaredPass` 成功后确认。JVM 已通过唯一 FFM 出口绑定六个入口，在真实 TERRAIN ACCUMULATE 调用前后查询 receipt，保留 frontend invocation 的父子关系和独立的 native producer 身份。before-query 直接读取 native 最后确认序号，复杂度 O(1)；after-query 以二分查找取得本次新增 receipt，成功子事件从父事件结果中去重。查询失败保留成功 frontend draw 事实并独立记录 `NATIVE_RECEIPT`。九个结构已核对 C 大小，111 个字段已加入两端 ABI layout 对照，返回值校验版本、保留字段和帧身份。

frontend manifest 现在保存实际 direct、bulk、collection 和 indirect draw selection；bulk 使用原 buffer position 的绝对读取，PointerBuffer 保留字节偏移，indirect 保留真实 GPU buffer 范围。只有当前采集 pass 才展开 selection。原 frame seal 时会实际 begin/query/seal native stream，并随原 frame 退休。跨阶段 drawGroup 的 CPU 选择关联已接线，具体范围见下文；native 全阶段 receipt 与完整内容租约尚未建立，append 当前只有绑定。实际追加计数仍为零，native 据真实 receipt 返回缺失事件或执行 adapter，JVM 不把空 manifest 当成完整流。此前 stream selection 的 Kotlin/Java 编译已通过，日志为 `build/reports/workgraph/oit-stream-selection-jvm-build.log`；本轮新增阶段事实的构建单列记录。

四 lane 的归一化与平均 shader、独立 normalized composite 已实现，1/2/4 coefficient target 的 `cs_6_9` 与 composite 的 normal/flipY `ps_6_9` 均已通过 DXC 编译。native 已实现真实 root/PSO、独立 batch 常量与 descriptor、四 lane dispatch 和平均 dispatch；每次 recording 共享一张 RGBA32F corrected 与固定 `68×68×64` normalized scratch，避免按 batch 重复分配整帧纹理。批次禁止交错，区域不得重叠，资源由实际 recording lease 保留；私有输出按 raw COM resource 声明，不经通用对象销毁入口。普通录制失败使用独立 `recordingFailed` 阻止提交并允许安全销毁，提交结果未知使用原 `submissionFailed`。

本轮 native `Release|x64` 已完成编译和链接，0 个警告、0 个错误，日志为 native 工作树的 `build-native-normalization-stream.log`；JVM `compileKotlin compileJava` 通过，日志为 `build/reports/workgraph/oit-normalization-stream-jvm-build.log`。独立 FFM 检查加载该新 DLL，对照真实 JVM mirror，553 个 ABI 字段全部匹配，其中 execution stream 为 111 个字段；六个 stream 和七个 normalization 导出均存在，记录为 `build/reports/workgraph/oit-stream-layout-check.log`。检查未初始化设备，没有替换运行中的 DLL、打包部署、启动游戏或采集。本轮未实测新 executor 的 GPU 输出和性能。

这些低层 dispatch 返回 `RECORDED_UNVERIFIED`，query 不暴露可消费 binding，publication token 保持零。depth/cull/water/各 producer 的完整事件执行、内容租约、全帧覆盖检查与发布仍须接通，当前不创建没有生产消费者的 JVM normalization wrapper，也不把独立归一化当成完整 tile executor。

后续生命周期复核发现，JVM 的同步提交、异步结算和 display abort 曾把已知录制拒绝当成提交结果未知，阻止实际关闭。现已使用版本化 typed submit/settle/status 与 checked destroy：只有真实提交返回 context fence；已知拒绝不执行失败列表、不轮转 transient，等待此前真实 fence 后丢弃失败列表；unknown/timeout 保留资源。确认销毁后才释放 JVM 命令寿命资源。丢弃后 encoder 保持终止状态，避免继续使用只录制、未执行的上传事实。截图/PNG 取消关闭自有读回 buffer，Tracy 只恢复等待状态；第三方普通 Runnable 缺少取消 owner 时明确记录，不执行成功回调。正常 draw 与 context control 不新增状态查询。两处显示取消入口共用 checked abort，motion 解绑通过 context control，清理不再重新打开录制。

三阶段 terrain 已在现有 `record(prepared)` 完整成功之后暂存 CPU 选择事实，随成功 frontend event 发布。drawGroup 使用实际 buffer owner/range、有序 indexed command/instance/section/primitive selection、geometry generation 和 atlas/lightmap 身份；缺少材质身份时保留明确原因。Minecraft 的三个 stage 确实复用同一 command buffer owner/range；`Batch` 因此按需持有一份不可变有序 selection，三阶段共享其条目与缓存结构化 hash，精确比较仍保留。普通非采集 draw 不新增 prepared facts 或 selection 副本；原 stage PSO 与 ACCUMULATE history 路径保持原语义。新事实已随本轮 JVM 编译通过，未提供非 ACCUMULATE native receipt。静态几何、meshlet descriptor buffer 和材质纹理仍需由真实分配和写入所有者保证内容版本。逐条 native receipt 的字段与帧身份偏移已改为固定缓存，避免结果循环中重复解析 FFM layout；未测量其帧时间收益。

本轮生命周期修改的最终 native Release/x64 编译链接通过，0 警告、0 错误，日志为 native `build-recording-lifecycle-final.log`。JVM 编译与依赖方向检查记录为 `build/reports/workgraph/recording-lifecycle-jvm-final-build.log`。独立 FFM 检查加载最终 DLL，553 个字段全部匹配，其中 stream 为 111 个字段，六个 stream、七个 normalization、四个生命周期导出齐全；记录为 `recording-lifecycle-abi-check.log`。Tracy 的取消逻辑由普通业务对象执行，Mixin 仅传 SAM lambda；静态截图/PNG 注入体内联，PNG callback 不再创建 Mixin 内部匿名类。对应字节码在 `recording-lifecycle-mixin-bytecode.txt`。没有打包、替换客户端或运行游戏；失败录制与取消的真实设备行为尚未运行验证。

原 draw 的 geometry allocation 与 meshlet descriptor 寿命已接入：原版 dispatcher 锁内取得 TLSF extraction pin，普通 indexed/custom-index、indirect/separate 和 Work Graph 的实际读取另持 submission 引用，随真实 context fence 释放；producer copy 前同样取得目标区间引用，失败时退休尚未发布的 allocation。producer 登记先结算旧异步提交并打开当前列表，避免新引用被旧 fence 提前释放。native copy 保留实际 source/destination object，JVM 两条 buffer copy 入口检查录制结果。TLSF free 和 heap close 等最后引用释放，不逐 draw 复制静态 VBO。meshlet selection 保存固定 buffer generation/offset/count，replacement 与失败候选由同一 owner 退休；同提交按真实 owner 去重。tile replay 的 geometry/material/UPLOAD 完整内容 owner 与事件 adapter 仍须接齐，当前不发布 corrected frame。源码接点和范围见 `build/reports/workgraph/geometry-original-draw-implementation-report.md`。

该批 native Release/x64 编译链接成功，记录为 native 工作树 `build-geometry-lifetime-native.log`；最终 `compileKotlin compileJava processResources` 与依赖方向检查成功，记录为 `build/reports/workgraph/geometry-lifetime-jvm-final-build.log`。最终字节码确认两个静态读回 Mixin 的 Companion 为 private，注入体直接调用业务对象，地形 uploader 使用先结算提交的登记入口；记录为 `geometry-lifetime-mixin-final-bytecode.txt`。`processResources` 自动执行 `copyNative`，新 DLL 已暂存到 `build/generated/native` 和开发目录 `run/dxrmc`；没有重启或运行客户端。vendor staging 仍报告重建树的 XeSS/vcpkg 运行库源目录缺失，本批没有改动 SDK 打包来源。实际 Mixin 变换和新资源寿命的游戏运行结果尚未验证。

当前 `D:\Projects\MinecraftMod\DXRMC\rebuild\MCDR` 的 Release/x64 构建已通过，记录为 `frameprep-prewarm-native-build-withdeps.log`，包含 frameprep 预热 ABI 和已接入的 provider/VRS 工作图；构建复用 `D:\Projects\MinecraftMod\DXRMC\MCDR` 的现有 SDK/vcpkg 依赖及本机 VS2026。JVM 的 `compileKotlin compileJava processResources` 也已通过，记录为 `prewarm-jvm-build.log`。Kotlin `2.5.0-RC` 下载问题通过本地验证用 Maven Central 仓库路由解决，未更换项目编译器版本或下载工作区 JDK。旧构建日志和 SHA256 仅说明对应历史场景使用的产物，不代表本轮新 DLL。Naga processor 的既有测试/Release 构建未在本次重复运行。

游戏实测已覆盖 SMAA 原版世界、地形/手部/UI、RTSS、窗口缩放及正常保存退出。Streamline DLSS SR 已实际创建并出图；首次缩放暴露 JVM 把 native 的成功清除返回值 `0` 错当失败，已修正 `DLSSOutput.release` 并调整原先使用错误假定的探针，修正后运行记录单列。F3+T/ESC 自动输入没有得到可靠确认，不能据此宣称资源重载/暂停测试通过。PIX timing 捕获停在收尾，只留下未验证的部分文件；GPU attach 返回 `PIXTOOL17`，要求以 GPU capture 模式启动。没有有效 PIX/Nsight 分析或同场景性能比较结论。

### 无世界菜单黑屏修复（2026-11-12）

此前世界出图的证据没有覆盖标题界面。`VanillaFrameCoordinator.beforeGui()` 在 `IDLE` 分支直接返回，跳过了 `D3D12GUISplit.prepareForGui()`；主目标附件受渲染缩放影响时，panorama、GUI 和 blur 已通过 mixin 绘制到窗口尺寸的 GUI 目标，但 `preparedThisFrame` 没有置位，最终 Present 仍选中被清成黑色的主目标。RTSS 位于 Present 叠加路径，因而黑屏时仍然可见。

修复使无世界帧在清理重建输入后同样执行 GUI 准备，再交给原版 `GuiRenderer.render()`。菜单帧不创建世界帧身份，也不运行世界 AA/SR 或进入 native world-post 协议；GUI 准备、实际绘制目标和最终呈现源在同一帧内保持一致。

- 相同 50% 渲染缩放、DLSS 关闭配置下做修复前后实机对照：主目标 `427×240`、GUI 目标 `854×480`；旧代码持续全黑，修复后标题背景、文字和按钮正常。
- 修复后的同一会话覆盖设置页 blur、多次窗口尺寸变化与 GUI target 重建，正常关闭并完成 surface/device teardown，Gradle 返回 `BUILD SUCCESSFUL`。Kotlin/Java 编译及当前 native staging 随运行任务完成；本修复不修改 native。
- 本地证据：`game-menu-scale50-before-fix.log/.png`、`game-menu-scale50-fixed.log/.png`、`game-menu-scale50-resize-settled.png`，均位于 `build/reports/workgraph/`。测试前的 options/config 已备份，测试后按原文件恢复并核对 hash。
- 恢复本次测试开始时的原配置（DLSS 开启、普通渲染缩放记忆为 100%）后，再次不带 quick-play 启动，标题界面正常出图；`game-menu-final-original-config.log/.png` 记录画面及正常关闭，Gradle 返回 `BUILD SUCCESSFUL`。
- 另一次进入世界的日志 `game-client-menu-original-config.log` 曾在 `selected post adapter did not execute` 后出现 `GUI phase is out of order or stale`。统一的 world-pass abort/reset 现在负责清理失败帧的 graphics pass、native frame、GUI 本帧状态和 phase identity，并继续抛出原错误，避免接续的 stale phase 错误。这项清理不证明最初的 adapter 失败已被消除；完整世界切换/SDK 矩阵仍需实测。
- 最新 native/JVM fresh run 在 `dxrMcRenderScale:50` 下点击警告页后，`build/reports/workgraph/menu-fresh-scale50-main.png` 已确认标题全景、按钮、文字和 RTSS 均可见；本次使用的 `MCDR.dll` SHA256 为 `60ED51B5CEDBB5A3CE75ACA02F599F52E33334F209E72E7A416223737E31BFED`，测试结束后仅处理本项目客户端，`run/options.txt` 已恢复为 `dxrMcRenderScale:100`。无世界帧现在先清理上一世界的 HUD-less/UI composition 状态，再准备窗口尺寸的 GUI target，避免 Present 继续选择被清黑的主 target。

2026-11-12 15:33 的新编译回归再次确认同一修复没有被 JVM 重编译或 native staging 覆盖：Gradle 9.9.1 下 `compileKotlin compileJava copyNative stageShaderProcessor` 成功，使用当前 `MCDR.dll`（SHA256 `1894bc4e12d8e29caa49c236ed4d764057a136c1fc48eba87514203e69229ed9`）启动无 quick-play 客户端，点击早期加载警告页的“Proceed to main menu”后，标题背景、按钮、文字和 RTSS 均可见。截图为 `build/reports/workgraph/menu-current-recheck-final2.png`，日志为 `build/reports/workgraph/menu-current-recheck.log`；正常关闭完成 surface/device teardown，配置文件按启动前备份恢复并核对 SHA256。

- 当前独立验证只保留 `src/probes/` 下的生产源码探针：`compilation/run.ps1` 检查编译键、失败重试、有界缓存与版本撤销，`terrain_atlas/run.ps1` 检查图集扩容、fence 退休和失败回滚。它们在独立进程中编译或调用生产类，测试替身只存在于探针目录，不进入 `src/main`。本轮没有追加重复分支测试，也没有重跑无关探针。
- 旧的启动自检、tick 测试钩子、fake backend 清单和历史 harness 记录不再作为当前工作包验收依据；生产路径不创建测试资源、不注入故意失败、不维护测试计数器。GPU/SDK 行为只能由真实 native 构建和客户端场景验证确认。
- `src/main` 当前只保留运行时契约、资源状态转换和生命周期错误处理；`git diff --check` 通过。若编译依赖无法下载，记录为环境阻塞，不通过修改业务代码绕过。

## 图形常量契约

使用 [图形绑定契约](graphics-bindings.md)。当前源码 native/JVM ABI 为 21；原版帧、显示帧、PreparedFrame 和 provider mask schema 为 2，世界输出和 OIT Resolve schema 为 3，terrain Begin/metadata/draw schema 为 2，capture shader ABI 为 5。显示帧布局仍为 672 字节，mesh 描述符为 104 字节；新增真实 Present 回执为 112 字节，必须配套当前 Naga processor 与 DLL。完整布局、两阶段 Prepare/Submit、回执与有序批量绑定入口见 [FFM ABI](../misc/ffm-abi.md)。下文旧 ABI 章节保留各自产物的历史记录，当前构建以 ABI21 为准。

## 尚未完成的目标

### 三阶段 terrain draw receipts（2026-11-16）

原版 terrain 的 `DEPTH_BOUNDS`、`TRANSMITTANCE`、`ACCUMULATE` 现在都在真实 `ExecuteIndirect` 与 `finishDeclaredPass` 成功点取得 native V2 receipt。JVM 在实际 prepared draw 前开启 token，结束后读取 receipt；receipt 保存 frame、producer/scope、pass、draw、stage 和实际 pipeline 身份，并由已有 `OitRecordFrames` 持有。失败走 `recordingFailed`，未知提交沿原生命周期保留资源；旧 V1 ACCUMULATE receipt/frontier 语义不变。

V2 ABI 为 `mcdr_oit_terrain_stage_begin_v2` 120 字节、`mcdr_oit_terrain_stage_receipt_v2` 136 字节，增加真实 pipeline 指针/身份校验。journal 归原 Frame，容量按倍增并计入既有预算，确认点不再分配。receipt 的 `contentState` 仍是 `UNSUPPORTED`，这一步只证明原版 draw 已执行并留下可审计身份，不宣称 tile 内容已冻结或完整 executor 已可消费。

本轮 native Release/x64 与 JVM `compileKotlin compileJava` 均通过；独立 FFM 检查加载新 DLL，584 个 ABI 字段匹配，旧 execution stream 仍为 111 个字段，并找到六个 V1 stream、四个 V2 terrain receipt、七个 normalization 和四个 command lifecycle 导出。用户对上一版运行客户端确认半透明残影已消失；本轮新 DLL 尚未启动游戏，动态画面仍需用户确认。

### Terrain 读取范围与 lane 深度资源（2026-11-16）

真实 terrain prepare 现在保存逐 command 的 allocation owner/generation、选中 VBO 字节区间与 meshlet descriptor generation/range；JVM 在三个 stage 的原 draw 前，通过唯一 FFM 出口向 active token 注册。native 校验同 recording 的 batch、vertex binding、atlas 范围、command 顺序与真实 section 数量，成功 receipt 保留范围、object leases 和 TerrainBatch shared lease。JVM 原 allocation/descriptor pin 继续随同 context fence 退休；native 不建全局 allocation 表。注册数组、journal 与排序临时副本都进入既有预算。

tile owner 已分开持有 D32_FLOAT 的 main/terrain/cloud 三张数组深度，三个 geometry stage 共用 mainDepth；RGBA32_FLOAT scratchDepth 保留给归一化。mesh shader 已按实际属性分量读取 word，原版 BLOCK 的最后 UV2 只读 4 字节，避免越过有效顶点末端。18 个真实格式 terrain stage/orientation/meshlet 变体已通过 DXC，验证生成器使用真实 VertexFormat，拒绝属性全归零的无效编译输入。

native Release/x64 构建完成，0 个警告、0 个错误；JVM `compileKotlin compileJava` 与依赖方向检查通过。新 DLL 与当前 JVM mirror 共 618 个 ABI 字段匹配，旧 execution stream 仍为 111 个字段，新增两个 read-set 导出存在。构建记录为 native `build-terrain-readset-native.log`、JVM `build/reports/workgraph/terrain-readset-jvm-build.log` 与 `terrain-readset-abi-check.log`。本轮未执行 processResources、打包、部署或启动游戏。

读取范围注册仍不构成可执行 tile 内容。UPLOAD 真实 owner、内容版本及 candidate 逐 command 对应已接入，具体约束和剩余内容闭包见 [TRANSMITTANCE tile 执行契约](transmittance-tile-execution.md#同-recording-的读取范围注册)。receipt 保持 Unsupported；深度初始化、全部 producer、完整事件执行和 corrected 输出仍未完成。用户“残影消失了”的确认对应此前运行客户端。

### UPLOAD 与 sampled texture 内容所有权（2026-11-16）

terrain prepare 采集 uniform、globals 和 sections 的真实 UPLOAD allocation/read lease，native 在原 draw 前核对实际资源范围和 candidate 的 base vertex、descriptor offset/count。设备侧实现了纹理版本 owner、真实 SRV 覆盖、首次冲突写入前的 GPU 保存，以及随 recording fence 退休的私有 backing/view。公共 texture writer 与 device 外的 terrain、blit/sharpen、SMAA 写点均已接入；完整 executor 尚无 capture caller，不在当前必然 unavailable 的旧入口分配材质副本。

native Release/x64 构建成功，0 警告、0 错误；JVM `compileKotlin compileJava` 与依赖方向检查成功。真实新 DLL 与 JVM mirror 的 618 个 ABI 字段匹配，旧 stream 的 111 个字段和原导出均保留。记录为 native `build-sampled-upload-owner-native.log`、JVM `build/reports/workgraph/sampled-upload-owner-jvm-build.log` 和 `sampled-upload-owner-abi-check.log`。没有执行 processResources、打包、替换客户端或运行游戏；新增 GPU 内容保存路径尚无运行结果。

### 完整事件 journal 与 execution V2 接线（2026-11-16）

JVM 在真实 scope/pass 开始、返回、失败及 draw 成功点保存不可变 journal，seal 按该顺序调用独立 V2 stream；旧 V1 只保留 ACCUMULATE history 的 receipt/frontier 查询，不再创建空 stream 进行 seal。每条 terrain 命令先查询真实三阶段 receipt，核对 invocation、frame/scope/pass/draw、pipeline 和版本；commandOrdinal 来自完整 receipt 的 nativeSequence 顺序。普通 DEPTH_BOUNDS/TRANSMITTANCE 的 shaderGeneration=0 按实际值保留。receipt 覆盖不齐、缺内容或缺 adapter 时返回具体缺项；当前不产生 sealed handle 或 corrected 输出。

sampled texture writer 现在即使没有 capture owner，也在纹理自身保存实际 recording/fence 身份，首次 capture 会检查其它未完成 writer。实际提交与已丢弃录制分别退休；版本容器的真实容量计入预算，纹理子资源数量在 owner 内缓存。CPU 写入的锁由返回 lease 持有至写入结束。完整 executor 尚无 capture caller，GPU 保存、跨 recording 等待和退休行为仍缺实际运行验证。

配套 native Release/x64 编译链接成功，0 警告、0 错误，日志为 native 工作树 `build-execution-v2-native.log`。最终 JVM `compileKotlin compileJava` 与依赖方向检查成功，日志为 `build/reports/workgraph/execution-v2-final-jvm-build.log`。独立 FFM 检查实际加载新 DLL：747 个 ABI 字段全部匹配，旧 V1 stream 的 111 个字段及六个新增 V2 入口齐全，记录为 `execution-v2-final-abi-check.log`。本批没有执行 processResources、打包、替换客户端或启动游戏。

下一处实现边界是 Composite render pass 创建之前的当帧 normalization recipe；其真实 sampler、Q、coefficients、bounds、target 和 compiled shader 必须从同次调用取得。四 lane opaque/cutout seed、water/cloud、bounds copy/cull、完整 producer adapter、内容 lease 和 tile executor 仍需实现；接线约束集中在 [TRANSMITTANCE tile 执行契约](transmittance-tile-execution.md#完整-v2-事件流与-composite-边界)。用户确认残影消失的客户端与本批未部署产物分别记录。

### Composite 前真实输入与数学认证（2026-11-16）

Composite hook 已移动到原版唯一 `createRenderPass` 调用前，直接接收同次调用的 color/depth/load 参数以及 `nearestSampler`、Q、coefficients、bounds 和原 `RenderTarget`。patched 字节码的 LocalVariableTable 已确认这些局部变量覆盖调用点；第二次 `mainRenderTarget()` 不再重复调用。输入契约保存实际 view/texture、格式、尺寸、mip/layer、sampler、compiled pipeline、defines、版本和认证身份；显示阶段再单独核对替换后的 source view、pipeline、附件和 `(3,1,0,0)` draw。

原版 `minecraft:core/oit_composite` 与 `minecraft:core/screenquad` 的展开源码哈希、OIT coefficient/rank/attachment 关系、depth/blend/raster 状态和 sampled bindings 已加入 shader 编译认证。当前 jar 的认证结果为 coefficient=8、targetCount=2；generation、源码哈希和 contract identity 随实际 compiled pipeline 保存。认证缺失时保持 typed unavailable，不影响原版一次 Composite。

JVM `compileKotlin compileJava` 已通过，日志为 `build/reports/workgraph/oit-composite-inputs-jvm-build.log`；编译包含 V2 缓冲复用和 Composite 输入接线，唯一已有警告来自 `D3D12RasterMotionCapture.kt` 的旧 cast。输入接线报告见 `build/reports/workgraph/oit-composite-inputs-report.md`，数学认证报告见 `build/reports/workgraph/oit-normalization-certification-report.md`。当前 input getter 尚未接入 V2/native 的 normalization resource lease；完整内容 lease、四 lane executor 和 native adapter 仍保持明确 unavailable。

本轮又接入了真实的 sampled texture 内容登记：Composite 采集在原 command recording 上调用 native `CaptureSampledTextureContentV1`，由 native 为每个可冻结的 SRV 保留 recording lease，并返回 owner identity、单调 owner generation、content version 和实际 mip/layer 范围。FFM 输出缓冲按各字段的准确 `MemoryLayout` 大小分配。归一化 payload 已有版本化布局和 ABI metadata，但暂不发 `NORMALIZATION` event；D32 深度的 sampled-content 入口明确拒绝，normalized/corrected 输出也还没有真实 owner/version，因此当前仍返回 typed unavailable，不把认证或借用句柄当成可执行内容。

### CPU 侧现有证据与当前优化（2026-11-15）

现有 JFR `build/reports/workgraph/runtime-sdk/display-prefix-runtime.jfr` 只覆盖旧版本、41 秒和混合退出窗口，不能作为当前帧率或 GPU 时间基线。旧记录的模组调用包含地形批次准备、meshlet 表构建、motion 行扩容和 FFM descriptor/layout 操作；全体 Java 执行样本中，`HashMap.getNode` 和原版 `extractSectionDrawGroups` 分别占 11.24% 和 5.96%，这些比例也只适用于该旧窗口。旧版本的 `D3D12MeshDrawCapture` 容器分配数字已经因单遍批次快照改动而失效，不再按旧估重追改。

当前生产代码已把 `FrameWireLayout` 的稳定偏移集中缓存；本轮又将 OIT source、primitive 和 previous-triangle metadata 的字段偏移缓存到 `OitRecordFrames`，避免每条透明 metadata 行重复执行 `MemoryLayout.groupElement`/`byteOffset`。这只降低 JVM/FFM 编码开销，不改变 ABI 字段、记录布局或 GPU 工作量；`compileKotlin` 已通过，日志为 `build/reports/workgraph/abi20-layout-cache-compile.log`。剩余 CPU 热区按实际版本重新测量前，不把 JFR 的旧分配估重直接转成性能收益。

本轮 terrain producer 改为 JVM 内部 `Facts` schema 2：每个 section 保存原 draw fact、atlas descriptor base 和不可变 geometry 引用，不逐三角展开、不逐 meshlet 创建事实对象，也不在 CPU 投影 AABB。非 capture 的 terrain prepare 不再创建 `TerrainPreparedFacts`、复制 binding 数组或创建 descriptor-offset 数组；真实 capture 接收本次 prepare 独占的数组，后续保留方仍按原契约复制。FFM 同步读取这些数组，GPU slice 与 native 资源租约保持原生命周期。本轮没有测量 FPS 或 CPU/GPU 帧时间。

### 当前交付与剩余范围（2026-11-15）

| 工作包 | 实施代理 | 交付边界 | 依赖与验收 |
| --- | --- | --- | --- |
| H 声明式资源调度 | 6.1sol `scheduler-next` / `raster_exact_motion` / `raster_motion_remaining` / `particle_motion_producer`，主代理集成 | world-post、普通 raster 附件与实际输入、terrain child、GUI 与 surface source 已接线；完整帧、一次发布的 PreparedFrame、业务录制会话与 policy 已接入；读取按实际 mip/layer/plane 合并，scheduler 维护紧凑访问前沿并复用普通输入 token | provider 私有资源和 backbuffer/Present 继续归各自 native owner；历史隔离世界运行无声明错误并正常退出，全部资源布局、最新 SDK 场景及性能仍待对应记录 |
| I 重建输入与历史租约 | 6.1sol `reconstruction-next`、presentation/Streamline、`raster_exact_motion`、`raster_exposure`、`particle_motion_producer` 与主代理 | 真实帧身份、history reset、entity/第三人称普通持物/已知 opaque particle 的顶点来源、第一人称独立输入、世界独立 coverage、天体 previous 保存、天空前启动录制、ORDERED_INDIRECT 与地形 WG 的 raw 接线、曝光/掩码/VRS 和 SDK 源租约已接入 | custom/special model、未认证来源、shader-only deformation 与多运动透明仍有缺口；partial/additive/OIT 实际贡献保守无效；ABI11 已完成配套构建，新增 raw 路径的运行确认与半透明残留调查见当前构建节 |
| J GPU 输入与绑定压力 | 6.1sol `gpu-next` / `gpu-input-repair`，主代理构建 | 实际 GPU 描述符/记录/完整 consumer 与 zero-record 验证通过；50+18 无效用例通过；pipeline 所有权修复后 graphics 600 像素最终生产 DLL 重跑通过 | GPU-input 接口信任 native GPU producer 写出的内部地址/数量/stride；并非任意 GPU 数据沙箱；游戏生产使用与性能仍待测 |
| K 集成与游戏验证 | 主代理，6.1sol 独立集成审查 | native Release 与正式 JAR 通过；菜单、缩放世界、三家 SR 与正常退出已有对应实测；VRS 重投影、持物/opaque particle、有序 motion 批次和新普通输入接入已有对应运行记录 | 完整 guides、重载/切换/SDK 矩阵和 PIX/Nsight 比较尚无完整结论 |

H/I/J 代码已交接并由主代理集成；旧 worker 报告中的“等待共享签名/未 build/未实测”仅描述当时的检查点，不覆盖本页列出的更新证据。阶段顺序用于管理依赖，完整目标契约保持主方案定义。

当前工作树包含可执行的地形、provider 掩码和 VRS 工作图、生产 world-post/普通 raster/GUI/Present source 声明调度、terrain prepare/draw 的 native 声明、真实帧输入历史和 SDK 专属源租约。frameprep/provider-private 资源已经接入生产路径，其 COMMON↔UAV 与 COMMON↔SHADING_RATE_SOURCE 转换由 native 私有 ABI 管理，不伪造 vanilla scheduler manifest。剩余完整 guides 与场景/性能验收见下文。
- 任务正文通过本机 Codex CLI 的 6.1sol 执行通道传递；任务、共享契约、各代理状态与日志位于 `build/reports/workgraph/`。该目录是本地构建产物，长期完成状态以本页为准。

### A2 当前接线

- `D3D12TransientMemory` 使用按提交 fence 封存的可增长 upload page；映射 UBO 按 256 字节对齐，纹理 staging 按 512 字节对齐，单次大于页大小的分配不回绕覆盖旧页。meshlet descriptor 在 atlas replacement 与增量 append 中按批次写入单一 staging block，再提交一次连续 copy；旧 atlas 与失败 replacement 均按提交 fence 退休。
- native draw/compute descriptor page 在当前 command context 内按需增长；旧 page 通过 `recordingLeases` 保留到对应 slot fence 完成，SRV 表缓存同时校验 view 序列和 CBV 布局，容量不足在绑定前明确失败。
- 深度格式字节分类、`GetCopyableFootprints` 行距和紧凑 CPU 行到合法 D3D12 footprint 的 scratch copy 已接线；深度/模板部分 plane 拷贝在当前 SPI 无法表达时明确拒绝。
- 集成构建及 compute/graphics descriptor 实际 readback 已通过；匹配 Agility debug layer/GPU validation 是否开启须由对应日志确认，当前不作开启声明。实际场景由主代理验证；原始分工细节见 `build/reports/workgraph/resources-status.md`。
- texture-to-texture 完整 depth/stencil subresource 遍历全部 plane；buffer 拷贝仍拒绝当前接口无法表达的多 plane 数据。紧凑 R8/RG 等非四字节行宽通过合法 scratch footprint 加逐字节 buffer copy 实现，不额外要求行宽为四的倍数。

## H 生产调度范围与全帧导入边界

外层 `LevelRenderer.render` 包装在几何准备前冻结 `VanillaFrameInputs` 并分配单调递增的真实帧身份，依据实际 camera state 和已成功录制的 level projection 上传确认来源。冻结后进入 `WORLD_STARTED`，启动透明来源捕获、native frame、VRS 准备和 raw 附件清理，天空绘制由此进入资源声明。world main-pass lambda 的 `HEAD` 验证原附件并进入 `RECORDING`；时域路径在 OIT Composite 前重建不透明层并进入 `OPAQUE_POST`，晚期世界继续绘制到 SceneDisplay。AfterLevel 完成后才标记真正 `WorldReady`，生成 final guides；完整世界路径在这个位置执行世界输出。最终世界调用成功才提交 raster predecessor；几何准备、天空、main-pass 和后续清理任一异常都会撤销当前相机帧与 predecessor，关闭活动 graphics pass/token 并复位 native/CPU phase 后抛出原异常。

GUI target 的 depth clear、hudless color clear、世界层 texture-to-texture blit 和最终 surface source blit 复用同一 `VanillaResourceUse` 声明入口，按实际 D3D12 状态提交并在操作后校验最终状态。交换链 backbuffer、Present 内部转换和 surface 的 acquire/Present 生命周期仍由 native surface 私有状态拥有：当前 ABI 没有与冻结 frame `resourceVersion` 关联的 backbuffer manifest，JVM 不伪造 backbuffer 声明。

native `src/render/vanilla/d3d12_vanilla_frame.cpp` 的 `executeDeclaredWorldPost` 与 `beginDeclaredPass`/`finishDeclaredPass` 执行资源声明规划。TAA/SR/DLSS 的外层输入声明由已发布的 PreparedFrame 生成，SDK 翻译由 native adapter 负责；SMAA 使用实际 scene 声明。早期重建后允许普通 raster 与 terrain WG 继续进入 `OpaquePost` 的声明调度；`AfterLevel` 完成后由 `beforeDisplayScene` 标记 `WorldReady`，不重复已完成的重建。token 按 native 序号登记，finish 验证实际 final state；scheduler 保持父子及兄弟声明的提交顺序，处理 RAW/WAR/WAW、UAV 顺序、精确 mip/layer/plane 范围、外部 final-state adoption 和 graphics/descriptor/VRS 失效。转换或结束失败必须中止本帧。

terrain 的 prepare/draw 在 native 内创建 child 声明，继承主 world raster 的 attachment 父 token；祖先与子 token 的资源范围不得重叠。prepare 导入 uniform/globals CBV、sections/atlas SRV 和私有 visible/arguments UAV 输出；draw 导入 vertex、globals、atlas、visible、arguments 及实际 mesh 材质 CBV/SRV。mesh pipeline descriptor 与 root signature 使用同一组常规 binding 布局，纹理读取保留真实 mip/layer/plane。JVM `Prepared.draw` 只调用一次 native 绘制，不重建资源列表或逐 draw 查询版本。

wrapped 与 native 私有资源共用 `PassResourceUse`；同一实际 resource/subresource 的兼容只读状态在入口合并，写冲突和有出口状态变换的冲突继续拒绝。UPLOAD 固定为 `GENERIC_READ`。terrain 私有输出创建于 `COMMON`，显式基准初态仍先查询 context tracker；列表结束统一回到 `COMMON`，batch 不保存跨列表的 GPU 状态。冻结版本只在有效的 `Recording` 阶段可读，draw 同时校验 batch 的资源版本、context fence 和录制序号。wrapper、raw resource、program、batch、PSO 与 command signature 均由录制租约保留到相应 fence 完成。

普通 raster 的附件父声明现由 native `mcdr_dx12VanillaBeginRasterPass` 从实际 `activeAttachmentRanges` 创建，与 begin/end 的精确 transition 共用范围；JVM 不再重建全纹理附件 manifest 或逐 pass 查询资源版本。RTV 使用真实 mip、实际输出的所有 array slices 和 plane 0，DSV 使用真实 mip/slices 及 depth/stencil planes，motion 为单 subresource。父声明与普通输入子声明均为 effects 0；真实外部子 pass 保留自己的 effects，foreign graphics 退出恢复实际 VB/IB 视图后撤销 descriptor 快照。

device 在成功设置后保存完整 root packet、实际 SRV 描述、消费中的 VB 槽和 IB，并持有真实 recording lease。`abi_draw` 在 direct/bulk/indirect 消费边界导入；普通 bulk 每次调用只建一份输入作用域，motion bulk 冻结全部有效 overrides 的读取并集，后续逐 draw push 之后恢复该并集。兼容读状态按实际资源/subresource 合并，UPLOAD 始终为 `GENERIC_READ`，READBACK draw 读取和 attachment feedback 在转换前拒绝。当前实际支持的 SRV 为 Texture2D 与 TextureCube，不宣称 array/3D/MS view 已实现。

调度器按实际 resource/subresource 保存最后 writer、最新 reader 与出口状态，避免每 draw 扫描本帧完整 uses 历史。普通输入复用 context 私有 token 和 plan 容量，不向公开 token 集合逐 draw 追加。该有序 context 模型保留 RAW/WAR/WAW、UAV ordering、版本校验、失败帧 poison/reset；源码减少的历史扫描与租约分配不等同于实测 CPU/FPS 收益。

身份是录制租约固定的实际 `ID3D12Resource*` 加本帧冻结 `resourceVersion`。当前 outer-resource 调度覆盖 world-post、普通 raster 附件及实际输入、GUI depth/world blit 和 surface source；provider 私有历史、scratch、SDK 分配仍由所属业务拥有。交换链 backbuffer 和 Present 内部转换继续由 native surface 私有状态拥有。完整帧、一次发布的 PreparedFrame 与 native SDK 翻译已接入；encoder/pass 通过注入的业务录制会话与 policy 接线。设备编译、source 缓存、预热、重载及变体编排已迁入业务 compiler，并由编译前依赖检查约束；完整 guides 的具体缺口见 I 节。

## I 输入语义、VRS 与 SDK 租约

- jitter/history 只在真实世界投影帧推进；重复/生成 Present 不推进模拟历史。world、尺寸、资源/配置版本和相机切换触发 reset。`ModelFeatureRenderer → ModelPart/Cube → BufferBuilder/MeshData → StagedVertexBuffer` 采集 CPU entity model 的实际 baked 顶点，按 entity 生命周期、model、render type 与 part/cube 配对连续真实帧。同帧重复 model/render type 或 cube 时撤销对应模型的已写有效行和本帧历史，不再用 occurrence 猜测来源。空实体帧也推进历史；每个 quad/triangle 任一顶点缺失即整 primitive 无效。额外 MRT 沿用原 shader 的覆盖、discard、depth 与插值，记录 current→previous 像素位移及真实深度；合成只接受与最终深度完全相等的有效 guide。其余像素使用相机重投影；存在光栅 guide 时不再叠加 AABB 平移近似。
- 第三人称普通持物以实际 entity 生命周期、arm、attachment、layer、display context、foil、item components 和有序 quad 对象/局部拓扑为来源，跨 submit 保留至 `ItemFeatureRenderer.prepareSubmit`。opaque quad particles 以实际粒子生命周期和已知 extractor 的 emission role 为来源，跨 packed layer 保留到原四角发射；历史包含实际 billboard、roll 和 scale。二者共用 scope 和 staged-buffer 关联，并分别匹配原版 item/particle 顶点主函数。第一人称已有独立 HUD 投影、depth、coverage 和 motion，并由显示工作图与世界 guides 合并；来源或 previous 不完整时跳过该帧 FG。special model、透明/OIT、未知 extractor、修改 model-view 的 layering 和 shader-only deformation 仍缺完整精确覆盖，GUI 继续使用独立显示层。停用速度输入时粒子不建立逐粒子来源/拓扑/Lifetime，opaque packed offset 仍计数，避免启停期间错位。
- 设备深度为 reversed D3D [0,1]；SDK jitter 为 `(jx,-jy)`，motion 为去 jitter 的 current→previous 像素位移，scale=1。所有 provider 接收实际 `1×1 R32_FLOAT` 曝光纹理；当前 vanilla 是编码 LDR，preExposure=1、exposure=1，不把曝光纹理解释为 HDR。明确的线性 float HDR 输入才执行两阶段 log-luminance 归约。XeSS 的编码 LDR 输入在 adapter 内转换为私有线性 RGBA16_FLOAT，输出转换回调用方编码，HDR flag 仍由原输入语义决定；preExposure 倒数只通过 multiplier 应用一次。FSR 分别接收 reactive 和 transparency/composition，XeSS 接收 responsive；这些 OIT 派生掩码不覆盖全部材质变化。掩码 output flags 为 reactive=1、composition=2、responsive=4，与 SR capability flags 不同。
- depth copy、velocity 的真实输入/输出、上传 buffer、OIT source 和 VRS 输入使用冻结的 `resourceVersion` 进入同一资源声明入口；terrain 私有 visible/arguments 输出通过 native raw 声明导入，SDK scratch 保留不透明资源边界。VRS 在 `BeginFrame` 成功后准备本帧，在 world 完成且 GUI/清深度前复制实际深度供下一真实帧读取。capture 必须来自同 world epoch、资源版本和 history generation 的紧邻上一真实帧，实际附件、尺寸和格式一致。双向 jittered clip 映射包含 camera-relative 原点差；native 对每个当前 tile 的 3×3 保护采样做最多四次历史深度反查，越界、断层和不收敛保持 1×1。候选历史与最终 SRI 各有独立 ping-pong，避免最终细档反馈导致永久无法变粗。motion 缺失时明确使用 depth-only 输入；该屏幕空间估计不能证明全量新表面/独立运动可见性，详见 [mesh/VRS](../mesh&vrs/README.md#33-屏幕空间着色率图tier-2)。
- native UI registry 和每次 recording 持有 wrapper/pool pin 与 COM 引用；FG 接收晚期 SceneDisplay、canonical depth/MV 与 UiDisplay 的精确资源租约，不复制整个 `recordingLeases`。应用 direct fence、CPU submit handshake、SDK 接受与 Present 返回分别处理。
- XeSSFG 的所有应用输入（含 HUDLess/UI）使用 `ONLY_NOW`，由 SDK 在提供的列表录制拷贝，源租约保留至该列表完成。FSRFG 固定 `allowAsyncWorkloads=false`；本地 SDK 源码表明 HUDLess 插值读取和 UI 内部 double-buffer 拷贝在 gameQueue 上，应用源租约以 Present 之后 signal 的 gameQueue fence 退休。此 fence 只覆盖已证明的源读取，不证明 SDK composition/presentation 内部队列结束；disable、SDK drain 和 context Destroy 分别检查，失败保留 owner 供重试。
- Streamline SR 使用现役候选 viewport2..4 的 local tags；GUI 完成后的晚期 FG 提交才在 viewport1 发布最终 depth/motion/HUDLess/UI 的 `eOnlyValidNow` 输入。显式尺寸、实际状态与资源租约配套，output 替换、world/no-world、resize、surface/device close 使用 checked invalidation。SDK 接受后还需最终 command submit 与实际 Present 成功才提交历史；异步 Present 通过不可变 ticket 配对当前帧、provider generation、context 与 fence。
- 设备销毁通过 checked shutdown 统一清标签、释放 feature/parameters、NGX shutdown 与设备 owner 的 `slShutdown`；成功后才清纹理/COM、adapter API 缓存及 `DeviceContext`。每一步失败保留剩余 owner 供重试，旧 void wrapper 不作为销毁成功证明。world/no-world invalidation 在转换点先等待上一 CPU Present 完整收尾，避免并发修改 Streamline 当前帧标签。

## J GPU-input 信任边界与 pipeline 修复

生产 `dispatchGpuBuffers` 及 handle ABI 校验 schema、device/context、input buffer/offset/span、alignment、binding 范围、UAV 能力及 input/output alias，并将资源固定到录制完成；ABI 不接受任意 GPU virtual address。GPU 写入的 NODE_GPU_INPUT 内部记录地址、count 和 stride 属于可信 native GPU producer 契约。CPU span 校验无法读取或隔离未回读的 GPU 描述符，50+18 无效用例验证的是 host-visible 边界，不是恶意 GPU descriptor 的安全性。

GPU-input 的约束是生产 native producer 与 consumer 必须使用同一运行时和 schema；state-object 失败诊断包含实际 HRESULT/SM 和可用 InfoQueue 信息。该接口只在明确的生产调用点启用，不以探针通过作为游戏路径切换证明。

graphics 提前释放缺陷的根因是录制中的 `currentPipeline` 及驱动所需 root/PSO 生命周期先于消费终止。修复使用 pipeline 的引用计数和每个 recording 的去重 lease；资源释放遵循 slot fence 完成和 command context 销毁顺序。实际 600 像素压力探针验证了修复，不据此推断 FPS 或 descriptor 分配性能。

## 精确剩余交付与下一依赖

1. **提交/退出（A/K）**：Present 收尾悬空访问窗口、void DLSS shutdown 失败未传播及 velocity 管线使用错误销毁入口均已修复；三家 SR 的真实世界与正常退出已有本页证据。保留 SDK tag/disable/shutdown 失败与设备移除的真实错误传播、owner 保留和重试语义；必要的专门生命周期验证使用独立程序，不在业务中添加故障开关。应用队列 fence 不代表 display-controller 或 SDK 内部呈现队列结束。
2. **帧与业务所有权（B/C/H）**：普通 draw 的 CBV/SRV/VB/IB/indirect 已在实际消费边界导入，并合并兼容读取、拒绝 attachment feedback。世界冻结帧与显示输入使用 schema2，世界输出使用 schema3；显示分为 GUI 前 Prepare、GUI 后 Submit。时域路径在 OIT Composite 前完成 opaque 重建，SceneDisplay 承接透明、晚期世界、手部与效果。设备编译/source 缓存、预热、重载及变体编排已移入业务 compiler；设备、command、编译产物/转换缓存与 FFM 核心的依赖由编译前检查约束。当前 ABI 见 [FFM ABI](../misc/ffm-abi.md)，配套构建与各版本运行范围见本页当前构建节。
3. **完整 guides（F/I）**：entity/第三人称普通持物/已知 opaque particle 已有实际顶点来源和原 shader MRT。重复 model/cube 被明确判无效；跨帧交替出现的不同语义角色仍需要生产者 role。世界与第一人称分别提供独立 coverage，显示工作图负责世界/手部 guide 合并；无法确认 previous、运动或 post 映射时跳过该帧 FG。custom callback 需要已知 owner/拓扑角色或显式 opt-in；shader-only deformation 需要 previous 变形输入与版本；透明层需要多贡献片元与真实 OIT resolve 的 guide 规则。运动画质、线性 HDR、完整透明覆盖与当前版本三家 FG 实际运行仍需各自证据。
4. **SDK/跨帧（F/I/K）**：三家 SR 基础世界/退出和 XeSS resize 已实际运行；后续修改按真实依赖选择 no-world、换世界、output/UI 替换或 resize 场景，核对异步 Present 与主线程 invalidation 的串行关系。SDK 内部队列 drain 和 context Destroy 的实际行为不能由应用侧脚本推断；不把重复执行完整矩阵或业务内故障注入作为交付条件。
5. **预热与性能（G/K）**：源/manifest 缓存、normal/flipY 与 terrain PSO/WG、设备支持的 frameprep WG、曝光管线和 DisplayFrameGuides 已接入实际重载；继续覆盖资源包内容变更、重叠重载与配置切换。按固定场景、相机路径、配置与资源版本采集 CPU p95/p99、阶段 GPU 时间、上传字节/容量及 PIX/Nsight 证据。当前没有性能收益结论。

### 云/OIT 输入调查（2026-11-15）

26.3.0.95 的普通/云 bounds 分离，最终 composite 读取原 bounds。云私有 DSV 在 DEPTH_BOUNDS 使用 reversed-Z `GREATER_EQUAL` 并写深度，ACCUMULATE 只读该 DSV。`OitReconstructionInputs` 在真实 ACCUMULATE 存活片元记录候选，保留原 shader 的输出、fog、discard 与遮挡；云候选为 RGBA32_FLOAT 的 depth/motion/valid，普通贡献为 R8_UNORM + MAX，覆盖非零 RGB、alpha 与非有限输出。

`CloudRendererTemporalMixin` 采集真实 prepare 的 ticks/partialTick、纹理宽度/generation 和 renderer 身份，按纹理宽度对应的 400-tick 周期计算连续滚动量。previous 须来自紧邻且已呈现的真实帧，并匹配 world/resource/history。源码认证失败、来源变化或非有限候选均拒用；成功呈现才提交云历史，中止与身份失效清除它。

`OitProducerCapture` 采集 `executeOit` 的八个真实调用来源、stage、实际颜色附件/DSV、Coeff/DepthBounds view 与 sampler；provider 返回时冻结原始绑定，draw 时核对当前绑定。云候选同时要求原有 shader 认证和同一实际 CLOUD renderer，第三方复用云 shader 仍记为未知。采集复用既有九种 frontend draw 包装，明确空几何不记贡献，原调用成功后才消费记录。custom 返回 true 的接管标记 incomplete，false 且无 draw 不污染输入；pass、scope、帧取消与成功呈现沿原 OIT 生命周期退休。普通 draw 在进入记录前直接查表返回，不为 OIT 新建闭包；实际 OIT 才创建本次记录。

`ReconstructionFrameInputs` 拥有独立 D32_FLOAT/RG16_FLOAT pair，resolve 同时选择同源 depth/motion；world-output 和 provider masks 消费同一 pair。resolve 的 DSV/RTV 入口分别声明 DEPTH_WRITE/RENDER_TARGET，`EndRenderPass` 后均为 COMMON，出口声明须与之相同。选择云还要求最终 raw owner 为 OIT、候选有效且该像素没有普通 OIT 贡献。混合、未知和无效来源使用原 capturedDepth/baseVelocity；实际 OIT 的 coverage 继续为 unavailable。只有选择 pipeline 而没有实际 draw 时不算贡献，没有 OIT draw 时保留基础 coverage。SR/TAA 关闭时不创建附件，也不录制本帧 clear/resolve。

固定 resolve PSO 由 `VanillaPipelineCompiler` 的现有 `PipelineReload` 准备和持有。在 native frameprep/display 预热前创建候选，原版缓存安装成功后发布；重复准备复用候选，撤销时关闭候选，替换或 compiler 关闭时释放已发布程序。OIT 只借用本帧冻结的程序，`recordResolve` 不编译 PSO；缺少预热或本帧中途换代直接报错。实际录制使用的 PSO 由 native recording lease 保留到对应 slot fence 完成。

普通、地形 WG 与宽线变体均包含匹配的附加 RTV。ABI14 的 `mcdr_dx12SetAdditionalColorTarget` 由 device 绑定附件，C ABI adapter 将其交给 vanilla scheduler 执行 prepare/commit，再合入原 attachment token，结束时统一校验 COMMON。候选附件由 OIT owner 单独登记至 encoder，resolve pair 由重建输入 owner 登记；context 完成并销毁后分别关闭。

契约见 [主线计划 §5.5](vanilla-work-graph-plan.md#55-云与-oit-的贡献输入)。native Release、完整 Kotlin/Java/JAR 和真实 shader 转换/DXC SM6.9 编译通过；配套客户端已进入世界并持续呈现。当前交付为真实云候选、普通贡献拒绝与独立 SR pair；多运动透明、普通贡献与云重叠、多云层的完整混合历史仍有缺口，FG 继续保留 unavailable，动态画质待用户确认。源码依据见 `build/reports/workgraph/cloud-oit-guides-audit.md`。

### G 重载与预热边界

`VanillaFrameCoordinator.prepareResourceReload()` 在帧间准备下一 `resourceVersion`，并在 prepare 到 commit 之间阻止开始新 display frame。`FramePrepPrograms.Prepared` 同时持有这个版本候选、OIT resolve 和 native frameprep/display 候选；成功提交才发布版本，失败与撤销关闭全部候选。主附件对象不变的资源包重载也使下一真实帧拒绝旧 previous；已冻结 identity 保持原值。

通用 WG 的实例租约按 command recording 登记一次，同录制的后续 dispatch 不重复分配 `RecordingLease`。实例初始化使用 `Required → Recorded → Complete`；租约同时持有真实 context fence，在归还时确认首次初始化是否实际执行。未提交列表被 End/Begin 重置或 context 销毁时回到 `Required`；已经完成初始化的实例遇到后续丢弃保持 `Complete`。`beginCommandListWithWait` 至少等待当前槽的真实提交 fence，调用方传 0 也不能提前重置 allocator。独立 `src/probes/workgraph_reuse/` 已使用本轮 ABI14 DLL 在实际 GPU 运行通过：覆盖丢弃初始化、同 fence 序号复用、每录制 64 次 dispatch、两个未提交 context、program 提交前释放及传 0 重用已提交槽，并回读 128 个 uint 核对结果。记录为 `build/reports/workgraph/reuse/20261115-031647/`。

CPU-record `dispatchBuffers` 现在在任何资源状态变更前校验 record stride/alignment、程序与缓冲所属 device、范围和 root 地址；同一 `ID3D12Resource` 的 CBV/SRV 绑定合并整资源的读状态，UAV 与读绑定别名直接拒绝。资源保活统一复用 recording object lease，不再为一次 dispatch 额外创建 COM vector lease；`recordCount == 0` 保持无副作用返回。GPU-input 入口继续沿用自己的 NODE_GPU_INPUT span 与 alias 校验。

`ShaderManager.reload` 在新 `Configs` 产生后建立唯一事务，required/optional 两批共用同一版本；注册过的动态管线与已用设备变体进入库存。normal/flipY、宽线和地形 WG 变体在临时映射完成，必需项失败保留原缓存；optional 失败遵循原版加载语义。旧 frontend wrapper 与设备缓存分别持有租约，撤销事务只关闭其所属产物；在途 GPU draw 沿用录制租约。首次必需管线失败仍报告致命加载错误，已有已发布缓存时交给原版重载错误处理。

在原版关闭旧 post-chain cache 前，`FramePrepPrograms` 读取含 options 覆盖的实际 SR mask 配置，并结合 VRS 开关、Tier 2 和有效 tileSize 调用 ABI15 的 `mcdr_dx12PrepareFramePrograms(context, generation, flags, candidateOut)`。flags=1 声明需要 provider mask，flags=2 声明需要 VRS；请求超出设备能力时拒绝。每个新 generation 准备曝光 meter/reduce PSO、设备支持的 mask/VRS 图和 DisplayFrameGuides，FG 关闭或 flags=0 也完成这些预热。prepare 保留两个 active owner 与旧 display pending，generation 必须高于两域当前值。

`VanillaPipelineCompiler` 提前构造 normal/flipY/source maps、bridge registrations 和旧资源清单。Mixin 保留旧 post-chain 与 pipeline cache，安装新 frontend 后调用 native commit；安装或 commit 失败时直接恢复两个旧字段，再关闭候选。native 验证两域 owner、context 身份、录制版本和 fence 后连续执行两个 `noexcept` 交换；JVM 随后仅赋已准备好的引用、版本与所有权。旧 source 的延迟 worker 登记被拒绝。成功发布后的旧资源关闭错误单独记录，新代继续生效。

prepare 到 commit 之间重开 command list、录制业务状态变化或其他候选发布会撤销旧候选。已提交且等待 Present 回执的 FG 帧继续持有独立资源租约，不阻止重载；active frame/renderpass、异步 submit 仍持有 context、未知提交和 display pending 通过分类错误拒绝。实际 mask/VRS/exposure/display 只消费预热产物；旧 WG program/instance、曝光 PSO 和 display slot 由 recording lease 保留到对应 fence 完成。旧两个 prewarm 导出保留独立消费者的立即发布、同代幂等契约，shader reload 使用共同候选入口。

一次真实 F3+T 暴露 `readShaderInclude` 使用 LWJGL `contentString()` 读取原版 `memUTF8(text, false)` 缓冲：该方法扫描零结尾，越过 `content_length`，导致额外 `#endif` 或乱码。已核对本项目使用的 LWJGL 3.4.3 字节码：`content()` 同样调用 `memByteBufferNT1`，并不按 `content_length` 创建视图，因此改成 `memUTF8(result.content())` 仍会越界。实现现直接读取 `CONTENT` 字段的指针，并把 `content_length` 传给有长度参数的 `memUTF8`，同时保持使用当前事务的 include 源。

2026-11-13 的针对性验证已完成：Kotlin/Java 编译与正式 `jar` 构建通过，检查发布容器及嵌套 `illusion-core.jar` 均未包含自检或独立探针类。同一 D3D12 客户端在 15:18:05 收到一次真实 F3+T，重载期间没有 shader 编译失败或错误日志，重载后菜单全景、文字、按钮和 RTSS 正常显示，随后正常销毁 surface/device，Gradle 返回 `BUILD SUCCESSFUL`。此次未重编 native、未追加或重跑独立探针；`options.txt` 与运行前备份的 SHA256 一致。证据为 `build/reports/workgraph/include-bounded-jvm-build.log`、`include-bounded-client.log` 与 `include-bounded-after-reload.png`。这项结果覆盖原有资源的一次重载，不代表已验证资源包内容变更或重叠重载。

启动链已删除 JVM 行为自检、native 资源分配/映射自检和虚构 mesh shader 冒烟编译。运行时保留设备/SM/WG tier、DXC 接口与真实管线创建错误检查；SM6.9 是否可编译由实际生产 shader 编译结果决定，不以假 shader 证明。独立验证边界见 `src/probes/README.md`。

### 光栅 guides 集成与真实运行修正（2026-11-13）

新增 motion MRT 的 PSO 创建曾返回 `E_INVALIDARG`：PS 独有的 `SV_IsFrontFace` 改变了新增 varying 的签名位置。适配器现在按原 VS 输出顺序构造规范化 PS 输入，motion 字段跟随原输出、PS 独有系统参数置后，原着色器只执行一次。实际客户端已完成全部必需管线加载并显示菜单。

真实缩放世界依次暴露并修正三处契约错误：GUI/surface blit 的输入按实际采样状态 `SHADER_RESOURCE` 声明；世界帧尺寸从实际颜色附件读取，避免把逻辑窗口尺寸用于 motion 附件；UPLOAD/READBACK buffer 维持 D3D12 固定的 `GENERIC_READ`/`COPY_DEST` 状态。`finishDeclaredPass` 只接受返回值 `1`，native 拒绝时将具体 pass、资源及实际/预期状态传给唯一 ABI 错误出口。调度器的资源状态限制保持有效。

配套 ABI6 DLL 与 JVM 已进入真实世界并持续出图，覆盖 `503×283`、`1510×787` 世界附件和 `2560×1334` 输出，F3 确认 mesh 地形与 D3D12 路径。该会话配置曾切换，不将整体出图解释为新 XeSS 色彩转换、所有 SDK 输入或完整实体 motion 的验收；VRS 重复拒绝上一帧 capture 的行为单独处理。证据为 `build/reports/workgraph/raster-guides-world-fixed-heaps.log` 与 `raster-guides-fixed-heaps-world.png`。

该会话退出在 `flushPendingDeletes` 崩溃：日志中的 velocity graphics pipeline 句柄与崩溃对象一致。`D3D12Velocity` 的重试与 owner 关闭把 `Dx12Pipeline*` 交给接收 `Dx12Object*` 的资源销毁入口，导致 root signature 被当作 buffer resource 调用。两处现改用已有 `D3D12DestroyPipeline`，沿用 native 的管线录制租约；不为隐藏类型错误增加 native 注册表或忽略非法句柄的分支。

随后用独立游戏目录和世界副本启动真实 XeSS 配置，确认 `libxess 2.0.2` 创建 context，编码 LDR 输入经私有线性适配出图；从 `427×240 → 854×480` 缩放到 `629×372 → 1258×744` 后仍有世界和 UI。该会话实际创建 velocity graphics pipeline，保存退出时完成 encoder/async renderer 释放、DLSS shutdown 和 `destroyDevice done`，Gradle 返回 `BUILD SUCCESSFUL`。这项退出修复仅重编 JVM；用户的原 options/config hash 与隔离运行前一致。证据为 `build/reports/workgraph/raster-guides-xess-runtime.log` 和 `raster-guides-xess-world.png`。这证明基础执行、缩放和退出，不证明运动画质或性能收益。

验收专用的 `MCDR_DXC_DISABLE` native 分支和 Gradle 注入已删除，真实 DXC 路径配置、发现和错误传播保留。另删除 Streamline 启动时提交虚构 `1920×1080` Quality 参数的 `slDLSSSetOptions` 行为探测及 `sl-probe.txt` 旁路写入；初始化、设备挂接和特性能力查询保留，DLSS 参数只在实际重建业务中设置。本轮未增加业务内自检或独立探针。

同一隔离目录随后分别运行 FSR 和 Streamline DLSS。FSR 在 `427×240 → 854×480` 创建真实 provider、绘制世界与 UI，并正常保存退出；Streamline 在删除启动行为探测后的新 DLL 上，首次在真实世界阶段设置 `854×480` DLSS 参数，`503×283 → 854×480` 出图，随后完成 surface/device 关闭。两次 Gradle 均返回 `BUILD SUCCESSFUL`。证据为 `build/reports/workgraph/raster-guides-fsr-runtime.log`、`raster-guides-fsr-world.png`、`raster-guides-dlss-runtime.log`、`raster-guides-dlss-world-settled.png`。这些会话使用当前编码 LDR/unity exposure，不覆盖线性 HDR 测光或完整实体 motion 画质。

最终 native Release/x64 构建与 Kotlin/Java/正式 `jar` 构建通过。检查发布容器及嵌套 `illusion-core.jar`，没有独立 probe/test 或 SelfTest 类；嵌入 `MCDR.dll` 与当前 Release 一致，SHA256 为 `AE231A355DF45D1B96BC8863C1B7DDFA28B747FA6D26C456BA872B5820A0CCD1`。构建记录为 `build/reports/workgraph/test-boundary-native-final.log` 和 `raster-guides-production-final-build.log`。三家 SR 场景使用世界副本，用户原 options/config 的 SHA256 均与隔离运行前一致。没有追加、重跑独立探针或进行完整测试矩阵。

### VRS 历史重投影与收集器分配（2026-11-13）

ABI7 为 VRS 追加独立候选输出与两份列主序 clip 映射；FFM 使用既有线程 scratch 同步传输 128 字节矩阵，native 复制到 164 字节工作图输入记录后不保留宿主指针。`VrsHistoryProjection` 使用 double 矩阵建立包含相机原点差、实际投影与 jitter 的映射，再转换为 GPU float。精确坐标相等的限制由真实重投影替代，紧邻真实帧、世界/资源/历史代际和附件兼容条件继续生效。

候选与最终图各两张 `R8_UINT`；native 为缺失的可选 SRV 建立对应类型的 null descriptor，五个真实根绑定和录制租约覆盖有效输入及两个输出。当前候选完整写入，历史候选经相同映射读取；开启时间钳制且无候选历史时先输出细档，下一个兼容候选允许晋升，消除最终 `min(rate, previousFinal)` 使细档永久保留的问题。该算法依旧是有限历史采样，不能证明当前全部可见性或完整独立物体运动。

CPU model 收集器改为交换两张历史 map 并清理可复用的一张，移除每个有效顶点的 `Pair`/boxed offset 分配；单 piece 已规范化的 sidecar 直接发布为只读历史，多 piece 才合并。没有新增验收状态、测试分支或运行计数器；这减少了源码可确认的分配/复制，不构成 CPU 时间收益测量。

DLSS 正常输出路径中的 experimental/evidence-only 与旧黑屏叙述已改成实际 external/private 输出选择、成功后复制和 checked release。另删除早已停用的输出采样常量、九项采样/熔断字段、调度重置、空 `reportOutputSample` 及死分支；真实评价失败计数、历史提交、输出租约与 GUI 完成条件保留，不增加替代读回判据。这部分仅涉及 JVM 与文档，独立编译打包，不重复运行 native 检查。

native Release/x64 和 Kotlin/Java/正式 JAR 构建通过，记录为 `vrs-reprojection-native-build.log` 与 `vrs-reprojection-production-build.log`。使用独立 `runtime-sdk` 游戏目录、原世界副本和真实 Streamline DLSS 配置，`503×283 → 854×480` 出图；VRS 创建并持续生成 `32×18` 图像，F3 显示 ABI7、mesh terrain 与递增 SRI 帧数。该会话不再反复报告旧屏幕坐标不相等，正常保存并完成 surface/encoder/device 释放，Gradle 返回 `BUILD SUCCESSFUL`。证据为 `vrs-reprojection-runtime.log`、`vrs-reprojection-world.png` 和 `vrs-reprojection-world-f3.png`。运行中的正常 jitter 提供跨坐标实际输入；尝试发送移动后的截图受到其他窗口遮挡，不作为移动相机验收。没有粗档 tile 比例、完整消隐/运动画质或性能收益结论，也没有新增或重跑独立探针。

DLSS 生产清理后的最终 Kotlin/Java/JAR 构建同样通过，记录为 `dlss-production-cleanup-build.log`；这次没有重编 native 或再次运行客户端。正式容器与嵌套 JAR 中 test/probe/SelfTest 类为零，嵌入的 ABI7 `MCDR.dll` 与 Release 一致，SHA256 为 `C5BB1F0DDE8427BEFE943EB68D2A5E87CA8DF73D04E2F03B3271276DDF09C822`。最终产物为 `build/libs/dxr_mc-2630-0.6.0-alpha.jar`，90,079,320 字节。两仓 `git diff --check` 通过；用户原 `run/options.txt` 与配置 hash 保持本轮运行前值，实际客户端已退出。

### 持物、粒子与有序 motion 批次集成（2026-11-13）

第三人称普通持物与已知 opaque quad particles 已通过实际提交/四角发射进入共用 previous-position sidecar；捕获中断、重复模型/part/cube 和不兼容历史会使对应行失效。粒子在停用速度输入时不分配逐粒子来源与拓扑。四种 motion `multiDraw` 使用一次有序 FFM 批次，逐 draw 保留参数/历史绑定，共享 PSO 与普通绑定准备；GPU draw 数和逐 draw native 描述符工作没有因此减少。DLSS 后锐化与回拷已分别在实际 context 内声明真实资源和最终状态。

native Release/x64 与 Kotlin/Java/正式 JAR 构建通过，JVM 构建记录为 `build/reports/workgraph/producer-motion-batch-jvm-build.log`。两次真实隔离世界运行记录为 `producer-motion-batch-runtime.log`、`producer-motion-scene-runtime.log`，均正常保存退出，未发现实际 mixin、scheduler 或运行时异常。后一场景为旋转的盔甲架双手持物、持续 opaque 暴击粒子、DLSS 开启；用户动态观察反馈为“暂未看到明显异常”。此反馈仅覆盖该场景，不代表完整 guide 覆盖、其他内容画质或性能结论；动态视觉确认由用户完成。

本检查点正式 JAR 为 90,140,735 字节，外层与嵌套 `illusion-core.jar` 均没有 test/probe/SelfTest/FakeBackend 类。嵌入 ABI7 `MCDR.dll` 与 Release 一致，SHA256 为 `6644A7D1077A77074F4937FA5D404F33EF5EFCE2F9CDBCE11D11227F8AFE71B1`；本次增加的 bound-multiDraw 导出是当前 JVM 必需符号，不能混用旧 ABI7 DLL。两仓 diff 检查通过，用户原 options/config hash 保持运行前值，任务客户端已退出。未增加生产测试钩子、行为自检或独立探针。

### 普通输入设计与 motion 缓存修正（2026-11-13）

6.1sol 完成 JVM/native 两侧有界审查，普通输入导入确定采用主线计划中的 native 实际绑定快照。设计识别出完整 root/IA 快照、真实 view 元信息、普通输入消费声明与 compact scheduler 四项共同依赖；其后生产实现已接入，具体所有权与当前边界见本页 H 节。以下构建记录仅对应先行的 JVM motion packet 修正。

审查发现并修复实际 JVM 缺陷：来源 uniform/texture 被清除时，普通 packet 更新而缓存的 motion packet 仍保留旧绑定；motion 准备仅重填仍存在的来源，无法删除旧项。现在所有来源修改通过同一 inline helper 同步两类缓存，新建 packet 从当前来源初始化，motion 参数/历史继续显式覆盖。由此删除每次 motion 准备时重复遍历全部来源 map 的工作。修复保留原批次和缺失绑定错误语义，未修改 native。

针对本次 JVM 修复的 Kotlin/Java 与正式 JAR 构建通过，最终记录为 `build/reports/workgraph/motion-packet-invalidation-jvm-final-build.log`；Kotlin 编译实际执行记录在 `motion-packet-invalidation-jvm-central-build.log`。NeoForge Maven 连接超时后，通过忽略目录下的临时 init script 从 Maven Central 解析 Kotlin 工具依赖；打包使用现有完整 `mcdrDependencyRoot`，补齐九项 SDK 资源。没有修改仓库依赖源配置、安装 JDK、重编 native 或重启客户端。

缓存修正后的 JAR 为 90,141,227 字节；外层与嵌套 core 的 test/probe/SelfTest/FakeBackend 类仍为零，所需 Agility/DXC/XeSS DLL 均存在，嵌入 native SHA256 保持 `6644A7D1077A77074F4937FA5D404F33EF5EFCE2F9CDBCE11D11227F8AFE71B1`。用户原 options/config hash 保持一致。用户的动态观察反馈对应前一项持物/粒子集成场景，不作为缓存修正后再次运行的证据。

### 普通光栅输入与紧凑调度集成（2026-11-13）

完整实际绑定快照、真实 SRV 描述、精确附件范围与 direct/bulk/indirect 消费声明已由 6.1sol 分工、主代理集成。bounded 源码交叉复核发现 foreign graphics 退出曾清除 IA 跟踪，而 JVM 无法恢复完整 IA；现在 native 成功设置 VB/IB 时保存实际视图及租约，外部操作后重绑硬件视图再撤销 root packet。普通附件父 token 改为 effects 0，避免每次普通 pass 结束重置仍有效的 PSO、descriptor、IA 和 VRS；真正外部 child 保留自己声明的副作用。

独立 `src/probes/vanilla_scheduler/main.cpp` 直接编译生产 scheduler header，检查 100,000 次重复读取不累计历史依赖、分离 subresource、最后 writer/最新 reader、UAV/external state、版本和 stale plan、poison/reset 以及复用 plan 的成功→失败→恢复，结果通过。该探针不进入生产源码或 JAR；只验证有序调度模型，不证明 GPU、SDK 或游戏画质。日志为 `build/reports/workgraph/raster-scheduler-probe.log`。

最终 native Release/x64 构建通过，包含实际 IA 恢复和父 effects 0，日志为 `raster-input-native-final-build.log`；JVM `compileKotlin compileJava jar` 通过，日志为 `raster-input-jvm-build.log` 与 `raster-input-jvm-final-build.log`。新必需 raster-parent 导出保持 ABI7 布局，当前 JVM 必须与含该符号的 DLL 配套。

使用同一独立 `runtime-sdk` 目录启动新 DLL，用户在加载警告后确认“已进入世界”。日志记录 Streamline DLSS `503×283 → 854×480`，随后尺寸变化为 `1510×787 → 2560×1334`；VRS 图从 `32×18` 重建为 `95×50` 并继续生成。世界持续录制、提交和呈现，未记录 graphics-input、declared-pass 或 scheduler 拒绝，随后正常保存世界、销毁 encoder/async renderer/surface/device，Gradle 返回 `BUILD SUCCESSFUL`。证据为 `build/reports/workgraph/raster-input-runtime-final.log`。当前 Release 与实际运行 DLL 的 SHA256 均为 `331E1A466944873EAA669A0AC133898BD1BAB1879C7172C8A1A4C58E12B4915D`。

这次运行证明该真实场景的资源接入、尺寸变化和退出；用户此前“暂未看到明显异常”仍只对应先前的盔甲架/粒子场景，不扩展为本次动态画质或完整覆盖结论。代理没有使用截图/自动按键推断运动质量，没有添加生产验收状态。用户原 `run/options.txt` 与配置 hash 均保持隔离运行前值，任务客户端已退出。读取前沿和租约去重的性能收益仍需实际 CPU/GPU 测量。

### 闲置 JVM bundle 编排清理（2026-11-13）

实际 caller 审计确认 `D3D12AsyncContext` 没有生产调用，bundle pool/recorder、旧 master executor、预过渡和 frame-slot 的 FFM 包装没有消费者；`asyncCommandLists`、`asyncWorkers` 与 `commandBundles` 仅由这套闲置 owner 或配置自身读取。现已删除该 owner、三项无效配置/旧来源/双语翻译及相关 JVM 句柄、包装和必需符号查找。没有新增替代 manager、回退或业务验收状态。

native 公开 ABI7 及正确的 bundle/executor/partition descriptor 实现独立保留兼容；当前原版 JVM 不实例化它们。真实设备 `Init/DestroyAsyncRenderer`、encoder 录制/提交握手、context fence、AsyncFence 包装及计时资源的关闭条件保持各自所有权。清理不会关闭当前异步提交线程，也不证明 native 所有历史模块已删除。

限定清理后的 `compileKotlin compileJava jar` 通过，日志为 `build/reports/workgraph/dormant-bundle-jvm-final-build.log`；本次没有重编 native、重复启动客户端或重跑独立探针。发布容器和嵌套 core 未包含 probe/test/SelfTest/FakeBackend 或已删除的 context/enum 类，Agility/DXC/XeSS DLL 和许可证齐全，嵌入的 native 与上述 Release 相同。前述世界运行覆盖清理前的新输入 DLL；纯 JVM 删除以源码调用审计、编译和产物检查为证据。

### 统一相机帧与 ABI8 接线（2026-11-13）

JVM `VanillaFrameInputs` 已落地：实际相机 double origin、旋转、变换后 pristine/raster 投影、真实 jitter、可用的 lens metadata、世界/时间/历史代际、渲染与显示尺寸和所选配置在世界前冻结。矩阵私有复制；previous 只来自紧邻且相容的成功外层 render，abort 或 Present 收尾异常会清除 predecessor。velocity、raster/item/particle motion、VRS、TAA、SR/DLSS 和 FG 读取同一帧；OIT 掩码捕获按同一帧对象和实际附件尺寸判断有效性，不再自行读取 live camera。

level projection 仅在 staging/copy 成功录制后发布不可变记录，冻结时核对 world、frameTime、generation 和实际附件尺寸。停用 temporal 仍记录真实投影且 jitter 为零；冻结后若实际上传改变则终止该帧。`LevelRenderer.render` 的 Kotlin `WrapMethod` 包装已覆盖几何准备、天空、main-pass 和后续清理，世界输出成功才提交历史；ABI11 将 native 录制提前到冻结后，主 pass 只推进已有帧的阶段，不再分配第二个真实帧身份。

DLSS 的 current/previous VP 共享当前相机原点，位置差先在 double 中计算再转 float。FG 的稳定 double 原点由 native pending/accepted/committed 历史拥有；SDK 接受输入、实际 Present 成功并得到 provider 回执后才推进。JOML 列主序数组直接复制到 SDK 行主序存储，表达 Streamline/XeSS-FG 行向量所需的数学转置；native 的逆矩阵和乘法顺序保留。

当前源码已使用 schema2/ABI8。BeginFrame 同步复制完整696字节帧，WorldReady/GuiReady 使用32字节身份，PreparedFrame 在实际 depth/motion/exposure/mask 命令录制后发布一次。评价只携带身份、选定 consumer、reset 和实际输出；native 校验范围、格式、尺寸、别名及版本后，从已保存的中立值构造 TAA/SR/DLSS/FG 私有参数。七类布局的全部字段参与既有双向 ABI 校验，C 编译检查实际大小。旧独立每帧 SDK 出口与 JVM 镜像已删除。

准备资源和唯一发布状态归现有 `ReconstructionFrameInputs`，绑定同一个不可变帧；未选择的资源不宣称有效，首次没有历史/运动时发布真实 NONE/color。native 重建业务记录上次实际成功消费的身份/历史/consumer/backend，不相容时强制 reset，失败不推进。普通帧收尾只清元数据；中止、无世界、NONE 或提交失败撤销重建与依赖 FG 历史，GPU 资源继续按 fence 保留。

包装型 FG 在新准备前、registry/provider 锁外结算上一实际 Present，再开始本帧 ID 和 tag；late acquire 复用已开始的帧。首次 DLSS callback 从同一 pending receipt 取得冻结的镜头、尺寸、时间和单位。XeSS required API 失败传播到接受状态；FSR reset 按 OR 保留至 Prepare 成功，回执对应当前 frameID。此串行点将上一 Present 的剩余等待提前到重建准备处，吞吐代价尚未测量。

两次实际进世界失败均定位到 JVM。第一次读取了刻意保留窗口逻辑尺寸的 MainTarget 字段，现改为真实颜色附件 mip0 尺寸。第二次投影含非有限值；源码确认 Camera 的两项 FOV 平滑倍率默认0，首个 render 早于 game tick 时产生零 FOV。新增 Kotlin constructor mixin 将倍率初始化为中性值1，实际投影上传和有限值检查继续保留。

`abi8-native-build.log` 和 `abi8-jvm-build.log` 记录 native Release、Kotlin/Java/正式 JAR 构建通过。配套 ABI8 隔离客户端通过 ABI 加载及 Camera mixin 应用，23:09:55 进入世界后在 JVM 抛出 `World frame time moved backwards`。实际 Minecraft 源码将 `frameTimeNs` 赋为一次渲染的耗时；原接线把相邻耗时当时间戳相减，帧变快就得到负数。现由 Kotlin mixin 在 `Minecraft.renderFrame` 开始时记录一次 `Util.getNanos()`，投影上传记录、抖动序列去重、实体提取记录和冻结帧共同使用这个时间，帧间隔只对真实开始时间作差。

普通运行和隔离运行均设置 NeoForge 自带的 `config/neoforge-client.toml` → `showLoadWarnings = false`，关闭需手动继续的加载警告页。警告仍写入日志；本次警告来自 KotlinForForge 的旧 `logoFile` 元数据。FML 早期窗口保持启用，致命加载错误仍走原有流程。

时间修复后的 `compileKotlin compileJava jar` 通过，记录在 `abi8-frame-time-final-build.log`。正式 JAR 为90,172,312字节，SDK 资源齐全，外层与嵌套 core 没有 test/probe/SelfTest/FakeBackend 类；嵌入 native 与配套 Release 一致，SHA256 为 `A8F6EA5A1E0E1C1213F796209A0FF8DEE3B5FB2C8DB094BBDFD3840A3FE667DE`。本次没有改动或重编 native。

`abi8-frame-time-runtime.log` 记录新 Kotlin 帧时间 mixin 成功应用；客户端跳过警告页后于23:25:38自动进入隔离 New World，23:25:39开始成功消费 `503×283 → 854×480` 的 DLSS prepared 输入，VRS 随后取得真实前帧深度与运动，运行到23:31:35累计呈现超过30,000帧，未再触发帧时间异常。加载过程中23:25:37有一次 pending Present 等待超时和可恢复 surface acquire 失败，随后自动恢复。23:31:38世界保存、surface/encoder/async renderer/device 清理完成，Gradle 返回 `BUILD SUCCESSFUL`；动态画质未由代理判定。

### 业务录制会话与 pass 策略（2026-11-14）

`D3D12Backend` 在创建设备时注入 `VanillaCommandRecordingFactory`。每个 FRAME/UPLOAD encoder 独立持有会话，实际投影 copy 成功后才确认回执；普通上传直接沿用通用路径。native Begin/EndRenderPass 与声明父 token 的调用顺序保持一致，失败时通过原错误通道撤销录制。

`VanillaRasterPassPolicy` 拥有地形路径选择、真实 motion 参数和 VRS 作用域，`VanillaPipelineSemantics` 独占弱引用分类缓存；通用 pass 保留所有实际 draw 重载、binding packet 更新、PSO 切换和恢复。motion 批次复用 binding packet 内预先生成的 override 槽，不再每批创建槽数组或按名字扫描。既有计时移入诊断域，FFM 门面不再读取配置；编译前检查约束五个 FFM/command 核心文件的依赖方向。

context 的一次性销毁与销毁后资源清理已分开。登记资源只在成功 close 后移除；关闭失败时保留未完成资源，再次关闭可以继续清理。瞬时上传池同样在成功释放后移除待清理批次，避免提前标记完成。GPU fence 超时或提交失败仍保留所属资源。

`raster-policy-cleanup-build.log` 记录本轮 Kotlin/Java/JAR 构建通过；正式包90,188,404字节，外层与嵌套 core 无测试或探针类，所需 SDK 资源完整，内嵌 native 与前述 Release 一致。此次只修改 JVM 和文档，没有重编 native、增加测试分支或运行截图/模拟按键。

`raster-policy-runtime.log` 记录新客户端于00:14:20跳过警告页并自动进入隔离 New World，00:14:21成功消费 `503×283 → 854×480` 的 DLSS prepared 输入，VRS 随后取得真实前帧深度与运动；会话累计呈现超过17,000帧，并完成 `1510×787 → 2560×1334` 的实际尺寸切换。00:22:02世界保存、encoder/async/device 清理完成，Gradle 返回 `BUILD SUCCESSFUL in 8m 31s`。本次没有动态画质判断。

### 编译业务所有权与设备依赖（2026-11-14）

`VanillaPipelineCompiler` 实现中立 `D3D12PipelineCompiler`，完整拥有 normal/flipY/source/shader 缓存、naga 编译器、版本化预热与重载事务。pending 完成、publish、abort 和 close 共用该 owner 的锁；reload token 校验实际所属 compiler，来源 identity 与 generation 保持分离。`VanillaPipelineVariants` 编排 motion/宽线/地形资格，`TerrainPipelineCompiler` 创建真实地形 PSO/WG 后一次移交；compiled pipeline 只持有已完成产物。

Backend、RenderPass 和实际 Kotlin/Java compile/reload 调用方已迁入同一入口。通用 conversion cache 与撤销异常放在 d3d12.shader，HLSL 输出由诊断域 `ShaderDumps` 实现中立接口。Device 不再读取配置或引用 terrain；Backend 将提交模式转成枚举，录制工厂在 encoder context 成功完成并销毁后释放业务 sampler。编译前依赖检查覆盖设备、command、编译产物/转换缓存与 FFM 核心。

关闭进度按成功的单项推进：compiler 每成功释放一个缓存租约才移除该条目；Bridge 保留最后租约直到产物关闭完成；frontend 只在 release 成功后记为已关闭。管线逐个关闭并移除成功的地形/mesh 变体，并补齐宽线 PSO 的释放；失败项保持所属 owner。实际 ShaderManager.apply 经原版 reloadExecutor 在渲染线程执行，设备退出亦由该线程执行，未新增 compiler/device 的反向锁。

`pipeline-variants-build.log` 记录变体迁移构建，`compiler-ownership-final-build.log` 记录全部设备/编译归属及关闭修正后的 Kotlin/Java/JAR 构建成功。正式包90,205,762字节；`compiler-ownership-final-package.log` 确认外层与嵌套 core 无 test/probe/SelfTest/FakeBackend 类，必需运行库完整，内嵌 native SHA256 为 `A8F6EA5A1E0E1C1213F796209A0FF8DEE3B5FB2C8DB094BBDFD3840A3FE667DE`。本轮只改 JVM/文档，未重编 native，未新增测试或业务故障开关。

`compiler-ownership-runtime.log` 记录编译归属迁移后00:52:32自动进入隔离 New World，00:52:33消费真实 `503×283 → 854×480` 的 DLSS prepared 输入，VRS持续执行，累计呈现超过19,800帧。01:06:49世界保存和设备清理完成，Gradle `BUILD SUCCESSFUL in 15m 8s`。随后关闭修正的最终版本在 `compiler-close-runtime.log` 中01:10:29自动进入世界，01:10:31消费相同尺寸DLSS输入，累计超过5,400帧；01:13:41完成surface/encoder/async/device清理，Gradle `BUILD SUCCESSFUL in 4m 42s`。两次均使用窗口正常关闭，不截图或模拟按键，未要求用户重复点警告；动态画质未由代理判定。

该检查点结束时没有本任务客户端，原 `run/options.txt` 与 `run/config/dxr_mc-client.toml` hash 保持原值。世界/显示来源错配的确定方案见主计划 §4.1；其后 ABI9 接线见下一节。

### 世界重建与晚期显示帧（ABI9）

本节保留 ABI9 的接线与对应构建/运行事实；当前两阶段契约见 [FFM ABI](../misc/ffm-abi.md)，本节旧产物的记录仅对应 ABI9。

世界 PreparedFrame 在世界及 AfterLevel 完成、第一人称手部和晚期效果之前发布并消费；AA/SR 写入 SceneDisplay。手部使用实际 HUD projection/model-view、独立 depth/MV 和 R8 coverage，晚期场景保持原版 screen effects、outline 与 post 顺序。GUI 在独立 UiDisplay 上绘制，GUI endFrame 后唯一提交 DisplayFrameInputs；native 使用显示相机统一世界和手部 guides，再向三家 FG adapter 提交。Streamline SR 使用候选分配的 viewport2..4，现役 viewport 随 Commit 发布；DLSS-G 使用 viewport1，早期 SR→FG 标签入口已删除。

显示工作图保留完整 depth/MV/validity/provenance tuple；XeSS 的3×3 dilation选择整个 tuple。coverage=0/1/3 分别表示未覆盖、覆盖但运动不可用、覆盖且运动有效；65504仅为RG16_FLOAT范围上限。工作图汇总最终像素有效性到四字节 buffer，提交并等待同 context fence 后回读；存在不可用运动则跳过本帧 FG。两段之间保留 GuiReady 与输入/输出/descriptor租约，只有最终提交创建 Present ticket。该同帧等待每个候选FG帧执行一次，CPU/GPU吞吐代价尚未测量。

native 把 SDK Accepted、command Queued、实际 Presented 分开，用不可变 ticket 配对 frame identity、context、provider generation 与 fence。UI 清理前结算上一 Present 和 SDK 源读取；FSR 的 scene-only 呈现只在当帧已接受且UI注册成功时使用，其他情况呈现完整 composite。未知post映射、整层或最终像素无有效运动、GUI已经包含场景背景时跳过FG。真实帧的世界、手部、效果和GUI仍正常绘制。

显示层同时修正 blur 前 GUI 被 scene 覆盖的问题；普通F2延迟到GUI完成、晚期提交和完整合成之后，沿原版保存/通知流程读取真实输出尺寸。Tracy读取相同完整显示目标。没有Present的帧显式取消匹配ticket并清理当前GUI/scene状态，资源继续由原encoder及真实fence持有。debug panorama维持独立原版入口，本次未改其逐面生命周期。

ABI9 的取消入口和配套 JAR 已完成构建，`abi9-display-final-jvm-build.log` 记录 JVM 打包结果。`abi9-display-final-client.log` 记录客户端进入隔离 New World 并消费 DLSS prepared 输入；用户反馈只有准星、物品栏等 HUD 可见，世界和手持物没有显示。该运行记录保留为黑屏复现，不能作为出图通过的结论。

### 世界显示黑屏：描述符堆一致性

普通绘制的 `pushDescriptors` 已使用 command context 私有的可增长描述符页，blit、锐化、SMAA 和 TAA 却仍把 SRV 写入全局 draw heap。世界结果搬入 SceneDisplay、SceneDisplay 搬入 composite 时，root table 的 GPU 句柄指向了命令列表未绑定的堆。SMAA 的 edges 与两张 LUT 也来自不同的 shader-visible 堆，无法同时绑定。

device 模块内部的 `allocateDrawDescriptors` 统一分配当前 context 的连续描述符范围，普通绘制与后处理共用该入口。后处理在绑定任何 root table 前一次申请本次 draw 所需的全部纹理：blit/锐化一张，SMAA 权重最多三张、邻域两张，TAA resolve 三张；扩容只发生在整批绑定之前。旧的独立 LUT 描述符堆已删除，资源仍由原 SMAA 状态持有，描述符页继续使用原 recording lease 与 fence 生命周期。GUI 合成和场景深度搬运的 fullscreen pipeline 显式关闭面剔除。

`abi9-black-world-native-build.log` 和 `abi9-black-world-jvm-build.log` 记录配套 Release DLL、Kotlin/Java 与 JAR 构建通过，已核对嵌套 core JAR 的 DLL 与 Release 输出一致。`abi9-black-world-client.log` 对应修复后的运行，用户仍反馈只有 HUD 可见，描述符修复没有解决本次全部黑屏原因。

### 世界显示黑屏：GUI 清屏 alpha

`VanillaFrameCoordinator.beginDisplayFrame` 的世界分支使用 `Vector4f()` 清 GUI。实际 JOML 1.10.9 的零参数构造将 `w` 设为 1，清屏颜色因此为 `(0,0,0,1)`。独立 GUI 随后按预乘 alpha 叠到 scene 上，整个不透明黑底覆盖世界与手部，只留下 GUI 自己绘制的准星、物品栏。已用实际依赖 JAR 的 `javap -c org.joml.Vector4f` 确认构造字节码为 `fconst_1; putfield w`。

世界分支已显式改为 `Vector4f(0f,0f,0f,0f)`，菜单继续使用原 GUI 清屏颜色。这个修正仅涉及 JVM，无需重编 native；`abi9-gui-alpha-jvm-build.log` 记录 Kotlin/Java 与 JAR 构建通过，`abi9-gui-alpha-client.log` 记录重新进入隔离 New World。用户已确认世界地形、天空和手持物恢复显示。此次确认针对黑屏修复，不扩展为运动画质或性能结论；临时 PIX 捕获配置已恢复关闭。

### 世界投影 jitter 修正（2026-11-13）

实际上传的世界投影包含 hurt/bob/nausea 变换，旧的元素 8/9 减法在这些变换下产生随顶点位置变化的偏移。共享 `D3D12Temporal.jitterProjection` 已改为对实际投影左乘 clip-space 平移，深度与齐次行保留原值。实际 raster 上传、velocity 逆矩阵和 VRS 共用该函数；手部与 GUI 的独立投影继续分别处理。数学说明集中在 [抗锯齿文档 §3.1](../antialiasing/README.md#31-抖动序列)。

独立 JavaScript float32 计算对照完整矩阵乘法与投影后的像素位移，覆盖六类矩阵、四组 jitter 和 648 个位置。最大像素误差约 `0.00001824`，同一组合变换下旧公式误差约 `0.02512`；深度与齐次值保持一致。该计算核对公式，实际 Kotlin 编译由 `projection-jitter-jvm-build.log` 记录。

`compileKotlin compileJava jar` 通过，正式 JAR 为 90,137,549 字节，内嵌 native 与前述 Release 一致，SDK 资源和许可证齐全。用户原 options/config hash 保持原值。本次只更新 JVM 和文档，未重编 native 或启动客户端；新 jitter 的动态画质待用户实际观察。

### 世界运动完整性、透明来源与 mesh 压紧（2026-11-14）

本节保留 ABI9 检查点的实现与对应构建事实；ABI10 对天空/天体及透明实际像素贡献的后续修改见[下一节](#abi10-运动来源与像素贡献)。

`D3D12RasterMotionCapture` 在世界绘制结束时封存完整性，显示提交读取同一 `VanillaFrameInputs` 的结果；第一人称采集会清空临时 geometry/published 列表，但不会改变已封存的世界结论。世界历史要求连续真实帧、相同世界/worldEpoch、resourceVersion 和 historyGeneration。缺失或尺寸不匹配的 previous 顶点、不完整模型、重复 cube、混合空间、未绑定的已发布历史及仅有 AABB 的实体均使完整性失败。历史在通过代际和空间校验后才计入实际绑定；不支持的实体/粒子着色器和实例化几何也不能声明完整运动。每次实际 draw 记录调用前的 history binding 序号，成功返回后要求本次调用内确实绑定历史；普通 GPU indirect 没有该绑定，不能仅凭存在 exact PSO 声称可用。AABB 行继续按近似语义服务已有重建，显示 FG 不把它升级为逐像素来源。

静态相机重投影能力在编译产物创建时确定。`CameraReprojectionSources` 校验本次实际编译、include 完整展开后的源码及受控注入 defines，指纹固定到当前 `26.3.0.95`；顶点和 include 变形、未知 defines、翻转变体均不认可。地形要求原版完整顶点源；片段出现深度写入时还要求原版完整片段指纹，以接受原版 OIT include 中受条件控制的深度不变写入。自定义片段没有深度写入时可认可；含深度声明的修改版片段保守失效。screenquad 同时要求原版顶点和五种已核对的完整片段源：depth blit、integrate depth、OIT depth bounds、color blit、OIT composite，未知片段及 UV 修改均失效。原版 SKY/END_SKY 还要求对应 pipeline 对象身份。temporal sky 已接入真实 previous 变换来源；太阳、月亮、星星和日出日落等透明天体仍缺完整 provenance，相关绘制保守跳过 FG，普通世界绘制保持执行。游戏版本或资源包改变认可源码时，需要重新核对能力与指纹。

`FrontendRenderPassGuideMixin` 观察九种原版 draw 入口的成功返回，直接和 CPU 批量绘制检查实际非零 count，间接绘制按非空命令范围记录。`WorldTransparencyCapture` 只在世界绘制窗口采集 blend/OIT 事实，AA/SR 和手部之前封存，在显示帧退休时丢弃。不可变输入区分没有透明贡献、普通混合缺 previous/provenance、OIT 缺 color resolve，以及 color resolve 已有但 motion/provenance 缺失；单独执行 OIT composite 不声称产生运动。OIT depth-bounds cull、depth blit 和 integrate 等全屏辅助绘制不记作透明几何，空 OIT 阶段不会因这些辅助图而跳过 FG。显示 world `motionValid` 同时要求相容 previous、实际 motion texture、完整 raster 来源、opaque particle 来源和透明 availability；不满足时复用 native 的 `skipDisplayFrameGeneration` 清理本帧 provider 输入，真实帧保持原绘制和呈现。DEBUG 日志给出真实帧号与跳过原因。已经提交但完全被遮挡或 alpha 为零的透明 draw 仍会保守跳过 FG；当前没有透明逐像素运动和多贡献 provenance 生产者。

opaque particle 遇到未知 extractor 或缺少生命周期 owner 时，以原子值记录对应的单调真实帧号；显示提交按同一 frame 查询完整性。这个记录不持有世界对象，也不依赖跨 PackedState 的普通布尔读写。已知粒子继续使用实际生命周期、拓扑和 previous 顶点，缺少 previous 时由既有 raster 完整性规则处理。

`DisplayFrameGuides` 沿现有版本化预热通道创建，FG 关闭时也预热。native 按 generation 暂存完整 graph/instance，编译、创建和 record schema 校验全部成功后才替换；失败保留原 owner，旧 generation 拒绝，同 generation 复用。显示提交要求已有匹配的预热产物，旧实例仍由录制租约保留到真实 fence 完成。

该历史版本的 mesh 批次保存去重几何列表，重复 OIT/绘制准备复用该列表；atlas 全部命中时直接复用驻留 buffer。当时地形 `EmitVisible` 使用最多 64 条输入的 coalescing node，每组领取一次连续可见槽位，保留原 descriptor/section 关联和容量错误处理；五节点拓扑和 visible MS 消费格式保持一致。prepare/draw 的资源声明全部收归 native，私有输出与常规材质读取共用原声明入口，JVM 绘制只保留一个 downcall。该版本没有帧时间实测；当前生产与融合候选的对照见[地形节点融合与驻留编码](#地形节点融合与驻留编码)。

独立源码分类验证直接调用生产的 defines 注入与 include 展开函数，从当前游戏 JAR 读取真实 shader；原版地形/OIT、静态天空和五类全屏辅助源可认可，顶点/include/depth/UV 修改、未知片段/defines 与独立天体均按规则拒绝，记录在 `camera-source-classification.log`。验证源码和类仅位于忽略的 `build/reports`，不进入发布包。

配套 native Release/x64 与 `compileKotlin compileJava jar` 构建通过，包含 frameprep 预热收敛、terrain 资源调度及完整性接线；构建使用现有本机 JDK、VS2026 和来源仓 SDK 目录，Kotlin 依赖沿已有验证用 Maven Central 路由取得，未修改项目仓库配置。发布容器与嵌套 core 均无测试类，SDK 运行库及许可证资源齐全；内嵌 native 与 Release 输出 SHA256 一致，为 `963D5B01D121A3690A29C893A609399AF4180E036294CD1911603E2974A29F27`。本轮没有启动客户端；新的 terrain 材质读取接线、重载预热时序、动态运动画质与性能按实际运行结果确认。

### ABI10 运动来源与像素贡献

`D3D12RasterMotionShader` 沿用原 fragment、discard、`SV_Depth` 和 RT0 颜色输出，从最终 RGB/alpha 判断实际颜色贡献。无贡献输出 raw tuple 的 `w=0`，对应 guide blend 保留已有后方 tuple；有贡献先记录 covered-invalid，只有无 blend 替换或 over 的 alpha 恰为 1，才允许真实 previous motion 或已认证静态相机来源覆盖为有效。实际贡献规则如下：

| 颜色合成 | 产生颜色贡献的条件 |
| --- | --- |
| 无 blend / REPLACE | 总有贡献，alpha 为零也算 |
| SRC_ALPHA / ONE_MINUS_SRC_ALPHA | 最终 alpha 非零 |
| ONE / ONE_MINUS_SRC_ALPHA（预乘 over） | 最终 alpha 非零或 RGB 任一分量非零 |
| SRC_ALPHA / ONE | RGB × alpha 任一分量非零 |
| SRC_COLOR / ONE、ONE / ONE | RGB 任一分量非零 |

partial over、additive 以及 OIT composite 的实际贡献均保守得到 coverage 1；原 fragment discard 或实际无贡献像素保留后方 tuple。OIT 中间工作图继续负责原颜色处理，由真实 composite 阶段判定最终贡献，尚未生产多贡献运动。`WorldTransparencyCapture` 只有在本次 draw 确实录制 producer binding 后才解除 draw 级透明否决。ABI10 检查点的 ORDERED_INDIRECT 与 opaque terrain mesh/work graph 尚未写入 raw MRT；[ABI11 接线](#abi11-地形运动接线)已补齐这两条路径。未接生产者、未知 blend 或未认证 shader 仍明确不可用。

`CelestialMotionCapture` 按实际 owner、太阳/月亮/星星/日出日落角色、几何版本与连续真实帧保存局部 model-view，并结合真实 previous 投影提供变换；顶点与片段仍要求原版 pipeline 身份、展开源码和受控 defines。shader 已实现有效 celestial tuple 的 depth=0 分支，additive 实际贡献仍为 coverage 1。ABI10 的 raw 在 main pass 开始时登记并清零，漏过了更早的天空绘制，depth=0 像素使用相机旋转重投影。ABI11 已将 native 录制、raw 登记和清理提前到天空之前，静态表面的替换贡献由普通 raster 与地形 WG 共用规则覆盖；新增时序的实际运行状态见当前构建节。

`D3D12Velocity` 双 MRT 输出有限值 RG16F motion 与独立 R8_UNORM coverage；世界全屏合成当前写入 1/255 或 3/255，编码契约见 [FFM ABI](../misc/ffm-abi.md)。存在 raw tuple 但来源深度与最终深度不匹配时保持 coverage 1。因此 alpha=0 仍写深度、或后续静态表面覆盖引起的来源深度错配，仍可能使局部像素无效；来源不足不以零 motion 伪装成有效。SR/AA 继续只读有限运动纹理。以上是当前源码接线，最终配套构建、动态画质与实际 FG 运行分别见下文，完整 guides 尚未完成。

## 配置与原生世界输出的当前接线

配置契约集中在 [render-configuration.md](render-configuration.md)。RenderConfiguration 拥有 requested/applied/persistent，PreparedRenderConfiguration 创建真实候选，按 FG Prepare → SR Prepare → 组合 Commit → 世界附件发布推进。完整 Published 才更新 persistent，AA/比例/DLSS/SR/FG 的 save 统一读取 settingsToSave，临时 resize 配置不覆盖磁盘偏好。同尺寸失败保留原配置；主菜单等待世界返回 AwaitingWorld，结束 UI 回调并保留 requested/pending。取消仍忙的提交恢复 persistent，若与临时 applied 不同则继续准备。resize 尚未 ready 时先发布新尺寸临时 NONE 配置，同尺寸 SDK 拒绝后暂停自动重试，等待新请求或新尺寸。小窗口按 caps 最小输入向上抬高 Applied 的真实比例，用户存储档位保持原值。

单个 Kotlin OptionsRenderOptionsMixin 在 loadSavedSettings 内一次加载 AA 和其余渲染选项，全部加载后才归一化 requested 并初始化 persistent；未进世界时磁盘偏好不会被默认值或临时 NONE 配置覆盖。独立 AA 加载/保存 mixin 已移除。

SR Prepare 返回 int32 typed status 和独立 outToken，明确区分暂未准备、拒绝与完成结果未知；未知结果继续持有资源并等待安全退休。FG Prepare 的 0 等待先前显示帧、录制、Present 或 provider 输入退休，-3 表示 SDK 不支持或参数档位拒绝；FFM 保留 typed FgPreparation 和候选 token。SR_DISPATCH 为 152 字节，尾部 pCamera 指向真实 RASTER_CAMERA；Streamline 候选使用独立 frame0 allocation token，正式帧统一从 ++gReflex.frameCounter 的 1 开始编号。配置 retirement 向 encoder 正向登记 AutoCloseable；device 不反向依赖配置。native 在 GPU idle 后检查 FG、SR provider 与 SDK 释放，失败保留设备。

VanillaWorldOutput 只分配并保活资源、记录世界运动与提交事实。native prepareWorldOutput 统一完成 AA/SR 选帧、曝光、mask V2、PreparedFrame 发布、evaluate 与 SceneDisplay 输出；重建历史和相机跳变判断归 native。DLSS 后置锐化保留，输出纹理由 WorldOutput 持有。原独立 JVM 逐帧 SR/DLSS 数据面、DLSSOutput、ReconstructionHistory、跨门面锁 setter 与 scheduled destructive resize 描述已经失效。

mask V2 用真实颜色/深度历史、motion、jitter 差与 OIT 来源区分 reactive、composition 和 responsive；FSR 使用前两者，XeSS 使用 responsive。旧 mask 均值读回已删除。贡献规则见 [ABI10 运动来源与像素贡献](#abi10-运动来源与像素贡献)，天空、ORDERED_INDIRECT 与地形 WG 的后续接线见 [ABI11 接线](#abi11-地形运动接线)。未知 post 仍跳过 FG。FG 最终阶段保留同帧 validity 等待与回读，尚无性能实测。

构建、客户端覆盖与 resize 后退出确认见下节。动态画质仍按用户实际观察确认。

## 当前构建与运行

### Lane 拷贝与原版 PSO

ABI31 使用 Minecraft 26.3.0.95 的原版源码，四条全屏 depth/bounds 管线通过生产 Naga 编译与源码认证，并在 RTX 5070 Laptop 上创建 PSO。三条 blit 使用 `InSampler` SRV 0，cull 使用 `Projection` CBV 0 和 `DepthBoundsSampler` SRV 1。native 可根据已认证 receipt 录制 tile array 拷贝和 cull。cull 的独立 GPU 验证覆盖两层各 68×68 个像素的 RGBA 和深度，包括通过测试写入与未通过时保留原值。完整帧调度尚未调用该入口。

### 地形可见表分桶

ABI30 用原地形 VS 将实际 visible meshlet 转为屏幕包围框，再完成 tile 分类、prefix 和间接命令生成。Kotlin 只分配 GPU scratch 和常量，各 stage 独立持有根签名。见[分桶契约](transmittance-tile-execution.md)。

RTX 5070 Laptop 创建了全部 18 个 envelope PSO。独立 D3D12 GPU 检查覆盖 ACCUMULATE 的 normal/flipY、跨近裁面、同一 meshlet 覆盖两 tile 和 visible 表尾项。两种朝向均得到 mask `[2, 3, 0]`、3 条有效间接绘制命令、错误字段为 0。native、JVM 构建和 FFM 测试通过，`copyNative` 配套 DLL 哈希一致。完整帧仍等待四 lane 执行器。

### World depth seed 采集边界

ABI29 将世界深度事实采集接入需要 OIT 重建且 resolve program 已发布的帧。在首个世界 depth clear 前开启，在 `executeOit` 入口封口。完整 clear 和真实 draw 通过原录制身份验证后清除 `GAP_OPAQUE_SEED`；四 lane 回放仍需要绑定冻结和 adapter。见[完整事件流](transmittance-tile-execution.md#实际命令内容与-adapter-的实施契约)。

native Release/x64 和 JVM `compileKotlin compileJava` 通过。`copyNative` 后构建目录和开发客户端使用的 DLL 哈希与 native 产物一致。FFM 检查通过 839 个布局字段及分配用例。RTX 5070 Laptop 的 `build/reports/workgraph/oit-operations/20261119-124750/gpu.log` 覆盖三帧和九条普通 OIT draw，其中一帧验证实际深度 clear/draw 和重复封口，一帧验证达到事件上限后仍可提交。`build/reports/workgraph/reuse/20261119-125506/gpu.log` 覆盖 Work Graph 资源别名、在途 context 和提交槽复用。游戏画面、地形四 lane 回放和性能尚无实测结果。

### Kotlin 内联与嵌套 FFM 分配

编码作用域、短数组包装和 staging writer 显式 `inline`，业务调度、downcall 门面与 mixin 边界保持普通函数。小型参数使用四个线程缓存槽，结构体与数组按真实布局大小、stride 和对齐分配；具体缓存预算及 GPU 上传寿命统一见 [FFM 数组与结构体数组](../misc/ffm-abi.md#24-数组与结构体数组)。嵌套作用域的所有参数均从独立临时 arena 分配，包含小于缓存上限的参数；push constants、VRS 矩阵与回读结果也遵守同一作用域。

JVM `compileKotlin compileJava checkD3D12Dependencies` 通过，日志为 `build/reports/workgraph/ffm-nested-scope-jvm-build.log`。独立分配检查已复现小型嵌套参数覆盖外层缓存的问题，修复后检查通过；实际 DLL 的布局字段、FFM 签名和拒绝输出也通过。字节码中的两个 WG、push constants 与 VRS 方法各只有一次 typed `invokeExact`，没有 `Function.invoke`，记录位于 `build/reports/workgraph/ffm-nested-scope-bytecode.txt`。性能收益尚未测量。

### WG 参数复用与 sampled view 延迟分配（2026-11-16）

terrain Work Graph prepare 和普通 Work Graph dispatch 复用三个独立 FFM scratch 槽，分别编码句柄、offset、length。短作用域显式 inline，primitive 数组直接复制，native 同步消费参数并保留实际 GPU 资源。大小、对齐和寿命的分配规则见 [FFM 数组参数](../misc/ffm-abi.md)。

sampled texture capture 不再预分配私有 descriptor heap。没有冲突写入时读取原 view；需要读取已保存版本时，首次 resolve 才申请预算并创建私有 SRV，后续复用句柄。取消读取不提前释放已被录制命令引用的 descriptor，资源沿真实 fence 退休。

native Release/x64、JVM `compileKotlin compileJava checkD3D12Dependencies` 通过，ABI 保持 25。DLL 为 4,448,768 字节，SHA256 `A761B464B1343CE5DA51A64D5B38D8E4DA0E10C74FED1B78D5B4F2B3EE29BC93`。构建日志为 native `target/sampled-view-allocation-native-build.log` 与 JVM `build/reports/workgraph/ffm-wg-allocation-jvm-build.log`。

两条 WG 方法的字节码均只有一次 typed invokeExact 和两次 primitive 数组直接复制，没有 Function.invoke、vararg 数组副本；临时 Arena 只位于大小或对齐超限的条件分支。实际 DLL 的 831 个布局字段、FFM 签名与现有大小、对齐、空数组和异常释放检查通过，记录为 `build/reports/workgraph/ffm-wg-allocation-bytecode-check.log` 与 `ffm-allocation-check/result.log`。本次没有运行 GPU 内容保存或私有 SRV 采样检查，没有打包、更换客户端或游戏性能结果；完整四 lane executor 仍按其明确缺项实施。

### ABI26 terrain stage registration（2026-11-17）

terrain Work Graph 的 16/32/64 meshlet 变体现在都注册普通 source 与存在的 capture source。native route 保存单一版本化 stage 合约、tile generation 和最多两个 source identity；外部 root 的 binding type/register、push constants、深度格式、MRT 数量与参数位置均在 PSO 创建和注册时核对，source 与 tile 不能形成自引用。terrain packet 保存注册描述和 route 强租约，V2 batch 准备再次核对 route、source/tile identity 与代际；这一步只认证接线，不把完整四 lane executor 标成可用。

ABI 26 的 40 B `mcdr_terrain_tile_stage_v2` 在 native `Release|x64`、JVM `compileKotlin compileJava checkD3D12Dependencies` 和实际 FFM 分配检查中通过，当前 DLL 布局字段数为 839。FFM 检查同时覆盖不同大小目标、四个 scratch 槽、嵌套 confined arena、大目标、高对齐、异常释放和空指针策略。完整内容冻结、opaque seed、stage executor 与动态画质仍按各自缺项记录，未运行游戏或性能采样。

### ABI25 operation packet 接入执行流（2026-11-16）

普通 operation 的真实 receipt 已经逐条接入 V2 stream，native 保留 device packet 并在 seal 按 nativeSequence 与 terrain 命令合并。scope/pass 及 receipt 的公开计数保持原契约，嵌套调用的返回次序不参与 GPU 命令排序。事件容量包含真实 operation 数量，FFM 编码缓冲按布局分配并在 seal 内复用。完整接口、预算与缺项见 [实际命令契约](transmittance-tile-execution.md#实际命令内容与-adapter-的实施契约)。

旧 BatchV2/V3 已移除每 packet 的一次性 consumed 判断，Resolve V4 也不再把四个 draw packet 当作四个采样 lane。旧描述无法携带完整事件，继续在 binning/replay GPU 命令前返回 UnsupportedContent。terrain WG 非 capture 分支的 VBO 读集改为真实 buffer 剩余范围，避免误用 candidates 表的长度。

native Release/x64、JVM `compileKotlin compileJava checkD3D12Dependencies` 通过，构建分别使用 VS2026 自带 MSBuild 与机器已有 JDK。VS 的 C++ targets 位于 `MSBuild/Microsoft/VC/v180`；使用 `dotnet build` 找不到 C++ props 时，应改用该 MSBuild。当前 DLL 为 4,447,232 字节，SHA256 `446CA25E1F43103686D403A675A369A7A88E90384DD140229E575DFF150578D4`，日志为 native `target/oit-operations-abi25-native-build.log` 和 JVM `build/reports/workgraph/oit-operations-abi25-jvm-build.log`。

实际 DLL 的 831 个布局字段、ABI25 版本与新增 FFM 签名检查通过；现有分配检查覆盖小缓存、四槽独立性、大目标、高对齐、异常释放与空数组。字节码检查确认五个 bulk 方法各只有一次 typed invokeExact，没有 Function1.invoke。现有 Work Graph GPU 程序通过丢弃录制、重开列表、每列表 64 次派发、两个在途 context、提交前释放 program 与复用已提交槽的检查；记录为 `build/reports/workgraph/ffm-allocation-check/result.log`、`oit-operations-abi25-bytecode-check.log` 和 `reuse/20261116-224552/`。尚未打包或更换运行中的客户端，完整四 lane executor、动态画质和游戏性能没有本轮结论。

`src/probes/oit_operations/` 已用两个独立 encoder 创建两个真实 64×64 OIT 帧，完成六次 draw：检查原始 receipt/packet/read-set 身份、逆序追加、重复与伪造身份拒绝、跨帧跨 context 拒绝，以及漏项返回原始 receiptId。第一帧遗漏 receipt 6，seal 的 missingMask 为 115；第二帧全部登记后为 51，RECEIPT 缺项清除，已有内容/seed/normalization/adapter 缺项保留。每帧请求销毁 draw PSO/目标后再提交，真实 fence 均完成。记录为 `build/reports/workgraph/oit-operations/20261116-230147/`；具体覆盖范围见 [程序说明](../../src/probes/oit_operations/README.md)。同一 context 的完整显示帧复用和合并命令表的外部查询不在该程序覆盖范围。

### ABI24 graphics command packet 与 OIT operation 回执（2026-11-16）

native Release/x64、JVM `compileKotlin compileJava` 与依赖方向检查通过。实际 DLL 为 4,445,696 字节，SHA256 `717387900D046BAB008E4A16E1031B3B43373235080FF0D46D76FEB8CC72F23A`。ABI24 的 144 B operation begin、192 B operation receipt 与 831 个布局字段已由真实 DLL 和 JVM 镜像逐项核对。

普通 Draw、DrawIndexed、Indirect 和 Mesh 命令在真实 D3D12 命令录制前后生成 packet；packet 保留当前 PSO/root、绑定、顶点/索引或 indirect 范围、附件、读集与 recording lease。observer 未绑定时不分配 packet；绑定后先按真实变长容量预留预算，再保留 GPU/对象租约。当前 content 仍记录为 `Unfrozen`，adapter 仍记录为 `Unavailable`，没有用逐 draw GPU 复制冒充内容冻结。

OIT operation 在 action 前建立 native scope，真实 draw 确认并完成 input pass 后才发布 receipt；finish 会检查 scope 内每个 packet，query 以 0 作为正常终点。嵌套 operation 的 receipt 只归最内层，父 scope 只接收 GPU 失败事实。JVM 使用独立的 `FfmScratch` 槽位编码 144 B、8 B 和 192 B 目标，action 不占用 scratch 生命周期；超过 64 KiB 或要求更高对齐的目标进入 confined arena，数组字节数用 `Math.multiplyExact` 计算，缓存扩容带溢出检查。

独立 `src/probes/ffi_allocation/` 加载上述实际 DLL，检查 831 个布局字段、四个 operation FFM 符号及空 recording 拒绝边界，并覆盖小/大/高对齐分配和异常释放。没有执行 GPU operation replay、完整 content freeze 或动态画质确认。

### ABI23 来源绑定与未消费输入撤销（2026-11-16）

native Release/x64、JVM `compileKotlin compileJava` 与依赖方向检查通过。实际 DLL 为 4,408,832 字节，SHA256 `5922B998C111128FE73450F0021552DC451D48C9B939BE330A5361AF166DDC54`。构建日志为 native `target/oit-provenance-abi23-native-build.log` 与 JVM `build/reports/workgraph/oit-provenance-abi23-jvm-final-build.log`；没有执行 `copyNative`、打包或替换运行中客户端。

`src/probes/ffi_allocation/` 加载上述实际 DLL，800 个布局字段与 JVM 镜像一致，capture 与 normalization pipeline 新入口的 FFM 签名、拒绝时输出、大小/对齐缓存边界、大目标作用域、异常释放和空数组策略通过。`oit-provenance-abi23-ffm-bytecode.txt` 中五个 bulk 方法各只有一次 typed `invokeExact`、没有 `Function1.invoke`。这些结果覆盖 ABI 与 CPU 分配；本轮没有 GPU 内容保存、完整 normalization PSO 创建、运行画质或性能测量。

### ABI21 真实 Present 回执与 terrain OIT（2026-11-15）

异步 Present 入队时，JVM 曾立即推进 previous，而 native OIT 仅在实际 `S_OK` 后发布，两边可能引用不同历史。现在入队只保存候选，通过 schema 1 的真实 Present 回执，在下一显示帧开始、投影冻结及配置应用前结算。世界的 recorded/presented 分开；世界、viewmodel 和 OIT 的发布校验实际身份、fence 与成功状态，OIT 还要求 native publication flag。超时、提交未知和退休信号未知保留 owner 与未消费回执。配套 ABI21 客户端的固定留屏动态结果已由用户确认消失。

terrain ACCUMULATE 已沿生产 Work Graph 的 `PublishTerrainMetadataV2 → BindTerrainDrawV2 → ExecuteIndirect → Unbind` 接入 records。每批提交有界 section 表，同 generation 的静态顶点复用真实 VBO，previous clip 在 MS 中用已呈现前帧相机与原点生成；CPU 不逐三角形展开 previous，也没有第二套静态顶点 atlas。section 身份包含 world/producer/坐标/layer，材质跟踪真实 texture/view/sampler 和成功上传/写入 generation。三个 texture 上传/复制 FFM 改绑 Checked 导出，实际成功才记录版本；登记表淘汰同时撤销纹理身份，漏记写入的纹理保持未知。

terrain 的 producer owner 固定为当前 `LevelRenderer`。原先使用每帧新建的 `ChunkSectionsToRender`，producerId 和 section surfaceId 会随批次一起更换，使同一地形始终匹配不到已呈现历史。批次只提供当帧绘制范围，不拥有跨帧身份；世界切换继续由 worldEpoch 撤销旧身份。

原版 `SectionDraw.hasCustomIndexBuffer=false` 的几何使用共享索引缓冲，上传时没有独立 index buffer，`indexBytes=0` 是合法输入。`LayerUpload` 已允许这个边界，继续校验正的顶点大小、完整 quad 数量及 meshlet 范围；自定义索引路径仍保留实际字节数。旧构造条件错误拒绝这种上传，会在进入世界后的地形构建中抛出异常。

SceneDisplay 深度转存使用普通 frontend render pass 声明实际附件和 SRV 输入，深度清除随 attachment 提交。手动 `declaredPass` 再创建 frontend pass 会重复拥有相同目标，已移除这层声明。首次 opaque motion 没有 previous 时仍清零 motion/coverage，清除结束状态按 native 的实际 `RENDER_TARGET` 声明；正常绘制分支保留 `EndRenderPass` 后的 `COMMON` 出口。

普通 draw 的 OIT 登记入口保持在业务包内执行，不内联到 mixin。Kotlin 曾将登记 lambda 的辅助类复制进受 Mixin 管理的包，导致雨雪 indexed draw 首次加载该类时被 NeoForge 拒绝。Mixin 继续使用 Kotlin 转交绘制事实，登记、贡献提交和失败处理仍由 `OitProducerCapture` 拥有。

shader ABI5 保持 record 144 B、pixel 8 B，capture 常量为 208 B，GPU terrain section 为 80 B；t9/t10 提供 current/previous section，capture 为 11 SRV + 3 UAV，resolve 为 11 SRV + 5 UAV。图形绑定一次预留 capture 与普通 SRV，防止恢复普通绑定时换堆使句柄失效。Naga 原 VS 返回结构保持原样，独立 MS 输出逐字段复制并追加 previous clip，修复原聚合初始化字段数不匹配；捕获 MS/PS 的插值接口同步排列。

native Release/x64 构建通过，为 `4,121,088` 字节，SHA256 `8B88C4728F8A4A461B2E9CE087270CCDEDD5CD4082CF6E660F7E37FC1CD5B076`。配套 `compileKotlin compileJava jar` 通过；完成深度声明、首次 motion 清零、terrain owner、Mixin 内联和共享索引缓冲边界修正后的最新 JAR 为 `90,969,677` 字节，SHA256 `2B245CFDF67D5BEED3A6B5929BA94DF34CB18DB1BBA51DE85C704CDC45A102E2`。最新包检查确认内嵌 DLL 一致、10 项运行资源和 29 项关键 class 齐全，terrain 上传 mixin 已登记，无 probe/test/SelfTest。构建及包检查记录为 `build/reports/workgraph/abi21-native-build-final.log`、`abi21-terrain-upload-build.log`、`abi21-terrain-upload-package-check.log`，产物继续使用 `abi15-build/libs/` 目录名。实际字节码确认九处 Mixin draw 包装通过 `invokedynamic` 调用业务登记入口，没有旧的内联辅助类；记录为 `abi21-mixin-boundary-bytecode.txt`。最新隔离客户端已持续运行约 300 帧，没有声明、Mixin 或地形上传崩溃。用户于 2026-11-15 在该客户端的 DLAA/M、FG 关闭场景明确反馈“残影消失了”，关闭水、玻璃和云固定留屏的问题记录。

最新运行日志为 `build/reports/workgraph/abi21-client-terrain-upload.log`。同一客户端的 `runtime-sdk/dxrmc/sl.log` 在 12:40:05 明确记录 `(DLAA) Using App hint Preset M`，以及 `Created DLSSContext feature (854,480)(optimal) -> (854,480) for viewport 2`；确认实际创建了等分辨率 DLAA，FG 为关闭状态。该次运行未启用连续采集，保留客户端供用户在正常运行条件下观察。

真实原版 GLSL 经生产 Naga/包装器与 DXC SM6.9 离线编译通过：14 对 normal/flipY IA 接口、6 对 terrain 16/32/64 mesh 接口及三个 OIT compute 入口。记录为 `abi21-production-capture-shaders.log`、`oit-compute-abi5-production/`；没有执行游戏读回、GPU 重放或新增业务测试。配套客户端使用原隔离存档和 DLAA、FG 关闭配置启动，不加载采集、debug layer 或 JFR。FEATURE/BORDER 新接线的源码、构建和未加载状态见下一节；TRANSMITTANCE alpha/coverage AA、未认证来源的完整 records 与多贡献 FG guides 仍有实施工作。

实际启动发现并修复两处阻断：云颜色/向量的 `WrapOperation` 仅写 `prepare` 时没有选中目标，现与其它采集点一样使用完整方法描述符；OIT 程序候选要求 command list 必须关闭，与统一 frame-programs 在空闲、已打开列表上发布的契约冲突，现移除这项额外限制，继续由公共控制边界拒绝活动 render pass、异步 owner、冻结世界帧和提交未知，OIT 域拒绝未退休捕获帧。首次失败日志为 `abi21-client.log`，资源重载失败并正常关闭的日志为 `abi21-client-final.log`，修正后客户端日志为 `abi21-client-oit-prewarm.log`。

### ABI21 FEATURE 与 BORDER 接线（未加载当前客户端）

FEATURE 已沿原 model Key/cube、item Slot/stack/quads/topology 和 particle Lifetime/role/topology 接入真实 `BufferBuilder → MeshData → ExecuteInfo`。`lookupFeature` 复用原 previous positions，另外保留来源和 UV0；实际 VB、IB/type、stride、slice/baseVertex、index 范围和 pipeline topology 全部核对后才编码记录。每条 draw 一条 native source，全部 primitive.sourceIndex=0，跨帧身份由 source/ordinal/triangle 的 surface owner 拥有。previous 只接受真实成功 Present 对应帧，使用该旧帧的 jittered projection 与 viewRotation。

pending 保留原 source 引用；重复消费或晚撤销会在 Resolve 前使 coverage UNAVAILABLE，停用该帧 RGB 历史修正并保留完整当前透明颜色，失效 surface 不发布到历史。FEATURE shader 同时认证 fragment 与包含 helper 的完整展开 vertex 源码，规则见 [着色器文档](../shaders/README.md#3-源码宏与-include)。BORDER 已采集实际成功上传的 16 个顶点、mesh generation、dynamic transform/color 和毫秒相位；按原 draw 的 firstIndex 选择真实 side，保持每条原 draw 执行一次。

初次整合的 JVM `compileKotlin compileJava jar` 已通过，记录为 `build/reports/workgraph/abi21-feature-final-build2.log`；包检查 `abi21-feature-package-check.log` 确认 10 项运行资源与 36 项关键 class、BORDER mixin 登记和内嵌 ABI21 DLL 一致。此后只读审阅确认 FEATURE 只比较主纹理，漏掉 tint、overlay、lightmap、法线/方向光和 ColorModulator；本轮已补齐真实顶点、绑定资源与 CPU uniform 的着色身份。细节见 `build/reports/workgraph/abi21-feature-implementation.md` 与 `feature-records-review.md`。

着色身份修正已完成：属性来自实际 CPU MeshData，Normal 按原版 `RGBA8_SNORM` 认证而只读取 xyz；动态颜色取成功的 Transform 序列化，方向光仅在 Checked upload 录制成功后登记，绑定按真实 buffer/offset/range 查询。覆写和关闭撤销相交范围，登记按 buffer 身份与 TreeMap 分桶，普通 staging/geometry 写入立即返回。ENTITY/ITEM/PARTICLE 按真实 shader/defines 选择 overlay、lightmap 与方向光；缺失或改变只撤销材质 previous。UV0 变化且顶点着色不均匀时拒绝材质历史；双面 ENTITY 的几何 previousUv 映射反转、退化或 quad 无效时也撤销材质历史，防止 front/back 颜色串用。

着色身份认证的源码修订已完成：capture 缓存 schema 升为 `/5`，shader ABI 仍为 5；ENTITY、ITEM、PARTICLE 现在认证完整展开的 vertex source 闭包，移除 compiler/pipeline define 前缀后再计算规范化 SHA-256，helper 函数变化会拒绝材质历史。terrain 记录同时纳入原版 `Sampler2` lightmap 的 view、sampler 和 generation；可靠 lightmap 缺失时只撤销材质 previous，保留几何 previous。`compileKotlin compileJava jar` 已在修复 `checkD3D12Dependencies` 配置缓存引用后通过，日志为 `build/reports/workgraph/workgraph-jvm-final-build.log`；现有包检查 `build/reports/workgraph/workgraph-jvm-package-check.log` 通过，JAR 为 `91,093,145` 字节，SHA-256 为 `8A04EC37C42070CCF38C34D63FB507C6AA8AF5C823FF5DB14B0F3FC471B32185`，嵌入 ABI21 Release DLL 为 `519BAF287C62C4900B7B75160A9A952D1455B0B593E1E3B856F1DD0A2E37A2D6`。包内 `/5` capture、terrain lightmap key、FEATURE shading class、五个 uniform mixin 与 10 项运行资源均已核对；没有 probes/selftest。新增 mixin 字节码无 lambda 辅助类，记录为 `abi21-feature-uniform-mixin-bytecode.txt`。

当前运行窗口仍是旧 ABI21 terrain 客户端；用户已在 DLAA、FG 关闭、正常运行条件下确认水、玻璃和云的固定残影消失。运行结果覆盖当前已加载的 terrain 路径，FEATURE/BORDER `/5` capture 或 terrain lightmap identity 尚未在该窗口加载；当前窗口保持不动。原版 sorted quad IB 已接入实际上传映射并进入配套包，surface 身份保持原 source ordinal/triangle role，metadata 按实际 GPU primitive 顺序排列。TextureMat/glint/dissolve、任意 custom IB、未知 extractor 与多实例的完整记录，TRANSMITTANCE alpha/coverage AA 和多贡献 FG guides 继续按主计划实施。

本轮 CPU 热路径优化把编译产物固定的 terrain uniform binding 列表从每批 `buildBindings().filter` 改成 pipeline 生命周期内的只读缓存；逐帧 buffer slice、handle 和数组仍由批次独立持有。`D3D12TerrainWorkGraph.prepare` 每个有效 terrain command batch 不再扫描固定布局或创建临时 uniform 列表，未做运行时 FPS 结论。旧 OIT contribution/cloud guide 附件的 JVM 延迟分配已接入源码：成功进入 records 路径时不创建 legacy attachments，只有 opaque capture 不可用、确实走旧 guide 路径时才在 `executeOit` 开头创建并清零；该改动尚未在客户端动态验证。

native OIT 的历史与临时资源所有权已拆开：`HistoryResources` 只保留后续历史匹配需要的 records/pixels/sources/primitives/terrain 和身份数据，allocator、previous、constants、descriptor heap、metadata、程序对象以及冻结的 opaque/resolve/VBO 租约仍由当前 `Frame` 和真实 recording/fence lease 持有。成功 Present 后 pending/presented 只保存历史资源，取消、提交未知和超时沿用原有安全退休路径。opaque copies、未记录覆盖、status 和 corrected transparency 按真实 `ID3D12Resource*` 与 `GetResourceAllocationInfo` 计入 512 MiB 全局预算并去重；原版 accumulation、depth、bounds、coeff 不重复计费，超预算返回现有 typed `OVER_BUDGET`，不等待、不降采样、不发布半初始化历史。实现报告为 `build/reports/workgraph/oit-native-ownership-result.md`，native Release 构建日志为 `build/reports/workgraph/oit-native-ownership-build.log`，0 警告、0 错误。

最新一轮 `compileKotlin compileJava jar` 已通过。正式 JAR `build/libs/dxr_mc-2630-0.6.0-alpha.jar` 为 `91,121,785` 字节，SHA256 `0DA956DD8182F9904B1040FE427D5B2536BE593F08EA5D8DDDCEC592957A5C58`；包检查确认 44 个关键 class、10 项运行资源、无 probe/test/SelfTest，嵌入的 Release DLL 为 `4,145,664` 字节，SHA256 `324E7549796043E33F5D8E89F1B87CB3ECDFD5FD6CBD8D756FA723CE76D871D7`。排序索引接线、surface 状态失效修正和此前的 OIT/`/5` capture、lightmap 身份、legacy attachment 延迟分配均已进入包。

当前运行窗口仍未替换为这个新包；用户已在旧 ABI21 terrain 客户端的 DLAA、FG 关闭、正常运行条件下确认水、玻璃和云的固定残影消失。新包动态行为尚未确认，当前窗口保持不动。

### 地形节点融合与驻留编码

三节点候选把 `MeshletCull/EmitVisible` 合并到 `MeshletRange`，图为 `SectionEntry → SectionCull → MeshletRange`。64 线程组沿用原剔除数学，wave 内压紧、组内汇总，每个非空候选组一次全局计数预留；尾线程不读 atlas，全部线程参加三次组同步。入口、root、descriptor/section 格式、容量与 overflow 契约不变。主代理用实际 DXC `lib_6_9 -HV 2021 -WX` 编译通过，独立审查见 `build/reports/workgraph/terrain-node-fusion-review.md`。最终工程决定生产保留五节点，候选保存在 `src/probes/terrain_nodes/fused-candidate.hlsl`，不进入发布资源。

`src/probes/terrain_nodes/` 在 RTX 5070 Laptop 的真实波宽 32 上完成旧新图共 80 次 dispatch：覆盖 cull/backface 开关、0/1/63/64/65/129、多个 section、混合方向/无界、恰满和溢出；检查 count/overflow、section 表、无重复 visible 集合与 canary。WaveSize(32/64) 均通过离线编译，硬件只执行实际波宽；生产 initialize/finalize compute 已编译，finalize 的硬件执行不在本次范围。

独立 timestamp 各预热一次、交替旧新各 16 次，仅量 graph dispatch 和其 UAV barrier。65536 全可见、每 64 项存活一项、1024 个单项 section 的旧→新中位分别为 122.720→99.840、117.712→99.584、52.320→41.488 µs。前两类新图出现周期性长尾，最高 8.421/8.376 ms，原因尚未定位；保留原始 `build/reports/workgraph/terrain-nodes/20261115-161129/timings.csv`，这些结果不提供游戏帧率结论。当前窗口未更换新 shader。

一次针对性的逐轮提交对照保留相同 shader，每轮旧新各一次后 submit/wait 再继续。dense 旧→新的中位/平均/最大为 122.400/129.396/243.424 → 102.928/109.004/200.736 µs；sparse 为 112.800/115.674/128.576 → 101.216/102.586/116.864 µs，64 个样本均低于 500 µs。原单列表 dense/sparse 新图均值却达到 662.860/884.436 µs，原值为 124.680/118.392 µs。逐轮等待消除了本次长尾，根因仍缺 GPU 调度证据；生产不能为隐藏长尾增加等待，故本轮撤下融合。对照记录在 `terrain-nodes/20261115-162259/`，完整报告为 `build/reports/workgraph/terrain-node-fusion-gpu-check.md`。

`D3D12MeshletResidency` 的 replacement 将总字节与 candidate offset 计算合为单次遍历，upload 复用现有字节数；append 同样不再重复求和。候选仅在 checked copy 成功后发布，弱引用身份、失败回滚和 fence 退休保持原契约。该改动只减少 CPU 列表遍历，未测得 CPU 帧时间；配套构建记录见下一节。

TRANSMITTANCE 的完整 coverage 方案采用主计划 §5.5.1 的四子样本 tile 流式处理。原先 `oit-transmittance-implementation-design.md` 的全局 sample pool 只适用于稀疏历史，不能用其容量或溢出行为承担完整当帧覆盖。terrain draw packet、资源内容冻结和三阶段 tile consumer 分别记录进度，packet 字段不可变不视作内容已冻结。

### terrain draw packet 与配套构建

native device 保存真实 viewport/scissor 和成功上传的 push constants；命令列表 Reset 及 foreign graphics 失效入口撤销有效性，单纯根切换保留 raster 状态并撤销 push。surface 的 backbuffer blit、纹理 blit 和 sharpen 现在也通过同一失效入口，后续 terrain packet 不会读取这些直接命令遗留的图形状态。terrain prepare 保留 globals/atlas 的实际长度。仅 OIT capture draw 创建不可变 packet，包含真实 frame/producer/scope、section 几何/材质版本、pipeline/root、绑定和精确资源范围；原 ExecuteIndirect 直接使用 packet 的 VBO/globals/atlas/visible/arguments/signature，仍执行一次。普通 terrain 不创建 packet，capture 也不重复 pushDescriptors 或重放已经生效的 raster 状态。

当前提交的 recording lease 与临时 OIT Frame 持有同一 packet，program/batch/PSO、普通 binding 对象与资源寿命均被保留；packet 不进入 HistoryResources，没有 Frame 强引用环。内容状态明确为 `OriginalDrawOnly`，没有冻结提交内可变纹理/DEFAULT buffer，也没有禁止原版合法写入；内容冻结、三阶段 packet 和四子样本 tile 执行仍需实际接线。末端 surface blit 在直接改 viewport、root 或 attachment 前已调用统一失效入口，后续记录不会读取遗留 graphics 状态。实施报告为 `build/reports/workgraph/transmittance-draw-packet-result.md`。

surface 状态失效修正后的 native Release/x64 构建通过，0 警告、0 错误；该 DLL 已在最新 JVM `jar` 中重新封装，包内 hash 与 Release 输出一致。

上一版 surface 修正前的包 hash 仍保留在历史记录中；当前包检查确认 44 个关键 class、10 项运行资源齐全，发布包内 terrain shader 仍为五节点，独立融合候选未被打包，无 probe/test/SelfTest。

本节配套产物的大小与哈希统一见[当前构建与运行](#当前构建与运行)，其中包含 FEATURE/BORDER `/5`、terrain lightmap、legacy attachment 延迟分配、OIT 历史/临时资源拆分，以及本节 packet 和驻留编码改动。生产 shader 保持五节点，融合候选不进入包。当前隔离客户端与其资源没有替换，用户“残影消失”的动态确认继续对应旧 ABI21 terrain 版本；配套产物尚未在该客户端运行。

### ABI20 OIT 存储与旁表容量（2026-11-15）

在 ABI19 合成顺序上压紧 OIT 存储：record 192 B → 144 B，pixel 状态 16 B → 8 B。删除 Resolve 从未读取的字段，保留所有参与计算的 FP32 值及原 finite 校验；records 262144、每像素 32、metadata 配额和 512 MiB 上限保持不变。4K 四个完整 Frame 加 program 的额度从 789.25 MiB 降至 488.25 MiB，剩余 23.75 MiB 给 draw 常量页。更多 pending、其他 context 与重叠 program 仍受共享预算限制，未插入隐藏等待。

metadata source/primitive/previous/section 容量不足在完整校验后返回专用 -7，不写入旁表。JVM 在 capture PSO 绑定前处理该拒绝，原 draw 使用当帧颜色与未记录覆盖；分配失败/共享预算不足的 -3、契约和绑定失败继续中止当前帧，不重放 draw。

逐 producer 的真实 records 状态保持明确：云/雨雪已消费实际 BindDraw；terrain WG 已有 capture shader，但生产 draw 尚未绑定 records，需把真实 section primitive/previous position与UV随几何增量交付；FEATURE 聚合实体、持物和透明粒子，需将surface/material身份与previous随真实staged draw范围保留；边界需记录真实rebuild四边形、dynamic transform与3秒UV相位。透明粒子沿原FEATURE scope，不新增伪producer；现有opaque particle motion明确跳过translucent，不能冒充其previous。未完成的来源继续保留真实当帧透明颜色，TRANSMITTANCE alpha/coverage AA和多贡献FG guides仍属于未完成目标。

独立 `src/probes/oit_history/run.ps1` 通过原 7 项 RGB history 检查和新增 packed Append 并发检查，日志为 `build/reports/workgraph/oit-history/20261115-104537/gpu.log`。该程序读取的是独立小纹理及 buffers，不采集运行中游戏；它不承担动态画质结论，后续 ABI21 terrain 客户端已由用户确认固定留屏消失。

最终 native Release/x64 为 `4,090,880` 字节，SHA256 `8A35C1FE2309FD084AD39667692E984D37B1D40351D9C169D752A37BE2CF67EE`。配套 JVM `compileKotlin compileJava jar` 通过，日志为 `build/reports/workgraph/abi20-jvm-build-final.log`；正式 JAR 位于 `build/reports/workgraph/abi15-build/libs/dxr_mc-2630-0.6.0-alpha.jar`，为 `90,919,216` 字节，SHA256 `987F2F443126C4B4930CD7FDE1B696DC7453F3E2ED8BF99CE8170D121B998FED`。包检查确认内嵌 DLL 与 Release 一致，10 项运行资源和 26 项对应 class 齐全，mixin 配置正确，未混入 probes/test/SelfTest，日志为 `build/reports/workgraph/abi20-package-check.log`。构建目录中的 `abi15-build` 为沿用的目录名，当前包使用顶层 ABI20 与 shader ABI4；旧 ABI14 客户端尚未退出，新包尚未加载。

### ABI19 OIT 合成与晚期世界路由（2026-11-15）

当前生产接线在原版 OIT Composite 前冻结不透明颜色、深度和运动，执行一次 OIT records Resolve V3 与不透明重建；超分/AA 输出写入独立 SceneDisplay，随后把 corrected transparency 作为唯一透明颜色合成。SeeThrough、AlwaysOnTop、AfterLevel 和 post effect 仍按原顺序绘制，最终世界完成后才发布 final depth/motion、GUI 和 Present。首帧或 native consumer 0 保留当前栅格；实际产生 temporal display 的帧使用明确的 render↔display jitter 偏移，晚期世界几何绑定同一帧的无抖 projection，取消或退休时恢复原 projection。

`mcdr_world_output_input` 已升级为 schema 3、360 字节，`sourceStage` 区分 `OPAQUE_BEFORE_COMPOSITE` 与 `COMPLETE_WORLD`；顶层 ABI 为 19。OIT Resolve V3 使用 192 字节 capture record、RGBA16_FLOAT corrected transparency 和 R32_UINT status，unknown、overflow、invalid 与空贡献保留当帧透明颜色，历史只在实际 Present 成功后提交。TRANSMITTANCE 的真实 alpha/coverage 重建以及未认证 producer 的完整透明历史仍保持明确缺口，当前记录不能据此宣称全部透明效果已完成。

本轮 JVM `compileKotlin compileJava jar` 通过，日志为 `build/reports/workgraph/oit-abi19-jvm-build.log`；native ABI19 Release/x64 产物由配套构建提供，Gradle 最终重新打包日志为 `oit-abi19-final-jar.log`。包内 DLL 为 `4,090,368` 字节，SHA256 `E792D9FDF8A27AF36BABA457935DEFAA00E1C3E13F05A8A9BCF972127A742F69`，与 native Release 完全一致；正式 JAR 为 `90,917,608` 字节，SHA256 `06EAAE8E2CB1D53A60BA83B52BBCE3A33FEE76F4AEC809728E23AAF3919212D8`。六个 Naga→DXC SM6.9 的 composite/depth/screenquad 变体通过离线检查，包检查确认所需运行库和对应 class、无探针或自检类，日志为 `oit-abi19-package-check.log`。固定留屏尚待用户在正常帧率下动态确认。

当前 OIT 共享预算仍固定为 512 MiB：4K 下三个基础 Frame 已约 588.94 MiB，native 以 `OVER_BUDGET` 中止该帧，不等待未提交 fence、不发布半初始化 token，也不替换透明历史；1440p 四个基础 Frame 约 504 MiB，仅剩约 4 MiB 常量页。边界证据见 `D:/Projects/MinecraftMod/DXRMC/rebuild/MCDR/docs/oit-record-budget-native-status.md`。

### ABI17 间接绘制与 OIT/motion 附件

普通 indexed/non-indexed indirect draw 现在与有序间接绘制共用 motion 作用域，使用已有 neutral bindings；地形仍由独立 Work Graph 路径提交。间接命令的实际范围留在 GPU 消费，未凭首条命令构造 previous。地形新增 16/32/64 档 OIT guide + motion 组合 PSO，各变体使用独立 descriptor，附件顺序固定为原 pass、OIT guide、motion；缺组合变体明确失败。关闭与编译失败清理包含新 PSO。

实际生产 HLSL 检查发现 OIT 包装入口允许缩进，motion 入口解析却曾只匹配顶格函数；现已允许行首水平空白，普通 OIT+motion 与 terrain 组合均沿用这条规则。修正后原 PS、guide PS、组合 PS 及 16/32/64 三档 MS 共六份着色器通过 DXC SM6.9 编译，输出反射确认 `SV_Target0 → 1 → 2`、原目标签名与 discard 保留，三档 mesh 输出与组合 PS 输入相符。材料位于 `build/reports/workgraph/terrain-oit-motion-validation/`；没有启动游戏或采集。

native 切换 additional 附件保留已选择的 motion，解除 motion 注册保留 additional，并拒绝两者资源别名。mesh PSO 与普通 graphics PSO 共用颜色写掩码的判定规则，Work Graph 仅为实际写颜色的管线登记原 pass 颜色已写。world motion 仍要求原颜色、深度与注册资源匹配，独立 OIT accumulate 不会自动获得该附件。

Release/x64 构建通过，0 警告、0 错误；JVM `compileKotlin compileJava jar` 在既有 `abi15-build.init.gradle` 独立目录构建通过，依赖方向检查随编译执行，使用机器现有 VS2026 和 GraalVM 25.1。日志为 `build/reports/workgraph/oit-motion-native-build.log`、`oit-motion-combined-jvm-build.log`。本轮包内 DLL 与 native 输出一致，四个修改 class 与本次编译产物一致，发布 core 不含探针或自检类。当前运行窗口仍为 ABI14 + DLAA M，没有替换为这批 ABI17 产物。

本轮核对确认：旧 motion64 序列来自 ABI13/K，会话已结束；当前窗口的 Streamline 日志明确创建 DLAA App hint M。普通透明的 invalid coverage 尚无 DLAA 逐像素消费者，records 也尚无 RGB history 消费者；上述管线修正不构成固定残影已解决的结论。当前 M 的动态表现交由用户在无采集状态下观察，不再运行连续全图读回。

### ABI17 未记录透明片元覆盖

Resolve 已切换为 schema 2／`mcdr_dx12OitResolveRecordsV2`，x64 描述为 104 字节，必需输入同尺寸 R8_UNORM 未记录覆盖图。其余 OIT CPU 描述仍为 schema 1，capture shader ABI 2 与 prelude hash 不变。旧 Resolve V1 类型及导出已删除，JVM 与 DLL 配套使用；完整 current transparency 保持只读。

普通 IA 的 ORIGINAL + ACCUMULATE 编译独立 UNRECORDED PSO 和宽线 quad 变体，原 fragment main 执行一次，保留原颜色、discard 和深度，所有存活片元附加写覆盖 1，包含 q 为零与 additive 零 alpha。R8/MAX 目标定义由 OIT 域统一拥有。backend 按实际 route 选择显式 PSO；capture draw 临时解除覆盖附件，真实 draw 一次后恢复，异常保留原错误与 suppressed。仅实际非空 draw 事务成功才登记缺失；WG、flipY 或没有覆盖 PSO 时登记 UNAVAILABLE，不在 setPipeline 时推测绘制。

OIT 域在 Begin 懒分配覆盖资源并每帧清零，由原 encoder 关闭时释放。Resolve 收到非零覆盖就把对应像素标为 unknown；UNAVAILABLE 使用唯一 ABI 掩码 1，让整帧包含 EMPTY 像素均保留 unknown。native compute 的九槽 descriptor heap 保留原 capture 六槽，追加 t3 current、t4 coverage、u3 status；初始化不读取两个 Resolve 输入，compact 声明真实读取。status 禁止与 Resolve 输入或 frozen opaque 资源别名，纹理由 frame lease 保留，descriptor 页和租约容器进入共享预算。

native Release/x64 与 JVM `compileKotlin compileJava jar` 均通过，依赖方向检查随编译执行。日志为 `build/reports/workgraph/oit-unrecorded-abi17-native-build.log`、`oit-unrecorded-abi17-jvm-build.log` 和 `oit-unrecorded-abi17-coverage-order-jvm-jar.log`。覆盖登记修复后重新打包：`build/reports/workgraph/raster-oit-unrecorded-abi17-E27B6163.jar`，90,853,396 字节，SHA256 `E27B6163CEE026E0EA21B8FB2311A4834565D64F9222BB7336C054CABC5D19CA`；内嵌 DLL 与 Release 输出一致，为 4,067,840 字节，SHA256 `2533C5579B23B38D4B61C9CFB15D1F7E23A0F7244F00D08836D3EF181176A8FD`。原 26 个运行时 class、本批另 13 个 class、11 个 OIT/capture 导出、运行库及许可证已核对；没有旧 Resolve V1 导出，包内无探针、自检或测试替身。结果为 `oit-unrecorded-abi17-coverage-order-package-check.log`、`oit-unrecorded-abi17-coverage-order-extra-check.json`。

七类真实 ACCUMULATE 的 VS、原 PS 和 UNRECORDED PS 共 21 份 CPU DXC 编译及七对 IA 接口检查通过；两个 native compute 入口通过 `cs_6_9 -WX`，反射确认 compact 读取 t4，初始化不读取 current/coverage。未新增测试，未运行 GPU、采集、重放或游戏。主循环仍未调用 Begin/Resolve，新 records 池保持未启用；逐 surface RGB history、TRANSMITTANCE coverage 重建、其余 producer 与 world-v3 仍未完成。固定留屏问题保持未解决，无性能提升结论。

### ABI16 透明记录基础实现（2026-11-15）

完整透明路径的确定要求集中在[计划 §5.5](vanilla-work-graph-plan.md#55-云与-oit-的贡献输入)：保存最终 q 和未加权材质事实、按 surface 维护 history、使用当前权重合成、独立覆盖/透射重建、opaque/final 运动阶段与真实晚期顺序。来源、shader 与 native 布局的具体调查保留在 `build/reports/workgraph/oit-surface-producer-design.md`、`oit-contribution-shader-design.md`、`oit-native-records-design.md`。地形的 chunk/sample/fog 顺序、天气纵向 UV 和时间折返条件已按实际 26.3.0.95 shader/字节码修正。

生产编译链已创建独立 capture 产物与 PSO，覆盖 normal/flipY、宽线 quad 和 terrain 16/32/64。原 fragment main 只执行一次，最终 q 和私有 GLSL metadata 经 Naga 保留，外层仅输出原 `SV_Target0`，并调用 native 唯一 prelude 的 Append。七类真实原版管线共 36 份生成 shader 通过 DXC，14 对 IA VS/PS 的 semantic/index/mask/register 一致；已修正 PrimitiveID 挤占 instance 输入寄存器的问题。编译材料和结果在 `build/reports/workgraph/oit-capture-production/`，该验证仅使用 CPU 编译器，没有执行 GPU。

cloud/weather 的真实 owner、展开 cell/face、四角、随机返回和 UV 相位已进入不可变 surface 描述；geometry/material previous 分开判断，只提交实际成功 presented 的前帧。WeatherEffectRendererTemporalMixin 已登记。完整 surface 描述仅由活动 draw 的消费者显式请求，成功消费后才进入待提交表；旧云 guide 每 producer 只保存一份 preparation，云 mesh 重建时冻结一次。天气复用具名采集存储，Phase/Quad/Vertex、sampler 和纹理保留推迟到实际请求时创建。previous weather texture 仅供 CPU 身份比较，不重新绑定旧 GPU view。`OitSurfaceRecords` 按真实 quad 索引顺序构造 primitive 和 previous triangle；云保留旧 face/cell/inside 顺序，天气保留旧朝向与当前世界 Y，并展开真实 UV 时间周期。

capture shader ABI 2 已接入独立 geometry/material previous：PS 使用透视 `SV_Barycentrics` 插值旧 clip，UV 材质通过旧 UV 平面求对应位置；两组 UV/深度和有效位独立保存。previous 使用成功呈现前帧的 jittered raster projection，不混入 SDK 去 jitter motion。设备一次查询 `BarycentricsSupported`，capture graphics/mesh PSO 与 mesh root 在不支持时明确失败。GPU 私有 record 使用原预留字段保持 128 字节，CPU ABI/schema 未改变。36 份实际 HLSL、14 对 IA 接口和 6 对 terrain 接口通过 CPU DXC 检查，材料见 `oit-capture-previous-production/`。

`OitSurfaceRecordCapture` 已连接普通 indexed draw 的 capture 作用域，目前认证原版 cloud/weather。准备时核对帧身份、scope serial、profile/generation、PSO 和实际 index/instance 范围；真实录制才发布 metadata，Bind/Draw/Unbind 成功后向外层事务登记消费，附件/PSO 恢复失败不提交。backend 只在正常世界方向调用，捕获期间解除旧 guide 附件，结束后恢复附件、PSO、descriptor dirty 与 VRS。native Unbind 要求 lease 被一次真实 draw 消费，空 draw 清绑定后中止帧。显示帧在 reset 前退休 records，中止释放失败仍执行统一 finally 并保留原异常。Begin 从当帧冻结且已发布的 program 读取 generation，调用方只提供 limits，已删除允许调用方拼代次的临时 `OitRecordPlan`。

native 新增独立 OIT records 域、专用 graphics/mesh roots、1×1 VRS 作用域、scheduler UAV 访问、预算约束和录制租约；GPU initialize/compact 真实读取 records、完整 current 透明纹理并输出逐像素索引范围与状态。普通 draw 无 capture 时直接返回；实际 capture 保存该 command context 的 SRI、rate 和 combiners，解绑图像并切为 1×1，退出后恢复，图像由 recording lease 保活。JVM 的 `OitRecordRows/Frames` 实现 CPU 语义旁表到 FFM 的打包及生命周期，八类 CPU 布局加入原 ABI 校验；关闭走 context 控制入口，不为退休已提交帧重开 command list。`ReconstructionFrameInputs.captureOpaque` 可复制独立 opaque color/depth，并在原 raw 附件被后续绘制修改前复用唯一 `D3D12Velocity` 数学生成 opaque motion；OPAQUE/FINAL 使用独立缓存与目标。这些入口当前均未挂入正常主循环。

剩余确定工作是主循环冻结 opaque 并 Begin/Seal/Resolve、terrain/feature/particle/border 的实际 draw 接线与未认证区域标记、旧 record 的 owner/depth/footprint/coverage 对应检查、逐 surface RGB history、TRANSMITTANCE alpha/coverage 重建，以及保留 SeeThrough/AlwaysOnTop/AfterLevel/post 顺序的新世界输出。compact 只完成 records 整理；当前主循环未调用 Begin/Resolve，draw capture 为空，正常绘制未启用新 records 池。正常世界输出继续读取完整 current color 的 schema 2。

上一批 shader ABI 1 的 native Release/x64 与 JVM `compileKotlin compileJava jar` 均已通过，依赖方向检查随编译执行；日志为 `build/reports/workgraph/oit-records-abi16-native-build.log` 和 `oit-records-abi16-jvm-build.log`。使用本机 VS2026、GraalVM 25.1、现有 SDK 依赖及独立 JVM 构建目录。对应保留包为 `build/reports/workgraph/raster-oit-records-abi16-D01B900A.jar`，90,809,412 字节，SHA256 `D01B900AC691C3AE8F78B66D07DFA7F6CC192083255F6335C9A3CFC9AC535947`；内嵌 DLL 为 4,059,136 字节，与当次 native 输出一致，SHA256 `79E85552C4A3EEEEEF424CDCEF8BA2E73CE16A9103BEA00D9D443F186C3EE33A`。11 个关键 class 与当次编译输出一致，天气 mixin 已登记，9 项运行库/许可证资源齐全，包内无探针或自检类；记录为 `oit-records-abi16-package-check.json`。

shader ABI 2 的配套 native Release/x64 与最终 JVM 构建也已通过，日志为 `oit-previous-shader2-abi16-native-build.log` 和 `oit-previous-shader2-abi16-jvm-final-build.log`。保留包为 `build/reports/workgraph/raster-oit-previous-shader2-abi16-011D87D4.jar`，90,843,243 字节，SHA256 `011D87D466C737BD1EE125F4E550C45390CCAE8905913F277BC66F425C0DEC69`；内嵌 DLL 与 Release 输出一致，为 4,066,816 字节，SHA256 `04A83F42DE1946B58EC6BAC659E1E36A136CF0163CA19649C73981C90174D2E1`。22 个本批关键 class 与编译输出一致，原 26 个运行时 class、天气 mixin 和运行库/许可证也已检查，包内无探针、自检类或已删除的临时 plan；结果为 `oit-previous-shader2-abi16-package-check.json`。11 个 OIT/capture C 导出与正式 DLL 一致。compiler capture 与 frame programs 都使用同一次 compiler reload generation；Begin 的生产调用者尚未接入，不把准备入口记为已启用。

固定留屏仍未解决；本轮未重新采集、回放、启动或操作游戏。连续全图采集已停用，现有数据保留作离线分析，用户反馈的约 3 FPS 采集开销不能用于正常帧率的动态确认。本轮消除未消费 surface 的分配与普通 draw 的 VRS 恢复开销，没有新的 FPS 测量。

### ABI15 OIT 来源与运动采集（2026-11-15）

本轮交付实际 OIT 来源、stage 和资源绑定的采集与消费，业务规则见[云/OIT 输入调查](#云oit-输入调查2026-11-15)。四个新 Kotlin mixin 已登记，八个原版/NeoForge 调用目标和 frontend/provider 签名已对照实际 26.3.0.95 JAR；已有 draw 包装继续作为唯一入口。创建 pass 后若事实采集失败，会关闭尚未交还的 pass 再抛出原错误。另修复 WORLD item 白名单漏掉 `HEAD`，使贴眼望远镜进入已有 `eye` owner 的运动采集；保留两处合法 Mixin accessor cast。

`compileKotlin compileJava jar` 及依赖方向检查通过，最终日志为 `build/reports/workgraph/raster-oit-producers-jvm-final.log`。构建沿用 ABI15 隔离目录、本机 GraalVM 25.1 与现有 native Release，本轮没有修改 native。保留产物为 `build/reports/workgraph/raster-oit-producers-abi15-5D0891D3.jar`，90,642,679 字节，SHA256 `5D0891D3C33658790EFC03188E06F67AC0BF3081941D59A2BB9A383434C33528`。内嵌 DLL 与既有 ABI15 Release 一致；26 个关键 class 与本轮编译输出一致，四个新增 mixin 已进入包内配置，运行库与许可证齐全，没有探针或自检类，记录为 `raster-oit-producers-package-check.log`。编译字节码确认普通 draw 在查表失败分支直接调用原绘制，OIT 闭包仅在已登记 pass 分支创建；没有 FPS 收益测量。

本轮没有启动、关闭或操作游戏，没有截图、全图读回或新增测试。固定留屏继续记为未解决，当前运行窗口仍保留原 ABI14 会话。完整多贡献 records、透明独立历史与新接线的运行确认尚未完成；此包只补足来源事实，FG 的 unavailable 边界保留。既有日志和本机 SDK 契约复核未发现新的宿主必填参数遗漏，详细依据为 `build/reports/workgraph/dlaa-native-contract-followup.md`，没有用可选 bias/frame-time 参数替代缺失的贡献历史。

### ABI15 同代重载发布（2026-11-15）

frameprep/display 预热与 JVM frontend/backend 的发布契约见 [G 重载与预热边界](#g-重载与预热边界)。ABI15 新增共同候选 prepare/commit/abort，世界与显示输入仍为 schema2。顶点采集同时合并三坐标的容量检查，并复用一次有限值扫描结果；历史数组仍独立持有，没有性能收益数据。

配套 native Release/x64 和 JVM `compileKotlin compileJava jar` 已通过，依赖方向检查随编译执行。当前客户端锁住默认输出目录的 Minecraft JAR，本轮使用 `build/reports/workgraph/abi15-build.init.gradle` 输出到独立构建目录；构建命令另外传入 `-PmcdrNativeRoot=D:/Projects/MinecraftMod/DXRMC/rebuild/MCDR` 和 `-PmcdrDependencyRoot=D:/Projects/MinecraftMod/DXRMC/MCDR`，使用本机 GraalVM 25.1。最终日志为 `native-program-transaction-build.log`、`raster-program-transaction-isolated-jvm-final.log`；这轮未重启或操作游戏、未采集画面。

保留产物为 `build/reports/workgraph/raster-program-transaction-abi15-6BD8846F.jar`，90,591,180 字节，SHA256 `6BD8846F2FF33984CF4793358659BEC494254A654868A0CEA53B030C58C35C1A`。内嵌 DLL 为 3,991,552 字节，与本次 native Release 输出一致，SHA256 `E4CB779006DA1D2819039875FA18182E0BE99DED575B598401185A04FF49D93C`。包中运行库与许可证齐全，16 个关键 class 与隔离编译输出逐字节一致，新增 accessor 已登记，没有探针或自检类；记录为 `raster-program-transaction-package-check.log`。

独立真实设备/context 验证通过：prepare 保留两域旧程序、竞争候选过期后可取消、错误 context 提交不消费句柄、renderpass 返回 Busy、同 fence 重开列表撤销候选、context 关闭后取消安全。独立编译探针已适配显式 ownership transfer，并通过版本撤销与候选清理检查。日志为 `native-program-transaction-probe.log` 和 `raster-program-transaction-compilation-probe.log`。这次没有实测 context 地址恰好复用、在途 GPU 压力、完整 display pending、提交未知或 JVM frontend 安装失败恢复；对应代码与验证范围记录于 `native-program-transaction-result.md`、`frame-programs-independent-review.md`。

ABI15 的游戏重载与动态画面仍待实际运行。透明固定留屏继续按未解决记录，当前游戏现场仍对应前文 ABI14、Preset M；本轮只使用既有输入和日志调查，没有新增采集。

### ABI14 CPU 编码与绑定复用（2026-11-15）

`VanillaFrameWire` 和 `DisplayFrameWire` 使用初始化时从 `MCDRLayout` 推导的具名字段偏移和大小，逐帧、逐层写入不再执行 `groupElement/byteOffset/select`，也不使用字符串 Map 逐字段查询。公共 identity、camera、binding 元数据统一由 `FrameWireLayout` 持有；字段值、schema2、arena 有效期、矩阵顺序与两类相机各自的校验规则保持原契约。

`D3D12CompiledRenderPipeline` 持有不可变 `D3D12BindingLayout`；基础、motion 参数/history 名组合和 OIT 参数名分别缓存对应布局。重复名称通过首 slot 与 primitive 后继链按原顺序赋值，packet 不再重建名称分组。每个 pass 仍独立持有绑定值、descriptor 数组和 override slots；SDK 外部状态失效后的 graphics 恢复、每 draw push constants 与 dirty 顺序保持原逻辑。`ensureListOpen` 在结算 pending submit 和完成回调后查询状态，只有真正执行 Begin 时再检查打开结果，没有增加与 native 并行维护的列表状态。

本机 GraalVM 25.1 的 `compileKotlin compileJava jar` 通过，记录为 `build/reports/workgraph/raster-cpu-layouts-jvm-build.log`；依赖方向检查随编译执行。保留产物为 `build/reports/workgraph/raster-cpu-layouts-abi14-06984A0E.jar`，90,566,339 字节，SHA256 `06984A0E12F55F39B432B6A5E1A45EB8B0158487B4D942F65E0B5C74E29EC496`。8 组受影响产物共 24 个 class 与本轮编译结果一致，包内无采集或探针类；native DLL 与下节 ABI14 产物相同，未重建 native。本轮没有启动客户端或采集性能；旧 JFR 只用于选择当前仍存在的重复计算路径，没有本轮帧时间或 FPS 收益结论。

### ABI14 云候选、资源版本与 WG 生命周期（2026-11-15）

本轮使用已有 VS2026 完成 native Release/x64 构建；已有 GraalVM 25.1 完成 `compileKotlin compileJava jar`，沿用临时 Maven Central 路由和 `--no-configuration-cache`，没有下载 JDK。新增附加 RTV 必需符号，native/JVM ABI 同为 14，其余 schema/结构体布局保持本页图形常量契约。生产云/terrain ACCUMULATE、terrain TRANSMITTANCE 多 MRT 和 resolve VS/PS 已真实转换并通过 DXC SM6.9 编译；检查程序与脚本均保留在生产目录之外。

已运行的配套 JAR 为 90,548,379 字节，SHA256 `C0F05CD9743F100CE70FCB7AD9902AEC88210668BF7288B88037CF3C6FD91B95`；其中嵌套 `illusion-core.jar` 的 `MCDR.dll` 与 native 输出一致，为 3,984,384 字节，SHA256 `DAACFB18344731CE005030421AE681D65A51123598E1C50B0BA26D86AE68E479`。包内包含新 OIT 类和 Kotlin 云 mixin，无 probe/SelfTest 类；OIT class 与该次 Kotlin 编译结果一致。保留产物为 `build/reports/workgraph/raster-oit-abi14-C0F05CD9.jar`。

实际 GPU 的 WG 生命周期检查通过，范围见 G 节。该包保留推荐 M 和上一版 mesh/显示等待优化，并加入云 SR pair、成功资源重载的版本通知及 WG 租约修复。配套隔离客户端于 03:25:45 进入 New World，Kotlin 云 mixin 成功织入，Streamline 明确记录 DLAA 使用 App hint Preset M，FG 关闭，后续持续呈现且没有新增声明错误。运行日志为 `build/reports/workgraph/raster-oit-abi14-common-client.log`。本次未加载采集/性能分析，没有 CPU/GPU 性能结论；M 的动态画质和新云输入仍待用户观察，半透明残留问题保持未确认解决。

固定 resolve PSO 预热调整后的 `compileKotlin compileJava jar` 通过，日志为 `build/reports/workgraph/raster-oit-prewarm-jvm-build.log`；使用已有 GraalVM 25.1、临时 Kotlin 仓库路由及 `-PmcdrDependencyRoot=D:/Projects/MinecraftMod/DXRMC/MCDR`。最新保留包为 `build/reports/workgraph/raster-oit-prewarm-abi14-66F1A072.jar`，90,551,607 字节，SHA256 `66F1A072642A3F343FA14459723282694A4C4BC74D7EA4DCEF331BBBA7772B41`。内嵌 DLL 与上段 native 输出相同，三个修改类与本轮编译产物一致，9 项运行库/许可证资源齐全，包内无采集或探针类。本次仅构建打包，当前客户端仍运行前一包。

连续 64 帧全图读回曾将帧率降至用户报告的 3 FPS，该采集已停止。上述 ABI14 客户端未加载采集。旧序列用于离线数据排查，不作正常游玩帧率或动态画质确认；本轮不再启动连续全图读回。

### ABI13 mesh 编码与显示等待（2026-11-15）

6.1sol `wg_mesh_encoding` 整理 mesh CPU 编码，`wg_fg_wait_review` 整理显示槽与跳过路径，主代理集成并修复 SDK 错误传播。`Batch` 在采集时一次确定唯一几何、layer、meshlet 容量和顶点范围；采集不再使用 `map → any → filterNotNull` 三次遍历。后续 prepare 仍校验当前 buffer/offset，并按实际 atlas/staging 编码，不缓存 GPU slice。驻留全部命中时不创建 missing 列表，绑定句柄/offset/length 单遍写入三个原生数组，保留 draw 和 binding 顺序。

`DisplaySlot` 创建时初始化 14 个固定描述符，同尺寸复用时每候选只创建 6 个外部来源 SRV，原先为 20 次；首次创建与首次消费合计仍为 20 次。Unavailable 或 GUI backdrop 已确定跳过 FG 时，在等 guide fence 前执行原跳过出口；prefix 与 GUI 两个 recording 均持有同一 slot/来源租约，槽 fence 完成后才清租约，仍在使用的槽不能重写。正常候选继续等待准确的同帧 guideFence 并回读 4 字节 validity。生产 JVM 在 FG 关闭时直接返回，不执行这条工作图或回读。

独立复核发现 `invalidateCameraLocked` 曾吞掉 `mcdr_dlss_invalidate_inputs` 失败，导致 skip 报正常返回并丢弃 pending；现由内部 bool 结果传播为 skip 的负返回。`presentSurface` 保留入口检查，并在 `fgBeforePresent` 后再次检查 Streamline 失效状态，防止钩子中产生的清标签/eOff 失败越过检查后继续 Present。未改变公共 ABI、provider 输入或正常候选的有效性规则。

MSVC v145 Release/x64 和本机 GraalVM 25.1 的 `compileKotlin compileJava jar` 均通过，日志为 `build/reports/workgraph/raster-mesh-display-native-build.log` 与 `raster-mesh-display-jvm-build.log`。正式 JAR 为 90,512,335 字节，SHA256 为 `511CFA20F3355C0CB7C882CA5382EC4391B0712921499633B37ED9EE0B30D1A1`；DLL 为 3,977,728 字节，SHA256 为 `12B8BEFB16AFAC0B4D5D862D156F0E19D1BD8C6B63AAE6CD4163045500BAC3CE`。native、staged、resources 与嵌套 core DLL 一致，三份 mesh class 和 DLSS class 与本次编译产物一致，发布 core 不含独立探针类。

现有 atlas 检查已修正过时的第二次 Copy 失败注入，匹配整批一次 Copy 的实际实现，并通过增长、失败发布回滚、按 context/fence 保留和未提交资源清理检查，日志为 `raster-mesh-atlas-check.log`。该独立程序验证 JVM 所有权交互，未运行 GPU；生产代码未增加测试分支。没有本轮游戏运行、动态画质或 CPU/GPU 耗时结论，正常 FG、backdrop、resize 与 SDK 失败仍按真实运行范围记录。

### ABI13 推荐 M 构建

2026-11-15 使用机器已有 GraalVM 25.1 完成 `compileKotlin compileJava jar`，本轮仅更改 JVM 模型推荐项、语言文案与记录，native 保持 ABI13，未重新编译。初次构建遇到 NeoForge Maven 超时及已有配置缓存序列化错误；成功构建通过 `--no-configuration-cache` 和本次临时 init 脚本将 `org.jetbrains.kotlin` 指向 Maven Central，未修改项目仓库配置，也未下载 JDK。完整命令参数和日志保存在 `build/reports/workgraph/dlaa-preset-m-central-build.log` 与同目录 `kotlin-central.init.gradle`。

此版本单独保存在 `build/reports/workgraph/dlaa-preset-m-3D084EE2.jar`，为 90,514,281 字节，SHA256 为 `3D084EE240FFBFAC2F6203E4963C5605BB3DD3891D934E091EE4E9996541A235`。嵌套 core 的 DLSS class 与该次编译结果一致，字节码包含推荐项和显式 M 的 native13 映射；包内中英文分别显示“推荐（M）”和“Recommended (M)”。配套 `MCDR.dll` 为 3,977,728 字节，嵌套 core 与该次 ABI13 输出的 SHA256 均为 `9AC12BBAE9A5BB7E0EE7C304D56E5E4D9FA47B9700425444FE8D3B0D67DC7BCA`；包内无 probe/test/SelfTest 类。原产物核对见 `build/reports/workgraph/dlaa-preset-m-artifact.json`。

连续游戏采集已结束。模型差异已由下述离线输入对照复现；新默认 M 的游戏内动态效果尚待用户确认。

### ABI13 撤销无效输入

ABI13 删除 ABI12 的 current-color bias 输入、资源和贡献历史，恢复 PreparedFrame、世界输出与 mask V2 的布局。native Release/x64 与 JVM `compileKotlin compileJava jar` 已通过，日志为 `_tmp_oit_trace/raster-guides-abi13-native-build.log`、`raster-guides-abi13-jvm-compile.log` 和 `raster-guides-abi13-jvm-build.log`。正式 JAR 为 90,514,122 字节；配套 DLL 为 3,977,728 字节，native、staged 和嵌套 core 的 SHA256 均为 `9AC12BBAE9A5BB7E0EE7C304D56E5E4D9FA47B9700425444FE8D3B0D67DC7BCA`，core 中无 probe/test/SelfTest 类。这次构建撤销无效方案并保留预算修复。配套客户端已进入世界，用户仍确认透明图案留屏，问题未修复。

### ABI13 连续输入与独立重放

`build/reports/workgraph/oit-pixel-trace/motion64-capture-1794672482847/` 保存 64 个连续世界帧的 color/depth/motion/raw/coverage/reconstructionOutput/scene、完整前后相机及对应完成 fence。实际为 DLAA、100%、FG0，SDK 日志明确使用 K，覆盖全部 16 个 jitter phase，64 批均成功完成。连续全图读回和写盘把用户帧率压到约 3 FPS，采集已结束，客户端随后保存退出；这套采集方式不适合让用户操作。数据没有完整静止尾段，不能用它报告静止后的残留时长。

离线数值核对见同级 `motion64-analysis/notes.md`：相邻 63 组 Previous 与上一组 Current 的相机、矩阵和 jitter 一致。OIT、camera-surface 地形与天空的运动方向及像素单位符合相机投影；考虑 RG16_FLOAT 转换精度后，主比较组误差 P95 小于 0.00008 像素。只按位置和朝向有 18 帧静止，其中 10 帧投影/FOV 仍在变化；完整相机静止只有 8 帧。此核对验证记录相机和输出 MV 的一致性，实际颜色、深度的跨帧投影仍单独核对。

`src/probes/dlaa_replay/` 通过公开 ABI、实际 MCDR/Streamline 和隐藏窗口 Present 重放相同 64 帧。`build/reports/workgraph/dlaa-replay/20261115-001539-sequence-final-build-only/` 记录 64 次成功 Present、257 次完成 fence 等待和每帧精确为 1.0 的曝光。从第 28 帧到第 64 帧，新 reset 起始的重放与游戏捕获的 reconstructionOutput 全图 RGB 逐字节相同，采集前历史造成的两路差异已消失。单独把输入 alpha 全设为 255 的对照位于 `20261115-002504-opaque-alpha-build-only/`，其全部 64 帧 RGB 与未修改输入的重放完全相同。两组结果用于排查 SDK 输入与历史，后续模型差异见 [局部颜色与模型对照](#abi13-局部颜色与模型对照)。

此前 `capture-1794669380460/` 的两张图各重复 64 次，只反复提供同一个 jitter 子像素样本，不能据此推断真实 16-phase 输入的历史消退。该实验、连续重放及任何数值分析均不替代用户对留屏、拖影和闪烁的动态观察。

### ABI13 半透明输入与历史滞留定位

`motion64-analysis/color-depth-notes.md` 对实际颜色、深度和相机做了交叉核对：无平移且相机位移超过 10 像素的帧对中，地形正确重投影的 RGB 误差为 0.508/255，屏幕原位误差为 14.575/255。有限深度 OIT 的相容深度比例较低，不能用筛选后的小样本宣称全部透明输入稳定。

第 9→10 帧相机位置、方向和投影一致，部分 OIT 深度仍由 0/0.000x 变成 0.06–0.11。这批高值已对应到原版天气几何：三处像素反投影至同一 `z=12.5` 平面，设备深度拟合残差 P99 为 `5.34e-8`；按 `WeatherEffectRenderer` 的列中心、朝向和真实相机计算，两处样本的预测与实测深度相差约 `7.5e-8`。雨雪列位于半格坐标，纹理 UV 随时间滚动，alpha discard 改变最近可见片元。此变化有正常来源，不作为深度损坏、jitter 错位或 Work Graph 写坏资源的证据。`raw.bin` 来自世界运动附件，raw.x 与最终 depth 相同也没有直接读出 DepthBounds 的四个通道。具体平面统计见 `motion64-analysis/oit-plane-notes.md`。

`motion64-output-analysis/report.md` 记录了重建输出保留旧颜色的具体轨迹：像素 `(301,147)` 在第 46–57 帧连续 12 帧输入固定为 `(141,143,155)`，DLAA 输出从 `(113,114,119)` 缓慢变为 `(122,124,135)`，始终更接近第 43–45 帧旧输入 `(117,120,131)`。这段 raw 为 `(0,0,-4,1)`、depth=0、coverage=1；第 51–57 帧相机位置、朝向及 FOV 固定，MV 小于 0.001 像素。少量非 OIT 像素也保留旧颜色，不能把所有偏差归给一种透明材质。材质身份未采集；12 帧的约 3.25 秒间隔受采集降帧影响，不据此推断正常帧率下的残留时长。

已确认一个输入契约缺口：OIT raw 的 `-4` 和逐像素 coverage=1 表明没有可信的表面运动；Velocity 输出相机重投影后，世界 SR 提交只带运动纹理与整帧分类，DLSS 看不到逐像素的不可用标记。当前 Streamline K 没有公开承诺的局部历史拒绝接口：`motionVectorsInvalidValue` 只服务 SL 补相机运动，当前 `cameraMotionIncluded=true` 时 SR 不读取该值；current-color bias 仅 F 支持，SR transparency mask 保留未用，particle/animated texture 参数也没有当前模型的生产支持承诺。不能追加这些参数或自行约定 NaN/巨大向量后宣称接好了透明历史拒绝。FG 的逐像素 coverage 仍在独立显示路径中使用。

独立重放还做了 SDK jitter 双轴取反对照：只将 Current/Previous 的 jitter 元数据取反，实际 raster 矩阵及全部纹理字节保持原值，刻意隔离 SDK 参数。`motion64-jitter-analysis/negated-output/` 的 64 帧均成功评价和 Present；例如第 57 帧 `(301,147)` 由原 `(122,124,135)` 变成 `(123,125,137)`，仍明显保留旧颜色。该对照没有改善旧色轨迹，生产 jitter 保持原值。连续全图读回已结束；后续分析使用现有输入的独立重放。

### ABI13 局部颜色与模型对照

`build/reports/workgraph/oit-pixel-trace/motion64-uniform-analysis/preset-report.md` 记录解析局部色块和原场景的模型对照。解析输入为 854×480 的静止 1 米平面，相机和几何固定、motion 全零、连续 16 相位 jitter、曝光固定为 1。前 32 帧全图输入 `(117,120,131)`，第 33 帧起只把中心 400×160 的矩形改为 `(141,143,155)`，占画面 15.6%，其余背景保持原色。每条独立运行从 reset 开始，后续保留历史；输入经公开 ABI 上传后回读逐字节核对。

| DLAA 模型 | 第 33 帧中心 RGB | 第 34 帧中心 RGB | 第 64 帧中心 RGB |
| --- | --- | --- | --- |
| J | `(117,120,131)` | `(117,120,131)` | `(118,120,130)` |
| K | `(117,120,131)` | `(117,120,131)` | `(118,120,130)` |
| L | `(140,142,154)` | `(141,143,155)` | `(141,143,155)` |
| M | `(139,141,153)` | `(140,142,154)` | `(141,143,155)` |

当前 DLSS 310.9.1 的 J/K 在局部颜色变化中持续保留旧色，L/M 在两三帧内跟上输入。原游戏 64 帧只换模型重放也有同样差异：像素 `(301,147)` 在第 46–57 帧输入固定为 `(141,143,155)`，第 57 帧 K 为 `(122,124,135)`，L/M 均为 `(141,143,155)`。L/M 的独立 `sl.log` 分别确认 DLAA 使用 App hint Preset L/M。全部透明输入、depth、MV、alpha、相机、jitter 和曝光保持原值。

线性浮点对照见同级 `motion64-linear-analysis/report.md`：相同 prepared publish/evaluate 路径的 RGBA8 控制组与原 K 输出全 64 帧 RGB 一致；sRGB EOTF 转 RGBA32_FLOAT 后，K 仍保留旧色，原始 float 输出也确认偏差，故本轮不接入线性转换。原场景零深度替换为 512 米有限深度同样没有改善。逐帧递增 `historyGeneration` 的隔离对照中，真实像素 `(301,147)` 第 50 帧从 K 的 `(117,120,130)` 改为 `(135,137,149)`，用于确认历史的影响，正式渲染不逐帧 reset。

据此将应用 `default` 明确映射到 M/native13，界面显示“推荐（M）”，显式 J/K/L/M 继续可选；旧数字 `0` 读取为 default，`13` 读取为显式 M。候选准备和冻结帧共用 `DeepLearningSuperSampling.renderPreset`。配置与完整映射见 [DLSS 专项](../superresolution/dlss.md#质量与-preset)。本轮验证了现有输入中的模型差异；新默认项的游戏动态观感与 GPU 耗时尚待用户确认。

模型对照保持相同的主机曝光输入。本地 DLSS 310.6.0 官方指南 §3.9 明确规定 Preset L 始终使用 AutoExposure，J/K 支持外部曝光；M 的内部曝光规则未给出。表中的差异同时包含模型路径与有效曝光策略的差异。FP32/HDR 对照固定曝光为 1，动态 HDR 曝光不在该对照范围；生产 LDR 曝光保持现有契约。

本地 Streamline 源码还存在错误通道缺口：`sl.dlss/dlssEntry.cpp` 忽略 `evaluateFeature` 的失败返回值，尾部仍可返回 `eOk`；公共 NGX 调用层会记录实际失败。已检查的 replay 日志没有这类失败记录。该项单独记录为 SDK 源码问题，调查内容见 `build/reports/workgraph/dlss-remaining-contract-review.md`，当前留屏仍未解决。

OIT 代表深度和多贡献 motion 的独立缺口仍有记录。本轮保持完整透明时域处理，不加入 current-color bias、NaN/巨大 motion 或透明层无历史合成。分层设计见 `build/reports/workgraph/oit-pixel-trace/motion64-analysis/oit-correspondence-design.md`：从真实 OIT `ACCUMULATE` producer 捕获 owner、previous clip/UV 和透射权重，贡献层各自维护历史后按原 wavelet 加权结果相加，最后合成回底图；该设计未在本轮接入。

### ABI12 DLAA 局部历史拒绝

这次尝试已撤销，用户确认 ABI12 仍然固定留屏。ABI11 下原生 NONE/100%、SR/FG 关闭时画面正常，仅 DLAA 时透明图案固定留屏。独立工具在实际提交 fence 后读取两帧输入及重建输出，数据位于 `build/reports/workgraph/oit-pixel-trace/capture-1794667326481/`。例如 `(633,306)` 输入 RGB 从 `(25,32,19)` 变为天空 `(184,210,255)`，DLAA 输出仍由 `(106,128,104)` 变为 `(105,127,107)`，SceneDisplay 延续该颜色；opaque 地形也有明显偏差。

只读对照 AntiAliasingMod 的 `TemporalSceneComposition`、`OrderedContributionReplay`、`EffectHistory` 和 `taa_effect_resolve.fsh`：按实际绘制顺序处理贡献；当帧贡献消失时输出零；来源、拓扑、深度或运动不可信时只用当帧。Illusion 曾尝试保留 26.3 的多附件 OIT 合光，在世界输出前生成 current-color bias，把这些规则传给 DLSS。

当时工作图把不可信来源标记为 U，bias 取当帧 U、前帧同屏 U 和前帧重投影 U 的并集并外扩一像素，历史只保存真实 U。ABI12 为此追加了独立输入和 PreparedFrame 必需位 `0x80`，同时接入 NGX 与 Streamline 参数。

`capture-1794669380460/` 的帧 5306/5307 表明：`(806,315)` 输入由 `(63,81,123)` 变为 `(37,44,43)`，DLAA 却仅由 `(71,89,113)` 变为 `(71,90,112)`，两帧 bias 都为 255。该区域 5×5 输入均值最大通道变化 56.64，输出均值变化 0.52；包含 jitter 的映射后输入仍有明显变化。离线重算第二帧全部 409,920 个 bias 像素与实际输出一致。完整分析见同目录 `bias-deep-analysis-final.txt`；采集覆盖相邻静止相机两帧，持续留屏由用户观察确认。

NVIDIA `DLSS_Programming_Guide_Release.pdf`（2026-03-31，§3.15，PDF 第 40 页）明确说明 current-color bias 目前仅 Preset F 支持，最新模型不应使用。当时 DLAA 默认 Transformer K，该输入无法承担局部拒绝历史。ABI13 撤销相应 SDK 参数、资源、私有贡献历史和 ABI 扩展，恢复 mask V2；保留已有相机、配置、生命周期修复以及本次确认的 Work Graph 预算修复。后续模型对照与推荐项更改见 [局部颜色与模型对照](#abi13-局部颜色与模型对照)。

首次配套启动在真实帧 5 触发 `In-flight Work Graph backing memory exceeds program budget`，见 `_tmp_oit_trace/oit-pixels-abi12-runtime.log`；该会话中止，未采集输出。FramePrep 创建时只校验单实例需求，运行却用同一 64 MiB 限制全部飞行实例。修正为每实例上限 64 MiB、总预算按 context 实际录制槽数量计算；图只在需要新实例时分配真实 minimum。互斥的 TileReconstruct/TileEmpty 输出共享 64 条记录额度，保持线程组的实际最大输出数量。保留飞行实例隔离，未通过共用未退休 backing 绕过预算。

预算修正后的 native Release 与正式 JAR 再构建通过，日志为 `_tmp_oit_trace/raster-guides-abi12-budget-native-build.log` 和 `raster-guides-abi12-budget-jvm-build.log`。正式 JAR 为 90,517,914 字节，配套 DLL 为 3,988,480 字节，SHA256 `908CB389FAE8A650567CED9E81D78FA2F4B0296272316E5D50DC4797F8ECFF52`，native/staged/resources/嵌套 core/实际运行 DLL 一致。`_tmp_oit_trace/oit-pixels-abi12-budget-runtime.log` 确认进入 New World 并持续 Present；驱动报告 mask 图单实例 24,527,280 字节、VRS 图 5,241,608 字节，各图总预算 201,326,592 字节。当前 mask 需要三份时为 73,581,840 字节，原 64 MiB 总预算确实不足。

### ABI11 地形运动接线

opaque/cutout terrain 的 Work Graph 绘制保留原 MS、fragment 颜色、discard 和深度；normal/raw 各有 16/32/64 三档 PSO，按当前 pass 的真实 motion 附件选择。raw 只包装 fragment，不增加 mesh 根绑定或插值量，已认证静态来源写 `CAMERA_SURFACE`，未知来源写 `SURFACE_INVALID`。普通 raster 与 terrain 共用贡献和来源标记规则。mesh 描述符显式追加 raw 标记并升为 ABI11，关闭及编译失败清理覆盖两组 PSO。

ORDERED_INDIRECT 保持原排序和单次间接绘制，实际 draw 经过 raw PSO/descriptor 作用域；间接命令的 base/instance 仍由 GPU 消费，`neutralBindings` 不查询顶点或天体历史，明确上传 `historyValid=0`，不读取 GPU 命令或把首项范围当作整批来源。direct/viewmodel 共用参数上传实现并保留各自的历史选择。

世界生命周期按 `IDLE → WORLD_STARTED → RECORDING → WORLD_READY` 推进；`WORLD_STARTED` 即允许声明实际 raster 附件和输入。冻结后启动透明捕获与 PCL、native 世界录制，再准备 VRS 并登记清理 raw；main HEAD 只验证附件与阶段。VRS 最终 SRI 仍仅在 terrain scope 绑定。无世界、异常、取消和显示帧退休统一结束 native raw 附着，再丢弃本帧状态。

native Release/x64 与 JVM `compileKotlin compileJava jar` 配套构建已通过，日志分别为 `D:\Projects\MinecraftMod\DXRMC\_tmp_oit_trace\raster-guides-abi11-native-build.log` 与 `raster-guides-abi11-jvm-build.log`。本轮包含 ABI11 mesh 描述符、raw PSO 绑定元数据和逐帧 DLSS 查询移除重复能力探测。正式 JAR 为 90,513,074 字节，嵌套 core、native Release 输出、staged 和 JVM 资源中的 MCDR.dll SHA256 均为 `2DB27C539023BB17CDA0044A8D8D1A7E5D64ADC0A203DD94B94EE9F2DCDBAE6B`；包内无 probes、测试替身或自检类。

`_tmp_oit_trace/raster-guides-abi11-runtime.log` 记录配套 ABI11 客户端在 DLAA、FG 关闭下进入 New World，持续实际 Present，并正常保存退出；该次预热和世界绘制未出现 raw PS/DXC、mesh PSO 创建或世界输出错误。用户确认仅开启 DLAA 仍会出现水、玻璃、云固定留屏，半透明问题尚未修复。当前对照 AntiAliasingMod 的效果贡献分层、来源身份及历史拒绝规则，并继续定位 SR 前后的实际颜色与运动输入；运行中切换和完整 guides 仍未确认。

### ABI10 两阶段接线与 DLAA 修复

当前源码已接入 `beforeGui` 现有 GuiReady 后的显示 Prepare，以及 `afterGui` 只补 UI/backdrop 的最终 Submit。native 深复制并核对 scene/layers/posts/cameras，先提交世界与 guides、保存准确 `guideFence` 后换 ring，GUI 录制之后才等待同帧 guide fence 并读取 validity；有效才接受 SDK，中间提交不创建最终 Present ticket。世界新增独立 R8 coverage，SR/AA 仍读取有限值 RG16F motion；具体协议见 [FFM ABI](../misc/ffm-abi.md)。

native Release/x64 与 JVM/JAR 配套构建已通过。两阶段显示提交及两个 frame ticket 函数的 C++ 跨模块 export 修正对应 `_tmp_display_shader/display-prefix-native-build.log` 与 `display-prefix-jvm-build.log`。本轮继续修复 DLAA：冻结帧先请求 tier 4，旧 `applyDlssToTexture` 随后又从全局默认配置请求 tier 2，导致 prepared SR 校验失败并中止世界输出。现已移除执行时的旧 quality/preset 覆盖，create/evaluate 共用明确的 prepared 参数匹配检查；状态查询报告具体不匹配字段或已准备的 Streamline 会话。显式 UAV 输出也已优先于旧输出 override。

最新 JVM/JAR 构建日志为 `D:\Projects\MinecraftMod\DXRMC\_tmp_oit_trace\dlaa-fix-jvm-build.log`。正式 JAR 为 90,503,991 字节，嵌套 core 中的 MCDR.dll、native Release 输出、JVM 资源目录与隔离客户端实际 DLL 的 SHA256 一致，均为 `5314DE96FE69618A8B886AE871ADCD72D96412E5E42262231E42C784069C54D2`；core 中没有 probes、测试替身或自检类。

`_tmp_oit_trace/dlaa-fix-runtime.log` 记录该 ABI10 隔离客户端在 DLAA、FG 关闭下进入 New World，prepared 状态为 tier 4，未出现本次配置错配或世界输出异常。用户已确认同场景关闭 SR/FG 的 100% 原生画面正常；随后配套 ABI11 运行的用户反馈确认仅开启 DLAA 仍有水、玻璃、云固定留屏，见上一节。运行中切换仍需确认。

独立 agent 的真实 12 帧记录 `oit-trace-1794663626964.log` 已确认 DLAA 每帧实际绘制 raw OIT composite；四张 OIT 纹理共清理 48 次，raw guide 清理 12 次，均早于对应绘制。trans0/accumulate 交换时 composite SRV 同步交换，174 对 render pass 完整；与原生记录 `oit-trace-1794662295084.log` 对比，原版与 raw 的 descriptor/HLSL 一致。当前 JVM 调用记录未发现漏清、错附件或旧 view，native 实际 GPU 附件内容和 SR 前后颜色仍需继续定位。没有本轮性能结论。下文 ABI9 的产物与记录保留其版本范围。

### ABI9 配套构建与客户端记录

2026-11-14 的配套 native Release/x64 与 JVM `compileKotlin compileJava jar` 均已通过，包含配置 retirement、FG Prepare 状态分类、Kotlin 统一 Options mixin、依赖检查和 resize/配置时序修复。日志位于 `D:\Projects\MinecraftMod\DXRMC\_tmp_display_shader\`：`configuration-fg-status-native-build.log` 与 `configuration-fg-status-jvm-build.log`。

正式 JAR 为 90,402,755 字节。嵌套 core 中的 MCDR.dll、native Release 输出和实际 `runtime-sdk/dxrmc/MCDR.dll` 的 SHA256 一致，均为 `1873789DAE4AED8838B89803AD5F67CC1502BE45C6000B400CCC8227E6DAFE75`；包内没有旧 OptionsAntiAliasingMixin、自检或测试类。

实际每帧顺序为 Minecraft.renderFrame HEAD 记录开始时间 → window configure → beginDisplayFrame 按窗口尺寸 resize 原版目标 → apply config → native display begin → 后续投影上传按已发布尺寸/配置计算 jitter。配置发布不再提前到时间记录阶段，避免原版随后 resize 改写本帧世界附件。

`configuration-resize-client.log` 记录 20:05:26 进入隔离 New World，之后持续实际 Present，未再出现 `World attachments differ`。该日志对应上述 ABI9 配套产物的 NONE/100%，DLSS 未启用；该产物的 DLSS/SR/FG 用户确认仍待答复，启用后的运行与 resize 行为尚未确认。动态画质和性能尚无结论。

用户随后明确确认 resize 后退出“没有再闪出，直接正常退出”。resize 后退出闪出 early-window 最后一帧的问题已关闭；该确认覆盖退出表现，DLSS/SR/FG 的运行覆盖仍按上一段单独记录。

## 当前验证边界

已确认的构建、独立探针和场景见本页验证记录。SMAA 世界/缩放/正常退出、DLSS SR 出图及清除返回值修复后正常退出、无世界菜单/blur/窗口调整、原有资源的一次真实 F3+T 重载已有实机证据。world-post adapter 失败后的 GuiReady 拒绝、资源包内容变更与重叠重载、世界切换和各家 FG/超分矩阵仍须分别验证。没有完整计划完成、完整 SDK 硬件生命周期通过或性能提升声明。

本地证据（`build/` 为忽略产物，本页正文独立描述结论和限制）：[最终 native 构建](../../build/reports/workgraph/integration-native-final.log)、[最终 JVM/staging](../../build/reports/workgraph/integration-jvm-stage-final.log)、[地形资源 token 接线](../../build/reports/workgraph/raster-resource-import-status.md)、[帧契约](../../build/reports/workgraph/frame-contract-final.log)、[集成审查请求](../../build/reports/workgraph/final-integration-review-requests.md)。独立行为探针的源码入口仅为 `src/probes/` 下的脚本。
