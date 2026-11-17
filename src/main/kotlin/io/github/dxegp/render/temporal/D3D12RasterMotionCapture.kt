package io.github.dxegp.render.temporal

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.vertex.VertexFormat
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.mixin.temporal.RenderSetupMotionAccessor
import io.github.dxegp.mixin.temporal.RenderTypeMotionAccessor
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frameprep.FrameInputCoordinates
import io.github.dxegp.render.presentation.viewmodel.D3D12ViewmodelMotionCapture
import io.github.dxegp.render.temporal.celestial.CelestialMotionCapture
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.world.entity.Entity
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

object D3D12RasterMotionCapture {
    internal enum class MotionSpace { WORLD_CAMERA_RELATIVE, VIEWMODEL_POSE }
    internal data class GeometryFrame(val world: Any, val sequence: Long, val generation: Long,
        val hasPrevious: Boolean, val space: MotionSpace)
    internal class GeometryCapture internal constructor()

    class VertexHistory internal constructor(
        val firstVertex: Int,
        val vertexCount: Int,
        val previousPositions: FloatArray,
        val currentCameraX: Double,
        val currentCameraY: Double,
        val currentCameraZ: Double,
        val historyGeneration: Long,
        internal val space: MotionSpace,
    )

    private data class DrawKey(val entity: Long, val model: Any, val renderType: Any, val space: MotionSpace)
    private data class Key(val draw: DrawKey, val part: ModelPart, val cube: ModelPart.Cube)
    private data class Geometry(val frame: Long, val generation: Long, val positions: FloatArray,
        val source: FeatureGeometrySource?)
    private class FloatRows {
        private var values = FloatArray(64)
        var size = 0
            private set
        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun appendPosition(positionX: Float, positionY: Float, positionZ: Float) {
            val required = size + 3
            if (required > values.size) values = values.copyOf(maxOf(values.size * 2, required))
            values[size] = positionX
            values[size + 1] = positionY
            values[size + 2] = positionZ
            size = required
        }
        operator fun get(index: Int): Float = values[index]
        operator fun set(index: Int, value: Float) {
            values[index] = value
        }
        fun clear() {
            size = 0
        }
        fun toFloatArray(): FloatArray = values.copyOf(size)
    }
    private class ModelScope(val draw: DrawKey) {
        var part: ModelPart? = null
        var cube: ModelPart.Cube? = null
        var vertex = 0
        val positions = FloatRows()
        var key: Key? = null
        var previous: Geometry? = null
        var source: FeatureGeometrySource? = null
        val sources = ArrayList<FeatureGeometrySource>()
        val writtenRows = WrittenRows()
        val keys = HashSet<Key>()
        var firstWrittenRow = 0
    }
    private class BuilderRows {
        val rows = FloatRows()
        val sources = ArrayList<FeatureVertexSource?>()
        var completed: FloatArray? = null
    }
    private class GeometryScope(val capture: GeometryCapture, val previous: FloatArray?,
        val source: FeatureGeometrySource? = null) {
        val positions = FloatRows()
        val writtenRows = WrittenRows()
    }
    private class WrittenRows {
        private var builders = arrayOfNulls<BuilderRows>(32)
        private var offsets = IntArray(32)
        var size = 0
            private set

        fun add(builder: BuilderRows, offset: Int) {
            if (size == builders.size) {
                builders = builders.copyOf(builders.size * 2)
                offsets = offsets.copyOf(offsets.size * 2)
            }
            builders[size] = builder
            offsets[size++] = offset
        }

        fun invalidate(first: Int = 0) {
            for (index in first until size) {
                val builder = builders[index] ?: continue
                val offset = offsets[index] + 3
                builder.rows[offset] = 0.0f
                builder.completed?.let { if (offset < it.size) it[offset] = 0.0f }
            }
        }

