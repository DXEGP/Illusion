package io.github.dxegp.render.frameprep

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator

internal object D3D12Exposure : AutoCloseable {
    private var owner: D3D12CommandEncoderBackend? = null
    private var output: D3D12GpuTexture? = null

    fun produce(encoder: D3D12CommandEncoderBackend, color: D3D12GpuTexture): Long {
        check(owner == null || owner === encoder) { "Exposure changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val target = output ?: D3D12GpuTexture(
            GpuTexture.USAGE_TEXTURE_BINDING or D3D12GpuTexture.USAGE_STORAGE,
            "Reconstruction frame exposure", GpuFormat.R32_FLOAT, 1, 1, 1, 1,
        ).also { output = it }
        val encoding = when (color.format) {
            GpuFormat.RGBA8_UNORM -> 1
            else -> 0
        }
        return encoder.recordCommands { context ->
            val version = checkNotNull(VanillaFrameCoordinator.currentResourceVersion()) {
                "Exposure requires an active world frame"
            }
            val uses = listOf(
                MCDR_ABI.VanillaResourceUse(color.handle(), version, access = 1, requiredState = 0x40),
                MCDR_ABI.VanillaResourceUse(target.handle(), version, access = 4,
                    requiredState = 0x8, finalState = 0x40),
            )
            VanillaFrameCoordinator.declaredPass(context, 1 shl 17, uses) {
                MCDR_ABI.D3D12ProduceExposure(context, color.handle(), target.handle(),
                    color.getWidth(0), color.getHeight(0), encoding, FrameInputCoordinates.PRE_EXPOSURE)
            }
            target.handle()
        }
    }

    override fun close() {
        output?.close()
        output = null
        owner = null
    }
}
