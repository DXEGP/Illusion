package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtBiomeInputs
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(value = [ModifiableBiomeInfo::class], remap = false)
abstract class BiomeInfoPublicationMixin {
    @WrapOperation(method = ["applyBiomeModifiers"], at = [At(value = "FIELD",
            target = "Lnet/neoforged/neoforge/common/world/ModifiableBiomeInfo;modifiedBiomeInfo:Lnet/neoforged/neoforge/common/world/ModifiableBiomeInfo\$BiomeInfo;",
            opcode = 181)])
    private fun `dxr_mc$info`(
        owner: ModifiableBiomeInfo, value: ModifiableBiomeInfo.BiomeInfo, original: Operation<Void?>
    ) {
        D3D12RtBiomeInputs.write(owner, value) { original.call(owner, value) }
    }

    @Inject(method = ["get", "getModifiedBiomeInfo"], at = [At("HEAD")])
    private fun `dxr_mc$read`(callback: CallbackInfoReturnable<ModifiableBiomeInfo.BiomeInfo>) {
        D3D12RtModelSupport.recordBiomeInfoRead(this as ModifiableBiomeInfo)
    }
}

