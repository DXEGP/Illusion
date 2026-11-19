package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometry
import net.minecraft.client.renderer.oit.OitStage
import java.util.Collections

internal object OitTerrainTileProducer {
    internal enum class UnavailableReason {
        FROZEN_CAMERA_NOT_ATTACHED,
        PREPARED_FACTS_MISMATCH,
        MESHLET_BOUNDS_OUTSIDE_SECTION,
        TILE_REGIONS_NOT_CAPTURED,
    }

    internal enum class SealedEventResourceState { COMPLETE, INCOMPLETE }

    internal data class SealedEventSummary(
        val sequence: Long,
        val nativeSequenceMin: Long,
        val nativeSequenceMax: Long,
        val sourceDrawId: Long,
        val parentSourceDrawId: Long?,
        val kind: OitExecutionEventKind,
        val stage: OitStage?,
        val resources: SealedEventResourceState,
        val nativeDrawReceiptAvailable: Boolean,
        val terrainStageReceiptComplete: Boolean,
        val operationReceiptAvailable: Boolean,
    )

    internal data class SealedEventContract(
        val events: List<SealedEventSummary>,
        val normalizationCaptured: Boolean,
    ) {
        val depthBoundsCaptured: Boolean
            get() = hasComplete(OitExecutionEventKind.DEPTH_BOUNDS)
        val depthBoundsCullCaptured: Boolean
            get() = hasComplete(OitExecutionEventKind.DEPTH_BOUNDS_CULL)
        val depthBoundsCopyCaptured: Boolean
            get() = hasComplete(OitExecutionEventKind.DEPTH_BOUNDS_COPY)
        val waterMaskCaptured: Boolean
            get() = hasComplete(OitExecutionEventKind.WATER_MASK)
        val terrainDepthCaptured: Boolean
            get() {
                val copies = events.filter { it.kind in TERRAIN_DEPTH_COPY_KINDS }
                return when {
                    waterMaskCaptured -> copies.size == 2 && copies.all(::isComplete)
                    copies.isEmpty() -> true
                    else -> false
                }
            }
        val transmittanceCaptured: Boolean
            get() = hasComplete(OitExecutionEventKind.TRANSMITTANCE)
        val accumulateCaptured: Boolean
            get() = hasComplete(OitExecutionEventKind.ACCUMULATE)
        val complete: Boolean
            get() = normalizationCaptured && depthBoundsCaptured && depthBoundsCopyCaptured &&
                depthBoundsCullCaptured && terrainDepthCaptured && transmittanceCaptured && accumulateCaptured &&
                events.isNotEmpty() && events.all { isComplete(it) }

        private fun hasComplete(kind: OitExecutionEventKind): Boolean = events.any { it.kind == kind && isComplete(it) }

        private fun isComplete(event: SealedEventSummary): Boolean =
            event.resources == SealedEventResourceState.COMPLETE &&
                event.nativeSequenceMin > 0L && event.nativeSequenceMax >= event.nativeSequenceMin &&
                (event.terrainStageReceiptComplete || event.operationReceiptAvailable)

        companion object {
            val EMPTY = SealedEventContract(emptyList(), false)
        }
    }

    internal data class SectionFact(
        val section: D3D12MeshDrawCapture.TerrainDrawFact,
        val atlasDescriptorBase: Int,
        val geometry: D3D12SectionGeometry.LayerUpload,
    )

