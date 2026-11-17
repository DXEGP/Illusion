package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.util.ShaderCompileException
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier

internal fun interface D3D12ShaderOutput {
    fun write(shader: D3D12CompiledShader)
}

class D3D12ShaderCompiler internal constructor(private val output: D3D12ShaderOutput) : AutoCloseable {
    private val conversions = ShaderConversionCache()
    val generation: Long get() = conversions.generation

    fun invalidate(): Long = conversions.invalidate()

    internal val globalDefines = ShaderDefines.builder()
        .define("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")
        .build()

    fun createIntermediary(filename: String, source: String, type: ShaderType,
                           includeResolver: ((Identifier) -> String?)? = null): D3D12IntermediaryShaderModule {
        val expected = generation
        val defined = injectShaderDefines(source, globalDefines)
        val expanded = includeResolver?.let { expandShaderIncludes(defined, it) } ?: defined
        val conversion = conversions.convert(expected, ShaderConversionRequest(filename, expanded, type))
        return D3D12IntermediaryShaderModule(filename, expanded, type, conversion, expected, this)
    }

    fun compile(pipeline: RenderPipeline, vertex: D3D12IntermediaryShaderModule,
                fragment: D3D12IntermediaryShaderModule, flipY: Boolean): D3D12CompiledShader {
        val expected = generation
        check(vertex.compilerIdentity === this && fragment.compilerIdentity === this) {
            "Shader module belongs to another compiler"
        }
        conversions.checkGeneration(vertex.compilationGeneration)
        conversions.checkGeneration(fragment.compilationGeneration)
        val entries = collectBindings(pipeline, vertex, fragment)
        val bindings = entries.mapIndexed { index, entry -> entry.name to index }.toMap()
        val attributes = pipeline.vertexFormatBindings.filterNotNull()
            .flatMap { format -> format.elements.map { it.name() } }
        val location = pipeline.location.toString()
        val vertexResult = conversions.convert(expected, ShaderConversionRequest(vertex.filename, vertex.source, vertex.stage,
            bindings = bindings, vertexAttributes = attributes,
            flipY = flipY || location.contains("animate_sprite"),
            declaredRootConstantBytes = pipeline.pushConstantSize(),
            rootConstantLayout = vertex.conversion?.manifest?.pushConstants))
        val isScreenQuad = pipeline.shaders[ShaderType.VERTEX]?.toString() == "minecraft:core/screenquad"
        val flipTexcoord = !flipY && (location.contains("lightmap") ||
            (isScreenQuad && SAMPLES_WITH_TEXCOORD.containsMatchIn(fragment.source)))
        val fragmentResult = conversions.convert(expected, ShaderConversionRequest(fragment.filename, fragment.source, fragment.stage,
            bindings = bindings, vertexOutputs = vertexResult.manifest.stageIo.outputs,
            flipTexcoord = flipTexcoord, declaredRootConstantBytes = pipeline.pushConstantSize(),
            rootConstantLayout = fragment.conversion?.manifest?.pushConstants))
        validateBindings(vertexResult, bindings)
        validateBindings(fragmentResult, bindings)
        val rootConstants = mergeRootConstants(vertexResult.manifest.pushConstants,
            fragmentResult.manifest.pushConstants, pipeline.pushConstantSize())
        val inputs = vertexResult.manifest.stageIo.inputs
        for (input in inputs) {
            if (input.name !in attributes) {
                throw ShaderCompileException("Vertex input ${input.name} is absent from ${pipeline.location}")
            }
        }
        var compiled = D3D12CompiledShader(vertexResult.hlsl, fragmentResult.hlsl, java.util.List.copyOf(entries),
            inputs, rootConstants, vertexResult.manifest.stageIo.outputs)
        compiled = compiled.copy(oitNormalization = D3D12OitNormalizationSource.certify(
            pipeline, globalDefines, vertex.source, fragment.source, compiled, expected, flipY,
        ))
        val tileCertification = D3D12OitCaptureSource.certifyReplayStage(fragment.source, pipeline,
            globalDefines, vertex.source)
        val tileCaptureIdentity = tileCertification?.let {
            val prelude = MCDR_ABI.D3D12OitShaderPreludeV1()
            check(prelude.abi == D3D12OitCaptureSource.SHADER_ABI_VERSION && prelude.hash != 0L) {
                "Native OIT capture shader contract is unavailable"
            }
            D3D12OitCaptureIdentity(prelude.abi, prelude.hash, expected, it.profile)
        }
        compiled = compiled.copy(oitTile = when {
            tileCertification != null -> D3D12OitTileShader.build(compiled, pipeline, tileCertification,
                expected, flipY)
            "OIT" in pipeline.shaderDefines.flags() -> D3D12OitTileCompilation.Unsupported(
                D3D12OitTileUnsupported.SOURCE_CONTRACT)
            else -> D3D12OitTileCompilation.NotApplicable
        }, oitTileCaptureIdentity = tileCaptureIdentity)
        val oitProfile = D3D12OitCaptureSource.certify(fragment.source, pipeline, globalDefines, vertex.source)
        if (oitProfile != null) {
            val prelude = MCDR_ABI.D3D12OitShaderPreludeV1()
            check(prelude.abi == D3D12OitCaptureSource.SHADER_ABI_VERSION && prelude.hash != 0L) {
                "Native OIT capture shader contract is unavailable"
            }
            val captureSource = D3D12OitCaptureSource.instrument(fragment.source, oitProfile)
            val capture = conversions.convert(expected, ShaderConversionRequest(fragment.filename, captureSource,
                fragment.stage, bindings = bindings, vertexOutputs = vertexResult.manifest.stageIo.outputs,
                flipTexcoord = flipTexcoord, declaredRootConstantBytes = pipeline.pushConstantSize(),
                rootConstantLayout = fragment.conversion?.manifest?.pushConstants,
                variantContract = "${D3D12OitCaptureSource.SCHEMA}/${prelude.abi}/${prelude.hash}/$oitProfile/" +
                    "${oitProfile.previousDomain}/perspective-barycentrics/primitive-1/$flipY"))
            validateBindings(capture, bindings)
            compiled = compiled.copy(oitCapture = D3D12RasterMotionShader.buildOitCapture(compiled, capture.hlsl,
                D3D12OitCaptureContract(oitProfile, prelude, expected)))
        }
        output.write(compiled)
        conversions.checkGeneration(expected)
        return compiled
    }

