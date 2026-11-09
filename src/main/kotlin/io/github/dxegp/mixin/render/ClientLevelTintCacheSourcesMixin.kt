package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.color.block.BlockTintCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ColorResolver
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import java.util.function.ToIntFunction

@Mixin(ClientLevel::class)
abstract class ClientLevelTintCacheSourcesMixin {
    @WrapOperation(method = ["lambda\$new\$0"], at = [At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 0)])
    private fun `dxr_mc$ownGrass`(
        source: ToIntFunction<BlockPos>, original: Operation<BlockTintCache>
    ): BlockTintCache {
        return `dxr_mc$own`(source, BiomeColors.GRASS_COLOR_RESOLVER, original)
    }

    @WrapOperation(method = ["lambda\$new\$0"], at = [At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 1)])
    private fun `dxr_mc$ownFoliage`(
        source: ToIntFunction<BlockPos>, original: Operation<BlockTintCache>
    ): BlockTintCache {
        return `dxr_mc$own`(source, BiomeColors.FOLIAGE_COLOR_RESOLVER, original)
    }

    @WrapOperation(method = ["lambda\$new\$0"], at = [At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 2)])
    private fun `dxr_mc$ownDryFoliage`(
        source: ToIntFunction<BlockPos>, original: Operation<BlockTintCache>
    ): BlockTintCache {
        return `dxr_mc$own`(source, BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER, original)
    }

    @WrapOperation(method = ["lambda\$new\$0"], at = [At(value = "NEW",
            target = "(Ljava/util/function/ToIntFunction;)Lnet/minecraft/client/color/block/BlockTintCache;", ordinal = 3)])
    private fun `dxr_mc$ownWater`(
        source: ToIntFunction<BlockPos>, original: Operation<BlockTintCache>
    ): BlockTintCache {
        return `dxr_mc$own`(source, BiomeColors.WATER_COLOR_RESOLVER, original)
    }

    @Unique
    private fun `dxr_mc$own`(
        source: ToIntFunction<BlockPos>, resolver: ColorResolver, original: Operation<BlockTintCache>
    ): BlockTintCache {
        val cache = original.call(source)
        D3D12SectionGeometry.ownTintCacheSource((this as ClientLevel), cache, source, resolver)
        return cache
    }
}

