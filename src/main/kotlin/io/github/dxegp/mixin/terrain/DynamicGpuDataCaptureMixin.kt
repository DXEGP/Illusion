package io.github.dxegp.mixin.terrain

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import net.minecraft.client.renderer.DynamicGpuData
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(DynamicGpuData::class)
abstract class DynamicGpuDataCaptureMixin {
    @Inject(method = ["writeChunkSectionCommands"], at = [At("RETURN")])
    private fun D3D12CaptureCommands(groups: List<List<DynamicGpuData.IndexedDraw>>,
                                    callback: CallbackInfoReturnable<Array<GpuBufferSlice>>) {
        D3D12MeshDrawCapture.captureBatches(callback.returnValue, groups)
    }
}
