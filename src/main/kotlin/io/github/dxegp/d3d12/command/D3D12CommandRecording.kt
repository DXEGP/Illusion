package io.github.dxegp.d3d12.command

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.backend.api.RenderPassBackend
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import java.nio.ByteBuffer

internal enum class D3D12CommandScope {
    FRAME,
    UPLOAD,
}

internal interface D3D12CommandRecordingFactory : AutoCloseable {
    fun create(scope: D3D12CommandScope): D3D12CommandRecording
}

internal interface D3D12PreparedBufferUpload {
    val payload: ByteBuffer
    fun confirmRecorded()
    fun discard()
}

internal interface D3D12CommandRecording : AutoCloseable {
    fun prepareBufferUpload(destination: GpuBufferSlice, data: ByteBuffer): D3D12PreparedBufferUpload?
    fun beginRasterPass(context: Long): Long
    fun finishRasterPass(context: Long, token: Long)
    fun failRasterPass(context: Long, token: Long)
    fun createRenderPass(
        device: D3D12Device?,
        context: Long,
        descriptor: RenderPassDescriptor,
        transientMemory: D3D12TransientMemory,
    ): RenderPassBackend
}
