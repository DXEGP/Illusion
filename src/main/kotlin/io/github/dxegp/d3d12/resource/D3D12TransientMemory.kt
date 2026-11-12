package io.github.dxegp.d3d12.resource

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.buffers.TransientMemory
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.diagnostics.IllusionLogger
import java.nio.ByteBuffer

class D3D12TransientMemory(private val ctx: Long, private val ensureListOpen: Runnable) : TransientMemory {
    private var closed = false
    private val buffers = SubmissionRetirement<D3D12GpuBuffer>(::isComplete) { it.close() }
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

    internal fun writeIntoStagingBlock(total: Long, alignment: Long,
        write: (ByteBuffer) -> Long): Result<Pair<GpuBufferSlice, Long>> =
        writeIntoStagingBlock(total, alignment, 0L, write)

    private fun writeIntoStagingBlock(total: Long, alignment: Long, minimumAllocation: Long,
        write: (ByteBuffer) -> Long): Result<Pair<GpuBufferSlice, Long>> {
        beginAllocation()
        var attempt = 0
        while (true) {
            val allocation = staging.allocate(total, SubmissionUploadArena.commonAlignment(alignment, 512L),
                GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_COPY_SRC, minimumAllocation)
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
        MCDR_ABI.D3D12CopyBuffer(ctx, (source.buffer() as D3D12GpuBuffer).handle(), source.offset(),
            (destination.buffer() as D3D12GpuBuffer).handle(), destination.offset(), total)
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
        mapped.seal(submittedFence)
        staging.seal(submittedFence)
        buffers.seal(submittedFence)
    }

    fun close() {
        if (closed) return
        closed = true
        mapped.closeAfterCompletion()
        staging.closeAfterCompletion()
        buffers.drainAfterCompletion()
    }

    private fun totalBytes(data: List<ByteBuffer>): Long = data.fold(0L) { total, buffer ->
        Math.addExact(total, buffer.remaining().toLong())
    }
}
