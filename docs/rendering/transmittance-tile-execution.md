# TRANSMITTANCE 四子样本 tile 执行契约

本文件把四子样本透明覆盖的执行边界固定下来。完整实现由内容冻结、shader 路由、GPU 分桶、tile executor 和最终归一化五部分组成；任何一部分缺失时，当前帧保留原版透明颜色并记录 typed unavailable，不能把原始 `ExecuteIndirect` 当作 tile consumer。

## 固定参数

- resident batch 最多 16 个 tile；每 tile 有效区域为 `64×64`，四边各保留 `2 px` guard。
- scratch 使用单采样 `Texture2DArray`，按 batch 复用；四个 lane 对应平移 viewport `(−0.25,−0.25)`、`(+0.25,−0.25)`、`(−0.25,+0.25)`、`(+0.25,+0.25)`。
- VS/MS 输出固定的 viewport array index 和 render target array index；设备不支持直接输出时使用最小 GS 转发 system value。没有合法 IA/mesh adapter 的 topology 直接返回 unsupported。
- 透视插值、clip、top-left、depth/stencil、front-face、discard、`SampleGrad` 和 `SampleLevel` 继续由原硬件/原 shader 语义完成。tile 坐标只改变 scratch viewport 与本地 attachment 地址，不能改 projection 或手写 coverage。

## 原版阶段顺序

每个 lane 都完整执行以下阶段，顺序不能压缩：

1. 从冻结的 opaque/cutout depth 事件建立 main DSV。
2. 第一次 water mask：从当时 main depth 复制 terrain depth，再执行 water geometry。
3. `DEPTH_BOUNDS` 写原 bounds；随后 cull 读原 bounds，写 culled bounds 和 main depth。
4. Cloud `DEPTH_BOUNDS` 前把当前 main depth 复制到 cloud 私有 DSV，再执行 cloud depth；若有 water mask，第二次从更新后的 main depth 重建 terrain depth 并执行 water geometry。
5. `TRANSMITTANCE` 按真实 producer 顺序写共同 coefficients。
6. `ACCUMULATE` 按同一顺序读取 coefficients 和对应 DSV，写共同 Q。
7. 每个 lane 独立执行原版 normalization/composite 数学，最后平均四个已归一化的 premultiplied 结果。

原 bounds 与 culled bounds 始终分开；最终 depth 使用原 bounds 的 `b` 分量。chunk/primitive 分批只限制临时 refs 数量，不能丢弃 overdraw、限制每像素层数或把 262144 条历史 records 当作当帧 coverage 池。

## GPU 分桶与 CPU 录制

GPU Work Graph/compute 负责 primitive 或 meshlet envelope、tile ref 扇出、count、prefix、compact 和 indirect args。CPU 只录制 `batch × lane × stage × compatible ordered run`，禁止 `tile × all draw`。每个 run 固定 PSO、root signature、descriptor closure、目标和深度状态；ExecuteIndirect 只消费 root constants、VB/IB、可见表和 arguments，不能切换 PSO、viewport、descriptor table 或 attachments。

terrain ref 必须保留真实 section、visible/arguments、VBO、globals、atlas、signature 和资源版本；普通 IA ref 还必须保留真实 index type、base vertex、instance、primitive identity。tile consumer 只能消费同一 recording 内已 seal 的 packet，不能在另一个 submission 里重新读取可变 DEFAULT/UPLOAD 内容。

`frameSerial` 使用 native OIT recording 的 command-list serial（开始录制前的 `fenceValue + 1`）；`VanillaFrameIdentity.realFrameId` 只标识游戏帧。两者必须分别传递和校验，不能用游戏帧号代替 recording serial。

`packetOrdinal/packetCount` 表示本 recording 捕获的真实 draw 序列；`laneIndex` 表示四个子样本；resident batch 表示本次复用 scratch 的 tile 区域。这三类身份独立。每个原 packet 的同一 tile 区域必须齐备四个 lane，同 packet/lane 的区域不能重叠，提交保持原 draw 顺序。

