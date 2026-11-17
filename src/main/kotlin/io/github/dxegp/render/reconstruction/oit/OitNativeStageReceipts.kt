package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import net.minecraft.client.renderer.oit.OitStage

internal data class OitTerrainNativeRecording(
    val context: Long,
    val frameHandle: Long,
    val frameSerial: Long,
    val programGeneration: Long,
)

internal object OitNativeStageReceipts {
    fun recordDraw(recording: OitTerrainNativeRecording,
        invocation: OitTerrainStageInvocation, prepared: D3D12TerrainWorkGraph.Prepared,
        action: () -> Unit): OitNativeTerrainStageReceiptResult {
        val context = recording.context
        val frameHandle = recording.frameHandle
        val frameSerial = recording.frameSerial
        val pipeline = prepared.pipeline
        if (!MCDR_ABI.hasOitTerrainStageReceiptsV2()) {
            action()
            return OitNativeTerrainStageReceiptResult.EntryUnavailable
        }
        return Arena.ofConfined().use { arena ->
            val input = arena.allocate(MCDRLayout.OIT_TERRAIN_STAGE_BEGIN_V2)
            input.set(MCDRLayout.I32, Begin.schemaVersion, 2)
            input.set(MCDRLayout.I32, Begin.structSize, Math.toIntExact(MCDRLayout.OIT_TERRAIN_STAGE_BEGIN_V2.byteSize()))
            val stageCode = when (invocation.scope.stage) {
                OitStage.DEPTH_BOUNDS -> 1
                OitStage.TRANSMITTANCE -> 2
                OitStage.ACCUMULATE -> 3
            }
            input.set(MCDRLayout.I32, Begin.stage, stageCode)
            input.set(MCDRLayout.I64, Begin.frameSerial, frameSerial)
            input.set(MCDRLayout.I64, Begin.producerId, invocation.scope.producerId)
            input.set(MCDRLayout.I64, Begin.scopeSerial, invocation.scope.serial)
            input.set(MCDRLayout.I64, Begin.passId, invocation.passId)
            input.set(MCDRLayout.I64, Begin.drawId, invocation.sourceDrawId)
            input.set(MCDRLayout.I64, Begin.parentDrawId, invocation.parentSourceDrawId ?: 0L)
            input.set(MCDRLayout.PTR, Begin.pipeline, MemorySegment.ofAddress(pipeline))
            val identity = invocation.frame.identity
            input.set(MCDRLayout.I64, Begin.identity + Identity.worldEpoch, identity.worldEpoch)
            input.set(MCDRLayout.I64, Begin.identity + Identity.frameId, identity.realFrameId)
            input.set(MCDRLayout.I64, Begin.identity + Identity.configVersion, identity.configVersion)
            input.set(MCDRLayout.I64, Begin.identity + Identity.resourceVersion, identity.resourceVersion)
            val output = arena.allocate(MCDRLayout.PTR)
            val begin = MCDR_ABI.D3D12OitBeginTerrainStageDrawV2(context, frameHandle, input, output)
            if (begin == -2) {
                check(output.get(MCDRLayout.PTR, 0).address() == 0L)
                action()
                return@use OitNativeTerrainStageReceiptResult.Unsupported
            }
            requireSuccess(begin, "begin terrain stage", invocation)
            val token = output.get(MCDRLayout.PTR, 0).address()
            check(token != 0L)
            val receiptLayout = MCDRLayout.OIT_TERRAIN_STAGE_RECEIPT_V2
            val receipt = arena.allocate(receiptLayout)
            val readSetRegistration: OitTerrainReadSetRegistration
            try {
                readSetRegistration = OitTerrainReplayReadSet.registerReads(arena, recording, token, invocation, prepared)
                action()
                requireSuccess(MCDR_ABI.D3D12OitFinishTerrainStageDrawV2(context, frameHandle, token, receipt),
                    "finish terrain stage", invocation)
            } catch (failure: Throwable) {
                if (failure !is OitRecordException || failure.failure != OitRecordFailure.UNKNOWN_SUBMISSION) {
                    try {
                        requireSuccess(MCDR_ABI.D3D12OitAbortTerrainStageDrawV2(context, frameHandle, token),
                            "abort terrain stage", invocation)
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
            check(receipt.get(MCDRLayout.I32, Receipt.schemaVersion) == 2 &&
                receipt.get(MCDRLayout.I32, Receipt.structSize).toLong() == receiptLayout.byteSize())
            check(receipt.get(MCDRLayout.I32, Receipt.flags) == 0 && receipt.get(MCDRLayout.I32, Receipt.producerKind) == 0 &&
                receipt.get(MCDRLayout.I32, Receipt.reserved0) == 0 && receipt.get(MCDRLayout.I32, Receipt.reserved1) == 0)
            check(receipt.get(MCDRLayout.I64, Receipt.identity + Identity.worldEpoch) == identity.worldEpoch &&
                receipt.get(MCDRLayout.I64, Receipt.identity + Identity.frameId) == identity.realFrameId &&
                receipt.get(MCDRLayout.I64, Receipt.identity + Identity.configVersion) == identity.configVersion &&
                receipt.get(MCDRLayout.I64, Receipt.identity + Identity.resourceVersion) == identity.resourceVersion)
            check(receipt.get(MCDRLayout.I32, Receipt.stage) == stageCode && receipt.get(MCDRLayout.I64, Receipt.pipeline) == pipeline)
            val content = when (receipt.get(MCDRLayout.I32, Receipt.contentState)) {
                0 -> OitNativeContentState.ORIGINAL
                1 -> OitNativeContentState.UNSUPPORTED
                else -> error("Unknown terrain stage receipt content state")
            }
            val receiptId = receipt.get(MCDRLayout.I64, Receipt.receiptId)
            val sequence = receipt.get(MCDRLayout.I64, Receipt.sequence)
            check(receiptId > 0L && sequence > 0L && receipt.get(MCDRLayout.I64, Receipt.frameSerial) == frameSerial)
            OitNativeTerrainStageReceiptResult.Available(OitNativeTerrainStageReceipt(receiptId,
                sequence, frameSerial, receipt.get(MCDRLayout.I64, Receipt.producerId),
                receipt.get(MCDRLayout.I64, Receipt.scopeSerial), receipt.get(MCDRLayout.I64, Receipt.drawId),
                receipt.get(MCDRLayout.I64, Receipt.parentDrawId).takeIf { it != 0L },
                receipt.get(MCDRLayout.I64, Receipt.passId), invocation.scope.stage, content, pipeline, readSetRegistration))
        }
    }

    private object Identity {
        private val layout = MCDRLayout.FRAME_IDENTITY
        val worldEpoch = layout.byteOffset(groupElement("worldEpoch"))
        val frameId = layout.byteOffset(groupElement("realFrameId"))
        val configVersion = layout.byteOffset(groupElement("configVersion"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
    }

    private object Begin {
        private val layout = MCDRLayout.OIT_TERRAIN_STAGE_BEGIN_V2
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val stage = layout.byteOffset(groupElement("stage"))
        val frameSerial = layout.byteOffset(groupElement("frameSerial"))
        val producerId = layout.byteOffset(groupElement("producerId"))
        val scopeSerial = layout.byteOffset(groupElement("producerScopeSerial"))
        val passId = layout.byteOffset(groupElement("sourcePassId"))
        val drawId = layout.byteOffset(groupElement("sourceDrawId"))
        val parentDrawId = layout.byteOffset(groupElement("parentSourceDrawId"))
        val identity = layout.byteOffset(groupElement("identity"))
        val pipeline = layout.byteOffset(groupElement("pipeline"))
    }

    private object Receipt {
        private val layout = MCDRLayout.OIT_TERRAIN_STAGE_RECEIPT_V2
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val stage = layout.byteOffset(groupElement("stage"))
        val flags = layout.byteOffset(groupElement("flags"))
        val producerKind = layout.byteOffset(groupElement("producerKind"))
        val contentState = layout.byteOffset(groupElement("contentState"))
        val reserved0 = layout.byteOffset(groupElement("reserved0"))
        val reserved1 = layout.byteOffset(groupElement("reserved1"))
        val receiptId = layout.byteOffset(groupElement("receiptId"))
        val sequence = layout.byteOffset(groupElement("nativeSequence"))
        val frameSerial = layout.byteOffset(groupElement("frameSerial"))
        val producerId = layout.byteOffset(groupElement("producerId"))
        val scopeSerial = layout.byteOffset(groupElement("producerScopeSerial"))
        val passId = layout.byteOffset(groupElement("sourcePassId"))
        val drawId = layout.byteOffset(groupElement("sourceDrawId"))
        val parentDrawId = layout.byteOffset(groupElement("parentSourceDrawId"))
        val identity = layout.byteOffset(groupElement("identity"))
        val pipeline = layout.byteOffset(groupElement("pipelineIdentity"))
    }

    private fun requireSuccess(status: Int, operation: String, invocation: OitTerrainStageInvocation) {
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
        }, operation, invocation.frame.identity, MCDR_ABI.D3D12LastError())
    }
}
