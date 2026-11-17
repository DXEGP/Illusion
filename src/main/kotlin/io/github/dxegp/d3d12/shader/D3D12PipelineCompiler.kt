package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend

interface D3D12PipelineReload

interface FrontendReloadInstall {
    fun install()
    fun restore()
    fun closeRetired()
}

class D3D12PipelineReloadRequest(
    val source: ShaderSource,
    required: List<RenderPipeline>,
    optional: List<RenderPipeline>,
    val request: Long,
) {
    val required: List<RenderPipeline> = java.util.List.copyOf(required)
    val optional: List<RenderPipeline> = java.util.List.copyOf(optional)
}

interface D3D12PipelineCompiler : AutoCloseable {
    fun requestPipelineReload(): Long
    fun beginPipelineReload(request: D3D12PipelineReloadRequest): D3D12PipelineReload
    fun completePipelineReload(reload: D3D12PipelineReload)
    fun abortPipelineReload(reload: D3D12PipelineReload)
    fun abortPipelineReloadRequest(request: Long)
    fun validatePipelineReload(source: ShaderSource)
    fun hasPublishedPipelineCache(): Boolean
    fun preparePipelineReloadPrograms(source: ShaderSource, encoder: D3D12CommandEncoderBackend)
    fun publishPipelineReload(
        source: ShaderSource,
        frontendInventory: Set<RenderPipeline>,
        frontend: FrontendReloadInstall,
    )
    fun getOrCompilePipeline(
        pipeline: RenderPipeline,
        orientation: D3D12PipelineOrientation,
    ): D3D12CompiledRenderPipeline
    fun compilePipeline(info: BackendRenderPipeline.CreateInfo): BackendRenderPipeline.Pending
    fun compilePipeline(
        info: BackendRenderPipeline.CreateInfo,
        pipeline: RenderPipeline,
        source: ShaderSource?,
    ): BackendRenderPipeline.Pending
}
