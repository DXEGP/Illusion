package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class SectionUploadCaptureMixin {

    @Shadow
    public abstract long getSectionNode();

    @Shadow
    public abstract SectionMesh getSectionMesh();

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"))
    private void dxr_mc$captureUpload(ChunkSectionLayer layer, CompiledSectionMesh mesh,
                                      ByteBuffer vertexBuffer, ByteBuffer indexBuffer,
                                      CallbackInfoReturnable<Boolean> cir) {
        Level level = Minecraft.getInstance().level;
        // 索引来源：带自定义切片的层（排序后的半透明等）自己有索引，其余走原版共享的顺序 quad 缓冲。
        // 元素字节数用 `indexBytes / indexCount` 反推，避免引用包私有的 SectionDraw / IndexType。
        int indexCount = mesh.isEmpty(layer) ? 0 : mesh.getSectionDraw(layer).indexCount();
        int indexBytes = indexBuffer == null ? 0 : indexBuffer.remaining();
        D3D12SectionGeometry.IndexSource source = indexBytes == 0
                ? D3D12SectionGeometry.IndexSource.SEQUENTIAL_QUADS
                : D3D12SectionGeometry.IndexSource.CUSTOM_SLICE;
        int indexStride = indexCount > 0 && indexBytes > 0 ? indexBytes / indexCount : 0;
        D3D12SectionGeometry.stageUpload(
                getSectionNode(),
                mesh,
                layer.name(),
                indexCount,
                level,
                vertexBuffer,
                source,
                indexStride,
                indexBuffer);
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void dxr_mc$commitUpload(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        D3D12SectionGeometry.commitUpload(getSectionNode(), mesh, Minecraft.getInstance().level);
    }

    @Inject(method = "releaseSectionMesh", at = @At("HEAD"))
    private void dxr_mc$discardUpload(SectionMesh mesh, CallbackInfo ci) {
        D3D12SectionGeometry.discardUpload(mesh);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void dxr_mc$removeSection(CallbackInfo ci) {
        if (getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
            D3D12SectionGeometry.removeSection(getSectionNode());
        }
    }
}
