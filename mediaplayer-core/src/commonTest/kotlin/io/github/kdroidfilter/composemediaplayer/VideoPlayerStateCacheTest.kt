package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoPlayerStateCacheTest {
    @Test
    fun defaultClearCacheReportsUnsupportedCache() {
        val state = PreviewableVideoPlayerState()

        assertEquals(CacheClearResult.NotSupported, state.clearCache())
    }

    @Test
    fun cacheConfigRejectsNonPositiveCacheSize() {
        assertFailsWith<IllegalArgumentException> {
            CacheConfig(maxCacheSizeBytes = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            CacheConfig(maxCacheSizeBytes = -1L)
        }
    }
}
