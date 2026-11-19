package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendOp
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.frontend.FrontendRenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12AdditionalRasterBindings
import io.github.dxegp.d3d12.command.D3D12AdditionalRasterAttachment
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.command.D3D12RasterRoute
import io.github.dxegp.d3d12.command.D3D12RasterAttachmentRole
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.FrameProgramsException
import io.github.dxegp.d3d12.ffi.FrameProgramsFailure
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.d3d12.shader.D3D12OitGuideSource
import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputResources
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frame.vanilla.PreparedBinding
import io.github.dxegp.render.frameprep.MotionGuideCoverage
import io.github.dxegp.render.reconstruction.OpaqueReconstructionInputs
import io.github.dxegp.render.reconstruction.ReconstructionFrameInputs
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.temporal.D3D12RasterMotion
import io.github.dxegp.render.temporal.D3D12Velocity
import org.joml.Matrix4f
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.oit.OitStage
import net.minecraft.resources.Identifier
import java.util.Optional

internal class ReconstructionTemporalInputs(
    val frame: VanillaFrameInputs,
    val depth: D3D12GpuTexture,
    val motion: D3D12GpuTexture,
    val guideCoverage: MotionGuideCoverage,
)

internal class PendingOitContributionResolve(
    val frame: VanillaFrameInputs,
    val transparency: GpuTextureView,
    val pipeline: CompiledRenderPipeline,
    val transparencySampler: String,
)

