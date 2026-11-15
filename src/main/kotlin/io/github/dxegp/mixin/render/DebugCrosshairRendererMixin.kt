package io.github.dxegp.mixin.render

import net.minecraft.client.renderer.DebugCrosshairRenderer
import org.spongepowered.asm.mixin.Mixin

@Mixin(DebugCrosshairRenderer::class)
abstract class DebugCrosshairRendererMixin

