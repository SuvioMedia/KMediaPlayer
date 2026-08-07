package io.github.kdroidfilter.composemediaplayer.desktop

import java.io.Closeable

/**
 * Creates a short-lived loopback HTTP view of one remote media source.
 *
 * Implementations keep the original URI and request headers outside the native decoder. For HLS,
 * playlist references must be rewritten so every manifest, key, initialization fragment and media
 * segment remains behind [JvmHlsMediaProxy.localUri]. Progressive sources must preserve Range and
 * HEAD semantics so a native decoder can begin and seek without downloading the whole file.
 */
public fun interface JvmHlsMediaProxyFactory {
    public suspend fun openProxy(request: DesktopPlaybackRequest): JvmHlsMediaProxy
}

/** One independently owned loopback media endpoint. */
public interface JvmHlsMediaProxy : Closeable {
    /** An uncredentialed HTTP URI bound to the numeric loopback interface. */
    public val localUri: String
}
