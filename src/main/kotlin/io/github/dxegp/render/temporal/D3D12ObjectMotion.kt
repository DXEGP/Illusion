package io.github.dxegp.render.temporal

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory
import kotlin.math.max
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import net.minecraft.client.renderer.entity.state.EntityRenderState
import io.github.dxegp.render.frameprep.MotionGuideCoverage

/**
 * Per-object motion vectors, CPU side: this frame's object snapshot collection.
 *
 * Per-object matrices are not available: the model matrix is baked into the vertices on the CPU and
 * `ModelViewMat` carries only the camera rotation, so the only usable delta is each object's own
 * world-space displacement.
 *
 * Rows hold world-space absolute positions. [D3D12Velocity] subtracts *this frame's* camera when it
 * builds the GPU buffer — subtracting any other frame's camera would inject the camera motion as
 * object velocity, while a static object under a static camera must stay at exactly zero.
 *
 * EntityRenderDispatcher extraction supplies final poses; only states in the level's actual render
 * list participate. Consecutive real render frames supply previous positions. The AABB association
 * remains approximate: this is not a per-pixel object/deformation motion guide.
 */
object D3D12ObjectMotion {
    private val LOGGER = LoggerFactory.getLogger("dxrmc-objectmotion")

    /** 16 floats per row; must match the struct in [D3D12Velocity]'s inline HLSL byte for byte. */
    const val ROW_FLOATS: Int = 16

    /** Max objects reported per frame; the rest is dropped and counted. */
    const val MAX_OBJECTS: Int = 256

    /** uint offsets into the 16-float row; index 4 is unused, 5..11 mirror the HLSL struct. */
    const val FIELD_ID: Int = 0
    const val FIELD_CUR_X: Int = 1
    const val FIELD_CUR_Y: Int = 2
    const val FIELD_CUR_Z: Int = 3
    const val FIELD_PREV_X: Int = 5
    const val FIELD_PREV_Y: Int = 6
    const val FIELD_PREV_Z: Int = 7
    const val FIELD_HALF_X: Int = 8
    const val FIELD_HALF_Y: Int = 9
    const val FIELD_HALF_Z: Int = 10
    const val FIELD_YAW: Int = 11

    /** Stable identity for the extracted entity draw candidate. Parts are not exposed by vanilla here. */
    internal data class DrawIdentity(val entityId: Int, val drawId: Long, val partId: String? = null)

    internal data class TransformPair(
        val currentX: Double,
        val currentY: Double,
        val currentZ: Double,
        val previousX: Double?,
        val previousY: Double?,
        val previousZ: Double?,
    )

    internal data class EntityGuide(
        val identity: DrawIdentity,
        val transform: TransformPair,
        val coverage: MotionGuideCoverage,
        val partCoverage: MotionGuideCoverage = MotionGuideCoverage.PART_TRANSFORM_UNAVAILABLE,
        val rasterCoverage: MotionGuideCoverage = MotionGuideCoverage.RASTER_COVERAGE_UNAVAILABLE,
    )

    /**
     * One object at world-space absolute position — the camera subtraction happens in [writeWorldRows].
     */
    private class Snapshot(
        val id: Int,
        val currX: Double, val currY: Double, val currZ: Double,
        val prevX: Double, val prevY: Double, val prevZ: Double,
        val halfX: Float, val halfY: Float, val halfZ: Float,
        val centerYOffset: Float,
        val yaw: Float
    )

    /**
     * This frame's slots, deduplicated by [Entity.id] (a later write wins), so `size` == object count.
     */
    private val currentSnapshots: MutableMap<Int, Snapshot> = LinkedHashMap()
    private data class RenderPose(val entity: WeakReference<Entity>, val frame: Long,
        val x: Double, val y: Double, val z: Double)
    private val previousPoses = HashMap<Int, RenderPose>()
    private data class ExtractedPose(val entity: WeakReference<Entity>, val world: Any,
        val stamp: Long, val x: Double, val y: Double, val z: Double,
        val width: Float, val height: Float)
    private val extracted = WeakHashMap<EntityRenderState, ExtractedPose>()

