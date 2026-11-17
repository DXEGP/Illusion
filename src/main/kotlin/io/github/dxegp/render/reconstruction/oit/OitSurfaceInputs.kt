package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import net.minecraft.client.CloudStatus
import net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance
import net.minecraft.client.renderer.state.level.WeatherRenderState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4fc
import org.joml.Vector3fc
import org.joml.Vector4fc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap

internal enum class OitSurfaceKind { CLOUD, RAIN, SNOW, BORDER }

internal enum class OitWeatherRandom { RAIN_SPEED, SNOW_DOUBLE_U, SNOW_DOUBLE_V, SNOW_GAUSSIAN_U, SNOW_GAUSSIAN_V }

internal enum class OitSurfaceValidity {
    VALID, NO_PREVIOUS_PRESENT, NEW_SURFACE, RESOURCE_CHANGED, MATERIAL_DISCONTINUITY,
    UNCERTIFIED, NON_FINITE,
}

internal data class OitSurfaceOwner(
    val worldEpoch: Long,
    val producerId: Long,
    val producerGeneration: Long,
    val kind: OitSurfaceKind,
    val cellX: Long,
    val cellZ: Long,
    val role: Int,
)

internal data class OitSurfaceOrigin(val x: Double, val y: Double, val z: Double) {
    constructor(position: Vec3) : this(position.x, position.y, position.z)
}

internal data class OitSurfaceVertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float) {
    val finite: Boolean
        get() = x.isFinite() && y.isFinite() && z.isFinite() && u.isFinite() && v.isFinite()
}

internal data class OitCloudFace(val x: Int, val z: Int, val direction: Int, val flags: Int) {
    val role: Int
        get() = direction or flags.shl(3)
}

internal class OitCloudMesh(faces: List<OitCloudFace>) {
    val faces: List<OitCloudFace> = Collections.unmodifiableList(ArrayList(faces))
}

internal class OitCloudPreparation(
    val producerId: Long,
    val generation: Long,
    val width: Int,
    val textureHeight: Int,
    val ticks: Long,
    val partialTick: Float,
    val height: Float,
    val status: CloudStatus,
    val origin: OitSurfaceOrigin,
    val centerX: Long,
    val centerZ: Long,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    val cellSizeX: Float,
    val cellSizeY: Float,
    val cellSizeZ: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
    val mesh: OitCloudMesh,
) {
    val faces: List<OitCloudFace>
        get() = mesh.faces
    val finite: Boolean = ticks >= 0L && partialTick.isFinite() && height.isFinite() && origin.x.isFinite() &&
        origin.y.isFinite() && origin.z.isFinite() && offsetX.isFinite() && offsetY.isFinite() &&
        offsetZ.isFinite() && cellSizeX.isFinite() && cellSizeY.isFinite() && cellSizeZ.isFinite() &&
        red.isFinite() && green.isFinite() && blue.isFinite() && alpha.isFinite()
}

internal class OitWeatherPhase(
    val kind: OitSurfaceKind,
    val ticks: Long,
    val partialTick: Float,
    val rawU: Float,
    val rawV: Float,
    val fallV: Float,
    val speed: Float,
    randomValues: List<Double>,
) {
    val randomValues: List<Double> = Collections.unmodifiableList(ArrayList(randomValues))
    val finite: Boolean
        get() = ticks >= 0L && partialTick.isFinite() && rawU.isFinite() && rawV.isFinite() &&
            fallV.isFinite() && speed.isFinite() && randomValues.all(Double::isFinite)
}

internal class OitWeatherQuad(
    val kind: OitSurfaceKind,
    val x: Int,
    val z: Int,
    val bottomY: Int,
    val topY: Int,
    val uOffset: Float,
    val vOffset: Float,
    val color: Int,
    val light: Int,
    val phase: OitWeatherPhase?,
    val origin: OitSurfaceOrigin,
    vertices: List<OitSurfaceVertex>,
) {
    val vertices: List<OitSurfaceVertex> = Collections.unmodifiableList(ArrayList(vertices))
    val finite: Boolean
        get() = topY > bottomY && vertices.size == 4 && vertices.all { it.finite }
}

internal data class OitSurfaceSampler(
    val addressU: AddressMode,
    val addressV: AddressMode,
    val minFilter: FilterMode,
    val magFilter: FilterMode,
    val anisotropy: Int,
    val maxLod: Double?,
) {
    constructor(sampler: GpuSampler) : this(sampler.getAddressModeU(), sampler.getAddressModeV(),
        sampler.getMinFilter(), sampler.getMagFilter(), sampler.getMaxAnisotropy(),
        sampler.getMaxLod().let { if (it.isPresent) it.asDouble else null })
}

