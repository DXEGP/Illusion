package io.github.dxegp.render.temporal

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.command.D3D12MotionBindings
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import io.github.dxegp.render.temporal.celestial.CelestialMotionCapture
import java.nio.ByteOrder
import java.util.IdentityHashMap

internal object D3D12RasterMotion : AutoCloseable {
    private data class RasterHistory(
        val vertices: D3D12RasterMotionCapture.VertexHistory? = null,
        val vertexBase: Int = 0,
        val celestialTransform: FloatArray? = null,
    )
    private data class RasterViewport(val x: Int, val y: Int, val width: Int, val height: Int)

    private const val CLEAR_PASS = 0x30006
    private const val PARAMETER_BYTES = 256L
    private var owner: D3D12CommandEncoderBackend? = null
    private var target: D3D12GpuTexture? = null
    private var targetView: D3D12GpuTextureView? = null
    private var viewmodelTarget: D3D12GpuTexture? = null
    private var viewmodelTargetView: D3D12GpuTextureView? = null
    private var activeTarget: D3D12GpuTexture? = null
    private var motionSpace = D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE
    private var jitterX = 0f
    private var jitterY = 0f
    private var frame: VanillaFrameIdentity? = null
    private var worldColor = 0L
    private var worldDepth = 0L
    private val uploads = IdentityHashMap<D3D12RasterMotionCapture.VertexHistory, GpuBufferSlice>()
    private var neutral: GpuBufferSlice? = null
    private var previousViewProjection: FloatArray? = null
    private var attached = false

    fun begin(encoder: D3D12CommandEncoderBackend, identity: VanillaFrameIdentity, color: Long, depth: Long) {
        check(frame == null && !attached) { "World raster motion began before the previous capture retired" }
        if (!D3D12Velocity.isActive()) return
        check(owner == null || owner === encoder) { "Raster motion changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val existing = target
        if (existing == null || existing.getWidth(0) != identity.width || existing.getHeight(0) != identity.height) {
            targetView?.close()
            existing?.close()
            target = D3D12GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING,
                "World raster motion and coverage", GpuFormat.RGBA32_FLOAT, identity.width, identity.height, 1, 1)
            targetView = D3D12GpuTextureView(checkNotNull(target), 0, 1)
        }
        val output = checkNotNull(target)
        val camera = VanillaFrameCoordinator.currentFrame()?.previous
        previousViewProjection = camera?.let { D3D12Temporal.multiply(it.projection(), it.viewRotation()) }
        frame = identity
        activeTarget = output
        motionSpace = D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE
        jitterX = VanillaFrameCoordinator.currentFrame()?.current?.jitterX?.toFloat() ?: 0f
        jitterY = VanillaFrameCoordinator.currentFrame()?.current?.jitterY?.toFloat() ?: 0f
        worldColor = color
        worldDepth = depth
        encoder.recordCommands { context ->
            MCDR_ABI.D3D12SetRasterMotionTarget(context, color, depth, output.handle())
            attached = true
            val uses = listOf(VanillaFrameInputResources.texture(output.handle(), identity.resourceVersion, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET))
            VanillaFrameCoordinator.declaredPass(context, CLEAR_PASS, uses, graphicsEffects = 0) {
                MCDR_ABI.D3D12ClearColorTexture(context, output.handle(), 0f, 0f, 0f, 0f)
            }
        }
    }

