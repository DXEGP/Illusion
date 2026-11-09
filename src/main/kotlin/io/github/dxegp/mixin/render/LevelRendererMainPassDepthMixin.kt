package io.github.dxegp.mixin.render

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.render.advanced.taa.D3D12TAA
import io.github.dxegp.experimental.superresolution.dlss.DeepLearningSuperSampling
import io.github.dxegp.d3d12.render.core.D3D12GpuTextureView
import io.github.dxegp.experimental.superresolution.D3D12SrMask
import net.minecraft.client.renderer.LevelTargetBundle
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelRenderer::class)
abstract class LevelRendererMainPassDepthMixin {
    @Shadow @Final
    private var targets: LevelTargetBundle? = null

    @Inject(method = ["lambda\$addMainPass\$0"], at = [At("RETURN")], remap = false)
    private fun `dxr_mc$dlssDepthSnapshotAfterMainPass`(
        slice: GpuBufferSlice?, firstFlag: Boolean, sections: ChunkSectionsToRender?,
        frame: FeatureRenderDispatcher.PreparedFrame?, secondFlag: Boolean, thirdFlag: Boolean,
        callback: CallbackInfo
    ) {
        D3D12TAA.captureTemporalData()
        DeepLearningSuperSampling.captureMotionVectors()
        DeepLearningSuperSampling.captureDepthSnapshot()
        `dxr_mc$captureTransmittance`()
    }

    private fun `dxr_mc$captureTransmittance`() {
        val handles = targets?.transmittance ?: return
        if (handles.isEmpty()) return
        val views = buildList {
            for (handle in handles) {
                try {
                    val view = handle.get()?.colorTextureView as? D3D12GpuTextureView
                    if (view != null) add(view)
                } catch (_: Throwable) {
                }
            }
        }
        if (views.isNotEmpty()) D3D12SrMask.captureTransmittance(views.toTypedArray())
    }
}

