package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.temporal.FeatureGeometryKind
import io.github.dxegp.render.temporal.FeatureGeometrySource
import io.github.dxegp.render.temporal.FeatureSortedIndexMapping

internal data class OitFeatureSurfaceOwner(
    val sourceId: Long,
    val geometryGeneration: Long,
    val firstOrdinal: Int,
    val triangle: Int,
)

internal class OitFeatureSurface(
    val owner: OitFeatureSurfaceOwner,
    val surfaceId: Long,
    val material: OitSurfaceMaterial,
    materialUv: FloatArray,
    val shading: OitFeatureShading?,
) {
    private val uv = materialUv.copyOf()

    init {
        require(surfaceId > 0L && uv.size == 6 && uv.all(Float::isFinite))
    }

    fun materialUv(): FloatArray = uv.copyOf()
}

internal class OitFeatureTriangle(
    val source: FeatureGeometrySource,
    val surface: OitFeatureSurface,
    val previous: OitRecordPreviousTriangle?,
    private val sortedIndices: FeatureSortedIndexMapping? = null,
) {
    val valid: Boolean
        get() = sortedIndices?.valid != false && source.valid && source.generation == surface.owner.geometryGeneration
}

internal class OitFeatureDraw(
    val frame: VanillaFrameInputs,
    val kind: FeatureGeometryKind,
    val triangles: List<OitFeatureTriangle>,
    private val sortedIndices: FeatureSortedIndexMapping? = null,
) {
    val valid: Boolean
        get() = sortedIndices?.valid != false && triangles.all { it.valid }
}
