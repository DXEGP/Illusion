package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.command.D3D12TerrainDraw
import io.github.dxegp.d3d12.command.D3D12PreparedRasterCapture
import io.github.dxegp.d3d12.command.D3D12TerrainPreparedFacts
import io.github.dxegp.d3d12.ffi.allocateFfmArray
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.OitShaderPreludeV1
import io.github.dxegp.d3d12.shader.D3D12OitCaptureProfile
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import net.minecraft.client.renderer.oit.OitStage
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.util.WeakHashMap

internal object OitTerrainRecords {
    private data class SectionOwner(val world: Long, val producer: Long, val sectionX: Int,
        val sectionY: Int, val sectionZ: Int, val layer: String)

    private object MetadataOffsets {
        val layout = MCDRLayout.OIT_TERRAIN_METADATA
        val size = Math.toIntExact(layout.byteSize())
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val source = layout.byteOffset(groupElement("source"))
        val sectionCount = layout.byteOffset(groupElement("sectionCount"))
        val primitiveCount = layout.byteOffset(groupElement("primitiveCount"))
        val sections = layout.byteOffset(groupElement("sections"))
        val vertexBuffer = layout.byteOffset(groupElement("vertexBuffer"))
        val vertexOffset = layout.byteOffset(groupElement("vertexOffset"))
        val vertexBytes = layout.byteOffset(groupElement("vertexBytes"))
    }

    private object SourceOffsets {
        private val layout = MCDRLayout.OIT_SOURCE
        val size = layout.byteSize()
        val producerId = layout.byteOffset(groupElement("producerId"))
        val producerGeneration = layout.byteOffset(groupElement("producerGeneration"))
        val shaderGeneration = layout.byteOffset(groupElement("shaderGeneration"))
        val producerKind = layout.byteOffset(groupElement("producerKind"))
        val validity = layout.byteOffset(groupElement("validity"))
    }

    private object SectionOffsets {
        val layout = MCDRLayout.OIT_TERRAIN_SECTION
        val size = layout.byteSize()
        val surfaceId = layout.byteOffset(groupElement("surfaceId"))
        val geometryGeneration = layout.byteOffset(groupElement("geometryGeneration"))
        val materialId = layout.byteOffset(groupElement("materialId"))
        val materialGeneration = layout.byteOffset(groupElement("materialGeneration"))
        val textureGeneration = layout.byteOffset(groupElement("textureGeneration"))
        val primitiveBase = layout.byteOffset(groupElement("primitiveBase"))
        val primitiveCount = layout.byteOffset(groupElement("primitiveCount"))
        val sectionX = layout.byteOffset(groupElement("sectionX"))
        val sectionY = layout.byteOffset(groupElement("sectionY"))
        val sectionZ = layout.byteOffset(groupElement("sectionZ"))
        val flags = layout.byteOffset(groupElement("flags"))
        val vertexBase = layout.byteOffset(groupElement("vertexBase"))
        val vertexCount = layout.byteOffset(groupElement("vertexCount"))
    }

