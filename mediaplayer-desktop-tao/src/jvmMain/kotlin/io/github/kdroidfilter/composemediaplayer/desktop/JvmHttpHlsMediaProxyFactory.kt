package io.github.kdroidfilter.composemediaplayer.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Credential-safe HTTPS transport for a bundled libmpv runtime that accepts loopback HTTP only.
 *
 * Remote URLs and headers stay in the JVM. Progressive files are streamed with Range support,
 * without first downloading the full body. The proxy also rewrites every URI carried by an HLS
 * playlist to an opaque route on `127.0.0.1`, and never includes source identities in exceptions
 * or [toString].
 */
public class JvmHttpHlsMediaProxyFactory : JvmHlsMediaProxyFactory {
    override suspend fun openProxy(request: DesktopPlaybackRequest): JvmHlsMediaProxy {
        val uri = request.source.uri.toRemoteHttpUri()
        return JvmHttpHlsMediaProxy(uri, request.requestHeaders)
    }
}

private class JvmHttpHlsMediaProxy(
    private val source: URI,
    requestHeaders: Map<String, String>,
) : JvmHlsMediaProxy {
    private val closed = AtomicBoolean(false)
    private val routeSequence = AtomicLong(0L)
    private val routes = ConcurrentHashMap<String, URI>()
    private val routesByTarget = ConcurrentHashMap<URI, String>()
    private val proxyPathPrefix = "$PROXY_PATH_ROOT${UUID.randomUUID()}/"
    private val headers = requestHeaders.sanitizedProxyRequestHeaders()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "kmedia-hls-proxy").apply { isDaemon = true }
    }
    private val client =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
            .build()
    private val server =
        HttpServer.create(
            InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0),
            0,
        )
    private val initialRoute = register(source)

    override val localUri: String
        get() = loopbackUri(initialRoute).toASCIIString()

    init {
        server.createContext(proxyPathPrefix, ::handle)
        server.executor = executor
        server.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        routes.clear()
        routesByTarget.clear()
        server.stop(0)
        executor.shutdownNow()
    }

    override fun toString(): String =
        "JvmHttpHlsMediaProxy(source=<redacted>, requestHeaders=<redacted:${headers.size}>)"

    private fun handle(exchange: HttpExchange) {
        try {
            if (closed.get()) {
                exchange.sendStatus(HTTP_SERVICE_UNAVAILABLE)
                return
            }
            if (exchange.requestMethod !in SUPPORTED_METHODS) {
                exchange.responseHeaders.set("Allow", SUPPORTED_METHODS.joinToString(", "))
                exchange.sendStatus(HTTP_METHOD_NOT_ALLOWED)
                return
            }
            val route = exchange.requestURI.rawPath.removePrefix(proxyPathPrefix)
            val target = routes[route]
            if (route.isBlank() || target == null) {
                exchange.sendStatus(HTTP_NOT_FOUND)
                return
            }
            proxy(exchange, target)
        } catch (_: Throwable) {
            runCatching { exchange.sendStatus(HTTP_BAD_GATEWAY) }
        } finally {
            exchange.close()
        }
    }

    private fun proxy(
        exchange: HttpExchange,
        initialTarget: URI,
    ) {
        var target = initialTarget
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = sendRemote(exchange, target)
            if (response.statusCode() in REDIRECT_STATUS_CODES) {
                response.body().close()
                if (redirectCount == MAX_REDIRECTS) throw IOException("Too many media redirects.")
                val location = response.headers().firstValue("Location").orElse(null)
                    ?: throw IOException("The media redirect is incomplete.")
                target = target.resolve(location).requireRemoteHttpUri()
                return@repeat
            }
            writeResponse(exchange, target, response)
            return
        }
    }

    private fun sendRemote(
        exchange: HttpExchange,
        target: URI,
    ): HttpResponse<InputStream> {
        val builder =
            HttpRequest
                .newBuilder(target)
                .timeout(REMOTE_REQUEST_TIMEOUT)
                .header("Accept-Encoding", "identity")
                .method(exchange.requestMethod, HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) !in CREDENTIAL_HEADERS || target.hasSameOriginAs(source)) {
                builder.header(name, value)
            }
        }
        exchange.requestHeaders.getFirst("Range")?.let { range ->
            if (!VALID_RANGE.matches(range)) throw IOException("The local media range is invalid.")
            builder.header("Range", range)
        }
        return try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("The desktop HLS transport was interrupted.", interrupted)
        } catch (failure: IllegalArgumentException) {
            throw IOException("The desktop HLS transport rejected a request.", failure)
        }
    }

    private fun writeResponse(
        exchange: HttpExchange,
        target: URI,
        response: HttpResponse<InputStream>,
    ) {
        response.body().use { body ->
            val status = response.statusCode()
            if (status !in HTTP_SUCCESS_RANGE) {
                exchange.sendStatus(status.takeIf { it in VALID_HTTP_STATUS_RANGE } ?: HTTP_BAD_GATEWAY)
                return
            }
            if (exchange.requestMethod == "HEAD") {
                copyResponseMetadata(exchange, response)
                exchange.sendResponseHeaders(status, -1L)
                return
            }
            if (target.isHlsPlaylist(response)) {
                val playlistBytes = body.readNBytes(MAX_PLAYLIST_BYTES + 1)
                if (playlistBytes.size > MAX_PLAYLIST_BYTES) {
                    throw IOException("The HLS playlist exceeds the configured bound.")
                }
                val rewritten =
                    rewritePlaylist(
                        playlistBytes.toString(StandardCharsets.UTF_8),
                        target,
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", HLS_CONTENT_TYPE)
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(status, rewritten.size.toLong())
                exchange.responseBody.use { output -> output.write(rewritten) }
                return
            }

            copyResponseMetadata(exchange, response)
            val length = response.headers().firstValueAsLong("Content-Length").orElse(0L)
            exchange.sendResponseHeaders(status, length.takeIf { it > 0L } ?: 0L)
            exchange.responseBody.use { output -> body.transferTo(output) }
        }
    }

    private fun copyResponseMetadata(
        exchange: HttpExchange,
        response: HttpResponse<InputStream>,
    ) {
        FORWARDED_RESPONSE_HEADERS.forEach { name ->
            response.headers().firstValue(name).orElse(null)?.let { value ->
                exchange.responseHeaders.set(name, value)
            }
        }
        exchange.responseHeaders.set("Cache-Control", "no-store")
    }

    private fun rewritePlaylist(
        playlist: String,
        base: URI,
    ): String {
        val trailingNewline = playlist.endsWith('\n')
        val rewritten =
            playlist
                .lineSequence()
                .map { originalLine ->
                    val line = originalLine.removeSuffix("\r")
                    when {
                        line.isBlank() -> line
                        line.startsWith('#') -> rewriteUriAttributes(line, base)
                        else -> rewriteStandaloneUri(line, base)
                    }
                }.joinToString("\n")
        return if (trailingNewline) "$rewritten\n" else rewritten
    }

    private fun rewriteUriAttributes(
        line: String,
        base: URI,
    ): String =
        HLS_URI_ATTRIBUTE.replace(line) { match ->
            val rewritten = localReference(base, match.groupValues[1])
            "URI=\"$rewritten\""
        }

    private fun rewriteStandaloneUri(
        line: String,
        base: URI,
    ): String {
        val leading = line.takeWhile(Char::isWhitespace)
        val trailing = line.takeLastWhile(Char::isWhitespace)
        val reference = line.trim()
        return leading + localReference(base, reference) + trailing
    }

    private fun localReference(
        base: URI,
        reference: String,
    ): String {
        val target = base.resolve(reference).requireRemoteHttpUri()
        return loopbackUri(register(target)).toASCIIString()
    }

    private fun register(target: URI): String {
        return routesByTarget.computeIfAbsent(target) { registeredTarget ->
            val route = routeSequence.incrementAndGet().toString(ROUTE_RADIX)
            routes[route] = registeredTarget
            route
        }
    }

    private fun loopbackUri(route: String): URI =
        URI(
            "http",
            null,
            LOOPBACK_ADDRESS,
            server.address.port,
            "$proxyPathPrefix$route",
            null,
            null,
        )
}

