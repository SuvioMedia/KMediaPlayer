package io.github.kdroidfilter.composemediaplayer.windows

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal object WindowsHlsResourceReader {
    fun readPlaylist(
        uri: String,
        requestHeaders: Map<String, String>,
    ): LoadedHlsPlaylist? {
        if (WINDOWS_DRIVE_PATH.matches(uri)) {
            val path = Path.of(uri).toAbsolutePath().normalize()
            return readBoundedFile(path)?.let { content ->
                LoadedHlsPlaylist(content, path.toUri(), emptyMap())
            }
        }
        val parsed = runCatching { URI(uri) }.getOrNull()
        return when (parsed?.scheme?.lowercase()) {
            "http", "https" ->
                readRemotePlaylist(
                    parsed,
                    requestHeaders.filterKeys { it.lowercase() !in PROBE_CONTROLLED_HEADERS },
                )
            "file" ->
                readBoundedFile(Path.of(parsed))?.let { content ->
                    LoadedHlsPlaylist(content, parsed, emptyMap())
                }
            null -> {
                val path = Path.of(uri).toAbsolutePath().normalize()
                readBoundedFile(path)?.let { content ->
                    LoadedHlsPlaylist(content, path.toUri(), emptyMap())
                }
            }
            else -> null
        }
    }

    fun readInitializationSegment(
        loaded: LoadedHlsPlaylist,
        locator: String,
    ): ByteArray? {
        val resource = resolveResource(loaded, locator) ?: return null
        return runCatching {
            when (resource.uri.scheme?.lowercase()) {
                "http", "https" -> readRemoteBytes(resource.uri, resource.requestHeaders, MAXIMUM_INIT_BYTES)
                "file" -> readBoundedBytes(Path.of(resource.uri), MAXIMUM_INIT_BYTES)
                else -> null
            }
        }.getOrNull()
    }

    fun resolveResource(
        loaded: LoadedHlsPlaylist,
        locator: String,
    ): ResolvedHlsResource? {
        val uri = runCatching { loaded.baseUri.resolve(locator) }.getOrNull() ?: return null
        val baseScheme = loaded.baseUri.scheme?.lowercase()
        val resourceScheme = uri.scheme?.lowercase()
        if (
            resourceScheme !in SUPPORTED_RESOURCE_SCHEMES ||
            (baseScheme in REMOTE_SCHEMES && resourceScheme == "file")
        ) {
            return null
        }
        val headers =
            if (baseScheme in REMOTE_SCHEMES && !loaded.baseUri.hasSameOriginAs(uri)) {
                loaded.requestHeaders.filterKeys { it.lowercase() in SAFE_CROSS_ORIGIN_HEADERS }
            } else {
                loaded.requestHeaders
            }
        return ResolvedHlsResource(uri, headers)
    }

    private fun readRemotePlaylist(
        initialUri: URI,
        initialHeaders: Map<String, String>,
    ): LoadedHlsPlaylist? {
        var uri = initialUri
        var headers = initialHeaders
        var redirectCount = 0
        var playlist: LoadedHlsPlaylist? = null
        var finished = false
        while (!finished) {
            when (val response = readRemotePlaylistResponse(uri, headers)) {
                is RemotePlaylistResponse.Content -> {
                    response.playlist?.let { content ->
                        playlist = LoadedHlsPlaylist(content, uri, headers)
                    }
                    finished = true
                }

                RemotePlaylistResponse.Rejected -> finished = true
                is RemotePlaylistResponse.Redirect -> {
                    val redirected = uri.resolve(response.location)
                    if (redirectCount >= MAXIMUM_REDIRECTS || redirected.scheme?.lowercase() !in REMOTE_SCHEMES) {
                        finished = true
                    } else {
                        if (!uri.hasSameOriginAs(redirected)) {
                            headers = headers.filterKeys { it.lowercase() in SAFE_CROSS_ORIGIN_HEADERS }
                        }
                        uri = redirected
                        redirectCount++
                    }
                }
            }
        }
        return playlist
    }

    private fun readRemotePlaylistResponse(
        uri: URI,
        requestHeaders: Map<String, String>,
    ): RemotePlaylistResponse {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.setRequestProperty("Accept", HLS_ACCEPT_HEADER)
            if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                connection.setRequestProperty("User-Agent", USER_AGENT)
            }
            requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Accept-Encoding", "identity")

            val status = connection.responseCode
            when {
                status in REDIRECT_STATUS_CODES ->
                    connection
                        .getHeaderField("Location")
                        ?.let(RemotePlaylistResponse::Redirect)
                        ?: RemotePlaylistResponse.Rejected
                status !in HTTP_SUCCESS_STATUS_RANGE -> RemotePlaylistResponse.Rejected
                connection.contentLengthLong > MAXIMUM_PLAYLIST_BYTES -> RemotePlaylistResponse.Rejected
                else -> RemotePlaylistResponse.Content(readBoundedPlaylist(connection))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedPlaylist(connection: HttpURLConnection): String? =
        connection.inputStream.use { input ->
            input
                .readNBytes(MAXIMUM_PLAYLIST_BYTES + 1)
                .takeIf { it.size <= MAXIMUM_PLAYLIST_BYTES }
                ?.toString(Charsets.UTF_8)
        }

    private fun readBoundedFile(path: Path): String? =
        readBoundedBytes(path, MAXIMUM_PLAYLIST_BYTES)?.toString(Charsets.UTF_8)

    private fun readBoundedBytes(
        path: Path,
        maximumBytes: Int,
    ): ByteArray? =
        Files.newInputStream(path).use { input ->
            input.readNBytes(maximumBytes + 1).takeIf { it.size <= maximumBytes }
        }

    private fun readRemoteBytes(
        initialUri: URI,
        initialHeaders: Map<String, String>,
        maximumBytes: Int,
    ): ByteArray? {
        var uri = initialUri
        var headers = initialHeaders
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                }
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= MAXIMUM_REDIRECTS) return null
                    val location = connection.getHeaderField("Location") ?: return null
                    val redirected = uri.resolve(location)
                    if (redirected.scheme?.lowercase() !in REMOTE_SCHEMES) return null
                    if (!uri.hasSameOriginAs(redirected)) {
                        headers = headers.filterKeys { it.lowercase() in SAFE_CROSS_ORIGIN_HEADERS }
                    }
                    uri = redirected
                } else {
                    if (status !in HTTP_SUCCESS_STATUS_RANGE || connection.contentLengthLong > maximumBytes) {
                        return null
                    }
                    return connection.inputStream.use { input ->
                        input.readNBytes(maximumBytes + 1).takeIf { it.size <= maximumBytes }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

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

    private const val MAXIMUM_REDIRECTS = 5
    private const val MAXIMUM_PLAYLIST_BYTES = 1024 * 1024
    private const val MAXIMUM_INIT_BYTES = 4 * 1024 * 1024
    private const val NETWORK_TIMEOUT_MS = 15_000
    private const val HLS_ACCEPT_HEADER = "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain"
    private const val USER_AGENT = "ComposeMediaPlayer/2 Windows HLS color probe"
    private val REMOTE_SCHEMES = setOf("http", "https")
    private val SUPPORTED_RESOURCE_SCHEMES = setOf("http", "https", "file")
    private val REDIRECT_STATUS_CODES =
        setOf(
            HTTP_MOVED_PERMANENTLY,
            HTTP_FOUND,
            HTTP_SEE_OTHER,
            HTTP_TEMPORARY_REDIRECT,
            HTTP_PERMANENT_REDIRECT,
        )
    private val SAFE_CROSS_ORIGIN_HEADERS = setOf("accept", "accept-language", "user-agent")
    private val PROBE_CONTROLLED_HEADERS = setOf("range", "if-range", "accept-encoding")
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
    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
}

internal data class LoadedHlsPlaylist(
    val content: String,
    val baseUri: URI,
    val requestHeaders: Map<String, String>,
)

internal data class ResolvedHlsResource(
    val uri: URI,
    val requestHeaders: Map<String, String>,
)

private sealed interface RemotePlaylistResponse {
    data class Content(
        val playlist: String?,
    ) : RemotePlaylistResponse

    data class Redirect(
        val location: String,
    ) : RemotePlaylistResponse

    data object Rejected : RemotePlaylistResponse
}
