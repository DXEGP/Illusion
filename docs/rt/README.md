# 光线追踪：配置、日志、查询、导出、G-Buffer 与 B-Mega 出图

B-Mega 软遍历后端已能把光追结果画到屏幕上（§9）：几何与材质导出 → G-Buffer → 逐像素线性扫描 →
合光上屏，全部在 JVM 侧接线，native 只提供通用的 compute / 多 RT 能力。DXR 与 Work Graph 两个
加速后端还没接，速度与画质都属于 S2 的「结构正确」级别。这一篇写**已经能用**的六样东西：
`[raytracing]` 配置键、启动日志、两条查询接口、导出缓存、G-Buffer，以及逐帧派发与合光。
世界几何的采集与 meshlet 在 [`mesh&vrs/README.md`](../mesh&vrs/README.md) §2.1 里。

## 1. 配置

全部在 `config/dxr_mc-client.toml` 里：`[raytracing]` 段的键改完不用重启，下一次 `pushToNative()` 生效；
`[shaderpack]` 段只有一个键，决定光追用哪个包的四份 HLSL，见 §11。

`[raytracing]` 这组键是**包作用域**的：界面入口只有「视频设置 → 光影包 → 光影包设置」，由包在
`shaders/configs.json` 里声明要暴露哪几项（§11.6）；TOML 是这些值的存储位置，不是给人手改的开关面板。

| 键 | 默认 | 取值 | 作用 | B-Mega 生效 |
| --- | --- | --- | --- | --- |
| `backend` | `AUTO` | `AUTO` / `MEGA` / `DXR` / `WG` | 只允许**下调**；`AUTO` 由能力与验证状态决定。`WG` 目前没有实现，选它等于 `MEGA`（见 §16.7） | 是 |
| `qualityProfile` | `BALANCED` | `VALIDATION` / `BALANCED` / `QUALITY` | 档位只调参数，不跳门 | 否 |
| `spp` | `1` | 1–16 | 验证 profile 是 1 | 否 |
| `maxBounces` | `2` | 1–8 | 路径最大弹射 | 否 |
| `samplingMode` | `BSDF` | `BSDF` / `COSINE` / `RESERVOIR` | `RESERVOIR` 只用于间接漫反射 | 否 |
| `voxelTrustRadius` | `8.0` | 0–32 | 体素轨的信任半径（米） | 否 |
| `hzb` | `false` | 布尔 | HZB 剔除（第一版只靠 `InstanceMask`） | 否 |
| `asTrace` | `false` | 布尔 | compute 档把可见性判断换成加速结构（同一条 kernel，画面应逐像素不变）；DXR 档自动恒开 | 是 |
| `historyFrames` | `16` | 1–64 | 历史长度上限，降噪与缓存都夹到它 | 否 |
| `debugView` | `NONE` | 见下 | 调试视图 | 是 |
| `invalidationLog` | `false` | 布尔 | 每次历史失效打一行原因位 | 否 |
| `[shaderpack] active` | `B-Mega` | 包名；空串表示无光影 | 光追用哪个包的四份 HLSL；由光影包界面写入，选中时同时把 `backend` 改成 `MEGA` | 是 |

`debugView` 取值：`NONE` / `DEPTH` / `NORMAL` / `ALBEDO` / `ROUGHNESS` / `HIT_DISTANCE` /
`DIRECT` / `INDIRECT` / `CACHE_CONFIDENCE` / `REACTIVE_MASK` / `MOTION_VECTORS` /
`HISTORY_REJECTION` / `TRAVERSAL_STEPS` / `DUAL_TRACK` / `GEOMETRY` / `BLAS`。
B-Mega 真正接通的只有 `DEPTH`（第五张线性深度附件）、`ALBEDO`、`NORMAL`（`debugChannel` 模式 4：
`0.5 + 0.5 * n`）、`ROUGHNESS`（`debugChannel` 模式 3，取 `g_NormalRoughness.w`）四个，外加 S3 的
`BLAS`（实例盒线框，§14.6）；
其余枚举值切过去画面会停在合成结果上（不铺任何调试图），它们等各自的 RT pass 出现才接线。

「B-Mega 生效」列是本轮代码的事实：`[raytracing]` 里只有 `backend`、`debugView` 与 `asTrace` 被读（`D3D12Settings.rtBackend` / `rtDebugView`，`D3D12RtFrame` / `D3D12RtGBuffer` / `D3D12RtGeometryPool` / `D3D12RtMaterialTable` / `D3D12RtTrace` 五处判定 + `asTrace` 在 `D3D12RtFrame` 与自检里各一处），其余七个键（`spp` / `maxBounces` / `samplingMode` / `hzb` / `historyFrames` / `voxelTrustRadius` / `invalidationLog`）只是**为后续阶段预留的配置面**，改它们不会改变画面上任何一个像素。B-Mega 固定 1 spp、一次弹射（`mega_trace.hlsl` 里 `RtTrace` 只发一条弹射射线），不要在 S2 的调参上浪费时间——要这些参数生效得等 S4 的降噪/时域与 S6 的体素轨。

## 2. 日志

设备创建后第一次 `pushToNative()` 打两行：

```
RT caps: rt=12 mesh=10 wg=10(queried) vrs=2 sm=0x69 ser=0 dxc=1 sched=WG note=ok
RT config: backend=AUTO mode=3 profile=BALANCED spp=1 bounces=2 sampling=BSDF hzb=false as=false
RT config: history=16 view=NONE invlog=false trustRadius=8.0 pack=B-Mega
```

- `wg=…(queried)`：`unqueried` 表示 `D3D12_FEATURE_D3D12_OPTIONS21` 查询失败，此时
  `workGraphsSupported` 一律为 false——"没问成"和"不支持"必须分开。
- `ser=0` 恒成立：本工程的 Windows SDK（10.0.26100）没有 `OPTIONS22`，等换 `directx-headers`。
- `sched=…`：**只看能力**的首选后端（WG → DXR → MEGA）。真实后端还要乘验证状态与用户选择。

关闭设备时打一行影子跟踪汇总：

```
rt state shadow: transitions=… firstUse=… uav=… tracked=… mismatches=… promotions=… widened=… <note>
```

四个计数器的分工：

| 字段 | 含义 |
| --- | --- |
| `transitions` | 经过跟踪器的屏障条目总数 |
| `firstUse` / `tracked` | 首次遇到、只登记不判错的资源数 / 账本里当前资源数 |
| `promotions` | 账本记的是 `COMMON`（D3D12 允许隐式提升到任意读状态，不需要屏障）——**正常情况下它很大** |
| `widened` | 声明的 `before` 是组合掩码、而账本只有其中单个状态（子集关系），合法 |
| `mismatches` | 以上两类都不是的真不一致，附 `<note>` 里的 `decl=/track=` |

2026-10-29 16:49 的实机基线：`transitions=1392095 firstUse=4221 uav=0 tracked=4221 mismatches=0 promotions=607942 widened=0`。
`promotions` 占四成多、`widened=0` 说明真实用到的合法写法是"从 COMMON 隐式提升"，组合掩码那条路本机没走到。

## 3. 查询接口

```kotlin
MCDR_ABI.D3D12QueryRtCaps()?.let { caps ->
    if (!caps.workGraphsSupported) { /* 退回 DXR 或 MEGA */ }
    caps.capabilityBackends      // 能力侧候选，按优先级
    caps.preferredBackend
}

MCDR_ABI.D3D12QueryRtTrackStats()?.let { stats ->
    if (stats.mismatches > 0) IllusionLogger.warnMessage("rt state: ${stats.note}")
}
```

两个函数都返回 `null`：前者表示设备还没建或 ABI 缺失，后者表示跟踪器没数据（尚未提交过屏障）。

## 4. 屏障

状态转换一律走 `D3D12Barrier(ctx, descs)`，不要直接调 native 的 `ResourceBarrier`；
`before == after` 会被丢弃，split 必须成对。细节见
[`misc/backend-and-device.md`](../misc/backend-and-device.md) §3.9。

## 5. 阶段 0 的两项实机确认

1. 进世界跑够 500 帧后正常退出，看日志里 `rt state shadow: … mismatches=0`；
2. 3 张 UAV 的 compute 在 S2 接上后，看同一次启动里没有 `dispatchComputeEx` 的报错行。

## 6. 源码位置

| 位置 | 内容 |
| --- | --- |
| `d3d12/extern/MCDR_ABI.kt` | `D3D12QueryRtCaps` / `D3D12QueryRtTrackStats` / `D3D12Barrier` / `D3D12CreateComputePipelineEx` / 第 12 节的七个加速结构入口与 `RtGeometry` |
| `d3d12/extern/MCDR_Layout.kt` | `mcdr_rt_caps`(80 B) / `mcdr_rt_track_stats`(92 B) / `mcdr_barrier_desc`(40 B) / `mcdr_rt_geometry`(64 B) |
| `misc/settings/D3D12Settings.kt` | 配置解析、`RT caps:` 与 `RT config:` 两行日志 |
| `IllusionConfig.kt` | `RtBackend` / `RtQualityProfile` / `RtSamplingMode` / `RtDebugView` |
| `dxrmc/rt/D3D12RtGeometryPool.kt` | 顶点 / 索引 / 材质三块导出池、64 B 段记录 |
| `dxrmc/rt/D3D12RtMaterialTable.kt` | 图集精灵 → 40 B 材质记录、逐 quad 材质索引 |
| `dxrmc/rt/D3D12RtGBuffer.kt` | G-Buffer 五张附件 + 自己的深度、待重放批次、失败计数 |
| `dxrmc/rt/D3D12RtGBufferShader.kt` | 地形 VS/PS → `ms_main` + `gbuf_main` |
| `dxrmc/rt/D3D12RtTrace.kt` | 主光线 + 一次弹射 compute、四场景自检 |
| `dxrmc/rt/D3D12RtFrame.kt` | 逐帧派发、合光、调试视图、镜像与常量槽轮转 |
| `dxrmc/rt/D3D12RtFrameConstants.kt` | `WgaFrame`（416 B）的 Kotlin 镜像 |
| `dxrmc/rt/D3D12RtShaderSources.kt` | 按包读 HLSL、展开 `#include`、三级覆盖查找 |
| `dxrmc/rt/D3D12ShaderPacks.kt` | 光影包发现、`pack.json` 元数据、兼容性、选中与应用 |
| `dxrmc/rt/D3D12RtStats.kt` | `rt_stats.json` 行与 `rt stats:` 日志 |
| `dxrmc/rt/D3D12RtBlas.kt` | 第 13 节的加速结构：每段一条 BLAS、每帧一条 TLAS、实例表与压缩 |
| `mixin/render/LevelRendererRtDebugViewMixin.java` | `debugView` 把附件铺到主目标 |
| `d3d12/render/advanced/mesh/D3D12SectionGeometry.kt` | 上传采集入口、`Layer` 枚举、契约视图 |

## 7. 导出几何缓存（S2）

`D3D12RtGeometryPool` 在上传时把每段每层的顶点、索引与逐 quad 材质索引复制进三块只增池，供
B-Mega 的线性扫描 compute 当 `ByteAddressBuffer` 读。**只有 `[raytracing] backend` 不是 `AUTO` 时才工作**，
默认玩家零开销。

为什么不直接复用原版 Uber 缓冲的切片：切片只在提交当帧有效，Uber 缓冲扩容或整理会让句柄失效
（`D3D12SectionGeometry` 的头注释记录了这条）。跨帧稳定可读的缓冲要等 S3 的
`mcdr_dx12BufferGpuAddress` 与 AS 管理器。

用法：

- 写完几何不用通知谁——`captureUpload` 在 `geometryVersion` 变化时自己调 `publish`；
- `D3D12RtGeometryPool.summary()` / `layerSummary()` / `validate()` 是一行的规模、分层与自检；
- 周期日志（每 1024 次上传）末尾追加 `rt geometry pool: …` 与 `bad=`，验收看这两个数。

验收口径：`triangles=` 与同一行的 `section geometry: … triangles=` 同量级（池只收 2-a 生效后上传的段，
首次会偏小）、`bad=0`、`skipped=` 持续增长（说明没在重复拷贝）。

容量与取舍（三块池各自封顶：顶点 768 MB、索引 96 MB、材质 96 MB）：

- **半透明层不收**（`hidden=` 计数）。S2 只追踪不透明几何，半透明归 S4 的 OIT；收进来只是白占池子。
  按实机日志，半透明约占上传次数的两成。
- **写满后停止收新段**（`full=true`），不清空。清空会把已经收好的近处地形一起丢掉，而近处正是追踪
  实际要用的那一批——重新上传要等方块变化或重进世界，画面会一直缺几何。停止收段的代价只是远处地形
  在追踪里缺席（那些段本来就被 `TRACE_RADIUS` 排除）。
- 单个 section 的顶点约 80 KB（740 个 quad × 112 B），768 MB 大约够 9000 段；视距很大时仍会写满，
  那时日志会有一次 `rt pool full: v=… i=… m=… MB records=…`，之后不再增长。按 quad 压缩存储（顶点只留位置）留给 S8。

一条记录 64 B，布局：`originX/Y/Z`(int3)、
`layer`(u32)、`vertexOffset`/`indexOffset`(u64)、`vertexCount`/`indexCount`(u32)、
`indexStride | flags << 8`(u32)、`geometryVersion`(u32)、`materialOffset`(u32)、`quadCount`(u32)、
`sectionNode`(u64)。`D3D12RtGeometryPool.recordBytes()` 按 `(sectionNode, layer)` 排序导出。
`flags` 取 `D3D12SectionGeometry.Layer.flag`，`layer` 取同一个枚举的 `ordinal`，所以
`sectionNode * 8 + layer` 是稳定键，四层不会互相覆盖。

**光追是在世界载入之后才打开的怎么办**：段只有在「上传」时才进池，关闭光追时载入的那些段不会经过上传钩子，
池里就只剩打开之后新传的几段（画面上只有脚下一小圈有光追）。`D3D12RtFrame.tick()` 因此带一次性的补救：
段数低于 `GEOMETRY_MIN_RECORDS = 32` 时调 `LevelRenderer.invalidateCompiledGeometry(...)` 让原版作废全部已编译几何、
重传一遍，并打一行 `rt geometry: rebuilt chunk meshes, pool had N sections`。只做一次，不会反复触发。

### 7.1 材质索引与材质表

26.3 的地形顶点缓冲没有方块 id 列：`DefaultVertexFormat.BLOCK` 只有 `Position`(RGB32_FLOAT)、
`Color`(RGBA8_UNORM)、`UV0`(RG32_FLOAT)、`UV2`(RG16_SINT)，共 28 B。所以逐 quad 材质只能由
**UV0 四角平均**反查图集精灵的矩形、再拿顶点 `Color` 当 albedo 乘子推出来。

`D3D12RtMaterialTable` 是一条 40 B 的记录表（`albedo` RGB、`emissive` RGB、`roughness`、`flags`、`metalness`），
表项按 `(精灵索引, 顶点色)` 去重，逐 quad 存一个 **u16** 索引进几何池的材质块：

| 偏移 | 内容 |
| --- | --- |
| 0 / 4 / 8 | `albedo` RGB（线性色，精灵平均色 × 顶点色） |
| 12 / 16 / 20 | `emissive` RGB（线性色，强度取启发式表） |
| 24 | `roughness` |
| 28 | `flags`：bit0 半透明、bit1 次表面、bit2 双面、bit3 脉动自发光 |

表项 0 是兜底（中性灰 0.6、`roughness=0.8`、不发光），查不到精灵或表满的 quad 都指它。

- 颜色一律存**线性**空间：光追在线性空间积分，出图前不要再转一次。
- 表项上限 65536（跟随 u16 索引），比 12 bit 的紧凑 ID 宽：逐 quad 索引本来就是
  u16，没有省位的理由；间接表那一层等 S6 的体素导出需要时再加。
- 烘表在客户端 tick（渲染线程）惰性做一次，结果是不可变快照；UV 候选桶是 128×128 的 CSR 网格，
  查询先取桶再做矩形包含测试，不逐 quad 扫几千个精灵。
- 表就绪之前上传的段只有兜底索引，`tick()` 烘完会调 `D3D12RtGeometryPool.refreshMaterials()` 用池里的
  顶点副本重算一遍——顶点副本稳定，不需要重新上传几何。
- 日志：`rt materials: entries=… sprites=… quads=… spriteMiss=… full=… bakes=… bakeUs=…`。
  `full` 只该在真撞上 65536 项时增长，`spriteMiss` 大幅增长说明 UV 中心没落进任何精灵矩形。

警告：

- 动画精灵（水、岩浆、火）只采第 0 帧，`albedo` 与当前帧的贴图不一致——S2 只要求结构正确。
- 生物群系染色由顶点 `Color` 带进来，草/树叶的颜色因此是对的；但同一方块在不同群系下会拆成
  多条表项，这是去重键的一部分，不是漏算。
- `flags` 的 bit2 双面只对植被类生效；真正的双面求交在着色器里按 `abs(dot(n, d))` 处理。

重建时机：只在 `TextureAtlas` 对象身份变化时重烘。`TextureAtlas.getTextures()` 每次返回新的
- 材质表的 `revision()` 有两处 +1：重烘成功时、**每新增一个条目时**。少加后者会让上传镜像停在首帧那份，
  光追读到的材质缓冲里除了兜底项全是 0（弹射项乘上这个 albedo 就是一片黑）。
`unmodifiableMap` 包装，按 Map 身份比较永远不等——那样会每 tick 重烘一次并全量回填材质，
`rt materials:` 行会以每秒十几条的频率刷屏，`quads=` 累计值也一路涨。

### 7.2 把缓冲绑进 compute（S2 起）

`D3D12DispatchComputeEx` 的 SRV/UAV 表**纹理与缓冲都能收**，缓冲走 raw view，
HLSL 侧就是 `ByteAddressBuffer` / `RWByteAddressBuffer`：

- 只读缓冲：`device.createBuffer(label, usage or GpuBuffer.USAGE_COPY_DST, data)` 一步上传；
- 可写缓冲（UAV）：usage 必须带 **1024**（本工程内部约定，见 native `createBuffer` 的注释；
  不要用 512 —— 那是 `GpuBuffer.USAGE_INDIRECT_PARAMETERS`，撞位会让 UPLOAD 堆资源带上
  `ALLOW_UNORDERED_ACCESS` 而非法）；
- 绑定顺序就是表里出现的顺序：SRV 从 `t0` 起、UAV 从 `u0` 起，`b0` 是 0~8 个 int32 常量。

## 8. G-Buffer pass（S2 2-c）

不透明地形每帧写五张附件，外加自己的一份深度，构成光追要读的 G-Buffer。
只有 `[raytracing] backend` 不是 `AUTO` 时才工作，默认玩家零开销。

| 附件 | 格式 | 内容 | 清除值 |
| --- | --- | --- | --- |
| `rt-gbuffer-albedo` | `RGBA8_UNORM` | rgb = 反照率（图集采样线性化 × 顶点色），a = 1 表示「被 G-Buffer 写过」 | (0, 0, 0, 0) |
| `rt-gbuffer-specular` | `RGBA8_UNORM` | rgb = 常量 0.04（金属性 F0 占位） | (0, 0, 0, 1) |
| `rt-gbuffer-normal-roughness` | `RGBA16_FLOAT` | xyz = 世界空间面法线，w = 粗糙度 | (0, 0, 0, 0) |
| `rt-gbuffer-emissive` | `RGBA16_FLOAT` | rgb = 自发光（当前恒 0） | (0, 0, 0, 0) |
| `rt-gbuffer-depth-linear` | `RGBA16_FLOAT` | rgb = `sqrt(saturate(相机距离 / 64))` | (0, 0, 0, 0) |
| `rt-gbuffer-depth` | `D32_FLOAT` | 本 pass 自己的深度（写、`GREATER_THAN_OR_EQUAL`） | 0.0（reverse-Z 远端） |

反照率的 `a` 是「这一像素有地形」的标记，合光靠它把光栅没画的像素（天空、实体、粒子）退回
`indirect`。清成 (0,0,0,0) 而不是白色：清白会把那些像素当白色反照率乘出一片死白。

**深度必须自己写，不能借主 pass 那份做只读比较**：同一几何在同一帧里第二次绘制，深度值浮点不完全相等，
`GREATER_THAN_OR_EQUAL` 会把整片像素拒掉，画面上是地平线附近一条锯齿白带和近处的楔形漏画。
自己的深度从 0 开始（reverse-Z 远端），第一个通过的片元就是它，不存在相等性问题。

**不要试图把深度附件当 SRV 读回**：`rt-gbuffer-depth` 直接做 compute 的 `t0` 时实测读出来全是 0
（native 已把 `D32_FLOAT` 映射成 `R32_FLOAT` 的 SRV，仍读不到），所以第五张附件专门存线性距离给调试视图用。

肉眼查看：

- `[raytracing] backend` 设 `MEGA`（或其它非 `AUTO` 值）打开光追；
- `[raytracing] debugView` 设 `ALBEDO` / `NORMAL` / `DEPTH` / `ROUGHNESS`，主目标上直接显示那一张；
  前三张直接 blit，`ROUGHNESS` 要先过一遍解包 compute（见 §9.5）。

改 `debugView` 不用重启游戏：配置文件被监听，几秒内下一次合光就走新档位。

每帧数据流：

1. 主 pass 里每次地形 `DispatchMesh` 成功（`D3D12RenderPassBackend.tryMeshletDraw`）就记一条
   `D3D12RtGBuffer.Batch`——段表 / meshlet 表 / globals 三组句柄加组数，都指向本帧瞬时缓冲；
2. 主 pass 结束（`D3D12CommandEncoderBackend.submitRenderPass`）时若捕到过批次，立刻开一个带
   五张颜色附件 + 自己那份深度的 pass，`colorClearFlags` 全 1（每帧清），深度清成 0.0；
3. `D3D12RenderPassBackend.replayGBuffer()` 逐批重放：重建 globals（组数、`cull = 0`、VRS 档位
   全 1X1）→ `D3D12SetPipeline` 到 G-Buffer PSO → 重新 push 描述符 → `D3D12MeshDrawMeshlets`；
4. End，主目标不受影响。

必须是独立 pass：native 的 `beginRenderPass` 在 pass 已打开时直接报 `render pass already open`，
主 pass 里嵌不进第二段带不同附件的渲染。代价是主目标与深度各多两次状态切换。

重放口径照抄主 pass，只有三处不同：

- **组数用原始 meshlet 数**，不是 `(n + 31) / 32`：G-Buffer 的 MS 恒不带 AS（带 payload 的
  `ms_main` 在没有 AS 时是非法入口），cull 也就在这一遍关掉；
- **深度用自己那份、写深度、`GREATER_THAN_OR_EQUAL`**：不借主 pass 的深度（理由见上）；
- **PSO 的颜色槽是五张 G-Buffer**：`D3D12Device.createGBufferVariant` 只为 `[mesh] terrain` 的
  SOLID / CUTOUT 地形管线建，半透明与 OIT 不建。

着色器怎么来的：`D3D12RtGBufferShader.build` 拿地形管线原来的 VS / PS HLSL 改写——MS 由
`D3D12MeshletShader.build` 生成后多输出一条 `float3 gbufWorldPos`（占下一个空闲 `TEXCOORD`，
相机相对坐标与主 pass 对齐）；PS 从 spvc 入口之前的前缀里捞出纹理、采样器、UV0、Color 字段，
拼上光影包的 `B-Mega/shaders/mega_gbuffer_ps.hlsl` 模板（占位符在运行时替换）得到 `gbuf_main`：反照率 = `pow(采样, 2.2)`（有顶点色再乘），面法线 =
`normalize(cross(ddy(worldPos), ddx(worldPos)))` —— **顺序反了**（D3D 屏幕 y 朝下，`cross(ddx, ddy)`
得到的是朝里的法线，地面会存成 `-Y`）。光照路径按射线方向自己翻面，所以顺序错了只影响存进 G-Buffer 的法线，
`debugView=NORMAL` 会整片洋红。

