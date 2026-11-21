# TRANSMITTANCE 四子样本 tile 执行契约

本文件把四子样本透明覆盖的执行边界固定下来。完整实现由内容冻结、shader 路由、GPU 分桶、tile executor 和最终归一化五部分组成；任何一部分缺失时，当前帧保留原版透明颜色并记录 typed unavailable，不能把原始 `ExecuteIndirect` 当作 tile consumer。

## 正常帧的当前接线

正常帧不建立 tile 变体。`VanillaPipelineCompiler` 只为每条原版管线创建普通图形 PSO；`OitReconstructionInputs` 不 arm opaque depth seed、不建立 execution stream、不调用 tile 执行入口。Composite 消费当帧 per-draw records 经 Resolve V3 得到的 corrected transparency，tile executor、四 lane depth/coverage 与 normalization 都不可达。

两侧实现已在当前源码中删除：JVM 的 tile/lane 执行流、terrain 回放、tile 资源与归一化文件不再存在；native 的 `OitExecuteTileStreamV2`、执行流 V1/V2、terrain stage receipt/read set、`d3d12_oit_normalization.cpp` 与 `OitResolveRecordsV4` 也已删除。本文件余下章节是该能力的重新引入契约，不是当前实现描述；恢复时必须同时提供 lane 管线变体与 opaque seed，否则 native 返回 `-2`。

记录追加对每个透明片元只做一次全帧分配器原子：`rebuild\MCDR\include\mcdr_oit_shader.h` 的 `AppendV1` 用 `McdrOitAllocatorV1.InterlockedAdd(0u, 1u, index)` 取记录索引。禁止改回 compare-exchange 重试循环——它把全帧所有透明片元串行化到同一条 cache line 上，实测 854×480 帧间隔 108.5 → 31 ms、2560×1334 411 → 32.5 ms。索引只决定记录落点，顺序仍由逐像素 `InterlockedExchange` 的链表和 `previous` 链决定。

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
4. Cloud `DEPTH_BOUNDS` 前把当前 main depth 复制到 cloud 私有 DSV，再执行 cloud depth；若有 water mask，第二次从更新后的 main depth 重建 terrain depth 并执行 water geometry。两次 water mask 各有自己的 render pass，先拷贝再录制该 pass 的所有遮罩 draw，写入同一张 terrain depth。
5. `TRANSMITTANCE` 按真实 producer 顺序写共同 coefficients。
6. `ACCUMULATE` 按同一顺序读取 coefficients 和对应 DSV，写共同 Q。
7. 每个 lane 独立执行原版 normalization/composite 数学，最后平均四个已归一化的 premultiplied 结果。

原 bounds 与 culled bounds 始终分开；最终 depth 使用原 bounds 的 `b` 分量。chunk/primitive 分批只限制临时 refs 数量，不能丢弃 overdraw、限制每像素层数或把 262144 条历史 records 当作当帧 coverage 池。

## GPU 分桶与 CPU 录制

GPU Work Graph/compute 负责 primitive 或 meshlet envelope、tile ref 扇出、count、prefix、compact 和 indirect args。CPU 只录制 `batch × lane × stage × compatible ordered run`，禁止 `tile × all draw`。每个 run 固定 PSO、root signature、descriptor closure、目标和深度状态；ExecuteIndirect 只消费 root constants、VB/IB、可见表和 arguments，不能切换 PSO、viewport、descriptor table 或 attachments。

terrain ref 必须保留真实 section、visible/arguments、VBO、globals、atlas、signature 和资源版本；普通 IA ref 还必须保留真实 index type、base vertex、instance、primitive identity。tile consumer 只能消费同一 recording 内已 seal 的 packet，不能在另一个 submission 里重新读取可变 DEFAULT/UPLOAD 内容。

