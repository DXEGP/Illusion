package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import java.nio.ByteOrder
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

class D3D12MeshletResidency : AutoCloseable {
    internal data class DescriptorRange(val descriptorOffset: Int, val descriptorCount: Int) {
        val byteOffset: Long get() = descriptorOffset.toLong() * D3D12MeshletBuilder.MESHLET_DESC_BYTES
        val byteLength: Long get() = descriptorCount.toLong() * D3D12MeshletBuilder.MESHLET_DESC_BYTES
    }

    internal class PreparedDescriptors private constructor(
        val buffer: D3D12GpuBuffer,
        val generation: Long,
        val ranges: Map<D3D12SectionGeometry.LayerUpload, DescriptorRange>,
    ) {
        fun offset(geometry: D3D12SectionGeometry.LayerUpload): Int = ranges.getValue(geometry).descriptorOffset

        companion object {
            fun capture(buffer: D3D12GpuBuffer, generation: Long,
                geometries: List<D3D12SectionGeometry.LayerUpload>,
                offsets: Map<D3D12SectionGeometry.LayerUpload, Int>): PreparedDescriptors {
                val ranges = LinkedHashMap<D3D12SectionGeometry.LayerUpload, DescriptorRange>(geometries.size)
                for (geometry in geometries) {
                    ranges[geometry] = DescriptorRange(offsets.getValue(geometry), geometry.meshlets.size)
                }
                return PreparedDescriptors(buffer, generation, Collections.unmodifiableMap(ranges))
            }
        }
    }

    private class MeshletBufferGeneration(val buffer: D3D12GpuBuffer) : AutoCloseable {
        var identity = 0L
            private set
        private var reads = 0
        private var retired = false
        private var closed = false

        private class ReadLease(private val generation: MeshletBufferGeneration) : AutoCloseable {
            private var closed = false

            @Synchronized
            override fun close() {
                if (closed) return
                closed = true
                generation.releaseRead()
            }
        }

        fun publish() {
            check(identity == 0L && !retired) { "Terrain meshlet descriptor generation was already published" }
            identity = nextGeneration.incrementAndGet()
        }

        fun retainRead(staging: D3D12TransientMemory) {
            staging.retainOriginalDrawRead(this) {
                synchronized(this) {
                    check(!retired && !closed) { "Terrain meshlet descriptor generation is retired" }
                    reads = Math.incrementExact(reads)
                    ReadLease(this)
                }
            }
        }

        @Synchronized
        private fun releaseRead() {
            check(reads > 0) { "Terrain meshlet descriptor read lease was released twice" }
            reads--
            closeRetiredBuffer()
        }

        @Synchronized
        override fun close() {
            retired = true
            closeRetiredBuffer()
        }

        private fun closeRetiredBuffer() {
            if (retired && reads == 0 && !closed) {
                closed = true
                buffer.close()
            }
        }
    }

    private var offsets = WeakHashMap<D3D12SectionGeometry.LayerUpload, Int>()
    private var generation: MeshletBufferGeneration? = null
    private var used = 0
    private var closed = false

    @Synchronized
    internal fun prepare(ctx: Long, staging: D3D12TransientMemory,
                         geometries: List<D3D12SectionGeometry.LayerUpload>): PreparedDescriptors {
        check(!closed) { "Terrain meshlet residency is closed" }
        val current = generation
        var missing: ArrayList<D3D12SectionGeometry.LayerUpload>? = null
        var needed = 0L
        for (geometry in geometries) {
            if (geometry in offsets) continue
            if (missing == null) missing = ArrayList()
            missing.add(geometry)
            needed += geometry.meshlets.size.toLong() * D3D12MeshletBuilder.MESHLET_DESC_BYTES
        }
        if (missing == null && current != null) {
            current.retainRead(staging)
            return PreparedDescriptors.capture(current.buffer, current.identity, geometries, offsets)
        }
        val additions = missing ?: emptyList()
        if (current == null || used.toLong() + needed > current.buffer.size()) {
            val live = ArrayList<D3D12SectionGeometry.LayerUpload>(offsets.size + additions.size)
            live.addAll(offsets.keys)
            live.addAll(additions)
            var bytes = 0L
            val candidateOffsets = WeakHashMap<D3D12SectionGeometry.LayerUpload, Int>(live.size)
            var candidateUsed = 0
            for (geometry in live) {
                candidateOffsets[geometry] = candidateUsed / D3D12MeshletBuilder.MESHLET_DESC_BYTES
                val geometryBytes = descriptorBytes(geometry)
                bytes += geometryBytes
                require(bytes <= Int.MAX_VALUE - 15L) { "Terrain meshlet atlas exceeds 2 GB" }
                candidateUsed += geometryBytes
            }
            var capacity = 1024L * 1024L
            while (capacity < bytes) capacity *= 2
            capacity = minOf(capacity, Int.MAX_VALUE - 15L)
            val replacement = MeshletBufferGeneration(
                D3D12GpuBuffer(GpuBuffer.USAGE_COPY_DST, capacity, "terrain meshlet descriptors"))
            try {
                replacement.retainRead(staging)
                upload(ctx, staging, replacement.buffer, 0, live, bytes)
            } catch (failure: Throwable) {
                replacement.close()
                throw failure
            }
            replacement.publish()
            generation = replacement
            offsets = candidateOffsets
            used = candidateUsed
            current?.close()
        } else {
            require(used.toLong() + needed <= Int.MAX_VALUE - 15L)
            current.retainRead(staging)
            upload(ctx, staging, current.buffer, used, additions, needed)
            for (geometry in additions) {
                offsets[geometry] = used / D3D12MeshletBuilder.MESHLET_DESC_BYTES
                used += descriptorBytes(geometry)
            }
        }
        val prepared = checkNotNull(generation)
        return PreparedDescriptors.capture(prepared.buffer, prepared.identity, geometries, offsets)
    }

    private fun descriptorBytes(geometry: D3D12SectionGeometry.LayerUpload): Int =
        Math.multiplyExact(geometry.meshlets.size, D3D12MeshletBuilder.MESHLET_DESC_BYTES)

    private fun upload(ctx: Long, staging: D3D12TransientMemory,
                       target: D3D12GpuBuffer, targetOffset: Int,
                       geometries: List<D3D12SectionGeometry.LayerUpload>, bytes: Long) {
        if (bytes == 0L) return
        require(targetOffset >= 0 && bytes <= target.size() - targetOffset)
        val source = staging.writeIntoStagingBlock(bytes, 16L) { destination ->
            destination.order(ByteOrder.LITTLE_ENDIAN)
            for (geometry in geometries) {
                for (meshlet in geometry.meshlets) meshlet.pack(destination)
            }
            bytes
        }.getOrThrow().first
        check(MCDR_ABI.D3D12CopyBufferChecked(ctx,
            (source.buffer() as D3D12GpuBuffer).handle(), source.offset(),
            target.handle(), targetOffset.toLong(), bytes)) {
            "Terrain meshlet descriptor upload failed: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        generation?.close()
        generation = null
        offsets.clear()
        used = 0
    }

    private companion object {
        val nextGeneration = AtomicLong()
    }
}
