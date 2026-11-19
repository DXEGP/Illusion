package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.client.renderer.oit.OitPipelineSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import io.github.dxegp.d3d12.ffi.MCDR_ABI

enum class D3D12OitTileUnsupported {
    SOURCE_CONTRACT, TOPOLOGY, STAGE_SIGNATURE, RESERVED_BINDING, ATTACHMENT_SAMPLING,
    MESH_SIGNATURE,
}

sealed interface D3D12OitTileCompilation {
    data object NotApplicable : D3D12OitTileCompilation
    data class Unsupported(val reason: D3D12OitTileUnsupported) : D3D12OitTileCompilation
    data class Candidate(val program: D3D12OitTileProgram) : D3D12OitTileCompilation
}

sealed interface D3D12OitTileIaCompilation {
    data object NotApplicable : D3D12OitTileIaCompilation
    data class Unsupported(val reason: D3D12OitTileUnsupported) : D3D12OitTileIaCompilation
    data class Candidate(val program: D3D12OitTileIaProgram) : D3D12OitTileIaCompilation
}

data class D3D12OitTileIaProgram(
    val stage: D3D12OitReplayStage,
    val programGeneration: Long,
    val contractHash: String,
    val directVertexHlsl: String,
    val fragmentHlsl: String,
    val laneRegisters: List<Int>,
    val vertexEvaluation: D3D12OitTileVertexEvaluation,
    val flipY: Boolean,
)

sealed interface D3D12OpaqueSeedCompilation {
    data object NotApplicable : D3D12OpaqueSeedCompilation
    data class Unsupported(val reason: D3D12OitTileUnsupported) : D3D12OpaqueSeedCompilation
    data class Candidate(val program: D3D12OpaqueSeedProgram) : D3D12OpaqueSeedCompilation
}

data class D3D12OpaqueSeedProgram(
    val programGeneration: Long,
    val contractHash: String,
    val directVertexHlsl: String,
    val fragmentHlsl: String,
    val vertexEvaluation: D3D12OitTileVertexEvaluation,
    val flipY: Boolean,
)

data class D3D12OitTileRegion(val sourceX: Int, val sourceY: Int, val width: Int, val height: Int) {
    init {
        require(sourceX >= 0 && sourceY >= 0 && sourceX % 2 == 0 && sourceY % 2 == 0)
        require(width in 1..D3D12OitTileContract.TILE_SIZE && height in 1..D3D12OitTileContract.TILE_SIZE)
    }
}

data class D3D12OitTileViewport(val x: Float, val y: Float, val width: Float, val height: Float) {
    init {
        require(listOf(x, y, width, height).all(Float::isFinite) && width > 0.0f && height > 0.0f)
    }
}

data class D3D12OitTileLane(val x: Float, val y: Float)

object D3D12OitTileContract {
    const val SCHEMA_VERSION = 1
    const val SCHEMA = "illusion.oit-tile-shader/1"
    const val TILE_SIZE = 64
    const val GUARD_PIXELS = 2
    const val SCRATCH_SIZE = TILE_SIZE + GUARD_PIXELS * 2
    const val MAX_RESIDENT_TILES = 16
    const val LANE_COUNT = 4
    const val REGISTER_SPACE = 3
    const val DRAW_REGISTER = 0
    const val DRAW_DWORDS = 4
    const val BATCH_REGISTER = 1
    const val BATCH_BYTES = 32 + MAX_RESIDENT_TILES * 16
    const val BATCH_ALLOCATION_BYTES = 512
    const val TERRAIN_MESH_SCHEMA = "illusion.oit-tile-terrain/static-descriptor/1"
    const val TERRAIN_ENVELOPE_ENTRY = "BuildTerrainTileEnvelopesV1"
    val lanes: List<D3D12OitTileLane> = java.util.List.of(
        D3D12OitTileLane(-0.25f, -0.25f), D3D12OitTileLane(0.25f, -0.25f),
        D3D12OitTileLane(-0.25f, 0.25f), D3D12OitTileLane(0.25f, 0.25f))

