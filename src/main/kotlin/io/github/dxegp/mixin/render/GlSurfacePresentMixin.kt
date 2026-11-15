package io.github.dxegp.mixin.render

import com.mojang.renderpearl.backend.opengl.GlSurface
import io.github.dxegp.misc.settings.D3D12Settings
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GlSurface::class)
abstract class GlSurfacePresentMixin {
    @Inject(method = ["present"], at = [At("HEAD")], cancellable = true)
    private fun `dxr_mc$onPresent`(callback: CallbackInfo) {
        if (D3D12Settings.skipGlSwap) callback.cancel()
    }
}

