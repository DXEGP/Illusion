package io.github.dxegp.mixin.render

import com.mojang.renderpearl.api.textures.GpuSampler
import io.github.dxegp.d3d12.render.advanced.terrain.D3D12TerrainSampler
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable

@Mixin(ChunkSectionsToRender::class)
abstract class ChunkSectionsToRenderSamplerMixin {
    @ModifyVariable(method = ["renderGroup"], at = [At("HEAD")], argsOnly = true)
    private fun `dxr_mc$biasGroupSampler`(sampler: GpuSampler): GpuSampler =
        D3D12TerrainSampler.wrap(sampler) ?: sampler

    @ModifyVariable(method = ["renderOit"], at = [At("HEAD")], argsOnly = true)
    private fun `dxr_mc$biasOitSampler`(sampler: GpuSampler): GpuSampler =
        D3D12TerrainSampler.wrap(sampler) ?: sampler
}

