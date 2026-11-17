package io.github.dxegp.mixin.backend

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.backend.api.GpuDeviceBackend
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder
import io.github.dxegp.d3d12.device.D3D12Device
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(PipelineBuilder::class)
abstract class PipelineBuilderSourceMixin {
    private companion object {
        @JvmStatic
        @WrapOperation(method = ["lambda\$compilePipeline\$0"], remap = false,
            at = [At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/api/GpuDeviceBackend;compilePipeline(Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline\$CreateInfo;)Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline\$Pending;")])
        private fun illusionCompileWithSource(
            backend: GpuDeviceBackend,
            info: BackendRenderPipeline.CreateInfo,
            original: Operation<BackendRenderPipeline.Pending>,
            @Local(argsOnly = true) pipeline: RenderPipeline,
            @Local(argsOnly = true) source: ShaderSource,
        ): BackendRenderPipeline.Pending = if (backend is D3D12Device)
            backend.pipelines.compilePipeline(info, pipeline, source) else original.call(backend, info)
    }
}
