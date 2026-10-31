package dev.nts.expdxr.d3d12.render.advanced.mesh

import dev.nts.expdxr.dxrmc.rt.D3D12RtGeometryPool
import net.minecraft.core.SectionPos
import dev.nts.expdxr.misc.settings.D3D12Settings
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * 段几何注册表：段上传时采集 CPU 侧元信息，不缓存 GPU 句柄（Uber 缓冲扩容/整理会让句柄失效）。
 * `levelIdentity` 变化（切世界/重连）时整体清空并递增 epoch。见 docs/mesh&vrs/README.md §2.1。
 */
object D3D12SectionGeometry {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc/mesh")

    /** 单层（SOLID/CUTOUT/TRANSLUCENT）的上传规模。 */
    /**
     * 这一层的索引从哪来。原版对**非**自定义索引的层用一张共享的顺序索引缓冲
     * （`RenderSystem.sharedSequentialQuad`，模式 `(i,i+1,i+2, i+2,i+3,i)`），只有需要排序的
     * 半透明层才带自己的一片索引（`RenderSectionBufferSlice.indexBuffer`）。
     * 对 BLAS 来说两者都是"可用的索引"，区别只在能不能直接复用原版那块缓冲。
     */
    enum class IndexSource {
        /** 没有自己的索引：用原版共享的顺序 quad 缓冲（`RenderSystem.getSequentialBuffer(QUADS)`）。 */
        SEQUENTIAL_QUADS,

        /** 自带索引切片（排序后的半透明等），格式见 [LayerUpload.indexStrideBytes]。 */
        CUSTOM_SLICE,
    }

    data class LayerUpload(
        val layer: String,
        val indexCount: Int,
        val vertexBytes: Int,
        val indexBytes: Int,
        val indexSource: IndexSource = IndexSource.SEQUENTIAL_QUADS,
        /** 自带索引时的元素字节数（2 = R16，4 = R32）；共享路径在分配前不确定，记 0。 */
        val indexStrideBytes: Int = 0,
        /** 该段的 meshlet 表（32 B/条）；`[mesh] terrain` 打开时每次上传都切，否则只做 shadow 统计。 */
        val meshlets: List<D3D12MeshletBuilder.Meshlet> = emptyList(),
    ) {
        val triangles: Int get() = indexCount / 3
        val meshletBytes: Int get() = meshlets.size * D3D12MeshletBuilder.MESHLET_DESC_BYTES
    }

    /**
     * 一个 section 的几何快照。`generation` 是全局计数器（判断"这帧看到的是不是同一版本"）；
     * `geometryVersion` 是**逐 section** 的版本号，只在真正影响三角形的输入变化时 +1
     * （层集合、任一层的 indexCount / 顶点字节数 / 索引字节数），同数据重传不动它。
     */
    data class SectionGeometry(
        val key: Long,
        val epoch: Int,
        val generation: Long,
        val geometryVersion: Int,
        val capturedAtNanos: Long,
        val layers: Map<String, LayerUpload>,
    ) {
        val triangles: Int get() = layers.values.sumOf { it.triangles }
    }

    private val sections = ConcurrentHashMap<Long, SectionGeometry>()
    private var epoch: Int = 0
    private var generation: Long = 0
    private var levelIdentity: Int = 0

    /** 采集计数与成本。 */
    private val uploads = AtomicLong()
    private val capturedTriangles = AtomicLong()
    private val emptyUploads = AtomicLong()
    private val vertexBytesTotal = AtomicLong()
    private val indexBytesTotal = AtomicLong()
    private val customIndexLayers = AtomicLong()
    private val captureNanosTotal = AtomicLong()

    /** shadow 统计只切前 [MESHLET_SHADOW_UPLOADS] 次上传，默认路径零成本。 */
    private const val MESHLET_SHADOW_UPLOADS = 64
    private val meshletUploads = AtomicLong()
    private val meshletCount = AtomicLong()
    private val meshletPure = AtomicLong()
    private val meshletQuads = AtomicLong()
    private val meshletDirections = AtomicIntegerArray(7)

