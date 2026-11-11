# 着色器编译与资源接口

当前原版 GLSL 固定使用 `资源源代码 → 宏/include → 方言归一 → naga IR → HLSL/manifest → DXC → DXIL → PSO`。
`illusion_shader.dll` 是必需的模组资源；缺失、ABI 不符或转换失败直接报告错误。宿主不使用 shaderc/SPIRV-Cross 编译或自动重试旧链。
当前入口服务原版 `RenderPipeline`，不代表已实现 Iris/OptiFine 整包运行时、社区 geometry/compute 图或原生 HLSL 光影包格式。

## 1. 构建与配置

在 Gradle 工程根执行 `gradlew.bat jar`。`buildShaderProcessor` 使用 Cargo `build --release --locked --lib` 构建 Rust 转换库，
`stageShaderProcessor` 把 DLL、转换库许可证与 naga 的 MIT/Apache 许可证加入主资源集，普通 JAR 与 `jarALL` 均包含它们。
构建输入包含转换器源代码、Cargo 锁文件和本地 naga/naga-types fork；Cargo 失败使任务失败，不使用未知版本的旧 DLL。

默认从相邻 `Illusion-shaders-extension` 查找源码；重建目录布局使用上两级工作区中的同名目录。
构建机可用 `-PshaderProcessorRoot=<绝对路径>` 指定转换器工程；其同级必须有 `naga-for-hlsl`。这不是游戏配置或运行时编译器选择器。
MCDR 的 DXC/Agility 运行库继续按既有资源清单打包。先构建相邻 MCDR，再构建 JVM 工程，确保 `copyNative` 取到匹配的 native DLL。

游戏内 HLSL 落盘使用 `[debug] dumpHlsl`，目录为 `<user.dir>/dxrmc/hlsl/`，文件名由整对 VS/PS 内容 hash 确定。
编译器路径、实现和回退不提供玩家开关。

## 2. 业务边界与数据流

| 所有者 | 输入与输出 |
| --- | --- |
| `D3D12Device` | 源码获取、按 id/stage/defines 缓存、管线/PSO 生命周期 |
| `D3D12ShaderCompiler` | 宏/include、共享绑定表、阶段链接、坐标选项；输出编译产物 |
| `NagaShaderProcessor` | 转换请求与 manifest 的类型化适配，不管理设备或 GPU 资源 |
| `MCDR_ABI` / 内部 `NagaLibrary` | 唯一 FFM 门面与三函数 ABI 传输、内存所有权 |
| `NativeLibraryResources` | 从 classpath 解包必需资源，不反向初始化 ABI |
| `NagaVertexAdapter` | 将当前 naga 顶点入口适配为 mesh generator 的输入，不在 terrain 内识别编译器私有结构名 |
| native shader/device | DXC、DXIL 缓存、根签名、PSO 与实际资源视图 |

`D3D12IntermediaryShaderModule` 保存不可变的展开源与初次转换反射。它没有 native IR 指针或需要手工释放的 SPIR-V 缓冲。
链接时用两阶段反射生成一份 binding 表，再按实际布局重新转换两个阶段；不能使用发现阶段的 location 作为最终 IA 布局。
最终 `D3D12CompiledShader` 包含 HLSL、资源表和最终 VS 输入。`D3D12CompiledRenderPipeline.buildBindings()` 直接返回该资源表，不重复推导绑定顺序。

## 3. 源码、宏与 include

`D3D12Device` 首先向 `ShaderSource` 请求源；资源尚未发布时使用 `D3D12FallbackShaderSource` 捕获的原版启动源。这是源码生命周期适配，仍只调用 naga。
`ShaderDefines` 放在 `#version` 之后；全局注入 `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`，让原版 reversed-Z/0..1 深度分支成立。
`gl_VertexID` 等方言处理归转换器所有。

`#include <namespace:path>` 由宿主资源解析器展开，优先读取当前资源包；原版 `ShadercIncludeResult` 仅用于原版 include 缓存互操作，不调用其编译器。
展开最多 16 层、累计源码预算 8 MiB；未找到 include 或越界均报告编译错误。
宿主文本展开不分析条件宏，原版 include 使用自己的防重守卫。社区包的 VFS/条件 include 运行时尚未实现，不把此原版适配当作整包解析器。

## 4. 资源绑定

ABI 为 `illusion_shader_abi_version` / `illusion_shader_convert` / `illusion_shader_free`，版本 1；manifest schema 为 `illusion.shader-manifest/1`。
FFM 使用进程寿命的库 arena 和每次调用的局部请求 arena；输出指针先清零，复制 UTF-8 JSON 后在 `finally` 中用库的 free 释放。
输出字符串读取窗口上限为 64 MiB。加载资源只接受 classpath 中的 DLL，并按字节比较刷新 `<user.dir>/dxrmc/illusion_shader.dll`。

寄存器号为最终 `entries` 下标，两个阶段请求同一 `register_layout=host` 与 `bindings`：

