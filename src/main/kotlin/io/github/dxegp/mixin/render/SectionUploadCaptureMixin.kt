package io.github.dxegp.mixin.render

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import io.github.dxegp.experimental.raytracing.D3D12SectionCompileState
import io.github.dxegp.experimental.raytracing.D3D12SectionTaskState
import io.github.dxegp.experimental.raytracing.D3D12SectionCaptureState
import io.github.dxegp.experimental.raytracing.D3D12SectionRegionState
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.world.level.Level
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.nio.ByteBuffer
import java.lang.ref.WeakReference

@Mixin(targets = ["net.minecraft.client.renderer.chunk.SectionRenderDispatcher\$RenderSection"])
abstract class SectionUploadCaptureMixin : D3D12SectionCompileState {
    @Unique @Volatile
    private var `dxr_mc$compileTask`: WeakReference<SectionRenderDispatcher.RenderSection.SectionTask>? = null

    override val compileTaskIdentity: Any?
        get() = `dxr_mc$compileTask`?.get()

    override fun hasPendingCompile(): Boolean {
        val task = `dxr_mc$compileTask`?.get() ?: return false
        return !(task as SectionCompileTaskAccessor).`dxr_mc$isCancelled`().get() &&
            !(task as D3D12SectionTaskState).captureState.installed
    }

    @Inject(
        method = ["createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Ljava/util/List;)" +
            "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher\$RenderSection\$SectionTask;"],
        at = [At("RETURN")]
    )
    private fun `dxr_mc$trackCompileTask`(
        region: RenderSectionRegion, additionalRenderers: List<*>?,
        callback: CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask>
    ) {
        synchronized(this) {
            val level = Minecraft.getInstance().level
            D3D12SectionGeometry.observeLevel(level)
            val task = callback.returnValue
            val state = (task as D3D12SectionTaskState).captureState
            state.bind(getSectionNode(), level, D3D12SectionGeometry.epoch(),
                (region as D3D12SectionRegionState).compileScope)
            state.bindSourceRegion(region)
            `dxr_mc$compileTask` = WeakReference(task)
        }
    }

    @Shadow
    abstract fun getSectionNode(): Long

    @Shadow
    abstract fun getSectionMesh(): SectionMesh

    @Inject(method = ["addSectionBuffersToUberBuffer"], at = [At("HEAD")])
    private fun `dxr_mc$captureUpload`(
        layer: ChunkSectionLayer, mesh: CompiledSectionMesh,
        vertexBuffer: ByteBuffer?, indexBuffer: ByteBuffer?, callback: CallbackInfoReturnable<Boolean>
    ) {
        if (vertexBuffer == null && mesh === getSectionMesh()) return
        val level = Minecraft.getInstance().level
        val draw = mesh.getSectionDraw(layer)
        val indexCount = draw?.indexCount() ?: 0
        val source = if (draw?.hasCustomIndexBuffer() == true) D3D12SectionGeometry.IndexSource.CUSTOM_SLICE
            else D3D12SectionGeometry.IndexSource.SEQUENTIAL_QUADS
        val indexStride = if (source == D3D12SectionGeometry.IndexSource.CUSTOM_SLICE)
            requireNotNull(draw).indexType().bytes else 0
        D3D12SectionGeometry.stageUpload(
            getSectionNode(), mesh, layer.name, indexCount, level, vertexBuffer, source, indexStride, indexBuffer
        )
    }

    @WrapMethod(method = ["setSectionMesh"])
    private fun `dxr_mc$commitUpload`(mesh: SectionMesh, original: Operation<SectionMesh>): SectionMesh =
        synchronized(this) {
            val task = `dxr_mc$compileTask`?.get()
            if (task == null || (task as SectionCompileTaskAccessor).`dxr_mc$isCancelled`().get()) {
                return@synchronized mesh
            }
            val state = (task as D3D12SectionTaskState).captureState
            val level = Minecraft.getInstance().level
            val epoch = D3D12SectionGeometry.epoch()
            val node = getSectionNode()
            if (!state.canInstallRenderMesh(mesh, node, level, epoch)) return@synchronized mesh
            val previous = original.call(mesh)
            if (getSectionMesh() === mesh && Minecraft.getInstance().level === level) {
                if (!state.canInstall(mesh, node, level, epoch) ||
                    !D3D12SectionGeometry.commitCurrentUpload(
                        node, mesh, level, epoch, state.compileScope, state.producerManifest
                    )) {
                    D3D12SectionGeometry.discardUpload(mesh)
                }
                state.observeInstall(mesh, node, level, epoch)
            }
            previous
        }

    @Inject(method = ["releaseSectionMesh"], at = [At("HEAD")])
    private fun `dxr_mc$discardUpload`(mesh: SectionMesh, callback: CallbackInfo) {
        D3D12SectionGeometry.discardUpload(mesh)
    }

    @Inject(method = ["reset"], at = [At("HEAD")])
    private fun `dxr_mc$removeSection`(callback: CallbackInfo) {
        synchronized(this) {
            `dxr_mc$compileTask` = null
            D3D12SectionGeometry.removeSection(getSectionNode())
        }
    }
}

