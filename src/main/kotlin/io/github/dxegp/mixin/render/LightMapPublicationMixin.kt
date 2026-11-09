package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.lighting.DataLayerStorageMap
import org.spongepowered.asm.mixin.Mixin

@Mixin(DataLayerStorageMap::class)
abstract class LightMapPublicationMixin {
    @WrapMethod(method = ["setLayer"])
    private fun `dxr_mc$publishSet`(node: Long, layer: DataLayer, original: Operation<Void?>) {
        D3D12SectionGeometry.beginLightMapMutation((this as DataLayerStorageMap<*>), node, true).use { publication ->
            original.call(node, layer)
            publication.complete()
        }
    }

    @WrapMethod(method = ["removeLayer"])
    private fun `dxr_mc$publishRemove`(node: Long, original: Operation<DataLayer?>): DataLayer? {
        D3D12SectionGeometry.beginLightMapMutation((this as DataLayerStorageMap<*>), node, true).use { publication ->
            val result = original.call(node)
            publication.complete()
            return result
        }
    }

    @WrapMethod(method = ["copyDataLayer"])
    private fun `dxr_mc$publishCopy`(node: Long, original: Operation<DataLayer?>): DataLayer? {
        D3D12SectionGeometry.beginLightMapMutation((this as DataLayerStorageMap<*>), node, false).use { publication ->
            val result = original.call(node)
            publication.complete()
            return result
        }
    }
}

