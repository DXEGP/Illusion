package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.mojang.blaze3d.pipeline.RenderTarget
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.presentation.LateWorldTargets
import net.minecraft.client.renderer.LevelRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(LevelRenderer::class)
abstract class LevelRendererSceneDisplayTargetMixin {
    @ModifyExpressionValue(
        method = ["blitEntityOutline"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")],
        remap = false,
    )
    private fun illusionOutlineSceneTarget(original: RenderTarget): RenderTarget =
        D3D12SceneDisplay.targetForFrame() ?: original

    @ModifyExpressionValue(
        method = ["executeAlwaysOnTop"],
        at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/resource/ResourceHandle;get()Ljava/lang/Object;")],
        remap = false,
    )
    private fun illusionAlwaysOnTopDepth(original: Any): Any =
        LateWorldTargets.routeAlwaysOnTopDepth(original as RenderTarget)
}
