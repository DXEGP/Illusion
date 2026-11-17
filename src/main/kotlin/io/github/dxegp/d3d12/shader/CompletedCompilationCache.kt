package io.github.dxegp.d3d12.shader

class CompilationRevokedException : IllegalStateException("Compilation generation has been revoked")

internal class CompletedCompilationCache<Value : Any>(
    private val maxEntries: Int = 512,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val sizeOf: (Value) -> Long,
) : AutoCloseable {
    private data class Entry<Value>(val value: Value, val bytes: Long)

    private val lock = Any()
    private val completed = LinkedHashMap<String, Entry<Value>>(16, 0.75f, true)
    private var retainedBytes = 0L
    private var currentGeneration = 0L
    private var closed = false

    init {
        require(maxEntries > 0 && maxBytes > 0)
    }

    val generation: Long get() = synchronized(lock) {
        check(!closed) { "Compiler is closed" }
        currentGeneration
    }

    fun checkGeneration(expected: Long) = synchronized(lock) {
        if (closed || expected != currentGeneration) throw CompilationRevokedException()
    }

    fun getOrCompile(expected: Long, key: String, compile: () -> Value): Value {
        synchronized(lock) {
            checkGeneration(expected)
            completed[key]?.let { return it.value }
        }
        val result = compile()
        val bytes = sizeOf(result).coerceAtLeast(1) + key.length * 2L + 96
        return synchronized(lock) {
            checkGeneration(expected)
            completed[key]?.let { return@synchronized it.value }
            if (bytes <= maxBytes) {
                while (completed.size >= maxEntries || retainedBytes > maxBytes - bytes) {
                    val oldest = completed.entries.iterator()
                    retainedBytes -= oldest.next().value.bytes
                    oldest.remove()
                }
                completed[key] = Entry(result, bytes)
                retainedBytes += bytes
            }
            result
        }
    }

    fun invalidate(): Long = synchronized(lock) {
        check(!closed) { "Compiler is closed" }
        currentGeneration = Math.incrementExact(currentGeneration)
        completed.clear()
        retainedBytes = 0
        currentGeneration
    }

    override fun close() = synchronized(lock) {
        closed = true
        completed.clear()
        retainedBytes = 0
    }
}
