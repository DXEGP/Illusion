package io.github.dxegp.render.reconstruction.oit

import com.mojang.blaze3d.systems.RenderSystem
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.allocateFfmArray
import io.github.dxegp.render.frame.vanilla.PreparedBinding
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import io.github.dxegp.render.reconstruction.OpaqueReconstructionInputs
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import org.joml.Matrix4f
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment

internal enum class OitRecordFailure {
    INVALID_CONTRACT, UNSUPPORTED, OVER_BUDGET, BUSY, STALE, UNKNOWN_SUBMISSION, RECORDING_FAILED, NATIVE_CONTRACT,
}

internal sealed interface OitTerrainTilePreparation {
    data class Consumed(val batches: List<OitTerrainTileBatch>) : OitTerrainTilePreparation {
        init {
            require(batches.isNotEmpty())
        }
    }
    data class Unavailable(val reason: Reason) : OitTerrainTilePreparation
    data class Rejected(val result: Int, val detail: String) : OitTerrainTilePreparation

    enum class Reason {
        NO_TILE_BATCH,
        INCOMPLETE_PACKET_SET,
        EXECUTION_STREAM_UNAVAILABLE,
        NATIVE_ENTRY_UNAVAILABLE,
        INVALID_RESOURCES,
        UNSUPPORTED_ROUTE,
        UNSUPPORTED_CONTENT,
        FRAME_NOT_SEALED,
        STALE_FRAME,
        TERRAIN_PRODUCER_FACTS_UNAVAILABLE,
    }
}

internal class OitRecordException(
    val failure: OitRecordFailure,
    val operation: String,
    val identity: VanillaFrameIdentity,
    detail: String,
) : IllegalStateException("OIT $operation failed for $identity ($failure): $detail")

internal enum class OitUnrecordedCoverageAvailability {
    COMPLETE, UNAVAILABLE,
}

internal class OitUnrecordedCoverage(
    val binding: PreparedBinding,
    val availability: OitUnrecordedCoverageAvailability,
)

internal class OitContributionResolveTargets(
    val correctedTransparency: PreparedBinding,
    val status: PreparedBinding,
)