    private fun collectBindings(pipeline: RenderPipeline,
                                vararg shaders: D3D12IntermediaryShaderModule): List<D3D12BindGroupEntry> {
        val uniforms = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts).associateBy { it.name() }
        val entries = linkedMapOf<String, D3D12BindGroupEntry>()
        fun add(entry: D3D12BindGroupEntry) {
            val previous = entries.putIfAbsent(entry.name, entry)
            if (previous != null && previous != entry) {
                throw ShaderCompileException("Conflicting shader resource: ${entry.name}")
            }
        }
        for (shader in shaders) {
            (val constantBuffers, val textures, val samplers, val storageBuffers, val storageImages) = requireNotNull(shader.conversion).manifest.resources
            if (storageImages.isNotEmpty()) {
                throw ShaderCompileException("RenderPipeline does not expose storage images: ${shader.filename}")
            }
            for (buffer in constantBuffers) {
                val name = buffer.uniformName
                val uniform = uniforms[name]
                    ?: throw ShaderCompileException("Missing uniform buffer: $name")
                if (uniform.type() == UniformType.TEXEL_BUFFER ||
                    uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                    throw ShaderCompileException("Uniform buffer type mismatch: $name")
                }
                add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, name, null))
            }
            for (texture in textures) {
                if (uniforms[texture.name]?.type() != UniformType.COMBINED_IMAGE_SAMPLER) {
                    throw ShaderCompileException("Missing sampled texture: ${texture.name}")
                }
                add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.SAMPLED_IMAGE, texture.name, null))
            }
            for ((val name, val texture, val comparison) in samplers) {
                if (comparison || textures.none { it.name == texture }) {
                    throw ShaderCompileException("Unsupported vanilla sampler: $name")
                }
            }
            for ((val name, val hlslType) in storageBuffers) {
                val uniform = uniforms[name]
                if (uniform?.type() != UniformType.TEXEL_BUFFER || uniform.gpuFormat() != GpuFormat.R8_SINT ||
                    hlslType != "ByteAddressBuffer") {
                    throw ShaderCompileException("Unsupported vanilla buffer: $name")
                }
                add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.TEXEL_BUFFER, name,
                    uniform.gpuFormat(), rawBuffer = true))
            }
        }
        return entries.values.toList()
    }

    private fun validateBindings(result: D3D12ShaderConversion, bindings: Map<String, Int>) {
        (val constantBuffers, val textures, val samplers, val storageBuffers) = result.manifest.resources
        for (resource in buildList {
            addAll(constantBuffers)
            addAll(textures)
            addAll(storageBuffers)
            addAll(samplers)
        }) {
            val name = resource.texture ?: resource.uniformName
            if (resource.space != 0 || bindings[name] != resource.register) {
                throw ShaderCompileException("naga register mismatch: $name")
            }
        }
        for ((val register, val space) in buildList {
            addAll(constantBuffers)
            addAll(textures)
            addAll(storageBuffers)
            addAll(samplers)
        }) {
            if (space == D3D12RootConstantLayoutContract.SPACE &&
                register == D3D12RootConstantLayoutContract.REGISTER) {
                throw ShaderCompileException("naga resource collides with reserved root constants b0,space1")
            }
        }
    }

    private fun mergeRootConstants(
        vertex: D3D12RootConstantLayout?,
        fragment: D3D12RootConstantLayout?,
        declaredBytes: Int,
    ): D3D12RootConstantLayout? {
        val layout = vertex ?: fragment
        if (vertex != null && fragment != null && vertex != fragment) {
            throw ShaderCompileException("vertex and fragment push-constant layouts differ")
        }
        if (declaredBytes < 0 || declaredBytes % 4 != 0 ||
            declaredBytes > D3D12RootConstantLayoutContract.MAX_DWORDS * 4) {
            throw ShaderCompileException("push constant size must be 4-byte aligned and <= 128 bytes")
        }
        if (layout == null) {
            if (declaredBytes != 0) throw ShaderCompileException("pipeline declares push constants but shaders have no push-constant block")
            return null
        }
        if ((layout.register != D3D12RootConstantLayoutContract.REGISTER) ||
            (layout.space != D3D12RootConstantLayoutContract.SPACE) ||
            (layout.byteSize != (layout.dwordCount * 4)) ||
            (layout.dwordCount <= 0) || (layout.dwordCount > D3D12RootConstantLayoutContract.MAX_DWORDS) ||
            (declaredBytes == 0) || (layout.byteSize > declaredBytes)
        ) {
            throw ShaderCompileException("invalid push-constant metadata or pipeline size")
        }
        return layout
    }

    override fun close() = conversions.close()

    private companion object {
        val SAMPLES_WITH_TEXCOORD = Regex("\\btexture\\w*\\s*\\([^,]+,\\s*texCoord\\b")
    }
}

