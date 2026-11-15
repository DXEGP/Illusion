package io.github.dxegp.mixin.options

import io.github.dxegp.d3d12.render.advanced.D3D12AntiAliasing
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.io.File

@Mixin(Options::class)
abstract class OptionsAntiAliasingMixin {
    @Shadow
    abstract fun getFile(): File

    @Inject(method = ["load"], at = [At("TAIL")])
    private fun `dxr_mc$loadAntiAliasing`(callback: CallbackInfo) {
        D3D12AntiAliasing.load(getFile())
    }

    @Inject(method = ["save"], at = [At("TAIL")])
    private fun `dxr_mc$saveAntiAliasing`(callback: CallbackInfo) {
        D3D12AntiAliasing.save(getFile())
    }
}

