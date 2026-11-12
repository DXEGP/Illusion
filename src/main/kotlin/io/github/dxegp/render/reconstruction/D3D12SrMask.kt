package io.github.dxegp.render.reconstruction

import com.mojang.renderpearl.api.GpuFormat
import io.github.dxegp.Illusion
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.render.temporal.D3D12Temporal
import net.minecraft.client.Minecraft
import io.github.dxegp.render.frameprep.ProviderMaskSemantic
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator

/** Builds the FSR reactive/XeSS responsive mask from OIT wavelet attachments. */
object D3D12SrMask {
    private const val MAX_MASK = 900
    private const val ATTACHMENTS = 2

    private var output: D3D12GpuTexture? = null
    private var sourceTextures = arrayOfNulls<D3D12GpuTexture>(ATTACHMENTS)
    private var attachments = 0
    private var width = 0
    private var height = 0
    private var sourceVersion = 0L
    private var capturedWorldEpoch = 0L
    private var capturedRealFrameId = 0L
    private var capturedViewportVersion = 0L
    private var capturedWorld: Any? = null
    private var capturedFrameTime = Long.MIN_VALUE
    private var capturedHistoryGeneration = Long.MIN_VALUE
    private var capturedView: FloatArray? = null
    private var failure: String? = null

    /** Calling this with no usable attachments invalidates the prior capture. */
    @JvmStatic
    @Synchronized
    fun captureTransmittance(
        views: Array<D3D12GpuTextureView>,
        worldEpoch: Long,
        realFrameId: Long,
        viewportVersion: Long,
    ) {
        sourceVersion += 1L
        sourceTextures = arrayOfNulls(ATTACHMENTS)
        attachments = 0
        width = 0
        height = 0
        capturedWorldEpoch = worldEpoch
        capturedRealFrameId = realFrameId
        capturedViewportVersion = viewportVersion
        val minecraft = Minecraft.getInstance()
        capturedWorld = minecraft.level
        capturedFrameTime = minecraft.frameTimeNs
        capturedHistoryGeneration = D3D12Temporal.historyGeneration
        capturedView = D3D12Temporal.currentCameraView()?.copyOf()
        if (worldEpoch <= 0 || realFrameId <= 0 || viewportVersion <= 0 || capturedWorld == null) {
            failure = "OIT capture has no valid frame identity"
            return
        }
        for (view in views) {
            if (attachments >= ATTACHMENTS) break
            val texture = view.texture() as? D3D12GpuTexture ?: continue
            if (texture.isClosed()) continue
            val candidateWidth = texture.getWidth(0)
            val candidateHeight = texture.getHeight(0)
            if (candidateWidth <= 0 || candidateHeight <= 0) continue
            if (attachments == 0) {
                width = candidateWidth
                height = candidateHeight
            } else if (candidateWidth != width || candidateHeight != height) {
                sourceTextures = arrayOfNulls(ATTACHMENTS)
                attachments = 0
                width = 0
                height = 0
                failure = "OIT coefficient attachments have different dimensions"
                return
            }
            if (sourceTextures.any { it === texture }) continue
            sourceTextures[attachments++] = texture
        }
        if (attachments == ATTACHMENTS) {
            failure = null
            Illusion.LOGGER.debug(
                "SR mask captured {}x{} OIT coefficient attachments (source version {})",
                width, height, sourceVersion,
            )
        } else {
            failure = "OIT capture supplied $attachments of $ATTACHMENTS coefficient attachments"
        }
    }

    @JvmStatic
    @Synchronized
    fun hasSource(): Boolean {
        val minecraft = Minecraft.getInstance()
        val identity = VanillaFrameCoordinator.currentIdentity() ?: return false
        return attachments == ATTACHMENTS && capturedWorld === minecraft.level &&
            capturedWorldEpoch == identity.worldEpoch && capturedRealFrameId == identity.realFrameId &&
            capturedViewportVersion == identity.resourceVersion &&
            capturedFrameTime == minecraft.frameTimeNs &&
            capturedHistoryGeneration == D3D12Temporal.historyGeneration &&
            capturedView.contentEquals(D3D12Temporal.currentCameraView()) &&
            sourceTextures.all { it != null && !it.isClosed() }
    }

