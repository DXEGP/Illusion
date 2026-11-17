package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.pipeline.RenderTarget
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.presentation.D3D12GUISplit
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg

@Mixin(Minecraft::class)
abstract class MinecraftDisplayFrameMixin {
    @WrapMethod(method = ["renderFrame"], remap = false)
    private fun illusionRetireUnpresentedFrame(advanceGameTime: Boolean, operation: Operation<Any?>) {
        try {
            operation.call(advanceGameTime)
        } catch (failure: Throwable) {
            VanillaFrameCoordinator.abortWorldRender(failure)
            throw failure
        } finally {
            VanillaFrameCoordinator.retireUnpresentedFrame()
        }
    }

    @ModifyArg(
        method = ["renderFrame"],
        at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/TracyFrameCapture;capture(Lcom/mojang/blaze3d/pipeline/RenderTarget;)V")],
        index = 0,
        remap = false,
    )
    private fun illusionCaptureCompletedDisplay(original: RenderTarget): RenderTarget =
        D3D12GUISplit.completedDisplayTarget() ?: original
}
