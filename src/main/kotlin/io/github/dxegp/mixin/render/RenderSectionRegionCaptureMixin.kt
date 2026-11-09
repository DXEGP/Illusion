package io.github.dxegp.mixin.render

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12SectionRegionState
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique

@Mixin(RenderSectionRegion::class)
abstract class RenderSectionRegionCaptureMixin : D3D12SectionRegionState {
    @field:Unique
    override var compileScope: D3D12SectionGeometry.CompileScope? = null
}

