package io.github.dxegp.d3d12.shader

data class D3D12MeshVertexProgram(
    val source: String,
    val inputs: List<D3D12ShaderIo>,
    val uniforms: Map<String, String>,
)

object NagaVertexAdapter {
    fun adapt(shader: D3D12CompiledShader): D3D12MeshVertexProgram? {
        val source = shader.vertexHlsl
        val entry = Regex("(?m)^(\\w+) main\\(([^)]*)\\)").find(source) ?: return null
        val inputs = shader.vertexInputs.associateBy { it.location }
        val arguments = mutableListOf<String>()
        for (parameter in entry.groupValues[2].split(',').filter { it.isNotBlank() }) {
            val parsed = Regex("^\\s*(\\w+)\\s+\\w+\\s*:\\s*LOC(\\d+)\\s*$")
                .matchEntire(parameter) ?: return null
            val input = inputs[parsed.groupValues[2].toInt()] ?: return null
            if (input.hlslType != parsed.groupValues[1]) return null
            arguments += "input.input${input.location}"
        }
        if (arguments.size != inputs.size) return null
        val renamed = source.replaceRange(entry.range, entry.value.replace(" main(", " dxrVertexMain("))
        val wrapper = buildString {
            appendLine("struct D3D12VertexInput {")
            for (input in shader.vertexInputs) appendLine("    ${input.hlslType} input${input.location};")
            appendLine("};")
            appendLine("typedef ${entry.groupValues[1]} D3D12VertexOutput;")
            appendLine("D3D12VertexOutput vs_main(D3D12VertexInput input) {")
            appendLine("    return dxrVertexMain(${arguments.joinToString(", ")});")
            appendLine("}")
        }
        val uniforms = mutableMapOf<String, String>()
        val blocks = Regex("cbuffer\\s+\\w+\\s*:[^{]+\\{([^}]+)}", RegexOption.DOT_MATCHES_ALL)
        for (block in blocks.findAll(source)) {
            val instance = Regex("\\b(\\w+)\\s+(\\w+)\\s*;").find(block.groupValues[1]) ?: continue
            val struct = Regex("struct\\s+${Regex.escape(instance.groupValues[1])}\\s*\\{([^}]+)};",
                RegexOption.DOT_MATCHES_ALL).find(source) ?: continue
            for (member in Regex("\\b(?:float[234]?(?:x[234])?|int[234]?|uint[234]?)\\s+(\\w+)\\s*;")
                .findAll(struct.groupValues[1])) {
                uniforms[member.groupValues[1]] = "${instance.groupValues[2]}.${member.groupValues[1]}"
            }
        }
        return D3D12MeshVertexProgram("$renamed\n$wrapper", shader.vertexInputs, uniforms.toMap())
    }
}
