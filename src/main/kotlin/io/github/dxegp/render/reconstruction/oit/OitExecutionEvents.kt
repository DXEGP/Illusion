package io.github.dxegp.render.reconstruction.oit

import net.minecraft.client.renderer.oit.OitStage
import java.util.Collections

internal enum class OitExecutionEventKind {
    WATER_MASK,
    WATER_DEPTH_COPY,
    CLOUD_DEPTH_COPY,
    TERRAIN_DEPTH_COPY,
    DEPTH_BOUNDS_COPY,
    DEPTH_BOUNDS,
    DEPTH_BOUNDS_CULL,
    TRANSMITTANCE,
    ACCUMULATE,
    COMPOSITE,
    UNCLASSIFIED_DRAW,
}

internal data class OitExecutionProducer(val producerId: Long, val kind: OitProducerKind)

internal enum class OitExecutionScopeCompletion { ACTIVE, EMPTY, RETURNED, FAILED }

internal data class OitExecutionScope(
    val scope: OitProducerScope,
    val actualDrawCalls: Long,
    val completion: OitExecutionScopeCompletion,
    val customHandled: Boolean?,
    val drawCountUnit: OitDrawCountUnit = OitDrawCountUnit.FRONTEND_INVOCATION,
)

internal enum class OitDrawCountUnit { FRONTEND_INVOCATION }

internal sealed interface OitColorAttachmentLoad {
    data object Load : OitColorAttachmentLoad
    data class Clear(val red: Float, val green: Float, val blue: Float, val alpha: Float) : OitColorAttachmentLoad
}

internal sealed interface OitDepthAttachmentLoad {
    data object Load : OitDepthAttachmentLoad
    data class Clear(val depth: Double) : OitDepthAttachmentLoad
}

internal enum class OitAttachmentStore { UNKNOWN }

internal data class OitColorAttachmentFacts(val view: OitTextureViewFacts,
    val load: OitColorAttachmentLoad, val store: OitAttachmentStore)

internal data class OitDepthAttachmentFacts(val view: OitTextureViewFacts,
    val load: OitDepthAttachmentLoad, val store: OitAttachmentStore)

internal class OitPassDescriptorFacts(
    val passId: Long,
    colors: List<OitColorAttachmentFacts?>,
    val depth: OitDepthAttachmentFacts?,
    val renderX: Int,
    val renderY: Int,
    val renderWidth: Int,
    val renderHeight: Int,
) {
    val colors: List<OitColorAttachmentFacts?> = Collections.unmodifiableList(ArrayList(colors))
}

internal enum class OitExecutionPassCompletion { ACTIVE, RETURNED, FAILED }

internal data class OitExecutionPass(val descriptor: OitPassDescriptorFacts,
    val completion: OitExecutionPassCompletion)

internal sealed interface OitDrawGroupEvidence {
    data object Unavailable : OitDrawGroupEvidence
    data class TerrainSelection(val groupId: Long) : OitDrawGroupEvidence
    data class TerrainUnavailable(val reason: OitTerrainGroupUnavailable) : OitDrawGroupEvidence
}

internal enum class OitNativeExecutionGap {
    DRAW_RECEIPT_UNAVAILABLE,
    FROZEN_CONTENT_LEASE_UNAVAILABLE,
    CROSS_STAGE_DRAW_SELECTION_UNAVAILABLE,
    ATTACHMENT_STORE_UNKNOWN,
    NATIVE_COMMAND_GRANULARITY_UNAVAILABLE,
    BULK_DRAW_SELECTION_UNAVAILABLE,
}

internal enum class OitExecutionFailureKind { DRAW, PASS, SCOPE, OPERATION, PROVIDER_STAGE, HISTORY_CONTRIBUTION, NATIVE_RECEIPT, TERRAIN_STAGE }

internal data class OitExecutionFailure(val kind: OitExecutionFailureKind,
    val sourceDrawId: Long?, val passId: Long?, val scopeSerial: Long?)

