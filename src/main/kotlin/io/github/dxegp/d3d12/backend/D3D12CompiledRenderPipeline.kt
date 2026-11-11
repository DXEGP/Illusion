package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.ffi.MCDR_ABI

/**
 * 编译后的渲染管线：持有 native 的 `Dx12Pipeline*`（D3DCompile 字节码 + root signature + PSO）。
 * 由 D3D12Device.precompilePipeline 创建、clearPipelineCache 统一销毁；bindings 由 [buildBindings] 动态推导。
 */
class D3D12CompiledRenderPipeline(
    private val info: RenderPipeline,
    private var handle: Long,
    private val bindings: List<D3D12BindGroupEntry> = emptyList(),
) : BackendRenderPipeline {

    /** meshlet 版地形的 PSO（`D3D12MeshletShader` 的 MS + 同一条 PS）：每线程组 16 quad。 */
    var meshletPso: Long = 0L
        private set

    /** 挂上 meshlet 变体（重复挂会先释放旧的）。`asActive` = 该 PSO 带 AS 剔除。 */
    fun attachMeshletVariant(pso: Long, asActive: Boolean) {
        if (this.meshletPso != 0L && this.meshletPso != pso) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
        }
        this.meshletPso = pso
        this.meshletAs = asActive
    }

    fun hasMeshletVariant(): Boolean = this.meshletPso != 0L

    /** meshlet 变体是否带 AS（派发组数按 ceil(meshlet/32) 折算）。 */
    var meshletAs: Boolean = false
        private set

    fun meshletPipeline(): Long = this.meshletPso

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
        if (this.meshletPso != 0L) {
            MCDR_ABI.D3D12DestroyPipeline(this.meshletPso)
            this.meshletPso = 0L
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
