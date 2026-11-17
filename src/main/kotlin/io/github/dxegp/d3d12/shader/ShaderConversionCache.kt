package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.ShaderType
import java.io.DataOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal data class ShaderConversionRequest(
    val filename: String,
    val source: String,
    val stage: ShaderType,
    val bindings: Map<String, Int> = emptyMap(),
    val vertexAttributes: List<String> = emptyList(),
    val vertexOutputs: List<D3D12ShaderIo> = emptyList(),
    val flipY: Boolean = false,
    val flipTexcoord: Boolean = false,
    val declaredRootConstantBytes: Int = 0,
    val rootConstantLayout: D3D12RootConstantLayout? = null,
    val variantContract: String = "",
) {
    fun fingerprint(toolFingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(BufferedOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest))).use { output ->
            fun text(value: String) {
                output.writeInt(value.length)
                output.writeChars(value)
            }
            text("illusion.shader-conversion-cache/2")
            text(NagaShaderProcessor.MANIFEST_SCHEMA)
            text(toolFingerprint)
            text(filename)
            text(source)
            text(variantContract)
            text(stage.name)
            text("6_9/host")
            output.writeInt(D3D12RootConstantLayoutContract.REGISTER)
            output.writeInt(D3D12RootConstantLayoutContract.SPACE)
            output.writeInt(D3D12RootConstantLayoutContract.MAX_DWORDS)
            output.writeInt(D3D12RootConstantLayoutContract.MAX_ROOT_DWORDS)
            output.writeInt(bindings.size)
            bindings.forEach { (name, register) -> text(name); output.writeInt(register) }
            output.writeInt(vertexAttributes.size)
            vertexAttributes.forEach(::text)
            output.writeInt(vertexOutputs.size)
            vertexOutputs.forEach { stageIo ->
                text(stageIo.name)
                output.writeInt(stageIo.location)
                text(stageIo.hlslType)
                output.writeBoolean(stageIo.interpolation != null)
                stageIo.interpolation?.let(::text)
            }
            output.writeBoolean(flipY)
            output.writeBoolean(flipTexcoord)
            output.writeInt(declaredRootConstantBytes)
            output.writeBoolean(rootConstantLayout != null)
            rootConstantLayout?.let { layout ->
                output.writeInt(layout.register)
                output.writeInt(layout.space)
                output.writeInt(layout.byteSize)
                output.writeInt(layout.dwordCount)
                output.writeInt(layout.members.size)
                layout.members.forEach { member ->
                    text(member.name)
                    text(member.hlslType)
                    output.writeInt(member.offset)
                    output.writeInt(member.size)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal class ShaderConversionCache(
    private val toolFingerprint: () -> String = NagaShaderProcessor::toolFingerprint,
    private val convert: (ShaderConversionRequest) -> D3D12ShaderConversion = NagaShaderProcessor::convert,
    maxEntries: Int = 512,
    maxBytes: Long = 32L * 1024 * 1024,
) : AutoCloseable {
    private val completed = CompletedCompilationCache<D3D12ShaderConversion>(maxEntries, maxBytes) { result ->
        result.retainedBytes()
    }

    val generation: Long get() = completed.generation

    fun checkGeneration(expected: Long) = completed.checkGeneration(expected)

    fun convert(expected: Long, request: ShaderConversionRequest): D3D12ShaderConversion {
        completed.checkGeneration(expected)
        val snapshot = request.copy(
            bindings = java.util.Collections.unmodifiableMap(LinkedHashMap(request.bindings)),
            vertexAttributes = java.util.List.copyOf(request.vertexAttributes),
            vertexOutputs = java.util.List.copyOf(request.vertexOutputs),
            rootConstantLayout = request.rootConstantLayout?.let { layout ->
                layout.copy(members = java.util.List.copyOf(layout.members))
            },
        )
        return completed.getOrCompile(expected, snapshot.fingerprint(toolFingerprint())) {
            convert(snapshot).immutableCopy()
        }
    }

    fun invalidate(): Long = completed.invalidate()

    override fun close() = completed.close()
}

private fun D3D12ShaderConversion.immutableCopy(): D3D12ShaderConversion = copy(
    manifest = manifest.copy(
        resources = manifest.resources.let { resources ->
            resources.copy(
                constantBuffers = java.util.List.copyOf(resources.constantBuffers),
                textures = java.util.List.copyOf(resources.textures),
                samplers = java.util.List.copyOf(resources.samplers),
                storageBuffers = java.util.List.copyOf(resources.storageBuffers),
                storageImages = java.util.List.copyOf(resources.storageImages),
            )
        },
        stageIo = manifest.stageIo.let { stageIo ->
            stageIo.copy(inputs = java.util.List.copyOf(stageIo.inputs), outputs = java.util.List.copyOf(stageIo.outputs))
        },
        pushConstants = manifest.pushConstants?.let { layout ->
            layout.copy(members = java.util.List.copyOf(layout.members))
        },
    ),
)

private fun D3D12ShaderConversion.retainedBytes(): Long {
    fun String.bytes(): Long = length * 2L + 48
    fun D3D12ShaderIo.bytes(): Long = 48 + name.bytes() + hlslType.bytes() + (interpolation?.bytes() ?: 0)
    fun D3D12ShaderResource.bytes(): Long = 64 + name.bytes() + (hlslType?.bytes() ?: 0) + (texture?.bytes() ?: 0)
    val resources = manifest.resources
    val resourceBytes = listOf(resources.constantBuffers, resources.textures, resources.samplers,
        resources.storageBuffers, resources.storageImages).sumOf { list -> 64 + list.sumOf { it.bytes() + 8 } }
    val ioBytes = listOf(manifest.stageIo.inputs, manifest.stageIo.outputs).sumOf { list ->
        64 + list.sumOf { it.bytes() + 8 }
    }
    val rootBytes = manifest.pushConstants?.members?.sumOf { member ->
        64 + member.name.bytes() + member.hlslType.bytes()
    } ?: 0
    return 512 + hlsl.bytes() + manifest.schema.bytes() + resourceBytes + ioBytes + rootBytes
}
