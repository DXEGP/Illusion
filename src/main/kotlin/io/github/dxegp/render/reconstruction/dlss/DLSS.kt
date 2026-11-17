package io.github.dxegp.render.reconstruction.dlss

import com.mojang.serialization.Codec
import io.github.dxegp.Illusion
import io.github.dxegp.config.SrBackend
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.config.RenderConfiguration
import io.github.dxegp.render.resolution.D3D12RenderScale
import java.io.File
import java.lang.foreign.Arena
import java.nio.charset.StandardCharsets
import java.util.Optional
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

object DeepLearningSuperSampling {
    const val OPTIONS_KEY: String = "dxrMcDlss"
    const val QUALITY_OPTIONS_KEY: String = "dxrMcDlssQuality"
    const val PRESET_OPTIONS_KEY: String = "dxrMcDlssPreset"

    private const val NOTE_CAPACITY = 256
    private const val QUALITY_DEFAULT = 2
    private const val PRESET_DEFAULT = 0
    private const val PRESET_M = 13
    private val QUALITY_NAMES = listOf("ultra_performance", "performance", "balanced", "quality")
    private val PRESET_NAMES = listOf("default", "j", "k", "l", "m")
    private val PRESET_VALUES = listOf(PRESET_M, 10, 11, 12, PRESET_M)

    private val OPTION: OptionInstance<Boolean> = OptionInstance.createBoolean(
        "options.dlss",
        { Tooltip.create(Component.translatable("options.dlss.tooltip")) },
        false,
        { enabled ->
            RenderConfiguration.request { settings ->
                settings.copy(srBackend = if (enabled) SrBackend.DLSS
                    else if (settings.srBackend == SrBackend.DLSS) SrBackend.NONE else settings.srBackend)
            }
        },
    )

