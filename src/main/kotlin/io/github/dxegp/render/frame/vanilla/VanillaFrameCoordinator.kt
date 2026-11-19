package io.github.dxegp.render.frame.vanilla

import com.mojang.blaze3d.systems.RenderSystem
import io.github.dxegp.d3d12.ffi.FrameProgramsException
import io.github.dxegp.d3d12.ffi.FrameProgramsFailure
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.DisplayFrameSettlement
import io.github.dxegp.d3d12.ffi.DisplayPresentOutcome
import io.github.dxegp.d3d12.ffi.DisplaySubmissionState
import io.github.dxegp.diagnostics.D3D12FrameStats
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.render.antialiasing.D3D12TAA
import io.github.dxegp.render.presentation.D3D12GUISplit
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.presentation.LateWorldTargets
import io.github.dxegp.render.presentation.display.DisplayFrameSubmission
import io.github.dxegp.render.presentation.display.WorldTransparencyCapture
import io.github.dxegp.render.presentation.viewmodel.D3D12ViewmodelInputs
import io.github.dxegp.render.reconstruction.D3D12SrMask
import io.github.dxegp.render.reconstruction.dlss.DeepLearningSuperSampling
import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.antialiasing.D3D12PostSharpen
import io.github.dxegp.render.reconstruction.ReconstructionFrameInputs
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.OitOriginalCompositeCall
import io.github.dxegp.render.config.RenderConfiguration
import io.github.dxegp.render.vrs.D3D12ShadingRateImage
import io.github.dxegp.render.temporal.D3D12RasterMotion
import io.github.dxegp.render.temporal.D3D12Temporal
import io.github.dxegp.render.temporal.RecordedLevelProjection
import io.github.dxegp.render.frameprep.FrameInputCoordinates
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.GameRenderState
import org.joml.Vector4f
import com.mojang.blaze3d.pipeline.RenderTarget

object VanillaFrameCoordinator {
    private enum class DisplayFrameRetirement { SUBMITTED, ABORTED }
    private data class PendingPresentedFrame(val frame: VanillaFrameInputs?, val commandFenceValue: Long)

    private const val SCHEMA_VERSION = 2
    private val phaseMachine = VanillaFramePhaseMachine()
    private var observedWorld: Any? = null
    private var worldEpoch = 0L
    private var lastResourceIdentity: Any? = null
    private var lastDepthIdentity: Any? = null
    private var lastDisplayIdentity: Any? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDisplayWidth = 0
    private var lastDisplayHeight = 0
    private var lastResourceWorldEpoch = 0L
    private var realFrameId = 0L
    private var resourceVersion = 0L
    private var hadWorldFrame = false
    private var frameColorHandle = 0L
    private var frameDepthHandle = 0L
    private var frameInputs: VanillaFrameInputs? = null
    private var recordedWorldFrame: VanillaFrameInputs? = null
    private var presentedWorldFrame: VanillaFrameInputs? = null
    private var pendingPresentedFrame: PendingPresentedFrame? = null
    private var consumedPresentSerial = 0L
    private var commandOwner: D3D12CommandEncoderBackend? = null
    private val commandLifetime = AutoCloseable {
        pendingPresentedFrame = null
        recordedWorldFrame = null
        presentedWorldFrame = null
        consumedPresentSerial = 0L
        commandOwner = null
        OitReconstructionInputs.discardPresented()
        D3D12ViewmodelInputs.invalidate()
    }
    private var displayFrameStarted = false
    private var resourceReload: ResourceReload? = null

    internal fun currentFrame(): VanillaFrameInputs? = frameInputs
    internal fun latestRecordedWorldFrame(): VanillaFrameInputs? = recordedWorldFrame?.takeIf {
        it.world === Minecraft.getInstance().level
    }
    internal fun latestPresentedWorldFrame(): VanillaFrameInputs? = presentedWorldFrame?.takeIf {
        it.world === Minecraft.getInstance().level
    }
    internal fun currentIdentity(): VanillaFrameIdentity? = frameInputs?.identity
    internal fun currentResourceVersion(): Long? = frameInputs?.identity?.resourceVersion

