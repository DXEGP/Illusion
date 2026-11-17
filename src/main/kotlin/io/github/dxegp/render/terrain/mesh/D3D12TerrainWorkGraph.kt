package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.command.D3D12AdditionalRasterAttachment
import io.github.dxegp.d3d12.command.D3D12PreparedRasterDispatch
import io.github.dxegp.d3d12.command.D3D12PreparedRasterCapture
import io.github.dxegp.d3d12.command.D3D12TerrainPreparedFacts
import io.github.dxegp.d3d12.command.D3D12RasterAttachmentRole
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import io.github.dxegp.d3d12.shader.D3D12CompiledShader
import io.github.dxegp.d3d12.shader.D3D12OitGuideSource
import io.github.dxegp.d3d12.shader.NagaVertexAdapter
import java.nio.ByteOrder

object D3D12TerrainWorkGraph {
    fun requireSupport() {
        if (!D3D12Settings.meshTerrain) return
        val adapter = D3D12AdapterInfo.current
        val missing = buildList {
            if (!D3D12MeshShader.isSupported()) add("mesh shader support")
            if ((adapter?.workGraphsTier ?: 0) < 10) add("Work Graph Tier 1.0")
            if ((adapter?.shaderModelValue ?: 0) < 0x69) add("Shader Model 6.9")
        }
        check(missing.isEmpty()) { "Terrain requirements unavailable: ${missing.joinToString()}" }
    }

    fun requireIndirectAvailable(available: Boolean): Boolean {
        if (D3D12AdapterInfo.current != null && D3D12Settings.meshTerrain) {
            check(available) { "Terrain Work Graph requires vanilla indirect draw support" }
        }
        return available
    }

    fun useIndirectDraw(requested: Boolean): Boolean =
        requested || (D3D12AdapterInfo.current != null && D3D12Settings.meshTerrain)

    fun requiresOrderedDraw(pipeline: RenderPipeline): Boolean {
        val location = pipeline.location.toString()
        return !location.contains("oit_") && (location.contains("translucent") ||
            pipeline.colorTargetStates.any { it?.blendFunction()?.isPresent == true })
    }

    internal class Prepared(override val pipeline: Long, private var batch: Long,
        override val capture: D3D12PreparedRasterCapture?,
        override val terrainFacts: D3D12MeshDrawCapture.TerrainPreparedFacts?,
        private val preparedFactsConsumer: ((D3D12TerrainPreparedFacts) -> Unit)?) : D3D12PreparedRasterDispatch {
        internal val batchHandle: Long get() = batch
        override fun draw(context: Long, vertexBuffer: D3D12GpuBuffer, vertexOffset: Long): Boolean {
            check(MCDR_ABI.D3D12DrawTerrainWorkGraph(context, batch, vertexBuffer.handle(), vertexOffset)) {
                "Terrain Work Graph draw failed: ${MCDR_ABI.D3D12LastError()}"
            }
            return true
        }

        override fun recordPreparedFacts() {
            if (preparedFactsConsumer != null) preparedFactsConsumer.invoke(checkNotNull(terrainFacts))
        }

        override fun close() {
            if (batch != 0L) MCDR_ABI.D3D12DestroyTerrainBatch(batch)
            batch = 0L
        }
    }

