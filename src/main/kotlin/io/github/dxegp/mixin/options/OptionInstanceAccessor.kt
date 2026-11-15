package io.github.dxegp.mixin.options

import net.minecraft.client.OptionInstance
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(OptionInstance::class)
interface OptionInstanceAccessor {
    @Accessor("tooltip")
    fun `dxr_mc$tooltipSupplier`(): OptionInstance.TooltipSupplier<Any>?
}

