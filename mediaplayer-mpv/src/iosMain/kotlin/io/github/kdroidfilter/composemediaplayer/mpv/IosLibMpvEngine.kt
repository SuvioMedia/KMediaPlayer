@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import cnames.structs.cmp_mpv_player
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_COMMAND_FAILED
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_EVENT_END_FILE
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_EVENT_FILE_LOADED
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_EVENT_NONE
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_EVENT_PLAYBACK_RESTART
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_EVENT_SEEK
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_EVENT_SHUTDOWN
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_INCOMPATIBLE_CLIENT_API
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_INITIALIZATION_FAILED
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_INVALID_ARGUMENT
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_LIBRARY_NOT_FOUND
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_OK
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_RENDER_FAILED
import io.github.kdroidfilter.composemediaplayer.mpv.native.CMP_MPV_REQUIRED_SYMBOL_MISSING
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_event
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_command
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_create
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_destroy
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_free_property
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_get_property
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_render_bgr0
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_set_property
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_wait_event
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_player_wakeup
import io.github.kdroidfilter.composemediaplayer.mpv.native.cmp_mpv_probe
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

internal sealed interface IosNativeMpvEvent {
    data object None : IosNativeMpvEvent

    data object Shutdown : IosNativeMpvEvent

    data object FileLoaded : IosNativeMpvEvent

    data class EndFile(
        val reason: Int,
        val errorCode: Int,
    ) : IosNativeMpvEvent

    data object SeekStarted : IosNativeMpvEvent

    data object PlaybackRestarted : IosNativeMpvEvent
}

internal data class IosMpvRuntimeResolution(
    val libraryPath: String?,
    val versionMajor: Int,
    val versionMinor: Int,
)

internal fun inspectIosMpvRuntime(options: MpvPlaybackOptions): MpvBackendAvailability =
    when (val resolution = resolveIosMpvRuntime(options)) {
        is IosMpvRuntimeResult.Available ->
            MpvBackendAvailability.Available(
                backend = "libmpv iOS ${resolution.resolution.versionMajor}.${resolution.resolution.versionMinor}",
            )
        is IosMpvRuntimeResult.Unavailable -> resolution.availability
    }

internal fun requireIosMpvRuntime(options: MpvPlaybackOptions): IosMpvRuntimeResolution =
    when (val result = resolveIosMpvRuntime(options)) {
        is IosMpvRuntimeResult.Available -> result.resolution
        is IosMpvRuntimeResult.Unavailable ->
            throw io.github.kdroidfilter.composemediaplayer
                .MpvBackendUnavailableException(result.availability)
    }

private sealed interface IosMpvRuntimeResult {
    data class Available(
        val resolution: IosMpvRuntimeResolution,
    ) : IosMpvRuntimeResult

    data class Unavailable(
        val availability: MpvBackendAvailability.Unavailable,
    ) : IosMpvRuntimeResult
}

