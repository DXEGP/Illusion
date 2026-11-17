package io.github.dxegp.render.reconstruction.oit

import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.textures.GpuSampler
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import io.github.dxegp.d3d12.resource.D3D12GpuTexture

internal object OitTerrainMaterials {
    private class TextureVersion(val id: Long) {
        var generation = 1L
    }

    private data class MaterialKey(val textureId: Long, val viewId: Long, val samplerId: Long,
        val baseMip: Int, val mipLevels: Int, val sampler: OitSurfaceSampler)

    private data class TerrainMaterialKey(val material: OitSurfaceMaterial, val lightmap: OitSurfaceMaterial?)

    private val textures = WeakHashMap<GpuTexture, TextureVersion>()
    private val materials = LinkedHashMap<MaterialKey, Long>()
    private val terrainMaterials = LinkedHashMap<TerrainMaterialKey, Long>()
    private val views = WeakHashMap<GpuTextureView, Long>()
    private val samplers = WeakHashMap<GpuSampler, Long>()
    private val writtenTextures = LinkedHashMap<Long, WeakReference<GpuTexture>>()
    private var nextIdentity = 0L

    @Synchronized
    fun recordTextureUpload(texture: GpuTexture) {
        val version = textures.getOrPut(texture) { TextureVersion(Math.incrementExact(nextIdentity).also { nextIdentity = it }) }
        version.generation = Math.incrementExact(version.generation)
        val handle = (texture as? D3D12GpuTexture)?.handle() ?: return
        if (handle !in writtenTextures && writtenTextures.size >= 4096) {
            writtenTextures.remove(writtenTextures.keys.first())?.get()?.let(textures::remove)
        }
        writtenTextures[handle] = WeakReference(texture)
    }

    @Synchronized
    fun recordTextureWrite(handle: Long) {
        val texture = writtenTextures[handle]?.get() ?: return
        val version = textures[texture] ?: return
        version.generation = Math.incrementExact(version.generation)
    }

    @Synchronized
    fun invalidateTexture(texture: GpuTexture) {
        val nativeTexture = texture as? D3D12GpuTexture ?: return
        textures.remove(nativeTexture) ?: return
        val handle = nativeTexture.handle()
        if (writtenTextures[handle]?.get() === nativeTexture) writtenTextures.remove(handle)
    }

    @Synchronized
    fun recordTextureClear(texture: GpuTexture) {
        invalidateTexture(texture)
        recordTextureUpload(texture)
    }

    @Synchronized
    fun capture(view: GpuTextureView, sampler: GpuSampler): OitSurfaceMaterial? {
        val texture = textures[view.texture()] ?: return null
        val viewId = views.getOrPut(view) { Math.incrementExact(nextIdentity).also { nextIdentity = it } }
        val samplerId = samplers.getOrPut(sampler) { Math.incrementExact(nextIdentity).also { nextIdentity = it } }
        val key = MaterialKey(texture.id, viewId, samplerId, view.baseMipLevel(), view.mipLevels(), OitSurfaceSampler(sampler))
        val identity = materials[key] ?: run {
            if (materials.size >= 4096) materials.remove(materials.keys.first())
            Math.incrementExact(nextIdentity).also { nextIdentity = it; materials[key] = it }
        }
        return OitSurfaceMaterial(identity, identity, texture.generation)
    }

    @Synchronized
    fun captureTerrain(material: OitSurfaceMaterial, lightmap: OitSurfaceMaterial?): OitSurfaceMaterial {
        val key = TerrainMaterialKey(material, lightmap)
        val identity = terrainMaterials[key] ?: run {
            if (terrainMaterials.size >= 4096) terrainMaterials.remove(terrainMaterials.keys.first())
            Math.incrementExact(nextIdentity).also { nextIdentity = it; terrainMaterials[key] = it }
        }
        return OitSurfaceMaterial(identity, identity, material.textureGeneration)
    }
}
