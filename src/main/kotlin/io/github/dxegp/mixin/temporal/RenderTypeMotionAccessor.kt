package io.github.dxegp.mixin.temporal

import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(RenderType::class)
interface RenderTypeMotionAccessor {
    @Accessor("state")
    fun illusionMotionState(): RenderSetup
}

@Mixin(RenderSetup::class)
interface RenderSetupMotionAccessor {
    @Accessor("layeringTransform")
    fun illusionMotionLayering(): LayeringTransform
}
