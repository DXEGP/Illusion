package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import net.minecraft.client.color.block.BlockTintCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.biome.Biome
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ClientLevel::class)
abstract class ClientLevelTintReadMixin {
    @Inject(method = ["calculateBlockTint(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/ColorResolver;)I"],
            at = [At("HEAD")])
    private fun `dxr_mc$recordTintCalculator`(
        position: BlockPos, resolver: ColorResolver, callback: CallbackInfoReturnable<Int>
    ) {
        D3D12SectionGeometry.recordTintCalculatorRead((this as ClientLevel), resolver)
    }

    @Inject(method = ["getUncachedNoiseBiome(III)Lnet/minecraft/core/Holder;"], at = [At("HEAD")])
    private fun `dxr_mc$recordBiomeFallback`(
        quartX: Int, quartY: Int, quartZ: Int, callback: CallbackInfoReturnable<Holder<Biome>>
    ) {
        D3D12SectionGeometry.recordBiomeFallback()
    }

    @WrapOperation(method = ["getBlockTint"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockTintCache;getColor(Lnet/minecraft/core/BlockPos;)I")])
    private fun `dxr_mc$recordTintRead`(
        cache: BlockTintCache, position: BlockPos, original: Operation<Int>, requestedPosition: BlockPos,
        resolver: ColorResolver
    ): Int {
        D3D12SectionGeometry.recordTintCacheLookup((this as ClientLevel), cache, resolver)
        D3D12RtModelSupport.recordTintRead(cache, position)
        return original.call(cache, position)
    }
}

