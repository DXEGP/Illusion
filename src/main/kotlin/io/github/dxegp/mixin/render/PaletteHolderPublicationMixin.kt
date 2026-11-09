package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps
import net.minecraft.core.Holder
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(Holder.Reference::class)
abstract class PaletteHolderPublicationMixin {
    @WrapOperation(method = ["bindValue"], at = [At(value = "FIELD",
            target = "Lnet/minecraft/core/Holder\$Reference;value:Ljava/lang/Object;", opcode = 181)])
    private fun `dxr_mc$value`(holder: Holder.Reference<*>, value: Any?, original: Operation<Void?>) {
        D3D12RtIdMaps.writeHolder(holder, value) { original.call(holder, value) }
    }
}

