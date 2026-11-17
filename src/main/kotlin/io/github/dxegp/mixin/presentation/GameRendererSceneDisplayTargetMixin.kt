package io.github.dxegp.mixin.presentation

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.injector.ModifyReturnValue
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.render.presentation.D3D12GUISplit
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.presentation.LateWorldTargets
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.PostChain
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg

@Mixin(GameRenderer::class)
abstract class GameRendererSceneDisplayTargetMixin {
    @ModifyReturnValue(method = ["mainRenderTarget"], at = [At("RETURN")], remap = false)
    private fun illusionExternalLateWorldTarget(original: RenderTarget): RenderTarget =
        LateWorldTargets.externalTarget(original)

    @WrapOperation(
        method = ["renderLevel"],
        at = [At(value = "INVOKE", target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;")],
        remap = false,
    )
    private fun illusionRecordAfterLevel(bus: IEventBus, event: Event, original: Operation<Event>): Event =
        if (event is RenderLevelStageEvent.AfterLevel)
            LateWorldTargets.recordExternalDraw { original.call(bus, event) } else original.call(bus, event)

    @ModifyArg(
        method = ["render"],
        at = [At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V")],
        index = 0,
        remap = false,
    )
    private fun illusionGuiDepth(original: GpuTexture): GpuTexture =
        checkNotNull(D3D12GUISplit.targetForFrame().getDepthTexture())

    @ModifyExpressionValue(
        method = ["renderItemInHand", "render3dHud", "integrate3DHudDepth", "applyPostEffects", "takeAutoScreenshot"],
        at = [At(value = "FIELD", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;", opcode = Opcodes.GETFIELD)],
        remap = false,
    )
    private fun illusionSceneTarget(original: RenderTarget): RenderTarget =
        D3D12SceneDisplay.targetForFrame() ?: original

    @WrapOperation(
        method = ["applyPostEffects"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;process(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;)V")],
        remap = false,
    )
    private fun illusionRecordScenePostEffect(
        effect: PostChain,
        target: RenderTarget,
        allocator: GraphicsResourceAllocator,
        operation: Operation<Any?>,
    ) {
        try {
            operation.call(effect, target, allocator)
        } catch (failure: RuntimeException) {
            D3D12SceneDisplay.recordPostFailure(effect)
            throw failure
        }
        D3D12SceneDisplay.recordPostEffect(effect)
    }
}