        fun clear() {
            builders.fill(null, 0, size)
            size = 0
        }
    }
    private data class Published(val buffer: D3D12GpuBuffer, val byteOffset: Long,
        val stride: Int, val history: VertexHistory)
    private data class FeaturePublished(val buffer: D3D12GpuBuffer, val byteOffset: Long,
        val stride: Int, val facts: FeatureGeometryFacts)
    private data class EntityIdentity(val entity: WeakReference<Entity>, val identity: Long)
    private val entities = HashMap<Int, EntityIdentity>()
    private var nextEntity = 1L
    private var previous = HashMap<Key, Geometry>()
    private var current = HashMap<Key, Geometry>()
    private val models = HashMap<DrawKey, ModelScope>()
    private val builders = IdentityHashMap<BufferBuilder, BuilderRows>()
    private data class MeshFacts(val rows: FloatArray, val sources: List<FeatureVertexSource?>,
        val materialUv: FloatArray, val shading: List<FeatureVertexShading>)
    private val meshes = WeakHashMap<MeshData, MeshFacts>()
    private data class MeshRows(val count: Int, val rows: FloatArray?, val space: MotionSpace,
        val sources: List<FeatureVertexSource?>?, val materialUv: FloatArray?,
        val shading: List<FeatureVertexShading>?)
    private class ShadingAttribute(val offset: Int, val byteCount: Int) {
        fun read(bytes: ByteBuffer, vertexByteOffset: Int): Int? {
            val start = bytes.position().toLong() + vertexByteOffset + offset
            if (start < bytes.position() || start + byteCount > bytes.limit()) return null
            var packed = 0
            for (component in 0 until byteCount) {
                packed = packed or ((bytes.get(start.toInt() + component).toInt() and 0xff) shl (component * 8))
            }
            return packed
        }

        companion object {
            fun from(format: VertexFormat, name: String, expected: GpuFormat): ShadingAttribute? {
                val element = format.getElement(name)?.takeIf { it.format() == expected } ?: return null
                val size = expected.blockSize()
                if (element.offset() < 0 || element.offset() > format.vertexSize - size) return null
                return ShadingAttribute(element.offset(), if (expected == GpuFormat.RGBA8_SNORM) 3 else size)
            }
        }
    }
    private val draws = IdentityHashMap<StagedVertexBuffer.Draw, MutableList<MeshRows>>()
    private val published = ArrayList<Published>()
    private val featurePublished = ArrayList<FeaturePublished>()
    private var scope: ModelScope? = null
    private var geometryScope: GeometryScope? = null
    private var world: Any? = null
    private var frame = 0L
    private var generation = -1L
    private var cameraX = 0.0
    private var cameraY = 0.0
    private var cameraZ = 0.0
    private var collecting = false
    private var motionSpace = MotionSpace.WORLD_CAMERA_RELATIVE
    private var worldFrameInputs: VanillaFrameInputs? = null
    private var previousWorldFrameInputs: VanillaFrameInputs? = null
    private var worldPrevious = false
    private var worldComplete = false
    private var completedWorldFrame: VanillaFrameInputs? = null
    private var completedWorldMotion = false
    private var worldHistoryBindingSerial = 0L
    private var viewmodelPrevious = false
    private var viewmodelPreviousGeometry = HashMap<Key, Geometry>()
    private var viewmodelCurrentGeometry = HashMap<Key, Geometry>()
    private var viewmodelComplete = false
    private var viewmodelGeometry = 0
    private val viewmodelBoundHistory = IdentityHashMap<VertexHistory, Boolean>()

    internal fun geometryFrame(): GeometryFrame? {
        if (!collecting || !RenderSystem.isOnRenderThread()) return null
        val inputs = VanillaFrameCoordinator.currentFrame() ?: return null
        val currentWorld = world ?: return null
        if (currentWorld !== inputs.world || frame != inputs.identity.realFrameId ||
            generation != inputs.historyGeneration) return null
        return GeometryFrame(currentWorld, frame, generation,
            if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelPrevious else worldPrevious,
            motionSpace)
    }

    internal fun beginGeometry(previousPositions: FloatArray?): GeometryCapture? {
        if (geometryFrame() == null) return null
        check(geometryScope == null && scope?.cube == null) { "Raster motion geometry scopes must not overlap" }
        val capture = GeometryCapture()
        geometryScope = GeometryScope(capture, previousPositions)
        return capture
    }

