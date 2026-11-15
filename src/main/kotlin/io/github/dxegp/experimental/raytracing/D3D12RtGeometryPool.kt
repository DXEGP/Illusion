package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshletBuilder
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.ref.WeakReference
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * S2 的导出几何缓存：把每段每层的顶点（28 B/顶点，`DefaultVertexFormat.BLOCK`）、索引与逐 quad。
 * 只在内容变化时写，重传同数据不会重复拷贝；超容量停止分配并撤销无法更新的记录。
 * 见 docs/rt/README.md
 */
object D3D12RtGeometryPool {
    /** 顶点 / 索引 / 材质池上限。12~16 区块半径全部层的顶点约 60~120 MB，留一倍余量。 */
    private const val VERTEX_CAP = 768L * 1024 * 1024
    private const val INDEX_CAP = 96L * 1024 * 1024
    private const val MATERIAL_CAP = 96L * 1024 * 1024
    private const val DORMANT_RECORD_LIMIT = 1024
    private const val DORMANT_BYTE_LIMIT = 128L * 1024 * 1024
    private const val RETAINED_SOURCE_SECTION_LIMIT = 1024
    private const val RETAINED_SOURCE_BYTE_LIMIT = 128L * 1024 * 1024
    private const val CAPTURE_DEMAND_LIMIT = 256
    private const val RESIDENCY_DEMAND_LIMIT = 256
    private const val RESIDENCY_DEMAND_NANOS = 250_000_000L

    private const val STRIDE = D3D12MeshletBuilder.VERTEX_STRIDE
    private const val QUAD_VERTICES = 4
    private const val QUAD_INDICES = 6
    private const val SECTION_CAPTURE_LAYER = -1
    private const val OUTPUT_BYTES = 48

    /** 逐 quad 材质索引的字节数（u16，指向 [D3D12RtMaterialTable] 的记录）。 */
    private const val MATERIAL_BYTES = 2

    data class OutputBounds(val minimumX: Float, val minimumY: Float, val minimumZ: Float,
                            val maximumX: Float, val maximumY: Float, val maximumZ: Float)

    /** 一条"段-层"记录；偏移是相对池首的字节偏移。 */
    data class Record(
        val sectionNode: Long,
        val layer: Int,
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val geometryVersion: Int,
        val flags: Int,
        val vertexOffset: Long,
        val vertexCount: Int,
        val indexOffset: Long,
        val indexCount: Int,
        val indexStride: Int,
        val quadCount: Int,
        val materialOffset: Long,
        val contentVersion: Int = geometryVersion,
        val outputBounds: OutputBounds? = null,
    ) {
        val triangles: Int get() = indexCount / 3
    }

    private data class RecordKey(val sectionNode: Long, val layer: Int)

    private data class MissingCapture(val version: Int, val sinceNanos: Long)

    data class CaptureTarget(val epoch: Int, val sectionNode: Long, val layer: Int,
                             val version: Int, val sinceNanos: Long)

    data class CaptureBacklog(val layers: Int, val oldestAgeNanos: Long)

    class Content internal constructor(private val storage: ByteBuffer) {
        val identity: Long = nextContent.incrementAndGet()
        val byteCount: Int get() = storage.capacity()
        val bytes: ByteBuffer get() = storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        internal fun writable(): ByteBuffer = storage.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    }

    data class ContentRecord(val record: Record, val vertices: Content, val indices: Content, val quads: Content)

    private val nextContent = AtomicLong()
    private val retainedContent = mutableListOf<WeakReference<Content>>()

    private fun content(buffer: ByteBuffer): Content = Content(buffer).also {
        retainedContent += WeakReference(it)
    }

    private class GeometryArena(private val capacityLimit: Int) {
        private val allocations = TreeMap<Int, Content>()
        private val freeRanges = TreeMap<Int, Int>()
        var allocatedBytes = 0L
            private set
        var highWater = 0
            private set
        val allocationCount: Int get() = allocations.size

        fun canAllocate(byteCount: Int): Boolean = byteCount >= 0 &&
            (byteCount <= capacityLimit - highWater || freeRanges.values.any { it >= byteCount })

        fun allocate(byteCount: Int): Int? {
            require(byteCount >= 0)
            if (byteCount == 0) return 0
            val free = freeRanges.entries.firstOrNull { it.value >= byteCount }
            val offset = free?.key ?: highWater
            if (byteCount > capacityLimit - offset) return null
            val buffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
            allocations[offset] = content(buffer)
            if (free != null) {
                val remaining = free.value - byteCount
                freeRanges.remove(offset)
                if (remaining > 0) freeRanges[offset + byteCount] = remaining
            } else {
                highWater += byteCount
            }
            allocatedBytes += byteCount
            return offset
        }

        fun release(offset: Int, byteCount: Int) {
            if (byteCount == 0) return
            val allocation = checkNotNull(allocations.remove(offset))
            check(allocation.byteCount == byteCount)
            allocatedBytes -= byteCount
            var start = offset
            var length = byteCount
            freeRanges.lowerEntry(start)?.let { previous ->
                if (previous.key + previous.value == start) {
                    start = previous.key
                    length += previous.value
                    freeRanges.remove(previous.key)
                }
            }
            freeRanges.ceilingEntry(start)?.let { next ->
                if (start + length == next.key) {
                    length += next.value
                    freeRanges.remove(next.key)
                }
            }
            if (start + length == highWater) highWater = start else freeRanges[start] = length
        }

        fun view(offset: Long, byteCount: Int): ByteBuffer {
            require(offset in 0..Int.MAX_VALUE.toLong() && byteCount >= 0)
            if (byteCount == 0) return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
            val allocation = checkNotNull(allocations[offset.toInt()])
            require(allocation.byteCount == byteCount)
            return allocation.writable()
        }

        fun export(offset: Long, byteCount: Int): Content = checkNotNull(allocations[offset.toInt()]).also {
            require(it.byteCount == byteCount)
        }

        fun replace(offset: Long, buffer: ByteBuffer) {
            require(checkNotNull(allocations[offset.toInt()]).byteCount == buffer.remaining())
            allocations[offset.toInt()] = content(buffer)
        }

