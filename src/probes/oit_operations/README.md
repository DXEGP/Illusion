# OIT operation receipts

该独立程序使用正式 ABI 25 创建两个 64×64 Vanilla/OIT 帧，各提交三次真实三角形 draw。它检查真实 receipt 的原始 ID、帧身份、nativeSequence、packet/read-set ID、PSO 与 command ordinal，并覆盖重复 append、伪造身份、跨帧 receipt、遗漏 receipt 的错误报告。原有四项缺帧检查仍保留。

graphics pipeline 接收入口为 `main` 的 HLSL 源码，由实际生产编译器生成设备对应的 DXIL。附件在 render pass 结束后回到 COMMON；提交入口负责关闭当前 command list。显示帧通过公开 begin/abort 入口建立和退休，不改写生产内部状态。

第一帧故意漏掉第三份 receipt，seal 必须返回 `GAP_RECEIPT` 和该 receipt 的原始 ID。第二帧提交全部 receipt，必须消除这一缺项。两帧都按逆 nativeSequence 追加前两份 receipt，让 seal 处理实际 native 顺序；公开 ABI 没有合并命令表查询，程序不声称直接验证 terrain 与 operation 合并后的排序表。

V2 query、manifest 和 seal 的 `expected` / `observed` 保持 terrain-only，本例均为零。当前执行器尚未支持完整 replay，seal 应返回 unavailable，完整 adapter/content 路径仍有缺项。程序会输出实际 contentState / adapterState，不把 recorded receipt 当成 replay 已支持。

draw 后请求销毁 PSO 与目标纹理，再提交并等待真实 fence；opaque 输入保留到 fence 完成。每帧使用独立 encoder，完成后销毁；第二帧拒绝前一帧、前一 context 的 receipt。程序不覆盖同一 context 的完整显示帧复用，也不做图像读回或写盘。

在项目根目录用 Windows PowerShell 运行：

```powershell
./src/probes/oit_operations/run.ps1 -NativeRoot D:/Projects/MinecraftMod/DXRMC/rebuild/MCDR
```

加 `-BuildOnly` 只编译链接。运行要求新 ABI 25 DLL/import library，构建脚本会记录所用 DLL 的 SHA256。
