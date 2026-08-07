package io.github.kdroidfilter.composemediaplayer.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmHttpHlsMediaProxyFactoryTest {
    @Test
    fun streamsProgressiveRangesWithoutDownloadingTheWholeSource() =
        runTest {
            val requestedRanges = mutableListOf<String?>()
            val payload = "0123456789abcdef".encodeToByteArray()
            val origin =
                loopbackServer { exchange ->
                    val range = exchange.requestHeaders.getFirst("Range")
                    requestedRanges += range
                    when {
                        exchange.requestMethod == "HEAD" -> {
                            exchange.responseHeaders.set("Accept-Ranges", "bytes")
                            exchange.responseHeaders.set("Content-Length", payload.size.toString())
                            exchange.sendResponseHeaders(200, -1L)
                        }
                        range == "bytes=4-7" -> {
                            val slice = payload.copyOfRange(4, 8)
                            exchange.responseHeaders.set("Accept-Ranges", "bytes")
                            exchange.responseHeaders.set("Content-Range", "bytes 4-7/${payload.size}")
                            exchange.sendResponseHeaders(206, slice.size.toLong())
                            exchange.responseBody.use { it.write(slice) }
                        }
                        else -> exchange.respond("video/mp4", payload)
                    }
                }
            origin.start()
            val proxy =
                JvmHttpHlsMediaProxyFactory().openProxy(
                    DesktopPlaybackRequest(
                        MediaSourceSpec("http://127.0.0.1:${origin.address.port}/movie.mp4"),
                    ),
                )

            try {
                val response =
                    HTTP_CLIENT.send(
                        HttpRequest
                            .newBuilder(URI.create(proxy.localUri))
                            .header("Range", "bytes=4-7")
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )
                assertEquals(206, response.statusCode())
                assertEquals(
                    "bytes 4-7/${payload.size}",
                    response.headers().firstValue("Content-Range").orElse(null),
                )
                assertContentEquals(payload.copyOfRange(4, 8), response.body())
                assertEquals(1, requestedRanges.size)
                assertEquals("bytes=4-7", requestedRanges.single())
            } finally {
                proxy.close()
                origin.stop(0)
            }
        }

    @Test
    fun rewritesNestedPlaylistsKeysAndSegmentsToOpaqueLoopbackRoutes() =
        runTest {
            val headerCount = AtomicInteger(0)
            val origin =
                loopbackServer { exchange ->
                    if (exchange.requestHeaders.getFirst("Authorization") == TEST_AUTHORIZATION) {
                        headerCount.incrementAndGet()
                    }
                    when (exchange.requestURI.path) {
                        "/master.m3u8" ->
                            exchange.respond(
                                HLS_CONTENT_TYPE,
                                (
                                    "#EXTM3U\n" +
                                        "#EXT-X-STREAM-INF:BANDWIDTH=800000\n" +
                                        "media/playlist.m3u8\n"
                                ).encodeToByteArray(),
                            )
                        "/media/playlist.m3u8" ->
                            exchange.respond(
                                HLS_CONTENT_TYPE,
                                (
                                    "#EXTM3U\n" +
                                        "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n" +
                                        "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                                        "#EXTINF:4.0,\nsegment.ts\n#EXT-X-ENDLIST\n"
                                ).encodeToByteArray(),
                            )
                        "/media/key.bin" -> exchange.respond("application/octet-stream", KEY_BYTES)
                        "/media/init.mp4" -> exchange.respond("video/mp4", INIT_BYTES)
                        "/media/segment.ts" -> exchange.respond("video/mp2t", SEGMENT_BYTES)
                        else -> exchange.sendResponseHeaders(404, -1L)
                    }
                }
            origin.start()
            val source = "http://127.0.0.1:${origin.address.port}/master.m3u8"
            val proxy =
                JvmHttpHlsMediaProxyFactory().openProxy(
                    DesktopPlaybackRequest(
                        source = MediaSourceSpec(source),
                        requestHeaders = mapOf("Authorization" to TEST_AUTHORIZATION),
                    ),
                )

            try {
                assertFalse(source in proxy.toString())
                assertFalse(TEST_AUTHORIZATION in proxy.toString())
                val masterUri = URI.create(proxy.localUri)
                assertEquals("http", masterUri.scheme)
                assertEquals("127.0.0.1", masterUri.host)

                val master = getText(masterUri)
                assertFalse(origin.address.port.toString() in master)
                val mediaUri = URI.create(master.lineSequence().first { it.startsWith("http://") })
                assertEquals(masterUri.authority, mediaUri.authority)

                val media = getText(mediaUri)
                assertFalse(origin.address.port.toString() in media)
                val referencedUris = HTTP_URI.findAll(media).map { URI.create(it.value) }.toList()
                assertEquals(3, referencedUris.size)
                assertTrue(referencedUris.all { it.authority == masterUri.authority })
                assertContentEquals(KEY_BYTES, getBytes(referencedUris[0]))
                assertContentEquals(INIT_BYTES, getBytes(referencedUris[1]))
                assertContentEquals(SEGMENT_BYTES, getBytes(referencedUris[2]))
                assertTrue(headerCount.get() >= 5)
            } finally {
                proxy.close()
                origin.stop(0)
            }
        }

    @Test
    fun rejectsCredentialsEmbeddedInTheSourceUri() =
        runTest {
            assertFailsWith<java.io.IOException> {
                JvmHttpHlsMediaProxyFactory().openProxy(
                    DesktopPlaybackRequest(
                        MediaSourceSpec("https://user@example.invalid/master.m3u8"),
                    ),
                )
            }
        }
}

private fun loopbackServer(handler: (HttpExchange) -> Unit): HttpServer =
    HttpServer
        .create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        ).apply { createContext("/", handler) }

private fun HttpExchange.respond(
    contentType: String,
    body: ByteArray,
) {
    responseHeaders.set("Content-Type", contentType)
    sendResponseHeaders(200, body.size.toLong())
    responseBody.use { it.write(body) }
}

private fun getText(uri: URI): String = getBytes(uri).toString(StandardCharsets.UTF_8)

private fun getBytes(uri: URI): ByteArray =
    HTTP_CLIENT
        .send(
            HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        ).let { response ->
            assertEquals(200, response.statusCode())
            response.body()
        }

private val HTTP_CLIENT: HttpClient = HttpClient.newHttpClient()
private val HTTP_URI: Regex = Regex("http://[^\\s\"]+")
private val KEY_BYTES: ByteArray = "test-key".encodeToByteArray()
private val INIT_BYTES: ByteArray = "test-init".encodeToByteArray()
private val SEGMENT_BYTES: ByteArray = "test-segment".encodeToByteArray()
private const val HLS_CONTENT_TYPE: String = "application/vnd.apple.mpegurl"
private const val TEST_AUTHORIZATION: String = "Bearer test-only-placeholder"
