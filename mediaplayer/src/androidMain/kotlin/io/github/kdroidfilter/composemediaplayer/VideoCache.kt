package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Process-wide owner of the shared Media3 cache.
 *
 * Media3 permits only one [SimpleCache] for a directory at a time. The first active player therefore defines the
 * process-wide size limit. A player requesting a different limit shares that cache until every lease is closed; the
 * next player may then establish a new limit. In particular, creating a second player never releases a cache still
 * used by the first one.
 */
@UnstableApi
internal object VideoCache {
    private var simpleCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var configuredMaxBytes: Long = 0L
    private var leaseCount: Int = 0

    class Lease internal constructor(
        val cache: Cache,
        val configuredMaxBytes: Long,
    ) : AutoCloseable {
        private var closed = false

        fun clear() {
            synchronized(VideoCache) {
                check(!closed) { "Video cache lease has been closed." }
                cache.keys.toList().forEach(cache::removeResource)
            }
        }

        override fun close() {
            synchronized(VideoCache) {
                if (closed) return
                closed = true
                releaseLease()
            }
        }
    }

    @Synchronized
    fun acquire(
        context: Context,
        maxCacheSizeBytes: Long,
    ): Lease {
        val cache =
            simpleCache ?: createCache(context.applicationContext, maxCacheSizeBytes).also {
                simpleCache = it
                configuredMaxBytes = maxCacheSizeBytes
            }
        if (configuredMaxBytes != maxCacheSizeBytes) {
            androidVideoLogger.d {
                "Video cache is already configured with $configuredMaxBytes bytes; " +
                    "sharing it instead of replacing it with $maxCacheSizeBytes bytes while it is in use."
            }
        }
        leaseCount += 1
        return Lease(cache = cache, configuredMaxBytes = configuredMaxBytes)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createCache(
        context: Context,
        maxCacheSizeBytes: Long,
    ): SimpleCache {
        val cacheDir = File(context.cacheDir, "compose_media_player_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(maxCacheSizeBytes)
        val provider = StandaloneDatabaseProvider(context)
        return try {
            SimpleCache(cacheDir, evictor, provider).also { databaseProvider = provider }
        } catch (throwable: Throwable) {
            provider.close()
            throw throwable
        }
    }

    private fun releaseLease() {
        check(leaseCount > 0) { "Video cache lease count underflow." }
        leaseCount -= 1
        if (leaseCount == 0) {
            try {
                simpleCache?.release()
            } finally {
                try {
                    databaseProvider?.close()
                } finally {
                    simpleCache = null
                    databaseProvider = null
                    configuredMaxBytes = 0L
                }
            }
        }
    }

    @get:Synchronized
    internal val activeLeaseCount: Int
        get() = leaseCount

    @get:Synchronized
    internal val activeMaxCacheSizeBytes: Long?
        get() = configuredMaxBytes.takeIf { simpleCache != null }
}

/** Builds one upstream factory so request headers and cache can be composed instead of being mutually exclusive. */
@OptIn(UnstableApi::class)
internal fun buildAndroidDataSourceFactory(
    context: Context,
    cache: Cache?,
    requestHeaders: Map<String, String> = emptyMap(),
): DataSource.Factory {
    val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
    val httpFactory =
        DefaultHttpDataSource
            .Factory()
            .setDefaultRequestProperties(sanitizedHeaders)
    val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
    return if (cache == null) {
        upstreamFactory
    } else {
        CacheDataSource
            .Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory(headerAwareCacheKeyFactory(sanitizedHeaders))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

private fun headerAwareCacheKeyFactory(defaultHeaders: Map<String, String>): CacheKeyFactory =
    CacheKeyFactory { dataSpec ->
        val effectiveHeaders =
            normalizedCacheHeaders(
                defaultHeaders,
                dataSpec.httpRequestHeaders.sanitizedRequestHeaders(),
            )
        buildAndroidCacheKey(dataSpec, effectiveHeaders)
    }

internal fun buildAndroidCacheKey(
    dataSpec: DataSpec,
    normalizedHeaders: Map<String, String>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateLengthPrefixed(CACHE_KEY_VERSION)
    digest.updateLengthPrefixed(dataSpec.key ?: dataSpec.uri.toString())
    normalizedHeaders
        .toSortedMap()
        .forEach { (name, value) ->
            digest.updateLengthPrefixed(name)
            digest.updateLengthPrefixed(value)
        }
    return CACHE_KEY_PREFIX +
        digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }
}

private fun normalizedCacheHeaders(vararg sources: Map<String, String>): Map<String, String> =
    buildMap {
        sources.forEach { headers ->
            headers.forEach { (name, value) ->
                put(name.lowercase(Locale.ROOT), value)
            }
        }
    }

private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update((bytes.size ushr MOST_SIGNIFICANT_BYTE_SHIFT).toByte())
    update((bytes.size ushr SECOND_BYTE_SHIFT).toByte())
    update((bytes.size ushr THIRD_BYTE_SHIFT).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

private const val CACHE_KEY_VERSION = "compose-media-player-cache-v1"
private const val CACHE_KEY_PREFIX = "kmp:v1:"
private const val HEX_RADIX = 16
private const val HEX_BYTE_WIDTH = 2
private const val BYTE_MASK = 0xff
private const val MOST_SIGNIFICANT_BYTE_SHIFT = 24
private const val SECOND_BYTE_SHIFT = 16
private const val THIRD_BYTE_SHIFT = 8
