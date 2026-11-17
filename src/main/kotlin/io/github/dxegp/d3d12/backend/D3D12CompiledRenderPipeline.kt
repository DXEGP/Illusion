package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.shader.D3D12OitCaptureProfile
import io.github.dxegp.d3d12.shader.D3D12OitGuideSource
import io.github.dxegp.d3d12.shader.D3D12OitTileTerrainRoute
import io.github.dxegp.d3d12.shader.D3D12OitCaptureIdentity
import io.github.dxegp.d3d12.shader.D3D12OitReplayStage
import io.github.dxegp.d3d12.shader.D3D12OitTileBinningHandles
import io.github.dxegp.d3d12.shader.D3D12OitTileBinning
import io.github.dxegp.d3d12.shader.D3D12OitNormalizationCertification

internal data class D3D12OitCaptureProgram(
    val shaderAbiVersion: Int,
    val shaderContractHash: Long,
    val generation: Long,
    val profile: D3D12OitCaptureProfile,
) {
    init {
        require(shaderAbiVersion > 0 && shaderContractHash != 0L && generation > 0L)
    }
}

internal data class D3D12OitTilePipelineBinding(val route: Long, val tilePipeline: Long) {
    init {
        require(route != 0L && tilePipeline != 0L)
    }
}

internal data class D3D12OitTileRootKey(
    val identity: D3D12OitCaptureIdentity,
    val bindings: List<Pair<Int, Int>>,
    val pushConstantBytes: Int,
    val flipY: Boolean,
)

internal class D3D12OitTileRootLease internal constructor(
    private val key: D3D12OitTileRootKey,
    private var handle: Long,
) : AutoCloseable {
    internal fun handle(): Long = handle

    override fun close() {
        if (handle == 0L) return
        D3D12OitTileRootRegistry.release(key)
        handle = 0L
    }

    companion object {
        fun acquire(key: D3D12OitTileRootKey, create: () -> Long): D3D12OitTileRootLease =
            D3D12OitTileRootRegistry.acquire(key, create)
    }
}

private object D3D12OitTileRootRegistry {
    private data class Entry(val handle: Long, var references: Int)
    private val entries = mutableMapOf<D3D12OitTileRootKey, Entry>()

    @Synchronized
    fun acquire(key: D3D12OitTileRootKey, create: () -> Long): D3D12OitTileRootLease {
        val entry = entries[key] ?: run {
            val handle = create()
            check(handle != 0L) { "Terrain tile root signature failed" }
            Entry(handle, 0).also { entries[key] = it }
        }
        entry.references++
        return D3D12OitTileRootLease(key, entry.handle)
    }

    @Synchronized
    fun release(key: D3D12OitTileRootKey) {
        val entry = entries[key] ?: return
        if (--entry.references == 0) {
            entries.remove(key)
            MCDR_ABI.D3D12ReleaseRootSignature(entry.handle)
        }
    }
}

internal object D3D12OitTileRouteRegistry {
    private val routes = mutableMapOf<D3D12OitTileRootKey, MutableMap<D3D12OitReplayStage, D3D12OitTileTerrainNativeRoute>>()
    private val binning = mutableMapOf<D3D12OitTileRootKey, BinningEntry>()
    private data class BinningEntry(val handles: D3D12OitTileBinningHandles, var references: Int)

    internal class BinningLease internal constructor(
        private val key: D3D12OitTileRootKey,
        private var value: D3D12OitTileBinningHandles?,
    ) : AutoCloseable {
        internal fun handles(): D3D12OitTileBinningHandles = checkNotNull(value)
        override fun close() {
            if (value == null) return
            releaseBinning(key)
            value = null
        }
        companion object {
            internal fun acquire(key: D3D12OitTileRootKey): BinningLease =
                D3D12OitTileRouteRegistry.acquireBinning(key)
        }
    }

    @Synchronized
    internal fun register(key: D3D12OitTileRootKey, stage: D3D12OitReplayStage,
        owner: D3D12OitTileTerrainNativeRoute) {
        routes.getOrPut(key) { mutableMapOf() }[stage] = owner
    }

