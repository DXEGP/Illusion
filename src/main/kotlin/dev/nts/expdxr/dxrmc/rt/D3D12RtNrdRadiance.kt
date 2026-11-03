package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.misc.IllusionLogger

internal object D3D12RtNrdRadiance {
    /** 算出来之后当 SRV 喂给 NRD，所以既要能写也要能读。 */
    private const val USAGE = D3D12GpuTexture.USAGE_STORAGE or GpuTexture.USAGE_TEXTURE_BINDING

    private const val THREADS = 8

    const val DENOISING_RANGE = 500000f

    private var radiance: D3D12GpuTexture? = null
    private var output: D3D12GpuTexture? = null
    private var specularRadiance: D3D12GpuTexture? = null
    private var specularOutput: D3D12GpuTexture? = null
    private var specularHitDistance: D3D12GpuTexture? = null
    private var rrOutput: D3D12GpuTexture? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var pipeline = 0L
    private var reported = false

    /** NRD 的辐亮度输入；没写过就是 null。 */
    fun radianceTexture(): D3D12GpuTexture? = radiance

    /** NRD 的辐亮度输出（降噪结果）；没写过就是 null。 */
    fun outputTexture(): D3D12GpuTexture? = output

    fun specularTexture(): D3D12GpuTexture? = specularRadiance

    fun specularOutputTexture(): D3D12GpuTexture? = specularOutput

    fun specularHitDistanceTexture(): D3D12GpuTexture? = specularHitDistance

    fun rrOutputTexture(device: D3D12Device, width: Int, height: Int): D3D12GpuTexture? {
        val current = rrOutput
        if (current != null && current.getWidth(0) == width && current.getHeight(0) == height) return current
        val created = try {
            device.createTexture("rt-rr-output", USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1)
                as? D3D12GpuTexture ?: return null
        } catch (failure: Throwable) {
            fail("rr output allocation failed: ${failure.message}")
            return null
        }
        rrOutput = created
        current?.close()
        return created
    }

