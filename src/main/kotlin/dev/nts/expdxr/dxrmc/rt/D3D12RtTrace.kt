package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.buffers.GpuBuffer
import dev.nts.expdxr.RtBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.pow

/**
 * B-Mega 光追主通道（S2 2-d）。
 * 见 docs/rt/README.md
 */
object D3D12RtTrace {
    /** 自检分辨率：32×32 覆盖 16 个线程组，够区分命中/未命中又不必等太久。 */
    const val WIDTH: Int = 32
    const val HEIGHT: Int = 32

    /** 逐通道绝对误差上限（验收口径：与 CPU 参考逐像素误差 < 1e-5）。 */
    const val TOLERANCE: Float = 1e-5f

    /**
     * 相对容差：世界坐标到上千单位时 f32 的一个 ULP 就有 6e-5，两条不同的遍历实现差一个 ULP，
     * 直射项就会差这么多。只放宽到「一个 ULP 量级」，真正的阴影翻转（差 0.3 以上）照样会被抓到。
     */
    private const val RELATIVE_TOLERANCE: Float = 1e-4f

    private fun exceeds(diff: Float, reference: Float): Boolean =
        diff > TOLERANCE + RELATIVE_TOLERANCE * abs(reference)

    private const val RECORD_BYTES = 64
    private const val VERTEX_BYTES = 28
    // +0 albedo / +12 emissive / +24 roughness / +28 flags / +32 metalness（docs/rt/README.md §17.2）。
    private const val MATERIAL_BYTES = 40
    // 段边长：暴力档按 [origin - 0.5, origin + 16.5] 剪枝，自检场景必须整颗落在里面。
    private const val SECTION_SIZE = 16f
    private const val RT_DIR = "dxrmc"
    private const val SELF_TEST_FILE = "rt_selftest.json"
    private const val SELF_TEST_PREV_FILE = "rt_selftest.prev.json"
    /** 常量缓冲 = 光影包 `WgaFrame`（416 B）。 */
    private const val SCENE_BYTES = D3D12RtFrameConstants.BYTES

    /** 本项目内部约定：缓冲 usage 位 1024 = 当 `RWByteAddressBuffer` 用。 */
    private const val UAV_BUFFER_USAGE = 1024

    private const val MIN_BUFFER_BYTES = 16

    /* 管线句柄被两个线程碰：渲染线程派发、客户端 tick 跑自检；换包时 apply() 还会释放重建。
       创建/释放必须互斥，否则会重复建（泄漏一条）或把正在派的句柄释放掉。 */
    private val pipelineLock = Any()

    @Volatile private var pipeline: Long = 0L

    /** 加速结构变体的追踪管线（`RT_AS`，SRV 多一个 t5）；0 = 还没编译。 */
    @Volatile private var asPipeline: Long = 0L
    @Volatile private var asPipelineFailed: Boolean = false

    /** 自检当前走到哪一步：失败时靠它定位，光有异常类不够。 */
    private var step = "pipeline"
    /** 自检前的等待 tick 数：世界刚加载完的那几秒排队的工作太多，栅栏等待会把 tick 线程按住。 */
    private const val SELFTEST_DELAY_TICKS = 200

    private var selfTestTicks = 0
    private var deferrals = 0
    private const val MAX_DEFERRALS = 8

    /** 栅栏等待超时：GPU 还在处理上一批工作，这一帧不做自检、下一 tick 再来（不算失败）。 */
    private class SelfTestDeferred(message: String) : RuntimeException(message)

    @Volatile private var validated: Boolean = false
    @Volatile private var failed: Boolean = false

    fun isActive(): Boolean = D3D12Settings.rtBackend != RtBackend.AUTO.ordinal

    /** 逐帧派发用的计算管线（首次调用即编译）；0 = 编译失败，调用方必须跳过。 */
    internal fun dispatchPipeline(asPath: Boolean = false): Long =
        if (asPath) ensureAsPipeline() else ensurePipeline()

    fun isValidated(): Boolean = validated

    /**
     * 跑一次场景自检；已经跑过、光追没开、或上一次就失败过都直接返回。
     * 必须在没有命令列表打开的时候调用（客户端 tick 即可），内部会自带编码器并等待栅栏。
     * 见 docs/rt/README.md
     */
    /**
     * 把自检结论落成 `dxrmc/rt_selftest.json`：游戏内出问题只能靠日志，而日志会被别的行冲掉。
     * 文件只有一行，读它就知道是哪一档、过没过、卡在哪一步。
     */
    /** 每个场景一行结论；写进 rt_selftest.json，这样一次运行就能看到全部六个场景的结果。 */
    private val sceneNotes = ArrayList<String>()

    /** 报告是一行 JSON：任何注入的字符串带引号/换行都会把它写坏，读的人只会看到半行。 */
    private fun jsonSafe(text: String): String = text.replace("\\", "/").replace("\"", "'").replace("\n", " ")

    private fun noteScene(label: String, pass: Boolean, detail: String) {
        val safe = jsonSafe(detail)
        sceneNotes += "{\"scene\":\"$label\",\"pass\":$pass,\"detail\":\"$safe\"}"
    }

    private fun writeSelfTestReport(pass: Boolean, detail: String, stepOverride: String? = null) {
        // 报告里的 tier 是「实际跑的档」：配置写 dxr 但管线没建起来时会退到 accel / brute，
        // 按配置推断出来的名字会把这次运行说成它没走过的路。
        val tier = D3D12RtFrame.lastDispatchTier() ?: when {
            D3D12Settings.rtBackend == RtBackend.DXR.ordinal -> "dxr"
            D3D12Settings.rtAsTrace -> "accel"
            else -> "brute"
        }
        val safe = jsonSafe(detail)
        // 一次运行把该看的都写进来：六个场景逐项结论、几何契约、加速结构计数、SBT、逐帧档位。
        // 只看这一个文件就能判断这一轮到底哪一步不对，不必再进游戏第二次。
        val text = "{\"time\":${System.currentTimeMillis()},\"tier\":\"$tier\"," +
            "\"backend\":${D3D12Settings.rtBackend},\"asTrace\":${D3D12Settings.rtAsTrace}," +
            "\"pass\":$pass,\"step\":\"${stepOverride ?: step}\",\"detail\":\"$safe\"," +
            "\"scenes\":[${sceneNotes.joinToString(",")}]," +
            "\"contract\":\"${jsonSafe(accelReport(D3D12RtBlas.contractSummary(), D3D12RtBlas.selftestContractSummary()))}\"," +
            "\"blas\":\"${jsonSafe(accelReport(D3D12RtBlas.summary(), D3D12RtBlas.selftestBlasSummary()))}\"," +
            "\"sbt\":\"${jsonSafe(D3D12RtDxr.summary())}\"," +
            "\"frame\":\"${jsonSafe(D3D12RtFrame.lastDispatchSummary())}\"}\n"
        try {
            val dir = File(System.getProperty("user.dir"), RT_DIR)
            dir.mkdirs()
            val file = File(dir, SELF_TEST_FILE)
            // 本轮第一次写之前先把上一轮的结论挪走：否则新进程首帧那份 pending 会把
            // 上一轮的自检结论直接覆盖掉，下一次想对照「改完之后还过不过」就只剩一次运气。
            if (!preserved && file.exists()) {
                preserved = true
                file.copyTo(File(dir, SELF_TEST_PREV_FILE), overwrite = true)
            }
            file.writeText(text)
        } catch (e: IOException) {
            IllusionLogger.warnMessage("rt selftest: write failed (${e.javaClass.simpleName})")
        }
    }

