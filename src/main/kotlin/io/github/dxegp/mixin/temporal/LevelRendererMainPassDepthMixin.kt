package io.github.dxegp.mixin.temporal

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelRenderer::class)
abstract class LevelRendererMainPassDepthMixin {
    @Shadow
    @Final
    private lateinit var targets: LevelTargetBundle

    @WrapMethod(method = ["lambda\$addMainPass\$0"], remap = false)
    private fun illusionWorldPassAbortOnThrow(
        slice: GpuBufferSlice,
        firstFlag: Boolean,
        sections: ChunkSectionsToRender,
        frame: FeatureRenderDispatcher.PreparedFrame,
        secondFlag: Boolean,
        thirdFlag: Boolean,
        operation: Operation<Any?>,
    ) {
        try {
            operation.call(slice, firstFlag, sections, frame, secondFlag, thirdFlag)
        } catch (failure: Throwable) {
            VanillaFrameCoordinator.worldPassAborted(failure)
            throw failure
        }
    }

    @Inject(method = ["lambda\$addMainPass\$0"], at = [At("HEAD")], remap = false)
    private fun illusionWorldPassStarted(callback: CallbackInfo) {
        VanillaFrameCoordinator.worldPassStarted()
    }

    @Inject(method = ["lambda\$addMainPass\$0"], at = [At("RETURN")], remap = false)
    private fun illusionWorldPassCompleted(
        slice: GpuBufferSlice,
        firstFlag: Boolean,
        sections: ChunkSectionsToRender,
        frame: FeatureRenderDispatcher.PreparedFrame,
        secondFlag: Boolean,
        thirdFlag: Boolean,
        callback: CallbackInfo,
    ) {
        VanillaFrameCoordinator.worldPassCompleted(targets)
    }
}
