package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendOp
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.ShaderDefines
import io.github.dxegp.d3d12.ffi.OitShaderPreludeV1
import java.security.MessageDigest
import java.util.HexFormat

enum class D3D12OitCaptureProfile { TERRAIN, CLOUD, PARTICLE, ENTITY, ITEM, BORDER, LINE }

enum class D3D12OitReplayStage(val shaderDefine: String) {
    DEPTH_BOUNDS("OIT_DEPTH_BOUNDS"),
    TRANSMITTANCE("OIT_TRANSMITTANCE"),
    ACCUMULATE("OIT_ACCUMULATE"),
}

internal data class D3D12OitReplayCertification(
    val profile: D3D12OitCaptureProfile,
    val stage: D3D12OitReplayStage,
)

internal enum class D3D12OitPreviousDomain(val shaderDefine: String) {
    GEOMETRY("MCDR_OIT_PREVIOUS_GEOMETRY"),
    MATERIAL_UV("MCDR_OIT_PREVIOUS_MATERIAL_UV"),
}

internal val D3D12OitCaptureProfile.previousDomain: D3D12OitPreviousDomain
    get() = when (this) {
        D3D12OitCaptureProfile.CLOUD, D3D12OitCaptureProfile.LINE -> D3D12OitPreviousDomain.GEOMETRY
        else -> D3D12OitPreviousDomain.MATERIAL_UV
    }

data class D3D12OitCaptureShader(
    val vertexHlsl: String,
    val fragmentHlsl: String,
    val terrainFragmentHlsl: String,
    val shaderAbiVersion: Int,
    val shaderContractHash: Long,
    val programGeneration: Long,
    val profile: D3D12OitCaptureProfile,
    val prelude: String,
)

data class D3D12OitCaptureIdentity(
    val shaderAbiVersion: Int,
    val shaderContractHash: Long,
    val programGeneration: Long,
    val profile: D3D12OitCaptureProfile,
) {
    init {
        require(shaderAbiVersion > 0 && shaderContractHash != 0L && programGeneration > 0L)
    }
}

internal val D3D12OitCaptureShader.identity: D3D12OitCaptureIdentity
    get() = D3D12OitCaptureIdentity(shaderAbiVersion, shaderContractHash, programGeneration, profile)

internal data class D3D12OitCaptureContract(
    val profile: D3D12OitCaptureProfile,
    val prelude: OitShaderPreludeV1,
    val generation: Long,
)

internal object D3D12OitCaptureSource {
    const val SCHEMA = "illusion.oit-capture-shader/5"
    const val SHADER_ABI_VERSION = OitShaderPreludeV1.SUPPORTED_SHADER_ABI
    private val sources = mapOf(
        "875EAF6F4D3005F6689D6A6953B71559DAE6B8D779A1C62F279C7FFCB4840481" to D3D12OitCaptureProfile.TERRAIN,
        "E02F80CFD807738DF4522F663B8DE1F40C0894E8E832132804CB905F6E9DE9FD" to D3D12OitCaptureProfile.CLOUD,
        "846B63D1FE7CD797FDFDA6C3C7BBBB9ADB36776C57EB35F927C129047AA60A85" to D3D12OitCaptureProfile.PARTICLE,
        "BBA84340024DD74F98897BFE65ABF47758C1903B4A459F9BEE61A43C0B351DB7" to D3D12OitCaptureProfile.ENTITY,
        "9776E32310CA2E011C103771FBE4F560587D31C9B3A3C7989623D7847E4E3FBB" to D3D12OitCaptureProfile.ITEM,
        "33F0ACBE276406DEF7B7944808E99967D685131D150FD125C0622EF4D6D2EBAF" to D3D12OitCaptureProfile.BORDER,
        "5FA91EFE173FBBE0E1582829874C142F6C6BE164647BD6F9ED5B2DACE11C5707" to D3D12OitCaptureProfile.LINE,
    )
    private val vertexSources = mapOf(
        "18AE94084B67114079E0466A2A08D72F84DD5EC582C610303FDE7EBE1BEB02F7" to D3D12OitCaptureProfile.ENTITY,
        "23A5D0A0F6C1E13B9923504633F469051FC1F20B3FC8208E259A2B5C8696E510" to D3D12OitCaptureProfile.ITEM,
        "CD09F26F5C5A8EE599B33AFCB89606A3376E6BDA4DB8FE753F8339DE85B9D0BE" to D3D12OitCaptureProfile.PARTICLE,
    )
    private val replayVertexSources = vertexSources + mapOf(
        "CFF82357A94634820DFB9EABA3CC271A8B832F2D3CFDEEA87ECCB85227B0F230" to D3D12OitCaptureProfile.TERRAIN,
        "871F9181EC451065F0845A3232855715199257387DAAA958B7E8019F18D76A32" to D3D12OitCaptureProfile.CLOUD,
        "D11E7D7CF584858FFCD3774144EAFC9C8A9D0E090F700536B9850237E11CDD31" to D3D12OitCaptureProfile.BORDER,
        "9A7BDF774A772A286C0B928A2B8A8A1E39C92B417BFE3A2CEC9EEEB23F79FC35" to D3D12OitCaptureProfile.LINE,
    )
    private val flags = setOf("OIT", "OIT_ACCUMULATE", "OIT_ADDITIVE", "MULTIDRAW_TERRAIN",
        "GLINT", "GLINT_SPECIAL", "PER_FACE_LIGHTING", "EMISSIVE", "NO_OVERLAY", "DISSOLVE",
        "NO_CARDINAL_LIGHTING", "APPLY_TEXTURE_MATRIX")
    private val values = setOf("ALPHA_CUTOUT", "OIT_COEFF_COUNT", "OIT_WAVELET_RANK", "OIT_COEFF_ATTACHMENT_COUNT")
    private val number = Regex("[0-9]+(?:\\.[0-9]+)?[fF]?")

