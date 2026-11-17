package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import net.minecraft.client.renderer.DynamicGpuData
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.Collections
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12UploadRead
import io.github.dxegp.d3d12.command.D3D12TerrainPreparedFacts

object D3D12MeshDrawCapture {
    internal data class TerrainBatchFacts(
        val layer: String,
        val drawFacts: List<TerrainDrawFact>,
        val meshletCount: Int,
        val quadsPerMeshlet: Int,
        val primitiveCount: Int,
        val requiredVertexBytes: Long,
    ) {
        init {
            require(layer.isNotEmpty() && drawFacts.isNotEmpty() && meshletCount > 0 &&
                quadsPerMeshlet in setOf(16, 32, 64) && primitiveCount > 0 && requiredVertexBytes > 0L)
            require(drawFacts.sumOf { it.primitiveCount } == primitiveCount)
        }
    }

    internal data class TerrainPreparedFacts(
        val batch: TerrainBatchFacts,
        override val pipeline: Long,
        override val vertexBuffer: Long,
        override val vertexOffset: Long,
        override val bindingHandles: LongArray,
        override val bindingOffsets: LongArray,
        override val bindingLengths: LongArray,
        val meshletDescriptorOffsets: IntArray,
        override val sectionCount: Int,
        override val meshletCount: Int,
        val commandSelections: List<TerrainCommandSelection>,
        val exactReadSet: TerrainExactReadSet,
        val uploadReads: List<TerrainUploadReadFact>,
    ) : D3D12TerrainPreparedFacts {
        init {
            require(pipeline != 0L && vertexBuffer != 0L && vertexOffset >= 0L)
            require(bindingHandles.size == bindingOffsets.size && bindingHandles.size == bindingLengths.size)
            require(bindingHandles.all { it != 0L } && bindingOffsets.all { it >= 0L } && bindingLengths.all { it > 0L })
            require(meshletDescriptorOffsets.size == sectionCount && meshletDescriptorOffsets.all { it >= 0 })
            require(sectionCount == batch.drawFacts.size && meshletCount == batch.meshletCount)
            require(commandSelections.size == sectionCount)
            require(uploadReads.size == bindingHandles.size - 1)
            for (fact in uploadReads) {
                require(fact.preparedBindingIndex in 0 until bindingHandles.lastIndex)
                val read = fact.read
                if (read is D3D12UploadRead.Available) {
                    require(read.bufferHandle == bindingHandles[fact.preparedBindingIndex] &&
                        read.byteOffset == bindingOffsets[fact.preparedBindingIndex] &&
                        read.byteLength == bindingLengths[fact.preparedBindingIndex])
                }
            }
        }

        fun copyImmutable(): TerrainPreparedFacts = TerrainPreparedFacts(batch, pipeline, vertexBuffer,
            vertexOffset, bindingHandles.copyOf(), bindingOffsets.copyOf(), bindingLengths.copyOf(),
            meshletDescriptorOffsets.copyOf(), sectionCount, meshletCount, commandSelections, exactReadSet, uploadReads)
    }

    internal sealed interface TerrainUploadInput {
        data class Uniform(val name: String, val graphicsBindingSlot: Int) : TerrainUploadInput
        data object TerrainGlobals : TerrainUploadInput
        data object TerrainSections : TerrainUploadInput
    }

    internal data class TerrainUploadReadFact(
        val preparedBindingIndex: Int,
        val input: TerrainUploadInput,
        val read: D3D12UploadRead,
    )

    internal data class TerrainGeometryReadFact(
        val commandIndex: Int,
        val allocationGeneration: Long,
        val ownerIdentity: Long,
        val bufferHandle: Long,
        val byteOffset: Long,
        val byteLength: Long,
        val uploadedByteLength: Long,
        val allocationCapacity: Long,
        val baseVertex: Int,
        val indexCount: Int,
        val instanceCount: Int,
        val firstIndex: Int,
        val baseInstance: Int,
        val section: TerrainDrawFact,
    )

    internal data class TerrainMeshletReadFact(
        val commandIndex: Int,
        val generation: Long,
        val bufferHandle: Long,
        val byteOffset: Long,
        val byteLength: Long,
        val descriptorCount: Int,
    )

