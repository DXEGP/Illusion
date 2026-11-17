package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendOp
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry

data class D3D12RasterMotionProgram(
    val vertex: String,
    val fragment: String,
    val parameterBinding: Int,
    val historyBinding: Int,
)

data class D3D12OitRasterMotionProgram(
    val motion: D3D12RasterMotionProgram,
    val oitParameterBinding: Int?,
)

enum class D3D12RasterMotionSource {
    VERTEX_HISTORY, CELESTIAL_TRANSFORM, CAMERA_REPROJECTION, CONTRIBUTION_ONLY, OIT_COMPOSITE,
}

enum class D3D12RasterComposition { REPLACE, SOURCE_OVER, PREMULTIPLIED_OVER, ADD_ALPHA, ADD_COLOR, ADD }

internal enum class D3D12OitGuideSource { CONTRIBUTION, CLOUD, UNRECORDED }

data class D3D12RasterMotionContract(
    val source: D3D12RasterMotionSource,
    val composition: D3D12RasterComposition,
)

object D3D12RasterMotionTuple {
    const val CELESTIAL_VALID = -1.0f
    const val SURFACE_INVALID = -2.0f
    const val CELESTIAL_INVALID = -3.0f
    const val OIT_INVALID = -4.0f
    const val CAMERA_SURFACE = -5.0f
}

