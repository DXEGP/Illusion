package io.github.dxegp.mixin.temporal

import io.github.dxegp.render.temporal.D3D12Temporal
import net.minecraft.client.Minecraft
import net.minecraft.util.Util
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Minecraft::class)
abstract class MinecraftRenderFrameTimeMixin {
    @Inject(method = ["renderFrame"], at = [At("HEAD")])
    private fun illusionRecordFrameStart(advanceGameTime: Boolean, callback: CallbackInfo) {
        D3D12Temporal.beginRenderFrame(Util.getNanos())
    }
}
