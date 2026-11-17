package io.github.dxegp.render.terrain.mesh

import com.mojang.blaze3d.vertex.TlsfAllocator
import com.mojang.blaze3d.vertex.UberGpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class TerrainGeometryReadFailure(message: String) : IllegalStateException(message) {
    class MissingAllocation(message: String) : TerrainGeometryReadFailure(message)
    class RangeMismatch(message: String) : TerrainGeometryReadFailure(message)
    class RetiredAllocation(message: String) : TerrainGeometryReadFailure(message)
}

interface TerrainGeometryAllocationOwner {
    val terrainGeometryAllocations: TerrainGeometryAllocations?
    fun attachTerrainGeometryAllocations(lock: ReentrantLock): TerrainGeometryAllocations
    val terrainGeometryBufferUsage: Int
}

interface TerrainGeometryExtractionSource {
    fun captureTerrainGeometryReads(mesh: SectionMesh, layer: ChunkSectionLayer,
        slice: SectionRenderDispatcher.RenderSectionBufferSlice, customIndexBytes: Long): TerrainGeometryAllocations.DrawReads?
}

class TerrainGeometryAllocations(private val lock: ReentrantLock) {
    internal val identity = nextOwnerIdentity.incrementAndGet()
    private class Heap(val buffer: GpuBuffer) {
        val allocations = IdentityHashMap<TlsfAllocator.Allocation, Allocation>()
        var closeRequested = false
    }

    class Allocation internal constructor(
        internal val owner: TerrainGeometryAllocations,
        internal val allocator: TlsfAllocator,
        internal val allocation: TlsfAllocator.Allocation,
        val buffer: GpuBuffer,
        val offset: Long,
        val byteLength: Long,
        val capacity: Long,
        val generation: Long,
    ) {
        internal var references = 0
        internal var retired = false

        fun retainRead(): ReadLease = owner.retainRead(this)

        fun requireRange(actualBuffer: GpuBuffer, actualOffset: Long, length: Long) {
            if (buffer !== actualBuffer || offset != actualOffset || length <= 0L || length > byteLength) {
                throw TerrainGeometryReadFailure.RangeMismatch(
                    "Terrain allocation $generation expected buffer/offset/length ${System.identityHashCode(buffer)}/$offset/$byteLength, " +
                        "received ${System.identityHashCode(actualBuffer)}/$actualOffset/$length")
            }
        }
    }

    class ReadLease internal constructor(val allocation: Allocation) : AutoCloseable {
        private var closed = false

        override fun close() {
            allocation.owner.releaseRead(this)
        }

        internal fun markClosed(): Boolean {
            if (closed) return false
            closed = true
            return true
        }
    }

    class DrawReads(val vertex: Allocation, val index: Allocation?, val indexByteLength: Long) {
        val allocations: List<Allocation> = if (index == null) listOf(vertex) else listOf(vertex, index)
    }

    class Dispatcher(private val lock: ReentrantLock) {
        private class Layer {
            var vertex: UberGpuBuffer<SectionMesh>? = null
            var index: UberGpuBuffer<SectionMesh>? = null
        }

        private val layers = mutableMapOf<ChunkSectionLayer, Layer>()

        fun attachBuffer(layer: ChunkSectionLayer, buffer: UberGpuBuffer<SectionMesh>) {
            val adapter = buffer as TerrainGeometryAllocationOwner
            adapter.attachTerrainGeometryAllocations(lock)
            val buffers = layers.getOrPut(layer) { Layer() }
            when (adapter.terrainGeometryBufferUsage) {
                GpuBuffer.USAGE_VERTEX -> buffers.vertex = buffer
                GpuBuffer.USAGE_INDEX -> buffers.index = buffer
                else -> error("Unexpected terrain heap usage ${adapter.terrainGeometryBufferUsage}")
            }
        }

        fun captureReads(mesh: SectionMesh, layer: ChunkSectionLayer,
            slice: SectionRenderDispatcher.RenderSectionBufferSlice, customIndexBytes: Long): DrawReads? {
            if (slice.vertexBuffer() !is D3D12GpuBuffer) return null
            check(lock.isHeldByCurrentThread) { "Terrain extraction must hold dispatcher copyLock" }
            val buffers = layers[layer] ?: throw TerrainGeometryReadFailure.MissingAllocation("No terrain heaps for $layer")
            val vertex = findAllocation(buffers.vertex, mesh, layer, "vertex")
            vertex.requireRange(slice.vertexBuffer(), slice.vertexBufferOffset(), vertex.byteLength)
            val index = if (customIndexBytes > 0L) {
                val allocation = findAllocation(buffers.index, mesh, layer, "index")
                val buffer = slice.indexBuffer()
                    ?: throw TerrainGeometryReadFailure.MissingAllocation("No custom index buffer for $layer")
                allocation.requireRange(buffer, slice.indexBufferOffset(), customIndexBytes)
                allocation
            } else null
            return DrawReads(vertex, index, customIndexBytes)
        }

