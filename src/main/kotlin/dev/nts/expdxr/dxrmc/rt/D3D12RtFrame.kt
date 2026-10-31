package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.RtBackend
import dev.nts.expdxr.RtMode
import dev.nts.expdxr.RtDenoise
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Quaternionfc
import org.joml.Vector3f
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import java.util.concurrent.atomic.AtomicLong

/**
 * 逐帧光追派发：把几何池与材质表上传成 GPU 缓冲，跑一次 B-Mega 遍历，再合光成一张 HDR 结果。
 * 池每写一段、材质表每重烘一次才会重传，其余帧只重传 112 B 的相机常量。
 * 见 docs/rt/README.md
 */
object D3D12RtFrame {
    /** 常量缓冲就是 `WgaFrame`（b0，416 B），与 WorkGraph 后端共用布局。 */
    const val SCENE_BYTES = D3D12RtFrameConstants.BYTES

    /** 本项目内部约定：缓冲型 UAV 必须带这一位，native 才给它 `ALLOW_UNORDERED_ACCESS`。 */
    private const val UAV_USAGE = 1024

    /** raw view 的 `NumElements` 是 `size/4`，小于 16 B 的缓冲建不出合法视图。 */
    private const val MIN_BYTES = 16L

    /** `rt-hdr` 的每像素字节数（`RGBA16_FLOAT`）。 */
    private const val HDR_BYTES = 8L

    /** 与两个着色器的 `[numthreads(8, 8, 1)]` 一致。 */
    private const val THREADS = 8

    /** BLAS 线框档的线程组宽度与「每个实例 12 条棱」，必须与 mega_debug_blas.hlsl 一致。 */
    private const val BLAS_THREADS = 64
    private const val BLAS_EDGES_PER_BOX = 12

    /** 相机常量的三份轮转 UPLOAD 缓冲：单份会在 GPU 还在读的时候被下一帧改写。 */
    private const val SCENE_RING = 3

    /** 常量缓冲的槽跨度：`WgaFrame` 416 B，CBV 偏移必须是 256 的倍数，取 512。 */
    private const val FRAME_SLOT_BYTES = 512

    /** 镜像里最多放多少段：再多就是给 shader 的循环加无用功，追踪也够不到。 */
    private const val MIRROR_RECORDS = 384

    /** 镜像三块缓冲合计的字节上限。世界加载时每帧都有新段进来，不设上限就会每帧传几百 MB。 */
    private const val MIRROR_BYTES = 128L * 1024 * 1024

    /** 最短重建间隔（帧）。段上传会让 revision 每帧都变，不节流等于每帧重建镜像。 */
    private const val MIRROR_INTERVAL = 60

    /** 相机位移超过这么多方块就立刻重建，不等节流。 */
    private const val MIRROR_SLACK = 24f

    // 合光输出必须同时带 RENDER_ATTACHMENT：上屏走 blitTextureToTexture，它收尾把源切回
    // RENDER_TARGET，只带 STORAGE 的资源切到这个状态是非法的（Close 直接 E_INVALIDARG）。
    private const val OUTPUT_USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_RENDER_ATTACHMENT

    /**
     * 光追派发的内部分辨率除数下限：B-Mega 是逐像素线性扫整池，全分辨率在真实世界里会把一帧跑到几十秒。
     * 见 docs/rt/README.md
     */
    private const val TRACE_MIN_DIVISOR = 4

    /** 几何池段数低于它就认为「世界是在光追关闭时载入的」，触发一次原版重建。 */
    private const val GEOMETRY_MIN_RECORDS = 32

    /** tick 在客户端 tick 事件里跑（每秒 20 次）：60 次 = 3 秒还没有任何几何就报一次。 */
    private const val STARVED_TICKS = 60

    /** S2 占位的太阳仰角（弧度）：固定 59°，只让东西方向跟原版太阳走。 */
    private const val S2_SUN_ELEVATION = 59.0 * PI / 180.0

    /** 辐亮度的目标高度（像素）；除数按它反推，`TRACE_MIN_DIVISOR` 兜底。 */
    private const val TRACE_TARGET_HEIGHT = 160

    /** `renderScale` 缩到 25% 时目标高度只剩 40 像素，再低就没有降噪的余地了。 */
    private const val TRACE_MIN_SCALED_HEIGHT = 40

    /**
     * 只扫相机这么远的段（方块单位）。0 表示不裁，自检场景用 0；真实世界必须裁，否则求交次数爆炸。
     * 见 docs/rt/README.md
     */
    private const val TRACE_RADIUS = 96.0f

    /** 一帧的相机与光照参数；坐标是世界坐标（方块单位）。 */
    class Camera(
        val position: RtVec3,
        val right: RtVec3,
        val up: RtVec3,
        val forward: RtVec3,
        val tanHalfFovY: Float,
        val aspect: Float,
        val sunDir: RtVec3,
        val sunColor: RtVec3,
        )

    private class Radiance(
        val width: Int,
        val height: Int,
        val direct: D3D12GpuBuffer,
        val indirect: D3D12GpuBuffer,
        val aux: D3D12GpuBuffer,
        val bytes: Long,
    ) {
        fun close() {
            for (buffer in arrayOf(direct, indirect, aux)) {
                try {
                    buffer.close()
                } catch (_: Throwable) {
                    // 单块销毁失败不能挡住其余两块。
                }
            }
        }
    }

    /** 一帧的 `WgaFrame`：上传堆缓冲，按 `FRAME_SLOT_BYTES` 分槽轮转（CBV 偏移必须 256 对齐）。 */
    private class Scene(val buffer: D3D12GpuBuffer) {
        fun close() {
            try { buffer.close() } catch (_: Throwable) { }
        }
    }

