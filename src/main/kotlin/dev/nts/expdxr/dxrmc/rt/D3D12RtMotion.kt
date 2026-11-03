package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger

/**
 * 运动矢量目标（S4 4-b）：一张与 G-Buffer 同尺寸的 RGBA16F，在光追派发之后按线性深度重投影得到。
 *
 * 为什么单独一张纹理而不加进 G-Buffer 的附件表：那个 pass 的 PSO 是按主 pass 的 RTV 数建的 mesh 变体，
 *
 * 第六个 SV_Target 没有对应的格式表，只能在 pass 之后另开一次计算。见 docs/rt/README.md §19
 */
internal object D3D12RtMotion {
    /** 与 [D3D12RtGBuffer] 的线性深度附件同一套用量：算出来之后当 SRV 读。 */
    private const val USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_TEXTURE_BINDING

    private const val THREADS = 8

    private var target: D3D12GpuTexture? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var pipeline = 0L
    private var reported = false

    /** 当前纹理；没人写过就是 null（调用方自己决定退回什么）。 */
    fun texture(): D3D12GpuTexture? = target

    /**
     * 按 [depth]（G-Buffer 的线性深度）重投影出运动矢量，返回写好的纹理。
     *
     * 依赖 [cbv] 里这一帧的 `g_viewProj` / `g_prevViewProj`：必须在这一帧的常量写完之后调，
     * 否则上一帧矩阵还是上一帧的上一帧。
     */
    fun render(
        encoder: D3D12CommandEncoderBackend?,
        depth: D3D12GpuTexture?,
        cbv: Long,
        cbvOffset: Long,
        jitterDeltaX: Float = 0f,
        jitterDeltaY: Float = 0f,
    ): D3D12GpuTexture? {
        if (encoder == null || depth == null || cbv == 0L) return null
        val started = System.nanoTime()
        val device = encoder.device ?: return null
        val width = depth.getWidth(0)
        val height = depth.getHeight(0)
        if (width <= 0 || height <= 0) return null
        val motion = ensureTarget(device, width, height) ?: return null
        val pipeline = ensurePipeline() ?: return null
        encoder.ensureListOpen()
        // 同一张纹理上一帧也被这个 kernel 写过：不插屏障会读到上一帧的内容。
        MCDR_ABI.D3D12Barrier(
            encoder.nativeHandle(),
            listOf(MCDR_ABI.BarrierDesc(motion.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV)),
        )
        val motionSrvs = longArrayOf(depth.handle())
        val motionUavs = longArrayOf(motion.handle())
        val constants = intArrayOf(D3D12RtGBuffer.DEPTH_SCALE.toRawBits(),
            jitterDeltaX.toRawBits(), jitterDeltaY.toRawBits())
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, motionSrvs, motionUavs, constants, cbv, cbvOffset,
            (width + THREADS - 1) / THREADS, (height + THREADS - 1) / THREADS, 1,
        )
        if (!ok) {
            fail("motion dispatch failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return motion
    }

    private fun ensureTarget(device: D3D12Device, width: Int, height: Int): D3D12GpuTexture? {
        val current = target
        if (current != null && targetWidth == width && targetHeight == height) return current
        val created = try {
            device.createTexture("rt-motion", USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException("rt-motion allocate failed (${MCDR_ABI.D3D12LastError()})")
        } catch (t: Throwable) {
            fail("motion allocate failed: ${t.message}")
            return null
        }
        target = created
        current?.close()
        targetWidth = width
        targetHeight = height
        IllusionLogger.infoMessage("rt: motion ${width}x$height")
        return created
    }

    private fun ensurePipeline(): Long? {
        val cached = pipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtMotionShader.build(), D3D12RtMotionShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("motion compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtMotionShader.SRV_COUNT, D3D12RtMotionShader.UAV_COUNT)
        if (handle == 0L) {
            fail("motion pipeline failed: ${MCDR_ABI.D3D12LastError()}")
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
        target?.close()
        target = null
        targetWidth = 0
        targetHeight = 0
        if (pipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(pipeline)
            pipeline = 0L
        }
    }

    /** 一次性告警：这条链失败时画面只是少一个引导缓冲，不该每帧刷日志。 */
    private fun fail(message: String) {
        if (reported) return
        reported = true
        IllusionLogger.warnMessage("rt motion: $message")
    }
}
