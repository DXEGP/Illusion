package io.github.dxegp.mixin.debug

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(targets = ["net.minecraft.client.renderer.feature.FeatureRenderDispatcher\$PreparedFrame"])
abstract class PreparedFrameProfMixin {
    @Inject(method = ["executeSolid"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfExecFeaturesHead(ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.EXEC_FEATURES)
    }

    @Inject(method = ["executeSolid"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfExecFeaturesReturn(ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.EXEC_FEATURES)
    }
}

