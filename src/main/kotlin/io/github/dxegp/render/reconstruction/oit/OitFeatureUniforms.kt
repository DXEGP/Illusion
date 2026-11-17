package io.github.dxegp.render.reconstruction.oit

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.dxegp.d3d12.command.D3D12PreparedBufferUpload
import io.github.dxegp.d3d12.resource.D3D12GpuBuffer
import net.minecraft.client.renderer.DynamicGpuData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.TreeMap

internal data class OitFeatureColorModulatorIdentity(val red: Int, val green: Int, val blue: Int, val alpha: Int)

internal data class OitFeatureLightingIdentity(
    val light0X: Int, val light0Y: Int, val light0Z: Int,
    val light1X: Int, val light1Y: Int, val light1Z: Int,
)

internal data class OitFeatureUniformIdentity(
    val colorModulator: OitFeatureColorModulatorIdentity,
    val lighting: OitFeatureLightingIdentity?,
)

internal class OitFeatureColorWrite(val color: OitFeatureColorModulatorIdentity?)

internal interface OitFeatureTransformSerialization {
    fun illusionFeatureColorWrite(): OitFeatureColorWrite?
}

internal object OitFeatureUniforms {
    private class LightingUpload(val destination: GpuBufferSlice, val identity: OitFeatureLightingIdentity,
        val payload: ByteBuffer)

    private class UniformRange<Value>(val length: Long, val value: Value)

    private val colors = IdentityHashMap<GpuBuffer, TreeMap<Long, UniformRange<OitFeatureColorModulatorIdentity>>>()
    private val lighting = IdentityHashMap<GpuBuffer, TreeMap<Long, UniformRange<OitFeatureLightingIdentity>>>()
    private val colorOrder = LinkedHashSet<GpuBufferSlice>()
    private val lightingOrder = LinkedHashSet<GpuBufferSlice>()
    private var pendingLighting: LightingUpload? = null

    @Synchronized
    fun capture(uniforms: Map<String, GpuBufferSlice>): OitFeatureUniformIdentity? {
        RenderSystem.assertOnRenderThread()
        val transform = uniforms["DynamicTransforms"] ?: return null
        if (transform.buffer().isClosed) return null
        val color = colors[transform.buffer()]?.get(transform.offset())?.takeIf {
            it.length == transform.length()
        }?.value ?: return null
        val lights = uniforms["Lighting"]?.let { slice ->
            lighting[slice.buffer()]?.get(slice.offset())?.takeIf {
                !slice.buffer().isClosed && slice.length() >= 28L && slice.length() <= it.length
            }?.value
        }
        return OitFeatureUniformIdentity(color, lights)
    }

    fun recordTransformUpload(transform: DynamicGpuData.Transform, original: Operation<GpuBufferSlice>): GpuBufferSlice =
        recordTransformUploads(listOf(transform)) { listOf(original.call(transform)) }.single()

    fun recordTransformUploads(transforms: Array<DynamicGpuData.Transform>,
        original: Operation<Array<GpuBufferSlice>>): Array<GpuBufferSlice> =
        recordTransformUploads(transforms.toList()) { original.call(transforms).toList() }.toTypedArray()

    fun readColorWrite(buffer: ByteBuffer, start: Int): OitFeatureColorWrite {
        val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val colorOffset = start + 128
        val identity = if (start >= 0 && colorOffset >= start && colorOffset <= buffer.position() - 16 &&
            (0 until 4).all { bytes.getFloat(colorOffset + it * 4).isFinite() }) {
            OitFeatureColorModulatorIdentity(bytes.getInt(colorOffset), bytes.getInt(colorOffset + 4),
                bytes.getInt(colorOffset + 8), bytes.getInt(colorOffset + 12))
        } else null
        return OitFeatureColorWrite(identity)
    }

    @Synchronized
    private fun recordTransformUploads(transforms: List<DynamicGpuData.Transform>,
        action: () -> List<GpuBufferSlice>): List<GpuBufferSlice> {
        RenderSystem.assertOnRenderThread()
        val before = transforms.map { (it as OitFeatureTransformSerialization).illusionFeatureColorWrite() }
        val slices = try {
            action()
        } catch (failure: Throwable) {
            colors.clear()
            colorOrder.clear()
            throw failure
        }
        if (slices.size != transforms.size) {
            colors.clear()
            colorOrder.clear()
            return slices
        }
        for ((index, slice) in slices.withIndex()) {
            val written = (transforms[index] as OitFeatureTransformSerialization).illusionFeatureColorWrite()
            if (written === before[index]) continue
            invalidateBufferRange(slice)
            if (slice.buffer() !is D3D12GpuBuffer || slice.buffer().isClosed) continue
            written?.color?.let {
                saveRange(colors, colorOrder, 16384, slice, it)
            }
        }
        return slices
    }

