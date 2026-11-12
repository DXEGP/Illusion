package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.ShaderType

class D3D12IntermediaryShaderModule(
    val filename: String,
    val source: String,
    val stage: ShaderType,
    val conversion: D3D12ShaderConversion?,
) : AutoCloseable {
    override fun close() = Unit

    companion object {
        val INVALID = D3D12IntermediaryShaderModule("invalid", "", ShaderType.VERTEX, null)
    }
}
