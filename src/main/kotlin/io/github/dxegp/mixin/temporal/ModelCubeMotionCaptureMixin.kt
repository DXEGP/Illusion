package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import net.minecraft.client.model.geom.ModelPart
import org.spongepowered.asm.mixin.Mixin

@Mixin(ModelPart.Cube::class)
abstract class ModelCubeMotionCaptureMixin {
    @WrapMethod(method = ["compile"])
    private fun illusionCaptureCube(pose: PoseStack.Pose, builder: VertexConsumer,
        lightCoords: Int, overlayCoords: Int, color: Int, original: Operation<Any?>) {
        D3D12RasterMotionCapture.beginCube(this as Any as ModelPart.Cube)
        try {
            original.call(pose, builder, lightCoords, overlayCoords, color)
        } finally {
            D3D12RasterMotionCapture.endCube()
        }
    }
}
