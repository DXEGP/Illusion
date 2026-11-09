package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.expression.Expression
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtTintInputs
import net.minecraft.client.color.block.BlockTintCache
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(BlockTintCache::class)
abstract class BlockTintCachePublicationMixin {
    @WrapMethod(method = ["getColor"])
    private fun `dxr_mc$readColor`(position: BlockPos, original: Operation<Int>): Int {
        D3D12RtTintInputs.reading((this as BlockTintCache), position).use { read ->
            val result = original.call(position)
            read.complete()
            return result
        }
    }

    @Expression("?[?]")
    @WrapOperation(method = ["getColor"], at = [At("MIXINEXTRAS:EXPRESSION")])
    private fun `dxr_mc$readSlot`(array: IntArray, index: Int, original: Operation<Int>): Int {
        return D3D12RtTintInputs.readSlot(array, index) { original.call(array, index) }
    }

    @Expression("?[?] = ?")
    @WrapOperation(method = ["getColor"], at = [At("MIXINEXTRAS:EXPRESSION")])
    private fun `dxr_mc$writeSlot`(array: IntArray, index: Int, value: Int, original: Operation<Void?>) {
        D3D12RtTintInputs.writeSlot(array, index, value) { original.call(array, index, value) }
    }

    @WrapMethod(method = ["invalidateForChunk"])
    private fun `dxr_mc$publishChunkInvalidation`(chunkX: Int, chunkZ: Int, original: Operation<Void?>) {
        D3D12SectionGeometry.publishTintCache((this as BlockTintCache),
                ChunkPos.pack(chunkX, chunkZ)) { original.call(chunkX, chunkZ) }
    }

    @WrapMethod(method = ["invalidateAll"])
    private fun `dxr_mc$publishInvalidation`(original: Operation<Void?>) {
        D3D12SectionGeometry.publishTintCache((this as BlockTintCache), null) { original.call() }
    }
}

