package io.github.dxegp.render.reconstruction.oit

import org.joml.Matrix4f
import org.joml.Vector4f

internal class OitSurfaceRecordBatch(
    val surfaces: OitSurfaceDraw,
    val metadata: OitRecordMetadata,
    val draw: OitRecordDraw,
) {
    val firstIndex: Int
        get() = Math.multiplyExact(surfaces.firstVertex / 4, 6)

    val indexCount: Int
        get() = Math.multiplyExact(surfaces.surfaces.size, 6)
}

internal object OitSurfaceRecords {
    private val quadTriangles = arrayOf(intArrayOf(0, 1, 2), intArrayOf(2, 3, 0))

    fun capture(surfaceDraw: OitSurfaceDraw, programGeneration: Long, scopeSerial: Long): OitSurfaceRecordBatch {
        val identity = surfaceDraw.frame.identity
        if (programGeneration <= 0L || scopeSerial <= 0L || surfaceDraw.surfaces.isEmpty() ||
            surfaceDraw.firstVertex < 0 || surfaceDraw.firstVertex % 4 != 0 ||
            (surfaceDraw.firstVertex / 4L + surfaceDraw.surfaces.size.toLong()) * 6L > Int.MAX_VALUE) {
            throw OitRecordException(OitRecordFailure.INVALID_CONTRACT, "capture surfaces", identity,
                "Surface draw has an invalid program, scope, or quad range")
        }
        val kind = when (surfaceDraw.surfaces.first().current) {
            is OitCloudSurface -> OitProducerKind.CLOUD
            is OitWeatherSurface -> OitProducerKind.WEATHER
            is OitBorderSurface -> OitProducerKind.BORDER
        }
        val camera = surfaceDraw.previousFrame?.current
        val previousProjection = camera?.let { Matrix4f().set(it.rasterProjection()).mul(Matrix4f().set(it.viewRotation())) }
        val primitives = ArrayList<OitRecordPrimitive>(surfaceDraw.surfaces.size * 2)
        val previous = ArrayList<OitRecordPreviousTriangle>()
        val sourceCapabilities = LinkedHashSet<OitRecordValidity>()
        for ((surfaceIndex, pair) in surfaceDraw.surfaces.withIndex()) {
            if (pair.owner.producerId != surfaceDraw.producerId ||
                pair.owner.producerGeneration != surfaceDraw.producerGeneration ||
                pair.owner.worldEpoch != identity.worldEpoch || pair.surfaceId <= 0L ||
                when (pair.current) {
                    is OitCloudSurface -> OitProducerKind.CLOUD
                    is OitWeatherSurface -> OitProducerKind.WEATHER
                    is OitBorderSurface -> OitProducerKind.BORDER
                } != kind) {
                throw OitRecordException(OitRecordFailure.INVALID_CONTRACT, "capture surfaces", identity,
                    "Surface ${pair.surfaceId} differs from its draw owner or producer kind")
            }
            when (val current = pair.current) {
                is OitCloudSurface -> if (surfaceDraw.firstVertex != 0 || pair.owner.kind != OitSurfaceKind.CLOUD ||
                    current.face.direction !in 0..5 || current.preparation.faces.size != surfaceDraw.surfaces.size ||
                    current.preparation.faces[surfaceIndex] != current.face) {
                    throw OitRecordException(OitRecordFailure.INVALID_CONTRACT, "capture cloud surfaces", identity,
                        "Surface ${pair.surfaceId} differs from the actual cloud face order")
                }
                is OitWeatherSurface -> if (pair.owner.kind != current.quad.kind) {
                    throw OitRecordException(OitRecordFailure.INVALID_CONTRACT, "capture weather surfaces", identity,
                        "Surface ${pair.surfaceId} differs from the actual rain or snow kind")
                }
                is OitBorderSurface -> if (pair.owner.kind != OitSurfaceKind.BORDER ||
                    current.side * 4 != surfaceDraw.firstVertex || surfaceDraw.surfaces.size != 1) {
                    throw OitRecordException(OitRecordFailure.INVALID_CONTRACT, "capture border surfaces", identity,
                        "Surface ${pair.surfaceId} differs from the actual border quad range")
                }
            }
            val material = when (val current = pair.current) {
                is OitCloudSurface -> OitSurfaceMaterial(surfaceDraw.producerId,
                    current.preparation.generation, current.preparation.generation)
                is OitWeatherSurface -> current.material
                is OitBorderSurface -> current.material
            }
            val surface = OitRecordSurface(pair.surfaceId,
                (pair.current as? OitBorderSurface)?.mesh?.generation ?: surfaceDraw.producerGeneration,
                material.id, material.generation, material.textureGeneration)
            val projection = if (pair.previous is OitBorderSurface && camera != null)
                Matrix4f().set(camera.rasterProjection()).mul(Matrix4f().set(pair.previous.transform.modelView()))
                else previousProjection
            val vertices = if (projection != null && pair.geometryValidity == OitSurfaceValidity.VALID)
                previousVertices(pair) else null
            for (triangle in quadTriangles) {
                val validities = LinkedHashSet<OitRecordValidity>()
                if (pair.current.finite) {
                    validities.add(OitRecordValidity.CURRENT_MATERIAL)
                    validities.add(OitRecordValidity.COVERAGE)
                }
                var previousIndex: Int? = null
                if (vertices != null && projection != null) {
                    val clip = FloatArray(12)
                    val materialUv = FloatArray(6)
                    for ((corner, vertexIndex) in triangle.withIndex()) {
                        val vertex = vertices[vertexIndex]
                        val projected = projection.transform(Vector4f(vertex.x, vertex.y, vertex.z, 1f))
                        clip[corner * 4] = projected.x
                        clip[corner * 4 + 1] = projected.y
                        clip[corner * 4 + 2] = projected.z
                        clip[corner * 4 + 3] = projected.w
                        materialUv[corner * 2] = vertex.u
                        materialUv[corner * 2 + 1] = vertex.v
                    }
                    if (clip.all(Float::isFinite)) {
                        validities.add(OitRecordValidity.GEOMETRY_PREVIOUS)
                        if (pair.materialValidity == OitSurfaceValidity.VALID && materialUv.all(Float::isFinite))
                            validities.add(OitRecordValidity.MATERIAL_PREVIOUS)
                        if (!materialUv.all(Float::isFinite)) materialUv.fill(0f)
                        previousIndex = previous.size
                        previous.add(OitRecordPreviousTriangle(clip, materialUv,
                            if (OitRecordValidity.MATERIAL_PREVIOUS in validities)
                                OitRecordValidities.of(OitRecordValidity.GEOMETRY_PREVIOUS, OitRecordValidity.MATERIAL_PREVIOUS)
                            else OitRecordValidities.of(OitRecordValidity.GEOMETRY_PREVIOUS)))
                    }
                }
                sourceCapabilities.addAll(validities)
                primitives.add(OitRecordPrimitive(surface, 0, previousIndex,
                    OitRecordValidities.of(*validities.toTypedArray())))
            }
        }
        val source = OitRecordSource(surfaceDraw.producerId, surfaceDraw.producerGeneration, programGeneration,
            kind, OitRecordValidities.of(*sourceCapabilities.toTypedArray()))
        val metadata = OitRecordMetadata(listOf(source), primitives, previous, emptyList())
        val draw = OitRecordDraw(0, 0, primitives.size, scopeSerial, primitives.size, 1, 0)
        return OitSurfaceRecordBatch(surfaceDraw, metadata, draw)
    }

