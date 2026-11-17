package io.github.dxegp.d3d12.resource

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.buffers.TransientMemory
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.diagnostics.IllusionLogger
import java.nio.ByteBuffer
import java.util.IdentityHashMap

class D3D12TransientMemory(private val ctx: Long, private val ensureListOpen: Runnable) : TransientMemory {
    private var closed = false
    private val buffers = SubmissionRetirement<D3D12GpuBuffer>(::isComplete) { it.close() }
    private val originalDrawReads = SubmissionRetirement<AutoCloseable>(::isComplete) { it.close() }
    private val originalDrawOwners = IdentityHashMap<Any, AutoCloseable>()
    private val uploadAllocations = SubmissionRetirement<D3D12UploadAllocation>(::isComplete) {
        it.buffer.retireUploadAllocation(it)
    }
    private val uploadReads = SubmissionRetirement<D3D12UploadSubmissionLease>(::isComplete) { it.close() }
    private var uploadRecording: D3D12UploadRecording? = null
    private val mapped = uploadArena(4096L)
    private val staging = uploadArena(512L * 1024L)

    private fun isComplete(fence: Long): Boolean = MCDR_ABI.D3D12WaitForFence(ctx, fence, 0L)

    private fun uploadArena(pageSize: Long) = SubmissionUploadArena(
        pageSize, 8L * 1024L * 1024L, 12L * 1024L * 1024L,
        { usage, capacity -> D3D12GpuBuffer(usage, capacity) },
        { buffer: D3D12GpuBuffer -> buffer.close() }, ::isComplete)

    private fun beginAllocation() {
        check(!closed) { "D3D12TransientMemory is closed" }
        ensureListOpen.run()
        buffers.collect()
        originalDrawReads.collect()
        uploadReads.collect()
        uploadAllocations.collect()
    }

    override fun allocateCpu(size: Long, alignment: Long, minimumAllocation: Long, elementSize: Long): ByteBuffer {
        check(!closed) { "D3D12TransientMemory is closed" }
        require(size in 0L..Int.MAX_VALUE.toLong()) { "CPU allocation must fit a ByteBuffer" }
        return ByteBuffer.allocate(size.toInt())
    }

    override fun allocateStaging(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        beginAllocation()
        val allocation = staging.allocate(size, SubmissionUploadArena.commonAlignment(alignment, 512L),
            GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, minimumAllocation)
        if (size > 0L) uploadAllocations.retain(allocation.resource.registerUploadAllocation(allocation.offset, size))
        return allocation.resource.map(allocation.offset, size, false, true)
    }

    override fun allocateGpu(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        beginAllocation()
        require(size >= 0L) { "GPU allocation size must be non-negative" }
        val buffer = D3D12GpuBuffer(usage or GpuBuffer.USAGE_COPY_DST, size)
        buffers.retain(buffer)
        return buffer.slice()
    }

