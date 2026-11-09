package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtColorTables
import net.minecraft.client.resources.DryFoliageColorReloadListener
import net.minecraft.world.level.DryFoliageColor
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(DryFoliageColorReloadListener::class)
abstract class DryFoliageColorReloadMixin {
    @WrapOperation(method = ["apply([ILnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"],
            at = [At(value = "INVOKE", target = "Lnet/minecraft/world/level/DryFoliageColor;init([I)V")])
    private fun `dxr_mc$publish`(pixels: IntArray, original: Operation<Void?>) {
        D3D12RtColorTables.reload(DryFoliageColor::class.java, pixels) { snapshot -> original.call(snapshot) }
    }
}