internal sealed interface OitSurfaceSnapshot {
    val finite: Boolean
}
internal class OitCloudSurface(val preparation: OitCloudPreparation, val face: OitCloudFace) : OitSurfaceSnapshot {
    override val finite: Boolean
        get() = preparation.finite
}
internal class OitWeatherSurface(val quad: OitWeatherQuad, val sampled: OitSampledView,
    val sampler: OitSurfaceSampler, val material: OitSurfaceMaterial) : OitSurfaceSnapshot {
    override val finite: Boolean
        get() = quad.finite
}

internal class OitBorderMesh(val generation: Long, vertices: List<OitSurfaceVertex>) {
    val vertices: List<OitSurfaceVertex> = Collections.unmodifiableList(ArrayList(vertices))
}

internal class OitBorderTransform(val millis: Long, modelView: Matrix4fc, texture: Matrix4fc,
    val offset: OitSurfaceOrigin, val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    private val modelViewValues = modelView.get(FloatArray(16))
    private val textureValues = texture.get(FloatArray(16))
    val finite = millis >= 0L && modelViewValues.all(Float::isFinite) && textureValues.all(Float::isFinite) &&
        offset.x.isFinite() && offset.y.isFinite() && offset.z.isFinite() && red.isFinite() &&
        green.isFinite() && blue.isFinite() && alpha.isFinite()

    fun modelView(): FloatArray = modelViewValues.copyOf()
    fun texture(): FloatArray = textureValues.copyOf()

    val certifiedTexturePhase: Boolean
        get() {
            val phase = (millis % 3000L).toFloat() / 3000f
            return textureValues.indices.all { index ->
                textureValues[index] == when (index) {
                    0, 5, 10, 15 -> 1f
                    12, 13 -> phase
                    else -> 0f
                }
            }
        }
}

internal class OitBorderSurface(val mesh: OitBorderMesh, val side: Int, val transform: OitBorderTransform,
    val sampler: OitSurfaceSampler, val material: OitSurfaceMaterial) : OitSurfaceSnapshot {
    override val finite: Boolean
        get() = side in 0..3 && mesh.vertices.size == 16 && mesh.vertices.all { it.finite } && transform.finite
}

internal data class OitSurfaceMaterial(val id: Long, val generation: Long, val textureGeneration: Long)

internal class OitSurfacePair(
    val owner: OitSurfaceOwner,
    val surfaceId: Long,
    val current: OitSurfaceSnapshot,
    val previous: OitSurfaceSnapshot?,
    val geometryValidity: OitSurfaceValidity,
    val materialValidity: OitSurfaceValidity,
    val phaseDeltaU: Double,
    val phaseDeltaV: Double,
)

internal class OitSurfaceDraw(
    val frame: VanillaFrameInputs,
    val previousFrame: VanillaFrameInputs?,
    val producerId: Long,
    val producerGeneration: Long,
    val firstVertex: Int,
    surfaces: List<OitSurfacePair>,
    val material: OitSampledView?,
) {
    val schemaVersion = 1
    val surfaces: List<OitSurfacePair> = Collections.unmodifiableList(ArrayList(surfaces))
}

internal object OitSurfaceInputs {
    private class WeatherMaterial(val id: Long, sampled: OitSampledView, sampler: OitSurfaceSampler) {
        private var texture = sampled.view.texture
        private var baseMipLevel = sampled.view.baseMipLevel
        private var mipLevels = sampled.view.mipLevels
        private var sampling = sampler
        private var material = OitSurfaceMaterial(id, 1L, 1L)

        fun capture(sampled: OitSampledView, sampler: OitSurfaceSampler): OitSurfaceMaterial {
            val replacedTexture = texture !== sampled.view.texture
            if (replacedTexture || baseMipLevel != sampled.view.baseMipLevel || mipLevels != sampled.view.mipLevels ||
                sampling != sampler) {
                material = OitSurfaceMaterial(id, Math.incrementExact(material.generation),
                    if (replacedTexture) Math.incrementExact(material.textureGeneration) else material.textureGeneration)
                texture = sampled.view.texture
                baseMipLevel = sampled.view.baseMipLevel
                mipLevels = sampled.view.mipLevels
                sampling = sampler
            }
            return material
        }
    }

    private class WeatherPhaseCapture {
        var kind = OitSurfaceKind.RAIN
        var ticks = 0L
        var partialTick = 0f
        var rawU = 0f
        var rawV = 0f
        var fallV = 0f
        var speed = 0f
        var randomU = 0.0
        var randomV = 0.0
        var gaussianU = 0.0
        var gaussianV = 0.0

