package io.github.dxegp.render.presentation.framegen

import com.mojang.serialization.Codec
import io.github.dxegp.Illusion
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.config.RenderConfiguration
import java.io.File
import java.lang.foreign.Arena
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Optional
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

object FrameGeneration {
    const val OPTIONS_KEY = "dxrMcFrameGeneration"
    const val FRAMES_KEY = "dxrMcFrameGenerationFrames"
    const val BACKEND_KEY = "dxrMcFrameGenBackend"

    private const val MIN_FRAMES = 0
    private const val MAX_FRAMES = 5
    private val BACKEND_NAMES = linkedMapOf(
        MCDR_ABI.FG_BACKEND_DLSSG to "dlssg",
        MCDR_ABI.FG_BACKEND_FSRFG to "fsrfg",
        MCDR_ABI.FG_BACKEND_XESSFG to "xessfg",
    )
    private val BACKEND_BY_NAME = BACKEND_NAMES.entries.associate { (backend, name) -> name to backend }
    private var synchronizingOptions = false
    private var capsCache: Map<Int, MCDR_ABI.FgCaps?> = emptyMap()
    private var capsEpochNanos = Long.MIN_VALUE

    private object FrameValues : OptionInstance.SliderableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in MIN_FRAMES..MAX_FRAMES) Optional.of(value) else Optional.empty()
        override fun toSliderValue(value: Int): Double =
            (value - MIN_FRAMES).toDouble() / (MAX_FRAMES - MIN_FRAMES)
        override fun fromSliderValue(slider: Double): Int =
            (MIN_FRAMES + Math.round(slider * (MAX_FRAMES - MIN_FRAMES)).toInt()).coerceIn(MIN_FRAMES, MAX_FRAMES)
        override fun codec(): Codec<Int> = Codec.intRange(MIN_FRAMES, MAX_FRAMES)
        override fun next(value: Int): Optional<Int> = Optional.of(if (value >= MAX_FRAMES) MIN_FRAMES else value + 1)
        override fun previous(value: Int): Optional<Int> = Optional.of(if (value <= MIN_FRAMES) MAX_FRAMES else value - 1)
    }

    private val FRAMES_OPTION = OptionInstance(
        "options.frameGeneration.frames",
        { Tooltip.create(Component.translatable("options.frameGeneration.frames.tooltip")) },
        { _, value: Int ->
            if (value == MIN_FRAMES) Component.translatable("options.frameGeneration.auto")
            else Component.literal("${value + 1}×")
        },
        FrameValues,
        1,
    ) { value ->
        if (!synchronizingOptions) RenderConfiguration.request { it.copy(frameGenerationFrames = value) }
    }

    private val BACKEND_OPTIONS = BACKEND_NAMES.mapValues { (backend, name) ->
        OptionInstance.createBoolean(
            "options.frameGeneration.$name",
            { Tooltip.create(Component.translatable("options.frameGeneration.$name.tooltip")) },
            false,
        ) { enabled -> requestBackend(backend, enabled) }
    }

    @JvmStatic fun backendOption(backend: Int): OptionInstance<Boolean>? = BACKEND_OPTIONS[backend]
    @JvmStatic fun framesOption(): OptionInstance<Int> = FRAMES_OPTION
    @JvmStatic fun activeBackend(): Int = RenderConfiguration.applied().settings.frameGenerationBackend
    @JvmStatic fun mode(): Int = RenderConfiguration.applied().settings.frameGenerationMode
    @JvmStatic fun frames(): Int = RenderConfiguration.applied().settings.frameGenerationFrames
    @JvmStatic fun isEnabled(): Boolean = RenderConfiguration.applied().settings.frameGenerationEnabled

    @JvmStatic
    fun requestBackend(backend: Int, enabled: Boolean) {
        if (synchronizingOptions) return
        require(backend in BACKEND_NAMES) { "Unknown frame generation backend $backend" }
        RenderConfiguration.request { previous ->
            when {
                enabled -> previous.copy(frameGenerationBackend = backend, frameGenerationMode = 1)
                previous.frameGenerationBackend == backend ->
                    previous.copy(frameGenerationBackend = 0, frameGenerationMode = 0)
                else -> previous
            }
        }
    }

    @JvmStatic
    fun isAvailable(): Boolean = BACKEND_NAMES.keys.any { capsFor(it)?.available == true }

    @JvmStatic
    fun statusLine(): Component = statusLine(activeBackend())

    @JvmStatic
    fun statusLine(backend: Int): Component {
        if (backend == 0) return Component.translatable("dxr_mc.video.fg.off")
        val capabilities = capsFor(backend) ?: return Component.translatable("dxr_mc.video.fg.noabi")
        return Component.translatable(
            if (capabilities.available) "dxr_mc.video.fg.available" else "dxr_mc.video.fg.unavailable",
            capabilities.note,
        )
    }

    @JvmStatic
    fun isBackendUnavailable(backend: Int): Boolean = capsFor(backend)?.available == false

    @JvmStatic
    fun runtimeLine(backend: Int): Component =
        if (isEnabled() && activeBackend() == backend) runtimeLine() else Component.empty()

    @JvmStatic
    fun runtimeLine(): Component {
        if (!isEnabled()) return Component.empty()
        return Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(384L)
            if (MCDR_ABI.D3D12FgStatus(buffer, 384) <= 0) Component.empty()
            else Component.literal(buffer.getString(0, StandardCharsets.UTF_8).trim())
        }
    }

    @JvmStatic
    fun rollbackLine(): Component? {
        if (!isEnabled()) return null
        val state = MCDR_ABI.D3D12FgState()
        if (state == Int.MIN_VALUE || state and 0xFF != 0) return null
        return Component.translatable("dxr_mc.video.fg.rolled_back", capsFor(activeBackend())?.note.orEmpty())
    }

    @JvmStatic
    fun hudlessWanted(): Boolean = isEnabled() &&
        activeBackend() == MCDR_ABI.FG_BACKEND_FSRFG && Minecraft.getInstance().level != null

    internal fun nativeMode(): Int = when {
        !isEnabled() -> 0
        frames() == MIN_FRAMES -> 2
        else -> 1
    }

    internal fun nativeFrames(): Int =
        if (activeBackend() == MCDR_ABI.FG_BACKEND_FSRFG) 1 else frames().coerceAtLeast(1)

    private fun capsFor(backend: Int): MCDR_ABI.FgCaps? {
        val now = System.nanoTime()
        if (capsEpochNanos == Long.MIN_VALUE || now - capsEpochNanos >= 1_000_000_000L) {
            capsCache = BACKEND_NAMES.keys.associateWith { MCDR_ABI.queryFgCaps(it) }
            capsEpochNanos = now
        }
        return capsCache[backend]
    }

    internal fun synchronizeConfiguration(settings: RenderConfiguration.Settings) {
        synchronizingOptions = true
        try {
            FRAMES_OPTION.set(settings.frameGenerationFrames)
            BACKEND_OPTIONS.forEach { (backend, option) ->
                option.set(settings.frameGenerationEnabled && settings.frameGenerationBackend == backend)
            }
        } finally {
            synchronizingOptions = false
        }
        capsEpochNanos = Long.MIN_VALUE
    }

    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) return
        val entries = try {
            file.readLines(StandardCharsets.UTF_8).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator) to
                    line.substring(separator + 1).trim().removeSurrounding("\"").lowercase(Locale.ROOT)
            }.toMap()
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("Could not read frame generation options from {}", file, failure)
            return
        }
        RenderConfiguration.request { previous ->
            val backend = entries[BACKEND_KEY]?.let { BACKEND_BY_NAME[it] ?: 0 }
                ?: previous.frameGenerationBackend
            val rawMode = entries[OPTIONS_KEY]
            val mode = when (rawMode) {
                null -> previous.frameGenerationMode
                "off" -> 0
                "on", "dynamic" -> 1
                else -> rawMode.toIntOrNull()?.coerceIn(0, 1) ?: 0
            }
            previous.copy(
                frameGenerationBackend = backend,
                frameGenerationMode = if (backend == 0) 0 else mode,
                frameGenerationFrames = if (rawMode == "dynamic") MIN_FRAMES
                    else entries[FRAMES_KEY]?.toIntOrNull()?.coerceIn(MIN_FRAMES, MAX_FRAMES)
                        ?: previous.frameGenerationFrames,
            )
        }
    }

    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) return
        val settings = RenderConfiguration.settingsToSave()
        val entries = linkedMapOf(
            OPTIONS_KEY to settings.frameGenerationMode.toString(),
            FRAMES_KEY to settings.frameGenerationFrames.toString(),
            BACKEND_KEY to BACKEND_NAMES[settings.frameGenerationBackend].orEmpty(),
        )
        try {
            val kept = file.readLines(StandardCharsets.UTF_8).filterNot { it.substringBefore(':') in entries }
            file.writeText((kept + entries.map { (key, value) -> "$key:$value" })
                .joinToString(System.lineSeparator(), postfix = System.lineSeparator()), StandardCharsets.UTF_8)
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("Could not write frame generation options to {}", file, failure)
        }
    }
}
