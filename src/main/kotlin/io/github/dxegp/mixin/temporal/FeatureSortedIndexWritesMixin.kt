package io.github.dxegp.mixin.temporal

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.render.temporal.FeatureSortedIndexWrites
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.nio.ByteBuffer

@Mixin(D3D12GpuBuffer::class, remap = false)
abstract class FeatureSortedIndexMappedWritesMixin {
    @Inject(method = ["map(JJZZ)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice\$MappedView;"],
        at = [At("HEAD")], remap = false)
    private fun illusionRevokeMappedIndices(offset: Long, length: Long, read: Boolean, write: Boolean,
        callback: CallbackInfoReturnable<GpuBufferSlice.MappedView>) {
        if (write) FeatureSortedIndexWrites.revokeRange(GpuBufferSlice((this as Any) as GpuBuffer, offset, length))
    }

    @Inject(method = ["close"], at = [At("HEAD")], remap = false)
    private fun illusionRevokeClosedIndices(callback: CallbackInfo) {
        FeatureSortedIndexWrites.revokeBuffer((this as Any) as GpuBuffer)
    }
}

@Mixin(D3D12CommandEncoderBackend::class, remap = false)
abstract class FeatureSortedIndexBufferWritesMixin {
    @Inject(method = ["writeToBuffer"], at = [At("HEAD")], remap = false)
    private fun illusionRevokeUploadedIndices(destination: GpuBufferSlice, source: ByteBuffer, callback: CallbackInfo) {
        FeatureSortedIndexWrites.revokeRange(destination)
    }

    @Inject(method = ["copyToBuffer"], at = [At("HEAD")], remap = false)
    private fun illusionRevokeCopiedIndices(source: GpuBufferSlice, destination: GpuBufferSlice, callback: CallbackInfo) {
        FeatureSortedIndexWrites.revokeRange(destination)
    }

    @Inject(method = ["copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V"],
        at = [At("HEAD")], remap = false)
    private fun illusionRevokeTextureCopyIndices(source: GpuTexture, destination: GpuBuffer, offset: Long,
        callback: Runnable, mipLevel: Int, textureX: Int, textureY: Int, width: Int, height: Int,
        result: CallbackInfo) {
        FeatureSortedIndexWrites.revokeBuffer(destination)
    }
}
