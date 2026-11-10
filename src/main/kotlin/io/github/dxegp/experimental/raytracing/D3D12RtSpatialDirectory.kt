package io.github.dxegp.experimental.raytracing

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import it.unimi.dsi.fastutil.longs.LongSets
import net.minecraft.core.SectionPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor

object D3D12RtSpatialDirectory {
    const val PAGE_SIDE = 8
    const val MAX_PAGES = 512
    const val PAGE_BYTES = PAGE_SIDE * PAGE_SIDE * PAGE_SIDE * 4
    const val MAX_BYTES = 64 + MAX_PAGES * (16 + PAGE_BYTES)
    const val MAX_CACHED_DEPENDENCIES = 131072

    data class Page(val column: Int, val row: Int, val slice: Int)

    data class Region(val minimumX: Int, val minimumY: Int, val minimumZ: Int,
                      val sizeX: Int, val sizeY: Int, val sizeZ: Int,
                      val focus: Page)

    data class Update(val requestedPages: Int, val recomputedPages: Int, val reusedPages: Int,
                      val cachedPages: Int, val cachedDependencies: Int, val encodedPageBytes: Int,
                      val clippedPages: Int, val payloadBytes: Int, val buildNanos: Long)

    internal data class Build(val bytes: ByteBuffer, val update: Update)

    internal class Targets private constructor(internal val members: LongSet) : Set<Long> by members {
        override fun equals(other: Any?): Boolean = this === other || members == other
        override fun hashCode(): Int = members.hashCode()

        companion object {
            fun freeze(members: Set<Long>): Targets = Targets(LongSets.unmodifiable(LongOpenHashSet(members)))
        }
    }

    class Cache {
        private class Content(val data: ByteArray, val populated: Boolean)
        private data class Entry(val dependencies: Int, val content: Content)
        private val entries = linkedMapOf<Page, Entry>()
        private var dependencyCount = 0
        private var epoch: Int? = null
        private var universe: D3D12RtSourceUniverse.Snapshot? = null
        private var certifiable = false
        private var previousSources: List<D3D12RtSpatialSources.Source>? = null
        private var previousTargets: LongSet? = null

        fun clear() {
            entries.clear()
            dependencyCount = 0
            epoch = null
            universe = null
            certifiable = false
            previousSources = null
            previousTargets = null
        }

        private fun invalidatePage(page: Page) {
            entries.remove(page)?.let { dependencyCount -= it.dependencies }
        }

        private fun invalidateSource(node: Long) {
            if (entries.isEmpty()) return
            val bounds = universe?.bounds
            if (!certifiable || bounds == null) {
                invalidatePage(Page(SectionPos.x(node).floorDiv(PAGE_SIDE), SectionPos.y(node).floorDiv(PAGE_SIDE),
                    SectionPos.z(node).floorDiv(PAGE_SIDE)))
                return
            }
            fun lower(origin: Int, minimum: Float): Int =
                (origin.toLong() + ceil(minimum.toDouble() / 16.0).coerceIn(-8388608.0, 8388608.0).toLong() - 1L)
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt().floorDiv(PAGE_SIDE)
            fun upper(origin: Int, maximum: Float): Int =
                (origin.toLong() + floor(maximum.toDouble() / 16.0).coerceIn(-8388608.0, 8388608.0).toLong())
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt().floorDiv(PAGE_SIDE)
            val minimumX = lower(SectionPos.x(node), bounds.minimumX)
            val minimumY = lower(SectionPos.y(node), bounds.minimumY)
            val minimumZ = lower(SectionPos.z(node), bounds.minimumZ)
            val maximumX = upper(SectionPos.x(node), bounds.maximumX)
            val maximumY = upper(SectionPos.y(node), bounds.maximumY)
            val maximumZ = upper(SectionPos.z(node), bounds.maximumZ)
            val width = maximumX.toLong() - minimumX + 1L
            val height = maximumY.toLong() - minimumY + 1L
            val depth = maximumZ.toLong() - minimumZ + 1L
            if (width > entries.size || height > entries.size || depth > entries.size ||
                width * height > entries.size || width * height * depth > entries.size) {
                val iterator = entries.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val page = entry.key
                    if (page.column in minimumX..maximumX && page.row in minimumY..maximumY &&
                        page.slice in minimumZ..maximumZ) {
                        dependencyCount -= entry.value.dependencies
                        iterator.remove()
                    }
                }
            } else {
                for (slice in minimumZ..maximumZ) for (row in minimumY..maximumY) for (column in minimumX..maximumX)
                    invalidatePage(Page(column, row, slice))
            }
        }

