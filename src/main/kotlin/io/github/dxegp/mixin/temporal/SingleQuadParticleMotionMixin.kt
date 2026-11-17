package io.github.dxegp.mixin.temporal

import io.github.dxegp.render.temporal.D3D12ParticleMotionCapture
import net.minecraft.client.particle.SingleQuadParticle
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique

@Mixin(SingleQuadParticle::class)
abstract class SingleQuadParticleMotionMixin : D3D12ParticleMotionCapture.Owner {
    @Unique
    private var illusionParticleLifetime: D3D12ParticleMotionCapture.Lifetime? = null

    override fun illusionParticleMotion(): D3D12ParticleMotionCapture.Lifetime =
        illusionParticleLifetime ?: D3D12ParticleMotionCapture.Lifetime().also { illusionParticleLifetime = it }
}
