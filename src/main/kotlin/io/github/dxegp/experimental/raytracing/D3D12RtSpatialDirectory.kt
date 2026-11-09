package io.github.dxegp.experimental.raytracing

import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor

object D3D12RtSpatialDirectory {
    const val PAGE_SIDE = 8
    const val MAX_PAGES = 512
    const val PAGE_BYTES = PAGE_SIDE * PAGE_SIDE * PAGE_SIDE * 4
    const val MAX_BYTES = 64 + MAX_PAGES * (16 + PAGE_BYTES)
    const val MAX_CACHED_DEPENDENCIES = 32768

    data class Page(val column: Int, val row: Int, val slice: Int)

    data class Region(val minimumX: Int, val minimumY: Int, val minimumZ: Int,
                      val sizeX: Int, val sizeY: Int, val sizeZ: Int,
                      val focus: Page)

    data class Update(val requestedPages: Int, val recomputedPages: Int, val reusedPages: Int,
                      val cachedPages: Int, val cachedDependencies: Int, val encodedPageBytes: Int,
                      val clippedPages: Int, val payloadBytes: Int, val buildNanos: Long)

    internal data class Build(val bytes: ByteBuffer, val update: Update)

    class Cache {
        private data class Dependency(val source: D3D12RtSpatialSources.Source, val targeted: Boolean) {
            fun matches(other: Dependency): Boolean = targeted == other.targeted && source.sameProof(other.source)
        }

        private class Content(val data: ByteArray, val populated: Boolean)
        private data class Entry(val dependencies: List<Dependency>, val content: Content)
        private val entries = linkedMapOf<Page, Entry>()
        private var dependencyCount = 0
        private var epoch: Int? = null
        private var universe: D3D12RtSourceUniverse.Snapshot? = null
        private var certifiable = false

        fun clear() {
            entries.clear()
            dependencyCount = 0
            epoch = null
            universe = null
            certifiable = false
        }