    private class Mirrors(
        val key: Long,
        val recordCount: Int,
        val records: D3D12GpuBuffer,
        val recordBytes: ByteBuffer,
        val vertices: D3D12GpuBuffer,
        val indices: D3D12GpuBuffer,
        val materials: D3D12GpuBuffer,
        val quads: D3D12GpuBuffer,
        val bytes: Long,
        /** 顶点 / 索引缓冲的字节数：几何契约检查要拿它判记录里的偏移有没有越界。 */
        val vertexBytes: Long,
        val indexBytes: Long,
    ) {
        fun close() {
            for (buffer in arrayOf(records, vertices, indices, materials, quads)) {
                try {
                    buffer.close()
                } catch (_: Throwable) {
                    // 同上。
                }
            }
        }
    }

    @Volatile
    private var radiance: Radiance? = null

    @Volatile
    private var mirrors: Mirrors? = null

    @Volatile
    private var compositePipeline = 0L

    /** 这一帧的辐亮度缓冲是否真的被派发写过；没写成的话合光会把过期内容铺满屏幕。 */
    private var frameValid = false

    /** 已经为补齐几何池触发过一次原版重建。 */
    private var geometryRetriggered = false
    private var reconciled = false

    /** 太阳方向只记一次日志，避免每帧刷屏。 */
    private var sunLogged = false

    /** 走加速结构这条路只记一次；退回暴力路径也只在第一次退回时提示。 */
    private var accelLogged = false
    private var fallbackLogged = false
    private var wgWarned = false
    private var debugPipeline = 0L
    private var blasPipeline = 0L
    @Volatile
    private var output: D3D12GpuTexture? = null

    @Volatile
    private var outputWidth = 0

    @Volatile
    private var outputHeight = 0

    @Volatile
    private var sceneIndex = 0

    @Volatile
    private var dispatches = 0L
    private var frameCounter = 0L
    /** 连续多少个 tick 几何池还是空的（见 [STARVED_TICKS]）。 */
    private var starvedTicks = 0
    private var starvedReported = false

    /**
     * 上一帧的世界→裁剪矩阵（列入 [D3D12RtFrameConstants.write] 的 `g_prevViewProj`）。
     * 运动矢量、重投影、TAA 全读这一份：没人写它时 `g_prevViewProj` 恒等于当前帧，位移永远是 0，
     * 而且不会报任何错。见 docs/rt/README.md §19。
     */
    private val previousViewProjection = FloatArray(16)
    private val previousView = FloatArray(16)
    private val nrdMatrices = FloatArray(64)
    /** DLSS-D 的 `slSetConstants` 载荷：四个行主序矩阵 + 近/远/竖直视场/宽高比。 */
    private val slConstants = FloatArray(D3D12RtFrameConstants.SL_CONSTANTS_FLOATS)
    /** 上一次降噪用的（轨道, 宽, 高）：变了就丢历史，避免把上一套分辨率的历史搬过来。 */
    private var denoiseSignature = Long.MIN_VALUE

    /** 本帧 NRD 写好的辐亮度；为 null 时合光直接读三块缓冲。 */
    private var denoisedRadiance: D3D12GpuTexture? = null

    /** 第七槽的占位：1x1 的 RGBA16F，只在降噪没跑的帧上绑给 `gDenoised`。 */
    private var fallbackTexture: D3D12GpuTexture? = null

    private fun ensureFallback(device: D3D12Device): D3D12GpuTexture? {
        fallbackTexture?.let { return it }
        val texture = try {
            device.createTexture(
                "rt-nrd-fallback", GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA16_FLOAT, 1, 1, 1, 1,
            ) as? D3D12GpuTexture
        } catch (t: Throwable) {
            fail("fallback texture failed: ${t.message}")
            null
        } ?: return null
        fallbackTexture = texture
        return texture
    }
    private var previousValid = false

    /** 光追链异常计数：只报前四次（见 reportFailure）。 */
    private val throwables = AtomicLong()
    private const val MAX_THROWABLES = 4L
    private var mirrorFrame = 0L
    private var mirrorX = 0f
    private var mirrorY = 0f
    private var mirrorZ = 0f

    @Volatile
    private var failures = 0
    private var revDropped = 0
    private var mirrorInterval = MIRROR_INTERVAL
    private var scene: Scene? = null
    private var frameCbv = 0L
    private var frameCbvOffset = 0L

    fun isActive(): Boolean = D3D12Settings.rtEnabled

    fun summary(): String = "rt frame: dispatches=${dispatches} records=${mirrors?.recordCount ?: 0} " +
        "size=${outputWidth}x${outputHeight}"

    /** 光追关掉后把 GPU 资源还回去；与材质表共用同一个 tick。 */
    fun tick() {
        // 老配置里 mode 可能停在 OFF（只有 backend 被写过）：包是这两个键的来源，进入世界时对一次账。
        if (!reconciled) {
            reconciled = true
            if (D3D12ShaderPacks.reconcile()) {
                D3D12Settings.reload()
            }
        }
        if (!isActive()) {
            D3D12RtTrace.reportDisabled()
            D3D12RtStats.release()
            geometryRetriggered = false
            if (radiance != null || mirrors != null || output != null) {
                release()
            }
            return
        }
        retriggerGeometry()
        // 光追开着却一段几何都没有：整条链没启动（最常见的是 G-Buffer 变体没建起来）。
        // 这里只在长时间空转后报一次，免得把「刚进世界还在建段」当成故障。
        if (D3D12RtGeometryPool.recordCount() > 0) {
            starvedTicks = 0
        } else if (!starvedReported && ++starvedTicks >= STARVED_TICKS) {
            starvedReported = true
            IllusionLogger.warnMessage(
                "rt: no geometry after ${STARVED_TICKS / 20}s, gbuffer replay never ran")
        }
    }

    /**
     * 世界在光追关闭时载入的话，那些段不会经过上传钩子，几何池里只剩零散几段——画面上只有脚下一小圈有光追。
     * 见 docs/rt/README.md
     */
    private fun retriggerGeometry() {
        if (geometryRetriggered || D3D12RtGeometryPool.recordCount() >= GEOMETRY_MIN_RECORDS) {
            return
        }
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera()
        if (!camera.isInitialized()) {
            return
        }
        geometryRetriggered = true
        val had = D3D12RtGeometryPool.recordCount()
        client.levelRenderer.invalidateCompiledGeometry(level, client.options, camera, client.blockColors)
        IllusionLogger.infoMessage("rt geometry: rebuilt (pool $had)")
    }