object D3D12RasterMotionShader {
    internal fun buildOitCapture(shader: D3D12CompiledShader, fragmentSource: String,
        contract: D3D12OitCaptureContract): D3D12OitCaptureShader {
        check(contract.prelude.abi == D3D12OitCaptureSource.SHADER_ABI_VERSION && contract.prelude.hash != 0L) {
            "Unsupported native OIT shader contract"
        }
        val vertex = checkNotNull(entry(shader.vertexHlsl)) { "OIT capture vertex entry is unsupported" }
        val vertexFields = checkNotNull(fields(shader.vertexHlsl, vertex.result)) {
            "OIT capture vertex outputs are unsupported"
        }
        check(vertexFields.none { it.semantic.startsWith("MCDR_OIT", true) }) { "OIT vertex semantic collision" }
        val instances = vertex.arguments.flatMap { argument ->
            if (argument.semantic != null) {
                if (argument.semantic.equals("SV_InstanceID", true) && argument.type == "uint")
                    listOf(argument.name) else emptyList()
            } else checkNotNull(fields(shader.vertexHlsl, argument.type)).filter {
                it.semantic.equals("SV_InstanceID", true) && it.type == "uint"
            }.map { "${argument.name}.${it.name}" }
        }
        check(instances.size <= 1) { "OIT capture has ambiguous instance identity" }
        val vertexArguments = vertex.arguments.map { it.declaration }.toMutableList()
        val instance = instances.singleOrNull() ?: "McdrOitInstanceId".also {
            vertexArguments.add("uint $it : SV_InstanceID")
        }
        val renamedVertex = shader.vertexHlsl.replaceRange(vertex.match.range,
            vertex.match.value.replaceFirst(" main(", " McdrOitOriginalVertexMain("))
        val captureVertex = renamedVertex + "\n" + """
            struct McdrOitVertexOutput {
                ${vertexFields.joinToString("\n") { it.declaration + ";" }}
                nointerpolation uint McdrOitInstance : MCDR_OIT_INSTANCE;
            };
            McdrOitVertexOutput main(${vertexArguments.joinToString(", ")}) {
                ${vertex.result} original = McdrOitOriginalVertexMain(${vertex.arguments.joinToString(", ") { it.name }});
                McdrOitVertexOutput output = (McdrOitVertexOutput)0;
                ${vertexFields.joinToString("\n") { "output.${it.name} = original.${it.name};" }}
                output.McdrOitInstance = $instance;
                return output;
            }
        """.trimIndent()
        val fragment = checkNotNull(entry(fragmentSource)) { "OIT capture fragment entry is unsupported" }
        check(fragment.semantic == null) { "OIT capture requires named fragment outputs" }
        val outputs = checkNotNull(fields(fragmentSource, fragment.result)) { "OIT capture outputs are unsupported" }
        val metadata = setOf("IllusionOitMaterial", "IllusionOitSample", "IllusionOitTransport", "IllusionOitUv",
            "IllusionOitDerivatives", "IllusionOitFogColor")
        val colors = outputs.filter { it.name !in metadata }
        check(outputs.size == 7 && outputs.filter { it.name in metadata }.map { it.name }.toSet() == metadata &&
            outputs.all { it.type == "float4" } && colors.singleOrNull()?.semantic.equals("SV_Target0", true)) {
            "OIT capture fragment signature does not match its certified metadata schema"
        }
        val fragmentInputs = fragment.arguments.flatMap { argument ->
            if (argument.semantic != null) listOf(Field(argument.declaration, argument.type, argument.name, argument.semantic))
            else checkNotNull(fields(fragmentSource, argument.type))
        }
        val canonicalFields = vertexFields.toMutableList()
        for (input in fragmentInputs) {
            val output = canonicalFields.singleOrNull { it.semantic.equals(input.semantic, true) }
            if (output != null) {
                check(output.type == input.type) { "OIT capture vertex/fragment type mismatch: ${input.semantic}" }
            } else {
                check(input.semantic.equals("SV_IsFrontFace", true)) {
                    "OIT capture fragment input has no vertex source: ${input.semantic}"
                }
            }
        }
        val position = checkNotNull(canonicalFields.singleOrNull {
            it.semantic.equals("SV_Position", true) && it.type == "float4"
        }) { "OIT capture requires the actual SV_Position" }
        val renamedFragment = fragmentSource.replaceRange(fragment.match.range,
            fragment.match.value.replaceFirst(" main(", " McdrOitOriginalFragmentMain("))
        val fragments = listOf(false, true).map { terrain ->
            val identity = if (terrain) "float4 McdrOitPreviousClip : MCDR_OIT_PREVIOUS_CLIP;\nnointerpolation uint2 McdrOitSurface : MCDR_OIT_TERRAIN;" else
                "nointerpolation uint McdrOitInstance : MCDR_OIT_INSTANCE;"
            val pixelOnlyFields = fragmentInputs.filter { input ->
                canonicalFields.none { it.semantic.equals(input.semantic, true) }
            }
            val entryFields = canonicalFields + pixelOnlyFields
            val copies = fragment.arguments.joinToString("\n") { argument ->
                if (argument.semantic != null) {
                    val input = entryFields.single { it.semantic.equals(argument.semantic, true) }
                    "${argument.type} ${argument.name} = captureInput.${input.name};"
                } else {
                    "${argument.type} ${argument.name} = (${argument.type})0;\n" +
                        checkNotNull(fields(fragmentSource, argument.type)).joinToString("\n") { field ->
                            val input = entryFields.single { it.semantic.equals(field.semantic, true) }
                            "${argument.name}.${field.name} = captureInput.${input.name};"
                        }
                }
            }
            val primitive = if (terrain) "McdrOitTerrainPrimitiveIndexV1(captureInput.McdrOitSurface.x, captureInput.McdrOitSurface.y)" else
                "McdrOitPrimitiveIndexV1(McdrOitPrimitive, captureInput.McdrOitInstance)"
            val primitiveArgument = if (terrain) "" else ", uint McdrOitPrimitive : SV_PrimitiveID"
            val facingHistory = if (contract.profile == D3D12OitCaptureProfile.ENTITY &&
                fragmentInputs.any { it.semantic.equals("SV_IsFrontFace", true) }) """
                    float2 McdrOitGeometryDx = ddx_fine(contribution.previousUv);
                    float2 McdrOitGeometryDy = ddy_fine(contribution.previousUv);
                    float McdrOitGeometryDeterminant = McdrOitGeometryDx.x * McdrOitGeometryDy.y -
                        McdrOitGeometryDx.y * McdrOitGeometryDy.x;
                    uint McdrOitGeometryValid = (contribution.validity & MCDR_OIT_GEOMETRY_PREVIOUS_VALID) != 0u ? 1u : 0u;
                    McdrOitGeometryValid &= QuadReadAcrossX(McdrOitGeometryValid) & QuadReadAcrossY(McdrOitGeometryValid) &
                        QuadReadAcrossDiagonal(McdrOitGeometryValid);
                    if (McdrOitGeometryValid == 0u || !isfinite(McdrOitGeometryDeterminant) || McdrOitGeometryDeterminant <= 0.0f)
                        contribution.validity &= ~MCDR_OIT_MATERIAL_PREVIOUS_VALID;
                """.trimIndent() else ""
            renamedFragment + "\n" + contract.prelude.source + "\n" + """
                struct McdrOitFragmentInput {
                    ${canonicalFields.joinToString("\n") { it.declaration + ";" }}
                    $identity
                    ${pixelOnlyFields.joinToString("\n") { it.declaration + ";" }}
                };
                [earlydepthstencil]
                float4 main(McdrOitFragmentInput captureInput$primitiveArgument,
                    linear float3 McdrOitBarycentrics : SV_Barycentrics) : SV_Target0 {
                    $copies
                    ${fragment.result} original = McdrOitOriginalFragmentMain(${fragment.arguments.joinToString(", ") { it.name }});
                    McdrOitFragmentV1 contribution = (McdrOitFragmentV1)0;
                    contribution.position = captureInput.${position.name};
                    contribution.finalQ = original.${colors.single().name};
                    contribution.materialRgb = original.IllusionOitMaterial.rgb;
                    contribution.materialAlpha = original.IllusionOitMaterial.a;
                    contribution.sampleAlpha = original.IllusionOitSample.a;
                    contribution.transmittance = original.IllusionOitTransport.x;
                    contribution.accumAlpha = original.IllusionOitTransport.y;
                    contribution.fogColorRgb = original.IllusionOitFogColor.rgb;
                    contribution.fogFactor = original.IllusionOitTransport.z;
                    contribution.chunkVisibility = original.IllusionOitTransport.w;
                    contribution.materialUv = original.IllusionOitUv.xy;
                    contribution.materialDx = original.IllusionOitDerivatives.xy;
                    contribution.materialDy = original.IllusionOitDerivatives.zw;
                    contribution.response = original.IllusionOitTransport.x * original.IllusionOitSample.a *
                        (1.0 - original.IllusionOitTransport.z) * original.IllusionOitTransport.w;
                    contribution.coverage = 1.0;
                    contribution.primitiveIndex = $primitive;
                    contribution.validity = MCDR_OIT_CURRENT_MATERIAL_VALID | MCDR_OIT_COVERAGE_VALID |
                        MCDR_OIT_GEOMETRY_PREVIOUS_VALID | MCDR_OIT_MATERIAL_PREVIOUS_VALID;
                    ${if (terrain) "McdrOitResolveTerrainPreviousV2(contribution, captureInput.McdrOitPreviousClip);" else
                        "McdrOitResolvePreviousV2(contribution, McdrOitBarycentrics, ${contract.profile.previousDomain.shaderDefine});"}
                    $facingHistory
                    contribution.depthDx = ddx_fine(contribution.position.z);
                    contribution.depthDy = ddy_fine(contribution.position.z);
                    contribution.materialPreviousDx = ddx_fine(contribution.materialPreviousUv);
                    contribution.materialPreviousDy = ddy_fine(contribution.materialPreviousUv);
                    AppendV1(contribution);
                    return original.${colors.single().name};
                }
            """.trimIndent()
        }
        return D3D12OitCaptureShader(captureVertex, fragments[0], fragments[1], contract.prelude.abi,
            contract.prelude.hash, contract.generation, contract.profile, contract.prelude.source)
    }

