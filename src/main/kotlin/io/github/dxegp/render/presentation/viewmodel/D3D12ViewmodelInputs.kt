package io.github.dxegp.render.presentation.viewmodel

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.frame.vanilla.VanillaCamera
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frame.vanilla.VanillaPerspective
import io.github.dxegp.render.temporal.D3D12RasterMotion
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import io.github.dxegp.render.temporal.D3D12Temporal
import org.joml.Matrix4fc
import kotlin.math.abs
import kotlin.math.atan

internal class ViewmodelFrameInputs(
    val frame: VanillaFrameInputs,
    val currentCamera: VanillaCamera,
    val previousCamera: VanillaCamera?,
    val depth: D3D12GpuTexture,
    val motion: D3D12GpuTexture,
    val coverage: D3D12GpuTexture,
    val previousValid: Boolean,
    val motionValid: Boolean,
    val mixedSurface: Boolean,
)

internal object D3D12ViewmodelInputs : AutoCloseable {
    private data class ProjectionHistory(val frame: VanillaFrameInputs, val player: Long, val camera: VanillaCamera)
    private data class Recording(val frame: VanillaFrameInputs, val player: Long,
        val camera: VanillaCamera, val previousCamera: VanillaCamera?, val depth: D3D12GpuTexture)

    private var owner: D3D12CommandEncoderBackend? = null
    private var depthCopy: D3D12GpuTexture? = null
    private var previous: ProjectionHistory? = null
    private var stagedPresented: ProjectionHistory? = null
    private var recording: Recording? = null
    private var candidate: ViewmodelFrameInputs? = null
    private var candidatePlayer = 0L
    private var committedFrame = 0L

    fun needsCapture(frame: VanillaFrameInputs): Boolean =
        frame.settings.frameGenerationBackend != 0 && frame.settings.frameGenerationMode != 0

    fun begin(frame: VanillaFrameInputs, target: RenderTarget, projection: Matrix4fc) {
        begin(frame, target, projection,
            checkNotNull(target.getDepthTexture() as? D3D12GpuTexture) { "Viewmodel target has no D3D12 depth" },
            RenderSystem.getModelViewMatrixCopy())
    }

