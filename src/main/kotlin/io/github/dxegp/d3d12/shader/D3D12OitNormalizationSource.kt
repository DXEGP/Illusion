package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendOp
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.PolygonMode
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import net.minecraft.client.renderer.ShaderDefines
import java.security.MessageDigest
import java.util.HexFormat

private const val NORMALIZATION_CERTIFICATION_VERSION = 1

enum class D3D12OitNormalizationUnsupported {
    GENERATION, COMPILER_DEFINES, PIPELINE_DEFINES, COEFFICIENT_LAYOUT, SOURCE_CONTRACT,
    RASTER_STATE, DEPTH_STATE, TARGET_STATE, BLEND_STATE, BINDINGS, ROOT_CONSTANTS,
    COMPILED_STAGE, ORIENTATION, CLOSED_PIPELINE,
}

sealed interface D3D12OitNormalizationCertification {
    data class Certified(
        val generation: Long,
        val targetCount: Int,
        val fragmentSourceHash: String,
        val vertexSourceHash: String,
        val contractIdentity: String,
    ) : D3D12OitNormalizationCertification {
        val certificationVersion: Int = NORMALIZATION_CERTIFICATION_VERSION
        private val fragmentHashBytes = HexFormat.of().parseHex(fragmentSourceHash)
        private val vertexHashBytes = HexFormat.of().parseHex(vertexSourceHash)
        private val contractHashBytes = MessageDigest.getInstance("SHA-256")
            .digest(contractIdentity.toByteArray(Charsets.UTF_8))

        init {
            require(fragmentHashBytes.size == 32 && vertexHashBytes.size == 32)
        }

        fun fragmentSourceHashBytes(): ByteArray = fragmentHashBytes.copyOf()
        fun vertexSourceHashBytes(): ByteArray = vertexHashBytes.copyOf()
        fun contractHashBytes(): ByteArray = contractHashBytes.copyOf()
    }

    data class Unsupported(val reason: D3D12OitNormalizationUnsupported) : D3D12OitNormalizationCertification

    data object NotApplicable : D3D12OitNormalizationCertification
}

internal object D3D12OitNormalizationSource {
    private const val COMPOSITE_FRAGMENT = "minecraft:core/oit_composite"
    private const val SCREEN_QUAD_VERTEX = "minecraft:core/screenquad"
    private const val COMPOSITE_FRAGMENT_HASH =
        "A893373690C865287A7DABC9A1E8BBC2E7A41B6AE6BA82BB7F3C43695CA54131"
    private const val SCREEN_QUAD_VERTEX_HASH =
        "B47FF78239A84617B6B90246F9D6164516D492B7E3ABBE391B02221B41AEE68C"
    private const val CONTRACT_SCHEMA = "minecraft-oit-composite-normalization/$NORMALIZATION_CERTIFICATION_VERSION"
    private val valueNames = setOf("OIT_WAVELET_RANK", "OIT_COEFF_COUNT", "OIT_COEFF_ATTACHMENT_COUNT")

