package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView

internal class D3D12BindingPacket(val entries: List<D3D12BindGroupEntry>) {
    private val slotsByName = entries.indices.groupBy { entries[it].name }
    val uniformValues = arrayOfNulls<GpuBufferSlice>(entries.size)
    val textureValues = arrayOfNulls<D3D12GpuTextureView>(entries.size)
    val types = IntArray(entries.size) { entries[it].type.ordinal }
    val buffers = LongArray(entries.size)
    val offsets = LongArray(entries.size)
    val lengths = LongArray(entries.size)
    val texelFormats = IntArray(entries.size) {
        val entry = entries[it]
        if (entry.rawBuffer) -1 else entry.texelBufferFormat?.ordinal ?: 0
    }
    val views = LongArray(entries.size)

    fun setUniform(name: String, value: GpuBufferSlice?) {
        for (slot in slotsByName[name].orEmpty()) uniformValues[slot] = value
    }

    fun setTexture(name: String, value: D3D12GpuTextureView?) {
        for (slot in slotsByName[name].orEmpty()) textureValues[slot] = value
    }
}
