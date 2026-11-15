package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtSectionArrays
import net.minecraft.client.renderer.chunk.SectionCopy
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(SectionCopy::class)
abstract class SectionCopyArrayPublicationMixin {
    @WrapOperation(method = ["<init>"], at = [At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;")])
    private fun `dxr_mc$borrow`(
        chunk: LevelChunk, original: Operation<Array<LevelChunkSection>>
    ): Array<LevelChunkSection> {
        D3D12RtSectionArrays.borrow(chunk).use { access ->
            return original.call(chunk)
        }
    }
}

