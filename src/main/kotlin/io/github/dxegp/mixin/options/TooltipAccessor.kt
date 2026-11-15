package io.github.dxegp.mixin.options

import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(Tooltip::class)
interface TooltipAccessor {
    @Accessor("message")
    fun `dxr_mc$message`(): Component
}