旧 `resolveRecordsV4` 只接受四个 batch，并要求 `packetCount == terrainBatchCount`，无法消费真实 packet × lane × resident batches。JVM 已移除 `size == 4` 的静默丢弃和用数组 index 重写 packet ordinal 的接线。完整性校验通过后仍在任何 tile GPU 录制之前返回 `EXECUTION_STREAM_UNAVAILABLE`，普通 records 继续使用既有 Q 编码的 V3 resolve。新执行契约必须由 native seal、执行全部有序事件并发布 normalized 输出，不能重解释旧 V4 的字段。

## 内容冻结与失败语义

packet 的 COM lease 只保证对象寿命。真正可消费前还要取得与 command recording、world epoch、program generation 和 resource version 绑定的内容 lease。可变常量与 UPLOAD slice 在 capture 点保存精确字节，GPU 写入的 DEFAULT buffer/纹理在原 draw 对应的位置复制所需范围，或由原 owner 在 tile 消费结束前延迟复用；完整静态 VBO/atlas 不重复镜像。原版合法 map/copy/write 继续执行，后续写入只改变原资源，tile 读取冻结内容。持续 map 的指针不能靠写入事件表监视，必须在捕获时复制或由真实分配 owner 保证范围不可复用。

状态沿 `Recording → Frozen → Consumed` 前进。冻结失败或 shader/descriptor closure 不完整时，在录制 tile 命令之前返回 typed unsupported；不能先录制命令，再靠状态变更撤销。reset、abort 和提交未知沿原录制生命周期保留或退休资源，不把未知提交当作未执行。packet 持有 strong lease，context 的 weak 表只用于查找和清理；提交完成后由实际 fence 退休。写入监视用于发现未覆盖的冻结范围，不能代替内容副本或原 owner 的范围租约。

当前旧 ACCUMULATE history capture 已知缺少可执行的完整事件流，直接保存 `UnsupportedContent` 和实际 recording 身份，不分配冻结资源、不录制复制。内容冻结能力保留在 native 边界：UPLOAD 按范围复制 CPU 字节，DEFAULT buffer 按范围录制 GPU copy，单采样、单 plane、非 depth 的 Texture2D/cube 按 SRV 所需子资源复制并建立独立 view；活动附件别名、MSAA、多 plane、depth 和其它 view 类型拒绝。冻结 wrappers 与 CPU descriptor heap 由同一个私有 owner 持有，录制租约保留该 owner，退休不进入普通 buffer pool 或普通资源计数。

新执行流必须在原 draw 之前完成可执行计划检查，再在真实 draw 边界冻结所需内容。静态 VBO/atlas 应接真实 allocation owner 的不可复用范围租约；当前没有这类消费者，不能为最终必然 unavailable 的旧入口每 draw 镜像整张材质纹理或整套几何。资源复制能力已包含在本机 native Release/x64 成功构建中，尚无该新路径的游戏运行结果。

首批原 draw 寿命保护使用原版 `UberGpuBuffer` / TLSF 的真实 allocation。分配在实际上传 copy 前登记 producer lease，copy 成功后才发布给 renderer；在 `extractSectionDrawGroups` 持有 dispatcher 的原锁时取得独立 read pin。普通 indexed、custom-index、indirect 和 separate draw 在真实 draw 点把本帧提取到的 vertex/index allocation group 交给 `D3D12TransientMemory`，`freeAllocation` 退休被 pin 的旧区间，最后一个 pin 释放后才执行真正的 TLSF free；heap close 同样等待其区间租约。frame extraction 与 GPU submission 各持独立引用，submission 引用交给已有 `D3D12TransientMemory` / `SubmissionRetirement`，随真实 context fence 释放。几何范围按实际选中区间保存，不复制包含大段空隙的 `requiredVertexBytes` 前缀。

meshlet descriptor 由唯一 writer `D3D12MeshletResidency` 按 buffer generation 与选中 offset/count 保留；同 generation 只追加，replacement 退休旧 generation。read lease 在 Work Graph prepare 读取 descriptor 前交给 submission，`Prepared.close` 不提前释放它。native 继续使用已有 recording/object 与 TerrainBatch 租约，不新建通用 content registry。