    fun certify(
        pipeline: RenderPipeline,
        compilerDefines: ShaderDefines,
        vertexSource: String,
        fragmentSource: String,
        compiled: D3D12CompiledShader,
        generation: Long,
        flipY: Boolean,
    ): D3D12OitNormalizationCertification {
        val fragment = pipeline.shaders[ShaderType.FRAGMENT]?.toString()
        if (fragment != COMPOSITE_FRAGMENT) return D3D12OitNormalizationCertification.NotApplicable
        val directives = compilerDefines.asSourceDirectives() + pipeline.shaderDefines.asSourceDirectives()
        val actualFragmentHash = sourceHash(fragmentSource, directives)
        fun unsupported(reason: D3D12OitNormalizationUnsupported) = D3D12OitNormalizationCertification.Unsupported(reason)
        if (generation <= 0L) return unsupported(D3D12OitNormalizationUnsupported.GENERATION)
        if (flipY) return unsupported(D3D12OitNormalizationUnsupported.ORIENTATION)
        if (compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")) {
            return unsupported(D3D12OitNormalizationUnsupported.COMPILER_DEFINES)
        }
        val defines = pipeline.shaderDefines
        if (defines.flags() != setOf("OIT") || defines.values().keys != valueNames) {
            return unsupported(D3D12OitNormalizationUnsupported.PIPELINE_DEFINES)
        }
        val coefficients = defines.values()["OIT_COEFF_COUNT"]?.toIntOrNull()
            ?: return unsupported(D3D12OitNormalizationUnsupported.COEFFICIENT_LAYOUT)
        val rank = defines.values()["OIT_WAVELET_RANK"]?.toIntOrNull()
            ?: return unsupported(D3D12OitNormalizationUnsupported.COEFFICIENT_LAYOUT)
        val attachments = defines.values()["OIT_COEFF_ATTACHMENT_COUNT"]?.toIntOrNull()
            ?: return unsupported(D3D12OitNormalizationUnsupported.COEFFICIENT_LAYOUT)
        if (coefficients !in setOf(4, 8, 16) || coefficients % 4 != 0 ||
            rank !in 1..3 || coefficients != 1.shl(rank + 1) || attachments != coefficients / 4) {
            return unsupported(D3D12OitNormalizationUnsupported.COEFFICIENT_LAYOUT)
        }
        if (pipeline.primitiveTopology != PrimitiveTopology.TRIANGLES || pipeline.polygonMode != PolygonMode.FILL || !pipeline.isCull ||
            pipeline.vertexFormatBindings.any { it != null }) return unsupported(D3D12OitNormalizationUnsupported.RASTER_STATE)
        val depth = pipeline.depthStencilState
        if (depth == null || depth.depthTest() != CompareOp.ALWAYS_PASS || !depth.writeDepth() ||
            depth.depthBiasScaleFactor() != 0f || depth.depthBiasConstant() != 0f || depth.stencilTest() != null) {
            return unsupported(D3D12OitNormalizationUnsupported.DEPTH_STATE)
        }
        val target = pipeline.colorTargetStates.singleOrNull()
            ?: return unsupported(D3D12OitNormalizationUnsupported.TARGET_STATE)
        if (target.format() != GpuFormat.RGBA8_UNORM || target.writeMask() != 15) {
            return unsupported(D3D12OitNormalizationUnsupported.TARGET_STATE)
        }
        val blend = target.blendFunction().orElse(null)
            ?: return unsupported(D3D12OitNormalizationUnsupported.BLEND_STATE)
        if (blend.color().sourceFactor() != BlendFactor.ONE ||
            blend.color().destFactor() != BlendFactor.ONE_MINUS_SRC_ALPHA ||
            blend.color().op() != BlendOp.ADD || blend.alpha().sourceFactor() != BlendFactor.ONE ||
            blend.alpha().destFactor() != BlendFactor.ONE_MINUS_SRC_ALPHA || blend.alpha().op() != BlendOp.ADD) {
            return unsupported(D3D12OitNormalizationUnsupported.BLEND_STATE)
        }
        if (pipeline.shaders.keys != setOf(ShaderType.VERTEX, ShaderType.FRAGMENT) ||
            pipeline.shaders[ShaderType.VERTEX]?.toString() != SCREEN_QUAD_VERTEX) {
            return unsupported(D3D12OitNormalizationUnsupported.SOURCE_CONTRACT)
        }
        val actualVertexHash = sourceHash(vertexSource, directives)
        if (actualFragmentHash != COMPOSITE_FRAGMENT_HASH || actualVertexHash != SCREEN_QUAD_VERTEX_HASH) {
            return unsupported(D3D12OitNormalizationUnsupported.SOURCE_CONTRACT)
        }
        if (compiled.vertexHlsl.isBlank() || compiled.fragmentHlsl.isBlank()) {
            return unsupported(D3D12OitNormalizationUnsupported.COMPILED_STAGE)
        }
        if (pipeline.pushConstantSize() != 0 || compiled.rootConstants != null) {
            return unsupported(D3D12OitNormalizationUnsupported.ROOT_CONSTANTS)
        }
        val expectedTextures = setOf("Sampler0", "DepthBoundsSampler") + (0 until attachments).map { "Coeff$it" }
        val uniforms = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts)
        if (uniforms.size != expectedTextures.size + 2 ||
            uniforms.map { it.name() }.toSet() != expectedTextures + setOf("Globals", "Projection") || uniforms.any { uniform ->
                when (uniform.name()) {
                    "Globals", "Projection" -> uniform.type() != UniformType.UNIFORM_BUFFER
                    in expectedTextures -> uniform.type() != UniformType.COMBINED_IMAGE_SAMPLER
                    else -> true
                }
            } || compiled.entries.size != expectedTextures.size + 2 ||
            compiled.entries.filter { it.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER }.map { it.name }.toSet() != setOf("Globals", "Projection") ||
            compiled.entries.filter { it.type == D3D12BindGroupEntry.Type.SAMPLED_IMAGE }.map { it.name }.toSet() != expectedTextures ||
            compiled.entries.any { it.type == D3D12BindGroupEntry.Type.TEXEL_BUFFER ||
                it.rawBuffer || it.texelBufferFormat != null ||
                (it.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER && it.name !in setOf("Globals", "Projection")) }) {
            return unsupported(D3D12OitNormalizationUnsupported.BINDINGS)
        }
        val identity = "$CONTRACT_SCHEMA/$actualFragmentHash/$actualVertexHash/$coefficients/$rank/$attachments"
        return D3D12OitNormalizationCertification.Certified(
            generation, coefficients / 4, actualFragmentHash, actualVertexHash, identity,
        )
    }

    private fun sourceHash(source: String, directives: String): String? {
        val normalized = source.replace("\r\n", "\n")
        val firstLine = normalized.indexOf('\n')
        if (firstLine < 0 || !normalized.startsWith("#version ") ||
            !normalized.startsWith(directives, firstLine + 1)) return null
        val canonical = normalized.substring(0, firstLine + 1) + normalized.substring(firstLine + 1 + directives.length)
        return HexFormat.of().withUpperCase().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)),
        )
    }
}
