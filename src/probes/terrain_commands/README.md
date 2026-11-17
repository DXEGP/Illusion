# 地形间接命令分段检查

执行 `./src/probes/terrain_commands/run.ps1`。脚本使用已有 GraalVM 25.1、缓存的 Kotlin 2.5 RC 和项目编译输出，不下载依赖；结果写入 `build/reports/workgraph/terrain-command-ranges/`。

原版按 `maxDrawIndirectDrawCount` 将同一个已上传 command buffer 拆为多个连续区间，每条 `IndexedDraw` 占 20 字节。检查通过真实 `captureDraw → captureBatches → batch` 调用验证完整批次、头/中/尾及嵌套分段的原始顺序、几何事实、局部 primitive 编号、缓存复用、无效区间拒绝和帧清理。只替代 native buffer 的分配/释放边界，TLSF 分配与地形采集使用生产实现，不读取或修改被测对象的内部状态。

脚本先将受影响的生产源码与检查程序编译到独立目录，再运行检查。它不加载 native DLL、不运行 GPU、不启动游戏；GPU 可见表、间接绘制和资源 fence 行为由对应的独立检查负责。