    @Synchronized
    internal fun unregister(key: D3D12OitTileRootKey, stage: D3D12OitReplayStage,
        owner: D3D12OitTileTerrainNativeRoute) {
        val stages = routes[key] ?: return
        if (stages[stage] === owner) stages.remove(stage)
        if (stages.isEmpty()) routes.remove(key)
    }

    @Synchronized
    internal fun lookup(key: D3D12OitTileRootKey): Map<D3D12OitReplayStage, D3D12OitTileTerrainNativeRoute> =
        routes[key]?.toMap() ?: emptyMap()

    internal fun lookup(identity: D3D12OitCaptureIdentity, bindings: List<Pair<Int, Int>>,
        pushConstantBytes: Int, flipY: Boolean = false): Map<D3D12OitReplayStage, D3D12OitTileTerrainNativeRoute> =
        lookup(D3D12OitTileRootKey(identity, bindings, pushConstantBytes, flipY))

    @Synchronized
    internal fun lookup(identity: D3D12OitCaptureIdentity): List<Map<D3D12OitReplayStage, D3D12OitTileTerrainNativeRoute>> =
        routes.filterKeys { it.identity == identity }.values.map { it.toMap() }

    @Synchronized
    private fun acquireBinning(key: D3D12OitTileRootKey): BinningLease {
        val entry = binning[key] ?: BinningEntry(D3D12OitTileBinning.compile(), 0).also {
            binning[key] = it
        }
        entry.references++
        return BinningLease(key, entry.handles)
    }

    @Synchronized
    private fun releaseBinning(key: D3D12OitTileRootKey) {
        val entry = binning[key] ?: return
        if (--entry.references == 0) {
            binning.remove(key)
            entry.handles.close()
        }
    }

    @Synchronized
    internal fun lookupBinning(key: D3D12OitTileRootKey): D3D12OitTileBinningHandles? =
        binning[key]?.handles

    @Synchronized
    internal fun lookupBinning(identity: D3D12OitCaptureIdentity, bindings: List<Pair<Int, Int>>,
        pushConstantBytes: Int, flipY: Boolean = false): D3D12OitTileBinningHandles? =
        lookupBinning(D3D12OitTileRootKey(identity, bindings, pushConstantBytes, flipY))
}

internal class D3D12OitTileTerrainNativeRoute(
    val descriptor: D3D12OitTileTerrainRoute,
    private val rootLease: D3D12OitTileRootLease,
    private val key: D3D12OitTileRootKey,
    private val stage: D3D12OitReplayStage,
    private val variants: Map<Int, D3D12OitTilePipelineBinding>,
    private val binningLease: D3D12OitTileRouteRegistry.BinningLease,
) : AutoCloseable {
    private var closed = false

    init {
        require(rootSignatureHandle() != 0L && variants.keys == setOf(16, 32, 64))
        D3D12OitTileRouteRegistry.register(key, stage, this)
    }

    internal fun rootSignatureHandle(): Long = rootLease.handle()

    internal fun routeKey(): D3D12OitTileRootKey = key

    internal fun replayStage(): D3D12OitReplayStage = stage

    internal fun binningHandles(): D3D12OitTileBinningHandles = binningLease.handles()

    internal fun pipelineHandle(quadsPerMeshlet: Int): Long = variants.getValue(quadsPerMeshlet).tilePipeline

    override fun close() {
        if (closed) return
        closed = true
        D3D12OitTileRouteRegistry.unregister(key, stage, this)
        for (binding in variants.values) MCDR_ABI.D3D12DestroyPipeline(binding.tilePipeline)
        for (binding in variants.values) MCDR_ABI.D3D12DestroyTerrainTileRouteV1(binding.route)
        binningLease.close()
        rootLease.close()
    }
}

/**
 * 编译后的渲染管线：持有 native PSO、业务变体与最终绑定表。
 * 缓存和 frontend 各自持有租约，最后一个租约释放时关闭产物。
 */