        fun snapshot(): OitWeatherPhase = OitWeatherPhase(kind, ticks, partialTick, rawU, rawV, fallV, speed,
            if (kind == OitSurfaceKind.RAIN) listOf(randomU) else listOf(randomU, randomV, gaussianU, gaussianV))
    }

    private class WeatherExtraction {
        var state: WeatherRenderState? = null
        val phases = IdentityHashMap<ColumnInstance, WeatherPhaseCapture>()
        val columns = ArrayList<WeatherPhaseCapture>()
        var count = 0
        var rainRandom = 0.0
        var snowDoubleU = 0.0
        var snowDoubleV = 0.0
        var snowGaussianU = 0.0
        var snowGaussianV = 0.0

        fun captureColumn(column: ColumnInstance): WeatherPhaseCapture {
            if (count == columns.size) columns.add(WeatherPhaseCapture())
            return columns[count++].also { phases[column] = it }
        }
    }

    private class WeatherQuadCapture {
        var kind = OitSurfaceKind.RAIN
        var column: ColumnInstance? = null
        var phase: WeatherPhaseCapture? = null
        var color = 0
        var x0 = 0f
        var x1 = 0f
        var top = 0f
        var bottom = 0f
        var z0 = 0f
        var z1 = 0f
        var u0 = 0f
        var u1 = 0f
        var v0 = 0f
        var v1 = 0f

        fun snapshot(origin: OitSurfaceOrigin): OitWeatherQuad {
            val current = checkNotNull(column)
            return OitWeatherQuad(kind, current.x(), current.z(), current.bottomY(), current.topY(),
                current.uOffset(), current.vOffset(), color, current.lightCoords(), phase?.snapshot(), origin,
                listOf(OitSurfaceVertex(x0, top, z0, u0, v0), OitSurfaceVertex(x1, top, z1, u1, v0),
                    OitSurfaceVertex(x1, bottom, z1, u1, v1), OitSurfaceVertex(x0, bottom, z0, u0, v1)))
        }
    }

    private class WeatherGeometry {
        val quads = ArrayList<WeatherQuadCapture>()
        var count = 0
        var origin = OitSurfaceOrigin(0.0, 0.0, 0.0)
    }

    private class WeatherPreparation(val state: WeatherRenderState, val extraction: WeatherExtraction?,
        val geometry: WeatherGeometry) {
        var kind: OitSurfaceKind? = null
    }

    private class CloudRecording(val ticks: Long, val partialTick: Float, val height: Float,
        val status: CloudStatus, val origin: OitSurfaceOrigin) {
        var width = 0
        var textureHeight = 0
        val floors = ArrayList<Int>(4)
        val vectors = ArrayList<Triple<Float, Float, Float>>(2)
        var color: List<Float>? = null
        var mesh: ArrayList<OitCloudFace>? = null
    }

    private class ProducerState(val id: Long) {
        var generation = 1L
        val weatherExtraction = WeatherExtraction()
        val weatherGeometry = WeatherGeometry()
        val weatherMaterials = java.util.EnumMap<OitSurfaceKind, WeatherMaterial>(OitSurfaceKind::class.java)
        var extraction: WeatherExtraction? = null
        var weatherPreparation: WeatherPreparation? = null
        var weather: WeatherGeometry? = null
        var cloudRecording: CloudRecording? = null
        var cloudMesh: OitCloudMesh? = null
        var cloud: OitCloudPreparation? = null
        var borderMesh: OitBorderMesh? = null
        var borderMeshGeneration = 0L
    }

    private class WeatherBatch(val renderer: Any, val startColumn: Int, val columnCount: Int,
        val geometry: WeatherGeometry, val view: GpuTextureView, val sampler: GpuSampler)
    private class BorderBatch(val renderer: Any) {
        var millis: Long? = null
        var transform: OitBorderTransform? = null
        var view: GpuTextureView? = null
        var sampler: GpuSampler? = null
    }
    private class Presented(val frame: VanillaFrameInputs, val surfaces: Map<OitSurfaceOwner, OitSurfacePair>,
        val clouds: Map<Long, OitCloudPreparation>)

    private val producers = IdentityHashMap<Any, ProducerState>()
    private var nextIdentity = 0L
    private var frame: VanillaFrameInputs? = null
    private var presented: Presented? = null
    private var stagedPresented: Presented? = null
    private var pending: MutableMap<OitSurfaceOwner, OitSurfacePair>? = null
    private var pendingClouds: MutableMap<Long, OitCloudPreparation>? = null
    private var weatherBatch: WeatherBatch? = null
    private var borderBatch: BorderBatch? = null

    fun beginFrame(inputs: VanillaFrameInputs) {
        check(frame == null) { "OIT surface frame began before retirement" }
        frame = inputs
        pending = null
        pendingClouds = null
        OitFeatureInputs.beginFrame(inputs)
    }

