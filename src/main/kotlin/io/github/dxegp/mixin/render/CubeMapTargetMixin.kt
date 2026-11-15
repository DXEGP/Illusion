package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.mojang.blaze3d.pipeline.RenderTarget
import io.github.dxegp.d3d12.render.advanced.smaa.D3D12GUISplit
import net.minecraft.client.renderer.CubeMap
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(CubeMap::class)
abstract class CubeMapTargetMixin {
    @ModifyExpressionValue(method = ["render"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")], remap = false)
    private fun `dxr_mc$guiTargetForPanorama`(original: RenderTarget): RenderTarget =
        D3D12GUISplit.targetForFrame() ?: original
}

