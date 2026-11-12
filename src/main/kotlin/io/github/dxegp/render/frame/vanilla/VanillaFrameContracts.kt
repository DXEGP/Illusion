package io.github.dxegp.render.frame.vanilla

internal enum class VanillaFramePhase {
    IDLE,
    RECORDING,
    WORLD_READY,
    WORLD_POST,
    GUI_READY,
}

internal data class VanillaFrameIdentity(
    val schemaVersion: Int,
    val worldEpoch: Long,
    val realFrameId: Long,
    val configVersion: Long,
    val resourceVersion: Long,
    val width: Int,
    val height: Int,
)

internal data class VanillaPassInput(
    val identity: VanillaFrameIdentity,
    val passId: Int,
    val resourceHandle: Long,
    val readState: Int,
    val writeState: Int,
)

internal class VanillaFramePhaseMachine {
    var phase: VanillaFramePhase = VanillaFramePhase.IDLE
        private set
    var identity: VanillaFrameIdentity? = null
        private set
    private var executedPasses: Int = 0

    fun begin(next: VanillaFrameIdentity): Boolean {
        if (next.schemaVersion != 1 || next.worldEpoch <= 0L || next.realFrameId <= 0L ||
            next.configVersion <= 0L || next.resourceVersion <= 0L || next.width <= 0 || next.height <= 0
        ) return false
        if (phase != VanillaFramePhase.IDLE) return false
        identity = next
        executedPasses = 0
        phase = VanillaFramePhase.RECORDING
        return true
    }

    fun worldReady(candidate: VanillaFrameIdentity): Boolean {
        if (phase != VanillaFramePhase.RECORDING || identity != candidate) return false
        phase = VanillaFramePhase.WORLD_READY
        return true
    }

    fun post(pass: VanillaPassInput): Boolean {
        if ((phase != VanillaFramePhase.WORLD_READY && phase != VanillaFramePhase.WORLD_POST) ||
            identity != pass.identity || pass.passId == 0 || (executedPasses and pass.passId) != 0 ||
            pass.resourceHandle == 0L || pass.readState < 0 || pass.writeState < 0
        ) return false
        executedPasses = executedPasses or pass.passId
        phase = VanillaFramePhase.WORLD_POST
        return true
    }

    fun worldPostStarted(candidate: VanillaFrameIdentity): Boolean {
        if ((phase != VanillaFramePhase.WORLD_READY && phase != VanillaFramePhase.WORLD_POST) ||
            identity != candidate
        ) return false
        phase = VanillaFramePhase.WORLD_POST
        return true
    }

    fun guiReady(candidate: VanillaFrameIdentity): Boolean {
        if ((phase != VanillaFramePhase.WORLD_READY && phase != VanillaFramePhase.WORLD_POST) || identity != candidate) return false
        phase = VanillaFramePhase.GUI_READY
        return true
    }

    fun finish() {
        phase = VanillaFramePhase.IDLE
        identity = null
        executedPasses = 0
    }
}
