package io.github.dxegp.probes.compilation

import io.github.dxegp.d3d12.shader.CompilationRevokedException
import io.github.dxegp.d3d12.shader.CompletedCompilationCache
import io.github.dxegp.render.compilation.VersionedPipelinePrewarm

fun main() {
    failedCompilationIsRetryable()
    generationRevocationRejectsInFlightResults()
    stagedPrewarmKeepsOwnershipUntilCloseOrTransfer()
    println("PASS: compilation generation and prewarm ownership")
}

private fun failedCompilationIsRetryable() {
    var attempts = 0
    val cache = CompletedCompilationCache<String> { it.length.toLong() }
    try {
        val generation = cache.generation
        check(runCatching {
            cache.getOrCompile(generation, "shader") {
                attempts++
                error("expected transient failure")
            }
        }.isFailure)
        check(cache.getOrCompile(generation, "shader") { attempts++; "compiled" } == "compiled")
        check(cache.getOrCompile(generation, "shader") { error("cached value was not reused") } == "compiled")
        check(attempts == 2)
    } finally {
        cache.close()
    }
}

private fun generationRevocationRejectsInFlightResults() {
    val cache = CompletedCompilationCache<String> { it.length.toLong() }
    try {
        val generation = cache.generation
        val revoked = runCatching {
            cache.getOrCompile(generation, "reload") {
                cache.invalidate()
                "stale"
            }
        }.exceptionOrNull()
        check(revoked is CompilationRevokedException)
    } finally {
        cache.close()
    }
}

private fun stagedPrewarmKeepsOwnershipUntilCloseOrTransfer() {
    class Value : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    val owner = VersionedPipelinePrewarm<String, Value>()
    val value = Value()
    val prepared = owner.prepare(owner.generation, listOf("raster"), { value }, { true })
    owner.invalidate()
    check(runCatching { owner.withPrepared(prepared) {} }.exceptionOrNull() is CompilationRevokedException)
    check(!value.closed)
    prepared.close()
    check(value.closed)

    val published = Value()
    owner.prepare(owner.generation, listOf("raster"), { published }, { true }).use { ready ->
        owner.withPrepared(ready) { snapshot ->
            check(snapshot["raster"] === published)
            ready.transferOwnership()
        }
    }
    check(!published.closed)
    published.close()
    owner.close()
}
