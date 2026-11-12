package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import java.nio.ByteOrder
import java.util.WeakHashMap

class D3D12MeshletResidency : AutoCloseable {
    private val offsets = WeakHashMap<D3D12SectionGeometry.LayerUpload, Int>()
    private var buffer: D3D12GpuBuffer? = null
    private var used = 0

    internal fun prepare(ctx: Long, staging: D3D12TransientMemory,
                         geometries: List<D3D12SectionGeometry.LayerUpload>): D3D12GpuBuffer {
        check(MCDR_ABI.D3D12IsListOpen(ctx)) { "Terrain meshlet upload requires an open command list" }
        val missing = geometries.distinct().filter { it !in offsets }
        val needed = missing.sumOf { it.meshlets.size.toLong() * D3D12MeshletBuilder.MESHLET_DESC_BYTES }
        if (buffer == null || used.toLong() + needed > buffer!!.size()) {
            val live = (offsets.keys.toList() + missing).distinct()
            val bytes = live.sumOf { it.meshlets.size.toLong() * D3D12MeshletBuilder.MESHLET_DESC_BYTES }
            require(bytes <= Int.MAX_VALUE - 15L) { "Terrain meshlet atlas exceeds 2 GB" }
            var capacity = 1024L * 1024L
            while (capacity < bytes) capacity *= 2
            capacity = minOf(capacity, Int.MAX_VALUE - 15L)
            val replacement = D3D12GpuBuffer(GpuBuffer.USAGE_COPY_DST, capacity, "terrain meshlet descriptors")
            val candidateOffsets = mutableMapOf<D3D12SectionGeometry.LayerUpload, Int>()
            var candidateUsed = 0
            try {
                for (geometry in live) {
                    upload(ctx, staging, replacement, candidateUsed, geometry)
                    candidateOffsets[geometry] = candidateUsed / D3D12MeshletBuilder.MESHLET_DESC_BYTES
                    candidateUsed += geometry.meshlets.size * D3D12MeshletBuilder.MESHLET_DESC_BYTES
                }
            } catch (failure: Throwable) {
                replacement.close()
                throw failure
            }
            val previous = buffer
            buffer = replacement
            offsets.clear()
            offsets.putAll(candidateOffsets)
            used = candidateUsed
            previous?.close()
        } else {
            require(used.toLong() + needed <= Int.MAX_VALUE - 15L)
            for (geometry in missing) {
                upload(ctx, staging, buffer!!, used, geometry)
                offsets[geometry] = used / D3D12MeshletBuilder.MESHLET_DESC_BYTES
                used += geometry.meshlets.size * D3D12MeshletBuilder.MESHLET_DESC_BYTES
            }
        }
        return buffer!!
    }

    private fun upload(ctx: Long, staging: D3D12TransientMemory,
                       target: D3D12GpuBuffer, targetOffset: Int,
                       geometry: D3D12SectionGeometry.LayerUpload) {
        val bytes = geometry.meshlets.size.toLong() * D3D12MeshletBuilder.MESHLET_DESC_BYTES
        require(targetOffset >= 0 && bytes <= target.size() - targetOffset)
        val source = staging.writeIntoStagingBlock(bytes, 16L) { destination ->
            destination.order(ByteOrder.LITTLE_ENDIAN)
            for (meshlet in geometry.meshlets) meshlet.pack(destination)
            bytes
        }.getOrThrow().first
        check(MCDR_ABI.D3D12CopyBufferChecked(ctx,
            (source.buffer() as D3D12GpuBuffer).handle(), source.offset(),
            target.handle(), targetOffset.toLong(), bytes)) {
            "Terrain meshlet descriptor upload failed: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    internal fun offset(geometry: D3D12SectionGeometry.LayerUpload): Int = offsets.getValue(geometry)

    override fun close() {
        buffer?.close()
        buffer = null
        offsets.clear()
        used = 0
    }
}
