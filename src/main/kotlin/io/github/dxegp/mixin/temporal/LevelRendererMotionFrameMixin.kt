package io.github.dxegp.mixin.temporal

import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Vector4f
import org.spongepowered.asm.mixin.Mixin

@Mixin(LevelRenderer::class)
abstract class LevelRendererMotionFrameMixin {
    @WrapMethod(method = ["render"])
    private fun illusionRenderWorldFrame(
        allocator: GraphicsResourceAllocator,
        renderOutline: Boolean,
        camera: CameraRenderState,
        terrainFog: GpuBufferSlice,
        fogColor: Vector4f,
        shouldRenderSky: Boolean,
        consistentDepthRequired: Boolean,
        operation: Operation<Any?>,
    ) {
        try {
            VanillaFrameCoordinator.beginWorldRender(camera)
            D3D12RasterMotionCapture.beginWorldFrame()
            operation.call(allocator, renderOutline, camera, terrainFog, fogColor, shouldRenderSky, consistentDepthRequired)
        } catch (failure: Throwable) {
            VanillaFrameCoordinator.abortWorldRender(failure)
            throw failure
        }
    }
}
