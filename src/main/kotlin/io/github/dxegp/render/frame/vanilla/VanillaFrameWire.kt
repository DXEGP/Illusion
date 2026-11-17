package io.github.dxegp.render.frame.vanilla

import io.github.dxegp.d3d12.ffi.MCDR_ABI as MCDR
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.render.frameprep.MotionCoverage
import io.github.dxegp.render.frameprep.MotionGuideCoverage
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment

internal object VanillaFrameWire {
    private const val SCHEMA_VERSION = 2

    fun begin(ctx: Long, frame: VanillaFrameInputs): Int = Arena.ofConfined().use { arena ->
        val input = arena.allocate(MCDRLayout.VANILLA_FRAME_INPUT)
        require(frame.identity.schemaVersion == SCHEMA_VERSION)
        input.set(MCDRLayout.I32, Offsets.schemaVersion, SCHEMA_VERSION)
        input.set(MCDRLayout.I32, Offsets.structSize, Offsets.size.toInt())
        identity(input.asSlice(Offsets.identity, FrameWireLayout.Identity.size), frame.identity)
        input.set(MCDRLayout.I64, Offsets.historyGeneration, frame.historyGeneration)
        input.set(MCDRLayout.I64, Offsets.frameTimeNanos, frame.frameTimeNanos)
        input.set(MCDRLayout.I64, Offsets.jitterPhaseIndex, frame.jitterPhaseIndex)
        input.set(MCDRLayout.I32, Offsets.renderWidth, frame.identity.width)
        input.set(MCDRLayout.I32, Offsets.renderHeight, frame.identity.height)
        input.set(MCDRLayout.I32, Offsets.displayWidth, frame.displayWidth)
        input.set(MCDRLayout.I32, Offsets.displayHeight, frame.displayHeight)
        input.set(MCDRLayout.I32, Offsets.flags, if (frame.previous != null) 1 else 0)
        input.set(MCDRLayout.I32, Offsets.colorEncoding, 1)
        input.set(MCDRLayout.I32, Offsets.depthConvention, 1)
        input.set(MCDRLayout.I32, Offsets.motionConvention, 1)
        input.set(MCDRLayout.F32, Offsets.frameDeltaMs, frame.frameDeltaMs)
        input.set(MCDRLayout.F32, Offsets.preExposure, 1.0f)
        input.set(MCDRLayout.F32, Offsets.motionScaleX, 1.0f)
        input.set(MCDRLayout.F32, Offsets.motionScaleY, 1.0f)
        input.set(MCDRLayout.F32, Offsets.viewSpaceToMeters, 1.0f)
        input.set(MCDRLayout.I64, Offsets.previousRealFrameId, if (frame.previous != null) frame.identity.realFrameId - 1L else 0L)
        settings(input.asSlice(Offsets.settings, SettingsOffsets.size), frame.settings)
        camera(input.asSlice(Offsets.current, FrameWireLayout.Camera.size), frame.current)
        frame.previous?.let { camera(input.asSlice(Offsets.previous, FrameWireLayout.Camera.size), it) }
        MCDR.D3D12VanillaBeginFrame(ctx, input)
    }

    fun worldReady(ctx: Long, identity: VanillaFrameIdentity): Int = identityCall(ctx, identity, MCDR::D3D12VanillaWorldReady)

    fun guiReady(ctx: Long, identity: VanillaFrameIdentity): Int = identityCall(ctx, identity, MCDR::D3D12VanillaGuiReady)

    private fun identityCall(ctx: Long, value: VanillaFrameIdentity, call: (Long, MemorySegment) -> Int): Int =
        Arena.ofConfined().use { arena ->
            val input = arena.allocate(MCDRLayout.FRAME_IDENTITY)
            identity(input, value)
            call(ctx, input)
        }

