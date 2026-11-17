package io.github.dxegp.render.resolution

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.serialization.Codec
import io.github.dxegp.Illusion
import io.github.dxegp.config.SrBackend
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.config.RenderConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

object D3D12RenderScale {
    private const val MIN_PERCENT = 25
    private const val MAX_PERCENT = 400
    private const val STEP_PERCENT = 25
    private const val DEFAULT_PERCENT = 100

    const val OPTIONS_KEY: String = "dxrMcRenderScale"
    const val DLSS_OPTIONS_KEY: String = "dxrMcRenderScaleDlss"

    private var syncGeneration = 0

    @JvmStatic
    fun syncGeneration(): Int = syncGeneration

    @JvmStatic
    fun logSliderResync() {
        Illusion.LOGGER.info("render-scale slider re-synced to {}%", percent())
    }

    @JvmStatic
    fun minimumPercent(): Int = MIN_PERCENT

    private fun maximumPercent(): Int =
        if (RenderConfiguration.applied().settings.reconstructionEnabled) 100 else MAX_PERCENT

    private fun stepPercent(): Int =
        if (RenderConfiguration.applied().settings.reconstructionEnabled) 1 else STEP_PERCENT

    private fun denormalise(value: Int): Int {
        if (isLockedByDlaa()) return DEFAULT_PERCENT
        val step = stepPercent()
        val bounded = value.coerceIn(MIN_PERCENT, maximumPercent())
        return (((bounded + step / 2) / step) * step).coerceIn(MIN_PERCENT, maximumPercent())
    }

    private object PercentRange : OptionInstance.SliderableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> = Optional.of(denormalise(value))

        override fun codec(): Codec<Int> = Codec.intRange(MIN_PERCENT, MAX_PERCENT)

        override fun toSliderValue(value: Int): Double =
            if (isLockedByDlaa()) 1.0 else when (value) {
                MIN_PERCENT -> 0.0
                maximumPercent() -> 1.0
                else -> Mth.map(value + 0.5, MIN_PERCENT.toDouble(), maximumPercent() + 1.0, 0.0, 1.0)
            }

        override fun fromSliderValue(slider: Double): Int = denormalise(Mth.floor(
            Mth.map(slider.coerceIn(0.0, 1.0), 0.0, 1.0, MIN_PERCENT.toDouble(), maximumPercent() + 1.0),
        ))

        override fun next(current: Int): Optional<Int> =
            Optional.of(denormalise(current + stepPercent()))

        override fun previous(current: Int): Optional<Int> =
            Optional.of(denormalise(current - stepPercent()))
    }

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.renderScale",
        { Tooltip.create(Component.translatable("options.renderScale.tooltip")) },
        { caption, value -> Component.translatable("options.percent_value", caption, value) },
        PercentRange,
        DEFAULT_PERCENT,
        { value -> setPercent(value) },
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    @JvmStatic
    fun current(): Int = percent()

    @JvmStatic
    fun percent(): Int = RenderConfiguration.applied().renderScalePercent

    @JvmStatic
    fun storedPlain(): Int = RenderConfiguration.applied().settings.plainScalePercent

    @JvmStatic
    fun storedDlss(): Int = RenderConfiguration.applied().settings.dlssScalePercent

    @JvmStatic
    fun isDlssMode(): Boolean = RenderConfiguration.applied().settings.srBackend == SrBackend.DLSS

    @JvmStatic
    fun isLockedByDlaa(): Boolean =
        RenderConfiguration.applied().settings.antiAliasing == D3D12AntiAliasing.DLAA

    @JvmStatic
    fun isEnabled(): Boolean = percent() != DEFAULT_PERCENT

    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    @JvmStatic
    fun setPercent(value: Int) {
        RenderConfiguration.request { settings ->
            if (settings.srBackend == SrBackend.DLSS)
                settings.copy(dlssScalePercent = value.coerceIn(MIN_PERCENT, 100))
            else settings.copy(plainScalePercent = value.coerceIn(MIN_PERCENT, MAX_PERCENT))
        }
    }

    @JvmStatic
    fun setPercentAtFrameBoundary(value: Int) = setPercent(value)

    fun synchronizeConfiguration(settings: RenderConfiguration.Settings) {
        OPTION.set(RenderConfiguration.applied().renderScalePercent)
        syncGeneration++
    }

    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) return
        try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            val plain = lines.firstOrNull { it.startsWith("$OPTIONS_KEY:") }
                ?.substring(OPTIONS_KEY.length + 1)?.trim()?.toIntOrNull()
            val dlss = lines.firstOrNull { it.startsWith("$DLSS_OPTIONS_KEY:") }
                ?.substring(DLSS_OPTIONS_KEY.length + 1)?.trim()?.toIntOrNull()
            RenderConfiguration.request { settings ->
                settings.copy(
                    plainScalePercent = plain?.coerceIn(MIN_PERCENT, MAX_PERCENT)?.let {
                        ((it + STEP_PERCENT / 2) / STEP_PERCENT * STEP_PERCENT)
                            .coerceIn(MIN_PERCENT, MAX_PERCENT)
                    } ?: settings.plainScalePercent,
                    dlssScalePercent = dlss?.coerceIn(MIN_PERCENT, 100) ?: settings.dlssScalePercent,
                )
            }
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("could not read {} from {}", OPTIONS_KEY, file, failure)
        }
    }

    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) return
        try {
            val settings = RenderConfiguration.settingsToSave()
            val keys = listOf(OPTIONS_KEY, DLSS_OPTIONS_KEY)
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> keys.any { line.startsWith("$it:") } }
            val eol = System.lineSeparator()
            val written = listOf("$OPTIONS_KEY:${settings.plainScalePercent}",
                "$DLSS_OPTIONS_KEY:${settings.dlssScalePercent}")
            file.writeText((kept + written).joinToString(eol) + eol, StandardCharsets.UTF_8)
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("could not write {} to {}", OPTIONS_KEY, file, failure)
        }
    }

    @JvmStatic
    fun scaled(value: Int): Int {
        val percentage = percent()
        if (value <= 0 || percentage == DEFAULT_PERCENT || !isAvailable()) return value
        return (value.toLong() * percentage / DEFAULT_PERCENT).coerceAtLeast(1L).toInt()
    }

    @JvmStatic
    fun worldLayerSize(value: Int): Int = scaledForAttachment(value)

    @JvmStatic
    fun scaledForAttachment(value: Int): Int {
        val dimension = scaled(value)
        if (dimension <= value) return dimension
        val limit = RenderSystem.getDevice().deviceInfo.limits().maxTextureSize()
        return if (value <= limit) minOf(dimension, limit) else value
    }
}