    fun <Result> recordBorderMesh(renderer: Any, vertices: ByteBuffer, action: () -> Result): Result {
        val producer = producers.getOrPut(renderer) { ProducerState(Math.incrementExact(nextIdentity).also { nextIdentity = it }) }
        producer.borderMesh = null
        val source = vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (source.remaining() != 16 * 5 * Float.SIZE_BYTES) return action()
        val captured = List(16) {
            OitSurfaceVertex(source.getFloat(), source.getFloat(), source.getFloat(), source.getFloat(), source.getFloat())
        }
        val result = action()
        producer.borderMeshGeneration = Math.incrementExact(producer.borderMeshGeneration)
        producer.borderMesh = OitBorderMesh(producer.borderMeshGeneration, captured)
        return result
    }

    fun <Result> recordBorderDraw(renderer: Any, action: () -> Result): Result {
        if (frame == null || producers[renderer]?.borderMesh == null) return action()
        check(borderBatch == null) { "World border draws overlap" }
        borderBatch = BorderBatch(renderer)
        try {
            return action()
        } finally {
            borderBatch = null
        }
    }

    fun recordBorderMillis(renderer: Any, millis: Long) {
        borderBatch?.takeIf { it.renderer === renderer }?.millis = millis
    }

    fun recordBorderTransform(renderer: Any, modelView: Matrix4fc, color: Vector4fc,
        offset: Vector3fc, texture: Matrix4fc) {
        val batch = borderBatch?.takeIf { it.renderer === renderer } ?: return
        val millis = batch.millis ?: return
        batch.transform = OitBorderTransform(millis, modelView, texture,
            OitSurfaceOrigin(offset.x().toDouble(), offset.y().toDouble(), offset.z().toDouble()),
            color.x(), color.y(), color.z(), color.w())
    }

    fun recordBorderMaterial(renderer: Any, view: GpuTextureView, sampler: GpuSampler) {
        val batch = borderBatch?.takeIf { it.renderer === renderer } ?: return
        batch.view = view
        batch.sampler = sampler
    }

    fun invalidateBorderMesh(renderer: Any) {
        producers[renderer]?.borderMesh = null
    }

    fun <Result> recordWeatherExtraction(renderer: Any, state: WeatherRenderState, action: () -> Result): Result {
        val producer = producers.getOrPut(renderer) { ProducerState(Math.incrementExact(nextIdentity).also { nextIdentity = it }) }
        val extraction = producer.weatherExtraction
        extraction.state = state
        extraction.phases.clear()
        extraction.count = 0
        producer.extraction = extraction
        producer.weather = null
        try {
            return action()
        } catch (failure: Throwable) {
            producer.extraction = null
            throw failure
        }
    }

    fun captureWeatherRandom(renderer: Any, random: OitWeatherRandom, value: Double) {
        val extraction = producers[renderer]?.extraction ?: return
        when (random) {
            OitWeatherRandom.RAIN_SPEED -> extraction.rainRandom = value
            OitWeatherRandom.SNOW_DOUBLE_U -> extraction.snowDoubleU = value
            OitWeatherRandom.SNOW_DOUBLE_V -> extraction.snowDoubleV = value
            OitWeatherRandom.SNOW_GAUSSIAN_U -> extraction.snowGaussianU = value
            OitWeatherRandom.SNOW_GAUSSIAN_V -> extraction.snowGaussianV = value
        }
    }

    fun captureRainPhase(renderer: Any, column: ColumnInstance, ticks: Long, partialTick: Float,
        rawV: Float, speed: Float) {
        val extraction = producers[renderer]?.extraction ?: return
        val phase = extraction.captureColumn(column)
        phase.kind = OitSurfaceKind.RAIN
        phase.ticks = ticks
        phase.partialTick = partialTick
        phase.rawU = 0f
        phase.rawV = rawV
        phase.fallV = 0f
        phase.speed = speed
        phase.randomU = extraction.rainRandom
    }

    fun captureSnowPhase(renderer: Any, column: ColumnInstance, ticks: Long, partialTick: Float,
        rawU: Float, rawV: Float, fallV: Float) {
        val extraction = producers[renderer]?.extraction ?: return
        val phase = extraction.captureColumn(column)
        phase.kind = OitSurfaceKind.SNOW
        phase.ticks = ticks
        phase.partialTick = partialTick
        phase.rawU = rawU
        phase.rawV = rawV
        phase.fallV = fallV
        phase.speed = 0f
        phase.randomU = extraction.snowDoubleU
        phase.randomV = extraction.snowDoubleV
        phase.gaussianU = extraction.snowGaussianU
        phase.gaussianV = extraction.snowGaussianV
    }

