package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.mojang.blaze3d.pipeline.RenderTarget
import io.github.dxegp.d3d12.render.advanced.smaa.D3D12GUISplit
import net.minecraft.client.renderer.GameRenderer
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(GameRenderer::class)
abstract class GameRendererBlurTargetMixin {
    @ModifyExpressionValue(method = ["processBlurEffect"], at = [At(value = "FIELD", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;", opcode = Opcodes.GETFIELD)], remap = false)
    private fun `dxr_mc$guiTargetForBlur`(original: RenderTarget): RenderTarget =
        D3D12GUISplit.targetForFrame() ?: original
}

