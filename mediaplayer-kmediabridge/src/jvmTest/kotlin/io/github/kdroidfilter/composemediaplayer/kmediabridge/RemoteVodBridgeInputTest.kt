package io.github.kdroidfilter.composemediaplayer.kmediabridge

import com.sun.net.httpserver.HttpServer
import io.github.shusek.kmediabridge.MediaInputKind
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteVodBridgeInputTest {
    @Test
    fun stagesRemoteVodWithHeadersAndDeletesItOnClose() =
        runBlocking {
            val payload = "remote-video-payload".encodeToByteArray()
            val receivedHeader = AtomicReference<String?>()
            val receivedRange = AtomicReference<String?>()
            val receivedEncoding = AtomicReference<String?>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/fixture.mp4") { exchange ->
                receivedHeader.set(exchange.requestHeaders.getFirst("X-Test-Token"))
                receivedRange.set(exchange.requestHeaders.getFirst("Range"))
                receivedEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            server.start()
            try {
                val prepared =
                    prepareBridgeInput(
                        uri = "http://127.0.0.1:${server.address.port}/fixture.mp4",
                        requestHeaders =
                            mapOf(
                                "X-Test-Token" to "expected",
                                "Range" to "bytes=8-",
                                "Accept-Encoding" to "gzip",
                            ),
                    )
                val staged = Path.of(prepared.input.locator)
                try {
                    assertEquals(MediaInputKind.FILE, prepared.input.kind)
                    assertTrue(Files.isRegularFile(staged))
                    assertContentEquals(payload, Files.readAllBytes(staged))
                    assertEquals("expected", receivedHeader.get())
                    assertNull(receivedRange.get())
                    assertEquals("identity", receivedEncoding.get())
                } finally {
                    prepared.close()
                }
                assertFalse(Files.exists(staged))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun rejectsRemoteHlsInsteadOfStagingAnIncompletePlaylist() =
        runBlocking {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    prepareBridgeInput("https://example.invalid/live/stream.m3u8", emptyMap())
                }
            assertTrue(failure.message.orEmpty().contains("HLS"))
        }

    @Test
    fun followsSameOriginRedirectAndRetainsRequestHeaders() =
        runBlocking {
            val receivedHeader = AtomicReference<String?>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/fixture.mp4")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/fixture.mp4") { exchange ->
                receivedHeader.set(exchange.requestHeaders.getFirst("X-Test-Token"))
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.use { it.write(byteArrayOf(1)) }
            }
            server.start()
            try {
                prepareBridgeInput(
                    uri = "http://127.0.0.1:${server.address.port}/redirect",
                    requestHeaders = mapOf("X-Test-Token" to "expected"),
                ).use {
                    assertEquals("expected", receivedHeader.get())
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun stripsCallerCredentialsAndCustomHeadersOnCrossOriginRedirect() =
        runBlocking {
            val authorization = AtomicReference<String?>()
            val customToken = AtomicReference<String?>()
            val accept = AtomicReference<String?>()
            val target = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            target.createContext("/fixture.mp4") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                customToken.set(exchange.requestHeaders.getFirst("X-Test-Token"))
                accept.set(exchange.requestHeaders.getFirst("Accept"))
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.use { it.write(byteArrayOf(1)) }
            }
            val redirect = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            redirect.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/fixture.mp4",
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            target.start()
            redirect.start()
            try {
                prepareBridgeInput(
                    uri = "http://127.0.0.1:${redirect.address.port}/redirect",
                    requestHeaders =
                        mapOf(
                            "Authorization" to "Bearer secret",
                            "X-Test-Token" to "secret",
                            "Accept" to "video/*",
                        ),
                ).use {
                    assertNull(authorization.get())
                    assertNull(customToken.get())
                    assertEquals("video/*", accept.get())
                }
            } finally {
                redirect.stop(0)
                target.stop(0)
            }
        }

    @Test
    fun keepsLocalFilesLocalWithoutCreatingACopy() =
        runBlocking {
            val source = Files.createTempFile("kmediaplayer-local-bridge-test-", ".mkv")
            try {
                val prepared = prepareBridgeInput(source.toUri().toString(), emptyMap())
                prepared.close()
                assertEquals(source.toAbsolutePath().toString(), prepared.input.locator)
                assertTrue(Files.exists(source))
            } finally {
                Files.deleteIfExists(source)
            }
        }
}
