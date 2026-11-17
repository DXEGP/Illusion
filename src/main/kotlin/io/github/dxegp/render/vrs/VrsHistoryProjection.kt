package io.github.dxegp.render.vrs

import io.github.dxegp.render.frame.vanilla.VanillaCamera
import org.joml.Matrix4d

internal class VrsHistoryProjection private constructor(
    private val viewProjection: Matrix4d,
    private val cameraX: Double,
    private val cameraY: Double,
    private val cameraZ: Double,
) {
    class Mapping(val previousToCurrent: FloatArray, val currentToPrevious: FloatArray)

    fun mapTo(current: VrsHistoryProjection): Mapping? {
        val forward = Matrix4d(current.viewProjection)
            .translate(cameraX - current.cameraX, cameraY - current.cameraY, cameraZ - current.cameraZ)
            .mul(Matrix4d(viewProjection).invert())
        val backward = Matrix4d(forward).invert()
        val previousToCurrent = forward.get(FloatArray(16))
        val currentToPrevious = backward.get(FloatArray(16))
        return if (previousToCurrent.all(Float::isFinite) && currentToPrevious.all(Float::isFinite)) {
            Mapping(previousToCurrent, currentToPrevious)
        } else null
    }

    companion object {
        fun capture(camera: VanillaCamera): VrsHistoryProjection? {
            val viewProjection = Matrix4d(camera.rasterProjection()).mul(Matrix4d(camera.viewRotation()))
            if (!viewProjection.isFinite || viewProjection.determinant() == 0.0) return null
            return VrsHistoryProjection(viewProjection, camera.originX, camera.originY, camera.originZ)
        }
    }
}
