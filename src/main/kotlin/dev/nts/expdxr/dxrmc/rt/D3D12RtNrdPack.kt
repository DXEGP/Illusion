package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger

/**
 * NRD 的输入编码转换（S4 4-c）：把 G-Buffer 的法线-粗糙度与线性深度转成 NRD 认的两种排布。
 *
 * 库的编码在编译期定死（`R10G10B10A2_UNORM` + `LINEAR` 粗糙度，见 `D3D12RtNrd` 打出的清单），
 * 打包公式必须与 `NRD.hlsli` 的 `NRD_FrontEnd_PackNormalAndRoughness` 逐位一致。见 docs/rt/README.md §21
 */
internal object D3D12RtNrdPack {
    /** 同 [D3D12RtMotion]：算出来之后当 SRV 读，所以既要能写也要能读。 */
    private const val USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_TEXTURE_BINDING

    private const val THREADS = 8

    private var normalRoughness: D3D12GpuTexture? = null
    private var viewZ: D3D12GpuTexture? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var pipeline = 0L
    private var reported = false

    /** NRD 的法线-粗糙度输入；没写过就是 null。 */
    fun normalRoughnessTexture(): D3D12GpuTexture? = normalRoughness

    /** NRD 的 viewZ 输入；没写过就是 null。 */
    fun viewZTexture(): D3D12GpuTexture? = viewZ

    /**
     * 按 [normalRoughnessSource]（G-Buffer 第 2 张附件）与 [depth]（线性深度附件）重新打包一次，
     * 返回是否成功。必须在 G-Buffer pass 与 [D3D12RtMotion] 之后调。
     */
    fun render(
        encoder: D3D12CommandEncoderBackend?,
        normalRoughnessSource: D3D12GpuTexture?,
        depth: D3D12GpuTexture?,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        if (encoder == null || normalRoughnessSource == null || depth == null || cbv == 0L) {
            return false
        }
        val started = System.nanoTime()
        val device = encoder.device ?: return false
        val width = depth.getWidth(0)
        val height = depth.getHeight(0)
        if (width <= 0 || height <= 0) return false
        val targets = ensureTargets(device, width, height) ?: return false
        val packedTarget = targets.first
        val depthTarget = targets.second
        val pipeline = ensurePipeline() ?: return false
        encoder.ensureListOpen()
        // 两张目标上一帧也被这个 kernel 写过：不插屏障会读到上一帧的内容。
        MCDR_ABI.D3D12Barrier(
            encoder.nativeHandle(),
            listOf(
                MCDR_ABI.BarrierDesc(packedTarget.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
                MCDR_ABI.BarrierDesc(depthTarget.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
            ),
        )
        val packSrvs = longArrayOf(normalRoughnessSource.handle(), depth.handle())
        val packUavs = longArrayOf(packedTarget.handle(), depthTarget.handle())
        // b1 只有 8 个 32 位槽：深度尺度按浮点位传，其余留空。
        val constants = intArrayOf(D3D12RtGBuffer.DEPTH_SCALE.toRawBits())
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, packSrvs, packUavs, constants, cbv, cbvOffset,
            (width + THREADS - 1) / THREADS, (height + THREADS - 1) / THREADS, 1,
        )
        if (!ok) {
            fail("pack dispatch failed: ${MCDR_ABI.D3D12LastError()}")
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
        val first = normalRoughness
        val second = viewZ
        if (first != null && second != null && targetWidth == width && targetHeight == height) {
            return first to second
        }
        var created: Pair<D3D12GpuTexture, D3D12GpuTexture>? = null
        try {
            // 引擎的 GpuFormat 没有 10-10-10-2 打包格式（见 §21.3）：这里用 RGBA16F 存同样的归一化打包值，
            // NRD 按 float4 读，数值一模一样，代价只是每像素多 8 字节。
            val packed = device.createTexture(
                "rt-nrd-normal-roughness", USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException(
                    "rt-nrd-normal-roughness allocate failed (${MCDR_ABI.D3D12LastError()})")
            val depth = device.createTexture(
                "rt-nrd-viewz", USAGE, GpuFormat.R32_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException("rt-nrd-viewz allocate failed (${MCDR_ABI.D3D12LastError()})")
            created = packed to depth
        } catch (t: Throwable) {
            fail("pack allocate failed: ${t.message}")
            return null
        }
        normalRoughness = created.first
        viewZ = created.second
        targetWidth = width
        targetHeight = height
        IllusionLogger.infoMessage("rt: nrd pack ${width}x$height")
        return created
    }

    private fun ensurePipeline(): Long? {
        val cached = pipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtNrdPackShader.build(), D3D12RtNrdPackShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("pack compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtNrdPackShader.SRV_COUNT, D3D12RtNrdPackShader.UAV_COUNT)
        if (handle == 0L) {
            fail("pack pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        pipeline = handle
        return handle
    }

    /**
     * 还回纹理与管线。compute 管线是 `Dx12Object*`，只能走 `ID3D12DestroyResource`：
     * 用 `D3D12DestroyPipeline` 释放等于按错类型析构，直接崩进程。见 `docs/rt/README.md` §9.8
     */
    fun release() {
        normalRoughness?.close()
        normalRoughness = null
        viewZ?.close()
        viewZ = null
        targetWidth = 0
        targetHeight = 0
        if (pipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(pipeline)
            pipeline = 0L
        }
    }

    /** 一次性告警：这条链失败时画面只是少两份引导数据，不该每帧刷日志。 */
    private fun fail(message: String) {
        if (reported) return
        reported = true
        IllusionLogger.warnMessage("rt nrd pack: $message")
    }
}
