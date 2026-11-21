# 抗锯齿：TAA / SMAA / 后处理锐化

AA 与 SR 在世界层完成，随后才绘制第一人称手部、晚期效果和独立 GUI。配置统一遵循 [渲染配置](../rendering/render-configuration.md)，世界输入与 provider mask 见 [超分总览](../superresolution/README.md)。

## 1. 世界输出顺序

`VanillaWorldOutput` 记录 velocity、取得世界深度并准备资源绑定；native `prepareWorldOutput` 统一决定本帧 consumer，录制选中的 SMAA/TAA/SR、曝光和 mask，发布 PreparedFrame 后评价并输出 SceneDisplay。JVM 的 AA/SR 门面不再逐帧执行各 pass。

世界输出发生在 LevelRenderer 和 AfterLevel 完成后、第一人称与晚期效果之前。缺少相容 previous 时保留当前世界颜色；已经选择的能力缺失必需资源时沿明确错误通道报告。失败帧撤销候选历史，资源继续由所属命令和 fence 租约保活。

## 2. 模式与配置

| 值 | 模式 | 世界输出行为 |
| --- | --- | --- |
| 0 | OFF | 不单独执行 AA |
| 1 | SMAA | 原生三阶段空间 AA |
| 2 | TAA | 原生时域 resolve 与历史管理 |
| 3 | DLAA | 选择 DLSS provider、渲染比例 100% |

选项键为 `dxrMcAaMode`。画质预设 fast/fancy/fabulous 分别请求 OFF/SMAA/TAA；DLSS 生效期间 AA 选择由统一配置规则约束。DLAA、DLSS-G 和普通 SR 的互斥/比例规则集中在 RenderConfiguration：DLAA 选择 DLSS，DLSS-G 要求 DLSS；TAA 与任何时间性 SR 不重复启用，DLSS 使用其自带 AA。

Option 变化只更新 requested。候选的 FG Prepare、SR Prepare 和组合 Commit 成功后发布 applied 并同步 Option/UI；完整 Published 才更新 persistent，save 通过 settingsToSave 读取它。同尺寸准备失败恢复原生效值。主菜单等待世界通过 AwaitingWorld 结束 UI 回调并保留 pending；resize 尚未 ready 时先发布新尺寸临时 NONE 配置，保持 persistent，同尺寸 SDK 拒绝后暂停自动重试，见配置契约。没有跨门面锁 setter、每帧重推 AA 闸门或从 setter 直接 resize 的路径。

## 3. 时间性重投影

### 3.1 抖动序列

时间性抗锯齿的第一块拼图是亚像素抖动：每帧把投影稍微偏一点点，让同一像素在不同帧采到不同的
子像素位置，再由历史混合把这些样本攒成更高的有效分辨率。

- 序列：Halton(2, 3)，两轴分别取 base 2 与 base 3 的 radical inverse，值域 `(0,1)`，减 `0.5`
  之后变成以 0 为中心的像素偏移，范围 `[-0.5, +0.5)`。
- 长度：`DEFAULT_JITTER_SAMPLES = 16`；实际相位数使用成功发布的 `jitterSamples`。Halton 表惰性生成并
  缓存，容量取 `max(jitterSamples, 16)`。
- 取第几个：`index = frameIndex % jitterSamples`，`frameIndex` 每个真实世界帧加一，与速度缓冲的乒乓、TAA 的帧号
  同一个节拍（都在 `D3D12Temporal.beginFrame()` 里推进）。

注入位置是 vanilla 的关卡投影矩阵 UBO：

- buffer label：`"Camera projection matrix UBO level"`（64 字节）；
- `clipJitterX = 2 * jitterX / width`，`clipJitterY = 2 * jitterY / height`；
- 对实际投影左乘 clip-space 平移：`Pj = Tclip(clipJitterX, clipJitterY) * P`。
  列主序四列都执行 `row0 += clipJitterX * row3`、`row1 += clipJitterY * row3`，深度行与齐次行保持原值，输入矩阵也保留原值。

这样 `clip'.xy = clip.xy + clipJitter.xy * clip.w`，透视除法后始终平移同一份 NDC 偏移。
原版实际上传的世界投影已经包含受伤、走路摇晃和恶心效果的变换；其 `clip.w` 可能依赖多个输入分量。
旧的仅改元素 8/9 的做法只适用于 `clip.w = -view.z`，这些效果会让实际偏移随顶点位置变化。
标准透视矩阵下，新公式自然化为原来的元素 8/9 减法。实际上传、velocity 逆矩阵和 VRS 重投影共用 `D3D12Temporal.jitterProjection`。

只对 `level` 注入。`"Camera projection matrix UBO 3d hud"` 用于第一人称手部，
`"Camera projection matrix UBO gui"` 用于正交 GUI；两者有独立上传路径，均不参与世界 jitter。


实际时域开关、jitter 开关与相位数随 applied 配置发布；相位只按真实世界帧推进，生成帧和重复 Present 不推进世界历史。只有实际 TAA/SR 消费者才使用世界 jitter；手部与 GUI 继续使用独立投影。FSR/XeSS 的相位数按 native 对真实尺寸的查询发布。