地形分桶使用 `BuildTerrainTileEnvelopesV1`。它复用原地形 VS 的顶点解码和变换，从 Work Graph 的 visible header 与 work 表定位实际 meshlet，再按原对角线裁剪跨近裁面的三角形。完整位于深度区间的 quad 直接投影。每条 64 B 记录保留 section-local 起始三角形、section ID 和 descriptor index。尾项写空包围框，分类阶段生成零 refs。buffer 由 GPU 写入，固定容量按最多 4096 条 meshlet 记录复用，stage 使用自己的 route 和 source VS。

同一 terrain selection 在同一 recording 内可能出现多次。capture 为每个 OIT stage 的出现顺序分配 occurrence，并用同一 selection 与 occurrence 生成跨 `DEPTH_BOUNDS`、`TRANSMITTANCE`、`ACCUMULATE` 的 group identity。seal 先用唯一 `ACCUMULATE` occurrence 锚定 terrain facts，再关联另外两个 stage；缺失、重复或顺序无法唯一对应时保持该 facts unavailable。

`frameSerial` 使用 native OIT recording 的 command-list serial（开始录制前的 `fenceValue + 1`）；`VanillaFrameIdentity.realFrameId` 只标识游戏帧。两者必须分别传递和校验，不能用游戏帧号代替 recording serial。

`packetOrdinal/packetCount` 表示本 recording 捕获的真实 draw 序列；`laneIndex` 表示四个子样本；resident batch 表示本次复用 scratch 的 tile 区域。这三类身份独立。每个原 packet 的同一 tile 区域必须齐备四个 lane，同 packet/lane 的区域不能重叠，提交保持原 draw 顺序。

旧 `resolveRecordsV4` 只接受四个 batch，并要求 `packetCount == terrainBatchCount`，无法消费真实 packet × lane × resident batches。JVM 已移除 `size == 4` 的静默丢弃和用数组 index 重写 packet ordinal 的接线。当前若 sealed depth/cull/normalization 事件不完整，在任何 tile GPU 录制之前返回 `SEALED_EVENTS_INCOMPLETE`；事件完整但执行流仍未提供消费契约时返回 `EXECUTION_STREAM_UNAVAILABLE`，普通 records 继续使用既有 Q 编码的 V3 resolve。新执行契约必须由 native seal、执行全部有序事件并发布 normalized 输出，不能重解释旧 V4 的字段。

## 内容冻结与失败语义

packet 的 COM lease 只保证对象寿命。真正可消费前还要取得与 command recording、world epoch、program generation 和 resource version 绑定的内容 lease。可变常量与 UPLOAD slice 在 capture 点保存精确字节，GPU 写入的 DEFAULT buffer/纹理在原 draw 对应的位置复制所需范围，或由原 owner 在 tile 消费结束前延迟复用；完整静态 VBO/atlas 不重复镜像。原版合法 map/copy/write 继续执行，后续写入只改变原资源，tile 读取冻结内容。持续 map 的指针不能靠写入事件表监视，必须在捕获时复制或由真实分配 owner 保证范围不可复用。

状态沿 `Recording → Frozen → Consumed` 前进。冻结失败或 shader/descriptor closure 不完整时，在录制 tile 命令之前返回 typed unsupported；不能先录制命令，再靠状态变更撤销。reset、abort 和提交未知沿原录制生命周期保留或退休资源，不把未知提交当作未执行。packet 持有 strong lease，context 的 weak 表只用于查找和清理；提交完成后由实际 fence 退休。写入监视用于发现未覆盖的冻结范围，不能代替内容副本或原 owner 的范围租约。

当前旧 ACCUMULATE history capture 仍缺少可执行的完整事件流，准备入口在录制 tile 命令前返回 `UnsupportedContent`。terrain packet capture 只有在执行器显式请求 `FreezeBindings`、tile adapter 已注册且 route lease 属于当前 recording 时才冻结内容；该路径对 UPLOAD 按范围复制 CPU 字节，对 DEFAULT buffer 按范围录制 GPU copy，对单采样、单 plane、非 depth 的 `Texture2D`/`Cube` 按 SRV 子资源建立独立 view。默认 `FactsOnly` 不复制内容。活动附件别名、MSAA、多 plane、depth 和其它 view 类型拒绝；冻结 wrappers 与 CPU descriptor heap 由同一个私有 owner 持有，录制租约保留该 owner，退休不进入普通 buffer pool 或普通资源计数。

