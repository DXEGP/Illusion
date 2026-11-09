package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At

@Mixin(ClientChunkCache::class)
abstract class ClientChunkCacheBiomePublicationMixin {
    @Shadow @Final
    private lateinit var level: ClientLevel

    @WrapOperation(method = ["replaceBiomes"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V")])
    private fun `dxr_mc$publishBiomes`(chunk: LevelChunk, buffer: FriendlyByteBuf, original: Operation<Void?>) {
        val position = chunk.pos
        D3D12SectionGeometry.beginBiomePublication(level,
                (this as ClientChunkCache), position.x, position.z).use { publication ->
            original.call(chunk, buffer)
            publication?.complete()
        }
    }
}

