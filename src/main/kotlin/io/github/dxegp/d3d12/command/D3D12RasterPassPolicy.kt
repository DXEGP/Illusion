package io.github.dxegp.d3d12.command

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.IndexType
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.textures.GpuSampler

internal enum class D3D12RasterRoute {
    INDIRECT,
    ORDERED_INDIRECT,
    TERRAIN_WORK_GRAPH,
}

internal class D3D12MotionBindings(val parameters: GpuBufferSlice, val history: GpuBufferSlice) {
    companion object {
        const val PARAMETER_NAME = "DXRMRasterMotionParameters"
        const val HISTORY_NAME = "DXRMRasterMotionHistory"
    }
}

internal interface D3D12TerrainPreparedFacts {
    val pipeline: Long
    val vertexBuffer: Long
    val vertexOffset: Long
    val bindingHandles: LongArray
    val bindingOffsets: LongArray
    val bindingLengths: LongArray
    val sectionCount: Int
    val meshletCount: Int
}

internal enum class D3D12RasterAttachmentRole {
    CONTRIBUTION_GUIDE,
    CLOUD_GUIDE,
    UNRECORDED_COVERAGE,
}

internal data class D3D12AdditionalRasterAttachment(val target: Long, val role: D3D12RasterAttachmentRole) {
    init {
        require(target != 0L)
    }
}

internal class D3D12AdditionalRasterBindings(
    val attachment: D3D12AdditionalRasterAttachment,
    val pso: Long,
    val lineQuadPso: Long,
    val parameters: GpuBufferSlice?,
    val parameterName: String,
    val rasterMotionPso: Long = 0L,
    val rasterMotionLineQuadPso: Long = 0L,
) {
    val target: Long get() = attachment.target
}

internal class D3D12TerrainDraw(
    val pipeline: D3D12CompiledRenderPipeline,
    val commands: GpuBufferSlice,
    val drawCount: Int,
    val vertexBuffer: D3D12GpuBuffer,
    val vertexOffset: Long,
    val rasterMotionTarget: Long,
    val additionalAttachment: D3D12AdditionalRasterAttachment?,
    val uniforms: Map<String, GpuBufferSlice>,
    val materialView: GpuTextureView? = null,
    val materialSampler: GpuSampler? = null,
    val lightmap: D3D12RasterSampledView? = null,
    val stageMaterialView: GpuTextureView? = materialView,
    val stageMaterialSampler: GpuSampler? = materialSampler,
)

internal interface D3D12PreparedRasterDispatch : AutoCloseable {
    val pipeline: Long
    val capture: D3D12PreparedRasterCapture?
    val terrainFacts: D3D12TerrainPreparedFacts?
    fun recordPreparedFacts()
    fun draw(context: Long, vertexBuffer: D3D12GpuBuffer, vertexOffset: Long): Boolean
}

internal data class D3D12IndexedRasterDraw(
    val indexCount: Int,
    val instanceCount: Int,
    val firstIndex: Int,
    val vertexOffset: Int,
    val firstInstance: Int,
    val source: D3D12IndexedRasterSource? = null,
)

internal data class D3D12IndexedRasterSource(
    val vertexBuffer: GpuBuffer,
    val vertexByteOffset: Long,
    val vertexStride: Int,
    val indexBuffer: GpuBuffer,
    val indexType: IndexType,
    val materialView: GpuTextureView?,
    val materialSampler: GpuSampler?,
    val uniforms: Map<String, GpuBufferSlice> = emptyMap(),
    val sampledViews: Map<String, D3D12RasterSampledView> = emptyMap(),
)

internal data class D3D12RasterSampledView(val view: GpuTextureView, val sampler: GpuSampler)

internal interface D3D12RasterDrawCapture {
    fun prepareIndexed(pipeline: D3D12CompiledRenderPipeline,
        draw: D3D12IndexedRasterDraw): D3D12PreparedRasterCapture?
    fun recordUncapturedDraw(pipeline: D3D12CompiledRenderPipeline,
        additionalAttachment: D3D12AdditionalRasterAttachment?)
}

internal interface D3D12PreparedRasterCapture {
    val pipeline: Long
    fun bindTerrainFacts(facts: D3D12TerrainPreparedFacts) { }
    fun <Result> recordDraw(action: () -> Result): Result
}

internal interface D3D12RasterPassPolicy {
    val drawCapture: D3D12RasterDrawCapture?
    fun selectPipeline(pipeline: D3D12CompiledRenderPipeline): D3D12RasterRoute
    fun recordAdditionalBindings(pipeline: D3D12CompiledRenderPipeline): D3D12AdditionalRasterBindings?
    fun recordDraw(additionalTarget: Long)
    fun recordColorWrite(target: Long)
    fun captureUniform(name: String, value: GpuBufferSlice)
    fun setVertexInput(buffer: GpuBuffer?, byteOffset: Long)
    fun retainIndexedGeometryReads(vertexBuffer: GpuBuffer?, indexBuffer: GpuBuffer?)
    fun setViewport(x: Int, y: Int, width: Int, height: Int)
    fun prepareMotionBindings(vertexBase: Int, instanceCount: Int): D3D12MotionBindings
    fun prepareIndirectMotionBindings(): D3D12MotionBindings
    fun recordTerrain(draw: D3D12TerrainDraw, record: (D3D12PreparedRasterDispatch) -> Unit)
}
