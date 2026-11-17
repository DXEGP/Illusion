package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import org.spongepowered.asm.mixin.Mixin

@Mixin(ModelFeatureRenderer::class)
abstract class ModelMotionCaptureMixin {
    @WrapMethod(method = ["prepareModel"])
    private fun illusionCaptureModel(submit: ModelFeatureRenderer.Submit<*>, original: Operation<Any?>) {
        D3D12RasterMotionCapture.beginModel(submit)
        var complete = false
        try {
            original.call(submit)
            complete = true
        } finally {
            D3D12RasterMotionCapture.endModel(complete)
        }
    }
}
