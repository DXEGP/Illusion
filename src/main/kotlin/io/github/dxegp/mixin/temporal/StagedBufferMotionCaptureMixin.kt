package io.github.dxegp.mixin.temporal

import com.mojang.renderpearl.api.vertex.VertexFormat
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexSorting
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.device.GpuDevice
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import io.github.dxegp.render.temporal.FeatureSortedIndexCapture
import io.github.dxegp.render.temporal.FeatureSortedIndexDescription
import io.github.dxegp.render.temporal.FeatureSortedIndexUpload
import net.minecraft.client.renderer.StagedVertexBuffer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer

@Mixin(StagedVertexBuffer.Draw::class)
interface StagedDrawMotionAccessor {
    @Accessor("format")
    fun illusionMotionFormat(): VertexFormat

    @Accessor("vertexCount")
    fun illusionFeatureVertexCount(): Int

    @Accessor("indexCount")
    fun illusionFeatureIndexCount(): Int

    @Accessor("vertexOffset")
    fun illusionFeatureVertexOffset(): Int

    @Accessor("indexOffset")
    fun illusionFeatureIndexOffset(): Int
}

@Mixin(StagedVertexBuffer::class)
abstract class StagedBufferMotionCaptureMixin {
    @Unique
    private val illusionSortedIndices = FeatureSortedIndexCapture()

    @WrapMethod(method = ["uploadDrawsToBuffers"])
    private fun illusionRecordSortedUpload(device: GpuDevice, draws: List<StagedVertexBuffer.Draw>,
        vertexBuffer: GpuBuffer, indexBuffer: GpuBuffer?, vertexBytes: Int, indexBytes: Int,
        original: Operation<Void?>) {
        illusionSortedIndices.beginUpload(FeatureSortedIndexUpload(vertexBuffer, indexBuffer, vertexBytes, indexBytes))
        try {
            original.call(device, draws, vertexBuffer, indexBuffer, vertexBytes, indexBytes)
            illusionSortedIndices.publishUpload()
        } catch (failure: Throwable) {
            illusionSortedIndices.discardUpload()
            throw failure
        }
    }

    @WrapOperation(method = ["uploadDrawsToBuffers"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/MeshData\$SortState;writeSortedIndexBuffer(Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/vertex/VertexSorting;)V")])
    private fun illusionRecordSortedIndices(sort: MeshData.SortState, buffer: ByteBuffer, sorting: VertexSorting,
        original: Operation<Void?>, @Local draw: StagedVertexBuffer.Draw) {
        val start = buffer.position()
        original.call(sort, buffer, sorting)
        val source = draw as StagedDrawMotionAccessor
        illusionSortedIndices.recordSortedWrite(draw, FeatureSortedIndexDescription(source.illusionFeatureVertexCount(),
            source.illusionFeatureIndexCount(), source.illusionFeatureVertexOffset(), source.illusionFeatureIndexOffset(),
            source.illusionMotionFormat().vertexSize, sort.indexType()), buffer, start)
    }

    @Inject(method = ["getExecuteInfo"], at = [At("RETURN")])
    private fun illusionPublishDraw(draw: StagedVertexBuffer.Draw,
        callback: CallbackInfoReturnable<StagedVertexBuffer.ExecuteInfo?>) {
        val format = (draw as StagedDrawMotionAccessor).illusionMotionFormat()
        D3D12RasterMotionCapture.publish(draw, callback.returnValue, format.vertexSize, illusionSortedIndices.lookup(draw))
    }

    @Inject(method = ["endDraw", "close"], at = [At("HEAD")])
    private fun illusionRetireSortedIndices(callback: CallbackInfo) {
        illusionSortedIndices.clear()
    }
}