    /**
     * 首帧派发成功后先落一份 `step = "pending"` 的报告：哪怕这一轮自检没跑完（崩了、关得快），
     * 文件里也有档位、几何契约、加速结构与帧派发的摘要，不用再进游戏第二次。
     */
    /**
     * 世界路径与自检实例是两份加速结构：世界还在加载时前者是默认值，而自检自己那份已经跑过了。
     * 只写「not checked」会让人以为这轮没查，写成两份各自的摘要才读得出实际发生了什么。
     */
    private fun accelReport(world: String, selftest: String): String =
        if (selftest == "none") "world $world" else "world $world; selftest $selftest"

    private var pendingReported = false

    private var disabledReported = false

    /** 上一轮的结论被挪到 `rt_selftest.prev.json` 后置位：一轮只挪一次，别把本轮自己的 pending 也挤掉。 */
    private var preserved = false

    /**
     * 光追整个没开时也留一行结论：否则进一次游戏什么都不留下，
     * 分不清「没开光追」和「开了但没跑起来」，等于白跑一趟。
     */
    fun reportDisabled() {
        if (disabledReported) return
        disabledReported = true
        writeSelfTestReport(
            false,
            "ray tracing is off (backend=${D3D12Settings.rtBackend}, mode=${D3D12Settings.rtMode})",
            "disabled",
        )
    }

    fun reportPending() {
        if (pendingReported) return
        pendingReported = true
        writeSelfTestReport(false, "first dispatch recorded, self-test pending", "pending")
    }

    fun validateOnce(device: D3D12Device?) {
        if (validated || failed || device == null || !isActive()) return
        if (++selfTestTicks < SELFTEST_DELAY_TICKS) return
        sceneNotes.clear()
        val ok = try {
            val gpu = ensurePipeline()
            // 两个场景都要跑：只跑第一个时，失败原因分不清是「没派发出去」还是「只有天空色对不上」。
            if (gpu == 0L) {
                step = "pipeline"
                false
            } else {
                // 开跑先留一行：没有这一行就分不清「自检没进来」和「进来但没打印结果」。
                IllusionLogger.infoMessage("rt self-test: start as=${D3D12Settings.rtAsTrace}")
                // 探针先跑：它把常量与三张输入缓冲的原值直接写回来，失败时立刻能分清是
                // 「常量没到」「SRV 表没绑对」还是「着色器逻辑本身错」。
                step = "probe"
                val probe = sceneGuard { validateProbe(device, gpu) }
                step = "empty"
                val empty = sceneGuard { validateScene(device, emptyScene(), gpu) }
                step = "triangle"
                val triangle = sceneGuard { validateScene(device, triangleScene(), gpu) }
                // 阴影场景只比 direct 与命中掩码：一次弹射在 16x16 地面上的掠射方向上会撞到
                // 「擦边命中/擦边错过」的浮点边界，两个实现都会各自翻转，那不是阴影语义的差异。
                step = "shadow"
                val shadow = sceneGuard { validateScene(device, shadowScene(), gpu, compareIndirect = false) }
                // 加速结构相关的两道只在需要它的档位上跑：MEGA 且 asTrace 关时不该建 AS。
                val accelOn = D3D12Settings.rtBackend == RtBackend.DXR.ordinal || D3D12Settings.rtAsTrace
                step = "as_triangle"
                val accel = !accelOn || sceneGuard { validateAsScene(device) }
                val dxrOn = D3D12Settings.rtBackend == RtBackend.DXR.ordinal
                step = "dxr"
                val dxr = !dxrOn ||
                    (sceneGuard { validateDxrScene(device, triangleScene(), compareIndirect = true) } &&
                        sceneGuard { validateDxrScene(device, shadowScene(), compareIndirect = false) })
                probe && empty && triangle && shadow && accel && dxr
            }
        } catch (t: SelfTestDeferred) {
            // 队列里还有没提交完的列表时等不到栅栏；这种情况会一直重复，所以要留一个「放弃了」的结论，
            // 否则 rt_selftest.json 永远不出现，看起来像自检根本没跑过。
            deferrals++
            if (deferrals < MAX_DEFERRALS) {
                IllusionLogger.warnMessage("rt trace: scene self-test deferred: ${t.message}")
                return
            }
            IllusionLogger.warnMessage("rt selftest: gave up after $deferrals deferrals (${t.message})")
            writeSelfTestReport(false, "deferred ${t.message}")
            failed = true
            return
        } catch (t: Throwable) {
            val where = t.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
            IllusionLogger.warnMessage("rt self-test: $step ${t.javaClass.simpleName} at $where")
            writeSelfTestReport(false, "${t.javaClass.simpleName} at $where")
            false
        }
        if (ok) {
            validated = true
            IllusionLogger.infoMessage(
                "rt trace: self-test passed (4 scenes, tolerance $TOLERANCE rel $RELATIVE_TOLERANCE)")
            writeSelfTestReport(true, "")
        } else {
            failed = true
            writeSelfTestReport(false, "see the log line above")
        }
    }

    /**
     * 释放追踪管线：光追关掉后调用一次，下次打开按当时的包重新编译。
     * 不释放的话换包只改了配置，已编译的管线还挂着旧包的源码。
     */
    fun releasePipeline() = synchronized(pipelineLock) {
        // compute 管线是原生 Dx12Object*，只能按资源释放；用 D3D12DestroyPipeline 会按 Dx12Pipeline 析构 → AV。
        for (handle in listOf(pipeline, asPipeline)) {
            if (handle != 0L) MCDR_ABI.ID3D12DestroyResource(handle)
        }
        pipeline = 0L
        asPipeline = 0L
        asPipelineFailed = false
        // 状态对象按包缓存，换包后必须重建。
        D3D12RtDxr.release()
    }