### 同 recording 的读取范围注册

`TerrainPreparedFacts.exactReadSet` 按真实 command 保存 allocation owner/generation、VBO 的绝对字节区间和对应 meshlet generation/descriptor 区间；同一 batch、vertex binding 和 descriptor generation 复用不可变事实。JVM 在 stage token 开启后、实际 draw 前调用 `OitRegisterTerrainReplayReadSetV2`。每个 command 按 geometry、meshlet 两项交错编码，`commandIndex` 保留其原顺序；native 要求 pair 数量等于真实 `TerrainBatch.sectionCount`。

注册绑定同一 frame serial、producer scope、program/resource version、terrain batch 与 vertex binding。native 在实际 draw 前核对对象、buffer 范围和真实 atlas 范围，取得 object leases；成功 draw 后，ranges 与真实 `TerrainBatch` shared lease 随 receipt 进入原 Frame journal。JVM 的 allocation/descriptor pin 继续由同一 context 的 submission fence 退休，native 对象引用不取代这些 pin。取消仅允许 draw 消费前执行；abort 清理未确认注册，提交未知仍保留原 owner。数组、引用、journal 和排序临时副本均计入 OIT 预算。

UPLOAD 列表现在来自真实 buffer/suballocation owner：uniform 使用实际 graphics binding slot，另有 `TerrainGlobals` 和 `TerrainSections` 两个专属角色。写映射关闭才推进内容版本，未关闭映射、无法覆盖读取范围、已退休 allocation 或失效 recording 返回明确的 unavailable。原 backing pin 和 allocation 随同 context 的真实 submission fence 退休；未知提交继续保留。native 将这些角色逐项对照实际绑定、globals 与 candidates 的 object/offset/length，不把 owner 身份当成内容副本。

native 在原 draw 前映射实际 UPLOAD candidates，按每条 40 字节 section 记录核对 VBO base vertex、meshlet descriptor offset/count 与已登记读取范围。此处没有 GPU 读回，也没有复制完整 VBO/atlas；descriptor 内部的最终 shader 读取闭包仍需完整 replay 预检确认。UPLOAD 在实际捕获点复制精确范围、sampled 材质和 GPU 输出内容、完整执行事件尚未接齐；receipt 保持 `UNSUPPORTED`，不发布 Frozen 或 corrected 输出。

设备侧 sampled texture 内容 owner 让同一纹理版本的真实 SRV 共享覆盖和私有 backing，首次冲突写入前按完整已登记子资源保存；同 recording 依靠 GPU 命令顺序，跨 recording 或 CPU 写入必须等待真实 fence 完成。清屏、上传、copy、附件 draw、compute，以及外部 terrain、surface blit/sharpen 和 SMAA copy-back 已在实际写点接入。Composite 边界已调用内容采集，先校验全部纹理布局，再登记各输入的 owner/version。D32 支持限于单采样、单 plane、单层 2D 的 `R32_TYPELESS` backing 与 `R32_FLOAT` SRV，保存资源保留深度标志，清深度和 depth attachment 写入都先保存旧内容。缺少 pipeline depthWrite 字段时首次 draw 保守处理 active D32 depth，可能使只读深度 pass 多一次保存；实际 GPU 保存与退休仍缺独立运行证据。

terrain 采集只保留实际不可变 CPU facts。旧入口无法消费完整执行流，不按每 draw 分配四 lane 的全屏 tile scratch，也不为该入口触发 tile 管线准备。scratch 由完整执行计划在内容和事件预检通过后取得，并按 resident batch 复用；native 旧 `BatchV2/V3` 入口明确返回 `UnsupportedContent`，不把局部三阶段录制当作完整结果。

### 完整 V2 事件流与 Composite 边界

V2 独立于旧 ACCUMULATE history 的 V1 stream，producer scope 与 render pass 分开记录，frame 自有的 depth/bounds copy、cull 与 terminal recipe 不虚构 producer。每条 terrain draw 引用其真实三阶段 receipt，前端 invocation、后端 command、receipt 与 primitive selection 各有独立身份。前端 journal 只说明真实 JVM 调用顺序；copy/cloud/water 等原操作要由各自 native adapter 确认，不能用前端 sequence 替代 GPU 执行证据。