    /** Records the mask into the already-open list; never submits or reads it back. */
    @Synchronized
    fun produce(encoder: D3D12CommandEncoderBackend, maskWidth: Int, maskHeight: Int): Long {
        if (failure != null || !hasSource() || maskWidth <= 0 || maskHeight <= 0 ||
            maskWidth != width || maskHeight != height
        ) return 0L
        val first = sourceTextures[0] ?: return 0L
        val second = sourceTextures[1] ?: return 0L
        val target = ensureOutput(maskWidth, maskHeight) ?: return 0L
        if (capturedWorldEpoch <= 0L || capturedRealFrameId <= 0L || capturedViewportVersion <= 0L) return 0L
        val produced = MCDR_ABI.D3D12ProduceReactiveMask(
                encoder.nativeHandle(), first.handle(), second.handle(), target.handle(),
                maskWidth, maskHeight,
                capturedWorldEpoch, capturedRealFrameId, sourceVersion, capturedViewportVersion,
            )
        if (!produced) {
            fail("native frameprep rejected the complete capture: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        return target.handle()
    }

    internal fun produceForProvider(encoder: D3D12CommandEncoderBackend, maskWidth: Int,
        maskHeight: Int, backend: Int): Long {
        val caps = MCDR_ABI.querySrCaps(backend) ?: return 0L
        val semantic = when {
            backend == MCDR_ABI.SR_BACKEND_FSR && caps.takesReactiveMask -> ProviderMaskSemantic.FSR_REACTIVE
            backend == MCDR_ABI.SR_BACKEND_XESS && caps.takesResponsiveMask -> ProviderMaskSemantic.XESS_RESPONSIVE
            else -> return 0L
        }
        val handle = produce(encoder, maskWidth, maskHeight)
        if (handle != 0L) Illusion.LOGGER.debug("OIT transparency mask supplied as {}", semantic)
        return handle
    }

    @Synchronized
    fun note(): String? = failure

    @Synchronized
    fun invalidateCapture() {
        sourceTextures = arrayOfNulls(ATTACHMENTS)
        attachments = 0
        capturedWorld = null
        capturedFrameTime = Long.MIN_VALUE
        capturedView = null
    }

    @Synchronized
    fun release() {
        output?.close()
        output = null
        invalidateCapture()
        sourceTextures = arrayOfNulls(ATTACHMENTS)
        attachments = 0
        width = 0
        height = 0
        capturedWorldEpoch = 0L
        capturedRealFrameId = 0L
        capturedViewportVersion = 0L
        sourceVersion += 1L
    }

    private fun ensureOutput(maskWidth: Int, maskHeight: Int): D3D12GpuTexture? {
        val existing = output
        if (existing != null && !existing.isClosed() && existing.getWidth(0) == maskWidth && existing.getHeight(0) == maskHeight) {
            return existing
        }
        existing?.close()
        return try {
            D3D12GpuTexture(
                D3D12GpuTexture.USAGE_STORAGE or 1 or 2,
                "DXRMC SR reactive mask",
                GpuFormat.R8_UNORM,
                maskWidth,
                maskHeight,
                1,
                1,
            ).also {
                output = it
                Illusion.LOGGER.info(
                    "SR mask Work Graph output {}x{} (scale {}, source version {})",
                    maskWidth, maskHeight, MAX_MASK, sourceVersion,
                )
            }
        } catch (t: Throwable) {
            fail("could not create the reactive-mask output: $t")
            null
        }
    }

    private fun fail(reason: String) {
        failure = reason
        Illusion.LOGGER.warn("SR mask disabled for this capture: {}", reason)
    }
}
