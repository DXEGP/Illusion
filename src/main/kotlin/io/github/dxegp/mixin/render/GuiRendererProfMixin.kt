package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import net.minecraft.client.gui.render.GuiRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GuiRenderer::class)
abstract class GuiRendererProfMixin {
    @Inject(method = ["render"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfGuiHead(ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.GUI)
    }

    @Inject(method = ["render"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfGuiReturn(ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.GUI)
    }
}

