package io.github.dxegp.experimental.raytracing

import java.nio.ByteBuffer
import java.nio.ByteOrder

class D3D12RtLightingChanges {
    enum class Reason(val mask: Int) {
        INPUT(1), SOURCE(2), RESOURCES(4), COMPILE_NOTIFICATION(8), CAPTURE_CHANGED(16), WORLD(32), OVERFLOW(64),
    }

    data class Region(val sectionNode: Long, val bounds: D3D12RtGeometryPool.OutputBounds?)

    data class Change(val sectionNode: Long, val firstRevision: Long, val revision: Long,
                      val reasons: Int, val bounds: D3D12RtGeometryPool.OutputBounds?)

    class Batch internal constructor(val epoch: Int, val afterRevision: Long, val revision: Long,
                                     val fullDomainReasons: Int, changes: List<Change>) {
        val changes: List<Change> = java.util.List.copyOf(changes)
        val fullDomain: Boolean get() = fullDomainReasons != 0 || changes.any { it.bounds == null }
        val empty: Boolean get() = fullDomainReasons == 0 && changes.isEmpty()

        fun encode(liveRevision: Long): ByteBuffer = ByteBuffer.allocate(64 + changes.size * 64)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x434C5452).putInt(1).putLong(epoch.toLong()).putLong(liveRevision)
                putLong(afterRevision).putLong(revision).putInt(fullDomainReasons).putInt(changes.size)
                putLong(0L).putLong(0L)
                for (change in changes) {
                    putLong(change.sectionNode).putLong(change.firstRevision).putLong(change.revision)
                    putInt(change.reasons).putInt(if (change.bounds == null) 0 else 1)
                    putFloat(change.bounds?.minimumX ?: 0f).putFloat(change.bounds?.minimumY ?: 0f)
                        .putFloat(change.bounds?.minimumZ ?: 0f).putFloat(change.bounds?.maximumX ?: 0f)
                        .putFloat(change.bounds?.maximumY ?: 0f).putFloat(change.bounds?.maximumZ ?: 0f)
                    putLong(0L)
                }
                flip()
            }.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }

    private val changes = ArrayDeque<Change>()
    private var epoch = 0
    private var lostThrough = 0L
    private val fullDomainRevisions = LongArray(Reason.entries.size)
    var revision = 0L
        private set

    fun reset(epoch: Int) {
        this.epoch = epoch
        changes.clear()
        revision++
        lostThrough = 0L
        fullDomainRevisions.fill(0L)
        fullDomainRevisions[Reason.WORLD.ordinal] = revision
    }

    fun record(reason: Reason, regions: Iterable<Region>) {
        val incoming = linkedMapOf<Long, D3D12RtGeometryPool.OutputBounds?>()
        for (region in regions) {
            incoming[region.sectionNode] = if (incoming.containsKey(region.sectionNode))
                union(incoming[region.sectionNode], region.bounds) else validBounds(region.bounds)
            if (incoming.size > LIMIT) {
                recordFullDomain(reason.mask or Reason.OVERFLOW.mask)
                return
            }
        }
        if (incoming.isEmpty()) return
        revision++
        for ([node, bounds] in incoming) {
            changes.addLast(Change(node, revision, revision, reason.mask, bounds))
            if (changes.size > LIMIT) {
                val removed = changes.removeFirst()
                lostThrough = maxOf(lostThrough, removed.revision)
            }
        }
    }

    fun recordFullDomain(reasons: Int) {
        require(reasons != 0 && reasons and KNOWN_REASONS.inv() == 0)
        revision++
        for (reason in Reason.entries) {
            if (reasons and reason.mask != 0) fullDomainRevisions[reason.ordinal] = revision
        }
    }

    fun since(epoch: Int, afterRevision: Long): Batch {
        require(afterRevision in 0..revision)
        var reasons = 0
        for (reason in Reason.entries) {
            if (afterRevision < fullDomainRevisions[reason.ordinal]) reasons = reasons or reason.mask
        }
        if (epoch != this.epoch) reasons = reasons or Reason.WORLD.mask
        if (afterRevision < lostThrough) reasons = reasons or Reason.OVERFLOW.mask
        val regions = linkedMapOf<Long, Change>()
        for (change in changes) {
            if (change.revision <= afterRevision) continue
            val previous = regions[change.sectionNode]
            regions[change.sectionNode] = if (previous == null) change else
                Change(change.sectionNode, previous.firstRevision, change.revision,
                    previous.reasons or change.reasons, union(previous.bounds, change.bounds))
        }
        return Batch(this.epoch, afterRevision, revision, reasons, regions.values.toList())
    }

    private fun validBounds(bounds: D3D12RtGeometryPool.OutputBounds?): D3D12RtGeometryPool.OutputBounds? =
        bounds?.takeIf {
            it.minimumX.isFinite() && it.minimumY.isFinite() && it.minimumZ.isFinite() &&
                it.maximumX.isFinite() && it.maximumY.isFinite() && it.maximumZ.isFinite() &&
                it.minimumX <= it.maximumX && it.minimumY <= it.maximumY && it.minimumZ <= it.maximumZ
        }

    private fun union(first: D3D12RtGeometryPool.OutputBounds?, second: D3D12RtGeometryPool.OutputBounds?):
        D3D12RtGeometryPool.OutputBounds? {
        val left = validBounds(first) ?: return null
        val right = validBounds(second) ?: return null
        return D3D12RtGeometryPool.OutputBounds(minOf(left.minimumX, right.minimumX),
            minOf(left.minimumY, right.minimumY), minOf(left.minimumZ, right.minimumZ),
            maxOf(left.maximumX, right.maximumX), maxOf(left.maximumY, right.maximumY), maxOf(left.maximumZ, right.maximumZ))
    }

    private companion object {
        const val LIMIT = 256
        const val KNOWN_REASONS = 127
    }
}
