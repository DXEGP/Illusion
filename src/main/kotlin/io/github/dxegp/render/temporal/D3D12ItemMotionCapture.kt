package io.github.dxegp.render.temporal

import io.github.dxegp.mixin.temporal.RenderSetupMotionAccessor
import io.github.dxegp.mixin.temporal.RenderTypeMotionAccessor
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.Minecraft
import io.github.dxegp.render.presentation.viewmodel.D3D12ViewmodelMotionCapture
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemDisplayContext
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

object D3D12ItemMotionCapture {
    private data class Owner(val entity: WeakReference<Entity>, val arm: HumanoidArm,
        val attachment: String, val state: ItemStackRenderState, val stack: ItemStack)
    private data class Slot(val entity: Long, val arm: HumanoidArm, val attachment: String,
        val layer: Int, val context: Any, val foil: Any, val space: D3D12RasterMotionCapture.MotionSpace)
    private data class Source(val slot: Slot, val stack: ItemStack, val quads: List<BakedQuad>,
        val topology: FloatArray, var feature: FeatureGeometrySource? = null)
    private data class History(val source: Source, val positions: FloatArray, val feature: FeatureGeometrySource?)
    private data class Identity(val entity: WeakReference<Entity>, val token: Long)
    private val identities = HashMap<Int, Identity>()
    private var nextIdentity = 1L
    private var owner: Owner? = null
    private var stackState: ItemStackRenderState? = null
    private var layer = -1
    private var nextLayer = 0
    private var submitting = false
    private class CaptureHistory {
        var frame: D3D12RasterMotionCapture.GeometryFrame? = null
        var previous = HashMap<Slot, History>()
        var current = HashMap<Slot, History>()
        val submitted = IdentityHashMap<ItemFeatureRenderer.Submit, Source>()
        val slots = HashMap<Slot, ItemFeatureRenderer.Submit>()
        val ambiguous = HashSet<Slot>()
    }
    private val histories = D3D12RasterMotionCapture.MotionSpace.entries.associateWith { CaptureHistory() }
    private var history = checkNotNull(histories[D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE])

    private fun synchronizeFrame(): Boolean {
        val next = D3D12RasterMotionCapture.geometryFrame() ?: return false
        history = checkNotNull(histories[next.space])
        val old = history.frame
        if (old == next) return true
        val reusable = history.previous
        history.previous = history.current
        history.current = reusable
        history.current.clear()
        if (!next.hasPrevious || old == null || old.world !== next.world || old.generation != next.generation ||
            old.sequence + 1L != next.sequence) history.previous.clear()
        history.submitted.clear()
        history.slots.clear()
        history.ambiguous.clear()
        identities.entries.removeIf { it.value.entity.get() == null }
        history.frame = next
        return true
    }

    fun held(state: EntityRenderState, item: ItemStackRenderState, stack: ItemStack,
        arm: HumanoidArm, attachment: String, action: () -> Unit) {
        val saved = owner
        val entity = if (synchronizeFrame()) D3D12ObjectMotion.extractedEntity(state) else null
        owner = entity?.let { Owner(WeakReference(it), arm, attachment, item, stack.copy()) }
        try { action() } finally { owner = saved }
    }

    fun firstPerson(item: ItemStackRenderState, stack: ItemStack, arm: HumanoidArm, action: () -> Unit) {
        val saved = owner
        val frame = D3D12RasterMotionCapture.geometryFrame()
        val player = Minecraft.getInstance().player
        owner = if (frame?.space == D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE &&
            player != null && synchronizeFrame()) {
            Owner(WeakReference(player), arm, "first-person-item", item, stack.copy())
        } else null
        try { action() } finally { owner = saved }
    }

    fun stack(state: ItemStackRenderState, action: () -> Unit) {
        val saved = stackState
        val savedNext = nextLayer
        nextLayer = 0
        stackState = state.takeIf { owner?.state === state }
        try { action() } finally { stackState = saved; nextLayer = savedNext }
    }

    fun layer(action: () -> Unit) {
        val saved = layer
        layer = nextLayer++
        try { action() } finally { layer = saved }
    }

    fun collection(action: () -> Unit) {
        val saved = submitting
        submitting = stackState != null && layer >= 0
        try { action() } finally { submitting = saved }
    }

