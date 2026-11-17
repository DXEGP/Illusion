package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.frontend.FrontendRenderPass
import com.mojang.renderpearl.frontend.FrontendRenderPipeline
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import org.spongepowered.asm.mixin.Mixin

@Mixin(FrontendRenderPass::class)
abstract class FrontendOitRenderPassMixin {
    @WrapMethod(method = ["setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"], remap = false)
    private fun illusionRecordPipeline(pipeline: com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline,
        original: Operation<Void?>) {
        original.call(pipeline)
        val frontend = pipeline as? FrontendRenderPipeline ?: return
        OitProducerCapture.recordPipeline(this, frontend.name())
    }

    @WrapMethod(method = ["setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V"], remap = false)
    private fun illusionRecordTexture(name: String, view: GpuTextureView, sampler: GpuSampler,
        original: Operation<Void?>) {
        original.call(name, view, sampler)
        OitProducerCapture.recordTextureBinding(this, name, view, sampler)
    }

    @WrapMethod(method = ["close()V"], remap = false)
    private fun illusionRetirePass(original: Operation<Void?>) {
        try {
            original.call()
        } catch (failure: Throwable) {
            OitProducerCapture.recordPassFailure()
            throw failure
        } finally {
            OitProducerCapture.retirePass(this)
        }
    }
}
