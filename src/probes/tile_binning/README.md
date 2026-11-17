# Tile prefix GPU 检查

在独立 D3D12 设备上运行当前生产 `tile_binning.hlsl` 的 `PrefixTileRefsV1`，使用生产的单组 dispatch 和 space3 根签名布局。脚本拼接实际 `tile_route.hlsl`、`tile_envelope.hlsl`、`tile_binning.hlsl`，用 DXC 编译全部 compute/node 入口；没有另写 prefix shader。程序和脚本不进入发布产物。

运行 `./src/probes/tile_binning/run.ps1`。`-RuntimeDirectory` 指定含 Agility 619 `D3D12Core.dll` 的目录，`-DxcPath` 指定支持 SM 6.9 的 DXC，`-BuildOnly` 只编译。输出保存到 `build/reports/workgraph/tile-binning/`，同时记录运行库、编译器及源文件 SHA256。

26 个用例逐项对比 GPU prefix 与 CPU 串行计数，覆盖 0、1、3、4、5、63、64、65、1023、1024、1025、4095、4096 项，混合 mask、全 16 位、全零、仅尾项、indexed/mesh stride、已有错误、无效 schema/flags/resident count/primitive count/stride/capacity，以及 32 位 mask 引发的容量溢出。检查 prefix 首尾保护字、所有未使用项、总数、错误字段和两个保留控制字；容量不足时保留原语义，prefix 已写入、control 变为 `(0, 8)`。

程序批量录制这些小 buffer，等待一次 fence 后共读回约 417 KiB。它不启动游戏、不截图、不读取纹理，不测 Work Graph 分类、scatter、生产生命周期或性能。
