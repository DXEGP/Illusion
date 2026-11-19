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
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuQueryPool
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.shader.D3D12GpuSampler
import io.github.dxegp.d3d12.shader.D3D12PipelineOrientation
import io.github.dxegp.diagnostics.D3D12RenderProf
import io.github.dxegp.diagnostics.RenderProfiling
import org.lwjgl.PointerBuffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.function.Supplier



/**
 * D3D12 render pass 后端（镜像官方 VulkanRenderPass）：uniform/texture 暂存在本类 map，core 之前按需
 * 推入原生瞬时描述符堆（[pushDescriptors]）；顶点/索引缓冲与 draw 直接下发 native。
 */
class D3D12RenderPassBackend internal constructor(
    private val device: D3D12Device?,
    private val ctx: Long,
    private val renderArea: RenderArea?,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val hasDepth: Boolean,
    private val colorTargetHandle: Long,
    /** 是否使用 shader Y-flip 变体管线（GUI 离屏 pass，由 CommandEncoder 按 texture usage 判断）。 */
    private val flipY: Boolean,
    private val policy: D3D12RasterPassPolicy,
    private val rasterMotionTarget: Long = 0L,
) : RenderPassBackend {
    private var pushedDebugGroups = 0


    /** 最近一次 slot0 顶点切片的字节偏移（MDI 每段的 `baseVertex` 相对它）。 */
    private var lastVertexSliceOffset = -1L

    /** 最近一次 slot0 顶点切片的缓冲（meshlet 派发要把它当 t14 的 RAW SRV 绑定）。 */
    private var lastVertexBuffer: GpuBuffer? = null
    private var lastVertexStride = 0

    /** 早期显示会**先绑顶点缓冲、后设管线**：stride 只能从管线推导，故先挂起等 [setPipeline] 落实；抛异常会让加载屏整条 draw 被吞掉。 */
    private val pendingVertexBuffers = LinkedHashMap<Int, GpuBufferSlice>()

    private var pipeline: D3D12CompiledRenderPipeline? = null
    private var anyDescriptorDirty = false
    private var constantSnapshot: ByteBuffer? = null
    private val bindingPackets = IdentityHashMap<D3D12CompiledRenderPipeline, D3D12BindingPacket>()
    private val motionBindingPackets =
        IdentityHashMap<D3D12CompiledRenderPipeline, MutableMap<String?, D3D12BindingPacket>>()
    private var motionBindings: D3D12MotionBindings? = null
    private var additionalBindings: D3D12AdditionalRasterBindings? = null
    private enum class RasterTopology { PIPELINE, LINE_QUAD }
    private var rasterTopology = RasterTopology.PIPELINE
    private val additionalBindingPackets = IdentityHashMap<D3D12CompiledRenderPipeline, D3D12BindingPacket>()
    private var route = D3D12RasterRoute.INDIRECT

    init {
        policy.setViewport(areaX(), areaY(), areaWidth(), areaHeight())
    }

    private var multiDrawPackedScratch = IntArray(0)
    private var multiDrawFirstScratch = IntArray(0)
    private var multiDrawCountScratch = IntArray(0)
    private var multiDrawBaseScratch = IntArray(0)
    private var motionOverrideBuffers = LongArray(0)
    private var motionOverrideOffsets = LongArray(0)
    private var motionOverrideLengths = LongArray(0)

    /** 当前管线的 uniform 名字表（顺序 = CreateInfo.uniforms，非 layouts 展平顺序）。 */
    private var uniformNames: List<String> = emptyList()
    private var currentIndexType: IndexType? = null
    private var currentIndexBuffer: GpuBuffer? = null
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
            device.pipelines.getOrCompilePipeline(base.info(),
                if (this.flipY) D3D12PipelineOrientation.FLIPPED_Y else D3D12PipelineOrientation.ORIGINAL)
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
        motionBindings = null

        // vanilla 按「管线 uniform 列表下标」回调 setUniform(Int)，下标来自 `CreateInfo.uniforms`（≠
        // flattenUniforms(bindGroupLayouts) 的顺序）。flipY 变体是另一个实例，把 base 的名单复制过去即可。
        if (compiled !== base) {
            compiled.setUniformNames(base.uniformNamesSnapshot())
        }
        this.uniformNames = compiled.uniformNamesSnapshot()
        if (this.uniformNames.isEmpty()) {
            // 兜底：没有 CreateInfo 的旁路编译对象名单为空，用 layouts 展平顺序顶上。
            this.uniformNames = BindGroupLayout.flattenUniforms(compiled.info().bindGroupLayouts)
                .map { it.name() }
        }
        this.anyDescriptorDirty = true
        // useDepth 必须看**管线**有没有 DepthStencilState，不能只看 pass 有没有深度附件：GUI 管线没有
        // depthStencilState，套上 withDepth PSO 会让 GREATER_EQUAL 丢掉所有 fragment → 黑屏。
        val pipelineHasDepth = compiled.info().depthStencilState != null
        val useDepth = this.hasDepth && pipelineHasDepth
        route = policy.selectPipeline(compiled)
        additionalBindings = if (!flipY) policy.recordAdditionalBindings(compiled) else null
        MCDR_ABI.D3D12SetAdditionalColorTarget(ctx, additionalBindings?.target ?: 0L)
        val ok = MCDR_ABI.D3D12SetPipeline(this.ctx,
            additionalBindings?.pso ?: compiled.handle(), useDepth)
        if (!ok) {
            throw IllegalStateException("dx12SetPipeline failed for ${base.info().location}")
        }
        policy.setVertexInput(lastVertexBuffer, lastVertexSliceOffset)
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
                this.applyVertexBuffer(slot, value, stride)
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
                updateBindingPackets { packet ->
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
        val snapshot = constantSnapshot?.takeIf { it.capacity() >= required } ?: ByteBuffer.allocate(required)
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
            updateBindingPackets { it.setTexture(name, null) }
            this.anyDescriptorDirty = true
            return
        }
        this.textures[name] = TextureViewAndSampler(
            textureView as D3D12GpuTextureView, sampler as D3D12GpuSampler
        )
        updateBindingPackets { it.setTexture(name, textureView) }
        this.anyDescriptorDirty = true
    }

    private fun setUniformByName(name: String, value: GpuBufferSlice) {
        if (this.hasDepth) {
            policy.captureUniform(name, value)
        }
        this.uniforms[name] = value
        updateBindingPackets { it.setUniform(name, value) }
        this.anyDescriptorDirty = true
    }

    private inline fun updateBindingPackets(update: (D3D12BindingPacket) -> Unit) {
        for (packet in bindingPackets.values) update(packet)
        for (packets in motionBindingPackets.values) {
            for (packet in packets.values) update(packet)
        }
        for (packet in additionalBindingPackets.values) update(packet)
    }

    private fun packedMultiDrawParameters(size: Int): IntArray {
        if (multiDrawPackedScratch.size < size) multiDrawPackedScratch = IntArray(size)
        return multiDrawPackedScratch
    }

    private fun ensureMultiDrawColumns(size: Int, indexed: Boolean) {
        if (multiDrawFirstScratch.size < size) {
            multiDrawFirstScratch = IntArray(size)
            multiDrawCountScratch = IntArray(size)
        }
        if (indexed && multiDrawBaseScratch.size < size) multiDrawBaseScratch = IntArray(size)
    }
    /**
     * 转发到 native 的 RSSetViewports。与 [enableScissor] 不同，这里**不**做 Y 翻转：直接透传调用方给的 y。
     */
    override fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        if (!MCDR_ABI.D3D12SetViewport(this.ctx, x, y, width, height)) {
            LOGGER.error("dx12SetViewport failed ({} {} {} {})", x, y, width, height)
        }
        policy.setViewport(x, y, width, height)
    }

    private fun rasterMotionPipeline(): Long = when (rasterTopology) {
        RasterTopology.PIPELINE -> additionalBindings?.rasterMotionPso ?: pipeline?.rasterMotionPso ?: 0L
        RasterTopology.LINE_QUAD -> additionalBindings?.rasterMotionLineQuadPso ?: pipeline?.rasterMotionLineQuadPso ?: 0L
    }

    private fun activeRasterPipeline(): Long = when (rasterTopology) {
        RasterTopology.PIPELINE -> additionalBindings?.pso ?: checkNotNull(pipeline).handle()
        RasterTopology.LINE_QUAD -> additionalBindings?.lineQuadPso ?: checkNotNull(pipeline).lineQuadPipeline()
    }

    private fun canRecordRasterMotion(): Boolean = rasterMotionTarget != 0L && rasterMotionPipeline() != 0L &&
        !flipY && hasDepth

    private inline fun <T> withRasterMotion(prepareBindings: () -> D3D12MotionBindings, action: () -> T): T {
        if (!canRecordRasterMotion()) return action()
        motionBindings = prepareBindings()
        var failure: Throwable? = null
        try {
            val motionPipeline = rasterMotionPipeline()
            check(motionPipeline != 0L && MCDR_ABI.D3D12SetPipeline(ctx, motionPipeline, true)) {
                "Raster motion pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
            }
            anyDescriptorDirty = true
            return action()
        } catch (drawFailure: Throwable) {
            failure = drawFailure
            throw drawFailure
        } finally {
            motionBindings = null
            anyDescriptorDirty = true
            try {
                check(MCDR_ABI.D3D12SetPipeline(ctx, activeRasterPipeline(), true)) {
                    "Raster motion pipeline restore failed: ${MCDR_ABI.D3D12LastError()}"
                }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    /** 镜像官方 pushDescriptors：把当前 uniform/texture 写入原生瞬时描述符堆并绑定 root table。 */
    private fun pushDescriptors() {
        val packet = prepareDescriptors() ?: return
        if (!MCDR_ABI.D3D12PushDescriptors(this.ctx, packet.types, packet.buffers, packet.offsets,
                packet.lengths, packet.texelFormats, packet.views)) {
            throw IllegalStateException("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}")
        }
        this.anyDescriptorDirty = false
    }

    private fun prepareDescriptors(): D3D12BindingPacket? {
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
            return null
        }
        val pipeline = this.pipeline
        if (pipeline == null || !pipeline.isValid()) {
            LOGGER.warn("pushDescriptors: SKIP (pipeline null or invalid)")
            return null
        }
        val motion = motionBindings
        val additional = additionalBindings
        val additionalParameterName = additional
            ?.takeIf { it.parameters != null }
            ?.parameterName
        val packet = if (motion != null) {
            val motionPackets = motionBindingPackets.getOrPut(pipeline) {
                HashMap<String?, D3D12BindingPacket>()
            }
            motionPackets.getOrPut(additionalParameterName) {
                D3D12BindingPacket(pipeline.motionBindingLayout(
                    D3D12MotionBindings.PARAMETER_NAME, D3D12MotionBindings.HISTORY_NAME,
                    additionalParameterName)).also { created ->
                    for ((name, value) in uniforms) created.setUniform(name, value)
                    for ((name, value) in textures) created.setTexture(name, value.view)
                }
            }
        } else {
            val packets = if (additionalParameterName != null)
                additionalBindingPackets else bindingPackets
            packets.getOrPut(pipeline) {
                val layout = if (additionalParameterName != null)
                    pipeline.additionalBindingLayout(additionalParameterName) else pipeline.bindingLayout
                D3D12BindingPacket(layout).also { created ->
                    for ((name, value) in uniforms) created.setUniform(name, value)
                    for ((name, value) in textures) created.setTexture(name, value.view)
                }
            }
        }
        if (motion != null) {
            packet.setUniform(D3D12MotionBindings.PARAMETER_NAME, motion.parameters)
            packet.setUniform(D3D12MotionBindings.HISTORY_NAME, motion.history)
        }
        if (additional?.parameters != null) packet.setUniform(additional.parameterName, additional.parameters)
        val bindings = packet.entries
        val count = bindings.size
        if (count == 0) {
            this.anyDescriptorDirty = false
            return null
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
        return packet
    }

    private fun recordMotionBatch(indexed: Boolean, parameters: IntArray, instanceCount: Int,
        firstInstance: Int, drawCount: Int) {
        val overrideCount = Math.multiplyExact(drawCount, 2)
        if (motionOverrideBuffers.size < overrideCount) {
            motionOverrideBuffers = LongArray(overrideCount)
            motionOverrideOffsets = LongArray(overrideCount)
            motionOverrideLengths = LongArray(overrideCount)
        }
        var firstBindings: D3D12MotionBindings? = null
        for (draw in 0 until drawCount) {
            val base = parameters[draw * 3 + if (indexed) 2 else 0]
            val bindings = policy.prepareMotionBindings(base, instanceCount)
            if (draw == 0) firstBindings = bindings
            val parameterSlot = draw * 2
            motionOverrideBuffers[parameterSlot] = (bindings.parameters.buffer() as D3D12GpuBuffer).handle()
            motionOverrideOffsets[parameterSlot] = bindings.parameters.offset()
            motionOverrideLengths[parameterSlot] = bindings.parameters.length()
            motionOverrideBuffers[parameterSlot + 1] = (bindings.history.buffer() as D3D12GpuBuffer).handle()
            motionOverrideOffsets[parameterSlot + 1] = bindings.history.offset()
            motionOverrideLengths[parameterSlot + 1] = bindings.history.length()
        }
        motionBindings = firstBindings
        var failure: Throwable? = null
        try {
            val motionPipeline = rasterMotionPipeline()
            check(motionPipeline != 0L && MCDR_ABI.D3D12SetPipeline(ctx, motionPipeline, true)) {
                "Raster motion batch pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
            }
            anyDescriptorDirty = true
            val packet = checkNotNull(prepareDescriptors())
            val overrides = checkNotNull(packet.overrideSlots)
            check(MCDR_ABI.D3D12MultiDrawBound(ctx, indexed, parameters, instanceCount, firstInstance,
                drawCount, packet.types, packet.buffers, packet.offsets, packet.lengths,
                packet.texelFormats, packet.views, overrides, motionOverrideBuffers,
                motionOverrideOffsets, motionOverrideLengths)) {
                "Raster motion batch failed: ${MCDR_ABI.D3D12LastError()}"
            }
        } catch (drawFailure: Throwable) {
            failure = drawFailure
            throw drawFailure
        } finally {
            motionBindings = null
            anyDescriptorDirty = true
            try {
                check(MCDR_ABI.D3D12SetPipeline(ctx, activeRasterPipeline(), true)) {
                    "Raster motion batch pipeline restore failed: ${MCDR_ABI.D3D12LastError()}"
                }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
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
        if (vertexBuffer == null) {
            if (slot == 0) {
                lastVertexBuffer = null
                lastVertexSliceOffset = -1L
                lastVertexStride = 0
                policy.setVertexInput(null, -1L)
            }
            pendingVertexBuffers.remove(slot)
            return
        }

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
            this.pendingVertexBuffers[slot] = vertexBuffer
            if (slot == 0) policy.setVertexInput(buffer, vertexBuffer.offset())
            return
        }
        this.applyVertexBuffer(slot, vertexBuffer, stride)
    }

    /** 按已知 stride 真正下发顶点缓冲绑定，并维护 slot0 的 meshlet 记账。 */
    private fun applyVertexBuffer(slot: Int, slice: GpuBufferSlice, stride: Int) {
        val buffer = slice.buffer() as D3D12GpuBuffer
        val offset = slice.offset()
        if (!MCDR_ABI.D3D12SetVertexBufferRangeV1(this.ctx, slot, buffer.handle(), offset, slice.length(), stride)) {
            throw IllegalStateException("dx12SetVertexBufferRangeV1 failed")
        }
        if (slot == 0) {
            // MDI 批次里每个段的 `baseVertex` 都相对这块切片，Stage 2 建段表要用。
            this.lastVertexSliceOffset = offset
            policy.setVertexInput(buffer, offset)
            this.lastVertexBuffer = buffer
            this.lastVertexStride = stride
        }
    }

    override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) {
        if (indexBuffer.isClosed) throw IllegalStateException("Index buffer has been closed!")

        if ((indexBuffer.usage() and USAGE_INDEX) == 0) throw IllegalStateException("Index buffer doesn't have GpuBuffer.USAGE_INDEX flag!")

        if (!MCDR_ABI.D3D12SetIndexBuffer(this.ctx, (indexBuffer as D3D12GpuBuffer).handle(), if (indexType == IndexType.INT) 1 else 0)) throw IllegalStateException("dx12SetIndexBuffer failed")
        this.currentIndexType = indexType
        this.currentIndexBuffer = indexBuffer
    }

    // ---- 绘制热路径插桩：PROF=1 时由 D3D12CommandEncoderBackend 每 300 帧汇总打印并清零 ----
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        private const val USAGE_VERTEX = 0x20
        private const val USAGE_INDEX = 0x40

    }

    override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int,
        vertexOffset: Int, firstInstance: Int) {
        val profT0 = if (RenderProfiling.enabled) System.nanoTime() else 0L
        if (indexCount > 0 && instanceCount > 0) {
            policy.retainIndexedGeometryReads(lastVertexBuffer, currentIndexBuffer)
            val capture = (if (!flipY) policy.drawCapture else null)?.let { drawCapture ->
                val vertexBuffer = lastVertexBuffer
                val indexBuffer = currentIndexBuffer
                val indexType = currentIndexType
                val material = textures["Sampler0"]
                val source = if (vertexBuffer != null && indexBuffer != null && indexType != null &&
                    lastVertexSliceOffset >= 0L && lastVertexStride > 0) {
                    D3D12IndexedRasterSource(vertexBuffer, lastVertexSliceOffset, lastVertexStride,
                        indexBuffer, indexType, material?.view, material?.sampler,
                        Collections.unmodifiableMap(HashMap(uniforms)),
                        Collections.unmodifiableMap(textures.mapValues { (_, sampled) ->
                            D3D12RasterSampledView(sampled.view, sampled.sampler)
                        }))
                } else null
                drawCapture.prepareIndexed(checkNotNull(pipeline),
                    D3D12IndexedRasterDraw(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance, source))
            }
            if (capture != null) {
                recordCapturedDraw(capture) {
                    check(MCDR_ABI.D3D12DrawIndexed(ctx, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)) {
                        "Captured indexed draw failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                }
                D3D12RenderProf.recordIndexedDraw(profT0)
                return
            }
        }
        withLineQuad {
            withRasterMotion({ policy.prepareMotionBindings(vertexOffset, instanceCount) }) {
                this.pushDescriptors()
                if (!MCDR_ABI.D3D12DrawIndexed(this.ctx, indexCount, instanceCount,
                    firstIndex, vertexOffset, firstInstance)) {
                    throw IllegalStateException("dx12DrawIndexed failed")
                }
                if (indexCount > 0 && instanceCount > 0) recordRasterDraw(additionalBindings?.target ?: 0L)
            }
        }
        D3D12RenderProf.recordIndexedDraw(profT0)
    }

    private inline fun <Result> withLineQuad(action: () -> Result): Result {
        val selected = pipeline?.takeIf { it.hasLineQuadVariant() } ?: return action()
        val useDepth = hasDepth && selected.info().depthStencilState != null
        val previousTopology = rasterTopology
        rasterTopology = RasterTopology.LINE_QUAD
        var failure: Throwable? = null
        try {
            check(MCDR_ABI.D3D12SetPipeline(ctx, activeRasterPipeline(), useDepth)) {
                "dx12SetPipeline (line quad variant) failed: ${MCDR_ABI.D3D12LastError()}"
            }
            anyDescriptorDirty = true
            return action()
        } catch (drawFailure: Throwable) {
            failure = drawFailure
            throw drawFailure
        } finally {
            rasterTopology = previousTopology
            try {
                check(MCDR_ABI.D3D12SetPipeline(ctx, activeRasterPipeline(), useDepth)) {
                    "dx12SetPipeline (restore after line quad) failed: ${MCDR_ABI.D3D12LastError()}"
                }
                anyDescriptorDirty = true
                pushDescriptors()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    private fun <Result> recordCapturedDraw(capture: D3D12PreparedRasterCapture, action: () -> Result): Result {
        val selected = checkNotNull(pipeline)
        val previousAdditional = additionalBindings
        val useDepth = hasDepth && selected.info().depthStencilState != null
        check(motionBindings == null) { "OIT capture cannot replace an active motion draw" }
        var failure: Throwable? = null
        try {
            MCDR_ABI.D3D12SetAdditionalColorTarget(ctx, 0L)
            additionalBindings = null
            check(MCDR_ABI.D3D12SetPipeline(ctx, capture.pipeline, useDepth)) {
                "OIT capture pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
            }
            anyDescriptorDirty = true
            return capture.recordDraw {
                pushDescriptors()
                action()
            }
        } catch (drawFailure: Throwable) {
            failure = drawFailure
            throw drawFailure
        } finally {
            additionalBindings = previousAdditional
            anyDescriptorDirty = true
            try {
                MCDR_ABI.D3D12SetAdditionalColorTarget(ctx, previousAdditional?.target ?: 0L)
                check(MCDR_ABI.D3D12SetPipeline(ctx,
                    previousAdditional?.pso ?: selected.handle(), useDepth)) {
                    "OIT capture pipeline restore failed: ${MCDR_ABI.D3D12LastError()}"
                }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    private fun recordRasterDraw(additionalTarget: Long) {
        policy.recordColorWrite(colorTargetHandle)
        policy.recordDraw(additionalTarget)
        policy.drawCapture?.recordUncapturedDraw(checkNotNull(pipeline),
            additionalBindings?.attachment?.takeIf { it.target == additionalTarget })
    }

    /** DispatchMesh 入口：先推描述符再下发；没有 IA 状态、没有顶点/索引缓冲。 */
    fun dispatchMesh(x: Int, y: Int, z: Int) {
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12DispatchMesh(this.ctx, x, y, z)) throw IllegalStateException("dx12DispatchMesh(${x}, ${y}, ${z}) failed")
        if (x > 0 && y > 0 && z > 0) recordRasterDraw(0L)
    }

    override fun multiDrawIndexed(drawParameters: IntBuffer, instanceCount: Int,
        firstInstance: Int, drawCount: Int) {
        val profT0 = if (RenderProfiling.enabled) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            D3D12RenderProf.recordMultiDraw(0, profT0)
            return
        }
        val parameterCount = Math.multiplyExact(drawCount, 3)
        val parameters = packedMultiDrawParameters(parameterCount)
        drawParameters.get(parameters, 0, parameterCount)
        if (instanceCount > 0) policy.retainIndexedGeometryReads(lastVertexBuffer, currentIndexBuffer)
        withLineQuad {
            if (canRecordRasterMotion()) {
                recordMotionBatch(true, parameters, instanceCount, firstInstance, drawCount)
            } else {
                this.pushDescriptors()
                if (!MCDR_ABI.D3D12MultiDrawIndexedPacked(this.ctx, parameters, instanceCount,
                    firstInstance, drawCount)) {
                    throw IllegalStateException("dx12MultiDrawIndexedPacked failed: ${MCDR_ABI.D3D12LastError()}")
                }
            }
            if (instanceCount > 0) recordRasterDraw(additionalBindings?.target ?: 0L)
        }
        D3D12RenderProf.recordMultiDraw(drawCount, profT0)
    }

    override fun multiDrawIndexed(firstIndexOffsets: PointerBuffer, indexCounts: IntBuffer,
        vertexOffsets: IntBuffer, drawCount: Int) {
        val profT0 = if (RenderProfiling.enabled) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            D3D12RenderProf.recordMultiDraw(0, profT0)
            return
        }
        val indexType = this.currentIndexType
            ?: throw IllegalStateException("multiDrawDirectSeparate requires a bound index buffer")
        val elementBytes = if (indexType == IndexType.INT) 4L else 2L
        ensureMultiDrawColumns(drawCount, indexed = true)
        val firstIndices = multiDrawFirstScratch
        val counts = multiDrawCountScratch
        val bases = multiDrawBaseScratch
        for (i in 0 until drawCount) {
            val byteOffset = firstIndexOffsets.get()
            require(byteOffset >= 0L && byteOffset % elementBytes == 0L) {
                "index offset $byteOffset is not aligned to $elementBytes bytes"
            }
            firstIndices[i] = Math.toIntExact(byteOffset / elementBytes)
            counts[i] = indexCounts.get()
            bases[i] = vertexOffsets.get()
        }
        policy.retainIndexedGeometryReads(lastVertexBuffer, currentIndexBuffer)
        withLineQuad {
            if (canRecordRasterMotion()) {
                val parameters = packedMultiDrawParameters(Math.multiplyExact(drawCount, 3))
                for (index in 0 until drawCount) {
                    parameters[index * 3] = firstIndices[index]
                    parameters[index * 3 + 1] = counts[index]
                    parameters[index * 3 + 2] = bases[index]
                }
                recordMotionBatch(true, parameters, 1, 0, drawCount)
            } else {
                this.pushDescriptors()
                if (!MCDR_ABI.D3D12MultiDrawIndexedSeparate(this.ctx, firstIndices, counts, bases, 1, 0,
                    drawCount)) {
                    throw IllegalStateException("dx12MultiDrawIndexedSeparate failed: ${MCDR_ABI.D3D12LastError()}")
                }
            }
            recordRasterDraw(additionalBindings?.target ?: 0L)
        }
        D3D12RenderProf.recordMultiDraw(drawCount, profT0)
    }

    override fun drawIndexedIndirect(commands: GpuBufferSlice, drawCount: Int) {
        require(drawCount >= 0) { "Draw count must be non-negative" }
        if (drawCount == 0) return
        if (route == D3D12RasterRoute.TERRAIN_WORK_GRAPH) {
            tryMeshletDraw(commands, drawCount)
            return
        }
        policy.retainIndexedGeometryReads(lastVertexBuffer, currentIndexBuffer)
        withRasterMotion({ policy.prepareIndirectMotionBindings() }) {
            this.pushDescriptors()
            check(MCDR_ABI.D3D12DrawIndexedIndirect(this.ctx,
                (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
                "dx12DrawIndexedIndirect failed: ${MCDR_ABI.D3D12LastError()}"
            }
            recordRasterDraw(additionalBindings?.target ?: 0L)
        }
    }

    private fun tryMeshletDraw(commands: GpuBufferSlice, drawCount: Int) {
        val compiled = checkNotNull(this.pipeline) { "No terrain pipeline bound" }
        val vertexBuffer = checkNotNull(this.lastVertexBuffer as? D3D12GpuBuffer) { "No terrain vertex buffer" }
        val motionTarget = if (!flipY && hasDepth) rasterMotionTarget else 0L
        val draw = D3D12TerrainDraw(compiled, commands, drawCount, vertexBuffer,
            lastVertexSliceOffset, motionTarget, additionalBindings?.attachment, uniforms,
            textures["Sampler0"]?.view?.takeIf { !flipY && hasDepth }, textures["Sampler0"]?.sampler,
            textures["Sampler2"]?.let { D3D12RasterSampledView(it.view, it.sampler) },
            textures["Sampler0"]?.view, textures["Sampler0"]?.sampler,
        )
        val useDepth = this.hasDepth && compiled.info().depthStencilState != null
        policy.recordTerrain(draw) { prepared ->
            val capture = prepared.capture
            if (capture != null) {
                recordCapturedDraw(capture) {
                    check(prepared.draw(this.ctx, vertexBuffer, this.lastVertexSliceOffset)) {
                        "Terrain capture draw failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                }
                policy.recordDraw(0L)
                return@recordTerrain
            }
            var failure: Throwable? = null
            try {
                check(MCDR_ABI.D3D12SetPipeline(this.ctx, prepared.pipeline, useDepth)) {
                    "Terrain pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
                }
                this.anyDescriptorDirty = true
                this.pushDescriptors()
                check(prepared.draw(this.ctx, vertexBuffer, this.lastVertexSliceOffset)) {
                    "Terrain indirect draw failed: ${MCDR_ABI.D3D12LastError()}"
                }
                recordRasterDraw(additionalBindings?.target ?: 0L)
            } catch (drawFailure: Throwable) {
                failure = drawFailure
                throw drawFailure
            } finally {
                this.anyDescriptorDirty = true
                try {
                    check(MCDR_ABI.D3D12SetPipeline(ctx, additionalBindings?.pso ?: compiled.handle(), useDepth)) {
                        "Terrain pipeline restore failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }


    override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        withRasterMotion({ policy.prepareMotionBindings(firstVertex, instanceCount) }) {
            this.pushDescriptors()
            if (!MCDR_ABI.D3D12Draw(this.ctx, vertexCount, instanceCount, firstVertex, firstInstance)) {
                throw IllegalStateException("dx12Draw failed")
            }
            if (vertexCount > 0 && instanceCount > 0) recordRasterDraw(additionalBindings?.target ?: 0L)
        }
    }

    override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int,
        drawCount: Int) {
        val profT0 = if (RenderProfiling.enabled) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            D3D12RenderProf.recordMultiDraw(0, profT0)
            return
        }
        val parameterCount = Math.multiplyExact(drawCount, 2)
        val parameters = packedMultiDrawParameters(parameterCount)
        drawParameters.get(parameters, 0, parameterCount)
        if (canRecordRasterMotion()) {
            val triples = packedMultiDrawParameters(Math.multiplyExact(drawCount, 3))
            for (index in drawCount - 1 downTo 0) {
                val first = parameters[index * 2]
                val count = parameters[index * 2 + 1]
                triples[index * 3] = first
                triples[index * 3 + 1] = count
                triples[index * 3 + 2] = 0
            }
            recordMotionBatch(false, triples, instanceCount, firstInstance, drawCount)
            if (instanceCount > 0) recordRasterDraw(additionalBindings?.target ?: 0L)
            D3D12RenderProf.recordMultiDraw(drawCount, profT0)
            return
        }
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12MultiDrawPacked(this.ctx, parameters, instanceCount, firstInstance, drawCount)) {
            throw IllegalStateException("dx12MultiDrawPacked failed: ${MCDR_ABI.D3D12LastError()}")
        }
        if (instanceCount > 0) recordRasterDraw(additionalBindings?.target ?: 0L)
        D3D12RenderProf.recordMultiDraw(drawCount, profT0)
    }

    override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) {
        val profT0 = if (RenderProfiling.enabled) System.nanoTime() else 0L
        require(drawCount >= 0) { "drawCount must be non-negative" }
        if (drawCount == 0) {
            D3D12RenderProf.recordMultiDraw(0, profT0)
            return
        }
        ensureMultiDrawColumns(drawCount, indexed = false)
        val first = multiDrawFirstScratch
        val counts = multiDrawCountScratch
        firstVertices.get(first, 0, drawCount)
        vertexCounts.get(counts, 0, drawCount)
        if (canRecordRasterMotion()) {
            val parameters = packedMultiDrawParameters(Math.multiplyExact(drawCount, 3))
            for (index in 0 until drawCount) {
                parameters[index * 3] = first[index]
                parameters[index * 3 + 1] = counts[index]
                parameters[index * 3 + 2] = 0
            }
            recordMotionBatch(false, parameters, 1, 0, drawCount)
            recordRasterDraw(additionalBindings?.target ?: 0L)
            D3D12RenderProf.recordMultiDraw(drawCount, profT0)
            return
        }
        this.pushDescriptors()
        if (!MCDR_ABI.D3D12MultiDrawSeparate(this.ctx, first, counts, drawCount)) {
            throw IllegalStateException("dx12MultiDrawSeparate failed: ${MCDR_ABI.D3D12LastError()}")
        }
        recordRasterDraw(additionalBindings?.target ?: 0L)
        D3D12RenderProf.recordMultiDraw(drawCount, profT0)
    }

    override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) {
        require(drawCount >= 0) { "Draw count must be non-negative" }
        if (drawCount == 0) return
        withRasterMotion({ policy.prepareIndirectMotionBindings() }) {
            this.pushDescriptors()
            check(MCDR_ABI.D3D12DrawIndirect(this.ctx,
                (commands.buffer() as D3D12GpuBuffer).handle(), commands.offset(), drawCount)) {
                "dx12DrawIndirect failed: ${MCDR_ABI.D3D12LastError()}"
            }
            recordRasterDraw(additionalBindings?.target ?: 0L)
        }
    }
}