    internal fun prepareResourceReload(): ResourceReload {
        RenderSystem.assertOnRenderThread()
        if (!displayFrameStarted) D3D12Device.sharedCommandEncoder()?.let(::settlePreviousDisplayFrame)
        if (resourceReload != null || displayFrameStarted || frameInputs != null ||
            phaseMachine.phase != VanillaFramePhase.IDLE) {
            throw FrameProgramsException(
                FrameProgramsFailure.BUSY, "prepare", null,
                "Shader reload requires a retired display frame")
        }
        return ResourceReload(Math.incrementExact(resourceVersion)).also { resourceReload = it }
    }

    internal class ResourceReload internal constructor(private val nextVersion: Long) : AutoCloseable {
        internal fun publish() {
            resourceVersion = nextVersion
            presentedWorldFrame = null
            OitReconstructionInputs.discardPresented()
            D3D12ViewmodelInputs.invalidate()
            resourceReload = null
        }

        override fun close() {
            if (resourceReload === this) resourceReload = null
        }
    }

    internal fun beginDisplayFrame(state: GameRenderState) {
        check(resourceReload == null) { "Display frame cannot begin during shader publication" }
        check(!displayFrameStarted) { "Display frame started before the previous frame retired" }
        D3D12Device.sharedCommandEncoder()?.let(::settlePreviousDisplayFrame)
        val renderer = Minecraft.getInstance().gameRenderer
        val target = renderer.mainRenderTarget()
        val window = state.windowRenderState
        if (target.width != window.width || target.height != window.height) {
            renderer.resize(window.width, window.height)
        }
        RenderConfiguration.applyPendingAtFrameStart()
        RenderSystem.getDevice().createCommandEncoder()
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Display frame has no command encoder" }
        check(commandOwner == null || commandOwner === encoder) { "Display frames changed command owner" }
        if (commandOwner == null) {
            encoder.retainUntilCommandsClosed(commandLifetime)
            commandOwner = encoder
        }
        val began = try {
            encoder.recordCommands { MCDR_ABI.D3D12BeginDisplayFrame(it) == 1 }
        } catch (failure: Throwable) {
            abortFailedDisplayBegin(encoder, failure)
            throw failure
        }
        if (!began) {
            val detail = MCDR_ABI.D3D12LastError().ifEmpty { "native display frame start failed" }
            val failure = IllegalStateException("Could not begin display frame: $detail")
            abortFailedDisplayBegin(encoder, failure)
            throw failure
        }
        displayFrameStarted = true
        D3D12GUISplit.beginFrame(if (state.shouldRenderLevel) Vector4f(0f, 0f, 0f, 0f) else state.guiRenderState.clearColorOverride)
    }

