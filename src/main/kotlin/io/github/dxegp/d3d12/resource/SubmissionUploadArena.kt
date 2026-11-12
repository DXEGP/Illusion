package io.github.dxegp.d3d12.resource

import java.util.ArrayDeque

internal class SubmissionRetirement<Resource>(
    private val isComplete: (Long) -> Boolean,
    private val retire: (Resource) -> Unit
) {
    private val recording = mutableListOf<Resource>()
    private val submitted = ArrayDeque<Pair<Long, List<Resource>>>()
    private var lastFence = 0L

    fun retain(resource: Resource) {
        recording.add(resource)
    }

    fun seal(fence: Long) {
        require(fence > lastFence) { "Submission fence must advance within its context" }
        lastFence = fence
        if (recording.isNotEmpty()) {
            submitted.addLast(fence to recording.toList())
            recording.clear()
        }
        collect()
    }

    fun collect() {
        while (submitted.isNotEmpty()) {
            val oldest = submitted.first()
            if (!isComplete(oldest.first)) return
            for (resource in oldest.second) retire(resource)
            submitted.removeFirst()
        }
    }

    fun drainAfterCompletion() {
        while (submitted.isNotEmpty()) {
            for (resource in submitted.removeFirst().second) retire(resource)
        }
        for (resource in recording) retire(resource)
        recording.clear()
    }
}

internal class SubmissionUploadArena<Resource>(
    private val defaultPageSize: Long,
    private val maxPooledPageSize: Long,
    private val maxPoolBytes: Long,
    private val create: (Int, Long) -> Resource,
    private val release: (Resource) -> Unit,
    isComplete: (Long) -> Boolean
) {
    init {
        require(defaultPageSize > 0L) { "Upload page size must be positive" }
        require(maxPooledPageSize >= 0L) { "Maximum pooled page size must be non-negative" }
        require(maxPoolBytes >= 0L) { "Maximum pooled bytes must be non-negative" }
    }

    internal data class Allocation<Resource>(val resource: Resource, val offset: Long)
    private data class Page<Resource>(val resource: Resource, val capacity: Long, val usage: Int,
        var used: Long = 0L, var reusable: Boolean = true)

    private val available = mutableListOf<Page<Resource>>()
    private val active = mutableMapOf<Int, Page<Resource>>()
    private var pooledBytes = 0L
    private var closing = false
    private val retirement = SubmissionRetirement<Page<Resource>>(isComplete) { page ->
        if (!closing && page.reusable && page.capacity <= maxPooledPageSize
            && page.capacity <= maxPoolBytes - pooledBytes) {
            page.used = 0L
            available.add(page)
            pooledBytes += page.capacity
        } else {
            release(page.resource)
        }
    }

    fun allocate(size: Long, alignment: Long, usage: Int, minimumAllocation: Long = 0L): Allocation<Resource> {
        require(size in 0L..Int.MAX_VALUE.toLong()) { "Upload allocation must fit a ByteBuffer" }
        require(alignment > 0L && minimumAllocation >= 0L) { "Invalid upload allocation alignment or minimum" }
        check(!closing) { "Upload arena is closed" }
        retirement.collect()
        var page = active[usage]
        var offset = if (page == null) 0L else alignUp(page.used, alignment)
        if (page == null || offset > page.capacity || size > page.capacity - offset) {
            val capacity = alignUp(maxOf(size, minimumAllocation, defaultPageSize), alignment)
            val reusableIndex = available.indexOfFirst { it.usage == usage && it.capacity >= capacity }
            page = if (reusableIndex >= 0) available.removeAt(reusableIndex).also {
                pooledBytes -= it.capacity
            } else Page(create(usage, capacity), capacity, usage)
            active[usage] = page
            retirement.retain(page)
            offset = 0L
        }
        page.used = Math.addExact(offset, size)
        return Allocation(page.resource, offset)
    }

    fun discard(resource: Resource) {
        val entry = active.entries.firstOrNull { it.value.resource === resource } ?: return
        entry.value.reusable = false
        active.remove(entry.key)
    }

    fun seal(fence: Long) {
        retirement.seal(fence)
        active.clear()
    }

    fun closeAfterCompletion() {
        if (closing) return
        closing = true
        retirement.drainAfterCompletion()
        for (page in available) release(page.resource)
        available.clear()
        active.clear()
        pooledBytes = 0L
    }

    companion object {
        fun alignUp(value: Long, alignment: Long): Long {
            require(value >= 0L && alignment > 0L) { "Invalid aligned range" }
            val remainder = value % alignment
            return if (remainder == 0L) value else Math.addExact(value, alignment - remainder)
        }

        fun commonAlignment(requested: Long, required: Long): Long {
            require(requested >= 0L && required > 0L) { "Invalid alignment" }
            val normalized = requested.coerceAtLeast(1L)
            var divisor = normalized
            var remainder = required
            while (remainder != 0L) {
                val next = divisor % remainder
                divisor = remainder
                remainder = next
            }
            return Math.multiplyExact(normalized / divisor, required)
        }
    }
}