class D3D12CompiledRenderPipeline(
    private val info: RenderPipeline,
    private var handle: Long,
    bindings: List<D3D12BindGroupEntry> = emptyList(),
    val rootConstants: io.github.dxegp.d3d12.shader.D3D12RootConstantLayout? = null,
    val cameraReprojectable: Boolean = false,
    oitNormalization: D3D12OitNormalizationCertification = D3D12OitNormalizationCertification.NotApplicable,
) : BackendRenderPipeline {
    internal var oitNormalization: D3D12OitNormalizationCertification = oitNormalization
        private set
    internal val bindingLayout = D3D12BindingLayout(bindings)
    internal val uniformBindings = bindingLayout.entries.filter { it.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER }
    private val motionBindingLayouts = HashMap<String, D3D12BindingLayout>()
    private val additionalBindingLayouts = HashMap<String, D3D12BindingLayout>()

    @Synchronized
    internal fun motionBindingLayout(parameterName: String, historyName: String,
        additionalParameterName: String? = null): D3D12BindingLayout {
        val key = "$parameterName\u0000$historyName\u0000${additionalParameterName ?: ""}"
        return motionBindingLayouts.getOrPut(key) {
            val additional = additionalParameterName?.let {
                D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, it, null)
            }
            val motionSlot = bindingLayout.entries.size + if (additional == null) 0 else 1
            D3D12BindingLayout(bindingLayout.entries + listOfNotNull(additional) + listOf(
                D3D12BindGroupEntry(D3D12BindGroupEntry.Type.UNIFORM_BUFFER, parameterName, null),
                D3D12BindGroupEntry(D3D12BindGroupEntry.Type.TEXEL_BUFFER, historyName,
                    com.mojang.renderpearl.api.GpuFormat.RGBA32_FLOAT)),
                intArrayOf(motionSlot, motionSlot + 1))
        }
    }

    @Synchronized
    internal fun additionalBindingLayout(parameterName: String): D3D12BindingLayout =
        additionalBindingLayouts.getOrPut(parameterName) {
            D3D12BindingLayout(bindingLayout.entries + D3D12BindGroupEntry(
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER, parameterName, null))
        }

    internal var oitGuidePso: Long = 0L
        private set
    internal var oitGuideSource: io.github.dxegp.d3d12.shader.D3D12OitGuideSource? = null
        private set
    internal var oitLineQuadPso: Long = 0L
        private set
    internal var oitCapturePso: Long = 0L
        private set
    internal var oitCaptureLineQuadPso: Long = 0L
        private set
    internal var oitCaptureProgram: D3D12OitCaptureProgram? = null
        private set
    internal var oitUnrecordedPso: Long = 0L
        private set
    internal var oitUnrecordedLineQuadPso: Long = 0L
        private set
    internal var oitGuideRasterMotionPso: Long = 0L
        private set
    internal var oitGuideRasterMotionLineQuadPso: Long = 0L
        private set
    internal var oitUnrecordedRasterMotionPso: Long = 0L
        private set
    internal var oitUnrecordedRasterMotionLineQuadPso: Long = 0L
        private set

    internal var oitTileTerrainRoute: D3D12OitTileTerrainRoute? = null
        private set
    internal var oitTileTerrainNativeRoute: D3D12OitTileTerrainNativeRoute? = null
        private set

    internal fun attachOitTileTerrainRoute(route: D3D12OitTileTerrainRoute,
        nativeRoute: D3D12OitTileTerrainNativeRoute) {
        check(oitTileTerrainRoute == null)
        check(oitTileTerrainNativeRoute == null)
        oitTileTerrainRoute = route
        oitTileTerrainNativeRoute = nativeRoute
    }

    internal fun terrainTileRootSignature(): Long? = oitTileTerrainNativeRoute?.rootSignatureHandle()

    internal fun terrainTileRouteKey(): D3D12OitTileRootKey? = oitTileTerrainNativeRoute?.routeKey()

    internal fun terrainTileStage(stage: D3D12OitReplayStage, quadsPerMeshlet: Int): Long =
        oitTileTerrainNativeRoute?.let { D3D12OitTileRouteRegistry.lookup(it.routeKey())[stage] }
            ?.pipelineHandle(quadsPerMeshlet) ?: 0L

    internal fun terrainTileStagePipeline(stage: D3D12OitReplayStage, quadsPerMeshlet: Int): Long =
        terrainTileStage(stage, quadsPerMeshlet)

    internal fun terrainTileBinning(): D3D12OitTileBinningHandles? =
        oitTileTerrainNativeRoute?.binningHandles()

    internal fun terrainTilePipeline(quadsPerMeshlet: Int): Long =
        oitTileTerrainNativeRoute?.pipelineHandle(quadsPerMeshlet) ?: 0L

    internal fun attachOitUnrecordedVariant(pso: Long, lineQuadPso: Long = 0L) {
        check(pso != 0L && oitUnrecordedPso == 0L && oitUnrecordedLineQuadPso == 0L)
        oitUnrecordedPso = pso
        oitUnrecordedLineQuadPso = lineQuadPso
    }

    internal fun attachOitCaptureVariant(program: D3D12OitCaptureProgram, pso: Long, lineQuadPso: Long = 0L) {
        check(pso != 0L && oitCapturePso == 0L && oitCaptureLineQuadPso == 0L && oitCaptureProgram == null)
        oitCaptureProgram = program
        oitCapturePso = pso
        oitCaptureLineQuadPso = lineQuadPso
    }

    internal fun attachOitGuideVariant(pso: Long, source: io.github.dxegp.d3d12.shader.D3D12OitGuideSource,
        lineQuadPso: Long = 0L) {
        check(oitGuidePso == 0L)
        oitGuidePso = pso
        oitGuideSource = source
        oitLineQuadPso = lineQuadPso
    }

    internal fun attachOitRasterMotionVariant(pso: Long,
        source: io.github.dxegp.d3d12.shader.D3D12OitGuideSource, lineQuadPso: Long = 0L) {
        check(pso != 0L)
        if (source == io.github.dxegp.d3d12.shader.D3D12OitGuideSource.UNRECORDED) {
            check(oitUnrecordedRasterMotionPso == 0L && oitUnrecordedRasterMotionLineQuadPso == 0L)
            oitUnrecordedRasterMotionPso = pso
            oitUnrecordedRasterMotionLineQuadPso = lineQuadPso
        } else {
            check(oitGuideRasterMotionPso == 0L && oitGuideRasterMotionLineQuadPso == 0L)
            oitGuideRasterMotionPso = pso
            oitGuideRasterMotionLineQuadPso = lineQuadPso
        }
    }
    internal var rasterMotionPso: Long = 0L
        private set
    internal var rasterMotionLineQuadPso: Long = 0L
        private set
    internal var rasterMotionExact: Boolean = false
        private set

    internal fun attachRasterMotionVariant(pso: Long, exact: Boolean, lineQuadPso: Long = 0L) {
        check(pso != 0L && rasterMotionPso == 0L && rasterMotionLineQuadPso == 0L)
        rasterMotionPso = pso
        rasterMotionLineQuadPso = lineQuadPso
        rasterMotionExact = exact
    }

    /** meshlet 版地形的 PSO（`D3D12MeshletShader` 的 MS + 同一条 PS）：每线程组 16 quad。 */
    var meshletPso: Long = 0L
        private set

    private var meshletCullPso: Long = 0L
    private val meshletSizes = mutableMapOf<Int, Pair<Long, Long>>()
    private var terrainSizes: MutableMap<Int, Long> = mutableMapOf()
    private var terrainMotionSizes: MutableMap<Int, Long> = mutableMapOf()
    private var terrainOitSizes: Map<D3D12OitGuideSource, Map<Int, Long>> = emptyMap()
    private var terrainOitMotionSizes: Map<D3D12OitGuideSource, Map<Int, Long>> = emptyMap()
    private var terrainCaptureSizes: MutableMap<Int, Long> = mutableMapOf()
    var terrainProgram: Long = 0L
        private set

    fun terrainPipeline(quads: Int): Long = terrainSizes[quads] ?: 0L
    internal fun terrainOitPipeline(quads: Int, source: D3D12OitGuideSource): Long =
        terrainOitSizes[source]?.get(quads) ?: 0L
    internal fun terrainOitRasterMotionPipeline(quads: Int,
        source: D3D12OitGuideSource): Long = terrainOitMotionSizes[source]?.get(quads) ?: 0L
    internal fun hasTerrainOitVariant(source: D3D12OitGuideSource): Boolean =
        terrainOitSizes[source]?.isNotEmpty() == true
    internal fun terrainCapturePipeline(quads: Int): Long = terrainCaptureSizes[quads] ?: 0L

    internal val hasTerrainRasterMotionVariant: Boolean get() = terrainMotionSizes.isNotEmpty()

    internal fun terrainRasterMotionPipeline(quads: Int): Long = terrainMotionSizes[quads] ?: 0L

    internal fun attachTerrainVariants(program: Long, pipelines: Map<Int, Long>, motionPipelines: Map<Int, Long>,
        oitPipelines: Map<D3D12OitGuideSource, Map<Int, Long>> = emptyMap(),
        oitMotionPipelines: Map<D3D12OitGuideSource, Map<Int, Long>> = emptyMap(),
        capturePipelines: Map<Int, Long> = emptyMap()) {
        check(terrainProgram == 0L && terrainSizes.isEmpty() && terrainMotionSizes.isEmpty() &&
            terrainOitSizes.isEmpty() && terrainOitMotionSizes.isEmpty() && terrainCaptureSizes.isEmpty())
        check(motionPipelines.isEmpty() || motionPipelines.keys == pipelines.keys)
        check(oitPipelines.values.all { it.keys == pipelines.keys })
        check(oitMotionPipelines.keys.all { it in oitPipelines } &&
            oitMotionPipelines.values.all { it.keys == pipelines.keys })
        check(capturePipelines.isEmpty() || capturePipelines.keys == pipelines.keys)
        check(capturePipelines.isEmpty() || oitCaptureProgram != null)
        terrainSizes = pipelines.toMutableMap()
        terrainMotionSizes = motionPipelines.toMutableMap()
        terrainOitSizes = oitPipelines.mapValues { it.value.toMap() }
        terrainOitMotionSizes = oitMotionPipelines.mapValues { it.value.toMap() }
        terrainCaptureSizes = capturePipelines.toMutableMap()
        terrainProgram = program
    }

    fun attachMeshletSize(quads: Int, pso: Long, cullPso: Long) {
        if (quads == 16) attachMeshletVariants(pso, cullPso)
        else {
            meshletSizes.put(quads, pso to cullPso)?.let { previous ->
                if (previous.first != 0L) MCDR_ABI.D3D12DestroyPipeline(previous.first)
                if (previous.second != 0L) MCDR_ABI.D3D12DestroyPipeline(previous.second)
            }
        }
    }

    fun attachMeshletVariants(pso: Long, cullPso: Long) {
        if (this.meshletPso != 0L && this.meshletPso != pso) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
        }
        if (this.meshletCullPso != 0L && this.meshletCullPso != cullPso) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletCullPso)
        }
        this.meshletPso = pso
        this.meshletCullPso = cullPso
    }

    fun hasMeshletVariant(cull: Boolean, quads: Int = 16): Boolean = meshletPipeline(cull, quads) != 0L

    fun meshletPipeline(cull: Boolean, quads: Int = 16): Long =
        if (quads == 16) { if (cull) this.meshletCullPso else this.meshletPso }
        else meshletSizes[quads]?.let { if (cull) it.second else it.first } ?: 0L

    /**
     * 线管线的 **TRIANGLES 拓扑变体** PSO：同一套着色器/顶点/索引，只换 `PrimitiveTopologyType`。
     * D3D12 没有宽线（`LINELIST` 恒 1px），线宽只靠 vsh 把端点横移，必须摊成两个三角形才是一条带子。
     */
    private var lineQuadPso: Long = 0L

    /** 挂上三角形变体（重复挂会先释放旧的）。 */
    fun attachLineQuadVariant(pso: Long) {
        if (this.lineQuadPso != 0L && this.lineQuadPso != pso) {
            MCDR_ABI.D3D12DestroyPipeline(this.lineQuadPso)
        }
        this.lineQuadPso = pso
    }

    fun hasLineQuadVariant(): Boolean = this.lineQuadPso != 0L

    fun lineQuadPipeline(): Long = this.lineQuadPso


    fun info(): RenderPipeline {
        return this.info
    }

    fun handle(): Long {
        return this.handle
    }

    fun buildBindings(): List<D3D12BindGroupEntry> = bindingLayout.entries

    /** 26.3 的 `CompiledRenderPipeline` 只剩 [isClosed]/[close]；这是本实现自己的有效性判据。 */
    fun isValid(): Boolean {
        return this.handle != 0L
    }

    /**
     * uniform 名列表必须原样存：前端回调 `setUniform(index, …)` 的下标是 CreateInfo.uniforms 的位置
     * （按 SPIR-V 描述符出现顺序编号），**不是** flattenUniforms 的顺序，不能自己从 layouts 推。
     */
    @Volatile
    private var uniformNameList: List<String> = emptyList()

    fun setUniformNames(names: List<String>) {
        this.uniformNameList = names
    }

    fun uniformNamesSnapshot(): List<String> {
        return this.uniformNameList
    }

    override fun isClosed(): Boolean {
        return this.handle == 0L
    }

    @Synchronized
    override fun close() {
        oitNormalization = D3D12OitNormalizationCertification.Unsupported(
            io.github.dxegp.d3d12.shader.D3D12OitNormalizationUnsupported.CLOSED_PIPELINE)
        if (oitGuidePso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitGuidePso)
        oitGuidePso = 0L
        oitGuideSource = null
        if (oitLineQuadPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitLineQuadPso)
        oitLineQuadPso = 0L
        if (oitCapturePso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitCapturePso)
        oitCapturePso = 0L
        if (oitCaptureLineQuadPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitCaptureLineQuadPso)
        oitCaptureLineQuadPso = 0L
        oitCaptureProgram = null
        if (oitUnrecordedPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitUnrecordedPso)
        oitUnrecordedPso = 0L
        if (oitUnrecordedLineQuadPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitUnrecordedLineQuadPso)
        oitUnrecordedLineQuadPso = 0L
        if (oitGuideRasterMotionPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitGuideRasterMotionPso)
        oitGuideRasterMotionPso = 0L
        if (oitGuideRasterMotionLineQuadPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitGuideRasterMotionLineQuadPso)
        oitGuideRasterMotionLineQuadPso = 0L
        if (oitUnrecordedRasterMotionPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitUnrecordedRasterMotionPso)
        oitUnrecordedRasterMotionPso = 0L
        if (oitUnrecordedRasterMotionLineQuadPso != 0L) MCDR_ABI.D3D12DestroyPipeline(oitUnrecordedRasterMotionLineQuadPso)
        oitUnrecordedRasterMotionLineQuadPso = 0L
        if (rasterMotionPso != 0L) MCDR_ABI.D3D12DestroyPipeline(rasterMotionPso)
        rasterMotionPso = 0L
        if (rasterMotionLineQuadPso != 0L) MCDR_ABI.D3D12DestroyPipeline(rasterMotionLineQuadPso)
        rasterMotionLineQuadPso = 0L
        for (pipeline in terrainSizes.values + terrainMotionSizes.values + terrainOitSizes.values.flatMap { it.values } +
            terrainOitMotionSizes.values.flatMap { it.values } + terrainCaptureSizes.values)
            MCDR_ABI.D3D12DestroyPipeline(pipeline)
        terrainSizes.clear()
        terrainMotionSizes.clear()
        terrainOitSizes = emptyMap()
        terrainOitMotionSizes = emptyMap()
        terrainCaptureSizes.clear()
        oitTileTerrainRoute = null
        oitTileTerrainNativeRoute?.close()
        oitTileTerrainNativeRoute = null
        if (terrainProgram != 0L) MCDR_ABI.D3D12DestroyTerrainWorkGraph(terrainProgram)
        terrainProgram = 0L
        val meshlets = meshletSizes.entries.iterator()
        while (meshlets.hasNext()) {
            val entry = meshlets.next()
            val variant = entry.value
            if (variant.first != 0L) {
                MCDR_ABI.D3D12DestroyPipeline(variant.first)
                entry.setValue(0L to variant.second)
            }
            if (variant.second != 0L) MCDR_ABI.D3D12DestroyPipeline(variant.second)
            meshlets.remove()
        }
        if (this.meshletPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
            this.meshletPso = 0L
        }
        if (this.meshletCullPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletCullPso)
            this.meshletCullPso = 0L
        }
        if (lineQuadPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(lineQuadPso)
            lineQuadPso = 0L
        }
        val pipeline = this.handle
        if (pipeline != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(pipeline)
            this.handle = 0L
        }
    }
}