    internal sealed interface TerrainExactReadSet {
        class Available(geometry: List<TerrainGeometryReadFact>, meshlets: List<TerrainMeshletReadFact>) : TerrainExactReadSet {
            val geometry: List<TerrainGeometryReadFact> = Collections.unmodifiableList(ArrayList(geometry))
            val meshlets: List<TerrainMeshletReadFact> = Collections.unmodifiableList(ArrayList(meshlets))
            init { require(this.geometry.size == this.meshlets.size && this.geometry.isNotEmpty()) }
        }
        data class Unavailable(val reason: Reason, val commandIndex: Int) : TerrainExactReadSet
        enum class Reason { GEOMETRY_OWNER_UNAVAILABLE, GEOMETRY_RANGE_MISMATCH, MESHLET_RANGE_UNAVAILABLE }
    }

    internal data class TerrainCommandSelection(
        val section: TerrainDrawFact,
        val indexCount: Int,
        val instanceCount: Int,
        val firstIndex: Int,
        val baseVertex: Int,
        val baseInstance: Int,
        val visibilityBits: Int,
    )

    private class TerrainOrderedSelection(draws: List<DrawSnapshot>, facts: List<TerrainDrawFact>) :
        AbstractList<TerrainCommandSelection>(), java.util.RandomAccess {
        private val entries = draws.mapIndexed { index, draw ->
            val command = draw.command
            TerrainCommandSelection(facts[index], command.indexCount(), command.instanceCount(),
                command.firstIndex(), command.baseVertex(), command.baseInstance(), draw.section.visibility().toRawBits())
        }
        private val structuralHash = entries.hashCode()

        override val size: Int get() = entries.size

        override fun get(index: Int): TerrainCommandSelection = entries[index]

        override fun hashCode(): Int = structuralHash

        override fun equals(other: Any?): Boolean = this === other || entries == other
    }

    internal data class TerrainDrawFact(
        val sectionX: Int,
        val sectionY: Int,
        val sectionZ: Int,
        val layer: String,
        val geometryGeneration: Long,
        val primitiveBase: Int,
        val primitiveCount: Int,
        val quadCount: Int,
    ) {
        init {
            require(layer.isNotEmpty() && geometryGeneration > 0L && primitiveBase >= 0 &&
                primitiveCount > 0 && primitiveCount % 2 == 0 && quadCount > 0 && primitiveCount == quadCount * 2)
        }
    }

    internal data class DrawSnapshot(
        val command: DynamicGpuData.IndexedDraw,
        val section: DynamicGpuData.ChunkSectionInfo,
        val geometry: D3D12SectionGeometry.LayerUpload,
        val geometryReads: TerrainGeometryAllocations.DrawReads,
    )

    class Batch internal constructor(draws: List<DrawSnapshot>) {
        internal val draws: List<DrawSnapshot> = Collections.unmodifiableList(draws)
        internal val geometries: List<D3D12SectionGeometry.LayerUpload>
        internal val layer: String?
        internal val meshletCount: Int?
        internal val requiredVertexBytes: Long
        val drawCount: Int get() = draws.size
        val quadsPerMeshlet: Int
        internal val terrainDrawFacts: List<TerrainDrawFact>
        internal val primitiveCount: Int
        internal val facts: TerrainBatchFacts?
        private var orderedSelection: List<TerrainCommandSelection>? = null
        private var exactReadSetVertexBuffer: GpuBuffer? = null
        private var exactReadSetVertexOffset = -1L
        private var exactReadSetDescriptorGeneration = 0L
        private var exactReadSetValue: TerrainExactReadSet? = null
        internal val commandSelections: List<TerrainCommandSelection>
            get() = synchronized(this) {
                orderedSelection ?: TerrainOrderedSelection(draws, terrainDrawFacts).also { orderedSelection = it }
            }