    internal fun buildOitFragment(shader: D3D12CompiledShader, target: Int,
        source: D3D12OitGuideSource): String? {
        if (target !in 1..7) return null
        val fragment = entry(shader.fragmentHlsl) ?: return null
        val outputs = if (fragment.semantic != null) listOf(
            Field("${fragment.result} original : ${fragment.semantic}", fragment.result, "original", fragment.semantic)
        ) else fields(shader.fragmentHlsl, fragment.result) ?: return null
        val colors = outputs.filter { it.semantic.startsWith("SV_Target", true) }
        if (colors.isEmpty() || colors.any { it.type != "float4" ||
                it.semantic.substring(9).toIntOrNull()?.let { index -> index >= target } != false }) return null
        val positions = fragment.arguments.flatMap { argument ->
            if (argument.semantic != null) {
                if (argument.semantic.equals("SV_Position", true) && argument.type == "float4")
                    listOf(argument.name) else emptyList()
            } else fields(shader.fragmentHlsl, argument.type)?.filter {
                it.semantic.equals("SV_Position", true) && it.type == "float4"
            }?.map { "${argument.name}.${it.name}" } ?: return null
        }
        if (positions.size > 1) return null
        val arguments = fragment.arguments.map { it.declaration }.toMutableList()
        val position = positions.singleOrNull() ?: "IllusionOitPosition".also {
            if (source != D3D12OitGuideSource.UNRECORDED) arguments.add("float4 $it : SV_Position")
        }
        val depthField = outputs.singleOrNull { it.semantic.startsWith("SV_Depth", true) }
        if (depthField != null && depthField.type != "float") return null
        fun value(field: Field) = if (fragment.semantic != null) "original" else "original.${field.name}"
        val depth = depthField?.let(::value) ?: "$position.z"
        val contributions = colors.joinToString(" || ") {
            "!all(isfinite(${value(it)})) || any(${value(it)} != 0.0)"
        }
        val finiteOutputs = colors.joinToString(" && ") { "all(isfinite(${value(it)}))" }
        val copies = if (fragment.semantic != null) "output.original = original;" else
            outputs.joinToString("\n") { "output.${it.name} = original.${it.name};" }
        val guide = when (source) {
            D3D12OitGuideSource.CONTRIBUTION -> "output.IllusionOitGuide = contributes ? float4(1.0, depth, 0.0, 1.0) : 0.0;"
            D3D12OitGuideSource.UNRECORDED -> "output.IllusionOitGuide = float4(1.0, 0.0, 0.0, 1.0);"
            D3D12OitGuideSource.CLOUD -> """
                output.IllusionOitGuide = contributes ? float4(depth, 0.0, 0.0, -1.0) : 0.0;
                float2 uv = $position.xy / IllusionOitExtent.xy;
                float4 previous = mul(IllusionOitCurrentToPrevious, float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0));
                if (contributes && ($finiteOutputs) && IllusionOitExtent.z > 0.5 && depth > 0.0 && depth <= 1.0 &&
                    all(isfinite(previous)) && previous.w > 0.0001) {
                    float2 previousUv = float2(previous.x / previous.w * 0.5 + 0.5, 0.5 - previous.y / previous.w * 0.5);
                    float2 motion = previousUv * IllusionOitExtent.xy - $position.xy + IllusionOitJitter.xy;
                    if (all(isfinite(motion)) && all(abs(motion) <= 65504.0))
                        output.IllusionOitGuide = float4(depth, motion, 1.0);
                }
            """.trimIndent()
        }
        val parameters = if (source == D3D12OitGuideSource.CLOUD) """
            cbuffer IllusionOitParameters : register(b${shader.entries.size}, space0) {
                float4x4 IllusionOitCurrentToPrevious;
                float4 IllusionOitExtent;
                float4 IllusionOitJitter;
            };
        """.trimIndent() else ""
        val renamed = shader.fragmentHlsl.replaceRange(fragment.match.range,
            fragment.match.value.replaceFirst(" main(", " IllusionOitOriginalMain("))
        val contributionState = if (source == D3D12OitGuideSource.UNRECORDED) "" else """
            bool contributes = $contributions;
            float depth = $depth;
        """.trimIndent()
        return renamed + "\n" + parameters + "\n" + """
            struct IllusionOitOutput {
                ${outputs.joinToString("\n") { it.declaration + ";" }}
                float4 IllusionOitGuide : SV_Target$target;
            };
            IllusionOitOutput main(${arguments.joinToString(", ")}) {
                ${fragment.result} original = IllusionOitOriginalMain(${fragment.arguments.joinToString(", ") { it.name }});
                IllusionOitOutput output = (IllusionOitOutput)0;
                $copies
                $contributionState
                $guide
                return output;
            }
        """.trimIndent()
    }

