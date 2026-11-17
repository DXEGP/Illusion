package io.github.dxegp.render.frame.vanilla

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.reconstruction.D3D12SrMask
import io.github.dxegp.render.reconstruction.ReconstructionFrameInputs
import io.github.dxegp.render.reconstruction.ReconstructionTiming
import io.github.dxegp.render.reconstruction.OpaqueReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.ReconstructionTemporalInputs
import io.github.dxegp.render.temporal.D3D12Velocity
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import net.minecraft.client.Minecraft

internal object VanillaWorldOutput : AutoCloseable {
    internal enum class SamplingGrid { CURRENT_RASTER, TEMPORAL_DISPLAY }
    private enum class SourceStage(val wire: Int) { COMPLETE_WORLD(0), OPAQUE_BEFORE_COMPOSITE(1) }
    private var owner: D3D12CommandEncoderBackend? = null
    private var exposure: D3D12GpuTexture? = null
    private var reconstructionOutput: D3D12GpuTexture? = null
    private val timing = ReconstructionTiming { "world output" }

    fun record(frame: VanillaFrameInputs, encoder: D3D12CommandEncoderBackend): SamplingGrid {
        encoder.recordVelocityPass()
        val color = checkNotNull(Minecraft.getInstance().gameRenderer.mainRenderTarget().colorTexture as? D3D12GpuTexture)
        val temporal = ReconstructionFrameInputs.recordTemporalInputs(encoder, frame)
        val depth = temporal?.depth ?: checkNotNull(ReconstructionFrameInputs.capturedDepth()) { "World output has no captured depth" }
        return recordOutput(frame, encoder, color, depth, temporal, SourceStage.COMPLETE_WORLD)
    }

    fun recordOpaque(frame: VanillaFrameInputs, encoder: D3D12CommandEncoderBackend,
        opaque: OpaqueReconstructionInputs): SamplingGrid {
        check(opaque.frame === frame && opaque.motion.frame === frame) { "Opaque world output belongs to another frame" }
        return recordOutput(frame, encoder, opaque.color, opaque.depth,
            ReconstructionTemporalInputs(frame, opaque.depth, opaque.motion.motion, opaque.motion.guideCoverage),
            SourceStage.OPAQUE_BEFORE_COMPOSITE)
    }

