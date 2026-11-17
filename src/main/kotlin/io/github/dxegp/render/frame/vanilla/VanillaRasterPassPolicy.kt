package io.github.dxegp.render.frame.vanilla

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12MotionBindings
import io.github.dxegp.d3d12.command.D3D12PreparedRasterDispatch
import io.github.dxegp.d3d12.command.D3D12RasterPassPolicy
import io.github.dxegp.d3d12.command.D3D12RasterRoute
import io.github.dxegp.d3d12.command.D3D12TerrainDraw
import io.github.dxegp.d3d12.command.D3D12TerrainPreparedFacts
import io.github.dxegp.d3d12.command.D3D12AdditionalRasterBindings
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.OitTerrainMaterials
import io.github.dxegp.render.reconstruction.oit.OitTerrainRecords
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import io.github.dxegp.render.reconstruction.oit.OitNativeTerrainStageReceiptResult
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.render.compilation.VanillaPipelineSemantics
import io.github.dxegp.render.temporal.D3D12RasterMotion
import io.github.dxegp.render.temporal.D3D12Temporal
import io.github.dxegp.render.terrain.mesh.D3D12MeshletResidency
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import io.github.dxegp.render.vrs.D3D12VariableShadingRate

internal class VanillaRasterPassPolicy(
    private val context: Long,
    private val transientMemory: D3D12TransientMemory,
    private val residency: D3D12MeshletResidency,
    private val semantics: VanillaPipelineSemantics,
) : D3D12RasterPassPolicy {
    override val drawCapture get() = OitReconstructionInputs.drawCapture

    private var selectedPipeline: D3D12CompiledRenderPipeline? = null
    private var selectedRoute = D3D12RasterRoute.INDIRECT
    private var vertexBuffer: GpuBuffer? = null
    private var vertexOffset = 0L
    private var viewportX = 0
    private var viewportY = 0
    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun selectPipeline(pipeline: D3D12CompiledRenderPipeline): D3D12RasterRoute {
        selectedPipeline = pipeline
        val route = semantics.route(pipeline.info())
        selectedRoute = if (route == D3D12RasterRoute.TERRAIN_WORK_GRAPH && !D3D12Settings.meshTerrain)
            D3D12RasterRoute.INDIRECT
        else route
        return selectedRoute
    }

    override fun captureUniform(name: String, value: GpuBufferSlice) {
        D3D12Temporal.captureUniform(name, value)
    }

    override fun recordAdditionalBindings(pipeline: D3D12CompiledRenderPipeline): D3D12AdditionalRasterBindings? =
        OitReconstructionInputs.recordBindings(pipeline, transientMemory, selectedRoute)

    override fun recordDraw(additionalTarget: Long) {
        OitReconstructionInputs.recordDraw(checkNotNull(selectedPipeline), additionalTarget)
    }

    override fun recordColorWrite(target: Long) {
        OitTerrainMaterials.recordTextureWrite(target)
    }

    override fun setVertexInput(buffer: GpuBuffer?, byteOffset: Long) {
        vertexBuffer = buffer
        vertexOffset = byteOffset
    }

    override fun retainIndexedGeometryReads(vertexBuffer: GpuBuffer?, indexBuffer: GpuBuffer?) {
        D3D12MeshDrawCapture.retainOriginalDrawReads(transientMemory, vertexBuffer, indexBuffer)
    }

    override fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        viewportX = x
        viewportY = y
        viewportWidth = width
        viewportHeight = height
    }

    override fun prepareMotionBindings(vertexBase: Int, instanceCount: Int): D3D12MotionBindings {
        val selected = checkNotNull(selectedPipeline)
        val stride = selected.info().vertexFormatBindings.firstOrNull()?.vertexSize ?: 0
        val buffer = if (selected.rasterMotionExact && instanceCount == 1) vertexBuffer else null
        return D3D12RasterMotion.bindings(
            transientMemory, buffer, vertexOffset + vertexBase.toLong() * stride, stride,
            vertexBase, instanceCount, viewportX, viewportY, viewportWidth, viewportHeight,
        )
    }

    override fun prepareIndirectMotionBindings(): D3D12MotionBindings =
        D3D12RasterMotion.neutralBindings(transientMemory, viewportX, viewportY, viewportWidth, viewportHeight)

    override fun recordTerrain(draw: D3D12TerrainDraw, record: (D3D12PreparedRasterDispatch) -> Unit) {
        D3D12VariableShadingRate.scope(context, draw.pipeline.info().location.toString()) {
            check(D3D12Settings.meshTerrain) { "Selected terrain Work Graph rendering is disabled" }
            val stageInvocation = OitProducerCapture.terrainStageInvocation()
            var stageReceipt: OitNativeTerrainStageReceiptResult = OitNativeTerrainStageReceiptResult.EntryUnavailable
            val stageFactsConsumer: ((D3D12TerrainPreparedFacts) -> Unit)? = if (stageInvocation == null) null else {
                preparedFacts -> OitTerrainRecords.recordPreparedStage(stageInvocation, draw, preparedFacts, stageReceipt)
            }
            val capture = OitTerrainRecords.prepare(draw)
            val prepared = checkNotNull(D3D12TerrainWorkGraph.prepare(
                draw.pipeline, draw.commands, draw.drawCount, draw.vertexBuffer, draw.vertexOffset,
                context, transientMemory, residency, draw.uniforms, D3D12Settings.meshTerrainCull,
                D3D12VariableShadingRate.msBand(), draw.rasterMotionTarget, draw.additionalAttachment, capture,
                stageFactsConsumer,
            )) { "Selected terrain Work Graph draw could not be prepared" }
            prepared.use {
                if (capture != null) {
                    D3D12TerrainWorkGraph.bindCaptureFacts(it, capture)
                }
                val records = if (stageInvocation == null) null else OitReconstructionInputs.terrainRecordFrames()
                if (records == null) record(it)
                else records.recordTerrainStage(checkNotNull(stageInvocation), it) {
                    record(it)
                }.also { receipt -> stageReceipt = receipt }
                it.recordPreparedFacts()
            }
        }
    }
}
