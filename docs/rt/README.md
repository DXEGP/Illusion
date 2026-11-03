# 光线追踪：配置、几何、硬件路径与降噪接口

> **当前实现**：内置包为 B-DXR，生产追踪与场景自检均通过 BLAS/TLAS、状态对象、SBT 和 `DispatchRays` 执行。`AUTO` 使用 DXR；WG 调度尚未实现，选择 WG 会报告回退 DXR。没有硬件 DXR 能力时关闭光追。
>
> **RAW 模式**：`[raytracing] denoise = "RAW"` 保留硬件追踪与合光，跳过降噪和时域重建。启动时自动迁移旧包名和旧后端、无降噪配置；迁移不提供软遍历兼容路径。
>
> **已知限制**：纯白块、方块更新后的旧几何/旧阴影、RT 范围内周期闪烁与范围外固定节奏抖动仍需用户目视复验。首次加速结构未就绪或没有有效快照时整帧直通原版；已有快照的变更按 section 体积屏蔽受影响光线。失效 section 包含相机时可能影响整个视野，失效边界与重建恢复仍需验证。native 帧所有权尚未完成。不同降噪器与 SR 方案分别验收，不设最终图像一致等级。

下面按接口与资源列出配置、日志、查询、几何导出、G-Buffer、追踪、降噪和合光。
DXR 的状态对象、SBT 与 `DispatchRays` 接线见 §16，NRD、RR 与 SR 适配见 §18 及后续章节；
当前资源与派发仍由 JVM 管理，native 帧所有权是后续目标。
世界几何的采集与 meshlet 在 [`mesh&vrs/README.md`](../mesh&vrs/README.md) §2.1 里。

## 1. 配置

全部在 `config/dxr_mc-client.toml` 里：`[raytracing]` 段的键改完不用重启，下一次 `pushToNative()` 生效；
`[shaderpack]` 段只有一个键，决定光追使用的 HLSL 包，见 §11。

`[raytracing]` 这组键是**包作用域**的：界面入口只有「视频设置 → 光影包 → 光影包设置」，由包在
`shaders/configs.json` 里声明要暴露哪几项（§11.6）；TOML 是这些值的存储位置，不是给人手改的开关面板。

| 键 | 默认 | 取值 | 作用 | B-DXR 生效 |
| --- | --- | --- | --- | --- |
| `backend` | `AUTO` | `AUTO` / `DXR` / `WG` | AUTO 使用 DXR；WG 当前回退 DXR；无硬件 DXR 时关闭光追 | 是 |
| `qualityProfile` | `BALANCED` | `VALIDATION` / `BALANCED` / `QUALITY` | 档位只调参数，不跳门 | 否 |
| `spp` | `1` | 1–16 | 验证 profile 是 1 | 否 |
| `maxBounces` | `2` | 1–8 | 路径最大弹射 | 否 |
| `samplingMode` | `BSDF` | `BSDF` / `COSINE` / `RESERVOIR` | `RESERVOIR` 只用于间接漫反射 | 否 |
| `voxelTrustRadius` | `8.0` | 0–32 | 体素轨的信任半径（米） | 否 |
| `hzb` | `false` | 布尔 | HZB 剔除（第一版只靠 `InstanceMask`） | 否 |
| `historyFrames` | `16` | 1–64 | 历史长度上限，降噪与缓存都夹到它 | 否 |
| `debugView` | `NONE` | 见下 | 调试视图 | 是 |
| `invalidationLog` | `false` | 布尔 | 每次历史失效打一行原因位 | 否 |
| `[shaderpack] active` | `B-DXR` | 包名；空串表示无光影 | 选择 HLSL 包并应用包声明的硬件后端 | 是 |

`debugView` 取值：`NONE` / `DEPTH` / `NORMAL` / `ALBEDO` / `ROUGHNESS` / `HIT_DISTANCE` / `SHADOW` / `COMPOSITE_MARK` /
`DIRECT` / `INDIRECT` / `CACHE_CONFIDENCE` / `REACTIVE_MASK` / `MOTION_VECTORS` /
`HISTORY_REJECTION` / `TRAVERSAL_STEPS` / `DUAL_TRACK` / `GEOMETRY` / `BLAS`。
B-DXR 真正接通的有七个：`DEPTH`（第五张线性深度附件）、`ALBEDO`、`NORMAL`（`debugChannel` 模式 4：
`0.5 + 0.5 * n`）、`ROUGHNESS`（`debugChannel` 模式 3，取 `g_NormalRoughness.w`）、
`DIRECT`（合光 `g_resolvePad.y = 1`：只显示追踪出的辐亮度，不乘 albedo、不做边缘淡出，没命中写黑；
整屏黑 = 射线没打到几何，有明暗 = 追踪有东西但画面接近原版）、
`HIT_DISTANCE`（同一分支的 `2` 档：命中距离按 64 格归一化成灰度，没命中写黑），
`COVERAGE`（`3` 档：`albedo.a >= 0.5` 写白，用来判断 G-Buffer 到底覆盖了多大面积），
`SHADOW`（`5` 档：被太阳挡住红、没挡住深灰、未覆盖黑；可见性由 `rt_dxr_lib.hlsl` 写进 `gAux.y`），

每 120 次上屏还会打一行 `rt blit: <n> tries, <k> fail, last <w>x<h>`：`fail` 增长说明合光产出了但没搬上屏（目标是 `null` 或尺寸不符），`source=none` 说明合光本身没返回纹理——两种「画面没变化」的成因靠这一行区分。
以及 `COMPOSITE_MARK`（`6` 档：整屏常量品红，只证明「合光算完并搬上主目标」这条通路通不通）和
`BLAS`（包内 `rt_debug_blas.hlsl` 画实例线框）。
其余枚举值切过去画面会停在合成结果上（不铺任何调试图），它们等各自的 RT pass 出现才接线。

B-DXR 固定 1 spp，分别采样漫反射与 GGX 镜面的一次间接弹射。降噪、倍率和曝光按 §18、§26 生效；采样质量、体素、HZB 等预留参数不改变追踪实现。

## 2. 日志

设备创建后第一次 `pushToNative()` 打两行：

```
RT caps: rt=12 mesh=10 wg=10(queried) vrs=2 sm=0x69 ser=0 dxc=1 sched=WG note=ok
RT config: backend=AUTO mode=3 profile=BALANCED spp=1 bounces=2 sampling=BSDF hzb=false
RT config: history=16 view=NONE invlog=false trustRadius=8.0 pack=B-DXR
```

- `wg=…(queried)`：`unqueried` 表示 `D3D12_FEATURE_D3D12_OPTIONS21` 查询失败，此时
  `workGraphsSupported` 一律为 false——"没问成"和"不支持"必须分开。
- `ser=0` 恒成立：本工程的 Windows SDK（10.0.26100）没有 `OPTIONS22`，等换 `directx-headers`。
- `sched=…`：**只看能力**的首选后端（WG → DXR；无候选时为 OFF）。真实后端还要乘验证状态与用户选择。

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
    if (!caps.workGraphsSupported) { /* 退回 DXR；DXR 不可用时关闭光追 */ }
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
B-DXR 的硬件追踪着色器当 `ByteAddressBuffer` 读。仅在 `D3D12Settings.rtEnabled` 为真时收集；
`AUTO` 与 `DXR` 都可以使用该缓存，未选包、mode 为 OFF 或硬件不支持 DXR 时关闭。

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
仅在 `D3D12Settings.rtEnabled` 为真时工作；`AUTO` 同样执行 G-Buffer 与光追合成。

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

- 选择 B-DXR，令 `[raytracing] mode` 为非 OFF 值；设备需支持硬件 DXR；
- `[raytracing] debugView` 设 `ALBEDO` / `NORMAL` / `DEPTH` / `ROUGHNESS`，主目标上直接显示那一张；
  前三张直接 blit，`ROUGHNESS` 要先过一遍解包 compute（见 §9.5）。

改 `debugView` 不用重启游戏：配置文件被监听，几秒内下一次合光就走新档位。

每帧数据流：

1. 主 pass 里每次地形 `DispatchMesh` 成功（`D3D12RenderPassBackend.tryMeshletDraw`）就记一条
   `D3D12RtGBuffer.Batch`——几何句柄与组数，以及该次绘制的 uniform slice 和 texture view 绑定快照；
2. 主 pass 结束（`D3D12CommandEncoderBackend.submitRenderPass`）时若捕到过批次，立刻开一个带
   五张颜色附件 + 自己那份深度的 pass，`colorClearFlags` 全 1（每帧清），深度清成 0.0；
3. `D3D12RenderPassBackend.replayGBuffer()` 逐批重放：重建 globals（组数、`cull = 0`、VRS 档位
   全 1X1）→ `D3D12SetPipeline` 到 G-Buffer PSO → 从该批次快照重新 push 描述符 → `D3D12MeshDrawMeshlets`。
   `Projection` 绑定替换为本次重放独立上传的 64 字节采样矩阵，切片按 256 字节对齐，原版 UBO 保持不变；
4. 所有批次成功才提交本帧偏移并继续追踪。任一上传、绑定或派发失败即停止，不发布部分 G-Buffer；
5. End，主目标保持稳定投影。新关卡投影上传与重放开始均撤销旧光追输出资格，避免失败时合成上一帧结果。

必须是独立 pass：native 的 `beginRenderPass` 在 pass 已打开时直接报 `render pass already open`，
主 pass 里嵌不进第二段带不同附件的渲染。代价是主目标与深度各多两次状态切换。

捕获批次时复制绑定映射，重放时禁止读取主 pass 结束时的最后一组纹理和 uniform。
快照里的 slice/view 只在本帧消费；后续批次改绑不能改变前面批次使用的图集与常量。

重放复用主 pass 的几何与材质绑定，投影使用 RT 专用采样切片；其它差异如下：

- **组数用原始 meshlet 数**，不是 `(n + 31) / 32`：G-Buffer 的 MS 恒不带 AS（带 payload 的
  `ms_main` 在没有 AS 时是非法入口），cull 也就在这一遍关掉；
- **深度用自己那份、写深度、`GREATER_THAN_OR_EQUAL`**：不借主 pass 的深度（理由见上）；
- **PSO 的颜色槽是五张 G-Buffer**：`D3D12Device.createGBufferVariant` 只为 `[mesh] terrain` 的
  SOLID / CUTOUT 地形管线建，半透明与 OIT 不建。

着色器怎么来的：`D3D12RtGBufferShader.build` 拿地形管线原来的 VS / PS HLSL 改写——MS 由
`D3D12MeshletShader.build` 生成后多输出一条 `float3 gbufWorldPos`（占下一个空闲 `TEXCOORD`，
相对本帧实际射线原点，见 §19.2）；PS 从 spvc 入口之前的前缀里捞出纹理、采样器、UV0、Color 字段，
拼上光影包的 `B-DXR/shaders/rt_gbuffer_ps.hlsl` 模板（占位符在运行时替换）得到 `gbuf_main`：反照率 = `pow(采样, 2.2)`（有顶点色再乘），面法线 =
`normalize(cross(ddy(worldPos), ddx(worldPos)))` —— **顺序反了**（D3D 屏幕 y 朝下，`cross(ddx, ddy)`
得到的是朝里的法线，地面会存成 `-Y`）。光照路径按射线方向自己翻面，所以顺序错了只影响存进 G-Buffer 的法线，
`debugView=NORMAL` 会整片洋红。

反照率取「图集采样 × 顶点色」：着色器里按 `register(t<n>, space<n>)` 找 PS 的 `Texture2D` 与同名寄存器的
`SamplerState`，用 `SampleLevel(..., 0.0)` 取 0 级 mip——图集里相邻精灵挨着，各向异性过滤会把邻块像素带进来。
spvc 的声明带 `space` 限定，正则漏掉它就会一路退回常量 `0.6` 灰色（`detail` 里显示 `albedo=constant*…`）。
找到的那一条 varying 名为 `gbufWorldPos`，语义取 `SPIRV_Cross_Output` 里最大 `TEXCOORD` 号 + 1。

G-Buffer 的 MS 在 `vs_main` 后用原始顶点 `Color` 覆盖输出的颜色 varying，保留群系染色与顶点 AO。
禁止直接使用原版 `Color * sample_lightmap(...)` 作为反照率，否则原版光照会再次乘进光追照明。

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

选择变化立即生成新的镜像，不再按固定帧数延后。近场记录及其版本未变时复用同一个 `MirrorSlice`，
远处区块上传不重建近场数据。GPU 镜像按 CPU 快照身份与材质 revision 判定复用；
相机移动后选中了不同记录时，即使全局 geometry revision 未变，也必须上传新镜像。
镜像上传日志首次为 INFO，后续为 DEBUG。

整段提交保留仍存在的层，再比较每个顶点的 position、颜色、UV（前 24 B）与索引内容。
仅重复上传或原版 lightmap（末 4 B）变化时，复用 RT 记录、版本与池切片；RT 光照不读取该 lightmap。
真实位置、UV、颜色、索引或层集合变化仍更新快照。注册表的上传版本与 RT 内容版本可以不同。

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

### 9.3 追踪尺寸与几何预算

`D3D12RtPipelinePlan` 统一决定 trace 与 G-Buffer 的 render 尺寸，追踪不再有独立低分辨率上限。
`TRACE_RADIUS = 96.0f`、`MIRROR_RECORDS = 384`、`MIRROR_BYTES = 128 MB` 限制近场镜像。
硬件 BLAS/TLAS 处理求交，镜像选择按段中心到相机的距离裁剪。几何或材质改变立即刷新所选镜像；
只有当前镜像的加速结构全部就绪才派发。远处上传未改变近场集合时复用镜像与历史。

### 9.4 解调纪律

光追着色器只写「到达表面的辐射度」：`gDirect` = 太阳项直接可见性乘积，`gIndirect` = 一次余弦弹射的
结果（未命中给天空色）。`gAux.xyz` 保存主命中距离、太阳可见性与 diffuse 弹射距离，
`gAux.w` 保存主命中材质 ID 的原始 uint 位。`gSpecular` 与 `gSpecularGrazing` 保存镜面 Schlick 系数，见 §17.2。
主表面材质只在重调制阶段乘一次：

一次弹射写的是**命中面的出射辐亮度**：`emissive + albedo * (sun 项 * 可见性 + sky 项)`。
漏掉这个 `albedo` 会让背光的第二命中面贡献 0，合光乘上主表面 albedo 后就是地面上的一块纯黑；
末尾的 `sky` 项是「弹射到此终止」的近似环境光（§4.9 允许把超距当环境光），不是第二次弹射。

弹射打空按弹射起点和 `bounceDir` 计算环境辐亮度；二次命中的终止环境使用
`WgaAmbientApprox()`，只依赖太阳高度。禁止将主射线方向的天空颜色复用于二次命中照明。
`debug mode 9` 使用固定环境常量，供 `D3D12RtLightContract` 的 CPU 参考对拍。

```
if (direct.w == 0) out = 原版像素          // 没有光追数据：天空、实体、追踪半径外
else {
    linear = albedo * (direct + indirect) + specular * f0 + specularGrazing + emissive
    color = WgaLinearToSrgb(WgaTonemapAces(linear * exposureTexture[0,0]))
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

`D3D12RtStats` 用一条 8 槽的时间戳池记录帧起点与六个阶段终点（G-Buffer / 光追 / 降噪 / SR / 合光 / 上屏），
每满 5 秒把上一窗口的一行 JSON 追加到 `<user.dir>/dxrmc/rt_stats.json`。帧计数在帧开始时自增、`total_ms` 取「最深的那个已打点槽位」，所以上屏那一步失败时 JSON 仍会落盘（只是 `blit_ms` 为 0）——这一格停更过一次，整份基线跟着停。

```json
{"time":"…","tier":"dxr","frames":…,"width":…,"height":…,"trace_width":…,"trace_height":…,"records":…,
 "triangles":…,"gbuffer_ms":…,"trace_ms":…,"denoise_ms":…,"sr_ms":…,"composite_ms":…,"blit_ms":…,"total_ms":…,
 "cpu_ms":…,"dispatches":…,"vram_mb":…,
 "blas_builds":…,"blas_refits":…,"blas_compacts":…,"blas_ms":…,"blas_build_us":…,
 "blas_vram_mb":…,"blas_scratch_mb":…,"tlas_instances":…,"tlas_ms":…}
```

`tier` 记录实际追踪后端，当前成功派发为 `dxr`。


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

`D3D12RtTrace` 使用 DXR 执行四项检查：常量与输入缓冲探针、空视野、单三角形、地面与遮挡板。
空视野通过退化三角形建立合法 BLAS，要求没有主射线命中。其余场景须实际命中，阴影场景同时要求直射与遮挡。
CPU 参考独立计算命中、材质和辐亮度；受控场景的离散量和数值容差用于检查算法与绑定，
不用于比较 NRD、RR、XeSS 等最终图像。阴影场景的间接光擦边命中不作逐像素门槛。

自检等待 200 tick，读回等待最多 3 秒，连续推迟 8 次后报告失败。换包重置自检状态。
报告写入 `dxrmc/rt_selftest.json`，前次运行保存为 `rt_selftest.prev.json`。
`tier` 使用最近实际派发档位；未派发时为 `none`。`backend` 是配置序号（AUTO=0、DXR=1、WG=2）。
报告保留毫秒时间戳 `time`、`pass`、`step`、`detail`、`scenes`、`contract`、`blas`、`sbt`、`frame`，
几何和加速结构分别包含世界实例与自检实例的摘要。自检没有 compute 软遍历基线。

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

- 天空走光影包的解析大气（`rt_dxr_lib.hlsl` 的 miss 分支调 `WgaEnvironmentRadiance`），不是原版那张贴图，
  所以天顶/地平线的颜色与原版不完全一致；主光线在镜像范围外没命中任何三角形时合光仍放过原版像素。
- 半透明、实体、粒子还没进 G-Buffer：它们既不写反照率也不遮挡，站在它们后面看到的是追踪出的地形/天空。
- 太阳方向读取同帧 `levelRenderState.skyRenderState.sunAngle`（弧度），按原版天空的
  Y 轴 −90°、X 轴太阳角旋转得到 `(-sin(angle), cos(angle), 0)`。保留地平线以下的方向，
  阴影方向与长度随世界实际太阳位置变化；太阳辐亮度仍为 `SUN_COLOR × SUN_STRENGTH` 常量。
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

着色器**不在 Kotlin 里**，也不在 mod 仓库里：S2 的 B-DXR 后端整套源码在光影包
`Illusion-HPT-Shaders\B-DXR\shaders\`（与 `WorkGraph-Advanced\` 平级，后者是 Work Graph 后端的目标形态）。
一个包能不能被选中、选中之后怎么生效，见 §11。

| 文件 | 入口 | 作用 |
| --- | --- | --- |
| `rt_dxr_lib.hlsl` | `RtRayGen` | 主光线、漫反射与 GGX 镜面弹射，共五张 raw 缓冲（SRV t0..t5 / UAV u0..u4） |
| `rt_integrate.hlsli` | `RtIntegrate` | DXR 主光线、直接光与一次间接弹射的积分实现 |
| `rt_resolve.hlsl` | `RtResolve` | diffuse/specular 重调制、自发光、曝光、显示变换与半径淡出（SRV t0..t11 / UAV u0） |
| `rt_exposure.hlsl` | `RtExposure` | 线性 HDR 表面测光与 1×1 曝光倍率更新（SRV t0..t6 / UAV u0） |
| `rt_debug.hlsl` | `RtDebug` | 单通道调试图：0..3 = x/y/z/w，4 = 法线档 |
| `rt_motion.hlsl` | `RtMotion` | 运动矢量：线性深度重建世界点后按上一帧矩阵重投影（SRV t0 / UAV u0，见 §19） |
| `rt_gbuffer_ps.hlsl` | `gbuf_main` | G-Buffer 多目标输出模板；不能单独编译，前缀由 spvc 输出拼上 |

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
   `include/`，最后写一份 `packs.txt`（内置包清单，B-DXR 排最前），那个根被挂成资源 srcDir。
   顶层没有 `.hlsl` 的目录不算包（`WorkGraph-Advanced` 只有公共头，GLSL 参考包也不拷）。
   光影包缺失时只 warn、不失败——独立目录缺一半时 JVM 半边必须还能构建。
2. **运行期读取**：`D3D12RtShaderSources` 按 `<包>/shaders/rt_dxr_lib.hlsl` 读源码，查找顺序是
   `<工作目录>/dxrmc/shaders/<相对路径>`（**改 HLSL 不必重编 Kotlin**）→
   `<工作目录>/dxrmc/shaderpacks/<相对路径>`（整个用户包）→ classpath 里的内置包。
   `D3D12CompileShaderDxc` 没有 include 处理器，`#include` 在这里展开：先找包含方同目录，再找 `include/`。