    private fun recordOutput(frame: VanillaFrameInputs, encoder: D3D12CommandEncoderBackend,
        color: D3D12GpuTexture, depth: D3D12GpuTexture, temporal: ReconstructionTemporalInputs?, sourceStage: SourceStage): SamplingGrid {
        check(owner == null || owner === encoder) { "World output changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val scene = checkNotNull(D3D12SceneDisplay.ensureTarget().colorTexture as? D3D12GpuTexture)
        val provider = frame.settings.reconstructionBackend != MCDR_ABI.SR_BACKEND_NONE
        val output = if (provider) {
            val previous = reconstructionOutput
            if (previous != null && !previous.isClosed() && previous.format == color.format &&
                previous.getWidth(0) == frame.displayWidth && previous.getHeight(0) == frame.displayHeight) previous
            else D3D12GpuTexture(
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or
                    GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or D3D12GpuTexture.USAGE_STORAGE,
                "World reconstruction output", color.format, frame.displayWidth, frame.displayHeight, 1, 1,
            ).also {
                reconstructionOutput = it
                previous?.close()
            }
        } else null
        val exposureTarget = if (provider) exposure ?: D3D12GpuTexture(
            GpuTexture.USAGE_TEXTURE_BINDING or D3D12GpuTexture.USAGE_STORAGE,
            "World output exposure", GpuFormat.R32_FLOAT, 1, 1, 1, 1,
        ).also { exposure = it } else null
        val tracked = timing.writeGpuFront(encoder)
        val started = System.nanoTime()
        try {
            encoder.recordCommands { context ->
                Arena.ofConfined().use { arena ->
                    val layout = MCDRLayout.WORLD_OUTPUT_INPUT
                    val input = arena.allocate(layout)
                    input.set(MCDRLayout.I32, layout.byteOffset(groupElement("schemaVersion")), 3)
                    input.set(MCDRLayout.I32, layout.byteOffset(groupElement("structSize")), layout.byteSize().toInt())
                    input.set(MCDRLayout.I32, layout.byteOffset(groupElement("sourceStage")), sourceStage.wire)
                    input.set(MCDRLayout.F32, layout.byteOffset(groupElement("dlssPostSharpness")), frame.settings.dlssPostSharpness)
                    val identity = input.asSlice(layout.byteOffset(groupElement("identity")), MCDRLayout.FRAME_IDENTITY.byteSize())
                    for ((name, value) in listOf("worldEpoch" to frame.identity.worldEpoch,
                        "realFrameId" to frame.identity.realFrameId, "configVersion" to frame.identity.configVersion,
                        "resourceVersion" to frame.identity.resourceVersion)) {
                        identity.set(MCDRLayout.I64, MCDRLayout.FRAME_IDENTITY.byteOffset(groupElement(name)), value)
                    }
                    val motion = temporal?.motion?.handle() ?: D3D12Velocity.currentHandle()
                    input.set(MCDRLayout.I32, layout.byteOffset(groupElement("motionCoverage")),
                        VanillaFrameWire.motionCoverage(D3D12Velocity.motionCoverage()))
                    input.set(MCDRLayout.I32, layout.byteOffset(groupElement("motionGuideCoverage")),
                        VanillaFrameWire.guideCoverage(temporal?.guideCoverage ?: D3D12Velocity.motionGuideCoverage()))
                    for ((name, handle) in listOf("color" to color.handle(), "depth" to depth.handle(),
                        "motion" to motion, "exposure" to (exposureTarget?.handle() ?: 0L),
                        "reconstructionOutput" to (output?.handle() ?: 0L), "scene" to scene.handle())) {
                        if (handle == 0L) continue
                        val binding = input.asSlice(layout.byteOffset(groupElement(name)), MCDRLayout.PREPARED_BINDING.byteSize())
                        binding.set(MCDRLayout.PTR, MCDRLayout.PREPARED_BINDING.byteOffset(groupElement("object")),
                            MemorySegment.ofAddress(handle))
                        binding.set(MCDRLayout.I32, MCDRLayout.PREPARED_BINDING.byteOffset(groupElement("subresourceCount")), 1)
                    }
                    if (provider && frame.settings.providerMasks &&
                        frame.previous != null && motion != 0L) {
                        val masks = input.asSlice(layout.byteOffset(groupElement("masks")), MCDRLayout.PROVIDER_MASK_INPUT.byteSize())
                        D3D12SrMask.writeWorldOutputInputs(encoder, masks, frame, color, depth, motion)
                    }
                    check(MCDR_ABI.D3D12VanillaPrepareWorldOutput(context, input) == 1) {
                        "World output failed for ${frame.identity}: ${MCDR_ABI.D3D12LastError()}"
                    }
                }
            }
        } finally {
            timing.writeGpuBack(encoder, tracked)
        }
        timing.recordCost(System.nanoTime() - started, 0)
        val lens = frame.current.perspective
        val providerCameraValid = lens != null && lens.nearPlane.isFinite() && lens.farPlane.isFinite() &&
            lens.verticalFovRadians.isFinite() && lens.nearPlane > 0f && lens.farPlane > lens.nearPlane &&
            lens.verticalFovRadians > 0f && lens.verticalFovRadians < Math.PI
        val motion = temporal?.motion?.handle() ?: D3D12Velocity.currentHandle()
        return if (frame.previous != null && motion != 0L &&
            (provider && providerCameraValid || !provider && frame.settings.antiAliasing == 2))
            SamplingGrid.TEMPORAL_DISPLAY else SamplingGrid.CURRENT_RASTER
    }

    override fun close() {
        reconstructionOutput?.close()
        reconstructionOutput = null
        exposure?.close()
        exposure = null
        owner = null
    }
}
