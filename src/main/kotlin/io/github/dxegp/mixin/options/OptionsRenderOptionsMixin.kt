package io.github.dxegp.mixin.options

import io.github.dxegp.experimental.framegen.FrameGeneration
import io.github.dxegp.misc.settings.D3D12HdrPresentation
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12RenderScale
import io.github.dxegp.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import io.github.dxegp.experimental.superresolution.dlss.DeepLearningSuperSampling
import io.github.dxegp.experimental.superresolution.dlss.NvidiaReflex
import io.github.dxegp.experimental.superresolution.SuperResolution
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.io.File

@Mixin(Options::class)
abstract class OptionsRenderOptionsMixin {
    @Shadow
    abstract fun getFile(): File

    @Inject(method = ["load"], at = [At("TAIL")])
    private fun `dxr_mc$loadRenderOptions`(callback: CallbackInfo) {
        val file = getFile()
        D3D12RenderScale.load(file)
        D3D12VariableShadingRate.load(file)
        DeepLearningSuperSampling.load(file)
        NvidiaReflex.load(file)
        D3D12HdrPresentation.load(file)
        FrameGeneration.load(file)
        SuperResolution.load(file)
    }

    @Inject(method = ["save"], at = [At("TAIL")])
    private fun `dxr_mc$saveRenderOptions`(callback: CallbackInfo) {
        val file = getFile()
        D3D12RenderScale.save(file)
        D3D12VariableShadingRate.save(file)
        DeepLearningSuperSampling.save(file)
        NvidiaReflex.save(file)
        D3D12HdrPresentation.save(file)
        FrameGeneration.save(file)
        SuperResolution.save(file)
    }

    @Inject(method = ["isRestartRequiredToApplyVideoSettings"], at = [At("RETURN")], cancellable = true)
    private fun `dxr_mc$restartRequiredForHdr`(callback: CallbackInfoReturnable<Boolean>) {
        if (callback.returnValue == true || !D3D12HdrPresentation.isRestartPending()) return
        callback.returnValue = true
    }
}

