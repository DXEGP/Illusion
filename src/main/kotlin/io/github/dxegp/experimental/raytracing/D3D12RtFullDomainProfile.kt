package io.github.dxegp.experimental.raytracing

import com.google.gson.JsonObject
import io.github.dxegp.d3d12.extern.MCDR_ABI
import net.minecraft.client.Options
import net.minecraft.client.multiplayer.ClientLevel
import kotlin.math.ceil
import kotlin.math.floor

object D3D12RtFullDomainProfile {
    const val VERSION = 3
    const val QUERY_T_MAX = 1e9f
    const val SECONDARY_T_MAX = 1e9f
    const val LEGACY_RADIUS = 96f

    data class Request(val worldIdentity: Any, val dimension: String, val configuredViewDistance: Int,
                       val clientViewDistance: Int, val minimumSectionY: Int, val sectionCount: Int,
                       val primary: D3D12RtSpatialDirectory.Region)

    enum class Gap {
        DIRECTORY_PAGE_BUDGET, PRODUCER_UNIVERSE_UNAVAILABLE, NEAR_GEOMETRY_ONLY,
        GEOMETRY_RESIDENCY_BUDGET, GEOMETRY_SELECTION_STALE, GEOMETRY_PUBLICATION_PENDING, GEOMETRY_CONTENT_MISSING,
        SECONDARY_INTERVAL_UNKNOWN, OBJECT_CATEGORIES_INCOMPLETE,
    }

    data class Snapshot(val request: Request, val epoch: Int, val sourceRevision: Long,
                        val frameId: Long, val geometrySnapshotId: Long,
                        val directory: D3D12RtSpatialDirectory.Region, val requestedPages: Long,
                        val publishedPages: Int, val update: D3D12RtSpatialDirectory.Update,
                        val geometryCenter: RtVec3, val geometryRadius: Float?, val geometryRevision: Long,
                        val geometryRecords: Int, val recordLimit: Int, val geometryByteLimit: Long,
                        val geometryDomain: D3D12RtSpatialDirectory.Region?,
                        val residency: D3D12RtGeometryPool.Residency?, val selectionPublished: Boolean,
                        val scene: MCDR_ABI.RtSceneStats?, val compositeRadius: Float?,
                        val traceWidth: Int, val traceHeight: Int, val displayWidth: Int, val displayHeight: Int,
                        val producerReason: String, val gaps: Set<Gap>,
                        val managedMemory: MCDR_ABI.RtManagedMemoryBudget? = null)

    fun fromClient(world: ClientLevel, options: Options, position: RtVec3): Request =
        request(world, world.dimension().identifier().toString(), options.renderDistance().get(),
            options.effectiveRenderDistance, world.minSectionY, world.sectionsCount, position)

    fun compositeRadius(request: Request?): Float = if (request == null) LEGACY_RADIUS else 0f