        internal fun exactReadSet(vertexBuffer: GpuBuffer, vertexOffset: Long,
            descriptors: D3D12MeshletResidency.PreparedDescriptors): TerrainExactReadSet = synchronized(this) {
            exactReadSetValue?.takeIf { exactReadSetVertexBuffer === vertexBuffer &&
                exactReadSetVertexOffset == vertexOffset && exactReadSetDescriptorGeneration == descriptors.generation
            }?.let { return@synchronized it }
            val geometry = draws.mapIndexed { index, draw ->
                val allocation = draw.geometryReads.vertex
                val offset = Math.addExact(vertexOffset,
                    Math.multiplyExact(draw.command.baseVertex().toLong(), D3D12MeshletBuilder.VERTEX_STRIDE.toLong()))
                try {
                    allocation.requireRange(vertexBuffer, offset, draw.geometry.vertexBytes.toLong())
                } catch (failure: TerrainGeometryReadFailure.RangeMismatch) {
                    return@synchronized TerrainExactReadSet.Unavailable(TerrainExactReadSet.Reason.GEOMETRY_RANGE_MISMATCH, index)
                }
                val buffer = allocation.buffer as? D3D12GpuBuffer
                    ?: return@synchronized TerrainExactReadSet.Unavailable(TerrainExactReadSet.Reason.GEOMETRY_OWNER_UNAVAILABLE, index)
                TerrainGeometryReadFact(index, allocation.generation, allocation.owner.identity,
                    buffer.handle(), offset, draw.geometry.vertexBytes.toLong(), allocation.byteLength,
                    allocation.capacity, draw.command.baseVertex(), draw.command.indexCount(), draw.command.instanceCount(),
                    draw.command.firstIndex(), draw.command.baseInstance(),
                    terrainDrawFacts[index])
            }
            val meshlets = draws.mapIndexed { index, draw ->
                val range = descriptors.ranges[draw.geometry]
                    ?: return@synchronized TerrainExactReadSet.Unavailable(TerrainExactReadSet.Reason.MESHLET_RANGE_UNAVAILABLE, index)
                if (descriptors.generation <= 0L || range.descriptorOffset < 0 ||
                    range.descriptorCount != draw.geometry.meshlets.size || range.byteLength <= 0L ||
                    range.byteOffset > descriptors.buffer.size() || range.byteLength > descriptors.buffer.size() - range.byteOffset) {
                    return@synchronized TerrainExactReadSet.Unavailable(TerrainExactReadSet.Reason.MESHLET_RANGE_UNAVAILABLE, index)
                }
                TerrainMeshletReadFact(index, descriptors.generation, descriptors.buffer.handle(),
                    range.byteOffset, range.byteLength, range.descriptorCount)
            }
            TerrainExactReadSet.Available(geometry, meshlets).also {
                exactReadSetVertexBuffer = vertexBuffer
                exactReadSetVertexOffset = vertexOffset
                exactReadSetDescriptorGeneration = descriptors.generation
                exactReadSetValue = it
            }
        }

        fun retainGeometryReads(staging: D3D12TransientMemory, vertexBuffer: GpuBuffer, vertexOffset: Long) {
            for (draw in draws) {
                val allocation = draw.geometryReads.vertex
                val offset = Math.addExact(vertexOffset,
                    Math.multiplyExact(draw.command.baseVertex().toLong(), D3D12MeshletBuilder.VERTEX_STRIDE.toLong()))
                allocation.requireRange(vertexBuffer, offset, draw.geometry.vertexBytes.toLong())
                transferRead(staging, allocation)
            }
        }

        init {
            val uniqueGeometries = linkedSetOf<D3D12SectionGeometry.LayerUpload>()
            var commonLayer = draws.firstOrNull()?.geometry?.layer
            var maximumQuads = 0
            var meshletTotal = 0L
            var vertexBytes = 0L
            var valid = drawCount <= 65535 * 64
            var primitiveBase = 0
            val drawFacts = ArrayList<TerrainDrawFact>(draws.size)
            for (draw in draws) {
                val command = draw.command
                val geometry = draw.geometry
                if (uniqueGeometries.add(geometry)) {
                    maximumQuads = maxOf(maximumQuads, geometry.maximumMeshletQuads)
                }
                if (geometry.layer != commonLayer) commonLayer = null
                val count = geometry.meshlets.size
                val quadCount = geometry.quadCount
                val primitiveCountForDraw = Math.multiplyExact(quadCount, 2)
                drawFacts += TerrainDrawFact(draw.section.x(), draw.section.y(), draw.section.z(),
                    geometry.layer, geometry.generation, primitiveBase, primitiveCountForDraw, quadCount)
                primitiveBase = Math.addExact(primitiveBase, primitiveCountForDraw)
                meshletTotal += count
                vertexBytes = maxOf(vertexBytes,
                    command.baseVertex().toLong() * D3D12MeshletBuilder.VERTEX_STRIDE + geometry.vertexBytes)
                if (count == 0 || count > 65535 * 64 || command.instanceCount() != 1 ||
                    command.indexCount() != geometry.indexCount || command.baseVertex() < 0) valid = false
            }
            geometries = uniqueGeometries.toList()
            layer = commonLayer
            meshletCount = if (valid && meshletTotal <= (Int.MAX_VALUE - 32L - drawCount * 32L) / 8L)
                meshletTotal.toInt() else null
            requiredVertexBytes = vertexBytes
            quadsPerMeshlet = when { maximumQuads > 32 -> 64; maximumQuads > 16 -> 32; else -> 16 }
            if (primitiveBase <= 0) valid = false
            terrainDrawFacts = if (valid) Collections.unmodifiableList(drawFacts) else emptyList()
            primitiveCount = if (valid) primitiveBase else 0
            facts = if (valid && commonLayer != null && meshletCount != null)
                TerrainBatchFacts(commonLayer, terrainDrawFacts, meshletCount, quadsPerMeshlet,
                    primitiveCount, requiredVertexBytes)
            else null
        }

    }

