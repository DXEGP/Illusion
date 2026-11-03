package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.RtExposureMode
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings
import kotlin.math.exp

internal object D3D12RtExposure {
    const val SRV_COUNT = 7
    const val UAV_COUNT = 1
    private var output: D3D12GpuTexture? = null
    private var pipeline = 0L
    private var lastFrameNanos = 0L
    private var lastMode: RtExposureMode? = null
    private var lastEv = Float.NaN
    private var failed = false

    fun texture(): D3D12GpuTexture? = output

    fun render(
        encoder: D3D12CommandEncoderBackend,
        direct: Long, indirect: Long, specular: Long, specularGrazing: Long,
        albedo: Long, emissive: Long, specularMaterial: Long,
        cbv: Long, cbvOffset: Long, frameNanos: Long, reset: Boolean,
    ): Boolean {
        val device = encoder.device ?: return false
        if (pipeline == 0L) {
            val source = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath("rt_exposure.hlsl"))
            val bytecode = MCDR_ABI.D3D12CompileShaderDxc(source, "RtExposure", "cs_6_8")
                ?: return fail()
            pipeline = MCDR_ABI.D3D12CreateComputePipelineEx(bytecode, SRV_COUNT, UAV_COUNT)
            if (pipeline == 0L) return fail()
        }
        val created = output == null
        val exposure = output ?: (device.createTexture("rt-exposure",
            D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.R32_FLOAT, 1, 1, 1, 1) as? D3D12GpuTexture)?.also { output = it } ?: return fail()
        val mode = D3D12Settings.rtExposureMode
        val ev = D3D12Settings.rtExposureEv
        val elapsed = ((frameNanos - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.5)
        val resetExposure = reset || created || failed || lastMode != mode || lastEv != ev ||
            lastFrameNanos == 0L || frameNanos <= lastFrameNanos
        val blend = (1.0 - exp(-D3D12Settings.rtExposureSpeed * elapsed)).toFloat()
        val exposureSrvs = longArrayOf(direct, indirect, specular, specularGrazing, albedo, emissive, specularMaterial)
        val exposureUavs = longArrayOf(exposure.handle())
        encoder.ensureListOpen()
        if (!MCDR_ABI.D3D12DispatchComputeEx(encoder.nativeHandle(), pipeline, exposureSrvs, exposureUavs,
                intArrayOf(if (mode == RtExposureMode.AUTO) 1 else 0, ev.toRawBits(), blend.toRawBits(),
                    if (resetExposure) 1 else 0), cbv, cbvOffset, 1, 1, 1)) return fail()
        lastFrameNanos = frameNanos
        lastMode = mode
        lastEv = ev
        failed = false
        D3D12RtStats.onDispatches(1)
        return true
    }

    private fun fail(): Boolean {
        if (!failed) IllusionLogger.warnMessage("rt exposure: update failed")
        failed = true
        return false
    }

    fun release() {
        output?.close()
        output = null
        if (pipeline != 0L) MCDR_ABI.ID3D12DestroyResource(pipeline)
        pipeline = 0L
        lastFrameNanos = 0L
        lastMode = null
        lastEv = Float.NaN
        failed = false
    }
}
