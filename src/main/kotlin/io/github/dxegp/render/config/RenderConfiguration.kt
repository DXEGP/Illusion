package io.github.dxegp.render.config

import com.mojang.blaze3d.systems.RenderSystem
import io.github.dxegp.Illusion
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.config.SrBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.presentation.framegen.FrameGeneration
import io.github.dxegp.render.presentation.latency.NvidiaReflex
import io.github.dxegp.render.reconstruction.SuperResolution
import io.github.dxegp.render.reconstruction.dlss.DeepLearningSuperSampling
import io.github.dxegp.render.resolution.D3D12RenderScale
import io.github.dxegp.render.temporal.D3D12Temporal
import io.github.dxegp.render.temporal.D3D12Velocity
import net.minecraft.client.OptionInstance
import net.minecraft.client.Minecraft

object RenderConfiguration {
    data class Settings(
        val antiAliasing: Int = 0,
        val srBackend: SrBackend = SrBackend.NONE,
        val plainScalePercent: Int = 100,
        val dlssScalePercent: Int = 59,
        val dlssQuality: Int = 2,
        val dlssPreset: Int = 0,
        val fsrQuality: Int = 2,
        val xessQuality: Int = 2,
        val sharpness: Int = 0,
        val providerMasks: Boolean = true,
        val frameGenerationBackend: Int = 0,
        val frameGenerationMode: Int = 0,
        val frameGenerationFrames: Int = 1,
        val temporalChain: Boolean = false,
        val jitterEnabled: Boolean = true,
        val jitterSamples: Int = 16,
    ) {
        val renderScalePercent: Int get() = when {
            antiAliasing == D3D12AntiAliasing.DLAA -> 100
            srBackend == SrBackend.DLSS -> dlssScalePercent
            srBackend == SrBackend.FSR -> qualityPercent(fsrQuality)
            srBackend == SrBackend.XESS -> qualityPercent(xessQuality)
            else -> plainScalePercent
        }

        val reconstructionEnabled: Boolean get() = srBackend != SrBackend.NONE
        val frameGenerationEnabled: Boolean get() = frameGenerationBackend != 0 && frameGenerationMode != 0
        val temporalEnabled: Boolean get() = temporalChain || antiAliasing == D3D12AntiAliasing.TAA ||
            reconstructionEnabled || frameGenerationEnabled

        companion object {
            fun qualityPercent(tier: Int): Int = when (tier) {
                0 -> 34
                1 -> 50
                2 -> 59
                3 -> 67
                else -> 100
            }
        }
    }

    data class Applied(
        val settings: Settings,
        val version: Long,
        val renderWidth: Int = 0,
        val renderHeight: Int = 0,
        val displayWidth: Int = 0,
        val displayHeight: Int = 0,
        val renderScalePercent: Int = settings.renderScalePercent,
    )

    sealed interface Outcome {
        data class Published(val configuration: Applied) : Outcome
        data object RestartRequired : Outcome
        data object PreparationFailed : Outcome
        data object AwaitingWorld : Outcome
    }

    internal data class DisplayExtent(val width: Int, val height: Int)

    private sealed interface RequestState {
        data object Settled : RequestState
        data object Pending : RequestState
        data class Resizing(val extent: DisplayExtent) : RequestState
        data class SuspendedForResize(val extent: DisplayExtent) : RequestState
    }

    @Volatile
    private var published = Applied(Settings(), 0)
    private var requested = published.settings
    private var persistent = published.settings
    private var requestState: RequestState = RequestState.Settled
    private var initialized = false
    private var synchronizingOptions = false
    private var batching = false
    private var completion: ((Outcome) -> Unit)? = null

    @JvmStatic
    fun applied(): Applied = published

    @JvmStatic
    fun requested(): Settings = requested

    fun settingsToSave(): Settings = persistent

    @JvmStatic
    fun version(): Long = published.version

    fun awaitingWorld(): Boolean = (requestState == RequestState.Pending || requestState is RequestState.Resizing) &&
        requested.reconstructionEnabled &&
        Minecraft.getInstance().level == null

    @JvmStatic
    fun initialize() {
        if (initialized) return
        initialized = true
        reloadDefaults()
    }

    fun reloadDefaults() {
        request { previous -> previous.copy(srBackend = D3D12Settings.srBackend,
            sharpness = (D3D12Settings.srSharpness * 100).toInt().coerceIn(0, 100),
            providerMasks = D3D12Settings.srMask, temporalChain = D3D12Settings.temporalChain,
            jitterEnabled = D3D12Settings.temporalJitter) }
    }

