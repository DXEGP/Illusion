package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtFrame
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.core.SectionPos
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelExtractor::class)
abstract class LevelExtractorGeometryStateMixin {
    @Shadow private var level: ClientLevel? = null
    @Shadow @Final private lateinit var levelRenderer: LevelRenderer
    @Unique private var `dxr_mc$dirtyRange`: LongOpenHashSet? = null

    @Inject(method = ["setLevel"], at = [At("TAIL")])
    private fun `dxr_mc$observeLevel`(nextLevel: ClientLevel?, callback: CallbackInfo) {
        D3D12RtFrame.onLevelChanged(nextLevel)
        if (nextLevel == null) levelRenderer.resetLevelRenderData()
    }

    @WrapMethod(method = ["setSectionRangeDirty(IIIIII)V"])
    private fun `dxr_mc$invalidateSectionRange`(
        minimumX: Int, minimumY: Int, minimumZ: Int, maximumX: Int, maximumY: Int, maximumZ: Int,
        original: Operation<Void?>,
    ) {
        val nodes = LongOpenHashSet()
        for (sectionZ in minimumZ..maximumZ) {
            for (sectionX in minimumX..maximumX) {
                for (sectionY in minimumY..maximumY) nodes.add(SectionPos.asLong(sectionX, sectionY, sectionZ))
            }
        }
        D3D12SectionGeometry.invalidateCompileNotifications(nodes.toLongArray(), level)
        val previous = `dxr_mc$dirtyRange`
        `dxr_mc$dirtyRange` = nodes
        try {
            original.call(minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ)
        } finally {
            `dxr_mc$dirtyRange` = previous
        }
    }

    @Inject(method = ["setSectionDirty(IIIZ)V"], at = [At("HEAD")])
    private fun `dxr_mc$invalidateSection`(
        sectionX: Int, sectionY: Int, sectionZ: Int, fromPlayer: Boolean, callback: CallbackInfo
    ) {
        val node = SectionPos.asLong(sectionX, sectionY, sectionZ)
        if (`dxr_mc$dirtyRange`?.contains(node) != true) {
            D3D12SectionGeometry.invalidateCompileNotifications(longArrayOf(node), level)
        }
    }
}