    fun <Result> recordWeatherPreparation(renderer: Any, state: WeatherRenderState, camera: Vec3,
        action: () -> Result): Result {
        val producer = producers.getOrPut(renderer) { ProducerState(Math.incrementExact(nextIdentity).also { nextIdentity = it }) }
        check(producer.weatherPreparation == null) { "Weather preparations overlap" }
        val extraction = producer.extraction?.takeIf { it.state === state }
        val geometry = producer.weatherGeometry
        geometry.count = 0
        geometry.origin = OitSurfaceOrigin(camera)
        val preparation = WeatherPreparation(state, extraction, geometry)
        producer.weatherPreparation = preparation
        producer.weather = null
        try {
            val result = action()
            if (geometry.count == state.rainColumns.size + state.snowColumns.size) {
                producer.weather = geometry
            }
            return result
        } finally {
            producer.weatherPreparation = null
            producer.extraction = null
            extraction?.phases?.clear()
        }
    }

    fun <Result> recordWeatherInstances(renderer: Any, columns: List<ColumnInstance>, action: () -> Result): Result {
        val preparation = producers[renderer]?.weatherPreparation ?: return action()
        val state = preparation.state
        preparation.kind = when {
            state.rainColumns === columns -> OitSurfaceKind.RAIN
            state.snowColumns === columns -> OitSurfaceKind.SNOW
            else -> null
        }
        try {
            return action()
        } finally {
            preparation.kind = null
        }
    }

    fun captureWeatherQuad(renderer: Any, column: ColumnInstance, color: Int,
        x0: Float, x1: Float, top: Float, bottom: Float, z0: Float, z1: Float,
        u0: Float, u1: Float, v0: Float, v1: Float) {
        val preparation = producers[renderer]?.weatherPreparation ?: return
        val kind = preparation.kind ?: return
        val geometry = preparation.geometry
        if (geometry.count == geometry.quads.size) geometry.quads.add(WeatherQuadCapture())
        val quad = geometry.quads[geometry.count++]
        quad.kind = kind
        quad.column = column
        quad.phase = preparation.extraction?.phases?.get(column)
        quad.color = color
        quad.x0 = x0
        quad.x1 = x1
        quad.top = top
        quad.bottom = bottom
        quad.z0 = z0
        quad.z1 = z1
        quad.u0 = u0
        quad.u1 = u1
        quad.v0 = v0
        quad.v1 = v1
    }

    fun <Result> recordWeatherBatch(renderer: Any, startColumn: Int, columnCount: Int,
        view: GpuTextureView, sampler: GpuSampler, action: () -> Result): Result {
        if (columnCount == 0 || frame == null) return action()
        val producer = producers[renderer] ?: return action()
        val weather = producer.weather ?: return action()
        if (startColumn < 0 || columnCount < 0 || startColumn.toLong() + columnCount > weather.count) return action()
        check(weatherBatch == null) { "Weather surface batches overlap" }
        weatherBatch = WeatherBatch(renderer, startColumn, columnCount, weather, view, sampler)
        try {
            return action()
        } finally {
            weatherBatch = null
        }
    }

    fun <Result> recordCloudPreparation(renderer: Any, ticks: Long, partialTick: Float, height: Float,
        status: CloudStatus, camera: Vec3, action: () -> Result): Result {
        val producer = producers.getOrPut(renderer) { ProducerState(Math.incrementExact(nextIdentity).also { nextIdentity = it }) }
        check(producer.cloudRecording == null) { "Cloud preparations overlap" }
        val recording = CloudRecording(ticks, partialTick, height, status, OitSurfaceOrigin(camera))
        producer.cloudRecording = recording
        producer.cloud = null
        try {
            val result = action()
            val color = recording.color
            val mesh = producer.cloudMesh
            if (recording.width <= 0 || recording.textureHeight <= 0 || recording.floors.size != 4 ||
                recording.vectors.size != 2 || color == null || mesh == null || ticks < 0L) return result
            val floors = recording.floors
            val centerX = Math.addExact(Math.addExact(floors[2].toLong(),
                Math.multiplyExact(floors[0].toLong(), recording.width.toLong())),
                Math.multiplyExact(ticks / (recording.width.toLong() * 400L), recording.width.toLong()))
            val centerZ = Math.addExact(floors[3].toLong(),
                Math.multiplyExact(floors[1].toLong(), recording.textureHeight.toLong()))
            val offset = recording.vectors[0]
            val size = recording.vectors[1]
            producer.cloud = OitCloudPreparation(producer.id, producer.generation, recording.width,
                recording.textureHeight, ticks, partialTick, height, status, recording.origin, centerX, centerZ,
                offset.first, offset.second, offset.third, size.first, size.second, size.third,
                color[0], color[1], color[2], color[3], mesh)
            return result
        } finally {
            producer.cloudRecording = null
        }
    }

