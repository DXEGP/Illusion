package io.github.dxegp.render.compilation

import io.github.dxegp.d3d12.shader.CompilationRevokedException

class VersionedPipelinePrewarm<Key, Value : AutoCloseable> : AutoCloseable {
    private val lock = Any()
    private var currentGeneration = 0L
    private var closed = false

    val generation: Long get() = synchronized(lock) {
        check(!closed) { "Prewarm owner is closed" }
        currentGeneration
    }

    fun invalidate(): Long = synchronized(lock) {
        check(!closed) { "Prewarm owner is closed" }
        currentGeneration = Math.incrementExact(currentGeneration)
        currentGeneration
    }

    fun prepare(
        expectedGeneration: Long,
        inventory: Iterable<Key>,
        compile: (Key) -> Value,
        validate: (Value) -> Boolean,
    ): Prepared<Key, Value> {
        val staged = LinkedHashMap<Key, Value>()
        try {
            checkGeneration(expectedGeneration)
            for (key in inventory) {
                check(!staged.containsKey(key)) { "Duplicate pipeline in prewarm inventory: $key" }
                checkGeneration(expectedGeneration)
                val pipeline = compile(key)
                staged[key] = pipeline
                check(validate(pipeline)) { "Pipeline prewarm failed: $key" }
                checkGeneration(expectedGeneration)
            }
            return Prepared(this, expectedGeneration, java.util.Collections.unmodifiableMap(staged))
        } catch (failure: Throwable) {
            closeAll(staged.values, failure)
            throw failure
        }
    }

    fun withPrepared(prepared: Prepared<Key, Value>, publication: (Map<Key, Value>) -> Unit) {
        synchronized(lock) {
            check(prepared.owner === this) { "Prewarm belongs to another owner" }
            checkGeneration(prepared.generation)
            synchronized(prepared) {
                val pipelines = checkNotNull(prepared.staged) { "Prewarm is already consumed" }
                publication(pipelines)
            }
        }
    }

    private fun checkGeneration(expected: Long) = synchronized(lock) {
        if (closed || expected != currentGeneration) throw CompilationRevokedException()
    }

    override fun close() = synchronized(lock) {
        closed = true
    }

    class Prepared<Key, Value : AutoCloseable> internal constructor(
        internal val owner: VersionedPipelinePrewarm<Key, Value>,
        internal val generation: Long,
        internal var staged: Map<Key, Value>?,
    ) : AutoCloseable {
        internal fun transferOwnership() {
            staged = null
        }

        override fun close() {
            val abandoned = synchronized(this) {
                val pipelines = staged ?: return
                staged = null
                pipelines.values.toList()
            }
            closeAll(abandoned)
        }
    }

    private companion object {
        fun closeAll(values: Collection<AutoCloseable>, initialFailure: Throwable? = null) {
            var failure = initialFailure
            values.forEach { value ->
                try {
                    value.close()
                } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            if (initialFailure == null) failure?.let { throw it }
        }
    }
}