    private fun abortFailedDisplayBegin(encoder: D3D12CommandEncoderBackend, failure: Throwable) {
        try {
            check(encoder.abortDisplayFrame()) {
                "Could not abort failed display frame: ${MCDR_ABI.D3D12LastError()}"
            }
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
    }

    internal fun isRasterPassActive(): Boolean = when (phaseMachine.phase) {
        VanillaFramePhase.WORLD_STARTED,
        VanillaFramePhase.RECORDING,
        VanillaFramePhase.OPAQUE_READY,
        VanillaFramePhase.OPAQUE_POST,
        VanillaFramePhase.WORLD_READY,
        VanillaFramePhase.WORLD_POST -> true
        VanillaFramePhase.IDLE,
        VanillaFramePhase.GUI_READY,
        VanillaFramePhase.GUI_COMPLETED -> false
    }

    internal fun currentWorldTargetHandles(): VanillaWorldTargetHandles? {
        val frame = phaseMachine.identity ?: return null
        if (frameColorHandle == 0L || frameDepthHandle == 0L) return null
        return VanillaWorldTargetHandles(frameColorHandle, frameDepthHandle, frame.resourceVersion)
    }

    internal fun isCurrentWorldTarget(colorHandle: Long, depthHandle: Long): Boolean {
        val target = currentWorldTargetHandles() ?: return false
        return target.colorHandle == colorHandle && target.depthHandle == depthHandle
    }

    internal fun <T> recordDeclaredPresentationPass(
        pass: Int,
        uses: List<MCDR_ABI.VanillaResourceUse>,
        action: () -> T,
    ): T {
        val encoder = D3D12Device.sharedCommandEncoder()
            ?: return action()
        return encoder.recordCommands { ctx ->
            declaredPass(ctx, pass, uses, action = action)
        }
    }

    internal fun <T> declaredPass(
        ctx: Long,
        pass: Int,
        uses: List<MCDR_ABI.VanillaResourceUse>,
        graphicsEffects: Int = 3,
        action: () -> T,
    ): T {
        if (phaseMachine.identity == null) return action()
        try {
            val token = MCDR_ABI.D3D12VanillaBeginDeclaredPass(ctx, pass, uses, graphicsEffects)
            check(token != 0L) { "Declared pass returned a null token" }
            val result = action()
            check(MCDR_ABI.D3D12VanillaFinishDeclaredPass(ctx, token)) {
                "Declared pass commit failed: ${MCDR_ABI.D3D12LastError()}"
            }
            return result
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    internal fun beginWorldRender(cameraState: CameraRenderState) {
        check(displayFrameStarted) { "World render has no active display frame" }
        check(frameInputs == null && phaseMachine.phase == VanillaFramePhase.IDLE) {
            "World render started before the previous frame finished"
        }
        val minecraft = Minecraft.getInstance()
        val world = checkNotNull(minecraft.level) { "World render has no world" }
        val receipt = checkNotNull(D3D12Temporal.recordedLevelProjection()) {
            "World render has no successfully recorded level projection"
        }
        check(receipt.world === world && receipt.frameTimeNanos == D3D12Temporal.renderFrameTimeNanos &&
            receipt.historyGeneration == D3D12Temporal.historyGeneration) {
            "World render projection belongs to another frame"
        }
        if (world !== observedWorld) {
            if (observedWorld != null) invalidateReconstructionInputs()
            observedWorld = world
            worldEpoch++
            recordedWorldFrame = null
            presentedWorldFrame = null
        }
        val target = minecraft.gameRenderer.mainRenderTarget()
        val color = (target.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: error("World frame requires a D3D12 color attachment")
        val depth = target.depthTexture as? D3D12GpuTexture
            ?: error("World frame requires a D3D12 depth attachment")
        check(!color.isClosed() && !depth.isClosed()) { "World frame attachment is closed" }
        val width = color.getWidth(0)
        val height = color.getHeight(0)
        check(width > 0 && height > 0 && depth.getWidth(0) == width && depth.getHeight(0) == height &&
            receipt.width == width && receipt.height == height) {
            "Recorded projection extent differs from world attachments"
        }
        val display = D3D12SceneDisplay.ensureTarget().getColorTexture() as? D3D12GpuTexture
            ?: error("World frame requires a D3D12 scene display attachment")
        check(!display.isClosed()) { "World frame display attachment is closed" }
        val displayWidth = display.getWidth(0)
        val displayHeight = display.getHeight(0)
        check(displayWidth > 0 && displayHeight > 0) { "World frame display extent is empty" }
        val applied = RenderConfiguration.applied()
        check(applied.version > 0 && applied.renderWidth == width && applied.renderHeight == height &&
            applied.displayWidth == displayWidth && applied.displayHeight == displayHeight) {
            "World attachments differ from applied configuration ${applied.version}"
        }
        val selected = applied.settings
        val settings = VanillaFrameSettings(selected.antiAliasing, selected.srBackend.id,
            applied.renderScalePercent, selected.jitterEnabled, selected.jitterSamples,
            selected.sharpness / 100.0f, selected.providerMasks,
            if (selected.antiAliasing == D3D12AntiAliasing.DLAA) 4 else selected.dlssQuality,
            DeepLearningSuperSampling.renderPreset(selected.dlssPreset), selected.frameGenerationBackend,
            if (!selected.frameGenerationEnabled) 0 else if (selected.frameGenerationFrames == 0) 2 else 1,
            selected.frameGenerationFrames.coerceAtLeast(1), D3D12TAA.BLEND,
            D3D12PostSharpen.taaAmount, D3D12PostSharpen.amount)
        val resourceIdentity = target.colorTextureView
        if (resourceIdentity !== lastResourceIdentity || target.depthTexture !== lastDepthIdentity ||
            display !== lastDisplayIdentity ||
            width != lastWidth || height != lastHeight || displayWidth != lastDisplayWidth ||
            displayHeight != lastDisplayHeight || lastResourceWorldEpoch != worldEpoch) {
            lastResourceIdentity = resourceIdentity
            lastDepthIdentity = target.depthTexture
            lastDisplayIdentity = display
            lastWidth = width
            lastHeight = height
            lastDisplayWidth = displayWidth
            lastDisplayHeight = displayHeight
            lastResourceWorldEpoch = worldEpoch
            resourceVersion = Math.incrementExact(resourceVersion)
        }
        check(realFrameId < Long.MAX_VALUE) { "Real frame sequence exhausted" }
        realFrameId += 1L
        val identity = VanillaFrameIdentity(
            SCHEMA_VERSION,
            worldEpoch,
            realFrameId,
            applied.version,
            resourceVersion.coerceAtLeast(1L),
            width,
            height,
        )
        val view = cameraState.viewRotationMatrix.get(FloatArray(16))
        val baseProjection = cameraState.projectionMatrix.get(FloatArray(16))
        val planes = FrameInputCoordinates.depthPlanes(baseProjection)
        val verticalFov = 2.0 * kotlin.math.atan(1.0 / baseProjection[5].toDouble())
        val perspective = if (!cameraState.isPanoramicMode && planes != null &&
            verticalFov.isFinite() && verticalFov > 0.0 && verticalFov < Math.PI) {
            VanillaPerspective(planes[0], planes[1], verticalFov.toFloat())
        } else null
        val current = VanillaCamera(cameraState.pos.x, cameraState.pos.y, cameraState.pos.z,
            cameraState.yRot, cameraState.xRot, cameraState.isPanoramicMode,
            receipt.jitterX, receipt.jitterY, perspective, receipt.projection(), receipt.rasterProjection(), view)
        val previousFrame = presentedWorldFrame?.takeIf {
            it.world === world && it.identity.realFrameId == identity.realFrameId - 1L &&
                it.identity.worldEpoch == identity.worldEpoch && it.identity.configVersion == identity.configVersion &&
                it.identity.resourceVersion == identity.resourceVersion && it.historyGeneration == receipt.historyGeneration &&
                it.current.panoramic == current.panoramic && (it.current.perspective == null) == (perspective == null)
        }
        val frameDeltaMs = previousFrame?.let {
            ((receipt.frameTimeNanos - it.frameTimeNanos).toDouble() / 1_000_000.0).toFloat()
        } ?: 0.0f
        check(frameDeltaMs.isFinite() && frameDeltaMs >= 0.0f) { "World frame time moved backwards" }
        frameInputs = VanillaFrameInputs(identity, world, receipt.frameTimeNanos, receipt.historyGeneration,
            receipt.jitterPhaseIndex, displayWidth, displayHeight, frameDeltaMs, settings, current, previousFrame?.current)
        frameColorHandle = color.handle()
        frameDepthHandle = depth.handle()
        try {
            check(phaseMachine.begin(identity)) { "Could not begin world frame ${identity.realFrameId}" }
            val inputs = checkNotNull(frameInputs)
            hadWorldFrame = true
            WorldTransparencyCapture.begin(inputs)
            if (inputs.settings.reconstructionBackend == MCDR_ABI.SR_BACKEND_DLSS) {
                DeepLearningSuperSampling.pclFrameBegin()
            }
            recordNativeFrame { ctx ->
                val begun = VanillaFrameWire.begin(ctx, inputs)
                if (begun == 1) MCDR_ABI.D3D12VanillaRegisterWorldDepthV1(ctx, frameDepthHandle) else begun
            }
            D3D12ShadingRateImage.prepareFrame()
            val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
            D3D12RasterMotion.begin(encoder, identity, frameColorHandle, frameDepthHandle)
            OitReconstructionInputs.begin(encoder, inputs)
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    internal fun levelProjectionRecorded(receipt: RecordedLevelProjection) {
        val frame = frameInputs ?: return
        if (phaseMachine.phase == VanillaFramePhase.GUI_READY) return
        val camera = frame.current
        if (receipt.world !== frame.world || receipt.frameTimeNanos != frame.frameTimeNanos ||
            receipt.width != frame.identity.width || receipt.height != frame.identity.height ||
            receipt.historyGeneration != frame.historyGeneration || receipt.jitterPhaseIndex != frame.jitterPhaseIndex ||
            receipt.jitterX != camera.jitterX || receipt.jitterY != camera.jitterY ||
            !receipt.projection().contentEquals(camera.projection()) ||
            !receipt.rasterProjection().contentEquals(camera.rasterProjection())) {
            val failure = IllegalStateException("Level projection changed during a frozen world frame")
            abortActiveFrame(failure)
            throw failure
        }
    }

    private fun commitWorldRender() {
        val frame = checkNotNull(frameInputs) { "World render completed without frame inputs" }
        check((phaseMachine.phase == VanillaFramePhase.WORLD_READY || phaseMachine.phase == VanillaFramePhase.WORLD_POST) &&
            phaseMachine.identity == frame.identity) {
            "World render completed before world inputs were recorded"
        }
        recordedWorldFrame = frame
    }

    internal fun abortWorldRender(failure: Throwable) {
        pendingPresentedFrame = pendingPresentedFrame?.copy(frame = null)
        abortActiveFrame(failure)
    }

    @JvmStatic
    fun worldPassStarted() {
        val inputs = checkNotNull(frameInputs) { "World pass has no frozen camera frame" }
        val frame = inputs.identity
        try {
            check(phaseMachine.mainPassStarted(frame)) {
                "World main pass started out of order: ${phaseMachine.phase}"
            }
            val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val color = checkNotNull((target.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture)
            val depth = checkNotNull(target.depthTexture as? D3D12GpuTexture)
            check(color.handle() == frameColorHandle && depth.handle() == frameDepthHandle &&
                color.getWidth(0) == frame.width && color.getHeight(0) == frame.height) {
                "World attachments changed after camera freeze"
            }
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    @JvmStatic
    internal fun prepareOitComposite(compositeInput: OitOriginalCompositeCall): RenderTarget? {
        val inputs = checkNotNull(frameInputs) { "OIT composite has no frozen frame" }
        check(phaseMachine.phase == VanillaFramePhase.RECORDING) {
            "OIT composite arrived out of order: ${phaseMachine.phase}"
        }
        try {
            check(phaseMachine.opaqueReady(inputs.identity))
            val scene = OitReconstructionInputs.prepareComposite(compositeInput)
            if (scene == null) {
                check(phaseMachine.opaquePreparationSkipped(inputs.identity))
                return null
            }
            check(phaseMachine.opaquePostCompleted(inputs.identity))
            D3D12ShadingRateImage.captureWorldDepth(checkNotNull(D3D12Device.sharedCommandEncoder()))
            LateWorldTargets.activateSceneDisplay(inputs)
            return scene
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    @JvmStatic
    fun worldPassCompleted(targets: LevelTargetBundle) {
        val frame = phaseMachine.identity ?: run {
            D3D12SrMask.invalidateCapture()
            return
        }
        check(phaseMachine.phase == VanillaFramePhase.RECORDING || phaseMachine.phase == VanillaFramePhase.OPAQUE_POST) {
            "World pass completed out of order: ${phaseMachine.phase}"
        }
        try {
            val views = targets.transmittance.mapNotNull { handle ->
                runCatching { handle.get().colorTextureView as? D3D12GpuTextureView }.getOrNull()
            }
            D3D12SrMask.captureTransmittance(
                views.toTypedArray(), frame.worldEpoch, frame.realFrameId, frame.resourceVersion,
            )
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    @JvmStatic
    fun beforeDisplayScene() {
        val inputs = checkNotNull(frameInputs) { "World completion has no frame inputs" }
        val frame = inputs.identity
        check(phaseMachine.phase == VanillaFramePhase.RECORDING || phaseMachine.phase == VanillaFramePhase.OPAQUE_POST) {
            "World completed out of order: ${phaseMachine.phase}"
        }
        try {
            WorldTransparencyCapture.seal(inputs)
            val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
            D3D12RasterMotion.end(encoder)
            recordNativeFrame { ctx -> VanillaFrameWire.worldReady(ctx, frame) }
            check(phaseMachine.worldReady(frame))
            val lateTarget = LateWorldTargets.sceneTarget()
            if (lateTarget == null) ReconstructionFrameInputs.capture(encoder, Minecraft.getInstance())
            else {
                ReconstructionFrameInputs.captureDisplayWorldDepth(encoder, inputs, lateTarget)
                encoder.recordVelocityPass()
            }
            commitWorldRender()
            check(phaseMachine.worldPostStarted(frame))
            D3D12SceneDisplay.prepareWorldOutput(inputs)
            LateWorldTargets.restoreWorldProjection()
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    @JvmStatic
    fun worldPassAborted(failure: Throwable?) {
        abortActiveFrame(failure)
    }

    private fun abortActiveFrame(failure: Throwable?) {
        try {
            retireDisplayFrame(DisplayFrameRetirement.ABORTED)
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            failure.addSuppressed(cleanup)
        }
    }

    @JvmStatic
    fun beforeGui() {
        try {
            if (phaseMachine.phase == VanillaFramePhase.IDLE) {
                D3D12RasterMotion.end(checkNotNull(D3D12Device.sharedCommandEncoder()))
                D3D12RasterMotion.discard()
                OitReconstructionInputs.invalidate()
                recordedWorldFrame = null
                presentedWorldFrame = null
                frameInputs = null
                if (hadWorldFrame) {
                    invalidateReconstructionInputs()
                    hadWorldFrame = false
                }
                D3D12GUISplit.resetForNoWorld()
                D3D12SceneDisplay.discardFrame()
                D3D12ViewmodelInputs.invalidate()
                D3D12SrMask.invalidateCapture()
                D3D12GUISplit.prepareForGui()
                return
            }
            check(phaseMachine.phase == VanillaFramePhase.WORLD_POST) {
                "Invalid world-post phase ${phaseMachine.phase}"
            }
            val inputs = checkNotNull(frameInputs)
            val frame = inputs.identity
            D3D12SceneDisplay.sealScene(inputs)
            DeepLearningSuperSampling.pclRenderSubmitStart()
            D3D12GUISplit.prepareForGui()
            recordNativeFrame { ctx -> VanillaFrameWire.guiReady(ctx, frame) }
            check(phaseMachine.guiReady(frame))
            DisplayFrameSubmission.prepare(inputs)
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    @JvmStatic
    fun afterGui() {
        D3D12GUISplit.completeGui()
        try {
            if (phaseMachine.phase != VanillaFramePhase.IDLE) {
                val inputs = checkNotNull(frameInputs)
                check(phaseMachine.guiCompleted(inputs.identity)) { "GUI completed out of order: ${phaseMachine.phase}" }
                DisplayFrameSubmission.submit(inputs)
            }
            D3D12GUISplit.composeDisplay()
            D3D12GUISplit.saveScreenshots()
        } catch (failure: Throwable) {
            abortActiveFrame(failure)
            throw failure
        }
    }

    @JvmStatic
    fun retireSubmittedDisplayFrame() {
        retireDisplayFrame(DisplayFrameRetirement.SUBMITTED)
    }

    internal fun retireUnpresentedFrame() {
        retireDisplayFrame(DisplayFrameRetirement.ABORTED)
    }

    private fun retireDisplayFrame(retirement: DisplayFrameRetirement) {
        if (!displayFrameStarted) return
        if (retirement == DisplayFrameRetirement.ABORTED) {
            recordedWorldFrame = null
            presentedWorldFrame = null
            hadWorldFrame = false
        }
        try {
            val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Display retirement has no command encoder" }
            if (retirement == DisplayFrameRetirement.ABORTED) {
                encoder.abortWorldRenderPassState()
                D3D12ViewmodelInputs.invalidate()
            }
            D3D12RasterMotion.end(encoder)
            OitReconstructionInputs.retireRecords()
            if (retirement == DisplayFrameRetirement.ABORTED) OitReconstructionInputs.invalidate()
            val retired = when (retirement) {
                DisplayFrameRetirement.SUBMITTED -> encoder.contextControl { ctx -> MCDR_ABI.D3D12VanillaResetFrame(ctx) == 1 }
                DisplayFrameRetirement.ABORTED -> encoder.abortDisplayFrame()
            }
            check(retired) {
                "Could not retire display frame: $retirement; ${MCDR_ABI.D3D12LastError()}"
            }
            displayFrameStarted = false
            D3D12Temporal.onDisplayFrameRetired()
            check(pendingPresentedFrame == null) { "Previous display presentation has not settled" }
            val fence = encoder.contextControl(MCDR_ABI::D3D12GetFenceValue)
            if (retirement == DisplayFrameRetirement.SUBMITTED) {
                frameInputs?.let(OitReconstructionInputs::stagePresented)
                pendingPresentedFrame = PendingPresentedFrame(frameInputs, fence)
            } else {
                pendingPresentedFrame = PendingPresentedFrame(null, fence)
                settlePreviousDisplayFrame(encoder)
            }
        } catch (failure: Throwable) {
            recordedWorldFrame = null
            presentedWorldFrame = null
            try { OitReconstructionInputs.invalidate() } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            D3D12SrMask.invalidateCapture()
            D3D12GUISplit.discardFrame()
            D3D12SceneDisplay.discardFrame()
            LateWorldTargets.discardFrame()
            DisplayFrameSubmission.discardFrame()
            WorldTransparencyCapture.discard()
            frameColorHandle = 0L
            frameDepthHandle = 0L
            frameInputs = null
            ReconstructionFrameInputs.invalidate()
            phaseMachine.finish()
            D3D12RasterMotion.discard()
        }
    }

    private fun settlePreviousDisplayFrame(encoder: D3D12CommandEncoderBackend) {
        when (val settled = encoder.settleDisplayPresentation(2_000L)) {
            DisplayFrameSettlement.Empty -> check(pendingPresentedFrame == null) {
                "Display candidate has no native presentation result"
            }
            is DisplayFrameSettlement.TimedOut -> error(
                "Display presentation remains pending: serial=${settled.receipt.serial}")
            is DisplayFrameSettlement.SubmissionUnknown -> error(
                "Display submission ownership remains unknown: serial=${settled.receipt.serial}")
            is DisplayFrameSettlement.Completed -> {
                val receipt = settled.receipt
                check(receipt.serial > consumedPresentSerial) { "Display presentation result was already consumed" }
                consumedPresentSerial = receipt.serial
                val pending = pendingPresentedFrame
                pendingPresentedFrame = null
                val frame = pending?.frame
                val matches = pending != null && frame != null && frame.world === Minecraft.getInstance().level &&
                    receipt.worldEpoch == frame.identity.worldEpoch && receipt.realFrameId == frame.identity.realFrameId &&
                    receipt.configVersion == frame.identity.configVersion &&
                    receipt.resourceVersion == frame.identity.resourceVersion &&
                    receipt.historyGeneration == frame.historyGeneration &&
                    receipt.historyGeneration == D3D12Temporal.historyGeneration &&
                    receipt.commandFenceValue == pending.commandFenceValue
                if (receipt.outcome == DisplayPresentOutcome.SUCCEEDED) {
                    check(receipt.dxgiStatusValid && receipt.dxgiStatus == 0 &&
                        receipt.submission == DisplaySubmissionState.SUBMITTED) { "Invalid successful display receipt" }
                    D3D12FrameStats.onFramePresented(receipt.outputFrameCount)
                }
                if (matches && receipt.outcome == DisplayPresentOutcome.SUCCEEDED) {
                    presentedWorldFrame = frame
                    if (receipt.oitHistoryPublished) OitReconstructionInputs.publishPresented(checkNotNull(frame))
                    else OitReconstructionInputs.discardPresented()
                    D3D12ViewmodelInputs.publishPresented(checkNotNull(frame))
                } else {
                    presentedWorldFrame = null
                    OitReconstructionInputs.discardPresented()
                    D3D12ViewmodelInputs.invalidate()
                }
            }
        }
    }

    internal fun closeDisplayFrames() {
        pendingPresentedFrame = pendingPresentedFrame?.copy(frame = null)
        retireUnpresentedFrame()
        D3D12Device.sharedCommandEncoder()?.let(::settlePreviousDisplayFrame)
        recordedWorldFrame = null
        presentedWorldFrame = null
        OitReconstructionInputs.discardPresented()
        D3D12ViewmodelInputs.invalidate()
    }

    private fun recordNativeFrame(action: (Long) -> Int): Boolean {
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "World frame has no command encoder" }
        val result = encoder.recordCommands(action)
        check(result == 1) { "Native frame phase rejected: $result; ${MCDR_ABI.D3D12LastError()}" }
        return true
    }

    private fun invalidateReconstructionInputs() {
        OitReconstructionInputs.invalidate()
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
        check(encoder.contextControl(MCDR_ABI::D3D12InvalidateReconstructionInputs)) {
            "Reconstruction inputs remain active: ${MCDR_ABI.D3D12LastError()}"
        }
    }

}
