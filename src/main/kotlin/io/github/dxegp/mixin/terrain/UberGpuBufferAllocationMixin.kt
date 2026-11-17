package io.github.dxegp.mixin.terrain

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.vertex.StagingBuffer
import com.mojang.blaze3d.vertex.TlsfAllocator
import com.mojang.blaze3d.vertex.UberGpuBuffer
import com.mojang.datafixers.util.Pair
import com.mojang.renderpearl.api.buffers.GpuBuffer
import io.github.dxegp.render.terrain.mesh.TerrainGeometryAllocationOwner
import io.github.dxegp.render.terrain.mesh.TerrainGeometryAllocations
import io.github.dxegp.d3d12.resource.D3D12TransientMemory
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.concurrent.locks.ReentrantLock

@Mixin(UberGpuBuffer::class)
abstract class UberGpuBufferAllocationMixin : TerrainGeometryAllocationOwner {
    @Shadow @Final private var bufferUsage: Int = 0
    @Shadow @Final private lateinit var nodes: List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>>
    @Unique private var illusionGeometryAllocations: TerrainGeometryAllocations? = null

    override val terrainGeometryAllocations: TerrainGeometryAllocations?
        get() = illusionGeometryAllocations
    override val terrainGeometryBufferUsage: Int
        get() = bufferUsage

    override fun attachTerrainGeometryAllocations(lock: ReentrantLock): TerrainGeometryAllocations =
        illusionGeometryAllocations ?: TerrainGeometryAllocations(lock).also { illusionGeometryAllocations = it }

    @WrapOperation(method = ["uploadStagedAllocations"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/StagingBuffer\$Uploader;copyTo(Lcom/mojang/blaze3d/vertex/StagingBuffer\$BufferHandle;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;J)V")], require = 1)
    private fun illusionPublishAllocation(uploader: StagingBuffer.Uploader, source: StagingBuffer.BufferHandle,
        destination: GpuBuffer, offset: Long, original: Operation<Void?>,
        @Local allocation: TlsfAllocator.Allocation) {
        val owner = illusionGeometryAllocations
        if (owner == null) {
            original.call(uploader, source, destination, offset)
            return
        }
        val allocator = nodes.first { it.second === allocation.heap }.first
        val fact = owner.reserveUpload(allocator, allocation, destination, source.size())
        if (fact == null) {
            original.call(uploader, source, destination, offset)
            return
        }
        val staging = (uploader as StagingUploaderGeometryAccessor).illusionGeometryEncoder().transientMemory()
            as D3D12TransientMemory
        try {
            staging.retainUploadDestination(fact, fact::retainRead)
            original.call(uploader, source, destination, offset)
        } catch (failure: Throwable) {
            owner.retireAllocation(allocation)
            throw failure
        }
    }

    @WrapOperation(method = ["freeAllocation"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/TlsfAllocator;free(Lcom/mojang/blaze3d/vertex/TlsfAllocator\$Allocation;)V")], require = 1)
    private fun illusionRetireAllocation(allocator: TlsfAllocator, allocation: TlsfAllocator.Allocation,
        original: Operation<Void?>) {
        if (illusionGeometryAllocations?.retireAllocation(allocation) != true) original.call(allocator, allocation)
    }

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun illusionRetireAllocations(callback: CallbackInfo) {
        illusionGeometryAllocations?.retireAll()
    }

    @WrapOperation(method = ["uploadStagedAllocations", "close"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;close()V")], require = 2)
    private fun illusionCloseHeap(buffer: GpuBuffer, original: Operation<Void?>) {
        if (illusionGeometryAllocations?.closeHeap(buffer) != true) original.call(buffer)
    }
}
