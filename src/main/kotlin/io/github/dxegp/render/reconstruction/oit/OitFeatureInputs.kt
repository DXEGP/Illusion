package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12IndexedRasterDraw
import io.github.dxegp.d3d12.shader.D3D12OitCaptureProfile
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import io.github.dxegp.render.temporal.FeatureGeometryKind
import org.joml.Matrix4f
import org.joml.Vector4f

internal object OitFeatureInputs {
    private class Presented(val frame: VanillaFrameInputs, val surfaces: Map<OitFeatureSurfaceOwner, OitFeatureSurface>)

    private val quadTriangles = arrayOf(intArrayOf(0, 1, 2), intArrayOf(2, 3, 0))
    private val triangle = arrayOf(intArrayOf(0, 1, 2))
    private var frame: VanillaFrameInputs? = null
    private var pending: MutableMap<OitFeatureSurfaceOwner, OitFeatureTriangle>? = null
    private var staged: Presented? = null
    private var presented: Presented? = null
    private var nextSurfaceId = 0L

    val consumedSourcesValid: Boolean
        get() = pending?.values?.all { it.valid } != false

    fun beginFrame(inputs: VanillaFrameInputs) {
        check(frame == null && staged == null) { "FEATURE frame began before presentation settled" }
        frame = inputs
        pending = null
    }