    fun request(change: (Settings) -> Settings) {
        if (synchronizingOptions) return
        if (!initialized) initialize()
        val changed = change(requested)
        requested = if (batching) changed else normalize(changed)
        if (requestState != RequestState.Settled || requested != published.settings || published.version == 0L)
            requestState = resumedRequestState(requestState)
    }

    fun requestAntiAliasing(mode: Int) {
        request { settings ->
            settings.copy(antiAliasing = mode,
                srBackend = if (settings.antiAliasing == D3D12AntiAliasing.DLAA && mode != D3D12AntiAliasing.DLAA)
                    SrBackend.NONE else settings.srBackend)
        }
    }

    fun loadSavedSettings(read: () -> Unit) {
        if (!initialized) initialize()
        val previous = requested
        val previousState = requestState
        try {
            batching = true
            read()
            requested = normalize(requested)
            persistent = requested
            requestState = resumedRequestState(requestState)
        } catch (failure: Throwable) {
            requested = previous
            requestState = previousState
            throw failure
        } finally {
            batching = false
        }
    }

    fun submitSettings(edit: () -> Unit, onComplete: (Outcome) -> Unit) {
        if (!initialized) initialize()
        check(completion == null) { "Render configuration submission is already pending" }
        completion = onComplete
        try {
            batching = true
            edit()
            requested = normalize(requested)
            requestState = resumedRequestState(requestState)
        } catch (failure: Throwable) {
            completion = null
            requested = published.settings
            requestState = RequestState.Settled
            synchronizeOptions()
            throw failure
        } finally {
            batching = false
        }
        if (awaitingWorld()) {
            completion = null
            onComplete(Outcome.AwaitingWorld)
        }
    }

    fun cancelSubmission() {
        if (completion == null) return
        completion = null
        requested = persistent
        requestState = if (requested == published.settings) RequestState.Settled else resumedRequestState(requestState)
        synchronizeOptions()
    }

    fun ownsOption(option: OptionInstance<*>): Boolean = option in setOf<OptionInstance<*>?>(
        D3D12AntiAliasing.option(), D3D12RenderScale.option(),
        DeepLearningSuperSampling.option(), DeepLearningSuperSampling.qualityOption(),
        DeepLearningSuperSampling.presetOption(), SuperResolution.fsrOption(), SuperResolution.xessOption(),
        SuperResolution.fsrQualityOption(), SuperResolution.xessQualityOption(),
        SuperResolution.maskOption(), SuperResolution.sharpnessOption(), FrameGeneration.framesOption(),
        FrameGeneration.backendOption(MCDR_ABI.FG_BACKEND_DLSSG),
        FrameGeneration.backendOption(MCDR_ABI.FG_BACKEND_FSRFG),
        FrameGeneration.backendOption(MCDR_ABI.FG_BACKEND_XESSFG),
    )

    @JvmStatic
    fun applyPendingAtFrameStart() {
        RenderSystem.assertOnRenderThread()
        if (!initialized) initialize()
        val extent = PreparedRenderConfiguration.displayExtent() ?: return
        val extentChanged = PreparedRenderConfiguration.extentChanged(published)
        if (!PreparedRenderConfiguration.retireAbandoned()) {
            check(!extentChanged) { "Resized display cannot render while configuration GPU resources remain pending" }
            return
        }
        val state = requestState
        if (state is RequestState.SuspendedForResize && state.extent == extent) {
            if (extentChanged) publishDisplayBaseline(extent)
            return
        }
        if (state == RequestState.Settled && !extentChanged) return
        val resizing = extentChanged || state is RequestState.Resizing || state is RequestState.SuspendedForResize
        val bootstrapping = published.version == 0L &&
            (requested.reconstructionEnabled || requested.frameGenerationEnabled)
        if (bootstrapping) {
            publishDisplayBaseline(extent)
            requestState = RequestState.Resizing(extent)
            return
        }
        val outcome = try {
            publishPreparedConfiguration(requested)?.let { Outcome.Published(it) }
        } catch (failure: RestartRequired) {
            Outcome.RestartRequired
        } catch (failure: Exception) {
            Illusion.LOGGER.warn("Render configuration preparation failed; keeping version {}", published.version, failure)
            Outcome.PreparationFailed
        }
        if (outcome == null) {
            if (resizing) {
                if (extentChanged) publishDisplayBaseline(extent)
                requestState = RequestState.Resizing(extent)
            }
            return
        }
        if (outcome !is Outcome.Published && resizing) {
            if (extentChanged) publishDisplayBaseline(extent)
            requestState = RequestState.SuspendedForResize(extent)
        } else {
            requestState = RequestState.Settled
            requested = published.settings
        }
        if (outcome is Outcome.Published) persistent = outcome.configuration.settings
        synchronizeOptions()
        val notify = completion
        completion = null
        notify?.invoke(outcome)
        if (notify == null && outcome is Outcome.Published) Minecraft.getInstance().options.save()
    }

