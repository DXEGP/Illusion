package io.github.dxegp.mixin.backend;

import net.minecraft.client.renderer.DebugCrosshairRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 3D 准星接管点 —— **已停用，该类目前是一个空标记**（删掉不改变行为）。原设计在 HEAD `ci.cancel()` 把准星
 * 推迟到世界层搬进 GUI 目标之后补画，但补画链一笔都提交不上去，故整条接管停掉。
 */
@Mixin(DebugCrosshairRenderer.class)
public class DebugCrosshairRendererMixin {
}
