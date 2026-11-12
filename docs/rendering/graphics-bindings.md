# 图形绑定与 root constants

图形管线的 push constants 使用 `b0,space1`，普通资源使用 `space0`。Naga 输出 block 大小、成员偏移和类型；JVM 校验 vertex/fragment 布局及 pipeline 声明后创建 native 根签名。单块上限 128 字节，大小须为 4 的倍数；native 同时检查整个根签名不超过 64 DWORD。Mesh 变体追加常量参数，不改变 b8/b9 和 t14–t16 的既有参数位置。

`pushConstants(ByteBuffer)` 精确复制 remaining 字节，不移动调用方 position/limit，不解释字节序。切换到不同布局时撤销旧快照，缺少、过短或过长的数据明确失败。兼容的 flipY、宽线和 mesh 变体共享逻辑 pass 的常量；draw 前重放。

Naga immediate block 当前支持标量、向量和结构体成员；数组由 Naga validator 明确拒绝。预处理不得给 `layout(push_constant)` 添加普通 UBO binding。vertex/fragment 声明当前要求完整布局相同，只有一阶段声明时以该布局为准。

Work Graph、compute、外部 SDK 改写命令列表后，恢复 draw heap 并撤销 PSO/SRV 缓存。draw 前 `EnsureGraphicsState` 恢复所选 pipeline；资源绑定及常量随恢复重新写入。此检查增加一次 FFM 调用，实际开销纳入场景测量；不以未测量的估计宣称性能收益。

验证包括实际 b0/space1 红/绿像素输出、插入 compute 后恢复图形状态、错误长度与上限拒绝，以及 Naga 声明、成员布局、寄存器冲突测试。地形生产 WG/间接 MS 探针在新根签名实现上重新通过；含自定义 vertex push constants 的 terrain WG 语义尚未验证。