    private fun previousVertices(pair: OitSurfacePair): List<OitSurfaceVertex>? = when (val current = pair.current) {
        is OitCloudSurface -> {
            val old = pair.previous as? OitCloudSurface
            if (old == null || !old.finite || old.face.direction !in 0..5 ||
                old.face.role != current.face.role) null
            else (0..3).map { corner ->
                val index = old.face.direction * 4 + if (old.face.flags and 16 != 0) 3 - corner else corner
                val preparation = old.preparation
                val x = ((0xF03CC3 ushr index) and 1).toFloat() * preparation.cellSizeX +
                    old.face.x.toFloat() * preparation.cellSizeX + preparation.offsetX
                val y = ((0x6666F0 ushr index) and 1).toFloat() * preparation.cellSizeY + preparation.offsetY
                val z = ((0xC3F066 ushr index) and 1).toFloat() * preparation.cellSizeZ +
                    old.face.z.toFloat() * preparation.cellSizeZ + preparation.offsetZ
                OitSurfaceVertex(x, y, z, 0f, 0f)
            }
        }
        is OitWeatherSurface -> {
            val old = pair.previous as? OitWeatherSurface
            if (old == null || !old.finite || !current.finite) null
            else {
                val top = old.quad.vertices[0]
                val bottom = old.quad.vertices[3]
                val spanY = bottom.y.toDouble() - top.y.toDouble()
                if (!spanY.isFinite() || spanY == 0.0) null
                else {
                    val originDeltaY = current.quad.origin.y - old.quad.origin.y
                    val liftShiftV = if (pair.materialValidity == OitSurfaceValidity.VALID)
                        pair.phaseDeltaV - (current.quad.vOffset.toDouble() - old.quad.vOffset.toDouble()) else 0.0
                    current.quad.vertices.mapIndexed { index, vertex ->
                        val oldVertex = old.quad.vertices[index]
                        val oldY = vertex.y.toDouble() + originDeltaY
                        val vertical = (oldY - top.y.toDouble()) / spanY
                        val oldV = top.v.toDouble() + vertical * (bottom.v.toDouble() - top.v.toDouble()) - liftShiftV
                        OitSurfaceVertex(oldVertex.x, oldY.toFloat(), oldVertex.z, oldVertex.u, oldV.toFloat())
                    }
                }
            }
        }
        is OitBorderSurface -> {
            val old = pair.previous as? OitBorderSurface
            if (old == null || !old.finite || !current.finite || old.side != current.side ||
                old.mesh.generation != current.mesh.generation) null
            else {
                val texture = Matrix4f().set(old.transform.texture())
                old.mesh.vertices.subList(old.side * 4, old.side * 4 + 4).map { vertex ->
                    val uv = texture.transform(Vector4f(vertex.u, vertex.v, 0f, 1f))
                    OitSurfaceVertex((vertex.x + old.transform.offset.x).toFloat(),
                        (vertex.y + old.transform.offset.y).toFloat(), (vertex.z + old.transform.offset.z).toFloat(),
                        (uv.x - pair.phaseDeltaU).toFloat(), (uv.y - pair.phaseDeltaV).toFloat())
                }
            }
        }
    }
}
