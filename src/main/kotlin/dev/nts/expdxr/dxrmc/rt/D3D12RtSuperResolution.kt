package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.SrBackend
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.extern.MCDRLayout
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

internal object D3D12RtSuperResolution {
    private const val SOURCE = "rt_nrd_remodulate.hlsl"
    private const val ENTRY = "RtNrdRemodulate"
    private const val SRV_COUNT = 6
    private const val UAV_COUNT = 2
    private const val THREADS = 8
    private const val TEXT_CAPACITY = 256
    private const val USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_TEXTURE_BINDING

    private var color: D3D12GpuTexture? = null
    private var output: D3D12GpuTexture? = null
    private var deviceDepth: D3D12GpuTexture? = null
    private var pipeline = 0L
    private var frameIndex = 0
    private var lastPlan: D3D12RtPipelinePlan.Plan? = null
    private var lastFrameNanos = 0L
    private var available = true

    fun byteCount(): Long = listOfNotNull(color, output).sumOf {
        it.getWidth(0).toLong() * it.getHeight(0) * 8L
    } + (deviceDepth?.let { it.getWidth(0).toLong() * it.getHeight(0) * 4L } ?: 0L)

    fun reset() {
        available = true
        lastPlan = null
        lastFrameNanos = 0L
    }

    fun reconstruct(
        encoder: D3D12CommandEncoderBackend,
        denoised: D3D12GpuTexture,
        denoisedSpecular: D3D12GpuTexture,
        albedo: D3D12GpuTexture,
        specularMaterial: D3D12GpuTexture,
        emissive: D3D12GpuTexture,
        viewZ: D3D12GpuTexture,
        motion: D3D12GpuTexture,
        plan: D3D12RtPipelinePlan.Plan,
        constants: FloatArray,
        cbv: Long,
        cbvOffset: Long,
        resetHistory: Boolean,
        exposure: Long = 0L,
    ): D3D12GpuTexture? {
        val changed = lastPlan != plan
        val reset = changed || resetHistory
        if (changed) {
            available = true
            lastPlan = plan
            lastFrameNanos = 0L
        }
        if (!available || !plan.superResolution) return null
        if (!ensureTargets(encoder, plan) || !ensurePipeline()) return null
        val source = color ?: return null
        val target = output ?: return null
        val srDepth = deviceDepth ?: return null
        encoder.ensureListOpen()
        val remodulateSrvs = longArrayOf(denoised.handle(), albedo.handle(), emissive.handle(), viewZ.handle(),
            denoisedSpecular.handle(), specularMaterial.handle())
        val remodulateUavs = longArrayOf(source.handle(), srDepth.handle())
        if (!MCDR_ABI.D3D12DispatchComputeEx(
                encoder.nativeHandle(), pipeline, remodulateSrvs, remodulateUavs,
                depthProjection(constants, plan.upscaler) + intArrayOf(constants[68].toRawBits(), constants[69].toRawBits()),
                cbv, cbvOffset,
                (plan.renderWidth + THREADS - 1) / THREADS, (plan.renderHeight + THREADS - 1) / THREADS, 1,
            )) return fail("remodulation failed")
        D3D12RtStats.onDispatches(1)
        frameIndex += 1
        val now = System.nanoTime()
        val frameTimeMs = if (lastFrameNanos == 0L) 16.7f else
            ((now - lastFrameNanos) / 1_000_000f).coerceIn(1f, 1000f)
        lastFrameNanos = now
        val result = Arena.ofConfined().use { arena ->
            val textures = longArrayOf(source.handle(), target.handle(), srDepth.handle(), motion.handle())
            if (plan.upscaler == SrBackend.DLSS) {
                val matrices = arena.allocate(D3D12RtFrameConstants.SL_CONSTANTS_FLOATS.toLong() * Float.SIZE_BYTES, 16L)
                for (index in 0 until D3D12RtFrameConstants.SL_CONSTANTS_FLOATS) {
                    matrices.setAtIndex(ValueLayout.JAVA_FLOAT, index.toLong(), constants[index])
                }
                val text = arena.allocate(TEXT_CAPACITY.toLong())
                val accepted = MCDR_ABI.D3D12RtSrEvalExposure(
                    encoder.nativeHandle(), if (exposure == 0L) textures else textures + exposure,
                    plan.renderWidth, plan.renderHeight, plan.displayWidth, plan.displayHeight,
                    frameIndex, reset, matrices, floatArrayOf(1f, 1f), text, TEXT_CAPACITY,
                )
                val diagnostic = text.getString(0)
                if (accepted <= 0) return fail(diagnostic.ifBlank { "evaluation unavailable" })
                if (changed) IllusionLogger.infoMessage(diagnostic.ifBlank { "rt sr: evaluation accepted" })
                accepted
            } else {
                val desc = arena.allocate(MCDRLayout.SR_DISPATCH)
                writeDispatch(desc, plan, constants, textures, reset, frameTimeMs)
                val accepted = MCDR_ABI.D3D12SrEvaluate(encoder.nativeHandle(), desc)
                val backend = plan.upscaler.name.lowercase()
                if (accepted != 1) return fail("$backend evaluation unavailable ($accepted)")
                if (changed) IllusionLogger.infoMessage("rt sr: $backend evaluation accepted")
                accepted
            }
        }
        D3D12RtStats.onDispatches(result)
        return target
    }