    /** 换包后必须重跑自检：上一个包通过的结论对新包的着色器无效。 */
    fun resetSelfTest() = synchronized(pipelineLock) {
        validated = false
        failed = false
        selfTestTicks = 0
    }

    private fun ensurePipeline(): Long = synchronized(pipelineLock) {
        val cached = pipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtTraceShader.build(), D3D12RtTraceShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            IllusionLogger.warnMessage("rt trace: DXC compile failed: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtTraceShader.SRV_COUNT, D3D12RtTraceShader.UAV_COUNT)
        if (handle == 0L) {
            IllusionLogger.warnMessage("rt trace: compute pipeline creation failed: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        pipeline = handle
        return handle
    }

    /**
     * 加速结构变体：同一份源码前加 `#define RT_AS 1`，SRV 多一个 `t5`（TLAS）。
     * 编译或建管线失败只记一次，之后直接返回 0 让调用方退回暴力路径。
     */
    private fun ensureAsPipeline(): Long = synchronized(pipelineLock) {
        val cached = asPipeline
        if (cached != 0L) return cached
        if (asPipelineFailed) return 0L
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtTraceShader.build(asPath = true), D3D12RtTraceShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            asPipelineFailed = true
            IllusionLogger.warnMessage("rt trace: DXC compile failed for the accel variant: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtTraceShader.SRV_COUNT_AS, D3D12RtTraceShader.UAV_COUNT)
        if (handle == 0L) {
            asPipelineFailed = true
            IllusionLogger.warnMessage("rt trace: compute pipeline creation failed for the accel variant: ${MCDR_ABI.D3D12LastError()}")
            return 0L
        }
        asPipeline = handle
        return handle
    }

    // ---- 场景 ----

    private class Scene(
        val label: String,
        val positions: FloatArray,
        val indices: IntArray,
        val origin: RtVec3,
        val camera: RtVec3,
        val right: RtVec3,
        val up: RtVec3,
        val forward: RtVec3,
        val tanHalfFovY: Float,
        val aspect: Float,
        val sunDir: RtVec3,
        val sunColor: RtVec3,
        val skyColor: RtVec3,
    ) {
        val triangleCount: Int get() = indices.size / 3
    }

    private fun emptyScene(): Scene = Scene(
        label = "empty",
        positions = FloatArray(0),
        indices = IntArray(0),
        origin = RtVec3(0f, 0f, 0f),
        camera = RtVec3(0.25f, 0.25f, 0f),
        right = RtVec3(1f, 0f, 0f),
        up = RtVec3(0f, 1f, 0f),
        forward = RtVec3(0f, 0f, 1f),
        tanHalfFovY = 0.5f,
        aspect = 1f,
        sunDir = RtVec3(0f, 0f, 1f),
        sunColor = RtVec3(1f, 0.95f, 0.9f),
        skyColor = RtVec3(0.1f, 0.2f, 0.3f),
    )

    /**
     * 一块几乎铺满视野的三角形（平面 `z = 2`），顶点落在记录的 16³ 段盒内。
     *
     * 见 docs/rt/README.md 9.7 / 16.6 节。
     */
    private fun triangleScene(): Scene = Scene(
        label = "triangle",
        // 顶点必须落在记录那段 16x16x16 的盒子里：暴力档先按段盒剪枝，顶点伸出盒外时整段会被裁掉，
        // GPU 与 CPU 参考就对不上（见 docs/rt/README.md 16.6 节）。
        positions = floatArrayOf(
            1f, 1f, 2f,
            9f, 1f, 2f,
            5f, 9f, 2f,
        ),
        indices = intArrayOf(0, 1, 2),
        origin = RtVec3(0f, 0f, 0f),
        camera = RtVec3(5f, 5f, -6f),
        right = RtVec3(1f, 0f, 0f),
        up = RtVec3(0f, 1f, 0f),
        forward = RtVec3(0f, 0f, 1f),
        tanHalfFovY = 0.6f,
        aspect = 1f,
        sunDir = RtVec3(0.15f, 0.25f, -1f),
        sunColor = RtVec3(1f, 0.95f, 0.9f),
        skyColor = RtVec3(0.1f, 0.2f, 0.3f),
    )

    /**
     * 地面 + 一块悬空挡板：挡板把一部分地面的太阳直射光挡住，那里的 `direct` 必须是 0。
     * 见 docs/rt/README.md
     */
    private fun shadowScene(): Scene = Scene(
        label = "shadow",
        // 几何必须落在记录那段 16x16x16 的盒子里（追踪按段盒剪枝），所以地面铺满 [0,16] 而不是更大。
        positions = floatArrayOf(
            // 地面（两块三角形，y = 0）
            0f, 0f, 0f, 16f, 0f, 0f, 16f, 0f, 16f, 0f, 0f, 16f,
            // 悬空挡板（y = 4，沿 -sunDir 在地面上投影出阴影）
            4f, 4f, 6f, 10f, 4f, 6f, 10f, 4f, 11f, 4f, 4f, 11f,
        ),
        indices = intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7),
        origin = RtVec3(0f, 0f, 0f),
        camera = RtVec3(8f, 6f, -1f),
        right = RtVec3(1f, 0f, 0f),
        up = RtVec3(0f, 0.9578f, 0.2873f),
        forward = RtVec3(0f, -0.2873f, 0.9578f),
        tanHalfFovY = 0.7f,
        aspect = 1f,
        sunDir = RtVec3(0.2095f, 0.9428f, 0.3143f),
        sunColor = RtVec3(1f, 0.95f, 0.9f),
        skyColor = RtVec3(0.1f, 0.2f, 0.3f),
    )

    // ---- GPU 侧 ----

    private class GpuResult(val direct: FloatArray, val indirect: FloatArray, val aux: FloatArray)

    /**
     * 探针：一次派发把常量与三张输入缓冲的原值写进输出，与期望值逐项比对。
     * 见 docs/rt/README.md
     */
    private fun validateProbe(device: D3D12Device, gpuPipeline: Long): Boolean {
        val scene = triangleScene()
        val result = runGpu(
            device, scene, gpuPipeline, recordBytes(scene), vertexBytes(scene), indexBytes(scene),
            materialBytes(scene), quadMaterialBytes(scene),
            // debugMode = 8 是探针；recordCount 给非零假值。
            sceneBytes(scene, debugMode = 8, recordCount = 3), probe = true,
        ) ?: return false
        val width = result.direct[0]
        val height = result.direct[1]
        val records = result.direct[2]
        // 探针在 ir0 里回读的是 g_sunRadiance.rgb：天空在常量里已经没有独立的槽
        // （自检环境的常量天空只给 debug mode 9 的未命中像素用），别再按天空比。
        val sun = result.indirect
        val vertexZ = result.aux[1]
        val material = result.aux[2]
        val ok = width == WIDTH.toFloat() && height == HEIGHT.toFloat() && records == 3f &&
            abs(sun[0] - scene.sunColor.x) < 1e-3f && abs(sun[1] - scene.sunColor.y) < 1e-3f &&
            abs(sun[2] - scene.sunColor.z) < 1e-3f &&
            abs(vertexZ - 2f) < 1e-3f && abs(material - 0.7f) < 1e-3f
        val line = "rt trace: probe width=$width height=$height records=$records " +
            "sun=(${sun[0]}, ${sun[1]}, ${sun[2]}) vertexZ=$vertexZ material=$material"
        if (ok) {
            IllusionLogger.infoMessage("$line -> pass")
        } else {
            IllusionLogger.warnMessage("$line -> fail")
            if (width == 0f && height == 0f && records == 0f && vertexZ == 0f) {
                IllusionLogger.warnMessage(
                    "rt trace: no constant reached the shader, rebuild native (MSBuild MCDR + copyNative) " +
                        "if run/dxrmc/MCDR.dll is older than the sources",
                )
            }
        }
        return ok
    }

    /**
     * 单个场景抛异常不该中断整轮自检：记下当前步骤与帧链，继续跑后面的场景。
     * `SelfTestDeferred` 表示队列繁忙，仍然整体推迟。
     */
    private inline fun sceneGuard(body: () -> Boolean): Boolean = try {
        body()
    } catch (d: SelfTestDeferred) {
        throw d
    } catch (t: Throwable) {
        val where = t.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
        IllusionLogger.warnMessage("rt self-test: $step ${t.javaClass.simpleName} at $where")
        false
    }

    private fun validateScene(
        device: D3D12Device,
        scene: Scene,
        gpuPipeline: Long,
        compareIndirect: Boolean = true,
    ): Boolean {
        val records = recordBytes(scene)
        val vertices = vertexBytes(scene)
        val indices = indexBytes(scene)
        val materials = materialBytes(scene)
        val quads = quadMaterialBytes(scene)
        val camera = sceneBytes(scene)

        val result = runGpu(device, scene, gpuPipeline, records, vertices, indices, materials, quads, camera)
            ?: return false
        val cpu = _reference(scene, records, vertices, indices, materials, quads, camera)

        var worst = 0f
        var over = 0
        var maskMismatch = 0
        var gpuHits = 0
        var cpuHits = 0
        var gpuShadowed = 0
        for (i in 0 until WIDTH * HEIGHT) {
            val cpuHit = cpu.aux[i * 4] > 0f
            val gpuHit = result.aux[i * 4] > 0f
            if (gpuHit) gpuHits++
            if (cpuHit) cpuHits++
            // 命中但一点太阳直射都拿不到 = 落在阴影里（这是阴影射线真的求过交的证据）。
            if (gpuHit && result.direct[i * 4] <= 0f && result.direct[i * 4 + 1] <= 0f &&
                result.direct[i * 4 + 2] <= 0f
            ) {
                gpuShadowed++
            }
            if (cpuHit != gpuHit) maskMismatch++
            for (c in 0 until 4) {
                val at = i * 4 + c
                val d = abs(result.direct[at] - cpu.direct[at])
                if (d > worst) worst = d
                if (exceeds(d, cpu.direct[at])) over++
                if (compareIndirect) {
                    val e = abs(result.indirect[at] - cpu.indirect[at])
                    if (e > worst) worst = e
                    if (exceeds(e, cpu.indirect[at])) over++
                }
            }
        }
        // 两侧都没命中时逐像素比较恒等：没有覆盖的场景证明不了任何事，必须要求真的有命中。
        val covered = scene.triangleCount == 0 || (gpuHits > 0 && cpuHits > 0)
        val ok = over == 0 && maskMismatch == 0 && covered
        noteScene(
            scene.label, ok,
            "maxDiff=$worst over=$over mask=$maskMismatch hits=$gpuHits/$cpuHits shadowed=$gpuShadowed " +
                "covered=$covered indirect=$compareIndirect")
        // 一行一个事实：三行分别是对拍误差、命中统计、判定依据（日志规范：正文 ≤60、硬上限 90）。
        val head = "rt trace: scene=${scene.label}"
        logLine("$head maxDiff=$worst over=$over mask=$maskMismatch triangles=${scene.triangleCount}")
        logLine("$head hits gpu=$gpuHits cpu=$cpuHits shadowed=$gpuShadowed covered=$covered")
        logLine(
            "$head indirect=${if (compareIndirect) "on" else "off"} " +
                "tol=$TOLERANCE+${RELATIVE_TOLERANCE}rel -> ${if (ok) "pass" else "fail"}",
            warn = !ok,
        )
        if (!ok) {
            IllusionLogger.warnMessage("rt trace: detail ${describeMismatch(result, cpu)}")
        }
        return ok
    }

    /** 自检的 INFO 与 WARN 走同一个出口，判定行在失败时要落到 WARN 通道。 */
    private fun logLine(text: String, warn: Boolean = false) {
        if (warn) IllusionLogger.warnMessage(text) else IllusionLogger.infoMessage(text)
    }

    /** 自检失败时的取证：哪一侧整块是 0、前几个超差采样各是多少。 */
    private fun describeMismatch(gpu: GpuResult, cpu: CpuResult): String = buildString {
        append("gpuZero[d=${allZero(gpu.direct)} i=${allZero(gpu.indirect)}")
        append(" auxMask=0] cpuZero[d=${allZero(cpu.direct)} i=${allZero(cpu.indirect)}]")
        var shown = 0
        var pixel = 0
        while (pixel < WIDTH * HEIGHT && shown < 3) {
            for (c in 0 until 4) {
                val at = pixel * 4 + c
                val direct = abs(gpu.direct[at] - cpu.direct[at])
                if (exceeds(direct, cpu.direct[at])) {
                    append(" | px=$pixel direct[$c] gpu=${gpu.direct[at]} cpu=${cpu.direct[at]}")
                    shown++
                }
                if (shown >= 3) break
                val indirect = abs(gpu.indirect[at] - cpu.indirect[at])
                if (exceeds(indirect, cpu.indirect[at])) {
                    append(" | px=$pixel indirect[$c] gpu=${gpu.indirect[at]} cpu=${cpu.indirect[at]}")
                    shown++
                }
                if (shown >= 3) break
            }
            pixel++
        }
    }

    private fun allZero(values: FloatArray): Boolean = values.all { it == 0f }

    /** 按场景建齐 CPU 侧缓冲并跑一趟 GPU，供各档对拍复用。 */
    private fun runScene(
        device: D3D12Device, scene: Scene, pipeline: Long,
        accel: D3D12RtAccel? = null, raygen: Boolean = false,
    ): GpuResult? = runGpu(
        device, scene, pipeline,
        recordBytes(scene), vertexBytes(scene), indexBytes(scene),
        materialBytes(scene), quadMaterialBytes(scene), sceneBytes(scene),
        accel = accel, raygen = raygen,
    )

    /**
     * 同一颗三角形走两条遍历：内联 `RayQuery` 版必须与暴力档逐像素一致。
     * 见 docs/rt/README.md 14.5 节。
     */
    private fun validateAsScene(device: D3D12Device): Boolean {
        val pipeline = ensureAsPipeline()
        if (pipeline == 0L) return false
        val scene = triangleScene()
        val accel = D3D12RtBlas.newAccel()
        try {
            step = "as_triangle:brute"
            val brute = runScene(device, scene, ensurePipeline()) ?: return false
            step = "as_triangle:accel"
            val viaAccel = runScene(device, scene, pipeline, accel) ?: return false
            D3D12RtBlas.noteSelftest(accel)
            return comparePaths(brute, viaAccel, "as_triangle paths")
        } finally {
            accel.release()
        }
    }

    /**
     * `DispatchRays` 版必须与暴力档逐像素一致；着色器在 `mega_dxr_lib.hlsl` 里共用同一份命中解码。
     * 见 docs/rt/README.md 16.6 节。
     */
    private fun validateDxrScene(device: D3D12Device, scene: Scene, compareIndirect: Boolean): Boolean {
        val state = D3D12RtDxr.pipeline(device)
        if (state == 0L) return false
        val accel = D3D12RtBlas.newAccel()
        try {
            step = "dxr ${scene.label}:brute"
            val brute = runScene(device, scene, ensurePipeline()) ?: return false
            step = "dxr ${scene.label}:dispatch"
            val viaDxr = runScene(device, scene, state, accel, raygen = true) ?: return false
            D3D12RtBlas.noteSelftest(accel)
            return comparePaths(brute, viaDxr, "dxr ${scene.label} paths", compareIndirect)
        } finally {
            accel.release()
        }
    }

    /** 两条 GPU 路径的逐像素对拍：命中掩码、命中距离、直接光、弹射三项都要在容差内。 */
    private fun comparePaths(
        brute: GpuResult,
        accel: GpuResult,
        label: String,
        compareIndirect: Boolean = true,
    ): Boolean {
        var worst = 0f
        var over = 0
        var maskMismatch = 0
        val planes = buildList {
            add(brute.aux to accel.aux)
            add(brute.direct to accel.direct)
            if (compareIndirect) add(brute.indirect to accel.indirect)
        }
        for (i in 0 until WIDTH * HEIGHT) {
            if ((brute.aux[i * 4] > 0f) != (accel.aux[i * 4] > 0f)) maskMismatch++
            for ((left, right) in planes) {
                for (c in 0 until 4) {
                    val d = abs(left[i * 4 + c] - right[i * 4 + c])
                    if (d > worst) worst = d
                    if (exceeds(d, right[i * 4 + c])) over++
                }
            }
        }
        val ok = over == 0 && maskMismatch == 0
        val line = "rt trace: $label maxDiff=$worst over=$over mask=$maskMismatch " +
            "tol=$TOLERANCE+${RELATIVE_TOLERANCE}rel"
        if (ok) {
            IllusionLogger.infoMessage("$line -> pass")
        } else {
            IllusionLogger.warnMessage("$line -> fail")
        }
        return ok
    }

    private fun runGpu(
        device: D3D12Device, scene: Scene, gpuPipeline: Long,
        records: ByteBuffer, vertices: ByteBuffer, indices: ByteBuffer,
        materials: ByteBuffer, quads: ByteBuffer, camera: ByteBuffer,
        probe: Boolean = false,
        accel: D3D12RtAccel? = null,
        raygen: Boolean = false,
    ): GpuResult? {
        // 暴力 5 个 SRV，加速结构 / DXR 变体多一个 t5。
        val sources = ArrayList<D3D12GpuBuffer>(6)
        val outputs = ArrayList<D3D12GpuBuffer>(3)
        val readbacks = ArrayList<D3D12GpuBuffer>(3)
        val constantHandles = ArrayList<D3D12GpuBuffer>(1)
        var ctx = 0L
        try {
            step = "${scene.label}:upload"
            for ((name, data) in listOf(
                "records" to records, "vertices" to vertices, "indices" to indices,
                // 顺序必须与着色器绑定一致：t4 = gQuadMaterials。
                "materials" to materials, "quads" to quads,
            )) {
                sources.add(upload(device, "rt-test-$name", data) ?: return null)
            }
            step = "${scene.label}:alloc"
            val bytes = (WIDTH * HEIGHT * 16).toLong()
            repeat(3) { i ->
                outputs.add(newBuffer(device, "rt-test-out-$i", UAV_BUFFER_USAGE, bytes) ?: return null)
            }
            repeat(3) { i ->
                readbacks.add(newBuffer(device, "rt-test-read-$i", READ_USAGE, bytes) ?: return null)
            }

            // 常量缓冲必须是上传堆 + 256 对齐的 CBV 偏移（compute 根签名 [2] 是 b0 的 CBV）。
            val constantBuffer = newBuffer(
                device, "rt-test-constants", GpuBuffer.USAGE_MAP_WRITE, 512L,
            ) ?: return null
            constantHandles.add(constantBuffer)
            constantBuffer.map(0L, camera.remaining().toLong(), false, true).use { view ->
                view.data().put(camera.duplicate())
            }

            step = "${scene.label}:encoder"
            ctx = MCDR_ABI.D3D12CreateCommandEncoder()
            if (ctx == 0L) {
                IllusionLogger.warnMessage("rt trace: command encoder creation failed: ${MCDR_ABI.D3D12LastError()}")
                return null
            }
            MCDR_ABI.D3D12BeginCommandList(ctx)
            var srvs = LongArray(sources.size) { sources[it].handle() }
            if (accel != null) {
                // 加速结构要的是显存缓冲：上传顺序是 records / vertices / indices / materials / quads。
                if (!accel.prepare(device, ctx, records, 1, sources[1].handle(), sources[2].handle())) {
                    IllusionLogger.warnMessage("rt trace: accel structure build failed")
                    return null
                }
                val view = accel.view()
                if (view == 0L) {
                    IllusionLogger.warnMessage("rt trace: accel structure view missing")
                    return null
                }
                srvs += view                                        // t5 = gTlas
            }
            val uavs = LongArray(outputs.size) { outputs[it].handle() }
            // 常量全部在 WgaFrame 里（416 B 缓冲），这里不再传 root constants。
            val constants = intArrayOf(0)
            step = "${scene.label}:dispatch"
            val dispatched = if (raygen) {
                D3D12RtDxr.dispatch(device, ctx, srvs, uavs, constantBuffer.handle(), 0L, WIDTH, HEIGHT)
            } else {
                MCDR_ABI.D3D12DispatchComputeEx(
                    ctx, gpuPipeline, srvs, uavs, constants, constantBuffer.handle(), 0L,
                    (WIDTH + 7) / 8, (HEIGHT + 7) / 8, 1,
                )
            }
            if (!dispatched) {
                IllusionLogger.warnMessage("rt trace: dispatch failed: ${MCDR_ABI.D3D12LastError()}")
                return null
            }
            step = "${scene.label}:readback"
            for (i in 0 until 3) {
                MCDR_ABI.D3D12CopyBuffer(ctx, outputs[i].handle(), 0L, readbacks[i].handle(), 0L, bytes)
            }
            val fence = MCDR_ABI.D3D12Submit(ctx)
            if (!MCDR_ABI.D3D12WaitForFence(ctx, fence, 3_000_000_000L)) {
                // 队列里还压着世界加载的工作，这次不做自检；不是结论性失败，下次 tick 重来。
                throw SelfTestDeferred("fence wait timed out")
            }

            val planes = Array(3) { readFloats(readbacks[it], bytes.toInt()) ?: return null }
            return GpuResult(planes[0], planes[1], planes[2])
        } finally {
            if (ctx != 0L) MCDR_ABI.D3D12DestroyCommandEncoder(ctx)
            for (buffer in sources + outputs + readbacks + constantHandles) buffer.close()
        }
    }

    private fun newBuffer(
        device: D3D12Device, label: String, usage: Int, bytes: Long,
    ): D3D12GpuBuffer? {
        val created = device.createBuffer(Supplier { label }, usage, bytes) as? D3D12GpuBuffer
        if (created == null) {
            IllusionLogger.warnMessage("rt buffer: $label failed (${MCDR_ABI.D3D12LastError()})")
        }
        return created
    }

    private fun upload(device: D3D12Device, label: String, data: ByteBuffer): D3D12GpuBuffer? {
        // raw SRV 的元素粒度是 4 字节（NumElements = size/4）：6 字节的索引缓冲只有 4 字节可见，
        // 第三条边会读成 0 变成退化三角形。这里补齐到 4 字节整数倍（且不小于 16 字节）。
        // 必须走收 ByteBuffer 的重载：另一个重载只给大小，内容一个字节都不会上传，
        // 着色器读到的是没初始化过的显存（自检于是拿着垃圾数据比对）。
        val padded = if (data.remaining() % 4 == 0 && data.remaining() >= MIN_BUFFER_BYTES) {
            data
        } else {
            ByteBuffer.allocate(maxOf(MIN_BUFFER_BYTES, (data.remaining() + 3) and -4))
                .order(ByteOrder.LITTLE_ENDIAN).also {
                    it.put(data.duplicate())
                    it.flip()
                }
        }
        val created = device.createBuffer(Supplier { label }, 0, padded) as? D3D12GpuBuffer
        if (created == null) {
            IllusionLogger.warnMessage("rt buffer: $label failed (${MCDR_ABI.D3D12LastError()})")
        }
        return created
    }

    private fun readFloats(buffer: D3D12GpuBuffer, bytes: Int): FloatArray? {
        val mapped = MCDR_ABI.D3D12MapBuffer(buffer.handle(), 0L, bytes.toLong(), true, false)
        if (mapped == null) {
            IllusionLogger.warnMessage("rt trace: read-back map failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        try {
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes / 4)
            for (i in out.indices) out[i] = mapped.getFloat(i * 4)
            return out
        } finally {
            MCDR_ABI.D3D12UnmapBuffer(buffer.handle())
        }
    }

    // ---- 场景缓冲（与 GPU 记录布局逐字节对齐，CPU 参考读同一份字节） ----

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size.coerceAtLeast(MIN_BUFFER_BYTES)).order(ByteOrder.LITTLE_ENDIAN)

    private fun recordBytes(scene: Scene): ByteBuffer {
        val out = buffer(RECORD_BYTES * 1)
        if (scene.triangleCount == 0) return out
        // 暴力档先按段盒剪枝、加速结构不剪：顶点整颗落在 [origin, origin + 16] 是两档一致的前提。
        // 场景写歪时在这里指名顶点，别等到逐像素对不上再回头找。
        for (i in 0 until scene.positions.size / 3) {
            val inside = scene.positions[i * 3] in scene.origin.x..(scene.origin.x + SECTION_SIZE) &&
                scene.positions[i * 3 + 1] in scene.origin.y..(scene.origin.y + SECTION_SIZE) &&
                scene.positions[i * 3 + 2] in scene.origin.z..(scene.origin.z + SECTION_SIZE)
            if (!inside) {
                error("scene vertex $i outside the section box")
            }
        }
        out.putInt(0, scene.origin.x.toInt())
        out.putInt(4, scene.origin.y.toInt())
        out.putInt(8, scene.origin.z.toInt())
        out.putInt(12, 0)                                   // layer
        out.putLong(16, 0L)                                 // vertexOffset
        out.putLong(24, 0L)                                 // indexOffset
        out.putInt(32, scene.positions.size / 3)            // vertexCount
        out.putInt(36, scene.indices.size)                  // indexCount
        out.putInt(40, 2 or (1 shl 8))                       // indexStride = u16，flags = SOLID
        out.putInt(44, 1)                                   // geometryVersion
        out.putInt(48, 0)                                   // materialOffset（以 quad 计）
        out.putInt(52, scene.triangleCount / 2 + 1)         // quadCount
        out.putLong(56, 0L)                                 // sectionNode
        return out
    }

    private fun vertexBytes(scene: Scene): ByteBuffer {
        val count = scene.positions.size / 3
        val out = buffer(VERTEX_BYTES * count.coerceAtLeast(1))
        // 与 DefaultVertexFormat.BLOCK 对齐：Position(12) + Color(4) + UV0(8) + UV2(4)。
        for (i in 0 until count) {
            val at = i * VERTEX_BYTES
            out.putFloat(at, scene.positions[i * 3])
            out.putFloat(at + 4, scene.positions[i * 3 + 1])
            out.putFloat(at + 8, scene.positions[i * 3 + 2])
            out.putInt(at + 12, -1)                          // 顶点色 = 白
        }
        return out
    }

    private fun indexBytes(scene: Scene): ByteBuffer {
        val out = buffer(scene.indices.size * 2)
        for (i in scene.indices.indices) out.putShort(i * 2, scene.indices[i].toShort())
        return out
    }

    private fun materialBytes(scene: Scene): ByteBuffer {
        val out = buffer(MATERIAL_BYTES)
        out.putFloat(0, 0.7f)
        out.putFloat(4, 0.7f)
        out.putFloat(8, 0.7f)
        out.putFloat(12, 0f)
        out.putFloat(16, 0f)
        out.putFloat(20, 0f)
        out.putFloat(24, 0.8f)
        out.putInt(28, 0)
        return out
    }

    private fun quadMaterialBytes(scene: Scene): ByteBuffer {
        val quads = (scene.triangleCount / 2 + 1).coerceAtLeast(1)
        return buffer(quads * 2)
    }

    /**
     * 自检场景的 `WgaFrame`。
     *
     * `debugMode = 9` 是 B-Mega 的自检环境：着色器把天空换成常量 `g_moonRadiance.rgb`
     * （场景的 `skyColor`），CPU 参考才对得上；正常出图用 `0`，天空走光影包的解析大气。
     */
    private fun sceneBytes(scene: Scene, debugMode: Int = 9, recordCount: Int = -1): ByteBuffer {
        val out = buffer(SCENE_BYTES)
        D3D12RtFrameConstants.write(
            target = out,
            position = scene.camera,
            forward = scene.forward,
            up = scene.up,
            tanHalfFovY = scene.tanHalfFovY,
            aspect = scene.aspect,
            width = WIDTH,
            height = HEIGHT,
            sunDir = scene.sunDir,
            sunRadiance = scene.sunColor,
            sunStrength = 1f,
            frameIndex = 0,
            radius = 0f,
            recordCount = if (recordCount >= 0) recordCount else if (scene.triangleCount == 0) 0 else 1,
            debugMode = debugMode,
        )
        // 自检环境的常量天空占 g_moonRadiance.rgb（正常出图这一行不用）。
        putVec(out, 256, scene.skyColor)
        return out
    }

    /** 列主序 `invViewProj` × 裁剪空间 (ndc, 1, 1)，再减去相机位置归一化——与 HLSL 的 RtRayDirection 等价。 */
    private fun rayDirection(inverse: FloatArray, ndcx: Float, ndcy: Float, camPos: RtVec3): RtVec3 {
        val x = inverse[0] * ndcx + inverse[4] * ndcy + inverse[8] + inverse[12]
        val y = inverse[1] * ndcx + inverse[5] * ndcy + inverse[9] + inverse[13]
        val z = inverse[2] * ndcx + inverse[6] * ndcy + inverse[10] + inverse[14]
        val w = inverse[3] * ndcx + inverse[7] * ndcy + inverse[11] + inverse[15]
        val scale = 1f / w
        return D3D12RtLightContract.normalize(RtVec3(x * scale, y * scale, z * scale) - camPos)
    }

    private fun putVec(buffer: ByteBuffer, offset: Int, value: RtVec3) {
        buffer.putFloat(offset, value.x)
        buffer.putFloat(offset + 4, value.y)
        buffer.putFloat(offset + 8, value.z)
    }

    // ---- CPU 参考：读同一份字节、走同一条光线语义 ----

    private class RefHit {
        var hit: Boolean = false
        var t: Float = 0f
        var normal: RtVec3 = RtVec3(0f, 0f, 1f)
        var material: Int = 0
    }

    private class CpuResult(val direct: FloatArray, val indirect: FloatArray, val aux: FloatArray)

    private fun _reference(
        scene: Scene, records: ByteBuffer, vertices: ByteBuffer, indices: ByteBuffer,
        materials: ByteBuffer, quads: ByteBuffer, camera: ByteBuffer,
    ): CpuResult {
        val direct = FloatArray(WIDTH * HEIGHT * 4)
        val indirect = FloatArray(WIDTH * HEIGHT * 4)
        val aux = FloatArray(WIDTH * HEIGHT * 4)

        val camPos = readVec(camera, 192)
        val sunDir = D3D12RtLightContract.normalize(readVec(camera, 224))
        val sunColor = readVec(camera, 240)
        val skyColor = readVec(camera, 256)
        val inverse = FloatArray(16)
        for (i in 0 until 16) inverse[i] = camera.getFloat(128 + i * 4)
        val recordCount = if (scene.triangleCount == 0) 0 else 1

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val pixel = y * WIDTH + x
                // direct 的 alpha 是命中标记（着色器写 `hitFlag`），indirect 的 alpha 恒为 1。
                direct[pixel * 4 + 3] = 0f
                indirect[pixel * 4 + 3] = 1f
                val uvx = (x + 0.5f) / WIDTH
                val uvy = (y + 0.5f) / HEIGHT
                val ndcx = uvx * 2f - 1f
                val ndcy = 1f - uvy * 2f
                // 必须和着色器同一条路径：逆视图投影 × 裁剪空间向量（HLSL 也是 mul(M, clip)）。
                val dir = rayDirection(inverse, ndcx, ndcy, camPos)

                val hit = trace(recordCount, records, vertices, indices, quads, camPos, dir, 1.0e9f)
                if (hit.hit) {
                    val normal = faceNormal(hit.normal, dir)
                    val position = camPos + dir * hit.t
                    val origin = position + normal * D3D12RtLightContract.rayBias(hit.t)
                    aux[pixel * 4] = hit.t
                    aux[pixel * 4 + 1] = normal.x
                    aux[pixel * 4 + 2] = normal.y
                    aux[pixel * 4 + 3] = normal.z

                    val shadow = trace(recordCount, records, vertices, indices, quads, origin, sunDir, 1.0e9f)
                    val visible = if (shadow.hit) 0f else 1f
                    val sun = sunColor * (maxOf(normal.dot(sunDir), 0f) * visible)
                    direct[pixel * 4] = sun.x
                    direct[pixel * 4 + 1] = sun.y
                    direct[pixel * 4 + 2] = sun.z
                    direct[pixel * 4 + 3] = 1f

                    val rng = D3D12RtLightContract.Rng(D3D12RtLightContract.seed(x, y, 0, 1, 0, 0))
                    val bounceDir = D3D12RtLightContract.cosineHemisphere(rng.next2(), normal)
                    if (normal.dot(bounceDir) > 0f) {
                        val second = trace(recordCount, records, vertices, indices, quads, origin, bounceDir, 1.0e9f)
                        if (second.hit) {
                            val normal2 = faceNormal(second.normal, bounceDir)
                            val origin2 = origin + bounceDir * second.t +
                                normal2 * D3D12RtLightContract.rayBias(second.t)
                            val shadow2 = trace(recordCount, records, vertices, indices, quads, origin2, sunDir, 1.0e9f)
                            val visible2 = if (shadow2.hit) 0f else 1f
                            val materialAt = second.material * MATERIAL_BYTES
                            val albedo2 = readVec(materials, materialAt)
                            val emissive = readVec(materials, materialAt + 12)
                            val indirect2 = shadeBounce(albedo2, emissive,
                                materials.getFloat(materialAt + 24), materials.getFloat(materialAt + 32),
                                normal2, sunDir, sunColor, skyColor, visible2, bounceDir)
                            indirect[pixel * 4] = indirect2.x
                            indirect[pixel * 4 + 1] = indirect2.y
                            indirect[pixel * 4 + 2] = indirect2.z
                        } else {
                            indirect[pixel * 4] = skyColor.x
                            indirect[pixel * 4 + 1] = skyColor.y
                            indirect[pixel * 4 + 2] = skyColor.z
                        }
                    }
                } else {
                    // 相机光线打空：与 rt_main 的 else 分支逐行对齐（天空即环境入射）。
                    indirect[pixel * 4] = skyColor.x
                    indirect[pixel * 4 + 1] = skyColor.y
                    indirect[pixel * 4 + 2] = skyColor.z
                }
            }
        }
        return CpuResult(direct, indirect, aux)
    }

