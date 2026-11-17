package io.github.dxegp.render.reconstruction

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.Illusion
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import org.joml.Matrix4f

object D3D12SrMask : AutoCloseable {
    private const val MAX_MASK = 900
    private const val ATTACHMENTS = 2

    private var owner: D3D12CommandEncoderBackend? = null
    private var output: D3D12GpuTexture? = null
    private var compositionOutput: D3D12GpuTexture? = null
    private var sourceTextures = arrayOfNulls<D3D12GpuTexture>(ATTACHMENTS)
    private var sourceCopies = arrayOfNulls<D3D12GpuTexture>(ATTACHMENTS)
    private var attachments = 0
    private var width = 0
    private var height = 0
    private var sourceVersion = 0L
    private var capturedFrame: VanillaFrameInputs? = null

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
        val frame = VanillaFrameCoordinator.currentFrame()
        capturedFrame = frame
        if (frame == null || worldEpoch != frame.identity.worldEpoch ||
            realFrameId != frame.identity.realFrameId || viewportVersion != frame.identity.resourceVersion) {
            return
        }
        if (!frame.settings.providerMasks ||
            (frame.settings.reconstructionBackend != MCDR_ABI.SR_BACKEND_FSR &&
                frame.settings.reconstructionBackend != MCDR_ABI.SR_BACKEND_XESS)) {
            return
        }
        val sources = ArrayList<D3D12GpuTexture>(ATTACHMENTS)
        for (view in views) {
            if (attachments >= ATTACHMENTS) break
            val texture = view.texture() as? D3D12GpuTexture ?: continue
            if (texture.isClosed()) continue
            check(view.baseMip() == 0 && texture.getMipLevels() == 1 && texture.getDepthOrLayers() == 1) {
                "OIT coefficient capture requires a single base-mip attachment"
            }
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
                return
            }
            if (sources.any { it === texture }) continue
            sources.add(texture)
            attachments++
        }
        if (attachments == ATTACHMENTS) {
            val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "OIT capture has no command encoder" }
            retainOwner(encoder)
            val copies = sources.mapIndexed { index, texture ->
                val existing = sourceCopies[index]
                if (existing != null && !existing.isClosed() && existing.format == texture.format &&
                    existing.getWidth(0) == width && existing.getHeight(0) == height) {
                    existing
                } else {
                    D3D12GpuTexture(
                        GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                        "Captured OIT coefficient $index", texture.format, width, height, 1, 1,
                    ).also {
                        sourceCopies[index] = it
                        existing?.close()
                    }
                }
            }
            val uses = sources.flatMapIndexed { index, texture ->
                VanillaFrameInputResources.textureCopy(texture.handle(), copies[index].handle(), viewportVersion)
            }
            encoder.recordCommands { context ->
                VanillaFrameCoordinator.declaredPass(context, 0x3000A, uses, graphicsEffects = 0) {
                    sources.forEachIndexed { index, texture ->
                        encoder.copyTextureToTexture(texture, copies[index], 0, 0, 0, 0, 0, width, height)
                    }
                }
            }
            copies.forEachIndexed { index, texture -> sourceTextures[index] = texture }
            Illusion.LOGGER.debug(
                "SR mask captured {}x{} OIT coefficient attachments (source version {})",
                width, height, sourceVersion,
            )
        }
    }

    @JvmStatic
    @Synchronized
    fun hasSource(): Boolean {
        val frame = VanillaFrameCoordinator.currentFrame() ?: return false
        return attachments == ATTACHMENTS && capturedFrame === frame &&
            width == frame.identity.width && height == frame.identity.height &&
            sourceTextures.all { it != null && !it.isClosed() &&
                it.getWidth(0) == width && it.getHeight(0) == height }
    }

    @Synchronized
    internal fun writeWorldOutputInputs(encoder: D3D12CommandEncoderBackend, input: MemorySegment,
        frame: VanillaFrameInputs, color: D3D12GpuTexture, depth: D3D12GpuTexture, motion: Long) {
        if (frame.settings.reconstructionBackend != MCDR_ABI.SR_BACKEND_FSR &&
            frame.settings.reconstructionBackend != MCDR_ABI.SR_BACKEND_XESS) return
        retainOwner(encoder)
        val primary = checkNotNull(ensureOutput(frame.identity.width, frame.identity.height))
        val first = sourceTextures[0]?.takeIf { hasSource() }
        val second = sourceTextures[1]?.takeIf { hasSource() }
        val composition = if (first != null && second != null &&
            frame.settings.reconstructionBackend == MCDR_ABI.SR_BACKEND_FSR)
            checkNotNull(ensureCompositionOutput(frame.identity.width, frame.identity.height)) else null
        val layout = MCDRLayout.PROVIDER_MASK_INPUT
        input.set(JAVA_INT, layout.byteOffset(groupElement("schemaVersion")), 2)
        input.set(JAVA_INT, layout.byteOffset(groupElement("structSize")), layout.byteSize().toInt())
        val identity = input.asSlice(layout.byteOffset(groupElement("identity")), MCDRLayout.FRAME_IDENTITY.byteSize())
        for ((name, value) in listOf("worldEpoch" to frame.identity.worldEpoch,
            "realFrameId" to frame.identity.realFrameId, "configVersion" to frame.identity.configVersion,
            "resourceVersion" to frame.identity.resourceVersion)) {
            identity.set(JAVA_LONG, MCDRLayout.FRAME_IDENTITY.byteOffset(groupElement(name)), value)
        }
        input.set(JAVA_LONG, layout.byteOffset(groupElement("historyGeneration")), frame.historyGeneration)
        input.set(JAVA_LONG, layout.byteOffset(groupElement("previousRealFrameId")),
            if (frame.previous == null) 0L else frame.identity.realFrameId - 1L)
        for ((name, handle) in listOf("color" to color.handle(), "depth" to depth.handle(), "motion" to motion,
            "source0" to (first?.handle() ?: 0L), "source1" to (second?.handle() ?: 0L),
            "output" to primary.handle(), "compositionOutput" to (composition?.handle() ?: 0L))) {
            input.set(ADDRESS, layout.byteOffset(groupElement(name)), MemorySegment.ofAddress(handle))
        }
        input.set(JAVA_INT, layout.byteOffset(groupElement("width")), frame.identity.width)
        input.set(JAVA_INT, layout.byteOffset(groupElement("height")), frame.identity.height)
        input.set(JAVA_INT, layout.byteOffset(groupElement("inputFlags")),
            1 or (if (first != null && second != null) 2 else 0) or
                (if (frame.previous != null) 4 else 0))
        input.set(JAVA_FLOAT, layout.byteOffset(groupElement("historyJitterX")),
            ((frame.previous?.jitterX ?: 0.0) - frame.current.jitterX).toFloat())
        input.set(JAVA_FLOAT, layout.byteOffset(groupElement("historyJitterY")),
            (frame.current.jitterY - (frame.previous?.jitterY ?: 0.0)).toFloat())
        val currentProjection = Matrix4f().set(frame.current.rasterProjection())
            .mul(Matrix4f().set(frame.current.viewRotation()))
        val previous = frame.previous ?: frame.current
        val previousView = Matrix4f().set(previous.viewRotation()).translate(
            (frame.current.originX - previous.originX).toFloat(),
            (frame.current.originY - previous.originY).toFloat(),
            (frame.current.originZ - previous.originZ).toFloat())
        val mapping = Matrix4f().set(previous.rasterProjection()).mul(previousView)
            .mul(currentProjection.invert()).get(FloatArray(16))
        check(mapping.all(Float::isFinite)) { "Provider-mask history mapping is invalid" }
        val matrixOffset = layout.byteOffset(groupElement("currentToPrevious"))
        mapping.forEachIndexed { index, value -> input.set(JAVA_FLOAT, matrixOffset + index * 4L, value) }
    }

    @Synchronized
    fun invalidateCapture() {
        sourceTextures = arrayOfNulls(ATTACHMENTS)
        attachments = 0
        capturedFrame = null
    }

    @Synchronized
    override fun close() {
        output?.close()
        output = null
        compositionOutput?.close()
        compositionOutput = null
        sourceCopies.forEach { it?.close() }
        sourceCopies = arrayOfNulls(ATTACHMENTS)
        invalidateCapture()
        sourceTextures = arrayOfNulls(ATTACHMENTS)
        attachments = 0
        width = 0
        height = 0
        sourceVersion += 1L
        owner = null
    }

    private fun retainOwner(encoder: D3D12CommandEncoderBackend) {
        check(owner == null || owner === encoder) { "SR mask outputs changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
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
            logFailure("could not create the reactive-mask output: $t")
            null
        }
    }

    private fun ensureCompositionOutput(maskWidth: Int, maskHeight: Int): D3D12GpuTexture? {
        val existing = compositionOutput
        if (existing != null && !existing.isClosed() && existing.getWidth(0) == maskWidth && existing.getHeight(0) == maskHeight) {
            return existing
        }
        existing?.close()
        return try {
            D3D12GpuTexture(
                D3D12GpuTexture.USAGE_STORAGE or 1 or 2,
                "DXRMC SR transparency and composition mask",
                GpuFormat.R8_UNORM,
                maskWidth,
                maskHeight,
                1,
                1,
            ).also { compositionOutput = it }
        } catch (t: Throwable) {
            logFailure("could not create the transparency-and-composition output: $t")
            null
        }
    }

    private fun logFailure(reason: String) {
        Illusion.LOGGER.warn("SR mask disabled for this capture: {}", reason)
    }
}
