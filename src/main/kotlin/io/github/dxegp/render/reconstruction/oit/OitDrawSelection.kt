package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.IndexType
import java.util.Collections

internal sealed interface OitDrawSelection {
    data class Indexed(val indexCount: Int, val instanceCount: Int, val firstIndex: Int,
        val baseVertex: Int, val firstInstance: Int) : OitDrawSelection

    data class Vertices(val vertexCount: Int, val instanceCount: Int, val firstVertex: Int,
        val firstInstance: Int) : OitDrawSelection

    enum class IndirectKind { INDEXED, VERTICES }

    class Indirect(val kind: IndirectKind, commands: GpuBufferSlice, val drawCount: Int) : OitDrawSelection {
        val buffer: GpuBuffer = commands.buffer()
        val byteOffset: Long = commands.offset()
        val byteLength: Long = commands.length()
    }

    class IndexedBatch(draws: List<Indexed>) : OitDrawSelection {
        val draws: List<Indexed> = Collections.unmodifiableList(ArrayList(draws))
    }

    class VertexBatch(draws: List<Vertices>) : OitDrawSelection {
        val draws: List<Vertices> = Collections.unmodifiableList(ArrayList(draws))
    }

    data class IndexedByteOffset(val indexByteOffset: Long, val indexCount: Int, val baseVertex: Int)

    class IndexedOffsetBatch(draws: List<IndexedByteOffset>) : OitDrawSelection {
        val draws: List<IndexedByteOffset> = Collections.unmodifiableList(ArrayList(draws))
    }

    class IndexedCollection(draws: Collection<RenderPass.Draw<*>>, defaultIndexBuffer: GpuBuffer?,
        defaultIndexType: IndexType?) : OitDrawSelection {
        class Member(draw: RenderPass.Draw<*>, defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?) {
            val slot: Int = draw.slot()
            val vertexBuffer: GpuBuffer = draw.vertexBuffer()
            val indexBuffer: GpuBuffer? = draw.indexBuffer() ?: defaultIndexBuffer
            val indexType: IndexType? = draw.indexType() ?: defaultIndexType
            val selection = Indexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0)
        }

        val draws: List<Member> = Collections.unmodifiableList(draws.map {
            Member(it, defaultIndexBuffer, defaultIndexType)
        })
    }
}