    fun sectionCount(): Int = sections.size

    /** 第一条契约违例的文字（日志里只留一条，避免刷屏）。 */
    private var firstViolation: String? = null

    /**
     * 只检查"不依赖导出面就能判"的三条：顶点必须是 28 B 的整数倍、索引数与索引字节要自洽、
     * 走共享顺序 quad 缓冲的层索引数必须是 6 的倍数（一个 quad 6 个索引）。
     * 依赖体素导出的检查（逐步方块的 kind 与面数）要等 S2，见 §3.6。
     */
    private fun checkContract(
        layer: String,
        indexCount: Int,
        vertexBytes: Int,
        indexBytes: Int,
        source: IndexSource,
        indexStrideBytes: Int,
    ) {
        if (vertexBytes % D3D12MeshletBuilder.VERTEX_STRIDE != 0) {
            violation("layer=$layer vertexBytes=$vertexBytes 不是 ${D3D12MeshletBuilder.VERTEX_STRIDE} 的整数倍")
        }
        if (indexCount > 0 && source == IndexSource.SEQUENTIAL_QUADS && indexCount % QUAD_INDICES != 0) {
            violation("layer=$layer indexCount=$indexCount 不是 $QUAD_INDICES 的整数倍（共享 quad 缓冲）")
        }
        if (source == IndexSource.CUSTOM_SLICE && indexCount > 0) {
            if (indexBytes % indexCount != 0) {
                violation("layer=$layer indexBytes=$indexBytes 不能被 indexCount=$indexCount 整除")
            }
            if (indexStrideBytes != 2 && indexStrideBytes != 4) {
                violation("layer=$layer indexStrideBytes=$indexStrideBytes 既不是 R16 也不是 R32")
            }
        }
    }

    private fun violation(message: String) {
        violations.incrementAndGet()
        if (firstViolation == null) {
            firstViolation = message
        }
    }
    /** 两次上传的三角形相关输入是否完全一致（决定 `geometryVersion` 要不要 +1）。 */
    private fun sameGeometry(before: Map<String, LayerUpload>, after: Map<String, LayerUpload>): Boolean {
        if (before.keys != after.keys) return false
        for ((layer, a) in before) {
            val b = after[layer] ?: return false
            if (a.indexCount != b.indexCount || a.vertexBytes != b.vertexBytes || a.indexBytes != b.indexBytes) return false
            if (a.indexSource != b.indexSource || a.indexStrideBytes != b.indexStrideBytes) return false
        }
        return true
    }

    fun epoch(): Int = epoch


    fun find(sectionNode: Long): SectionGeometry? = sections[sectionNode]

    /** S2 导出缓存只关心"变没变"，拿到当前版本号即可。 */
    @JvmStatic
    fun geometryVersionOf(sectionNode: Long): Int = sections[sectionNode]?.geometryVersion ?: -1

