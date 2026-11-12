package io.github.dxegp.render.reconstruction

import java.lang.ref.WeakReference

internal class ReconstructionHistory {
    private var world: WeakReference<Any>? = null
    private var key: String? = null
    private var generation = -1L
    private var sequence = -1L
    private var realFrameId = -1L
    private var worldEpoch = -1L
    private var resourceVersion = -1L
    private var configVersion = -1L
    private var camera: ReconstructionFrameInputs.CameraPose? = null

    fun hasConsumed(frame: ReconstructionFrameInputs.Snapshot): Boolean =
        world?.get() === frame.world && worldEpoch == frame.worldEpoch && realFrameId == frame.realFrameId

    fun requiresReset(frame: ReconstructionFrameInputs.Snapshot, frameKey: String): Boolean {
        if (world?.get() !== frame.world || key != frameKey || generation != frame.historyGeneration ||
            frame.sequence != sequence + 1L || frame.realFrameId != realFrameId + 1L ||
            frame.worldEpoch != worldEpoch || frame.resourceVersion != resourceVersion ||
            frame.configVersion != configVersion
        ) return true
        val previous = camera ?: return true
        val current = frame.camera
        val deltaX = current.x - previous.x
        val deltaY = current.y - previous.y
        val deltaZ = current.z - previous.z
        val directionDot = current.forwardX * previous.forwardX + current.forwardY * previous.forwardY +
            current.forwardZ * previous.forwardZ
        return !deltaX.isFinite() || !deltaY.isFinite() || !deltaZ.isFinite() ||
            deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > CAMERA_CUT_DISTANCE_SQUARED ||
            !directionDot.isFinite() || directionDot < 0.0f
    }

    fun commit(frame: ReconstructionFrameInputs.Snapshot, frameKey: String) {
        check(!hasConsumed(frame)) { "Reconstruction history already consumed this real frame" }
        world = WeakReference(frame.world)
        key = frameKey
        generation = frame.historyGeneration
        sequence = frame.sequence
        worldEpoch = frame.worldEpoch
        realFrameId = frame.realFrameId
        resourceVersion = frame.resourceVersion
        configVersion = frame.configVersion
        camera = frame.camera
    }

    fun reset() {
        world = null
        key = null
        generation = -1L
        sequence = -1L
        realFrameId = -1L
        worldEpoch = -1L
        resourceVersion = -1L
        configVersion = -1L
        camera = null
    }

    private companion object {
        const val CAMERA_CUT_DISTANCE_SQUARED = 64.0 * 64.0
    }
}
