package io.github.dxegp.d3d12.ffi

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

internal class FfmScratch {
    private companion object {
        const val MAX_CACHED_BYTES: Long = 64L * 1024L
        const val MAX_CACHED_ALIGNMENT: Long = 256L
        const val INITIAL_CAPACITY: Long = 256L
    }

    private val segments = arrayOfNulls<MemorySegment>(4)
    private val alignments = LongArray(4) { 1L }

    private var scopeDepth = 0
    private var activeTemporary: Arena? = null

    fun requiresTemporary(bytes: Long, alignment: Long): Boolean {
        require(bytes >= 0L)
        require(alignment > 0L && alignment and (alignment - 1L) == 0L)
        return bytes > MAX_CACHED_BYTES || alignment > MAX_CACHED_ALIGNMENT
    }

    /**
     * Runs one synchronous FFM encoding/downcall scope.
     *
     * Cached slots are only used by the outermost scope. Nested calls receive a confined arena,
     * so a helper that performs another ABI call cannot overwrite an address still owned by its
     * caller. If the caller's estimate is smaller than a later target, a confined arena is
     * created lazily by [allocate] and remains valid until this scope returns.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun <Result> withScope(
        maximumBytes: Long,
        maximumAlignment: Long,
        crossinline action: (Arena?) -> Result,
    ): Result {
        val temporaryRequired = requiresTemporary(maximumBytes, maximumAlignment)
        val parentTemporary = activeTemporary
        val temporary = if (scopeDepth != 0 || temporaryRequired) {
            Arena.ofConfined()
        } else null
        activeTemporary = temporary
        scopeDepth++
        try {
            return action(temporary)
        } finally {
            val completedTemporary = activeTemporary
            activeTemporary = parentTemporary
            scopeDepth--
            completedTemporary?.close()
        }
    }

    fun allocate(index: Int, bytes: Long, alignment: Long, temporary: Arena?): MemorySegment {
        require(index in segments.indices)
        val transient = requiresTemporary(bytes, alignment)
        val need = bytes.coerceAtLeast(1L)
        val currentTemporary = temporary ?: activeTemporary
        if (currentTemporary != null) {
            return currentTemporary.allocate(need, alignment)
        }
        if (transient) {
            check(scopeDepth != 0) {
                "Large or highly aligned FFM allocation requires a call scope"
            }
            val arena = activeTemporary ?: Arena.ofConfined().also { activeTemporary = it }
            return arena.allocate(need, alignment)
        }
        val existing = segments[index]
        if (existing != null && existing.byteSize() >= need && alignments[index] >= alignment) {
            return existing.asSlice(0L, need)
        }
        var capacity = maxOf(INITIAL_CAPACITY, existing?.byteSize() ?: 0L)
        while (capacity < need) {
            require(capacity <= Long.MAX_VALUE / 2L) {
                "FFM scratch allocation exceeds the addressable size"
            }
            capacity *= 2L
        }
        val actualAlignment = maxOf(alignments[index], alignment)
        val created = Arena.ofAuto().allocate(capacity, actualAlignment)
        segments[index] = created
        alignments[index] = actualAlignment
        return created.asSlice(0L, need)
    }
}