当前 JVM seal 消费真实 journal，通过 V2 begin/append/query/terrain-receipt/seal/abort 入口提交边界与 observation；旧 V1 空 stream seal 已移除。scope 与 pass 的计数、EMPTY/FAILED 和 manifest 随实际成功 append 推进。terrain receipt 按 nativeSequence 计算每个 sourceDrawId 的 commandOrdinal，零 shaderGeneration 保留实际语义；receipt 覆盖不完整时保留 RECEIPT 缺项。

Composite 创建 render pass 前，JVM 显式传入当次 color、main depth、Q、bounds、coefficients、sampler、target 和 compiled shader。全部 view 的布局先校验，随后通过 `OitCaptureExecutionTextureV1` 取得 native Frame 生成的 captureId、实际 texture、owner/version 与 subresource 范围。Frame 持有真实 sampled-content lease，JVM 不能制造 captureId。重复捕获只复用仍指向同一原内容的记录；旧内容已保存为副本时，新捕获取得新的身份。

每个 capture 只登记自身读取的子资源计数。seal Unavailable、Frame seal 缺少 sealed 执行流、stream/frame abort、reset 和部分捕获失败都会撤销该计数，避免原 Composite 的颜色/深度写入为无消费者输入生成副本。共享版本的其它 read 保留计数；read、descriptor 与已经录制的 GPU copy 资源继续由 recording lease 保活到实际 fence 完成。提交未知保留 owner。

journal 完成后追加 frame 自有的 terminal `NORMALIZATION`，不关联 open producer scope 或 render pass。资源列表按 color / main depth / Q / bounds / coefficients 排列，main depth 使用 `RESOURCE_MAIN_DEPTH`，读集按实际数量进入 query 与 manifest。native 同时核对 captureId、对象、版本、角色、格式、尺寸与真实租约，再保活 pipeline 和 sampler。元数据结构 `mcdr_oit_execution_texture_v1` 为 64 B；`mcdr_oit_execution_normalization_v2` 为 224 B，保存输入 captureId 和认证信息，输出由完整 executor 分配。旧 normalization V1 保留原 payload 语义。

认证信息由 JVM 的源码与实际编译结果检查产生，`Certified` 在构造时解析 source hashes，并计算 contractIdentity 的 UTF-8 SHA-256；每帧编码只复制已缓存值。主 Composite PSO 通过 `CreateOitNormalizationPipelineV1` 创建，native 校验 136 B 认证描述和真实管线描述，同次保存不可变认证 owner。V2 逐项核对该 PSO 保存的 generation、targetCount、certificationVersion 和三组 hash；普通管线没有此 owner 时拒绝消费。device 只保活 owner，源码规则归 shader 域，认证匹配归 OIT 域。

完整 adapter、opaque seed、selection、read set 与四 lane executor 仍有实现缺口，保留 `GAP_NORMALIZATION` 等对应缺项。V2 seal 返回 Unavailable，不发布 sealed 成功句柄或 corrected 输出。最终显示的 corrected view 与 source mapping 由实际 Composite 绑定核对；draw 成功后的 `COMPOSITE` observation 仅记录结果，不借用上一帧 recipe。

```mermaid
flowchart LR
    A[实际 scope/pass/draw 与 receipts] --> B[Composite 前取得原 normalization recipe]
    B --> C[V2 全事件与内容检查]
    C --> D[四 lane tile 执行与归一化]
    D --> E[当帧 opaque/display 输入]
    E --> F[原 Composite 点执行一次]
    F --> G[记录实际 Composite 结果]
```

完整四 lane opaque/cutout seed、water mask/depth、cloud、bounds copy/cull、所有 producer adapter 和内容 lease 都归同一执行计划。缺项时 V2 返回具体原因，不产生 sealed 成功句柄；旧 V1 receipt 语义与原 Q resolve 保持原契约。实现依赖按真实 producer 能力逐项接齐，不能先清除 Unsupported 来模拟完成。