    fun packBatch(viewport: D3D12OitTileViewport, tiles: List<D3D12OitTileRegion>, laneIndex: Int): ByteArray {
        require(tiles.size in 1..MAX_RESIDENT_TILES && laneIndex in lanes.indices)
        val buffer = ByteBuffer.allocate(BATCH_ALLOCATION_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(SCHEMA_VERSION).putInt(tiles.size).putInt(laneIndex).putInt(0)
        buffer.putFloat(viewport.x).putFloat(viewport.y).putFloat(viewport.width).putFloat(viewport.height)
        for (tile in tiles) buffer.putInt(tile.sourceX).putInt(tile.sourceY).putInt(tile.width).putInt(tile.height)
        return buffer.array()
    }

    fun scratchViewport(viewport: D3D12OitTileViewport, tile: D3D12OitTileRegion,
        laneIndex: Int): D3D12OitTileViewport {
        val lane = lanes[laneIndex]
        return viewport.copy(x = viewport.x - tile.sourceX + GUARD_PIXELS - lane.x,
            y = viewport.y - tile.sourceY + GUARD_PIXELS - lane.y)
    }
}

data class D3D12OitTileVertexEvaluation(
    val source: String,
    val parameters: List<D3D12OitTileVertexParameter>,
    val entry: String = "McdrTileEvaluateClipV1",
)

data class D3D12OitTileVertexParameter(val name: String, val hlslType: String, val semantic: String?)

data class D3D12OitTileProgram(
    val stage: D3D12OitReplayStage,
    val profile: D3D12OitCaptureProfile,
    val programGeneration: Long,
    val contractHash: String,
    val directVertexHlsl: String,
    val geometryVertexHlsl: String,
    val geometryHlsl: String,
    val fragmentHlsl: String,
    val laneRegisters: List<Int>,
    val vertexEvaluation: D3D12OitTileVertexEvaluation,
    val flipY: Boolean,
) {
    val binning: D3D12OitTileBinningProgram get() = D3D12OitTileBinning.program

    fun routeTerrainMesh(meshHlsl: String): D3D12OitTileMeshCompilation =
        D3D12OitTileShader.routeTerrainMesh(meshHlsl, fragmentHlsl, laneRegisters)
}

data class D3D12OitTileTerrainRoute(
    val program: D3D12OitTileProgram,
    val capture: D3D12OitCaptureIdentity,
    val meshRoutes: Map<Int, D3D12OitTileMeshCompilation>,
) {
    init {
        require(meshRoutes.keys.all { it in setOf(16, 32, 64) })
        require(program.programGeneration == capture.programGeneration && program.profile == capture.profile)
    }
}

data class D3D12OitTileBinningProgram(
    val nodeSource: String,
    val computeSource: String,
    val nodeEntry: String = "TileEnvelopeEntryV1",
    val initializeEntry: String = "InitializeTileBinsV1",
    val prefixEntry: String = "PrefixTileRefsV1",
    val scatterEntry: String = "ScatterTileCommandsV1",
    val envelopeStride: Int = 64,
    val indexedCommandStride: Int = 36,
    val meshCommandStride: Int = 28,
    val maximumChunkPrimitives: Int = 4096,
)

internal data class D3D12OitTileBinningHandles(
    val workGraph: Long,
    val initialize: Long,
    val prefix: Long,
    val scatter: Long,
) : AutoCloseable {
    init {
        require(workGraph != 0L && initialize != 0L && prefix != 0L && scatter != 0L)
    }

    override fun close() {
        MCDR_ABI.D3D12DestroyWorkGraph(workGraph)
        MCDR_ABI.D3D12DestroyPipeline(initialize)
        MCDR_ABI.D3D12DestroyPipeline(prefix)
        MCDR_ABI.D3D12DestroyPipeline(scatter)
    }
}

internal object D3D12OitTileBinning {
    private val rootBindings = intArrayOf(
        0, 0, 3, 0, 1, 3, 0, 2, 3, 1, 8, 3,
        2, 0, 3, 2, 1, 3, 2, 2, 3, 2, 3, 3,
    )

    val program: D3D12OitTileBinningProgram by lazy {
        val source = listOf("tile_route.hlsl", "tile_envelope.hlsl", "tile_binning.hlsl")
            .joinToString("\n", transform = ::tileShaderResource)
        D3D12OitTileBinningProgram("#define MCDR_TILE_BIN_NODES\n$source", source)
    }

