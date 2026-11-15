package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap
import net.minecraft.world.level.chunk.HashMapPalette
import net.minecraft.world.level.chunk.Palette
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(HashMapPalette::class)
abstract class HashPaletteEntryInsertionMixin<Value : Any> {
    @WrapOperation(method = ["idFor"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/util/CrudeIncrementalIntIdentityHashBiMap;add(Ljava/lang/Object;)I")])
    private fun `dxr_mc$append`(
        values: CrudeIncrementalIntIdentityHashBiMap<Value>, value: Value, original: Operation<Int>
    ): Int {
        D3D12SectionGeometry.beginPaletteEntryAppend(this as Palette<*>).use { publication ->
            val result = original.call(values, value)
            publication.complete()
            return result
        }
    }
}

