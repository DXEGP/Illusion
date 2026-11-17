package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.presentation.viewmodel.D3D12ViewmodelInputs
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.PlayerRenderState
import org.joml.Matrix4f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At

@Mixin(GameRenderer::class)
abstract class GameRendererViewmodelInputsMixin {
    @Shadow private lateinit var hudProjection: Projection

    @WrapOperation(
        method = ["render3dHud"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
        )],
        remap = false,
    )
    private fun capture(renderer: GameRenderer, cameraState: CameraRenderState, playerState: PlayerRenderState,
        depthTextureView: GpuTextureView, original: Operation<Any?>) {
        val frame = VanillaFrameCoordinator.currentFrame()
        if (frame == null || !D3D12ViewmodelInputs.needsCapture(frame)) {
            D3D12ViewmodelInputs.invalidate()
            original.call(renderer, cameraState, playerState, depthTextureView)
            return
        }
        val depth = depthTextureView.texture()
        check(depth is D3D12GpuTexture) { "Viewmodel frame has no actual D3D12 depth attachment" }
        val projection = hudProjection.getMatrix(Matrix4f())
        D3D12ViewmodelInputs.begin(frame, checkNotNull(D3D12SceneDisplay.targetForFrame()), projection, depth,
            RenderSystem.getModelViewMatrixCopy().mul(cameraState.viewRotationMatrix))
        try {
            original.call(renderer, cameraState, playerState, depthTextureView)
            D3D12ViewmodelInputs.end()
        } catch (failure: Throwable) {
            try { D3D12ViewmodelInputs.invalidate() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
