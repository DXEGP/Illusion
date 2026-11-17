package io.github.dxegp.diagnostics

import io.github.dxegp.config.D3D12Settings

internal object RenderProfiling {
    val enabled: Boolean
        get() = D3D12Settings.profiling || IllusionLogger.debugEnabled()
}
