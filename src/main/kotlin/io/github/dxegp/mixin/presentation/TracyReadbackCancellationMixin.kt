package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.TracyFrameCapture
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12ReadbackCompletion
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.diagnostics.IllusionLogger
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At

@Mixin(TracyFrameCapture::class)
abstract class TracyReadbackCancellationMixin {
    @Shadow
    private lateinit var status: TracyFrameCapture.Status

    @WrapOperation(
        method = ["capture"],
        at = [At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;I)V")],
        remap = false,
    )
    private fun illusionCancelTracyReadback(
        encoder: CommandEncoder,
        source: GpuTexture,
        destination: GpuBuffer,
        offset: Long,
        completion: Runnable,
        mipLevel: Int,
        operation: Operation<Any?>,
    ) {
        if (source !is D3D12GpuTexture) {
            operation.call(encoder, source, destination, offset, completion, mipLevel)
            return
        }
        val callback = D3D12ReadbackCompletion.cancellable(completion) { failure ->
            status = TracyFrameCapture.Status.WAITING_FOR_CAPTURE
            IllusionLogger.warnMessage("Tracy readback was cancelled: ${failure.message}")
        }
        operation.call(encoder, source, destination, offset, callback, mipLevel)
    }
}
