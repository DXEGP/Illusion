package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.render.reconstruction.oit.OitFeatureColorWrite
import io.github.dxegp.render.reconstruction.oit.OitFeatureTransformSerialization
import io.github.dxegp.render.reconstruction.oit.OitFeatureUniforms
import net.minecraft.client.renderer.DynamicGpuData
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer

@Mixin(DynamicGpuData.Transform::class)
internal abstract class FeatureTransformSerializationMixin : OitFeatureTransformSerialization {
    @Unique
    private var illusionColorWrite: OitFeatureColorWrite? = null

    override fun illusionFeatureColorWrite(): OitFeatureColorWrite? = illusionColorWrite

    @WrapMethod(method = ["write(Ljava/nio/ByteBuffer;)V"])
    private fun illusionRecordSerializedColor(buffer: ByteBuffer, original: Operation<Void?>) {
        val start = buffer.position()
        illusionColorWrite = null
        original.call(buffer)
        illusionColorWrite = OitFeatureUniforms.readColorWrite(buffer, start)
    }
}

@Mixin(DynamicGpuData::class)
abstract class FeatureTransformUniformMixin {
    @WrapMethod(method = ["writeTransform(Lnet/minecraft/client/renderer/DynamicGpuData\$Transform;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"])
    private fun illusionRecordTransform(transform: DynamicGpuData.Transform,
        original: Operation<GpuBufferSlice>): GpuBufferSlice =
        OitFeatureUniforms.recordTransformUpload(transform, original)

    @WrapMethod(method = ["writeTransforms([Lnet/minecraft/client/renderer/DynamicGpuData\$Transform;)[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"])
    private fun illusionRecordTransforms(transforms: Array<DynamicGpuData.Transform>,
        original: Operation<Array<GpuBufferSlice>>): Array<GpuBufferSlice> =
        OitFeatureUniforms.recordTransformUploads(transforms, original)

    @Inject(method = ["reset", "close"], at = [At("HEAD")])
    private fun illusionRetireTransforms(callback: CallbackInfo) {
        OitFeatureUniforms.retireTransforms()
    }
}
