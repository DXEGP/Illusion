package io.github.dxegp.render.presentation.display

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import io.github.dxegp.mixin.presentation.PostChainDisplayAccessor
import io.github.dxegp.mixin.presentation.PostPassDisplayAccessor
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostPass
import net.minecraft.client.renderer.ShaderManager
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.HexFormat
import java.util.WeakHashMap

internal object DisplayPostSources {
    private const val SCREEN_QUAD = "8f77b113e7d3c2cb7146629efaadcbd16ba7242495a72d1e67c6ae10630e47e7"
    private val fragmentSources = mapOf(
        "minecraft:post/invert" to "57b8eda36bfff9f21c1cc32e3f7eb2e1791569130e4802e98105a97586f063ba",
        "minecraft:post/blit" to "cb5c9902ba6aa8410a2ce25eebd563bbf402f5f685c2fea0d8e0313e640b4bfa",
    )
    private class CompiledSource(val compiled: WeakReference<CompiledRenderPipeline>, val exactColor: Boolean)
    private val compiledSources = WeakHashMap<RenderPipeline, CompiledSource>()

    @Synchronized
    fun recordCompiledSource(pipeline: RenderPipeline, compiled: CompiledRenderPipeline, source: ShaderSource) {
        val fragmentId = pipeline.shaders[ShaderType.FRAGMENT] ?: return
        val expected = fragmentSources[fragmentId.toString()] ?: return
        if (compiledSources[pipeline]?.compiled?.get() === compiled) return
        val vertexId = pipeline.shaders[ShaderType.VERTEX]
        val exactColor = source is ShaderManager.Configs && vertexId?.toString() == "minecraft:core/screenquad" &&
            pipeline.shaderDefines.flags().isEmpty() && pipeline.shaderDefines.values().isEmpty() &&
            pipeline.primitiveTopology == PrimitiveTopology.TRIANGLES &&
            vertexId != null && fingerprint(source.getShader(vertexId, ShaderType.VERTEX)) == SCREEN_QUAD &&
            fingerprint(source.getShader(fragmentId, ShaderType.FRAGMENT)) == expected
        compiledSources[pipeline] = CompiledSource(WeakReference(compiled), exactColor)
    }

    fun describe(chain: PostChain): DisplayPostInputs {
        val kind = if (isColorOnlyInvert(chain)) DisplayPostKind.COLOR else DisplayPostKind.UNAVAILABLE
        return DisplayPostInputs(chain.id().toString(), kind, FloatArray(16), FloatArray(16))
    }

    private fun isColorOnlyInvert(chain: PostChain): Boolean {
        if (chain.id().toString() != "minecraft:invert") return false
        val state = chain as PostChainDisplayAccessor
        val passes = state.illusionDisplayPasses()
        if (passes.size != 2 || state.illusionDisplayExternalTargets() != setOf(PostChain.MAIN_TARGET_ID)) return false
        val invert = passes[0] as PostPassDisplayAccessor
        val blit = passes[1] as PostPassDisplayAccessor
        val intermediate = invert.illusionDisplayOutput()
        val targets = state.illusionDisplayInternalTargets()
        val target = targets[intermediate] ?: return false
        if (targets.size != 1 || target.width().isPresent || target.height().isPresent || target.persistent() ||
            blit.illusionDisplayOutput() != PostChain.MAIN_TARGET_ID) return false
        val invertInput = invert.illusionDisplayInputs().singleOrNull() as? PostPass.TargetInput ?: return false
        val blitInput = blit.illusionDisplayInputs().singleOrNull() as? PostPass.TargetInput ?: return false
        if (invertInput.samplerName() != "In" || invertInput.targetId() != PostChain.MAIN_TARGET_ID ||
            invertInput.depthBuffer() || invertInput.bilinear() || blitInput.samplerName() != "In" ||
            blitInput.targetId() != intermediate || blitInput.depthBuffer() || blitInput.bilinear()) return false
        val invertPipeline = invert.illusionDisplayPipeline()
        val blitPipeline = blit.illusionDisplayPipeline()
        if (invertPipeline.shaders[ShaderType.FRAGMENT]?.toString() != "minecraft:post/invert" ||
            blitPipeline.shaders[ShaderType.FRAGMENT]?.toString() != "minecraft:post/blit") return false
        return listOf(invertPipeline, blitPipeline).all { pipeline ->
            val compiled = RenderSystem.getCompiledPipeline(pipeline)
            synchronized(this) {
                compiledSources[pipeline]?.let { it.compiled.get() === compiled && it.exactColor } == true
            }
        }
    }

    private fun fingerprint(source: String?): String? = source?.let {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(it.replace("\r\n", "\n").trim().toByteArray(Charsets.UTF_8)))
    }
}
