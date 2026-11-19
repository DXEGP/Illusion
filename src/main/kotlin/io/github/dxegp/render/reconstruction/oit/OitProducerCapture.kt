package io.github.dxegp.render.reconstruction.oit

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.command.D3D12TerrainDraw
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.oit.OitStage
import java.util.Collections
import java.util.IdentityHashMap

internal enum class OitProducerKind {
    TERRAIN, FEATURE, BORDER, WEATHER, CLOUD, CUSTOM_WEATHER, CUSTOM_CLOUD, CUSTOM_TRANSLUCENT;

    val custom: Boolean
        get() = this == CUSTOM_WEATHER || this == CUSTOM_CLOUD || this == CUSTOM_TRANSLUCENT
}

internal class OitProducer(val kind: OitProducerKind, val owner: Any)

internal class OitFrameFacts(val identity: VanillaFrameIdentity, val historyGeneration: Long) {
    val schemaVersion = 1
}

internal class OitProducerScope(val frame: OitFrameFacts, val producer: OitProducer, val stage: OitStage,
    val serial: Long, val producerId: Long)

internal enum class OitScopeCompletion { RETURNED, FAILED }

internal enum class OitDrawDisposition { EMPTY, GEOMETRY, INDIRECT_COMMANDS }

internal class OitProducerResult(
    val scope: OitProducerScope,
    val drawCalls: Long,
    val completion: OitScopeCompletion,
    val customHandled: Boolean?,
)

internal class OitTextureViewFacts(view: GpuTextureView) {
    val source: GpuTextureView = view
    val texture: GpuTexture = view.texture()
    val baseMipLevel: Int = view.baseMipLevel()
    val mipLevels: Int = view.mipLevels()
    val width: Int = view.getWidth(0)
    val height: Int = view.getHeight(0)
    val format: GpuFormat = texture.format
}

internal class OitSampledView(val view: OitTextureViewFacts, val sampler: GpuSampler)

internal class OitPassFacts(
    val frame: OitFrameFacts,
    val stage: OitStage,
    val providerStage: OitStage?,
    colorTargets: List<OitTextureViewFacts?>,
    val depthTarget: OitTextureViewFacts?,
    sampledViews: Map<String, OitSampledView>,
    originalSampledViews: Map<String, OitSampledView>?,
    val historyEligible: Boolean = true,
    val descriptor: OitPassDescriptorFacts,
) {
    companion object {
        val guideSamplerNames: Set<String> = Collections.unmodifiableSet(
            (0 until LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT).mapTo(LinkedHashSet()) { "Coeff$it" }
                .apply { add("DepthBoundsSampler") })
    }

    val colorTargets: List<OitTextureViewFacts?> = Collections.unmodifiableList(ArrayList(colorTargets))
    val sampledViews: Map<String, OitSampledView> = Collections.unmodifiableMap(LinkedHashMap(sampledViews))
    val originalSampledViews: Map<String, OitSampledView>? = originalSampledViews?.let {
        Collections.unmodifiableMap(LinkedHashMap(it))
    }

    fun hasAccumulateInputs(): Boolean = historyEligible && stage == OitStage.ACCUMULATE && providerStage == stage &&
        colorTargets.size == 1 && colorTargets[0] != null && depthTarget != null &&
        originalSampledViews != null && sampledViews.keys == guideSamplerNames &&
        originalSampledViews.keys == guideSamplerNames && originalSampledViews.all { (name, original) ->
            val actual = sampledViews[name]
            actual?.view?.source === original.view.source && actual.sampler === original.sampler
        }
}

internal class OitDrawFacts(val frame: OitFrameFacts, val scope: OitProducerScope?, val pass: OitPassFacts?) {
    var surfaces: OitSurfaceDraw? = null
        private set

    fun captureDrawSurfaces(): OitSurfaceDraw? = surfaces ?: OitSurfaceInputs.drawInput(scope, pass).also {
        surfaces = it
    }

    val accumulate: Boolean
        get() = scope?.stage == OitStage.ACCUMULATE || pass?.stage == OitStage.ACCUMULATE

    val knownProducer: Boolean
        get() = scope != null && !scope.producer.kind.custom && scope.stage == pass?.stage &&
            scope.frame === frame && pass.frame === frame
}