    internal data class Facts(
        val schemaVersion: Int,
        val frame: VanillaFrameIdentity,
        val primitiveCount: Int,
        val meshletCount: Int,
        val producerId: Long,
        val producerScopeSerial: Long,
        val sourceDrawId: Long,
        val parentSourceDrawId: Long?,
        val sourcePassId: Long,
        val commandSelections: List<D3D12MeshDrawCapture.TerrainCommandSelection>,
        val material: OitTerrainStageMaterial,
        val pipeline: Long,
        val vertexBuffer: Long,
        val vertexOffset: Long,
        val sections: List<SectionFact>,
        val accumulateReceiptId: Long?,
        val terrainGroupId: Long? = null,
        val sealedEvents: SealedEventContract = SealedEventContract.EMPTY,
    ) {
        val sectionCount: Int get() = sections.size

        init {
            require(schemaVersion == 2 && frame.width > 0 && frame.height > 0)
            require(primitiveCount > 0 && meshletCount > 0 && pipeline != 0L && vertexBuffer != 0L &&
                vertexOffset >= 0L && producerId > 0L && producerScopeSerial > 0L && sourceDrawId > 0L &&
                sourcePassId > 0L && sections.isNotEmpty() && commandSelections.size == sections.size)
        }
    }

    internal sealed interface Result {
        data class Captured(val facts: Facts) : Result
        data class Unavailable(val reason: UnavailableReason) : Result
    }

    internal fun capture(
        frame: VanillaFrameInputs?,
        batch: D3D12MeshDrawCapture.Batch,
        facts: D3D12MeshDrawCapture.TerrainPreparedFacts,
        invocation: OitTerrainStageInvocation,
        material: OitTerrainStageMaterial,
        receipt: OitNativeTerrainStageReceiptResult,
    ): Result {
        val inputs = frame ?: return Result.Unavailable(UnavailableReason.FROZEN_CAMERA_NOT_ATTACHED)
        if (facts.batch.drawFacts.size != batch.drawCount || facts.batch.meshletCount <= 0 ||
            facts.meshletDescriptorOffsets.size != batch.drawCount || facts.commandSelections.size != batch.drawCount)
            return Result.Unavailable(UnavailableReason.PREPARED_FACTS_MISMATCH)
        val sections = ArrayList<SectionFact>(batch.drawCount)
        for ((sectionIndex, draw) in batch.draws.withIndex()) {
            if (!draw.geometry.bounded)
                return Result.Unavailable(UnavailableReason.MESHLET_BOUNDS_OUTSIDE_SECTION)
            sections += SectionFact(facts.batch.drawFacts[sectionIndex],
                facts.meshletDescriptorOffsets[sectionIndex], draw.geometry)
        }
        return Result.Captured(Facts(
            schemaVersion = 2,
            frame = inputs.identity,
            primitiveCount = facts.batch.primitiveCount,
            meshletCount = facts.batch.meshletCount,
            producerId = invocation.scope.producerId,
            producerScopeSerial = invocation.scope.serial,
            sourceDrawId = invocation.sourceDrawId,
            parentSourceDrawId = invocation.parentSourceDrawId,
            sourcePassId = invocation.passId,
            commandSelections = Collections.unmodifiableList(facts.commandSelections.toList()),
            material = material,
            pipeline = facts.pipeline,
            vertexBuffer = facts.vertexBuffer,
            vertexOffset = facts.vertexOffset,
            sections = Collections.unmodifiableList(sections),
            accumulateReceiptId = (receipt as? OitNativeTerrainStageReceiptResult.Available)?.receipt?.receiptId,
        ))
    }

    internal fun attachSealedEvents(
        facts: Facts,
        recording: OitExecutionRecording?,
        normalization: OitCompositeNormalizationInput?,
    ): Facts {
        if (recording == null || recording.frame.identity != facts.frame) return facts
        val candidates = recording.events.filter { event ->
            event.resourceVersion == facts.frame.resourceVersion &&
                event.kind in REQUIRED_KINDS
        }.sortedBy(OitExecutionEvent::sequence)
        val accumulatorGroups = candidates.asSequence()
            .filter { it.kind == OitExecutionEventKind.ACCUMULATE }
            .flatMap { event ->
                event.terrainPreparedFacts.asSequence()
                    .filter { stage ->
                        val receipt = stage.nativeReceipt as? OitNativeTerrainStageReceiptResult.Available
                        receipt != null && receipt.receipt.receiptId == facts.accumulateReceiptId
                    }
                    .mapNotNull { (it.drawGroup as? OitDrawGroupEvidence.TerrainSelection)?.groupId }
            }
            .distinct()
            .toList()
        val anchoredFacts = accumulatorGroups.singleOrNull()?.let { facts.copy(terrainGroupId = it) }
        val terrainStages = anchoredFacts?.let { anchor ->
            candidates.filter { event -> matchesTerrainStage(anchor, event) }
        }.orEmpty()
        val chain = anchoredFacts?.let { selectResourceChain(candidates, terrainStages) }.orEmpty()
        val summaries = chain.map { event ->
            summarize(event, terrainStages.contains(event), facts, recording.recordingSerial)
        }
        val normalizationCaptured = normalization?.takeIf { input ->
            input.isCurrent() && input.frame.identity == facts.frame && input.recordingSerial == recording.recordingSerial &&
                inputResourcesCurrent(input, facts.frame.resourceVersion)
        }?.let { normalizationMatches(it, chain) } == true
        return (anchoredFacts ?: facts).copy(sealedEvents = SealedEventContract(
            Collections.unmodifiableList(summaries), normalizationCaptured,
        ))
    }

