package io.github.dxegp.mixin.temporal

import io.github.dxegp.render.temporal.D3D12ObjectMotion
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(EntityRenderDispatcher::class)
abstract class EntityMotionCaptureMixin {
    @Inject(method = ["extractEntity"], at = [At("RETURN")])
    private fun captureEntity(entity: Entity, partialTick: Float,
        callback: CallbackInfoReturnable<EntityRenderState>) {
        D3D12ObjectMotion.captureExtracted(entity, callback.returnValue)
    }
}
