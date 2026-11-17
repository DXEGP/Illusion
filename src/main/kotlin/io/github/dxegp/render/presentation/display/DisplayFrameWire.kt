package io.github.dxegp.render.presentation.display

import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.render.frame.vanilla.FrameWireLayout
import io.github.dxegp.render.frame.vanilla.PreparedBinding
import io.github.dxegp.render.frame.vanilla.VanillaCamera
import io.github.dxegp.render.frame.vanilla.VanillaFrameIdentity
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import io.github.dxegp.d3d12.ffi.allocateFfmArray

internal object DisplayFrameWire {
    fun prepare(context: Long, value: DisplayFrameInputs): Int = encode(value, null) {
        MCDR_ABI.D3D12PrepareDisplayFrame(context, it)
    }

    fun submit(context: Long, value: DisplayFrameInputs, ui: DisplayUiInputs): Int = encode(value, ui) {
        MCDR_ABI.D3D12SubmitDisplayFrame(context, it)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun encode(
        value: DisplayFrameInputs,
        ui: DisplayUiInputs?,
        call: (MemorySegment) -> Int,
    ): Int = Arena.ofConfined().use { arena ->
        val layout = MCDRLayout.DISPLAY_FRAME_INPUT
        val input = arena.allocate(layout)
        val frame = value.frame
        input.set(MCDRLayout.I32, Offsets.schemaVersion, 2)
        input.set(MCDRLayout.I32, Offsets.structSize, Offsets.size.toInt())
        identity(input.asSlice(Offsets.identity, FrameWireLayout.Identity.size), frame.identity)
        input.set(MCDRLayout.I64, Offsets.historyGeneration, frame.historyGeneration)
        input.set(MCDRLayout.I64, Offsets.previousRealFrameId, value.previousRealFrameId)
        input.set(MCDRLayout.I32, Offsets.width, frame.displayWidth)
        input.set(MCDRLayout.I32, Offsets.height, frame.displayHeight)
        input.set(MCDRLayout.I32, Offsets.flags, (if (value.previous != null) 1 else 0) or
            (if (ui?.containsScene == true) 2 else 0))
        input.set(MCDRLayout.I32, Offsets.resetReasons, value.resetReasons)
        input.set(MCDRLayout.F32, Offsets.frameDeltaMs, frame.frameDeltaMs)
        input.set(MCDRLayout.F32, Offsets.viewSpaceToMeters, 1.0f)
        input.set(MCDRLayout.I32, Offsets.frameGenerationBackend, frame.settings.frameGenerationBackend)
        input.set(MCDRLayout.I32, Offsets.frameGenerationMode, frame.settings.frameGenerationMode)
        input.set(MCDRLayout.I32, Offsets.frameGenerationFrames, frame.settings.frameGenerationFrames)
        input.set(MCDRLayout.I32, Offsets.colorEncoding, 1)
        binding(input.asSlice(Offsets.scene, FrameWireLayout.Binding.size), value.scene)
        ui?.let { binding(input.asSlice(Offsets.ui, FrameWireLayout.Binding.size), it.texture) }
        camera(input.asSlice(Offsets.current, FrameWireLayout.Camera.size), value.current)
        value.previous?.let { camera(input.asSlice(Offsets.previous, FrameWireLayout.Camera.size), it) }
        val layerLayout = MCDRLayout.DISPLAY_LAYER_INPUT
        val layerArray = arena.allocateFfmArray(value.layers.size, layerLayout)
        value.layers.forEachIndexed { index, layer ->
            val destination = layerArray.asSlice(index * LayerOffsets.size, LayerOffsets.size)
            destination.set(MCDRLayout.I32, LayerOffsets.structSize, LayerOffsets.size.toInt())
            destination.set(MCDRLayout.I32, LayerOffsets.kind, layer.kind.wireValue)
            destination.set(MCDRLayout.I32, LayerOffsets.flags, (if (layer.previous != null) 1 else 0) or
                (if (layer.motionValid) 2 else 0) or (if (layer.mixedSurface) 4 else 0))
            destination.set(MCDRLayout.I32, LayerOffsets.composition, layer.composition.wireValue)
            destination.set(MCDRLayout.I32, LayerOffsets.width, layer.width)
            destination.set(MCDRLayout.I32, LayerOffsets.height, layer.height)
            binding(destination.asSlice(LayerOffsets.depth, FrameWireLayout.Binding.size), layer.depth)
            layer.motion?.let { binding(destination.asSlice(LayerOffsets.motion, FrameWireLayout.Binding.size), it) }
            layer.coverage?.let { binding(destination.asSlice(LayerOffsets.coverage, FrameWireLayout.Binding.size), it) }
            camera(destination.asSlice(LayerOffsets.current, FrameWireLayout.Camera.size), layer.current)
            layer.previous?.let { camera(destination.asSlice(LayerOffsets.previous, FrameWireLayout.Camera.size), it) }
        }
        input.set(MCDRLayout.I32, Offsets.layerCount, value.layers.size)
        input.set(MCDRLayout.I32, Offsets.layerStride, LayerOffsets.size.toInt())
        input.set(MCDRLayout.PTR, Offsets.layers, layerArray)
        val postLayout = MCDRLayout.DISPLAY_POST_INPUT
        input.set(MCDRLayout.I32, Offsets.postCount, value.posts.size)
        input.set(MCDRLayout.I32, Offsets.postStride, PostOffsets.size.toInt())
        if (value.posts.isNotEmpty()) {
            val postArray = arena.allocateFfmArray(value.posts.size, postLayout)
            value.posts.forEachIndexed { index, post ->
                val destination = postArray.asSlice(index * PostOffsets.size, PostOffsets.size)
                destination.set(MCDRLayout.I32, PostOffsets.structSize, PostOffsets.size.toInt())
                destination.set(MCDRLayout.I32, PostOffsets.kind, post.kind.wireValue)
                array(destination.asSlice(PostOffsets.current, PostOffsets.currentSize), post.current())
                array(destination.asSlice(PostOffsets.previous, PostOffsets.previousSize), post.previous())
            }
            input.set(MCDRLayout.PTR, Offsets.posts, postArray)
        }
        call(input)
    }

    private fun identity(destination: MemorySegment, value: VanillaFrameIdentity) {
        val offsets = FrameWireLayout.Identity
        destination.set(MCDRLayout.I64, offsets.worldEpoch, value.worldEpoch)
        destination.set(MCDRLayout.I64, offsets.realFrameId, value.realFrameId)
        destination.set(MCDRLayout.I64, offsets.configVersion, value.configVersion)
        destination.set(MCDRLayout.I64, offsets.resourceVersion, value.resourceVersion)
    }

    private fun camera(destination: MemorySegment, value: VanillaCamera) {
        val offsets = FrameWireLayout.Camera
        val origin = destination.asSlice(offsets.origin, offsets.originSize)
        origin.setAtIndex(MCDRLayout.F64, 0, value.originX)
        origin.setAtIndex(MCDRLayout.F64, 1, value.originY)
        origin.setAtIndex(MCDRLayout.F64, 2, value.originZ)
        destination.set(MCDRLayout.F64, offsets.jitterX, value.jitterX)
        destination.set(MCDRLayout.F64, offsets.jitterY, value.jitterY)
        array(destination.asSlice(offsets.projection, offsets.projectionSize), value.projection())
        array(destination.asSlice(offsets.rasterProjection, offsets.rasterProjectionSize), value.rasterProjection())
        array(destination.asSlice(offsets.viewRotation, offsets.viewRotationSize), value.viewRotation())
        val perspective = checkNotNull(value.perspective) { "Display camera has no perspective projection" }
        destination.set(MCDRLayout.F32, offsets.nearPlane, perspective.nearPlane)
        destination.set(MCDRLayout.F32, offsets.farPlane, perspective.farPlane)
        destination.set(MCDRLayout.F32, offsets.verticalFovRadians, perspective.verticalFovRadians)
        destination.set(MCDRLayout.I32, offsets.flags, 257)
        destination.set(MCDRLayout.F32, offsets.yaw, value.yaw)
        destination.set(MCDRLayout.F32, offsets.pitch, value.pitch)
    }

    private fun binding(destination: MemorySegment, value: PreparedBinding) {
        val offsets = FrameWireLayout.Binding
        destination.set(MCDRLayout.PTR, offsets.resource, MemorySegment.ofAddress(value.handle))
        destination.set(MCDRLayout.I32, offsets.firstSubresource, value.firstSubresource)
        destination.set(MCDRLayout.I32, offsets.subresourceCount, value.subresourceCount)
    }

    private fun array(destination: MemorySegment, values: FloatArray) {
        values.forEachIndexed { index, value -> destination.setAtIndex(MCDRLayout.F32, index.toLong(), value) }
    }

    private object Offsets {
        private val layout = MCDRLayout.DISPLAY_FRAME_INPUT
        val size = layout.byteSize()
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val identity = layout.byteOffset(groupElement("identity"))
        val historyGeneration = layout.byteOffset(groupElement("historyGeneration"))
        val previousRealFrameId = layout.byteOffset(groupElement("previousRealFrameId"))
        val width = layout.byteOffset(groupElement("width"))
        val height = layout.byteOffset(groupElement("height"))
        val flags = layout.byteOffset(groupElement("flags"))
        val resetReasons = layout.byteOffset(groupElement("resetReasons"))
        val frameDeltaMs = layout.byteOffset(groupElement("frameDeltaMs"))
        val viewSpaceToMeters = layout.byteOffset(groupElement("viewSpaceToMeters"))
        val frameGenerationBackend = layout.byteOffset(groupElement("frameGenerationBackend"))
        val frameGenerationMode = layout.byteOffset(groupElement("frameGenerationMode"))
        val frameGenerationFrames = layout.byteOffset(groupElement("frameGenerationFrames"))
        val colorEncoding = layout.byteOffset(groupElement("colorEncoding"))
        val scene = layout.byteOffset(groupElement("scene"))
        val ui = layout.byteOffset(groupElement("ui"))
        val current = layout.byteOffset(groupElement("current"))
        val previous = layout.byteOffset(groupElement("previous"))
        val layerCount = layout.byteOffset(groupElement("layerCount"))
        val layerStride = layout.byteOffset(groupElement("layerStride"))
        val layers = layout.byteOffset(groupElement("layers"))
        val postCount = layout.byteOffset(groupElement("postCount"))
        val postStride = layout.byteOffset(groupElement("postStride"))
        val posts = layout.byteOffset(groupElement("posts"))
    }

    private object LayerOffsets {
        private val layout = MCDRLayout.DISPLAY_LAYER_INPUT
        val size = layout.byteSize()
        val structSize = layout.byteOffset(groupElement("structSize"))
        val kind = layout.byteOffset(groupElement("kind"))
        val flags = layout.byteOffset(groupElement("flags"))
        val composition = layout.byteOffset(groupElement("composition"))
        val width = layout.byteOffset(groupElement("width"))
        val height = layout.byteOffset(groupElement("height"))
        val depth = layout.byteOffset(groupElement("depth"))
        val motion = layout.byteOffset(groupElement("motion"))
        val coverage = layout.byteOffset(groupElement("coverage"))
        val current = layout.byteOffset(groupElement("current"))
        val previous = layout.byteOffset(groupElement("previous"))
    }

    private object PostOffsets {
        private val layout = MCDRLayout.DISPLAY_POST_INPUT
        val size = layout.byteSize()
        val structSize = layout.byteOffset(groupElement("structSize"))
        val kind = layout.byteOffset(groupElement("kind"))
        val current = layout.byteOffset(groupElement("current"))
        val previous = layout.byteOffset(groupElement("previous"))
        val currentSize = layout.select(groupElement("current")).byteSize()
        val previousSize = layout.select(groupElement("previous")).byteSize()
    }
}
