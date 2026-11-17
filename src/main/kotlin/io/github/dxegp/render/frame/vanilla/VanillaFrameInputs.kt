package io.github.dxegp.render.frame.vanilla

internal data class VanillaPerspective(
    val nearPlane: Float,
    val farPlane: Float,
    val verticalFovRadians: Float,
)

internal class VanillaCamera(
    val originX: Double,
    val originY: Double,
    val originZ: Double,
    val yaw: Float,
    val pitch: Float,
    val panoramic: Boolean,
    val jitterX: Double,
    val jitterY: Double,
    val perspective: VanillaPerspective?,
    projection: FloatArray,
    rasterProjection: FloatArray,
    viewRotation: FloatArray,
) {
    private val projectionValues = projection.copyOf()
    private val rasterProjectionValues = rasterProjection.copyOf()
    private val viewRotationValues = viewRotation.copyOf()

    init {
        require(originX.isFinite() && originY.isFinite() && originZ.isFinite())
        require(yaw.isFinite() && pitch.isFinite() && jitterX.isFinite() && jitterY.isFinite())
        require(projectionValues.size == 16 && projectionValues.all(Float::isFinite))
        require(rasterProjectionValues.size == 16 && rasterProjectionValues.all(Float::isFinite))
        require(viewRotationValues.size == 16 && viewRotationValues.all(Float::isFinite))
    }

    fun projection(): FloatArray = projectionValues.copyOf()
    fun rasterProjection(): FloatArray = rasterProjectionValues.copyOf()
    fun viewRotation(): FloatArray = viewRotationValues.copyOf()
}

internal data class VanillaFrameSettings(
    val antiAliasing: Int,
    val reconstructionBackend: Int,
    val renderScalePercent: Int,
    val jitterEnabled: Boolean,
    val jitterSamples: Int,
    val providerSharpness: Float,
    val providerMasks: Boolean,
    val dlssQuality: Int,
    val dlssPreset: Int,
    val frameGenerationBackend: Int,
    val frameGenerationMode: Int,
    val frameGenerationFrames: Int,
    val taaBlend: Float,
    val taaSharpness: Float,
    val dlssPostSharpness: Float,
)

internal class VanillaFrameInputs(
    val identity: VanillaFrameIdentity,
    val world: Any,
    val frameTimeNanos: Long,
    val historyGeneration: Long,
    val jitterPhaseIndex: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val frameDeltaMs: Float,
    val settings: VanillaFrameSettings,
    val current: VanillaCamera,
    val previous: VanillaCamera?,
)
