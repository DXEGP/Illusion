package io.github.dxegp.mixin.terrain

import com.mojang.blaze3d.vertex.StagingBuffer
import com.mojang.renderpearl.api.commands.CommandEncoder
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(StagingBuffer.Uploader::class)
interface StagingUploaderGeometryAccessor {
    @Accessor("encoder")
    fun illusionGeometryEncoder(): CommandEncoder
}