internal class OitRecordFrames private constructor(
    private val encoder: D3D12CommandEncoderBackend,
    private val inputs: OpaqueReconstructionInputs,
    val programGeneration: Long,
    val recordingSerial: Long,
    private val handle: Long,
) : AutoCloseable {
    internal data class SampledTextureCapture(
        val captureId: Long,
        val texture: Long,
        val ownerIdentity: Long,
        val ownerGeneration: Long,
        val contentVersion: Long,
        val firstSubresource: Int,
        val subresourceCount: Int,
        val resourceVersion: Long,
    )

    internal sealed interface SampledTextureCaptureResult {
        data class Captured(val content: SampledTextureCapture) : SampledTextureCaptureResult
        data class Unavailable(val failure: OitRecordFailure, val detail: String) : SampledTextureCaptureResult
    }

    internal fun captureSampledTextureContent(view: Long,
        budget: Long = 64L * 1024L * 1024L): SampledTextureCaptureResult {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && !drawBound)
        return encoder.recordCommands { context ->
            Arena.ofConfined().use { arena ->
                val output = arena.allocate(MCDRLayout.OIT_EXECUTION_TEXTURE_V1)
                val result = MCDR_ABI.D3D12OitCaptureExecutionTextureV1(context, handle, view, budget, output)
                if (result != 1) {
                    val failure = when (result) {
                        -1 -> OitRecordFailure.INVALID_CONTRACT
                        -2 -> OitRecordFailure.UNSUPPORTED
                        -3 -> OitRecordFailure.OVER_BUDGET
                        -4 -> OitRecordFailure.BUSY
                        -5 -> OitRecordFailure.STALE
                        -6 -> OitRecordFailure.UNKNOWN_SUBMISSION
                        -8 -> OitRecordFailure.RECORDING_FAILED
                        else -> OitRecordFailure.NATIVE_CONTRACT
                    }
                    val detail = "Native status $result: ${MCDR_ABI.D3D12LastError()}"
                    if (failure == OitRecordFailure.UNSUPPORTED || failure == OitRecordFailure.OVER_BUDGET)
                        return@use SampledTextureCaptureResult.Unavailable(failure, detail)
                    throw OitRecordException(failure, "capture execution texture", inputs.frame.identity, detail)
                }
                val content = SampledTextureCapture(
                    output.get(MCDRLayout.I64, TextureCaptureOffsets.captureId),
                    output.get(MCDRLayout.PTR, TextureCaptureOffsets.texture).address(),
                    output.get(MCDRLayout.I64, TextureCaptureOffsets.ownerIdentity),
                    output.get(MCDRLayout.I64, TextureCaptureOffsets.ownerGeneration),
                    output.get(MCDRLayout.I64, TextureCaptureOffsets.contentVersion),
                    output.get(MCDRLayout.I32, TextureCaptureOffsets.firstSubresource),
                    output.get(MCDRLayout.I32, TextureCaptureOffsets.subresourceCount),
                    output.get(MCDRLayout.I64, TextureCaptureOffsets.resourceVersion),
                )
                if (output.get(MCDRLayout.I32, TextureCaptureOffsets.schemaVersion) != 1 ||
                    output.get(MCDRLayout.I32, TextureCaptureOffsets.structSize).toLong() != TextureCaptureOffsets.size ||
                    content.captureId == 0L || content.texture == 0L || content.ownerIdentity == 0L ||
                    content.ownerGeneration == 0L || content.contentVersion == 0L || content.firstSubresource < 0 ||
                    content.subresourceCount <= 0 || content.resourceVersion != inputs.frame.identity.resourceVersion) {
                    throw OitRecordException(OitRecordFailure.NATIVE_CONTRACT, "capture execution texture",
                        inputs.frame.identity, "Native texture capture has an invalid header, identity, or range")
                }
                SampledTextureCaptureResult.Captured(content)
            }
        }
    }
    internal enum class Phase { RECORDING, SEALED, RESOLVED, ABORTED }

    internal sealed interface MetadataPublication

    internal data object MetadataCapacityExceeded : MetadataPublication

    internal class PublishedMetadata internal constructor(
        internal val owner: OitRecordFrames,
        internal val handle: Long,
    ) : MetadataPublication

    private var phase = Phase.RECORDING
    private var drawBound = false
    private val executionStream = OitNativeExecutionStream(inputs.frame.identity, inputs.frame.historyGeneration,
        recordingSerial, programGeneration, handle)
    val nativeExecutionResult: OitNativeExecutionResult? get() = executionStream.result
    val frame get() = inputs.frame

    fun <Result> recordExecutionOperation(facts: OitNativeOperationReceipts.OperationFacts,
        action: () -> Result): Pair<Result, OitNativeOperationReceiptQuery> {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && facts.identity == inputs.frame.identity &&
            facts.frameSerial == recordingSerial && facts.programGeneration == programGeneration)
        return encoder.recordCommands { context -> OitNativeOperationReceipts.record(context, handle, facts, action) }
    }

    fun recordTerrainStage(invocation: OitTerrainStageInvocation, prepared: D3D12TerrainWorkGraph.Prepared,
        action: () -> Unit): OitNativeTerrainStageReceiptResult {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && invocation.frame.identity == inputs.frame.identity)
        return encoder.recordCommands { context ->
            OitNativeStageReceipts.recordDraw(OitTerrainNativeRecording(context, handle, recordingSerial, programGeneration),
                invocation, prepared, action)
        }
    }

    fun queryNativeDrawReceipts(afterSequence: Long): OitNativeReceiptQuery {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING)
        return encoder.recordCommands { context -> executionStream.queryDrawReceipts(context, afterSequence) }
    }

    fun queryNativeDrawFrontier(): OitNativeReceiptFrontier {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING)
        return encoder.recordCommands { context -> executionStream.queryDrawFrontier(context) }
    }

    fun publishMetadata(rows: OitRecordMetadata): MetadataPublication {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && !drawBound)
        return encoder.recordCommands { context ->
            Arena.ofConfined().use { arena ->
                val input = arena.allocate(MCDRLayout.OIT_METADATA)
                writeHeader(input, MCDRLayout.OIT_METADATA)
                val sources = arena.allocateFfmArray(rows.sources.size, MCDRLayout.OIT_SOURCE)
                rows.sources.forEachIndexed { index, row ->
                    val target = sources.asSlice(index * SourceOffsets.size, SourceOffsets.size)
                    target.set(MCDRLayout.I64, SourceOffsets.producerId, row.producerId)
                    target.set(MCDRLayout.I64, SourceOffsets.producerGeneration, row.producerGeneration)
                    target.set(MCDRLayout.I64, SourceOffsets.shaderGeneration, row.shaderGeneration)
                    target.set(MCDRLayout.I32, SourceOffsets.producerKind, when (row.kind) {
                        OitProducerKind.TERRAIN -> 0
                        OitProducerKind.FEATURE -> 1
                        OitProducerKind.BORDER -> 2
                        OitProducerKind.WEATHER -> 3
                        OitProducerKind.CLOUD -> 4
                        OitProducerKind.CUSTOM_WEATHER -> 5
                        OitProducerKind.CUSTOM_CLOUD -> 6
                        OitProducerKind.CUSTOM_TRANSLUCENT -> 7
                    })
                    target.set(MCDRLayout.I32, SourceOffsets.validity, row.validities.wireValue)
                }
                val primitives = arena.allocateFfmArray(rows.primitives.size, MCDRLayout.OIT_PRIMITIVE)
                rows.primitives.forEachIndexed { index, row ->
                    val target = primitives.asSlice(index * PrimitiveOffsets.size, PrimitiveOffsets.size)
                    target.set(MCDRLayout.I64, PrimitiveOffsets.surfaceId, row.surface.surfaceId)
                    target.set(MCDRLayout.I64, PrimitiveOffsets.geometryGeneration, row.surface.geometryGeneration)
                    target.set(MCDRLayout.I64, PrimitiveOffsets.materialId, row.surface.materialId)
                    target.set(MCDRLayout.I64, PrimitiveOffsets.materialGeneration, row.surface.materialGeneration)
                    target.set(MCDRLayout.I64, PrimitiveOffsets.textureGeneration, row.surface.textureGeneration)
                    target.set(MCDRLayout.I32, PrimitiveOffsets.sourceIndex, row.sourceIndex)
                    target.set(MCDRLayout.I32, PrimitiveOffsets.previousIndex, row.previousIndex ?: -1)
                    target.set(MCDRLayout.I32, PrimitiveOffsets.flags, row.validities.wireValue)
                }
                val previous = if (rows.previous.isEmpty()) MemorySegment.NULL else
                    arena.allocateFfmArray(rows.previous.size, MCDRLayout.OIT_PREVIOUS_TRIANGLE).also { buffer ->
                        rows.previous.forEachIndexed { index, row ->
                            val target = buffer.asSlice(index * PreviousOffsets.size, PreviousOffsets.size)
                            MemorySegment.copy(row.clip(), 0, target, MCDRLayout.F32, PreviousOffsets.clip, 12)
                            MemorySegment.copy(row.materialUv(), 0, target, MCDRLayout.F32, PreviousOffsets.materialUv, 6)
                            target.set(MCDRLayout.I32, PreviousOffsets.flags, row.validities.wireValue)
                        }
                    }
                val sections = arena.allocateIntArray(rows.sectionPrimitiveBases)
                writeInt(input, MCDRLayout.OIT_METADATA, "sourceCount", rows.sources.size)
                writeInt(input, MCDRLayout.OIT_METADATA, "primitiveCount", rows.primitives.size)
                writeInt(input, MCDRLayout.OIT_METADATA, "previousCount", rows.previous.size)
                writeInt(input, MCDRLayout.OIT_METADATA, "sectionCount", rows.sectionPrimitiveBases.size)
                for ((name, pointer) in listOf("sources" to sources, "primitives" to primitives,
                    "previous" to previous, "sectionPrimitiveBases" to sections)) {
                    input.set(MCDRLayout.PTR, MCDRLayout.OIT_METADATA.byteOffset(groupElement(name)), pointer)
                }
                val output = arena.allocate(MCDRLayout.PTR)
                val result = MCDR_ABI.D3D12OitPublishMetadataV1(context, handle, input, output)
                metadataPublication(result, output, "publish metadata")
            }
        }
    }

    fun publishTerrainMetadata(input: MemorySegment): MetadataPublication {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && !drawBound)
        return encoder.recordCommands { context ->
            Arena.ofConfined().use { arena ->
                val output = arena.allocate(MCDRLayout.PTR)
                val result = MCDR_ABI.D3D12OitPublishTerrainMetadataV2(context, handle, input, output)
                metadataPublication(result, output, "publish terrain metadata")
            }
        }
    }

    private fun metadataPublication(result: Int, output: MemorySegment, operation: String): MetadataPublication {
        if (result == -7) return MetadataCapacityExceeded
        requireSuccess(result, operation)
        val token = output.get(MCDRLayout.PTR, 0).address()
        check(token != 0L)
        return PublishedMetadata(this, token)
    }

    internal inline fun <Result> recordDraw(metadata: PublishedMetadata, draw: OitRecordDraw,
        crossinline action: () -> Result): Result =
        recordBoundDraw(metadata, { context ->
            Arena.ofConfined().use { arena ->
                val layout = MCDRLayout.OIT_DRAW
                val input = arena.allocate(layout)
                writeHeader(input, layout)
                for ((name, value) in listOf("sourceIndex" to draw.sourceIndex, "primitiveBase" to draw.primitiveBase,
                    "primitiveCount" to draw.primitiveCount, "primitivesPerInstance" to draw.primitivesPerInstance,
                    "instanceCount" to draw.instanceCount, "firstInstance" to draw.firstInstance)) writeInt(input, layout, name, value)
                writeLong(input, layout, "producerScopeSerial", draw.producerScopeSerial)
                requireSuccess(MCDR_ABI.D3D12OitBindDrawV1(context, handle, metadata.handle, input), "bind draw")
            }
        }, action)

    internal inline fun <Result> recordTerrainDraw(metadata: PublishedMetadata, scopeSerial: Long,
        crossinline action: () -> Result): Result =
        recordBoundDraw(metadata, { context ->
            require(scopeSerial > 0L)
            Arena.ofConfined().use { arena ->
                val layout = MCDRLayout.OIT_TERRAIN_DRAW
                val input = arena.allocate(layout)
                writeInt(input, layout, "schemaVersion", 2)
                writeInt(input, layout, "structSize", Math.toIntExact(layout.byteSize()))
                writeLong(input, layout, "producerScopeSerial", scopeSerial)
                requireSuccess(MCDR_ABI.D3D12OitBindTerrainDrawV2(context, handle, metadata.handle, input),
                    "bind terrain draw")
            }
        }, action)

    private inline fun <Result> recordBoundDraw(metadata: PublishedMetadata, crossinline bind: (Long) -> Unit,
        crossinline action: () -> Result): Result {
        RenderSystem.assertOnRenderThread()
        check(metadata.owner === this && phase == Phase.RECORDING && !drawBound)
        return encoder.recordCommands { context ->
            bind(context)
            drawBound = true
            var failure: Throwable? = null
            try {
                action()
            } catch (drawFailure: Throwable) {
                failure = drawFailure
                throw drawFailure
            } finally {
                try {
                    requireSuccess(MCDR_ABI.D3D12OitUnbindDrawV1(context, handle), "unbind draw")
                    drawBound = false
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }

    fun seal(compositeInput: OitCompositeInputCapture) {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && !drawBound)
        encoder.recordCommands { context ->
            executionStream.seal(context, OitProducerCapture.executionResult(), compositeInput)
            requireSuccess(MCDR_ABI.D3D12OitSealFrameV1(context, handle), "seal frame")
        }
        phase = Phase.SEALED
    }

    fun prepareTerrainTiles(batch: OitTerrainTileBatch): OitTerrainTilePreparation {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.SEALED)
        if (batch.frameSerial != recordingSerial ||
            batch.worldEpoch != inputs.frame.identity.worldEpoch ||
            batch.resourceVersion != inputs.frame.identity.resourceVersion ||
            batch.programGeneration != programGeneration) {
            return OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.STALE_FRAME)
        }
        return encoder.recordCommands { context ->
            Arena.ofConfined().use { arena ->
                val result = MCDR_ABI.D3D12OitPrepareTerrainTilesV2(context, handle, batch.write(arena))
                readTerrainTilePreparation(result, batch)
            }
        }
    }

    fun prepareTerrainTilesV3(batch: OitTerrainTileBatch, packetOrdinal: Int,
        packetCount: Int): OitTerrainTilePreparation {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.SEALED)
        require(packetCount > 0 && packetOrdinal in 0 until packetCount)
        if (batch.frameSerial != recordingSerial ||
            batch.worldEpoch != inputs.frame.identity.worldEpoch ||
            batch.resourceVersion != inputs.frame.identity.resourceVersion ||
            batch.programGeneration != programGeneration) {
            return OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.STALE_FRAME)
        }
        return encoder.recordCommands { context ->
            Arena.ofConfined().use { arena ->
                val result = MCDR_ABI.D3D12OitPrepareTerrainTilesV3(
                    context, handle, batch.writeV3(arena, packetOrdinal, packetCount),
                )
                readTerrainTilePreparation(result, batch)
            }
        }
    }

    private fun readTerrainTilePreparation(result: Int, batch: OitTerrainTileBatch): OitTerrainTilePreparation =
        when (result) {
            0 -> OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.UNSUPPORTED_ROUTE)
            1 -> OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.UNSUPPORTED_CONTENT)
            2 -> OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.STALE_FRAME)
            3 -> OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.INVALID_RESOURCES)
            4 -> OitTerrainTilePreparation.Unavailable(OitTerrainTilePreparation.Reason.FRAME_NOT_SEALED)
            5 -> OitTerrainTilePreparation.Consumed(listOf(batch))
            6 -> OitTerrainTilePreparation.Rejected(result,
                "Terrain tile GPU recording failed; command submission is invalid")
            Int.MIN_VALUE -> OitTerrainTilePreparation.Unavailable(
                OitTerrainTilePreparation.Reason.NATIVE_ENTRY_UNAVAILABLE)
            else -> OitTerrainTilePreparation.Rejected(result, MCDR_ABI.D3D12LastError())
        }

    fun resolveRecords(currentTransparency: PreparedBinding, unrecordedCoverage: OitUnrecordedCoverage,
        targets: OitContributionResolveTargets) {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.SEALED)
        encoder.recordCommands { context ->
            Arena.ofConfined().use { arena ->
                val layout = MCDRLayout.OIT_RESOLVE
                val base = arena.allocate(layout)
                writeInt(base, layout, "schemaVersion", 3)
                writeInt(base, layout, "structSize", Math.toIntExact(layout.byteSize()))
                writeIdentity(base.asSlice(layout.byteOffset(groupElement("identity")), MCDRLayout.FRAME_IDENTITY.byteSize()), inputs.frame.identity)
                base.set(MCDRLayout.PTR, layout.byteOffset(groupElement("frame")), MemorySegment.ofAddress(handle))
                writeBinding(base, layout, "currentTransparency", currentTransparency)
                writeBinding(base, layout, "unrecordedCoverage", unrecordedCoverage.binding)
                writeBinding(base, layout, "correctedTransparency", targets.correctedTransparency)
                writeBinding(base, layout, "statusOutput", targets.status)
                writeInt(base, layout, "flags", when (unrecordedCoverage.availability) {
                    OitUnrecordedCoverageAvailability.COMPLETE -> 0
                    OitUnrecordedCoverageAvailability.UNAVAILABLE -> MCDR_ABI.OIT_RESOLVE_UNRECORDED_COVERAGE_UNAVAILABLE
                })
                writeInt(base, layout, "reserved", 0)
                requireSuccess(MCDR_ABI.D3D12OitResolveRecordsV3(context, base), "resolve records")
            }
        }
        phase = Phase.RESOLVED
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (phase == Phase.ABORTED) return
        encoder.contextControl { context -> requireSuccess(MCDR_ABI.D3D12OitAbortFrameV1(context, handle), "retire frame") }
        executionStream.retireWithFrame()
        phase = Phase.ABORTED
        drawBound = false
    }

    private fun requireSuccess(result: Int, operation: String) {
        if (result == 1) return
        throw OitRecordException(when (result) {
            -1 -> OitRecordFailure.INVALID_CONTRACT
            -2 -> OitRecordFailure.UNSUPPORTED
            -3 -> OitRecordFailure.OVER_BUDGET
            -4 -> OitRecordFailure.BUSY
            -5 -> OitRecordFailure.STALE
            -6 -> OitRecordFailure.UNKNOWN_SUBMISSION
            -8 -> OitRecordFailure.RECORDING_FAILED
            else -> OitRecordFailure.NATIVE_CONTRACT
        }, operation, inputs.frame.identity, MCDR_ABI.D3D12LastError())
    }

    companion object {
        private object TextureCaptureOffsets {
            private val layout = MCDRLayout.OIT_EXECUTION_TEXTURE_V1
            val size = layout.byteSize()
            val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
            val structSize = layout.byteOffset(groupElement("structSize"))
            val captureId = layout.byteOffset(groupElement("captureId"))
            val texture = layout.byteOffset(groupElement("texture"))
            val ownerIdentity = layout.byteOffset(groupElement("ownerIdentity"))
            val ownerGeneration = layout.byteOffset(groupElement("ownerGeneration"))
            val contentVersion = layout.byteOffset(groupElement("contentVersion"))
            val firstSubresource = layout.byteOffset(groupElement("firstSubresource"))
            val subresourceCount = layout.byteOffset(groupElement("subresourceCount"))
            val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
        }

        private object SourceOffsets {
            private val layout = MCDRLayout.OIT_SOURCE
            val size = layout.byteSize()
            val producerId = layout.byteOffset(groupElement("producerId"))
            val producerGeneration = layout.byteOffset(groupElement("producerGeneration"))
            val shaderGeneration = layout.byteOffset(groupElement("shaderGeneration"))
            val producerKind = layout.byteOffset(groupElement("producerKind"))
            val validity = layout.byteOffset(groupElement("validity"))
        }

        private object PrimitiveOffsets {
            private val layout = MCDRLayout.OIT_PRIMITIVE
            val size = layout.byteSize()
            val surfaceId = layout.byteOffset(groupElement("surfaceId"))
            val geometryGeneration = layout.byteOffset(groupElement("geometryGeneration"))
            val materialId = layout.byteOffset(groupElement("materialId"))
            val materialGeneration = layout.byteOffset(groupElement("materialGeneration"))
            val textureGeneration = layout.byteOffset(groupElement("textureGeneration"))
            val sourceIndex = layout.byteOffset(groupElement("sourceIndex"))
            val previousIndex = layout.byteOffset(groupElement("previousIndex"))
            val flags = layout.byteOffset(groupElement("flags"))
        }

        private object PreviousOffsets {
            private val layout = MCDRLayout.OIT_PREVIOUS_TRIANGLE
            val size = layout.byteSize()
            val clip = layout.byteOffset(groupElement("clip"))
            val materialUv = layout.byteOffset(groupElement("materialUv"))
            val flags = layout.byteOffset(groupElement("flags"))
        }

        fun begin(encoder: D3D12CommandEncoderBackend, inputs: OpaqueReconstructionInputs,
            limits: OitRecordLimits, programGeneration: Long): OitRecordFrames {
            RenderSystem.assertOnRenderThread()
            check(inputs.frame === VanillaFrameCoordinator.currentFrame() && inputs.motion.frame === inputs.frame)
            require(programGeneration > 0)
            return encoder.recordCommands { context ->
                Arena.ofConfined().use { arena ->
                    val layout = MCDRLayout.OIT_BEGIN
                    val beginLayout = MCDRLayout.OIT_BEGIN_V2
                    val begin = arena.allocate(beginLayout)
                    val input = begin.asSlice(0, layout.byteSize())
                    writeInt(input, layout, "schemaVersion", 2)
                    writeInt(input, layout, "structSize", Math.toIntExact(beginLayout.byteSize()))
                    val frame = inputs.frame
                    writeIdentity(input.asSlice(layout.byteOffset(groupElement("identity")), MCDRLayout.FRAME_IDENTITY.byteSize()), frame.identity)
                    writeLong(input, layout, "historyGeneration", frame.historyGeneration)
                    val previous = VanillaFrameCoordinator.latestPresentedWorldFrame()?.takeIf { frame.previous === it.current }
                    writeLong(input, layout, "previousRealFrameId", previous?.identity?.realFrameId ?: 0)
                    writeLong(input, layout, "programGeneration", programGeneration)
                    writeLong(input, layout, "totalBudgetBytes", limits.totalBudgetBytes)
                    for ((name, value) in listOf("maxRecords" to limits.maxRecords,
                        "maxRecordsPerPixel" to limits.maxRecordsPerPixel, "maxSourceRows" to limits.maxSourceRows,
                        "maxPrimitiveRows" to limits.maxPrimitiveRows, "maxPreviousRows" to limits.maxPreviousRows,
                        "width" to frame.identity.width, "height" to frame.identity.height, "colorEncoding" to 1)) writeInt(input, layout, name, value)
                    input.set(MCDRLayout.F32, layout.byteOffset(groupElement("preExposure")), 1f)
                    writeBinding(input, layout, "opaqueColor", PreparedBinding(inputs.color.handle()))
                    writeBinding(input, layout, "opaqueDepth", PreparedBinding(inputs.depth.handle()))
                    writeBinding(input, layout, "opaqueMotion", PreparedBinding(inputs.motion.motion.handle()))
                    writeInt(begin, beginLayout, "maxTerrainSectionRows", 4096)
                    writeInt(begin, beginLayout, "maxTerrainPrimitives", Int.MAX_VALUE - 1)
                    val camera = previous?.current
                    val previousViewProjection = if (camera == null) Matrix4f() else
                        Matrix4f().set(camera.rasterProjection()).mul(Matrix4f().set(camera.viewRotation()))
                    MemorySegment.copy(previousViewProjection.get(FloatArray(16)), 0, begin, MCDRLayout.F32,
                        beginLayout.byteOffset(groupElement("previousViewProjection")), 16)
                    val previousOrigin = camera?.let { doubleArrayOf(it.originX, it.originY, it.originZ) }
                        ?: DoubleArray(3)
                    MemorySegment.copy(previousOrigin, 0, begin, MCDRLayout.F64,
                        beginLayout.byteOffset(groupElement("previousOrigin")), 3)
                    writeInt(begin, beginLayout, "previousCameraValid", if (camera != null) 1 else 0)
                    val output = arena.allocate(MCDRLayout.PTR)
                    val recordingSerial = Math.incrementExact(MCDR_ABI.D3D12GetFenceValue(context))
                    val result = MCDR_ABI.D3D12OitBeginFrameV2(context, begin, output)
                    val token = output.get(MCDRLayout.PTR, 0).address()
                    val recording = OitRecordFrames(encoder, inputs, programGeneration, recordingSerial, token)
                    recording.requireSuccess(result, "begin frame")
                    check(token != 0L)
                    recording
                }
            }
        }

        private fun writeHeader(target: MemorySegment, layout: MemoryLayout) {
            writeInt(target, layout, "schemaVersion", 1)
            writeInt(target, layout, "structSize", Math.toIntExact(layout.byteSize()))
        }

        private fun writeInt(target: MemorySegment, layout: MemoryLayout, name: String, value: Int) {
            target.set(MCDRLayout.I32, layout.byteOffset(groupElement(name)), value)
        }

        private fun writeLong(target: MemorySegment, layout: MemoryLayout, name: String, value: Long) {
            target.set(MCDRLayout.I64, layout.byteOffset(groupElement(name)), value)
        }

        private fun writeIdentity(target: MemorySegment, identity: VanillaFrameIdentity) {
            val layout = MCDRLayout.FRAME_IDENTITY
            writeLong(target, layout, "worldEpoch", identity.worldEpoch)
            writeLong(target, layout, "realFrameId", identity.realFrameId)
            writeLong(target, layout, "configVersion", identity.configVersion)
            writeLong(target, layout, "resourceVersion", identity.resourceVersion)
        }

        private fun writeBinding(target: MemorySegment, layout: MemoryLayout, name: String, binding: PreparedBinding) {
            val value = target.asSlice(layout.byteOffset(groupElement(name)), MCDRLayout.PREPARED_BINDING.byteSize())
            value.set(MCDRLayout.PTR, MCDRLayout.PREPARED_BINDING.byteOffset(groupElement("object")), MemorySegment.ofAddress(binding.handle))
            writeInt(value, MCDRLayout.PREPARED_BINDING, "firstSubresource", binding.firstSubresource)
            writeInt(value, MCDRLayout.PREPARED_BINDING, "subresourceCount", binding.subresourceCount)
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun Arena.allocateIntArray(values: List<Int>): MemorySegment {
            if (values.isEmpty()) return MemorySegment.NULL
            val target = allocateFfmArray(values.size, MCDRLayout.I32)
            values.forEachIndexed { index, value ->
                target.set(MCDRLayout.I32, index * MCDRLayout.I32.byteSize(), value)
            }
            return target
        }
    }
}
