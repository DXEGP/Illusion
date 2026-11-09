package io.github.dxegp.mixin.client

import io.github.dxegp.compat.FpsLimitCompat
import com.mojang.blaze3d.platform.FramerateLimitTracker
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(FramerateLimitTracker::class)
abstract class FpsLimitTrackerMixin {
    @Shadow @Final
    private lateinit var options: Options

    @Inject(method = ["getFramerateLimit"], at = [At("HEAD")], cancellable = true)
    private fun `dxrmc$keepFramerate`(callback: CallbackInfoReturnable<Int>) {
        if (FpsLimitCompat.isNever(options.inactivityFpsLimit().get())) {
            callback.returnValue = options.framerateLimit().get()
        }
    }
}