private fun resolveIosMpvRuntime(options: MpvPlaybackOptions): IosMpvRuntimeResult {
    val frameworkRoot = NSBundle.mainBundle.privateFrameworksPath
    val candidates =
        when (val source = options.runtimeSource) {
            MpvRuntimeSource.Bundled ->
                listOfNotNull(iosBundledMpvFrameworkPath(frameworkRoot))
            MpvRuntimeSource.System ->
                buildList<String?> {
                    add(null)
                    if (frameworkRoot != null) {
                        add("$frameworkRoot/KMediaMpv.framework/KMediaMpv")
                        add("$frameworkRoot/MPV.framework/MPV")
                        add("$frameworkRoot/libmpv.framework/libmpv")
                    }
                }
            is MpvRuntimeSource.ExplicitPath -> {
                val path = source.path
                val bundlePath = NSBundle.mainBundle.bundlePath.trimEnd('/')
                if (!path.startsWith('/') ||
                    path.split('/').any { it == ".." } ||
                    !path.startsWith("$bundlePath/") ||
                    !NSFileManager.defaultManager.fileExistsAtPath(path)
                ) {
                    return unavailable(
                        MpvBackendUnavailableReason.INVALID_RUNTIME,
                        "The iOS libmpv path must identify a code-signed binary inside the application bundle.",
                    )
                }
                listOf(path)
            }
        }

    var strongestFailure = CMP_MPV_LIBRARY_NOT_FOUND.toInt()
    candidates.forEach { candidate ->
        val probe =
            memScoped {
                val major = alloc<IntVar>()
                val minor = alloc<IntVar>()
                val status = cmp_mpv_probe(candidate, major.ptr, minor.ptr)
                if (status == CMP_MPV_OK.toInt()) {
                    return IosMpvRuntimeResult.Available(
                        IosMpvRuntimeResolution(
                            libraryPath = candidate,
                            versionMajor = major.value,
                            versionMinor = minor.value,
                        ),
                    )
                }
                status
            }
        if (probe != CMP_MPV_LIBRARY_NOT_FOUND.toInt()) {
            strongestFailure = probe
        }
    }
    if (options.runtimeSource == MpvRuntimeSource.Bundled &&
        strongestFailure == CMP_MPV_LIBRARY_NOT_FOUND.toInt()
    ) {
        return unavailable(
            MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
            "The verified KMediaMpv iOS framework is not embedded in this application. " +
                "Add the version-matched KMediaMpv CocoaPod so Xcode embeds and signs its XCFrameworks. " +
                "MpvRuntimeSource.System and ExplicitPath remain available for a custom libmpv.",
        )
    }
    if (options.runtimeSource is MpvRuntimeSource.ExplicitPath &&
        strongestFailure == CMP_MPV_LIBRARY_NOT_FOUND.toInt()
    ) {
        return unavailable(
            MpvBackendUnavailableReason.INVALID_RUNTIME,
            "The configured iOS framework binary exists but could not be loaded as libmpv.",
        )
    }
    return unavailable(strongestFailure.toPublicReason(), strongestFailure.toGuidance())
}

internal fun iosBundledMpvFrameworkPath(frameworkRoot: String?): String? =
    frameworkRoot?.trimEnd('/')?.let { "$it/KMediaMpv.framework/KMediaMpv" }

private fun unavailable(
    reason: MpvBackendUnavailableReason,
    guidance: String,
): IosMpvRuntimeResult.Unavailable =
    IosMpvRuntimeResult.Unavailable(
        MpvBackendAvailability.Unavailable(reason = reason, guidance = guidance),
    )

private fun Int.toPublicReason(): MpvBackendUnavailableReason =
    when (this) {
        CMP_MPV_LIBRARY_NOT_FOUND.toInt() -> MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING
        CMP_MPV_REQUIRED_SYMBOL_MISSING.toInt(),
        CMP_MPV_INCOMPATIBLE_CLIENT_API.toInt(),
        CMP_MPV_INVALID_ARGUMENT.toInt(),
        -> MpvBackendUnavailableReason.INVALID_RUNTIME
        CMP_MPV_INITIALIZATION_FAILED.toInt(),
        CMP_MPV_COMMAND_FAILED.toInt(),
        CMP_MPV_RENDER_FAILED.toInt(),
        -> MpvBackendUnavailableReason.INITIALIZATION_FAILED
        else -> MpvBackendUnavailableReason.INVALID_RUNTIME
    }

private fun Int.toGuidance(): String =
    when (this) {
        CMP_MPV_LIBRARY_NOT_FOUND.toInt() ->
            "No app-linked or embedded libmpv framework was found on iOS."
        CMP_MPV_REQUIRED_SYMBOL_MISSING.toInt() ->
            "The embedded iOS library is not a compatible libmpv; required client/render symbols are missing."
        CMP_MPV_INCOMPATIBLE_CLIENT_API.toInt() ->
            "The embedded iOS libmpv does not expose client API major 2."
        else -> "The embedded iOS libmpv failed its runtime probe."
    }

