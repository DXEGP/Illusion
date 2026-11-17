# 独立行为探针

这里的代码只由显式调用的脚本或独立 source set 编译，游戏启动、tick、资源重载和生产 JAR 不加载这些类。

新增探针必须对应明确的 ABI、GPU 资源寿命或硬件行为问题，并说明它能证明的行为。已有探针按实际改动选用，不要求每次修改全部重跑。

- `compilation/run.ps1`：编译缓存键、失败重试、容量、版本撤销和预热产物清理。
- `terrain_atlas/run.ps1`：图集扩容、提交后退休和失败替换的资源所有权。
- `vanilla_scheduler/main.cpp`：真实 scheduler header 的重复读取前沿、分离 mip 依赖、RAW/WAR/WAW、UAV 顺序、版本与 plan 复用；独立编译方式见同目录 README。
- `workgraph_reuse/run.ps1`：真实 C ABI/GPU 的未提交初始化撤销、End/Begin 同 fence 序号复用、同录制重复 dispatch、两个在途 context 与提交前销毁 program；不启动游戏或采集画面。
- `dlaa_replay/run.ps1`：通过真实 MCDR/Streamline 重放已采集的颜色、深度与运动；`-Preset 10/11/12/13` 分别选择 J/K/L/M，默认 11。`-Mode Sequence` 连续重放，`SequenceResetHistory` 逐帧重置历史，`SequenceLinearColor` 使用线性 RGBA32_FLOAT 输入/输出，`SequencePreparedLdr` 提供相同 prepared 路径的 RGBA8 控制组。脚本及报告保留采样、模型覆盖和 Present 条件，不代替游戏内动态确认。

测试替身、故障注入、断言脚本与验证用计数器放在本目录；生产源码保留真实运行需要的能力查询、资源状态和错误处理。不要为测试增加业务开关、启动自检、tick 钩子或虚构后端。

源码编译、探针结果、客户端运行和 PIX/Nsight 性能记录分别报告。编译通过不能代替游戏运行，探针通过不能代替 SDK 硬件验证。
