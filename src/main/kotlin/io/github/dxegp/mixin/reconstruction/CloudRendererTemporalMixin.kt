package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.render.reconstruction.oit.OitCloudFace
import io.github.dxegp.render.reconstruction.oit.OitExecutionEventKind
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import com.mojang.renderpearl.api.commands.RenderPass
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.OitSurfaceInputs
import net.minecraft.client.CloudStatus
import net.minecraft.client.renderer.CloudRenderer
import net.minecraft.client.renderer.oit.OitRenderPassProvider
import net.minecraft.client.renderer.oit.OitStage
import net.minecraft.world.phys.Vec3
import net.minecraft.core.Direction
import org.joml.Vector4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer

@Mixin(CloudRenderer::class)
abstract class CloudRendererTemporalMixin {
    @WrapOperation(method = ["renderOit"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V")])
    private fun illusionRecordCloudDepthCopy(pass: RenderPass, vertexCount: Int, instanceCount: Int,
        firstVertex: Int, firstInstance: Int, original: Operation<Void?>) {
        OitProducerCapture.recordExecutionOperation(OitExecutionEventKind.CLOUD_DEPTH_COPY) {
            original.call(pass, vertexCount, instanceCount, firstVertex, firstInstance)
        }
    }

    @WrapMethod(method = ["prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"])
    private fun illusionRecordPreparation(color: Int, status: CloudStatus, cloudHeight: Float, distance: Int,
        camera: Vec3, ticks: Long, partialTick: Float, original: Operation<Any?>) {
        OitSurfaceInputs.recordCloudPreparation(this, ticks, partialTick, cloudHeight, status, camera) {
            original.call(color, status, cloudHeight, distance, camera, ticks, partialTick)
        }
    }

    @ModifyExpressionValue(method = ["prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"],
        at = [At(value = "FIELD", target = "Lnet/minecraft/client/renderer/CloudRenderer\$TextureData;width:I", ordinal = 0)])
    private fun illusionCaptureTextureWidth(width: Int): Int {
        OitSurfaceInputs.captureCloudWidth(this, width)
        return width
    }

    @ModifyExpressionValue(method = ["prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"],
        at = [At(value = "FIELD", target = "Lnet/minecraft/client/renderer/CloudRenderer\$TextureData;height:I", ordinal = 0)])
    private fun illusionCaptureTextureHeight(height: Int): Int {
        OitSurfaceInputs.captureCloudHeight(this, height)
        return height
    }

    @ModifyExpressionValue(method = ["prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;floor(D)I")])
    private fun illusionCaptureFloor(value: Int): Int {
        OitSurfaceInputs.captureCloudFloor(this, value)
        return value
    }

    @WrapOperation(method = ["prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putVec4(Lorg/joml/Vector4fc;)Lcom/mojang/blaze3d/buffers/Std140Builder;")])
    private fun illusionCaptureCloudColor(builder: Std140Builder, color: Vector4fc,
        original: Operation<Std140Builder>): Std140Builder {
        OitSurfaceInputs.captureCloudColor(this, color.x(), color.y(), color.z(), color.w())
        return original.call(builder, color)
    }

    @WrapOperation(method = ["prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putVec3(FFF)Lcom/mojang/blaze3d/buffers/Std140Builder;")])
    private fun illusionCaptureCloudVector(builder: Std140Builder, x: Float, y: Float, z: Float,
        original: Operation<Std140Builder>): Std140Builder {
        OitSurfaceInputs.captureCloudVector(this, x, y, z)
        return original.call(builder, x, y, z)
    }

    @Inject(method = ["buildMesh"], at = [At("HEAD")])
    private fun illusionBeginMesh(callback: CallbackInfo) {
        OitSurfaceInputs.beginCloudMesh(this)
    }

    @Inject(method = ["buildMesh"], at = [At("RETURN")])
    private fun illusionPublishMesh(callback: CallbackInfo) {
        OitSurfaceInputs.publishCloudMesh(this)
    }

    @Inject(method = ["encodeFace"], at = [At("RETURN")])
    private fun illusionCaptureFace(buffer: ByteBuffer, x: Int, z: Int, direction: Direction, flags: Int,
        callback: CallbackInfo) {
        val end = buffer.position()
        val packed = buffer.get(end - 1).toInt() and 255
        OitSurfaceInputs.captureCloudFace(this, OitCloudFace(
            (buffer.get(end - 3).toInt() shl 1) or ((packed ushr 7) and 1),
            (buffer.get(end - 2).toInt() shl 1) or ((packed ushr 6) and 1), packed and 7, packed and 48))
    }

    @Inject(method = ["apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"],
        at = [At("HEAD")])
    private fun illusionInvalidateTexture(callback: CallbackInfo) {
        OitReconstructionInputs.invalidateCloudTexture(this)
    }

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun illusionCloseSurfaces(callback: CallbackInfo) {
        OitSurfaceInputs.closeProducer(this)
    }

    @WrapMethod(method = ["renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider\$Parameters;)V"])
    private fun illusionRecordCloud(status: CloudStatus, stage: OitStage, depth: GpuTextureView,
        parameters: OitRenderPassProvider.Parameters, original: Operation<Any?>) {
        if (stage != OitStage.ACCUMULATE) {
            original.call(status, stage, depth, parameters)
            return
        }
        OitReconstructionInputs.beginCloudDraw(this)
        try {
            original.call(status, stage, depth, parameters)
        } finally {
            OitReconstructionInputs.endCloudDraw()
        }
    }
}