    fun render(
        encoder: D3D12CommandEncoderBackend?,
        direct: Long,
        indirect: Long,
        aux: Long,
        specular: Long,
        specularGrazing: Long,
        viewZ: Long,
        albedo: Long,
        emissive: Long,
        specularMaterial: Long,
        normalRoughness: Long,
        encodeReblur: Boolean,
        width: Int,
        height: Int,
        cbv: Long,
        cbvOffset: Long,
    ): Boolean {
        if (encoder == null || direct == 0L || indirect == 0L || aux == 0L || viewZ == 0L || cbv == 0L) {
            return false
        }
        if (width <= 0 || height <= 0) return false
        val started = System.nanoTime()
        val device = encoder.device ?: return false
        val targets = ensureTargets(device, width, height) ?: return false
        val radiance = targets.first
        val denoised = targets.second
        val specularTarget = specularRadiance ?: return false
        val specularDenoised = specularOutput ?: return false
        val hitDistanceTarget = specularHitDistance ?: return false
        val pipeline = ensurePipeline() ?: return false
        encoder.ensureListOpen()
        MCDR_ABI.D3D12Barrier(
            encoder.nativeHandle(),
            listOf(
                MCDR_ABI.BarrierDesc(radiance.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
                MCDR_ABI.BarrierDesc(denoised.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
                MCDR_ABI.BarrierDesc(specularTarget.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
                MCDR_ABI.BarrierDesc(specularDenoised.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
                MCDR_ABI.BarrierDesc(hitDistanceTarget.handle(), 0, 0, MCDR_ABI.BarrierKind.UAV),
            ),
        )
        val radianceSrvs = longArrayOf(direct, indirect, aux, viewZ, albedo, emissive,
            specular, specularGrazing, specularMaterial, normalRoughness)
        val radianceUavs = longArrayOf(radiance.handle(), specularTarget.handle(), hitDistanceTarget.handle())
        val ok = MCDR_ABI.D3D12DispatchComputeEx(
            encoder.nativeHandle(),
            pipeline,
            radianceSrvs,
            radianceUavs,
            intArrayOf(if (encodeReblur) 1 else 0),
            cbv,
            cbvOffset,
            (width + THREADS - 1) / THREADS,
            (height + THREADS - 1) / THREADS,
            1,
        )
        if (!ok) {
            fail("radiance dispatch failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        D3D12RtStats.onCpuEncode(System.nanoTime() - started)
        D3D12RtStats.onDispatches(1)
        return true
    }

    private fun ensureTargets(
        device: D3D12Device,
        width: Int,
        height: Int,
    ): Pair<D3D12GpuTexture, D3D12GpuTexture>? {
        val first = radiance
        val second = output
        val previousSpecular = specularRadiance
        val previousSpecularOutput = specularOutput
        val previousHitDistance = specularHitDistance
        if (first != null && second != null && previousSpecular != null && previousSpecularOutput != null &&
            previousHitDistance != null && targetWidth == width && targetHeight == height) {
            return first to second
        }
        val allocated = mutableListOf<D3D12GpuTexture>()
        fun allocate(name: String, format: GpuFormat = GpuFormat.RGBA16_FLOAT): D3D12GpuTexture {
            val texture = checkNotNull(device.createTexture(name, USAGE, format, width, height, 1, 1)
                as? D3D12GpuTexture) { "$name allocation failed" }
            allocated += texture
            return texture
        }
        val created = try {
            val source = allocate("rt-nrd-radiance")
            val result = allocate("rt-nrd-radiance-out")
            allocate("rt-nrd-specular")
            allocate("rt-nrd-specular-out")
            allocate("rt-specular-hit-distance", GpuFormat.R32_FLOAT)
            source to result
        } catch (t: Throwable) {
            allocated.forEach { it.close() }
            fail("radiance allocate failed: ${t.message}")
            return null
        }
        radiance = created.first
        output = created.second
        specularRadiance = allocated[2]
        specularOutput = allocated[3]
        specularHitDistance = allocated[4]
        first?.close()
        second?.close()
        previousSpecular?.close()
        previousSpecularOutput?.close()
        previousHitDistance?.close()
        targetWidth = width
        targetHeight = height
        IllusionLogger.infoMessage("rt: nrd radiance ${width}x$height")
        return created
    }

    private fun ensurePipeline(): Long? {
        val cached = pipeline
        if (cached != 0L) return cached
        val dxil = MCDR_ABI.D3D12CompileShaderDxc(
            D3D12RtNrdRadianceShader.build(), D3D12RtNrdRadianceShader.ENTRY, "cs_6_8")
        if (dxil == null) {
            fail("radiance compile failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        val handle = MCDR_ABI.D3D12CreateComputePipelineEx(
            dxil, D3D12RtNrdRadianceShader.SRV_COUNT, D3D12RtNrdRadianceShader.UAV_COUNT)
        if (handle == 0L) {
            fail("radiance pipeline failed: ${MCDR_ABI.D3D12LastError()}")
            return null
        }
        pipeline = handle
        return handle
    }

    /** 还回纹理与管线：compute 管线只能走 `ID3D12DestroyResource`（见 §9.8）。 */
    fun release() {
        rrOutput?.close()
        rrOutput = null
        radiance?.close()
        radiance = null
        output?.close()
        output = null
        specularRadiance?.close()
        specularRadiance = null
        specularOutput?.close()
        specularOutput = null
        specularHitDistance?.close()
        specularHitDistance = null
        targetWidth = 0
        targetHeight = 0
        if (pipeline != 0L) {
            MCDR_ABI.ID3D12DestroyResource(pipeline)
            pipeline = 0L
        }
    }

    private fun fail(message: String) {
        if (reported) return
        reported = true
        IllusionLogger.warnMessage("rt nrd radiance: $message")
    }
}
