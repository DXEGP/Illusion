package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunkSection
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(ChunkAccess::class)
abstract class ChunkSectionArrayPublicationMixin {
    @WrapMethod(method = ["getSections"])
    private fun `dxr_mc$expose`(original: Operation<Array<LevelChunkSection>>): Array<LevelChunkSection> {
        return D3D12RtSectionArrays.expose((this as ChunkAccess), original.call())
    }

    @WrapOperation(method = ["getSection", "getHighestFilledSectionIndex"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;")])
    private fun `dxr_mc$borrow`(
        chunk: ChunkAccess, original: Operation<Array<LevelChunkSection>>
    ): Array<LevelChunkSection> {
        D3D12RtSectionArrays.borrow(chunk).use { access ->
            return original.call(chunk)
        }
    }
}

