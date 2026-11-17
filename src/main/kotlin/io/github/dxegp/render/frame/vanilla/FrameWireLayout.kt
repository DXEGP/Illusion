package io.github.dxegp.render.frame.vanilla

import io.github.dxegp.d3d12.ffi.MCDRLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement

internal object FrameWireLayout {
    object Identity {
        private val layout = MCDRLayout.FRAME_IDENTITY
        val size = layout.byteSize()
        val worldEpoch = layout.byteOffset(groupElement("worldEpoch"))
        val realFrameId = layout.byteOffset(groupElement("realFrameId"))
        val configVersion = layout.byteOffset(groupElement("configVersion"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
    }

    object Camera {
        private val layout = MCDRLayout.RASTER_CAMERA
        val size = layout.byteSize()
        val origin = layout.byteOffset(groupElement("origin"))
        val jitterX = layout.byteOffset(groupElement("jitterX"))
        val jitterY = layout.byteOffset(groupElement("jitterY"))
        val projection = layout.byteOffset(groupElement("projection"))
        val rasterProjection = layout.byteOffset(groupElement("rasterProjection"))
        val viewRotation = layout.byteOffset(groupElement("viewRotation"))
        val nearPlane = layout.byteOffset(groupElement("nearPlane"))
        val farPlane = layout.byteOffset(groupElement("farPlane"))
        val verticalFovRadians = layout.byteOffset(groupElement("verticalFovRadians"))
        val flags = layout.byteOffset(groupElement("flags"))
        val yaw = layout.byteOffset(groupElement("yaw"))
        val pitch = layout.byteOffset(groupElement("pitch"))
        val originSize = layout.select(groupElement("origin")).byteSize()
        val projectionSize = layout.select(groupElement("projection")).byteSize()
        val rasterProjectionSize = layout.select(groupElement("rasterProjection")).byteSize()
        val viewRotationSize = layout.select(groupElement("viewRotation")).byteSize()
    }

    object Binding {
        private val layout = MCDRLayout.PREPARED_BINDING
        val size = layout.byteSize()
        val resource = layout.byteOffset(groupElement("object"))
        val firstSubresource = layout.byteOffset(groupElement("firstSubresource"))
        val subresourceCount = layout.byteOffset(groupElement("subresourceCount"))
    }
}
