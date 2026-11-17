package io.github.dxegp.d3d12.ffi

internal enum class CommandDestructionOutcome { DESTROYED, OWNERSHIP_PENDING, SUBMISSION_UNKNOWN }

internal sealed interface CommandSubmissionOutcome {
    data object Ready : CommandSubmissionOutcome
    data class Submitted(val fence: Long) : CommandSubmissionOutcome
    data object RecordingRejected : CommandSubmissionOutcome
    data object OwnershipPending : CommandSubmissionOutcome
    data object SubmissionUnknown : CommandSubmissionOutcome

    companion object {
        fun decode(status: Int, fence: Long): CommandSubmissionOutcome {
            if (status == 1) {
                check(fence > 0L) { "Submitted command outcome has no context fence" }
                return Submitted(fence)
            }
            check(fence == 0L) { "Unsubmitted command outcome contains a fence" }
            return when (status) {
                0 -> Ready
                2 -> RecordingRejected
                3 -> OwnershipPending
                4 -> SubmissionUnknown
                else -> error("Invalid command outcome status: $status")
            }
        }
    }
}
