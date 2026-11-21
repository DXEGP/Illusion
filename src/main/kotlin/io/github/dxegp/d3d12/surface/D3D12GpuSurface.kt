package io.github.dxegp.d3d12.surface

import io.github.dxegp.render.frame.vanilla.recordVelocityPass

import io.github.dxegp.d3d12.resource.D3D12GpuTexture
import io.github.dxegp.d3d12.resource.D3D12GpuTextureView

import io.github.dxegp.diagnostics.IllusionLogger

import com.mojang.renderpearl.backend.api.CommandEncoderBackend
import com.mojang.renderpearl.api.device.GpuSurface
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend
import com.mojang.renderpearl.api.device.SurfaceException
import com.mojang.renderpearl.api.textures.GpuTextureView
import io.github.dxegp.diagnostics.D3D12RenderProf
import io.github.dxegp.render.presentation.D3D12GUISplit
import io.github.dxegp.render.frame.vanilla.VanillaFrameCoordinator
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import java.util.ArrayList

/**
 * D3D12 交换链后端（DXGI flip model）：swapchain 由窗口 HWND 懒创建、[configure] 里重建，
 * blit 用当前命令编码器，present 按配置的 [GpuSurface.PresentMode] 走 vsync。
 */
class D3D12GpuSurface(hwnd: Long) : GpuSurfaceBackend {
    private companion object {
        const val SURFACE_BLIT_PASS = 0x20003
        const val STATE_SHADER_RESOURCE = 0xC0
        const val STATE_COMMON = 0
    }
    private val handle: Long
    private val presentModes: MutableList<GpuSurface.PresentMode> = ArrayList()
    private var closed = false
    /** acquire 推迟到 blit 之前（[ensureAcquired]）：帧首 acquire 会阻塞等上一帧 Present。包装层的 hasImageAcquired 不受影响。 */
    private var acquireDeferred = false
    /** 推迟的 acquire 已失败：下一帧 acquireNextTexture 抛 SurfaceException 触发 MC 重建 surface。 */
    private var acquireDeferredFailed = false
    /**
     * 本帧 blit 被跳过（推迟 acquire 失败）。vanilla 包装层的 hasBlittedTexture 无条件置 true，判断不了
     * "这帧到底画没画"；present() 必须依据本标志跳过，否则会 flip 一张本帧从未写过的 back buffer。
     */
    private var blitSkipped = false

    init {
        this.handle = MCDR_ABI.D3D12CreateSurface(hwnd)
        if (this.handle == 0L) {
            throw IllegalStateException("dx12CreateSurface returned a null handle")
        }
        val modes = MCDR_ABI.D3D12SurfacePresentModes()
        val all = GpuSurface.PresentMode.values()
        for (mode in modes) {
            if (mode >= 0 && mode < all.size) {
                this.presentModes.add(all[mode])
            }
        }
    }

    @Throws(SurfaceException::class)
    override fun configure(config: GpuSurface.Configuration) {
        this.acquireDeferred = false
        this.acquireDeferredFailed = false
        this.blitSkipped = false
        val ok = MCDR_ABI.D3D12ConfigureSurface(this.handle, config.width(), config.height(),
            config.presentMode().ordinal)
        IllusionLogger.debugMessage("configureSurface: ${config.width()}x${config.height()}"
                + " mode=${config.presentMode()} ok=${ok}")
        if (!ok) {
            throw SurfaceException("Failed to configure DX12 surface to "
                + "${config.width()}x${config.height()}")
        }
    }

    override fun isSuboptimal(): Boolean {
        return MCDR_ABI.D3D12IsSurfaceSuboptimal(this.handle)
    }

    @Throws(SurfaceException::class)
    override fun acquireNextTexture() {
        if (this.acquireDeferredFailed) {
            this.acquireDeferredFailed = false
            throw SurfaceException("Failed to acquire DX12 back buffer (deferred)")
        }
        this.acquireDeferred = true
    }

    /**
     * 在真正需要 back buffer 之前（blit 时）才 acquire：此时本帧录制已完成、上一帧 Present 通常已结束，等待为 0ms。
     * 返回 false = 本帧跳过 blit（画面停在上一帧，不崩）。
     */
    private fun ensureAcquired(): Boolean {
        if (!this.acquireDeferred) {
            return true
        }
        this.acquireDeferred = false
        if (MCDR_ABI.D3D12AcquireSurface(this.handle)) {
            return true
        }
        IllusionLogger.warnMessage("deferred acquire failed — skipping blit this frame")
        System.err.flush()
        this.acquireDeferredFailed = true
        return false
    }

    override fun blitFromTexture(commandEncoder: CommandEncoderBackend, textureView: GpuTextureView) {
        D3D12RenderProf.begin(D3D12RenderProf.BLIT)
        if (!ensureAcquired()) {
            this.blitSkipped = true
            D3D12RenderProf.end(D3D12RenderProf.BLIT)
            return
        }
        val encoder = commandEncoder as D3D12CommandEncoderBackend
        val fallback = (textureView as D3D12GpuTextureView).texture() as D3D12GpuTexture
        val tex = D3D12GUISplit.presentSourceTexture(fallback)
        encoder.recordVelocityPass()
        val version = VanillaFrameCoordinator.currentResourceVersion() ?: 1L
        VanillaFrameCoordinator.recordDeclaredPresentationPass(
            SURFACE_BLIT_PASS,
            listOf(MCDR_ABI.VanillaResourceUse(
                tex.handle(), version, access = 1,
                requiredState = STATE_SHADER_RESOURCE, finalState = STATE_COMMON,
            )),
        ) {
            MCDR_ABI.D3D12BlitSurface(encoder.nativeHandle(), this.handle, tex.handle())
        }
        D3D12RenderProf.end(D3D12RenderProf.BLIT)
    }

    override fun present() {
        val skipped = this.blitSkipped
        try {
            presentFrame()
        } catch (failure: Throwable) {
            VanillaFrameCoordinator.abortWorldRender(failure)
            throw failure
        } finally {
            if (skipped) VanillaFrameCoordinator.retireUnpresentedFrame()
            else VanillaFrameCoordinator.retireSubmittedDisplayFrame()
        }
    }

    private fun presentFrame() {
        if (this.blitSkipped) {
            this.blitSkipped = false
            IllusionLogger.debugMessage("present skipped — no blit this frame")
            return
        }
        D3D12RenderProf.begin(D3D12RenderProf.PRESENT)
        MCDR_ABI.D3D12PresentSurface(handle)
        D3D12RenderProf.end(D3D12RenderProf.PRESENT)
    }

    override fun supportedPresentModes(): Collection<GpuSurface.PresentMode> {
        return this.presentModes
    }

    override fun close() {
        if (this.closed) {
            return
        }
        val started = System.nanoTime()
        IllusionLogger.debugMessage("destroySurface begin")
        VanillaFrameCoordinator.closeDisplayFrames()
        check(MCDR_ABI.D3D12DestroySurface(this.handle)) { "Surface still owns pending work: ${MCDR_ABI.D3D12LastError()}" }
        this.closed = true
        IllusionLogger.debugMessage("destroySurface took ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}

