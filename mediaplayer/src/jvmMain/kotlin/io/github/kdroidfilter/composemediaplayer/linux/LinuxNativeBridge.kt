package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader
import java.nio.ByteBuffer

/**
 * JNI direct mapping to the native Linux GStreamer video player library.
 * Handles are opaque Long values (native pointer cast to jlong, 0 = null).
 */
internal object LinuxNativeBridge {
    /** Expected native API version — must match NATIVE_VIDEO_PLAYER_VERSION in the Linux .so. */
    private const val EXPECTED_NATIVE_VERSION = 13

    init {
        runCatching {
            NativeLibraryLoader.load("KMediaPlayerVulkanProjection", LinuxNativeBridge::class.java)
        }
        NativeLibraryLoader.load("NativeVideoPlayer", LinuxNativeBridge::class.java)
        val nativeVersion =
            runCatching { nGetNativeVersion() }
                .getOrElse {
                    throw IllegalStateException(
                        "NativeVideoPlayer Linux library is missing the native version API. " +
                            "Please rebuild libNativeVideoPlayer.so.",
                        it,
                    )
                }
        require(nativeVersion == EXPECTED_NATIVE_VERSION) {
            "NativeVideoPlayer Linux library version mismatch: expected $EXPECTED_NATIVE_VERSION " +
                "but got $nativeVersion. Please rebuild libNativeVideoPlayer.so or update the Kotlin bindings."
        }
    }

    // Playback control
    @JvmStatic external fun nGetNativeVersion(): Int

    @JvmStatic external fun nGetGStreamerRuntimeInfo(): IntArray?

    @JvmStatic external fun nIsGtkWaylandAdapterAvailable(): Boolean

    @JvmStatic external fun nIsGtkX11AdapterAvailable(): Boolean

    @JvmStatic external fun nIsVulkanProjectionRendererAvailable(): Boolean

    @JvmStatic external fun nQueryVulkanCapabilities(): Int

    /**
     * Returns flags, resolved wl_output global id, minimum luminance x10000,
     * maximum luminance and reference white, or null when the GTK Wayland connection cannot be
     * queried safely.
     */
    @JvmStatic external fun nQueryGtkWaylandColorCapabilities(outputId: Int): LongArray?

    @JvmStatic external fun nCreateNativeVideoWidget(
        handle: Long,
        libVlc: Boolean,
        integerConfiguration: IntArray?,
        floatingConfiguration: FloatArray?,
    ): Long

    @JvmStatic external fun nDisposeNativeVideoWidget(widget: Long)

    @JvmStatic external fun nUpdateWaylandHdrProjectionConfiguration(
        handle: Long,
        integerConfiguration: IntArray,
        floatingConfiguration: FloatArray,
    )

    @JvmStatic external fun nGetWaylandHdrOutputState(handle: Long): Int

    @JvmStatic external fun nGetDecodedVideoColorInfo(handle: Long): IntArray?

    @JvmStatic external fun nGetWaylandOutputId(handle: Long): Int

    @JvmStatic external fun nCreatePlayer(): Long

    @JvmStatic external fun nCreateLibVlcPlayer(
        libVlcPath: String,
        pluginPath: String,
        nativeVideoOutput: Boolean,
    ): Long

    @JvmStatic external fun nOpenLibVlcUriWithHeaders(
        handle: Long,
        uri: String,
        requestHeaders: String,
        startPlayback: Boolean,
    ): Boolean

    @JvmStatic external fun nOpenUri(
        handle: Long,
        uri: String,
    )

    @JvmStatic external fun nOpenUriWithHeaders(
        handle: Long,
        uri: String,
        requestHeaders: String,
    )

    @JvmStatic external fun nPlay(handle: Long)

    @JvmStatic external fun nPlayLibVlc(handle: Long)

    @JvmStatic external fun nPause(handle: Long)

    @JvmStatic external fun nPauseLibVlc(handle: Long)

