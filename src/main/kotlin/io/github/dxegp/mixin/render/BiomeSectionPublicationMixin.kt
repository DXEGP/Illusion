package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import net.minecraft.core.Holder
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerRO
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(LevelChunkSection::class)
abstract class BiomeSectionPublicationMixin {
    @Inject(method = ["getNoiseBiome"], at = [At("HEAD")])
    private fun `dxr_mc$recordBiomeRead`(
        quartX: Int, quartY: Int, quartZ: Int, callback: CallbackInfoReturnable<Holder<Biome>>
    ) {
        D3D12RtModelSupport.recordBiomeRead(this as LevelChunkSection)
    }

    @WrapMethod(method = ["read"])
    private fun `dxr_mc$publishSectionBiomes`(buffer: FriendlyByteBuf, original: Operation<Void?>) {
        D3D12SectionGeometry.beginBiomeSectionPublication((this as LevelChunkSection), true).use { publication ->
            original.call(buffer)
            publication?.complete()
        }
    }

    @WrapMethod(method = ["readBiomes"])
    private fun `dxr_mc$publishBiomeSectionRead`(buffer: FriendlyByteBuf, original: Operation<Void?>) {
        D3D12SectionGeometry.beginBiomeSectionPublication((this as LevelChunkSection), true).use { publication ->
            original.call(buffer)
            publication?.complete()
        }
    }

    @WrapMethod(method = ["fillBiomesFromNoise"])
    private fun `dxr_mc$publishBiomeFill`(
        resolver: BiomeResolver, quartX: Int, quartY: Int, quartZ: Int, original: Operation<Void?>
    ) {
        D3D12SectionGeometry.beginBiomeSectionPublication((this as LevelChunkSection), true).use { publication ->
            original.call(resolver, quartX, quartY, quartZ)
            publication?.complete()
        }
    }

    @WrapOperation(method = ["read", "readBiomes", "fillBiomesFromNoise"], at = [At(value = "FIELD",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;biomes:Lnet/minecraft/world/level/chunk/PalettedContainerRO;",
            opcode = Opcodes.PUTFIELD)])
    private fun `dxr_mc$publishBiomeIdentity`(
        section: LevelChunkSection, palette: PalettedContainerRO<Holder<Biome>>, original: Operation<Void?>
    ) {
        D3D12SectionGeometry.publishBiomePalette(section) { original.call(section, palette) }
    }
}