    private val REQUIRED_KINDS = setOf(
        OitExecutionEventKind.WATER_MASK,
        OitExecutionEventKind.TERRAIN_DEPTH_COPY, OitExecutionEventKind.WATER_DEPTH_COPY,
        OitExecutionEventKind.CLOUD_DEPTH_COPY,
        OitExecutionEventKind.DEPTH_BOUNDS_COPY,
        OitExecutionEventKind.DEPTH_BOUNDS, OitExecutionEventKind.DEPTH_BOUNDS_CULL,
        OitExecutionEventKind.TRANSMITTANCE, OitExecutionEventKind.ACCUMULATE,
    )
    private val TERRAIN_DEPTH_COPY_KINDS = setOf(
        OitExecutionEventKind.TERRAIN_DEPTH_COPY,
        OitExecutionEventKind.WATER_DEPTH_COPY,
    )

    private fun summarize(event: OitExecutionEvent, terrainStage: Boolean, facts: Facts,
        recordingSerial: Long): SealedEventSummary {
        val terrainReceiptComplete = terrainStage && event.terrainPreparedFacts.all { stage ->
            val receipt = stage.nativeReceipt as? OitNativeTerrainStageReceiptResult.Available ?: return@all false
            val actual = receipt.receipt
            actual.contentState == OitNativeContentState.ORIGINAL &&
                actual.readSetRegistration == OitTerrainReadSetRegistration.REGISTERED &&
                actual.receiptId > 0L && actual.nativeSequence > 0L && actual.frameSerial > 0L &&
                actual.frameSerial == recordingSerial &&
                actual.producerId == stage.invocation.scope.producerId &&
                actual.producerScopeSerial == stage.invocation.scope.serial &&
                actual.sourceDrawId == stage.invocation.sourceDrawId &&
                actual.parentSourceDrawId == stage.invocation.parentSourceDrawId &&
                actual.passId == stage.invocation.passId && actual.stage == stage.invocation.scope.stage &&
                actual.pipelineIdentity == stage.pipeline && stage.invocation.frame.identity == facts.frame
        }
        val operationReceiptAvailable = (event.nativeOperationReceipts as? OitNativeOperationReceiptQuery.Available)
            ?.receipts?.takeIf { it.isNotEmpty() }?.all { receipt ->
                receipt.identity == facts.frame && receipt.resourceVersion == facts.frame.resourceVersion &&
                    receipt.recordState == OitNativeOperationReceipts.State.RECORDED &&
                    receipt.contentState == OitNativeOperationReceipts.State.CONTENT_FROZEN &&
                    receipt.adapterState == OitNativeOperationReceipts.State.ADAPTER_READY &&
                    receipt.packetId > 0L && receipt.readSetId > 0L &&
                    receipt.sourceDrawId == event.sourceDrawId &&
                    receipt.parentSourceDrawId == event.parentSourceDrawId &&
                    receipt.sourcePassId == event.pass?.descriptor?.passId &&
                    receipt.producerId == (event.scope?.producerId ?: 0L) &&
                    receipt.producerScopeSerial == (event.scope?.serial ?: 0L)
            } == true
        val nativeRange = nativeSequenceRange(event)
        return SealedEventSummary(event.sequence, nativeRange?.min ?: 0L, nativeRange?.max ?: 0L,
            event.sourceDrawId, event.parentSourceDrawId, event.kind,
            event.stage, if (resourceEvidenceComplete(event)) SealedEventResourceState.COMPLETE
            else SealedEventResourceState.INCOMPLETE, event.nativeDrawReceipts is OitNativeReceiptQuery.Available,
            terrainReceiptComplete, operationReceiptAvailable)
    }

