package io.github.dxegp.render.presentation.display

import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.OitDisplayGuideAvailability
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import net.minecraft.client.renderer.RenderPipelines

internal enum class TransparencyGuideAvailability(val skipReason: String?) {
    COMPLETE(null),
    PREVIOUS_MOTION_AND_PROVENANCE_UNAVAILABLE("transparent previous motion and pixel provenance are unavailable"),
    OIT_RESOLVE_UNAVAILABLE("OIT color resolve is unavailable"),
    OIT_GUIDES_INACTIVE("OIT reconstruction guides are inactive"),
    OIT_CONTRIBUTION_RESOLVE_UNAVAILABLE("OIT contribution resolve is unavailable"),
    OIT_MOTION_AND_PROVENANCE_UNAVAILABLE("OIT resolve has no multi-contribution motion or pixel provenance"),
}

internal class WorldTransparencyGuides(
    val frame: VanillaFrameInputs,
    val blendedDraws: Boolean,
    val oitDraws: Boolean,
    val oitColorResolved: Boolean,
    val uncoveredBlendedDraws: Boolean,
    val oit: OitDisplayGuideAvailability,
) {
    val availability: TransparencyGuideAvailability = when {
        oitDraws && !oitColorResolved -> TransparencyGuideAvailability.OIT_RESOLVE_UNAVAILABLE
        oitDraws -> when (oit) {
            OitDisplayGuideAvailability.INACTIVE -> TransparencyGuideAvailability.OIT_GUIDES_INACTIVE
            OitDisplayGuideAvailability.RESOLVE_UNAVAILABLE ->
                TransparencyGuideAvailability.OIT_CONTRIBUTION_RESOLVE_UNAVAILABLE
            OitDisplayGuideAvailability.MULTI_CONTRIBUTION_UNAVAILABLE ->
                TransparencyGuideAvailability.OIT_MOTION_AND_PROVENANCE_UNAVAILABLE
        }
        uncoveredBlendedDraws -> TransparencyGuideAvailability.PREVIOUS_MOTION_AND_PROVENANCE_UNAVAILABLE
        else -> TransparencyGuideAvailability.COMPLETE
    }

}

internal object WorldTransparencyCapture {
    private var frame: VanillaFrameInputs? = null
    private var collecting = false
    private var blendedDraws = false
    private var oitDraws = false
    private var oitColorResolved = false
    private var uncoveredBlendedDraws = false
    private var sealed: WorldTransparencyGuides? = null

    fun begin(value: VanillaFrameInputs) {
        check(frame == null) { "World transparency capture began before the previous frame retired" }
        frame = value
        collecting = true
        blendedDraws = false
        oitDraws = false
        oitColorResolved = false
        uncoveredBlendedDraws = false
        sealed = null
    }

    fun recordDraw(compiled: D3D12CompiledRenderPipeline, bindingSerialBeforeDraw: Long) {
        if (!collecting) return
        val pipeline = compiled.info()
        val pixelProducerRecorded = compiled.rasterMotionPso != 0L &&
            D3D12RasterMotionCapture.worldHistoryBindingSerial() > bindingSerialBeforeDraw
        when (pipeline.location) {
            RenderPipelines.OIT_COMPOSITE.location,
            OitReconstructionInputs.compositePipeline.location -> {
                oitColorResolved = true
            }
            RenderPipelines.OIT_DEPTH_BOUNDS_CULL.location,
            RenderPipelines.BLIT_DEPTH_BOUNDS.location,
            RenderPipelines.BLIT_DEPTH_DURING_DEPTH_BOUNDS.location,
            RenderPipelines.BLIT_DEPTH.location,
            RenderPipelines.INTEGRATE_DEPTH.location -> return
            else -> if ("OIT" in pipeline.shaderDefines.flags()) {
                oitDraws = true
            } else if (pipeline.colorTargetStates.any { state ->
                    state != null && state.writeMask() != 0 && state.blendFunction().isPresent
                }) {
                blendedDraws = true
                if (!pixelProducerRecorded) uncoveredBlendedDraws = true
            }
        }
    }

    fun seal(value: VanillaFrameInputs) {
        check(frame === value && collecting) { "World transparency capture belongs to another frame" }
        sealed = WorldTransparencyGuides(value, blendedDraws, oitDraws, oitColorResolved,
            uncoveredBlendedDraws, OitReconstructionInputs.displayGuides(value))
        collecting = false
    }

    fun current(value: VanillaFrameInputs): WorldTransparencyGuides =
        checkNotNull(sealed?.takeIf { it.frame === value }) { "Display frame has no sealed transparency facts" }

    fun discard() {
        frame = null
        collecting = false
        sealed = null
    }
}
