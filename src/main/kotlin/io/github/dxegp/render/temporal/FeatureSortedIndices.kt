package io.github.dxegp.render.temporal

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import net.minecraft.client.renderer.StagedVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap

internal data class FeaturePrimitiveOrder(val firstVertex: Int, val triangleRole: Int)

internal data class FeatureSortedIndexDescription(
    val vertexCount: Int,
    val indexCount: Int,
    val vertexByteOffset: Int,
    val indexByteOffset: Int,
    val stride: Int,
    val indexType: IndexType,
)

internal class FeatureSortedIndexMapping(
    val generation: Long,
    val vertexBuffer: GpuBuffer,
    val indexBuffer: GpuBuffer,
    private val description: FeatureSortedIndexDescription,
    primitives: List<FeaturePrimitiveOrder>,
) {
    val primitives: List<FeaturePrimitiveOrder> = Collections.unmodifiableList(primitives.toList())
    @Volatile
    var valid = true
        private set

    fun revoke() {
        valid = false
    }

    fun matches(execution: StagedVertexBuffer.ExecuteInfo, vertexCount: Int, stride: Int): Boolean =
        valid && !vertexBuffer.isClosed && !indexBuffer.isClosed &&
            execution.topology() == PrimitiveTopology.QUADS && execution.vertexBuffer() === vertexBuffer &&
            execution.customIndexBuffer() === indexBuffer && execution.indexType() == description.indexType &&
            execution.firstIndex().toLong() * description.indexType.bytes == description.indexByteOffset.toLong() &&
            execution.indexCount() == description.indexCount && vertexCount == description.vertexCount &&
            stride == description.stride &&
            execution.baseVertex().toLong() * stride == description.vertexByteOffset.toLong()

    fun overlaps(slice: GpuBufferSlice): Boolean {
        if (slice.length() <= 0L) return false
        val start: Long
        val length: Long
        when {
            slice.buffer() === indexBuffer -> {
                start = description.indexByteOffset.toLong()
                length = description.indexCount.toLong() * description.indexType.bytes
            }
            slice.buffer() === vertexBuffer -> {
                start = description.vertexByteOffset.toLong()
                length = description.vertexCount.toLong() * description.stride
            }
            else -> return false
        }
        return if (slice.offset() <= start) start - slice.offset() < slice.length()
            else slice.offset() - start < length
    }

}

internal object FeatureSortedIndexWrites {
    private val mappings = IdentityHashMap<GpuBuffer, MutableSet<FeatureSortedIndexMapping>>()

    @Synchronized
    fun register(mapping: FeatureSortedIndexMapping) {
        mappings.getOrPut(mapping.vertexBuffer) { LinkedHashSet() }.add(mapping)
        mappings.getOrPut(mapping.indexBuffer) { LinkedHashSet() }.add(mapping)
    }

    @Synchronized
    fun revokeRange(slice: GpuBufferSlice) {
        if (slice.length() <= 0L) return
        val candidates = mappings[slice.buffer()] ?: return
        var revoked: MutableList<FeatureSortedIndexMapping>? = null
        for (mapping in candidates) if (mapping.overlaps(slice)) {
            if (revoked == null) revoked = ArrayList()
            revoked.add(mapping)
        }
        revoked?.forEach(::revokeMapping)
    }

    @Synchronized
    fun revokeBuffer(buffer: GpuBuffer) {
        val revoked = mappings.remove(buffer) ?: return
        revoked.forEach(::revokeMapping)
    }

    private fun revokeMapping(mapping: FeatureSortedIndexMapping) {
        mapping.revoke()
        mappings[mapping.vertexBuffer]?.let {
            it.remove(mapping)
            if (it.isEmpty()) mappings.remove(mapping.vertexBuffer)
        }
        mappings[mapping.indexBuffer]?.let {
            it.remove(mapping)
            if (it.isEmpty()) mappings.remove(mapping.indexBuffer)
        }
    }

    @Synchronized
    fun clear() {
        mappings.values.forEach { values -> values.forEach(FeatureSortedIndexMapping::revoke) }
        mappings.clear()
    }
}

