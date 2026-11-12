package io.github.dxegp.render.reconstruction

import io.github.dxegp.Illusion
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend

internal class ReconstructionTiming(private val backendLabel: () -> String) : AutoCloseable {
    private var owner: D3D12CommandEncoderBackend? = null
    private val evalSamples = LongArray(COST_SAMPLES)
    private val blitSamples = LongArray(COST_SAMPLES)
    private var sampleCursor = 0
    private var gpuPool = 0L
    private var gpuFrame = 0
    private var gpuPoolWarned = false
    private val gpuSamples = LongArray(GPU_SAMPLES)

    fun reset() {
        sampleCursor = 0
        gpuFrame = 0
    }

    override fun close() {
        if (gpuPool != 0L) {
            MCDR_ABI.D3D12DestroyQueryPool(gpuPool)
            gpuPool = 0L
        }
        reset()
        gpuPoolWarned = false
        owner = null
    }

    private companion object {
        const val COST_SAMPLES = 128
        const val COST_LOG_EVERY = 120
        const val GPU_SAMPLES = 120
    }

    private fun ensureGpuPool(): Boolean {
        if (gpuPool != 0L) {
            return true
        }
        if (gpuPoolWarned) {
            return false
        }
        val pool = try {
            MCDR_ABI.D3D12CreateQueryPool(GPU_SAMPLES * 2)
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: creating the query pool threw {}", t.toString())
            return false
        }
        if (pool == 0L) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: no mcdr_dx12CreateQueryPool")
            return false
        }
        gpuPool = pool
        return true
    }

    fun writeGpuFront(encoder: D3D12CommandEncoderBackend): Boolean {
        if (owner != null && owner !== encoder) return false
        if (owner == null) {
            encoder.retainUntilCommandsClosed(this)
            owner = encoder
        }
        if (gpuFrame >= GPU_SAMPLES || !ensureGpuPool()) {
            return false
        }
        return try {
            MCDR_ABI.D3D12WriteTimestamp(encoder.nativeHandle(), gpuPool, gpuFrame * 2)
            true
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: writing a timestamp threw {}", t.toString())
            false
        }
    }

    fun writeGpuBack(encoder: D3D12CommandEncoderBackend, tracked: Boolean) {
        if (!tracked || owner !== encoder) {
            return
        }
        try {
            MCDR_ABI.D3D12WriteTimestamp(encoder.nativeHandle(), gpuPool, gpuFrame * 2 + 1)
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: writing a timestamp threw {}", t.toString())
            return
        }
        gpuFrame++
        if (gpuFrame >= GPU_SAMPLES) {
            flushGpuSamples()
            gpuFrame = 0
        }
    }

    private fun flushGpuSamples() {
        if (gpuPool == 0L) {
            return
        }
        val frequency = try {
            MCDR_ABI.D3D12GetTimestampFrequency()
        } catch (t: Throwable) {
            0L
        }
        if (frequency <= 0L) {
            return
        }
        val values = LongArray(GPU_SAMPLES * 2)
        try {
            MCDR_ABI.D3D12ReadQueryValues(gpuPool, 0, GPU_SAMPLES * 2, values)
        } catch (t: Throwable) {
            gpuPoolWarned = true
            Illusion.LOGGER.warn("GPU timing disabled: reading the query pool threw {}", t.toString())
            return
        }
        var accepted = 0
        val limit = frequency / 10
        for (i in 0 until GPU_SAMPLES) {
            val front = values[i * 2]
            val back = values[i * 2 + 1]
            if (front !in 1..<back) {
                continue
            }
            val ticks = back - front
            if (ticks > limit) {
                continue
            }
            gpuSamples[accepted++] = ticks
        }
        if (accepted == 0) {
            return
        }
        val sorted = gpuSamples.copyOf(accepted).also { it.sort() }
        Illusion.LOGGER.debug(
            "gpu cost over {} frames: backend={} p50={}us p95={}us (max {}us, {} samples rejected)",
            accepted,
            backendLabel(),
            sorted[accepted / 2] * 1_000_000L / frequency,
            sorted[(accepted * 95) / 100] * 1_000_000L / frequency,
            sorted[accepted - 1] * 1_000_000L / frequency,
            GPU_SAMPLES - accepted,
        )
    }

    fun recordCost(evalNanos: Long, blitNanos: Long) {
        evalSamples[sampleCursor % COST_SAMPLES] = evalNanos
        blitSamples[sampleCursor % COST_SAMPLES] = blitNanos
        sampleCursor++
        if (sampleCursor % COST_LOG_EVERY != 0) {
            return
        }
        val frames = minOf(sampleCursor, COST_SAMPLES)
        val eval = evalSamples.copyOf(frames).also { it.sort() }
        val blit = blitSamples.copyOf(frames).also { it.sort() }
        Illusion.LOGGER.debug(
            "record cost over {} frames: evaluate p50={}us p95={}us, blit p50={}us p95={}us (CPU only)",
            frames,
            eval[frames / 2] / 1_000,
            eval[(frames * 95) / 100] / 1_000,
            blit[frames / 2] / 1_000,
            blit[(frames * 95) / 100] / 1_000,
        )
    }

}
