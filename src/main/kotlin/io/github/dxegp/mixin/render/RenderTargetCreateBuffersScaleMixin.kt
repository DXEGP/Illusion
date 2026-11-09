package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12RenderScale
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import java.util.function.Supplier

@Mixin(RenderTarget::class)
abstract class RenderTargetCreateBuffersScaleMixin {
    @WrapOperation(method = ["createBuffers"], at = [At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuDevice;" +
                    "createTexture(Ljava/util/function/Supplier;" +
                    "ILcom/mojang/renderpearl/api/GpuFormat;" +
                    "IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;")])
    private fun `dxr_mc$scaleBuffersTexture`(
        device: GpuDevice, label: Supplier<String>, usage: Int, format: GpuFormat, width: Int, height: Int,
        depthOrLayers: Int, mipLevels: Int, original: Operation<GpuTexture>
    ): GpuTexture {
        return original.call(device, label, usage, format,
                width,
                height,
                depthOrLayers, mipLevels)
    }

    @Unique private fun `dxr_mc$scaleBuffersDimension`(value: Int): Int {
        return D3D12RenderScale.scaledForAttachment(value)
    }
}

