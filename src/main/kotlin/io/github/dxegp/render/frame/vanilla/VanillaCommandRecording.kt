package io.github.dxegp.render.frame.vanilla

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.backend.api.RenderPassBackend
import io.github.dxegp.d3d12.command.D3D12CommandRecording
import io.github.dxegp.d3d12.command.D3D12CommandRecordingFactory
import io.github.dxegp.d3d12.command.D3D12CommandScope
import io.github.dxegp.d3d12.command.D3D12PreparedBufferUpload
import io.github.dxegp.d3d12.command.D3D12RenderPassBackend
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.render.compilation.VanillaPipelineSemantics
import io.github.dxegp.render.temporal.D3D12RasterMotion
import io.github.dxegp.render.temporal.D3D12Temporal
import io.github.dxegp.render.reconstruction.oit.OitFeatureUniforms
import io.github.dxegp.render.terrain.D3D12TerrainSampler
import io.github.dxegp.render.terrain.mesh.D3D12MeshletResidency
import java.nio.ByteBuffer

internal object VanillaCommandRecordingFactory : D3D12CommandRecordingFactory {
    override fun create(scope: D3D12CommandScope): D3D12CommandRecording = VanillaCommandRecording(scope)

    override fun close() {
        D3D12TerrainSampler.release()
    }
}

private class VanillaCommandRecording(private val scope: D3D12CommandScope) : D3D12CommandRecording {
    private var meshletResidency: D3D12MeshletResidency? = null
    private val rasterSemantics = VanillaPipelineSemantics()

    override fun prepareBufferUpload(destination: GpuBufferSlice, data: ByteBuffer): D3D12PreparedBufferUpload? {
        OitFeatureUniforms.prepareLightingUpload(destination, data)?.let { return it }
        if (!D3D12Temporal.isLevelProjectionUpload(destination, data)) return null
        try {
            val payload = checkNotNull(D3D12Temporal.interceptWrite(destination, data)) {
                "Level projection upload has no prepared payload"
            }
            return object : D3D12PreparedBufferUpload {
                override val payload: ByteBuffer = payload

                override fun confirmRecorded() {
                    val receipt = checkNotNull(D3D12Temporal.commitLevelProjectionUpload(destination, payload)) {
                        "Level projection upload has no recorded receipt"
                    }
                    VanillaFrameCoordinator.levelProjectionRecorded(receipt)
                }

                override fun discard() {
                    D3D12Temporal.discardLevelProjectionUpload(destination)
                }
            }
        } catch (failure: Throwable) {
            try {
                D3D12Temporal.discardLevelProjectionUpload(destination)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun beginRasterPass(context: Long): Long =
        if (scope == D3D12CommandScope.FRAME && VanillaFrameCoordinator.isRasterPassActive())
            MCDR_ABI.D3D12VanillaBeginRasterPass(context)
        else 0L

    override fun finishRasterPass(context: Long, token: Long) {
        if (token == 0L) return
        check(MCDR_ABI.D3D12VanillaFinishDeclaredPass(context, token)) {
            "Raster declaration commit failed: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    override fun failRasterPass(context: Long, token: Long) {
        if (token == 0L && (scope != D3D12CommandScope.FRAME || !VanillaFrameCoordinator.isRasterPassActive())) return
        check(MCDR_ABI.D3D12VanillaResetFrame(context) == 1) {
            "Raster declaration reset failed: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    override fun createRenderPass(
        device: D3D12Device?,
        context: Long,
        descriptor: RenderPassDescriptor,
        transientMemory: D3D12TransientMemory,
    ): RenderPassBackend {
        val colors = descriptor.colorAttachments()
        val firstColor = colors.firstOrNull()?.textureView()?.texture()?.let { it as D3D12GpuTexture }
        val depth = descriptor.depthAttachment()?.textureView()?.texture()?.let { it as D3D12GpuTexture }
        val colorHandle = firstColor?.handle() ?: 0L
        val depthHandle = depth?.handle() ?: 0L
        val hasDepth = depthHandle != 0L
        val area = descriptor.renderArea
        val residency = meshletResidency ?: D3D12MeshletResidency().also { meshletResidency = it }
        return D3D12RenderPassBackend(
            device, context, area, area.width(), area.height(), hasDepth, colorHandle,
            hasDepth && firstColor?.usage() == 13,
            VanillaRasterPassPolicy(context, transientMemory, residency, rasterSemantics),
            if (colors.size == 1) D3D12RasterMotion.attachment(colorHandle, depthHandle) else 0L,
        )
    }

    override fun close() {
        rasterSemantics.clear()
        meshletResidency?.close()
        meshletResidency = null
    }
}