    private val entityIdentities = WeakHashMap<Entity, DrawIdentity>()
    private val currentGuides = LinkedHashMap<Int, EntityGuide>()

    @Synchronized
    fun captureExtracted(entity: Entity, state: EntityRenderState) {
        val minecraft = Minecraft.getInstance()
        val world = minecraft.level ?: return
        extracted[state] = ExtractedPose(WeakReference(entity), world, D3D12Temporal.renderFrameTimeNanos,
            state.x, state.y, state.z, state.boundingBoxWidth, state.boundingBoxHeight)
    }

    @Synchronized
    internal fun entityGuides(): List<EntityGuide> = currentGuides.values.toList()

    @Synchronized
    internal fun extractedEntity(state: EntityRenderState): Entity? {
        val minecraft = Minecraft.getInstance()
        val pose = extracted[state] ?: return null
        return pose.entity.get()?.takeIf {
            pose.world === minecraft.level && pose.stamp == D3D12Temporal.renderFrameTimeNanos
        }
    }

    @Synchronized
    internal fun motionGuideCoverage(): MotionGuideCoverage = when {
        currentGuides.values.any { it.coverage == MotionGuideCoverage.INVALID } -> MotionGuideCoverage.INVALID
        currentGuides.values.any { it.coverage == MotionGuideCoverage.ENTITY_TRANSLATION_AABB_APPROXIMATION } ->
            MotionGuideCoverage.ENTITY_TRANSLATION_AABB_APPROXIMATION
        currentGuides.isNotEmpty() -> MotionGuideCoverage.INVALID
        else -> MotionGuideCoverage.CAMERA_ONLY
    }

    /** Snapshot rolled by [writeWorldRows], at most once per frame (later calls must reuse it). */
    @Volatile
    private var writeSnapshots: Map<Int, Snapshot> = emptyMap()

    /** Frame number of [writeSnapshots]; `-1` = nothing rolled yet. */
    private var lastConsumedFrame: Long = -1L

    @Volatile
    private var frameNumber: Long = 0L

    @Volatile
    private var lastOverflowLoggedFrame: Long = -1L

    @Volatile
    private var overflowCount: Long = 0L

