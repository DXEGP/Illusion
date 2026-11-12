package io.github.dxegp.render.frame.vanilla

import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.render.antialiasing.D3D12SMAAWorld
import io.github.dxegp.render.antialiasing.D3D12TAA
import io.github.dxegp.render.presentation.D3D12GUISplit
import io.github.dxegp.render.reconstruction.D3D12SrMask
import io.github.dxegp.render.reconstruction.SuperResolution
import io.github.dxegp.render.reconstruction.dlss.DeepLearningSuperSampling
import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.resolution.D3D12RenderScale
import io.github.dxegp.render.vrs.D3D12ShadingRateImage
import io.github.dxegp.d3d12.device.D3D12Device
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelTargetBundle

object VanillaFrameCoordinator {
    private const val SCHEMA_VERSION = 1
    private val phaseMachine = VanillaFramePhaseMachine()
    private var observedWorld: Any? = null
    private var worldEpoch = 0L
    private var lastResourceIdentity: Any? = null
    private var lastDepthIdentity: Any? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastResourceWorldEpoch = 0L
    private var realFrameId = 0L
    private var resourceVersion = 0L
    private var lastConfigSignature = Long.MIN_VALUE
    private var configVersion = 0L
    private var hadWorldFrame = false

    internal fun currentIdentity(): VanillaFrameIdentity? = phaseMachine.identity

    private fun identity(): VanillaFrameIdentity? {
        val minecraft = Minecraft.getInstance()
        val world = minecraft.level ?: return null
        if (world !== observedWorld) {
            if (observedWorld != null) invalidateReconstructionInputs()
            observedWorld = world
            worldEpoch++
        }
        val target = minecraft.gameRenderer.mainRenderTarget()
        val width = target.width
        val height = target.height
        val configSignature = (D3D12AntiAliasing.current().toLong() shl 32) xor
            (SuperResolution.backendId().toLong() shl 16) xor D3D12RenderScale.percent().toLong()
        if (configSignature != lastConfigSignature) {
            lastConfigSignature = configSignature
            configVersion++
        }
        val resourceIdentity = target.colorTextureView
        if (resourceIdentity !== lastResourceIdentity || target.depthTexture !== lastDepthIdentity ||
            width != lastWidth || height != lastHeight || lastResourceWorldEpoch != worldEpoch) {
            lastResourceIdentity = resourceIdentity
            lastDepthIdentity = target.depthTexture
            lastWidth = width
            lastHeight = height
            lastResourceWorldEpoch = worldEpoch
            resourceVersion++
        }
        check(realFrameId < Long.MAX_VALUE) { "Real frame sequence exhausted" }
        realFrameId += 1L
        return VanillaFrameIdentity(
            SCHEMA_VERSION,
            worldEpoch,
            realFrameId,
            configVersion.coerceAtLeast(1L),
            resourceVersion.coerceAtLeast(1L),
            width,
            height,
        )
    }

    @JvmStatic
    fun worldPassCompleted(targets: LevelTargetBundle) {
        val frame = identity() ?: run {
            D3D12SrMask.invalidateCapture()
            return
        }
        hadWorldFrame = true
        check(phaseMachine.begin(frame)) { "Duplicate or out-of-order world frame ${frame.realFrameId}" }
        recordNativeFrame { ctx -> MCDR_ABI.D3D12VanillaBeginFrame(ctx, frame) }
        recordNativeFrame { ctx -> MCDR_ABI.D3D12VanillaWorldReady(ctx, frame) }
        check(phaseMachine.worldReady(frame))
        D3D12TAA.captureTemporalData()
        SuperResolution.captureFrameInputs()
        D3D12Device.sharedCommandEncoder()?.let(D3D12ShadingRateImage::captureWorldDepth)
        val views = targets.transmittance.mapNotNull { handle ->
            runCatching { handle.get().colorTextureView as? D3D12GpuTextureView }.getOrNull()
        }
        D3D12SrMask.captureTransmittance(
            views.toTypedArray(), frame.worldEpoch, frame.realFrameId, frame.resourceVersion,
        )
    }

    @JvmStatic
    fun beforeGui() {
        if (phaseMachine.phase == VanillaFramePhase.IDLE) {
            if (hadWorldFrame) {
                invalidateReconstructionInputs()
                hadWorldFrame = false
            }
            D3D12SrMask.invalidateCapture()
            D3D12GUISplit.prepareForGui()
            return
        }
        check(phaseMachine.phase == VanillaFramePhase.WORLD_READY) { "Invalid world-post phase ${phaseMachine.phase}" }
        val frame = checkNotNull(phaseMachine.identity)
        check(phaseMachine.worldPostStarted(frame))
        D3D12TAA.applyIfEnabled()
        D3D12SMAAWorld.applyIfEnabled()
        SuperResolution.applyIfEnabled()
        DeepLearningSuperSampling.pclRenderSubmitStart()
        D3D12GUISplit.prepareForGui()
        recordNativeFrame { ctx -> MCDR_ABI.D3D12VanillaGuiReady(ctx, frame) }
        check(phaseMachine.guiReady(frame))
    }

    @JvmStatic
    fun beforeDepthClear() {
        if (phaseMachine.phase != VanillaFramePhase.WORLD_READY) return
        if (SuperResolution.backendId() != MCDR_ABI.SR_BACKEND_DLSS) return
        DeepLearningSuperSampling.reconcileAntiAliasingEveryFrame()
        DeepLearningSuperSampling.pclFrameBegin()
    }

    @JvmStatic
    fun framePresented() {
        try {
            val encoder = D3D12Device.sharedCommandEncoder()
            if (encoder != null) {
                check(encoder.contextControl { ctx -> MCDR_ABI.D3D12VanillaResetFrame(ctx) == 1 }) {
                    "Could not reset native frame after Present"
                }
            }
        } finally {
            D3D12SrMask.invalidateCapture()
            phaseMachine.finish()
        }
    }

    private fun recordNativeFrame(action: (Long) -> Int): Boolean {
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "World frame has no command encoder" }
        val result = encoder.recordCommands(action)
        check(result == 1) { "Native frame phase rejected: $result; ${MCDR_ABI.D3D12LastError()}" }
        return true
    }

    private fun invalidateReconstructionInputs() {
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
        check(encoder.contextControl(MCDR_ABI::D3D12InvalidateReconstructionInputs)) {
            "Reconstruction inputs remain active: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    private fun MCDR_ABI.D3D12VanillaBeginFrame(ctx: Long, frame: VanillaFrameIdentity): Int =
        D3D12VanillaBeginFrame(ctx, frame.width, frame.height, frame.worldEpoch, frame.realFrameId,
            frame.configVersion, frame.resourceVersion)

    private fun MCDR_ABI.D3D12VanillaWorldReady(ctx: Long, frame: VanillaFrameIdentity): Int =
        D3D12VanillaWorldReady(ctx, frame.width, frame.height, frame.worldEpoch, frame.realFrameId,
            frame.configVersion, frame.resourceVersion)

    private fun MCDR_ABI.D3D12VanillaGuiReady(ctx: Long, frame: VanillaFrameIdentity): Int =
        D3D12VanillaGuiReady(ctx, frame.width, frame.height, frame.worldEpoch, frame.realFrameId,
            frame.configVersion, frame.resourceVersion)
}
