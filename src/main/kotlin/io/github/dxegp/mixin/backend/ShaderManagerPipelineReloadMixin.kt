package io.github.dxegp.mixin.backend

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.llamalad7.mixinextras.sugar.Share
import com.llamalad7.mixinextras.sugar.ref.LocalRef
import com.mojang.blaze3d.pipeline.PipelineCache
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.ShaderSource
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.shader.D3D12PipelineReloadRequest
import io.github.dxegp.d3d12.shader.FrontendReloadInstall
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.ShaderManager
import org.spongepowered.asm.mixin.Mixin
import org.slf4j.LoggerFactory
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.function.Function

@Mixin(ShaderManager::class)
abstract class ShaderManagerPipelineReloadMixin {
    @WrapOperation(method = ["reload"], at = [At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;thenComposeAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")])
    private fun illusionStageReload(
        configs: CompletableFuture<ShaderManager.Configs>,
        compile: Function<ShaderManager.Configs, CompletionStage<Any>>,
        executor: Executor,
        original: Operation<CompletableFuture<Any>>,
        @Local device: GpuDevice,
        @Share("illusionReloadFailure") cleanup: LocalRef<Runnable>,
    ): CompletableFuture<Any> {
        val backend = (device as? FrontendGpuDeviceBackendAccessor)?.illusionBackend() as? D3D12Device
            ?: return original.call(configs, compile, executor)
        val pipelines = backend.pipelines
        val request = pipelines.requestPipelineReload()
        cleanup.set(Runnable { pipelines.abortPipelineReloadRequest(request) })
        val staged = Function<ShaderManager.Configs, CompletionStage<Any>> { source ->
            val reload = try {
                pipelines.beginPipelineReload(D3D12PipelineReloadRequest(source, RenderPipelines.requiredPipelines(),
                    RenderPipelines.optionalPipelines(), request))
            } catch (failure: Throwable) {
                source.close()
                throw failure
            }
            try {
                compile.apply(source).whenComplete { _, failure ->
                    pipelines.completePipelineReload(reload)
                    if (failure != null) pipelines.abortPipelineReload(reload)
                }
            } catch (failure: Throwable) {
                pipelines.completePipelineReload(reload)
                pipelines.abortPipelineReload(reload)
                throw failure
            }
        }
        return original.call(configs, staged, executor)
    }

    @WrapOperation(method = ["apply"], at = [At(value = "NEW",
        target = "com/mojang/blaze3d/pipeline/PipelineCache")])
    private fun illusionValidateApply(device: GpuDevice, source: ShaderSource,
        original: Operation<PipelineCache>): PipelineCache {
        val backend = (device as? FrontendGpuDeviceBackendAccessor)?.illusionBackend() as? D3D12Device
        backend?.pipelines?.validatePipelineReload(source)
        return original.call(device, source)
    }

    @WrapOperation(method = ["apply"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ShaderManager\$PostChainCache;close()V")])
    private fun illusionPreparePrograms(
        oldPostChains: ShaderManager.PostChainCache,
        original: Operation<Unit>,
        @Local cache: PipelineCache,
        @Local candidatePostChains: ShaderManager.PostChainCache,
        @Share("illusionOldPostChains") retiredPostChains: LocalRef<ShaderManager.PostChainCache>,
        @Share("illusionClosePostChains") closeRetired: LocalRef<AutoCloseable>,
    ) {
        val frontend = cache as PipelineCacheReloadAccessor
        val backend = (frontend.illusionDevice() as? FrontendGpuDeviceBackendAccessor)?.illusionBackend()
            as? D3D12Device
        if (backend == null) {
            original.call(oldPostChains)
            return
        }
        retiredPostChains.set(oldPostChains)
        closeRetired.set(AutoCloseable { original.call(oldPostChains) })
        try {
            backend.pipelines.preparePipelineReloadPrograms(frontend.illusionShaderSource(),
                backend.createCommandEncoder() as D3D12CommandEncoderBackend)
        } catch (failure: Throwable) {
            for (resource in listOf(candidatePostChains, cache)) {
                try { resource.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    @Inject(method = ["reload"], at = [At("RETURN")])
    private fun illusionCleanFailedReload(
        callback: CallbackInfoReturnable<CompletableFuture<Void>>,
        @Share("illusionReloadFailure") cleanup: LocalRef<Runnable>,
    ) {
        val onFailure = cleanup.get() ?: return
        callback.returnValue.whenComplete { _, failure -> if (failure != null) onFailure.run() }
    }

    @WrapOperation(method = ["apply"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;setCurrentPipelineCache(Lcom/mojang/blaze3d/pipeline/PipelineCache;)Lcom/mojang/blaze3d/pipeline/PipelineCache;")])
    private fun illusionPublishReload(
        cache: PipelineCache,
        original: Operation<PipelineCache?>,
        @Share("illusionOldPostChains") retiredPostChains: LocalRef<ShaderManager.PostChainCache>,
        @Share("illusionClosePostChains") closeRetired: LocalRef<AutoCloseable>,
    ): PipelineCache? {
        val frontend = cache as PipelineCacheReloadAccessor
        val backend = (frontend.illusionDevice() as? FrontendGpuDeviceBackendAccessor)?.illusionBackend()
            as? D3D12Device ?: return original.call(cache)
        val manager = this as ShaderManagerPostChainsAccessor
        val oldPostChains = checkNotNull(retiredPostChains.get()) { "Old post-chain cache was not retained" }
        val newPostChains = manager.illusionPostChains()
        val oldPipelineCache = RenderSystem.currentPipelineCache
        val closePostChains = checkNotNull(closeRetired.get()) { "Old post-chain cleanup was not retained" }
        val installation = object : FrontendReloadInstall {
            override fun install() {
                original.call(cache)
            }

            override fun restore() {
                RenderSystem.currentPipelineCache = oldPipelineCache
                manager.illusionSetPostChains(oldPostChains)
            }

            override fun closeRetired() {
                closePostChains.close()
            }
        }
        try {
            backend.pipelines.publishPipelineReload(frontend.illusionShaderSource(), frontend.illusionPipelines().keys,
                installation)
        } catch (failure: Throwable) {
            try { installation.restore() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            for (resource in listOf(newPostChains, cache)) {
                try { resource.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        return oldPipelineCache
    }

    @WrapOperation(method = ["apply"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/pipeline/PipelineCache;close()V", ordinal = 1)])
    private fun illusionCloseRetiredPipelineCache(cache: PipelineCache, original: Operation<Unit>) {
        val frontend = cache as PipelineCacheReloadAccessor
        val backend = (frontend.illusionDevice() as? FrontendGpuDeviceBackendAccessor)?.illusionBackend()
            as? D3D12Device
        if (backend == null) {
            original.call(cache)
            return
        }
        try {
            original.call(cache)
        } catch (cleanup: Throwable) {
            LoggerFactory.getLogger("dxrmc").error("Could not close retired frontend pipeline cache", cleanup)
        }
    }
}
