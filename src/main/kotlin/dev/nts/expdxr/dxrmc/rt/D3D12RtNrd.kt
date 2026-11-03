package dev.nts.expdxr.dxrmc.rt

import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.misc.IllusionLogger
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * NRD 后端（S4 4-c）。第一步只读库自己算出来的实例清单：pipeline 数、常量缓冲上限、纹理池尺寸与
 * 寄存器空间全部由库决定，手填对不上就是整段降噪不出图。清单口径见 docs/rt/README.md §20。
 */
internal object D3D12RtNrd {
    /** 缓冲区容量：native 写满即截断，每行一条事实。 */
    private const val TEXT_CAPACITY = 2048

    private var inspected = false

    /** 上一次建上下文的设备与尺寸；三者任一变化都要重建（纹理池按屏幕尺寸开）。 */
    private var reported = false
    private var failed = false

    /** 上下文是否就绪：`ensure` 建成功后为 true，`shutdown` 清掉。 */
    private var ready = false

    private var lastDevice: D3D12Device? = null
    private var lastWidth = 0
    private var lastHeight = 0

    /** 只读一次：清单是常量，每帧重读没有意义。 */
    fun inspectOnce() {
        if (inspected) {
            return
        }
        inspected = true
        val lines = report() ?: return
        for (line in lines) {
            IllusionLogger.infoMessage(line)
        }
    }

    /**
     * 建常驻上下文：实例、每条着色器一条 PSO、两套纹理池、一张描述符堆。
     * 设备或分辨率变了会整体重建，所以每帧调都不会有额外开销。
     */
    fun ensure(device: D3D12Device?, width: Int, height: Int) {
        if (device == null || width <= 0 || height <= 0) {
            return
        }
        if (device === lastDevice && width == lastWidth && height == lastHeight) {
            return
        }
        lastDevice = device
        lastWidth = width
        lastHeight = height
        var result = 0
        var text = ""
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(TEXT_CAPACITY.toLong())
            result = MCDR_ABI.D3D12NrdInit(width, height, buffer, TEXT_CAPACITY)
            text = runCatching { buffer.getString(0) }.getOrDefault("")
        }
        ready = result > 0
        reported = false
        failed = false
        val lines = text.lines().filter { it.isNotBlank() }
        for (line in lines) {
            if (result > 0) IllusionLogger.infoMessage(line) else IllusionLogger.warnMessage(line)
        }
        if (lines.isEmpty()) {
            if (result == 0) {
                IllusionLogger.infoMessage("nrd: not built in")
            } else {
                IllusionLogger.warnMessage("nrd: context unavailable")
            }
        }
    }

    fun reset() {
        if (ready) return
        lastWidth = 0
        lastHeight = 0
    }

    fun denoise(
        encoder: Long,
        motion: Long,
        normalRoughness: Long,
        viewZ: Long,
        radianceIn: Long,
        radianceOut: Long,
        specularIn: Long,
        specularOut: Long,
        matrices: MemorySegment,
        jitter: FloatArray,
        frameIndex: Int,
        resetHistory: Boolean,
    ): Int {
        if (!ready) {
            return 0
        }
        val textures = longArrayOf(motion, normalRoughness, viewZ, radianceIn, radianceOut, specularIn, specularOut)
        var result = 0
        var text = ""
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(TEXT_CAPACITY.toLong())
            val offsets = arena.allocate(MCDRLayout.NRD_JITTER_FLOAT_COUNT.toLong() * Float.SIZE_BYTES, 4L)
            require(jitter.size == MCDRLayout.NRD_JITTER_FLOAT_COUNT)
            for (index in jitter.indices) {
                offsets.setAtIndex(ValueLayout.JAVA_FLOAT, index.toLong(), jitter[index])
            }
            result =
                MCDR_ABI.D3D12NrdDenoiseTemporal(
                    encoder,
                    textures,
                    matrices,
                    offsets,
                    frameIndex,
                    resetHistory,
                    buffer,
                    TEXT_CAPACITY,
                )
            text = runCatching { buffer.getString(0) }.getOrDefault("")
        }
        if (result <= 0) {
            failOnce(result, text)
        } else if (!reported || failed) {
            reported = true
            failed = false
            // 成功也报一次：`nrd: dispatch passes=<n> missing=<m>` 是「降噪真的派发了」的唯一证据。
            val lines = text.lines().filter { it.isNotBlank() }
            IllusionLogger.infoMessage(lines.lastOrNull { it.startsWith("nrd: dispatch") } ?: "nrd: dispatch ok")
            for (line in lines) {
                if (line.startsWith("nrd: dispatch")) {
                    continue
                }
                // 首帧的逐 pass 表只在会话开始时报一次：线程组尺寸与管线号是排查「派发后挂死」的唯一线索。
                IllusionLogger.debugMessage(line)
            }
        }
        return result
    }

    /** 失败只说一次：每帧刷屏没有意义，原因见 docs/rt/README.md §23.2。 */
    private fun failOnce(result: Int, text: String) {
        if (failed) {
            return
        }
        reported = true
        failed = true
        val reason = text.lines().firstOrNull { it.isNotBlank() } ?: "nrd: dispatch failed ($result)"
        IllusionLogger.warnMessage(reason)
    }

    /** 设备销毁前调一次，避免 NRD 的实例和描述符堆跟着设备泄漏。 */
    fun shutdown() {
        if (lastDevice == null) {
            return
        }
        lastDevice = null
        lastWidth = 0
        lastHeight = 0
        ready = false
        reported = false
        failed = false
        MCDR_ABI.D3D12NrdShutdown()
    }

    private fun report(): List<String>? {
        var result = 0
        var text = ""
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(TEXT_CAPACITY.toLong())
            result = MCDR_ABI.D3D12NrdInspect(buffer, TEXT_CAPACITY)
            text = runCatching { buffer.getString(0) }.getOrDefault("")
        }
        val lines = text.lines().filter { it.isNotBlank() }
        return when {
            result > 0 -> lines.ifEmpty { null }
            result == 0 -> {
                IllusionLogger.infoMessage("nrd: not built in")
                null
            }
            else -> {
                for (line in lines) {
                    IllusionLogger.warnMessage(line)
                }
                if (lines.isEmpty()) {
                    IllusionLogger.warnMessage("nrd: inspect unavailable")
                }
                null
            }
        }
    }
}
