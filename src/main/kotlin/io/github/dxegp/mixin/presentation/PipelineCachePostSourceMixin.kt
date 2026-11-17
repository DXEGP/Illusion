package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.pipeline.PipelineCache
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import io.github.dxegp.render.presentation.display.DisplayPostSources
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow

@Mixin(PipelineCache::class)
abstract class PipelineCachePostSourceMixin {
    @Shadow @Final private lateinit var shaderSource: ShaderSource

    @WrapMethod(method = ["get"])
    private fun illusionRecordPostSource(pipeline: RenderPipeline,
        original: Operation<CompiledRenderPipeline?>): CompiledRenderPipeline? {
        val compiled = original.call(pipeline) ?: return null
        DisplayPostSources.recordCompiledSource(pipeline, compiled, shaderSource)
        return compiled
    }
}
