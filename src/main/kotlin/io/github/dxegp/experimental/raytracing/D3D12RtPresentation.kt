package io.github.dxegp.experimental.raytracing

import io.github.dxegp.RtDebugView
import io.github.dxegp.d3d12.render.adapter.D3D12Device
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture
import io.github.dxegp.d3d12.render.core.D3D12GpuTextureView
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.renderer.LevelTargetBundle

object D3D12RtPresentation {
    private var blitTries = 0
    private var blitFails = 0

    fun blit(targets: LevelTargetBundle?) {
        val bundle = targets ?: return
        if (D3D12RtFrame.currentCamera() == null) return
        val encoder = D3D12Device.sharedCommandEncoder()
        val main = try {
            bundle.main.get()
        } catch (_: Throwable) {
            return
        }
        val targetView = main.colorTextureView as? D3D12GpuTextureView ?: return
        val destination = targetView.texture() as D3D12GpuTexture
        val view = D3D12Settings.rtDebugView
        val compositeMode = when (view) {
            RtDebugView.NONE.ordinal -> 0
            RtDebugView.DIRECT.ordinal -> 1
            RtDebugView.HIT_DISTANCE.ordinal -> 2
            RtDebugView.COVERAGE.ordinal -> 3
            RtDebugView.INDIRECT.ordinal -> 4
            RtDebugView.SHADOW.ordinal -> 5
            RtDebugView.COMPOSITE_MARK.ordinal -> 6
            RtDebugView.PRIMARY_VALIDITY.ordinal -> 7
            RtDebugView.DIRECT_VALIDITY.ordinal -> 8
            RtDebugView.DIFFUSE_VALIDITY.ordinal -> 9
            RtDebugView.SPECULAR_VALIDITY.ordinal -> 10
            else -> null
        }
        if (compositeMode != null) {
            val image = if (compositeMode == 0) D3D12RtFrame.composite(encoder, destination)
                else D3D12RtFrame.composite(encoder, destination, compositeMode)
            if (image != null && sameSize(image, destination)) {
                val activeEncoder = requireNotNull(encoder)
                val copied = D3D12RtFrame.blitComposite(activeEncoder, image, destination)
                val reportResult = view == RtDebugView.NONE.ordinal || view == RtDebugView.COMPOSITE_MARK.ordinal
                if (!reportResult || copied) D3D12RtStats.mark(activeEncoder, D3D12RtStats.SLOT_BLIT)
                if (reportResult) noteBlit(copied, image)
            } else if (view == RtDebugView.NONE.ordinal) {
                noteBlit(false, image)
            }
            return
        }
        if (view == RtDebugView.ROUGHNESS.ordinal || view == RtDebugView.NORMAL.ordinal) {
            val mode = if (view == RtDebugView.ROUGHNESS.ordinal) 3 else 4
            val packed = D3D12RtFrame.debugChannel(
                encoder, D3D12RtGBuffer.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS), mode,
                destination.getWidth(0), destination.getHeight(0)
            )
            if (packed != null && sameSize(packed, destination) && requireNotNull(encoder).recordTextureBlit(packed, destination)) {
                D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_BLIT)
            }
            return
        }
        if (view == RtDebugView.BLAS.ordinal) {
            val image = D3D12RtFrame.composite(encoder, destination) ?: return
            val activeEncoder = requireNotNull(encoder)
            val drawn = D3D12RtFrame.debugBlas(encoder, image) ?: image
            if (D3D12RtFrame.blitComposite(activeEncoder, drawn, destination)) {
                D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_BLIT)
            }
            return
        }
        val source = D3D12RtGBuffer.debugTexture() ?: return
        if (D3D12RtGBuffer.blit(requireNotNull(encoder), source, destination)) {
            D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_BLIT)
        }
    }

    private fun sameSize(source: D3D12GpuTexture, destination: D3D12GpuTexture): Boolean =
        source.getWidth(0) == destination.getWidth(0) && source.getHeight(0) == destination.getHeight(0)

    private fun sameSize(source: D3D12RtFrame.CompositeImage, destination: D3D12GpuTexture): Boolean =
        source.width == destination.getWidth(0) && source.height == destination.getHeight(0)

    private fun noteBlit(copied: Boolean, source: D3D12RtFrame.CompositeImage?) {
        blitTries++
        if (copied) return
        blitFails++
        if (blitFails > 3) return
        val size = source?.let { "${it.width}x${it.height}" } ?: "none"
        IllusionLogger.warnMessage("rt blit: $blitTries tries, $blitFails fail, last $size")
    }
}
