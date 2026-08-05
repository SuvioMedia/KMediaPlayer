package io.github.kdroidfilter.composemediaplayer.desktop

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Credential-safe random-access source for desktop backends whose native runtime has networking disabled.
 * Implementations own authentication, range requests and caching outside the native decoder.
 */
public fun interface JvmSeekableMediaDataSourceFactory {
    public suspend fun open(request: DesktopPlaybackRequest): JvmSeekableMediaDataSource
}

/** One independently owned seekable media source. */
public interface JvmSeekableMediaDataSource : Closeable {
    /** Total byte length, or `null` while it is not known. */
    public val length: Long?

    /**
     * Reads at [position] without changing shared cursor state. Returns `-1` at end of input.
     * Implementations must never include request credentials in thrown exception messages.
     */
    public suspend fun read(
        position: Long,
        destination: ByteBuffer,
    ): Int
}