    internal fun compile(): D3D12OitTileBinningHandles {
        fun compile(source: String, entry: String, profile: String): ByteArray =
            checkNotNull(MCDR_ABI.D3D12CompileShaderDxc(source, entry, profile)) {
                "OIT tile binning $profile/$entry compilation failed: ${MCDR_ABI.D3D12LastError()}"
            }
        val node = compile(program.nodeSource, program.nodeEntry, "lib_6_9")
        val initialize = compile(program.computeSource, program.initializeEntry, "cs_6_9")
        val prefix = compile(program.computeSource, program.prefixEntry, "cs_6_9")
        val scatter = compile(program.computeSource, program.scatterEntry, "cs_6_9")
        var graph = 0L
        var initializePipeline = 0L
        var prefixPipeline = 0L
        var scatterPipeline = 0L
        try {
            // Allow one backing allocation for each of the three native command-list slots.
            val backingBudget = 3L * 8L * 1024L * 1024L
            graph = MCDR_ABI.D3D12CreateWorkGraph(node, "IllusionTileBinning", program.nodeEntry,
                16, rootBindings, backingBudget)
            check(graph != 0L) { "OIT tile binning Work Graph creation failed: ${MCDR_ABI.D3D12LastError()}" }
            initializePipeline = MCDR_ABI.D3D12CreateTerrainTileBinningPipeline(initialize)
            check(initializePipeline != 0L) { "OIT tile initialize PSO creation failed: ${MCDR_ABI.D3D12LastError()}" }
            prefixPipeline = MCDR_ABI.D3D12CreateTerrainTileBinningPipeline(prefix)
            check(prefixPipeline != 0L) { "OIT tile prefix PSO creation failed: ${MCDR_ABI.D3D12LastError()}" }
            scatterPipeline = MCDR_ABI.D3D12CreateTerrainTileBinningPipeline(scatter)
            check(scatterPipeline != 0L) { "OIT tile scatter PSO creation failed: ${MCDR_ABI.D3D12LastError()}" }
            return D3D12OitTileBinningHandles(graph, initializePipeline, prefixPipeline, scatterPipeline)
        } catch (failure: Throwable) {
            if (scatterPipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(scatterPipeline)
            if (prefixPipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(prefixPipeline)
            if (initializePipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(initializePipeline)
            if (graph != 0L) MCDR_ABI.D3D12DestroyWorkGraph(graph)
            throw failure
        }
    }
}

sealed interface D3D12OitTileMeshCompilation {
    data class Candidate(val meshHlsl: String, val fragmentHlsl: String,
        val inputMode: D3D12OitTileMeshInput, val envelopeHlsl: String) : D3D12OitTileMeshCompilation
    data class Unsupported(val reason: D3D12OitTileUnsupported) : D3D12OitTileMeshCompilation
}

enum class D3D12OitTileMeshInput { STATIC_DESCRIPTOR }

internal object D3D12OitTileShader {
    private data class Field(val declaration: String, val type: String, val name: String, val semantic: String)
    private data class Parameter(val declaration: String, val type: String, val name: String, val semantic: String?)
    private data class Entry(val match: MatchResult, val result: String, val parameters: List<Parameter>,
        val semantic: String?)
    private data class VertexRoute(val direct: String, val geometry: String,
        val evaluation: D3D12OitTileVertexEvaluation)
    private data class FragmentRoute(val entry: Entry, val renamed: String, val arguments: List<String>,
        val actualPosition: String, val copies: String)
    private data class TileFragment(val hlsl: String, val laneRegisters: List<Int>)
    private data class IaSource(val pipelines: List<OitPipelineSet>, val vertexHash: String, val fragmentHash: String)
    private val iaSources by lazy {
        listOf(
            IaSource(listOf(RenderPipelines.OIT_TRANSLUCENT_BLOCK),
                "6cd75822d15a7e37b2efe06d9af66b859da03950c62bbedc22d3c0ae0c0cc40b", "beb1ace5f3fb71c6701975e7830646b552cff9d60b99b290f0fca7f881aa2b98"),
            IaSource(listOf(RenderPipelines.OIT_ENTITY, RenderPipelines.OIT_ENTITY_EMISSIVE,
                RenderPipelines.OIT_ENTITY_CULL, RenderPipelines.OIT_BREEZE_WIND,
                RenderPipelines.OIT_ENERGY_SWIRL, RenderPipelines.OIT_EYES),
                "18ae94084b67114079e0466a2a08d72f84dd5ec582c610303fde7ebe1beb02f7", "bba84340024dd74f98897bfe65abf47758c1903b4a459f9bee61a43c0b351db7"),
            IaSource(listOf(RenderPipelines.OIT_ENTITY_SHADOW),
                "1b4512cd125a6c8ff758f943e669c7879dd005d10fc2bd8565b1a048930b008c", "076da9740f7fe6d502822b789835aaf445299dc472e84dc4ef4a3d14be26b388"),
            IaSource(listOf(RenderPipelines.OIT_ITEM, RenderPipelines.OIT_ITEM_GLINT, RenderPipelines.OIT_ITEM_GLINT_SPECIAL),
                "23a5d0a0f6c1e13b9923504633f469051fc1f20b3fc8208e259a2b5c8696e510", "9776e32310ca2e011c103771fbe4f560587d31c9b3a3c7989623d7847e4e3fbb"),
            IaSource(listOf(RenderPipelines.OIT_BEACON_BEAM),
                "5850ba0b31c7df4b1876b825f9ddbf0f72d7bdfa53087a19621038b173b2b09a", "3a5f9c010fb08b80db891858721f58c4a0c2c71b5d5aa624ae2b879645d9cc00"),
            IaSource(listOf(RenderPipelines.OIT_CRUMBLING),
                "b5315545b15eeba0574de0c6efcef1f726ac71c93da6bfc88e7a330b03392ead", "64aa2e5a6fc41547f0194cede6cc2da927bed1e8943eae9e567fc4d9993ad436"),
            IaSource(listOf(RenderPipelines.OIT_TEXT, RenderPipelines.OIT_TEXT_GRAYSCALE,
                RenderPipelines.OIT_TEXT_POLYGON_OFFSET, RenderPipelines.OIT_TEXT_GRAYSCALE_POLYGON_OFFSET),
                "b891d1f98c65bf013abf54562d52fa5392fa1480d36acec907e3c23aeb5d7c1c", "347f1c8d6cf7f0d5d89e1ca98fc2481d606727059fe3d0757959f16bd2614b44"),
            IaSource(listOf(RenderPipelines.OIT_LIGHTNING, RenderPipelines.OIT_DRAGON_RAYS),
                "cf5c9fd3a11bb3ed3325203c5723b6ecb08ab1bc6abf897c1f06b4368658bcc8", "f245569ff1e7b7f80ae6c7abfd7a82e9d00dd23e03d286dfa1d97b93747d9ae6"),
            IaSource(listOf(RenderPipelines.OIT_CLOUDS, RenderPipelines.OIT_FLAT_CLOUDS),
                "871f9181ec451065f0845a3232855715199257387daaa958b7e8019f18d76a32", "e02f80cfd807738df4522f663b8de1f40c0894e8e832132804cb905f6e9de9fd"),
            IaSource(listOf(RenderPipelines.OIT_DEBUG_QUADS, RenderPipelines.OIT_DEBUG_TRIANGLE_FAN),
                "b244861695334b410c0f3e61d6071d0ea680f99bb0b268a646a61ba18382e166", "dfe3e9c72e4b47e32d1358402ef2080397965ec09d5d360463438bfe0afe3983"),
            IaSource(listOf(RenderPipelines.OIT_WORLD_BORDER),
                "d11e7d7cf584858ffcd3774144eafc9c8a9d0e090f700536b9850237e11cdd31", "33f0acbe276406def7b7944808e99967d685131d150fd125c0622ef4d6d2ebaf"),
            IaSource(listOf(RenderPipelines.OIT_PARTICLE, RenderPipelines.OIT_WEATHER),
                "cd09f26f5c5a8ee599b33afcb89606a3376e6bda4db8fe753f8339de85b9d0be", "846b63d1fe7cd797fdfda6c3c7bbbb9adb36776c57eb35f927c129047aa60a85"),
        )
    }
    private data class OpaqueSeedSource(val pipeline: RenderPipeline, val vertexHash: String,
        val fragmentHash: String)
    private val opaqueSeedSources by lazy {
        val blockVertex = "6cd75822d15a7e37b2efe06d9af66b859da03950c62bbedc22d3c0ae0c0cc40b"
        val blockFragment = "beb1ace5f3fb71c6701975e7830646b552cff9d60b99b290f0fca7f881aa2b98"
        val terrainVertex = "cff82357a94634820dfb9eaba3cc271a8b832f2d3cfdeea87eccb85227b0f230"
        val terrainFragment = "875eaf6f4d3005f6689d6a6953b71559dae6b8d779a1c62f279c7ffcb4840481"
        val entityVertex = "18ae94084b67114079e0466a2a08d72f84dd5ec582c610303fde7ebe1beb02f7"
        val entityFragment = "bba84340024dd74f98897bfe65abf47758c1903b4a459f9bee61a43c0b351db7"
        val particleVertex = "cd09f26f5c5a8ee599b33afcb89606a3376e6bda4db8fe753f8339de85b9d0be"
        val particleFragment = "846b63d1fe7cd797fdfda6c3c7bbbb9adb36776c57eb35f927c129047aa60a85"
        val itemVertex = "23a5d0a0f6c1e13b9923504633f469051fc1f20b3fc8208e259a2b5c8696e510"
        val itemFragment = "9776e32310ca2e011c103771fbe4f560587d31c9b3a3c7989623d7847e4e3fbb"
        listOf(
            OpaqueSeedSource(RenderPipelines.SOLID_BLOCK, blockVertex, blockFragment),
            OpaqueSeedSource(RenderPipelines.CUTOUT_BLOCK, blockVertex, blockFragment),
            OpaqueSeedSource(RenderPipelines.SOLID_TERRAIN, terrainVertex, terrainFragment),
            OpaqueSeedSource(RenderPipelines.CUTOUT_TERRAIN, terrainVertex, terrainFragment),
            OpaqueSeedSource(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, terrainVertex, terrainFragment),
            OpaqueSeedSource(RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, terrainVertex, terrainFragment),
            OpaqueSeedSource(RenderPipelines.ENTITY_SOLID, entityVertex, entityFragment),
            OpaqueSeedSource(RenderPipelines.ENTITY_CUTOUT, entityVertex, entityFragment),
            OpaqueSeedSource(RenderPipelines.ENTITY_CUTOUT_CULL, entityVertex, entityFragment),
            OpaqueSeedSource(RenderPipelines.OPAQUE_PARTICLE, particleVertex, particleFragment),
            OpaqueSeedSource(RenderPipelines.ITEM_CUTOUT, itemVertex, itemFragment),
        )
    }
    private val entryPattern = Regex("(?m)^[\\t ]*([A-Za-z_][A-Za-z_0-9]*) main\\(([^)]*)\\)\\s*(?::\\s*([A-Za-z_][A-Za-z_0-9]*))?\\s*\\{")
    private val parameterPattern = Regex("^([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)(?:\\s*:\\s*([A-Za-z_][A-Za-z_0-9]*))?$")
    private val fieldPattern = Regex("^((?:(?:nointerpolation|linear|noperspective|centroid|sample)\\s+)*)([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*:\\s*([A-Za-z_][A-Za-z_0-9]*)$")
    private val prelude by lazy { tileShaderResource("tile_route.hlsl") }
    private val terrainEnvelopePrelude by lazy {
        listOf("tile_envelope.hlsl", "tile_binning.hlsl", "terrain_tile_envelope.hlsl")
            .joinToString("\n", transform = ::tileShaderResource)
    }
    private val laneAttachmentNames = listOf("DepthBoundsSampler", "Coeff0", "Coeff1", "Coeff2", "Coeff3")
    private val spaceZeroSrv = Regex("\\bregister\\s*\\(\\s*t(\\d+)\\s*(?:,\\s*space0\\s*)?\\)")

    fun buildOpaqueSeed(shader: D3D12CompiledShader, pipeline: RenderPipeline, compilerDefines: ShaderDefines,
        vertexSource: String, fragmentSource: String, generation: Long, flipY: Boolean): D3D12OpaqueSeedCompilation {
        val stock = opaqueSeedSources.singleOrNull { it.pipeline === pipeline }
            ?: return D3D12OpaqueSeedCompilation.NotApplicable
        val directives = compilerDefines.asSourceDirectives() + pipeline.shaderDefines.asSourceDirectives()
        if (generation <= 0 || compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE") ||
            sourceHash(vertexSource, directives) != stock.vertexHash ||
            sourceHash(fragmentSource, directives) != stock.fragmentHash || shader.oitCapture != null)
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.SOURCE_CONTRACT)
        if (pipeline.primitiveTopology !in setOf(PrimitiveTopology.TRIANGLES, PrimitiveTopology.QUADS,
                PrimitiveTopology.TRIANGLE_FAN))
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.TOPOLOGY)
        val combined = shader.vertexHlsl + shader.fragmentHlsl
        if (Regex("register\\([^)]*space3\\s*\\)").containsMatchIn(combined))
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.RESERVED_BINDING)
        if (Regex("\\b(?:RW\\w*|AppendStructuredBuffer|ConsumeStructuredBuffer|Interlocked\\w*|" +
                "SV_Depth\\w*|SV_Coverage|SV_SampleIndex|SV_VertexID|SV_StartVertexLocation|SV_StartInstanceLocation)\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(combined))
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val vertex = buildVertexRoute(shader.vertexHlsl)
            ?: return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val fragment = fragmentRoute(shader.fragmentHlsl)
            ?: return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val outputs = fields(shader.fragmentHlsl, fragment.entry.result)
        if (fragment.entry.semantic?.equals("SV_Target0", true) != true &&
            (outputs == null || outputs.size != 1 || !outputs.single().semantic.equals("SV_Target0", true)))
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val depthFragment = prelude + "\n" + fragment.renamed + "\n" + """
            void main(${fragment.entry.parameters.joinToString(", ") { it.declaration }}) {
                ${fragment.copies}
                McdrTileOriginalFragmentV1(${fragment.entry.parameters.joinToString(", ") { it.name }});
            }
        """.trimIndent()
        val contract = listOf("illusion.opaque-seed-tile-shader/1", stock.vertexHash, stock.fragmentHash,
            directives, generation, flipY, pipeline.primitiveTopology, prelude, vertex.direct,
            depthFragment).joinToString("\n")
        return D3D12OpaqueSeedCompilation.Candidate(D3D12OpaqueSeedProgram(generation, shaderHash(contract),
            vertex.direct, depthFragment, vertex.evaluation, flipY))
    }

    fun buildIa(shader: D3D12CompiledShader, pipeline: RenderPipeline, compilerDefines: ShaderDefines,
        vertexSource: String, fragmentSource: String, generation: Long, flipY: Boolean): D3D12OitTileIaCompilation {
        val stock = iaSources.singleOrNull { source -> source.pipelines.any { pipelines ->
            pipeline === pipelines.depthBoundsPipeline() || pipeline === pipelines.transmittancePipeline() ||
                pipeline === pipelines.accumulatePipeline()
        } } ?: return D3D12OitTileIaCompilation.NotApplicable
        val stage = stock.pipelines.firstNotNullOf { pipelines -> when {
            pipeline === pipelines.depthBoundsPipeline() -> D3D12OitReplayStage.DEPTH_BOUNDS
            pipeline === pipelines.transmittancePipeline() -> D3D12OitReplayStage.TRANSMITTANCE
            pipeline === pipelines.accumulatePipeline() -> D3D12OitReplayStage.ACCUMULATE
            else -> null
        } }
        val defines = pipeline.shaderDefines
        val directives = compilerDefines.asSourceDirectives() + defines.asSourceDirectives()
        if (generation <= 0 || compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE") ||
            defines.flags().filter { it in D3D12OitReplayStage.entries.map { stage -> stage.shaderDefine } } !=
                listOf(stage.shaderDefine) || "OIT" !in defines.flags() ||
            sourceHash(vertexSource, directives) != stock.vertexHash ||
            sourceHash(fragmentSource, directives) != stock.fragmentHash)
            return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.SOURCE_CONTRACT)
        if (pipeline.primitiveTopology !in setOf(PrimitiveTopology.TRIANGLES, PrimitiveTopology.QUADS,
                PrimitiveTopology.TRIANGLE_FAN))
            return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.TOPOLOGY)
        if (Regex("register\\([^)]*space3\\s*\\)").containsMatchIn(shader.vertexHlsl + shader.fragmentHlsl))
            return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.RESERVED_BINDING)
        val cloudVertexPulling = stock.pipelines.all {
            it === RenderPipelines.OIT_CLOUDS || it === RenderPipelines.OIT_FLAT_CLOUDS
        } && pipeline.vertexFormatBindings.all { it == null } && shader.vertexInputs.isEmpty() &&
            shader.entries.count { it.type == D3D12BindGroupEntry.Type.TEXEL_BUFFER && it.name == "CloudFaces" } == 1
        // 无 VB 的原版 cloud 保留原 draw 的 VertexID；其余 IA 仍会重定位顶点范围。
        if (Regex("\\b(?:SV_StartVertexLocation|SV_StartInstanceLocation)\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(shader.vertexHlsl) ||
            !cloudVertexPulling && Regex("\\bSV_VertexID\\b", RegexOption.IGNORE_CASE).containsMatchIn(shader.vertexHlsl))
            return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val vertex = buildVertexRoute(shader.vertexHlsl)
            ?: return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val fragment = buildFragment(shader.fragmentHlsl)
            ?: return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.ATTACHMENT_SAMPLING)
        if (!validLaneBindings(shader, pipeline, stage, fragment))
            return D3D12OitTileIaCompilation.Unsupported(D3D12OitTileUnsupported.ATTACHMENT_SAMPLING)
        var directVertex = vertex.direct
        var directFragment = fragment.hlsl
        if (!Regex("\\bSV_InstanceID\\b", RegexOption.IGNORE_CASE).containsMatchIn(shader.vertexHlsl)) {
            val vertexEntry = checkNotNull(entry(directVertex))
            val fragmentEntry = checkNotNull(entry(directFragment))
            val vertexArguments = vertexEntry.parameters.map { it.declaration } +
                "uint McdrTileIaInstance : SV_InstanceID"
            val fragmentArguments = fragmentEntry.parameters.map { it.declaration } +
                "nointerpolation uint McdrTileIaSlice : MCDR_TILE_IA_SLOT"
            directVertex = directVertex.replaceRange(vertexEntry.match.range,
                vertexEntry.match.value.replaceFirst("(${vertexEntry.match.groupValues[2]})",
                    "(${vertexArguments.joinToString(", ")})") +
                    "\n    McdrTileSlot = McdrTileVisibleMeshlet == 1u ? McdrTileIaInstance : McdrTileRootSlot;")
                .replace("struct McdrTileVertexOutputV1 {",
                    "struct McdrTileVertexOutputV1 {\n    nointerpolation uint McdrTileIaSlice : MCDR_TILE_IA_SLOT;")
                .replace("output.McdrTileViewport = McdrTileSlot;",
                    "output.McdrTileIaSlice = McdrTileSlot;\n    output.McdrTileViewport = McdrTileSlot;")
            directFragment = directFragment.replaceRange(fragmentEntry.match.range,
                fragmentEntry.match.value.replaceFirst("(${fragmentEntry.match.groupValues[2]})",
                    "(${fragmentArguments.joinToString(", ")})") +
                    "\n    McdrTileSlot = McdrTileIaSlice;")
            // IA 专用第 4 个常量选择实例路由；mesh/逐 tile 合同仍使用原始常量布局。
            val batchPrelude = "#define MCDR_TILE_IA_INSTANCE_BATCH_V1 1\nstatic uint McdrTileSlot = 0u;\n"
            directVertex = batchPrelude + directVertex.replace("uint McdrTileSlot;", "uint McdrTileRootSlot;")
                .replace("McdrTileVisibleMeshlet", "McdrTileIaRouteFlags")
            directFragment = batchPrelude + directFragment.replace("uint McdrTileSlot;", "uint McdrTileRootSlot;")
                .replace("McdrTileVisibleMeshlet", "McdrTileIaRouteFlags")
        }
        val contract = listOf("illusion.oit-tile-ia/2", stage, generation, flipY, pipeline.location,
            pipeline.primitiveTopology, directives, directVertex, directFragment, fragment.laneRegisters)
            .joinToString("\n")
        return D3D12OitTileIaCompilation.Candidate(D3D12OitTileIaProgram(stage, generation, shaderHash(contract),
            directVertex, directFragment, fragment.laneRegisters, vertex.evaluation, flipY))
    }

