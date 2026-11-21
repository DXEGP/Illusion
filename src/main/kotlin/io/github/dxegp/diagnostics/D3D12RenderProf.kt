package io.github.dxegp.diagnostics

import io.github.dxegp.d3d12.shader.D3D12StackSampler
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * 渲染线程分段计时：mixin 在目标方法 HEAD/RETURN 调 begin/end，每 [INTERVAL] 帧输出一次均值。
 * 互操作硬约束：本类是 Java mixin 的埋点目标，它们以 `D3D12RenderProf.begin(x)` / `D3D12RenderProf.RENDER_GROUP`
 * 这种**静态**形式访问，所以方法必须 `@JvmStatic`、阶段常量必须 `const val`（普通 `val` 不会生成寄宿类静态字段，
 * 编译期就找不到）。
 */
object D3D12RenderProf {

    @JvmField
    val ENABLED: Boolean = RenderProfiling.enabled

    private const val INTERVAL = 300

    const val FRAME = 0

    const val EXTRACT = 1

    const val RENDER = 2

    const val WORLD = 3

    const val GUI = 4

    const val BLIT = 5

    const val PRESENT = 6

    const val SUBMIT_FEATURES = 7

    const val PREPARE_CHUNKS = 8

    const val COMPILE_SECTIONS = 9

    const val UPLOAD_TERRAIN = 10

    const val RENDER_GROUP = 11

    const val OCCLUSION = 12

    const val PREPARE_FEATURES = 13

    const val EXECUTE_FRAMEGRAPH = 14

    const val EXEC_FEATURES = 15

    private const val N = 16

    private val acc = LongArray(N)
    private val mark = LongArray(N)
    private var lastFrameStartNs = 0L
    private var accIntervalNs = 0L
    private var frames = 0
    private var samplerStarted = false
    private var indexedDrawCalls = 0L
    private var indexedDrawNanos = 0L
    private var multiDrawBatches = 0L
    private var multiDrawCount = 0L
    private var multiDrawNanos = 0L

    private var gcCountBase = -1L
    private var gcTimeBase = -1L

    internal fun recordIndexedDraw(started: Long) {
        if (started == 0L) return
        indexedDrawCalls++
        indexedDrawNanos += System.nanoTime() - started
    }

    internal fun recordMultiDraw(drawCount: Int, started: Long) {
        if (started == 0L) return
        multiDrawBatches++
        multiDrawCount += drawCount
        multiDrawNanos += System.nanoTime() - started
    }

    internal fun logRasterDrawTiming(frameCount: Int) {
        val inverseFrames = 1.0 / frameCount
        IllusionLogger.debugMessage("core: batches/frame=${multiDrawBatches * inverseFrames}"
            + " draws/frame=${multiDrawCount * inverseFrames}"
            + " batchPath=${ms(multiDrawNanos * inverseFrames)}ms"
            + " drawIndexedCalls/frame=${indexedDrawCalls * inverseFrames}"
            + " drawIndexed=${ms(indexedDrawNanos * inverseFrames)}ms")
        indexedDrawCalls = 0L
        indexedDrawNanos = 0L
        multiDrawBatches = 0L
        multiDrawCount = 0L
        multiDrawNanos = 0L
    }

    @JvmStatic
    fun begin(stage: Int) {
        if (ENABLED) {
            mark[stage] = System.nanoTime()
        }
    }

    @JvmStatic
    fun end(stage: Int) {
        if (ENABLED) {
            acc[stage] += System.nanoTime() - mark[stage]
        }
    }

    /** 帧首：累计相邻帧起始的时间差（即真实 frameInterval）。 */
    @JvmStatic
    fun frameStart() {
        if (!ENABLED) {
            return
        }
        if (!samplerStarted) {
            samplerStarted = true
            D3D12StackSampler.attach()
            D3D12StackSampler.start()
        }
        val now = System.nanoTime()
        if (lastFrameStartNs != 0L) {
            accIntervalNs += now - lastFrameStartNs
        }
        lastFrameStartNs = now
    }

    @JvmStatic
    fun frameEnd() {
        if (!ENABLED) {
            return
        }
        if (++frames % INTERVAL != 0) {
            return
        }
        val inv = 1.0 / INTERVAL
        IllusionLogger.debugMessage(
            "render n=$INTERVAL" +
                " frameInterval=${ms(accIntervalNs * inv)}ms" +
                " frame=${ms(acc[FRAME] * inv)}ms" +
                " extract=${ms(acc[EXTRACT] * inv)}ms" +
                " render=${ms(acc[RENDER] * inv)}ms" +
                "[world=${ms(acc[WORLD] * inv)} gui=${ms(acc[GUI] * inv)}]ms" +
                " blit=${ms(acc[BLIT] * inv)}ms" +
                " present=${ms(acc[PRESENT] * inv)}ms"
        )
        IllusionLogger.debugMessage(
            "world n=$INTERVAL" +
                " submitFeatures=${ms(acc[SUBMIT_FEATURES] * inv)}ms" +
                " prepareChunks=${ms(acc[PREPARE_CHUNKS] * inv)}ms" +
                " renderGroup=${ms(acc[RENDER_GROUP] * inv)}ms" +
                " compileSections=${ms(acc[COMPILE_SECTIONS] * inv)}ms" +
                " uploadTerrain=${ms(acc[UPLOAD_TERRAIN] * inv)}ms" +
                " occlusion=${ms(acc[OCCLUSION] * inv)}ms"
        )
        IllusionLogger.debugMessage(
            "world2 n=$INTERVAL" +
                " prepareFeatures=${ms(acc[PREPARE_FEATURES] * inv)}ms" +
                " execFrameGraph=${ms(acc[EXECUTE_FRAMEGRAPH] * inv)}ms" +
                " [execFeaturesSolid=${ms(acc[EXEC_FEATURES] * inv)}ms]" +
                " ${gcStats()}"
        )
        IllusionLogger.debugMessage(D3D12StackSampler.report(10))
        System.err.flush()
        acc.fill(0L)
        accIntervalNs = 0L
    }

    private fun ms(ns: Double): String = "%.2f".format(Locale.ROOT, ns / 1_000_000.0)

    private fun gcStats(): String {
        var count = 0L
        var time = 0L
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            val c = gc.collectionCount
            val t = gc.collectionTime
            if (c > 0L) {
                count += c
            }
            if (t > 0L) {
                time += t
            }
        }
        if (gcCountBase < 0L) {
            gcCountBase = count
            gcTimeBase = time
        }
        val dc = count - gcCountBase
        val dt = time - gcTimeBase
        gcCountBase = count
        gcTimeBase = time
        val usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20
        return "gc(per%dF)=%d/%.1fms heap=%dMB".format(Locale.ROOT, INTERVAL, dc, dt.toDouble(), usedMb)
    }
}