    fun release() = synchronized(frameLock) {
        scene?.close()
        scene = null
        val currentRadiance = radiance
        val currentMirrors = mirrors
        val currentOutput = output
        radiance = null
        mirrors = null
        output = null
        frameValid = false
        outputWidth = 0
        outputHeight = 0
        // 加速结构引用镜像缓冲的 GPU 地址，必须在镜像被销毁之前释放。
        D3D12RtBlas.release()
        currentRadiance?.close()
        currentMirrors?.close()
        currentOutput?.close()
        // 占位纹理跟着设备走：设备换了它就没用了，必须一起释放。
        fallbackTexture?.close()
        fallbackTexture = null
        denoisedRadiance = null
        D3D12RtDlssd.reset()
        // 三个都是 compute 管线（D3D12CreateComputePipeline*），原生侧是 Dx12Object*，不是 Dx12Pipeline*：
        // 用 D3D12DestroyPipeline 释放等于按错的类型 delete，析构里读到别的对象的字段 → 直接 AV 崩进程。
        // 图形/mesh 管线（Dx12Pipeline*）才走 D3D12DestroyPipeline。
        if (compositePipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(compositePipeline)
            compositePipeline = 0L
        }
        if (debugPipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(debugPipeline)
            debugPipeline = 0L
        }
        if (blasPipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(blasPipeline)
            blasPipeline = 0L
        }
        // 追踪管线也一起还回去：换包之后重新打开光追就会按新包的源码重编。
        D3D12RtTrace.releasePipeline()
        D3D12RtMotion.release()
        D3D12RtNrdPack.release()
    }

    /** 一帧的相机；读不到（还没初始化）时返回 null，调用方跳过这次派发。 */
    fun currentCamera(aspect: Float): Camera? {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        if (!camera.isInitialized() || aspect <= 0f) return null
        val rotation = camera.rotation()
        val position = camera.position()
        return Camera(
            position = RtVec3(position.x.toFloat(), position.y.toFloat(), position.z.toFloat()),
            right = axis(rotation, 1f, 0f, 0f),
            up = axis(rotation, 0f, 1f, 0f),
            forward = axis(rotation, 0f, 0f, -1f),
            tanHalfFovY = tan(camera.fov * PI / 360.0).toFloat(),
            aspect = aspect,
            sunDir = sunDirection(camera),
            sunColor = SUN_COLOR,
            )
    }

    /**
     * 主光线 + 1 弹射，写进三张辐亮度缓冲。返回 false 表示这次没派发（调用方无需处理）。
     * 见 docs/rt/README.md
     */
    /** 我们持有的显存峰值（六类基线之一）：镜像、相机常量、辐亮度、G-Buffer、HDR 输出。 */
    private fun vramBytes(): Long {
        var bytes = 0L
        mirrors?.let { bytes += it.bytes }
        if (scene != null) {
            bytes += FRAME_SLOT_BYTES.toLong() * SCENE_RING
        }
        radiance?.let { bytes += it.bytes }
        bytes += D3D12RtGBuffer.byteCount()
        output?.let { bytes += it.getWidth(0).toLong() * it.getHeight(0) * HDR_BYTES }
        return bytes
    }

    /** 光追派发：CPU 编码耗时与派发次数进基线统计，真正的实现在 [dispatchImpl]。 */
    fun dispatch(

        device: D3D12Device?,
        encoder: D3D12CommandEncoderBackend?,
        camera: Camera?,
        width: Int,
        height: Int,
    ): Boolean {
        val started = System.nanoTime()
        try {
            return synchronized(frameLock) { dispatchImpl(device, encoder, camera, width, height) }
        } finally {
            D3D12RtStats.onCpuEncode(System.nanoTime() - started)
            if (frameValid) {
                D3D12RtStats.onDispatches(1)
                D3D12RtStats.onVram(vramBytes())
            }
        }
    }

