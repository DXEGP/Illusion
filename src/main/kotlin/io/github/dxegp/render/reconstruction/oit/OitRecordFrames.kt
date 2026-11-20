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
import org.joml.Matrix4f
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment

internal enum class OitRecordFailure {
    INVALID_CONTRACT, UNSUPPORTED, OVER_BUDGET, BUSY, STALE, UNKNOWN_SUBMISSION, RECORDING_FAILED, NATIVE_CONTRACT,
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
    internal enum class Phase { RECORDING, SEALED, RESOLVED, ABORTED }

    internal sealed interface MetadataPublication

    internal data object MetadataCapacityExceeded : MetadataPublication

    internal class PublishedMetadata internal constructor(
        internal val owner: OitRecordFrames,
        internal val handle: Long,
    ) : MetadataPublication

    private var phase = Phase.RECORDING
    private var drawBound = false
    val frame get() = inputs.frame
    fun isRecording(): Boolean = phase == Phase.RECORDING

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

    fun seal() {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.RECORDING && !drawBound)
        encoder.recordCommands { context ->
            requireSuccess(MCDR_ABI.D3D12OitSealFrameV1(context, handle), "seal frame")
        }
        phase = Phase.SEALED
    }

    fun resolveRecords(currentTransparency: PreparedBinding, unrecordedCoverage: OitUnrecordedCoverage,
        targets: OitContributionResolveTargets): Boolean {
        RenderSystem.assertOnRenderThread()
        check(phase == Phase.SEALED)
        val resolved = encoder.recordCommands { context ->
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
                val status = MCDR_ABI.D3D12OitResolveRecordsV3(context, base)
                if (status == -3) return@use false
                requireSuccess(status, "resolve records")
                true
            }
        }
        if (resolved) phase = Phase.RESOLVED
        return resolved
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (phase == Phase.ABORTED) return
        encoder.contextControl { context -> requireSuccess(MCDR_ABI.D3D12OitAbortFrameV1(context, handle), "retire frame") }
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
            limits: OitRecordLimits, programGeneration: Long): OitRecordFrames? {
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
                    if (result == -3) return@use null
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