    internal fun beginFeatureGeometry(previousPositions: FloatArray?, source: FeatureGeometrySource): GeometryCapture? {
        if (geometryFrame()?.space != MotionSpace.WORLD_CAMERA_RELATIVE) return beginGeometry(previousPositions)
        if (geometryFrame() == null) return null
        check(geometryScope == null && scope?.cube == null) { "Raster motion geometry scopes must not overlap" }
        val capture = GeometryCapture()
        geometryScope = GeometryScope(capture, previousPositions, source)
        return capture
    }

    internal fun endGeometry(capture: GeometryCapture, complete: Boolean = true): FloatArray? {
        val active = geometryScope ?: return null
        check(active.capture === capture) { "Raster motion geometry scope closed by a different producer" }
        geometryScope = null
        val positions = active.positions.toFloatArray()
        val finite = positions.all(Float::isFinite)
        if (!complete || !finite) active.source?.invalidate()
        if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelGeometry++
        if (!complete || active.previous?.size != positions.size || !finite) {
            active.writtenRows.invalidate()
            invalidateMotion()
        }
        return positions.takeIf { complete && finite && geometryFrame() != null }
    }

    fun beginWorldFrame() {
        if (!D3D12Velocity.isActive()) {
            if (collecting) reset()
            return
        }
        val inputs = VanillaFrameCoordinator.currentFrame() ?: run { reset(); return }
        val nextWorld = inputs.world
        val nextGeneration = inputs.historyGeneration
        if (world !== nextWorld || generation != nextGeneration) {
            reset()
            world = nextWorld
            generation = nextGeneration
        }
        if (frame == inputs.identity.realFrameId) return
        FeatureSortedIndexWrites.clear()
        val old = worldFrameInputs
        worldPrevious = inputs.previous != null && old != null && FrameInputCoordinates.previousFrameCompatible(
            old.identity.realFrameId, inputs.identity.realFrameId,
            old.identity.worldEpoch, inputs.identity.worldEpoch,
            old.identity.resourceVersion, inputs.identity.resourceVersion,
            old.historyGeneration, inputs.historyGeneration, old.world, inputs.world)
        previousWorldFrameInputs = old.takeIf { worldPrevious }
        worldFrameInputs = inputs
        worldComplete = worldPrevious
        completedWorldFrame = null
        completedWorldMotion = false
        worldHistoryBindingSerial = 0L
        collecting = true
        motionSpace = MotionSpace.WORLD_CAMERA_RELATIVE
        frame = inputs.identity.realFrameId
        val reusable = previous
        previous = current
        current = reusable
        current.clear()
        if (!worldPrevious) previous.clear()
        entities.entries.removeIf { it.value.entity.get() == null }
        models.clear()
        builders.clear()
        meshes.clear()
        draws.clear()
        published.clear()
        featurePublished.clear()
        scope = null
        geometryScope = null
        val camera = inputs.current
        cameraX = camera.originX
        cameraY = camera.originY
        cameraZ = camera.originZ
    }

    internal fun endWorldFrame() {
        if (!collecting || motionSpace != MotionSpace.WORLD_CAMERA_RELATIVE) return
        val inputs = worldFrameInputs
        completedWorldFrame = inputs
        completedWorldMotion = inputs != null && inputs === VanillaFrameCoordinator.currentFrame() &&
            worldComplete && scope == null && geometryScope == null
        collecting = false
    }

    internal fun worldMotionComplete(): Boolean = completedWorldMotion &&
        completedWorldFrame === VanillaFrameCoordinator.currentFrame()

    internal fun worldHistoryBindingSerial(): Long = worldHistoryBindingSerial

    internal fun recordWorldMotionBinding() {
        worldHistoryBindingSerial++
    }

    internal fun bindCelestialHistory() {
        check(geometryFrame()?.space == MotionSpace.WORLD_CAMERA_RELATIVE) {
            "Celestial history was bound outside world capture"
        }
        worldHistoryBindingSerial++
    }

