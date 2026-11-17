package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.dxegp.render.temporal.D3D12ParticleMotionCapture
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(QuadParticleRenderState::class)
abstract class QuadParticleRenderStateMotionMixin {
    @Unique
    private val illusionParticlePacked = D3D12ParticleMotionCapture.PackedState()

    @Inject(method = ["add"], at = [At("RETURN")])
    private fun illusionAddParticle(layer: SingleQuadParticle.Layer,
        x: Float, y: Float, z: Float, xRot: Float, yRot: Float, zRot: Float, wRot: Float,
        scale: Float, u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int, light: Int, callback: CallbackInfo) {
        illusionParticlePacked.add(layer)
    }

    @Inject(method = ["clear"], at = [At("RETURN")])
    private fun illusionClearParticles(callback: CallbackInfo) {
        illusionParticlePacked.clear()
    }

    @WrapMethod(method = ["buildLayer"])
    private fun illusionBuildParticleLayer(layer: SingleQuadParticle.Layer, builder: VertexConsumer,
        original: Operation<Void?>) {
        illusionParticlePacked.build(layer) { original.call(layer, builder) }
    }

    @WrapMethod(method = ["renderRotatedQuad"])
    private fun illusionRenderParticleQuad(builder: VertexConsumer,
        x: Float, y: Float, z: Float, xRot: Float, yRot: Float, zRot: Float, wRot: Float,
        scale: Float, u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int, light: Int, original: Operation<Void?>) {
        illusionParticlePacked.quad {
            original.call(builder, x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, light)
        }
    }
}