反照率取「图集采样 × 顶点色」：着色器里按 `register(t<n>, space<n>)` 找 PS 的 `Texture2D` 与同名寄存器的
`SamplerState`，用 `SampleLevel(..., 0.0)` 取 0 级 mip——图集里相邻精灵挨着，各向异性过滤会把邻块像素带进来。
spvc 的声明带 `space` 限定，正则漏掉它就会一路退回常量 `0.6` 灰色（`detail` 里显示 `albedo=constant*…`）。
找到的那一条 varying 名为 `gbufWorldPos`，语义取 `SPIRV_Cross_Output` 里最大 `TEXCOORD` 号 + 1。

打开 `[debug] dumpHlsl` 时这一对产物会落到 `dxrmc/hlsl/gbuffer_ms_<hash>.hlsl` 与
`gbuffer_ps_<hash>.hlsl`：注入点靠正则在 spvc 输出里定位，坏了只有现场产物能拿去离线编译。
编译失败时**不看配置**也会落盘成 `gbuffer_failed_{ms,ps}_<hash>.hlsl`，两个阶段各自独立编译、
各自报错（只报第一个错会让下一个错误等下次启动才暴露）。

日志：附件分配成功一次后打 `rt gbuffer: targets {w}x{h} albedo=RGBA8_UNORM …`；分配失败 warn
四次后静默（`rt gbuffer: allocate failed: … (attempt n/4)`），重放失败打
`rt gbuffer: redispatch failed: …`。

源码见 §6 表里 `D3D12RtGBuffer.kt` / `D3D12RtGBufferShader.kt` / `LevelRendererRtDebugViewMixin.java`
三行，以及 `D3D12RenderPassBackend.replayGBuffer`、`D3D12CommandEncoderBackend.recordGBufferPass`。

警告：

- 只有**不透明地形**写进 G-Buffer：半透明归 S4 的 OIT，实体、粒子、手都还没写。
- 粗糙度整张是常量 `0.8`（`D3D12RtGBuffer.DEFAULT_ROUGHNESS`）：材质表还没进 P/S——mesh 根签名
  的 extras 表固定 3 个 descriptor range（t14 / t15 / t16）加 b8，加第 4 个要动 ABI。
- `specular` 只有常量 F0、`emissive` 恒 0，同样是等材质表。
- 法线来自屏幕空间导数：像素落在三角形边缘时会取到隔壁三角形的值，轮廓一像素内偏色属正常。
- 屏幕空间导数在**平面三角形**上是精确的，但台阶状的方块侧壁会得到「面法线」而不是「斜面法线」——
  这与原版的地形光照不同，S3 接 DXR 后按几何法线取。
- 没捕到地形批次（纯天空、主菜单）就不开这个 pass，`debugView` 也就没有可铺的图。
- `debugView` 的铺屏走 `recordTextureBlit`，它会用线性采样把源缩放到目标尺寸——G-Buffer 与主目标
  同尺寸时就是 1:1 拷贝。
- `MATERIAL` 档位还没有：材质索引还没进 G-Buffer（见上一条粗糙度的原因），`RtDebugView` 里也没有
  这个枚举，`GEOMETRY` 只是给 S3 的几何调试留的名字。
- `DEPTH` 视图铺的是 `g_DepthLinear` 的线性距离（已开平方），不是深度附件的 reverse-Z 原值：
  深度附件当 SRV 读回来全是 0，读不到。
- 追踪半径（`TRACE_RADIUS = 96`）之外的地面拿不到主光线命中，合光直接放过原版像素——远处没有
  光追阴影，与近处之间能看到一条亮度/色调分界，这是镜像范围的边界，不是几何错误。

## 9. 逐帧派发与合光（S2 2-d / 2-e）

### 9.1 一帧的顺序

主 pass 结束 → G-Buffer pass（§8）→ 光追派发 → 合光 → 上屏 blit。四段都在同一条 DIRECT 命令列表里，
只有 G-Buffer 与合光之间的两段需要 `inRenderPass == 0`。

- **光追派发生在 `D3D12CommandEncoderBackend.recordGBufferPass` 的末尾**（`D3D12EndRenderPass` 之后），
  因为 native `beginRenderPass` 只认「当前没有打开的 pass」，而 `dispatchComputeEx` 又不检查
  `inRenderPass`——在 pass 里派发不会被 native 拦住，只会被 D3D12 调试层报错。
- **合光与上屏在 `LevelRendererRtDebugViewMixin` 的 main pass 体 RETURN**：那时 OIT、outline 已合成，
  深度还在，GUI 未画；`debugView = NONE` 走「合光 → blit」，其余视图走「G-Buffer 单图 → blit」。

### 9.2 上传镜像

几何池与材质表各带一个 `revision()` 计数器（`D3D12RtGeometryPool` / `D3D12RtMaterialTable`），
`D3D12RtFrame.mirrorsFor` 把它俩和相机位移一起当键：池写进新段、材质表重烘、或相机移动超过
`MIRROR_SLACK = 24` 方块时才重传。

**镜像不是整池。** `D3D12RtGeometryPool.mirrorNear` 只挑段中心（记录原点 + 8）在
`TRACE_RADIUS + MIRROR_SLACK` 内的段，并按距离由近到远取到上限为止：`MIRROR_RECORDS = 384` 段、
顶点 + 索引 + 材质三块合计 `MIRROR_BYTES = 128 MB`。挑出来的段压成紧凑的 64 B 记录加三块紧排数据，
记录里的 `vertexOffset / indexOffset / materialOffset` 全部改写成紧凑缓冲里的新偏移，
所以着色器的解码逻辑与 §7 完全一致。整池上传是错的：世界加载时每帧都有新段进来，
一次几百 MB 的 memcpy 会把渲染线程按住到「未响应」。

重建有节流：最短 `MIRROR_INTERVAL = 60` 帧一次，并按实测重建耗时自适应（超过 8 ms 就把间隔翻倍、
上限 600 帧；低于 2 ms 就折半退回 60）。重建那一条日志在第一次是 info（`rt frame: mirror records=…
buildMs=… interval=…`），之后降为 debug，避免刷屏。

常量缓冲就是包里那份 `WgaFrame`（416 B，`D3D12RtFrameConstants.BYTES`），建在**上传堆**
（`GpuBuffer.USAGE_MAP_WRITE`）并按 `FRAME_SLOT_BYTES = 512` 分槽轮转三份（`SCENE_RING = 3`）：
单份会在 GPU 还在读的时候被下一帧改写，而 416 B 的内容塞进 512 B 的槽是为了满足 CBV 偏移必须
256 字节对齐。它由 `sceneFor` 单独分配、不随镜像重建；每帧 `map(write = true)` 直接写 416 B，
不再经过 staging + `D3D12CopyBuffer`（上传堆本来就 CPU 可写）。字段偏移全在
`D3D12RtFrameConstants`，与包的 `wga_constants.h` 逐字段对拍（见 §10.1）。

五块 SRV 缓冲（记录 / 顶点 / 索引 / 材质 / 逐 quad 材质）都走
`device.createBuffer(label, 0, data)`（一次性 `writeToBuffer`）；缓冲型 UAV 必须带 usage 位 1024，见 §7.2。

镜像按**两遍**生成：第一遍只挑段、累加字节数，第二遍按精确总量分配再拷贝。这样一次装不下的大段
（单段顶点就有几十 KB）不会把输出缓冲撑爆；`appendSlice` 另有一道「池被重置过就跳过这一条」的保护，
越界只会少一段几何，不会把渲染线程打死。

记录里的 `materialOffset` 存的是 **quad 序号**，不是字节偏移：shader 侧 `RtQuadMaterial` 自己乘 2 取 u16。

### 9.3 派发的两个规模旋钮

B-Mega 是逐像素线性扫几何池，真实世界（万级段 × 每段上千三角形）全分辨率一帧要跑几十亿次求交。
两个常量控制规模，都在 `D3D12RtFrame` 里：

- `TRACE_MIN_DIVISOR = 4` / `TRACE_TARGET_HEIGHT = 160`：辐亮度高度按窗口自适应（854×480 → 除数 4、`213x120`；
  2560×1334 → 除数 9、`284x148`），窗口越大除数越大，单帧耗时基本恒定；合光时最近邻放大。
  实测（854×480、5 段 2580 三角形）`trace = 2.6 ms`；2560×1334（`284x148`、233 段）`trace = 5.6~8.3 ms`，
  成本大致正比于「像素数 × 镜像段数」。
  上限取 4 是为了让阴影在地面上看得出形状——1/16 时一个方块宽的阴影只占 1~2 个辐亮度像素，被放大成一片糊斑。S8 换自适应。
- `TRACE_RADIUS = 96.0f`：只扫段中心（记录里的原点 + 8）在相机 96 方块内的段；0 表示不裁（自检场景用 0）。
  镜像侧另有两道独立上限（`MIRROR_RECORDS = 384` 段 / `MIRROR_BYTES = 128 MB`），半径只是上限之一。
  半径内的地面能拿到太阳项；半径外的地面主光线打不到任何三角形，在画面上是一条比近处暗的带子——
  16 方块时这条带子就在脚下，看起来像「地面坏了」，96 方块把它推到视距边缘（远处还有合光的半径淡出兜底）。

镜像侧还有两道独立的上限（§9.2）：`MIRROR_RECORDS = 384` 段、`MIRROR_BYTES = 128 MB`。
半径决定着色器跳过哪些段，镜像上限决定 GPU 上到底有哪些段可选——两者都要够大才不会在画面里留下暗带，
同时又要有上限，免得一次重建把渲染线程按住。

这几个量不必单独传：着色器从 `WgaFrame` 里取——辐亮度尺寸读 `g_screen.xy`、段数读 `(uint)g_misc.w`、
半径读 `g_misc.z`、帧号读 `(uint)g_cameraPos.w`、调试档读 `(uint)g_misc.y`，相机基与逆投影也在同一份
cbuffer 里（§10.1）。半径裁剪在着色器里是
`distance(origin + 8.0, camPos) > radius` 即跳过整段——**`origin` 是段的方块坐标原点**，
所以要 +8 取中心；顶点 Position 是段内 0..16 的方块单位。

半径内仍有几十个段，逐段做三角形扫描会把 GPU 拖到 TDR，所以每段还要过一遍**段盒粗筛**：
盒是 `origin - 0.5` 到 `origin + 16.5`（0.5 余量吸收顶点溢出），slab 法与射线求交，
`leave < max(enter, 0)` 或 `enter >= hit.t` 就跳过整段。**`enter >= hit.t` 不影响结果**：
盒的入口已在当前最近命中之后，段内的任何交都不会更近。粗筛只剪枝不改结果，
所以 CPU 参考实现不需要同步。

### 9.4 解调纪律

光追着色器只写「到达表面的辐射度」：`gDirect` = 太阳项直接可见性乘积，`gIndirect` = 一次余弦弹射的
结果（未命中给天空色），`gAux` = `float4(命中距离, 命中法线.xyz)`（`w` 恒 0）。**albedo 只在合光里乘一次**：

一次弹射写的是**命中面的出射辐亮度**：`emissive + albedo * (sun 项 * 可见性 + sky 项)`。
漏掉这个 `albedo` 会让背光的第二命中面贡献 0，合光乘上主表面 albedo 后就是地面上的一块纯黑；
末尾的 `sky` 项是「弹射到此终止」的近似环境光（§4.9 允许把超距当环境光），不是第二次弹射。

```
if (direct.w == 0) out = 原版像素          // 没有光追数据：天空、实体、追踪半径外
else {
    color = WgaLinearToSrgb(WgaTonemapAces((albedo * (direct + indirect) + emissive) * g_exposure.x))
    fade  = saturate((radius - hitDistance) / (0.35 * radius))  // gAux.x = 命中距离
    out   = lerp(原版像素, color, fade)                        // 半径边缘淡出，避免一圈接缝
}
```

`WgaLinearToSrgb(WgaTonemapAces(...))` 是整条链里**唯一**的显示变换：主目标格式是 `RGBA8_UNORM`，
原版其余绘制都在 gamma 空间写，线性辐亮度直接写进去会整幅发暗。G-Buffer 里的 albedo 保持线性，不做任何编码。

合光的第 5 个 SRV 是主目标颜色本身、第 6 个是 `gAux`（只用到 `x` = 主光线命中距离）：
命中距离接近 `TRACE_RADIUS` 时淡回原版像素，否则半径处会有一条「RT 地面 / 原版地面」的硬边。`gDirect.w` 为 0（主光线在镜像范围内没打到三角形）或
albedo 的 `a` 为 0（G-Buffer 没写过这个像素）时**原样放过主 pass 的像素**：这样天空还是原版的天空、
实体与粒子还由原版画、`TRACE_RADIUS` 外的远景也保持原版外观。用辐亮度硬填这些像素的后果是
「天空变成常量色、远处出现一条偏蓝地带」。

合光读 G-Buffer 的 albedo（`RGBA8_UNORM`，已是线性空间）与 emissive，按 `gRadianceW/H`
把输出像素映射到降采样的辐亮度像素再取 16 B 三元组——**不能拿输出像素直接索引辐亮度缓冲**，
越界会读到 0 变成黑块。输出写进 `rt-hdr`（`RGBA16_FLOAT`），再 `recordTextureBlit` 铺到主目标。`rt-hdr` 必须按
`USAGE_STORAGE or USAGE_RENDER_ATTACHMENT` 创建：`blitTextureToTexture` 收尾会把**源**切回
`RENDER_TARGET`，只带 `STORAGE` 的资源切到这个状态非法，错误会留到 `Close()` 报 `E_INVALIDARG`
并移除设备（表现是整帧不提交、渲染线程卡在等栅栏）。

### 9.5 调试视图怎么出图

`[raytracing] debugView` 不为 `NONE` 时不再合光，而是把一张图铺到主目标上：

| `debugView` | 源 | 通道 |
| --- | --- | --- |
| `DEPTH` | `g_DepthLinear` | 直接 blit，灰度 = `sqrt(距离/64)` |
| `NORMAL` | `g_NormalRoughness` | `debugChannel` 模式 `4`：`0.5 + 0.5 * rgb` 编到 0..1 再 blit |
| `ALBEDO` | `g_DiffuseAlbedo` | 直接 blit |
| `ROUGHNESS` | `g_NormalRoughness` | `debugChannel` 取 `w` 拉成灰度 |
| `BLAS` | 合成结果本身 | `debugBlas`：把 TLAS 里每个实例的 16³ 段盒画成彩色棱线叠上去（§14.6） |

粗糙度这类单通道数据不能直接 blit（`recordTextureBlit` 只采三通道），要先过
`D3D12RtDebugShader` 的 compute：读源纹理第 `gChannel` 个通道、`saturate` 后铺成灰色写进 `rt-hdr`，
再 blit 上屏。`debugChannel` 在派发前会把 `g_misc.y` 打成 `channel`（`D3D12RtFrameConstants.patchDebugMode`）——
追踪每帧都会把这一位写回 0，不自己打一次就等于永远按通道 0 取。法线档写的是 `0.5 + 0.5 * n`（世界空间法线在 -1..1，直接 blit 会被 `RGBA8` 目标截成 0/1，
地面会因为 `y = +1 → 1`、`x/z = 0 → 0` 变成纯绿甚至全黑；编码后地面是淡绿、朝 +X 的面是淡红）。深度视图铺的是**线性距离**（不是 reverse-Z 深度值），已开平方压过动态范围：
近处 1 方块 ≈ 0.13、16 方块 ≈ 0.5、64 方块 = 1。

### 9.6 逐阶段耗时

`D3D12RtStats` 用一条 8 槽的时间戳池打四个点（G-Buffer 结束 / 光追结束 / 合光结束 / 上屏结束），
每满 5 秒把上一窗口的一行 JSON 追加到 `<user.dir>/dxrmc/rt_stats.json`：

```json
{"time":"…","tier":"brute","frames":…,"width":…,"height":…,"trace_width":…,"trace_height":…,"records":…,
 "triangles":…,"gbuffer_ms":…,"trace_ms":…,"denoise_ms":…,"composite_ms":…,"blit_ms":…,"total_ms":…,
 "cpu_ms":…,"dispatches":…,"vram_mb":…,
 "blas_builds":…,"blas_refits":…,"blas_compacts":…,"blas_ms":…,"blas_build_us":…,
 "blas_vram_mb":…,"blas_scratch_mb":…,"tlas_instances":…,"tlas_ms":…}
```

`tier` 是本窗口实际跑的追踪档：`brute`（compute 暴力求交）、`accel`（compute + 内联 `RayQuery`）、
`dxr`（状态对象 + SBT + `DispatchRays`）——判断「光追有没有效果」先看这一格，不用翻日志。
`blas_*` / `tlas_*` 是 S3 才有的列（`[raytracing] backend = AUTO` 时恒为 0），口径见 13.4 节。


读回点刻意放在**下一帧**的 G-Buffer pass 之前：`D3D12ReadQueryValues` 内部会 flush 并等栅栏，
同一帧里刚写完就读会把渲染线程卡死；上一帧已 present 提交，值一定 retired。读回值不是正数时
这一窗口整行作废，不写 0 进基线。`cpu_ms` 是编码侧墙钟时间（夹住派发/合光函数的 `System.nanoTime`，窗口内按帧平均），
`dispatches` 是每帧 compute 派发次数（光追 1 + 合光 1，调试视图另加 1），`vram_mb` 是自打开光追以来的峰值（关掉光追时清零）——
只统计我们自己持有的那几块（镜像、相机常量、辐亮度、G-Buffer、`rt-hdr`），不含驱动侧对齐与别的子系统。
一个窗口整行作废时（时间戳未 retired），`cpu_ms` / `dispatches` 的累加器也一起清零：它们按「本窗口帧数」求平均，把上一窗的量带进来会算出偏大的均值。

S2 没有加速结构、也没有辐亮度缓存，所以六类基线里「AS build/refit 时间与字节」「缓存命中率与更新量」
这两类在 S2 记为 `none`（不是漏测）。日志同步打一行
`rt stats: total=…ms trace=…ms cpu=…ms`（分阶段数值只进 `rt_stats.json`，日志一行三列）。

S2 实测（854x480 窗口、镜像 5 段 / 2580 三角形、派发 `213x120`）：`gbuffer=0.1 ms`、
`trace=2.6 ms`、`composite=0.08 ms`、`blit=0.01 ms`、`total≈2.8 ms`；2560x1334（`284x148`、233 段）
`total≈6.2 ms`。这是线性扫描 + 自适应除数的数字，只作 S8 的起点，不是 S2 的验收条件。

### 9.7 着色器自检

`D3D12RtTrace` 带一套与 CPU 参考逐像素比对的离线自检，S2 的验收口径（场景 1 空场景、场景 2 单三角形，
逐像素误差 < 1e-5）就是它：

- 先跑探针（调试模式 `8`）：把 `g_screen`、`g_misc.w`（段数）、太阳色、追踪半径、第一个顶点的 z、
  第一个材质的 albedo 原值写进输出缓冲再逐项比对，一眼分清「常量没到」「SRV 表绑错」和「着色器逻辑错」。
- 再跑三个场景：空场景（0 个三角形，全走天空分支）、单三角形、阴影场景。单三角形在平面 `z = 2` 上
  铺满视野的绝大部分（顶点 `(1,1,2)` / `(9,1,2)` / `(5,9,2)`，相机在 `(5,5,-6)` 朝 `+z`），太阳在相机
  一侧：命中像素的 `direct` 必然非 0，未命中只有天空色，所以这个场景能直接区分「GPU 有没有做求交」，
  而不是靠 `aux` 通道侧面推断。**顶点必须落在记录那段 16³ 盒内**——暴力档先按段盒剪枝，顶点伸出盒外的
  三角形会被整段裁掉，GPU 与 CPU 参考就对不上了（同一个坑在引擎级探针上踩过一次，见 16.6 节）。
- 阴影场景是地面（`y = 0`、两块三角形、16x16 方块）加一块 `y = 4` 的悬空挡板（`x 4..10`、`z 6..11`），
  太阳斜射，挡板在地面上投影出阴影（几何必须落在记录那段 16³ 盒子里，否则会被段盒粗筛剪掉）。
  没有它的话，阴影射线即使永远返回「可见」也能过前两个场景；`hits[…]` 里的 `shadowed=` 是被挡板
  遮住的像素数，必须大于 0 才说明阴影真的求过交。分辨率固定 `WIDTH = HEIGHT = 32`，按 `4 x 4 x 1` 派发。
- GPU 与 CPU 参考读的是同一批 `ByteBuffer`，所以自检同时校验 64 B 记录、28 B 顶点、2/4 字节索引、
  逐 quad 材质这四层解码。
- 空场景与单三角形比 `direct` / `indirect` 的四个通道，外加 `aux.x > 0` 的命中掩码。判定用混合容差：
  `diff > TOLERANCE + RELATIVE_TOLERANCE * |reference|`，绝对项 `TOLERANCE = 1e-5`、相对项 `1e-4`。
  只有绝对容差时 DXR 档的阴影场景会误报：实测 `maxDiff=4.96e-5 over=272 maskMismatch=0`，
  超差全在明暗交界附近——那里 `max(dot(n, sunDir), 0)` 本身接近 0，法线末位上的差异被这个乘积放大到 1e-5 量级；
  真正的阴影翻转是 0.3 量级，`maskMismatch` 会同时变正。相对项只放宽到一个 ULP 尺度，不吃掉这种区别。
- 阴影场景只比 `direct` 的四个通道与命中掩码（日志里 `indirect=off`）：一次弹射在 16x16 地面上大量走掠射
  方向，射线与挡板/地面边缘擦边时命中与错过只差浮点末位，两个实现会各自翻转——那不是阴影语义的差异。
  阴影本身完全落在 `direct` 里（太阳可见性），所以这个取舍不影响「阴影对不对」的结论。
- 日志：每个场景一行
  每个场景打三行（一行一个事实）：`rt trace: scene=<场景> maxDiff=… over=… mask=… triangles=…`、
`rt trace: scene=<场景> hits gpu=… cpu=… shadowed=… covered=…`、
`rt trace: scene=<场景> indirect=<on|off> tol=1.0E-5+1.0E-4rel -> pass|fail`，
  前四个场景全部通过才打 `rt trace: self-test passed (4 scenes, tolerance 1.0E-5 rel 1.0E-4)`；
带三角形的场景另要求两侧都**真的有命中**（日志里的 `covered=true`），否则「两侧都没命中」会让逐像素比较恒等 —— 一个空场景证明不了任何事。

自检失败时按场景各打一条：`rt self-test: <场景>/<子步骤> <异常类> at <帧链>`（子步骤是 `upload` / `alloc` / `encoder` / `dispatch` / `reference` / `compare`）；单个场景抛出异常不会中断
其余场景（`SelfTestDeferred` 例外，它表示队列还忙、下次再跑）。改这里的判定前先看 §16.8 的验收清单。。
- 纯 CPU 的那一半可以脱离游戏复跑：`pwsh _port/rt_pure_selftest.ps1` 反射调 `triangleScene` / `recordBytes` / `_reference` 等私有成员，打印三个场景 `direct` / `indirect` / `aux` 的 `sum/min/max/nonZero`。它不需要 GPU，用来在改完缓冲布局或参考实现后确认「场景构造 + CPU 参考」这条链本身不抛异常、且命中像素数合理；GPU 那一半仍要靠引擎级探针（§16.5）与游戏内自检。