    fun recordLightingUpload(encoder: CommandEncoder, destination: GpuBufferSlice, source: ByteBuffer,
        original: Operation<Void?>) {
        recordLightingUpload(destination, source) { original.call(encoder, destination, source) }
    }

    @Synchronized
    private fun recordLightingUpload(destination: GpuBufferSlice, source: ByteBuffer, action: () -> Unit) {
        RenderSystem.assertOnRenderThread()
        val bytes = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val offset = bytes.position()
        val identity = if (bytes.remaining() >= 28 && destination.length() >= 28L &&
            listOf(0, 4, 8, 16, 20, 24).all { bytes.getFloat(offset + it).isFinite() }) {
            OitFeatureLightingIdentity(bytes.getInt(offset), bytes.getInt(offset + 4), bytes.getInt(offset + 8),
                bytes.getInt(offset + 16), bytes.getInt(offset + 20), bytes.getInt(offset + 24))
        } else null
        invalidateBufferRange(destination)
        val previous = pendingLighting
        pendingLighting = if (destination.buffer() is D3D12GpuBuffer && identity != null)
            LightingUpload(destination, identity, source) else null
        try {
            action()
        } catch (failure: Throwable) {
            invalidateBufferRange(destination)
            throw failure
        } finally {
            pendingLighting = previous
        }
    }

    @Synchronized
    fun prepareLightingUpload(destination: GpuBufferSlice, data: ByteBuffer): D3D12PreparedBufferUpload? {
        val upload = pendingLighting?.takeIf { it.destination == destination && it.payload === data } ?: return null
        RenderSystem.assertOnRenderThread()
        return object : D3D12PreparedBufferUpload {
            override val payload: ByteBuffer = data

            override fun confirmRecorded() {
                synchronized(this@OitFeatureUniforms) {
                    check(pendingLighting === upload) { "FEATURE lighting upload left its producer scope" }
                    if (destination.buffer().isClosed) return
                    saveRange(lighting, lightingOrder, 64, destination, upload.identity)
                }
            }

            override fun discard() {
                invalidateBufferRange(destination)
            }
        }
    }

    @Synchronized
    fun invalidateBufferRange(slice: GpuBufferSlice) {
        revokeRanges(colors, colorOrder, slice)
        revokeRanges(lighting, lightingOrder, slice)
    }

    private fun <Value> saveRange(storage: IdentityHashMap<GpuBuffer, TreeMap<Long, UniformRange<Value>>>,
        order: LinkedHashSet<GpuBufferSlice>, capacity: Int, slice: GpuBufferSlice, value: Value) {
        storage.getOrPut(slice.buffer()) { TreeMap() }[slice.offset()] = UniformRange(slice.length(), value)
        order.add(slice)
        while (order.size > capacity) {
            val retired = order.first()
            order.remove(retired)
            val ranges = checkNotNull(storage[retired.buffer()])
            ranges.remove(retired.offset())
            if (ranges.isEmpty()) storage.remove(retired.buffer())
        }
    }

    private fun <Value> revokeRanges(storage: IdentityHashMap<GpuBuffer, TreeMap<Long, UniformRange<Value>>>,
        order: LinkedHashSet<GpuBufferSlice>, slice: GpuBufferSlice) {
        val ranges = storage[slice.buffer()] ?: return
        if (slice.length() <= 0L) return
        val end = if (slice.offset() > Long.MAX_VALUE - slice.length()) Long.MAX_VALUE else slice.offset() + slice.length()
        ranges.floorEntry(slice.offset())?.takeIf { (offset, range) ->
            range.length > slice.offset() - offset
        }?.let {
            order.remove(GpuBufferSlice(slice.buffer(), it.key, it.value.length))
            ranges.remove(it.key)
        }
        var next = ranges.ceilingEntry(slice.offset())
        while (next != null && next.key < end) {
            order.remove(GpuBufferSlice(slice.buffer(), next.key, next.value.length))
            ranges.remove(next.key)
            next = ranges.ceilingEntry(slice.offset())
        }
        if (ranges.isEmpty()) storage.remove(slice.buffer())
    }

    @Synchronized
    fun retireTransforms() {
        RenderSystem.assertOnRenderThread()
        colors.clear()
        colorOrder.clear()
    }

    @Synchronized
    fun retireBuffer(buffer: D3D12GpuBuffer) {
        colors.remove(buffer)?.forEach { (offset, range) -> colorOrder.remove(GpuBufferSlice(buffer, offset, range.length)) }
        lighting.remove(buffer)?.forEach { (offset, range) -> lightingOrder.remove(GpuBufferSlice(buffer, offset, range.length)) }
    }
}
