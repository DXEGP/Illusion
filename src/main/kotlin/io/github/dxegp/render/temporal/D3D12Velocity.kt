package io.github.dxegp.render.temporal

import io.github.dxegp.render.antialiasing.D3D12TAA
import io.github.dxegp.render.frameprep.MotionCoverage
import io.github.dxegp.render.frameprep.MotionGuideCoverage
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.reconstruction.ReconstructionFrameInputs

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.surface.D3D12GpuSurface
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.shader.D3D12RasterMotionTuple
import java.nio.ByteOrder
import java.nio.FloatBuffer
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory

    /** [abandonedSteps] bit: the velocity textures or the parameter block failed to allocate/upload. */
private const val STEP_RESOURCES: Int = 1

    /** [abandonedSteps] bit: the main-depth SRV view failed to create. */
private const val STEP_DEPTH_VIEW: Int = 2

    /** [abandonedSteps] bit: the velocity pipeline failed to create, bind or draw. */
private const val STEP_PIPELINE: Int = 4

internal class OpaqueMotionInputs(
    val frame: VanillaFrameInputs,
    val motion: D3D12GpuTexture,
    val coverage: D3D12GpuTexture,
    val guideCoverage: MotionGuideCoverage,
)

object D3D12Velocity {
    private enum class MotionPhase { OPAQUE, FINAL }

    private class MotionSources(
        val frame: VanillaFrameInputs,
        val phase: MotionPhase,
        val depth: D3D12GpuTexture,
        val raster: D3D12GpuTextureView?,
    )

    private class OpaqueTargets(val motion: D3D12GpuTexture, val coverage: D3D12GpuTexture) : AutoCloseable {
        override fun close() {
            try { motion.close() } finally { coverage.close() }
        }
    }

    private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc-velocity")

    /** Two half floats of pixel motion (native ordinal 41). */
    val FORMAT: GpuFormat = GpuFormat.RG16_FLOAT

    private val USAGE: Int = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC

    /** MC `PrimitiveTopology.TRIANGLES` ordinal (native `toPrimitiveTopology` maps 4 -> TRIANGLELIST). */
    private const val TOPOLOGY_TRIANGLES: Int = 4

    private const val WRITE_MASK_RG: Int = 0x3

    /** 256 B: D3D12 wants constant-buffer sizes 256-aligned even though only 172 B are used. */
    private const val CBV_SIZE: Long = 256L

    /** Object rows as a texel `Buffer<float>` (native `pushDescriptors` accepts texel buffers only here), 16 floats per row. */
    private val OBJECT_FORMAT: GpuFormat = GpuFormat.R32_FLOAT

    private const val OBJECT_BYTES: Long = D3D12ObjectMotion.MAX_OBJECTS.toLong() * D3D12ObjectMotion.ROW_FLOATS * 4L

    /** AABB margin in blocks: swinging limbs briefly leave the box, and without it they drop out. */
    private const val OBJECT_BOX_MARGIN: Float = 0.35f

    /** Per-object motion cap in pixels; a larger delta means the row matched the wrong object (fall back). */
    private const val OBJECT_MAX_MOTION_PIXELS: Float = 128.0f

    private const val FRAME_NONE: Long = -1L

    /** Master switch, seeded from `[debug] temporalChain`; without a resolve it only writes another texture. */
    @Volatile
    var enabled: Boolean = D3D12Settings.temporalChain

    /** Ping-pong textures: `current` is written this frame, `previous` is the last frame's result. */
    private var current: D3D12GpuTexture? = null
    private var previous: D3D12GpuTexture? = null
    private var currentCoverage: D3D12GpuTexture? = null
    private var previousCoverage: D3D12GpuTexture? = null
    private var opaqueTargets: OpaqueTargets? = null
    private var opaqueSources: MotionSources? = null
    private var opaqueDepthView: D3D12GpuTextureView? = null
    private var opaqueDepthViewFor: Long = 0L
    private var previousReady = false
    private var uploadOwner: io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend? = null
    private var width: Int = 0
    private var height: Int = 0

    /**
     * SRV views of the two textures. They must swap **together** with [current]/[previous] in
     * [beginFrame], or the resolve samples the wrong frame and the whole screen smears.
     */
    private var currentView: D3D12GpuTextureView? = null
    private var previousView: D3D12GpuTextureView? = null

    private var pipeline: Long = 0L

    /** Pipeline creation error (recorded once: no per-frame retry). */
    private var pipelineError: String? = null

    /** Parameter block (own buffer; the label shows up in native diagnostics). */
    private var uniform: GpuBufferSlice? = null

    /** Object row buffer (UPLOAD heap, bound as `t2` every frame). */
    private var objects: GpuBufferSlice? = null

    private val objectScratch: FloatArray = FloatArray(D3D12ObjectMotion.MAX_OBJECTS * D3D12ObjectMotion.ROW_FLOATS)

    /** Rows actually bound this frame (0 = no per-object correction). */
    private var objectCount: Int = 0

    /** Depth SRV view + the texture it belongs to (recreated when the main target is rebuilt). */
    private var depthView: D3D12GpuTextureView? = null
    private var depthViewFor: Long = 0L

