package io.github.dxegp.render.terrain.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * meshlet 生成器（CPU 纯函数，不碰 GPU）：切分 + 量化 AABB + 方向掩码。
 * 顶点是 `DefaultVertexFormat.BLOCK`（28 B，无法线），朝向只能由绕序现算后静态写进 [Meshlet.directionMask]。
 * 默认每 [QUADS_PER_MESHLET] 个 Quad 一个 meshlet；AABB 量化 `[0,16] -> [0,255]`（x15.9375）。
 * 见 docs/mesh&vrs/README.md §2.2。
 */
object D3D12MeshletBuilder {

    const val VERTEX_STRIDE = 28

    /** 1 meshlet = 16 Quad = 64 顶点 / 32 三角形。 */
    const val QUADS_PER_MESHLET = 16

    /** 一个 Quad 的顶点数：BufferBuilder 固定 4 顶点一个面。 */
    const val VERTICES_PER_QUAD = 4

    /** 每条 `MeshletDescriptor` 的字节数（见 [Meshlet.pack]）。 */
    const val MESHLET_DESC_BYTES = 16

    const val DIR_POS_X = 1 shl 0
    const val DIR_NEG_X = 1 shl 1
    const val DIR_POS_Y = 1 shl 2
    const val DIR_NEG_Y = 1 shl 3
    const val DIR_POS_Z = 1 shl 4
    const val DIR_NEG_Z = 1 shl 5

    /** bit 6：交叉面 / 双面材质（草、花、斜面）—— 不能做背面剔除。 */
    const val DIR_NON_AXIS = 1 shl 6

    const val BOUNDS_OUTSIDE_SECTION = 1 shl 7

    private const val AXIS_EPSILON = 1.0e-6f

    /** 量化系数：`[0,16] * 15.9375 = [0,255]`（16 -> 255 而非 256，正好装进 uint8）。 */
    private const val QUANTIZE = 15.9375f

    /** meshlet 元数据；打包布局为两个量化 AABB、localVertexOffset 和保留字段。 */
    data class Meshlet(
        /** 在 section 内的 quad 序号（不是索引号）。 */
        val firstQuad: Int,
        val quadCount: Int,
        val quantMinX: Int, val quantMinY: Int, val quantMinZ: Int,
        val quantMaxX: Int, val quantMaxY: Int, val quantMaxZ: Int,
        /** 该 meshlet 内出现过的方向位（多个 = 混合朝向）。 */
        val directionMask: Int,
    ) {
        val isPure: Boolean get() = Integer.bitCount(directionMask) == 1

        fun packedAabbMin(): Int =
            (quantMinX and 0xFF) or ((quantMinY and 0xFF) shl 8) or
                ((quantMinZ and 0xFF) shl 16) or ((quadCount and 0xFF) shl 24)

        fun packedAabbMax(): Int =
            (quantMaxX and 0xFF) or ((quantMaxY and 0xFF) shl 8) or
                ((quantMaxZ and 0xFF) shl 16) or ((directionMask and 0xFF) shl 24)

        /** 写一条 16 B 静态 descriptor；布局见 docs/mesh&vrs/README.md §2.5.3。 */
        fun pack(dst: ByteBuffer) {
            dst.putInt(packedAabbMin())
            dst.putInt(packedAabbMax())
            dst.putInt(firstQuad * VERTICES_PER_QUAD)
            dst.putInt(0)
        }
    }

    /** 一次切分的结果。 */
    data class Result(
        val quads: Int,
        val vertices: Int,
        val meshlets: List<Meshlet>,
        /** 逐 Quad 的方向直方图，下标 = 方向位序号 0..6（顺序同 [DIR_POS_X]..[DIR_NON_AXIS]）。 */
        val directionCounts: IntArray,
    ) {
        val meshletCount: Int get() = meshlets.size
        val pureMeshlets: Int get() = meshlets.count { it.isPure }
    }