    internal fun worldDrawRecorded(pipeline: D3D12CompiledRenderPipeline, bindingSerialBeforeDraw: Long) {
        if (!collecting || motionSpace != MotionSpace.WORLD_CAMERA_RELATIVE) return
        if ("OIT" in pipeline.info().shaderDefines.flags() &&
            pipeline.info().location != net.minecraft.client.renderer.RenderPipelines.OIT_COMPOSITE.location) return
        if (pipeline.rasterMotionPso != 0L && worldHistoryBindingSerial > bindingSerialBeforeDraw) return
        if (!pipeline.cameraReprojectable) {
            worldComplete = false
        }
    }

    internal fun beginViewmodelFrame(inputs: VanillaFrameInputs, hasPrevious: Boolean) {
        check(scope == null && geometryScope == null) { "Viewmodel capture began inside another geometry source" }
        world = inputs.world
        frame = inputs.identity.realFrameId
        generation = inputs.historyGeneration
        motionSpace = MotionSpace.VIEWMODEL_POSE
        collecting = true
        viewmodelPrevious = hasPrevious
        viewmodelComplete = hasPrevious
        viewmodelGeometry = 0
        viewmodelBoundHistory.clear()
        if (!hasPrevious) viewmodelPreviousGeometry.clear()
        viewmodelCurrentGeometry.clear()
        models.clear()
        builders.clear()
        meshes.clear()
        draws.clear()
        published.clear()
        featurePublished.clear()
    }

    internal fun endViewmodelFrame() {
        check(scope == null && geometryScope == null) { "Viewmodel capture ended with unfinished geometry" }
        collecting = false
    }

    internal fun viewmodelMotionComplete(): Boolean = viewmodelComplete && viewmodelGeometry > 0 &&
        published.isNotEmpty() && published.all { viewmodelBoundHistory.containsKey(it.history) }

    internal fun invalidateViewmodelMotion() {
        if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelComplete = false
    }

    internal fun invalidateMotion() {
        when (motionSpace) {
            MotionSpace.WORLD_CAMERA_RELATIVE -> {
                scope?.writtenRows?.invalidate()
                geometryScope?.writtenRows?.invalidate()
            }
            MotionSpace.VIEWMODEL_POSE -> viewmodelComplete = false
        }
    }

    internal fun commitViewmodelFrame() {
        val reusable = viewmodelPreviousGeometry
        viewmodelPreviousGeometry = viewmodelCurrentGeometry
        viewmodelCurrentGeometry = reusable
        viewmodelCurrentGeometry.clear()
    }

    internal fun invalidateViewmodelFrame() {
        viewmodelPreviousGeometry.clear()
        viewmodelCurrentGeometry.clear()
        viewmodelBoundHistory.clear()
        viewmodelPrevious = false
        if (motionSpace == MotionSpace.VIEWMODEL_POSE) {
            collecting = false
            scope = null
            geometryScope = null
            models.clear()
            builders.clear()
            meshes.clear()
            draws.clear()
            published.clear()
        }
    }

    fun beginModel(submit: ModelFeatureRenderer.Submit<*>) {
        scope = null
        if (geometryFrame() == null) return
        val setup = (submit.renderType() as RenderTypeMotionAccessor).illusionMotionState()
        if ((setup as RenderSetupMotionAccessor).illusionMotionLayering().modifier != null) {
            invalidateMotion()
            return
        }
        val key = if (motionSpace == MotionSpace.VIEWMODEL_POSE) {
            val source = D3D12ViewmodelMotionCapture.source(submit) ?: return
            DrawKey(source.player, source.model, source.renderType, motionSpace)
        } else {
            val state = submit.state() as? EntityRenderState
            val entity = state?.let(D3D12ObjectMotion::extractedEntity)
            if (entity == null) {
                invalidateMotion()
                return
            }
            val existing = entities[entity.id]
            val identity = if (existing?.entity?.get() === entity) existing.identity else {
                val allocated = nextEntity++
                entities[entity.id] = EntityIdentity(WeakReference(entity), allocated)
                allocated
            }
            DrawKey(identity, submit.model(), submit.renderType(), motionSpace)
        }
        val existingScope = models[key]
        if (existingScope != null) {
            rejectModel(existingScope)
            return
        }
        scope = ModelScope(key).also { models[key] = it }
    }