新执行流必须在原 draw 之前完成可执行计划检查，再在真实 draw 边界冻结所需内容。terrain packet 只冻结实际 vertex/atlas 与绑定读取范围，capture-only Buffer 没有更窄范围时才按声明对象大小冻结；不复制整张材质纹理或整套几何。资源复制能力已包含在本机 native Release/x64 成功构建中，尚无该新路径的游戏运行结果。

首批原 draw 寿命保护使用原版 `UberGpuBuffer` / TLSF 的真实 allocation。分配在实际上传 copy 前登记 producer lease，copy 成功后才发布给 renderer；在 `extractSectionDrawGroups` 持有 dispatcher 的原锁时取得独立 read pin。普通 indexed、custom-index、indirect 和 separate draw 在真实 draw 点把本帧提取到的 vertex/index allocation group 交给 `D3D12TransientMemory`，`freeAllocation` 退休被 pin 的旧区间，最后一个 pin 释放后才执行真正的 TLSF free；heap close 同样等待其区间租约。frame extraction 与 GPU submission 各持独立引用，submission 引用交给已有 `D3D12TransientMemory` / `SubmissionRetirement`，随真实 context fence 释放。几何范围按实际选中区间保存，不复制包含大段空隙的 `requiredVertexBytes` 前缀。

meshlet descriptor 由唯一 writer `D3D12MeshletResidency` 按 buffer generation 与选中 offset/count 保留；同 generation 只追加，replacement 退休旧 generation。read lease 在 Work Graph prepare 读取 descriptor 前交给 submission，`Prepared.close` 不提前释放它。native 继续使用已有 recording/object 与 TerrainBatch 租约，不新建通用 content registry。

### 同 recording 的读取范围注册

`TerrainPreparedFacts.exactReadSet` 按真实 command 保存 allocation owner/generation、VBO 的绝对字节区间和对应 meshlet generation/descriptor 区间；同一 batch、vertex binding 和 descriptor generation 复用不可变事实。JVM 在 stage token 开启后、实际 draw 前调用 `OitRegisterTerrainReplayReadSetV2`。每个 command 按 geometry、meshlet 两项交错编码，`commandIndex` 保留其原顺序；native 要求 pair 数量等于真实 `TerrainBatch.sectionCount`。

注册绑定同一 frame serial、producer scope、program/resource version、terrain batch 与 vertex binding。native 在实际 draw 前核对对象、buffer 范围和真实 atlas 范围，取得 object leases；成功 draw 后，ranges 与真实 `TerrainBatch` shared lease 随 receipt 进入原 Frame journal。JVM 的 allocation/descriptor pin 继续由同一 context 的 submission fence 退休，native 对象引用不取代这些 pin。取消仅允许 draw 消费前执行；abort 清理未确认注册，提交未知仍保留原 owner。数组、引用、journal 和排序临时副本均计入 OIT 预算。

UPLOAD 列表来自真实 buffer/suballocation owner。上传堆 uniform 使用实际 graphics binding slot，`TerrainGlobals` 和 `TerrainSections` 使用专属角色。写映射关闭才推进内容版本，未关闭映射、无法覆盖读取范围、已退休 allocation 或失效 recording 返回明确的 unavailable。原 backing pin 和 allocation 随实际 submission fence 退休，未知提交继续保留。native 将已登记的角色逐项对照实际绑定、globals 与 candidates 的 object/offset/length。`Globals` 等 DEFAULT 堆 uniform 由 native 在原 draw 边界按实际绑定范围录制 GPU copy。

