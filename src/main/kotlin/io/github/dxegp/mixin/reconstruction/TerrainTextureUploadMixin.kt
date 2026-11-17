package io.github.dxegp.mixin.reconstruction

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.render.reconstruction.oit.OitTerrainMaterials
import org.joml.Vector4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer

@Mixin(D3D12CommandEncoderBackend::class, remap = false)
abstract class TerrainTextureUploadMixin {
    @Inject(method = ["clearColorTexture"], at = [At("RETURN")], remap = false)
    private fun recordColorClear(texture: GpuTexture, color: Vector4fc, callback: CallbackInfo) {
        OitTerrainMaterials.recordTextureClear(texture)
    }

    @Inject(method = ["clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"],
        at = [At("RETURN")], remap = false)
    private fun recordColorAndDepthClear(colorTexture: GpuTexture, color: Vector4fc,
        depthTexture: GpuTexture, depth: Double, callback: CallbackInfo) {
        OitTerrainMaterials.recordTextureClear(colorTexture)
    }

    @Inject(method = ["clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V"],
        at = [At("RETURN")], remap = false)
    private fun recordColorAndDepthRegionClear(colorTexture: GpuTexture, color: Vector4fc,
        depthTexture: GpuTexture, depth: Double, regionX: Int, regionY: Int, regionWidth: Int,
        regionHeight: Int, mipLevel: Int, callback: CallbackInfo) {
        OitTerrainMaterials.invalidateTexture(colorTexture)
    }

    @Inject(method = ["writeToTexture"], at = [At("RETURN")], remap = false)
    private fun recordTextureUpload(destination: GpuTexture, source: ByteBuffer, mipLevel: Int,
        depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int, callback: CallbackInfo) {
        OitTerrainMaterials.recordTextureUpload(destination)
    }

    @Inject(method = ["copyBufferToTexture"], at = [At("RETURN")], remap = false)
    private fun recordBufferUpload(source: GpuBufferSlice, sourceX: Int, sourceY: Int,
        sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int,
        destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int, callback: CallbackInfo) {
        OitTerrainMaterials.recordTextureUpload(destination)
    }

    @Inject(method = ["copyTextureToTexture"], at = [At("RETURN")], remap = false)
    private fun recordTextureCopy(source: GpuTexture, destination: GpuTexture, mipLevel: Int,
        destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int, callback: CallbackInfo) {
        OitTerrainMaterials.recordTextureUpload(destination)
    }
}
