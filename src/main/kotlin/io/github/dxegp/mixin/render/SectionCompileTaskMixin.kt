package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12SectionTaskState
import io.github.dxegp.experimental.raytracing.D3D12SectionCaptureState
import io.github.dxegp.experimental.raytracing.D3D12SectionCompileState
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.renderer.SectionBufferBuilderPack
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.core.SectionPos
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(targets = ["net.minecraft.client.renderer.chunk.SectionRenderDispatcher\$RenderSection\$CompileTask"])
abstract class SectionCompileTaskMixin : D3D12SectionTaskState {
    @field:Unique
    override val captureState = D3D12SectionCaptureState()

    @WrapOperation(
        method = ["doTask"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;" +
                "compile(Lnet/minecraft/core/SectionPos;" +
                "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;" +
                "Lcom/mojang/blaze3d/vertex/VertexSorting;" +
                "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)" +
                "Lnet/minecraft/client/renderer/chunk/SectionCompiler\$Results;")]
    )
    private fun `dxr_mc$captureCompilerResources`(
        compiler: SectionCompiler, position: SectionPos, region: RenderSectionRegion, sorting: VertexSorting,
        buffers: SectionBufferBuilderPack,
        additionalRenderers: List<AddSectionGeometryEvent.AdditionalSectionRenderer>,
        original: Operation<SectionCompiler.Results>
    ): SectionCompiler.Results {
        if (captureState.compileScope == null) {
            return original.call(compiler, position, region, sorting, buffers, additionalRenderers)
        }
        captureState.captureCompiler(compiler)
        val renderers = additionalRenderers.toList()
        captureState.prepareSourceSupport(
            position, region, compiler.javaClass == SectionCompiler::class.java, renderers.isNotEmpty()
        )
        try {
            captureState.beginSupportSession(
                region, compiler.javaClass == SectionCompiler::class.java, renderers.isNotEmpty()
            ).use { support ->
                val result = original.call(compiler, position, region, sorting, buffers, renderers)
                captureState.captureProducerManifest(support.finish())
                return result
            }
        } catch (failure: Throwable) {
            captureState.cancelSourceSupport()
            throw failure
        }
    }

    @Inject(method = ["cancel"], at = [At("HEAD")])
    private fun `dxr_mc$cancelSourceSupport`(callback: CallbackInfo) {
        captureState.cancelSourceSupport()
    }

    @ModifyExpressionValue(
        method = ["doTask"],
        at = [At(value = "NEW", target = "net/minecraft/client/renderer/chunk/CompiledSectionMesh")]
    )
    private fun `dxr_mc$trackResult`(mesh: CompiledSectionMesh): CompiledSectionMesh {
        captureState.capture(mesh)
        return mesh
    }

    @WrapOperation(
        method = ["doTask"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher\$RenderSection;" +
                "setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)" +
                "Lnet/minecraft/client/renderer/chunk/SectionMesh;")]
    )
    private fun `dxr_mc$trackEmptyResult`(
        section: SectionRenderDispatcher.RenderSection, mesh: SectionMesh, original: Operation<SectionMesh>
    ): SectionMesh = synchronized(section) {
        captureState.capture(mesh)
        if ((section as D3D12SectionCompileState).compileTaskIdentity !== this ||
            (this as SectionCompileTaskAccessor).`dxr_mc$isCancelled`().get()) {
            return@synchronized mesh
        }
        original.call(section, mesh)
    }
}