native 在原 draw 前映射实际 UPLOAD candidates，按每条 40 字节 section 记录核对 VBO base vertex、meshlet descriptor offset/count 与已登记读取范围。此处没有 GPU 读回，也没有复制完整 VBO/atlas；descriptor 内部的最终 shader 读取闭包仍需完整 replay 预检确认。UPLOAD 在实际捕获点复制精确范围，sampled 材质按 SRV 子资源冻结；GPU 输出内容与完整执行事件尚未接齐，receipt 保持 `UNSUPPORTED`，不发布可消费的 Frozen 或 corrected 输出。

设备侧 sampled texture 内容 owner 让同一纹理版本的真实 SRV 共享覆盖和私有 backing，首次冲突写入前按完整已登记子资源保存；同 recording 依靠 GPU 命令顺序，跨 recording 或 CPU 写入必须等待真实 fence 完成。清屏、上传、copy、附件 draw、compute，以及外部 terrain、surface blit/sharpen 和 SMAA copy-back 已在实际写点接入。Composite 边界已调用内容采集，先校验全部纹理布局，再登记各输入的 owner/version。D32 支持限于单采样、单 plane、单层 2D 的 `R32_TYPELESS` backing 与 `R32_FLOAT` SRV，保存资源保留深度标志，清深度和 depth attachment 写入都先保存旧内容。缺少 pipeline depthWrite 字段时首次 draw 保守处理 active D32 depth，可能使只读深度 pass 多一次保存；实际 GPU 保存与退休仍缺独立运行证据。

terrain 采集只保留实际不可变 CPU facts。旧入口无法消费完整执行流，不按每 draw 分配四 lane 的全屏 tile scratch，也不为该入口触发 tile 管线准备。scratch 由完整执行计划在内容和事件预检通过后取得，并按 resident batch 复用；native 旧 `BatchV2/V3` 入口明确返回 `UnsupportedContent`，不把局部三阶段录制当作完整结果。

### 完整 V2 事件流与 Composite 边界

V2 的 terrain stage receipt 在该阶段的原 draw 录制成功后生成，记录实际 PSO 的 shader generation。只有绑定历史采集的 ACCUMULATE draw 会同时校验自己的 V1 history receipt。JVM 用 ACCUMULATE 的 V2 receipt ID 找到对应 drawGroup，重复几何选择各自保留身份。producer scope 与 render pass 分开记录，frame 自有的 depth/bounds copy、cull 与 terminal recipe 直接归 frame。copy/cloud/water 等操作的 GPU 顺序由实际 native adapter 的命令序号确定。

当前 JVM seal 消费真实 journal，通过 V2 begin/append/query/terrain-receipt/seal/abort 入口提交边界与 observation；旧 V1 空 stream seal 已移除。scope 与 pass 的计数、EMPTY/FAILED 和 manifest 随实际成功 append 推进。terrain receipt 按 nativeSequence 计算每个 sourceDrawId 的 commandOrdinal，零 shaderGeneration 保留实际语义；receipt 覆盖不完整时保留 RECEIPT 缺项。

Composite 创建 render pass 前，JVM 校验当次 color、main depth、Q、bounds、coefficients、sampler、target 和 compiled shader，并提交 `OBJECT_ONLY` 资源行。回放从四路 scratch 取得 Q 与系数输入，main depth 来自 opaque seed 及有序操作。现有 `OitCaptureExecutionTextureV1` 供需要按版本读取源纹理的调用方取得 native Frame 生成的 captureId、owner/version 与子资源范围。

每个 capture 只登记自身读取的子资源计数。seal Unavailable、Frame seal 缺少 sealed 执行流、stream/frame abort、reset 和部分捕获失败都会撤销该计数，避免原 Composite 的颜色/深度写入为无消费者输入生成副本。共享版本的其它 read 保留计数；read、descriptor 与已经录制的 GPU copy 资源继续由 recording lease 保活到实际 fence 完成。提交未知保留 owner。

普通 depth/bounds 操作使用 V2 的 `OBJECT_ONLY` 纹理行传递 backing handle、子资源、角色与访问方式。depth copy 采样 main depth，写对应 terrain/cloud DSV。water mask 对 terrain depth 使用 `READ_WRITE`，cull 采样原 bounds，写 culled bounds 并读写 main depth。native 从原 draw 的 packet 核对实际绑定，在 seal 按录制顺序检查前驱，失配时报告 `GAP_READ_SET` 和实际 receipt。FFM 按单事件最多资源行数分配缓冲，循环使用，每次 append 后由 native 保留对象租约。

