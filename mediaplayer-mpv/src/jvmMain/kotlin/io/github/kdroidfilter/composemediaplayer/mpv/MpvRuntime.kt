package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.MpvMacRenderer
import io.github.kdroidfilter.composemediaplayer.mpv.internal.LibMpvLibrary
import io.github.kdroidfilter.composemediaplayer.mpv.internal.MpvLoadFailure
import io.github.shusek.kmediampv.runtime.desktop.MpvDesktopRuntime
import io.github.shusek.kmediampv.runtime.desktop.MpvRuntimeException
import java.nio.file.Path

/** Selects the shared libmpv library loaded by the optional backend. */
internal sealed interface MpvLibrarySource {
    /**
     * Resolves the verified runtime supplied by the optional
     * `kmedia-mpv-runtime-desktop` dependency.
     */
    data object Bundled : MpvLibrarySource

    /** Tries conservative, platform-specific system library names and standard install locations. */
    data object Automatic : MpvLibrarySource

    /** Loads a library through the platform's normal system-library lookup. */
    data class SystemLibrary(
        val name: String,
    ) : MpvLibrarySource {
        init {
            require(name.isNotBlank()) { "The libmpv system-library name must not be blank." }
            require('/' !in name && '\\' !in name) {
                "Use MpvRuntimeSource.ExplicitPath for a filesystem path."
            }
        }
    }

    /** Loads exactly the absolute shared-library path supplied by the application. */
    data class ExplicitPath(
        val path: Path,
    ) : MpvLibrarySource {
        init {
            require(path.isAbsolute) { "The libmpv path must be absolute." }
        }
    }
}

/** Runtime options that are safe to expose without passing arbitrary mpv command-line options. */
internal data class MpvRuntimeConfig(
    val librarySource: MpvLibrarySource = MpvLibrarySource.Bundled,
    val preserveAssStyles: Boolean = true,
    val useEmbeddedFonts: Boolean = true,
    val macRenderer: MpvMacRenderer = MpvMacRenderer.MOLTENVK,
    /** One non-recursive directory containing application-supplied subtitle fonts. */
    val subtitleFontsDirectory: Path? = null,
    /** Application-private parent directory for the verified bundled runtime. */
    val desktopRuntimeDirectory: Path? = null,
    val maxRenderPixels: Int = 16_777_216,
) {
    init {
        subtitleFontsDirectory?.let { directory ->
            require(directory.isAbsolute) {
                "subtitleFontsDirectory must be an absolute path."
            }
        }
        desktopRuntimeDirectory?.let { directory ->
            require(directory.isAbsolute) {
                "desktopRuntimeDirectory must be an absolute path."
            }
        }
        require(maxRenderPixels in 1..67_108_864) {
            "maxRenderPixels must be between 1 and 67,108,864."
        }
    }
}

internal data class MpvClientApiVersion(
    val major: Int,
    val minor: Int,
) {
    override fun toString(): String = "$major.$minor"
}

/** The artifact cannot infer the effective license of a user-provided native binary. */
internal enum class MpvRuntimeLicenseStatus {
    VERIFIED_KMEDIAMPV_BUNDLED,
    UNVERIFIED_USER_PROVIDED,
}

internal data class MpvRuntimeInfo(
    val clientApiVersion: MpvClientApiVersion,
    val loadedFrom: MpvLibrarySource,
    val licenseStatus: MpvRuntimeLicenseStatus = MpvRuntimeLicenseStatus.UNVERIFIED_USER_PROVIDED,
)

internal enum class MpvUnavailableReason {
    RUNTIME_DEPENDENCY_MISSING,
    UNSUPPORTED_PLATFORM,
    BUNDLED_RUNTIME_REJECTED,
    NATIVE_ACCESS_DISABLED,
    LIBRARY_NOT_FOUND,
    REQUIRED_SYMBOL_MISSING,
    INCOMPATIBLE_CLIENT_API,
    LOAD_FAILED,
}

internal sealed interface MpvRuntimeAvailability {
    data class Available(
        val info: MpvRuntimeInfo,
    ) : MpvRuntimeAvailability

    data class Unavailable(
        val reason: MpvUnavailableReason,
        val guidance: String,
    ) : MpvRuntimeAvailability
}