| 资源 | manifest 与宿主处理 | HLSL / native |
| --- | --- | --- |
| 常量缓冲 | `constant_buffers`；去掉 `constants_` 得到原版 block 名 | `b<index>`，root CBV |
| 采样纹理 | `textures`；要求原版 `COMBINED_IMAGE_SAMPLER` | `t<index>` 与同号 `s<index>` |
| 字节 texel buffer | `storage_buffers`；当前原版适配只接收 R8_SINT / ByteAddressBuffer | `t<index>`，RAW SRV |

资源按顶点后片元遍历、同名去重；同名不同类型拒绝。最终 manifest 的 register/space 必须与分配一致。
当前原版接口不接受 storage image 或 comparison sampler，也不将未知 SSBO 当成字节 texel buffer。完整社区资源语义需要包运行时自己的类型化描述。

R8_SINT 的每个元素占一个字节，转换器按 DWORD 读取后提取并符号扩展目标字节。
绑定传 `texelFormat=-1`，native 使用 `R32_TYPELESS` 与 `D3D12_BUFFER_SRV_FLAG_RAW`；offset 必须 4 字节对齐，最后不足 4 字节的范围向上取整。
native 验证逻辑切片边界及向上取整后的物理分配边界；缓冲物理分配为 256 字节对齐。不能把 R8_SINT typed SRV 传给 ByteAddressBuffer。

## 5. 阶段链接与顶点布局

VS 请求的 `vertex_attributes` 来自原版 vertex format。最终反射按原始输入名字找到 format 元素，以其 location 生成 `LOC<location>`，
保持原 slot、format、offset、stride、stepRate；不补造 POSITION/TEXCOORD 语义或不存在的输入。

PS 请求的 `fragment_inputs` 是 VS 输出名字到 location 的映射，保留空洞，输入声明顺序不影响链接；找不到对应输出时报错。
`vertex_outputs` 同时传 location/type/interpolation，使转换器按 VS 输出顺序补齐缺失的 PS 输入，维持 D3D12 签名的分量打包。
初次转换用于资源发现，不能替代上述链接。DXC/PSO 仍负责最终类型和签名校验。

## 6. 坐标与地形 mesh

`flipY` 或 `animate_sprite` 管线请求 `flip_y`；转换器在 GLSL 归一中应用顶点 Y 翻转。
`lightmap` 且非 flipY，或 `screenquad` 使用 `texCoord` 纹理采样且非 flipY，片元请求 `flip_texcoord=texCoord`。
原位 `gl_FragCoord`/texelFetch 路线不因 screenquad 名称翻转。坐标规则不以 HLSL 字符串插入语句实现。

地形 mesh 的顶点格式读取最终 manifest；`NagaVertexAdapter` 根据当前 naga 入口的 LOC 参数生成 `D3D12VertexInput` 和 `vs_main`，
并解析 cbuffer instance 的成员访问。编译器入口变化由此适配层负责。
`D3D12MeshletShader` 解码顶点/段属性、构造 MS/AS；矩阵保持 row_major 与 `mul(vector, matrix)`。
VS/PS 和生成的 MS/AS 均由 DXC 产生 SM6.9 DXIL，具体地形执行约束见 [mesh 文档](../mesh&vrs/README.md)。

## 7. 缓存与错误

设备的 source cache 按 id/stage/defines 保存模块，pipeline cache 保存布局和 flipY 变体，资源重载清理对应缓存。
库不保存跨调用 IR；每次转换独立持有数据，原模块不会因另一条管线重绑定而被修改。
native DXIL 缓存拥有最终 DXC 产物；生成源码变化自然改变其内容键。

转换错误包含逻辑文件名与库提供的诊断；原版设备将失败管线标记无效。没有 compiler fallback。
阶段签名、根签名与资源视图需要一起验证；离线转换或 JAR 打包成功不能证明游戏画面正确。

## 8. 文件导航

路径相对于 `src/main/kotlin/io/github/dxegp/`。

| 文件 | 职责 |
| --- | --- |
| `d3d12/shader/D3D12ShaderCompiler.kt` | 原版源准备、资源表、阶段链接、坐标 |
| `d3d12/shader/NagaShaderProcessor.kt` | JSON 请求与 manifest 适配 |
| `d3d12/shader/D3D12IntermediaryShaderModule.kt` / `D3D12CompiledShader.kt` | 源模块与管线编译产物 |
| `d3d12/shader/NagaVertexAdapter.kt` | naga 顶点函数到 mesh 入口 |
| `d3d12/ffi/NagaLibrary.kt` / `MCDR_ABI.kt` | 编译器 C ABI 传输与唯一公开出口 |
| `d3d12/device/D3D12Device.kt` | 管线缓存、最终顶点布局、native PSO |
| `d3d12/backend/D3D12CompiledRenderPipeline.kt` | 持有并复用最终绑定表 |
