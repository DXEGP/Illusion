package io.github.dxegp.render.compilation

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendOp
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12OitCaptureProgram
import io.github.dxegp.d3d12.command.D3D12RasterRoute
import io.github.dxegp.d3d12.device.D3D12AdapterInfo
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.shader.D3D12PipelineCompilation
import io.github.dxegp.d3d12.shader.D3D12PipelineOrientation
import io.github.dxegp.d3d12.shader.D3D12PipelineVariants
import io.github.dxegp.d3d12.shader.D3D12RasterMotionShader
import io.github.dxegp.d3d12.shader.D3D12RasterMotionSource
import io.github.dxegp.d3d12.shader.D3D12RasterMotionContract
import io.github.dxegp.d3d12.shader.D3D12OitGuideSource
import io.github.dxegp.d3d12.shader.D3D12OitReplayStage
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import net.minecraft.client.renderer.RenderPipelines
import io.github.dxegp.render.terrain.mesh.TerrainPipelineCompiler
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import org.slf4j.LoggerFactory

internal class VanillaPipelineVariants : D3D12PipelineVariants {
    private val semantics = VanillaPipelineSemantics()
    private val logger = LoggerFactory.getLogger("dxrmc")

    override fun compile(compilation: D3D12PipelineCompilation) {
        val result = compilation.pipeline
        val pipeline = result.info()
        val name = pipeline.location.toString()
        val topology = pipeline.primitiveTopology
        val lineTopology = topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES
        val descriptor = compilation.baseDescriptor
        compilation.shaders.oitCapture?.let { capture ->
            val captureDescriptor = compilation.createDescriptor().shaders(capture.vertexHlsl, capture.fragmentHlsl)
            val capturePipeline = MCDR_ABI.D3D12CreateGraphicsCapturePipelineV1(captureDescriptor,
                pipeline.pushConstantSize(), capture.shaderAbiVersion, capture.shaderContractHash, capture.programGeneration)
            check(capturePipeline != 0L) { "OIT capture PSO failed for $name: ${MCDR_ABI.D3D12LastError()}" }
            var captureLineQuad = 0L
            try {
                attachOitTileIaRoute(compilation, capturePipeline)
                if (topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES) {
                    captureDescriptor.topology(PrimitiveTopology.TRIANGLES.ordinal, false, pipeline.polygonMode.ordinal)
                    captureLineQuad = MCDR_ABI.D3D12CreateGraphicsCapturePipelineV1(captureDescriptor,
                        pipeline.pushConstantSize(), capture.shaderAbiVersion, capture.shaderContractHash, capture.programGeneration)
                    check(captureLineQuad != 0L) { "OIT line capture PSO failed for $name: ${MCDR_ABI.D3D12LastError()}" }
                }
                result.attachOitCaptureVariant(D3D12OitCaptureProgram(capture.shaderAbiVersion,
                    capture.shaderContractHash, capture.programGeneration, capture.profile), capturePipeline, captureLineQuad)
            } catch (failure: Throwable) {
                MCDR_ABI.D3D12DestroyPipeline(capturePipeline)
                if (captureLineQuad != 0L) MCDR_ABI.D3D12DestroyPipeline(captureLineQuad)
                throw failure
            }
        }
        if (compilation.orientation == D3D12PipelineOrientation.ORIGINAL &&
            "OIT_ACCUMULATE" in pipeline.shaderDefines.flags() &&
            descriptor.colorTargets.size in 1 until MCDRPipelineDesc.MAX_COLOR_TARGETS) {
            val guideSource = if (OitReconstructionInputs.isCloudSource(compilation.vertexSource))
                D3D12OitGuideSource.CLOUD else D3D12OitGuideSource.CONTRIBUTION
            for (source in listOf(guideSource, D3D12OitGuideSource.UNRECORDED)) {
                val fragment = D3D12RasterMotionShader.buildOitFragment(
                    compilation.shaders, descriptor.colorTargets.size, source) ?: continue
                val guideDescriptor = compilation.createDescriptor()
                    .shaders(compilation.shaders.vertexHlsl, fragment)
                    .addColorTarget(OitReconstructionInputs.colorTarget(source))
                if (source == D3D12OitGuideSource.CLOUD)
                    guideDescriptor.addBinding(0, compilation.shaders.entries.size)
                val guidePipeline = MCDR_ABI.D3D12CreateGraphicsPipeline(guideDescriptor, pipeline.pushConstantSize())
                check(guidePipeline != 0L) { "OIT $source PSO failed for $name: ${MCDR_ABI.D3D12LastError()}" }
                var guideLineQuad = 0L
                try {
                    attachOitTileIaRoute(compilation, guidePipeline)
                    if (topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES) {
                        guideDescriptor.topology(PrimitiveTopology.TRIANGLES.ordinal, false, pipeline.polygonMode.ordinal)
                        guideLineQuad = MCDR_ABI.D3D12CreateGraphicsPipeline(guideDescriptor, pipeline.pushConstantSize())
                        check(guideLineQuad != 0L) { "OIT line $source PSO failed for $name: ${MCDR_ABI.D3D12LastError()}" }
                    }
                    if (source == D3D12OitGuideSource.UNRECORDED)
                        result.attachOitUnrecordedVariant(guidePipeline, guideLineQuad)
                    else result.attachOitGuideVariant(guidePipeline, source, guideLineQuad)
                } catch (failure: Throwable) {
                    MCDR_ABI.D3D12DestroyPipeline(guidePipeline)
                    if (guideLineQuad != 0L) MCDR_ABI.D3D12DestroyPipeline(guideLineQuad)
                    throw failure
                }
            }
        }
        val composition = D3D12RasterMotionShader.composition(pipeline)
        if (compilation.orientation == D3D12PipelineOrientation.ORIGINAL &&
            pipeline !== OitReconstructionInputs.compositePipeline &&
            (descriptor.hasDepth || compilation.celestialMotionExact || pipeline === RenderPipelines.OIT_COMPOSITE) &&
            descriptor.colorTargets.size == 1 && composition != null &&
            (!name.contains("terrain") || D3D12TerrainWorkGraph.requiresOrderedDraw(pipeline)) &&
            (topology == PrimitiveTopology.TRIANGLES || topology == PrimitiveTopology.QUADS ||
                topology == PrimitiveTopology.TRIANGLE_FAN || lineTopology)) {
            val source = when {
                lineTopology -> D3D12RasterMotionSource.CONTRIBUTION_ONLY
                pipeline === RenderPipelines.OIT_COMPOSITE -> D3D12RasterMotionSource.OIT_COMPOSITE
                compilation.celestialMotionExact -> D3D12RasterMotionSource.CELESTIAL_TRANSFORM
                result.cameraReprojectable -> D3D12RasterMotionSource.CAMERA_REPROJECTION
                D3D12RasterMotionShader.supportsVertexSource(compilation.vertexSource) ->
                    D3D12RasterMotionSource.VERTEX_HISTORY
                else -> D3D12RasterMotionSource.CONTRIBUTION_ONLY
            }
            val motion = D3D12RasterMotionShader.build(compilation.shaders, 1,
                D3D12RasterMotionContract(source, composition))
            if (motion != null) {
                val motionTarget = MCDRPipelineDesc.ColorTarget().format(GpuFormat.RGBA32_FLOAT.ordinal).writeMask(15)
                    .blend(BlendFactor.SRC_ALPHA.ordinal, BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal, BlendOp.ADD.ordinal,
                        BlendFactor.SRC_ALPHA.ordinal, BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal, BlendOp.ADD.ordinal)
                val motionDescriptor = compilation.createDescriptor()
                    .shaders(motion.vertex, motion.fragment)
                    .addColorTarget(motionTarget)
                    .addBinding(0, motion.parameterBinding)
                    .addBinding(2, motion.historyBinding)
                motionDescriptor.rasterMotion = true
                val opaqueSeed = compilation.opaqueSeed
                val motionPipeline = if (opaqueSeed != null) {
                    check(opaqueSeed.flipY == (compilation.orientation == D3D12PipelineOrientation.FLIPPED_Y) &&
                        !lineTopology && source in setOf(D3D12RasterMotionSource.VERTEX_HISTORY,
                            D3D12RasterMotionSource.CAMERA_REPROJECTION, D3D12RasterMotionSource.CONTRIBUTION_ONLY)) {
                        "Opaque seed raster motion contract does not match $name"
                    }
                    MCDR_ABI.D3D12CreateOpaqueSeedPipelineV1(motionDescriptor, pipeline.pushConstantSize(),
                        opaqueSeed.directVertexHlsl, opaqueSeed.fragmentHlsl, opaqueSeed.programGeneration)
                } else MCDR_ABI.D3D12CreateGraphicsPipeline(motionDescriptor, pipeline.pushConstantSize())
                check(motionPipeline != 0L) { "Raster motion PSO failed for $name: ${MCDR_ABI.D3D12LastError()}" }
                var motionLineQuad = 0L
                var motionAttached = false
                try {
                    attachOitTileIaRoute(compilation, motionPipeline)
                    if (lineTopology) {
                        motionDescriptor.topology(PrimitiveTopology.TRIANGLES.ordinal, false, pipeline.polygonMode.ordinal)
                        motionLineQuad = MCDR_ABI.D3D12CreateGraphicsPipeline(motionDescriptor, pipeline.pushConstantSize())
                        check(motionLineQuad != 0L) { "Line raster motion PSO failed for $name: ${MCDR_ABI.D3D12LastError()}" }
                    }
                    result.attachRasterMotionVariant(motionPipeline,
                        !lineTopology && (compilation.celestialMotionExact ||
                            D3D12RasterMotionShader.supportsVertexSource(compilation.vertexSource)),
                        lineQuadPso = motionLineQuad)
                    motionAttached = true

                    for (oitSource in if ("OIT_ACCUMULATE" in pipeline.shaderDefines.flags() &&
                        descriptor.colorTargets.size in 1 until MCDRPipelineDesc.MAX_COLOR_TARGETS) {
                        listOfNotNull(result.oitGuideSource, D3D12OitGuideSource.UNRECORDED)
                    } else emptyList()) {
                        val combined = checkNotNull(D3D12RasterMotionShader.buildOitAndMotion(compilation.shaders,
                            oitSource, descriptor.colorTargets.size + 1,
                            D3D12RasterMotionContract(source, composition))) {
                            "OIT $oitSource raster motion shader is incompatible with $name"
                        }
                        val combinedDescriptor = compilation.createDescriptor()
                            .shaders(combined.motion.vertex, combined.motion.fragment)
                            .addColorTarget(OitReconstructionInputs.colorTarget(oitSource))
                            .addColorTarget(motionTarget)
                        combined.oitParameterBinding?.let { combinedDescriptor.addBinding(0, it) }
                        combinedDescriptor.addBinding(0, combined.motion.parameterBinding)
                            .addBinding(2, combined.motion.historyBinding)
                        combinedDescriptor.rasterMotion = true
                        val combinedPipeline = MCDR_ABI.D3D12CreateGraphicsPipeline(combinedDescriptor,
                            pipeline.pushConstantSize())
                        check(combinedPipeline != 0L) {
                            "OIT $oitSource raster motion PSO failed for $name: ${MCDR_ABI.D3D12LastError()}"
                        }
                        var combinedLineQuad = 0L
                        try {
                            attachOitTileIaRoute(compilation, combinedPipeline)
                            if (lineTopology) {
                                combinedDescriptor.topology(PrimitiveTopology.TRIANGLES.ordinal, false, pipeline.polygonMode.ordinal)
                                combinedLineQuad = MCDR_ABI.D3D12CreateGraphicsPipeline(combinedDescriptor,
                                    pipeline.pushConstantSize())
                                check(combinedLineQuad != 0L) {
                                    "OIT line $oitSource raster motion PSO failed for $name: ${MCDR_ABI.D3D12LastError()}"
                                }
                            }
                            result.attachOitRasterMotionVariant(combinedPipeline, oitSource, combinedLineQuad)
                        } catch (failure: Throwable) {
                            MCDR_ABI.D3D12DestroyPipeline(combinedPipeline)
                            if (combinedLineQuad != 0L) MCDR_ABI.D3D12DestroyPipeline(combinedLineQuad)
                            throw failure
                        }
                    }
                } catch (failure: Throwable) {
                    if (!motionAttached) {
                        MCDR_ABI.D3D12DestroyPipeline(motionPipeline)
                        if (motionLineQuad != 0L) MCDR_ABI.D3D12DestroyPipeline(motionLineQuad)
                    }
                    throw failure
                }
            }
        }
        if (topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES) {
            val quadDescriptor = compilation.createDescriptor()
            quadDescriptor.topology(PrimitiveTopology.TRIANGLES.ordinal, false, pipeline.polygonMode.ordinal)
            val quadPipeline = MCDR_ABI.D3D12CreateGraphicsPipeline(quadDescriptor, pipeline.pushConstantSize())
            if (quadPipeline != 0L) {
                result.attachLineQuadVariant(quadPipeline)
                logger.info("line quad variant for {}: pso={}", name, quadPipeline)
            } else {
                logger.warn("line quad variant for {} failed; lines stay 1px", name)
            }
        }
        if (semantics.route(pipeline) == D3D12RasterRoute.TERRAIN_WORK_GRAPH) {
            check((D3D12AdapterInfo.current?.workGraphsTier ?: 0) >= 10) {
                "Terrain requires Work Graph Tier 1.0"
            }
            TerrainPipelineCompiler.compile(compilation)
        }
    }

