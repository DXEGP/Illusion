package io.github.dxegp.d3d12.command

import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.backend.D3D12BindingPacket
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline


import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea
import com.mojang.renderpearl.backend.api.RenderPassBackend
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import io.github.dxegp.render.temporal.D3D12Temporal
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuQueryPool
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.d3d12.shader.D3D12GpuSampler
import io.github.dxegp.render.vrs.D3D12VariableShadingRate
import io.github.dxegp.diagnostics.IllusionLogger
import org.lwjgl.PointerBuffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.function.Supplier


/** mesh 跳过理由的去重必须**跨帧**（每个 pass 每帧都会新建 backend）；只服务日志。 */
/** 其余均为日志去重/限流状态，同样必须全局（实例字段每帧都会被重置）。 */
private val gDrawIndirectLocations: MutableSet<String> = HashSet()

/**
 * D3D12 render pass 后端（镜像官方 VulkanRenderPass）：uniform/texture 暂存在本类 map，core 之前按需
 * 推入原生瞬时描述符堆（[pushDescriptors]）；顶点/索引缓冲与 draw 直接下发 native。
 */
class D3D12RenderPassBackend(
    private val device: D3D12Device?,
    private val ctx: Long,
    private val renderArea: RenderArea?,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val hasDepth: Boolean,
    private val colorTargetHandle: Long,
    /** 是否使用 shader Y-flip 变体管线（GUI 离屏 pass，由 CommandEncoder 按 texture usage 判断）。 */
    private val flipY: Boolean,
    /** 本 encoder 的瞬态上传区：meshlet 派发要上传段表/meshlet 表/小常量，只有 `uploadStaging` 能给 COPY_SRC 与 256 对齐偏移。 */
    private val transientMemory: D3D12TransientMemory,
    private val meshletResidency: io.github.dxegp.render.terrain.mesh.D3D12MeshletResidency,
) : RenderPassBackend {
    private var pushedDebugGroups = 0

    /** mesh 派发是否启用（唯一入口是 `[mesh] terrain` 配置项）。 */
    private val meshDispatchEnabled: Boolean
        get() = D3D12Settings.meshTerrain

    /** 最近一次 slot0 顶点切片的字节偏移（MDI 每段的 `baseVertex` 相对它）。 */
    private var lastVertexSliceOffset = -1L

    /** 最近一次 slot0 顶点切片的缓冲（meshlet 派发要把它当 t14 的 RAW SRV 绑定）。 */
    private var lastVertexBuffer: GpuBuffer? = null

    /** 早期显示会**先绑顶点缓冲、后设管线**：stride 只能从管线推导，故先挂起等 [setPipeline] 落实；抛异常会让加载屏整条 draw 被吞掉。 */
    private val pendingVertexBuffers = LinkedHashMap<Int, Pair<D3D12GpuBuffer, Long>>()

    private var pipeline: D3D12CompiledRenderPipeline? = null
    private var anyDescriptorDirty = false
    private var constantSnapshot: ByteBuffer? = null
    private val bindingPackets = IdentityHashMap<D3D12CompiledRenderPipeline, D3D12BindingPacket>()

    /** 当前管线的 uniform 名字表（顺序 = CreateInfo.uniforms，非 layouts 展平顺序）。 */
    private var uniformNames: List<String> = emptyList()
    private var currentIndexType: IndexType? = null
    private val uniforms: MutableMap<String, GpuBufferSlice> = HashMap()

    private val textures: MutableMap<String, TextureViewAndSampler> = HashMap()

    private data class TextureViewAndSampler(val view: D3D12GpuTextureView, val sampler: D3D12GpuSampler)

    /** 当前管线的 location 字符串（如 minecraft:pipeline/animate_sprite_blit）。 */
    fun pipelineLocation(): String? {
        val p = this.pipeline ?: return null
        return p.info().location.toString()
    }

    fun colorTargetHandle(): Long {
        return this.colorTargetHandle
    }

    /** 本 pass 是否是 GUI 离屏图集 pass（usage==13 且带深度，几何整体 Y 翻转）。 */
    fun isFlipY(): Boolean {
        return this.flipY
    }

    fun outputWidth(): Int {
        return this.outputWidth
    }

    fun outputHeight(): Int {
        return this.outputHeight
    }

    fun areaX(): Int {
        val area = this.renderArea
        return area?.x() ?: 0
    }

    fun areaY(): Int {
        val area = this.renderArea
        return area?.y() ?: 0
    }

    fun areaWidth(): Int {
        val area = this.renderArea
        return area?.width() ?: this.outputWidth
    }

    fun areaHeight(): Int {
        val area = this.renderArea
        return area?.height() ?: this.outputHeight
    }

    override fun pushDebugGroup(label: Supplier<String>) {
        this.pushedDebugGroups++
    }

    override fun popDebugGroup() {
        this.pushedDebugGroups--
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        MCDR_ABI.D3D12WriteTimestamp(this.ctx,
            (pool as D3D12GpuQueryPool).nativeHandle(), index)
    }
    // ---- pipeline + descriptors ----
    override fun setPipeline(pipeline: BackendRenderPipeline) {
        val device = this.device
            ?: throw IllegalStateException("No D3D12 device bound to this render pass")
        val base = pipeline as? D3D12CompiledRenderPipeline
            ?: throw IllegalStateException(
                "Pipeline ${pipeline.javaClass.name} is not a D3D12 compiled pipeline")
        // 前端缓存里可能是上一次资源重载**前**编译的对象（vanilla 重载会把它们 close、handle 归 0 ⇒
        // isValid()==false）→ 会抛 "not valid" 且没有 COMPILE FAILED 日志。故 flipY 变体或失效对象都重新取。
        val compiled = if (this.flipY || !base.isValid()) {
            device.getOrCompilePipeline(base.info(), this.flipY)
        } else {
            base
        }
        if (!compiled.isValid()) {
            throw IllegalStateException(
                "Pipeline ${base.info().location} is not valid (shader compilation failed)")
        }
        if (this.pipeline?.rootConstants != compiled.rootConstants ||
            this.pipeline?.info()?.pushConstantSize() != compiled.info().pushConstantSize()) {
            constantSnapshot = null
        }
        this.pipeline = compiled

        // vanilla 按「管线 uniform 列表下标」回调 setUniform(Int)，下标来自 `CreateInfo.uniforms`（≠
        // flattenUniforms(bindGroupLayouts) 的顺序）。flipY 变体是另一个实例，把 base 的名单复制过去即可。
        if (compiled !== base) {
            compiled.setUniformNames(base.uniformNamesSnapshot())
        }
        this.uniformNames = compiled.uniformNamesSnapshot()
        if (this.uniformNames.isEmpty()) {
            // 兜底：管线只经自检路径编译（无 CreateInfo）时名单为空，用 layouts 展平顺序顶上。
            this.uniformNames = BindGroupLayout.flattenUniforms(compiled.info().bindGroupLayouts)
                .map { it.name() }
        }
        this.anyDescriptorDirty = true
        // useDepth 必须看**管线**有没有 DepthStencilState，不能只看 pass 有没有深度附件：GUI 管线没有
        // depthStencilState，套上 withDepth PSO 会让 GREATER_EQUAL 丢掉所有 fragment → 黑屏。
        val pipelineHasDepth = compiled.info().depthStencilState != null
        val useDepth = this.hasDepth && pipelineHasDepth
        val ok = MCDR_ABI.D3D12SetPipeline(this.ctx, compiled.handle(), useDepth)
        if (!ok) {
            throw IllegalStateException("dx12SetPipeline failed for ${base.info().location}")
        }
        // 落实 setPipeline 之前挂起的顶点缓冲（早期显示是"先绑 buffer、后设管线"）。
        if (this.pendingVertexBuffers.isNotEmpty()) {
            val formatBindings = compiled.info().vertexFormatBindings
            val pending = this.pendingVertexBuffers.entries.map { it.key to it.value }
            this.pendingVertexBuffers.clear()
            for ((slot, value) in pending) {
                val stride = formatBindings.getOrNull(slot)?.vertexSize ?: 0
                if (stride == 0) {
                    LOGGER.warn(
                        "vertex buffer slot {} has no format in pipeline {} (dropped)",
                        slot, base.info().location)
                    continue
                }
                this.applyVertexBuffer(slot, value.first, value.second, stride)
            }
        }
    }

    /** 把回调进来的 uniform 下标记翻回名字（本类内部按名字绑定描述符，与 native `pushDescriptors` 契约一致）。 */
    override fun setUniform(index: Int, value: Any?) {
        val name = this.uniformNames.getOrNull(index)
        if (name == null) {
            LOGGER.warn("setUniform({}) ignored: pipeline has {} uniforms",
                index, this.uniformNames.size)
            return
        }
        when (value) {
            null -> {
                this.textures.remove(name)
                this.uniforms.remove(name)
                for (packet in bindingPackets.values) {
                    packet.setUniform(name, null)
                    packet.setTexture(name, null)
                }
                this.anyDescriptorDirty = true
            }
            is com.mojang.renderpearl.util.TextureViewAndSampler ->
                this.bindTexture(name, value.view(), value.sampler())
            is GpuBufferSlice -> this.setUniformByName(name, value)
            is GpuBuffer -> this.setUniformByName(name, value.slice())
            else -> throw IllegalArgumentException(
                "Unsupported uniform value for '$name': ${value.javaClass.name}")
        }
    }

    override fun pushConstants(data: ByteBuffer) {
        val selected = checkNotNull(pipeline) { "Push constants require a pipeline" }
        val required = selected.info().pushConstantSize()
        require(data.remaining() == required) { "Push constant size must be $required bytes" }
        if (required == 0) return
        val snapshot = constantSnapshot?.takeIf { it.capacity() == required } ?: ByteBuffer.allocate(required)
        snapshot.clear()
        snapshot.put(data.duplicate())
        snapshot.flip()
        constantSnapshot = snapshot
    }

    private fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) {
        if (textureView == null || sampler == null) {
            if (textureView != null || sampler != null) {
                throw IllegalArgumentException("Both texture and sampler must be null or non-null")
            }
            this.textures.remove(name)
            for (packet in bindingPackets.values) packet.setTexture(name, null)
            this.anyDescriptorDirty = true
            return
        }
        this.textures[name] = TextureViewAndSampler(
            textureView as D3D12GpuTextureView, sampler as D3D12GpuSampler
        )
        for (packet in bindingPackets.values) packet.setTexture(name, textureView)
        this.anyDescriptorDirty = true
    }

    private fun setUniformByName(name: String, value: GpuBufferSlice) {
        // 捕获时间性渲染要的逐物体 UBO（"DynamicTransforms" / "ChunkSection"）；vanilla 的 DynamicTransforms
        // 环由 CPU 直接写（usage 130），map(read) 安全。只在有深度附件的 pass 记录：GUI/图集 blit 也 set 这两个名字，混进来会让配对序号失真。
        if (this.hasDepth) {
            D3D12Temporal.captureUniform(name, value)
        }
        this.uniforms[name] = value
        for (packet in bindingPackets.values) packet.setUniform(name, value)
        this.anyDescriptorDirty = true
    }
    /**
     * 转发到 native 的 RSSetViewports。与 [enableScissor] 不同，这里**不**做 Y 翻转：直接透传调用方给的 y。
     */
    override fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        if (!MCDR_ABI.D3D12SetViewport(this.ctx, x, y, width, height)) {
            LOGGER.error("dx12SetViewport failed ({} {} {} {})", x, y, width, height)
        }
    }

    /** 镜像官方 pushDescriptors：把当前 uniform/texture 写入原生瞬时描述符堆并绑定 root table。 */
    private fun pushDescriptors() {
        if (this.pipeline?.isValid() == true && MCDR_ABI.D3D12EnsureGraphicsState(ctx)) {
            anyDescriptorDirty = true
        }
        val selected = this.pipeline
        if (selected != null && selected.info().pushConstantSize() != 0) {
            val constants = checkNotNull(constantSnapshot) { "Push constants were not supplied for the selected layout" }
            check(MCDR_ABI.D3D12PushGraphicsConstants(ctx, constants)) {
                "Graphics constants failed: ${MCDR_ABI.D3D12LastError()}"
            }
        }
        if (!this.anyDescriptorDirty) {
            return
        }
        val pipeline = this.pipeline
        if (pipeline == null || !pipeline.isValid()) {
            LOGGER.warn("pushDescriptors: SKIP (pipeline null or invalid)")
            return
        }
        val packet = bindingPackets.getOrPut(pipeline) {
            D3D12BindingPacket(pipeline.buildBindings()).also { created ->
                for ((name, value) in uniforms) created.setUniform(name, value)
                for ((name, value) in textures) created.setTexture(name, value.view)
            }
        }
        val bindings = packet.entries
        val count = bindings.size
        if (count == 0) {
            this.anyDescriptorDirty = false
            return
        }
        val types = packet.types
        val buffers = packet.buffers
        val offsets = packet.offsets
        val lengths = packet.lengths
        val texelFormats = packet.texelFormats
        val views = packet.views
        for (i in 0 until count) {
            val entry = bindings[i]
            when (entry.type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> {
                    val value = requireUniform(packet, i)
                    val buffer = value.buffer()
                    types[i] = 0
                    buffers[i] = (buffer as D3D12GpuBuffer).handle()
                    offsets[i] = value.offset()
                    lengths[i] = value.length()
                }
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> {
                    val texture = packet.textureValues[i]
                        ?: throw IllegalStateException("Texture '${entry.name}' was not bound before core")
                    if (texture.isClosed) {
                        throw IllegalStateException("Texture '${entry.name}' is closed")
                    }
                    types[i] = 1
                    views[i] = texture.handle()
                }
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> {
                    val value = requireUniform(packet, i)
                    val buffer = value.buffer()
                    types[i] = 2
                    buffers[i] = (buffer as D3D12GpuBuffer).handle()
                    offsets[i] = value.offset()
                    lengths[i] = value.length()
                    texelFormats[i] = if (entry.rawBuffer) -1 else requireNotNull(entry.texelBufferFormat).ordinal
                }
            }
        }
        if (!MCDR_ABI.D3D12PushDescriptors(this.ctx, types, buffers, offsets, lengths, texelFormats, views)) {
            // 带上 native 的具体原因：最常见的是 CBV 地址没按 256 字节对齐，只报 "failed" 会丢掉那条 offset 信息。
            throw IllegalStateException("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}")
        }
        this.anyDescriptorDirty = false
    }

    private fun requireUniform(packet: D3D12BindingPacket, slot: Int): GpuBufferSlice {
        val name = packet.entries[slot].name
        val value = packet.uniformValues[slot] ?: throw IllegalStateException("Uniform '${name}' was not set before core")
        val buffer = value.buffer()
        if (buffer.isClosed) throw IllegalStateException("Uniform '${name}' buffer is closed")
        return value
    }
    // ---- scissor ----

    override fun enableScissor(x: Int, y: Int, width: Int, height: Int) {
        // GL 约定（左下原点）× RSSetScissorRects（左上原点），是否互为镜像取决于本 pass 有没有做几何 Y 翻转：
        //  * flipY=false（主窗口）：几何不翻 → 必须换算 outputHeight - y - height，否则列表项被上移裁剪。
        //  * flipY=true（GUI 图集 / PIP）：顶点着色器已取反 gl_Position.y → 原样直通，再换算会让物品整块空白。
        val scissorY = if (this.flipY) y else this.outputHeight - y - height
        if (!MCDR_ABI.D3D12SetScissor(this.ctx, x, scissorY, width, height)) {
            LOGGER.error("dx12SetScissor failed ({} {} {} {})", x, scissorY, width, height)
        }
    }

    override fun disableScissor() {
        val area = this.renderArea
        if (area != null) this.enableScissor(area.x(), area.y(), area.width(), area.height())
        else this.enableScissor(0, 0, this.outputWidth, this.outputHeight)
    }

    // ---- vertex / index buffers + draws ----

    override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) {
        if (vertexBuffer == null) return

        val buffer = vertexBuffer.buffer()
        if (buffer.isClosed) throw IllegalStateException("Vertex buffer at slot ${slot} has been closed!")

        if ((buffer.usage() and USAGE_VERTEX) == 0) throw IllegalStateException("Vertex buffer at slot ${slot} doesn't have GpuBuffer.USAGE_VERTEX flag!")

        val pipeline = this.pipeline
        var stride = 0
        if (pipeline != null) {
            val info = pipeline.info()
            val bindings = info.vertexFormatBindings
            if (slot < bindings.size) {
                val binding = bindings[slot]
                if (binding != null) {
                    stride = binding.vertexSize
                }
            }
        }
        if (stride == 0) {
            // 管线还没设（早期显示先绑 buffer 后设管线）或该槽位没声明顶点布局 → 挂起；抛异常会让加载屏整条 draw 失败。
            this.pendingVertexBuffers[slot] = (buffer as D3D12GpuBuffer) to vertexBuffer.offset()
            return
        }
        this.applyVertexBuffer(slot, buffer as D3D12GpuBuffer, vertexBuffer.offset(), stride)
    }

    /** 按已知 stride 真正下发顶点缓冲绑定，并维护 slot0 的 meshlet 记账。 */
    private fun applyVertexBuffer(slot: Int, buffer: D3D12GpuBuffer, offset: Long, stride: Int) {
        if (!MCDR_ABI.D3D12SetVertexBuffer(this.ctx, slot, buffer.handle(), offset, stride)) {
            throw IllegalStateException("dx12SetVertexBuffer failed")
        }
        if (slot == 0) {
            // MDI 批次里每个段的 `baseVertex` 都相对这块切片，Stage 2 建段表要用。
            this.lastVertexSliceOffset = offset
            this.lastVertexBuffer = buffer
        }
    }

    override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) {
        if (indexBuffer.isClosed) throw IllegalStateException("Index buffer has been closed!")

        if ((indexBuffer.usage() and USAGE_INDEX) == 0) throw IllegalStateException("Index buffer doesn't have GpuBuffer.USAGE_INDEX flag!")

        if (!MCDR_ABI.D3D12SetIndexBuffer(this.ctx, (indexBuffer as D3D12GpuBuffer).handle(), if (indexType == IndexType.INT) 1 else 0)) throw IllegalStateException("dx12SetIndexBuffer failed")
        this.currentIndexType = indexType
    }

    // ---- 绘制热路径插桩：PROF=1 时由 D3D12CommandEncoderBackend 每 300 帧汇总打印并清零 ----
    companion object {
        /** 准星补画期间的诊断开关：打印这一笔绑了什么管线。 */
        @JvmStatic
        var replayProbe: Boolean = false
        @JvmStatic
        var replayBinds: Int = 0
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        private const val USAGE_VERTEX = 0x20
        private const val USAGE_INDEX = 0x40

        /** DEBUG: true 时强制所有 pass 使用 withoutDepth PSO（禁用深度测试/写入），用于排查黑屏是否由深度问题导致。 */
        private const val DEBUG_DISABLE_DEPTH_TEST = false


        @JvmField var gProfDrawIndexedCalls = 0L  // drawIndexed 调用次数
        @JvmField var gProfDrawIndexedNs = 0L     // drawIndexed（pushDescriptors + downcall）耗时
        @JvmField var gProfMultiBatches = 0L
        @JvmField var gProfMultiDraws = 0L
        @JvmField var gProfMultiPathNs = 0L

        private fun recordMultiProfile(drawCount: Int, started: Long) {
            if (started != 0L) {
                gProfMultiBatches++
                gProfMultiDraws += drawCount
                gProfMultiPathNs += System.nanoTime() - started
            }
        }
    }

    override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int,
        vertexOffset: Int, firstInstance: Int) {
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        // 线管线有三角形变体 ⇒ 切过去：同一份索引在 TRIANGLES 下摊成两个三角形，拼出 `LineWidth` 带宽；LINELIST 恒 1px。
        val lineQuad = this.pipeline?.takeIf { it.hasLineQuadVariant() }
        val lineQuadDepth = lineQuad?.let { it.info().depthStencilState != null } ?: false
        if (lineQuad != null) {
            if (!MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.lineQuadPipeline(), lineQuadDepth)) {
                throw IllegalStateException("dx12SetPipeline (line quad variant) failed")
            }
            this.anyDescriptorDirty = true
        }
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DrawIndexed(this.ctx, indexCount, instanceCount,
            firstIndex, vertexOffset, firstInstance)) {
            throw IllegalStateException("dx12DrawIndexed failed")
        }
        if (lineQuad != null) {
            // 恢复本 pass 的常规管线（描述符表随 PSO 变化，需要重推）。
            if (!MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.handle(), lineQuadDepth)) {
                throw IllegalStateException("dx12SetPipeline (restore after line quad) failed")
            }
            this.anyDescriptorDirty = true
            this.pushDescriptors()
        }
        if (profT0 != 0L) {
            gProfDrawIndexedCalls++
            gProfDrawIndexedNs += System.nanoTime() - profT0
        }
    }

    /** DispatchMesh 入口：先推描述符再下发；没有 IA 状态、没有顶点/索引缓冲。 */
    fun dispatchMesh(x: Int, y: Int, z: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DispatchMesh(this.ctx, x, y, z)) throw IllegalStateException("dx12DispatchMesh(${x}, ${y}, ${z}) failed")
    }

    override fun multiDrawIndexed(drawParameters: IntBuffer, instanceCount: Int,
        firstInstance: Int, drawCount: Int) {
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            recordMultiProfile(0, profT0)
            return
        }
        val parameters = IntArray(Math.multiplyExact(drawCount, 3))
        drawParameters.get(parameters)
        val lineQuad = this.pipeline?.takeIf { it.hasLineQuadVariant() }
        val lineQuadDepth = lineQuad?.let { it.info().depthStencilState != null } ?: false
        if (lineQuad != null && !MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.lineQuadPipeline(), lineQuadDepth)) {
            throw IllegalStateException("dx12SetPipeline (line quad variant) failed")
        }
        if (lineQuad != null) this.anyDescriptorDirty = true
        try {
            this.pushDescriptors()
            if (!MCDR_ABI.D3D12MultiDrawIndexedPacked(this.ctx, parameters, instanceCount,
                firstInstance, drawCount)) {
                throw IllegalStateException("dx12MultiDrawIndexedPacked failed: ${MCDR_ABI.D3D12LastError()}")
            }
        } finally {
            if (lineQuad != null) {
                if (!MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.handle(), lineQuadDepth)) {
                    throw IllegalStateException("dx12SetPipeline (restore after line quad) failed")
                }
                this.anyDescriptorDirty = true
                this.pushDescriptors()
            }
        }
        recordMultiProfile(drawCount, profT0)
    }

    override fun multiDrawIndexed(firstIndexOffsets: PointerBuffer, indexCounts: IntBuffer,
        vertexOffsets: IntBuffer, drawCount: Int) {
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            recordMultiProfile(0, profT0)
            return
        }
        val indexType = this.currentIndexType
            ?: throw IllegalStateException("multiDrawDirectSeparate requires a bound index buffer")
        val elementBytes = if (indexType == IndexType.INT) 4L else 2L
        val firstIndices = IntArray(drawCount)
        val counts = IntArray(drawCount)
        val bases = IntArray(drawCount)
        for (i in 0 until drawCount) {
            val byteOffset = firstIndexOffsets.get()
            require(byteOffset >= 0L && byteOffset % elementBytes == 0L) {
                "index offset $byteOffset is not aligned to $elementBytes bytes"
            }
            firstIndices[i] = Math.toIntExact(byteOffset / elementBytes)
            counts[i] = indexCounts.get()
            bases[i] = vertexOffsets.get()
        }
        val lineQuad = this.pipeline?.takeIf { it.hasLineQuadVariant() }
        val lineQuadDepth = lineQuad?.let { it.info().depthStencilState != null } ?: false
        if (lineQuad != null && !MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.lineQuadPipeline(), lineQuadDepth)) {
            throw IllegalStateException("dx12SetPipeline (line quad variant) failed")
        }
        if (lineQuad != null) this.anyDescriptorDirty = true
        try {
            this.pushDescriptors()
            if (!MCDR_ABI.D3D12MultiDrawIndexedSeparate(this.ctx, firstIndices, counts, bases, 1, 0,
                drawCount)) {
                throw IllegalStateException("dx12MultiDrawIndexedSeparate failed: ${MCDR_ABI.D3D12LastError()}")
            }
        } finally {
            if (lineQuad != null) {
                if (!MCDR_ABI.D3D12SetPipeline(this.ctx, lineQuad.handle(), lineQuadDepth)) {
                    throw IllegalStateException("dx12SetPipeline (restore after line quad) failed")
                }
                this.anyDescriptorDirty = true
                this.pushDescriptors()
            }
        }
        recordMultiProfile(drawCount, profT0)
    }

    override fun drawIndexedIndirect(commands: GpuBufferSlice, drawCount: Int) {
        require(drawCount >= 0) { "Draw count must be non-negative" }
        if (drawCount == 0) return
        val location = this.pipeline?.info()?.location?.toString() ?: ""
        if (this.pipeline?.info()?.let(D3D12TerrainWorkGraph::requiresOrderedDraw) == true) {
            this.pushDescriptors()
            check(MCDR_ABI.D3D12DrawIndexedIndirect(this.ctx,
                (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
                "Ordered transparent draw failed: ${MCDR_ABI.D3D12LastError()}"
            }
            return
        }
        if (gDrawIndirectLocations.add(location)) {
            // 作用域白名单按管线名判：名字错了整条通路就是静默空转，所以每个新名字打一行方便核对。
            LOGGER.info("MDI terrain candidate '{}' terrain={} rate={}", location,
                isTerrain(location), D3D12VariableShadingRate.terrainRateFor(location))
        }
        // 地形**全部**（不透明 + OIT 半透明）只走 meshlet，无 IMR 回退；OIT 顺序无关，mesh 的线程组顺序才无所谓。
        if (isTerrain(location)) {
            // 着色率唯一作用域：整批地形包在一次 scope 内，白名单在 `terrainRateFor`；档位 OFF 时零 native 调用。
            D3D12VariableShadingRate.scope(this.ctx, location) {
                tryMeshletDraw(commands, drawCount)
            }
            return
        }
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DrawIndexedIndirect(this.ctx,
            (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
            throw IllegalStateException("dx12DrawIndexedIndirect failed")
        }
    }

    /** 地形管线（含 OIT 三段）；shadow 不属于本路径。 */
    private fun isTerrain(location: String): Boolean =
        location.contains("terrain") && !location.contains("shadow")

    private fun tryMeshletDraw(commands: GpuBufferSlice, drawCount: Int) {
        check(meshDispatchEnabled) { "Terrain Work Graph requires mesh dispatch" }
        val compiled = checkNotNull(this.pipeline) { "No terrain pipeline bound" }
        val vertexBuffer = checkNotNull(this.lastVertexBuffer as? D3D12GpuBuffer) { "No terrain vertex buffer" }
        val prepared = checkNotNull(D3D12TerrainWorkGraph.prepare(compiled, commands, drawCount,
            vertexBuffer, this.lastVertexSliceOffset, this.ctx, this.transientMemory,
            this.meshletResidency, this.uniforms, D3D12Settings.meshTerrainCull,
            D3D12VariableShadingRate.msBand())) { "Terrain Work Graph inputs or pipeline are unavailable" }
        val useDepth = this.hasDepth && compiled.info().depthStencilState != null
        prepared.use {
            try {
                check(MCDR_ABI.D3D12SetPipeline(this.ctx, it.pipeline, useDepth)) {
                    "Terrain pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
                }
                this.anyDescriptorDirty = true
                this.pushDescriptors()
                check(it.draw(this.ctx, vertexBuffer, this.lastVertexSliceOffset)) {
                    "Terrain indirect draw failed: ${MCDR_ABI.D3D12LastError()}"
                }
            } finally {
                this.anyDescriptorDirty = true
                check(MCDR_ABI.D3D12SetPipeline(this.ctx, compiled.handle(), useDepth)) {
                    "Terrain pipeline restore failed: ${MCDR_ABI.D3D12LastError()}"
                }
            }
        }
    }


    override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12Draw(this.ctx, vertexCount, instanceCount, firstVertex, firstInstance)) {
            throw IllegalStateException("dx12Draw failed")
        }
    }

    override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int,
        drawCount: Int) {
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            recordMultiProfile(0, profT0)
            return
        }
        val parameters = IntArray(Math.multiplyExact(drawCount, 2))
        drawParameters.get(parameters)
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12MultiDrawPacked(this.ctx, parameters, instanceCount, firstInstance, drawCount)) {
            throw IllegalStateException("dx12MultiDrawPacked failed: ${MCDR_ABI.D3D12LastError()}")
        }
        recordMultiProfile(drawCount, profT0)
    }

    override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) {
        val profT0 = if (MCDR_ABI.PROF) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            recordMultiProfile(0, profT0)
            return
        }
        val first = IntArray(drawCount)
        val counts = IntArray(drawCount)
        firstVertices.get(first)
        vertexCounts.get(counts)
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12MultiDrawSeparate(this.ctx, first, counts, drawCount)) {
            throw IllegalStateException("dx12MultiDrawSeparate failed: ${MCDR_ABI.D3D12LastError()}")
        }
        recordMultiProfile(drawCount, profT0)
    }

    override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DrawIndirect(this.ctx,
            (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
            throw IllegalStateException("dx12DrawIndirect failed")
        }
    }
}

