package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.api.GpuFormat
data class D3D12BindGroupEntry(
    val type: Type,
    val name: String,
    val texelBufferFormat: GpuFormat?,
    val rawBuffer: Boolean = false,
) {
    enum class Type {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }
}
