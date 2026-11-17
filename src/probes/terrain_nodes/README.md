# 地形节点融合 GPU 检查

运行 `./src/probes/terrain_nodes/run.ps1 -NativeRoot D:/Projects/MinecraftMod/DXRMC/rebuild/MCDR`。脚本从指定 native 目录读取 Release DLL、导入库与 ABI header，使用 `run/dxrmc` 的 Agility/DXC runtime；`-RuntimeDirectory` 可覆盖。只执行独立进程中的小缓冲区工作图，不启动或操作游戏，不读取画面。`-BuildOnly` 只编译 C++，HLSL 的生产 DXC 编译由实际运行执行。

程序读取当前生产五节点 `terrain_workgraph.hlsl` 与本目录三节点 `fused-candidate.hlsl`，仅补上固定相机、单位矩阵和背面剔除宏；不改图节点。融合候选因单列表连续派发长尾退出正式资源，保留独立源便于核对既有证据，检查不依赖忽略目录里的旧副本。通过真实 C ABI 创建 state object、上传候选与 atlas、DispatchGraph、等待 fence 并读回 visible。旧新图分别编译背面剔除开/关两种变体，各执行 20 个场景：0/1/63/64/65/129 项、整组剔除、混合方向/非轴向/无界、runtime cull 关闭、远处 meshlet/section、多 section、容量恰满和溢出。比较实际计数、overflow bit 0、原样 section 表、无序 work 集合、重复项、未写区与末尾 canary。溢出时各自保留的前缀允许不同，但每项必须属于预期集合、无重复且不越界。

另外用独立的 64 线程 broadcasting WG 报告实际运行的 `WaveGetLaneCount()`。只给离线编译副本的 `MeshletRange` 增加 `WaveSize(32)`/`WaveSize(64)`，分别编译；不创建或派发这两份强制波宽变体。生产 Initialize/Finalize 的 compute 入口也编译，**没有执行其 GPU 逻辑**：通用 compute ABI 使用 texture u0/b0，地形专用 ABI 的 visible/arguments 输出为私有缓冲区。此检查覆盖图写出的 overflow 和 count，未覆盖最终 draw slice 清零及 terrain 专用调用链。

计时复用真实 query pool ABI，在同一录制里交替旧新次序，各 16 次。三个输入分别是 16×4096 全可见、16×4096 每 64 项仅存活 1 项、1024×1 小 section；候选、atlas、容量相同。timestamp 范围含 DispatchGraph 与既有 UAV barrier，初始化 copy 在计时前，结果读回在 fence 完成后。每图先做一次不计时 warmup，报告保留每次原始值、最大值和中位数。计时材料保存在 `timings.csv`，20261115-161129 的新图在同次录制的 round7/14 出现规律性离群，原因尚未定位，应结合原始样本判断。游戏内总体速度、动态观感与其他 GPU 的波宽需分别确认。

每次输出位于 `build/reports/workgraph/terrain-nodes/<时间>/`，包含构建与 GPU 日志、DLL/source SHA-256、实际送给 DXC 的 HLSL、DXIL 和原始计时。程序只在 fence 成功等待、context 销毁后释放查询池；等待失败交由进程退出保留资源。

使用 `-PairsOnly` 可运行针对性对照：每轮只录制一对旧/新 dispatch，提交并等待 fence，再开始下一轮；保留 `pair-submission-lifecycle.csv` 的录制序号、query 和完成 fence。该模式只重复 dense/sparse 两组，不覆盖完整正确性矩阵。
