package io.github.dxegp.mixin.terrain

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.sugar.Local
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometryOwner
import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(LevelRenderer::class)
abstract class LevelRendererMeshCaptureMixin {
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
                                @Local(argsOnly = true) sections: List<DynamicGpuData.ChunkSectionInfo>): DynamicGpuData.IndexedDraw {
        D3D12MeshDrawCapture.captureDraw(command,
            (mesh as? D3D12SectionGeometryOwner)?.d3d12Geometry?.snapshot(layer.name), sections)
        return command
    }

    @Inject(method = ["resetLevelRenderData"], at = [At("HEAD")])
    private fun D3D12ClearMeshCapture(callback: CallbackInfo) {
        D3D12MeshDrawCapture.beginFrame()
    }
}
