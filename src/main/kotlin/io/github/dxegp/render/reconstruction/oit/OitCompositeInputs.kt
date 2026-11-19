package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.frontend.FrontendRenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.shader.D3D12GpuSampler
import io.github.dxegp.d3d12.shader.D3D12OitNormalizationCertification
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import java.util.Collections

internal class OitOriginalCompositeCall(
    val target: RenderTarget,
    val color: GpuTextureView,
    val depth: GpuTextureView,
    val sampler: GpuSampler,
    val accumulate: GpuTextureView,
    coefficients: List<GpuTextureView>,
    val depthBounds: GpuTextureView,
) {
    val coefficients: List<GpuTextureView> = Collections.unmodifiableList(ArrayList(coefficients))
}

internal enum class OitCompositeInputUnavailable {
    INPUT_NOT_CAPTURED, RECONSTRUCTION_INACTIVE, NON_D3D12_RESOURCE, CLOSED_RESOURCE, TEXTURE_LAYOUT, SAMPLER_LAYOUT,
    COEFFICIENT_COUNT, PIPELINE_BINDINGS, PIPELINE_GENERATION, SHADER_CERTIFICATION, CONTENT_VERSION,
}

internal sealed interface OitCompositeInputCapture {
    data class Available(val input: OitCompositeNormalizationInput) : OitCompositeInputCapture
    data class Unavailable(val reason: OitCompositeInputUnavailable,
        val certification: D3D12OitNormalizationCertification? = null,
        val contentFailure: OitRecordFrames.SampledTextureCaptureResult.Unavailable? = null) : OitCompositeInputCapture
}

internal class OitCompositeTexture(val view: D3D12GpuTextureView, val texture: D3D12GpuTexture) {
    val viewHandle = view.handle()
    val textureHandle = texture.handle()
    val format: GpuFormat = texture.format
    val width = view.getWidth(0)
    val height = view.getHeight(0)
    val baseMip = view.baseMipLevel()
    val mipLevels = view.mipLevels()
    val layers = texture.getDepthOrLayers()
    fun isCurrent(): Boolean = !view.isClosed() && !texture.isClosed() &&
        view.handle() == viewHandle && texture.handle() == textureHandle
}

internal class OitCompositeNormalizationInput private constructor(
    val frame: VanillaFrameInputs,
    val recordingSerial: Long,
    val programGeneration: Long,
    val originalTarget: RenderTarget,
    val color: OitCompositeTexture,
    val depth: OitCompositeTexture,
    val accumulate: OitCompositeTexture,
    coefficients: List<OitCompositeTexture>,
    val depthBounds: OitCompositeTexture,
    val sampler: D3D12GpuSampler,
    val pipeline: D3D12CompiledRenderPipeline,
    val certification: D3D12OitNormalizationCertification.Certified,
) {
    val coefficients: List<OitCompositeTexture> = Collections.unmodifiableList(ArrayList(coefficients))
    val samplerHandle = sampler.handle()
    val pipelineHandle = pipeline.handle()
    val pipelineDefines: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(pipeline.info().shaderDefines.values()))
    val pipelineFlags: Set<String> = Collections.unmodifiableSet(LinkedHashSet(pipeline.info().shaderDefines.flags()))
    val normalizationRecipe: OitTileNormalizationRecipe = OitTileNormalizationPrograms.recipe(coefficients.size)
    val inputCount: Int get() = coefficients.size + 4

    inline fun forEachTexture(action: (Int, OitCompositeTexture) -> Unit) {
        action(0, color)
        action(1, depth)
        action(2, accumulate)
        action(3, depthBounds)
        for (index in coefficients.indices) action(index + 4, coefficients[index])
    }

    fun isCurrent(): Boolean = frame === VanillaFrameCoordinator.currentFrame() &&
        !sampler.isClosed() && sampler.handle() == samplerHandle && pipeline.isValid() &&
        pipeline.handle() == pipelineHandle && pipeline.oitNormalization == certification &&
        color.isCurrent() && depth.isCurrent() && accumulate.isCurrent() && depthBounds.isCurrent() &&
        coefficients.all(OitCompositeTexture::isCurrent)

    companion object {
        fun capture(records: OitRecordFrames, call: OitOriginalCompositeCall,
            compiled: CompiledRenderPipeline): OitCompositeInputCapture {
            val pipeline = (compiled as? FrontendRenderPipeline)?.backendRenderPipeline() as? D3D12CompiledRenderPipeline
                ?: return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.NON_D3D12_RESOURCE)
            val certification = pipeline.oitNormalization
            if (certification !is D3D12OitNormalizationCertification.Certified)
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.SHADER_CERTIFICATION, certification)
            if (!pipeline.isValid() || compiled.isClosed())
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.CLOSED_RESOURCE, certification)
            if (certification.generation != records.programGeneration)
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.PIPELINE_GENERATION, certification)
            if (call.coefficients.size !in setOf(1, 2, 4) || certification.targetCount != call.coefficients.size)
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.COEFFICIENT_COUNT, certification)
            val expectedBindings = buildSet {
                add("Sampler0")
                add("DepthBoundsSampler")
                repeat(call.coefficients.size) { add("Coeff$it") }
            }
            val sampledBindings = pipeline.buildBindings().filter { it.type == D3D12BindGroupEntry.Type.SAMPLED_IMAGE }
            if (sampledBindings.size != expectedBindings.size || sampledBindings.map { it.name }.toSet() != expectedBindings)
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.PIPELINE_BINDINGS, certification)
            val sampler = call.sampler as? D3D12GpuSampler
                ?: return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.NON_D3D12_RESOURCE, certification)
            if (sampler.isClosed())
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.CLOSED_RESOURCE, certification)
            if (sampler.getMinFilter() != FilterMode.NEAREST || sampler.getMagFilter() != FilterMode.NEAREST ||
                sampler.getAddressModeU() != AddressMode.CLAMP_TO_EDGE || sampler.getAddressModeV() != AddressMode.CLAMP_TO_EDGE)
                return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.SAMPLER_LAYOUT, certification)
            val views = listOf(call.color, call.depth, call.accumulate, call.depthBounds) + call.coefficients
            val formats = listOf(GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT, GpuFormat.RGBA16_FLOAT,
                GpuFormat.RGBA32_FLOAT) + List(call.coefficients.size) { GpuFormat.RGBA16_FLOAT }
            for ((index, view) in views.withIndex()) {
                val d3dView = view as? D3D12GpuTextureView
                    ?: return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.NON_D3D12_RESOURCE, certification)
                val texture = d3dView.texture() as? D3D12GpuTexture
                    ?: return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.NON_D3D12_RESOURCE, certification)
                if (d3dView.isClosed() || texture.isClosed())
                    return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.CLOSED_RESOURCE, certification)
                if (d3dView.baseMipLevel() != 0 || d3dView.mipLevels() != 1 || texture.getDepthOrLayers() != 1 ||
                    texture.getMipLevels() != 1 || d3dView.getWidth(0) != records.frame.identity.width ||
                    d3dView.getHeight(0) != records.frame.identity.height ||
                    texture.format != formats[index] || texture.usage() and GpuTexture.USAGE_RENDER_ATTACHMENT == 0 ||
                    (index >= 2 && texture.usage() and GpuTexture.USAGE_TEXTURE_BINDING == 0))
                    return OitCompositeInputCapture.Unavailable(OitCompositeInputUnavailable.TEXTURE_LAYOUT, certification)
            }
            val textures = ArrayList<OitCompositeTexture>(views.size)
            for (view in views) {
                val d3dView = view as D3D12GpuTextureView
                val texture = d3dView.texture() as D3D12GpuTexture
                textures += OitCompositeTexture(d3dView, texture)
            }
            return OitCompositeInputCapture.Available(OitCompositeNormalizationInput(records.frame,
                records.recordingSerial, records.programGeneration, call.target, textures[0], textures[1], textures[2],
                textures.drop(4), textures[3], sampler, pipeline, certification))
        }
    }
}

