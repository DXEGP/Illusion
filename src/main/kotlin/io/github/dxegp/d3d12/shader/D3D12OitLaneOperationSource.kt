package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.PolygonMode
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import java.security.MessageDigest
import java.util.HexFormat
import net.minecraft.client.renderer.ShaderDefines

class D3D12OitLaneOperationCertification internal constructor(
    internal val kind: Int,
    internal val generation: Long,
    internal val projectionBinding: Int,
)

internal object D3D12OitLaneOperationSource {
    private const val SCREEN_QUAD_HASH = "b47ff78239a84617b6b90246f9d6164516d492b7e3abbe391b02221b41aee68c"
    private const val BLIT_DEPTH_HASH = "9250fd8bb71bed7e031c79e6813dec675747f1af917077a3ce1149cf5c3e78d7"
    private const val BLIT_BOUNDS_HASH = "8d01499c27529b99c95d1fc9f2e5e319ccc144c03992b37745e785e8a91e0e25"
    private const val EXPANDED_CULL_HASH = "3b95874f7d19491d7a27ed9230d325ebbe4182b5dd64ab0f30d2771bfcdad36f"
    private val cullDefines = mapOf("OIT_WAVELET_RANK" to "2", "OIT_COEFF_COUNT" to "8", "OIT_COEFF_ATTACHMENT_COUNT" to "2")

    private enum class Pipeline(
        val abiKind: Int,
        val fragmentId: String,
        val fragmentHash: String,
        val colorMask: Int?,
        val depthCompare: CompareOp?,
    ) {
        DEPTH_COPY(1, "minecraft:core/blit_depth", BLIT_DEPTH_HASH, null, CompareOp.ALWAYS_PASS),
        CLOUD_DEPTH_COPY(1, "minecraft:core/blit_depth", BLIT_DEPTH_HASH, 0, CompareOp.ALWAYS_PASS),
        BOUNDS_COPY(2, "minecraft:core/blit_screen", BLIT_BOUNDS_HASH, 15, null),
        CULL_BOUNDS(3, "minecraft:core/oit_depth_bounds_cull", EXPANDED_CULL_HASH, 15, CompareOp.GREATER_THAN_OR_EQUAL),
    }

    fun certify(pipeline: RenderPipeline, compilerDefines: ShaderDefines, vertexSource: String, fragmentSource: String,
        compiled: D3D12CompiledShader, generation: Long, flipY: Boolean): D3D12OitLaneOperationCertification? {
        val operation = when (pipeline.location.toString()) {
            "minecraft:pipeline/blit_depth" -> Pipeline.DEPTH_COPY
            "minecraft:pipeline/blit_depth_during_depth_bounds" -> Pipeline.CLOUD_DEPTH_COPY
            "minecraft:pipeline/blit_depth_bounds" -> Pipeline.BOUNDS_COPY
            "minecraft:pipeline/oit_depth_bounds_cull" -> Pipeline.CULL_BOUNDS
            else -> return null
        }
        if (flipY || generation <= 0 || compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")) return null
        val cullBounds = operation == Pipeline.CULL_BOUNDS
        val defines = pipeline.shaderDefines
        if (cullBounds) {
            if (defines.flags() != setOf("OIT") || defines.values() != cullDefines) return null
        } else if (!defines.isEmpty) return null
        if (pipeline.shaders.keys != setOf(ShaderType.VERTEX, ShaderType.FRAGMENT) ||
            pipeline.shaders[ShaderType.VERTEX]?.toString() != "minecraft:core/screenquad" ||
            pipeline.shaders[ShaderType.FRAGMENT]?.toString() != operation.fragmentId ||
            pipeline.primitiveTopology != PrimitiveTopology.TRIANGLES || pipeline.polygonMode != PolygonMode.FILL ||
            !pipeline.isCull || pipeline.vertexFormatBindings.any { it != null } || pipeline.pushConstantSize() != 0 ||
            compiled.vertexInputs.isNotEmpty() || compiled.rootConstants != null ||
            compiled.vertexHlsl.isBlank() || compiled.fragmentHlsl.isBlank()) return null
        val depth = pipeline.depthStencilState
        if (operation.depthCompare == null) {
            if (depth != null) return null
        } else if (depth == null || depth.depthTest() != operation.depthCompare || !depth.writeDepth() ||
            depth.depthBiasScaleFactor() != 0f || depth.depthBiasConstant() != 0f || depth.stencilTest() != null) return null
        val targets = pipeline.colorTargetStates
        val target = targets.firstOrNull()
        if (targets.drop(1).any { it != null }) return null
        if (operation.colorMask == null) {
            if (target != null) return null
        } else if (target == null || target.format() != GpuFormat.RGBA32_FLOAT ||
            target.writeMask() != operation.colorMask || target.blendFunction().isPresent) return null

        val sampler = if (cullBounds) "DepthBoundsSampler" else "InSampler"
        val uniforms = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts)
        val expectedUniforms = buildMap {
            put("Globals", UniformType.UNIFORM_BUFFER)
            put(sampler, UniformType.COMBINED_IMAGE_SAMPLER)
            if (cullBounds) put("Projection", UniformType.UNIFORM_BUFFER)
        }
        if (uniforms.size != expectedUniforms.size || uniforms.associate { it.name() to it.type() } != expectedUniforms)
            return null
        val expectedBindings = buildSet {
            add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.SAMPLED_IMAGE, sampler, null))
            if (cullBounds) add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, "Projection", null))
        }
        if (compiled.entries.size != expectedBindings.size || compiled.entries.toSet() != expectedBindings) return null
        val directives = compilerDefines.asSourceDirectives() + defines.asSourceDirectives()
        if (sourceHash(vertexSource, directives) != SCREEN_QUAD_HASH ||
            sourceHash(fragmentSource, directives) != operation.fragmentHash) return null
        val projection = compiled.entries.indexOfFirst { it.name == "Projection" }
        return D3D12OitLaneOperationCertification(operation.abiKind, generation, projection)
    }

    private fun sourceHash(source: String, directives: String): String? {
        val normalized = source.replace("\r\n", "\n")
        val firstLine = normalized.indexOf('\n')
        if (firstLine < 0 || !normalized.startsWith("#version ") ||
            !normalized.startsWith(directives, firstLine + 1)) return null
        val canonical = normalized.removeRange(firstLine + 1, firstLine + 1 + directives.length)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }
}
