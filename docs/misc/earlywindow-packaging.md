# 早期窗口打包与开发启动

发布包是一个外层早期服务 JAR。外层仅包含
`io.github.dxegp.bootstrap.earlywindow.service` 下的引导器、窗口 provider
和共享租约接口，以及两份 FML 服务声明和加载画面资源。
标准 `META-INF/jarjar/metadata.json` 指向内嵌的
`META-INF/jarjar/illusion-core.jar`。内层才是普通 NeoForge 模组，包含
`neoforge.mods.toml`、mixin、游戏业务类、native 与允许再分发的 vendor 资源。

FML 12.0.12 在创建早期窗口之前发现外层服务。`GraphicsBootstrapper`
通过 `FMLConfig` API 选择 `dxrmc`，随后 FML 才读取 `earlyWindowProvider`，
因此首次安装即可启用同窗口路径。`earlyWindowControl = false` 时保持禁用；
用户显式选择其他 provider 时保留该选择并给出切换说明。
之后，标准 JarInJar dependency locator 从外层依赖源中发现内层普通模组。

## 安装与升级

将发布 JAR 放入 `mods/`。从旧伴随包布局升级时，先关闭游戏，再删除
`mods/dxrmc-earlywindow.jar`，并确保目录中只保留一份 Illusion 发布包。
引导器检测到旧伴随包后会明确报错并指示删除，避免静默加载重复 provider。
新布局不向 `mods/` 解包，也不通过文本替换改写 TOML。

## Gradle 产物

- `jar`：完整的单文件发布包。
- `coreJar`：普通内层模组，输出到 `build/intermediates/`。
- `earlyWindowJar`：供开发启动和外层组装使用的纯早期服务容器。
- `jarALL`：保持相同单文件布局，内嵌 `coreJarAll`；后者的 vendor 目录
  额外包含开发 SDK 运行库，仅用于本地测试，禁止分发。

`runClient` 在启动前将纯服务容器复制为
`run/mods/dxrmc-dev-earlyservice.jar`。游戏业务仍由正常 ModDev 源集加载，
因此开发启动使用同一引导器与 provider，不会重复发现内层模组。
开发目录中不要同时放入 Illusion 发布 JAR。

独立 `earlyService` 源集编译早期类；游戏源集仅在编译时引用其输出。
内层不包含早期类或服务声明。早期 Kotlin 引导器只调用 JDK/FML，
该源集关闭参数、调用结果与接收者断言，避免生成 `kotlin.jvm.internal`
调用；发布前需检查实际字节码。Java provider 与 Kotlin 租约都不依赖 Kotlin runtime。
外层不打包 `kotlin/**`，因此不依赖游戏层 KotlinForForge，也不会在两层
同时导出 Kotlin 包。
