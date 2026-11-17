package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.render.reconstruction.oit.OitProducer
import io.github.dxegp.render.reconstruction.oit.OitProducerCapture
import io.github.dxegp.render.reconstruction.oit.OitProducerKind
import io.github.dxegp.render.reconstruction.oit.OitExecutionEventKind
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import io.github.dxegp.render.reconstruction.oit.OitOriginalCompositeCall
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.render.presentation.LateWorldTargets
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft
import net.minecraft.client.CloudStatus
import net.minecraft.client.renderer.CloudRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.client.renderer.WorldBorderRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.oit.OitRenderPassProvider
import net.minecraft.client.renderer.oit.OitStage
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.client.renderer.state.level.WeatherRenderState
import net.minecraft.client.renderer.state.level.WorldBorderRenderState
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.CustomCloudsRenderer
import net.neoforged.neoforge.client.CustomWeatherEffectRenderer
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyArg
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier

@Mixin(LevelRenderer::class)
abstract class LevelRendererOitProducerMixin {
    @Shadow
    @Final
    private lateinit var targets: LevelTargetBundle

    @ModifyArg(method = ["render"], at = [At(value = "INVOKE", ordinal = 0,
        target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;importExternal(Ljava/lang/String;Ljava/lang/Object;)Lcom/mojang/blaze3d/resource/ResourceHandle;")], index = 1)
    private fun illusionImportLateWorldTarget(original: Any): Any =
        if (original is RenderTarget) LateWorldTargets.importWorldTarget(original) else original

    @Inject(method = ["executeOit"], at = [At("HEAD")])
    private fun illusionBeginOitRecords(callback: CallbackInfo) {
        val encoder = D3D12Device.sharedCommandEncoder() ?: return
        OitReconstructionInputs.captureOpaqueAndBeginRecords(encoder, Minecraft.getInstance())
    }

    @WrapOperation(method = ["executeOitWaterMask"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V")])
    private fun illusionRecordWaterDepthCopy(pass: RenderPass, vertexCount: Int, instanceCount: Int,
        firstVertex: Int, firstInstance: Int, original: Operation<Void?>) {
        OitProducerCapture.recordExecutionOperation(OitExecutionEventKind.WATER_DEPTH_COPY) {
            original.call(pass, vertexCount, instanceCount, firstVertex, firstInstance)
        }
    }

    @WrapOperation(method = ["executeOitWaterMask"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher\$PreparedFrame;executeWaterMask(Lcom/mojang/renderpearl/api/commands/RenderPass;)V")])
    private fun illusionRecordWaterMask(features: FeatureRenderDispatcher.PreparedFrame, pass: RenderPass,
        original: Operation<Void?>) {
        OitProducerCapture.recordExecutionOperation(OitExecutionEventKind.WATER_MASK) {
            original.call(features, pass)
        }
    }

    @WrapOperation(method = ["executeDepthBoundsCull"], at = [At(value = "INVOKE", ordinal = 0,
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V")])
    private fun illusionRecordDepthBoundsCopy(pass: RenderPass, vertexCount: Int, instanceCount: Int,
        firstVertex: Int, firstInstance: Int, original: Operation<Void?>) {
        OitProducerCapture.recordExecutionOperation(OitExecutionEventKind.DEPTH_BOUNDS_COPY) {
            original.call(pass, vertexCount, instanceCount, firstVertex, firstInstance)
        }
    }

    @WrapOperation(method = ["executeDepthBoundsCull"], at = [At(value = "INVOKE", ordinal = 1,
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V")])
    private fun illusionRecordDepthBoundsCull(pass: RenderPass, vertexCount: Int, instanceCount: Int,
        firstVertex: Int, firstInstance: Int, original: Operation<Void?>) {
        OitProducerCapture.recordExecutionOperation(OitExecutionEventKind.DEPTH_BOUNDS_CULL) {
            original.call(pass, vertexCount, instanceCount, firstVertex, firstInstance)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V")])
    private fun illusionRecordCompositeDraw(pass: RenderPass, vertexCount: Int, instanceCount: Int,
        firstVertex: Int, firstInstance: Int, original: Operation<Void?>) {
        OitProducerCapture.recordExecutionOperation(OitExecutionEventKind.COMPOSITE) {
            original.call(pass, vertexCount, instanceCount, firstVertex, firstInstance)
        }
        OitReconstructionInputs.confirmCompositeDraw(pass, vertexCount, instanceCount, firstVertex, firstInstance)
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;")])
    private fun illusionPrepareOitComposite(encoder: CommandEncoder, label: Supplier<String>, color: GpuTextureView,
        colorLoad: Optional<*>, depth: GpuTextureView, depthLoad: OptionalDouble, original: Operation<RenderPass>,
        @Local(index = 18) nearestSampler: GpuSampler, @Local(index = 19) renderTarget: RenderTarget,
        @Local(index = 12) accumulateTargetView: GpuTextureView,
        @Local(index = 13) transmittanceTargetViews: Array<GpuTextureView>,
        @Local(index = 10) depthBoundsTargetView: GpuTextureView): RenderPass {
        if (OitReconstructionInputs.terrainRecordFrames() == null)
            return original.call(encoder, label, color, colorLoad, depth, depthLoad)
        val input = OitOriginalCompositeCall(renderTarget, color, depth, nearestSampler,
            accumulateTargetView, transmittanceTargetViews.toList(), depthBoundsTargetView)
        val scene = VanillaFrameCoordinator.prepareOitComposite(input)
        val displayColor = scene?.colorTextureView ?: color
        val displayDepth = scene?.depthTextureView ?: depth
        val pass = original.call(encoder, label, displayColor, colorLoad, displayDepth, depthLoad)
        OitReconstructionInputs.confirmCompositePass(pass, displayColor, displayDepth)
        return pass
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V")])
    private fun illusionCompositeAccumulation(pass: RenderPass, name: String, originalView: GpuTextureView,
        sampler: GpuSampler, original: Operation<Void?>) {
        val view = if (name == "Sampler0" && LateWorldTargets.sceneTarget() != null)
            checkNotNull(OitReconstructionInputs.correctedTransparencyView()) else originalView
        original.call(pass, name, view, sampler)
        OitReconstructionInputs.confirmCompositeBinding(pass, name, originalView, view, sampler)
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V")])
    private fun illusionCompositePipeline(pass: RenderPass, pipeline: CompiledRenderPipeline,
        original: Operation<Void?>) {
        if (LateWorldTargets.sceneTarget() != null) OitReconstructionInputs.bindComposite(pass, pipeline)
        else {
            original.call(pass, pipeline)
            OitReconstructionInputs.confirmCompositePipeline(pass, pipeline)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderOit(Lcom/mojang/renderpearl/api/textures/GpuSampler;Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider\$Parameters;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V")])
    private fun illusionRecordTerrain(chunks: ChunkSectionsToRender, sampler: GpuSampler, stage: OitStage,
        parameters: OitRenderPassProvider.Parameters, atlas: GpuTextureView, lightmap: GpuTextureView,
        original: Operation<Void?>) {
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.TERRAIN, this), stage) {
            original.call(chunks, sampler, stage, parameters, atlas, lightmap)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher\$PreparedFrame;executeOit(Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")])
    private fun illusionRecordFeatures(features: FeatureRenderDispatcher.PreparedFrame, stage: OitStage,
        pass: RenderPass, original: Operation<Void?>) {
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.FEATURE, features), stage) {
            original.call(features, stage, pass)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/WorldBorderRenderer;renderOit(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lnet/minecraft/world/phys/Vec3;DLnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")])
    private fun illusionRecordBorder(renderer: WorldBorderRenderer, state: WorldBorderRenderState, camera: Vec3,
        renderDistance: Double, stage: OitStage, pass: RenderPass, original: Operation<Void?>) {
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.BORDER, renderer), stage) {
            original.call(renderer, state, camera, renderDistance, stage, pass)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;renderOit(Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")])
    private fun illusionRecordWeather(renderer: WeatherEffectRenderer, stage: OitStage, state: WeatherRenderState,
        pass: RenderPass, original: Operation<Void?>) {
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.WEATHER, renderer), stage) {
            original.call(renderer, stage, state, pass)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/CloudRenderer;renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider\$Parameters;)V")])
    private fun illusionRecordCloud(renderer: CloudRenderer, status: CloudStatus, stage: OitStage,
        mainDepth: GpuTextureView, parameters: OitRenderPassProvider.Parameters, original: Operation<Void?>) {
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.CLOUD, renderer), stage) {
            original.call(renderer, status, stage, mainDepth, parameters)
        }
    }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE", remap = false,
        target = "Lnet/neoforged/neoforge/client/CustomWeatherEffectRenderer;renderSnowAndRainOit(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)Z")])
    private fun illusionRecordCustomWeather(renderer: CustomWeatherEffectRenderer, level: LevelRenderState,
        weather: WeatherRenderState, camera: Vec3, stage: OitStage, pass: RenderPass,
        original: Operation<Boolean>): Boolean =
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.CUSTOM_WEATHER, renderer), stage) {
            original.call(renderer, level, weather, camera, stage, pass)
        }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE", remap = false,
        target = "Lnet/neoforged/neoforge/client/CustomCloudsRenderer;renderCloudsOit(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/CloudStatus;Lorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider\$Parameters;)Z")])
    private fun illusionRecordCustomCloud(renderer: CustomCloudsRenderer, level: LevelRenderState,
        status: CloudStatus, rotation: Matrix4fc, stage: OitStage, mainDepth: GpuTextureView,
        parameters: OitRenderPassProvider.Parameters, original: Operation<Boolean>): Boolean =
        OitProducerCapture.recordScope(OitProducer(OitProducerKind.CUSTOM_CLOUD, renderer), stage) {
            original.call(renderer, level, status, rotation, stage, mainDepth, parameters)
        }

    @WrapOperation(method = ["executeOit"], at = [At(value = "INVOKE", remap = false,
        target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;")])
    private fun illusionRecordCustomTranslucent(bus: IEventBus, event: Event, original: Operation<Event>): Event {
        if (event !is RenderLevelStageEvent.OitTranslucent) return original.call(bus, event)
        return OitProducerCapture.recordScope(OitProducer(OitProducerKind.CUSTOM_TRANSLUCENT, event), event.oitStage) {
            original.call(bus, event)
        }
    }
}
