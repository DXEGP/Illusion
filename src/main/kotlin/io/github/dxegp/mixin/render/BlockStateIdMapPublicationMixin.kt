package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps
import it.unimi.dsi.fastutil.objects.Reference2IntMap
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(targets = ["net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks\$BlockCallbacks\$ClearableObjectIntIdentityMap"], remap = false)
abstract class BlockStateIdMapPublicationMixin {
    @WrapMethod(method = ["clear"], remap = false) private fun `dxr_mc$clear`(original: Operation<Void?>) {
        D3D12RtIdMaps.writing(this, true).use { write ->
            original.call()
            write.complete()
        }
    }

    @WrapOperation(method = ["clear"], remap = false, at = [At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;clear()V", remap = false)])
    private fun `dxr_mc$reverse`(ids: Reference2IntMap<*>, original: Operation<Void?>) {
        D3D12RtIdMaps.clear(this) { original.call(ids) }
    }

    @WrapOperation(method = ["clear"], remap = false, at = [At(value = "INVOKE",
            target = "Ljava/util/List;clear()V", remap = false)])
    private fun `dxr_mc$values`(values: List<*>, original: Operation<Void?>) {
        D3D12RtIdMaps.clear(this) { original.call(values) }
    }
}

