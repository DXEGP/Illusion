package dev.nts.expdxr.dxrmc.rt

import com.mojang.renderpearl.api.buffers.GpuBuffer
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.core.D3D12GpuBuffer
import dev.nts.expdxr.misc.IllusionLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier

/**
 * S3 的加速结构管理器：一个镜像切片的每条段记录对应一条 BLAS，TLAS 每帧重建一次。
 * 见 docs/rt/README.md §13。
 *
 * 两条不能违反的规则：BLAS 的顶点 / 索引必须指向**镜像缓冲**（默认堆），否则原生侧直接拒绝；
 * 实例的 3×4 矩阵只写平移 —— 镜像顶点是段内局部坐标，世界位置取自记录的 origin。
 * 实例的 `InstanceID` 写**记录下标**，着色器命中后用它在 `gRecords` 里取同一份材质与顶点。
 *
 * 这是实例而不是单例：自检要一份只含一颗三角形的独立加速结构，与渲染线程那份互不干扰。
 */
internal class D3D12RtAccel {
    private companion object {
        /** 镜像记录宽度，与 `D3D12RtGeometryPool.mirrorNear` 写出的 64 B 记录一一对应。 */
        const val RECORD_BYTES = 64

        /** `D3D12_RAYTRACING_INSTANCE_DESC` 固定 64 B。 */
        const val INSTANCE_BYTES = 64

        const val VERTEX_STRIDE = 28L
        const val UAV_USAGE = 1024
        const val MIN_BYTES = 256L

        /** 主光线只看静态不透明几何；动态 / 半透明留到 S4。 */
        const val INSTANCE_MASK = 0x01

        /** 同一条 BLAS 连续 refit 的上限，超过就整条重建（refit 会让遍历变慢）。 */
        const val REFIT_LIMIT = 2

        /** 段从镜像里消失多少帧后回收它的 BLAS；留一段缓冲，来回走不会被反复重建。 */
        const val EVICT_FRAMES = 180L

        /** SBT 命中组记录的下标：与 `D3D12RtDxr` 写 SBT 的顺序一致（opaque 在前、cutout 在后）。 */
        const val HIT_GROUP_OPAQUE = 0

        const val HIT_GROUP_CUTOUT = 1
    }

    private class Entry(
        var version: Int,
        var result: Long,
        var resultBytes: Long,
        var compact: Long,
        var vertexCount: Int,
        var indexCount: Int,
        var indexStride: Int,
    ) {
        var built = false
        var refits = 0
        /** 本条的 scratch 需求：只有创建/重建时问一次，别每帧对每条几何再问一遍。 */
        var scratchBytes = 0L
        var compactValid = false

        /** 最后一次出现在镜像里的帧号；太久不出现就回收（走远了不该继续占显存）。 */
        var lastSeen = 0L

        /** 交给 TLAS 的地址：压缩过就用紧凑版，否则用原始结果。 */
        fun address(): Long = if (compactValid && compact != 0L) compact else result

        fun close() {
            for (handle in longArrayOf(result, compact)) {
                if (handle != 0L) MCDR_ABI.ID3D12DestroyResource(handle)
            }
            result = 0L
            compact = 0L
            compactValid = false
            built = false
        }
    }

    private val entries = HashMap<Long, Entry>()
    /* prepare 在渲染线程、release 可能在换包/关光追的另一个线程：互斥，避免释放正在写的加速结构。 */
    private val lock = Any()
    /** BLAS 的 scratch 必须是普通 UAV 缓冲（初始 COMMON）：拿加速结构状态的缓冲当 scratch，驱动会判非法并扛不住整帧。 */
    private var scratchBuffer: D3D12GpuBuffer? = null
    private var scratchBytes = 0L
    private var instanceBuffer: D3D12GpuBuffer? = null
    private var instanceCapacity = 0
    private var tlas = 0L
    private var tlasBytes = 0L
    private var tlasScratchBuffer: D3D12GpuBuffer? = null
    private var tlasScratchBytes = 0L
    private var tlasView = 0L
    private var liveInstances = 0
    private var frameId = 0L
    private var infoLogged = false
    private var warned = false
    private var contractChecked = false

