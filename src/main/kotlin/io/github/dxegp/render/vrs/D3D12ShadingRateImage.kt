package io.github.dxegp.render.vrs

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.config.IllusionConfig
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.temporal.D3D12Velocity
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.render.frameprep.FrameInputCoordinates
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import net.minecraft.client.Minecraft
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Tier 2 的屏幕空间着色率图像（SRI）：每帧开画前跑一次 Work Graph，读上一帧深度与运动矢量，按硬件 tile 判定
 * 是否降采样并写入 `R8_UINT` 着率图，地形批次绑到命令列表（`RSSetShadingRateImage`）。任何一步失败只记一次
 * 日志并永久自关。见 docs/mesh&vrs/README.md §3.3。
 */
object D3D12ShadingRateImage {
    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-vrs-sri")

    /** 运行时 tile 尺寸（NVIDIA 为 16×16；能力查询里拿到的就是这个值）。 */
    private fun tileSizeValue(): Int = D3D12AdapterInfo.current?.shadingRateTileSize?.takeIf { it > 0 } ?: 0

    /** 深度快照 usage：SRV + COPY_DST + COPY_SRC，必须写成一行（工具链不接受以 `or` 开头的续行）。 */
    private val DEPTH_USAGE: Int = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC

    /** 深度边沿阈值（千分比）：四角深度相对差超过它即判为几何边沿。 */
    private const val EDGE_PERMILLE: Int = 50

    /** 运动矢量阈值（像素）：超过它即判为"正在动"，不让它变粗。 */
    private const val MOTION_PIXELS: Int = 8

    /**
     * 着色率图像（两张 ping-pong）：Work Graph 不能同时读写同一张纹理，每帧写 A 读 B。
     */
    private val images: LongArray = LongArray(2)
    private val candidates: LongArray = LongArray(2)

    /** 本帧写入下标；[lastWritten] 是上一帧写完那张（= 本帧的 `t2`，也是要绑的那张）。 */
    private var writeIndex: Int = 0
    private var lastWritten: Int = -1

    /** 上一帧的 SRI 是否仍有效（强制 1X1 的帧必须清掉，否则下一帧 `min(x, 0)` 再糊一帧）。 */
    private var historyValid: Boolean = false
    private var depthTex: Long = 0L
    private var owner: D3D12CommandEncoderBackend? = null
    private var capturedWorld: Any? = null
    private var capturedGeneration = -1L
    private var capturedProjection: VrsHistoryProjection? = null
    private var capturedMotion = 0L
    private var capturedWorldEpoch = 0L
    private var capturedRealFrameId = 0L
    private var capturedResourceVersion = 0L
    private var sourceVersion = 0L
    private var capturedDepth: D3D12GpuTexture? = null
    private var depthFormat: GpuFormat? = null
    private var tilesX: Int = 0
    private var tilesY: Int = 0
    private var pixelW: Int = 0
    private var pixelH: Int = 0

    private var ready: Boolean = false
    private var readyWorld: Any? = null
    private var readySequence = -1L
    private var readyGeneration = -1L
    private var readyFrameTime = Long.MIN_VALUE
    private var readyWorldEpoch = 0L
    private var readyRealFrameId = 0L
    private var readyResourceVersion = 0L
    private var readySourceVersion = 0L

    /** 一次性失败闩锁：置上之后永久退回逐图元档位。 */
    private var failed: Boolean = false
    private var lastNote: String? = null
    private var frames: Long = 0L


    /** 本帧强制全 1X1：相机跳变或世界切换后的那一帧。 */
    private var forceFine: Boolean = false

    /** 一帧内相机位移超过它（方块）就判为跳变 —— `/tp`、重生、传送门都远超这个量级。 */
    private const val CAMERA_JUMP_BLOCKS: Double = 8.0

    /** 一帧内视角转角超过它（度）也算跳变（相机路径跳帧、视角瞬间切换）。 */
    private const val CAMERA_JUMP_DEGREES: Float = 30.0f

    @JvmStatic
    fun frames(): Long = frames