    fun prepare(draw: D3D12IndexedRasterDraw, pipeline: D3D12CompiledRenderPipeline): OitFeatureDraw? {
        val inputs = frame ?: return null
        val source = draw.source ?: return null
        val profile = pipeline.oitCaptureProgram?.profile ?: return null
        val topology = pipeline.info().primitiveTopology
        if (draw.instanceCount != 1 || draw.firstInstance != 0 || draw.indexCount <= 0 ||
            draw.firstIndex < 0 || source.vertexByteOffset < 0L || source.vertexStride <= 0) return null
        val byteOffset = source.vertexByteOffset + draw.vertexOffset.toLong() * source.vertexStride
        if (byteOffset < 0L) return null
        val facts = D3D12RasterMotionCapture.lookupFeature(source.vertexBuffer, byteOffset, source.vertexStride)
            ?: return null
        if (facts.frame !== inputs || inputs !== VanillaFrameCoordinator.currentFrame()) return null
        val execution = facts.execution
        val history = facts.history
        if (execution.topology() != topology ||
            execution.vertexBuffer() !== source.vertexBuffer ||
            execution.indexBuffer() !== source.indexBuffer || execution.indexType() != source.indexType ||
            execution.firstIndex() != draw.firstIndex || execution.indexCount() != draw.indexCount ||
            execution.baseVertex().toLong() * facts.stride != byteOffset || facts.stride != source.vertexStride ||
            history.space != D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE ||
            history.historyGeneration != inputs.historyGeneration || facts.sources.size != history.vertexCount ||
            facts.shading.size != history.vertexCount ||
            facts.materialUv.size.toLong() != history.vertexCount.toLong() * 2L ||
            history.previousPositions.size.toLong() != history.vertexCount.toLong() * 4L ||
            facts.materialUv.any { !it.isFinite() }) return null
        val kind = when (profile) {
            D3D12OitCaptureProfile.ENTITY -> FeatureGeometryKind.ENTITY
            D3D12OitCaptureProfile.ITEM -> FeatureGeometryKind.ITEM
            D3D12OitCaptureProfile.PARTICLE -> FeatureGeometryKind.PARTICLE
            else -> return null
        }
        if (facts.sources.any { it == null || !it.source.valid || it.source.kind != kind }) return null
        val corners: Int
        val triangles: Array<IntArray>
        when (execution.topology()) {
            PrimitiveTopology.QUADS -> { corners = 4; triangles = quadTriangles }
            PrimitiveTopology.TRIANGLES -> { corners = 3; triangles = triangle }
            else -> return null
        }
        if (history.vertexCount % corners != 0 ||
            history.vertexCount.toLong() / corners * triangles.size * 3L != draw.indexCount.toLong()) return null
        val sortedIndices = if (execution.customIndexBuffer() != null) {
            facts.sortedIndices?.takeIf { it.matches(execution, history.vertexCount, facts.stride) } ?: return null
        } else null
        if (sortedIndices != null && sortedIndices.primitives.size != draw.indexCount / 3) return null
        val view = source.materialView ?: return null
        val sampler = source.materialSampler ?: return null
        val material = OitTerrainMaterials.capture(view, sampler) ?: return null
        OitProducerCapture.captureSampledView(view, sampler) ?: return null
        val shading = OitFeatureShadingCapture.capture(pipeline, source, facts)
        val old = presented?.takeIf { prior ->
            facts.previousFrame === prior.frame && VanillaFrameCoordinator.latestPresentedWorldFrame() === prior.frame &&
                inputs.previous === prior.frame.current && prior.frame.world === inputs.world &&
                prior.frame.identity.worldEpoch == inputs.identity.worldEpoch &&
                prior.frame.identity.resourceVersion == inputs.identity.resourceVersion &&
                prior.frame.identity.configVersion == inputs.identity.configVersion &&
                prior.frame.historyGeneration == inputs.historyGeneration
        }
        val projection = old?.frame?.current?.let { camera ->
            Matrix4f().set(camera.rasterProjection()).mul(Matrix4f().set(camera.viewRotation()))
        }
        val output = ArrayList<OitFeatureTriangle>(draw.indexCount / 3)
        val seen = HashSet<OitFeatureSurfaceOwner>()
        for (first in 0 until history.vertexCount step corners) {
            val origin = checkNotNull(facts.sources[first])
            for (corner in 0 until corners) {
                val vertex = checkNotNull(facts.sources[first + corner])
                if (vertex.source !== origin.source || vertex.ordinal != origin.ordinal + corner) return null
            }
            for ((triangleIndex, indices) in triangles.withIndex()) {
                val owner = OitFeatureSurfaceOwner(origin.source.id, origin.source.generation,
                    origin.ordinal, triangleIndex)
                if (!seen.add(owner) || pending?.containsKey(owner) == true) return null
                val before = old?.surfaces?.get(owner)
                val uv = FloatArray(6)
                for ((corner, index) in indices.withIndex()) {
                    uv[corner * 2] = facts.materialUv[(first + index) * 2]
                    uv[corner * 2 + 1] = facts.materialUv[(first + index) * 2 + 1]
                }
                val surfaceId = before?.surfaceId ?: Math.incrementExact(nextSurfaceId).also { nextSurfaceId = it }
                val surface = OitFeatureSurface(owner, surfaceId, material, uv, shading?.triangle(first, indices))
                var previous: OitRecordPreviousTriangle? = null
                if (before != null && projection != null && indices.all {
                        history.previousPositions[(first + it) * 4 + 3] == 1f
                    }) {
                    val clip = FloatArray(12)
                    for ((corner, index) in indices.withIndex()) {
                        val offset = (first + index) * 4
                        val position = projection.transform(Vector4f(history.previousPositions[offset],
                            history.previousPositions[offset + 1], history.previousPositions[offset + 2], 1f))
                        clip[corner * 4] = position.x
                        clip[corner * 4 + 1] = position.y
                        clip[corner * 4 + 2] = position.z
                        clip[corner * 4 + 3] = position.w
                    }
                    if (clip.all(Float::isFinite)) {
                        val previousUv = before.materialUv()
                        val currentShading = surface.shading
                        val shadingMatches = currentShading != null && before.shading == currentShading &&
                            (previousUv.contentEquals(uv) || currentShading.vertices.all {
                                it == currentShading.vertices.first()
                            })
                        val validity = if (before.material == material && shadingMatches)
                            OitRecordValidities.of(OitRecordValidity.GEOMETRY_PREVIOUS, OitRecordValidity.MATERIAL_PREVIOUS)
                        else OitRecordValidities.of(OitRecordValidity.GEOMETRY_PREVIOUS)
                        previous = OitRecordPreviousTriangle(clip, previousUv, validity)
                    }
                }
                output.add(OitFeatureTriangle(origin.source, surface, previous, sortedIndices))
            }
        }
        val ordered = if (sortedIndices == null) output.toList() else sortedIndices.primitives.map { primitive ->
            output[primitive.firstVertex / corners * triangles.size + primitive.triangleRole]
        }
        return OitFeatureDraw(inputs, kind, ordered, sortedIndices)
    }

    fun recordDrawConsumption(draw: OitFeatureDraw) {
        check(draw.frame === frame) { "FEATURE draw changed frame before consumption" }
        val current = pending ?: LinkedHashMap<OitFeatureSurfaceOwner, OitFeatureTriangle>().also { pending = it }
        for (triangle in draw.triangles) {
            val existing = current.putIfAbsent(triangle.surface.owner, triangle)
            if (existing != null) {
                existing.source.invalidate()
                triangle.source.invalidate()
            }
        }
    }

    fun stagePresented(inputs: VanillaFrameInputs) {
        check(frame === inputs && staged == null) { "FEATURE presentation differs from its active frame" }
        staged = Presented(inputs, pending?.filterValues { it.valid }?.mapValues { it.value.surface } ?: emptyMap())
        pending = null
        frame = null
    }

    fun publishPresented(inputs: VanillaFrameInputs) {
        val candidate = checkNotNull(staged) { "FEATURE presentation has no staged surfaces" }
        check(candidate.frame === inputs) { "FEATURE presentation belongs to another frame" }
        presented = candidate
        staged = null
    }

    fun invalidate() {
        frame = null
        pending = null
        staged = null
        presented = null
    }
}
