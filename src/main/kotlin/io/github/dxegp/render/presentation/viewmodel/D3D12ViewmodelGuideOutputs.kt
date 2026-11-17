package io.github.dxegp.render.presentation.viewmodel

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs

internal object D3D12ViewmodelGuideOutputs : AutoCloseable {
    data class Outputs(val motion: D3D12GpuTexture, val coverage: D3D12GpuTexture)

    private var owner: D3D12CommandEncoderBackend? = null
    private var motion: D3D12GpuTexture? = null
    private var coverage: D3D12GpuTexture? = null
    private var depthView: D3D12GpuTextureView? = null
    private var depthHandle = 0L
    private var pipeline = 0L

    fun record(encoder: D3D12CommandEncoderBackend, frame: VanillaFrameInputs,
        raster: D3D12GpuTextureView, depth: D3D12GpuTexture): Outputs {
        check(owner == null || owner === encoder) { "Viewmodel guides changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val width = depth.getWidth(0)
        val height = depth.getHeight(0)
        val existing = motion
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            motion?.close()
            coverage?.close()
            val usage = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING
            motion = D3D12GpuTexture(usage, "Viewmodel motion", GpuFormat.RG16_FLOAT, width, height, 1, 1)
            coverage = D3D12GpuTexture(usage, "Viewmodel coverage", GpuFormat.R8_UNORM, width, height, 1, 1)
        }
        if (depthHandle != depth.handle()) {
            depthView?.close()
            depthView = D3D12GpuTextureView(depth, 0, 1)
            depthHandle = depth.handle()
        }
        if (pipeline == 0L) {
            val descriptor = MCDRPipelineDesc().shaders(VERTEX, FRAGMENT).topology(4, false, 0)
                .addColorTarget(MCDRPipelineDesc.ColorTarget().format(GpuFormat.RG16_FLOAT.ordinal).writeMask(3))
                .addColorTarget(MCDRPipelineDesc.ColorTarget().format(GpuFormat.R8_UNORM.ordinal).writeMask(1))
                .addBinding(1, 0).addBinding(1, 1)
            pipeline = MCDR_ABI.D3D12CreateGraphicsPipeline(descriptor)
            check(pipeline != 0L) { "Viewmodel guide pipeline failed: ${MCDR_ABI.D3D12LastError()}" }
        }
        val outputs = Outputs(checkNotNull(motion), checkNotNull(coverage))
        val rasterTexture = raster.texture() as D3D12GpuTexture
        val version = frame.identity.resourceVersion
        val uses = listOf(
            VanillaFrameInputResources.texture(rasterTexture.handle(), version, 1,
                VanillaFrameInputResources.STATE_SHADER_RESOURCE),
            VanillaFrameInputResources.texture(depth.handle(), version, 1,
                VanillaFrameInputResources.STATE_SHADER_RESOURCE),
            VanillaFrameInputResources.texture(outputs.motion.handle(), version, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET, VanillaFrameInputResources.STATE_COMMON),
            VanillaFrameInputResources.texture(outputs.coverage.handle(), version, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET, VanillaFrameInputResources.STATE_COMMON),
        )
        encoder.recordCommands { context ->
            VanillaFrameCoordinator.declaredPass(context, 0x30009, uses) {
                MCDR_ABI.D3D12BeginRenderPass(context,
                    longArrayOf(outputs.motion.handle(), outputs.coverage.handle()), intArrayOf(0, 0),
                    byteArrayOf(1, 1), FloatArray(8), 0L, 0, 0.toByte(), 0.0, 0, 0, width, height)
                try {
                    MCDR_ABI.D3D12SetViewport(context, 0, 0, width, height)
                    MCDR_ABI.D3D12SetScissor(context, 0, 0, width, height)
                    check(MCDR_ABI.D3D12SetPipeline(context, pipeline, false)) {
                        "Viewmodel guide pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                    check(MCDR_ABI.D3D12PushDescriptors(context, intArrayOf(1, 1), longArrayOf(0L, 0L),
                        longArrayOf(0L, 0L), longArrayOf(0L, 0L), intArrayOf(0, 0),
                        longArrayOf(raster.handle(), checkNotNull(depthView).handle()))) {
                        "Viewmodel guide binding failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                    check(MCDR_ABI.D3D12Draw(context, 3, 1, 0, 0)) {
                        "Viewmodel guide draw failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                } finally {
                    MCDR_ABI.D3D12EndRenderPass(context)
                }
            }
        }
        return outputs
    }

    override fun close() {
        depthView?.close()
        depthView = null
        depthHandle = 0L
        motion?.close()
        motion = null
        coverage?.close()
        coverage = null
        if (pipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(pipeline)
        pipeline = 0L
        owner = null
    }

    private val VERTEX = """
        float4 main(uint vertexId : SV_VertexID) : SV_Position {
            float2 uv = float2((vertexId << 1) & 2, vertexId & 2);
            return float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
        }
    """.trimIndent()

    private val FRAGMENT = """
        Texture2D<float4> RasterGuide : register(t0);
        Texture2D<float> ViewmodelDepth : register(t1);
        struct Guides {
            float2 motion : SV_Target0;
            float coverage : SV_Target1;
        };
        Guides main(float4 position : SV_Position) {
            uint2 pixel = uint2(position.xy);
            float depth = ViewmodelDepth.Load(int3(pixel, 0));
            float4 raster = RasterGuide.Load(int3(pixel, 0));
            Guides output = (Guides)0;
            bool rasterCovered = all(isfinite(raster)) && raster.w > 0.5;
            bool covered = (isfinite(depth) && depth > 0.0) || rasterCovered;
            bool motionValid = rasterCovered && raster.z >= 0.0 && (depth == 0.0 || depth == raster.z) &&
                all(abs(raster.xy) <= 65504.0);
            uint coverage = covered ? 1u : 0u;
            if (motionValid) {
                output.motion = raster.xy;
                coverage |= 2u;
            }
            output.coverage = float(coverage) / 255.0;
            return output;
        }
    """.trimIndent()
}