internal class IosLibMpvEngine private constructor(
    private var player: CPointer<cmp_mpv_player>?,
) {
    fun command(vararg arguments: String) {
        require(arguments.isNotEmpty()) { "An mpv command must not be empty." }
        val current = checkNotNull(player) { "The libmpv player is closed." }
        memScoped {
            val nativeArguments = allocArray<CPointerVar<ByteVar>>(arguments.size + 1)
            arguments.forEachIndexed { index, argument ->
                nativeArguments[index] = argument.cstr.getPointer(this)
            }
            nativeArguments[arguments.size] = null
            check(cmp_mpv_player_command(current, nativeArguments) == CMP_MPV_OK.toInt()) {
                "libmpv rejected the command."
            }
        }
    }

    fun setProperty(
        name: String,
        value: String,
    ) {
        val current = checkNotNull(player) { "The libmpv player is closed." }
        check(
            cmp_mpv_player_set_property(
                current,
                name,
                value,
            ) == CMP_MPV_OK.toInt(),
        ) {
            "libmpv rejected property $name."
        }
    }

    fun getProperty(name: String): String? {
        val current = player ?: return null
        val value =
            cmp_mpv_player_get_property(
                current,
                name,
            ) ?: return null
        return try {
            value.toKString()
        } finally {
            cmp_mpv_player_free_property(current, value)
        }
    }

    fun waitEvent(timeoutSeconds: Double): IosNativeMpvEvent {
        val current = player ?: return IosNativeMpvEvent.Shutdown
        return memScoped {
            val event = alloc<cmp_mpv_event>()
            check(cmp_mpv_player_wait_event(current, timeoutSeconds, event.ptr) == CMP_MPV_OK.toInt())
            when (event.event_id) {
                CMP_MPV_EVENT_NONE.toInt() -> IosNativeMpvEvent.None
                CMP_MPV_EVENT_SHUTDOWN.toInt() -> IosNativeMpvEvent.Shutdown
                CMP_MPV_EVENT_FILE_LOADED.toInt() -> IosNativeMpvEvent.FileLoaded
                CMP_MPV_EVENT_END_FILE.toInt() ->
                    IosNativeMpvEvent.EndFile(
                        reason = event.end_file_reason,
                        errorCode = event.error_code,
                    )
                CMP_MPV_EVENT_SEEK.toInt() -> IosNativeMpvEvent.SeekStarted
                CMP_MPV_EVENT_PLAYBACK_RESTART.toInt() -> IosNativeMpvEvent.PlaybackRestarted
                else -> IosNativeMpvEvent.None
            }
        }
    }

    fun render(
        width: Int,
        height: Int,
        rowBytes: ULong,
        pixels: COpaquePointer?,
    ) {
        val current = checkNotNull(player) { "The libmpv player is closed." }
        check(
            cmp_mpv_player_render_bgr0(
                current,
                width,
                height,
                rowBytes,
                pixels,
            ) == CMP_MPV_OK.toInt(),
        ) {
            "libmpv software rendering failed."
        }
    }

    fun wakeup() {
        player?.let(::cmp_mpv_player_wakeup)
    }

    fun close() {
        val current = player ?: return
        player = null
        cmp_mpv_player_destroy(current)
    }

    companion object {
        fun create(
            resolution: IosMpvRuntimeResolution,
            options: MpvPlaybackOptions,
        ): IosLibMpvEngine {
            val statusAndPlayer =
                memScoped {
                    val status = alloc<IntVar>()
                    val player =
                        cmp_mpv_player_create(
                            resolution.libraryPath,
                            options.subtitleFontsDirectory,
                            if (options.preserveAssStyles) 1 else 0,
                            if (options.useEmbeddedFonts) 1 else 0,
                            status.ptr,
                        )
                    status.value to player
                }
            val player =
                statusAndPlayer.second
                    ?: throw io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableException(
                        MpvBackendAvailability.Unavailable(
                            reason = statusAndPlayer.first.toPublicReason(),
                            guidance = statusAndPlayer.first.toGuidance(),
                        ),
                    )
            return IosLibMpvEngine(player)
        }
    }
}
