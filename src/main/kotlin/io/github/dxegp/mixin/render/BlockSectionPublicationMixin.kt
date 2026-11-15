package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import org.spongepowered.asm.mixin.Mixin

@Mixin(LevelChunkSection::class)
abstract class BlockSectionPublicationMixin {
    @WrapMethod(method = ["setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"])
    private fun `dxr_mc$publishEdit`(
        localX: Int, localY: Int, localZ: Int, state: BlockState, lock: Boolean,
        original: Operation<BlockState>
    ): BlockState {
        D3D12SectionGeometry.beginBlockSectionPublication((this as LevelChunkSection), false).use { publication ->
            val result = original.call(localX, localY, localZ, state, lock)
            publication?.complete()
            return result
        }
    }

    @WrapMethod(method = ["read"])
    private fun `dxr_mc$publishRead`(buffer: FriendlyByteBuf, original: Operation<Void?>) {
        D3D12SectionGeometry.beginBlockSectionPublication((this as LevelChunkSection), true).use { publication ->
            original.call(buffer)
            publication?.complete()
        }
    }

    @WrapMethod(method = ["recalcBlockCounts"])
    private fun `dxr_mc$publishCounts`(original: Operation<Void?>) {
        D3D12SectionGeometry.beginBlockSectionPublication((this as LevelChunkSection), false).use { publication ->
            original.call()
            publication?.complete()
        }
    }
}

