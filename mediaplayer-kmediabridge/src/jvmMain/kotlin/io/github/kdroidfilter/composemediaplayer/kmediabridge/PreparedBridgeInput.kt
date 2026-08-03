@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PreparedBridgeInput(
    val input: MediaInput,
    private val stagedFile: Path? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            stagedFile?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

internal suspend fun prepareBridgeInput(
    uri: String,
    requestHeaders: Map<String, String>,
): PreparedBridgeInput {
    val localPath = localPath(uri)
    if (localPath != null) {
        return PreparedBridgeInput(MediaInput(locator = localPath, kind = MediaInputKind.FILE))
    }

    val parsed = runCatching { URI(uri) }.getOrNull()
    val remoteUri = parsed?.takeIf { it.scheme?.lowercase() in REMOTE_SCHEMES }
    if (remoteUri == null) {
        return PreparedBridgeInput(
            MediaInput(
                locator = uri,
                kind = MediaInputKind.URI,
                requestHeaders = requestHeaders.sanitizedRequestHeaders(),
            ),
        )
    }
    require(!remoteUri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
        "Remote HLS stays on the platform streaming backend and cannot be staged as a VOD file."
    }

    val pendingInput = AtomicReference<PreparedBridgeInput?>()
    try {
        val prepared =
            withContext(Dispatchers.IO) {
                stageRemoteVod(
                    remoteUri,
                    requestHeaders
                        .sanitizedRequestHeaders()
                        .filterKeys { it.lowercase() !in STAGING_CONTROLLED_HEADERS },
                ).also(pendingInput::set)
            }
        pendingInput.compareAndSet(prepared, null)
        return prepared
    } finally {
        // withContext has prompt cancellation: a completed blocking download can be discarded
        // before its result is resumed on the caller dispatcher. Retain the staged input until
        // that hand-off succeeds so cancellation cannot orphan a multi-gigabyte temporary file.
        pendingInput.getAndSet(null)?.close()
    }
}

private fun stageRemoteVod(
    uri: URI,
    requestHeaders: Map<String, String>,
): PreparedBridgeInput {
    val suffix = remoteSuffix(uri)
    val staged = Files.createTempFile("kmediaplayer-bridge-vod-", suffix)
    var ownershipTransferred = false
    try {
        val response = sendRemoteVodRequest(uri, requestHeaders)
        copyRemoteVodResponse(response, staged)
        check(Files.size(staged) > 0L) { "The remote VOD response was empty." }
        val prepared =
            PreparedBridgeInput(
                input = MediaInput(locator = staged.toString(), kind = MediaInputKind.FILE),
                stagedFile = staged,
            )
        ownershipTransferred = true
        return prepared
    } finally {
        if (!ownershipTransferred) runCatching { Files.deleteIfExists(staged) }
    }
}

private fun copyRemoteVodResponse(
    response: HttpResponse<java.io.InputStream>,
    staged: Path,
) {
    response.body().use { source ->
        check(response.statusCode() in HTTP_SUCCESS_STATUS_RANGE) {
            "The remote VOD server returned HTTP ${response.statusCode()}."
        }
        response.headers().firstValueAsLong("Content-Length").ifPresent(::validateRemoteVodLength)
        Files.newOutputStream(staged).use { destination ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                copied += count
                validateRemoteVodLength(copied)
                destination.write(buffer, 0, count)
            }
        }
    }
}

private fun validateRemoteVodLength(length: Long) {
    check(length <= MAXIMUM_REMOTE_VOD_BYTES) {
        "The remote VOD exceeds the ${MAXIMUM_REMOTE_VOD_BYTES / GIBIBYTE} GiB staging limit."
    }
}

private fun sendRemoteVodRequest(
    initialUri: URI,
    initialHeaders: Map<String, String>,
): HttpResponse<java.io.InputStream> {
    var uri = initialUri
    var headers = initialHeaders
    repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
        val request = remoteVodRequest(uri, headers)
        val response = REMOTE_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in REDIRECT_STATUS_CODES) return response

        response.body().close()
        check(redirectCount < MAXIMUM_REDIRECTS) {
            "The remote VOD exceeded the $MAXIMUM_REDIRECTS redirect limit."
        }
        val location = response.headers().firstValue("Location").orElse(null)
        check(!location.isNullOrBlank()) { "The remote VOD redirect did not contain a Location header." }
        val redirectedUri = uri.resolve(location)
        check(redirectedUri.scheme?.lowercase() in REMOTE_SCHEMES) {
            "The remote VOD redirected to an unsupported URI scheme."
        }
        if (!uri.hasSameOriginAs(redirectedUri)) {
            headers =
                headers.filterKeys { name ->
                    name.lowercase() in SAFE_CROSS_ORIGIN_HEADERS
                }
        }
        uri = redirectedUri
    }
    error("Unreachable redirect state")
}

private fun remoteVodRequest(
    uri: URI,
    requestHeaders: Map<String, String>,
): HttpRequest =
    HttpRequest
        .newBuilder(uri)
        .timeout(REMOTE_REQUEST_TIMEOUT)
        .GET()
        .apply {
            requestHeaders.forEach { (name, value) -> header(name, value) }
            header("Accept-Encoding", "identity")
            if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", "ComposeMediaPlayer/2 KMediaBridge")
            }
        }.build()

private fun URI.hasSameOriginAs(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun URI.effectivePort(): Int =
    when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> HTTPS_DEFAULT_PORT
        else -> HTTP_DEFAULT_PORT
    }

private fun remoteSuffix(uri: URI): String {
    val candidate =
        uri.path
            .orEmpty()
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
    return candidate
        .takeIf { it.length in MINIMUM_EXTENSION_LENGTH..MAXIMUM_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }
        ?.let { ".${it.lowercase()}" }
        ?: ".media"
}

private val REMOTE_HTTP_CLIENT: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
private val REMOTE_REQUEST_TIMEOUT: Duration = Duration.ofMinutes(REQUEST_TIMEOUT_MINUTES)
private val REMOTE_SCHEMES = setOf("http", "https")
private val REDIRECT_STATUS_CODES =
    setOf(
        HTTP_MOVED_PERMANENTLY,
        HTTP_FOUND,
        HTTP_SEE_OTHER,
        HTTP_TEMPORARY_REDIRECT,
        HTTP_PERMANENT_REDIRECT,
    )
private val SAFE_CROSS_ORIGIN_HEADERS = setOf("accept", "accept-language", "user-agent")
private val STAGING_CONTROLLED_HEADERS = setOf("range", "if-range", "accept-encoding")
private const val MAXIMUM_REDIRECTS = 5
private const val COPY_BUFFER_BYTES = 128 * 1024
private const val GIBIBYTE = 1024L * 1024L * 1024L
private const val MAXIMUM_REMOTE_VOD_BYTES = 16L * GIBIBYTE
private const val HTTP_SUCCESS_MINIMUM = 200
private const val HTTP_SUCCESS_MAXIMUM = 299
private val HTTP_SUCCESS_STATUS_RANGE = HTTP_SUCCESS_MINIMUM..HTTP_SUCCESS_MAXIMUM
private const val HTTP_MOVED_PERMANENTLY = 301
private const val HTTP_FOUND = 302
private const val HTTP_SEE_OTHER = 303
private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308
private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val MINIMUM_EXTENSION_LENGTH = 1
private const val MAXIMUM_EXTENSION_LENGTH = 8
private const val CONNECT_TIMEOUT_SECONDS = 20L
private const val REQUEST_TIMEOUT_MINUTES = 30L
