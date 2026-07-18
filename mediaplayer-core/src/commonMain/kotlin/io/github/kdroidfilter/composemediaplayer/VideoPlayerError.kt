package io.github.kdroidfilter.composemediaplayer

/**
 * Represents different types of errors that can occur during video playback in a video player.
 *
 * This sealed class is used for error reporting and handling within the video player system.
 * Each type of error is represented as a subclass of `VideoPlayerError` with an associated descriptive message.
 *
 * Subclasses:
 * - `CodecError`: Indicates a decoder or codec failure.
 * - `UnsupportedCodecError`: Indicates media that the current platform cannot decode.
 * - `NetworkError`: Represents network-related problems, like connectivity issues.
 * - `CorsError`: Represents browser CORS failures.
 * - `SourceError`: Relates to issues with the video source, such as an invalid or unavailable file/URL.
 * - `NoSourceError`: Indicates that no usable source was provided or resolved.
 * - `TimeoutError`: Indicates a load or playback operation timed out.
 * - `HlsError`: Represents HLS manifest/segment/controller failures.
 * - `UnknownError`: Covers any issues that do not fit into the other categories.
 */
sealed class VideoPlayerError {
    data class CodecError(
        val message: String,
    ) : VideoPlayerError()

    data class UnsupportedCodecError(
        val message: String,
    ) : VideoPlayerError()

    data class NetworkError(
        val message: String,
    ) : VideoPlayerError()

    data class CorsError(
        val message: String,
    ) : VideoPlayerError()

    data class SourceError(
        val message: String,
    ) : VideoPlayerError()

    data class NoSourceError(
        val message: String,
    ) : VideoPlayerError()

    data class TimeoutError(
        val message: String,
    ) : VideoPlayerError()

    data class HlsError(
        val message: String,
        val type: String? = null,
        val details: String? = null,
        val fatal: Boolean = true,
    ) : VideoPlayerError()

    data class UnknownError(
        val message: String,
    ) : VideoPlayerError()
}