    override fun allocateGpuMapped(size: Long, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice.MappedView {
        beginAllocation()
        val allocation = mapped.allocate(size, SubmissionUploadArena.commonAlignment(alignment, 256L),
            usage or GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, minimumAllocation)
        if (size > 0L) uploadAllocations.retain(allocation.resource.registerUploadAllocation(allocation.offset, size))
        return allocation.resource.map(allocation.offset, size, false, true)
    }

    override fun uploadStaging(data: MutableList<ByteBuffer>, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        val total = totalBytes(data)
        require(total > 0L) { "Cannot upload zero bytes" }
        return writeIntoStagingBlock(total, alignment, minimumAllocation) { destination ->
            for (buffer in data) destination.put(buffer.duplicate())
            total
        }.getOrThrow().first
    }

    @Suppress("NOTHING_TO_INLINE")
    internal inline fun writeIntoStagingBlock(total: Long, alignment: Long,
        crossinline write: (ByteBuffer) -> Long): Result<Pair<GpuBufferSlice, Long>> =
        writeIntoStagingBlock(total, alignment, 0L, write)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun writeIntoStagingBlock(total: Long, alignment: Long, minimumAllocation: Long,
        crossinline write: (ByteBuffer) -> Long): Result<Pair<GpuBufferSlice, Long>> {
        beginAllocation()
        var attempt = 0
        while (true) {
            val allocation = staging.allocate(total, SubmissionUploadArena.commonAlignment(alignment, 512L),
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, minimumAllocation)
            if (total > 0L) uploadAllocations.retain(allocation.resource.registerUploadAllocation(allocation.offset, total))
            val view = try {
                allocation.resource.map(allocation.offset, total, false, true)
            } catch (failure: Throwable) {
                staging.discard(allocation.resource)
                IllusionLogger.warnFormat("transientMemory: staging page mapping failed at %d+%d: %s%n",
                    allocation.offset, total, failure.toString())
                if (attempt++ >= 1) return Result.failure(failure)
                continue
            }
            return runCatching {
                view.use {
                    val written = write(it.data())
                    require(written in 0L..total) { "Staging writer exceeded its allocation" }
                    allocation.resource.slice(allocation.offset, written) to written
                }
            }
        }
    }

    override fun uploadGpu(data: MutableList<ByteBuffer>, alignment: Long,
        usage: @GpuBuffer.Usage Int, minimumAllocation: Long, elementSize: Long): GpuBufferSlice {
        val total = totalBytes(data)
        require(total > 0L) { "Cannot upload zero bytes" }
        val source = uploadStaging(data, alignment, usage, minimumAllocation, elementSize)
        val destination = allocateGpu(total, alignment, usage, minimumAllocation, elementSize)
        check(MCDR_ABI.D3D12CopyBufferChecked(ctx, (source.buffer() as D3D12GpuBuffer).handle(), source.offset(),
            (destination.buffer() as D3D12GpuBuffer).handle(), destination.offset(), total)) {
            "GPU upload recording failed: ${MCDR_ABI.D3D12LastError()}"
        }
        return destination
    }

    override fun multiUploadStaging(data: MutableList<ByteBuffer>,
        alignment: Long, usage: @GpuBuffer.Usage Int): MutableList<GpuBufferSlice> {
        return data.mapTo(mutableListOf()) { buffer ->
            val length = buffer.remaining().toLong()
            writeIntoStagingBlock(length, alignment) { destination ->
                destination.put(buffer.duplicate())
                length
            }.getOrThrow().first
        }
    }

    override fun multiUploadGpu(data: MutableList<ByteBuffer>,
        alignment: Long, usage: @GpuBuffer.Usage Int): MutableList<GpuBufferSlice> {
        return data.mapTo(mutableListOf()) { buffer ->
            uploadGpu(mutableListOf(buffer), alignment, usage, 0L, 1L)
        }
    }

    fun rotate(submittedFence: Long) {
        check(!closed) { "D3D12TransientMemory is closed" }
        uploadRecording?.seal()
        uploadRecording = null
        uploadReads.seal(submittedFence)
        uploadAllocations.seal(submittedFence)
        mapped.seal(submittedFence)
        staging.seal(submittedFence)
        buffers.seal(submittedFence)
        originalDrawReads.seal(submittedFence)
        originalDrawOwners.clear()
    }

    internal fun retireAfterSubmission(buffer: D3D12GpuBuffer) {
        check(!closed) { "D3D12TransientMemory is closed" }
        buffers.retain(buffer)
    }

    internal fun retainPreparedUploadRead(slice: GpuBufferSlice): D3D12UploadRead {
        check(!closed) { "D3D12TransientMemory is closed" }
        val buffer = slice.buffer() as? D3D12GpuBuffer
            ?: return D3D12UploadRead.Unavailable(D3D12UploadRead.Reason.OWNER_UNAVAILABLE)
        val recording = uploadRecording ?: D3D12UploadRecording(ctx).also { uploadRecording = it }
        val read = buffer.retainUploadRead(slice.offset(), slice.length(), recording)
        if (read is D3D12UploadRead.Available) {
            val lease = checkNotNull(read.submissionLease)
            try {
                uploadReads.retain(lease)
            } catch (failure: Throwable) {
                lease.close()
                throw failure
            }
        }
        return read
    }

    internal fun retainOriginalDrawRead(owner: Any, acquire: () -> AutoCloseable) {
        check(!closed) { "D3D12TransientMemory is closed" }
        if (originalDrawOwners.containsKey(owner)) return
        val lease = acquire()
        try {
            originalDrawReads.retain(lease)
            originalDrawOwners[owner] = lease
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }

    internal fun retainUploadDestination(owner: Any, acquire: () -> AutoCloseable) {
        beginAllocation()
        retainOriginalDrawRead(owner, acquire)
    }

    fun close() {
        if (closed) return
        uploadRecording?.seal()
        uploadRecording = null
        uploadReads.drainAfterCompletion()
        uploadAllocations.drainAfterCompletion()
        mapped.closeAfterCompletion()
        staging.closeAfterCompletion()
        buffers.drainAfterCompletion()
        originalDrawReads.drainAfterCompletion()
        originalDrawOwners.clear()
        closed = true
    }

    private fun totalBytes(data: List<ByteBuffer>): Long = data.fold(0L) { total, buffer ->
        Math.addExact(total, buffer.remaining().toLong())
    }
}
