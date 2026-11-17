package io.github.dxegp.d3d12.resource

import java.util.concurrent.atomic.AtomicLong

internal class D3D12UploadAllocation(
    val buffer: D3D12GpuBuffer,
    val byteOffset: Long,
    val byteLength: Long,
) : AutoCloseable {
    val identity: Long = identities.incrementAndGet().also { check(it > 0L) }
    private var contentVersion = 0L
    private var openWrites = 0
    private var writtenOffset = 0L
    private var writtenLength = 0L
    private var retired = false
    private var overlappingWrites = false
    val hasOpenWrites: Boolean
        @Synchronized get() = openWrites != 0

    @Synchronized
    fun beginWrite() {
        check(!retired) { "Upload allocation has retired" }
        if (openWrites > 0) overlappingWrites = true
        else overlappingWrites = false
        openWrites = Math.incrementExact(openWrites)
    }

    @Synchronized
    fun completeWrite(offset: Long, length: Long) {
        check(openWrites > 0)
        openWrites--
        contentVersion = Math.incrementExact(contentVersion)
        writtenOffset = maxOf(offset, byteOffset)
        writtenLength = minOf(Math.addExact(offset, length), Math.addExact(byteOffset, byteLength)) - writtenOffset
    }

    @Synchronized
    fun readRange(offset: Long, length: Long): D3D12UploadRead {
        if (retired) return D3D12UploadRead.Unavailable(D3D12UploadRead.Reason.ALLOCATION_RETIRED)
        if (offset < byteOffset || length <= 0L || offset > byteOffset + byteLength ||
            length > byteOffset + byteLength - offset) {
            return D3D12UploadRead.Unavailable(D3D12UploadRead.Reason.RANGE_MISMATCH)
        }
        if (openWrites != 0) return D3D12UploadRead.Unavailable(D3D12UploadRead.Reason.WRITE_OPEN)
        if (overlappingWrites) return D3D12UploadRead.Unavailable(D3D12UploadRead.Reason.OVERLAPPING_WRITES)
        if (contentVersion == 0L || offset < writtenOffset || offset > writtenOffset + writtenLength ||
            length > writtenOffset + writtenLength - offset) {
            return D3D12UploadRead.Unavailable(D3D12UploadRead.Reason.WRITE_RANGE_UNAVAILABLE)
        }
        return D3D12UploadRead.Available(this, identity, contentVersion, buffer.handle(), offset, length)
    }

    @Synchronized
    fun isCurrent(read: D3D12UploadRead.Available): Boolean =
        read.allocation === this && !retired && openWrites == 0 && !overlappingWrites && read.contentVersion == contentVersion &&
            !buffer.isClosed

    @Synchronized
    override fun close() {
        retired = true
    }

    companion object {
        private val identities = AtomicLong()
    }
}

internal sealed interface D3D12UploadRead {
    @ConsistentCopyVisibility
    data class Available internal constructor(
        internal val allocation: D3D12UploadAllocation,
        val ownerIdentity: Long,
        val contentVersion: Long,
        val bufferHandle: Long,
        val byteOffset: Long,
        val byteLength: Long,
        internal val submissionLease: D3D12UploadSubmissionLease? = null,
    ) : D3D12UploadRead {
        val allocationOffset: Long get() = allocation.byteOffset
        val allocationLength: Long get() = allocation.byteLength
        internal fun retainSubmission(lease: D3D12UploadSubmissionLease): Available =
            copy(submissionLease = lease)

        fun isCurrent(context: Long): Boolean = submissionLease?.isRecording(context) == true && allocation.isCurrent(this)
    }

    data class Unavailable(val reason: Reason) : D3D12UploadRead

    enum class Reason {
        NOT_UPLOAD, OWNER_UNAVAILABLE, ALLOCATION_RETIRED, RANGE_MISMATCH, WRITE_OPEN,
        WRITE_RANGE_UNAVAILABLE, BUFFER_CLOSED, OVERLAPPING_WRITES,
    }
}

internal class D3D12UploadRecording(val context: Long) {
    @Volatile
    var isRecording = true
        private set

    fun seal() {
        isRecording = false
    }
}

internal class D3D12UploadSubmissionLease(
    private val recording: D3D12UploadRecording,
    private val backing: AutoCloseable,
) : AutoCloseable {
    fun isRecording(context: Long): Boolean = isActive && recording.isRecording && recording.context == context
    @Volatile
    var isActive = true
        private set

    @Synchronized
    override fun close() {
        if (!isActive) return
        isActive = false
        backing.close()
    }
}
