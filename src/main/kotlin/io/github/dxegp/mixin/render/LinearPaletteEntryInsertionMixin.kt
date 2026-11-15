package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteEntries
import net.minecraft.world.level.chunk.LinearPalette
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.PaletteResize
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(LinearPalette::class)
abstract class LinearPaletteEntryInsertionMixin<Value : Any> {
    @WrapMethod(method = ["idFor"])
    private fun `dxr_mc$insert`(value: Value, resize: PaletteResize<Value>, original: Operation<Int>): Int {
        D3D12RtPaletteEntries.insertion(this as Palette<*>).use { insertion ->
            val result = original.call(value, resize)
            insertion.complete()
            return result
        }
    }

    @WrapOperation(method = ["idFor"], at = [At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 2,
            target = "Lnet/minecraft/world/level/chunk/LinearPalette;values:[Ljava/lang/Object;")])
    private fun `dxr_mc$beginWrite`(entries: LinearPalette<Value>, original: Operation<Array<Value>>): Array<Value> {
        D3D12RtPaletteEntries.writeInsertion(entries)
        return original.call(entries)
    }
}

