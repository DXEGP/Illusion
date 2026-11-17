package io.github.dxegp.d3d12.command

import com.mojang.renderpearl.backend.api.CommandEncoderBackend
import com.mojang.renderpearl.backend.api.RenderPassBackend
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.buffers.TransientMemory
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.textures.GpuTexture
import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.diagnostics.RenderProfiling
import io.github.dxegp.diagnostics.D3D12RenderProf
import io.github.dxegp.Illusion
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.DisplayFrameSettlement
import io.github.dxegp.d3d12.ffi.CommandSubmissionOutcome
import io.github.dxegp.d3d12.ffi.CommandDestructionOutcome
import io.github.dxegp.d3d12.device.D3D12Device
import io.github.dxegp.d3d12.resource.*
import io.github.dxegp.d3d12.command.*
import io.github.dxegp.d3d12.surface.*
import org.joml.Vector4fc
import java.nio.ByteBuffer
import java.util.*
import java.util.function.Consumer
import kotlin.math.roundToLong

internal interface D3D12ReadbackCompletion : Runnable {
    fun cancel(failure: Throwable)

    companion object {
        fun cancellable(completion: Runnable, cancellation: Consumer<Throwable>): D3D12ReadbackCompletion =
            object : D3D12ReadbackCompletion {
                override fun run() = completion.run()
                override fun cancel(failure: Throwable) = cancellation.accept(failure)
            }

        fun cancellable(completion: Runnable, cancellationOwner: D3D12ReadbackCompletion): D3D12ReadbackCompletion =
            object : D3D12ReadbackCompletion {
                override fun run() = completion.run()
                override fun cancel(failure: Throwable) = cancellationOwner.cancel(failure)
            }

        fun owned(destination: GpuBuffer, completion: Runnable): D3D12ReadbackCompletion =
            object : D3D12ReadbackCompletion {
                private var terminal = false

                override fun run() {
                    if (terminal) return
                    terminal = true
                    completion.run()
                }

                override fun cancel(failure: Throwable) {
                    if (terminal) return
                    terminal = true
                    destination.close()
                    IllusionLogger.warnMessage("GPU readback was cancelled: ${failure.message}")
                }
            }
    }
}

/**
 * D3D12 [CommandEncoderBackend]（镜像官方 VulkanCommandEncoder）。native 侧持有两个 allocator + 一个 fence：
 * submit() 执行已录制的列表、signal fence、等 value-2（vanilla 双缓冲提交）。一个设备只有一个共享实例。
 * 见 docs/misc/backend-and-device.md §3.3。
 */
