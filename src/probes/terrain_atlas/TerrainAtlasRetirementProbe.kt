package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12TransientMemory

private fun geometry(generation: Long, meshlets: Int) = D3D12SectionGeometry.LayerUpload(
    generation, "SOLID", meshlets * 6, meshlets * 112, meshlets * 24,
    List(meshlets) { D3D12MeshletBuilder.Meshlet(0, 1, 0, 0, 0, 255, 255, 255, 1) })

fun main() {
    val context = 41L
    val staging = D3D12TransientMemory(context, Runnable {})
    val residency = D3D12MeshletResidency()
    val original = geometry(1L, 65_536)
    val addition = geometry(2L, 1)
    val overflow = geometry(3L, 131_072)
    val abandoned = geometry(4L, 262_144)
    fun collect() {
        staging.allocateStaging(4L, 4L, GpuBuffer.USAGE_COPY_SRC, 0L, 1L).close()
    }
    fun latestAtlas(): Long = MCDR_ABI.buffers.entries.last { it.value.usage == GpuBuffer.USAGE_COPY_DST }.key
    fun destroys(handle: Long): Int = MCDR_ABI.buffers.getValue(handle).destroys

    val first = residency.prepare(context, staging, listOf(original))
    check(first.size() == 1024L * 1024L)
    staging.rotate(1L)
    val second = residency.prepare(context, staging, listOf(addition))
    check(second !== first && second.size() == 2L * 1024L * 1024L)
    check(destroys(first.handle()) == 0)
    MCDR_ABI.completedFences[context] = 1L
    MCDR_ABI.completedFences[context + 1L] = 1000L
    collect()
    check(destroys(first.handle()) == 0) { "Unsealed replacement retired on an earlier fence" }
    staging.rotate(2L)
    collect()
    check(destroys(first.handle()) == 0) { "Other context completion retired this atlas" }
    MCDR_ABI.completedFences[context] = 2L
    collect()
    check(destroys(first.handle()) == 1 && destroys(second.handle()) == 0)

    val priorOffset = residency.offset(addition)
    MCDR_ABI.failCopyAt = MCDR_ABI.copyCalls + 1
    check(runCatching { residency.prepare(context, staging, listOf(overflow)) }.isFailure)
    val failed = latestAtlas()
    check(failed != second.handle() && destroys(failed) == 0)
    check(residency.prepare(context, staging, listOf(addition)) === second)
    check(residency.offset(addition) == priorOffset) { "Failed upload changed published offsets" }
    staging.rotate(3L)
    collect()
    check(destroys(failed) == 0) { "Failed upload retired before its submission completed" }
    MCDR_ABI.completedFences[context] = 3L
    collect()
    check(destroys(failed) == 1 && destroys(second.handle()) == 0)

    MCDR_ABI.failCopyAt = MCDR_ABI.copyCalls + 1
    check(runCatching { residency.prepare(context, staging, listOf(abandoned)) }.isFailure)
    val unsubmitted = latestAtlas()
    MCDR_ABI.completedFences[context] = 100L
    collect()
    check(destroys(unsubmitted) == 0) { "Unsubmitted resource retired without command destruction" }
    staging.close()
    check(destroys(unsubmitted) == 1 && destroys(second.handle()) == 0)
    residency.close()
    staging.close()
    residency.close()
    check(MCDR_ABI.buffers.values.all { it.destroys == 1 })
    println("PASS actual atlas/transient classes: growth, upload failure, publication rollback, per-context fences, unsubmitted retention, terminal cleanup")
}