internal sealed interface OitExecutionResourceEvidence {
    class PassResources(val pass: OitPassFacts, sampledViews: Map<String, OitSampledView>) :
        OitExecutionResourceEvidence {
        val sampledViews: Map<String, OitSampledView> = Collections.unmodifiableMap(LinkedHashMap(sampledViews))
    }
    data class DepthCopy(val source: OitSampledView, val destination: OitTextureViewFacts) :
        OitExecutionResourceEvidence
    data class ColorCopy(val source: OitSampledView, val destination: OitTextureViewFacts) :
        OitExecutionResourceEvidence
    data class Unavailable(val reason: String) : OitExecutionResourceEvidence
}

internal class OitExecutionEvent(
    val sequence: Long,
    val sourceDrawId: Long,
    val parentSourceDrawId: Long?,
    val sourceKind: OitDrawCountUnit,
    val disposition: OitDrawDisposition,
    val selection: OitDrawSelection,
    val nativeDrawReceipts: OitNativeReceiptQuery = OitNativeReceiptQuery.NotQueried,
    val drawGroup: OitDrawGroupEvidence,
    val kind: OitExecutionEventKind,
    val stage: OitStage?,
    val scope: OitProducerScope?,
    val pass: OitPassFacts?,
    val pipelineLocation: String?,
    val resourceVersion: Long,
    val resourceEvidence: OitExecutionResourceEvidence,
    terrainPreparedFacts: List<OitTerrainStageFacts> = emptyList(),
    val nativeOperationReceipts: OitNativeOperationReceiptQuery = OitNativeOperationReceiptQuery.NotQueried,
) {
    val terrainPreparedFacts: List<OitTerrainStageFacts> = Collections.unmodifiableList(ArrayList(terrainPreparedFacts))
}

internal sealed interface OitExecutionJournalFact {
    data class ScopeBegin(val scope: OitProducerScope, val parentScopeSerial: Long?, val passId: Long?) :
        OitExecutionJournalFact
    data class ScopeEnd(val scope: OitExecutionScope) : OitExecutionJournalFact
    data class PassBegin(val descriptor: OitPassDescriptorFacts, val scope: OitProducerScope?, val stage: OitStage?) :
        OitExecutionJournalFact
    data class PassEnd(val pass: OitExecutionPass) : OitExecutionJournalFact
    data class Observation(val event: OitExecutionEvent) : OitExecutionJournalFact
    data class Failure(val failure: OitExecutionFailure) : OitExecutionJournalFact
}

internal data class OitExecutionJournalEntry(val sequence: Long, val fact: OitExecutionJournalFact)

internal class OitExecutionRecording(
    val frame: OitFrameFacts,
    val recordingSerial: Long,
    producers: List<OitExecutionProducer>,
    scopes: List<OitExecutionScope>,
    passes: List<OitExecutionPass>,
    events: List<OitExecutionEvent>,
    failures: List<OitExecutionFailure>,
    nativeGaps: Set<OitNativeExecutionGap>,
    val frameFailed: Boolean,
    journalEntries: List<OitExecutionJournalEntry>,
) {
    val producers: List<OitExecutionProducer> = Collections.unmodifiableList(ArrayList(producers))
    val scopes: List<OitExecutionScope> = Collections.unmodifiableList(ArrayList(scopes))
    val passes: List<OitExecutionPass> = Collections.unmodifiableList(ArrayList(passes))
    val events: List<OitExecutionEvent> = Collections.unmodifiableList(ArrayList(events))
    val failures: List<OitExecutionFailure> = Collections.unmodifiableList(ArrayList(failures))
    val nativeGaps: Set<OitNativeExecutionGap> = Collections.unmodifiableSet(LinkedHashSet(nativeGaps))
    val journalEntries: List<OitExecutionJournalEntry> = Collections.unmodifiableList(ArrayList(journalEntries))
}
