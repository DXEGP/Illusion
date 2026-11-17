package io.github.dxegp.d3d12.shader

import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry

data class D3D12CompiledShader(
    val vertexHlsl: String,
    val fragmentHlsl: String,
    val entries: List<D3D12BindGroupEntry>,
    val vertexInputs: List<D3D12ShaderIo>,
    val rootConstants: D3D12RootConstantLayout?,
    val vertexOutputs: List<D3D12ShaderIo> = emptyList(),
    val oitCapture: D3D12OitCaptureShader? = null,
    val oitTile: D3D12OitTileCompilation = D3D12OitTileCompilation.NotApplicable,
    val oitTileCaptureIdentity: D3D12OitCaptureIdentity? = null,
    val oitNormalization: D3D12OitNormalizationCertification = D3D12OitNormalizationCertification.NotApplicable,
)
