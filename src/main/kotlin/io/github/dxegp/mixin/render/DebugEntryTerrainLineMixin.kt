package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.debug.D3D12DebugInfo
import net.minecraft.client.gui.components.debug.DebugEntrySimplePerformanceImpactors
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(DebugEntrySimplePerformanceImpactors::class)
abstract class DebugEntryTerrainLineMixin {
    @WrapOperation(
        method = ["display"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/" +
            "DebugScreenDisplayer;addLine(Ljava/lang/String;)V")],
        remap = false
    )
    private fun `dxr_mc$appendMeshTag`(displayer: DebugScreenDisplayer, line: String, original: Operation<Void?>) {
        original.call(displayer, D3D12DebugInfo.terrainLine(line))
    }
}