### 原 draw 与重放的接点

三阶段的 CPU 选择事实从现有 terrain prepare/draw 取得。正常 `DEPTH_BOUNDS`、`TRANSMITTANCE` 保留自己的 PSO；`ACCUMULATE` 保留已有 history capture。一次原 draw、frontend invocation、跨阶段 drawGroup 与四个 lane 分别保留身份，native receipt 绑定实际执行的那一次 draw。跨阶段关联使用真实 producer owner、完整有序 command/section/instance/primitive selection、geometry generation、buffer owner/range 和材质身份，不能用各阶段的数组位置配对。

单事件 replay 归 native 完整 executor 调用，每次只执行原流中的一个 draw stage，并沿用该事件实际 shader、绑定和内容 owner。底层 `recordTerrainTileReplay` 通过显式的 `TerrainTileAttachmentMode` 区分 `Clear` 与 `Load`，调用方必须由完整 lane 初始化事件决定清屏；普通 replay 使用 `Load`，禁止每 draw 清除共同 bounds、coefficients 或 Q。旧 `BatchV2/V3` 仍按自己的不完整契约返回 unavailable，新增执行能力使用版本化入口。完整 executor 尚未实现。

### 实际命令、内容与 adapter 的实施契约

device 拥有通用 `GraphicsDrawPacket`，保存真实 IA/indexed/indirect/mesh 参数、VB/IB 范围、PSO/root、descriptor、viewport/scissor、push constants 和 attachment。Frame 通过 context 局部回调接收实际命令，device 不导入 OIT。准备发生在原 draw 校验之后、GPU 命令录制之前；原命令与所属 declared pass 成功完成后才确认 receipt。multi-draw 为每条实际命令生成 receipt，前端 scope 只表达调用归属。

统一 receipt 分开记录命令是否 Recorded/Empty/RecordingFailed、内容是否 Frozen/Unsupported/Stale、adapter 是否 Ready 或缺少 topology/binding/addressing 支持。Recorded 表示命令已录入当前 command list，提交与完成继续由真实 fence 管理。terrain 与普通 operation 使用同一 Frame 内的 native sequence 排序；旧 V2 terrain receiptCount 保持原语义，全部 operation/seed 计数通过新增版本接口公开。

ABI25 的 `mcdr_dx12OitAppendExecutionOperationEventV1` 将每条真实 operation receipt 与一个 V2 事件关联。Frame 按 receiptId 查询原命令，仅接受已完成 operation 中已确认且 input pass 完成的 packet，逐字段检查帧、recording、来源、命令序号、PSO 和 generation；重复消费返回 stale。stream 独立持有 packet 的强引用，seal 按统一 nativeSequence 合并 terrain 与 operation，生成只读命令表，漏项返回原始 receiptId。前端嵌套调用按实际命令归属，不按返回次序执行。事件的 `receiptCount/receiptIds` 为零，旧 query、manifest 和 seal 的 expected/observed 仍只统计 terrain。ABI26 的 terrain packet 另外保存 native stage adapter 的 source/tile identity、generation 和 route 租约，批次准备会再次核对这些值，避免 route 或 PSO 换代后误消费。

JVM 按实际 operation receipt 数量计算事件容量，并在单次 seal 的 Arena 内复用一个按布局分配的 receipt 缓冲；native 同步复制字段，保留实际 packet，不保留 FFM 地址。packet 索引、事件表和排序表计入同一预算。只读命令表已进入生产执行流，完整内容冻结、opaque seed、shader adapter 和四 lane 执行仍有缺项，seal 保持 Unavailable。

外部读取沿用精确字节范围的 buffer content 与 sampled texture content owner。terrain 的 visible/arguments 由真实 batch lease 保活，不逐 draw 复制。main/terrain/cloud depth、bounds、culled bounds、coefficients 和 Q 属于 lane 内部资源，packet 保存角色及前驱版本，重放时绑定对应 scratch。depth/bounds copy 默认重放实际 shader；只有其数学已独立认证时才替换为原生 copy。water 两次 occurrence、cloud depth 初始化与 cloud geometry 分别记录，保持原执行顺序。

