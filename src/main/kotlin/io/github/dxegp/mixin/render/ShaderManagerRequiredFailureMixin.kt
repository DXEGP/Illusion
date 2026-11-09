package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import net.minecraft.CrashReport
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import io.github.dxegp.misc.IllusionLogger

@Mixin(ShaderManager::class)
abstract class ShaderManagerRequiredFailureMixin {
    @WrapOperation(
        method = ["apply(Lcom/mojang/renderpearl/api/device/GpuDevice;" +
            "Lnet/minecraft/client/renderer/ShaderManager\$PendingResults;)V"],
        at = [At(value = "NEW", target = "java/lang/RuntimeException")]
    )
    private fun `dxr_mc$crashOnRequiredPipelineFailure`(
        message: String?, original: Operation<RuntimeException>
    ): RuntimeException {
        val failure = original.call(message)
        if (message?.startsWith("Failed to load required shader programs") == true) {
            IllusionLogger.warnMessage("Required render pipelines failed")
            Minecraft.getInstance()?.delayCrash(CrashReport.forThrowable(failure, "Loading required render pipelines"))
        }
        return failure
    }
}