    private fun matchesTerrainStage(facts: Facts, event: OitExecutionEvent): Boolean {
        val expectedKind = when (event.stage) {
            OitStage.DEPTH_BOUNDS -> OitExecutionEventKind.DEPTH_BOUNDS
            OitStage.TRANSMITTANCE -> OitExecutionEventKind.TRANSMITTANCE
            OitStage.ACCUMULATE -> OitExecutionEventKind.ACCUMULATE
            null -> null
        }
        if (expectedKind != event.kind || event.drawGroup !is OitDrawGroupEvidence.TerrainSelection ||
            event.terrainPreparedFacts.isEmpty()) return false
        if (facts.material !is OitTerrainStageMaterial.Known) return false
        val matchingStages = event.terrainPreparedFacts.filter { stage ->
            val invocation = stage.invocation
            val stageGroup = stage.drawGroup as? OitDrawGroupEvidence.TerrainSelection
            invocation.frame.identity == facts.frame && invocation.scope.producerId == facts.producerId &&
                invocation.scope.stage == event.stage && stage.vertexBuffer == facts.vertexBuffer &&
                stage.vertexRange.byteOffset == facts.vertexOffset &&
                stage.selection == facts.commandSelections && stage.material == facts.material &&
                stage.material is OitTerrainStageMaterial.Known && stageGroup != null &&
                stageGroup.groupId == event.drawGroup.groupId &&
                (facts.terrainGroupId == null || stageGroup.groupId == facts.terrainGroupId)
        }
        return matchingStages.size == 1
    }