private fun URI.isHlsPlaylist(response: HttpResponse<*>): Boolean {
    val mediaType =
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
    val cleanPath = path.orEmpty().lowercase(Locale.ROOT)
    return mediaType in HLS_MEDIA_TYPES || cleanPath.endsWith(".m3u8")
}

private fun String.toRemoteHttpUri(): URI =
    try {
        URI.create(this).requireRemoteHttpUri()
    } catch (failure: IllegalArgumentException) {
        throw IOException("The desktop HLS source URI is invalid.", failure)
    }

private fun URI.requireRemoteHttpUri(): URI {
    if (scheme?.lowercase(Locale.ROOT) !in REMOTE_SCHEMES ||
        host.isNullOrBlank() ||
        userInfo != null
    ) {
        throw IOException("The desktop HLS transport requires an uncredentialed HTTP or HTTPS URI.")
    }
    return this
}

private fun URI.hasSameOriginAs(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun URI.effectivePort(): Int =
    port.takeIf { it >= 0 }
        ?: if (scheme.equals("https", ignoreCase = true)) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT

private fun Map<String, String>.sanitizedProxyRequestHeaders(): Map<String, String> =
    filterKeys { name -> name.lowercase(Locale.ROOT) !in BLOCKED_REQUEST_HEADERS }

private fun HttpExchange.sendStatus(status: Int) {
    sendResponseHeaders(status, -1L)
}

private val REMOTE_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
private val SUPPORTED_METHODS: Set<String> = linkedSetOf("GET", "HEAD")
private val REMOTE_SCHEMES: Set<String> = setOf("http", "https")
private val HLS_MEDIA_TYPES: Set<String> =
    setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )
