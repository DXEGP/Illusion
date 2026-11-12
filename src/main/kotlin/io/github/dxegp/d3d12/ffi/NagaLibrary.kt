package io.github.dxegp.d3d12.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

internal object NagaLibrary {
    private val libraryArena = Arena.ofShared()
    private val linker = Linker.nativeLinker()
    private val library = SymbolLookup.libraryLookup(
        NativeLibraryResources.extractRequiredLibrary("illusion_shader.dll"), libraryArena)
    private val version = linker.downcallHandle(
        library.find("illusion_shader_abi_version").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT))
    private val convert = linker.downcallHandle(
        library.find("illusion_shader_convert").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val free = linker.downcallHandle(
        library.find("illusion_shader_free").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    init {
        val actual = version.invokeExact() as Int
        check(actual == 1) { "naga: unsupported ABI version $actual" }
    }

    fun convert(request: String): String = Arena.ofConfined().use { arena ->
        require('\u0000' !in request) { "naga: request contains NUL" }
        val source = arena.allocateFrom(request)
        val output = arena.allocate(ValueLayout.ADDRESS)
        output.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
        try {
            val status = convert.invokeExact(source, output) as Int
            val response = output.get(ValueLayout.ADDRESS, 0)
            check(status in 0..1 && response.address() != 0L) { "naga: invalid response ($status)" }
            response.reinterpret(64L * 1024 * 1024).getString(0)
        } finally {
            val response = output.get(ValueLayout.ADDRESS, 0)
            if (response.address() != 0L) free.invokeExact(response)
        }
    }
}
