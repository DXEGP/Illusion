# 重建分支协作规则

本分支从 main 建立，承担集中式渲染器的 JVM 接线；当前源码尚未包含新渲染器。
JVM 实现和 mixin 均优先使用 Kotlin，只有实际工具链无法支持的注入点使用最小 Java 适配。
main 中的现有 Java mixin 是待选择迁移的基线，不代表新功能必须采用 Java。

统一包名为 `io.github.dxegp`，包迁移同步处理 mixin 配置、反射字符串、资源与日志入口。
native 调用只用 FFM；JVM 提交世界增量与帧输入，不管理 SDK 各 pass 的执行和历史。
新重建契约见 `docs/rendering/README.md`，文档入口为 `docs/overview.md`。
按 scene/material/frame/config 封装采集业务；mixin 只转交事实，MCDR_ABI 只承担唯一 FFM 出口。
模块传递版本化描述、不可变增量和资源租约，不共享可变 manager，不泄漏 SDK/GPU 内部布局。
后台产物检查 worldEpoch/版本；配置成功发布后才标记生效，错误/取消/关闭遵循同一生命周期契约。
不要把来源分支的整套 RT frame、全量几何镜像和回补注册表移入本分支。

当前源码已按 `io.github.dxegp` 的业务目录归类，当前模块地图见 `docs/overview.md`。
MCDR_ABI 保持唯一 FFM 出口；NativeLibraryResources 只解包资源且不得反向初始化 ABI。
启动路径不运行行为自检；独立行为探针位于 `src/probes/`。只为明确的 ABI、资源生命周期或硬件行为缺口添加探针，不为重复覆盖 getter、分支或形式化防御增加测试；运行时能力查询和降级属于生产行为。ReconstructionTiming 拥有超分查询池与采样状态。
拖影、闪烁、运动稳定性等动态视觉确认交给用户；代理提供具体场景和观察项，不以静态截图替代动态结论，不反复截图或自动操作游戏尝试证明画质。
连续全图读回和写盘会严重降帧，不用于用户操作时的动态确认；已有采集数据优先离线分析。
计时池仅在 command context 完成并销毁后释放，停用超分只重置采样。
计时池使用前向唯一 encoder 实例登记 AutoCloseable；device 不反向引用重建业务。
共享与一次性 encoder 都在成功等待 fence、销毁 context 后关闭登记资源，超时不释放。
Kotlin 的内联边界与 FFM 大小、对齐、空数组及调用作用域统一遵循 `docs/misc/ffm-abi.md`；跨异步提交的内存必须由真实资源 owner 和 fence 持有。