        private fun findAllocation(buffer: UberGpuBuffer<SectionMesh>?, mesh: SectionMesh,
            layer: ChunkSectionLayer, role: String): Allocation {
            val source = buffer ?: throw TerrainGeometryReadFailure.MissingAllocation("No $role heap for $layer")
            val allocation = source.getAllocation(mesh)
                ?: throw TerrainGeometryReadFailure.MissingAllocation("No $role allocation for $layer mesh ${System.identityHashCode(mesh)}")
            return (source as TerrainGeometryAllocationOwner).terrainGeometryAllocations?.requireAllocation(allocation)
                ?: throw TerrainGeometryReadFailure.MissingAllocation("No $role allocation owner for $layer")
        }
    }

    private val heaps = IdentityHashMap<GpuBuffer, Heap>()
    private val allocations = IdentityHashMap<TlsfAllocator.Allocation, Allocation>()
    private var retired = false

    fun reserveUpload(allocator: TlsfAllocator, allocation: TlsfAllocator.Allocation,
        buffer: GpuBuffer, byteLength: Long): Allocation? = lock.withLock {
        if (buffer !is D3D12GpuBuffer) return@withLock null
        check(!retired && !allocation.isFreed) { "Cannot publish retired terrain geometry" }
        require(byteLength > 0L && byteLength <= allocation.size)
        check(!allocations.containsKey(allocation)) { "Terrain allocation was published twice" }
        val heap = heaps.getOrPut(buffer) { Heap(buffer) }
        check(!heap.closeRequested) { "Cannot upload into a closing terrain heap" }
        val fact = Allocation(this, allocator, allocation, buffer, allocation.offsetFromHeap,
            byteLength, allocation.size, nextGeneration.incrementAndGet())
        allocations[allocation] = fact
        heap.allocations[allocation] = fact
        fact
    }

    fun requireAllocation(allocation: TlsfAllocator.Allocation): Allocation = lock.withLock {
        allocations[allocation] ?: throw TerrainGeometryReadFailure.MissingAllocation(
            "Unregistered terrain allocation at ${allocation.offsetFromHeap}")
    }

    fun retireAllocation(allocation: TlsfAllocator.Allocation): Boolean = lock.withLock {
        val fact = allocations[allocation] ?: return@withLock false
        fact.retired = true
        if (fact.references == 0) freeAllocation(fact)
        true
    }

    fun retireAll() = lock.withLock {
        retired = true
        for (fact in allocations.values.toList()) {
            fact.retired = true
            if (fact.references == 0) freeAllocation(fact)
        }
    }

    fun closeHeap(buffer: GpuBuffer): Boolean = lock.withLock {
        val heap = heaps[buffer] ?: return@withLock false
        heap.closeRequested = true
        closeRetiredHeap(heap)
        true
    }

    private fun retainRead(allocation: Allocation): ReadLease = lock.withLock {
        if (allocation.retired && allocation.references == 0 || !allocations.containsKey(allocation.allocation)) {
            throw TerrainGeometryReadFailure.RetiredAllocation("Terrain allocation ${allocation.generation} has retired")
        }
        allocation.references = Math.incrementExact(allocation.references)
        ReadLease(allocation)
    }

    private fun releaseRead(lease: ReadLease) = lock.withLock {
        if (!lease.markClosed()) return@withLock
        val allocation = lease.allocation
        check(allocation.references > 0)
        allocation.references--
        if (allocation.references == 0 && allocation.retired) freeAllocation(allocation)
    }

    private fun freeAllocation(allocation: Allocation) {
        allocation.allocator.free(allocation.allocation)
        allocations.remove(allocation.allocation)
        val heap = checkNotNull(heaps[allocation.buffer])
        heap.allocations.remove(allocation.allocation)
        closeRetiredHeap(heap)
    }

    private fun closeRetiredHeap(heap: Heap) {
        if (heap.closeRequested && heap.allocations.isEmpty()) {
            heap.buffer.close()
            heaps.remove(heap.buffer)
        }
    }

    private companion object {
        val nextGeneration = AtomicLong()
        val nextOwnerIdentity = AtomicLong()
    }
}