internal class OitFrameResult(val frame: OitFrameFacts, producers: List<OitProducerResult>, val failed: Boolean) {
    val producers: List<OitProducerResult> = Collections.unmodifiableList(ArrayList(producers))
}

internal object OitProducerCapture {
    private class DrawRecording(val disposition: OitDrawDisposition, val selection: OitDrawSelection,
        val sourceDrawId: Long?,
        val parentSourceDrawId: Long?) {
        val contributions = ArrayList<() -> Unit>()
        var facts: OitDrawFacts? = null
        var nativeReceiptFailure: Throwable? = null
        var terrainStageFailure: Throwable? = null
        var terrainStageFacts: MutableList<OitTerrainStageFacts>? = null
        var operationReceipts: OitNativeOperationReceiptQuery = OitNativeOperationReceiptQuery.NotQueried
    }

    private class ScopeRecording(val facts: OitProducerScope) {
        var drawCalls = 0L
        var actualDrawCalls = 0L
    }

    private class FrameRecording(val facts: OitFrameFacts) {
        val scopes = ArrayDeque<ScopeRecording>()
        val results = ArrayList<OitProducerResult>()
        val passes = IdentityHashMap<Any, OitPassFacts>()
        val pipelineLocations = IdentityHashMap<Any, String>()
        val executionSampledViews = IdentityHashMap<Any, MutableMap<String, OitSampledView>>()
        val executionEvents = ArrayList<OitExecutionEvent>()
        val journalEntries = ArrayList<OitExecutionJournalEntry>()
        private var journalSequence = 0L
        val producers = ArrayList<OitExecutionProducer>()
        val producerIds = OitProducerKind.entries.associateWith { IdentityHashMap<Any, Long>() }
        val executionScopes = ArrayList<OitExecutionScope>()
        val executionPasses = LinkedHashMap<Long, OitExecutionPass>()
        val failures = ArrayList<OitExecutionFailure>()
        var nextProducerId = 0L
        var nextPassId = 0L
        var nextSourceDrawId = 0L
        val textures = Collections.newSetFromMap(IdentityHashMap<D3D12GpuTexture, Boolean>())
        var activePass: Any? = null
        var providerStage: OitStage? = null
        var draw: DrawRecording? = null
        var executionKind: OitExecutionEventKind? = null
        var failed = false
        var executionSequence = 0L
        val assignedTerrainReceiptIds = HashSet<Long>()
        val assignedTerrainStageReceiptIds = HashSet<Long>()
        var lastTerrainStageSequence = 0L
        val terrainBufferIds = IdentityHashMap<GpuBuffer, Long>()
        val terrainOccurrenceCounts = HashMap<OitTerrainStageOccurrenceKey, Int>()
        val terrainGroups = HashMap<OitTerrainOccurrenceKey, Long>()
        var nextTerrainBufferId = 0L
        var nextTerrainGroupId = 0L

        fun recordJournal(fact: OitExecutionJournalFact) {
            journalSequence = Math.incrementExact(journalSequence)
            journalEntries += OitExecutionJournalEntry(journalSequence, fact)
        }

        fun recordFailure(failure: OitExecutionFailure) {
            failures += failure
            recordJournal(OitExecutionJournalFact.Failure(failure))
        }

        fun retainView(view: GpuTextureView): OitTextureViewFacts {
            val facts = OitTextureViewFacts(view)
            val texture = facts.texture as? D3D12GpuTexture
            if (texture != null && textures.add(texture)) texture.addViews()
            return facts
        }