    /** 给着色器的 `RaytracingAccelerationStructure` SRV；0 = 这帧没有 TLAS。 */
    fun view(): Long = tlasView

    /** 这帧 TLAS 里的实例数（成功构建时等于镜像记录数）。 */
    fun instances(): Int = liveInstances

    /** 实例表（64 B/条）的 GPU 地址；调试图的线框直接读它，见 §14.6。 */
    fun instanceTable(): Long = instanceBuffer?.handle() ?: 0L

    /** 加速结构自己占的显存（结果 + 紧凑 + scratch + 实例表），只用于统计。 */
    /** 最近一次几何契约检查的结论（给自检报告用，不进日志）。 */
    private var lastContract = "not checked"

    /** 最近一次 prepare 的计数摘要（给自检报告用）。 */
    private var lastSummary = "not prepared"

    fun contractSummary(): String = lastContract

    fun summary(): String = lastSummary

    fun vramBytes(): Long = synchronized(lock) {
        var total = scratchBytes + tlasBytes + tlasScratchBytes +
            instanceCapacity.toLong() * INSTANCE_BYTES
        for (entry in entries.values) {
            total += entry.resultBytes + if (entry.compact != 0L) entry.resultBytes else 0L
        }
        return total
    }

    /** 光追关掉时调用：整套释放，下次打开重头建。 */
    fun release() = synchronized(lock) {
        for (entry in entries.values) {
            entry.close()
        }
        entries.clear()
        for (handle in longArrayOf(tlas, tlasView)) {
            if (handle != 0L) MCDR_ABI.ID3D12DestroyResource(handle)
        }
        for (buffer in listOfNotNull(scratchBuffer, tlasScratchBuffer)) {
            try {
                buffer.close()
            } catch (_: Throwable) {
                // 设备已经没了的时候释放失败没有意义，计数照样清零。
            }
        }
        scratchBuffer = null
        scratchBytes = 0L
        tlas = 0L
        tlasBytes = 0L
        tlasScratchBuffer = null
        tlasScratchBytes = 0L
        tlasView = 0L
        liveInstances = 0
        instanceBuffer?.let { buffer ->
            try {
                buffer.close()
            } catch (_: Throwable) {
                // 释放失败没有补救动作，下一次打开光追会重新建。
            }
        }
        instanceBuffer = null
        instanceCapacity = 0
    }

    /**
     * 在**已打开**的命令列表上补齐 BLAS 并重建 TLAS。`records` 是镜像记录表（CPU 侧那份字节），
     * `vertexBuffer` / `indexBuffer` 是镜像缓冲的句柄。返回 false 表示这帧没有可用的加速结构。
     */
    /**
     * 跑一次几何契约检查（每个进程一次）。默认档（MEGA、不建加速结构）也必须跑：
     * 「顶点整颗落在自己段的盒内」是暴力档与加速结构档结果一致的前提，不建 AS 时更没人替它把关。
     */
    fun checkContractOnce(view: ByteBuffer, recordCount: Int, vertexBytes: Long, indexBytes: Long): Unit =
        synchronized(lock) {
            if (contractChecked) return
            contractChecked = true
            checkContract(view, recordCount, vertexBytes, indexBytes)
        }