    fun depthProjection(constants: FloatArray, upscaler: SrBackend): IntArray {
        val reverse = (upscaler == SrBackend.XESS || upscaler == SrBackend.FSR) && constants[10] < 0f
        val depthScale = if (reverse) constants[11] - constants[10] else constants[10]
        val depthOffset = if (reverse) constants[15] - constants[14] else constants[14]
        return intArrayOf(depthScale.toRawBits(), depthOffset.toRawBits(),
            constants[11].toRawBits(), constants[15].toRawBits())
    }

    fun writeDispatch(
        desc: MemorySegment,
        plan: D3D12RtPipelinePlan.Plan,
        constants: FloatArray,
        textures: LongArray,
        reset: Boolean,
        frameTimeMs: Float,
    ) {
        desc.fill(0)
        fun putInt(name: String, value: Int) = desc.set(ValueLayout.JAVA_INT,
            MCDRLayout.offset(MCDRLayout.SR_DISPATCH, name), value)
        fun putFloat(name: String, value: Float) = desc.set(ValueLayout.JAVA_FLOAT,
            MCDRLayout.offset(MCDRLayout.SR_DISPATCH, name), value)
        putInt("renderWidth", plan.renderWidth)
        putInt("renderHeight", plan.renderHeight)
        putInt("displayWidth", plan.displayWidth)
        putInt("displayHeight", plan.displayHeight)
        putFloat("motionVectorScaleX", 1f)
        putFloat("motionVectorScaleY", 1f)
        putFloat("jitterOffsetX", constants[68])
        putFloat("jitterOffsetY", constants[69])
        putFloat("preExposure", 1f)
        putFloat("frameTimeDeltaMs", frameTimeMs)
        putFloat("cameraNear", constants[64])
        putFloat("cameraFar", constants[65])
        putFloat("cameraVerticalFov", constants[66])
        putInt("reset", if (reset) 1 else 0)
        putInt("hdrInput", 1)
        putInt("backend", plan.upscaler.id)
        putInt("flags", MCDR_ABI.SR_FLAG_UNJITTERED_MV)
        for ([index, name] in listOf("pColorInput", "pColorOutput", "pDepth", "pMotionVectors").withIndex()) {
            desc.set(ValueLayout.ADDRESS, MCDRLayout.offset(MCDRLayout.SR_DISPATCH, name),
                MemorySegment.ofAddress(textures[index]))
        }
        putInt("depthFormatOrdinal", GpuFormat.R32_FLOAT.ordinal)
        putFloat("cameraAspectRatio", constants[67])
        putFloat("viewSpaceToMetersFactor", 1f)
    }

    private fun ensurePipeline(): Boolean {
        if (pipeline != 0L) return true
        val source = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
        val bytecode = MCDR_ABI.D3D12CompileShaderDxc(source, ENTRY, "cs_6_8") ?: run {
            fail("remodulation compilation failed")
            return false
        }
        pipeline = MCDR_ABI.D3D12CreateComputePipelineEx(bytecode, SRV_COUNT, UAV_COUNT)
        if (pipeline == 0L) {
            fail("remodulation pipeline failed")
            return false
        }
        return true
    }

    private fun ensureTargets(encoder: D3D12CommandEncoderBackend, plan: D3D12RtPipelinePlan.Plan): Boolean {
        val currentColor = color
        val currentOutput = output
        val currentDepth = deviceDepth
        if (currentColor != null && currentOutput != null && currentDepth != null &&
            currentColor.getWidth(0) == plan.renderWidth && currentColor.getHeight(0) == plan.renderHeight &&
            currentOutput.getWidth(0) == plan.displayWidth && currentOutput.getHeight(0) == plan.displayHeight &&
            currentDepth.getWidth(0) == plan.renderWidth && currentDepth.getHeight(0) == plan.renderHeight) return true
        val device = encoder.device ?: return false
        val allocated = mutableListOf<D3D12GpuTexture>()
        fun allocate(name: String, format: GpuFormat, width: Int, height: Int, usage: Int = USAGE): D3D12GpuTexture {
            val texture = checkNotNull(device.createTexture(name, usage, format, width, height, 1, 1)
                as? D3D12GpuTexture) { "$name allocation failed" }
            allocated += texture
            return texture
        }
        val created = try {
            val source = allocate("rt-sr-color", GpuFormat.RGBA16_FLOAT, plan.renderWidth, plan.renderHeight)
            val target = allocate("rt-sr-output", GpuFormat.RGBA16_FLOAT, plan.displayWidth, plan.displayHeight,
                USAGE or GpuTexture.USAGE_RENDER_ATTACHMENT)
            val depth = allocate("rt-sr-depth", GpuFormat.R32_FLOAT, plan.renderWidth, plan.renderHeight)
            Triple(source, target, depth)
        } catch (failure: Throwable) {
            allocated.forEach { it.close() }
            fail("target allocation failed")
            return false
        }
        color = created.first
        output = created.second
        deviceDepth = created.third
        currentColor?.close()
        currentOutput?.close()
        currentDepth?.close()
        return true
    }

    private fun fail(reason: String): D3D12GpuTexture? {
        if (available) IllusionLogger.warnMessage("rt sr: ${reason.removePrefix("rt sr: ")}")
        available = false
        return null
    }

    fun release() {
        color?.close()
        output?.close()
        deviceDepth?.close()
        color = null
        output = null
        deviceDepth = null
        if (pipeline != 0L) MCDR_ABI.ID3D12DestroyResource(pipeline)
        pipeline = 0L
        reset()
    }
}