        fun recordExecution(passKey: Any, pass: OitPassFacts, draw: DrawRecording,
            kind: OitExecutionEventKind, pipelineLocation: String?, nativeReceipts: OitNativeReceiptQuery) {
            val sampled = Collections.unmodifiableMap(LinkedHashMap(executionSampledViews[passKey].orEmpty()))
            val evidence = when (kind) {
                OitExecutionEventKind.WATER_DEPTH_COPY,
                OitExecutionEventKind.CLOUD_DEPTH_COPY,
                OitExecutionEventKind.TERRAIN_DEPTH_COPY -> {
                    val source = sampled["InSampler"]
                    val destination = pass.depthTarget
                    if (source != null && destination != null) OitExecutionResourceEvidence.DepthCopy(source, destination)
                    else OitExecutionResourceEvidence.Unavailable("Depth copy source or destination unavailable")
                }
                OitExecutionEventKind.DEPTH_BOUNDS_COPY -> {
                    val source = sampled["InSampler"]
                    val destination = pass.colorTargets.singleOrNull()
                    if (source != null && destination != null) OitExecutionResourceEvidence.ColorCopy(source, destination)
                    else OitExecutionResourceEvidence.Unavailable("Depth bounds copy source or destination unavailable")
                }
                OitExecutionEventKind.DEPTH_BOUNDS_CULL -> {
                    if (sampled["DepthBoundsSampler"] != null && pass.colorTargets.singleOrNull() != null &&
                        pass.depthTarget != null) OitExecutionResourceEvidence.PassResources(pass, sampled)
                    else OitExecutionResourceEvidence.Unavailable("Depth bounds cull source or destination unavailable")
                }
                OitExecutionEventKind.COMPOSITE -> {
                    if (sampled.keys.containsAll(OitPassFacts.guideSamplerNames) && sampled["Sampler0"] != null &&
                        pass.colorTargets.singleOrNull() != null && pass.depthTarget != null)
                        OitExecutionResourceEvidence.PassResources(pass, sampled)
                    else OitExecutionResourceEvidence.Unavailable("Composite source or destination unavailable")
                }
                else -> OitExecutionResourceEvidence.PassResources(pass, sampled)
            }
            executionSequence = Math.incrementExact(executionSequence)
            val event = OitExecutionEvent(executionSequence, checkNotNull(draw.sourceDrawId),
                draw.parentSourceDrawId, OitDrawCountUnit.FRONTEND_INVOCATION,
                draw.disposition, draw.selection, nativeReceipts,
                draw.terrainStageFacts?.singleOrNull()?.drawGroup ?: OitDrawGroupEvidence.Unavailable, kind,
                pass.providerStage ?: scopes.lastOrNull()?.facts?.stage,
                scopes.lastOrNull()?.facts, pass, pipelineLocation,
                facts.identity.resourceVersion,
                evidence, draw.terrainStageFacts.orEmpty(), draw.operationReceipts)
            executionEvents += event
            if (nativeReceipts != OitNativeReceiptQuery.Failed) {
                recordJournal(OitExecutionJournalFact.Observation(event))
            }
        }
    }

    private var recording: FrameRecording? = null
    private var nextScopeSerial = 0L

    fun beginFrame(inputs: VanillaFrameInputs) {
        RenderSystem.assertOnRenderThread()
        check(recording == null) { "OIT producer frame began before retirement" }
        recording = FrameRecording(OitFrameFacts(inputs.identity, inputs.historyGeneration))
    }

