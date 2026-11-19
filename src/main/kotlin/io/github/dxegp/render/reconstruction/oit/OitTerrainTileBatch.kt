package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.shader.D3D12OitTileContract.LANE_COUNT
import io.github.dxegp.d3d12.shader.D3D12OitTileContract.MAX_RESIDENT_TILES
import io.github.dxegp.d3d12.shader.D3D12OitTileContract.TILE_SIZE
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemoryLayout.PathElement.groupElement

internal data class OitTerrainTileResource(
    val objectHandle: Long,
    val offset: Long,
    val bytes: Long,
) {
    init {
        require(objectHandle != 0L && offset >= 0L && bytes > 0L)
    }

    fun write(target: MemorySegment, layout: java.lang.foreign.MemoryLayout) {
        target.set(MCDRLayout.PTR, layout.byteOffset(groupElement("object")), MemorySegment.ofAddress(objectHandle))
        target.set(MCDRLayout.I64, layout.byteOffset(groupElement("offset")), offset)
        target.set(MCDRLayout.I64, layout.byteOffset(groupElement("bytes")), bytes)
    }
}

internal data class OitTerrainTileRegion(val originX: Int, val originY: Int, val width: Int, val height: Int) {
    init {
        require(originX >= 0 && originY >= 0 && originX % 2 == 0 && originY % 2 == 0)
        require(width in 1..64 && height in 1..64)
    }
}

internal class OitTerrainTileGrid(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    private val columns = Math.ceilDiv(width, TILE_SIZE)
    val tileCount = Math.multiplyExact(columns, Math.ceilDiv(height, TILE_SIZE))

    fun writeRegions(target: MemorySegment, base: Long, firstTile: Int, count: Int) {
        require(count in 1..MAX_RESIDENT_TILES && firstTile in 0..(tileCount - count))
        for (index in 0 until count) {
            val tile = firstTile + index
            val x = tile % columns * TILE_SIZE
            val y = tile / columns * TILE_SIZE
            val offset = base + index * regionSize
            target.set(MCDRLayout.I32, offset + originX, x)
            target.set(MCDRLayout.I32, offset + originY, y)
            target.set(MCDRLayout.I32, offset + effectiveWidth, minOf(TILE_SIZE, width - x))
            target.set(MCDRLayout.I32, offset + effectiveHeight, minOf(TILE_SIZE, height - y))
        }
    }

    companion object {
        private val layout = MCDRLayout.TERRAIN_TILE
        val regionSize = layout.byteSize()
        private val originX = layout.byteOffset(groupElement("originX"))
        private val originY = layout.byteOffset(groupElement("originY"))
        private val effectiveWidth = layout.byteOffset(groupElement("effectiveWidth"))
        private val effectiveHeight = layout.byteOffset(groupElement("effectiveHeight"))
    }
}

internal data class OitTerrainTileStage(
    val stage: Int,
    val pipeline: Long,
    val rootSignature: Long,
    val depth: OitTerrainTileResource,
    val renderTargets: List<OitTerrainTileResource>,
) {
    init {
        require(stage in 0..2 && (pipeline == 0L) == (rootSignature == 0L) && renderTargets.isNotEmpty() && renderTargets.size <= 5)
    }
}

