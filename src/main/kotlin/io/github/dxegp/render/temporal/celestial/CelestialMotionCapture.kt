package io.github.dxegp.render.temporal.celestial

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frameprep.FrameInputCoordinates
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import org.joml.Matrix4f
import java.util.EnumMap
import java.util.WeakHashMap

internal enum class CelestialRole { SUN, MOON, STARS, SUNRISE_SUNSET }

internal data class CelestialDrawSource(val owner: Any, val role: CelestialRole,
    val vertices: GpuBuffer, val geometryVariant: Any? = null)

internal object CelestialMotionCapture {
    private class Sample(val inputs: VanillaFrameInputs, val vertices: GpuBuffer,
        val geometryVariant: Any?, val modelView: FloatArray)
    private class Draw(val source: CelestialDrawSource, val inputs: VanillaFrameInputs, val previous: Sample?) {
        var modelView: FloatArray? = null
        var bound = false
        var valid = true
    }

    private val histories = WeakHashMap<Any, EnumMap<CelestialRole, Sample>>()
    private var active: Draw? = null

    fun recordDraw(source: CelestialDrawSource, draw: () -> Unit) {
        val geometry = D3D12RasterMotionCapture.geometryFrame()
        val inputs = VanillaFrameCoordinator.currentFrame()
        if (geometry == null || inputs == null ||
            geometry.space != D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
            draw()
            return
        }
        check(active == null) { "Celestial draw scopes must not overlap" }
        val history = histories.getOrPut(source.owner) { EnumMap(CelestialRole::class.java) }
        val previous = history[source.role]?.takeIf { old ->
            geometry.hasPrevious && inputs.previous != null && old.vertices === source.vertices &&
                old.geometryVariant == source.geometryVariant && FrameInputCoordinates.previousFrameCompatible(
                    old.inputs.identity.realFrameId, inputs.identity.realFrameId,
                    old.inputs.identity.worldEpoch, inputs.identity.worldEpoch,
                    old.inputs.identity.resourceVersion, inputs.identity.resourceVersion,
                    old.inputs.historyGeneration, inputs.historyGeneration, old.inputs.world, inputs.world)
        }
        val captured = Draw(source, inputs, previous)
        if (history[source.role]?.inputs === inputs) captured.valid = false
        active = captured
        try {
            draw()
            val matrix = captured.modelView
            if (captured.valid && matrix != null && VanillaFrameCoordinator.currentFrame() === inputs) {
                history[source.role] = Sample(inputs, source.vertices, source.geometryVariant, matrix)
            } else history.remove(source.role)
            if (matrix != null && !captured.bound) D3D12RasterMotionCapture.invalidateMotion()
        } catch (failure: Throwable) {
            history.remove(source.role)
            D3D12RasterMotionCapture.invalidateMotion()
            throw failure
        } finally {
            active = null
        }
    }

    fun recordTransform(modelView: Matrix4f) {
        val draw = active ?: return
        val matrix = modelView.get(FloatArray(16))
        if (draw.modelView != null || !matrix.all(Float::isFinite)) {
            draw.valid = false
            D3D12RasterMotionCapture.invalidateMotion()
            return
        }
        draw.modelView = matrix
    }

    fun bindPrevious(buffer: GpuBuffer?, byteOffset: Long, stride: Int, vertexBase: Int): FloatArray? {
        val draw = active ?: return null
        val previous = draw.previous ?: return null
        if (!draw.valid || draw.modelView == null || draw.bound ||
            VanillaFrameCoordinator.currentFrame() !== draw.inputs ||
            buffer !== draw.source.vertices || stride <= 0 || byteOffset != vertexBase.toLong() * stride) return null
        val matrix = Matrix4f().set(previous.inputs.current.projection())
            .mul(Matrix4f().set(previous.modelView)).get(FloatArray(16))
        if (!matrix.all(Float::isFinite)) return null
        draw.bound = true
        D3D12RasterMotionCapture.bindCelestialHistory()
        return matrix
    }

    fun reset() {
        histories.clear()
        active = null
    }
}