    fun <Result> recordScope(producer: OitProducer, stage: OitStage, action: () -> Result): Result {
        val current = recording ?: return action()
        RenderSystem.assertOnRenderThread()
        nextScopeSerial = Math.incrementExact(nextScopeSerial)
        val producerId = current.producerIds.getValue(producer.kind).getOrPut(producer.owner) {
            current.nextProducerId = Math.incrementExact(current.nextProducerId)
            current.producers += OitExecutionProducer(current.nextProducerId, producer.kind)
            current.nextProducerId
        }
        val scope = ScopeRecording(OitProducerScope(current.facts, producer, stage, nextScopeSerial, producerId))
        current.recordJournal(OitExecutionJournalFact.ScopeBegin(scope.facts, current.scopes.lastOrNull()?.facts?.serial,
            current.activePass?.let(current.passes::get)?.descriptor?.passId))
        current.scopes.addLast(scope)
        var completion = OitScopeCompletion.FAILED
        var customHandled: Boolean? = null
        try {
            val result = action()
            completion = OitScopeCompletion.RETURNED
            if (producer.kind.custom) customHandled = result as? Boolean
            return result
        } finally {
            check(current.scopes.removeLast() === scope) { "OIT producer scopes retired out of order" }
            current.results.add(OitProducerResult(scope.facts, scope.drawCalls, completion, customHandled))
            val executionCompletion = when {
                completion == OitScopeCompletion.FAILED -> OitExecutionScopeCompletion.FAILED
                scope.actualDrawCalls == 0L -> OitExecutionScopeCompletion.EMPTY
                else -> OitExecutionScopeCompletion.RETURNED
            }
            val executionScope = OitExecutionScope(scope.facts, scope.actualDrawCalls,
                executionCompletion, customHandled)
            current.executionScopes += executionScope
            current.recordJournal(OitExecutionJournalFact.ScopeEnd(executionScope))
            if (completion == OitScopeCompletion.FAILED) {
                current.failed = true
                current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.SCOPE, null,
                    current.activePass?.let(current.passes::get)?.descriptor?.passId, scope.facts.serial))
            }
        }
    }

    fun recordProviderStage(stage: OitStage, action: () -> RenderPass): RenderPass {
        val current = recording ?: return action()
        val previous = current.providerStage
        current.providerStage = stage
        try {
            val pass = action()
            current.passes[pass]?.let { facts ->
                current.passes[pass] = OitPassFacts(facts.frame, facts.stage, facts.providerStage,
                    facts.colorTargets, facts.depthTarget, facts.sampledViews, facts.sampledViews,
                    facts.historyEligible, facts.descriptor)
            }
            return pass
        } catch (failure: Throwable) {
            current.failed = true
            current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.PROVIDER_STAGE,
                current.draw?.sourceDrawId, current.activePass?.let(current.passes::get)?.descriptor?.passId,
                current.scopes.lastOrNull()?.facts?.serial))
            throw failure
        } finally {
            current.providerStage = previous
        }
    }

    fun recordPass(pass: Any, descriptor: RenderPassDescriptor) {
        val current = recording ?: return
        if (OitReconstructionInputs.terrainRecordFrames()?.isRecording() == false) return
        val stage = current.providerStage ?: current.scopes.lastOrNull()?.facts?.stage
        check(current.activePass == null) { "OIT passes overlap" }
        val colors = descriptor.colorAttachments().map { attachment ->
            attachment?.textureView()?.let(current::retainView)
        }
        val depth = descriptor.depthAttachment()?.textureView()?.let(current::retainView)
        current.nextPassId = Math.incrementExact(current.nextPassId)
        val colorAttachments = descriptor.colorAttachments().mapIndexed { index, attachment ->
            attachment?.let {
                val clear = attachment.clearValue()
                val load = if (clear.isPresent) clear.get().let { value ->
                    OitColorAttachmentLoad.Clear(value.x(), value.y(), value.z(), value.w())
                } else OitColorAttachmentLoad.Load
                OitColorAttachmentFacts(checkNotNull(colors[index]), load, OitAttachmentStore.UNKNOWN)
            }
        }
        val depthAttachment = descriptor.depthAttachment()?.let { attachment ->
            val clear = attachment.clearValue()
            OitDepthAttachmentFacts(checkNotNull(depth),
                if (clear.isPresent) OitDepthAttachmentLoad.Clear(clear.getAsDouble()) else OitDepthAttachmentLoad.Load,
                OitAttachmentStore.UNKNOWN)
        }
        val area = descriptor.renderArea()
        val descriptorFacts = OitPassDescriptorFacts(current.nextPassId, colorAttachments, depthAttachment,
            area.x(), area.y(), area.width(), area.height())
        current.passes[pass] = OitPassFacts(current.facts, stage ?: OitStage.ACCUMULATE,
            current.providerStage, colors, depth, emptyMap(), null, stage == OitStage.ACCUMULATE, descriptorFacts)
        current.executionPasses[descriptorFacts.passId] = OitExecutionPass(descriptorFacts, OitExecutionPassCompletion.ACTIVE)
        current.executionSampledViews[pass] = LinkedHashMap()
        current.activePass = pass
        current.recordJournal(OitExecutionJournalFact.PassBegin(descriptorFacts, current.scopes.lastOrNull()?.facts, stage))
    }

    fun recordPipeline(pass: Any, location: String) {
        recording?.let { current ->
            if (current.passes.containsKey(pass)) current.pipelineLocations[pass] = location
        }
    }

    fun recordTextureBinding(pass: Any, name: String, view: GpuTextureView, sampler: GpuSampler) {
        val current = recording ?: return
        val facts = current.passes[pass] ?: return
        if (name !in OitPassFacts.guideSamplerNames && name != "InSampler" && name != "Sampler0") return
        val executionViews = checkNotNull(current.executionSampledViews[pass])
        val captured = executionViews[name]?.takeIf { it.view.source === view && it.sampler === sampler }
            ?: OitSampledView(current.retainView(view), sampler).also { executionViews[name] = it }
        if (name !in OitPassFacts.guideSamplerNames) return
        if (facts.sampledViews[name]?.let { it.view.source === view && it.sampler === sampler } == true) return
        val sampled = LinkedHashMap(facts.sampledViews)
        sampled[name] = captured
        current.passes[pass] = OitPassFacts(facts.frame, facts.stage, facts.providerStage,
            facts.colorTargets, facts.depthTarget, sampled, facts.originalSampledViews, facts.historyEligible, facts.descriptor)
    }

    fun retirePass(pass: Any) {
        val current = recording ?: return
        current.passes.remove(pass)?.descriptor?.let { descriptor ->
            val completion = current.executionPasses.getValue(descriptor.passId).completion
            val executionPass = OitExecutionPass(descriptor,
                if (completion == OitExecutionPassCompletion.FAILED) completion else OitExecutionPassCompletion.RETURNED)
            current.executionPasses[descriptor.passId] = executionPass
            current.recordJournal(OitExecutionJournalFact.PassEnd(executionPass))
        }
        current.pipelineLocations.remove(pass)
        current.executionSampledViews.remove(pass)
        if (current.activePass === pass) current.activePass = null
    }

    fun recordPassFailure() {
        val current = recording ?: return
        current.failed = true
        val descriptor = current.activePass?.let(current.passes::get)?.descriptor
        descriptor?.let { current.executionPasses[it.passId] = OitExecutionPass(it, OitExecutionPassCompletion.FAILED) }
        current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.PASS, current.draw?.sourceDrawId,
            descriptor?.passId, current.scopes.lastOrNull()?.facts?.serial))
    }

    fun <Result> recordExecutionOperation(kind: OitExecutionEventKind, action: () -> Result): Result {
        val current = recording ?: return action()
        val previous = current.executionKind
        current.executionKind = kind
        try {
            return action()
        } catch (failure: Throwable) {
            current.failed = true
            current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.OPERATION, current.draw?.sourceDrawId,
                current.activePass?.let(current.passes::get)?.descriptor?.passId, current.scopes.lastOrNull()?.facts?.serial))
            throw failure
        } finally {
            current.executionKind = previous
        }
    }

    fun terrainStageInvocation(): OitTerrainStageInvocation? {
        val current = recording ?: return null
        val draw = current.draw ?: return null
        val sourceDrawId = draw.sourceDrawId ?: return null
        val scope = current.scopes.lastOrNull()?.facts ?: return null
        if (scope.producer.kind != OitProducerKind.TERRAIN) return null
        val pass = current.activePass?.let(current.passes::get) ?: return null
        if (scope.frame !== current.facts || pass.frame !== current.facts ||
            scope.stage != pass.stage || scope.stage != pass.providerStage) return null
        return OitTerrainStageInvocation(current.facts, scope, sourceDrawId,
            draw.parentSourceDrawId, pass.descriptor.passId)
    }

    fun recordTerrainStage(invocation: OitTerrainStageInvocation, draw: D3D12TerrainDraw,
        prepared: D3D12MeshDrawCapture.TerrainPreparedFacts, material: OitTerrainStageMaterial,
        receipt: OitNativeTerrainStageReceiptResult) {
        val current = checkNotNull(recording)
        val drawRecording = checkNotNull(current.draw)
        check(current.facts === invocation.frame && drawRecording.sourceDrawId == invocation.sourceDrawId &&
            current.scopes.lastOrNull()?.facts === invocation.scope &&
            current.activePass?.let(current.passes::get)?.descriptor?.passId == invocation.passId)
        val commandOwner = current.terrainBufferIds.getOrPut(draw.commands.buffer()) {
            current.nextTerrainBufferId = Math.incrementExact(current.nextTerrainBufferId)
            current.nextTerrainBufferId
        }
        val vertexOwner = current.terrainBufferIds.getOrPut(draw.vertexBuffer) {
            current.nextTerrainBufferId = Math.incrementExact(current.nextTerrainBufferId)
            current.nextTerrainBufferId
        }
        val commandRange = OitTerrainBufferRange(commandOwner, draw.commands.offset(), draw.commands.length())
        val vertexRange = OitTerrainBufferRange(vertexOwner, prepared.vertexOffset, prepared.batch.requiredVertexBytes)
        val group = when (material) {
            is OitTerrainStageMaterial.Known -> {
                val selectionKey = OitTerrainSelectionKey(invocation.scope.producerId, commandRange, vertexRange,
                    draw.drawCount, prepared.commandSelections, material)
                val occurrenceKey = OitTerrainStageOccurrenceKey(selectionKey, invocation.scope.stage)
                val occurrence = current.terrainOccurrenceCounts.getOrDefault(occurrenceKey, 0)
                current.terrainOccurrenceCounts[occurrenceKey] = Math.incrementExact(occurrence)
                val groupKey = OitTerrainOccurrenceKey(selectionKey, occurrence)
                val groupId = current.terrainGroups.getOrPut(groupKey) {
                    current.nextTerrainGroupId = Math.incrementExact(current.nextTerrainGroupId)
                    current.nextTerrainGroupId
                }
                OitDrawGroupEvidence.TerrainSelection(groupId)
            }
            is OitTerrainStageMaterial.Unavailable -> OitDrawGroupEvidence.TerrainUnavailable(material.reason)
        }
        val pending = drawRecording.terrainStageFacts ?: ArrayList<OitTerrainStageFacts>().also {
            drawRecording.terrainStageFacts = it
        }
        if (receipt is OitNativeTerrainStageReceiptResult.Available) {
            val actual = receipt.receipt
            check(actual.sourceDrawId == invocation.sourceDrawId && actual.parentSourceDrawId == invocation.parentSourceDrawId &&
                actual.passId == invocation.passId && actual.producerId == invocation.scope.producerId &&
                actual.producerScopeSerial == invocation.scope.serial && actual.stage == invocation.scope.stage &&
                actual.pipelineIdentity == prepared.pipeline) {
                "Terrain stage receipt does not match the actual prepared draw"
            }
            check(actual.nativeSequence > current.lastTerrainStageSequence &&
                current.assignedTerrainStageReceiptIds.add(actual.receiptId)) {
                "Terrain stage receipt was repeated or returned out of order"
            }
            current.lastTerrainStageSequence = actual.nativeSequence
        }
        pending += OitTerrainStageFacts(invocation, draw, prepared, commandRange, vertexRange, material, group, receipt)
    }

    fun recordTerrainStageFailure(invocation: OitTerrainStageInvocation, failure: Throwable) {
        val current = checkNotNull(recording)
        val draw = checkNotNull(current.draw)
        check(current.facts === invocation.frame && draw.sourceDrawId == invocation.sourceDrawId)
        current.failed = true
        draw.terrainStageFailure = failure
        current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.TERRAIN_STAGE,
            invocation.sourceDrawId, invocation.passId, invocation.scope.serial))
    }

    fun drawInputs(): OitDrawFacts? {
        val current = recording ?: return null
        current.draw?.facts?.let { return it }
        val scope = current.scopes.lastOrNull()?.facts
        val pass = current.activePass?.let(current.passes::get)
        if (scope?.stage != OitStage.ACCUMULATE && pass?.hasAccumulateInputs() != true) return null
        return OitDrawFacts(current.facts, scope, pass).also {
            current.draw?.facts = it
        }
    }

    fun captureDrawSurfaces(): OitSurfaceDraw? {
        val current = recording ?: return null
        val draw = checkNotNull(current.draw) { "OIT surfaces require an active draw transaction" }
        if (draw.disposition == OitDrawDisposition.EMPTY) return null
        return drawInputs()?.captureDrawSurfaces()
    }

    fun captureSampledView(view: GpuTextureView, sampler: GpuSampler): OitSampledView? {
        val current = recording ?: return null
        if (current.scopes.lastOrNull()?.facts?.stage != OitStage.ACCUMULATE) return null
        return OitSampledView(current.retainView(view), sampler)
    }

    fun recordDraw(contribution: () -> Unit) {
        val current = recording ?: return
        val draw = current.draw
        if (draw == null) {
            current.failed = true
            return
        }
        if (draw.disposition != OitDrawDisposition.EMPTY) draw.contributions.add(contribution)
    }

    internal fun <Result> recordDrawCall(pass: Any, disposition: OitDrawDisposition,
        selection: () -> OitDrawSelection,
        action: () -> Result): Result {
        val current = recording ?: return action()
        if (!current.passes.containsKey(pass)) return action()
        val previous = current.draw
        val sourceDrawId = if (disposition == OitDrawDisposition.EMPTY) null else {
            current.nextSourceDrawId = Math.incrementExact(current.nextSourceDrawId)
            current.nextSourceDrawId
        }
        val draw = DrawRecording(disposition, selection(), sourceDrawId, previous?.sourceDrawId)
        val terrainScope = current.scopes.lastOrNull()?.facts?.takeIf {
            it.producer.kind == OitProducerKind.TERRAIN && it.stage == OitStage.ACCUMULATE
        }
        val receiptOwner = if (terrainScope != null && disposition != OitDrawDisposition.EMPTY)
            OitReconstructionInputs.terrainRecordFrames() else null
        current.draw = draw
        try {
            val preceding = try {
                receiptOwner?.queryNativeDrawFrontier()
            } catch (failure: Throwable) {
                current.failed = true
                current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.NATIVE_RECEIPT,
                    draw.sourceDrawId, current.passes[pass]?.descriptor?.passId, terrainScope?.serial))
                previous?.nativeReceiptFailure = failure
                throw failure
            }
            val result = try {
                val operationOwner = OitReconstructionInputs.terrainRecordFrames()?.takeIf {
                    disposition != OitDrawDisposition.EMPTY &&
                        current.scopes.lastOrNull()?.facts?.producer?.kind != OitProducerKind.TERRAIN
                }
                if (operationOwner != null) {
                    val passFacts = checkNotNull(current.passes[pass])
                    val scope = current.scopes.lastOrNull()?.facts
                    val kind = current.executionKind ?: when (passFacts.providerStage) {
                        OitStage.DEPTH_BOUNDS -> OitExecutionEventKind.DEPTH_BOUNDS
                        OitStage.TRANSMITTANCE -> OitExecutionEventKind.TRANSMITTANCE
                        OitStage.ACCUMULATE -> OitExecutionEventKind.ACCUMULATE
                        null -> OitExecutionEventKind.UNCLASSIFIED_DRAW
                    }
                    val nativeKind = when (kind) {
                        OitExecutionEventKind.DEPTH_BOUNDS, OitExecutionEventKind.TRANSMITTANCE,
                        OitExecutionEventKind.ACCUMULATE -> 1
                        OitExecutionEventKind.WATER_DEPTH_COPY, OitExecutionEventKind.TERRAIN_DEPTH_COPY -> 7
                        OitExecutionEventKind.CLOUD_DEPTH_COPY -> 13
                        OitExecutionEventKind.DEPTH_BOUNDS_COPY -> 8
                        OitExecutionEventKind.DEPTH_BOUNDS_CULL -> 9
                        OitExecutionEventKind.WATER_MASK -> 12
                        OitExecutionEventKind.COMPOSITE, OitExecutionEventKind.UNCLASSIFIED_DRAW -> 14
                    }
                    val stage = when (kind) {
                        OitExecutionEventKind.WATER_DEPTH_COPY, OitExecutionEventKind.WATER_MASK -> 4
                        OitExecutionEventKind.CLOUD_DEPTH_COPY -> 5
                        OitExecutionEventKind.COMPOSITE -> 6
                        else -> when (scope?.stage ?: passFacts.providerStage) {
                            OitStage.DEPTH_BOUNDS -> 1
                            OitStage.TRANSMITTANCE -> 2
                            OitStage.ACCUMULATE -> 3
                            null -> 0
                        }
                    }
                    val facts = OitNativeOperationReceipts.OperationFacts(nativeKind, 0,
                        operationOwner.recordingSerial, current.facts.identity, scope?.producerId ?: 0L,
                        scope?.serial ?: 0L, passFacts.descriptor.passId, checkNotNull(sourceDrawId),
                        draw.parentSourceDrawId, scope?.producer?.kind?.ordinal ?: -1, stage,
                        operationOwner.programGeneration, 0L, 0L)
                    operationOwner.recordExecutionOperation(facts, action).let { (value, receipts) ->
                        draw.operationReceipts = receipts
                        value
                    }
                } else action()
            } catch (failure: Throwable) {
                current.failed = true
                if (draw.nativeReceiptFailure === failure) previous?.nativeReceiptFailure = failure
                else if (draw.terrainStageFailure === failure) previous?.terrainStageFailure = failure
                else current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.DRAW, draw.sourceDrawId,
                    current.passes[pass]?.descriptor?.passId, current.scopes.lastOrNull()?.facts?.serial))
                throw failure
            }
            val passFacts = current.passes[pass]
            if (passFacts != null && disposition != OitDrawDisposition.EMPTY) {
                current.scopes.lastOrNull()?.let { it.actualDrawCalls = Math.incrementExact(it.actualDrawCalls) }
                val location = current.pipelineLocations[pass]
                val kind = current.executionKind ?: when (passFacts.providerStage) {
                    OitStage.DEPTH_BOUNDS -> OitExecutionEventKind.DEPTH_BOUNDS
                    OitStage.TRANSMITTANCE -> OitExecutionEventKind.TRANSMITTANCE
                    OitStage.ACCUMULATE -> OitExecutionEventKind.ACCUMULATE
                    null -> OitExecutionEventKind.UNCLASSIFIED_DRAW
                }
                val nativeReceipts = try {
                    if (preceding is OitNativeReceiptFrontier.Available) {
                        val queried = checkNotNull(receiptOwner).queryNativeDrawReceipts(preceding.sequence)
                        if (queried is OitNativeReceiptQuery.Available) {
                            val unassigned = queried.receipts.filterNot {
                                it.receiptId in current.assignedTerrainReceiptIds
                            }
                            check(unassigned.all { receipt ->
                                receipt.producerScopeSerial == terrainScope?.serial &&
                                    receipt.producerKind == OitProducerKind.TERRAIN
                            }) { "Terrain native receipts belong to another producer invocation" }
                            OitNativeReceiptQuery.Available(unassigned)
                        } else queried
                    } else if (preceding == OitNativeReceiptFrontier.EntryUnavailable) {
                        OitNativeReceiptQuery.EntryUnavailable
                    } else OitNativeReceiptQuery.NotQueried
                } catch (failure: Throwable) {
                    current.failed = true
                    current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.NATIVE_RECEIPT,
                        draw.sourceDrawId, passFacts.descriptor.passId, terrainScope?.serial))
                    current.recordExecution(pass, passFacts, draw, kind, location, OitNativeReceiptQuery.Failed)
                    previous?.nativeReceiptFailure = failure
                    throw failure
                }
                current.recordExecution(pass, passFacts, draw, kind, location, nativeReceipts)
                if (nativeReceipts is OitNativeReceiptQuery.Available) {
                    current.assignedTerrainReceiptIds.addAll(nativeReceipts.receipts.map { it.receiptId })
                }
            }
            try {
                if (previous != null) previous.contributions.addAll(draw.contributions)
                else for (contribution in draw.contributions) {
                    contribution()
                    current.scopes.lastOrNull()?.let { it.drawCalls = Math.incrementExact(it.drawCalls) }
                }
            } catch (failure: Throwable) {
                current.failed = true
                current.recordFailure(OitExecutionFailure(OitExecutionFailureKind.HISTORY_CONTRIBUTION,
                    draw.sourceDrawId, current.passes[pass]?.descriptor?.passId,
                    current.scopes.lastOrNull()?.facts?.serial))
                throw failure
            }
            return result
        } finally {
            current.draw = previous
        }
    }

    fun frameResult(): OitFrameResult? = recording?.let { current ->
        OitFrameResult(current.facts, current.results,
            current.failed || current.scopes.isNotEmpty() || current.activePass != null)
    }

    fun executionResult(recordingSerial: Long): OitExecutionRecording? = recording?.let { current ->
        val scopes = current.executionScopes + current.scopes.map {
            OitExecutionScope(it.facts, it.actualDrawCalls, OitExecutionScopeCompletion.ACTIVE, null)
        }
        OitExecutionRecording(current.facts, recordingSerial, current.producers, scopes.sortedBy { it.scope.serial },
            current.executionPasses.values.toList(), current.executionEvents, current.failures,
            setOf(OitNativeExecutionGap.DRAW_RECEIPT_UNAVAILABLE,
                OitNativeExecutionGap.FROZEN_CONTENT_LEASE_UNAVAILABLE,
                OitNativeExecutionGap.CROSS_STAGE_DRAW_SELECTION_UNAVAILABLE,
                OitNativeExecutionGap.ATTACHMENT_STORE_UNKNOWN,
                OitNativeExecutionGap.NATIVE_COMMAND_GRANULARITY_UNAVAILABLE,
                OitNativeExecutionGap.BULK_DRAW_SELECTION_UNAVAILABLE), current.failed, current.journalEntries)
    }

    fun retireFrame() {
        val current = recording ?: return
        recording = null
        var failure: Throwable? = null
        for (texture in current.textures) {
            try { texture.removeViews() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}
