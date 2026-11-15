package io.github.dxegp.mixin.render

import net.minecraft.client.renderer.block.BlockStateModelSet
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.client.renderer.chunk.SectionCompiler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(SectionCompiler::class)
interface SectionCompilerAccessor {
    @Accessor("blockModelSet")
    fun `dxr_mc$blockModelSet`(): BlockStateModelSet

    @Accessor("fluidModelSet")
    fun `dxr_mc$fluidModelSet`(): FluidStateModelSet
}

