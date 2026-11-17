package io.github.dxegp.mixin.antialiasing

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.state.GameRenderState
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
abstract class GameRendererSmaaWorldMixin {
    @Shadow
    @Final
    private lateinit var gameRenderState: GameRenderState

    @WrapMethod(method = ["render"], remap = false)
    private fun illusionDisplayFrame(operation: Operation<Any?>) {
        try {
            VanillaFrameCoordinator.beginDisplayFrame(gameRenderState)
            operation.call()
        } catch (failure: Throwable) {
            VanillaFrameCoordinator.abortWorldRender(failure)
            throw failure
        }
    }

    @WrapMethod(method = ["renderLevel"], remap = false)
    private fun illusionAbortFailedDisplayScene(operation: Operation<Any?>) {
        try {
            operation.call()
        } catch (failure: Throwable) {
            VanillaFrameCoordinator.abortWorldRender(failure)
            throw failure
        }
    }

    @Inject(
        method = ["renderLevel"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V")],
        remap = false,
    )
    private fun illusionCompleteWorldBeforeHud(callback: CallbackInfo) {
        VanillaFrameCoordinator.beforeDisplayScene()
    }

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
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V", shift = At.Shift.AFTER)],
        remap = false,
    )
    private fun illusionAfterGui(callback: CallbackInfo) {
        VanillaFrameCoordinator.afterGui()
    }
}
