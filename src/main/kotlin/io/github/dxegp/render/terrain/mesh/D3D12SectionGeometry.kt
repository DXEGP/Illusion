package io.github.dxegp.render.terrain.mesh

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class D3D12SectionGeometry {
    class LayerUpload(
        val generation: Long,
        val layer: String,
        val indexCount: Int,
        val vertexBytes: Int,
        val indexBytes: Int,
        val meshlets: List<D3D12MeshletBuilder.Meshlet>,
    ) {
        val maximumMeshletQuads = meshlets.maxOfOrNull { it.quadCount } ?: 0
        val bounded = meshlets.all { it.directionMask and D3D12MeshletBuilder.BOUNDS_OUTSIDE_SECTION == 0 }
    }

    private val generation = nextGeneration.incrementAndGet()
    private var retired = false
    private var layers: Map<String, LayerUpload> = emptyMap()

    @Synchronized
    fun captureUpload(layer: String, indexCount: Int, indexBytes: Int, vertex: ByteBuffer?) {
        if (retired || vertex == null || layer in layers || indexCount <= 0) return
        val built = D3D12MeshletBuilder.build(vertex,
            quadsPerMeshlet = io.github.dxegp.config.D3D12Settings.meshQuads) ?: return
        val upload = LayerUpload(generation, layer, indexCount, vertex.remaining(), indexBytes,
            built.meshlets.toList())
        layers = layers + (layer to upload)
    }

    @Synchronized
    fun snapshot(layer: String): LayerUpload? = layers[layer]

    @Synchronized
    fun retire() {
        retired = true
        layers = emptyMap()
    }

    private companion object {
        val nextGeneration = AtomicLong()
    }
}
