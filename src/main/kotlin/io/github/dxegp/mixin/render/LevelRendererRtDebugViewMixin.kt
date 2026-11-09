package io.github.dxegp.mixin.render

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.RtDebugView
import io.github.dxegp.experimental.raytracing.D3D12RtFrame
import io.github.dxegp.experimental.raytracing.D3D12RtPresentation
import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(value = [LevelRenderer::class], priority = 1100)
abstract class LevelRendererRtDebugViewMixin {
    @Shadow @Final
    private var targets: LevelTargetBundle? = null

    @Inject(
        method = ["lambda\$addMainPass\$0"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;" +
                "executeOit(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;" +
                "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher\$PreparedFrame;)V")],
        remap = false, require = 1
    )
    private fun `dxr_mc$compositeBeforeOit`(
        slice: GpuBufferSlice?, firstFlag: Boolean, sections: ChunkSectionsToRender?,
        frame: FeatureRenderDispatcher.PreparedFrame?, secondFlag: Boolean, thirdFlag: Boolean,
        callback: CallbackInfo
    ) {
        if (D3D12Settings.rtDebugView != RtDebugView.NONE.ordinal) return
        `dxr_mc$tryBlit`()
    }

    @Inject(method = ["lambda\$addMainPass\$0"], at = [At("RETURN")], remap = false)
    private fun `dxr_mc$blitDebugView`(
        slice: GpuBufferSlice?, firstFlag: Boolean, sections: ChunkSectionsToRender?,
        frame: FeatureRenderDispatcher.PreparedFrame?, secondFlag: Boolean, thirdFlag: Boolean,
        callback: CallbackInfo
    ) {
        if (D3D12Settings.rtDebugView == RtDebugView.NONE.ordinal) return
        `dxr_mc$tryBlit`()
    }

    @Unique
    private fun `dxr_mc$tryBlit`() {
        if (!D3D12Settings.rtEnabled) return
        try {
            D3D12RtPresentation.blit(targets)
        } catch (failure: Throwable) {
            D3D12RtFrame.reportFailure(failure)
        }
    }
}
