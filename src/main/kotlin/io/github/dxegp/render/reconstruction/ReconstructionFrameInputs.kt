package io.github.dxegp.render.reconstruction

import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import net.minecraft.client.Minecraft
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.ReconstructionTemporalInputs
import io.github.dxegp.render.temporal.D3D12Velocity
import io.github.dxegp.render.temporal.D3D12RasterMotion
import io.github.dxegp.render.temporal.OpaqueMotionInputs
import io.github.dxegp.render.presentation.D3D12SceneDisplay

internal class OpaqueReconstructionInputs(
    val frame: VanillaFrameInputs,
    val color: D3D12GpuTexture,
    val depth: D3D12GpuTexture,
    val motion: OpaqueMotionInputs,
)

internal object ReconstructionFrameInputs : AutoCloseable {
    private enum class DepthSource { COMPLETE_WORLD, DISPLAY_WORLD }
    private data class Capture(val frame: VanillaFrameInputs, val depth: D3D12GpuTexture,
        val source: DepthSource = DepthSource.COMPLETE_WORLD)
    private class OpaqueAttachments(val color: D3D12GpuTexture, val depth: D3D12GpuTexture) : AutoCloseable {
        override fun close() {
            try { color.close() } finally { depth.close() }
        }
    }

    private var owner: D3D12CommandEncoderBackend? = null
    private var depth: D3D12GpuTexture? = null
    private var snapshot: Capture? = null
    private var temporalDepth: D3D12GpuTexture? = null
    private var temporalMotion: D3D12GpuTexture? = null
    private var temporalInputs: ReconstructionTemporalInputs? = null
    private var opaqueAttachments: OpaqueAttachments? = null
    private var opaqueInputs: OpaqueReconstructionInputs? = null

