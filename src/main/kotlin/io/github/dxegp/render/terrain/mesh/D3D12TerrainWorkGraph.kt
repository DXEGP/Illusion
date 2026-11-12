package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.d3d12.shader.D3D12CompiledShader
import io.github.dxegp.d3d12.shader.NagaVertexAdapter
import java.nio.ByteOrder

object D3D12TerrainWorkGraph {
    fun requiresOrderedDraw(pipeline: RenderPipeline): Boolean {
        val location = pipeline.location.toString()
        return !location.contains("oit_") && (location.contains("translucent") ||
            pipeline.colorTargetStates.any { it?.blendFunction()?.isPresent == true })
    }

    class Prepared(val pipeline: Long, private var batch: Long) : AutoCloseable {
        fun draw(ctx: Long, vertexBuffer: D3D12GpuBuffer, vertexOffset: Long): Boolean =
            MCDR_ABI.D3D12DrawTerrainWorkGraph(ctx, batch, vertexBuffer.handle(), vertexOffset)

        override fun close() {
            if (batch != 0L) MCDR_ABI.D3D12DestroyTerrainBatch(batch)
            batch = 0L
        }
    }

    fun source(shader: D3D12CompiledShader, backfaceCull: Boolean): String? {
        val vertex = NagaVertexAdapter.adapt(shader) ?: return null
        val names = listOf("CameraBlockPos", "CameraOffset", "ModelViewMat", "ProjMat")
        val uniforms = names.map { vertex.uniforms[it] ?: return null }
        return buildString {
            appendLine(vertex.source)
            for ((index, macro) in listOf("TERRAIN_CAMERA_BLOCK", "TERRAIN_CAMERA_OFFSET",
                "TERRAIN_MODEL_VIEW", "TERRAIN_PROJECTION").withIndex()) {
                appendLine("#define $macro ${uniforms[index]}")
            }
            appendLine("#define TERRAIN_BACKFACE_CULL ${if (backfaceCull) 1 else 0}")
            append(resource("terrain_workgraph.hlsl"))
        }
    }

    fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream(
        "/assets/illusion/shaders/terrain/$name")) { "Missing terrain shader $name" }
        .bufferedReader().use { it.readText() }

    fun prepare(compiled: D3D12CompiledRenderPipeline, commands: GpuBufferSlice, drawCount: Int,
                vertexBuffer: D3D12GpuBuffer?, vertexOffset: Long, ctx: Long,
                staging: D3D12TransientMemory, residency: D3D12MeshletResidency,
                uniforms: Map<String, GpuBufferSlice>, cull: Boolean, vrsBand: FloatArray): Prepared? {
        val location = compiled.info().location.toString()
        if (requiresOrderedDraw(compiled.info())) return null
        val layer = when {
            location.contains("solid_terrain") -> "SOLID"
            location.contains("cutout_terrain") -> "CUTOUT"
            location.contains("translucent") || location.contains("oit_") -> "TRANSLUCENT"
            else -> return null
        }
        if (vertexBuffer == null || vertexOffset < 0L || compiled.terrainProgram == 0L) return null
        val batch = D3D12MeshDrawCapture.batch(commands) ?: return null
        if (batch.drawCount != drawCount) return null
        if (batch.draws.any { draw ->
            val start = vertexOffset + draw.command.baseVertex().toLong() * D3D12MeshletBuilder.VERTEX_STRIDE
            start < vertexOffset || start > vertexBuffer.size() ||
                draw.geometry.vertexBytes > vertexBuffer.size() - start
        }) return null
        val pipeline = compiled.terrainPipeline(batch.quadsPerMeshlet)
        if (pipeline == 0L) return null
        val tables = D3D12MeshDrawCapture.buildTables(batch, layer, vertexOffset, ctx, staging, residency)
            ?: return null
        val globals = staging.writeIntoStagingBlock(32L, 256L) { destination ->
            destination.order(ByteOrder.LITTLE_ENDIAN)
            destination.putInt(tables.meshletCount).putInt(if (cull) 1 else 0)
                .putInt(tables.sectionCount).putInt(0)
            for (component in 0..3) destination.putFloat(vrsBand[component])
            32L
        }.getOrThrow().first
        val slices = compiled.buildBindings().filter { it.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER }
            .map { checkNotNull(uniforms[it.name]) { "Missing terrain uniform ${it.name}" } } +
            listOf(globals, tables.sections, tables.meshlets)
        check(slices.none { it.buffer().isClosed }) { "Terrain Work Graph input buffer is closed" }
        val handle = MCDR_ABI.D3D12PrepareTerrainWorkGraph(ctx, compiled.terrainProgram,
            slices.map { (it.buffer() as D3D12GpuBuffer).handle() }.toLongArray(),
            slices.map { it.offset() }.toLongArray(), slices.map { it.length() }.toLongArray(),
            tables.sectionCount, tables.meshletCount)
        check(handle != 0L) { "Terrain Work Graph preparation failed: ${MCDR_ABI.D3D12LastError()}" }
        return Prepared(pipeline, handle)
    }
}
