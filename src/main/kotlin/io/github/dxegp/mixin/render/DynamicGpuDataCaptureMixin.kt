package io.github.dxegp.mixin.render

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12MeshDrawCapture
import net.minecraft.client.renderer.DynamicGpuData
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(DynamicGpuData::class)
abstract class DynamicGpuDataCaptureMixin {
    @Inject(method = ["writeChunkSectionCommands"], at = [At("HEAD")])
    private fun `dxr_mc$beginFrame`(
        groups: List<List<DynamicGpuData.IndexedDraw>>?,
        callback: CallbackInfoReturnable<Array<GpuBufferSlice?>>
    ) {
        D3D12MeshDrawCapture.beginFrame()
    }

    @Inject(method = ["writeChunkSectionCommands"], at = [At("RETURN")])
    private fun `dxr_mc$captureCommands`(
        groups: List<List<DynamicGpuData.IndexedDraw>>?,
        callback: CallbackInfoReturnable<Array<GpuBufferSlice?>>
    ) {
        val slices = callback.returnValue ?: return
        if (groups == null) return
        for (index in 0 until minOf(slices.size, groups.size)) {
            val slice = slices[index] ?: continue
            D3D12MeshDrawCapture.captureBatch(slice.buffer(), slice.offset(), groups[index])
        }
    }
}

