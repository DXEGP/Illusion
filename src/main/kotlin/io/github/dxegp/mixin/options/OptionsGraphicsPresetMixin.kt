package io.github.dxegp.mixin.options

import io.github.dxegp.d3d12.render.advanced.D3D12AntiAliasing
import net.minecraft.client.GraphicsPreset
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Options::class)
abstract class OptionsGraphicsPresetMixin {
    @Inject(
        method = ["applyGraphicsPreset"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/GraphicsPreset;apply(Lnet/minecraft/client/Minecraft;)V",
            shift = At.Shift.AFTER
        )],
        remap = false
    )
    private fun `dxr_mc$applyPresetToAntiAliasing`(preset: GraphicsPreset, callback: CallbackInfo) {
        D3D12AntiAliasing.applyFromPreset(preset.serializedName)
    }
}

