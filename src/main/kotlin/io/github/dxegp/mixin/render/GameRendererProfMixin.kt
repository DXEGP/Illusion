package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import net.minecraft.client.DeltaTracker
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
abstract class GameRendererProfMixin {
    @Inject(method = ["extract"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfExtractHead(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.EXTRACT)
    }

    @Inject(method = ["extract"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfExtractReturn(deltaTracker: DeltaTracker?, advanceGameTime: Boolean, ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.EXTRACT)
    }

    @Inject(method = ["render"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfRenderHead(ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.RENDER)
    }

    @Inject(method = ["render"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfRenderReturn(ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.RENDER)
    }

    @Inject(method = ["renderLevel"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfRenderLevelHead(ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.WORLD)
    }

    @Inject(method = ["renderLevel"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfRenderLevelReturn(ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.WORLD)
    }
}

