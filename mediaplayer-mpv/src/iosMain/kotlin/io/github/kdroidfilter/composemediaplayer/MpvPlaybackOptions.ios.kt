@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mpv.IosLibMpvEngine
import io.github.kdroidfilter.composemediaplayer.mpv.IosMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mpv.inspectIosMpvRuntime
import io.github.kdroidfilter.composemediaplayer.mpv.requireIosMpvRuntime
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory

internal actual fun mpvBackendInfo(): VideoPlayerBackendInfo =
    VideoPlayerBackendInfo(
        id = "mpv",
        displayName = "MPV (iOS)",
        capabilities =
            PlayerCapabilities(
                supportsMkv = true,
                supportedUriSchemes = setOf("file"),
            ),
    )

actual fun inspectMpvBackend(options: MpvPlaybackOptions): MpvBackendAvailability {
    val fontError = options.validateIosSubtitleFontsDirectory()
    if (fontError != null) {
        return MpvBackendAvailability.Unavailable(
            reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
            guidance = fontError,
        )
    }
    return inspectIosMpvRuntime(options)
}

actual fun createMpvVideoPlayerState(options: MpvPlaybackOptions): VideoPlayerState {
    options.validateIosSubtitleFontsDirectory()?.let { guidance ->
        throw MpvBackendUnavailableException(
            MpvBackendAvailability.Unavailable(
                reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
                guidance = guidance,
            ),
        )
    }
    val resolution = requireIosMpvRuntime(options)
    val engine = IosLibMpvEngine.create(resolution, options)
    return try {
        IosMpvVideoPlayerState(
            options = options,
            engine = engine,
        )
    } catch (failure: Throwable) {
        engine.close()
        throw failure
    }
}

private fun MpvPlaybackOptions.validateIosSubtitleFontsDirectory(): String? {
    val path = subtitleFontsDirectory ?: return null
    val bundlePath = NSBundle.mainBundle.bundlePath.trimEnd('/')
    val homePath = NSHomeDirectory().trimEnd('/')
    if (!path.startsWith('/') ||
        path.split('/').any { it == ".." } ||
        (!path.startsWith("$bundlePath/") && !path.startsWith("$homePath/"))
    ) {
        return "The iOS subtitle-font directory must be an app-private absolute path without parent traversal."
    }
    val isDirectory =
        memScoped {
            val directory = alloc<BooleanVar>()
            val exists =
                NSFileManager.defaultManager.fileExistsAtPath(
                    path,
                    isDirectory = directory.ptr,
                )
            exists && directory.value
        }
    return if (isDirectory) {
        null
    } else {
        "The configured iOS subtitle-font directory does not exist."
    }
}
