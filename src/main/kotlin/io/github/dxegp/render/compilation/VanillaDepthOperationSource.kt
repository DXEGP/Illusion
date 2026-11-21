package io.github.dxegp.render.compilation

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.PolygonMode
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.shader.D3D12CompiledShader
import io.github.dxegp.d3d12.shader.D3D12OitTileUnsupported
import io.github.dxegp.d3d12.shader.D3D12OitTileVertexEvaluation
import io.github.dxegp.d3d12.shader.D3D12OitTileVertexParameter
import io.github.dxegp.d3d12.shader.D3D12OpaqueSeedCompilation
import io.github.dxegp.d3d12.shader.D3D12OpaqueSeedProgram
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.ShaderDefines
import java.security.MessageDigest
import java.util.HexFormat

internal object VanillaDepthOperationSource {
    private const val WATER_VERTEX_HASH = "c4de81d67342c8b335d3c87f2e9aa21dd3921e5212c167c08e04383ea6387e11"
    private const val WATER_FRAGMENT_HASH = "8fad78a057ee8aa2b04a39238d3b903bed134e216a768c36f7bf2c399a5ab54a"
    private val tileRoute by lazy {
        checkNotNull(VanillaDepthOperationSource::class.java.getResourceAsStream(
            "/assets/illusion/shaders/oit/tile_route.hlsl")) { "Missing OIT tile route shader" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun buildWaterMask(shader: D3D12CompiledShader, pipeline: RenderPipeline, compilerDefines: ShaderDefines,
        vertexSource: String, fragmentSource: String, generation: Long, flipY: Boolean): D3D12OpaqueSeedCompilation {
        if (pipeline !== RenderPipelines.OIT_WATER_MASK) return D3D12OpaqueSeedCompilation.NotApplicable
        val directives = compilerDefines.asSourceDirectives() + pipeline.shaderDefines.asSourceDirectives()
        val depth = pipeline.depthStencilState
        val bindings = pipeline.vertexFormatBindings
        val expectedEntries = setOf(
            D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, "DynamicTransforms", null),
            D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, "Projection", null),
        )
        if (generation <= 0 || compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE") || !pipeline.shaderDefines.isEmpty ||
            sourceHash(vertexSource, directives) != WATER_VERTEX_HASH ||
            sourceHash(fragmentSource, directives) != WATER_FRAGMENT_HASH ||
            pipeline.shaders.keys != setOf(ShaderType.VERTEX, ShaderType.FRAGMENT) ||
            pipeline.shaders.values.any { it.toString() != "minecraft:core/rendertype_water_mask" } ||
            pipeline.primitiveTopology != PrimitiveTopology.QUADS || pipeline.polygonMode != PolygonMode.FILL ||
            !pipeline.isCull || pipeline.colorTargetStates.any { it != null } || pipeline.pushConstantSize() != 0 ||
            depth == null || depth.depthTest() != CompareOp.GREATER_THAN_OR_EQUAL || !depth.writeDepth() ||
            depth.depthBiasScaleFactor() != 0f || depth.depthBiasConstant() != 0f || depth.stencilTest() != null ||
            bindings.firstOrNull() !== DefaultVertexFormat.POSITION || bindings.drop(1).any { it != null } ||
            shader.entries.size != expectedEntries.size || shader.entries.toSet() != expectedEntries ||
            shader.rootConstants != null || shader.oitCapture != null || shader.vertexInputs.size != 1 ||
            shader.vertexInputs.single().let { it.name != "Position" || it.location != 0 || it.hlslType != "float3" })
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.SOURCE_CONTRACT)

        val source = shader.vertexHlsl
        val entry = Regex("(?m)^([A-Za-z_][A-Za-z_0-9]*) main\\(float3 ([A-Za-z_][A-Za-z_0-9]*) : LOC0\\)\\s*\\{")
            .findAll(source).singleOrNull()
            ?: return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val resultType = entry.groupValues[1]
        val positionInput = entry.groupValues[2]
        val output = Regex("\\bstruct ${Regex.escape(resultType)}\\s*\\{\\s*float4 " +
            "([A-Za-z_][A-Za-z_0-9]*) : SV_Position;\\s*};").find(source)
            ?: return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        if (Regex("\\b(?:SV_VertexID|SV_InstanceID|SV_Depth\\w*|SV_Coverage|SV_SampleIndex|" +
                "SV_ViewportArrayIndex|SV_RenderTargetArrayIndex|RW\\w*|Interlocked\\w*)\\b|space3",
                RegexOption.IGNORE_CASE).containsMatchIn(source + shader.fragmentHlsl))
            return D3D12OpaqueSeedCompilation.Unsupported(D3D12OitTileUnsupported.STAGE_SIGNATURE)
        val positionOutput = output.groupValues[1]
        val renamed = source.replaceRange(entry.range,
            entry.value.replaceFirst(" main(", " McdrWaterOriginalVertexV1("))
        val vertex = renamed + "\n" + tileRoute + "\n" + """
            struct McdrWaterTileVertexV1 {
                float4 position : SV_Position;
                uint viewport : SV_ViewportArrayIndex;
                uint slice : SV_RenderTargetArrayIndex;
            };
            McdrWaterTileVertexV1 main(float3 $positionInput : LOC0) {
                $resultType original = McdrWaterOriginalVertexV1($positionInput);
                McdrWaterTileVertexV1 output = (McdrWaterTileVertexV1)0;
                output.position = original.$positionOutput;
                output.viewport = McdrTileSlot;
                output.slice = McdrTileSlot;
                return output;
            }
        """.trimIndent()
        val fragment = "void main() {}\n"
        val evaluation = D3D12OitTileVertexEvaluation(renamed + "\n" + """
            float4 McdrTileEvaluateClipV1(float3 $positionInput) {
                return McdrWaterOriginalVertexV1($positionInput).$positionOutput;
            }
        """.trimIndent(), listOf(D3D12OitTileVertexParameter(positionInput, "float3", "LOC0")))
        val contract = listOf("illusion.oit-water-mask-tile/1", WATER_VERTEX_HASH, WATER_FRAGMENT_HASH,
            directives, generation, flipY, vertex, fragment).joinToString("\n")
        return D3D12OpaqueSeedCompilation.Candidate(D3D12OpaqueSeedProgram(generation, shaderHash(contract),
            vertex, fragment, evaluation, flipY))
    }

    private fun sourceHash(source: String, directives: String): String? {
        val normalized = source.replace("\r\n", "\n")
        val firstLine = normalized.indexOf('\n')
        if (firstLine < 0 || !normalized.startsWith("#version ") ||
            !normalized.startsWith(directives, firstLine + 1)) return null
        return shaderHash(normalized.removeRange(firstLine + 1, firstLine + 1 + directives.length))
    }

    private fun shaderHash(source: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)))
}
