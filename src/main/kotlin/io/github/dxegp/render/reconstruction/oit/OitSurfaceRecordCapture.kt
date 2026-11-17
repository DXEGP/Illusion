package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12AdditionalRasterAttachment
import io.github.dxegp.d3d12.command.D3D12IndexedRasterDraw
import io.github.dxegp.d3d12.command.D3D12PreparedRasterCapture
import io.github.dxegp.d3d12.command.D3D12RasterDrawCapture
import io.github.dxegp.d3d12.command.D3D12RasterAttachmentRole
import io.github.dxegp.d3d12.ffi.OitShaderPreludeV1
import io.github.dxegp.d3d12.shader.D3D12OitCaptureProfile
import net.minecraft.client.renderer.oit.OitStage

internal class OitSurfaceRecordCapture(
    private val records: OitRecordFrames,
    private val unrecordedTarget: Long,
) : D3D12RasterDrawCapture {
    private var recordedCoverageAvailability = OitUnrecordedCoverageAvailability.COMPLETE

    val coverageAvailability: OitUnrecordedCoverageAvailability
        get() = if (OitFeatureInputs.consumedSourcesValid) recordedCoverageAvailability
        else OitUnrecordedCoverageAvailability.UNAVAILABLE

    override fun recordUncapturedDraw(pipeline: D3D12CompiledRenderPipeline,
        additionalAttachment: D3D12AdditionalRasterAttachment?) {
        val facts = OitProducerCapture.drawInputs()
        if (facts?.accumulate != true && "OIT_ACCUMULATE" !in pipeline.info().shaderDefines.flags()) return
        OitProducerCapture.recordDraw {
            if (additionalAttachment?.role != D3D12RasterAttachmentRole.UNRECORDED_COVERAGE ||
                additionalAttachment.target != unrecordedTarget || facts?.frame?.identity != records.frame.identity ||
                facts.frame.historyGeneration != records.frame.historyGeneration) {
                recordedCoverageAvailability = OitUnrecordedCoverageAvailability.UNAVAILABLE
            }
        }
    }

    override fun prepareIndexed(
        pipeline: D3D12CompiledRenderPipeline,
        draw: D3D12IndexedRasterDraw,
    ): D3D12PreparedRasterCapture? {
        val facts = OitProducerCapture.drawInputs() ?: return null
        val scope = facts.scope ?: return null
        if (scope.producer.kind == OitProducerKind.FEATURE)
            return OitFeatureRecordCapture.prepare(records, facts, pipeline, draw)
        val expectedProfile = when (scope.producer.kind) {
            OitProducerKind.CLOUD -> D3D12OitCaptureProfile.CLOUD
            OitProducerKind.WEATHER -> D3D12OitCaptureProfile.PARTICLE
            OitProducerKind.BORDER -> D3D12OitCaptureProfile.BORDER
            else -> return null
        }
        if (scope.stage != OitStage.ACCUMULATE ||
            facts.pass?.stage != scope.stage || !facts.knownProducer) return null
        if (facts.frame.identity != records.frame.identity ||
            facts.frame.historyGeneration != records.frame.historyGeneration || scope.serial <= 0L) {
            throw invalid(facts, "active producer frame differs from OIT records frame")
        }
        val capture = pipeline.oitCaptureProgram ?: return null
        if (capture.profile != expectedProfile) return null
        if (capture.generation != records.programGeneration || pipeline.oitCapturePso == 0L ||
            capture.shaderAbiVersion != OitShaderPreludeV1.SUPPORTED_SHADER_ABI || capture.shaderContractHash == 0L) {
            throw invalid(facts, "capture program generation or PSO differs from records")
        }
        val captured = OitProducerCapture.captureDrawSurfaces() ?: return null
        val surfaces = if (scope.producer.kind == OitProducerKind.BORDER) {
            if (draw.indexCount != 6 || draw.firstIndex !in 0..18 || draw.firstIndex % 6 != 0 ||
                draw.vertexOffset != 0 || draw.instanceCount != 1 || draw.firstInstance != 0) return null
            val side = draw.firstIndex / 6
            val pair = captured.surfaces.singleOrNull { (it.current as? OitBorderSurface)?.side == side }
                ?: return null
            OitSurfaceDraw(captured.frame, captured.previousFrame, captured.producerId,
                captured.producerGeneration, side * 4, listOf(pair), captured.material)
        } else captured
        if (surfaces.frame !== records.frame) throw invalid(facts, "surface draw belongs to another frozen frame")
        val batch = OitSurfaceRecords.capture(surfaces, capture.generation, scope.serial)
        if (draw.indexCount != batch.indexCount || draw.firstIndex != batch.firstIndex || draw.vertexOffset != 0 ||
            draw.instanceCount != 1 || draw.firstInstance != 0 || draw.indexCount <= 0) {
            throw invalid(facts, "indexed draw does not match captured surface range")
        }
        val metadata = when (val publication = records.publishMetadata(batch.metadata)) {
            is OitRecordFrames.PublishedMetadata -> publication
            OitRecordFrames.MetadataCapacityExceeded -> return null
        }
        return PreparedCapture(records, facts, batch, metadata, capture.generation, pipeline.oitCapturePso)
    }

    private class PreparedCapture(
        private val records: OitRecordFrames,
        private val facts: OitDrawFacts,
        private val batch: OitSurfaceRecordBatch,
        private val metadata: OitRecordFrames.PublishedMetadata,
        private val generation: Long,
        override val pipeline: Long,
    ) : D3D12PreparedRasterCapture {
        private enum class Phase { PREPARED, RECORDING, COMPLETED, FAILED }

        private var phase = Phase.PREPARED

        override fun <Result> recordDraw(action: () -> Result): Result {
            if (phase != Phase.PREPARED || OitProducerCapture.drawInputs() !== facts ||
                records.programGeneration != generation || batch.surfaces.frame !== records.frame) {
                throw OitRecordException(OitRecordFailure.STALE, "record surface draw", records.frame.identity,
                    "Prepared surface capture is consumed or no longer belongs to the active draw")
            }
            phase = Phase.RECORDING
            try {
                val result = records.recordDraw(metadata, batch.draw, action)
                OitProducerCapture.recordDraw {
                    OitSurfaceInputs.recordDrawConsumption(batch.surfaces)
                }
                phase = Phase.COMPLETED
                return result
            } catch (failure: Throwable) {
                phase = Phase.FAILED
                throw failure
            }
        }
    }

    private fun invalid(facts: OitDrawFacts, detail: String): OitRecordException =
        OitRecordException(OitRecordFailure.INVALID_CONTRACT, "prepare indexed surface draw", facts.frame.identity, detail)
}