    fun composition(pipeline: RenderPipeline): D3D12RasterComposition? {
        val target = pipeline.colorTargetStates.singleOrNull() ?: return null
        if (target.writeMask() and 7 != 7) return null
        val blend = target.blendFunction().orElse(null) ?: return D3D12RasterComposition.REPLACE
        val color = blend.color()
        if (color.op() != BlendOp.ADD) return null
        return when (color.sourceFactor() to color.destFactor()) {
            BlendFactor.SRC_ALPHA to BlendFactor.ONE_MINUS_SRC_ALPHA -> D3D12RasterComposition.SOURCE_OVER
            BlendFactor.ONE to BlendFactor.ONE_MINUS_SRC_ALPHA -> D3D12RasterComposition.PREMULTIPLIED_OVER
            BlendFactor.SRC_ALPHA to BlendFactor.ONE -> D3D12RasterComposition.ADD_ALPHA
            BlendFactor.SRC_COLOR to BlendFactor.ONE -> D3D12RasterComposition.ADD_COLOR
            BlendFactor.ONE to BlendFactor.ONE -> D3D12RasterComposition.ADD
            else -> null
        }
    }

    private val comments = Regex("/\\*.*?\\*/|//[^\\r\\n]*", RegexOption.DOT_MATCHES_ALL)
    private val tokens = Regex("[A-Za-z_][A-Za-z_0-9]*|[0-9]+(?:\\.[0-9]*)?|[^\\s]")
    private val exactEntityMain = """
        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            sphericalVertexDistance = fog_spherical_distance(Position);
            cylindricalVertexDistance = fog_cylindrical_distance(Position);
        #ifdef PER_FACE_LIGHTING
            vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
            vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
            vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
        #elif defined(NO_CARDINAL_LIGHTING)
            vertexColor = Color;
        #else
            vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
        #endif
        #if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
            lightMapColor = sample_lightmap(Sampler2, UV2);
        #endif
        #if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
            overlayColor = texelFetch(Sampler1, UV1, 0);
        #endif
            texCoord0 = UV0;
        #ifdef APPLY_TEXTURE_MATRIX
            texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
        #endif
        #ifdef GLINT
            texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
        #endif
        }
    """.trimIndent()
    private val exactItemMain = """
        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        #ifndef OIT_ALPHA_ONLY
            sphericalVertexDistance = fog_spherical_distance(Position);
            cylindricalVertexDistance = fog_cylindrical_distance(Position);
        #endif
            vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
        #ifndef OIT_ALPHA_ONLY
            lightMapColor = sample_lightmap(Sampler2, UV2);
            overlayColor = texelFetch(Sampler1, UV1, 0);
        #endif
            texCoord0 = UV0;
        #ifdef GLINT
        #ifdef GLINT_SPECIAL
            texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
        #else
            texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
        #endif
        #endif
        }
    """.trimIndent()
    private val exactParticleMain = """
        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            sphericalVertexDistance = fog_spherical_distance(Position);
            cylindricalVertexDistance = fog_cylindrical_distance(Position);
            texCoord0 = UV0;
            vertexColor = Color * sample_lightmap(Sampler2, UV2);
        }
    """.trimIndent()
    private val supportedMains = listOf(exactEntityMain, exactItemMain, exactParticleMain)
    private val supportedMainTokens = supportedMains.map { main -> tokens.findAll(main).map { it.value }.toList() }
    private val protectedIdentifiers = Regex("[A-Za-z_][A-Za-z_0-9]*")
        .findAll(supportedMains.joinToString("\n").replace(Regex("(?m)^\\s*#.*$"), ""))
        .map { it.value }.toSet()