private val BLOCKED_REQUEST_HEADERS: Set<String> =
    setOf(
        "accept-encoding",
        "connection",
        "content-length",
        "expect",
        "host",
        "range",
        "upgrade",
    )
private val CREDENTIAL_HEADERS: Set<String> = setOf("authorization", "cookie", "proxy-authorization")
private val FORWARDED_RESPONSE_HEADERS: Set<String> =
    setOf(
        "Accept-Ranges",
        "Content-Range",
        "Content-Type",
        "ETag",
        "Last-Modified",
    )
private val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
private val HTTP_SUCCESS_RANGE: IntRange = 200..299
private val VALID_HTTP_STATUS_RANGE: IntRange = 100..599
private val VALID_RANGE: Regex = Regex("bytes=[0-9]*-[0-9]*(?:,[0-9]*-[0-9]*)*")
private val HLS_URI_ATTRIBUTE: Regex = Regex("URI=\"([^\"]+)\"")
private const val LOOPBACK_ADDRESS: String = "127.0.0.1"
private const val PROXY_PATH_ROOT: String = "/hls/"
private const val HLS_CONTENT_TYPE: String = "application/vnd.apple.mpegurl"
private const val MAX_PLAYLIST_BYTES: Int = 8 * 1024 * 1024
private const val MAX_REDIRECTS: Int = 5
private const val ROUTE_RADIX: Int = 36
private const val HTTP_DEFAULT_PORT: Int = 80
private const val HTTPS_DEFAULT_PORT: Int = 443
private const val HTTP_NOT_FOUND: Int = 404
private const val HTTP_METHOD_NOT_ALLOWED: Int = 405
private const val HTTP_BAD_GATEWAY: Int = 502
private const val HTTP_SERVICE_UNAVAILABLE: Int = 503