        internal fun build(region: Region, sources: D3D12RtSpatialSources.Snapshot, targets: Set<Long>): Build {
            val started = System.nanoTime()
            if (epoch != sources.epoch || universe !== sources.universe || certifiable != sources.certifiable) {
                clear()
                epoch = sources.epoch
                universe = sources.universe
                certifiable = sources.certifiable
            }
            val selected = pages(region)
            val candidates = sources.pageSources(selected)
            val dependencies = IdentityHashMap<D3D12RtSpatialSources.Source, Dependency>(
                minOf(sources.sources.size, candidates.sumOf { it.size }))
            val retained = selected.map { page ->
                entries.remove(page)?.also { dependencyCount -= it.dependencies.size }
            }
            var recomputed = 0
            var reused = 0
            var clipped = 0
            val populated = selected.mapIndexedNotNull { index, page ->
                val required = candidates[index].map { source -> dependencies.getOrPut(source) {
                    Dependency(source, source.layers.isNotEmpty() && source.sectionNode in targets)
                } }
                val previous = retained[index]
                val entry = if (previous != null && previous.dependencies.size == required.size &&
                    required.indices.all { required[it].matches(previous.dependencies[it]) }) {
                    reused++
                    previous
                } else {
                    recomputed++
                    val states = sources.certifiedStates(page.column * PAGE_SIDE, page.row * PAGE_SIDE,
                        page.slice * PAGE_SIDE, PAGE_SIDE, targets, candidates[index])
                    for (source in candidates[index]) {
                        val sectionX = SectionPos.x(source.sectionNode)
                        val sectionY = SectionPos.y(source.sectionNode)
                        val sectionZ = SectionPos.z(source.sectionNode)
                        if (sectionX.floorDiv(PAGE_SIDE) != page.column || sectionY.floorDiv(PAGE_SIDE) != page.row ||
                            sectionZ.floorDiv(PAGE_SIDE) != page.slice) continue
                        val cell = ((sectionZ.mod(PAGE_SIDE) * PAGE_SIDE + sectionY.mod(PAGE_SIDE)) * PAGE_SIDE +
                            sectionX.mod(PAGE_SIDE))
                        if (states[cell] !in 1..2) states[cell] = source.state
                    }
                    val data = ByteArray(PAGE_BYTES)
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply { for (state in states) putInt(state) }
                    Entry(required, Content(data, states.any { it != 0 }))
                }
                if (entry.dependencies.size <= MAX_CACHED_DEPENDENCIES) {
                    entries[page] = entry
                    dependencyCount += entry.dependencies.size
                }
                while (entries.size > MAX_PAGES || dependencyCount > MAX_CACHED_DEPENDENCIES) {
                    val oldest = entries.entries.iterator().next()
                    dependencyCount -= oldest.value.dependencies.size
                    entries.remove(oldest.key)
                }
                val content = entry.content
                if (!content.populated) return@mapIndexedNotNull null
                val minimumX = page.column * PAGE_SIDE
                val minimumY = page.row * PAGE_SIDE
                val minimumZ = page.slice * PAGE_SIDE
                if (minimumX >= region.minimumX && minimumY >= region.minimumY && minimumZ >= region.minimumZ &&
                    minimumX + PAGE_SIDE <= region.minimumX + region.sizeX &&
                    minimumY + PAGE_SIDE <= region.minimumY + region.sizeY &&
                    minimumZ + PAGE_SIDE <= region.minimumZ + region.sizeZ) return@mapIndexedNotNull page to content.data
                clipped++
                val data = content.data.copyOf()
                val states = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                for (slice in 0 until PAGE_SIDE) for (row in 0 until PAGE_SIDE) for (column in 0 until PAGE_SIDE) {
                    if (minimumX + column !in region.minimumX until region.minimumX + region.sizeX ||
                        minimumY + row !in region.minimumY until region.minimumY + region.sizeY ||
                        minimumZ + slice !in region.minimumZ until region.minimumZ + region.sizeZ)
                        states.putInt(((slice * PAGE_SIDE + row) * PAGE_SIDE + column) * 4, 0)
                }
                if (data.any { it != 0.toByte() }) page to data else null
            }
            val payload = ByteBuffer.allocate(64 + populated.size * (16 + PAGE_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(0x53505452).putInt(2)
            payload.putInt(region.minimumX).putInt(region.minimumY).putInt(region.minimumZ)
            payload.putInt(region.sizeX).putInt(region.sizeY).putInt(region.sizeZ)
            repeat(6) { payload.putInt(0) }
            payload.putInt(sources.spatialFlags).putInt(populated.size)
            populated.forEachIndexed { index, entry ->
                val page = entry.first
                payload.putInt(page.column).putInt(page.row).putInt(page.slice)
                    .putInt(64 + populated.size * 16 + index * PAGE_BYTES)
            }
            for (entry in populated) payload.put(entry.second)
            payload.flip()
            return Build(payload.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
                Update(selected.size, recomputed, reused, entries.size, dependencyCount, recomputed * PAGE_BYTES,
                    clipped, payload.limit(), System.nanoTime() - started))
        }
    }

    fun region(centerX: Float, centerY: Float, centerZ: Float, radius: Float): Region {
        require(centerX.isFinite() && centerY.isFinite() && centerZ.isFinite() && radius.isFinite() && radius >= 0f)
        val reach = ceil(radius.toDouble() / 16.0).toLong().coerceIn(0L, 1L shl 22)
        fun extent(limit: Int): Int = (reach * 2L + 1L).coerceAtMost(limit * 2L).toInt()
        fun section(center: Float, limit: Int): Int =
            floor(center.toDouble() / 16.0).coerceIn(-limit.toDouble(), limit - 1.0).toInt()
        val sizeX = extent(1 shl 21)
        val sizeY = extent(1 shl 19)
        val sizeZ = sizeX
        val sectionX = section(centerX, 1 shl 21)
        val sectionY = section(centerY, 1 shl 19)
        val sectionZ = section(centerZ, 1 shl 21)
        return Region((sectionX.toLong() - reach).coerceIn(-(1L shl 21), (1L shl 21) - sizeX).toInt(),
            (sectionY.toLong() - reach).coerceIn(-(1L shl 19), (1L shl 19) - sizeY).toInt(),
            (sectionZ.toLong() - reach).coerceIn(-(1L shl 21), (1L shl 21) - sizeZ).toInt(),
            sizeX, sizeY, sizeZ, Page(sectionX.floorDiv(PAGE_SIDE), sectionY.floorDiv(PAGE_SIDE),
                sectionZ.floorDiv(PAGE_SIDE)))
    }

    internal fun pages(region: Region): List<Page> {
        val minimum = Page(region.minimumX.floorDiv(PAGE_SIDE), region.minimumY.floorDiv(PAGE_SIDE),
            region.minimumZ.floorDiv(PAGE_SIDE))
        val maximum = Page((region.minimumX + region.sizeX - 1).floorDiv(PAGE_SIDE),
            (region.minimumY + region.sizeY - 1).floorDiv(PAGE_SIDE),
            (region.minimumZ + region.sizeZ - 1).floorDiv(PAGE_SIDE))
        fun distance(page: Page): Long {
            val deltaX = page.column.toLong() - region.focus.column
            val deltaY = page.row.toLong() - region.focus.row
            val deltaZ = page.slice.toLong() - region.focus.slice
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        }
        val pending = PriorityQueue(compareBy<Page> { distance(it) }.thenBy { it.slice }
            .thenBy { it.row }.thenBy { it.column })
        val visited = hashSetOf<Page>()
        fun enqueue(page: Page) {
            if (page.column in minimum.column..maximum.column && page.row in minimum.row..maximum.row &&
                page.slice in minimum.slice..maximum.slice && visited.add(page)) pending += page
        }
        enqueue(region.focus)
        return buildList {
            while (size < MAX_PAGES && pending.isNotEmpty()) {
                val page = pending.remove()
                add(page)
                enqueue(page.copy(column = page.column - 1))
                enqueue(page.copy(column = page.column + 1))
                enqueue(page.copy(row = page.row - 1))
                enqueue(page.copy(row = page.row + 1))
                enqueue(page.copy(slice = page.slice - 1))
                enqueue(page.copy(slice = page.slice + 1))
            }
        }.sortedWith(compareBy<Page> { it.slice }.thenBy { it.row }.thenBy { it.column })
    }

}
