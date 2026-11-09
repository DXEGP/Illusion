package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtCompilerInputs
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.world.level.block.Block
import org.spongepowered.asm.mixin.Mixin

@Mixin(BlockColors::class)
abstract class BlockColorsTintSourcesMixin {
    @WrapMethod(method = ["register"])
    private fun `dxr_mc$publishTintSources`(
        sources: List<BlockTintSource>, blocks: Array<Block>, original: Operation<Void?>
    ) {
        D3D12SectionGeometry.publishTintSources(this as BlockColors) { original.call(D3D12RtCompilerInputs.freezeTintSources(sources), blocks) }
    }
}