    private fun publishPreparedConfiguration(settings: Settings): Applied? {
        val next = PreparedRenderConfiguration.prepare(settings, published)?.use { prepared ->
            val applied = prepared.publish(published.version + 1L) ?: return null
            published = applied
            D3D12Temporal.enabled = applied.settings.temporalEnabled
            D3D12Temporal.jitterEnabled = applied.settings.jitterEnabled
            D3D12Temporal.jitterSamples = prepared.jitterSamples
            D3D12Velocity.enabled = applied.settings.temporalEnabled
            applied
        } ?: return null
        try {
            if (next.settings.frameGenerationEnabled &&
                MCDR_ABI.queryFgCaps(next.settings.frameGenerationBackend)?.needsReflex == true) NvidiaReflex.forceOn()
            else NvidiaReflex.releaseFrameGenerationForce()
        } catch (failure: Exception) {
            Illusion.LOGGER.error("Could not synchronize Reflex after publishing configuration {}", next.version, failure)
        }
        return next
    }

    private fun publishDisplayBaseline(extent: DisplayExtent) {
        check(PreparedRenderConfiguration.retireAbandoned()) {
            "Cannot publish display baseline ${extent.width}x${extent.height}: GPU resources remain pending"
        }
        val baseline = requested.copy(srBackend = SrBackend.NONE, antiAliasing = D3D12AntiAliasing.OFF,
            plainScalePercent = 100, frameGenerationBackend = 0, frameGenerationMode = 0,
            temporalChain = requested.temporalEnabled)
        checkNotNull(publishPreparedConfiguration(baseline)) {
            "Could not prepare a renderable display baseline ${extent.width}x${extent.height}"
        }
        synchronizeOptions()
    }

    private fun synchronizeOptions() {
        synchronizingOptions = true
        try {
            D3D12AntiAliasing.synchronizeConfiguration(published.settings)
            D3D12RenderScale.synchronizeConfiguration(published.settings)
            SuperResolution.synchronizeConfiguration(published.settings)
            DeepLearningSuperSampling.synchronizeConfiguration(published.settings)
            FrameGeneration.synchronizeConfiguration(published.settings)
        } finally {
            synchronizingOptions = false
        }
    }

    private fun resumedRequestState(state: RequestState): RequestState = when (state) {
        is RequestState.Resizing -> state
        is RequestState.SuspendedForResize -> RequestState.Resizing(state.extent)
        RequestState.Pending, RequestState.Settled -> RequestState.Pending
    }

    private fun normalize(value: Settings): Settings {
        val dlssGeneration = value.frameGenerationBackend == MCDR_ABI.FG_BACKEND_DLSSG && value.frameGenerationMode != 0
        val backend = if (value.antiAliasing == D3D12AntiAliasing.DLAA || dlssGeneration) SrBackend.DLSS else value.srBackend
        val aa = when {
            value.antiAliasing == D3D12AntiAliasing.DLAA -> value.antiAliasing
            backend == SrBackend.DLSS || (backend != SrBackend.NONE && value.antiAliasing == D3D12AntiAliasing.TAA) ->
                D3D12AntiAliasing.OFF
            else -> value.antiAliasing
        }
        return value.copy(srBackend = backend, antiAliasing = aa,
            plainScalePercent = value.plainScalePercent.coerceIn(25, 400),
            dlssScalePercent = value.dlssScalePercent.coerceIn(25, 100),
            dlssQuality = value.dlssQuality.coerceIn(0, 3), dlssPreset = value.dlssPreset.coerceIn(0, 4),
            fsrQuality = value.fsrQuality.coerceIn(0, 4), xessQuality = value.xessQuality.coerceIn(0, 4),
            sharpness = value.sharpness.coerceIn(0, 100),
            frameGenerationMode = if (value.frameGenerationBackend == 0) 0 else value.frameGenerationMode.coerceIn(0, 1),
            frameGenerationFrames = if (value.frameGenerationBackend == MCDR_ABI.FG_BACKEND_FSRFG) 1 else
                value.frameGenerationFrames.coerceIn(0, 5), jitterSamples = value.jitterSamples.coerceAtLeast(1))
    }

    internal class RestartRequired : IllegalStateException("Frame generation provider change requires restart")

    internal class PreparationRejected(val reason: MCDR_ABI.SrPreparationFailure, detail: String) :
        IllegalStateException("SR configuration preparation $reason: $detail")
}
