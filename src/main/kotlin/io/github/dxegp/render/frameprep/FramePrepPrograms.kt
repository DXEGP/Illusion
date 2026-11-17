package io.github.dxegp.render.frameprep

import io.github.dxegp.config.IllusionConfig
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.reconstruction.SuperResolution
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.vrs.D3D12VariableShadingRate

object FramePrepPrograms {
    internal class Prepared(
        val generation: Long,
        private val encoder: D3D12CommandEncoderBackend,
        private val resourceReload: VanillaFrameCoordinator.ResourceReload,
        private var program: OitReconstructionInputs.ResolveProgram?,
        private var candidate: Long,
    ) : AutoCloseable {
        internal val resolveProgram: OitReconstructionInputs.ResolveProgram
            get() = checkNotNull(program) { "Frame programs are already consumed" }

        internal fun commitNative() {
            check(candidate != 0L) { "Frame programs are already consumed" }
            encoder.contextControl { context -> MCDR_ABI.D3D12CommitFramePrograms(context, candidate) }
            candidate = 0L
        }

        internal fun publish(resolve: OitReconstructionInputs.ResolveProgram) {
            OitReconstructionInputs.publishResolveProgram(resolve)
            resourceReload.publish()
            program = null
        }

        internal fun releaseRegistration() {
            encoder.releaseCommandLifetimeResource(this)
        }

        override fun close() {
            val abandoned = candidate
            candidate = 0L
            val abandonedProgram = program
            program = null
            resourceReload.close()
            releaseRegistration()
            var failure: Throwable? = null
            try {
                MCDR_ABI.D3D12AbortFramePrograms(abandoned)
            } catch (cleanup: Throwable) {
                failure = cleanup
            }
            try {
                abandonedProgram?.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            failure?.let { throw it }
        }
    }

    internal fun prepare(encoder: D3D12CommandEncoderBackend, generation: Long): Prepared {
        val resourceReload = VanillaFrameCoordinator.prepareResourceReload()
        var program: OitReconstructionInputs.ResolveProgram? = null
        var candidate = 0L
        var prepared: Prepared? = null
        try {
            program = OitReconstructionInputs.createResolveProgram(generation)
            var flags = 0
            val backend = SuperResolution.backendId()
            if (SuperResolution.mask() &&
                (backend == MCDR_ABI.SR_BACKEND_FSR || backend == MCDR_ABI.SR_BACKEND_XESS)) {
                flags = flags or 1
            }
            val sriEnabled = !IllusionConfig.SPEC.isLoaded || IllusionConfig.VRS_SRI.get()
            val adapter = D3D12AdapterInfo.current
            if (sriEnabled && D3D12VariableShadingRate.isEnabled() && adapter != null &&
                adapter.shadingRateTier >= 2 && adapter.shadingRateTileSize > 0) {
                flags = flags or 2
            }
            candidate = encoder.contextControl { context ->
                MCDR_ABI.D3D12PrepareFramePrograms(context, generation, flags)
            }
            prepared = Prepared(generation, encoder, resourceReload, program, candidate)
            encoder.retainUntilCommandsClosed(prepared)
            return prepared
        } catch (failure: Throwable) {
            if (prepared != null) {
                try { prepared.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            } else {
                resourceReload.close()
                try { MCDR_ABI.D3D12AbortFramePrograms(candidate) }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { program?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}