自检场景的顶点在 `recordBytes(...)` 里当场断言必须落在 `[origin, origin + 16]` 之内：越界的顶点会让暴力档把整段裁掉、GPU 与 CPU 参考对不上（就是 16.6 节那条不变量），报错是 `scene vertex <i> outside the section box`。改场景几何时这条断言先响，别等逐像素比不过再回头找。
- 自检自带命令编码器与栅栏等待（读回必然阻塞），所以在客户端 tick 上跑，并且先跳过
  `SELFTEST_DELAY_TICKS = 200` 个 tick（约 10 秒）：世界加载排队的工作会让这次等待把 tick 线程按住。
- 自检只要求「有一个活着的设备」（`D3D12Device.current()`），**不必先建世界**：停在标题界面等 200 tick
  就会打印结果，排查管线/显存问题时不用每次进存档。
- 栅栏等待超时（3 秒）不算失败：打 `rt trace: scene self-test deferred: fence wait timed out`，下个 tick 再试。
  但连续 8 次都等不到（`MAX_DEFERRALS`）就放弃并写结论：`rt selftest: gave up after 8 deferrals (…)`，
  `rt_selftest.json` 里 `pass=false`、`detail` 带 `deferred …`。不这样做的话文件永远不出现，看起来像自检根本没跑过。
- 自检失败只记一次、不再重试，也不阻断出图：S2 阶段看的是画面结构，自检是留痕用的证据。
- 失败时紧跟一行取证 `rt trace: detail gpuZero[d=… i=… auxMask=0] cpuZero[d=… i=…] | px=… direct[0] gpu=… cpu=…`：
  `gpuZero/cpuZero` 说明哪一侧整块是 0（一眼分辨「没派发出去」和「参考算错」），后面最多三个超差采样
  给出具体像素与两边的值。

自检结论同时落成 `dxrmc/rt_selftest.json`（`user.dir` 下的一行 JSON）：日志会被后续行冲掉，而这个文件只有一行，读它就知道这一轮走的哪一档、过没过、卡在哪一步。键：`time` 毫秒时间戳、`tier` 本轮实际档位（`brute` / `accel` / `dxr`）、`backend` 配置里的后端序号、`asTrace` 加速结构开关、`pass` 整轮结论（六个场景全过才 `true`）、`step` 最后进入的步骤（`pipeline` / `probe` / `empty` / `triangle` / `shadow` / `as_triangle` / `dxr`）、`detail` 异常类与出错位置（通过时是空串）、`scenes` 六个场景各一条 `{scene, pass, detail}`（`detail` 里带 `maxDiff` / `over` / `mask` / `hits` / `shadowed` / `covered` / `indirect`）、`contract` 几何契约检查的计数、`blas` 加速结构的计数摘要（这两项各写两份：`world …` 是渲染线程跟随世界几何的那一份，`selftest …` 是自检自己建的临时实例；世界还在加载时前者是 `not prepared`，实际查过的数字在后者里；`contract` 只带几何契约、`blas` 只带加速结构计数，两列不会写成一模一样的一段）、`sbt` 状态对象与 SBT 的摘要、`frame` 最近一次派发用的档位与实例数。本轮第一次写之前会把上一轮的文件复制成 `dxrmc/rt_selftest.prev.json`（一轮只挪一次，本轮的 `pending` 不会把上一轮的结论挤掉）；文件在两种情况下也会先落一行：光追整个没开时写 `step` 为 `disabled`（`detail` 带当时的 `backend` 与 `mode`，免得为看结论白跑一趟）；**首帧派发成功后**写 `step` 为 `pending`（说明这一轮到此为止还没出结论）。自检出结论后再覆盖成最终结果 —— 所以哪怕中途崩溃或提前退出，文件里也有档位、几何契约、加速结构与帧派发的摘要。一次运行只读这一个文件就能判断哪一步不对，不必再进游戏第二次。

```json
{"time":1761811200000,"tier":"dxr","backend":2,"asTrace":false,"pass":false,"step":"triangle","detail":"IllegalStateException at D3D12RtTrace.kt:812"}
```


### 9.8 四条硬约束（缓冲读取与管线释放）

- **raw SRV 的元素粒度是 4 字节**：native 按 `NumElements = size/4` 建视图，缓冲长度不是 4 的倍数时
  尾部读成 0。索引按 u16 计，奇数个索引只有 2 字节余量，会把一条边读成 0 得到退化三角形。
  `D3D12RtTrace.upload` 与 `D3D12RtGeometryPool.mirrorNear` 都把长度补齐到 4 字节整数倍。
- **`ByteAddressBuffer.Load` 的字节偏移必须 4 字节对齐**：u16 索引在奇数位置（第 2、6 字节）直接
  `Load` 得到 0。`RtIndexAt` 统一先读对齐的 dword 再取高低半字（`byteOffset & ~3` + 半字选择），
  4 字节索引才直接读。
- **纹理用法位要覆盖 blit 的两端**：blit 的目标要 `USAGE_RENDER_ATTACHMENT`，而它收尾会把源切回
  `RENDER_TARGET`，所以 `rt-hdr` 必须同时带 `USAGE_STORAGE or USAGE_RENDER_ATTACHMENT`。
- **compute 管线只能按资源释放**：`D3D12CreateComputePipeline*` 在原生侧返回 `Dx12Object*`
  （`kind = ComputePipeline`），只有 `D3D12CreateGraphicsPipeline` 与 `D3D12CreateMeshPipeline*`
  才返回 `Dx12Pipeline*`。拿 `D3D12DestroyPipeline` 释放 compute 管线等于按错的类型 delete：
  `Dx12Pipeline` 的 `ComPtr` 与 `std::unordered_map` 会去析构 `Dx12Object` 的字段，读到野指针就是
  `EXCEPTION_ACCESS_VIOLATION`（关掉光追时崩在渲染线程，栈顶是 `D3D12RtFrame.release`）。
  释放一律走 `MCDR_ABI.ID3D12DestroyResource`（延迟销毁，命令列表可能还引用着它）；
  `_port/rt_pipeline_release_check.py` 拦这条。

警告：

- 天空走光影包的解析大气（`mega_trace.hlsl` 的 miss 分支调 `WgaEnvironmentRadiance`），不是原版那张贴图，
  所以天顶/地平线的颜色与原版不完全一致；主光线在镜像范围外没命中任何三角形时合光仍放过原版像素。
- 半透明、实体、粒子还没进 G-Buffer：它们既不写反照率也不遮挡，站在它们后面看到的是追踪出的地形/天空。
- 太阳方向是 S2 占位：仰角固定 59°（与原版亮度观感一致），只有东西方向跟原版太阳走
  （`EnvironmentAttributes.SUN_ANGLE` 经 `sin < 0` 判东/西半边），所以阴影不会随天上的太阳升高而变暗、
  也不会在黄昏时把整片光追区域压暗；太阳辐亮度仍是常量（`SUN_COLOR` × `SUN_STRENGTH`），S5 换真实天空模型。
- 辐亮度缓冲按窗口高度自适应派发（854×480 下 1/4、2560×1334 下约 1/9），边缘有块状放大；阴影越远越糊，
  这是 S2 的临时规模，S8 换自适应采样。
- 只扫相机 96 方块内的段：更远的地形既不投影也不遮挡，`TRACE_RADIUS` 之外淡回原版像素。
- 光追链上的任何异常都不许冒泡到帧图：`D3D12RtFrame.reportFailure` 只记前四次日志，`recordGBufferPass`、调试视图 mixin、池的 `publish` 都包了 try/catch——出图是开发档功能，崩掉整帧比丢掉一张图糟得多


**自检结果的读法**（三条线分开看，别混成"坏了"）：

| 现象 | 含义 | 处置 |
| --- | --- | --- |
| `probe ... -> fail` 且四个数全 0 | 常量缓冲没到着色器 | 先确认 `run/dxrmc/MCDR.dll` 不比源码旧（native 改了要 `MSBuild MCDR.vcxproj` + `gradlew copyNative`）；再确认 `wga_bindings.hlsli` 是包里唯一一份 `register(b0)` cbuffer |
| `probe -> pass`，`empty`/`triangle` 某条 fail | 光线语义或缓冲区布局不一致 | 看同一行的 `detail` 取证行（`gpuZero`/`cpuZero` 哪个 true） |
| `deferred` | 队列还没空闲，下个 tick 重试 | 不用管，等 200 tick 之后的行 |

## 10. 着色器源码与光影包

着色器**不在 Kotlin 里**，也不在 mod 仓库里：S2 的 B-Mega 后端整套源码在光影包
`Illusion-HPT-Shaders\B-Mega\shaders\`（与 `WorkGraph-Advanced\` 平级，后者是 Work Graph 后端的目标形态）。
一个包能不能被选中、选中之后怎么生效，见 §11。

| 文件 | 入口 | 作用 |
| --- | --- | --- |
| `mega_trace.hlsl` | `MegaTraceMain` | 主光线 + 一次弹射 + 阴影可见性，写三张辐亮度缓冲（SRV t0..t5 / UAV u0..u2） |
| `mega_resolve.hlsl` | `MegaResolve` | 辐亮度 × albedo + 自发光，直通原版像素、半径淡出（SRV t0..t5 / UAV u0） |
| `mega_debug.hlsl` | `MegaDebug` | 单通道调试图：0..3 = x/y/z/w，4 = 法线档 |
| `mega_motion.hlsl` | `MegaMotion` | 运动矢量：线性深度重建世界点后按上一帧矩阵重投影（SRV t0 / UAV u0，见 §19） |
| `mega_gbuffer_ps.hlsl` | `gbuf_main` | G-Buffer 多目标输出模板；不能单独编译，前缀由 spvc 输出拼上 |

两份公共头由光影包与 mod 共用，**不要在本仓库再抄一份**：

- `Illusion-shaders-extension\shaders\include\rt_light_contract.hlsli` —— 采样与终止的契约；
  JVM 镜像是 `D3D12RtLightContract.kt`，Rust 镜像是 `src/rt_light_contract.rs`，场景自检按 1e-5 对拍，
  三者必须同步改（`python _port\contract_check.py` 会比对常量与函数覆盖）。
- `Illusion-HPT-Shaders\WorkGraph-Advanced\shaders\include\wga_common.hlsli` —— 颜色编码与数学
  （`WgaLinearToSrgb`、`WgaTonemapAces`、噪声）；合光的 gamma 编码就走它，不要另写 `pow(1/2.2)`。

三件事按顺序发生：

1. **构建期**：`Illusion\build.gradle.kts` 的 `stageShaders` 遍历 `..\Illusion-HPT-Shaders\<包>\shaders`，
   把每个包的 `*.hlsl` / `*.hlsli` / `pack.json` 拷进
   `build/generated/shaders/assets/dxr_mc/shaders/<包>/shaders/`，两处 `include\*.hlsli` 摊平进该包的
   `include/`，最后写一份 `packs.txt`（内置包清单，B-Mega 排最前），那个根被挂成资源 srcDir。
   顶层没有 `.hlsl` 的目录不算包（`WorkGraph-Advanced` 只有公共头，GLSL 参考包也不拷）。
   光影包缺失时只 warn、不失败——独立目录缺一半时 JVM 半边必须还能构建。
2. **运行期读取**：`D3D12RtShaderSources` 按 `<包>/shaders/mega_trace.hlsl` 读源码，查找顺序是
   `<工作目录>/dxrmc/shaders/<相对路径>`（**改 HLSL 不必重编 Kotlin**）→
   `<工作目录>/dxrmc/shaderpacks/<相对路径>`（整个用户包）→ classpath 里的内置包。
   `D3D12CompileShaderDxc` 没有 include 处理器，`#include` 在这里展开：先找包含方同目录，再找 `include/`。
3. **编译**：`D3D12CompileShaderDxc(source, entry, target)`。入口名与 SRV/UAV 槽数留在 Kotlin
   （`D3D12RtTraceShader.ENTRY` / `SRV_COUNT` / `UAV_COUNT`），必须与 HLSL 绑定顺序一致，
   少给一个 SRV 会让后面的缓冲整体错位。

改 HLSL 的流程：改光影包里的文件 → `python _port\hlsl_check.py`（dxc 取自 Windows SDK，
`-I` 同时指向两处 include，参数与运行期一致；`--keep` 留产物）→ 进游戏看日志。

G-Buffer 的三小块 HLSL 也是包里的片段文件（要塞进生成式着色器，不能单独编译）：`mega_gbuffer_varying.hlsl`（MS/PS 共用的世界坐标 varying）、`mega_gbuffer_ms_patch.hlsl`（meshlet MS 里的调用点补丁）、`mega_gbuffer_albedo_sample.hlsl` / `_constant.hlsl` / `mega_gbuffer_tint.hlsl`（反照率三种写法）。`D3D12RtShaderSources.loadSnippet` 读它们时会丢掉纯注释行与空行、保留缩进，所以片段里可以写注释说明，不必担心注释被插进 struct 内部。

静态检查一次跑完：`python _port/rt_check_all.py`（从仓库根跑；加 `--gradle` 连 Kotlin/Java 编译一起）。它按固定顺序跑下面各条、逐条打 `PASS`/`FAIL`，任何一条失败就以非零码退出——脚本把工作目录钉在仓库根，因为 `docs_refcheck.py` 这类老脚本按进程 cwd 找目标，从 `Illusion\` 里跑会扫 0 个文件并报「三组 0」的假通过。

一次性跑完：`python _port/rt_check_all.py`（`--gradle` 附带编译、`--gpu` 附带引擎级探针）。十六条静态检查 + 一次纯 CPU 自检：`python _port/docs_eolcheck.py` 检查 `docs/` 下每份 `.md` 的行尾（必须全 LF）、无 BOM、文件尾有换行——写歪了不影响渲染，所以只有机器会报；`python _port/rt_stats_keys_check.py` 对拍 `rt_stats.json` 的列（Kotlin 写出的键 × 本节的样例）——少一列基线表就缺数据、多一列按文档写的脚本读不到，两边都不报错；`python _port/frame_constants_check.py` 对拍 `WgaFrame` 的字段偏移（`host/wga_constants.h` 权威）与 Kotlin 镜像，并禁止包里出现第二个 `register(b0)` 的 cbuffer；`python _port/hlsl_check.py` 编包里的入口（三个 compute 入口 + `lib_6_8` 库目标 + G-Buffer PS 模板 + MS 补丁片段，片段用最小 harness 验语法）；`python _port/rt_bindings_check.py` 对拍着色器里实际用到的 `register(t/u)` 槽位、Kotlin 的 `SRV_COUNT` / `UAV_COUNT`、派发时数组的长度，以及**数组元素与槽位的对应顺序**（SRV 与 UAV 都是：第 N 个实参必须落在 `register(tN)` / `register(uN)` 上）——多声明一个槽位就是一张没写过的描述符，少声明会让整表后移、全部绑错缓冲，顺序反了则是每个槽位读到别的缓冲，三种都不报错；`python _port/rt_shader_files_check.py` 校验 Kotlin 引用的每个包内路径都存在、包内每处 `#include` 都能在三个 include 目录里解析到、三个 compute 入口的 `[numthreads]` 与 Kotlin 的 `THREADS` 一致、着色器里 `RtDebugMode() == N` 的每个 `N` 都有对应的 `DEBUG_MODE_*` 常量（探针档与自检天空档只在这两处出现，写错一侧只会让自检莫名失败）；`python _port/rt_placeholders_check.py` 对拍 G-Buffer 模板里的 `%X%` 占位符与 Kotlin 的 `replace("%X%", …)`——少一处替换会留下字面量（编译不过或语义错），多一处替换说明占位符被改名了。模板里的占位符只写在代码行，别写进注释：替换会把代码插进注释里；`python _port/rt_pipeline_release_check.py` 保证 compute 管线只走 `ID3D12DestroyResource`（`dxrmc/rt/` 里禁用 `D3D12DestroyPipeline`，见 §9.8），并要求原生每条加速结构构建/压缩之后 8 行内都出现 `accelBarrier(`（见 §13.6），同时禁止 `dxrmc/rt/` 里出现 `as D3D12Gpu*` 裸转型（原生分配失败返回 null，Kotlin 的 `as` 会抛出**没有 message 的 NPE**，日志只剩一句 `rt self-test: null`；要写 `as?` 加一条带名字的日志）；`python _port/rt_sbt_check.py` 保证四件事：SBT 的标识符按**命中组导出名**取、命中组记录的先后与实例的贡献下标一致（见 §16.4）、ray payload 装得进原生的 `MaxPayloadSizeInBytes`、排版常量自洽（`TABLE_BYTES` == 记录数 × 记录大小，三个起点分别是 0 / 64 / 128，`DispatchRays` 实参顺序对齐）——四种写错方式都是编译不报错、运行时才失效；`python _port/docs_log_check.py` 把文档里引用的日志行与源码对拍——本文的验收清单整个建立在日志行上，改了日志文案却不同步文档，读者照着清单去日志里找会发现一行都没有；只查日志标签（`rt trace:` / `as:` / `shaderpack:` / `dred:`），组合行的完整文本另计；`python _port/pack_config_check.py` 把包声明与引擎配置对拍——`configs.json` 里的每个 `key` 都必须在 `D3D12PackConfig.kt` 有读、有写，且 `IllusionConfig.kt` 里还有同名 TOML 键；少任何一边，那一行都会出现在包设置里、写进 `settings.json`，却永远到不了引擎（不报错，只是不生效）；它还会把每个枚举设置里声明的取值逐个对到 `IllusionConfig.kt` 的枚举上——设置页是按名字查的，包里多写一个不存在的取值不会报错，只是那一档点了没用；`pwsh _port/rt_pure_selftest.ps1` 在游戏外跑自检的纯 CPU 那一半（三个场景的缓冲 + CPU 参考），改过缓冲布局或参考实现后用它确认这条链本身不抛异常、命中像素数合理（见 §9.7）；`python _port/docs_table_check.py` 检查 markdown 表格中间没有空白行——空白行会把表格截成两段，下面的行退化成普通段落，在编辑器里看不出来（本轮 `rt_plan.md` 就是这样坏了 587 处）；
`python _port/rt_check_all.py --gpu` 另外跑 `_port/abi_engine_probe.exe`：它加载 `Illusion\run\dxrmc\MCDR.dll`，用引擎自己的 ABI 建一颗三角形的 BLAS/TLAS，分别走 `DispatchRays` 与 compute + 内联 `RayQuery` 两档，要求两档都打印 `PASS`、`aux:` 命中数一致，且 `planes:` 里 `lit=` 与 `dark=` **都不为零**——场景是「底面 + 挡板」两块 quad，挡板挡住的那部分是影子射线真的被拦下来的证据；全亮等于没有遮挡，全暗说明常量或法线不对。这是唯一能自动发现「D3D12 调用合法但引擎接线错」的检查，默认不开（需要 GPU 与已构建的 DLL）。
`--gpu` 随后跑 `_port/rt_tier_compare.py`：探针用 `--dump` 把 `aux` / `direct` / `indirect` 三个平面写成二进制，脚本读回来做三档两两逐像素比较（`brute` / `accel` / `dxr`），任一对超过 `1e-5` 就失败——这就是 3-e 要的「三档差异图」的游戏外等价物，本机实测九组比较全是 `maxDiff=0.000e+00`。紧接着跑 `_port/rt_scene_compare.py`：它不建自己的场景，而是拿 `_port/SelfTestPureProbe.java` 从**自检代码本身**导出的场景缓冲（`_tmp_selftest/<场景>/` 里的 `records` / `vertices` / `indices` / `materials` / `quads` / `camera` 与 CPU 参考的三个平面），让探针按 `--scene-dir` 读进去、三档各派发一次，再按**与游戏内自检同一套判据**比较（`direct` 每通道逐像素、`aux` 只当命中掩码、`shadow` 场景不比 `indirect`）。三场景 × 三档实测 `maxDiff ≤ 1.2e-07`、`maskMismatch=0`，`failed=0` —— 游戏内自检所依赖的数据与判据在游戏外全部闭环，剩下的只有 Minecraft 运行时本身。`python _port/abi_call_check.py` 守 FFM 调用点：`invokeExact` 的第一个实参不能是 `MCDRLayout.*`（那是 `MemorySegment.copy` 的写法，句柄要的是一组值）、每个 `MH_*` 都必须在 `bindAll` 里绑定，否则第一次跑到就是 `WrongMethodTypeException`

常量缓冲就是 Work Graph 后端那份 `WgaFrame`（`wga_bindings.hlsli`，`cbuffer` 在 b0、416 B），
宿主侧镜像在 `D3D12RtFrameConstants.kt`，与 `WorkGraph-Advanced\host\wga_constants.h` 的
`FrameConstants` 逐字节一致——两个后端共用一套常量布局，回归矩阵只加一列。

B-Mega 占用的字段：`g_invViewProj`（屏幕 NDC → 世界方向）、`g_cameraPos`（xyz 相机、w 帧序号）、
`g_screen`（这一次派发的像素尺寸，即降采样后的辐亮度尺寸）、`g_sunDir`（w 太阳角半径）、
`g_sunRadiance`（rgb 辐亮度、w 直射强度）、`g_exposure.x`（曝光）、`g_misc`（x 反射最大粗糙度、
y 调试模式、z 追踪半径、w 记录数）。`g_moonRadiance` 只在自检环境里装常量天空。

调试模式（`g_misc.y`）：`0..3` 取该通道铺灰、`4` 法线档、`8` 自检探针、`9` 自检环境
（天空换成常量 `g_moonRadiance.rgb`，CPU 参考才对得上）。

天空与太阳不再自己写：追踪的 miss 分支调 `wga_shading.hlsli` 的 `WgaEnvironmentRadiance(...)`，
合光走 `WgaTonemapAces` + `WgaLinearToSrgb`——与 Work Graph 后端同一条 HDR 出口。

### 10.1 常量缓冲是 b0 的 CBV，不是 root constants

compute 根签名固定四个参数：`[0]` SRV 表（`t0..`）、`[1]` UAV 表（`u0..`）、`[2]` **`b0` 的 CBV**、`[3]` `b1` 的 root constants（8 dword）。`WgaFrame` 是 416 B，超过 root constants 上限，所以必须走 CBV：

- 常量缓冲建在上传堆（`GpuBuffer.USAGE_MAP_WRITE`），一帧一份，按 512 B 分槽轮转三份；CBV 偏移必须 256 字节对齐，416 B 的内容塞进 512 B 的槽。
- 每帧 `Map` 后直接写 416 B，不经过 staging + `D3D12CopyBuffer`（上传堆本来就 CPU 可写）。
- 同一帧的追踪、合光、调试三次派发共用同一份常量与同一个偏移。
- `D3D12DispatchComputeEx(ctx, pipeline, srvs, uavs, constants, cbv, cbvOffset, x, y, z)`：`cbv = 0` 表示不绑（着色器读不到常量时会拿到全 0）。

着色器里只允许有一份 `cbuffer ... : register(b0)`（`wga_bindings.hlsli` 的 `WgaFrame`）。再声明一个 `b0` 的局部 cbuffer 会与它抢同一个寄存器槽，`g_exposure.x` 之类会读到别的字段，合光整帧乘 0 就是黑屏。

同一条纪律适用于 SRV/UAV 数量：`SRV_COUNT` / `UAV_COUNT` 必须等于着色器用到的最大槽位加一。`_port/rt_bindings_check.py` 把着色器槽位、这两个常量、派发数组三处一起校验。另外 `D3D12RtFrame.kt` 里 `traceSrvs` 的长度必须按**管线**选（`asSrvs = dxrTier || useAccelPipeline`），按 `accel` 选会在「加速结构建出来但管线没建起来」时多绑一个槽。


## 11. 光影包：目录形状、选中规则与生效时机

