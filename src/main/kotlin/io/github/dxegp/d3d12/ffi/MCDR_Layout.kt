package io.github.dxegp.d3d12.ffi

import java.lang.foreign.AddressLayout
import java.lang.foreign.GroupLayout
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.ValueLayout
import java.util.*

/**
 * Mirror of the C aggregates declared in `MCDR/include/mcdr_abi.h`.
 * <p><b>[MemoryLayout.structLayout] inserts NO padding.</b> Every pad byte the C compiler would insert must appear
 * here explicitly — named `_padN` where the header names one, else `MemoryLayout.paddingLayout(n)`; trailing padding
 * counts too, it is what makes `byteSize()` a usable array stride.
 * 互操作：标量布局与偏移常量必须 `@JvmField`（普通 `val` 生成 `MCDR.INSTANCE.getXxx()` 访问器，Java 侧 static import 解析不到）。
 */
object MCDRLayout {

    @JvmField
    val I32: ValueLayout.OfInt = ValueLayout.JAVA_INT

    @JvmField
    val I64: ValueLayout.OfLong = ValueLayout.JAVA_LONG

    @JvmField
    val F32: ValueLayout.OfFloat = ValueLayout.JAVA_FLOAT

    @JvmField
    val F64: ValueLayout.OfDouble = ValueLayout.JAVA_DOUBLE

    @JvmField
    val I8: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

    @JvmField
    val PTR: AddressLayout = ValueLayout.ADDRESS

