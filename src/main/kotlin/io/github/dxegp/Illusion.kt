package io.github.dxegp

import io.github.dxegp.config.IllusionConfig

import com.mojang.logging.LogUtils
import io.github.dxegp.Illusion.Companion.OPTIONS_KEY_PREFERRED_GRAPHICS
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.backend.D3D12GraphicsApi
import io.github.dxegp.diagnostics.D3D12VrsDebugEntry
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.render.vrs.D3D12VariableShadingRate
import io.github.dxegp.diagnostics.IllusionLogger
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.fml.loading.FMLPaths

@Mod(Illusion.MODID)
class Illusion(modEventBus: IEventBus, modContainer: ModContainer) {
    companion object {
        const val MODID = "dxr_mc"
        const val CLASSPATH = "io.github.dxegp"
        const val NATIVE_NAME = "MCDR"
        const val MOD_NAME = "Illusion"

        /** `options.txt` key under which vanilla `Options` persists the chosen graphics backend. */
        private const val OPTIONS_KEY_PREFERRED_GRAPHICS = "preferredGraphicsBackend"

        @JvmField
        val LOGGER = LogUtils.getLogger()
    }

    init {
        modContainer.registerConfig(ModConfig.Type.CLIENT, IllusionConfig.SPEC)
        D3D12VrsDebugEntry.register(modEventBus)
        D3D12VariableShadingRate.register()
        IllusionLogger.applyDefaults()
        modEventBus.addListener<ModConfigEvent.Loading> {
            IllusionLogger.applyFromConfig()
            D3D12Settings.reload()
            D3D12Settings.pushToNative()
            applyGpuCaptureConfig()
        }
        modEventBus.addListener<ModConfigEvent.Reloading> {
            IllusionLogger.applyFromConfig()
            D3D12Settings.reload()
            D3D12Settings.pushToNative()
            applyGpuCaptureConfig()
        }
        LOGGER.info("[$MODID] $MOD_NAME is constructing...")
        val preferredGraphics = preferredGraphicsSettingFromOptionsFile()
        LOGGER.info(
            if (preferredGraphics == D3D12GraphicsApi.SERIALIZED_NAME) {
                "[$NATIVE_NAME] $MODID is currently using Direct3D Feature Level ${startupFeatureLevel()}."
            } else {
                "[$MODID] Preferred graphics setting is ${preferredGraphics ?: "default"}, so $NATIVE_NAME will not be initialized."
            }
        )
    }

    /**
     * Direct3D feature level the game is going to run on, as far as it can be known this early — the live device does
     * not exist yet at mod construction, so the native side enumerates adapters instead of asking
     * [D3D12AdapterInfo]. Returns `"unknown"` when the native library is unavailable or the query fails; this line is
     * diagnostics and must never break mod construction.
     */
    private fun startupFeatureLevel(): String {
        D3D12AdapterInfo.current?.let { return@let it.featureLevel }
        if (!MCDR_ABI.isAvailable()) {
            return "unknown"
        }
        return runCatching {
            val adapters = D3D12AdapterInfo.parseList(MCDR_ABI.ID3D12EnumerateAdapters())
            (adapters.firstOrNull { it.featureLevel == "12_2" } ?: adapters.firstOrNull())?.featureLevel
        }.getOrNull() ?: "unknown"
    }

    /**
     * The graphics backend the player selected, as saved in `<game dir>/options.txt`. Mod construction runs before the
     * client exists (`Minecraft.getInstance()` throws, and the appended `PreferredGraphicsApi` constant is not
     * installed until `Options` touches the enum), and vanilla writes the choice under
     * [OPTIONS_KEY_PREFERRED_GRAPHICS] through the enum codec, so the value is **quoted**
     * (`preferredGraphicsBackend:"d3d12"`). Returns `null` when the key is absent.
     */
    private fun preferredGraphicsSettingFromOptionsFile(): String? {
        val file = FMLPaths.GAMEDIR.get().resolve("options.txt").toFile()
        if (!file.isFile) {
            return null
        }
        return try {
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("$OPTIONS_KEY_PREFERRED_GRAPHICS:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            LOGGER.warn("[$MODID] could not read $file ($t); assuming the vanilla default graphics setting")
            null
        }
    }

    /**
     * Push `[debug] pixCapture` / `[debug] pixTimingCapture` to the native side. PIX hooks Direct3D 12 from inside its
     * own DLL, so the layer has to load before the first device exists; a self-driven timing capture needs the same
     * layer (the ETW half of PIX), so either switch reports `true` to native. Both options carry `gameRestart()`,
     * so changing them asks for a restart instead of silently doing nothing.
     */
    private fun applyGpuCaptureConfig() {
        val enabled = IllusionConfig.PIX_CAPTURE.get() || IllusionConfig.PIX_TIMING_CAPTURE.get()
        val status = MCDR_ABI.D3D12SetPIXCapture(enabled)
        when (status) {
            1 -> LOGGER.info("[$MODID] PIX GPU capture layer loaded (PIX must be running to save a capture).")

            0 if enabled -> LOGGER.info("[$MODID] PIX GPU capture requested; the layer loads before the first device.")
            0 -> LOGGER.info("[$MODID] PIX GPU capture disabled ([debug] pixCapture = false).")
            Int.MIN_VALUE -> LOGGER.info("[$MODID] PIX GPU capture unavailable: the loaded $NATIVE_NAME.dll has no mcdrSetPixCapture.")
            else -> LOGGER.warn("[$MODID] PIX GPU capture is on but WinPixGpuCapturer.dll could not be loaded; see the [$NATIVE_NAME] ERROR line for the paths that were searched, or set MCDR_PIX_CAPTURER.")
        }
    }
}
