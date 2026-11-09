package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.core.SectionPos
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelExtractor::class)
abstract class LevelExtractorGeometryStateMixin {
    @Shadow private var level: ClientLevel? = null

    @Inject(method = ["setLevel"], at = [At("TAIL")])
    private fun `dxr_mc$observeLevel`(nextLevel: ClientLevel?, callback: CallbackInfo) {
        D3D12SectionGeometry.observeLevel(nextLevel)
    }

    @Inject(method = ["setSectionDirty(IIIZ)V"], at = [At("HEAD")])
    private fun `dxr_mc$invalidateSection`(
        sectionX: Int, sectionY: Int, sectionZ: Int, fromPlayer: Boolean, callback: CallbackInfo
    ) {
        D3D12SectionGeometry.invalidateSection(SectionPos.asLong(sectionX, sectionY, sectionZ), level)
    }
}

