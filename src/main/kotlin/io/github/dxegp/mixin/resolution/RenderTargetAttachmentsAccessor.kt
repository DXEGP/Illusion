package io.github.dxegp.mixin.resolution

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(RenderTarget::class)
interface RenderTargetAttachmentsAccessor {
    @get:Accessor("colorTexture")
    @set:Accessor("colorTexture")
    var illusionColorTexture: GpuTexture?

    @get:Accessor("colorTextureView")
    @set:Accessor("colorTextureView")
    var illusionColorTextureView: GpuTextureView?

    @get:Accessor("depthTexture")
    @set:Accessor("depthTexture")
    var illusionDepthTexture: GpuTexture?

    @get:Accessor("depthTextureView")
    @set:Accessor("depthTextureView")
    var illusionDepthTextureView: GpuTextureView?
}
