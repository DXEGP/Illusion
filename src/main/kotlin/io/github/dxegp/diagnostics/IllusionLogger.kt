package io.github.dxegp.diagnostics

import io.github.dxegp.config.IllusionConfig
import io.github.dxegp.diagnostics.IllusionLogger.i18n_LOG_KEY
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.TranslatableEnum
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.slf4j.LoggerFactory

/**
 * 本 mod 的日志级别，同时管三处：本 mod 的 log4j logger（[IllusionLogger.OWN_LOGGERS]）、
 * [IllusionLogger.debugMessage] / `debugFormat` 的诊断打印、以及推给 native 的 `gLogLevel`。
 * `D3D12_LOG_VERBOSE=1` 是开发逃生口，优先级高于配置文件。
 */
enum class IllusionLogLevel(val nativeLevel: Int) : TranslatableEnum {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3),
    TRACE(3);

    /**
     * 配置界面里的显示名：不实现 `TranslatableEnum` 就只显示 `ERROR` / `WARN` 这类常量名。
     * 键名见 `assets/dxr_mc/lang` 下的 `en_us.json` / `zh_cn.json`。
     */
    override fun getTranslatedName(): Component =
        Component.translatable("$i18n_LOG_KEY.logLevel.${this.name}")

    fun toLog4j(): Level = when (this) {
        ERROR -> Level.ERROR
        WARN -> Level.WARN
        INFO -> Level.INFO
        DEBUG -> Level.DEBUG
        TRACE -> Level.TRACE
    }
}

/**
 * JVM 侧唯一日志出口。
 * 状态用 `@Volatile` 字段 + getter，不能改成 `val`：类在配置读取前就会被触碰，
 * 类初始化时抓下的 `val` 会永远停在启动默认值。
 */
object IllusionLogger {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc")

    /** 开发逃生口，覆盖配置文件（只在类初始化时读一次）。 */
    private val ENV_VERBOSE = "1" == System.getenv("D3D12_LOG_VERBOSE")

    /**
     * 本 mod 用到的全部 logger 名。Minecraft 的 log4j2 配置里没有这些条目，不显式 setLevel 就会继承
     * root，玩家配的级别形同虚设。`io.github.dxegp` 是包级 logger，覆盖 `LogUtils.getLogger()` 派生的类名。
     */
    private val OWN_LOGGERS: List<String> =
        listOf("io.github.dxegp", "backend", "dxr_mc-temporal", "dxr_mc-velocity", "dxr_mc")

    /** "全都打"的 native 序号；TRACE 与 DEBUG 同为 3（见 [IllusionLogLevel.nativeLevel]）。 */
    private const val NATIVE_DEBUG = 3

    @Volatile
    private var configured: IllusionLogLevel =
        if (ENV_VERBOSE) IllusionLogLevel.DEBUG
        else IllusionLogLevel.ERROR

    @Volatile
    private var nativeVerbose: Boolean = false

    /** 上一次打过的 banner：两个 `@Mod` 入口都会应用一次默认值，避免重复；级别真变了仍各打一条。 */
    @Volatile
    private var lastBanner: String? = null

    /** 当前级别（配置读到之后；读到之前是 ERROR 默认值）。 */
    @JvmStatic
    fun level() = configured

    /** DEBUG 及以上：高频 dump 的开关。 */
    @JvmStatic
    fun debugEnabled(): Boolean = ENV_VERBOSE || configured.ordinal >= IllusionLogLevel.DEBUG.ordinal

    /** 诊断打印：格式化和判级都得在 [debugEnabled] 守卫内，否则关掉的日志照样要花格式化开销。 */
    @JvmStatic
    fun debugMessage(message: String) {
        if (debugEnabled()) LOGGER.debug(message)
    }

    @JvmStatic
    fun debugFormat(format: String, vararg args: Any?) {
        if (debugEnabled()) LOGGER.debug(format.format(*args))
    }

    /**
     * 一次性状态报告（"把哪组值应用给了 native"）：默认级别下也可见，不该落在 [debugMessage] 里。
     */
    @JvmStatic
    fun infoMessage(message: String) {
        LOGGER.info(message)
    }

    /** 出事了但引擎继续跑；**不**按 mod 级别过滤，由 log4j 配置决定是否显示。 */
    @JvmStatic
    fun warnMessage(message: String) {
        LOGGER.warn(message)
    }

    @JvmStatic
    fun warnFormat(format: String, vararg args: Any?) {
        LOGGER.warn(format.format(*args))
    }

    /** 应用 `config/dxr_mc-client.toml` 当前值（配置加载/变更时调用）。 */
    fun applyFromConfig() {
        applyOpt(IllusionConfig.LOG_LEVEL.get(), IllusionConfig.NATIVE_VERBOSE.get(),
            IllusionConfig.SHUTDOWN_TRACE.get(), IllusionConfig.NATIVE_LOG_FILE.get())
    }

    /** 立刻应用内置默认值：本类在配置读到之前就会被触碰，这段窗口里的输出也得已被过滤。 */
    fun applyDefaults() {
        applyOpt(if (ENV_VERBOSE) IllusionLogLevel.DEBUG else IllusionLogLevel.ERROR, false)
    }

    /**
     * 配置加载与热变更都走这里。
     * @param shutdownTrace 关停路径的 native 进度标记是否**无视 logLevel 恒定输出**（默认 true，与 native 默认一致）
     * @param nativeLogFile native 日志镜像到该文件，空 = 只走 stderr；启动器不落盘子进程输出，用户端取证只靠它
     */
    fun applyOpt(
        level: IllusionLogLevel,
        nativeVerbose: Boolean,
        shutdownTrace: Boolean = true,
        nativeLogFile: String = "",
    ) {
        val effective = if (ENV_VERBOSE) IllusionLogLevel.DEBUG else level
        configured = effective
        this.nativeVerbose = nativeVerbose
        for (name in OWN_LOGGERS) {
            Configurator.setLevel(name, effective.toLog4j())
        }
        val nativeLevel = if (ENV_VERBOSE || nativeVerbose) NATIVE_DEBUG else effective.nativeLevel
        pushNative(nativeLevel, shutdownTrace, nativeLogFile)
        val source = if (ENV_VERBOSE) "D3D12_LOG_VERBOSE" else "config/dxr_mc-client.toml"
        val banner = "log level = $effective (native $nativeLevel, source: $source)"
        if (banner != lastBanner) {
            lastBanner = banner
            LOGGER.info(banner)
        }
    }

    private fun pushNative(level: Int, shutdownTrace: Boolean, nativeLogFile: String) {
        try {
            if (MCDR_ABI.isAvailable()) {
                MCDR_ABI.D3D12SetLogLevel(level)
                MCDR_ABI.D3D12SetShutdownTraceAlways(shutdownTrace)
                if (nativeLogFile.isNotBlank() && MCDR_ABI.D3D12SetLogFile(nativeLogFile)) {
                    LOGGER.info("native log mirrored to {}", nativeLogFile)
                }
            }
        } catch (t: Throwable) {
            LOGGER.warn(
                "could not push the native log settings (level {}, shutdownTrace {}, file {})",
                level, shutdownTrace, nativeLogFile, t,
            )
        }
    }

    const val i18n_LOG_KEY = "dxr_mc.configuration.logging"
}