class D3D12CommandEncoderBackend internal constructor(
    private val device: D3D12Device?,
    private val recording: D3D12CommandRecording,
) : CommandEncoderBackend {
    private val commandLifetimeResources = mutableSetOf<AutoCloseable>()
    private var commandsDestroyed = false

    internal fun retainUntilCommandsClosed(resource: AutoCloseable) {
        check(!commandsDestroyed)
        commandLifetimeResources += resource
    }

    internal fun releaseCommandLifetimeResource(resource: AutoCloseable) {
        commandLifetimeResources.remove(resource)
    }

    internal fun destroyCommandsAndReleaseResources() {
        if (!commandsDestroyed) {
            settleCommandsForClosure()
            when (val settlement = settleDisplayPresentation(2_000L)) {
                DisplayFrameSettlement.Empty, is DisplayFrameSettlement.Completed -> Unit
                is DisplayFrameSettlement.TimedOut -> error(
                    "Device shutdown: display presentation remains pending serial=${settlement.receipt.serial}")
                is DisplayFrameSettlement.SubmissionUnknown -> error(
                    "Device shutdown: display submission remains unknown serial=${settlement.receipt.serial}")
            }
            if (!MCDR_ABI.D3D12WaitForFence(ctx,
                    MCDR_ABI.D3D12GetFenceValue(ctx), 5_000_000_000L)) {
                IllusionLogger.warnMessage("Device shutdown: GPU fence timed out")
                error("Device shutdown: GPU commands remain active")
            }
            drainCompletedCallbacks()
            val destruction = MCDR_ABI.D3D12DestroyCommandEncoderCheckedV1(ctx)
            check(destruction == CommandDestructionOutcome.DESTROYED) {
                "Command context destruction did not complete: $destruction"
            }
            commandsDestroyed = true
        }
        this.transientMemory.close()
        while (commandLifetimeResources.isNotEmpty()) {
            val resource = commandLifetimeResources.first()
            resource.close()
            commandLifetimeResources.remove(resource)
        }
    }

    private val ctx: Long

    /** GUI 的防御性 `RSSetShadingRate` 复位要用同一条列表的上下文（只给同包调用方）。 */
    internal fun nativeCtx(): Long = nativeHandle()
    internal inline fun <T> recordCommands(crossinline action: (Long) -> T): T {
        ensureListOpen()
        return action(ctx)
    }

    internal fun commandDevice(): D3D12Device? = device
    internal fun settleDisplayPresentation(timeoutMs: Long): DisplayFrameSettlement {
        check(!commandsDestroyed) { "Command context is destroyed" }
        settlePendingSubmit()
        return MCDR_ABI.D3D12SettleDisplayFrameV1(ctx, timeoutMs)
    }
    internal fun <T> contextControl(action: (Long) -> T): T {
        check(!commandsDestroyed) { "Command context is destroyed" }
        try { settlePendingSubmit() }
        catch (failure: Throwable) {
            if (recordingRejection !== failure) throw failure
        }
        check(!submissionFailed) { "Command submission result is unknown; resources remain retained" }
        return action(ctx)
    }
    internal fun abortDisplayFrame(): Boolean = contextControl { nativeContext ->
        val rejected = when (MCDR_ABI.D3D12CommandRecordingStatusV1(nativeContext)) {
            CommandSubmissionOutcome.RecordingRejected -> {
                if (recordingRejection == null)
                    recordingRejection = IllegalStateException("Command recording was rejected before submission")
                true
            }
            CommandSubmissionOutcome.Ready -> false
            CommandSubmissionOutcome.SubmissionUnknown -> {
                submissionFailed = true
                error("Command submission result is unknown; resources remain retained")
            }
            CommandSubmissionOutcome.OwnershipPending -> error("Command submission ownership remains pending")
            is CommandSubmissionOutcome.Submitted -> error("Recording status returned a submitted outcome")
        }
        if (rejected && currentRenderPass != null) abortWorldRenderPassState()
        val aborted = MCDR_ABI.D3D12AbortDisplayFrame(nativeContext) == 1
        if (aborted && rejected) cancelPendingReadbacks()
        aborted
    }
    private val transientMemory: D3D12TransientMemory
    private val pendingCallbacks = mutableListOf<Runnable>()
    private val submittedCallbacks = ArrayDeque<Pair<Long, List<Runnable>>>()
    private var currentRenderPass: RenderPassBackend? = null
    private var declaredRenderPassToken = 0L
    /**
     * 本帧提交已交给渲染线程、尚未结算。改由 [ensureListOpen] 结算 —— 中间隔着 MC 的 extract，
     * 渲染线程早已提交，主线程基本不阻塞（原先在 submit() 末尾阻塞 0.09-0.11ms/帧）。
     */
    private var pendingSubmit = false
    private var pendingFenceBefore = 0L
    private var submissionFailed = false
    private var recordingRejection: IllegalStateException? = null
    private var recordingDiscarded = false
    private var closing = false

    init {
        this.ctx = try {
            MCDR_ABI.D3D12CreateCommandEncoder().also {
                check(it != 0L) { "dx12CreateCommandEncoder returned a null handle" }
            }
        } catch (failure: Throwable) {
            try { recording.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        try {
            this.transientMemory = D3D12TransientMemory(this.ctx, Runnable { this.ensureListOpen() })
            retainUntilCommandsClosed(recording)
            MCDR_ABI.D3D12BeginCommandList(this.ctx)
            check(MCDR_ABI.D3D12IsListOpen(this.ctx)) { "Initial command list could not be opened" }
        } catch (failure: Throwable) {
            try {
                check(MCDR_ABI.D3D12DestroyCommandEncoderCheckedV1(this.ctx) == CommandDestructionOutcome.DESTROYED) {
                    "Initial command context destruction did not complete"
                }
                recording.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** Native CommandContext* handle (used by surface blit + render pass). */
    fun nativeHandle(): Long {
        ensureListOpen()
        return this.ctx
    }

    /**
     * 懒加载打开命令列表（幂等）。列表未打开时会 allocator reset —— 内部按 fenceValue-2 等 GPU 释放该 allocator。
     */
    private fun ensureListOpen() {
        check(!commandsDestroyed && !closing) { "Command encoder is closed" }
        recordingRejection?.let { throw it }
        // 结算上一帧挂起的提交必须早于读 listOpen：渲染线程的 submitCommandList 会 Close 旧列表（1→0），
        // 先读可能读到中间态而跳过 begin，主线程随即写进一条正在被关闭/提交的列表（数据竞争）。
        settlePendingSubmit()
        check(!submissionFailed) { "Command submission failed; context remains retained" }
        drainCompletedCallbacks()
        if (!MCDR_ABI.D3D12IsListOpen(this.ctx)) {
            MCDR_ABI.D3D12BeginCommandList(this.ctx)
            check(MCDR_ABI.D3D12IsListOpen(this.ctx)) { "Command list could not be opened" }
        }
    }

    /**
     * 结算上一帧挂起的异步提交。必须在下一帧 dx12BeginCommandList **之前**：begin 会读写 ctx 的
     * fenceValue / currentListSlot / commandList / resourceState，而这些同时被渲染线程的
     * submitCommandList（Close + Execute + Signal）修改，未结算就 Reset 会产生数据竞争。
     */
    private fun settlePendingSubmit() {
        if (!this.pendingSubmit) return
        val prof = RenderProfiling.enabled
        val t0 = if (prof) System.nanoTime() else 0L
        val outcome = MCDR_ABI.D3D12SettleCommandSubmissionV1(this.ctx, 10_000L)
        if (prof) gProfWaitSubmitNs += System.nanoTime() - t0
        when (outcome) {
            is CommandSubmissionOutcome.Submitted -> {
                check(outcome.fence > pendingFenceBefore) { "Async submission did not publish its fence" }
                this.pendingSubmit = false
                acceptSubmission(outcome.fence)
            }
            CommandSubmissionOutcome.RecordingRejected -> {
                this.pendingSubmit = false
                val failure = recordingRejection ?: IllegalStateException("Command recording was rejected before submission")
                    .also { recordingRejection = it }
                throw failure
            }
            CommandSubmissionOutcome.OwnershipPending -> error("Async submission ownership remains pending")
            CommandSubmissionOutcome.SubmissionUnknown -> {
                submissionFailed = true
                error("Async command submission result is unknown; resources remain retained")
            }
            CommandSubmissionOutcome.Ready -> error("Async command submission returned an invalid ready outcome")
        }
    }

    private fun discardRejectedRecording() {
        when (MCDR_ABI.D3D12CommandRecordingStatusV1(ctx)) {
            CommandSubmissionOutcome.RecordingRejected -> {
                check(abortDisplayFrame()) {
                    "Could not discard rejected recording: ${MCDR_ABI.D3D12LastError()}"
                }
            }
            CommandSubmissionOutcome.SubmissionUnknown -> error("Command submission result is unknown; resources remain retained")
            CommandSubmissionOutcome.OwnershipPending -> error("Command submission ownership remains pending")
            CommandSubmissionOutcome.Ready -> Unit
            is CommandSubmissionOutcome.Submitted -> error("Recording status returned a submitted outcome")
        }
    }

    private fun settleCommandsForClosure() {
        try { settlePendingSubmit() }
        catch (failure: Throwable) {
            if (recordingRejection !== failure) throw failure
        }
        check(!submissionFailed) { "Failed submission retains its command resources" }
        discardRejectedRecording()
        if (pendingCallbacks.isNotEmpty()) {
            try {
                submit()
                settlePendingSubmit()
            } catch (failure: Throwable) {
                if (recordingRejection !== failure) throw failure
                discardRejectedRecording()
            }
        }
    }

    private fun cancelPendingReadbacks() {
        val failure = recordingRejection ?: IllegalStateException("Command recording was rejected before submission")
            .also { recordingRejection = it }
        recordingDiscarded = true
        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        for (callback in callbacks) {
            if (callback is D3D12ReadbackCompletion) {
                try { callback.cancel(failure) }
                catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                    IllusionLogger.warnMessage("GPU readback cancellation failed: $cleanup")
                }
            } else {
                IllusionLogger.warnMessage("Unsubmitted GPU readback was cancelled; its callback has no owner cancellation contract: $callback")
            }
        }
    }

    private fun acceptSubmission(submittedFence: Long) {
        if (pendingCallbacks.isNotEmpty()) {
            submittedCallbacks.addLast(submittedFence to pendingCallbacks.toList())
            pendingCallbacks.clear()
        }
        this.transientMemory.rotate(submittedFence)
        drainCompletedCallbacks()
    }

    private fun drainCompletedCallbacks() {
        while (submittedCallbacks.isNotEmpty()) {
            val submission = submittedCallbacks.first()
            if (!MCDR_ABI.D3D12WaitForFence(ctx, submission.first, 0L)) return
            submittedCallbacks.removeFirst()
            for (callback in submission.second) {
                try {
                    callback.run()
                } catch (failure: Throwable) {
                    IllusionLogger.warnMessage("GPU completion callback failed: $failure")
                    failure.printStackTrace()
                }
            }
        }
    }

    private fun textureHandle(texture: GpuTexture): Long = (texture as D3D12GpuTexture).handle()

    private fun bufferHandle(buffer: GpuBuffer): Long = (buffer as D3D12GpuBuffer).handle()

    /** 纳秒 -> 毫秒，保留两位小数。 */
    private fun profMs(ns: Double): Double {
        return (ns * 1e-6 * 100.0).roundToLong() / 100.0
    }

    override fun transientMemory(): TransientMemory {
        return this.transientMemory
    }

    // ---- render pass ----

    override fun createRenderPass(descriptor: RenderPassDescriptor): RenderPassBackend {
        val colorAttachments: List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>?> =
            descriptor.colorAttachments()
        val colorCount = colorAttachments.size
        // 用 var 而非 val：数组长度可能随附件组合变化。
        var colorTextures = LongArray(colorCount)
        var colorMips = IntArray(colorCount)
        var colorClearFlags = ByteArray(colorCount)
        var clearColors = FloatArray(colorCount * 4)
        for (i in 0 until colorCount) {
            val attachment = colorAttachments.get(i)
            if (attachment == null) {
                colorTextures[i] = 0L  // withUnusedColorAttachment
                continue
            }
            val view = attachment.textureView()
            colorTextures[i] = (view.texture() as D3D12GpuTexture).handle()
            // 必须把 view 的 base mip 传给 native RTV：TextureAtlas 逐级上传到 mipViews[level]，RTV 恒绑 mip0
            // 会让 mip1+ 永不写入，远处大 LOD 采样到未初始化内容。
            if (view is D3D12GpuTextureView) {
                colorMips[i] = view.baseMip()
            }
            if (attachment.clearValue().isPresent) {
                val color = attachment.clearValue().get()
                colorClearFlags[i] = 1
                clearColors[i * 4] = color.x()
                clearColors[i * 4 + 1] = color.y()
                clearColors[i * 4 + 2] = color.z()
                clearColors[i * 4 + 3] = color.w()
            }
        }

        var depthTexture = 0L
        var depthMip = 0
        var depthClearFlag: Byte = 0
        var depthClearValue = 0.0
        val depthAttachment = descriptor.depthAttachment()
        if (depthAttachment != null) {
            val depthView = depthAttachment.textureView()
            depthTexture = (depthView.texture() as D3D12GpuTexture).handle()
            if (depthView is D3D12GpuTextureView) {
                depthMip = depthView.baseMip()
            }
            if (depthAttachment.clearValue().isPresent) {
                depthClearFlag = 1
                depthClearValue = depthAttachment.clearValue().asDouble
            }
        }

        val area = descriptor.renderArea
        var x = 0
        var y = 0
        var w = 0
        var h = 0
        x = area.x()
        y = area.y()
        w = area.width()
        h = area.height()

        this.ensureListOpen()
        try {
            MCDR_ABI.D3D12BeginRenderPass(this.ctx, colorTextures, colorMips,
                colorClearFlags, clearColors, depthTexture, depthMip,
                depthClearFlag, depthClearValue, x, y, w, h)
            declaredRenderPassToken = recording.beginRasterPass(this.ctx)
        } catch (failure: Throwable) {
            abortRasterPass(declaredRenderPassToken, failure)
            declaredRenderPassToken = 0L
            throw failure
        }
        val pass = try {
            recording.createRenderPass(this.device, this.ctx, descriptor, this.transientMemory)
        } catch (failure: Throwable) {
            val token = declaredRenderPassToken
            abortRasterPass(token, failure)
            declaredRenderPassToken = 0L
            throw failure
        }
        this.currentRenderPass = pass
        return pass
    }

    /**
     * 把 `src` 画到 `dst`（整屏三角形 + 线性采样，允许两边尺寸不同），目标留在 RENDER_TARGET 供随后的 pass
     * 直接用。renderScale != 100 时主渲染目标是缩放尺寸，1:1 copy 搬不过去。返回 false = 没搬成，调用方回退。
     */
    fun recordTextureBlit(src: D3D12GpuTexture, dst: D3D12GpuTexture): Boolean {
        this.ensureListOpen()
        return MCDR_ABI.dx12BlitTextureToTexture(this.ctx, src.handle(), dst.handle())
    }
    override fun submitRenderPass() {
        val token = declaredRenderPassToken
        try {
            MCDR_ABI.D3D12EndRenderPass(this.ctx)
            recording.finishRasterPass(this.ctx, token)
        } catch (failure: Throwable) {
            resetFailedRasterPass(token, failure)
            throw failure
        } finally {
            declaredRenderPassToken = 0L
            this.currentRenderPass = null
        }
    }

    private fun abortRasterPass(token: Long, failure: Throwable) {
        try { MCDR_ABI.D3D12EndRenderPass(this.ctx) }
        catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        resetFailedRasterPass(token, failure)
    }

    private fun resetFailedRasterPass(token: Long, failure: Throwable) {
        try { recording.failRasterPass(this.ctx, token) }
        catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
    }

    internal fun abortWorldRenderPassState() {
        val hadRenderPass = currentRenderPass != null
        declaredRenderPassToken = 0L
        if (!hadRenderPass) return
        try {
            MCDR_ABI.D3D12EndRenderPass(this.ctx)
        } finally {
            currentRenderPass = null
        }
    }

    // ---- clears ----

    override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Vector4fc) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearColorTexture(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w())
        MCDR_ABI.D3D12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    override fun clearColorAndDepthTextures(colorTexture: GpuTexture, clearColor: Vector4fc,
        depthTexture: GpuTexture, clearDepth: Double, regionX: Int, regionY: Int,
        regionWidth: Int, regionHeight: Int, mipLevel: Int) {
        this.ensureListOpen()
        // 必须只清该槽位矩形区域：忽略 region 整图清空，会让滚动/翻页触发重绘时把其它已烘好的槽位图标
        // 一起抹掉（图集区域仍标记有效、不再重绘）。
        MCDR_ABI.D3D12ClearColorTextureRegion(this.ctx, textureHandle(colorTexture),
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(),
            regionX, regionY, regionWidth, regionHeight)
        MCDR_ABI.D3D12ClearDepthTextureRegion(this.ctx, textureHandle(depthTexture),
            clearDepth, regionX, regionY, regionWidth, regionHeight)
    }

    override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearDepthTexture(this.ctx, textureHandle(depthTexture), clearDepth)
    }

    /** 清模板纹理（26.3 已从接口移除，保留实现）。native 返回 1=成功 / 0=失败，与 depth/color 清理一样不检查。 */
    override fun clearStencilTexture(texture: GpuTexture, value: Int) {
        this.ensureListOpen()
        MCDR_ABI.D3D12ClearStencilTexture(this.ctx, textureHandle(texture), value)
    }

    // ---- copies ----

    override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
        var prepared: D3D12PreparedBufferUpload? = null
        try {
            prepared = recording.prepareBufferUpload(destination, data)
            val payload = prepared?.payload ?: data
            this.ensureListOpen()
            val staging = this.transientMemory.uploadStaging(payload, 1L, GpuBuffer.USAGE_COPY_SRC)
            check(MCDR_ABI.D3D12CopyBufferChecked(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
                bufferHandle(destination.buffer()), destination.offset(), payload.remaining().toLong())) {
                "Buffer upload recording failed: ${MCDR_ABI.D3D12LastError()}"
            }
            prepared?.confirmRecorded()
        } catch (failure: Throwable) {
            try { prepared?.discard() }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) {
        this.ensureListOpen()
        check(MCDR_ABI.D3D12CopyBufferChecked(this.ctx, bufferHandle(source.buffer()), source.offset(),
            bufferHandle(target.buffer()), target.offset(),
            source.length())) {
            "Buffer copy recording failed: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    override fun writeToTexture(destination: GpuTexture, source: ByteBuffer, mipLevel: Int,
        depthOrLayer: Int, destX: Int, destY: Int, width: Int, height: Int) {
        this.ensureListOpen()
        val staging = this.transientMemory.uploadStaging(source, 1L, GpuBuffer.USAGE_COPY_SRC)
        MCDR_ABI.D3D12WriteToTexture(this.ctx, bufferHandle(staging.buffer()), staging.offset(),
            width, height, textureHandle(destination), mipLevel, depthOrLayer, destX, destY)
    }

    override fun copyBufferToTexture(source: GpuBufferSlice, sourceX: Int, sourceY: Int,
        sourceWidth: Int, sourceHeight: Int, destination: GpuTexture, destinationX: Int,
        destinationY: Int, copyWidth: Int, copyHeight: Int, mipLevel: Int, arrayLayer: Int) {
        // 必须显式 ensureListOpen：uploadStaging 现在只做 CPU 侧块内映射，不再录制任何 GPU 命令。缺了它，
        // 帧外资源重载（SpriteContents mip 上传 / 图集动画）会抛 "no open command list" → 白屏 + 花屏闪烁。
        this.ensureListOpen()
        val texelSize = destination.format.blockSize()
        val skipTexels = sourceX.toLong() + sourceY.toLong() * sourceWidth
        val skipBytes = skipTexels * texelSize.toLong()
        MCDR_ABI.D3D12CopyBufferToTexture(this.ctx, bufferHandle(source.buffer()),
            source.offset() + skipBytes, sourceWidth, sourceHeight,
            textureHandle(destination), mipLevel, arrayLayer,
            destinationX, destinationY, copyWidth, copyHeight)
    }

    override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long,
        callback: Runnable, mipLevel: Int) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel,
            0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel))
    }

    override fun copyTextureToBuffer(source: GpuTexture, destination: GpuBuffer, offset: Long,
        callback: Runnable, mipLevel: Int, x: Int, y: Int, width: Int, height: Int) {
        try {
            this.ensureListOpen()
        } catch (failure: Throwable) {
            if (callback is D3D12ReadbackCompletion) {
                if (recordingDiscarded) {
                    try { callback.cancel(failure) }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                else if (recordingRejection != null) pendingCallbacks.add(callback)
            }
            throw failure
        }
        MCDR_ABI.D3D12CopyTextureToBuffer(this.ctx, textureHandle(source), mipLevel, 0,
            x, y, width, height, bufferHandle(destination), offset)
        this.pendingCallbacks.add(callback)
        // vanilla 契约：Screenshot.takeScreenshot 只在一个**从不 submit** 的一次性 encoder 上登记回调，期望回调
        // 在该帧提交时触发。一次性 encoder（device == null）必须在这里立刻提交并同步跑回调，否则每秒一次的
        // 自动截图都留下一个 1.6MB READBACK 缓冲（vanilla 回调里的 buffer.close() 永不执行）。
        if (this.device == null) {
            this.submit()
            check(MCDR_ABI.D3D12WaitForFence(ctx, MCDR_ABI.D3D12GetFenceValue(ctx), 5_000_000_000L)) {
                "Readback submission remains active"
            }
            drainCompletedCallbacks()
        }
    }

    override fun copyTextureToTexture(source: GpuTexture, destination: GpuTexture, mipLevel: Int,
        destX: Int, destY: Int, sourceX: Int, sourceY: Int, width: Int, height: Int) {
        this.ensureListOpen()
        MCDR_ABI.D3D12CopyTextureToTexture(this.ctx, textureHandle(source),
            textureHandle(destination), mipLevel, 0, sourceX, sourceY, destX, destY, width, height)
    }

    // ---- fences & timestamps ----

    override fun createFence(): GpuFence {
        this.ensureListOpen()
        // token = 本 encoder 当前序号 + 1，只有本 encoder 的下一次 submit 推进它。**不能**退回设备级
        // queueFenceValue：一次性 encoder（createBuffer(data) 的上传）提交时也会推进全局序列，而 vanilla 在
        // 帧中间取 token，会让 StagedVertexBuffer / MappableRingBuffer 的缓冲在帧提交前就被提前回收。
        val fenceValue = MCDR_ABI.D3D12GetFenceValue(this.ctx)
        val target = fenceValue + 1
        return object : GpuFence {
            private var completed = false

            override fun awaitCompletion(timeoutMs: Long): Boolean {
                if (!this.completed) {
                    val timeoutNs = if (timeoutMs > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else timeoutMs * 1_000_000L
                    this.completed = MCDR_ABI.D3D12WaitForFence(this@D3D12CommandEncoderBackend.ctx, target, timeoutNs)
                }
                return this.completed
            }

            override fun close() { this.completed = true }
        }
    }

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        this.ensureListOpen()
        MCDR_ABI.D3D12WriteTimestamp(this.ctx, (pool as D3D12GpuQueryPool).nativeHandle(), index)
    }

    // ---- submit lifecycle ----

    override fun submit() {
        check(!commandsDestroyed && !closing) { "Command encoder is closed" }
        // ASYNC 路径：主线程录制完 → 通知渲染线程 → 等 RECORDING_READY → 通知命令就绪 → 渲染线程
        // Close+Execute+Present；结算推迟到下一帧（[settlePendingSubmit]）。
        // allocator Reset + begin 必须只由**主线程**做（[ensureListOpen]）：渲染线程若 Reset 会丢掉整帧
        // 已录制的命令，back buffer 永远不被写入 → Present 只能显示未初始化内容（花屏根因）。
        ensureListOpen()

        val fenceBefore = MCDR_ABI.D3D12GetFenceValue(this.ctx)

        // 只有帧 encoder（device != null，即 D3D12Device.createCommandEncoder() 返回的共享 encoder）允许
        // 进入异步帧管线。一次性 encoder 走进来会劫持整帧：渲染线程替它 Close+Execute+Signal、把
        // gAsyncOwnsPresent 置 true 并 presentSurface（可能提前 Present → 闪旧画面/双 Present），还会把 frame
        // encoder 的 fence token 一起推走。它们一律走下面的同步路径。
        val asyncStarted = this.device != null && MCDR_ABI.D3D12AsyncRenderBeginFrame(this.ctx)
        if (!asyncStarted) {
            submissionFailed = true
            when (val outcome = MCDR_ABI.D3D12SubmitV1(this.ctx)) {
                is CommandSubmissionOutcome.Submitted -> {
                    check(outcome.fence > fenceBefore) { "Command submission failed to publish its fence" }
                    submissionFailed = false
                    acceptSubmission(outcome.fence)
                }
                CommandSubmissionOutcome.RecordingRejected -> {
                    submissionFailed = false
                    val failure = IllegalStateException("Command recording was rejected before submission")
                    recordingRejection = failure
                    throw failure
                }
                CommandSubmissionOutcome.OwnershipPending -> error("Command submission ownership remains pending")
                CommandSubmissionOutcome.SubmissionUnknown -> error("Command submission result is unknown; resources remain retained")
                CommandSubmissionOutcome.Ready -> error("Command submission returned no recorded work")
            }
            return
        }

        this.pendingFenceBefore = fenceBefore
        this.pendingSubmit = true

        // 记录本帧起点与相对上一帧的间隔（仅 PROF=1 生效）。
        val prof = RenderProfiling.enabled
        val profStart = System.nanoTime()
        if (prof) {
            // 累加而非覆盖：否则只统计到最后一个间隔。
            if (gProfLastSubmitNs != 0L) gProfIntervalNs += profStart - gProfLastSubmitNs
            gProfLastSubmitNs = profStart
        }

        // 等渲染线程到 RECORDING_READY：必须用 native 阻塞等待（WaitForSingleObject），
        // sleep(1) 轮询每帧在关键路径上多引入约 1ms 调度开销。超时保护不变。
        val profT0 = if (prof) System.nanoTime() else 0L
        MCDR_ABI.D3D12AsyncSendCommandsReady(this.ctx)
        val recordingReady = MCDR_ABI.D3D12AsyncRenderWaitRecordingReady(this.ctx, 5_000L)
        if (prof) gProfRecordingReadyNs += System.nanoTime() - profT0
        if (!recordingReady) {
            IllusionLogger.warnMessage("asyncRenderWaitRecordingReady timeout after 5000ms; ownership retained, ctx=0x"
                + this.ctx.toULong().toString(16))
            System.err.flush()
            error("Async recording handoff remains pending")
        }

        // 本帧**不再**阻塞等待提交完成：渲染线程此刻 Close+Execute+Signal+Present，主线程直接返回，
        // 结算推迟到下一帧开头（仍早于下一次 beginCommandList，见 [settlePendingSubmit]）。

        // 每 PROF_INTERVAL 帧输出一次各阶段耗时均值（ms）。
        if (prof) {
            ++gProfAsyncFrames
            gProfTotalNs += System.nanoTime() - profStart
            if (gProfAsyncFrames % PROF_INTERVAL == 0L) {
                val inv = 1.0 / PROF_INTERVAL
                IllusionLogger.debugMessage("prof n=${PROF_INTERVAL}"
                    + " frameInterval=${profMs(gProfIntervalNs * inv)}"
                    + "ms recordingReady=${profMs(gProfRecordingReadyNs * inv)}"
                    + "ms waitSubmit=${profMs(gProfWaitSubmitNs * inv)}"
                    + "ms submitTotal=${profMs(gProfTotalNs * inv)}ms")
                D3D12RenderProf.logRasterDrawTiming(PROF_INTERVAL)
                System.err.flush()
                gProfIntervalNs = 0L; gProfRecordingReadyNs = 0L
                gProfWaitSubmitNs = 0L; gProfTotalNs = 0L
            }
        }
    }
    fun close() {
        if (commandsDestroyed) {
            destroyCommandsAndReleaseResources()
            return
        }
        // close 前必须结算挂起的异步提交：两条路径都会在 close 之后立刻 dx12DestroyCommandEncoder，
        // 若渲染线程仍在 Close+Execute+Signal 该 ctx，销毁会与之竞争 → 悬空/崩溃。结算后渲染线程不再触碰本 ctx。
        settleCommandsForClosure()
        if (this.device == null) this.closing = true
        if (this.currentRenderPass != null) {
            val token = declaredRenderPassToken
            try {
                MCDR_ABI.D3D12EndRenderPass(this.ctx)
                recording.finishRasterPass(this.ctx, token)
            } catch (failure: Throwable) {
                resetFailedRasterPass(token, failure)
                throw failure
            } finally {
                declaredRenderPassToken = 0L
                this.currentRenderPass = null
            }
        }
        MCDR_ABI.D3D12EndCommandList(this.ctx)
        // 共享 encoder（device != null）由 D3D12Device.close() 销毁 CommandContext，这里**不能**调
        // dx12DestroyCommandEncoder（CubeMap.render() 等内部 close 会顺手销毁共享 ctx）；一次性 encoder 仍要销毁。
        if (this.device == null) {
            destroyCommandsAndReleaseResources()
        } else {
            drainCompletedCallbacks()
        }
    }

    companion object {
        // 帧时间插桩（PROF=1 才启用，正常游玩零开销）：量化 submit() 内部各同步阶段耗时。
        private const val PROF_INTERVAL = 300
        private var gProfAsyncFrames = 0L
        private var gProfLastSubmitNs = 0L
        private var gProfIntervalNs = 0L
        private var gProfRecordingReadyNs = 0L
        private var gProfWaitSubmitNs = 0L
        private var gProfTotalNs = 0L
    }
}