3. **编译**：`D3D12CompileShaderDxc(source, entry, target)`。入口名与 SRV/UAV 槽数留在 Kotlin
   （`D3D12RtTraceShader.ENTRY` / `SRV_COUNT` / `UAV_COUNT`），必须与 HLSL 绑定顺序一致，
   少给一个 SRV 会让后面的缓冲整体错位。

改 HLSL 的流程：改光影包里的文件 → `python _port\hlsl_check.py`（dxc 取自 Windows SDK，
`-I` 同时指向两处 include，参数与运行期一致；`--keep` 留产物）→ 进游戏看日志。

G-Buffer 的三小块 HLSL 也是包里的片段文件（要塞进生成式着色器，不能单独编译）：`rt_gbuffer_varying.hlsl`（MS/PS 共用的世界坐标 varying）、`rt_gbuffer_ms_patch.hlsl`（meshlet MS 里的调用点补丁）、`rt_gbuffer_albedo_sample.hlsl` / `_constant.hlsl` / `rt_gbuffer_tint.hlsl`（反照率三种写法）。`D3D12RtShaderSources.loadSnippet` 读它们时会丢掉纯注释行与空行、保留缩进，所以片段里可以写注释说明，不必担心注释被插进 struct 内部。

静态检查一次跑完：`python _port/rt_check_all.py`（从仓库根跑；加 `--gradle` 连 Kotlin/Java 编译一起）。它按固定顺序跑下面各条、逐条打 `PASS`/`FAIL`，任何一条失败就以非零码退出——脚本把工作目录钉在仓库根，因为 `docs_refcheck.py` 这类老脚本按进程 cwd 找目标，从 `Illusion\` 里跑会扫 0 个文件并报「三组 0」的假通过。

一次性跑完：`python _port/rt_check_all.py`（`--gradle` 附带编译、`--gpu` 附带引擎级探针）。十六条静态检查 + 一次纯 CPU 自检：`python _port/docs_eolcheck.py` 检查 `docs/` 下每份 `.md` 的行尾（必须全 LF）、无 BOM、文件尾有换行——写歪了不影响渲染，所以只有机器会报；`python _port/rt_stats_keys_check.py` 对拍 `rt_stats.json` 的列（Kotlin 写出的键 × 本节的样例）——少一列基线表就缺数据、多一列按文档写的脚本读不到，两边都不报错；`python _port/frame_constants_check.py` 对拍 `WgaFrame` 的字段偏移（`host/wga_constants.h` 权威）与 Kotlin 镜像，并禁止包里出现第二个 `register(b0)` 的 cbuffer；`python _port/hlsl_check.py` 编包里的入口（三个 compute 入口 + `lib_6_8` 库目标 + G-Buffer PS 模板 + MS 补丁片段，片段用最小 harness 验语法）；`python _port/rt_bindings_check.py` 对拍着色器里实际用到的 `register(t/u)` 槽位、Kotlin 的 `SRV_COUNT` / `UAV_COUNT`、派发时数组的长度，以及**数组元素与槽位的对应顺序**（SRV 与 UAV 都是：第 N 个实参必须落在 `register(tN)` / `register(uN)` 上）——多声明一个槽位就是一张没写过的描述符，少声明会让整表后移、全部绑错缓冲，顺序反了则是每个槽位读到别的缓冲，三种都不报错；`python _port/rt_shader_files_check.py` 校验 Kotlin 引用的每个包内路径都存在、包内每处 `#include` 都能在三个 include 目录里解析到、三个 compute 入口的 `[numthreads]` 与 Kotlin 的 `THREADS` 一致、着色器里 `RtDebugMode() == N` 的每个 `N` 都有对应的 `DEBUG_MODE_*` 常量（探针档与自检天空档只在这两处出现，写错一侧只会让自检莫名失败）；`python _port/rt_placeholders_check.py` 对拍 G-Buffer 模板里的 `%X%` 占位符与 Kotlin 的 `replace("%X%", …)`——少一处替换会留下字面量（编译不过或语义错），多一处替换说明占位符被改名了。模板里的占位符只写在代码行，别写进注释：替换会把代码插进注释里；`python _port/rt_pipeline_release_check.py` 保证 compute 管线只走 `ID3D12DestroyResource`（`dxrmc/rt/` 里禁用 `D3D12DestroyPipeline`，见 §9.8），并要求原生每条加速结构构建/压缩之后 8 行内都出现 `accelBarrier(`（见 §13.6），同时禁止 `dxrmc/rt/` 里出现 `as D3D12Gpu*` 裸转型（原生分配失败返回 null，Kotlin 的 `as` 会抛出**没有 message 的 NPE**，日志只剩一句 `rt self-test: null`；要写 `as?` 加一条带名字的日志）；`python _port/rt_sbt_check.py` 保证四件事：SBT 的标识符按**命中组导出名**取、命中组记录的先后与实例的贡献下标一致（见 §16.4）、ray payload 装得进原生的 `MaxPayloadSizeInBytes`、排版常量自洽（`TABLE_BYTES` == 记录数 × 记录大小，三个起点分别是 0 / 64 / 128，`DispatchRays` 实参顺序对齐）——四种写错方式都是编译不报错、运行时才失效；`python _port/docs_log_check.py` 把文档里引用的日志行与源码对拍——本文的验收清单整个建立在日志行上，改了日志文案却不同步文档，读者照着清单去日志里找会发现一行都没有；只查日志标签（`rt trace:` / `as:` / `shaderpack:` / `dred:`），组合行的完整文本另计；`python _port/pack_config_check.py` 把包声明与引擎配置对拍——`configs.json` 里的每个 `key` 都必须在 `D3D12PackConfig.kt` 有读、有写，且 `IllusionConfig.kt` 里还有同名 TOML 键；少任何一边，那一行都会出现在包设置里、写进 `settings.json`，却永远到不了引擎（不报错，只是不生效）；它还会把每个枚举设置里声明的取值逐个对到 `IllusionConfig.kt` 的枚举上——设置页是按名字查的，包里多写一个不存在的取值不会报错，只是那一档点了没用；`pwsh _port/rt_pure_selftest.ps1` 在游戏外跑自检的纯 CPU 那一半（三个场景的缓冲 + CPU 参考），改过缓冲布局或参考实现后用它确认这条链本身不抛异常、命中像素数合理（见 §9.7）；`python _port/docs_table_check.py` 检查 markdown 表格中间没有空白行——空白行会把表格截成两段，下面的行退化成普通段落，在编辑器里看不出来（本轮 `rt_plan.md` 就是这样坏了 587 处）；
`python _port/rt_check_all.py --gpu` 另外跑 `_port/abi_engine_probe.exe`：它加载 `Illusion\run\dxrmc\MCDR.dll`，用引擎自己的 ABI 建一颗三角形的 BLAS/TLAS，分别走 `DispatchRays` 与 compute + 内联 `RayQuery` 两档，要求两档都打印 `PASS`、`aux:` 命中数一致，且 `planes:` 里 `lit=` 与 `dark=` **都不为零**——场景是「底面 + 挡板」两块 quad，挡板挡住的那部分是影子射线真的被拦下来的证据；全亮等于没有遮挡，全暗说明常量或法线不对。这是唯一能自动发现「D3D12 调用合法但引擎接线错」的检查，默认不开（需要 GPU 与已构建的 DLL）。
`--gpu` 运行 `_port/rt_scene_compare.py`，使用 `_port/SelfTestPureProbe.java` 从自检代码导出的固定场景缓冲与 CPU 参考，分别验证各档的解析命中、遮挡和光照。固定解析场景的容差只覆盖该输入，不扩展成实际场景的整图匹配要求。
同一个 `--gpu` 总门还运行 `_port/rt_guide_failure_check.py`：它验证本帧 motion/pack/radiance 生成失败时不消费缓存、不提交 vendor，恢复帧重置历史；vendor 求值在该探针中截获，不能用作画质通过证据。
`_port/rt_tier_compare.py` 保留为单独运行的差异诊断工具，默认 GPU 总门不执行三档两两逐像素匹配。
`python _port/abi_call_check.py` 检查 FFM 调用点：`invokeExact` 的第一个实参不能是 `MCDRLayout.*`，每个 `MH_*` 都必须在 `bindAll` 中绑定。

常量缓冲就是 Work Graph 后端那份 `WgaFrame`（`wga_bindings.hlsli`，`cbuffer` 在 b0、416 B），
宿主侧镜像在 `D3D12RtFrameConstants.kt`，与 `WorkGraph-Advanced\host\wga_constants.h` 的
`FrameConstants` 逐字节一致——两个后端共用一套常量布局，回归矩阵只加一列。

B-DXR 占用的字段：`g_viewProj`（采样射线方向、运动矢量与 viewZ）、`g_cameraPos`（xyz 相机、w 帧序号）、
`g_screen`（这一次派发的像素尺寸，即降采样后的辐亮度尺寸）、`g_sunDir`（w 太阳角半径）、
`g_sunRadiance`（rgb 辐亮度、w 直射强度）、`g_misc`（x 反射最大粗糙度、
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
| `_port/rt_resolve_pad_check.py` | 调试图档位落在 `g_resolvePad.y`（Kotlin 常量数组第 3 项 `0`、第 4 项 `debugMode`） | 切到任何调试图都没有反应，画面停在合成结果上（`--selftest` 自带负例） |
| `_port/rt_upload_check.py` | `dxrmc/rt` 里出现「只给大小」的 `device.createBuffer` 重载（usage 0 + `…toLong()`）就 FAIL | 那个重载只分配、不上传，着色器读到未初始化显存；自检于是拿垃圾数据比对，日志里看不出原因 |
| `_port/rt_selftest_report_check.py` | `writeSelfTestReport` 写出的键集必须都在文档里写着；报告里注入的摘要（`contract` / `blas` / `sbt` / `frame`）与 `detail` 必须包 `jsonSafe()`（`--selftest` 自带负例自证） | 少了键 ⇒ 文件里有字段没人知道含义；少转义 ⇒ 一个引号就把这一行写成坏 JSON |
| `_port/rt_lock_check.py` | `D3D12RtTrace` 的两条管线、`D3D12RtDxr` 的状态对象、`D3D12RtBlas` 的加速结构、`D3D12RtFrame` 的释放与合光/调试入口，创建/释放/派发读句柄都在锁下 | 自检跑在客户端 tick、派发在渲染线程：不进锁会重复建造（泄漏）或在派发途中释放句柄（设备移除） |
| `_port/rt_material_layout_check.py` | `D3D12RtMaterialTable.ENTRY_BYTES`、`D3D12RtTrace.MATERIAL_BYTES`、`rt_shade.hlsli` 的 `RT_MATERIAL_BYTES` 必须是同一个数（`--selftest` 自带负例自证） | 只改一处 ⇒ 光追按错的步长读材质，albedo 与粗糙度整体错位，编译与日志都不会报 |
| `_port/rt_guide_layout_check.py` | 引导数据表（§19.7）与 `D3D12RtGuide.SLOTS` 的槽位名、格式、来源、顺序逐项一致，且 `specular` 占位值的说明还在（`--selftest` 自带负例自证） | 槽位换了格式没换、顺序换了 ⇒ 降噪与超分绑到别的缓冲，编译和日志都不报 |
| `_port/ngx_rr_probe.cpp`（由 `rt_check_all.py --gpu` 的 `ngx rr` 一步调用） | 游戏外问 NGX 本机是否支持 DLSS Ray Reconstruction（`SuperSamplingDenoising.Available`），探针缺失或本机不支持只打印 `SKIP` | 以为 RR 可用、接完才发现只能在别的机器上跑；或者反过来，明明可用却退到备选方案 |
| `_port/rt_nrd_adaptation_check.py` | 14 条源码契约，覆盖深度/命中距离/MV/历史/资源状态/显式偏移等；`--selftest` 包含 25 个内存负例 | 防止单边修改留下拖影、写入丢弃或禁用后仍派发；源码通过不等于 SDK 适配通过 |
| `_port/rt_jitter_contract_check.py` | 38 条共享采样接线规则及 38 个破坏负例；数值对照由 `rt_jitter_check.py` 执行 | 保持投影捕获顺序、consumer 闸门、当前/上一帧载荷、SDK 符号与 MV 去偏移一致 |
| `_port/log_line_check.py` | 日志正文字面量（占位符不算）硬上限 90 字符，且不得含日期、`because` / `so that` / `which means` 从句、`see …` 指路、文字级别前缀、工单号（`--selftest` 自带负例自证） | 日志是玩家唯一能读的东西；长了没人看，带日期与从句的句子在别的机器上还会立刻过时 |
### 11.1 必须有的文件

| 文件 | 作用 |
| --- | --- |
| `rt_dxr_lib.hlsl` | DXR 库（raygeneration / miss / closesthit） |
| `rt_resolve.hlsl` | 合光 compute（入口 `RtResolve`） |
| `rt_exposure.hlsl` | 曝光测光 compute（入口 `RtExposure`） |
| `rt_debug.hlsl` | 调试视图 compute（入口 `RtDebug`） |
| `rt_gbuffer_ps.hlsl` | G-Buffer PS 模板（`gbuf_main`，前缀由 spvc 输出拼上） |
| `rt_material_guide.hlsl` | 材质 guides compute（入口 `RtMaterialGuide`） |
| `rt_nrd_remodulate.hlsl` | NRD 后重调制与深度转换（入口 `RtNrdRemodulate`） |

七个缺一个，这个包在界面上是灰的、应用不了（判据在 `D3D12ShaderPacks.REQUIRED_ENTRIES`）。
其余片段与公共头（`rt_gbuffer_*.hlsl`、`include\*.hlsli`）按需读，缺了只会让对应阶段失败。

### 11.2 `pack.json`

可选，只用来填界面上的说明面板；键都可省，省了只显示包名与「这个包没有写说明」。B-DXR 自带一份：

```json
{
  "shaderDescription": "B-DXR ray-traced visibility for dxr_mc: one diffuse bounce per pixel ..."
}
```

认两组写法：`name` / `shaderName`、`author` / `shaderAuthor`、`version` / `shaderVersion`、
`shaderDescription` / `description`。`shaderDescription` 是 Iris 的键，照抄 Iris 的包能直接读；
不认识的键忽略。

### 11.3 内置包与用户包

- **内置**：`stageShaders` 拷进 JAR 的那些，清单在 `assets/dxr_mc/shaders/packs.txt`（一行一个包名）。
  读不到清单时只认 `B-DXR`。
- **用户**：`<工作目录>/dxrmc/shaderpacks/<包名>/shaders/…`，文件要求与内置包相同。
  同名时**内置优先**，用户包只能补内置没有的名字。
- 「打开文件夹」按钮打开的是用户包目录（`D3D12ShaderPacks.packsDirectory()`），不存在会自动建。

### 11.4 选中与生效

- 选择存在 `[shaderpack] active`；应用时**同时**写 `[raytracing] backend` 与 `[raytracing] mode`：两者都取包在
  `configs.json` 里声明的值（见 11.6），包没声明才回落 `DXR` / `FULL`；选「无光影」清空 active 并写
  `AUTO` + `OFF`。包与光追不是两个独立开关，只写包不改这两个键等于什么都没发生。
- 光追出图的判据是 `D3D12Settings.rtEnabled`：已选包、mode 非 OFF、设备支持硬件 DXR。

- 配置里写了不存在或缺入口的包时，`activeName()` 回落到 `B-DXR` 并只警告一次，光追照常跑。
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
- `[raytracing] backend` 与 `mode` 是包属性，声明缺失时使用 DXR / FULL；`reconcile()` 在首次 tick 对齐配置。

- 界面：标题、显示包名的副标题、按 `group` 分段的行、底部「完成」；布尔点击切换、枚举点击循环、
  区间项在行内轨道上点或拖取值。逐行规则见 `docs/misc/settingscreen/README.md` §6.11。
- B-DXR 包后端固定 DXR。其余选项按 §1 标明是否生效；没有加速结构开关。

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
- refit：`PERFORM_UPDATE`，`SourceAccelerationStructureData` 写**自己**的 GPU 地址（B-DXR 目前不用，见 §13.1）；拓扑（索引数、
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
- 镜像输入缓冲换地址不使已构建的 BLAS 失效。BLAS 保存构建结果；输入只需活到构建提交完成。
- 版本变化先把该条标记为未构建，只有 `D3D12BuildBlas` 成功后才登记新版本与拓扑。
  构建预算耗尽或失败时保留待处理状态；结果容量按新 prebuild 需求扩容，旧缓冲延迟回收。
- 任何一项变了 ⇒ 整条重建。计划里"顶点只动过就走 refit（`PERFORM_UPDATE`）"这一条当前**用不上**：
  池子对任何重新上传都会 bump `geometryVersion`，分不清"只是顶点移动"和"拓扑也变了"，所以只保留重建
  一条路，`REFIT_LIMIT` 与 `blas_refits` 因此恒为 0。真要 refit 得先让池子把两种变更分开记版本。
- scratch 只需一块：同一条命令列表里的多次 Build 顺序执行，不开第二条列表就不用给每条几何切分区。
- BLAS 按段常驻，所以要回收：每项记最后一次出现的帧号，`EVICT_FRAMES = 180` 帧没再出现就销毁
  （每 60 帧扫一次）。不回收的话走动起来条目只增不减，显存跟着世界大小涨。