    fun supportsVertexSource(source: String): Boolean {
        val clean = comments.replace(source, "")
        if (clean.contains("##") || Regex("\\bgl_Position\\b").findAll(clean).count() != 1) return false
        val main = Regex("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{[^{}]*}")
            .findAll(clean).toList().singleOrNull() ?: return false
        if (tokens.findAll(main.value).map { it.value }.toList() !in supportedMainTokens) return false
        val directives = Regex("(?m)^\\s*#\\s*(?:define|undef)\\s+([A-Za-z_][A-Za-z_0-9]*)")
        if (directives.findAll(clean).any { it.groupValues[1] in protectedIdentifiers }) return false
        if (!Regex("\\buniform\\s+Projection\\s*\\{\\s*mat4\\s+ProjMat\\s*;\\s*}\\s*;")
                .containsMatchIn(clean)) return false
        return Regex("\\buniform\\s+DynamicTransforms\\s*\\{\\s*mat4\\s+ModelViewMat\\s*;\\s*mat4\\s+TextureMat\\s*;\\s*vec4\\s+ColorModulator\\s*;\\s*vec3\\s+ModelOffset\\s*;\\s*}\\s*;")
            .containsMatchIn(clean)
    }

    private data class Field(val declaration: String, val type: String, val name: String,
        val semantic: String)
    private data class Argument(val declaration: String, val type: String, val name: String,
        val semantic: String?)
    private data class Entry(val match: MatchResult, val result: String, val arguments: List<Argument>,
        val semantic: String?)
    private val entryPattern = Regex("(?m)^[\\t ]*([A-Za-z_][A-Za-z_0-9]*) main\\(([^)]*)\\)\\s*(?::\\s*([A-Za-z_][A-Za-z_0-9]*))?\\s*\\{")
    private val argumentPattern = Regex("^([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)(?:\\s*:\\s*([A-Za-z_][A-Za-z_0-9]*))?$")
    private val fieldPattern = Regex("^((?:(?:nointerpolation|linear|noperspective|centroid|sample)\\s+)*)([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*:\\s*([A-Za-z_][A-Za-z_0-9]*)$")

    fun buildTerrainFragment(shader: D3D12CompiledShader, motionTarget: Int,
        contract: D3D12RasterMotionContract): String? {
        require(contract.source == D3D12RasterMotionSource.CAMERA_REPROJECTION ||
            contract.source == D3D12RasterMotionSource.CONTRIBUTION_ONLY)
        if (motionTarget !in 1..7 || shader.fragmentHlsl.contains("DXRMRaster")) return null
        val fragmentEntry = entry(shader.fragmentHlsl) ?: return null
        val fragmentFields = if (fragmentEntry.semantic != null) {
            listOf(Field("${fragmentEntry.result} original : ${fragmentEntry.semantic}",
                fragmentEntry.result, "original", fragmentEntry.semantic))
        } else fields(shader.fragmentHlsl, fragmentEntry.result) ?: return null
        val targets = fragmentFields.filter { it.semantic.startsWith("SV_Target", true) }
        if (targets.isEmpty() || targets.any {
                it.semantic.substring(9).toIntOrNull()?.let { index -> index >= motionTarget } ?: true
            }) return null
        val positions = fragmentEntry.arguments.flatMap { argument ->
            if (argument.semantic != null) {
                if (argument.semantic.equals("SV_Position", true) && argument.type == "float4")
                    listOf(argument.name) else emptyList()
            } else fields(shader.fragmentHlsl, argument.type)?.filter {
                it.semantic.equals("SV_Position", true) && it.type == "float4"
            }?.map { "${argument.name}.${it.name}" } ?: return null
        }
        if (positions.size > 1) return null
        val arguments = fragmentEntry.arguments.map { it.declaration }.toMutableList()
        val pixelPosition = positions.singleOrNull() ?: "DXRMRasterPosition".also {
            arguments.add("float4 $it : SV_Position")
        }
        val depth = fragmentFields.singleOrNull {
            it.semantic.equals("SV_Depth", true) || it.semantic.equals("SV_DepthGreaterEqual", true) ||
                it.semantic.equals("SV_DepthLessEqual", true)
        }
        if (depth != null && depth.type != "float") return null
        val originalDepth = when {
            depth == null -> "$pixelPosition.z"
            fragmentEntry.semantic != null -> "original"
            else -> "original.${depth.name}"
        }
        val color = targets.singleOrNull { it.semantic.equals("SV_Target0", true) && it.type == "float4" }
            ?: return null
        val value = if (fragmentEntry.semantic != null) "original" else "original.${color.name}"
        val copies = if (fragmentEntry.semantic != null) "output.original = original;" else
            fragmentFields.joinToString("\n") { "output.${it.name} = original.${it.name};" }
        val surfaceMotion = surfaceMotion(contract, value, originalDepth)
        return renamed(shader.fragmentHlsl, fragmentEntry) + "\n" + """
            struct DXRMRasterTerrainFragmentOutput {
                ${fragmentFields.joinToString("\n") { it.declaration + ";" }}
                float4 DXRMRasterMotion : SV_Target$motionTarget;
            };
            DXRMRasterTerrainFragmentOutput main(${arguments.joinToString(", ")}) {
                ${fragmentEntry.result} original = DXRMRasterOriginalMain(${fragmentEntry.arguments.joinToString(", ") { it.name }});
                DXRMRasterTerrainFragmentOutput output = (DXRMRasterTerrainFragmentOutput)0;
                $copies
                $surfaceMotion
                return output;
            }
        """.trimIndent()
    }

