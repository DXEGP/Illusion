package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps
import it.unimi.dsi.fastutil.objects.ObjectList
import it.unimi.dsi.fastutil.objects.Reference2IntMap
import net.minecraft.core.Holder
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.resources.ResourceKey
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(MappedRegistry::class)
abstract class RegistryIdMapPublicationMixin<Value : Any> {
    @WrapMethod(method = ["register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder\$Reference;"])
    private fun `dxr_mc$register`(
        id: Int, key: ResourceKey<Value>, value: Value, info: RegistrationInfo,
        original: Operation<Holder.Reference<Value>>
    ): Holder.Reference<Value> {
        D3D12RtIdMaps.writing(this, false).use { write ->
            val result = original.call(id, key, value, info)
            write.complete()
            return result
        }
    }

    @WrapMethod(method = ["registerIdMapping"])
    private fun `dxr_mc$map`(key: ResourceKey<Value>, id: Int, original: Operation<Void?>) {
        D3D12RtIdMaps.writing(this, false).use { write ->
            original.call(key, id)
            write.complete()
        }
    }

    @WrapMethod(method = ["clear"])
    private fun `dxr_mc$clear`(full: Boolean, original: Operation<Void?>) {
        D3D12RtIdMaps.writing(this, true).use { write ->
            original.call(full)
            write.complete()
        }
    }

    @WrapMethod(method = ["iterator"])
    private fun `dxr_mc$iterator`(original: Operation<MutableIterator<Value>>): MutableIterator<Value> {
        return D3D12RtIdMaps.iterator((this as MappedRegistry<Value>), original.call())
    }

    @WrapOperation(method = ["register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder\$Reference;", "registerIdMapping"],
            at = [At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectList;add(Ljava/lang/Object;)Z")])
    private fun `dxr_mc$append`(
        values: ObjectList<Holder.Reference<Value>>, value: Any?, original: Operation<Boolean>
    ): Boolean {
        return D3D12RtIdMaps.append(this, value) { original.call(values, value) }
    }

    @WrapOperation(method = ["registerIdMapping"], at = [At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectList;set(ILjava/lang/Object;)Ljava/lang/Object;")])
    private fun `dxr_mc$slot`(
        values: ObjectList<Holder.Reference<Value>>, id: Int, value: Any?, original: Operation<Any?>
    ): Any? {
        return D3D12RtIdMaps.writeSlot(this, id, value) { original.call(values, id, value) }
    }

    @WrapOperation(method = ["register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder\$Reference;", "registerIdMapping"],
            at = [At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;put(Ljava/lang/Object;I)I")])
    private fun `dxr_mc$reverse`(ids: Reference2IntMap<Value>, value: Value, id: Int, original: Operation<Int>): Int {
        return D3D12RtIdMaps.writeReverse(this, value, id) { original.call(ids, value, id) }
    }

    @WrapOperation(method = ["clear"], at = [At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectList;clear()V")])
    private fun `dxr_mc$values`(values: ObjectList<*>, original: Operation<Void?>) {
        D3D12RtIdMaps.clear(this) { original.call(values) }
    }

    @WrapOperation(method = ["clear"], at = [At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;clear()V")])
    private fun `dxr_mc$ids`(ids: Reference2IntMap<*>, original: Operation<Void?>) {
        D3D12RtIdMaps.clear(this) { original.call(ids) }
    }
}

