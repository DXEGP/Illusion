package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtLightArrays
import net.minecraft.world.level.chunk.DataLayer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(DataLayer::class)
abstract class LightLayerPublicationMixin {
    @Inject(method = ["<init>([B)V"], at = [At("RETURN")])
    private fun `dxr_mc$constructed`(bytes: ByteArray, callback: CallbackInfo) {
        D3D12RtLightArrays.constructed((this as DataLayer), bytes)
    }

    @WrapMethod(method = ["getData"])
    private fun `dxr_mc$expose`(original: Operation<ByteArray>): ByteArray {
        return D3D12RtLightArrays.read(this as DataLayer) { original.call() }
    }

    @WrapOperation(method = ["set(II)V"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/DataLayer;getData()[B")])
    private fun `dxr_mc$borrowSet`(layer: DataLayer, original: Operation<ByteArray>): ByteArray {
        D3D12RtLightArrays.borrow(layer).use { access ->
            return original.call(layer)
        }
    }

    @WrapOperation(method = ["copy"], at = [At(value = "NEW",
            target = "([B)Lnet/minecraft/world/level/chunk/DataLayer;")])
    private fun `dxr_mc$ownCopy`(bytes: ByteArray, original: Operation<DataLayer>): DataLayer {
        val result = original.call(bytes)
        D3D12RtLightArrays.own(result, bytes)
        return result
    }

    @WrapMethod(method = ["copy"])
    private fun `dxr_mc$copy`(original: Operation<DataLayer>): DataLayer {
        val copy = D3D12SectionGeometry.beginLightLayerCopy(this as DataLayer)
        val result = original.call()
        D3D12SectionGeometry.finishLightLayerCopy(copy, result)
        return result
    }

    @WrapMethod(method = ["set(IIII)V"])
    private fun `dxr_mc$publishSet`(blockX: Int, blockY: Int, blockZ: Int, value: Int, original: Operation<Void?>) {
        D3D12SectionGeometry.beginLightLayerPublication((this as DataLayer), false).use { publication ->
            original.call(blockX, blockY, blockZ, value)
            publication?.complete()
        }
    }

    @WrapMethod(method = ["fill(I)V"])
    private fun `dxr_mc$publishFill`(value: Int, original: Operation<Void?>) {
        D3D12SectionGeometry.beginLightLayerPublication((this as DataLayer), true).use { publication ->
            original.call(value)
            publication?.complete()
        }
    }
}

