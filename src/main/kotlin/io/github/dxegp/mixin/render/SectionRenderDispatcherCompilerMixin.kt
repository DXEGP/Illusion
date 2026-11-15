package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import org.spongepowered.asm.mixin.Mixin

@Mixin(SectionRenderDispatcher::class)
abstract class SectionRenderDispatcherCompilerMixin {
    @WrapMethod(method = ["setCompiler"])
    private fun `dxr_mc$publishCompiler`(compiler: SectionCompiler, original: Operation<Void?>) {
        D3D12SectionGeometry.publishCompiler((this as SectionRenderDispatcher), compiler) { original.call(compiler) }
    }
}