/** Read-only runtime probe. It never downloads or installs a native library. */
internal object MpvRuntime {
    const val COMPILED_CLIENT_API_MAJOR: Int = 2
    const val COMPILED_CLIENT_API_MINOR: Int = 5

    fun inspect(config: MpvRuntimeConfig = MpvRuntimeConfig()): MpvRuntimeAvailability =
        try {
            val resolved = resolveMpvRuntime(config)
            LibMpvLibrary.open(resolved.librarySource).use { library ->
                val version = library.clientApiVersion
                if (version.major != COMPILED_CLIENT_API_MAJOR) {
                    MpvRuntimeAvailability.Unavailable(
                        reason = MpvUnavailableReason.INCOMPATIBLE_CLIENT_API,
                        guidance =
                            "The installed libmpv exposes client API ${version.major}.${version.minor}; " +
                                "this artifact requires major API $COMPILED_CLIENT_API_MAJOR.",
                    )
                } else {
                    MpvRuntimeAvailability.Available(
                        MpvRuntimeInfo(
                            clientApiVersion = version,
                            loadedFrom = library.loadedFrom,
                            licenseStatus = resolved.licenseStatus,
                        ),
                    )
                }
            }
        } catch (failure: MpvLoadFailure) {
            MpvRuntimeAvailability.Unavailable(
                reason = failure.reason,
                guidance = failure.guidance,
            )
        } catch (failure: MpvRuntimeResolutionFailure) {
            MpvRuntimeAvailability.Unavailable(
                reason = failure.reason,
                guidance = failure.guidance,
            )
        }
}

internal data class ResolvedMpvRuntime(
    val librarySource: MpvLibrarySource,
    val requiredOptions: Map<String, String>,
    val licenseStatus: MpvRuntimeLicenseStatus,
)

internal class MpvRuntimeResolutionFailure(
    val reason: MpvUnavailableReason,
    val guidance: String,
    cause: Throwable? = null,
) : IllegalStateException(guidance, cause)

internal fun resolveMpvRuntime(
    config: MpvRuntimeConfig,
    osName: String = System.getProperty("os.name"),
    architecture: String = System.getProperty("os.arch"),
): ResolvedMpvRuntime {
    if (!isMpvDesktopPlatformSupported(osName, architecture)) {
        throw MpvRuntimeResolutionFailure(
            reason = MpvUnavailableReason.UNSUPPORTED_PLATFORM,
            guidance =
                "The MPV desktop backend supports Linux x86_64/ARM64, macOS ARM64, " +
                    "and Windows x86_64.",
        )
    }
    return when (val source = config.librarySource) {
        MpvLibrarySource.Bundled -> resolveBundledMpvRuntime(config)
        else ->
            ResolvedMpvRuntime(
                librarySource = source,
                requiredOptions = emptyMap(),
                licenseStatus = MpvRuntimeLicenseStatus.UNVERIFIED_USER_PROVIDED,
            )
    }
}