    private fun identity(target: MemorySegment, value: VanillaFrameIdentity) {
        val offsets = FrameWireLayout.Identity
        target.set(MCDRLayout.I64, offsets.worldEpoch, value.worldEpoch)
        target.set(MCDRLayout.I64, offsets.realFrameId, value.realFrameId)
        target.set(MCDRLayout.I64, offsets.configVersion, value.configVersion)
        target.set(MCDRLayout.I64, offsets.resourceVersion, value.resourceVersion)
    }

    private fun settings(target: MemorySegment, value: VanillaFrameSettings) {
        val offsets = SettingsOffsets
        target.set(MCDRLayout.I32, offsets.antiAliasing, value.antiAliasing)
        target.set(MCDRLayout.I32, offsets.reconstructionBackend, value.reconstructionBackend)
        target.set(MCDRLayout.I32, offsets.renderScalePercent, value.renderScalePercent)
        target.set(MCDRLayout.I32, offsets.jitterEnabled, if (value.jitterEnabled) 1 else 0)
        target.set(MCDRLayout.I32, offsets.jitterSamples, value.jitterSamples)
        target.set(MCDRLayout.F32, offsets.providerSharpness, value.providerSharpness)
        target.set(MCDRLayout.I32, offsets.providerMasks, if (value.providerMasks) 1 else 0)
        target.set(MCDRLayout.I32, offsets.dlssQuality, value.dlssQuality)
        target.set(MCDRLayout.I32, offsets.dlssPreset, value.dlssPreset)
        target.set(MCDRLayout.I32, offsets.frameGenerationBackend, value.frameGenerationBackend)
        target.set(MCDRLayout.I32, offsets.frameGenerationMode, value.frameGenerationMode)
        target.set(MCDRLayout.I32, offsets.frameGenerationFrames, value.frameGenerationFrames)
        target.set(MCDRLayout.F32, offsets.taaBlend, value.taaBlend)
        target.set(MCDRLayout.F32, offsets.taaSharpness, value.taaSharpness)
    }

    internal fun camera(target: MemorySegment, value: VanillaCamera) {
        val offsets = FrameWireLayout.Camera
        val origin = target.asSlice(offsets.origin, offsets.originSize)
        origin.setAtIndex(MCDRLayout.F64, 0, value.originX)
        origin.setAtIndex(MCDRLayout.F64, 1, value.originY)
        origin.setAtIndex(MCDRLayout.F64, 2, value.originZ)
        target.set(MCDRLayout.F64, offsets.jitterX, value.jitterX)
        target.set(MCDRLayout.F64, offsets.jitterY, value.jitterY)
        array(target.asSlice(offsets.projection, offsets.projectionSize), value.projection())
        array(target.asSlice(offsets.rasterProjection, offsets.rasterProjectionSize), value.rasterProjection())
        array(target.asSlice(offsets.viewRotation, offsets.viewRotationSize), value.viewRotation())
        val lens = value.perspective?.takeIf {
            it.nearPlane.isFinite() && it.farPlane.isFinite() && it.verticalFovRadians.isFinite() &&
                it.nearPlane > 0.0f && it.farPlane > it.nearPlane &&
                it.verticalFovRadians > 0.0f && it.verticalFovRadians < Math.PI
        }
        if (lens != null) {
            target.set(MCDRLayout.F32, offsets.nearPlane, lens.nearPlane)
            target.set(MCDRLayout.F32, offsets.farPlane, lens.farPlane)
            target.set(MCDRLayout.F32, offsets.verticalFovRadians, lens.verticalFovRadians)
        }
        target.set(MCDRLayout.I32, offsets.flags, (if (lens != null) 257 else 0) or (if (value.panoramic) 2 else 0))
        target.set(MCDRLayout.F32, offsets.yaw, value.yaw)
        target.set(MCDRLayout.F32, offsets.pitch, value.pitch)
    }

    private fun array(target: MemorySegment, values: FloatArray) {
        values.forEachIndexed { index, value -> target.setAtIndex(MCDRLayout.F32, index.toLong(), value) }
    }

