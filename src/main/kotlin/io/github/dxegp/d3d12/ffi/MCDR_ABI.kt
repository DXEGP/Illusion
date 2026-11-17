@file:Suppress("unused", "UNCHECKED_CAST", "FunctionName", "LocalVariableName", "RedundantVisibilityModifier", "MemberVisibilityCanBePrivate", "KotlinRedundantDiagnosticSuppress", "USELESS_CAST", "SENSELESS_COMPARISON")

package io.github.dxegp.d3d12.ffi

import io.github.dxegp.diagnostics.IllusionLogger
import io.github.dxegp.d3d12.command.D3D12FenceCallback
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class FrameProgramsFailure(internal val resultCode: Int?) {
    INVALID_REQUEST(0),
    COMPILATION_FAILED(-1),
    BUSY(-2),
    STALE(-3),
    INVALID_CONTEXT(-4),
    SUBMISSION_UNKNOWN(-5),
    ABI_CONTRACT(null),
}

class FrameProgramsException(
    val failure: FrameProgramsFailure,
    val operation: String,
    val nativeResult: Int?,
    detail: String,
) : IllegalStateException("Frame programs $operation failed: $failure ($nativeResult): $detail")

internal data class OitShaderPreludeV1(val abi: Int, val hash: Long, val source: String) {
    companion object {
        const val SUPPORTED_SHADER_ABI = 5
    }
}

internal enum class DisplaySubmissionState { NONE, QUEUED, SUBMITTED, FAILED, UNKNOWN }
internal enum class DisplayPresentOutcome { PENDING, SUCCEEDED, OCCLUDED, MODE_CHANGED, FAILED, NOT_CALLED, CANCELLED, UNKNOWN }
internal enum class DisplayPresentRetirement { PENDING, SIGNALLED, NOT_REQUIRED, UNKNOWN }

internal data class DisplayPresentReceipt(
    val serial: Long,
    val worldEpoch: Long,
    val realFrameId: Long,
    val configVersion: Long,
    val resourceVersion: Long,
    val providerGeneration: Long,
    val historyGeneration: Long,
    val commandFenceValue: Long,
    val submitQueueFenceValue: Long,
    val presentQueueFenceValue: Long,
    val submission: DisplaySubmissionState,
    val outcome: DisplayPresentOutcome,
    val dxgiStatus: Int,
    val flags: Int,
    val outputFrameCount: Int,
    val retirement: DisplayPresentRetirement,
) {
    val oitHistoryPublished: Boolean get() = flags and 1 != 0
    val dxgiStatusValid: Boolean get() = flags and 4 != 0
}

internal sealed interface DisplayFrameSettlement {
    data object Empty : DisplayFrameSettlement
    data class Completed(val receipt: DisplayPresentReceipt) : DisplayFrameSettlement
    data class TimedOut(val receipt: DisplayPresentReceipt) : DisplayFrameSettlement
    data class SubmissionUnknown(val receipt: DisplayPresentReceipt) : DisplayFrameSettlement
}

/**
 * Java FFM binding for MCDR; see docs/misc/ffm-abi.md (contract, arena rules, failure modes).
 * Handles are plain `long`, 0 = missing; every handle is linked once and must be invoked with a
 * descriptor that matches its native prototype exactly (see [MethodHandle.invokeExact]).
 */
object MCDR_ABI {
    fun D3D12ConvertShader(request: String): String = NagaLibrary.convert(request)

    /** [logging] logLevel gates every per-frame diagnostic print. */
    val LOG_VERBOSE: Boolean
        get() = IllusionLogger.debugEnabled()
    private const val LIB_NAME = "MCDR.dll"
    /** Owns the loaded library and every upcall stub; lives for the process. */
    private val ARENA: Arena = Arena.ofShared()
    private val LINKER: Linker = Linker.nativeLinker()
    private var LIB: SymbolLookup? = null
    private var LOAD_FAILURE: Throwable? = null
    // Method handles are nullable so a failed load leaves them null instead of throwing
    // ExceptionInInitializerError — requireLoaded() turns that into a readable message.
    private var MH_abiversion: MethodHandle? = null
    private var MH_abilayout: MethodHandle? = null
    private var MH_lasterror: MethodHandle? = null
    private var MH_setlogverbose: MethodHandle? = null
    private var MH_setloglevel: MethodHandle? = null
    private var MH_setshutdowntrace: MethodHandle? = null
    private var MH_setlogfile: MethodHandle? = null
    private var MH_setenvironment: MethodHandle? = null
    private var MH_setpixcapture: MethodHandle? = null
    private var MH_setpixdirectory: MethodHandle? = null
    private var MH_startpixtiming: MethodHandle? = null
    private var MH_stoppixtiming: MethodHandle? = null
    private var MH_setshadingrate: MethodHandle? = null
    private var MH_setantialiasing: MethodHandle? = null
    private var MH_querydlss: MethodHandle? = null
    /** The 8 SR entry points; all skipOptional. */
    private var MH_srpick: MethodHandle? = null
    private var MH_srquerycaps: MethodHandle? = null
    private var MH_srcreate: MethodHandle? = null
    private var MH_srresize: MethodHandle? = null
    private var MH_srprepare: MethodHandle? = null
    private var MH_srcommit: MethodHandle? = null
    private var MH_srabort: MethodHandle? = null
    private var MH_srjitter: MethodHandle? = null
    private var MH_srdestroy: MethodHandle? = null
    private var MH_srjitterphase: MethodHandle? = null
    private var MH_srquery: MethodHandle? = null
    /** The 5 FG entry points; all skipOptional. */
    private var MH_fgpick: MethodHandle? = null
    private var MH_fgquerycaps: MethodHandle? = null
    private var MH_fgsetmode: MethodHandle? = null
    private var MH_fgprepare: MethodHandle? = null
    private var MH_fgabort: MethodHandle? = null
    private var MH_commitpreparedconfiguration: MethodHandle? = null
    private var MH_fgpresented: MethodHandle? = null
    private var MH_fgstatus: MethodHandle? = null
    private var MH_fgstate: MethodHandle? = null
    private var MH_fgsetuitexture: MethodHandle? = null
    // hudless present: the world layer carries no UI, the SDK composites it.
    private var MH_fgsetuicomposition: MethodHandle? = null
    private var MH_fguicompositionready: MethodHandle? = null
    /** Scope shading rate (see [D3D12SetShadingRateNow]). */
    private var MH_setshadingratenow: MethodHandle? = null
    private var MH_setshadingratecombiners: MethodHandle? = null
    /** DLSS switch, independent of the AA mode. */
    private var MH_setdlssenabled: MethodHandle? = null
    /** Snapshot the world depth into a native-private D32 texture. */
    private var MH_snapshotdlssdepth: MethodHandle? = null
    private var MH_setdlssquality: MethodHandle? = null
    private var MH_setdlssrenderpreset: MethodHandle? = null
    private var MH_setdlssoutput: MethodHandle? = null
    private var MH_reflexframebegin: MethodHandle? = null
    private var MH_reflexmarker: MethodHandle? = null
    private var MH_meshsupport: MethodHandle? = null
    /** Reflex mode + availability. */
    private var MH_setreflexmode: MethodHandle? = null
    private var MH_setdlssgmode: MethodHandle? = null
    private var MH_getdlssgpresented: MethodHandle? = null
    private var MH_setreflexframelimit: MethodHandle? = null
    private var MH_sethdrmode: MethodHandle? = null
    private var MH_setraytracingmode: MethodHandle? = null
    private var MH_setswapchainoptions: MethodHandle? = null
    private var MH_gethdrappliedmode: MethodHandle? = null
    private var MH_reflexavailable: MethodHandle? = null
    private var MH_dlssavailable: MethodHandle? = null
    private var MH_createdevice: MethodHandle? = null
    private var MH_createtexture: MethodHandle? = null
    private var MH_createbuffer: MethodHandle? = null
    private var MH_createsampler: MethodHandle? = null
    /** Terrain sampler with mip bias; falls back to [MH_createsampler] when absent. */
    private var MH_createsamplerbias: MethodHandle? = null
    private var MH_createtextureview: MethodHandle? = null
    private var MH_destroyresource: MethodHandle? = null
    private var MH_mapbuffer: MethodHandle? = null
    private var MH_unmapbuffer: MethodHandle? = null
    private var MH_enumerateadapters: MethodHandle? = null
    private var MH_getadapterinfo: MethodHandle? = null
    private var MH_getqueuehandle: MethodHandle? = null
    private var MH_getdevicehandle: MethodHandle? = null
    private var MH_destroydevice: MethodHandle? = null
    private var MH_createhiddenwindow: MethodHandle? = null
    private var MH_destroyhiddenwindow: MethodHandle? = null
    private var MH_gettimestampfrequency: MethodHandle? = null
    private var MH_createcommandencoder: MethodHandle? = null
    private var MH_destroycommandencoder: MethodHandle? = null
    private var MH_begincommandlist: MethodHandle? = null
    private var MH_begincommandlistwithwait: MethodHandle? = null
    private var MH_endcommandlist: MethodHandle? = null
    private var MH_submit: MethodHandle? = null
    private var MH_submitv1: MethodHandle? = null
    private var MH_commandrecordingstatusv1: MethodHandle? = null
    private var MH_settlecommandsubmissionv1: MethodHandle? = null
    private var MH_destroycommandencodercheckedv1: MethodHandle? = null
    private var MH_waitforfence: MethodHandle? = null
    private var MH_getfencevalue: MethodHandle? = null
    private var MH_gettimestampnow: MethodHandle? = null
    private var MH_islistopen: MethodHandle? = null
    private var MH_copybuffer: MethodHandle? = null
    private var MH_copybufferchecked: MethodHandle? = null
    private var MH_writetotexture: MethodHandle? = null
    private var MH_copybuffertotexture: MethodHandle? = null
    private var MH_copytexturetobuffer: MethodHandle? = null
    private var MH_copytexturetotexture: MethodHandle? = null
    private var MH_clearcolortexture: MethodHandle? = null
    private var MH_cleardepthtexture: MethodHandle? = null
    private var MH_clearcolortextureregion: MethodHandle? = null
    private var MH_cleardepthtextureregion: MethodHandle? = null
    private var MH_beginrenderpass: MethodHandle? = null
    private var MH_endrenderpass: MethodHandle? = null
    private var MH_getactivecolortexture: MethodHandle? = null
    private var MH_createquerypool: MethodHandle? = null
    private var MH_destroyquerypool: MethodHandle? = null
    private var MH_writetimestamp: MethodHandle? = null
    private var MH_readqueryvalue: MethodHandle? = null
    private var MH_readqueryvalues: MethodHandle? = null
    private var MH_initasyncrenderer: MethodHandle? = null
    private var MH_destroyasyncrenderer: MethodHandle? = null
    private var MH_asyncrenderbeginframe: MethodHandle? = null
    private var MH_asyncrenderwaitcomplete: MethodHandle? = null
    private var MH_asyncrenderisrecordingready: MethodHandle? = null
    private var MH_asyncrenderwaitrecordingready: MethodHandle? = null
    private var MH_asyncsendcommandsready: MethodHandle? = null
    private var MH_clearasyncrenderctx: MethodHandle? = null
    // World-layer in-place SMAA (three passes + copy back).
    private var MH_applysmaatotexture: MethodHandle? = null
    private var MH_blittexturetotexture: MethodHandle? = null
    private var MH_sharpentexturetotexture: MethodHandle? = null
    // TAA resolve, held entirely by native.
    private var MH_creategraphicspipeline: MethodHandle? = null
    private var MH_creategraphicsconstants: MethodHandle? = null
    private var MH_createoitnormalizationpipelinev1: MethodHandle? = null
    private var MH_oitshaderprelude: MethodHandle? = null
    private var MH_creategraphicscapture: MethodHandle? = null
    private var MH_createmeshcaptureroot: MethodHandle? = null
    private var MH_createmeshcapture: MethodHandle? = null
    private var MH_createterrantileroot: MethodHandle? = null
    private var MH_createterrantilroute: MethodHandle? = null
    private var MH_bindterrantileroute: MethodHandle? = null
    private var MH_bindterraintilepipeline: MethodHandle? = null
    private var MH_bindterraintilestagev2: MethodHandle? = null
    private var MH_destroyterraintileroute: MethodHandle? = null
    private var MH_oitbegin: MethodHandle? = null
    private var MH_oitbeginv2: MethodHandle? = null
    private var MH_oitmetadata: MethodHandle? = null
    private var MH_oitterrainmetadata: MethodHandle? = null
    private var MH_oitbind: MethodHandle? = null
    private var MH_oitterrainbind: MethodHandle? = null
    private var MH_oitterrainpreparetiles: MethodHandle? = null
    private var MH_oitterrainpreparetilesv3: MethodHandle? = null
    private var MH_oitunbind: MethodHandle? = null
    private var MH_oitseal: MethodHandle? = null
    private var MH_oitresolve: MethodHandle? = null
    private var MH_oitresolvev4: MethodHandle? = null
    private var MH_oitabort: MethodHandle? = null
    private var MH_oitexecutionbegin: MethodHandle? = null
    private var MH_oitexecutionquery: MethodHandle? = null
    private var MH_oitreceiptquery: MethodHandle? = null
    private var MH_oitreceiptfrontier: MethodHandle? = null
    private var MH_oitexecutionappend: MethodHandle? = null
    private var MH_oitexecutionseal: MethodHandle? = null
    private var MH_oitexecutionbeginv2: MethodHandle? = null
    private var MH_oitexecutiontexturev1: MethodHandle? = null
    private var MH_oitexecutionappendv2: MethodHandle? = null
    private var MH_oitexecutionoperationappendv1: MethodHandle? = null
    private var MH_oitexecutionqueryv2: MethodHandle? = null
    private var MH_oitexecutionterrainreceiptv2: MethodHandle? = null
    private var MH_oitexecutionsealv2: MethodHandle? = null
    private var MH_oitexecutionabortv2: MethodHandle? = null
    private var MH_oitoperationbeginv1: MethodHandle? = null
    private var MH_oitoperationfinishv1: MethodHandle? = null
    private var MH_oitoperationcancelv1: MethodHandle? = null
    private var MH_oitoperationreceiptv1: MethodHandle? = null
    private var MH_oitsampledcontentv1: MethodHandle? = null
    private var MH_oitterrainstagebeginv2: MethodHandle? = null
    private var MH_oitterrainstagefinishv2: MethodHandle? = null
    private var MH_oitterrainstageabortv2: MethodHandle? = null
    private var MH_oitterrainstagereceiptv2: MethodHandle? = null
    private var MH_oitterrainreadsetregisterv2: MethodHandle? = null
    private var MH_oitterrainreadsetcancelv2: MethodHandle? = null
    private var MH_pushgraphicsconstants: MethodHandle? = null
    private var MH_ensuregraphicsstate: MethodHandle? = null
    private var MH_createmeshconstants: MethodHandle? = null
    private var MH_createmeshrootconstants: MethodHandle? = null
    private var MH_meshrootsigconstants: MethodHandle? = null
    private var MH_destroypipeline: MethodHandle? = null
    // Mesh shaders: DXC compile + AS/MS pipeline + DispatchMesh.
    private var MH_compileshaderdxc: MethodHandle? = null
    private var MH_createworkgraph: MethodHandle? = null
    private var MH_vanillabegin: MethodHandle? = null
    private var MH_vanillaworld: MethodHandle? = null
    private var MH_vanillagui: MethodHandle? = null
    private var MH_vanillapublish: MethodHandle? = null
    private var MH_vanillaevaluate: MethodHandle? = null
    private var MH_vanillaworldoutput: MethodHandle? = null
    private var MH_begindisplay: MethodHandle? = null
    private var MH_settledisplay: MethodHandle? = null
    private var MH_abortdisplay: MethodHandle? = null
    private var MH_preparedisplay: MethodHandle? = null
    private var MH_submitdisplay: MethodHandle? = null
    private var MH_prewarmdisplay: MethodHandle? = null
    private var MH_vanillareset: MethodHandle? = null
    private var MH_vanillabegindeclared: MethodHandle? = null
    private var MH_vanillabeginraster: MethodHandle? = null
    private var MH_vanillafinishdeclared: MethodHandle? = null
    private var MH_invalidatereconstructioninputs: MethodHandle? = null
    private var MH_prewarmframeprep: MethodHandle? = null
    private var MH_prepareframeprograms: MethodHandle? = null
    private var MH_commitframeprograms: MethodHandle? = null
    private var MH_abortframeprograms: MethodHandle? = null
    private var MH_producereactivemask: MethodHandle? = null
    private var MH_produceprovidermasks: MethodHandle? = null
    private var MH_produceprovidermasksv2: MethodHandle? = null
    private var MH_producevrs: MethodHandle? = null
    private var MH_produceexposure: MethodHandle? = null
    private var MH_resetframeprep: MethodHandle? = null
    private var MH_createterrainworkgraph: MethodHandle? = null
    private var MH_destroyterrainworkgraph: MethodHandle? = null
    private var MH_prepareterrainworkgraph: MethodHandle? = null
    private var MH_drawterrainworkgraph: MethodHandle? = null
    private var MH_destroyterrainbatch: MethodHandle? = null
    private var MH_destroyworkgraph: MethodHandle? = null
    private var MH_dispatchworkgraph: MethodHandle? = null
    private var MH_multiDrawIndexedPacked: MethodHandle? = null
    private var MH_multiDrawIndexedSeparate: MethodHandle? = null
    private var MH_multiDrawPacked: MethodHandle? = null
    private var MH_multiDrawSeparate: MethodHandle? = null
    private var MH_multiDrawBound: MethodHandle? = null
    private var MH_createmeshpipeline: MethodHandle? = null
    private var MH_createmeshpipelineroot: MethodHandle? = null
    private var MH_meshrootsig: MethodHandle? = null
    private var MH_meshrootsigrelease: MethodHandle? = null
    private var MH_dispatchmesh: MethodHandle? = null
    private var MH_meshdrawmeshlets: MethodHandle? = null

