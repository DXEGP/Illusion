package io.github.dxegp.render.antialiasing

import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator

object D3D12TAA {
    internal const val BLEND = 0.0625f

    @JvmStatic
    fun isSelected(): Boolean = (VanillaFrameCoordinator.currentFrame()?.settings?.antiAliasing
        ?: D3D12AntiAliasing.current()) == D3D12AntiAliasing.TAA
}