    private val producers = WeakHashMap<Any, Long>()
    private val sections = LinkedHashMap<SectionOwner, Long>(4096, 0.75f, true)
    private var nextIdentity = 0L
    private var worldEpoch = 0L
    fun prepare(draw: D3D12TerrainDraw): D3D12PreparedRasterCapture? {
        val records = OitReconstructionInputs.terrainRecordFrames() ?: return null
        val facts = OitProducerCapture.drawInputs() ?: return null
        val scope = facts.scope ?: return null
        if (!facts.knownProducer || scope.producer.kind != OitProducerKind.TERRAIN ||
            scope.stage != OitStage.ACCUMULATE || facts.pass?.hasAccumulateInputs() != true) return null
        if (facts.frame.identity != records.frame.identity || facts.frame.historyGeneration != records.frame.historyGeneration)
            throw OitRecordException(OitRecordFailure.STALE, "prepare terrain", records.frame.identity, "Terrain frame differs from records")
        val capture = draw.pipeline.oitCaptureProgram ?: return null
        if (capture.profile != D3D12OitCaptureProfile.TERRAIN) return null
        check(capture.generation == records.programGeneration && capture.shaderAbiVersion == OitShaderPreludeV1.SUPPORTED_SHADER_ABI)
        val batch = D3D12MeshDrawCapture.batch(draw.commands) ?: return null
        if (batch.drawCount != draw.drawCount || batch.primitiveCount <= 0 || batch.terrainDrawFacts.size != batch.drawCount) return null
        val pipeline = draw.pipeline.terrainCapturePipeline(batch.quadsPerMeshlet)
        if (pipeline == 0L) return null
        val materialView = draw.materialView ?: return null
        val materialSampler = draw.materialSampler ?: return null
        val primaryMaterial = OitTerrainMaterials.capture(materialView, materialSampler) ?: return null
        val lightmap = draw.lightmap?.let { OitTerrainMaterials.capture(it.view, it.sampler) }
        val material = OitTerrainMaterials.captureTerrain(primaryMaterial, lightmap)
        val validities = if (lightmap == null) 11 else 15
        if (worldEpoch != records.frame.identity.worldEpoch) {
            worldEpoch = records.frame.identity.worldEpoch
            sections.clear()
            producers.clear()
        }
        val producer = producers.getOrPut(scope.producer.owner) { Math.incrementExact(nextIdentity).also { nextIdentity = it } }
        val metadata = Arena.ofConfined().use { arena ->
            val input = arena.allocate(MetadataOffsets.layout)
            input.set(MCDRLayout.I32, MetadataOffsets.schemaVersion, 2)
            input.set(MCDRLayout.I32, MetadataOffsets.structSize, MetadataOffsets.size)
            val source = input.asSlice(MetadataOffsets.source, SourceOffsets.size)
            source.set(MCDRLayout.I64, SourceOffsets.producerId, producer)
            source.set(MCDRLayout.I64, SourceOffsets.producerGeneration, worldEpoch)
            source.set(MCDRLayout.I64, SourceOffsets.shaderGeneration, capture.generation)
            source.set(MCDRLayout.I32, SourceOffsets.producerKind, 0)
            source.set(MCDRLayout.I32, SourceOffsets.validity, 15)
            val rows = arena.allocateFfmArray(batch.drawCount, SectionOffsets.layout)
            for ((index, fact) in batch.terrainDrawFacts.withIndex()) {
                val key = SectionOwner(worldEpoch, producer, fact.sectionX, fact.sectionY, fact.sectionZ, fact.layer)
                val owner = sections[key] ?: run {
                    if (sections.size >= 65536) sections.remove(sections.keys.first())
                    Math.incrementExact(nextIdentity).also { nextIdentity = it; sections[key] = it }
                }
                val row = rows.asSlice(index * SectionOffsets.size, SectionOffsets.size)
                row.set(MCDRLayout.I64, SectionOffsets.surfaceId, owner)
                row.set(MCDRLayout.I64, SectionOffsets.geometryGeneration, fact.geometryGeneration)
                row.set(MCDRLayout.I64, SectionOffsets.materialId, material.id)
                row.set(MCDRLayout.I64, SectionOffsets.materialGeneration, material.generation)
                row.set(MCDRLayout.I64, SectionOffsets.textureGeneration, material.textureGeneration)
                row.set(MCDRLayout.I32, SectionOffsets.primitiveBase, fact.primitiveBase)
                row.set(MCDRLayout.I32, SectionOffsets.primitiveCount, fact.primitiveCount)
                row.set(MCDRLayout.I32, SectionOffsets.sectionX, fact.sectionX)
                row.set(MCDRLayout.I32, SectionOffsets.sectionY, fact.sectionY)
                row.set(MCDRLayout.I32, SectionOffsets.sectionZ, fact.sectionZ)
                row.set(MCDRLayout.I32, SectionOffsets.flags, validities)
                row.set(MCDRLayout.I32, SectionOffsets.vertexBase, batch.draws[index].command.baseVertex())
                row.set(MCDRLayout.I32, SectionOffsets.vertexCount, Math.multiplyExact(fact.quadCount, 4))
            }
            input.set(MCDRLayout.I32, MetadataOffsets.sectionCount, batch.drawCount)
            input.set(MCDRLayout.I32, MetadataOffsets.primitiveCount, batch.primitiveCount)
            input.set(MCDRLayout.PTR, MetadataOffsets.sections, rows)
            input.set(MCDRLayout.PTR, MetadataOffsets.vertexBuffer, MemorySegment.ofAddress(draw.vertexBuffer.handle()))
            input.set(MCDRLayout.I64, MetadataOffsets.vertexOffset, draw.vertexOffset)
            input.set(MCDRLayout.I64, MetadataOffsets.vertexBytes, batch.requiredVertexBytes)
            when (val result = records.publishTerrainMetadata(input)) {
                is OitRecordFrames.PublishedMetadata -> result
                OitRecordFrames.MetadataCapacityExceeded -> null
            }
        } ?: return null
        return object : D3D12PreparedRasterCapture {
            override val pipeline = pipeline
            private var consumed = false
            private var terrainFactsBound = false

            override fun bindTerrainFacts(facts: D3D12TerrainPreparedFacts) {
                val terrainFacts = facts as? D3D12MeshDrawCapture.TerrainPreparedFacts
                    ?: error("Terrain Work Graph facts came from an incompatible producer")
                check(terrainFacts.batch == batch.facts && terrainFacts.pipeline == pipeline &&
                    facts.vertexBuffer == draw.vertexBuffer.handle() && facts.vertexOffset == draw.vertexOffset &&
                    terrainFacts.sectionCount == batch.drawCount && terrainFacts.meshletCount == batch.meshletCount &&
                    facts.bindingHandles.size >= 3) {
                    "Terrain Work Graph facts changed between draw and preparation"
                }
                terrainFactsBound = true
            }

            override fun <Result> recordDraw(action: () -> Result): Result {
                check(!consumed && OitProducerCapture.drawInputs() === facts && terrainFactsBound)
                consumed = true
                val result = records.recordTerrainDraw(metadata, scope.serial, action)
                OitProducerCapture.recordDraw {
                    check(records.frame.identity == facts.frame.identity)
                }
                return result
            }
        }
    }

}