一个「光影包」就是 `..\Illusion-HPT-Shaders\<包名>\shaders\` 这样一个目录。包名来自目录名，也是
`[shaderpack] active` 里存的值，也是着色器加载键的第一段（`<包>/shaders/xxx.hlsl`）。

| 脚本 | 检查什么 | 违反后的症状 |
| --- | --- | --- |
| `_port/rt_gbuffer_clear_check.py` | G-Buffer pass 每帧都清全部附件（`clearFlags` 全 1）、`clearColors` 的分量数等于 `ATTACHMENTS * 4` | 清屏退化成「不清」或「少清一张」时 debug 视图与采样读到上一帧残留，画面只是偶尔旧一下、不报错 |
| `_port/rt_gbuffer_variant_check.py` | `D3D12RtGBufferShader.build()` 的失败出口全部带原因、调用点两行日志齐全、主 pass 转换链上的回旧链守卫在 | 变体静默建不出来时主 pass 照常画、光追整条链消失（没有 `rt frame: dispatch`），日志里一个字都不提 |
| `_port/rt_upload_check.py` | `dxrmc/rt` 里出现「只给大小」的 `device.createBuffer` 重载（usage 0 + `…toLong()`）就 FAIL | 那个重载只分配、不上传，着色器读到未初始化显存；自检于是拿垃圾数据比对，日志里看不出原因 |
| `_port/rt_selftest_report_check.py` | `writeSelfTestReport` 写出的键集必须都在文档里写着；报告里注入的摘要（`contract` / `blas` / `sbt` / `frame`）与 `detail` 必须包 `jsonSafe()`（`--selftest` 自带负例自证） | 少了键 ⇒ 文件里有字段没人知道含义；少转义 ⇒ 一个引号就把这一行写成坏 JSON |
| `_port/rt_lock_check.py` | `D3D12RtTrace` 的两条管线、`D3D12RtDxr` 的状态对象、`D3D12RtBlas` 的加速结构、`D3D12RtFrame` 的释放与合光/调试入口，创建/释放/派发读句柄都在锁下 | 自检跑在客户端 tick、派发在渲染线程：不进锁会重复建造（泄漏）或在派发途中释放句柄（设备移除） |
| `_port/rt_material_layout_check.py` | `D3D12RtMaterialTable.ENTRY_BYTES`、`D3D12RtTrace.MATERIAL_BYTES`、`mega_shade.hlsli` 的 `RT_MATERIAL_BYTES` 必须是同一个数（`--selftest` 自带负例自证） | 只改一处 ⇒ 光追按错的步长读材质，albedo 与粗糙度整体错位，编译与日志都不会报 |
| `_port/rt_guide_layout_check.py` | 引导数据表（§19.7）与 `D3D12RtGuide.SLOTS` 的槽位名、格式、来源、顺序逐项一致，且 `specular` 占位值的说明还在（`--selftest` 自带负例自证） | 槽位换了格式没换、顺序换了 ⇒ 降噪与超分绑到别的缓冲，编译和日志都不报 |
| `_port/ngx_rr_probe.cpp`（由 `rt_check_all.py --gpu` 的 `ngx rr` 一步调用） | 游戏外问 NGX 本机是否支持 DLSS Ray Reconstruction（`SuperSamplingDenoising.Available`），探针缺失或本机不支持只打印 `SKIP` | 以为 RR 可用、接完才发现只能在别的机器上跑；或者反过来，明明可用却退到备选方案 |
| `_port/rt_nrd_adaptation_check.py` | NRD 九项适配的可判定部分，外加降噪输出的取样位置（深度约定/命中距离量纲/MV 单位/历史复位/资源状态/轨道互斥/抖动/附件数/粗糙度下限/降噪输出按屏幕像素取），`--selftest` 破坏三处 | 这些项单边改动都不报错：症状是整屏拖影、边缘糊、写入被丢弃，或者关掉光追仍在跑降噪 |
| `_port/log_line_check.py` | 日志正文字面量（占位符不算）硬上限 90 字符，且不得含日期、`because` / `so that` / `which means` 从句、`see …` 指路、文字级别前缀、工单号（`--selftest` 自带负例自证） | 日志是玩家唯一能读的东西；长了没人看，带日期与从句的句子在别的机器上还会立刻过时 |
### 11.1 必须有的文件

| 文件 | 作用 |
| --- | --- |
| `mega_trace.hlsl` | 追踪 compute（入口 `MegaTraceMain`） |
| `mega_resolve.hlsl` | 合光 compute（入口 `MegaResolve`） |
| `mega_debug.hlsl` | 调试视图 compute（入口 `MegaDebug`） |
| `mega_gbuffer_ps.hlsl` | G-Buffer PS 模板（`gbuf_main`，前缀由 spvc 输出拼上） |

四个缺一个，这个包在界面上是灰的、应用不了（判据在 `D3D12ShaderPacks.REQUIRED_ENTRIES`）。
其余片段与公共头（`mega_gbuffer_*.hlsl`、`include\*.hlsli`）按需读，缺了只会让对应阶段失败。

### 11.2 `pack.json`

可选，只用来填界面上的说明面板；键都可省，省了只显示包名与「这个包没有写说明」。B-Mega 自带一份：

```json
{
  "shaderDescription": "B-Mega ray-traced visibility for dxr_mc: one diffuse bounce per pixel ..."
}
```

认两组写法：`name` / `shaderName`、`author` / `shaderAuthor`、`version` / `shaderVersion`、
`shaderDescription` / `description`。`shaderDescription` 是 Iris 的键，照抄 Iris 的包能直接读；
不认识的键忽略。

### 11.3 内置包与用户包

- **内置**：`stageShaders` 拷进 JAR 的那些，清单在 `assets/dxr_mc/shaders/packs.txt`（一行一个包名）。
  读不到清单时只认 `B-Mega`。
- **用户**：`<工作目录>/dxrmc/shaderpacks/<包名>/shaders/…`，文件要求与内置包相同。
  同名时**内置优先**，用户包只能补内置没有的名字。
- 「打开文件夹」按钮打开的是用户包目录（`D3D12ShaderPacks.packsDirectory()`），不存在会自动建。

### 11.4 选中与生效

- 选择存在 `[shaderpack] active`；应用时**同时**写 `[raytracing] backend` 与 `[raytracing] mode`：两者都取包在
  `configs.json` 里声明的值（见 11.6），包没声明才回落 `MEGA` / `FULL`；选「无光影」清空 active 并写
  `AUTO` + `OFF`。包与光追不是两个独立开关，只写包不改这两个键等于什么都没发生。
- 光追出图的判据是 `D3D12Settings.rtEnabled`：**后端不是 `AUTO` 且档位不是 `OFF`**。只看后端会让「档位
  `OFF`」形同虚设，只看档位又区分不出无光影——所以五个入口（帧、G-Buffer、追踪、几何池、材质表）一律问这一个属性。
- 配置里写了不存在或缺入口的包时，`activeName()` 回落到 `B-Mega` 并只警告一次，光追照常跑。
- 生效时机：应用会清掉源码缓存，但**已经编译好的管线不会原地换**。关掉光追再打开
  （`release()` 里连追踪管线一起释放）或重启游戏，才会按新包的源码重编——界面提示就是这么写的。
- 换包会清掉场景自检的结论（`D3D12RtTrace.resetSelfTest()`），下次打开光追重跑。

### 11.5 界面

入口在「视频设置 → 光影包」页（侧边栏最后一页）的「光影包…」按钮。布局照抄 Iris 的包选择页：标题、斜体副标题、包列表、
悬停说明面板、底部两行按钮；已应用的包用黄色（`0xFFFFF263`），不可用的包灰色，「无光影」永远第一行。
逐行规则见 `docs/misc/settingscreen/README.md` §6.10。

光影包单独占一页，不要塞进别的页的固定区：那几行按设计不参与滚动，塞进去会把二级页签与内容区一起压出可视范围。

### 11.6 包自己声明的设置（`configs.json`）

光追那一组开关属于**包**，不属于全局配置：同一个键在不同包里含义可以不同，所以它们只出现在
「视频设置 → 光影包 → 光影包设置」，不放进全局配置页。

- 声明位置：`<包>/shaders/configs.json` 的 `attributes` 数组，键名与 Radiance 的包设置文件一致。
- 单条字段：`name`（稳定 id，同时是 `settings.json` 的键）、`description`（`a.b` 形态当语言键，否则当字面量）、
  `type`（`bool` / `int_range:min-max` / `float_range:min-max` / `enum:a-b-c`；`vec3` 等其他类型先跳过）、
  `default_value`、`group`（分组标题的语言键）、`performance_impact`（`low` / `high`，只用于行尾提示）、
  `key`（绑到 TOML，形如 `raytracing:debugView`）。
- 取值顺序：包的 `settings.json` → 它绑的 TOML 键 → `default_value`。
- 落盘：开关与滑块写 `<user.dir>/dxrmc/shaderpacks/<包>/settings.json`；带 `key` 的**同时**写 TOML 并立刻
  `D3D12Settings.reload()`，不等重启。内置包在 jar 里只读，值一律落在用户目录这一份。
- `[raytracing] backend`、`mode` 与 `asTrace` 都是包的属性：应用包时按它的声明写 TOML，声明缺失才回落
  `MEGA` / `FULL` / `false`；`reconcile()` 在启动后首次 tick 把这三个键按声明拉回一次。
- 界面：标题、显示包名的副标题、按 `group` 分段的行、底部「完成」；布尔点击切换、枚举点击循环、
  区间项在行内轨道上点或拖取值。逐行规则见 `docs/misc/settingscreen/README.md` §6.11。
- B-Mega 的 `configs.json` 声明了 12 项，分两组：`pipeline` 是后端（`MEGA` / `DXR`）、加速结构开关（`asTrace`）、光追档位、质量档、
  采样方式、`spp`、`maxBounces`；`debug` 是调试视图（`NONE` / `DEPTH` / `ALBEDO` / `NORMAL` / `ROUGHNESS` /
  `BLAS`）、失效日志、`hzb`、`historyFrames`、`voxelTrustRadius`。
- 其中 B-Mega 当前**只读** `backend`、`asTrace` 与 `debugView`（见 §1 的生效列）；`spp` / `maxBounces` /
  `samplingMode` / `hzb` / `historyFrames` / `voxelTrustRadius` 现在不改变画面，所以它们的行名里直接标了
  「B-Mega 固定 1」或「未实现」，不靠玩家去猜。

## 12. 加速结构原语（S3 3-a）

3-f 又加了三条与状态对象相关的入口（RTPS / 着色器标识符 / `DispatchRays`）与 `mcdr_rt_hit_group`，见 §15。

这一节只讲"怎么建 BLAS / TLAS"这件事的 ABI 面。谁在什么时机建、refit 还是重建、计数器怎么记，
是 §3 的 AS 管理器（`BlasManager`）要决定的事。

### 12.1 七个入口

`MCDR_ABI_VERSION` 从 7 升到 8 时新增，全部是必需导出（旧 DLL 在握手阶段就会被拒）：

| 入口 | 作用 |
| --- | --- |
| `D3D12CreateAccelStructBuffer(size)` | 建一块能直接当 BLAS / TLAS 用的缓冲，失败返回 0 |
| `D3D12GetBlasPrebuildInfo(geometries)` | 查询 `[结果上限字节, scratch 字节]`，失败返回 null |
| `D3D12GetTlasPrebuildInfo(instanceCount)` | 同上，按实例数查 |
| `D3D12BuildBlas(ctx, dest, scratch, geometries, update)` | 在已打开的命令列表上建（`update = false`）或 refit（`update = true`） |
| `D3D12BuildTlas(ctx, dest, scratch, instances, instanceCount)` | 重建 TLAS |
| `D3D12CopyAccelStruct(ctx, dest, src)` | 压缩拷贝（`COMPACT`） |
| `D3D12CreateAccelStructView(accel)` | 给加速结构建 SRV，可直接放进 compute 的 SRV 表 |

`MCDR_ABI_VERSION` 从 9 升到 10 时又加了一条：

| 入口 | 作用 |
| --- | --- |
| `D3D12GetBufferAddress(buffer)` | 缓冲的 GPU 虚拟地址，失败返回 0。实例描述的 `AccelerationStructure` 必须写它（见 §13.3） |

原生实现在 `MCDR\src\core\d3d12_device.cpp`（`createAccelStructBuffer` / `getBlasPrebuildInfo` /
`getTlasPrebuildInfo` / `buildBlas` / `buildTlas` / `copyAccelStruct` / `createAccelStructView`），
C 包装在 `MCDR\src\c_abi\abi_device.cpp`，Kotlin 封装在 `Illusion\src\main\kotlin\dev\nts\expdxr\d3d12\extern\MCDR_ABI.kt`。

### 12.2 `mcdr_rt_geometry`（64 B）

一条几何就是"顶点缓冲的一段 + 索引缓冲的一段"：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `vertexBuffer` | 缓冲句柄 | 顶点缓冲，必须是 DEFAULT 堆 |
| `vertexStride` | `int64` | 顶点步长，地形是 28 |
| `vertexCount` | `int64` | 顶点数 |
| `vertexOffset` | `int64` | **字节**偏移（`StartVertexLocation * vertexStride`） |
| `indexBuffer` | 缓冲句柄 | 索引缓冲 |
| `indexCount` | `int64` | 索引数 |
| `indexOffset` | `int64` | 索引的**字节**偏移 |
| `flags` | `int32` | 0 = 不透明几何，1 = 需要 alpha 测试（`GEOMETRY_FLAG_NONE`） |
| `indexStride` | `int32` | 索引宽度字节数：2 = `R16_UINT`，4 = `R32_UINT` |

顶点格式固定 `DXGI_FORMAT_R32G32B32_FLOAT`。一条 BLAS 最多 4 条几何（`MCDR_ABI.MAX_ACCEL_GEOMETRIES`）。

S3 的 `D3D12RtBlas` 是**一 (段, 层) 一条 BLAS、每条只放一条几何**：记录里那 8 位是层标志位
（`SOLID = 1`、`CUTOUT = 2`、`TRANSLUCENT = 4`），`SOLID` 标 `GEOMETRY_FLAG_OPAQUE`，
后两层标 `GEOMETRY_FLAG_NONE`（驱动据此允许候选三角形，配合 §14.2 的 `CommitNonOpaqueTriangleHit`）。
把 `SOLID` 也标成 `NONE` 会让每次命中都走候选路径——结果一样，但白丢驱动的"不透明三角形"快路。

### 12.3 缓冲与对齐

- AS 缓冲走 `D3D12CreateAccelStructBuffer`，不要在别处拿普通缓冲凑：它必须在 **DEFAULT 堆**、
  带 `ALLOW_UNORDERED_ACCESS`、初始状态就是 `D3D12_RESOURCE_STATE_RAYTRACING_ACCELERATION_STRUCTURE`，
  而且**不进缓冲池**（池里的资源会被别的用途借走，状态也对不上）。
- 分配宽度按 256 B 对齐。scratch 和结果数据同样要求 256 B 对齐，预构建信息里的两个数字都是"字节"。
- **scratch 不能用加速结构缓冲**：`D3D12CreateAccelStructBuffer` 建出来的资源初始状态是
  `RAYTRACING_ACCELERATION_STRUCTURE`，而 scratch 必须是 `UNORDERED_ACCESS`。状态不对时驱动
  不会在调用点报错，而是整帧挂住（约 8 秒后 TDR，随后 `DXGI_ERROR_DEVICE_REMOVED / 0x887A0005`，
  游戏崩在下一个 `Map`）。scratch 用普通 UAV 缓冲（初始 `COMMON`，首次写入隐式提升），见
  `D3D12RtBlas.ensureScratch` / `ensureTlas`。
- 顶点与索引范围由原生侧校验（`vertexOffset + vertexCount * vertexStride`、
  `indexOffset + indexCount * indexStride` 必须落在缓冲宽度内）。越界读在 GPU 上同样是挂死而不是报错，
  所以这条检查失败时只返回 0 并写一条 `buildBlas: vertex range … exceeds the vertex buffer (… bytes)`。
- TLAS 的实例缓冲是普通的 `ALLOW_UNORDERED_ACCESS` 缓冲（上传或 GPU 写都行），每条实例 64 B、
  16 B 对齐；`InstanceID` 低 16 位放段索引、高 16 位放几何子集，主光线用 `0x01 + 0x02 + 0x04`。

### 12.4 建与 refit

- 首次构建：`PREFER_FAST_BUILD + ALLOW_UPDATE`，`SourceAccelerationStructureData` 写 0。
- refit：`PERFORM_UPDATE`，`SourceAccelerationStructureData` 写**自己**的 GPU 地址（B-Mega 目前不用，见 §13.1）；拓扑（索引数、
  几何数）变了不能 refit，只能重建。
- TLAS 每帧重建，用 `PREFER_FAST_BUILD`，不加 `ALLOW_UPDATE`。
- 压缩：建完立刻 `D3D12CopyAccelStruct(dest, src)`。紧凑版要等这次提交执行完才能当 SRV 用，
  所以**下一帧**再切过去，这一帧继续用未压缩的那块。

### 12.5 失败与日志

七个入口失败时返回 0 / null，原因走 `mcdrLastError()`（最常见的是"没有打开的命令列表"）。
原生侧每建一次会写一行日志：`as: accel struct buffer size=… aligned=… bytes`、
`as: blas prebuild result=… scratch=… geometries=…`、`as: accel struct view slot=… address=…`。
行首的 `as:` 是子系统标记（acceleration structure），不是级别；级别由结构化前缀给出。

加速结构的建/refit 细节日志（`as: accel struct buffer`、`as: blas prebuild`、`as: tlas prebuild`）每条几何每帧都会触发，默认降在 DEBUG：要看就把 `[logging] nativeVerbose` 打开（或 `logLevel = DEBUG`），否则整屏都是它们。INFO 级别的 `as:` 只有一行汇总：`as: inst=… blas=… b=… r=… c=… ms=…`。

### 12.6 与 compute 派发的关系

`D3D12CreateAccelStructView` 建出来的视图是 `Dx12Object::Kind::AccelStructView`：它自己不持有资源，
只占一个描述符槽。派发时它被当作 SRV 传进去，但**不做状态转换** —— 把加速结构转成
`NON_PIXEL_SHADER_RESOURCE` 会让下一次 Build 直接报状态不符。释放它用 `ID3D12DestroyResource`
（和加速结构缓冲一样，都是 `Dx12Object`）。

## 13. 加速结构管理（S3 3-b / 3-c）

`D3D12RtBlas` 是唯一的加速结构持有者：一条镜像段记录一条 BLAS，整帧一条 TLAS。它在
`D3D12RtFrame.dispatch()` 里、`writeScene` 之后、compute 派发之前调用，所以建与 refit 的命令
和 tracing 派发录在**同一张**命令列表上。

### 13.1 BLAS 的归属与键

- 键是 `(sectionNode << 4) | layer`，值里记 `geometryVersion`、顶点数、索引数、索引宽度。
- 一项是"新"的时候：`D3D12GetBlasPrebuildInfo` 拿结果尺寸与 scratch 尺寸，建两块 AS 缓冲
  （结果块 + 紧凑块），然后首建。
- 版本、顶点数、索引数、索引宽度**全都没变** ⇒ 这条 BLAS 仍然有效，直接复用（不录任何命令，
  只把它的地址交给 TLAS）。稳态下这是绝大多数段走的路。
- 任何一项变了 ⇒ 整条重建。计划里"顶点只动过就走 refit（`PERFORM_UPDATE`）"这一条当前**用不上**：
  池子对任何重新上传都会 bump `geometryVersion`，分不清"只是顶点移动"和"拓扑也变了"，所以只保留重建
  一条路，`REFIT_LIMIT` 与 `blas_refits` 因此恒为 0。真要 refit 得先让池子把两种变更分开记版本。
- scratch 只需一块：同一条命令列表里的多次 Build 顺序执行，不开第二条列表就不用给每条几何切分区。
- BLAS 按段常驻，所以要回收：每项记最后一次出现的帧号，`EVICT_FRAMES = 180` 帧没再出现就销毁
  （每 60 帧扫一次）。不回收的话走动起来条目只增不减，显存跟着世界大小涨。

### 13.2 压缩晚一帧

建或 refit 之后 `compactValid = false`；下一帧开头先做 `D3D12CopyAccelStruct(compact, result)`，
成功后才把 `compactValid` 置真。给 TLAS 的地址是 `address()`：压缩过用紧凑块，否则用结果块。
所以"这一帧的实例表里，一部分段指向紧凑块、一部分指向结果块"是正常状态，不需要等齐。

### 13.3 实例表与 TLAS

- 实例表是**上传堆**缓冲（`GpuBuffer.USAGE_MAP_WRITE`），每帧 `map` 直写：上传堆永远是
  `GENERIC_READ`，正好是 `InstanceDescs` 要求的可读状态，CPU 也不用手工同步。
  原生侧 `buildTlas` 因此允许实例表在上传堆，但加速结构本体与 scratch 仍然必须留在默认堆。
- 每条实例 64 B：`Transform` 只写平移（`row0.w = originX`、`row1.w = originY`、`row2.w = originZ`），
  因为镜像顶点是**段内局部坐标**；`InstanceID = 记录下标`（着色器命中后拿它在 `gRecords` 里取同一份
  材质与顶点，见 §14.2）、`InstanceMask = 0x01`（静态不透明）、
  `InstanceContributionToHitGroupIndex` 按层写 0 / 1（`HITOPAQUE` / `HITCUTOUT`，见 §16.3）、
  末尾 8 字节写 BLAS 的 **GPU 虚拟地址**（`D3D12GetBufferAddress`，见 §12.1）。
- 这一格不是对象句柄：句柄是 CPU 侧堆指针，写进去等于让每个实例指向不存在的显存。写错时建 TLAS
  不报错，只是所有光线都命中不到东西；原生侧的 `buildTlas` 只做搬运，不做句柄到地址的翻译。
- 掩码目前只有 `0x01`：镜像里只有静态几何与镂空层，动态实体与半透明还没进池。§3.2 里给主光线留的
  `0x02`（cutout）/ `0x04`（动态）等真要分类时才加——现在加只会让"这条光线看得见什么"与
  "池子里有什么"对不上。
- 实例池容量翻倍增长（起步 64 条），TLAS 结果块或 scratch 不够大时重建两块并**同时重建视图** ——
  视图指向旧的 TLAS 缓冲，换块后旧视图就是悬空地址。

### 13.4 计数与日志

九个数字进 `rt_stats.json`：`blas_builds` / `blas_refits` / `blas_compacts`（窗口内累计）、
`blas_ms`（每帧均值的 CPU 录制耗时）与 `blas_build_us`（单条 BLAS 录制的峰值微秒）、
`blas_vram_mb` / `blas_scratch_mb`（峰值）、`tlas_instances`、`tlas_ms`（TLAS 那一段的每帧均值）。

`blas_ms` 与 `tlas_ms` 必须分开记：两者都是 CPU 侧录制时间，混在一个数里就没法按段对账。
§3.6 的成本预算（每帧 ≤ 0.15 ms、每段 ≤ 25 µs）说的是 GPU 侧，这里给的是 CPU 录制侧的下限估计——
GPU 侧要等时间戳槽扩到加速结构（`SLOT_*` 目前只覆盖 G-Buffer / 追踪 / 合光 / 上屏）才有。

第一次成功建完写一行 `as: inst=… blas=… b=… r=… c=… ms=…`（实例数 / 条目数 / 重建 / refit / 压缩 / 录制毫秒）；
失败只报一次 `as: …`，避免每帧刷屏。释放顺序：`D3D12RtFrame.release()` 先释放加速结构
（它们引用镜像缓冲的地址），再关镜像缓冲。

### 13.5 几何契约检查

`D3D12RtAccel` 在**第一次** `prepare` 时扫一遍全部镜像记录，逐条检查字段自洽性——这是 G3 的第一条
验收（几何契约完整性）。只看一次：记录内容的变化由 `geometryVersion` 兜住，逐帧重扫只会白烧 CPU。

查的是这六项（任一非零说明记录与 BLAS 的读法对不上，命中结果会是"看起来没问题"的垃圾）：

| 项 | 判据 | 后果 |
| --- | --- | --- |
| `empty` | `vertexCount <= 0` 或 `indexCount <= 0` | 这段会被跳过，实例数少于池里的段数 |
| `indices` | `indexCount % 3 != 0` | 最后一个三角形读到缓冲外 |
| `stride` | `indexStride` 不是 2 或 4 | 驱动按 16 位读 32 位索引（或反之），三角形全错 |
| `version` | `geometryVersion <= 0` | 变化检测失效，BLAS 永远不会重建 |
| `unaligned` | `vertexOffset` / `indexOffset` 不是 4 的倍数 | 半字读取跨 dword，索引值被拆错 |
| `outOfRange` | 偏移 + 计数 × 步长 超过对应缓冲字节数 | 读到别的缓冲的内容 |

日志一行 `rt geometry: contract records=… empty=0 indices=0 stride=0 version=0 unaligned=0 outOfRange=0`（每个进程一次，所有档位都跑：默认 `MEGA` 不建加速结构时更没人替这条不变量把关）；
有任何一项非零会追加一条 `as: geometry contract: N record(s) violate the mirror layout`。缓冲区大小
由 `D3D12RtFrame.Mirrors` 在建立镜像时带进来，自检里的独立实例不传大小（跳过越界两项，其余照查）。

### 13.6 几何描述与缓冲状态（建 AS 的两个坑）

**`Triangles.VertexFormat` 必须是真格式**，写 `DXGI_FORMAT_UNKNOWN` 会让整条 build 命令非法：
`ID3D12GraphicsCommandList::Close` 返回 `E_INVALIDARG`，列表进错误态，后续 `Map` / 建缓冲全部失败，
表现是「进世界几秒后设备移除」。段顶点是 28 字节步长、位置在前 12 字节，所以格式与步长**两个都要写**：
`VertexFormat = DXGI_FORMAT_R32G32B32_FLOAT` + `VertexBuffer.StrideInBytes = 28`。

**scratch 不能是加速结构缓冲**：必须 DEFAULT 堆 + `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS`，
初始 `COMMON`，用前显式转到 `UNORDERED_ACCESS`。用 `mcdr_dx12CreateAccelStructBuffer` 建 scratch 会
同时缺 UAV 标志与正确的初始状态。共享同一块 scratch 的多条 build 之间要插 UAV 屏障
（`D3D12RtAccel.prepare` 的 `builtThisFrame > 0` 分支）。

**建完必须插 UAV 屏障**：`BuildRaytracingAccelerationStructure` 与
`CopyRaytracingAccelerationStructure` 之后都要用 `D3D12_RESOURCE_BARRIER_TYPE_UAV` 收口目标资源
（原生侧 `buildBlas` / `buildTlas` / `copyAccelStruct` 已经内置）。同一帧的 `DispatchRays` 会把 TLAS
当 SRV 读，缺这条屏障等于读一份还没写完的加速结构——不报错，只是可能一个实例都命中不了。

这两条都用独立探针（`_port\as_probe.cpp`，不依赖游戏与 `MCDR.dll`）验证过：只建 BLAS + TLAS、
打调试层错误并等 fence，正确写法退出码 0，写 UNKNOWN 时复现 `Close` 的 `E_INVALIDARG`。

**当前档位**：默认（`MEGA` + `asTrace = false`）不建加速结构，逐帧走镜像里的段 AABB + 暴力三角形；
`asTrace = true` 时 `MEGA` 档也建 BLAS/TLAS，compute 档改用内联 `RayQuery`（同一条 kernel，画面应
逐像素不变）；`backend = "DXR"` 时建 AS 并走 `DispatchRays`。自检的 `as_triangle` 场景在这两种建 AS
的档位都会跑，`dxr triangle/shadow` 只在 `DXR` 档跑（`D3D12RtTrace.validateOnce`）。

## 14. 加速结构追踪（S3 3-d：inline `RayQuery`）

### 14.1 两条路径并存

`mega_trace.hlsl` 里 `RtTrace` 有两份实现，用 `#if defined(RT_AS)` 分开，**函数签名与出参完全一致**
（`out RtHit`：命中、距离、法线、材质下标），入口仍是 `MegaTraceMain`：

