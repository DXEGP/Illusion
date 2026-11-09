package io.github.dxegp.mixin.render

import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12ShadingRateImage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(FrameGraphBuilder::class)
abstract class FrameGraphBuilderProfMixin {
    @Inject(method = ["execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
            "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder\$Inspector;)V"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfExecFrameGraphHead(
        resourceAllocator: GraphicsResourceAllocator?, inspector: FrameGraphBuilder.Inspector?,
        ci: CallbackInfo
    ) {
        D3D12ShadingRateImage.prepareFrame()
        D3D12RenderProf.begin(D3D12RenderProf.EXECUTE_FRAMEGRAPH)
    }

    @Inject(method = ["execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
            "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder\$Inspector;)V"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfExecFrameGraphReturn(
        resourceAllocator: GraphicsResourceAllocator?, inspector: FrameGraphBuilder.Inspector?,
        ci: CallbackInfo
    ) {
        D3D12RenderProf.end(D3D12RenderProf.EXECUTE_FRAMEGRAPH)
    }
}

