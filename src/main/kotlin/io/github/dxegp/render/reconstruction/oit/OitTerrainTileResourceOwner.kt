package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.shader.D3D12OitReplayStage
import io.github.dxegp.d3d12.shader.D3D12OitTileContract
import io.github.dxegp.d3d12.shader.D3D12OitTileBinning
import java.nio.ByteOrder

internal data class OitTerrainTileStageDescription(
    val stage: D3D12OitReplayStage,
    val pipeline: Long,
    val rootSignature: Long,
    val renderTargetFormats: List<GpuFormat>,
) {
    init {
        require((pipeline == 0L) == (rootSignature == 0L) && renderTargetFormats.isNotEmpty() &&
            renderTargetFormats.size <= 5)
    }
}

internal data class OitTerrainTileBinningHandles(
    val workGraph: Long,
    val initializePipeline: Long,
    val prefixPipeline: Long,
    val scatterPipeline: Long,
) {
    init {
        require(workGraph != 0L && initializePipeline != 0L && prefixPipeline != 0L && scatterPipeline != 0L)
    }
}

internal data class OitTerrainTileDepthResources(
    val mainDepth: OitTerrainTileResource,
    val terrainDepth: OitTerrainTileResource,
    val cloudDepth: OitTerrainTileResource,
)

internal class OitTerrainTileResourceOwner private constructor(
    private val uploadBuffers: List<D3D12GpuBuffer>,
    private val defaultBuffers: List<D3D12GpuBuffer>,
    private val textures: List<D3D12GpuTexture>,
    val batch: OitTerrainTileBatch,
    val depthResources: OitTerrainTileDepthResources,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (resource in textures.asReversed() + defaultBuffers.asReversed() + uploadBuffers.asReversed()) {
            try {
                resource.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        fun create(
            frameSerial: Long,
            worldEpoch: Long,
            programGeneration: Long,
            resourceVersion: Long,
            grid: OitTerrainTileGrid,
            firstTile: Int,
            residentTileCount: Int,
            meshletChunkCapacity: Int,
            stages: List<OitTerrainTileStageDescription>,
            binning: OitTerrainTileBinningHandles,
        ): OitTerrainTileResourceOwner {
            require(residentTileCount in 1..D3D12OitTileContract.MAX_RESIDENT_TILES &&
                firstTile in 0..(grid.tileCount - residentTileCount))
            require(stages.map { it.stage } == D3D12OitReplayStage.entries)
            require(stages[0].renderTargetFormats == listOf(GpuFormat.RGBA32_FLOAT) &&
                stages[1].renderTargetFormats.size in setOf(1, 2, 4) &&
                stages[1].renderTargetFormats.all { it == GpuFormat.RGBA16_FLOAT } &&
                stages[2].renderTargetFormats == listOf(GpuFormat.RGBA16_FLOAT))
            val binningLayout = D3D12OitTileBinning.program
            require(meshletChunkCapacity in 1..binningLayout.maximumChunkPrimitives)
            val refCapacity = Math.multiplyExact(meshletChunkCapacity, residentTileCount)
            val commandBytes = Math.multiplyExact(refCapacity, binningLayout.meshCommandStride)
            val uploads = ArrayList<D3D12GpuBuffer>()
            val defaults = ArrayList<D3D12GpuBuffer>()
            val textures = ArrayList<D3D12GpuTexture>()
            try {
                fun gpu(name: String, bytes: Int): D3D12GpuBuffer =
                    D3D12GpuBuffer(D3D12GpuBuffer.USAGE_STORAGE, bytes.toLong(), name).also { defaults += it }
                fun ref(buffer: D3D12GpuBuffer, bytes: Long = buffer.size()): OitTerrainTileResource =
                    OitTerrainTileResource(buffer.handle(), 0L, bytes)
                fun texRef(texture: D3D12GpuTexture): OitTerrainTileResource =
                    OitTerrainTileResource(texture.handle(), 0L, 1L)
                val envelopeBuffer = gpu("terrain tile envelopes",
                    Math.multiplyExact(meshletChunkCapacity, binningLayout.envelopeStride))
                val binningConstants = D3D12GpuBuffer(GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC,
                    16L, "terrain tile binning constants").also { uploads += it }
                binningConstants.map(0L, 16L, false, true).use { view ->
                    view.data().order(ByteOrder.LITTLE_ENDIAN).putInt(meshletChunkCapacity)
                        .putInt(refCapacity).putInt(1).putInt(binningLayout.meshCommandStride)
                }
                val envelopeIndexBytes = Math.multiplyExact(meshletChunkCapacity, Int.SIZE_BYTES)
                val masks = gpu("terrain tile masks", envelopeIndexBytes)
                val prefixes = gpu("terrain tile prefixes", envelopeIndexBytes)
                val commands = gpu("terrain tile commands", commandBytes)
                val control = gpu("terrain tile control", 16)
                fun texture(name: String, format: GpuFormat, usage: Int): D3D12GpuTexture =
                    D3D12GpuTexture(usage, name, format, D3D12OitTileContract.SCRATCH_SIZE,
                        D3D12OitTileContract.SCRATCH_SIZE, residentTileCount, 1).also { textures += it }
                val textureUsage = GpuTexture.USAGE_TEXTURE_BINDING or D3D12GpuTexture.USAGE_STORAGE or
                    GpuTexture.USAGE_RENDER_ATTACHMENT
                val depthUsage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or
                    GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST
                val bounds = texture("terrain tile bounds", GpuFormat.RGBA32_FLOAT, textureUsage)
                val culledBounds = texture("terrain tile culled bounds", GpuFormat.RGBA32_FLOAT, textureUsage)
                val mainDepth = texture("terrain tile main depth", GpuFormat.D32_FLOAT, depthUsage)
                val terrainDepth = texture("terrain tile terrain depth", GpuFormat.D32_FLOAT, depthUsage)
                val cloudDepth = texture("terrain tile cloud depth", GpuFormat.D32_FLOAT, depthUsage)
                val depthResources = OitTerrainTileDepthResources(
                    texRef(mainDepth), texRef(terrainDepth), texRef(cloudDepth))
                val q = texture("terrain tile q", GpuFormat.RGBA16_FLOAT, textureUsage)
                val scratchColor = texture("terrain tile scratch color", GpuFormat.RGBA32_FLOAT, textureUsage)
                val scratchDepth = texture("terrain tile scratch depth", GpuFormat.RGBA32_FLOAT, textureUsage)
                val coefficients = List(4) { texture("terrain tile coefficient $it", GpuFormat.RGBA16_FLOAT, textureUsage) }
                val stageBindings = stages.mapIndexed { index, description ->
                    val targets = when (description.stage) {
                        D3D12OitReplayStage.DEPTH_BOUNDS -> listOf(bounds)
                        D3D12OitReplayStage.TRANSMITTANCE -> coefficients.take(description.renderTargetFormats.size)
                        D3D12OitReplayStage.ACCUMULATE -> listOf(q)
                    }
                    OitTerrainTileStage(index, description.pipeline, description.rootSignature,
                        depthResources.mainDepth, targets.map(::texRef))
                }
                val batch = OitTerrainTileBatch(residentTileCount, 0, frameSerial, worldEpoch,
                    programGeneration, resourceVersion, grid, firstTile, meshletChunkCapacity,
                    refCapacity, 1, binningLayout.meshCommandStride, ref(binningConstants), ref(envelopeBuffer), ref(masks),
                    ref(prefixes), ref(commands), ref(control), texRef(bounds), texRef(culledBounds),
                    depthResources.terrainDepth, depthResources.cloudDepth, coefficients.map(::texRef), texRef(q),
                    texRef(scratchColor), texRef(scratchDepth),
                    binning.workGraph, binning.initializePipeline, binning.prefixPipeline, binning.scatterPipeline,
                    stageBindings)
                return OitTerrainTileResourceOwner(uploads, defaults, textures, batch, depthResources)
            } catch (failure: Throwable) {
                for (resource in textures.asReversed() + defaults.asReversed() + uploads.asReversed()) {
                    try {
                        resource.close()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }
    }

}
