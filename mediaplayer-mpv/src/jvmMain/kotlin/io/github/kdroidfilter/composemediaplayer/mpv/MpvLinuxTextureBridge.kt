package io.github.kdroidfilter.composemediaplayer.mpv

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** JNI bridge from libmpv's OpenGL render API to rotating GBM DMA-BUF targets. */
internal object MpvLinuxTextureBridge {
    private const val LIBRARY_NAME = "ComposeMediaPlayerMpvLinux"

    private val loaded: Boolean by lazy(::loadNativeLibrary)

    val isAvailable: Boolean
        get() = isLinux() && loaded

    @JvmStatic
    external fun nCreateRenderer(
        mpvHandle: Long,
        libraryLoadName: String,
        renderNode: String,
        fourcc: Int,
        modifiers: LongArray,
        extendedLinear: Boolean,
    ): Long

    /** Renders a new libmpv frame, returning the latest producer serial. */
    @JvmStatic
    external fun nRenderFrame(
        nativeRenderer: Long,
        width: Int,
        height: Int,
    ): Long

    /** fd, size, stride, FourCC, offset/modifier, generation/serial, acquire fence, EDR flag. */
    @JvmStatic
    external fun nAcquireTextureFrame(
        nativeRenderer: Long,
        serial: Long,
    ): LongArray?

    /** Releases a rendered slot whose DMA-BUF was never transferred to Nucleus. */
    @JvmStatic
    external fun nDiscardTextureFrame(
        nativeRenderer: Long,
        serial: Long,
    )

    /** Transfers ownership of [frameFd] and [releaseFenceFd] back to the producer. */
    @JvmStatic
    external fun nReleaseTextureFrame(
        nativeRenderer: Long,
        generation: Long,
        serial: Long,
        frameFd: Int,
        releaseFenceFd: Int,
    )

    @JvmStatic
    external fun nReportPresented(
        nativeRenderer: Long,
        serial: Long,
    )

    @JvmStatic external fun nGetFailure(nativeRenderer: Long): String?

    @JvmStatic external fun nDetach(nativeRenderer: Long)

    private fun loadNativeLibrary(): Boolean {
        if (!isLinux()) return false
        try {
            System.loadLibrary(LIBRARY_NAME)
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Published JVM artifacts carry the architecture-specific resource below.
        }

        return runCatching {
            val architecture =
                when (System.getProperty("os.arch", "").lowercase()) {
                    "aarch64", "arm64" -> "linux-arm64"
                    "amd64", "x86_64" -> "linux-x86-64"
                    else -> error("Unsupported Linux architecture")
                }
            val resource = "composemediaplayer/native/$architecture/lib$LIBRARY_NAME.so"
            val classLoader =
                MpvLinuxTextureBridge::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val input = requireNotNull(classLoader.getResourceAsStream(resource))
            val directory = Files.createTempDirectory("composemediaplayer-mpv-linux-")
            val library = directory.resolve("lib$LIBRARY_NAME.so")
            input.use { source -> Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING) }
            val executablePermissions =
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            runCatching { Files.setPosixFilePermissions(directory, executablePermissions) }
            runCatching { Files.setPosixFilePermissions(library, executablePermissions) }
            library.toFile().deleteOnExit()
            directory.toFile().deleteOnExit()
            System.load(library.toAbsolutePath().toString())
        }.isSuccess
    }

    private fun isLinux(): Boolean = System.getProperty("os.name", "").lowercase().contains("linux")
}
