package io.github.dxegp.render.reconstruction.oit

internal class OitFeatureRecordBatch(
    val surfaces: OitFeatureDraw,
    val metadata: OitRecordMetadata,
    val draw: OitRecordDraw,
)

internal object OitFeatureRecords {
    fun capture(surfaces: OitFeatureDraw, programGeneration: Long, scopeSerial: Long): OitFeatureRecordBatch? {
        if (surfaces.triangles.isEmpty() || !surfaces.valid) return null
        val primitives = ArrayList<OitRecordPrimitive>(surfaces.triangles.size)
        val previous = ArrayList<OitRecordPreviousTriangle>()
        val capabilities = linkedSetOf(OitRecordValidity.CURRENT_MATERIAL, OitRecordValidity.COVERAGE)
        for (triangle in surfaces.triangles) {
            val validity = linkedSetOf(OitRecordValidity.CURRENT_MATERIAL, OitRecordValidity.COVERAGE)
            val previousIndex = triangle.previous?.let { row ->
                validity.add(OitRecordValidity.GEOMETRY_PREVIOUS)
                if (row.validities.wireValue and OitRecordValidity.MATERIAL_PREVIOUS.wireValue != 0)
                    validity.add(OitRecordValidity.MATERIAL_PREVIOUS)
                previous.size.also { previous.add(row) }
            }
            capabilities.addAll(validity)
            val surface = triangle.surface
            primitives.add(OitRecordPrimitive(OitRecordSurface(surface.surfaceId,
                surface.owner.geometryGeneration, surface.material.id, surface.material.generation,
                surface.material.textureGeneration), 0, previousIndex,
                OitRecordValidities.of(*validity.toTypedArray())))
        }
        val source = OitRecordSource(surfaces.kind.ordinal + 1L, surfaces.frame.identity.worldEpoch,
            programGeneration, OitProducerKind.FEATURE, OitRecordValidities.of(*capabilities.toTypedArray()))
        return OitFeatureRecordBatch(surfaces, OitRecordMetadata(listOf(source), primitives, previous, emptyList()),
            OitRecordDraw(0, 0, primitives.size, scopeSerial, primitives.size, 1, 0))
    }
}
