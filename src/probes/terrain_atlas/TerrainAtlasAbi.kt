package io.github.dxegp.d3d12.ffi

import java.nio.ByteBuffer

object MCDR_ABI {
    data class BufferState(val usage: Int, val bytes: ByteBuffer, var destroys: Int = 0)

    val buffers = linkedMapOf<Long, BufferState>()
    val completedFences = mutableMapOf<Long, Long>()
    var copyCalls = 0
    var failCopyAt = 0
    private var nextHandle = 0L

    fun ID3D12CreateBuffer(usage: Int, size: Long): Long {
        val handle = ++nextHandle
        buffers[handle] = BufferState(usage, ByteBuffer.allocate(Math.toIntExact(size)))
        return handle
    }

    fun ID3D12DestroyResource(handle: Long) {
        check(++buffers.getValue(handle).destroys == 1) { "Buffer $handle was destroyed twice" }
    }

    fun D3D12MapBuffer(handle: Long, offset: Long, length: Long,
                       read: Boolean, write: Boolean): ByteBuffer? {
        check(read || write)
        val state = buffers.getValue(handle)
        check(state.destroys == 0)
        return state.bytes.slice(Math.toIntExact(offset), Math.toIntExact(length))
    }

    fun D3D12UnmapBuffer(handle: Long) {
        check(buffers.getValue(handle).destroys == 0)
    }

    fun D3D12WaitForFence(ctx: Long, value: Long, timeoutNs: Long): Boolean {
        check(timeoutNs == 0L)
        return value <= completedFences.getOrDefault(ctx, 0L)
    }

    fun D3D12IsListOpen(ctx: Long): Boolean = ctx > 0L

    fun D3D12CopyBufferChecked(ctx: Long, source: Long, sourceOffset: Long,
                              target: Long, targetOffset: Long, bytes: Long): Boolean {
        check(D3D12IsListOpen(ctx))
        if (++copyCalls == failCopyAt) return false
        val sourceState = buffers.getValue(source)
        val targetState = buffers.getValue(target)
        check(sourceState.destroys == 0 && targetState.destroys == 0)
        targetState.bytes.slice(Math.toIntExact(targetOffset), Math.toIntExact(bytes))
            .put(sourceState.bytes.slice(Math.toIntExact(sourceOffset), Math.toIntExact(bytes)))
        return true
    }

    fun D3D12CopyBuffer(ctx: Long, source: Long, sourceOffset: Long,
                       target: Long, targetOffset: Long, bytes: Long) {
        check(D3D12CopyBufferChecked(ctx, source, sourceOffset, target, targetOffset, bytes))
    }

    fun D3D12LastError(): String = "Injected atlas copy failure"
}
