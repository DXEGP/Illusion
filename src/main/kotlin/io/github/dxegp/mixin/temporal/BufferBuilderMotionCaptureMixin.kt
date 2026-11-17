package io.github.dxegp.mixin.temporal

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(BufferBuilder::class)
abstract class BufferBuilderMotionCaptureMixin {
    @Shadow private var vertices: Int = 0

    @Inject(method = ["addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"], at = [At("HEAD")])
    private fun illusionCapturePosition(x: Float, y: Float, z: Float,
        callback: CallbackInfoReturnable<VertexConsumer>) {
        D3D12RasterMotionCapture.vertex(this as Any as BufferBuilder, vertices, x, y, z)
    }

    @Inject(method = ["addVertex(FFFIFFIIFFF)V"], at = [At("HEAD")])
    private fun illusionCaptureCompleteVertex(x: Float, y: Float, z: Float, color: Int,
        textureU: Float, textureV: Float, overlay: Int, light: Int,
        normalX: Float, normalY: Float, normalZ: Float, callback: CallbackInfo) {
        D3D12RasterMotionCapture.vertex(this as Any as BufferBuilder, vertices, x, y, z)
    }

    @Inject(method = ["build"], at = [At("RETURN")])
    private fun illusionPublishMesh(callback: CallbackInfoReturnable<MeshData?>) {
        D3D12RasterMotionCapture.built(this as Any as BufferBuilder, callback.returnValue)
    }
}