    fun request(worldIdentity: Any, dimension: String, configuredViewDistance: Int, clientViewDistance: Int,
                minimumSectionY: Int, sectionCount: Int, position: RtVec3): Request {
        require(configuredViewDistance >= 0 && clientViewDistance >= 0 && sectionCount > 0)
        require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite())
        fun section(value: Float, limit: Int): Int = floor(value.toDouble() / 16.0)
            .coerceIn(-limit.toDouble(), limit - 1.0).toInt()
        val sectionX = section(position.x, 1 shl 21)
        val sectionY = section(position.y, 1 shl 19)
        val sectionZ = section(position.z, 1 shl 21)
        val lowerY = minimumSectionY.toLong().coerceIn(-(1L shl 19), (1L shl 19) - 1)
        val upperY = (minimumSectionY.toLong() + sectionCount - 1).coerceIn(lowerY, (1L shl 19) - 1)
        fun lower(origin: Int): Int = (origin.toLong() - clientViewDistance)
            .coerceIn(-(1L shl 21), (1L shl 21) - 1).toInt()
        fun upper(origin: Int): Int = (origin.toLong() + clientViewDistance)
            .coerceIn(-(1L shl 21), (1L shl 21) - 1).toInt()
        val region = D3D12RtSpatialDirectory.Region(lower(sectionX), lowerY.toInt(), lower(sectionZ),
            upper(sectionX) - lower(sectionX) + 1, (upperY - lowerY + 1).toInt(),
            upper(sectionZ) - lower(sectionZ) + 1, D3D12RtSpatialDirectory.Page(sectionX.floorDiv(8),
                sectionY.coerceIn(lowerY.toInt(), upperY.toInt()).floorDiv(8), sectionZ.floorDiv(8)))
        return Request(worldIdentity, dimension, configuredViewDistance, clientViewDistance,
            minimumSectionY, sectionCount, region)
    }

    internal fun directory(request: Request, universe: D3D12RtSourceUniverse.Snapshot): D3D12RtSpatialDirectory.Region {
        val primary = request.primary
        val bounds = universe.bounds ?: return primary
        fun extent(minimum: Int, size: Int, lower: Float, upper: Float, limit: Long): Pair<Int, Int> {
            if (!lower.isFinite() || !upper.isFinite()) return minimum to size
            val first = (minimum.toLong() + ceil(lower.toDouble() / 16.0).coerceIn(-8388608.0, 0.0).toLong() - 1L)
                .coerceIn(-limit, limit - 1)
            val last = (minimum.toLong() + size - 1L + floor(upper.toDouble() / 16.0)
                .coerceIn(0.0, 8388608.0).toLong()).coerceIn(first, limit - 1)
            return first.toInt() to (last - first + 1).toInt()
        }
        val horizontal = extent(primary.minimumX, primary.sizeX, bounds.minimumX, bounds.maximumX, 1L shl 21)
        val vertical = extent(primary.minimumY, primary.sizeY, bounds.minimumY, bounds.maximumY, 1L shl 19)
        val depth = extent(primary.minimumZ, primary.sizeZ, bounds.minimumZ, bounds.maximumZ, 1L shl 21)
        return D3D12RtSpatialDirectory.Region(horizontal.first, vertical.first, depth.first,
            horizontal.second, vertical.second, depth.second, primary.focus)
    }

    internal fun snapshot(request: Request, coverage: D3D12RtGeometryPool.Coverage, geometryCenter: RtVec3,
                          geometryRadius: Float?, frameId: Long, geometrySnapshotId: Long,
                          geometryRevision: Long, geometryRecords: Int, recordLimit: Int,
                          geometryByteLimit: Long, geometryDomain: D3D12RtSpatialDirectory.Region?,
                          residency: D3D12RtGeometryPool.Residency?, selectionPublished: Boolean,
                          scene: MCDR_ABI.RtSceneStats?, compositeRadius: Float?, plan: D3D12RtPipelinePlan.Plan): Snapshot {
        val region = coverage.region
        fun count(minimum: Int, size: Int): Long =
            (minimum + size - 1).floorDiv(8).toLong() - minimum.floorDiv(8) + 1
        val pages = count(region.minimumX, region.sizeX) * count(region.minimumY, region.sizeY) *
            count(region.minimumZ, region.sizeZ)
        val gaps = buildSet {
            if (pages > coverage.spatialUpdate.requestedPages) add(Gap.DIRECTORY_PAGE_BUDGET)
            if (!coverage.sources.certifiable) add(Gap.PRODUCER_UNIVERSE_UNAVAILABLE)
            if (residency == null) add(Gap.NEAR_GEOMETRY_ONLY) else {
                if (residency.omittedGroups > 0) add(Gap.GEOMETRY_RESIDENCY_BUDGET)
                if (residency.superseded || residency.request != request || residency.geometryEpoch != coverage.versions.epoch ||
                    residency.geometryRevision != coverage.versions.revision) add(Gap.GEOMETRY_SELECTION_STALE)
            }
            if (!selectionPublished || scene == null || scene.worldEpoch != coverage.versions.epoch.toLong() ||
                scene.liveRevision != coverage.versions.revision || scene.targetSnapshotId != geometrySnapshotId ||
                scene.pendingSnapshotId != 0L || scene.pendingBuildCount > 0) add(Gap.GEOMETRY_PUBLICATION_PENDING)
            if (scene != null && (scene.absentCount > 0 || scene.obsoleteCount > 0 || scene.proxySectionCount > 0))
                add(Gap.GEOMETRY_CONTENT_MISSING)
            add(Gap.SECONDARY_INTERVAL_UNKNOWN)
            add(Gap.OBJECT_CATEGORIES_INCOMPLETE)
        }
        return Snapshot(request, coverage.versions.epoch, coverage.sources.revision, frameId, geometrySnapshotId, region, pages,
            coverage.spatial.getInt(60), coverage.spatialUpdate, geometryCenter, geometryRadius, geometryRevision,
            geometryRecords, recordLimit, geometryByteLimit, geometryDomain, residency, selectionPublished, scene, compositeRadius,
            plan.traceWidth, plan.traceHeight,
            plan.displayWidth, plan.displayHeight, coverage.sources.universe.reason, gaps)
    }

    fun json(profile: Snapshot?): String = if (profile == null) "null" else JsonObject().apply {
        addProperty("version", VERSION)
        addProperty("dimension", profile.request.dimension)
        addProperty("world_epoch", profile.epoch)
        addProperty("source_revision", profile.sourceRevision)
        addProperty("frame_id", profile.frameId)
        addProperty("geometry_snapshot_id", profile.geometrySnapshotId)
        addProperty("configured_view_distance", profile.request.configuredViewDistance)
        addProperty("client_view_distance", profile.request.clientViewDistance)
        addProperty("minimum_section_y", profile.request.minimumSectionY)
        addProperty("section_count", profile.request.sectionCount)
        addProperty("directory_minimum_x", profile.directory.minimumX)
        addProperty("directory_minimum_y", profile.directory.minimumY)
        addProperty("directory_minimum_z", profile.directory.minimumZ)
        addProperty("directory_size_x", profile.directory.sizeX)
        addProperty("directory_size_y", profile.directory.sizeY)
        addProperty("directory_size_z", profile.directory.sizeZ)
        addProperty("requested_pages", profile.requestedPages)
        addProperty("selected_pages", profile.update.requestedPages)
        addProperty("published_pages", profile.publishedPages)
        addProperty("recomputed_pages", profile.update.recomputedPages)
        addProperty("reused_pages", profile.update.reusedPages)
        addProperty("encoded_page_bytes", profile.update.encodedPageBytes)
        addProperty("payload_bytes", profile.update.payloadBytes)
        addProperty("directory_build_ns", profile.update.buildNanos)
        addProperty("geometry_radius", profile.geometryRadius)
        addProperty("geometry_center_x", profile.geometryCenter.x)
        addProperty("geometry_center_y", profile.geometryCenter.y)
        addProperty("geometry_center_z", profile.geometryCenter.z)
        addProperty("geometry_revision", profile.geometryRevision)
        addProperty("geometry_records", profile.geometryRecords)
        addProperty("record_limit", profile.recordLimit)
        addProperty("geometry_byte_limit", profile.geometryByteLimit)
        add("trace_radius", com.google.gson.JsonNull.INSTANCE)
        addProperty("primary_t_max", QUERY_T_MAX)
        addProperty("composite_radius", profile.compositeRadius)
        addProperty("selection_published", profile.selectionPublished)
        profile.geometryDomain?.let { region ->
            add("geometry_domain", JsonObject().apply {
                addProperty("minimum_x", region.minimumX)
                addProperty("minimum_y", region.minimumY)
                addProperty("minimum_z", region.minimumZ)
                addProperty("size_x", region.sizeX)
                addProperty("size_y", region.sizeY)
                addProperty("size_z", region.sizeZ)
            })
        }
        profile.residency?.let { residency ->
            add("geometry_selection", JsonObject().apply {
                addProperty("source_revision", residency.geometryRevision)
                addProperty("superseded", residency.superseded)
                addProperty("requested_groups", residency.requestedGroups)
                addProperty("requested_records", residency.requestedRecords)
                addProperty("requested_bytes", residency.requestedBytes)
                addProperty("selected_groups", residency.selectedGroups)
                addProperty("demanded_groups", residency.demandedGroups)
                addProperty("admitted_demands", residency.admittedDemands.size)
                addProperty("selected_records", residency.slice?.recordCount ?: 0)
                addProperty("selected_bytes", residency.selectedBytes)
                addProperty("omitted_groups", residency.omittedGroups)
                addProperty("byte_limit", residency.budget.byteLimit)
                addProperty("record_limit", residency.budget.recordLimit)
                addProperty("vertex_capacity", residency.budget.vertexCapacity)
                addProperty("index_capacity", residency.budget.indexCapacity)
                addProperty("quad_capacity", residency.budget.quadCapacity)
                add("limits", JsonObject().apply {
                    for ([reason, groups] in residency.limits) addProperty(reason.name, groups)
                })
            })
        }
        profile.scene?.let { scene ->
            add("geometry_publication", JsonObject().apply {
                addProperty("frame_id", scene.frameId)
                addProperty("target_snapshot_id", scene.targetSnapshotId)
                addProperty("pending_snapshot_id", scene.pendingSnapshotId)
                addProperty("recorded_records", scene.recordedCount)
                addProperty("pending_builds", scene.pendingBuildCount)
                addProperty("absent_records", scene.absentCount)
                addProperty("obsolete_records", scene.obsoleteCount)
                addProperty("proxy_sections", scene.proxySectionCount)
                addProperty("oldest_age_frames", scene.oldestAgeFrames)
            })
        }
        addProperty("secondary_t_max", SECONDARY_T_MAX)
        addProperty("trace_width", profile.traceWidth)
        addProperty("trace_height", profile.traceHeight)
        addProperty("display_width", profile.displayWidth)
        addProperty("display_height", profile.displayHeight)
        addProperty("producer_reason", profile.producerReason)
        profile.managedMemory?.let { memory ->
            add("native_managed_memory", JsonObject().apply {
                fun account(name: String, values: MCDR_ABI.RtMemoryAccount) {
                    add(name, JsonObject().apply {
                        addProperty("bytes", values.byteCount)
                        addProperty("limit_bytes", values.limitBytes)
                        addProperty("rejections", values.rejectionCount)
                        addProperty("peak_bytes", values.peakBytes)
                    })
                }
                account("total", memory.total)
                account("geometry_gpu", memory.geometryGpu)
                account("geometry_upload", memory.geometryUpload)
                account("acceleration", memory.acceleration)
                account("frame_gpu", memory.frameGpu)
                account("frame_host", memory.frameHost)
            })
        }
        add("gaps", com.google.gson.JsonArray().apply { for (gap in profile.gaps) add(gap.name) })
    }.toString()
}
