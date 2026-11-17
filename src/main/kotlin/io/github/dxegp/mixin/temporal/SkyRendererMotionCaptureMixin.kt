package io.github.dxegp.mixin.temporal

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPass
import io.github.dxegp.render.temporal.celestial.CelestialDrawSource
import io.github.dxegp.render.temporal.celestial.CelestialMotionCapture
import io.github.dxegp.render.temporal.celestial.CelestialRole
import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.client.renderer.SkyRenderer
import net.minecraft.world.level.MoonPhase
import org.joml.Matrix4f
import org.joml.Vector4f
import org.joml.Vector4fc
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At

@Mixin(SkyRenderer::class)
abstract class SkyRendererMotionCaptureMixin {
    @Shadow @Final private lateinit var sunBuffer: GpuBuffer
    @Shadow @Final private lateinit var moonBuffer: GpuBuffer
    @Shadow @Final private lateinit var starBuffer: GpuBuffer
    @Shadow @Final private lateinit var sunriseBuffer: GpuBuffer

    @WrapMethod(method = ["renderSun"])
    private fun illusionRecordSun(pass: RenderPass, brightness: Float, pose: PoseStack, original: Operation<Void?>) {
        CelestialMotionCapture.recordDraw(CelestialDrawSource(this, CelestialRole.SUN, sunBuffer)) {
            original.call(pass, brightness, pose)
        }
    }

    @WrapMethod(method = ["renderMoon"])
    private fun illusionRecordMoon(pass: RenderPass, phase: MoonPhase, brightness: Float, pose: PoseStack,
        original: Operation<Void?>) {
        CelestialMotionCapture.recordDraw(CelestialDrawSource(this, CelestialRole.MOON, moonBuffer, phase)) {
            original.call(pass, phase, brightness, pose)
        }
    }

    @WrapMethod(method = ["renderStars"])
    private fun illusionRecordStars(pass: RenderPass, brightness: Float, pose: PoseStack, original: Operation<Void?>) {
        CelestialMotionCapture.recordDraw(CelestialDrawSource(this, CelestialRole.STARS, starBuffer)) {
            original.call(pass, brightness, pose)
        }
    }

    @WrapMethod(method = ["renderSunriseAndSunset"])
    private fun illusionRecordSunrise(pass: RenderPass, pose: PoseStack, angle: Float, color: Vector4fc,
        original: Operation<Void?>) {
        CelestialMotionCapture.recordDraw(CelestialDrawSource(this, CelestialRole.SUNRISE_SUNSET, sunriseBuffer)) {
            original.call(pass, pose, angle, color)
        }
    }

    @WrapOperation(method = ["renderSun", "renderMoon", "renderStars", "renderSunriseAndSunset"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")])
    private fun illusionRecordTransform(uniforms: DynamicGpuData, modelView: Matrix4f, color: Vector4f,
        original: Operation<GpuBufferSlice>): GpuBufferSlice {
        CelestialMotionCapture.recordTransform(modelView)
        return original.call(uniforms, modelView, color)
    }
}
