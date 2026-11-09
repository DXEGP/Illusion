package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.core.IdMap
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.chunk.HashMapPalette
import net.minecraft.world.level.chunk.LinearPalette
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.SingleValuePalette
import org.spongepowered.asm.mixin.Mixin

@Mixin(SingleValuePalette::class, LinearPalette::class, HashMapPalette::class)
abstract class PaletteEntryPublicationMixin<Value : Any> {
    @WrapMethod(method = ["read"])
    private fun `dxr_mc$read`(buffer: FriendlyByteBuf, registry: IdMap<Value>, original: Operation<Void?>) {
        D3D12SectionGeometry.beginPaletteEntryDecode((this as Palette<*>), registry).use { publication ->
            original.call(buffer, registry)
            publication.complete()
        }
    }

    @WrapMethod(method = ["copy"])
    private fun `dxr_mc$copy`(original: Operation<Palette<Value>>): Palette<Value> {
        val copy = D3D12SectionGeometry.beginPaletteEntryCopy(this as Palette<*>)
        val result = original.call()
        D3D12SectionGeometry.finishPaletteEntryCopy(copy, result)
        return result
    }
}

