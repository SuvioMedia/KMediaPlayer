package io.github.kdroidfilter.composemediaplayer.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Credential-safe JVM HTTP transport for a native backend with networking disabled.
 *
 * The application opts into this factory when constructing [DesktopPlaybackSession]. Requests
 * are made by the JVM, redirects fail closed, and neither the source URI nor header values are
 * exposed by exceptions or string representations. The session materializes the bytes into its
 * bounded private cache before passing a header-free local path to MPV.
 */
public class JvmHttpSeekableMediaDataSourceFactory : JvmSeekableMediaDataSourceFactory {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override suspend fun open(request: DesktopPlaybackRequest): JvmSeekableMediaDataSource {
        val uri =
            runCatching { URI.create(request.source.uri) }
                .getOrElse { throw IOException("The desktop media source URI is invalid.") }
        if (uri.scheme?.lowercase() !in SUPPORTED_SCHEMES) {
            throw IOException("The desktop HTTP media transport requires an HTTP or HTTPS source.")
        }
        return HttpSeekableMediaDataSource(client, uri, request.requestHeaders)
    }
}

private class HttpSeekableMediaDataSource(
    private val client: HttpClient,
    private val uri: URI,
    requestHeaders: Map<String, String>,
) : JvmSeekableMediaDataSource {
    private val headers: Map<String, String> =
        requestHeaders.filterKeys { name -> name.lowercase() !in BLOCKED_REQUEST_HEADERS }
    private val lock = Mutex()
    private val closed = AtomicBoolean(false)
    private var stream: InputStream? = null
    private var streamPosition: Long = 0L
    private var knownLength: Long? = null

    override val length: Long?
        get() = knownLength

    override suspend fun read(
        position: Long,
        destination: ByteBuffer,
    ): Int =
        lock.withLock {
            if (closed.get()) throw IOException("The desktop media transport is closed.")
            if (position < 0L) throw IOException("The desktop media read position is invalid.")
            if (!destination.hasRemaining()) return@withLock 0
            knownLength?.let { total -> if (position >= total) return@withLock -1 }

            if (stream == null || streamPosition != position) {
                closeStream()
                if (!openStream(position)) return@withLock -1
            }

            val buffer = ByteArray(minOf(destination.remaining(), HTTP_READ_BUFFER_BYTES))
            val count =
                try {
                    checkNotNull(stream).read(buffer)
                } catch (_: Throwable) {
                    closeStream()
                    throw IOException("The desktop media transport failed while reading data.")
                }
            if (count < 0) {
                knownLength = knownLength ?: streamPosition
                closeStream()
                return@withLock -1
            }
            destination.put(buffer, 0, count)
            streamPosition += count
            count
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeStream()
    }

    override fun toString(): String =
        "HttpSeekableMediaDataSource(uri=<redacted>, requestHeaders=<redacted:${headers.size}>)"

    private fun openStream(position: Long): Boolean {
        try {
            return executeOpenStream(position)
        } catch (_: IOException) {
            failOpeningSource()
        } catch (_: IllegalArgumentException) {
            failOpeningSource()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failOpeningSource()
        }
    }

    private fun executeOpenStream(position: Long): Boolean {
        val builder =
            HttpRequest
                .newBuilder(uri)
                .GET()
                .header("Range", "bytes=$position-")
        headers.forEach(builder::header)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        when (response.statusCode()) {
            HTTP_PARTIAL_CONTENT -> Unit
            HTTP_OK ->
                if (position != 0L) {
                    response.body().close()
                    throw IOException("The desktop media server does not support range reads.")
                }
            HTTP_RANGE_NOT_SATISFIABLE -> {
                response.body().close()
                knownLength = knownLength ?: position
                return false
            }
            else -> {
                response.body().close()
                throw IOException("The desktop media server rejected the request.")
            }
        }
        updateKnownLength(response, position)
        stream = response.body()
        streamPosition = position
        return true
    }

    private fun updateKnownLength(
        response: HttpResponse<InputStream>,
        position: Long,
    ) {
        response
            .headers()
            .firstValue("Content-Range")
            .orElse(null)
            ?.substringAfterLast('/', missingDelimiterValue = "")
            ?.takeUnless { it == "*" }
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.let { knownLength = it }
        if (knownLength == null && response.statusCode() == HTTP_OK && position == 0L) {
            response
                .headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L)
                .takeIf { it >= 0L }
                ?.let { knownLength = it }
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }
}

private fun failOpeningSource(): Nothing = throw IOException("The desktop media transport could not open the source.")

private val SUPPORTED_SCHEMES = setOf("http", "https")
private val BLOCKED_REQUEST_HEADERS =
    setOf(
        "connection",
        "content-length",
        "expect",
        "host",
        "range",
        "upgrade",
    )
private const val HTTP_READ_BUFFER_BYTES = 256 * 1024
private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
