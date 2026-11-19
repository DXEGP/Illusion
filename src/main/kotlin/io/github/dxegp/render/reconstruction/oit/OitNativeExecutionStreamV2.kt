package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.GpuFormat
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.allocateFfmArray
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import java.lang.foreign.Arena
import java.lang.foreign.GroupLayout
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import net.minecraft.client.renderer.oit.OitStage

internal class OitNativeExecutionStreamV2(
    private val identity: VanillaFrameIdentity,
    private val historyGeneration: Long,
    private val frameSerial: Long,
    private val programGeneration: Long,
    private val frameHandle: Long,
) {
    private data class ScopeCounts(val scope: OitProducerScope, var events: Int = 0,
        var receipts: Int = 0, var flags: Int = 0)
    private data class PassCounts(val passId: Long, var events: Int = 0, var completion: Int = 0)
    private data class TerrainReceipt(val facts: OitTerrainStageFacts, val receipt: OitNativeTerrainStageReceipt,
        val shaderGeneration: Long, val readSetRegistered: Boolean, var commandOrdinal: Int = 0)
    private enum class TextureRole(val code: Int, val format: GpuFormat) {
        MAIN_DEPTH(3, GpuFormat.D32_FLOAT),
        TERRAIN_DEPTH(4, GpuFormat.D32_FLOAT),
        CLOUD_DEPTH(5, GpuFormat.D32_FLOAT),
        BOUNDS(6, GpuFormat.RGBA32_FLOAT),
        COEFFICIENT(7, GpuFormat.RGBA16_FLOAT),
        Q(8, GpuFormat.RGBA16_FLOAT),
        CULLED_BOUNDS(9, GpuFormat.RGBA32_FLOAT),
    }
    private enum class TextureAccess(val code: Int) { READ(1), WRITE(2), READ_WRITE(3) }
    private data class TextureSubresource(val texture: Long, val mip: Int)
    private data class TextureBinding(val role: TextureRole, val access: TextureAccess,
        val subresource: TextureSubresource, val roleIndex: Int = 0)

    private data class Event(
        val kind: Int,
        val scope: OitProducerScope? = null,
        val passId: Long = 0,
        val sourceDrawId: Long = 0,
        val parentSourceDrawId: Long = 0,
        val stage: Int = 0,
        val flags: Int = 0,
        val drawGroupId: Long = 0,
        val terrain: TerrainReceipt? = null,
        val operation: OitNativeOperationReceipt? = null,
        val operationBindings: List<TextureBinding> = emptyList(),
        val normalization: OitCompositeNormalizationInput? = null,
    )

    private var handle = 0L
    private var sequence = 0L
    private var resourceCount = 0L
    private var missingMask = 0
    private val scopes = LinkedHashMap<Long, ScopeCounts>()
    private val passes = LinkedHashMap<Long, PassCounts>()
    private val consumedReceipts = HashSet<Long>()
    private val consumedOperations = HashMap<Long, OitNativeOperationReceipt>()
    private var lastReceiptSequence = 0L

    fun seal(context: Long, recording: OitExecutionRecording?,
        compositeInput: OitCompositeInputCapture): OitNativeExecutionResult {
        if (!MCDR_ABI.hasOitExecutionStreamV2()) return OitNativeExecutionResult.Unavailable(OitNativeExecutionUnavailable.ENTRY_UNAVAILABLE)
        if (recording == null) return OitNativeExecutionResult.Unavailable(OitNativeExecutionUnavailable.MANIFEST_UNAVAILABLE)
        check(recording.frame.identity == identity && recording.frame.historyGeneration == historyGeneration)
        val normalization = when (compositeInput) {
            is OitCompositeInputCapture.Available -> compositeInput.input.also {
                check(it.frame.identity == identity && it.frame.historyGeneration == historyGeneration &&
                    it.recordingSerial == frameSerial && it.programGeneration == programGeneration && it.isCurrent()) {
                    "Composite normalization input is stale"
                }
            }
            is OitCompositeInputCapture.Unavailable -> {
                missingMask = missingMask or NORMALIZATION
                if (compositeInput.contentFailure != null) missingMask = missingMask or CONTENT
                null
            }
        }
        var maximumEvents = recording.journalEntries.size.toLong()
        var maximumReceipts = 0L
        var maximumResources = normalization?.inputCount?.toLong() ?: 0L
        var resourcesPerEvent = normalization?.inputCount ?: 0
        val laneTextures = recording.events.asSequence()
            .filter { event ->
                event.kind.nativeKind in laneTextureSources &&
                    (event.nativeOperationReceipts as? OitNativeOperationReceiptQuery.Available)?.receipts?.any {
                        it.recordState == OitNativeOperationReceipts.State.RECORDED
                    } == true
            }
            .flatMap { operationTextureBindings(it, emptyMap()).orEmpty().asSequence() }
            .groupBy(TextureBinding::role, TextureBinding::subresource)
            .mapValues { (_, subresources) -> subresources.distinct() }
        val operationBindings = HashMap<OitExecutionEvent, List<TextureBinding>>()
        for (entry in recording.journalEntries) {
            val fact = entry.fact
            if (fact is OitExecutionJournalFact.Observation) {
                val event = fact.event
                val operationCount = (event.nativeOperationReceipts as? OitNativeOperationReceiptQuery.Available)
                    ?.receipts?.size ?: 0
                val terrainCount = event.terrainPreparedFacts.size.toLong()
                maximumReceipts += terrainCount
                maximumEvents += terrainCount + operationCount
                val drawCount = if (terrainCount > 0) terrainCount else operationCount.toLong()
                if (drawCount > 0) {
                    operationTextureBindings(event, laneTextures)?.let { bindings ->
                        operationBindings[event] = bindings
                        maximumResources += bindings.size * drawCount
                        resourcesPerEvent = maxOf(resourcesPerEvent, bindings.size)
                    }
                }
                if (maximumEvents > Int.MAX_VALUE || maximumReceipts > Int.MAX_VALUE ||
                    maximumResources > Int.MAX_VALUE) {
                    return OitNativeExecutionResult.Unavailable(OitNativeExecutionUnavailable.COUNT_OVERFLOW)
                }
            }
        }
        if (normalization != null) maximumEvents++
        if (maximumEvents > Int.MAX_VALUE || maximumReceipts > Int.MAX_VALUE) {
            return OitNativeExecutionResult.Unavailable(OitNativeExecutionUnavailable.COUNT_OVERFLOW)
        }
        return Arena.ofConfined().use { arena ->
            val begin = Struct.allocate(arena, ExecutionLayouts.begin)
            writeIdentity(begin)
            begin.setLong("frameSerial", frameSerial)
            begin.setLong("historyGeneration", historyGeneration)
            begin.setLong("programGeneration", programGeneration)
            begin.setInt("maxEvents", maximumEvents.toInt().coerceAtLeast(1))
            begin.setInt("maxScopes", recording.journalEntries.count { it.fact is OitExecutionJournalFact.ScopeBegin }.coerceAtLeast(1))
            begin.setInt("maxPasses", recording.journalEntries.count { it.fact is OitExecutionJournalFact.PassBegin }.coerceAtLeast(1))
            begin.setInt("maxResources", maximumResources.toInt().coerceAtLeast(1))
            begin.setInt("maxReceipts", maximumReceipts.toInt().coerceAtLeast(1))
            val output = arena.allocate(MCDRLayout.PTR)
            val status = MCDR_ABI.D3D12OitBeginExecutionStreamV2(context, frameHandle, begin.segment, output)
            if (status == -2 || status == -3) {
                check(output.get(MCDRLayout.PTR, 0).address() == 0L)
                return@use OitNativeExecutionResult.Unavailable(if (status == -3)
                    OitNativeExecutionUnavailable.OVER_BUDGET else OitNativeExecutionUnavailable.UNSUPPORTED)
            }
            requireSuccess(status, "begin execution stream V2")
            handle = output.get(MCDRLayout.PTR, 0).address()
            check(handle != 0L)
            try {
                val memory = ExecutionMemory(arena, resourcesPerEvent, normalization?.inputCount ?: 0)
                val initial = queryInfo(arena, context)
                val receipts = queryTerrainReceipts(memory, context, recording)
                val completeReceipts = initial.getUnsigned("receiptCount") == receipts.size.toLong()
                if (!completeReceipts) missingMask = missingMask or RECEIPT
                val ordinals = HashMap<Long, Int>()
                for (receipt in receipts.values.sortedBy { it.receipt.nativeSequence }) {
                    receipt.commandOrdinal = ordinals.getOrDefault(receipt.receipt.sourceDrawId, 0)
                    ordinals[receipt.receipt.sourceDrawId] = Math.incrementExact(receipt.commandOrdinal)
                }
                for (entry in recording.journalEntries) {
                    when (val fact = entry.fact) {
                        is OitExecutionJournalFact.ScopeBegin -> append(memory, context, Event(SCOPE_BEGIN, fact.scope))
                        is OitExecutionJournalFact.ScopeEnd -> {
                            val flags = when (fact.scope.completion) {
                                OitExecutionScopeCompletion.EMPTY -> 1
                                OitExecutionScopeCompletion.FAILED -> 2
                                OitExecutionScopeCompletion.RETURNED -> 0
                                OitExecutionScopeCompletion.ACTIVE -> continue
                            }
                            append(memory, context, Event(SCOPE_END, fact.scope.scope, flags = flags))
                        }
                        is OitExecutionJournalFact.PassBegin -> append(memory, context,
                            Event(PASS_BEGIN, passId = fact.descriptor.passId))
                        is OitExecutionJournalFact.PassEnd -> {
                            if (fact.pass.completion == OitExecutionPassCompletion.ACTIVE) continue
                            append(memory, context, Event(PASS_END, passId = fact.pass.descriptor.passId,
                                flags = if (fact.pass.completion == OitExecutionPassCompletion.FAILED) 2 else 0))
                        }
                        is OitExecutionJournalFact.Observation -> appendObservation(memory, context, fact.event, receipts,
                            completeReceipts, operationBindings[fact.event])
                        is OitExecutionJournalFact.Failure -> missingMask = missingMask or FAILED
                    }
                }
                if (normalization != null) {
                    append(memory, context, Event(NORMALIZATION_EVENT, stage = 6, normalization = normalization))
                }
                val info = queryInfo(arena, context)
                check(info.getUnsigned("eventCount") == sequence && info.getUnsigned("scopeCount") == scopes.size.toLong() &&
                    info.getUnsigned("passCount") == passes.size.toLong() && info.getUnsigned("resourceCount") == resourceCount &&
                    info.getUnsigned("consumedReceiptCount") == consumedReceipts.size.toLong())
                val manifest = writeManifest(arena, recording)
                val sealed = Struct.allocate(arena, ExecutionLayouts.sealResult)
                output.set(MCDRLayout.PTR, 0, MemorySegment.NULL)
                val sealStatus = MCDR_ABI.D3D12OitSealExecutionStreamV2(context, handle, manifest.segment, output, sealed.segment)
                if (sealStatus != -2) requireSuccess(sealStatus, "seal execution stream V2")
                sealed.requireHeader()
                check(sealed.getInt("reserved0") == 0 && sealed.getInt("reserved1") == 0)
                val nativeInfo = OitNativeExecutionInfo(info.getLong("streamId"), info.getUnsigned("receiptCount"),
                    info.getUnsigned("consumedReceiptCount"), sequence, info.getUnsigned("openScopeCount"),
                    recording.events.size, recording.scopes.size, info.getUnsigned("resourceCount"))
                val reportedMask = sealed.getInt("missingMask")
                val sealedHandle = output.get(MCDRLayout.PTR, 0).address()
                if (sealStatus == 1) {
                    check(sealedHandle == handle && sealedHandle != 0L && sealed.getInt("state") == 5 &&
                        reportedMask == 0 && missingMask == 0)
                    return@use OitNativeExecutionResult.Sealed(sealedHandle, nativeInfo)
                }
                check(sealedHandle == 0L && sealed.getInt("state") == 2)
                val reason = when {
                    compositeInput is OitCompositeInputCapture.Unavailable &&
                        compositeInput.contentFailure?.failure == OitRecordFailure.OVER_BUDGET -> OitNativeExecutionUnavailable.OVER_BUDGET
                    compositeInput is OitCompositeInputCapture.Unavailable &&
                        compositeInput.contentFailure != null -> OitNativeExecutionUnavailable.UNSUPPORTED_CONTENT
                    reportedMask and RECEIPT != 0 -> OitNativeExecutionUnavailable.MISSING_DRAW_RECEIPT
                    reportedMask and OPEN_SCOPE != 0 -> OitNativeExecutionUnavailable.OPEN_SCOPE
                    reportedMask and (MANIFEST or OPEN_PASS) != 0 -> OitNativeExecutionUnavailable.INCOMPLETE_MANIFEST
                    else -> OitNativeExecutionUnavailable.MISSING_EXECUTION_ADAPTERS
                }
                OitNativeExecutionResult.Unavailable(reason,
                    nativeInfo, sealed.getLong("firstEventId"), sealed.getLong("receiptId"),
                    sealed.getUnsigned("expected"), sealed.getUnsigned("observed"), reportedMask,
                    sealed.getLong("firstIssueSequence"))
            } catch (failure: Throwable) {
                if (failure !is OitRecordException || failure.failure != OitRecordFailure.UNKNOWN_SUBMISSION) {
                    try { requireSuccess(MCDR_ABI.D3D12OitAbortExecutionStreamV2(context, handle), "abort execution stream V2") }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }

    private fun operationTextureBindings(event: OitExecutionEvent,
        laneTextures: Map<TextureRole, List<TextureSubresource>>): List<TextureBinding>? {
        if (event.resourceVersion != identity.resourceVersion) return null
        val evidence = event.resourceEvidence
        return when (event.kind) {
            OitExecutionEventKind.WATER_DEPTH_COPY, OitExecutionEventKind.TERRAIN_DEPTH_COPY,
            OitExecutionEventKind.CLOUD_DEPTH_COPY -> {
                val copy = evidence as? OitExecutionResourceEvidence.DepthCopy ?: return null
                val destination = if (event.kind == OitExecutionEventKind.CLOUD_DEPTH_COPY)
                    TextureRole.CLOUD_DEPTH else TextureRole.TERRAIN_DEPTH
                listOf(
                    textureBinding(TextureRole.MAIN_DEPTH, TextureAccess.READ, copy.source.view) ?: return null,
                    textureBinding(destination, TextureAccess.WRITE, copy.destination) ?: return null,
                )
            }
            OitExecutionEventKind.DEPTH_BOUNDS_COPY -> {
                val copy = evidence as? OitExecutionResourceEvidence.ColorCopy ?: return null
                listOf(
                    textureBinding(TextureRole.BOUNDS, TextureAccess.READ, copy.source.view) ?: return null,
                    textureBinding(TextureRole.CULLED_BOUNDS, TextureAccess.WRITE, copy.destination) ?: return null,
                )
            }
            OitExecutionEventKind.DEPTH_BOUNDS_CULL -> {
                val pass = evidence as? OitExecutionResourceEvidence.PassResources ?: return null
                listOf(
                    textureBinding(TextureRole.BOUNDS, TextureAccess.READ, pass.sampledViews["DepthBoundsSampler"]?.view)
                        ?: return null,
                    textureBinding(TextureRole.CULLED_BOUNDS, TextureAccess.WRITE, pass.pass.colorTargets.singleOrNull())
                        ?: return null,
                    textureBinding(TextureRole.MAIN_DEPTH, TextureAccess.READ_WRITE, pass.pass.depthTarget) ?: return null,
                )
            }
            OitExecutionEventKind.WATER_MASK -> {
                val pass = evidence as? OitExecutionResourceEvidence.PassResources ?: return null
                listOf(textureBinding(TextureRole.TERRAIN_DEPTH, TextureAccess.READ_WRITE, pass.pass.depthTarget)
                    ?: return null)
            }
            OitExecutionEventKind.DEPTH_BOUNDS, OitExecutionEventKind.TRANSMITTANCE, OitExecutionEventKind.ACCUMULATE -> {
                val pass = evidence as? OitExecutionResourceEvidence.PassResources ?: return null
                stageTextureBindings(event.kind, pass, laneTextures)
            }
            else -> null
        }
    }

    private fun stageTextureBindings(kind: OitExecutionEventKind, evidence: OitExecutionResourceEvidence.PassResources,
        laneTextures: Map<TextureRole, List<TextureSubresource>>): List<TextureBinding>? {
        val pass = evidence.pass
        val depth = textureSubresource(pass.depthTarget, GpuFormat.D32_FLOAT) ?: return null
        val depthRole = depthRoles.singleOrNull { laneTextures[it]?.singleOrNull() == depth } ?: return null
        val depthBinding = TextureBinding(depthRole, TextureAccess.READ, depth)
        if (kind == OitExecutionEventKind.DEPTH_BOUNDS) {
            val bounds = textureSubresource(pass.colorTargets.singleOrNull(), GpuFormat.RGBA32_FLOAT) ?: return null
            val boundsRole = boundsRoles.singleOrNull { laneTextures[it]?.singleOrNull() == bounds } ?: return null
            return listOf(depthBinding, TextureBinding(boundsRole, TextureAccess.READ_WRITE, bounds))
        }
        val boundsBinding = textureBinding(TextureRole.CULLED_BOUNDS, TextureAccess.READ,
            evidence.sampledViews["DepthBoundsSampler"]?.view) ?: return null
        if (boundsBinding.subresource != laneTextures[TextureRole.CULLED_BOUNDS]?.singleOrNull()) return null
        if (kind == OitExecutionEventKind.TRANSMITTANCE) {
            if (pass.colorTargets.size !in coefficientCounts) return null
            return buildList {
                add(depthBinding)
                add(boundsBinding)
                for ((index, view) in pass.colorTargets.withIndex())
                    add(textureBinding(TextureRole.COEFFICIENT, TextureAccess.READ_WRITE, view, index) ?: return null)
            }
        }
        val coefficients = evidence.sampledViews.keys.filter { it.startsWith("Coeff") }.toSet()
        if (coefficients.size !in coefficientCounts || coefficients != (0 until coefficients.size).mapTo(HashSet()) { "Coeff$it" })
            return null
        val q = textureBinding(TextureRole.Q, TextureAccess.READ_WRITE, pass.colorTargets.singleOrNull()) ?: return null
        return buildList {
            add(depthBinding)
            add(boundsBinding)
            for (index in 0 until coefficients.size)
                add(textureBinding(TextureRole.COEFFICIENT, TextureAccess.READ, evidence.sampledViews["Coeff$index"]?.view, index)
                    ?: return null)
            add(q)
        }
    }

    private fun textureBinding(role: TextureRole, access: TextureAccess, view: OitTextureViewFacts?,
        roleIndex: Int = 0): TextureBinding? {
        if (access == TextureAccess.READ && view?.mipLevels != 1) return null
        val subresource = textureSubresource(view, role.format) ?: return null
        return TextureBinding(role, access, subresource, roleIndex)
    }

    private fun textureSubresource(view: OitTextureViewFacts?, format: GpuFormat): TextureSubresource? {
        if (view == null || view.source !is D3D12GpuTextureView) return null
        val texture = view.texture as? D3D12GpuTexture ?: return null
        if (texture.depthOrLayers != 1 || view.format != format || view.baseMipLevel !in 0 until texture.mipLevels)
            return null
        return TextureSubresource(texture.handle(), view.baseMipLevel)
    }

    private fun appendObservation(memory: ExecutionMemory, context: Long, event: OitExecutionEvent,
        receipts: Map<Long, TerrainReceipt>, completeReceipts: Boolean, operationBindings: List<TextureBinding>?) {
        if (event.nativeDrawReceipts == OitNativeReceiptQuery.Failed) {
            missingMask = missingMask or FAILED or RECEIPT
            return
        }
        if (event.terrainPreparedFacts.isNotEmpty()) {
            val selected = event.terrainPreparedFacts.mapNotNull {
                (it.nativeReceipt as? OitNativeTerrainStageReceiptResult.Available)?.receipt?.receiptId?.let(receipts::get)
            }.sortedBy { it.receipt.nativeSequence }
            if (!completeReceipts || selected.size != event.terrainPreparedFacts.size) {
                missingMask = missingMask or RECEIPT
                return
            }
            if (operationBindings == null) missingMask = missingMask or ADAPTER
            for (selectedReceipt in selected) {
                val receipt = selectedReceipt.receipt
                if (receipt.receiptId in consumedReceipts) continue
                if (receipt.sourceDrawId != event.sourceDrawId || receipt.parentSourceDrawId != event.parentSourceDrawId ||
                    receipt.passId != event.pass?.descriptor?.passId || event.stage != receipt.stage ||
                    event.scope !== selectedReceipt.facts.invocation.scope ||
                    receipt.nativeSequence <= lastReceiptSequence) {
                    missingMask = missingMask or RECEIPT or SELECTION
                    continue
                }
                append(memory, context, Event(DRAW, selectedReceipt.facts.invocation.scope, receipt.passId,
                    receipt.sourceDrawId, receipt.parentSourceDrawId ?: 0L,
                    drawGroupId = (selectedReceipt.facts.drawGroup as? OitDrawGroupEvidence.TerrainSelection)?.groupId ?: 0L,
                    terrain = selectedReceipt, operationBindings = operationBindings.orEmpty()))
            }
            return
        }
        if (event.scope?.producer?.kind == OitProducerKind.TERRAIN) missingMask = missingMask or RECEIPT
        val kind = event.kind.nativeKind
        val domainGap = when {
            kind == COPY_DEPTH || kind == WATER_MASK -> WATER
            kind == CLOUD_DEPTH || event.scope?.producer?.kind == OitProducerKind.CLOUD -> CLOUD
            else -> 0
        }
        val passId = event.pass?.descriptor?.passId
        if (passId == null) {
            missingMask = missingMask or MANIFEST or ADAPTER or domainGap
            return
        }
        val stage = when (event.kind) {
            OitExecutionEventKind.WATER_DEPTH_COPY, OitExecutionEventKind.WATER_MASK -> 4
            OitExecutionEventKind.CLOUD_DEPTH_COPY -> 5
            OitExecutionEventKind.COMPOSITE -> 6
            else -> stageCode(event.scope?.stage ?: event.stage)
        }
        val needsBindings = kind != UNCLASSIFIED_DRAW
        when (val operations = event.nativeOperationReceipts) {
            is OitNativeOperationReceiptQuery.Available -> {
                if (needsBindings && operations.receipts.isEmpty()) missingMask = missingMask or RECEIPT or domainGap
                for (receipt in operations.receipts) {
                    val scope = event.scope
                    if (receipt.identity != identity || receipt.frameSerial != frameSerial ||
                        receipt.programGeneration != programGeneration || receipt.resourceVersion != identity.resourceVersion ||
                        receipt.sourceDrawId != event.sourceDrawId || receipt.parentSourceDrawId != event.parentSourceDrawId ||
                        receipt.sourcePassId != passId || receipt.producerId != (scope?.producerId ?: 0L) ||
                        receipt.producerScopeSerial != (scope?.serial ?: 0L) ||
                        receipt.producerKind != (scope?.producer?.kind?.let(::producerCode) ?: -1) ||
                        receipt.stage != stage || receipt.kind != kind) {
                        missingMask = missingMask or RECEIPT or SELECTION or domainGap
                        continue
                    }
                    val previous = consumedOperations[receipt.receiptId]
                    check(previous == null || previous == receipt) { "Operation receipt identity changed" }
                    if (previous != null) continue
                    if (needsBindings && operationBindings == null && receipt.recordState == OitNativeOperationReceipts.State.RECORDED)
                        missingMask = missingMask or ADAPTER or domainGap
                    append(memory, context, Event(receipt.kind, scope, passId, receipt.sourceDrawId,
                        receipt.parentSourceDrawId ?: 0L, receipt.stage, operation = receipt,
                        operationBindings = operationBindings.orEmpty()))
                }
                return
            }
            OitNativeOperationReceiptQuery.Failed -> {
                missingMask = missingMask or FAILED or RECEIPT or domainGap
                return
            }
            OitNativeOperationReceiptQuery.EntryUnavailable, OitNativeOperationReceiptQuery.NotQueried -> Unit
        }
        if (needsBindings) missingMask = missingMask or RECEIPT or domainGap
        // Native reserves unbacked terrain DRAW for events with actual terrain receipts.
        val observationKind = if (kind == DRAW) UNCLASSIFIED_DRAW else kind
        append(memory, context, Event(observationKind, event.scope, passId, event.sourceDrawId,
            event.parentSourceDrawId ?: 0L, stage))
    }

    private fun append(memory: ExecutionMemory, context: Long, event: Event) {
        val input = memory.event
        input.segment.fill(0)
        input.writeHeader()
        input.setInt("kind", event.kind)
        input.setInt("flags", event.flags)
        input.setLong("sequence", sequence + 1)
        input.setLong("programGeneration", programGeneration)
        val key = input.child("key", ExecutionLayouts.key)
        key.setInt("producerKind", event.scope?.producer?.kind?.let(::producerCode) ?: -1)
        key.setInt("stage", event.scope?.stage?.let(::stageCode) ?: event.stage)
        key.setLong("producerId", event.scope?.producerId ?: 0L)
        key.setLong("producerScopeSerial", event.scope?.serial ?: 0L)
        key.setLong("sourcePassId", event.passId)
        key.setLong("sourceDrawId", event.sourceDrawId)
        key.setLong("parentSourceDrawId", event.parentSourceDrawId)
        event.terrain?.let {
            key.setInt("commandOrdinal", it.commandOrdinal)
            input.setLong("drawGroupId", event.drawGroupId)
            input.setLong("pipelineIdentity", it.receipt.pipelineIdentity)
            input.setLong("shaderGeneration", it.shaderGeneration)
            input.setInt("receiptCount", 1)
            memory.receiptId.set(MCDRLayout.I64, 0, it.receipt.receiptId)
            input.setPointer("receiptIds", memory.receiptId)
        }
        event.operation?.let {
            key.setInt("producerKind", it.producerKind)
            key.setInt("stage", it.stage)
            key.setInt("commandOrdinal", it.commandOrdinal)
            input.setLong("pipelineIdentity", it.pipelineIdentity)
            input.setLong("shaderGeneration", it.shaderGeneration)
            input.setLong("programGeneration", it.programGeneration)
            OitNativeOperationReceipts.writeReceipt(memory.operationReceipt, it)
        }
        event.operationBindings.forEachIndexed { index, binding ->
            val resource = memory.resourceRow(index)
            resource.setInt("role", binding.role.code)
            resource.setInt("roleIndex", binding.roleIndex)
            resource.setInt("access", binding.access.code)
            resource.setInt("contentRequirement", OBJECT_ONLY)
            resource.setPointer("object", MemorySegment.ofAddress(binding.subresource.texture))
            resource.setLong("resourceIdentity", binding.subresource.texture)
            resource.setLong("resourceVersion", identity.resourceVersion)
            resource.setInt("firstSubresource", binding.subresource.mip)
            resource.setInt("subresourceCount", 1)
        }
        event.normalization?.let { value ->
            val payload = checkNotNull(memory.normalization)
            payload.segment.fill(0)
            payload.writeHeader(2)
            payload.setInt("targetCount", value.coefficients.size)
            payload.setInt("certificationVersion", value.certification.certificationVersion)
            payload.setLong("certificationGeneration", value.certification.generation)
            payload.setPointer("pipeline", MemorySegment.ofAddress(value.pipelineHandle))
            payload.setPointer("sampler", MemorySegment.ofAddress(value.samplerHandle))
            payload.setBytes("fragmentSourceHash", value.certification.fragmentSourceHashBytes())
            payload.setBytes("vertexSourceHash", value.certification.vertexSourceHashBytes())
            payload.setBytes("contractHash", value.certification.contractHashBytes())
            payload.setInt("outputWidth", identity.width)
            payload.setInt("outputHeight", identity.height)
            value.forEachTexture { index, texture ->
                val resource = memory.resourceRow(index)
                resource.setInt("role", when (index) {
                    0 -> 1
                    1 -> TextureRole.MAIN_DEPTH.code
                    2 -> TextureRole.Q.code
                    3 -> TextureRole.BOUNDS.code
                    else -> TextureRole.COEFFICIENT.code
                })
                resource.setInt("roleIndex", if (index >= 4) index - 4 else 0)
                resource.setInt("access", TextureAccess.READ.code)
                resource.setInt("contentRequirement", OBJECT_ONLY)
                resource.setPointer("object", MemorySegment.ofAddress(texture.textureHandle))
                resource.setLong("resourceIdentity", texture.textureHandle)
                resource.setLong("resourceVersion", identity.resourceVersion)
                resource.setInt("firstSubresource", texture.baseMip)
                resource.setInt("subresourceCount", 1)
            }
            input.setInt("payloadKind", 2)
            input.setInt("payloadBytes", Math.toIntExact(ExecutionLayouts.normalization.memory.byteSize()))
            input.setPointer("payload", payload.segment)
        }
        val eventResources = event.normalization?.inputCount ?: event.operationBindings.size
        if (eventResources != 0) {
            input.setInt("resourceCount", eventResources)
            input.setPointer("resources", checkNotNull(memory.resources))
        }
        val output = memory.eventId
        if (event.operation != null) {
            val result = MCDR_ABI.D3D12OitAppendExecutionOperationEventV1(context, handle,
                input.segment, memory.operationReceipt, output)
            if (result != 1) requireSuccess(result,
                "append operation ${event.operation.kind}/${event.operation.stage} " +
                    "(receipt ${event.operation.receiptId}, pass ${event.operation.sourcePassId}, " +
                    "scope ${event.operation.producerScopeSerial})")
        } else {
            requireSuccess(MCDR_ABI.D3D12OitAppendExecutionEventV2(context, handle, input.segment, output), "append execution event V2")
        }
        check(output.get(MCDRLayout.I64, 0) == sequence + 1)
        sequence++
        resourceCount += eventResources.toLong()
        if (event.kind == SCOPE_BEGIN) {
            val scope = checkNotNull(event.scope)
            scopes[scope.serial] = ScopeCounts(scope)
        }
        if (event.kind == PASS_BEGIN) passes[event.passId] = PassCounts(event.passId)
        event.scope?.let {
            val count = scopes.getValue(it.serial)
            count.events = Math.incrementExact(count.events)
            if (event.terrain != null) count.receipts = Math.incrementExact(count.receipts)
            if (event.kind == SCOPE_END) count.flags = event.flags
        }
        if (event.passId != 0L) {
            val count = passes.getValue(event.passId)
            count.events = Math.incrementExact(count.events)
            if (event.kind == PASS_END) count.completion = if (event.flags == 2) 2 else 1
        }
        event.terrain?.let {
            consumedReceipts += it.receipt.receiptId
            lastReceiptSequence = it.receipt.nativeSequence
            if (!it.readSetRegistered) missingMask = missingMask or READ_SET
        }
        event.operation?.let { consumedOperations[it.receiptId] = it }
    }

    private fun queryTerrainReceipts(memory: ExecutionMemory, context: Long, recording: OitExecutionRecording): Map<Long, TerrainReceipt> {
        val receipts = LinkedHashMap<Long, TerrainReceipt>()
        for (entry in recording.journalEntries) {
            val fact = entry.fact as? OitExecutionJournalFact.Observation ?: continue
            for (terrain in fact.event.terrainPreparedFacts) {
                val receipt = (terrain.nativeReceipt as? OitNativeTerrainStageReceiptResult.Available)?.receipt ?: continue
                val invocation = terrain.invocation
                val scope = invocation.scope
                check(invocation.frame === recording.frame && scope.frame === recording.frame &&
                    scope.producer.kind == OitProducerKind.TERRAIN && scope.producerId == receipt.producerId &&
                    scope.serial == receipt.producerScopeSerial && scope.stage == receipt.stage &&
                    invocation.passId == receipt.passId && invocation.sourceDrawId == receipt.sourceDrawId &&
                    invocation.parentSourceDrawId == receipt.parentSourceDrawId && terrain.pipeline == receipt.pipelineIdentity)
                receipts[receipt.receiptId]?.let { check(it.receipt == receipt) }
                if (receipt.receiptId in receipts) continue
                val output = memory.terrainReceipt
                requireSuccess(MCDR_ABI.D3D12OitQueryExecutionTerrainReceiptV2(context, handle, receipt.receiptId, output.segment), "query execution terrain receipt V2")
                output.requireHeader()
                val actual = output.child("receipt", ExecutionLayouts.stageReceipt)
                actual.requireHeader()
                requireIdentity(actual.child("identity", ExecutionLayouts.identity))
                check(actual.getInt("flags") == 0 && actual.getInt("producerKind") == 0 &&
                    actual.getInt("reserved0") == 0 && actual.getInt("reserved1") == 0 && output.getInt("reserved") == 0)
                check(actual.getLong("receiptId") == receipt.receiptId && actual.getLong("nativeSequence") == receipt.nativeSequence &&
                    actual.getLong("frameSerial") == frameSerial && receipt.frameSerial == frameSerial &&
                    actual.getLong("producerId") == receipt.producerId && actual.getLong("producerScopeSerial") == receipt.producerScopeSerial &&
                    actual.getLong("sourcePassId") == receipt.passId && actual.getLong("sourceDrawId") == receipt.sourceDrawId &&
                    actual.getLong("parentSourceDrawId") == (receipt.parentSourceDrawId ?: 0L) &&
                    actual.getLong("pipelineIdentity") == receipt.pipelineIdentity && terrain.pipeline == receipt.pipelineIdentity &&
                    actual.getInt("stage") == stageCode(receipt.stage) &&
                    actual.getInt("contentState") == (if (receipt.contentState == OitNativeContentState.ORIGINAL) 0 else 1))
                check(output.getLong("programGeneration") == programGeneration && output.getLong("resourceVersion") == identity.resourceVersion)
                val shaderGeneration = output.getLong("shaderGeneration")
                val registered = output.getInt("readSetRegistered")
                check(registered in 0..1 && (registered == 1) ==
                    (receipt.readSetRegistration == OitTerrainReadSetRegistration.REGISTERED))
                receipts[receipt.receiptId] = TerrainReceipt(terrain, receipt, shaderGeneration, registered == 1)
            }
        }
        return receipts
    }

    private fun writeManifest(arena: Arena, recording: OitExecutionRecording): Struct {
        val manifest = Struct.allocate(arena, ExecutionLayouts.manifest)
        manifest.setInt("eventCount", Math.toIntExact(sequence))
        manifest.setInt("scopeCount", scopes.size)
        manifest.setInt("passCount", passes.size)
        manifest.setInt("resourceCount", Math.toIntExact(resourceCount))
        manifest.setInt("receiptCount", consumedReceipts.size)
        manifest.setLong("lastSequence", sequence)
        manifest.setInt("flags", if (recording.frameFailed || missingMask and FAILED != 0) 1 else 0)
        manifest.setInt("missingMask", missingMask)
        if (scopes.isNotEmpty()) {
            val layout = ExecutionLayouts.scope
            val rows = arena.allocateFfmArray(scopes.size, layout.memory)
            for ((index, count) in scopes.values.withIndex()) {
                val row = Struct(rows.asSlice(index * layout.memory.byteSize(), layout.memory.byteSize()), layout)
                row.writeHeader()
                row.setInt("producerKind", producerCode(count.scope.producer.kind))
                row.setInt("stage", stageCode(count.scope.stage))
                row.setLong("producerId", count.scope.producerId)
                row.setLong("producerScopeSerial", count.scope.serial)
                row.setInt("eventCount", count.events)
                row.setInt("receiptCount", count.receipts)
                row.setInt("flags", count.flags)
            }
            manifest.setPointer("scopes", rows)
        }
        if (passes.isNotEmpty()) {
            val layout = ExecutionLayouts.pass
            val rows = arena.allocateFfmArray(passes.size, layout.memory)
            for ((index, count) in passes.values.withIndex()) {
                val row = Struct(rows.asSlice(index * layout.memory.byteSize(), layout.memory.byteSize()), layout)
                row.writeHeader()
                row.setLong("sourcePassId", count.passId)
                row.setInt("eventCount", count.events)
                row.setInt("completion", count.completion)
            }
            manifest.setPointer("passes", rows)
        }
        return manifest
    }

    private fun queryInfo(arena: Arena, context: Long): Struct {
        val info = Struct.allocate(arena, ExecutionLayouts.info)
        requireSuccess(MCDR_ABI.D3D12OitQueryExecutionStreamV2(context, handle, info.segment), "query execution stream V2")
        info.requireHeader()
        requireIdentity(info.child("identity", ExecutionLayouts.identity))
        check(info.getLong("frameSerial") == frameSerial && info.getLong("programGeneration") == programGeneration &&
            info.getLong("streamId") == handle && info.getInt("state") == 1)
        return info
    }

    fun retireWithFrame() { handle = 0L }

    private fun writeIdentity(target: Struct) {
        val value = target.child("identity", ExecutionLayouts.identity)
        value.setLong("worldEpoch", identity.worldEpoch)
        value.setLong("realFrameId", identity.realFrameId)
        value.setLong("configVersion", identity.configVersion)
        value.setLong("resourceVersion", identity.resourceVersion)
    }

    private fun requireIdentity(value: Struct) {
        check(value.getLong("worldEpoch") == identity.worldEpoch && value.getLong("realFrameId") == identity.realFrameId &&
            value.getLong("configVersion") == identity.configVersion && value.getLong("resourceVersion") == identity.resourceVersion)
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

    private class ExecutionMemory(arena: Arena, resourcesPerEvent: Int, normalizationInputCount: Int) {
        val event = Struct.allocate(arena, ExecutionLayouts.event)
        val terrainReceipt = Struct.allocate(arena, ExecutionLayouts.terrainReceipt)
        val operationReceipt: MemorySegment = arena.allocate(MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1)
        val eventId: MemorySegment = arena.allocate(MCDRLayout.I64)
        val receiptId: MemorySegment = arena.allocate(MCDRLayout.I64)
        val normalization = if (normalizationInputCount != 0) Struct.allocate(arena, ExecutionLayouts.normalization) else null
        val resources = if (resourcesPerEvent != 0)
            arena.allocateFfmArray(resourcesPerEvent, ExecutionLayouts.resource.memory) else null

        fun resourceRow(index: Int): Struct {
            val layout = ExecutionLayouts.resource
            val size = layout.memory.byteSize()
            val row = Struct(checkNotNull(resources).asSlice(index * size, size), layout)
            row.segment.fill(0)
            row.writeHeader()
            return row
        }
    }

    private class ExecutionLayout(val memory: MemoryLayout) {
        private val offsets = (memory as GroupLayout).memberLayouts().associate { member ->
            val name = member.name().orElseThrow()
            name to memory.byteOffset(groupElement(name))
        }
        fun offset(name: String): Long = offsets.getValue(name)
    }

    private object ExecutionLayouts {
        val begin = ExecutionLayout(MCDRLayout.OIT_EXECUTION_BEGIN_V2)
        val key = ExecutionLayout(MCDRLayout.OIT_EXECUTION_KEY_V2)
        val event = ExecutionLayout(MCDRLayout.OIT_EXECUTION_EVENT_V2)
        val resource = ExecutionLayout(MCDRLayout.OIT_EXECUTION_RESOURCE_V2)
        val normalization = ExecutionLayout(MCDRLayout.OIT_EXECUTION_NORMALIZATION_V2)
        val scope = ExecutionLayout(MCDRLayout.OIT_EXECUTION_SCOPE_V2)
        val pass = ExecutionLayout(MCDRLayout.OIT_EXECUTION_PASS_V2)
        val manifest = ExecutionLayout(MCDRLayout.OIT_EXECUTION_MANIFEST_V2)
        val info = ExecutionLayout(MCDRLayout.OIT_EXECUTION_INFO_V2)
        val terrainReceipt = ExecutionLayout(MCDRLayout.OIT_EXECUTION_TERRAIN_RECEIPT_V2)
        val stageReceipt = ExecutionLayout(MCDRLayout.OIT_TERRAIN_STAGE_RECEIPT_V2)
        val sealResult = ExecutionLayout(MCDRLayout.OIT_EXECUTION_SEAL_RESULT_V2)
        val identity = ExecutionLayout(MCDRLayout.FRAME_IDENTITY)
    }

    private class Struct(val segment: MemorySegment, private val layout: ExecutionLayout) {
        fun child(name: String, childLayout: ExecutionLayout): Struct = Struct(segment.asSlice(layout.offset(name), childLayout.memory.byteSize()), childLayout)
        fun setInt(name: String, value: Int) = segment.set(MCDRLayout.I32, layout.offset(name), value)
        fun setLong(name: String, value: Long) = segment.set(MCDRLayout.I64, layout.offset(name), value)
        fun setPointer(name: String, value: MemorySegment) = segment.set(MCDRLayout.PTR, layout.offset(name), value)
        fun setLongAt(name: String, index: Int, value: Long) =
            segment.set(MCDRLayout.I64, layout.offset(name) + index * MCDRLayout.I64.byteSize(), value)
        fun setBytes(name: String, value: ByteArray) {
            require(value.size == 32)
            MemorySegment.copy(value, 0, segment, MCDRLayout.I8, layout.offset(name), value.size)
        }
        fun getInt(name: String): Int = segment.get(MCDRLayout.I32, layout.offset(name))
        fun getLong(name: String): Long = segment.get(MCDRLayout.I64, layout.offset(name))
        fun getUnsigned(name: String): Long = Integer.toUnsignedLong(getInt(name))
        fun writeHeader(schemaVersion: Int = 2) {
            setInt("schemaVersion", schemaVersion)
            setInt("structSize", Math.toIntExact(layout.memory.byteSize()))
        }
        fun requireHeader() { check(getInt("schemaVersion") == 2 && getInt("structSize").toLong() == layout.memory.byteSize()) }
        companion object {
            fun allocate(arena: Arena, layout: ExecutionLayout): Struct = Struct(arena.allocate(layout.memory), layout).also { it.writeHeader() }
        }
    }

    companion object {
        private const val DRAW = 1
        private const val PASS_BEGIN = 2
        private const val PASS_END = 3
        private const val SCOPE_BEGIN = 4
        private const val SCOPE_END = 5
        private const val COPY_DEPTH = 7
        private const val COPY_BOUNDS = 8
        private const val CULL_BOUNDS = 9
        private const val WATER_MASK = 12
        private const val CLOUD_DEPTH = 13
        private const val NORMALIZATION_EVENT = 10
        private const val UNCLASSIFIED_DRAW = 14
        private const val OBJECT_ONLY = 0
        private const val CONTENT = 0x0001
        private const val WATER = 0x0004
        private const val CLOUD = 0x0008
        private const val NORMALIZATION = 0x0010
        private const val ADAPTER = 0x0020
        private const val RECEIPT = 0x0040
        private const val OPEN_SCOPE = 0x0080
        private const val OPEN_PASS = 0x0100
        private const val MANIFEST = 0x0200
        private const val FAILED = 0x0400
        private const val SELECTION = 0x0800
        private const val READ_SET = 0x1000

        private val laneTextureSources = setOf(COPY_DEPTH, CLOUD_DEPTH, COPY_BOUNDS, CULL_BOUNDS)
        private val depthRoles = listOf(TextureRole.MAIN_DEPTH, TextureRole.TERRAIN_DEPTH, TextureRole.CLOUD_DEPTH)
        private val boundsRoles = listOf(TextureRole.BOUNDS, TextureRole.CULLED_BOUNDS)
        private val coefficientCounts = setOf(1, 2, 4)

        private val OitExecutionEventKind.nativeKind: Int
            get() = when (this) {
                OitExecutionEventKind.DEPTH_BOUNDS, OitExecutionEventKind.TRANSMITTANCE, OitExecutionEventKind.ACCUMULATE -> DRAW
                OitExecutionEventKind.WATER_DEPTH_COPY, OitExecutionEventKind.TERRAIN_DEPTH_COPY -> COPY_DEPTH
                OitExecutionEventKind.CLOUD_DEPTH_COPY -> CLOUD_DEPTH
                OitExecutionEventKind.DEPTH_BOUNDS_COPY -> COPY_BOUNDS
                OitExecutionEventKind.DEPTH_BOUNDS_CULL -> CULL_BOUNDS
                OitExecutionEventKind.WATER_MASK -> WATER_MASK
                OitExecutionEventKind.COMPOSITE, OitExecutionEventKind.UNCLASSIFIED_DRAW -> UNCLASSIFIED_DRAW
            }

        private fun stageCode(stage: OitStage?): Int = when (stage) {
            OitStage.DEPTH_BOUNDS -> 1
            OitStage.TRANSMITTANCE -> 2
            OitStage.ACCUMULATE -> 3
            null -> 0
        }
        private fun producerCode(kind: OitProducerKind): Int = when (kind) {
            OitProducerKind.TERRAIN -> 0
            OitProducerKind.FEATURE -> 1
            OitProducerKind.BORDER -> 2
            OitProducerKind.WEATHER -> 3
            OitProducerKind.CLOUD -> 4
            OitProducerKind.CUSTOM_WEATHER -> 5
            OitProducerKind.CUSTOM_CLOUD -> 6
            OitProducerKind.CUSTOM_TRANSLUCENT -> 7
        }
    }
}
