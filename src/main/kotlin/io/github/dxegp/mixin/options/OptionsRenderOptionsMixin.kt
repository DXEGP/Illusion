package io.github.dxegp.mixin.options

import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.config.RenderConfiguration
import io.github.dxegp.render.presentation.D3D12HdrPresentation
import io.github.dxegp.render.presentation.framegen.FrameGeneration
import io.github.dxegp.render.presentation.latency.NvidiaReflex
import io.github.dxegp.render.reconstruction.SuperResolution
import io.github.dxegp.render.reconstruction.dlss.DeepLearningSuperSampling
import io.github.dxegp.render.resolution.D3D12RenderScale
import io.github.dxegp.render.vrs.D3D12VariableShadingRate
import java.io.File
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Options::class)
abstract class OptionsRenderOptionsMixin {
    @Shadow
    abstract fun getFile(): File

    @Inject(method = ["load"], at = [At("TAIL")])
    private fun illusionLoadRenderOptions(callback: CallbackInfo) {
        val file = getFile()
        RenderConfiguration.loadSavedSettings {
            D3D12AntiAliasing.load(file)
            D3D12RenderScale.load(file)
            D3D12VariableShadingRate.load(file)
            DeepLearningSuperSampling.load(file)
            NvidiaReflex.load(file)
            D3D12HdrPresentation.load(file)
            FrameGeneration.load(file)
            SuperResolution.load(file)
        }
    }

    @Inject(method = ["save"], at = [At("TAIL")])
    private fun illusionSaveRenderOptions(callback: CallbackInfo) {
        val file = getFile()
        D3D12AntiAliasing.save(file)
        D3D12RenderScale.save(file)
        D3D12VariableShadingRate.save(file)
        DeepLearningSuperSampling.save(file)
        NvidiaReflex.save(file)
        D3D12HdrPresentation.save(file)
        FrameGeneration.save(file)
        SuperResolution.save(file)
    }

    @Inject(method = ["isRestartRequiredToApplyVideoSettings"], at = [At("RETURN")], cancellable = true)
    private fun illusionRestartRequiredForHdr(callback: CallbackInfoReturnable<Boolean>) {
        if (D3D12HdrPresentation.isRestartPending()) callback.returnValue = true
    }
}
