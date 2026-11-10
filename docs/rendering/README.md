# 集中渲染器接线契约

这是新渲染器的目标接口设计，尚未实现。当前源码提供 D3D12/Blaze3D 基础；本页不将设计描述为已可用功能。

## 1. JVM 的职责

JVM 用 Kotlin 采集世界、方块模型、材质、实体和相机输入，经 FFM 提交到 native RenderPipeline。
mixin 同样优先使用 Kotlin；只有具体注入点经编译与织入验证无法支持时使用最小 Java 适配。
输入生产者只报告事实和变化，不负责几何驻留排序、NRD、超分、历史缓冲或 Present。

### 1.1 业务封装

JVM 的目标业务目录为 `io.github.dxegp.render` 下的 scene、material、frame、config；以下是目标职责，尚未完成源码迁移。

| 业务 | 入口与输出 | 边界 |
| --- | --- | --- |
| scene / SceneBridge | 区块、模型、实体事件 → 带身份/版本的不可变增量 | 不排序 GPU 驻留，不向工作线程暴露存活 Minecraft 对象 |
| material / 资源导入 | 资源包/动画/重载 → 原始资产与语义材质描述 | 保留通道/颜色空间/来源；不预先压成 SDK 私有布局 |
| frame / 帧采集 | 相机、视点、时间、UI → FrameInputs 与帧资源租约 | 应用帧与生成帧分开，不分别生成多套 jitter/曝光状态 |
| config / 配置适配 | IllusionConfig → D3D12Settings.Snapshot → 版本化 RenderProfile | 不增设配置存储；只提交意图与明确组合，不分散调用各 SDK |
| frame / native 会话 | 创建、提交、执行、重配、关闭 → 输出/错误/统计 | 所有 downcall 经过唯一 MCDR_ABI，不暴露 provider 原始句柄 |

mixin 只采集必要事实并转交业务入口；材质解码、全局注册表、驻留管理和 SDK 状态机不得放进注入处理器。
采集在允许访问 Minecraft 对象的线程完成，后台任务只读取冻结值；产物携带 worldEpoch、内容/配置版本并在提交时拒绝过期结果。
业务输入不可变不等于每帧复制整个世界，按稳定 ID 和变更集维护。跨模块只传描述/句柄，不共享可变 manager 或全局服务定位器。

### 1.2 输入语义

| 输入 | 内容与失效 |
| --- | --- |
| 世界页 | section/region ID、块状态、完整模型分类、逐面材质、内容版本；加载/编辑/卸载发增量 |
| 复杂几何 | 模型顶点、索引、材质/alpha、实例与 previous transform；动态变形更新对应版本 |
| 材质 | labPBR `_s/_n`、albedo、动画帧、atlas/UV/tint、光谱材质元数据；资源重载统一撤销 |
| 相机/帧 | worldEpoch、frame ID、原点、current/previous 矩阵、jitter、输入/显示尺寸、时间和曝光 |

块数据提交简单实心分类和逐面语义，由 native 形成 DDA 页；复杂块和实体提交几何事实，由 native 构建 BVH。
远景生产者提交足以重建的几何中间数据、材质映射、误差界与来源版本，native 生成 Mega Geometry 内容；不把 cluster/AS 字节作为唯一记录。
不能由可见 draw list 推断整个世界为空。简单实心块在精确域内所有距离都使用 DDA，不附加距离阈值。

## 2. 世界渲染与输出

native RenderPipeline 统一组装调度；场景、几何、材质、大气、输运、重建和呈现模块分别封装业务状态。
重建模式固定为 `NRD + DLSS SR / FSR SR / XeSS SR` 或 `DLSS RR`；RR 不重复经过 NRD/另一家 SR。
pipeline 返回显示分辨率线性 HDR 世界颜色、guide/透明度语义和完成 fence，统一呈现模块再处理 UI/FG/Present。
UI 不经过世界 jitter、降噪和超分；各配置页不能分别偷偷启用多条后处理链。
表面/透射/体积/Kerr 的 guides 和历史在 native 按信号定义，JVM 不将它们预先压成唯一首表面深度或运动。

## 3. 配置与 ABI

配置定义、Snapshot 与调用点同步；功能通过 NeoForge 配置和相应 UI 暴露，不增加环境变量入口。
优先利用 SM6.9、WG 与 CoopVec，实际 shader/设备/运行库要求由所选模块聚合；SM6.9 不作为所有入口的无条件标签。
所选 profile 缺必需能力或属于未支持组合时明确拒绝启用，不静默改为旧渲染器或另一家 provider。
模型/纹理缓存属于资产，不通过文件是否存在充当功能开关。

Render ABI 包含 pipeline create/destroy、SceneDelta、PipelineDescription、FrameInputs、FrameOutput 和统计。
结构布局由版本化 schema 定义，两端验证 size/version；异步使用的 Java 数据必须复制或显式保活。
扩展记录带类型/大小，未知必需扩展报错；SDK 结构、GPU 地址、descriptor/cluster 索引不进入公共场景描述。
重配先准备候选图，在帧边界提交成功才更新生效版本；失败明确保留原生效状态，不把旧图当作新配置已成功。
切世界/退出停止提交并取消未发布任务，native 依据实际消费 fence 释放资源，JVM 按租约关闭 Arena。
代码按 `io.github.dxegp` 统一命名，旧包迁移同步更新 mixin 清单和反射路径。

## 4. 复用范围

复用 D3D12 基础、资源包读取、PBR 图集映射、实际模型/实体采集与适用 Kotlin mixin。
PBR 必须按 texel 读取规范通道，材质动画不能只截取首帧；geometry/material/model 版本分别明确。
不继承分散的 RT 合光 hook、全量 mirror/source inventory 调度和 provider 私有的全局帧状态。
实现按模块依赖组织，不为每个接口另建最小出图阶段。
NRD/MG/神经模型的内部升级不应修改采集接口；公共语义变更时同步更新 schema、消费者与迁移检查。
记录采集/快照/提交/等待时间和带版本的增量回放；ABI、生命周期、过期任务拒绝与依赖边界检查随业务实现。
