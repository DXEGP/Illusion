package io.github.dxegp.render.compilation

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.ShaderDefines
import java.security.MessageDigest
import java.util.HexFormat

internal object CameraReprojectionSources {
    private const val TERRAIN_VERTEX = "1064756a2680f2ec6e8aca49085c3400608ad71533c82110d14882703d40276a"
    private const val TERRAIN_FRAGMENT = "5ae4b6b57dc2c5f33658f924c19e3037aa323dd0b2190681a5a3f4b4ef9bf7ed"
    private const val SKY_VERTEX = "3db48935f02ea870213e17024d719e3f3ecd58c260aeb6227132da1a56480d58"
    private const val END_SKY_VERTEX = "95b7b5bad2eb8961bdf23aca47657743257b341e82575e9b45bc24a5aeb7350b"
    private const val SCREEN_QUAD_VERTEX = "8f77b113e7d3c2cb7146629efaadcbd16ba7242495a72d1e67c6ae10630e47e7"
    private val celestialSources = mapOf(
        RenderPipelines.CELESTIAL to Pair(
            "eb290211e888ae8e006e4c4b5773e06a3cb52e700e8c4c9e0197dd4dde090760",
            "f5c36257df557ba66bbedc735f2422e9d69538127dff9284285725c0c2babd7e"),
        RenderPipelines.STARS to Pair(
            "948988c834c291e22fba1b3e9025fc65f3e2b81bfede6d5e617f4a090bca5179",
            "989db8bce14cd4ec08e7463526edfd7fce7cd6081b8674a1d0e4f7da1fc45766"),
        RenderPipelines.SUNRISE_SUNSET to Pair(
            "fc8fe2f94526e39797617751227452ee1dde11dd1ec27e1b1e65e07f3a0fcc98",
            "5b5b43608834e0c37f7c0a6a5dbb3fb4e4efb7f2dbb1ecbfea1625c488ca2909"),
    )
    private val screenQuadFragments = setOf(
        "b28c79372694ae6cf751e67e097fe7aa7e9626bdf2c8f2923bd7c561dfe4ab43",
        "35d8410d7d6bef1924d666c241b3cd72a1e683497b2e345b2c332751c13d09b5",
        "7c83bf469c1d58bd80bc6ca33990b080c4bdd6a05573e2d20ae205729b1dac56",
        "3f275679b39a81e8f76c8275b62b30aa9851d300310992899c567ed4a163a42e",
        "8aa9d1159255ab3cc6be097f5d2b280ac25c132c4e18814a7c29fa36619e2d5f",
    )
    private val supportedFlags = setOf("MULTIDRAW_TERRAIN", "OIT", "OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS",
        "OIT_TRANSMITTANCE", "OIT_ACCUMULATE")
    private val supportedValues = setOf("ALPHA_CUTOUT", "OIT_WAVELET_RANK", "OIT_COEFF_COUNT",
        "OIT_COEFF_ATTACHMENT_COUNT")
    private val number = Regex("[0-9]+(?:\\.[0-9]+)?[fF]?")
    private val comments = Regex("/\\*.*?\\*/|//[^\\r\\n]*", RegexOption.DOT_MATCHES_ALL)
    private val fragmentDepth = Regex("\\bgl_FragDepth[A-Za-z_0-9]*\\b")

    fun supports(pipeline: RenderPipeline, vertexSource: String, fragmentSource: String,
        compilerDefines: ShaderDefines): Boolean {
        val defines = pipeline.shaderDefines
        if (compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")) return false
        if (defines.flags().any { it !in supportedFlags } || defines.values().any { (name, value) ->
                name !in supportedValues || !number.matches(value)
            }) return false
        val directives = compilerDefines.asSourceDirectives() + defines.asSourceDirectives()
        val vertex = originalSource(vertexSource, directives) ?: return false
        val fragment = originalSource(fragmentSource, directives) ?: return false
        val vertexHash = fingerprint(vertex)
        val screenQuad = vertexHash == SCREEN_QUAD_VERTEX
        val knownVertex = vertexHash == TERRAIN_VERTEX || screenQuad ||
            (pipeline === RenderPipelines.SKY && vertexHash == SKY_VERTEX) ||
            (pipeline === RenderPipelines.END_SKY && vertexHash == END_SKY_VERTEX)
        if (!knownVertex) return false
        if (screenQuad) return fingerprint(fragment) in screenQuadFragments
        val fragmentCode = comments.replace(fragment, "")
        if (fragmentCode.contains("##")) return false
        return !fragmentDepth.containsMatchIn(fragmentCode) ||
            (vertexHash == TERRAIN_VERTEX && fingerprint(fragment) == TERRAIN_FRAGMENT)
    }

    fun supportsCelestial(pipeline: RenderPipeline, vertexSource: String, fragmentSource: String,
        compilerDefines: ShaderDefines): Boolean {
        val expected = celestialSources.entries.singleOrNull { it.key === pipeline }?.value ?: return false
        if (compilerDefines.values().isNotEmpty() ||
            compilerDefines.flags() != setOf("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE") ||
            pipeline.shaderDefines.values().isNotEmpty() || pipeline.shaderDefines.flags().isNotEmpty()) return false
        val directives = compilerDefines.asSourceDirectives()
        val vertex = originalSource(vertexSource, directives) ?: return false
        val fragment = originalSource(fragmentSource, directives) ?: return false
        return fingerprint(vertex) == expected.first && fingerprint(fragment) == expected.second
    }

    private fun originalSource(source: String, directives: String): String? {
        val normalized = source.replace("\r\n", "\n")
        if (!normalized.startsWith("#version ")) return null
        val firstLine = normalized.indexOf('\n')
        if (firstLine < 0 || !normalized.startsWith(directives, firstLine + 1)) return null
        return (normalized.substring(0, firstLine + 1) + normalized.substring(firstLine + 1 + directives.length)).trim()
    }

    private fun fingerprint(source: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)))
}