private fun resolveBundledMpvRuntime(config: MpvRuntimeConfig): ResolvedMpvRuntime {
    if (!isBundledMpvDesktopSupported()) {
        throw MpvRuntimeResolutionFailure(
            reason = MpvUnavailableReason.UNSUPPORTED_PLATFORM,
            guidance =
                "The bundled KMediaMpv runtime does not support this desktop. " +
                    "Bundled targets are Linux x86_64/ARM64, macOS ARM64, and Windows x86_64.",
        )
    }

    try {
        val fontsDirectory = config.subtitleFontsDirectory
        val runtimeDirectory = config.desktopRuntimeDirectory ?: configuredDesktopRuntimeDirectory()
        val resolution =
            if (fontsDirectory == null) {
                null
            } else if (runtimeDirectory == null) {
                MpvDesktopRuntime.resolveRuntimeForLoading(fontsDirectory)
            } else {
                MpvDesktopRuntime.resolveRuntimeForLoading(fontsDirectory, runtimeDirectory)
            }
        val path =
            resolution?.libMpvPath()
                ?: runtimeDirectory?.let(MpvDesktopRuntime::resolveLibMpvForLoading)
                ?: MpvDesktopRuntime.resolveLibMpvForLoading()
        return ResolvedMpvRuntime(
            librarySource = MpvLibrarySource.ExplicitPath(path),
            requiredOptions = resolution?.requiredOptions().orEmpty(),
            licenseStatus = MpvRuntimeLicenseStatus.VERIFIED_KMEDIAMPV_BUNDLED,
        )
    } catch (failure: NoClassDefFoundError) {
        throw MpvRuntimeResolutionFailure(
            reason = MpvUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
            guidance =
                "The MPV backend was selected without the optional " +
                    "io.github.shusek:kmedia-mpv-runtime-desktop dependency.",
            cause = failure,
        )
    } catch (failure: UnsupportedClassVersionError) {
        throw MpvRuntimeResolutionFailure(
            reason = MpvUnavailableReason.BUNDLED_RUNTIME_REJECTED,
            guidance = "KMediaMpv requires a Java 25 runtime.",
            cause = failure,
        )
    } catch (failure: MpvRuntimeException) {
        val reason = failure.reason().toMpvUnavailableReason()
        throw MpvRuntimeResolutionFailure(
            reason = reason,
            guidance =
                if (reason == MpvUnavailableReason.UNSUPPORTED_PLATFORM) {
                    "The bundled KMediaMpv runtime does not support this desktop. " +
                        "Bundled targets are Linux x86_64/ARM64, macOS ARM64, and Windows x86_64."
                } else {
                    "The bundled KMediaMpv runtime was rejected (${failure.reason().name})."
                },
            cause = failure,
        )
    } catch (failure: RuntimeException) {
        throw MpvRuntimeResolutionFailure(
            reason = MpvUnavailableReason.BUNDLED_RUNTIME_REJECTED,
            guidance = "The bundled KMediaMpv runtime could not be verified.",
            cause = failure,
        )
    }
}

private fun configuredDesktopRuntimeDirectory(): Path? {
    val configuredPath =
        System
            .getProperty(MPV_RUNTIME_DIRECTORY_PROPERTY)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: System
                .getenv(MPV_RUNTIME_DIRECTORY_ENVIRONMENT)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            ?: return null
    return Path.of(configuredPath).also { path ->
        require(path.isAbsolute) { "The configured MPV runtime directory must be absolute." }
    }
}

internal fun isBundledMpvDesktopSupported(
    osName: String = System.getProperty("os.name"),
    architecture: String = System.getProperty("os.arch"),
): Boolean {
    val normalizedOsName = osName.lowercase()
    val normalizedArchitecture = architecture.lowercase()
    return (
        normalizedOsName.contains("mac") &&
            normalizedArchitecture in setOf("aarch64", "arm64")
    ) ||
        (
            normalizedOsName.contains("linux") &&
                normalizedArchitecture in setOf("amd64", "x86_64", "x86-64", "x64", "aarch64", "arm64")
        ) ||
        (
            normalizedOsName.contains("windows") &&
                normalizedArchitecture in setOf("amd64", "x86_64", "x86-64", "x64")
        )
}

private const val MPV_RUNTIME_DIRECTORY_PROPERTY = "composemediaplayer.mpv.runtimeDirectory"
private const val MPV_RUNTIME_DIRECTORY_ENVIRONMENT = "COMPOSE_MEDIA_PLAYER_MPV_RUNTIME_DIRECTORY"

internal fun isMpvDesktopPlatformSupported(
    osName: String,
    architecture: String,
): Boolean {
    val normalizedOsName = osName.lowercase()
    val normalizedArchitecture = architecture.lowercase()
    return when {
        normalizedOsName.contains("mac") || normalizedOsName.contains("darwin") ->
            normalizedArchitecture in setOf("aarch64", "arm64")
        normalizedOsName.contains("win") ->
            normalizedArchitecture in setOf("amd64", "x86_64", "x86-64", "x64")
        normalizedOsName.contains("linux") ->
            normalizedArchitecture in setOf("amd64", "x86_64", "x86-64", "x64", "aarch64", "arm64")
        else -> false
    }
}

internal fun MpvRuntimeException.Reason.toMpvUnavailableReason(): MpvUnavailableReason =
    when (this) {
        MpvRuntimeException.Reason.UNSUPPORTED_PLATFORM -> MpvUnavailableReason.UNSUPPORTED_PLATFORM
        else -> MpvUnavailableReason.BUNDLED_RUNTIME_REJECTED
    }
