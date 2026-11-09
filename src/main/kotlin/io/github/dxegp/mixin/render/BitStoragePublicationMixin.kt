package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteArrays
import net.minecraft.util.BitStorage
import net.minecraft.util.SimpleBitStorage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(SimpleBitStorage::class)
abstract class BitStoragePublicationMixin {
    @Inject(method = ["<init>(II[J)V"], at = [At("RETURN")])
    private fun `dxr_mc$constructed`(bits: Int, size: Int, supplied: LongArray?, callback: CallbackInfo) {
        D3D12RtPaletteArrays.constructed((this as BitStorage), supplied)
    }

    @WrapMethod(method = ["getRaw"])
    private fun `dxr_mc$exposeArray`(original: Operation<LongArray>): LongArray {
        D3D12RtPaletteArrays.expose(this as BitStorage)
        return original.call()
    }

    @WrapMethod(method = ["set"])
    private fun `dxr_mc$publishSet`(index: Int, value: Int, original: Operation<Void?>) {
        D3D12SectionGeometry.beginPaletteStorageWrite((this as BitStorage), false).use { publication ->
            original.call(index, value)
            publication.complete()
        }
    }

    @WrapMethod(method = ["getAndSet"])
    private fun `dxr_mc$publishExchange`(index: Int, value: Int, original: Operation<Int>): Int {
        D3D12SectionGeometry.beginPaletteStorageWrite((this as BitStorage), false).use { publication ->
            val previous = original.call(index, value)
            publication.complete()
            return previous
        }
    }

    @WrapMethod(method = ["fill"])
    private fun `dxr_mc$publishFill`(value: Int, original: Operation<Void?>) {
        D3D12SectionGeometry.beginPaletteStorageWrite((this as BitStorage), true).use { publication ->
            original.call(value)
            publication.complete()
        }
    }

    @WrapMethod(method = ["copy"])
    private fun `dxr_mc$copyContent`(original: Operation<BitStorage>): BitStorage {
        val copy = D3D12SectionGeometry.beginPaletteStorageCopy(this as BitStorage)
        val result = original.call()
        D3D12SectionGeometry.finishPaletteStorageCopy(copy, result)
        return result
    }
}

