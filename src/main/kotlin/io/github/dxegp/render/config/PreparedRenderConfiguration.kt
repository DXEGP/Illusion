package io.github.dxegp.render.config

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.Illusion
import io.github.dxegp.config.SrBackend
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.render.antialiasing.D3D12AntiAliasing
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frame.vanilla.VanillaFrameWire
import io.github.dxegp.render.frameprep.FrameInputCoordinates
import io.github.dxegp.render.reconstruction.dlss.DeepLearningSuperSampling
import io.github.dxegp.render.resolution.PreparedWorldTarget
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import net.minecraft.client.Minecraft

internal class PreparedRenderConfiguration private constructor(
    private val configuration: RenderConfiguration.Applied,
    private val world: PreparedWorldTarget,
    private val resources: MutableList<AutoCloseable>,
) : AutoCloseable {
    private var srToken = 0L
    private var fgToken = 0L
    private var completionUnknown = false
    private var retiredWorld: AutoCloseable? = null
    private var closed = false
    val jitterSamples: Int get() = configuration.settings.jitterSamples

    fun publish(version: Long): RenderConfiguration.Applied? {
        check(!closed && srToken != 0L && fgToken != 0L)
        world.validateForPublication()
        when (val result = MCDR_ABI.D3D12CommitPreparedConfiguration(
            srToken, fgToken, configuration.settings.antiAliasing)) {
            0 -> return null
            1 -> Unit
            else -> error("Prepared configuration commit rejected: $result")
        }
        srToken = 0L
        fgToken = 0L
        retiredWorld = world.publish()
        return configuration.copy(version = version)
    }

    override fun close() {
        if (closed) return
        for ((token, abort) in listOf(srToken to MCDR_ABI::D3D12SrAbort, fgToken to MCDR_ABI::D3D12FgAbort)) {
            if (token == 0L) continue
            try {
                val result = abort(token)
                check(result == 0 || result == 1) { "Prepared configuration abort rejected: $result" }
                if (result == 1) {
                    if (token == srToken && abort == MCDR_ABI::D3D12SrAbort) srToken = 0L
                    else fgToken = 0L
                }
            } catch (failure: Exception) {
                Illusion.LOGGER.error("Could not retire prepared configuration token {}", token, failure)
            }
        }
        if (srToken != 0L || fgToken != 0L || completionUnknown) {
            if (this !in abandoned) abandoned.add(this)
            return
        }
        closed = true
        for (resource in listOfNotNull(retiredWorld) + resources.asReversed() + world) {
            try {
                resource.close()
            } catch (failure: Exception) {
                Illusion.LOGGER.error("Could not close render configuration resource", failure)
            }
        }
        resources.clear()
        retiredWorld = null
    }

    companion object {
        private val abandoned = mutableListOf<PreparedRenderConfiguration>()
        private var commandOwner: D3D12CommandEncoderBackend? = null
        private val retirement = AutoCloseable {
            check(retireAbandoned()) { "Prepared configuration still owns GPU resources" }
            commandOwner = null
        }

        fun retireAbandoned(): Boolean {
            val retiring = abandoned.iterator()
            while (retiring.hasNext()) {
                val candidate = retiring.next()
                candidate.close()
                if (!candidate.closed) return false
                retiring.remove()
            }
            return true
        }

        fun extentChanged(applied: RenderConfiguration.Applied): Boolean {
            if (!D3D12Device.isInitialized()) return false
            val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val color = target.colorTextureView?.texture() ?: return false
            return target.width != applied.displayWidth || target.height != applied.displayHeight ||
                color.getWidth(0) != applied.renderWidth || color.getHeight(0) != applied.renderHeight
        }

        fun displayExtent(): RenderConfiguration.DisplayExtent? {
            if (!D3D12Device.isInitialized()) return null
            val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            return if (target.width > 0 && target.height > 0)
                RenderConfiguration.DisplayExtent(target.width, target.height) else null
        }

        fun prepare(settings: RenderConfiguration.Settings,
            applied: RenderConfiguration.Applied): PreparedRenderConfiguration? {
            RenderSystem.assertOnRenderThread()
            if (!D3D12Device.isInitialized()) return null
            if (D3D12Device.sharedCommandEncoder() == null) RenderSystem.getDevice().createCommandEncoder()
            val encoder = checkNotNull(D3D12Device.sharedCommandEncoder()) { "Configuration preparation has no command owner" }
            check(commandOwner == null || commandOwner === encoder) { "Configuration candidates changed command owner" }
            if (commandOwner == null) {
                encoder.retainUntilCommandsClosed(retirement)
                commandOwner = encoder
            }
            if (!retireAbandoned()) return null
            val minecraft = Minecraft.getInstance()
            val target = minecraft.gameRenderer.mainRenderTarget()
            val displayWidth = target.width
            val displayHeight = target.height
            if (displayWidth <= 0 || displayHeight <= 0 || target.colorTextureView == null || target.depthTexture == null)
                return null
            val frame = VanillaFrameCoordinator.latestRecordedWorldFrame()?.takeIf { it.world === minecraft.level }
            if (settings.reconstructionEnabled && frame?.current?.perspective == null) return null
            val limit = RenderSystem.getDevice().deviceInfo.limits().maxTextureSize()
            val caps = if (settings.reconstructionEnabled) {
                checkNotNull(MCDR_ABI.querySrCaps(settings.srBackend.id)) { "SR capabilities are unavailable" }.also {
                    check(it.available) { "SR backend ${settings.srBackend} is unavailable: ${it.note}" }
                    require(displayWidth >= it.minRenderWidth && displayHeight >= it.minRenderHeight) {
                        "SR display extent ${displayWidth}x$displayHeight is below the backend input minimum"
                    }
                }
            } else null
            val scalePercent = if (caps == null) settings.renderScalePercent else maxOf(settings.renderScalePercent,
                ((caps.minRenderWidth * 100L + displayWidth - 1L) / displayWidth).toInt(),
                ((caps.minRenderHeight * 100L + displayHeight - 1L) / displayHeight).toInt()).coerceAtMost(100)
            val width = (displayWidth.toLong() * scalePercent / 100L).coerceIn(1L, limit.toLong()).toInt()
            val height = (displayHeight.toLong() * scalePercent / 100L).coerceIn(1L, limit.toLong()).toInt()
            if (caps != null) require(width >= caps.minRenderWidth && height >= caps.minRenderHeight &&
                width <= caps.maxRenderWidth && height <= caps.maxRenderHeight) {
                "SR input extent ${width}x$height is unsupported"
            }
            val samples = if (settings.srBackend == SrBackend.FSR || settings.srBackend == SrBackend.XESS)
                MCDR_ABI.D3D12SrJitterPhaseCount(settings.srBackend.id, width, displayWidth).takeIf { it > 0 }
                    ?: settings.jitterSamples else settings.jitterSamples
            val resources = mutableListOf<AutoCloseable>()
            val result = PreparedRenderConfiguration(RenderConfiguration.Applied(settings.copy(jitterSamples = samples), applied.version,
                width, height, displayWidth, displayHeight, scalePercent), PreparedWorldTarget.prepare(width, height), resources)
            try {
                Arena.ofConfined().use { arena ->
                    val fg = arena.allocate(MCDRLayout.FG_DISPATCH)
                    fg.set(JAVA_INT, MCDRLayout.FG_DISPATCH_DISPLAY_WIDTH, displayWidth)
                    fg.set(JAVA_INT, MCDRLayout.FG_DISPATCH_DISPLAY_HEIGHT, displayHeight)
                    fg.set(JAVA_INT, MCDRLayout.FG_DISPATCH_BACKEND, settings.frameGenerationBackend)
                    fg.set(JAVA_INT, MCDRLayout.FG_DISPATCH_MODE, when {
                        !settings.frameGenerationEnabled -> 0
                        settings.frameGenerationFrames == 0 -> 2
                        else -> 1
                    })
                    fg.set(JAVA_INT, MCDRLayout.FG_DISPATCH_NUM_FRAMES, settings.frameGenerationFrames.coerceAtLeast(1))
                    fg.set(JAVA_INT, MCDRLayout.FG_DISPATCH_RESET, 1)
                    val fgPreparation = MCDR_ABI.D3D12FgPrepare(fg)
                    result.fgToken = fgPreparation.retainedToken
                    when (fgPreparation) {
                        is MCDR_ABI.FgPreparation.Ready -> Unit
                        is MCDR_ABI.FgPreparation.NotReady -> { result.close(); return null }
                        is MCDR_ABI.FgPreparation.Rejected -> {
                            if (fgPreparation.reason == MCDR_ABI.FgPreparationFailure.COMPLETION_UNKNOWN &&
                                fgPreparation.retainedToken == 0L) result.completionUnknown = true
                            if (fgPreparation.reason == MCDR_ABI.FgPreparationFailure.RESTART_REQUIRED)
                                throw RenderConfiguration.RestartRequired()
                            error("FG configuration preparation ${fgPreparation.reason}: ${fgPreparation.detail}")
                        }
                    }
                    check(result.fgToken != 0L) { "FG configuration preparation returned no token" }
                    val sr = arena.allocate(MCDRLayout.SR_DISPATCH)
                    for ((field, value) in listOf("renderWidth" to width, "renderHeight" to height,
                        "displayWidth" to displayWidth, "displayHeight" to displayHeight,
                        "backend" to settings.srBackend.id, "reset" to 1,
                        "depthFormatOrdinal" to result.world.depth.format.ordinal))
                        sr.set(JAVA_INT, MCDRLayout.srDispatchOffset(field), value)
                    if (settings.reconstructionEnabled) {
                        val color = result.world.color as D3D12GpuTexture
                        val depth = result.world.depth as D3D12GpuTexture
                        val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or
                            GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or D3D12GpuTexture.USAGE_STORAGE
                        val output = D3D12GpuTexture(usage, "Prepared reconstruction output", color.format,
                            displayWidth, displayHeight, 1, 1).also(resources::add)
                        val motion = D3D12GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or
                            GpuTexture.USAGE_COPY_SRC, "Prepared reconstruction motion", GpuFormat.RG16_FLOAT,
                            width, height, 1, 1).also(resources::add)
                        val exposure = D3D12GpuTexture(GpuTexture.USAGE_TEXTURE_BINDING or D3D12GpuTexture.USAGE_STORAGE,
                            "Prepared reconstruction exposure", GpuFormat.R32_FLOAT, 1, 1, 1, 1).also(resources::add)
                        val reactive = if (settings.providerMasks && caps!!.takesMasks != 0)
                            D3D12GpuTexture(usage, "Prepared reconstruction mask", GpuFormat.R8_UNORM,
                                width, height, 1, 1).also(resources::add) else null
                        val composition = if (settings.providerMasks && caps!!.takesTransparencyAndCompositionMask)
                            D3D12GpuTexture(usage, "Prepared reconstruction composition mask", GpuFormat.R8_UNORM,
                                width, height, 1, 1).also(resources::add) else null
                        for ((field, texture) in listOf("pColorInput" to color, "pColorOutput" to output,
                            "pDepth" to depth, "pMotionVectors" to motion, "pExposure" to exposure,
                            "pReactiveMask" to reactive, "pTransparencyAndComposition" to composition))
                            sr.set(ADDRESS, MCDRLayout.srDispatchOffset(field),
                                MemorySegment.ofAddress(texture?.handle() ?: 0L))
                        sr.set(JAVA_INT, MCDRLayout.srDispatchOffset("hdrInput"),
                            if (color.format == GpuFormat.RGBA16_FLOAT) 1 else 0)
                        writeCamera(sr, checkNotNull(frame), result.configuration)
                        val camera = arena.allocate(MCDRLayout.RASTER_CAMERA)
                        VanillaFrameWire.camera(camera, frame.current)
                        sr.set(ADDRESS, MCDRLayout.srDispatchOffset("pCamera"), camera)
                    }
                    val context = MCDR_ABI.D3D12CreateCommandEncoder()
                    check(context != 0L) { "Could not create configuration command context" }
                    try {
                        val preparation = MCDR_ABI.D3D12SrPrepare(context, sr,
                            if (settings.antiAliasing == D3D12AntiAliasing.DLAA) 4 else settings.dlssQuality,
                            DeepLearningSuperSampling.renderPreset(settings.dlssPreset))
                        result.srToken = preparation.retainedToken
                        when (preparation) {
                            is MCDR_ABI.SrPreparation.Ready -> Unit
                            is MCDR_ABI.SrPreparation.NotReady -> { result.close(); return null }
                            is MCDR_ABI.SrPreparation.Rejected -> {
                                if (preparation.reason == MCDR_ABI.SrPreparationFailure.COMPLETION_UNKNOWN &&
                                    preparation.retainedToken == 0L) result.completionUnknown = true
                                throw RenderConfiguration.PreparationRejected(preparation.reason, preparation.detail)
                            }
                        }
                    } finally {
                        MCDR_ABI.D3D12DestroyCommandEncoder(context)
                    }
                }
                return result
            } catch (failure: Throwable) {
                result.close()
                throw failure
            }
        }

        private fun writeCamera(description: MemorySegment, frame: VanillaFrameInputs,
            configuration: RenderConfiguration.Applied) {
            val perspective = checkNotNull(frame.current.perspective)
            val jitter = FrameInputCoordinates.sdkJitter(frame.current.jitterX.toFloat(), frame.current.jitterY.toFloat())
            for ((field, value) in listOf("jitterOffsetX" to jitter[0], "jitterOffsetY" to jitter[1],
                "motionVectorScaleX" to FrameInputCoordinates.MOTION_SCALE,
                "motionVectorScaleY" to FrameInputCoordinates.MOTION_SCALE,
                "preExposure" to FrameInputCoordinates.PRE_EXPOSURE, "frameTimeDeltaMs" to frame.frameDeltaMs,
                "cameraNear" to perspective.nearPlane, "cameraFar" to perspective.farPlane,
                "cameraVerticalFov" to perspective.verticalFovRadians, "sharpness" to configuration.settings.sharpness / 100.0f,
                "cameraAspectRatio" to configuration.renderWidth.toFloat() / configuration.renderHeight,
                "viewSpaceToMetersFactor" to 1.0f))
                description.set(JAVA_FLOAT, MCDRLayout.srDispatchOffset(field), value)
        }
    }
}
