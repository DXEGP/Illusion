package io.github.dxegp.mixin.presentation

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostPass
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(PostChain::class)
internal interface PostChainDisplayAccessor {
    @Accessor("passes")
    fun illusionDisplayPasses(): List<PostPass>

    @Accessor("internalTargets")
    fun illusionDisplayInternalTargets(): Map<Identifier, PostChainConfig.InternalTarget>

    @Accessor("externalTargets")
    fun illusionDisplayExternalTargets(): Set<Identifier>
}

@Mixin(PostPass::class)
internal interface PostPassDisplayAccessor {
    @Accessor("pipeline")
    fun illusionDisplayPipeline(): RenderPipeline

    @Accessor("outputTargetId")
    fun illusionDisplayOutput(): Identifier

    @Accessor("inputs")
    fun illusionDisplayInputs(): List<PostPass.Input>
}