    private fun selectResourceChain(events: List<OitExecutionEvent>, stages: List<OitExecutionEvent>): List<OitExecutionEvent> {
        val singletonKinds = setOf(
            OitExecutionEventKind.DEPTH_BOUNDS_COPY,
            OitExecutionEventKind.DEPTH_BOUNDS_CULL,
            OitExecutionEventKind.CLOUD_DEPTH_COPY,
        )
        if (singletonKinds.any { kind -> events.count { it.kind == kind } > 1 }) return emptyList()
        val stageKinds = setOf(
            OitExecutionEventKind.DEPTH_BOUNDS,
            OitExecutionEventKind.TRANSMITTANCE,
            OitExecutionEventKind.ACCUMULATE,
        )
        if (stageKinds.any { kind -> stages.count { it.kind == kind } > 1 }) return emptyList()
        val terrainCopyEvents = events.filter { it.kind in TERRAIN_DEPTH_COPY_KINDS }
        val waterMasks = events.filter { it.kind == OitExecutionEventKind.WATER_MASK }
        val waterPasses = if (terrainCopyEvents.isEmpty() && waterMasks.isEmpty()) emptyList() else {
            if (terrainCopyEvents.size != 2) return emptyList()
            val masksByPass = waterMasks.groupBy { it.pass?.descriptor?.passId }
            val copyPassIds = terrainCopyEvents.map { it.pass?.descriptor?.passId }.toSet()
            if (copyPassIds.size != 2 || null in copyPassIds || masksByPass.keys != copyPassIds) return emptyList()
            terrainCopyEvents.map { copy ->
                val passId = copy.pass?.descriptor?.passId ?: return emptyList()
                val masks = masksByPass[passId] ?: return emptyList()
                val depth = copy.resourceEvidence as? OitExecutionResourceEvidence.DepthCopy ?: return emptyList()
                var previous = copy
                for (mask in masks) {
                    val evidence = mask.resourceEvidence as? OitExecutionResourceEvidence.PassResources ?: return emptyList()
                    val priorDepthBinding = evidence.sampledViews["InSampler"]
                    if (!follows(previous, mask) || !sameView(evidence.pass.depthTarget, depth.destination) ||
                        (priorDepthBinding != null && !sameView(priorDepthBinding.view, depth.source.view))) return emptyList()
                    previous = mask
                }
                WaterDepthPass(copy, depth, masks)
            }
        }
        val firstWaterPass = waterPasses.firstOrNull()
        val secondWaterPass = waterPasses.getOrNull(1)
        val bounds = stages.firstOrNull { stage ->
            stage.kind == OitExecutionEventKind.DEPTH_BOUNDS &&
                (firstWaterPass?.let { follows(it.masks.last(), stage) } ?: true) &&
                (stage.resourceEvidence as? OitExecutionResourceEvidence.PassResources)
                    ?.pass?.depthTarget?.let { depth ->
                        firstWaterPass?.let { sameView(depth, it.depth.destination) } ?: true
                    } == true
        }
            ?: return emptyList()
        val depthCopy = events.firstOrNull { event ->
            event.kind == OitExecutionEventKind.DEPTH_BOUNDS_COPY && follows(bounds, event) &&
                event.resourceEvidence is OitExecutionResourceEvidence.ColorCopy &&
                sameView(
                    (bounds.resourceEvidence as? OitExecutionResourceEvidence.PassResources)
                        ?.pass?.colorTargets?.singleOrNull(),
                    event.resourceEvidence.source.view,
                )
        } ?: return emptyList()
        val cull = events.firstOrNull { event ->
            event.kind == OitExecutionEventKind.DEPTH_BOUNDS_CULL && follows(depthCopy, event) &&
                event.resourceEvidence is OitExecutionResourceEvidence.PassResources &&
                sameView((depthCopy.resourceEvidence as OitExecutionResourceEvidence.ColorCopy).source.view,
                    event.resourceEvidence.sampledViews["DepthBoundsSampler"]?.view) &&
                sameView(depthCopy.resourceEvidence.destination,
                    event.resourceEvidence.pass.colorTargets.singleOrNull())
        } ?: return emptyList()
        val boundsPass = (bounds.resourceEvidence as OitExecutionResourceEvidence.PassResources).pass
        val cullPass = (cull.resourceEvidence as OitExecutionResourceEvidence.PassResources).pass
        if (!sameView(firstWaterPass?.depth?.source?.view ?: boundsPass.depthTarget, cullPass.depthTarget))
            return emptyList()
        val cloudDepthCopy = events.firstOrNull { it.kind == OitExecutionEventKind.CLOUD_DEPTH_COPY }
        if (cloudDepthCopy != null && !follows(cull, cloudDepthCopy)) return emptyList()
        val beforeSecondWaterPass = cloudDepthCopy ?: cull
        if (secondWaterPass != null && !follows(beforeSecondWaterPass, secondWaterPass.copy))
            return emptyList()
        val transmittancePredecessor = secondWaterPass?.masks?.last() ?: beforeSecondWaterPass
        val transmittance = stages.firstOrNull { stage ->
            stage.kind == OitExecutionEventKind.TRANSMITTANCE &&
                follows(transmittancePredecessor, stage) &&
                (stage.resourceEvidence as? OitExecutionResourceEvidence.PassResources)
                    ?.pass?.depthTarget?.let { depth ->
                        secondWaterPass?.let { waterPass ->
                            sameView(depth, waterPass.depth.destination) &&
                                sameView(waterPass.depth.source.view, cullPass.depthTarget) &&
                                sameView(waterPass.depth.destination, firstWaterPass?.depth?.destination)
                        } ?: sameView(depth, cullPass.depthTarget)
                    } == true
        }
            ?: return emptyList()
        val transmittancePass = (transmittance.resourceEvidence as OitExecutionResourceEvidence.PassResources).pass
        val culledBounds = cullPass.colorTargets.singleOrNull() ?: return emptyList()
        if (!sameView(transmittancePass.sampledViews["DepthBoundsSampler"]?.view, culledBounds)) return emptyList()
        val accumulate = stages.firstOrNull { stage ->
            stage.kind == OitExecutionEventKind.ACCUMULATE && follows(transmittance, stage) &&
                (stage.resourceEvidence as? OitExecutionResourceEvidence.PassResources)?.pass?.depthTarget?.let {
                    val accumulatePass = stage.resourceEvidence
                    val coefficientNames = accumulatePass.sampledViews.keys.filter { name -> name.startsWith("Coeff") }
                        .sortedWith(compareBy(String::length).thenBy { it })
                    val expectedNames = (0 until coefficientNames.size).map { "Coeff$it" }
                    sameView(it, transmittancePass.depthTarget) &&
                        sameView(accumulatePass.sampledViews["DepthBoundsSampler"]?.view, culledBounds) &&
                        coefficientNames.size == transmittancePass.colorTargets.size &&
                        coefficientNames == expectedNames && coefficientNames.indices.all { index ->
                            sameView(accumulatePass.sampledViews[coefficientNames[index]]?.view,
                                transmittancePass.colorTargets.getOrNull(index))
                        }
                } == true
        }
            ?: return emptyList()
        return buildList {
            firstWaterPass?.let {
                add(it.copy)
                addAll(it.masks)
            }
            add(bounds)
            add(depthCopy)
            add(cull)
            cloudDepthCopy?.let(::add)
            secondWaterPass?.let {
                add(it.copy)
                addAll(it.masks)
            }
            add(transmittance)
            add(accumulate)
        }.sortedBy(OitExecutionEvent::sequence)
    }

