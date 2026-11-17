package io.github.dxegp.mixin.temporal

import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import net.minecraft.client.model.geom.ModelPart
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ModelPart::class)
abstract class ModelPartMotionCaptureMixin {
    @Inject(method = ["compile"], at = [At("HEAD")])
    private fun illusionCapturePart(callback: CallbackInfo) {
        D3D12RasterMotionCapture.beginPart(this as Any as ModelPart)
    }
}