    private fun surfaceMotion(contract: D3D12RasterMotionContract, value: String, originalDepth: String): String {
        val contributes = when (contract.composition) {
            D3D12RasterComposition.REPLACE -> "true"
            D3D12RasterComposition.SOURCE_OVER -> "$value.a != 0.0"
            D3D12RasterComposition.PREMULTIPLIED_OVER -> "$value.a != 0.0 || any($value.rgb != 0.0)"
            D3D12RasterComposition.ADD_ALPHA -> "any(($value.rgb * $value.a) != 0.0)"
            D3D12RasterComposition.ADD_COLOR,
            D3D12RasterComposition.ADD -> "any($value.rgb != 0.0)"
        }
        val replaces = when (contract.composition) {
            D3D12RasterComposition.REPLACE -> "true"
            D3D12RasterComposition.SOURCE_OVER,
            D3D12RasterComposition.PREMULTIPLIED_OVER -> "$value.a == 1.0"
            else -> "false"
        }
        val invalidDepth = when (contract.source) {
            D3D12RasterMotionSource.CELESTIAL_TRANSFORM -> D3D12RasterMotionTuple.CELESTIAL_INVALID
            D3D12RasterMotionSource.OIT_COMPOSITE -> D3D12RasterMotionTuple.OIT_INVALID
            else -> D3D12RasterMotionTuple.SURFACE_INVALID
        }
        val cameraSurface = if (contract.source == D3D12RasterMotionSource.CAMERA_REPROJECTION) """
            if (replaces && isfinite(depth)) {
                output.DXRMRasterMotion = float4(depth, 0.0, ${D3D12RasterMotionTuple.CAMERA_SURFACE}, 1.0);
            }
        """.trimIndent() else ""
        return """
            float depth = $originalDepth;
            bool contributes = !all(isfinite($value)) || ($contributes);
            bool replaces = all(isfinite($value)) && ($replaces);
            if (!contributes) return output;
            output.DXRMRasterMotion = float4(isfinite(depth) ? depth : 1.0, 0.0, $invalidDepth, 1.0);
            $cameraSurface
        """.trimIndent()
    }

