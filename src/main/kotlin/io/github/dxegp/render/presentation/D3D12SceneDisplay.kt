package io.github.dxegp.render.presentation

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frame.vanilla.VanillaWorldOutput
import io.github.dxegp.render.reconstruction.ReconstructionFrameInputs
import io.github.dxegp.render.reconstruction.OpaqueReconstructionInputs
import io.github.dxegp.render.presentation.display.DisplayPostInputs
import io.github.dxegp.render.presentation.display.DisplayPostSources
import io.github.dxegp.render.presentation.display.DisplayPostKind
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalDouble

internal object D3D12SceneDisplay : AutoCloseable {
    internal enum class DepthMapping { SAME_GRID, RASTER_TO_DISPLAY, DISPLAY_TO_RASTER }
    private val depthPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("dxr_mc", "pipeline/scene_depth"))
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("dxr_mc", "core/scene_depth"))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
        .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, true))
        .withPushConstantSize(8)
        .build()

    internal val pipelines: List<RenderPipeline> = listOf(depthPipeline)
    private var target: RenderTarget? = null
    private var owner: D3D12CommandEncoderBackend? = null
    private var frame: VanillaFrameInputs? = null
    private var sealed = false
    private var samplingGrid = VanillaWorldOutput.SamplingGrid.CURRENT_RASTER
    private val effects = ArrayList<DisplayPostInputs>()

    fun targetForFrame(): RenderTarget? = target.takeIf { frame != null && !sealed }

    fun displayIsUnjittered(value: VanillaFrameInputs): Boolean {
        check(frame === value) { "Display sampling grid belongs to another frame" }
        return samplingGrid == VanillaWorldOutput.SamplingGrid.TEMPORAL_DISPLAY
    }

    internal fun pushSourceMapping(pass: RenderPass, value: VanillaFrameInputs, mapping: DepthMapping) {
        val direction = when (mapping) {
            DepthMapping.SAME_GRID -> 0f
            DepthMapping.RASTER_TO_DISPLAY -> 1f
            DepthMapping.DISPLAY_TO_RASTER -> -1f
        }
        val constants = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        constants.putFloat((direction * value.current.jitterX / value.identity.width).toFloat())
        constants.putFloat((-direction * value.current.jitterY / value.identity.height).toFloat())
        constants.flip()
        pass.pushConstants(constants)
    }

    fun ensureTarget(): RenderTarget {
        RenderSystem.assertOnRenderThread()
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Scene display has no command owner" }
        check(owner == null || owner === encoder) { "Scene display changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.width
        val height = minecraft.window.height
        check(width > 0 && height > 0) { "Scene display extent is empty" }
        val existing = target
        if (existing != null && existing.width == width && existing.height == height) return existing
        check(frame == null) { "Scene display resized during frame ${frame?.identity}" }
        val created = object : RenderTarget("DXRMC scene display", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT) {}
        try {
            created.resize(width, height)
        } catch (failure: Throwable) {
            try { created.destroyBuffers() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        target = created
        existing?.destroyBuffers()
        return created
    }

    fun prepareWorldOutput(value: VanillaFrameInputs) {
        if (frame === value) {
            check(!sealed) { "Scene display was sealed before world completion" }
            return
        }
        prepareOutput(value, null)
    }

    fun prepareOpaqueForOit(value: VanillaFrameInputs): RenderTarget {
        val opaque = checkNotNull(ReconstructionFrameInputs.capturedOpaque(value)) {
            "Opaque scene display has no current captured inputs"
        }
        prepareOutput(value, opaque)
        return checkNotNull(targetForFrame())
    }

    private fun prepareOutput(value: VanillaFrameInputs, opaque: OpaqueReconstructionInputs?) {
        check(frame == null && !sealed) { "Scene display was prepared twice" }
        val destination = ensureTarget()
        val color = checkNotNull(destination.getColorTexture() as? D3D12GpuTexture)
        val depth = checkNotNull(destination.getDepthTexture() as? D3D12GpuTexture)
        check(color.getWidth(0) == value.displayWidth && color.getHeight(0) == value.displayHeight) {
            "Scene display differs from frozen frame extent ${value.identity}"
        }
        val sourceDepth = opaque?.depth ?: ReconstructionFrameInputs.capturedDepth()
            ?: error("World depth is unavailable at scene display preparation")
        check(sourceDepth.getWidth(0) == value.identity.width && sourceDepth.getHeight(0) == value.identity.height &&
            depth.getWidth(0) == value.displayWidth && depth.getHeight(0) == value.displayHeight) {
            "World/post depth transfer differs from frozen source or display extent ${value.identity}"
        }
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
        samplingGrid = if (opaque != null) VanillaWorldOutput.recordOpaque(value, encoder, opaque)
        else VanillaWorldOutput.record(value, encoder)
        val mapping = if (samplingGrid == VanillaWorldOutput.SamplingGrid.TEMPORAL_DISPLAY)
            DepthMapping.RASTER_TO_DISPLAY else DepthMapping.SAME_GRID
        recordDepthTransfer(encoder, value, sourceDepth, checkNotNull(destination.getDepthTextureView()), mapping)
        frame = value
        effects.clear()
    }

    internal fun recordDepthTransfer(encoder: D3D12CommandEncoderBackend, value: VanillaFrameInputs,
        source: D3D12GpuTexture, destination: GpuTextureView, mapping: DepthMapping) {
        val target = destination.texture() as D3D12GpuTexture
        check(!source.isClosed() && !destination.isClosed() && source !== target) {
            "World depth transfer has invalid or aliased attachments"
        }
        RenderSystem.getDevice().createTextureView(source).use { sourceView ->
            encoder.recordCommands {
                val descriptor = RenderPassDescriptor.builder { "World depth sampling transfer" }
                    .withDepthAttachment(destination, OptionalDouble.of(0.0))
                    .build()
                RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor).use { pass ->
                    pass.setPipeline(RenderSystem.getCompiledPipeline(depthPipeline))
                    pushSourceMapping(pass, value, mapping)
                    pass.setUniform("InSampler", sourceView,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
                    pass.draw(3, 1, 0, 0)
                }
            }
        }
    }

    fun recordPostEffect(effect: net.minecraft.client.renderer.PostChain) {
        check(frame != null && !sealed) { "Post effect arrived outside scene display recording" }
        effects.add(DisplayPostSources.describe(effect))
    }

    fun recordPostFailure(effect: net.minecraft.client.renderer.PostChain) {
        check(frame != null && !sealed) { "Post failure arrived outside scene display recording" }
        effects.add(DisplayPostInputs(effect.id().toString(), DisplayPostKind.UNAVAILABLE, FloatArray(16), FloatArray(16)))
    }

    fun postEffects(): List<DisplayPostInputs> = effects.toList()

    fun sealScene(value: VanillaFrameInputs) {
        check(frame === value && !sealed) { "Scene display seal belongs to another frame" }
        sealed = true
    }

    fun sceneTexture(): D3D12GpuTexture? = (target?.getColorTexture() as? D3D12GpuTexture)?.takeIf {
        frame != null && sealed && !it.isClosed()
    }

    fun discardFrame() {
        frame = null
        sealed = false
        samplingGrid = VanillaWorldOutput.SamplingGrid.CURRENT_RASTER
        effects.clear()
    }

    override fun close() {
        discardFrame()
        target?.destroyBuffers()
        target = null
        owner = null
    }
}
