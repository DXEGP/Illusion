package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.OptionInstance
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(OptionInstance::class)
abstract class BiomeBlendOptionPublicationMixin {
    @WrapOperation(method = ["set"], at = [At(value = "FIELD",
            target = "Lnet/minecraft/client/OptionInstance;value:Ljava/lang/Object;", opcode = Opcodes.PUTFIELD)])
    private fun `dxr_mc$publishBiomeBlend`(option: OptionInstance<*>, value: Any?, original: Operation<Void?>) {
        D3D12SectionGeometry.publishBiomeBlend(option, value) { original.call(option, value) }
    }
}