    fun endModel(complete: Boolean = true) {
        if (!complete) scope?.let(::rejectModel)
        scope = null
    }

    private fun rejectModel(active: ModelScope) {
        active.sources.forEach(FeatureGeometrySource::invalidate)
        invalidateMotion()
        active.writtenRows.invalidate()
        val destination = if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelCurrentGeometry else current
        for (key in active.keys) destination.remove(key)
        scope = null
    }

    fun beginPart(part: ModelPart) {
        scope?.part = part
    }

    fun beginCube(cube: ModelPart.Cube) {
        val active = scope ?: return
        val part = active.part ?: return
        active.cube = cube
        active.vertex = 0
        active.positions.clear()
        active.firstWrittenRow = active.writtenRows.size
        val key = Key(active.draw, part, cube)
        if (!active.keys.add(key)) {
            rejectModel(active)
            return
        }
        active.key = key
        val history = if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelPreviousGeometry else previous
        active.previous = history[key]?.takeIf { it.frame + 1L == frame && it.generation == generation }
        active.source = if (motionSpace == MotionSpace.WORLD_CAMERA_RELATIVE) {
            FeatureGeometrySource.next(FeatureGeometryKind.ENTITY, active.previous?.source).also(active.sources::add)
        } else null
    }

    fun endCube() {
        val active = scope ?: return
        val key = active.key ?: return
        val positions = active.positions.toFloatArray()
        val finite = positions.all(Float::isFinite)
        val source = active.source
        if (!finite) source?.invalidate()
        if (active.previous != null && active.previous?.positions?.size != positions.size) source?.advanceGeneration()
        if (active.previous?.positions?.size != positions.size || !finite) {
            active.writtenRows.invalidate(active.firstWrittenRow)
            invalidateMotion()
        }
        val destination = if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelCurrentGeometry else current
        if (finite) destination[key] = Geometry(frame, generation, positions, source)
        if (motionSpace == MotionSpace.VIEWMODEL_POSE) viewmodelGeometry++
        active.key = null
        active.previous = null
        active.source = null
        active.cube = null
    }

    fun vertex(builder: BufferBuilder, index: Int, x: Float, y: Float, z: Float) {
        if (!collecting || !RenderSystem.isOnRenderThread()) return
        val active = scope
        val geometry = geometryScope
        val rows = builders[builder] ?: if (active?.cube != null || geometry != null) {
            BuilderRows().also { builders[builder] = it }
        } else return
        if (world == null || index < 0) return
        val row = index * 4
        if (rows.rows.size > row) return
        while (rows.rows.size <= row + 3) rows.rows.add(0.0f)
        while (rows.sources.size <= index) rows.sources.add(null)
        if (geometry != null) {
            val offset = geometry.positions.size
            geometry.source?.let { rows.sources[index] = FeatureVertexSource(it, offset / 3) }
            geometry.positions.appendPosition(x, y, z)
            val old = geometry.previous ?: return
            if (offset + 2 >= old.size) return
            writePrevious(rows, row, old, offset, x, y, z, geometry.writtenRows)
            return
        }
        if (active == null) return
        if (active.cube == null) return
        val ordinal = active.vertex++
        active.source?.let { rows.sources[index] = FeatureVertexSource(it, ordinal) }
        active.positions.appendPosition(x, y, z)
        val old = active.previous ?: return
        val offset = ordinal * 3
        if (offset + 2 >= old.positions.size) return
        writePrevious(rows, row, old.positions, offset, x, y, z, active.writtenRows)
    }

    private fun writePrevious(rows: BuilderRows, row: Int, positions: FloatArray, offset: Int,
        x: Float, y: Float, z: Float, writtenRows: WrittenRows) {
        val previousX = positions[offset]
        val previousY = positions[offset + 1]
        val previousZ = positions[offset + 2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() ||
            !previousX.isFinite() || !previousY.isFinite() || !previousZ.isFinite()) return
        rows.rows[row] = previousX
        rows.rows[row + 1] = previousY
        rows.rows[row + 2] = previousZ
        rows.rows[row + 3] = 1.0f
        writtenRows.add(rows, row)
    }