    private data class WaterDepthPass(val copy: OitExecutionEvent, val depth: OitExecutionResourceEvidence.DepthCopy,
        val masks: List<OitExecutionEvent>)

    private fun follows(previous: OitExecutionEvent, next: OitExecutionEvent): Boolean {
        val previousNative = nativeSequenceRange(previous) ?: return false
        val nextNative = nativeSequenceRange(next) ?: return false
        return previous.sequence < next.sequence && previousNative.max < nextNative.min
    }

    private data class NativeSequenceRange(val min: Long, val max: Long)

    private fun nativeSequenceRange(event: OitExecutionEvent): NativeSequenceRange? {
        val sequences = ArrayList<Long>()
        for (stage in event.terrainPreparedFacts) {
            val receipt = stage.nativeReceipt as? OitNativeTerrainStageReceiptResult.Available ?: return null
            sequences += receipt.receipt.nativeSequence
        }
        val operations = event.nativeOperationReceipts as? OitNativeOperationReceiptQuery.Available
        for (receipt in operations?.receipts.orEmpty()) sequences += receipt.nativeSequence
        if (sequences.isEmpty() || sequences.any { it <= 0L }) return null
        sequences.sort()
        if (sequences.zipWithNext().any { (left, right) -> right <= left }) return null
        return NativeSequenceRange(sequences.first(), sequences.last())
    }