    /**
     * 切一段 `BLOCK` 顶点流；`stride < 28` 或不足一个 Quad 时返回 null（调用方按"切不出"处理）。
     * `vertex` 的 `position..limit` 即有效数据。
     */
    fun build(vertex: ByteBuffer, stride: Int = VERTEX_STRIDE, quadsPerMeshlet: Int = QUADS_PER_MESHLET): Result? {
        require(quadsPerMeshlet in listOf(16, 32, 64))
        if (stride < VERTEX_STRIDE) return null
        val bytes = vertex.remaining()
        val vertices = bytes / stride
        val quads = vertices / VERTICES_PER_QUAD
        if (quads <= 0) return null

        val buf = vertex.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = buf.position()
        val directionCounts = IntArray(7)
        val meshlets = ArrayList<Meshlet>((quads + quadsPerMeshlet - 1) / quadsPerMeshlet)
        var first = 0
        while (first < quads) {
            val count = minOf(quadsPerMeshlet, quads - first)
            var mask = 0
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (quad in first until first + count) {
                val v0 = base + quad * VERTICES_PER_QUAD * stride
                val x0 = buf.getFloat(v0)
                val y0 = buf.getFloat(v0 + 4)
                val z0 = buf.getFloat(v0 + 8)
                val v1 = v0 + stride
                val v2 = v0 + 2 * stride
                // cross(p1-p0, p2-p0)：顶点里没法线，只能用绕序反推面朝向。
                val ax = buf.getFloat(v1) - x0
                val ay = buf.getFloat(v1 + 4) - y0
                val az = buf.getFloat(v1 + 8) - z0
                val bx = buf.getFloat(v2) - x0
                val by = buf.getFloat(v2 + 4) - y0
                val bz = buf.getFloat(v2 + 8) - z0
                val nx = ay * bz - az * by
                val ny = az * bx - ax * bz
                val nz = ax * by - ay * bx

                val direction = directionOf(nx, ny, nz)
                mask = mask or direction
                directionCounts[Integer.numberOfTrailingZeros(direction)]++
                for (v in 0 until VERTICES_PER_QUAD) {
                    val off = v0 + v * stride
                    val x = buf.getFloat(off)
                    val y = buf.getFloat(off + 4)
                    val z = buf.getFloat(off + 8)
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }
            }
            if (minX < 0f || minY < 0f || minZ < 0f || maxX > 16f || maxY > 16f || maxZ > 16f) {
                mask = mask or BOUNDS_OUTSIDE_SECTION
            }
            meshlets.add(
                Meshlet(
                    firstQuad = first,
                    quadCount = count,
                    quantMinX = quantizeMin(minX), quantMinY = quantizeMin(minY), quantMinZ = quantizeMin(minZ),
                    quantMaxX = quantizeMax(maxX), quantMaxY = quantizeMax(maxY), quantMaxZ = quantizeMax(maxZ),
                    directionMask = mask,
                )
            )
            first += count
        }
        return Result(quads = quads, vertices = vertices, meshlets = meshlets,
            directionCounts = directionCounts)
    }

    /** 六向剔除只接受轴对齐法线，其余面使用 [DIR_NON_AXIS]。 */
    private fun directionOf(nx: Float, ny: Float, nz: Float): Int {
        val ax = abs(nx)
        val ay = abs(ny)
        val az = abs(nz)
        val maxComponent = maxOf(ax, ay, az)
        if (!maxComponent.isFinite() || maxComponent < 1e-6f) return DIR_NON_AXIS
        val tolerance = maxComponent * AXIS_EPSILON
        return when {
            ay <= tolerance && az <= tolerance -> if (nx > 0f) DIR_POS_X else DIR_NEG_X
            ax <= tolerance && az <= tolerance -> if (ny > 0f) DIR_POS_Y else DIR_NEG_Y
            ax <= tolerance && ay <= tolerance -> if (nz > 0f) DIR_POS_Z else DIR_NEG_Z
            else -> DIR_NON_AXIS
        }
    }

    private fun quantizeMin(value: Float): Int = floor(value.toDouble() * QUANTIZE).toInt().coerceIn(0, 255)

    private fun quantizeMax(value: Float): Int = ceil(value.toDouble() * QUANTIZE).toInt().coerceIn(0, 255)

    /** 量化值的反解（GLSL 侧应写成 `q / 15.9375`，与这里互为逆运算）。 */
    fun dequantize(q: Int): Float = (q and 0xFF) / QUANTIZE
}
