package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import io.github.dxegp.render.presentation.viewmodel.D3D12ViewmodelMotionCapture
import io.github.dxegp.render.temporal.D3D12ItemMotionCapture
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.SubmitNodeCollection
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState
import net.minecraft.client.renderer.state.level.PlayerRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(FirstPersonHandsAndItemsRenderer::class)
abstract class FirstPersonItemMotionCaptureMixin {
    @WrapMethod(method = ["submitArmWithItem"])
    private fun capture(player: PlayerRenderState, state: FirstPersonHandsAndItemsRenderState,
        partialTicks: Float, xRot: Float, hand: InteractionHand, attack: Float, stack: ItemStack,
        inverseHeight: Float, pose: PoseStack, collector: SubmitNodeCollector, light: Int,
        original: Operation<Any?>) {
        val avatar = player.avatarRenderState
        if (avatar == null) {
            original.call(player, state, partialTicks, xRot, hand, attack, stack, inverseHeight, pose, collector, light)
            return
        }
        val main = hand == InteractionHand.MAIN_HAND
        val arm = if (main) avatar.mainArm else avatar.mainArm.opposite
        val item = if (main) state.mainHandRenderState else state.offHandRenderState
        D3D12ItemMotionCapture.firstPerson(item, stack, arm) {
            original.call(player, state, partialTicks, xRot, hand, attack, stack, inverseHeight, pose, collector, light)
        }
    }
}

@Mixin(FirstPersonHandsAndItemsRenderer::class)
abstract class FirstPersonArmMotionCaptureMixin {
    @WrapMethod(method = ["renderPlayerHand"])
    private fun capture(pose: PoseStack, collector: SubmitNodeCollector, light: Int, arm: HumanoidArm,
        player: PlayerRenderState, original: Operation<Any?>) {
        D3D12ViewmodelMotionCapture.arm(player.avatarRenderState, arm) {
            original.call(pose, collector, light, arm, player)
        }
    }
}

@Mixin(AvatarRenderer::class)
abstract class FirstPersonArmModelMotionCaptureMixin {
    @WrapMethod(method = ["renderHand"])
    private fun capture(pose: PoseStack, collector: SubmitNodeCollector, light: Int, skin: Identifier,
        arm: ModelPart, sleeve: Boolean, original: Operation<Any?>) {
        D3D12ViewmodelMotionCapture.modelPart(arm) {
            original.call(pose, collector, light, skin, arm, sleeve)
        }
    }
}

@Mixin(ModelFeatureRenderer.Submit::class)
abstract class FirstPersonModelSubmitMotionMixin {
    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun capture(callback: CallbackInfo) {
        D3D12ViewmodelMotionCapture.submitted(this as Any as ModelFeatureRenderer.Submit<*>)
    }
}

@Mixin(CustomFeatureRenderer.Submit::class)
abstract class FirstPersonCustomSubmitMotionMixin {
    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun capture(callback: CallbackInfo) {
        D3D12ViewmodelMotionCapture.unsupportedSource()
    }
}

@Mixin(SubmitNodeCollection::class)
abstract class FirstPersonSpecialSubmitMotionMixin {
    @Inject(method = ["submitSpecial"], at = [At("HEAD")])
    private fun capture(callback: CallbackInfo) {
        D3D12ViewmodelMotionCapture.unsupportedSource()
    }
}
