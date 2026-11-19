package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.textures.GpuSampler
import io.github.dxegp.d3d12.command.D3D12TerrainDraw
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import java.util.Collections

internal class OitTerrainStageInvocation(
    val frame: OitFrameFacts,
    val scope: OitProducerScope,
    val sourceDrawId: Long,
    val parentSourceDrawId: Long?,
    val passId: Long,
)

internal sealed interface OitNativeTerrainStageReceiptResult {
    data object EntryUnavailable : OitNativeTerrainStageReceiptResult
    data object Unsupported : OitNativeTerrainStageReceiptResult
    data class Available(val receipt: OitNativeTerrainStageReceipt) : OitNativeTerrainStageReceiptResult
}

internal data class OitNativeTerrainStageReceipt(
    val receiptId: Long,
    val nativeSequence: Long,
    val frameSerial: Long,
    val producerId: Long,
    val producerScopeSerial: Long,
    val sourceDrawId: Long,
    val parentSourceDrawId: Long?,
    val passId: Long,
    val stage: net.minecraft.client.renderer.oit.OitStage,
    val contentState: OitNativeContentState,
    val pipelineIdentity: Long,
    val readSetRegistration: OitTerrainReadSetRegistration,
)

internal enum class OitTerrainGroupUnavailable {
    MATERIAL_VIEW_UNAVAILABLE, MATERIAL_SAMPLER_UNAVAILABLE,
    MATERIAL_IDENTITY_UNAVAILABLE, LIGHTMAP_IDENTITY_UNAVAILABLE,
}

internal data class OitTerrainSampledMaterial(
    val identity: OitSurfaceMaterial,
    val baseMipLevel: Int,
    val mipLevels: Int,
    val width: Int,
    val height: Int,
    val sampler: OitSurfaceSampler,
) {
    constructor(identity: OitSurfaceMaterial, view: GpuTextureView, sampler: GpuSampler) :
        this(identity, view.baseMipLevel(), view.mipLevels(), view.getWidth(0), view.getHeight(0), OitSurfaceSampler(sampler))
}

internal sealed interface OitTerrainStageMaterial {
    data class Known(val primary: OitTerrainSampledMaterial, val lightmap: OitTerrainSampledMaterial?) : OitTerrainStageMaterial
    data class Unavailable(val reason: OitTerrainGroupUnavailable) : OitTerrainStageMaterial
}

internal data class OitTerrainBufferRange(val ownerId: Long, val byteOffset: Long, val byteLength: Long)

internal data class OitTerrainSelectionKey(
    val producerId: Long,
    val commandRange: OitTerrainBufferRange,
    val vertexRange: OitTerrainBufferRange,
    val drawCount: Int,
    val selection: List<D3D12MeshDrawCapture.TerrainCommandSelection>,
    val material: OitTerrainStageMaterial.Known,
)

internal data class OitTerrainStageOccurrenceKey(
    val selection: OitTerrainSelectionKey,
    val stage: net.minecraft.client.renderer.oit.OitStage,
)

internal data class OitTerrainOccurrenceKey(
    val selection: OitTerrainSelectionKey,
    val ordinal: Int,
)

internal data class OitTerrainPreparedBinding(
    val name: String, val handle: Long, val byteOffset: Long, val byteLength: Long,
)

internal class OitTerrainStageFacts(
    val invocation: OitTerrainStageInvocation,
    draw: D3D12TerrainDraw,
    prepared: D3D12MeshDrawCapture.TerrainPreparedFacts,
    val commandRange: OitTerrainBufferRange,
    val vertexRange: OitTerrainBufferRange,
    val material: OitTerrainStageMaterial,
    val drawGroup: OitDrawGroupEvidence,
    val nativeReceipt: OitNativeTerrainStageReceiptResult,
) {
    val pipeline: Long = prepared.pipeline
    val pipelineLocation: String = draw.pipeline.info().location.toString()
    val vertexBuffer: Long = prepared.vertexBuffer
    val sectionCount: Int = prepared.sectionCount
    val meshletCount: Int = prepared.meshletCount
    val primitiveCount: Int = prepared.batch.primitiveCount
    val quadsPerMeshlet: Int = prepared.batch.quadsPerMeshlet
    val selection: List<D3D12MeshDrawCapture.TerrainCommandSelection> = prepared.commandSelections
    val exactReadSet: D3D12MeshDrawCapture.TerrainExactReadSet = prepared.exactReadSet
    val uploadReads: List<D3D12MeshDrawCapture.TerrainUploadReadFact> = prepared.uploadReads
    val meshletDescriptorOffsets: List<Int> = Collections.unmodifiableList(prepared.meshletDescriptorOffsets.toList())
    val bindings: List<OitTerrainPreparedBinding> = Collections.unmodifiableList(prepared.bindingHandles.indices.map { index ->
        val uniforms = draw.pipeline.uniformBindings
        val name = if (index < uniforms.size) uniforms[index].name else when (index - uniforms.size) {
            0 -> "TerrainGlobals"
            1 -> "TerrainSections"
            2 -> "TerrainMeshlets"
            else -> error("Unexpected terrain prepared binding index $index")
        }
        OitTerrainPreparedBinding(name, prepared.bindingHandles[index], prepared.bindingOffsets[index], prepared.bindingLengths[index])
    })
}