opaque/cutout seed 捕获从首个 world depth clear/draw 之前开始，到原 `executeOit` 入口结束，覆盖实际地形、实体和其它 world-depth writer。最终单采样 depth 无法补回四 lane 的 coverage 和 alpha discard，现有 opaque color/depth copy继续只供现有重建输入使用。shader adapter 同时保存全局屏幕计算与 scratch 局部附件地址，单改 viewport 或 array index不能覆盖 `SV_Position`、全屏 uniform 和 attachment 采样。

native 当前已提供显式 arm/seal/cancel 的 seed 采集契约，绑定真实 world depth、graphics observer 和已发布程序代际；普通帧只维护内嵌计数，不分配完整事件表。主 terrain WG 与 IA/indirect/mesh 接入实际深度写入，opaque-before-composite 边界负责封口。当前没有 arm 的生产调用方、journal 的 executor 读取接口或四 lane seed 重放，`GAP_OPAQUE_SEED` 保持缺失；内部采集接口不发布完整 executor 成功。

完整 seal 在任何 tile 命令录制前，检查 seed、全部 receipt 的唯一消费与原顺序、内部资源版本、外部 frozen 内容、shader 路由、ordered run、预算和真实资源。准备失败不录制 replay；GPU 命令已经部分录制后的失败归 RecordingFailed，拒绝提交该列表。terrain 提供单 stage 录制入口，清屏归 lane 初始化；GPU 按 primitive/instance/section/source ordinal 分桶，refs 不足时按 primitive chunk 继续，禁止丢弃 overdraw。四 lane 使用固定 resident batch scratch 完成各自 normalization 后平均。

### 录制拒绝与释放

已知录制失败在 `Close/Execute` 前拒绝提交，属于未提交的失败；真实 `Execute` 后的提交或 signal 结果未知继续保留资源。同步提交和异步结算通过版本化 typed submit/settle/status 把两种结果传给 JVM，只有实际提交携带 context fence。已知拒绝不推进 fence、不执行读回成功回调，也不把临时上传绑定到伪造的提交。异步 worker 归还所有权后，取消该 display ticket，再等待此前真实提交的 fence并丢弃失败列表；checked destroy 确认 native context 已销毁后，才能释放 encoder 登记的 timing 与其它资源。正常 draw 和 context control 不额外查询录制状态。

丢弃失败列表后，当前 JVM encoder 进入终止状态。meshlet residency 等 owner 在录制上传后就已发布 CPU offset，丢弃 GPU 命令不会撤销这些发布；允许下一帧直接继续会读取尚未上传的内容。当前决策是失败立即终止并完成释放，可复用恢复需要所有写入 owner 的失效或回滚契约。读回取消交给真实 owner 清理，截图自有缓冲与 Tracy 长期持有的缓冲分别处理，不能执行成功回调代替取消。

## 归一化输出

tile executor 输出已经完成 normalization 的 premultiplied transparency。四个 lane 全部完成后发布独立 corrected target，普通 composite 只合成一次。任意 lane、stage、producer 或内容 lease unavailable 时，保留完整当帧原版透明颜色；不复用旧贡献、不平均未完成 lane，也不把 OIT motion/provenance 缺口写成有效 FG guide。

归一化输出属于目标契约。当前 tile resolve 的 `McdrOitTileInfoV1.w=0` 表示 typed unavailable，必须保留完整当帧原版透明颜色；真实 producer 尚未完成 depth/cull/normalization 事件、系数与 Q 发布，不能写入有效标记。完整输出还必须统一 premultiplied 颜色量纲与 composite 规则，避免重复归一化。

## 当前实现边界

### 顶点读取范围的证明

terrain mesh shader 的属性解码把分量数传入读取函数，32 位属性按 1/2/3/4 个 word 选择 `Load/Load2/Load3/Load4`，16 位属性按所需 word 数读取。原版 28 字节 BLOCK 顶点的实际区间为 Position `[0,12)`、Color `[12,16)`、UV0 `[16,24)`、UV2 `[24,28)`，最后一个顶点不会再因固定 `Load2/Load4` 读取下一 allocation。三个 OIT stage、normal/flipY、16/32/64 quad 共 18 个真实 format mesh 变体已通过 DXC。通用格式的 read-set 仍须保存实际 byte interval；不能用单个 boolean owner 宣称读取完整。无法证明的格式在 tile 预检阶段返回 typed unavailable，原版 draw 仍按现有路径执行。