    fun built(builder: BufferBuilder, mesh: MeshData?) {
        if (!collecting || !RenderSystem.isOnRenderThread()) return
        val rows = builders.remove(builder) ?: return
        if (mesh == null) return
        val count = mesh.drawState().vertexCount()
        val output = FloatArray(count * 4)
        val available = minOf(output.size, rows.rows.size)
        for (index in 0 until available) output[index] = rows.rows[index]
        rows.completed = output
        val primitiveVertices = when (mesh.drawState().primitiveTopology()) {
            PrimitiveTopology.QUADS -> 4
            PrimitiveTopology.TRIANGLES -> 3
            else -> 0
        }
        if (primitiveVertices == 0 || count % primitiveVertices != 0) {
            for (index in 3 until output.size step 4) output[index] = 0.0f
        } else {
            for (primitive in 0 until count step primitiveVertices) {
                if ((primitive until primitive + primitiveVertices).any { output[it * 4 + 3] != 1.0f }) {
                    for (vertex in primitive until primitive + primitiveVertices) output[vertex * 4 + 3] = 0.0f
                }
            }
        }
        val format = mesh.drawState().format()
        val element = format.getElement("UV0")
        val bytes = mesh.vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val uv = FloatArray(count * 2) { Float.NaN }
        if (element != null && element.format() == GpuFormat.RG32_FLOAT && element.offset() >= 0 &&
            element.offset() + 8 <= format.vertexSize && count.toLong() * format.vertexSize <= bytes.remaining().toLong()) {
            val start = bytes.position()
            for (vertex in 0 until count) {
                val offset = start + vertex * format.vertexSize + element.offset()
                uv[vertex * 2] = bytes.getFloat(offset)
                uv[vertex * 2 + 1] = bytes.getFloat(offset + 4)
            }
        }
        val sources = List(count) { vertex ->
            rows.sources.getOrNull(vertex).takeIf { uv[vertex * 2].isFinite() && uv[vertex * 2 + 1].isFinite() }
        }
        val color = ShadingAttribute.from(format, "Color", GpuFormat.RGBA8_UNORM)
        val overlay = ShadingAttribute.from(format, "UV1", GpuFormat.RG16_SINT)
        val lightmap = ShadingAttribute.from(format, "UV2", GpuFormat.RG16_SINT)
        val normal = ShadingAttribute.from(format, "Normal", GpuFormat.RGBA8_SNORM)
        val shading = List(count) { vertex ->
            if (sources[vertex] == null) FeatureVertexShading.UNKNOWN else {
                val offset = vertex * format.vertexSize
                FeatureVertexShading(color?.read(bytes, offset), overlay?.read(bytes, offset),
                    lightmap?.read(bytes, offset), normal?.read(bytes, offset))
            }
        }
        meshes[mesh] = MeshFacts(output, sources, uv, Collections.unmodifiableList(shading))
    }

    fun appended(draw: StagedVertexBuffer.Draw, mesh: MeshData) {
        if (!collecting) return
        val count = mesh.drawState().vertexCount()
        val facts = meshes.remove(mesh)
        if (count > 0 && facts == null) invalidateMotion()
        draws.getOrPut(draw) { ArrayList() }.add(MeshRows(count, facts?.rows, motionSpace,
            facts?.sources, facts?.materialUv, facts?.shading))
    }