    /**
     * 记录一次上传；`levelIdentity` 变化时清空注册表并推进 epoch。渲染线程与 worker 线程都会调用。
     */
    @JvmStatic
    fun captureUpload(sectionNode: Long, layer: String, indexCount: Int, vertexBytes: Int,
                      indexBytes: Int, levelIdentity: Int, vertex: ByteBuffer?,
                      indexSource: IndexSource, indexStrideBytes: Int, index: ByteBuffer? = null) {
        val started = System.nanoTime()
        if (levelIdentity != this.levelIdentity) {
            sections.clear()
            this.levelIdentity = levelIdentity
            epoch++
        }
        // 切分是旁路数据：`[mesh] terrain` 打开时每次上传都切（派发要全量段表），否则只做前
        // [MESHLET_SHADOW_UPLOADS] 次统计。失败 / 缓冲为空都静默跳过 —— 绝不能影响上传本身。
        val shadowLimit = if (D3D12Settings.meshTerrain) Int.MAX_VALUE else MESHLET_SHADOW_UPLOADS
        var meshlets: List<D3D12MeshletBuilder.Meshlet> = emptyList()
        if (vertex != null && indexCount > 0 && meshletUploads.get() < shadowLimit) {
            val built = try {
                D3D12MeshletBuilder.build(vertex)
            } catch (ignored: Throwable) {
                null
            }
            if (built != null) {
                meshlets = built.meshlets
                val n = meshletUploads.incrementAndGet()
                meshletQuads.addAndGet(built.quads.toLong())
                meshletCount.addAndGet(built.meshletCount.toLong())
                meshletPure.addAndGet(built.pureMeshlets.toLong())
                for (i in 0 until 7) {
                    meshletDirections.addAndGet(i, built.directionCounts[i])
                }
                if (!D3D12Settings.meshTerrain &&
                    (n == MESHLET_SHADOW_UPLOADS.toLong() || n % 16L == 0L)) {
                    LOGGER.warn("meshlet shadow: {}", meshletSummary())
                }
            }
        }
        checkContract(layer, indexCount, vertexBytes, indexBytes, indexSource, indexStrideBytes)
        val upload = LayerUpload(layer, indexCount, vertexBytes, indexBytes, indexSource, indexStrideBytes, meshlets)
        val existing = sections[sectionNode]
        val layers = if (existing == null) {
            LinkedHashMap<String, LayerUpload>(3)
        } else {
            LinkedHashMap(existing.layers)
        }
        layers[layer] = upload
        val version = if (existing != null && sameGeometry(existing.layers, layers)) {
            existing.geometryVersion
        } else {
            versionBumps.incrementAndGet()
            (existing?.geometryVersion ?: 0) + 1
        }
        val custom = layers.values.any { it.indexSource == IndexSource.CUSTOM_SLICE }
        if (custom) {
            customIndexLayers.incrementAndGet()
        }
        val geometry = SectionGeometry(
            key = sectionNode,
            epoch = epoch,
            generation = ++generation,
            geometryVersion = version,
            capturedAtNanos = started,
            layers = layers,
        )
        val contracts = layers.keys.map(::kindOfLayer)
        for (kind in contracts) {
            kindCounts.incrementAndGet(kind.ordinal)
        }
        quadIndices.addAndGet((layers.values.sumOf { it.vertexBytes } / D3D12MeshletBuilder.VERTEX_STRIDE / QUAD_VERTICES * QUAD_INDICES).toLong())
        sections[sectionNode] = geometry
        if (vertex != null) {
            // 导出面在 worker 线程上跑：池里的任何异常都不许把区块上传打断。
            try {
                D3D12RtGeometryPool.publish(sectionNode, layer, version, vertex, index, indexCount)
            } catch (t: Throwable) {
                poolFailures.incrementAndGet()
                if (poolFailures.get() <= 4L) {
                    LOGGER.warn("rt geometry pool publish failed: {}: {}",
                        t.javaClass.simpleName, t.message)
                }
            }
        }
        val total = uploads.incrementAndGet()
        if (indexCount <= 0) {
            emptyUploads.incrementAndGet()
        } else {
            capturedTriangles.addAndGet(upload.triangles.toLong())
        }
        vertexBytesTotal.addAndGet(vertexBytes.toLong())
        indexBytesTotal.addAndGet(indexBytes.toLong())
        captureNanosTotal.addAndGet(System.nanoTime() - started)
        if (total <= 3L || total % 1024L == 0L) {
            val pos = SectionPos.of(sectionNode)
            LOGGER.debug("section upload #{} at ({},{},{}) vBytes={} iBytes={} bad={}",
                total, pos.x(), pos.y(), pos.z(), vertexBytes, indexBytes,
                D3D12RtGeometryPool.validate())
        }
    }

    private val poolFailures = java.util.concurrent.atomic.AtomicLong()

    private fun poolSummary(): String = try {
        D3D12RtGeometryPool.summary()
    } catch (t: Throwable) {
        "rt geometry pool: summary failed: ${t.javaClass.simpleName}"
    }

