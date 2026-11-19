package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.util.Collections

internal enum class OitNativeExecutionUnavailable {
    ENTRY_UNAVAILABLE, MANIFEST_UNAVAILABLE, OVER_BUDGET, UNSUPPORTED,
    INCOMPLETE_MANIFEST, OPEN_SCOPE, MISSING_DRAW_RECEIPT, UNSUPPORTED_CONTENT,
    MISSING_NATIVE_ADAPTER, RESOURCE_CLOSURE, MISSING_EXECUTION_ADAPTERS, COUNT_OVERFLOW,
}

internal data class OitNativeDrawReceipt(
    val receiptId: Long,
    val nativeSequence: Long,
    val frameSerial: Long,
    val producerScopeSerial: Long,
    val producerId: Long,
    val producerGeneration: Long,
    val shaderGeneration: Long,
    val programGeneration: Long,
    val sectionBase: Long,
    val sectionCount: Long,
    val primitiveBase: Long,
    val primitiveCount: Long,
    val contentState: OitNativeContentState,
    val producerKind: OitProducerKind,
)

internal enum class OitNativeContentState { ORIGINAL, UNSUPPORTED }

internal sealed interface OitNativeReceiptFrontier {
    data class Available(val sequence: Long) : OitNativeReceiptFrontier
    data object EntryUnavailable : OitNativeReceiptFrontier
}

internal sealed interface OitNativeReceiptQuery {
    class Available(receipts: List<OitNativeDrawReceipt>) : OitNativeReceiptQuery {
        val receipts: List<OitNativeDrawReceipt> = Collections.unmodifiableList(ArrayList(receipts))
    }
    data object EntryUnavailable : OitNativeReceiptQuery
    data object NotQueried : OitNativeReceiptQuery
    data object Failed : OitNativeReceiptQuery
}

internal data class OitNativeExecutionInfo(
    val streamId: Long,
    val nativeDrawCount: Long,
    val appendedDrawCount: Long,
    val eventCount: Long,
    val openScopeCount: Long,
    val frontendEventCount: Int,
    val frontendScopeCount: Int,
    val resourceCount: Long = 0,
)

internal sealed interface OitNativeExecutionResult {
    data class Unavailable(
        val reason: OitNativeExecutionUnavailable,
        val info: OitNativeExecutionInfo? = null,
        val firstEventId: Long = 0,
        val drawReceiptId: Long = 0,
        val expected: Long = 0,
        val observed: Long = 0,
        val missingMask: Int = 0,
        val firstIssueSequence: Long = 0,
    ) : OitNativeExecutionResult
    data class Sealed(val handle: Long, val info: OitNativeExecutionInfo) : OitNativeExecutionResult
}

