package io.github.dxegp.d3d12.shader

import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc

enum class D3D12PipelineOrientation {
    ORIGINAL,
    FLIPPED_Y,
}

internal class D3D12PipelineCompilation(
    val pipeline: D3D12CompiledRenderPipeline,
    val shaders: D3D12CompiledShader,
    val vertexSource: String,
    val orientation: D3D12PipelineOrientation,
    val baseDescriptor: MCDRPipelineDesc,
    val createDescriptor: () -> MCDRPipelineDesc,
    val celestialMotionExact: Boolean = false,
    val opaqueSeed: D3D12OpaqueSeedProgram? = null,
    val iaTile: D3D12OitTileIaProgram? = null,
) {
    val tileRoute: D3D12OitTileCompilation get() = shaders.oitTile
}

internal interface D3D12PipelineVariants : AutoCloseable {
    fun compile(compilation: D3D12PipelineCompilation)
    fun validate(pipeline: D3D12CompiledRenderPipeline)
}
