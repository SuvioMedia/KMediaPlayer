package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Java 25 FFM binding for the tiny, pinned libdovi shim in `native/libdovi`. */
class JvmLibDoviRpuConverter(
    libraryPath: Path? = configuredLibDoviPath(),
) : DolbyVisionRpuConverter,
    Closeable {
    private val binding =
        libraryPath
            ?.takeIf { currentJvmLibDoviPlatformIsSupported() }
            ?.takeIf(Files::isRegularFile)
            ?.let(LibDoviBinding::open)

    override val isAvailable: Boolean get() = binding != null

    override suspend fun prepare(): Boolean = isAvailable

    override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult =
        withContext(Dispatchers.IO) {
            if (rpuNalUnit.isEmpty()) {
                return@withContext DolbyVisionRpuConversionResult.Invalid("The Dolby Vision RPU NAL unit is empty.")
            }
            val activeBinding =
                binding
                    ?: return@withContext DolbyVisionRpuConversionResult.Unavailable(
                        "No compatible composemediaplayer libdovi native library was found.",
                    )
            runCatching { activeBinding.convert(rpuNalUnit) }
                .fold(
                    onSuccess = { converted ->
                        if (converted == null) {
                            DolbyVisionRpuConversionResult.Invalid(
                                "libdovi rejected the RPU or it was not Dolby Vision Profile 7.",
                            )
                        } else {
                            DolbyVisionRpuConversionResult.Success(converted)
                        }
                    },
                    onFailure = { error ->
                        DolbyVisionRpuConversionResult.Invalid(
                            "libdovi conversion failed: ${error.message ?: error::class.simpleName}",
                        )
                    },
                )
        }

    override fun close() {
        binding?.close()
    }

    private class LibDoviBinding(
        private val arena: Arena,
        private val convert: MethodHandle,
        private val free: MethodHandle,
    ) : Closeable {
        fun convert(input: ByteArray): ByteArray? =
            Arena.ofConfined().use { callArena ->
                val inputMemory = callArena.allocate(input.size.toLong())
                inputMemory.copyFrom(MemorySegment.ofArray(input))
                val outputLength = callArena.allocate(ValueLayout.JAVA_LONG)
                val output =
                    convert.invokeWithArguments(inputMemory, input.size.toLong(), outputLength) as MemorySegment
                if (output == MemorySegment.NULL) return@use null
                val length = outputLength.get(ValueLayout.JAVA_LONG, 0)
                if (length <= 0 || length > MAXIMUM_RPU_BYTES) {
                    free.invokeWithArguments(output, length.coerceAtLeast(0))
                    return@use null
                }
                try {
                    output.reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
                } finally {
                    free.invokeWithArguments(output, length)
                }
            }

        override fun close() = arena.close()

        companion object {
            fun open(path: Path): LibDoviBinding? =
                runCatching {
                    val arena = Arena.ofShared()
                    var keepArena = false
                    try {
                        val lookup = SymbolLookup.libraryLookup(path, arena)
                        val linker = Linker.nativeLinker()
                        val convert =
                            linker.downcallHandle(
                                lookup.find("cmp_dovi_convert_profile7_to81").orElseThrow(),
                                FunctionDescriptor.of(
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS,
                                ),
                            )
                        val free =
                            linker.downcallHandle(
                                lookup.find("cmp_dovi_free_buffer").orElseThrow(),
                                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                            )
                        LibDoviBinding(arena, convert, free).also { keepArena = true }
                    } finally {
                        if (!keepArena) arena.close()
                    }
                }.getOrNull()
        }
    }

    companion object {
        private const val MAXIMUM_RPU_BYTES = 4L * 1024L * 1024L

        private fun configuredLibDoviPath(): Path? {
            if (!currentJvmLibDoviPlatformIsSupported()) return null
            val configured =
                System.getProperty("composemediaplayer.libdovi")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_LIBDOVI")
            if (!configured.isNullOrBlank()) {
                return Path.of(configured).takeIf(Files::isRegularFile)
            }
            return extractBundledLibrary()
        }

        private fun extractBundledLibrary(): Path? {
            val os =
                when {
                    System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) -> "darwin"
                    System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true) -> "windows"
                    System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true) -> "linux"
                    else -> return null
                }
            val architecture =
                when (System.getProperty("os.arch").orEmpty().lowercase()) {
                    "aarch64", "arm64" -> "aarch64"
                    "amd64", "x86_64" -> if (os == "darwin") return null else "x86-64"
                    else -> return null
                }
            val fileName =
                when (os) {
                    "darwin" -> "libcomposemediaplayer_dovi.dylib"
                    "windows" -> "composemediaplayer_dovi.dll"
                    else -> "libcomposemediaplayer_dovi.so"
                }
            val resource = "/composemediaplayer/dolbyvision/native/$os-$architecture/$fileName"
            val stream = JvmLibDoviRpuConverter::class.java.getResourceAsStream(resource) ?: return null
            return runCatching {
                stream.use { input ->
                    Files
                        .createTempFile(
                            "composemediaplayer-dovi-",
                            ".${fileName.substringAfterLast('.')}",
                        ).also { path ->
                            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
                            path.toFile().deleteOnExit()
                        }
                }
            }.getOrNull()
        }
    }
}

internal fun isJvmLibDoviPlatformSupported(
    osName: String,
    architecture: String,
): Boolean {
    val normalizedOs = osName.lowercase()
    val normalizedArchitecture = architecture.lowercase()
    val knownArchitecture =
        normalizedArchitecture in setOf("aarch64", "arm64", "amd64", "x86_64")
    if (!knownArchitecture) return false
    return when {
        normalizedOs.contains("mac") || normalizedOs.contains("darwin") ->
            normalizedArchitecture in setOf("aarch64", "arm64")
        normalizedOs.contains("windows") || normalizedOs.contains("linux") -> true
        else -> false
    }
}

private fun currentJvmLibDoviPlatformIsSupported(): Boolean =
    isJvmLibDoviPlatformSupported(
        osName = System.getProperty("os.name").orEmpty(),
        architecture = System.getProperty("os.arch").orEmpty(),
    )
