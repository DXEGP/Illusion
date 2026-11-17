package io.github.dxegp.render.presentation.viewmodel

import io.github.dxegp.render.temporal.D3D12RasterMotionCapture
import net.minecraft.client.Minecraft
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

object D3D12ViewmodelMotionCapture {
    internal data class ModelSource(val player: Long, val model: Any, val renderType: Any)
    private data class ArmSource(val arm: HumanoidArm, val root: ModelPart,
        val skin: Any, val sleeve: Boolean)
    private data class Owner(val player: Long, val arm: HumanoidArm, val state: AvatarRenderState)
    private var player = WeakReference<Player>(null)
    private var playerToken = 0L
    private var owner: Owner? = null
    private var root: ModelPart? = null
    private var mixedSurface = false
    private var sources = 0
    private var supportedSources = 0
    private var unsupported = false
    private val submitted = IdentityHashMap<ModelFeatureRenderer.Submit<*>, ModelSource>()

    internal fun playerIdentity(): Long {
        val current = Minecraft.getInstance().player ?: return 0L
        if (player.get() !== current) {
            player = WeakReference(current)
            playerToken++
        }
        return playerToken
    }

    internal fun begin() {
        submitted.clear()
        owner = null
        root = null
        mixedSurface = false
        sources = 0
        supportedSources = 0
        unsupported = false
    }

    fun arm(state: AvatarRenderState?, arm: HumanoidArm, action: () -> Unit) {
        val saved = owner
        owner = if (state != null && D3D12RasterMotionCapture.geometryFrame()?.space ==
            D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) {
            Owner(playerIdentity(), arm, state)
        } else null
        try { action() } finally { owner = saved }
    }

    fun modelPart(part: ModelPart, action: () -> Unit) {
        val saved = root
        root = part.takeIf { owner != null }
        try { action() } finally { root = saved }
    }

    fun submitted(submit: ModelFeatureRenderer.Submit<*>) {
        if (D3D12RasterMotionCapture.geometryFrame()?.space != D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) return
        sources++
        mixedSurface = mixedSurface || submit.renderType().hasBlending()
        val active = owner ?: run { unsupported = true; return }
        val part = root ?: run { unsupported = true; return }
        val model = submit.model()
        if (model !is Model.Simple || model.root() !== part || active.player == 0L) {
            unsupported = true
            return
        }
        val sleeve = if (active.arm == HumanoidArm.RIGHT) active.state.showRightSleeve else active.state.showLeftSleeve
        submitted[submit] = ModelSource(active.player,
            ArmSource(active.arm, part, active.state.skin.body().texturePath(), sleeve), submit.renderType())
        supportedSources++
    }

    internal fun source(submit: ModelFeatureRenderer.Submit<*>): ModelSource? = submitted[submit]

    fun itemSource(mixed: Boolean) {
        if (D3D12RasterMotionCapture.geometryFrame()?.space != D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) return
        sources++
        mixedSurface = mixedSurface || mixed
    }

    fun unsupportedSource() {
        if (D3D12RasterMotionCapture.geometryFrame()?.space != D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) return
        sources++
        mixedSurface = true
        unsupported = true
    }

    fun supportedItemSource() {
        if (D3D12RasterMotionCapture.geometryFrame()?.space == D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE) {
            supportedSources++
        }
    }

    internal fun hasMixedSurface(): Boolean = mixedSurface || unsupported
    internal fun hasSources(): Boolean = sources > 0
    internal fun allSourcesSupported(): Boolean = !unsupported && supportedSources == sources
}
