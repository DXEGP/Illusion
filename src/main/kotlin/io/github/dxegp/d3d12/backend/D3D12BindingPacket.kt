package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView

internal class D3D12BindingLayout(
    entries: List<D3D12BindGroupEntry>,
    overrideSlots: IntArray? = null,
) {
    val entries: List<D3D12BindGroupEntry> = java.util.List.copyOf(entries)
    private val overrideSlots = overrideSlots?.copyOf()
    private val firstSlotsByName: Map<String, Int>
    private val nextSlots = IntArray(entries.size) { -1 }

    init {
        val firstSlots = HashMap<String, Int>()
        val lastSlots = HashMap<String, Int>()
        for (slot in this.entries.indices) {
            val name = this.entries[slot].name
            val previous = lastSlots.put(name, slot)
            if (previous == null) firstSlots[name] = slot else nextSlots[previous] = slot
        }
        firstSlotsByName = firstSlots
    }

    fun firstSlot(name: String): Int = firstSlotsByName[name] ?: -1

    fun nextSlot(slot: Int): Int = nextSlots[slot]

    fun copyOverrideSlots(): IntArray? = overrideSlots?.copyOf()
}

internal class D3D12BindingPacket(private val layout: D3D12BindingLayout) {
    val entries: List<D3D12BindGroupEntry> get() = layout.entries
    val overrideSlots: IntArray? = layout.copyOverrideSlots()
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
        var slot = layout.firstSlot(name)
        while (slot >= 0) {
            uniformValues[slot] = value
            slot = layout.nextSlot(slot)
        }
    }

    fun setTexture(name: String, value: D3D12GpuTextureView?) {
        var slot = layout.firstSlot(name)
        while (slot >= 0) {
            textureValues[slot] = value
            slot = layout.nextSlot(slot)
        }
    }
}
