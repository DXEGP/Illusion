# OIT RGB history GPU check

`run.ps1` 从 native 的 `mcdr_oit_shader.h` 和 `mcdr_oit_resolve_shader.h` 提取实际 HLSL，使用 DXC 编译 SM 6.9，选择高性能适配器并用 Agility 619 创建设备。在独立 3×3 RGBA16_FLOAT 纹理上执行 compact 和 RGB resolve，每像素两份 surface records；读回仅几十字节的结果，不启动 Minecraft，也不读取游戏画面。

运行 `./src/probes/oit_history/run.ps1`；`-NativeRoot` 指定 native 源码目录，`-RuntimeDirectory` 指定含 `D3D12Core.dll` 的目录。输出在 `build/reports/workgraph/oit-history/`，脚本与源码不进入发布产物。

检查当前透明权重、sampleAlpha、fog、chunkVisibility 对 RGB 校正的实际数值，历史帧故意使用不同的权重；同时验证无 presented 输入、unknown、overflow、空贡献与 owner 替换保持当前颜色，以及移动后的历史查询使用旧屏幕位置。alpha 始终保持当前输入。半精度输出按数值误差 0.0002 比较。shader ABI 4 使用 144 B record 和 8 B packed pixel，额外从实际 Initialize 与 Append 并发写入同一个像素，检查 32 条有界链、unknown/overflow 标志共存、其他像素清零，以及 capture 字段与 consumer 偏移一致。

这个程序仅验证生产 compute shader 的 GPU 运算与布局。生产 capture metadata、Present 票据接线、pending 清理与动态视觉仍须分别确认，脚本不模拟这些生命周期。
