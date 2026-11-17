package io.github.dxegp.render.presentation

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import java.nio.ByteOrder

internal object LateWorldTargets : AutoCloseable {
    private class ProjectionScope(
        private val previous: GpuBufferSlice,
        private val previousType: ProjectionType,
    ) : AutoCloseable {
        override fun close() {
            RenderSystem.setProjectionMatrix(previous, previousType)
        }
    }

    private class WorldTarget(source: RenderTarget) : RenderTarget(
        "World frame target", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT,
    ) {
        init {
            routeTo(source)
        }

        fun routeTo(target: RenderTarget) {
            width = checkNotNull(target.getColorTexture()).getWidth(0)
            height = checkNotNull(target.getColorTexture()).getHeight(0)
            colorTexture = target.getColorTexture()
            colorTextureView = target.getColorTextureView()
            depthTexture = target.getDepthTexture()
            depthTextureView = target.getDepthTextureView()
        }

        override fun createBuffers(width: Int, height: Int) {
            error("World frame target borrows its attachments")
        }

        override fun resize(width: Int, height: Int) {
            error("World frame target cannot resize borrowed attachments")
        }

        override fun destroyBuffers() {
            colorTexture = null
            colorTextureView = null
            depthTexture = null
            depthTextureView = null
        }
    }

    private var owner: D3D12CommandEncoderBackend? = null
    private var frame: VanillaFrameInputs? = null
    private var worldTarget: WorldTarget? = null
    private var sceneActive = false
    private var alwaysOnTopDepth: RenderTarget? = null
    private var externalFrame: VanillaFrameInputs? = null
    private var projectionScope: ProjectionScope? = null

    fun importWorldTarget(source: RenderTarget): RenderTarget {
        RenderSystem.assertOnRenderThread()
        val inputs = checkNotNull(VanillaFrameCoordinator.currentFrame()) { "World target has no frozen frame" }
        check(frame == null) { "World target was imported twice" }
        frame = inputs
        sceneActive = false
        return WorldTarget(source).also { worldTarget = it }
    }

    fun activateSceneDisplay(inputs: VanillaFrameInputs) {
        check(frame === inputs && !sceneActive) { "Late world target belongs to another frame" }
        val target = checkNotNull(D3D12SceneDisplay.targetForFrame()) { "Opaque reconstruction has no display target" }
        check(target.width == inputs.displayWidth && target.height == inputs.displayHeight) {
            "Late world target differs from the frozen display extent"
        }
        checkNotNull(worldTarget).routeTo(target)
        sceneActive = true
        if (D3D12SceneDisplay.displayIsUnjittered(inputs)) {
            check(projectionScope == null) { "Late world projection was activated twice" }
            val previous = checkNotNull(RenderSystem.getProjectionMatrixBuffer()) {
                "Late world projection has no recorded world projection"
            }
            val previousType = RenderSystem.getProjectionType()
            val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) {
                "Late world projection has no command owner"
            }
            val projection = encoder.transientMemory().allocateGpuMapped(
                64L, 256L, GpuBuffer.USAGE_UNIFORM, 0L, 1L,
            ).use { mapped ->
                mapped.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(inputs.current.projection())
                mapped.slice()
            }
            projectionScope = ProjectionScope(previous, previousType)
            RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE)
        }
    }

    fun sceneTarget(): RenderTarget? = worldTarget.takeIf { sceneActive }

    fun externalTarget(original: RenderTarget): RenderTarget =
        if (externalFrame != null && externalFrame === frame)
            D3D12SceneDisplay.targetForFrame() ?: original else original

    fun <Result> recordExternalDraw(action: () -> Result): Result {
        val previous = externalFrame
        externalFrame = frame
        try {
            return action()
        } finally {
            externalFrame = previous.takeIf { it === frame }
        }
    }

    fun routeAlwaysOnTopDepth(original: RenderTarget): RenderTarget {
        if (!sceneActive) return original
        val inputs = checkNotNull(frame)
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Late world depth has no command owner" }
        check(owner == null || owner === encoder) { "Late world depth changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val existing = alwaysOnTopDepth
        if (existing != null && existing.width == inputs.displayWidth && existing.height == inputs.displayHeight) {
            return existing
        }
        val created = object : RenderTarget("Late world always-on-top depth", null, GpuFormat.D32_FLOAT) {}
        created.resize(inputs.displayWidth, inputs.displayHeight)
        alwaysOnTopDepth = created
        existing?.destroyBuffers()
        return created
    }

    fun discardFrame() {
        restoreWorldProjection()
        worldTarget?.destroyBuffers()
        worldTarget = null
        frame = null
        sceneActive = false
        externalFrame = null
    }

    fun restoreWorldProjection() {
        val scope = projectionScope ?: return
        projectionScope = null
        scope.close()
    }

    override fun close() {
        discardFrame()
        alwaysOnTopDepth?.destroyBuffers()
        alwaysOnTopDepth = null
        owner = null
    }
}