internal object OitReconstructionInputs : AutoCloseable {
    private var lastTileStatus: String? = null
    val compositePipeline: RenderPipeline = RenderPipeline.builder(RenderPipelines.OIT_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("dxr_mc", "pipeline/oit_reconstruction_composite"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("dxr_mc", "core/oit_reconstruction_composite"))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_OIT_COEFFS_DEPTH_BOUNDS_SAMPLER)
        .withColorTargetState(ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
            GpuFormat.RGBA8_UNORM, 15))
        .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, true))
        .withPushConstantSize(8)
        .build()

    private class RecordResolveAttachments(val width: Int, val height: Int) : AutoCloseable {
        private val usage = GpuTexture.USAGE_TEXTURE_BINDING or D3D12GpuTexture.USAGE_STORAGE
        val correctedTransparency = D3D12GpuTexture(usage, "OIT corrected transparency",
            GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
        val status = try {
            D3D12GpuTexture(usage, "OIT contribution status", GpuFormat.R32_UINT, width, height, 1, 1)
        } catch (failure: Throwable) {
            try { correctedTransparency.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        val targets = OitContributionResolveTargets(PreparedBinding(correctedTransparency.handle()),
            PreparedBinding(status.handle()))
        private var correctedView: D3D12GpuTextureView? = null

        fun correctedTransparencyView(): D3D12GpuTextureView = correctedView ?: D3D12GpuTextureView(
            correctedTransparency, 0, 1).also { correctedView = it }

        override fun close() {
            var failure: Throwable? = null
            for (resource in listOfNotNull(correctedView, correctedTransparency, status)) {
                try { resource.close() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            correctedView = null
            failure?.let { throw it }
        }
    }

    private class NormalizedTransparency(val width: Int, val height: Int) : AutoCloseable {
        val texture = D3D12GpuTexture(GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
            "OIT four-lane transparency", GpuFormat.RGBA32_FLOAT, width, height, 1, 1)
        val view = try {
            D3D12GpuTextureView(texture, 0, 1)
        } catch (failure: Throwable) {
            try { texture.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }

        override fun close() {
            try { view.close() } finally { texture.close() }
        }
    }

    private class Attachments(val width: Int, val height: Int) : AutoCloseable {
        private val usage = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING
        val contribution: D3D12GpuTexture
        val cloud: D3D12GpuTexture
        val contributionView: D3D12GpuTextureView
        val cloudView: D3D12GpuTextureView
        init {
            val resources = ArrayList<AutoCloseable>()
            try {
                contribution = D3D12GpuTexture(usage, "OIT visible contributions", GpuFormat.R8_UNORM,
                    width, height, 1, 1).also(resources::add)
                cloud = D3D12GpuTexture(usage, "Cloud temporal candidate", GpuFormat.RGBA32_FLOAT,
                    width, height, 1, 1).also(resources::add)
                contributionView = D3D12GpuTextureView(contribution, 0, 1).also(resources::add)
                cloudView = D3D12GpuTextureView(cloud, 0, 1).also(resources::add)
            } catch (failure: Throwable) {
                for (resource in resources.asReversed()) {
                    try { resource.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
        override fun close() {
            var failure: Throwable? = null
            for (resource in listOf(contributionView, cloudView, contribution, cloud)) {
                try { resource.close() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            failure?.let { throw it }
        }
    }

    private var owner: D3D12CommandEncoderBackend? = null
    private var attachments: Attachments? = null
    private var frame: VanillaFrameInputs? = null
    private var cloudDraw: Any? = null
    private var drawnCloud: OitCloudPreparation? = null
    private var parameters: com.mojang.renderpearl.api.buffers.GpuBufferSlice? = null
    private var incomplete = false
    private var recordedOit = false
    private var frameResolveProgram: ResolveProgram? = null
    private var recordFrames: OitRecordFrames? = null
    private var unrecordedCoverage: D3D12GpuTexture? = null
    private var recordResolveAttachments: RecordResolveAttachments? = null
    private var normalizedTransparency: NormalizedTransparency? = null
    private var compositeDisplay: OitCompositeDisplayConfirmation? = null
    internal var pendingContributionResolve: PendingOitContributionResolve? = null
        private set
    internal var drawCapture: OitSurfaceRecordCapture? = null
        private set

    internal fun terrainRecordFrames(): OitRecordFrames? = recordFrames

    internal class ResolveProgram(val generation: Long, private var pipeline: Long) : AutoCloseable {
        val handle: Long get() = pipeline.also { check(it != 0L) { "OIT resolve program is closed" } }

        override fun close() {
            val retired = pipeline
            pipeline = 0L
            if (resolveProgram === this) resolveProgram = null
            if (retired != 0L) MCDR_ABI.D3D12DestroyPipeline(retired)
        }
    }

    private var resolveProgram: ResolveProgram? = null

    fun isCloudSource(source: String): Boolean {
        val injectedDefine = Regex("^#define (?:OIT(?:_[A-Z_]+)?|RENDERPEARL_DEPTH_IS_ZERO_TO_ONE)(?:\\s.*)?$")
        val original = source.replace("\r\n", "\n").lineSequence()
            .filterNot { injectedDefine.matches(it) }.joinToString("\n").trim()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(original.toByteArray(Charsets.UTF_8)))
        return hash == "769783b6cfc6efa53a455bcbbee3291a7d33b2e3194b7f767db1b38c8b40a68c"
    }

    fun colorTarget(source: D3D12OitGuideSource): MCDRPipelineDesc.ColorTarget {
        return when (source) {
            D3D12OitGuideSource.CLOUD -> MCDRPipelineDesc.ColorTarget().format(GpuFormat.RGBA32_FLOAT.ordinal).writeMask(15)
            D3D12OitGuideSource.CONTRIBUTION, D3D12OitGuideSource.UNRECORDED ->
                MCDRPipelineDesc.ColorTarget().format(GpuFormat.R8_UNORM.ordinal)
                .writeMask(1).blend(BlendFactor.ONE.ordinal, BlendFactor.ONE.ordinal, BlendOp.MAX.ordinal,
                    BlendFactor.ONE.ordinal, BlendFactor.ONE.ordinal, BlendOp.MAX.ordinal)
        }
    }

    fun begin(encoder: D3D12CommandEncoderBackend, inputs: VanillaFrameInputs) {
        check(frame == null) { "OIT inputs began before their previous frame retired" }
        if (!reconstructionRequired(inputs)) {
            invalidate()
            return
        }
        check(owner == null || owner === encoder) { "OIT inputs changed command owner" }
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        frame = inputs
        OitSurfaceInputs.beginFrame(inputs)
        frameResolveProgram = resolveProgram
        if (frameResolveProgram != null) {
            encoder.recordCommands { context ->
                val result = MCDR_ABI.D3D12VanillaArmWorldDepthSeedV1(
                    context, MAX_OPAQUE_SEED_EVENTS, RECORD_LIMITS.totalBudgetBytes,
                )
                if (result != 1 && result != -3) throwOpaqueSeedFailure(result, "arm opaque seed", inputs)
            }
        }
        drawnCloud = null
        parameters = null
        incomplete = false
        recordedOit = false
    }

    fun captureOpaqueAndBeginRecords(encoder: D3D12CommandEncoderBackend, minecraft: Minecraft) {
        val inputs = frame ?: return
        check(inputs === VanillaFrameCoordinator.currentFrame() && owner === encoder) {
            "OIT opaque capture belongs to another frame or command owner"
        }
        encoder.recordCommands { context ->
            when (val result = MCDR_ABI.D3D12VanillaSealWorldDepthSeedV1(context)) {
                1, 0, -3, -6, -7 -> Unit
                else -> throwOpaqueSeedFailure(result, "seal opaque seed", inputs)
            }
        }
        val opaque = ReconstructionFrameInputs.captureOpaque(encoder, minecraft)
        OitProducerCapture.beginFrame(inputs)
        if (opaque == null || !beginRecords(encoder, opaque, RECORD_LIMITS)) {
            val identity = inputs.identity
            val existing = attachments
            val current = if (existing != null && existing.width == identity.width && existing.height == identity.height)
                existing else Attachments(identity.width, identity.height).also {
                    attachments = it
                    existing?.close()
                }
            encoder.recordCommands { context ->
                val uses = listOf(current.contribution, current.cloud).map {
                    VanillaFrameInputResources.texture(it.handle(), identity.resourceVersion, 2,
                        VanillaFrameInputResources.STATE_RENDER_TARGET)
                }
                VanillaFrameCoordinator.declaredPass(context, 0x3000B, uses, graphicsEffects = 0) {
                    MCDR_ABI.D3D12ClearColorTexture(context, current.contribution.handle(), 0f, 0f, 0f, 0f)
                    MCDR_ABI.D3D12ClearColorTexture(context, current.cloud.handle(), 0f, 0f, 0f, 0f)
                }
            }
            return
        }
        val legacy = attachments
        attachments = null
        legacy?.close()
    }

    private fun resolveAccumulatedRecords(records: OitRecordFrames, call: OitOriginalCompositeCall,
        input: OitCompositeInputCapture): PendingOitContributionResolve {
        val view = checkNotNull(call.accumulate as? D3D12GpuTextureView) {
            "OIT accumulate input is not a D3D12 texture view"
        }
        val texture = view.texture() as D3D12GpuTexture
        val identity = records.frame.identity
        check(!view.isClosed() && !texture.isClosed() && view.baseMip() == 0 &&
            texture.format == GpuFormat.RGBA16_FLOAT && texture.getWidth(0) == identity.width &&
            texture.getHeight(0) == identity.height) { "OIT accumulate input differs from the frozen frame" }
        val capture = checkNotNull(drawCapture) { "OIT record capture was already sealed" }
        val availability = if (OitProducerCapture.frameResult()?.failed != false)
            OitUnrecordedCoverageAvailability.UNAVAILABLE else capture.coverageAvailability
        val coverage = OitUnrecordedCoverage(PreparedBinding(checkNotNull(unrecordedCoverage).handle()), availability)
        drawCapture = null
        val stream = records.seal(input)
        if (stream is OitNativeExecutionResult.Sealed) {
            val capturedBinning = OitTerrainRecords.tileBinning
            val binning = capturedBinning ?: (RenderSystem.getCompiledPipeline(RenderPipelines.OIT_TERRAIN
                .getPipeline(OitStage.ACCUMULATE)) as? FrontendRenderPipeline)?.let {
                (it.backendRenderPipeline() as? D3D12CompiledRenderPipeline)?.terrainTileBinning()
            }
            if (binning != null) {
                val pipeline = RenderSystem.getCompiledPipeline(OitTileNormalizationPrograms.normalizedCompositePipeline)
                val nativePipeline = (pipeline as? FrontendRenderPipeline)?.backendRenderPipeline() as? D3D12CompiledRenderPipeline
                check(nativePipeline?.isValid() == true && !pipeline.isClosed()) { "OIT normalized Composite pipeline is unavailable" }
                val normalized = normalizedTransparency?.takeIf {
                    it.width == identity.width && it.height == identity.height
                } ?: NormalizedTransparency(identity.width, identity.height).also {
                    val old = normalizedTransparency
                    normalizedTransparency = it
                    old?.close()
                }
                if (records.executeTiles(stream, call.coefficients.size, binning, normalized.texture.handle())) {
                    if (lastTileStatus != "active") {
                        lastTileStatus = "active"
                        IllusionLogger.debugMessage("OIT tile composite active")
                    }
                    return PendingOitContributionResolve(records.frame, normalized.view, pipeline, "NormalizedTransparencySampler")
                }
            }
        } else if (stream is OitNativeExecutionResult.Unavailable && IllusionLogger.debugEnabled()) {
            val inputFailure = input as? OitCompositeInputCapture.Unavailable
            val status = "${stream.reason} (${stream.missingMask.toString(16)})"
            if (lastTileStatus != status) {
                lastTileStatus = status
                IllusionLogger.debugFormat("OIT tile unavailable: %s (event %d)", status, stream.firstIssueSequence)
                if (inputFailure != null)
                    IllusionLogger.debugFormat("OIT Composite input: %s (%s)", inputFailure.reason,
                        inputFailure.contentFailure?.detail ?: inputFailure.certification)
            }
        }
        val existing = recordResolveAttachments
        val output = if (existing != null && existing.width == identity.width && existing.height == identity.height)
            existing else RecordResolveAttachments(identity.width, identity.height).also {
                recordResolveAttachments = it
                existing?.close()
            }
        val transparency = if (records.resolveRecords(PreparedBinding(texture.handle()), coverage, output.targets)) {
            output.correctedTransparencyView()
        } else {
            call.accumulate
        }
        return PendingOitContributionResolve(records.frame, transparency,
            RenderSystem.getCompiledPipeline(compositePipeline), "Sampler0")
    }

    fun prepareComposite(call: OitOriginalCompositeCall): RenderTarget? {
        val records = recordFrames ?: return null
        check(pendingContributionResolve == null) { "OIT composite was prepared twice" }
        checkNotNull(owner)
        check(records.frame === frame && records.frame === VanillaFrameCoordinator.currentFrame()) {
            "OIT composite belongs to another world frame"
        }
        val originalPipeline = RenderSystem.getCompiledPipeline(RenderPipelines.OIT_COMPOSITE)
        val input = OitCompositeNormalizationInput.capture(records, call, originalPipeline)
        val resolved = resolveAccumulatedRecords(records, call, input)
        pendingContributionResolve = resolved
        val scene = D3D12SceneDisplay.prepareOpaqueForOit(records.frame)
        compositeDisplay = OitCompositeDisplayConfirmation(records.frame, call, originalPipeline, scene,
            resolved.transparency, resolved.transparencySampler)
        return scene
    }

    fun correctedTransparencyView(): GpuTextureView? {
        val pending = pendingContributionResolve ?: return null
        check(pending.frame === VanillaFrameCoordinator.currentFrame()) { "OIT corrected input belongs to another frame" }
        return pending.transparency
    }

    fun compositeSampler(name: String): String? = when (pendingContributionResolve?.transparencySampler) {
        "NormalizedTransparencySampler" -> when {
            name == "Sampler0" -> "NormalizedTransparencySampler"
            name.startsWith("Coeff") -> null
            else -> name
        }
        else -> name
    }

    fun bindComposite(pass: RenderPass, originalPipeline: CompiledRenderPipeline) {
        val pending = checkNotNull(pendingContributionResolve)
        val inputs = pending.frame
        check(inputs === VanillaFrameCoordinator.currentFrame()) { "OIT composite bindings belong to another frame" }
        val selected = pending.pipeline
        pass.setPipeline(selected)
        val mapping = if (D3D12SceneDisplay.displayIsUnjittered(inputs))
            D3D12SceneDisplay.DepthMapping.RASTER_TO_DISPLAY else D3D12SceneDisplay.DepthMapping.SAME_GRID
        D3D12SceneDisplay.pushSourceMapping(pass, inputs, mapping)
        compositeDisplay?.confirmPipeline(pass, originalPipeline, selected, mapping)
    }

    fun confirmCompositePipeline(pass: RenderPass, pipeline: com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline) {
        compositeDisplay?.confirmPipeline(pass, pipeline, pipeline, D3D12SceneDisplay.DepthMapping.SAME_GRID)
    }

    fun confirmCompositeBinding(pass: RenderPass, name: String, original: GpuTextureView,
        effectiveName: String?, effective: GpuTextureView, sampler: com.mojang.renderpearl.api.textures.GpuSampler) {
        compositeDisplay?.confirmBinding(pass, name, original, effectiveName, effective, sampler)
    }

    fun confirmCompositePass(pass: RenderPass, color: GpuTextureView, depth: GpuTextureView) {
        compositeDisplay?.confirmPass(pass, color, depth)
    }

    fun confirmCompositeDraw(pass: RenderPass, vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        compositeDisplay?.confirmDraw(pass, vertexCount, instanceCount, firstVertex, firstInstance)
    }

    private fun beginRecords(encoder: D3D12CommandEncoderBackend, opaque: OpaqueReconstructionInputs,
        limits: OitRecordLimits): Boolean {
        check(frame === opaque.frame && owner === encoder) { "OIT records belong to another frame or command owner" }
        check(recordFrames == null) { "OIT records began before retirement" }
        val program = checkNotNull(frameResolveProgram) { "OIT frame programs were not prewarmed" }
        check(program === resolveProgram) { "OIT record program changed during its world frame" }
        val records = OitRecordFrames.begin(encoder, opaque, limits, program.generation) ?: return false
        recordFrames = records
        val identity = opaque.frame.identity
        val existing = unrecordedCoverage
        val coverage = if (existing != null && !existing.isClosed() &&
            existing.getWidth(0) == identity.width && existing.getHeight(0) == identity.height) existing
        else D3D12GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING,
            "OIT unrecorded fragment coverage", GpuFormat.R8_UNORM, identity.width, identity.height, 1, 1).also {
            unrecordedCoverage = it
            existing?.close()
        }
        encoder.recordCommands { context ->
            val uses = listOf(VanillaFrameInputResources.texture(coverage.handle(), identity.resourceVersion, 2,
                VanillaFrameInputResources.STATE_RENDER_TARGET))
            VanillaFrameCoordinator.declaredPass(context, 0x3000F, uses, graphicsEffects = 0) {
                MCDR_ABI.D3D12ClearColorTexture(context, coverage.handle(), 0f, 0f, 0f, 0f)
            }
        }
        drawCapture = OitSurfaceRecordCapture(records, coverage.handle())
        return true
    }

    fun displayGuides(inputs: VanillaFrameInputs): OitDisplayGuideAvailability {
        if (!reconstructionRequired(inputs)) {
            check(frame == null) { "Inactive OIT display guides have an active reconstruction frame" }
            return OitDisplayGuideAvailability.INACTIVE
        }
        check(frame === inputs) { "OIT display guides belong to another world frame" }
        val resolved = pendingContributionResolve ?: return OitDisplayGuideAvailability.RESOLVE_UNAVAILABLE
        check(resolved.frame === inputs) { "OIT display resolve belongs to another world frame" }
        return OitDisplayGuideAvailability.MULTI_CONTRIBUTION_UNAVAILABLE
    }

    private fun reconstructionRequired(inputs: VanillaFrameInputs): Boolean =
        inputs.settings.reconstructionBackend != MCDR_ABI.SR_BACKEND_NONE || inputs.settings.antiAliasing == 2

    private fun throwOpaqueSeedFailure(result: Int, operation: String, inputs: VanillaFrameInputs): Nothing {
        val failure = when (result) {
            -1 -> OitRecordFailure.INVALID_CONTRACT
            -2 -> OitRecordFailure.UNSUPPORTED
            -4 -> OitRecordFailure.BUSY
            -5 -> OitRecordFailure.STALE
            -8 -> OitRecordFailure.RECORDING_FAILED
            else -> OitRecordFailure.NATIVE_CONTRACT
        }
        throw OitRecordException(failure, operation, inputs.identity, MCDR_ABI.D3D12LastError())
    }

    fun retireRecords() {
        pendingContributionResolve = null
        compositeDisplay = null
        val records = recordFrames ?: return
        drawCapture = null
        records.close()
        recordFrames = null
    }

    fun invalidateCloudTexture(renderer: Any) {
        OitSurfaceInputs.invalidateCloudTexture(renderer)
        drawnCloud = null
        parameters = null
        if (frame != null) incomplete = true
    }

    fun beginCloudDraw(renderer: Any) {
        check(cloudDraw == null) { "Cloud guide draw is nested" }
        cloudDraw = renderer
    }

    fun endCloudDraw() {
        cloudDraw = null
    }

    fun recordBindings(pipeline: D3D12CompiledRenderPipeline,
        memory: D3D12TransientMemory, route: D3D12RasterRoute): D3D12AdditionalRasterBindings? {
        if ("OIT_ACCUMULATE" !in pipeline.info().shaderDefines.flags()) return null
        val inputs = frame?.takeIf { it === VanillaFrameCoordinator.currentFrame() } ?: return null
        val draw = OitProducerCapture.drawInputs() ?: return null
        val pass = draw.pass ?: return null
        if (draw.frame.identity != inputs.identity || !draw.accumulate || !pass.hasAccumulateInputs()) return null
        if (recordFrames != null) {
            if (pipeline.oitUnrecordedPso == 0L ||
                route == D3D12RasterRoute.TERRAIN_WORK_GRAPH && !pipeline.hasTerrainOitVariant(D3D12OitGuideSource.UNRECORDED) ||
                pipeline.hasLineQuadVariant() && pipeline.oitUnrecordedLineQuadPso == 0L) return null
            return D3D12AdditionalRasterBindings(D3D12AdditionalRasterAttachment(checkNotNull(unrecordedCoverage).handle(),
                D3D12RasterAttachmentRole.UNRECORDED_COVERAGE),
                pipeline.oitUnrecordedPso, pipeline.oitUnrecordedLineQuadPso, null, PARAMETER_NAME,
                pipeline.oitUnrecordedRasterMotionPso, pipeline.oitUnrecordedRasterMotionLineQuadPso)
        }
        val source = pipeline.oitGuideSource
        if (pipeline.oitGuidePso == 0L || source == null) return null
        val current = checkNotNull(attachments)
        if (source == D3D12OitGuideSource.CONTRIBUTION)
            return D3D12AdditionalRasterBindings(D3D12AdditionalRasterAttachment(current.contribution.handle(),
                D3D12RasterAttachmentRole.CONTRIBUTION_GUIDE), pipeline.oitGuidePso,
                pipeline.oitLineQuadPso, null, PARAMETER_NAME,
                pipeline.oitGuideRasterMotionPso, pipeline.oitGuideRasterMotionLineQuadPso)
        val scope = draw.scope ?: return null
        if (!draw.knownProducer || scope.producer.kind != OitProducerKind.CLOUD ||
            scope.producer.owner !== cloudDraw) return null
        val facts = OitSurfaceInputs.cloudPreparation(cloudDraw) ?: return null
        val previous = OitSurfaceInputs.previousCloudPreparation(facts)
        val currentParameters = parameters ?: run {
            val period = facts.width.toLong() * 400L
            val tickDelta = if (previous != null) facts.ticks - previous.ticks else -1L
            val valid = tickDelta in 0L..(period / 2L) && previous != null
            val scrollDelta = if (valid) {
                val currentPhase = (facts.ticks % period).toFloat() + facts.partialTick
                val previousPhase = (previous.ticks % period).toFloat() + previous.partialTick
                val cycles = facts.ticks / period - previous.ticks / period
                (currentPhase * 0.030000001f).toDouble() - (previousPhase * 0.030000001f).toDouble() +
                    cycles.toDouble() * facts.width.toDouble() * 12.0
            } else 0.0
            val camera = inputs.current
            val prior = inputs.previous ?: camera
            val inverse = Matrix4f().set(camera.rasterProjection()).mul(Matrix4f().set(camera.viewRotation())).invert()
            val mapping = Matrix4f().set(prior.projection()).mul(Matrix4f().set(prior.viewRotation()).translate(
                (camera.originX - prior.originX + scrollDelta).toFloat(),
                (camera.originY - prior.originY).toFloat(), (camera.originZ - prior.originZ).toFloat())).mul(inverse)
                .get(FloatArray(16))
            check(mapping.all(Float::isFinite)) { "Cloud previous projection is invalid for ${inputs.identity}" }
            memory.allocateGpuMapped(256L, 256L, GpuBuffer.USAGE_UNIFORM, 0L, 4L).use { mapped ->
                mapped.data().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().apply {
                    put(mapping)
                    put(inputs.identity.width.toFloat())
                    put(inputs.identity.height.toFloat())
                    put(if (valid) 1f else 0f)
                    put(0f)
                    put(camera.jitterX.toFloat())
                    put(-camera.jitterY.toFloat())
                    put(0f)
                    put(0f)
                }
                mapped.slice()
            }.also { parameters = it }
        }
        return D3D12AdditionalRasterBindings(D3D12AdditionalRasterAttachment(current.cloud.handle(),
            D3D12RasterAttachmentRole.CLOUD_GUIDE), pipeline.oitGuidePso,
            pipeline.oitLineQuadPso, currentParameters, PARAMETER_NAME,
            pipeline.oitGuideRasterMotionPso, pipeline.oitGuideRasterMotionLineQuadPso)
    }

    fun recordDraw(pipeline: D3D12CompiledRenderPipeline, additionalTarget: Long) {
        if (recordFrames != null) return
        val inputs = frame?.takeIf { it === VanillaFrameCoordinator.currentFrame() } ?: return
        val draw = OitProducerCapture.drawInputs()
        if (draw?.accumulate != true && "OIT_ACCUMULATE" !in pipeline.info().shaderDefines.flags()) return
        val cloud = OitSurfaceInputs.cloudPreparation(cloudDraw)?.takeIf {
            draw?.knownProducer == true && draw.scope?.producer?.kind == OitProducerKind.CLOUD &&
                draw.scope.producer.owner === cloudDraw
        }
        OitProducerCapture.recordDraw {
            if (cloud != null) OitSurfaceInputs.recordCloudDraw(cloud)
            recordedOit = true
            if (additionalTarget == 0L || draw == null || draw.frame.identity != inputs.identity ||
                !draw.knownProducer || draw.pass?.hasAccumulateInputs() != true) incomplete = true
            if (pipeline.oitGuideSource == D3D12OitGuideSource.CLOUD) {
                if (cloud == null) incomplete = true
                else {
                    if (drawnCloud?.let { it !== cloud } == true) incomplete = true
                    drawnCloud = cloud
                }
            }
        }
    }

    fun recordResolve(encoder: D3D12CommandEncoderBackend, inputs: VanillaFrameInputs,
        baseDepth: D3D12GpuTexture, baseMotion: D3D12GpuTexture, depth: D3D12GpuTexture,
        motion: D3D12GpuTexture): ReconstructionTemporalInputs {
        check(frame === inputs && encoder === owner) { "OIT resolve inputs belong to another frame or encoder" }
        if (recordFrames != null)
            return ReconstructionTemporalInputs(inputs, baseDepth, baseMotion,
                MotionGuideCoverage.RASTER_COVERAGE_UNAVAILABLE)
        val producers = OitProducerCapture.frameResult()
        if (producers == null || producers.frame.identity != inputs.identity || producers.failed ||
            producers.producers.any { it.customHandled == true }) incomplete = true
        if (!recordedOit)
            return ReconstructionTemporalInputs(inputs, baseDepth, baseMotion,
                if (incomplete) MotionGuideCoverage.RASTER_COVERAGE_UNAVAILABLE else D3D12Velocity.motionGuideCoverage())
        val current = checkNotNull(attachments)
        val raw = D3D12RasterMotion.view()
        if (incomplete || raw == null)
            return ReconstructionTemporalInputs(inputs, baseDepth, baseMotion, MotionGuideCoverage.RASTER_COVERAGE_UNAVAILABLE)
        val pipeline = checkNotNull(frameResolveProgram) { "OIT resolve pipeline was not prewarmed" }
        check(pipeline === resolveProgram) { "OIT resolve program changed during its world frame" }
        val baseDepthView = D3D12GpuTextureView(baseDepth, 0, 1)
        val baseMotionView = D3D12GpuTextureView(baseMotion, 0, 1)
        try {
            encoder.recordCommands { context ->
                val version = inputs.identity.resourceVersion
                val uses = listOf(baseDepth.handle(), baseMotion.handle(), current.contribution.handle(),
                    current.cloud.handle(), (raw.texture() as D3D12GpuTexture).handle()).map {
                    VanillaFrameInputResources.texture(it, version, 1, VanillaFrameInputResources.STATE_SHADER_RESOURCE)
                } + listOf(
                    VanillaFrameInputResources.texture(depth.handle(), version, 2, 0x10,
                        finalState = VanillaFrameInputResources.STATE_COMMON),
                    VanillaFrameInputResources.texture(motion.handle(), version, 2, VanillaFrameInputResources.STATE_RENDER_TARGET,
                        finalState = VanillaFrameInputResources.STATE_COMMON))
                VanillaFrameCoordinator.declaredPass(context, 0x3000C, uses, graphicsEffects = 3) {
                    MCDR_ABI.D3D12BeginRenderPass(context, longArrayOf(motion.handle()), intArrayOf(0),
                        byteArrayOf(0), FloatArray(4), depth.handle(), 0, 0, 0.0,
                        0, 0, inputs.identity.width, inputs.identity.height)
                    try {
                        check(MCDR_ABI.D3D12SetPipeline(context, pipeline.handle, true)) { MCDR_ABI.D3D12LastError() }
                        check(MCDR_ABI.D3D12PushDescriptors(context, IntArray(5) { 1 }, LongArray(5), LongArray(5),
                            LongArray(5), IntArray(5), longArrayOf(baseDepthView.handle(), baseMotionView.handle(),
                                current.contributionView.handle(), current.cloudView.handle(), raw.handle()))) {
                            "OIT resolve bindings failed: ${MCDR_ABI.D3D12LastError()}"
                        }
                        check(MCDR_ABI.D3D12Draw(context, 3, 1, 0, 0)) {
                            "OIT resolve failed: ${MCDR_ABI.D3D12LastError()}"
                        }
                    } finally {
                        MCDR_ABI.D3D12EndRenderPass(context)
                    }
                }
            }
        } finally {
            baseDepthView.close()
            baseMotionView.close()
        }
        return ReconstructionTemporalInputs(inputs, depth, motion, MotionGuideCoverage.RASTER_COVERAGE_UNAVAILABLE)
    }

    fun createResolveProgram(generation: Long): ResolveProgram {
        if (frame != null || frameResolveProgram != null) {
            throw FrameProgramsException(
                FrameProgramsFailure.BUSY, "prepare", null,
                "OIT frame must retire before shader reload")
        }
        check(generation >= 0L && generation > (resolveProgram?.generation ?: -1L)) {
            "OIT resolve program generation is stale: $generation"
        }
        val descriptor = MCDRPipelineDesc().shaders(RESOLVE_VERTEX, RESOLVE_FRAGMENT)
            .topology(PrimitiveTopology.TRIANGLES.ordinal, false, 0)
            .depth(GpuFormat.D32_FLOAT.ordinal, true, CompareOp.ALWAYS_PASS.ordinal)
            .addColorTarget(MCDRPipelineDesc.ColorTarget().format(GpuFormat.RG16_FLOAT.ordinal).writeMask(3))
        for (binding in 0..4) descriptor.addBinding(1, binding)
        val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(descriptor)
        check(handle != 0L) { "OIT resolve pipeline prewarm failed: ${MCDR_ABI.D3D12LastError()}" }
        return ResolveProgram(generation, handle)
    }

    fun publishResolveProgram(program: ResolveProgram) {
        resolveProgram = program
    }

    fun stagePresented(inputs: VanillaFrameInputs) {
        check(recordFrames == null) { "OIT records must retire before the native frame reset" }
        if (frame === inputs) OitSurfaceInputs.stagePresented(inputs) else OitSurfaceInputs.invalidate()
        OitTerrainRecords.clearTileReplay()
        frame = null
        frameResolveProgram = null
        drawnCloud = null
        parameters = null
        OitProducerCapture.retireFrame()
    }

    fun publishPresented(inputs: VanillaFrameInputs) {
        OitSurfaceInputs.publishPresented(inputs)
    }

    fun discardPresented() {
        OitSurfaceInputs.invalidate()
    }

    fun invalidate() {
        retireRecords()
        OitTerrainRecords.clearTileReplay()
        frame = null
        frameResolveProgram = null
        drawnCloud = null
        parameters = null
        cloudDraw = null
        OitSurfaceInputs.invalidate()
        OitProducerCapture.retireFrame()
    }

    override fun close() {
        invalidate()
        val current = attachments
        val coverage = unrecordedCoverage
        val recordOutput = recordResolveAttachments
        val normalizedOutput = normalizedTransparency
        attachments = null
        unrecordedCoverage = null
        recordResolveAttachments = null
        normalizedTransparency = null
        resolveProgram = null
        owner = null
        var failure: Throwable? = null
        for (resource in listOfNotNull<AutoCloseable>(current, coverage, recordOutput, normalizedOutput)) {
            try { resource.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }

    private const val PARAMETER_NAME = "IllusionOitParameters"
    private const val MAX_OPAQUE_SEED_EVENTS = 4096
    private val RECORD_LIMITS = OitRecordLimits(512L * 1024L * 1024L,
        262144, 32, 4096, 65536, 65536)
    private val RESOLVE_VERTEX = """
        float4 main(uint vertex : SV_VertexID) : SV_Position {
            float2 uv = float2((vertex << 1) & 2, vertex & 2);
            return float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
        }
    """.trimIndent()
    private val RESOLVE_FRAGMENT = """
        Texture2D<float> MainDepth : register(t0);
        Texture2D<float2> BaseMotion : register(t1);
        Texture2D<float4> Contributions : register(t2);
        Texture2D<float4> Cloud : register(t3);
        Texture2D<float4> FinalOwner : register(t4);
        struct Result { float2 motion : SV_Target0; float depth : SV_Depth; };
        Result main(float4 position : SV_Position) {
            int2 pixel = int2(position.xy);
            float4 cloud = Cloud.Load(int3(pixel, 0));
            float4 other = Contributions.Load(int3(pixel, 0));
            float4 owner = FinalOwner.Load(int3(pixel, 0));
            bool selectCloud = owner.w > 0.0 && owner.z == -4.0 && all(isfinite(other)) &&
                other.x == 0.0 && all(isfinite(cloud)) && cloud.w == 1.0 && cloud.x > 0.0 && cloud.x <= 1.0;
            Result result;
            result.depth = selectCloud ? cloud.x : MainDepth.Load(int3(pixel, 0));
            result.motion = selectCloud ? cloud.yz : BaseMotion.Load(int3(pixel, 0));
            return result;
        }
    """.trimIndent()
}
