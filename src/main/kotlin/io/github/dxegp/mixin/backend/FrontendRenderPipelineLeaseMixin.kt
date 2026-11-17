package io.github.dxegp.mixin.backend

import com.mojang.renderpearl.frontend.FrontendRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12PipelineBridge
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(FrontendRenderPipeline::class)
abstract class FrontendRenderPipelineLeaseMixin {
    @Unique
    @Volatile
    private var illusionLeaseClosed = false

    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun illusionFrontendCreated(callback: CallbackInfo) {
        val frontend = this as Any as FrontendRenderPipeline
        val pipeline = frontend.backendRenderPipeline() as? D3D12CompiledRenderPipeline ?: return
        D3D12PipelineBridge.frontendCreated(pipeline, frontend)
    }

    @Inject(method = ["close"], at = [At("HEAD")], cancellable = true)
    private fun illusionReleaseLease(callback: CallbackInfo) {
        val pipeline = (this as Any as FrontendRenderPipeline).backendRenderPipeline()
            as? D3D12CompiledRenderPipeline ?: return
        synchronized(this) {
            if (!illusionLeaseClosed) {
                D3D12PipelineBridge.release(pipeline)
                illusionLeaseClosed = true
            }
        }
        callback.cancel()
    }

    @Inject(method = ["isClosed"], at = [At("HEAD")], cancellable = true)
    private fun illusionLeaseIsClosed(callback: CallbackInfoReturnable<Boolean>) {
        if (illusionLeaseClosed) callback.returnValue = true
    }
}
