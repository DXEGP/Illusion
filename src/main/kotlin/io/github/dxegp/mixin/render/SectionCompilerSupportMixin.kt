package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.BlockQuadOutput
import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.block.FluidRenderer
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.neoforged.neoforge.client.fluid.CustomFluidRenderer
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At

@Mixin(SectionCompiler::class)
abstract class SectionCompilerSupportMixin {
    @Shadow @Final
    private var cutoutLeaves: Boolean = false

    @WrapOperation(method = ["compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler\$Results;"],
            at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V")])
    private fun `dxr_mc$captureModelSupport`(
        renderer: ModelBlockRenderer, output: BlockQuadOutput, originX: Float, originY: Float, originZ: Float,
        level: BlockAndTintGetter, position: BlockPos, state: BlockState, model: BlockStateModel, seed: Long,
        original: Operation<Void?>
    ) {
        D3D12RtModelSupport.beginModel(
                model, originX, originY, originZ, ModelBlockRenderer.forceOpaque(cutoutLeaves, state),
                !state.hasOffsetFunction()).use { placement ->
            original.call(renderer, output, originX, originY, originZ, level, position, state, model, seed)
        }
    }

    @WrapOperation(method = ["compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler\$Results;"],
            at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer\$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V")])
    private fun `dxr_mc$captureFluidSupport`(
        renderer: FluidRenderer, level: BlockAndTintGetter, position: BlockPos, output: FluidRenderer.Output,
        state: BlockState, fluid: FluidState, original: Operation<Void?>
    ) {
        D3D12RtModelSupport.recordFluid(renderer, level, position, state, fluid)
        original.call(renderer, level, position, output, state, fluid)
    }

    @WrapOperation(method = ["compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler\$Results;"],
            at = [At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/fluid/CustomFluidRenderer;renderFluid(Lnet/minecraft/client/renderer/block/FluidRenderer;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer\$Output;Lnet/minecraft/world/level/block/state/BlockState;)Z")])
    private fun `dxr_mc$captureCustomFluidSupport`(
        custom: CustomFluidRenderer, renderer: FluidRenderer, fluid: FluidState, level: BlockAndTintGetter,
        position: BlockPos, output: FluidRenderer.Output, state: BlockState, original: Operation<Boolean>
    ): Boolean {
        D3D12RtModelSupport.recordFluid()
        return original.call(custom, renderer, fluid, level, position, output, state)
    }
}