internal data class OitTerrainTileBatch(
    val residentTileCount: Int,
    val laneIndex: Int,
    val frameSerial: Long,
    val worldEpoch: Long,
    val programGeneration: Long,
    val resourceVersion: Long,
    private val grid: OitTerrainTileGrid,
    private val firstTile: Int,
    val primitiveCount: Int,
    val refCapacity: Int,
    val commandKind: Int,
    val commandStride: Int,
    val binningConstants: OitTerrainTileResource,
    val envelopes: OitTerrainTileResource,
    val masks: OitTerrainTileResource,
    val prefixes: OitTerrainTileResource,
    val commands: OitTerrainTileResource,
    val control: OitTerrainTileResource,
    val bounds: OitTerrainTileResource,
    val culledBounds: OitTerrainTileResource,
    val depthTerrain: OitTerrainTileResource,
    val depthCloud: OitTerrainTileResource,
    val coefficients: List<OitTerrainTileResource>,
    val q: OitTerrainTileResource,
    val scratchColor: OitTerrainTileResource,
    val scratchDepth: OitTerrainTileResource,
    val binningWorkGraph: Long,
    val binningInitializePipeline: Long,
    val binningPrefixPipeline: Long,
    val binningScatterPipeline: Long,
    val stages: List<OitTerrainTileStage>,
    internal val lifetime: AutoCloseable? = null,
) {
    init {
        require(residentTileCount in 1..MAX_RESIDENT_TILES && laneIndex in 0 until LANE_COUNT)
        require(frameSerial > 0L && worldEpoch > 0L && programGeneration > 0L && resourceVersion > 0L)
        require(firstTile in 0..(grid.tileCount - residentTileCount) && primitiveCount > 0 &&
            refCapacity.toLong() >= Math.multiplyExact(primitiveCount.toLong(), residentTileCount.toLong()))
        require(commandKind in 0..1 && commandStride == if (commandKind == 0) 36 else 28)
        require(coefficients.size == 4 && binningWorkGraph != 0L && binningInitializePipeline != 0L &&
            binningPrefixPipeline != 0L && binningScatterPipeline != 0L)
        require(stages.size == 3 && stages.map { it.stage } == listOf(0, 1, 2))
    }

    private fun writePayload(target: MemorySegment) {
        fun i32(name: String, value: Int) = target.set(MCDRLayout.I32, batchLayout.byteOffset(groupElement(name)), value)
        fun i64(name: String, value: Long) = target.set(MCDRLayout.I64, batchLayout.byteOffset(groupElement(name)), value)
        fun ptr(name: String, value: Long) = target.set(MCDRLayout.PTR, batchLayout.byteOffset(groupElement(name)), MemorySegment.ofAddress(value))
        i32("schemaVersion", 2); i32("structSize", Math.toIntExact(batchSize))
        i32("residentTileCount", residentTileCount); i32("laneIndex", laneIndex); i32("flags", 0); i32("reserved0", 0)
        i64("frameSerial", frameSerial); i64("worldEpoch", worldEpoch); i64("programGeneration", programGeneration); i64("resourceVersion", resourceVersion)
        val floatSize = MCDRLayout.F32.byteSize()
        target.set(MCDRLayout.F32, viewportOffset, 0f)
        target.set(MCDRLayout.F32, viewportOffset + floatSize, 0f)
        target.set(MCDRLayout.F32, viewportOffset + 2 * floatSize, grid.width.toFloat())
        target.set(MCDRLayout.F32, viewportOffset + 3 * floatSize, grid.height.toFloat())
        i32("primitiveCount", primitiveCount); i32("refCapacity", refCapacity); i32("commandKind", commandKind); i32("commandStride", commandStride)
        fun resource(name: String, value: OitTerrainTileResource) {
            value.write(target.asSlice(batchLayout.byteOffset(groupElement(name)), MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize()), MCDRLayout.TERRAIN_TILE_RESOURCE_REF)
        }
        resource("binningConstants", binningConstants); resource("envelopes", envelopes); resource("masks", masks)
        resource("prefixes", prefixes); resource("commands", commands); resource("control", control); resource("bounds", bounds)
        resource("culledBounds", culledBounds); resource("depthTerrain", depthTerrain); resource("depthCloud", depthCloud)
        val coefficientsOffset = batchLayout.byteOffset(groupElement("coefficients"))
        this.coefficients.forEachIndexed { index, value ->
            value.write(
                target.asSlice(
                    coefficientsOffset + index * MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize(),
                    MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize(),
                ),
                MCDRLayout.TERRAIN_TILE_RESOURCE_REF,
            )
        }
        resource("q", q); resource("scratchColor", scratchColor); resource("scratchDepth", scratchDepth)
        ptr("binningWorkGraph", binningWorkGraph); ptr("binningInitializePipeline", binningInitializePipeline)
        ptr("binningPrefixPipeline", binningPrefixPipeline); ptr("binningScatterPipeline", binningScatterPipeline)
        val stagesBase = batchLayout.byteOffset(groupElement("stages"))
        stages.forEachIndexed { index, stage ->
            val stageLayout = MCDRLayout.TERRAIN_TILE_STAGE_BINDING_V2
            val base = stagesBase + index * stageLayout.byteSize()
            target.set(MCDRLayout.I32, base, stage.stage); target.set(MCDRLayout.I32, base + 4, 0)
            target.set(MCDRLayout.I32, base + 8, stage.renderTargets.size); target.set(MCDRLayout.I32, base + 12, 0)
            target.set(MCDRLayout.PTR, base + 16, MemorySegment.ofAddress(stage.pipeline))
            stage.depth.write(target.asSlice(base + 24, MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize()), MCDRLayout.TERRAIN_TILE_RESOURCE_REF)
            val targetsBase = base + 24 + MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize()
            stage.renderTargets.forEachIndexed { targetIndex, value ->
                value.write(target.asSlice(targetsBase + targetIndex * MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize(), MCDRLayout.TERRAIN_TILE_RESOURCE_REF.byteSize()), MCDRLayout.TERRAIN_TILE_RESOURCE_REF)
            }
        }
    }

    internal fun write(arena: Arena): MemorySegment {
        val target = arena.allocate(batchLayout)
        writePayload(target)
        grid.writeRegions(target, tilesOffset, firstTile, residentTileCount)
        return target
    }

    internal fun writeLanePayloads(target: MemorySegment) {
        require(target.byteSize() == laneBatchBytes)
        target.fill(0)
        writePayload(target)
        for (lane in 0 until LANE_COUNT) {
            val base = lane * batchSize
            if (lane != 0) MemorySegment.copy(target, 0, target, base, batchSize)
            target.set(MCDRLayout.I32, base + laneIndexOffset, lane)
        }
    }

    internal fun writeLaneRegions(target: MemorySegment, firstTile: Int) {
        grid.writeRegions(target, tilesOffset, firstTile, residentTileCount)
        val regionBytes = residentTileCount * OitTerrainTileGrid.regionSize
        for (lane in 1 until LANE_COUNT) {
            MemorySegment.copy(target, tilesOffset, target, lane * batchSize + tilesOffset, regionBytes)
        }
    }

    internal fun writeV3(arena: Arena, packetOrdinal: Int, packetCount: Int): MemorySegment {
        require(packetOrdinal in 0 until packetCount)
        val layout = MCDRLayout.TERRAIN_TILE_BATCH_V3
        val target = arena.allocate(layout)
        target.set(MCDRLayout.I32, layout.byteOffset(groupElement("schemaVersion")), 3)
        target.set(MCDRLayout.I32, layout.byteOffset(groupElement("structSize")), Math.toIntExact(layout.byteSize()))
        target.set(MCDRLayout.I32, layout.byteOffset(groupElement("packetOrdinal")), packetOrdinal)
        target.set(MCDRLayout.I32, layout.byteOffset(groupElement("packetCount")), packetCount)
        val payloadOffset = layout.byteOffset(groupElement("payload"))
        val payload = target.asSlice(payloadOffset, batchSize)
        writePayload(payload)
        grid.writeRegions(payload, tilesOffset, firstTile, residentTileCount)
        return target
    }

    internal fun closeResources() {
        lifetime?.close()
    }

    companion object {
        private val batchLayout = MCDRLayout.TERRAIN_TILE_BATCH_V2
        private val batchSize = batchLayout.byteSize()
        private val viewportOffset = batchLayout.byteOffset(groupElement("sourceViewport"))
        private val tilesOffset = batchLayout.byteOffset(groupElement("tiles"))
        private val laneIndexOffset = batchLayout.byteOffset(groupElement("laneIndex"))
        val laneBatchBytes = LANE_COUNT * batchSize
        val laneBatchAlignment = batchLayout.byteAlignment()
    }

}
