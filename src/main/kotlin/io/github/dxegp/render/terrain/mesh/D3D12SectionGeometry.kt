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
        meshlets: List<D3D12MeshletBuilder.Meshlet>,
    ) {
        val meshlets: List<D3D12MeshletBuilder.Meshlet> = meshlets.toList()
        val quadCount: Int
            get() = Math.floorDiv(indexCount, 6)
        val maximumMeshletQuads = meshlets.maxOfOrNull { it.quadCount } ?: 0
        val bounded = meshlets.all { it.directionMask and D3D12MeshletBuilder.BOUNDS_OUTSIDE_SECTION == 0 }

        init {
            require(generation > 0L && layer.isNotEmpty() && indexCount > 0 && indexCount % 6 == 0)
            require(vertexBytes > 0 && indexBytes >= 0 && meshlets.isNotEmpty())
            require(meshlets.zipWithNext().all { (first, second) -> first.firstQuad < second.firstQuad })
            require(meshlets.all { it.firstQuad >= 0 && it.quadCount > 0 &&
                it.firstQuad + it.quadCount <= quadCount })
            require(meshlets.sumOf { it.quadCount } == quadCount)
        }
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
            built.meshlets)
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
