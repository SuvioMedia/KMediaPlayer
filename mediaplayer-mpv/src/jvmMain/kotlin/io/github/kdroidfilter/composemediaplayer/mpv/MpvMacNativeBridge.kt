package io.github.kdroidfilter.composemediaplayer.mpv

import java.awt.Window
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** JNI bridge for the embedded macOS libmpv OpenGL/EDR surface. */
internal object MpvMacNativeBridge {
    private const val LIBRARY_NAME = "ComposeMediaPlayerMpvMac"
    private const val RESOURCE_PATH =
        "composemediaplayer/native/darwin-arm64/libComposeMediaPlayerMpvMac.dylib"

    private val loaded: Boolean by lazy(::loadNativeLibrary)

    val isAvailable: Boolean
        get() = isMacArm64() && loaded

    @JvmStatic
    external fun nAttach(
        mpvHandle: Long,
        window: Window,
        libraryLoadName: String,
        colorMode: Int,
    ): Long

    @JvmStatic external fun nDetach(nativeRenderer: Long)

    @JvmStatic external fun nSetColorMode(
        nativeRenderer: Long,
        colorMode: Int,
    )

    @JvmStatic external fun nRequestRedraw(nativeRenderer: Long)

    /** Requests AppKit's native full-screen transition for the dedicated AWT-backed window. */
    @JvmStatic external fun nSetWindowFullscreen(
        window: Window,
        fullscreen: Boolean,
    ): Boolean

    /** Returns the current AppKit NSWindow full-screen style state. */
    @JvmStatic external fun nIsWindowFullscreen(window: Window): Boolean

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
    BT2100_PQ(1),
    BT2100_HLG(2),
}
