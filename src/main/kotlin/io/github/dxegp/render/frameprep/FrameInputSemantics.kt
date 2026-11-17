package io.github.dxegp.render.frameprep

internal enum class MotionCoverage {
    CAMERA_REPROJECTION,
    CAMERA_WITH_ENTITY_TRANSLATION_APPROXIMATION,
    CAMERA_WITH_RASTER_ENTITY_MODELS,
}

/**
 * Facts available for an object motion guide. An entity row can improve camera
 * reprojection without being a complete per-pixel guide.
 */
internal enum class MotionGuideCoverage {
    CAMERA_ONLY,
    ENTITY_TRANSLATION_AABB_APPROXIMATION,
    PART_TRANSFORM_UNAVAILABLE,
    RASTER_COVERAGE_UNAVAILABLE,
    CPU_ENTITY_MODEL_RASTER_PARTIAL,
    INVALID,
}

internal enum class ProviderMaskSemantic {
    FSR_REACTIVE,
    FSR_TRANSPARENCY_AND_COMPOSITION,
    XESS_RESPONSIVE,
}

internal enum class ProviderMaskHistory {
    UNAVAILABLE,
    COMPATIBLE,
}

internal object FrameInputCoordinates {
    const val MOTION_SCALE = 1.0f
    const val PRE_EXPOSURE = 1.0f
    const val DEPTH_CLEAR = 0.0f
    const val MOTION_INCLUDES_JITTER = false
    const val HAS_COMPOSITION_MASK = true

    fun sdkJitter(jitterX: Float, jitterY: Float): FloatArray = floatArrayOf(jitterX, -jitterY)

    fun depthPlanes(projection: FloatArray): FloatArray? {
        if (projection.size != 16 || projection[11] != -1.0f) return null
        val nearDenominator = projection[10]
        val farDenominator = projection[10] + 1.0f
        if (nearDenominator == 0.0f || farDenominator == 0.0f) return null
        val first = projection[14] / nearDenominator
        val second = projection[14] / farDenominator
        if (!first.isFinite() || !second.isFinite() || first <= 0.0f || second <= 0.0f) return null
        return floatArrayOf(minOf(first, second), maxOf(first, second))
    }

    fun previousFrameCompatible(capturedFrameId: Long, currentFrameId: Long,
        capturedWorldEpoch: Long, currentWorldEpoch: Long,
        capturedResourceVersion: Long, currentResourceVersion: Long,
        capturedGeneration: Long, currentGeneration: Long, capturedWorld: Any?, currentWorld: Any?,
    ): Boolean = capturedWorld != null && capturedWorld === currentWorld &&
        capturedFrameId > 0L && currentFrameId > capturedFrameId && currentFrameId - capturedFrameId == 1L &&
        capturedWorldEpoch > 0L && capturedWorldEpoch == currentWorldEpoch &&
        capturedResourceVersion > 0L && capturedResourceVersion == currentResourceVersion &&
        capturedGeneration == currentGeneration

}
