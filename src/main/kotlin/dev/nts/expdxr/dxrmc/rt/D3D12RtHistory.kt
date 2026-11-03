package dev.nts.expdxr.dxrmc.rt

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

internal class D3D12RtHistory {
    enum class InvalidationReason(val mask: Int) {
        FIRST_FRAME(1 shl 0),
        RESIZE(1 shl 1),
        CAMERA_CUT(1 shl 2),
        WORLD(1 shl 3),
        GEOMETRY(1 shl 4),
        MATERIAL(1 shl 5),
        TIME_JUMP(1 shl 6),
        DEVICE(1 shl 7),
        FRAME_GENERATION(1 shl 8),
        PIPELINE(1 shl 9),
        RESOURCES(1 shl 10),
        FRAME_GAP(1 shl 11),
        EVALUATION_FAILURE(1 shl 12),
    }

    data class Snapshot(
        val plan: D3D12RtPipelinePlan.Plan,
        val worldIdentity: Any?,
        val deviceIdentity: Any,
        val guideIdentity: Any?,
        val geometryEpoch: Int,
        val geometryRevision: Long,
        val materialRevision: Long,
        val traceBackend: Int,
        val frameGeneration: Long,
        val worldTime: Long,
        val frameNanos: Long,
        val position: RtVec3,
        val forward: RtVec3,
        val up: RtVec3,
        val projection: FloatArray,
        val jitterEnabled: Boolean = false,
    )

    data class Invalidation(val generation: Long, val frame: Long, val reasons: Int) {
        fun has(reason: InvalidationReason): Boolean = reasons and reason.mask != 0
    }

    class Consumer {
        private var generation = Long.MIN_VALUE
        private var frame = Long.MIN_VALUE

        fun requiresReset(invalidation: Invalidation): Boolean = generation != invalidation.generation ||
            frame + 1 != invalidation.frame

        fun accept(invalidation: Invalidation) {
            generation = invalidation.generation
            frame = invalidation.frame
        }
    }

    private val pendingReasons = AtomicInteger()
    private var previous: Snapshot? = null
    private var generation = 0L
    private var frame = 0L

    fun invalidate(reason: InvalidationReason) {
        pendingReasons.getAndUpdate { it or reason.mask }
    }

    fun clear() {
        previous = null
        invalidate(InvalidationReason.DEVICE)
    }

    fun begin(snapshot: Snapshot): Invalidation {
        require(snapshot.projection.size == 16)
        var reasons = pendingReasons.getAndSet(0)
        val before = previous
        if (before == null) {
            reasons = reasons or InvalidationReason.FIRST_FRAME.mask
        } else {
            if (before.plan.displayWidth != snapshot.plan.displayWidth ||
                before.plan.displayHeight != snapshot.plan.displayHeight ||
                before.plan.renderWidth != snapshot.plan.renderWidth ||
                before.plan.renderHeight != snapshot.plan.renderHeight ||
                before.plan.traceWidth != snapshot.plan.traceWidth ||
                before.plan.traceHeight != snapshot.plan.traceHeight) {
                reasons = reasons or InvalidationReason.RESIZE.mask
            }
            if (before.plan.track != snapshot.plan.track || before.plan.upscaler != snapshot.plan.upscaler ||
                before.plan.enabled != snapshot.plan.enabled || before.traceBackend != snapshot.traceBackend ||
                before.jitterEnabled != snapshot.jitterEnabled) {
                reasons = reasons or InvalidationReason.PIPELINE.mask
            }
            if (before.worldIdentity !== snapshot.worldIdentity || before.geometryEpoch != snapshot.geometryEpoch) {
                reasons = reasons or InvalidationReason.WORLD.mask
            }
            if (before.deviceIdentity !== snapshot.deviceIdentity) reasons = reasons or InvalidationReason.DEVICE.mask
            if (before.guideIdentity !== snapshot.guideIdentity) reasons = reasons or InvalidationReason.RESOURCES.mask
            if (before.geometryRevision != snapshot.geometryRevision) reasons = reasons or InvalidationReason.GEOMETRY.mask
            if (before.materialRevision != snapshot.materialRevision) reasons = reasons or InvalidationReason.MATERIAL.mask
            if (before.frameGeneration != snapshot.frameGeneration) reasons = reasons or InvalidationReason.FRAME_GENERATION.mask
            if (snapshot.worldTime < before.worldTime || snapshot.worldTime - before.worldTime > TIME_JUMP_TICKS) {
                reasons = reasons or InvalidationReason.TIME_JUMP.mask
            }
            val elapsed = snapshot.frameNanos - before.frameNanos
            if (elapsed <= 0 || elapsed > MAX_FRAME_GAP_NANOS) reasons = reasons or InvalidationReason.FRAME_GAP.mask
            if (cameraCut(before, snapshot)) reasons = reasons or InvalidationReason.CAMERA_CUT.mask
        }
        previous = snapshot.copy(projection = snapshot.projection.copyOf())
        frame += 1
        if (reasons != 0) generation += 1
        return Invalidation(generation, frame, reasons)
    }

    private fun cameraCut(before: Snapshot, after: Snapshot): Boolean {
        val deltaX = after.position.x - before.position.x
        val deltaY = after.position.y - before.position.y
        val deltaZ = after.position.z - before.position.z
        val distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        if (!distanceSquared.isFinite() || distanceSquared > CAMERA_CUT_DISTANCE_SQUARED) return true
        for ([previousDirection, currentDirection] in listOf(before.forward to after.forward, before.up to after.up)) {
            val cosine = previousDirection.x * currentDirection.x + previousDirection.y * currentDirection.y +
                previousDirection.z * currentDirection.z
            if (!cosine.isFinite() || cosine < CAMERA_CUT_COSINE) return true
        }
        if (after.projection.any { !it.isFinite() }) return true
        for (index in intArrayOf(0, 5, 10, 14)) {
            val scale = abs(before.projection[index]).coerceAtLeast(0.001f)
            if (abs(after.projection[index] - before.projection[index]) > scale * PROJECTION_CUT_FRACTION) return true
        }
        return intArrayOf(3, 7, 8, 9, 11, 15).any {
            abs(after.projection[it] - before.projection[it]) > PROJECTION_CUT_FRACTION
        }
    }

    private companion object {
        const val CAMERA_CUT_DISTANCE_SQUARED = 16f * 16f
        const val CAMERA_CUT_COSINE = 0.70710677f
        const val PROJECTION_CUT_FRACTION = 0.1f
        const val TIME_JUMP_TICKS = 200L
        const val MAX_FRAME_GAP_NANOS = 500_000_000L
    }
}