    fun captureCloudWidth(renderer: Any, width: Int) {
        producers[renderer]?.cloudRecording?.width = width
    }

    fun captureCloudHeight(renderer: Any, height: Int) {
        producers[renderer]?.cloudRecording?.textureHeight = height
    }

    fun captureCloudFloor(renderer: Any, value: Int) {
        producers[renderer]?.cloudRecording?.floors?.add(value)
    }

    fun captureCloudVector(renderer: Any, x: Float, y: Float, z: Float) {
        producers[renderer]?.cloudRecording?.vectors?.add(Triple(x, y, z))
    }

    fun captureCloudColor(renderer: Any, red: Float, green: Float, blue: Float, alpha: Float) {
        producers[renderer]?.cloudRecording?.color = listOf(red, green, blue, alpha)
    }

    fun beginCloudMesh(renderer: Any) {
        val recording = producers[renderer]?.cloudRecording ?: return
        check(recording.mesh == null) { "Cloud mesh builds overlap" }
        recording.mesh = ArrayList()
    }

    fun captureCloudFace(renderer: Any, face: OitCloudFace) {
        producers[renderer]?.cloudRecording?.mesh?.add(face)
    }

    fun publishCloudMesh(renderer: Any) {
        val producer = producers[renderer] ?: return
        val recording = producer.cloudRecording ?: return
        producer.cloudMesh = OitCloudMesh(checkNotNull(recording.mesh))
        recording.mesh = null
    }

    fun cloudPreparation(renderer: Any?): OitCloudPreparation? = producers[renderer]?.cloud

    fun previousCloudPreparation(current: OitCloudPreparation): OitCloudPreparation? {
        val inputs = frame ?: return null
        if (!current.finite) return null
        val prior = previousPresented(inputs) ?: return null
        return prior.clouds[current.producerId]?.takeIf {
            it.generation == current.generation && it.width == current.width &&
                it.textureHeight == current.textureHeight && it.status == current.status &&
                it.height == current.height && it.finite
        }
    }