    private fun trace(
        recordCount: Int, records: ByteBuffer, vertices: ByteBuffer, indices: ByteBuffer,
        quads: ByteBuffer, origin: RtVec3, dir: RtVec3, maxT: Float,
    ): RefHit {
        val hit = RefHit()
        hit.t = maxT
        for (r in 0 until recordCount) {
            val base = r * RECORD_BYTES
            val recordOrigin = RtVec3(records.getInt(base).toFloat(), records.getInt(base + 4).toFloat(),
                records.getInt(base + 8).toFloat())
            val vertexOffset = records.getLong(base + 16).toInt()
            val indexOffset = records.getLong(base + 24).toInt()
            val indexCount = records.getInt(base + 36)
            val stride = records.getInt(base + 40) and 0xFF
            val materialOffset = records.getInt(base + 48)
            var k = 0
            while (k + 2 < indexCount) {
                val ia = indexAt(indices, indexOffset + k * stride, stride)
                val ib = indexAt(indices, indexOffset + (k + 1) * stride, stride)
                val ic = indexAt(indices, indexOffset + (k + 2) * stride, stride)
                // 记录里的 vertexOffset 是字节偏移；顶点之间隔 VERTEX_BYTES 字节。
                val a = recordOrigin + vertexAt(vertices, vertexOffset + ia * VERTEX_BYTES)
                val b = recordOrigin + vertexAt(vertices, vertexOffset + ib * VERTEX_BYTES)
                val c = recordOrigin + vertexAt(vertices, vertexOffset + ic * VERTEX_BYTES)
                val normal = intersectTriangle(origin, dir, a, b, c, hit)
                if (normal != null) {
                    hit.hit = true
                    hit.normal = normal
                    hit.material = quadMaterial(quads, materialOffset + k / 6)
                }
                k += 3
            }
        }
        return hit
    }