    private fun resourceEvidenceComplete(event: OitExecutionEvent): Boolean = when (val evidence = event.resourceEvidence) {
        is OitExecutionResourceEvidence.Unavailable -> false
        is OitExecutionResourceEvidence.DepthCopy -> evidence.source.view.width > 0 && evidence.destination.width > 0
        is OitExecutionResourceEvidence.ColorCopy -> evidence.source.view.width > 0 && evidence.destination.width > 0
        is OitExecutionResourceEvidence.PassResources -> when (event.kind) {
            OitExecutionEventKind.WATER_MASK -> evidence.pass.depthTarget != null &&
                evidence.pass.colorTargets.all { it == null } && evidence.sampledViews.keys.all { it == "InSampler" }
            OitExecutionEventKind.DEPTH_BOUNDS -> evidence.pass.depthTarget != null &&
                evidence.pass.colorTargets.singleOrNull() != null && evidence.sampledViews.isEmpty()
            OitExecutionEventKind.DEPTH_BOUNDS_CULL -> evidence.pass.depthTarget != null &&
                evidence.pass.colorTargets.singleOrNull() != null &&
                evidence.sampledViews["DepthBoundsSampler"] != null &&
                evidence.sampledViews.keys.all { it == "DepthBoundsSampler" || it == "InSampler" }
            OitExecutionEventKind.TRANSMITTANCE -> evidence.pass.depthTarget != null &&
                evidence.pass.colorTargets.size in setOf(1, 2, 4) && evidence.pass.colorTargets.all { it != null } &&
                evidence.sampledViews.keys == setOf("DepthBoundsSampler")
            OitExecutionEventKind.ACCUMULATE -> evidence.pass.depthTarget != null &&
                evidence.pass.colorTargets.singleOrNull() != null &&
                evidence.sampledViews.keys.contains("DepthBoundsSampler") &&
                evidence.sampledViews.keys.filter { it.startsWith("Coeff") }.let { names ->
                    names.size in setOf(1, 2, 4) &&
                        names.sortedWith(compareBy(String::length).thenBy { it }) ==
                            (0 until names.size).map { "Coeff$it" } && evidence.sampledViews.size == names.size + 1
                }
            else -> evidence.pass.depthTarget != null && evidence.pass.colorTargets.any { it != null }
        }
    }

    private fun normalizationMatches(input: OitCompositeNormalizationInput, chain: List<OitExecutionEvent>): Boolean {
        val transmittance = chain.firstOrNull { it.kind == OitExecutionEventKind.TRANSMITTANCE }
            ?.resourceEvidence as? OitExecutionResourceEvidence.PassResources ?: return false
        val accumulate = chain.firstOrNull { it.kind == OitExecutionEventKind.ACCUMULATE }
            ?.resourceEvidence as? OitExecutionResourceEvidence.PassResources ?: return false
        val output = transmittance.pass.colorTargets
        if (output.size != input.coefficients.size || output.indices.any {
                !sameCompositeView(input.coefficients[it], output[it])
            }) return false
        val bounds = chain.firstOrNull { it.kind == OitExecutionEventKind.DEPTH_BOUNDS }
            ?.resourceEvidence as? OitExecutionResourceEvidence.PassResources ?: return false
        val cull = chain.firstOrNull { it.kind == OitExecutionEventKind.DEPTH_BOUNDS_CULL }
            ?.resourceEvidence as? OitExecutionResourceEvidence.PassResources ?: return false
        if (!sameCompositeView(input.accumulate, accumulate.pass.colorTargets.singleOrNull()) ||
            !sameCompositeView(input.depthBounds, bounds.pass.colorTargets.singleOrNull()) ||
            !sameCompositeView(input.depth, cull.pass.depthTarget)) return false
        if (input.coefficients.size != transmittance.pass.colorTargets.size) return false
        return input.coefficients.indices.all { index ->
            sameCompositeView(input.coefficients[index], accumulate.sampledViews["Coeff$index"]?.view)
        }
    }

    private fun inputResourcesCurrent(input: OitCompositeNormalizationInput, resourceVersion: Long): Boolean {
        return input.frame.identity.resourceVersion == resourceVersion
    }

    private fun sameView(left: OitTextureViewFacts?, right: OitTextureViewFacts?): Boolean =
        left != null && right != null && left.texture === right.texture &&
            left.baseMipLevel == right.baseMipLevel && left.mipLevels == right.mipLevels &&
            left.width == right.width && left.height == right.height && left.format == right.format

    private fun sameCompositeView(left: OitCompositeTexture?, right: OitTextureViewFacts?): Boolean =
        left != null && right != null && left.texture === right.texture &&
            left.baseMip == right.baseMipLevel && left.mipLevels == right.mipLevels &&
            left.width == right.width && left.height == right.height && left.format == right.format
}