internal class FeatureSortedIndexUpload(
    val vertexBuffer: GpuBuffer,
    val indexBuffer: GpuBuffer?,
    val vertexBytes: Int,
    val indexBytes: Int,
)

internal class FeatureSortedIndexCapture {
    private class Draft(val description: FeatureSortedIndexDescription, val primitives: List<FeaturePrimitiveOrder>)

    private val drafts = IdentityHashMap<StagedVertexBuffer.Draw, Draft>()
    private val published = IdentityHashMap<StagedVertexBuffer.Draw, FeatureSortedIndexMapping>()
    private var active: FeatureSortedIndexUpload? = null
    private var generation = 0L

    fun beginUpload(upload: FeatureSortedIndexUpload) {
        check(active == null) { "Sorted FEATURE upload is already active" }
        clear()
        generation = Math.incrementExact(generation)
        active = upload
    }

    fun publishUpload() {
        val upload = checkNotNull(active) { "Sorted FEATURE upload is not active" }
        try {
            val indexBuffer = upload.indexBuffer ?: return
            if (upload.vertexBuffer.isClosed || indexBuffer.isClosed) return
            for ((draw, draft) in drafts) {
                val mapping = FeatureSortedIndexMapping(generation, upload.vertexBuffer, indexBuffer,
                    draft.description, draft.primitives)
                published[draw] = mapping
                FeatureSortedIndexWrites.register(mapping)
            }
        } finally {
            active = null
            drafts.clear()
        }
    }

    fun discardUpload() {
        active = null
        drafts.clear()
    }

    fun recordSortedWrite(draw: StagedVertexBuffer.Draw, description: FeatureSortedIndexDescription,
        bytes: ByteBuffer, start: Int) {
        val upload = active ?: return
        drafts.remove(draw)
        if (upload.indexBuffer == null || description.vertexCount <= 0 || description.vertexCount % 4 != 0 ||
            description.indexCount.toLong() != description.vertexCount.toLong() / 4L * 6L ||
            description.vertexByteOffset < 0 || description.indexByteOffset < 0 || description.stride <= 0 ||
            description.vertexByteOffset % description.stride != 0 ||
            description.indexByteOffset % description.indexType.bytes != 0 ||
            description.vertexByteOffset.toLong() + description.vertexCount.toLong() * description.stride > upload.vertexBytes ||
            description.indexByteOffset.toLong() + description.indexCount.toLong() * description.indexType.bytes > upload.indexBytes ||
            start.toLong() != upload.vertexBytes.toLong() + description.indexByteOffset ||
            start < 0 || start > bytes.position() ||
            bytes.position().toLong() - start != description.indexCount.toLong() * description.indexType.bytes) return
        val input = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val indices = IntArray(description.indexCount) { index ->
            val offset = start + index * description.indexType.bytes
            when (description.indexType) {
                IndexType.SHORT -> input.getShort(offset).toInt() and 0xffff
                IndexType.INT -> input.getInt(offset)
            }
        }
        val seen = BooleanArray(description.vertexCount / 4)
        val primitives = ArrayList<FeaturePrimitiveOrder>(description.indexCount / 3)
        for (offset in indices.indices step 6) {
            val first = indices[offset]
            if (first < 0 || first % 4 != 0 || first >= description.vertexCount || seen[first / 4] ||
                indices[offset + 1] != first + 1 || indices[offset + 2] != first + 2 ||
                indices[offset + 3] != first + 2 || indices[offset + 4] != first + 3 ||
                indices[offset + 5] != first) return
            seen[first / 4] = true
            primitives.add(FeaturePrimitiveOrder(first, 0))
            primitives.add(FeaturePrimitiveOrder(first, 1))
        }
        if (seen.any { !it }) return
        drafts[draw] = Draft(description, Collections.unmodifiableList(primitives))
    }

    fun lookup(draw: StagedVertexBuffer.Draw): FeatureSortedIndexMapping? = published[draw]?.takeIf { it.valid }

    fun clear() {
        published.clear()
        drafts.clear()
    }
}