    private fun dispatchImpl(
        device: D3D12Device?,
        encoder: D3D12CommandEncoderBackend?,
        camera: Camera?,
        width: Int,
        height: Int,
    ): Boolean {
        frameValid = false
        // 这几种提前返回以前是静默的：屏幕停在原版画面上、日志一个字都没有，
        // 和「光追根本没开」分不出来。每种原因各报一次。
        if (device == null) return dispatchSkip("no device")
        if (encoder == null) return dispatchSkip("no encoder")
        if (camera == null) return dispatchSkip("no camera")
        if (width <= 0 || height <= 0) return dispatchSkip("empty target ${width}x$height")
        // 3-f：选 DXR 档时逐帧走状态对象 + SBT，选 MEGA 档仍走 compute（两条路的着色器共用同一份
        // 命中与解调代码，所以换档不改变画面语义）。
        val dxrTier = D3D12Settings.rtBackend == RtBackend.DXR.ordinal
        // WG 后端还没实现，快照里它和 MEGA 走同一条路：不提示的话用户会以为 Work Graph 真的在跑。
        if (!wgWarned && D3D12Settings.rtBackend == RtBackend.WG.ordinal) {
            wgWarned = true
            IllusionLogger.warnMessage(
                "rt backend WG is not implemented, using the compute path like MEGA")
        }
        // 加速结构有两种用法：DXR 档走状态对象 + DispatchRays，MEGA 档可选用它替掉段 AABB
        // （同一条 compute kernel，只有可见性判断换了）。见 docs/rt/README.md 13.6。
        val asTier = !dxrTier && D3D12Settings.rtAsTrace
        val buildAccel = dxrTier || asTier
        val brutePipeline = if (dxrTier) 0L else D3D12RtTrace.dispatchPipeline()
        if (!dxrTier && brutePipeline == 0L) return false
        val accelPipeline = if (asTier) D3D12RtTrace.dispatchPipeline(asPath = true) else 0L
        val mirror = mirrorsFor(device, camera) ?: return false
        val sceneTarget = sceneFor(device) ?: return false
        val divisor = traceDivisor(height)
        val traceWidth = (width / divisor).coerceAtLeast(1)
        val traceHeight = (height / divisor).coerceAtLeast(1)
        val frame = radianceFor(device, traceWidth, traceHeight) ?: return false
        encoder.ensureListOpen()
        if (!writeScene(sceneTarget, camera, traceWidth, traceHeight, mirror.recordCount)) return false
        // 降噪的纹理池按屏幕尺寸开，尺寸一变就得整体重建（见 docs/rt/README.md §22）。
        D3D12RtNrd.ensure(device, width, height)
        // 运动矢量必须在这一帧的常量写完之后算：它要靠 g_viewProj / g_prevViewProj 的重投影差。
        D3D12RtMotion.render(
            encoder, D3D12RtGBuffer.texture(D3D12RtGBuffer.LINEAR_DEPTH), frameCbv, frameCbvOffset)
        // NRD 要的两种编码必须在派发之后重排一遍（见 docs/rt/README.md §21）。
        D3D12RtNrdPack.render(
            encoder,
            D3D12RtGBuffer.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS),
            D3D12RtGBuffer.texture(D3D12RtGBuffer.LINEAR_DEPTH),
            frameCbv, frameCbvOffset)
        // 五个槽位都准备好之后打一行（一次进程），降噪与超分接线时要靠它。
        D3D12RtGuide.noteOnce()
        // 每个进程一次：默认档不建加速结构，这条检查得自己跑（见 docs/rt/README.md 16.6）。
        D3D12RtBlas.checkContractOnce(
            mirror.recordBytes, mirror.recordCount, mirror.vertexBytes, mirror.indexBytes)
        if (buildAccel) {
            D3D12RtBlas.prepare(
                device, encoder.nativeHandle(), mirror.recordBytes, mirror.recordCount,
                mirror.vertices.handle(), mirror.indices.handle(), mirror.vertexBytes, mirror.indexBytes,
            )
        }
        val accelView = if (buildAccel) D3D12RtBlas.view() else 0L
        val accel = accelView != 0L
        val useAccelPipeline = asTier && accel && accelPipeline != 0L
        val pipeline = if (useAccelPipeline) accelPipeline else brutePipeline
        // 绑几个 SRV 由**管线**决定，不是由「AS 建没建出来」决定：MEGA + asTrace 时若加速结构管线没建起来，
        // 实际派发的是 5 个槽的暴力管线，多绑一个 t5 会被原生按数量校验拒掉（每帧静默失败、画面等于没开光追）。
        val asSrvs = dxrTier || useAccelPipeline
        val traceSrvs = if (asSrvs) longArrayOf(
            mirror.records.handle(), mirror.vertices.handle(), mirror.indices.handle(),
            mirror.materials.handle(), mirror.quads.handle(), accelView,
        ) else longArrayOf(
            mirror.records.handle(), mirror.vertices.handle(), mirror.indices.handle(),
            mirror.materials.handle(), mirror.quads.handle(),
        )
        val traceUavs = longArrayOf(frame.direct.handle(), frame.indirect.handle(), frame.aux.handle())
        if (accel) {
            if (!accelLogged) {
                accelLogged = true
                IllusionLogger.infoMessage(
                    "rt trace: ${if (dxrTier) "dxr" else "as"} tier on, tlas slot=$accelView " +
                        "inst=${D3D12RtBlas.instances()}",
                )
            }
        } else if (dxrTier && !fallbackLogged) {
            fallbackLogged = true
            IllusionLogger.warnMessage("rt trace: dxr tier needs the accel structure")
        }
        val constants = intArrayOf(0)
        if (frameCbv == 0L) {
            // 常量缓冲没绑上时着色器拿到的全是 0：宁可不派发（画面留在原版），也不要写一屏黑。
            fail("frame constants are not bound")
            return false
        }
        val ok = if (dxrTier) {
            if (!accel) {
                fail("dxr tier needs the acceleration structure")
                return false
            }
            D3D12RtDxr.dispatch(
                device, encoder.nativeHandle(), traceSrvs, traceUavs, frameCbv, frameCbvOffset,
                traceWidth, traceHeight)
        } else {
            MCDR_ABI.D3D12DispatchComputeEx(
                encoder.nativeHandle(), pipeline, traceSrvs, traceUavs, constants, frameCbv, frameCbvOffset,
                (traceWidth + THREADS - 1) / THREADS, (traceHeight + THREADS - 1) / THREADS, 1,
            )
        }
        if (!ok) {
            fail("dispatch failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        frameValid = true
        val tier = when {
            dxrTier -> "dxr"
            useAccelPipeline -> "accel"
            else -> "brute"
        }
        D3D12RtStats.setTier(tier)
        D3D12RtStats.onTraceDispatched(traceWidth, traceHeight, mirror.recordCount)
        // 降噪轨：辐亮度先搬成 2D 纹理，再交给 NRD 逐帧派发（见 docs/rt/README.md §23.5）。
        if (!denoiseFrame(encoder, frame, width, height, frameCbv, frameCbvOffset)) {
            // 降噪没跑：这一段记 0，合光的耗时仍然从降噪那一点算起。
            D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_DENOISE)
        }
        D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_TRACE)
        val count = dispatches + 1
        dispatches = count
        // 真值表按显示分辨率算，表变了才打一行（见 docs/rt/README.md §18）。
        D3D12RtPipelinePlan.note(D3D12RtPipelinePlan.configured(width, height))
        D3D12RtNrd.inspectOnce()
        if (count == 1L) {
        lastTier = tier
        lastDispatch = "tier=$tier accelView=$accelView records=${mirror.recordCount} " +
            "trace=${traceWidth}x$traceHeight"
        // 放在 lastDispatch 之后：这类报告的意义就是把「这一帧到底走了哪条路」带出去。
        D3D12RtTrace.reportPending()
        IllusionLogger.infoMessage("rt frame: dispatch ${traceWidth}x$traceHeight " +
                "of ${width}x$height records=${mirror.recordCount} tier=$tier " +
                "triangles=${D3D12RtGeometryPool.triangleCount()} radius=$TRACE_RADIUS")
        }
        return true
    }

    /**
     * 合光：`albedo * (direct + indirect) + emissive` 写进一张 HDR 纹理并返回它，供调用方 blit 上屏。
     * 见 docs/rt/README.md
     */
    /* 资源在 tick（客户端线程）里释放、在 dispatch/composite（渲染线程）里使用：两边必须互斥，
       否则关光追的一瞬间会把正在写的目标释放掉（设备移除）。 */
    private val frameLock = Any()

    /** 合光没跑成的原因各报一次：静默返回会让「光追看不见」没有任何线索。 */
    private val dispatchSkips = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 派发没跑起来时留一行（每种原因一次）：否则画面等于原版而日志无一字。 */
    /** 最近一次派发用的档位与实例数：自检报告里带出去，省得再跑一次看日志。 */
    private var lastDispatch = "none"

    /** 最近一次真正派发走的档：报告里的 `tier` 要用它，而不是「配置里写着什么」。 */
    private var lastTier: String? = null

    fun lastDispatchTier(): String? = lastTier

    fun lastDispatchSummary(): String = lastDispatch

    private fun dispatchSkip(reason: String): Boolean {
        if (dispatchSkips.add(reason)) IllusionLogger.warnMessage("rt dispatch: skipped, $reason")
        return false
    }

    private val compositeSkips = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun compositeSkip(reason: String): D3D12GpuTexture? {
        if (compositeSkips.add(reason)) {
            IllusionLogger.warnMessage("rt composite: skipped, $reason")
        }
        return null
    }

    fun composite(
        encoder: D3D12CommandEncoderBackend?,
        albedo: D3D12GpuTexture?,
        emissive: D3D12GpuTexture?,
        source: D3D12GpuTexture?,
    ): D3D12GpuTexture? = synchronized(frameLock) {
        if (encoder == null) return compositeSkip("no encoder")
        if (albedo == null || emissive == null || source == null) return compositeSkip("missing inputs")
        val started = System.nanoTime()
        val device = encoder.device ?: return compositeSkip("no device")
        val frame = radiance ?: return compositeSkip("no radiance targets")
        if (!frameValid) return compositeSkip("frame invalid")
        // 输出与 G-Buffer 同尺寸：albedo/emissive 是逐像素读的，输出小了会把 albedo 抽稀。
        val width = albedo.getWidth(0)
        val height = albedo.getHeight(0)
        if (width <= 0 || height <= 0) return compositeSkip("empty albedo")
        val pipeline = ensureCompositePipeline() ?: return compositeSkip("no pipeline")
        val target = ensureOutput(device, width, height) ?: return compositeSkip("no output target")
        encoder.ensureListOpen()
        // 降噪轨：上一帧内 NRD 写好的那张（没有就把开关置 0，画面退回直接读三块缓冲）。
        val denoised = denoisedRadiance
        // 降噪没跑时第七槽留着不用（开关是 0），绑一张同尺寸的占位纹理免得堆里出现空描述符。
        val denoisedView = denoised ?: ensureFallback(device) ?: return compositeSkip("no denoised fallback")
        val compositeSrvs = longArrayOf(
            albedo.handle(), frame.direct.handle(), emissive.handle(), frame.indirect.handle(),
            source.handle(), frame.aux.handle(),
            denoisedView.handle(),
        )
        val compositeUavs = longArrayOf(target.handle())
        val constants = intArrayOf(
            if (denoised != null) 1 else 0,
            D3D12RtNrdRadiance.DENOISING_RANGE.toRawBits(),
        )
        if (frameCbv == 0L) return compositeSkip("frame constants not bound")
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, compositeSrvs, compositeUavs, constants, frameCbv, frameCbvOffset,
            (width + THREADS - 1) / THREADS, (height + THREADS - 1) / THREADS, 1,
        )
        if (!ok) {
            fail("composite failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_COMPOSITE)
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return target
    }

    /**
     * 调试图：把 [source] 的第 [channel] 个通道拉成灰度写进一张 HDR 目标，供 `debugView` 直接铺屏。
     * 粗糙度这类单通道数据不能直接 blit（blit 只采三通道），走这里过一遍 compute。
     * 见 docs/rt/README.md
     */
    fun debugChannel(
        encoder: D3D12CommandEncoderBackend?,
        source: D3D12GpuTexture?,
        channel: Int,
    ): D3D12GpuTexture? = synchronized(frameLock) {
        // `channel` 0..3 = 取单个通道铺灰；4 = 法线档（0.5 + 0.5 * rgb），见 D3D12RtDebugShader。
        if (encoder == null || source == null || channel !in 0..4) return null
        val started = System.nanoTime()
        val device = encoder.device ?: return null
        val width = source.getWidth(0)
        val height = source.getHeight(0)
        if (width <= 0 || height <= 0) return null
        val pipeline = ensureDebugPipeline() ?: return null
        val target = ensureOutput(device, width, height) ?: return null
        encoder.ensureListOpen()
        // 常量槽里 g_misc.y 每帧都被追踪写回 0，这里必须自己打上通道号，否则永远按通道 0 取。
        patchDebugMode(channel)
        val debugSrvs = longArrayOf(source.handle())
        val debugUavs = longArrayOf(target.handle())
        val constants = intArrayOf(0)
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, debugSrvs, debugUavs, constants, frameCbv, frameCbvOffset,
            (width + THREADS - 1) / THREADS, (height + THREADS - 1) / THREADS, 1,
        )
        if (!ok) {
            fail("debug view failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return target
    }

    /**
     * BLAS 实例线框（`debugView = BLAS`）：把 TLAS 里每个实例的段盒画成彩色棱线，直接叠在 [target] 上。
     *
     * 只画线不拷底图，所以目标就是合成结果那张纹理；前一段派发也写过它，
     * 派发前必须插一条 UAV 屏障，否则线框会读到旧像素。见 docs/rt/README.md §14.6
     */
    fun debugBlas(
        encoder: D3D12CommandEncoderBackend?,
        target: D3D12GpuTexture?,
    ): D3D12GpuTexture? = synchronized(frameLock) {
        if (encoder == null || target == null || frameCbv == 0L) return null
        val instances = D3D12RtBlas.instances()
        val table = D3D12RtBlas.instanceTable()
        if (instances <= 0 || table == 0L) return null
        val started = System.nanoTime()
        val pipeline = ensureBlasPipeline() ?: return null
        encoder.ensureListOpen()
        MCDR_ABI.D3D12Barrier(
            encoder.nativeHandle(),
            listOf(MCDR_ABI.BarrierDesc(target.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV)),
        )
        val threads = instances * BLAS_EDGES_PER_BOX
        val blasSrvs = longArrayOf(table)
        val blasUavs = longArrayOf(target.handle())
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, blasSrvs, blasUavs, intArrayOf(instances),
            frameCbv, frameCbvOffset, (threads + BLAS_THREADS - 1) / BLAS_THREADS, 1, 1,
        )
        if (!ok) {
            fail("blas view failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return target
    }

    /** 把 `g_misc.y` 打成 [mode]，其余常量不动（见 D3D12RtFrameConstants.patchDebugMode）。 */
    private fun patchDebugMode(mode: Int) {
        val current = scene ?: return
        try {
            current.buffer.map(frameCbvOffset, SCENE_BYTES.toLong(), false, true).use { view ->
                D3D12RtFrameConstants.patchDebugMode(view.data(), mode)
            }
        } catch (t: Throwable) {
            fail("debug mode patch failed: ${t.message}")
        }
    }

    private fun ensureDebugPipeline(): Long? {
        val cached = debugPipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtDebugShader.build(), D3D12RtDebugShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("debug view compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtDebugShader.SRV_COUNT, D3D12RtDebugShader.UAV_COUNT)
        if (handle == 0L) {
            fail("debug view pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        debugPipeline = handle
        return handle
    }

    private fun ensureBlasPipeline(): Long? {
        val cached = blasPipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtDebugShader.buildBlas(), D3D12RtDebugShader.BLAS_ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("blas view compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtDebugShader.BLAS_SRV_COUNT, D3D12RtDebugShader.BLAS_UAV_COUNT)
        if (handle == 0L) {
            fail("blas view pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        blasPipeline = handle
        return handle
    }

    private fun ensureCompositePipeline(): Long? {
        val cached = compositePipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtCompositeShader.build(), D3D12RtCompositeShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("composite compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtCompositeShader.SRV_COUNT, D3D12RtCompositeShader.UAV_COUNT)
        if (handle == 0L) {
            fail("composite pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        compositePipeline = handle
        return handle
    }

    private fun ensureOutput(device: D3D12Device, width: Int, height: Int): D3D12GpuTexture? {
        val current = output
        if (current != null && outputWidth == width && outputHeight == height) return current
        val created = try {
            device.createTexture("rt-hdr", OUTPUT_USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture
                ?: throw IllegalStateException("rt-hdr allocate failed (${MCDR_ABI.D3D12LastError()})")
        } catch (t: Throwable) {
            fail("hdr allocate failed: ${t.message}")
            return null
        }
        output = created
        outputWidth = width
        outputHeight = height
        // 分辨率一变，上一帧矩阵的宽高比就对不上了：旧矩阵当上一帧用会算出一帧乱飞的运动矢量。
        previousValid = false
        // DLSS-D 的选项按输出尺寸缓存，尺寸变了要允许它重新下发一次。
        D3D12RtDlssd.reset()
        IllusionLogger.infoMessage("rt: hdr ${width}x$height")
        return created
    }

    private fun radianceFor(device: D3D12Device, width: Int, height: Int): Radiance? {
        val current = radiance
        if (current != null && current.width == width && current.height == height) return current
        val bytes = (width.toLong() * height * 16L).coerceAtLeast(MIN_BYTES)
        val created = try {
            Radiance(
                width, height,
                uav(device, "rt-direct", bytes),
                uav(device, "rt-indirect", bytes),
                uav(device, "rt-aux", bytes),
                bytes * 3L,
            )
        } catch (t: Throwable) {
            fail("radiance allocate failed: ${t.message}")
            return null
        }
        radiance = created
        return created
    }

    private fun uav(device: D3D12Device, label: String, bytes: Long): D3D12GpuBuffer =
        device.createBuffer(Supplier { label }, UAV_USAGE, bytes) as? D3D12GpuBuffer
            ?: throw IllegalStateException("$label allocate failed (${MCDR_ABI.D3D12LastError()})")

    private fun mirrorsFor(device: D3D12Device, camera: Camera): Mirrors? {
        frameCounter++
        val revisionKey = D3D12RtGeometryPool.revision() * 1_000_003L + D3D12RtMaterialTable.revision()
        val current = mirrors
        if (current != null && current.key == revisionKey) return current
        val moved = abs(camera.position.x - mirrorX) + abs(camera.position.y - mirrorY) +
            abs(camera.position.z - mirrorZ) > MIRROR_SLACK
        if (current != null && !moved && frameCounter - mirrorFrame < mirrorInterval) {
            return current
        }
        mirrorFrame = frameCounter
        // 只镜像相机附近的一小片：整池上传在世界加载时会每帧传几百 MB，直接把画面卡死。
        val slice = D3D12RtGeometryPool.mirrorNear(
            camera.position.x, camera.position.y, camera.position.z,
            TRACE_RADIUS + MIRROR_SLACK, MIRROR_RECORDS, MIRROR_BYTES,
        ) ?: return current
        mirrorX = camera.position.x
        mirrorY = camera.position.y
        mirrorZ = camera.position.z
        val materials = D3D12RtMaterialTable.entryBytes()
        if (materials.remaining() <= 0) return current
        val key = revisionKey
        val startedAt = System.nanoTime()
        // 加速结构要用同一份记录的 CPU 字节，先留一份副本：upload() 会消费掉原缓冲的位置。
        val recordBytes = slice.records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val created = try {
            Mirrors(
                key = key,
                recordCount = slice.recordCount,
                records = upload(device, "rt-records", slice.records),
                recordBytes = recordBytes,
                vertices = upload(device, "rt-vertices", slice.vertices),
                indices = upload(device, "rt-indices", slice.indices),
                materials = upload(device, "rt-materials", materials),
                quads = upload(device, "rt-quads", slice.quads),
                bytes = (slice.records.remaining() + slice.vertices.remaining() + slice.indices.remaining() +
                    materials.remaining() + slice.quads.remaining()).toLong(),
                vertexBytes = slice.vertices.remaining().toLong(),
                indexBytes = slice.indices.remaining().toLong(),
            )
        } catch (t: Throwable) {
            fail("mirror upload failed: ${t.message}")
            return null
        }
        val elapsedNanos = System.nanoTime() - startedAt
        val previous = mirrors
        mirrors = created
        previous?.close()
        revDropped++
        // 重建本身慢了就把间隔翻倍（世界加载时 CPU 已经很忙），快了再退回来，避免同一份开销每帧重演。
        mirrorInterval = if (elapsedNanos > 8_000_000L) {
            (mirrorInterval * 2).coerceAtMost(600)
        } else if (elapsedNanos < 2_000_000L) {
            (mirrorInterval / 2).coerceAtLeast(MIRROR_INTERVAL)
        } else {
            mirrorInterval
        }
        val line = "rt frame: mirror records=${slice.recordCount} triangles=${slice.triangles} " +
            "v=${slice.vertices.remaining() shr 10}KB i=${slice.indices.remaining() shr 10}KB " +
            "q=${slice.quads.remaining() shr 10}KB m=${materials.remaining()}B " +
            "buildMs=${elapsedNanos / 1_000_000} interval=$mirrorInterval rebuilds=$revDropped"
        if (revDropped == 1) IllusionLogger.infoMessage(line) else IllusionLogger.debugMessage(line)
        return created
    }

    private fun upload(device: D3D12Device, label: String, data: ByteBuffer): D3D12GpuBuffer {
        val padded = if (data.remaining() % 4 == 0 && data.remaining() >= MIN_BYTES) {
            data
        } else {
            val size = ((data.remaining() + 3) / 4 * 4).coerceAtLeast(MIN_BYTES.toInt())
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also {
                it.put(data.duplicate())
                it.flip()
            }
        }
        return device.createBuffer(Supplier { label }, 0, padded) as? D3D12GpuBuffer
            ?: throw IllegalStateException("$label allocate failed (${MCDR_ABI.D3D12LastError()})")
    }

    private fun sceneFor(device: D3D12Device): Scene? {
        scene?.let { return it }
        return try {
            val created = Scene(
                buffer = device.createBuffer(
                    Supplier { "rt-frame-constants" }, GpuBuffer.USAGE_MAP_WRITE,
                    (FRAME_SLOT_BYTES * SCENE_RING).toLong(),
                ) as? D3D12GpuBuffer
                    ?: throw IllegalStateException("rt-frame-constants allocate failed (${MCDR_ABI.D3D12LastError()})"),
            )
            scene = created
            created
        } catch (t: Throwable) {
            fail("scene buffer failed: ${t.message}")
            null
        }
    }

    /**
     * 降噪轨（S4 4-c）：把这一帧的辐亮度搬成纹理再交给 NRD。
     *
     * 只在 `[raytracing] denoise` 不是 `OFF` 时跑；返回 true 表示这一帧真的派发过（调用方就不用再补打点）。
     */
    private fun denoiseFrame(
        encoder: D3D12CommandEncoderBackend,
        frame: Radiance,
        width: Int,
        height: Int,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        denoisedRadiance = null
        if (D3D12Settings.rtDenoise == RtDenoise.OFF) {
            return false
        }
        val motion = D3D12RtMotion.texture()
        val normalRoughness = D3D12RtNrdPack.normalRoughnessTexture()
        val viewZ = D3D12RtNrdPack.viewZTexture()
        if (motion == null || normalRoughness == null || viewZ == null) {
            return false
        }
        if (!D3D12RtNrdRadiance.render(
                encoder, frame.direct.handle(), frame.indirect.handle(), frame.aux.handle(),
                width, height, cbv, cbvOffset)) {
            return false
        }
        val signature = D3D12Settings.rtDenoise.ordinal.toLong() * 1_000_000_000L +
            width.toLong() * 10_000L + height
        val resetHistory = signature != denoiseSignature || frameCounter < 3L
        denoiseSignature = signature
        val radianceIn = D3D12RtNrdRadiance.radianceTexture() ?: return false
        val radianceOut = D3D12RtNrdRadiance.outputTexture() ?: return false
        if (D3D12Settings.rtDenoise == RtDenoise.RR) {
            val depth = D3D12RtGBuffer.depthTexture()
            if (depth != null) {
                val accepted = D3D12RtDlssd.evaluate(
                    encoder.nativeHandle(),
                    radianceIn.handle(),
                    radianceOut.handle(),
                    depth.handle(),
                    motion.handle(),
                    normalRoughness.handle(),
                    width,
                    height,
                    width,
                    height,
                    slConstants,
                    resetHistory,
                )
                if (accepted > 0) {
                    D3D12RtStats.onDispatches(accepted)
                    D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_DENOISE)
                    denoisedRadiance = radianceOut
                    return true
                }
            }
            // RR 不可用时整链退回 NRD：宁可降噪档位不符，也不要画面突然回到未降噪。
        }
        val ok = Arena.ofConfined().use { arena ->
            val segment = arena.allocate(256L, 16L)
            for (i in 0 until 64) {
                segment.setAtIndex(ValueLayout.JAVA_FLOAT, i.toLong(), nrdMatrices[i])
            }
            D3D12RtNrd.denoise(
                encoder.nativeHandle(),
                motion.handle(),
                normalRoughness.handle(),
                viewZ.handle(),
                radianceIn.handle(),
                radianceOut.handle(),
                segment,
                frameCounter.toInt(),
                resetHistory,
            )
        }
        if (ok <= 0) {
            return false
        }
        D3D12RtStats.onDispatches(ok)
        D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_DENOISE)
        denoisedRadiance = radianceOut
        return true
    }

    private fun writeScene(
        target: Scene,
        camera: Camera,
        traceWidth: Int,
        traceHeight: Int,
        recordCount: Int,
    ): Boolean {
        val index = (sceneIndex + 1) % SCENE_RING
        sceneIndex = index
        val offset = (index * FRAME_SLOT_BYTES).toLong()
        val painted = try {
            target.buffer.map(offset, SCENE_BYTES.toLong(), false, true).use { view ->
                D3D12RtFrameConstants.write(
                    target = view.data(),
                    position = camera.position,
                    forward = camera.forward,
                    up = camera.up,
                    tanHalfFovY = camera.tanHalfFovY,
                    aspect = camera.aspect,
                    width = traceWidth,
                    height = traceHeight,
                    sunDir = camera.sunDir,
                    sunRadiance = camera.sunColor,
                    sunStrength = SUN_STRENGTH,
                    frameIndex = frameCounter.toInt(),
                    radius = TRACE_RADIUS,
                    recordCount = recordCount,
                    debugMode = 0,
                    // 上一帧矩阵：运动矢量与重投影要用；首帧没有上一帧，按零位移。
                    previousViewProjection = if (previousValid) previousViewProjection else null,
                    previousViewMatrix = if (previousValid) previousView else null,
                    outViewProjection = previousViewProjection,
                    outNrdMatrices = nrdMatrices,
                    outSlConstants = slConstants,
                )
                true
            }
        } catch (t: Throwable) {
            fail("scene map failed: ${t.message}")
            false
        }
        if (!painted) return false
        // 下一帧的「上一帧视图矩阵」就是这一帧写的那个（nrdMatrices 的第三块）。
        nrdMatrices.copyInto(previousView, 0, 32, 48)
        previousValid = true
        // 同一帧的三次派发（追踪/合光/调试）共用这一份常量。
        frameCbv = target.buffer.handle()
        frameCbvOffset = offset
        return true
    }

    /**
     * 光追链上的异常出口：只记前四次，不向渲染线程抛。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    fun reportFailure(t: Throwable) {
        if (throwables.incrementAndGet() > MAX_THROWABLES) return
        IllusionLogger.warnMessage("rt: ${t.javaClass.simpleName}: ${t.message}")
    }

    private fun fail(reason: String) {
        val count = failures + 1
        failures = count
        if (count <= 4) {
            IllusionLogger.warnMessage("rt frame: $reason")
        }
    }

    /**
     * 太阳方向取自原版的 `EnvironmentAttributes.SUN_ANGLE`，按原版 `SkyRenderer` 的 `rotate(YP,-90) → rotate(XP,angle)`。
     * 见 docs/rt/README.md
     */
    private fun sunDirection(camera: net.minecraft.client.Camera): RtVec3 {
        val degrees = try {
            camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, 0f)
        } catch (_: Throwable) {
            return SUN_DIR
        }
        // S2 占位：仰角固定 59°（与原版亮度观感一致），只有东西方向跟原版太阳走 ——。
        // 真按太阳仰角算直射的话，黄昏时整片光追区域会比原版暗一截，半径边缘出现一圈可见边界。
        // 见 docs/rt/README.md
        val angle = degrees * PI / 180.0
        val east = -sin(angle) >= 0.0
        val horizontal = cos(S2_SUN_ELEVATION).toFloat()
        val vertical = sin(S2_SUN_ELEVATION).toFloat()
        val direction = RtVec3(if (east) horizontal else -horizontal, vertical, 0f)
        if (!sunLogged) {
            sunLogged = true
            IllusionLogger.infoMessage(
                "rt sun: angle=" + degrees + " deg east=" + east + " dir=(" + direction.x + ", " + direction.y + ")"
            )
        }
        return direction
    }

    /**
     * 让辐亮度高度落在「目标高度」附近；窗口越大除数越大，单帧耗时基本恒定。
     *
     * 目标高度受 `[raytracing] renderScale` 缩放：100 就是 `TRACE_TARGET_HEIGHT`，25 时降到四分之一。
     * 光追没开（`RtDenoise.OFF`）时忽略倍率，与 `D3D12RtPipelinePlan` 的真值表一致（§18）。
     */
    private fun traceDivisor(height: Int): Int {
        val percent =
            if (D3D12Settings.rtDenoise == RtDenoise.OFF) 100 else D3D12Settings.rtRenderScale
        val target = (TRACE_TARGET_HEIGHT * percent.coerceIn(25, 100) / 100)
            .coerceAtLeast(TRACE_MIN_SCALED_HEIGHT)
        return ((height + target - 1) / target).coerceAtLeast(TRACE_MIN_DIVISOR)
    }

    private fun axis(rotation: Quaternionfc, x: Float, y: Float, z: Float): RtVec3 {
        val vector = rotation.transform(Vector3f(x, y, z))
        return RtVec3(vector.x, vector.y, vector.z)
    }

    /**
     * 太阳方向 / 颜色是 S2 的占位常量：S5 的天空模型接上后再换成逐帧计算。
     * 见 docs/rt/README.md
     */
    private val SUN_DIR = D3D12RtLightContract.normalize(RtVec3(0.35f, 0.86f, 0.37f))
    private val SUN_COLOR = RtVec3(1f, 0.96f, 0.9f)

    /** 太阳辐亮度强度（进 `g_sunRadiance.w`）：与天空走同一套 HDR 出口，1.0 时接近原版观感。 */
    private const val SUN_STRENGTH = 1f
}




