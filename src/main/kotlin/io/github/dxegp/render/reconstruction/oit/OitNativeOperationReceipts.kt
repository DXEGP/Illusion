package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.FfmScratch
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.util.Collections

internal data class OitNativeOperationReceipt(
    val kind: Int,
    val commandFamily: Int,
    val receiptId: Long,
    val nativeSequence: Long,
    val frameSerial: Long,
    val identity: VanillaFrameIdentity,
    val pipelineIdentity: Long,
    val shaderGeneration: Long,
    val programGeneration: Long,
    val resourceVersion: Long,
    val packetId: Long,
    val readSetId: Long,
    val recordState: Int,
    val contentState: Int,
    val adapterState: Int,
    val producerId: Long,
    val producerScopeSerial: Long,
    val sourcePassId: Long,
    val sourceDrawId: Long,
    val parentSourceDrawId: Long?,
    val producerKind: Int,
    val stage: Int,
    val commandOrdinal: Int,
)

internal sealed interface OitNativeOperationReceiptQuery {
    class Available(receipts: List<OitNativeOperationReceipt>) : OitNativeOperationReceiptQuery {
        val receipts: List<OitNativeOperationReceipt> = Collections.unmodifiableList(ArrayList(receipts))
    }
    data object EntryUnavailable : OitNativeOperationReceiptQuery
    data object NotQueried : OitNativeOperationReceiptQuery
    data object Failed : OitNativeOperationReceiptQuery
}

internal object OitNativeOperationReceipts {
    private val scratch = ThreadLocal.withInitial { FfmScratch() }
    private object Begin {
        private val layout = MCDRLayout.OIT_EXECUTION_OPERATION_BEGIN_V1
        val schema = layout.byteOffset(groupElement("schemaVersion"))
        val size = layout.byteOffset(groupElement("structSize"))
        val identity = layout.byteOffset(groupElement("identity"))
        val frameSerial = layout.byteOffset(groupElement("frameSerial"))
        val key = layout.byteOffset(groupElement("key"))
        val kind = layout.byteOffset(groupElement("kind"))
        val family = layout.byteOffset(groupElement("expectedCommandFamily"))
        val programGeneration = layout.byteOffset(groupElement("programGeneration"))
        val shaderGeneration = layout.byteOffset(groupElement("shaderGeneration"))
        val pipeline = layout.byteOffset(groupElement("pipeline"))
    }