    private fun attachOitTileIaRoute(compilation: D3D12PipelineCompilation, sourcePipeline: Long) {
        val program = compilation.iaTile ?: return
        if (program.stage != D3D12OitReplayStage.ACCUMULATE) return
        val name = compilation.pipeline.info().location.toString()
        check(name.startsWith("minecraft:pipeline/oit_accumulate_") &&
            program.flipY == (compilation.orientation == D3D12PipelineOrientation.FLIPPED_Y)) {
            "OIT tile IA variant does not match $name"
        }
        check(MCDR_ABI.D3D12AttachOitTileIARouteV1(sourcePipeline, compilation.pipeline.handle())) {
            "OIT tile IA variant attachment failed for $name: ${MCDR_ABI.D3D12LastError()}"
        }
    }

    override fun validate(pipeline: D3D12CompiledRenderPipeline) {
        check(pipeline.isValid()) { "Pipeline ${pipeline.info().location} is invalid" }
        val info = pipeline.info()
        val name = info.location.toString()
        if (semantics.route(info) == D3D12RasterRoute.TERRAIN_WORK_GRAPH) {
            check(pipeline.terrainProgram != 0L && listOf(16, 32, 64).all { pipeline.terrainPipeline(it) != 0L }) {
                "Terrain pipeline $name is missing Work Graph variants"
            }
            if (pipeline.oitCapturePso != 0L) {
                check(listOf(16, 32, 64).all { pipeline.terrainCapturePipeline(it) != 0L }) {
                    "Terrain pipeline $name is missing certified OIT capture variants"
                }
            }
        }
        val topology = info.primitiveTopology
        if (topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES) {
            check(pipeline.hasLineQuadVariant()) { "Line pipeline $name is missing its quad variant" }
            if (pipeline.rasterMotionPso != 0L) {
                check(pipeline.rasterMotionLineQuadPso != 0L && !pipeline.rasterMotionExact) {
                    "Line pipeline $name is missing its contribution motion quad variant"
                }
            }
            if (pipeline.oitGuideRasterMotionPso != 0L) {
                check(pipeline.oitGuideRasterMotionLineQuadPso != 0L) {
                    "Line pipeline $name is missing its OIT guide motion quad variant"
                }
            }
            if (pipeline.oitUnrecordedRasterMotionPso != 0L) {
                check(pipeline.oitUnrecordedRasterMotionLineQuadPso != 0L) {
                    "Line pipeline $name is missing its unrecorded OIT motion quad variant"
                }
            }
            if (pipeline.oitCapturePso != 0L) {
                check(pipeline.oitCaptureLineQuadPso != 0L) { "Line pipeline $name is missing its OIT capture quad variant" }
            }
            if (pipeline.oitUnrecordedPso != 0L) {
                check(pipeline.oitUnrecordedLineQuadPso != 0L) { "Line pipeline $name is missing its unrecorded OIT quad variant" }
            }
        }
    }

    override fun close() {
        semantics.clear()
    }
}
