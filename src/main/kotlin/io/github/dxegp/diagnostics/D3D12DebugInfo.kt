package io.github.dxegp.diagnostics

import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.render.resolution.D3D12RenderScale
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.config.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * vanilla `system_specs` 组里只补 DXRMC 独有的行（分辨率/显存/后端），由
 * `DebugEntrySystemSpecsMixin` 追加到同一组。VRS 行不在这里（见 [D3D12VrsDebugEntry]）。
 */
object D3D12DebugInfo {
    @JvmField val SYSTEM_GROUP = Identifier.withDefaultNamespace("system")

    /** @return 追加到 vanilla GPU 块之后的行；DXRMC 不是当前后端（GL/Vulkan）时返回空表。 */
    @JvmStatic
    fun extraSystemLines(windowWidth: Int, windowHeight: Int): List<String> {
        val info = D3D12AdapterInfo.current ?: return emptyList<String>()
        val luid = if (info.luid.isEmpty()) "unknown" else info.luid
        val lines = mutableListOf<String>()
        val attachment = mainTargetSize()
        val scaledWidth = D3D12RenderScale.scaled(windowWidth)
        val scaledHeight = D3D12RenderScale.scaled(windowHeight)
        val attachmentsAreScaled = attachment != null &&
            (attachment.first != windowWidth || attachment.second != windowHeight)
        val renderedWidth = if (attachmentsAreScaled) attachment.first else scaledWidth
        val renderedHeight = if (attachmentsAreScaled) attachment.second else scaledHeight
        lines.add("Resolution: output ${windowWidth}x${windowHeight}, rendered ${renderedWidth}x${renderedHeight}")
        lines.add("VRAM: ${info.vramMiB()} MiB dedicated / ${info.sharedMiB()} MiB shared")
        lines.add("Illusion: ${info.backendName()} (MCDR ABI v${MCDR_ABI.MCDR_ABI_VERSION}) - LUID: $luid")
        return lines
    }

    /** `Terrain Rendering:` 的后缀；只有 D3D12 后端在跑且 `[mesh] terrain` 生效时才追加，否则是假信息。 */
    @JvmStatic
    fun terrainLine(line: String): String {
        if (!line.startsWith("Terrain Rendering: ")) return line
        if (D3D12AdapterInfo.current == null || !D3D12Settings.meshTerrain) return line
        return "$line with mesh (Powered by Illusion)"
    }

    /** 主渲染目标附件的真实像素尺寸；F3 在渲染线程上取，读不到就返回 null。 */
    private fun mainTargetSize(): Pair<Int, Int>? = runCatching {
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val texture = (main.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return@runCatching null
        texture.getWidth(0) to texture.getHeight(0)
    }.getOrNull()
}
