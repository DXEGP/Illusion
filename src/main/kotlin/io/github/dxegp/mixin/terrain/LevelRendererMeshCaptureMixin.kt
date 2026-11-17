package io.github.dxegp.mixin.terrain

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.sugar.Local
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometryOwner
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import io.github.dxegp.render.terrain.mesh.TerrainGeometryExtractionSource
import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(LevelRenderer::class)
abstract class LevelRendererMeshCaptureMixin {
    @Shadow private var sectionRenderDispatcher: SectionRenderDispatcher? = null
    @ModifyExpressionValue(method = ["render"],
        at = [At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;multiDrawIndirectAvailable:Z")],
        require = 1)
    private fun D3D12RequireIndirectTerrain(available: Boolean): Boolean =
        D3D12TerrainWorkGraph.requireIndirectAvailable(available)

    @ModifyExpressionValue(method = ["render"],
        at = [At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;shouldUseMultiDrawIndirectForTerrain:Z")],
        require = 1)
    private fun D3D12SelectIndirectTerrain(requested: Boolean): Boolean =
        D3D12TerrainWorkGraph.useIndirectDraw(requested)

    @Inject(method = ["extractSectionDrawGroups"], at = [At("HEAD")])
    private fun D3D12BeginMeshCapture(callback: CallbackInfoReturnable<Int>) {
        D3D12MeshDrawCapture.beginFrame()
    }

    @ModifyExpressionValue(method = ["extractSectionDrawGroups"],
        at = [At(value = "NEW", target = "net/minecraft/client/renderer/DynamicGpuData\$IndexedDraw")],
        require = 1)
    private fun D3D12CaptureDraw(command: DynamicGpuData.IndexedDraw,
                                @Local mesh: SectionMesh,
                                @Local layer: ChunkSectionLayer,
                                @Local slice: SectionRenderDispatcher.RenderSectionBufferSlice,
                                @Local draw: SectionMesh.SectionDraw,
                                @Local(argsOnly = true) sections: List<DynamicGpuData.ChunkSectionInfo>): DynamicGpuData.IndexedDraw {
        val customIndexBytes = if (draw.hasCustomIndexBuffer())
            Math.multiplyExact(draw.indexCount().toLong(), draw.indexType().bytes.toLong()) else 0L
        val reads = (sectionRenderDispatcher as TerrainGeometryExtractionSource)
            .captureTerrainGeometryReads(mesh, layer, slice, customIndexBytes)
        D3D12MeshDrawCapture.captureDraw(command,
            (mesh as? D3D12SectionGeometryOwner)?.d3d12Geometry?.snapshot(layer.name), sections, reads)
        return command
    }

    @Inject(method = ["resetLevelRenderData"], at = [At("HEAD")])
    private fun D3D12ClearMeshCapture(callback: CallbackInfo) {
        D3D12MeshDrawCapture.beginFrame()
    }
}
