package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.render.reconstruction.oit.OitSurfaceInputs
import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.client.renderer.WorldBorderRenderer
import net.minecraft.client.renderer.oit.OitStage
import net.minecraft.client.renderer.state.level.WorldBorderRenderState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer

@Mixin(WorldBorderRenderer::class)
abstract class WorldBorderRendererTemporalMixin {
    @WrapOperation(method = ["rebuildWorldBorderBuffer"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;writeToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V")])
    private fun illusionRecordBorderMesh(encoder: CommandEncoder, destination: GpuBufferSlice,
        source: ByteBuffer, original: Operation<Void?>) {
        OitSurfaceInputs.recordBorderMesh(this, source) { original.call(encoder, destination, source) }
    }

    @WrapMethod(method = ["renderOit(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lnet/minecraft/world/phys/Vec3;DLnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"])
    private fun illusionRecordBorderDraw(state: WorldBorderRenderState, camera: Vec3, renderDistance: Double,
        stage: OitStage, pass: RenderPass, original: Operation<Void?>) {
        if (stage != OitStage.ACCUMULATE) {
            original.call(state, camera, renderDistance, stage, pass)
            return
        }
        OitSurfaceInputs.recordBorderDraw(this) { original.call(state, camera, renderDistance, stage, pass) }
    }

    @WrapOperation(method = ["prepareDynamicTransforms"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/util/Util;getMillis()J")])
    private fun illusionRecordBorderTime(original: Operation<Long>): Long {
        val millis = original.call()
        OitSurfaceInputs.recordBorderMillis(this, millis)
        return millis
    }

    @WrapOperation(method = ["prepareDynamicTransforms"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;Lorg/joml/Vector3f;Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")])
    private fun illusionRecordBorderTransform(uniforms: DynamicGpuData, modelView: Matrix4f, color: Vector4f,
        offset: Vector3f, texture: Matrix4f, original: Operation<GpuBufferSlice>): GpuBufferSlice {
        val result = original.call(uniforms, modelView, color, offset, texture)
        OitSurfaceInputs.recordBorderTransform(this, modelView, color, offset, texture)
        return result
    }

    @WrapOperation(method = ["prepareRenderPass"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V")])
    private fun illusionRecordBorderMaterial(pass: RenderPass, name: String, view: GpuTextureView,
        sampler: GpuSampler, original: Operation<Void?>) {
        original.call(pass, name, view, sampler)
        if (name == "Sampler0") OitSurfaceInputs.recordBorderMaterial(this, view, sampler)
    }

    @Inject(method = ["invalidate"], at = [At("HEAD")])
    private fun illusionInvalidateBorderMesh(callback: CallbackInfo) {
        OitSurfaceInputs.invalidateBorderMesh(this)
    }

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun illusionCloseBorder(callback: CallbackInfo) {
        OitSurfaceInputs.closeProducer(this)
    }
}
