package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
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
    val vertexEvaluation: D3D12OitTileVertexEvaluation,
    val flipY: Boolean,
) {
    val binning: D3D12OitTileBinningProgram get() = D3D12OitTileBinning.program

    fun routeTerrainMesh(meshHlsl: String): D3D12OitTileMeshCompilation =
        D3D12OitTileShader.routeTerrainMesh(meshHlsl, fragmentHlsl)
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
        val resources = listOf("tile_route.hlsl", "tile_envelope.hlsl", "tile_binning.hlsl").map { name ->
            checkNotNull(javaClass.getResourceAsStream("/assets/illusion/shaders/oit/$name")) {
                "Missing OIT tile shader $name"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val source = resources.joinToString("\n")
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
            graph = MCDR_ABI.D3D12CreateWorkGraph(node, "IllusionTileBinning", program.nodeEntry,
                16, rootBindings, 8L * 1024L * 1024L)
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
        val inputMode: D3D12OitTileMeshInput) : D3D12OitTileMeshCompilation
    data class Unsupported(val reason: D3D12OitTileUnsupported) : D3D12OitTileMeshCompilation
}

enum class D3D12OitTileMeshInput { STATIC_DESCRIPTOR }

internal object D3D12OitTileShader {
    private data class Field(val declaration: String, val type: String, val name: String, val semantic: String)
    private data class Parameter(val declaration: String, val type: String, val name: String, val semantic: String?)
    private data class Entry(val match: MatchResult, val result: String, val parameters: List<Parameter>,
        val semantic: String?)
    private val entryPattern = Regex("(?m)^[\\t ]*([A-Za-z_][A-Za-z_0-9]*) main\\(([^)]*)\\)\\s*(?::\\s*([A-Za-z_][A-Za-z_0-9]*))?\\s*\\{")
    private val parameterPattern = Regex("^([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)(?:\\s*:\\s*([A-Za-z_][A-Za-z_0-9]*))?$")
    private val fieldPattern = Regex("^((?:(?:nointerpolation|linear|noperspective|centroid|sample)\\s+)*)([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*:\\s*([A-Za-z_][A-Za-z_0-9]*)$")
    private val prelude by lazy { resource("tile_route.hlsl") }

    fun build(shader: D3D12CompiledShader, pipeline: RenderPipeline, certification: D3D12OitReplayCertification,
        generation: Long, flipY: Boolean): D3D12OitTileCompilation {
        if (pipeline.primitiveTopology !in setOf(PrimitiveTopology.TRIANGLES, PrimitiveTopology.QUADS,
                PrimitiveTopology.TRIANGLE_FAN) || certification.profile == D3D12OitCaptureProfile.LINE)
            return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.TOPOLOGY)
        if (Regex("register\\([^)]*space3\\s*\\)").containsMatchIn(shader.vertexHlsl + shader.fragmentHlsl))
            return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.RESERVED_BINDING)
        val vertex = entry(shader.vertexHlsl)
            ?: return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val vertexFields = fields(shader.vertexHlsl, vertex.result)
            ?: return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val position = vertexFields.singleOrNull { it.type == "float4" && it.semantic.equals("SV_Position", true) }
            ?: return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        if (vertexFields.any { it.semantic.startsWith("MCDR_TILE", true) ||
                it.semantic.equals("SV_ViewportArrayIndex", true) || it.semantic.equals("SV_RenderTargetArrayIndex", true) })
            return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val fragment = buildFragment(shader.fragmentHlsl, certification.stage)
            ?: return D3D12OitTileCompilation.Unsupported(D3D12OitTileUnsupported.ATTACHMENT_SAMPLING)
        val renamedVertex = rename(shader.vertexHlsl, vertex, "McdrTileOriginalVertexV1")
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
        val contract = listOf(D3D12OitTileContract.SCHEMA, D3D12OitTileContract.TERRAIN_MESH_SCHEMA,
            certification.stage, certification.profile,
            generation, flipY, pipeline.primitiveTopology, prelude, directVertex, fragment).joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(contract.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return D3D12OitTileCompilation.Candidate(D3D12OitTileProgram(certification.stage, certification.profile,
            generation, hash, directVertex, shader.vertexHlsl, geometry, fragment,
            D3D12OitTileVertexEvaluation(evaluation, java.util.List.copyOf(vertex.parameters.map {
                D3D12OitTileVertexParameter(it.name, it.type, it.semantic)
            })), flipY))
    }

    fun routeTerrainMesh(source: String, fragment: String): D3D12OitTileMeshCompilation {
        val meshlet = "uint meshletId = gVisibleBase + gid.x + gid.y * 256u;"
        val drawSlice = "cbuffer TerrainDrawSlice : register(b9) { uint gVisibleBase; };"
        val loadMeshlet = Regex("MeshletDesc loadMeshlet\\(uint id\\) \\{[^}]*}")
        val visibleMeshlet = loadMeshlet.findAll(source).singleOrNull()
        val primitiveCount = Regex("out indices uint3 tris\\[(\\d+)]").find(source)?.groupValues?.get(1)
        if (primitiveCount == null || source.split(meshlet).size != 2 || source.contains("MCDR_OIT_") ||
            source.split(drawSlice).size != 2 ||
            visibleMeshlet == null || !visibleMeshlet.value.contains("uint b = work.x * 16u;") ||
            !visibleMeshlet.value.contains("m.sectionId = work.y;") ||
            !source.contains("uint b = 32u + id * 32u;") ||
            source.contains("SV_ViewportArrayIndex") || source.contains("SV_RenderTargetArrayIndex"))
            return D3D12OitTileMeshCompilation.Unsupported(D3D12OitTileUnsupported.MESH_SIGNATURE)
        var result = source.replaceRange(visibleMeshlet.range, """
            MeshletDesc loadMeshlet(uint id) {
                uint b = id * 16u;
                MeshletDesc m;
                m.packedMin = gMeshlets.Load(b + 0u);
                m.packedMax = gMeshlets.Load(b + 4u);
                m.sectionId = McdrTileSourceInstance;
                m.localVertexOffset = gMeshlets.Load(b + 8u);
                return m;
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
        return D3D12OitTileMeshCompilation.Candidate(prelude + "\n" + result, fragment,
            D3D12OitTileMeshInput.STATIC_DESCRIPTOR)
    }

    private fun buildFragment(source: String, stage: D3D12OitReplayStage): String? {
        val fragment = entry(source) ?: return null
        var result = rename(source, fragment, "McdrTileOriginalFragmentV1")
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
        val textureDeclarations = Regex("Texture2D<float4>\\s+(\\w+)\\s*:\\s*register\\(t(\\d+)(?:,\\s*space0)?\\)\\s*;")
        val attachments = textureDeclarations.findAll(result).filter { declaration ->
            declaration.groupValues[1].removeSuffix("_") in setOf("DepthBoundsSampler", "Coeff0", "Coeff1", "Coeff2", "Coeff3")
        }.toList()
        val attachmentNames = attachments.map { it.groupValues[1].removeSuffix("_") }.toSet()
        if (stage != D3D12OitReplayStage.DEPTH_BOUNDS && "DepthBoundsSampler" !in attachmentNames) return null
        if (stage == D3D12OitReplayStage.ACCUMULATE && "Coeff0" !in attachmentNames) return null
        for (declaration in attachments.asReversed()) {
            val name = declaration.groupValues[1]
            val binding = if (name.removeSuffix("_") == "DepthBoundsSampler") 0 else
                name.removeSuffix("_").removePrefix("Coeff").toInt() + 1
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
        return prelude + "\nstatic float4 McdrTileScratchPositionV1 = (float4)0;\n" + result + "\n" + """
            ${fragment.result} main(${arguments.joinToString(", ")})$semantic {
                McdrTileScratchPositionV1 = $actualPosition;
                $copies
                return McdrTileOriginalFragmentV1(${fragment.parameters.joinToString(", ") { it.name }});
            }
        """.trimIndent()
    }

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

    private fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream(
        "/assets/illusion/shaders/oit/$name")) { "Missing OIT tile shader $name" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
