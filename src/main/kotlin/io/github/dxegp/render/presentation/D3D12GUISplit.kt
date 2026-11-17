package io.github.dxegp.render.presentation

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.FilterMode
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.joml.Vector4fc
import java.io.File
import java.util.ArrayDeque
import java.util.function.Consumer

class D3D12GUIRenderTarget(useStencil: Boolean) :
    RenderTarget("DXRMC GUI", GpuFormat.RGBA8_UNORM,
        if (useStencil) GpuFormat.D32_FLOAT_S8_UINT else GpuFormat.D32_FLOAT)

object D3D12GUISplit : AutoCloseable {
    private data class DisplayScreenshot(val directory: File, val callback: Consumer<Component>)

    private val compositePipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("dxr_mc", "pipeline/gui_composite"))
        .withVertexShader("core/screenquad")
        .withFragmentShader("core/blit_screen")
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        .build()
    private var target: D3D12GUIRenderTarget? = null
    private var owner: D3D12CommandEncoderBackend? = null
    private var composite: D3D12GUIRenderTarget? = null
    private var frameTarget: D3D12GUIRenderTarget? = null
    private var preparedThisFrame = false
    private var hudlessArmed = false
    private var hudlessPushed = false
    private var pushedUiHandle = 0L
    private var guiCompleted = false
    private var blurredBackdrop = false
    private var displayComposed = false
    private val screenshotRequests = ArrayDeque<DisplayScreenshot>()

    @JvmStatic
    fun isActive(): Boolean = frameTarget != null

    @JvmStatic
    fun isHudlessArmed(): Boolean = hudlessArmed

    @JvmStatic
    fun targetForFrame(): RenderTarget = frameTarget ?: ensureTarget()

    internal fun uiTextureForFrame(): D3D12GpuTexture? = (frameTarget?.getColorTexture() as? D3D12GpuTexture)?.takeIf {
        guiCompleted && !it.isClosed()
    }

    internal fun guiContainsSceneBackdrop(): Boolean = guiCompleted && blurredBackdrop

    private fun ensureTarget(): D3D12GUIRenderTarget {
        RenderSystem.assertOnRenderThread()
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.width
        val height = minecraft.window.height
        check(width > 0 && height > 0) { "GUI display extent is empty" }
        val existing = target
        if (existing != null && existing.width == width && existing.height == height) return existing
        if (pushedUiHandle != 0L) {
            check(MCDR_ABI.D3D12FgSetUiTexture(0L) == 1) { "Frame generation still borrows the previous GUI target" }
            pushedUiHandle = 0L
        }
        val created = D3D12GUIRenderTarget(minecraft.gameRenderer.mainRenderTarget().hasDepth())
        created.resize(width, height)
        val createdComposite = D3D12GUIRenderTarget(false)
        createdComposite.resize(width, height)
        target = created
        val oldComposite = composite
        composite = createdComposite
        existing?.destroyBuffers()
        oldComposite?.destroyBuffers()
        return created
    }

    internal fun beginFrame(clearColor: Vector4fc) {
        check(frameTarget == null) { "GUI frame was not retired" }
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorTexture(checkNotNull(ensureTarget().getColorTexture()), clearColor)
        guiCompleted = false
        blurredBackdrop = false
        displayComposed = false
        hudlessArmed = false
    }

    @JvmStatic
    fun prepareForGui() {
        check(!preparedThisFrame) { "GUI target was prepared twice" }
        val gui = ensureTarget()
        frameTarget = gui
        val encoderOwner = checkNotNull(D3D12Device.sharedCommandEncoder()) { "GUI has no command owner" }
        check(owner == null || owner === encoderOwner) { "GUI display changed command owner" }
        if (owner == null) {
            encoderOwner.retainUntilCommandsClosed(this)
            owner = encoderOwner
        }
        val depth = checkNotNull(gui.getDepthTexture() as? D3D12GpuTexture)
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "GUI has no command encoder" }
        encoder.clearDepthTexture(depth, 0.0)
        preparedThisFrame = true
    }

    @JvmStatic
    fun prepareBlurBackdrop() {
        if (!preparedThisFrame) return
        val scene = D3D12SceneDisplay.sceneTexture() ?: return
        check(!blurredBackdrop) { "GUI blur backdrop was prepared twice" }
        val output = checkNotNull(composite)
        composeSceneAndGui(scene, output)
        val source = checkNotNull(output.getColorTexture() as? D3D12GpuTexture)
        val destination = checkNotNull(frameTarget?.getColorTexture() as? D3D12GpuTexture)
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
        val version = checkNotNull(VanillaFrameCoordinator.currentResourceVersion())
        VanillaFrameCoordinator.recordDeclaredPresentationPass(0x20005, listOf(
            MCDR_ABI.VanillaResourceUse(source.handle(), version, access = 1, requiredState = 0xC0, finalState = 0x4),
            MCDR_ABI.VanillaResourceUse(destination.handle(), version, access = 2, requiredState = 0x4),
        )) {
            check(encoder.recordTextureBlit(source, destination)) { "GUI blur backdrop transfer failed" }
        }
        blurredBackdrop = true
    }

    internal fun completeGui() {
        check(preparedThisFrame && !guiCompleted) { "GUI completed without preparation or more than once" }
        val ui = checkNotNull(frameTarget?.getColorTexture() as? D3D12GpuTexture)
        check(!ui.isClosed()) { "Completed GUI texture is closed" }
        if (ui.handle() != pushedUiHandle) {
            check(MCDR_ABI.D3D12FgSetUiTexture(ui.handle()) == 1) { "Frame generation rejected GUI texture" }
            pushedUiHandle = ui.handle()
        }
        val scene = D3D12SceneDisplay.sceneTexture()
        val settings = VanillaFrameCoordinator.currentFrame()?.settings
        val wantsHudless = scene != null && !blurredBackdrop && settings != null &&
            settings.frameGenerationMode != 0 && settings.frameGenerationBackend == MCDR_ABI.FG_BACKEND_FSRFG
        check(MCDR_ABI.D3D12FgSetUiComposition(wantsHudless) == 1) { "Frame generation rejected GUI composition change" }
        hudlessPushed = wantsHudless
        hudlessArmed = wantsHudless
        guiCompleted = true
    }

    internal fun composeDisplay() {
        check(guiCompleted && !displayComposed) { "Display composition preceded GUI completion or ran twice" }
        val scene = D3D12SceneDisplay.sceneTexture()
        if (scene != null && !blurredBackdrop) composeSceneAndGui(scene, checkNotNull(composite))
        displayComposed = true
    }

    private fun composeSceneAndGui(scene: D3D12GpuTexture, output: D3D12GUIRenderTarget) {
        val destination = checkNotNull(output.getColorTexture() as? D3D12GpuTexture)
        val encoder = checkNotNull(D3D12Device.sharedCommandEncoder())
        val version = checkNotNull(VanillaFrameCoordinator.currentResourceVersion())
        VanillaFrameCoordinator.recordDeclaredPresentationPass(0x20006, listOf(
            MCDR_ABI.VanillaResourceUse(scene.handle(), version, access = 1, requiredState = 0xC0, finalState = 0x4),
            MCDR_ABI.VanillaResourceUse(destination.handle(), version, access = 2, requiredState = 0x4),
        )) {
            check(encoder.recordTextureBlit(scene, destination)) { "Scene transfer to final composite failed" }
        }
        val descriptor = RenderPassDescriptor.builder { "GUI over scene display" }
            .withColorAttachment(checkNotNull(output.getColorTextureView()))
            .build()
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor).use { pass ->
            pass.setPipeline(RenderSystem.getCompiledPipeline(compositePipeline))
            pass.setUniform("InSampler", checkNotNull(frameTarget?.getColorTextureView()),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
            pass.draw(3, 1, 0, 0)
        }
    }

    internal fun queueScreenshot(directory: File, callback: Consumer<Component>) {
        RenderSystem.assertOnRenderThread()
        screenshotRequests.addLast(DisplayScreenshot(directory, callback))
    }

    internal fun saveScreenshots() {
        if (screenshotRequests.isEmpty()) return
        val output = checkNotNull(completedDisplayTarget()) { "Screenshot preceded display composition" }
        val color = checkNotNull(output.getColorTexture()) { "Screenshot has no display color" }
        check(output.width == color.getWidth(0) && output.height == color.getHeight(0)) {
            "Screenshot extent differs from the completed display attachment"
        }
        while (screenshotRequests.isNotEmpty()) {
            val request = screenshotRequests.removeFirst()
            Screenshot.grab(request.directory, output, request.callback)
        }
    }

    internal fun completedDisplayTarget(): RenderTarget? {
        if (!displayComposed) return null
        return if (D3D12SceneDisplay.sceneTexture() == null || blurredBackdrop) frameTarget else composite
    }

    @JvmStatic
    fun resetForNoWorld() {
        discardFrame()
        if (hudlessPushed) check(MCDR_ABI.D3D12FgSetUiComposition(false) == 1) {
            "Frame generation still owns the GUI composition"
        }
        hudlessArmed = false
        hudlessPushed = false
    }

    @JvmStatic
    fun discardFrame() {
        frameTarget = null
        preparedThisFrame = false
        guiCompleted = false
        blurredBackdrop = false
        displayComposed = false
        hudlessArmed = false
    }

    @JvmStatic
    fun presentSourceTexture(fallback: D3D12GpuTexture): D3D12GpuTexture {
        val scene = D3D12SceneDisplay.sceneTexture()
        if (scene != null) {
            check(displayComposed) { "Scene presentation preceded completed display composition" }
            if (blurredBackdrop) return checkNotNull(uiTextureForFrame())
            if (hudlessArmed) {
                if (MCDR_ABI.D3D12FgUiCompositionReady() == 1) return scene
                check(MCDR_ABI.D3D12FgSetUiComposition(false) == 1) { "Frame generation rejected GUI fallback" }
                hudlessPushed = false
                hudlessArmed = false
            }
            return checkNotNull(composite?.getColorTexture() as? D3D12GpuTexture)
        }
        return uiTextureForFrame() ?: fallback
    }

    override fun close() {
        if (pushedUiHandle != 0L) check(MCDR_ABI.D3D12FgSetUiTexture(0L) == 1) {
            "Frame generation still borrows the GUI target at shutdown"
        }
        target?.destroyBuffers()
        composite?.destroyBuffers()
        target = null
        composite = null
        owner = null
        pushedUiHandle = 0L
        hudlessArmed = false
        hudlessPushed = false
        screenshotRequests.clear()
        discardFrame()
    }
}