    @JvmStatic external fun nSetVolume(
        handle: Long,
        volume: Float,
    )

    @JvmStatic external fun nSetLibVlcVolume(
        handle: Long,
        volume: Float,
    )

    @JvmStatic external fun nGetVolume(handle: Long): Float

    @JvmStatic external fun nGetLibVlcVolume(handle: Long): Float

    @JvmStatic external fun nSeekTo(
        handle: Long,
        time: Double,
    )

    @JvmStatic external fun nSeekLibVlcTo(
        handle: Long,
        time: Double,
    )

    @JvmStatic external fun nDisposePlayer(handle: Long)

    @JvmStatic external fun nDisposeLibVlcPlayer(handle: Long)

    @JvmStatic external fun nSetPlaybackSpeed(
        handle: Long,
        speed: Float,
    )

    @JvmStatic external fun nSetLibVlcPlaybackSpeed(
        handle: Long,
        speed: Float,
    )

    @JvmStatic external fun nGetPlaybackSpeed(handle: Long): Float

    @JvmStatic external fun nGetLibVlcPlaybackSpeed(handle: Long): Float

    // Frame access
    @JvmStatic external fun nLockFrame(
        handle: Long,
        outInfo: IntArray,
    ): Long

    @JvmStatic external fun nLockLibVlcFrame(
        handle: Long,
        outInfo: IntArray,
    ): Long

    @JvmStatic external fun nUnlockFrame(handle: Long)

    @JvmStatic external fun nUnlockLibVlcFrame(handle: Long)

    @JvmStatic external fun nWrapPointer(
        address: Long,
        size: Long,
    ): ByteBuffer?

    @JvmStatic external fun nGetFrameWidth(handle: Long): Int

    @JvmStatic external fun nGetLibVlcFrameWidth(handle: Long): Int

    @JvmStatic external fun nGetFrameHeight(handle: Long): Int

    @JvmStatic external fun nGetLibVlcFrameHeight(handle: Long): Int

    @JvmStatic external fun nSetOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ): Int

    @JvmStatic external fun nConfigureTextureOutput(
        handle: Long,
        width: Int,
        height: Int,
        inputP010: Boolean,
        outputHdr: Boolean,
        integerConfiguration: IntArray,
        floatingConfiguration: FloatArray,
    ): Boolean

    @JvmStatic external fun nDetachTextureOutput(handle: Long)

    /** serial, generation, size, FourCC, fd, stride, offset, modifier and acquire fence. */
    @JvmStatic external fun nAcquireTextureFrame(handle: Long): LongArray?

    @JvmStatic external fun nReleaseTextureFrame(
        handle: Long,
        generation: Long,
        serial: Long,
        dmaBufFd: Int,
        releaseFenceFd: Int,
    )

    // Timing
    @JvmStatic external fun nGetVideoDuration(handle: Long): Double

    @JvmStatic external fun nGetLibVlcVideoDuration(handle: Long): Double

    @JvmStatic external fun nGetCurrentTime(handle: Long): Double

    @JvmStatic external fun nGetLibVlcCurrentTime(handle: Long): Double

    // Metadata
    @JvmStatic external fun nGetVideoTitle(handle: Long): String?

    @JvmStatic external fun nGetVideoBitrate(handle: Long): Long

    @JvmStatic external fun nGetVideoMimeType(handle: Long): String?

    @JvmStatic external fun nGetVideoDecoderName(handle: Long): String?

    @JvmStatic external fun nGetAudioChannels(handle: Long): Int

    @JvmStatic external fun nGetAudioSampleRate(handle: Long): Int

    @JvmStatic external fun nGetFrameRate(handle: Long): Float

    @JvmStatic external fun nGetLibVlcFrameRate(handle: Long): Float

    // Playback completion
    @JvmStatic external fun nConsumeDidPlayToEnd(handle: Long): Boolean

    @JvmStatic external fun nConsumeLibVlcDidPlayToEnd(handle: Long): Boolean

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