internal fun injectShaderDefines(source: String, defines: ShaderDefines): String {
    if (defines.isEmpty) return source
    val directives = defines.asSourceDirectives().let { if (it.endsWith('\n')) it else "$it\n" }
    val version = source.indexOf("#version")
    if (version < 0) return directives + source
    val lineEnd = source.indexOf('\n', version)
    if (lineEnd < 0) return "$source\n$directives"
    return source.substring(0, lineEnd + 1) + directives + source.substring(lineEnd + 1)
}

private val GLSL_INCLUDE_PATTERN = Regex("^[ \\t]*#[ \\t]*include[ \\t]+<([^>\\r\\n]+)>[ \\t]*\\r?$", RegexOption.MULTILINE)
private const val MAX_INCLUDE_DEPTH = 16
private const val MAX_EXPANDED_CHARS = 8 * 1024 * 1024

internal fun expandShaderIncludes(source: String, includeResolver: (Identifier) -> String?): String {
    var expandedChars = source.length
    fun expand(text: String, depth: Int): String = GLSL_INCLUDE_PATTERN.replace(text) { match ->
        if (depth >= MAX_INCLUDE_DEPTH) throw ShaderCompileException("Shader include nesting exceeds $MAX_INCLUDE_DEPTH")
        val identifier = Identifier.parse(match.groupValues[1].trim())
        val included = includeResolver(identifier)
            ?: throw ShaderCompileException("Shader include is missing: $identifier")
        expandedChars += included.length
        if (expandedChars > MAX_EXPANDED_CHARS) throw ShaderCompileException("Shader include expansion exceeds 8 MiB")
        expand(included, depth + 1) + "\n"
    }
    return expand(source, 0)
}
