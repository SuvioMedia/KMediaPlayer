package io.github.kdroidfilter.composemediaplayer

import kotlin.time.Duration

/** One compressed timeline frame produced by an isolated Android decoder session. */
public data class AndroidMediaThumbnail(
    public val bytes: ByteArray,
    public val mimeType: String,
    public val timestamp: Duration,
    public val width: Int,
    public val height: Int,
)

/**
 * Optional Android media operations which must not seek or pause the visible player.
 *
 * Implementations decode in an isolated session and emit frames in input order. Each emitted
 * image is already compressed so callers can persist it immediately without retaining decoded
 * video buffers.
 */
public interface AndroidMediaAdvancedControls {
    public suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int = 320,
        emit: suspend (index: Int, thumbnail: AndroidMediaThumbnail?) -> Unit,
    )
}

/** Android capabilities implemented by the active backend, when available. */
public val VideoPlayerState.androidMediaAdvancedControls: AndroidMediaAdvancedControls?
    get() = unwrapDelegatingState() as? AndroidMediaAdvancedControls
