package io.github.dxegp.mixin.render

import io.github.dxegp.misc.settings.D3D12Settings
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(GameRenderer::class)
abstract class GameRendererTransparencyMixin {
    @Inject(method = ["useImprovedTransparency"], at = [At("HEAD")], cancellable = true)
    private fun `dxr_mc$forceOitForMeshTerrain`(callback: CallbackInfoReturnable<Boolean>) {
        if (D3D12Settings.meshTerrain) callback.returnValue = true
    }
}