    // Compute minimum path (prerequisite for the shading-rate image / NRC).
    private var MH_createcomputepipeline: MethodHandle? = null
    private var MH_createterrantilebinningpipeline: MethodHandle? = null
    private var MH_dispatchcompute: MethodHandle? = null
    private var MH_createcomputepipelineshadingrate: MethodHandle? = null
    private var MH_createshadingrateimage: MethodHandle? = null
    private var MH_dispatchcomputeshadingrate: MethodHandle? = null
    private var MH_createtexture3: MethodHandle? = null
    private var MH_dispatchtexture3: MethodHandle? = null
    private var MH_setshadingrateimage: MethodHandle? = null
    private var MH_createsurface: MethodHandle? = null
    private var MH_surfacepresentmodes: MethodHandle? = null
    private var MH_setexclusivefullscreen: MethodHandle? = null
    private var MH_configuresurface: MethodHandle? = null
    private var MH_acquiresurface: MethodHandle? = null
    private var MH_blitsurface: MethodHandle? = null
    private var MH_presentsurface: MethodHandle? = null
    private var MH_getbackbufferhandle: MethodHandle? = null
    private var MH_issurfacesuboptimal: MethodHandle? = null
    private var MH_readbacktexturepixels: MethodHandle? = null
    /** Readback with a known starting state (a GPU-written texture must use this). */
    private var MH_readbacktexturepixelsin: MethodHandle? = null
    /** State the DLSS output stopped in last frame (0 = unknown). */
    private var MH_dlssoutputstate: MethodHandle? = null
    private var MH_destroysurface: MethodHandle? = null
    private var MH_setpipeline: MethodHandle? = null
    private var MH_setrastermotiontarget: MethodHandle? = null
    private var MH_setadditionalcolortarget: MethodHandle? = null
    private var MH_setscissor: MethodHandle? = null
    /** 1 = set, 0 = failed. */
    private var MH_setviewport: MethodHandle? = null
    private var MH_setvertexbuffer: MethodHandle? = null
    /** 1 = cleared, 0 = failed. */
    private var MH_clearstenciltexture: MethodHandle? = null
    private var MH_setindexbuffer: MethodHandle? = null
    private var MH_pushdescriptors: MethodHandle? = null
    private var MH_drawindexed: MethodHandle? = null
    private var MH_draw: MethodHandle? = null
    private var MH_drawindexedindirect: MethodHandle? = null
    private var MH_drawindirect: MethodHandle? = null
    private var MH_asyncfencecreate: MethodHandle? = null
    private var MH_asyncfencedestroy: MethodHandle? = null
    private var MH_asyncfencesignal: MethodHandle? = null
    private var MH_asyncfencewait: MethodHandle? = null
    private var MH_asyncfenceiscomplete: MethodHandle? = null
    private var MH_asyncfenceregistercallback: MethodHandle? = null
    /** The only upcall stub: native calls it from its own worker thread, so it must be static and live for the process — a collected stub is a dangling function pointer, and an exception escaping it terminates the JVM. */
    private var STUB_TARGET: MethodHandle? = null
    private var FENCE_STUB: MemorySegment? = null
    init {
        var lib: SymbolLookup? = null
        var failure: Throwable? = null
        try {
            val dllPath = NativeLibraryResources.extractLibrary()
            lib = SymbolLookup.libraryLookup(dllPath, ARENA)
        } catch (t: Throwable) {
            failure = t
        }
        val loaded = lib
        if (loaded == null) {
            LIB = null
            LOAD_FAILURE = failure
        } else {
            LIB = loaded
            var bindFailure: Throwable? = null
            try {
                bindAll()
                verifyABI()
            } catch (t: Throwable) {
                bindFailure = t
            }
            LOAD_FAILURE = bindFailure
            if (bindFailure == null) {
                IllusionLogger.infoMessage(
                    "MCDR native library loaded (FFM): ${LIB_NAME}"
                            + ", ABI v${safeABIVersion()}"
                )
                IllusionLogger.infoMessage("struct layouts: ${MCDRLayout.describeSizes()}")
                IllusionLogger.infoMessage(
                    "startup log level ${IllusionLogger.level()}"
                            + " (before the config loads; the effective level comes from [logging] logLevel in config/dxr_mc-client.toml"
                            + " and is printed again once it does; for development DX12_LOG_VERBOSE=1 forces DEBUG)"
                )
            }
        }
    }
    // ---- Loading ----
    private fun safeABIVersion(): Int {
        return try {
            MH_abiversion!!.invokeExact() as Int
        } catch (t: Throwable) {
            -1
        }
    }
    private fun requireLoaded() {
        if (LOAD_FAILURE != null) {
            throw IllegalStateException("MCDR native library is unavailable: ${LOAD_FAILURE}", LOAD_FAILURE)
        }
    }
    fun isAvailable(): Boolean {
        return LOAD_FAILURE == null
    }
    /** Why the library is unavailable, or null when it is fine. */
    fun unavailableReason(): String? {
        return LOAD_FAILURE?.toString()
    }
    private fun skip(name: String, desc: FunctionDescriptor): MethodHandle {
        val addr = LIB!!.find(name).orElseThrow {
            IllegalStateException("MCDR export not found: ${name}")
        }
        return LINKER.downcallHandle(addr, desc)
    }
    /** Like [skip], but a missing export yields null instead of failing the whole bind (stale DLL = one feature degrades). */
    private fun skipOptional(name: String, desc: FunctionDescriptor): MethodHandle? {
        return try {
            val addr = LIB!!.find(name).orElse(null) ?: return null
            LINKER.downcallHandle(addr, desc)
        } catch (t: Throwable) {
            null
        }
    }
    // MSVC `long` is 32-bit: C `long` -> JAVA_INT / MCDRLayout.I32, never I64, and every
    // invokeExact argument must sit at exactly the static type its descriptor declares.
    private fun fd(ret: MemoryLayout, vararg args: MemoryLayout): FunctionDescriptor {
        return FunctionDescriptor.of(ret, *args)
    }
    private fun fdv(vararg args: MemoryLayout): FunctionDescriptor {
        return FunctionDescriptor.ofVoid(*args)
    }
    /** Cross-check every mirrored struct offset/size against the running DLL. */
    private fun verifyABI() {
        val version = MH_abiversion!!.invokeExact() as Int
        if (version != MCDR_ABI_VERSION) {
            throw IllegalStateException("MCDR ABI version mismatch: java=${MCDR_ABI_VERSION}, native=${version}")
        }
        Arena.ofConfined().use { arena ->
            val pFields = arena.allocate(MCDRLayout.PTR)
            val pCount = arena.allocate(MCDRLayout.I32)
            MH_abilayout!!.invokeExact(pFields, pCount)
            val count = pCount.get(MCDRLayout.I32, 0)
            val first = pFields.get(MCDRLayout.PTR, 0) as MemorySegment
            if (count <= 0 || first.address() == 0L) {
                throw IllegalStateException("mcdrABILayout() reported ${count} fields")
            }
            val fields = first.reinterpret(Math.multiplyExact(MCDRLayout.ABI_FIELD.byteSize(), count.toLong()))
            val nativeFields = mutableMapOf<String, LongArray>()
            val stride = MCDRLayout.ABI_FIELD.byteSize()
            for (i in 0 until count) {
                val f = fields.asSlice(i * stride, stride)
                val nameSeg = f.get(MCDRLayout.PTR, 0) as MemorySegment
                val name = nameSeg.reinterpret(Long.MAX_VALUE).getString(0)
                nativeFields[name] = longArrayOf(f.get(MCDRLayout.I64, 8) as Long, f.get(MCDRLayout.I64, 16) as Long)
            }
            MCDRLayout.verify(nativeFields)
        }
    }
    private fun unexpected(t: Throwable): RuntimeException {
        return IllegalStateException("MCDR native call failed: ${t}", t)
    }
    private fun ptr(handle: Long): MemorySegment {
        return MemorySegment.ofAddress(handle)
    }
    private fun addr(seg: MemorySegment?): Long {
        return if (seg == null) 0L else seg.address()
    }
    /**
     * `MCDR_ABI_VERSION` from `mcdr_abi.h`; 2->3 added the SR tables, 3->4 the FG tables, and
     * 14->15 added transactional frame-program publication (all append-only).
     * This constant, the Kotlin mirrors and the native `layoutTable()` must agree — [verifyABI]
     * aborts before device creation otherwise.
     */
    const val MCDR_ABI_VERSION: Int = 26
    internal const val OIT_RESOLVE_UNRECORDED_COVERAGE_UNAVAILABLE: Int = 1
    /** `MCDR_FG_BACKEND_*`, value for value with `mcdr_abi.h`. */
    const val FG_BACKEND_NONE: Int = 0
    const val FG_BACKEND_DLSSG: Int = 1
    const val FG_BACKEND_FSRFG: Int = 2
    const val FG_BACKEND_XESSFG: Int = 3
    /** `MCDR_FG_CHAIN_*`; v1 only reaches the first two. */
    const val FG_CHAIN_APP_NATIVE: Int = 0
    const val FG_CHAIN_SL_PROXY: Int = 1
    const val FG_CHAIN_FSR_WRAP: Int = 2
    const val FG_CHAIN_XESS_WRAP: Int = 3
    /**
     * `MCDR_FG_LATENCY_*`: what this backend is *architecturally* paired with, not runtime
     * availability (read `available` / `note` for that).
     */
    const val FG_LATENCY_NONE: Int = 0
    const val FG_LATENCY_REFLEX: Int = 1
    const val FG_LATENCY_ANTILAG2: Int = 2
    const val FG_LATENCY_XELL: Int = 3
    /** `MCDR_SR_BACKEND_*`, value for value with `mcdr_abi.h`. */
    const val SR_BACKEND_NONE: Int = 0
    const val SR_BACKEND_DLSS: Int = 1
    const val SR_BACKEND_FSR: Int = 2
    const val SR_BACKEND_XESS: Int = 3
    /** `MCDR_SR_MASK_*`. */
    const val SR_MASK_REACTIVE: Int = 1
    const val SR_MASK_RESPONSIVE: Int = 2
    const val SR_MASK_TRANSPARENCY_AND_COMPOSITION: Int = 4
    /** A native `int32_t` bool as a Kotlin `Boolean`. */
    private fun ok(value: Int): Boolean {
        return value != 0
    }
    private val SCRATCH: ThreadLocal<FfmScratch> = ThreadLocal.withInitial { FfmScratch() }

