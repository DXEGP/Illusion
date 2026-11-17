package io.github.dxegp.d3d12.device

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.device.DeviceFeatures
import com.mojang.renderpearl.api.device.DeviceInfo
import com.mojang.renderpearl.api.device.DeviceLimits
import com.mojang.renderpearl.api.device.DeviceType
import com.mojang.renderpearl.api.device.HintsAndWorkarounds
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.backend.api.CommandEncoderBackend
import com.mojang.renderpearl.backend.api.GpuDeviceBackend
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend
import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.command.D3D12CommandRecordingFactory
import io.github.dxegp.d3d12.command.D3D12CommandScope
import io.github.dxegp.d3d12.resource.D3D12GpuQueryPool
import io.github.dxegp.d3d12.surface.D3D12GpuSurface
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.shader.D3D12PipelineCompiler
import io.github.dxegp.d3d12.shader.D3D12GpuSampler
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLProperties
import org.lwjgl.sdl.SDLVideo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/**
 * vanilla [GpuDeviceBackend] 的 D3D12 实现：创建资源，并委托管线编译与命令录制。
 * 一个设备只有一个共享 [D3D12CommandEncoderBackend]；失败即崩、不回退。
 * 见 docs/misc/backend-and-device.md §3.1。
 */
class D3D12Device internal constructor(
    private val commandRecordingFactory: D3D12CommandRecordingFactory,
    val pipelines: D3D12PipelineCompiler,
    submissionMode: D3D12SubmissionMode,
) : GpuDeviceBackend {
    private val deviceInfo: DeviceInfo

    // 共享 CommandEncoder：createCommandEncoder() 每次返回同一实例（blit 与 submit 同 encoder、
    // 整帧一次提交）。每次 new 独立 ctx 会让 submit 提交空列表 → backbuffer 呈现未初始化垃圾。
    private var sharedCommandEncoder: D3D12CommandEncoderBackend? = null
    // 同一实例登记到伴生对象，供帧中后处理（世界层 SMAA）取用。

    init {
        val probe = MCDR_ABI.ID3D12CreateDevice()
        LOGGER.info("Device capability summary: {}", probe)
        val frequency = MCDR_ABI.D3D12GetTimestampFrequency()
        if (frequency == 0L) {
            LOGGER.warn("GetTimestampFrequency returned 0; timestampPeriod=1.0")
        }
        // 适配器信息取自 DXGI/DXCore（F3 与系统信息行读 D3D12AdapterInfo.current）；查询失败保持 null。
        val nativeInfo = D3D12AdapterInfo.parse(MCDR_ABI.ID3D12GetAdapterInfo())
        if (nativeInfo !== D3D12AdapterInfo.UNKNOWN) {
            D3D12AdapterInfo.current = nativeInfo
            LOGGER.info("Adapter: {}", nativeInfo.summary())
        } else {
            LOGGER.warn("Adapter info unavailable; keeping the device-summary fallback")
        }
        this.deviceInfo = buildDeviceInfo(parseAdapterName(probe), nativeInfo, frequency)
        if (submissionMode == D3D12SubmissionMode.ASYNCHRONOUS) {
            MCDR_ABI.D3D12InitAsyncRenderer(1)
        }
        initialized = true
    }

    override fun createSampler(addressModeU: AddressMode, addressModeV: AddressMode,
        minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble): GpuSampler {
        return D3D12GpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod)
    }

    // 26.3 的 backend SPI 只保留 String 版 createTexture 与带 mip 范围的 createTextureView，其余是内部重载。
    fun createTexture(label: Supplier<String>?, usage: @GpuTexture.Usage Int,
        format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture {
        val name = label?.get() ?: ""
        return D3D12GpuTexture(
            usage, name, format, width, height,
            depthOrLayers, mipLevels
        )
    }

    override fun createTexture(label: String?, usage: @GpuTexture.Usage Int,
        format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture {
        val name = label ?: ""
        return D3D12GpuTexture(
            usage, name, format, width, height,
            depthOrLayers, mipLevels
        )
    }

    fun createTextureView(texture: GpuTexture): GpuTextureView {
        return this.createTextureView(texture, 0, texture.getMipLevels())
    }

    override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView {
        return D3D12GpuTextureView(texture, baseMipLevel, mipLevels)
    }

    override fun createBuffer(label: Supplier<String>?, usage: @GpuBuffer.Usage Int, size: Long): GpuBuffer {
        // 保留 label：UBO 身份识别用（见 D3D12GpuBuffer.label）。
        val name = label?.get() ?: ""
        return D3D12GpuBuffer(usage, size, name)
    }

    override fun createBuffer(label: Supplier<String>?, usage: @GpuBuffer.Usage Int,
        data: ByteBuffer): GpuBuffer {
        // 镜像官方 VulkanDevice.createBuffer(data)：带 COPY_DST 分配后经命令编码器上传。
        val buffer = this.createBuffer(label, usage or GpuBuffer.USAGE_COPY_DST, data.remaining().toLong())
        val encoder = D3D12CommandEncoderBackend(null, commandRecordingFactory.create(D3D12CommandScope.UPLOAD))
        try {
            encoder.writeToBuffer(buffer.slice(), data)
            encoder.submit()
        } finally {
            encoder.close()
        }
        return buffer
    }

    /**
     * SDL3 窗口句柄（`SDL_Window*`）→ HWND 由 `SDL_PROP_WINDOW_WIN32_HWND_POINTER` 取，跨 FFM 边界仍是一个
     * `uint64_t`。`isIconified` 是 26.3 新参，建 surface 用不到（最小化由游戏自己判断）。
     */
    override fun createSurface(windowHandle: Long, isIconified: BooleanSupplier): GpuSurfaceBackend {
        val hwnd = win32Hwnd(windowHandle)
        if (hwnd == 0L) {
            throw IllegalStateException("SDL_PROP_WINDOW_WIN32_HWND_POINTER is null for SDL window "
                + "${windowHandle} (video driver: ${SDLVideo.SDL_GetCurrentVideoDriver()}, "
                + "last SDL error: ${SDLError.SDL_GetError()})")
        }
        return D3D12GpuSurface(hwnd)
    }

    private fun win32Hwnd(window: Long): Long {
        val props = SDLVideo.SDL_GetWindowProperties(window)
        if (props == 0) return 0L
        return SDLProperties.SDL_GetPointerProperty(
            props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L)
    }

    override fun createCommandEncoder(): CommandEncoderBackend {
        // 共享单例：全帧 blit/绘制/submit 落同一 CommandContext。
        var encoder = this.sharedCommandEncoder
        if (encoder == null) {
            encoder = D3D12CommandEncoderBackend(this, commandRecordingFactory.create(D3D12CommandScope.FRAME))
            this.sharedCommandEncoder = encoder
        }
        sharedEncoderForFrame = encoder
        return encoder
    }

    override fun compilePipeline(
        info: BackendRenderPipeline.CreateInfo
    ): BackendRenderPipeline.Pending = pipelines.compilePipeline(info)

    override fun createTimestampQueryPool(size: Int): GpuQueryPool {
        return D3D12GpuQueryPool(size)
    }

    /** 26.3 用 GPU 时间戳校准偏移取代 getTimestampNow()；本后端时间戳 query 与呈现帧同源，偏移恒 0。 */
    override fun getTimestampCalibrationOffset(): Long {
        return 0L
    }

    override fun getLastDebugMessages(): MutableList<String> {
        return ArrayList()
    }

    override fun isDebuggingEnabled(): Boolean {
        return false
    }

    override fun getDeviceInfo(): DeviceInfo {
        return this.deviceInfo
    }
    @Synchronized
    override fun close() {
        // 先销毁共享 encoder 再清管线缓存。encoder.close() 对共享 encoder **不**调
        // dx12DestroyCommandEncoder（避免 CubeMap.render() 内部 close 顺手销毁 ctx），所以这里显式销毁。
        val closeStarted = System.nanoTime()
        IllusionLogger.debugMessage("device.close: enter")
        val encoder = this.sharedCommandEncoder
        if (encoder != null) {
            encoder.close()
            encoder.destroyCommandsAndReleaseResources()
            this.sharedCommandEncoder = null
        }
        IllusionLogger.debugMessage("device.close: encoder done")
        sharedEncoderForFrame = null  // 帧中后处理不能再摸到已销毁的 ctx
        commandRecordingFactory.close()
        pipelines.close()
        IllusionLogger.debugMessage("device.close: cache+compiler done")
        val asyncStarted = System.nanoTime()
        check(MCDR_ABI.D3D12DestroyAsyncRenderer()) { "Async renderer still owns GPU work" }
        IllusionLogger.debugMessage("device.close: async renderer done")
        val asyncMs = (System.nanoTime() - asyncStarted) / 1_000_000
        // 必须趁 DXGI/D3D12 仍存活时显式释放设备：留给 DLL_PROCESS_DETACH 会让 ComPtr::Release()
        // 在 dxgi.dll 已 detach 后执行 → 原生异常，退出码变成 2170。
        check(MCDR_ABI.D3D12DestroyDevice()) { "Device still owns pending work: ${MCDR_ABI.D3D12LastError()}" }
        initialized = false
        // 清掉适配器信息：F3 与系统信息行按它非空决定是否显示 DX12 行。
        D3D12AdapterInfo.current = null
        IllusionLogger.debugMessage(
            "D3D12Device.close took ${(System.nanoTime() - closeStarted) / 1_000_000} ms" +
                " (async ${asyncMs} ms)"
        )
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")

        /** 标记 DX12 后端是否已成功初始化。 */
        @Volatile
        private var initialized: Boolean = false

        @JvmStatic
        fun isInitialized(): Boolean {
            return initialized
        }

        /**
         * 整帧共享的 CommandEncoder（[createCommandEncoder] 里登记、close() 里清空）。官方 GpuDevice 不暴露
         * backend、`CommandEncoder.backend()` 是 protected，Java 侧要靠这个出口在帧中间追加后处理。
         * null = 设备未起或已销毁，调用方直接跳过。
         */
        @Volatile
        private var sharedEncoderForFrame: D3D12CommandEncoderBackend? = null

        @JvmStatic
        fun sharedCommandEncoder(): D3D12CommandEncoderBackend? {
            return sharedEncoderForFrame
        }

        private fun parseAdapterName(probe: String?): String {
            if (probe == null || probe.isBlank()) {
                return "D3D12 adapter"
            }
            val sep = probe.indexOf(" (")
            return if (sep < 0) probe else probe.substring(0, sep)
        }

        /** 组装 F3 可见的设备描述（对齐 VulkanDevice / GlHeuristics）：值全部来自 native 适配器查询，常量仅作回落。 */
        private fun buildDeviceInfo(
            adapterName: String,
            nativeInfo: D3D12AdapterInfo,
            timestampFrequency: Long
        ): DeviceInfo {
            val timestampPeriod = if (timestampFrequency > 0L) 1.0f / timestampFrequency.toFloat() else 1.0f
            val haveNative = nativeInfo !== D3D12AdapterInfo.UNKNOWN
            val name = if (haveNative && nativeInfo.name.isNotEmpty()) nativeInfo.name else adapterName
            return DeviceInfo(
                name,
                if (haveNative) nativeInfo.vendorName() else "D3D12 Unknown",
                if (haveNative) nativeInfo.driverInfo() else "MCDR D3D12 Driver",
                true,                                       // D3D12 NDC depth is 0..1
                if (haveNative) nativeInfo.backendName() else "DirectX 12",
                timestampPeriod,
                DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 4096, 8, 65536),
                DeviceFeatures(true, true, true, true, true, true, true, true),
                emptySet(),
                HintsAndWorkarounds(false, false, false, false),
                if (haveNative) nativeInfo.deviceTypeEnum() else DeviceType.DISCRETE
            )
        }
    }
}
