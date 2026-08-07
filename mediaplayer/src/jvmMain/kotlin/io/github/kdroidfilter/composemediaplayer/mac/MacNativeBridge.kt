package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader
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

    /** Warms a second AVPlayer while the current item and native layer remain untouched. */
    @JvmStatic external fun nPrepareUriReplacement(
        handle: Long,
        uri: String,
        requestHeadersJson: String,
    ): Long

    /** 0 = preparing, 1 = first frame decoded, negative = failed or superseded. */
    @JvmStatic external fun nGetUriReplacementStatus(
        handle: Long,
        token: Long,
    ): Int

    @JvmStatic external fun nGetUriReplacementError(
        handle: Long,
        token: Long,
    ): String?

    /** Switches the existing AVPlayerLayer to the prepared player in one AppKit transaction. */
    @JvmStatic external fun nCommitUriReplacement(
        handle: Long,
        token: Long,
    ): Boolean

    @JvmStatic external fun nCancelUriReplacement(
        handle: Long,
        token: Long,
    )

    @JvmStatic external fun nPlay(handle: Long)

    @JvmStatic external fun nPause(handle: Long)

    /** Stops decoding and disconnects native video output without releasing the handle. */
    @JvmStatic external fun nRetirePlayer(handle: Long)

    /** True after the active AVFoundation item is ready for playback. */
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

    /** Enables the RGBA16Float IOSurface output on the Tao/Skia Metal queue; queue 0 disables it. */
    @JvmStatic external fun nSetHdrMetalTextureOutput(
        handle: Long,
        commandQueue: Long,
    ): Boolean

    @JvmStatic external fun nSetHdrMetalTextureViewportSize(
        handle: Long,
        width: Int,
        height: Int,
    )

    /** outInfo = [IOSurface pointer, width, height, completed-frame serial]. */
    @JvmStatic external fun nGetHdrMetalTextureOutputInfo(
        handle: Long,
        outInfo: LongArray,
    ): Boolean

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

    /** Capabilities of the NSScreen currently hosting a Tao native child view. */
    @JvmStatic external fun nGetDisplayColorCapabilitiesForView(nativeView: Long): String?

    /** Creates an owned AppKit child view for Nucleus `NativeView` embedding. */
    @JvmStatic external fun nCreateNativeVideoView(handle: Long): Long

    /** Disconnects the renderer and releases the AppKit child created above. */
    @JvmStatic external fun nDisposeNativeVideoView(
        handle: Long,
        nativeView: Long,
    )

    /** Resizes the owning NSWindow in place without AppKit's fullscreen-Space reparenting. */
    @JvmStatic external fun nSetNativeWindowFullscreen(
        nativeView: Long,
        fullscreen: Boolean,
    ): Boolean

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
