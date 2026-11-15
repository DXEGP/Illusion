package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.extern.MCDR_ABI
import io.github.dxegp.d3d12.render.advanced.smaa.D3D12GUISplit
import io.github.dxegp.d3d12.render.advanced.smaa.D3D12SMAAWorld
import io.github.dxegp.d3d12.render.advanced.taa.D3D12TAA
import io.github.dxegp.experimental.superresolution.dlss.DeepLearningSuperSampling
import io.github.dxegp.experimental.superresolution.SuperResolution
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
abstract class GameRendererSmaaWorldMixin {
    @Inject(
        method = ["render"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V")],
        remap = false
    )
    private fun `dxr_mc$smaaWorldOnly`(callback: CallbackInfo) {
        D3D12TAA.applyIfEnabled()
        D3D12SMAAWorld.applyIfEnabled()
        SuperResolution.applyIfEnabled()
        DeepLearningSuperSampling.pclRenderSubmitStart()
        D3D12GUISplit.prepareForGui()
    }

    @Inject(method = ["render"], at = [At("HEAD")], remap = false)
    private fun `dxr_mc$dlssFrameBegin`(callback: CallbackInfo) {
        if (SuperResolution.backendId() != MCDR_ABI.SR_BACKEND_DLSS) return
        DeepLearningSuperSampling.pclFrameBegin()
        DeepLearningSuperSampling.reconcileAntiAliasingEveryFrame()
    }
}

