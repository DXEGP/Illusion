package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12IndexedRasterDraw
import io.github.dxegp.d3d12.command.D3D12PreparedRasterCapture
import io.github.dxegp.d3d12.ffi.OitShaderPreludeV1
import net.minecraft.client.renderer.oit.OitStage

internal object OitFeatureRecordCapture {
    fun prepare(records: OitRecordFrames, facts: OitDrawFacts, pipeline: D3D12CompiledRenderPipeline,
        draw: D3D12IndexedRasterDraw): D3D12PreparedRasterCapture? {
        val scope = facts.scope ?: return null
        if (scope.producer.kind != OitProducerKind.FEATURE || !facts.knownProducer ||
            scope.stage != OitStage.ACCUMULATE || facts.pass?.hasAccumulateInputs() != true) return null
        if (facts.frame.identity != records.frame.identity ||
            facts.frame.historyGeneration != records.frame.historyGeneration || scope.serial <= 0L) {
            throw OitRecordException(OitRecordFailure.STALE, "prepare FEATURE draw", facts.frame.identity,
                "Active producer differs from the records frame")
        }
        val program = pipeline.oitCaptureProgram ?: return null
        if (pipeline.oitCapturePso == 0L || program.generation != records.programGeneration ||
            program.shaderAbiVersion != OitShaderPreludeV1.SUPPORTED_SHADER_ABI || program.shaderContractHash == 0L) {
            throw OitRecordException(OitRecordFailure.INVALID_CONTRACT, "prepare FEATURE draw", facts.frame.identity,
                "Capture program differs from the records contract")
        }
        val defines = pipeline.info().shaderDefines.flags()
        if ("GLINT" in defines || "GLINT_SPECIAL" in defines || "APPLY_TEXTURE_MATRIX" in defines ||
            "DISSOLVE" in defines) return null
        val surfaces = OitFeatureInputs.prepare(draw, pipeline) ?: return null
        val batch = OitFeatureRecords.capture(surfaces, program.generation, scope.serial) ?: return null
        val metadata = when (val publication = records.publishMetadata(batch.metadata)) {
            is OitRecordFrames.PublishedMetadata -> publication
            OitRecordFrames.MetadataCapacityExceeded -> return null
        }
        return Prepared(records, facts, batch, metadata, pipeline.oitCapturePso)
    }

    private class Prepared(
        private val records: OitRecordFrames,
        private val facts: OitDrawFacts,
        private val batch: OitFeatureRecordBatch,
        private val metadata: OitRecordFrames.PublishedMetadata,
        override val pipeline: Long,
    ) : D3D12PreparedRasterCapture {
        private enum class Phase { PREPARED, RECORDING, COMPLETED, FAILED }
        private var phase = Phase.PREPARED

        override fun <Result> recordDraw(action: () -> Result): Result {
            if (phase != Phase.PREPARED || OitProducerCapture.drawInputs() !== facts ||
                records.frame !== batch.surfaces.frame || !batch.surfaces.valid) {
                throw OitRecordException(OitRecordFailure.STALE, "record FEATURE draw", records.frame.identity,
                    "Prepared draw was consumed or changed source")
            }
            phase = Phase.RECORDING
            try {
                val result = records.recordDraw(metadata, batch.draw, action)
                OitProducerCapture.recordDraw { OitFeatureInputs.recordDrawConsumption(batch.surfaces) }
                phase = Phase.COMPLETED
                return result
            } catch (failure: Throwable) {
                phase = Phase.FAILED
                throw failure
            }
        }
    }
}
