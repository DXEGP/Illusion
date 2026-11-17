package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional

internal enum class OitNormalizationResourceKind { CONSTANT_BUFFER, SAMPLED_TEXTURE, STORAGE_TEXTURE }

internal data class OitNormalizationBinding(val kind: OitNormalizationResourceKind, val register: Int)

internal data class OitTileNormalizationRecipe(
    val targetCount: Int,
    val laneEntry: String,
    val averageEntry: String,
    val laneResources: List<OitNormalizationBinding>,
    val averageResources: List<OitNormalizationBinding>,
) {
    init {
        require(targetCount in setOf(1, 2, 4))
        require(laneEntry == "NormalizeOitLane" && averageEntry == "AverageNormalizedOitLanes")
        require(laneResources == buildList {
            add(OitNormalizationBinding(OitNormalizationResourceKind.CONSTANT_BUFFER, 0))
            for (register in 0..targetCount)
                add(OitNormalizationBinding(OitNormalizationResourceKind.SAMPLED_TEXTURE, register))
            add(OitNormalizationBinding(OitNormalizationResourceKind.STORAGE_TEXTURE, 0))
        })
        require(averageResources == listOf(
            OitNormalizationBinding(OitNormalizationResourceKind.CONSTANT_BUFFER, 0),
            OitNormalizationBinding(OitNormalizationResourceKind.SAMPLED_TEXTURE, 5),
            OitNormalizationBinding(OitNormalizationResourceKind.STORAGE_TEXTURE, 1),
        ))
    }
}

internal object OitTileNormalizationPrograms {
    private const val RESOURCE = "/assets/illusion/shaders/oit/tile_normalization.hlsl"
    private val source: String by lazy {
        checkNotNull(javaClass.getResourceAsStream(RESOURCE)) { "Missing OIT normalization shader" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    val normalizedCompositePipeline: RenderPipeline = RenderPipeline.builder(RenderPipelines.OIT_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("dxr_mc", "pipeline/oit_normalized_composite"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("dxr_mc", "core/oit_normalized_composite"))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("NormalizedTransparencySampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("DepthBoundsSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withColorTargetState(ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
            GpuFormat.RGBA8_UNORM, 15))
        .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, true))
        .withPushConstantSize(8)
        .build()

    fun recipe(targetCount: Int): OitTileNormalizationRecipe = OitTileNormalizationRecipe(
        targetCount,
        "NormalizeOitLane",
        "AverageNormalizedOitLanes",
        buildList {
            add(OitNormalizationBinding(OitNormalizationResourceKind.CONSTANT_BUFFER, 0))
            for (register in 0..targetCount)
                add(OitNormalizationBinding(OitNormalizationResourceKind.SAMPLED_TEXTURE, register))
            add(OitNormalizationBinding(OitNormalizationResourceKind.STORAGE_TEXTURE, 0))
        },
        listOf(OitNormalizationBinding(OitNormalizationResourceKind.CONSTANT_BUFFER, 0),
            OitNormalizationBinding(OitNormalizationResourceKind.SAMPLED_TEXTURE, 5),
            OitNormalizationBinding(OitNormalizationResourceKind.STORAGE_TEXTURE, 1)),
    )

    fun packBatch(regions: List<OitTerrainTileRegion>, laneIndex: Int, outputWidth: Int, outputHeight: Int): ByteArray {
        require(regions.size in 1..16 && laneIndex in 0..3 && outputWidth > 0 && outputHeight > 0)
        require(regions.all { it.originX <= outputWidth - it.width && it.originY <= outputHeight - it.height })
        for (index in regions.indices) {
            val region = regions[index]
            require(regions.take(index).none { previous ->
                previous.originX < region.originX + region.width && region.originX < previous.originX + previous.width &&
                    previous.originY < region.originY + region.height && region.originY < previous.originY + previous.height
            })
        }
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1).putInt(regions.size).putInt(laneIndex).putInt(0)
        buffer.putInt(outputWidth).putInt(outputHeight).putInt(0).putInt(0)
        for (region in regions)
            buffer.putInt(region.originX).putInt(region.originY).putInt(region.width).putInt(region.height)
        return buffer.array()
    }

    fun compileLane(targetCount: Int): ByteArray = compile(targetCount, "NormalizeOitLane")

    fun compileAverage(targetCount: Int): ByteArray = compile(targetCount, "AverageNormalizedOitLanes")

    private fun compile(targetCount: Int, entry: String): ByteArray {
        require(targetCount in setOf(1, 2, 4))
        return checkNotNull(MCDR_ABI.D3D12CompileShaderDxc(
            "#define MCDR_OIT_TARGET_COUNT $targetCount\n$source", entry, "cs_6_9")) {
            "OIT normalization $targetCount/$entry compilation failed: ${MCDR_ABI.D3D12LastError()}"
        }
    }
}
