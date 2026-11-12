package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.MCDRMeshPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.shader.D3D12CompiledShader
import io.github.dxegp.render.terrain.mesh.D3D12MeshletShader
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import com.mojang.renderpearl.api.GpuFormat

/**
 * 编译后的渲染管线：持有 native 的 `Dx12Pipeline*`（D3DCompile 字节码 + root signature + PSO）。
 * 由 D3D12Device.precompilePipeline 创建、clearPipelineCache 统一销毁；bindings 由 [buildBindings] 动态推导。
 */
class D3D12CompiledRenderPipeline(
    private val info: RenderPipeline,
    private var handle: Long,
    private val bindings: List<D3D12BindGroupEntry> = emptyList(),
    val rootConstants: io.github.dxegp.d3d12.shader.D3D12RootConstantLayout? = null,
) : BackendRenderPipeline {

    /** meshlet 版地形的 PSO（`D3D12MeshletShader` 的 MS + 同一条 PS）：每线程组 16 quad。 */
    var meshletPso: Long = 0L
        private set

    private var meshletCullPso: Long = 0L
    private val meshletSizes = mutableMapOf<Int, Pair<Long, Long>>()
    private val terrainSizes = mutableMapOf<Int, Long>()
    var terrainProgram: Long = 0L
        private set

    fun terrainPipeline(quads: Int): Long = terrainSizes[quads] ?: 0L

    fun compileTerrainWorkGraphVariants(compiled: D3D12CompiledShader, flipY: Boolean) {
        if (terrainProgram != 0L) return
        if (D3D12TerrainWorkGraph.requiresOrderedDraw(info)) return
        val format = info.vertexFormatBindings.firstOrNull() ?: return
        val uniformRegisters = compiled.entries.mapIndexedNotNull { index, binding ->
            if (binding.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER) index else null
        }.toIntArray()
        require(compiled.entries.withIndex().none { (index, binding) ->
            if (binding.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER) index == 8 || index == 9
            else index in 14..16
        }) { "Terrain pipeline bindings overlap reserved b8/b9/t14/t15/t16" }
        val backfaceCull = info.isCull && !flipY && !info.location.toString().contains("animate_sprite")
        val source = D3D12TerrainWorkGraph.source(compiled, backfaceCull) ?: return
        fun compile(hlsl: String, entry: String, profile: String): ByteArray =
            checkNotNull(MCDR_ABI.D3D12CompileShaderDxc(hlsl, entry, profile)) {
                "Terrain $profile/$entry compilation failed: ${MCDR_ABI.D3D12LastError()}"
            }
        val nodes = compile(source, "", "lib_6_9")
        val arguments = D3D12TerrainWorkGraph.resource("terrain_arguments.hlsl")
        val initialize = compile(arguments, "InitializeTerrain", "cs_6_9")
        val finalize = compile(arguments, "FinalizeTerrainArguments", "cs_6_9")
        val pixel = compile(compiled.fragmentHlsl, "main", "ps_6_9")
        val rootBindings = compiled.entries.mapIndexed { index, binding ->
            (when (binding.type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2
            }) to index
        }
        val root = MCDR_ABI.D3D12CreateMeshRootSignature(rootBindings, 2, info.pushConstantSize())
        check(root != 0L) { "Terrain root signature failed: ${MCDR_ABI.D3D12LastError()}" }
        val variants = mutableMapOf<Int, Long>()
        var program = 0L
        try {
            for (quads in listOf(16, 32, 64)) {
                val hlsl = checkNotNull(D3D12MeshletShader.build(compiled, format,
                    quadsPerMeshlet = quads, visibleWork = true))
                val descriptor = MCDRMeshPipelineDesc().shaders(compile(hlsl, "ms_main", "ms_6_9"), null, pixel)
                for (state in info.colorTargetStates) {
                    val target = MCDRPipelineDesc.ColorTarget()
                    if (state == null) target.format(-1)
                    else {
                        target.format(state.format().ordinal).writeMask(state.writeMask())
                        state.blendFunction().ifPresent { blend ->
                            target.blend(blend.color().sourceFactor().ordinal, blend.color().destFactor().ordinal,
                                blend.color().op().ordinal, blend.alpha().sourceFactor().ordinal,
                                blend.alpha().destFactor().ordinal, blend.alpha().op().ordinal)
                        }
                    }
                    descriptor.addColorTarget(target)
                }
                info.depthStencilState?.let { depth ->
                    descriptor.depth(GpuFormat.D32_FLOAT.ordinal, depth.writeDepth(), depth.depthTest().ordinal)
                }
                descriptor.topology(backfaceCull, info.polygonMode.ordinal)
                val pipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(descriptor, root, info.pushConstantSize())
                check(pipeline != 0L) { "Terrain visible MS PSO failed: ${MCDR_ABI.D3D12LastError()}" }
                variants[quads] = pipeline
            }
            program = MCDR_ABI.D3D12CreateTerrainWorkGraph(nodes, initialize, finalize,
                uniformRegisters, 512L * 1024 * 1024)
            check(program != 0L) { "Terrain Work Graph creation failed: ${MCDR_ABI.D3D12LastError()}" }
            terrainSizes.putAll(variants)
            terrainProgram = program
        } catch (failure: Throwable) {
            for (pipeline in variants.values) MCDR_ABI.D3D12DestroyPipeline(pipeline)
            if (program != 0L) MCDR_ABI.D3D12DestroyTerrainWorkGraph(program)
            throw failure
        } finally {
            MCDR_ABI.D3D12ReleaseRootSignature(root)
        }
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

    fun buildBindings(): List<D3D12BindGroupEntry> = bindings

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

    override fun close() {
        for (pipeline in terrainSizes.values) MCDR_ABI.D3D12DestroyPipeline(pipeline)
        terrainSizes.clear()
        if (terrainProgram != 0L) MCDR_ABI.D3D12DestroyTerrainWorkGraph(terrainProgram)
        terrainProgram = 0L
        for (variant in meshletSizes.values) {
            if (variant.first != 0L) MCDR_ABI.D3D12DestroyPipeline(variant.first)
            if (variant.second != 0L) MCDR_ABI.D3D12DestroyPipeline(variant.second)
        }
        meshletSizes.clear()
        if (this.meshletPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
            this.meshletPso = 0L
        }
        if (this.meshletCullPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletCullPso)
            this.meshletCullPso = 0L
        }
        // 先取走再归零：FrontendGpuDevice.close() 与缓存/桥接路径都可能再 close 一次，
        // 不归零就是同一个 Dx12Pipeline* 被销毁两次（原生 double free → 退出时 0xC0000374 堆损坏）。
        val pipeline = this.handle
        this.handle = 0L
        if (pipeline != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(pipeline)
        }
    }
}
