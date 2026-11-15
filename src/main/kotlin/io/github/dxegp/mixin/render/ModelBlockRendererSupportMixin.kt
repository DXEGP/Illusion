package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport
import io.github.dxegp.experimental.raytracing.D3D12RtBlockExtensions
import io.github.dxegp.experimental.raytracing.D3D12RtBlockTintInputs
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At

@Mixin(ModelBlockRenderer::class)
abstract class ModelBlockRendererSupportMixin {
    @Shadow @Final
    private lateinit var blockColors: BlockColors

    @Shadow
    private var tintSourcesInitialized = false

    @Unique
    private var `dxr_mc$tintInputs`: List<BlockTintSource>? = null

    @Unique
    private var `dxr_mc$tintState`: BlockState? = null

    @Unique
    private var `dxr_mc$dynamicTintKnown` = false

    @Unique
    private var `dxr_mc$dynamicTintUsed` = false

    @Unique
    private var `dxr_mc$dynamicTintInput`: D3D12RtBlockExtensions.Input? = null

    @Unique
    private var `dxr_mc$staticTintInputs`: Int2ObjectOpenHashMap<D3D12RtBlockTintInputs.Input>? = null

    @Unique
    private var `dxr_mc$currentTintIndex` = 0

    @WrapMethod(method = ["getTintColor"])
    private fun `dxr_mc$checkTintCache`(
        level: BlockAndTintGetter, state: BlockState, position: BlockPos, index: Int, original: Operation<Int>
    ): Int {
        if (!tintSourcesInitialized) {
            `dxr_mc$tintInputs` = null
            `dxr_mc$dynamicTintKnown` = true
            `dxr_mc$dynamicTintUsed` = false
            `dxr_mc$dynamicTintInput` = null
            val inputs = `dxr_mc$staticTintInputs`
            if (inputs == null) `dxr_mc$staticTintInputs` = Int2ObjectOpenHashMap()
            else inputs.clear()
        }
        val previousIndex = `dxr_mc$currentTintIndex`
        `dxr_mc$currentTintIndex` = index
        try {
            return original.call(level, state, position, index)
        } finally {
            D3D12SectionGeometry.recordBlockTintSlot(
                blockColors, state, `dxr_mc$tintInputs`.takeIf { `dxr_mc$tintState` === state },
                index, `dxr_mc$dynamicTintKnown`
            )
            if (`dxr_mc$dynamicTintUsed`) {
                D3D12SectionGeometry.recordBlockTintExtensionInput(blockColors, state, `dxr_mc$dynamicTintInput`)
            }
            D3D12SectionGeometry.recordBlockTintInput(
                state, `dxr_mc$tintInputs`, index, level, position, `dxr_mc$staticTintInputs`?.get(index)
            )
            `dxr_mc$currentTintIndex` = previousIndex
        }
    }

    @WrapOperation(
        method = ["configureTintCache"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockColors;" +
                "getTintSources(Lnet/minecraft/world/level/block/state/BlockState;)Ljava/util/List;")]
    )
    private fun `dxr_mc$captureTintInputs`(
        colors: BlockColors, state: BlockState, original: Operation<List<BlockTintSource>?>
    ): List<BlockTintSource>? {
        `dxr_mc$tintState` = state
        val inputs = original.call(colors, state)
        `dxr_mc$tintInputs` = inputs
        return inputs
    }

    @WrapOperation(
        method = ["computeTintColor"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockTintSource;" +
                "colorInWorld(Lnet/minecraft/world/level/block/state/BlockState;" +
                "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I")]
    )
    private fun `dxr_mc$checkTintSource`(
        source: BlockTintSource, state: BlockState, level: BlockAndTintGetter,
        position: BlockPos, original: Operation<Int>
    ): Int {
        D3D12SectionGeometry.recordBlockTintSource(blockColors, source)
        val calculation = D3D12SectionGeometry.beginBlockTintSource(source, state, level, position)
        var completed = false
        try {
            val value = original.call(source, state, level, position)
            completed = true
            return value
        } finally {
            val input = D3D12SectionGeometry.finishBlockTintSource(calculation, completed)
            `dxr_mc$staticTintInputs`?.put(`dxr_mc$currentTintIndex`, input)
        }
    }

    @WrapOperation(
        method = ["computeTintColor"],
        at = [At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/extensions/common/IClientBlockExtensions;" +
                "collectDynamicTintValues(Lnet/minecraft/world/level/block/state/BlockState;" +
                "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;" +
                "Lit/unimi/dsi/fastutil/ints/IntList;)V",
            remap = false)]
    )
    private fun `dxr_mc$checkDynamicTint`(
        extension: IClientBlockExtensions, state: BlockState, level: BlockAndTintGetter,
        position: BlockPos, output: IntList, original: Operation<Void?>
    ) {
        `dxr_mc$dynamicTintKnown` = extension === IClientBlockExtensions.DEFAULT
        `dxr_mc$dynamicTintUsed` = true
        `dxr_mc$dynamicTintInput` = D3D12SectionGeometry.recordBlockTintExtension(blockColors, state, extension)
        if (!`dxr_mc$dynamicTintKnown`) D3D12SectionGeometry.recordBlockTintSource(blockColors, null)
        original.call(extension, state, level, position, output)
    }

    @WrapOperation(
        method = ["tesselateBlock"],
        at = [At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;" +
                "getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;")]
    )
    private fun `dxr_mc$capturePositionOffset`(
        state: BlockState, position: BlockPos, original: Operation<Vec3>
    ): Vec3 {
        val offset = original.call(state, position)
        D3D12RtModelSupport.recordOffset(offset)
        return offset
    }
}

