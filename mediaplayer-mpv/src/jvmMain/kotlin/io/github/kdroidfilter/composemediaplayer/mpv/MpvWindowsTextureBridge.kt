package io.github.kdroidfilter.composemediaplayer.mpv

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JNI bridge from libmpv's OpenGL render API to a shared D3D11 video texture. */
internal object MpvWindowsTextureBridge {
    private const val LIBRARY_NAME = "ComposeMediaPlayerMpvWindows"

    private data class NativeRuntime(
        val angleEglLibrary: String,
        val angleGlesLibrary: String,
    )

    private val runtime: NativeRuntime? by lazy(::loadNativeRuntime)

    val isAvailable: Boolean
        get() = isWindows() && runtime != null

    fun createRenderer(
        mpvHandle: Long,
        libraryLoadName: String,
        adapterLuid: Long,
        extendedLinear: Boolean,
        width: Int,
        height: Int,
    ): Long {
        val nativeRuntime = runtime ?: return 0L
        return nCreateRenderer(
            mpvHandle = mpvHandle,
            libraryLoadName = libraryLoadName,
            adapterLuid = adapterLuid,
            extendedLinear = extendedLinear,
            width = width,
            height = height,
            angleEglLibrary = nativeRuntime.angleEglLibrary,
            angleGlesLibrary = nativeRuntime.angleGlesLibrary,
        )
    }

    @JvmStatic
    private external fun nCreateRenderer(
        mpvHandle: Long,
        libraryLoadName: String,
        adapterLuid: Long,
        extendedLinear: Boolean,
        width: Int,
        height: Int,
        angleEglLibrary: String,
        angleGlesLibrary: String,
    ): Long

    /** Renders when libmpv has an update and returns the latest producer frame serial. */
    @JvmStatic external fun nRenderFrame(
        nativeRenderer: Long,
        width: Int,
        height: Int,
    ): Long

    /** handle, size, DXGI format, producer generation/serial, adapter LUID, extended-linear flag. */
    @JvmStatic external fun nGetTextureOutputInfo(nativeRenderer: Long): LongArray?

    /** Reports the exact producer frame only after Nucleus confirms a system Present. */
    @JvmStatic external fun nReportPresented(
        nativeRenderer: Long,
        frameSerial: Long,
    )

    @JvmStatic external fun nGetFailure(nativeRenderer: Long): String?

    @JvmStatic external fun nDetach(nativeRenderer: Long)

    private fun loadNativeRuntime(): NativeRuntime? {
        if (!isWindows()) return null
        return runCatching {
            val (bridgeArchitecture, nucleusArchitecture) =
                when (System.getProperty("os.arch", "").lowercase()) {
                    "aarch64", "arm64" -> "win32-arm64" to "win32-aarch64"
                    "amd64", "x86_64" -> "win32-x86-64" to "win32-x64"
                    else -> error("Unsupported Windows architecture")
                }
            val classLoader =
                MpvWindowsTextureBridge::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val directory = Files.createTempDirectory("composemediaplayer-mpv-windows-")
            val library = directory.resolve("$LIBRARY_NAME.dll")
            val angleEglLibrary = directory.resolve("ComposeMediaPlayerMpvEGL.dll")
            // ANGLE resolves its GLES sidecar by this exact basename relative
            // to the loaded EGL module. The unique directory still gives MPV
            // a separate module instance from the one owned by Nucleus/Skia.
            val angleGlesLibrary = directory.resolve("libGLESv2.dll")
            copyResource(
                classLoader = classLoader,
                resource = "composemediaplayer/native/$bridgeArchitecture/$LIBRARY_NAME.dll",
                destination = library,
            )
            copyResource(
                classLoader = classLoader,
                resource = "nucleus/native/$nucleusArchitecture/libEGL.dll",
                destination = angleEglLibrary,
            )
            copyResource(
                classLoader = classLoader,
                resource = "nucleus/native/$nucleusArchitecture/libGLESv2.dll",
                destination = angleGlesLibrary,
            )
            library.toFile().deleteOnExit()
            angleEglLibrary.toFile().deleteOnExit()
            angleGlesLibrary.toFile().deleteOnExit()
            directory.toFile().deleteOnExit()
            System.load(library.toAbsolutePath().toString())
            NativeRuntime(
                angleEglLibrary = angleEglLibrary.toAbsolutePath().toString(),
                angleGlesLibrary = angleGlesLibrary.toAbsolutePath().toString(),
            )
        }.getOrNull()
    }

    private fun copyResource(
        classLoader: ClassLoader,
        resource: String,
        destination: java.nio.file.Path,
    ) {
        requireNotNull(classLoader.getResourceAsStream(resource)) { "Missing native resource: $resource" }
            .use { source -> Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("windows")
}
