package io.github.dxegp.render.terrain.mesh

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendOp
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.backend.D3D12OitTilePipelineBinding
import io.github.dxegp.d3d12.backend.D3D12OitTileTerrainNativeRoute
import io.github.dxegp.d3d12.ffi.MCDRMeshPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.shader.D3D12PipelineCompilation
import io.github.dxegp.d3d12.shader.D3D12PipelineOrientation
import io.github.dxegp.d3d12.shader.D3D12RasterComposition
import io.github.dxegp.d3d12.shader.D3D12RasterMotionContract
import io.github.dxegp.d3d12.shader.D3D12RasterMotionShader
import io.github.dxegp.d3d12.shader.D3D12RasterMotionSource
import io.github.dxegp.d3d12.shader.D3D12OitGuideSource
import io.github.dxegp.d3d12.shader.D3D12OitTileCompilation
import io.github.dxegp.d3d12.shader.D3D12OitTileMeshCompilation
import io.github.dxegp.d3d12.shader.D3D12OitTileTerrainRoute
import io.github.dxegp.d3d12.shader.identity
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs

internal object TerrainPipelineCompiler {
    fun compile(compilation: D3D12PipelineCompilation) {
        val result = compilation.pipeline
        if (result.terrainProgram != 0L) return
        val info = result.info()
        val compiled = compilation.shaders
        val format = info.vertexFormatBindings.firstOrNull() ?: return
        val uniformRegisters = compiled.entries.mapIndexedNotNull { index, binding ->
            if (binding.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER) index else null
        }.toIntArray()
        require(compiled.entries.withIndex().none { (index, binding) ->
            if (binding.type == D3D12BindGroupEntry.Type.UNIFORM_BUFFER) index == 8 || index == 9
            else index in 14..16
        }) { "Terrain pipeline bindings overlap reserved b8/b9/t14/t15/t16" }
        val backfaceCull = info.isCull && compilation.orientation == D3D12PipelineOrientation.ORIGINAL &&
            !info.location.toString().contains("animate_sprite")
        val source = D3D12TerrainWorkGraph.source(compiled, backfaceCull) ?: return
        fun compile(hlsl: String, entry: String, profile: String): ByteArray =
            checkNotNull(MCDR_ABI.D3D12CompileShaderDxc(hlsl, entry, profile)) {
                "Terrain $profile/$entry compilation failed: ${MCDR_ABI.D3D12LastError()}"
            }
        val nodes = compile(source, "", "lib_6_9")
        val arguments = D3D12TerrainWorkGraph.resource("terrain_arguments.hlsl")
        val initialize = compile(arguments, "InitializeTerrain", "cs_6_9")
        val finalize = compile(arguments, "FinalizeTerrainArguments", "cs_6_9")
        val oit = "OIT_ACCUMULATE" in info.shaderDefines.flags()
        val oitFragments = if (oit) listOf(D3D12OitGuideSource.CONTRIBUTION, D3D12OitGuideSource.UNRECORDED)
            .associateWith { source -> checkNotNull(D3D12RasterMotionShader.buildOitFragment(
                compiled, info.colorTargetStates.size, source)) {
                "Terrain OIT $source output is unsupported for ${info.location}"
            } } else emptyMap()
        val oitPixels = oitFragments.mapValues { (_, source) -> compile(source, "main", "ps_6_9") }
        val pixel = compile(compiled.fragmentHlsl, "main", "ps_6_9")
        val capture = compiled.oitCapture
        val captureIdentity = capture?.identity ?: compiled.oitTileCaptureIdentity
        val capturePixel = capture?.let { compile(it.terrainFragmentHlsl, "main", "ps_6_9") }
        val composition = D3D12RasterMotionShader.composition(info)
        val motionPixel = if (compilation.orientation == D3D12PipelineOrientation.ORIGINAL &&
            info.depthStencilState != null && composition == D3D12RasterComposition.REPLACE &&
            !D3D12TerrainWorkGraph.requiresOrderedDraw(info) && !info.location.toString().contains("oit_")) {
            val motionSource = if (result.cameraReprojectable) D3D12RasterMotionSource.CAMERA_REPROJECTION
                else D3D12RasterMotionSource.CONTRIBUTION_ONLY
            val fragment = checkNotNull(D3D12RasterMotionShader.buildTerrainFragment(compiled, 1,
                D3D12RasterMotionContract(motionSource, composition))) {
                "Terrain raw fragment output is unsupported for ${info.location}"
            }
            compile(fragment, "main", "ps_6_9")
        } else null
        val oitMotionPixels = if (oitFragments.isNotEmpty() &&
            compilation.orientation == D3D12PipelineOrientation.ORIGINAL && info.depthStencilState != null) {
            val contract = D3D12RasterMotionContract(D3D12RasterMotionSource.CONTRIBUTION_ONLY,
                checkNotNull(composition) { "Terrain OIT composition is unsupported for ${info.location}" })
            oitFragments.mapValues { (source, fragmentSource) ->
                val fragment = checkNotNull(D3D12RasterMotionShader.buildTerrainFragment(
                    compiled.copy(fragmentHlsl = fragmentSource), info.colorTargetStates.size + 1, contract)) {
                    "Terrain combined OIT $source motion output is unsupported for ${info.location}"
                }
                compile(fragment, "main", "ps_6_9")
            }
        } else emptyMap()
        val rootBindings = compiled.entries.mapIndexed { index, binding ->
            (when (binding.type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2
            }) to index
        }
        val root = MCDR_ABI.D3D12CreateMeshRootSignature(rootBindings, 2, info.pushConstantSize())
        check(root != 0L) { "Terrain root signature failed: ${MCDR_ABI.D3D12LastError()}" }
        val variants = mutableMapOf<Int, Long>()
        val motionVariants = mutableMapOf<Int, Long>()
        val oitVariants = mutableMapOf<D3D12OitGuideSource, MutableMap<Int, Long>>()
        val oitMotionVariants = mutableMapOf<D3D12OitGuideSource, MutableMap<Int, Long>>()
        val captureVariants = mutableMapOf<Int, Long>()
        fun terrainDescriptor(mesh: ByteArray, fragment: ByteArray): MCDRMeshPipelineDesc {
            val descriptor = MCDRMeshPipelineDesc().shaders(mesh, null, fragment)
            for (binding in rootBindings) descriptor.addBinding(binding.first, binding.second)
            for (state in info.colorTargetStates) {
                val target = MCDRPipelineDesc.ColorTarget()
                if (state == null) target.format(-1)
                else {
                    target.format(state.format().ordinal).writeMask(state.writeMask())
                    state.blendFunction().ifPresent { blend ->
                        target.blend(blend.color().sourceFactor().ordinal, blend.color().destFactor().ordinal,
                            blend.color().op().ordinal, blend.alpha().sourceFactor().ordinal,
                            blend.alpha().destFactor().ordinal, blend.alpha().op().ordinal)
                    }
                }
                descriptor.addColorTarget(target)
            }
            info.depthStencilState?.let { depth ->
                descriptor.depth(GpuFormat.D32_FLOAT.ordinal, depth.writeDepth(), depth.depthTest().ordinal)
            }
            return descriptor.topology(backfaceCull, info.polygonMode.ordinal)
        }
        val motionTarget = MCDRPipelineDesc.ColorTarget()
            .format(GpuFormat.RGBA32_FLOAT.ordinal).writeMask(15)
            .blend(BlendFactor.SRC_ALPHA.ordinal, BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal, BlendOp.ADD.ordinal,
                BlendFactor.SRC_ALPHA.ordinal, BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal, BlendOp.ADD.ordinal)
        var captureRoot = 0L
        var program = 0L
        val tileCandidate = (compilation.tileRoute as? D3D12OitTileCompilation.Candidate)?.program
        val tileMeshRoutes = mutableMapOf<Int, D3D12OitTileMeshCompilation>()
        var attachedTileNativeRoute: D3D12OitTileTerrainNativeRoute? = null
        try {
            if (capture != null) {
                captureRoot = MCDR_ABI.D3D12CreateMeshCaptureRootSignatureV1(rootBindings, 2, info.pushConstantSize(),
                    capture.shaderAbiVersion, capture.shaderContractHash, capture.programGeneration)
                check(captureRoot != 0L) { "Terrain OIT capture root failed: ${MCDR_ABI.D3D12LastError()}" }
            }
            for (quads in listOf(16, 32, 64)) {
                val hlsl = checkNotNull(D3D12MeshletShader.build(compiled, format,
                    quadsPerMeshlet = quads, visibleWork = true))
                tileCandidate?.let { tileMeshRoutes[quads] = it.routeTerrainMesh(hlsl) }
                val mesh = compile(hlsl, "ms_main", "ms_6_9")
                val descriptor = terrainDescriptor(mesh, pixel)
                val pipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(descriptor, root, info.pushConstantSize())
                check(pipeline != 0L) { "Terrain visible MS PSO failed: ${MCDR_ABI.D3D12LastError()}" }
                variants[quads] = pipeline
                if (capture != null) {
                    val captureMesh = checkNotNull(D3D12MeshletShader.build(compiled, format,
                        quadsPerMeshlet = quads, visibleWork = true, capture = capture)) {
                        "Terrain OIT capture mesh shader is unsupported for ${info.location}"
                    }
                    val captureDescriptor = terrainDescriptor(compile(captureMesh, "ms_main", "ms_6_9"),
                        checkNotNull(capturePixel))
                    val capturePipeline = MCDR_ABI.D3D12CreateMeshCapturePipelineWithRootSignatureV1(captureDescriptor,
                        captureRoot, info.pushConstantSize(), capture.shaderAbiVersion, capture.shaderContractHash,
                        capture.programGeneration)
                    check(capturePipeline != 0L) { "Terrain OIT capture MS PSO failed: ${MCDR_ABI.D3D12LastError()}" }
                    captureVariants[quads] = capturePipeline
                }
                for ((source, oitPixel) in oitPixels) {
                    val oitDescriptor = terrainDescriptor(mesh, oitPixel)
                        .addColorTarget(OitReconstructionInputs.colorTarget(source))
                    val oitPipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(oitDescriptor, root,
                        info.pushConstantSize())
                    check(oitPipeline != 0L) { "Terrain OIT $source MS PSO failed: ${MCDR_ABI.D3D12LastError()}" }
                    oitVariants.getOrPut(source) { mutableMapOf() }[quads] = oitPipeline
                }
                for ((source, oitMotionPixel) in oitMotionPixels) {
                    val oitMotionDescriptor = terrainDescriptor(mesh, oitMotionPixel)
                        .addColorTarget(OitReconstructionInputs.colorTarget(source))
                        .addColorTarget(motionTarget)
                    oitMotionDescriptor.rasterMotion = true
                    val oitMotionPipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(oitMotionDescriptor,
                        root, info.pushConstantSize())
                    check(oitMotionPipeline != 0L) {
                        "Terrain combined OIT motion MS PSO failed: ${MCDR_ABI.D3D12LastError()}"
                    }
                    oitMotionVariants.getOrPut(source) { mutableMapOf() }[quads] = oitMotionPipeline
                }
                if (motionPixel != null) {
                    val motionDescriptor = terrainDescriptor(mesh, motionPixel).addColorTarget(motionTarget)
                    motionDescriptor.rasterMotion = true
                    val motionPipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(motionDescriptor, root,
                        info.pushConstantSize())
                    check(motionPipeline != 0L) { "Terrain raw MS PSO failed: ${MCDR_ABI.D3D12LastError()}" }
                    motionVariants[quads] = motionPipeline
                }
            }
            program = MCDR_ABI.D3D12CreateTerrainWorkGraph(nodes, initialize, finalize,
                uniformRegisters, 512L * 1024 * 1024)
            check(program != 0L) { "Terrain Work Graph creation failed: ${MCDR_ABI.D3D12LastError()}" }
            val tileRouteDescriptor = if (tileCandidate != null && captureIdentity != null &&
                tileMeshRoutes.size == 3 && tileMeshRoutes.values.all { it is D3D12OitTileMeshCompilation.Candidate }) {
                D3D12OitTileTerrainRoute(tileCandidate, captureIdentity, tileMeshRoutes.toMap())
            } else null
            tileRouteDescriptor?.let { route ->
                val rootKey = io.github.dxegp.d3d12.backend.D3D12OitTileRootKey(
                    route.capture, rootBindings, info.pushConstantSize(),
                    compilation.orientation == D3D12PipelineOrientation.FLIPPED_Y)
                var tileRootLease: io.github.dxegp.d3d12.backend.D3D12OitTileRootLease? = null
                var nativeRouteOwner: D3D12OitTileTerrainNativeRoute? = null
                var binningLease: io.github.dxegp.d3d12.backend.D3D12OitTileRouteRegistry.BinningLease? = null
                val nativeBindings = mutableMapOf<Int, D3D12OitTilePipelineBinding>()
                try {
                    tileRootLease = io.github.dxegp.d3d12.backend.D3D12OitTileRootLease.acquire(rootKey) {
                        MCDR_ABI.D3D12CreateTerrainTileRootV1(rootBindings, info.pushConstantSize(),
                            route.capture.shaderAbiVersion, route.capture.shaderContractHash,
                            route.capture.programGeneration)
                    }
                    val tileRoot = tileRootLease.handle()
                    binningLease = io.github.dxegp.d3d12.backend.D3D12OitTileRouteRegistry.BinningLease.acquire(rootKey)
                    for (quads in listOf(16, 32, 64)) {
                        val meshRoute = (route.meshRoutes.getValue(quads) as D3D12OitTileMeshCompilation.Candidate)
                        val tileMesh = compile(meshRoute.meshHlsl, "ms_main", "ms_6_9")
                        val tilePixel = compile(meshRoute.fragmentHlsl, "main", "ps_6_9")
                        var tilePipeline = 0L
                        var nativeRoute = 0L
                        try {
                            tilePipeline = MCDR_ABI.D3D12CreateMeshPipelineWithRootSignature(
                                terrainDescriptor(tileMesh, tilePixel), tileRoot, info.pushConstantSize())
                            check(tilePipeline != 0L) {
                                "Terrain tile ${quads}-quad PSO failed: ${MCDR_ABI.D3D12LastError()}"
                            }
                            nativeRoute = MCDR_ABI.D3D12CreateTerrainTileRouteV1(tileRoot,
                                route.capture.shaderAbiVersion, route.capture.shaderContractHash,
                                route.capture.programGeneration)
                            check(nativeRoute != 0L) {
                                "Terrain tile ${quads}-quad route failed: ${MCDR_ABI.D3D12LastError()}"
                            }
                            check(MCDR_ABI.D3D12BindTerrainTilePipelineV1(nativeRoute, tilePipeline)) {
                                "Terrain tile ${quads}-quad pipeline bind failed: ${MCDR_ABI.D3D12LastError()}"
                            }
                            check(MCDR_ABI.D3D12BindTerrainTileStageV2(
                                variants.getValue(quads), nativeRoute, route.capture.shaderAbiVersion,
                                route.capture.shaderContractHash, route.capture.programGeneration,
                                route.program.stage.ordinal, quads)) {
                                "Terrain tile ${quads}-quad source bind failed: ${MCDR_ABI.D3D12LastError()}"
                            }
                            captureVariants[quads]?.let { capturePipeline ->
                                check(MCDR_ABI.D3D12BindTerrainTileStageV2(
                                    capturePipeline, nativeRoute, route.capture.shaderAbiVersion,
                                    route.capture.shaderContractHash, route.capture.programGeneration,
                                    route.program.stage.ordinal, quads)) {
                                    "Terrain tile ${quads}-quad capture bind failed: ${MCDR_ABI.D3D12LastError()}"
                                }
                            }
                            nativeBindings[quads] = D3D12OitTilePipelineBinding(nativeRoute, tilePipeline)
                            nativeRoute = 0L
                            tilePipeline = 0L
                        } finally {
                            if (tilePipeline != 0L) MCDR_ABI.D3D12DestroyPipeline(tilePipeline)
                            if (nativeRoute != 0L) MCDR_ABI.D3D12DestroyTerrainTileRouteV1(nativeRoute)
                        }
                    }
                    nativeRouteOwner = D3D12OitTileTerrainNativeRoute(route,
                        checkNotNull(tileRootLease), rootKey, checkNotNull(tileCandidate).stage, nativeBindings.toMap(),
                        checkNotNull(binningLease))
                    tileRootLease = null
                    binningLease = null
                    result.attachOitTileTerrainRoute(route, nativeRouteOwner)
                    attachedTileNativeRoute = nativeRouteOwner
                } catch (failure: Throwable) {
                    if (nativeRouteOwner == null) {
                        for (binding in nativeBindings.values) {
                            MCDR_ABI.D3D12DestroyPipeline(binding.tilePipeline)
                            MCDR_ABI.D3D12DestroyTerrainTileRouteV1(binding.route)
                        }
                    } else if (attachedTileNativeRoute == null) {
                        nativeRouteOwner.close()
                    }
                    binningLease?.close()
                    tileRootLease?.close()
                    throw failure
                }
            }
            result.attachTerrainVariants(program, variants, motionVariants, oitVariants, oitMotionVariants, captureVariants)
        } catch (failure: Throwable) {
            attachedTileNativeRoute?.close()
            for (pipeline in variants.values + motionVariants.values + oitVariants.values.flatMap { it.values } +
                oitMotionVariants.values.flatMap { it.values } + captureVariants.values)
                MCDR_ABI.D3D12DestroyPipeline(pipeline)
            if (program != 0L) MCDR_ABI.D3D12DestroyTerrainWorkGraph(program)
            throw failure
        } finally {
            if (captureRoot != 0L) MCDR_ABI.D3D12ReleaseRootSignature(captureRoot)
            MCDR_ABI.D3D12ReleaseRootSignature(root)
        }
    }
}
