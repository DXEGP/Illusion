package io.github.dxegp.d3d12.ffi

object MCDR_ABI {
    private var nextHandle = 0L
    private val live = mutableSetOf<Long>()

    fun ID3D12CreateBuffer(usage: Int, size: Long): Long {
        check(usage != 0 && size > 0L)
        return (++nextHandle).also { live.add(it) }
    }

    fun ID3D12DestroyResource(handle: Long) {
        check(live.remove(handle))
    }
}