    fun begin(frame: VanillaFrameInputs, target: RenderTarget, projection: Matrix4fc,
        depth: D3D12GpuTexture, modelView: Matrix4fc) {
        RenderSystem.assertOnRenderThread()
        check(needsCapture(frame)) { "Viewmodel capture has no frozen frame-generation consumer" }
        check(recording == null) { "Viewmodel inputs already active" }
        candidate = null
        val color = checkNotNull(target.getColorTexture() as? D3D12GpuTexture) { "Viewmodel target has no D3D12 color" }
        check(color.getWidth(0) == frame.displayWidth && color.getHeight(0) == frame.displayHeight &&
            depth.getWidth(0) == frame.displayWidth && depth.getHeight(0) == frame.displayHeight) {
            "Viewmodel attachments differ from frozen display extent"
        }
        val projectionValues = projection.get(FloatArray(16))
        val modelViewValues = modelView.get(FloatArray(16))
        require(projectionValues.all(Float::isFinite) && modelViewValues.all(Float::isFinite))
        require(abs(projection.m23() + 1f) < 0.0001f && abs(projection.m33()) < 0.0001f &&
            projection.m11() > 0f && projection.m22() >= 0f && projection.m32() > 0f) {
            "Viewmodel projection is not the actual reversed-Z perspective"
        }
        val farPlane = if (projection.m22() > 0f) projection.m32() / projection.m22() else Float.POSITIVE_INFINITY
        val camera = VanillaCamera(frame.current.originX, frame.current.originY, frame.current.originZ,
            frame.current.yaw, frame.current.pitch, false, 0.0, 0.0,
            VanillaPerspective(projection.m32() / (projection.m22() + 1f),
                farPlane, 2f * atan(1f / projection.m11())),
            projectionValues, projectionValues, modelViewValues)
        val player = D3D12ViewmodelMotionCapture.playerIdentity()
        val old = previous?.takeIf {
            player != 0L && it.player == player && it.frame.world === frame.world && frame.previous != null &&
                it.frame.historyGeneration == frame.historyGeneration &&
                it.frame.identity.realFrameId + 1L == frame.identity.realFrameId &&
                it.frame.identity.worldEpoch == frame.identity.worldEpoch &&
                it.frame.identity.configVersion == frame.identity.configVersion &&
                it.frame.identity.resourceVersion == frame.identity.resourceVersion &&
                it.frame.displayWidth == frame.displayWidth && it.frame.displayHeight == frame.displayHeight
        }
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Viewmodel capture has no command encoder" }
        check(owner == null || owner === encoder) { "Viewmodel capture changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        D3D12ViewmodelMotionCapture.begin()
        D3D12RasterMotionCapture.beginViewmodelFrame(frame, old != null)
        try {
            D3D12RasterMotion.beginViewmodel(encoder, frame.identity, color.handle(), depth.handle(),
                frame.displayWidth, frame.displayHeight,
                old?.camera?.let { D3D12Temporal.multiply(it.projection(), it.viewRotation()) })
            recording = Recording(frame, player, camera, old?.camera, depth)
        } catch (failure: Throwable) {
            try { invalidate() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun end() {
        RenderSystem.assertOnRenderThread()
        val active = checkNotNull(recording) { "Viewmodel inputs are not active" }
        val encoder = checkNotNull(owner)
        try {
            D3D12RasterMotion.end(encoder)
            D3D12RasterMotionCapture.endViewmodelFrame()
            if (!D3D12ViewmodelMotionCapture.hasSources()) {
                invalidate()
                return
            }
            val existing = depthCopy
            val depth = if (existing != null && existing.format == active.depth.format &&
                existing.getWidth(0) == active.frame.displayWidth && existing.getHeight(0) == active.frame.displayHeight) {
                existing
            } else {
                D3D12GpuTexture(GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                    "Viewmodel recorded depth", active.depth.format,
                    active.frame.displayWidth, active.frame.displayHeight, 1, 1).also {
                    depthCopy = it
                    existing?.close()
                }
            }
            encoder.recordCommands { context ->
                VanillaFrameCoordinator.declaredPass(context, 0x30008,
                    VanillaFrameInputResources.textureCopy(active.depth.handle(), depth.handle(),
                        active.frame.identity.resourceVersion), graphicsEffects = 0) {
                    encoder.copyTextureToTexture(active.depth, depth, 0, 0, 0, 0, 0,
                        active.frame.displayWidth, active.frame.displayHeight)
                }
            }
            val guides = D3D12ViewmodelGuideOutputs.record(encoder, active.frame,
                checkNotNull(D3D12RasterMotion.viewmodelView()), depth)
            candidate = ViewmodelFrameInputs(active.frame, active.camera, active.previousCamera,
                depth, guides.motion, guides.coverage, active.previousCamera != null,
                active.previousCamera != null && D3D12RasterMotionCapture.viewmodelMotionComplete() &&
                    D3D12ViewmodelMotionCapture.allSourcesSupported(),
                D3D12ViewmodelMotionCapture.hasMixedSurface())
            candidatePlayer = active.player
        } catch (failure: Throwable) {
            try { invalidate() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            recording = null
        }
    }

    fun current(): ViewmodelFrameInputs? = candidate?.takeIf { it.frame === VanillaFrameCoordinator.currentFrame() }

    fun stagePresented(frame: VanillaFrameInputs) {
        val value = candidate ?: run { invalidate(); return }
        check(recording == null && value.frame === frame) { "Viewmodel frame changed before commit" }
        if (committedFrame == frame.identity.realFrameId) return
        check(stagedPresented == null) { "Viewmodel presentation has not settled" }
        stagedPresented = ProjectionHistory(frame, candidatePlayer, value.currentCamera)
        committedFrame = frame.identity.realFrameId
    }

    fun publishPresented(frame: VanillaFrameInputs) {
        val staged = stagedPresented ?: return
        check(staged.frame === frame) { "Viewmodel presentation result belongs to another frame" }
        previous = staged
        stagedPresented = null
        candidate = null
        D3D12RasterMotionCapture.commitViewmodelFrame()
    }

    fun invalidate() {
        try {
            if (recording != null) owner?.let(D3D12RasterMotion::end)
        } finally {
            recording = null
            candidate = null
            previous = null
            stagedPresented = null
            candidatePlayer = 0L
            committedFrame = 0L
            D3D12RasterMotionCapture.invalidateViewmodelFrame()
        }
    }

    override fun close() {
        invalidate()
        depthCopy?.close()
        depthCopy = null
        owner = null
    }
}