    internal fun bindCaptureFacts(prepared: Prepared, capture: D3D12PreparedRasterCapture) {
        val facts = checkNotNull(prepared.terrainFacts) { "Terrain capture has no prepared Work Graph facts" }
        capture.bindTerrainFacts(facts)
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

    internal fun prepare(compiled: D3D12CompiledRenderPipeline, commands: GpuBufferSlice, drawCount: Int,
                vertexBuffer: D3D12GpuBuffer?, vertexOffset: Long, ctx: Long,
                staging: D3D12TransientMemory, residency: D3D12MeshletResidency,
                uniforms: Map<String, GpuBufferSlice>, cull: Boolean, vrsBand: FloatArray,
                rasterMotionTarget: Long = 0L, additionalAttachment: D3D12AdditionalRasterAttachment? = null,
                capture: D3D12PreparedRasterCapture? = null,
                preparedFactsConsumer: ((D3D12TerrainPreparedFacts) -> Unit)? = null): Prepared? {
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
        if (vertexOffset > vertexBuffer.size() ||
            batch.requiredVertexBytes > vertexBuffer.size() - vertexOffset) return null
        val pipeline = when {
            capture != null -> capture.pipeline
            additionalAttachment != null -> {
                val source = when (additionalAttachment.role) {
                    D3D12RasterAttachmentRole.CONTRIBUTION_GUIDE -> D3D12OitGuideSource.CONTRIBUTION
                    D3D12RasterAttachmentRole.UNRECORDED_COVERAGE -> D3D12OitGuideSource.UNRECORDED
                    D3D12RasterAttachmentRole.CLOUD_GUIDE -> error("Terrain cannot write a cloud guide")
                }
                val selected = if (rasterMotionTarget != 0L)
                    compiled.terrainOitRasterMotionPipeline(batch.quadsPerMeshlet, source)
                else compiled.terrainOitPipeline(batch.quadsPerMeshlet, source)
                check(selected != 0L) {
                    "Terrain ${compiled.info().location} has no ${additionalAttachment.role} variant for its active attachments"
                }
                selected
            }
            rasterMotionTarget != 0L && compiled.hasTerrainRasterMotionVariant ->
                compiled.terrainRasterMotionPipeline(batch.quadsPerMeshlet)
            else -> compiled.terrainPipeline(batch.quadsPerMeshlet)
        }
        if (pipeline == 0L) return null
        batch.retainGeometryReads(staging, vertexBuffer, vertexOffset)
        val tables = D3D12MeshDrawCapture.buildTables(batch, layer, vertexOffset, ctx, staging, residency)
            ?: return null
        val globals = staging.writeIntoStagingBlock(32L, 256L) { destination ->
            destination.order(ByteOrder.LITTLE_ENDIAN)
            destination.putInt(tables.meshletCount).putInt(if (cull) 1 else 0)
                .putInt(tables.sectionCount).putInt(0)
            for (component in 0..3) destination.putFloat(vrsBand[component])
            32L
        }.getOrThrow().first
        val uniformBindings = compiled.uniformBindings
        val uniformCount = uniformBindings.size
        val handles = LongArray(uniformCount + 3)
        val offsets = LongArray(handles.size)
        val lengths = LongArray(handles.size)
        val uploadReads = if (capture != null || preparedFactsConsumer != null)
            ArrayList<D3D12MeshDrawCapture.TerrainUploadReadFact>(uniformCount + 2) else null
        var uniformSlot = 0
        for (bindingIndex in handles.indices) {
            val slice = when (bindingIndex) {
                uniformCount -> globals
                uniformCount + 1 -> tables.sections
                uniformCount + 2 -> tables.meshlets
                else -> {
                    val binding = uniformBindings[bindingIndex]
                    checkNotNull(uniforms[binding.name]) { "Missing terrain uniform ${binding.name}" }
                }
            }
            val buffer = slice.buffer() as D3D12GpuBuffer
            check(!buffer.isClosed) { "Terrain Work Graph input buffer is closed" }
            handles[bindingIndex] = buffer.handle()
            offsets[bindingIndex] = slice.offset()
            lengths[bindingIndex] = slice.length()
            if (uploadReads != null && bindingIndex < uniformCount + 2) {
                val input = when (bindingIndex) {
                    uniformCount -> D3D12MeshDrawCapture.TerrainUploadInput.TerrainGlobals
                    uniformCount + 1 -> D3D12MeshDrawCapture.TerrainUploadInput.TerrainSections
                    else -> {
                        while (compiled.bindingLayout.entries[uniformSlot].type != D3D12BindGroupEntry.Type.UNIFORM_BUFFER) uniformSlot++
                        val binding = uniformBindings[bindingIndex]
                        check(compiled.bindingLayout.entries[uniformSlot].name == binding.name)
                        D3D12MeshDrawCapture.TerrainUploadInput.Uniform(binding.name, uniformSlot++)
                    }
                }
                uploadReads += D3D12MeshDrawCapture.TerrainUploadReadFact(bindingIndex, input,
                    staging.retainPreparedUploadRead(slice))
            }
        }
        val preparedFacts = if (capture != null || preparedFactsConsumer != null) batch.facts?.let { facts ->
            D3D12MeshDrawCapture.TerrainPreparedFacts(
                facts, pipeline, vertexBuffer.handle(), vertexOffset,
                handles, offsets, lengths,
                IntArray(batch.drawCount) { index -> tables.descriptors.offset(batch.draws[index].geometry) },
                tables.sectionCount, tables.meshletCount,
                batch.commandSelections,
                batch.exactReadSet(vertexBuffer, vertexOffset, tables.descriptors),
                java.util.Collections.unmodifiableList(checkNotNull(uploadReads)),
            )
        } else null
        val handle = MCDR_ABI.D3D12PrepareTerrainWorkGraph(ctx, compiled.terrainProgram,
            handles, offsets, lengths,
            tables.sectionCount, tables.meshletCount)
        check(handle != 0L) { "Terrain Work Graph preparation failed: ${MCDR_ABI.D3D12LastError()}" }
        return Prepared(pipeline, handle, capture, preparedFacts, preparedFactsConsumer)
    }
}
