package io.github.dxegp.d3d12.render.gui

import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import it.unimi.dsi.fastutil.objects.Object2IntMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.server.level.progress.ChunkLoadStatusView
import net.minecraft.util.ARGB
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc

object D3D12LoadingGrid {
    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor, centerX: Int, centerY: Int, size: Int, margin: Int,
             status: ChunkLoadStatusView, palette: Object2IntMap<ChunkStatus>): Boolean {
        if (size <= 0 || margin < 0) return false
        if (Minecraft.getInstance().debugEntries.isCurrentlyEnabled(DebugScreenEntries.VISUALIZE_CHUNKS_ON_SERVER)) {
            val marker = (size + margin) / 2 + 1
            graphics.fill(centerX - marker, centerY - marker, centerX + marker, centerY + marker, -65536)
        }
        graphics.submitGuiElementRenderState(capture(graphics.pose(), graphics.peekScissorStack(),
            centerX, centerY, size, margin, status, palette))
        return true
    }

    fun capture(pose: Matrix3x2fc, scissor: ScreenRectangle?, centerX: Int, centerY: Int, size: Int, margin: Int,
                status: ChunkLoadStatusView, palette: Object2IntMap<ChunkStatus>): GuiElementRenderState {
        val diameter = status.radius() * 2 + 1
        val stride = size + margin
        val extent = diameter * stride - margin
        val left = centerX - extent / 2
        val top = centerY - extent / 2
        val colors = IntArray(diameter * diameter) { index ->
            ARGB.opaque(palette.getInt(status.get(index / diameter, index % diameter)))
        }
        val frozenPose = Matrix3x2f(pose)
        val bounds = ScreenRectangle(left, top, extent, extent).transformMaxBounds(frozenPose)
        return Grid(frozenPose, scissor, if (scissor == null) bounds else scissor.intersection(bounds),
            left, top, size, stride, diameter, colors)
    }

    private class Grid(
        private val pose: Matrix3x2f, private val scissor: ScreenRectangle?, private val bounds: ScreenRectangle?,
        private val left: Int, private val top: Int, private val size: Int, private val stride: Int,
        private val diameter: Int, private val colors: IntArray,
    ) : GuiElementRenderState {
        override fun pipeline(): RenderPipeline = RenderPipelines.GUI
        override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = bounds

        override fun buildVertices(consumer: VertexConsumer) {
            for (column in 0 until diameter) for (row in 0 until diameter) {
                val startX = (left + column * stride).toFloat()
                val startY = (top + row * stride).toFloat()
                val color = colors[column * diameter + row]
                consumer.addVertexWith2DPose(pose, startX + size, startY + size).setColor(color)
                consumer.addVertexWith2DPose(pose, startX + size, startY).setColor(color)
                consumer.addVertexWith2DPose(pose, startX, startY).setColor(color)
                consumer.addVertexWith2DPose(pose, startX, startY + size).setColor(color)
            }
        }
    }
}