    /** 本帧是否可用（`prepareFrame` 成功过、且没有失败闩锁）。 */
    @JvmStatic
    fun isReady(): Boolean {
        val frame = VanillaFrameCoordinator.currentFrame() ?: return false
        val identity = frame.identity
        return ready && !failed && sriEnabled() &&
            readyWorld === frame.world && readySequence == identity.realFrameId &&
            readyGeneration == frame.historyGeneration && readyFrameTime == frame.frameTimeNanos &&
            readyWorldEpoch == identity.worldEpoch && readyRealFrameId == identity.realFrameId &&
            readyResourceVersion == identity.resourceVersion && readySourceVersion == sourceVersion
    }

    @JvmStatic
    fun tileSize(): String = if (tilesX <= 0) "none" else
        tilesX.toString() + "x" + tilesY + "@" + pixelW + "x" + pixelH + " tile=" + tileSizeValue()

    @JvmStatic
    @Synchronized
    fun captureWorldDepth(encoder: D3D12CommandEncoderBackend) {
        if (!sriEnabled() || !D3D12VariableShadingRate.isEnabled()) {
            capturedWorld = null
            return
        }
        val minecraft = Minecraft.getInstance()
        val frame = VanillaFrameCoordinator.currentFrame() ?: return
        val world = frame.world
        val identity = frame.identity
        val depth = minecraft.gameRenderer.mainRenderTarget().depthTexture as? D3D12GpuTexture ?: return
        if (depth.isClosed()) return
        if (depth.getWidth(0) != identity.width || depth.getHeight(0) != identity.height) return
        if (capturedWorld === world && capturedWorldEpoch == identity.worldEpoch &&
            capturedRealFrameId == identity.realFrameId &&
            capturedGeneration == frame.historyGeneration &&
            capturedResourceVersion == identity.resourceVersion && capturedDepth === depth) return
        capturedWorld = null
        if (owner == null) {
            encoder.retainUntilCommandsClosed(AutoCloseable { reset(); owner = null })
            owner = encoder
        }
        check(owner === encoder) { "VRS depth capture changed command owner" }
        if (depthFormat != depth.format) {
            releaseImage()
            depthFormat = depth.format
        }
        if (!ensureResources(depth.getWidth(0), depth.getHeight(0))) return
        encoder.recordCommands { ctx ->
            VanillaFrameCoordinator.declaredPass(ctx, VanillaFrameInputResources.VRS_DEPTH_CAPTURE_PASS,
                VanillaFrameInputResources.textureCopy(depth.handle(), depthTex, identity.resourceVersion),
                graphicsEffects = 0) {
                MCDR_ABI.D3D12CopyTextureToTexture(ctx, depth.handle(), depthTex,
                    0, 0, 0, 0, 0, 0, pixelW, pixelH)
            }
        }
        capturedWorld = world
        capturedWorldEpoch = identity.worldEpoch
        capturedRealFrameId = identity.realFrameId
        capturedResourceVersion = identity.resourceVersion
        sourceVersion += 1L
        capturedDepth = depth
        capturedGeneration = frame.historyGeneration
        capturedProjection = VrsHistoryProjection.capture(frame.current)
        capturedMotion = (D3D12Velocity.currentMotionView()?.texture() as? D3D12GpuTexture)?.handle() ?: 0L
    }

    internal fun requiresFine(): Boolean = sriEnabled() && !isReady()

