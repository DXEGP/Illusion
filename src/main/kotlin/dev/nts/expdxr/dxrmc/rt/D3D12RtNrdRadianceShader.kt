package dev.nts.expdxr.dxrmc.rt

/**
 * 辐亮度缓冲 → NRD 输入纹理的入口描述（S4 4-c）。见 docs/rt/README.md §23.5
 */
internal object D3D12RtNrdRadianceShader {
    const val ENTRY = "MegaNrdRadiance"

    /** direct / indirect / aux 三块缓冲。 */
    const val SRV_COUNT = 3

    /** 一张 RGBA16F：RGB 辐亮度、A 归一化命中距离。 */
    const val UAV_COUNT = 1

    private const val SOURCE = "mega_nrd_radiance.hlsl"

    fun build(): String = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
}