    fun certifyReplayStage(source: String, pipeline: RenderPipeline, compilerDefines: ShaderDefines,
        vertexSource: String): D3D12OitReplayCertification? {
        val defines = pipeline.shaderDefines
        val stage = D3D12OitReplayStage.entries.singleOrNull { it.shaderDefine in defines.flags() } ?: return null
        val replayFlags = flags + setOf("OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS", "OIT_TRANSMITTANCE")
        if ("OIT" !in defines.flags() || defines.flags().any { it !in replayFlags } ||
            defines.values().any { (name, value) -> name !in values || !number.matches(value) } ||
            compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")) return null
        val coefficients = defines.values()["OIT_COEFF_COUNT"]?.toIntOrNull() ?: return null
        val rank = defines.values()["OIT_WAVELET_RANK"]?.toIntOrNull() ?: return null
        val attachments = defines.values()["OIT_COEFF_ATTACHMENT_COUNT"]?.toIntOrNull() ?: return null
        if (coefficients !in setOf(4, 8, 16) || rank !in 1..3 ||
            coefficients != 1.shl(rank + 1) || attachments != coefficients / 4) return null
        val directives = compilerDefines.asSourceDirectives() + defines.asSourceDirectives()
        val profile = sourceHash(source, directives)?.let(sources::get) ?: return null
        val stageBody = when (stage) {
            D3D12OitReplayStage.DEPTH_BOUNDS -> "calculateDepthBounds"
            D3D12OitReplayStage.TRANSMITTANCE -> "addTransmittance"
            D3D12OitReplayStage.ACCUMULATE -> "sampleColorForAccumulation"
        }
        if (!Regex("\\b${Regex.escape(stageBody)}\\s*\\(").containsMatchIn(source)) return null
        if (sourceHash(vertexSource, directives)?.let(replayVertexSources::get) != profile) return null
        if (stage != D3D12OitReplayStage.DEPTH_BOUNDS && pipeline.depthStencilState?.writeDepth() != false) return null
        return D3D12OitReplayCertification(profile, stage)
    }

    fun certify(source: String, pipeline: RenderPipeline, compilerDefines: ShaderDefines,
        vertexSource: String): D3D12OitCaptureProfile? {
        val defines = pipeline.shaderDefines
        if ("OIT_ACCUMULATE" !in defines.flags() || "OIT" !in defines.flags() ||
            defines.flags().any { it !in flags } || defines.values().any { (name, value) ->
                name !in values || !number.matches(value)
            }) return null
        if (compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")) return null
        if (pipeline.depthStencilState?.writeDepth() != false) return null
        val target = pipeline.colorTargetStates.filterNotNull().singleOrNull() ?: return null
        val blend = target.blendFunction().orElse(null) ?: return null
        if (target.writeMask() != 15 || blend.color().sourceFactor() != BlendFactor.ONE ||
            blend.color().destFactor() != BlendFactor.ONE || blend.color().op() != BlendOp.ADD ||
            blend.alpha().sourceFactor() != BlendFactor.ONE || blend.alpha().destFactor() != BlendFactor.ONE ||
            blend.alpha().op() != BlendOp.ADD) return null
        val coefficients = defines.values()["OIT_COEFF_COUNT"]?.toIntOrNull() ?: return null
        val rank = defines.values()["OIT_WAVELET_RANK"]?.toIntOrNull() ?: return null
        val attachments = defines.values()["OIT_COEFF_ATTACHMENT_COUNT"]?.toIntOrNull() ?: return null
        if (coefficients !in setOf(4, 8, 16) || rank !in 1..3 ||
            coefficients != 1.shl(rank + 1) || attachments != coefficients / 4) return null
        val directives = compilerDefines.asSourceDirectives() + defines.asSourceDirectives()
        val fragmentHash = sourceHash(source, directives) ?: return null
        val profile = sources[fragmentHash] ?: return null
        if (profile == D3D12OitCaptureProfile.ENTITY || profile == D3D12OitCaptureProfile.ITEM ||
            profile == D3D12OitCaptureProfile.PARTICLE) {
            val vertexHash = sourceHash(vertexSource, directives) ?: return null
            if (vertexSources[vertexHash] != profile) return null
        }
        return profile
    }

    private fun sourceHash(source: String, directives: String): String? {
        val normalized = source.replace("\r\n", "\n")
        if (!normalized.startsWith("#version ")) return null
        val firstLine = normalized.indexOf('\n')
        if (firstLine < 0 || !normalized.startsWith(directives, firstLine + 1)) return null
        val original = normalized.substring(0, firstLine + 1) + normalized.substring(firstLine + 1 + directives.length)
        return HexFormat.of().withUpperCase().formatHex(
            MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8)))
    }

