package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.frontend.FrontendRenderPass
import com.mojang.renderpearl.frontend.FrontendRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.render.presentation.display.WorldTransparencyCapture
import io.github.dxegp.render.reconstruction.oit.OitDrawDisposition
import io.github.dxegp.render.reconstruction.oit.OitDrawSelection
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import org.lwjgl.PointerBuffer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import java.nio.IntBuffer

@Mixin(FrontendRenderPass::class)
abstract class FrontendRenderPassGuideMixin {
    @Shadow
    private var boundPipeline: FrontendRenderPipeline? = null

    @Unique
    private fun illusionRecordWorldDraw(bindingSerialBeforeDraw: Long) {
        val pipeline = boundPipeline?.backendRenderPipeline() as? D3D12CompiledRenderPipeline ?: return
        WorldTransparencyCapture.recordDraw(pipeline, bindingSerialBeforeDraw)
        D3D12RasterMotionCapture.worldDrawRecorded(pipeline, bindingSerialBeforeDraw)
    }

    @WrapMethod(method = ["drawIndexed"], remap = false)
    private fun illusionRecordIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int,
        vertexOffset: Int, firstInstance: Int, original: Operation<Void?>) {
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        val disposition = if (indexCount > 0 && instanceCount > 0) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY
        OitProducerCapture.recordDrawCall(this, disposition, {
            OitDrawSelection.Indexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)
        }) {
            original.call(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)
        }
        if (disposition != OitDrawDisposition.EMPTY) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["draw"], remap = false)
    private fun illusionRecordVertices(vertexCount: Int, instanceCount: Int, firstVertex: Int,
        firstInstance: Int, original: Operation<Void?>) {
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        val disposition = if (vertexCount > 0 && instanceCount > 0) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY
        OitProducerCapture.recordDrawCall(this, disposition, {
            OitDrawSelection.Vertices(vertexCount, instanceCount, firstVertex, firstInstance)
        }) {
            original.call(vertexCount, instanceCount, firstVertex, firstInstance)
        }
        if (disposition != OitDrawDisposition.EMPTY) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["drawIndexedIndirect"], remap = false)
    private fun illusionRecordIndexedIndirect(commands: GpuBufferSlice, drawCount: Int, original: Operation<Void?>) {
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (drawCount > 0) OitDrawDisposition.INDIRECT_COMMANDS else OitDrawDisposition.EMPTY,
            { OitDrawSelection.Indirect(OitDrawSelection.IndirectKind.INDEXED, commands, drawCount) }) {
            original.call(commands, drawCount)
        }
        if (drawCount > 0) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["drawIndirect"], remap = false)
    private fun illusionRecordIndirect(commands: GpuBufferSlice, drawCount: Int, original: Operation<Void?>) {
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (drawCount > 0) OitDrawDisposition.INDIRECT_COMMANDS else OitDrawDisposition.EMPTY,
            { OitDrawSelection.Indirect(OitDrawSelection.IndirectKind.VERTICES, commands, drawCount) }) {
            original.call(commands, drawCount)
        }
        if (drawCount > 0) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["multiDrawIndexed(Ljava/nio/IntBuffer;III)V"], remap = false)
    private fun illusionRecordIndexedBatch(parameters: IntBuffer, instanceCount: Int, firstInstance: Int,
        drawCount: Int, original: Operation<Void?>) {
        val first = parameters.position()
        val hasGeometry = instanceCount > 0 && (0 until drawCount).any { parameters.get(first + it * 3 + 1) > 0 }
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (hasGeometry) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY, {
                OitDrawSelection.IndexedBatch(List(drawCount) { index ->
                    val position = first + index * 3
                    OitDrawSelection.Indexed(parameters.get(position + 1), instanceCount,
                        parameters.get(position), parameters.get(position + 2), firstInstance)
                })
            }) {
            original.call(parameters, instanceCount, firstInstance, drawCount)
        }
        if (hasGeometry) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"], remap = false)
    private fun illusionRecordIndexedColumns(firstIndices: PointerBuffer, indexCounts: IntBuffer,
        vertexOffsets: IntBuffer, drawCount: Int, original: Operation<Void?>) {
        val first = indexCounts.position()
        val hasGeometry = (0 until drawCount).any { indexCounts.get(first + it) > 0 }
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (hasGeometry) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY, {
                OitDrawSelection.IndexedOffsetBatch(List(drawCount) { index ->
                    OitDrawSelection.IndexedByteOffset(firstIndices.get(firstIndices.position() + index),
                        indexCounts.get(first + index), vertexOffsets.get(vertexOffsets.position() + index))
                })
            }) {
            original.call(firstIndices, indexCounts, vertexOffsets, drawCount)
        }
        if (hasGeometry) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["multiDraw(Ljava/nio/IntBuffer;III)V"], remap = false)
    private fun illusionRecordVertexBatch(parameters: IntBuffer, instanceCount: Int, firstInstance: Int,
        drawCount: Int, original: Operation<Void?>) {
        val first = parameters.position()
        val hasGeometry = instanceCount > 0 && (0 until drawCount).any { parameters.get(first + it * 2 + 1) > 0 }
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (hasGeometry) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY, {
                OitDrawSelection.VertexBatch(List(drawCount) { index ->
                    val position = first + index * 2
                    OitDrawSelection.Vertices(parameters.get(position + 1), instanceCount,
                        parameters.get(position), firstInstance)
                })
            }) {
            original.call(parameters, instanceCount, firstInstance, drawCount)
        }
        if (hasGeometry) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"], remap = false)
    private fun illusionRecordVertexColumns(firstVertices: IntBuffer, vertexCounts: IntBuffer,
        drawCount: Int, original: Operation<Void?>) {
        val first = vertexCounts.position()
        val hasGeometry = (0 until drawCount).any { vertexCounts.get(first + it) > 0 }
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (hasGeometry) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY, {
                OitDrawSelection.VertexBatch(List(drawCount) { index ->
                    OitDrawSelection.Vertices(vertexCounts.get(first + index), 1,
                        firstVertices.get(firstVertices.position() + index), 0)
                })
            }) {
            original.call(firstVertices, vertexCounts, drawCount)
        }
        if (hasGeometry) illusionRecordWorldDraw(bindingSerial)
    }

    @WrapMethod(method = ["drawMultipleIndexed"], remap = false)
    private fun illusionRecordIndexedCollection(draws: Collection<RenderPass.Draw<*>>, defaultIndexBuffer: GpuBuffer?,
        defaultIndexType: IndexType?, dynamicUniforms: Collection<String>, uniformArgument: Any?, original: Operation<Void?>) {
        val hasGeometry = draws.any { it.indexCount() > 0 }
        val bindingSerial = D3D12RasterMotionCapture.worldHistoryBindingSerial()
        OitProducerCapture.recordDrawCall(this,
            if (hasGeometry) OitDrawDisposition.GEOMETRY else OitDrawDisposition.EMPTY,
            { OitDrawSelection.IndexedCollection(draws, defaultIndexBuffer, defaultIndexType) }) {
            original.call(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument)
        }
        if (hasGeometry) illusionRecordWorldDraw(bindingSerial)
    }
}
