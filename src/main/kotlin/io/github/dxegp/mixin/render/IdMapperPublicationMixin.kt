package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps
import it.unimi.dsi.fastutil.objects.Reference2IntMap
import net.minecraft.core.IdMapper
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(IdMapper::class)
abstract class IdMapperPublicationMixin<Value : Any> {
    @WrapMethod(method = ["addMapping"])
    private fun `dxr_mc$write`(value: Value, id: Int, original: Operation<Void?>) {
        D3D12RtIdMaps.writing(this, false).use { write ->
            original.call(value, id)
            write.complete()
        }
    }

    @WrapOperation(method = ["addMapping"], at = [At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;put(Ljava/lang/Object;I)I")])
    private fun `dxr_mc$reverse`(ids: Reference2IntMap<Value>, value: Value, id: Int, original: Operation<Int>): Int {
        return D3D12RtIdMaps.writeReverse(this, value, id) { original.call(ids, value, id) }
    }

    @WrapOperation(method = ["addMapping"], at = [At(value = "INVOKE",
            target = "Ljava/util/List;add(Ljava/lang/Object;)Z")])
    private fun `dxr_mc$grow`(values: List<Value>, value: Value, original: Operation<Boolean>): Boolean {
        return D3D12RtIdMaps.append(this, value) { original.call(values, value) }
    }

    @WrapOperation(method = ["addMapping"], at = [At(value = "INVOKE",
            target = "Ljava/util/List;set(ILjava/lang/Object;)Ljava/lang/Object;")])
    private fun `dxr_mc$slot`(values: List<Value>, id: Int, value: Value, original: Operation<Value>): Any? {
        return D3D12RtIdMaps.writeSlot(this, id, value) { original.call(values, id, value) }
    }
}

