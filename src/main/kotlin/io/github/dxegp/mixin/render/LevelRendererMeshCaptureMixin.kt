package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshDrawCapture
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtGeometryRecovery
import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelRenderer::class)
abstract class LevelRendererMeshCaptureMixin {
    @Inject(method = ["compileSections"], at = [At("TAIL")])
    private fun `dxr_mc$submitRecovery`(callback: CallbackInfo) {
        D3D12SectionGeometry.observeCurrentCompiler()
        D3D12RtGeometryRecovery.submit(this as LevelRenderer)
    }

    @Inject(method = ["extractSectionDrawGroups"], at = [At("HEAD")])
    private fun `dxr_mc$beginExtraction`(callback: CallbackInfoReturnable<Int>) {
        D3D12SectionGeometry.observeCurrentCompiler()
        D3D12MeshDrawCapture.beginExtraction()
    }

    @WrapOperation(
        method = ["extractSectionDrawGroups"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;" +
                "getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;" +
                "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)" +
                "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher\$RenderSectionBufferSlice;")]
    )
    private fun `dxr_mc$captureSlice`(
        dispatcher: SectionRenderDispatcher, mesh: SectionMesh, layer: ChunkSectionLayer,
        original: Operation<SectionRenderDispatcher.RenderSectionBufferSlice?>
    ): SectionRenderDispatcher.RenderSectionBufferSlice? {
        val slice = original.call(dispatcher, mesh, layer)
        if (slice != null) {
            D3D12MeshDrawCapture.captureSlice(mesh, layer.name, slice.vertexBuffer(), slice.vertexBufferOffset())
        }
        return slice
    }

    @Inject(method = ["extractSectionDrawGroups"], at = [At("RETURN")])
    private fun `dxr_mc$finishExtraction`(
        respectTranslucentOrder: Boolean, sectionInfos: List<DynamicGpuData.ChunkSectionInfo>,
        drawGroups: Map<ChunkSectionLayer, *>?, callback: CallbackInfoReturnable<Int>
    ) {
        D3D12MeshDrawCapture.finishExtraction(sectionInfos)
    }
}

