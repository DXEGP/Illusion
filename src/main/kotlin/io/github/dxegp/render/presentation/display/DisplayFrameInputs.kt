package io.github.dxegp.render.presentation.display

import io.github.dxegp.render.frame.vanilla.PreparedBinding
import io.github.dxegp.render.frame.vanilla.VanillaCamera
import io.github.dxegp.render.frame.vanilla.VanillaFrameInputs

internal enum class DisplayWorldGuideAvailability(val skipReason: String?) {
    COMPLETE(null),
    RASTER_MOTION_UNAVAILABLE("world raster coverage or previous motion is incomplete"),
    PARTICLE_MOTION_UNAVAILABLE("opaque particle motion source is incomplete"),
    PREVIOUS_CAMERA_UNAVAILABLE("previous display camera is unavailable"),
    MOTION_UNAVAILABLE("world motion texture is unavailable"),
    COVERAGE_UNAVAILABLE("world motion coverage is unavailable"),
}

internal class DisplayWorldGuides(
    val transparency: WorldTransparencyGuides,
    val availability: DisplayWorldGuideAvailability,
) {
    val skipReason = transparency.availability.skipReason ?: availability.skipReason
    val motionValid = transparency.availability == TransparencyGuideAvailability.COMPLETE &&
        availability == DisplayWorldGuideAvailability.COMPLETE
}

internal enum class DisplayLayerKind(val wireValue: Int) {
    WORLD(1),
    VIEWMODEL(2),
}

internal enum class DisplayLayerComposition(val wireValue: Int) {
    DEPTH_TESTED(0),
    OVERLAY_AFTER_WORLD(1),
}

internal class DisplayLayerInputs(
    val kind: DisplayLayerKind,
    val composition: DisplayLayerComposition,
    val width: Int,
    val height: Int,
    val depth: PreparedBinding,
    val motion: PreparedBinding?,
    val coverage: PreparedBinding?,
    val current: VanillaCamera,
    val previous: VanillaCamera?,
    val motionValid: Boolean,
    val mixedSurface: Boolean,
) {
    init {
        require(width > 0 && height > 0)
        require(current.perspective != null)
        require(!motionValid || (motion != null && coverage != null))
    }
}

internal enum class DisplayPostKind(val wireValue: Int) {
    COLOR(0),
    UV_TRANSFORM(1),
    UNAVAILABLE(2),
}

internal class DisplayPostInputs(
    val effect: String,
    val kind: DisplayPostKind,
    current: FloatArray,
    previous: FloatArray,
) {
    private val currentValues = current.copyOf()
    private val previousValues = previous.copyOf()

    init {
        require(effect.isNotBlank())
        require(currentValues.size == 16 && currentValues.all(Float::isFinite))
        require(previousValues.size == 16 && previousValues.all(Float::isFinite))
    }

    fun current(): FloatArray = currentValues.copyOf()
    fun previous(): FloatArray = previousValues.copyOf()
}

internal class DisplayFrameInputs(
    val frame: VanillaFrameInputs,
    val scene: PreparedBinding,
    val current: VanillaCamera,
    val previous: VanillaCamera?,
    val previousRealFrameId: Long,
    layers: List<DisplayLayerInputs>,
    posts: List<DisplayPostInputs>,
    val worldGuides: DisplayWorldGuides,
    val resetReasons: Int,
) {
    val layers = layers.toList()
    val posts = posts.toList()

    init {
        require(this.layers.size in 1..2 && this.layers[0].kind == DisplayLayerKind.WORLD)
        require(this.layers.size == 1 || this.layers[1].kind == DisplayLayerKind.VIEWMODEL)
        require((previous != null) == (previousRealFrameId != 0L))
        require(worldGuides.transparency.frame === frame)
        require(worldGuides.motionValid == this.layers[0].motionValid)
    }
}

internal class DisplayUiInputs(
    val texture: PreparedBinding,
    val containsScene: Boolean,
)