    private inline fun <Result> withScratch(
        maximumBytes: Long,
        maximumAlignment: Long = 16L,
        crossinline action: (Arena?) -> Result,
    ): Result = SCRATCH.get().withScope(maximumBytes, maximumAlignment, action)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun scratch(index: Int, bytes: Long, temporary: Arena?, alignment: Long = 16L): MemorySegment =
        SCRATCH.get().allocate(index, bytes, alignment, temporary)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun checkedArrayBytes(count: Int, element: MemoryLayout): Long {
        require(count >= 0)
        return Math.multiplyExact(count.toLong(), element.byteSize())
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Arena.allocateBindingPairs(bindings: List<Pair<Int, Int>>): MemorySegment {
        require(bindings.size <= MCDRPipelineDesc.MAX_BINDINGS)
        if (bindings.isEmpty()) return MemorySegment.NULL
        val pairBytes = 2
        val target = allocateFfmArray(Math.multiplyExact(bindings.size, pairBytes), MCDRLayout.I8)
        bindings.forEachIndexed { index, [type, register] ->
            require(type in 0..2 && register in 0..255)
            val base = index.toLong() * pairBytes
            target.set(MCDRLayout.I8, base, type.toByte())
            target.set(MCDRLayout.I8, base + 1L, register.toByte())
        }
        return target
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun scratchArray(index: Int, count: Int, element: MemoryLayout,
                                    temporary: Arena?, emptyPolicy: FfmEmptyAllocation = FfmEmptyAllocation.NULL): MemorySegment {
        val bytes = checkedArrayBytes(count, element)
        if (count == 0 && emptyPolicy == FfmEmptyAllocation.NULL) return MemorySegment.NULL
        return scratch(index, bytes.coerceAtLeast(1L), temporary, element.byteAlignment())
    }

    private inline fun <Result> withComputeConstants(constants: IntArray,
        crossinline dispatch: (MemorySegment, Int) -> Result): Result {
        val count = constants.size
        require(count in 0..8) { "Compute constants require 0..8 dwords, got $count" }
        return withScratch(checkedArrayBytes(count, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            val encoded = scratchArray(0, count, MCDRLayout.I32, temporary)
            if (count != 0) MemorySegment.copy(constants, 0, encoded, MCDRLayout.I32, 0L, count)
            dispatch(encoded, count)
        }
    }
    // ---- Meta ----
    /** Last error this thread recorded; "" when none. */
    fun D3D12LastError(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_lasterror!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return ""
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_lasterror!!.invokeExact(buf, need) as Int
            return if (written > 0) buf.getString(0) else ""
        }
    }
    /** Route native log output through `DEBUG` (1) or `verbose` (0). */
    fun D3D12SetLogVerbose(enabled: Boolean) {
        if (LOAD_FAILURE != null) {
            return
        }
        try {
            MH_setlogverbose!!.invokeExact(if (enabled) 1 else 0)
        } catch (ignored: Throwable) {
        // logging configuration must never fail engine start-up
        }
    }
    /** 0=error 1=warning 2=info 3=debug; a DLL predating `mcdrSetLogLevel` falls back to [D3D12SetLogVerbose]. Must run before the device is created. */
    fun D3D12SetLogLevel(level: Int) {
        if (LOAD_FAILURE != null) {
            return
        }
        val clamped = level.coerceIn(0, 3)
        try {
            val handle = MH_setloglevel
            if (handle != null) {
                handle.invokeExact(clamped)
            } else {
                D3D12SetLogVerbose(clamped >= 2)
            }
        } catch (ignored: Throwable) {
        // logging configuration must never fail engine start-up
        }
    }
    /** Push/clear one env var — only for native `getenv()` read points that already exist, never as a new switch (see AGENTS.md). */
    fun D3D12SetEnvironment(name: String, value: String?) {
        if (LOAD_FAILURE != null) {
            return
        }
        val handle = MH_setenvironment ?: return
        try {
            Arena.ofConfined().use { arena ->
                val key = cstringUtf8(arena, name)
                val valSeg = if (value == null) MemorySegment.NULL else cstringUtf8(arena, value)
                handle.invokeExact(key, valSeg)
            }
        } catch (ignored: Throwable) {
        // configuration propagation must never fail start-up
        }
    }
    /**
     * Whether shutdown-path progress markers bypass logLevel; config `[logging] shutdownTrace`.
     * A DLL without this export keeps the built-in always-on behaviour.
     */
    fun D3D12SetShutdownTraceAlways(always: Boolean) {
        if (LOAD_FAILURE != null) {
            return
        }
        val handle = MH_setshutdowntrace ?: return
        try {
            handle.invokeExact(if (always) 1 else 0)
        } catch (ignored: Throwable) {
        // logging configuration must never fail engine start-up
        }
    }

    /**
     * Mirror every native line into `path` (UTF-8, appended, relative = game dir); "" disables it.
     * Config `[logging] nativeLogFile` — launchers like Prism only persist log4j, not the child's stderr.
     * @return false when the loaded DLL has no such export.
     */
    fun D3D12SetLogFile(path: String): Boolean {
        if (LOAD_FAILURE != null) {
            return false
        }
        val handle = MH_setlogfile ?: return false
        return try {
            Arena.ofConfined().use { arena ->
                val status = handle.invokeExact(cstringUtf8(arena, path)) as Int
                status != 0
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetLogFile failed: $t")
            false
        }
    }

    /** Load WinPixGpuCapturer.dll — **before the first D3D12 device**. 1/0/-1, [Int.MIN_VALUE] = no export; never throws. */
    fun D3D12SetPIXCapture(enabled: Boolean): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setpixcapture ?: return Int.MIN_VALUE
        return try {
            val status = handle.invokeExact(if (enabled) 1 else 0) as Int
            status
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetPixCapture failed: $t")
            Int.MIN_VALUE
        }
    }
    /** PIX DLL path or directory; call it before [D3D12SetPIXCapture]. "" restores auto-detection. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetPIXDirectory(path: String): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setpixdirectory ?: return Int.MIN_VALUE
        return try {
            Arena.ofConfined().use { arena ->
                val status = handle.invokeExact(cstringUtf8(arena, path)) as Int
                status
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetPixDirectory failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Start a PIX timing capture written to `path` (.wpix) — only PIXBeginCapture puts a file where the caller asks. 1/0/negative HRESULT/[Int.MIN_VALUE]; waits on a native thread, never on the render thread. */
    fun D3D12StartPIXTimingCapture(path: String): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_startpixtiming ?: return Int.MIN_VALUE
        return try {
            Arena.ofConfined().use { arena ->
                val status = handle.invokeExact(cstringUtf8(arena, path)) as Int
                status
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("mcdrStartPixTimingCapture failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Stop the timing capture and return without waiting (PIX spools on its own thread). 1/0/negative HRESULT/[Int.MIN_VALUE]. */
    fun D3D12StopPIXTimingCapture(): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_stoppixtiming ?: return Int.MIN_VALUE
        return try {
            val status = handle.invokeExact() as Int
            status
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("mcdrStopPixTimingCapture failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Random-access shading rate: raw `D3D12_SHADING_RATE` (1X1=0x0 1X2=0x1 2X1=0x4 2X2=0x5 2X4=0x6 4X2=0x9 4X4=0xA), -1 = issue nothing. Re-issued on every list start. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetShadingRate(rate: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setshadingrate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(rate) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetShadingRate failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Issue one `RSSetShadingRate` on ctx's open list right now — the per-draw ceiling, not the
     * stored one. -1 is **not** accepted here: the caller simply does not call it.
     *  1 = issued, 0 = device cannot honour the rate (or the list is not a GraphicsCommandList5),
     *  -1 = unknown rate, -2 = **no open command list**, [Int.MIN_VALUE] = no export. Never throws.
     */
    fun D3D12SetShadingRateNow(ctx: Long, rate: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setshadingratenow ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), rate) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetShadingRateNow failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Both `D3D12_SHADING_RATE_COMBINER` values (PASSTHROUGH=0 OVERRIDE=1 MIN=2 MAX=3 SUM=4).
     * Stay {PASSTHROUGH, PASSTHROUGH} until SV_ShadingRate is actually written: under MIN a
     * per-primitive 1X1 would undo every 2X2.
     *  1 = stored, -1 = value outside 0..4, [Int.MIN_VALUE] = no export.
     */
    fun D3D12SetShadingRateCombiners(first: Int, second: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setshadingratecombiners ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(first, second) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetShadingRateCombiners failed: $t")
            Int.MIN_VALUE
        }
    }
    /** 0=OFF 1=SMAA 2=TAA 3=DLAA (same numbering as the video option); 4 is **not** legal any more. No restart. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetAntiAliasing(mode: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setantialiasing ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(mode) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetAntiAliasing failed: $t")
            Int.MIN_VALUE
        }
    }
    /** DLSS switch, decoupled from the AA mode. 1/0/-1/[Int.MIN_VALUE] (an old DLL still gates on `aaMode == 4`). Never throws. */
    fun D3D12SetDlssEnabled(enabled: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssenabled ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(enabled) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssEnabled failed: $t")
            Int.MIN_VALUE
        }
    }
    /** 0=Performance 1=Balanced 2=Quality (native maps it to `NVSDK_NGX_PerfQuality_Value`). 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetDlssQuality(quality: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssquality ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(quality) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssQuality failed: $t")
            Int.MIN_VALUE
        }
    }
    /** NGX render preset: 0=Default, 10=J 11=K 12=L 13=M; a change rebuilds the feature. Stored value / -1 / [Int.MIN_VALUE]. */
    fun D3D12SetDlssRenderPreset(preset: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssrenderpreset ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(preset) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssRenderPreset failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Copy the depth **before** it is cleared into the native-private D32 snapshot; later evaluations prefer it. 1/0/-1/[Int.MIN_VALUE] (= fall back to the caller's depth). */
    fun D3D12SnapshotDlssDepth(ctx: Long, depth: Long): Int =
        dlssCall(MH_snapshotdlssdepth, "dx12SnapshotDlssDepth failed: ", ctx, ptr(depth))
    /** Hand a JVM-created texture to native as the DLSS output; the value must be the **`Dx12Object*` handle** ([D3D12GpuTexture.handle], never a reinterpret_cast'd resource), 0 = revoke. 1/0/-1/[Int.MIN_VALUE]. */
    /** PCL frame begin: opens this frame's SL token (eSimulationStart). */
    fun D3D12ReflexFrameBegin() {
        if (LOAD_FAILURE != null) return
        val handle = MH_reflexframebegin ?: return
        try {
            handle.invokeExact()
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12ReflexFrameBegin failed: ${t}")
        }
    }
    /** 0=off 1=on 2=on+boost. */
    /** DLSS-G: mode 0=off 1=on 2=dynamic; frames 1..5. */
    fun D3D12SetDlssgMode(mode: Int, frames: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setdlssgmode
        if (handle == null) {
            io.github.dxegp.diagnostics.IllusionLogger.warnMessage("DLSS-G: mcdr_dx12SetDlssgMode is not bound (native build too old?)")
            return
        }
        try {
            handle.invokeExact(mode, frames)
        } catch (t: Throwable) {
            io.github.dxegp.diagnostics.IllusionLogger.warnMessage("DLSS-G: mcdr_dx12SetDlssgMode threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Frames the last Present really submitted; 1 when the export is missing (= no frame generation). */
    fun D3D12DlssgPresentedFrames(): Int {
        val handle = MH_getdlssgpresented ?: return 1
        return try {
            (handle.invokeExact() as Int).coerceAtLeast(1)
        } catch (e: Throwable) {
            1
        }
    }

    fun D3D12SetReflexMode(mode: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setreflexmode ?: return
        try {
            handle.invokeExact(mode)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetReflexMode(${mode}) failed: ${t}")
        }
    }
    /** 1 = Reflex available; 0 = no export. */
    /** 1 = Streamline reports DLSS loaded; 0 also covers "SL up but the NGX runtime is too old" — grey the UI out. */
    fun D3D12DlssAvailable(): Int {
        if (LOAD_FAILURE != null) return 0
        val handle = MH_dlssavailable ?: return 1  // 旧 DLL 没有这个导出时按“未知=可用”处理
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12DlssAvailable failed: ${t}")
            1
        }
    }
    fun D3D12ReflexAvailable(): Int {
        if (LOAD_FAILURE != null) return 0
        val handle = MH_reflexavailable ?: return 0
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12ReflexAvailable failed: ${t}")
            0
        }
    }
    /** Reflex frame limiter in microseconds per frame (0 = off). */
    fun D3D12SetReflexFrameLimit(micros: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setreflexframelimit ?: return
        try {
            handle.invokeExact(micros)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetReflexFrameLimit(${micros}) failed: ${t}")
        }
    }
    /** 0=auto 1=force on 2=force off; takes effect only when the swapchain is (re)built. */
    /** 0=off 1=ao 2=ao_shadows 3=full; state only — push it before the device is created. */
    fun D3D12SetRaytracingMode(mode: Int) {
        if (!isAvailable()) return
        @Suppress("TooGenericExceptionCaught")
        try {
            val handle = MH_setraytracingmode ?: return
            handle.invokeExact(mode)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetRaytracingMode($mode) failed: ${t}")
        }
    }
    fun D3D12SetHdrMode(mode: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_sethdrmode ?: return
        try {
            handle.invokeExact(mode)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetHdrMode($mode) failed: ${t}")
        }
    }

    /** allowTearing / presentChildWindow; applies at the next swapchain build. */
    fun D3D12SetSwapchainOptions(allowTearing: Boolean, presentChild: Boolean) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setswapchainoptions ?: return
        try {
            handle.invokeExact(if (allowTearing) 1 else 0, if (presentChild) 1 else 0)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage(
                "dx12SetSwapchainOptions($allowTearing, $presentChild) failed: ${t}",
            )
        }
    }
    /** HDR mode the swapchain was actually built with, -1 before any build. */
    fun D3D12HdrAppliedMode(): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_gethdrappliedmode ?: return -1
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            -1
        }
    }
    /** 1 = mesh shaders supported, 0 = not, -1 = no export; [reasonOut] gets one line of why. */
    fun D3D12QueryMeshSupport(reasonOut: MemorySegment, capacity: Int): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_meshsupport ?: return -1
        return try {
            (handle.invokeExact(reasonOut, capacity) as Int)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12QueryMeshSupport failed: ${t}")
            -1
        }
    }
    /** PCL marker (1=eSimulationEnd 2=eRenderSubmitStart). */
    fun D3D12ReflexMarker(marker: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_reflexmarker ?: return
        try {
            handle.invokeExact(marker)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12ReflexMarker(${marker}) failed: ${t}")
        }
    }
    fun D3D12SetDlssOutput(textureHandle: Long): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssoutput ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(textureHandle)) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssOutput failed: $t")
            Int.MIN_VALUE
        }
    }
    private fun dlssCall(handle: MethodHandle?, what: String, ctx: Long, input: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val h = handle ?: return Int.MIN_VALUE
        return try {
            h.invokeExact(ptr(ctx), input) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("$what$t")
            Int.MIN_VALUE
        }
    }
    /** Write DLSS/NGX availability into [note] (UTF-8, at most [cap] bytes, may be NULL); 1/0/-1. */
    fun D3D12QueryDlss(note: MemorySegment, cap: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_querydlss ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(note, cap) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12QueryDlss failed: $t")
            Int.MIN_VALUE
        }
    }
    // ---- SR (super resolution) contract layer: not on any per-frame path, so a confined arena is fine ----

    /**
     * One `mcdr_sr_caps` row, field for field. [note] is native's fixed `char[28]` reason, not a
     * machine verdict — read [available] for that.
     */
    data class SrCaps(
        val backend: Int,
        val available: Boolean,
        val coupledDenoise: Boolean,
        val needsExposure: Boolean,
        val takesMasks: Int,
        val externalHeap: Boolean,
        val jitterPhaseCount: Int,
        val minRenderWidth: Int,
        val minRenderHeight: Int,
        val maxRenderWidth: Int,
        val maxRenderHeight: Int,
        val nativeAA: Boolean,
        val note: String
    ) {
        val takesReactiveMask: Boolean get() = (takesMasks and SR_MASK_REACTIVE) != 0

        val takesResponsiveMask: Boolean get() = (takesMasks and SR_MASK_RESPONSIVE) != 0

        val takesTransparencyAndCompositionMask: Boolean
            get() = (takesMasks and SR_MASK_TRANSPARENCY_AND_COMPOSITION) != 0
    }

    /** Record the wanted backend (`mcdr_dx12SrPick`): pure bookkeeping, nothing is built or destroyed. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SrPick(backend: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srpick ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrPick failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Query one backend's caps; null = unusable (no export / bad argument / call failed — all treated alike). */
    fun querySrCaps(backend: Int): SrCaps? {
        if (LOAD_FAILURE != null) {
            return null
        }
        val handle = MH_srquerycaps ?: return null
        return try {
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(MCDRLayout.SR_CAPS)
                val rc = handle.invokeExact(backend, seg) as Int
                if (rc != 1) {
                    return null
                }
                SrCaps(
                    backend = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_BACKEND),
                    available = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_AVAILABLE) != 0,
                    coupledDenoise = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_COUPLED_DENOISE) != 0,
                    needsExposure = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_NEEDS_EXPOSURE) != 0,
                    takesMasks = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_TAKES_MASKS),
                    externalHeap = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_EXTERNAL_HEAP) != 0,
                    jitterPhaseCount = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_JITTER_PHASE_COUNT),
                    minRenderWidth = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_MIN_RENDER_WIDTH),
                    minRenderHeight = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_MIN_RENDER_HEIGHT),
                    maxRenderWidth = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_MAX_RENDER_WIDTH),
                    maxRenderHeight = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_MAX_RENDER_HEIGHT),
                    nativeAA = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_NATIVE_AA) != 0,
                    note = seg.getString(MCDRLayout.SR_CAPS_NOTE, Charsets.UTF_8)
                )
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrQueryCaps failed: $t")
            null
        }
    }

    /** One line of SR status into [note]. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SrQuery(note: MemorySegment, cap: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srquery ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(note, cap) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrQuery failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Create or wake the backend (`mcdr_dx12SrCreate`); **the command list must be open**.
     * @param maxRenderWidth upper bound for rebuild decisions, not this frame's size.
     * 1/0/-1/[Int.MIN_VALUE].
     */
    fun D3D12SrCreate(
        ctx: Long,
        backend: Int,
        maxRenderWidth: Int,
        maxRenderHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srcreate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), backend, maxRenderWidth, maxRenderHeight, displayWidth, displayHeight) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrCreate failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Size change; 1 = accepted, 0 = backend not ready (ignore), -1, [Int.MIN_VALUE]. */
    fun D3D12SrResize(ctx: Long, renderWidth: Int, renderHeight: Int, displayWidth: Int, displayHeight: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srresize ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), renderWidth, renderHeight, displayWidth, displayHeight) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrResize failed: $t")
            Int.MIN_VALUE
        }
    }

    enum class SrPreparationFailure { INVALID_ARGUMENT, PROVIDER_REJECTED, COMPLETION_UNKNOWN, CONTRACT_VIOLATION }

    sealed interface SrPreparation {
        val retainedToken: Long
        data class Ready(override val retainedToken: Long) : SrPreparation
        data class NotReady(override val retainedToken: Long) : SrPreparation
        data class Rejected(val reason: SrPreparationFailure, override val retainedToken: Long,
            val detail: String) : SrPreparation
    }

    fun D3D12SrPrepare(ctx: Long, description: MemorySegment, dlssQuality: Int, dlssPreset: Int): SrPreparation {
        check(LOAD_FAILURE == null) { "Native library is unavailable" }
        val function = checkNotNull(MH_srprepare) { "Native SR preparation entry point is unavailable" }
        return Arena.ofConfined().use { arena ->
            val token = arena.allocate(MCDRLayout.I64)
            val status = try {
                function.invokeExact(ptr(ctx), description, dlssQuality, dlssPreset, token) as Int
            } catch (failure: Throwable) {
                return@use SrPreparation.Rejected(SrPreparationFailure.COMPLETION_UNKNOWN,
                    token.get(MCDRLayout.I64, 0L), "Native SR preparation call failed: $failure")
            }
            val retainedToken = token.get(MCDRLayout.I64, 0L)
            when {
                status == 1 && retainedToken != 0L -> SrPreparation.Ready(retainedToken)
                status == 0 -> SrPreparation.NotReady(retainedToken)
                else -> SrPreparation.Rejected(when (status) {
                    -1 -> SrPreparationFailure.INVALID_ARGUMENT
                    -2 -> SrPreparationFailure.PROVIDER_REJECTED
                    -3 -> SrPreparationFailure.COMPLETION_UNKNOWN
                    else -> SrPreparationFailure.CONTRACT_VIOLATION
                }, retainedToken, D3D12LastError().ifEmpty { "SR preparation returned status $status" })
            }
        }
    }

    fun D3D12SrCommit(token: Long): Int {
        val function = checkNotNull(MH_srcommit) { "Native SR commit entry point is unavailable" }
        return function.invokeExact(token) as Int
    }

    fun D3D12SrAbort(token: Long): Int {
        val function = checkNotNull(MH_srabort) { "Native SR abort entry point is unavailable" }
        return function.invokeExact(token) as Int
    }

    enum class FgPreparationFailure {
        INVALID_ARGUMENT, RESTART_REQUIRED, PROVIDER_REJECTED, COMPLETION_UNKNOWN, CONTRACT_VIOLATION
    }

    sealed interface FgPreparation {
        val retainedToken: Long
        data class Ready(override val retainedToken: Long) : FgPreparation
        data class NotReady(override val retainedToken: Long) : FgPreparation
        data class Rejected(val reason: FgPreparationFailure, override val retainedToken: Long,
            val detail: String) : FgPreparation
    }

    fun D3D12FgPrepare(description: MemorySegment): FgPreparation {
        val function = checkNotNull(MH_fgprepare) { "Native FG preparation entry point is unavailable" }
        return Arena.ofConfined().use { arena ->
            val token = arena.allocate(MCDRLayout.I64)
            val status = try {
                function.invokeExact(description, token) as Int
            } catch (failure: Throwable) {
                return@use FgPreparation.Rejected(FgPreparationFailure.COMPLETION_UNKNOWN,
                    token.get(MCDRLayout.I64, 0L), "Native FG preparation call failed: $failure")
            }
            val retainedToken = token.get(MCDRLayout.I64, 0L)
            when {
                status == 1 && retainedToken != 0L -> FgPreparation.Ready(retainedToken)
                status == 0 -> FgPreparation.NotReady(retainedToken)
                else -> FgPreparation.Rejected(when (status) {
                    -1 -> FgPreparationFailure.INVALID_ARGUMENT
                    -2 -> FgPreparationFailure.RESTART_REQUIRED
                    -3 -> FgPreparationFailure.PROVIDER_REJECTED
                    else -> FgPreparationFailure.CONTRACT_VIOLATION
                }, retainedToken, D3D12LastError().ifEmpty { "FG preparation returned status $status" })
            }
        }
    }

    fun D3D12FgAbort(token: Long): Int {
        val function = checkNotNull(MH_fgabort) { "Native FG abort entry point is unavailable" }
        return function.invokeExact(token) as Int
    }

    fun D3D12CommitPreparedConfiguration(srToken: Long, fgToken: Long, antiAliasing: Int): Int {
        val function = checkNotNull(MH_commitpreparedconfiguration) { "Native configuration commit entry point is unavailable" }
        return function.invokeExact(srToken, fgToken, antiAliasing) as Int
    }

    /**
     * Backend's preferred sub-pixel jitter. **Returning 0 is normal** (= no phase table of its own;
     * [outX]/[outY] are untouched). 1/0/-1/[Int.MIN_VALUE].
     */
    fun D3D12SrJitter(frameIndex: Int, outX: MemorySegment, outY: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srjitter ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(frameIndex, outX, outY) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrJitter failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Ask the backend for its jitter phase length — the only source; never hard-code 8 or 16.
     * 0 = n/a, -1 = bad argument, [Int.MIN_VALUE] = no export.
     */
    fun D3D12SrJitterPhaseCount(backend: Int, renderWidth: Int, displayWidth: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srjitterphase ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend, renderWidth, displayWidth) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrJitterPhaseCount failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Release the backend; **only with no open command list** (frame start); idempotent. */
    fun D3D12SrDestroy() {
        if (LOAD_FAILURE != null) {
            return
        }
        val handle = MH_srdestroy ?: return
        try {
            handle.invokeExact()
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrDestroy failed: $t")
        }
    }

    /**
     * Record the wanted frame-generation backend (pure bookkeeping; SR and FG may be different
     * vendors). backend 0=none 1=DLSS-G 2=FSR-FG 3=XeSS-FG; 1/0/[Int.MIN_VALUE].
     */
    fun D3D12FgPick(backend: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgpick ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgPick failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Fill [out] (`FG_CAPS`, 80 bytes, confined arena). **The only verdict bit is `available`** —
     * the rest explain why. 1/-1/[Int.MIN_VALUE].
     */
    fun D3D12FgQueryCaps(backend: Int, out: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgquerycaps ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend, out) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgQueryCaps failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Store the desired options (`FG_DISPATCH`); applied before the next Present, so any thread may
     * call it. mode 0=off 1=fixed(numFrames) 2=dynamic. 1/0/-1/[Int.MIN_VALUE].
     */
    fun D3D12FgSetMode(desc: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgsetmode ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(desc) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetMode failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Frames the last Present really submitted (1 = no interpolation); [Int.MIN_VALUE] = no export. */
    fun D3D12FgPresented(): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgpresented ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgPresented failed: $t")
            Int.MIN_VALUE
        }
    }

    /** One line of FG status into [note]. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12FgStatus(note: MemorySegment, cap: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgstatus ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(note, cap) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgStatus failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Hand the GUI composite target to the FG backend as its UI texture (`Dx12Object*` handle,
     * 0 = revoke); call it once per target rebuild. 1/[Int.MIN_VALUE].
     */
    fun D3D12FgSetUiTexture(objectHandle: Long): Int {
        if (LOAD_FAILURE != null) return Int.MIN_VALUE
        val handle = MH_fgsetuitexture ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(MemorySegment.NULL.takeIf { objectHandle == 0L } ?: MemorySegment.ofAddress(objectHandle))
            1
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetUiTexture failed: $t")
            Int.MIN_VALUE
        }
    }
    /**
     * hudless present: the backbuffer carries the world only and the backend composites the UI onto
     * every presented frame. Requires [D3D12FgUiCompositionReady] — else the UI disappears; switch it
     * only on the once-per-frame latch. 1/[Int.MIN_VALUE].
     */
    fun D3D12FgSetUiComposition(enabled: Boolean): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgsetuicomposition ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(if (enabled) 1 else 0)
            1
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetUiComposition failed: $t")
            Int.MIN_VALUE
        }
    }

    /** 1 = the backend will composite the UI this Present, 0 = present world+GUI yourself, [Int.MIN_VALUE] = no export. */
    fun D3D12FgUiCompositionReady(): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fguicompositionready ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgUiCompositionReady failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Packed FG state (`mcdr_dx12FgState`): [0..7] desiredMode, [8..15] chainMode, [16..23] active,
     * [24..31] rebuilds. Read this instead of `options.txt` — a native rollback clears desiredMode
     * while the option still says "on". [Int.MIN_VALUE] = no export.
     */
    fun D3D12FgState(): Int {        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgstate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgState failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * One `mcdr_fg_caps` row. [note] is native's short English reason (<= 27 bytes), shown as-is and
     * deliberately untranslated so it still matches the log.
     */
    data class FgCaps(
        val backend: Int,
        val available: Boolean,
        val runtimeLoaded: Boolean,
        val deviceSupported: Boolean,
        val chainCompatible: Boolean,
        val needsReflex: Boolean,
        val needsUiResource: Boolean,
        val needsHudless: Boolean,
        val transitionsInputs: Boolean,
        val maxGeneratedFrames: Int,
        val chainOwner: Int,
        /** `MCDR_FG_LATENCY_*`: architectural pairing, **not** runtime availability. */
        val latencyProvider: Int,
        val note: String
    )

    /**
     * Query one backend's caps; null = unavailable (no export / bad argument / call failed).
     * Unlike SR this never probes LoadLibrary, so it is safe on the per-frame path.
     */
    fun queryFgCaps(backend: Int): FgCaps? {
        if (LOAD_FAILURE != null) {
            return null
        }
        val handle = MH_fgquerycaps ?: return null
        return try {
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(MCDRLayout.FG_CAPS)
                val rc = handle.invokeExact(backend, seg) as Int
                if (rc != 1) {
                    return null
                }
                FgCaps(
                    backend = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_BACKEND),
                    available = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_AVAILABLE) != 0,
                    runtimeLoaded = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_RUNTIME_LOADED) != 0,
                    deviceSupported = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_DEVICE_SUPPORTED) != 0,
                    chainCompatible = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_CHAIN_COMPATIBLE) != 0,
                    needsReflex = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_NEEDS_REFLEX) != 0,
                    needsUiResource = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_NEEDS_UI_RESOURCE) != 0,
                    needsHudless = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_NEEDS_HUDLESS) != 0,
                    transitionsInputs = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_TRANSITIONS_INPUTS) != 0,
                    maxGeneratedFrames = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_MAX_GENERATED_FRAMES),
                    chainOwner = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_CHAIN_OWNER),
                    latencyProvider = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_LATENCY_PROVIDER),
                    note = seg.getString(MCDRLayout.FG_CAPS_NOTE, Charsets.UTF_8)
                )
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgQueryCaps failed: $t")
            null
        }
    }

    /** Throw with the native error text when `value` is false. */
    private fun check(value: Boolean, what: String) {
        if (!value) {
            val detail = D3D12LastError()
            throw IllegalStateException("${what} failed"
                + (if (detail.isEmpty()) "" else ": $detail"))
        }
    }
    // ---- Device + resources ----
    /** Create the device and return its adapter/feature summary, or "ERROR: ...". */
    fun ID3D12CreateDevice(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_createdevice!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return "ERROR: native library reported no device summary"
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_createdevice!!.invokeExact(buf, need) as Int
            if (written <= 0) {
                return "ERROR: native library reported no device summary"
            }
            return buf.getString(0)
        }
    }
    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateTexture(usage: Int, format: Int, width: Int,
                            height: Int, depthOrLayers: Int, mipLevels: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createtexture!!.invokeExact(
                usage, format, width, height, depthOrLayers, mipLevels) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateBuffer(usage: Int, size: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createbuffer!!.invokeExact(usage, size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateSampler(addressU: Int, addressV: Int, minFilter: Int,
                            magFilter: Int, maxAnisotropy: Int, maxLod: Float): Long {
        requireLoaded()
        try {
            return addr(MH_createsampler!!.invokeExact(
                addressU, addressV, minFilter, magFilter, maxAnisotropy, maxLod) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Sampler with mip LOD bias; 0 = no such export — fall back to [ID3D12CreateSampler], never fail startup. */
    fun ID3D12CreateSamplerWithMipBias(addressU: Int, addressV: Int, minFilter: Int,
                                       magFilter: Int, maxAnisotropy: Int, maxLod: Float,
                                       mipLodBias: Float): Long {
        requireLoaded()
        val handle = MH_createsamplerbias ?: return 0L
        try {
            return addr(handle.invokeExact(
                addressU, addressV, minFilter, magFilter, maxAnisotropy, maxLod, mipLodBias) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateTextureView(textureHandle: Long, baseMipLevel: Int, mipLevels: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createtextureview!!.invokeExact(
                ptr(textureHandle), baseMipLevel, mipLevels) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun ID3D12DestroyResource(handle: Long) {
        requireLoaded()
        try {
            MH_destroyresource!!.invokeExact(ptr(handle))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Mapped view is explicitly LITTLE_ENDIAN; null on failure (see [D3D12LastError]). */
    fun D3D12MapBuffer(bufferHandle: Long, offset: Long, length: Long,
                       read: Boolean, write: Boolean): ByteBuffer? {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val pLen = arena.allocate(MCDRLayout.I64)
            val base = MH_mapbuffer!!.invokeExact(
                ptr(bufferHandle), offset, length, if (read) 1 else 0, if (write) 1 else 0, pLen) as MemorySegment
            if (base.address() == 0L) {
                return null
            }
            val actual = pLen.get(MCDRLayout.I64, 0) as Long
            return base.reinterpret(actual).asByteBuffer().order(ByteOrder.nativeOrder())
        }
    }
    fun D3D12UnmapBuffer(bufferHandle: Long) {
        requireLoaded()
        try {
            MH_unmapbuffer!!.invokeExact(ptr(bufferHandle))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** JSON array of every D3D12-capable adapter. */
    fun ID3D12EnumerateAdapters(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_enumerateadapters!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return "[]"
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_enumerateadapters!!.invokeExact(buf, need) as Int
            return if (written > 0) buf.getString(0) else "[]"
        }
    }
    /** JSON object for the live device's adapter; "" when there is no device. */
    fun ID3D12GetAdapterInfo(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_getadapterinfo!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return ""
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_getadapterinfo!!.invokeExact(buf, need) as Int
            return if (written > 0) buf.getString(0) else ""
        }
    }
    /** Global queue COM pointer; 0 when no device. */
    fun ID3D12GetQueueHandle(): Long {
        requireLoaded()
        try {
            return MH_getqueuehandle!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Global device COM pointer; 0 when no device. */
    fun ID3D12GetDeviceHandle(): Long {
        requireLoaded()
        try {
            return MH_getdevicehandle!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Hidden Win32 window for a standalone loop; 0 on failure. */
    fun D3D12CreateHiddenWindow(width: Int, height: Int): Long {
        requireLoaded()
        try {
            return MH_createhiddenwindow!!.invokeExact(width, height) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyHiddenWindow(hwnd: Long) {
        requireLoaded()
        try {
            MH_destroyhiddenwindow!!.invokeExact(hwnd)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Command layer ----
    /** GPU timestamp ticks per second; 0 if unknown. */
    fun D3D12GetTimestampFrequency(): Long {
        requireLoaded()
        try {
            return MH_gettimestampfrequency!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a `CommandContext*` handle. */
    fun D3D12CreateCommandEncoder(): Long {
        requireLoaded()
        try {
            return addr(MH_createcommandencoder!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyCommandEncoder(ctx: Long) {
        requireLoaded()
        try {
            MH_destroycommandencoder!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    internal fun D3D12DestroyCommandEncoderCheckedV1(ctx: Long): CommandDestructionOutcome {
        requireLoaded()
        return when (val result = MH_destroycommandencodercheckedv1!!.invokeExact(ptr(ctx)) as Int) {
            1 -> CommandDestructionOutcome.DESTROYED
            2 -> CommandDestructionOutcome.OWNERSHIP_PENDING
            3 -> CommandDestructionOutcome.SUBMISSION_UNKNOWN
            else -> error("Invalid command destruction outcome: $result")
        }
    }

    internal fun D3D12CommandRecordingStatusV1(ctx: Long): CommandSubmissionOutcome {
        requireLoaded()
        return CommandSubmissionOutcome.decode(MH_commandrecordingstatusv1!!.invokeExact(ptr(ctx)) as Int, 0L)
    }

    internal fun D3D12SubmitV1(ctx: Long): CommandSubmissionOutcome {
        requireLoaded()
        return withScratch(MCDRLayout.I64.byteSize(), MCDRLayout.I64.byteAlignment()) { temporary ->
            val fence = scratch(0, MCDRLayout.I64.byteSize(), temporary, MCDRLayout.I64.byteAlignment())
            val status = MH_submitv1!!.invokeExact(ptr(ctx), fence) as Int
            CommandSubmissionOutcome.decode(status, fence.get(MCDRLayout.I64, 0L))
        }
    }

    internal fun D3D12SettleCommandSubmissionV1(ctx: Long, timeoutMs: Long): CommandSubmissionOutcome {
        requireLoaded()
        require(timeoutMs >= 0L)
        return withScratch(MCDRLayout.I64.byteSize(), MCDRLayout.I64.byteAlignment()) { temporary ->
            val fence = scratch(0, MCDRLayout.I64.byteSize(), temporary, MCDRLayout.I64.byteAlignment())
            val status = MH_settlecommandsubmissionv1!!.invokeExact(ptr(ctx), timeoutMs, fence) as Int
            CommandSubmissionOutcome.decode(status, fence.get(MCDRLayout.I64, 0L))
        }
    }
    /** Reset the allocator and open the list. */
    fun D3D12BeginCommandList(ctx: Long) {
        requireLoaded()
        try {
            MH_begincommandlist!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Wait on the queue fence before resetting the allocator; 0 = no wait. */
    fun D3D12BeginCommandListWithWait(ctx: Long, waitForValue: Long) {
        requireLoaded()
        try {
            MH_begincommandlistwithwait!!.invokeExact(ptr(ctx), waitForValue)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12EndCommandList(ctx: Long) {
        requireLoaded()
        try {
            MH_endcommandlist!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ExecuteCommandLists + Signal(fence, ++value); non-blocking, returns this submit's fence value. */
    fun D3D12Submit(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_submit!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Waits on **this context's** fence only — a one-shot encoder cannot advance a frame encoder's value. false = timeout, not an error. */
    fun D3D12WaitForFence(ctx: Long, value: Long, timeoutNs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_waitforfence!!.invokeExact(ptr(ctx), value, timeoutNs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Current value of this context's fence; a new fence targets this value + 1. */
    fun D3D12GetFenceValue(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_getfencevalue!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Read the GPU timestamp now (blocking). */
    fun D3D12GetTimestampNow(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_gettimestampnow!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12IsListOpen(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_islistopen!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** CopyBufferRegion into the open list. */
    fun D3D12CopyBuffer(ctx: Long, src: Long, srcOffset: Long,
                        dst: Long, dstOffset: Long, size: Long) {
        requireLoaded()
        try {
            MH_copybuffer!!.invokeExact(ptr(ctx), ptr(src), srcOffset, ptr(dst), dstOffset, size)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12CopyBufferChecked(ctx: Long, src: Long, srcOffset: Long,
                             dst: Long, dstOffset: Long, size: Long): Boolean {
        requireLoaded()
        return try {
            (MH_copybufferchecked!!.invokeExact(ptr(ctx), ptr(src), srcOffset,
                ptr(dst), dstOffset, size) as Int) != 0
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    /** Staging -> texture sub-region, tightly packed width*height texels. */
    fun D3D12WriteToTexture(ctx: Long, stagingBuf: Long, stagingOffset: Long,
                            width: Int, height: Int, dstTex: Long, mip: Int, layer: Int, dstX: Int, dstY: Int) {
        requireLoaded()
        try {
            val result = MH_writetotexture!!.invokeExact(ptr(ctx), ptr(stagingBuf), stagingOffset,
                width, height, ptr(dstTex), mip, layer, dstX, dstY) as Int
            check(result == 1) { "Texture upload failed: ${D3D12LastError()}" }
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** (possibly padded) buffer sub-region -> texture. */
    fun D3D12CopyBufferToTexture(ctx: Long, srcBuf: Long,
                                 srcOffset: Long, srcWidth: Int, srcHeight: Int,
                                 dstTex: Long, mip: Int, layer: Int,
                                 dstX: Int, dstY: Int,
                                 w: Int, h: Int) {
        requireLoaded()
        try {
            val result = MH_copybuffertotexture!!.invokeExact(ptr(ctx), ptr(srcBuf), srcOffset,
                srcWidth, srcHeight, ptr(dstTex), mip, layer, dstX, dstY, w, h) as Int
            check(result == 1) { "Buffer to texture copy failed: ${D3D12LastError()}" }
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Texture -> readback buffer; the row pitch is 256-aligned, honour it. */
    fun D3D12CopyTextureToBuffer(ctx: Long, srcTex: Long,
                                 mip: Int, layer: Int,
                                 srcX: Int, srcY: Int,
                                 w: Int, h: Int,
                                 dstBuf: Long, dstOffset: Long) {
        requireLoaded()
        try {
            MH_copytexturetobuffer!!.invokeExact(ptr(ctx),
                ptr(srcTex), mip, layer,
                srcX, srcY, w, h,
                ptr(dstBuf), dstOffset)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12CopyTextureToTexture(ctx: Long, srcTex: Long, dstTex: Long,
                                  mip: Int, layer: Int, srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            val result = MH_copytexturetotexture!!.invokeExact(ptr(ctx), ptr(srcTex), ptr(dstTex),
                mip, layer, srcX, srcY, dstX, dstY, w, h) as Int
            check(result == 1) { "Texture copy failed: ${D3D12LastError()}" }
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Full-texture colour clear; the texture must be a RENDER_ATTACHMENT. */
    fun D3D12ClearColorTexture(ctx: Long, texture: Long, r: Float, g: Float, b: Float, a: Float) {
        requireLoaded()
        try {
            MH_clearcolortexture!!.invokeExact(ptr(ctx), ptr(texture), r, g, b, a)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Three in-place SMAA passes on the world layer; 0 means not selected, 1 applied, negative means failure. */
    fun dx12ApplySmaaToTexture(ctx: Long, texture: Long): Int {
        requireLoaded()
        val handle = MH_applysmaatotexture ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), ptr(texture)) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Full-screen blit src->dst (sizes may differ); leaves dst in RENDER_TARGET. false when the export is missing. */
    fun dx12BlitTextureToTexture(ctx: Long, src: Long, dst: Long): Boolean {
        requireLoaded()
        val handle = MH_blittexturetotexture ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), ptr(src), ptr(dst)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 5-tap unsharp src->dst (TAA/DLAA/DLSS post-sharpen); false => fall back to [dx12BlitTextureToTexture]. */
    /** Whether this DLL exports the post-sharpen. */
    fun supportsSharpenTextureToTexture(): Boolean {
        requireLoaded()
        return MH_sharpentexturetotexture != null
    }
    fun dx12SharpenTextureToTexture(ctx: Long, src: Long, dst: Long, amount: Float): Boolean {
        requireLoaded()
        val handle = MH_sharpentexturetotexture ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), ptr(src), ptr(dst), amount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12ClearDepthTexture(ctx: Long, texture: Long, depth: Double) {
        requireLoaded()
        try {
            MH_cleardepthtexture!!.invokeExact(ptr(ctx), ptr(texture), depth)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 1 = cleared, 0 = failed. */
    fun D3D12ClearStencilTexture(ctx: Long, texture: Long, value: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_clearstenciltexture!!.invokeExact(ptr(ctx), ptr(texture), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Rect clear in render-target pixels (per-slot atlas clears wiped already-baked icons). */
    fun D3D12ClearColorTextureRegion(ctx: Long, texture: Long,
                                     r: Float, g: Float,
                                     b: Float, a: Float,
                                     x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_clearcolortextureregion!!.invokeExact(ptr(ctx),
                ptr(texture),
                r, g, b, a, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12ClearDepthTextureRegion(ctx: Long, texture: Long, depth: Double,
                                     x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_cleardepthtextureregion!!.invokeExact(ptr(ctx), ptr(texture), depth, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /**
     * RTV/DSV + transitions + OMSetRenderTargets + viewport/scissor (+ optional clears).
     * colorMips selects the real RTV mip (atlas uploads bind mipViews[level]); a zero-length colour
     * array is a legal depth-only pass; colorClearFlags 0=load 1=clear.
     */
    fun D3D12BeginRenderPass(ctx: Long, colorTextures: LongArray?, colorMips: IntArray?,
                             colorClearFlags: ByteArray?, clearColors: FloatArray?,
                             depthTexture: Long, depthMip: Int,
                             depthClearFlag: Byte, depthClearValue: Double,
                             x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        val colorCount = colorTextures?.size ?: 0
        val clearColorCount = Math.multiplyExact(colorCount, 4)
        val maximumBytes = maxOf(checkedArrayBytes(colorCount.coerceAtLeast(1), MCDRLayout.PTR),
            if (clearColors != null && clearColors.size >= clearColorCount)
                checkedArrayBytes(clearColorCount, MCDRLayout.F32) else 0L)
        withScratch(maximumBytes,
            maxOf(MCDRLayout.PTR.byteAlignment(), MCDRLayout.I32.byteAlignment(), MCDRLayout.F32.byteAlignment())) { temporary ->
            val views = scratchArray(0, colorCount.coerceAtLeast(1), MCDRLayout.PTR, temporary, FfmEmptyAllocation.NON_NULL)
            for (index in 0 until colorCount) {
                views.set(MCDRLayout.PTR, index * MCDRLayout.PTR.byteSize(), ptr(colorTextures!![index]))
            }
            var mips = MemorySegment.NULL
            if (colorCount > 0 && colorMips != null && colorMips.size >= colorCount) {
                mips = scratchArray(2, colorCount, MCDRLayout.I32, temporary)
                MemorySegment.copy(colorMips, 0, mips, MCDRLayout.I32, 0, colorCount)
            }
            var flags = MemorySegment.NULL
            if (colorCount > 0 && colorClearFlags != null && colorClearFlags.size >= colorCount) {
                flags = scratchArray(1, colorCount, MCDRLayout.I32, temporary)
                for (index in 0 until colorCount) {
                    flags.set(MCDRLayout.I32, index * MCDRLayout.I32.byteSize(), colorClearFlags[index].toInt())
                }
            }
            var colors = MemorySegment.NULL
            if (clearColors != null && clearColors.size >= clearColorCount) {
                colors = scratchArray(3, clearColorCount, MCDRLayout.F32, temporary)
                MemorySegment.copy(clearColors, 0, colors, MCDRLayout.F32, 0, clearColorCount)
            }
            try {
                MH_beginrenderpass!!.invokeExact(ptr(ctx), views, colorCount, mips, flags, colors,
                    ptr(depthTexture), depthMip, depthClearFlag.toInt(), depthClearValue, x, y, w, h)
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }
    fun D3D12EndRenderPass(ctx: Long) {
        requireLoaded()
        try {
            MH_endrenderpass!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** First active colour attachment of the open pass; 0 when none. */
    fun D3D12GetActiveColorTexture(ctx: Long): Long {
        requireLoaded()
        try {
            return addr(MH_getactivecolortexture!!.invokeExact(ptr(ctx)) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a native handle; 0 on failure. */
    fun D3D12CreateQueryPool(size: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createquerypool!!.invokeExact(size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyQueryPool(pool: Long) {
        requireLoaded()
        try {
            MH_destroyquerypool!!.invokeExact(ptr(pool))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** EndQuery(TIMESTAMP) at `index` into the open list. */
    fun D3D12WriteTimestamp(ctx: Long, pool: Long, index: Int) {
        requireLoaded()
        try {
            MH_writetimestamp!!.invokeExact(ptr(ctx), ptr(pool), index)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking read of one timestamp. */
    fun D3D12ReadQueryValue(pool: Long, index: Int): Long {
        requireLoaded()
        try {
            return MH_readqueryvalue!!.invokeExact(ptr(pool), index) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking read of `count` timestamps into [out]. */
    fun D3D12ReadQueryValues(pool: Long, start: Int, count: Int, out: LongArray) {
        requireLoaded()
        require(start >= 0 && count >= 0)
        require(out.size >= count)
        if (count == 0) {
            return
        }
        withScratch(checkedArrayBytes(count, MCDRLayout.I64), MCDRLayout.I64.byteAlignment()) { temporary ->
            val buf = scratchArray(0, count, MCDRLayout.I64, temporary)
            try {
                val rc = MH_readqueryvalues!!.invokeExact(ptr(pool), start, count, buf) as Int
                if (rc != 0) {
                    MemorySegment.copy(buf, MCDRLayout.I64, 0L, out, 0, count)
                }
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }
    // ---- Async: dedicated render thread ----
    /** Start the render thread; **must precede [D3D12CreateCommandEncoder]**. */
    fun D3D12InitAsyncRenderer(workerCount: Int) {
        requireLoaded()
        try {
            MH_initasyncrenderer!!.invokeExact(workerCount)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyAsyncRenderer(): Boolean {
        requireLoaded()
        try {
            return MH_destroyasyncrenderer!!.invokeExact() as Int == 1
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Must run before process exit: releasing the ComPtrs in DLL_PROCESS_DETACH jumps into a dead dxgi vtable (exit code 0x87A); idempotent. */
    fun D3D12DestroyDevice(): Boolean {
        requireLoaded()
        try {
            return ok(MH_destroydevice!!.invokeExact() as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking: ask the render thread for the next frame. */
    fun D3D12AsyncRenderBeginFrame(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderbeginframe!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking: wait until this frame is submitted; false = timeout. */
    fun D3D12AsyncRenderWaitComplete(ctx: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderwaitcomplete!!.invokeExact(ptr(ctx), timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking: has the render thread reached "recording ready"? */
    fun D3D12AsyncRenderIsRecordingReady(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderisrecordingready!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking "recording ready"; replaces the old sleep(1) poll on the critical path. */
    fun D3D12AsyncRenderWaitRecordingReady(ctx: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderwaitrecordingready!!.invokeExact(ptr(ctx), timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Tell the render thread that all commands are recorded. */
    fun D3D12AsyncSendCommandsReady(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncsendcommandsready!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Clear `gAsyncRenderCtx` (Java-side timeout fallback). */
    fun D3D12ClearAsyncRenderCtx() {
        requireLoaded()
        try {
            MH_clearasyncrenderctx!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Pipeline ----
    /** Compile the HLSL pair and build the pipeline; 0 on failure (see [D3D12LastError]). */
    fun D3D12CreateGraphicsPipeline(desc: MCDRPipelineDesc, pushConstantBytes: Int = 0): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(MH_creategraphicsconstants!!.invokeExact(packed, pushConstantBytes) as MemorySegment)
        }
    }

    internal fun D3D12CreateOitNormalizationPipelineV1(desc: MCDRPipelineDesc,
        certificationVersion: Int, targetCount: Int, programGeneration: Long,
        fragmentSourceHash: ByteArray, vertexSourceHash: ByteArray, contractHash: ByteArray): Long {
        requireLoaded()
        require(certificationVersion == 1 && targetCount in setOf(1, 2, 4) && programGeneration > 0L)
        require(fragmentSourceHash.size == 32 && vertexSourceHash.size == 32 && contractHash.size == 32)
        return Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            val layout = MCDRLayout.OIT_NORMALIZATION_PIPELINE_V1
            val certification = arena.allocate(layout)
            certification.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("schemaVersion")), 1)
            certification.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("structSize")), layout.byteSize().toInt())
            certification.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("certificationVersion")), certificationVersion)
            certification.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("targetCount")), targetCount)
            certification.set(MCDRLayout.I64, layout.byteOffset(MemoryLayout.PathElement.groupElement("programGeneration")), programGeneration)
            MemorySegment.copy(fragmentSourceHash, 0, certification, MCDRLayout.I8,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("fragmentSourceHash")), 32)
            MemorySegment.copy(vertexSourceHash, 0, certification, MCDRLayout.I8,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("vertexSourceHash")), 32)
            MemorySegment.copy(contractHash, 0, certification, MCDRLayout.I8,
                layout.byteOffset(MemoryLayout.PathElement.groupElement("contractHash")), 32)
            addr(checkNotNull(MH_createoitnormalizationpipelinev1).invokeExact(packed, certification) as MemorySegment)
        }
    }

    internal fun D3D12OitShaderPreludeV1(): OitShaderPreludeV1 {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val required = arena.allocate(MCDRLayout.I32)
            val abi = arena.allocate(MCDRLayout.I32)
            val hash = arena.allocate(MCDRLayout.I64)
            val handle = checkNotNull(MH_oitshaderprelude)
            val query = handle.invokeExact(MemorySegment.NULL, 0, required, abi, hash) as Int
            check(query == 1) { "OIT shader contract query failed: ${D3D12LastError()}" }
            val size = required.get(MCDRLayout.I32, 0)
            val version = abi.get(MCDRLayout.I32, 0)
            val fingerprint = hash.get(MCDRLayout.I64, 0)
            check(size > 1 && version == OitShaderPreludeV1.SUPPORTED_SHADER_ABI) {
                "Unsupported OIT shader contract: abi=$version bytes=$size"
            }
            val source = arena.allocate(size.toLong())
            val copied = handle.invokeExact(source, size, required, abi, hash) as Int
            check(copied == 1 && required.get(MCDRLayout.I32, 0) == size &&
                abi.get(MCDRLayout.I32, 0) == version && hash.get(MCDRLayout.I64, 0) == fingerprint &&
                source.get(MCDRLayout.I8, size - 1L) == 0.toByte()) {
                "OIT shader contract changed during transfer: ${D3D12LastError()}"
            }
            return OitShaderPreludeV1(version, fingerprint, source.getString(0))
        }
    }

    private fun writeOitCapturePipeline(arena: Arena, shaderAbiVersion: Int,
        shaderContractHash: Long, programGeneration: Long): MemorySegment {
        require(shaderAbiVersion == OitShaderPreludeV1.SUPPORTED_SHADER_ABI && programGeneration > 0L)
        val layout = MCDRLayout.OIT_CAPTURE_PIPELINE
        val input = arena.allocate(layout)
        input.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("schemaVersion")), 1)
        input.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("structSize")), layout.byteSize().toInt())
        input.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("shaderAbiVersion")), shaderAbiVersion)
        input.set(MCDRLayout.I32, layout.byteOffset(MemoryLayout.PathElement.groupElement("flags")), 1)
        input.set(MCDRLayout.I64, layout.byteOffset(MemoryLayout.PathElement.groupElement("shaderContractHash")), shaderContractHash)
        input.set(MCDRLayout.I64, layout.byteOffset(MemoryLayout.PathElement.groupElement("programGeneration")), programGeneration)
        return input
    }

    internal fun D3D12CreateGraphicsCapturePipelineV1(desc: MCDRPipelineDesc, pushConstantBytes: Int,
        shaderAbiVersion: Int, shaderContractHash: Long, programGeneration: Long): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        Arena.ofConfined().use { arena ->
            val descriptor = desc.pack(arena)
            val capture = writeOitCapturePipeline(arena, shaderAbiVersion, shaderContractHash, programGeneration)
            return addr(MH_creategraphicscapture!!.invokeExact(descriptor, pushConstantBytes, capture) as MemorySegment)
        }
    }

    internal fun D3D12CreateMeshCaptureRootSignatureV1(bindings: List<Pair<Int, Int>>, meshExtras: Int,
        pushConstantBytes: Int, shaderAbiVersion: Int, shaderContractHash: Long, programGeneration: Long): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        require(bindings.all { [type, register] -> type in 0..2 && register in 0..255 })
        Arena.ofConfined().use { arena ->
            val packed = arena.allocateBindingPairs(bindings)
            val capture = writeOitCapturePipeline(arena, shaderAbiVersion, shaderContractHash, programGeneration)
            return MH_createmeshcaptureroot!!.invokeExact(packed, bindings.size, meshExtras,
                pushConstantBytes, capture) as Long
        }
    }

    internal fun D3D12CreateMeshCapturePipelineWithRootSignatureV1(desc: MCDRMeshPipelineDesc, root: Long,
        pushConstantBytes: Int, shaderAbiVersion: Int, shaderContractHash: Long, programGeneration: Long): Long {
        requireLoaded()
        require(root != 0L && pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        Arena.ofConfined().use { arena ->
            val descriptor = desc.pack(arena)
            val capture = writeOitCapturePipeline(arena, shaderAbiVersion, shaderContractHash, programGeneration)
            return addr(MH_createmeshcapture!!.invokeExact(descriptor, root, pushConstantBytes, capture) as MemorySegment)
        }
    }

    internal fun D3D12CreateTerrainTileRootV1(bindings: List<Pair<Int, Int>>, pushConstantBytes: Int,
        shaderAbiVersion: Int, shaderContractHash: Long, programGeneration: Long): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        require(bindings.all { [type, register] -> type in 0..2 && register in 0..255 })
        Arena.ofConfined().use { arena ->
            val packed = arena.allocateBindingPairs(bindings)
            val capture = writeOitCapturePipeline(arena, shaderAbiVersion, shaderContractHash, programGeneration)
            return MH_createterrantileroot!!.invokeExact(packed, bindings.size, pushConstantBytes, capture) as Long
        }
    }

    internal fun D3D12CreateTerrainTileRouteV1(root: Long, shaderAbiVersion: Int,
        shaderContractHash: Long, programGeneration: Long): Long {
        requireLoaded()
        require(root != 0L)
        Arena.ofConfined().use { arena ->
            val capture = writeOitCapturePipeline(arena, shaderAbiVersion, shaderContractHash, programGeneration)
            return addr(MH_createterrantilroute!!.invokeExact(root, capture) as MemorySegment)
        }
    }

    internal fun D3D12BindTerrainTilePipelineV1(route: Long, tilePipeline: Long): Boolean {
        requireLoaded()
        require(route != 0L && tilePipeline != 0L)
        return ok(MH_bindterraintilepipeline!!.invokeExact(ptr(route), ptr(tilePipeline)) as Int)
    }

    internal fun D3D12BindTerrainTileRouteV1(capturePipeline: Long, route: Long): Boolean {
        requireLoaded()
        require(capturePipeline != 0L && route != 0L)
        return ok(MH_bindterrantileroute!!.invokeExact(ptr(capturePipeline), ptr(route)) as Int)
    }

    internal fun D3D12BindTerrainTileStageV2(sourcePipeline: Long, route: Long, shaderAbiVersion: Int,
        shaderContractHash: Long, programGeneration: Long, stage: Int, quadsPerMeshlet: Int): Boolean {
        requireLoaded()
        require(sourcePipeline != 0L && route != 0L)
        require(shaderAbiVersion == OitShaderPreludeV1.SUPPORTED_SHADER_ABI && programGeneration > 0L)
        require(stage in 0..2 &&
            (quadsPerMeshlet == 16 || quadsPerMeshlet == 32 || quadsPerMeshlet == 64))
        val layout = MCDRLayout.TERRAIN_TILE_STAGE_V2
        return withScratch(layout.byteSize(), layout.byteAlignment()) { temporary ->
            val contract = scratch(0, layout.byteSize(), temporary, layout.byteAlignment())
            contract.set(MCDRLayout.I32, MCDRLayout.TERRAIN_TILE_STAGE_SCHEMA, 2)
            contract.set(MCDRLayout.I32, MCDRLayout.TERRAIN_TILE_STAGE_SIZE, layout.byteSize().toInt())
            contract.set(MCDRLayout.I32, MCDRLayout.TERRAIN_TILE_STAGE_SHADER_ABI, shaderAbiVersion)
            contract.set(MCDRLayout.I32, MCDRLayout.TERRAIN_TILE_STAGE_KIND, stage)
            contract.set(MCDRLayout.I32, MCDRLayout.TERRAIN_TILE_STAGE_QUADS, quadsPerMeshlet)
            contract.set(MCDRLayout.I32, MCDRLayout.TERRAIN_TILE_STAGE_ADDRESSING, 1)
            contract.set(MCDRLayout.I64, MCDRLayout.TERRAIN_TILE_STAGE_HASH, shaderContractHash)
            contract.set(MCDRLayout.I64, MCDRLayout.TERRAIN_TILE_STAGE_GENERATION, programGeneration)
            ok(MH_bindterraintilestagev2!!.invokeExact(ptr(sourcePipeline), ptr(route), contract) as Int)
        }
    }

    internal fun D3D12DestroyTerrainTileRouteV1(route: Long) {
        if (route == 0L) return
        requireLoaded()
        MH_destroyterraintileroute!!.invokeExact(ptr(route))
    }

    internal fun D3D12OitBeginFrameV1(ctx: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return MH_oitbegin!!.invokeExact(ptr(ctx), input, output) as Int
    }

    internal fun D3D12OitBeginFrameV2(ctx: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return MH_oitbeginv2!!.invokeExact(ptr(ctx), input, output) as Int
    }

    internal fun D3D12OitPublishMetadataV1(ctx: Long, frame: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return MH_oitmetadata!!.invokeExact(ptr(ctx), ptr(frame), input, output) as Int
    }

    internal fun D3D12OitBindDrawV1(ctx: Long, frame: Long, metadata: Long, input: MemorySegment): Int {
        requireLoaded()
        return MH_oitbind!!.invokeExact(ptr(ctx), ptr(frame), ptr(metadata), input) as Int
    }

    internal fun D3D12OitPublishTerrainMetadataV2(ctx: Long, frame: Long, input: MemorySegment,
        output: MemorySegment): Int {
        requireLoaded()
        return MH_oitterrainmetadata!!.invokeExact(ptr(ctx), ptr(frame), input, output) as Int
    }

    internal fun D3D12OitBindTerrainDrawV2(ctx: Long, frame: Long, metadata: Long, input: MemorySegment): Int {
        requireLoaded()
        return MH_oitterrainbind!!.invokeExact(ptr(ctx), ptr(frame), ptr(metadata), input) as Int
    }

    internal fun D3D12OitPrepareTerrainTilesV2(ctx: Long, frame: Long, batch: MemorySegment): Int {
        if (LOAD_FAILURE != null) return Int.MIN_VALUE
        val handle = MH_oitterrainpreparetiles ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), ptr(frame), batch) as Int
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    internal fun D3D12OitPrepareTerrainTilesV3(ctx: Long, frame: Long, batch: MemorySegment): Int {
        if (LOAD_FAILURE != null) return Int.MIN_VALUE
        val handle = MH_oitterrainpreparetilesv3 ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), ptr(frame), batch) as Int
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    internal fun D3D12OitUnbindDrawV1(ctx: Long, frame: Long): Int {
        requireLoaded()
        return MH_oitunbind!!.invokeExact(ptr(ctx), ptr(frame)) as Int
    }

    internal fun D3D12OitSealFrameV1(ctx: Long, frame: Long): Int {
        requireLoaded()
        return MH_oitseal!!.invokeExact(ptr(ctx), ptr(frame)) as Int
    }

    internal fun D3D12OitResolveRecordsV3(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_oitresolve, ctx, input)

    internal fun D3D12OitResolveRecordsV4(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_oitresolvev4, ctx, input)

    internal fun D3D12OitAbortFrameV1(ctx: Long, frame: Long): Int {
        requireLoaded()
        return MH_oitabort!!.invokeExact(ptr(ctx), ptr(frame)) as Int
    }

    internal fun hasOitExecutionStreamV1(): Boolean = LOAD_FAILURE == null &&
        MH_oitexecutionbegin != null && MH_oitexecutionquery != null && MH_oitreceiptquery != null &&
        MH_oitreceiptfrontier != null && MH_oitexecutionappend != null && MH_oitexecutionseal != null

    internal fun D3D12OitBeginExecutionStreamV1(ctx: Long, frame: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionbegin).invokeExact(ptr(ctx), ptr(frame), input, output) as Int
    }

    internal fun D3D12OitQueryExecutionStreamV1(ctx: Long, stream: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionquery).invokeExact(ptr(ctx), ptr(stream), output) as Int
    }

    internal fun D3D12OitQueryTerrainDrawReceiptV1(ctx: Long, frame: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitreceiptquery).invokeExact(ptr(ctx), ptr(frame), input, output) as Int
    }

    internal fun D3D12OitQueryTerrainDrawFrontierV1(ctx: Long, frame: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitreceiptfrontier).invokeExact(ptr(ctx), ptr(frame), output) as Int
    }

    internal fun D3D12OitAppendExecutionEventV1(ctx: Long, stream: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionappend).invokeExact(ptr(ctx), ptr(stream), input, output) as Int
    }

    internal fun hasOitTerrainStageReceiptsV2(): Boolean = LOAD_FAILURE == null &&
        MH_oitterrainstagebeginv2 != null && MH_oitterrainstagefinishv2 != null &&
        MH_oitterrainstageabortv2 != null && MH_oitterrainstagereceiptv2 != null

    internal fun hasOitExecutionStreamV2(): Boolean = LOAD_FAILURE == null &&
        MH_oitexecutiontexturev1 != null &&
        MH_oitexecutionbeginv2 != null && MH_oitexecutionappendv2 != null && MH_oitexecutionqueryv2 != null &&
        MH_oitexecutionoperationappendv1 != null &&
        MH_oitexecutionterrainreceiptv2 != null &&
        MH_oitexecutionsealv2 != null && MH_oitexecutionabortv2 != null

    internal fun D3D12OitCaptureExecutionTextureV1(ctx: Long, frame: Long, view: Long, budget: Long,
        output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutiontexturev1).invokeExact(ptr(ctx), ptr(frame), ptr(view), budget, output) as Int
    }

    internal fun D3D12OitBeginExecutionOperationV1(context: Long, frame: Long,
        input: MemorySegment, operationOut: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitoperationbeginv1).invokeExact(ptr(context), ptr(frame), input, operationOut) as Int
    }

    internal fun D3D12OitFinishExecutionOperationV1(context: Long, frame: Long,
        operation: Long, receiptCountOut: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitoperationfinishv1).invokeExact(ptr(context), ptr(frame), ptr(operation), receiptCountOut) as Int
    }

    internal fun D3D12OitCancelExecutionOperationV1(context: Long, frame: Long, operation: Long): Int {
        requireLoaded()
        return checkNotNull(MH_oitoperationcancelv1).invokeExact(ptr(context), ptr(frame), ptr(operation)) as Int
    }

    internal fun D3D12OitQueryExecutionOperationReceiptV1(context: Long, frame: Long,
        operation: Long, afterSequence: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitoperationreceiptv1).invokeExact(ptr(context), ptr(frame), ptr(operation), afterSequence, output) as Int
    }

    internal fun D3D12OitBeginExecutionStreamV2(ctx: Long, frame: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionbeginv2).invokeExact(ptr(ctx), ptr(frame), input, output) as Int
    }

    internal fun D3D12OitAppendExecutionEventV2(ctx: Long, stream: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionappendv2).invokeExact(ptr(ctx), ptr(stream), input, output) as Int
    }

    internal fun D3D12OitAppendExecutionOperationEventV1(ctx: Long, stream: Long, input: MemorySegment,
        receipt: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionoperationappendv1).invokeExact(ptr(ctx), ptr(stream), input, receipt, output) as Int
    }

    internal fun D3D12OitQueryExecutionStreamV2(ctx: Long, stream: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionqueryv2).invokeExact(ptr(ctx), ptr(stream), output) as Int
    }

    internal fun D3D12OitQueryExecutionTerrainReceiptV2(ctx: Long, stream: Long, receiptId: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionterrainreceiptv2).invokeExact(ptr(ctx), ptr(stream), receiptId, output) as Int
    }

    internal fun D3D12OitSealExecutionStreamV2(ctx: Long, stream: Long, manifest: MemorySegment,
        output: MemorySegment, result: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionsealv2).invokeExact(ptr(ctx), ptr(stream), manifest, output, result) as Int
    }

    internal fun D3D12OitAbortExecutionStreamV2(ctx: Long, stream: Long): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionabortv2).invokeExact(ptr(ctx), ptr(stream)) as Int
    }

    internal fun captureSampledTextureContentV1(ctx: Long, view: Long, budget: Long,
        ownerIdentity: MemorySegment, ownerGeneration: MemorySegment, contentVersion: MemorySegment,
        firstMip: MemorySegment, mipCount: MemorySegment, firstLayer: MemorySegment, layerCount: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitsampledcontentv1).invokeExact(ptr(ctx), ptr(view), budget,
            ownerIdentity, ownerGeneration, contentVersion, firstMip, mipCount, firstLayer, layerCount) as Int
    }

    internal fun D3D12OitBeginTerrainStageDrawV2(ctx: Long, frame: Long, input: MemorySegment, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitterrainstagebeginv2).invokeExact(ptr(ctx), ptr(frame), input, output) as Int
    }

    internal fun D3D12OitFinishTerrainStageDrawV2(ctx: Long, frame: Long, token: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitterrainstagefinishv2).invokeExact(ptr(ctx), ptr(frame), ptr(token), output) as Int
    }

    internal fun D3D12OitAbortTerrainStageDrawV2(ctx: Long, frame: Long, token: Long): Int {
        requireLoaded()
        return checkNotNull(MH_oitterrainstageabortv2).invokeExact(ptr(ctx), ptr(frame), ptr(token)) as Int
    }

    internal fun D3D12OitQueryTerrainStageReceiptV2(ctx: Long, frame: Long, receiptId: Long, output: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitterrainstagereceiptv2).invokeExact(ptr(ctx), ptr(frame), receiptId, output) as Int
    }

    internal fun hasOitTerrainReplayReadSetV2(): Boolean = LOAD_FAILURE == null &&
        MH_oitterrainreadsetregisterv2 != null && MH_oitterrainreadsetcancelv2 != null

    internal fun D3D12OitRegisterTerrainReplayReadSetV2(ctx: Long, frame: Long, token: Long,
        batch: Long, vertex: Long, vertexOffset: Long, input: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitterrainreadsetregisterv2)
            .invokeExact(ptr(ctx), ptr(frame), ptr(token), ptr(batch), ptr(vertex), vertexOffset, input) as Int
    }

    internal fun D3D12OitCancelTerrainReplayReadSetV2(ctx: Long, frame: Long, token: Long): Int {
        requireLoaded()
        return checkNotNull(MH_oitterrainreadsetcancelv2).invokeExact(ptr(ctx), ptr(frame), ptr(token)) as Int
    }

    internal fun D3D12OitSealExecutionStreamV1(ctx: Long, stream: Long, manifest: MemorySegment,
        output: MemorySegment, result: MemorySegment): Int {
        requireLoaded()
        return checkNotNull(MH_oitexecutionseal).invokeExact(ptr(ctx), ptr(stream), manifest, output, result) as Int
    }

    fun D3D12PushGraphicsConstants(ctx: Long, data: ByteBuffer): Boolean {
        requireLoaded()
        val size = data.remaining()
        require(size in 1..128 && size % 4 == 0)
        val source = MemorySegment.ofBuffer(data)
        return withScratch(size.toLong()) { temporary ->
            val packed = scratch(0, size.toLong(), temporary)
            MemorySegment.copy(source, 0, packed, 0, size.toLong())
            ok(MH_pushgraphicsconstants!!.invokeExact(ptr(ctx), packed, size) as Int)
        }
    }

    fun D3D12EnsureGraphicsState(ctx: Long): Boolean {
        requireLoaded()
        val result = MH_ensuregraphicsstate!!.invokeExact(ptr(ctx)) as Int
        check(result > 0) { "Cannot restore graphics state: ${D3D12LastError()}" }
        return result == 2
    }
    fun D3D12DestroyPipeline(pipeline: Long) {
        requireLoaded()
        try {
            MH_destroypipeline!!.invokeExact(ptr(pipeline))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Mesh shaders: only DXC can emit AS/MS (D3DCompile stops at SM 5.1), and the pipeline state
    // stream has no equivalent in the packed graphics descriptor — so native compiles too. ----
    /** Compile one entry point to DXIL; [entry] "" means "main"; null on failure. */
    fun D3D12CompileShaderDxc(source: String, entry: String, profile: String): ByteArray? {
        requireLoaded()
        val utf8 = source.toByteArray(StandardCharsets.UTF_8)
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(utf8.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(utf8, 0, src, ValueLayout.JAVA_BYTE, 0, utf8.size)
            val entrySeg = cstringUtf8(arena, entry)
            val profileSeg = cstringUtf8(arena, profile)
            // First call probes the required size (null output buffer).
            val size = MH_compileshaderdxc!!.invokeExact(
                src, utf8.size, entrySeg, profileSeg, MemorySegment.NULL, 0) as Int
            if (size <= 0) {
                return null
            }
            val out = arena.allocate(size.toLong())
            val written = MH_compileshaderdxc!!.invokeExact(
                src, utf8.size, entrySeg, profileSeg, out, size) as Int
            if (written != size) {
                return null
            }
            val bytes = ByteArray(size)
            MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, bytes, 0, size)
            return bytes
        }
    }
    /** Build an AS/MS pipeline from DXIL; 0 on failure. */
    fun D3D12CreateMeshPipeline(desc: MCDRMeshPipelineDesc, pushConstantBytes: Int = 0): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(MH_createmeshconstants!!.invokeExact(packed, pushConstantBytes) as MemorySegment)
        }
    }
    fun D3D12CreateTerrainWorkGraph(nodes: ByteArray, initialize: ByteArray, finalize: ByteArray,
                                    uniformRegisters: IntArray, budget: Long): Long {
        requireLoaded()
        require(nodes.isNotEmpty() && initialize.isNotEmpty() && finalize.isNotEmpty() && budget >= 0L)
        require(uniformRegisters.size <= 28 && uniformRegisters.all { it >= 0 })
        Arena.ofConfined().use { arena ->
            val nodeCode = arena.allocateBytes(nodes)
            val initCode = arena.allocateBytes(initialize)
            val finalCode = arena.allocateBytes(finalize)
            val registers = arena.allocateInts(uniformRegisters)
            return addr(MH_createterrainworkgraph!!.invokeExact(nodeCode, nodes.size, initCode,
                initialize.size, finalCode, finalize.size, registers, uniformRegisters.size, budget) as MemorySegment)
        }
    }

    fun D3D12VanillaBeginFrame(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_vanillabegin, ctx, input)

    fun D3D12VanillaWorldReady(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_vanillaworld, ctx, input)

    fun D3D12VanillaGuiReady(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_vanillagui, ctx, input)

    fun D3D12VanillaPublishPreparedFrame(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_vanillapublish, ctx, input)

    fun D3D12VanillaEvaluateReconstruction(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_vanillaevaluate, ctx, input)

    fun D3D12VanillaPrepareWorldOutput(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_vanillaworldoutput, ctx, input)

    fun D3D12BeginDisplayFrame(ctx: Long): Int {
        requireLoaded()
        return MH_begindisplay!!.invokeExact(ptr(ctx)) as Int
    }

    internal fun D3D12SettleDisplayFrameV1(ctx: Long, timeoutMs: Long): DisplayFrameSettlement {
        requireLoaded()
        require(timeoutMs >= 0L)
        val layout = MCDRLayout.PRESENT_RECEIPT
        return withScratch(layout.byteSize(), layout.byteAlignment()) { temporary ->
            val output = scratch(0, layout.byteSize(), temporary, layout.byteAlignment())
            output.fill(0.toByte())
            output.set(MCDRLayout.I32, 0L, 1)
            output.set(MCDRLayout.I32, 4L, layout.byteSize().toInt())
            val result = MH_settledisplay!!.invokeExact(ptr(ctx), timeoutMs, output) as Int
            check(result in -2..1) { "Display settlement rejected: $result; ${D3D12LastError()}" }
            check(output.get(MCDRLayout.I32, 0L) == 1 &&
                output.get(MCDRLayout.I32, 4L) == layout.byteSize().toInt()) { "Invalid display receipt header" }
            if (result == 0) return@withScratch DisplayFrameSettlement.Empty
            fun readLong(field: String): Long = output.get(MCDRLayout.I64,
                layout.byteOffset(MemoryLayout.PathElement.groupElement(field)))
            fun readInt(field: String): Int = output.get(MCDRLayout.I32,
                layout.byteOffset(MemoryLayout.PathElement.groupElement(field)))
            val identity = output.asSlice(layout.byteOffset(MemoryLayout.PathElement.groupElement("identity")), 32L)
            val receipt = DisplayPresentReceipt(
                readLong("serial"), identity.get(MCDRLayout.I64, 0L), identity.get(MCDRLayout.I64, 8L),
                identity.get(MCDRLayout.I64, 16L), identity.get(MCDRLayout.I64, 24L),
                readLong("providerGeneration"), readLong("historyGeneration"), readLong("commandFenceValue"),
                readLong("submitQueueFenceValue"), readLong("presentQueueFenceValue"),
                checkNotNull(DisplaySubmissionState.entries.getOrNull(readInt("submissionState"))),
                checkNotNull(DisplayPresentOutcome.entries.getOrNull(readInt("presentOutcome"))),
                readInt("dxgiStatus"), readInt("flags"), readInt("outputFrameCount"),
                checkNotNull(DisplayPresentRetirement.entries.getOrNull(readInt("retirementState"))),
            )
            check(receipt.serial > 0L && receipt.outputFrameCount >= 0 && receipt.flags and 7.inv() == 0) {
                "Invalid display receipt identity or flags"
            }
            when (result) {
                1 -> DisplayFrameSettlement.Completed(receipt)
                -1 -> DisplayFrameSettlement.TimedOut(receipt)
                else -> DisplayFrameSettlement.SubmissionUnknown(receipt)
            }
        }
    }

    fun D3D12AbortDisplayFrame(ctx: Long): Int {
        requireLoaded()
        return MH_abortdisplay!!.invokeExact(ptr(ctx)) as Int
    }

    fun D3D12PrepareDisplayFrame(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_preparedisplay, ctx, input)

    fun D3D12SubmitDisplayFrame(ctx: Long, input: MemorySegment): Int =
        vanillaFrameCall(MH_submitdisplay, ctx, input)

    fun D3D12VanillaResetFrame(ctx: Long): Int {
        requireLoaded()
        return MH_vanillareset!!.invokeExact(ptr(ctx)) as Int
    }

    data class VanillaResourceUse(
        val objectHandle: Long,
        val version: Long,
        val firstSubresource: Int = -1,
        val subresourceCount: Int = 0,
        val access: Int,
        val requiredState: Int,
        val finalState: Int = -1,
        val externalState: Int = 0,
    )

    fun D3D12VanillaBeginDeclaredPass(ctx: Long, pass: Int, uses: List<VanillaResourceUse>,
                                      graphicsEffects: Int = 3): Long {
        requireLoaded()
        require(ctx != 0L && pass > 0 && uses.isNotEmpty() && graphicsEffects and -4 == 0)
        val layout = MCDRLayout.VANILLA_RESOURCE_USE
        val maximumBytes = maxOf(checkedArrayBytes(uses.size, layout), MCDRLayout.PTR.byteSize())
        return withScratch(maximumBytes, maxOf(layout.byteAlignment(), MCDRLayout.PTR.byteAlignment())) { temporary ->
            val array = scratchArray(0, uses.size, layout, temporary)
            array.fill(0.toByte())
            uses.forEachIndexed { index, use ->
                val item = array.asSlice(index * layout.byteSize(), layout.byteSize())
                require(use.objectHandle != 0L && use.version > 0L && use.access in 1..4 &&
                    use.requiredState >= 0 && use.externalState in 0..2)
                item.set(MCDRLayout.PTR, MCDRLayout.VANILLA_USE_OBJECT, ptr(use.objectHandle))
                item.set(MCDRLayout.I64, MCDRLayout.VANILLA_USE_VERSION, use.version)
                item.set(MCDRLayout.I32, MCDRLayout.VANILLA_USE_FIRST, if (use.firstSubresource < 0) -1 else use.firstSubresource)
                item.set(MCDRLayout.I32, MCDRLayout.VANILLA_USE_COUNT, use.subresourceCount)
                item.set(MCDRLayout.I32, MCDRLayout.VANILLA_USE_ACCESS, use.access)
                item.set(MCDRLayout.I32, MCDRLayout.VANILLA_USE_REQUIRED, use.requiredState)
                item.set(MCDRLayout.I32, MCDRLayout.VANILLA_USE_FINAL, use.finalState)
                item.set(MCDRLayout.I32, MCDRLayout.VANILLA_USE_EXTERNAL, use.externalState)
            }
            val out = scratch(1, MCDRLayout.PTR.byteSize(), temporary, MCDRLayout.PTR.byteAlignment())
            out.set(MCDRLayout.PTR, 0L, MemorySegment.NULL)
            val result = MH_vanillabegindeclared!!.invokeExact(ptr(ctx), pass, array, uses.size,
                graphicsEffects, out) as Int
            check(result > 0) { "Declared pass rejected: ${D3D12LastError()}" }
            addr(out.get(MCDRLayout.PTR, 0L))
        }
    }

    fun D3D12VanillaBeginRasterPass(ctx: Long): Long {
        requireLoaded()
        require(ctx != 0L)
        return withScratch(MCDRLayout.PTR.byteSize(), MCDRLayout.PTR.byteAlignment()) { temporary ->
            val out = scratch(0, MCDRLayout.PTR.byteSize(), temporary, MCDRLayout.PTR.byteAlignment())
            out.set(MCDRLayout.PTR, 0L, MemorySegment.NULL)
            val result = MH_vanillabeginraster!!.invokeExact(ptr(ctx), out) as Int
            check(result == 1) { "Raster declaration rejected: ${D3D12LastError()}" }
            val token = addr(out.get(MCDRLayout.PTR, 0L))
            check(token != 0L) { "Raster declaration returned a null token" }
            token
        }
    }

    fun D3D12VanillaFinishDeclaredPass(ctx: Long, token: Long): Boolean {
        requireLoaded()
        require(ctx != 0L && token != 0L)
        return (MH_vanillafinishdeclared!!.invokeExact(ptr(ctx), ptr(token)) as Int) == 1
    }

    fun D3D12InvalidateReconstructionInputs(ctx: Long): Boolean {
        requireLoaded()
        return ok(MH_invalidatereconstructioninputs!!.invokeExact(ptr(ctx)) as Int)
    }

    fun D3D12PrewarmFrameprep(ctx: Long, generation: Long, flags: Int) {
        requireLoaded()
        require(ctx != 0L && generation > 0L && flags in 0..3)
        val result = MH_prewarmframeprep!!.invokeExact(ptr(ctx), generation, flags) as Int
        check(result > 0) { "Frameprep Work Graph prewarm failed: ${D3D12LastError()}" }
    }

    fun D3D12PrewarmDisplayFrame(ctx: Long, generation: Long) {
        requireLoaded()
        require(ctx != 0L && generation > 0L)
        val result = MH_prewarmdisplay!!.invokeExact(ptr(ctx), generation) as Int
        check(result > 0) { "Display Work Graph prewarm failed: ${D3D12LastError()}" }
    }

    fun D3D12PrepareFramePrograms(ctx: Long, generation: Long, flags: Int): Long {
        requireLoaded()
        require(ctx != 0L && generation > 0L && flags in 0..3)
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.PTR)
            output.set(MCDRLayout.PTR, 0L, MemorySegment.NULL)
            val result = MH_prepareframeprograms!!.invokeExact(ptr(ctx), generation, flags, output) as Int
            requireFrameProgramsSuccess("prepare", result)
            val candidate = addr(output.get(MCDRLayout.PTR, 0L))
            if (candidate == 0L) {
                throw FrameProgramsException(FrameProgramsFailure.ABI_CONTRACT, "prepare", result,
                    "Successful preparation returned a null candidate")
            }
            return candidate
        }
    }

    fun D3D12CommitFramePrograms(ctx: Long, candidate: Long) {
        requireLoaded()
        require(ctx != 0L && candidate != 0L)
        val result = MH_commitframeprograms!!.invokeExact(ptr(ctx), ptr(candidate)) as Int
        requireFrameProgramsSuccess("commit", result)
    }

    fun D3D12AbortFramePrograms(candidate: Long) {
        if (candidate == 0L) return
        requireLoaded()
        MH_abortframeprograms!!.invokeExact(ptr(candidate))
    }

    private fun requireFrameProgramsSuccess(operation: String, result: Int) {
        if (result == 1) return
        val failure = FrameProgramsFailure.entries.firstOrNull { it.resultCode == result }
            ?: FrameProgramsFailure.ABI_CONTRACT
        throw FrameProgramsException(failure, operation, result, D3D12LastError())
    }

    fun D3D12ProduceReactiveMask(ctx: Long, source0: Long, source1: Long, output: Long,
                                 width: Int, height: Int, worldEpoch: Long, realFrameId: Long,
                                 sourceVersion: Long, viewportVersion: Long): Boolean {
        requireLoaded()
        require(ctx != 0L && width > 0 && height > 0)
        require(worldEpoch > 0 && realFrameId > 0 && sourceVersion > 0 && viewportVersion > 0)
        val result = MH_producereactivemask!!.invokeExact(ptr(ctx), ptr(source0), ptr(source1), ptr(output),
            width, height, worldEpoch, realFrameId, sourceVersion, viewportVersion) as Int
        check(result >= 0) { "Native reactive mask production failed" }
        return result > 0
    }

    fun D3D12ProduceProviderMasks(ctx: Long, source0: Long, source1: Long, output: Long,
                                  compositionOutput: Long, width: Int, height: Int, outputFlags: Int,
                                  worldEpoch: Long, realFrameId: Long, sourceVersion: Long,
                                  viewportVersion: Long): Boolean {
        requireLoaded()
        require(ctx != 0L && width > 0 && height > 0 && outputFlags in 1..4)
        require((outputFlags and 1) == 0 || output != 0L)
        require((outputFlags and 2) == 0 || compositionOutput != 0L)
        require((outputFlags and 4) == 0 || output != 0L)
        require(worldEpoch > 0 && realFrameId > 0 && sourceVersion > 0 && viewportVersion > 0)
        val result = MH_produceprovidermasks!!.invokeExact(
            ptr(ctx), ptr(source0), ptr(source1), ptr(output), ptr(compositionOutput),
            width, height, outputFlags, worldEpoch, realFrameId, sourceVersion, viewportVersion
        ) as Int
        check(result >= 0) { "Native provider mask production failed" }
        return result > 0
    }

    fun D3D12ProduceProviderMasksV2(ctx: Long, input: MemorySegment): Boolean {
        requireLoaded()
        require(ctx != 0L && input.byteSize() == MCDRLayout.PROVIDER_MASK_INPUT.byteSize())
        val result = MH_produceprovidermasksv2!!.invokeExact(ptr(ctx), input) as Int
        check(result > 0) { "Native provider-mask classification failed: ${D3D12LastError()}" }
        return true
    }

    fun D3D12ProduceVrs(ctx: Long, depth: Long, motion: Long, previous: Long, output: Long,
                        width: Int, height: Int, tileSize: Int, edgePermille: Int,
                        motionPixels: Int, flags: Int, worldEpoch: Long, realFrameId: Long,
                        sourceVersion: Long, viewportVersion: Long, candidates: Long,
                        previousToCurrent: FloatArray, currentToPrevious: FloatArray): Boolean {
        requireLoaded()
        require(ctx != 0L && output != 0L && candidates != 0L && width > 0 && height > 0 && tileSize > 0)
        require((flags and 1) == 0 || depth != 0L)
        require((flags and 8) == 0 || previous != 0L)
        require((flags and 4) == 0 || (flags and 1) == 0 || motion != 0L)
        require(worldEpoch > 0 && realFrameId > 0 && sourceVersion > 0 && viewportVersion > 0)
        require(previousToCurrent.size == 16 && currentToPrevious.size == 16)
        return withScratch(128L) { temporary ->
            val matrices = scratch(0, 128L, temporary)
            for (index in 0 until 16) {
                matrices.setAtIndex(MCDRLayout.F32, index.toLong(), previousToCurrent[index])
                matrices.setAtIndex(MCDRLayout.F32, (index + 16).toLong(), currentToPrevious[index])
            }
            val result = MH_producevrs!!.invokeExact(
                ptr(ctx), ptr(depth), ptr(motion), ptr(previous), ptr(output),
                width, height, tileSize, edgePermille, motionPixels, flags,
                worldEpoch, realFrameId, sourceVersion, viewportVersion, ptr(candidates),
                matrices.asSlice(0L, 64L), matrices.asSlice(64L, 64L)
            ) as Int
            check(result >= 0) { "Native VRS production failed: ${D3D12LastError()}" }
            result > 0
        }
    }

    fun D3D12ProduceExposure(ctx: Long, color: Long, output: Long,
                            width: Int, height: Int, colorEncoding: Int, preExposure: Float): Boolean {
        requireLoaded()
        val result = MH_produceexposure!!.invokeExact(ptr(ctx), ptr(color), ptr(output),
            width, height, colorEncoding, preExposure) as Int
        check(result > 0) { "Exposure metering failed: ${D3D12LastError()}" }
        return true
    }

    fun D3D12ResetFrameprep(ctx: Long) {
        requireLoaded()
        MH_resetframeprep!!.invokeExact(ptr(ctx))
    }

    private fun vanillaFrameCall(handle: MethodHandle?, ctx: Long, input: MemorySegment): Int {
        requireLoaded()
        return handle!!.invokeExact(ptr(ctx), input) as Int
    }

    fun D3D12DestroyTerrainWorkGraph(program: Long) {
        requireLoaded()
        MH_destroyterrainworkgraph!!.invokeExact(ptr(program))
    }

    fun D3D12PrepareTerrainWorkGraph(ctx: Long, program: Long, buffers: LongArray,
                                     offsets: LongArray, lengths: LongArray,
                                     sectionCount: Int, meshletCapacity: Int): Long {
        requireLoaded()
        require(sectionCount > 0 && meshletCapacity > 0)
        require(buffers.isNotEmpty() && buffers.size <= 32 && buffers.size == offsets.size && offsets.size == lengths.size)
        val count = buffers.size
        val maximumBytes = maxOf(checkedArrayBytes(count, MCDRLayout.PTR), checkedArrayBytes(count, MCDRLayout.I64))
        return withScratch(maximumBytes, maxOf(MCDRLayout.PTR.byteAlignment(), MCDRLayout.I64.byteAlignment())) { temporary ->
            val handles = scratchArray(0, count, MCDRLayout.PTR, temporary)
            val starts = scratchArray(1, count, MCDRLayout.I64, temporary)
            val sizes = scratchArray(2, count, MCDRLayout.I64, temporary)
            for (index in buffers.indices) {
                require(buffers[index] != 0L && offsets[index] >= 0L && lengths[index] > 0L)
                handles.set(MCDRLayout.PTR, index * MCDRLayout.PTR.byteSize(), ptr(buffers[index]))
            }
            MemorySegment.copy(offsets, 0, starts, MCDRLayout.I64, 0L, count)
            MemorySegment.copy(lengths, 0, sizes, MCDRLayout.I64, 0L, count)
            addr(MH_prepareterrainworkgraph!!.invokeExact(ptr(ctx), ptr(program), handles,
                starts, sizes, count, sectionCount, meshletCapacity) as MemorySegment)
        }
    }

    fun D3D12DrawTerrainWorkGraph(ctx: Long, batch: Long, vertexBuffer: Long, vertexOffset: Long): Boolean {
        requireLoaded()
        require(vertexOffset >= 0L)
        return ok(MH_drawterrainworkgraph!!.invokeExact(ptr(ctx), ptr(batch), ptr(vertexBuffer), vertexOffset) as Int)
    }

    fun D3D12DestroyTerrainBatch(batch: Long) {
        requireLoaded()
        MH_destroyterrainbatch!!.invokeExact(ptr(batch))
    }

    fun D3D12CreateWorkGraph(dxil: ByteArray, programName: String, entryName: String,
                            recordSize: Int, bindingTriples: IntArray, backingBudget: Long): Long {
        requireLoaded()
        require(dxil.isNotEmpty() && recordSize > 0 && backingBudget >= 0)
        require(bindingTriples.size % 3 == 0 && bindingTriples.size <= 96)
        require(programName.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
        require(entryName.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
        Arena.ofConfined().use { arena ->
            val code = arena.allocateBytes(dxil)
            val bindings = arena.allocateInts(bindingTriples)
            return addr(MH_createworkgraph!!.invokeExact(1, code, dxil.size,
                cstringUtf8(arena, programName), cstringUtf8(arena, entryName), recordSize,
                bindings, bindingTriples.size / 3, backingBudget) as MemorySegment)
        }
    }

    fun D3D12DestroyWorkGraph(program: Long) {
        requireLoaded()
        MH_destroyworkgraph!!.invokeExact(ptr(program))
    }

    fun D3D12DispatchWorkGraph(ctx: Long, program: Long, records: MemorySegment,
                              recordCount: Int, recordStride: Int, buffers: LongArray,
                              offsets: LongArray, lengths: LongArray): Boolean {
        requireLoaded()
        require(recordCount >= 0 && recordStride > 0)
        require(buffers.size <= 32 && buffers.size == offsets.size && offsets.size == lengths.size)
        require(records.isNative && records.byteSize() >= Math.multiplyExact(recordCount.toLong(), recordStride.toLong()))
        val count = buffers.size
        val maximumBytes = maxOf(checkedArrayBytes(count, MCDRLayout.PTR), checkedArrayBytes(count, MCDRLayout.I64))
        return withScratch(maximumBytes, maxOf(MCDRLayout.PTR.byteAlignment(), MCDRLayout.I64.byteAlignment())) { temporary ->
            val handles = scratchArray(0, count, MCDRLayout.PTR, temporary, FfmEmptyAllocation.NULL)
            val starts = scratchArray(1, count, MCDRLayout.I64, temporary, FfmEmptyAllocation.NULL)
            val sizes = scratchArray(2, count, MCDRLayout.I64, temporary, FfmEmptyAllocation.NULL)
            for (index in buffers.indices) {
                require(offsets[index] >= 0 && lengths[index] > 0)
                handles.set(MCDRLayout.PTR, index * MCDRLayout.PTR.byteSize(), ptr(buffers[index]))
            }
            MemorySegment.copy(offsets, 0, starts, MCDRLayout.I64, 0L, count)
            MemorySegment.copy(lengths, 0, sizes, MCDRLayout.I64, 0L, count)
            ok(MH_dispatchworkgraph!!.invokeExact(ptr(ctx), ptr(program), records,
                recordCount, recordStride, handles, starts, sizes, count) as Int)
        }
    }
    /** Same as [D3D12CreateMeshPipeline], but reusing the caller-supplied `ID3D12RootSignature*` address (0 = create one). */
    /** Build a root signature from a binding layout; `meshExtras` != 0 appends b8 + t14/t15. */
    fun D3D12CreateMeshRootSignature(bindings: MemorySegment?, count: Int, meshExtras: Int, pushConstantBytes: Int = 0): Long {
        requireLoaded()
        require(count in 0..MCDRPipelineDesc.MAX_BINDINGS &&
            (count == 0 || (bindings != null && bindings.address() != 0L)))
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        val handle = MH_meshrootsigconstants!!
        val ptr = bindings ?: MemorySegment.NULL
        return handle.invokeExact(ptr, count, meshExtras, pushConstantBytes) as Long
    }
    /** Same, from a JVM (type, reg) list: type 0=CBV 1=SRV+sampler 2=SRV, reg = b#/t#. */
    fun D3D12CreateMeshRootSignature(bindings: List<Pair<Int, Int>>, meshExtras: Int = 1, pushConstantBytes: Int = 0): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        val handle = MH_meshrootsigconstants!!
        if (bindings.isEmpty()) {
            return handle.invokeExact(MemorySegment.NULL, 0, meshExtras, pushConstantBytes) as Long
        }
        Arena.ofConfined().use { arena ->
            val segment = arena.allocateBindingPairs(bindings)
            return handle.invokeExact(segment, bindings.size, meshExtras, pushConstantBytes) as Long
        }
    }
    /** Release a root signature from [D3D12CreateMeshRootSignature]; 0 is safe. */
    fun D3D12ReleaseRootSignature(handle: Long) {
        if (handle == 0L) return
        MH_meshrootsigrelease?.invokeExact(handle)
    }
    fun D3D12CreateMeshPipelineWithRootSignature(desc: MCDRMeshPipelineDesc, rootSignature: Long, pushConstantBytes: Int = 0): Long {
        requireLoaded()
        require(pushConstantBytes in 0..128 && pushConstantBytes % 4 == 0)
        val handle = MH_createmeshrootconstants!!
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(handle.invokeExact(packed, rootSignature, pushConstantBytes) as MemorySegment)
        }
    }
    /** `DispatchMesh(x,y,z)`; needs a mesh pipeline bound and an open render pass. */
    fun D3D12DispatchMesh(ctx: Long, x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_dispatchmesh!!.invokeExact(ptr(ctx), x, y, z) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /**
     * One `DispatchMesh(meshletCount)` for a whole batch. Binding contract (`meshExtras = 1`):
     * b8 = [globals] (256-aligned, first dword = meshletCount), t14 vertex pool (28 B/vertex),
     * t15 section table (32 B), t16 meshlet table (32 B) — all RAW SRVs, no index buffer.
     */
    fun D3D12MeshDrawMeshlets(ctx: Long, vertexBuffer: Long, vertexOffset: Long, sections: Long,
                              sectionsOffset: Long, sectionsLength: Long, meshlets: Long, meshletsOffset: Long,
                              meshletsLength: Long, globals: Long, globalsOffset: Long,
                              meshletCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_meshdrawmeshlets!!.invokeExact(
                ptr(ctx), ptr(vertexBuffer), vertexOffset, ptr(sections), sectionsOffset, sectionsLength,
                ptr(meshlets), meshletsOffset, meshletsLength, ptr(globals), globalsOffset, meshletCount
            ) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Compute: fixed root signature [0] root UAV u0, [1] root constants b0 (8 dwords), matching
    // [D3D12DispatchCompute]; Dispatch() on a DIRECT list is legal, no compute queue needed. ----
    /** Build the compute PSO from `cs_6_x` DXIL; 0 on failure. */
    fun D3D12CreateComputePipeline(dxil: ByteArray): Long {
        requireLoaded()
        require(dxil.isNotEmpty())
        val handle = MH_createcomputepipeline ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocateBytes(dxil)
            return addr(handle.invokeExact(src, dxil.size) as MemorySegment)
        }
    }

    internal fun D3D12CreateTerrainTileBinningPipeline(dxil: ByteArray): Long {
        requireLoaded()
        require(dxil.isNotEmpty())
        Arena.ofConfined().use { arena ->
            val code = arena.allocateBytes(dxil)
            return addr(MH_createterrantilebinningpipeline!!.invokeExact(code, dxil.size) as MemorySegment)
        }
    }

    /**
     * `Dispatch(x,y,z)` on the open DIRECT list. [uavTexture] must carry `USAGE_STORAGE`(32);
     * native brackets it with COMMON -> UNORDERED_ACCESS -> COMMON, so the caller tracks no state.
     * @param constants 0..8 dwords for b0
     */
    fun D3D12DispatchCompute(ctx: Long, pipeline: Long, uavTexture: Long, constants: IntArray,
                             x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        val handle = MH_dispatchcompute ?: return false
        return withComputeConstants(constants) { encoded, count ->
            ok(handle.invokeExact(ptr(ctx), ptr(pipeline), ptr(uavTexture), encoded, count, x, y, z) as Int)
        }
    }

    /** NUL-terminated UTF-8 copy in `arena`; never called with null. */
    /**
     * SRI compute PSO; root signature is fixed: [0] table (t0/t1 SRVs), [1] table (u0 UAV),
     * [2] root constants b0 (8 dwords). Typed UAV/SRV **must** sit in a descriptor table — as root
     * descriptors CreateComputePipelineState returns E_INVALIDARG.
     */
    fun D3D12CreateComputePipelineShadingRate(dxil: ByteArray): Long {
        requireLoaded()
        require(dxil.isNotEmpty())
        val handle = MH_createcomputepipelineshadingrate ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocateBytes(dxil)
            return addr(handle.invokeExact(src, dxil.size) as MemorySegment)
        }
    }

    /**
     * `R8_UINT` shading-rate image; the arguments are **tile counts** (ceil(pixel /
     * ShadingRateImageTileSize), NVIDIA 16). 0 on failure, including VRS tier < 2.
     */
    fun D3D12CreateShadingRateImage(tilesX: Int, tilesY: Int): Long {
        requireLoaded()
        val handle = MH_createshadingrateimage ?: return 0L
        return addr(handle.invokeExact(tilesX, tilesY) as MemorySegment)
    }

    /**
     * Regenerate the SRI image from last frame's depth (t0), motion (t1) and image (t2) into u0;
     * with no history pass the current image and clear the temporal-clamp bit (bit1 of dword 8).
     */
    fun D3D12DispatchComputeShadingRate(ctx: Long, pipeline: Long, srvDepth: Long, srvMotion: Long,
                                        srvPrevious: Long, image: Long, constants: IntArray,
                                        x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        val handle = MH_dispatchcomputeshadingrate ?: return false
        return withComputeConstants(constants) { encoded, count ->
            ok(handle.invokeExact(ptr(ctx), ptr(pipeline), ptr(srvDepth), ptr(srvMotion),
                ptr(srvPrevious), ptr(image), encoded, count, x, y, z) as Int)
        }
    }

    /** Generic 3xSRV + 1xUAV + b0(8 int) compute pass — the SRI signature, same native implementation. */
    fun D3D12CreateComputePipelineTexture3(dxil: ByteArray): Long {
        requireLoaded()
        require(dxil.isNotEmpty())
        val handle = MH_createtexture3 ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocateBytes(dxil)
            return addr(handle.invokeExact(src, dxil.size) as MemorySegment)
        }
    }

    /** 1 = dispatched. */
    fun D3D12DispatchComputeTexture3(ctx: Long, pipeline: Long, srv0: Long, srv1: Long, srv2: Long,
                                     image: Long, constants: IntArray, x: Int, y: Int, z: Int): Int {
        requireLoaded()
        val handle = MH_dispatchtexture3 ?: return Int.MIN_VALUE
        return withComputeConstants(constants) { encoded, count ->
            handle.invokeExact(ptr(ctx), ptr(pipeline), ptr(srv0), ptr(srv1), ptr(srv2),
                ptr(image), encoded, count, x, y, z) as Int
        }
    }

    /** Attach the SRI image to the open list (`image == 0L` detaches); native moves it COMMON -> SHADING_RATE_SOURCE. */
    fun D3D12SetShadingRateImage(ctx: Long, image: Long): Boolean {
        requireLoaded()
        val handle = MH_setshadingrateimage ?: return false
        return ok(handle.invokeExact(ptr(ctx), ptr(image)) as Int)
    }

    private fun cstringUtf8(arena: Arena, s: String): MemorySegment {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        val seg = arena.allocate(bytes.size + 1L)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.size)
        seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0.toByte())
        return seg
    }
    // ---- Surface (DXGI swapchain) ----
    /** DXGI swapchain for a native HWND; 0 on failure. */
    fun D3D12CreateSurface(hwnd: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createsurface!!.invokeExact(hwnd) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** PresentMode ordinals (IMMEDIATE=0 MAILBOX=1 FIFO=2 FIFO_RELAXED=3); DXGI FLIP supports all but MAILBOX. */
    fun D3D12SurfacePresentModes(): IntArray {
        requireLoaded()
        try {
            val total = MH_surfacepresentmodes!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (total <= 0) {
                return IntArray(0)
            }
            return withScratch(checkedArrayBytes(total, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
                val buf = scratchArray(0, total, MCDRLayout.I32, temporary)
                val written = MH_surfacepresentmodes!!.invokeExact(buf, total) as Int
                val count = minOf(total, written.coerceAtLeast(0))
                val out = IntArray(count)
                for (index in 0 until count) {
                    out[index] = buf.get(MCDRLayout.I32, index * 4L) as Int
                }
                out
            }
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ResizeBuffers to width x height with the given present mode. */
    /** Real exclusive fullscreen; false keeps the composited picture. */
    fun D3D12SetExclusiveFullscreen(surface: Long, on: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_setexclusivefullscreen!!.invokeExact(ptr(surface), if (on) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12ConfigureSurface(surface: Long, width: Int, height: Int, presentMode: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_configuresurface!!.invokeExact(ptr(surface), width, height, presentMode) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Acquire the next back buffer. */
    fun D3D12AcquireSurface(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_acquiresurface!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Copy `texture` into the acquired back buffer on the open list. */
    fun D3D12BlitSurface(ctx: Long, surface: Long, texture: Long) {
        requireLoaded()
        try {
            MH_blitsurface!!.invokeExact(ptr(ctx), ptr(surface), ptr(texture))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12PresentSurface(surface: Long) {
        requireLoaded()
        try {
            MH_presentsurface!!.invokeExact(ptr(surface))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Raw `ID3D12Resource*` of the acquired back buffer (deliberately not a cached `Dx12Object*`). */
    fun D3D12GetBackBufferHandle(surface: Long): Long {
        requireLoaded()
        try {
            return MH_getbackbufferhandle!!.invokeExact(ptr(surface)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Whether the last present reported a suboptimal status. */
    fun D3D12IsSurfaceSuboptimal(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_issurfacesuboptimal!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 3x3 sampled pixels as int[36] (9 px x RGBA); null on failure. Diagnostic. */
    fun D3D12ReadbackTexturePixels(texHandle: Long): IntArray? {
        return readback36(MH_readbacktexturepixels, texHandle, "dx12ReadbackTexturePixels")
    }
    /**
     * Readback with the **real** current state. The stateless variant assumes COMMON, which only
     * holds for textures the GPU never wrote: a DLSS output stops in COPY_SOURCE or
     * UNORDERED_ACCESS, and a COMMON barrier from there is invalid — it read back as zeros.
     * @param fromState must come from [D3D12DlssOutputState]; it is restored afterwards.
     */
    fun D3D12ReadbackTexturePixelsIn(texHandle: Long, fromState: Int): IntArray? {
        requireLoaded()
        val handle = MH_readbacktexturepixelsin ?: return null
        val count = 36
        return withScratch(checkedArrayBytes(count, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            val buf = scratchArray(0, count, MCDRLayout.I32, temporary)
            try {
                val rc = handle.invokeExact(ptr(texHandle), fromState, buf) as Int
                if (rc == 0) return@withScratch null
                IntArray(count) { index -> buf.getAtIndex(MCDRLayout.I32, index.toLong()) }
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }
    /** State the DLSS output stopped in at the end of last frame; 0 = no handover this frame, do not sample. */
    fun D3D12DlssOutputState(): Int {
        requireLoaded()
        val handle = MH_dlssoutputstate ?: return 0
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            0
        }
    }
    private fun readback36(handle: MethodHandle?, obj: Long, what: String): IntArray? {
        requireLoaded()
        val count = 36
        return withScratch(checkedArrayBytes(count, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            val buf = scratchArray(0, count, MCDRLayout.I32, temporary)
            try {
                val rc = handle!!.invokeExact(ptr(obj), buf) as Int
                if (rc == 0) return@withScratch null
                IntArray(count) { index -> buf.getAtIndex(MCDRLayout.I32, index.toLong()) }
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }
    fun D3D12DestroySurface(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_destroysurface!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Draw commands ----
    internal fun D3D12SetRasterMotionTarget(ctx: Long, color: Long, depth: Long, motion: Long) {
        requireLoaded()
        val result = MH_setrastermotiontarget!!.invokeExact(ptr(ctx), ptr(color), ptr(depth), ptr(motion)) as Int
        check(result == 1) { "Raster motion attachment failed: ${D3D12LastError()}" }
    }

    internal fun D3D12SetAdditionalColorTarget(ctx: Long, target: Long) {
        requireLoaded()
        val result = MH_setadditionalcolortarget!!.invokeExact(ptr(ctx), ptr(target)) as Int
        check(result == 1) { "Additional color attachment failed: ${D3D12LastError()}" }
    }

    /** `hasDepth` selects the with/without-depth PSO variant. */
    fun D3D12SetPipeline(ctx: Long, pipeline: Long, hasDepth: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_setpipeline!!.invokeExact(ptr(ctx), ptr(pipeline), if (hasDepth) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Scissor rect on the open pass. */
    fun D3D12SetScissor(ctx: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setscissor!!.invokeExact(ptr(ctx), x, y, width, height) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 1 = set, 0 = failed. */
    fun D3D12SetViewport(ctx: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setviewport!!.invokeExact(ptr(ctx), x, y, width, height) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** [slot] 0..15. */
    fun D3D12SetVertexBuffer(ctx: Long, slot: Int, buffer: Long, offset: Long, stride: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setvertexbuffer!!.invokeExact(ptr(ctx), slot, ptr(buffer), offset, stride) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** `indexType` 0=SHORT(R16_UINT) 1=INT(R32_UINT). */
    fun D3D12SetIndexBuffer(ctx: Long, buffer: Long, indexType: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setindexbuffer!!.invokeExact(ptr(ctx), ptr(buffer), indexType) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Write the core's descriptors into this frame's transient heap and bind root table 0. Parallel arrays, one entry per binding: 0=CBV 1=SRV(texture view) 2=SRV(texel buffer). */
    fun D3D12PushDescriptors(ctx: Long, types: IntArray?, buffers: LongArray?,
                             offsets: LongArray?, lengths: LongArray?, texelFormats: IntArray?, views: LongArray?): Boolean {
        requireLoaded()
        val count = if (types == null) 0 else types.size
        if (count == 0) {
            // A pipeline with no bindings is a no-op, not a failure.
            return true
        }
        val stride = MCDRLayout.BINDING_SLOT.byteSize()
        return withScratch(checkedArrayBytes(count, MCDRLayout.BINDING_SLOT), MCDRLayout.BINDING_SLOT.byteAlignment()) { temporary ->
            val slots = scratchArray(0, count, MCDRLayout.BINDING_SLOT, temporary, FfmEmptyAllocation.NON_NULL)
            for (index in 0 until count) {
                val base = index * stride
                slots.set(MCDRLayout.I32, base, types!![index])
                slots.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TEXEL_FORMAT, if (texelFormats == null || index >= texelFormats.size) 0 else texelFormats[index])
                slots.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_BUFFER, ptr(if (buffers == null || index >= buffers.size) 0L else buffers[index]))
                slots.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_VIEW, ptr(if (views == null || index >= views.size) 0L else views[index]))
                slots.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_OFFSET, if (offsets == null || index >= offsets.size) 0L else offsets[index])
                slots.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_LENGTH, if (lengths == null || index >= lengths.size) 0L else lengths[index])
            }
            try {
                ok(MH_pushdescriptors!!.invokeExact(ptr(ctx), slots, count) as Int)
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }
    /** DrawIndexedInstanced on the open pass. */
    fun D3D12DrawIndexed(ctx: Long, indexCount: Int, instanceCount: Int,
                         firstIndex: Int, baseVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindexed!!.invokeExact(ptr(ctx), indexCount, instanceCount,
                firstIndex, baseVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun drawInts(slot: Int, values: IntArray, count: Int, temporary: Arena?): MemorySegment {
        require(count >= 0 && count <= values.size)
        val encoded = scratch(slot, checkedArrayBytes(count, MCDRLayout.I32).coerceAtLeast(4L),
            temporary, MCDRLayout.I32.byteAlignment())
        MemorySegment.copy(values, 0, encoded, MCDRLayout.I32, 0, count)
        return encoded
    }

    fun D3D12MultiDrawIndexedPacked(ctx: Long, parameters: IntArray, instanceCount: Int,
                                    firstInstance: Int, drawCount: Int): Boolean {
        requireLoaded()
        require(drawCount >= 0 && drawCount <= parameters.size / 3)
        val parameterCount = Math.multiplyExact(drawCount, 3)
        return withScratch(checkedArrayBytes(parameterCount, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            ok(MH_multiDrawIndexedPacked!!.invokeExact(ptr(ctx), drawInts(0, parameters, parameterCount, temporary),
                instanceCount, firstInstance, drawCount) as Int)
        }
    }

    fun D3D12MultiDrawIndexedSeparate(ctx: Long, firstIndices: IntArray, indexCounts: IntArray,
                                      baseVertices: IntArray, instanceCount: Int,
                                      firstInstance: Int, drawCount: Int): Boolean {
        requireLoaded()
        return withScratch(checkedArrayBytes(drawCount, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            ok(MH_multiDrawIndexedSeparate!!.invokeExact(ptr(ctx), drawInts(0, firstIndices, drawCount, temporary),
                drawInts(1, indexCounts, drawCount, temporary), drawInts(2, baseVertices, drawCount, temporary),
                instanceCount, firstInstance, drawCount) as Int)
        }
    }

    fun D3D12MultiDrawPacked(ctx: Long, parameters: IntArray, instanceCount: Int,
                             firstInstance: Int, drawCount: Int): Boolean {
        requireLoaded()
        require(drawCount >= 0 && drawCount <= parameters.size / 2)
        val parameterCount = Math.multiplyExact(drawCount, 2)
        return withScratch(checkedArrayBytes(parameterCount, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            ok(MH_multiDrawPacked!!.invokeExact(ptr(ctx), drawInts(0, parameters, parameterCount, temporary),
                instanceCount, firstInstance, drawCount) as Int)
        }
    }

    fun D3D12MultiDrawSeparate(ctx: Long, firstVertices: IntArray, vertexCounts: IntArray,
                               drawCount: Int): Boolean {
        requireLoaded()
        return withScratch(checkedArrayBytes(drawCount, MCDRLayout.I32), MCDRLayout.I32.byteAlignment()) { temporary ->
            ok(MH_multiDrawSeparate!!.invokeExact(ptr(ctx), drawInts(0, firstVertices, drawCount, temporary),
                drawInts(1, vertexCounts, drawCount, temporary), drawCount) as Int)
        }
    }

    fun D3D12MultiDrawBound(ctx: Long, indexed: Boolean, parameters: IntArray, instanceCount: Int,
        firstInstance: Int, drawCount: Int, types: IntArray, buffers: LongArray, offsets: LongArray,
        lengths: LongArray, texelFormats: IntArray, views: LongArray, overrideIndices: IntArray,
        overrideBuffers: LongArray, overrideOffsets: LongArray, overrideLengths: LongArray): Boolean {
        requireLoaded()
        require(drawCount >= 0 && drawCount <= parameters.size / 3)
        if (drawCount == 0) return true
        val count = types.size
        require(buffers.size >= count && offsets.size >= count && lengths.size >= count &&
            texelFormats.size >= count && views.size >= count)
        require(overrideIndices.all { it in 0 until count && (types[it] == 0 || types[it] == 2) })
        val overrideEntries = Math.multiplyExact(drawCount, overrideIndices.size)
        require(overrideBuffers.size >= overrideEntries && overrideOffsets.size >= overrideEntries &&
            overrideLengths.size >= overrideEntries)
        val stride = MCDRLayout.BINDING_SLOT.byteSize()
        val drawParameterCount = Math.multiplyExact(drawCount, 3)
        val maximumBytes = maxOf(
            maxOf(checkedArrayBytes(drawParameterCount, MCDRLayout.I32), checkedArrayBytes(count, MCDRLayout.BINDING_SLOT)),
            maxOf(checkedArrayBytes(overrideIndices.size, MCDRLayout.I32), checkedArrayBytes(overrideEntries, MCDRLayout.BINDING_SLOT)))
        return withScratch(maximumBytes, maxOf(MCDRLayout.I32.byteAlignment(), MCDRLayout.BINDING_SLOT.byteAlignment())) { temporary ->
            val draws = drawInts(0, parameters, drawParameterCount, temporary)
            val shared = scratchArray(1, count, MCDRLayout.BINDING_SLOT, temporary, FfmEmptyAllocation.NON_NULL)
            for (index in 0 until count) {
                val base = index * stride
                shared.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TYPE, types[index])
                shared.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TEXEL_FORMAT, texelFormats[index])
                shared.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_BUFFER, ptr(buffers[index]))
                shared.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_VIEW, ptr(views[index]))
                shared.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_OFFSET, offsets[index])
                shared.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_LENGTH, lengths[index])
            }
            val indices = drawInts(2, overrideIndices, overrideIndices.size, temporary)
            val bindings = scratchArray(3, overrideEntries, MCDRLayout.BINDING_SLOT, temporary, FfmEmptyAllocation.NON_NULL)
            for (index in 0 until overrideEntries) {
                val source = overrideIndices[index % overrideIndices.size]
                val base = index * stride
                bindings.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TYPE, types[source])
                bindings.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TEXEL_FORMAT, texelFormats[source])
                bindings.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_BUFFER, ptr(overrideBuffers[index]))
                bindings.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_VIEW, MemorySegment.NULL)
                bindings.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_OFFSET, overrideOffsets[index])
                bindings.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_LENGTH, overrideLengths[index])
            }
            ok(MH_multiDrawBound!!.invokeExact(ptr(ctx), if (indexed) 1 else 0, draws,
                instanceCount, firstInstance, drawCount, shared, count, indices, overrideIndices.size,
                bindings) as Int)
        }
    }
    /** DrawInstanced on the open pass. */
    fun D3D12Draw(ctx: Long, vertexCount: Int, instanceCount: Int,
                  firstVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_draw!!.invokeExact(ptr(ctx), vertexCount, instanceCount,
                firstVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ExecuteIndirect (DrawIndexedInstanced) with the given command buffer. */
    fun D3D12DrawIndexedIndirect(ctx: Long, commands: Long, offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindexedindirect!!.invokeExact(ptr(ctx), ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ExecuteIndirect (DrawInstanced) with the given command buffer. */
    fun D3D12DrawIndirect(ctx: Long, commands: Long, offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindirect!!.invokeExact(ptr(ctx), ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Fence manager (SetEventOnCompletion + monitor thread, no polling) ----
    /** ID3D12Fence + monitor thread; 0 on failure. */
    fun D3D12AsyncFenceCreate(): Long {
        requireLoaded()
        try {
            return addr(MH_asyncfencecreate!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12AsyncFenceDestroy(mgr: Long) {
        requireLoaded()
        try {
            MH_asyncfencedestroy!!.invokeExact(ptr(mgr))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** queue->Signal(fence, value). */
    fun AsyncFenceSignal(mgr: Long, value: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfencesignal!!.invokeExact(ptr(mgr), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Block until `value`; `timeoutMs == 0` waits forever. */
    fun AsyncFenceWait(mgr: Long, value: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfencewait!!.invokeExact(ptr(mgr), value, timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking. */
    fun D3D12AsyncFenceIsComplete(mgr: Long, value: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfenceiscomplete!!.invokeExact(ptr(mgr), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Fires once when the fence reaches `value`, then releases the handle; `timeoutMs == 0` = no timeout, and the callback runs outside the registry lock. */
    fun AsyncFenceRegisterCallback(mgr: Long, value: Long,
                                   callback: D3D12FenceCallback?, timeoutMs: Long): Boolean {
        requireLoaded()
        if (callback == null) {
            return false
        }
        val handle = NEXT_CALLBACK_HANDLE.getAndIncrement()
        CALLBACKS.put(handle, CallbackEntry(callback))
        try {
            val accepted = ok(MH_asyncfenceregistercallback!!.invokeExact(
                ptr(mgr), value, FENCE_STUB!!, handle.toLong(), timeoutMs) as Int)
            if (!accepted) {
                CALLBACKS.remove(handle)
            }
            return accepted
        } catch (t: Throwable) {
            CALLBACKS.remove(handle)
            throw unexpected(t)
        }
    }
    private val CALLBACKS = ConcurrentHashMap<Int, CallbackEntry>()
    private val NEXT_CALLBACK_HANDLE = AtomicInteger(1)
    private class CallbackEntry(val callback: D3D12FenceCallback) {
        val fired = AtomicBoolean()
    }
    /** Upcall target: **never let an exception escape** — that terminates the JVM abruptly. */
    @JvmStatic
    private fun onFenceComplete(userHandle: Long, value: Long, completed: Int) {
        try {
            val handle = userHandle.toInt()
            val entry = CALLBACKS.get(handle) ?: return
            if (completed != 0 && !entry.fired.compareAndSet(false, true)) {
                return  // already fired for this registration
            }
            if (completed != 0) {
                CALLBACKS.remove(handle)
            }
            entry.callback.onFenceComplete(value, completed != 0)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("fence callback failed: ${t}")
        }
    }
    // ---- FFM linker bootstrap ----
    private fun bindAll() {
        bindFenceStub()
        bindMetaAndDevice()
        bindCommand()
        bindPipelineSurfaceDraw()
        bindAsync()
        // The JVM owns the verbose switch and must push it — native no longer reads the environment.
        D3D12SetLogVerbose(LOG_VERBOSE)
        // Start from the built-in default; IllusionLogger.applyFromConfig() pushes the configured value later.
        D3D12SetShutdownTraceAlways(true)
    }
    private fun bindMetaAndDevice() {
        MH_abiversion = skip("mcdrABIVersion", fd(MCDRLayout.I32))
        MH_abilayout = skip("mcdrABILayout", fdv(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_lasterror = skip("mcdrLastError", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setlogverbose = skip("mcdrSetLogVerbose", fdv(MCDRLayout.I32))
        // 0=error 1=warning 2=info 3=debug; a DLL without it falls back to the boolean mcdrSetLogVerbose.
        // mcdr_dx12SetEnvironment only serves native's existing getenv() read points — never a new feature.
        MH_setenvironment = skipOptional("mcdr_dx12SetEnvironment", fdv(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_setloglevel = skipOptional("mcdrSetLogLevel", fdv(MCDRLayout.I32))
        // Config [logging] shutdownTrace; a DLL without it keeps the built-in always-on behaviour.
        MH_setshutdowntrace = skipOptional("mcdrSetShutdownTraceAlways", fdv(MCDRLayout.I32))
        // Config [logging] nativeLogFile — launchers do not persist the child's stdout/stderr.
        MH_setlogfile = skipOptional("mcdrSetLogFile", fd(MCDRLayout.I32, MCDRLayout.PTR))
        // **Must run before the first D3D12 device exists** — the layer hooks D3D12 when it loads.
        MH_setpixcapture = skipOptional("mcdrSetPixCapture", fd(MCDRLayout.I32, MCDRLayout.I32))
        // Config [debug] pixDirectory: the DLL itself or its directory; before mcdrSetPixCapture.
        MH_setpixdirectory = skipOptional(
            "mcdrSetPixDirectory",
            fd(MCDRLayout.I32, MCDRLayout.PTR),
        )
        // PIXBeginCapture from the process itself — the only way to get a file at a chosen path.
        MH_startpixtiming = skipOptional(
            "mcdrStartPixTimingCapture",
            fd(MCDRLayout.I32, MCDRLayout.PTR),
        )
        MH_stoppixtiming = skipOptional("mcdrStopPixTimingCapture", fd(MCDRLayout.I32))
        // Raw D3D12_SHADING_RATE, -1 = off; capability is judged natively (tier 0 / AdditionalShadingRates).
        MH_setshadingrate = skipOptional("mcdrSetShadingRate", fd(MCDRLayout.I32, MCDRLayout.I32))
        // 0=OFF 1=SMAA 2=TAA 3=DLAA (same numbering as the video option); DLAA is accepted but degrades to OFF.
        MH_setantialiasing = skipOptional("mcdrSetAntiAliasing", fd(MCDRLayout.I32, MCDRLayout.I32))
        // Scope shading rate (ctx + rate); no export => MIN_VALUE and the JVM stays non-scoped.
        MH_setshadingratenow =
            skipOptional("mcdr_dx12SetShadingRateNow", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setshadingratecombiners =
            skipOptional("mcdr_dx12SetShadingRateCombiners", fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        // Create and Evaluate hit the same native implementation; bind Evaluate only when evaluating per frame.
        MH_querydlss = skipOptional("mcdr_dx12QueryDlss", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        // The 8 SR entry points; no export => MIN_VALUE, callers degrade silently.
        MH_srpick = skipOptional("mcdr_dx12SrPick", fd(MCDRLayout.I32, MCDRLayout.I32))
        MH_fgprepare = skipOptional("mcdr_dx12FgPrepare", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_fgabort = skipOptional("mcdr_dx12FgAbort", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_commitpreparedconfiguration = skipOptional("mcdr_dx12CommitPreparedConfiguration", fd(MCDRLayout.I32, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32))
        MH_srprepare = skipOptional("mcdr_dx12SrPrepare", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_srcommit = skipOptional("mcdr_dx12SrCommit", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_srabort = skipOptional("mcdr_dx12SrAbort", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_srquerycaps = skipOptional("mcdr_dx12SrQueryCaps", fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_srcreate = skipOptional(
            "mcdr_dx12SrCreate",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32)
        )
        MH_srresize = skipOptional(
            "mcdr_dx12SrResize",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32)
        )
        MH_srjitter = skipOptional("mcdr_dx12SrJitter", fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_srdestroy = skipOptional("mcdr_dx12SrDestroy", fdv())
        MH_srjitterphase =
            skipOptional(
                "mcdr_dx12SrJitterPhaseCount",
                fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32),
            )
        MH_srquery = skipOptional("mcdr_dx12SrQuery", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        // FG entry points. Still skipOptional: an ABI-version mismatch throws first, so a half-upgraded pair degrades.
        MH_fgpick = skipOptional("mcdr_dx12FgPick", fd(MCDRLayout.I32, MCDRLayout.I32))
        MH_fgquerycaps = skipOptional(
            "mcdr_dx12FgQueryCaps",
            fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR)
        )
        MH_fgsetmode = skipOptional("mcdr_dx12FgSetMode", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_fgpresented = skipOptional("mcdr_dx12FgPresented", fd(MCDRLayout.I32))
        MH_fgstatus = skipOptional("mcdr_dx12FgStatus", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_fgstate = skipOptional("mcdr_dx12FgState", fd(MCDRLayout.I32))
        MH_fgsetuitexture = skipOptional("mcdr_dx12FgSetUiTexture", fdv(MCDRLayout.PTR))
        // hudless present + who composites the UI; old DLL => MIN_VALUE, FrameGeneration keeps compositing itself.
        MH_fgsetuicomposition = skipOptional("mcdr_dx12FgSetUiComposition", fdv(MCDRLayout.I32))
        MH_fguicompositionready = skipOptional("mcdr_dx12FgUiCompositionReady", fd(MCDRLayout.I32))
        // DLSS switch decoupled from the AA mode; no export => MIN_VALUE, the legacy bridge falls back to aaMode == 4.
        MH_setdlssenabled = skipOptional("mcdr_dx12SetDlssEnabled", fd(MCDRLayout.I32, MCDRLayout.I32))
        // **Before** vanilla clearDepthTexture: afterwards the depth is 0.0 (reverse-Z far) and the depth term becomes a constant.
        MH_snapshotdlssdepth = skipOptional("mcdr_dx12SnapshotDlssDepth",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        // 0=Performance 1=Balanced 2=Quality; no export => MIN_VALUE, native still picks by ratio.
        MH_setdlssquality = skipOptional("mcdr_dx12SetDlssQuality", fd(MCDRLayout.I32, MCDRLayout.I32))
        MH_setdlssrenderpreset =
            skipOptional("mcdr_dx12SetDlssRenderPreset", fd(MCDRLayout.I32, MCDRLayout.I32))
        // JVM-created DLSS output texture as a Dx12Object* handle; no export => MIN_VALUE keeps the private UAV path.
        MH_reflexframebegin = skipOptional("mcdr_dx12ReflexFrameBegin", fdv())
        MH_reflexmarker = skipOptional("mcdr_dx12ReflexMarker", fdv(MCDRLayout.I32))
        MH_meshsupport = skipOptional("mcdr_dx12QueryMeshSupport", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_setreflexmode = skipOptional("mcdr_dx12SetReflexMode", fdv(MCDRLayout.I32))
        MH_setdlssgmode = skipOptional("mcdr_dx12SetDlssgMode", fdv(MCDRLayout.I32, MCDRLayout.I32))
    MH_getdlssgpresented = skipOptional("mcdr_dx12GetDlssgPresented", fd(MCDRLayout.I32))
        MH_setreflexframelimit = skipOptional("mcdr_dx12SetReflexFrameLimit", fdv(MCDRLayout.I32))
        // **Before** device/swapchain creation; pushed while the config is read.
        MH_sethdrmode = skipOptional("mcdr_dx12SetHdrMode", fdv(MCDRLayout.I32))
        MH_setraytracingmode = skipOptional("mcdr_dx12SetRaytracingMode", fdv(MCDRLayout.I32))
        // State only; native picks the G-Buffer / RT branch once device + surface are ready.
        MH_setswapchainoptions =
            skipOptional("mcdr_dx12SetSwapchainOptions", fdv(MCDRLayout.I32, MCDRLayout.I32))
        // Applies at the next swapchain build.
        MH_gethdrappliedmode = skipOptional("mcdr_dx12GetHdrAppliedMode", fd(MCDRLayout.I32))
        MH_reflexavailable = skipOptional("mcdr_dx12ReflexAvailable", fd(MCDRLayout.I32))
        MH_dlssavailable = skipOptional("mcdr_dx12DlssAvailable", fd(MCDRLayout.I32))
        MH_setdlssoutput = skipOptional("mcdr_dx12SetDlssOutput",
            fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_createdevice = skip("mcdr_dx12CreateDevice", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_destroydevice = skip("mcdr_dx12DestroyDeviceChecked", fd(MCDRLayout.I32))
        MH_createtexture = skip("mcdr_dx12CreateTexture", fd(
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_createbuffer = skip("mcdr_dx12CreateBuffer", fd(MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I64))
        MH_createsampler = skip("mcdr_dx12CreateSampler", fd(
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.F32
        ))
        MH_createsamplerbias = skipOptional("mcdr_dx12CreateSamplerWithMipBias", fd(
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.F32,
            MCDRLayout.F32
        ))
        MH_createtextureview = skip("mcdr_dx12CreateTextureView", fd(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_destroyresource = skip("mcdr_dx12DestroyResource", fdv(MCDRLayout.PTR))
        MH_mapbuffer = skip("mcdr_dx12MapBuffer", fd(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_unmapbuffer = skip("mcdr_dx12UnmapBuffer", fdv(MCDRLayout.PTR))
        MH_enumerateadapters = skip("mcdr_dx12EnumerateAdapters", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_getadapterinfo = skip("mcdr_dx12GetAdapterInfo", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_getqueuehandle = skip("mcdr_dx12GetQueueHandle", fd(MCDRLayout.I64))
        MH_getdevicehandle = skip("mcdr_dx12GetDeviceHandle", fd(MCDRLayout.I64))
        MH_createhiddenwindow = skip("mcdr_dx12CreateHiddenWindow", fd(MCDRLayout.I64, MCDRLayout.I32, MCDRLayout.I32))
        MH_destroyhiddenwindow = skip("mcdr_dx12DestroyHiddenWindow", fdv(MCDRLayout.I64))
    }
    private fun bindCommand() {
        // Native renamed these two exports (the other 123 are unchanged).
        MH_gettimestampfrequency = skip("MCDR_D3D12_get_timestamp_frequency", fd(MCDRLayout.I64))
        MH_createcommandencoder = skip("MCDR_D3D12_create_command_encoder", fd(MCDRLayout.PTR))
        MH_destroycommandencoder = skip("mcdr_dx12DestroyCommandEncoder", fdv(MCDRLayout.PTR))
        MH_begincommandlist = skip("mcdr_dx12BeginCommandList", fdv(MCDRLayout.PTR))
        MH_begincommandlistwithwait = skip("mcdr_dx12BeginCommandListWithWait", fdv(MCDRLayout.PTR, MCDRLayout.I64))
        MH_endcommandlist = skip("mcdr_dx12EndCommandList", fdv(MCDRLayout.PTR))
        MH_submit = skip("mcdr_dx12Submit", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_submitv1 = skip("mcdr_dx12SubmitV1", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_commandrecordingstatusv1 = skip("mcdr_dx12CommandRecordingStatusV1", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_settlecommandsubmissionv1 = skip("mcdr_dx12SettleCommandSubmissionV1",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_destroycommandencodercheckedv1 = skip("mcdr_dx12DestroyCommandEncoderCheckedV1",
            fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_waitforfence = skip("mcdr_dx12WaitForFence", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64))
        MH_getfencevalue = skip("mcdr_dx12GetFenceValue", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_gettimestampnow = skip("mcdr_dx12GetTimestampNow", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_islistopen = skip("mcdr_dx12IsListOpen", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_copybuffer = skip("mcdr_dx12CopyBuffer", fdv(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
        MH_copybufferchecked = skip("mcdr_dx12CopyBufferChecked", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64,
            MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64))
        MH_writetotexture = skip("mcdr_dx12WriteToTextureChecked", fd(MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_copybuffertotexture = skip("mcdr_dx12CopyBufferToTextureChecked",
            fd(MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I64,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_copytexturetobuffer = skip("mcdr_dx12CopyTextureToBuffer",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.I64
            ))
        MH_copytexturetotexture = skip("mcdr_dx12CopyTextureToTextureChecked",
            fd(MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_clearcolortexture = skip("mcdr_dx12ClearColorTexture", fdv(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.F32,
            MCDRLayout.F32,
            MCDRLayout.F32,
            MCDRLayout.F32
        ))
        MH_cleardepthtexture = skip("mcdr_dx12ClearDepthTexture", fdv(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.F64))
        MH_clearcolortextureregion = skip("mcdr_dx12ClearColorTextureRegion",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.F32,
                MCDRLayout.F32,
                MCDRLayout.F32,
                MCDRLayout.F32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_cleardepthtextureregion = skip("mcdr_dx12ClearDepthTextureRegion",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.F64,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_beginrenderpass = skip("mcdr_dx12BeginRenderPass",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.F64,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_endrenderpass = skip("mcdr_dx12EndRenderPass", fdv(MCDRLayout.PTR))
        MH_getactivecolortexture = skip("mcdr_dx12GetActiveColorTexture", fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_createquerypool = skip("mcdr_dx12CreateQueryPool", fd(MCDRLayout.PTR, MCDRLayout.I32))
        MH_destroyquerypool = skip("mcdr_dx12DestroyQueryPool", fdv(MCDRLayout.PTR))
        MH_writetimestamp = skip("mcdr_dx12WriteTimestamp", fdv(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_readqueryvalue = skip("mcdr_dx12ReadQueryValue", fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32))
        MH_readqueryvalues = skip("mcdr_dx12ReadQueryValues", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_initasyncrenderer = skip("mcdr_dx12InitAsyncRenderer", fdv(MCDRLayout.I32))
        MH_destroyasyncrenderer = skip("mcdr_dx12DestroyAsyncRenderer", fd(MCDRLayout.I32))
        MH_asyncrenderbeginframe = skip("mcdr_dx12AsyncRenderBeginFrame", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_asyncrenderwaitcomplete = skip("mcdr_dx12AsyncRenderWaitComplete", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64
        ))
        MH_asyncrenderisrecordingready = skip("mcdr_dx12AsyncRenderIsRecordingReady", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_asyncrenderwaitrecordingready = skip("mcdr_dx12AsyncRenderWaitRecordingReady", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64
        ))
        MH_asyncsendcommandsready = skip("mcdr_dx12AsyncSendCommandsReady", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_clearasyncrenderctx = skip("mcdr_dx12ClearAsyncRenderCtx", fdv())
        // skipOptional throughout: a missing export disables one pass, never the whole bind.
        MH_applysmaatotexture = skipOptional("mcdr_dx12ApplySmaaToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        // In-place SMAA on the world layer; false => no export or SMAA off (rendering continues).
        MH_blittexturetotexture = skipOptional("mcdr_dx12BlitTextureToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        // The GUI target takes over by blitting the scaled world target; no export => GUI shares the main target.
        MH_sharpentexturetotexture = skipOptional("mcdr_dx12SharpenTextureToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.F32))
        // 5-tap sharpen (TAA/DLAA/DLSS); no export => degrade to blit.
    }
    private fun bindPipelineSurfaceDraw() {
        MH_multiDrawIndexedPacked = skip("mcdr_dx12MultiDrawIndexedPacked", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        MH_multiDrawIndexedSeparate = skip("mcdr_dx12MultiDrawIndexedSeparate", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        MH_multiDrawPacked = skip("mcdr_dx12MultiDrawPacked", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        MH_multiDrawSeparate = skip("mcdr_dx12MultiDrawSeparate", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_multiDrawBound = skip("mcdr_dx12MultiDrawBound", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32,
            MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.PTR))
        MH_createterrainworkgraph = skip("mcdr_dx12CreateTerrainWorkGraph", fd(MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I64))
        MH_destroyterrainworkgraph = skip("mcdr_dx12DestroyTerrainWorkGraph", fdv(MCDRLayout.PTR))
        MH_prepareterrainworkgraph = skip("mcdr_dx12PrepareTerrainWorkGraph", fd(MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        MH_drawterrainworkgraph = skip("mcdr_dx12DrawTerrainWorkGraph", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64))
        MH_destroyterrainbatch = skip("mcdr_dx12DestroyTerrainBatch", fdv(MCDRLayout.PTR))
        MH_vanillabegin = skip("mcdr_dx12VanillaBeginFrame", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_vanillaworld = skip("mcdr_dx12VanillaWorldReady", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_vanillagui = skip("mcdr_dx12VanillaGuiReady", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_vanillapublish = skip("mcdr_dx12VanillaPublishPreparedFrame", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_vanillaevaluate = skip("mcdr_dx12VanillaEvaluateReconstruction", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_vanillaworldoutput = skip("mcdr_dx12VanillaPrepareWorldOutput", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_begindisplay = skip("mcdr_dx12BeginDisplayFrame", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_settledisplay = skip("mcdr_dx12SettleDisplayFrameV1", fd(
            MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_abortdisplay = skip("mcdr_dx12AbortDisplayFrame", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_preparedisplay = skip("mcdr_dx12PrepareDisplayFrame", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_submitdisplay = skip("mcdr_dx12SubmitDisplayFrame", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_prewarmdisplay = skip("mcdr_dx12PrewarmDisplayFrame", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I64))
        MH_vanillareset = skip("mcdr_dx12VanillaResetFrame", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_vanillabegindeclared = skip("mcdr_dx12VanillaBeginDeclaredPass", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_vanillafinishdeclared = skip("mcdr_dx12VanillaFinishDeclaredPass", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_vanillabeginraster = skip("mcdr_dx12VanillaBeginRasterPass", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_invalidatereconstructioninputs = skip("mcdr_dx12InvalidateReconstructionInputs", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_prewarmframeprep = skip("mcdr_dx12PrewarmFrameprep", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I32))
        MH_prepareframeprograms = skip("mcdr_dx12PrepareFramePrograms", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I32, MCDRLayout.PTR))
        MH_commitframeprograms = skip("mcdr_dx12CommitFramePrograms", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_abortframeprograms = skip("mcdr_dx12AbortFramePrograms", fdv(MCDRLayout.PTR))
        MH_producereactivemask = skip("mcdr_dx12ProduceReactiveMask", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64))
        MH_produceprovidermasks = skip("mcdr_dx12ProduceProviderMasks", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32,
            MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64))
        MH_produceprovidermasksv2 = skip("mcdr_dx12ProduceProviderMasksV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_producevrs = skip("mcdr_dx12ProduceVrs", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32,
            MCDRLayout.I32, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_produceexposure = skip("mcdr_dx12ProduceExposure", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.F32))
        MH_resetframeprep = skip("mcdr_dx12ResetFrameprep", fdv(MCDRLayout.PTR))
        MH_createworkgraph = skip("mcdr_dx12CreateWorkGraph", fd(MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I64))
        MH_destroyworkgraph = skip("mcdr_dx12DestroyWorkGraph", fdv(MCDRLayout.PTR))
        MH_dispatchworkgraph = skip("mcdr_dx12DispatchWorkGraph", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_creategraphicspipeline = skip("mcdr_dx12CreateGraphicsPipeline", fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_creategraphicsconstants = skip("mcdr_dx12CreateGraphicsPipelineWithConstants", fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_createoitnormalizationpipelinev1 = skip("mcdr_dx12CreateOitNormalizationPipelineV1", fd(MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitshaderprelude = skip("mcdr_dx12OitShaderPreludeV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_creategraphicscapture = skip("mcdr_dx12CreateGraphicsCapturePipelineV1", fd(MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR))
        MH_createmeshcaptureroot = skip("mcdr_dx12CreateMeshCaptureRootV1", fd(MCDRLayout.I64,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_createmeshcapture = skip("mcdr_dx12CreateMeshCapturePipelineV1", fd(MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I32, MCDRLayout.PTR))
         MH_createterrantileroot = skip("mcdr_dx12CreateTerrainTileRootV1", fd(MCDRLayout.I64,
             MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
         MH_createterrantilroute = skip("mcdr_dx12CreateTerrainTileRouteV1", fd(MCDRLayout.PTR,
             MCDRLayout.I64, MCDRLayout.PTR))
         MH_bindterrantileroute = skip("mcdr_dx12BindTerrainTileRouteV1", fd(MCDRLayout.I32,
             MCDRLayout.PTR, MCDRLayout.PTR))
         MH_bindterraintilepipeline = skip("mcdr_dx12BindTerrainTilePipelineV1", fd(MCDRLayout.I32,
             MCDRLayout.PTR, MCDRLayout.PTR))
         MH_bindterraintilestagev2 = skip("mcdr_dx12BindTerrainTileStageV2", fd(MCDRLayout.I32,
             MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
         MH_destroyterraintileroute = skip("mcdr_dx12DestroyTerrainTileRouteV1", fdv(MCDRLayout.PTR))
        MH_oitbegin = skip("mcdr_dx12OitBeginFrameV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitbeginv2 = skip("mcdr_dx12OitBeginFrameV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitmetadata = skip("mcdr_dx12OitPublishMetadataV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainmetadata = skip("mcdr_dx12OitPublishTerrainMetadataV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitbind = skip("mcdr_dx12OitBindDrawV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainbind = skip("mcdr_dx12OitBindTerrainDrawV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainpreparetiles = skip("mcdr_dx12OitPrepareTerrainTilesV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainpreparetilesv3 = skip("mcdr_dx12OitPrepareTerrainTilesV3", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitunbind = skip("mcdr_dx12OitUnbindDrawV1", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitseal = skip("mcdr_dx12OitSealFrameV1", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitresolve = skip("mcdr_dx12OitResolveRecordsV3", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitresolvev4 = skip("mcdr_dx12OitResolveRecordsV4", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitabort = skip("mcdr_dx12OitAbortFrameV1", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionbegin = skipOptional("mcdr_dx12OitBeginExecutionStreamV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionquery = skipOptional("mcdr_dx12OitQueryExecutionStreamV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitreceiptquery = skipOptional("mcdr_dx12OitQueryTerrainDrawReceiptV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitreceiptfrontier = skipOptional("mcdr_dx12OitQueryTerrainDrawFrontierV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionappend = skipOptional("mcdr_dx12OitAppendExecutionEventV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionseal = skipOptional("mcdr_dx12OitSealExecutionStreamV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionbeginv2 = skipOptional("mcdr_dx12OitBeginExecutionStreamV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitoperationbeginv1 = skip("mcdr_dx12OitBeginExecutionOperationV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitoperationfinishv1 = skip("mcdr_dx12OitFinishExecutionOperationV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitoperationcancelv1 = skip("mcdr_dx12OitCancelExecutionOperationV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitoperationreceiptv1 = skip("mcdr_dx12OitQueryExecutionOperationReceiptV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_oitexecutiontexturev1 = skipOptional("mcdr_dx12OitCaptureExecutionTextureV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_oitexecutionappendv2 = skipOptional("mcdr_dx12OitAppendExecutionEventV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionoperationappendv1 = skip("mcdr_dx12OitAppendExecutionOperationEventV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionqueryv2 = skipOptional("mcdr_dx12OitQueryExecutionStreamV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionterrainreceiptv2 = skipOptional("mcdr_dx12OitQueryExecutionTerrainReceiptV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_oitexecutionsealv2 = skipOptional("mcdr_dx12OitSealExecutionStreamV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitexecutionabortv2 = skipOptional("mcdr_dx12OitAbortExecutionStreamV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitsampledcontentv1 = skipOptional("mcdr_dx12CaptureSampledTextureContentV1", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainstagebeginv2 = skipOptional("mcdr_dx12OitBeginTerrainStageDrawV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainstagefinishv2 = skipOptional("mcdr_dx12OitFinishTerrainStageDrawV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainstageabortv2 = skipOptional("mcdr_dx12OitAbortTerrainStageDrawV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_oitterrainstagereceiptv2 = skipOptional("mcdr_dx12OitQueryTerrainStageReceiptV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_oitterrainreadsetregisterv2 = skipOptional("mcdr_dx12OitRegisterTerrainReplayReadSetV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_oitterrainreadsetcancelv2 = skipOptional("mcdr_dx12OitCancelTerrainReplayReadSetV2", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_meshrootsigconstants = skip("mcdr_dx12CreateMeshRootSignatureWithConstants", fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        MH_pushgraphicsconstants = skip("mcdr_dx12PushGraphicsConstants", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_ensuregraphicsstate = skip("mcdr_dx12EnsureGraphicsState", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_createmeshconstants = skip("mcdr_dx12CreateMeshPipelineWithConstants", fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_createmeshrootconstants = skip("mcdr_dx12CreateMeshPipelineWithRootConstants", fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I32))
        MH_destroypipeline = skip("mcdr_dx12DestroyPipeline", fdv(MCDRLayout.PTR))
        // TAA resolve; no export => TAA disables itself with a logged reason.
        MH_compileshaderdxc = skip("mcdr_dx12CompileShaderDxc", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_createmeshpipeline = skip("mcdr_dx12CreateMeshPipeline", fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_createmeshpipelineroot = skip("mcdr_dx12CreateMeshPipelineWithRootSignature",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64))
        MH_meshrootsig = skip("mcdr_dx12CreateMeshRootSignature",
            fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32))
        MH_meshrootsigrelease = skip("mcdr_dx12ReleaseRootSignature", fdv(MCDRLayout.I64))
        MH_dispatchmesh = skip("mcdr_dx12DispatchMesh", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        // meshlet terrain dispatch (vertex pool / section table / meshlet table + b8).
        MH_meshdrawmeshlets = skip("mcdr_dx12MeshDrawMeshlets", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        // compute minimum path: root UAV u0 + 8 root constants b0.
        MH_createcomputepipeline = skip("mcdr_dx12CreateComputePipeline", fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_createterrantilebinningpipeline = skip("mcdr_dx12CreateTerrainTileBinningPipeline",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_dispatchcompute = skip("mcdr_dx12DispatchCompute", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        // shading-rate image: 2xSRV + 1xUAV compute PSO, R8_UINT image, SRI binding.
        MH_createcomputepipelineshadingrate = skip("mcdr_dx12CreateComputePipelineShadingRate",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_createshadingrateimage = skip("mcdr_dx12CreateShadingRateImage",
            fd(MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32))
        MH_dispatchcomputeshadingrate = skip("mcdr_dx12DispatchComputeShadingRate", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_setshadingrateimage = skip("mcdr_dx12SetShadingRateImage",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        // generic compute texture pass, same root signature as the SRI one.
        MH_createtexture3 = skip("mcdr_dx12CreateComputePipelineTexture3",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_dispatchtexture3 = skip("mcdr_dx12DispatchComputeTexture3", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_createsurface = skip("mcdr_dx12CreateSurface", fd(MCDRLayout.PTR, MCDRLayout.I64))
        MH_surfacepresentmodes = skip("mcdr_dx12SurfacePresentModes", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setexclusivefullscreen = skip("mcdr_dx12SetExclusiveFullscreen", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_configuresurface = skip("mcdr_dx12ConfigureSurface", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_acquiresurface = skip("mcdr_dx12AcquireSurface", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_blitsurface = skip("mcdr_dx12BlitSurface", fdv(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_presentsurface = skip("mcdr_dx12PresentSurface", fdv(MCDRLayout.PTR))
        MH_getbackbufferhandle = skip("mcdr_dx12GetBackBufferHandle", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_issurfacesuboptimal = skip("mcdr_dx12IsSurfaceSuboptimal", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_readbacktexturepixels = skip("mcdr_dx12ReadbackTexturePixels", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR
        ))
        // Append-only; no export => black-frame sampling falls back to the old path.
        MH_readbacktexturepixelsin = skipOptional("mcdr_dx12ReadbackTexturePixelsIn", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_dlssoutputstate = skipOptional("mcdr_dx12DlssOutputState", fd(MCDRLayout.I32))
        MH_destroysurface = skip("mcdr_dx12DestroySurfaceChecked", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_setpipeline = skip("mcdr_dx12SetPipeline", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setrastermotiontarget = skip("mcdr_dx12SetRasterMotionTarget", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_setadditionalcolortarget = skip("mcdr_dx12SetAdditionalColorTarget", fd(MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR))
        MH_setscissor = skip("mcdr_dx12SetScissor", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        // SetViewport / ClearStencilTexture, kept next to MH_setscissor.
        MH_setviewport = skip("mcdr_dx12SetViewport", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_clearstenciltexture = skip("mcdr_dx12ClearStencilTexture", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_setvertexbuffer = skip("mcdr_dx12SetVertexBuffer", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        MH_setindexbuffer = skip("mcdr_dx12SetIndexBuffer", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_pushdescriptors = skip("mcdr_dx12PushDescriptors", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_drawindexed = skip("mcdr_dx12DrawIndexed", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_draw = skip("mcdr_dx12Draw", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_drawindexedindirect = skip("mcdr_dx12DrawIndexedIndirect", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        MH_drawindirect = skip("mcdr_dx12DrawIndirect", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
    }
    private fun bindFenceStub() {
        try {
            STUB_TARGET = MethodHandles.lookup().findStatic(MCDR_ABI::class.java, "onFenceComplete",
                MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("cannot resolve the fence upcall target", e)
        }
        FENCE_STUB = LINKER.upcallStub(STUB_TARGET!!, fdv(MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32), ARENA)
    }
    /** Binds the native fence manager and its completion callback. */
    private fun bindAsync() {
        // Fence manager. FENCE_STUB is the only upcall; timeoutMs == 0 means infinite, which is why
        // the descriptor carries the timeout rather than the callee.
        MH_asyncfencecreate = skip("mcdr_dx12AsyncFenceCreate", fd(MCDRLayout.PTR))
        MH_asyncfencedestroy = skip("mcdr_dx12AsyncFenceDestroy", fdv(MCDRLayout.PTR))
        MH_asyncfencesignal = skip("mcdr_dx12AsyncFenceSignal", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64))
        MH_asyncfencewait = skip("mcdr_dx12AsyncFenceWait", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
        MH_asyncfenceiscomplete = skip("mcdr_dx12AsyncFenceIsComplete", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64))
        MH_asyncfenceregistercallback = skip("mcdr_dx12AsyncFenceRegisterCallback", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
    }
}
