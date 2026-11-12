package io.github.dxegp.mixin.terrain

import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometry
import io.github.dxegp.render.terrain.mesh.D3D12SectionGeometryOwner
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(CompiledSectionMesh::class)
abstract class CompiledSectionGeometryMixin : D3D12SectionGeometryOwner {
    @Unique
    private val meshGeometry = D3D12SectionGeometry()

    @get:Unique
    override val d3d12Geometry: D3D12SectionGeometry get() = meshGeometry

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun D3D12RetireGeometry(callback: CallbackInfo) {
        meshGeometry.retire()
    }
}