    private object Receipt {
        private val layout = MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1
        val schema = layout.byteOffset(groupElement("schemaVersion"))
        val size = layout.byteOffset(groupElement("structSize"))
        val kind = layout.byteOffset(groupElement("kind"))
        val family = layout.byteOffset(groupElement("commandFamily"))
        val receiptId = layout.byteOffset(groupElement("receiptId"))
        val sequence = layout.byteOffset(groupElement("nativeSequence"))
        val frameSerial = layout.byteOffset(groupElement("frameSerial"))
        val identity = layout.byteOffset(groupElement("identity"))
        val key = layout.byteOffset(groupElement("key"))
        val pipeline = layout.byteOffset(groupElement("pipelineIdentity"))
        val shaderGeneration = layout.byteOffset(groupElement("shaderGeneration"))
        val programGeneration = layout.byteOffset(groupElement("programGeneration"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
        val packetId = layout.byteOffset(groupElement("packetId"))
        val readSetId = layout.byteOffset(groupElement("readSetId"))
        val recordState = layout.byteOffset(groupElement("recordState"))
        val contentState = layout.byteOffset(groupElement("contentState"))
        val adapterState = layout.byteOffset(groupElement("adapterState"))
        val reserved = layout.byteOffset(groupElement("reserved"))
    }

    private object Identity {
        private val layout = MCDRLayout.FRAME_IDENTITY
        val worldEpoch = layout.byteOffset(groupElement("worldEpoch"))
        val frameId = layout.byteOffset(groupElement("realFrameId"))
        val configVersion = layout.byteOffset(groupElement("configVersion"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
    }

    private object Key {
        private val layout = MCDRLayout.OIT_EXECUTION_KEY_V2
        val producerId = layout.byteOffset(groupElement("producerId"))
        val scopeSerial = layout.byteOffset(groupElement("producerScopeSerial"))
        val passId = layout.byteOffset(groupElement("sourcePassId"))
        val drawId = layout.byteOffset(groupElement("sourceDrawId"))
        val parentDrawId = layout.byteOffset(groupElement("parentSourceDrawId"))
        val producerKind = layout.byteOffset(groupElement("producerKind"))
        val stage = layout.byteOffset(groupElement("stage"))
        val commandOrdinal = layout.byteOffset(groupElement("commandOrdinal"))
        val reserved = layout.byteOffset(groupElement("reserved"))
    }

    internal data class OperationFacts(
        val kind: Int, val commandFamily: Int, val frameSerial: Long, val identity: VanillaFrameIdentity,
        val producerId: Long, val scopeSerial: Long, val passId: Long, val sourceDrawId: Long,
        val parentSourceDrawId: Long?, val producerKind: Int, val stage: Int,
        val programGeneration: Long, val shaderGeneration: Long, val pipeline: Long,
    )

    fun <Result> record(context: Long, frame: Long, facts: OperationFacts,
        action: () -> Result): Pair<Result, OitNativeOperationReceiptQuery> {
        val operation = begin(context, frame, facts)
        if (operation == 0L) return action() to OitNativeOperationReceiptQuery.EntryUnavailable
        val receiptCount: Long
        val result = try {
            val value = action()
            receiptCount = finish(context, frame, operation, facts.identity)
            value
        } catch (failure: Throwable) {
            if (failure !is OitRecordException || failure.failure != OitRecordFailure.UNKNOWN_SUBMISSION) {
                try { requireSuccess(MCDR_ABI.D3D12OitCancelExecutionOperationV1(context, frame, operation),
                    "cancel execution operation", facts.identity) }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        val receipts = query(context, frame, operation, facts)
        check(receipts.receipts.size.toLong() == receiptCount)
        return result to receipts
    }

    private fun begin(context: Long, frame: Long, facts: OperationFacts): Long {
            val input = scratch.get().allocate(0, MCDRLayout.OIT_EXECUTION_OPERATION_BEGIN_V1.byteSize(), 8L, null)
            input.fill(0)
            input.set(MCDRLayout.I32, Begin.schema, 1)
            input.set(MCDRLayout.I32, Begin.size, MCDRLayout.OIT_EXECUTION_OPERATION_BEGIN_V1.byteSize().toInt())
            writeIdentity(input.asSlice(Begin.identity, MCDRLayout.FRAME_IDENTITY.byteSize()), facts.identity)
            input.set(MCDRLayout.I64, Begin.frameSerial, facts.frameSerial)
            val key = input.asSlice(Begin.key, MCDRLayout.OIT_EXECUTION_KEY_V2.byteSize())
            writeKey(key, facts)
            input.set(MCDRLayout.I32, Begin.kind, facts.kind)
            input.set(MCDRLayout.I32, Begin.family, facts.commandFamily)
            input.set(MCDRLayout.I64, Begin.programGeneration, facts.programGeneration)
            input.set(MCDRLayout.I64, Begin.shaderGeneration, facts.shaderGeneration)
            input.set(MCDRLayout.PTR, Begin.pipeline,
                if (facts.pipeline == 0L) MemorySegment.NULL else MemorySegment.ofAddress(facts.pipeline))
            val operationOut = scratch.get().allocate(1, 8L, 8L, null)
            operationOut.fill(0)
            val beginStatus = MCDR_ABI.D3D12OitBeginExecutionOperationV1(context, frame, input, operationOut)
            if (beginStatus == -2) {
                check(operationOut.get(MCDRLayout.PTR, 0).address() == 0L)
                return 0L
            }
            requireSuccess(beginStatus, "begin execution operation", facts.identity)
            val operation = operationOut.get(MCDRLayout.PTR, 0).address()
            check(operation != 0L)
            return operation
    }

    private fun finish(context: Long, frame: Long, operation: Long, identity: VanillaFrameIdentity): Long {
        val countOut = scratch.get().allocate(1, 8L, 8L, null)
        countOut.fill(0)
        requireSuccess(MCDR_ABI.D3D12OitFinishExecutionOperationV1(context, frame, operation, countOut),
            "finish execution operation", identity)
        return countOut.get(MCDRLayout.I64, 0)
    }

    private fun query(context: Long, frame: Long, operation: Long,
        facts: OperationFacts): OitNativeOperationReceiptQuery.Available {
        val output = scratch.get().allocate(2, MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1.byteSize(), 8L, null)
        val receipts = ArrayList<OitNativeOperationReceipt>()
        var after = 0L
        while (true) {
            val status = MCDR_ABI.D3D12OitQueryExecutionOperationReceiptV1(context, frame, operation, after, output)
            if (status == 0) break
            requireSuccess(status, "query execution operation receipt", facts.identity)
            check(output.get(MCDRLayout.I32, Receipt.schema) == 1 &&
                output.get(MCDRLayout.I32, Receipt.size).toLong() == MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1.byteSize())
            val identity = facts.identity
            val actualIdentity = output.asSlice(Receipt.identity, MCDRLayout.FRAME_IDENTITY.byteSize())
            check(actualIdentity.get(MCDRLayout.I64, Identity.worldEpoch) == identity.worldEpoch &&
                actualIdentity.get(MCDRLayout.I64, Identity.frameId) == identity.realFrameId &&
                actualIdentity.get(MCDRLayout.I64, Identity.configVersion) == identity.configVersion &&
                actualIdentity.get(MCDRLayout.I64, Identity.resourceVersion) == identity.resourceVersion &&
                output.get(MCDRLayout.I64, Receipt.frameSerial) == facts.frameSerial)
            check(output.get(MCDRLayout.I32, Receipt.reserved) == 0)
            val key = output.asSlice(Receipt.key, MCDRLayout.OIT_EXECUTION_KEY_V2.byteSize())
            check(key.get(MCDRLayout.I64, Key.producerId) == facts.producerId &&
                key.get(MCDRLayout.I64, Key.scopeSerial) == facts.scopeSerial &&
                key.get(MCDRLayout.I64, Key.passId) == facts.passId &&
                key.get(MCDRLayout.I64, Key.drawId) == facts.sourceDrawId &&
                key.get(MCDRLayout.I64, Key.parentDrawId) == (facts.parentSourceDrawId ?: 0L) &&
                key.get(MCDRLayout.I32, Key.producerKind) == facts.producerKind &&
                key.get(MCDRLayout.I32, Key.stage) == facts.stage &&
                key.get(MCDRLayout.I32, Key.commandOrdinal) == receipts.size &&
                key.get(MCDRLayout.I32, Key.reserved) == 0)
            check(output.get(MCDRLayout.I32, Receipt.kind) == facts.kind &&
                output.get(MCDRLayout.I32, Receipt.family) in 1..5 &&
                (facts.commandFamily == 0 || output.get(MCDRLayout.I32, Receipt.family) == facts.commandFamily) &&
                output.get(MCDRLayout.I64, Receipt.pipeline) != 0L &&
                (facts.pipeline == 0L || output.get(MCDRLayout.I64, Receipt.pipeline) == facts.pipeline) &&
                (facts.shaderGeneration == 0L || output.get(MCDRLayout.I64, Receipt.shaderGeneration) == facts.shaderGeneration) &&
                output.get(MCDRLayout.I64, Receipt.programGeneration) == facts.programGeneration &&
                output.get(MCDRLayout.I64, Receipt.resourceVersion) == facts.identity.resourceVersion &&
                output.get(MCDRLayout.I32, Receipt.recordState) in 1..3 &&
                output.get(MCDRLayout.I32, Receipt.contentState) in 0..3 &&
                output.get(MCDRLayout.I32, Receipt.adapterState) in 0..4)
            val sequence = output.get(MCDRLayout.I64, Receipt.sequence)
            check(sequence > after && output.get(MCDRLayout.I64, Receipt.receiptId) > 0)
            receipts += OitNativeOperationReceipt(output.get(MCDRLayout.I32, Receipt.kind), output.get(MCDRLayout.I32, Receipt.family),
                output.get(MCDRLayout.I64, Receipt.receiptId), sequence, facts.frameSerial, identity,
                output.get(MCDRLayout.I64, Receipt.pipeline), output.get(MCDRLayout.I64, Receipt.shaderGeneration),
                output.get(MCDRLayout.I64, Receipt.programGeneration), output.get(MCDRLayout.I64, Receipt.resourceVersion),
                output.get(MCDRLayout.I64, Receipt.packetId), output.get(MCDRLayout.I64, Receipt.readSetId),
                output.get(MCDRLayout.I32, Receipt.recordState), output.get(MCDRLayout.I32, Receipt.contentState),
                output.get(MCDRLayout.I32, Receipt.adapterState), key.get(MCDRLayout.I64, Key.producerId),
                key.get(MCDRLayout.I64, Key.scopeSerial), key.get(MCDRLayout.I64, Key.passId), key.get(MCDRLayout.I64, Key.drawId),
                key.get(MCDRLayout.I64, Key.parentDrawId).takeIf { it != 0L }, key.get(MCDRLayout.I32, Key.producerKind),
                key.get(MCDRLayout.I32, Key.stage), key.get(MCDRLayout.I32, Key.commandOrdinal))
            after = sequence
        }
        return OitNativeOperationReceiptQuery.Available(receipts)
    }

    private fun writeIdentity(target: MemorySegment, identity: VanillaFrameIdentity) {
        target.set(MCDRLayout.I64, Identity.worldEpoch, identity.worldEpoch)
        target.set(MCDRLayout.I64, Identity.frameId, identity.realFrameId)
        target.set(MCDRLayout.I64, Identity.configVersion, identity.configVersion)
        target.set(MCDRLayout.I64, Identity.resourceVersion, identity.resourceVersion)
    }

    fun writeReceipt(target: MemorySegment, receipt: OitNativeOperationReceipt) {
        target.fill(0)
        target.set(MCDRLayout.I32, Receipt.schema, 1)
        target.set(MCDRLayout.I32, Receipt.size, Math.toIntExact(MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1.byteSize()))
        target.set(MCDRLayout.I32, Receipt.kind, receipt.kind)
        target.set(MCDRLayout.I32, Receipt.family, receipt.commandFamily)
        target.set(MCDRLayout.I64, Receipt.receiptId, receipt.receiptId)
        target.set(MCDRLayout.I64, Receipt.sequence, receipt.nativeSequence)
        target.set(MCDRLayout.I64, Receipt.frameSerial, receipt.frameSerial)
        writeIdentity(target.asSlice(Receipt.identity, MCDRLayout.FRAME_IDENTITY.byteSize()), receipt.identity)
        val key = target.asSlice(Receipt.key, MCDRLayout.OIT_EXECUTION_KEY_V2.byteSize())
        key.set(MCDRLayout.I64, Key.producerId, receipt.producerId)
        key.set(MCDRLayout.I64, Key.scopeSerial, receipt.producerScopeSerial)
        key.set(MCDRLayout.I64, Key.passId, receipt.sourcePassId)
        key.set(MCDRLayout.I64, Key.drawId, receipt.sourceDrawId)
        key.set(MCDRLayout.I64, Key.parentDrawId, receipt.parentSourceDrawId ?: 0L)
        key.set(MCDRLayout.I32, Key.producerKind, receipt.producerKind)
        key.set(MCDRLayout.I32, Key.stage, receipt.stage)
        key.set(MCDRLayout.I32, Key.commandOrdinal, receipt.commandOrdinal)
        target.set(MCDRLayout.I64, Receipt.pipeline, receipt.pipelineIdentity)
        target.set(MCDRLayout.I64, Receipt.shaderGeneration, receipt.shaderGeneration)
        target.set(MCDRLayout.I64, Receipt.programGeneration, receipt.programGeneration)
        target.set(MCDRLayout.I64, Receipt.resourceVersion, receipt.resourceVersion)
        target.set(MCDRLayout.I64, Receipt.packetId, receipt.packetId)
        target.set(MCDRLayout.I64, Receipt.readSetId, receipt.readSetId)
        target.set(MCDRLayout.I32, Receipt.recordState, receipt.recordState)
        target.set(MCDRLayout.I32, Receipt.contentState, receipt.contentState)
        target.set(MCDRLayout.I32, Receipt.adapterState, receipt.adapterState)
    }

    private fun writeKey(target: MemorySegment, facts: OperationFacts) {
        target.set(MCDRLayout.I64, Key.producerId, facts.producerId)
        target.set(MCDRLayout.I64, Key.scopeSerial, facts.scopeSerial)
        target.set(MCDRLayout.I64, Key.passId, facts.passId)
        target.set(MCDRLayout.I64, Key.drawId, facts.sourceDrawId)
        target.set(MCDRLayout.I64, Key.parentDrawId, facts.parentSourceDrawId ?: 0L)
        target.set(MCDRLayout.I32, Key.producerKind, facts.producerKind)
        target.set(MCDRLayout.I32, Key.stage, facts.stage)
        target.set(MCDRLayout.I32, Key.commandOrdinal, 0)
        target.set(MCDRLayout.I32, Key.reserved, 0)
    }

    private fun requireSuccess(status: Int, operation: String, identity: VanillaFrameIdentity) {
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
}
