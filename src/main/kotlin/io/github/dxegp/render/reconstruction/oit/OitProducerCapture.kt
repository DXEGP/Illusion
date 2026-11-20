package io.github.dxegp.render.reconstruction.oit

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
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
    private class DrawRecording(val disposition: OitDrawDisposition, val selection: OitDrawSelection) {
        val contributions = ArrayList<() -> Unit>()
        var facts: OitDrawFacts? = null
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
        val producerIds = OitProducerKind.entries.associateWith { IdentityHashMap<Any, Long>() }
        var nextProducerId = 0L
        var nextPassId = 0L
        val textures = Collections.newSetFromMap(IdentityHashMap<D3D12GpuTexture, Boolean>())
        var activePass: Any? = null
        var providerStage: OitStage? = null
        var draw: DrawRecording? = null
        var failed = false

        fun retainView(view: GpuTextureView): OitTextureViewFacts {
            val facts = OitTextureViewFacts(view)
            val texture = facts.texture as? D3D12GpuTexture
            if (texture != null && textures.add(texture)) texture.addViews()
            return facts
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
            current.nextProducerId
        }
        val scope = ScopeRecording(OitProducerScope(current.facts, producer, stage, nextScopeSerial, producerId))
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
            if (completion == OitScopeCompletion.FAILED) current.failed = true
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
        current.activePass = pass
    }

    fun recordPipeline(pass: Any, location: String) {
        recording?.let { current ->
            if (current.passes.containsKey(pass)) current.pipelineLocations[pass] = location
        }
    }

    fun recordTextureBinding(pass: Any, name: String, view: GpuTextureView, sampler: GpuSampler) {
        val current = recording ?: return
        val facts = current.passes[pass] ?: return
        if (name !in OitPassFacts.guideSamplerNames) return
        if (facts.sampledViews[name]?.let { it.view.source === view && it.sampler === sampler } == true) return
        val sampled = LinkedHashMap(facts.sampledViews)
        sampled[name] = OitSampledView(current.retainView(view), sampler)
        current.passes[pass] = OitPassFacts(facts.frame, facts.stage, facts.providerStage,
            facts.colorTargets, facts.depthTarget, sampled, facts.originalSampledViews, facts.historyEligible, facts.descriptor)
    }

    fun retirePass(pass: Any) {
        val current = recording ?: return
        current.passes.remove(pass)
        current.pipelineLocations.remove(pass)
        if (current.activePass === pass) current.activePass = null
    }

    fun recordPassFailure() {
        recording?.failed = true
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
        val draw = DrawRecording(disposition, selection())
        current.draw = draw
        try {
            val result = action()
            if (disposition != OitDrawDisposition.EMPTY) {
                current.scopes.lastOrNull()?.let { it.actualDrawCalls = Math.incrementExact(it.actualDrawCalls) }
            }
            if (previous != null) previous.contributions.addAll(draw.contributions)
            else for (contribution in draw.contributions) {
                contribution()
                current.scopes.lastOrNull()?.let { it.drawCalls = Math.incrementExact(it.drawCalls) }
            }
            return result
        } catch (failure: Throwable) {
            current.failed = true
            throw failure
        } finally {
            current.draw = previous
        }
    }

    fun frameResult(): OitFrameResult? = recording?.let { current ->
        OitFrameResult(current.facts, current.results,
            current.failed || current.scopes.isNotEmpty() || current.activePass != null)
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
