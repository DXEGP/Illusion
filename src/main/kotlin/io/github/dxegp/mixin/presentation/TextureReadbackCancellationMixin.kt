package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Share
import com.llamalad7.mixinextras.sugar.ref.LocalRef
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12ReadbackCompletion
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(TextureUtil::class)
abstract class TextureReadbackCancellationMixin {
    private companion object {
        @JvmStatic
        @WrapOperation(
            method = ["writeAsPNG(Ljava/nio/file/Path;Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTexture;ILjava/util/function/IntUnaryOperator;)V"],
            at = [At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;I)V")],
            remap = false,
        )
        private fun illusionOwnTextureReadback(
            encoder: CommandEncoder,
            source: GpuTexture,
            destination: GpuBuffer,
            offset: Long,
            completion: Runnable,
            mipLevel: Int,
            operation: Operation<Any?>,
            @Share("illusionTextureReadbackCancellation") cancellation: LocalRef<D3D12ReadbackCompletion>,
        ) {
            if (source !is D3D12GpuTexture) {
                operation.call(encoder, source, destination, offset, completion, mipLevel)
                return
            }
            var owner = cancellation.get()
            if (owner == null) {
                owner = D3D12ReadbackCompletion.owned(destination, completion)
                cancellation.set(owner)
            }
            val cancelOwner = checkNotNull(owner)
            val callback = D3D12ReadbackCompletion.cancellable(completion, cancelOwner)
            operation.call(encoder, source, destination, offset, callback, mipLevel)
        }
    }
}