ABI31 对原版全屏三角、depth/bounds blit 和 bounds cull 认证实际展开源码、编译定义、正常 Y 方向、PSO 状态和描述符。seal 仅在已认证的 blit 实际执行 `Draw(3, 1, 0, 0)`，且 source、destination、viewport 与 scissor 均为同一帧的全尺寸单 mip、单采样视图时生成 lane copy。lane copy 以同尺寸、同 backing format 的 scratch array 为输入输出，一次 `CopyResource` 覆盖全部 resident tile 及 guard。bounds cull 在原 draw 前冻结 64 字节 Projection。seal 核对原 bounds copy 前驱后，回放用 `GREATER_EQUAL` 写 culled bounds 和 main depth，保留未通过深度测试的值。

journal 完成后追加 frame 自有的 terminal `NORMALIZATION`。资源列表按 color / main depth / Q / bounds / coefficients 排列，main depth 使用 `RESOURCE_MAIN_DEPTH`，读集按实际数量进入 query 与 manifest。native 核对对象、当前资源版本、角色、格式和尺寸，并保活 pipeline 和 sampler。`mcdr_oit_execution_normalization_v2` 为 224 B，保存认证信息，`OBJECT_ONLY` 形式的 captureId 为零。ABI34 接收调用方提供的 RGBA32_FLOAT 输出纹理，完整覆盖视口后才写入结果。

认证信息由 JVM 的源码与实际编译结果检查产生，`Certified` 在构造时解析 source hashes，并计算 contractIdentity 的 UTF-8 SHA-256；每帧编码只复制已缓存值。主 Composite PSO 通过 `CreateOitNormalizationPipelineV1` 创建，native 校验 136 B 认证描述和真实管线描述，同次保存不可变认证 owner。V2 逐项核对该 PSO 保存的 generation、targetCount、certificationVersion 和三组 hash；普通管线没有此 owner 时拒绝消费。device 只保活 owner，源码规则归 shader 域，认证匹配归 OIT 域。

V2 seal 对 adapter、opaque seed、selection、read set 与 normalization 分别检查。检查全部通过才发布可执行句柄。最终显示的 corrected view 与 source mapping 由实际 Composite 绑定核对，draw 成功后记录当帧 `COMPOSITE` observation。

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

三阶段的 CPU 选择事实从现有 terrain prepare/draw 取得。正常 `DEPTH_BOUNDS`、`TRANSMITTANCE` 保留自己的 PSO；`ACCUMULATE` 保留已有 history capture。一次原 draw、frontend invocation、跨阶段 drawGroup 与四个 lane 分别保留身份，native receipt 绑定实际执行的那一次 draw。跨阶段关联使用真实 producer owner、完整有序 command/section/instance/primitive selection、geometry generation、buffer owner/range 和材质身份，不能用各阶段的数组位置配对。不同 OIT stage 可以使用各自的 pipeline；关联键是同一 frame、producer scope、`TerrainSelection.groupId`、selection、材质和几何事实，每个 stage 仍必须单独具备有效 pipeline 与 receipt。terrain stage 只有在 `Available` receipt 的 `contentState` 为原始内容、replay read set 为 `REGISTERED` 且 invocation、selection、材质和 `TerrainSelection.groupId` 全部相符时才算完成；depth copy、bounds copy、cull、transmittance、accumulate 还要按纹理对象/子资源和事件序号串成同一条读写链，缺任一事实就保留 `SEALED_EVENTS_INCOMPLETE`。

