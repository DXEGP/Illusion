package io.github.dxegp.mixin.backend

import com.mojang.blaze3d.pipeline.PipelineCache
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.backend.api.GpuDeviceBackend
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import net.minecraft.client.renderer.ShaderManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(FrontendGpuDevice::class)
interface FrontendGpuDeviceBackendAccessor {
    @Accessor("backend")
    fun illusionBackend(): GpuDeviceBackend
}

@Mixin(PipelineCache::class)
interface PipelineCacheReloadAccessor {
    @Accessor("device")
    fun illusionDevice(): GpuDevice

    @Accessor("shaderSource")
    fun illusionShaderSource(): ShaderSource

    @Accessor("cache")
    fun illusionPipelines(): Map<RenderPipeline, CompiledRenderPipeline>
}

@Mixin(ShaderManager::class)
interface ShaderManagerPostChainsAccessor {
    @Accessor("postChains")
    fun illusionPostChains(): ShaderManager.PostChainCache

    @Accessor("postChains")
    fun illusionSetPostChains(cache: ShaderManager.PostChainCache)
}
