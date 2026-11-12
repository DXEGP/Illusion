package io.github.dxegp.mixin.antialiasing

import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
abstract class GameRendererSmaaWorldMixin {
    @Inject(
        method = ["render"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V")],
        remap = false,
    )
    private fun illusionBeforeGui(callback: CallbackInfo) {
        VanillaFrameCoordinator.beforeGui()
    }

    @Inject(
        method = ["render"],
        at = [At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
            shift = At.Shift.BEFORE,
        )],
        remap = false,
    )
    private fun illusionBeforeDepthClear(callback: CallbackInfo) {
        VanillaFrameCoordinator.beforeDepthClear()
    }
}