- `#if defined(RT_AS)`：`RayQuery<RAY_FLAG_NONE>` + `TraceRayInline` 打 TLAS，命中后从 `gRecords`
  回读记录、从 `gVertices` / `gIndices` 重算三角形与法线。
- `#else`：S2 的暴力路径（逐段 AABB 粗筛 + 全部三角形求交）。

两份实现共用后面的着色、弹射、解调代码 —— 换路径时下游一行都不用改，这也是"先 inline、后
rgen/chit"的原因：inline 版能和暴力版逐像素对拍，rgen/chit 版再拿同样的自检兜住。

### 14.2 命中回读

- `InstanceID` 就是**镜像记录下标**（实例表里按记录下标写，不是紧凑序号），所以
  `RtLoadRecord(CommittedInstanceID(), …)` 取到的记录与暴力路径是同一份。
- `CommittedPrimitiveIndex()` 是这条几何里的三角形序号：取 `primitive * 3` 起三个索引，
  顶点位置仍是 `origin + 段内局部坐标`。
- 法线用 `normalize(cross(b - a, c - a))`，与暴力路径的 `RtIntersectTriangle` **同一个叉积顺序**，
  自检才能逐位对拍（顺序反了地面法线会变成 -Y，画面一片洋红）。
- 材质下标是 `materialOffset + primitive / 2`（一个 quad 两个三角形）。
- 几何用 `GEOMETRY_FLAG_NONE`（cutout）时命中会以 `CANDIDATE_NON_OPAQUE_TRIANGLE` 返回，
  **不显式 `CommitNonOpaqueTriangleHit()` 就永远拿不到 `COMMITTED_TRIANGLE_HIT`**。现在的循环
  对候选一律提交；按顶点 alpha 裁剪留到接好材质纹理之后。

### 14.3 绑定与槽数

加速结构变体多一个 `t5`：`RaytracingAccelerationStructure gTlas : register(t5)`。所以
`D3D12RtTraceShader` 有两个槽数常量：`SRV_COUNT = 5`（暴力）与 `SRV_COUNT_AS = 6`（加速结构）。
派发数组顺序固定为 `records / vertices / indices / materials / quads / TLAS 视图`，
`_port/rt_bindings_check.py` 会把"着色器用到的最高槽位"与这两个常量都算合法。

### 14.4 编译与自检

- 运行时：`D3D12RtTraceShader.build(asPath = true)` 在源码最前面插一行 `#define RT_AS 1`，
  其余照常读光影包。管线单独建一条（`ensureAsPipeline`），与暴力管线互不影响，释放同样走
  `MCDR_ABI.ID3D12DestroyResource`（compute 管线是 `Dx12Object`）。
- 离线：`_port/hlsl_check.py` 对含 `defined(RT_AS)` 的入口追加一次 `-DRT_AS=1` 编译，两条分支
  都要过 DXC。
- 自检场景 `as_triangle`：把单三角形场景建成一条 TLAS，用加速结构变体跑一遍，先与**同一份**
  CPU 参考比（`rt trace: scene=as_triangle …`），再把**两档 GPU 结果**逐像素对拍
  （`rt trace: as_triangle paths maxDiff=… over=… mask=… tol=…`）—— 同一帧、同一相机、
  同一 RNG 种子，差别只有遍历方式。命中掩码、命中距离、直接光、弹射三项都要在 `1e-5` 内。
  自检用独立实例（`D3D12RtBlas.newAccel()`），跑完 `release()`，不碰渲染线程那一份。

### 14.5 帧内选路与回退

`D3D12RtFrame` 每帧按这一顺序选路：`D3D12RtBlas.prepare(...)` 建好本帧的 TLAS ⇒ 若
`D3D12RtBlas.view() != 0` 且加速结构管线编译成功，就用**加速结构变体**并多绑一个 `t5`；
否则退回暴力路径。两条路写同一组 UAV，下游合光不知道差别。

两条日志各只打一次：`rt trace: accel path on, tlas slot=… instances=…`，
以及退回时的 `rt trace: accel path unavailable (tlas=… pipeline=…), using brute force`
—— 后者出现说明本帧的加速结构没建起来（第一帧、或镜像为空），画面会掉回暴力路径但不会黑屏。

BLAS 实例线框见 §14.6；三档差异图像（暴力 / inline 加速结构 / DispatchRays 三路逐像素对比）要等 3-f 有 rgen/chit 版才做。
### 14.6 `debugView = BLAS`：实例盒线框

`debugView = BLAS` 把 TLAS 里每个实例的 16³ 段盒画成彩色棱线，直接叠在合成结果上，用来确认
「加速结构里的盒子就是几何池里的段」。它不逐像素求交——384 个实例 × 全屏会到十亿级迭代——而是
**按棱派线程**：实例 `i` 的第 `e` 条棱对应 `SV_DispatchThreadID.x = i * 12 + e`，靠 `edge / 4`
选出轴向、`edge % 4` 选出另外两轴的角点位，投影后用 DDA 沿线写 3×3 像素。

- 着色器：`mega_debug_blas.hlsl`（入口 `MegaDebugBlasEdges`，`[numthreads(64,1,1)]`），
  `t0` 是 `StructuredBuffer<TlasInstance>`（就是 64 B/条的实例表），`u0` 是**合成结果本身**。
- 不拷底图：只往线上写，没写到的像素保持合成值。因此派发前必须插一条
  `BarrierKind.UAV` 屏障（前一段合光也写这张纹理）。
- `b1` 的 `g_instanceCount` 是唯一入参；线程组数 `ceil(instances * 12 / 64)`。
  常量 `BLAS_THREADS = 64` / `BLAS_EDGES_PER_BOX = 12` 在 `D3D12RtFrame` 里，必须与着色器一致
  （`_port/hlsl_check.py` 只保证能编译，数量关系靠这两处常量对齐）。
- 棱线颜色由 `frac(id * 0.6180339887)` 散列，同一段每帧同色、相邻段颜色明显不同。
- 与计划的偏差：计划写的是 `D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_TOOLS_VISUALIZATION`，
  那需要一条完整的 RTPS + 工具库可视化着色器（是 3-f 的依赖）。这里先用解析线框达成同一目的
  （看见 BLAS 边界），等 3-f 有 RTPS 再换。

## 15. 光线追踪状态对象与 SBT（S3 3-f：ABI 层）

3-d 的 inline `RayQuery` 与这里的 `DispatchRays` 吃的是**同一份命中与解调代码**，区别只在入口：
前者是一个 compute 着色器，后者是一条 `D3D12_STATE_OBJECT_TYPE_RAYTRACING_PIPELINE`。本节只写
已经落进 ABI 的那一层；着色器库与逐帧派发接线见后续小节。

### 15.1 三个入口

| ABI | 作用 | 返回值 |
| --- | --- | --- |
| `mcdr_dx12CreateRaytracingPipeline(lib, size, raygen, miss, groups, count, srv, uav, recursion)` | 用 `lib_6_x` 编出的 DXIL 库建状态对象 | 句柄；`NULL` = 失败（原因见 `mcdrLastError()`） |
| `mcdr_dx12GetShaderIdentifier(pipeline, export, out32)` | 取某个导出名的 32 B 标识符 | `1` = 已写入 `out32`；`0` = 名字不存在 |
| `mcdr_dx12DispatchRays(ctx, pipeline, table, …, width, height)` | 在已打开的命令列表上派发 | `1` = 已派发；`0` = 参数错；`-2` = 没有打开的列表 |

`MCDR_ABI_VERSION` 随之升到 **9**（结构体与签名只增不改）；JVM 侧同一份数值在
`MCDR_ABI.MCDR_ABI_VERSION`，两边不一致时 `verifyABI` 在建设备之前就抛异常。

### 15.2 全局根签名为什么与 compute 相同

状态对象的 `D3D12_GLOBAL_ROOT_SIGNATURE` 与 compute 管线用**同一套四参数**：
`[0]` SRV 表、`[1]` UAV 表、`[2]` CBV `b0`、`[3]` root constants `b1`（8 个 dword）。
两边的绑定代码是同一段（`bindComputeStyle`），少写一步（描述符堆、CBV 偏移、常量）只会静默绑错，
不会报错。因此改绑定顺序时必须同时改 compute 与 `DispatchRays` 两条路径。

### 15.3 hit group

`mcdr_rt_hit_group`（32 B，四条 UTF-8 指针：`name` / `closestHit` / `anyHit` / `intersection`）。
三角形几何只需要 `closestHit`（`anyHit` 用于镂空判定）；填了 `intersection` 的组会建成
`D3D12_HIT_GROUP_TYPE_PROCEDURAL_PRIMITIVE`，否则是 `D3D12_HIT_GROUP_TYPE_TRIANGLES`。
一次最多 `MCDR_RT_MAX_HIT_GROUPS`（4）个组，与 cppm 的 `kMaxRtHitGroups` 必须一致。
名字只在建状态对象那一次调用里被读，调用返回后 Kotlin 侧不需要保留字符串。

### 15.4 SBT 布局与对齐

- 记录头部固定 32 B（`D3D12_SHADER_IDENTIFIER_SIZE_IN_BYTES`），由 `GetShaderIdentifier` 写入；
  记录本身建议 64 B，给以后放局部根参数留位置。
- 起点（`raygenOffset` / `missOffset` / `hitOffset`）必须是 32 的倍数，步长（`missStride` /
  `hitStride`）也必须是 32 的倍数 —— 不满足时 native 直接拒派发并写一条 ERROR。
- 目前唯一合法的源是上传堆缓冲（`USAGE_MAP_WRITE`）：CPU 每帧写标识符，GPU 直接从它读地址。

### 15.5 失败与日志

- 建状态对象失败时先 `dumpInfoQueueMessages()` 再返回；常见原因是导出名写错或
  `MaxPayloadSizeInBytes` 小于着色器实际写的载荷。
- raygen / miss 的名字在返回前就用 `GetShaderIdentifier` 验一遍，缺了就报
  `createRaytracingPipeline: missing export <name>`，不会拖到派发时才出空标识符。
- 成功时打一行 `createRaytracingPipeline: PSO handle=… lib=… bytes hitGroups=… srv=… uav=… recursion=…`。

### 15.6 现状

ABI 层、着色器库、自检对拍与逐帧切档都已接上（见 §16）。逐帧走哪条路由 `[raytracing] backend`
决定：`MEGA` 用 compute 的 inline 路径，`DXR` 用状态对象 + `SetPipelineState1` + `DispatchRays`；
两条路吃同一个 `WgaFrame`、同一份 `mega_shade.hlsli`，自检里逐像素对拍（§16.6）。

## 16. DXR 路径：着色器库、SBT 与自检对拍（S3 3-f）

### 16.1 共享头 `mega_shade.hlsli`

compute 路径（`mega_trace.hlsl`）与 DXR 路径（`mega_dxr_lib.hlsl`）**必须吃同一份命中解码与光照
数学**，否则逐像素对拍只能证明"两边都错得不一样"。因此下面这些只允许在共享头里定义一次，
入口着色器不得再抄一份：

- 绑定：`t0 gRecords` / `t1 gVertices` / `t2 gIndices` / `t3 gMaterials` / `t4 gQuadMaterials`、
  `u0 gDirect` / `u1 gIndirect` / `u2 gAux`（TLAS 的 `t5` 只出现在两个入口着色器里）。
- 记录与顶点解码：`RtLoadRecord` / `RtIndexAt` / `RtVertexPosition` / `RtQuadMaterial` /
  `RtFaceNormal`。
- 命中回读：`RtTriangleVertices(record, primitive, a, b, c)` 与
  `RtHitFromPrimitive(record, primitive, t, hit)` —— 法线固定 `normalize(cross(b - a, c - a))`，
  材质下标固定 `materialOffset + primitive / 2`。
- 光照：`RtDirectRadiance` / `RtIndirectRadiance`。

### 16.2 着色器库 `mega_dxr_lib.hlsl`

以 `lib_6_8` 编译（`_port/hlsl_check.py` 的 `LIBRARIES` 里登记，编译时不带 `-E`）。

| 导出 | 着色器类型 | 作用 |
| --- | --- | --- |
| `MegaRayGen` | `raygeneration` | 每个像素发四条光线：主光线、太阳阴影、一次弹射、弹射后的阴影；结果写三张 UAV |
| `MegaMiss` | `miss` | 把 `hit` 清 0、`t` 清 0、法线置 `(0,0,1)` |
| `MegaClosestHit` | `closesthit` | 用 `InstanceID()` / `PrimitiveIndex()` / `RayTCurrent()` 走 `RtHitFromPrimitive` 写 payload |

载荷是 `struct [raypayload] DxrPayload`（28 B：`t` / `record` / `primitive` / `hit` / `normal`）。
`[raypayload]` 属性必须写在 `struct` 关键字之后，写在下一行会报
`an attribute list cannot appear here`。28 B 必须小于原生建的
`D3D12_RAYTRACING_SHADER_CONFIG.MaxPayloadSizeInBytes`（32）——`_port/rt_sbt_check.py` 会把
payload 的字段大小与原生那个数字对拍，改着色器的人不必记得它。

raygen 里四次 `TraceRay` 的 mask 都是 `0x01`，`rayContributionToHitGroupIndex` 与倍数参数都是 0：
命中组由实例记录里的 `InstanceContributionToHitGroupIndex` 决定，逐光线再指定一次会与实例表打架。

### 16.3 命中组

| 组名 | closest-hit | 用途 |
| --- | --- | --- |
| `HITOPAQUE` | `MegaClosestHit` | 不透明地形 |
| `HITCUTOUT` | `MegaClosestHit` | 镂空（叶子等） |

两个组暂时共用同一个 closest-hit：镜像几何没有 alpha，镂空判据要等材质表带上 alpha 才有意义。
实例表里 `InstanceContributionToHitGroupIndex` 按层写：镂空 / 半透明层写 1（`HITCUTOUT`），
其余写 0（`HITOPAQUE`）。两个组暂时共用同一个 closest-hit，所以这条现在只影响走哪条 SBT 记录；
等材质表带上 alpha，`HITCUTOUT` 才会真的做出镂空。

### 16.4 SBT 排版（`D3D12RtDxr`）

- 记录步长 64 B（32 B 标识符 + 32 B 留给局部根参数），必须是 32 的倍数（原生会校验）。
- 布局：raygen `0`、miss `64`、命中组 `128` 与 `192`，整块 256 B。
- **标识符按命中组导出名取**（`HITOPAQUE` / `HITCUTOUT`），不是 closest-hit 的 `MegaClosestHit`：
  `GetShaderIdentifier` 只认建状态对象时登记的导出名，写错不会报错，派发那一刻整张 SBT 失效。
- **记录顺序就是贡献下标**：命中组表第 0 条（偏移 128）必须对应 `D3D12RtBlas.HIT_GROUP_OPAQUE = 0`，
  第 1 条（偏移 192）对应 `HIT_GROUP_CUTOUT = 1`。两边顺序反了不报错，只是镂空几何走进不透明命中组。
- **排版常量要自洽**：`TABLE_BYTES == RECORD_BYTES * (1 + MISS_COUNT + HIT_COUNT)`，三个起点分别是
  `0` / `RECORD_BYTES` / `2 * RECORD_BYTES`。多算一条记录不报错，只是那张多出来的记录永远读不到；
  起点写错一位则两条记录互相覆盖。
- `_port/rt_sbt_check.py` 把上面三条都守住：组名必须出现在标识符列表里、每条命中组记录的位置
  与 `D3D12RtBlas` 里声明的贡献下标逐一相等、排版常量自洽（记录数 × 记录大小 == 整块大小），
  并检查 `DispatchRays` 的实参按 raygen / miss / 命中组的顺序排列。
- 缓冲是上传堆（`USAGE_MAP_WRITE`）：标识符只在建的时候写一次，之后 GPU 直接读地址。
- `DispatchRays` 与 compute 共用 `WgaFrame`，因此**必须把当前场景槽的偏移一起传下去**
  （`D3D12RtFrame` 传 `frameCbvOffset`）：三槽轮转，不传偏移就读到别的帧的相机矩阵。
- 状态对象与 SBT 都是"按包"的：换包时 `D3D12RtTrace.releasePipeline()` 一并释放，
  下次自检按新包重建（否则还在跑旧包的库）。

### 16.5 校验

- `_port/hlsl_check.py` 编译库目标（不带 `-E`），失败会让门直接红。
- `_port/rt_bindings_check.py` 的 `dxr` 行把库里的 `t0..t5` / `u0..u2` 与 `D3D12RtDxr` 的
  `SRV_COUNT = 6` / `UAV_COUNT = 3` 对上，并把自检那个 SRV 数组的容量一起校一遍。
- 状态对象与 SBT 标识符另有独立探针（不加载游戏、不加载 `MCDR.dll`）：用 dxc 编出 `lib_6_8`，按 16.4 的排版建状态对象，再问 `MegaRayGen` / `MegaMiss` / `HITOPAQUE` / `HITCUTOUT` 四条标识符。四条都非空才算通过；把命中组名字写成 `MegaClosestHit` 时会拿到不可用的记录，这正是 `rt_sbt_check.py` 要挡的那类错。
- 派发本身也有独立探针 `_port/rtps_dispatch_probe.cpp`（同样不加载游戏与 `MCDR.dll`）：一颗 10×10 三角形 + BLAS/TLAS、与引擎同构的四参数根签名、4 条 64 B SBT 记录，`DispatchRays(8,8)` 写三张 raw 平面后读回。判据是命中像素数 > 0。
  - `--minimal` 用它自带的最小库（只写常量、一条 `TraceRay`）；两种模式都 `PASS`：最小库 64/64 命中、包内库 62/64 命中（两个角落像素射线没打到三角形），命中距离 8.0 与相机到 `z = 0` 平面的距离一致。
  - 探针里踩到的三个坑都是探针自身的、与引擎无关：暂存缓冲必须在 `ExecuteCommandLists` 之后才释放（否则 GPU 拷的是已释放内存，几何变垃圾、射线永远不命中）、多平面读回要用 `CopyBufferRegion` + 偏移（`CopyResource` 要求两侧等长）、调试层消息要靠 `ID3D12InfoQueue` 才取得到（默认走 `OutputDebugString`，`--debug` 光开调试层看不到任何东西）。
- 引擎级探针 `_port/abi_engine_probe.cpp` 走 `MCDR.dll` 自己的 ABI（`mcdr_dx12BuildBlas` / `BuildTlas` / `CreateRaytracingPipeline` / `GetShaderIdentifier` / `DispatchRays`），验的是引擎接线而不是 D3D12 调用是否合法：建一颗 10×10 三角形、`DispatchRays(8,8)` 后读回 `aux.x`，判据是命中像素数 > 0 且命中距离约等于相机到 `z = 0` 平面的距离（8.0）。当前结果：64/64 命中、`first = 20.2114`、`lit = 40` / `dark = 24`（挡板在底面投下的影子），两档与暴力档在 `aux`、`direct`、`indirect` 三个平面上逐像素 `maxDiff = 0`。几何从非零字节偏移开始（前塞 3 个顶点 / 2 个索引），所以它同时盯着两条语义：记录的 `vertexOffset` 是**字节**偏移，而索引必须是相对该偏移的**局部**顶点号——把索引写成绝对顶点号会让整块几何偏移三格（不报错，只是打到了别处）。
  - 同一个探针带 `--bench=N` 时只量构建成本：先把 N 块 BLAS 结果缓冲一次建好（创建 committed resource 是几百微秒级的开销，不属于构建成本），计时区间里只跑 N 次 `mcdr_dx12BuildBlas`，打印 `bench: builds=N record=…ms total=…ms per=…us`。
  - 同一个探针带 `--compute` 时改走另一条档：把 `mega_trace.hlsl` 前面加 `#define RT_AS 1`、按 `cs_6_8` 编 `MegaTraceMain`，用 `mcdr_dx12CreateComputePipelineEx`（6 SRV / 3 UAV）建管线、`mcdr_dx12DispatchComputeEx` 派发。两条档在同一场景上给出**完全相同**的三个平面（`aux` 40/64、`first = 8.7464`、`center = 8.0312`；`direct_sum = 0`、`indirect_sum = 72.3405`、命中标记 40），说明内联 `RayQuery` 与 `DispatchRays` 两条路在引擎接线与光照数学层面都等价。
  - 引擎侧因此修掉一条：`dispatchRays` 必须先 `SetComputeRootSignature`（与 compute 共用同一个四参数根签名）。状态对象虽然自带全局根签名，但只要还往根参数表里写描述符，就必须先绑上同一份签名，否则驱动在 `DispatchRays` 里直接访问违规。
  - 探针侧的两个坑：`mcdr_dx12Submit` 返回的是本 ctx 的序号（0 = 没有要提交的东西），**不能先把 `mcdr_dx12EndCommandList` 调掉再提交**，等待要用 `mcdr_dx12GetFenceValue(ctx)` 取到的值；`mcdr_dx12CopyBuffer` 的目的地必须是 DEFAULT 堆缓冲，往 UPLOAD 堆缓冲里拷是非法调用（那类缓冲只能 map 写），只在 `Close` 时暴露成 `E_INVALIDARG`。

