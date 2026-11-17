package io.github.dxegp.mixin.temporal

import com.mojang.blaze3d.vertex.MeshData
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import net.minecraft.client.renderer.StagedVertexBuffer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(StagedVertexBuffer.Draw::class)
abstract class StagedDrawMotionCaptureMixin {
    @Inject(method = ["append"], at = [At("RETURN")])
    private fun illusionAppendHistory(mesh: MeshData, callback: CallbackInfo) {
        D3D12RasterMotionCapture.appended(this as Any as StagedVertexBuffer.Draw, mesh)
    }
}
