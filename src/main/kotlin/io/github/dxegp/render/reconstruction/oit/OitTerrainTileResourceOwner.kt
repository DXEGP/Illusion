package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.shader.D3D12OitReplayStage
import io.github.dxegp.d3d12.shader.D3D12OitTileContract
import io.github.dxegp.d3d12.shader.D3D12OitTileBinning
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class OitTerrainTileEnvelope(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val sourcePrimitive: Int,
    val sourceInstance: Int,
    val visibleMeshlet: Int,
    val forceAllTiles: Boolean,
) {
    init {
        require(listOf(minX, minY, maxX, maxY).all(Float::isFinite))
        require(minX <= maxX && minY <= maxY)
        require(sourcePrimitive >= 0 && sourceInstance >= 0 && visibleMeshlet >= 0)
    }
}

internal data class OitTerrainTileStageDescription(
    val stage: D3D12OitReplayStage,
    val pipeline: Long,
    val rootSignature: Long,
    val renderTargetFormats: List<GpuFormat>,
) {
    init {
        require(pipeline != 0L && rootSignature != 0L && renderTargetFormats.isNotEmpty() &&
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
    private val uploadCopies: List<Pair<D3D12GpuBuffer, D3D12GpuBuffer>>,
    private val batch: OitTerrainTileBatch,
    val depthResources: OitTerrainTileDepthResources,
) : AutoCloseable {
    private var initialized = false
    private var closed = false

    fun batch(): OitTerrainTileBatch = batch

    fun initialize(context: Long) {
        check(!closed && !initialized)
        for ((upload, target) in uploadCopies) {
            check(MCDR_ABI.D3D12CopyBufferChecked(context, upload.handle(), 0L, target.handle(), 0L, target.size())) {
                "Terrain tile initialization copy failed: ${MCDR_ABI.D3D12LastError()}"
            }
        }
        initialized = true
    }

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
            sourceViewport: FloatArray,
            tiles: List<OitTerrainTileRegion>,
            envelopes: List<OitTerrainTileEnvelope>,
            laneIndex: Int,
            stages: List<OitTerrainTileStageDescription>,
            routeRootSignature: Long,
            binning: OitTerrainTileBinningHandles,
        ): OitTerrainTileResourceOwner {
            require(tiles.size in 1..D3D12OitTileContract.MAX_RESIDENT_TILES &&
                laneIndex in 0 until D3D12OitTileContract.LANE_COUNT)
            require(stages.map { it.stage } == D3D12OitReplayStage.entries)
            require(stages.map { it.rootSignature }.toSet() == setOf(routeRootSignature))
            require(stages[0].renderTargetFormats.size == 1 &&
                stages[1].renderTargetFormats.size in setOf(1, 2, 4) &&
                stages[2].renderTargetFormats.size == 1)
            require(stages.all { description -> description.renderTargetFormats.all { it == GpuFormat.RGBA32_FLOAT } })
            val binningLayout = D3D12OitTileBinning.program
            require(envelopes.isNotEmpty() && envelopes.size <= binningLayout.maximumChunkPrimitives)
            val refCapacity = Math.multiplyExact(envelopes.size, tiles.size)
            val commandBytes = Math.multiplyExact(refCapacity, binningLayout.meshCommandStride)
            val uploads = ArrayList<D3D12GpuBuffer>()
            val defaults = ArrayList<D3D12GpuBuffer>()
            val textures = ArrayList<D3D12GpuTexture>()
            val copies = ArrayList<Pair<D3D12GpuBuffer, D3D12GpuBuffer>>()
            try {
                fun upload(name: String, bytes: Int, fill: (ByteBuffer) -> Unit): D3D12GpuBuffer {
                    val result = D3D12GpuBuffer(GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC,
                        bytes.toLong(), name)
                    uploads += result
                    result.map(0L, bytes.toLong(), false, true).use { view ->
                        val data = view.data().order(ByteOrder.LITTLE_ENDIAN)
                        fill(data)
                    }
                    return result
                }
                fun gpu(name: String, bytes: Int, source: D3D12GpuBuffer): D3D12GpuBuffer {
                    val result = D3D12GpuBuffer(GpuBuffer.USAGE_COPY_DST or D3D12GpuTexture.USAGE_STORAGE,
                        bytes.toLong(), name)
                    defaults += result
                    copies += source to result
                    return result
                }
                fun bufferPair(name: String, bytes: Int, fill: (ByteBuffer) -> Unit): D3D12GpuBuffer {
                    val source = upload("$name upload", bytes, fill)
                    return gpu(name, bytes, source)
                }
                fun ref(buffer: D3D12GpuBuffer, bytes: Long = buffer.size()): OitTerrainTileResource =
                    OitTerrainTileResource(buffer.handle(), 0L, bytes)
                fun texRef(texture: D3D12GpuTexture): OitTerrainTileResource =
                    OitTerrainTileResource(texture.handle(), 0L, 1L)
                val envelopeBuffer = upload("terrain tile envelopes",
                    Math.multiplyExact(envelopes.size, binningLayout.envelopeStride)) { data ->
                    for (envelope in envelopes) {
                        data.putFloat(envelope.minX).putFloat(envelope.minY)
                            .putFloat(envelope.maxX).putFloat(envelope.maxY)
                        data.putInt(envelope.sourcePrimitive).putInt(envelope.sourceInstance)
                            .putInt(envelope.visibleMeshlet).putInt(1)
                        data.putInt(envelope.forceAllTiles.compareTo(false))
                            .putInt(0).putInt(0).putInt(0)
                        data.putInt(0).putInt(0).putInt(0).putInt(0)
                    }
                }
                val binningConstants = upload("terrain tile binning constants", 16) { data ->
                    data.putInt(envelopes.size).putInt(refCapacity).putInt(1).putInt(binningLayout.meshCommandStride)
                }
                val envelopeIndexBytes = Math.multiplyExact(envelopes.size, Int.SIZE_BYTES)
                val masks = bufferPair("terrain tile masks", envelopeIndexBytes) { data -> repeat(envelopes.size) { data.putInt(0) } }
                val prefixes = bufferPair("terrain tile prefixes", envelopeIndexBytes) { data -> repeat(envelopes.size) { data.putInt(0) } }
                val commands = bufferPair("terrain tile commands", commandBytes) { data ->
                    repeat(commandBytes / Int.SIZE_BYTES) { data.putInt(0) }
                }
                val control = bufferPair("terrain tile control", 16) { data ->
                    data.putInt(0).putInt(0).putInt(0).putInt(0)
                }
                fun texture(name: String, format: GpuFormat, usage: Int): D3D12GpuTexture =
                    D3D12GpuTexture(usage, name, format, D3D12OitTileContract.SCRATCH_SIZE,
                        D3D12OitTileContract.SCRATCH_SIZE, tiles.size, 1).also { textures += it }
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
                val q = texture("terrain tile q", GpuFormat.RGBA32_FLOAT, textureUsage)
                val scratchColor = texture("terrain tile scratch color", GpuFormat.RGBA32_FLOAT, textureUsage)
                val scratchDepth = texture("terrain tile scratch depth", GpuFormat.RGBA32_FLOAT, textureUsage)
                val coefficients = List(4) { texture("terrain tile coefficient $it", GpuFormat.RGBA32_FLOAT, textureUsage) }
                val stageBindings = stages.mapIndexed { index, description ->
                    val targets = when (description.stage) {
                        D3D12OitReplayStage.DEPTH_BOUNDS -> listOf(bounds)
                        D3D12OitReplayStage.TRANSMITTANCE -> coefficients.take(description.renderTargetFormats.size)
                        D3D12OitReplayStage.ACCUMULATE -> listOf(q)
                    }
                    OitTerrainTileStage(index, description.pipeline, description.rootSignature,
                        depthResources.mainDepth, targets.map(::texRef))
                }
                val batch = OitTerrainTileBatch(tiles.size, laneIndex, frameSerial, worldEpoch,
                    programGeneration, resourceVersion, sourceViewport.copyOf(), tiles.toList(), envelopes.size,
                    refCapacity, 1, binningLayout.meshCommandStride, ref(binningConstants), ref(envelopeBuffer), ref(masks),
                    ref(prefixes), ref(commands), ref(control), texRef(bounds), texRef(culledBounds),
                    depthResources.terrainDepth, depthResources.cloudDepth, coefficients.map(::texRef), texRef(q),
                    texRef(scratchColor), texRef(scratchDepth),
                    binning.workGraph, binning.initializePipeline, binning.prefixPipeline, binning.scatterPipeline,
                    routeRootSignature, stageBindings)
                return OitTerrainTileResourceOwner(uploads, defaults, textures, copies, batch, depthResources)
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
