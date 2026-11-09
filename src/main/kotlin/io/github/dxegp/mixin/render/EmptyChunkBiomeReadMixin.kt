package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.EmptyLevelChunk
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(EmptyLevelChunk::class)
abstract class EmptyChunkBiomeReadMixin {
    @Inject(method = ["getNoiseBiome(III)Lnet/minecraft/core/Holder;"], at = [At("HEAD")])
    private fun `dxr_mc$recordBiomeFallback`(
        quartX: Int, quartY: Int, quartZ: Int, callback: CallbackInfoReturnable<Holder<Biome>>
    ) {
        D3D12SectionGeometry.recordBiomeFallback()
    }
}