internal class OitCompositeDisplayConfirmation(
    private val frame: VanillaFrameInputs,
    private val original: OitOriginalCompositeCall,
    private val originalPipeline: CompiledRenderPipeline,
    destination: RenderTarget,
    private val transparency: GpuTextureView,
    private val transparencySampler: String,
) {
    private val color = checkNotNull(destination.colorTextureView)
    private val depth = checkNotNull(destination.depthTextureView)
    private var pass: RenderPass? = null
    private var pipeline: CompiledRenderPipeline? = null
    private var mapping: D3D12SceneDisplay.DepthMapping? = null
    private val bindings = HashSet<String>()
    private var completed = false

    fun confirmPass(actual: RenderPass, actualColor: GpuTextureView, actualDepth: GpuTextureView) {
        check(frame === VanillaFrameCoordinator.currentFrame() && pass == null && !completed)
        check(actualColor === color && actualDepth === depth) { "Composite display attachments changed" }
        pass = actual
    }

    fun confirmBinding(actual: RenderPass, name: String, source: GpuTextureView, effectiveName: String?, effective: GpuTextureView,
        sampler: GpuSampler) {
        check(pass === actual && !completed && frame === VanillaFrameCoordinator.currentFrame())
        val expected = when (name) {
            "Sampler0" -> original.accumulate
            "DepthBoundsSampler" -> original.depthBounds
            else -> if (name.startsWith("Coeff")) name.removePrefix("Coeff").toIntOrNull()
                ?.let(original.coefficients::getOrNull) else null
        } ?: error("Unexpected Composite sampled binding: $name")
        check(source === expected && sampler === original.sampler && !sampler.isClosed() && !source.isClosed()) {
            "Original Composite binding changed: $name"
        }
        val expectedName = when {
            name == "Sampler0" -> transparencySampler
            transparencySampler != "Sampler0" && name.startsWith("Coeff") -> null
            else -> name
        }
        check(effectiveName == expectedName) { "Display Composite binding name changed: $name" }
        if (effectiveName == null) return
        check(effective === (if (name == "Sampler0") transparency else expected) && !effective.isClosed()) {
            "Display Composite binding changed: $name"
        }
        bindings += effectiveName
    }

    fun confirmPipeline(actual: RenderPass, source: CompiledRenderPipeline, effective: CompiledRenderPipeline,
        sourceMapping: D3D12SceneDisplay.DepthMapping) {
        check(pass === actual && !completed && frame === VanillaFrameCoordinator.currentFrame())
        check(source === originalPipeline && !source.isClosed() && !effective.isClosed()) {
            "Composite pipeline changed after input capture"
        }
        pipeline = effective
        mapping = sourceMapping
    }

    fun confirmDraw(actual: RenderPass, vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        check(pass === actual && !completed && frame === VanillaFrameCoordinator.currentFrame())
        check(vertexCount == 3 && instanceCount == 1 && firstVertex == 0 && firstInstance == 0)
        val expectedBindings = buildSet {
            add(transparencySampler)
            add("DepthBoundsSampler")
            if (transparencySampler == "Sampler0") repeat(original.coefficients.size) { add("Coeff$it") }
        }
        check(pipeline?.isClosed() == false && mapping != null && bindings == expectedBindings) {
            "Composite display bindings are incomplete"
        }
        completed = true
    }
}
