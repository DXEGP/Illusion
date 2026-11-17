package io.github.dxegp.render.presentation.display

import com.mojang.blaze3d.systems.RenderSystem
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.render.frame.vanilla.PreparedBinding
import io.github.dxegp.render.frame.vanilla.VanillaCamera
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.presentation.D3D12GUISplit
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.presentation.viewmodel.D3D12ViewmodelInputs
import io.github.dxegp.render.reconstruction.ReconstructionFrameInputs
import io.github.dxegp.render.temporal.D3D12Velocity
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import io.github.dxegp.render.temporal.D3D12ParticleMotionCapture
import org.joml.Matrix4f

internal object DisplayFrameSubmission {
    private var prepared: DisplayFrameInputs? = null

    fun prepare(frame: VanillaFrameInputs) {
        RenderSystem.assertOnRenderThread()
        check(prepared == null) { "Display frame was prepared before the previous frame retired" }
        if (frame.settings.frameGenerationMode == 0 || frame.settings.frameGenerationBackend == 0) {
            return
        }
        val scene = checkNotNull(D3D12SceneDisplay.sceneTexture()) { "Display submission has no sealed scene" }
        val depth = checkNotNull(ReconstructionFrameInputs.capturedDepth()) { "Display submission has no world depth" }
        val motion = D3D12Velocity.currentHandle().takeIf { it != 0L }?.let(::PreparedBinding)
        val coverage = D3D12Velocity.currentCoverageHandle().takeIf { it != 0L }?.let(::PreparedBinding)
        val current = canonicalCamera(frame.current, frame)
        val previous = frame.previous?.let { canonicalCamera(it, frame) }
        val transparency = WorldTransparencyCapture.current(frame)
        val rasterMotionComplete = D3D12RasterMotionCapture.worldMotionComplete()
        val particleMotionComplete = D3D12ParticleMotionCapture.opaqueMotionComplete(frame)
        val worldGuides = DisplayWorldGuides(transparency, when {
            !rasterMotionComplete -> DisplayWorldGuideAvailability.RASTER_MOTION_UNAVAILABLE
            !particleMotionComplete -> DisplayWorldGuideAvailability.PARTICLE_MOTION_UNAVAILABLE
            previous == null -> DisplayWorldGuideAvailability.PREVIOUS_CAMERA_UNAVAILABLE
            motion == null -> DisplayWorldGuideAvailability.MOTION_UNAVAILABLE
            coverage == null -> DisplayWorldGuideAvailability.COVERAGE_UNAVAILABLE
            else -> DisplayWorldGuideAvailability.COMPLETE
        })
        val layers = buildList {
            add(DisplayLayerInputs(DisplayLayerKind.WORLD, DisplayLayerComposition.DEPTH_TESTED,
                frame.identity.width, frame.identity.height, PreparedBinding(depth.handle()), motion, coverage,
                frame.current, frame.previous, worldGuides.motionValid, transparency.blendedDraws || transparency.oitDraws))
            D3D12ViewmodelInputs.current()?.let { hand ->
                check(hand.frame === frame) { "Viewmodel inputs belong to another display frame" }
                add(DisplayLayerInputs(DisplayLayerKind.VIEWMODEL, DisplayLayerComposition.OVERLAY_AFTER_WORLD,
                    hand.depth.getWidth(0), hand.depth.getHeight(0), PreparedBinding(hand.depth.handle()),
                    PreparedBinding(hand.motion.handle()), PreparedBinding(hand.coverage.handle()),
                    hand.currentCamera, hand.previousCamera, hand.motionValid, hand.mixedSurface))
            }
        }
        val posts = D3D12SceneDisplay.postEffects()
        val inputs = DisplayFrameInputs(frame, PreparedBinding(scene.handle()),
            current, previous, if (previous != null) frame.identity.realFrameId - 1L else 0L,
            layers, posts, worldGuides, (if (previous == null) 1 else 0) or
                (if (motion == null || coverage == null) 2 else 0))
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Display submission has no command encoder" }
        val result = encoder.recordCommands { DisplayFrameWire.prepare(it, inputs) }
        check(result >= 0) { "Display guide preparation failed: ${MCDR_ABI.D3D12LastError()}" }
        prepared = inputs
    }

    fun submit(frame: VanillaFrameInputs) {
        RenderSystem.assertOnRenderThread()
        if (frame.settings.frameGenerationMode == 0 || frame.settings.frameGenerationBackend == 0) {
            D3D12ViewmodelInputs.stagePresented(frame)
            return
        }
        val pending = checkNotNull(prepared) { "Display submission preceded guide preparation" }
        check(pending.frame === frame) { "Display guides belong to another frame" }
        val ui = checkNotNull(D3D12GUISplit.uiTextureForFrame()) { "Display submission preceded GUI completion" }
        val uiInputs = DisplayUiInputs(PreparedBinding(ui.handle()), D3D12GUISplit.guiContainsSceneBackdrop())
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Display submission has no command encoder" }
        try {
            val result = encoder.recordCommands { DisplayFrameWire.submit(it, pending, uiInputs) }
            check(result >= 0) { "Display frame submission failed: ${MCDR_ABI.D3D12LastError()}" }
            if (result == 0 && IllusionLogger.debugEnabled()) {
                val reason = if (uiInputs.containsScene) "GUI contains the scene backdrop"
                    else pending.worldGuides.skipReason ?: "display camera, post mapping or pixel motion is unavailable"
                IllusionLogger.debugMessage("FG skipped realFrame=${frame.identity.realFrameId}: $reason")
            }
            prepared = null
            D3D12ViewmodelInputs.stagePresented(frame)
        } catch (failure: Throwable) {
            D3D12ViewmodelInputs.invalidate()
            throw failure
        }
    }

    fun discardFrame() {
        prepared = null
    }

    private fun canonicalCamera(camera: VanillaCamera, frame: VanillaFrameInputs): VanillaCamera {
        val lens = checkNotNull(camera.perspective) { "Display frame has no perspective camera" }
        val projection = Matrix4f().setPerspective(lens.verticalFovRadians,
            frame.displayWidth.toFloat() / frame.displayHeight, lens.farPlane, lens.nearPlane, true)
            .get(FloatArray(16))
        return VanillaCamera(camera.originX, camera.originY, camera.originZ, camera.yaw, camera.pitch,
            false, 0.0, 0.0, lens, projection, projection, camera.viewRotation())
    }
}
