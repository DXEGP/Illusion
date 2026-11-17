package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometry
import java.util.Collections

internal object OitTerrainTileProducer {
    internal enum class UnavailableReason {
        FROZEN_CAMERA_NOT_ATTACHED,
        PREPARED_FACTS_MISMATCH,
        MESHLET_BOUNDS_OUTSIDE_SECTION,
        TILE_REGIONS_NOT_CAPTURED,
    }

    internal data class SectionFact(
        val section: D3D12MeshDrawCapture.TerrainDrawFact,
        val atlasDescriptorBase: Int,
        val geometry: D3D12SectionGeometry.LayerUpload,
    )

    internal data class Facts(
        val schemaVersion: Int,
        val frame: VanillaFrameIdentity,
        val primitiveCount: Int,
        val meshletCount: Int,
        val sections: List<SectionFact>,
    ) {
        val sectionCount: Int get() = sections.size

        init {
            require(schemaVersion == 2 && frame.width > 0 && frame.height > 0)
            require(primitiveCount > 0 && meshletCount > 0 && sections.isNotEmpty())
        }
    }

    internal sealed interface Result {
        data class Captured(val facts: Facts) : Result
        data class Unavailable(val reason: UnavailableReason) : Result
    }

    internal fun capture(
        frame: VanillaFrameInputs?,
        batch: D3D12MeshDrawCapture.Batch,
        facts: D3D12MeshDrawCapture.TerrainPreparedFacts,
    ): Result {
        val inputs = frame ?: return Result.Unavailable(UnavailableReason.FROZEN_CAMERA_NOT_ATTACHED)
        if (facts.batch.drawFacts.size != batch.drawCount || facts.batch.meshletCount <= 0 ||
            facts.meshletDescriptorOffsets.size != batch.drawCount)
            return Result.Unavailable(UnavailableReason.PREPARED_FACTS_MISMATCH)
        val sections = ArrayList<SectionFact>(batch.drawCount)
        for ((sectionIndex, draw) in batch.draws.withIndex()) {
            if (!draw.geometry.bounded)
                return Result.Unavailable(UnavailableReason.MESHLET_BOUNDS_OUTSIDE_SECTION)
            sections += SectionFact(facts.batch.drawFacts[sectionIndex],
                facts.meshletDescriptorOffsets[sectionIndex], draw.geometry)
        }
        return Result.Captured(Facts(
            schemaVersion = 2,
            frame = inputs.identity,
            primitiveCount = facts.batch.primitiveCount,
            meshletCount = facts.batch.meshletCount,
            sections = Collections.unmodifiableList(sections),
        ))
    }
}
