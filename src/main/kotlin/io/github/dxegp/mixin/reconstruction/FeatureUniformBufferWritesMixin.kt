package io.github.dxegp.mixin.reconstruction

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.render.reconstruction.oit.OitFeatureUniforms
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.nio.ByteBuffer

@Mixin(D3D12GpuBuffer::class, remap = false)
abstract class FeatureUniformMappedWriteMixin {
    @Inject(method = ["map(JJZZ)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice\$MappedView;"],
        at = [At("HEAD")], remap = false)
    private fun illusionInvalidateMappedWrite(offset: Long, length: Long, read: Boolean, write: Boolean,
        callback: CallbackInfoReturnable<GpuBufferSlice.MappedView>) {
        if (write) OitFeatureUniforms.invalidateBufferRange(GpuBufferSlice(
            this as D3D12GpuBuffer, offset, length))
    }

    @Inject(method = ["close"], at = [At("HEAD")], remap = false)
    private fun illusionInvalidateClosedBuffer(callback: CallbackInfo) {
        OitFeatureUniforms.retireBuffer(this as D3D12GpuBuffer)
    }
}

@Mixin(D3D12CommandEncoderBackend::class, remap = false)
abstract class FeatureUniformBufferWritesMixin {
    @Inject(method = ["writeToBuffer"], at = [At("HEAD")], remap = false)
    private fun illusionInvalidateUpload(destination: GpuBufferSlice, source: ByteBuffer, callback: CallbackInfo) {
        OitFeatureUniforms.invalidateBufferRange(destination)
    }

    @Inject(method = ["copyToBuffer"], at = [At("HEAD")], remap = false)
    private fun illusionInvalidateCopy(source: GpuBufferSlice, destination: GpuBufferSlice, callback: CallbackInfo) {
        OitFeatureUniforms.invalidateBufferRange(destination)
    }
}
