package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.nts.expdxr.RtBackend
import dev.nts.expdxr.RtDebugView
import dev.nts.expdxr.d3d12.backend.D3D12CompiledRenderPipeline
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings

/**
 * G-Buffer 的五张附件与「待重放的 meshlet 派发」清单。
 * 主 pass 一旦结束就不能在同一次渲染里重新打开，所以 G-Buffer 只能是一个**独立的 pass**：。
 * 见 docs/rt/README.md
 */
object D3D12RtGBuffer {

    /** 颜色附件数；与 [D3D12RtGBufferShader] 的 `SV_Target0..4` 一一对应。 */
    const val ATTACHMENTS = 5

    const val ALBEDO = 0

    const val SPECULAR = 1

    const val NORMAL_ROUGHNESS = 2

    const val EMISSIVE = 3

    /**
     * 相机距离（线性深度，按 [DEPTH_SCALE] 归一）。第五张附件而不是去读深度附件：。
     * 见 docs/rt/README.md
     */
    const val LINEAR_DEPTH = 4

    /**
     * 2-c 的粗糙度占位值：材质表还没接进 P/S（mesh 根签名的 extras 表固定 3 个 range），。
     * 见 docs/rt/README.md
     */
    const val DEFAULT_ROUGHNESS = 0.8f

    /** 线性深度的归一尺度（方块）：`saturate(distance / 64)`，与追踪半径一致。 */
    const val DEPTH_SCALE = 64.0f

    /**
     * 主 pass 里一次成功的 meshlet 派发。句柄都指向本帧的瞬时缓冲，跨帧即失效 ——。
     * 所以清单只在本帧内被消费（`submitRenderPass` 里），不能缓存到下一帧。
     * 见 docs/rt/README.md
     */
    class Batch(
        val pipeline: D3D12CompiledRenderPipeline,
        val pso: Long,
        val vertexBuffer: Long,
        val vertexOffset: Long,
        val sections: Long,
        val sectionsOffset: Long,
        val sectionsLength: Long,
        val meshlets: Long,
        val meshletsOffset: Long,
        val meshletsLength: Long,
        val meshletCount: Int,
        val uniforms: Map<String, GpuBufferSlice>,
        val textureViews: Map<String, D3D12GpuTextureView>,
    )

    /** 五张附件 + 自己的深度；尺寸随主渲染目标变化，变了就整组重建。 */
    class Targets(
        val width: Int,
        val height: Int,
        private val textures: List<D3D12GpuTexture>,
        private val depth: D3D12GpuTexture,
    ) {
        fun texture(index: Int): D3D12GpuTexture = textures[index]

        fun handle(index: Int): Long = textures[index].handle()

        /**
         * G-Buffer 自己的深度。不能复用主 pass 那份：同一几何在同帧第二次绘制时深度值相等，。
         * `GREATER_THAN_OR_EQUAL` 在浮点不完全相同时会整片拒绝（地平线窄带、近处楔形的漏画就是这样来的）。
         * 见 docs/rt/README.md
         */
        fun depthTexture(): D3D12GpuTexture = depth

        fun depthHandle(): Long = depth.handle()

        /** 该附件的字节数（按格式与尺寸算）。 */
        fun bytesOf(index: Int): Long {
            val texture = textures[index]
            return pixels(texture) * bytesPerPixel(texture.format)
        }

        /** 深度附件的字节数。 */
        fun depthBytes(): Long = pixels(depth) * bytesPerPixel(depth.format)

        private fun pixels(texture: D3D12GpuTexture): Long = texture.getWidth(0).toLong() * texture.getHeight(0)

        private fun bytesPerPixel(format: GpuFormat): Int = when (format) {
            GpuFormat.RGBA16_UNORM, GpuFormat.RGBA16_SNORM, GpuFormat.RGBA16_UINT,
            GpuFormat.RGBA16_SINT, GpuFormat.RGBA16_FLOAT -> 8
            GpuFormat.R32_FLOAT, GpuFormat.D32_FLOAT, GpuFormat.RGBA8_UNORM, GpuFormat.R8_UNORM -> 4
            else -> 4
        }

        fun close() {
            for (texture in textures) {
                try {
                    texture.close()
                } catch (_: Throwable) {
                    // 单个附件销毁失败不能挡住其余三张。
                }
            }
            try {
                depth.close()
            } catch (_: Throwable) {
                // 同上：清理阶段的失败只吞掉。
            }
        }
    }

    /** 五张附件 + 深度的显存占用（基线数据用；按格式算，不含驱动侧对齐）。 */
    fun byteCount(): Long {
        val current = targets ?: return 0L
        var bytes = current.depthBytes()
        for (index in 0 until ATTACHMENTS) {
            bytes += current.bytesOf(index)
        }
        return bytes
    }

    @Volatile
    private var targets: Targets? = null