        fun snapshot(): ByteBuffer {
            val output = ByteBuffer.allocate(highWater).order(ByteOrder.LITTLE_ENDIAN)
            for ([offset, allocation] in allocations) {
                output.position(offset)
                output.put(allocation.bytes)
            }
            output.position(0)
            return output.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private data class Allocation(
        val vertexOffset: Int,
        val vertexBytes: Int,
        val indexOffset: Int,
        val indexBytes: Int,
        val materialOffset: Int,
        val materialBytes: Int,
    )

    private val records = ConcurrentHashMap<RecordKey, Record>()
    private val missingVersions = linkedMapOf<RecordKey, MissingCapture>()
    private val missingSections = Long2ObjectLinkedOpenHashMap<MissingCapture>()
    private var pendingOutputSlots = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private var pendingOutputRows = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private var pendingOutputUniverse: D3D12RtSourceUniverse.Snapshot? = null
    private data class ProducerManifest(val support: D3D12RtModelSupport.Manifest, val versions: Map<Int, Int>,
                                        val requiredLayers: Set<Int>)
    private val producerManifests = Long2ObjectLinkedOpenHashMap<ProducerManifest>()
    private data class SectionPublication(val node: Long, val epoch: Int, val layers: Set<Int>)
    private var sectionPublication: SectionPublication? = null
    private data class SourceRequirement(val support: D3D12RtModelSupport.SourceManifest, val version: Int)
    private val sourceRequirements = Long2ObjectLinkedOpenHashMap<SourceRequirement>()
    private data class UnprovenOutput(val version: Int, val bounds: OutputBounds?)
    private val unprovenOutputs = Long2ObjectLinkedOpenHashMap<UnprovenOutput>()
    private var nextSectionCaptureVersion = 0
    private var captureAgeDirty = true
    private var oldestCaptureNanos: Long? = null

    private fun observeCaptureAge(previous: Long?, current: Long?) {
        if (previous == current) return
        val oldest = oldestCaptureNanos
        if (previous != null && previous == oldest) captureAgeDirty = true
        if (current != null && (oldest == null || current < oldest))
            oldestCaptureNanos = current
    }

    private val captureOrder = linkedSetOf<RecordKey>()
    private data class CaptureDemand(val target: CaptureTarget, val receivedNanos: Long)
    private val captureDemands = linkedMapOf<RecordKey, CaptureDemand>()
    private var captureDemandNext = false
    private data class ResidencyDemand(val identity: Long, val records: List<Record>, val receivedNanos: Long)
    private val residencyDemands = linkedMapOf<Long, ResidencyDemand>()
    private var nextResidencyDemand = 0L
    private val dormantRecords = linkedMapOf<RecordKey, Record>()
    private var dormantBytes = 0L
    private val retainedSources = linkedMapOf<Long, Long>()
    private var retainedSourceBytes = 0L
    private var mirroredRecords: List<Record> = emptyList()
    private var mirroredSlice: MirrorSlice? = null
    private var geometryEpoch = 0
    private val lightingChanges = D3D12RtLightingChanges()
    private var cachedVersions: Versions? = null
    private var cachedSpatialSources: D3D12RtSpatialSources.Snapshot? = null
    private val spatialSources = TreeMap<Long, D3D12RtSpatialSources.Source>()
    private val dirtySpatialSources = LongOpenHashSet()
    private var sourceUniverse = D3D12RtSourceUniverse.unknown
    private var currentSourceUniverse = D3D12RtSourceUniverse.unavailable
    private var cachedCoverage: Coverage? = null
    private data class TargetSelection(val centerX: Float, val centerY: Float, val centerZ: Float, val radius: Float,
                                       val primary: D3D12RtSpatialDirectory.Region?,
                                       val query: D3D12RtSpatialDirectory.Region,
                                       val universe: D3D12RtSourceUniverse.Snapshot)
    private data class TargetSnapshot(val members: D3D12RtSpatialDirectory.Targets, val bytes: ByteBuffer)
    private var targetSelection: TargetSelection? = null
    private var targetMembers = LongOpenHashSet()
    private var targetSnapshot: TargetSnapshot? = null
    private data class SpatialGrid(val sources: D3D12RtSpatialSources.Snapshot,
                                   val region: D3D12RtSpatialDirectory.Region,
                                   val targets: D3D12RtSpatialDirectory.Targets, val build: D3D12RtSpatialDirectory.Build)
    private var cachedSpatialGrid: SpatialGrid? = null
    private val spatialDirectory = D3D12RtSpatialDirectory.Cache()
    private val invalidSections = LongOpenHashSet()
    private enum class SpatialState(val code: Int) {
        UNLOADED(3), DIRTY(4), LOADED_UNPROVEN(5),
    }
    private val spatialStates = Long2ObjectLinkedOpenHashMap<SpatialState>()
    private var vertices = GeometryArena(VERTEX_CAP.toInt())
    private var indices = GeometryArena(INDEX_CAP.toInt())
    private var materials = GeometryArena(MATERIAL_CAP.toInt())

    private val revision = AtomicLong()
    private val published = AtomicLong()
    private val skipped = AtomicLong()
    private val resets = AtomicLong()
    private val copiedBytes = AtomicLong()

    private var full = false

    /**
     * 发布一段一层的几何。`vertex` / `index` 是上传时看到的字节：。
     * 见 docs/rt/README.md
     */
    @Synchronized
    @JvmStatic
    fun publish(sectionNode: Long, layer: String, geometryVersion: Int,
                vertex: ByteBuffer, index: ByteBuffer?, indexCount: Int): Boolean {
        dirtySpatialSources.add(sectionNode)
        val kind = D3D12SectionGeometry.Layer.of(layer)
        val key = RecordKey(sectionNode, kind.ordinal)
        if (!D3D12Settings.rtEnabled) {
            if (indexCount > 0 || vertex.hasRemaining()) markMissing(key, geometryVersion)
            return false
        }
        try {
            val published = publishRecord(sectionNode, kind, key, geometryVersion, vertex, index, indexCount)
            if (records.containsKey(key)) {
                if (removeMissing(key)) revision.incrementAndGet()
            } else if (vertex.hasRemaining()) {
                markMissing(key, geometryVersion)
            }
            return published
        } catch (failure: Throwable) {
            markMissing(key, geometryVersion)
            throw failure
        }
    }

    private fun publishRecord(sectionNode: Long, kind: D3D12SectionGeometry.Layer, key: RecordKey,
                              geometryVersion: Int, vertex: ByteBuffer, index: ByteBuffer?, indexCount: Int): Boolean {
        val previous = records[key]
        if (previous != null && previous.contentVersion == geometryVersion) {
            skipped.incrementAndGet()
            return false
        }
        val vertexBytes = vertex.remaining()
        val vertexCount = vertexBytes / STRIDE
        if (vertexCount <= 0) {
            return false
        }
        val quadCount = vertexCount / QUAD_VERTICES
        val effectiveIndexCount = if (indexCount > 0) indexCount else quadCount * QUAD_INDICES
        val indexStride = if (index != null && indexCount > 0) {
            if (index.remaining() / indexCount >= 4) 4 else 2
        } else {
            // 原版共享顺序缓冲的元素大小按顶点数选；我们合成同样规则的索引。
            if (vertexCount > 0xFFFF) 4 else 2
        }
        val indexBytes = effectiveIndexCount.toLong() * indexStride
        val materialBytes = quadCount.toLong() * MATERIAL_BYTES
        if (previous != null && matchesGeometry(previous, vertex, index, effectiveIndexCount, indexStride)) {
            skipped.incrementAndGet()
            return false
        }
        val dormant = dormantRecords[key]
        if (previous == null && dormant != null &&
            matchesGeometry(dormant, vertex, index, effectiveIndexCount, indexStride)) {
            val materialIds = D3D12RtMaterialTable.quadMaterials(vertex, vertex.position(), dormant.quadCount)
            val materialChanged = remapMaterials(dormant, materialIds)
            records[key] = dormant
            removeDormant(key)
            if (materialChanged && dormant in mirroredRecords) mirroredSlice = null
            full = false
            published.incrementAndGet()
            revision.incrementAndGet()
            return true
        }
        val geometryIdentity = (previous ?: dormant)?.takeIf {
            matchesGeometry(it, vertex, index, effectiveIndexCount, indexStride, false)
        }?.geometryVersion ?: geometryVersion
        if (previous != null || dormant != null) {
            recordLightingChanges(setOf(sectionNode), D3D12RtLightingChanges.Reason.CAPTURE_CHANGED,
                D3D12RtLightingChanges.Region(sectionNode, outputBounds(vertex)))
        }
        removeDormant(key)?.let(::releaseRecord)
        if (previous != null) {
            records.remove(key)
            releaseRecord(previous)
            revision.incrementAndGet()
        }
        val materialIds = D3D12RtMaterialTable.quadMaterials(vertex, vertex.position(), quadCount)
        val allocation = allocateStorage(vertexBytes, indexBytes, materialBytes) ?: run {
            if (!full) {
                full = true
                IllusionLogger.warnMessage("RT pool: storage budget exhausted")
            }
            skipped.incrementAndGet()
            return false
        }
        var committed = false
        var stagedRecord: Record? = null
        try {
            val position = SectionPos.of(sectionNode)
            val record = Record(
                sectionNode = sectionNode,
                layer = kind.ordinal,
                originX = position.x() * 16,
                originY = position.y() * 16,
                originZ = position.z() * 16,
                geometryVersion = geometryIdentity,
                flags = kind.flag,
                vertexOffset = allocation.vertexOffset.toLong(),
                vertexCount = vertexCount,
                indexOffset = allocation.indexOffset.toLong(),
                indexCount = effectiveIndexCount,
                indexStride = indexStride,
                quadCount = quadCount,
                materialOffset = allocation.materialOffset.toLong(),
                outputBounds = outputBounds(vertex),
                contentVersion = geometryVersion,
            )
            stagedRecord = record
            vertices.view(record.vertexOffset, vertexBytes).put(vertex.duplicate())
            val indexData = indices.view(record.indexOffset, allocation.indexBytes)
            if (index != null && indexCount > 0) {
                indexData.put(index.duplicate())
            } else {
                writeSequentialQuadIndices(indexData, quadCount, indexStride)
            }
            val materialData = materials.view(record.materialOffset, allocation.materialBytes)
            for (materialId in materialIds) materialData.putShort(materialId)
            records[key] = record
            committed = true
        } finally {
            if (!committed) {
                stagedRecord?.let { records.remove(key, it) }
                releaseStorage(allocation)
            }
        }
        full = false
        copiedBytes.addAndGet(vertexBytes + indexBytes + materialBytes)
        published.incrementAndGet()
        revision.incrementAndGet()
        return true
    }

    /** 每次成功写入、材质回填与清空都会 +1：上传镜像靠它判断要不要重传。 */
    @JvmStatic
    fun revision(): Long = revision.get()

    @JvmStatic
    @Synchronized
    fun markMissing(sectionNode: Long, layer: String, geometryVersion: Int) {
        val kind = D3D12SectionGeometry.Layer.of(layer)
        markMissing(RecordKey(sectionNode, kind.ordinal), geometryVersion)
    }

    private fun markMissing(key: RecordKey, geometryVersion: Int) {
        dirtySpatialSources.add(key.sectionNode)
        residencyDemands.remove(key.sectionNode)
        val previous = records.remove(key)
        previous?.let(::releaseRecord)
        val previousMissing = missingVersions[key]
        val missing = MissingCapture(geometryVersion, previousMissing?.sinceNanos ?: System.nanoTime())
        missingVersions[key] = missing
        observeCaptureAge(previousMissing?.sinceNanos, missing.sinceNanos)
        captureOrder += key
        val changed = previousMissing?.version != geometryVersion
        if (changed) captureDemands.remove(key)
        if (changed || previous != null) {
            revision.incrementAndGet()
        }
    }

    private fun removeMissing(key: RecordKey): Boolean {
        captureOrder.remove(key)
        captureDemands.remove(key)
        val previous = missingVersions.remove(key)
        observeCaptureAge(previous?.sinceNanos, null)
        return (previous != null).also {
            if (it) {
                dirtySpatialSources.add(key.sectionNode)
            }
        }
    }

    private fun missingCapture(key: RecordKey): MissingCapture? =
        if (key.layer == SECTION_CAPTURE_LAYER) missingSections[key.sectionNode] else missingVersions[key]

    private fun oldestCapture(sectionNode: Long): MissingCapture? {
        var oldest = missingSections[sectionNode]
        for (layer in D3D12SectionGeometry.Layer.entries) {
            val missing = missingVersions[RecordKey(sectionNode, layer.ordinal)] ?: continue
            if (oldest == null || missing.sinceNanos < oldest.sinceNanos) oldest = missing
        }
        return oldest
    }

    private fun removeSectionCapture(sectionNode: Long): Boolean {
        captureOrder.remove(RecordKey(sectionNode, SECTION_CAPTURE_LAYER))
        captureDemands.remove(RecordKey(sectionNode, SECTION_CAPTURE_LAYER))
        val previous = missingSections.remove(sectionNode) ?: return false
        removeSectionOutput(sectionNode)
        observeCaptureAge(previous.sinceNanos, null)
        return true
    }

    private fun writeSectionOutput(node: Long, missing: MissingCapture) {
        var slot = pendingOutputSlots.get(node)
        if (slot == -1) {
            slot = pendingOutputSlots.size
            val requiredBytes = (slot + 1) * OUTPUT_BYTES
            if (pendingOutputRows.capacity() < requiredBytes) {
                val capacity = maxOf(requiredBytes, pendingOutputRows.capacity().coerceAtMost(Int.MAX_VALUE / 2) * 2)
                pendingOutputRows = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(pendingOutputRows.array(), 0, slot * OUTPUT_BYTES)
                }
            }
            pendingOutputSlots.put(node, slot)
        }
        val support = sourceRequirements[node]?.takeIf { it.version == missing.version }
        val bounds = support?.support?.bounds ?: currentSourceUniverse.bounds
        pendingOutputRows.position(slot * OUTPUT_BYTES)
        pendingOutputRows.putLong(node).putInt(SECTION_CAPTURE_LAYER).putInt(missing.version)
            .putInt(if (bounds == null) 0 else 1).putInt(0)
        pendingOutputRows.putFloat(bounds?.minimumX ?: 0f).putFloat(bounds?.minimumY ?: 0f)
            .putFloat(bounds?.minimumZ ?: 0f).putFloat(bounds?.maximumX ?: 0f)
            .putFloat(bounds?.maximumY ?: 0f).putFloat(bounds?.maximumZ ?: 0f)
    }

    private fun updateSectionOutput(node: Long) {
        if (pendingOutputUniverse !== currentSourceUniverse) return
        missingSections[node]?.let { writeSectionOutput(node, it) }
    }

    private fun removeSectionOutput(node: Long) {
        if (pendingOutputUniverse !== currentSourceUniverse) return
        val slot = pendingOutputSlots.remove(node)
        if (slot == -1 || slot == pendingOutputSlots.size) return
        val lastOffset = pendingOutputSlots.size * OUTPUT_BYTES
        val movedNode = pendingOutputRows.getLong(lastOffset)
        pendingOutputRows.put(slot * OUTPUT_BYTES, pendingOutputRows, lastOffset, OUTPUT_BYTES)
        pendingOutputSlots.put(movedNode, slot)
    }

    private fun appendSectionOutputs(outputs: ByteBuffer) {
        if (pendingOutputUniverse !== currentSourceUniverse) {
            pendingOutputUniverse = null
            pendingOutputSlots.clear()
            for ([node, missing] in missingSections) writeSectionOutput(node, missing)
            pendingOutputUniverse = currentSourceUniverse
        }
        outputs.put(pendingOutputRows.array(), 0, missingSections.size * OUTPUT_BYTES)
    }