    /** 在 world BeginFrame 冻结身份后、地形绘制前准备，读取兼容的上一真实帧深度快照。 */
    @JvmStatic
    @Synchronized
    fun prepareFrame() {
        if (isReady()) return
        ready = false
        val frame = VanillaFrameCoordinator.currentFrame() ?: return
        val identity = frame.identity
        if (failed) {
            return
        }
        if (!D3D12VariableShadingRate.isEnabled()) {
            historyValid = false
            note("AUTO is off or this device refused it; SRI stays idle")
            return
        }
        if (tileSizeValue() <= 0) {
            note("device did not report a VRS image tile size; SRI stays off this frame")
            return
        }
        if (!sriEnabled()) {
            historyValid = false
            capturedWorld = null
            note("`[vrs] sri` is off; falling back to the per-primitive distance bands")
            return
        }
        val minecraft = Minecraft.getInstance()
        val target = minecraft.gameRenderer.mainRenderTarget()
        val depth = target.depthTexture as? D3D12GpuTexture
        if (depth == null || depth.isClosed()) {
            note("the main render target has no D3D12 depth texture; SRI stays off this frame")
            return
        }
        val w = depth.getWidth(0)
        val h = depth.getHeight(0)
        if (w != identity.width || h != identity.height) return
        if (w <= 0 || h <= 0) {
            return
        }
        val cameraJumped = detectCameraJump()
        val validCapture = frame.previous != null && capturedDepth === depth && sourceVersion > 0L && depthFormat == depth.format &&
            pixelW == w && pixelH == h &&
            FrameInputCoordinates.previousFrameCompatible(
                capturedRealFrameId, identity.realFrameId, capturedWorldEpoch, identity.worldEpoch,
                capturedResourceVersion, identity.resourceVersion,
                capturedGeneration, frame.historyGeneration, capturedWorld, frame.world)
        val currentProjection = VrsHistoryProjection.capture(frame.current)
        val mapping = currentProjection?.let { capturedProjection?.mapTo(it) }
        if (forceFine || cameraJumped || !validCapture || mapping == null) {
            historyValid = false
            val reason = when {
                forceFine -> "history reset"
                cameraJumped -> "camera cut"
                !validCapture -> "no depth capture from the immediately preceding real-world frame"
                else -> "camera projection cannot map previous depth into this frame"
            }
            forceFine = false
            note("$reason; terrain stays 1X1")
            return
        }
        val previousMotion = D3D12Velocity.previousMotionView()
        val previousTexture = previousMotion?.texture() as? D3D12GpuTexture
        val motion = if (previousTexture != null && !previousTexture.isClosed() &&
            previousTexture.getWidth(0) == w && previousTexture.getHeight(0) == h &&
            previousTexture.handle() == capturedMotion) capturedMotion else 0L
        val motionPixels = if (motion == 0L) Int.MAX_VALUE else motionPixels()
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        check(owner === encoder) { "VRS preparation changed command owner" }
        try {
            // 上一帧 submit 之后这条列表就关掉了 —— 每帧都要重新开一条。
            val clampBit = if (temporalClampEnabled()) 2 else 0
            val historyBit = if (historyValid) 8 else 0
            val compatibleBit = 1
            val motionBit = if (motion != 0L) 4 else 0
            val dispatched = encoder.recordCommands { ctx ->
                val uses = buildList {
                    add(VanillaFrameInputResources.texture(depthTex, identity.resourceVersion, 1,
                        VanillaFrameInputResources.STATE_NON_PIXEL_SHADER_RESOURCE))
                    if (motionBit != 0) add(VanillaFrameInputResources.texture(motion, identity.resourceVersion, 1,
                        VanillaFrameInputResources.STATE_NON_PIXEL_SHADER_RESOURCE))
                    if (historyBit != 0) add(VanillaFrameInputResources.texture(
                        candidates[1 - writeIndex], identity.resourceVersion, 1,
                        VanillaFrameInputResources.STATE_NON_PIXEL_SHADER_RESOURCE))
                    add(VanillaFrameInputResources.texture(images[writeIndex], identity.resourceVersion, 2,
                        VanillaFrameInputResources.STATE_UNORDERED_ACCESS,
                        VanillaFrameInputResources.STATE_COMMON))
                    add(VanillaFrameInputResources.texture(candidates[writeIndex], identity.resourceVersion, 2,
                        VanillaFrameInputResources.STATE_UNORDERED_ACCESS,
                        VanillaFrameInputResources.STATE_NON_PIXEL_SHADER_RESOURCE))
                }
                VanillaFrameCoordinator.declaredPass(ctx, VanillaFrameInputResources.VRS_INPUT_PASS, uses) {
                    MCDR_ABI.D3D12ProduceVrs(ctx, depthTex,
                        motion, if (historyValid) candidates[1 - writeIndex] else 0L,
                        images[writeIndex], w, h, tileSizeValue(),
                        edgePermille(), motionPixels, compatibleBit or clampBit or motionBit or historyBit,
                        identity.worldEpoch, identity.realFrameId, sourceVersion,
                        identity.resourceVersion, candidates[writeIndex],
                        mapping.previousToCurrent, mapping.currentToPrevious)
                }
            }
            if (!dispatched) {
                fail("dx12DispatchComputeShadingRate failed: " + MCDR_ABI.D3D12LastError())
                return
            }
            lastWritten = writeIndex
            writeIndex = 1 - writeIndex
            historyValid = true
            ready = true
            readyWorld = minecraft.level
            readySequence = identity.realFrameId
            readyGeneration = frame.historyGeneration
            readyFrameTime = frame.frameTimeNanos
            readyWorldEpoch = identity.worldEpoch
            readyRealFrameId = identity.realFrameId
            readyResourceVersion = identity.resourceVersion
            readySourceVersion = sourceVersion
            frames++
            note(if (motion == 0L) "previous depth reprojected; SRI runs depth-only (no matching previous motion)"
                else "previous depth and motion reprojected; SRI generated")
            if (frames == 1L) {
                LOGGER.info("SRI: first frame generated ({}x{} tiles for {}x{})", tilesX, tilesY, w, h)
            }
        } catch (t: Throwable) {
            fail("SRI prepare threw: " + t)
            throw t
        }
    }