    @JvmField
    val SR_DISPATCH: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("renderWidth"), I32.withName("renderHeight"),
        I32.withName("displayWidth"), I32.withName("displayHeight"),
        F32.withName("jitterOffsetX"), F32.withName("jitterOffsetY"),
        F32.withName("motionVectorScaleX"), F32.withName("motionVectorScaleY"),
        F32.withName("preExposure"), F32.withName("frameTimeDeltaMs"),
        F32.withName("cameraNear"), F32.withName("cameraFar"),
        F32.withName("cameraVerticalFov"), F32.withName("sharpness"),
        I32.withName("reset"), I32.withName("hdrInput"),
        I32.withName("backend"), I32.withName("flags"),
        PTR.withName("pColorInput"), PTR.withName("pColorOutput"),
        PTR.withName("pDepth"), PTR.withName("pMotionVectors"),
        PTR.withName("pReactiveMask"), PTR.withName("pExposure"),
        I32.withName("depthFormatOrdinal"), F32.withName("cameraAspectRatio"),
        F32.withName("viewSpaceToMetersFactor"), I32.withName("_pad2"),
        PTR.withName("pTransparencyAndComposition"),
        PTR.withName("pCamera"),
    ).withByteAlignment(8)

    @JvmStatic
    fun srDispatchOffset(field: String): Long = SR_DISPATCH.byteOffset(PathElement.groupElement(field))

    @JvmField
    val FRAME_IDENTITY: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("worldEpoch"), I64.withName("realFrameId"),
        I64.withName("configVersion"), I64.withName("resourceVersion")
    ).withName("mcdr_frame_identity")

    @JvmField
    val PRESENT_RECEIPT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), I64.withName("serial"),
        FRAME_IDENTITY.withName("identity"),
        I64.withName("providerGeneration"), I64.withName("historyGeneration"),
        I64.withName("commandFenceValue"), I64.withName("submitQueueFenceValue"),
        I64.withName("presentQueueFenceValue"),
        I32.withName("submissionState"), I32.withName("presentOutcome"), I32.withName("dxgiStatus"),
        I32.withName("flags"), I32.withName("outputFrameCount"), I32.withName("retirementState"),
    ).withName("mcdr_present_receipt_v1")

    @JvmField
    val OIT_CAPTURE_PIPELINE: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"),
        I32.withName("shaderAbiVersion"), I32.withName("flags"),
        I64.withName("shaderContractHash"), I64.withName("programGeneration"),
    ).withName("mcdr_oit_capture_pipeline_v1")

    @JvmField
    val RASTER_CAMERA: MemoryLayout = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(3, F64).withName("origin"),
        F64.withName("jitterX"), F64.withName("jitterY"),
        MemoryLayout.sequenceLayout(16, F32).withName("projection"),
        MemoryLayout.sequenceLayout(16, F32).withName("rasterProjection"),
        MemoryLayout.sequenceLayout(16, F32).withName("viewRotation"),
        F32.withName("nearPlane"), F32.withName("farPlane"), F32.withName("verticalFovRadians"),
        I32.withName("flags"), F32.withName("yaw"), F32.withName("pitch")
    ).withName("mcdr_raster_camera")

    @JvmField
    val PROVIDER_MASK_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I64.withName("historyGeneration"), I64.withName("previousRealFrameId"),
        PTR.withName("color"), PTR.withName("depth"), PTR.withName("motion"),
        PTR.withName("source0"), PTR.withName("source1"), PTR.withName("output"), PTR.withName("compositionOutput"),
        I32.withName("width"), I32.withName("height"), I32.withName("outputFlags"), I32.withName("inputFlags"),
        F32.withName("historyJitterX"), F32.withName("historyJitterY"),
        MemoryLayout.sequenceLayout(16, F32).withName("currentToPrevious")
    ).withName("mcdr_provider_mask_input")

    @JvmField
    val FRAME_SETTINGS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("antiAliasing"), I32.withName("reconstructionBackend"),
        I32.withName("renderScalePercent"), I32.withName("jitterEnabled"), I32.withName("jitterSamples"),
        F32.withName("providerSharpness"), I32.withName("providerMasks"),
        I32.withName("dlssQuality"), I32.withName("dlssPreset"),
        I32.withName("frameGenerationBackend"), I32.withName("frameGenerationMode"),
        I32.withName("frameGenerationFrames"), F32.withName("taaBlend"), F32.withName("taaSharpness")
    ).withName("mcdr_frame_settings")

    @JvmField
    val VANILLA_FRAME_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I64.withName("historyGeneration"), I64.withName("frameTimeNanos"), I64.withName("jitterPhaseIndex"),
        I32.withName("renderWidth"), I32.withName("renderHeight"),
        I32.withName("displayWidth"), I32.withName("displayHeight"),
        I32.withName("flags"), I32.withName("colorEncoding"), I32.withName("depthConvention"),
        I32.withName("motionConvention"), F32.withName("frameDeltaMs"), F32.withName("preExposure"),
        F32.withName("motionScaleX"), F32.withName("motionScaleY"), F32.withName("viewSpaceToMeters"),
        I32.withName("reserved"), I64.withName("previousRealFrameId"), FRAME_SETTINGS.withName("settings"),
        RASTER_CAMERA.withName("current"), RASTER_CAMERA.withName("previous")
    ).withName("mcdr_vanilla_frame_input")

    @JvmField
    val PREPARED_BINDING: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("object"), I32.withName("firstSubresource"), I32.withName("subresourceCount")
    ).withName("mcdr_prepared_binding")

    @JvmField
    val OIT_BEGIN: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I64.withName("historyGeneration"), I64.withName("previousRealFrameId"),
        I64.withName("programGeneration"), I64.withName("totalBudgetBytes"),
        I32.withName("maxRecords"), I32.withName("maxRecordsPerPixel"),
        I32.withName("width"), I32.withName("height"), I32.withName("colorEncoding"), I32.withName("flags"),
        F32.withName("preExposure"), I32.withName("reserved"),
        I32.withName("maxSourceRows"), I32.withName("maxPrimitiveRows"),
        I32.withName("maxPreviousRows"), I32.withName("reservedMetadata"),
        PREPARED_BINDING.withName("opaqueColor"), PREPARED_BINDING.withName("opaqueDepth"),
        PREPARED_BINDING.withName("opaqueMotion"),
    ).withName("mcdr_oit_begin_v1")

    @JvmField
    val OIT_BEGIN_V2: MemoryLayout = MemoryLayout.structLayout(
        OIT_BEGIN.withName("base"),
        I32.withName("maxTerrainSectionRows"), I32.withName("maxTerrainPrimitives"),
        MemoryLayout.sequenceLayout(16, F32).withName("previousViewProjection"),
        MemoryLayout.sequenceLayout(3, F64).withName("previousOrigin"),
        I32.withName("previousCameraValid"), I32.withName("reserved"),
    ).withName("mcdr_oit_begin_v2")

    @JvmField
    val OIT_SOURCE: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("producerId"), I64.withName("producerGeneration"), I64.withName("shaderGeneration"),
        I32.withName("producerKind"), I32.withName("validity"),
    ).withName("mcdr_oit_source_v1")

    @JvmField
    val OIT_PRIMITIVE: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("surfaceId"), I64.withName("geometryGeneration"), I64.withName("materialId"),
        I64.withName("materialGeneration"), I64.withName("textureGeneration"),
        I32.withName("sourceIndex"), I32.withName("previousIndex"),
        I32.withName("flags"), I32.withName("reserved"),
    ).withName("mcdr_oit_primitive_v1")

    @JvmField
    val OIT_PREVIOUS_TRIANGLE: MemoryLayout = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(12, F32).withName("clip"),
        MemoryLayout.sequenceLayout(6, F32).withName("materialUv"),
        I32.withName("flags"), I32.withName("reserved"),
    ).withName("mcdr_oit_previous_triangle_v1")

    @JvmField
    val OIT_METADATA: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"),
        I32.withName("sourceCount"), I32.withName("primitiveCount"),
        I32.withName("previousCount"), I32.withName("sectionCount"),
        PTR.withName("sources"), PTR.withName("primitives"), PTR.withName("previous"),
        PTR.withName("sectionPrimitiveBases"),
    ).withName("mcdr_oit_metadata_v1")

    @JvmField
    val OIT_DRAW: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"),
        I32.withName("sourceIndex"), I32.withName("primitiveBase"),
        I32.withName("primitiveCount"), I32.withName("flags"), I64.withName("producerScopeSerial"),
        I32.withName("primitivesPerInstance"), I32.withName("instanceCount"),
        I32.withName("firstInstance"), I32.withName("reserved"),
    ).withName("mcdr_oit_draw_v1")

    @JvmField
    val OIT_TERRAIN_SECTION: MemoryLayout = MemoryLayout.structLayout(
        I64.withName("surfaceId"), I64.withName("geometryGeneration"),
        I64.withName("materialId"), I64.withName("materialGeneration"), I64.withName("textureGeneration"),
        I32.withName("primitiveBase"), I32.withName("primitiveCount"),
        I32.withName("sectionX"), I32.withName("sectionY"), I32.withName("sectionZ"), I32.withName("flags"),
        I32.withName("vertexBase"), I32.withName("vertexCount"),
    ).withName("mcdr_oit_terrain_section_v2")

    @JvmField
    val OIT_TERRAIN_METADATA: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), OIT_SOURCE.withName("source"),
        I32.withName("sectionCount"), I32.withName("primitiveCount"),
        PTR.withName("sections"), PTR.withName("vertexBuffer"),
        I64.withName("vertexOffset"), I64.withName("vertexBytes"),
    ).withName("mcdr_oit_terrain_metadata_v2")

    @JvmField
    val OIT_TERRAIN_DRAW: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), I64.withName("producerScopeSerial"),
    ).withName("mcdr_oit_terrain_draw_v2")

    @JvmField
    val TERRAIN_TILE_RESOURCE_REF: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("object"), I64.withName("offset"), I64.withName("bytes"),
    ).withName("mcdr_terrain_tile_resource_ref")

    @JvmField
    val TERRAIN_TILE: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("originX"), I32.withName("originY"),
        I32.withName("effectiveWidth"), I32.withName("effectiveHeight"),
    ).withName("mcdr_terrain_tile")

    @JvmField
    val TERRAIN_TILE_STAGE_V2: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"),
        I32.withName("shaderAbiVersion"), I32.withName("stage"),
        I32.withName("quadsPerMeshlet"), I32.withName("addressingVersion"),
        I64.withName("shaderContractHash"), I64.withName("programGeneration"),
    ).withName("mcdr_terrain_tile_stage_v2")

    @JvmField val TERRAIN_TILE_STAGE_SCHEMA: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("schemaVersion"))
    @JvmField val TERRAIN_TILE_STAGE_SIZE: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("structSize"))
    @JvmField val TERRAIN_TILE_STAGE_SHADER_ABI: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("shaderAbiVersion"))
    @JvmField val TERRAIN_TILE_STAGE_KIND: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("stage"))
    @JvmField val TERRAIN_TILE_STAGE_QUADS: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("quadsPerMeshlet"))
    @JvmField val TERRAIN_TILE_STAGE_ADDRESSING: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("addressingVersion"))
    @JvmField val TERRAIN_TILE_STAGE_HASH: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("shaderContractHash"))
    @JvmField val TERRAIN_TILE_STAGE_GENERATION: Long = TERRAIN_TILE_STAGE_V2.byteOffset(MemoryLayout.PathElement.groupElement("programGeneration"))

    @JvmField
    val TERRAIN_TILE_STAGE_BINDING_V2: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("stage"), I32.withName("flags"), I32.withName("renderTargetCount"),
        I32.withName("reserved"), PTR.withName("pipeline"),
        TERRAIN_TILE_RESOURCE_REF.withName("depth"),
        MemoryLayout.sequenceLayout(5, TERRAIN_TILE_RESOURCE_REF).withName("renderTargets"),
    ).withName("mcdr_terrain_tile_stage_binding_v2")

    @JvmField
    val TERRAIN_TILE_BATCH_V2: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), I32.withName("residentTileCount"),
        I32.withName("laneIndex"), I32.withName("flags"), I32.withName("reserved0"),
        I64.withName("frameSerial"), I64.withName("worldEpoch"), I64.withName("programGeneration"),
        I64.withName("resourceVersion"), MemoryLayout.sequenceLayout(4, F32).withName("sourceViewport"),
        MemoryLayout.sequenceLayout(16, TERRAIN_TILE).withName("tiles"), I32.withName("primitiveCount"),
        I32.withName("refCapacity"), I32.withName("commandKind"), I32.withName("commandStride"),
        TERRAIN_TILE_RESOURCE_REF.withName("binningConstants"), TERRAIN_TILE_RESOURCE_REF.withName("envelopes"),
        TERRAIN_TILE_RESOURCE_REF.withName("masks"), TERRAIN_TILE_RESOURCE_REF.withName("prefixes"),
        TERRAIN_TILE_RESOURCE_REF.withName("commands"), TERRAIN_TILE_RESOURCE_REF.withName("control"),
        TERRAIN_TILE_RESOURCE_REF.withName("bounds"), TERRAIN_TILE_RESOURCE_REF.withName("culledBounds"),
        TERRAIN_TILE_RESOURCE_REF.withName("depthTerrain"), TERRAIN_TILE_RESOURCE_REF.withName("depthCloud"),
        MemoryLayout.sequenceLayout(4, TERRAIN_TILE_RESOURCE_REF).withName("coefficients"),
        TERRAIN_TILE_RESOURCE_REF.withName("q"), TERRAIN_TILE_RESOURCE_REF.withName("scratchColor"),
        TERRAIN_TILE_RESOURCE_REF.withName("scratchDepth"), PTR.withName("binningWorkGraph"),
        PTR.withName("binningInitializePipeline"), PTR.withName("binningPrefixPipeline"),
        PTR.withName("binningScatterPipeline"),
        MemoryLayout.sequenceLayout(3, TERRAIN_TILE_STAGE_BINDING_V2).withName("stages"),
    ).withName("mcdr_terrain_tile_batch_v2")

    @JvmField
    val TERRAIN_TILE_BATCH_V3: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"),
        I32.withName("packetOrdinal"), I32.withName("packetCount"),
        TERRAIN_TILE_BATCH_V2.withName("payload"),
    ).withName("mcdr_terrain_tile_batch_v3")

    @JvmField
    val OIT_RESOLVE: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        PTR.withName("frame"), PREPARED_BINDING.withName("currentTransparency"),
        PREPARED_BINDING.withName("unrecordedCoverage"), PREPARED_BINDING.withName("correctedTransparency"),
        PREPARED_BINDING.withName("statusOutput"),
        I32.withName("flags"), I32.withName("reserved"),
    ).withName("mcdr_oit_resolve_v3")

    @JvmField
    val OIT_RESOLVE_V4: MemoryLayout = MemoryLayout.structLayout(
        OIT_RESOLVE.withName("base"), PTR.withName("terrainBatches"), I32.withName("terrainBatchCount"),
        I32.withName("reservedV4"),
    ).withName("mcdr_oit_resolve_v4")

    @JvmField
    val WORLD_OUTPUT_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I32.withName("motionCoverage"), I32.withName("motionGuideCoverage"),
        PREPARED_BINDING.withName("color"), PREPARED_BINDING.withName("depth"),
        PREPARED_BINDING.withName("motion"), PREPARED_BINDING.withName("exposure"),
        PREPARED_BINDING.withName("reconstructionOutput"), PREPARED_BINDING.withName("scene"),
        PROVIDER_MASK_INPUT.withName("masks"), F32.withName("dlssPostSharpness"), I32.withName("reserved"),
        I32.withName("sourceStage"), I32.withName("reservedStage")
    ).withName("mcdr_world_output_input")

    @JvmField
    val PREPARED_FRAME: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I64.withName("historyGeneration"), I32.withName("availability"), I32.withName("required"),
        I32.withName("consumer"), I32.withName("motionCoverage"), I32.withName("motionGuideCoverage"),
        I32.withName("resetReasons"), PREPARED_BINDING.withName("color"), PREPARED_BINDING.withName("depth"),
        PREPARED_BINDING.withName("motion"), PREPARED_BINDING.withName("exposure"),
        PREPARED_BINDING.withName("reactive"), PREPARED_BINDING.withName("composition"),
        PREPARED_BINDING.withName("responsive")
    ).withName("mcdr_prepared_frame")

    @JvmField
    val RECONSTRUCTION_REQUEST: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I32.withName("consumer"), I32.withName("flags"), PREPARED_BINDING.withName("output")
    ).withName("mcdr_reconstruction_request")

    @JvmField
    val DISPLAY_LAYER_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("structSize"), I32.withName("kind"), I32.withName("flags"), I32.withName("composition"),
        I32.withName("width"), I32.withName("height"), PREPARED_BINDING.withName("depth"),
        PREPARED_BINDING.withName("motion"), PREPARED_BINDING.withName("coverage"),
        RASTER_CAMERA.withName("current"), RASTER_CAMERA.withName("previous")
    ).withName("mcdr_display_layer_input")

    @JvmField
    val DISPLAY_POST_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("structSize"), I32.withName("kind"), I32.withName("flags"), I32.withName("reserved"),
        MemoryLayout.sequenceLayout(16, F32).withName("current"),
        MemoryLayout.sequenceLayout(16, F32).withName("previous")
    ).withName("mcdr_display_post_input")

    @JvmField
    val DISPLAY_FRAME_INPUT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("schemaVersion"), I32.withName("structSize"), FRAME_IDENTITY.withName("identity"),
        I64.withName("historyGeneration"), I64.withName("previousRealFrameId"),
        I32.withName("width"), I32.withName("height"), I32.withName("flags"), I32.withName("resetReasons"),
        F32.withName("frameDeltaMs"), F32.withName("viewSpaceToMeters"),
        I32.withName("frameGenerationBackend"), I32.withName("frameGenerationMode"),
        I32.withName("frameGenerationFrames"), I32.withName("colorEncoding"),
        PREPARED_BINDING.withName("scene"), PREPARED_BINDING.withName("ui"),
        RASTER_CAMERA.withName("current"), RASTER_CAMERA.withName("previous"),
        I32.withName("layerCount"), I32.withName("layerStride"), PTR.withName("layers"),
        I32.withName("postCount"), I32.withName("postStride"), PTR.withName("posts")
    ).withName("mcdr_display_frame_input")

    @JvmField
    val VANILLA_RESOURCE_USE: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("object"), I64.withName("version"), I32.withName("firstSubresource"),
        I32.withName("subresourceCount"), I32.withName("access"), I32.withName("requiredState"),
        I32.withName("finalState"), I32.withName("externalState")
    ).withByteAlignment(8).withName("mcdr_vanilla_resource_use")
    @JvmField val VANILLA_USE_OBJECT = offset(VANILLA_RESOURCE_USE, "object")
    @JvmField val VANILLA_USE_VERSION = offset(VANILLA_RESOURCE_USE, "version")
    @JvmField val VANILLA_USE_FIRST = offset(VANILLA_RESOURCE_USE, "firstSubresource")
    @JvmField val VANILLA_USE_COUNT = offset(VANILLA_RESOURCE_USE, "subresourceCount")
    @JvmField val VANILLA_USE_ACCESS = offset(VANILLA_RESOURCE_USE, "access")
    @JvmField val VANILLA_USE_REQUIRED = offset(VANILLA_RESOURCE_USE, "requiredState")
    @JvmField val VANILLA_USE_FINAL = offset(VANILLA_RESOURCE_USE, "finalState")
    @JvmField val VANILLA_USE_EXTERNAL = offset(VANILLA_RESOURCE_USE, "externalState")

    @JvmField
    val BINDING_SLOT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("type"),
        I32.withName("texelFormat"),
        PTR.withName("buffer"),
        PTR.withName("view"),
        I64.withName("offset"),
        I64.withName("length")
    ).withByteAlignment(8).withName("mcdr_binding_slot")

    @JvmField
    val PARTITION_DRAW: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("indexBuffer"),
        PTR.withName("vertexBuffer"),
        I64.withName("vertexOffset"),
        I32.withName("indexType"),
        I32.withName("indexCount"),
        I32.withName("firstIndex"),
        I32.withName("baseVertex"),
        I32.withName("vertexSlot"),
        I32.withName("vertexStride"),
        I32.withName("_pad0"),
        MemoryLayout.paddingLayout(4)
    ).withByteAlignment(8).withName("mcdr_partition_draw")

    @JvmStatic
    fun offset(layout: MemoryLayout, field: String): Long {
        try {
            return layout.byteOffset(MemoryLayout.PathElement.groupElement(field))
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("no field ${field} in ${layout}", e)
        }
    }

    @JvmField val BINDING_SLOT_TYPE: Long = offset(BINDING_SLOT, "type")
    @JvmField val BINDING_SLOT_TEXEL_FORMAT: Long = offset(BINDING_SLOT, "texelFormat")
    @JvmField val BINDING_SLOT_BUFFER: Long = offset(BINDING_SLOT, "buffer")
    @JvmField val BINDING_SLOT_VIEW: Long = offset(BINDING_SLOT, "view")
    @JvmField val BINDING_SLOT_OFFSET: Long = offset(BINDING_SLOT, "offset")
    @JvmField val BINDING_SLOT_LENGTH: Long = offset(BINDING_SLOT, "length")

    @JvmField val PARTITION_DRAW_INDEX_BUFFER: Long = offset(PARTITION_DRAW, "indexBuffer")
    @JvmField val PARTITION_DRAW_VERTEX_BUFFER: Long = offset(PARTITION_DRAW, "vertexBuffer")
    @JvmField val PARTITION_DRAW_VERTEX_OFFSET: Long = offset(PARTITION_DRAW, "vertexOffset")
    @JvmField val PARTITION_DRAW_INDEX_TYPE: Long = offset(PARTITION_DRAW, "indexType")
    @JvmField val PARTITION_DRAW_INDEX_COUNT: Long = offset(PARTITION_DRAW, "indexCount")
    @JvmField val PARTITION_DRAW_FIRST_INDEX: Long = offset(PARTITION_DRAW, "firstIndex")
    @JvmField val PARTITION_DRAW_BASE_VERTEX: Long = offset(PARTITION_DRAW, "baseVertex")
    @JvmField val PARTITION_DRAW_VERTEX_SLOT: Long = offset(PARTITION_DRAW, "vertexSlot")
    @JvmField val PARTITION_DRAW_VERTEX_STRIDE: Long = offset(PARTITION_DRAW, "vertexStride")
    @JvmField val PARTITION_DRAW_PAD0: Long = offset(PARTITION_DRAW, "_pad0")
    @JvmField
    val COLOR_TARGET: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("format"),
        I8.withName("writeMask"),
        I8.withName("blendEnabled"),
        I8.withName("srcColor"),
        I8.withName("dstColor"),
        I8.withName("colorOp"),
        I8.withName("srcAlpha"),
        I8.withName("dstAlpha"),
        I8.withName("alphaOp")
    ).withName("mcdr_color_target_desc")

    @JvmField
    val DEPTH_STATE: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("depthFormat"),
        I8.withName("depthWrite"),
        I8.withName("depthCompareOp"),
        MemoryLayout.paddingLayout(2)
    ).withByteAlignment(4).withName("mcdr_depth_state_desc")

    @JvmField
    val INPUT_ELEMENT: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("location"),
        I32.withName("binding"),
        I32.withName("format"),
        I32.withName("offset"),
        I32.withName("stride"),
        I32.withName("stepRate"),
        I32.withName("_pad0"),
        MemoryLayout.paddingLayout(4),
        PTR.withName("semanticName")
    ).withByteAlignment(8).withName("mcdr_input_element_desc")

    @JvmField
    val PIPELINE_BINDING: MemoryLayout = MemoryLayout.structLayout(
        I8.withName("type"),
        I8.withName("reg")
    ).withName("mcdr_pipeline_binding_desc")

    @JvmField
    val PIPELINE_DESC: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("vsData"),
        I32.withName("vsLen"),
        I32.withName("_pad0"),
        PTR.withName("psData"),
        I32.withName("psLen"),
        I32.withName("colorCount"),
        PTR.withName("colorTargets"),
        I32.withName("hasDepth"),
        I32.withName("pipelineFlags"),
        DEPTH_STATE.withName("depth"),
        I32.withName("topology"),
        I32.withName("cullEnabled"),
        I32.withName("polygonMode"),
        I32.withName("inputElementCount"),
        PTR.withName("inputElements"),
        I32.withName("bindingCount"),
        I32.withName("_pad2"),
        PTR.withName("bindings")
    ).withName("mcdr_pipeline_desc")

    @JvmField
    val MESH_PIPELINE_DESC: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("asData"),
        I32.withName("asLen"),
        I32.withName("_pad0"),
        PTR.withName("msData"),
        I32.withName("msLen"),
        I32.withName("_pad1"),
        PTR.withName("psData"),
        I32.withName("psLen"),
        I32.withName("colorCount"),
        PTR.withName("colorTargets"),
        I32.withName("hasDepth"),
        I32.withName("_pad2"),
        DEPTH_STATE.withName("depth"),
        I32.withName("cullEnabled"),
        I32.withName("polygonMode"),
        I32.withName("bindingCount"),
        I32.withName("_pad3"),
        PTR.withName("bindings"),
        I32.withName("rasterMotion"),
        I32.withName("_pad4")
    ).withName("mcdr_mesh_pipeline_desc")

    @JvmField
    val ABI_FIELD: MemoryLayout = MemoryLayout.structLayout(
        PTR.withName("name"),
        I64.withName("offset"),
        I64.withName("size")
    ).withName("mcdr_abi_field")

    @JvmField
    val SR_CAPS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("backend"),
        I32.withName("available"),
        I32.withName("coupledDenoise"),
        I32.withName("needsExposure"),
        I32.withName("takesMasks"),
        I32.withName("externalHeap"),
        I32.withName("jitterPhaseCount"),
        I32.withName("minRenderWidth"),
        I32.withName("minRenderHeight"),
        I32.withName("maxRenderWidth"),
        I32.withName("maxRenderHeight"),
        I32.withName("nativeAA"),
        I32.withName("reserved"),
        MemoryLayout.sequenceLayout(28L, I8).withName("note")
    ).withName("mcdr_sr_caps")

    /** `mcdr_sr_caps` 的字段偏移。 */
    @JvmField val SR_CAPS_BACKEND: Long = offset(SR_CAPS, "backend")
    @JvmField val SR_CAPS_AVAILABLE: Long = offset(SR_CAPS, "available")
    @JvmField val SR_CAPS_COUPLED_DENOISE: Long = offset(SR_CAPS, "coupledDenoise")
    @JvmField val SR_CAPS_NEEDS_EXPOSURE: Long = offset(SR_CAPS, "needsExposure")
    @JvmField val SR_CAPS_TAKES_MASKS: Long = offset(SR_CAPS, "takesMasks")
    @JvmField val SR_CAPS_EXTERNAL_HEAP: Long = offset(SR_CAPS, "externalHeap")
    @JvmField val SR_CAPS_JITTER_PHASE_COUNT: Long = offset(SR_CAPS, "jitterPhaseCount")
    @JvmField val SR_CAPS_MIN_RENDER_WIDTH: Long = offset(SR_CAPS, "minRenderWidth")
    @JvmField val SR_CAPS_MIN_RENDER_HEIGHT: Long = offset(SR_CAPS, "minRenderHeight")
    @JvmField val SR_CAPS_MAX_RENDER_WIDTH: Long = offset(SR_CAPS, "maxRenderWidth")
    @JvmField val SR_CAPS_MAX_RENDER_HEIGHT: Long = offset(SR_CAPS, "maxRenderHeight")
    @JvmField val SR_CAPS_NATIVE_AA: Long = offset(SR_CAPS, "nativeAA")
    @JvmField val SR_CAPS_NOTE: Long = offset(SR_CAPS, "note")

    @JvmField
    val FG_CAPS: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("backend"),
        I32.withName("available"),
        I32.withName("runtimeLoaded"),
        I32.withName("deviceSupported"),
        I32.withName("chainCompatible"),
        I32.withName("needsReflex"),
        I32.withName("needsUiResource"),
        I32.withName("needsHudless"),
        I32.withName("transitionsInputs"),
        I32.withName("maxGeneratedFrames"),
        I32.withName("chainOwner"),
        I32.withName("reserved"),
        MemoryLayout.sequenceLayout(28L, I8).withName("note"),
        I32.withName("latencyProvider")
    ).withName("mcdr_fg_caps")

    @JvmField
    val FG_DISPATCH: MemoryLayout = MemoryLayout.structLayout(
        I32.withName("displayWidth"),
        I32.withName("displayHeight"),
        I32.withName("backend"),
        I32.withName("mode"),
        I32.withName("numFrames"),
        I32.withName("reset"),
        I64.withName("appFrameId"),
        I64.withName("backendFrameId"),
        I64.withName("presentId"),
        PTR.withName("pBackbuffer"),
        PTR.withName("pHudlessColor"),
        PTR.withName("pDepth"),
        PTR.withName("pMotionVectors"),
        PTR.withName("pUiColor"),
        MemoryLayout.sequenceLayout(2L, I32).withName("_pad2")
    ).withName("mcdr_fg_dispatch")

    /** `mcdr_fg_caps` 的字段偏移。 */
    @JvmField val FG_CAPS_BACKEND: Long = offset(FG_CAPS, "backend")
    @JvmField val FG_CAPS_AVAILABLE: Long = offset(FG_CAPS, "available")
    @JvmField val FG_CAPS_RUNTIME_LOADED: Long = offset(FG_CAPS, "runtimeLoaded")
    @JvmField val FG_CAPS_DEVICE_SUPPORTED: Long = offset(FG_CAPS, "deviceSupported")
    @JvmField val FG_CAPS_CHAIN_COMPATIBLE: Long = offset(FG_CAPS, "chainCompatible")
    @JvmField val FG_CAPS_NEEDS_REFLEX: Long = offset(FG_CAPS, "needsReflex")
    @JvmField val FG_CAPS_NEEDS_UI_RESOURCE: Long = offset(FG_CAPS, "needsUiResource")
    @JvmField val FG_CAPS_NEEDS_HUDLESS: Long = offset(FG_CAPS, "needsHudless")
    @JvmField val FG_CAPS_TRANSITIONS_INPUTS: Long = offset(FG_CAPS, "transitionsInputs")
    @JvmField val FG_CAPS_MAX_GENERATED_FRAMES: Long = offset(FG_CAPS, "maxGeneratedFrames")
    @JvmField val FG_CAPS_CHAIN_OWNER: Long = offset(FG_CAPS, "chainOwner")
    @JvmField val FG_CAPS_NOTE: Long = offset(FG_CAPS, "note")
    @JvmField val FG_CAPS_LATENCY_PROVIDER: Long = offset(FG_CAPS, "latencyProvider")

    @JvmField val FG_DISPATCH_DISPLAY_WIDTH: Long = offset(FG_DISPATCH, "displayWidth")
    @JvmField val FG_DISPATCH_DISPLAY_HEIGHT: Long = offset(FG_DISPATCH, "displayHeight")
    @JvmField val FG_DISPATCH_BACKEND: Long = offset(FG_DISPATCH, "backend")
    @JvmField val FG_DISPATCH_MODE: Long = offset(FG_DISPATCH, "mode")
    @JvmField val FG_DISPATCH_NUM_FRAMES: Long = offset(FG_DISPATCH, "numFrames")
    @JvmField val FG_DISPATCH_RESET: Long = offset(FG_DISPATCH, "reset")
    @JvmField val FG_DISPATCH_APP_FRAME_ID: Long = offset(FG_DISPATCH, "appFrameId")
    @JvmField val FG_DISPATCH_BACKEND_FRAME_ID: Long = offset(FG_DISPATCH, "backendFrameId")
    @JvmField val FG_DISPATCH_PRESENT_ID: Long = offset(FG_DISPATCH, "presentId")
    @JvmField val FG_DISPATCH_P_BACKBUFFER: Long = offset(FG_DISPATCH, "pBackbuffer")
    @JvmField val FG_DISPATCH_P_HUDLESS_COLOR: Long = offset(FG_DISPATCH, "pHudlessColor")
    @JvmField val FG_DISPATCH_P_DEPTH: Long = offset(FG_DISPATCH, "pDepth")
    @JvmField val FG_DISPATCH_P_MOTION_VECTORS: Long = offset(FG_DISPATCH, "pMotionVectors")
    @JvmField val FG_DISPATCH_P_UI_COLOR: Long = offset(FG_DISPATCH, "pUiColor")

    private val EXPECTED_C_SIZES: Array<Array<Any>> = arrayOf(
        arrayOf<Any>("mcdr_provider_mask_input", PROVIDER_MASK_INPUT, 200L),
        arrayOf<Any>("mcdr_frame_identity", FRAME_IDENTITY, 32L),
        arrayOf<Any>("mcdr_raster_camera", RASTER_CAMERA, 256L),
        arrayOf<Any>("mcdr_frame_settings", FRAME_SETTINGS, 56L),
        arrayOf<Any>("mcdr_vanilla_frame_input", VANILLA_FRAME_INPUT, 696L),
        arrayOf<Any>("mcdr_prepared_binding", PREPARED_BINDING, 16L),
        arrayOf<Any>("mcdr_oit_capture_pipeline_v1", OIT_CAPTURE_PIPELINE, 32L),
        arrayOf<Any>("mcdr_oit_begin_v1", OIT_BEGIN, 168L),
        arrayOf<Any>("mcdr_oit_begin_v2", OIT_BEGIN_V2, 272L),
        arrayOf<Any>("mcdr_oit_terrain_section_v2", OIT_TERRAIN_SECTION, 72L),
        arrayOf<Any>("mcdr_oit_terrain_metadata_v2", OIT_TERRAIN_METADATA, 80L),
        arrayOf<Any>("mcdr_oit_terrain_draw_v2", OIT_TERRAIN_DRAW, 16L),
        arrayOf<Any>("mcdr_terrain_tile_resource_ref", TERRAIN_TILE_RESOURCE_REF, 24L),
        arrayOf<Any>("mcdr_terrain_tile", TERRAIN_TILE, 16L),
        arrayOf<Any>("mcdr_terrain_tile_stage_v2", TERRAIN_TILE_STAGE_V2, 40L),
        arrayOf<Any>("mcdr_terrain_tile_stage_binding_v2", TERRAIN_TILE_STAGE_BINDING_V2, 168L),
        arrayOf<Any>("mcdr_terrain_tile_batch_v2", TERRAIN_TILE_BATCH_V2, 1288L),
        arrayOf<Any>("mcdr_terrain_tile_batch_v3", TERRAIN_TILE_BATCH_V3, 1304L),
        arrayOf<Any>("mcdr_oit_source_v1", OIT_SOURCE, 32L),
        arrayOf<Any>("mcdr_oit_primitive_v1", OIT_PRIMITIVE, 56L),
        arrayOf<Any>("mcdr_oit_previous_triangle_v1", OIT_PREVIOUS_TRIANGLE, 80L),
        arrayOf<Any>("mcdr_oit_metadata_v1", OIT_METADATA, 56L),
        arrayOf<Any>("mcdr_oit_draw_v1", OIT_DRAW, 48L),
        arrayOf<Any>("mcdr_oit_resolve_v3", OIT_RESOLVE, 120L),
        arrayOf<Any>("mcdr_oit_resolve_v4", OIT_RESOLVE_V4, 136L),
        arrayOf<Any>("mcdr_prepared_frame", PREPARED_FRAME, 184L),
        arrayOf<Any>("mcdr_reconstruction_request", RECONSTRUCTION_REQUEST, 64L),
        arrayOf<Any>("mcdr_world_output_input", WORLD_OUTPUT_INPUT, 360L),
        arrayOf<Any>("mcdr_display_layer_input", DISPLAY_LAYER_INPUT, 584L),
        arrayOf<Any>("mcdr_display_post_input", DISPLAY_POST_INPUT, 144L),
        arrayOf<Any>("mcdr_display_frame_input", DISPLAY_FRAME_INPUT, 672L),
        arrayOf<Any>("mcdr_vanilla_resource_use", VANILLA_RESOURCE_USE, 40L),
        arrayOf<Any>("mcdr_binding_slot",         BINDING_SLOT,        40L),
        arrayOf<Any>("mcdr_partition_draw",       PARTITION_DRAW,      56L),
        arrayOf<Any>("mcdr_color_target_desc",    COLOR_TARGET,        12L),
        arrayOf<Any>("mcdr_depth_state_desc",     DEPTH_STATE,          8L),
        arrayOf<Any>("mcdr_input_element_desc",   INPUT_ELEMENT,       40L),
        arrayOf<Any>("mcdr_pipeline_binding_desc", PIPELINE_BINDING,    2L),
        arrayOf<Any>("mcdr_pipeline_desc",        PIPELINE_DESC,       96L),
        arrayOf<Any>("mcdr_mesh_pipeline_desc",   MESH_PIPELINE_DESC, 104L),
        arrayOf<Any>("mcdr_sr_caps",             SR_CAPS,            80L),
        arrayOf<Any>("mcdr_sr_dispatch",         SR_DISPATCH,        152L),
        arrayOf<Any>("mcdr_fg_caps",             FG_CAPS,            80L),
        arrayOf<Any>("mcdr_fg_dispatch",         FG_DISPATCH,        96L),
        arrayOf<Any>("mcdr_abi_field",            ABI_FIELD,           24L),
    )

    init {
        for (row in EXPECTED_C_SIZES) {
            val actual = (row[1] as MemoryLayout).byteSize()
            val expected = row[2] as Long
            if (actual != expected) {
                throw IllegalStateException(
                    "MCDR ABI struct size mismatch for ${row[0]}" +
                        ": java=${actual} c=${expected}" +
                        " — fix the mirror in MCDR_Layout.kt against MCDR/include/mcdr_abi.h"
                )
            }
        }
    }

    /** Every mirrored aggregate, keyed by its C type name. */
    private val STRUCTS: MutableMap<String, MemoryLayout> = LinkedHashMap()

    init {
        STRUCTS["mcdr_binding_slot"] = BINDING_SLOT
        STRUCTS["mcdr_frame_identity"] = FRAME_IDENTITY
        STRUCTS["mcdr_present_receipt_v1"] = PRESENT_RECEIPT
        STRUCTS["mcdr_provider_mask_input"] = PROVIDER_MASK_INPUT
        STRUCTS["mcdr_raster_camera"] = RASTER_CAMERA
        STRUCTS["mcdr_frame_settings"] = FRAME_SETTINGS
        STRUCTS["mcdr_vanilla_frame_input"] = VANILLA_FRAME_INPUT
        STRUCTS["mcdr_prepared_binding"] = PREPARED_BINDING
        STRUCTS["mcdr_oit_capture_pipeline_v1"] = OIT_CAPTURE_PIPELINE
        STRUCTS["mcdr_oit_begin_v1"] = OIT_BEGIN
        STRUCTS["mcdr_oit_begin_v2"] = OIT_BEGIN_V2
        STRUCTS["mcdr_oit_terrain_section_v2"] = OIT_TERRAIN_SECTION
        STRUCTS["mcdr_oit_terrain_metadata_v2"] = OIT_TERRAIN_METADATA
        STRUCTS["mcdr_oit_terrain_draw_v2"] = OIT_TERRAIN_DRAW
        STRUCTS["mcdr_terrain_tile_stage_v2"] = TERRAIN_TILE_STAGE_V2
        STRUCTS["mcdr_oit_source_v1"] = OIT_SOURCE
        STRUCTS["mcdr_oit_primitive_v1"] = OIT_PRIMITIVE
        STRUCTS["mcdr_oit_previous_triangle_v1"] = OIT_PREVIOUS_TRIANGLE
        STRUCTS["mcdr_oit_metadata_v1"] = OIT_METADATA
        STRUCTS["mcdr_oit_draw_v1"] = OIT_DRAW
        STRUCTS["mcdr_oit_resolve_v3"] = OIT_RESOLVE
        STRUCTS["mcdr_oit_resolve_v4"] = OIT_RESOLVE_V4
        STRUCTS["mcdr_prepared_frame"] = PREPARED_FRAME
        STRUCTS["mcdr_reconstruction_request"] = RECONSTRUCTION_REQUEST
        STRUCTS["mcdr_world_output_input"] = WORLD_OUTPUT_INPUT
        STRUCTS["mcdr_display_layer_input"] = DISPLAY_LAYER_INPUT
        STRUCTS["mcdr_display_post_input"] = DISPLAY_POST_INPUT
        STRUCTS["mcdr_display_frame_input"] = DISPLAY_FRAME_INPUT
        STRUCTS["mcdr_vanilla_resource_use"] = VANILLA_RESOURCE_USE
        STRUCTS["mcdr_partition_draw"] = PARTITION_DRAW
        STRUCTS["mcdr_color_target_desc"] = COLOR_TARGET
        STRUCTS["mcdr_depth_state_desc"] = DEPTH_STATE
        STRUCTS["mcdr_input_element_desc"] = INPUT_ELEMENT
        STRUCTS["mcdr_pipeline_binding_desc"] = PIPELINE_BINDING
        STRUCTS["mcdr_pipeline_desc"] = PIPELINE_DESC
        STRUCTS["mcdr_mesh_pipeline_desc"] = MESH_PIPELINE_DESC
        STRUCTS["mcdr_sr_caps"] = SR_CAPS
        STRUCTS["mcdr_fg_caps"] = FG_CAPS
        STRUCTS["mcdr_fg_dispatch"] = FG_DISPATCH
          STRUCTS["mcdr_sr_dispatch"] = SR_DISPATCH

    }

    /** Named top-level members of every mirrored struct, as `"Struct.field" -> {offset, size}`. */
    @JvmStatic
    fun describe(): Map<String, LongArray> {
        val out = linkedMapOf<String, LongArray>()
        for (e in STRUCTS.entries) {
            val structName = e.key
            val layout = e.value
            for (member in (layout as GroupLayout).memberLayouts()) {
                val nameOpt: Optional<String> = member.name()
                if (nameOpt.isEmpty) {
                    continue
                }
                val field = nameOpt.get()
                val offset = layout.byteOffset(PathElement.groupElement(field))
                out["${structName}.${field}"] = longArrayOf(offset, member.byteSize())
            }
        }
        return out
    }

    /**
     * Compare these mirrors against the offsets/sizes the running DLL reports; throws with every disagreement listed,
     * not just the first.
     * @param nativeFields what `mcdrABILayout()` published, as `name -> {offset, size}`
     */
    @JvmStatic
    fun verify(nativeFields: Map<String, LongArray>) {
        val javaFields = describe()
        val problems = mutableListOf<String>()

        for (e in javaFields.entries) {
            val nativeValue = nativeFields[e.key]
            if (nativeValue == null) {
                problems.add("${e.key}: missing from the native layout report")
                continue
            }
            if (nativeValue[0] != e.value[0]) {
                problems.add("${e.key}: offset java=${e.value[0]} native=${nativeValue[0]}")
            }
            if (nativeValue[1] != e.value[1]) {
                problems.add("${e.key}: size java=${e.value[1]} native=${nativeValue[1]}")
            }
        }
        for (name in nativeFields.keys) {
            if (!javaFields.containsKey(name)) {
                problems.add("${name}: reported by native but absent from the Java mirror")
            }
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "MCDR ABI layout mismatch (${problems.size}" +
                    " field(s)); the Kotlin mirror in io.github.dxegp.d3d12.ffi.MCDRLayout" +
                    " disagrees with MCDR/include/mcdr_abi.h:\n  ${problems.joinToString("\n  ")}"
            )
        }
    }

    /** Struct sizes only, for a compact startup log line. */
    @JvmStatic
    fun describeSizes(): String {
        return STRUCTS.entries.joinToString(", ") { "${it.key}=${it.value.byteSize()}" }
    }
}
