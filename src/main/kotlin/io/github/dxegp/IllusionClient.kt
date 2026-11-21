package io.github.dxegp

import io.github.dxegp.Illusion.Companion.MODID
import io.github.dxegp.Illusion.Companion.MOD_NAME
import io.github.dxegp.diagnostics.D3D12PixCaptureController
import io.github.dxegp.diagnostics.D3D12ExitWatchdog
import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.render.config.RenderConfiguration
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

/**
 * Client-only half of the mod. `@Mod(dist = CLIENT)` makes FML skip this class on a dedicated server, so the
 * client-only classes it references are never touched server-side.
 */
@Mod(value = MODID, dist = [Dist.CLIENT])
class IllusionClient(container: ModContainer) {
    init {
        IllusionLogger.applyDefaults()
        RenderConfiguration.initialize()
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { modContainer, parent -> ConfigurationScreen(modContainer, parent) }
        )

        Illusion.LOGGER.info("[$MODID] $MOD_NAME Client setup: D3D12 backend is registered via PreferredGraphicsApiMixin; creation is driven by the vanilla backend-selection loop")
        D3D12ExitWatchdog.start()
        container.eventBus?.let {
            D3D12PixCaptureController.register(it)
        }
    }
}
