package io.github.kdroidfilter.composemediaplayer.mpv

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** JNI bridge from libmpv's OpenGL render API to a rotating IOSurface pool. */
internal object MpvMacNativeBridge {
    private const val LIBRARY_NAME = "ComposeMediaPlayerMpvMac"
    private const val RESOURCE_PATH =
        "composemediaplayer/native/darwin-arm64/libComposeMediaPlayerMpvMac.dylib"

    private val loaded: Boolean by lazy(::loadNativeLibrary)

    val isAvailable: Boolean
        get() = isMacArm64() && loaded

    @JvmStatic
    external fun nCreateRenderer(
        mpvHandle: Long,
        libraryLoadName: String,
        colorMode: Int,
    ): Long

    @JvmStatic external fun nRenderFrame(
        nativeRenderer: Long,
        width: Int,
        height: Int,
    ): Long

    /** IOSurface, size, pixel format, producer generation/serial, extended-linear flag. */
    @JvmStatic external fun nGetTextureOutputInfo(nativeRenderer: Long): LongArray?

    @JvmStatic external fun nReleaseTextureFrame(
        nativeRenderer: Long,
        generation: Long,
        serial: Long,
    )

    /** Acknowledges libmpv only after Nucleus confirms a later system Present. */
    @JvmStatic external fun nReportPresented(
        nativeRenderer: Long,
        serial: Long,
    )

    @JvmStatic external fun nDetach(nativeRenderer: Long)

    @JvmStatic external fun nSetColorMode(
        nativeRenderer: Long,
        colorMode: Int,
    )

    /** Configures libmpv's native GPU post-process projection without crossing Compose/CPU. */
    @JvmStatic external fun nSetProjection(
        nativeRenderer: Long,
        parameters: FloatArray,
    )

    /** Applies the public Compose scale mode to the native projected output. */
    @JvmStatic external fun nSetContentScale(
        nativeRenderer: Long,
        contentScaleMode: Int,
        mediaAspect: Float,
    )

    @JvmStatic external fun nRequestRedraw(nativeRenderer: Long)

    @JvmStatic external fun nGetDisplayRefreshRate(nativeRenderer: Long): Double

    /** Frame counters followed by aggregate/max render and drawable-flush times in nanoseconds. */
    @JvmStatic external fun nGetPresentationDiagnostics(nativeRenderer: Long): LongArray?

    private fun loadNativeLibrary(): Boolean {
        if (!isMacArm64()) return false
        try {
            System.loadLibrary(LIBRARY_NAME)
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Development and ordinary JVM artifacts load the verified classpath resource below.
        }

        return runCatching {
            val classLoader = MpvMacNativeBridge::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val input = requireNotNull(classLoader.getResourceAsStream(RESOURCE_PATH))
            val directory = Files.createTempDirectory("composemediaplayer-mpv-native-")
            runCatching {
                Files.setPosixFilePermissions(
                    directory,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }
            val library = directory.resolve("lib$LIBRARY_NAME.dylib")
            input.use { source -> Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING) }
            runCatching {
                Files.setPosixFilePermissions(
                    library,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }
            library.toFile().deleteOnExit()
            directory.toFile().deleteOnExit()
            System.load(library.toAbsolutePath().toString())
        }.isSuccess
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) &&
            architecture in setOf("aarch64", "arm64")
    }
}

internal enum class MpvMacOutputColorMode(
    val nativeValue: Int,
) {
    SDR(0),
    EXTENDED_LINEAR(1),
}
