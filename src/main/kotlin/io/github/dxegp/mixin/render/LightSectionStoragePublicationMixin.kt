package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.objects.ObjectIterator
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.lighting.DataLayerStorageMap
import net.minecraft.world.level.lighting.LayerLightSectionStorage
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(LayerLightSectionStorage::class)
abstract class LightSectionStoragePublicationMixin {
    @Shadow @Final
    private lateinit var queuedSections: Long2ObjectMap<DataLayer>

    @Inject(method = ["getDataLayerData(J)Lnet/minecraft/world/level/chunk/DataLayer;"], at = [At("HEAD")])
    private fun `dxr_mc$recordLayerRead`(sectionNode: Long, callback: CallbackInfoReturnable<DataLayer>) {
        D3D12RtModelSupport.recordLightLayerRead((this as LayerLightSectionStorage<*>), sectionNode)
    }

    @Inject(method = ["<init>"], at = [At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;queuedSections:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;")])
    private fun `dxr_mc$ownQueue`(callback: CallbackInfo) {
        D3D12SectionGeometry.ownLightQueue((this as LayerLightSectionStorage<*>), queuedSections)
    }

    @WrapOperation(method = ["queueSectionData", "markNewInconsistencies"], at = [At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;")])
    private fun `dxr_mc$putQueue`(
        queue: Long2ObjectMap<DataLayer>, sectionNode: Long, layer: Any?, original: Operation<Any?>
    ): Any? {
        D3D12SectionGeometry.beginLightQueueMutation((this as LayerLightSectionStorage<*>), sectionNode).use { publication ->
            val result = original.call(queue, sectionNode, layer)
            publication?.complete()
            return result
        }
    }

    @WrapOperation(method = ["queueSectionData", "markNewInconsistencies"], at = [At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;remove(J)Ljava/lang/Object;")])
    private fun `dxr_mc$removeQueue`(
        queue: Long2ObjectMap<DataLayer>, sectionNode: Long, original: Operation<Any?>
    ): Any? {
        D3D12SectionGeometry.beginLightQueueMutation((this as LayerLightSectionStorage<*>), sectionNode).use { publication ->
            val result = original.call(queue, sectionNode)
            publication?.complete()
            return result
        }
    }

    @WrapOperation(method = ["markNewInconsistencies"], at = [At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectIterator;remove()V")])
    private fun `dxr_mc$consumeQueue`(
        iterator: ObjectIterator<*>, original: Operation<Void?>, @Local(index = 4) sectionNode: Long
    ) {
        D3D12SectionGeometry.beginLightQueueMutation((this as LayerLightSectionStorage<*>), sectionNode).use { publication ->
            original.call(iterator)
            publication?.complete()
        }
    }

    @WrapOperation(method = ["swapSectionMap"], at = [At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;visibleSectionData:Lnet/minecraft/world/level/lighting/DataLayerStorageMap;")])
    private fun `dxr_mc$publishLightMap`(
        storage: LayerLightSectionStorage<*>, next: DataLayerStorageMap<*>, original: Operation<Void?>
    ) {
        D3D12SectionGeometry.publishLightMap(storage, next) { original.call(storage, next) }
    }
}