    fun instrument(source: String, profile: D3D12OitCaptureProfile): String {
        val normalized = source.replace("\r\n", "\n")
        val header = Regex("(?m)^#extension[^\\n]*\\n").findAll(normalized).lastOrNull()
        checkNotNull(header) { "Certified OIT shader has no extension header" }
        val declarations = """
            layout(location = 1) out vec4 IllusionOitMaterial;
            layout(location = 2) out vec4 IllusionOitSample;
            layout(location = 3) out vec4 IllusionOitTransport;
            layout(location = 4) out vec4 IllusionOitUv;
            layout(location = 5) out vec4 IllusionOitDerivatives;
            layout(location = 6) out vec4 IllusionOitFogColor;
        """.trimIndent() + "\n"
        var result = normalized.substring(0, header.range.last + 1) + declarations +
            normalized.substring(header.range.last + 1)
        val main = "void main() {\n"
        check(Regex(Regex.escape(main)).findAll(result).count() == 1) { "Certified OIT main changed" }
        val visibility = if (profile == D3D12OitCaptureProfile.TERRAIN) "chunkVisibility" else "1.0"
        val materialUv = profile.previousDomain == D3D12OitPreviousDomain.MATERIAL_UV
        val uv = if (materialUv) "texCoord0" else "vec2(0.0)"
        val derivatives = if (materialUv) "vec4(dFdx(texCoord0), dFdy(texCoord0))" else "vec4(0.0)"
        val fogColor = if (profile == D3D12OitCaptureProfile.BORDER) "vec4(0.0)" else "FogColor"
        result = result.replace(main, main + "    IllusionOitTransport = vec4(0.0, 0.0, 0.0, $visibility);\n" +
            "    IllusionOitUv = vec4($uv, 0.0, 0.0);\n" +
            "    IllusionOitDerivatives = $derivatives;\n" +
            "    IllusionOitFogColor = $fogColor;\n")
        val sample = "    return vec4(color.rgb * color.a, accumAlpha) * sampledTransmittance;"
        check(Regex(Regex.escape(sample)).findAll(result).count() == 1) { "Certified OIT sample changed" }
        val material = if (profile == D3D12OitCaptureProfile.TERRAIN) "" else "    IllusionOitMaterial = color;\n"
        result = result.replace(sample, material + "    IllusionOitSample = color;\n" +
            "    IllusionOitTransport.x = sampledTransmittance;\n" +
            "    IllusionOitTransport.y = accumAlpha;\n" + sample)
        if (profile == D3D12OitCaptureProfile.TERRAIN) {
            val chunk = "    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);"
            check(Regex(Regex.escape(chunk)).findAll(result).count() == 1) { "Certified terrain visibility changed" }
            result = result.replace(chunk, "    IllusionOitMaterial = color;\n$chunk")
        }
        if (profile != D3D12OitCaptureProfile.BORDER) {
            val fog = "    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);"
            check(Regex(Regex.escape(fog)).findAll(result).count() == 1) { "Certified OIT fog changed" }
            result = result.replace(fog, "    IllusionOitTransport.z = fogValue * fogColor.a;\n$fog")
        }
        return result
    }
}
