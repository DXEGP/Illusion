package io.github.dxegp.mixin.render

import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import java.util.concurrent.atomic.AtomicBoolean

@Mixin(targets = ["net.minecraft.client.renderer.chunk.SectionRenderDispatcher\$RenderSection\$SectionTask"])
interface SectionCompileTaskAccessor {
    @Accessor("isCancelled")
    fun `dxr_mc$isCancelled`(): AtomicBoolean
}

