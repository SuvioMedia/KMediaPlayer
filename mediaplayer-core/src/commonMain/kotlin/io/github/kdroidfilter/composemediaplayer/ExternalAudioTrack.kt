package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import kotlin.time.Duration

/** Determines whether an external track replaces programme audio or plays over it. */
enum class ExternalAudioPlaybackMode {
    REPLACE,
    OVERLAY,
}

/** A timeline interval during which programme audio should be ducked for an overlay track. */
@Stable
data class ExternalAudioDuckingInterval(
    val start: Duration,
    val endExclusive: Duration,
) {
    init {
        require(start.isFinite() && start >= Duration.ZERO) { "Audio ducking start must be finite and non-negative." }
        require(endExclusive.isFinite() && endExclusive > start) {
            "Audio ducking end must be finite and follow its start."
        }
    }
}

/**
 * An app-provided audio track that is aligned to the current content timeline.
 *
 * The source and request-header values are deliberately omitted from [toString] because either may contain
 * short-lived playback credentials. External audio is session-bound: platform implementations clear it when a new
 * primary source is opened or the current source is released.
 */
@Stable
data class ExternalAudioTrack(
    val id: String,
    val label: String,
    val source: MediaSourceSpec,
    val language: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val isDefault: Boolean = false,
    val playbackMode: ExternalAudioPlaybackMode = ExternalAudioPlaybackMode.REPLACE,
    val duckingIntervals: List<ExternalAudioDuckingInterval> = emptyList(),
    val duckingVolumeMultiplier: Float = 1f,
) {
    init {
        require(id.isNotBlank()) { "External audio track id must not be blank." }
        require(label.isNotBlank()) { "External audio track label must not be blank." }
        require(source.uri.isNotBlank()) { "External audio track source URI must not be blank." }
        require(channels == null || channels > 0) { "External audio channel count must be positive." }
        require(sampleRate == null || sampleRate > 0) { "External audio sample rate must be positive." }
        require(bitrate == null || bitrate >= 0) { "External audio bitrate must not be negative." }
        require(duckingVolumeMultiplier in 0f..1f) { "Audio ducking volume multiplier must be between zero and one." }
        require(
            duckingIntervals.zipWithNext().all { (previous, next) -> previous.endExclusive <= next.start },
        ) { "Audio ducking intervals must be ordered and must not overlap." }
    }

    fun asAudioTrack(): AudioTrack =
        AudioTrack(
            id = id,
            label = label,
            language = language,
            channels = channels,
            sampleRate = sampleRate,
            bitrate = bitrate,
            isDefault = isDefault,
            isEmbedded = false,
        )

    override fun toString(): String =
        "ExternalAudioTrack(id=$id, label=$label, language=$language, " +
            "source=<redacted>, requestHeaderCount=${requestHeaders.size}, channels=$channels, " +
            "sampleRate=$sampleRate, bitrate=$bitrate, isDefault=$isDefault, playbackMode=$playbackMode, " +
            "duckingIntervalCount=${duckingIntervals.size}, duckingVolumeMultiplier=$duckingVolumeMultiplier)"
}
