package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader
import java.awt.Component
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
    ): Long

    @JvmStatic external fun nCreateLibAssRenderer(libAssPath: String): Long

    @JvmStatic external fun nSetLibAssTrack(
        handle: Long,
        assData: String,
    ): Boolean

    @JvmStatic external fun nAddLibAssFont(
        handle: Long,
        name: String,
        data: ByteArray,
    ): Boolean

    @JvmStatic external fun nBlendLibAssFrame(
        handle: Long,
        pixelsAddress: Long,
        rowBytes: Int,
        width: Int,
        height: Int,
        timeMs: Long,
    ): Boolean

    @JvmStatic external fun nDisposeLibAssRenderer(handle: Long)

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

    @JvmStatic external fun nGetHdrCapabilities(): String?

    @JvmStatic external fun nAttachHdrMetalView(
        handle: Long,
        component: Component,
    ): Boolean

    @JvmStatic external fun nDetachHdrMetalView(
        handle: Long,
        component: Component,
    )

    @JvmStatic external fun nSetHdrMetalContentScaleMode(
        handle: Long,
        mode: Int,
    )

    @JvmStatic external fun nIsHdrMetalAvailable(handle: Long): Boolean

    // Timing / rate info
    @JvmStatic external fun nGetVideoFrameRate(handle: Long): Float

    @JvmStatic external fun nGetScreenRefreshRate(handle: Long): Float

    @JvmStatic external fun nGetCaptureFrameRate(handle: Long): Float

    @JvmStatic external fun nGetVideoDuration(handle: Long): Double

    @JvmStatic external fun nGetCurrentTime(handle: Long): Double

    // Metadata
    @JvmStatic external fun nGetVideoTitle(handle: Long): String?

    @JvmStatic external fun nGetVideoBitrate(handle: Long): Long

    @JvmStatic external fun nGetVideoMimeType(handle: Long): String?

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
