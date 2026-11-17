package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.command.D3D12IndexedRasterSource
import io.github.dxegp.d3d12.shader.D3D12OitCaptureProfile
import io.github.dxegp.render.temporal.FeatureGeometryFacts
import io.github.dxegp.render.temporal.FeatureVertexShading
import java.util.Collections

internal enum class OitFeatureShadingAttribute { OVERLAY, LIGHTMAP, NORMAL }

internal data class OitFeatureShaderIdentity(
    val profile: D3D12OitCaptureProfile,
    val flags: Set<String>,
    val values: Map<String, String>,
)

internal data class OitFeatureDrawShadingIdentity(
    val shader: OitFeatureShaderIdentity,
    val uniforms: OitFeatureUniformIdentity,
    val textures: Map<String, OitSurfaceMaterial>,
)

internal data class OitFeatureShading(
    val draw: OitFeatureDrawShadingIdentity,
    val vertices: List<FeatureVertexShading>,
)

internal class OitFeatureDrawShading(
    private val identity: OitFeatureDrawShadingIdentity,
    private val attributes: Set<OitFeatureShadingAttribute>,
    private val vertices: List<FeatureVertexShading>,
) {
    fun triangle(firstVertex: Int, indices: IntArray): OitFeatureShading? {
        val values = ArrayList<FeatureVertexShading>(indices.size)
        for (index in indices) {
            val vertex = vertices.getOrNull(firstVertex + index) ?: return null
            val color = vertex.color ?: return null
            val overlay = if (OitFeatureShadingAttribute.OVERLAY in attributes) vertex.overlay ?: return null else null
            val lightmap = if (OitFeatureShadingAttribute.LIGHTMAP in attributes) vertex.lightmap ?: return null else null
            val normal = if (OitFeatureShadingAttribute.NORMAL in attributes) vertex.normal ?: return null else null
            values.add(FeatureVertexShading(color, overlay, lightmap, normal))
        }
        return OitFeatureShading(identity, Collections.unmodifiableList(values))
    }
}

internal object OitFeatureShadingCapture {
    fun capture(pipeline: D3D12CompiledRenderPipeline, source: D3D12IndexedRasterSource,
        facts: FeatureGeometryFacts): OitFeatureDrawShading? {
        val profile = pipeline.oitCaptureProgram?.profile ?: return null
        val defines = pipeline.info().shaderDefines
        val flags = defines.flags()
        val attributes = buildSet {
            when (profile) {
                D3D12OitCaptureProfile.ENTITY -> {
                    if ("NO_OVERLAY" !in flags) add(OitFeatureShadingAttribute.OVERLAY)
                    if ("EMISSIVE" !in flags) add(OitFeatureShadingAttribute.LIGHTMAP)
                    if ("PER_FACE_LIGHTING" in flags || "NO_CARDINAL_LIGHTING" !in flags)
                        add(OitFeatureShadingAttribute.NORMAL)
                }
                D3D12OitCaptureProfile.ITEM -> addAll(listOf(OitFeatureShadingAttribute.OVERLAY,
                    OitFeatureShadingAttribute.LIGHTMAP, OitFeatureShadingAttribute.NORMAL))
                D3D12OitCaptureProfile.PARTICLE -> add(OitFeatureShadingAttribute.LIGHTMAP)
                else -> return null
            }
        }
        val capturedUniforms = OitFeatureUniforms.capture(source.uniforms) ?: return null
        val lighting = if (OitFeatureShadingAttribute.NORMAL in attributes)
            capturedUniforms.lighting ?: return null else null
        val uniforms = OitFeatureUniformIdentity(capturedUniforms.colorModulator, lighting)
        val textures = LinkedHashMap<String, OitSurfaceMaterial>()
        val samplers = buildList {
            if (OitFeatureShadingAttribute.OVERLAY in attributes) add("Sampler1")
            if (OitFeatureShadingAttribute.LIGHTMAP in attributes) add("Sampler2")
        }
        for (name in samplers) {
            val sampled = source.sampledViews[name] ?: return null
            textures[name] = OitTerrainMaterials.capture(sampled.view, sampled.sampler) ?: return null
        }
        val shader = OitFeatureShaderIdentity(profile, Collections.unmodifiableSet(flags.toSet()),
            Collections.unmodifiableMap(defines.values().toMap()))
        return OitFeatureDrawShading(OitFeatureDrawShadingIdentity(shader, uniforms,
            Collections.unmodifiableMap(textures)), attributes, facts.shading)
    }
}