### 16.6 自检：场景级的 B-Mega / B-DXR 对拍

- 记录里的 `vertexOffset` 是**字节**偏移（池按字节写入，加速结构的几何描述也按字节取），取顶点必须 `vertexOffset + 索引 * 28`；把它当元素索引会读到 28 倍远的位置——只有池偏移恰好为 0 的那一段是对的，其余段全读成垃圾（不报错，只是命中不到）。`indexOffset` 同样是字节偏移。

几何必须整颗落在自己段的盒 `[origin, origin + 16]` 内（暴力档先做段 AABB 粗筛，盒是 `origin - 0.5` 到
`origin + 16.5`；加速结构没有这层裁剪）。顶点伸出盒外时两档会给出不同结果——暴力档看不见、DXR 看得见；
`rt geometry: contract` 的 `outOfRange` 就是查这个。

同一份镜像记录、同一相机、同一 RNG 种子，各跑一次 compute 与 `DispatchRays`，逐像素比三个平面：

| 平面 | 内容 | 比的是什么 |
| --- | --- | --- |
| `aux` | `float4(hit.t, 面法线)` | 命中掩码（`t > 0`）、命中距离、几何朝向 |
| `direct` | `float4(direct, 命中标记)` | 太阳项是否一致 |
| `indirect` | `float4(indirect, 1)` | 弹射项（含命中面的 albedo，间接约束材质） |

容差 `TOLERANCE = 1e-5`。覆盖的场景：

| 场景 | 额外说明 | 日志标签 |
| --- | --- | --- |
| `triangle` | 单三角形、正对相机 | `rt trace: dxr triangle paths …` |
| `shadow` | 只比 `aux` 与 `direct`：一次弹射在 16×16 地面的掠射方向上会撞到"擦边命中/擦边错过"的浮点边界，两个实现各自翻转不算语义差异 | `rt trace: dxr shadow paths …` |
| `as_triangle` | 同一颗三角形走三条 GPU 路：compute 暴力、compute 内联 `RayQuery`、`DispatchRays` | `rt trace: as_triangle paths …` / `rt trace: as_triangle dxr paths …` |

空场景不参与 DXR 对拍：没有实例就建不出 TLAS，而它的背景像素（未命中）已由三角形场景的未命中区覆盖。

自检整体通过时打一行 `rt trace: self-test passed (4 scenes, tolerance 1.0E-5 rel 1.0E-4)`；
`as_triangle` 与 `dxr triangle/shadow` 只在 `backend = "DXR"` 时跑（见 13.6）。

自检跑在客户端 tick、派发跑在渲染线程、换包释放跑在应用配置的那一刻：`D3D12RtTrace` 的两条管线、`D3D12RtDxr` 的状态对象与 SBT、`D3D12RtBlas` 的加速结构、`D3D12RtFrame` 自己的辐亮度/镜像/输出/常量缓冲，创建与释放都在各自的那把锁里，派发读句柄时也进同一把锁。不进锁会在换包/关光追的瞬间把正在用的句柄释放掉（设备移除）。

### 16.7 逐帧档位

逐帧走哪条路由两个开关决定：`[raytracing] backend`（`MEGA` / `DXR`）与 `asTrace`（只有
`MEGA` 档读它）。`DXR` 档必须建加速结构，`MEGA` 档只有 `asTrace = true` 才建。

| 档位 | 逐帧派发 | 日志（首帧） |
| --- | --- | --- |
| `MEGA` + `asTrace = false`（默认） | compute + 段 AABB 暴力三角形，不建加速结构 | `rt frame: … tier=brute` |
| `MEGA` + `asTrace = true` | compute + 内联 `RayQuery`，走 BLAS/TLAS | `rt trace: as tier on, tlas slot=… inst=…` 与 `tier=accel` |
| `DXR` | 状态对象 + SBT，`DispatchRays`；加速结构不可用时不派发 | `rt trace: dxr tier on, …` 与 `tier=dxr` |
| `WG` | 同 `MEGA`（没有实现），选它只 warn 一次 | `rt backend WG is not implemented, …` |

第一帧的派发摘要带档位：`rt frame: dispatch 284x148 of 2560x1334 records=… tier=dxr …`。
`asTrace` 打开而加速结构没建出来时，compute 档静默退回暴力路径（画面不变、`tier=brute`），
因为那条路的着色器只是少了 `t5`；`DXR` 档没这个退路。
`WG` 没有实现：快照里它和 `MEGA` 同路，选它时只打一次
`rt backend WG is not implemented, using the compute path like MEGA`，不静默装成 Work Graph 在跑。
`DXR` 档在加速结构不可用时**不派发**（只 warn 一次、画面留在原版），不会悄悄退回 compute：
换档的意义就是逐像素可对拍，静默回退会让对比结论失真。

切换 `backend` 后按光追重开生效（管线与状态对象都按包缓存，见 §11.4）。

### 16.8 实机验收清单

S3 的验收要一次实机运行。按顺序看下面六处，任何一处不过就别看下一处：

| # | 前置 | 找什么（`logs/latest.log`） | 通过标准 | 不过先查 |
| --- | --- | --- | --- | --- |
| 1 | `gradlew copyNative` | 没有 `MCDR ABI version mismatch: java=9, native=…` | 无该异常 | DLL 还是旧的：`MCDR.vcxproj` 重编后再 `copyNative`（`runClient` 不依赖它） |
| 2 | `backend = MEGA`、`asTrace = false`，进世界 | 没有 `as:` 行（这两项都关时不建加速结构，见 13.6） | 只有 `rt stats:` 与自检行 | 出现 `as:` 说明 `asTrace` 或 `backend` 不是默认值 |
| 3 | 同上，第一帧就有（与档位无关：默认 `MEGA` 也会跑） | `rt geometry: contract records=… empty=0 indices=0 stride=0 version=0 unaligned=0 outOfRange=0` | 六项全 0（G3 的几何契约项，见 13.5） | 哪一项非零就查对应的镜像写入（§12.2 的字段表） |
| 4 | 同上，等约 200 tick | `rt trace: self-test passed (4 scenes, tolerance 1.0E-5 rel 1.0E-4)` | 出现该行 | 看 `rt trace:` 失败行；`deferred` 是队列忙，下个 tick 再来；同一结论落成 `dxrmc/rt_selftest.json`：`pass` / `step` / `detail`，外加 `scenes` 六个场景逐项、`contract` / `blas` / `sbt` / `frame` 四段摘要 —— 一次运行这一个文件就够，不必再跑第二次 |
| 5 | `backend = MEGA` 且 `asTrace = true` | `rt trace: as tier on, tlas slot=… inst=…`、首帧 `tier=accel`、约 200 tick 后自检多跑 `as_triangle` | 三行都在，且画面与 `asTrace = false` 时逐像素一致 | 崩在 `BuildRaytracingAccelerationStructure` 说明几何描述又不合法（13.6 的两条） |
| 6a | 任意档位 | 没有 `rt composite: skipped, …` | 无该行 | 这条只在合光没跑成时各报一次：`no radiance targets` / `frame invalid` 说明光追本身没跑起来，`missing inputs` 说明 G-Buffer 没传进来；出现它就等于屏幕上没有光追 |
| 6b | 任意档位 | 没有 `rt dispatch: skipped, …` | 无该行 | 派发没跑起来时每种原因各报一次：`no device` / `no encoder` / `no camera` / `empty target <w>x<h>`。出现就说明这一帧没做追踪，画面等于原版 |
| 6 | 跑满一个统计窗口（5 秒） | `rt stats: total=…ms trace=…ms cpu=…ms`（`rt_stats.json` 里有 blas / tlas 全列） | 每 5 秒一行，`total` 与 `trace` 同量级 | `trace` 恒 0 说明没派发（先看 `rt dispatch:` 的失败行） |
| 7 | `backend = DXR` | `rt trace: dxr tier on, tlas slot=… inst=…`、首帧 `tier=dxr`，`rt_stats.json` 每行的 `"tier"` 也是 `dxr`，约 200 tick 后自检多跑 `dxr triangle/shadow` | 四处都对，且画面与第 5 步逐像素一致 | `dxr tier needs the accel structure` 说明建 AS 失败（先看 `as:` 与 13.6）；`tier` 仍是 `accel`/`brute` 说明 `backend` 没生效（看 `RT config:` 的 `backend=`） |

`rt_stats.json` 里的 `blas_ms` / `blas_build_us` 是 **CPU 侧**录制时间，只能当 §3.6 成本预算的下限估计，
不能直接当成"GPU 每帧 ≤ 0.15 ms"的通过依据（见 13.4）。



### 16.9 S3 验收项与覆盖方式

计划 §3.6 的每一项对应下面某一种检查。写着「游戏内」的只能靠一次真实运行拿到，其余都能在机器上离线复跑。

| 验收项（计划 §3.6） | 覆盖方式 | 现状 |
| --- | --- | --- |
| 几何契约完整性（无空字段，改一方块 `geometryVersion` 恰好 +1） | `rt geometry: contract` 行：**所有档位**在镜像重建后跑一次（默认 `MEGA` 不建加速结构也跑，见 16.6） | 游戏内首次镜像时 |
| BLAS 正确性（命中距离、面法线） | 引擎级探针三档逐像素对拍（`_port/abi_engine_probe.exe` 与 `_port/rt_tier_compare.py`），以及自检的 `as_triangle` / `dxr` 场景 | 游戏外 `maxDiff=0` |
| 命中三角形索引完全一致 | 按编码等价：材质索引是 `primitive / 2`（逐 quad），同一 quad 的两个三角形同面、同材质，所以它们之间选错不改变任何输出；不同 quad 的三角形互不重叠，只可能命中其中一个。跨 quad 的分歧由探针里「地面与挡板在屏幕空间重叠、深度差 4」的那批像素覆盖 | 等价，无需单独测 |
| 双轨一致性（B-Mega 与 B-DXR 离散量） | 同上三档对拍，以及 `rt self-test` 的 `dxr` 场景 | 游戏外 `maxDiff=0`；游戏内那次 `as_triangle 0.0`、`dxr triangle 5.72e-6`、`dxr shadow 4.96e-5`（`maskMismatch=0`，落在 §9.4 的混合容差内） |
| BLAS 成本（每帧 ≤ 0.15 ms、每段 ≤ 25 µs） | 录制侧：`_port/abi_engine_probe.exe --bench=N`（只计 `build_blas`，分配不计时）；游戏内：`rt_stats.json` 的 `blas_ms` / `blas_build_us` / `tlas_ms`（见 13.4） | 录制侧每段 2.6 µs（64 段）/ 8.6 µs（256 段）/ 11.8 µs（1024 段）；把 64 与 1024 两次的总耗时相减得**边际成本 19 µs/段**（含提交与 GPU，是 GPU 侧的保守上界），仍低于 25 µs。每帧 0.15 ms 只够重建约 20 段，所以必须按 `geometryVersion` 增量重建（384 段全重建约 3 ms） |
| DXR 档运行期可用 | 游戏内三行日志（16.8 的 3–5 步） | 游戏内已验证：`rt trace: dxr tier on`、`rt dxr: state object ready`、`tier=dxr` 三行都在，自检也跑了 `dxr` 的 `triangle` / `shadow` |

上一条不是靠比较证明的，是靠编码证明的：`RtHitFromPrimitive` 用 `materialOffset + primitive / 2` 取材质，而几何是按 quad 导出的（`D3D12RtGeometryPool` 的 `QUAD_VERTICES = 4` / `QUAD_INDICES = 6`），因此一个 quad 的两个三角形在面与材质上完全等价（两档读的是同一份镜像索引，quad 顺序一致）。真要逐三角形比对（例如将来引入逐三角形 UV 或法线），再把 `primitiveIndex` 输出到一张调试平面。

## 17. labPBR 材质图接入

外部 PBR 材质包（如 SPBR）把光滑度、法线放在基础色贴图旁边：`block/stone.png` 对应的 `block/stone_s.png`
是 labPBR 的 specular 图，`block/stone_n.png` 是法线图。本节说明光追阶段怎么读它们、读到的数字进哪里，
以及没有材质包时凭什么退化。

### 17.1 两个来源

`config/dxr_mc-client.toml` 的 `[materials]` 段：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `true` | 关掉就完全不查贴图，材质表退回内置启发式 |
| `packPath` | `""` | 非空 = 只读这个目录（开发期直连材质包仓库）；空 = 读游戏里启用的资源包 |

