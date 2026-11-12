package io.github.dxegp.mixin.terrain

import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometryOwner
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.nio.ByteBuffer

@Mixin(targets = ["net.minecraft.client.renderer.chunk.SectionRenderDispatcher\$RenderSection"])
abstract class SectionUploadCaptureMixin {
    @Inject(method = ["addSectionBuffersToUberBuffer"], at = [At("HEAD")])
    private fun D3D12CaptureUpload(layer: ChunkSectionLayer, mesh: CompiledSectionMesh,
                                   vertexBuffer: ByteBuffer?, indexBuffer: ByteBuffer?,
                                   callback: CallbackInfoReturnable<Boolean>) {
        (mesh as D3D12SectionGeometryOwner).d3d12Geometry.captureUpload(
            layer.name, mesh.getSectionDraw(layer)?.indexCount() ?: 0,
            indexBuffer?.remaining() ?: 0, vertexBuffer)
    }
}
