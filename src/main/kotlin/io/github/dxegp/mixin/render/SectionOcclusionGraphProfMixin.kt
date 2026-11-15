package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(SectionOcclusionGraph::class)
abstract class SectionOcclusionGraphProfMixin {
    @Inject(method = ["update"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfOcclusionHead(
        camera: CameraRenderState?, fov: Int, chunkLoadingRenderState: ChunkLoadingRenderState?,
        ci: CallbackInfo
    ) {
        D3D12RenderProf.begin(D3D12RenderProf.OCCLUSION)
    }

    @Inject(method = ["update"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfOcclusionReturn(
        camera: CameraRenderState?, fov: Int, chunkLoadingRenderState: ChunkLoadingRenderState?,
        ci: CallbackInfo
    ) {
        D3D12RenderProf.end(D3D12RenderProf.OCCLUSION)
    }
}

