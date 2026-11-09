package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.debug.D3D12DebugInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(DebugEntrySystemSpecs::class)
abstract class DebugEntrySystemSpecsMixin {
    @Inject(method = ["display"], at = [At("TAIL")])
    private fun `dxr_mc$appendD3D12Lines`(
        displayer: DebugScreenDisplayer, serverOrClientLevel: Level?,
        clientChunk: LevelChunk?, serverChunk: LevelChunk?, callback: CallbackInfo
    ) {
        val window = Minecraft.getInstance().window
        val lines = D3D12DebugInfo.extraSystemLines(window.width, window.height)
        if (lines.isNotEmpty()) displayer.addToGroup(D3D12DebugInfo.SYSTEM_GROUP, lines)
    }
}