    /** 最近一次拿到过的设备：场景自检要自带编码器，只能在 tick 这类没有列表打开的时刻跑。 */
    @Volatile
    private var device: D3D12Device? = null

    @Volatile
    private var failures = 0

    internal fun deviceForSelfTest(): D3D12Device? = this.device

    /** 光追关闭时整条 G-Buffer 路径不工作（默认玩家零开销）。 */
    fun isActive(): Boolean = D3D12Settings.rtEnabled

    fun targets(): Targets? = this.targets

    /** 第 [index] 张附件；还没分配时返回 null。合光要读 albedo / emissive 两张。 */
    fun texture(index: Int): D3D12GpuTexture? = this.targets?.texture(index)

    /** G-Buffer 自己的深度附件；DEPTH 调试视图读它。还没分配时返回 null。 */
    fun depthTexture(): D3D12GpuTexture? = this.targets?.depthTexture()

    /** 取（必要时重建）五张附件；失败返回 null，调用方必须跳过这次 G-Buffer pass。 */
    fun ensure(device: D3D12Device?, width: Int, height: Int): Targets? {
        if (device == null || !isActive() || width <= 0 || height <= 0) {
            return null
        }
        this.device = device
        val current = this.targets
        if (current != null && current.width == width && current.height == height) {
            return current
        }
        release()
        val created = try {
            create(device, width, height)
        } catch (t: Throwable) {
            fail("allocate failed: ${t.message}")
            return null
        }
        this.targets = created
        IllusionLogger.infoMessage("rt gbuffer: targets ${width}x${height} " +
            "albedo=${GpuFormat.RGBA8_UNORM.name} specular=${GpuFormat.RGBA8_UNORM.name} " +
            "normalRough=${GpuFormat.RGBA16_FLOAT.name} emissive=${GpuFormat.RGBA16_FLOAT.name} " +
            "linearDepth=${GpuFormat.RGBA16_FLOAT.name}")
        return created
    }

    /**
     * 当前 `debugView` 要铺到主目标上的那张附件；DEPTH 由主目标的深度提供，这里返回 null。
     * 见 docs/rt/README.md
     */
    fun debugTexture(): D3D12GpuTexture? {
        val view = D3D12Settings.rtDebugView
        val current = this.targets ?: return null
        return when (view) {
            RtDebugView.ALBEDO.ordinal -> current.texture(ALBEDO)
            RtDebugView.DEPTH.ordinal -> current.texture(LINEAR_DEPTH)
            RtDebugView.NORMAL.ordinal -> current.texture(NORMAL_ROUGHNESS)
            else -> null
        }
    }

    /** 每客户端 tick 一次：光追关掉后把五张附件还给设备，不留悬挂资源。 */
    fun tick() {
        if (!isActive() && this.targets != null) {
            release()
        }
    }

    fun release() {
        val current = this.targets ?: return
        this.targets = null
        current.close()
    }

    private fun create(device: D3D12Device, width: Int, height: Int): Targets {
        val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT
        val formats = arrayOf(
            GpuFormat.RGBA8_UNORM,
            GpuFormat.RGBA8_UNORM,
            GpuFormat.RGBA16_FLOAT,
            GpuFormat.RGBA16_FLOAT,
            GpuFormat.RGBA16_FLOAT,
        )
        val labels = arrayOf("rt-gbuffer-albedo", "rt-gbuffer-specular",
            "rt-gbuffer-normal-roughness", "rt-gbuffer-emissive", "rt-gbuffer-depth-linear")
        val created = ArrayList<D3D12GpuTexture>(ATTACHMENTS)
        var depth: D3D12GpuTexture? = null
        try {
            for (index in 0 until ATTACHMENTS) {
                val attachmentUsage = usage or D3D12GpuTexture.USAGE_STORAGE
                created.add(device.createTexture(labels[index], attachmentUsage, formats[index],
                    width, height, 1, 1) as? D3D12GpuTexture
                    ?: throw IllegalStateException("${labels[index]} allocate failed"))
            }
            depth = device.createTexture("rt-gbuffer-depth", GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.D32_FLOAT, width, height, 1, 1) as? D3D12GpuTexture
                ?: throw IllegalStateException("rt-gbuffer-depth allocate failed")
        } catch (t: Throwable) {
            for (texture in created) {
                try {
                    texture.close()
                } catch (_: Throwable) {
                    // 同上：清理阶段的失败只吞掉。
                }
            }
            if (depth != null) {
                try {
                    depth.close()
                } catch (_: Throwable) {
                    // 同上。
                }
            }
            throw t
        }
        return Targets(width, height, created, requireNotNull(depth))
    }

    private fun fail(reason: String) {
        if (this.failures >= 4) {
            return
        }
        this.failures++
        IllusionLogger.warnMessage("rt gbuffer: $reason (${this.failures}/4)")
    }
}
