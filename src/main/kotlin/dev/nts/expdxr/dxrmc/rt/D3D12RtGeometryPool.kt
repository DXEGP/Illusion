package dev.nts.expdxr.dxrmc.rt

import dev.nts.expdxr.RtBackend
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12MeshletBuilder
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12SectionGeometry
import dev.nts.expdxr.misc.settings.D3D12Settings
import net.minecraft.core.SectionPos
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * S2 的导出几何缓存：把每段每层的顶点（28 B/顶点，`DefaultVertexFormat.BLOCK`）、索引与逐 quad。
 * 只在 `geometryVersion` 变化时写，重传同数据不会重复拷贝；超容量整体清空并记一次日志。
 * 见 docs/rt/README.md
 */
object D3D12RtGeometryPool {
    private val LOGGER = LoggerFactory.getLogger("dxr_mc/rtpool")

    /** 顶点 / 索引 / 材质池上限。12~16 区块半径全部层的顶点约 60~120 MB，留一倍余量。 */
    private const val VERTEX_CAP = 768L * 1024 * 1024
    private const val INDEX_CAP = 96L * 1024 * 1024
    private const val MATERIAL_CAP = 96L * 1024 * 1024

    private const val STRIDE = D3D12MeshletBuilder.VERTEX_STRIDE
    private const val QUAD_VERTICES = 4
    private const val QUAD_INDICES = 6

    /** 逐 quad 材质索引的字节数（u16，指向 [D3D12RtMaterialTable] 的记录）。 */
    private const val MATERIAL_BYTES = 2

    /** 一条"段-层"记录；偏移是相对池首的字节偏移。 */
    data class Record(
        val sectionNode: Long,
        val layer: Int,
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val geometryVersion: Int,
        val flags: Int,
        val vertexOffset: Long,
        val vertexCount: Int,
        val indexOffset: Long,
        val indexCount: Int,
        val indexStride: Int,
        val quadCount: Int,
        val materialOffset: Long,
    ) {
        val triangles: Int get() = indexCount / 3
    }