    /**
     * Per-frame collection entry, called from [D3D12Temporal.beginFrame] before the velocity pass.
     */
    @Synchronized
    fun harvestVisible() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        for (state in minecraft.gameRenderer.gameRenderState().levelRenderState.entityRenderStates) {
            val pose = extracted[state] ?: continue
            if (pose.world !== level || pose.stamp != D3D12Temporal.renderFrameTimeNanos || state.isInvisible) continue
            val entity = pose.entity.get() ?: continue
            harvest(entity, pose)
        }
    }

    /**
     * Writes one row from the actual extracted position and the previous real frame's position.
     * Half extents are floored at 0.01 so a zero-sized box still matches a pixel.
     */
    private fun harvest(entity: Entity, pose: ExtractedPose) {
        val id = entity.id
        val x = pose.x
        val y = pose.y
        val z = pose.z
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        val previous = previousPoses.put(id, RenderPose(WeakReference(entity), frameNumber, x, y, z))
        val compatible = previous != null && previous.entity.get() === entity && previous.frame == frameNumber - 1L
        val identity = entityIdentities.getOrPut(entity) {
            DrawIdentity(id, id.toLong(), null)
        }
        currentGuides[id] = EntityGuide(
            identity,
            TransformPair(x, y, z, previous?.takeIf { compatible }?.x,
                previous?.takeIf { compatible }?.y, previous?.takeIf { compatible }?.z),
            if (compatible) MotionGuideCoverage.ENTITY_TRANSLATION_AABB_APPROXIMATION else MotionGuideCoverage.INVALID,
        )
        if (!compatible) return
        if (currentSnapshots.size >= MAX_OBJECTS && !currentSnapshots.containsKey(id)) {
            overflowCount++
            if (lastOverflowLoggedFrame != frameNumber) {
                lastOverflowLoggedFrame = frameNumber
                LOGGER.warn(
                    "object row buffer is full ($MAX_OBJECTS rows); the remaining objects skip per-object velocity correction this frame" +
                        " ($overflowCount dropped so far)"
                )
            }
            return
        }
        val halfWidth = max(pose.width * 0.5f, 0.01f)
        val halfHeight = max(pose.height * 0.5f, 0.01f)
        currentSnapshots[id] = Snapshot(
            id = id,
            currX = x, currY = y, currZ = z,
            prevX = previous.x, prevY = previous.y, prevZ = previous.z,
            halfX = halfWidth,
            halfY = halfHeight,
            halfZ = halfWidth,
            // The render origin is the feet, while the shader wants the box centre.
            centerYOffset = halfHeight,
            yaw = 0.0f
        )
    }

    /** Advances one frame: clears the slots that [harvestVisible] then refills. */
    @Synchronized
    fun beginFrame() {
        currentSnapshots.clear()
        frameNumber++
        currentGuides.clear()
        previousPoses.entries.removeIf { it.value.frame < frameNumber - 1L || it.value.entity.get() == null }
        val minecraft = Minecraft.getInstance()
        extracted.entries.removeIf {
            it.value.world !== minecraft.level || it.value.stamp != D3D12Temporal.renderFrameTimeNanos
        }
    }

    /** Clears everything (world exit / teleport / device shutdown). */
    @Synchronized
    fun reset() {
        D3D12RasterMotionCapture.reset()
        currentSnapshots.clear()
        previousPoses.clear()
        writeSnapshots = emptyMap()
        lastConsumedFrame = -1L
        frameNumber = 0L
        overflowCount = 0L
        entityIdentities.clear()
        currentGuides.clear()
    }

    /**
     * Writes this frame's rows into [target] (rows = `target.size / ROW_FLOATS`) and returns the count
     * actually written. [cameraX]/[cameraY]/[cameraZ] must be this frame's camera.
     * [currentSnapshots] rolls into [writeSnapshots] only once per frame — otherwise the second
     * serialization of the frame would read empty slots and report 0 rows.
     */
    @Synchronized
    fun writeWorldRows(target: FloatArray, cameraX: Double, cameraY: Double, cameraZ: Double): Int {
        if (lastConsumedFrame != frameNumber) {
            writeSnapshots = LinkedHashMap(currentSnapshots)
            lastConsumedFrame = frameNumber
        }
        val source = writeSnapshots
        if (source.isEmpty()) {
            return 0
        }
        val capacity = target.size / ROW_FLOATS
        val limit = if (source.size < capacity) source.size else capacity
        var written = 0
        for (snapshot in source.values) {
            if (written >= limit) {
                break
            }
            val offset = written * ROW_FLOATS
            target[offset + FIELD_ID] = snapshot.id.toFloat()
            target[offset + FIELD_CUR_X] = (snapshot.currX - cameraX).toFloat()
            // Lift to the box centre: the shader treats this Y as the centre, not the feet.
            target[offset + FIELD_CUR_Y] = (snapshot.currY - cameraY + snapshot.centerYOffset).toFloat()
            target[offset + FIELD_CUR_Z] = (snapshot.currZ - cameraZ).toFloat()
            target[offset + FIELD_PREV_X] = (snapshot.prevX - cameraX).toFloat()
            target[offset + FIELD_PREV_Y] = (snapshot.prevY - cameraY + snapshot.centerYOffset).toFloat()
            target[offset + FIELD_PREV_Z] = (snapshot.prevZ - cameraZ).toFloat()
            target[offset + FIELD_HALF_X] = snapshot.halfX
            target[offset + FIELD_HALF_Y] = snapshot.halfY
            target[offset + FIELD_HALF_Z] = snapshot.halfZ
            target[offset + FIELD_YAW] = snapshot.yaw
            written++
        }
        return written
    }

}