    internal fun publish(draw: StagedVertexBuffer.Draw, info: StagedVertexBuffer.ExecuteInfo?, stride: Int,
        sortedIndices: FeatureSortedIndexMapping?) {
        if (!collecting || info == null || stride <= 0) return
        val buffer = info.vertexBuffer() as? D3D12GpuBuffer ?: return
        val byteOffset = info.baseVertex().toLong() * stride
        if (published.any { it.buffer === buffer && it.byteOffset == byteOffset }) return
        val pieces = draws[draw] ?: return
        if (pieces.any { it.space != motionSpace }) {
            invalidateMotion()
            return
        }
        if (pieces.any { piece -> piece.count > 0 && (piece.rows == null ||
                piece.rows.size != piece.count * 4 ||
                (3 until piece.rows.size step 4).any { piece.rows[it] != 1.0f }) }) invalidateMotion()
        val hasPreviousRows = pieces.any { piece -> piece.rows?.let { values ->
            (3 until values.size step 4).any { values[it] != 0.0f }
        } == true }
        val hasFeatureSources = motionSpace == MotionSpace.WORLD_CAMERA_RELATIVE && pieces.any { piece ->
            piece.sources?.any { it != null } == true }
        if (!hasPreviousRows && !hasFeatureSources) return
        val single = pieces.singleOrNull()
        val rows = single?.rows?.takeIf { it.size == single.count * 4 }
            ?: FloatArray(pieces.sumOf { it.count } * 4).also { merged ->
                var offset = 0
                for (piece in pieces) {
                    piece.rows?.copyInto(merged, offset)
                    offset += piece.count * 4
                }
            }
        val history = VertexHistory(info.baseVertex(), rows.size / 4, rows, cameraX, cameraY, cameraZ,
            generation, motionSpace)
        if (hasFeatureSources) {
            val inputs = worldFrameInputs
            if (inputs != null && featurePublished.none { it.buffer === buffer && it.byteOffset == byteOffset }) {
                val sources = ArrayList<FeatureVertexSource?>(history.vertexCount)
                val shading = ArrayList<FeatureVertexShading>(history.vertexCount)
                val uv = FloatArray(history.vertexCount * 2) { Float.NaN }
                var offset = 0
                for (piece in pieces) {
                    for (vertex in 0 until piece.count) {
                        sources.add(piece.sources?.getOrNull(vertex))
                        shading.add(piece.shading?.getOrNull(vertex) ?: FeatureVertexShading.UNKNOWN)
                    }
                    piece.materialUv?.takeIf { it.size == piece.count * 2 }?.copyInto(uv, offset * 2)
                    offset += piece.count
                }
                featurePublished.add(FeaturePublished(buffer, byteOffset, stride,
                    FeatureGeometryFacts(inputs, previousWorldFrameInputs, info, stride, history,
                        Collections.unmodifiableList(sources), uv, Collections.unmodifiableList(shading), sortedIndices)))
            }
        }
        if (!hasPreviousRows) return
        published.add(Published(buffer, byteOffset, stride, history))
    }

    fun lookup(buffer: GpuBuffer, vertexByteOffset: Long, vertexStride: Int): VertexHistory? {
        if (geometryFrame() == null) return null
        return published.firstOrNull { it.buffer === buffer && it.byteOffset == vertexByteOffset &&
            it.stride == vertexStride }?.history
    }

    internal fun lookupFeature(buffer: GpuBuffer, vertexByteOffset: Long, vertexStride: Int): FeatureGeometryFacts? {
        if (geometryFrame()?.space != MotionSpace.WORLD_CAMERA_RELATIVE) return null
        return featurePublished.firstOrNull { it.buffer === buffer && it.byteOffset == vertexByteOffset &&
            it.stride == vertexStride }?.facts
    }

    internal fun bindHistory(history: VertexHistory) {
        when (history.space) {
            MotionSpace.WORLD_CAMERA_RELATIVE -> worldHistoryBindingSerial++
            MotionSpace.VIEWMODEL_POSE -> viewmodelBoundHistory[history] = true
        }
    }

    fun reset() {
        FeatureSortedIndexWrites.clear()
        CelestialMotionCapture.reset()
        previous.clear()
        current.clear()
        models.clear()
        entities.clear()
        builders.clear()
        meshes.clear()
        draws.clear()
        published.clear()
        featurePublished.clear()
        scope = null
        geometryScope = null
        world = null
        generation = -1L
        frame = 0L
        collecting = false
        worldFrameInputs = null
        previousWorldFrameInputs = null
        worldPrevious = false
        worldComplete = false
        completedWorldFrame = null
        completedWorldMotion = false
        worldHistoryBindingSerial = 0L
        invalidateViewmodelFrame()
        motionSpace = MotionSpace.WORLD_CAMERA_RELATIVE
    }
}
