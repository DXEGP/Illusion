package io.github.dxegp.render.temporal

internal class RecordedLevelProjection(
    val world: Any,
    val frameTimeNanos: Long,
    val historyGeneration: Long,
    val jitterPhaseIndex: Long,
    val width: Int,
    val height: Int,
    val jitterX: Double,
    val jitterY: Double,
    projection: FloatArray,
    rasterProjection: FloatArray,
) {
    private val projectionValues = projection.copyOf()
    private val rasterProjectionValues = rasterProjection.copyOf()

    init {
        require(width > 0 && height > 0)
        require(historyGeneration >= 0L && jitterPhaseIndex >= 0L)
        require(jitterX.isFinite() && jitterY.isFinite())
        require(projectionValues.size == 16 && projectionValues.all(Float::isFinite))
        require(rasterProjectionValues.size == 16 && rasterProjectionValues.all(Float::isFinite))
    }

    fun projection(): FloatArray = projectionValues.copyOf()
    fun rasterProjection(): FloatArray = rasterProjectionValues.copyOf()
}
