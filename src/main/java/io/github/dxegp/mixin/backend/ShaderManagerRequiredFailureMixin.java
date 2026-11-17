package io.github.dxegp.mixin.backend;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.dxegp.d3d12.device.D3D12Device;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 首次加载必需管线失败时报告崩溃，重载失败保留上一代已发布管线。
 *
 * <p>资源重载只在全部必需管线成功后发布新缓存；加载错误继续交给原版重载流程处理。
 *
 * <p>必须包 `NEW` 而不是改方法签名：`apply` 的参数类型 `ShaderManager$PendingResults` 是私有内部
 * record，Java 侧写不出来（用 `Object` 会被 Mixin 判为 invalid signature）。
 * 见 docs/misc/mixins-and-earlywindow.md §3.7
 */
@Mixin(ShaderManager.class)
public class ShaderManagerRequiredFailureMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("dxrmc/pipelines");

    private static final String REQUIRED_PREFIX = "Failed to load required shader programs";

    @WrapOperation(
        method = "apply(Lcom/mojang/renderpearl/api/device/GpuDevice;"
            + "Lnet/minecraft/client/renderer/ShaderManager$PendingResults;)V",
        at = @At(value = "NEW", target = "java/lang/RuntimeException"))
    private static RuntimeException dxr_mc$crashOnRequiredPipelineFailure(String message,
        Operation<RuntimeException> original) {
        RuntimeException thrown = original.call(message);
        if (message != null && message.startsWith(REQUIRED_PREFIX)) {
            if (RenderSystem.tryGetDevice() instanceof FrontendGpuDeviceBackendAccessor frontend
                && frontend.illusionBackend() instanceof D3D12Device backend
                && backend.getPipelines().hasPublishedPipelineCache()) {
                LOGGER.error("Required render pipeline reload failed; keeping the published pipeline cache.\n{}", message);
                return thrown;
            }
            LOGGER.error("Initial required render pipeline loading failed; no published pipeline cache is available.\n{}", message);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.delayCrash(CrashReport.forThrowable(thrown, "Loading required render pipelines"));
            }
        }
        return thrown;
    }
}
