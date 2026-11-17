package io.github.dxegp.render.temporal

import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import net.minecraft.client.renderer.StagedVertexBuffer
import java.util.concurrent.atomic.AtomicLong

internal enum class FeatureGeometryKind { ENTITY, ITEM, PARTICLE }

internal class FeatureGeometrySource(
    val id: Long,
    generation: Long,
    val kind: FeatureGeometryKind,
) {
    var generation: Long = generation
        private set

    @Volatile
    var valid: Boolean = true
        private set

    fun invalidate() {
        valid = false
    }

    fun advanceGeneration() {
        generation = Math.incrementExact(generation)
    }

    companion object {
        private val identities = AtomicLong()

        fun next(kind: FeatureGeometryKind, previous: FeatureGeometrySource?): FeatureGeometrySource =
            FeatureGeometrySource(previous?.id ?: identities.incrementAndGet(),
                previous?.let { if (it.valid) it.generation else Math.incrementExact(it.generation) } ?: 1L, kind)
    }
}

internal data class FeatureVertexSource(val source: FeatureGeometrySource, val ordinal: Int)

internal data class FeatureVertexShading(val color: Int?, val overlay: Int?, val lightmap: Int?, val normal: Int?) {
    companion object {
        val UNKNOWN = FeatureVertexShading(null, null, null, null)
    }
}

internal class FeatureGeometryFacts(
    val frame: VanillaFrameInputs,
    val previousFrame: VanillaFrameInputs?,
    val execution: StagedVertexBuffer.ExecuteInfo,
    val stride: Int,
    val history: D3D12RasterMotionCapture.VertexHistory,
    val sources: List<FeatureVertexSource?>,
    val materialUv: FloatArray,
    val shading: List<FeatureVertexShading>,
    val sortedIndices: FeatureSortedIndexMapping?,
)
