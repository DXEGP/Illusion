package io.github.dxegp.render.frame.vanilla

import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.antialiasing.D3D12PostSharpen
import io.github.dxegp.render.antialiasing.D3D12TAA
import io.github.dxegp.render.temporal.D3D12Velocity
import java.lang.foreign.MemorySegment

internal fun D3D12CommandEncoderBackend.recordVelocityPass() {
    if (D3D12Velocity.isActive()) recordCommands { D3D12Velocity.record(it, commandDevice()) }
}

internal fun D3D12CommandEncoderBackend.recordSharpen(source: Long, target: Long): Boolean =
    D3D12PostSharpen.isEnabled() && recordCommands { D3D12PostSharpen.record(it, source, target) }

internal fun D3D12CommandEncoderBackend.recordSmaaWorldPass(texture: D3D12GpuTexture): Boolean =
    recordCommands { MCDR_ABI.dx12ApplySmaaToTexture(it, texture.handle()) }

internal fun D3D12CommandEncoderBackend.recordTaaPass(world: D3D12GpuTexture): Boolean =
    recordCommands { D3D12TAA.record(it, commandDevice(), world) }

internal fun D3D12CommandEncoderBackend.recordDlssEvaluate(input: MemorySegment): Int =
    recordCommands { MCDR_ABI.D3D12EvaluateDlss(it, input) }

internal fun D3D12CommandEncoderBackend.recordSrRelease(): Int =
    recordCommands { MCDR_ABI.D3D12SrCreate(it, MCDR_ABI.SR_BACKEND_NONE, 1, 1, 1, 1) }
