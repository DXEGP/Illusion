package dev.nts.expdxr.dxrmc.rt

import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.misc.IllusionLogger
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * DLSS-D（Ray Reconstruction）逐帧求值的 Java 侧入口（S4 4-d）。
 *
 * 游戏进程里 NGX 由 Streamline 持有，所以求值只能交给 native 的 `mcdr_dx12DlssdEval`（它转去
 * `slSetTagForFrame` + `slEvaluateFeature`）。SL 拒绝过一次就不再试，调用方回退到 NRD，
 * 原因与判据见 docs/rt/README.md §25。
 */
internal object D3D12RtDlssd {
    private const val TEXT_CAPACITY = 256

    /** 一次性诊断：成功报一行，失败报一行。 */
    private var reported = false

    /** SL 拒绝过（返回 0 或负数）就整局不再尝试。 */
    private var available = true

    /** SL 的 frame token 每帧都要新取，这里自己数。 */
    private var frameIndex = 0

    /** `slSetConstants` 的载荷：每帧内容都变，所以复用同一段本地内存。 */
    private var constantsArena: Arena? = null
    private var constants: MemorySegment? = null

    /**
     * 求值一帧。纹理顺序是 radiance / output / depth / motion / normal（打包的法线-粗糙度）。
     *
     * [slConstants] 是 `D3D12RtFrameConstants.write` 填的 72 个浮点（四个行主序矩阵 + 近/远/视场/宽高比）：
     * DLSS-D 缺了这套常量不会报错，只会按「零位移」重投影，画面表现为拖影。
     *
     * 返回 1 表示 SL 接受，0 表示不可用（已放弃），-1 表示这一帧失败。
     */
    fun evaluate(
        encoder: Long,
        radiance: Long,
        output: Long,
        depth: Long,
        motion: Long,
        normal: Long,
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
        slConstants: FloatArray,
        reset: Boolean,
    ): Int {
        if (!available) {
            return 0
        }
        val textures = longArrayOf(radiance, output, depth, motion, normal)
        var result = 0
        var text = ""
        frameIndex += 1
        val constantSegment = constantSegment(slConstants) ?: return 0
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(TEXT_CAPACITY.toLong())
            result =
                MCDR_ABI.D3D12DlssdEval(
                    encoder,
                    textures,
                    width,
                    height,
                    outputWidth,
                    outputHeight,
                    frameIndex,
                    reset,
                    constantSegment,
                    buffer,
                    TEXT_CAPACITY,
                )
            text = runCatching { buffer.getString(0) }.getOrDefault("")
        }
        if (result <= 0) {
            available = false
            if (!reported) {
                reported = true
                val why = text.ifBlank { "no diagnostic" }
                IllusionLogger.warnMessage("rt dlssd: unavailable ($why), falling back to nrd")
            }
            return 0
        }
        if (!reported) {
            reported = true
            IllusionLogger.infoMessage(text.ifBlank { "dlssd: eval ok" })
        }
        return result
    }

    /** 设备重建或重新切到 RR 档时允许再试一次。 */
    fun reset() {
        available = true
        reported = false
        frameIndex = 0
        constantsArena?.close()
        constantsArena = null
        constants = null
    }

    /** 把 72 个浮点摊进本地内存，复用同一段，避免每帧新建 Arena。 */
    private fun constantSegment(values: FloatArray): MemorySegment? {
        if (values.size < D3D12RtFrameConstants.SL_CONSTANTS_FLOATS) {
            return null
        }
        val arena = constantsArena ?: Arena.ofConfined().also { constantsArena = it }
        val segment = constants ?: arena
            .allocate(
                D3D12RtFrameConstants.SL_CONSTANTS_FLOATS.toLong() * Float.SIZE_BYTES,
                Long.SIZE_BYTES.toLong(),
            )
            .also { constants = it }
        for (i in 0 until D3D12RtFrameConstants.SL_CONSTANTS_FLOATS) {
            segment.setAtIndex(ValueLayout.JAVA_FLOAT, i.toLong(), values[i])
        }
        return segment
    }
}
