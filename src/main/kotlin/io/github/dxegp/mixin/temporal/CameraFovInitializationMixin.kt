package io.github.dxegp.mixin.temporal

import net.minecraft.client.Camera
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Camera::class)
abstract class CameraFovInitializationMixin {
    @Shadow
    private var fovModifier: Float = 0.0f

    @Shadow
    private var oldFovModifier: Float = 0.0f

    @Inject(method = ["<init>"], at = [At("RETURN")], remap = false)
    private fun illusionInitializeFovModifiers(callback: CallbackInfo) {
        fovModifier = 1.0f
        oldFovModifier = 1.0f
    }
}
