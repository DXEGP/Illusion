package io.github.dxegp.diagnostics

import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.shader.D3D12CompiledShader
import io.github.dxegp.d3d12.shader.D3D12ShaderOutput
import java.nio.file.Files
import java.nio.file.Path

internal object ShaderDumps : D3D12ShaderOutput {
    override fun write(shader: D3D12CompiledShader) {
        if (!D3D12Settings.dumpHlsl) return
        val directory = Path.of("dxrmc", "hlsl")
        Files.createDirectories(directory)
        val stamp = (shader.vertexHlsl + shader.fragmentHlsl).hashCode().toUInt().toString(16)
        Files.writeString(directory.resolve("vs_$stamp.hlsl"), shader.vertexHlsl)
        Files.writeString(directory.resolve("ps_$stamp.hlsl"), shader.fragmentHlsl)
    }
}
