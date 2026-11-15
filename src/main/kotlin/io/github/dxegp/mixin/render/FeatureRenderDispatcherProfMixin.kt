package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(FeatureRenderDispatcher::class)
abstract class FeatureRenderDispatcherProfMixin {
    @Inject(method = ["prepareFrame"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfPrepareFeaturesHead(
        submitNodeStorage: SubmitNodeStorage?,
        cir: CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame>
    ) {
        D3D12RenderProf.begin(D3D12RenderProf.PREPARE_FEATURES)
    }

    @Inject(method = ["prepareFrame"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfPrepareFeaturesReturn(
        submitNodeStorage: SubmitNodeStorage?,
        cir: CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame>
    ) {
        D3D12RenderProf.end(D3D12RenderProf.PREPARE_FEATURES)
    }
}

