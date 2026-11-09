package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.MainTarget
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12RenderScale
import java.util.function.Supplier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(MainTarget::class)
abstract class MainTargetRenderScaleMixin {
    @WrapOperation(
        method = ["allocateColorAttachment", "allocateDepthAttachment"],
        at = [At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuDevice;" +
                "createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;" +
                "IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;")]
    )
    private fun `dxr_mc$scaleAttachmentTexture`(
        device: GpuDevice, label: Supplier<String>?, usage: Int, format: GpuFormat,
        width: Int, height: Int, depthOrLayers: Int, mipLevels: Int, original: Operation<GpuTexture>
    ): GpuTexture = original.call(
        device, label, usage, format,
        D3D12RenderScale.scaledForAttachment(width), D3D12RenderScale.scaledForAttachment(height),
        depthOrLayers, mipLevels
    )
}

