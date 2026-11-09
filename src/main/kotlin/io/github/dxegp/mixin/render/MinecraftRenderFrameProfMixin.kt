package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.adapter.D3D12RenderProf
import io.github.dxegp.misc.settings.ImprovedTransparencyLock
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Minecraft::class)
abstract class MinecraftRenderFrameProfMixin {
    @Inject(method = ["renderFrame"], at = [At("HEAD")], remap = false)
    private fun D3D12ProfFrameHead(advanceGameTime: Boolean, ci: CallbackInfo) {
        ImprovedTransparencyLock.ensure()
        D3D12RenderProf.frameStart()
        D3D12RenderProf.begin(D3D12RenderProf.FRAME)
    }

    @Inject(method = ["renderFrame"], at = [At("RETURN")], remap = false)
    private fun D3D12ProfFrameReturn(advanceGameTime: Boolean, ci: CallbackInfo) {
        D3D12RenderProf.end(D3D12RenderProf.FRAME)
        D3D12RenderProf.frameEnd()
    }
}