    internal class BatchTables(val sections: GpuBufferSlice, val meshlets: GpuBufferSlice, val sectionCount: Int,
                      val meshletCount: Int, val descriptors: D3D12MeshletResidency.PreparedDescriptors)

    private data class CommandRange(val offset: Long, val length: Long)

    private val captured = IdentityHashMap<DynamicGpuData.IndexedDraw, DrawSnapshot>()
    private val batches = IdentityHashMap<GpuBuffer, MutableMap<CommandRange, Batch>>()
    private val extractionReads = IdentityHashMap<TerrainGeometryAllocations.Allocation, TerrainGeometryAllocations.ReadLease>()
    private val bufferReads = IdentityHashMap<GpuBuffer, MutableList<TerrainGeometryAllocations.Allocation>>()
    private val readGroups = IdentityHashMap<GpuBuffer, GeometryReadGroup>()

    private class GeometryReadGroup(val allocations: List<TerrainGeometryAllocations.Allocation>) {
        fun acquire(): AutoCloseable {
            val leases = ArrayList<TerrainGeometryAllocations.ReadLease>(allocations.size)
            try {
                for (allocation in allocations) leases += allocation.retainRead()
            } catch (failure: Throwable) {
                for (lease in leases) lease.close()
                throw failure
            }
            return AutoCloseable { for (lease in leases) lease.close() }
        }
    }

    fun beginFrame() {
        val previous = synchronized(this) {
            captured.clear()
            batches.clear()
            bufferReads.clear()
            readGroups.clear()
            extractionReads.values.toList().also { extractionReads.clear() }
        }
        for (lease in previous) lease.close()
    }

    fun captureDraw(command: DynamicGpuData.IndexedDraw,
                    geometry: D3D12SectionGeometry.LayerUpload?,
                    sections: List<DynamicGpuData.ChunkSectionInfo>,
                    geometryReads: TerrainGeometryAllocations.DrawReads?) {
        if (geometryReads == null) return
        val retained = ArrayList<TerrainGeometryAllocations.ReadLease>(geometryReads.allocations.size)
        try {
            for (allocation in geometryReads.allocations) retained += allocation.retainRead()
        } catch (failure: Throwable) {
            for (lease in retained) lease.close()
            throw failure
        }
        var duplicate: ArrayList<TerrainGeometryAllocations.ReadLease>? = null
        try {
            val section = sections.getOrNull(command.baseInstance())
            if (geometry != null) geometryReads.vertex.requireRange(geometryReads.vertex.buffer,
                geometryReads.vertex.offset, geometry.vertexBytes.toLong())
            synchronized(this) {
                for (lease in retained) {
                    if (extractionReads.containsKey(lease.allocation)) {
                        (duplicate ?: ArrayList<TerrainGeometryAllocations.ReadLease>(1).also { duplicate = it }) += lease
                    }
                    else {
                        extractionReads[lease.allocation] = lease
                        bufferReads.getOrPut(lease.allocation.buffer) { ArrayList() } += lease.allocation
                        readGroups.remove(lease.allocation.buffer)
                    }
                }
                if (geometry != null && section != null) {
                    captured[command] = DrawSnapshot(command, section, geometry, geometryReads)
                }
            }
        } catch (failure: Throwable) {
            for (lease in retained) lease.close()
            throw failure
        }
        duplicate?.forEach(TerrainGeometryAllocations.ReadLease::close)
    }

    fun retainOriginalDrawReads(staging: D3D12TransientMemory, vertexBuffer: GpuBuffer?, indexBuffer: GpuBuffer?) {
        vertexBuffer?.let { transferReadGroup(staging, it) }
        if (indexBuffer != null && indexBuffer !== vertexBuffer) transferReadGroup(staging, indexBuffer)
    }

