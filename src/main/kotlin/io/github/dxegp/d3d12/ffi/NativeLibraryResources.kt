package io.github.dxegp.d3d12.ffi

import io.github.dxegp.diagnostics.IllusionLogger
import java.nio.file.Files
import java.nio.file.Path

internal object NativeLibraryResources {
    private const val LIB_NAME = "MCDR.dll"
    private const val EXTRACT_DIR = "dxrmc"
    private const val VENDOR_MANIFEST = "/vendor/manifest.txt"

    fun extractRequiredLibrary(name: String): Path {
        val bytes = javaClass.getResourceAsStream("/$name")?.use { it.readAllBytes() }
            ?: error("Required compiler library is not packaged: $name")
        val directory = Path.of(System.getProperty("user.dir"), EXTRACT_DIR)
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
            Files.write(target, bytes)
        }
        return target.toAbsolutePath()
    }
    /** Copy MCDR.dll to `<user.dir>/dxrmc/` and return the path; refreshed on a byte difference, not on timestamps (JAR entries are pinned to 1980 and two DLLs can share a size). */
    fun extractLibrary(): Path {
        val dir = Path.of(System.getProperty("user.dir"), EXTRACT_DIR)
        Files.createDirectories(dir)
        extractVendorResources(dir)
        val dllPath = dir.resolve(LIB_NAME)
        var useJarDll = true
        var jarBytes: ByteArray? = null
        try {
            NativeLibraryResources::class.java.getResourceAsStream("/${LIB_NAME}").use { input ->
                if (input != null) {
                    jarBytes = input.readAllBytes()
                }
            }
        } catch (ignored: Exception) {

        }
        if (jarBytes == null) {
            if (!Files.exists(dllPath)) {
                throw IllegalStateException("${LIB_NAME} is neither packaged in the JAR nor present at "
                    + "${dllPath.toAbsolutePath()} — build the MCDR native project first")
            }
            useJarDll = false
        } else if (Files.exists(dllPath)) {
            try {
                if (jarBytes.contentEquals(Files.readAllBytes(dllPath))) {
                    useJarDll = false
                }
            } catch (ignored: Exception) {

            }
        }
        if (useJarDll) {
            Files.write(dllPath, jarBytes!!)
        }
        return dllPath.toAbsolutePath()
    }
    /** Unpack the bundled SDK runtimes next to MCDR.dll; never throws — a missing or broken SDK must only make itself unavailable. */
    private fun extractVendorResources(dir: Path) {
        try {
            val entries = javaClass.getResourceAsStream(VENDOR_MANIFEST)?.use { input ->
                input.readAllBytes().toString(Charsets.UTF_8)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            } ?: return
            var written = 0
            var current = 0
            val problems = mutableListOf<String>()
            for (entry in entries) {
                try {
                    val bytes = javaClass.getResourceAsStream("/$entry")?.use { it.readAllBytes() }
                    if (bytes == null) {
                        problems += "$entry (未打进 JAR)"
                        continue
                    }
                    val target = dir.resolve(entry.substringAfterLast('/'))

                    if (Files.exists(target)) {
                        val same = try {
                            Files.readAllBytes(target).contentEquals(bytes)
                        } catch (ignored: Exception) {
                            false
                        }
                        if (same) {
                            current++
                            continue
                        }
                    }
                    Files.write(target, bytes)
                    written++
                } catch (t: Throwable) {
                    problems += "$entry (${t.message ?: t::class.java.simpleName})"
                }
            }
            if (written > 0 || problems.isNotEmpty()) {
                IllusionLogger.infoMessage("SDK runtime DLLs: $written extracted, $current already current"
                    + (if (problems.isEmpty()) "" else "; ${problems.size} failed: ${problems.joinToString("; ")}"))
            }
        } catch (ignored: Throwable) {

        }
    }
}