几何在原版 `setSectionMesh` 返回时按整段发布。上传 HEAD 只暂存 CPU 字节，同一 mesh/layer 的重试不重复收集；
整段替换同时删除不再存在的层，空网格与 reset 移除该段，取消任务释放暂存数据。
每次提交的非空层取得新的几何版本，同大小的顶点、UV 或索引内容变化也会触发更新。

镜像按当前选择的记录复用 CPU 快照；远处记录变化而近场选择不变时复用既有 GPU 镜像与历史。
近场实际变化立即重建，不等待帧数节流；新选择为空时立即撤销旧镜像与 AS。
CPU 池的 `materialOffset` 单位是字节，复制时直接使用；GPU 记录转换为 quad 索引后由 shader 乘 2，不能重复转换。

### 13.2 压缩晚一帧

建或 refit 之后 `compactValid = false`；下一帧开头先做 `D3D12CopyAccelStruct(compact, result)`，
成功后才把 `compactValid` 置真。给 TLAS 的地址是 `address()`：压缩过用紧凑块，否则用结果块。

**当前不分配紧凑块**（`val compact = 0L`）：切到紧凑块之后一条射线都打不到三角形——
`COVERAGE` 正常（G-Buffer 有覆盖）、`HIT_DISTANCE` 与 `SHADOW` 全黑，关掉压缩立刻恢复。
拷贝命令本身没错（`CopyRaytracingAccelerationStructure` 的目标、源顺序与屏障都对），
所以是紧凑副本的内容或地址在 GPU 侧不可遍历；查清之前一律交原始 BLAS，代价是显存不省。

### 13.3 实例表与 TLAS

- 实例表是**上传堆**缓冲（`GpuBuffer.USAGE_MAP_WRITE`），JVM 每帧 `map` 填写。
  native `buildTlas` 在录制时复制有效实例到 encoder 持有的独立上传切片，GPU 读取该快照；
  禁止直接让 GPU 读取下一帧会覆写的 CPU 表。切片按所属列表的实际 fence 完成值复用。
  加速结构本体与 scratch 仍然必须留在默认堆。
- 每条实例 64 B：`Transform` 只写平移（`row0.w = originX`、`row1.w = originY`、`row2.w = originZ`），
  因为镜像顶点是**段内局部坐标**；`InstanceID = 记录下标`（着色器命中后拿它在 `gRecords` 里取同一份
  材质与顶点，见 §14.2）、`InstanceMask = 0x01`（静态不透明）、
  `InstanceContributionToHitGroupIndex` 按层写 0 / 1（`HITOPAQUE` / `HITCUTOUT`，见 §16.3）、
  末尾 8 字节写 BLAS 的 **GPU 虚拟地址**（`D3D12GetBufferAddress`，见 §12.1）。
- `Flags` 固定写 `0x01`（`D3D12_RAYTRACING_INSTANCE_FLAG_TRIANGLE_CULL_DISABLE`，`INSTANCE_FLAGS`）。
  镜像三角形的绕序不保证与原版面一致，开着背面剔除时射线会被整片剔掉：`COVERAGE` 正常、
  `HIT_DISTANCE` 与 `SHADOW` 全黑，自检照样全绿的——这个坑只能靠进世界看调试档发现。
  体素几何都是闭合块，关掉剔除只是多走几个背面，代价可忽略。
- 这一格不是对象句柄：句柄是 CPU 侧堆指针，写进去等于让每个实例指向不存在的显存。写错时建 TLAS
  不报错，只是所有光线都命中不到东西；原生侧的 `buildTlas` 只做搬运，不做句柄到地址的翻译。
- 掩码目前只有 `0x01`：镜像里只有静态几何与镂空层，动态实体与半透明还没进池。§3.2 里给主光线留的
  `0x02`（cutout）/ `0x04`（动态）等真要分类时才加——现在加只会让"这条光线看得见什么"与
  "池子里有什么"对不上。
- 实例池容量翻倍增长（起步 64 条），TLAS 结果块或 scratch 不够大时重建两块并**同时重建视图** ——
  视图指向旧的 TLAS 缓冲，换块后旧视图就是悬空地址。

RT 的 UPLOAD 堆 b0 常量同样在 `dispatchComputeEx` / `dispatchRays` 绑定时快照。
JVM 的三槽场景缓冲只作为 CPU 填写源；录制后的 dispatch 不再直接读取会被后续 map 覆写的槽位。
native 页面与 allocator/list 同槽，等待实际 fence 后才复用；这不替代完整帧所有权与几何版本发布。

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

日志一行 `rt geometry: contract records=… empty=0 indices=0 stride=0 version=0 unaligned=0 outOfRange=0`（每个进程首次镜像时执行）；
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

所有生产追踪都构建 BLAS/TLAS。未完成当前几何构建时跳过追踪和合光，禁止使用旧 TLAS 配新记录。

## 14. 硬件求交与命中解码

### 14.1 追踪入口

生产路径只使用 `rt_dxr_lib.hlsl` 的 `TraceRay`。内联 RayQuery 可用于独立几何探针，不提供整帧生产后端。

### 14.2 命中回读

`InstanceID` 对应镜像记录下标，`PrimitiveIndex` 对应局部三角形。`RtHitFromPrimitive` 读取同帧记录、
顶点、索引与逐 quad 材质，构造命中距离、法线与材质编号。

### 14.3 绑定与槽数

SRV 固定为 t0..t5：records、vertices、indices、materials、quads、TLAS。
UAV 固定为 u0..u4：direct、indirect、aux、specular、specularGrazing。b0 为 WgaFrame。

### 14.4 失败处理

状态对象、SBT、当前 TLAS 或帧常量不可用时跳过当前帧光追。失败不触发软遍历。

### 14.5 自检

场景检查直接对照 CPU 参考，见 §9.7；不编译 compute 追踪变体。

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

### 15.6 当前派发

`D3D12RtDxr` 持有状态对象与 SBT；逐帧绑定同帧镜像和 TLAS，再执行 `DispatchRays`。
DXR 与 WG 调度的选择独立于降噪选择，当前可用的生产调度为 DXR。

## 16. DXR 路径：着色器库、SBT 与自检对拍（S3 3-f）

### 16.1 共享头 `rt_shade.hlsli`

`rt_shade.hlsli` 提供命中解码、材质读取和光照计算；`rt_integrate.hlsli` 组织主光线、太阳阴影与间接弹射。
`rt_dxr_lib.hlsl` 提供硬件求交与 raygeneration / miss / closesthit 导出。

### 16.2 着色器库 `rt_dxr_lib.hlsl`

以 `lib_6_8` 编译（`_port/hlsl_check.py` 的 `LIBRARIES` 里登记，编译时不带 `-E`）。

| 导出 | 着色器类型 | 作用 |
| --- | --- | --- |
| `RtRayGen` | `raygeneration` | 主光线、太阳阴影、独立 diffuse/specular 弹射与二次命中阴影；共五张 UAV |
| `RtMiss` | `miss` | 把 `hit` 清 0、`t` 清 0、法线置 `(0,0,1)` |
| `RtClosestHit` | `closesthit` | 用 `InstanceID()` / `PrimitiveIndex()` / `RayTCurrent()` 走 `RtHitFromPrimitive` 写 payload |

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
| `HITOPAQUE` | `RtClosestHit` | 不透明地形 |
| `HITCUTOUT` | `RtClosestHit` | 镂空（叶子等） |
| `HITINVALID` | `RtInvalidHit` | 待重建 section 的失效体积 |

两个组暂时共用同一个 closest-hit：镜像几何没有 alpha，镂空判据要等材质表带上 alpha 才有意义。
实例表里 `InstanceContributionToHitGroupIndex` 按层写：镂空 / 半透明层写 1（`HITCUTOUT`），
其余写 0（`HITOPAQUE`）。两个组暂时共用同一个 closest-hit，所以这条现在只影响走哪条 SBT 记录；
等材质表带上 alpha，`HITCUTOUT` 才会真的做出镂空。

失效代理的贡献下标为 2，使用共享的 16×16×16 立方体 BLAS，实例原点为 section 原点。
其 `InstanceID` 从旧快照的记录数开始，不得用来索引 `gRecords`；`RtInvalidHit` 直接返回 `hit=2`。
主光线、太阳遮挡或 diffuse/specular 弹射命中代理时，清除该样本的 radiance、aux 和有效标记，
合光按 `direct.w=0` 直通当前原版像素。正常三角形命中必须显式初始化 `RtHit.invalid=false`。

### 16.4 SBT 排版（`D3D12RtDxr`）

- 记录步长 64 B（32 B 标识符 + 32 B 留给局部根参数），必须是 32 的倍数（原生会校验）。
- 布局：raygen `0`、miss `64`、命中组 `128`、`192` 与 `256`，整块 320 B。
- **标识符按命中组导出名取**（`HITOPAQUE` / `HITCUTOUT` / `HITINVALID`），不是 closest-hit 的函数名：
  `GetShaderIdentifier` 只认建状态对象时登记的导出名，写错不会报错，派发那一刻整张 SBT 失效。
- **记录顺序就是贡献下标**：命中组表第 0 条（偏移 128）必须对应 `D3D12RtBlas.HIT_GROUP_OPAQUE = 0`，
  第 1 条（偏移 192）对应 `HIT_GROUP_CUTOUT = 1`，第 2 条（偏移 256）对应 `HIT_GROUP_INVALID = 2`。
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

编译 `lib_6_8`，校验 raygen、miss 与三类 hit group 的标识符、SBT 对齐及绑定槽数。
GPU 探针直接通过 MCDR ABI 建 BLAS/TLAS，再用包内 DXR 库追踪受控场景。命中距离、材质与遮挡须正确。

### 16.6 自检：DXR 与独立 CPU 参考

四项场景与报告格式见 §9.7。场景坐标满足镜像布局约束；无降噪诊断使用 `RAW`，仍走硬件 DXR。

### 16.7 逐帧档位

| 配置 | 实际执行 | 不可用时 |
| --- | --- | --- |
| AUTO / DXR | DXR 状态对象 + SBT + DispatchRays | 关闭光追或跳过未就绪帧 |
| WG | 当前回退 DXR，日志报告 WG 尚不可用 | DXR 不可用时关闭光追 |
| mode=OFF 或未选包 | 原版渲染 | 不分配 RT 帧资源 |

`RAW`、RR、NRD_SR 是独立的降噪轨道，不改变上述追踪路由。
实际成功派发的统计为 `tier=dxr`；WG 回退不得报告为 WG。

### 16.8 实机验收清单

进入原存档，分别检查 RAW、RR、NRD_SR 与独立 SR。行走、快速转向后停下，确认阴影位置、纹理、
亮点收敛、残影和闪烁。破坏或放置方块后确认新几何与阴影同步；同时检查 RT 范围内外是否稳定。
视觉结果由用户确认，日志只能证明派发与资源状态。

### 16.9 覆盖边界

几何生命周期回归覆盖同大小修改、整段提交、空段/卸载、取消上传和世界切换。
GPU 回归覆盖分帧构建、拓扑扩容、镜像换址复用及真实射线距离随编辑更新。
GPU 回归还覆盖失效代理的实际命中、产品 DXR 有效标记与五张输出平面、未修改区域继续追踪、
失效集合稳定时复用历史，以及新快照发布后恢复真实几何。当前待补项为失效边界的世界内视觉复验与 native 帧所有权。

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
| `parallaxDepth` | `0.25` | `_n.A` 从 0 到 1 的最大深度（方块），范围 0–0.5；0 关闭视差与高度自遮挡，热切换重新烘焙 |

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
| +12 | `emissive` RGB | 无 `_s` 贴图时的启发式自发光；有贴图时使用其 alpha 强度 |
| +24 | `roughness` | 无贴图时的启发式值 |
| +28 | `flags` | 启发式（透明 / 双面 / 自发光位） |
| +32 | `metalness` | 无贴图时的金属度 |
| +36 | 贴图元数据偏移 | 0 表示无图；非零为同一缓冲内的绝对字节偏移 |

记录后接不可变的贴图数据。每个精灵的 64 B 头含 atlas UV 起点与逆尺寸（+0），
`_s` / `_n` / 基础色各自的相对数据偏移、宽、高（+16/+28/+40），
视差深度（+52）与该图最大凹陷比例（+56）；像素按 ARGB uint 保存。
基础色来自当前 atlas 精灵首帧，和屏幕上启用的资源包一致。
不同 tint 的材质记录共享同一份精灵贴图。上传时复制记录和贴图，并将 +36 转成绝对偏移。
不得返回可被下一次烘焙覆盖的 CPU 缓冲视图。

`rt_integrate.hlsli` 使用命中采样的着色法线、粗糙度、F0、金属度与自发光，
计算太阳直射、漫反射和 GGX 镜面弹射；无贴图的命中保留启发式材质。

主命中的 diffuse 与 specular 分开采样。CPU 参考 `D3D12RtTrace.shadeBounce`
对应无贴图材质；贴图采样与视差由独立 GPU 探针验证。

主命中的材质因子由 `RtMaterialGuide` 在追踪后补写到 G-Buffer，见 §19.7。
它消费 DXR 同一次采样生成的 normal、roughness、F0、metalness 与 emissive，
以同次命中的基础色完成有色金属 F0 与自发光重调制；无贴图时保留光栅 albedo 与启发式值。
材质 guide 先由原始像素 albedo 计算 F0，再将 diffuse albedo 乘 `1-metalness`。

主表面镜面使用 GGX VNDF，`alpha=roughness²`，roughness 下限与 guide 同为 `1.5/512`。
diffuse/specular 的采样 seed 使用实际帧号 `RtFrameIndex()`，sampleIndex 分别为 0/1。
每帧更新弹射方向，CPU/GPU 自检按相机常量中的同一帧号复现。对反射入射光 `incoming`，
令 `Fc=(1-dot(view,halfDirection))^5`、`visibility=WgaGgxG1(dot(normal,light),alpha)`：

- `specular.rgb = incoming * visibility * (1-Fc)`，w 为独立的首次反射命中距离；
- `specularGrazing.rgb = incoming * visibility * Fc`，w 为 0；
- 重调制使用 `specular.rgb * pixelF0 + specularGrazing.rgb`，保留 F0 的像素纹理；
- 反射打空的距离为 `1e9`，采样朝内为 0；天空主像素的两个镜面缓冲均为零。

反射环境已包含太阳盘，主镜面不再额外叠加太阳 NEE。二次命中的光照仍使用本节的终止近似。

### 17.3 逐命中采样

`RtSampleSurface` 使用 DXR 返回的三角形重心坐标插值顶点 UV，将 atlas UV 转为精灵局部坐标，
以最近邻采样 `_s` / `_n`。主命中与二次命中调用同一函数，不把整张材质图平均成一个数。

`_n.A` 为高度，255 表示原表面，较小值向内凹。先沿入射方向在高度图中步进并细化交点，
再用同一个局部 UV 采样基础色、法线与 `_s`，避免只有光照偏移而纹理仍贴在平面上。
高度使用双线性采样；基础色按 2.2 次幂线性化，再乘重心插值的顶点 RGB（包含群系染色/AO）。
高度交点使用 16–64 层与 6 次细化，太阳自遮挡使用 8–48 层；均不访问相邻精灵。
全白高度图不做视差。存在透明像素的精灵关闭高度视差；掠射角余弦 ≤0.05 时保留原表面。

主命中的虚拟径向深度写入既有 linear-depth，MV、NRD viewZ 与 RR 读取该深度。
宏观阴影和弹射射线从虚拟表面沿出射方向返回三角形平面后再施加 bias，避免撞到自身宏观面。
该路径提供表面视差与太阳自遮挡，不改变 BLAS、方块轮廓或碰撞；间接微表面多次遮挡尚未实现。

- `_s.R` 为光滑度：感知 roughness 使用 `1-R/255`，GGX 的 alpha 再取 roughness²。
- `_s.G` 的 0–229 为介质 F0，230–237 为预定义金属折射/消光系数，238–255 使用 albedo 着色的金属 F0。
  不得把 G/255 当作连续金属度。
- `_s.A` 的 0–254 为归一化自发光强度，255 为不发光。alpha 是数据，0 不能当透明像素丢掉。
- `_n.RG` 解码切线空间 XY，Z 由单位长度恢复；按三角形位置/UV 构造带手性的切线基，
  将贴图向上的 Y 转到 atlas 向下的 V。扰动法线参与直射与弹射，并写入 NRD/RR 引导。
  射线起点偏移仍沿几何法线，映射法线背向观察方向时回退几何法线。

当前限制：贴图动画取首帧；`_n.B` 的 AO 和 `_s.B` 的孔隙/SSS 尚未接入。
有材质图的二次命中同样采样基础色，缺图材质继续使用精灵均值。

### 17.4 验收

- 不配 `packPath`、不启用材质包：日志那行是 `from resources` 且 `spec=0`，画面与接入前一致；
- 配 `packPath = Illusion-HPT-Shaders/SPBR-22_6`：`spec` 应是几千（该包 10 659 张图里 3 757 张 `_s`），
  石头/木头/金属的粗糙度与金属度各不相同；
- `rt_pbr_loader_check.py` 调用产品 PNG 读取与打包，验证 ARGB、alpha=0、首帧裁切、偏移与不可变快照。
- `rt_parallax_probe.py` 由 GPU 信号探针调用，用解析高度斜面检查正/负视角位移、基础色/粗糙度/自发光共享 UV、
  顶点染色、深度，以及遮挡脊的太阳可见性；guide 探针检查虚拟深度、基础色与金属重调制。
- `rt_sr_signal_check.py --pbr-materials <产品导出的缓冲>` 经实际 GPU 检查空间变化、F0/金属编码、
  自发光、法线参与直射以及共享 guide；同时验证没有贴图时的退化。画质由用户在世界内确认。

## 18. 降噪与超分的单一真值表（S4 4-a）

`D3D12RtPipelinePlan.configured(displayWidth, displayHeight)` 生成一帧的轨道、尺寸与派发闸门。
G-Buffer 重放、viewport、trace、motion、NRD pack 与 radiance 使用同一个计划：

| 轨道 | trace 倍率 | 实际 G-Buffer / 降噪尺寸 | 实际降噪 | 放大 |
| --- | --- | --- | --- | --- |
| `OFF` | 按 100% | 显示尺寸 / 不派发降噪 | 无 | 原始 trace 合光插值 |
| `RR` | `renderScale` | render 尺寸 | 尝试 DLSS-D，不可用则 NRD | RR 输出显示尺寸；NRD 回退按所选 SR 后端重建或合光插值 |
| `NRD_SR` | `renderScale` | render 尺寸 | REBLUR_DIFFUSE_SPECULAR | 低于 100% 时按所选 SR 后端重建；NONE 或不可用时合光插值 |
| `RAW` | `renderScale` | render 尺寸 / 不派发降噪 | 无 | 原始 trace 合光插值 |

硬件追踪与 G-Buffer 使用同一 render 尺寸，见 §26.1。
`RAW` 与 `OFF` 不生成降噪输入，也不派发降噪。降噪 OFF 只关闭降噪，光追总开关由包选择、mode 与硬件能力决定。
光追总开关关闭时，计划将 G-Buffer 与 trace 尺寸置零，重放入口立即返回。