    fun submitted(submit: ItemFeatureRenderer.Submit) {
        D3D12ViewmodelMotionCapture.itemSource(submit.quads().any { it.materialInfo().layer().translucent() })
        if (!submitting || !synchronizeFrame() || submit.outlineColor() != 0) {
            D3D12ViewmodelMotionCapture.unsupportedSource()
            return
        }
        val space = history.frame?.space ?: return
        val contexts = when (space) {
            D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE ->
                setOf(ItemDisplayContext.THIRD_PERSON_LEFT_HAND, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    ItemDisplayContext.HEAD)
            D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE ->
                setOf(ItemDisplayContext.FIRST_PERSON_LEFT_HAND, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
        }
        if (submit.displayContext() !in contexts) return
        val active = owner ?: return
        val entity = active.entity.get() ?: return
        if (submit.quads().isEmpty() || space == D3D12RasterMotionCapture.MotionSpace.VIEWMODEL_POSE &&
            submit.quads().any { it.materialInfo().layer().translucent() }) {
            D3D12ViewmodelMotionCapture.unsupportedSource()
            return
        }
        if (submit.quads().any { quad ->
                val material = quad.materialInfo()
                val renderType = when (submit.foilType()) {
                    ItemStackRenderState.FoilType.NONE -> material.itemRenderType()
                    ItemStackRenderState.FoilType.STANDARD -> material.itemGlintRenderType()
                    ItemStackRenderState.FoilType.SPECIAL -> material.itemGlintSpecialRenderType()
                }
                val setup = (renderType as RenderTypeMotionAccessor).illusionMotionState()
                (setup as RenderSetupMotionAccessor).illusionMotionLayering().modifier != null
            }) return
        val existing = identities[entity.id]
        val identity = if (existing?.entity?.get() === entity) existing.token else nextIdentity++.also {
            identities[entity.id] = Identity(WeakReference(entity), it)
        }
        val slot = Slot(identity, active.arm, active.attachment, layer, submit.displayContext(), submit.foilType(), space)
        val duplicate = history.slots.putIfAbsent(slot, submit)
        if (duplicate != null) {
            history.ambiguous.add(slot)
            history.submitted[duplicate]?.feature?.invalidate()
            history.current.remove(slot)?.feature?.invalidate()
            return
        }
        val quads = submit.quads().toList()
        val topology = FloatArray(quads.size * 12)
        for ((quadIndex, quad) in quads.withIndex()) {
            for (vertex in 0 until 4) {
                val position = quad.position(vertex)
                val offset = quadIndex * 12 + vertex * 3
                topology[offset] = position.x()
                topology[offset + 1] = position.y()
                topology[offset + 2] = position.z()
            }
        }
        history.submitted[submit] = Source(slot, active.stack, quads, topology)
        D3D12ViewmodelMotionCapture.supportedItemSource()
    }

    fun prepare(submit: ItemFeatureRenderer.Submit, action: () -> Unit) {
        if (!synchronizeFrame()) { action(); return }
        val source = history.submitted[submit]
        if (source == null || source.slot in history.ambiguous) { action(); return }
        if (history.frame?.space == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE &&
            history.current.containsKey(source.slot)) {
            history.ambiguous.add(source.slot)
            source.feature?.invalidate()
            history.current[source.slot]?.feature?.invalidate()
        }
        if (!source.topology.all(Float::isFinite)) { action(); return }
        val previous = history.previous[source.slot]
        val old = previous?.takeIf { history ->
            ItemStack.isSameItemSameComponents(history.source.stack, source.stack) &&
                history.source.quads.size == source.quads.size &&
                source.quads.indices.all { history.source.quads[it] === source.quads[it] } &&
                history.source.topology.contentEquals(source.topology)
        }
        val feature = if (history.frame?.space == D3D12RasterMotionCapture.MotionSpace.WORLD_CAMERA_RELATIVE) {
            source.feature ?: FeatureGeometrySource.next(FeatureGeometryKind.ITEM, previous?.feature).also {
                if (previous != null && old == null) it.advanceGeneration()
                source.feature = it
            }
        } else null
        val oldPositions = old?.positions
        val capture = if (feature == null) D3D12RasterMotionCapture.beginGeometry(oldPositions)
            else D3D12RasterMotionCapture.beginFeatureGeometry(oldPositions, feature)
        if (capture == null) { action(); return }
        var complete = false
        try {
            action()
            complete = true
        } finally {
            val positions = D3D12RasterMotionCapture.endGeometry(capture, complete)
            if (complete && positions != null && positions.size == source.quads.size * 12) {
                history.current[source.slot] = History(source, positions, feature)
            } else {
                feature?.invalidate()
            }
        }
    }
}
