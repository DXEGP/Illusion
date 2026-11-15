package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.chunk.PalettedContainer
import org.spongepowered.asm.mixin.Mixin

@Mixin(PalettedContainer::class)
abstract class BlockPalettePublicationMixin<Value : Any> {
    @WrapMethod(method = ["getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;"])
    private fun `dxr_mc$publishGetAndSet`(
        localX: Int, localY: Int, localZ: Int, value: Value, original: Operation<Value>
    ): Value {
        D3D12SectionGeometry.beginBlockPalettePublication((this as PalettedContainer<*>), false).use { publication ->
            val result = original.call(localX, localY, localZ, value)
            publication?.complete()
            return result
        }
    }

    @WrapMethod(method = ["getAndSetUnchecked"])
    private fun `dxr_mc$publishUnchecked`(
        localX: Int, localY: Int, localZ: Int, value: Value, original: Operation<Value>
    ): Value {
        D3D12SectionGeometry.beginBlockPalettePublication((this as PalettedContainer<*>), false).use { publication ->
            val result = original.call(localX, localY, localZ, value)
            publication?.complete()
            return result
        }
    }

    @WrapMethod(method = ["set(IIILjava/lang/Object;)V"])
    private fun `dxr_mc$publishSet`(localX: Int, localY: Int, localZ: Int, value: Value, original: Operation<Void?>) {
        D3D12SectionGeometry.beginBlockPalettePublication((this as PalettedContainer<*>), false).use { publication ->
            original.call(localX, localY, localZ, value)
            publication?.complete()
        }
    }

    @WrapMethod(method = ["onResize"])
    private fun `dxr_mc$publishResize`(bits: Int, value: Value, original: Operation<Int>): Int {
        D3D12SectionGeometry.beginBlockPalettePublication((this as PalettedContainer<*>), false).use { publication ->
            val result = original.call(bits, value)
            publication?.complete()
            return result
        }
    }

    @WrapMethod(method = ["read"])
    private fun `dxr_mc$publishRead`(buffer: FriendlyByteBuf, original: Operation<Void?>) {
        D3D12SectionGeometry.beginBlockPalettePublication((this as PalettedContainer<*>), true).use { publication ->
            original.call(buffer)
            publication?.complete()
        }
    }
}

