package io.github.dxegp.mixin.backend

import net.minecraft.client.renderer.ShaderManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Failed apply and transaction abandonment can both relinquish the same include buffers. */
@Mixin(ShaderManager.Configs::class)
abstract class ShaderConfigsCloseMixin {
    @Unique
    private val illusionIncludesClosed = AtomicBoolean()

    @Inject(method = ["close"], at = [At("HEAD")], cancellable = true)
    private fun illusionCloseIncludesOnce(callback: CallbackInfo) {
        if (!illusionIncludesClosed.compareAndSet(false, true)) callback.cancel()
    }
}
