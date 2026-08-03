@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.PreparedVideoPipelineSource
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

internal actual fun platformDolbyVisionSourceBridgeAvailable(): Boolean = true

internal actual suspend fun preparePlatformDolbyVisionSource(
    request: VideoPipelineSourceRequest,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    withContext(Dispatchers.IO) {
        val dataSource = JvmDolbyVisionDataSource(request.uri, request.requestHeaders)
        when (detectContainer(request.uri, request.mimeType)) {
            DolbyVisionContainer.MP4 -> prepareFlatMp4Source(request, dataSource, converter)
            DolbyVisionContainer.HLS_VOD -> prepareHlsVodSource(request, dataSource, converter)
            DolbyVisionContainer.MATROSKA -> prepareMatroskaSource(request, dataSource, converter)
            else -> rejected("Only unencrypted MP4, Matroska and fMP4 HLS VOD can be bridged on JVM.")
        }
    }

private suspend fun prepareFlatMp4Source(
    request: VideoPipelineSourceRequest,
    dataSource: JvmDolbyVisionDataSource,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    when (
        val opened =
            FlatMp4DolbyVisionAdapter.open(
                source = dataSource,
                converter = converter,
                enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
            )
    ) {
        is FlatMp4DolbyVisionOpenResult.Success ->
            ready(request, FlatMp4HlsSession(opened.session))
        is FlatMp4DolbyVisionOpenResult.Failure ->
            if (JvmFfmpegDolbyVisionAdapter.isAvailable()) {
                prepareFfmpegSpoolSource(request, DolbyVisionContainer.MP4, converter)
            } else {
                rejected(opened.message)
            }
    }

private suspend fun prepareMatroskaSource(
    request: VideoPipelineSourceRequest,
    dataSource: JvmDolbyVisionDataSource,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    when (
        val opened =
            MatroskaDolbyVisionAdapter.open(
                source = dataSource,
                converter = converter,
                enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
            )
    ) {
        is MatroskaDolbyVisionOpenResult.Success -> ready(request, MatroskaHlsSession(opened.session))
        is MatroskaDolbyVisionOpenResult.Failure ->
            if (JvmFfmpegDolbyVisionAdapter.isAvailable()) {
                prepareFfmpegSpoolSource(request, DolbyVisionContainer.MATROSKA, converter)
            } else {
                rejected(opened.message)
            }
    }

@Suppress("TooGenericExceptionCaught")
private suspend fun prepareFfmpegSpoolSource(
    request: VideoPipelineSourceRequest,
    container: DolbyVisionContainer,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation {
    if (!JvmFfmpegDolbyVisionAdapter.isAvailable()) {
        return rejected("FFmpeg is required for the JVM $container Dolby Vision bridge but was not found.")
    }
    val opened =
        JvmFfmpegDolbyVisionAdapter.open(
            request =
                JvmFfmpegDolbyVisionRequest(
                    input = request.uri,
                    container = container,
                    requestHeaders = request.requestHeaders,
                    enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
                ),
            converter = converter,
        )
    val conversionSession =
        when (opened) {
            is JvmFfmpegDolbyVisionOpenResult.Success -> opened.session
            is JvmFfmpegDolbyVisionOpenResult.Failure -> return rejected(opened.message)
        }
    val directory = Files.createTempDirectory("kmediaplayer-dovi-")
    val fragments = mutableListOf<SpooledDolbyVisionFragment>()
    var totalBytes = conversionSession.initializationSegment.size.toLong()
    return try {
        while (true) {
            when (val fragment = conversionSession.nextFragment()) {
                is JvmFfmpegDolbyVisionFragmentResult.Success -> {
                    totalBytes += fragment.payload.size
                    if (totalBytes > maximumSpoolBytes()) {
                        error("The converted Dolby Vision VOD exceeds the configured temporary-storage limit.")
                    }
                    val path = directory.resolve("segment-${fragments.size}.m4s")
                    Files.write(
                        path,
                        fragment.payload,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                    fragments +=
                        SpooledDolbyVisionFragment(
                            path = path,
                            startPresentationTimeUs = fragment.startPresentationTimeUs,
                            endPresentationTimeUs = fragment.endPresentationTimeUs,
                        )
                }
                JvmFfmpegDolbyVisionFragmentResult.EndOfStream -> break
                is JvmFfmpegDolbyVisionFragmentResult.Failure -> error(fragment.message)
            }
        }
        if (fragments.isEmpty()) error("FFmpeg did not emit any Dolby Vision media fragments.")
        ready(
            request,
            SpooledFfmpegHlsSession(
                initializationSegment = conversionSession.initializationSegment,
                directory = directory,
                fragments = fragments,
            ),
        )
    } catch (cancelled: CancellationException) {
        directory.deleteRecursively()
        throw cancelled
    } catch (error: Throwable) {
        directory.deleteRecursively()
        rejected("Unable to prepare the JVM Dolby Vision VOD bridge: ${error.message ?: error::class.simpleName}.")
    } finally {
        conversionSession.close()
    }
}

private suspend fun prepareHlsVodSource(
    request: VideoPipelineSourceRequest,
    dataSource: JvmDolbyVisionDataSource,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation {
    val playlist =
        runCatching { dataSource.readBounded(request.uri, MAXIMUM_PLAYLIST_BYTES).decodeToString() }
            .getOrElse { return rejected("Unable to read the HLS playlist: ${it.message ?: it::class.simpleName}.") }
    return when (
        val opened =
            HlsVodDolbyVisionAdapter.open(
                playlistUri = request.uri,
                playlist = playlist,
                dataSource = dataSource,
                converter = converter,
                enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
            )
    ) {
        is HlsVodDolbyVisionOpenResult.Success -> ready(request, ExistingHlsVodSession(opened.session))
        is HlsVodDolbyVisionOpenResult.Failure ->
            VideoPipelineSourcePreparation.Rejected(opened.reason.toColorPipelineFallbackReason(), opened.message)
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ready(
    request: VideoPipelineSourceRequest,
    session: JvmDolbyVisionHlsSession,
): VideoPipelineSourcePreparation {
    var sessionOwnedByReady = true
    return try {
        val outputColorInfo = request.profile81OutputColorInfo()
        val detail =
            if (request.source.dolbyVision?.enhancementLayer ==
                io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer.FEL
            ) {
                "Dolby Vision Profile 7 FEL was converted to Profile 8.1; the enhancement layer and FEL mapping were discarded."
            } else {
                "Dolby Vision Profile 7 RPU was converted to Profile 8.1 without re-encoding the base-layer picture."
            }
        val source =
            if (isWindowsHost() && session.supportsSingleFilePlayback) {
                sessionOwnedByReady = false
                materializeDolbyVisionSession(
                    session = session,
                    outputColorInfo = outputColorInfo,
                    detail = detail,
                )
            } else {
                LoopbackDolbyVisionHlsSource(
                    session = session,
                    outputColorInfo = outputColorInfo,
                    detail = detail,
                )
            }
        VideoPipelineSourcePreparation.Ready(source)
    } catch (cancelled: CancellationException) {
        if (sessionOwnedByReady) session.close()
        throw cancelled
    } catch (error: Exception) {
        if (sessionOwnedByReady) session.close()
        rejected(
            "Unable to start the local Dolby Vision playback bridge: " +
                "${error.message ?: error::class.simpleName}.",
        )
    }
}

private fun rejected(detail: String) =
    VideoPipelineSourcePreparation.Rejected(
        ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
        detail,
    )

internal interface JvmDolbyVisionHlsSession {
    val initializationSegment: ByteArray

    /** Number of media fragments when this session can be represented as one fragmented MP4. */
    val singleFileSegmentCount: Int?
        get() = null

    val supportsSingleFilePlayback: Boolean
        get() = singleFileSegmentCount != null

    fun playlist(resourcePrefix: String): String

    suspend fun segment(index: Int): ByteArray

    suspend fun additionalResource(
        path: String,
        resourcePrefix: String,
    ): HlsVodBridgeResource? = null

    fun close() = Unit
}

private data class SpooledDolbyVisionFragment(
    val path: Path,
    val startPresentationTimeUs: Long,
    val endPresentationTimeUs: Long,
)

private class SpooledFfmpegHlsSession(
    override val initializationSegment: ByteArray,
    private val directory: Path,
    private val fragments: List<SpooledDolbyVisionFragment>,
) : JvmDolbyVisionHlsSession {
    @Volatile private var closed = false

    override val singleFileSegmentCount: Int = fragments.size

    override fun playlist(resourcePrefix: String): String {
        check(!closed) { "The spooled Dolby Vision session is closed." }
        val prefix = resourcePrefix.trimEnd('/')
        val targetDuration =
            fragments
                .maxOf {
                    ceil((it.endPresentationTimeUs - it.startPresentationTimeUs) / MICROSECONDS_PER_SECOND).toInt()
                }.coerceAtLeast(1)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"$prefix/init.mp4\"")
            fragments.forEachIndexed { index, fragment ->
                val duration =
                    (fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) /
                        MICROSECONDS_PER_SECOND
                appendLine("#EXTINF:${String.format(Locale.ROOT, "%.6f", duration)},")
                appendLine("$prefix/segment/$index.m4s")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    override suspend fun segment(index: Int): ByteArray =
        withContext(Dispatchers.IO) {
            check(!closed) { "The spooled Dolby Vision session is closed." }
            val fragment = fragments.getOrNull(index) ?: error("Dolby Vision segment $index is out of range.")
            Files.readAllBytes(fragment.path)
        }

    override fun close() {
        if (closed) return
        closed = true
        directory.deleteRecursively()
    }
}

private class ExistingHlsVodSession(
    private val session: HlsVodDolbyVisionSession,
) : JvmDolbyVisionHlsSession {
    override val initializationSegment: ByteArray = session.initializationSegment

    override fun playlist(resourcePrefix: String): String = session.renderEntryPlaylist(resourcePrefix)

    override suspend fun segment(index: Int): ByteArray =
        when (val result = session.convertSegment(index)) {
            is HlsVodDolbyVisionSegmentResult.Success -> result.payload
            is HlsVodDolbyVisionSegmentResult.Failure -> error(result.message)
        }

    override suspend fun additionalResource(
        path: String,
        resourcePrefix: String,
    ): HlsVodBridgeResource? = session.readMasterResource(path, resourcePrefix)
}

private class FlatMp4HlsSession(
    private val session: FlatMp4DolbyVisionSession,
) : JvmDolbyVisionHlsSession {
    override val initializationSegment: ByteArray = session.initializationSegment
    override val singleFileSegmentCount: Int = session.fragments.size

    override fun playlist(resourcePrefix: String): String {
        val normalizedPrefix = resourcePrefix.trimEnd('/')
        val targetDuration =
            session.fragments
                .maxOfOrNull { fragment ->
                    ceil((fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) / MICROSECONDS_PER_SECOND)
                        .toInt()
                }?.coerceAtLeast(1) ?: 1
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"$normalizedPrefix/init.mp4\"")
            session.fragments.forEach { fragment ->
                val duration =
                    (fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) /
                        MICROSECONDS_PER_SECOND
                appendLine("#EXTINF:${String.format(Locale.ROOT, "%.6f", duration)},")
                appendLine("$normalizedPrefix/segment/${fragment.index}.m4s")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    override suspend fun segment(index: Int): ByteArray =
        when (val result = session.convertFragment(index)) {
            is FlatMp4DolbyVisionFragmentResult.Success -> result.payload
            is FlatMp4DolbyVisionFragmentResult.Failure -> error(result.message)
        }
}

private class MatroskaHlsSession(
    private val session: MatroskaDolbyVisionSession,
) : JvmDolbyVisionHlsSession {
    override val initializationSegment: ByteArray = session.initializationSegment
    override val singleFileSegmentCount: Int = session.fragments.size

    override fun playlist(resourcePrefix: String): String {
        val normalizedPrefix = resourcePrefix.trimEnd('/')
        val targetDuration =
            session.fragments
                .maxOfOrNull { fragment ->
                    ceil((fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) / MICROSECONDS_PER_SECOND)
                        .toInt()
                }?.coerceAtLeast(1) ?: 1
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"$normalizedPrefix/init.mp4\"")
            session.fragments.forEach { fragment ->
                val duration =
                    (fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) /
                        MICROSECONDS_PER_SECOND
                appendLine("#EXTINF:${String.format(Locale.ROOT, "%.6f", duration)},")
                appendLine("$normalizedPrefix/segment/${fragment.index}.m4s")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    override suspend fun segment(index: Int): ByteArray =
        when (val result = session.convertFragment(index)) {
            is MatroskaDolbyVisionFragmentResult.Success -> result.payload
            is MatroskaDolbyVisionFragmentResult.Failure -> error(result.message)
        }
}

/**
 * Windows Media Foundation sends HLS through its BGRA frame-server route. A local fragmented MP4
 * instead reaches the controlled P010/D3D11 path, so bounded file VOD is materialized before open.
 */
internal suspend fun materializeDolbyVisionSession(
    session: JvmDolbyVisionHlsSession,
    outputColorInfo: io.github.kdroidfilter.composemediaplayer.VideoColorInfo,
    detail: String,
    maximumBytes: Long = maximumSpoolBytes(),
): PreparedVideoPipelineSource {
    val pendingSource = AtomicReference<PreparedVideoPipelineSource?>()
    var ownershipTransferred = false
    try {
        val prepared =
            withContext(Dispatchers.IO) {
                val segmentCount =
                    requireNotNull(session.singleFileSegmentCount) {
                        "This Dolby Vision session cannot be represented as one fragmented MP4."
                    }
                require(segmentCount > 0) { "The Dolby Vision session contains no media fragments." }
                require(maximumBytes > 0) { "The temporary-storage limit must be positive." }

                val directory = Files.createTempDirectory("kmediaplayer-dovi-mf-")
                val path = directory.resolve("stream.mp4")
                var completed = false
                try {
                    var totalBytes = 0L
                    Files
                        .newOutputStream(
                            path,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        ).use { output ->
                            fun append(payload: ByteArray) {
                                totalBytes += payload.size
                                if (totalBytes > maximumBytes) {
                                    error(
                                        "The converted Dolby Vision VOD exceeds " +
                                            "the configured temporary-storage limit.",
                                    )
                                }
                                output.write(payload)
                            }

                            append(session.initializationSegment)
                            repeat(segmentCount) { index ->
                                coroutineContext.ensureActive()
                                append(session.segment(index))
                            }
                        }
                    MaterializedDolbyVisionFileSource(
                        directory = directory,
                        path = path,
                        session = session,
                        outputColorInfo = outputColorInfo,
                        detail = detail,
                    ).also { source ->
                        completed = true
                        pendingSource.set(source)
                    }
                } finally {
                    if (!completed) directory.deleteRecursively()
                }
            }
        pendingSource.set(null)
        ownershipTransferred = true
        return prepared
    } finally {
        val pending = pendingSource.getAndSet(null)
        if (pending != null) {
            pending.close()
        } else if (!ownershipTransferred) {
            session.close()
        }
    }
}

internal class MaterializedDolbyVisionFileSource(
    private val directory: Path,
    private val path: Path,
    private val session: JvmDolbyVisionHlsSession,
    override val outputColorInfo: io.github.kdroidfilter.composemediaplayer.VideoColorInfo,
    override val detail: String,
) : PreparedVideoPipelineSource {
    private val closed = AtomicBoolean(false)

    override val uri: String = path.toString()
    override val requestHeaders: Map<String, String> = emptyMap()
    override val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.CONVERTED

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            directory.deleteRecursively()
        } finally {
            session.close()
        }
    }
}

internal class LoopbackDolbyVisionHlsSource(
    private val session: JvmDolbyVisionHlsSession,
    override val outputColorInfo: io.github.kdroidfilter.composemediaplayer.VideoColorInfo,
    override val detail: String,
) : PreparedVideoPipelineSource {
    private val executor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "kmediaplayer-dovi-http").apply { isDaemon = true }
        }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0), 0)
    private val token = UUID.randomUUID().toString()
    private val rootPath = "/$token/"

    @Volatile private var closed = false

    override val requestHeaders: Map<String, String> = emptyMap()
    override val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.CONVERTED
    override val uri: String

    init {
        server.executor = executor
        server.createContext(rootPath, ::handle)
        server.start()
        val resourcePrefix = "http://$LOOPBACK_ADDRESS:${server.address.port}/$token"
        uri = "$resourcePrefix/stream.m3u8"
    }

    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
        executor.shutdownNow()
        session.close()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        try {
            if (closed) {
                exchange.respondText(HTTP_GONE, "The Dolby Vision bridge is closed.")
                return
            }
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.responseHeaders.add("Allow", "GET, HEAD")
                exchange.respondText(HTTP_METHOD_NOT_ALLOWED, "Only GET and HEAD are supported.")
                return
            }
            val relativePath = exchange.requestURI.path.removePrefix(rootPath)
            val response =
                when {
                    relativePath == "stream.m3u8" ->
                        HttpPayload(
                            session.playlist(uri.substringBeforeLast('/')).encodeToByteArray(),
                            HLS_CONTENT_TYPE,
                        )
                    relativePath == "init.mp4" -> HttpPayload(session.initializationSegment, MP4_CONTENT_TYPE)
                    SEGMENT_PATH.matches(relativePath) -> {
                        val index = SEGMENT_PATH.matchEntire(relativePath)!!.groupValues[1].toInt()
                        HttpPayload(runBlocking(Dispatchers.IO) { session.segment(index) }, MP4_CONTENT_TYPE)
                    }
                    else ->
                        runBlocking(Dispatchers.IO) {
                            session.additionalResource(relativePath, uri.substringBeforeLast('/'))
                        }?.let { HttpPayload(it.payload, it.contentType) }
                }
            if (response == null) {
                exchange.respondText(HTTP_NOT_FOUND, "Unknown Dolby Vision bridge resource.")
            } else {
                exchange.respond(response)
            }
        } catch (error: Throwable) {
            runCatching {
                exchange.respondText(
                    HTTP_INTERNAL_ERROR,
                    "Dolby Vision fragment conversion failed: ${error.message ?: error::class.simpleName}.",
                )
            }
        } finally {
            exchange.close()
        }
    }
}

private data class HttpPayload(
    val bytes: ByteArray,
    val contentType: String,
)

private fun HttpExchange.respond(payload: HttpPayload) {
    responseHeaders.set("Content-Type", payload.contentType)
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("Content-Length", payload.bytes.size.toString())
    if (requestMethod == "HEAD") {
        sendResponseHeaders(HTTP_OK, -1)
    } else {
        sendResponseHeaders(HTTP_OK, payload.bytes.size.toLong())
        responseBody.use { it.write(payload.bytes) }
    }
}

private fun HttpExchange.respondText(
    status: Int,
    message: String,
) {
    val bytes = message.encodeToByteArray()
    responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
    sendResponseHeaders(status, if (requestMethod == "HEAD") -1 else bytes.size.toLong())
    if (requestMethod != "HEAD") responseBody.use { it.write(bytes) }
}

private class JvmDolbyVisionDataSource(
    private val rootUri: String,
    private val requestHeaders: Map<String, String>,
) : DolbyVisionRandomAccessDataSource,
    DolbyVisionMediaDataSource {
    init {
        requestHeaders.forEach { (name, value) ->
            require(name.isNotBlank() && name.none { it == ':' || it == '\r' || it == '\n' })
            require(value.none { it == '\r' || it == '\n' })
        }
    }

    override suspend fun size(): Long = withContext(Dispatchers.IO) { contentLength(rootUri) }

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            readUri(rootUri, DolbyVisionByteRange(offset, length.toLong()), length)
        }

    override suspend fun read(
        uri: String,
        byteRange: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(maximumBytes in 1..MAXIMUM_MEDIA_BYTES) { "The media byte limit is outside the supported range." }
            byteRange?.let { require(it.length <= maximumBytes) { "The media byte range exceeds its limit." } }
            readUri(uri, byteRange, maximumBytes)
        }

    suspend fun readBounded(
        uri: String,
        maximumBytes: Int,
    ): ByteArray = withContext(Dispatchers.IO) { readUri(uri, null, maximumBytes) }

    private fun contentLength(uri: String): Long {
        localPath(uri)?.let { return Files.size(it) }
        val head = openHttp(uri, method = "HEAD", range = null)
        try {
            if (head.responseCode in HTTP_SUCCESS) {
                head.contentLengthLong.takeIf { it >= 0 }?.let { return it }
            }
        } finally {
            head.disconnect()
        }
        val ranged = openHttp(uri, method = "GET", range = DolbyVisionByteRange(0, 1))
        try {
            val total = CONTENT_RANGE_TOTAL.find(ranged.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)
            return total?.toLongOrNull() ?: error("The HTTP source does not expose its content length.")
        } finally {
            ranged.disconnect()
        }
    }

    private fun readUri(
        uri: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        require(maximumBytes > 0)
        localPath(uri)?.let { path ->
            val offset = range?.offset ?: 0L
            val availableBytes = Files.size(path)
            if (range == null && availableBytes > maximumBytes) {
                error("The local media resource exceeds its byte limit.")
            }
            val requested = range?.length?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: availableBytes.toInt()
            require(requested <= maximumBytes) { "The requested media resource exceeds its byte limit." }
            RandomAccessFile(path.toFile(), "r").use { input ->
                require(offset <= input.length() - requested) { "The requested local media range is unavailable." }
                input.seek(offset)
                return ByteArray(requested).also(input::readFully)
            }
        }

        val connection = openHttp(uri, method = "GET", range = range)
        try {
            val status = connection.responseCode
            require(status in HTTP_SUCCESS) { "HTTP $status while reading $uri" }
            if (range != null && range.offset > 0 && status != HttpURLConnection.HTTP_PARTIAL) {
                error("The HTTP server ignored a non-zero byte-range request.")
            }
            val expected = range?.length?.toInt()
            val limit = expected ?: maximumBytes
            require(limit <= maximumBytes) { "The requested media resource exceeds its byte limit." }
            val bytes = connection.inputStream.use { it.readAtMost(limit) }
            if (expected != null && bytes.size != expected) error("The HTTP byte-range response was truncated.")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttp(
        uri: String,
        method: String,
        range: DolbyVisionByteRange?,
    ): HttpURLConnection {
        val connection =
            URI(uri).toURL().openConnection() as? HttpURLConnection
                ?: error("Only file and HTTP(S) Dolby Vision sources are supported.")
        connection.requestMethod = method
        connection.instanceFollowRedirects = true
        connection.connectTimeout = HTTP_TIMEOUT_MILLIS
        connection.readTimeout = HTTP_TIMEOUT_MILLIS
        requestHeaders.forEach(connection::setRequestProperty)
        range?.let {
            val end = it.offset + it.length - 1
            connection.setRequestProperty("Range", "bytes=${it.offset}-$end")
        }
        return connection
    }
}

private fun java.io.InputStream.readAtMost(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_READ_BUFFER_BYTES))
    val buffer = ByteArray(DEFAULT_READ_BUFFER_BYTES)
    var remaining = maximumBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
        remaining -= count
    }
    if (read() >= 0) error("The media resource exceeds its byte limit.")
    return output.toByteArray()
}

private fun localPath(uri: String): Path? =
    runCatching {
        when {
            WINDOWS_ABSOLUTE_PATH.matches(uri) -> Path.of(uri)
            URI(uri).scheme == null -> Path.of(uri)
            URI(uri).scheme.equals("file", ignoreCase = true) -> Path.of(URI(uri))
            else -> null
        }
    }.getOrNull()

private fun detectContainer(
    uri: String,
    mimeType: String?,
): DolbyVisionContainer {
    val normalizedMime = mimeType.orEmpty().lowercase()
    if (normalizedMime.contains("mpegurl")) return DolbyVisionContainer.HLS_VOD
    if (normalizedMime.contains("matroska")) return DolbyVisionContainer.MATROSKA
    if (normalizedMime.contains("mp4")) return DolbyVisionContainer.MP4
    return when (
        uri
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase()
    ) {
        "m3u8" -> DolbyVisionContainer.HLS_VOD
        "mp4", "m4v", "mov" -> DolbyVisionContainer.MP4
        "mkv", "mka", "webm" -> DolbyVisionContainer.MATROSKA
        else -> DolbyVisionContainer.UNKNOWN
    }
}

private fun maximumSpoolBytes(): Long =
    System
        .getProperty("composemediaplayer.dolbyvision.maximumSpoolBytes")
        ?.toLongOrNull()
        ?.takeIf { it in MINIMUM_SPOOL_BYTES..MAXIMUM_SPOOL_BYTES }
        ?: DEFAULT_MAXIMUM_SPOOL_BYTES

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.deleteIfExists(path) } }
    }
}

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
private const val MP4_CONTENT_TYPE = "video/mp4"
private const val MAXIMUM_PLAYLIST_BYTES = 8 * 1024 * 1024
private const val MAXIMUM_MEDIA_BYTES = 64 * 1024 * 1024
private const val DEFAULT_READ_BUFFER_BYTES = 64 * 1024
private const val HTTP_TIMEOUT_MILLIS = 15_000
private const val MINIMUM_SPOOL_BYTES = 64L * 1024L * 1024L
private const val DEFAULT_MAXIMUM_SPOOL_BYTES = 32L * 1024L * 1024L * 1024L
private const val MAXIMUM_SPOOL_BYTES = 1024L * 1024L * 1024L * 1024L
private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val HTTP_METHOD_NOT_ALLOWED = 405
private const val HTTP_GONE = 410
private const val HTTP_INTERNAL_ERROR = 500
private const val MICROSECONDS_PER_SECOND = 1_000_000.0
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private val HTTP_SUCCESS = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX
private val SEGMENT_PATH = Regex("segment/(\\d+)\\.m4s")
private val CONTENT_RANGE_TOTAL = Regex("/([0-9]+)$")
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

private fun isWindowsHost(): Boolean = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