单事件 replay 由 `mcdr_dx12OitExecuteTileStreamV2` 调用，每次执行原流中的一个 draw stage，并沿用实际 shader、绑定和内容 owner。分桶 viewport 必须匹配原 draw，回放保留原深度范围，并为每个数组层绑定平移后的原 scissor 与 tile 有效范围及 guard 的交集。lane 开始时清屏，后续 draw 使用 `Load`。旧 tile 准备入口按原契约返回 unavailable。

### 实际命令、内容与 adapter 的实施契约

device 拥有通用 `GraphicsDrawPacket`，保存真实 IA/indexed/indirect/mesh 参数、VB/IB 范围、PSO/root、descriptor、viewport/scissor、push constants 和 attachment。Frame 通过 context 局部回调接收实际命令，device 不导入 OIT。准备发生在原 draw 校验之后、GPU 命令录制之前；原命令与所属 declared pass 成功完成后才确认 receipt。multi-draw 为每条实际命令生成 receipt，前端 scope 只表达调用归属。

统一 receipt 分开记录命令是否 Recorded/Empty/RecordingFailed、内容是否 Frozen/Unsupported/Stale、adapter 是否 Ready 或缺少 topology/binding/addressing 支持。Recorded 表示命令已录入当前 command list，提交与完成继续由真实 fence 管理。terrain 与普通 operation 使用同一 Frame 内的 native sequence 排序；旧 V2 terrain receiptCount 保持原语义，全部 operation/seed 计数通过新增版本接口公开。

ABI25 的 `mcdr_dx12OitAppendExecutionOperationEventV1` 将每条真实 operation receipt 与一个 V2 事件关联。Frame 按 receiptId 查询原命令，仅接受已完成 operation 中已确认且 input pass 完成的 packet，逐字段检查帧、recording、来源、命令序号、PSO 和 generation；重复消费返回 stale。stream 独立持有 packet 的强引用，seal 按统一 nativeSequence 合并 terrain 与 operation，生成只读命令表，漏项返回原始 receiptId。前端嵌套调用按实际命令归属，不按返回次序执行。事件的 `receiptCount/receiptIds` 为零，旧 query、manifest 和 seal 的 expected/observed 仍只统计 terrain。ABI26 的 terrain packet 另外保存 native stage adapter 的 source/tile identity、generation 和 route 租约，批次准备会再次核对这些值，避免 route 或 PSO 换代后误消费。

JVM 按实际 operation receipt 数量计算事件容量，并在单次 seal 的 Arena 内复用一个按布局分配的 receipt 缓冲。native 同步复制字段，保留实际 packet。执行流通过阶段回执索引取 terrain packet。只有原帧的 opaque seed、附件和所有回放读取全都通过校验时，seal 才返回可执行句柄。

外部读取沿用精确字节范围的 buffer content 与 sampled texture content owner。terrain 的 visible/arguments 由真实 batch lease 保活，不逐 draw 复制。main/terrain/cloud depth、bounds、culled bounds、coefficients 和 Q 属于 lane 内部资源，packet 保存角色及前驱版本，重放时绑定对应 scratch。depth/bounds copy 默认重放实际 shader；只有其数学已独立认证时才替换为原生 copy。water 两次 occurrence、cloud depth 初始化与 cloud geometry 分别记录，保持原执行顺序。

opaque/cutout seed 捕获从首个 world depth clear/draw 之前开始，到原 `executeOit` 入口结束，覆盖实际地形、实体和其它 world-depth writer。ABI27 在世界帧开始时登记实际 D32 depth，随后才进入 LevelRenderer 的 world clear 和 draw。登记属于 native 世界帧，与 motion 开关独立。最终单采样 depth 无法补回四 lane 的 coverage 和 alpha discard。shader adapter 同时保存全局屏幕计算与 scratch 局部附件地址。

ABI32 的 opaque seed 路由按原版管线对象和展开源码哈希认证，包括实心与 cutout 的方块和地形、地形 multidraw、已认证的实体、opaque 粒子与 cutout 物品。depth-only tile PSO 保留原像素着色器所需输入和 alpha discard。普通 IA 保存实际读取范围，地形分桶读取真实 Work Graph 输出。透明累积的原版、capture、guide 及 guide+motion PSO 共用对应 stage 的 tile route。

