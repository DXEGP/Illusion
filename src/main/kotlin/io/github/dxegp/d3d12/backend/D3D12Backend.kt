package io.github.dxegp.d3d12.backend

import io.github.dxegp.d3d12.shader.D3D12BootstrapShaderSource
import com.mojang.renderpearl.api.device.BackendCreationException
import com.mojang.renderpearl.api.device.GpuBackend
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import io.github.dxegp.render.frame.vanilla.VanillaCommandRecordingFactory
import io.github.dxegp.render.compilation.VanillaPipelineVariants
import io.github.dxegp.render.compilation.VanillaPipelineCompiler
import com.mojang.renderpearl.api.device.GpuDebugOptions
import io.github.dxegp.Illusion.Companion.NATIVE_NAME
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.diagnostics.D3D12Fatal
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.device.D3D12SubmissionMode
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLVideo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import net.neoforged.fml.loading.EarlyLoadingScreenController
import io.github.dxegp.bootstrap.earlywindow.service.DxrmcEarlyWindowProvider

/**
 * Direct3D 12 [GpuBackend] 实现：建窗口、建设备并交给集中渲染器。
 *
 * 失败即崩、不回退 GL/Vulkan（崩溃路径见 [D3D12Fatal]）。
 */
class D3D12Backend : GpuBackend {
    override fun getName(): String = "D3D12"

    override fun loadLibrary() {}

    override fun unloadLibrary() {}

    /**
     * flags 是游戏算好的 SDL3 标志，**原样透传**。绝不能或上 `SDL_WINDOW_OPENGL`：进程里一旦出现过被使用的 GL
     * 上下文，RTSS/Afterburner 的 OSD 就会改走 OpenGL 呈现路径、被 D3D12 子窗口整块盖住而永久不可见。
     */
    override fun createWindow(title: String?, width: Int, height: Int, flags: Long): Long {
        if (D3D12Settings.glContext) {
            LOGGER.warn("glContext = true has no effect since 26.3: the GL context belongs to "
                + "the OpenGL backend, the D3D12 backend never requests one")
        }
        val earlyWindow = (EarlyLoadingScreenController.current() as? DxrmcEarlyWindowProvider)?.claimWindow() ?: 0L
        val handle = if (earlyWindow != 0L) earlyWindow else SDLVideo.SDL_CreateWindow(title ?: "", width, height, flags)
        if (handle == 0L) {
            D3D12Fatal.crash("SDL_CreateWindow failed for DX12 (video driver: "
                + "${SDLVideo.SDL_GetCurrentVideoDriver()}): ${SDLError.SDL_GetError()}", null)
        }
        LOGGER.info("[$NATIVE_NAME] SDL window: {}x{}, flags=0x{}", width, height, flags.toString(16))
        return handle
    }

    @Throws(BackendCreationException::class)
    override fun createDevice(debugOptions: GpuDebugOptions): GpuDevice {
        try {
            D3D12Settings.pushToNative()
            val submissionMode = if (D3D12Settings.asyncRenderThread) D3D12SubmissionMode.ASYNCHRONOUS
                else D3D12SubmissionMode.SYNCHRONOUS
            if (submissionMode == D3D12SubmissionMode.SYNCHRONOUS) {
                LOGGER.warn("async renderer disabled ([debug] asyncRenderThread = false) — diagnostic mode only")
            }
            val device = D3D12Device(
                VanillaCommandRecordingFactory,
                VanillaPipelineCompiler(D3D12BootstrapShaderSource.shaderSource, VanillaPipelineVariants()),
                submissionMode,
            )
            try {
                D3D12Settings.pushToNative()
                D3D12TerrainWorkGraph.requireSupport()
                return FrontendGpuDevice(device)
            } catch (failure: Throwable) {
                device.close()
                throw failure
            }
        } catch (t: Throwable) {
            LOGGER.error("D3D12 initialization failed", t)
            LOGGER.error("D3D12 failure is fatal by design: no GL/Vulkan fallback, "
                + "preferredGraphicsBackend is reset to default and the game crashes with a report")
            D3D12Fatal.crash("DX12 initialization failed: $t", t)
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc")
    }
}