    private fun clearSectionOutputs() {
        pendingOutputSlots = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        pendingOutputRows = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
        pendingOutputUniverse = null
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun nextMissingCapture(nowNanos: Long = System.nanoTime()): CaptureTarget? {
        pruneCaptureDemands(nowNanos)
        val key = (if (captureDemandNext) captureDemands.keys.firstOrNull() else null)
            ?: captureOrder.firstOrNull() ?: return null
        captureOrder.remove(key)
        captureOrder += key
        captureDemands.remove(key)
        captureDemandNext = !captureDemandNext
        val missing = checkNotNull(missingCapture(key))
        return CaptureTarget(geometryEpoch, key.sectionNode, key.layer, missing.version, missing.sinceNanos)
    }

    private fun pruneCaptureDemands(nowNanos: Long) {
        captureDemands.entries.removeAll { [key, demand] ->
            val current = missingCapture(key)
            demand.target.epoch != geometryEpoch || current?.version != demand.target.version ||
                current.sinceNanos != demand.target.sinceNanos ||
                nowNanos - demand.receivedNanos < 0L
        }
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun requestCaptures(epoch: Long, expectedRevision: Long, nodes: LongArray,
                        nowNanos: Long = System.nanoTime()): Int {
        require(nodes.size <= 64)
        pruneCaptureDemands(nowNanos)
        if (epoch != geometryEpoch.toLong() || expectedRevision !in 0L..revision.get()) return 0
        var accepted = 0
        for (node in nodes) {
            for (target in missingCaptures(node)) {
                val key = RecordKey(node, target.layer)
                if (key !in captureDemands && captureDemands.size >= CAPTURE_DEMAND_LIMIT) continue
                captureDemands[key] = CaptureDemand(target, nowNanos)
                accepted++
            }
        }
        return accepted
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun requestCameraCaptures(epoch: Long, sectionNode: Long, nowNanos: Long = System.nanoTime()): Int {
        val nodes = LongArray(27)
        var count = 0
        for (distance in 0..3) {
            for (deltaZ in -1..1) for (deltaY in -1..1) for (deltaX in -1..1) {
                if (abs(deltaX) + abs(deltaY) + abs(deltaZ) != distance) continue
                nodes[count++] = SectionPos.offset(sectionNode, deltaX, deltaY, deltaZ)
            }
        }
        return requestCaptures(epoch, revision.get(), nodes, nowNanos)
    }

    @JvmStatic
    @Synchronized
    fun clearCaptureDemands() {
        captureDemands.clear()
        captureDemandNext = false
    }

    private fun residencyGroup(node: Long): List<Record>? {
        if (node in invalidSections || node in missingSections) return null
        val group = ArrayList<Record>(D3D12SectionGeometry.Layer.entries.size)
        for (layer in D3D12SectionGeometry.Layer.entries) {
            val key = RecordKey(node, layer.ordinal)
            if (key in missingVersions) return null
            records[key]?.let(group::add)
        }
        return group.takeIf { it.isNotEmpty() }
    }

    private fun isCurrent(demand: ResidencyDemand): Boolean =
        residencyGroup(demand.records.first().sectionNode)?.let { group ->
            group.size == demand.records.size && group.indices.all { group[it] === demand.records[it] }
        } == true

    private fun pruneResidencyDemands(nowNanos: Long) {
        residencyDemands.values.removeAll {
            nowNanos - it.receivedNanos !in 0L until RESIDENCY_DEMAND_NANOS || !isCurrent(it)
        }
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun requestResidency(epoch: Long, expectedRevision: Long, nodes: LongArray,
                         residentNodes: Set<Long> = emptySet(), nowNanos: Long = System.nanoTime()): Int {
        require(nodes.size <= 64)
        pruneResidencyDemands(nowNanos)
        if (epoch != geometryEpoch.toLong() || expectedRevision != revision.get()) return 0
        var accepted = 0
        for (node in nodes) {
            if (node in residentNodes) continue
            val group = residencyGroup(node) ?: continue
            val previous = residencyDemands[node]
            if (previous == null && residencyDemands.size >= RESIDENCY_DEMAND_LIMIT) continue
            residencyDemands[node] = ResidencyDemand(previous?.identity ?: ++nextResidencyDemand, group, nowNanos)
            accepted++
        }
        return accepted
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun hasResidencyDemands(request: D3D12RtFullDomainProfile.Request, residentNodes: Set<Long>,
                            nowNanos: Long = System.nanoTime()): Boolean {
        pruneResidencyDemands(nowNanos)
        residencyDemands.entries.removeAll { [node, demand] ->
            node in residentNodes || demand.records.none { outputIntersects(it, request.primary) }
        }
        return residencyDemands.isNotEmpty()
    }

    @JvmStatic
    @Synchronized
    fun completeResidencyDemands(residency: Residency) {
        if (residency.superseded || residency.geometryEpoch != geometryEpoch) return
        for ([node, identity] in residency.admittedDemands) {
            val demand = residencyDemands[node] ?: continue
            if (demand.identity == identity && isCurrent(demand)) residencyDemands.remove(node)
        }
    }

    @JvmStatic
    @Synchronized
    fun clearResidencyDemands() {
        residencyDemands.clear()
    }

    @JvmStatic
    @Synchronized
    fun isMissing(target: CaptureTarget): Boolean = target.epoch == geometryEpoch &&
        missingCapture(RecordKey(target.sectionNode, target.layer))?.version == target.version

    @JvmStatic
    @Synchronized
    fun hasSectionCapture(sectionNode: Long): Boolean = missingSections.containsKey(sectionNode)

    @JvmStatic
    @Synchronized
    fun missingCaptures(sectionNode: Long): List<CaptureTarget> = buildList {
        missingSections[sectionNode]?.let { missing ->
            add(CaptureTarget(geometryEpoch, sectionNode, SECTION_CAPTURE_LAYER, missing.version, missing.sinceNanos))
        }
        for (layer in D3D12SectionGeometry.Layer.entries) {
            val missing = missingVersions[RecordKey(sectionNode, layer.ordinal)] ?: continue
            add(CaptureTarget(geometryEpoch, sectionNode, layer.ordinal, missing.version, missing.sinceNanos))
        }
    }

    @JvmStatic
    @Synchronized
    fun captureBacklog(nowNanos: Long = System.nanoTime()): CaptureBacklog {
        if (captureAgeDirty) {
            oldestCaptureNanos = listOfNotNull(missingVersions.values.minOfOrNull { it.sinceNanos },
                missingSections.values.minOfOrNull { it.sinceNanos }).minOrNull()
            captureAgeDirty = false
        }
        return CaptureBacklog(missingVersions.size + missingSections.size,
            oldestCaptureNanos?.let { (nowNanos - it).coerceAtLeast(0) } ?: 0)
    }

    @JvmStatic
    @Synchronized
    fun invalidateSection(sectionNode: Long) {
        invalidateSections(longArrayOf(sectionNode)) {}
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun invalidateModelResources(required: LongArray, newUniverse: D3D12RtSourceUniverse = D3D12RtSourceUniverse.unknown,
                                 publish: () -> Unit) {
        lightingChanges.recordFullDomain(D3D12RtLightingChanges.Reason.RESOURCES.mask)
        val nodes = buildSet {
            addAll(required.asList())
            addAll(records.keys.map { it.sectionNode })
            addAll(missingVersions.keys.map { it.sectionNode })
            addAll(missingSections.keys)
            addAll(invalidSections)
        }.filterTo(linkedSetOf()) { spatialStates[it] != SpatialState.UNLOADED }
        val previous = missingSections.toMutableMap()
        for ([key, missing] in missingVersions) {
            if ((previous[key.sectionNode]?.sinceNanos ?: Long.MAX_VALUE) > missing.sinceNanos) {
                previous[key.sectionNode] = missing
            }
        }
        records.values.forEach(::releaseRecord)
        dormantRecords.values.forEach(::releaseRecord)
        records.clear()
        dormantRecords.clear()
        dormantBytes = 0L
        retainedSources.clear()
        retainedSourceBytes = 0L
        missingVersions.clear()
        missingSections.clear()
        clearSectionOutputs()
        producerManifests.clear()
        sourceRequirements.clear()
        invalidSections.clear()
        captureOrder.clear()
        clearCaptureDemands()
        clearResidencyDemands()
        captureAgeDirty = true
        oldestCaptureNanos = null
        mirroredRecords = emptyList()
        mirroredSlice = null
        full = false
        cachedSpatialSources = null
        spatialSources.clear()
        unprovenOutputs.clear()
        dirtySpatialSources.clear()
        dirtySpatialSources.addAll(spatialStates.keys)
        cachedCoverage = null
        clearCoverageTargets()
        cachedSpatialGrid = null
        spatialDirectory.clear()
        try {
            for (node in nodes) queueSectionCapture(node, SpatialState.DIRTY, previous[node])
            sourceUniverse = newUniverse
            currentSourceUniverse = newUniverse.snapshot()
            publish()
        } finally {
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun invalidateSections(nodes: LongArray, reason: D3D12RtLightingChanges.Reason = D3D12RtLightingChanges.Reason.INPUT,
                           publish: () -> Unit) {
        val active = LongOpenHashSet(nodes)
        active.removeIf(java.util.function.LongPredicate { spatialStates[it] == SpatialState.UNLOADED })
        recordLightingChanges(active, reason)
        try {
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val node = iterator.nextLong()
                val previous = oldestCapture(node)
                retireSection(node, preserveCapture = true)
                queueSectionCapture(node, SpatialState.DIRTY, previous)
            }
            publish()
        } finally {
            if (active.isNotEmpty()) revision.incrementAndGet()
        }
    }

    private fun queueSectionCapture(sectionNode: Long, state: SpatialState, previous: MissingCapture?) {
        dirtySpatialSources.add(sectionNode)
        captureDemands.remove(RecordKey(sectionNode, SECTION_CAPTURE_LAYER))
        residencyDemands.remove(sectionNode)
        invalidSections += sectionNode
        spatialStates[sectionNode] = state
        val missing = MissingCapture(++nextSectionCaptureVersion, previous?.sinceNanos ?: System.nanoTime())
        val replaced = missingSections.put(sectionNode, missing)
        updateSectionOutput(sectionNode)
        observeCaptureAge(replaced?.sinceNanos, missing.sinceNanos)
        captureOrder += RecordKey(sectionNode, SECTION_CAPTURE_LAYER)
    }

    @JvmStatic
    @Synchronized
    fun isSectionUnloaded(sectionNode: Long): Boolean = spatialStates[sectionNode] == SpatialState.UNLOADED

    @JvmStatic
    @Synchronized
    fun replaceChunk(nodes: LongArray, loaded: Boolean, dependent: LongArray = longArrayOf(),
                     retired: LongArray = longArrayOf(), publish: () -> Unit) {
        val state = if (loaded) SpatialState.LOADED_UNPROVEN else SpatialState.UNLOADED
        val changedNodes = LongOpenHashSet(nodes)
        val retiredNodes = LongOpenHashSet(retired)
        val affected = LongOpenHashSet(changedNodes).apply {
            addAll(retiredNodes)
            addAll(LongOpenHashSet(dependent))
        }
        recordLightingChanges(affected, D3D12RtLightingChanges.Reason.SOURCE)
        val required = LongOpenHashSet(dependent).apply { if (loaded) addAll(changedNodes) }
        var changed = false
        try {
            val iterator = affected.iterator()
            while (iterator.hasNext()) {
                val node = iterator.nextLong()
                val previous = oldestCapture(node)
                val nextState = when (node) {
                    in changedNodes -> state
                    in retiredNodes -> SpatialState.UNLOADED
                    else -> spatialStates[node]
                }
                val capture = node in required && nextState != SpatialState.UNLOADED
                if (retireSection(node, preserveCapture = capture)) changed = true
                if (node in retiredNodes && spatialStates.put(node, SpatialState.UNLOADED) != SpatialState.UNLOADED) changed = true
                if (node in changedNodes && spatialStates.put(node, state) != state) changed = true
                if (capture) {
                    queueSectionCapture(node, if (node in changedNodes) state else SpatialState.DIRTY, previous)
                    changed = true
                }
            }
            publish()
        } finally {
            if (changed) revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    fun releaseSection(sectionNode: Long, publish: () -> Unit) {
        val previous = oldestCapture(sectionNode)
        val state = spatialStates[sectionNode] ?: SpatialState.LOADED_UNPROVEN
        retireSections(setOf(sectionNode))
        try {
            if (state != SpatialState.UNLOADED) queueSectionCapture(sectionNode, state, previous)
            publish()
        } finally {
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    internal fun retainSection(sectionNode: Long, scope: D3D12SectionGeometry.CompileScope): LongArray? {
        val producer = producerManifests[sectionNode] ?: return null
        if (producer.support.scope !== scope || scope.sectionNode != sectionNode || scope.epoch != geometryEpoch ||
            spatialStates[sectionNode] != SpatialState.LOADED_UNPROVEN || sectionNode in invalidSections ||
            missingSections.containsKey(sectionNode)) return null
        val current = D3D12SectionGeometry.Layer.entries.mapNotNull { records[RecordKey(sectionNode, it.ordinal)] }
        if (current.mapTo(hashSetOf()) { it.layer } != producer.requiredLayers ||
            producer.versions != current.associate { it.layer to it.geometryVersion } ||
            D3D12SectionGeometry.Layer.entries.any { missingVersions.containsKey(RecordKey(sectionNode, it.ordinal)) }) return null
        val byteCount = current.sumOf(::geometryByteCount)
        if (byteCount > RETAINED_SOURCE_BYTE_LIMIT) return null
        if (retainedSources.containsKey(sectionNode)) return longArrayOf()
        retainedSources[sectionNode] = byteCount
        retainedSourceBytes += byteCount
        return buildList {
            while (retainedSources.size > RETAINED_SOURCE_SECTION_LIMIT || retainedSourceBytes > RETAINED_SOURCE_BYTE_LIMIT) {
                val oldest = retainedSources.keys.first()
                releaseSection(oldest) {}
                add(oldest)
            }
        }.toLongArray()
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun observeCompiledSection(sectionNode: Long, manifest: D3D12RtModelSupport.Manifest? = null) {
        dirtySpatialSources.add(sectionNode)
        val keys = D3D12SectionGeometry.Layer.entries.map { RecordKey(sectionNode, it.ordinal) }
        val current = keys.mapNotNull { records[it] }
        val missing = keys.mapNotNull { key -> missingVersions[key]?.let { key to it } }.toMap()
        val publication = sectionPublication?.takeIf { it.node == sectionNode && it.epoch == geometryEpoch }
        val accepted = manifest?.takeIf { support ->
            publication != null && support.scope.sectionNode == sectionNode && support.scope.epoch == geometryEpoch && support.complete &&
                publication.layers.all { support.bounds(it) != null } &&
                current.all { support.contains(it.layer, it.outputBounds) } &&
                missing.keys.all { support.bounds(it.layer) != null }
        }?.let { support ->
            ProducerManifest(support, buildMap {
                for (record in current) put(record.layer, record.geometryVersion)
                for ([key, requirement] in missing) put(key.layer, requirement.version)
            }, checkNotNull(publication).layers)
        }
        val previous = producerManifests.remove(sectionNode)
        if (accepted != null) producerManifests[sectionNode] = accepted
        val stateChanged = spatialStates.put(sectionNode, SpatialState.LOADED_UNPROVEN) != SpatialState.LOADED_UNPROVEN
        if (previous != accepted || stateChanged) {
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    fun publishSourceSupport(target: CaptureTarget, manifest: D3D12RtModelSupport.SourceManifest): Boolean {
        if (target.layer != SECTION_CAPTURE_LAYER || target.sectionNode != manifest.scope.sectionNode ||
            target.epoch != manifest.scope.epoch || !isMissing(target)) return false
        val bounds = manifest.bounds
        val accepted = bounds?.takeIf {
            it.minimumX.isFinite() && it.minimumY.isFinite() && it.minimumZ.isFinite() &&
                it.maximumX.isFinite() && it.maximumY.isFinite() && it.maximumZ.isFinite() &&
                it.minimumX <= 0f && it.minimumY <= 0f && it.minimumZ <= 0f &&
                it.maximumX >= 16f && it.maximumY >= 16f && it.maximumZ >= 16f
        }?.let { SourceRequirement(manifest, target.version) }
        val previous = sourceRequirements.remove(target.sectionNode)
        if (accepted != null) sourceRequirements[target.sectionNode] = accepted
        if (previous != accepted) {
            updateSectionOutput(target.sectionNode)
            dirtySpatialSources.add(target.sectionNode)
            revision.incrementAndGet()
        }
        return accepted != null
    }

    @JvmStatic
    @Synchronized
    fun revokeSourceSupport(manifest: D3D12RtModelSupport.SourceManifest) {
        val node = manifest.scope.sectionNode
        if (sourceRequirements[node]?.support !== manifest) return
        sourceRequirements.remove(node)
        updateSectionOutput(node)
        dirtySpatialSources.add(node)
        revision.incrementAndGet()
    }

    @JvmStatic
    @Synchronized
    fun replaceSection(sectionNode: Long, layers: Set<String> = emptySet(), publish: () -> Unit) {
        val retained = layers.map { D3D12SectionGeometry.Layer.of(it).ordinal }.toSet()
        val previous = oldestCapture(sectionNode)
        val changed = retireSections(setOf(sectionNode), retained)
        val previousPublication = sectionPublication
        val publication = SectionPublication(sectionNode, geometryEpoch, retained)
        sectionPublication = publication
        try {
            publish()
        } catch (failure: Throwable) {
            if (sectionPublication === publication && producerManifests.remove(sectionNode) != null) revision.incrementAndGet()
            throw failure
        } finally {
            if (sectionPublication === publication) {
                sectionPublication = previousPublication?.takeIf { it.epoch == geometryEpoch }
                if (previous != null) {
                    for (layer in retained) {
                        val key = RecordKey(sectionNode, layer)
                        val missing = missingVersions[key] ?: continue
                        if (missing.sinceNanos > previous.sinceNanos) {
                            missingVersions[key] = missing.copy(sinceNanos = previous.sinceNanos)
                            observeCaptureAge(missing.sinceNanos, previous.sinceNanos)
                        }
                    }
                }
                if (changed) revision.incrementAndGet()
            }
        }
    }

    private fun retireSections(nodes: Set<Long>, retained: Set<Int> = emptySet()): Boolean {
        var changed = false
        for (node in nodes) if (retireSection(node, retained)) changed = true
        return changed
    }

    private fun retireSection(node: Long, retained: Set<Int> = emptySet(), preserveCapture: Boolean = false): Boolean {
        dirtySpatialSources.add(node)
        residencyDemands.remove(node)
        retainedSources.remove(node)?.let { retainedSourceBytes -= it }
        var changed = producerManifests.remove(node) != null
        if (sourceRequirements.remove(node) != null) {
            changed = true
            updateSectionOutput(node)
        }
        if (!preserveCapture) {
            if (invalidSections.remove(node)) changed = true
            if (removeSectionCapture(node)) changed = true
        }
        for (layer in D3D12SectionGeometry.Layer.entries) {
            if (layer.ordinal in retained) continue
            val key = RecordKey(node, layer.ordinal)
            if (removeMissing(key)) changed = true
            records.remove(key)?.let { record ->
                retainDormant(key, record)
                changed = true
            }
        }
        return changed
    }

    private fun geometryByteCount(record: Record): Long =
        record.vertexCount.toLong() * STRIDE + record.indexCount.toLong() * record.indexStride +
            record.quadCount.toLong() * MATERIAL_BYTES + RECORD_BYTES

    private fun removeDormant(key: RecordKey): Record? = dormantRecords.remove(key)?.also {
        dormantBytes -= geometryByteCount(it)
    }

    private fun retainDormant(key: RecordKey, record: Record) {
        removeDormant(key)?.let(::releaseRecord)
        val byteCount = geometryByteCount(record)
        if (byteCount > DORMANT_BYTE_LIMIT) {
            releaseRecord(record)
            return
        }
        dormantRecords[key] = record
        dormantBytes += byteCount
        while (dormantRecords.size > DORMANT_RECORD_LIMIT || dormantBytes > DORMANT_BYTE_LIMIT) {
            evictDormant()
        }
    }

    private fun evictDormant(): Boolean {
        val oldest = dormantRecords.entries.firstOrNull() ?: return false
        val record = checkNotNull(removeDormant(oldest.key))
        releaseRecord(record)
        return true
    }

    private fun allocateStorage(vertexBytes: Int, indexBytes: Long, materialBytes: Long): Allocation? {
        if (vertexBytes > VERTEX_CAP || indexBytes !in 0..INDEX_CAP || materialBytes !in 0..MATERIAL_CAP) return null
        val indexSize = indexBytes.toInt()
        val materialSize = materialBytes.toInt()
        while (!vertices.canAllocate(vertexBytes) || !indices.canAllocate(indexSize) ||
            !materials.canAllocate(materialSize)) {
            if (!evictDormant()) return null
        }
        var vertexOffset: Int? = null
        var indexOffset: Int? = null
        var materialOffset: Int? = null
        var complete = false
        try {
            vertexOffset = checkNotNull(vertices.allocate(vertexBytes))
            indexOffset = checkNotNull(indices.allocate(indexSize))
            materialOffset = checkNotNull(materials.allocate(materialSize))
            val allocation = Allocation(vertexOffset, vertexBytes, indexOffset, indexSize, materialOffset, materialSize)
            complete = true
            return allocation
        } finally {
            if (!complete) {
                vertexOffset?.let { vertices.release(it, vertexBytes) }
                indexOffset?.let { indices.release(it, indexSize) }
                materialOffset?.let { materials.release(it, materialSize) }
            }
        }
    }

    private fun releaseStorage(allocation: Allocation) {
        vertices.release(allocation.vertexOffset, allocation.vertexBytes)
        indices.release(allocation.indexOffset, allocation.indexBytes)
        materials.release(allocation.materialOffset, allocation.materialBytes)
    }

    private fun releaseRecord(record: Record) {
        vertices.release(record.vertexOffset.toInt(), record.vertexCount * STRIDE)
        indices.release(record.indexOffset.toInt(), record.indexCount * record.indexStride)
        materials.release(record.materialOffset.toInt(), record.quadCount * MATERIAL_BYTES)
    }

    private fun matchesGeometry(previous: Record, vertex: ByteBuffer, index: ByteBuffer?,
                                indexCount: Int, indexStride: Int, includeShading: Boolean = true): Boolean {
        if (previous.vertexCount * STRIDE != vertex.remaining() || previous.indexCount != indexCount ||
            previous.indexStride != indexStride) return false
        val incoming = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val oldVertices = vertices.view(previous.vertexOffset, vertex.remaining())
        for (vertexIndex in 0 until previous.vertexCount) {
            val oldOffset = vertexIndex * STRIDE
            val newOffset = incoming.position() + vertexIndex * STRIDE
            if (oldVertices.getLong(oldOffset) != incoming.getLong(newOffset) ||
                oldVertices.getInt(oldOffset + 8) != incoming.getInt(newOffset + 8)) return false
            if (includeShading && (oldVertices.getInt(oldOffset + 12) != incoming.getInt(newOffset + 12) ||
                oldVertices.getLong(oldOffset + 16) != incoming.getLong(newOffset + 16))) return false
        }
        val oldIndices = indices.view(previous.indexOffset, indexCount * indexStride)
        if (index != null) return index.remaining() == oldIndices.remaining() && oldIndices == index
        val pattern = intArrayOf(0, 1, 2, 0, 2, 3)
        oldIndices.order(ByteOrder.LITTLE_ENDIAN)
        for (indexNumber in 0 until indexCount) {
            val actual = if (indexStride == 4) oldIndices.int else oldIndices.short.toInt() and 0xffff
            if (actual != indexNumber / QUAD_INDICES * QUAD_VERTICES + pattern[indexNumber % QUAD_INDICES]) return false
        }
        return true
    }

    @JvmStatic
    fun recordCount(): Int = records.size

    @JvmStatic
    fun records(): Collection<Record> = records.values

    @JvmStatic
    @Synchronized
    fun isCurrent(sectionNode: Long, layer: Int, geometryVersion: Int): Boolean =
        records[RecordKey(sectionNode, layer)]?.geometryVersion == geometryVersion

    data class Versions(val epoch: Int, val revision: Long, val records: ByteBuffer, val outputs: ByteBuffer,
                        val lightingRevision: Long = 0L)

    @JvmStatic
    @Synchronized
    fun lightingChangesSince(versions: Versions, afterRevision: Long): D3D12RtLightingChanges.Batch? {
        refreshSourceUniverse()
        if (versions.epoch != geometryEpoch || versions.revision != revision.get() ||
            versions.lightingRevision != lightingChanges.revision) return null
        return lightingChanges.since(versions.epoch, afterRevision)
    }

    private fun recordLightingChanges(nodes: Set<Long>, reason: D3D12RtLightingChanges.Reason,
                                      incoming: D3D12RtLightingChanges.Region? = null) {
        if (nodes.isEmpty()) return
        lightingChanges.record(reason, nodes.asSequence().map { node ->
            val universe = currentSourceUniverse.bounds
            val bounds = universe?.let { initial ->
                var combined: OutputBounds? = initial
                fun include(bounds: OutputBounds?) {
                    val current = combined
                    combined = if (current == null || bounds == null) null else
                        OutputBounds(minOf(current.minimumX, bounds.minimumX), minOf(current.minimumY, bounds.minimumY),
                            minOf(current.minimumZ, bounds.minimumZ), maxOf(current.maximumX, bounds.maximumX),
                            maxOf(current.maximumY, bounds.maximumY), maxOf(current.maximumZ, bounds.maximumZ))
                }
                for (layer in D3D12SectionGeometry.Layer.entries) {
                    val record = records[RecordKey(node, layer.ordinal)] ?: dormantRecords[RecordKey(node, layer.ordinal)]
                        ?: continue
                    include(record.outputBounds)
                }
                if (incoming?.sectionNode == node) include(incoming.bounds)
                combined
            }
            D3D12RtLightingChanges.Region(node, bounds)
        }.asIterable())
    }

    @JvmStatic
    @Synchronized
    fun versions(): Versions {
        refreshSourceUniverse()
        val currentRevision = revision.get()
        cachedVersions?.takeIf { it.epoch == geometryEpoch && it.revision == currentRevision &&
            it.lightingRevision == lightingChanges.revision }?.let { return it }
        refreshSpatialSources()
        val universe = currentSourceUniverse
        val data = ByteBuffer.allocate((records.size + missingVersions.size) * 16).order(ByteOrder.LITTLE_ENDIAN)
        val outputs = ByteBuffer.allocate((records.size + missingVersions.size + missingSections.size + unprovenOutputs.size) * OUTPUT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        fun output(node: Long, layer: Int, version: Int, bounds: OutputBounds?) {
            outputs.putLong(node).putInt(layer).putInt(version).putInt(if (bounds == null) 0 else 1).putInt(0)
            outputs.putFloat(bounds?.minimumX ?: 0f).putFloat(bounds?.minimumY ?: 0f)
                .putFloat(bounds?.minimumZ ?: 0f).putFloat(bounds?.maximumX ?: 0f)
                .putFloat(bounds?.maximumY ?: 0f).putFloat(bounds?.maximumZ ?: 0f)
        }
        for (record in records.values) {
            data.putLong(record.sectionNode).putInt(record.layer).putInt(record.geometryVersion)
            output(record.sectionNode, record.layer, record.geometryVersion, record.outputBounds)
        }
        for ([key, missing] in missingVersions) {
            data.putLong(key.sectionNode).putInt(key.layer).putInt(missing.version)
            val support = producerManifests[key.sectionNode]?.takeIf { it.versions[key.layer] == missing.version }
            output(key.sectionNode, key.layer, missing.version, support?.support?.bounds(key.layer) ?: universe.bounds)
        }
        appendSectionOutputs(outputs)
        for ([node, proof] in unprovenOutputs)
            output(node, SECTION_CAPTURE_LAYER, proof.version, proof.bounds ?: universe.bounds)
        data.flip()
        outputs.flip()
        return Versions(geometryEpoch, currentRevision, data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            outputs.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN), lightingChanges.revision)
            .also { cachedVersions = it }
    }

    private fun refreshSourceUniverse() {
        val universe = sourceUniverse.snapshot()
        if (universe !== currentSourceUniverse) {
            currentSourceUniverse = universe
            lightingChanges.recordFullDomain(D3D12RtLightingChanges.Reason.RESOURCES.mask)
            revision.incrementAndGet()
        }
    }

    @JvmStatic
    @Synchronized
    fun sourceInventory(): D3D12RtSpatialSources.Snapshot {
        val versions = versions()
        return sourceInventory(versions)
    }

    private fun refreshSpatialSources() {
        for (node in dirtySpatialSources.toLongArray()) {
            val currentLayers = buildList {
                for (layer in D3D12SectionGeometry.Layer.entries) {
                    val key = RecordKey(node, layer.ordinal)
                    records[key]?.let { add(D3D12RtSpatialSources.Layer(it.layer, it.geometryVersion, true)) }
                    missingVersions[key]?.let { add(D3D12RtSpatialSources.Layer(layer.ordinal, it.version, false)) }
                }
            }
            if (node !in spatialStates && currentLayers.isEmpty() && node !in missingSections && node !in producerManifests) {
                spatialSources.remove(node)
                unprovenOutputs.remove(node)
                updateCoverageTarget(node)
                continue
            }
            val producer = producerManifests[node]?.takeIf { candidate ->
                candidate.versions.size == currentLayers.size &&
                    currentLayers.all { it.version != null && candidate.versions[it.layer] == it.version }
            }
            val source = sourceRequirements[node]?.takeIf { it.version == missingSections[node]?.version }
            val support = (source?.support?.bounds ?: producer?.support?.sourceBounds)?.takeIf { bounds ->
                currentLayers.all { layer ->
                    if (!layer.captured) true else records[RecordKey(node, layer.layer)]?.outputBounds?.let { output ->
                        output.minimumX >= bounds.minimumX && output.minimumY >= bounds.minimumY &&
                            output.minimumZ >= bounds.minimumZ && output.maximumX <= bounds.maximumX &&
                            output.maximumY <= bounds.maximumY && output.maximumZ <= bounds.maximumZ
                    } == true
                }
            }
            val requiredLayers = if (producer == null || producer.requiredLayers.all { required ->
                currentLayers.any { it.layer == required }
            }) currentLayers else (currentLayers + producer.requiredLayers.filter { required ->
                currentLayers.none { it.layer == required }
            }.map { D3D12RtSpatialSources.Layer(it, null, false) }).sortedBy { it.layer }
            val state = spatialStates[node]?.code ?: 0
            val scope = source?.support?.scope ?: producer?.support?.scope
            val captureVersion = missingSections[node]?.version
            val previous = spatialSources[node]
            if (previous == null || previous.let {
                it.state != state || it.bounds != support || it.scope !== scope ||
                    it.captureVersion != captureVersion || it.layers != requiredLayers || it.layerSetKnown != (producer != null)
            }) spatialSources[node] = D3D12RtSpatialSources.Source(
                node, state, support, scope, captureVersion, requiredLayers, producer != null)
            val unproven = state == SpatialState.LOADED_UNPROVEN.code && captureVersion == null &&
                (producer == null || support == null || scope?.epoch != geometryEpoch || requiredLayers.any { it.version == null })
            if (unproven) {
                val old = unprovenOutputs[node]
                unprovenOutputs[node] = UnprovenOutput(old?.version ?: ++nextSectionCaptureVersion, support)
            } else unprovenOutputs.remove(node)
            updateCoverageTarget(node)
        }
        dirtySpatialSources.clear()
    }

    private fun sourceInventory(versions: Versions): D3D12RtSpatialSources.Snapshot {
        cachedSpatialSources?.takeIf { it.epoch == versions.epoch && it.revision == versions.revision }?.let { return it }
        refreshSpatialSources()
        val sources = java.util.List.copyOf(spatialSources.values)
        return D3D12RtSpatialSources.Snapshot(versions.epoch, versions.revision, { sources }, currentSourceUniverse)
            .also { cachedSpatialSources = it }
    }

    data class Coverage(val versions: Versions, val nodes: ByteBuffer, val spatial: ByteBuffer,
                        val centerX: Float, val centerY: Float, val centerZ: Float, val radius: Float,
                        val sources: D3D12RtSpatialSources.Snapshot, val spatialUpdate: D3D12RtSpatialDirectory.Update,
                        val region: D3D12RtSpatialDirectory.Region, val request: D3D12RtFullDomainProfile.Request? = null)

    private fun spatialGrid(region: D3D12RtSpatialDirectory.Region,
                            sources: D3D12RtSpatialSources.Snapshot,
                            targets: D3D12RtSpatialDirectory.Targets): D3D12RtSpatialDirectory.Build {
        cachedSpatialGrid?.takeIf { it.sources === sources && it.region == region && it.targets == targets }?.let {
            return it.build
        }
        val frozen = spatialDirectory.build(region, sources, targets)
        cachedSpatialGrid = SpatialGrid(sources, region, targets, frozen)
        return frozen
    }

    private fun selectsTarget(record: Record, selection: TargetSelection): Boolean =
        if (selection.primary == null)
            outputDistanceSquared(record, selection.centerX, selection.centerY, selection.centerZ) <=
                selection.radius.toDouble() * selection.radius
        else outputIntersects(record, selection.primary)

    private fun selectsObligation(node: Long, selection: TargetSelection): Boolean {
        val bounds = spatialSources[node]?.bounds ?: selection.universe.bounds ?: return true
        return outputIntersects(node, bounds, selection.query)
    }

    private fun updateCoverageTarget(node: Long) {
        val selection = targetSelection ?: return
        val obligation = node in invalidSections || node in unprovenOutputs || D3D12SectionGeometry.Layer.entries.any { layer ->
            RecordKey(node, layer.ordinal) in missingVersions
        }
        val required = obligation && selectsObligation(node, selection) || D3D12SectionGeometry.Layer.entries.any { layer ->
            val key = RecordKey(node, layer.ordinal)
            records[key]?.let { selectsTarget(it, selection) } == true
        }
        val changed = if (required) targetMembers.add(node) else targetMembers.remove(node)
        if (changed) targetSnapshot = null
    }

    private fun coverageTargets(selection: TargetSelection): TargetSnapshot {
        if (targetSelection != selection) {
            val members = LongOpenHashSet()
            for (record in records.values) if (selectsTarget(record, selection)) members.add(record.sectionNode)
            for (node in invalidSections) if (selectsObligation(node, selection)) members.add(node)
            for (node in unprovenOutputs.keys) if (selectsObligation(node, selection)) members.add(node)
            for (key in missingVersions.keys) if (selectsObligation(key.sectionNode, selection)) members.add(key.sectionNode)
            if (members != targetMembers) {
                targetMembers = members
                targetSnapshot = null
            }
            targetSelection = selection
        }
        return targetSnapshot ?: run {
            val bytes = ByteBuffer.allocate(targetMembers.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (node in targetMembers.toLongArray().apply { sort() }) bytes.putLong(node)
            bytes.flip()
            TargetSnapshot(D3D12RtSpatialDirectory.Targets.freeze(targetMembers),
                bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)).also { targetSnapshot = it }
        }
    }

    private fun clearCoverageTargets() {
        targetSelection = null
        targetMembers = LongOpenHashSet()
        targetSnapshot = null
    }

    private fun outputBounds(vertex: ByteBuffer): OutputBounds? {
        if (!vertex.hasRemaining() || vertex.remaining() % STRIDE != 0) return null
        val source = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var minimumX = 0f
        var minimumY = 0f
        var minimumZ = 0f
        var maximumX = 16f
        var maximumY = 16f
        var maximumZ = 16f
        for (offset in source.position() until source.limit() step STRIDE) {
            val positionX = source.getFloat(offset)
            val positionY = source.getFloat(offset + 4)
            val positionZ = source.getFloat(offset + 8)
            if (!positionX.isFinite() || !positionY.isFinite() || !positionZ.isFinite()) return null
            minimumX = minOf(minimumX, positionX)
            minimumY = minOf(minimumY, positionY)
            minimumZ = minOf(minimumZ, positionZ)
            maximumX = maxOf(maximumX, positionX)
            maximumY = maxOf(maximumY, positionY)
            maximumZ = maxOf(maximumZ, positionZ)
        }
        return OutputBounds(minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ)
    }

    private fun outputDistanceSquared(record: Record, centerX: Float, centerY: Float, centerZ: Float): Double {
        val bounds = record.outputBounds ?: return 0.0
        val localX = centerX.toDouble() - record.originX
        val localY = centerY.toDouble() - record.originY
        val localZ = centerZ.toDouble() - record.originZ
        val deltaX = maxOf(bounds.minimumX - localX, 0.0, localX - bounds.maximumX)
        val deltaY = maxOf(bounds.minimumY - localY, 0.0, localY - bounds.maximumY)
        val deltaZ = maxOf(bounds.minimumZ - localZ, 0.0, localZ - bounds.maximumZ)
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
    }

    private fun outputIntersects(record: Record, region: D3D12RtSpatialDirectory.Region): Boolean {
        val bounds = record.outputBounds ?: return true
        return outputIntersects(record.sectionNode, bounds, region)
    }

    private fun outputIntersects(node: Long, bounds: OutputBounds, region: D3D12RtSpatialDirectory.Region): Boolean {
        fun overlaps(origin: Int, lower: Float, upper: Float, minimum: Int, size: Int): Boolean =
            origin.toDouble() + lower <= (minimum.toLong() + size) * 16.0 &&
                origin.toDouble() + upper >= minimum.toLong() * 16.0
        return overlaps(SectionPos.x(node) * 16, bounds.minimumX, bounds.maximumX, region.minimumX, region.sizeX) &&
            overlaps(SectionPos.y(node) * 16, bounds.minimumY, bounds.maximumY, region.minimumY, region.sizeY) &&
            overlaps(SectionPos.z(node) * 16, bounds.minimumZ, bounds.maximumZ, region.minimumZ, region.sizeZ)
    }

    @JvmStatic
    @Synchronized
    fun coverageNear(centerX: Float, centerY: Float, centerZ: Float, radius: Float): Coverage =
        coverage(centerX, centerY, centerZ, radius, null)

    @JvmStatic
    @Synchronized
    fun coverageFor(request: D3D12RtFullDomainProfile.Request, centerX: Float, centerY: Float,
                    centerZ: Float, radius: Float): Coverage = coverage(centerX, centerY, centerZ, radius, request)

    private fun coverage(centerX: Float, centerY: Float, centerZ: Float, radius: Float,
                         request: D3D12RtFullDomainProfile.Request?): Coverage {
        require(centerX.isFinite() && centerY.isFinite() && centerZ.isFinite() && radius.isFinite() && radius >= 0f)
        val versions = versions()
        val sources = sourceInventory(versions)
        val region = request?.let { D3D12RtFullDomainProfile.directory(it, sources.universe) }
            ?: D3D12RtSpatialDirectory.region(centerX, centerY, centerZ, radius)
        cachedCoverage?.takeIf {
            it.versions === versions && it.centerX == centerX && it.centerY == centerY &&
                it.centerZ == centerZ && it.radius == radius && it.region == region && it.request == request
        }?.let { return it }
        val selection = if (request == null) TargetSelection(centerX, centerY, centerZ, radius, null, region, sources.universe)
            else TargetSelection(0f, 0f, 0f, 0f, request.primary, region, sources.universe)
        val targets = coverageTargets(selection)
        val directory = spatialGrid(region, sources, targets.members)
        return Coverage(versions, targets.bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            directory.bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            centerX, centerY, centerZ, radius, sources, directory.update, region, request)
            .also { cachedCoverage = it }
    }

    @JvmStatic
    @Synchronized
    fun changedSections(published: ByteBuffer, current: ByteBuffer): Set<Long> {
        val before = published.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val after = current.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val versions = mutableMapOf<RecordKey, Int>()
        val changed = mutableSetOf<Long>()
        for (base in 0 until before.limit() step RECORD_BYTES) {
            val node = before.getLong(base + 56)
            val key = RecordKey(node, before.getInt(base + 12))
            val version = before.getInt(base + 44)
            versions[key] = version
            if (records[key]?.geometryVersion != version) changed += node
        }
        for (base in 0 until after.limit() step RECORD_BYTES) {
            val node = after.getLong(base + 56)
            val key = RecordKey(node, after.getInt(base + 12))
            if (versions[key] != after.getInt(base + 44)) changed += node
        }
        return changed
    }

    @JvmStatic
    fun triangleCount(): Long = records.values.sumOf { it.indexCount.toLong() / 3 }

    /** 顶点 / 索引 / 材质池的独立只读快照，逻辑偏移与 Record 对应。 */
    @JvmStatic
    @Synchronized
    fun vertexBytes(): ByteBuffer = vertices.snapshot()

    @JvmStatic
    @Synchronized
    fun indexBytes(): ByteBuffer = indices.snapshot()

    @JvmStatic
    @Synchronized
    fun materialBytes(): ByteBuffer = materials.snapshot()

    data class StorageStats(
        val vertexBytes: Long,
        val indexBytes: Long,
        val materialBytes: Long,
        val highWaterBytes: Long,
        val allocationCount: Int,
        val dormantRecordCount: Int,
        val dormantBytes: Long,
        val retainedContentBytes: Long,
        val retainedSourceSections: Int,
        val retainedSourceBytes: Long,
    ) {
        val residentBytes: Long get() = vertexBytes + indexBytes + materialBytes
    }

    @JvmStatic
    @Synchronized
    fun storageStats(): StorageStats {
        var retainedBytes = 0L
        retainedContent.removeAll { reference ->
            val allocation = reference.get()
            if (allocation == null) true else {
                retainedBytes += allocation.byteCount
                false
            }
        }
        return StorageStats(vertices.allocatedBytes, indices.allocatedBytes,
            materials.allocatedBytes, vertices.highWater.toLong() + indices.highWater + materials.highWater,
            vertices.allocationCount + indices.allocationCount + materials.allocationCount,
            dormantRecords.size, dormantBytes, retainedBytes, retainedSources.size, retainedSourceBytes)
    }

    @JvmStatic
    fun summary(): String = "rt geometry pool: records=${records.size} published=${published.get()} " +
        "skipped=${skipped.get()} full=$full triangles=${triangleCount()} " +
        "v=${vertices.allocatedBytes shr 20}MB i=${indices.allocatedBytes shr 20}MB " +
        "m=${materials.allocatedBytes shr 20}MB copied=${copiedBytes.get() shr 20}MB"

    /**
     * 材质表刚烘好时回填全部记录的材质索引：表就绪之前的上传只能写兜底值。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun refreshMaterials(): Int {
        if (materials.allocatedBytes == 0L) {
            return 0
        }
        var updated = 0
        for (record in records.values) {
            if (record.quadCount <= 0) continue
            val vertexData = vertices.view(record.vertexOffset, record.vertexCount * STRIDE)
            val materialIds = D3D12RtMaterialTable.quadMaterials(vertexData, 0, record.quadCount)
            if (remapMaterials(record, materialIds)) updated++
        }
        if (updated > 0) {
            revision.incrementAndGet()
            mirroredSlice = null
        }
        return updated
    }

    private fun remapMaterials(record: Record, ids: ShortArray): Boolean {
        val previous = materials.view(record.materialOffset, record.quadCount * MATERIAL_BYTES)
        if (ids.indices.all { previous.getShort(it * MATERIAL_BYTES) == ids[it] }) return false
        val replacement = ByteBuffer.allocate(previous.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        for (id in ids) replacement.putShort(id)
        replacement.flip()
        materials.replace(record.materialOffset, replacement)
        return true
    }

    /**
     * 每条记录的索引必须落在自己的顶点区间内；返回违规记录数。
     * 偏移或步长错一位时首个索引就会越界，抽样足够抓住结构错误。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun validate(): Int {
        var bad = 0
        for (record in records.values) {
            if (record.indexCount <= 0) continue
            val view = indices.view(record.indexOffset, record.indexCount * record.indexStride)
            val step = (record.indexCount / VALIDATE_SAMPLES).coerceAtLeast(1)
            var violation = false
            var indexNumber = 0
            while (indexNumber < record.indexCount) {
                val offset = indexNumber * record.indexStride
                if (offset + record.indexStride > view.limit()) {
                    violation = true
                    break
                }
                val value = if (record.indexStride == 4) view.getInt(offset) else view.getShort(offset).toInt() and 0xFFFF
                if (value >= record.vertexCount) {
                    violation = true
                    break
                }
                indexNumber += step
            }
            if (!violation) {
                val last = (record.indexCount - 1) * record.indexStride
                if (last + record.indexStride > view.limit()) {
                    violation = true
                } else {
                    val value = if (record.indexStride == 4) view.getInt(last) else view.getShort(last).toInt() and 0xFFFF
                    violation = value >= record.vertexCount
                }
            }
            if (violation) {
                bad++
            }
        }
        return bad
    }

    private const val VALIDATE_SAMPLES = 64

    /** 一条记录在 GPU 侧的字节数（64 B，见 rt_plan §3.4 的 `SectionRecord`）。 */
    const val RECORD_BYTES = 64

    /**
     * 把当前所有记录打包成 `RECORD_BYTES` 步长的字节块，供 blocks 当 `ByteAddressBuffer` 读。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun recordBytes(): ByteBuffer {
        val ordered = records.values.sortedWith(
            compareBy({ it.sectionNode }, { it.layer }))
        val buffer = ByteBuffer.allocate(ordered.size * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (r in ordered) {
            buffer.putInt(r.originX)
            buffer.putInt(r.originY)
            buffer.putInt(r.originZ)
            buffer.putInt(r.layer)
            buffer.putLong(r.vertexOffset)
            buffer.putLong(r.indexOffset)
            buffer.putInt(r.vertexCount)
            buffer.putInt(r.indexCount)
            buffer.putInt(r.indexStride or (r.flags shl 8))
            buffer.putInt(r.geometryVersion)
            buffer.putInt(r.materialOffset.toInt())
            buffer.putInt(r.quadCount)
            buffer.putLong(r.sectionNode)
        }
        buffer.flip()
        return buffer
    }

    /**
     * 相机附近那一小片几何的紧凑副本，供 compute 上传成镜像缓冲。
     * 的 memcpy 加一次大分配，画面会直接卡死。这里只挑相机附近、且总量封顶的一批段，几十毫秒就能传完。
     * 见 docs/rt/README.md
     */
    class MirrorSlice(
        val geometryEpoch: Int,
        val geometryRevision: Long,
        val records: ByteBuffer,
        val contents: List<ContentRecord>,
        val vertexBytes: Int,
        val indexBytes: Int,
        val quadBytes: Int,
        val recordCount: Int,
        val triangles: Long,
    ) {
        val vertices: ByteBuffer by lazy { pack(vertexBytes, 16) { it.vertices } }
        val indices: ByteBuffer by lazy { pack(indexBytes, 24) { it.indices } }
        val quads: ByteBuffer by lazy { pack(quadBytes, 48) { it.quads } }

        private fun pack(byteCount: Int, offsetField: Int, select: (ContentRecord) -> Content): ByteBuffer {
            val output = ByteBuffer.allocateDirect(pad4(byteCount.toLong())).order(ByteOrder.LITTLE_ENDIAN)
            for ([index, entry] in contents.withIndex()) {
                val offset = if (offsetField == 48) records.getInt(index * RECORD_BYTES + offsetField) * MATERIAL_BYTES
                    else records.getLong(index * RECORD_BYTES + offsetField).toInt()
                output.position(offset)
                output.put(select(entry).bytes)
            }
            output.limit(byteCount).position(0)
            return output.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /**
     * 以当前输出界选择完整段层组，导出 64 B 记录与不可变内容清单。
     * 见 docs/rt/README.md
     */
    private fun pad4(bytes: Long): Int = ((bytes + 3L) and -4L).toInt()

    private val contractLog = java.util.concurrent.atomic.AtomicBoolean(false)

    data class ResidencyBudget(val byteLimit: Long, val recordLimit: Int = 65535,
                               val vertexCapacity: Long = 256L shl 20, val indexCapacity: Long = 64L shl 20,
                               val quadCapacity: Long = 8L shl 20) {
        init {
            require(byteLimit > 0 && recordLimit in 1..65535)
            require(listOf(vertexCapacity, indexCapacity, quadCapacity).all { it in 16..(512L shl 20) && it % 4L == 0L })
            require(vertexCapacity + indexCapacity + quadCapacity <= 512L shl 20)
        }

        fun capacities(): LongArray = longArrayOf(vertexCapacity, indexCapacity, quadCapacity)
    }

    enum class ResidencyLimit { RECORDS, PAYLOAD_BYTES, VERTEX_ARENA, INDEX_ARENA, QUAD_ARENA }

    data class Residency(val request: D3D12RtFullDomainProfile.Request, val budget: ResidencyBudget,
                         val geometryEpoch: Int, val geometryRevision: Long,
                         val requestedGroups: Int, val requestedRecords: Int, val requestedBytes: Long,
                         val selectedGroups: Int, val selectedBytes: Long,
                         val limits: Map<ResidencyLimit, Int>, val slice: MirrorSlice?,
                         val superseded: Boolean = false, val demandedGroups: Int = 0,
                         val admittedDemands: Map<Long, Long> = emptyMap()) {
        val omittedGroups: Int get() = requestedGroups - selectedGroups
    }

    private data class MirrorSelection(val epoch: Int, val revision: Long, val records: List<Record>,
                                       val required: Boolean, val cachedRecords: List<Record>,
                                       val cachedSlice: MirrorSlice?, val demands: Map<Long, ResidencyDemand>)

    private data class MirrorGroup(val node: Long, val records: List<Record>, val demandOrder: Int,
                                   val preferredOrder: Int, val distanceSquared: Double,
                                   val vertexBytes: Long, val indexBytes: Long, val quadBytes: Long) {
        val byteCount: Long = vertexBytes + indexBytes + quadBytes + records.size * RECORD_BYTES.toLong()
    }

    private fun mirrorSelection(): MirrorSelection = synchronized(D3D12RtGeometryPool::class.java) {
        refreshSourceUniverse()
        pruneResidencyDemands(System.nanoTime())
        MirrorSelection(geometryEpoch, revision.get(), records.values.toList(),
            invalidSections.isNotEmpty() || missingVersions.isNotEmpty(), mirroredRecords, mirroredSlice,
            residencyDemands.toMap())
    }

    private fun isCurrent(selection: MirrorSelection): Boolean =
        selection.epoch == geometryEpoch && selection.revision == revision.get()

    @JvmStatic
    @JvmOverloads
    fun mirrorFor(request: D3D12RtFullDomainProfile.Request, x: Float, y: Float, z: Float,
                  budget: ResidencyBudget, preferredNodes: Set<Long> = emptySet()): Residency {
        require(x.isFinite() && y.isFinite() && z.isFinite())
        synchronized(D3D12RtGeometryPool::class.java) {
            residencyDemands.values.removeAll { demand ->
                demand.records.none { outputIntersects(it, request.primary) }
            }
        }
        return selectMirror(mirrorSelection(), request, x, y, z, budget, preferredNodes)
    }

    private fun selectMirror(selection: MirrorSelection, request: D3D12RtFullDomainProfile.Request,
                             x: Float, y: Float, z: Float, budget: ResidencyBudget,
                             preferredNodes: Set<Long>): Residency {
        val demandOrder = selection.demands.keys.withIndex().associate { [index, node] -> node to index }
        val intersecting = selection.records.filter { outputIntersects(it, request.primary) }
            .mapTo(LongOpenHashSet()) { it.sectionNode }
        val grouped = Long2ObjectOpenHashMap<MutableList<Record>>()
        for (record in selection.records) {
            if (record.sectionNode in intersecting) grouped.getOrPut(record.sectionNode) { mutableListOf() }.add(record)
        }
        val groups = grouped.values.map { records ->
            val node = records.first().sectionNode
            MirrorGroup(node, records, demandOrder[node] ?: Int.MAX_VALUE, if (node in preferredNodes) 0 else 1,
                records.minOf { outputDistanceSquared(it, x, y, z) },
                records.sumOf { pad4(it.vertexCount.toLong() * STRIDE).toLong() },
                records.sumOf { pad4(it.indexCount.toLong() * it.indexStride).toLong() },
                records.sumOf { pad4(it.quadCount.toLong() * MATERIAL_BYTES).toLong() })
        }.sortedWith(compareBy<MirrorGroup> { it.demandOrder }.thenBy { it.preferredOrder }
            .thenBy { it.distanceSquared }.thenBy { it.node })
        val admittedDemands = mutableMapOf<Long, Long>()
        val chosen = ArrayList<Record>(minOf(selection.records.size, budget.recordLimit))
        val limits = mutableMapOf<ResidencyLimit, Int>()
        var vertexBytes = 0L
        var indexBytes = 0L
        var quadBytes = 0L
        var requestedBytes = 0L
        var requestedRecords = 0
        var selectedGroups = 0
        for (group in groups) {
            requestedBytes += group.byteCount
            requestedRecords += group.records.size
            val exceeded = buildSet {
                if (group.records.size > budget.recordLimit - chosen.size) add(ResidencyLimit.RECORDS)
                if (vertexBytes + indexBytes + quadBytes + chosen.size * RECORD_BYTES.toLong() + group.byteCount > budget.byteLimit)
                    add(ResidencyLimit.PAYLOAD_BYTES)
                if (vertexBytes + group.vertexBytes > budget.vertexCapacity) add(ResidencyLimit.VERTEX_ARENA)
                if (indexBytes + group.indexBytes > budget.indexCapacity) add(ResidencyLimit.INDEX_ARENA)
                if (quadBytes + group.quadBytes > budget.quadCapacity) add(ResidencyLimit.QUAD_ARENA)
            }
            if (exceeded.isNotEmpty()) {
                for (reason in exceeded) limits[reason] = (limits[reason] ?: 0) + 1
                continue
            }
            chosen.addAll(group.records)
            selection.demands[group.node]?.let { demand ->
                admittedDemands[group.node] = demand.identity
            }
            selectedGroups++
            vertexBytes += group.vertexBytes
            indexBytes += group.indexBytes
            quadBytes += group.quadBytes
        }
        val required = intersecting.isNotEmpty() || selection.required
        val slice = if (required) mirrorSlice(selection, chosen) else null
        val superseded = if (required) slice == null else synchronized(D3D12RtGeometryPool::class.java) {
            !isCurrent(selection)
        }
        return Residency(request, budget, selection.epoch, selection.revision, groups.size, requestedRecords,
            requestedBytes, selectedGroups, vertexBytes + indexBytes + quadBytes + chosen.size * RECORD_BYTES.toLong(),
            limits.toMap(), slice, superseded, intersecting.count { it in selection.demands }, admittedDemands.toMap())
    }

    private fun mirrorSlice(selection: MirrorSelection, chosen: MutableList<Record>): MirrorSlice? {
        chosen.sortWith(compareBy<Record> { it.sectionNode }.thenBy { it.layer })
        val cached = selection.cachedSlice?.takeIf { selection.cachedRecords == chosen }
        val slice = cached ?: run {
            val contents = synchronized(D3D12RtGeometryPool::class.java) {
                if (!isCurrent(selection)) return null
                chosen.map(::exportContent)
            }
            packMirror(selection.epoch, selection.revision, contents)
        }
        return publishMirror(selection, chosen, slice)
    }

    private fun publishMirror(selection: MirrorSelection, chosen: List<Record>, slice: MirrorSlice): MirrorSlice? =
        synchronized(D3D12RtGeometryPool::class.java) {
            if (!isCurrent(selection)) return null
            mirroredRecords = chosen
            mirroredSlice = slice
            slice
        }

    private fun exportContent(record: Record): ContentRecord = ContentRecord(record,
        vertices.export(record.vertexOffset, record.vertexCount * STRIDE),
        indices.export(record.indexOffset, record.indexCount * record.indexStride),
        materials.export(record.materialOffset, record.quadCount * MATERIAL_BYTES))

    @JvmStatic
    @Synchronized
    fun mirrorNear(x: Float, y: Float, z: Float, radius: Float, maxRecords: Int,
                   maxBytes: Long): MirrorSlice? {
        if (maxRecords <= 0 || maxBytes <= 0) {
            return null
        }
        require(x.isFinite() && y.isFinite() && z.isFinite() && radius.isFinite() && radius >= 0f)
        val limit = radius.toDouble() * radius
        val intersecting = hashSetOf<Long>()
        for (record in records.values) {
            if (outputDistanceSquared(record, x, y, z) <= limit) intersecting += record.sectionNode
        }
        val picked = ArrayList<Pair<Record, Double>>(records.size.coerceAtMost(256))
        for (record in records.values) {
            if (record.sectionNode !in intersecting) continue
            val deltaX = record.originX + 8.0 - x
            val deltaY = record.originY + 8.0 - y
            val deltaZ = record.originZ + 8.0 - z
            val distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
            picked.add(record to distance)
        }
        picked.sortWith(compareBy<Pair<Record, Double>> { it.second }.thenBy { it.first.sectionNode })
        // 先按上限挑出真正要装的段，算出精确字节数再分配：一次装不下的大段不能撑爆输出缓冲。
        val chosen = ArrayList<Record>(minOf(picked.size, maxRecords))
        var verticesBytes = 0L
        var indicesBytes = 0L
        var quadsBytes = 0L
        for (sectionRecords in picked.groupBy({ it.first.sectionNode }, { it.first }).values) {
            if (sectionRecords.size > maxRecords - chosen.size) break
            val vertexBytes = sectionRecords.sumOf { it.vertexCount.toLong() * STRIDE }
            val indexBytes = sectionRecords.sumOf { pad4(it.indexCount.toLong() * it.indexStride).toLong() }
            val quadBytes = sectionRecords.sumOf { it.quadCount.toLong() * MATERIAL_BYTES }
            if (verticesBytes + indicesBytes + quadsBytes + vertexBytes + indexBytes + quadBytes > maxBytes) {
                break
            }
            chosen.addAll(sectionRecords)
            verticesBytes += vertexBytes
            indicesBytes += indexBytes
            quadsBytes += quadBytes
        }
        if (chosen.isEmpty() && !coverageNear(x, y, z, radius).nodes.hasRemaining()) {
            return null
        }
        return packMirror(chosen)
    }

    private fun packMirror(chosen: MutableList<Record>): MirrorSlice {
        chosen.sortWith(compareBy<Record> { it.sectionNode }.thenBy { it.layer })
        mirroredSlice?.let { cached ->
            if (mirroredRecords == chosen) return cached
        }
        return packMirror(geometryEpoch, revision.get(), chosen.map(::exportContent)).also {
            mirroredRecords = chosen
            mirroredSlice = it
        }
    }

    private fun packMirror(epoch: Int, sourceRevision: Long, contents: List<ContentRecord>): MirrorSlice {
        // raw SRV 的元素粒度是 4 字节（NumElements = size/4），长度不是 4 的倍数时最后几个字节读不到：
        // 索引按 u16 计，奇数个索引就只有 2 字节余量，会把三条边之一读成 0 得到退化三角形。
        val recordsOut = ByteBuffer.allocateDirect(contents.size * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var vertexPosition = 0L
        var indexPosition = 0L
        var quadPosition = 0
        var triangles = 0L
        for (entry in contents) {
            val record = entry.record
            val vertexBytes = record.vertexCount.toLong() * STRIDE
            val indexBytes = record.indexCount.toLong() * record.indexStride
            val quadBytes = record.quadCount.toLong() * MATERIAL_BYTES
            recordsOut.putInt(record.originX)
            recordsOut.putInt(record.originY)
            recordsOut.putInt(record.originZ)
            recordsOut.putInt(record.layer)
            indexPosition = pad4(indexPosition).toLong()
            recordsOut.putLong(vertexPosition)
            recordsOut.putLong(indexPosition)
            recordsOut.putInt(record.vertexCount)
            recordsOut.putInt(record.indexCount)
            recordsOut.putInt(record.indexStride or (record.flags shl 8))
            recordsOut.putInt(record.geometryVersion)
            // 材质偏移以 quad 为单位（shader 里 `RtQuadMaterial` 自己乘 2），不是字节偏移。
            recordsOut.putInt(quadPosition / MATERIAL_BYTES)
            recordsOut.putInt(record.quadCount)
            recordsOut.putLong(record.sectionNode)
            vertexPosition += vertexBytes
            indexPosition += indexBytes
            quadPosition += quadBytes.toInt()
            triangles += record.triangles
        }
        // 一次性对账：记录原点与顶点坐标必须分别是「世界坐标」与「段内局部坐标」，
        // 两者语义搞反了（或都加了原点）会让几何整体跑到别处，射线一条都打不到。
        if (contractLog.compareAndSet(false, true) && contents.isNotEmpty()) {
            val first = recordsOut.getInt(0)
            val second = recordsOut.getInt(4)
            val third = recordsOut.getInt(8)
            val firstVertices = contents.first().vertices.bytes
            val vx = firstVertices.getFloat(0)
            val vy = firstVertices.getFloat(4)
            val vz = firstVertices.getFloat(8)
            io.github.dxegp.misc.IllusionLogger.infoMessage(
                "rt mirror: origin=($first,$second,$third) vertex=($vx,$vy,$vz)")
        }
        recordsOut.flip()
        return MirrorSlice(epoch, sourceRevision, recordsOut.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            contents, vertexPosition.toInt(), pad4(indexPosition), quadPosition, contents.size, triangles)
    }

    /** 逐层三角形数与顶点数的一行摘要，验收时与 `section geometry` 行对账。 */
    @JvmStatic
    fun layerSummary(): String {
        val byLayer = IntArray(4)
        val verts = LongArray(4)
        for (r in records.values) {
            byLayer[r.layer] += r.triangles
            verts[r.layer] += r.vertexCount.toLong()
        }
        return "rt geometry pool layers: solid=${byLayer[0]} cutout=${byLayer[1]} " +
            "translucent=${byLayer[2]} other=${byLayer[3]} | v solid=${verts[0]} cutout=${verts[1]}"
    }

    @JvmStatic
    @Synchronized
    fun clear(epoch: Int) {
        geometryEpoch = epoch
        lightingChanges.reset(epoch)
        cachedVersions = null
        cachedSpatialSources = null
        spatialSources.clear()
        unprovenOutputs.clear()
        dirtySpatialSources.clear()
        cachedCoverage = null
        clearCoverageTargets()
        cachedSpatialGrid = null
        spatialDirectory.clear()
        sectionPublication = null
        if (records.isNotEmpty()) {
            resets.incrementAndGet()
        }
        full = false
        mirroredRecords = emptyList()
        mirroredSlice = null
        revision.incrementAndGet()
        records.clear()
        missingVersions.clear()
        missingSections.clear()
        clearSectionOutputs()
        producerManifests.clear()
        sourceRequirements.clear()
        captureAgeDirty = true
        oldestCaptureNanos = null
        invalidSections.clear()
        spatialStates.clear()
        captureOrder.clear()
        clearCaptureDemands()
        clearResidencyDemands()
        dormantRecords.clear()
        dormantBytes = 0L
        retainedSources.clear()
        retainedSourceBytes = 0L
        resetPools()
    }

    private fun resetPools() {
        revision.incrementAndGet()
        vertices = GeometryArena(VERTEX_CAP.toInt())
        indices = GeometryArena(INDEX_CAP.toInt())
        materials = GeometryArena(MATERIAL_CAP.toInt())
    }

    /** 原版共享顺序 quad 缓冲的模式：`(4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3)`。 */
    private fun writeSequentialQuadIndices(target: ByteBuffer, quadCount: Int, stride: Int) {
        val pattern = IntArray(QUAD_INDICES)
        for (q in 0 until quadCount) {
            val base = q * QUAD_VERTICES
            pattern[0] = base
            pattern[1] = base + 1
            pattern[2] = base + 2
            pattern[3] = base
            pattern[4] = base + 2
            pattern[5] = base + 3
            for (value in pattern) {
                if (stride == 4) target.putInt(value) else target.putShort(value.toShort())
            }
        }
    }
}


