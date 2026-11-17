package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.renderpearl.api.commands.RenderPass
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import net.minecraft.client.renderer.oit.OitRenderPassProvider
import net.minecraft.client.renderer.oit.OitStage
import org.spongepowered.asm.mixin.Mixin
import java.util.function.Supplier

@Mixin(OitRenderPassProvider::class)
abstract class OitRenderPassProviderMixin {
    private companion object {
        @JvmStatic
        @WrapMethod(method = ["createRenderPass(Lnet/minecraft/client/renderer/oit/OitStage;Ljava/util/function/Supplier;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider\$Parameters;)Lcom/mojang/renderpearl/api/commands/RenderPass;"])
        private fun illusionRecordStage(stage: OitStage, label: Supplier<String>,
            parameters: OitRenderPassProvider.Parameters, original: Operation<RenderPass>): RenderPass =
            OitProducerCapture.recordProviderStage(stage) { original.call(stage, label, parameters) }
    }
}
