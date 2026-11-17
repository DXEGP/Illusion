package io.github.dxegp.render.reconstruction.oit

import java.util.Collections

internal enum class OitRecordValidity(val wireValue: Int) {
    CURRENT_MATERIAL(1), GEOMETRY_PREVIOUS(2), MATERIAL_PREVIOUS(4), COVERAGE(8),
}

@JvmInline
internal value class OitRecordValidities private constructor(val wireValue: Int) {
    companion object {
        fun of(vararg values: OitRecordValidity): OitRecordValidities =
            OitRecordValidities(values.fold(0) { bits, value -> bits or value.wireValue })
    }
}

internal data class OitRecordSource(
    val producerId: Long,
    val producerGeneration: Long,
    val shaderGeneration: Long,
    val kind: OitProducerKind,
    val validities: OitRecordValidities,
) {
    init {
        require(producerId > 0 && producerGeneration > 0 && shaderGeneration > 0)
        require(!kind.custom || validities.wireValue == 0)
    }
}

internal data class OitRecordSurface(
    val surfaceId: Long,
    val geometryGeneration: Long,
    val materialId: Long,
    val materialGeneration: Long,
    val textureGeneration: Long,
) {
    init {
        require(surfaceId > 0 && geometryGeneration > 0 && materialId > 0 &&
            materialGeneration > 0 && textureGeneration > 0)
    }
}

internal data class OitTerrainSurfaceOwner(
    val worldEpoch: Long,
    val producerId: Long,
    val producerGeneration: Long,
    val sectionX: Int,
    val sectionY: Int,
    val sectionZ: Int,
    val layer: String,
    val geometryGeneration: Long,
    val quadIndex: Int,
) {
    init {
        require(worldEpoch > 0L && producerId > 0L && producerGeneration > 0L && layer.isNotEmpty() &&
            geometryGeneration > 0L && quadIndex >= 0)
    }
}

internal data class OitRecordPrimitive(
    val surface: OitRecordSurface,
    val sourceIndex: Int,
    val previousIndex: Int?,
    val validities: OitRecordValidities,
) {
    init {
        require(sourceIndex >= 0 && (previousIndex == null || previousIndex >= 0))
    }
}

internal class OitRecordPreviousTriangle(
    clip: FloatArray,
    materialUv: FloatArray,
    val validities: OitRecordValidities,
) {
    private val clipValues = clip.copyOf()
    private val materialUvValues = materialUv.copyOf()

    init {
        require(clipValues.size == 12 && clipValues.all(Float::isFinite))
        require(materialUvValues.size == 6 && materialUvValues.all(Float::isFinite))
    }

    fun clip(): FloatArray = clipValues.copyOf()
    fun materialUv(): FloatArray = materialUvValues.copyOf()
}

internal class OitRecordMetadata(
    sources: List<OitRecordSource>,
    primitives: List<OitRecordPrimitive>,
    previous: List<OitRecordPreviousTriangle>,
    sectionPrimitiveBases: List<Int>,
) {
    val sources: List<OitRecordSource> = Collections.unmodifiableList(ArrayList(sources))
    val primitives: List<OitRecordPrimitive> = Collections.unmodifiableList(ArrayList(primitives))
    val previous: List<OitRecordPreviousTriangle> = Collections.unmodifiableList(ArrayList(previous))
    val sectionPrimitiveBases: List<Int> = Collections.unmodifiableList(ArrayList(sectionPrimitiveBases))

    init {
        require(this.sources.isNotEmpty() && this.primitives.isNotEmpty())
        require(this.primitives.all { it.sourceIndex < this.sources.size &&
            (it.previousIndex == null || it.previousIndex < this.previous.size) })
        require(this.sectionPrimitiveBases.all { it in 0..this.primitives.size })
        require(this.sectionPrimitiveBases.zipWithNext().all { (first, second) -> first <= second })
    }
}

internal data class OitRecordLimits(
    val totalBudgetBytes: Long,
    val maxRecords: Int,
    val maxRecordsPerPixel: Int,
    val maxSourceRows: Int,
    val maxPrimitiveRows: Int,
    val maxPreviousRows: Int,
) {
    init {
        require(totalBudgetBytes > 0 && maxRecords > 0 && maxRecordsPerPixel > 0)
        require(maxSourceRows > 0 && maxPrimitiveRows > 0 && maxPreviousRows >= 0)
    }
}

internal data class OitRecordDraw(
    val sourceIndex: Int,
    val primitiveBase: Int,
    val primitiveCount: Int,
    val producerScopeSerial: Long,
    val primitivesPerInstance: Int,
    val instanceCount: Int,
    val firstInstance: Int,
) {
    init {
        require(sourceIndex >= 0 && primitiveBase >= 0 && primitiveCount > 0 && producerScopeSerial > 0)
        require(primitivesPerInstance >= 0 && instanceCount > 0 && firstInstance >= 0)
        require(primitivesPerInstance == 0 || primitivesPerInstance.toLong() * instanceCount == primitiveCount.toLong())
    }
}