    /** 按**字节**偏移取顶点：与着色器的 `RtVertexAt(vertexOffset + 索引 * 28)` 逐字节对齐。 */
    private fun vertexAt(vertices: ByteBuffer, byteOffset: Int): RtVec3 =
        readVec(vertices, byteOffset)

    private fun indexAt(indices: ByteBuffer, byteOffset: Int, stride: Int): Int =
        if (stride == 4) indices.getInt(byteOffset) else indices.getShort(byteOffset).toInt() and 0xFFFF

    private fun quadMaterial(quads: ByteBuffer, quadIndex: Int): Int {
        val byteOffset = quadIndex * 2
        val word = quads.getInt(byteOffset and 3.inv())
        return if (byteOffset and 2 == 0) word and 0xFFFF else word ushr 16
    }

    /** 返回面法线（命中且更新了 [hit] 的 t），未命中返回 null。 */
    private fun intersectTriangle(
        org: RtVec3, dir: RtVec3, v0: RtVec3, v1: RtVec3, v2: RtVec3, hit: RefHit,
    ): RtVec3? {
        val e1 = v1 - v0
        val e2 = v2 - v0
        val p = dir.cross(e2)
        val det = e1.dot(p)
        if (abs(det) < 1e-9f) return null
        val invDet = 1f / det
        val tvec = org - v0
        val u = tvec.dot(p) * invDet
        if (u < 0f || u > 1f) return null
        val q = tvec.cross(e1)
        val v = dir.dot(q) * invDet
        if (v < 0f || u + v > 1f) return null
        val t = e2.dot(q) * invDet
        if (t <= 1e-5f || t >= hit.t) return null
        hit.t = t
        return D3D12RtLightContract.normalize(e1.cross(e2))
    }