配置位于 `config/dxr_mc-client.toml` 的 `[raytracing]`：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `denoise` | `OFF` | 选择降噪轨道 |
| `renderScale` | `100` | 25–100，参与计划尺寸与 trace 目标高度计算，见 §26 |

独立 SR 后端读取 `SuperResolution.backend()`，与 `[sr] backend` 初值及视频选项共享选择。
计划变化时记录 `rt: denoise=<track> render=<w>x<h> gbuffer=<w>x<h> sr=<backend>`。
此处的 render/G-Buffer 是计划值，不能证明资源已经按该尺寸分配。
实际 trace 网格查看 `rt frame: dispatch <w>x<h> of <displayW>x<displayH>`；实际资源以分配点为准。

## 19. 运动矢量与引导数据（S4 4-b）

运动矢量是降噪与超分的重投影输入：每个像素告诉后级「这个像素在世界里的那个点，上一帧落在屏幕的哪里」。
它在光追派发之后单独算一次，写进 `rt-motion`（RGBA16F，与 G-Buffer 同尺寸），由 `RR` / `NRD_SR` 消费。

### 19.1 为什么另开一次计算

加第六张 G-Buffer 附件走不通：那个 pass 的 PSO 是 `createGBufferVariant` 复用主 pass 的 RTV 数建的 mesh 变体，
逐 pass 的 `recordGBufferPass` 只给 5 个颜色槽建格式表，`SV_Target5` 没有对应格式 ⇒ 只能 pass 之后单独 `Dispatch`。
`D3D12RtMotion.render()` 就是这一步，由 `D3D12RtFrame.dispatchImpl` 在 `writeScene(...)` 成功之后调用。

### 19.2 算法与约定

在 `D3D12CommandEncoderBackend.writeToBuffer` 捕获关卡投影 UBO 的实际上传矩阵。
该矩阵已包含原版 `bobView`、`bobHurt` 及屏幕效果；禁止只从 `Camera` 的位置、朝向和 FOV 重建。
捕获结果按 `frameTimeNs` 校验，只供同帧 G-Buffer 与追踪消费；缺失时跳过派发。

`D3D12RtCamera.resolve` 将最终投影中的相机效果移入视图：

```
E = inverse(P_base) * P_final
V = E * R_camera * T(-logicalPosition)
VP = P_base * V = P_final * R_camera * T(-logicalPosition)
rayOrigin = inverse(V) * (0, 0, 0, 1)
```

追踪与运动矢量使用这份 VP；NRD 与 Streamline 使用 `P_base` 和包含摇晃的 V。
`g_cameraPos.xyz` 写实际射线原点。G-Buffer 重放在 b8 的字节偏移 32 传入
`gRtCameraOffset = rayOrigin - logicalPosition`，生成的 `gbufWorldPos` 减去该偏移，
保证径向深度与射线具有同一原点。b8 的前 32 字节保持 meshlet/VRS 布局，G-Buffer 的 VRS 关闭。

RT 从 `D3D12Temporal.frameJitter(frameNanos)` 取得同帧实际注入的唯一 Halton 偏移。
先捕获无 jitter 最终投影并解析 bob。RT 开启时原版世界 UBO 保持稳定；只有 G-Buffer 重放使用采样投影，
其全部批次成功后才提交偏移给 PT、guide 与 SDK。未重放、过期帧或不完整批次不能沿用旧光追输出。
clip 平移左乘最终投影，逐列执行 `row0 += clipX * row3`、`row1 += clipY * row3`。
`g_viewProj` / `g_prevViewProj` 与 `g_invViewProj` 含对应采样偏移，SDK 矩阵与持久历史矩阵保持无 jitter。
透视射线方向统一调用 `rt_camera.hlsli` 的 `RtRayDirection`：令 `horizontal = row0.xyz - ndc.x * row3.xyz`、
`vertical = row1.xyz - ndc.y * row3.xyz`，取 `cross(vertical, horizontal)` 并选择 clip.w 为正的一侧后归一化。
只使用投影的线性部分；禁止将近裁剪点反投影为绝对世界坐标再减相机位置，避免浮点消减误差使射线随 Halton 周期偏移。
RT 开启且当前 Plan 含 RR 或 NRD 时启用偏移消费闸门；它不随某一帧的 SDK 求值结果开关，
因此失败帧不会让世界投影在稳定与 jittered 两套坐标之间来回切换。首帧、RT 关闭、降噪关闭或窗口无效时为零。
偏移仍只进入独立 G-Buffer 重放与匹配的 RT 输入，原版世界 UBO 保持稳定。
逐帧 Halton 变化不触发 camera cut。行走摇晃仍按原版最终投影逐帧保留。

合光按输出像素直接读取稳定的原版 source，禁止再用 jitter 平移它。原始 RT/G-Buffer 输入仍按
`outputUv + contentJitter / radianceSize` 读取；RR/SR 已重建的颜色按 `outputUv` 读取。
无覆盖、范围外与半径淡出使用同一稳定 source，追踪未就绪时整帧直通也无需补偿原版投影。

`Illusion-HPT-Shaders\B-DXR\shaders\rt_motion.hlsl` 的 `RtMotion`：

1. 读 G-Buffer 的 `LINEAR_DEPTH`（`t0`）。存的是 `sqrt(dist / DEPTH_SCALE)`，所以 `distance = stored * stored * DEPTH_SCALE`。
   `DEPTH_SCALE = 64.0`，按浮点位放在 b1 根常量的第一个 32 位槽（b0 的字段已排满，布局见包里 `wga_bindings.hlsli` 的 `cbuffer WgaFrame`）。
2. 与追踪共用 `rt_camera.hlsli`：`uv = (id + 0.5) / size`、`ndc = (2u - 1, 1 - 2v)`，
   `dir = RtRayDirection(ndc)`，世界点 = 相机 + 方向 × 距离。禁止独立实现另一套射线反投影。
3. 世界点分别乘 `g_viewProj` 与 `g_prevViewProj`，取 `xy / w` 之差，乘 `0.5` 换成 NDC 跨度的半宽，再乘像素尺寸。
4. 屏幕位移为 `(dx,-dy)`，再加当前减上一帧的屏幕内容偏移以移除 jitter。b1 第二/三槽传入该差值；输出仍是像素单位、+y 朝下的真实运动。
5. 深度为 0 的像素（天空、G-Buffer 没写过的实体）位移记 0：它们自己就是静止的，写别的东西会被后级当成运动。

共享采样离线检查运行 `python _port/rt_jitter_check.py`，GPU 总门也包含此项。
JVM 直接调用产品生成器，导出 192 帧 bob/FOV/正反 Z/两种尺寸的真实矩阵与偏移载荷。
实际产品主射线、MV 与 viewZ HLSL 对拍 1,728 个解析点，CPU 参考计入 RGBA16F 径向深度量化。
预算分别为 MV 0.005 pixel、方向 0.0002、viewZ 0.002；遗漏 MV 去偏移的负例须超过 0.1 pixel。
七个独立进程各运行 32 帧，匹配 SDK 帧号、输入与运行库；NRD 检查有限输出与资源归还，
RR 使用 320×200 原生尺寸，RT DLSS SR 从 320×200 放大到 640×400。
RR/SR 的正确、反号及零偏移组只改变相机偏移载荷，末八帧均方误差与负例退化倍数记录为图像诊断，
不决定工程检查的退出码；共 120 次非法偏移须在资源接管前拒绝。
报告保存逐帧读回哈希、当前源码/SDK 运行库/输入哈希和末帧完整快照，并从快照重算误差。
该检查不覆盖世界内投影上传与 G-Buffer 重放、FSR/XeSS 非零偏移画质、主观残影或长时间运行。

`python _port/rt_jitter_check.py --precision-only` 检查远离原点时的射线精度：
1715×893、两组世界坐标、正反深度和 16 相 Halton，共 64 帧、576 条 GPU 射线。
用独立双精度相机相对矩阵将 GPU 方向投回屏幕，误差必须小于 0.02 渲染像素；这是投影几何检查，
不比较不同降噪器的图像，也不替代世界内阴影稳定性的目视验收。

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
| `_port/hlsl_check.py` | `rt_motion.hlsl` 按 `cs_6_8` + 入口 `RtMotion` 编译一次 |
| `_port/rt_bindings_check.py` | `motionSrvs` / `motionUavs` 的槽位数与顺序对 `register(t0)` / `register(u0)` |
| `_port/rt_shader_files_check.py` | Kotlin 里出现的 `rt_motion.hlsl` 必须在包内存在 |
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
`linear-depth` 保存径向距离编码；NRD 的正 viewZ 由 §21 的 pack pass 单独转换。

G-Buffer 像素着色器先写电介质默认值：F0 为 0.04、roughness 为 0.8、emissive 为零。
追踪的三条路径均将主命中材质 ID 按原始 uint 写入 `aux.w`；不要按浮点数值读取该槽。
`D3D12RtMaterialGuide.render` 随后派发 `RtMaterialGuide`，读取 `aux`、40 B 材质表、
像素 albedo 与径向深度，更新五张现有附件，不新增 RTV：

- `specular.rgb` 使用采样 F0；有色金属与启发式材质按像素 albedo 重调制，alpha 保存 metalness；
  无贴图时的 F0 为 `lerp(0.04, albedo, metalness)`；
- `normal-roughness.xyz` 使用 DXR 同次采样的世界空间法线，w 写入 roughness，下限为 `1.5 / 512`；
- `emissive.rgb` 使用采样强度乘像素 albedo，或启发式 RGB，并限制到有限的非负 FP16 范围。
- `albedo.rgb` 在 F0 计算后乘 `1-metalness`，纯金属的主漫反射归零。
- `linear-depth` 在高度视差生效时写入虚拟交点径向距离，供后续 MV、NRD 与 RR 使用。

绑定为两个 SRV（aux、materials）与五个 UAV（specular、normal-roughness、emissive、albedo、linear-depth）。
albedo 和 linear-depth 通过 UAV 读取并更新，禁止同时作为同一派发的 SRV。

生产 `aux` 为五个平面，每平面每像素 16 B：原距离/可见性/材质 ID 平面后，
追加 normal/roughness、F0/metalness、自发光数据/模式位、采样基础色/虚拟交点距离增量。五张 raw UAV 的槽位不变。
guide 从这些平面消费追踪时采样的属性，避免降噪使用与着色不同的法线或材质。
诊断的单平面 `aux` 仍读取 40 B 常量材质记录。

只接受有效主命中、有效材质索引、覆盖 alpha 与匹配的径向距离。
距离容差为 `max(0.02, surfaceDistance * 0.002)`；缺失记录、非有限值或深度不符时保留默认值。
主命中按 trace 尺寸映射，深度检查防止把相邻表面的材质写到当前像素。
材质补写必须在 NRD pack 之前执行，使 NRD 与 RR 读取同一帧的 roughness。

当前实际创建 `REBLUR_DIFFUSE_SPECULAR`，使用独立 diffuse/specular 输入、输出与各自命中距离。
镜面 RGB 在纹理化时除以 `max(F0,0.02)`，降噪后乘回相同因子；该正下限使解调可逆。

准备齐了才打一行（一次进程）：

```
rt guide: 5 slot(s), material factors ready
```

缺项时是 `rt guide: incomplete, motion or gbuffer missing` —— 这行说明降噪拿不到完整输入，不要按「能跑就行」放过。

静态门 `_port/rt_guide_layout_check.py` 把这张表与 `SLOTS` 逐项对拍（槽位名、格式、来源都要一致，
顺序也不能换），并检查主命中材质与双 lobe 限制的说明。
`_port/rt_sr_signal_check.py` 执行实际 guide HLSL，覆盖 RGBA8 F0、几何法线保留、
粗糙度下限、自发光范围、天空/漏命中、错位深度、非有限值与截短缓冲。

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

`D3D12RtNrd.ensure()` 按设备和 render 尺寸缓存初始化结果，同一配置失败后不逐帧重复创建上下文。
切换降噪档位、超分方案或尺寸，以及关闭后重新开启光追时允许重试。
`D3D12RtFrame.updatePlan()` 与 `release()` 调用 `D3D12RtNrd.reset()`，只清除失败初始化的尺寸缓存；
已就绪的同设备/同尺寸上下文继续复用。设备或尺寸变化由 native 按在途生命周期重建上下文。
初始化重试与历史复位分别处理；RR、NRD、SR 的历史仍只在实际求值成功后消费。

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

工作区的 `python _port/rt_nrd_recovery_check.py` 调用实际 JVM 的 `ensure()`、Plan 更新和 `release()`，
在 FFM 初始化句柄处注入失败与不可用返回，再恢复真实 native 初始化。
覆盖同配置失败缓存、关闭重开、OFF/RAW/RR 切回、超分方案变化、尺寸变化与非法请求；
12 次初始化尝试中有 6 次真实 native 成功。已就绪的上下文在切档和释放帧资源时不会重复初始化。
该检查进入 GPU 总门，只覆盖初始化恢复，不代替 SDK 帧派发、世界内切换或画质验收。

## 21. NRD 输入编码（S4 4-c）

NRD 只看两种「引导」排布：法线-粗糙度与 viewZ。两者都不是我们 G-Buffer 里的原样数据，必须在派发之后
重排一次，否则降噪拿到的是解错的法线 —— 画面只会变糊，不报任何错。

### 21.1 为什么必须自己转