`packPath` 支持绝对路径，也支持相对游戏目录（`Illusion\run\`）的路径。两种来源都找不到图时返回空结果，
材质表保留 `lookOf(path)` 的启发式值 —— 发行版不带任何贴图，这条路必须能走。

一次重烘打一行：`rt materials: spec=<有 _s 的精灵数> normal=<有 _n 的精灵数> of <查过的精灵数> from <off|packPath|resources>`。
贴图按精灵名缓存，缓存随每次重烘清空；没有映射的精灵也缓存，否则几万个精灵会把重烘拖成卡顿。

首次重烘要开几千个文件（SPBR 里有 3 757 张 `_s`、2 396 张 `_n`），会有一次几百毫秒到一秒量级的停顿；
之后每次图集重烘（例如资源包切换）都要再来一遍 —— 缓存的键是精灵名，图集换了必须重解析。

### 17.2 材质记录（40 B）与光照

| 偏移 | 字段 | 来源 |
| --- | --- | --- |
| +0 | `albedo` RGB | 基础色贴图 4×4 采样均值（线性空间） |
| +12 | `emissive` RGB | 启发式（`lookOf`），材质包没有自发光图 |
| +24 | `roughness` | `1 - _s.R / 255` |
| +28 | `flags` | 启发式（透明 / 双面 / 自发光位） |
| +32 | `metalness` | `_s.G / 255` |

`flags` 留在 +28、金属度追加在 +32：老偏移一个都没动，着色器只是多读一个字。

着色器侧（`mega_shade.hlsli`）：

- 漫反射 `albedo * (1 - metalness)`，F0 `lerp(0.04, albedo, metalness)`；
- 太阳项 `max(dot(n, sunDir), 0) * 漫反射 + 高光`，高光是 Blinn：`exponent = exp2(1 + 9 * (1 - roughness))`、
  `lobe = pow(saturate(dot(n, halfDir)), exponent)`、`f0 * lobe * (exponent + 2) / 8`；
- 天空项只走漫反射。

这套只在**弹射面**（第二命中）生效：主命中写的是 `direct`/`indirect` 三张辐射度，契约要求那里不乘任何材质
（`docs/rt/README.md` 7.1 与 `rt_plan.md` §3.3）。CPU 参考 `D3D12RtTrace.shadeBounce` 与着色器逐项对齐，
改一边必须改另一边，否则自检报「GPU 与 CPU 参考差很大」而画面看不出问题。

### 17.3 法线图与孔隙：只探测不解码

`_n` 只用来统计（`normal=` 那个计数），不参与着色：G-Buffer 的像素着色器拿不到材质表 —— mesh 根签名的
SRV 槽是固定的四张附件与顶点/段/材质缓冲，多一张法线图集就得改根签名与整条 G-Buffer 管线。
孔隙（`_s.B`）同样没接：光追阶段没有遮挡项，接了也没有消费者。

### 17.4 验收

- 不配 `packPath`、不启用材质包：日志那行是 `from resources` 且 `spec=0`，画面与接入前一致；
- 配 `packPath = Illusion-HPT-Shaders/SPBR-22_6`：`spec` 应是几千（该包 10 659 张图里 3 757 张 `_s`），
  石头/木头/金属的粗糙度与金属度各不相同；
- 自检三个场景的 `maxDiff` 不因接入而变（自检用的是固定材质表，与材质包无关）。

## 18. 降噪与超分的单一真值表（S4 4-a）

光追链路的每一环都按同一张表决定分辨率与去留：`D3D12RtPipelinePlan.configured(displayWidth, displayHeight)`
是唯一入口，别处要尺寸就调它，不要再自己乘一次百分比。这张表就是 `rt_plan.md` §6.1 的落地形态。

| 轨道 | `renderScale` | trace 尺寸 | G-Buffer 尺寸 | 降噪 | 超分 |
| --- | --- | --- | --- | --- | --- |
| `OFF` | 忽略（按 100%） | 光追整条不跑 | 主 pass 尺寸 | 无 | 无 |
| `RR` | 生效 | `renderScale%` 定目标高度 | 主 pass 尺寸 | DLSS-D 内置 | DLSS-D 内置（输出即显示尺寸） |
| `NRD_SR` | 生效 | 同上 | 主 pass 尺寸 | NRD（`REBLUR_DIFFUSE`） | 合光时双线性上采样 |
| `MEGAKERNEL` | 生效 | 同上 | 主 pass 尺寸 | 无（基准：直接看噪点） | 合光时双线性上采样 |

G-Buffer 与合光仍按显示分辨率跑：那个 pass 挂在主 pass 之后的同一条链上，缩小它要连主 pass 一起缩，
见 §26.2 的取舍说明。

配置键（`config/dxr_mc-client.toml` 的 `[raytracing]`）：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `denoise` | `OFF` | 走哪条轨道；`MEGAKERNEL` 是未降噪基准，`RR` 走 DLSS-D（§25），`NRD_SR` 走 NRD（§23） |
| `renderScale` | `100` | 光追渲染分辨率占显示分辨率的百分比（25–100）；缩放的是 trace 的目标高度，见 §26 |

表变了才打一行（每次改分辨率、改轨道、改倍率各一次）：

```
rt: denoise=<track> render=<w>x<h> gbuffer=<w>x<h>
```

`gbuffer=0x0` 说明光追没开；`gbuffer` 与 `render` 不同就是有人又自己算了一遍尺寸 —— 这行的用处就在这。
`MEGAKERNEL` 也照 `renderScale` 缩放：它是「降噪前」的基准，缩不缩放由用户决定，表如实写出来。

单帧内部的 `rt frame: dispatch <w>x<h> of <displayW>x<displayH>` 那行是派发时的实际网格，
两者对不上同样说明尺寸来源不一致。

## 19. 运动矢量与引导数据（S4 4-b）

运动矢量是降噪与超分的重投影输入：每个像素告诉后级「这个像素在世界里的那个点，上一帧落在屏幕的哪里」。
它在光追派发之后单独算一次，写进 `rt-motion`（RGBA16F，与 G-Buffer 同尺寸），今天还没有消费者 ——
`RR` / `NRD_SR` 接线时直接读它。

### 19.1 为什么另开一次计算

加第六张 G-Buffer 附件走不通：那个 pass 的 PSO 是 `createGBufferVariant` 复用主 pass 的 RTV 数建的 mesh 变体，
逐 pass 的 `recordGBufferPass` 只给 5 个颜色槽建格式表，`SV_Target5` 没有对应格式 ⇒ 只能 pass 之后单独 `Dispatch`。
`D3D12RtMotion.render()` 就是这一步，由 `D3D12RtFrame.dispatchImpl` 在 `writeScene(...)` 成功之后调用。

### 19.2 算法与约定

`Illusion-HPT-Shaders\B-Mega\shaders\mega_motion.hlsl` 的 `MegaMotion`：

1. 读 G-Buffer 的 `LINEAR_DEPTH`（`t0`）。存的是 `sqrt(dist / DEPTH_SCALE)`，所以 `distance = stored * stored * DEPTH_SCALE`。
   `DEPTH_SCALE = 64.0`，按浮点位放在 b1 根常量的第一个 32 位槽（b0 的字段已排满，布局见包里 `wga_bindings.hlsli` 的 `cbuffer WgaFrame`）。
2. 与 `mega_trace` 同一套反投影：`uv = (id + 0.5) / size`、`ndc = (2u - 1, 1 - 2v)`、`mul(g_invViewProj, float4(ndc, 1, 1))`
   后再归一化减相机位置得到方向，世界点 = 相机 + 方向 × 距离。两处必须同式，否则运动矢量整体偏。
3. 世界点分别乘 `g_viewProj` 与 `g_prevViewProj`，取 `xy / w` 之差，乘 `0.5` 换成 NDC 跨度的半宽，再乘像素尺寸。
4. 输出 `float4(dx, -dy, 0, 1)`：**像素空间、+y 朝下**（D3D 屏幕坐标）。NDC 的 y 朝上，纵向必须取反 —— NRD 与 DLSS 都按像素空间读。
5. 深度为 0 的像素（天空、G-Buffer 没写过的实体）位移记 0：它们自己就是静止的，写别的东西会被后级当成运动。

### 19.3 上一帧矩阵

`D3D12RtFrameConstants.write(...)` 的两个可选参数负责轮转：`previousViewProjection` 传上一帧存下的矩阵，
`outViewProjection` 把这一帧的矩阵写回同一个 `FloatArray(16)`。没人传时 `g_prevViewProj` 等于当前帧，
位移恒为 0 且不报错 —— 这正是 `pending` 阶段该有的行为。

分辨率一变（`ensureOutput` 里 `outputWidth/outputHeight` 换了）就置 `previousValid = false`：宽高比变了，
旧矩阵当上一帧用会算出一帧乱飞的运动矢量。自检路径（`D3D12RtTrace` 里的 `write`）不传新参数，
所以自检的 `g_prevViewProj` 恒等于当前帧，不会污染世界帧的轮转。

### 19.4 成本与日志

一次 `8x8` 覆盖 G-Buffer 尺寸的派发，只读一张纹理、只写一张纹理；记在 `cpu` 与 `dispatches` 两列，
`rt_stats.json` 没有单独的 motion 槽（要在别处对比就自己看 `cpu`）。

创建 `rt-motion` 时一行：

```
rt: motion <w>x<h>
```

尺寸只随 G-Buffer 变化，不是每帧。派发失败一次性打 `rt motion: ...`（这条链失败时画面只是少一个引导缓冲，
不该每帧刷日志）。

### 19.5 静态门

| 脚本 | 管什么 |
| --- | --- |
| `_port/hlsl_check.py` | `mega_motion.hlsl` 按 `cs_6_8` + 入口 `MegaMotion` 编译一次 |
| `_port/rt_bindings_check.py` | `motionSrvs` / `motionUavs` 的槽位数与顺序对 `register(t0)` / `register(u0)` |
| `_port/rt_shader_files_check.py` | Kotlin 里出现的 `mega_motion.hlsl` 必须在包内存在 |
| `_port/rt_guide_layout_check.py` | 引导数据表（§19.7）与 `D3D12RtGuide.SLOTS` 逐项对拍 |

### 19.6 验收

本次改动不改变任何可见像素：光追开着时控制台只多两行 —— 建目标时的 `rt: motion <w>x<h>` 与槽位齐了之后的 `rt guide: …`，`rt_stats.json` 的 `cpu` 会多出一次
小派发的耗时。要确认它真的跑了，看这两处即可；真正的画面收益要等 `RR` / `NRD_SR` 读它之后才有。

### 19.7 引导数据的单一定义

NRD、DLSS-D、FFX 要的是同一组输入，所以只有一份清单：`Illusion\src\main\kotlin\dev\nts\expdxr\dxrmc\rt\D3D12RtGuide.kt`
的 `SLOTS`。顺序就是 SRV 槽位顺序，消费方直接拿 `srvHandles()` 去绑；缺任何一项时它返回 null，
调用方整条跳过 —— 少绑一个槽位时原生会按数量校验拒掉整次派发，绑半套等于每帧静默失败。

| 槽位 | 格式 | 来源 |
| --- | --- | --- |
| `albedo` | `RGBA8` | `gbuffer` |
| `specular` | `RGBA8` | `gbuffer` |
| `normal-roughness` | `RGBA16F` | `gbuffer` |
| `linear-depth` | `RGBA16F` | `gbuffer` |
| `motion` | `RGBA16F` | `motion` |

`gbuffer` 指的是 G-Buffer 的四张颜色附件（§8），`motion` 是 §19.2 那张 `rt-motion`。
viewZ 不再单独存一份：`linear-depth` 反推就是它（`stored * stored * DEPTH_SCALE`），多存一份必然与它打架。

**`specular` 今天是占位值。** G-Buffer 的像素着色器拿不到材质表（mesh 根签名的 extras 表固定 3 个 range），
那张附件里恒为 0.04，材质图算出的金属度进不了这一层。接线顺序是：先让材质金属度到得了 PS，
`specular` 就变成 `lerp(0.04, albedo, metalness)` 的真值，NRD 的 `REBLUR_DIFFUSE_SPECULAR` 才能分开
漫反射与镜面两套历史。在那之前用 `REBLUR_DIFFUSE`（只要 albedo 与 normal/z）是诚实的，
用 specular 变体则等于把一切都当电介质。

准备齐了才打一行（一次进程）：

```
rt guide: 5 slot(s), specular constant 0.04
```

缺项时是 `rt guide: incomplete, motion or gbuffer missing` —— 这行说明降噪拿不到完整输入，不要按「能跑就行」放过。

静态门 `_port/rt_guide_layout_check.py` 把这张表与 `SLOTS` 逐项对拍（槽位名、格式、来源都要一致，
顺序也不能换），并检查 `specular` 的占位值说明还在文档里。

## 20. NRD 后端（S4 4-c）

NRD 4.18 是静态库（`MCDR\external\nrd\`），DXIL 内嵌在库里，不用我们自己编译它的着色器。代价是
它的管线数量、常量缓冲上限、纹理池尺寸与寄存器空间全部由库决定 —— 这些数字手填错一个，降噪就
整段不出图，而且不会报错。

### 20.1 第一步：把库自己算出来的清单读出来

`MCDR\src\advanced\d3d12_nrd.cpp` 建一个 NRD 实例、读 `InstanceDesc`、把字段按「每行一条事实」写进
调用方缓冲；`mcdr_dx12NrdInspect` 只是这条路径的 ABI 出口（纯 CPU，不需要 D3D12 设备）。
`Illusion\src\main\kotlin\dev\nts\expdxr\dxrmc\rt\D3D12RtNrd.kt` 的 `inspectOnce()` 在首帧派发后把这个
清单打一次。`MCDR_ABI` 里对应 `D3D12NrdInspect`（ABI v11）。

### 20.2 实测数字（NRD 4.18，`REBLUR_DIFFUSE_SPECULAR`）

| 字段 | 值 | 用途 |
| --- | --- | --- |
| `pipelinesNum` | `14` | 每次 `GetComputeDispatches` 的 pipeline 下标都落在这个范围内 |
| `constantBufferMaxDataSize` | `912` 字节 | root CBV 一次上传的上限，超过就截断 |
| `constantBufferAndSamplersSpaceIndex` / `resourcesSpaceIndex` | `1` / `0` | 两套根签名空间不能混 |
| `constantBufferRegisterIndex` / `resourcesBaseRegisterIndex` / `samplersBaseRegisterIndex` | `0` / `0` / `0` | 与上面两个空间联用 |
| `samplersNum` | `2` | `NEAREST_CLAMP` + `LINEAR_CLAMP` |
| `permanentPoolSize` / `transientPoolSize` | `13` / `8` | 纹理池条目数，格式由 `TextureDesc` 给出 |
| `perSetTexturesMaxNum` / `perSetStorageTexturesMaxNum` / `setsMaxNum` | `18` / `7` / `36` | 描述符池上限 |
| `pipelines[0].computeShaderDXIL.size` | `3596` 字节 | 库自带 DXIL，我们只建 PSO 不编译 |
| `shaderEntryPoint` | `main` | **不是**手册里写的 `NRD_CS_MAIN` |

### 20.3 接下来要按这份清单做

1. 按 `pipelinesNum` 为每条 `PipelineDesc` 建 compute PSO（DXIL 直接用，`hasConstantData` 决定要不要绑 CBV）。
2. 按 `permanentPool` / `transientPool` 建纹理池，格式来自 `TextureDesc`，`downsampleFactor` 参与尺寸换算。
3. 描述符堆按 `descriptorPoolDesc` 的上限建；每次派发按 `DispatchDesc.resources` 逐项绑。
4. 每帧 `SetCommonSettings`（`motionVectorScale` 用 1/renderW、1/renderH，我们的运动矢量是像素单位）
   + `SetDenoiserSettings` + `GetComputeDispatches`。

### 20.4 日志样例

```
nrd: pipelines=14
nrd: cb max=912 bytes
nrd: spaces cb=1 res=0
nrd: registers cb=0 srv=0 smp=0
nrd: samplers=2
nrd: pool permanent=13 transient=8
nrd: desc set srv=18 uav=7 sets=36
nrd: pipeline0 dxil=3596 cb=1
nrd: entry=main
```

这份 DLL 没编进 NRD 时只有一行 `nrd: not built in`；建实例失败时那几行按 WARN 打，内容由库里给出。

### 20.5 门

`_port/abi_layout_check.py`（结构布局与版本）与 `_port\rt_check_all.py --gradle` 覆盖这一层：新增入口
必须同时出现在 `mcdr_abi.h`、`MCDR_ABI.kt` 的 `skipOptional` 与 `MCDR_ABI_VERSION` 的版本记录里。

## 21. NRD 输入编码（S4 4-c）

NRD 只看两种「引导」排布：法线-粗糙度与 viewZ。两者都不是我们 G-Buffer 里的原样数据，必须在派发之后
重排一次，否则降噪拿到的是解错的法线 —— 画面只会变糊，不报任何错。

### 21.1 为什么必须自己转

编码在 NRD 编译期定死，运行时只能问出来不能改：`LibraryDesc.normalEncoding = 2`
（`R10_G10_B10_A2_UNORM`）、`roughnessEncoding = 1`（`LINEAR`）。这两个值由 `D3D12RtNrd` 的清单打出，
见 §20.4 的 `nrd: normal encoding=2 roughness=1`。软件包的 `include` 里没有 `NRD.hlsli`，打包公式
照 NVIDIA-RTX/NRD 的 `_NRD_EncodeNormalRoughness101010` 逐位实现，放在
`Illusion-HPT-Shaders\B-Mega\shaders\mega_nrd_pack.hlsl`。

### 21.2 打包公式

法线先按 L1 归一，再把 `x/y` 斜切成两轴、`z` 的符号折进粗糙度：

```
n /= |n.x| + |n.y| + |n.z|
r.y  = n.y * 0.5 + 0.5
r.x  = n.x * 0.5 + r.y
r.y -= n.x * 0.5
roughness = max(roughness, 1.5 / 512)      // 不能为 0，否则 z 的符号位吃掉粗糙度
r.z = (n.z < 0 ? -roughness : roughness) * 0.5 + 0.5
```

`w` 是 `materialID / 3`，本工程恒 0（只有一类材质），所以恒定写 0。

### 21.3 格式偏差：用 `RGBA16_FLOAT` 存同一组归一化值

引擎的 `GpuFormat` 枚举里没有 10-10-10-2 打包格式（`javap` 查过：只有 `R8/RG8/RGBA8`、`R16/RGBA16`
的 UNORM/SNORM/UINT/SINT、`R32/RGBA32` 的 UINT/SINT、`R16/R32_FLOAT` 等），而 NRD 的输入只是一张
SRV，按 `float4` 读。所以这里存的是**同样的归一化打包值**，只是每像素 8 字节而不是 4 字节：
解码数学不变，精度更高。`IN_VIEWZ` 用 `R32_FLOAT`，与 NRD 的「线性视图深度」直接对应。

### 21.4 viewZ 是投影距离，不是到相机的直线距离

G-Buffer 的线性深度存的是径向距离（`sqrt(saturate(length(worldPos) / DEPTH_SCALE))`，见 §8），
NRD 要的是沿视线方向的投影。转换在同一个 kernel 里做：

```
radial = stored * stored * DEPTH_SCALE          // 反解出世界距离
viewZ  = radial * saturate(dot(dir, forward))   // dir 由 g_invViewProj 反投影，forward 取屏幕中心方向
```

打空的像素写 `viewZ = 0`、法线写相机正前方、粗糙度取上限：NRD 会把它们当无穷远，不会去和旁边的
表面混。

### 21.5 日志与验收

建目标时一行：`rt: nrd pack <w>x<h>`。失败只打一次 `rt nrd pack: …`（这条链断了画面只是少两份
引导数据，不该每帧刷屏）。本次改动不改变可见像素：`rt_stats.json` 的 `cpu` 会多出一次小派发的耗时，
`dispatch` 计数 +1。

### 21.6 门

| 门 | 覆盖什么 |
| --- | --- |
| `_port/hlsl_check.py` | `mega_nrd_pack.hlsl` 的 `cs_6_8` 编译 |
| `_port/rt_bindings_check.py` | 两个 SRV（`gNormalRoughness` / `gLinearDepth`）与两个 UAV（`gNrdNormalRoughness` / `gNrdViewZ`）的槽位顺序 |
| `_port/rt_shader_files_check.py` | Kotlin 里写的 `mega_nrd_pack.hlsl` 在包里真实存在 |

## 22. NRD 常驻上下文（S4 4-c）

NRD 的 14 条降噪管线不能每帧新建：`PipelineDesc` 里带的是库内编译好的 DXIL，根签名、纹理池、描述符堆
也必须与 `InstanceDesc` 逐字段对上。这一层做成常驻上下文，随设备与画面尺寸重建。

### 22.1 根签名四项从 `InstanceDesc` 抄

根签名的槽位、寄存器号、寄存器空间全部来自 `InstanceDesc`，写死任何一项都会让 `CreateComputePipelineState`
返回 `E_INVALIDARG`（不报哪一项不对）：

| 根参数 | 类型 | 寄存器 | 空间 | 来源字段 |
| --- | --- | --- | --- | --- |
| `[0]` | CBV | `b0` | `space1` | `constantBufferRegisterIndex` + `constantBufferAndSamplersSpaceIndex` |
| `[1]` | 描述符表（SRV） | `t0` 起 | `space0` | `resourcesBaseRegisterIndex` + `resourcesSpaceIndex` |
| `[2]` | 描述符表（UAV） | `u0` 起 | `space0` | 同上（NRD 的 SRV/UAV 共用一个空间） |
| `[3]` | 描述符表（采样器） | `s0` 起 | `space1` | `samplersBaseRegisterIndex` + `constantBufferAndSamplersSpaceIndex` |

今天读出来的值是 `spaces cb=1 res=0`、`registers cb=0 srv=0 smp=0`、`samplers=2`，与 `REBLUR_DIFFUSE_SPECULAR`
的约定一致；库升级后这些数字可能变，代码按字段读，不按常量写。

### 22.2 格式映射

`nrd::Format` 有 44 项（`Format::MAX_NUM` 是 43 之后的哨兵），必须逐项映射到 `DXGI_FORMAT`。前 40 项都是
常规 8/16/32 位格式，**40–43 是打包格式**：`R10_G10_B10_A2_UNORM` / `R10_G10_B10_A2_UINT` /
`R11_G11_B10_UFLOAT` / `R9_G9_B9_E5_UFLOAT`。漏掉这四项时症状是建池纹理失败：

```
nrd: context failed, create pool texture 0
```

映射错的症状相反 —— 不报错，降噪读到另一种精度，画面慢慢发糊。`D3D12RtNrd` 的清单给出 `ToDxgi` 的输入
（`nrd: pool permanent=13 transient=8`），改动格式后先看清单再跑。

### 22.3 重建时机

`mcdr_dx12NrdInit(width, height, outText, capacity)` 在每次派发前调一次（`D3D12RtFrame.dispatchImpl` 里的
`D3D12RtNrd.ensure`），内部三条判据：设备指针、宽、高都没变就直接返回 `1`，否则先 `shutdown` 再重建
（纹理池按屏幕尺寸开）。分辨率变化必须重建，池纹理尺寸是建的时候定死的。

设备指针由 ABI 层给：`mcdr_dx12NrdInit` 在 `abi_device.cpp` 里用 `d3d12_mc::getDeviceHandle()` 取
`ID3D12Device*`，再转交给 `src/advanced/d3d12_nrd.cpp` 的 `mcdr_nrd_context_init` —— 后者是普通翻译单元，
拿不到 `gCtx`。

### 22.4 结果与失败定位

成功时 `outText` 两行，`D3D12RtNrd` 按 INFO 逐行打印：

```
nrd: context ready, psos=14 pool=13/8 heap=900
nrd: cb max=912 samplers=2
```

失败时第一行就是失败的那一步，`%u` 是池索引或管线索引：

| stage | 含义 |
| --- | --- |
| `create instance` | `nrd::CreateInstance` 失败（denoiser 标识符或内存分配） |
| `serialize root signature` | `D3D12SerializeRootSignature` 失败 |
| `create root signature` | `CreateRootSignature` 失败（根参数与 `InstanceDesc` 不符） |
| `create pipeline state` | `CreateComputePipelineState` 失败（根签名与库内 DXIL 不匹配） |
| `create pool texture` | 池纹理建不出来（格式映射或堆属性） |
| `create descriptor heap` | 描述符堆建不出来（`setsMaxNum × perSet` 超出堆上限） |

`mcdr_dx12NrdInit` 返回 `0`（`nrd: not built in`）表示这份 DLL 没编进 `MCDR_HAS_NRD`；返回 `-1` 表示上面某一步
失败。`outText` 的容量由调用方给（`D3D12RtNrd.TEXT_CAPACITY = 2048`），写满截断不报错。

### 22.5 门

| 门 | 覆盖什么 |
| --- | --- |
| `_port/abi_layout_check.py` | `MCDR_ABI_VERSION` 与新增入口的三处声明一致 |
| `_port/ffm_call_sites_check.py` | `MCDR_ABI.kt` 里新增的两个 MethodHandle 都有对应调用点 |
| `_port/rt_pipeline_release_check.py` | 上下文在 `D3D12RtNrd.shutdown()` 之前没有泄漏路径 |

本次改动不改变可见像素：控制台多两行，`rt_stats.json` 没有新增列。

## 23. NRD 逐帧派发（S4 4-c，进行中）

### 23.1 一条派发链要做什么

`nrd::GetComputeDispatches` 返回的是「这一次降噪要跑哪些 pass、每个 pass 绑哪些资源」。集成方负责建资源、建视图、绑根签名与派发，NRD 自身不分配任何资源。

固定顺序（顺序错了不报错，只会得到空结果）：

1. `nrd::SetCommonSettings`，把本帧的矩阵、尺寸、运动矢量缩放写进实例。
2. `nrd::GetComputeDispatches`，取回 `DispatchDesc` 数组。**反过来的话 `gridWidth/gridHeight` 全是 0**，派发等于空转。
3. 逐个 `DispatchDesc`：按 `pipelineDesc.resourceRanges` 里的段序把 SRV/UAV 视图连续摆进一张着色器可见堆（SRV 段在前、UAV 段在后），再 `SetComputeRootSignature` / `SetPipelineState` / `SetDescriptorHeaps` / `SetComputeRootDescriptorTable` / `SetComputeRootConstantBufferView` / 屏障 / `Dispatch`。

根签名的四个参数与 `InstanceDesc` 一一对应，见 §22.1。`DispatchDesc.constantBufferData` 是可选的每 pass 常量，按 256 字节对齐放进一块 upload 缓冲并逐次偏移。

### 23.2 六个真实踩过的坑

- **清理的 CPU 句柄必须来自非着色器可见堆**：`ClearUnorderedAccessViewFloat` 的 `UnorderedAccessViewCPUHandle` 若取自 `D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE` 的堆，整条清理会被静默丢弃（调试层只报 `points to a descriptor heap type that is CPU write only`）。清理用堆单独建一张 `FLAG_NONE` 的，GPU 句柄仍从可见堆取。
- **描述符堆要在任何 Clear/Copy 之前绑定**：命令列表还没 `SetDescriptorHeaps` 时清理同样被丢弃，报错文本是 `needs to be from the descriptor heap currently set`。
- **同一张可见堆内 CopyDescriptorsSimple 不可靠**：从可见堆往可见堆拷视图时调试层报 `SrcDescriptorRangeStart points to a descriptor heap type that is CPU write only`，拷完目标槽仍是空描述符。改为直接把视图建到目标槽后，调试层消息从 75 条降到 0 条。
- **写入的资源必须停在 `UNORDERED_ACCESS`**：UAV 写入的资源若还停在 `NON_PIXEL_SHADER_RESOURCE`，写入被静默丢弃（池纹理留 0、降噪输出全 0，调试层也不报）。每个 pass 之前按上一状态逐个过渡。
- **同一个 pass 里既是 SRV 又是 UAV 的资源要按 UAV 处理**：`OUT_DIFF` 这类资源写完又被下一步当输入读，若最后停在 SRV，UAV 那次写入被丢弃。合并角色时「有 storage 用途就按 UAV」。
- **读回的状态要按记录表取**：探针里读回 `mv` 时写死 `NON_PIXEL_SHADER_RESOURCE`，而它在上一次派发里被当作 UAV 写过，调试层立刻报状态不匹配。按同一张状态表取值后消息归零。

另外两个必需项：NRD 的 2 个采样器（`NEAREST_CLAMP` / `LINEAR_CLAMP`）要自己建并绑到参数 `[3]`，空采样器表会让降噪读到 0；`CommonSettings` 的 `viewZScale` / `denoisingRange`（500000）/ `disocclusionThreshold`（0.01）没有隐式默认值，零初始化会让整屏被当成超距。

7. **每段常量的步长必须是「对齐后的段长」**：`DispatchDesc.constantBufferDataSize` 常是 896 字节，而 `D3D12_CPU_DESCRIPTOR_HANDLE` 要求的 256 对齐只是**起点**要求。若把步长写成 256，后一段的 `memcpy` 会盖掉前一段的字节，所有 pass 都读到别的 pass 的常量（表现为降噪输出只有一条 tile 行有值或全 0，调试层 0 条消息）。正确步长是 `align(desc->constantBufferMaxDataSize, 256)`，实测本库为 1024。

### 23.3 当前状态

游戏外探针（`_port\nrd_pso_probe.exe`，640x360，`REBLUR_DIFFUSE`，RTX 5070 Laptop）已经把整条链跑通：21 次派发、`missing=0`、调试层 0 条消息，读回 `radiance-out` 为 **230400 / 230400 像素非零，均值 0.4995**（输入常量 0.5，静置一帧后基本原样保留）。

排查过程中定位到四条规范要求，都已写进 §23.2：`ReblurSettings` 与 `CommonSettings` 零初始化不是默认值；先 `SetCommonSettings` 再 `GetComputeDispatches`；每段常量的步长必须按对齐后的段长（896 → 1024），否则后一段盖掉前一段、所有 pass 读到别的 pass 的常量。第三条是本次「输出只有一条 tile 行有值或全 0、调试层却一条消息都没有」的真因。

用 `NRD_PROBE_PASSES=<n>` 二分时的对照表（修好步长前）：

| 跑到的 pass | `radiance-out` |
| --- | --- |
| 不跑派发 | 230400 个 0.25（读回与清理通路正常） |
| 14（13 次清理 + Classify tiles） | 0 |
| 15（+Pre-pass，半径 30） | 0 |
| 16（+Temporal accumulation） | 0 |
| 21（全部） | 0 |

修好步长后同样的 21 次派发直接得到 0.4995 的满屏结果，`NRD_PROBE_NOPREPASS` 与 `NRD_PROBE_ACCUM` 两个开关保留下来，作为回归对比用。

### 23.4 引擎侧的接口（S4 4-c，已接好但未打开）

- `MCDR_ABI_VERSION = 13`：新增 `mcdr_dx12NrdDenoise(ctx, textures, textureCount, matrices, frameIndex, outText, capacity)`。五张纹理按 `motion / normal-roughness / viewZ / radiance-in / radiance-out` 的顺序传；`matrices` 是四个列主序 4x4（当前视投影、上一帧视投影、当前世界到视图、上一帧世界到视图），共 256 字节。返回派发的 pass 数，`0` = 没有上下文，`-2` = 没有打开的列表。
- 内核侧在 `MCDR\src\core\d3d12_device.cpp` 的 `nrdDenoise`：先把五张纹理过渡到 `COMMON`（NRD 每个 pass 自己按 SRV/UAV 用，靠 `COMMON` 的自动提升/衰减），派发完再回 `COMMON`，最后 `restoreDrawHeap`；纹理指针来自 `Dx12Object::resource`，与 `dispatchComputeEx` 同一套写法。
- 派发实现仍在 `MCDR\src\advanced\d3d12_nrd.cpp`：`resolveResource` 把 `IN_*` / `OUT_*` 映射到引擎纹理、`PERMANENT_POOL` / `TRANSIENT_POOL` 映射到上下文里的池纹理；描述符堆按帧号奇偶分成两块轮转（`setsMaxNum * perSet / 2` 槽一块），避免 CPU 改描述符时 GPU 还在读上一帧那一块；常量环形缓冲同样按奇偶两块，每段步长取 `align(constantBufferMaxDataSize, 256)`。
- 降噪器换成 `REBLUR_DIFFUSE`：`REBLUR_DIFFUSE_SPECULAR` 需要镜面引导，而我们的 `specular` 还是常数 0.04（见 §19.7）。
- Kotlin 侧 `D3D12RtNrd.denoise(...)` 已就位（含失败只报一次的逻辑），但**还没有接进 `D3D12RtFrame`**：现在的辐亮度是 `direct / indirect / aux` 三块 `D3D12GpuBuffer`，NRD 要的是 `Texture2D`，下一步要么让 `mega_trace.hlsl` 直接写一张 UAV 纹理，要么加一次 buffer→texture 的搬运。

### 23.5 日志

上下文就绪后只打两行（见 §22.4），逐帧派发不打日志；探针的输出不进入游戏日志。

### 23.6 辐亮度纹理化与帧内调用（S4 4-c，已接进帧）

光追的辐亮度是三块 `ByteAddressBuffer`（`direct` / `indirect` / `aux`），NRD 要的是 `Texture2D`，两者之间用一次 compute 搬运（`mega_nrd_radiance.hlsl`，入口 `MegaNrdRadiance`，位于 `Illusion-HPT-Shaders/B-Mega/shaders/`）：

- 输入 `t0 gDirect` / `t1 gIndirect` / `t2 gAux`（都是 `ByteAddressBuffer`，记录 16 字节：辐亮度 3 + 命中距离 1）。
- 输出 `u0 gRadiance`（`RGBA16_FLOAT`），`rgb` 是 `direct + indirect`，`a` 是 `saturate(hitDistance / denoisingRange)`——NRD 的 `IN_DIFF_RADIANCE_HITDIST` 就吃这个打包。
- 屏幕分辨率与追踪分辨率不同，所以每个屏幕像素按 `tracePixel = min(id * traceSize / screenSize, traceSize - 1)` 取最近的追踪像素（`g_screen` 里就是追踪尺寸，见 §19.2）；不插值是因为降噪随后会自己模糊，插值只会把命中距离抹平。
- `denoisingRange` 取 4096 方块（`D3D12RtNrdRadiance.DENOISING_RANGE`），与 `CommonSettings.denoisingRange` 必须一致，否则命中距离的归一化与降噪器的判距不同步。

帧内的接线（`D3D12RtFrame.denoiseFrame`，只在 `[raytracing] denoise` 不是 `off` 时跑）：

1. 取 `D3D12RtMotion.texture()`、`D3D12RtNrdPack.normalRoughnessTexture()` / `viewZTexture()`；
2. `D3D12RtNrdRadiance.render(...)` 把辐亮度搬成 `rt-nrd-radiance`；
3. 用 `D3D12RtNrd.denoise(...)` 跑 NRD，`matrices` 是 `D3D12RtFrameConstants.write` 的 `outNrdMatrices` 给出的四个列主序 4x4（当前视投影、上一帧视投影、当前世界到视图、上一帧世界到视图）。

`rt-nrd-radiance-out` 已经接到合光（`mega_resolve.hlsl`）：

- 第七个 SRV `t6 gDenoised` 绑这张纹理（`D3D12RtCompositeShader.SRV_COUNT = 7`）；降噪没跑的帧绑一张 1×1 的 `rt-nrd-fallback`，避免堆里出现空描述符。
- 根常量 `b1`（`cbuffer MegaResolveParams`）带 `g_denoiseEnabled` 与 `g_denoisingRangeBits`：开关为 1 时辐亮度取 `gDenoised.rgb`、命中距离取 `gDenoised.a * denoisingRange`，为 0 时仍读原来的 `gDirect` / `gIndirect` / `gAux`。两条路都在同一个着色器里，切换不改管线。
- 因此 `[raytracing] denoise = off` 时画面与 S3 完全一致；只有把 `denoise` 选成 `rr` / `nrd_sr` / `megakernel` 才会换成降噪后的辐亮度。

帧内顺序是「搬运 → NRD → 合光」，`D3D12RtFrame.denoiseFrame` 把成功的输出记在 `denoisedRadiance`，合光时取它；`release()` 里连同占位纹理一起释放。

降噪耗时进 `rt_stats.json` 的 `denoise_ms`（时间戳槽 `D3D12RtStats.SLOT_DENOISE`）：降噪没跑的帧由派发链紧跟着光追补一次打点，这一段就记 0，合光的耗时仍从降噪那一点算起。

### 23.8 九项适配的判定方式（S4 4-f 的前半）

`rt_plan.md` §6.7 列的九项里，七项能在源码上判定，逐条写进 `_port/rt_nrd_adaptation_check.py`；另有三条不属于 §6.7 但同样只会静默出错，一并放进同一张表（共 10 条规则，`--selftest` 会故意破坏三处，必须都报出来）：

| 适配项 | 判据 | 违反后的症状 |
| --- | --- | --- |
| 深度约定 | `CommonSettings.viewZScale` 恒 `1.0f`，viewZ 用世界单位 | 降噪的空间判距整体缩放，边缘糊成一片 |
| 命中距离量纲 | `DENOISING_RANGE` 在着色器写 A、在合光乘回，`D3D12RtFrame` 传同一常量的位模式 | 半径淡出的边界与降噪判距不同步，出现一圈硬边 |
| 运动矢量单位 | `motionVectorScale = {1/w, 1/h, 1}`，第三分量为 `1.0f` | 整屏拖影，且不报错 |
| 历史复位 | `accumulationMode` 按 `frame->resetHistory` 分支，且 `CLEAR_AND_RESTART` 只出现一处 | 常态清历史 = 关掉时间累积，噪点压不住 |
| 资源状态 | `nrdDenoise` 进出一律 `COMMON`（两处） | 部分 pass 的 UAV 写入被静默丢弃 |
| 轨道互斥 | `denoiseFrame` 先判 `RtDenoise.OFF` 早退，并先清掉上一帧的输出 | 关光追也照跑降噪，画面被上一轨的结果污染 |
| 时间抖动 | `cameraJitter` / `cameraJitterPrev` 显式写 `0.0f` | 哪天给追踪加抖动却忘了改这里，重投影对不上 → 整屏拖影 |
| 引导附件数 | `D3D12RtGBuffer.ATTACHMENTS == 5` | 多一张附件会与复用主 pass RTV 数的 PSO 不匹配，整片被拒 |
| 粗糙度下限 | 打包前 `max(roughness, 1.5/512)` | 粗糙度为 0 时 F0 爆掉，出现黑点 |
| 降噪输出的取样位置 | `mega_resolve.hlsl` 里 `gDenoised.Load(int3(pixel, 0))`，不得用 trace 像素索引 | 降噪输出是屏幕分辨率，按 trace 索引只读到左上角一小块 → 整屏一片低频色块 |

剩下两项——「噪点是否可接受」与「有没有鬼影」——只能肉眼判断，交给验收时看图。

### 23.7 历史累积与复位（S4 4-e 的一部分）

`CommonSettings.accumulationMode` 不再每帧都写 `CLEAR_AND_RESTART`：那样等于关掉时间累积，噪点只能靠空间模糊压。规则是

- 常态 `CONTINUE`，让 REBLUR 累积历史；
- 只在三种情况下丢历史（`mcdr_nrd_frame.resetHistory = 1`）：轨道或分辨率变了、目标纹理重建、进程起来的前三帧（`D3D12RtFrame.denoiseSignature` 与 `frameCounter` 判定）。

`mcdr_dx12NrdDenoise` 因此多一个 `resetHistory` 参数（`MCDR_ABI_VERSION` 13 → 14）。切轨道不丢历史会把上一轨的分辨率与 jitter 搬过来，症状是切换那一帧整屏拖影。

## 24. DLSS Ray Reconstruction 的可用性（S4 4-d 的前置判定）

4-d 要接的是 DLSS-D（Ray Reconstruction）：它把带噪辐亮度加法线/粗糙度/深度/运动矢量一次吃进去，
直接吐出降噪并放大后的图像，与 4-c 的 NRD 是同一条轨道上的两个选择。接之前先确定本机能不能用。

### 24.1 判定用的两个能力键

NGX 的 RR 可用性不叫 `RayReconstruction.Available`，而是走 DLSS-D 自己的名字（`MCDR\external\dlss\include\nvsdk_ngx_defs_dlssd.h`）：

| 键 | 字符串 | 含义 |
| --- | --- | --- |
| `NVSDK_NGX_Parameter_SuperSamplingDenoising_Available` | `SuperSamplingDenoising.Available` | 本机是否支持 RR，1 表示支持 |
| `NVSDK_NGX_Parameter_SuperSamplingDenoising_FeatureInitResult` | `SuperSamplingDenoising.FeatureInitResult` | 特性初始化结果，NGX 的 `0x1` 是成功 |

拉能力参数的固定顺序与 `MCDR\src\advanced\d3d12_dlss.cpp` 一致：
`NVSDK_NGX_D3D12_Init_with_ProjectID` → `NVSDK_NGX_D3D12_GetCapabilityParameters` → 逐键 `NVSDK_NGX_Parameter_GetI`。

### 24.2 为什么必须放在独立进程里问

NGX 要求 `nvngx_dlssd.dll` 与进程同目录（它的加载器按进程目录找 DLL），而游戏进程里 NGX 由 Streamline 持有
——`MCDR\src\advanced\d3d12_dlss.cpp` 里写明：SL 拥有 NGX 时再调 `NVSDK_NGX_D3D12_Init_*` 会撞 `0xBAD00005`。
所以可用性用独立探针问，不进游戏：源码 `_port\ngx_rr_probe.cpp`、产物 `_port\ngx_rr_probe.exe`，
运行时把 `nvngx_dlssd.dll` 与 `nvngx_dlss.dll` 拷到它旁边，`_port\rt_check_all.py --gpu` 会自动做这一步。

构建（vcvars64；NGX 的导入库只声明 `msvcprt`/`MSVCRT`，所以必须 `/MD` 并屏蔽静态 CRT，另需 `advapi32`/`user32`）：

```
cl /nologo /std:c++latest /EHsc /O2 /MD ngx_rr_probe.cpp /I ..\MCDR\external\dlss\include ^
   /Fe:ngx_rr_probe.exe /link /NODEFAULTLIB:libcpmt /NODEFAULTLIB:LIBCMT ^
   ..\MCDR\external\dlss\lib\Windows_x86_64\x64\nvsdk_ngx_d.lib d3d12.lib dxgi.lib uuid.lib advapi32.lib user32.lib
