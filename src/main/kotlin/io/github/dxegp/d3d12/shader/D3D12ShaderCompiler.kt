package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.util.ShaderCompileException
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path

class D3D12ShaderCompiler : AutoCloseable {
    private val globalDefines = ShaderDefines.builder()
        .define("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")
        .build()

    fun createIntermediary(filename: String, source: String, type: ShaderType,
                           includeResolver: ((Identifier) -> String?)? = null): D3D12IntermediaryShaderModule {
        val defined = injectShaderDefines(source, globalDefines)
        val expanded = includeResolver?.let { expandShaderIncludes(defined, it) } ?: defined
        val conversion = NagaShaderProcessor.convert(filename, expanded, type)
        return D3D12IntermediaryShaderModule(filename, expanded, type, conversion)
    }

    fun compile(pipeline: RenderPipeline, vertex: D3D12IntermediaryShaderModule,
                fragment: D3D12IntermediaryShaderModule, flipY: Boolean): D3D12CompiledShader {
        val entries = collectBindings(pipeline, vertex, fragment)
        val bindings = entries.mapIndexed { index, entry -> entry.name to index }.toMap()
        val attributes = pipeline.vertexFormatBindings.filterNotNull()
            .flatMap { format -> format.elements.map { it.name() } }
        val location = pipeline.location.toString()
        val vertexResult = NagaShaderProcessor.convert(vertex.filename, vertex.source, vertex.stage,
            bindings = bindings, vertexAttributes = attributes,
            flipY = flipY || location.contains("animate_sprite"))
        val isScreenQuad = pipeline.shaders[ShaderType.VERTEX]?.toString() == "minecraft:core/screenquad"
        val flipTexcoord = !flipY && (location.contains("lightmap") ||
            (isScreenQuad && SAMPLES_WITH_TEXCOORD.containsMatchIn(fragment.source)))
        val fragmentResult = NagaShaderProcessor.convert(fragment.filename, fragment.source, fragment.stage,
            bindings = bindings, vertexOutputs = vertexResult.manifest.stageIo.outputs,
            flipTexcoord = flipTexcoord)
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
        if (D3D12Settings.dumpHlsl) {
            val directory = Path.of("dxrmc", "hlsl")
            Files.createDirectories(directory)
            val stamp = (vertexResult.hlsl + fragmentResult.hlsl).hashCode().toUInt().toString(16)
            Files.writeString(directory.resolve("vs_$stamp.hlsl"), vertexResult.hlsl)
            Files.writeString(directory.resolve("ps_$stamp.hlsl"), fragmentResult.hlsl)
        }
        return D3D12CompiledShader(vertexResult.hlsl, fragmentResult.hlsl, entries, inputs, rootConstants)
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
            val resources = requireNotNull(shader.conversion).manifest.resources
            if (resources.storageImages.isNotEmpty()) {
                throw ShaderCompileException("RenderPipeline does not expose storage images: ${shader.filename}")
            }
            for (buffer in resources.constantBuffers) {
                val name = buffer.uniformName
                val uniform = uniforms[name]
                    ?: throw ShaderCompileException("Missing uniform buffer: $name")
                if (uniform.type() == UniformType.TEXEL_BUFFER ||
                    uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                    throw ShaderCompileException("Uniform buffer type mismatch: $name")
                }
                add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, name, null))
            }
            for (texture in resources.textures) {
                if (uniforms[texture.name]?.type() != UniformType.COMBINED_IMAGE_SAMPLER) {
                    throw ShaderCompileException("Missing sampled texture: ${texture.name}")
                }
                add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.SAMPLED_IMAGE, texture.name, null))
            }
            for (sampler in resources.samplers) {
                if (sampler.comparison || resources.textures.none { it.name == sampler.texture }) {
                    throw ShaderCompileException("Unsupported vanilla sampler: ${sampler.name}")
                }
            }
            for (buffer in resources.storageBuffers) {
                val uniform = uniforms[buffer.name]
                if (uniform?.type() != UniformType.TEXEL_BUFFER || uniform.gpuFormat() != GpuFormat.R8_SINT ||
                    buffer.hlslType != "ByteAddressBuffer") {
                    throw ShaderCompileException("Unsupported vanilla buffer: ${buffer.name}")
                }
                add(D3D12BindGroupEntry(D3D12BindGroupEntry.Type.TEXEL_BUFFER, buffer.name,
                    uniform.gpuFormat(), rawBuffer = true))
            }
        }
        return entries.values.toList()
    }

    private fun validateBindings(result: D3D12ShaderConversion, bindings: Map<String, Int>) {
        val resources = result.manifest.resources
        for (resource in resources.constantBuffers + resources.textures + resources.storageBuffers + resources.samplers) {
            val name = resource.texture ?: resource.uniformName
            if (resource.space != 0 || bindings[name] != resource.register) {
                throw ShaderCompileException("naga register mismatch: $name")
            }
        }
        for (resource in resources.constantBuffers + resources.textures + resources.storageBuffers + resources.samplers) {
            if (resource.space == D3D12RootConstantLayoutContract.SPACE &&
                resource.register == D3D12RootConstantLayoutContract.REGISTER) {
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
        if (layout.register != D3D12RootConstantLayoutContract.REGISTER ||
            layout.space != D3D12RootConstantLayoutContract.SPACE ||
            layout.byteSize != layout.dwordCount * 4 ||
            layout.dwordCount <= 0 || layout.dwordCount > D3D12RootConstantLayoutContract.MAX_DWORDS ||
            declaredBytes == 0 || layout.byteSize > declaredBytes) {
            throw ShaderCompileException("invalid push-constant metadata or pipeline size")
        }
        return layout
    }

    override fun close() = Unit

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
