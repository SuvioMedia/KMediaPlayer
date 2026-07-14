package io.github.kdroidfilter.composemediaplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.kdroid.androidcontextprovider.ContextProvider
import com.sun.net.httpserver.HttpServer
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("MagicNumber")
class VideoCacheTest {
    @Before
    fun setup() {
        ContextProvider.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun differentlyConfiguredPlayersShareCacheUntilLastLeaseCloses() {
        val context = ContextProvider.getContext()
        val leasesBefore = VideoCache.activeLeaseCount
        val maxBytesBefore = VideoCache.activeMaxCacheSizeBytes
        val first = VideoCache.acquire(context, 3L * 1024L * 1024L)
        val second = VideoCache.acquire(context, 7L * 1024L * 1024L)
        try {
            assertSame(first.cache, second.cache)
            assertEquals(first.configuredMaxBytes, second.configuredMaxBytes)
            assertEquals(leasesBefore + 2, VideoCache.activeLeaseCount)
            if (leasesBefore == 0) assertEquals(3L * 1024L * 1024L, first.configuredMaxBytes)

            first.close()
            assertEquals(leasesBefore + 1, VideoCache.activeLeaseCount)
            assertSame(second.cache, VideoCache.acquire(context, 11L * 1024L * 1024L).also { it.close() }.cache)
        } finally {
            first.close()
            second.close()
        }

        assertEquals(leasesBefore, VideoCache.activeLeaseCount)
        if (leasesBefore == 0) {
            assertNull(VideoCache.activeMaxCacheSizeBytes)
        } else {
            assertEquals(maxBytesBefore, VideoCache.activeMaxCacheSizeBytes)
        }
    }

    @Test
    fun requestHeadersAndCacheAreAppliedByTheSameDataSource() {
        val expectedBody = "cached response".encodeToByteArray()
        val receivedAuthorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestPath = "/video-${System.nanoTime()}"
        server.createContext(requestPath) { exchange ->
            receivedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(200, expectedBody.size.toLong())
            exchange.responseBody.use { it.write(expectedBody) }
        }
        server.start()

        val path = server.address.port.let { port -> "http://127.0.0.1:$port$requestPath" }
        val lease = VideoCache.acquire(ContextProvider.getContext(), 3L * 1024L * 1024L)
        try {
            val factory =
                buildAndroidDataSourceFactory(
                    context = ContextProvider.getContext(),
                    cache = lease.cache,
                    requestHeaders = mapOf("Authorization" to "Bearer android-test"),
                )
            assertContentEquals(expectedBody, factory.readAll(path))
            assertEquals("Bearer android-test", receivedAuthorization.get())

            server.stop(0)
            assertContentEquals(expectedBody, factory.readAll(path), "Second read should succeed from cache.")
        } finally {
            runCatching { server.stop(0) }
            lease.clear()
            lease.close()
        }
    }

    @Test
    fun cacheEntriesAreIsolatedByAuthorizationAndCookieHeaders() {
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestPath = "/private-video-${System.nanoTime()}"
        server.createContext(requestPath) { exchange ->
            requestCount.incrementAndGet()
            val body =
                listOf(
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Cookie"),
                ).joinToString("|").encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val path = server.address.port.let { port -> "http://127.0.0.1:$port$requestPath" }
        val firstBody = "Bearer first|session=first".encodeToByteArray()
        val secondBody = "Bearer second|session=second".encodeToByteArray()
        val lease = VideoCache.acquire(ContextProvider.getContext(), 3L * 1024L * 1024L)
        try {
            val firstFactory =
                buildAndroidDataSourceFactory(
                    context = ContextProvider.getContext(),
                    cache = lease.cache,
                    requestHeaders =
                        mapOf(
                            "Authorization" to "Bearer first",
                            "Cookie" to "session=first",
                        ),
                )
            val secondFactory =
                buildAndroidDataSourceFactory(
                    context = ContextProvider.getContext(),
                    cache = lease.cache,
                    requestHeaders =
                        mapOf(
                            "authorization" to "Bearer second",
                            "cookie" to "session=second",
                        ),
                )

            assertContentEquals(firstBody, firstFactory.readAll(path))
            assertContentEquals(secondBody, secondFactory.readAll(path))
            assertEquals(2, requestCount.get())

            server.stop(0)
            assertContentEquals(firstBody, firstFactory.readAll(path))
            assertContentEquals(secondBody, secondFactory.readAll(path))

            val cacheKey =
                buildAndroidCacheKey(
                    dataSpec = DataSpec.Builder().setUri(Uri.parse(path)).build(),
                    normalizedHeaders = mapOf("authorization" to "Bearer first", "cookie" to "session=first"),
                )
            assertFalse(cacheKey.contains("Bearer first"))
            assertFalse(cacheKey.contains("session=first"))
            assertFalse(cacheKey.contains(path))
        } finally {
            runCatching { server.stop(0) }
            lease.clear()
            lease.close()
        }
    }

    private fun DataSource.Factory.readAll(uri: String): ByteArray {
        val source = createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(Uri.parse(uri)).build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally {
            source.close()
        }
    }
}
