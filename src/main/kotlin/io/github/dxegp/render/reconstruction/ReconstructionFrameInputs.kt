package io.github.dxegp.render.reconstruction

import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.temporal.D3D12Temporal
import io.github.dxegp.render.temporal.D3D12Velocity
import io.github.dxegp.render.frameprep.MotionCoverage
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import net.minecraft.client.Minecraft

internal object ReconstructionFrameInputs : AutoCloseable {
    data class CameraPose(
        val x: Double, val y: Double, val z: Double,
        val forwardX: Float, val forwardY: Float, val forwardZ: Float,
    )

    data class Snapshot(
        val frameTimeNanos: Long,
        val sequence: Long,
        val world: Any,
        val historyGeneration: Long,
        val depth: D3D12GpuTexture,
        val motionVectors: Long,
        val camera: CameraPose,
        val motionCoverage: MotionCoverage,
        val worldEpoch: Long,
        val realFrameId: Long,
        val resourceVersion: Long,
        val configVersion: Long,
    )

    private var owner: D3D12CommandEncoderBackend? = null
    private var depth: D3D12GpuTexture? = null
    private var snapshot: Snapshot? = null

    fun capture(encoder: D3D12CommandEncoderBackend, minecraft: Minecraft) {
        io.github.dxegp.render.vrs.D3D12ShadingRateImage.captureWorldDepth(encoder)
        if (current(minecraft) != null) return
        snapshot = null
        val world = minecraft.level ?: return
        check(owner == null || owner === encoder) { "Reconstruction inputs changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val source = minecraft.gameRenderer.mainRenderTarget().depthTexture as? D3D12GpuTexture ?: return
        if (source.isClosed()) return
        val width = source.getWidth(0)
        val height = source.getHeight(0)
        if (width <= 0 || height <= 0) return
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
        encoder.copyTextureToTexture(source, target, 0, 0, 0, 0, 0, width, height)
        val position = minecraft.gameRenderer.mainCamera().position()
        val view = D3D12Temporal.currentCameraView() ?: return
        val identity = VanillaFrameCoordinator.currentIdentity() ?: return
        snapshot = Snapshot(
            minecraft.frameTimeNs, D3D12Temporal.frameSequence, world, D3D12Temporal.historyGeneration,
            target, D3D12Velocity.currentHandle(),
            CameraPose(position.x, position.y, position.z, -view[2], -view[6], -view[10]),
            D3D12Velocity.motionCoverage(),
            identity.worldEpoch, identity.realFrameId, identity.resourceVersion,
            identity.configVersion,
        )
    }

    fun current(minecraft: Minecraft): Snapshot? = snapshot?.takeIf {
        val identity = VanillaFrameCoordinator.currentIdentity()
        it.frameTimeNanos == minecraft.frameTimeNs && it.world === minecraft.level &&
            identity != null && it.worldEpoch == identity.worldEpoch && it.realFrameId == identity.realFrameId &&
            it.resourceVersion == identity.resourceVersion &&
            it.configVersion == identity.configVersion &&
            it.historyGeneration == D3D12Temporal.historyGeneration && !it.depth.isClosed() &&
            it.motionVectors != 0L && it.motionVectors == D3D12Velocity.currentHandle()
    }

    fun invalidate() {
        snapshot = null
    }

    fun release() {
        invalidate()
        depth?.close()
        depth = null
    }

    override fun close() {
        release()
        owner = null
    }
}
