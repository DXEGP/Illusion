package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.frontend.FrontendCommandEncoder
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import org.spongepowered.asm.mixin.Mixin

@Mixin(FrontendCommandEncoder::class)
abstract class FrontendOitCommandEncoderMixin {
    @WrapMethod(method = ["createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;"], remap = false)
    private fun illusionRecordPass(descriptor: RenderPassDescriptor, original: Operation<RenderPass>): RenderPass {
        var pass: RenderPass? = null
        try {
            pass = original.call(descriptor)
            OitProducerCapture.recordPass(pass, descriptor)
            return pass
        } catch (failure: Throwable) {
            OitProducerCapture.recordPassFailure()
            try { pass?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
