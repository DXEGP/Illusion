package io.github.dxegp.render.reconstruction

import com.mojang.serialization.Codec
import io.github.dxegp.Illusion
import io.github.dxegp.config.SrBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.config.RenderConfiguration
import java.io.File
import java.lang.foreign.Arena
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Optional
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

object SuperResolution {
    const val OPTIONS_KEY_BACKEND = "dxrMcSrBackend"
    const val OPTIONS_KEY_SHARPNESS = "dxrMcSrSharpness"
    const val OPTIONS_KEY_MASK = "dxrMcSrMask"
    const val QUALITY_OPTIONS_KEY_FSR = "dxrMcSrFsrQuality"
    const val QUALITY_OPTIONS_KEY_XESS = "dxrMcSrXessQuality"

    private val QUALITY_NAMES = listOf("ultra_performance", "performance", "balanced", "quality", "native_aa")
    private var synchronizingOptions = false
    private var capsCache: MCDR_ABI.SrCaps? = null
    private var capsCacheNanos = 0L

    private object QualityValues : OptionInstance.CycleableValueSet<Int> {
        override fun validateValue(value: Int): Optional<Int> =
            if (value in QUALITY_NAMES.indices) Optional.of(value) else Optional.empty()
        override fun valueListSupplier(): CycleButton.ValueListSupplier<Int> =
            CycleButton.ValueListSupplier.create(QUALITY_NAMES.indices.toList())
        override fun codec(): Codec<Int> = Codec.intRange(0, QUALITY_NAMES.lastIndex)
    }

    private val MASK_OPTION = OptionInstance.createBoolean(
        "options.sr.mask",
        { Tooltip.create(Component.translatable("options.sr.mask.tooltip")) },
        true,
    ) { value -> request { it.copy(providerMasks = value) } }

    private val FSR_OPTION = OptionInstance.createBoolean(
        "options.sr.fsr",
        { Tooltip.create(Component.translatable("options.sr.fsr.tooltip")) },
        false,
    ) { enabled -> requestBackend(SrBackend.FSR, enabled) }

    private val XESS_OPTION = OptionInstance.createBoolean(
        "options.sr.xess",
        { Tooltip.create(Component.translatable("options.sr.xess.tooltip")) },
        false,
    ) { enabled -> requestBackend(SrBackend.XESS, enabled) }

    private val SHARPNESS_OPTION = OptionInstance(
        "options.sr.sharpness",
        { Tooltip.create(Component.translatable("options.sr.sharpness.tooltip")) },
        { _, value: Int -> Component.literal("${value}%") },
        OptionInstance.IntRange(0, 100),
        0,
    ) { value -> request { it.copy(sharpness = value) } }

    private val FSR_QUALITY_OPTION = OptionInstance(
        "options.sr.fsr.quality",
        { Tooltip.create(Component.translatable("options.sr.fsr.quality.tooltip")) },
        { _, value: Int -> Component.translatable("options.sr.quality.${QUALITY_NAMES[value]}") },
        QualityValues,
        2,
    ) { value -> request { it.copy(fsrQuality = value) } }

    private val XESS_QUALITY_OPTION = OptionInstance(
        "options.sr.xess.quality",
        { Tooltip.create(Component.translatable("options.sr.xess.quality.tooltip")) },
        { _, value: Int -> Component.translatable("options.sr.quality.${QUALITY_NAMES[value]}") },
        QualityValues,
        2,
    ) { value -> request { it.copy(xessQuality = value) } }

    @JvmStatic fun backendId(): Int = backend().id
    @JvmStatic fun backend(): SrBackend = RenderConfiguration.applied().settings.srBackend
    @JvmStatic fun isEnabled(): Boolean = RenderConfiguration.applied().settings.reconstructionEnabled
    @JvmStatic fun needsVelocityPass(): Boolean = isEnabled()
    @JvmStatic fun fsrOption(): OptionInstance<Boolean> = FSR_OPTION
    @JvmStatic fun xessOption(): OptionInstance<Boolean> = XESS_OPTION
    @JvmStatic fun fsrQualityOption(): OptionInstance<Int> = FSR_QUALITY_OPTION
    @JvmStatic fun xessQualityOption(): OptionInstance<Int> = XESS_QUALITY_OPTION
    @JvmStatic fun sharpnessOption(): OptionInstance<Int> = SHARPNESS_OPTION
    @JvmStatic fun maskOption(): OptionInstance<Boolean> = MASK_OPTION
    @JvmStatic fun mask(): Boolean = RenderConfiguration.applied().settings.providerMasks
    @JvmStatic fun sharpness(): Float = RenderConfiguration.applied().settings.sharpness / 100.0f
    @JvmStatic fun caps(): MCDR_ABI.SrCaps? = MCDR_ABI.querySrCaps(backendId())

    @JvmStatic
    fun maskSupported(): Boolean {
        val backend = backendId()
        if (backend == MCDR_ABI.SR_BACKEND_NONE) return false
        val now = System.nanoTime()
        if (capsCache?.backend != backend || now - capsCacheNanos >= 1_000_000_000L) {
            capsCache = MCDR_ABI.querySrCaps(backend)
            capsCacheNanos = now
        }
        return (capsCache?.takesMasks ?: 0) != 0
    }

