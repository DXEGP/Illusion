package io.github.dxegp.d3d12.backend

import io.github.dxegp.d3d12.shader.D3D12BootstrapShaderSource
import io.github.dxegp.diagnostics.BackendSelfTests
import com.mojang.renderpearl.api.device.BackendCreationException
import com.mojang.renderpearl.api.device.GpuBackend
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import io.github.dxegp.render.terrain.mesh.D3D12MeshShader
import com.mojang.renderpearl.api.device.GpuDebugOptions
import io.github.dxegp.Illusion.Companion.NATIVE_NAME
import io.github.dxegp.config.D3D12Settings
import io.github.dxegp.diagnostics.D3D12Fatal
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.device.D3D12Device
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLVideo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import net.neoforged.fml.loading.EarlyLoadingScreenController
import io.github.dxegp.bootstrap.earlywindow.service.DxrmcEarlyWindowProvider

/**
 * Direct3D 12 [GpuBackend] 实现：建窗口、建设备、跑自检链。
 *
 * 失败即崩、不回退 GL/Vulkan（崩溃路径见 [D3D12Fatal]）。
 * 初始化顺序与自检链的门槛见 docs/misc/backend-and-device.md §3.1。
 */
class D3D12Backend : GpuBackend {
    /** [createWindow] 建出来的 `SDL_Window*`；`createDevice` 里做 surface 自检时要用它。 */
    private var lastWindowHandle: Long = 0L

    override fun getName(): String = "D3D12"

    // d3d12.dll 是系统组件，不需要预加载或卸载。
    override fun loadLibrary() {}

    override fun unloadLibrary() {}

    /**
     * flags 是游戏算好的 SDL3 标志，**原样透传**。
     *
     * 绝不能或上 `SDL_WINDOW_OPENGL`：进程里一旦出现过被使用的 GL 上下文，RTSS/Afterburner 的 OSD
     * 就会改走 OpenGL 呈现路径、被 D3D12 子窗口整块盖住而永久不可见。
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
        lastWindowHandle = handle
        LOGGER.info("[$NATIVE_NAME] SDL window: {}x{}, flags=0x{}", width, height, flags.toString(16))
        return handle
    }

    @Throws(BackendCreationException::class)
    override fun createDevice(debugOptions: GpuDebugOptions): GpuDevice {
        // 失败不回退：D3D12Fatal 抛 ReportedException，vanilla 的候选后端循环吞不掉。
        // [debug] failDevice = true 是这条崩溃路径的测试钩子，默认 false。
        if (D3D12Settings.failDevice) {
            D3D12Fatal.crash("simulated Direct3D 12 device failure ([debug] failDevice = true)", null)
        }
        try {
            // 这一次通常被**静默丢弃**：MCDR_ABI 句柄要等 D3D12Device 构造时才加载。
            D3D12Settings.pushToNative()
            // 内嵌 GLSL 只作兜底，真实 shader 源由 D3D12PipelineBridge 提供。
            val device = D3D12Device(D3D12BootstrapShaderSource.shaderSource)
            // ABI 已加载：必须在返回前再推一次，否则 [dlss] frameGeneration 这类
            // 「只在启动时读一次」的开关永远到不了 native。
            D3D12Settings.pushToNative()

            // 地形已无 IMR 回退：mesh 能力不足时必须留下玩家可见的 ERROR，不能让地形静默消失。
            if (!D3D12MeshShader.isSupported() && D3D12Settings.meshTerrain) {
                LOGGER.error("mesh shaders are unavailable but terrain has no IMR fallback: "
                    + "terrain batches will be skipped (see the mesh support reason above)")
            }
            try {
                BackendSelfTests.run(device, lastWindowHandle)
            } catch (t: Throwable) {
                device.close()
                throw t
            }
            // 必须清空：自检用内嵌 GLSL 填过 cache，不清的话真实 shader 永远命中、从不编译，屏幕是紫色。
            device.clearPipelineCache()
            return FrontendGpuDevice(device)
        } catch (t: Throwable) {
            LOGGER.error("D3D12 self-test failed", t)
            LOGGER.error("D3D12 failure is fatal by design: no GL/Vulkan fallback, "
                + "preferredGraphicsBackend is reset to default and the game crashes with a report")
            // 抛 ReportedException 而不是 BackendCreationException —— 后者会被回退循环吞掉。
            D3D12Fatal.crash("DX12 self-test failed: $t", t)
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxr_mc")
    }
}