    /** 采集成本与规模的一行摘要（日志用；调用方自己控制频率）。 */
    fun summary(): String {
        val count = uploads.get()
        val avgUs = if (count == 0L) 0.0 else captureNanosTotal.get() / 1000.0 / count
        return "section geometry: sections=${sections.size} uploads=$count " +
            "triangles=${capturedTriangles.get()} empty=${emptyUploads.get()} " +
            "vMB=${vertexBytesTotal.get() / (1024 * 1024)} iMB=${indexBytesTotal.get() / (1024 * 1024)} " +
            "avg=${"%.1f".format(avgUs)}us epoch=$epoch gen=$generation"
    }


    // ---- S1 / P83-a：SectionGeometry 契约 -------------------------------------------------
    // 契约本身是"一份几何、两个导出"的唯一上游（rt_plan §3.0）。今天能填的是
    // sectionId / geometryVersion / epoch / origin / flags / 顶点与 quad 索引范围 / AABB；
    // faceRange 与 materialSetId 归体素导出（P83-c），在材质表落地前恒为 0。

    /** 原版方块几何每个 quad 4 个顶点；quad q 的索引 = (4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3)。 */
    const val QUAD_VERTICES = 4
    const val QUAD_INDICES = 6

    /** `flags` 的位（rt_plan §3.0）。 */
    object Flag {
        const val SOLID = 1 shl 0
        const val CUTOUT = 1 shl 1
        const val TRANSLUCENT = 1 shl 2
        const val MODEL = 1 shl 3
        const val FLUID = 1 shl 4
    }

    /**
     * rt_plan §3.0 的 8 类几何。今天的分类只到"层"这一粒度（上传回调只给层，没有方块 id），
     * 逐方块判定要等 S2 的材质表；因此 [authoritative] 的结论只在 CUBE 一行是精确的。
     */
    enum class GeometryKind(val voxelDda: Boolean, val triangles: Boolean, val note: String) {
        CUBE(true, true, "双轨都可用"),
        SHAPED_BLOCK(false, true, "体素只记近似，强制转 RayQuery"),
        CUTOUT_PLANT(false, true, "无厚度，归 CUTOUT"),
        LEAVES(false, true, "体素只记占据"),
        TRANSLUCENT(false, true, "合成归 OIT，不参与 DDA 短路"),
        ENTITY(false, true, "动态实例"),
        MODEL_BLOCK(false, false, "顶点是否进 uber buffer 未验证"),
        SUBVOXEL(false, true, "细节由命中点 UV 补"),
    }

    /** 权威轨：两个都为 true 时按 §3.5 的分派规则选。 */
    fun authoritative(kind: GeometryKind): String = when {
        kind.voxelDda && kind.triangles -> "either"
        kind.triangles -> "triangles"
        kind.voxelDda -> "voxel"
        else -> "none"
    }

    /**
     * 上传回调给的层名是 `ChunkSectionLayer.name()`（全大写枚举名，不随语言变），四种取值固定，
     * 所以 [ordinal] 可以直接当槽号：`sectionNode * 8 + ordinal` 不会互相覆盖。
     * 契约位与几何类别跟着层走，一处定义、索引池与材质表共用。
     */
    enum class Layer(val flag: Int, val kind: GeometryKind) {
        SOLID(Flag.SOLID, GeometryKind.CUBE),
        CUTOUT(Flag.CUTOUT, GeometryKind.CUTOUT_PLANT),
        TRANSLUCENT(Flag.TRANSLUCENT, GeometryKind.TRANSLUCENT),
        OTHER(0, GeometryKind.SUBVOXEL),
        ;

        companion object {
            fun of(layer: String): Layer = when (layer.uppercase(Locale.ROOT)) {
                "SOLID" -> SOLID
                "CUTOUT" -> CUTOUT
                "TRANSLUCENT" -> TRANSLUCENT
                else -> OTHER
            }
        }
    }