实现必须在 native、JVM shader 路由和显示 guides 之间共享同一版本化 packet/producer/stage 事实。排序 index、FEATURE/BORDER capture 和 terrain packet 只是输入事实；在 tile executor、真实内容冻结和四 lane depth/coverage 完成前，不得宣称 TRANSMITTANCE alpha/coverage AA 已交付。

native `BatchV2` replay 已声明 binning 四个步骤与三个 replay stage 的实际资源状态。stage 0 的唯一颜色输出必须映射为原 `bounds`；stage 1 按实际 shader 系数布局映射 `coefficients[0..targetCount)`，target 数只接受 `1/2/4`；stage 2 的唯一颜色输出必须映射为 `q`。culled bounds、terrain/cloud depth 和 normalized scratch 输出不属于这三个几何 stage 的 MRT。原版独立 cull、深度复制/绘制和 normalization 事件目前尚未进入 `BatchV2`，所以正确映射三个 stage 后仍返回 `UnsupportedContent`，诊断为 `Tile V2 lacks sealed depth/cull/normalization events`；此检查发生在 binning/replay GPU 命令录制前。

JVM 事件旁表在真实 draw 成功返回后记录 water/cloud depth copy、bounds copy/cull、water geometry 和 composite，并保留实际 source、destination 和采样绑定；pipeline 名称仅用于诊断。原版 `oit_composite.fsh` 在同一个 draw 内计算 coverage、normalization 并合成，没有独立 normalization pass。tile executor 要从同一数学中输出各 lane 的 premultiplied transparency，再交给唯一显示合成。当前事件还没有 native frozen-content lease，execution recording 保持 incomplete。RGB records resolve 输出仍为原 Q 量纲。

tile envelope 固定为 64 字节：`pixelBounds`、`identity`、`draw`、`properties` 各 16 字节。CPU 上传、native 范围校验和 HLSL StructuredBuffer 使用同一布局；DXC 对 node/scatter 的反射已确认 stride 为 64。GPU 命令录制后的失败使用 `RecordingFailed`，JVM 将其转为 Rejected 并终止本帧；录制前的 `UnsupportedContent` 与 `UnsupportedRoute` 分别保留自己的原因。

JVM terrain producer 的内部 facts schema 2 按 section 保存真实 draw fact、atlas descriptor base 和不可变 geometry，meshlet 与量化 bounds 由 geometry 提供。该事实层不持有 native visible/arguments owner，不生成 tile regions，也不发布可执行 packet；缺失时保留 typed unavailable。非 capture 的 prepare 不建立 `TerrainPreparedFacts` 或 descriptor-offset 数组，真实 capture 的数组在本次 prepare 独占并按保留边界复制。源码优化减少了 CPU 展开、遍历和分配，本轮未测 FPS。

tile resource owner 按枚举顺序校验三个 stage，直接把 bounds、实际 coefficient targets 和 Q 作为对应 RTV，禁止为 stage 分配另一组无关颜色附件。该 owner 仍须由实际 producer 提供 tile regions、envelope 与 stage 程序才能创建；资源分配成功不发布执行完成。native replay 使用 packet 保存的材质绑定、push constants 和实际读取范围，单 resident tile 的 SRV 也保持 Texture2DArray。

每个 lane 的 `mainDepth`、`terrainDepth`、`cloudDepth` 使用三张独立 D32_FLOAT Texture2DArray，支持 DSV、采样和 copy。三个 geometry stage 共用 `mainDepth`，terrain/cloud 的保存与恢复通过各自深度资源完成；RGBA32_FLOAT `scratchDepth` 只承载归一化数据。这批仅接好资源所有权与 stage 绑定，初始化、深度 copy 和事件执行仍由完整 executor 接入。
