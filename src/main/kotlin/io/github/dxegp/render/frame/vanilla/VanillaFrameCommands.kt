package io.github.dxegp.render.frame.vanilla

import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.render.temporal.D3D12Velocity

internal fun D3D12CommandEncoderBackend.recordVelocityPass() {
    if (D3D12Velocity.isActive()) recordCommands { D3D12Velocity.record(it, commandDevice()) }
}
