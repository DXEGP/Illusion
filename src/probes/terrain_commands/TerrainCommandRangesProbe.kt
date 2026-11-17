package io.github.dxegp.render.terrain.mesh

import com.mojang.blaze3d.vertex.TlsfAllocator
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import net.minecraft.client.renderer.DynamicGpuData
import java.util.concurrent.locks.ReentrantLock

fun main() {
    val commands = D3D12GpuBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS, 320)
    val vertices = D3D12GpuBuffer(GpuBuffer.USAGE_VERTEX, 4096)
    val unrelated = D3D12GpuBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS, 320)
    val allocator = TlsfAllocator(TlsfAllocator.Heap(4096))
    val owner = TerrainGeometryAllocations(ReentrantLock())
    val sections = List(5) { DynamicGpuData.ChunkSectionInfo(it * 16, 0, 0, 1f) }
    val draws = List(5) { index ->
        val allocation = checkNotNull(allocator.allocate(112, 112))
        val read = checkNotNull(owner.reserveUpload(allocator, allocation, vertices, 112))
        val geometry = D3D12SectionGeometry.LayerUpload(index + 1L, "SOLID", 6, 112, 0,
            listOf(D3D12MeshletBuilder.Meshlet(0, 1, 0, 0, 0, 255, 255, 255, 1)))
        DynamicGpuData.IndexedDraw(6, 1, 0, (allocation.offsetFromHeap / 28).toInt(), index).also { draw ->
            D3D12MeshDrawCapture.captureDraw(draw, geometry, sections,
                TerrainGeometryAllocations.DrawReads(read, null, 0))
        }
    }
    D3D12MeshDrawCapture.captureBatches(arrayOf(commands.slice(40, 100)), listOf(draws))
    val complete = checkNotNull(D3D12MeshDrawCapture.batch(commands.slice(40, 100)))
    check(complete.drawCount == 5)
    fun expectRange(offset: Long, count: Int, expectedIndices: List<Int>) {
        val slice = commands.slice(offset, count * 20L)
        val batch = checkNotNull(D3D12MeshDrawCapture.batch(slice))
        check(batch.drawCount == count)
        check(batch.draws.map { it.command.baseInstance() } == expectedIndices)
        check(batch.commandSelections.map { it.baseInstance } == expectedIndices)
        check(batch.terrainDrawFacts.map { it.geometryGeneration } == expectedIndices.map { it + 1L })
        check(batch.terrainDrawFacts.map { it.primitiveBase } == List(count) { it * 2 })
        check(D3D12MeshDrawCapture.batch(slice) === batch)
    }
    expectRange(40, 2, listOf(0, 1))
    expectRange(60, 3, listOf(1, 2, 3))
    expectRange(100, 2, listOf(3, 4))
    expectRange(80, 1, listOf(2))
    for ((offset, length) in listOf(40L to 0L, 40L to 19L, 41L to 20L,
        20L to 40L, 120L to 40L, 140L to 20L)) {
        check(D3D12MeshDrawCapture.batch(GpuBufferSlice(commands, offset, length)) == null)
    }
    check(D3D12MeshDrawCapture.batch(unrelated.slice(40, 100)) == null)
    D3D12MeshDrawCapture.beginFrame()
    check(D3D12MeshDrawCapture.batch(commands.slice(40, 100)) == null)
    check(D3D12MeshDrawCapture.batch(commands.slice(60, 60)) == null)
    owner.retireAll()
    check(owner.closeHeap(vertices))
    commands.close()
    unrelated.close()
    println("PASS actual capture/batch: 20-byte command subranges, original order, facts, cached identity, invalid ranges, owner separation, frame cleanup")
}