    fun build(shader: D3D12CompiledShader, pipeline: RenderPipeline, certification: D3D12OitReplayCertification,
        generation: Long, flipY: Boolean): D3D12OitTileCompilation {
        if (pipeline.primitiveTopology !in setOf(PrimitiveTopology.TRIANGLES, PrimitiveTopology.QUADS,
                PrimitiveTopology.TRIANGLE_FAN) || certification.profile == D3D12OitCaptureProfile.LINE)
            return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.TOPOLOGY)
        if (Regex("register\\([^)]*space3\\s*\\)").containsMatchIn(shader.vertexHlsl + shader.fragmentHlsl))
            return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.RESERVED_BINDING)
        val vertex = buildVertexRoute(shader.vertexHlsl)
            ?: return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val fragment = buildFragment(shader.fragmentHlsl)
            ?: return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.ATTACHMENT_SAMPLING)
        if (!validLaneBindings(shader, pipeline, certification.stage, fragment))
            return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.ATTACHMENT_SAMPLING)
        val contract = listOf(D3D12OitTileContract.SCHEMA, D3D12OitTileContract.TERRAIN_MESH_SCHEMA,
            certification.stage, certification.profile,
            generation, flipY, pipeline.primitiveTopology, prelude, vertex.direct, fragment.hlsl,
            fragment.laneRegisters).joinToString("\n")
        return D3D12OitTileCompilation.Candidate(D3D12OitTileProgram(certification.stage, certification.profile,
            generation, shaderHash(contract), vertex.direct, shader.vertexHlsl, vertex.geometry, fragment.hlsl,
            fragment.laneRegisters,
            vertex.evaluation, flipY))
    }

    private fun validLaneBindings(shader: D3D12CompiledShader, pipeline: RenderPipeline,
        stage: D3D12OitReplayStage, fragment: TileFragment): Boolean {
        val registers = fragment.laneRegisters
        val coefficients = registers.drop(1)
        val count = pipeline.shaderDefines.values()["OIT_COEFF_ATTACHMENT_COUNT"]?.toIntOrNull() ?: return false
        if (count !in setOf(1, 2, 4)) return false
        val needsBounds = stage != D3D12OitReplayStage.DEPTH_BOUNDS
        if ((registers.first() >= 0) != needsBounds || coefficients.withIndex().any { (index, register) ->
                (register >= 0) != (stage == D3D12OitReplayStage.ACCUMULATE && index < count)
            }) return false
        return registers.withIndex().filter { it.value >= 0 }.all { (index, register) ->
            val binding = shader.entries.getOrNull(register)
            binding?.type == D3D12BindGroupEntry.Type.SAMPLED_IMAGE && binding.name == laneAttachmentNames[index]
        } && !declaresLaneSrv(shader.vertexHlsl, registers) && !declaresLaneSrv(fragment.hlsl, registers)
    }

    private fun buildVertexRoute(source: String): VertexRoute? {
        val vertex = entry(source) ?: return null
        val vertexFields = fields(source, vertex.result) ?: return null
        val position = vertexFields.singleOrNull { it.type == "float4" && it.semantic.equals("SV_Position", true) }
            ?: return null
        if (vertexFields.any { it.semantic.startsWith("MCDR_TILE", true) ||
                it.semantic.equals("SV_ViewportArrayIndex", true) || it.semantic.equals("SV_RenderTargetArrayIndex", true) })
            return null
        val renamedVertex = rename(source, vertex, "McdrTileOriginalVertexV1")
        val directVertex = renamedVertex + "\n" + prelude + "\n" + """
            struct McdrTileVertexOutputV1 {
                ${vertexFields.joinToString("\n") { it.declaration + ";" }}
                uint McdrTileViewport : SV_ViewportArrayIndex;
                uint McdrTileSlice : SV_RenderTargetArrayIndex;
            };
            McdrTileVertexOutputV1 main(${vertex.parameters.joinToString(", ") { it.declaration }}) {
                ${vertex.result} original = McdrTileOriginalVertexV1(${vertex.parameters.joinToString(", ") { it.name }});
                McdrTileVertexOutputV1 output = (McdrTileVertexOutputV1)0;
                ${vertexFields.joinToString("\n") { "output.${it.name} = original.${it.name};" }}
                output.McdrTileViewport = McdrTileSlot;
                output.McdrTileSlice = McdrTileSlot;
                return output;
            }
        """.trimIndent()
        val geometry = prelude + "\n" + """
            struct McdrTileGeometryInputV1 {
                ${vertexFields.joinToString("\n") { it.declaration + ";" }}
            };
            struct McdrTileGeometryOutputV1 {
                ${vertexFields.joinToString("\n") { it.declaration + ";" }}
                uint McdrTileViewport : SV_ViewportArrayIndex;
                uint McdrTileSlice : SV_RenderTargetArrayIndex;
            };
            [maxvertexcount(3)]
            void main(triangle McdrTileGeometryInputV1 vertices[3],
                inout TriangleStream<McdrTileGeometryOutputV1> outputStream) {
                [unroll] for (uint vertexIndex = 0u; vertexIndex < 3u; ++vertexIndex) {
                    McdrTileGeometryOutputV1 output = (McdrTileGeometryOutputV1)0;
                    ${vertexFields.joinToString("\n") { "output.${it.name} = vertices[vertexIndex].${it.name};" }}
                    output.McdrTileViewport = McdrTileSlot;
                    output.McdrTileSlice = McdrTileSlot;
                    outputStream.Append(output);
                }
                outputStream.RestartStrip();
            }
        """.trimIndent()
        val evaluationParameters = vertex.parameters.map { "${it.type} ${it.name}" }
        val evaluation = renamedVertex + "\nfloat4 McdrTileEvaluateClipV1(${evaluationParameters.joinToString(", ")}) {\n" +
            "    return McdrTileOriginalVertexV1(${vertex.parameters.joinToString(", ") { it.name }}).${position.name};\n}\n"
        return VertexRoute(directVertex, geometry,
            D3D12OitTileVertexEvaluation(evaluation, java.util.List.copyOf(vertex.parameters.map {
                D3D12OitTileVertexParameter(it.name, it.type, it.semantic)
            })))
    }

    fun routeTerrainMesh(source: String, fragment: String, laneRegisters: List<Int>): D3D12OitTileMeshCompilation {
        // The generated mesh shader and envelope both reuse the source VS declarations.
        if (declaresLaneSrv(source, laneRegisters) || declaresLaneSrv(fragment, laneRegisters))
            return D3D12OitTileMeshCompilation.Unsupported(D3D12OitTileUnsupported.RESERVED_BINDING)
        val meshlet = "uint meshletId = gVisibleBase + gid.x + gid.y * 256u;"
        val drawSlice = "cbuffer TerrainDrawSlice : register(b9) { uint gVisibleBase; };"
        val loadMeshlet = Regex("MeshletDesc loadMeshlet\\(uint id\\) \\{[^}]*}")
        val visibleMeshlet = loadMeshlet.findAll(source).singleOrNull()
        val primitiveCount = Regex("out indices uint3 tris\\[(\\d+)]").find(source)?.groupValues?.get(1)?.toIntOrNull()
        val vertexOutput = Regex("typedef (\\w+) D3D12VertexOutput;").find(source)?.groupValues?.get(1)
        val position = vertexOutput?.let { fields(source, it) }?.singleOrNull {
            it.type == "float4" && it.semantic.equals("SV_Position", true)
        }
        if (primitiveCount == null || primitiveCount !in setOf(32, 64, 128) || position == null ||
            source.split(meshlet).size != 2 || source.contains("MCDR_OIT_") ||
            source.split(drawSlice).size != 2 ||
            visibleMeshlet == null || !visibleMeshlet.value.contains("return loadMeshletDescriptor(work.x, work.y);") ||
            !source.contains("uint b = 32u + id * 32u;") ||
            !source.contains("#ifndef MCDR_TILE_TERRAIN_ENVELOPES") ||
            source.contains("SV_ViewportArrayIndex") || source.contains("SV_RenderTargetArrayIndex"))
            return D3D12OitTileMeshCompilation.Unsupported(D3D12OitTileUnsupported.MESH_SIGNATURE)
        var result = source.replaceRange(visibleMeshlet.range, """
            MeshletDesc loadMeshlet(uint id) {
                return loadMeshletDescriptor(id, McdrTileSourceInstance);
            }
        """.trimIndent())
            .replace(drawSlice, "")
            .replace(meshlet, "uint meshletId = McdrTileVisibleMeshlet;")
            .replace("if (meshletId < gSections.Load(0u)) {", "{")
        val declaration = Regex("struct D3D12MeshPrimitive \\{([^}]*)}", RegexOption.DOT_MATCHES_ALL).find(result)
        val routeFields = "uint McdrTileViewport : SV_ViewportArrayIndex; uint McdrTileSlice : SV_RenderTargetArrayIndex;"
        result = if (declaration != null) result.replaceRange(declaration.range,
            "struct D3D12MeshPrimitive { ${declaration.groupValues[1]} $routeFields }") else
            "struct D3D12MeshPrimitive { $routeFields };\n$result"
        if (!Regex("out primitives\\s+D3D12MeshPrimitive\\s+prims\\[").containsMatchIn(result)) {
            result = result.replace("out indices uint3 tris[$primitiveCount]",
                "out primitives D3D12MeshPrimitive prims[$primitiveCount],\n             out indices uint3 tris[$primitiveCount]")
        }
        val indexStore = "tris[vid * 2u + 1u] = uint3(baseV, baseV + 2u, baseV + 3u);"
        if (result.split(indexStore).size != 2)
            return D3D12OitTileMeshCompilation.Unsupported(D3D12OitTileUnsupported.MESH_SIGNATURE)
        result = result.replace(indexStore, indexStore + "\n" + """
                prims[vid * 2u].McdrTileViewport = McdrTileSlot;
                prims[vid * 2u + 1u].McdrTileViewport = McdrTileSlot;
                prims[vid * 2u].McdrTileSlice = McdrTileSlot;
                prims[vid * 2u + 1u].McdrTileSlice = McdrTileSlot;
        """.trimIndent())
        result = result.replace(".shadingRate = primRate;", ".shadingRate = 0u;")
        val evaluation = """
            float4 McdrTileEvaluateTerrainClipV1(SectionDesc section, uint vertexIndex) {
                return vs_main(loadTerrainVertex(section, vertexIndex)).${position.name};
            }
        """.trimIndent()
        val envelope = "#define MCDR_TILE_TERRAIN_ENVELOPES\n" +
            "#define MCDR_TILE_TERRAIN_MAX_QUADS ${primitiveCount / 2}u\n" +
            "$prelude\n$source\n$evaluation\n$terrainEnvelopePrelude"
        return D3D12OitTileMeshCompilation.Candidate(prelude + "\n" + result, fragment,
            D3D12OitTileMeshInput.STATIC_DESCRIPTOR, envelope)
    }

    private fun buildFragment(source: String): TileFragment? {
        val route = fragmentRoute(source) ?: return null
        val fragment = route.entry
        var result = route.renamed
        val actualPosition = route.actualPosition
        val arguments = route.arguments
        val copies = route.copies
        val textureDeclarations = Regex("Texture2D<float4>\\s+(\\w+)\\s*:\\s*register\\(t(\\d+)(?:,\\s*space0)?\\)\\s*;")
        val attachments = textureDeclarations.findAll(result).filter { declaration ->
            declaration.groupValues[1].removeSuffix("_") in laneAttachmentNames
        }.toList()
        val attachmentNames = attachments.map { it.groupValues[1].removeSuffix("_") }.toSet()
        if (attachmentNames.size != attachments.size) return null
        val laneRegisters = IntArray(laneAttachmentNames.size) { -1 }
        for (declaration in attachments.asReversed()) {
            val name = declaration.groupValues[1]
            val binding = laneAttachmentNames.indexOf(name.removeSuffix("_"))
            val register = declaration.groupValues[2].toIntOrNull() ?: return null
            if (register in laneRegisters) return null
            laneRegisters[binding] = register
            result = result.replaceRange(declaration.range, "Texture2DArray<float4> $name : register(t$binding, space3);")
        }
        for (declaration in attachments) {
            val name = declaration.groupValues[1]
            val methods = Regex("\\b${Regex.escape(name)}\\s*\\.\\s*(\\w+)\\s*\\(").findAll(result).toList()
            if (methods.any { it.groupValues[1] != "Load" }) return null
            for (load in methods.asReversed()) {
                var cursor = load.range.last + 1
                var depth = 1
                while (cursor < result.length && depth > 0) {
                    when (result[cursor]) { '(' -> depth++; ')' -> depth-- }
                    cursor++
                }
                if (depth != 0) return null
                val coordinate = result.substring(load.range.last + 1, cursor - 1).trim()
                val validCoordinate = Regex(
                    "int3\\(\\s*(?:naga_f2i32\\(\\s*[A-Za-z_]\\w*\\.xy\\s*\\)|[A-Za-z_]\\w*)\\s*,\\s*int\\(0\\)\\s*\\)")
                    .matches(coordinate)
                if (!validCoordinate) return null
                result = result.replaceRange(load.range.first, cursor,
                    "$name.Load(int4(int2(McdrTileScratchPositionV1.xy), int(McdrTileSlot), 0))")
            }
        }
        val semantic = fragment.semantic?.let { " : $it" } ?: ""
        val call = "McdrTileOriginalFragmentV1(${fragment.parameters.joinToString(", ") { it.name }});"
        val invocation = if (fragment.result == "void") call else "return $call"
        val hlsl = prelude + "\nstatic float4 McdrTileScratchPositionV1 = (float4)0;\n" + result + "\n" + """
            ${fragment.result} main(${arguments.joinToString(", ")})$semantic {
                McdrTileScratchPositionV1 = $actualPosition;
                $copies
                $invocation
            }
        """.trimIndent()
        return TileFragment(hlsl, java.util.List.copyOf(laneRegisters.toList()))
    }

    private fun declaresLaneSrv(source: String, laneRegisters: List<Int>): Boolean =
        spaceZeroSrv.findAll(source).any { (it.groupValues[1].toIntOrNull() ?: -1) in laneRegisters }

    private fun fragmentRoute(source: String): FragmentRoute? {
        val fragment = entry(source) ?: return null
        val inputFields = fragment.parameters.flatMap { parameter ->
            if (parameter.semantic != null) listOf(Field(parameter.declaration, parameter.type, parameter.name,
                parameter.semantic)) else fields(source, parameter.type) ?: return null
        }
        if (inputFields.any { it.semantic.equals("SV_Coverage", true) || it.semantic.equals("SV_SampleIndex", true) }) return null
        val position = inputFields.singleOrNull { it.semantic.equals("SV_Position", true) && it.type == "float4" }
        val actualPosition = fragment.parameters.flatMap { parameter ->
            if (parameter.semantic != null) {
                if (parameter.semantic.equals("SV_Position", true)) listOf(parameter.name) else emptyList()
            } else fields(source, parameter.type)?.filter { it.semantic.equals("SV_Position", true) }
                ?.map { "${parameter.name}.${it.name}" } ?: return null
        }.singleOrNull() ?: "McdrTilePositionV1"
        val arguments = fragment.parameters.map { it.declaration }.toMutableList()
        if (position == null) arguments.add("float4 $actualPosition : SV_Position")
        val copies = fragment.parameters.joinToString("\n") { parameter ->
            if (parameter.semantic != null) {
                when {
                    parameter.semantic.equals("SV_Position", true) -> "${parameter.name} = McdrTileSourcePositionV1(${parameter.name});"
                    parameter.semantic.equals("SV_PrimitiveID", true) -> "${parameter.name} = McdrTileSourcePrimitive;"
                    else -> ""
                }
            } else checkNotNull(fields(source, parameter.type)).joinToString("\n") { field ->
                when {
                    field.semantic.equals("SV_Position", true) -> "${parameter.name}.${field.name} = McdrTileSourcePositionV1(${parameter.name}.${field.name});"
                    field.semantic.equals("SV_PrimitiveID", true) -> "${parameter.name}.${field.name} = McdrTileSourcePrimitive;"
                    else -> ""
                }
            }
        }
        return FragmentRoute(fragment, rename(source, fragment, "McdrTileOriginalFragmentV1"),
            arguments, actualPosition, copies)
    }

    private fun sourceHash(source: String, directives: String): String? {
        val normalized = source.replace("\r\n", "\n")
        val firstLine = normalized.indexOf('\n')
        if (!normalized.startsWith("#version ") || firstLine < 0 ||
            !normalized.startsWith(directives, firstLine + 1)) return null
        return shaderHash(normalized.removeRange(firstLine + 1, firstLine + 1 + directives.length))
    }

    private fun shaderHash(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun entry(source: String): Entry? {
        val match = entryPattern.findAll(source).toList().singleOrNull() ?: return null
        val parameters = match.groupValues[2].split(',').filter(String::isNotBlank).map { declaration ->
            val parameter = parameterPattern.matchEntire(declaration.trim()) ?: return null
            Parameter(declaration.trim(), parameter.groupValues[1], parameter.groupValues[2],
                parameter.groupValues[3].takeIf(String::isNotEmpty))
        }
        return Entry(match, match.groupValues[1], parameters, match.groupValues[3].takeIf(String::isNotEmpty))
    }

    private fun fields(source: String, type: String): List<Field>? {
        val declaration = Regex("\\bstruct ${Regex.escape(type)}\\s*\\{([^}]*)}\\s*;",
            RegexOption.DOT_MATCHES_ALL).find(source) ?: return null
        return declaration.groupValues[1].split(';').filter(String::isNotBlank).map { field ->
            val match = fieldPattern.matchEntire(field.trim()) ?: return null
            Field(field.trim(), match.groupValues[2], match.groupValues[3], match.groupValues[4])
        }
    }

    private fun rename(source: String, entry: Entry, name: String): String = source.replaceRange(entry.match.range,
        entry.match.value.replaceFirst(" main(", " $name("))

}

private fun tileShaderResource(name: String): String = checkNotNull(D3D12OitTileShader::class.java.getResourceAsStream(
    "/assets/illusion/shaders/oit/$name")) { "Missing OIT tile shader $name" }
    .bufferedReader(Charsets.UTF_8).use { it.readText() }
