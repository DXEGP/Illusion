package io.github.dxegp.mixin.render

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import io.github.dxegp.d3d12.backend.D3D12PipelineBridge
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Mixin(FrontendGpuDevice::class)
abstract class FrontendGpuDeviceCompilePipelineMixin {
    @Inject(
        method = ["compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;" +
            "Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)" +
            "Ljava/util/concurrent/CompletableFuture;"],
        at = [At("HEAD")]
    )
    private fun `dxr_mc$registerPipeline`(
        pipeline: RenderPipeline, shaderSource: ShaderSource, executor: Executor,
        callback: CallbackInfoReturnable<CompletableFuture<CompiledRenderPipeline.Pending>>
    ) {
        D3D12PipelineBridge.register(pipeline, shaderSource)
    }
}