    fun drawInput(scope: OitProducerScope?, pass: OitPassFacts?): OitSurfaceDraw? {
        val inputs = frame ?: return null
        if (scope == null || pass?.hasAccumulateInputs() != true || scope.frame !== pass.frame ||
            scope.stage != pass.stage || scope.frame.identity != inputs.identity) return null
        val producer = producers[scope.producer.owner] ?: return null
        val previous = previousPresented(inputs)
        val snapshots: List<Pair<OitSurfaceOwner, OitSurfaceSnapshot>>
        val firstVertex: Int
        val material: OitSampledView?
        when (scope.producer.kind) {
            OitProducerKind.CLOUD -> {
                val preparation = producer.cloud ?: return null
                snapshots = preparation.faces.map { face ->
                    OitSurfaceOwner(inputs.identity.worldEpoch, producer.id, producer.generation,
                        OitSurfaceKind.CLOUD, Math.addExact(preparation.centerX, face.x.toLong()),
                        Math.addExact(preparation.centerZ, face.z.toLong()),
                        face.role or (if (preparation.status == CloudStatus.FANCY) 0 else 1 shl 16)) to
                        OitCloudSurface(preparation, face)
                }
                firstVertex = 0
                material = null
            }
            OitProducerKind.WEATHER -> {
                val batch = weatherBatch?.takeIf { it.renderer === scope.producer.owner } ?: return null
                val sampled = OitProducerCapture.captureSampledView(batch.view, batch.sampler) ?: return null
                val sampler = OitSurfaceSampler(batch.sampler)
                val kind = batch.geometry.quads[batch.startColumn].kind
                val materialState = producer.weatherMaterials.getOrPut(kind) {
                    WeatherMaterial(Math.incrementExact(nextIdentity).also { nextIdentity = it }, sampled, sampler)
                }
                val identity = materialState.capture(sampled, sampler)
                snapshots = (batch.startColumn until batch.startColumn + batch.columnCount).map { index ->
                    val quad = batch.geometry.quads[index].snapshot(batch.geometry.origin)
                    check(quad.kind == kind) { "Weather draw contains multiple material kinds" }
                    OitSurfaceOwner(inputs.identity.worldEpoch, producer.id, producer.generation, quad.kind,
                        quad.x.toLong(), quad.z.toLong(), 0) to OitWeatherSurface(quad, sampled, sampler, identity)
                }
                firstVertex = Math.multiplyExact(batch.startColumn, 4)
                material = sampled
            }
            OitProducerKind.BORDER -> {
                val batch = borderBatch?.takeIf { it.renderer === scope.producer.owner } ?: return null
                val mesh = producer.borderMesh ?: return null
                val transform = batch.transform ?: return null
                val sampler = batch.sampler ?: return null
                val sampled = OitProducerCapture.captureSampledView(batch.view ?: return null, sampler) ?: return null
                val identity = OitTerrainMaterials.capture(sampled.view.source, sampler) ?: return null
                val sampling = OitSurfaceSampler(sampler)
                snapshots = (0..3).map { side ->
                    OitSurfaceOwner(inputs.identity.worldEpoch, producer.id, producer.generation,
                        OitSurfaceKind.BORDER, 0L, 0L, side) to
                        OitBorderSurface(mesh, side, transform, sampling, identity)
                }
                firstVertex = 0
                material = sampled
            }
            else -> return null
        }
        val seen = HashSet<OitSurfaceOwner>()
        val pairs = snapshots.map { (owner, snapshot) ->
            check(seen.add(owner)) { "OIT surface owner occurs twice in one draw: $owner" }
            val old = previous?.surfaces?.get(owner)
            val sameFrame = pending?.get(owner)
            val surfaceId = sameFrame?.surfaceId ?: old?.surfaceId ?:
                Math.incrementExact(nextIdentity).also { nextIdentity = it }
            val geometry = when {
                !snapshot.finite || old?.current?.finite == false -> OitSurfaceValidity.NON_FINITE
                previous == null -> OitSurfaceValidity.NO_PREVIOUS_PRESENT
                old == null -> OitSurfaceValidity.NEW_SURFACE
                snapshot is OitCloudSurface && old.current is OitCloudSurface &&
                    snapshot.preparation.height != old.current.preparation.height -> OitSurfaceValidity.RESOURCE_CHANGED
                snapshot is OitBorderSurface && old.current is OitBorderSurface &&
                    snapshot.mesh.generation != old.current.mesh.generation -> OitSurfaceValidity.RESOURCE_CHANGED
                else -> OitSurfaceValidity.VALID
            }
            var materialValidity = geometry
            var phaseDeltaU = 0.0
            var phaseDeltaV = 0.0
            if (geometry == OitSurfaceValidity.VALID && snapshot is OitWeatherSurface) {
                val before = old?.current as? OitWeatherSurface
                val currentPhase = snapshot.quad.phase
                val priorPhase = before?.quad?.phase
                materialValidity = when {
                    before == null || currentPhase == null || priorPhase == null -> OitSurfaceValidity.UNCERTIFIED
                    !currentPhase.finite || !priorPhase.finite -> OitSurfaceValidity.NON_FINITE
                    snapshot.material != before.material ->
                        OitSurfaceValidity.RESOURCE_CHANGED
                    currentPhase.kind != priorPhase.kind || currentPhase.randomValues != priorPhase.randomValues ||
                        currentPhase.speed.toRawBits() != priorPhase.speed.toRawBits() ||
                        (currentPhase.ticks ushr 17) != (priorPhase.ticks ushr 17) ||
                        currentPhase.ticks < priorPhase.ticks ||
                        (currentPhase.ticks == priorPhase.ticks && currentPhase.partialTick < priorPhase.partialTick) ->
                        OitSurfaceValidity.MATERIAL_DISCONTINUITY
                    snapshot.sampler.addressU != AddressMode.REPEAT || snapshot.sampler.addressV != AddressMode.REPEAT ->
                        OitSurfaceValidity.UNCERTIFIED
                    else -> OitSurfaceValidity.VALID
                }
                if (materialValidity == OitSurfaceValidity.VALID && currentPhase != null && priorPhase != null) {
                    phaseDeltaU = snapshot.quad.uOffset.toDouble() - before.quad.uOffset.toDouble()
                    phaseDeltaV = if (snapshot.quad.kind == OitSurfaceKind.RAIN)
                        currentPhase.rawV.toDouble() - priorPhase.rawV.toDouble()
                    else snapshot.quad.vOffset.toDouble() - before.quad.vOffset.toDouble() -
                        (currentPhase.ticks / 512L - priorPhase.ticks / 512L).toDouble()
                }
            }
            if (geometry == OitSurfaceValidity.VALID && snapshot is OitBorderSurface) {
                val before = old?.current as? OitBorderSurface
                materialValidity = when {
                    before == null -> OitSurfaceValidity.UNCERTIFIED
                    snapshot.material != before.material -> OitSurfaceValidity.RESOURCE_CHANGED
                    !snapshot.transform.certifiedTexturePhase || !before.transform.certifiedTexturePhase ->
                        OitSurfaceValidity.UNCERTIFIED
                    snapshot.transform.millis < before.transform.millis ||
                        snapshot.transform.red != before.transform.red || snapshot.transform.green != before.transform.green ||
                        snapshot.transform.blue != before.transform.blue -> OitSurfaceValidity.MATERIAL_DISCONTINUITY
                    snapshot.sampler.addressU != AddressMode.REPEAT || snapshot.sampler.addressV != AddressMode.REPEAT ->
                        OitSurfaceValidity.UNCERTIFIED
                    else -> OitSurfaceValidity.VALID
                }
                if (materialValidity == OitSurfaceValidity.VALID && before != null) {
                    val cycles = (snapshot.transform.millis / 3000L - before.transform.millis / 3000L).toDouble()
                    phaseDeltaU = cycles
                    phaseDeltaV = cycles
                }
            }
            OitSurfacePair(owner, surfaceId, snapshot, old?.current, geometry, materialValidity, phaseDeltaU, phaseDeltaV)
        }
        return OitSurfaceDraw(inputs, previous?.frame, producer.id, producer.generation, firstVertex, pairs, material)
    }

