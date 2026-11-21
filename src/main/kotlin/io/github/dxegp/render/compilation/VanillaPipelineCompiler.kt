package io.github.dxegp.render.compilation

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.backend.api.BackendRenderPipeline
import com.mojang.renderpearl.util.ShaderCompileException
import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.diagnostics.ShaderDumps
import io.github.dxegp.d3d12.backend.D3D12BindGroupEntry
import io.github.dxegp.d3d12.backend.D3D12CompiledRenderPipeline
import io.github.dxegp.d3d12.backend.D3D12PipelineBridge
import io.github.dxegp.d3d12.command.D3D12CommandEncoderBackend
import io.github.dxegp.d3d12.ffi.MCDRPipelineDesc
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.shader.CompilationRevokedException
import io.github.dxegp.d3d12.shader.D3D12CompiledShader
import io.github.dxegp.d3d12.shader.D3D12FallbackShaderSource
import io.github.dxegp.d3d12.shader.D3D12IntermediaryShaderModule
import io.github.dxegp.d3d12.shader.D3D12OitNormalizationCertification
import io.github.dxegp.d3d12.shader.D3D12OitTileShader
import io.github.dxegp.d3d12.shader.D3D12OitTileIaCompilation
import io.github.dxegp.d3d12.shader.D3D12OpaqueSeedCompilation
import io.github.dxegp.d3d12.shader.D3D12PipelineCompilation
import io.github.dxegp.d3d12.shader.D3D12PipelineCompiler
import io.github.dxegp.d3d12.shader.D3D12PipelineOrientation
import io.github.dxegp.d3d12.shader.D3D12PipelineReload
import io.github.dxegp.d3d12.shader.D3D12PipelineReloadRequest
import io.github.dxegp.d3d12.shader.D3D12PipelineVariants
import io.github.dxegp.d3d12.shader.D3D12ShaderIo
import io.github.dxegp.d3d12.shader.D3D12ShaderCompiler
import io.github.dxegp.d3d12.shader.FrontendReloadInstall
import io.github.dxegp.d3d12.shader.injectShaderDefines
import io.github.dxegp.render.frameprep.FramePrepPrograms
import io.github.dxegp.render.presentation.D3D12SceneDisplay
import io.github.dxegp.render.reconstruction.oit.OitReconstructionInputs
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.ShadercIncludeResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

