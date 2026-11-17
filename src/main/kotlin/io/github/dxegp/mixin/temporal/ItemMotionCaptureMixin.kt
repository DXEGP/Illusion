package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import io.github.dxegp.render.temporal.D3D12ItemMotionCapture
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.SubmitNodeCollection
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.resources.model.geometry.ItemQuads
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ItemInHandLayer::class)
abstract class ItemInHandMotionCaptureMixin {
    @WrapMethod(method = ["submitArmWithItem"])
    private fun capture(state: ArmedEntityRenderState, item: ItemStackRenderState, stack: ItemStack,
        arm: HumanoidArm, pose: PoseStack, collector: SubmitNodeCollector, light: Int,
        original: Operation<Any?>) {
        D3D12ItemMotionCapture.held(state, item, stack, arm, "hand") {
            original.call(state, item, stack, arm, pose, collector, light)
        }
    }
}

@Mixin(PlayerItemInHandLayer::class)
abstract class PlayerItemInHandMotionCaptureMixin {
    @WrapMethod(method = ["renderItemHeldToEye"])
    private fun capture(state: AvatarRenderState, arm: HumanoidArm, pose: PoseStack,
        collector: SubmitNodeCollector, light: Int, original: Operation<Any?>) {
        val stack = if (arm == HumanoidArm.RIGHT) state.rightHandItemStack else state.leftHandItemStack
        D3D12ItemMotionCapture.held(state, state.heldOnHead, stack, arm, "eye") {
            original.call(state, arm, pose, collector, light)
        }
    }
}

@Mixin(ItemStackRenderState::class)
abstract class ItemStackMotionCaptureMixin {
    @WrapMethod(method = ["submit"])
    private fun capture(pose: PoseStack, collector: SubmitNodeCollector, light: Int,
        overlay: Int, outline: Int, original: Operation<Any?>) {
        D3D12ItemMotionCapture.stack(this as Any as ItemStackRenderState) {
            original.call(pose, collector, light, overlay, outline)
        }
    }
}

@Mixin(ItemStackRenderState.LayerRenderState::class)
abstract class ItemLayerMotionCaptureMixin {
    @WrapMethod(method = ["submit"])
    private fun capture(pose: PoseStack, collector: SubmitNodeCollector, light: Int,
        overlay: Int, outline: Int, original: Operation<Any?>) {
        D3D12ItemMotionCapture.layer { original.call(pose, collector, light, overlay, outline) }
    }
}

@Mixin(SubmitNodeCollection::class)
abstract class ItemSubmitCollectionMotionCaptureMixin {
    @WrapMethod(method = ["submitItem"])
    private fun capture(pose: PoseStack, context: ItemDisplayContext, light: Int,
        overlay: Int, outline: Int, tints: IntArray, quads: ItemQuads,
        foil: ItemStackRenderState.FoilType, original: Operation<Any?>) {
        D3D12ItemMotionCapture.collection {
            original.call(pose, context, light, overlay, outline, tints, quads, foil)
        }
    }
}

@Mixin(ItemFeatureRenderer.Submit::class)
abstract class ItemSubmitMotionCaptureMixin {
    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun capture(callback: CallbackInfo) {
        D3D12ItemMotionCapture.submitted(this as Any as ItemFeatureRenderer.Submit)
    }
}

@Mixin(ItemFeatureRenderer::class)
abstract class ItemFeatureMotionCaptureMixin {
    @WrapMethod(method = ["prepareSubmit"])
    private fun capture(submit: ItemFeatureRenderer.Submit, original: Operation<Any?>) {
        D3D12ItemMotionCapture.prepare(submit) { original.call(submit) }
    }
}
