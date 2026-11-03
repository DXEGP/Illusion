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
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12SectionGeometry
import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12Temporal
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.dxrmc.framegen.FrameGeneration
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicLong

/**
 * 逐帧光追派发：把几何池与材质表上传成 GPU 缓冲，跑一次 B-DXR 遍历，再合光成一张 HDR 结果。
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

    /** BLAS 线框档的线程组宽度与「每个实例 12 条棱」，必须与 rt_debug_blas.hlsl 一致。 */
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

    /** 相机位移超过这么多方块就立刻重建，不等节流。 */
    private const val MIRROR_SLACK = 24f

    // 合光输出必须同时带 RENDER_ATTACHMENT：上屏走 blitTextureToTexture，它收尾把源切回
    // RENDER_TARGET，只带 STORAGE 的资源切到这个状态是非法的（Close 直接 E_INVALIDARG）。
    private const val OUTPUT_USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_RENDER_ATTACHMENT


    /** 几何池段数低于它就认为「世界是在光追关闭时载入的」，触发一次原版重建。 */
    private const val GEOMETRY_MIN_RECORDS = 32

    /** tick 在客户端 tick 事件里跑（每秒 20 次）：60 次 = 3 秒还没有任何几何就报一次。 */
    private const val STARVED_TICKS = 60

    /** 派发心跳间隔（毫秒）：按时间而不是按帧，否则高帧率下每秒刷出好几行 INFO。 */
    private const val HEARTBEAT_MILLIS = 5000L

    /** 加速结构与追踪两段的 CPU 编码时长超过这个值就打一行分解（微秒）。 */
    private const val SLOW_FRAME_MICROS = 200_000L

    /** `renderScale` 缩到 25% 时目标高度只剩 40 像素，再低就没有降噪的余地了。 */

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
        val transform: D3D12RtCamera.Transform,
        val farPlane: Float,
        val worldIdentity: Any,
        val worldTime: Long,
        val frameNanos: Long,
        )

    private class Radiance(
        val width: Int,
        val height: Int,
        val direct: D3D12GpuBuffer,
        val indirect: D3D12GpuBuffer,
        val aux: D3D12GpuBuffer,
        val specular: D3D12GpuBuffer,
        val specularGrazing: D3D12GpuBuffer,
        val bytes: Long,
    ) {
        fun close() {
            for (buffer in arrayOf(direct, indirect, aux, specular, specularGrazing)) {
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
        val identity: Long,
        val geometrySnapshot: D3D12RtGeometryPool.MirrorSlice,
        val worldIdentity: Any,
        val geometryEpoch: Int,
        var key: Long,
        val geometryRevision: Long,
        val materialRevision: Long,
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
    private var publishedMirrors: Mirrors? = null

    private var nextMirrorIdentity = 0L

    @Volatile
    private var compositePipeline = 0L

    /** 这一帧的辐亮度缓冲是否真的被派发写过；没写成的话合光会把过期内容铺满屏幕。 */
    private var frameValid = false

    /** 已经为补齐几何池触发过一次原版重建。 */
    private var geometryRetriggered = false
    private var observedGeometryEpoch = Int.MIN_VALUE
    private var reconciled = false

    private var capturedCamera: Camera? = null
    private var capturedCameraStamp = Long.MIN_VALUE

    private var accelLogged = false
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
    /** 心跳节流：上次报时的时刻与当时的规模，规模变了要立刻报出来。 */
    private var heartbeatNanos = 0L
    private var heartbeatRecords = -1
    private var heartbeatTier: String? = null
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
    private val previousJitter = FloatArray(2)
    @Volatile
    private var temporalResolveReady = false
    /** DLSS-D 的 `slSetConstants` 载荷：四个行主序矩阵 + 近/远/竖直视场/宽高比。 */
    private val slConstants = FloatArray(D3D12RtFrameConstants.SL_CONSTANTS_FLOATS)
    private val history = D3D12RtHistory()
    private val rrHistory = D3D12RtHistory.Consumer()
    private val nrdHistory = D3D12RtHistory.Consumer()
    private val srHistory = D3D12RtHistory.Consumer()
    private var historyInvalidation = D3D12RtHistory.Invalidation(0, 0, 0)
    private var previousPlan: D3D12RtPipelinePlan.Plan? = null

    /** 合光只报一次成功行（真值表之外的形状变化靠它确认）。 */
    private val compositeRan = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本帧 NRD 写好的辐亮度；为 null 时合光直接读三块缓冲。 */
    private var denoisedRadiance: D3D12GpuTexture? = null
    private var denoisedSpecular: D3D12GpuTexture? = null
    private var denoisedIsReblur = false

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
    private var mirrorX = 0f
    private var mirrorY = 0f
    private var mirrorZ = 0f

    @Volatile
    private var failures = 0
    private var revDropped = 0
    private var scene: Scene? = null
    private var frameCbv = 0L
    private var frameCbvOffset = 0L

    fun isActive(): Boolean = D3D12Settings.rtEnabled

    fun hasTemporalResolve(): Boolean {
        if (!D3D12Settings.rtEnabled) return false
        val window = Minecraft.getInstance().window
        if (window.width <= 0 || window.height <= 0) return false
        val plan = D3D12RtPipelinePlan.configured(window.width, window.height)
        return plan.enabled && plan.denoise
    }

    fun summary(): String = "rt frame: dispatches=${dispatches} records=${mirrors?.recordCount ?: 0} " +
        "size=${outputWidth}x${outputHeight}"

    /** 光追关掉后把 GPU 资源还回去；与材质表共用同一个 tick。 */
    fun tick() {
        D3D12RenderScale.reconcileRayTracing()
        observeWorld(Minecraft.getInstance().level)
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
        // 只在已经进入世界、且空转够久时报一次——菜单里 tick 照跑，那时本来就没有几何。
        if (D3D12RtGeometryPool.recordCount() > 0 || Minecraft.getInstance().level == null) {
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
        temporalResolveReady = false
        scene?.close()
        scene = null
        val currentRadiance = radiance
        val currentMirrors = mirrors
        val currentPublishedMirrors = publishedMirrors
        val currentOutput = output
        radiance = null
        mirrors = null
        publishedMirrors = null
        output = null
        frameValid = false
        previousPlan = null
        previousValid = false
        capturedCamera = null
        capturedCameraStamp = Long.MIN_VALUE
        history.clear()
        outputWidth = 0
        outputHeight = 0
        // 加速结构引用镜像缓冲的 GPU 地址，必须在镜像被销毁之前释放。
        D3D12RtBlas.release()
        currentRadiance?.close()
        currentMirrors?.close()
        if (currentPublishedMirrors != null && currentPublishedMirrors !== currentMirrors) {
            currentPublishedMirrors.close()
        }
        currentOutput?.close()
        // 占位纹理跟着设备走：设备换了它就没用了，必须一起释放。
        fallbackTexture?.close()
        fallbackTexture = null
        denoisedRadiance = null
        denoisedSpecular = null
        D3D12RtNrd.reset()
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
        D3D12RtNrdRadiance.release()
        D3D12RtMaterialGuide.release()
        D3D12RtSuperResolution.release()
        D3D12RtExposure.release()
    }

    /** 一帧的相机；读不到（还没初始化）时返回 null，调用方跳过这次派发。 */
    fun currentCamera(): Camera? =
        capturedCamera.takeIf { capturedCameraStamp == D3D12Temporal.frameTimestampNanos }

    fun invalidateOutput() = synchronized(frameLock) {
        frameValid = false
    }

    fun captureProjection(data: ByteBuffer) {
        invalidateOutput()
        val client = Minecraft.getInstance()
        capturedCamera = null
        capturedCameraStamp = D3D12Temporal.frameTimestampNanos
        if (data.remaining() != 64) return
        val world = client.level ?: return
        val level = client.gameRenderer.gameRenderState().levelRenderState
        val state = level.cameraRenderState
        if (!state.initialized) return
        val projection = FloatArray(16)
        data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(projection)
        if (projection.any { !it.isFinite() }) return
        val transform = D3D12RtCamera.resolve(
            RtVec3(state.pos.x.toFloat(), state.pos.y.toFloat(), state.pos.z.toFloat()),
            state.viewRotationMatrix,
            state.projectionMatrix,
            Matrix4f().set(projection),
        )
        capturedCamera = Camera(
            position = transform.position,
            right = transform.right,
            up = transform.up,
            forward = transform.forward,
            tanHalfFovY = 1f / state.projectionMatrix.m11(),
            aspect = state.projectionMatrix.m11() / state.projectionMatrix.m00(),
            sunDir = D3D12RtCamera.sunDirection(level.skyRenderState.sunAngle),
            sunColor = SUN_COLOR,
            transform = transform,
            farPlane = state.depthFar,
            worldIdentity = world,
            worldTime = world.defaultClockTime,
            frameNanos = capturedCameraStamp,
        )
    }

    /**
     * 主光线与 diffuse/specular 弹射，写进五张 raw 缓冲。返回 false 表示未派发。
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
        bytes += D3D12RtSuperResolution.byteCount()
        if (D3D12RtExposure.texture() != null) bytes += Float.SIZE_BYTES
        return bytes
    }

    private fun updatePlan(plan: D3D12RtPipelinePlan.Plan) {
        if (previousPlan == plan) return
        D3D12RtNrd.reset()
        D3D12RtDlssd.reset()
        D3D12RtSuperResolution.reset()
        previousPlan = plan
    }

    /** 光追派发：CPU 编码耗时与派发次数进基线统计，真正的实现在 [dispatchImpl]。 */
    internal fun dispatch(

        device: D3D12Device?,
        encoder: D3D12CommandEncoderBackend?,
        camera: Camera?,
        plan: D3D12RtPipelinePlan.Plan,
    ): Boolean {
        val started = System.nanoTime()
        try {
            return synchronized(frameLock) {
                var dispatched = false
                try {
                    dispatchImpl(device, encoder, camera, plan).also { dispatched = it }
                } finally {
                    if (!dispatched) {
                        frameValid = false
                        temporalResolveReady = false
                        history.invalidate(D3D12RtHistory.InvalidationReason.FRAME_GAP)
                    }
                }
            }
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
        plan: D3D12RtPipelinePlan.Plan,
    ): Boolean {
        frameValid = false
        denoisedRadiance = null
        val width = plan.displayWidth
        val height = plan.displayHeight
        // 这几种提前返回以前是静默的：屏幕停在原版画面上、日志一个字都没有，
        // 和「光追根本没开」分不出来。每种原因各报一次。
        if (device == null) return dispatchSkip("no device")
        if (encoder == null) return dispatchSkip("no encoder")
        if (camera == null) return dispatchSkip("no camera")
        if (width <= 0 || height <= 0) return dispatchSkip("empty target ${width}x$height")
        if (!wgWarned && D3D12Settings.rtBackend == RtBackend.WG.ordinal) {
            wgWarned = true
            IllusionLogger.warnMessage("rt: WG unavailable, using DXR")
        }
        if (D3D12RtDxr.pipeline(device) == 0L) return dispatchSkip("DXR unavailable")
        val mirror = mirrorsFor(device, camera) ?: return false
        val sceneTarget = sceneFor(device) ?: return false
        val traceWidth = plan.traceWidth
        val traceHeight = plan.traceHeight
        val frame = radianceFor(device, traceWidth, traceHeight) ?: return false
        encoder.ensureListOpen()
        updatePlan(plan)
        val frameGeneration = FrameGeneration.activeBackend().toLong() shl 32 or
            (FrameGeneration.mode().toLong() shl 16) or FrameGeneration.frames().toLong()
        val jitter = D3D12Temporal.frameJitter(camera.frameNanos)
        D3D12RtBlas.checkContractOnce(
            mirror.recordBytes, mirror.recordCount, mirror.vertexBytes, mirror.indexBytes)
        val accelStart = System.nanoTime()
        D3D12RtBlas.advanceFrame()
        D3D12RtBlas.prepare(
            device, encoder.nativeHandle(), mirror.recordBytes, mirror.recordCount,
            mirror.vertices.handle(), mirror.indices.handle(), mirror.vertexBytes, mirror.indexBytes,
            mirror.geometryEpoch, mirror.geometryRevision, mirror.identity,
        )
        val activeMirror = when {
            D3D12RtBlas.activeMirrorId() == mirror.identity -> {
                val old = publishedMirrors
                publishedMirrors = mirror
                if (old != null && old !== mirror) old.close()
                mirror
            }
            canUsePublishedMirror(mirror, device, encoder.nativeHandle()) -> publishedMirrors
            else -> null
        } ?: return dispatchSkip("acceleration structure pending")
        val accelMicros = (System.nanoTime() - accelStart) / 1000L
        val accelView = D3D12RtBlas.view()
        if (accelView == 0L) return dispatchSkip("acceleration structure unavailable")
        historyInvalidation = history.begin(D3D12RtHistory.Snapshot(
            plan, camera.worldIdentity, device, D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO),
            activeMirror.geometryEpoch, D3D12RtBlas.sceneRevision(), activeMirror.materialRevision,
            RtBackend.DXR.ordinal,
            frameGeneration, camera.worldTime, camera.frameNanos, camera.position, camera.forward, camera.up,
            camera.transform.projection,
            jitter.enabled,
        ))
        if (historyInvalidation.reasons != 0) previousValid = false
        if (!writeScene(sceneTarget, camera, traceWidth, traceHeight, activeMirror.recordCount)) return false
        val traceSrvs = longArrayOf(
            activeMirror.records.handle(), activeMirror.vertices.handle(), activeMirror.indices.handle(),
            activeMirror.materials.handle(), activeMirror.quads.handle(), accelView,
        )
        val traceUavs = longArrayOf(frame.direct.handle(), frame.indirect.handle(), frame.aux.handle(),
            frame.specular.handle(), frame.specularGrazing.handle())
        if (!accelLogged) {
            accelLogged = true
            IllusionLogger.infoMessage("rt: DXR active (${D3D12RtBlas.instances()} instances)")
        }
        if (frameCbv == 0L) {
            // 常量缓冲没绑上时着色器拿到的全是 0：宁可不派发（画面留在原版），也不要写一屏黑。
            fail("frame constants are not bound")
            return false
        }
        val ok = D3D12RtDxr.dispatch(
            device, encoder.nativeHandle(), traceSrvs, traceUavs, frameCbv, frameCbvOffset,
            traceWidth, traceHeight)
        val traceMicros = (System.nanoTime() - accelStart) / 1000L - accelMicros
        // 这一帧慢到不像话时留一行分解：卡死前最后一条「慢帧」直接指出钱花在哪一段。
        if (accelMicros + traceMicros > SLOW_FRAME_MICROS) {
            IllusionLogger.infoMessage(
                "rt frame: slow accel=${accelMicros / 1000}ms trace=${traceMicros / 1000}ms " +
                    "records=${activeMirror.recordCount} inst=${D3D12RtBlas.instances()}"
            )
        }
        if (!ok) {
            fail("dispatch failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        if (!D3D12RtMaterialGuide.render(encoder, frame.aux, activeMirror.materials, frameCbv, frameCbvOffset)) {
            return false
        }
        val exposureAlbedo = D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO) ?: return false
        val exposureEmissive = D3D12RtGBuffer.texture(D3D12RtGBuffer.EMISSIVE) ?: return false
        val exposureSpecular = D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR) ?: return false
        val exposureReset = historyInvalidation.reasons and
            (D3D12RtHistory.InvalidationReason.GEOMETRY.mask or
                D3D12RtHistory.InvalidationReason.MATERIAL.mask).inv() != 0
        if (!D3D12RtExposure.render(encoder, frame.direct.handle(), frame.indirect.handle(),
                frame.specular.handle(), frame.specularGrazing.handle(), exposureAlbedo.handle(),
                exposureEmissive.handle(), exposureSpecular.handle(), frameCbv, frameCbvOffset,
                camera.frameNanos, exposureReset)) return false
        frameValid = true
        val tier = "dxr"
        D3D12RtStats.setTier(tier)
        D3D12RtStats.onTraceDispatched(traceWidth, traceHeight, activeMirror.recordCount)
        D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_TRACE)
        if (plan.denoise) {
            D3D12RtNrd.ensure(device, plan.renderWidth, plan.renderHeight)
        }
        // 降噪轨：辐亮度先搬成 2D 纹理，再交给 NRD 逐帧派发（见 docs/rt/README.md §23.5）。
        if (!denoiseFrame(encoder, frame, plan, frameCbv, frameCbvOffset)) {
            // 降噪没跑：这一段记 0，合光的耗时仍然从降噪那一点算起。
            D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_DENOISE)
        }
        val upscaleSucceeded = upscaleDenoisedFrame(encoder, plan, frameCbv, frameCbvOffset)
        temporalResolveReady = upscaleSucceeded && denoisedRadiance != null && !denoisedIsReblur &&
            traceWidth == plan.renderWidth && traceHeight == plan.renderHeight &&
            (plan.track == RtDenoise.RR || plan.superResolution)
        D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_SR)
        val count = dispatches + 1
        dispatches = count
        // 真值表按显示分辨率算，表变了才打一行（见 docs/rt/README.md §18）。
        D3D12RtPipelinePlan.note(plan)
        D3D12RtNrd.inspectOnce()
        lastTier = tier
        lastDispatch = "tier=$tier records=${activeMirror.recordCount} " +
            "inst=${D3D12RtBlas.instances()} trace=${traceWidth}x$traceHeight"
        if (count == 1L) {
            // 放在 lastDispatch 之后：这类报告的意义就是把「这一帧到底走了哪条路」带出去。
            D3D12RtTrace.reportPending()
        }
        // 规模变了或间隔到了才报：高帧率下按帧报会把 INFO 刷成每帧一行。
        // 设备移除时最后一条心跳就是卡住那一刻的规模（记录数、三角数、追踪尺寸）。
        val now = System.nanoTime()
        val scaleChanged = activeMirror.recordCount != heartbeatRecords || tier != heartbeatTier
        val cooled = now - heartbeatNanos >= HEARTBEAT_MILLIS * 1_000_000L
        if (count == 1L || cooled || (scaleChanged && now - heartbeatNanos >= 1_000_000_000L)) {
            heartbeatNanos = now
            heartbeatRecords = activeMirror.recordCount
            heartbeatTier = tier
            IllusionLogger.infoMessage("rt frame: dispatch ${traceWidth}x$traceHeight " +
                "of ${width}x$height records=${activeMirror.recordCount} tier=$tier " +
                "inst=${D3D12RtBlas.instances()} " +
                "cam=${camera.position.x.toInt()},${camera.position.y.toInt()},${camera.position.z.toInt()} " +
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

    /** 最近一次派发用的档位与实例数：自检报告里带出去，省得再跑一次看日志。 */
    private var lastDispatch = "none"

    /** 最近一次真正派发走的档：报告里的 `tier` 要用它，而不是「配置里写着什么」。 */
    private var lastTier: String? = null

    fun lastDispatchTier(): String? = lastTier

    fun lastDispatchSummary(): String = lastDispatch

    private fun dispatchSkip(reason: String): Boolean {
        if (dispatchSkips.add(reason)) IllusionLogger.warnMessage("rt dispatch: skipped, $reason")
        // 这一帧没有产出：合光必须停手，否则会把上一张旧图按 1:1 铺到主目标上。
        frameValid = false
        return false
    }

    private val compositeSkips = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun compositeSkip(reason: String): D3D12GpuTexture? {
        if (compositeSkips.add(reason)) {
            IllusionLogger.warnMessage("rt composite: skipped, $reason")
        }
        return null
    }

    @JvmOverloads
    fun composite(
        encoder: D3D12CommandEncoderBackend?,
        albedo: D3D12GpuTexture?,
        emissive: D3D12GpuTexture?,
        source: D3D12GpuTexture?,
        debugMode: Int = 0,
    ): D3D12GpuTexture? = synchronized(frameLock) {
        if (encoder == null) return compositeSkip("no encoder")
        if (albedo == null || emissive == null || source == null) return compositeSkip("missing inputs")
        val started = System.nanoTime()
        val device = encoder.device ?: return compositeSkip("no device")
        val frame = radiance ?: return compositeSkip("no radiance targets")
        // 这一帧没有派发产出就不能上屏：窗口改过尺寸时，旧图会被按 1:1 糊到左上角（象是裁剪+缩放）。
        if (!frameValid) return compositeSkip("no dispatched frame")
        // 输出域取**主目标尺寸**：主目标可能是窗口尺寸而 G-Buffer 是渲染尺寸，两者不同时
        // 按 G-Buffer 出图再 1:1 blit 只会盖住左上角。取样比例由着色器按各自 GetDimensions 算。
        val width = source.getWidth(0)
        val height = source.getHeight(0)
        if (width <= 0 || height <= 0) return compositeSkip("empty target")
        val pipeline = ensureCompositePipeline() ?: return compositeSkip("no pipeline")
        val target = ensureOutput(device, width, height) ?: return compositeSkip("no output target")
        encoder.ensureListOpen()
        // 降噪轨：上一帧内 NRD 写好的那张（没有就把开关置 0，画面退回直接读三块缓冲）。
        val denoised = denoisedRadiance
        // 降噪没跑时第七槽留着不用（开关是 0），绑一张同尺寸的占位纹理免得堆里出现空描述符。
        val denoisedView = denoised ?: ensureFallback(device) ?: return compositeSkip("no denoised fallback")
        val denoisedSpecularView = denoisedSpecular ?: denoisedView
        val specularMaterial = D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR)
            ?: return compositeSkip("no specular material")
        val exposure = D3D12RtExposure.texture() ?: return compositeSkip("no exposure")
        val compositeSrvs = longArrayOf(
            albedo.handle(), frame.direct.handle(), emissive.handle(), frame.indirect.handle(),
            source.handle(), frame.aux.handle(),
            denoisedView.handle(),
            frame.specular.handle(), frame.specularGrazing.handle(), specularMaterial.handle(),
            denoisedSpecularView.handle(),
            exposure.handle(),
        )
        val compositeUavs = longArrayOf(target.handle())
        // 调试图档位读的是 **g_resolvePad.y**（合光里五个分支），塞进 .x 等于一档都不生效。
        val constants = intArrayOf(
            if (denoised == null) 0 else if (denoisedIsReblur) 2 else 1,
            0,
            0,
            debugMode,
            slConstants[68].toRawBits(),
            slConstants[69].toRawBits(),
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
        // 一次成功就能证明合光真的跑过：没有这行而画面仍是原版，问题就在上屏而不是合光。
        if (compositeRan.compareAndSet(false, true)) {
            val flag = if (denoised != null) 1 else 0
            IllusionLogger.infoMessage("rt composite: ${width}x${height} mode=$debugMode denoise=$flag")
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
        width: Int,
        height: Int,
    ): D3D12GpuTexture? = synchronized(frameLock) {
        // `channel` 0..3 = 取单个通道铺灰；4 = 法线档（0.5 + 0.5 * rgb），见 D3D12RtDebugShader。
        if (encoder == null || source == null || channel !in 0..4) return null
        val started = System.nanoTime()
        val device = encoder.device ?: return null
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
        current?.close()
        IllusionLogger.infoMessage("rt: hdr ${width}x$height")
        return created
    }

    private fun radianceFor(device: D3D12Device, width: Int, height: Int): Radiance? {
        val current = radiance
        if (current != null && current.width == width && current.height == height) return current
        val bytes = (width.toLong() * height * 16L).coerceAtLeast(MIN_BYTES)
        val allocated = mutableListOf<D3D12GpuBuffer>()
        fun allocate(name: String, planes: Long = 1L): D3D12GpuBuffer =
            uav(device, name, bytes * planes).also { allocated += it }
        val created = try {
            Radiance(
                width, height,
                allocate("rt-direct"),
                allocate("rt-indirect"),
                allocate("rt-aux", 5L),
                allocate("rt-specular"),
                allocate("rt-specular-grazing"),
                bytes * 9L,
            )
        } catch (t: Throwable) {
            allocated.forEach { it.close() }
            fail("radiance allocate failed: ${t.message}")
            return null
        }
        radiance = created
        current?.close()
        return created
    }

    private fun uav(device: D3D12Device, label: String, bytes: Long): D3D12GpuBuffer =
        device.createBuffer(Supplier { label }, UAV_USAGE, bytes) as? D3D12GpuBuffer
            ?: throw IllegalStateException("$label allocate failed (${MCDR_ABI.D3D12LastError()})")

    private fun mirrorsFor(device: D3D12Device, camera: Camera): Mirrors? {
        frameCounter++
        val geometryEpoch = observeWorld(camera.worldIdentity)
        val geometryRevision = D3D12RtGeometryPool.revision()
        val materialRevision = D3D12RtMaterialTable.revision()
        val revisionKey = geometryRevision * 1_000_003L + materialRevision
        val current = mirrors?.takeIf {
            it.worldIdentity === camera.worldIdentity && it.geometryEpoch == geometryEpoch
        }
        if (current == null && mirrors != null) {
            discardMirrors()
            geometryRetriggered = false
        }
        val moved = abs(camera.position.x - mirrorX) + abs(camera.position.y - mirrorY) +
            abs(camera.position.z - mirrorZ) > MIRROR_SLACK
        if (current != null && current.key == revisionKey && !moved) return current
        // 只镜像相机附近的一小片：整池上传在世界加载时会每帧传几百 MB，直接把画面卡死。
        val slice = D3D12RtGeometryPool.mirrorNear(
            camera.position.x, camera.position.y, camera.position.z,
            TRACE_RADIUS + MIRROR_SLACK, MIRROR_RECORDS, MIRROR_BYTES,
        ) ?: run {
            discardMirrors()
            return null
        }
        if (slice.geometryEpoch != geometryEpoch) return null
        mirrorX = camera.position.x
        mirrorY = camera.position.y
        mirrorZ = camera.position.z
        if (current != null && current.geometrySnapshot === slice &&
            current.materialRevision == materialRevision) {
            current.key = revisionKey
            return current
        }
        val materials = D3D12RtMaterialTable.entryBytes()
        if (materials.remaining() <= 0) return null
        val key = revisionKey
        val startedAt = System.nanoTime()
        // 加速结构要用同一份记录的 CPU 字节，先留一份副本：upload() 会消费掉原缓冲的位置。
        val recordBytes = slice.records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val created = try {
            Mirrors(
                identity = ++nextMirrorIdentity,
                geometrySnapshot = slice,
                worldIdentity = camera.worldIdentity,
                geometryEpoch = slice.geometryEpoch,
                key = key,
                geometryRevision = slice.geometryRevision,
                materialRevision = materialRevision,
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
        if (previous != null && previous !== publishedMirrors) {
            previous.close()
        }
        revDropped++
        val line = "RT mirror: ${slice.recordCount} records (${elapsedNanos / 1_000_000} ms)"
        if (revDropped == 1) IllusionLogger.infoMessage(line) else IllusionLogger.debugMessage(line)
        return created
    }

    private fun observeWorld(worldIdentity: Any?): Int = synchronized(frameLock) {
        D3D12SectionGeometry.observeLevel(worldIdentity)
        val epoch = D3D12SectionGeometry.epoch()
        if (observedGeometryEpoch != epoch) {
            observedGeometryEpoch = epoch
            geometryRetriggered = false
            starvedTicks = 0
            starvedReported = false
            frameValid = false
            discardMirrors()
            history.invalidate(D3D12RtHistory.InvalidationReason.WORLD)
        }
        epoch
    }

    private fun discardMirrors() {
        temporalResolveReady = false
        D3D12RtBlas.invalidateGeometry()
        val current = mirrors
        val published = publishedMirrors
        mirrors = null
        publishedMirrors = null
        current?.close()
        if (published != null && published !== current) {
            published.close()
        }
        previousValid = false
    }

    private fun canUsePublishedMirror(current: Mirrors, device: D3D12Device, ctx: Long): Boolean {
        val published = publishedMirrors ?: return false
        if (published.worldIdentity !== current.worldIdentity ||
            published.geometryEpoch != current.geometryEpoch ||
            D3D12RtBlas.activeMirrorId() != published.identity
        ) {
            return false
        }
        val changed = D3D12RtGeometryPool.changedSections(published.recordBytes, current.recordBytes)
        return D3D12RtBlas.filterPublished(device, ctx, published.recordBytes, published.recordCount, changed)
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
        plan: D3D12RtPipelinePlan.Plan,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        denoisedRadiance = null
        denoisedSpecular = null
        denoisedIsReblur = false
        if (!plan.denoise) {
            return false
        }
        val width = plan.renderWidth
        val height = plan.renderHeight
        val albedo = D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO) ?: return evaluationFailure()
        val emissive = D3D12RtGBuffer.texture(D3D12RtGBuffer.EMISSIVE) ?: return evaluationFailure()
        val specularMaterial = D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR) ?: return evaluationFailure()
        val rawNormalRoughness = D3D12RtGBuffer.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS) ?: return evaluationFailure()
        val sourceDepth = D3D12RtGBuffer.texture(D3D12RtGBuffer.LINEAR_DEPTH) ?: return evaluationFailure()
        val motion = D3D12RtMotion.render(
            encoder, sourceDepth, cbv, cbvOffset,
            slConstants[68] - slConstants[70], slConstants[69] - slConstants[71],
        ) ?: run {
            return evaluationFailure()
        }
        if (!D3D12RtNrdPack.render(encoder, rawNormalRoughness, sourceDepth, cbv, cbvOffset)) {
            return evaluationFailure()
        }
        val normalRoughness = D3D12RtNrdPack.normalRoughnessTexture() ?: return evaluationFailure()
        val viewZ = D3D12RtNrdPack.viewZTexture() ?: return evaluationFailure()
        D3D12RtGuide.noteOnce()
        if (!D3D12RtNrdRadiance.render(
                encoder, frame.direct.handle(), frame.indirect.handle(), frame.aux.handle(),
                frame.specular.handle(), frame.specularGrazing.handle(),
                viewZ.handle(), albedo.handle(), emissive.handle(), specularMaterial.handle(), rawNormalRoughness.handle(),
                plan.track != RtDenoise.RR,
                width, height, cbv, cbvOffset)) {
            return evaluationFailure()
        }
        val radianceIn = D3D12RtNrdRadiance.radianceTexture() ?: return evaluationFailure()
        val radianceOut = D3D12RtNrdRadiance.outputTexture() ?: return evaluationFailure()
        val specularIn = D3D12RtNrdRadiance.specularTexture() ?: return evaluationFailure()
        val specularOut = D3D12RtNrdRadiance.specularOutputTexture() ?: return evaluationFailure()
        if (plan.track == RtDenoise.RR) {
            val normal = D3D12RtGBuffer.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS)
            val specular = D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR)
            val specularHitDistance = D3D12RtNrdRadiance.specularHitDistanceTexture()
            val rrOutput = encoder.device?.let {
                D3D12RtNrdRadiance.rrOutputTexture(it, plan.displayWidth, plan.displayHeight)
            }
            if (normal != null && specular != null && specularHitDistance != null && rrOutput != null) {
                val accepted = D3D12RtDlssd.evaluate(
                    encoder.nativeHandle(),
                    radianceIn.handle(),
                    rrOutput.handle(),
                    viewZ.handle(),
                    motion.handle(),
                    normal.handle(),
                    albedo.handle(),
                    specular.handle(),
                    specularHitDistance.handle(),
                    width,
                    height,
                    plan.displayWidth,
                    plan.displayHeight,
                    slConstants,
                    rrHistory.requiresReset(historyInvalidation),
                    D3D12RtExposure.texture()?.handle() ?: return evaluationFailure(),
                )
                if (accepted > 0) {
                    rrHistory.accept(historyInvalidation)
                    D3D12RtStats.onDispatches(accepted)
                    D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_DENOISE)
                    denoisedRadiance = rrOutput
                    return true
                }
            }
            if (!D3D12RtNrdRadiance.render(
                    encoder, frame.direct.handle(), frame.indirect.handle(), frame.aux.handle(),
                    frame.specular.handle(), frame.specularGrazing.handle(),
                    viewZ.handle(), albedo.handle(), emissive.handle(), specularMaterial.handle(), rawNormalRoughness.handle(),
                    true, width, height, cbv, cbvOffset)) {
                return evaluationFailure()
            }
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
                specularIn.handle(),
                specularOut.handle(),
                segment,
                floatArrayOf(-slConstants[68], -slConstants[69], -slConstants[70], -slConstants[71]),
                frameCounter.toInt(),
                nrdHistory.requiresReset(historyInvalidation),
            )
        }
        if (ok <= 0) {
            return evaluationFailure()
        }
        nrdHistory.accept(historyInvalidation)
        D3D12RtStats.onDispatches(ok)
        D3D12RtStats.mark(encoder, D3D12RtStats.SLOT_DENOISE)
        denoisedRadiance = radianceOut
        denoisedSpecular = specularOut
        denoisedIsReblur = true
        return true
    }

    private fun evaluationFailure(): Boolean {
        denoisedRadiance = null
        denoisedSpecular = null
        denoisedIsReblur = false
        history.invalidate(D3D12RtHistory.InvalidationReason.EVALUATION_FAILURE)
        return false
    }

    private fun upscaleDenoisedFrame(
        encoder: D3D12CommandEncoderBackend,
        plan: D3D12RtPipelinePlan.Plan,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        if (!plan.superResolution || !denoisedIsReblur) return true
        val denoised = denoisedRadiance ?: return superResolutionFailure()
        val specular = denoisedSpecular ?: return superResolutionFailure()
        val albedo = D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO) ?: return superResolutionFailure()
        val specularMaterial = D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR) ?: return superResolutionFailure()
        val emissive = D3D12RtGBuffer.texture(D3D12RtGBuffer.EMISSIVE) ?: return superResolutionFailure()
        val depth = D3D12RtNrdPack.viewZTexture() ?: return superResolutionFailure()
        val motion = D3D12RtMotion.texture() ?: return superResolutionFailure()
        val reconstructed = D3D12RtSuperResolution.reconstruct(
            encoder, denoised, specular, albedo, specularMaterial, emissive, depth, motion, plan, slConstants, cbv, cbvOffset,
            srHistory.requiresReset(historyInvalidation),
            D3D12RtExposure.texture()?.handle() ?: return superResolutionFailure(),
        ) ?: return superResolutionFailure()
        srHistory.accept(historyInvalidation)
        denoisedRadiance = reconstructed
        denoisedSpecular = null
        denoisedIsReblur = false
        return true
    }

    private fun superResolutionFailure(): Boolean {
        history.invalidate(D3D12RtHistory.InvalidationReason.EVALUATION_FAILURE)
        return false
    }

    private fun writeScene(
        target: Scene,
        camera: Camera,
        traceWidth: Int,
        traceHeight: Int,
        recordCount: Int,
    ): Boolean {
        val jitter = D3D12Temporal.frameJitter(camera.frameNanos)
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
                    viewMatrix = camera.transform.view,
                    projectionMatrix = camera.transform.projection,
                    farPlane = camera.farPlane,
                    jitterX = jitter.x.toFloat(),
                    jitterY = jitter.y.toFloat(),
                    previousJitterX = if (previousValid) previousJitter[0] else jitter.x.toFloat(),
                    previousJitterY = if (previousValid) previousJitter[1] else jitter.y.toFloat(),
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
        previousJitter[0] = jitter.x.toFloat()
        previousJitter[1] = jitter.y.toFloat()
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

    private val SUN_COLOR = RtVec3(1f, 0.96f, 0.9f)

    /** 太阳辐亮度强度（进 `g_sunRadiance.w`）：与天空走同一套 HDR 出口，1.0 时接近原版观感。 */
    private const val SUN_STRENGTH = 1f
}




