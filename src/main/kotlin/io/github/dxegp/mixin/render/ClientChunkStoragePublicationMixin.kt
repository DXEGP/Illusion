package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import java.util.concurrent.atomic.AtomicReferenceArray

@Mixin(targets = ["net.minecraft.client.multiplayer.ClientChunkCache\$Storage"])
abstract class ClientChunkStoragePublicationMixin {
    @Shadow @Final
    private lateinit var `this$0`: ClientChunkCache

    @WrapOperation(method = ["onChunkRemoved", "onChunkAdded", "refreshEmptySections"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;")])
    private fun `dxr_mc$borrow`(
        chunk: LevelChunk, original: Operation<Array<LevelChunkSection>>
    ): Array<LevelChunkSection> {
        D3D12RtSectionArrays.borrow(chunk).use { access ->
            return original.call(chunk)
        }
    }

    @WrapOperation(method = ["drop"], at = [At(value = "INVOKE", remap = false,
            target = "Ljava/util/concurrent/atomic/AtomicReferenceArray;compareAndSet(ILjava/lang/Object;Ljava/lang/Object;)Z")])
    private fun `dxr_mc$publishDrop`(
        slots: AtomicReferenceArray<LevelChunk?>, index: Int, expected: Any?, next: Any?,
        original: Operation<Boolean>
    ): Boolean {
        return D3D12SectionGeometry.publishChunkDrop(`this$0`, this, slots, index, (expected as LevelChunk?),
                (next as LevelChunk?)) { original.call(slots, index, expected, next) }
    }

    @WrapOperation(method = ["replace"], at = [At(value = "INVOKE", remap = false,
            target = "Ljava/util/concurrent/atomic/AtomicReferenceArray;getAndSet(ILjava/lang/Object;)Ljava/lang/Object;")])
    private fun `dxr_mc$publishReplacement`(
        slots: AtomicReferenceArray<LevelChunk?>, index: Int, next: Any?, original: Operation<Any?>
    ): Any? {
        return D3D12SectionGeometry.publishChunkReplacement(`this$0`, this, slots, index, (next as LevelChunk?)) { original.call(slots, index, next) as LevelChunk? }
    }
}

