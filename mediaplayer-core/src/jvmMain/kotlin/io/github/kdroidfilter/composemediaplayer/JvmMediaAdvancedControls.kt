package io.github.kdroidfilter.composemediaplayer

import kotlin.time.Duration

/** One compressed timeline frame produced by an isolated JVM desktop decoder session. */
public data class JvmMediaThumbnail(
    public val bytes: ByteArray,
    public val mimeType: String,
    public val timestamp: Duration,
    public val width: Int,
    public val height: Int,
)

/**
 * Optional JVM desktop media operations which must not seek or pause the visible player.
 *
 * Implementations decode in an isolated session and emit frames in input order. Each emitted
 * image is already compressed so callers can persist it immediately without retaining decoded
 * video buffers.
 */
public interface JvmMediaAdvancedControls {
    public suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int = 320,
        emit: suspend (index: Int, thumbnail: JvmMediaThumbnail?) -> Unit,
    )
}

/** JVM desktop capabilities implemented by the active backend, when available. */
public val VideoPlayerState.jvmMediaAdvancedControls: JvmMediaAdvancedControls?
    get() = unwrapDelegatingState() as? JvmMediaAdvancedControls
