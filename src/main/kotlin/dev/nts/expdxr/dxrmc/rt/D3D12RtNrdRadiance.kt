package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger

/**
 * 把降采样的辐亮度缓冲搬成 NRD 要的 2D 纹理（S4 4-c）。见 docs/rt/README.md §23.5
 *
 * NRD 只认 `Texture2D`，而光追那条链的辐亮度是 `(direct, indirect, aux)` 三块
 * `ByteAddressBuffer`：这里按最近邻放大到屏幕分辨率，并把命中距离归一化进 A 通道。
 */
internal object D3D12RtNrdRadiance {
    /** 算出来之后当 SRV 喂给 NRD，所以既要能写也要能读。 */
    private const val USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_TEXTURE_BINDING

    private const val THREADS = 8

    /** NRD 的 `denoisingRange`：命中距离按它归一化，派发时原样传进 native。 */
    const val DENOISING_RANGE = 4096f

    private var radiance: D3D12GpuTexture? = null
    private var output: D3D12GpuTexture? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var pipeline = 0L
    private var reported = false

    /** NRD 的辐亮度输入；没写过就是 null。 */
    fun radianceTexture(): D3D12GpuTexture? = radiance

    /** NRD 的辐亮度输出（降噪结果）；没写过就是 null。 */
    fun outputTexture(): D3D12GpuTexture? = output

    /**
     * 按 [direct] / [indirect] / [aux] 三块缓冲生成一张屏幕分辨率的辐亮度纹理。
     * 必须在光追派发之后调（三块缓冲这一帧刚写完）。
     */
    fun render(
        encoder: D3D12CommandEncoderBackend?,
        direct: Long,
        indirect: Long,
        aux: Long,
        width: Int,
        height: Int,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        if (encoder == null || direct == 0L || indirect == 0L || aux == 0L || cbv == 0L) {
            return false
        }
        if (width <= 0 || height <= 0) return false
        val started = System.nanoTime()
        val device = encoder.device ?: return false
        val targets = ensureTargets(device, width, height) ?: return false
        val radiance = targets.first
        val denoised = targets.second
        val pipeline = ensurePipeline() ?: return false
        encoder.ensureListOpen()
        // 两张目标每帧都重写：不插屏障会读到上一帧的内容。
        MCDR_ABI.D3D12Barrier(
            encoder.nativeHandle(),
            listOf(
                MCDR_ABI.BarrierDesc(radiance.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
                MCDR_ABI.BarrierDesc(denoised.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
            ),
        )
        val radianceSrvs = longArrayOf(direct, indirect, aux)
        val radianceUavs = longArrayOf(radiance.handle())
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(),
            pipeline,
            radianceSrvs,
            radianceUavs,
            intArrayOf(DENOISING_RANGE.toRawBits()),
            cbv,
            cbvOffset,
            (width + THREADS - 1) / THREADS,
            (height + THREADS - 1) / THREADS,
            1,
        )
        if (!ok) {
            fail("radiance dispatch failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return true
    }

    private fun ensureTargets(
        device: D3D12Device,
        width: Int,
        height: Int,
    ): Pair<D3D12GpuTexture, D3D12GpuTexture>? {
        val first = radiance
        val second = output
        if (first != null && second != null && targetWidth == width && targetHeight == height) {
            return first to second
        }
        try {
            val source = device.createTexture(
                "rt-nrd-radiance", USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException("rt-nrd-radiance allocate failed (${MCDR_ABI.D3D12LastError()})")
            val result = device.createTexture(
                "rt-nrd-radiance-out", USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException("rt-nrd-radiance-out allocate failed (${MCDR_ABI.D3D12LastError()})")
            radiance = source
            output = result
        } catch (t: Throwable) {
            fail("radiance allocate failed: ${t.message}")
            return null
        }
        targetWidth = width
        targetHeight = height
        IllusionLogger.infoMessage("rt: nrd radiance ${width}x$height")
        return radiance!! to output!!
    }

    private fun ensurePipeline(): Long? {
        val cached = pipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtNrdRadianceShader.build(), D3D12RtNrdRadianceShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("radiance compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtNrdRadianceShader.SRV_COUNT, D3D12RtNrdRadianceShader.UAV_COUNT)
        if (handle == 0L) {
            fail("radiance pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        pipeline = handle
        return handle
    }

    /** 还回纹理与管线：compute 管线只能走 `ID3D12DestroyResource`（见 §9.8）。 */
    fun release() {
        radiance?.close()
        radiance = null
        output?.close()
        output = null
        targetWidth = 0
        targetHeight = 0
        if (pipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(pipeline)
            pipeline = 0L
        }
    }

    private fun fail(message: String) {
        if (reported) return
        reported = true
        IllusionLogger.warnMessage("rt nrd radiance: $message")
    }
}