编码在 NRD 编译期定死，运行时只能问出来不能改：`LibraryDesc.normalEncoding = 2`
（`R10_G10_B10_A2_UNORM`）、`roughnessEncoding = 1`（`LINEAR`）。这两个值由 `D3D12RtNrd` 的清单打出，
见 §20.4 的 `nrd: normal encoding=2 roughness=1`。软件包的 `include` 里没有 `NRD.hlsli`，打包公式
照 NVIDIA-RTX/NRD 的 `_NRD_EncodeNormalRoughness101010` 逐位实现，放在
`Illusion-HPT-Shaders\B-DXR\shaders\rt_nrd_pack.hlsl`。

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
radial = stored * stored * DEPTH_SCALE
dir = RtRayDirection(ndc)
viewZ = max(dot(g_viewProj[3].xyz, dir) * radial, 0)
```

`dir` 与主射线共用 `rt_camera.hlsli`。透视的 `clip.w = -view.z`，相机原点的 clip.w 为零，
因此直接计算相对方向的投影距离，避免先构造绝对世界点再投影造成消减误差；保留最终相机效果中的旋转与缩放。
无效像素写 `viewZ = 2 × DENOISING_RANGE`，法线取 `(0, 0, 1)`、粗糙度取 1，排除出降噪范围。

### 21.5 日志与验收

建目标时一行：`rt: nrd pack <w>x<h>`。失败只打一次 `rt nrd pack: …`（这条链断了画面只是少两份
引导数据，不该每帧刷屏）。本次改动不改变可见像素：`rt_stats.json` 的 `cpu` 会多出一次小派发的耗时，
`dispatch` 计数 +1。

### 21.6 门

| 门 | 覆盖什么 |
| --- | --- |
| `_port/hlsl_check.py` | `rt_nrd_pack.hlsl` 的 `cs_6_8` 编译 |
| `_port/rt_bindings_check.py` | 两个 SRV（`gNormalRoughness` / `gLinearDepth`）与两个 UAV（`gNrdNormalRoughness` / `gNrdViewZ`）的槽位顺序 |
| `_port/rt_shader_files_check.py` | Kotlin 里写的 `rt_nrd_pack.hlsl` 在包里真实存在 |

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

## 23. NRD 逐帧派发（S4 4-c）

### 23.1 一条派发链要做什么

`nrd::GetComputeDispatches` 返回的是「这一次降噪要跑哪些 pass、每个 pass 绑哪些资源」。集成方负责建资源、建视图、绑根签名与派发，NRD 自身不分配任何资源。

固定顺序（顺序错了不报错，只会得到空结果）：

1. `nrd::SetCommonSettings`，把本帧的矩阵、尺寸、运动矢量缩放写进实例。
2. `nrd::GetComputeDispatches`，取回 `DispatchDesc` 数组。**反过来的话 `gridWidth/gridHeight` 全是 0**，派发等于空转。第三个参数是降噪器的 identifier 表，本工程的实例只有一个降噪器，传 `const nrd::Identifier ids[1] = {0}` 与条数 1；**不能传 `nullptr, 0`**（见 §23.2 第 8 条）。
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

8. **identifier 表不能留空**：`GetComputeDispatches` 在 `identifiersNum == 0` 时返回 `SUCCESS` 且 `dispatchDescs = nullptr`、`dispatchDescsNum = 0` —— 看着成功，实际一个 pass 都没跑。必须传实例自己的降噪器 identifier（本工程只有 `0`），并把「返回码不是 `SUCCESS`」「指针为空」「条数为 0」三种都当失败写进 `outText`；症状是每帧 `nrd: denoise unavailable (-1), no context`，而上下文明明已经就绪。

9. **每个 `Dispatch` 之后要一道 UAV 屏障**：`DispatchDesc` 里没有屏障信息，同一帧的 pass 之间纹理状态不变，D3D12 不会替 UAV 写入排序；NRD 自己的集成层在每次派发前插一道 storage 屏障，我们照做（`D3D12_RESOURCE_BARRIER_TYPE_UAV`，全资源范围）。少了它，后一个 pass 可能读到前一个 pass 写之前的像素。

10. **SRV/UAV 的格式取资源自己的**：视图格式与资源不符时调试层拒建视图、描述符留空，pass 读到垃圾且不报错。池纹理与输入纹理都按 `GetDesc().Format` 建，别按 `ResourceDesc.type` 猜。

11. **`SetCommonSettings` 的返回值不能丢**：参数非法时它返回 `INVALID_ARGUMENT`，但设置照旧生效 —— 只看内容不看返回值根本发现不了。`disocclusionThresholdAlternate` 与三个材质 ID（`strandMaterialID` / `cameraAttachedReflectionMaterialID` / `historyFixAlternatePixelStrideMaterialID`）留 0 就是非法值，本工程统一写 `999`；Reblur 拿 `NR.a` 与它们比较，0 会命中每一个像素。

### 23.3 当前状态

`REBLUR_DIFFUSE_SPECULAR` 已接入帧内，成功派发后将双通道结果交给合光。
首次成功日志中的 pass 数与 missing 数只证明派发与资源映射成功；画面质量仍须在世界内验收。

游戏外回归覆盖两类问题：

- Compute 描述符探针：3 个 encoder，各自 3 张在途列表，每张使用 600 个描述符，10,800 条记录全部匹配，调试层错误为 0。
- NRD 槽位与彩色信号探针：2,048 次派发、256 次忙槽拒绝。diffuse/specular 各 2,048/2,048 像素解码后分别接近线性 RGB `{0.9,0.4,0.3}` / `{0.2,0.7,1.1}`，调试层错误为 0。两路颜色不同且含负 chroma，能够发现通道交换或复制。
- 同一 NRD 探针交替开关 SDK `enableValidation`，1,024 次启用派发均包含独立 validation pass。
  常态派发数比相邻关闭帧多 1；诊断图 2,048/2,048 像素有限且 alpha 合法，存在非透明输出。
  SDK validation 是诊断图，不是错误计数；该检查不代替画质验收。

已知限制：`NRD_SR` 的独立 SR 依赖所选后端的运行库，不可用时降噪结果由合光插值到显示尺寸；
Streamline DLSS-D 不可用时回退 NRD。长时间运行、转动相机、窗口缩放以及噪点/鬼影的最终验收尚未闭合。

### 23.4 引擎侧的接口（S4 4-c）

- ABI 23 的实际 RT 帧调用 `mcdr_dx12NrdDenoiseTemporal`，接收 encoder、七张纹理、四个矩阵、独立偏移、帧号与 `resetHistory`。纹理顺序固定为 `motion / normal-roughness / viewZ / diffuse-in / diffuse-out / specular-in / specular-out`。
- 四个矩阵按列主序依次为当前投影、上一帧投影、当前世界到视图、上一帧世界到视图，共 256 字节。前两块不得含世界到视图变换。上一帧投影由上一帧视投影乘上一帧视图的逆矩阵恢复；读完所有上一帧输入后才更新输出数组，允许输入/输出共用历史数组。
- `nrdDenoise` 将零基索引 4/6 的输出纹理转为 `UNORDERED_ACCESS`，其余输入转为 `NON_PIXEL_SHADER_RESOURCE`，派发后回到 `COMMON`。永久池与瞬态池由 NRD 后端逐 pass 跟踪状态；同一 UAV 连续写入也要排序。
- NRD 的 8 个描述符/常量槽按实际 encoder 的 completion fence/value 回收，不按帧号取模覆盖。全部槽位仍忙时拒绝本次派发；重建或销毁上下文后，旧上下文保留到所有提交完成。
- 通用 compute / DXR 绑定与 VRS 的描述符按 encoder 的 command-list slot 分页分配，每页 256 个。一次录制只递增，不回绕；列表槽复用前等待该槽提交的 fence，之后才从第一页重新分配。
- 原版光栅与 G-Buffer 重放的 UPLOAD CBV 在 native `pushDescriptors` 录制时复制到列表槽持有的上传页；mesh b8 独立入口同样复制，包含重放相机偏移。CPU 后续覆写不会改变已录制的常量。页游标只在所属槽 fence 完成后复位；DEFAULT 堆常量仍按 GPU 命令顺序读取，上传 SRV 与 SBT 不在该快照范围内。CBV 偏移必须 256 B 对齐，长度不得超过剩余范围或 64 KiB；b8 复制剩余至多 256 B。
- JVM 瞬时映射常量和 staging 共用 512 KiB 起的上传块，按请求对齐且不回绕覆写。分配前结算异步提交并打开列表，保存当前 encoder fence 加一作为该切片的目标值；块复用和延迟队列释放均检查真实完成 fence。不能用 `rotate()` 次数代替 GPU 完成，不能在 native 录制快照之前覆盖已返回的源切片。具体规则见 [瞬时内存](../misc/backend-and-device.md#42-分块与回收)。
- BLAS 的 vertex/index 与 DEFAULT 堆 TLAS instance 输入在构建前转为 `NON_PIXEL_SHADER_RESOURCE`，scratch 转为 `UNORDERED_ACCESS`；UPLOAD instance 输入保持 `GENERIC_READ`，AS 结果保持 AS 状态。
- 双 lobe 的池、PSO 与描述符需求按 NRD `InstanceDesc` 动态生成；资源映射同时包含 `IN_SPEC_RADIANCE_HITDIST` 与 `OUT_SPEC_RADIANCE_HITDIST`。
- native 内部帧可提供 `validationOutput` 以启用 SDK validation 并映射 `OUT_VALIDATION`；公开七纹理入口保持该指针为空。独立探针负责诊断纹理的 UAV 状态与在途生命周期，默认游戏路径不额外派发 validation。

ABI 另提供 `mcdr_dx12NrdDenoiseTemporal`，在同一组输入上增加独立的四 float 偏移数组：
当前 x/y、上一帧 x/y，按 NRD 的渲染像素采样偏移约定直接传入 SDK。
矩阵保持无 jitter；不能把偏移追加到原入口的 64-float 数组之后。
四个分量必须有限且在 `[-0.5,0.5]`；空指针或非法值在接管纹理前返回 0。
FFM 对应 `MCDR_ABI.D3D12NrdDenoiseTemporal`，检查 256 B 矩阵与 16 B 偏移。
实际 RT 帧按共享 Halton `(Jx,Jy)` 传入 NRD `(-Jx,+Jy)`，上一帧同式。
原 `mcdr_dx12NrdDenoise` 保持零偏移兼容入口。

离线执行 `python _port/rt_nrd_temporal_check.py --jitter-interface`：
记录八帧原入口与八帧显式零偏移入口的两路完整读回差异，仅作图像诊断；
八组非零/边界偏移须成功求值且输出有限；29 次非法输入须在接管前拒绝，保持资源状态及累计计数。
该检查只验证接口与收尾，不验证非零采样投影、偏移符号或画质。
GPU 总门使用 `--jitter-interface`，通过标记包含 `engineering checks passed` 与 `jitter_interface=passed`。
`--highlight` 保留为单独运行的可选图像诊断。

### 23.5 日志

首次派发成功以 INFO 记录 pass 数与缺失资源数。pass 形状、输入尺寸等细节使用 DEBUG。
失败时记录精简诊断，帧内清空降噪输出并退回原始辐亮度；下一次成功调用重置历史。
日志和有限时长探针通过均不代替世界内目视验收。

### 23.6 辐亮度纹理化与帧内调用（S4 4-c）

`RtNrdRadiance` 将追踪网格的缓冲转换为降噪输入纹理：

- `t0 gDirect / t1 gIndirect / t2 gAux` 每条记录均为 16 字节；`t3 gViewZ` 读取 render 尺寸的视空间深度。
- `t4 gAlbedo / t5 gEmissive` 提供主表面材质，仅在线性 RR 模式中重调制为最终颜色。
- `t6 gSpecular / t7 gSpecularGrazing` 读取镜面 Schlick 系数，`t8 gSpecularMaterial / t9 gNormalRoughness` 提供 F0 与线性 roughness。
- `aux.x` 是相机主射线距离，`aux.y` 是太阳可见性，`aux.z` 是第一次 diffuse 弹射的距离。弹射打空写 `1e9`，归一化后为 1；`aux.w` 是主命中材质 ID 的原始 uint 位。
- `u0 gRadiance / u1 gSpecularRadiance` 均为 `RGBA16_FLOAT`。REBLUR 分别存解调 diffuse/specular 的 YCoCg；DLSS-D 的 u0 输入为 `albedo * irradiance + specularColor + emissive` 的带噪线性 RGB。
- `u2 gSpecularHitDistance` 为 `R32_FLOAT`，写世界单位首次反射距离；无效距离与天空主像素写 0，反射打空写 `1e9`。RR 读取这张独立纹理，禁止传入 NRD 的归一化 alpha。
- Diffuse 的归一化距离为 `saturate(aux.z / (3 + abs(viewZ) * 0.1))`，对应默认 REBLUR hit-distance 参数与 diffuse roughness=1。不得用 `denoisingRange` 作除数。
- Specular 使用独立反射距离，除数为 `(3+abs(viewZ)*0.1)*lerp(20,1,(1-exp2(-200*roughness²))*sqrt(roughness))`，与 NRD 默认参数一致。
- `CommonSettings.denoisingRange` 与 JVM 常量均为 500000。无效 G-Buffer 像素写两倍该范围的 viewZ，并使用有限的备用法线；零深度不会被 NRD 当作天空。

帧内先追踪并补写主命中材质，再产生 motion 与 packed guides，最后纹理化辐亮度。RR 尝试使用线性输入；
RR 不可用时重新编码同一帧输入为 YCoCg，再派发 NRD。只有成功的输出才交给合光。

在 `denoiseFrame()` 内检查本帧 motion、packed normal/viewZ 与辐亮度纹理化的生成结果。
任一步失败就停止本帧降噪及后续独立 SR，合光读取本帧原始辐亮度；禁止仅凭缓存纹理非空继续提交 SDK。
失败帧不确认历史消费者，下一次成功调用先 reset，之后连续帧继续累积。

合光第七槽 `t6 gDenoised` 绑定输出纹理。根常量 `g_denoiseEnabled` 为 0 时读原始缓冲，
为 1 时读取已包含主表面材质的线性 DLSS-D 输出，禁止再次乘 albedo 或叠加 emissive；
为 2 时分别解码 diffuse 与 `t10 gDenoisedSpecular` 的 YCoCg，再乘 diffuse albedo 与 `max(F0,0.02)`，叠加 emissive。
采样坐标按降噪纹理自身的尺寸映射，不沿用 trace 索引。
相机半径淡出始终使用 `aux.x` 的插值值，禁止把降噪后的 alpha 当作相机距离。

`denoise_ms` 使用 `D3D12RtStats.SLOT_DENOISE` 计时。没有降噪的帧在追踪结束后补打该时间戳。

### 23.8 适配检查与验收（S4 4-f）

`_port/rt_nrd_adaptation_check.py` 检查 14 条源码契约，`--selftest` 覆盖错误单位、空间、深度分量与接线缺失：

| 契约 | 判据 |
| --- | --- |
| 深度 | viewZ 使用世界单位，`viewZScale=1` |
| 无效像素 | JVM/native 范围一致，天空 viewZ 超过范围，禁止归一化零法线 |
| 颜色与距离 | REBLUR 编码/解码 YCoCg，diffuse 使用 secondary hit distance，相机淡出使用主射线距离 |
| 运动矢量 | 二维屏幕像素位移，`motionVectorScale={1/w,1/h,0}`、`isMotionVectorInWorldSpace=false`；NRD 从矩阵推导 viewZ 变化 |
| 历史 | 统一 `InvalidationReason` 位掩码与逐消费者确认，常态继续累积；见 §23.7 |
| 资源状态 | 五张输入 SRV，两张输出 UAV，完成后回 COMMON |
| 双 lobe | 实际双通道实例、specular 资源映射、独立命中距离与可逆 F0 解调 |
| RR 反射 | 八张纹理、世界单位距离标签、diffuse/specular seed 随实际帧号更新 |
| 轨道闸门 | OFF / RAW 早退，每帧先清空上次输出 |
| 抖动 | 无 jitter 矩阵与真实运动 MV，NRD 采样偏移为 `(-Jx,+Jy)` |
| 附件数 | G-Buffer 附件固定 5 张 |
| 粗糙度 | 打包下限为 `1.5/512` |
| 合光坐标 | 按降噪纹理自身尺寸计算采样位置 |

纯 CPU 矩阵回归覆盖相机平移、旋转、FOV 变化、历史数组别名，以及摇晃平移/旋转下的
18 个屏幕采样点、径向深度、运动矢量、正反 Z 投影与太阳四象限；GPU 彩色信号回归检查有色辐亮度与负 chroma。
实际 `rt_motion.hlsl` 的 GPU 回归使用独立解析场景，覆盖静止、相机平移/旋转、
FOV 变化与当前相机摇晃平移；105 个像素检查验证像素单位、屏幕 Y 方向与零/负深度清零。
这些检查只覆盖可自动判定的契约。噪点、鬼影、黑块、条纹与材质纹理须由用户在世界内确认。

`python _port/rt_guide_failure_check.py` 调用实际 JVM `denoiseFrame()`，在缓存已分配时，
分别令 motion、pack 与 radiance compute 拒绝一次，覆盖 `NRD_SR` 和 `RR` 共六组。
要求失败帧不调用 SDK、不发布降噪输出，恢复帧 reset，下一连续帧不 reset。
引导 compute 使用实际 native/GPU 路径；vendor 求值被截获以记录调用与 reset，不能据此认定真实 SDK 求值或画质通过。
报告记录六次拒绝、18 次截获调用及源码/字节码/运行库哈希；该检查纳入 `rt_check_all.py --gpu`。

同一探针的 `--routes` 运行实际 `denoiseFrame()` 与 `upscaleDenoisedFrame()`，覆盖四条降噪轨道、
四个 SR provider 和 100%/67%/50% 三种尺寸，共 48 个组合；另覆盖 RR 失败回退、NRD 失败恢复、
SR 求值失败与重调制失败后的抑制/恢复，共 18 组恢复路径、250 帧入口调用。
它核对 SDK 调用顺序、互斥、输出发布尺寸、reset 与 raw fallback；vendor 求值仍被截获，
不作图像质量或真实 SDK 画质验收。该运行已加入 `rt_check_all.py --gpu`。

`python _port/rt_nrd_temporal_check.py` 经引擎七纹理 ABI 执行实际 NRD 对照，
128×64、每组 24 帧、219 次 NRD 提交及一次编码 compute，逐帧等 fence 并读回双通道：
GPU 总门使用 `--jitter-interface`，检查新旧入口实际求值、非零偏移与非法输入拒绝。
`--highlight` 是可选的过滤后高光诊断，额外执行 864 次 NRD 求值，不作为默认阶段门。
表中的图像阈值是该探针的固定场景配置，用于诊断同一 SDK；不要求 NRD 与 RR 或其他降噪器达到同一输出，
也不以同一 SDK 的历史路径或编码负例达到固定图像差异作为阶段门。
输入编码、单位、资源安全与真实求值按工程约束核验；滤波后的高光和运动质量由用户分别实机判断。

| 项目 | 输入对照 | 当前脚本诊断配置 |
| --- | --- | --- |
| 深度 | 倾斜平面与轴向相机平移；viewZ 对比误喂 deviceZ/reverse deviceZ | 正确输入相对对比 RMSE 小于 1%；负例超过 2% 且 MSE 至少为正确输入 4 倍 |
| 法线空间 | 相机 0/0.45 rad 交替旋转；世界法线对比视图法线 | 视图法线的平均 MSE 超过世界法线 2 倍 |
| MV | 世界位置辐亮度渐变；解析像素 MV 对比反号和归一化 UV MV | 两个负例的平均 MSE 均超过正确像素 MV 2 倍 |
| 历史 | 预热后改变信号；reset 对比新建上下文首帧 | 同一 SDK 帧号及输入的最大差小于 0.002，CONTINUE 与首帧可区分 |
| 粗糙度编码 | 产品 pack 对比官方 pack/unpack，七档 roughness 对比平方与开方误用 | 8,192 样本匹配法线/粗糙度；GGX NDF 高光半宽与解析解一致，错误输入半宽至少相差 1.5 倍 |
| 过滤后高光 | roughness 0.2/0.35/0.5、三个 seed、32 帧；只改变粗糙度 guide 编码 | 中央八行镜面首通道（Y，输入为灰度）平均 profile 的 FWHM：正确九组相对误差均小于 5%，且随粗糙度递增；平方/开方误用的聚合宽度 MSE 各超过正确编码 2 倍，至少一组宽度相差超过 1 pixel |

深度/MV/法线的平均 MSE 为两路均值，单路数值另行记录，不能宣称每一路负例同等敏感。
报告写入工作区 `_tmp_s4_nrd_temporal.json`，包含 DLL SHA-256、设备、参数、阈值与结果，
并由 `rt_check_all.py --gpu` 调用。编码部分使用官方 HLSL 解码与解析 GGX 半宽；
过滤后高光另增加 864 次 NRD 求值与三次原始参考 compute，逐组重建上下文并匹配 SDK 帧号和噪声。
CPU 参考与无噪声 GPU 原始 profile 的最大差须小于 0.005，报告保留完整 profile、
阈值、探针/HLSL 哈希与 `filtered_highlight.measurement_complete`。
过滤后的宽度、随粗糙度递增趋势、负例倍数与新旧入口颜色差写入 `image_diagnostics`，不决定工程通过状态。
过滤后判据只覆盖 FWHM，不能外推到峰值、能量或世界内高光质量，也不要求每档错误编码都更差。
该测试不覆盖 RR 的 SDK 负例、非零 jitter 或曝光对照。

### 23.7 历史累积与复位（S4 4-e 的一部分）

`D3D12RtHistory` 是 RT 历史失效的唯一状态管理者。每次实际派发前，将同帧相机、世界时钟、
实际镜像版本、设备/guide 身份与当前 Plan 生成一份 `Snapshot`；`begin()` 合并显式请求与比较结果，
输出 `Invalidation(generation, frame, reasons)`。`reasons` 为 `InvalidationReason.mask` 的按位或，
有新原因才增加 generation；观察帧号独立递增。

| 原因 | 触发条件 |
| --- | --- |
| `FIRST_FRAME` | 尚无上一份快照或资源释放后重新开始 |
| `RESIZE` | 显示、render 或 trace 尺寸变化 |
| `CAMERA_CUT` | 单帧位移大于 16 m；forward/up 点积小于 `0.70710677`；投影关键系数相对变化超过 10% 或投影类型/偏移突变；相机数据非有限 |
| `WORLD` | 关卡对象身份或 `D3D12SectionGeometry.epoch()` 变化 |
| `GEOMETRY` / `MATERIAL` | 本帧实际采用的 CPU→GPU 镜像版本变化；不按仍未上传的全池版本每帧清历史 |
| `TIME_JUMP` | 同一关卡 default clock 倒退，或相邻观察帧前进超过 200 ticks |
| `DEVICE` | 设备对象改变，或 RT 上下文释放后重建 |
| `FRAME_GENERATION` | FG 选中后端、模式或倍率变化 |
| `PIPELINE` | RT 开关、降噪轨道、独立 SR 后端或 实际 trace backend 变化 |
| `RESOURCES` | G-Buffer albedo 附件对象改变；五张附件按组重建 |
| `FRAME_GAP` | 相邻观察帧时间戳不递增或间隔超过 500 ms；此前派发未成功 |
| `EVALUATION_FAILURE` | motion、packed guide 或辐亮度纹理化失败，或 NRD 求值未成功；下一帧保留复位请求 |

普通行走、摇晃、连续小幅转向与平滑 FOV 变化保持历史。投影快照复制数组，禁止与下一帧写入共用可变数组。
相机、重放校验、历史失效和曝光时间共用 `D3D12Temporal.frameTimestampNanos`：按呈现帧身份缓存
`System.nanoTime()`，同帧多次读取保持不变，下一帧重新取样。禁止使用 `Minecraft.frameTimeNs` 作为时间戳；
该属性是上一帧的耗时，帧率变化会使其下降或重复，错误触发 `FRAME_GAP` 和曝光复位。
几何镜像的位移检查必须先于版本命中返回；相机移动超过镜像余量 24 m 时，即使版本未变也重新选择附近几何。

世界身份按对象引用比较，禁止使用 identity hash 代替身份。`D3D12SectionGeometry.observeLevel()`
由客户端 tick、上传入口和实际派发共同观察；世界变化时在同一注册表锁内清空 section 元信息、
RT 顶点/索引/逐 quad 材质池与材质间接表，推进 `epoch` 和材质 revision。
即使新世界没有上传几何，退出到菜单或换世界也会清空 CPU 数据；null 世界的上传不发布记录。
上传的版本计算与池发布共用注册表锁，池切片在自身锁内一并捕获 `geometryEpoch/geometryRevision`。

`D3D12RtFrame` 单独记录已观察的 epoch，使上传先发现换世界时，下一次 tick/派发仍执行 GPU 镜像失效。
镜像只能在世界对象及 epoch 同时匹配时复用；跨世界不受镜像节流和无新切片时返回旧镜像的路径影响。
本帧历史读取实际切片携带的 epoch/revision，禁止把新全局 epoch 与旧镜像混成同一快照。
世界变化使当前帧结果和上一相机失效，清空可用 BLAS/TLAS/实例表，旧 AS 进入既有延迟回收队列；
实际资源销毁继续经过 native 的提交 fence。scratch 分配可复用，新世界的 AS 从新镜像重新构建。
世界变化后材质表在下一次 tick 重烘，几何不足时允许重新触发原版上传。

RR、NRD 与独立 SR 各持有一个 `Consumer`。generation 改变或该消费者漏过上一观察帧时，
`requiresReset()` 返回 true；只有 SDK 接受求值且该阶段产出纹理后才 `accept()`。
未执行的消费者不消耗复位，失败或暂时绕过后恢复仍必须 reset。RR 成功后不执行 NRD/SR，
因此随后进入 NRD 回退时也不会复用间断历史。

NRD 用该结果选择 `CLEAR_AND_RESTART` / `CONTINUE`，RR 写入 `sl::Constants.reset`。
独立 SR 合并 `changed || resetHistory`，分别传给 DLSS viewport 2 和 XeSS/FSR dispatch；
禁止只清 NRD 而让 SR 继续累积旧帧。所有 SDK 帧号仍递增，历史 generation 不代替 SDK token。
失效帧清除上一视图的有效标志，再写入同源相机矩阵，使该帧重投影不引用旧视图。

纯 CPU 回归覆盖 21 类事件、普通行走/摇晃、16 m/200 ticks/500 ms 边界、可变投影数组、
三个延迟或跳过的消费者、合并失效及释放后的代数单调性；源码契约门另检查三处消费与独立 SR 参数传递。
这些检查验证产品状态机，不代替逐原因的实际 SDK/世界内验收。
当前 FG 覆盖配置切换，尚未接入 native FG 内部 reset 回调；未来缓存/Reservoir、光谱配置和模型版本
仍需把各自事件接入相同位掩码。离线 `rt_world_geometry_check.py` 使用已编译 JVM 类检查
同名不同世界对象、同坐标新几何、空世界、退出到菜单、128 次双线程上传与 AS 延迟回收状态，已纳入静态总门。
默认 AS 回收检查使用合成句柄验证状态转换。追加 `--gpu` 时，通过实际 `D3D12Device`、FFM ABI
与产品 `D3D12RtBlas.prepare()` 建立 BLAS/TLAS：两个世界在同一 section 分别放置 4 m/9 m 深度的 quad，
交替切换后用 RayQuery 读回命中距离。四组各三次提交，队列等待独立 CPU fence；另一个队列 marker
确认提交尚未执行，此时失效旧 AS、关闭输入缓冲并切到空世界，然后放行并等待各 encoder 的提交 fence。
12 次读回均须匹配各自的 4 m/9 m，误差预算 `1e-4`，并要求 D3D12 InfoQueue corruption/error 为零。
这覆盖三张在途列表与产品 AS 失效/回收路径，不包含游戏窗口、完整帧降噪或世界内画质验收。

GPU 探针仅创建隐藏 Win32 窗口，不启动客户端。运行目录在工作区 `_tmp_s4_world_geometry_probe`，
classpath 首位放所选 DLL，防止已打包资源覆盖开发 DLL；结束时比对所选与实际 DLL 的 SHA256。
`_tmp_s4_world_gpu.json` 保存逐提交 epoch/fence/预期与实际距离、设备/驱动、调试层错误，
以及 DLL、运行库、探针、产品源码与 HLSL 哈希。失败启动先覆盖为 `passed=false`，禁止遗留上轮通过报告。
该检查已纳入 `rt_check_all.py --gpu`；真实世界切换、长时间运行与未来缓存清理仍须分别验收。

### FFM 句柄签名必须与 native 原型逐项一致

`MethodHandle.invokeExact` 的参数类型必须与 `downcallHandle` 时给的 `FunctionDescriptor` 完全一致，
差一个类型或一个参数都会在**调用时**抛 `WrongMethodTypeException`（编译期没有任何提示），
而包装层把它当成「功能不可用」打一行 warn 就降级了 —— 表现为 `nrd: not built in` 或
`rt dlssd: unavailable (no diagnostic)` 这类看不出根因的日志。

- 首参数是 `mcdr_command_ctx*` / 设备指针的入口，描述符必须写 `PTR`，调用侧写 `ptr(ctx)`；
  传 `Long` 会在运行期抛异常，而不是自动转成指针。
- 参数个数要按 `mcdr_abi.h` 的原型数，多写一个 `I32` 同样是运行期才炸。
- 新增 ABI 入口时先对着头文件抄一遍描述符，再用 `_port/abi_layout_check.py` 与探针各跑一次。

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
仅在 NVIDIA 适配器（vendor `0x10DE`）上创建设备，避免双显卡机器按 DXGI 顺序误选集显。
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

游戏进程内 NGX 由 Streamline 持有，独立 NGX availability 不能代替插件加载与实际求值。
`sl::Preferences` 必须与独立探测使用相同的非空 Project ID
`7c1f4a52-9b3e-4d68-8f21-5a6c0e2d9b74` 和 engine version `1.0`。
Streamline 在 engineVersion 非空时用 Project ID 查询能力，禁止只填版本而保留空 projectId。

设备日志分别报告 `supported`、实际 `loaded` 与 `optimalRenderWidth/Height`；
`sl::Result::eOk` 的数值为 0，不能将原始返回码直接当作 supported 布尔值。
实际闭合条件是 §25 中 `slSetConstants`、标签与 `slEvaluateFeature` 连续成功。
不可用时执行 NRD 回退，并保持“RR 未执行”的诊断，不能据回退成功判定 RR 已验收。

## 25. DLSS-D 逐帧求值（S4 4-d）

### 25.1 一帧的动作

求值在 native 的 `mcdr_dlssd_eval`（`MCDR\src\advanced\d3d12_dlss.cpp`）里，Kotlin 侧准备八张纹理：

1. 输入/输出尺寸变化时查询 `slDLSSDGetOptimalSettings`，选择包含实际输入宽高的动态区间。
   原生尺寸优先 `eDLAA`；缩放输入依次尝试 Quality、Balanced、Performance、UltraPerformance。
   没有合法区间时拒绝 RR 并回退 NRD。每帧下发相机视图正逆矩阵与选项，HDR 开启、alpha upscaling 关闭、preset F。
2. `slGetNewFrameToken` 使用持续递增的 RR 帧号。重置降噪历史时禁止将帧号归零；
   同一 viewport/帧号提交不同常量会得到 `eErrorDuplicatedConstants`。RT RR 固定使用 viewport 1。
3. 打标签（`slSetTagForFrame`），顺序固定：

| 槽 | `sl::BufferType` | 资源 | 生命周期 |
| --- | --- | --- | --- |
| 0 | `kBufferTypeScalingInputColor` | `rt-nrd-radiance`（带噪、已重调制线性颜色） | `eOnlyValidNow` |
| 1 | `kBufferTypeScalingOutputColor` | `rt-rr-output`（显示尺寸） | `eOnlyValidNow` |
| 2 | `kBufferTypeLinearDepth` | `rt-nrd-viewz`（R32_FLOAT，正的 viewZ） | `eValidUntilEvaluate` |
| 3 | `kBufferTypeMotionVectors` | `rt-motion`（像素单位） | `eValidUntilEvaluate` |
| 4 | `kBufferTypeNormalRoughness` | `rt-gbuffer-normal-roughness`（原始世界法线 xyz、roughness w） | `eOnlyValidNow` |
| 5 | `kBufferTypeAlbedo` | `rt-gbuffer-albedo` | `eOnlyValidNow` |
| 6 | `kBufferTypeSpecularAlbedo` | `rt-gbuffer-specular` | `eOnlyValidNow` |
| 7 | `kBufferTypeSpecularHitDistance` | `rt-specular-hit-distance`（R32_FLOAT，世界单位反射距离） | `eOnlyValidNow` |

4. `slEvaluateFeature(sl::kFeatureDLSS_RR, token, {viewport}, 1, list)`。

纹理与资源状态的对应关系是硬要求：输入必须停在 `NON_PIXEL_SHADER_RESOURCE`、输出停在 `UNORDERED_ACCESS`，
否则 SL 打标签成功但读到的内容未定义。`dlssdEval`（`MCDR\src\core\d3d12_device.cpp`）负责在调用前后做这组
过渡，返回后统一回 `COMMON` 并恢复绘制用的描述符堆。

### 25.2 法线、粗糙度与深度

DLSS-D 的 `normalRoughnessMode` 只有两种：`ePacked`（一张打包图）与 `eUnpacked`（法线 + 粗糙度分开）。
本工程使用 `ePacked`，直接传原始 G-Buffer `RGBA16_FLOAT`：xyz 为世界空间单位法线，w 为线性粗糙度。
禁止传入 NRD 的八面体编码纹理；RR 与 NRD 的 packed 含义不同。
Streamline DLSS-RR Programming Guide §4.1.3 同时允许世界与视图空间的单位法线。
验证本工程的世界空间约定时，构造与相机矩阵/所选空间不一致的负例；不能把视图空间本身判为错误。

深度传入已有 NRD pack 生成的正 viewZ，标签为 `kBufferTypeLinearDepth`，`depthInverted=eFalse`。
SDK 指南 §4.1.7 也支持 `kBufferTypeDepth` 硬件深度；必须按标签与反向深度标记配对验证，
不能把合法的硬件深度配置当作负例。
G-Buffer 深度附件没有 texture-binding 用法，不能作为 RR 的采样输入。
specular albedo 使用主命中材质 guide 生成的像素 F0；输入颜色包含独立 GGX 镜面贡献。
反射重投影使用世界单位 `specularHitDistance` 与本帧 `worldToCameraView/cameraViewToWorld`。
该距离为独立 `R32_FLOAT` 纹理，反射打空使用 `1e9`；禁止传入 NRD 的粗糙度归一化距离。
Diffuse 与 specular 的采样 seed 必须包含实际帧号，使静止画面能够积累不同弹射样本。

### 25.3 回退

`RR` 轨道上 SL 拒绝一次（返回 0 或负数）后，在当前 Plan 内停止重试，打一行
`rt dlssd: unavailable (<原因>), falling back to nrd`，这一帧起退回 NRD 的 `REBLUR_DIFFUSE_SPECULAR` 路径。
设备重建、光追关闭重开或 Plan 变化会 `D3D12RtDlssd.reset()`，给 SL 一次重新尝试的机会。
Plan 包含轨道、超分方案和实际输入/输出/trace 尺寸；历史按失效事件复位，SDK 帧号继续递增。

### 25.4 日志

```
dlssd: eval 0x0 consts=0x0 2560x1334 -> 2560x1334 packed=1
```

六个字段：`slEvaluateFeature` 返回码、`slSetConstants` 返回码、渲染尺寸、输出尺寸、是否打包模式。
两个返回码分开看是关键：`eval` 非 0 而 `consts` 为 0 说明常量没问题、问题在标签或求值；`consts` 非 0
说明常量本身就过不去（多半是矩阵布局或必填字段）。

### 25.5 验收

进游戏把 `[raytracing] denoise` 设为 `RR`，日志里应出现上面那条 `dlssd: eval`；
`NRD_SR` 使用 `nrd: dispatch …`，`RAW` 不得派发 NRD 或 RR。
成功日志只报一次，须持续观察有无回退。实际执行 RR 后，在同一处暗面与高光表面静止观察，
确认离散 bounce 亮点随历史积累收敛，再行走、转动相机检查残影。噪点与残影由用户目视验收。

### 25.6 相机常量（`slSetConstants`）

常量在 Kotlin 侧由 `D3D12RtFrameConstants.write` 的 `outSlConstants` 生成，共 104 个浮点。
缺失或 `slSetConstants` 返回失败时立即停止 RR，不能带着无效相机继续求值。
`mcdr_dlssd_frame.constants` 透传到 `d3d12_dlss.cpp`，再由 `slSetConstants` 交给 SL：

| 偏移 | 内容 | 来源 |
| --- | --- | --- |
| 0 | `cameraViewToClip`（行向量、行主序） | 本帧投影矩阵的转置 |
| 16 | `clipToCameraView` | 本帧投影逆矩阵的转置 |
| 32 | `clipToPrevClip` | （上一帧视投影 × 本帧视投影的逆）的转置 |
| 48 | `prevClipToClip` | 上式的逆 |
| 64 | `cameraNear` / `cameraFar` / `fovY` / `aspect` | 投影矩阵的构造参数 |
| 68 | 当前屏幕内容偏移 xy、上一帧屏幕内容偏移 xy | 共享 Halton `(Jx,-Jy)`，首帧上一帧等于当前 |
| 72 | `worldToCameraView` | 当前视图矩阵的转置，行主序 |
| 88 | `cameraViewToWorld` | 当前逆视图矩阵的转置，行主序 |

全部六个矩阵都从 JOML 的列向量约定转换到 Streamline 的行向量约定。
相机位置读取转置后逆视图的最后一行，right/up/forward 从前三行读取（forward 取负 Z）；
pinhole 为零，`jitterOffset` 读取 68/69，motionVectorsDilated/Jittered 显式关闭。
68–71 四分量必须有限且在 `[-0.5,0.5]`，RR 与 RT DLSS SR 都在接管资源前校验。
视图正逆矩阵同时传给 DLSSDOptions，每帧更新。

三条硬约定：

- **行向量与行主序**。SL 的 `float4x4` 使用 `row[4]`，向量从左侧相乘，平移位于最后一行，
  见 `MCDR\external\streamline\include\sl_matrix_helpers.h`。`putStreamlineMatrix` 使用 JOML 的
  `get(float[], offset)`：原矩阵的列主序数组正好是数学转置后矩阵的行主序数组。禁止仅更换存储顺序而不转置变换。
- **共享亚像素采样**。RR/SR 偏移取 `(Jx,-Jy)`，SDK 矩阵不含 jitter；光栅/PT 使用采样矩阵，MV 移除偏移差。
  原版行走/受伤摇晃保留在视图正逆矩阵中，启用闸门见 §19.2。
- **运动矢量单位**。我们的 MV 是像素单位且 +y 朝下，所以 `mvecScale = {1/w, 1/h}`，
  与 NRD 的 `motionVectorScale` 同一口径（见 §23.8）。

验收时分别观察 `rr` 与 `nrd_sr` 的噪声、残影、闪烁、纹理/高光保留和运动后收敛。
两种方案允许滤波、锐度、噪声与收敛表现不同，由用户分别判断是否可接受；不要求两图接近或达到某种一致等级。
输入的深度/MV/jitter 单位、法线与粗糙度编码、曝光尺度以及资源生命周期仍须正确。

### 25.7 RR 离线 SDK 对照

离线数值用于检查 SDK 接线与辅助定位，不作为各降噪器之间的图像一致性门禁。
不以统一 MSE/FWHM、PSNR/SSIM 或错误输入必须退化两倍等标准决定 S4 是否完成。
下述脚本中的图像阈值是具体合成场景的诊断配置；工具的 INCOMPLETE 若仅来自这些指标，
表示该诊断未形成结论，不表示渲染功能失败。黑块、异常亮点、纯色材质、曝光错误、明显残影和资源/设备错误须分别处理。

总门使用的 RR core、NRD、共享 jitter 和曝光 ABI 报告按下表区分结果：

| 字段 | 含义 |
| --- | --- |
| `acceptance_basis` | `engineering_contracts`；只表示当前探针覆盖的工程协议检查 |
| `engineering_passed` / `passed` | 真实求值、输入与资源等检查的结果；工程错误仍返回非零 |
| `image_diagnostics` / `image_diagnostics_passed` | 合成图像指标及其预算结果；未测量时汇总为 `null`，未达到预算不改变工程退出码 |
| `world_quality` | 固定为 `unverified`；实际世界画质由用户确认，离线探针不能自动改成通过 |

在工作区根执行 `python _port/rt_rr_temporal_check.py --case core`，通过实际引擎八纹理 ABI，
以 640×360 原生尺寸、每组 24 帧验证 HDR、运动矢量与历史 reset。主进程 123 次求值，
独立对照进程 122 次求值，逐帧等 fence 并读回；报告写入 `_tmp_s4_rr_temporal.json`，
包含运行库/探针 SHA-256、设备、输入参数、阈值、RGB 误差与未覆盖项。

| 项目 | 对照 | 当前脚本诊断配置 |
| --- | --- | --- |
| HDR | 带噪线性 `(4, 2, 0.5)` | 各通道相对 RMSE 小于 5%，R 通道 MSE 比输入至少降低 4 倍 |
| MV | 世界位置渐变与相机交替旋转；正确像素 MV、反号、UV 单位 | 正确 R 通道 MSE 小于 0.005，两个负例均超过正确值 2 倍 |
| 历史 | 辐亮度改变后的按需 reset、CONTINUE 与独立进程每帧 reset | 对齐求值次数/SDK 帧号/输入后，reset 与对照的 RGB 最大差小于 0.002；CONTINUE 各通道 MSE 超过 reset 2 倍 |

历史对照进程使用全程 reset 预热；此为匹配内部采样相位的对照，不是冷启动首帧。
RR 帧号始终递增，禁止在同一进程复用编号。相邻两次 reset 的 SDK 内部采样相位可能不同，
不能将差异直接解释为旧历史残留。

`rt_check_all.py --gpu` 调用 `core` 检查。`--case all` 另外采集深度、法线与粗糙度候选场景，
报告写入 `_tmp_s4_rr_exploratory.json`；负例敏感度不作为工程门，候选场景不替代世界内画质验收。
`--case depth_edges` 另以三个独立进程比较 viewZ、误喂 deviceZ 与误喂 reverse deviceZ。
使用 `--depth-scene` 选择以下固定场景，每组默认 24 帧：

| 场景 | 解析信号与测量区域 |
| --- | --- |
| `textured`（默认） | 横移相机观察 5 m 遮挡半平面与 30 m 背景的世界坐标纹理，测量遮挡边缘两侧各 8 pixel |
| `disocclusion` | 相同几何与运动，前景辐亮度 0.2、背景 1.2，测量相同边缘区域 |
| `reflection` | 5 m 镜面反射身后 5 m 的纹理墙，虚像深度 15 m；roughness=0.1，hit distance 为反射段实际长度，测量画面中央半宽、半高 |

匹配全部相关运行库、探针、初始化/读回 shader 哈希、SDK 帧号、实际求值次数与噪声。
每帧在 SDK 调用前以独立 compute 读取 depth、MV.xy、normal.xyz 与 roughness，逐像素对照 CPU 解析参考。
深度最大误差须不超过 0.0001，两个 MV 分量各不超过 0.02 pixel，法线逐分量一致，roughness 误差不超过 0.0001。
保存每帧边缘/区域误差与完整输出读回哈希，以及末帧完整输出、参考/噪声和引导缓冲快照。
父进程另从快照重算引导缓冲检查，确认变体的深度确实不同，其他引导值完全一致；
SDK 接管/归还/恢复堆必须与帧数相等，无外部残留、violation 或 D3D12 corruption/error。
报告分别为 `_tmp_s4_rr_depth_edges.json`、`_tmp_s4_rr_depth_edges_disocclusion.json` 与
`_tmp_s4_rr_depth_edges_reflection.json`；启动父进程时先写入未完成状态，避免子进程失败后留下旧通过结果。
子报告的 `measurement_complete=true` 只表示采样完成，`passed` 保持 false；适配通过由父进程单独判定。
该脚本的诊断配置为正确深度 R 通道 MSE 小于 0.01、噪声误差降低四倍和错配组超过正确组两倍。
这些数值用于复现该实验，不是深度协议正确性或 S4 可用性的统一门禁。
三个场景的 viewZ 与误喂 deviceZ 在全部 24 帧的完整读回均相同；GPU 引导缓冲核验确认它们的深度值不同。
`disocclusion` 的正确输入末帧 MSE 约 0.00899326、输入噪声 MSE 约 0.0379590，达到正确输入预算，
但两个错配负例均未达到退化预算。`textured` 与 `reflection` 的正确输入也未达到全部预算。
三个场景均返回 `INCOMPLETE`/非零，不接入通过的总门；不能据此修改产品深度约定或宣称 SDK 忽略深度。

执行 `python _port/rt_rr_temporal_check.py --case highlight --frames 32` 测量过滤后高光。
三个独立进程分别提交 linear/squared/root guide，每个进程包含 roughness 0.2/0.35/0.5、
三个噪声 seed、每组 32 帧，共 864 次求值。保持 SDK 帧号、实际求值次数、输入噪声及参考信号匹配，
核对 DLL/SDK/探针哈希，保存每组末帧完整读回、参考/输入哈希与中央八行 R 通道平均 profile。
静止相机观察半径 3 m、中心 `(0,0,-10)` 的球，输入为峰值归一化的解析 GGX lobe，
基线为 0.02、峰值增量为 4、乘性噪声幅度为 0.8，合成 specular hit distance 固定为 5 m。
此为静态 guide 敏感度实验，不是实际光线输运或反射运动的参考渲染。

CPU 双精度参考用于检查 GPU 原始 profile，合成参考差异的容差为 0.005。
该脚本记录九组 FWHM、相对宽度误差、负例聚合宽度 MSE 和宽度差。
脚本使用的 5%、两倍和 1 pixel 配置仅描述此实验；不要求实际降噪高光还原解析 lobe。
峰值、峰值位置、基线上方积分及高光 ROI MSE 同样作为诊断输出。
当前正确编码最大宽度误差约 3.56%，CPU/GPU profile 最大差约 0.00153；
平方/开方误用的聚合宽度 MSE 仅为正确值约 1.162/1.236 倍，最大宽度差约 0.3474/0.2479 pixel。
匹配对照下输出有差异，但负例未达敏感度预算；父报告 `_tmp_s4_rr_highlight.json` 保持
`measurement_complete=true`、`passed=false`，命令返回 `INCOMPLETE`/非零，不接入通过的总门。
这些结果用于保留实验事实，S4 可用性与实机验收不依赖此处的固定宽度或退化倍数。
独立子报告的采样完成也不替代实机判断。法线空间的候选负例仍须遵守 §25.2：
官方允许 world/view normals，不能将 view normals 本身定义为错误输入。

高方差候选输入使用 `--highlight-noise sparse --highlight-spp N`，`N` 可为 1/4/16/64。
每个样本以 `1/8` 概率命中，命中权重为 8，再取 N 个样本的均值；基线 0.02 不参与稀疏化，
期望仍为同一个解析 GGX lobe。逐帧/逐像素/逐 seed 的 hash 固定，三个编码变体共享完全相同的输入。
这是 Bernoulli 采样模拟，不是实际反射射线或路径输运参考；不据此修改产品 roughness 编码。
例如 `python _port/rt_rr_temporal_check.py --case highlight --highlight-noise sparse --highlight-spp 64 --frames 48`。
稀疏报告/快照以 `_sparse` 和可选 `_sppN` 后缀保存，不覆盖默认 uniform 结果。

父进程从所有完整快照重算输出/参考/输入哈希、中央八行 profile、FWHM、ROI MSE 与 CPU/GPU 参考误差，
并核对九组 roughness/seed 顺序、SDK 帧区间、当前源码/运行库哈希和接管/归还/恢复堆计数。
稀疏输入另逐像素复算全部样本的 uint32 hash 与命中数，对照快照中的 R 通道输入，验证该通道的 GPU 噪声；
半精度每次写入使用一个 binary16 ULP 的保守误差界限，另留 `1e-5` 的 float32 运算误差。
该界限只用于输入校验；5%、两倍与 1 pixel 仍保存在探针配置中，图像指标不作阶段验收门禁。
64 样本、48 帧对照共 1,296 次实际 RR 求值，27 份完整快照和全部输入检查通过，
但正确编码最大 FWHM 误差约 7.24%，平方/开方负例的聚合宽度 MSE 比例约 0.841/1.067，
最大宽度差约 0.284/0.201 pixel；报告保持 `passed=false` 并返回 `INCOMPLETE`/非零。
16 样本、128 帧的延长对照共 3,456 次实际 RR 求值，27 份完整快照和逐像素 R 输入复算通过；
正确编码最大 FWHM 误差仍约 5.28%，平方/开方的聚合宽度 MSE 比例约 0.834/1.095，
最大宽度差约 0.277/0.317 pixel；同样返回 `INCOMPLETE`/非零。
输入/资源检查与合成图像度量分别报告；上述候选对照保留为可选诊断，不作为 S4 的阻塞项。

当前 RR 使用共享 jitter、未曝光线性 HDR、`preExposure=1`；本节的零偏移 HDR 检查不代替手动/自动曝光适配。
这些数值检查也不代替世界内画质、全部历史失效原因、状态归属或完整后端组合矩阵验收。

玩家 RT 路径的曝光参数遵守以下当前契约：

- PT、NRD 与 RR/独立 SR 的输入颜色保持未曝光线性 HDR，SDK 的 `preExposure/exposureScale` 均为 1。
  最终合光读取 `t11 gExposure`，只执行一次曝光与 tone map；`WgaFrame.g_exposure.x` 不再决定 B-DXR 合光曝光。
- RR 与 RT DLSS-SR 均通过显式曝光入口提交同一张 `kBufferTypeExposure`，它由引擎计算。
  SR 设置 `useAutoExposure=eFalse`；玩家的 AUTO 是引擎测光，SDK 仍使用显式资源模式。
  对于旧 API/无资源调用，Streamline SR 在缺少该资源时设置 AutoExposure feature flag。
- RR 支持可选曝光纹理，其 `DLSSDOptions` 没有 `useAutoExposure` 字段。
  所带 RR 插件读取曝光纹理并提交 NGX，创建 flags 未设置 AutoExposure；缺纹理时 SDK 内部如何处理曝光，代码中未明确。
  禁止将 SR 的自动曝光开关当作 RR 的同名模式。
- 可选曝光纹理是 1×1 `R32_FLOAT` 的最终曝光倍率，不是已 tone map 的颜色。
  若将输入预乘曝光，必须同步传实际 `preExposure`，并在输出与最终合光中保持同一颜色尺度。
- SR 的 AutoExposure flag 在创建 feature 时确定。当前所带插件的重建判断不包含 `useAutoExposure`；
  设计热切换时必须显式处理 feature 重建，不能仅凭 setOptions 成功声称模式已切换。

ABI 23 另提供显式曝光入口 `D3D12DlssdEvalExposure` / `D3D12RtSrEvalExposure`，
接收独立两 float 的 `preExposure / exposureScale`，可额外传入一张 1×1 `R32_FLOAT` 曝光纹理。
相机载荷仍为 104 floats；新参数没有追加到相机数组。标量须有限且在 `[2^-16,2^16]`，
纹理完整预检发生在接管前；曝光资源与其他输入一起按 NPSR 接管、返回后回 `COMMON`。
资源模式切换先等待上次求值 fence，再释放 feature、清理选项缓存并请求 reset。
同一未提交列表内切换被拒绝；单纯修改同模式的两个标量会更新 SDK options。
玩家 RR 与独立 DLSS-SR 均使用显式入口，传入 `preExposure/exposureScale=1` 和同帧曝光资源。
NRD、XeSS、FSR 保持未曝光线性信号；最终合光读取相同倍率。手动/自动模式都保留资源，
因此切换玩家测光模式无需切换 SDK feature 的资源模式。
世界内曝光观感仍需用户确认；SDK 参数错配的图像敏感度只作可选诊断。
跨 encoder 的同队列等待、超时原子拒绝及恢复由下述 ABI 探针检查。

配置位于 `config/dxr_mc-client.toml` 的 `[raytracing]`，均支持热修改：

| 键 | 默认值/范围 | 行为 |
| --- | --- | --- |
| `exposureMode` | `MANUAL`，可选 `AUTO` | 手动固定倍率或引擎自动测光 |
| `exposureEv` | `0.0`，`[-8,8]` | 每增加 1 EV，曝光补偿乘 2；手动倍率为 `2^EV` |
| `exposureSpeed` | `1.5`，`[0.1,10]` / 秒 | 自动曝光在 log2 域的响应速率 |

`D3D12RtExposure` 在 trace 与主命中材质更新之后、降噪之前派发一次 `RtExposure`。
单个 256-thread 组均匀采样 64×64 网格，重建 `albedo*(direct+indirect)+specular*F0+grazing+emissive`；
只纳入 trace 命中且 G-Buffer 覆盖、RGB 有限的表面，使用 Rec.709 亮度系数 `(0.2126,0.7152,0.0722)`。
亮度 clamp 到 `[1e-4,1e4]` 后求几何平均，目标倍率为 `clamp(0.18*2^EV/geometricMean,1/256,256)`。
不计原版直通天空/实体；没有有效样本时保持上一倍率，reset 帧使用 `2^EV`。
正常帧在 log2 域以 `1-exp(-speed*dt)` 插值，dt 上限 0.5 秒。
首次分配、上一更新失败、测光模式/EV 变化、世界/相机切换等历史失效会立即重设曝光；
仅几何/材质 revision 更新不重设，避免正常区块上传反复打断适应。资源随 RT release 释放。
固定参考亮度与单一标量曝光不改变白点或逐通道色度。

`python _port/rt_exposure_product_check.py` 运行实际测光与最终合光 HLSL，进入 GPU 总门。
52 次测光覆盖手动 EV、亮暗切换、几何平均、NaN 样本排除、空覆盖保持与 reset；
50 次合光检查分别验证原始与已合成线性颜色，另外两次 NaN 输入只验测光。
CPU 解析参考要求曝光相对误差低于 `5e-5`、最终颜色最大误差低于 0.0015。
保存 52 份完整读回并重算误差；八个漏乘/重复乘曝光负例须产生大于 0.02 的颜色差。
同时核验 HDR 输入未被曝光修改，并要求 D3D12 corruption/error 为空；不代替世界内画质验收。

`python _port/rt_exposure_abi_check.py` 经产品 ABI 检查 RR 原生 320×200 与 SR 50% 放大到 640×400，
包括旧入口、新入口默认参数、缺曝光资源/带资源/旧入口/带资源四段切换。
基础组共 384 次实际 SDK 求值、344 次非法输入或未提交模式切换拒绝。
脚本另诊断旧/新默认路径输出是否逐位相同，以及每段 32 帧的后八帧 MSE 是否低于 0.001；这些图像比较不作阶段门。
带资源段中途改变预曝光，逐帧核对 HDR 预乘输入与曝光值。
报告保存源码、SDK、运行库、输入与快照哈希；父进程复核模式序列、二十四份末帧快照的曝光值和误差。
同时要求接管/归还/恢复堆次数相等、无残留/violation、D3D12 corruption/error 为空。
另有 RR/SR 两个跨 encoder 组，共 328 次实际求值、四次超时原子拒绝与四次延迟等待。
两张真实 encoder 使用同一 GPU queue；先让另一 encoder 的 fence 超过旧列表的待完成值，
确认其完成不能满足尚未提交的旧 encoder，再检查约五秒超时与提交后的重试。
另用 CPU-controlled fence 阻塞 GPU queue，检查已提交旧列表仍未完成时的超时与释放后的恢复。
双向切换时保持队列阻塞至少 0.25 秒，后台确认调用未返回、两个 encoder 的状态/归属/计数未改变，再解除阻塞。
切换成功后继续执行 31 帧；二十四份完整快照分别记录切换时刻及收敛结果。
RR 重建 feature 的首帧会重新积累历史；脚本对切换首帧与恢复 31 帧后的 MSE 分别使用 0.1 和 0.001 诊断预算。
两类统计分别记录；实际检查重试成功、输出有限与资源归还，世界内收敛由用户确认。
父进程复核顺序、fence 数值、等待时间、拒绝原子性、两套 scope 计数、快照哈希与颜色误差。
总计 712 次 SDK 求值、348 次拒绝，检查进入 GPU 总门；`--cross-encoder` 只运行新增两组并复用已有 JVM 输入。
范围为同队列串行求值；独立 GPU queue、同时求值、SDK 曝光错配敏感度及世界画质仍未覆盖。

工作区命令 `python _port/rt_exposure_check.py` 执行独立 SDK 曝光实验，`--analyze` 仅重新审计已有产物。
桥接使用引擎提供的 D3D12 device/queue，自行分配命令列表和纹理，直接调用 Streamline；
此路径不经过产品 RR/SR 的资源接管入口。RR 以 320×200 原生尺寸、SR 以 50% 放大到 640×400，
十二个独立进程各运行 48 帧，分别检查缺曝光纹理、1×1 R32_FLOAT 手动倍率 0.25、
四段预曝光 `0.0625 / 4 / 0.25 / 16` 及 SDK 参数保持 1 的错配组。
输入为十档亮度的纹理 HDR 平面，逐帧 CPU 核验预乘后的输入样本与实际曝光值。
保存源码、SDK 头、运行库、桥接和逐帧输入/输出哈希，以及每段末帧快照；父进程重新计算快照误差。
脚本的诊断阈值为正确组末八帧相对 MSE 低于 0.002、错配组误差超过正确值两倍，不作为阶段门。
当前正确组达到误差预算，但四组错配与对应正确组各有 48/48 帧完整输出相同；
实验报告 `measurement_complete=true`、`passed=false`，返回 INCOMPLETE/非零，不纳入通过的 GPU 总门。
不能据此推断 SDK 忽略曝光，也不能将直接 SDK 结果作为产品接线、模式热切换或世界内画质的通过证据。
错配参数未导致固定幅度的图像退化不构成 S4 失败；检查实际 HDR/preExposure/曝光资源责任、切换恢复和用户可见表现。

### 25.8 SDK 资源归属与状态验证

NRD、RR、RT 独立 DLSS SR、光栅 DLSS、XeSS 与 FSR 使用 native `SdkResourceScope`。
完整校验绑定后批量转态、登记 `externalResources`；SDK 返回时按契约重新声明状态，再归还引擎。
成功与失败均恢复 draw heap 并清空 PSO 缓存。各返回状态与诊断字段见
[native 设备层 §5.7](../../../MCDR/docs/device/README.md#57-sdk-资源接管与归还)。
FFM 查询为 `MCDR_ABI.D3D12QuerySdkResourceState(ctx, texture)`；仅用于与录制同步的状态检查。

工作区 `rt_check_all.py --gpu` 通过以下实际 ABI 探针验证：

| 探针 | 覆盖 |
| --- | --- |
| `rt_nrd_temporal_check.py` | 每次归还检查、四种非法绑定的无副作用拒绝、未初始化与第九在途派发的 SDK 拒绝收尾、后续双通道 compute 读回 |
| `rt_rr_temporal_check.py --case core` | 每次归还检查、四种非法绑定拒绝、主进程和独立 reset 对照的实际 RR 求值与后续读回 |
| `rt_sr_signal_check.py --backends` | 三后端可用时 39 次 SR 求值，包含四帧光栅 DLSS SDR；RT SR/XeSS/FSR 的 SDR/HDR、历史与切换；XeSS/FSR 可选 R8 mask、非零 jitter 与 MV 约定切换；22 次非法绑定、未知 flags 或未提交列表重建拒绝；后续颜色/mask compute 读回 |
| `rt_dlss_depth_check.py` | 隔离 NGX 与开发目录 Streamline，四格式往返及 R32/D32 的漏捕获、空参数、源/尺寸变化、开关与恢复，每实现 40 次、共 80 次光栅 SDK 求值；快照/当前源选择、源清零的逐像素读回与颜色 compute 读回 |
| `rt_dlss_failure_check.py` | Streamline 四次同帧冲突常量拒绝与四次临时 eOff 的 Evaluate 拒绝，验证接管前无状态变化、接管后完整归还、成功次数不误增、恢复前 compute 与新帧恢复；合计十二次成功求值、十六次 scope 归还 |
| `rt_exposure_abi_check.py` | RR/SR 显式曝光、旧入口兼容、四段资源模式切换和同队列跨 encoder 等待/超时恢复；712 次求值、348 次原子拒绝，48 份完整快照复核 |

各组探针都启用 D3D12 debug layer，要求 corruption/error 消息为空。
归还时要求 owner、尚未归还数量、violation 为 0，累计接管/归还/恢复堆次数相等。
归属检查配合 SDK 信号检查和真实后续访问使用，不能单凭跟踪计数证明 GPU 状态正确。
mask 共八次带资源求值，逐像素验证 1,843,200 个输入值保持不变；报告记录到工作区 `_tmp_s4_sr_backends.json`。
这只验证 mask 接管、切换及后续访问，不能证明动态/OIT 信号的掩码画质。
光栅 DLSS 在内部实际求值处经回调接管选定资源；独立 NGX 目录另验证四帧 SDR，覆盖 MV 中间纹理与 handover。
深度快照先将源转为 COPY_SOURCE，形状匹配后 CopyResource 复制全部子资源，再恢复源状态；
私有纹理的分配可跨帧复用，捕获有效期仅限当前帧；帧起点、捕获尝试、关闭与 shutdown 使其失效。
NGX 与 Streamline 共用选择器，只在源资源相同、尺寸与输入一致且本帧成功捕获时使用快照。
有效快照路径保持原深度状态，回退当前源时为 SRV；SDK scope/tag 使用实际选中的资源。
80 次检查覆盖单 mip/单 layer/单 sample 的格式往返、捕获有效期与状态归属；
两实现合计 18,432,000 个源深度值在 SDK 后逐像素读回为零。
不直接读回快照内部的 depth/stencil texel，不据常量颜色结果声称深度感知画质通过。
Streamline 常量提交失败在资源接管前返回，日志保留 SDK 错误码；同帧冲突常量为 `0x1B`。
实际 Evaluate 的 `eErrorInvalidIntegration`（`0x13`）在接管后拒绝；scope 仍完整归还，输出交接保持 0，成功求值次数不增加。
失败后、恢复前的 compute 检查 12,464 个 GUI 颜色样本，恢复 SDK 选项后四次新帧均成功。
tag/options 错误、SDK 部分 GPU 写入后的错误、NGX SDK 错误及 FG 失败收尾仍缺实际错误注入验证。
世界离开/分辨率切换的实机行为、光栅 HDR surface、FG、NRC、完整子资源/Split 跟踪、跨 encoder 同时求值及跨队列同步不在本组覆盖内。

## 26. 渲染倍率与上采样（S4 4-e）

`[raytracing] renderScale` 驱动 G-Buffer、重放 viewport、motion、guide 与降噪纹理的实际分配。
光追开启时，主目标及世界层附件保持窗口尺寸，普通光栅渲染比例与 SR 质量档不再缩小这些附件。
RT Plan 是光追输入尺寸的唯一来源，RR 或独立 SR 的输出直接匹配窗口尺寸。
RR 将 render 尺寸输入直接求值到独立的显示尺寸输出；`NRD_SR` 在低于 100% 时按所选 SR 后端重建显示尺寸。
选择 NONE 或独立 SR 不可用时保留 NRD 结果，由合光插值输出。

### 26.1 倍率怎么落到尺寸上

`D3D12RtPipelinePlan.calculate` 统一计算 render 尺寸，单边下限为 16 且不超过显示单边；
OFF 忽略 renderScale、按 100% 处理，其他轨道接受 25–100。
例如窗口为 2560×1334、光追倍率为 67% 时，输入为 1715×893，SR 输出为 2560×1334；
普通光栅比例保存为 50% 或 75% 均不改变这组尺寸。
`D3D12RenderScale.reconcileRayTracing()` 在光追开关变化时安排帧边界重建；
重建前世界层仍读取 `appliedPercent`，与当前主目标附件保持同一尺寸。
trace 尺寸恒等于 render 尺寸，不存在第二套追踪缩放上限。

纯 CPU 回归覆盖四种轨道、四种 SR 选择、25/50/100%、常规/奇数/极小窗口以及总开关，共 288 组计划。

### 26.2 实际资源尺寸

`recordGBufferPass` 捕获一份 Plan，按其 gbuffer 尺寸分配附件与重放 viewport，再传入 dispatch。
50% 的 854×480 主目标对应 427×240 的 G-Buffer、DXR trace、motion、NRD pack/radiance，RR 与独立 SR 均输出854×480。
Plan 变化重置相机历史与降噪签名；纹理替换后释放旧目标，成对分配失败时释放已创建的第一张。
单通道/法线调试图按目标显示尺寸生成，源纹理按自身尺寸映射。

### 26.3 合光里的上采样

`rt_resolve.hlsl` 按各自资源尺寸采样：

- 覆盖判定使用最近邻 trace 命中标记与 G-Buffer albedo alpha。
- 原始辐亮度与主射线距离使用 trace 四邻域插值；相机半径淡出只读取主射线距离。
- G-Buffer albedo/emissive 通过自身尺寸映射。
- 降噪输出通过 `GetDimensions` 独立映射坐标，不能沿用 trace 下标。REBLUR 输出还须先解码 YCoCg，见 §23.6。

低倍率下的合光插值不能替代独立 SR。块面细节、边缘、噪点与相机运动效果须在世界内验收。

### 26.4 NRD 后独立 SR

`D3D12RtSuperResolution.reconstruct` 只处理低于 100% 的 REBLUR 输出。先执行
`rt_nrd_remodulate.hlsl`：在 render 尺寸分别解码 diffuse/specular YCoCg，乘同帧 albedo 与 `max(F0,0.02)`，加 emissive，生成线性 HDR 颜色；
同一 pass 将正 viewZ 经本帧投影转换为设备深度。SR 输入禁止直接使用解调辐照度或正 viewZ。

NRD 时间累积会抑制光照信号的子像素偏移。进入 SR 前，用当前屏幕内容偏移 `(Jx,-Jy)`
在 `pixel - contentJitter` 位置重采样两路 NRD 光照，使其与当前 G-Buffer 的采样位置对齐。
`RtReblurSampleJittered` 使用四邻域双线性权重，拒绝 viewZ 差超过 `max(0.01 × centerDepth, 0.01)` 的邻点；
零偏移直接读取中心像素。albedo、F0、emissive 与设备深度保持当前像素采样，禁止连材质一起再次偏移。
重调制 b1 载荷为四个深度投影系数加两个偏移 float；SDK 的 jitter 仍使用同一份当前偏移。
该处理只用于 NRD 后独立 SR；RR 原始输入与不运行独立 SR 的 NRD 输出不经过这一步。

Plan 的 `upscaler` 来自 `SuperResolution.backend()`，选择 NONE 时不分配或派发独立 SR。
DLSS 通过 `mcdr_dx12RtSrEvalExposure` 使用 Streamline viewport 2；光栅 SR/FG 使用 viewport 0，RR 使用 viewport 1。
五张纹理依次为 render 颜色、display 输出、render 设备深度、render 像素 MV、1×1 曝光倍率。
相机复用 RR 的 104-float 载荷、无 jitter 矩阵与显式 `(Jx,-Jy)` 偏移，`depthInverted` 跟随投影；
SR 深度标签为 `kBufferTypeDepth`，RR 的 `kBufferTypeLinearDepth` 只用于 RR。

XeSS/FSR 通过 `mcdr_dx12SrEvaluate` 提交完整 `mcdr_sr_dispatch`，由现有 provider 懒建上下文。
输入为线性 HDR，`hdrInput=1`、`preExposure=1`；MV 为 render 像素真实位移、scale=1，偏移同样取 `(Jx,-Jy)`。
RT MV 已扣除当前/上一帧的 jitter 差，必须置 `flags=MCDR_SR_FLAG_UNJITTERED_MV`（1）。
FSR 对应关闭 `MOTION_VECTORS_JITTER_CANCELLATION`，XeSS 关闭 `JITTERED_MV`，避免二次补偿；
光栅入口仍使用 `flags=0`，表示 MV 包含 jitter。
这两家 provider 固定使用 reverse-Z，重调制的深度投影分子按需改为 clipW−clipZ，转换不改变颜色与 MV。
相机 near/far/FOV/aspect 与 RR 同源；可选 mask/exposure 纹理为空。
输出纹理同时声明 storage、texture binding 与 render attachment，允许 provider 在 UAV 写后恢复 RENDER_TARGET。

SDK 按实际输入/输出尺寸查询合法档位。Plan 包含后端选择，尺寸、轨道或后端变化均复位历史，求值帧号保持递增。
FSR/XeSS 的 MV 约定变化须重建上下文，FSR 的 HDR 标志变化同样重建。
内部重建先核对最后一次求值所用 encoder 的 fence 与目标值；仍在当前未提交列表时拒绝本次重建，
其他未完成使用最多等待 5 秒。拒绝发生在资源接管前，保留旧上下文；重建后的首次成功求值强制 reset。
该保护针对 provider 内部上下文重建，不能据此认定整个 provider 热切与销毁流程均已验收。
倍率在 25–100 范围内不代表 SDK 支持所有整数输入尺寸；不被接受的尺寸仍按下述失败路径输出。
输入转 NPSR、输出转 UAV；DLSS 求值后恢复 COMMON，XeSS/FSR 输出恢复 RENDER_TARGET，均恢复绘制描述符堆。失败只报一次并保留 NRD 合光；
Plan 变化允许重试。SR 成功后合光按已重调制颜色处理，禁止再次乘 albedo 或叠加 emissive；
曝光与 tone map 只执行一次。RR 成功时不叠加独立 SR，RR 回退 NRD 时允许独立 SR。

FSR 仍仅在开发侧有运行库时可用，发行 JAR 不包含 FidelityFX DLL。
后端路由、载荷与 SDK 离线检查不能代替世界内的噪点、残影与窗口缩放验收。

RT 开启时光栅 `SuperResolution.applyIfEnabled`、DLSS guide 捕获与求值提前返回。
`sr_ms` 计入重调制、设备深度转换与 SDK 求值；`trace_ms` 必须在降噪之前结束。

## 27. G-Buffer 变体与着色器链的依赖

世界里能出光追的前提是地形管线带一个 **G-Buffer 变体**：`tryMeshletDraw` 只有在
`hasGBufferVariant()` 为真时才捕获批次，`recordGBufferPass` 才有东西重放。变体缺失时主 pass 照常渲染，
但 `rt frame: dispatch` 一次都不会出现，几何池恒为 0 段，合光打一行
`rt composite: skipped, missing inputs` 就结束 —— 看上去像「整套管线没生效」。

本帧没有派发产出时合光直接返回（`no dispatched frame`）。合光输出采用主目标尺寸，
G-Buffer 与降噪输入按各自尺寸采样，允许 renderScale 使其小于主目标。

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


---
## 28. 加速结构的释放时机（S4 期间补）

换下来的加速结构缓冲与实例表**不能立刻释放**：在途帧的 `BuildRaytracingAccelerationStructure`
与 `DispatchRays` 还引用着旧地址，显存被回收后 GPU 读到的是别的数据，症状是设备移除
（`Map: HRESULT 0x887A0005`、`waitFence: TIMEOUT`），而不是一次明确的错误。

`D3D12RtAccel` 用一条延迟回收队列处理这件事：`retire(resource, buffer)` 把旧句柄压进队列，
`prepareCount` 每帧加一，攒满 `retirePrepares = 8` 帧后才真正销毁。上限定在引擎允许的在途帧数
（`kFramesInFlight = 4`，见 `MCDR\src\core\d3d12_async_executor.cppm`）的两倍：压的帧数不足以覆盖在途帧时，
症状与完全不压一样。`release()` 走 `flushRetired(force = true)` 立即清空——那时整条链已经不再提交。

各处换缓冲都要走 `retire`：

| 位置 | 换掉的东西 | 不延迟的症状 |
| --- | --- | --- |
| `ensureInstances` | `rt-tlas-instances` 上传表 | TLAS 构建读到别的数据，实例位置乱跳或直接卡死 |
| `ensureTlas` | TLAS 结果缓冲、`rt-tlas-scratch`、TLAS 的 SRV | `DispatchRays` 遍历已回收的树，设备移除 |
| `ensureScratch` | `rt-blas-scratch` | BLAS 构建写进别人的显存，随机损坏 |

世界加载期 TLAS 每帧重建一次（记录从几段涨到几百段），这条路径每次进世界都会走一遍；
段回收路径（`EVICT_FRAMES` 之后）不在此列，那时最后一次引用早已完成。

实例数一变就打一行，卡死时最后一条就是当时的规模：

```
as: tlas inst=557 bytes=131072
```

### 28.1 一帧建多少条 BLAS 也必须设上限

刚进世界时几百段同时新建：全塞进一帧，GPU 几秒交不出帧，随后被 TDR 判死（实测 1 → 144 段那一帧之后
5 秒 `waitFence: TIMEOUT`，再往后就是 `device removed`）。`D3D12RtAccel.buildsPerFrame = 16` 把每帧的
新建/重建限制在 16 条，没轮到的段下一帧继续。当前镜像的 TLAS 只在全部记录的 AS 就绪后原子发布；
待发布实例只在目标镜像、epoch 或 revision 改变时重置，不能因为它与已发布 revision 不同而逐帧重置。
同一 epoch 内从已发布快照共享版本、顶点数、索引数及索引宽度相同的已构建 BLAS；
每份快照保留独立条目元数据，共享结果缓冲使用引用计数。修改共享条目前分离所有权，禁止原地覆盖
仍供已发布 TLAS 使用的结果。取消 pending、交换发布与世界切换均只释放各自引用，最后一份引用才进入
资源退休或 native 销毁入口；两份快照的显存统计按实际结果句柄去重。
16 条预算只计真正新建或变化的 BLAS；单段变化可在本帧完成，纯删除或记录重排只重建实例表与 TLAS。
已发布的不可变快照直接复用 TLAS，不再逐帧重建。等待期间比对旧快照与当前几何池，
从发布 TLAS 移除已修改或删除 section 的旧实例，并为失效及新增 section 加入 `HITINVALID` 体积（§16.3）。
未受影响的实例继续使用配对的旧镜像；相同失效集合不重复重建。集合变化和完整快照发布递增
`sceneRevision`，使 NRD/RR/SR 的旧历史失效。发布实例逐帧推进资源退休，不依赖重复构建 TLAS。
世界切换禁止沿用旧快照；首次构建或空输入没有可用快照时直通原版。

`_port/rt_world_geometry_check.py --gpu` 检查 33 段跨三个构建帧完成、同大小内容变更、
一段增长到 128 quads、镜像缓冲换址后的 BLAS 复用，以及空场景撤销 TLAS，并读回实际 RayQuery 距离。
发布门面另检查第二份 33 段快照持续跨三帧构建、完整后交换、命中距离更新，以及静态 TLAS 不再重建。
跨快照共享检查覆盖连续三次单段编辑（含拓扑增长）、32 条未变 BLAS 地址保持、记录重排、删除与新增；
阻塞 GPU 队列时取消 17 段修改的 pending、重新构建、发布后释放全部快照，读回旧/新场景命中距离，
并核对共享资源显存不重复计费。单段编辑后另执行三次产品 DXR 派发。
失效测试同时通过真实 `D3D12RtDxr.dispatch` 验证主光线与阴影命中代理时清除样本、邻区继续出图和发布后恢复。
该检查验证几何与发布状态，不判断世界内白块、闪烁或阴影画质。

scratch 缓冲按倍数增长（`maxOf(bytes, scratchBytes * 2, MIN_BYTES)`）：逐条 `ensureScratch` 时若每遇到
更大的段就重分配，单帧几十次分配同样会把显存顶满。

一帧建到 8 条以上会打一行峰值：

```
as: blas burst b=16 peak=812us ms=23
```

新段的 `D3D12GetBlasPrebuildInfo` 也受同一个上限约束（要跟驱动打交道，连着问几百次一样会卡住这一帧）。

加速结构与追踪两段的 CPU 编码时长超过 200 ms 时打一行分解，卡死前最后一条「慢帧」直接指出钱花在哪：

```
rt frame: slow accel=1830ms trace=12ms records=144 inst=144
```

### 28.2 FFM 描述符必须与头文件逐项一致

`MCDR_ABI.kt` 里每个 `skipOptional("name", fd(ret, params...))` 的第 0 项是返回类型，其余逐项对应
`MCDR/include/mcdr_abi.h` 的形参。`invokeExact` 做精确类型匹配，差一个参数就抛
`WrongMethodTypeException`；包装层把它折成 `false` / `-1`，表现是功能安静地不执行。

`_port/ffm_signature_check.py` 逐项对拍两者，正常输出 `ffm signatures: 67 binding(s), failed=0`。
改 ABI 时先跑它，再进游戏。


## 28.3 诊断视图：先看覆盖率，再看辐亮度

`[debug] debugView` 的全部取值在 `IllusionConfig.RtDebugView` 里；排查「画面看着像原版」用其中这四档，按下面的顺序看：

| 视图 | 看到什么 | 结论 |
| --- | --- | --- |
| `COVERAGE` | 白 = G-Buffer 写过，黑 = 没写过 | 大片黑说明重放没覆盖屏幕，追踪自然没有输入 |
| `HIT_DISTANCE` | 灰阶 = 命中距离（64 格归一化），黑 = 打空 | 全黑且覆盖率正常说明射线起点或方向不对 |
| `DIRECT` | 只有追踪出来的辐亮度 | 有值而普通视图像原版，问题在合光之后的上屏 |
| `INDIRECT` | 只有间接项（天空/环境入射） | 直射有、间接没有时画面会显得平，靠它区分缺哪一项 |

G-Buffer pass 的视口原点恒为 `(0, 0)`：目标是我们自建的离屏纹理，把主 pass 的 `areaX/areaY` 搬过来会让光栅整体偏移、只画进一块，看上去就是「世界只剩左上角一块」。重放规模每 5 秒打一条 `rt gbuffer: replay batches=<n> meshlets=<n>`（首帧必打一次），用稳态数字判断覆盖是否正常。

进世界实测过的三档（沙漠平地 + 一块方块，`tier=dxr`、`denoise=1`）：

| 视图 | 实测 | 判定 |
| --- | --- | --- |
| `COVERAGE` | 地面与方块是白的，天空是黑的 | 重放覆盖了所有几何，只有天空按定义没覆盖 —— 「进世界看不见」不是覆盖率问题 |
| `COMPOSITE_MARK` | 整屏品红 | 屏幕上的像素确实由合光写出来，上屏这一段通 |
| `SHADOW` | 所有几何同色 | 这一档读 `aux.y`（太阳可见性，见 §9.4）；同色说明太阳可见性全场一致，查阴影要先确认太阳方向与阴影射线的起点偏移 |

`[debug] debugView` 可以**在游戏内热切换**：改 `config/dxr_mc-client.toml` 后文件监视线程会在几秒内把新值装进 `D3D12Settings`，不必重启；同一次运行里来回切 `NONE` / `COVERAGE` / `SHADOW` 即可对比。

取证用 `PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT)` 直接抓窗口，不需要把窗口切到前台：抓之前先确认 `rt frame: dispatch …` 心跳还在走（5 秒一条），心跳停了说明画面已经不再更新，抓到的就是旧帧。

合光成功时打一行 `rt composite: <w>x<h> mode=<n> denoise=<0|1>`；跳过时打 `rt composite: skipped, <原因>`。有前者而屏幕没变，就查上屏，不再查追踪。

`DIRECT` 与 `HIT_DISTANCE` 全黑、`SHADOW` 全场同色而 `COVERAGE` 正常，说明射线一条都没命中：合光对每个像素都走了直通分支（`rt_resolve.hlsl` 里 `direct.w < 0.5 || albedo.a < 0.5`）。此时先用一次「强制命中」判据分辨是追踪没写还是合光没读——把 raygen 末尾的 `direct` / `hitFlag` 换成常量（`direct = float3(0.35, 0.0, 0.35); hitFlag = 1.0;`）后重进世界：

- 整片几何被染上这个常量：追踪写进去了，合光读的也是同一张缓冲，问题在射线或加速结构一侧；
- 画面仍是原版：合光读的不是追踪写的那张缓冲。

这条判据用完必须删掉，否则自检会以 `rt trace: dxr triangle paths maxDiff=1.0` 失败——那条失败本身就是判据生效的证据。确认射线打空后，用两条向下探针二分「几何到底落在哪」：一条从 `g_cameraPos` 向下、一条从 `RtLoadRecord(0, …)` 的段原点向下，命中结果分别写进 R/G 通道。两条都打空且覆盖率正常时，先查实例表的 BLAS 地址与实例变换，再查 `TraceRay` 实际绑定的 AS 视图。
