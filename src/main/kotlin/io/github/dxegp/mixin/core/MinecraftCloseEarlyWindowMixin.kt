package io.github.dxegp.mixin.core

import io.github.dxegp.misc.fmlelw_control.EarlyWindowSurface
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Minecraft::class)
abstract class MinecraftCloseEarlyWindowMixin {
    @Inject(method = ["close"], at = [At("HEAD")], remap = false)
    private fun `dxr_mc$clearEarlyWindow`(callback: CallbackInfo) {
        EarlyWindowSurface.paintBlack()
    }
}

