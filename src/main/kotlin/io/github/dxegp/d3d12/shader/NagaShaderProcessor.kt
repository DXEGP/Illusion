package io.github.dxegp.d3d12.shader

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.util.ShaderCompileException
import io.github.dxegp.d3d12.ffi.MCDR_ABI

data class D3D12ShaderIo(
    val name: String,
    val location: Int,
    @SerializedName("hlsl_type") val hlslType: String,
    val interpolation: String?,
)

data class D3D12ShaderResource(
    val name: String,
    @SerializedName("hlsl_type") val hlslType: String?,
    val register: Int,
    val space: Int,
    val texture: String?,
    val comparison: Boolean,
) {
    val uniformName: String get() = name.removePrefix("constants_")
}

data class D3D12ShaderResources(
    @SerializedName("constant_buffers") val constantBuffers: List<D3D12ShaderResource>,
    val textures: List<D3D12ShaderResource>,
    val samplers: List<D3D12ShaderResource>,
    @SerializedName("storage_buffers") val storageBuffers: List<D3D12ShaderResource>,
    @SerializedName("storage_images") val storageImages: List<D3D12ShaderResource>,
)

data class D3D12ShaderInterface(val inputs: List<D3D12ShaderIo>, val outputs: List<D3D12ShaderIo>)

data class D3D12ShaderManifest(
    val schema: String,
    val resources: D3D12ShaderResources,
    @SerializedName("stage_io") val stageIo: D3D12ShaderInterface,
)

data class D3D12ShaderConversion(val hlsl: String, val manifest: D3D12ShaderManifest)

internal object NagaShaderProcessor {
    private val gson = Gson()

    fun convert(
        filename: String,
        source: String,
        stage: ShaderType,
        bindings: Map<String, Int> = emptyMap(),
        vertexAttributes: List<String> = emptyList(),
        vertexOutputs: List<D3D12ShaderIo> = emptyList(),
        flipY: Boolean = false,
        flipTexcoord: Boolean = false,
    ): D3D12ShaderConversion {
        val request = JsonObject().apply {
            addProperty("path", filename)
            addProperty("source", source)
            addProperty("stage", if (stage == ShaderType.FRAGMENT) "fragment" else "vertex")
            addProperty("shader_model", "6_9")
            addProperty("register_layout", "host")
            add("bindings", gson.toJsonTree(bindings))
            add("vertex_attributes", gson.toJsonTree(vertexAttributes))
            add("fragment_inputs", gson.toJsonTree(vertexOutputs.associate { it.name to it.location }))
            add("vertex_outputs", gson.toJsonTree(vertexOutputs.map { output ->
                JsonObject().apply {
                    addProperty("location", output.location)
                    addProperty("hlsl_type", output.hlslType)
                    output.interpolation?.let { addProperty("interpolation", it) }
                }
            }))
            addProperty("flip_y", flipY)
            if (flipTexcoord) addProperty("flip_texcoord", "texCoord")
        }
        try {
            val response = JsonParser.parseString(MCDR_ABI.D3D12ConvertShader(request.toString())).asJsonObject
            if (response["ok"]?.asBoolean != true) {
                throw ShaderCompileException("naga: $filename: ${response["message"]?.asString}")
            }
            val manifest = gson.fromJson(response["manifest"], D3D12ShaderManifest::class.java)
            check(manifest.schema == "illusion.shader-manifest/1") { "Unsupported naga manifest" }
            return D3D12ShaderConversion(response["hlsl"].asString, manifest)
        } catch (error: ShaderCompileException) {
            throw error
        } catch (error: Exception) {
            throw ShaderCompileException("naga: $filename: ${error.message}")
        }
    }
}