    @JvmStatic
    fun statusLine(backend: Int): Component {
        val label = backendLabel(backend)
        val capabilities = MCDR_ABI.querySrCaps(backend)
            ?: return Component.translatable("dxr_mc.video.sr.noabi", label)
        return Component.translatable(
            if (capabilities.available) "dxr_mc.video.sr.available" else "dxr_mc.video.sr.unavailable",
            label, capabilities.note,
        )
    }

    @JvmStatic
    fun runtimeLine(backend: Int): Component {
        if (backendId() != backend) return Component.empty()
        return Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(384L)
            if (MCDR_ABI.D3D12SrQuery(buffer, 384) <= 0) Component.empty()
            else Component.literal(buffer.getString(0, StandardCharsets.UTF_8).trim())
        }
    }

    @JvmStatic
    fun isBackendMissing(backend: Int): Boolean = MCDR_ABI.querySrCaps(backend)?.available == false

    @JvmStatic
    fun backendLabel(backend: Int): String = when (backend) {
        MCDR_ABI.SR_BACKEND_DLSS -> "DLSS"
        MCDR_ABI.SR_BACKEND_FSR -> "AMD FidelityFX"
        MCDR_ABI.SR_BACKEND_XESS -> "Intel XeSS"
        else -> "Super Resolution"
    }

    @JvmStatic
    fun onDlssToggle(enabled: Boolean) = requestBackend(SrBackend.DLSS, enabled)

    private fun requestBackend(backend: SrBackend, enabled: Boolean) {
        request { previous ->
            if (enabled) previous.copy(srBackend = backend)
            else if (previous.srBackend == backend) previous.copy(srBackend = SrBackend.NONE)
            else previous
        }
    }

    private fun request(change: (RenderConfiguration.Settings) -> RenderConfiguration.Settings) {
        if (!synchronizingOptions) RenderConfiguration.request(change)
    }

    internal fun synchronizeConfiguration(settings: RenderConfiguration.Settings) {
        synchronizingOptions = true
        try {
            FSR_OPTION.set(settings.srBackend == SrBackend.FSR)
            XESS_OPTION.set(settings.srBackend == SrBackend.XESS)
            FSR_QUALITY_OPTION.set(settings.fsrQuality)
            XESS_QUALITY_OPTION.set(settings.xessQuality)
            SHARPNESS_OPTION.set(settings.sharpness)
            MASK_OPTION.set(settings.providerMasks)
        } finally {
            synchronizingOptions = false
        }
        capsCache = null
    }

    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) return
        val entries = try {
            file.readLines(StandardCharsets.UTF_8).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator) to
                    line.substring(separator + 1).trim().removeSurrounding("\"")
            }.toMap()
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("Could not read SR options from {}", file, failure)
            return
        }
        RenderConfiguration.request { previous ->
            val loaded = entries[OPTIONS_KEY_BACKEND]?.let { name ->
                SrBackend.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SrBackend.NONE
            }
            fun tier(key: String, baseline: Int): Int {
                val raw = entries[key]?.lowercase(Locale.ROOT) ?: return baseline
                return QUALITY_NAMES.indexOf(raw).takeIf { it >= 0 }
                    ?: raw.toIntOrNull()?.takeIf { it in QUALITY_NAMES.indices } ?: baseline
            }
            previous.copy(
                srBackend = if (loaded == SrBackend.NONE && previous.srBackend == SrBackend.DLSS)
                    SrBackend.DLSS else loaded ?: previous.srBackend,
                sharpness = entries[OPTIONS_KEY_SHARPNESS]?.toIntOrNull()?.coerceIn(0, 100) ?: previous.sharpness,
                providerMasks = entries[OPTIONS_KEY_MASK]?.toBooleanStrictOrNull() ?: previous.providerMasks,
                fsrQuality = tier(QUALITY_OPTIONS_KEY_FSR, previous.fsrQuality),
                xessQuality = tier(QUALITY_OPTIONS_KEY_XESS, previous.xessQuality),
            )
        }
    }

    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) return
        val settings = RenderConfiguration.settingsToSave()
        val entries = linkedMapOf(
            OPTIONS_KEY_BACKEND to settings.srBackend.name,
            OPTIONS_KEY_SHARPNESS to settings.sharpness.toString(),
            OPTIONS_KEY_MASK to settings.providerMasks.toString(),
            QUALITY_OPTIONS_KEY_FSR to settings.fsrQuality.toString(),
            QUALITY_OPTIONS_KEY_XESS to settings.xessQuality.toString(),
        )
        try {
            val kept = file.readLines(StandardCharsets.UTF_8).filterNot { it.substringBefore(':') in entries }
            file.writeText((kept + entries.map { (key, value) -> "$key:$value" })
                .joinToString(System.lineSeparator(), postfix = System.lineSeparator()), StandardCharsets.UTF_8)
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("Could not write SR options to {}", file, failure)
        }
    }
}