    private val records = ConcurrentHashMap<Long, Record>()
    private var vertices: ByteBuffer = ByteBuffer.allocate(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
    private var indices: ByteBuffer = ByteBuffer.allocate(1 shl 18).order(ByteOrder.LITTLE_ENDIAN)
    private var materials: ByteBuffer = ByteBuffer.allocate(1 shl 18).order(ByteOrder.LITTLE_ENDIAN)

    private val revision = AtomicLong()
    private val published = AtomicLong()
    private val skipped = AtomicLong()
    private val resets = AtomicLong()
    private val copiedBytes = AtomicLong()
    private val hidden = AtomicLong()

    /** 池写满后置位：宁可停收新段，也不把已经收好的近处地形扔掉。 */
    private var full = false

    /**
     * 发布一段一层的几何。`vertex` / `index` 是上传时看到的字节：。
     * 见 docs/rt/README.md
     */
    @Synchronized
    @JvmStatic
    fun publish(sectionNode: Long, layer: String, geometryVersion: Int,
                vertex: ByteBuffer, index: ByteBuffer?, indexCount: Int): Boolean {
        if (!D3D12Settings.rtEnabled) {
            return false
        }
        val kind = D3D12SectionGeometry.Layer.of(layer)
        // 半透明不参与三角形追踪（S4 的 OIT 才处理它），复制进来只是白占池子。
        if (kind == D3D12SectionGeometry.Layer.TRANSLUCENT) {
            hidden.incrementAndGet()
            return false
        }
        if (full) {
            skipped.incrementAndGet()
            return false
        }
        val key = sectionNode * 8 + kind.ordinal
        val previous = records[key]
        if (previous != null && previous.geometryVersion == geometryVersion) {
            skipped.incrementAndGet()
            return false
        }
        val vertexBytes = vertex.remaining()
        val vertexCount = vertexBytes / STRIDE
        if (vertexCount <= 0) {
            return false
        }
        val quadCount = vertexCount / QUAD_VERTICES
        val effectiveIndexCount = if (indexCount > 0) indexCount else quadCount * QUAD_INDICES
        val indexStride = if (index != null && indexCount > 0) {
            if (index.remaining() / indexCount >= 4) 4 else 2
        } else {
            // 原版共享顺序缓冲的元素大小按顶点数选；我们合成同样规则的索引。
            if (vertexCount > 0xFFFF) 4 else 2
        }
        val indexBytes = effectiveIndexCount.toLong() * indexStride
        val materialBytes = quadCount.toLong() * MATERIAL_BYTES
        if (vertices.position().toLong() + vertexBytes > VERTEX_CAP ||
            indices.position().toLong() + indexBytes > INDEX_CAP ||
            materials.position().toLong() + materialBytes > MATERIAL_CAP
        ) {
            // 写满后停止收新段。清空会把已经收好的近处地形一起丢掉，而近处正是追踪要用的那一批，
            // 重新上传要等方块变化或重进世界，画面会一直缺几何。
            if (!full) {
                full = true
                LOGGER.warn("rt pool full: v={} i={} m={} MB records={}",
                    vertices.position() shr 20, indices.position() shr 20, materials.position() shr 20,
                    records.size)
            }
            skipped.incrementAndGet()
            return false
        }
        vertices = grow(vertices, vertexBytes, VERTEX_CAP)
        val vertexOffset = vertices.position().toLong()
        append(vertices, vertex)
        indices = grow(indices, indexBytes.toInt(), INDEX_CAP)
        val indexOffset = indices.position().toLong()
        if (index != null && indexCount > 0) {
            append(indices, index)
        } else {
            writeSequentialQuadIndices(indices, quadCount, indexStride)
        }
        materials = grow(materials, materialBytes.toInt(), MATERIAL_CAP)
        val materialOffset = materials.position().toLong()
        appendMaterials(vertex, quadCount)
        copiedBytes.addAndGet(vertexBytes + indexBytes + materialBytes)
        val pos = SectionPos.of(sectionNode)
        records[key] = Record(
            sectionNode = sectionNode,
            layer = kind.ordinal,
            originX = pos.x() * 16,
            originY = pos.y() * 16,
            originZ = pos.z() * 16,
            geometryVersion = geometryVersion,
            flags = kind.flag,
            vertexOffset = vertexOffset,
            vertexCount = vertexCount,
            indexOffset = indexOffset,
            indexCount = effectiveIndexCount,
            indexStride = indexStride,
            quadCount = quadCount,
            materialOffset = materialOffset,
        )
        published.incrementAndGet()
        revision.incrementAndGet()
        return true
    }

    /** 每次成功写入、材质回填与清空都会 +1：上传镜像靠它判断要不要重传。 */
    @JvmStatic
    fun revision(): Long = revision.get()

    @JvmStatic
    fun recordCount(): Int = records.size

    @JvmStatic
    fun records(): Collection<Record> = records.values

    @JvmStatic
    fun triangleCount(): Long = records.values.sumOf { it.indexCount.toLong() / 3 }

    /** 顶点 / 索引 / 材质池的只读视图（`limit` = 已写字节数），给上传用。 */
    @JvmStatic
    @Synchronized
    fun vertexBytes(): ByteBuffer = vertices.duplicate().also { it.flip() }

    @JvmStatic
    @Synchronized
    fun indexBytes(): ByteBuffer = indices.duplicate().also { it.flip() }

    @JvmStatic
    @Synchronized
    fun materialBytes(): ByteBuffer = materials.duplicate().also { it.flip() }

    @JvmStatic
    fun summary(): String = "rt geometry pool: records=${records.size} published=${published.get()} " +
        "skipped=${skipped.get()} hidden=${hidden.get()} full=$full triangles=${triangleCount()} " +
        "v=${vertices.position() shr 20}MB i=${indices.position() shr 20}MB " +
        "m=${materials.position() shr 20}MB copied=${copiedBytes.get() shr 20}MB"

    /**
     * 材质表刚烘好时回填全部记录的材质索引：表就绪之前的上传只能写兜底值。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun refreshMaterials(): Int {
        if (materials.position() == 0) {
            return 0
        }
        val view = vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var updated = 0
        for (r in records.values) {
            if (r.quadCount <= 0) continue
            val ids = D3D12RtMaterialTable.quadMaterials(view, r.vertexOffset.toInt(), r.quadCount)
            val at = r.materialOffset.toInt()
            for (i in 0 until r.quadCount) {
                materials.putShort(at + i * MATERIAL_BYTES, ids[i])
            }
            updated++
        }
        revision.incrementAndGet()
        return updated
    }

    /**
     * 每条记录的索引必须落在自己的顶点区间内；返回违规记录数。
     * 偏移或步长错一位时首个索引就会越界，抽样足够抓住结构错误。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun validate(): Int {
        var bad = 0
        val view = indices.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (r in records.values) {
            if (r.indexCount <= 0) continue
            val step = (r.indexCount / VALIDATE_SAMPLES).coerceAtLeast(1)
            var violation = false
            var i = 0
            while (i < r.indexCount) {
                val at = (r.indexOffset + i.toLong() * r.indexStride).toInt()
                if (at + r.indexStride > view.limit()) {
                    violation = true
                    break
                }
                val value = if (r.indexStride == 4) view.getInt(at) else view.getShort(at).toInt() and 0xFFFF
                if (value >= r.vertexCount) {
                    violation = true
                    break
                }
                i += step
            }
            if (!violation) {
                val last = (r.indexOffset + (r.indexCount - 1).toLong() * r.indexStride).toInt()
                if (last + r.indexStride > view.limit()) {
                    violation = true
                } else {
                    val value = if (r.indexStride == 4) view.getInt(last) else view.getShort(last).toInt() and 0xFFFF
                    violation = value >= r.vertexCount
                }
            }
            if (violation) {
                bad++
            }
        }
        return bad
    }

    private const val VALIDATE_SAMPLES = 64

    /** 一条记录在 GPU 侧的字节数（64 B，见 rt_plan §3.4 的 `SectionRecord`）。 */
    const val RECORD_BYTES = 64

    /**
     * 把当前所有记录打包成 `RECORD_BYTES` 步长的字节块，供 blocks 当 `ByteAddressBuffer` 读。
     * 见 docs/rt/README.md
     */
    @JvmStatic
    @Synchronized
    fun recordBytes(): ByteBuffer {
        val ordered = records.values.sortedWith(
            compareBy({ it.sectionNode }, { it.layer }))
        val buffer = ByteBuffer.allocate(ordered.size * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (r in ordered) {
            buffer.putInt(r.originX)
            buffer.putInt(r.originY)
            buffer.putInt(r.originZ)
            buffer.putInt(r.layer)
            buffer.putLong(r.vertexOffset)
            buffer.putLong(r.indexOffset)
            buffer.putInt(r.vertexCount)
            buffer.putInt(r.indexCount)
            buffer.putInt(r.indexStride or (r.flags shl 8))
            buffer.putInt(r.geometryVersion)
            buffer.putInt(r.materialOffset.toInt())
            buffer.putInt(r.quadCount)
            buffer.putLong(r.sectionNode)
        }
        buffer.flip()
        return buffer
    }

    /**
     * 相机附近那一小片几何的紧凑副本，供 compute 上传成镜像缓冲。
     * 的 memcpy 加一次大分配，画面会直接卡死。这里只挑相机附近、且总量封顶的一批段，几十毫秒就能传完。
     * 见 docs/rt/README.md
     */
    class MirrorSlice(
        val records: ByteBuffer,
        val vertices: ByteBuffer,
        val indices: ByteBuffer,
        val quads: ByteBuffer,
        val recordCount: Int,
        val triangles: Long,
    )

    /**
     * 挑出段中心在 `radius` 内、按距离由近到远的前若干段，压成紧凑的 64 B 记录 + 顶点/索引/材质三块。
     * 见 docs/rt/README.md
     */
    private fun pad4(bytes: Long): Int = ((bytes + 3L) and -4L).toInt()

    @JvmStatic
    @Synchronized
    fun mirrorNear(x: Float, y: Float, z: Float, radius: Float, maxRecords: Int,
                   maxBytes: Long): MirrorSlice? {
        if (records.isEmpty() || maxRecords <= 0 || maxBytes <= 0) {
            return null
        }
        val limit = radius * radius
        val picked = ArrayList<Pair<Record, Float>>(records.size.coerceAtMost(256))
        for (r in records.values) {
            val dx = (r.originX + 8) - x
            val dy = (r.originY + 8) - y
            val dz = (r.originZ + 8) - z
            val distance = dx * dx + dy * dy + dz * dz
            if (distance <= limit) {
                picked.add(r to distance)
            }
        }
        if (picked.isEmpty()) {
            return null
        }
        picked.sortBy { it.second }
        // 先按上限挑出真正要装的段，算出精确字节数再分配：一次装不下的大段不能撑爆输出缓冲。
        val chosen = ArrayList<Record>(minOf(picked.size, maxRecords))
        var verticesBytes = 0L
        var indicesBytes = 0L
        var quadsBytes = 0L
        for ([r, _] in picked) {
            if (chosen.size >= maxRecords) break
            val vertexBytes = r.vertexCount.toLong() * STRIDE
            val indexBytes = r.indexCount.toLong() * r.indexStride
            val quadBytes = r.quadCount.toLong() * MATERIAL_BYTES
            if (verticesBytes + indicesBytes + quadsBytes + vertexBytes + indexBytes + quadBytes > maxBytes) {
                break
            }
            chosen.add(r)
            verticesBytes += vertexBytes
            indicesBytes += indexBytes
            quadsBytes += quadBytes
        }
        if (chosen.isEmpty()) {
            return null
        }
        // raw SRV 的元素粒度是 4 字节（NumElements = size/4），长度不是 4 的倍数时最后几个字节读不到：
        // 索引按 u16 计，奇数个索引就只有 2 字节余量，会把三条边之一读成 0 得到退化三角形。
        val recordsOut = ByteBuffer.allocate(chosen.size * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val verticesOut = ByteBuffer.allocate(pad4(verticesBytes)).order(ByteOrder.LITTLE_ENDIAN)
        val indicesOut = ByteBuffer.allocate(pad4(indicesBytes)).order(ByteOrder.LITTLE_ENDIAN)
        val quadsOut = ByteBuffer.allocate(pad4(quadsBytes)).order(ByteOrder.LITTLE_ENDIAN)
        var triangles = 0L
        for (r in chosen) {
            val vertexBytes = r.vertexCount.toLong() * STRIDE
            val indexBytes = r.indexCount.toLong() * r.indexStride
            val quadBytes = r.quadCount.toLong() * MATERIAL_BYTES
            recordsOut.putInt(r.originX)
            recordsOut.putInt(r.originY)
            recordsOut.putInt(r.originZ)
            recordsOut.putInt(r.layer)
            recordsOut.putLong(verticesOut.position().toLong())
            recordsOut.putLong(indicesOut.position().toLong())
            recordsOut.putInt(r.vertexCount)
            recordsOut.putInt(r.indexCount)
            recordsOut.putInt(r.indexStride or (r.flags shl 8))
            recordsOut.putInt(r.geometryVersion)
            // 材质偏移以 quad 为单位（shader 里 `RtQuadMaterial` 自己乘 2），不是字节偏移。
            recordsOut.putInt(quadsOut.position() / MATERIAL_BYTES)
            recordsOut.putInt(r.quadCount)
            recordsOut.putLong(r.sectionNode)
            appendSlice(vertices, r.vertexOffset, vertexBytes, verticesOut)
            appendSlice(indices, r.indexOffset, indexBytes, indicesOut)
            appendSlice(materials, r.materialOffset * MATERIAL_BYTES, quadBytes, quadsOut)
            triangles += r.triangles
        }
        if (recordsOut.position() == 0) {
            return null
        }
        recordsOut.flip()
        verticesOut.flip()
        indicesOut.flip()
        quadsOut.flip()
        return MirrorSlice(recordsOut, verticesOut, indicesOut, quadsOut, chosen.size, triangles)
    }

    private fun appendSlice(src: ByteBuffer, offset: Long, length: Long, dst: ByteBuffer) {
        if (length <= 0L || offset < 0L) return
        val view = src.duplicate()
        val end = offset + length
        if (end > view.capacity() || end > view.limit()) {
            // 池被重置过（记录里的偏移作废）：这一条跳过，绝不因为拷贝越界把渲染线程打死。
            return
        }
        view.position(offset.toInt())
        view.limit(end.toInt())
        val room = minOf(dst.remaining(), length.toInt())
        if (room < length.toInt()) {
            view.limit(offset.toInt() + room)
        }
        dst.put(view)
    }

    /** 逐层三角形数与顶点数的一行摘要，验收时与 `section geometry` 行对账。 */
    @JvmStatic
    fun layerSummary(): String {
        val byLayer = IntArray(4)
        val verts = LongArray(4)
        for (r in records.values) {
            byLayer[r.layer] += r.triangles
            verts[r.layer] += r.vertexCount.toLong()
        }
        return "rt geometry pool layers: solid=${byLayer[0]} cutout=${byLayer[1]} " +
            "translucent=${byLayer[2]} other=${byLayer[3]} | v solid=${verts[0]} cutout=${verts[1]}"
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        if (records.isNotEmpty()) {
            resets.incrementAndGet()
        }
        full = false
        hidden.set(0)
        revision.incrementAndGet()
        records.clear()
        resetPools()
    }

    private fun resetPools() {
        revision.incrementAndGet()
        vertices = ByteBuffer.allocate(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
        indices = ByteBuffer.allocate(1 shl 18).order(ByteOrder.LITTLE_ENDIAN)
        materials = ByteBuffer.allocate(1 shl 18).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** 逐 quad 材质索引写进材质池；`vertex` 是原版缓冲，视图起点由它的 position 给出。 */
    private fun appendMaterials(vertex: ByteBuffer, quadCount: Int) {
        if (quadCount <= 0) {
            return
        }
        val ids = D3D12RtMaterialTable.quadMaterials(vertex, vertex.position(), quadCount)
        for (id in ids) {
            materials.putShort(id)
        }
    }

    private fun grow(buffer: ByteBuffer, needed: Int, cap: Long): ByteBuffer {
        val target = buffer.position().toLong() + needed
        if (target <= buffer.capacity()) {
            return buffer
        }
        var capacity = buffer.capacity().toLong()
        while (capacity < target) {
            capacity = capacity shl 1
        }
        val bigger = ByteBuffer.allocate(capacity.coerceAtMost(cap).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()
        bigger.put(buffer)
        return bigger
    }

    private fun append(target: ByteBuffer, source: ByteBuffer) {
        target.put(source.duplicate())
    }

    /** 原版共享顺序 quad 缓冲的模式：`(4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3)`。 */
    private fun writeSequentialQuadIndices(target: ByteBuffer, quadCount: Int, stride: Int) {
        val pattern = IntArray(QUAD_INDICES)
        for (q in 0 until quadCount) {
            val base = q * QUAD_VERTICES
            pattern[0] = base
            pattern[1] = base + 1
            pattern[2] = base + 2
            pattern[3] = base
            pattern[4] = base + 2
            pattern[5] = base + 3
            for (value in pattern) {
                if (stride == 4) target.putInt(value) else target.putShort(value.toShort())
            }
        }
    }
}


