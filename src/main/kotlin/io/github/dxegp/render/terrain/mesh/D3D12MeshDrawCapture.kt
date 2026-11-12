package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import net.minecraft.client.renderer.DynamicGpuData
import java.nio.ByteOrder
import java.util.IdentityHashMap
import io.github.dxegp.d3d12.resource.D3D12TransientMemory

object D3D12MeshDrawCapture {
    internal data class DrawSnapshot(
        val command: DynamicGpuData.IndexedDraw,
        val section: DynamicGpuData.ChunkSectionInfo,
        val geometry: D3D12SectionGeometry.LayerUpload,
    )

    class Batch internal constructor(internal val draws: List<DrawSnapshot>) {
        val drawCount: Int get() = draws.size
        val quadsPerMeshlet: Int = run {
            val maximum = draws.maxOfOrNull { it.geometry.maximumMeshletQuads } ?: 0
            when { maximum > 32 -> 64; maximum > 16 -> 32; else -> 16 }
        }
    }

    class BatchTables(val sections: GpuBufferSlice, val meshlets: GpuBufferSlice, val sectionCount: Int,
                      val meshletCount: Int)

    private data class CommandRange(val offset: Long, val length: Long)

    private val captured = IdentityHashMap<DynamicGpuData.IndexedDraw, DrawSnapshot>()
    private val batches = IdentityHashMap<GpuBuffer, MutableMap<CommandRange, Batch>>()

    @Synchronized
    fun beginFrame() {
        captured.clear()
        batches.clear()
    }

    @Synchronized
    fun captureDraw(command: DynamicGpuData.IndexedDraw,
                    geometry: D3D12SectionGeometry.LayerUpload?,
                    sections: List<DynamicGpuData.ChunkSectionInfo>) {
        if (geometry == null) return
        val section = sections.getOrNull(command.baseInstance()) ?: return
        captured[command] = DrawSnapshot(command, section, geometry)
    }

    @Synchronized
    fun captureBatches(commands: Array<GpuBufferSlice>, groups: List<List<DynamicGpuData.IndexedDraw>>) {
        for (groupIndex in 0 until minOf(commands.size, groups.size)) {
            val draws = groups[groupIndex]
            if (draws.isEmpty()) continue
            val snapshots = draws.map { captured[it] }
            if (snapshots.any { it == null }) continue
            val slice = commands[groupIndex]
            val range = CommandRange(slice.offset(), slice.length())
            batches.getOrPut(slice.buffer()) { mutableMapOf() }[range] =
                Batch(snapshots.filterNotNull())
        }
        captured.clear()
    }

    @Synchronized
    fun batch(commands: GpuBufferSlice): Batch? =
        batches[commands.buffer()]?.get(CommandRange(commands.offset(), commands.length()))

    fun buildTables(batch: Batch, layer: String, vertexSliceOffset: Long, ctx: Long,
                    staging: D3D12TransientMemory, residency: D3D12MeshletResidency): BatchTables? {
        if (vertexSliceOffset < 0L) return null
        var meshletTotal = 0L
        for (draw in batch.draws) {
            val command = draw.command
            val geometry = draw.geometry
            if (geometry.layer != layer || geometry.meshlets.isEmpty() || command.instanceCount() != 1 ||
                command.indexCount() != geometry.indexCount || command.baseVertex() < 0) return null
            meshletTotal += geometry.meshlets.size
        }
        if (meshletTotal > (Int.MAX_VALUE - 32L - batch.drawCount * 32L) / 8L ||
            batch.drawCount > 65535 * 64 || batch.draws.any { it.geometry.meshlets.size > 65535 * 64 }) return null
        val dynamicBytes = batch.drawCount * 40L
        if (dynamicBytes > Int.MAX_VALUE) return null

        val staticBuffer = residency.prepare(ctx, staging, batch.draws.map { it.geometry })
        val sectionsSlice = staging.writeIntoStagingBlock(dynamicBytes, 256L) { destination ->
            val sections = destination.order(ByteOrder.LITTLE_ENDIAN)
            for (drawIndex in batch.draws.indices) {
                val draw = batch.draws[drawIndex]
                val baseVertex = draw.command.baseVertex()
                val descriptors = draw.geometry.meshlets
                sections.putInt(baseVertex)
                sections.putInt(baseVertex / D3D12MeshletBuilder.VERTICES_PER_QUAD)
                sections.putInt(residency.offset(draw.geometry))
                sections.putInt(descriptors.size)
                sections.putInt(draw.section.x())
                sections.putInt(draw.section.y())
                sections.putInt(draw.section.z())
                sections.putFloat(draw.section.visibility())
                sections.putInt(drawIndex)
                sections.putInt(if (draw.geometry.bounded) 1 else 0)
            }
            dynamicBytes
        }.getOrThrow().first
        return BatchTables(sectionsSlice, staticBuffer.slice(), batch.drawCount, meshletTotal.toInt())
    }
}
