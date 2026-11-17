package io.github.dxegp.render.frame.vanilla

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer

internal object VanillaFrameInputResources {
    const val DEPTH_CAPTURE_PASS = 0x30001
    const val VELOCITY_PASS = 0x30002
    const val PROVIDER_MASK_PASS = 0x30003
    const val VRS_DEPTH_CAPTURE_PASS = 0x30004
    const val VRS_INPUT_PASS = 0x30005
    const val OPAQUE_CAPTURE_PASS = 0x3000D
    const val OPAQUE_VELOCITY_PASS = 0x3000E

    const val STATE_COMMON = 0
    const val STATE_RENDER_TARGET = 0x4
    const val STATE_NON_PIXEL_SHADER_RESOURCE = 0x40
    const val STATE_SHADER_RESOURCE = 0xC0
    const val STATE_COPY_DEST = 0x400
    const val STATE_COPY_SOURCE = 0x800
    const val STATE_UNORDERED_ACCESS = 0x8
    private const val STATE_VERTEX_AND_CONSTANT_BUFFER = 0x1
    private const val STATE_GENERIC_READ = 0xAC3

    fun texture(handle: Long, version: Long, access: Int, requiredState: Int,
        finalState: Int = requiredState): MCDR_ABI.VanillaResourceUse =
        MCDR_ABI.VanillaResourceUse(handle, version, firstSubresource = 0, subresourceCount = 1, access = access,
            requiredState = requiredState, finalState = finalState)

    fun textureCopy(source: Long, target: Long, version: Long): List<MCDR_ABI.VanillaResourceUse> =
        listOf(
            MCDR_ABI.VanillaResourceUse(source, version, access = 1,
                requiredState = STATE_COPY_SOURCE, finalState = STATE_COMMON),
            MCDR_ABI.VanillaResourceUse(target, version, access = 2,
                requiredState = STATE_COPY_DEST, finalState = STATE_COMMON),
        )

    fun velocityBuffers(uniform: GpuBufferSlice, objects: GpuBufferSlice,
        version: Long): List<MCDR_ABI.VanillaResourceUse> {
        val states = linkedMapOf<Long, Int>()
        fun add(slice: GpuBufferSlice, requested: Int) {
            val buffer = slice.buffer() as D3D12GpuBuffer
            val state = if ((buffer.usage() and GpuBuffer.USAGE_MAP_WRITE) != 0)
                STATE_GENERIC_READ else requested
            states[buffer.handle()] = (states[buffer.handle()] ?: 0) or state
        }
        add(uniform, STATE_VERTEX_AND_CONSTANT_BUFFER)
        add(objects, STATE_SHADER_RESOURCE)
        return states.map { (handle, state) ->
            MCDR_ABI.VanillaResourceUse(handle, version, firstSubresource = 0,
                subresourceCount = 1, access = 1, requiredState = state, finalState = state)
        }
    }
}
