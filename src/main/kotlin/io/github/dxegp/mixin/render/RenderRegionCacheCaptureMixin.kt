package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12SectionRegionState
import io.github.dxegp.misc.settings.D3D12Settings
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.chunk.RenderRegionCache
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionCopy
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique

@Mixin(RenderRegionCache::class)
abstract class RenderRegionCacheCaptureMixin {
    @Shadow @Final
    private lateinit var sectionCopyCache: Long2ObjectMap<SectionCopy>

    @Unique
    private var `dxr_mc$level`: ClientLevel? = null

    @Unique
    private var `dxr_mc$contentRevision` = 0L

    @WrapMethod(method = ["createRegion"])
    private fun `dxr_mc$captureScope`(
        level: ClientLevel, node: Long, original: Operation<RenderSectionRegion>
    ): RenderSectionRegion {
        val scope = if (D3D12Settings.rtEnabled) D3D12SectionGeometry.compileScope(node, level) else null
        val revision = D3D12SectionGeometry.contentRevision
        if (`dxr_mc$level` !== level || `dxr_mc$contentRevision` != revision) {
            sectionCopyCache.clear()
            `dxr_mc$level` = level
            `dxr_mc$contentRevision` = revision
        }
        val region = original.call(level, node)
        (region as D3D12SectionRegionState).compileScope = scope
        return region
    }
}