    /**
     * 世界 / 维度切换时把下一张图像强制成全 1X1；只置标志由下一帧 [prepareFrame] 消费（调用方不一定在渲染线程上）。
     */
    @JvmStatic
    fun requestForceFine(reason: String) {
        forceFine = true
        historyValid = false
        ready = false
        capturedWorld = null
        note("$reason; terrain stays 1X1 until a compatible previous frame is captured")
    }

    /** 比较本帧与上一帧的相机判断跳变；没有上一帧时不判，那由 [requestForceFine] 负责。 */
    private fun detectCameraJump(): Boolean {
        val frame = VanillaFrameCoordinator.currentFrame() ?: return true
        val camera = frame.current
        val previous = frame.previous ?: return true
        val yaw = camera.yaw
        val pitch = camera.pitch
        val dx = camera.originX - previous.originX
        val dy = camera.originY - previous.originY
        val dz = camera.originZ - previous.originZ
        val moved = dx * dx + dy * dy + dz * dz
        val turned = yawDelta(yaw, previous.yaw).coerceAtLeast(kotlin.math.abs(pitch - previous.pitch))
        val jumped =
            (moved > CAMERA_JUMP_BLOCKS * CAMERA_JUMP_BLOCKS || turned > CAMERA_JUMP_DEGREES)
        return jumped
    }

    /** 偏航角差（度），按最短方向折算到 0..180 —— 跨越 ±360 时不会误判成跳变。 */
    private fun yawDelta(current: Float, previous: Float): Float {
        var d = (current - previous) % 360.0f
        if (d > 180.0f) {
            d -= 360.0f
        } else if (d < -180.0f) {
            d += 360.0f
        }
        return kotlin.math.abs(d)
    }

    /**
     * 把着率图像绑到当前命令列表；返回 true 时必须配对调用 [unbind]。
     */
    @JvmStatic
    @Synchronized
    fun bind(ctx: Long): Boolean {
        val img = if (lastWritten >= 0) images[lastWritten] else 0L
        if (!isReady() || img == 0L) {
            return false
        }
        val bound = MCDR_ABI.D3D12SetShadingRateImage(ctx, img)
        if (!bound) {
            note("dx12SetShadingRateImage(bind) refused: " + MCDR_ABI.D3D12LastError())
        }
        return bound
    }

    /** 解绑（native 会把图像转回 COMMON，不留下 `SHADING_RATE_SOURCE` 残状态）。 */
    @JvmStatic
    @Synchronized
    fun unbind(ctx: Long) {
        if (lastWritten >= 0 && images[lastWritten] != 0L) {
            MCDR_ABI.D3D12SetShadingRateImage(ctx, 0L)
        }
    }

    /** 释放全部资源（关设备/退出世界时调用）。 */
    @JvmStatic
    @Synchronized
    fun reset() {
        releaseResources()
        ready = false
        frames = 0L
        forceFine = false
        historyValid = false
        lastWritten = -1
        writeIndex = 0
        capturedWorld = null
        capturedProjection = null
        capturedMotion = 0L
        capturedDepth = null
        readyWorldEpoch = 0L
        readyRealFrameId = 0L
        readyResourceVersion = 0L
        readySourceVersion = 0L
    }

    /** 只把帧计数清零，**不碰 GPU 资源**：钩子不一定在渲染线程上，不能像 [reset] 那样销毁纹理。 */
    @JvmStatic
    fun resetCounters() {
        frames = 0L
    }

