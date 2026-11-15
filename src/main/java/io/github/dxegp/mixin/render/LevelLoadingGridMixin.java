package io.github.dxegp.mixin.render;

import io.github.dxegp.d3d12.render.gui.D3D12LoadingGrid;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingGridMixin {
    @Shadow @Final
    private static Object2IntMap<ChunkStatus> COLORS;

    @Inject(method = "extractChunksForRendering", at = @At("HEAD"), cancellable = true)
    private static void dxr_mc$batchGrid(GuiGraphicsExtractor graphics, int centerX, int centerY,
            int size, int margin, ChunkLoadStatusView status, CallbackInfo callback) {
        if (D3D12LoadingGrid.draw(graphics, centerX, centerY, size, margin, status, COLORS)) {
            callback.cancel();
        }
    }
}