    private var previousRecordedFrame: Long = FRAME_NONE

    /** Last recorded frame. DLSS records early, so the later call from `blitFromTexture` must short-circuit. */
    private var recordedFrame: Long = FRAME_NONE

    private var disabledReason: String? = null

    /**
     * Build steps already given up on: [STEP_RESOURCES], [STEP_DEPTH_VIEW], [STEP_PIPELINE].
     * [forceEnable] clears these bits so the next [record] retries.
     */
    private var abandonedSteps: Int = 0

    /** Whether recording is allowed; the temporal master switch must be on as well (TAA consumes vectors too). */
    fun isActive(): Boolean = (enabled || D3D12TAA.isSelected() ||
        io.github.dxegp.render.presentation.framegen.FrameGeneration.isEnabled()) && D3D12Temporal.isActive()

    /** Latched off: [disable] ran, or a build step was abandoned (DLSS uses this to decide on a retry). */
    @Synchronized
    fun isLatched(): Boolean = disabledReason != null || abandonedSteps != 0

    /** Clears the self-disable latch (leaves [enabled] to the caller) and [reset]s so DLSS can retry; true = a latch was cleared. */
    @Synchronized
    fun forceEnable(): Boolean {
        val hadLatch = disabledReason != null || abandonedSteps != 0
        reset()
        abandonedSteps = 0
        pipelineError = null
        if (pipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(pipeline)
        pipeline = 0L
        return hadLatch
    }

    /** This frame's velocity texture handle (0 = not built). */
    @Synchronized
    fun currentHandle(): Long = if (isActive() &&
        recordedFrame == VanillaFrameCoordinator.currentFrame()?.identity?.realFrameId)
        this.current?.handle() ?: 0L else 0L

    internal fun currentTexture(): D3D12GpuTexture? = current?.takeIf {
        isActive() && recordedFrame == VanillaFrameCoordinator.currentFrame()?.identity?.realFrameId
    }

    @Synchronized
    fun currentCoverageHandle(): Long = if (isActive() &&
        recordedFrame == VanillaFrameCoordinator.currentFrame()?.identity?.realFrameId)
        currentCoverage?.handle() ?: 0L else 0L

    internal fun motionCoverage(): MotionCoverage = when {
        D3D12RasterMotion.view() != null -> MotionCoverage.CAMERA_WITH_RASTER_ENTITY_MODELS
        objectCount > 0 -> MotionCoverage.CAMERA_WITH_ENTITY_TRANSLATION_APPROXIMATION
        else -> MotionCoverage.CAMERA_REPROJECTION
    }

    internal fun motionGuideCoverage(): MotionGuideCoverage = if (D3D12RasterMotion.view() != null)
        MotionGuideCoverage.CPU_ENTITY_MODEL_RASTER_PARTIAL else D3D12ObjectMotion.motionGuideCoverage()

    /** Diagnostic one-liner for "why is there no motion texture" (the caller rate-limits it). */
    @Synchronized
    fun debugState(): String {
        return "frame=${VanillaFrameCoordinator.currentFrame()?.identity?.realFrameId} recorded=$recordedFrame current=${current != null}" +
            " previous=${previous != null} abandoned=$abandonedSteps latch=$disabledReason" +
            " active=${isActive()} enabled=$enabled temporal=${D3D12Temporal.isActive()}"
    }

    /** This frame's motion SRV for the TAA resolve; null = no data yet. */
    @Synchronized
    fun currentMotionView(): D3D12GpuTextureView? = if (recordedFrame != VanillaFrameCoordinator.currentFrame()?.identity?.realFrameId || this.current == null) null else this.currentView

    /** Previous frame's velocity texture handle (0 = no history). */
    @Synchronized
    fun previousHandle(): Long = if (hasPreviousFrame()) this.previous?.handle() ?: 0L else 0L

    /** Previous frame's motion SRV (the VRS motion test); null = no history, the caller skips this frame. */
    @Synchronized
    fun previousMotionView(): D3D12GpuTextureView? = if (!hasPreviousFrame() || this.previous == null) null else this.previousView

    private fun hasPreviousFrame(): Boolean {
        val frame = VanillaFrameCoordinator.currentFrame() ?: return false
        return previousReady && frame.previous != null && previousRecordedFrame == frame.identity.realFrameId - 1L
    }

    /** Releases everything (device shutdown / world exit). */
    @Synchronized
    fun reset() {
        previousRecordedFrame = FRAME_NONE
        recordedFrame = FRAME_NONE
        disabledReason = null
        releaseResources()
    }

    /** Per-frame ping-pong: the texture written this frame becomes "previous". */
    @Synchronized
    fun beginFrame() {
        opaqueSources = null
        if (!isActive()) {
            previousReady = false
            return
        }
        previousReady = current != null
        previousRecordedFrame = recordedFrame
        val swap = this.previous
        this.previous = this.current
        this.current = swap
        val swapView = this.previousView
        this.previousView = this.currentView
        this.currentView = swapView
        val swapCoverage = previousCoverage
        previousCoverage = currentCoverage
        currentCoverage = swapCoverage
    }

    /** Records the pass right before the blit, while the frame is fully drawn and the depth valid; true = really recorded. */
    @Synchronized
    fun record(ctx: Long, device: D3D12Device?): Boolean {
        if (!isActive()) return false
        val frame = VanillaFrameCoordinator.currentFrame() ?: return false
        val depth = ReconstructionFrameInputs.capturedDepth()
            ?: (Minecraft.getInstance().gameRenderer.mainRenderTarget().depthTexture as? D3D12GpuTexture)
            ?: return false
        return recordMotion(ctx, device, MotionSources(frame, MotionPhase.FINAL, depth, D3D12RasterMotion.view()))
    }

    @Synchronized
    internal fun recordOpaque(ctx: Long, device: D3D12Device?, depth: D3D12GpuTexture,
        raster: D3D12GpuTextureView?): OpaqueMotionInputs? {
        val frame = VanillaFrameCoordinator.currentFrame() ?: return null
        if (!recordMotion(ctx, device, MotionSources(frame, MotionPhase.OPAQUE, depth, raster))) return null
        val targets = checkNotNull(opaqueTargets)
        return OpaqueMotionInputs(frame, targets.motion, targets.coverage,
            if (frame.previous == null) MotionGuideCoverage.INVALID
            else if (raster != null) MotionGuideCoverage.CPU_ENTITY_MODEL_RASTER_PARTIAL
            else D3D12ObjectMotion.motionGuideCoverage())
    }

    private fun recordMotion(ctx: Long, device: D3D12Device?, sources: MotionSources): Boolean {
        if (!isActive()) {
            return false
        }
        if (abandonedSteps != 0) {
            return false
        }
        val frame = sources.frame
        check(frame === VanillaFrameCoordinator.currentFrame()) { "Motion inputs belong to another world frame" }
        if (sources.phase == MotionPhase.FINAL && this.recordedFrame == frame.identity.realFrameId) {
            return this.current != null && currentCoverage != null
        }
        if (sources.phase == MotionPhase.OPAQUE && opaqueSources?.frame === frame) {
            check(opaqueSources?.depth === sources.depth && opaqueSources?.raster === sources.raster) {
                "Opaque motion sources changed after recording"
            }
            return opaqueTargets != null
        }
        val depth = sources.depth
        if (depth.isClosed()) {
            return false
        }
        val w = depth.getWidth(0)
        val h = depth.getHeight(0)
        if (w <= 0 || h <= 0) {
            return false
        }
        val camera = frame.current
        val previousCamera = frame.previous ?: if (sources.phase == MotionPhase.OPAQUE) camera else return false
        if (w != frame.identity.width || h != frame.identity.height) return false
        val view = camera.viewRotation()
        val previousView = previousCamera.viewRotation()
        val prevProjection = previousCamera.projection()
        if (device == null) {
            return false
        }
        if (!ensurePipeline(device)) {
            return false
        }
        if (!ensureResources(device, w, h)) {
            return false
        }
        if (sources.phase == MotionPhase.OPAQUE && opaqueTargets == null) {
            val motion = D3D12GpuTexture(USAGE, "Opaque velocity", FORMAT, w, h, 1, 1)
            val coverage = try {
                D3D12GpuTexture(USAGE, "Opaque motion coverage", GpuFormat.R8_UNORM, w, h, 1, 1)
            } catch (failure: Throwable) {
                try { motion.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            opaqueTargets = OpaqueTargets(motion, coverage)
        }
        val encoder = D3D12Device.sharedCommandEncoder() ?: return false
        check(encoder.nativeHandle() == ctx) { "Velocity upload owner differs from recording context" }
        if (uploadOwner == null) {
            encoder.retainUntilCommandsClosed(AutoCloseable {
                reset()
                if (pipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(pipeline)
                pipeline = 0L
                pipelineError = null
                uploadOwner = null
            })
            uploadOwner = encoder
        }
        check(uploadOwner === encoder) { "Velocity resources changed command owner" }
        if (sources.phase == MotionPhase.OPAQUE && frame.previous == null) {
            val targets = checkNotNull(opaqueTargets)
            val uses = listOf(targets.motion, targets.coverage).map {
                VanillaFrameInputResources.texture(it.handle(), frame.identity.resourceVersion, 2,
                    VanillaFrameInputResources.STATE_RENDER_TARGET)
            }
            VanillaFrameCoordinator.declaredPass(ctx, VanillaFrameInputResources.OPAQUE_VELOCITY_PASS, uses,
                graphicsEffects = 0) {
                MCDR_ABI.D3D12ClearColorTexture(ctx, targets.motion.handle(), 0f, 0f, 0f, 0f)
                MCDR_ABI.D3D12ClearColorTexture(ctx, targets.coverage.handle(), 0f, 0f, 0f, 0f)
            }
            opaqueSources = sources
            return true
        }
        val memory = encoder.transientMemory()
        uniform = memory.allocateGpuMapped(CBV_SIZE, 256L, GpuBuffer.USAGE_UNIFORM, 0L, 1L).use { it.slice() }
        objects = memory.allocateGpuMapped(OBJECT_BYTES, 256L,
            GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 0L, 4L).use { it.slice() }
        val uniformBuffer = this.uniform ?: return false
        val color = when (sources.phase) {
            MotionPhase.FINAL -> this.current
            MotionPhase.OPAQUE -> opaqueTargets?.motion
        } ?: return false
        val coverage = when (sources.phase) {
            MotionPhase.FINAL -> currentCoverage
            MotionPhase.OPAQUE -> opaqueTargets?.coverage
        } ?: return false
        if (!ensureDepthView(depth, sources.phase)) {
            return false
        }
        val depthSrv = when (sources.phase) {
            MotionPhase.FINAL -> depthView
            MotionPhase.OPAQUE -> opaqueDepthView
        } ?: return false

        val jittered = camera.rasterProjection()
        val currViewProj = D3D12Temporal.multiply(jittered, view)
        val prevViewProj = D3D12Temporal.multiply(prevProjection, previousView)
        val invCurr = invert(currViewProj) ?: return false
        val rows = if (sources.raster != null) {
            objectCount = 0
            0
        } else uploadObjects()
        val jitter = doubleArrayOf(camera.jitterX, camera.jitterY)
        val prevJitter = doubleArrayOf(previousCamera.jitterX, previousCamera.jitterY)
        val camDelta = floatArrayOf((camera.originX - previousCamera.originX).toFloat(),
            (camera.originY - previousCamera.originY).toFloat(), (camera.originZ - previousCamera.originZ).toFloat())
        if (!writeParameters(uniformBuffer, prevViewProj, invCurr, w, h, rows,
                jitter[0].toFloat(), jitter[1].toFloat(),
                prevJitter[0].toFloat(), prevJitter[1].toFloat(),
                camDelta[0], camDelta[1], camDelta[2], sources.raster)) {
            return false
        }

        val objectBuffer = this.objects ?: return false
        val rasterGuide = sources.raster
        val rasterSrv = rasterGuide ?: depthSrv
        val version = VanillaFrameCoordinator.currentResourceVersion() ?: 1L
        val uses = listOf(
            VanillaFrameInputResources.texture(color.handle(), version, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET, VanillaFrameInputResources.STATE_COMMON),
            VanillaFrameInputResources.texture(coverage.handle(), version, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET, VanillaFrameInputResources.STATE_COMMON),
            VanillaFrameInputResources.texture(depth.handle(), version, 1,
                VanillaFrameInputResources.STATE_SHADER_RESOURCE),
        ) + (if (rasterGuide != null) listOf(
            VanillaFrameInputResources.texture((rasterGuide.texture() as D3D12GpuTexture).handle(), version, 1,
                VanillaFrameInputResources.STATE_SHADER_RESOURCE)) else emptyList()) +
            VanillaFrameInputResources.velocityBuffers(uniformBuffer, objectBuffer, version)
        val pass = when (sources.phase) {
            MotionPhase.FINAL -> VanillaFrameInputResources.VELOCITY_PASS
            MotionPhase.OPAQUE -> VanillaFrameInputResources.OPAQUE_VELOCITY_PASS
        }
        val recorded = VanillaFrameCoordinator.declaredPass(ctx, pass, uses) {
            MCDR_ABI.D3D12BeginRenderPass(ctx, longArrayOf(color.handle(), coverage.handle()), intArrayOf(0, 0),
                byteArrayOf(1, 1), FloatArray(8),
                0L, 0, 0.toByte(), 0.0, 0, 0, w, h)
            MCDR_ABI.D3D12SetViewport(ctx, 0, 0, w, h)
            MCDR_ABI.D3D12SetScissor(ctx, 0, 0, w, h)
            if (!MCDR_ABI.D3D12SetPipeline(ctx, this.pipeline, false)) {
                disable("dx12SetPipeline failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
                MCDR_ABI.D3D12EndRenderPass(ctx)
                return@declaredPass false
            }
            val pushed = MCDR_ABI.D3D12PushDescriptors(ctx,
                intArrayOf(0, 1, 2, 1),
                longArrayOf((uniformBuffer.buffer() as D3D12GpuBuffer).handle(), 0L,
                    (objectBuffer.buffer() as D3D12GpuBuffer).handle(), 0L),
                longArrayOf(uniformBuffer.offset(), 0L, objectBuffer.offset(), 0L),
                longArrayOf(CBV_SIZE, 0L, OBJECT_BYTES, 0L),
                intArrayOf(0, 0, OBJECT_FORMAT.ordinal, 0),
                longArrayOf(0L, depthSrv.handle(), 0L, rasterSrv.handle()))
            if (!pushed) {
                disable("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
                MCDR_ABI.D3D12EndRenderPass(ctx)
                return@declaredPass false
            }
            if (!MCDR_ABI.D3D12Draw(ctx, 3, 1, 0, 0)) {
                disable("dx12Draw failed: ${MCDR_ABI.D3D12LastError()}", STEP_PIPELINE)
                MCDR_ABI.D3D12EndRenderPass(ctx)
                return@declaredPass false
            }
            MCDR_ABI.D3D12EndRenderPass(ctx)
            true
        }
        if (!recorded) return false
        when (sources.phase) {
            MotionPhase.FINAL -> recordedFrame = frame.identity.realFrameId
            MotionPhase.OPAQUE -> opaqueSources = sources
        }
        return true
    }

    private fun ensureResources(device: D3D12Device, w: Int, h: Int): Boolean {
        if (this.current != null && this.previous != null && currentCoverage != null && previousCoverage != null &&
            this.width == w && this.height == h) {
            return true
        }
        releaseResources()
        return try {
            this.current = D3D12GpuTexture(USAGE, "DXRMC velocity", FORMAT, w, h, 1, 1)
            this.previous = D3D12GpuTexture(USAGE, "DXRMC velocity (previous)", FORMAT, w, h, 1, 1)
            currentCoverage = D3D12GpuTexture(USAGE, "World motion coverage", GpuFormat.R8_UNORM, w, h, 1, 1)
            previousCoverage = D3D12GpuTexture(USAGE, "World motion coverage (previous)", GpuFormat.R8_UNORM, w, h, 1, 1)
            this.currentView = D3D12GpuTextureView(this.current!!, 0, 1)
            this.previousView = D3D12GpuTextureView(this.previous!!, 0, 1)
            this.width = w
            this.height = h
            abandonedSteps = abandonedSteps and STEP_RESOURCES.inv()
            LOGGER.info("velocity: allocated {}x{} {} (ping-pong)", w, h, FORMAT)
            true
        } catch (t: Throwable) {
            releaseResources()
            disable("resource allocation failed: ${t}", STEP_RESOURCES)
            false
        }
    }

    /** Depth SRV (built once per texture; rebuilt automatically after the main target is recreated). */
    private fun ensureDepthView(depth: D3D12GpuTexture, phase: MotionPhase): Boolean {
        val handle = depth.handle()
        if (phase == MotionPhase.OPAQUE) {
            if (opaqueDepthView != null && opaqueDepthViewFor == handle) return true
            opaqueDepthView?.close()
            opaqueDepthView = null
            opaqueDepthViewFor = 0L
            opaqueDepthView = D3D12GpuTextureView(depth, 0, 1)
            opaqueDepthViewFor = handle
            return true
        }
        if (this.depthView != null && this.depthViewFor == handle) {
            return true
        }
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        return try {
            this.depthView = D3D12GpuTextureView(depth, 0, 1)
            this.depthViewFor = handle
            abandonedSteps = abandonedSteps and STEP_DEPTH_VIEW.inv()
            LOGGER.info("velocity: depth SRV view created for texture {} (fmt={})",
                handle.toULong().toString(16), depth.format)
            true
        } catch (t: Throwable) {
            disable("depth view creation failed: ${t}", STEP_DEPTH_VIEW)
            false
        }
    }

    private fun ensurePipeline(device: D3D12Device): Boolean {
        if (this.pipeline != 0L) {
            return true
        }
        if (this.pipelineError != null) {
            return false
        }
        val desc = MCDRPipelineDesc()
            .shaders(VERTEX_HLSL, PIXEL_HLSL)
            .topology(TOPOLOGY_TRIANGLES, false, 0)
            .addColorTarget(MCDRPipelineDesc.ColorTarget()
                .format(FORMAT.ordinal)
                .writeMask(WRITE_MASK_RG))
            .addColorTarget(MCDRPipelineDesc.ColorTarget().format(GpuFormat.R8_UNORM.ordinal).writeMask(1))
            .addBinding(0, 0)
            .addBinding(1, 1)
            .addBinding(2, 2)
            .addBinding(1, 3)
        val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc)
        if (handle == 0L) {
            this.pipelineError = MCDR_ABI.D3D12LastError()
            disable("dx12CreateGraphicsPipeline failed: ${this.pipelineError}", STEP_PIPELINE)
            return false
        }
        this.pipeline = handle
        abandonedSteps = abandonedSteps and STEP_PIPELINE.inv()
        LOGGER.info("velocity: pipeline compiled (handle={})", handle.toULong().toString(16))
        return true
    }

    /**
     * Uploads this frame's object rows, returns the count (0 = no per-object correction). A failure only
     * degrades this frame, so it never calls [disable].
     */
    private fun uploadObjects(): Int {
        val buffer = this.objects
        if (buffer == null) {
            this.objectCount = 0
            return 0
        }
        val camera = VanillaFrameCoordinator.currentFrame()?.current ?: return 0
        val rows = try {
            D3D12ObjectMotion.writeWorldRows(this.objectScratch, camera.originX, camera.originY, camera.originZ)
        } catch (t: Throwable) {
            LOGGER.warn("velocity: object row read failed — {}", t.toString())
            0
        }
        this.objectCount = rows
        if (rows <= 0) {
            return 0
        }
        return try {
            buffer.map(false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.put(this.objectScratch, 0, rows * D3D12ObjectMotion.ROW_FLOATS)
            }
            rows
        } catch (t: Throwable) {
            LOGGER.warn("velocity: object row upload failed — {}", t.toString())
            this.objectCount = 0
            0
        }
    }

    /**
     * Writes the parameter block: 0..15 `PrevViewProj`, 16..31 `InvCurrViewProj`, 32 `ScreenSize`,
     * 34 `ObjectParams` (x = rows + 1, y = motion cap in pixels), 36 `JitterPixels`, 38 `PrevJitterPixels`,
     * 40..42 `CameraDelta`; 172 B used, padded to 256. Map directly — `writeToBuffer` detours through
     * [D3D12Temporal.interceptWrite].
     */
    private fun writeParameters(buffer: GpuBufferSlice, prevViewProj: FloatArray,
        invCurrViewProj: FloatArray, w: Int, h: Int, rows: Int,
        jitterX: Float, jitterY: Float, prevJitterX: Float, prevJitterY: Float,
        camDeltaX: Float, camDeltaY: Float, camDeltaZ: Float, raster: D3D12GpuTextureView?): Boolean {
        return try {
            buffer.map(false, true).use { view ->
                val fb: FloatBuffer = view.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.put(prevViewProj, 0, 16)
                fb.put(invCurrViewProj, 0, 16)
                fb.put(w.toFloat())
                fb.put(h.toFloat())
                fb.put(rows.toFloat())
                fb.put(OBJECT_MAX_MOTION_PIXELS)
                fb.put(jitterX)
                fb.put(jitterY)
                fb.put(prevJitterX)
                fb.put(prevJitterY)
                fb.put(camDeltaX)
                fb.put(camDeltaY)
                fb.put(camDeltaZ)
                fb.put(if (raster != null) 1f else 0f)
                while (fb.position() < 64) {
                    fb.put(0.0f)
                }
            }
            true
        } catch (t: Throwable) {
            disable("parameter upload failed: ${t}", STEP_RESOURCES)
            false
        }
    }

    /**
     * Column-major 4x4 inverse (double precision, Gauss-Jordan); null when singular or non-finite.
     * Do not swap in JOML: `set()` reads row-major while `get()` writes column-major, so set-then-invert
     * silently yields the transpose.
     */
    private fun invert(matrix: FloatArray): FloatArray? {
        if (matrix.size < 16) {
            return null
        }
        val augmented = Array(4) { r ->
            DoubleArray(8) { c ->
                when {
                    c < 4 -> matrix[c * 4 + r].toDouble()
                    c - 4 == r -> 1.0
                    else -> 0.0
                }
            }
        }
        for (col in 0 until 4) {
            var pivot = col
            var best = kotlin.math.abs(augmented[col][col])
            for (r in col + 1 until 4) {
                val candidate = kotlin.math.abs(augmented[r][col])
                if (candidate > best) {
                    best = candidate
                    pivot = r
                }
            }
            if (!best.isFinite() || best < 1e-20) {
                return null
            }
            if (pivot != col) {
                val swap = augmented[col]
                augmented[col] = augmented[pivot]
                augmented[pivot] = swap
            }
            val diagonal = augmented[col][col]
            for (c in 0 until 8) {
                augmented[col][c] /= diagonal
            }
            for (r in 0 until 4) {
                if (r == col) {
                    continue
                }
                val factor = augmented[r][col]
                if (factor == 0.0) {
                    continue
                }
                for (c in 0 until 8) {
                    augmented[r][c] -= factor * augmented[col][c]
                }
            }
        }
        val out = FloatArray(16)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                out[c * 4 + r] = augmented[r][c + 4].toFloat()
            }
        }
        return out
    }

    private fun disable(reason: String) {
        disable(reason, 0)
    }

    /**
     * Closes for good, remembers the reason and which build step must not be retried.
     * @param abandonStep 0 = this frame only; anything else is an [abandonedSteps] bit, cleared by [forceEnable].
     */
    private fun disable(reason: String, abandonStep: Int) {
        if (abandonStep != 0) {
            abandonedSteps = abandonedSteps or abandonStep
        }
        if (this.disabledReason == null) {
            this.disabledReason = reason
            LOGGER.warn("velocity: disabling velocity pass — {}", reason)
        }
        this.enabled = false
    }

    private fun releaseResources() {
        previousReady = false
        opaqueSources = null
        opaqueDepthView?.close()
        opaqueDepthView = null
        opaqueDepthViewFor = 0L
        opaqueTargets?.close()
        opaqueTargets = null
        this.currentView?.close()
        this.currentView = null
        this.previousView?.close()
        this.previousView = null
        this.current?.close()
        this.current = null
        this.previous?.close()
        this.previous = null
        currentCoverage?.close()
        currentCoverage = null
        previousCoverage?.close()
        previousCoverage = null
        this.uniform = null
        this.objects = null
        this.objectCount = 0
        this.depthView?.close()
        this.depthView = null
        this.depthViewFor = 0L
        this.width = 0
        this.height = 0
    }

    /** Full-screen triangle: `SV_VertexID` 0/1/2 -> uv (0,0)/(2,0)/(0,2); an empty input layout is legal. */
    private val VERTEX_HLSL: String = """
        struct VsOut {
            float4 position : SV_Position;
        };

        VsOut main(uint vertexId : SV_VertexID) {
            VsOut output;
            float2 uv = float2((vertexId << 1) & 2, vertexId & 2);
            output.position = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
            return output;
        }
    """.trimIndent()

    /**
     * Depth reprojection -> screen-space motion in pixels, plus the per-object correction.
     * `SV_Position.xy` is the pixel centre, so the uv needs no extra interpolation.
     */
    private val PIXEL_HLSL: String = """
        cbuffer DXRMVelocity : register(b0, space0) {
            float4x4 PrevViewProj;
            float4x4 InvCurrViewProj;
            float2 ScreenSize;
                // x = 物体行数（0 表示没有物体），y = 位移像素上限。
            float2 ObjectParams;
            // 本帧抖动带来的像素偏移。uv 是抖动后的屏幕位置，PrevViewProj 是未抖动的
            // 上一帧投影，所以位移里混着这份 jitter，必须减掉才是去抖动空间的位移。
            float2 JitterPixels;
            // 上一帧的抖动保留在共享参数块，供 resolve 侧按自身历史约定使用；本 pass 的运动输出不加入它。
            float2 PrevJitterPixels;
            // 两帧之间相机平移的增量（本帧 − 上一帧）。深度反投影出的是**相对本帧相机**的
            // 位置，而 PrevViewProj 的视图矩阵只有旋转，所以必须补上这段平移，否则相机自己
            // 走动/侧移造成的视差整块丢失。
            float3 CameraDelta;
            float HasRasterGuide;
        };

        Texture2D<float> DXRMDepth : register(t1, space0);
        SamplerState DXRMDepthSampler : register(s1, space0);

        // texel buffer：每个 float 一个元素（绑定类型 2 + texelFormat = R32_FLOAT）。
        Buffer<float> DXRMObjects : register(t2, space0);
        Texture2D<float4> DXRMRasterGuide : register(t3, space0);

        static const uint OBJECT_ROW_FLOATS = 16;
        static const uint OBJECT_FIELD_CUR_X = 1;
        static const uint OBJECT_FIELD_PREV_X = 5;
        static const uint OBJECT_FIELD_HALF_X = 8;
        static const uint OBJECT_FIELD_YAW = 11;

        // 盒子上再外扩一点：挥臂/摆腿的顶点会短暂越出 AABB，不外扩就会漏掉肢体
        // （漏掉只是退回相机重投影，不会更差，但能补上就补）。
        static const float OBJECT_BOX_MARGIN = 0.25;

        // 画世界 AABB 的八个角：轴对齐盒 + 绕 Y 轴 yaw，正好八个顶点，用下标位选正负号。
        float3 objectCorner(float3 center, float3 halfExtent, float yaw, uint index) {
            float3 corner = float3((index & 1u) == 0u ? -halfExtent.x : halfExtent.x,
                                   (index & 2u) == 0u ? -halfExtent.y : halfExtent.y,
                                   (index & 4u) == 0u ? -halfExtent.z : halfExtent.z);
            float c = cos(yaw);
            float s = sin(yaw);
            return center + float3(corner.x * c - corner.z * s, corner.y, corner.x * s + corner.z * c);
        }

        // 像素的世界位置是否落在该物体**上一帧**的盒子里。用上一帧的盒子（而不是本帧的）是
        // 有意的：本 pass 读到的深度、反投影出的世界位置描述的都是**本帧**的几何，所以匹配时
        // 问的是"本帧这个位置，上一帧属于哪个物体"。
        bool insideObjectBox(float3 world, uint base) {
            float3 center = float3(DXRMObjects.Load(base + OBJECT_FIELD_CUR_X),
                                   DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 1u),
                                   DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 2u));
            float3 halfExtent = float3(DXRMObjects.Load(base + OBJECT_FIELD_HALF_X),
                                       DXRMObjects.Load(base + OBJECT_FIELD_HALF_X + 1u),
                                       DXRMObjects.Load(base + OBJECT_FIELD_HALF_X + 2u))
                                + OBJECT_BOX_MARGIN;
            float yaw = DXRMObjects.Load(base + OBJECT_FIELD_YAW);

            float3 minCorner = float3(1e30, 1e30, 1e30);
            float3 maxCorner = float3(-1e30, -1e30, -1e30);
            for (uint i = 0u; i < 8u; ++i) {
                float3 corner = objectCorner(center, halfExtent, yaw, i);
                minCorner = min(minCorner, corner);
                maxCorner = max(maxCorner, corner);
            }
            return all(world >= minCorner) && all(world <= maxCorner);
        }

        struct VelocityGuides {
            float2 motion : SV_Target0;
            float coverage : SV_Target1;
        };

        VelocityGuides motionGuides(float2 motion, uint coverage) {
            VelocityGuides output;
            if (!all(isfinite(motion)) || any(abs(motion) > 65504.0)) {
                motion = 0.0;
                coverage = 1u;
            }
            output.motion = motion;
            output.coverage = float(coverage) / 255.0;
            return output;
        }

        VelocityGuides main(float4 position : SV_Position) {
            float2 uv = position.xy / ScreenSize;
            float depth = DXRMDepth.SampleLevel(DXRMDepthSampler, uv, 0).r;
            if (!isfinite(depth) || depth < 0.0 || depth > 1.0) return motionGuides(0.0, 1u);
            uint coverage = 3u;
            if (HasRasterGuide > 0.5) {
                float4 guide = DXRMRasterGuide.Load(int3(uint2(position.xy), 0));
                if (!all(isfinite(guide))) coverage = 1u;
                else if (guide.w > 0.5) {
                    coverage = 1u;
                    if ((guide.z >= 0.0 && guide.z == depth) ||
                        (guide.z == ${D3D12RasterMotionTuple.CELESTIAL_VALID} && depth == 0.0)) {
                        return motionGuides(guide.xy, 3u);
                    }
                    if (guide.z == ${D3D12RasterMotionTuple.CAMERA_SURFACE} && guide.x >= depth && guide.x <= 1.0) {
                        depth = guide.x;
                        coverage = 3u;
                    }
                }
            }
            float4 ndc = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
            float4 world = mul(InvCurrViewProj, ndc);
            if (!all(isfinite(world))) return motionGuides(0.0, 1u);
            float4 prevClip;
            if (depth == 0.0) {
                prevClip = mul(PrevViewProj, float4(world.xyz, 0.0));
            } else {
                if (abs(world.w) < 1e-6) return motionGuides(0.0, 1u);
                world /= world.w;
                prevClip = mul(PrevViewProj, float4(world.xyz + CameraDelta, 1.0));
            }
            // 点在上一帧相机背后（w<0）时绝不能再做 xy/w：负的 w 会把屏幕位置镜像到另一边，
            // 得到一个量级看着合理、实际指向别处的位移，DLSS 照着它去历史缓冲取错颜色（沙地
            // 变白、树叶变紫，只在转视角/走动时闪一下）。这种像素没有对应点，报 0 交给 disocclusion。
            // `abs(prevClip.w) < 1e-6` 只挡住数值退化，恰好放过了 w<0 这一整类。
            if (!all(isfinite(prevClip)) || prevClip.w <= 1e-4) {
                return motionGuides(0.0, 1u);
            }
            float2 prevNdc = prevClip.xy / prevClip.w;
            float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, 0.5 - prevNdc.y * 0.5);
            // 运动向量方向是「从本帧指向该像素在上一帧的位置」，即 prevPos - curPos；写成
            // `uv - prevUv` 方向整体反了，静止时两边都是 0 看不出来，一动就在运动方向上拖出
            // 永不收敛的残影（机制见 docs/antialiasing/README.md 的速度缓冲一节）。
            float2 motion = (prevUv - uv) * ScreenSize + float2(JitterPixels.x, -JitterPixels.y);
            // NaN/inf 兜底：NaN 参与的比较恒为假，所以用"取反的 <=" 写法把它一并挡掉。上一条
            // 守卫之后这里理论上不会触发，留着是因为一旦传出去就是一整块画面的错色。
            if (!(dot(motion, motion) <= 1.0e12)) {
                return motionGuides(0.0, 1u);
            }
            if (depth == 0.0) return motionGuides(motion, coverage);
            // 先到先得 —— 行是按「上一帧采集顺序」排的，一个像素落在多个盒子里时
            // （嵌套的乘客/坐骑、相邻的掉落物）取第一行，够用且稳定。
            uint rowLimit = (uint)ObjectParams.x;
            for (uint row = 0u; row < rowLimit; ++row) {
                uint base = row * OBJECT_ROW_FLOATS;
                if (!insideObjectBox(world, base)) {
                    continue;
                }
                // 这个像素现在看到的是物体的表面点。它上一帧在哪儿？物体是刚体平移，
                // 所以上一帧位置 = 本帧世界位置 + (上一帧物体位置 - 本帧物体位置)。
                // 这是**精确**的对应关系，不需要也不应该去借物体中心的屏幕位移来近似。
                float3 cur = float3(DXRMObjects.Load(base + OBJECT_FIELD_CUR_X),
                                    DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 1u),
                                    DXRMObjects.Load(base + OBJECT_FIELD_CUR_X + 2u));
                float3 prv = float3(DXRMObjects.Load(base + OBJECT_FIELD_PREV_X),
                                    DXRMObjects.Load(base + OBJECT_FIELD_PREV_X + 1u),
                                    DXRMObjects.Load(base + OBJECT_FIELD_PREV_X + 2u));
                float3 prevWorld = world.xyz + (prv - cur) + CameraDelta;
                float4 prevObjectClip = mul(PrevViewProj, float4(prevWorld, 1.0));
                // 同 main 里的守卫：w<=0 会把位置镜像到画面另一处，得到假位移（见上）。
                if (prevObjectClip.w <= 1e-4) {
                    continue;
                }
                float2 prevObjectNdc = prevObjectClip.xy / prevObjectClip.w;
                float2 prevObjectUv = float2(prevObjectNdc.x * 0.5 + 0.5, 0.5 - prevObjectNdc.y * 0.5);
                // 同上：未抖动网格之间的位移；jitter 在像素坐标里的实际位移是
                // `(+J.x, -J.y)`（y 反号，理由见上面 motion 的注释）。
                float2 objectMotion = (prevObjectUv - uv) * ScreenSize + float2(JitterPixels.x, -JitterPixels.y);
                // 坏匹配（张冠李戴的行）位移会大得离谱，直接丢掉，回退相机重投影。
                if (dot(objectMotion, objectMotion) > ObjectParams.y * ObjectParams.y) {
                    continue;
                }
                return motionGuides(objectMotion, coverage);
            }
            return motionGuides(motion, coverage);
        }
    """.trimIndent()
}
