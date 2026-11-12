package io.github.dxegp.bootstrap.earlywindow.service

import net.neoforged.fml.loading.FMLConfig
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper
import org.slf4j.LoggerFactory
import java.nio.file.Files

class DxrmcGraphicsBootstrapper : GraphicsBootstrapper {
    override fun name(): String = "dxrmc"

    override fun bootstrap(arguments: Array<String>) {
        val legacy = FMLPaths.GAMEDIR.get().resolve("mods/dxrmc-earlywindow.jar")
        if (Files.exists(legacy)) {
            throw IllegalStateException(
                "Illusion now includes its early-window provider. Remove the obsolete $legacy and launch again."
            )
        }
        if (!FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) return
        val selected = FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER)
        when {
            "dxrmc".equals(selected) -> return
            "fmlearlywindow".equals(selected) -> FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, "dxrmc")
            else -> LoggerFactory.getLogger("dxrmc/earlywindow").warn(
                "Illusion early-window handoff is disabled because fml.toml selects provider '{}'; select 'dxrmc' to enable it",
                selected
            )
        }
    }
}
