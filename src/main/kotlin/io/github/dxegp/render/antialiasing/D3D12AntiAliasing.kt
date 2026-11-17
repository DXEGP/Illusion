package io.github.dxegp.render.antialiasing

import com.mojang.serialization.Codec
import io.github.dxegp.Illusion
import io.github.dxegp.config.SrBackend
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.render.config.RenderConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component

object D3D12AntiAliasing {
    const val OFF = 0
    const val SMAA = 1
    const val TAA = 2
    const val DLAA = 3
    const val OPTIONS_KEY: String = "dxrMcAaMode"

    @JvmField
    val MODES: List<Int> = listOf(OFF, SMAA, TAA, DLAA)

    private var synchronizing = false

    private val OPTION: OptionInstance<Int> = OptionInstance(
        "options.antiAliasing",
        { Tooltip.create(Component.translatable("options.antiAliasing.tooltip")) },
        { _, value -> Component.translatable("options.antiAliasing.mode.$value") },
        OptionInstance.Enum<Int>(MODES, Codec.INT),
        OFF,
        { value ->
            RenderConfiguration.requestAntiAliasing(value)
            if (!synchronizing) Minecraft.getInstance().options.setGraphicsPresetToCustom()
        },
    )

    @JvmStatic
    fun option(): OptionInstance<Int> = OPTION

    @JvmStatic
    fun current(): Int = RenderConfiguration.applied().settings.antiAliasing

    @JvmStatic
    fun isAvailable(): Boolean = D3D12AdapterInfo.current != null

    @JvmStatic
    fun isLockedByDlss(): Boolean = RenderConfiguration.applied().settings.let {
        it.srBackend == SrBackend.DLSS && it.antiAliasing != DLAA
    }

    @JvmStatic
    fun applyFromPreset(serializedName: String) {
        if (!isAvailable() || isLockedByDlss()) return
        val mode = when (serializedName) {
            "fast" -> OFF
            "fancy" -> SMAA
            "fabulous" -> TAA
            else -> return
        }
        RenderConfiguration.requestAntiAliasing(mode)
    }

    fun synchronizeConfiguration(settings: RenderConfiguration.Settings) {
        synchronizing = true
        try {
            OPTION.set(settings.antiAliasing)
        } finally {
            synchronizing = false
        }
        val screen = Minecraft.getInstance().gui.screen()
        if (screen is OptionsSubScreen) screen.resetOption(OPTION)
    }

    @JvmStatic
    fun load(file: File) {
        if (!file.isFile) return
        try {
            val line = file.readLines(StandardCharsets.UTF_8).firstOrNull {
                it.startsWith("$OPTIONS_KEY:")
            } ?: return
            val mode = line.substring(OPTIONS_KEY.length + 1).trim().toIntOrNull() ?: return
            if (mode in MODES) RenderConfiguration.requestAntiAliasing(mode)
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("could not read {} from {}", OPTIONS_KEY, file, failure)
        }
    }

    @JvmStatic
    fun save(file: File) {
        if (!file.isFile) return
        try {
            val kept = file.readLines(StandardCharsets.UTF_8)
                .filterNot { it.startsWith("$OPTIONS_KEY:") }
            val eol = System.lineSeparator()
            val mode = RenderConfiguration.settingsToSave().antiAliasing
            file.writeText((kept + "$OPTIONS_KEY:$mode").joinToString(eol) + eol,
                StandardCharsets.UTF_8)
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("could not write {} to {}", OPTIONS_KEY, file, failure)
        }
    }
}
