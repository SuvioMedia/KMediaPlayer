package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleFont
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleRenderer
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

actual class AssSubtitleExtension actual constructor(
    @Suppress("UNUSED_PARAMETER") config: AssSubtitleRendererConfig,
) : DesktopSubtitlePipelineExtension {
    actual override val id: String = ID

    actual override val availability: VideoPipelineExtensionAvailability
        get() =
            when {
                isMacOs() && AppleAssNativeBridge.isAvailable ->
                    VideoPipelineExtensionAvailability.Available
                isMacOs() ->
                    VideoPipelineExtensionAvailability.unavailable(AppleAssNativeBridge.failureDetail)
                isWindowsOrLinux() && SystemLibAssRuntime.isAvailable ->
                    VideoPipelineExtensionAvailability.Available
                isWindowsOrLinux() ->
                    VideoPipelineExtensionAvailability.unavailable(SystemLibAssRuntime.failureDetail)
                else ->
                    VideoPipelineExtensionAvailability.unavailable(
                        "The desktop ASS overlay supports macOS, Windows and Linux.",
                    )
            }
    actual override val supportedSubtitleFormats: Set<SubtitleFormat>
        get() =
            if (availability.canContribute) {
                setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)
            } else {
                emptySet()
            }

    override fun createRenderer(): DesktopSubtitleRenderer? =
        when {
            isMacOs() && AppleAssNativeBridge.isAvailable ->
                runCatching { AppleDesktopSubtitleRenderer() }.getOrNull()
            isWindowsOrLinux() && SystemLibAssRuntime.isAvailable ->
                runCatching { SystemLibAssSubtitleRenderer.create() }.getOrNull()
            else -> null
        }

    private companion object {
        const val ID = "composemediaplayer-ass"
    }
}

private class AppleDesktopSubtitleRenderer : DesktopSubtitleRenderer {
    private val lock = Any()
    private var handle: Long = AppleAssNativeBridge.nativeCreate()

    init {
        check(handle != 0L) { "The bundled libass renderer could not be created." }
    }

    override val backendDescription: String = "bundled libass 0.17.5 / CoreText"

    override fun addFont(font: DesktopSubtitleFont): Boolean =
        synchronized(lock) {
            handle.takeIf { it != 0L }?.let { current ->
                AppleAssNativeBridge.nativeAddFont(current, font.name, font.data)
            } ?: false
        }

    override fun setTrack(data: ByteArray): Boolean =
        synchronized(lock) {
            handle.takeIf { it != 0L }?.let { current ->
                AppleAssNativeBridge.nativeSetTrack(current, data)
            } ?: false
        }

    override fun blendBgraFrame(
        pixels: ByteBuffer,
        rowBytes: Int,
        width: Int,
        height: Int,
        timeMs: Long,
    ): Boolean {
        require(pixels.isDirect) { "The desktop subtitle frame must use a direct ByteBuffer." }
        require(width > 0 && height > 0) { "The subtitle frame dimensions must be positive." }
        require(width <= Int.MAX_VALUE / BYTES_PER_BGRA_PIXEL) {
            "The subtitle frame width is too large."
        }
        require(rowBytes >= width * BYTES_PER_BGRA_PIXEL) {
            "The subtitle frame row stride is too small."
        }
        require(rowBytes.toLong() * height <= pixels.capacity().toLong()) {
            "The direct subtitle frame buffer is too small."
        }
        return synchronized(lock) {
            handle.takeIf { it != 0L }?.let { current ->
                AppleAssNativeBridge.nativeBlendBgra(
                    current,
                    pixels,
                    rowBytes,
                    width,
                    height,
                    timeMs,
                )
            } ?: false
        }
    }

    override fun close() {
        synchronized(lock) {
            val current = handle
            handle = 0L
            if (current != 0L) AppleAssNativeBridge.nativeClose(current)
        }
    }

    private companion object {
        const val BYTES_PER_BGRA_PIXEL = 4
    }
}

internal object AppleAssNativeBridge {
    private val loadFailure: Throwable? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!isMacOs()) {
            UnsupportedOperationException("The bundled Apple libass runtime requires macOS.")
        } else {
            runCatching {
                AppleAssNativeRuntime.load()
                check(nativeVersion() >= REQUIRED_LIBASS_VERSION) {
                    "The bundled libass runtime is older than 0.17.5."
                }
            }.exceptionOrNull()
        }
    }

    val isAvailable: Boolean
        get() = loadFailure == null

    val failureDetail: String
        get() =
            loadFailure?.let { failure ->
                "The bundled macOS libass runtime could not be loaded (${failure::class.simpleName})."
            } ?: "The bundled macOS libass runtime is available."

    @JvmStatic external fun nativeVersion(): Int

    @JvmStatic external fun nativeCreate(): Long

    @JvmStatic external fun nativeAddFont(
        handle: Long,
        name: String,
        data: ByteArray,
    ): Boolean

    @JvmStatic external fun nativeSetTrack(
        handle: Long,
        data: ByteArray,
    ): Boolean

    @JvmStatic external fun nativeBlendBgra(
        handle: Long,
        pixels: ByteBuffer,
        rowBytes: Int,
        width: Int,
        height: Int,
        timeMs: Long,
    ): Boolean

    @JvmStatic external fun nativeClose(handle: Long)

    private const val REQUIRED_LIBASS_VERSION = 0x01705000
}

private object AppleAssNativeRuntime {
    private const val RESOURCE_ROOT = "composemediaplayer/ass/native"
    private const val MAIN_LIBRARY = "libcomposemediaplayer_ass.dylib"
    private const val FRIBIDI_LIBRARY = "libkmediafribidi.dylib"

    @Synchronized
    fun load() {
        val platform = macOsResourcePlatform()
        val classLoader = AppleAssNativeBridge::class.java.classLoader
        val directory = Files.createTempDirectory("composemediaplayer-ass-")
        setOwnerOnly(directory, executable = true)
        directory.toFile().deleteOnExit()

        listOf(FRIBIDI_LIBRARY, MAIN_LIBRARY).forEach { name ->
            val resource = "$RESOURCE_ROOT/$platform/$name"
            val destination = directory.resolve(name)
            val input =
                requireNotNull(classLoader.getResourceAsStream(resource)) {
                    "Missing bundled native resource: $resource"
                }
            BufferedInputStream(input).use { source ->
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnly(destination, executable = true)
            destination.toFile().deleteOnExit()
        }
        System.load(directory.resolve(MAIN_LIBRARY).toAbsolutePath().toString())
    }

    private fun macOsResourcePlatform(): String = macOsAssResourcePlatform(System.getProperty("os.arch", "unknown"))

    private fun setOwnerOnly(
        path: Path,
        executable: Boolean,
    ) {
        runCatching {
            val permissions =
                buildSet {
                    add(PosixFilePermission.OWNER_READ)
                    add(PosixFilePermission.OWNER_WRITE)
                    if (executable) add(PosixFilePermission.OWNER_EXECUTE)
                }
            Files.setPosixFilePermissions(path, permissions)
        }
    }
}

internal fun macOsAssResourcePlatform(architecture: String): String {
    if (architecture.lowercase() !in setOf("aarch64", "arm64")) {
        throw UnsupportedOperationException(
            "The bundled macOS libass runtime requires Apple Silicon (arm64); " +
                "actual architecture: $architecture.",
        )
    }
    return "darwin-aarch64"
}

private fun isMacOs(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "mac" in osName || "darwin" in osName
}

private fun isWindowsOrLinux(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "windows" in osName || "linux" in osName
}