    /** 由层名映射到几何类别（粗粒度）。 */
    fun kindOfLayer(layer: String): GeometryKind = Layer.of(layer).kind

    /**
     * 契约的一条记录。字段顺序与 rt_plan §3.0 的 15 行一一对应；`-1` = 该字段还没导出
     * （顶点/索引范围要等全局 quad 索引缓冲，见 S1 步骤 3）。
     */
    data class Contract(
        val sectionId: Long,
        val geometryVersion: Int,
        val epoch: Int,
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val flags: Int,
        val vertexOffset: Int,
        val vertexCount: Int,
        val quadIndexOffset: Int,
        val quadIndexCount: Int,
        val faceRange: Int,
        val materialSetId: Int,
        val worldMin: FloatArray,
        val worldMax: FloatArray,
        val kinds: Set<GeometryKind>,
    ) {
        val authoritative: String
            get() = if (kinds.isEmpty()) "none"
            else if (kinds.size == 1) D3D12SectionGeometry.authoritative(kinds.first())
            else "either"
    }

    private val violations = AtomicLong()
    private val versionBumps = AtomicLong()
    private val quadIndices = AtomicLong()
    private val kindCounts = AtomicIntegerArray(GeometryKind.entries.size)

    /** 契约记录；`null` = 这个 section 还没被采集过。 */
    fun contract(sectionNode: Long): Contract? {
        val geometry = sections[sectionNode] ?: return null
        val pos = SectionPos.of(sectionNode)
        val flags = geometry.layers.keys.fold(0) { acc, layer -> acc or Layer.of(layer).flag }
        val vertexCount = geometry.layers.values.sumOf { it.vertexBytes } / D3D12MeshletBuilder.VERTEX_STRIDE
        val quads = vertexCount / QUAD_VERTICES
        val originX = pos.x() * 16
        val originY = pos.y() * 16
        val originZ = pos.z() * 16
        return Contract(
            sectionId = sectionNode,
            geometryVersion = geometry.geometryVersion,
            epoch = geometry.epoch,
            originX = originX,
            originY = originY,
            originZ = originZ,
            flags = flags,
            vertexOffset = -1,
            vertexCount = vertexCount,
            quadIndexOffset = -1,
            quadIndexCount = quads * QUAD_INDICES,
            faceRange = 0,
            materialSetId = 0,
            worldMin = floatArrayOf(originX.toFloat(), originY.toFloat(), originZ.toFloat()),
            worldMax = floatArrayOf((originX + 16).toFloat(), (originY + 16).toFloat(), (originZ + 16).toFloat()),
            kinds = geometry.layers.keys.map(::kindOfLayer).toSet(),
        )
    }

    /** 契约规模的单行摘要（日志与 `/rt/debug/geometry` 共用）。 */
    fun contractSummary(): String {
        val counts = GeometryKind.entries.mapIndexed { i, kind -> "${kind.name}=${kindCounts.get(i)}" }
        return "contract: sections=${sections.size} versions=$versionBumps violations=$violations " +
            "quadIdx=${quadIndices.get()} custom=${customIndexLayers.get()}" +
            (firstViolation?.let { " first=${it}" } ?: "") +
            " [${counts.joinToString(" ")}]"
    }
    /**
     * shadow 统计摘要：`pure` = 单方向 meshlet 占比，`dirs` = 逐 Quad 朝向分布（+X…-Z, non-axis）。
     */
    fun meshletSummary(): String {
        val sections = meshletUploads.get()
        val meshlets = meshletCount.get()
        val purePct = if (meshlets == 0L) 0.0 else meshletPure.get() * 100.0 / meshlets
        val perSection = if (sections == 0L) 0.0 else meshlets.toDouble() / sections
        val dirs = (0 until 7).joinToString(",") { meshletDirections.get(it).toString() }
        return "sections=$sections meshlets=$meshlets perSection=${"%.1f".format(perSection)} " +
            "quads=${meshletQuads.get()} pure=${"%.1f".format(purePct)}% dirs=[$dirs]"
    }
}