    fun prepare(
        device: D3D12Device,
        ctx: Long,
        records: ByteBuffer,
        recordCount: Int,
        vertexBuffer: Long,
        indexBuffer: Long,
        vertexBytes: Long = 0L,
        indexBytes: Long = 0L,
    ): Boolean = synchronized(lock) {
        if (recordCount <= 0) return false
        val view = records.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (recordCount.toLong() * RECORD_BYTES > view.capacity()) return false
        // G3 的第一条验收（几何契约完整性）：一次就够，记录变化本身由 geometryVersion 兜住。
        if (!contractChecked) {
            contractChecked = true
            checkContract(view, recordCount, vertexBytes, indexBytes)
        }
        frameId++
        val started = System.nanoTime()
        var builds = 0
        /* refit 计数恒为 0：任何重新上传都会 bump `geometryVersion`，走不到 `PERFORM_UPDATE`（§13.1）。 */
        val refits = 0
        var compacts = 0
        // 本帧已经录了几条 build：第二条起要先插 UAV 屏障（共用同一块 scratch）。
        var builtThisFrame = 0
        var maxScratch = 0L
        // 单条 BLAS 录制的峰值耗时：§3.6 的成本预算按"每段"给，只有峰值能说明最贵的那一段。
        var peakBuildMicros = 0L

        // 1) 上一帧建好的先压缩：紧凑拷贝要等那次提交执行完，所以一律晚一帧。
        for (entry in entries.values) {
            if (entry.built && !entry.compactValid && entry.compact != 0L && entry.result != 0L) {
                if (MCDR_ABI.D3D12CopyAccelStruct(ctx, entry.compact, entry.result)) {
                    entry.compactValid = true
                    compacts++
                } else {
                    fail("compact failed: ${MCDR_ABI.D3D12LastError()}")
                }
            }
        }

        // 2) 逐条段记录：版本 / 计数变了才动 BLAS。
        val addresses = LongArray(recordCount)
        val origins = IntArray(recordCount * 3)
        // 每条记录的 SBT 命中组下标：镂空层走 HITCUTOUT，其余走 HITOPAQUE（见 D3D12RtDxr）。
        val hitGroupOf = IntArray(recordCount) { HIT_GROUP_OPAQUE }
        val live = ArrayList<Long>(recordCount)
        for (i in 0 until recordCount) {
            val base = i * RECORD_BYTES
            val originX = view.getInt(base)
            val originY = view.getInt(base + 4)
            val originZ = view.getInt(base + 8)
            val layer = view.getInt(base + 12)
            val vertexOffset = view.getLong(base + 16)
            val indexOffset = view.getLong(base + 24)
            val vertexCount = view.getInt(base + 32)
            val indexCount = view.getInt(base + 36)
            val strideAndFlags = view.getInt(base + 40)
            val version = view.getInt(base + 44)
            val node = view.getLong(base + 56)
            if (vertexCount <= 0 || indexCount <= 0) continue
            val indexStride = strideAndFlags and 0xFF
            // 记录里那 8 位是**层标志位**（SOLID=1、CUTOUT=2、TRANSLUCENT=4），不是"是否做 alpha 测试"。
            // 只有后两层需要非不透明几何：把 SOLID 也标成 GEOMETRY_FLAG_NONE，每次命中都会走候选路径，
            // 白白放弃驱动的"不透明三角形"快路（而且 DXR 侧没有 any-hit 时行为由实现决定）。
            val layerFlags = (strideAndFlags ushr 8) and 0xFF
            val useAlphaTest = (layerFlags and 0x6) != 0
            hitGroupOf[i] = if (useAlphaTest) HIT_GROUP_CUTOUT else HIT_GROUP_OPAQUE
            val key = (node shl 4) or (layer.toLong() and 0xFL)
            val geometry = MCDR_ABI.RtGeometry(
                vertexBuffer = vertexBuffer,
                vertexStride = VERTEX_STRIDE,
                vertexCount = vertexCount.toLong(),
                vertexOffset = vertexOffset,
                indexBuffer = indexBuffer,
                indexCount = indexCount.toLong(),
                indexOffset = indexOffset,
                indexStride = indexStride,
                useAlphaTest = useAlphaTest,
            )
            var entry = entries[key]
            entry?.lastSeen = frameId
            var rebuild = false
            if (entry == null) {
                val prebuild = MCDR_ABI.D3D12GetBlasPrebuildInfo(listOf(geometry))
                if (prebuild == null) {
                    fail("blas prebuild failed: ${MCDR_ABI.D3D12LastError()}")
                    continue
                }
                val resultBytes = prebuild[0].coerceAtLeast(MIN_BYTES)
                val result = MCDR_ABI.D3D12CreateAccelStructBuffer(resultBytes)
                if (result == 0L) {
                    fail("blas buffer failed: ${MCDR_ABI.D3D12LastError()}")
                    continue
                }
                val compact = MCDR_ABI.D3D12CreateAccelStructBuffer(resultBytes)
                entry = Entry(version, result, resultBytes, compact, vertexCount, indexCount, indexStride)
                entry.lastSeen = frameId
                entries[key] = entry
                rebuild = true
                entry.scratchBytes = prebuild[1]
            } else if (entry.version != version || entry.vertexCount != vertexCount ||
                entry.indexCount != indexCount || entry.indexStride != indexStride ||
                !entry.built || entry.refits >= REFIT_LIMIT
            ) {
                rebuild = true
                entry.version = version
                entry.vertexCount = vertexCount
                entry.indexCount = indexCount
                entry.indexStride = indexStride
                entry.refits = 0
                val prebuild = MCDR_ABI.D3D12GetBlasPrebuildInfo(listOf(geometry))
                if (prebuild != null) entry.scratchBytes = prebuild[1]
            }
            if (entry.result == 0L) continue
            // 版本、拓扑、索引宽度都没变：这条 BLAS 仍然有效，直接复用。
            // 以前这里会对"没变的段"也录一次 PERFORM_UPDATE，稳态下 384 段每帧白跑一遍
            // （按 §3.6 实测的每段 ~10 µs 算就是 4 ms 左右）；池子只要重新上传就会 bump
            // geometryVersion，所以"没变"就是真的没变。
            if (!rebuild && entry.built) {
                addresses[i] = entry.address()
                origins[i * 3] = originX
                origins[i * 3 + 1] = originY
                origins[i * 3 + 2] = originZ
                live.add(entry.address())
                continue
            }
            maxScratch = maxOf(maxScratch, entry.scratchBytes)
            val chunk = ensureScratch(device, maxScratch)
            if (chunk == 0L) {
                fail("blas scratch failed: ${MCDR_ABI.D3D12LastError()}")
                continue
            }
            // 走到这里的都是重建：没变的段在上面那条复用分支里已经 continue 了。
            // 计划里的 refit（PERFORM_UPDATE）暂时用不上 —— 池子对任何重新上传都会 bump
            // geometryVersion，分不清"只是顶点移动"和"拓扑也变了"，所以只保留整条重建这一条路。
            // 见 docs/rt/README.md §13.1
            val update = false
            // 多条 build 共用一个 scratch：D3D12 要求两次使用之间插 UAV 屏障，缺了会读到上一段还在写的
            // scratch（表现是随机的挂死/TDR）。见 docs/rt/README.md §13.6
            if (builtThisFrame > 0) {
                MCDR_ABI.D3D12Barrier(ctx, listOf(
                    MCDR_ABI.BarrierDesc(chunk, 0, 0, MCDR_ABI.BarrierKind.UAV),
                ))
            }
            val buildStart = System.nanoTime()
            if (!MCDR_ABI.D3D12BuildBlas(ctx, entry.result, chunk, listOf(geometry), update)) {
                fail("blas build failed: ${MCDR_ABI.D3D12LastError()}")
                continue
            }
            builtThisFrame++
            peakBuildMicros = maxOf(peakBuildMicros, (System.nanoTime() - buildStart) / 1000L)
            entry.built = true
            entry.refits = 0
            // 重建会让旧紧凑版失效，下一帧重新压一次。
            entry.compactValid = false
            builds++
            addresses[i] = entry.address()
            origins[i * 3] = originX
            origins[i * 3 + 1] = originY
            origins[i * 3 + 2] = originZ
            live.add(entry.address())
        }
        if (live.isEmpty()) return false
        // 走远的段回收：BLAS 是按段常驻的，不收回合越走越多。
        if (frameId % 60L == 0L) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (frameId - item.value.lastSeen > EVICT_FRAMES) {
                    item.value.close()
                    iterator.remove()
                }
            }
        }

        // 3) TLAS：实例表在记录顺序上紧凑排列，只写平移。
        //    从这里到结尾的耗时单独计，BLAS 与 TLAS 的成本不能混在一个数里（§13.4）。
        val tlasStart = System.nanoTime()
        val count = live.size
        val table = ensureInstances(device, count) ?: return false
        writeInstances(table, count, addresses, origins, hitGroupOf)
        val tlasInfo = MCDR_ABI.D3D12GetTlasPrebuildInfo(count)
        if (tlasInfo == null) {
            fail("tlas prebuild failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        if (!ensureTlas(device, tlasInfo[0].coerceAtLeast(MIN_BYTES), tlasInfo[1].coerceAtLeast(MIN_BYTES))) {
            return false
        }
        if (!MCDR_ABI.D3D12BuildTlas(ctx, tlas, tlasScratchBuffer?.handle() ?: 0L, table.handle(), count)) {
            fail("tlas build failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        if (tlasView == 0L) {
            tlasView = MCDR_ABI.D3D12CreateAccelStructView(tlas)
            if (tlasView == 0L) {
                fail("tlas view failed: ${MCDR_ABI.D3D12LastError()}")
                return false
            }
        }
        liveInstances = count
        val totalMicros = (System.nanoTime() - started) / 1000L
        D3D12RtStats.onBlasBuilt(builds, refits)
        D3D12RtStats.onBlasCompacted(compacts, vramBytes(), scratchBytes)
        D3D12RtStats.onBlasEncoded((tlasStart - started) / 1000L, peakBuildMicros)
        D3D12RtStats.onTlasBuilt(count, (System.nanoTime() - tlasStart) / 1000L)
        lastSummary = "inst=$count blas=${entries.size} b=$builds r=$refits c=$compacts " +
            "ms=${totalMicros / 1000} vram=${vramBytes() / (1024 * 1024)}MB"
        if (!infoLogged) {
            infoLogged = true
            IllusionLogger.infoMessage(
                "as: inst=$count blas=${entries.size} b=$builds r=$refits " +
                    "c=$compacts ms=${totalMicros / 1000}"
            )
        }
        return true
    }

    /** 3×4 行主序平移矩阵 + 实例号/掩码 + 命中组下标 + BLAS 地址，每条 64 B。 */
    private fun writeInstances(
        buffer: D3D12GpuBuffer,
        count: Int,
        addresses: LongArray,
        origins: IntArray,
        hitGroupOf: IntArray,
    ) {
        val bytes = count.toLong() * INSTANCE_BYTES
        buffer.map(0L, bytes, false, true).use { slice ->
            val out = slice.data()
            var written = 0
            for (i in addresses.indices) {
                val address = addresses[i]
                if (address == 0L) continue
                val base = written * INSTANCE_BYTES
                val origin = i * 3
                val row0 = origins[origin]
                val row1 = origins[origin + 1]
                val row2 = origins[origin + 2]
                out.putFloat(base, 1.0f)
                out.putFloat(base + 4, 0.0f)
                out.putFloat(base + 8, 0.0f)
                out.putFloat(base + 12, row0.toFloat())
                out.putFloat(base + 16, 0.0f)
                out.putFloat(base + 20, 1.0f)
                out.putFloat(base + 24, 0.0f)
                out.putFloat(base + 28, row1.toFloat())
                out.putFloat(base + 32, 0.0f)
                out.putFloat(base + 36, 0.0f)
                out.putFloat(base + 40, 1.0f)
                out.putFloat(base + 44, row2.toFloat())
                out.putInt(base + 48, (i and 0xFFFFFF) or (INSTANCE_MASK shl 24))
                // 高位 24 位是贡献下标：SBT 里 opaque 记录在 0、cutout 在 1，写反了镂空层会被当不透明。
                out.putInt(base + 52, hitGroupOf[i] and 0xFFFFFF)
                // 这里要的是 BLAS 的显存地址，不是对象句柄：写句柄等于把堆指针当显存地址，实例全部指向不存在的地方。
                val gpuAddress = MCDR_ABI.D3D12GetBufferAddress(address)
                if (gpuAddress == 0L) {
                    warn("no gpu address for a blas")
                    continue
                }
                out.putLong(base + 56, gpuAddress)
                written++
            }
        }
    }

    /**
     * BLAS 的 scratch。**不能用加速结构缓冲**：它创建时就在 `RAYTRACING_ACCELERATION_STRUCTURE` 状态，
     * 而 scratch 必须处于 `UNORDERED_ACCESS`；状态不对时驱动不报错，只是把整帧判死（设备移除）。
     * 这里用普通 UAV 缓冲（初始 `COMMON`，首次写入隐式提升到 `UNORDERED_ACCESS`）。
     */
    private fun ensureScratch(device: D3D12Device, bytes: Long): Long {
        val wanted = bytes.coerceAtLeast(MIN_BYTES)
        val current = scratchBuffer
        if (current != null && scratchBytes >= wanted) return current.handle()
        val created = try {
            device.createBuffer(Supplier { "rt-blas-scratch" }, UAV_USAGE, wanted) as? D3D12GpuBuffer
        } catch (t: Throwable) {
            fail("blas scratch failed: ${t.message}")
            return 0L
        }
        // 分配失败时原生返回 null：`as` 会抛一个没有 message 的 NPE，必须在这里挡住并说清是哪个缓冲。
        if (created == null) {
            fail("blas scratch allocate failed (${MCDR_ABI.D3D12LastError()})")
            return 0L
        }
        current?.let { buffer ->
            try {
                buffer.close()
            } catch (_: Throwable) {
                // 换缓冲失败不影响新建的那块。
            }
        }
        scratchBuffer = created
        scratchBytes = wanted
        return created.handle()
    }

    private fun ensureTlas(device: D3D12Device, resultBytes: Long, scratchSize: Long): Boolean {
        if (tlas != 0L && tlasBytes >= resultBytes && tlasScratchBytes >= scratchSize) return true
        val result = MCDR_ABI.D3D12CreateAccelStructBuffer(resultBytes)
        if (result == 0L) {
            fail("tlas buffer failed: ${MCDR_ABI.D3D12LastError()}")
            return false
        }
        // TLAS 的 scratch 同样是普通 UAV 缓冲，理由见 ensureScratch。
        val buffer = try {
            device.createBuffer(Supplier { "rt-tlas-scratch" }, UAV_USAGE, scratchSize) as? D3D12GpuBuffer
        } catch (t: Throwable) {
            MCDR_ABI.ID3D12DestroyResource(result)
            fail("tlas scratch failed: ${t.message}")
            return false
        }
        if (tlas != 0L) MCDR_ABI.ID3D12DestroyResource(tlas)
        tlasScratchBuffer?.let { old ->
            try {
                old.close()
            } catch (_: Throwable) {
                // 同 ensureScratch。
            }
        }
        // TLAS 换了缓冲，旧视图指向已销毁的资源：必须重建，否则着色器拿到悬空地址。
        if (tlasView != 0L) {
            MCDR_ABI.ID3D12DestroyResource(tlasView)
            tlasView = 0L
        }
        tlas = result
        tlasBytes = resultBytes
        tlasScratchBuffer = buffer
        tlasScratchBytes = scratchSize
        return true
    }

    private fun ensureInstances(device: D3D12Device, count: Int): D3D12GpuBuffer? {
        val current = instanceBuffer
        if (current != null && instanceCapacity >= count) return current
        val capacity = maxOf(count, instanceCapacity * 2, 64)
        val created = try {
            // 上传堆：CPU 每帧直写，GPU 侧天然就是 InstanceDescs 需要的可读状态。
            device.createBuffer(
                Supplier { "rt-tlas-instances" },
                GpuBuffer.USAGE_MAP_WRITE,
                capacity.toLong() * INSTANCE_BYTES,
            ) as? D3D12GpuBuffer
        } catch (t: Throwable) {
            fail("instance buffer failed: ${t.message}")
            return current
        }
        current?.let { buffer ->
            try {
                buffer.close()
            } catch (_: Throwable) {
                // 同上。
            }
        }
        instanceBuffer = created
        instanceCapacity = capacity
        return created
    }

    /**
     * 几何契约检查（G3 的第一条验收）：镜像记录的字段必须自洽，否则 BLAS 会按错的偏移读顶点/索引，
     * 命中结果只是"看起来没问题"的垃圾。只跑一次——记录内容的变化由 `geometryVersion` 兜住。
     */
    private fun checkContract(view: ByteBuffer, recordCount: Int, vertexBytes: Long, indexBytes: Long) {
        var empty = 0
        var badIndices = 0
        var badStride = 0
        var badVersion = 0
        var unaligned = 0
        var outOfRange = 0
        for (i in 0 until recordCount) {
            val base = i * RECORD_BYTES
            val vertexOffset = view.getLong(base + 16)
            val indexOffset = view.getLong(base + 24)
            val vertexCount = view.getInt(base + 32)
            val indexCount = view.getInt(base + 36)
            val strideAndFlags = view.getInt(base + 40)
            val version = view.getInt(base + 44)
            if (vertexCount <= 0 || indexCount <= 0) {
                empty++
                continue
            }
            val indexStride = strideAndFlags and 0xFF
            if (indexCount % 3 != 0) badIndices++
            if (indexStride != 2 && indexStride != 4) badStride++
            if (version <= 0) badVersion++
            // 原生侧按 4 字节对齐取半字（见 docs/rt/README.md 的索引读取约束），偏移不对就是错的三角形。
            if (vertexOffset and 3L != 0L || indexOffset and 3L != 0L) unaligned++
            if (vertexBytes > 0L && vertexOffset + vertexCount.toLong() * VERTEX_STRIDE > vertexBytes) {
                outOfRange++
            }
            if (indexBytes > 0L && indexOffset + indexCount.toLong() * indexStride > indexBytes) {
                outOfRange++
            }
        }
        IllusionLogger.infoMessage(
            "rt geometry: contract records=$recordCount empty=$empty indices=$badIndices stride=$badStride " +
                "version=$badVersion unaligned=$unaligned outOfRange=$outOfRange",
        )
        lastContract = "records=$recordCount empty=$empty indices=$badIndices stride=$badStride " +
            "version=$badVersion unaligned=$unaligned outOfRange=$outOfRange"
        val problems = empty + badIndices + badStride + badVersion + unaligned + outOfRange
        if (problems > 0) {
            warn("geometry contract: $problems record(s) violate the mirror layout")
        }
    }

    private fun warn(reason: String) {
        IllusionLogger.warnMessage("as: $reason")
    }

    private fun fail(reason: String) {
        if (!warned) {
            warned = true
            IllusionLogger.warnMessage("as: $reason")
        }
    }
}

