package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12ReadbackCompletion
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.presentation.D3D12GUISplit
import net.minecraft.client.Screenshot
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import java.io.File
import java.util.function.Consumer

@Mixin(Screenshot::class)
abstract class ScreenshotDisplayTargetMixin {
    private companion object {
        @JvmStatic
        @WrapOperation(
            method = ["takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V"],
            at = [At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;I)V")],
            remap = false,
        )
        private fun illusionOwnScreenshotReadback(
            encoder: CommandEncoder,
            source: GpuTexture,
            destination: GpuBuffer,
            offset: Long,
            completion: Runnable,
            mipLevel: Int,
            operation: Operation<Any?>,
        ) {
            val callback = if (source is D3D12GpuTexture)
                D3D12ReadbackCompletion.owned(destination, completion) else completion
            operation.call(encoder, source, destination, offset, callback, mipLevel)
        }

        @JvmStatic
        @WrapOperation(
            method = ["grab(Lnet/minecraft/client/Minecraft;Z)V"],
            at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V")],
            remap = false,
        )
        private fun illusionQueueDisplayScreenshot(
            directory: File,
            target: RenderTarget,
            callback: Consumer<Component>,
            operation: Operation<Any?>,
        ) {
            if (target.getColorTexture() is D3D12GpuTexture) {
                D3D12GUISplit.queueScreenshot(directory, callback)
            } else {
                operation.call(directory, target, callback)
            }
        }
    }
}