        private fun updateSources(sources: D3D12RtSpatialSources.Snapshot, targets: Set<Long>) {
            val current = sources.orderedSources
            val previous = previousSources
            val currentTargets = if (current.size <= MAX_CACHED_DEPENDENCIES && targets.size <= MAX_CACHED_DEPENDENCIES &&
                (1 until current.size).none { current[it - 1].sectionNode == current[it].sectionNode })
                (if (targets is Targets) targets.members else LongOpenHashSet(targets)) else null
            if (previous == null || currentTargets == null) {
                entries.clear()
                dependencyCount = 0
            } else {
                val oldTargets = checkNotNull(previousTargets)
                var before = 0
                var after = 0
                while (before < previous.size && after < current.size) {
                    val old = previous[before]
                    val next = current[after]
                    when {
                        old.sectionNode < next.sectionNode -> {
                            invalidateSource(old.sectionNode)
                            before++
                        }
                        old.sectionNode > next.sectionNode -> {
                            invalidateSource(next.sectionNode)
                            after++
                        }
                        else -> {
                            if ((old !== next && !old.sameProof(next)) ||
                                (oldTargets !== currentTargets &&
                                    (old.sectionNode in oldTargets) != (next.sectionNode in currentTargets)))
                                invalidateSource(next.sectionNode)
                            before++
                            after++
                        }
                    }
                }
                while (before < previous.size) invalidateSource(previous[before++].sectionNode)
                while (after < current.size) invalidateSource(current[after++].sectionNode)
            }
            previousSources = if (currentTargets == null) null else current
            previousTargets = currentTargets
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
            updateSources(sources, targets)
            val retained = selected.map { page ->
                entries.remove(page)?.also { dependencyCount -= it.dependencies }
            }
            val missing = selected.filterIndexed { index, _ -> retained[index] == null }
            val candidates = if (missing.isEmpty()) emptyList() else sources.pageSources(missing)
            var candidateIndex = 0
            var recomputed = 0
            var reused = 0
            var clipped = 0
            val populated = selected.mapIndexedNotNull { index, page ->
                val previous = retained[index]
                val entry = if (previous != null) {
                    reused++
                    previous
                } else {
                    recomputed++
                    val current = candidates[candidateIndex++]
                    val states = sources.certifiedStates(page.column * PAGE_SIDE, page.row * PAGE_SIDE,
                        page.slice * PAGE_SIDE, PAGE_SIDE, targets, current)
                    for (source in current) {
                        val sectionX = SectionPos.x(source.sectionNode)
                        val sectionY = SectionPos.y(source.sectionNode)
                        val sectionZ = SectionPos.z(source.sectionNode)
                        if (sectionX.floorDiv(PAGE_SIDE) != page.column || sectionY.floorDiv(PAGE_SIDE) != page.row ||
                            sectionZ.floorDiv(PAGE_SIDE) != page.slice) continue
                        val cell = ((sectionZ.mod(PAGE_SIDE) * PAGE_SIDE + sectionY.mod(PAGE_SIDE)) * PAGE_SIDE +
                            sectionX.mod(PAGE_SIDE))
                        if (states[cell] !in 1..2 && states[cell] != D3D12RtSpatialSources.PROXY_COVERED)
                            states[cell] = source.state
                    }
                    val data = ByteArray(PAGE_BYTES)
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply { for (state in states) putInt(state) }
                    Entry(current.size, Content(data, states.any { it != 0 }))
                }
                if (entry.dependencies <= MAX_CACHED_DEPENDENCIES) {
                    entries[page] = entry
                    dependencyCount += entry.dependencies
                }
                while (entries.size > MAX_PAGES || dependencyCount > MAX_CACHED_DEPENDENCIES) {
                    val oldest = entries.entries.iterator().next()
                    dependencyCount -= oldest.value.dependencies
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
