package io.github.dxegp.mixin.reconstruction

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.commands.RenderPass
import io.github.dxegp.render.reconstruction.oit.OitSurfaceInputs
import io.github.dxegp.render.reconstruction.oit.OitWeatherRandom
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance
import net.minecraft.client.renderer.state.level.WeatherRenderState
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(WeatherEffectRenderer::class)
abstract class WeatherEffectRendererTemporalMixin {
    @ModifyExpressionValue(method = ["createRainColumnInstance"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/util/RandomSource;nextFloat()F")])
    private fun illusionCaptureRainRandom(value: Float): Float {
        OitSurfaceInputs.captureWeatherRandom(this, OitWeatherRandom.RAIN_SPEED, value.toDouble())
        return value
    }

    @WrapMethod(method = ["extractRenderState"])
    private fun illusionRecordExtraction(level: ClientLevel, partialTick: Float, camera: Vec3,
        state: WeatherRenderState, original: Operation<Any?>) {
        OitSurfaceInputs.recordWeatherExtraction(this, state) {
            original.call(level, partialTick, camera, state)
        }
    }

    @Inject(method = ["createRainColumnInstance"], at = [At("RETURN")])
    private fun illusionCaptureRain(random: RandomSource, ticks: Long, x: Int, bottom: Int, top: Int,
        z: Int, light: Int, partialTick: Float, callback: CallbackInfoReturnable<ColumnInstance>,
        @Local(index = 12) speed: Float, @Local(index = 13) rawV: Float) {
        OitSurfaceInputs.captureRainPhase(this, callback.returnValue, ticks, partialTick, rawV, speed)
    }

    @ModifyExpressionValue(method = ["createSnowColumnInstance"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/util/RandomSource;nextDouble()D", ordinal = 0)])
    private fun illusionCaptureSnowDoubleU(value: Double): Double {
        OitSurfaceInputs.captureWeatherRandom(this, OitWeatherRandom.SNOW_DOUBLE_U, value)
        return value
    }

    @ModifyExpressionValue(method = ["createSnowColumnInstance"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/util/RandomSource;nextDouble()D", ordinal = 1)])
    private fun illusionCaptureSnowDoubleV(value: Double): Double {
        OitSurfaceInputs.captureWeatherRandom(this, OitWeatherRandom.SNOW_DOUBLE_V, value)
        return value
    }

    @ModifyExpressionValue(method = ["createSnowColumnInstance"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/util/RandomSource;nextGaussian()D", ordinal = 0)])
    private fun illusionCaptureSnowGaussianU(value: Double): Double {
        OitSurfaceInputs.captureWeatherRandom(this, OitWeatherRandom.SNOW_GAUSSIAN_U, value)
        return value
    }

    @ModifyExpressionValue(method = ["createSnowColumnInstance"], at = [At(value = "INVOKE",
        target = "Lnet/minecraft/util/RandomSource;nextGaussian()D", ordinal = 1)])
    private fun illusionCaptureSnowGaussianV(value: Double): Double {
        OitSurfaceInputs.captureWeatherRandom(this, OitWeatherRandom.SNOW_GAUSSIAN_V, value)
        return value
    }

    @Inject(method = ["createSnowColumnInstance"], at = [At("RETURN")])
    private fun illusionCaptureSnow(random: RandomSource, ticks: Long, x: Int, bottom: Int, top: Int,
        z: Int, light: Int, partialTick: Float, callback: CallbackInfoReturnable<ColumnInstance>,
        @Local(index = 12) actualU: Float, @Local(index = 13) actualV: Float, @Local(index = 14) fallV: Float) {
        OitSurfaceInputs.captureSnowPhase(this, callback.returnValue, ticks, partialTick, actualU, actualV, fallV)
    }

    @WrapMethod(method = ["prepare"])
    private fun illusionRecordPreparation(camera: Vec3, state: WeatherRenderState, original: Operation<Any?>) {
        OitSurfaceInputs.recordWeatherPreparation(this, state, camera) { original.call(camera, state) }
    }

    @WrapMethod(method = ["prepareInstances"])
    private fun illusionRecordInstances(builder: VertexConsumer, columns: List<ColumnInstance>, camera: Vec3,
        maxAlpha: Float, radius: Int, intensity: Float, original: Operation<Any?>) {
        OitSurfaceInputs.recordWeatherInstances(this, columns) {
            original.call(builder, columns, camera, maxAlpha, radius, intensity)
        }
    }

    @Inject(method = ["prepareInstances"], at = [At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        ordinal = 0)])
    private fun illusionCaptureQuad(callback: CallbackInfo, @Local(index = 9) column: ColumnInstance,
        @Local(index = 14) color: Int,
        @Local(index = 18) x0: Float, @Local(index = 19) x1: Float,
        @Local(index = 20) top: Float, @Local(index = 21) bottom: Float,
        @Local(index = 22) z0: Float, @Local(index = 23) z1: Float,
        @Local(index = 24) u0: Float, @Local(index = 25) u1: Float,
        @Local(index = 26) v0: Float, @Local(index = 27) v1: Float) {
        OitSurfaceInputs.captureWeatherQuad(this, column, color, x0, x1, top, bottom, z0, z1, u0, u1, v0, v1)
    }

    @WrapMethod(method = ["renderWeather"])
    private fun illusionRecordBatch(pass: RenderPass, texture: AbstractTexture, startColumn: Int, columnCount: Int,
        original: Operation<Any?>) {
        OitSurfaceInputs.recordWeatherBatch(this, startColumn, columnCount, texture.textureView, texture.sampler) {
            original.call(pass, texture, startColumn, columnCount)
        }
    }

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun illusionCloseSurfaces(callback: CallbackInfo) {
        OitSurfaceInputs.closeProducer(this)
    }
}
