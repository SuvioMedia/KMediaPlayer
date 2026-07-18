package io.github.kdroidfilter.composemediaplayer

/**
 * Configuration for video caching. When enabled, downloaded video data is stored
 * on disk so that subsequent plays of the same URI load from the local cache
 * instead of re-downloading.
 *
 * The cache is shared across all [VideoPlayerState] instances that use the same
 * configuration, which makes it ideal for scroll-based UIs (e.g. VerticalPager)
 * where multiple player instances may load the same URLs.
 *
 * Caching only applies to URIs opened via [VideoPlayerState.openUri]; local files
 * and assets are not cached.
 *
 * Currently supported on **Android** only. iOS deliberately reports caching as unsupported instead
 * of mutating the host application's process-global `NSURLCache`; other platforms accept the
 * configuration but do not cache media.
 *
 * @param enabled Whether caching is active. Default is `false`.
 * @param maxCacheSizeBytes Maximum disk space the cache may use, in bytes.
 *   When the limit is reached, the least-recently-used entries are evicted.
 *   Default is 100 MB.
 */
data class CacheConfig(
    val enabled: Boolean = false,
    val maxCacheSizeBytes: Long = 100L * 1024L * 1024L,
) {
    init {
        require(maxCacheSizeBytes > 0L) { "maxCacheSizeBytes must be greater than 0." }
    }
}

sealed class CacheClearResult {
    data object Cleared : CacheClearResult()

    data object Disabled : CacheClearResult()

    data object NotSupported : CacheClearResult()

    data class Failed(
        val message: String,
    ) : CacheClearResult()
}
