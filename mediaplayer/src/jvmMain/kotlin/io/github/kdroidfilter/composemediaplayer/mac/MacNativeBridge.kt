package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader
import java.awt.Component
import java.awt.Window
import java.nio.ByteBuffer

/**
 * JNI direct mapping to the native macOS video player library.
 * Handles are opaque Long values (native pointer cast to jlong, 0 = null).
 */
internal object MacNativeBridge {
    init {
        NativeLibraryLoader.load("NativeVideoPlayer", MacNativeBridge::class.java)
    }

    // Playback control
    @JvmStatic external fun nCreatePlayer(): Long

    @JvmStatic external fun nCreateLibVlcPlayer(
        libVlcPath: String,
        pluginPath: String,
        nativeVideoOutput: Boolean,
    ): Long

    @JvmStatic external fun nOpenUri(
        handle: Long,
        uri: String,
    )

    @JvmStatic external fun nOpenUriWithHeaders(
        handle: Long,
        uri: String,
        requestHeadersJson: String,
    )

    @JvmStatic external fun nOpenUriWithHeaderLines(
        handle: Long,
        uri: String,
        requestHeaders: String,
    )

    @JvmStatic external fun nPlay(handle: Long)

    @JvmStatic external fun nPause(handle: Long)

    /** True after the most recently opened AVFoundation item has replaced the previous one. */
    @JvmStatic external fun nIsReadyForPlayback(handle: Long): Boolean

    @JvmStatic external fun nSetVolume(
        handle: Long,
        volume: Float,
    )

    @JvmStatic external fun nGetVolume(handle: Long): Float

    @JvmStatic external fun nSeekTo(
        handle: Long,
        time: Double,
    )

    @JvmStatic external fun nDisposePlayer(handle: Long)

    @JvmStatic external fun nSetPlaybackSpeed(
        handle: Long,
        speed: Float,
    )

    @JvmStatic external fun nGetPlaybackSpeed(handle: Long): Float

    // Frame access — lock/unlock the native frame buffer directly.
    // outInfo must be IntArray(3); filled with [width, height, bytesPerRow] on success.
    // Returns the native base address of the locked buffer, or 0 on failure.
    // MUST call nUnlockFrame after reading.
    @JvmStatic external fun nLockFrame(
        handle: Long,
        outInfo: IntArray,
    ): Long

    @JvmStatic external fun nUnlockFrame(handle: Long)

    @JvmStatic external fun nWrapPointer(
        address: Long,
        size: Long,
    ): ByteBuffer?

    @JvmStatic external fun nGetFrameWidth(handle: Long): Int

    @JvmStatic external fun nGetFrameHeight(handle: Long): Int

    @JvmStatic external fun nGetDisplayAspectRatio(handle: Long): Double

    @JvmStatic external fun nSetOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ): Int

    @JvmStatic external fun nSetHdrMetalPreferred(
        handle: Long,
        preferred: Boolean,
    )

    @JvmStatic external fun nSetHdrToneMappingEnabled(
        handle: Long,
        enabled: Boolean,
    )

    @JvmStatic external fun nSetHdrMetalProjectionConfiguration(
        handle: Long,
        configuration: String,
    ): Boolean

    @JvmStatic external fun nGetHdrRendererFailure(handle: Long): String?

    /** Capabilities of the screen currently hosting this player's native layer. */
    @JvmStatic external fun nGetDisplayColorCapabilities(handle: Long): String?

    @JvmStatic external fun nAttachHdrMetalView(
        handle: Long,
        component: Component,
    ): Boolean

    @JvmStatic external fun nDetachHdrMetalView(
        handle: Long,
        component: Component,
    )

    /** Attaches the native AVPlayer/Metal layer below a transparent Compose layer in a full window. */
    @JvmStatic external fun nAttachHdrMetalWindow(
        handle: Long,
        window: Window,
    ): Boolean

    @JvmStatic external fun nAttachLibVlcNativeView(
        handle: Long,
        component: Component,
    ): Boolean

    @JvmStatic external fun nDetachLibVlcNativeView(
        handle: Long,
        component: Component,
    )

    /** Attaches libVLC below a transparent Compose layer and lets AppKit own window resizing. */
    @JvmStatic external fun nAttachLibVlcNativeWindow(
        handle: Long,
        window: Window,
    ): Boolean

    /** Requests AppKit's native full-screen transition for a dedicated native video window. */
    @JvmStatic external fun nSetWindowFullscreen(
        window: Window,
        fullscreen: Boolean,
    ): Boolean

    /** Configures opaque standard AppKit chrome for the dedicated video window. */
    @JvmStatic external fun nConfigureNativeWindow(window: Window): Boolean

    /** Returns the current AppKit NSWindow full-screen style state. */
    @JvmStatic external fun nIsWindowFullscreen(window: Window): Boolean

    @JvmStatic external fun nSetHdrMetalContentScaleMode(
        handle: Long,
        mode: Int,
    )

    @JvmStatic external fun nIsHdrMetalAvailable(handle: Long): Boolean

    /** True only after AVPlayerLayer/CoreAnimation or the controlled Metal renderer has a frame to display. */
    @JvmStatic external fun nIsHdrOutputReady(handle: Long): Boolean

    // Timing / rate info
    @JvmStatic external fun nGetVideoFrameRate(handle: Long): Float

    @JvmStatic external fun nGetScreenRefreshRate(handle: Long): Float

    @JvmStatic external fun nGetCaptureFrameRate(handle: Long): Float

    @JvmStatic external fun nGetPlaybackDiagnostics(handle: Long): String?

    @JvmStatic external fun nGetVideoDuration(handle: Long): Double

    @JvmStatic external fun nGetCurrentTime(handle: Long): Double

    // Metadata
    @JvmStatic external fun nGetVideoTitle(handle: Long): String?

    @JvmStatic external fun nGetVideoBitrate(handle: Long): Long

    @JvmStatic external fun nGetVideoMimeType(handle: Long): String?

    @JvmStatic external fun nGetVideoColorInfo(handle: Long): String?

    @JvmStatic external fun nGetAudioChannels(handle: Long): Int

    @JvmStatic external fun nGetAudioSampleRate(handle: Long): Int

    // Playback completion
    @JvmStatic external fun nConsumeDidPlayToEnd(handle: Long): Boolean

    @JvmStatic external fun nSelectLibVlcAudioTrack(
        handle: Long,
        ordinal: Int,
    ): Boolean

    @JvmStatic external fun nSelectLibVlcSubtitleTrack(
        handle: Long,
        ordinal: Int,
    ): Boolean

    @JvmStatic external fun nGetLibVlcAudioTrackDescriptions(handle: Long): String?

    @JvmStatic external fun nGetLibVlcSubtitleTrackDescriptions(handle: Long): String?

    @JvmStatic external fun nDisableLibVlcSubtitles(handle: Long): Boolean
}
