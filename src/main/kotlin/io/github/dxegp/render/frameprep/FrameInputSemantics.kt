package io.github.dxegp.render.frameprep

internal enum class MotionCoverage {
    CAMERA_REPROJECTION,
    CAMERA_WITH_ENTITY_TRANSLATION_APPROXIMATION,
}

internal enum class ProviderMaskSemantic {
    FSR_REACTIVE,
    XESS_RESPONSIVE,
}

internal object FrameInputCoordinates {
    const val MOTION_SCALE = 1.0f
    const val PRE_EXPOSURE = 1.0f
    const val DEPTH_CLEAR = 0.0f
    const val MOTION_INCLUDES_JITTER = false
    const val HAS_COMPOSITION_MASK = false

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

    fun previousFrameCompatible(capturedSequence: Long, currentSequence: Long,
        capturedGeneration: Long, currentGeneration: Long, capturedWorld: Any?, currentWorld: Any?,
        capturedProjection: FloatArray?, currentProjection: FloatArray?,
        capturedView: FloatArray?, currentView: FloatArray?, capturedCamera: DoubleArray?,
        currentCamera: DoubleArray, capturedWidth: Int, capturedHeight: Int, width: Int, height: Int): Boolean =
        capturedWorld != null && capturedWorld === currentWorld &&
            (capturedSequence == currentSequence || capturedSequence + 1L == currentSequence) &&
            capturedGeneration == currentGeneration &&
            capturedProjection != null && capturedProjection.contentEquals(currentProjection) &&
            capturedView != null && capturedView.contentEquals(currentView) &&
            capturedCamera != null && capturedCamera.all(Double::isFinite) &&
            capturedCamera.contentEquals(currentCamera) &&
            width > 0 && height > 0 && capturedWidth == width && capturedHeight == height
}