    private object QualityValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in QUALITY_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(QUALITY_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, QUALITY_NAMES.size - 1)
    }

    private val QUALITY_OPTION: OptionInstance<Int> = OptionInstance(
        "options.dlss.quality",
        { Tooltip.create(Component.translatable("options.dlss.quality.tooltip")) },
        { _, value -> Component.translatable("options.dlss.quality.${QUALITY_NAMES[value]}") },
        QualityValues,
        QUALITY_DEFAULT,
        { value -> RenderConfiguration.request { it.copy(dlssQuality = value,
            dlssScalePercent = RenderConfiguration.Settings.qualityPercent(value)) } },
    )

    private object PresetValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in PRESET_NAMES.indices) Optional.of(value) else Optional.empty()

        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(PRESET_NAMES.indices.toList())

        override fun codec(): Codec<Int> = Codec.intRange(0, PRESET_NAMES.size - 1)
    }

    private val PRESET_OPTION: OptionInstance<Int> = OptionInstance(
        "options.dlss.preset",
        { Tooltip.create(Component.translatable("options.dlss.preset.tooltip")) },
        { _, value -> Component.translatable("options.dlss.preset.${PRESET_NAMES[value]}") },
        PresetValues,
        PRESET_DEFAULT,
        { value -> RenderConfiguration.request { it.copy(dlssPreset = value) } },
    )

    @JvmStatic
    fun option(): OptionInstance<Boolean> = OPTION

    @JvmStatic
    fun qualityOption(): OptionInstance<Int> = QUALITY_OPTION

    @JvmStatic
    fun presetOption(): OptionInstance<Int> = PRESET_OPTION

    @JvmStatic
    fun quality(): Int = RenderConfiguration.applied().settings.dlssQuality

    @JvmStatic
    fun preset(): Int = RenderConfiguration.applied().settings.dlssPreset

    internal fun renderPreset(): Int = renderPreset(preset())

    internal fun renderPreset(index: Int): Int = PRESET_VALUES[index]

    @JvmStatic
    fun inputPercent(): Int = RenderConfiguration.Settings.qualityPercent(quality())

    @JvmStatic
    fun isEnabled(): Boolean = RenderConfiguration.applied().settings.srBackend == SrBackend.DLSS

    @JvmStatic
    fun isDlaaActive(): Boolean =
        RenderConfiguration.applied().settings.antiAliasing == D3D12AntiAliasing.DLAA

    @JvmStatic
    fun isAvailable(): Boolean =
        D3D12AdapterInfo.current != null && MCDR_ABI.D3D12DlssAvailable() == 1

    fun synchronizeConfiguration(settings: RenderConfiguration.Settings) {
        OPTION.set(settings.srBackend == SrBackend.DLSS)
        QUALITY_OPTION.set(settings.dlssQuality)
        PRESET_OPTION.set(settings.dlssPreset)
    }

    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) return
        try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            val toggle = lines.firstOrNull { it.startsWith("$OPTIONS_KEY:") }
                ?.substring(OPTIONS_KEY.length + 1)?.trim()?.lowercase()
            val enabled = when (toggle) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            val qualityRaw = lines.firstOrNull { it.startsWith("$QUALITY_OPTIONS_KEY:") }
                ?.substring(QUALITY_OPTIONS_KEY.length + 1)?.trim()?.lowercase()
            val quality = qualityRaw?.let { raw ->
                QUALITY_NAMES.indexOf(raw).takeIf { it >= 0 } ?: raw.toIntOrNull()
                    ?.takeIf { it in QUALITY_NAMES.indices }
            }
            val presetRaw = lines.firstOrNull { it.startsWith("$PRESET_OPTIONS_KEY:") }
                ?.substring(PRESET_OPTIONS_KEY.length + 1)?.trim()?.lowercase()
            val preset = presetRaw?.let { raw ->
                PRESET_NAMES.indexOf(raw).takeIf { it >= 0 } ?: raw.toIntOrNull()?.let {
                    if (it == 0) PRESET_DEFAULT else PRESET_VALUES.lastIndexOf(it).takeIf { index -> index >= 0 }
                }
            }
            RenderConfiguration.request { settings ->
                settings.copy(
                    srBackend = when {
                        enabled == true -> SrBackend.DLSS
                        enabled == false && settings.srBackend == SrBackend.DLSS -> SrBackend.NONE
                        else -> settings.srBackend
                    },
                    dlssQuality = quality ?: settings.dlssQuality,
                    dlssScalePercent = if (lines.none { it.startsWith("${D3D12RenderScale.DLSS_OPTIONS_KEY}:") } && quality != null)
                        RenderConfiguration.Settings.qualityPercent(quality) else settings.dlssScalePercent,
                    dlssPreset = preset ?: settings.dlssPreset,
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
            val keys = listOf(OPTIONS_KEY, QUALITY_OPTIONS_KEY, PRESET_OPTIONS_KEY)
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { line -> keys.any { line.startsWith("$it:") } }
            val written = listOf(
                "$OPTIONS_KEY:${settings.srBackend == SrBackend.DLSS}",
                "$QUALITY_OPTIONS_KEY:${QUALITY_NAMES[settings.dlssQuality]}",
                "$PRESET_OPTIONS_KEY:${PRESET_NAMES[settings.dlssPreset]}",
            )
            val eol = System.lineSeparator()
            file.writeText((kept + written).joinToString(eol) + eol, StandardCharsets.UTF_8)
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("could not write {} to {}", OPTIONS_KEY, file, failure)
        }
    }

    @JvmStatic
    fun pclFrameBegin() {
        MCDR_ABI.D3D12ReflexFrameBegin()
    }

    @JvmStatic
    fun pclRenderSubmitStart() {
        MCDR_ABI.D3D12ReflexMarker(1)
        MCDR_ABI.D3D12ReflexMarker(2)
    }

    @JvmStatic
    fun statusLine(): Component {
        try {
            return Arena.ofConfined().use { arena ->
                val note = arena.allocate(NOTE_CAPACITY.toLong())
                val result = MCDR_ABI.D3D12QueryDlss(note, NOTE_CAPACITY)
                if (result == Int.MIN_VALUE) Component.translatable("dxr_mc.video.sr.noabi", "DLSS")
                else Component.literal("DLSS: " + note.getString(0).ifEmpty { "(no detail)" })
            }
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("could not query DLSS status", failure)
            return Component.literal("DLSS: status unavailable")
        }
    }
}