    private fun transferReadGroup(staging: D3D12TransientMemory, buffer: GpuBuffer) {
        val group = synchronized(this) {
            readGroups[buffer] ?: bufferReads[buffer]?.let { allocations ->
                GeometryReadGroup(allocations.toList()).also { readGroups[buffer] = it }
            }
        } ?: return
        staging.retainOriginalDrawRead(group, group::acquire)
    }

    private fun transferRead(staging: D3D12TransientMemory, allocation: TerrainGeometryAllocations.Allocation) {
        staging.retainOriginalDrawRead(allocation, allocation::retainRead)
    }

    @Synchronized
    fun captureBatches(commands: Array<GpuBufferSlice>, groups: List<List<DynamicGpuData.IndexedDraw>>) {
        groups@ for (groupIndex in 0 until minOf(commands.size, groups.size)) {
            val draws = groups[groupIndex]
            if (draws.isEmpty()) continue
            val snapshots = ArrayList<DrawSnapshot>(draws.size)
            for (draw in draws) snapshots.add(captured[draw] ?: continue@groups)
            val slice = commands[groupIndex]
            check(slice.length() == draws.size.toLong() * INDEXED_COMMAND_BYTES) {
                "Terrain command range does not match its captured draw count"
            }
            val range = CommandRange(slice.offset(), slice.length())
            batches.getOrPut(slice.buffer()) { mutableMapOf() }[range] =
                Batch(snapshots)
        }
        captured.clear()
    }

    @Synchronized
    fun batch(commands: GpuBufferSlice): Batch? {
        val ranges = batches[commands.buffer()] ?: return null
        val range = CommandRange(commands.offset(), commands.length())
        ranges[range]?.let { return it }
        if (range.length <= 0L || range.length % INDEXED_COMMAND_BYTES != 0L) return null
        val source = ranges.entries.firstOrNull { (capturedRange, _) ->
            val relativeOffset = range.offset - capturedRange.offset
            relativeOffset >= 0L && relativeOffset % INDEXED_COMMAND_BYTES == 0L &&
                relativeOffset <= capturedRange.length && range.length <= capturedRange.length - relativeOffset
        } ?: return null
        val firstDraw = ((range.offset - source.key.offset) / INDEXED_COMMAND_BYTES).toInt()
        val drawCount = (range.length / INDEXED_COMMAND_BYTES).toInt()
        return Batch(source.value.draws.subList(firstDraw, firstDraw + drawCount)).also { ranges[range] = it }
    }

    internal fun facts(commands: GpuBufferSlice): TerrainBatchFacts? = batch(commands)?.facts

    internal fun buildTables(batch: Batch, layer: String, vertexSliceOffset: Long, ctx: Long,
                    staging: D3D12TransientMemory, residency: D3D12MeshletResidency): BatchTables? {
        if (vertexSliceOffset < 0L || batch.layer != layer) return null
        val meshletCount = batch.meshletCount ?: return null
        val dynamicBytes = batch.drawCount * 40L
        if (dynamicBytes > Int.MAX_VALUE) return null

        val descriptors = residency.prepare(ctx, staging, batch.geometries)
        val sectionsSlice = staging.writeIntoStagingBlock(dynamicBytes, 256L) { destination ->
            val sections = destination.order(ByteOrder.LITTLE_ENDIAN)
            for (drawIndex in batch.draws.indices) {
                val draw = batch.draws[drawIndex]
                val baseVertex = draw.command.baseVertex()
                sections.putInt(baseVertex)
                sections.putInt(baseVertex / D3D12MeshletBuilder.VERTICES_PER_QUAD)
                sections.putInt(descriptors.offset(draw.geometry))
                sections.putInt(draw.geometry.meshlets.size)
                sections.putInt(draw.section.x())
                sections.putInt(draw.section.y())
                sections.putInt(draw.section.z())
                sections.putFloat(draw.section.visibility())
                sections.putInt(drawIndex)
                sections.putInt(if (draw.geometry.bounded) 1 else 0)
            }
            dynamicBytes
        }.getOrThrow().first
        return BatchTables(sectionsSlice, descriptors.buffer.slice(), batch.drawCount, meshletCount, descriptors)
    }

    private const val INDEXED_COMMAND_BYTES = 5L * Int.SIZE_BYTES
}
