package io.github.dxegp.mixin.core

import com.mojang.blaze3d.platform.Window
import com.mojang.renderpearl.api.device.GpuBackend
import io.github.dxegp.d3d12.backend.D3D12Backend
import io.github.dxegp.misc.fmlelw_control.EarlyWindowSurface
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Window::class)
abstract class WindowAdoptCreatedMixin {
    @Inject(method = ["createWindow"], at = [At("HEAD")], cancellable = true)
    private fun `dxr_mc$adoptEarlyWindow`(
        backend: GpuBackend, width: Int, height: Int, title: String,
        callback: CallbackInfoReturnable<Long>
    ) {
        val pending = System.getProperty("dxrmc.earlywindow.handle") ?: return
        if (backend !is D3D12Backend) return
        System.clearProperty("dxrmc.earlywindow.handle")
        val handle = pending.toLongOrNull() ?: return
        if (handle != 0L) {
            System.setProperty("dxrmc.earlywindow.adopted", "1")
            EarlyWindowSurface.remember(handle)
            callback.returnValue = handle
        }
    }
}

