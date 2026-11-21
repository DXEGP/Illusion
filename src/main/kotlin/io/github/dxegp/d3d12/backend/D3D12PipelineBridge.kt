package io.github.dxegp.d3d12.backend

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Frontend registrations supply the reload inventory and the source for direct backend callers. Each asynchronous
 * job's exact pipeline/source must be forwarded separately: a name-only registration cannot distinguish overlapping
 * reloads. Native pipeline leases are independent of source registrations and shared by staging/device caches and
 * frontends.
 */
object D3D12PipelineBridge {
    internal class Entry(val pipeline: RenderPipeline, val source: ShaderSource)
    internal enum class RegistrationPhase { STAGED, PUBLISHED, DISCARDED }
    internal class ReloadRegistration(var phase: RegistrationPhase = RegistrationPhase.STAGED)

    private val registrationLock = Any()
    private var entries: MutableMap<String, Entry> = HashMap()
    private val reloadSources = WeakHashMap<ShaderSource, ReloadRegistration>()
    private class Lease(var references: Int = 1, var onFrontend: ((CompiledRenderPipeline) -> Unit)? = null)
    private val leases = IdentityHashMap<D3D12CompiledRenderPipeline, Lease>()

    fun inventory(): List<RenderPipeline> = synchronized(registrationLock) { entries.values.map { it.pipeline } }

    internal fun stage(source: ShaderSource) = synchronized(registrationLock) {
        check(!reloadSources.containsKey(source)) { "Shader source is already registered for reload" }
        reloadSources[source] = ReloadRegistration()
    }

    internal class PreparedRegistrations(
        private val replacement: MutableMap<String, Entry>,
        private val registration: ReloadRegistration,
        private val retiredRegistrations: Array<ReloadRegistration>,
    ) {
        internal fun publish() {
            entries = replacement
            for (retired in retiredRegistrations) retired.phase = RegistrationPhase.DISCARDED
            registration.phase = RegistrationPhase.PUBLISHED
        }
    }

    internal fun publishRegistrations(
        source: ShaderSource,
        inventory: Iterable<RenderPipeline>,
        candidates: Collection<D3D12CompiledRenderPipeline>,
        publication: (PreparedRegistrations) -> Unit,
    ) = synchronized(registrationLock) {
        val registration = checkNotNull(reloadSources[source]) { "Shader reload source was not staged" }
        check(registration.phase == RegistrationPhase.STAGED) { "Shader reload registration is already consumed" }
        val replacement = HashMap<String, Entry>()
        inventory.forEach { pipeline -> replacement[pipeline.location.toString()] = Entry(pipeline, source) }
        val retired = reloadSources.values.filter { it.phase == RegistrationPhase.PUBLISHED }.toTypedArray()
        val prepared = PreparedRegistrations(replacement, registration, retired)
        synchronized(leases) {
            candidates.forEach { pipeline ->
                checkNotNull(leases[pipeline]) { "Shader reload pipeline lease was released" }
            }
            candidates.forEach { pipeline -> leases[pipeline]!!.onFrontend = null }
        }
        publication(prepared)
    }

    fun own(pipeline: D3D12CompiledRenderPipeline) = synchronized(leases) {
        check(leases.put(pipeline, Lease()) == null) { "Pipeline already has an owner" }
    }

    fun retain(pipeline: D3D12CompiledRenderPipeline): Boolean = synchronized(leases) {
        val lease = leases[pipeline] ?: return false
        lease.references = Math.incrementExact(lease.references)
        true
    }

    fun observeFrontends(pipeline: D3D12CompiledRenderPipeline, observer: ((CompiledRenderPipeline) -> Unit)?) {
        synchronized(leases) { checkNotNull(leases[pipeline]).onFrontend = observer }
    }

    fun frontendCreated(pipeline: D3D12CompiledRenderPipeline, frontend: CompiledRenderPipeline) {
        val observer = synchronized(leases) { checkNotNull(leases[pipeline]).onFrontend }
        observer?.invoke(frontend)
    }

    fun release(pipeline: D3D12CompiledRenderPipeline) {
        synchronized(leases) {
            val lease = checkNotNull(leases[pipeline]) { "Pipeline lease already released" }
            if (lease.references == 1) {
                pipeline.close()
                leases.remove(pipeline)
            } else {
                lease.references--
            }
        }
    }

    fun discard(source: ShaderSource) {
        synchronized(registrationLock) {
            reloadSources[source]?.phase = RegistrationPhase.DISCARDED
            entries.entries.removeIf { it.value.source === source }
        }
    }

    @JvmStatic
    fun register(pipeline: RenderPipeline, source: ShaderSource) = synchronized(registrationLock) {
        val phase = reloadSources.getOrPut(source) { ReloadRegistration(RegistrationPhase.PUBLISHED) }.phase
        if (phase == RegistrationPhase.STAGED || phase == RegistrationPhase.DISCARDED) return@synchronized
        entries[pipeline.location.toString()] = Entry(pipeline, source)
    }

    @JvmStatic
    fun lookup(name: String): RenderPipeline? = synchronized(registrationLock) { entries[name]?.pipeline }

    @JvmStatic
    fun lookupSource(name: String): ShaderSource? = synchronized(registrationLock) { entries[name]?.source }

    @JvmStatic
    fun clear() = synchronized(registrationLock) {
        entries.clear()
        reloadSources.values.forEach { it.phase = RegistrationPhase.DISCARDED }
    }
}
