package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(SectionRenderDispatcher::class)
abstract class SectionRenderDispatcherProfMixin {
    @Inject(method = ["uploadTerrainBuffersToGpu"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfUploadTerrainHead(ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.UPLOAD_TERRAIN)
    }

    @Inject(method = ["uploadTerrainBuffersToGpu"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfUploadTerrainReturn(ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.UPLOAD_TERRAIN)
    }
}