internal class VanillaPipelineCompiler(
    private val defaultShaderSource: ShaderSource?,
    private val pipelineVariants: D3D12PipelineVariants,
) : D3D12PipelineCompiler {
    private var pipelineCache: MutableMap<RenderPipeline, D3D12CompiledRenderPipeline> = IdentityHashMap()
    /**
     * flipY 变体管线缓存（GUI 离屏 pass 专用）。键是值语义的 [FlipYKey]、每次调用新建实例，必须用
     * HashMap 按 equals/hashCode 查；误用 IdentityHashMap 会永远 miss → 每帧重编译。
     */
    private var pipelineCacheFlipY: MutableMap<FlipYKey, D3D12CompiledRenderPipeline> = HashMap()
    private var pipelineSources = IdentityHashMap<RenderPipeline, ShaderSource?>()
    private val shaderCache: MutableMap<ShaderCompilationKey, D3D12IntermediaryShaderModule> = LinkedHashMap()
    private var shaderCacheBytes = 0L
    private val pipelinePrewarm = VersionedPipelinePrewarm<RenderPipeline, ReloadPipelinePair>()
    private var glslCompiler: D3D12ShaderCompiler? = null
    private var pipelineReload: PipelineReload? = null
    private var publishedShaderSource: ShaderSource? = null
    private var publishedFramePrograms: OitReconstructionInputs.ResolveProgram? = null
    private var closed = false
    private var reloadRequest = 0L
    private val sourceGenerations = WeakHashMap<ShaderSource, Long>()

    private class ReloadPipelinePair(
        val normal: D3D12CompiledRenderPipeline? = null,
        val flipped: D3D12CompiledRenderPipeline? = null,
    ) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            for (pipeline in listOfNotNull(normal, flipped)) {
                try { D3D12PipelineBridge.release(pipeline) } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            failure?.let { throw it }
        }
    }

    private class PipelineReload(
        val owner: VanillaPipelineCompiler,
        val generation: Long,
        val source: ShaderSource,
        val request: Long,
    ) : D3D12PipelineReload {
        var finished = false
        var published = false
        var compilationFinished = false
        var framePrograms: FramePrepPrograms.Prepared? = null
        var frontendResults = ArrayList<CompiledRenderPipeline>()
        val frontendPipelines = Collections.newSetFromMap(IdentityHashMap<RenderPipeline, Boolean>())
    }

    private var reloadPrepared: VersionedPipelinePrewarm.Prepared<RenderPipeline, ReloadPipelinePair>? = null
    private var reloadPairs: Map<RenderPipeline, ReloadPipelinePair> = emptyMap()

    @Synchronized
    override fun requestPipelineReload(): Long {
        check(!closed) { "Pipeline compiler is closed" }
        reloadRequest = Math.incrementExact(reloadRequest)
        return reloadRequest
    }

    private data class FlipYKey(val pipeline: RenderPipeline, val flipY: Boolean)

    @Synchronized
    private fun beginPipelineReload(): Long {
        check(!closed) { "Pipeline compiler is closed" }
        pipelineReload?.let(::abortPipelineReload)
        val generation = this.getOrCreateCompiler().invalidate()
        pipelinePrewarm.invalidate()
        synchronized(shaderCache) {
            shaderCache.values.forEach { it.close() }
            shaderCache.clear()
            shaderCacheBytes = 0L
        }
        return generation
    }

    /** Called once, after Configs is loaded and before either vanilla inventory is compiled. */
    @Synchronized
    override fun beginPipelineReload(request: D3D12PipelineReloadRequest): D3D12PipelineReload {
        check(request.request == reloadRequest) { "Shader reload request was superseded" }
        val source = request.source
        val required = request.required
        val optional = request.optional
        val generation = beginPipelineReload()
        val reload = PipelineReload(this, generation, source, request.request)
        sourceGenerations[source] = generation
        pipelineReload = reload
        val inventory = Collections.newSetFromMap(IdentityHashMap<RenderPipeline, Boolean>())
        inventory.addAll(required)
        inventory.addAll(optional)
        inventory.add(OitReconstructionInputs.compositePipeline)
        inventory.addAll(D3D12SceneDisplay.pipelines)
        inventory.addAll(D3D12PipelineBridge.inventory())
        inventory.addAll(pipelineCache.keys)
        inventory.addAll(pipelineCacheFlipY.keys.map { it.pipeline })
        reload.frontendPipelines.addAll(required)
        reload.frontendPipelines.addAll(optional)
        val pairs = IdentityHashMap<RenderPipeline, ReloadPipelinePair>()
        try {
            D3D12PipelineBridge.stage(source)
            reloadPrepared = pipelinePrewarm.prepare(pipelinePrewarm.generation, inventory,
                compile = { pipeline ->
                    compileReloadPair(pipeline, source, generation).also { pair ->
                        pairs[pipeline] = pair
                        pair.normal?.let { compiled ->
                            D3D12PipelineBridge.observeFrontends(compiled) { recordReloadFrontend(reload, it) }
                        }
                    }
                },
                validate = { true })
            reloadPairs = pairs
            return reload
        } catch (failure: Throwable) {
            reload.compilationFinished = true
            try { abortPipelineReload(reload) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun compileReloadPair(pipeline: RenderPipeline, source: ShaderSource,
        generation: Long): ReloadPipelinePair {
        var normal: D3D12CompiledRenderPipeline? = null
        var flipped: D3D12CompiledRenderPipeline? = null
        try {
            normal = compilePipeline(pipeline, source, false, generation)
            pipelineVariants.validate(normal)
            flipped = compilePipeline(pipeline, source, true, generation)
            pipelineVariants.validate(flipped)
            return ReloadPipelinePair(normal, flipped)
        } catch (failure: Throwable) {
            listOfNotNull(normal, flipped).forEach {
                try { releaseCompiledPipeline(it) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            if (failure !is Exception || getOrCreateCompiler().generation != generation || closed) throw failure
            LOGGER.warn("Pipeline pair {} failed during reload", pipeline.location, failure)
            return ReloadPipelinePair()
        }
    }

    @Synchronized
    override fun abortPipelineReload(reload: D3D12PipelineReload) {
        val transaction = requireNotNull(reload as? PipelineReload) { "Unknown pipeline reload token" }
        require(transaction.owner === this) { "Pipeline reload belongs to another compiler" }
        if (pipelineReload !== transaction || transaction.finished) return
        transaction.finished = true
        pipelineReload = null
        val abandoned = reloadPrepared
        reloadPrepared = null
        reloadPairs = emptyMap()
        val programs = transaction.framePrograms
        transaction.framePrograms = null
        var failure: Throwable? = null
        for (resource in listOfNotNull(programs, abandoned, AutoCloseable { closeAbandonedReload(transaction) })) {
            try { resource.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    override fun completePipelineReload(reload: D3D12PipelineReload) {
        val transaction = requireNotNull(reload as? PipelineReload) { "Unknown pipeline reload token" }
        require(transaction.owner === this) { "Pipeline reload belongs to another compiler" }
        transaction.compilationFinished = true
        closeAbandonedReload(transaction)
    }

    @Synchronized
    private fun recordReloadFrontend(reload: PipelineReload, compiled: CompiledRenderPipeline?) {
        if (compiled == null) return
        if (reload.finished && !reload.published) compiled.close()
        else reload.frontendResults += compiled
    }

    private fun closeAbandonedReload(reload: PipelineReload) {
        if (!reload.finished || reload.published) return
        var failure: Throwable? = null
        reload.frontendResults.forEach {
            try { it.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        reload.frontendResults.clear()
        if (reload.compilationFinished) {
            D3D12PipelineBridge.discard(reload.source)
            try { reload.source.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    override fun abortPipelineReloadRequest(request: Long) {
        pipelineReload?.takeIf { it.request == request }?.let(::abortPipelineReload)
    }

    @Synchronized
    override fun validatePipelineReload(source: ShaderSource) {
        val reload = checkNotNull(pipelineReload) { "No shader reload to publish" }
        check(reload.source === source && !reload.finished && reload.request == reloadRequest &&
            reload.generation == getOrCreateCompiler().generation) { "Shader reload was revoked" }
    }

    @Synchronized
    override fun hasPublishedPipelineCache(): Boolean = !closed && publishedShaderSource != null

    @Synchronized
    override fun preparePipelineReloadPrograms(source: ShaderSource, encoder: D3D12CommandEncoderBackend) {
        validatePipelineReload(source)
        val reload = checkNotNull(pipelineReload)
        if (reload.framePrograms != null) return
        reload.framePrograms = FramePrepPrograms.prepare(encoder, reload.generation)
    }

    private class PreparedPublication(
        val normal: MutableMap<RenderPipeline, D3D12CompiledRenderPipeline>,
        val flipped: MutableMap<FlipYKey, D3D12CompiledRenderPipeline>,
        val sources: IdentityHashMap<RenderPipeline, ShaderSource?>,
        val candidates: List<D3D12CompiledRenderPipeline>,
        val retired: List<AutoCloseable>,
        val resolve: OitReconstructionInputs.ResolveProgram,
        val emptyFrontends: ArrayList<CompiledRenderPipeline> = ArrayList(),
        val emptyPairs: Map<RenderPipeline, ReloadPipelinePair> = emptyMap(),
    )

    @Synchronized
    override fun publishPipelineReload(source: ShaderSource, frontendInventory: Set<RenderPipeline>,
        frontend: FrontendReloadInstall) {
        validatePipelineReload(source)
        val reload = checkNotNull(pipelineReload)
        val prepared = checkNotNull(reloadPrepared)
        val programs = checkNotNull(reload.framePrograms) { "Frame programs were not prepared before publication" }
        check(programs.generation == reload.generation) { "Frame program generation was revoked" }
        val normal = IdentityHashMap<RenderPipeline, D3D12CompiledRenderPipeline>()
        val flipped = HashMap<FlipYKey, D3D12CompiledRenderPipeline>()
        val sources = IdentityHashMap<RenderPipeline, ShaderSource?>()
        val candidates = ArrayList<D3D12CompiledRenderPipeline>()
        val retired = ArrayList<AutoCloseable>()
        retired += AutoCloseable { frontend.closeRetired() }
        retired += AutoCloseable { programs.releaseRegistration() }
        retired += AutoCloseable { D3D12FallbackShaderSource.clearProvider() }
        publishedFramePrograms?.let(retired::add)
        reloadPairs.forEach { (pipeline, pair) ->
            pair.normal?.let(candidates::add)
            if (pipeline in reload.frontendPipelines && pipeline !in frontendInventory) {
                retired += pair
            } else if (pair.normal != null && pair.flipped != null) {
                normal[pipeline] = pair.normal
                flipped[FlipYKey(pipeline, true)] = pair.flipped
                sources[pipeline] = source
            }
        }
        val retiredPipelines = Collections.newSetFromMap(IdentityHashMap<D3D12CompiledRenderPipeline, Boolean>())
        retiredPipelines.addAll(pipelineCache.values)
        retiredPipelines.addAll(pipelineCacheFlipY.values)
        retiredPipelines.forEach { retired += AutoCloseable { D3D12PipelineBridge.release(it) } }
        val publication = PreparedPublication(normal, flipped, sources, candidates, retired, programs.resolveProgram)
        try {
            pipelinePrewarm.withPrepared(prepared) {
                D3D12PipelineBridge.publishRegistrations(source, publication.normal.keys, publication.candidates) { registrations ->
                    try {
                        frontend.install()
                        validatePipelineReload(source)
                        programs.commitNative()
                    } catch (failure: Throwable) {
                        try { frontend.restore() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                    programs.publish(publication.resolve)
                    publishedFramePrograms = publication.resolve
                    reload.framePrograms = null
                    pipelineCache = publication.normal
                    pipelineCacheFlipY = publication.flipped
                    pipelineSources = publication.sources
                    registrations.publish()
                    publishedShaderSource = source
                    reload.published = true
                    reload.finished = true
                    reload.frontendResults = publication.emptyFrontends
                    pipelineReload = null
                    reloadPrepared = null
                    reloadPairs = publication.emptyPairs
                    prepared.transferOwnership()
                }
            }
        } catch (failure: Throwable) {
            try { abortPipelineReload(reload) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        publication.retired.forEach { resource ->
            try { resource.close() } catch (cleanup: Throwable) {
                LOGGER.error("Could not close retired shader reload resource", cleanup)
            }
        }
    }

    @Synchronized
    override fun getOrCompilePipeline(pipeline: RenderPipeline,
        orientation: D3D12PipelineOrientation): D3D12CompiledRenderPipeline {
        val flipY = orientation == D3D12PipelineOrientation.FLIPPED_Y
        check(!closed) { "Pipeline compiler is closed" }
        if (!flipY) {
            pipelineCache[pipeline]?.takeIf { it.isValid() }?.let { return it }
            return compilePipeline(pipeline, resolveShaderSource(pipeline), false).also {
                if (it.isValid()) pipelineCache[pipeline] = it
            }
        }
        val key = FlipYKey(pipeline, true)
        pipelineCacheFlipY[key]?.takeIf { it.isValid() }?.let { return it }
        return compilePipeline(pipeline, resolveShaderSource(pipeline), true).also {
            if (it.isValid()) pipelineCacheFlipY[key] = it
        }
    }

    /**
     * Resolve the published source for lazy variants without consulting registrations from a reload that is
     * still being prepared. Dynamic frontend sources remain pipeline-specific.
     */
    private fun resolveShaderSource(pipeline: RenderPipeline): ShaderSource? {
        if (pipelineSources.containsKey(pipeline)) return pipelineSources[pipeline]
        return publishedShaderSource ?: D3D12PipelineBridge.lookupSource(pipeline.location.toString()) ?: this.defaultShaderSource
    }

    /**
     * backend 编译入口只收到 [BackendRenderPipeline.CreateInfo]，拿不到 vanilla RenderPipeline，而 GLSL→HLSL 链、
     * shaderDefines、mesh 变体判定都依赖后者 —— 按 `CreateInfo.name()` 从 [D3D12PipelineBridge] 查回。
     */
    @Synchronized
    override fun compilePipeline(
        info: BackendRenderPipeline.CreateInfo
    ): BackendRenderPipeline.Pending {
        val pipeline = D3D12PipelineBridge.lookup(info.name())
        if (pipeline == null) {
            LOGGER.error("compilePipeline({}) has no registered RenderPipeline "
                + "({} shaders); returning NULL pending", info.name(), info.shaders().size)
            return BackendRenderPipeline.Pending.NULL
        }
        return compilePipeline(info, pipeline, resolveShaderSource(pipeline))
    }

    /** PipelineBuilder supplies the exact source captured by this asynchronous frontend job. */
    @Synchronized
    override fun compilePipeline(info: BackendRenderPipeline.CreateInfo, pipeline: RenderPipeline,
        source: ShaderSource?): BackendRenderPipeline.Pending {
        check(!closed) { "Pipeline compiler is closed" }
        val uniformNames = info.uniforms().map { it.name() }
        if (info.name().contains("terrain")) {
            val detail = info.uniforms().joinToString(", ") { "${it.name()}:${it.type()}" }
            IllusionLogger.debugMessage("uniforms ${info.name()}: [$detail]")
        }
        val expectedGeneration = if (source != null && source !== publishedShaderSource)
            sourceGenerations[source] ?: getOrCreateCompiler().generation
        else getOrCreateCompiler().generation
        val reload = pipelineReload?.takeIf { it.source === source }
        if (source != null && source !== publishedShaderSource && sourceGenerations.containsKey(source) && reload == null)
            return BackendRenderPipeline.Pending.NULL
        return object : BackendRenderPipeline.Pending {
            override fun finishCompile(): BackendRenderPipeline? = synchronized(this@VanillaPipelineCompiler) {
                if (closed || getOrCreateCompiler().generation != expectedGeneration) return@synchronized null
                val compiled = if (reload != null) {
                    if (pipelineReload !== reload || reload.finished) return@synchronized null
                    reloadPairs[pipeline]?.normal ?: return@synchronized null
                } else {
                    val cached = pipelineCache[pipeline]
                    if (pipelineSources.containsKey(pipeline) && source === pipelineSources[pipeline] &&
                        cached?.isValid() == true) cached
                    else compilePipeline(pipeline, source, false, expectedGeneration).also { fresh ->
                        if (fresh.isValid()) {
                            pipelineCache.put(pipeline, fresh)?.let(D3D12PipelineBridge::release)
                            pipelineSources[pipeline] = source
                        }
                    }
                }
                if (!compiled.isValid()) {
                    compiled.close()
                    return@synchronized null
                }
                compiled.setUniformNames(uniformNames)
                check(D3D12PipelineBridge.retain(compiled)) { "Pipeline lost its cache lease" }
                compiled
            }
        }
    }

    @Synchronized
    private fun clearPipelineCache() {
        beginPipelineReload()
        val normal = pipelineCache.values.iterator()
        while (normal.hasNext()) {
            D3D12PipelineBridge.release(normal.next())
            normal.remove()
        }
        val flipped = pipelineCacheFlipY.values.iterator()
        while (flipped.hasNext()) {
            D3D12PipelineBridge.release(flipped.next())
            flipped.remove()
        }
        pipelineSources.clear()
        synchronized(shaderCache) {
            this.shaderCache.values.forEach { s -> s.close() }
            this.shaderCache.clear()
            this.shaderCacheBytes = 0L
        }
        D3D12PipelineBridge.clear()
        publishedShaderSource = null
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            clearPipelineCache()
            closed = true
        }
        pipelineVariants.close()
        pipelinePrewarm.close()
        val programs = publishedFramePrograms
        publishedFramePrograms = null
        programs?.close()
        val compiler = this.glslCompiler
        if (compiler != null) {
            compiler.close()
            this.glslCompiler = null
        }
        D3D12FallbackShaderSource.clearProvider()
    }

    /**
     * 编译一条管线：取 shader（缓存）→ GLSL 编译 → HLSL → 打包 desc → native D3DCompile + root signature。
     * 任何失败都返回 handle=0 的无效管线（isValid()==false）。@param flipY 注入 Y-flip 并关闭背面剔除。
     */
    private fun compilePipeline(pipeline: RenderPipeline,
        shaderSource: ShaderSource?, flipY: Boolean,
        expectedGeneration: Long = this.getOrCreateCompiler().generation): D3D12CompiledRenderPipeline {
        this.getOrCreateCompiler().let { compiler ->
            check(compiler.generation == expectedGeneration) { "Pipeline compilation was revoked by a shader reload" }
        }
        require(pipeline.pushConstantSize() in 0..128 && pipeline.pushConstantSize() % 4 == 0)
        val pipeName = pipeline.location.toString()
        val vertexId = pipeline.shaders[ShaderType.VERTEX] ?: pipeline.location
        val fragmentId = pipeline.shaders[ShaderType.FRAGMENT] ?: pipeline.location
        val vertexShader = this.getOrCompileShader(
            vertexId, ShaderType.VERTEX, pipeline.shaderDefines, shaderSource)
        val fragmentShader = this.getOrCompileShader(
            fragmentId, ShaderType.FRAGMENT, pipeline.shaderDefines, shaderSource)
        if (vertexShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("{} COMPILE FAILED (vertex): shader {} invalid",
                pipeName, vertexId)
            return D3D12CompiledRenderPipeline(pipeline, 0L)
        }
        if (fragmentShader === D3D12IntermediaryShaderModule.INVALID) {
            LOGGER.error("{} COMPILE FAILED (fragment): shader {} invalid",
                pipeName, fragmentId)
            return D3D12CompiledRenderPipeline(pipeline, 0L)
        }
        try {
            val compiled = this.getOrCreateCompiler()
                .compile(pipeline, vertexShader, fragmentShader, flipY)
            this.getOrCreateCompiler().let { compiler ->
                check(compiler.generation == expectedGeneration) { "Pipeline compilation was revoked by a shader reload" }
            }
            val desc = buildNativeDesc(compiled, pipeline, flipY)
            val handle = MCDR_ABI.D3D12CreateGraphicsPipeline(desc, pipeline.pushConstantSize())
            if (handle == 0L) {
                LOGGER.error("{} COMPILE FAILED (native): pipeline creation returned 0",
                    pipeName)
                return D3D12CompiledRenderPipeline(pipeline, 0L)
            }
            this.getOrCreateCompiler().let { compiler ->
                if (compiler.generation != expectedGeneration) {
                    MCDR_ABI.D3D12DestroyPipeline(handle)
                    throw CompilationRevokedException()
                }
            }
            LOGGER.info("{} COMPILE OK (handle={})", pipeName, handle)
            val result = D3D12CompiledRenderPipeline(
                pipeline, handle,
                compiled.entries, compiled.rootConstants,
                cameraReprojectable = !flipY && CameraReprojectionSources.supports(pipeline,
                    vertexShader.source, fragmentShader.source, getOrCreateCompiler().globalDefines),
                oitNormalization = compiled.oitNormalization,
            )
            try {
                pipelineVariants.compile(D3D12PipelineCompilation(
                    result, compiled, vertexShader.source,
                    if (flipY) D3D12PipelineOrientation.FLIPPED_Y else D3D12PipelineOrientation.ORIGINAL,
                    desc,
                    { buildNativeDesc(compiled, pipeline, flipY) },
                    celestialMotionExact = !flipY && CameraReprojectionSources.supportsCelestial(pipeline,
                        vertexShader.source, fragmentShader.source, getOrCreateCompiler().globalDefines),
                ))
                check(getOrCreateCompiler().generation == expectedGeneration && !closed) {
                    "Pipeline compilation was revoked by a shader reload"
                }
            } catch (failure: Throwable) {
                result.close()
                throw failure
            }
            D3D12PipelineBridge.own(result)
            return result
        } catch (e: ShaderCompileException) {
            LOGGER.error("{} COMPILE FAILED (compile): {}", pipeName, e.message)
            return D3D12CompiledRenderPipeline(pipeline, 0L)
        }
    }

    private fun releaseCompiledPipeline(pipeline: D3D12CompiledRenderPipeline) {
        if (pipeline.isValid()) D3D12PipelineBridge.release(pipeline) else pipeline.close()
    }

    private fun getOrCompileShader(id: Identifier, type: ShaderType,
        defines: ShaderDefines, shaderSource: ShaderSource?): D3D12IntermediaryShaderModule {
        val generation = this.getOrCreateCompiler().generation
        val key = ShaderCompilationKey(id, type, defines, generation, shaderSource)
        synchronized(shaderCache) {
            shaderCache[key]?.let { return it }
            val compiled = this.compileShader(key, shaderSource)
            if (compiled === D3D12IntermediaryShaderModule.INVALID) return compiled
            val bytes = compiled.source.length * 2L + compiled.filename.length * 2L + 128L
            while (shaderCache.size >= MAX_INTERMEDIARY_SHADERS ||
                shaderCacheBytes > MAX_INTERMEDIARY_SOURCE_BYTES - bytes) {
                val oldest = shaderCache.entries.iterator()
                if (!oldest.hasNext()) break
                val removed = oldest.next().value
                shaderCacheBytes -= removed.source.length * 2L + removed.filename.length * 2L + 128L
                oldest.remove()
                removed.close()
            }
            if (bytes <= MAX_INTERMEDIARY_SOURCE_BYTES) {
                shaderCache[key] = compiled
                shaderCacheBytes += bytes
            }
            return compiled
        }
    }

    private fun compileShader(key: ShaderCompilationKey,
        shaderSource: ShaderSource?): D3D12IntermediaryShaderModule {
        val source = (if (shaderSource != null) {
            shaderSource.getShader(key.id, key.type)
        } else {
            D3D12FallbackShaderSource.get(key.id, key.type)
        }) ?: if (publishedShaderSource == null && pipelineReload == null)
            D3D12FallbackShaderSource.get(key.id, key.type) else null
        if (source == null) {
            LOGGER.error("Couldn't find source for {} shader ({})", key.type, key.id)
            return D3D12IntermediaryShaderModule.INVALID
        }
        val sourceWithDefines = injectShaderDefines(source, key.defines)
        val includeResolver: ((Identifier) -> String?)? = if (shaderSource != null) {
            { id -> readShaderInclude(shaderSource, id) ?: if (publishedShaderSource == null && pipelineReload == null)
                D3D12FallbackShaderSource.getInclude(id) else null }
        } else {
            D3D12FallbackShaderSource::getInclude
        }
        try {
            return this.getOrCreateCompiler().createIntermediary(
                key.id.toDebugFileName(), sourceWithDefines, key.type, includeResolver)
        } catch (e: ShaderCompileException) {
            LOGGER.error("Couldn't compile {} shader {}: {}", key.type, key.id, e.message)
            return D3D12IntermediaryShaderModule.INVALID
        }
    }

    /**
     * 读一条 `#include`（`shaders/include/` 下）。资源重载的 ShaderSource 是当前事务的快照，必须优先使用它；
     * 快照缺失时返回 null，让本次编译明确失败，而不是拼入旧资源。
     */
    private fun readShaderInclude(shaderSource: ShaderSource, id: Identifier): String? {
        val cached = shaderSource.getInclude(id) ?: return null
        val result = ShadercIncludeResult.create(cached.includeResultPtr())
        val content = MemoryUtil.memGetAddress(result.address() + ShadercIncludeResult.CONTENT)
        return MemoryUtil.memUTF8(content, Math.toIntExact(result.content_length()))
    }

    @Synchronized
    private fun getOrCreateCompiler(): D3D12ShaderCompiler {
        var compiler = this.glslCompiler
        if (compiler == null) {
            compiler = D3D12ShaderCompiler(ShaderDumps)
            this.glslCompiler = compiler
        }
        return compiler
    }
    private fun buildVertexInputElements(pipeline: RenderPipeline,
        vertexShaderInputs: List<D3D12ShaderIo>): MutableList<IntArray> {
        val elements = ArrayList<IntArray>()
        val inputsByName = vertexShaderInputs.associateBy { it.name }
        val bindings = pipeline.vertexFormatBindings
        if (bindings.isEmpty()) {
            LOGGER.warn("{}: formatBindings is empty, inputs={}", pipeline.location, vertexShaderInputs)
        }
        for (i in bindings.indices) {
            val format = bindings[i] ?: continue
            val stride = format.vertexSize
            val stepRate = format.stepRate
            for (element in format.elements) {
                val input = inputsByName[element.name()] ?: continue
                val fmtOrd = element.format().ordinal
                elements.add(intArrayOf(
                    input.location, i, fmtOrd,
                    element.offset(), stride, stepRate
                ))
            }
        }
        if (pipeline.location.toString().contains("terrain")) {
            val slots = bindings.indices.joinToString(", ") { i ->
                val fmt = bindings[i]
                if (fmt == null) "s$i=null" else "s$i(stride=${fmt.vertexSize},step=${fmt.stepRate}," +
                    "elems=${fmt.elements.joinToString("|") { e -> e.name() }})"
            }
            IllusionLogger.debugMessage("${pipeline.location}: inputs=$vertexShaderInputs slots=[$slots]")
        }
        return elements
    }

    /**
     * 构建 `mcdr_dx12CreateGraphicsPipeline` 的 desc。FFM 下用 [MCDRPipelineDesc] 与 native 的
     * `mcdr_pipeline_desc` 共享字段定义，布局漂移会在边界处失败。
     */
    private fun buildNativeDesc(compiled: D3D12CompiledShader,
                                pipeline: RenderPipeline, flipY: Boolean): MCDRPipelineDesc {
        val colorTargets = pipeline.colorTargetStates
        val colorCount = colorTargets.size
        val hasDepth = pipeline.depthStencilState != null
        val inputElements = buildVertexInputElements(pipeline, compiled.vertexInputs)
        val entries = compiled.entries

        val desc = MCDRPipelineDesc()
            .shaders(compiled.vertexHlsl, compiled.fragmentHlsl)

        for (i in 0 until colorCount) {
            val target = colorTargets[i] ?: continue
            desc.addColorTarget(toColorTarget(target))
        }

        val loc = pipeline.location.toString()
        if (hasDepth) {
            var dss = pipeline.depthStencilState!!
            if (flipY && loc.contains("/item_cutout") && dss.depthTest() == CompareOp.NEVER_PASS) {
                dss = DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, dss.writeDepth())
            }
            if (loc.contains("pipeline/crosshair")) {
                dss = DepthStencilState(CompareOp.ALWAYS_PASS, false)
            }
            desc.depth(GpuFormat.D32_FLOAT.ordinal, dss.writeDepth(), dss.depthTest().ordinal)
        }

        var cull = pipeline.isCull
        if (loc.contains("animate_sprite") || flipY) {
            cull = false
        }
        desc.topology(pipeline.primitiveTopology.ordinal, cull,
            pipeline.polygonMode.ordinal)

        for (i in 0 until inputElements.size) {
            val element = inputElements[i]
            desc.addInputElement(
                MCDRPipelineDesc.InputElement(
                element[0],
                element[1],
                element[2],
                element[3],
                element[4],
                element[5],
                "LOC${element[0]}"))
        }

        for (i in 0 until entries.size) {
            val type = when (entries[i].type) {
                D3D12BindGroupEntry.Type.UNIFORM_BUFFER -> 0
                D3D12BindGroupEntry.Type.SAMPLED_IMAGE -> 1
                D3D12BindGroupEntry.Type.TEXEL_BUFFER -> 2
            }
            desc.addBinding(type, i)
        }
        return desc
    }

    /** `null`（withUnusedColorTargetState）= 槽位未使用：format=-1（DXGI_FORMAT_UNKNOWN）、无混合、无写入。 */
    private fun toColorTarget(state: ColorTargetState?): MCDRPipelineDesc.ColorTarget {
        val target = MCDRPipelineDesc.ColorTarget()
        if (state == null) {
            return target.format(-1)
        }
        target.format(state.format().ordinal).writeMask(state.writeMask())
        if (state.blendFunction().isPresent) {
            val blend = state.blendFunction().get()
            target.blend(blend.color().sourceFactor().ordinal, blend.color().destFactor().ordinal,
                blend.color().op().ordinal, blend.alpha().sourceFactor().ordinal,
                blend.alpha().destFactor().ordinal, blend.alpha().op().ordinal)
        }
        return target
    }

    private data class ShaderCompilationKey(
        val id: Identifier,
        val type: ShaderType,
        val defines: ShaderDefines,
        val generation: Long,
        val source: ShaderSource?,
    ) {
        override fun equals(other: Any?): Boolean = other is ShaderCompilationKey &&
            id == other.id && type == other.type && generation == other.generation && source === other.source &&
            defines.toString() == other.defines.toString()

        override fun hashCode(): Int = Objects.hash(id, type, generation, System.identityHashCode(source), defines.toString())

        override fun toString(): String {
            val s = "${this.id.toString()} (${this.type})"
            return if (this.defines.isEmpty) s else "${s} with ${this.defines}"
        }
    }

    private companion object {
        private const val MAX_INTERMEDIARY_SHADERS = 512
        private const val MAX_INTERMEDIARY_SOURCE_BYTES = 32L * 1024 * 1024
        private val LOGGER: Logger = LoggerFactory.getLogger("dxrmc")
    }
}