### 3.2 历史与 reset

TAA 私有参数、历史双纹理、resolve/copyback 和资源寿命由 native 拥有。冻结帧带当前/上一 jitter、historyGeneration、blend 和 sharpness；TAA 混合当前帧权重为 `0.0625`。缺少相容 previous 或本帧 motion 时不评价时域历史。

重建历史在成功评价后才提交。世界、config/resource 版本、historyGeneration、真实帧连续性和相机跳变参与有效性判断；相机单帧位移超过 64 格或转角超过 90°时 reset。JVM 不再另存 ReconstructionHistory，也不以 DLSS 会话退出作为唯一 reset 来源。

### 3.3 速度与逐物体来源

速度纹理为 `RG16_FLOAT`，表示去 jitter 的 current→previous 像素位移。深度采用 reversed D3D [0,1]；从抖动后的实际 raster 投影反投影，相机相对原点差以 double 计算后再进入 GPU 参数，SDK jitter 使用 `(jx,-jy)`。

普通实体、第三人称普通持物和已知 opaque particle 可从真实生命周期、模型/拓扑以及 previous 顶点生成 motion guide。额外 MRT 保持原 shader 的覆盖、discard、深度与插值；只有与最终深度相容的有效来源参与显示 guide。AABB 平移近似继续服务其原有重建用途，不能据此把 FG 标记为完整运动。

来源缺少 previous、有效逐像素运动或 post 映射时跳过本帧 FG，普通帧保持绘制。天空/天体、实际像素贡献、透明与 custom 各有独立支持边界。

## 4. 世界、手部和 GUI

SceneDisplay 承接 AA/SR 后的世界，再依原版顺序接入第一人称、screen effects、outline 和 post。手部有实际 HUD projection/model-view、独立 depth/motion/coverage，世界与手部 guide 在晚期显示工作图统一到显示相机。

UiDisplay 始终使用显示尺寸。世界分支显式清成 `(0,0,0,0)`，预乘 alpha 合成到 SceneDisplay，得到完整 composite。100% 比例、DLAA、Native AA 和关闭 SR 都沿用这些独立角色。GUI 不进入世界历史，也不作为 DLSS 的直接输出目标。

显示帧已接入 GUI 前 Prepare、GUI 后 Submit：前者冻结场景输入并提前提交 guides，后者只补 UI/backdrop、等待准确 guide fence 并回读同帧有效性，通过后才接受 SDK，具体契约见 [FFM ABI](../misc/ffm-abi.md)。当帧 provider 已接受且 UI 注册成功时才允许 scene-only 呈现，其余使用完整 composite。blur 导致 GUI 已经包含场景背景时跳过 FG，避免重复合成。普通 F2 和 Tracy 读取完成后的完整显示目标。

目标创建/更新、UI 注册清除、world/no-world 切换和关闭失败都保留相应 owner 并传播错误。世界尺寸由配置候选发布，不在 GUI 失败路径临时强制比例、直接破坏性 resize。

## 5. 后处理锐化

DLSS/DLAA 后置锐化仍在 native 世界输出中执行：SR 输出是 WorldOutput 拥有的独立显示尺寸纹理，`prepareWorldOutput` 将它锐化写入 SceneDisplay；强度为 0 时普通 blit。无需 DLSSOutput 或额外 JVM scratch owner。

TAA 锐化在 native resolve 的回填阶段执行；历史保存未锐化颜色，避免递归累积。DLSS 的 `[renderer] sharpenAmount` 默认 `0.67`，TAA 的 `taaSharpenAmount` 默认 `0.816`。两者分别配置，不能依赖 SDK 已弃用的锐化参数。

所有锐化读写目标必须独立，禁止 `src == dst`；输出与历史租约都必须覆盖实际 GPU 读取。资源仅在所属 context 成功完成并销毁后释放，超时保留。

## 6. 验证范围

FG 同帧 validity 等待/回读暂保留，其性能没有实测；拖影、闪烁和运动稳定性由用户实际观察。

## 7. 文件导航

路径相对 `src/main/kotlin/io/github/dxegp/`。

| 文件 | 职责 |
| --- | --- |
| `render/antialiasing/D3D12AntiAliasing.kt` | AA 选项、预设映射与配置请求 |
| `render/antialiasing/D3D12Taa.kt` | TAA 选择和 blend 值 |
| `render/config/RenderConfiguration.kt` | 配置规则与 applied 发布 |
| `render/frame/vanilla/VanillaWorldOutput.kt` | 世界输出资源与事实 |
| `render/temporal/D3D12Temporal.kt` | jitter 和冻结相机 |
| `render/temporal/D3D12Velocity.kt` | 实际世界 motion |
| `render/presentation/D3D12SceneDisplay.kt` | 世界输出及晚期场景 |
| `render/presentation/D3D12GUISplit.kt` | UiDisplay、composite 与截图 |
| `render/presentation/display/` | 晚期显示输入与 FG 提交 |

native 对应 `src/render/antialiasing/`、`src/render/vanilla/d3d12_vanilla_frame.cpp` 和 `src/render/presentation/d3d12_display_frame.cpp`。