    fun build(shader: D3D12CompiledShader, motionTarget: Int,
        contract: D3D12RasterMotionContract): D3D12RasterMotionProgram? {
        val source = contract.source
        if (motionTarget !in 1..7 || shader.vertexHlsl.contains("DXRMRaster") ||
            shader.fragmentHlsl.contains("DXRMRaster")) return null
        val vertexEntry = entry(shader.vertexHlsl) ?: return null
        val fragmentEntry = entry(shader.fragmentHlsl) ?: return null
        val vertexFields = fields(shader.vertexHlsl, vertexEntry.result) ?: return null
        if (vertexFields.singleOrNull { it.semantic.equals("SV_Position", true) && it.type == "float4" } == null) return null
        val fragmentFields = if (fragmentEntry.semantic != null) {
            listOf(Field("${fragmentEntry.result} original : ${fragmentEntry.semantic}",
                fragmentEntry.result, "original", fragmentEntry.semantic))
        } else fields(shader.fragmentHlsl, fragmentEntry.result) ?: return null
        val targets = fragmentFields.filter { it.semantic.startsWith("SV_Target", true) }
        if (targets.isEmpty() || targets.any {
                it.semantic.removePrefix("SV_Target").toIntOrNull()?.let { index -> index >= motionTarget } ?: true
            }) return null
        val usedLocations = Regex("\\bLOC([0-9]+)\\b").findAll(shader.vertexHlsl + "\n" + shader.fragmentHlsl)
            .map { it.groupValues[1].toInt() }.toList()
        val previousLocation = (usedLocations.maxOrNull() ?: -1) + 1
        if (previousLocation > 29) return null
        val parameterBinding = shader.entries.size
        val historyBinding = parameterBinding + 1
        val parameters = parameters(parameterBinding)
        val vertexId = vertexEntry.arguments.singleOrNull { it.semantic.equals("SV_VertexID", true) }
        if (vertexId != null && vertexId.type != "uint") return null
        val vertexArguments = vertexEntry.arguments.map { it.declaration }.toMutableList()
        if (vertexId == null) vertexArguments.add("uint DXRMRasterVertexId : SV_VertexID")
        val vertexIdName = vertexId?.name ?: "DXRMRasterVertexId"
        val vertexCalls = vertexEntry.arguments.joinToString(", ") { it.name }
        val vertexMotion = when (source) {
            D3D12RasterMotionSource.VERTEX_HISTORY -> """
                uint vertexId = $vertexIdName;
                if (DXRMRasterHistory.z != 0 && vertexId >= DXRMRasterHistory.x &&
                    vertexId - DXRMRasterHistory.x < DXRMRasterHistory.y) {
                    float4 previous = DXRMRasterHistoryBuffer[vertexId - DXRMRasterHistory.x];
                    if (previous.w > 0.5 && all(isfinite(previous.xyz))) {
                        output.DXRMRasterPreviousClip = mul(DXRMRasterPreviousViewProjection, float4(previous.xyz, 1.0));
                        output.DXRMRasterPreviousClip.y *= DXRMRasterJitter.z;
                        output.DXRMRasterHistoryValid = 1.0;
                    }
                }
            """.trimIndent()
            D3D12RasterMotionSource.CELESTIAL_TRANSFORM -> {
                val positions = vertexEntry.arguments.flatMap { argument ->
                    if (argument.semantic != null) {
                        if (argument.semantic == "LOC0" && argument.type == "float3") listOf(argument.name)
                        else emptyList()
                    } else fields(shader.vertexHlsl, argument.type)?.filter {
                        it.semantic == "LOC0" && it.type == "float3"
                    }?.map { "${argument.name}.${it.name}" } ?: emptyList()
                }
                val position = positions.singleOrNull() ?: return null
                """
                    if (DXRMRasterHistory.z != 0 && all(isfinite($position))) {
                        output.DXRMRasterPreviousClip = mul(DXRMRasterPreviousViewProjection, float4($position, 1.0));
                        output.DXRMRasterPreviousClip.y *= DXRMRasterJitter.z;
                        output.DXRMRasterHistoryValid = 1.0;
                    }
                """.trimIndent()
            }
            D3D12RasterMotionSource.CAMERA_REPROJECTION,
            D3D12RasterMotionSource.CONTRIBUTION_ONLY,
            D3D12RasterMotionSource.OIT_COMPOSITE -> ""
        }
        val vertex = renamed(shader.vertexHlsl, vertexEntry) + "\n" + parameters + "\n" + """
            Buffer<float4> DXRMRasterHistoryBuffer : register(t$historyBinding, space0);
            struct DXRMRasterVertexOutput {
                ${vertexFields.joinToString("\n") { it.declaration + ";" }}
                float4 DXRMRasterPreviousClip : LOC$previousLocation;
                noperspective float DXRMRasterHistoryValid : LOC${previousLocation + 1};
            };
            DXRMRasterVertexOutput main(${vertexArguments.joinToString(", ")}) {
                ${vertexEntry.result} original = DXRMRasterOriginalMain($vertexCalls);
                DXRMRasterVertexOutput output = (DXRMRasterVertexOutput)0;
                ${vertexFields.joinToString("\n") { "output.${it.name} = original.${it.name};" }}
                $vertexMotion
                return output;
            }
        """.trimIndent()
        val canonical = vertexFields.mapIndexed { index, field ->
            Field(field.declaration.replace(Regex("\\b${Regex.escape(field.name)}\\s*:"), "DXRMRasterInput$index :"),
                field.type, "DXRMRasterInput$index", field.semantic)
        }
        val originalInputs = LinkedHashMap<String, Field>()
        val initialization = ArrayList<String>()
        val fragmentArguments = mutableListOf("DXRMRasterFragmentInput DXRMRasterInput")
        val callArguments = ArrayList<String>()
        fun sourceFor(field: Field): String? {
            originalInputs[field.semantic] = field
            val matching = canonical.singleOrNull { it.semantic.equals(field.semantic, true) }
            if (matching != null) {
                if (matching.type != field.type) return null
                return "DXRMRasterInput.${matching.name}"
            }
            if (!field.semantic.startsWith("SV_", true)) return null
            val name = "DXRMRasterBuiltin${fragmentArguments.size}"
            fragmentArguments.add("${field.type} $name : ${field.semantic}")
            return name
        }
        for (argument in fragmentEntry.arguments) {
            if (argument.semantic != null) {
                val source = sourceFor(Field(argument.declaration, argument.type, argument.name, argument.semantic))
                    ?: return null
                callArguments.add(source)
            } else {
                val members = fields(shader.fragmentHlsl, argument.type) ?: return null
                initialization.add("${argument.type} ${argument.name} = (${argument.type})0;")
                for (field in members) {
                    val source = sourceFor(field) ?: return null
                    initialization.add("${argument.name}.${field.name} = $source;")
                }
                callArguments.add(argument.name)
            }
        }
        val pixelPosition = "DXRMRasterInput.${canonical.single { it.semantic.equals("SV_Position", true) }.name}"
        val fragmentMotionInput = "DXRMRasterInput.DXRMRasterPreviousClip"
        val missingInputs = canonical.filter { it.semantic.startsWith("LOC") && it.semantic !in originalInputs }
        if (missingInputs.any { !it.type.startsWith("float") }) return null
        val retainInputs = missingInputs.joinToString(" && ") {
            "all(isfinite(DXRMRasterInput.${it.name}))"
        }.ifEmpty { "true" }
        val depth = fragmentFields.singleOrNull {
            it.semantic.equals("SV_Depth", true) || it.semantic.equals("SV_DepthGreaterEqual", true) ||
                it.semantic.equals("SV_DepthLessEqual", true)
        }
        if (depth != null && depth.type != "float") return null
        val originalDepth = when {
            depth == null -> "$pixelPosition.z"
            fragmentEntry.semantic != null -> "original"
            else -> "original.${depth.name}"
        }
        val fragmentCopies = if (fragmentEntry.semantic != null) "output.original = original;" else
            fragmentFields.joinToString("\n") { "output.${it.name} = original.${it.name};" }
        val color = targets.singleOrNull { it.semantic.equals("SV_Target0", true) && it.type == "float4" }
            ?: return null
        val value = if (fragmentEntry.semantic != null) "original" else "original.${color.name}"
        val surfaceMotion = surfaceMotion(contract, value, originalDepth)
        val validDepth = if (source == D3D12RasterMotionSource.CELESTIAL_TRANSFORM)
            "${D3D12RasterMotionTuple.CELESTIAL_VALID}" else "depth"
        val fragment = renamed(shader.fragmentHlsl, fragmentEntry) + "\n" + parameters + "\n" + """
            struct DXRMRasterFragmentInput {
                ${canonical.joinToString("\n") { it.declaration + ";" }}
                float4 DXRMRasterPreviousClip : LOC$previousLocation;
                noperspective float DXRMRasterHistoryValid : LOC${previousLocation + 1};
            };
            struct DXRMRasterFragmentOutput {
                ${fragmentFields.joinToString("\n") { it.declaration + ";" }}
                float4 DXRMRasterMotion : SV_Target$motionTarget;
            };
            DXRMRasterFragmentOutput main(${fragmentArguments.joinToString(", ")}) {
                ${initialization.joinToString("\n")}
                ${fragmentEntry.result} original = DXRMRasterOriginalMain(${callArguments.joinToString(", ")});
                DXRMRasterFragmentOutput output = (DXRMRasterFragmentOutput)0;
                $fragmentCopies
                $surfaceMotion
                if (replaces && $retainInputs && DXRMRasterInput.DXRMRasterHistoryValid >= 1.0 && all(isfinite($fragmentMotionInput)) &&
                    $fragmentMotionInput.w > 0.0001 && isfinite(depth)) {
                    float2 previousNdc = $fragmentMotionInput.xy / $fragmentMotionInput.w;
                    float2 previousUv = float2(previousNdc.x * 0.5 + 0.5, 0.5 - previousNdc.y * 0.5);
                    float2 motion = previousUv * DXRMRasterViewport.zw + DXRMRasterViewport.xy - $pixelPosition.xy;
                    motion += float2(DXRMRasterJitter.x, -DXRMRasterJitter.y);
                    if (all(isfinite(motion)) && all(abs(motion) <= 65504.0)) {
                        output.DXRMRasterMotion = float4(motion, $validDepth, 1.0);
                    }
                }
                return output;
            }
        """.trimIndent()
        return D3D12RasterMotionProgram(vertex, fragment, parameterBinding, historyBinding)
    }