需要 OIT 重建且程序已发布的世界帧，在首个 world clear 前开启 seed journal，最多采集 4096 个事件并使用现有 OIT 共享预算。主 terrain WG 与 IA/indirect/mesh 的真实 depth draw 进入同一定向 observer。ABI29 在 `executeOit` 入口封口，首帧 motion 尚未就绪也会关闭 observer。V2 seal 核对 clear 和 draw 后更新 `GAP_OPAQUE_SEED`，完整回放还需冻结内容和 adapter。

完整 seal 在任何 tile 命令录制前，检查 seed、全部 receipt 的唯一消费与原顺序、内部资源版本、外部 frozen 内容、shader 路由、ordered run、预算和真实资源。准备失败不录制 replay；GPU 命令已经部分录制后的失败归 RecordingFailed，拒绝提交该列表。terrain 提供单 stage 录制入口，清屏归 lane 初始化；GPU 按 primitive/instance/section/source ordinal 分桶，refs 不足时按 primitive chunk 继续，禁止丢弃 overdraw。四 lane 使用固定 resident batch scratch 完成各自 normalization 后平均。

### 录制拒绝与释放

已知录制失败在 `Close/Execute` 前拒绝提交，属于未提交的失败；真实 `Execute` 后的提交或 signal 结果未知继续保留资源。同步提交和异步结算通过版本化 typed submit/settle/status 把两种结果传给 JVM，只有实际提交携带 context fence。已知拒绝不推进 fence、不执行读回成功回调，也不把临时上传绑定到伪造的提交。异步 worker 归还所有权后，取消该 display ticket，再等待此前真实提交的 fence并丢弃失败列表；checked destroy 确认 native context 已销毁后，才能释放 encoder 登记的 timing 与其它资源。正常 draw 和 context control 不额外查询录制状态。

丢弃失败列表后，当前 JVM encoder 进入终止状态。meshlet residency 等 owner 在录制上传后就已发布 CPU offset，丢弃 GPU 命令不会撤销这些发布；允许下一帧直接继续会读取尚未上传的内容。当前决策是失败立即终止并完成释放，可复用恢复需要所有写入 owner 的失效或回滚契约。读回取消交给真实 owner 清理，截图自有缓冲与 Tracy 长期持有的缓冲分别处理，不能执行成功回调代替取消。

## 归一化输出

tile executor 输出已经完成 normalization 的 premultiplied transparency。四个 lane 全部完成后发布独立 corrected target，普通 composite 只合成一次。任意 lane、stage、producer 或内容 lease unavailable 时，保留完整当帧原版透明颜色；不复用旧贡献、不平均未完成 lane，也不把 OIT motion/provenance 缺口写成有效 FG guide。

JVM 按 64×64 像素分 tile，每组最多 16 块，组内四 lane 顺序复用 68×68 数组层。每组的 FFM 参数复用渲染线程缓冲，尾组按实际 tile 数分配数组层。native 用实际的 1／2／4 张系数纹理选择归一化程序，平均覆盖全幅后才发布 RGBA32_FLOAT。Composite 使用 `NormalizedTransparencySampler` 和原 `DepthBoundsSampler`，沿用原坐标映射并直接合成预乘色。

窗口尺寸变化后，native 的 OIT begin 会在预算检查前释放已完成 fence 的旧录制资源。若此时仍有在途资源占满预算，本帧使用原合光。seal 缺项或 tile 回放预算不足时，尝试 V3 材质 RGB resolve。V3 也达到预算上限时，Composite 使用当帧原始 Q 和系数。已经录制的 tile scratch 保留到 fence 完成，只有完整覆盖的四路结果才能发布。命令录制契约错误会拒绝该帧提交。

## 当前实现边界

### 顶点读取范围的证明

