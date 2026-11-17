package io.github.dxegp.mixin.terrain

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.vertex.UberGpuBuffer
import io.github.dxegp.render.terrain.mesh.TerrainGeometryAllocations
import io.github.dxegp.render.terrain.mesh.TerrainGeometryExtractionSource
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import java.util.concurrent.locks.ReentrantLock

@Mixin(SectionRenderDispatcher::class)
abstract class SectionGeometryAllocationMixin : TerrainGeometryExtractionSource {
    @Shadow @Final private lateinit var copyLock: ReentrantLock
    @Unique private var illusionGeometryDispatcher: TerrainGeometryAllocations.Dispatcher? = null

    @ModifyExpressionValue(method = ["lambda\$new\$0"],
        at = [At(value = "NEW", target = "com/mojang/blaze3d/vertex/UberGpuBuffer")], require = 2)
    private fun illusionAttachGeometryHeap(buffer: UberGpuBuffer<SectionMesh>,
        @Local(argsOnly = true) layer: ChunkSectionLayer): UberGpuBuffer<SectionMesh> {
        val dispatcher = illusionGeometryDispatcher ?: TerrainGeometryAllocations.Dispatcher(copyLock)
            .also { illusionGeometryDispatcher = it }
        dispatcher.attachBuffer(layer, buffer)
        return buffer
    }

    override fun captureTerrainGeometryReads(mesh: SectionMesh, layer: ChunkSectionLayer,
        slice: SectionRenderDispatcher.RenderSectionBufferSlice, customIndexBytes: Long): TerrainGeometryAllocations.DrawReads? =
        checkNotNull(illusionGeometryDispatcher).captureReads(mesh, layer, slice, customIndexBytes)
}