    private fun previousPresented(inputs: VanillaFrameInputs): Presented? = presented?.takeIf {
        inputs.previous === it.frame.current && inputs.world === it.frame.world &&
            inputs.identity.worldEpoch == it.frame.identity.worldEpoch &&
            inputs.identity.configVersion == it.frame.identity.configVersion &&
            inputs.identity.resourceVersion == it.frame.identity.resourceVersion &&
            inputs.historyGeneration == it.frame.historyGeneration
    }

    fun recordDrawConsumption(draw: OitSurfaceDraw?) {
        if (draw == null) return
        check(draw.frame === frame) { "OIT surface draw belongs to another frame" }
        val surfaces = pending ?: LinkedHashMap<OitSurfaceOwner, OitSurfacePair>().also { pending = it }
        for (surface in draw.surfaces) surfaces[surface.owner] = surface
    }

    fun recordCloudDraw(preparation: OitCloudPreparation) {
        check(frame != null) { "Cloud draw arrived outside an OIT frame" }
        val clouds = pendingClouds ?: LinkedHashMap<Long, OitCloudPreparation>().also { pendingClouds = it }
        clouds[preparation.producerId] = preparation
    }

    fun stagePresented(inputs: VanillaFrameInputs) {
        check(frame === inputs) { "OIT surface presentation belongs to another frame" }
        check(stagedPresented == null) { "OIT surface presentation has not settled" }
        OitFeatureInputs.stagePresented(inputs)
        stagedPresented = Presented(inputs,
            pending?.let { Collections.unmodifiableMap(LinkedHashMap(it)) } ?: emptyMap(),
            pendingClouds?.let { Collections.unmodifiableMap(LinkedHashMap(it)) } ?: emptyMap())
        pending = null
        pendingClouds = null
        frame = null
        weatherBatch = null
        borderBatch = null
    }

    fun publishPresented(inputs: VanillaFrameInputs) {
        val candidate = checkNotNull(stagedPresented) { "OIT presentation has no staged surfaces" }
        check(candidate.frame === inputs) { "OIT presentation result belongs to another frame" }
        OitFeatureInputs.publishPresented(inputs)
        val generations = producers.values.associate { it.id to it.generation }
        presented = Presented(inputs,
            Collections.unmodifiableMap(candidate.surfaces.filterKeys {
                generations[it.producerId] == it.producerGeneration
            }),
            Collections.unmodifiableMap(candidate.clouds.filter { (producer, preparation) ->
                generations[producer] == preparation.generation
            }))
        stagedPresented = null
    }

    fun invalidate() {
        OitFeatureInputs.invalidate()
        frame = null
        presented = null
        stagedPresented = null
        pending = null
        pendingClouds = null
        weatherBatch = null
        borderBatch = null
    }

    fun invalidateCloudTexture(renderer: Any) {
        val producer = producers[renderer] ?: return
        producer.generation = Math.incrementExact(producer.generation)
        producer.cloud = null
        producer.cloudMesh = null
    }

    fun closeProducer(renderer: Any) {
        val producer = producers.remove(renderer) ?: return
        pending?.entries?.removeIf { it.key.producerId == producer.id }
        pendingClouds?.remove(producer.id)
        presented = presented?.withoutProducer(producer.id)
        stagedPresented = stagedPresented?.withoutProducer(producer.id)
    }

    private fun Presented.withoutProducer(producerId: Long): Presented = Presented(frame,
        Collections.unmodifiableMap(surfaces.filterKeys { it.producerId != producerId }),
        Collections.unmodifiableMap(clouds.filterKeys { it != producerId }))
}
