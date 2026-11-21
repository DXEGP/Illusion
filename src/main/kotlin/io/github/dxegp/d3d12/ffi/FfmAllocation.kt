package io.github.dxegp.d3d12.ffi

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Empty-array ABI policy for pointer-plus-count fields. */
internal enum class FfmEmptyAllocation {
    NULL,
    NON_NULL,
}

/**
 * Allocate an FFM array with the native element stride and alignment.
 * Native contracts normally use a NULL pointer when the count is zero; a few legacy entry points still require a
 * non-null address, so that choice is explicit at the call site. The byte count is checked before allocation; no
 * caller may silently truncate a variable-size target.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun Arena.allocateFfmArray(
    count: Int,
    element: MemoryLayout,
    emptyPolicy: FfmEmptyAllocation = FfmEmptyAllocation.NULL,
): MemorySegment {
    require(count >= 0) { "FFM array count must be non-negative" }
    require(element.byteSize() > 0L) { "FFM array element layout must have a positive size" }
    require(element.byteAlignment() > 0L) { "FFM array element layout must have a positive alignment" }
    val bytes = Math.multiplyExact(count.toLong(), element.byteSize())
    if (count == 0 && emptyPolicy == FfmEmptyAllocation.NULL) return MemorySegment.NULL
    return allocate(bytes.coerceAtLeast(1L), element.byteAlignment())
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Arena.allocateBytes(data: ByteArray): MemorySegment {
    val segment = allocate(data.size.toLong().coerceAtLeast(1L), ValueLayout.JAVA_BYTE.byteAlignment())
    MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, 0, data.size)
    return segment
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Arena.allocateInts(data: IntArray): MemorySegment {
    val segment = allocateFfmArray(data.size, ValueLayout.JAVA_INT)
    if (data.isNotEmpty()) MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_INT, 0, data.size)
    return segment
}
