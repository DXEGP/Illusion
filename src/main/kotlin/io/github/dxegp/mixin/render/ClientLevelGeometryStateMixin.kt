package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientLevel::class)
abstract class ClientLevelGeometryStateMixin {
    @Inject(method = ["onChunkLoaded"], at = [At("TAIL")])
    private fun `dxr_mc$observeChunkLoaded`(position: ChunkPos, callback: CallbackInfo) {
        val level = (this as ClientLevel)
        val chunk = level.chunkSource.getChunk(position.x, position.z, ChunkStatus.FULL, false)
        if (chunk != null) {
            D3D12SectionGeometry.observeChunkLoaded(position.x, position.z, level.minSectionY,
                    level.sectionsCount, level, chunk)
        }
    }

    @Inject(method = ["unload"], at = [At("HEAD")])
    private fun `dxr_mc$observeChunkUnloaded`(chunk: LevelChunk, callback: CallbackInfo) {
        val position = chunk.pos
        D3D12SectionGeometry.observeChunkUnloaded(position.x, position.z, this, chunk)
    }
}