    internal fun buildOitAndMotion(shader: D3D12CompiledShader, guideSource: D3D12OitGuideSource,
        motionTarget: Int, contract: D3D12RasterMotionContract): D3D12OitRasterMotionProgram? {
        require(motionTarget > 1) { "Combined OIT motion requires a separate guide target" }
        val guide = buildOitFragment(shader, motionTarget - 1, guideSource) ?: return null
        val oitParameterBinding = if (guideSource == D3D12OitGuideSource.CLOUD) shader.entries.size else null
        val motionShader = shader.copy(
            fragmentHlsl = guide,
            entries = if (oitParameterBinding == null) shader.entries else shader.entries +
                D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER,
                    "IllusionOitParameters", null),
        )
        val motion = build(motionShader, motionTarget, contract) ?: return null
        return D3D12OitRasterMotionProgram(motion, oitParameterBinding)
    }

    private fun entry(source: String): Entry? {
        val match = entryPattern.find(source) ?: return null
        val arguments = ArrayList<Argument>()
        val declaration = match.groupValues[2].trim()
        if (declaration.isNotEmpty()) {
            for (part in declaration.split(',')) {
                val argument = part.trim()
                val parsed = argumentPattern.matchEntire(argument) ?: return null
                arguments.add(Argument(argument, parsed.groupValues[1], parsed.groupValues[2],
                    parsed.groupValues[3].ifEmpty { null }))
            }
        }
        return Entry(match, match.groupValues[1], arguments, match.groupValues[3].ifEmpty { null })
    }

    private fun fields(source: String, type: String): List<Field>? {
        val structure = Regex("\\bstruct ${Regex.escape(type)}\\s*\\{([^}]*)}\\s*;", RegexOption.DOT_MATCHES_ALL)
            .find(source) ?: return null
        val result = ArrayList<Field>()
        for (member in structure.groupValues[1].split(';')) {
            val declaration = member.trim()
            if (declaration.isEmpty()) continue
            val field = fieldPattern.matchEntire(declaration) ?: return null
            result.add(Field(declaration, field.groupValues[2], field.groupValues[3], field.groupValues[4]))
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun renamed(source: String, entry: Entry): String {
        val signature = entry.match.value.replaceFirst(" main(", " DXRMRasterOriginalMain(")
        return source.replaceRange(entry.match.range, signature)
    }

    private fun parameters(binding: Int): String = """
        cbuffer DXRMRasterMotionParams : register(b$binding, space0) {
            float4x4 DXRMRasterPreviousViewProjection;
            float4 DXRMRasterViewport;
            float4 DXRMRasterJitter;
            uint4 DXRMRasterHistory;
        };
    """.trimIndent()
}