    internal fun motionCoverage(value: MotionCoverage): Int = when (value) {
        MotionCoverage.CAMERA_REPROJECTION -> 0
        MotionCoverage.CAMERA_WITH_ENTITY_TRANSLATION_APPROXIMATION -> 1
        MotionCoverage.CAMERA_WITH_RASTER_ENTITY_MODELS -> 2
    }

    internal fun guideCoverage(value: MotionGuideCoverage): Int = when (value) {
        MotionGuideCoverage.CAMERA_ONLY -> 0
        MotionGuideCoverage.ENTITY_TRANSLATION_AABB_APPROXIMATION -> 1
        MotionGuideCoverage.PART_TRANSFORM_UNAVAILABLE -> 2
        MotionGuideCoverage.RASTER_COVERAGE_UNAVAILABLE -> 3
        MotionGuideCoverage.CPU_ENTITY_MODEL_RASTER_PARTIAL -> 4
        MotionGuideCoverage.INVALID -> 5
    }

    private object Offsets {
        private val layout = MCDRLayout.VANILLA_FRAME_INPUT
        val size = layout.byteSize()
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val identity = layout.byteOffset(groupElement("identity"))
        val historyGeneration = layout.byteOffset(groupElement("historyGeneration"))
        val frameTimeNanos = layout.byteOffset(groupElement("frameTimeNanos"))
        val jitterPhaseIndex = layout.byteOffset(groupElement("jitterPhaseIndex"))
        val renderWidth = layout.byteOffset(groupElement("renderWidth"))
        val renderHeight = layout.byteOffset(groupElement("renderHeight"))
        val displayWidth = layout.byteOffset(groupElement("displayWidth"))
        val displayHeight = layout.byteOffset(groupElement("displayHeight"))
        val flags = layout.byteOffset(groupElement("flags"))
        val colorEncoding = layout.byteOffset(groupElement("colorEncoding"))
        val depthConvention = layout.byteOffset(groupElement("depthConvention"))
        val motionConvention = layout.byteOffset(groupElement("motionConvention"))
        val frameDeltaMs = layout.byteOffset(groupElement("frameDeltaMs"))
        val preExposure = layout.byteOffset(groupElement("preExposure"))
        val motionScaleX = layout.byteOffset(groupElement("motionScaleX"))
        val motionScaleY = layout.byteOffset(groupElement("motionScaleY"))
        val viewSpaceToMeters = layout.byteOffset(groupElement("viewSpaceToMeters"))
        val previousRealFrameId = layout.byteOffset(groupElement("previousRealFrameId"))
        val settings = layout.byteOffset(groupElement("settings"))
        val current = layout.byteOffset(groupElement("current"))
        val previous = layout.byteOffset(groupElement("previous"))
    }

    private object SettingsOffsets {
        private val layout = MCDRLayout.FRAME_SETTINGS
        val size = layout.byteSize()
        val antiAliasing = layout.byteOffset(groupElement("antiAliasing"))
        val reconstructionBackend = layout.byteOffset(groupElement("reconstructionBackend"))
        val renderScalePercent = layout.byteOffset(groupElement("renderScalePercent"))
        val jitterEnabled = layout.byteOffset(groupElement("jitterEnabled"))
        val jitterSamples = layout.byteOffset(groupElement("jitterSamples"))
        val providerSharpness = layout.byteOffset(groupElement("providerSharpness"))
        val providerMasks = layout.byteOffset(groupElement("providerMasks"))
        val dlssQuality = layout.byteOffset(groupElement("dlssQuality"))
        val dlssPreset = layout.byteOffset(groupElement("dlssPreset"))
        val frameGenerationBackend = layout.byteOffset(groupElement("frameGenerationBackend"))
        val frameGenerationMode = layout.byteOffset(groupElement("frameGenerationMode"))
        val frameGenerationFrames = layout.byteOffset(groupElement("frameGenerationFrames"))
        val taaBlend = layout.byteOffset(groupElement("taaBlend"))
        val taaSharpness = layout.byteOffset(groupElement("taaSharpness"))
    }
}
