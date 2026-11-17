package io.github.dxegp.render.resolution

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.mixin.resolution.RenderTargetAttachmentsAccessor
import net.minecraft.client.Minecraft

internal class PreparedWorldTarget private constructor(
    private val target: RenderTarget,
    private val expected: Attachments,
    private var candidate: Attachments?,
) : AutoCloseable {
    val color: GpuTexture get() = checkNotNull(candidate).color
    val depth: GpuTexture get() = checkNotNull(candidate).depth

    private class Attachments(
        val color: GpuTexture,
        val colorView: GpuTextureView,
        val depth: GpuTexture,
        val depthView: GpuTextureView,
    ) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            for (resource in listOf(depthView, colorView, depth, color)) {
                try {
                    resource.close()
                } catch (cleanup: Throwable) {
                    val original = failure
                    if (original == null) failure = cleanup else original.addSuppressed(cleanup)
                }
            }
            failure?.let { throw it }
        }
    }

    fun validateForPublication() {
        RenderSystem.assertOnRenderThread()
        val attachments = target as RenderTargetAttachmentsAccessor
        check(candidate != null && Minecraft.getInstance().gameRenderer.mainRenderTarget() === target &&
            attachments.illusionColorTexture === expected.color &&
            attachments.illusionColorTextureView === expected.colorView &&
            attachments.illusionDepthTexture === expected.depth &&
            attachments.illusionDepthTextureView === expected.depthView &&
            !expected.color.isClosed && !expected.depth.isClosed &&
            !expected.colorView.isClosed && !expected.depthView.isClosed) {
            "Prepared world target was closed, published, or superseded"
        }
    }

    fun publish(): AutoCloseable {
        val created = checkNotNull(candidate) { "World target candidate has no attachments" }
        val attachments = target as RenderTargetAttachmentsAccessor
        attachments.illusionColorTexture = created.color
        attachments.illusionColorTextureView = created.colorView
        attachments.illusionDepthTexture = created.depth
        attachments.illusionDepthTextureView = created.depthView
        candidate = null
        return expected
    }

    override fun close() {
        val abandoned = candidate ?: return
        candidate = null
        abandoned.close()
    }

    companion object {
        fun prepare(width: Int, height: Int): PreparedWorldTarget {
            RenderSystem.assertOnRenderThread()
            require(width > 0 && height > 0) { "World target extent is empty" }
            val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val attachments = target as RenderTargetAttachmentsAccessor
            val expected = Attachments(
                checkNotNull(attachments.illusionColorTexture),
                checkNotNull(attachments.illusionColorTextureView),
                checkNotNull(attachments.illusionDepthTexture),
                checkNotNull(attachments.illusionDepthTextureView),
            )
            val device = RenderSystem.getDevice()
            var color: GpuTexture? = null
            var colorView: GpuTextureView? = null
            var depth: GpuTexture? = null
            var depthView: GpuTextureView? = null
            try {
                color = device.createTexture({ "Prepared world color" }, expected.color.usage(),
                    expected.color.format, width, height, 1, 1)
                colorView = device.createTextureView(color)
                depth = device.createTexture({ "Prepared world depth" }, expected.depth.usage(),
                    expected.depth.format, width, height, 1, 1)
                depthView = device.createTextureView(depth)
                return PreparedWorldTarget(target, expected, Attachments(color, colorView, depth, depthView))
            } catch (failure: Throwable) {
                for (resource in listOfNotNull(depthView, colorView, depth, color)) {
                    try {
                        resource.close()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }
    }
}
