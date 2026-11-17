package io.github.dxegp.render.temporal

import net.minecraft.client.particle.SingleQuadParticle
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

object D3D12ParticleMotionCapture {
    interface Owner {
        fun illusionParticleMotion(): Lifetime
    }

    class Lifetime {
        internal var previous: Rendered? = null
        internal var current: Rendered? = null
    }

    internal data class Rendered(
        val world: WeakReference<Any>, val sequence: Long, val generation: Long,
        val topology: List<SingleQuadParticle.Layer>, val positions: Array<FloatArray?>,
        val sources: Array<FeatureGeometrySource?>,
    )

    private class Extraction(val lifetime: Lifetime?, val entries: MutableList<Entry> = ArrayList()) {
        @Volatile
        var complete = false
        var topology: List<SingleQuadParticle.Layer> = emptyList()
    }

    private class Entry(val extraction: Extraction, val role: Int, val opaqueRole: Int,
        val layer: SingleQuadParticle.Layer)
    private val extracting = ThreadLocal<Extraction?>()
    private val extractingParticle = ThreadLocal<SingleQuadParticle?>()
    private val unsupportedOpaqueFrame = AtomicLong()

    internal fun opaqueMotionComplete(frame: VanillaFrameInputs): Boolean =
        unsupportedOpaqueFrame.get() != frame.identity.realFrameId
    private val supportedClasses = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean {
            val stateType = net.minecraft.client.renderer.state.level.QuadParticleRenderState::class.java
            val declaring = type.getMethod("extract", stateType,
                net.minecraft.client.Camera::class.java, Float::class.javaPrimitiveType).declaringClass.name
            var candidate: Class<*>? = type
            while (candidate != null && candidate != SingleQuadParticle::class.java) {
                if (candidate.declaredMethods.any { it.name == "extractRotatedQuad" }) return false
                candidate = candidate.superclass
            }
            return declaring in supportedExtractors
        }
    }

    fun extract(particle: SingleQuadParticle, action: () -> Unit) {
        val outer = extracting.get()
        val outerParticle = extractingParticle.get()
        extracting.set(null)
        extractingParticle.set(particle.takeIf { D3D12Velocity.isActive() })
        try {
            action()
            extracting.get()?.let { scope ->
                scope.topology = scope.entries.map { it.layer }
                scope.complete = true
            }
        } finally {
            extracting.set(outer)
            extractingParticle.set(outerParticle)
        }
    }

    private val supportedExtractors = setOf(
        "net.minecraft.client.particle.SingleQuadParticle",
        "net.minecraft.client.particle.DustColorTransitionParticle",
        "net.minecraft.client.particle.FlyTowardsPositionParticle",
        "net.minecraft.client.particle.ShriekParticle",
        "net.minecraft.client.particle.VibrationSignalParticle",
        "net.minecraft.client.particle.FireworkParticles\$OverlayParticle",
        "net.minecraft.client.particle.FireworkParticles\$SparkParticle",
    )

    class PackedState {
        private class LayerEntries {
            var count = 0
            val tracked = HashMap<Int, Entry>()
        }
        private val layers = HashMap<SingleQuadParticle.Layer, LayerEntries>()
        private var rendering: LayerEntries? = null
        private var cursor = 0

        @Synchronized
        fun add(layer: SingleQuadParticle.Layer) {
            val entries = layers.getOrPut(layer) { LayerEntries() }
            val index = entries.count++
            val particle = extractingParticle.get() ?: return
            if (!supportedClasses.get(particle.javaClass) || particle !is Owner) {
                VanillaFrameCoordinator.currentFrame()?.takeIf { !layer.translucent() }?.let { frame ->
                    unsupportedOpaqueFrame.accumulateAndGet(frame.identity.realFrameId) { recorded, rejected ->
                        maxOf(recorded, rejected)
                    }
                }
                return
            }
            val scope = extracting.get() ?: run {
                val lifetime = particle.illusionParticleMotion()
                Extraction(lifetime).also(extracting::set)
            }
            val entry = Entry(scope, scope.entries.size, scope.entries.count { !it.layer.translucent() }, layer)
            scope.entries.add(entry)
            entries.tracked[index] = entry
        }

        @Synchronized
        fun clear() {
            layers.clear()
        }

        @Synchronized
        fun build(layer: SingleQuadParticle.Layer, action: () -> Unit) {
            if (rendering != null) {
                action()
                return
            }
            rendering = layers[layer]
            cursor = 0
            try {
                action()
            } finally {
                rendering = null
                cursor = 0
            }
        }

        fun quad(action: () -> Unit) {
            val entries = rendering
            val entry = entries?.tracked?.get(cursor++)
            val scope = entry?.extraction
            val lifetime = scope?.lifetime
            val frame = D3D12RasterMotionCapture.geometryFrame()
            if (entry == null || scope == null || !scope.complete || lifetime == null ||
                frame == null || entry.layer.translucent() &&
                frame.space != D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
                action()
                return
            }
            synchronized(lifetime) {
                var current = lifetime.current
                if (current == null || current.sequence != frame.sequence || current.generation != frame.generation ||
                    current.world.get() !== frame.world) {
                    lifetime.previous = current
                    current = Rendered(WeakReference(frame.world), frame.sequence, frame.generation,
                        scope.topology, arrayOfNulls(scope.topology.size), arrayOfNulls(scope.topology.size))
                    lifetime.current = current
                }
                val previous = lifetime.previous
                val prior = previous?.takeIf {
                    frame.hasPrevious && it.world.get() === frame.world && it.sequence == frame.sequence - 1 &&
                        it.generation == frame.generation &&
                        (if (entry.layer.translucent()) it.topology == scope.topology else
                            it.topology.filterNot { layer -> layer.translucent() } ==
                                scope.topology.filterNot { layer -> layer.translucent() })
                }
                val existing = current.sources.getOrNull(entry.role)
                if (existing != null || current.topology != scope.topology) {
                    if (current.topology != scope.topology) current.sources.forEach { it?.invalidate() }
                    else existing?.invalidate()
                }
                val oldSource = previous?.takeIf {
                    it.world.get() === frame.world && it.generation == frame.generation &&
                        it.sequence == frame.sequence - 1
                }?.sources?.getOrNull(entry.role)
                val source = if (frame.space == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
                    existing ?: FeatureGeometrySource.next(FeatureGeometryKind.PARTICLE, oldSource).also {
                        if (previous != null && previous.topology != scope.topology) it.advanceGeneration()
                        if (current.topology == scope.topology) current.sources[entry.role] = it else it.invalidate()
                    }
                } else null
                val priorRole = if (entry.layer.translucent()) entry.role else prior?.let { rendered ->
                    rendered.topology.indices.filter { !rendered.topology[it].translucent() }.getOrNull(entry.opaqueRole)
                }
                val oldPositions = priorRole?.let { prior?.positions?.getOrNull(it) }
                val capture = if (source == null) D3D12RasterMotionCapture.beginGeometry(oldPositions)
                    else D3D12RasterMotionCapture.beginFeatureGeometry(oldPositions, source)
                if (capture == null) {
                    action()
                    return
                }
                var success = false
                var positions: FloatArray? = null
                try {
                    action()
                    success = true
                } finally {
                    positions = D3D12RasterMotionCapture.endGeometry(capture, success)
                    if (!success) current.positions[entry.role] = null
                }
                if (success && positions?.size == 12 && current.topology == scope.topology &&
                    current.positions[entry.role] == null) {
                    current.positions[entry.role] = positions
                } else {
                    source?.invalidate()
                }
            }
        }
    }
}