internal class OitNativeExecutionStream(
    private val identity: VanillaFrameIdentity,
    private val historyGeneration: Long,
    private val frameSerial: Long,
    private val programGeneration: Long,
    private val frameHandle: Long,
) {
    private val executionV2 = OitNativeExecutionStreamV2(identity, historyGeneration, frameSerial, programGeneration, frameHandle)
    private var retired = false
    var result: OitNativeExecutionResult? = null
        private set
    var frontendRecording: OitExecutionRecording? = null
        private set

    fun queryDrawFrontier(context: Long): OitNativeReceiptFrontier {
        check(!retired)
        if (!MCDR_ABI.hasOitExecutionStreamV1()) return OitNativeReceiptFrontier.EntryUnavailable
        return Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.I64)
            requireSuccess(MCDR_ABI.D3D12OitQueryTerrainDrawFrontierV1(context, frameHandle, output),
                "query terrain draw frontier")
            val sequence = output.get(MCDRLayout.I64, 0)
            check(sequence >= 0L)
            OitNativeReceiptFrontier.Available(sequence)
        }
    }

    fun queryDrawReceipts(context: Long, afterSequence: Long): OitNativeReceiptQuery {
        check(!retired)
        require(afterSequence >= 0)
        if (!MCDR_ABI.hasOitExecutionStreamV1()) return OitNativeReceiptQuery.EntryUnavailable
        return Arena.ofConfined().use { arena ->
            val inputLayout = MCDRLayout.OIT_DRAW_RECEIPT_QUERY
            val outputLayout = MCDRLayout.OIT_DRAW_RECEIPT
            val input = arena.allocate(inputLayout)
            val output = arena.allocate(outputLayout)
            input.set(MCDRLayout.I32, ReceiptOffsets.querySchemaVersion, 1)
            input.set(MCDRLayout.I32, ReceiptOffsets.queryStructSize, Math.toIntExact(inputLayout.byteSize()))
            var sequence = afterSequence
            val receipts = ArrayList<OitNativeDrawReceipt>()
            while (true) {
                input.set(MCDRLayout.I64, ReceiptOffsets.afterSequence, sequence)
                val status = MCDR_ABI.D3D12OitQueryTerrainDrawReceiptV1(context, frameHandle, input, output)
                if (status == -4) break
                requireSuccess(status, "query terrain draw receipt")
                check(output.get(MCDRLayout.I32, ReceiptOffsets.schemaVersion) == 1)
                check(output.get(MCDRLayout.I32, ReceiptOffsets.structSize).toLong() == outputLayout.byteSize())
                requireIdentity(output.asSlice(ReceiptOffsets.identity, MCDRLayout.FRAME_IDENTITY.byteSize()))
                repeat(2) { index -> check(output.get(MCDRLayout.I64, ReceiptOffsets.reserved + index * 8L) == 0L) }
                val nextSequence = output.get(MCDRLayout.I64, ReceiptOffsets.nativeSequence)
                val receiptId = output.get(MCDRLayout.I64, ReceiptOffsets.receiptId)
                check(nextSequence > sequence && receiptId > 0)
                check(output.get(MCDRLayout.I64, ReceiptOffsets.frameSerial) == frameSerial)
                check(output.get(MCDRLayout.I64, ReceiptOffsets.programGeneration) == programGeneration)
                val scopeSerial = output.get(MCDRLayout.I64, ReceiptOffsets.producerScopeSerial)
                val producerId = output.get(MCDRLayout.I64, ReceiptOffsets.producerId)
                val producerGeneration = output.get(MCDRLayout.I64, ReceiptOffsets.producerGeneration)
                val shaderGeneration = output.get(MCDRLayout.I64, ReceiptOffsets.shaderGeneration)
                check(scopeSerial > 0 && producerId > 0 && producerGeneration > 0 && shaderGeneration > 0)
                val producerKind = when (output.get(MCDRLayout.I32, ReceiptOffsets.producerKind)) {
                    0 -> OitProducerKind.TERRAIN
                    1 -> OitProducerKind.FEATURE
                    2 -> OitProducerKind.BORDER
                    3 -> OitProducerKind.WEATHER
                    4 -> OitProducerKind.CLOUD
                    5 -> OitProducerKind.CUSTOM_WEATHER
                    6 -> OitProducerKind.CUSTOM_CLOUD
                    7 -> OitProducerKind.CUSTOM_TRANSLUCENT
                    else -> error("Unknown OIT native producer kind")
                }
                val contentState = when (output.get(MCDRLayout.I32, ReceiptOffsets.contentState)) {
                    0 -> OitNativeContentState.ORIGINAL
                    1 -> OitNativeContentState.UNSUPPORTED
                    else -> error("Unknown OIT native content state")
                }
                receipts += OitNativeDrawReceipt(receiptId, nextSequence, frameSerial, scopeSerial,
                    producerId, producerGeneration, shaderGeneration, programGeneration,
                    Integer.toUnsignedLong(output.get(MCDRLayout.I32, ReceiptOffsets.sectionBase)),
                    Integer.toUnsignedLong(output.get(MCDRLayout.I32, ReceiptOffsets.sectionCount)),
                    Integer.toUnsignedLong(output.get(MCDRLayout.I32, ReceiptOffsets.primitiveBase)),
                    Integer.toUnsignedLong(output.get(MCDRLayout.I32, ReceiptOffsets.primitiveCount)),
                    contentState, producerKind)
                sequence = nextSequence
            }
            OitNativeReceiptQuery.Available(receipts)
        }
    }

    fun seal(context: Long, recording: OitExecutionRecording?,
        compositeInput: OitCompositeInputCapture): OitNativeExecutionResult {
        check(!retired && result == null)
        frontendRecording = recording
        return executionV2.seal(context, recording, compositeInput).also { result = it }
    }

    fun retireWithFrame() {
        check(!retired)
        retired = true
        executionV2.retireWithFrame()
        frontendRecording = null
    }

    private fun requireSuccess(status: Int, operation: String) {
        if (status == 1) return
        throw OitRecordException(when (status) {
            -1 -> OitRecordFailure.INVALID_CONTRACT
            -2 -> OitRecordFailure.UNSUPPORTED
            -3 -> OitRecordFailure.OVER_BUDGET
            -4 -> OitRecordFailure.BUSY
            -5 -> OitRecordFailure.STALE
            -6 -> OitRecordFailure.UNKNOWN_SUBMISSION
            -8 -> OitRecordFailure.RECORDING_FAILED
            else -> OitRecordFailure.NATIVE_CONTRACT
        }, operation, identity, MCDR_ABI.D3D12LastError())
    }

    private fun requireIdentity(value: MemorySegment) {
        check(value.get(MCDRLayout.I64, IdentityOffsets.worldEpoch) == identity.worldEpoch)
        check(value.get(MCDRLayout.I64, IdentityOffsets.realFrameId) == identity.realFrameId)
        check(value.get(MCDRLayout.I64, IdentityOffsets.configVersion) == identity.configVersion)
        check(value.get(MCDRLayout.I64, IdentityOffsets.resourceVersion) == identity.resourceVersion)
    }

    private object ReceiptOffsets {
        private val layout = MCDRLayout.OIT_DRAW_RECEIPT
        val querySchemaVersion = MCDRLayout.OIT_DRAW_RECEIPT_QUERY.byteOffset(groupElement("schemaVersion"))
        val queryStructSize = MCDRLayout.OIT_DRAW_RECEIPT_QUERY.byteOffset(groupElement("structSize"))
        val afterSequence = MCDRLayout.OIT_DRAW_RECEIPT_QUERY.byteOffset(groupElement("afterSequence"))
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val identity = layout.byteOffset(groupElement("identity"))
        val reserved = layout.byteOffset(groupElement("reserved"))
        val nativeSequence = layout.byteOffset(groupElement("nativeSequence"))
        val receiptId = layout.byteOffset(groupElement("receiptId"))
        val frameSerial = layout.byteOffset(groupElement("frameSerial"))
        val programGeneration = layout.byteOffset(groupElement("programGeneration"))
        val producerScopeSerial = layout.byteOffset(groupElement("producerScopeSerial"))
        val producerId = layout.byteOffset(groupElement("producerId"))
        val producerGeneration = layout.byteOffset(groupElement("producerGeneration"))
        val shaderGeneration = layout.byteOffset(groupElement("shaderGeneration"))
        val producerKind = layout.byteOffset(groupElement("producerKind"))
        val contentState = layout.byteOffset(groupElement("contentState"))
        val sectionBase = layout.byteOffset(groupElement("sectionBase"))
        val sectionCount = layout.byteOffset(groupElement("sectionCount"))
        val primitiveBase = layout.byteOffset(groupElement("primitiveBase"))
        val primitiveCount = layout.byteOffset(groupElement("primitiveCount"))
    }

    private object IdentityOffsets {
        private val layout = MCDRLayout.FRAME_IDENTITY
        val worldEpoch = layout.byteOffset(groupElement("worldEpoch"))
        val realFrameId = layout.byteOffset(groupElement("realFrameId"))
        val configVersion = layout.byteOffset(groupElement("configVersion"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
    }
}
