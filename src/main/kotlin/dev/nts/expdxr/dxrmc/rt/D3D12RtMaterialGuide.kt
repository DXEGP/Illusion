package dev.nts.expdxr.dxrmc.rt

import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.misc.IllusionLogger

internal object D3D12RtMaterialGuide {
    private const val SOURCE = "rt_material_guide.hlsl"
    private const val ENTRY = "RtMaterialGuide"
    private const val SRV_COUNT = 2
    private const val UAV_COUNT = 5
    private const val THREADS = 8
    private var pipeline = 0L
    private var reported = false

    fun render(
        encoder: D3D12CommandEncoderBackend,
        aux: D3D12GpuBuffer,
        materials: D3D12GpuBuffer,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        val albedo = D3D12RtGBuffer.texture(D3D12RtGBuffer.ALBEDO) ?: return false
        val depth = D3D12RtGBuffer.texture(D3D12RtGBuffer.LINEAR_DEPTH) ?: return false
        val specular = D3D12RtGBuffer.texture(D3D12RtGBuffer.SPECULAR) ?: return false
        val normalRoughness = D3D12RtGBuffer.texture(D3D12RtGBuffer.NORMAL_ROUGHNESS) ?: return false
        val emissive = D3D12RtGBuffer.texture(D3D12RtGBuffer.EMISSIVE) ?: return false
        if (pipeline == 0L) {
            val source = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
            val bytecode = MCDR_ABI.D3D12CompileShaderDxc(source, ENTRY, "cs_6_8")
                ?: return fail("compilation failed")
            pipeline = MCDR_ABI.D3D12CreateComputePipelineEx(bytecode, SRV_COUNT, UAV_COUNT)
            if (pipeline == 0L) return fail("pipeline failed")
        }
        encoder.ensureListOpen()
        val materialSrvs = longArrayOf(aux.handle(), materials.handle())
        val materialUavs = longArrayOf(specular.handle(), normalRoughness.handle(), emissive.handle(), albedo.handle(), depth.handle())
        val accepted = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(), pipeline, materialSrvs, materialUavs,
            intArrayOf(D3D12RtGBuffer.DEPTH_SCALE.toRawBits()), cbv, cbvOffset,
            (specular.getWidth(0) + THREADS - 1) / THREADS,
            (specular.getHeight(0) + THREADS - 1) / THREADS, 1,
        )
        if (!accepted) return fail("dispatch failed")
        D3D12RtStats.onDispatches(1)
        return true
    }

    private fun fail(reason: String): Boolean {
        if (!reported) IllusionLogger.warnMessage("rt material guide: $reason")
        reported = true
        return false
    }

    fun release() {
        if (pipeline != 0L) MCDR_ABI.ID3D12DestroyResource(pipeline)
        pipeline = 0L
        reported = false
    }
}