terrain mesh shader 的属性解码把分量数传入读取函数，32 位属性按 1/2/3/4 个 word 选择 `Load/Load2/Load3/Load4`，16 位属性按所需 word 数读取。原版 28 字节 BLOCK 顶点的实际区间为 Position `[0,12)`、Color `[12,16)`、UV0 `[16,24)`、UV2 `[24,28)`，最后一个顶点不会再因固定 `Load2/Load4` 读取下一 allocation。三个 OIT stage、normal/flipY、16/32/64 quad 共 18 个真实 format mesh 变体已通过 DXC。通用格式的 read-set 仍须保存实际 byte interval；不能用单个 boolean owner 宣称读取完整。无法证明的格式在 tile 预检阶段返回 typed unavailable，原版 draw 仍按现有路径执行。

实现必须在 native、JVM shader 路由和显示 guides 之间共享同一版本化 packet/producer/stage 事实。排序 index、FEATURE/BORDER capture 和 terrain packet 只是输入事实；在 tile executor、真实内容冻结和四 lane depth/coverage 完成前，不得宣称 TRANSMITTANCE alpha/coverage AA 已交付。

native `BatchV2` replay 已声明 binning 四个步骤与三个 replay stage 的实际资源状态。stage 0 的唯一颜色输出必须映射为原 `bounds`；stage 1 按实际 shader 系数布局映射 `coefficients[0..targetCount)`，target 数只接受 `1/2/4`；stage 2 的唯一颜色输出必须映射为 `q`。culled bounds、terrain/cloud depth 和 normalized scratch 输出不属于这三个几何 stage 的 MRT。原版独立 cull、深度复制/绘制和 normalization 事件目前尚未进入 `BatchV2`，所以正确映射三个 stage 后仍返回 `UnsupportedContent`，诊断为 `Tile V2 lacks sealed depth/cull/normalization events`；此检查发生在 binning/replay GPU 命令录制前。

JVM 事件旁表根据真实 draw 记录 water/cloud depth copy、bounds copy/cull、water geometry 和 composite，并保留实际源、目标和采样绑定。native 按原命令顺序核对内容租约和附件，再用与原 `oit_composite.fsh` 相同的数学逐 lane 求预乘透明色。RGB records resolve 保持原 Q 量纲。

tile envelope 固定为 64 字节：`pixelBounds`、`identity`、`draw`、`properties` 各 16 字节。CPU 上传、native 范围校验和 HLSL StructuredBuffer 使用同一布局；DXC 对 node/scatter 的反射已确认 stride 为 64。GPU 命令录制后的失败使用 `RecordingFailed`，JVM 将其转为 Rejected 并终止本帧；录制前的 `UnsupportedContent` 与 `UnsupportedRoute` 分别保留自己的原因。

JVM terrain producer 的内部 facts schema 2 按 section 保存真实 draw fact、atlas descriptor base 和不可变 geometry，meshlet 与量化 bounds 由 geometry 提供。该事实层不持有 native visible/arguments owner，不生成 tile regions，也不发布可执行 packet；缺失时保留 typed unavailable。非 capture 的 prepare 不建立 `TerrainPreparedFacts` 或 descriptor-offset 数组，真实 capture 的数组在本次 prepare 独占并按保留边界复制。源码优化减少了 CPU 展开、遍历和分配，本轮未测 FPS。

tile resource owner 按枚举顺序校验三个 stage，直接把 bounds、实际 coefficient targets 和 Q 作为对应 RTV，禁止为 stage 分配另一组无关颜色附件。该 owner 仍须由实际 producer 提供 tile regions、envelope 与 stage 程序才能创建；资源分配成功不发布执行完成。native replay 使用 packet 保存的材质绑定、push constants 和实际读取范围，单 resident tile 的 SRV 也保持 Texture2DArray。

每个 lane 使用三张独立 D32_FLOAT Texture2DArray。普通透明绘制读 `mainDepth`。有水遮罩的地形和云分别使用在原命令位置复制的 `terrainDepth` 和 `cloudDepth`。云在 bounds cull 和 cloud depth copy 之后写 `culledBounds`。归一化输入从同一 lane 的 Q 和系数 RTV 取得。
