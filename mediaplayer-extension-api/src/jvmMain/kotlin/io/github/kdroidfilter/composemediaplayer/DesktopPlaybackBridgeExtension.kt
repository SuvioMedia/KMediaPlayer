package io.github.kdroidfilter.composemediaplayer

import java.io.Closeable

/** Runtime operations supported by an optional desktop container/playback bridge. */
public data class DesktopPlaybackBridgeCapabilities(
    public val canProbe: Boolean = false,
    public val canCopyVideo: Boolean = false,
    public val canToneMapToSdr: Boolean = false,
    public val canBurnSubtitles: Boolean = false,
)

/** Request passed to an optional desktop bridge when the platform cannot consume a source directly. */
public data class DesktopPlaybackBridgeRequest(
    public val uri: String,
    public val requestHeaders: Map<String, String> = emptyMap(),
    public val selectedAudioStreamIndex: Int? = null,
    public val selectedSubtitleStreamIndex: Int? = null,
    public val startPositionMs: Long = 0L,
    public val allowHdrCmafPassthrough: Boolean = false,
    public val requireHdrCmafPassthrough: Boolean = false,
    public val forceSdrOutput: Boolean = false,
) {
    init {
        require(uri.isNotBlank()) { "A desktop bridge URI must not be blank." }
        require(selectedAudioStreamIndex == null || selectedAudioStreamIndex >= 0) {
            "A selected audio stream index cannot be negative."
        }
        require(selectedSubtitleStreamIndex == null || selectedSubtitleStreamIndex >= 0) {
            "A selected subtitle stream index cannot be negative."
        }
        require(startPositionMs >= 0L) { "A desktop bridge start position cannot be negative." }
        require(!requireHdrCmafPassthrough || allowHdrCmafPassthrough) {
            "Required HDR CMAF passthrough must also be allowed."
        }
        require(!forceSdrOutput || !allowHdrCmafPassthrough) {
            "Forced SDR output cannot be combined with HDR CMAF passthrough."
        }
    }
}

/** Decoder-ready stream and tracks produced by an optional desktop bridge. */
public data class DesktopPlaybackBridgeSource(
    public val playlistUrl: String,
    public val durationMs: Long?,
    public val playbackOffsetMs: Long = 0L,
    public val audioTracks: List<AudioTrack> = emptyList(),
    public val selectedAudioStreamIndex: Int? = null,
    public val subtitleTracks: List<SubtitleTrack> = emptyList(),
    public val selectedSubtitleStreamIndex: Int? = null,
    public val inputColorInfo: VideoColorInfo = VideoColorInfo(),
    public val outputColorInfo: VideoColorInfo = VideoColorInfo(),
    public val toneMappedHdrToSdr: Boolean = false,
    public val hdrCmafPassthrough: Boolean = false,
    public val videoCopiedWithoutReencoding: Boolean = false,
    public val detail: String? = null,
) {
    init {
        require(playlistUrl.isNotBlank()) { "A desktop bridge playlist URL must not be blank." }
        require(durationMs == null || durationMs >= 0L) { "A desktop bridge duration cannot be negative." }
        require(playbackOffsetMs >= 0L) { "A desktop bridge playback offset cannot be negative." }
        require(selectedAudioStreamIndex == null || selectedAudioStreamIndex >= 0) {
            "A selected audio stream index cannot be negative."
        }
        require(selectedSubtitleStreamIndex == null || selectedSubtitleStreamIndex >= 0) {
            "A selected subtitle stream index cannot be negative."
        }
        require(!toneMappedHdrToSdr || !hdrCmafPassthrough) {
            "A bridge source cannot be both tone-mapped to SDR and HDR passthrough."
        }
        require(!toneMappedHdrToSdr || !videoCopiedWithoutReencoding) {
            "A tone-mapped bridge source cannot claim that its video was copied unchanged."
        }
        require(!hdrCmafPassthrough || videoCopiedWithoutReencoding) {
            "HDR CMAF passthrough requires an unchanged compressed video signal."
        }
    }
}

/** One player-scoped bridge transport. Closing it releases native and local-server resources. */
public interface DesktopPlaybackBridgeSession : Closeable {
    public val source: DesktopPlaybackBridgeSource
}

/**
 * Optional JVM bridge for containers and streams unsupported by the platform player.
 *
 * The extension object is reusable configuration; every [open] call returns an independently
 * owned session.
 */
public interface DesktopPlaybackBridgeExtension : VideoPipelineExtension {
    public val desktopCapabilities: DesktopPlaybackBridgeCapabilities

    public suspend fun open(request: DesktopPlaybackBridgeRequest): DesktopPlaybackBridgeSession
}
