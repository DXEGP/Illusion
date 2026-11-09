package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12RenderScale
import net.minecraft.client.renderer.LevelRenderer
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At

@Mixin(LevelRenderer::class)
abstract class LevelRendererWorldTargetsScaleMixin {
    @ModifyExpressionValue(
        method = ["render"],
        at = [At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I",
            opcode = Opcodes.GETFIELD)],
        remap = false
    )
    private fun `dxr_mc$worldTargetWidth`(original: Int): Int = D3D12RenderScale.worldLayerSize(original)

    @ModifyExpressionValue(
        method = ["render"],
        at = [At(value = "FIELD", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I",
            opcode = Opcodes.GETFIELD)],
        remap = false
    )
    private fun `dxr_mc$worldTargetHeight`(original: Int): Int = D3D12RenderScale.worldLayerSize(original)
}

