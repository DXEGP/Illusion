package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.platform.Lighting
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.CommandEncoder
import io.github.dxegp.render.reconstruction.oit.OitFeatureUniforms
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import java.nio.ByteBuffer

@Mixin(Lighting::class)
abstract class FeatureLightingUniformMixin {
    @WrapOperation(method = ["updateBuffer"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;writeToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V")])
    private fun illusionRecordLighting(encoder: CommandEncoder, destination: GpuBufferSlice,
        source: ByteBuffer, original: Operation<Void?>) {
        OitFeatureUniforms.recordLightingUpload(encoder, destination, source, original)
    }
}
