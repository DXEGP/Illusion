package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.LevelRenderState
import org.joml.Matrix4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(LevelRenderer::class)
abstract class LevelRendererProfMixin {
    @Inject(method = ["submitFeatures"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfSubmitFeaturesHead(
        levelRenderState: LevelRenderState?, submitNodeCollector: SubmitNodeCollector?, renderOutline: Boolean,
        ci: CallbackInfo
    ) {
        D3D12RenderProf.begin(D3D12RenderProf.SUBMIT_FEATURES)
    }

    @Inject(method = ["submitFeatures"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfSubmitFeaturesReturn(
        levelRenderState: LevelRenderState?, submitNodeCollector: SubmitNodeCollector?, renderOutline: Boolean,
        ci: CallbackInfo
    ) {
        D3D12RenderProf.end(D3D12RenderProf.SUBMIT_FEATURES)
    }

    @Inject(method = ["prepareChunkRenders"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfPrepareChunksHead(
        modelViewMatrix: Matrix4fc?, renderOutline: Boolean,
        cir: CallbackInfoReturnable<ChunkSectionsToRender>
    ) {
        D3D12RenderProf.begin(D3D12RenderProf.PREPARE_CHUNKS)
    }

    @Inject(method = ["prepareChunkRenders"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfPrepareChunksReturn(
        modelViewMatrix: Matrix4fc?, renderOutline: Boolean,
        cir: CallbackInfoReturnable<ChunkSectionsToRender>
    ) {
        D3D12RenderProf.end(D3D12RenderProf.PREPARE_CHUNKS)
    }

    @Inject(method = ["compileSections"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfCompileSectionsHead(camera: CameraRenderState?, ci: CallbackInfo) {
        D3D12RenderProf.begin(D3D12RenderProf.COMPILE_SECTIONS)
    }

    @Inject(method = ["compileSections"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfCompileSectionsReturn(camera: CameraRenderState?, ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.COMPILE_SECTIONS)
    }
}