/**
 * 渲染线程用的那一份加速结构（跟随镜像几何，全局唯一）。
 * 自检需要独立的一份时用 [newAccel]，两者各建各的 TLAS。
 */
internal object D3D12RtBlas {
    private val frame = D3D12RtAccel()

    fun view(): Long = frame.view()

    fun instances(): Int = frame.instances()

    fun instanceTable(): Long = frame.instanceTable()

    fun vramBytes(): Long = frame.vramBytes()

    fun contractSummary(): String = frame.contractSummary()

    fun summary(): String = frame.summary()

    fun release() = frame.release()

    fun checkContractOnce(view: ByteBuffer, recordCount: Int, vertexBytes: Long, indexBytes: Long) =
        frame.checkContractOnce(view, recordCount, vertexBytes, indexBytes)

    fun prepare(
        device: D3D12Device,
        ctx: Long,
        records: ByteBuffer,
        recordCount: Int,
        vertexBuffer: Long,
        indexBuffer: Long,
        vertexBytes: Long = 0L,
        indexBytes: Long = 0L,
    ): Boolean = frame.prepare(
        device, ctx, records, recordCount, vertexBuffer, indexBuffer, vertexBytes, indexBytes,
    )

    /** 自检实例的几何契约：世界路径没跑过时，报告里的契约数据只能来自这一份。 */
    private var selftestContract = "none"

    /** 自检实例的加速结构计数；与上面分开存，报告里两列才不会写成一模一样的一段。 */
    private var selftestBlas = "none"

    fun selftestContractSummary(): String = selftestContract

    fun selftestBlasSummary(): String = selftestBlas

    fun noteSelftest(accel: D3D12RtAccel) {
        selftestContract = accel.contractSummary()
        selftestBlas = accel.summary()
    }

    /** 自检用的独立实例，用完必须 [D3D12RtAccel.release]。 */
    fun newAccel(): D3D12RtAccel = D3D12RtAccel()
}