    private fun retainCommandOwner(encoder: D3D12CommandEncoderBackend) {
        check(owner == null || owner === encoder) { "Reconstruction inputs changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
    }

    fun captureOpaque(encoder: D3D12CommandEncoderBackend, minecraft: Minecraft): OpaqueReconstructionInputs? {
        val frame = VanillaFrameCoordinator.currentFrame() ?: return null
        opaqueInputs?.takeIf { it.frame === frame }?.let { return it }
        val source = minecraft.gameRenderer.mainRenderTarget()
        val color = source.colorTexture as? D3D12GpuTexture ?: return null
        val depth = source.depthTexture as? D3D12GpuTexture ?: return null
        if (color.isClosed() || depth.isClosed()) return null
        val width = frame.identity.width
        val height = frame.identity.height
        if (color.getWidth(0) != width || color.getHeight(0) != height ||
            depth.getWidth(0) != width || depth.getHeight(0) != height) return null
        retainCommandOwner(encoder)
        opaqueInputs = null
        val existing = opaqueAttachments
        val targets = if (existing != null && !existing.color.isClosed() && !existing.depth.isClosed() &&
            existing.color.format == color.format && existing.depth.format == depth.format &&
            existing.color.getWidth(0) == width && existing.color.getHeight(0) == height) existing
        else {
            val usage = GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT
            val copiedColor = D3D12GpuTexture(usage, "Opaque reconstruction color", color.format, width, height, 1, 1)
            val copiedDepth = try {
                D3D12GpuTexture(usage, "Opaque reconstruction depth", depth.format, width, height, 1, 1)
            } catch (failure: Throwable) {
                try { copiedColor.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            OpaqueAttachments(copiedColor, copiedDepth).also {
                opaqueAttachments = it
                existing?.close()
            }
        }
        val motion = encoder.recordCommands { context ->
            val version = frame.identity.resourceVersion
            val uses = VanillaFrameInputResources.textureCopy(color.handle(), targets.color.handle(), version) +
                VanillaFrameInputResources.textureCopy(depth.handle(), targets.depth.handle(), version)
            VanillaFrameCoordinator.declaredPass(context, VanillaFrameInputResources.OPAQUE_CAPTURE_PASS,
                uses, graphicsEffects = 0) {
                encoder.copyTextureToTexture(color, targets.color, 0, 0, 0, 0, 0, width, height)
                encoder.copyTextureToTexture(depth, targets.depth, 0, 0, 0, 0, 0, width, height)
            }
            D3D12Velocity.recordOpaque(context, encoder.commandDevice(), targets.depth, D3D12RasterMotion.view())
        } ?: return null
        return OpaqueReconstructionInputs(frame, targets.color, targets.depth, motion).also { opaqueInputs = it }
    }

    fun capture(encoder: D3D12CommandEncoderBackend, minecraft: Minecraft) {
        io.github.dxegp.render.vrs.D3D12ShadingRateImage.captureWorldDepth(encoder)
        if (capturedDepth() != null) return
        snapshot = null
        val frame = VanillaFrameCoordinator.currentFrame() ?: return
        retainCommandOwner(encoder)
        val source = minecraft.gameRenderer.mainRenderTarget().depthTexture as? D3D12GpuTexture ?: return
        if (source.isClosed()) return
        val width = source.getWidth(0)
        val height = source.getHeight(0)
        if (width != frame.identity.width || height != frame.identity.height) return
        val existing = depth
        val target = if (existing != null && !existing.isClosed() && existing.format == source.format &&
            existing.getWidth(0) == width && existing.getHeight(0) == height
        ) {
            existing
        } else {
            val created = D3D12GpuTexture(
                GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                "Reconstruction world depth", source.format, width, height, 1, 1,
            )
            depth = created
            existing?.close()
            created
        }
        val identity = frame.identity
        encoder.recordCommands { ctx ->
            VanillaFrameCoordinator.declaredPass(ctx, VanillaFrameInputResources.DEPTH_CAPTURE_PASS,
                VanillaFrameInputResources.textureCopy(source.handle(), target.handle(), identity.resourceVersion),
                graphicsEffects = 0) {
                encoder.copyTextureToTexture(source, target, 0, 0, 0, 0, 0, width, height)
            }
        }
        snapshot = Capture(frame, target)
    }

    fun capturedDepth(): D3D12GpuTexture? = snapshot?.takeIf {
        it.frame === VanillaFrameCoordinator.currentFrame() && !it.depth.isClosed()
    }?.depth

    fun capturedOpaque(frame: VanillaFrameInputs): OpaqueReconstructionInputs? = opaqueInputs?.takeIf {
        it.frame === frame && !it.color.isClosed() && !it.depth.isClosed() && !it.motion.motion.isClosed()
    }

    fun captureDisplayWorldDepth(encoder: D3D12CommandEncoderBackend, frame: VanillaFrameInputs, source: RenderTarget) {
        check(frame === VanillaFrameCoordinator.currentFrame()) { "Display world depth belongs to another frame" }
        if (snapshot?.let { it.frame === frame && it.source == DepthSource.DISPLAY_WORLD } == true) return
        val sourceDepth = checkNotNull(source.getDepthTexture() as? D3D12GpuTexture) {
            "Display world depth is unavailable"
        }
        check(!sourceDepth.isClosed() && sourceDepth.getWidth(0) == frame.displayWidth &&
            sourceDepth.getHeight(0) == frame.displayHeight) { "Display world depth differs from frozen display extent" }
        retainCommandOwner(encoder)
        val existing = depth
        val target = if (existing != null && !existing.isClosed() && existing.format == sourceDepth.format &&
            existing.getWidth(0) == frame.identity.width && existing.getHeight(0) == frame.identity.height &&
            (existing.usage() and GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) existing
        else D3D12GpuTexture(GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
            "Display world depth at render extent", sourceDepth.format,
            frame.identity.width, frame.identity.height, 1, 1).also {
            depth = it
            existing?.close()
        }
        temporalInputs = null
        snapshot = null
        io.github.dxegp.d3d12.resource.D3D12GpuTextureView(target, 0, 1).use { destination ->
            val mapping = if (D3D12SceneDisplay.displayIsUnjittered(frame))
                D3D12SceneDisplay.DepthMapping.DISPLAY_TO_RASTER else D3D12SceneDisplay.DepthMapping.SAME_GRID
            D3D12SceneDisplay.recordDepthTransfer(encoder, frame, sourceDepth, destination, mapping)
        }
        snapshot = Capture(frame, target, DepthSource.DISPLAY_WORLD)
    }

    fun recordTemporalInputs(encoder: D3D12CommandEncoderBackend, frame: VanillaFrameInputs): ReconstructionTemporalInputs? {
        if (frame.settings.reconstructionBackend == io.github.dxegp.d3d12.ffi.MCDR_ABI.SR_BACKEND_NONE &&
            frame.settings.antiAliasing != 2) return null
        temporalInputs?.takeIf { it.frame === frame }?.let { return it }
        check(owner === encoder && snapshot?.frame === frame) { "SR pair has no captured world frame" }
        val baseDepth = checkNotNull(capturedDepth())
        val baseMotion = D3D12Velocity.currentTexture() ?: return null
        val width = frame.identity.width
        val height = frame.identity.height
        if (temporalDepth?.let { !it.isClosed() && it.getWidth(0) == width && it.getHeight(0) == height } != true) {
            val usage = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING
            val depth = D3D12GpuTexture(usage, "SR temporal depth", GpuFormat.D32_FLOAT, width, height, 1, 1)
            val motion = try {
                D3D12GpuTexture(usage, "SR temporal motion", GpuFormat.RG16_FLOAT, width, height, 1, 1)
            } catch (failure: Throwable) {
                try { depth.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            val previous = listOfNotNull(temporalDepth, temporalMotion)
            temporalDepth = depth
            temporalMotion = motion
            var failure: Throwable? = null
            for (resource in previous) {
                try { resource.close() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            failure?.let { throw it }
        }
        return OitReconstructionInputs.recordResolve(encoder, frame, baseDepth, baseMotion,
            checkNotNull(temporalDepth), checkNotNull(temporalMotion)).also { temporalInputs = it }
    }

    fun invalidate() {
        snapshot = null
        temporalInputs = null
        opaqueInputs = null
    }

    override fun close() {
        invalidate()
        val resources = listOfNotNull(depth, temporalDepth, temporalMotion, opaqueAttachments)
        depth = null
        temporalDepth = null
        temporalMotion = null
        opaqueAttachments = null
        owner = null
        var failure: Throwable? = null
        for (resource in resources) {
            try { resource.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}