```

### 24.3 本机实测

RTX 5070 Laptop，驱动高于最低要求：

```
ngx api version=21
device: NVIDIA GeForce RTX 5070 Laptop GPU
init: success (0x00000001)
capability parameters: success (0x00000001)
rr available=1 call=success
rr feature init result=1 call=success
sr available=1 call=success
needs updated driver=0 call=success
min driver major=470 call=success
min driver minor=0 call=success
```

结论：本机 RR 可用，4-d 按 DLSS-D 接，不必退到 `rt_plan.md` §6.4 的 FFX 备选。
游戏进程内走 Streamline 的 `sl.dlss_d` 插件（`Illusion\run\dxrmc\sl.dlss_d.dll` 已在运行目录），
接入时用 `sl::kFeatureDLSS_RR` 取函数，不要再自己调 `NVSDK_NGX_D3D12_Init_*`。

### 24.4 门

`_port\rt_check_all.py --gpu` 的 `ngx rr` 一步：探针缺失、或本机报不支持，都只打印 `SKIP`（那是机器与驱动的差异），
只有探针跑不起来才算 `FAIL`，避免把别的机器上的能力差异当成本仓库的回归。

### 24.5 游戏内那条能力行

设备挂接时（`MCDR\src\core\d3d12_device.cpp` 的 `applyStreamline`）打一行，把「插件支不支持、加载没有、
给定 1920x1080 输出该渲染多大」一次说完：

```
streamline: dlssd supported=1 load=0x0 optimal=1280x720 call=0x0
```

- `supported` 是 `slIsFeatureSupported(sl::kFeatureDLSS_RR, adapterInfo)` 的结果，0 表示这台机器的驱动/GPU 不支持。
- `load` 是 `slIsFeatureLoaded` 的返回码，插件加载成功时是 `0x0`。
- `optimal` 是 `slDLSSDGetOptimalSettings` 给的 `renderWidthMin x renderHeightMin`（`eMaxQuality`、输出 1920x1080）。
- 最后一个 `call` 是 `slDLSSDGetOptimalSettings` 的返回码。

三个条件都成全才允许走 RR 轨道；`kFeatureDLSS_RR` 已加进 `slInit` 的 `featuresToLoad`（与 DLSS、Reflex、PCL、DLSS-G 并列），
漏了它插件根本不会加载、`load` 会一直非零。

### 24.6 Streamline 进程里的实际判定

独立进程的结论（§24.3）不能直接推广到游戏进程：那里 NGX 归 Streamline 持有，插件是否加载由 SL 决定。
同一台机器上 `dlssd available=1`，而游戏里的 `Illusion\run\dxrmc\sl.log` 记的是：

```
[getNGXFeatureRequirements] ngxResult failed 0xbad00005
[updateEmbeddedJSON] DLSSD feature is not supported …
Ignoring plugin 'sl.dlss_d' since it is not supported on this platform
```

成因是时序：SL 在 plugin mapping 阶段就问「这个特性支不支持」，而那一步要打进 NGX 拿 feature requirements
（`0xbad00005` 即 `NVSDK_NGX_Result_FAIL_InvalidParameter`，此时 NGX 尚未由 plugin startup 初始化），
于是插件被判定不支持并被丢弃。

后果与处置：`RR` 轨道拿不到求值（`D3D12RtDlssd.evaluate` 返回 0），`D3D12RtFrame.denoiseFrame` 打一行
`rt dlssd: unavailable (…), falling back to nrd` 后整局走 NRD（§25.3）。这是设计内的回退，不是失败。

不要在 `slInit` 之前 `LoadLibraryW("nvngx_dlssd.dll")` 抢先初始化：实测该调用会让进程直接退出
（没有任何输出，退出码 1）。

## 25. DLSS-D 逐帧求值（S4 4-d）

### 25.1 一帧的动作

求值在 native 的 `mcdr_dlssd_eval`（`MCDR\src\advanced\d3d12_dlss.cpp`）里，Java 侧只负责凑齐纹理：

1. 选项只在输出尺寸或档位变化时下发一次（`slDLSSDSetOptions`）：`eMaxQuality`、`colorBuffersHDR=eTrue`、
   `alphaUpscalingEnabled=eFalse`、四个 preset 全 `ePresetF`；尺寸取自 `outputWidth/outputHeight`。
2. `slGetNewFrameToken` 取这一帧的 token（每帧都要新取，不能复用）。
3. 打标签（`slSetTagForFrame`），顺序固定：

| 槽 | `sl::BufferType` | 资源 | 生命周期 |
| --- | --- | --- | --- |
| 0 | `kBufferTypeScalingInputColor` | `rt-nrd-radiance`（带噪辐亮度） | `eOnlyValidNow` |
| 1 | `kBufferTypeScalingOutputColor` | `rt-nrd-radiance-out` | `eOnlyValidNow` |
| 2 | `kBufferTypeDepth` | `rt-gbuffer-depth`（D32_FLOAT） | `eValidUntilPresent` |
| 3 | `kBufferTypeMotionVectors` | `rt-motion`（像素单位） | `eValidUntilPresent` |
| 4 | `kBufferTypeNormalRoughness` | `rt-nrd-normal-roughness`（打包） | `eOnlyValidNow` |
| 5 | `kBufferTypeRoughness` | 仅 unpacked 模式才传 | `eOnlyValidNow` |

4. `slEvaluateFeature(sl::kFeatureDLSS_RR, token, {viewport}, 1, list)`。

纹理与资源状态的对应关系是硬要求：输入必须停在 `NON_PIXEL_SHADER_RESOURCE`、输出停在 `UNORDERED_ACCESS`，
否则 SL 打标签成功但读到的内容未定义。`dlssdEval`（`MCDR\src\core\d3d12_device.cpp`）负责在调用前后做这组
过渡，返回后统一回 `COMMON` 并恢复绘制用的描述符堆。

### 25.2 为什么法线-粗糙度是「打包」而不是两张图

DLSS-D 的 `normalRoughnessMode` 只有两种：`ePacked`（一张打包图）与 `eUnpacked`（法线 + 粗糙度分开）。
本工程目前没有单独的粗糙度纹理，走 `ePacked`，把 `rt-nrd-normal-roughness` 直接喂给它。

已知偏差：那张纹理是 `RGBA16_FLOAT`，而 NRD 库内部按 `R10_G10_B10_A2_UNORM` 解释同一套归一化值
（见 §21.3）。数值分布一致、只是每像素多 8 字节；一旦将来为 RR 单独准备 R10G10B10A2 纹理，
必须同时改 `dlssdEval` 的纹理来源与这一节。

### 25.3 回退

`RR` 轨道上 SL 拒绝一次（返回 0 或负数）就整局不再尝试，打一行
`rt dlssd: unavailable (<原因>), falling back to nrd`，这一帧起退回 NRD 的 `REBLUR_DIFFUSE` 路径。
宁可降噪器与配置档不符，也不要画面突然回到未降噪。设备重建或分辨率变化会 `D3D12RtDlssd.reset()`，
给 SL 一次重新尝试的机会（选项按输出尺寸缓存，尺寸变了必须重发）。

### 25.4 日志

```
dlssd: eval 0x0 consts=0x0 2560x1334 -> 2560x1334 packed=1
```

六个字段：`slEvaluateFeature` 返回码、`slSetConstants` 返回码、渲染尺寸、输出尺寸、是否打包模式。
两个返回码分开看是关键：`eval` 非 0 而 `consts` 为 0 说明常量没问题、问题在标签或求值；`consts` 非 0
说明常量本身就过不去（多半是矩阵布局或必填字段）。

### 25.5 验收

进游戏把 `[raytracing] denoise` 设为 `rr`，日志里应出现上面那条 `dlssd: eval`；把它设回 `nrd_sr` 或
`megakernel` 则只看到 `nrd: dispatched … pass(es)`。两者都不该每帧刷屏：成功只报一次。

### 25.6 相机常量（`slSetConstants`）

重投影靠 SL 的相机常量，缺了它 SL 不报错、只按「零位移」重投影——画面表现为拖影，而不是报错。
常量在 Kotlin 侧由 `D3D12RtFrameConstants.write` 的 `outSlConstants` 出（72 个浮点），
`mcdr_dlssd_frame.constants` 透传到 `d3d12_dlss.cpp`，再由 `slSetConstants` 交给 SL：

| 偏移 | 内容 | 来源 |
| --- | --- | --- |
| 0 | `cameraViewToClip`（行主序） | 本帧投影矩阵 |
| 16 | `clipToCameraView` | 投影矩阵求逆 |
| 32 | `clipToPrevClip` | 上一帧视投影 × 本帧视投影的逆 |
| 48 | `prevClipToClip` | 上式的逆 |
| 64 | `cameraNear` / `cameraFar` / `fovY` / `aspect` | 投影矩阵的构造参数 |

三条硬约定：

- **行主序**。SL 的 `float4x4` 是 `row[4]`（`MCDR\external\streamline\include\sl_consts.h`），
  与 HLSL 的列主序相反；JOML 按列存，所以 `putRowMajor` 逐元素转置写入。
- **不含抖动**。`jitterOffset` 恒为 `0`，矩阵也不许含抖动偏移；本渲染器不做时间抖动，
  哪天加了抖动必须同时改这两处，否则 SL 按抖过的投影重投影、而我们的运动矢量没抖。
- **运动矢量单位**。我们的 MV 是像素单位且 +y 朝下，所以 `mvecScale = {1/w, 1/h}`，
  与 NRD 的 `motionVectorScale` 同一口径（见 §23.8）。

验收判据：`rr` 与 `nrd_sr` 两档同一场景的差异只在降噪质量（噪点、边缘），不在重投影（拖影、边缘重影）。

## 26. 渲染倍率与上采样（S4 4-e）

`[raytracing] renderScale` 决定光追那条链按显示分辨率的百分之几出图，合光再把它拉回显示尺寸。
今天只有 trace 受它影响；G-Buffer 与合光仍按显示分辨率跑（见 §26.2）。

### 26.1 倍率怎么落到尺寸上

`D3D12RtFrame.traceDivisor` 把倍率乘进「目标高度」：

```
目标高度 = clamp(160 × renderScale%, 40, 160)
除数     = max(ceil(显示高度 / 目标高度), 4)
trace    = 显示尺寸 / 除数
```

三处硬约定：

- **`OFF` 轨忽略倍率**（按 100% 算），与 §18 的真值表一致；否则「光追没开」也会因为倍率改动而改画面。
- **除数下限仍是 4**：B-Mega 是逐像素线性扫整池，全分辨率在真实世界会把一帧跑到几十秒，
  倍率只在这个下限之上再往下降。
- **目标高度下限 40 像素**：再低就没有降噪的余地，噪点会把画面糊成一片。

日志用现成的 `rt frame: dispatch <w>x<h> of <displayW>x<displayH>` 核对：把倍率从 100 调到 50，
`<w>x<h>` 的高度应当接近减半（受除数下限约束，可能不变）。

### 26.2 为什么 G-Buffer 不跟着缩

G-Buffer 那个 pass 是在主 pass 结束后立刻重放同一批网格（`recordGBufferPass` 复用主 pass 的 PSO 变体），
它的 RTV 尺寸必须与主 pass 一致，否则 `CreateMeshPipelineWithRootSignature` 建出来的变体对不上，
整片被拒。要让它缩小，就得把主 pass、阴影、粒子一起缩，那是另一个阶段的范围。

代价是清楚的：G-Buffer 的 albedo / normal 仍是全分辨率，只有辐亮度是低分辨率 —— 合光时用双线性
上采样补回来（§26.3），边缘会略软，但比最近邻的方块好得多。

### 26.3 合光里的双线性上采样

`mega_resolve.hlsl` 的采样从最近邻换成四邻域加权：

- 覆盖判定（`direct.w` 是否命中、`albedo.a` 是否写过）仍取**最近邻**那一格：
  插值会把命中标记抹成小数，几何边缘就会渗色。
- 辐亮度与命中距离按四邻域权重插值，保证 `albedo × radiance` 与淡出用的命中距离来自同一位置。
- **降噪输出不插值**：`rt-nrd-radiance-out` 是**屏幕分辨率**（NRD 与 DLSS-D 都按显示分辨率跑），
  按当前像素直接 `gDenoised.Load(int3(pixel, 0))`。拿 trace 像素去索引它只会读到左上角一小块，
  表现为「降噪开着时整屏一片模糊的低频色块」。

低分辨率下的方块噪点因此在屏幕上变成平滑过渡；倍率越低，越依赖降噪把细节补回来，这是
`MEGAKERNEL`（不降噪）在高倍率缩放下明显更难看的原因。

## 27. G-Buffer 变体与着色器链的依赖

世界里能出光追的前提是地形管线带一个 **G-Buffer 变体**：`tryMeshletDraw` 只有在
`hasGBufferVariant()` 为真时才捕获批次，`recordGBufferPass` 才有东西重放。变体缺失时主 pass 照常渲染，
但 `rt frame: dispatch` 一次都不会出现，几何池恒为 0 段，合光打一行
`rt composite: skipped, missing inputs` 就结束 —— 看上去像「整套管线没生效」。

变体由 `D3D12RtGBufferShader.build()` 从当前着色器链产出的 HLSL 生成，针脚按 SPIR-V Cross 的形态写
（`struct SPIRV_Cross_Input`、`void frag_main()`、`verts[vid] = vs_main(vin);`，见 §8）。
换一条不产这种形态的转换链，`build()` 找不到针脚就返回 `null`，变体随之消失。

因此主 pass 的转换链上有一条与 meshlet 包装同款的守卫（`D3D12Device.createPrimaryPipeline`）：
光追开着、管线又属于 G-Buffer 收录范围（位置里不含 `translucent` / `oit`）而变体建不出来时，
这条管线回 SPIR-V 链，日志记一行
`shader converter primary: <pipeline> keeps the old chain (gbuffer variant: <原因>)`。
回退不会让地形消失 —— 旧链本来就能画，只是少了转换链的收益。

三条日志可以判断状态：

| 日志 | 含义 |
| --- | --- |
| `gbuffer: variant ready (<pipeline>)` | 变体建起来了，世界里应当出现 `rt frame: dispatch` |
| `gbuffer: variant missing (<pipeline>)` | 变体是 0，光追链不会启动 |
| `gbuffer: variant unavailable (<原因>): <pipeline>` | `build()` 失败的具体针脚，与上表第二行成对出现 |

排查顺序：先看 `meshlet: d=` 是否在涨（主 pass 在画），再看 `gbuffer: variant` 三行之一，
最后才是 `rt frame: dispatch` 与 `rt stats:`。

光追开着而几何池连续 3 秒一段都没有时，`D3D12RtFrame.tick()` 会自己报一行：

```
rt: no geometry after 3s, gbuffer replay never ran
```

这行意味着重放一次都没发生（不是「光追画得慢」），最先怀疑的就是变体没建起来：
`hasGBufferVariant()` 为假时 `tryMeshletDraw` 不捕获批次，几何池因此永远是空的。

