package io.github.dxegp.render.compilation

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.command.D3D12RasterRoute
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import java.util.WeakHashMap

internal class VanillaPipelineSemantics {
    private val routes = WeakHashMap<RenderPipeline, D3D12RasterRoute>()

    fun route(pipeline: RenderPipeline): D3D12RasterRoute = routes.getOrPut(pipeline) {
        if (D3D12TerrainWorkGraph.requiresOrderedDraw(pipeline)) {
            D3D12RasterRoute.ORDERED_INDIRECT
        } else {
            val location = pipeline.location.toString()
            if (location.contains("terrain") && !location.contains("shadow"))
                D3D12RasterRoute.TERRAIN_WORK_GRAPH
            else D3D12RasterRoute.INDIRECT
        }
    }

    fun clear() = routes.clear()
}