    internal fun beginViewmodel(encoder: D3D12CommandEncoderBackend, identity: VanillaFrameIdentity,
        color: Long, depth: Long, width: Int, height: Int, previous: FloatArray?) {
        check(!attached) { "Viewmodel raster motion began before world capture ended" }
        check(owner == null || owner === encoder) { "Raster motion changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val existing = viewmodelTarget
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            viewmodelTargetView?.close()
            existing?.close()
            viewmodelTarget = D3D12GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING,
                "Viewmodel raster motion and coverage", GpuFormat.RGBA32_FLOAT, width, height, 1, 1)
            viewmodelTargetView = D3D12GpuTextureView(checkNotNull(viewmodelTarget), 0, 1)
        }
        val output = checkNotNull(viewmodelTarget)
        activeTarget = output
        motionSpace = D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE
        previousViewProjection = previous?.copyOf()
        jitterX = 0f
        jitterY = 0f
        uploads.clear()
        neutral = null
        worldColor = color
        worldDepth = depth
        encoder.recordCommands { context ->
            MCDR_ABI.D3D12SetRasterMotionTarget(context, color, depth, output.handle())
            attached = true
            val uses = listOf(VanillaFrameInputResources.texture(output.handle(), identity.resourceVersion, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET))
            VanillaFrameCoordinator.declaredPass(context, 0x30007, uses, graphicsEffects = 0) {
                MCDR_ABI.D3D12ClearColorTexture(context, output.handle(), 0f, 0f, 0f, 0f)
            }
        }
    }

    internal fun viewmodelView(): D3D12GpuTextureView? = viewmodelTargetView

    fun attachment(color: Long, depth: Long): Long =
        if (attached && color == worldColor && depth == worldDepth) activeTarget?.handle() ?: 0L else 0L

    fun view(): D3D12GpuTextureView? = if (frame != null && frame == VanillaFrameCoordinator.currentIdentity()) targetView else null

    fun end(encoder: D3D12CommandEncoderBackend) {
        if (attached) encoder.contextControl { MCDR_ABI.D3D12SetRasterMotionTarget(it, 0L, 0L, 0L) }
        if (motionSpace == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
            D3D12RasterMotionCapture.endWorldFrame()
        }
        attached = false
    }

    fun discard() {
        frame = null
        worldColor = 0L
        worldDepth = 0L
        previousViewProjection = null
        activeTarget = null
        uploads.clear()
        neutral = null
        attached = false
    }

    fun bindings(memory: D3D12TransientMemory, buffer: GpuBuffer?, byteOffset: Long, stride: Int,
        vertexBase: Int, instanceCount: Int, viewportX: Int, viewportY: Int,
        viewportWidth: Int, viewportHeight: Int): D3D12MotionBindings {
        if (motionSpace == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
            D3D12RasterMotionCapture.recordWorldMotionBinding()
        }
        val inputs = VanillaFrameCoordinator.currentFrame()
        val generation = inputs?.historyGeneration
        val celestial = if (instanceCount == 1 && motionSpace == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE)
            CelestialMotionCapture.bindPrevious(buffer, byteOffset, stride, vertexBase) else null
        val history = buffer?.let { D3D12RasterMotionCapture.lookup(it, byteOffset, stride) }
            ?.takeIf { instanceCount == 1 && it.historyGeneration == generation &&
                previousViewProjection != null && it.space == motionSpace }
        if (history != null) D3D12RasterMotionCapture.bindHistory(history)
        if (history == null && celestial == null && motionSpace == D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) {
            D3D12RasterMotionCapture.invalidateMotion()
        }
        return uploadBindings(memory, RasterHistory(history, vertexBase, celestial),
            RasterViewport(viewportX, viewportY, viewportWidth, viewportHeight))
    }

    fun neutralBindings(memory: D3D12TransientMemory, viewportX: Int, viewportY: Int,
        viewportWidth: Int, viewportHeight: Int): D3D12MotionBindings {
        check(motionSpace == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
            "Neutral raster motion bindings require world capture"
        }
        D3D12RasterMotionCapture.recordWorldMotionBinding()
        return uploadBindings(memory, RasterHistory(),
            RasterViewport(viewportX, viewportY, viewportWidth, viewportHeight))
    }

    private fun uploadBindings(memory: D3D12TransientMemory, history: RasterHistory,
        viewport: RasterViewport): D3D12MotionBindings {
        val positions = history.vertices?.let { captured ->
            uploads[captured] ?: memory.allocateGpuMapped(captured.previousPositions.size * 4L, 256L,
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 0L, 16L).use { mapped ->
                mapped.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(captured.previousPositions)
                mapped.slice()
            }.also { uploads[captured] = it }
        } ?: neutral ?: memory.allocateGpuMapped(16L, 256L, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 0L, 16L)
            .use { mapped ->
                mapped.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatArrayOf(0f, 0f, 0f, 0f))
                mapped.slice()
            }.also { neutral = it }
        val parameters = memory.allocateGpuMapped(PARAMETER_BYTES, 256L, GpuBuffer.USAGE_UNIFORM, 0L, 1L)
            .use { mapped ->
                val data = mapped.data().order(ByteOrder.LITTLE_ENDIAN)
                data.asFloatBuffer().put(history.celestialTransform ?: previousViewProjection ?: FloatArray(16))
                data.putFloat(64, viewport.x.toFloat())
                data.putFloat(68, viewport.y.toFloat())
                data.putFloat(72, viewport.width.toFloat())
                data.putFloat(76, viewport.height.toFloat())
                data.putFloat(80, jitterX)
                data.putFloat(84, jitterY)
                data.putFloat(88, 1f)
                data.putFloat(92, 0f)
                data.putInt(96, if (history.vertices != null) history.vertexBase else 0)
                data.putInt(100, history.vertices?.vertexCount ?: 0)
                data.putInt(104, if (history.vertices != null || history.celestialTransform != null) 1 else 0)
                data.putInt(108, if (motionSpace == D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) 1 else 0)
                mapped.slice()
            }
        return D3D12MotionBindings(parameters, positions)
    }

    override fun close() {
        discard()
        targetView?.close()
        targetView = null
        target?.close()
        target = null
        viewmodelTargetView?.close()
        viewmodelTargetView = null
        viewmodelTarget?.close()
        viewmodelTarget = null
        owner = null
    }
}