    private fun ensureResources(w: Int, h: Int): Boolean {
        val tile = tileSizeValue()
        if (tile <= 0) return false
        val tx = (w + tile - 1) / tile
        val ty = (h + tile - 1) / tile
        if (images.all { it != 0L } && candidates.all { it != 0L } &&
            tx == tilesX && ty == tilesY && w == pixelW && h == pixelH) {
            return true
        }
        // 只重建跟尺寸绑定的两张图与深度快照；Work Graph 状态由 native worker 持有。
        releaseImage()
        for (i in 0..1) {
            val img = MCDR_ABI.D3D12CreateShadingRateImage(tx, ty)
            if (img == 0L) {
                releaseImage()
                fail("dx12CreateShadingRateImage($tx,$ty) failed: " + MCDR_ABI.D3D12LastError())
                return false
            }
            images[i] = img
            val candidate = MCDR_ABI.D3D12CreateShadingRateImage(tx, ty)
            if (candidate == 0L) {
                releaseImage()
                fail("VRS candidate history creation failed: " + MCDR_ABI.D3D12LastError())
                return false
            }
            candidates[i] = candidate
        }
        // 快照必须与主深度**同格式**（D32_FLOAT）：CopyTextureToTexture 不做格式转换，`R32_FLOAT` 会报 "format mismatch"。
        val tex = MCDR_ABI.ID3D12CreateTexture(DEPTH_USAGE, checkNotNull(depthFormat).ordinal, w, h, 1, 1)
        if (tex == 0L) {
            releaseImage()
            fail("depth snapshot texture creation failed: " + MCDR_ABI.D3D12LastError())
            return false
        }
        depthTex = tex
        tilesX = tx
        tilesY = ty
        pixelW = w
        pixelH = h
        writeIndex = 0
        lastWritten = -1
        historyValid = false
        return true
    }

    /** 只放跟分辨率绑定的资源（两张图像 + 深度快照），PSO 与编码器留着。 */
    private fun releaseImage() {
        ready = false
        capturedWorld = null
        if (depthTex != 0L) {
            MCDR_ABI.ID3D12DestroyResource(depthTex)
            depthTex = 0L
        }
        for (i in 0..1) {
            if (images[i] != 0L) {
                MCDR_ABI.ID3D12DestroyResource(images[i])
                images[i] = 0L
            }
            if (candidates[i] != 0L) {
                MCDR_ABI.ID3D12DestroyResource(candidates[i])
                candidates[i] = 0L
            }
        }
        lastWritten = -1
        writeIndex = 0
        historyValid = false
        tilesX = 0
        tilesY = 0
    }

    private fun releaseResources() {
        releaseImage()
    }

    private fun note(message: String) {
        if (lastNote == message) {
            return
        }
        lastNote = message
        LOGGER.info("SRI: {}", message)
    }

    /** `[vrs]` 的开关每帧现读 —— 改 TOML 下一帧生效，不需要重启。 */
    private fun sriEnabled(): Boolean {
        if (!IllusionConfig.SPEC.isLoaded) return true
        return IllusionConfig.VRS_SRI.get()
    }

    private fun edgePermille(): Int {
        if (!IllusionConfig.SPEC.isLoaded) return EDGE_PERMILLE
        return IllusionConfig.VRS_SRI_EDGE_PERMILLE.get().coerceIn(0, 1000)
    }

    private fun motionPixels(): Int {
        if (!IllusionConfig.SPEC.isLoaded) return MOTION_PIXELS
        return IllusionConfig.VRS_SRI_MOTION_PIXELS.get().coerceIn(0, 256)
    }

    /** `[vrs] sriTemporalClamp` —— 每帧现读；关掉等于回到无历史的逐帧行为。 */
    private fun temporalClampEnabled(): Boolean {
        if (!IllusionConfig.SPEC.isLoaded) return true
        return IllusionConfig.VRS_SRI_TEMPORAL_CLAMP.get()
    }

    /** F3 诊断用：这一帧的钳制位到底有没有生效（配置开 + 历史有效）。 */
    @JvmStatic
    fun temporalClampActive(): Boolean = temporalClampEnabled() && historyValid

    private fun fail(message: String) {
        failed = true
        ready = false
        LOGGER.warn("SRI disabled for this session: {}", message)
    }
}