    private fun faceNormal(normal: RtVec3, dir: RtVec3): RtVec3 =
        if (normal.dot(dir) > 0f) normal * -1f else normal

    private fun readVec(buffer: ByteBuffer, offset: Int): RtVec3 =
        RtVec3(buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8))

    /**
     * 弹射面的出射辐射度；逐项与 `mega_shade.hlsli` 的 `RtIndirectRadiance` 对齐（§17.2）。
     *
     * 两边算得不一样时自检会报「GPU 与 CPU 参考差很大」，而画面看不出问题 —— 改一边必须改另一边。
     */
    private fun shadeBounce(albedo: RtVec3, emissive: RtVec3, roughness: Float, metalness: Float,
                            normal: RtVec3, sunDir: RtVec3, sunColor: RtVec3, skyColor: RtVec3,
                            visible: Float, viewDir: RtVec3): RtVec3 {
        val metal = metalness.coerceIn(0f, 1f)
        val smoothness = 1f - roughness.coerceIn(0f, 1f)
        val diffuse = albedo * (1f - metal)
        val f0 = albedo * metal + RtVec3(0.04f, 0.04f, 0.04f) * (1f - metal)
        val exponent = 2.0.pow((1.0 + 9.0 * smoothness).toDouble()).toFloat()
        // 半角向量要自己归一化：RtVec3 故意只放契约里有的运算（contract_check 逐条对拍）。
        val halfRaw = sunDir - viewDir
        val halfLen = halfRaw.length()
        val halfDir = if (halfLen > 0f) halfRaw * (1f / halfLen) else halfRaw
        val lobe = maxOf(normal.dot(halfDir), 0f).toDouble().pow(exponent.toDouble()).toFloat()
        val specular = f0 * (lobe * ((exponent + 2f) / 8f))
        val sun = (diffuse * maxOf(normal.dot(sunDir), 0f) + specular) * sunColor * visible
        return emissive + sun + diffuse * skyColor
    }

    /** GpuBuffer.USAGE_MAP_READ：READBACK 堆，可 Map 回读。 */
    private const val READ_USAGE = 1
}




