@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.AndroidPreparedVideoPipelineSource
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

internal actual fun platformDolbyVisionSourceBridgeAvailable(): Boolean =
    runCatching { ContextProvider.getContext() }.isSuccess

internal actual suspend fun preparePlatformDolbyVisionSource(
    request: VideoPipelineSourceRequest,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    withContext(Dispatchers.IO) {
        val context =
            runCatching { ContextProvider.getContext() }.getOrElse {
                return@withContext rejected("The Android application context is unavailable.")
            }
        val dataSource = AndroidDolbyVisionInput(context, request.uri, request.requestHeaders)
        when (detectAndroidContainer(request.uri, request.mimeType)) {
            DolbyVisionContainer.MP4 -> prepareAndroidFlatMp4(request, dataSource, converter)
            DolbyVisionContainer.HLS_VOD -> prepareAndroidHlsVod(request, dataSource, converter)
            DolbyVisionContainer.MATROSKA -> prepareAndroidMatroska(request, dataSource, converter)
            else -> rejected("Only unencrypted MP4, Matroska and fMP4 HLS VOD can be bridged on Android.")
        }
    }

private suspend fun prepareAndroidFlatMp4(
    request: VideoPipelineSourceRequest,
    input: AndroidDolbyVisionInput,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    when (
        val opened =
            FlatMp4DolbyVisionAdapter.open(
                source = input,
                converter = converter,
                enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
            )
    ) {
        is FlatMp4DolbyVisionOpenResult.Success -> androidReady(request, AndroidFlatMp4HlsSession(opened.session))
        is FlatMp4DolbyVisionOpenResult.Failure -> rejected(opened.message)
    }

private suspend fun prepareAndroidHlsVod(
    request: VideoPipelineSourceRequest,
    input: AndroidDolbyVisionInput,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation {
    val playlist =
        runCatching { input.readBounded(request.uri, MAXIMUM_PLAYLIST_BYTES).decodeToString() }
            .getOrElse { return rejected("Unable to read the HLS playlist: ${it.message ?: it::class.simpleName}.") }
    return when (
        val opened =
            HlsVodDolbyVisionAdapter.open(
                playlistUri = request.uri,
                playlist = playlist,
                dataSource = input,
                converter = converter,
                enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
            )
    ) {
        is HlsVodDolbyVisionOpenResult.Success -> androidReady(request, AndroidExistingHlsSession(opened.session))
        is HlsVodDolbyVisionOpenResult.Failure ->
            VideoPipelineSourcePreparation.Rejected(opened.reason.toColorPipelineFallbackReason(), opened.message)
    }
}

private suspend fun prepareAndroidMatroska(
    request: VideoPipelineSourceRequest,
    input: AndroidDolbyVisionInput,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    when (
        val opened =
            MatroskaDolbyVisionAdapter.open(
                source = input,
                converter = converter,
                enhancementLayer = request.source.dolbyVision!!.enhancementLayer,
            )
    ) {
        is MatroskaDolbyVisionOpenResult.Success ->
            androidReady(request, AndroidMatroskaHlsSession(opened.session))
        is MatroskaDolbyVisionOpenResult.Failure -> rejected(opened.message)
    }

private fun androidReady(
    request: VideoPipelineSourceRequest,
    session: AndroidDolbyVisionHlsSession,
): VideoPipelineSourcePreparation =
    VideoPipelineSourcePreparation.Ready(
        AndroidDolbyVisionMediaSource(
            session = session,
            outputColorInfo = request.profile81OutputColorInfo(),
            detail =
                if (request.source.dolbyVision?.enhancementLayer == DolbyVisionEnhancementLayer.FEL) {
                    "Dolby Vision Profile 7 FEL was converted to Profile 8.1; the enhancement layer and FEL mapping were discarded."
                } else {
                    "Dolby Vision Profile 7 RPU was converted to Profile 8.1 without re-encoding the base-layer picture."
                },
        ),
    )

private fun rejected(detail: String) =
    VideoPipelineSourcePreparation.Rejected(
        ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
        detail,
    )

private interface AndroidDolbyVisionHlsSession {
    val initializationSegment: ByteArray

    fun playlist(resourcePrefix: String): String

    suspend fun segment(index: Int): ByteArray

    suspend fun additionalResource(
        path: String,
        resourcePrefix: String,
    ): HlsVodBridgeResource? = null
}

private class AndroidExistingHlsSession(
    private val delegate: HlsVodDolbyVisionSession,
) : AndroidDolbyVisionHlsSession {
    override val initializationSegment: ByteArray = delegate.initializationSegment

    override fun playlist(resourcePrefix: String): String = delegate.renderEntryPlaylist(resourcePrefix)

    override suspend fun segment(index: Int): ByteArray =
        when (val result = delegate.convertSegment(index)) {
            is HlsVodDolbyVisionSegmentResult.Success -> result.payload
            is HlsVodDolbyVisionSegmentResult.Failure -> error(result.message)
        }

    override suspend fun additionalResource(
        path: String,
        resourcePrefix: String,
    ): HlsVodBridgeResource? = delegate.readMasterResource(path, resourcePrefix)
}

private class AndroidFlatMp4HlsSession(
    private val delegate: FlatMp4DolbyVisionSession,
) : AndroidDolbyVisionHlsSession {
    override val initializationSegment: ByteArray = delegate.initializationSegment

    override fun playlist(resourcePrefix: String): String {
        val prefix = resourcePrefix.trimEnd('/')
        val targetDuration =
            delegate.fragments
                .maxOfOrNull {
                    ceil((it.endPresentationTimeUs - it.startPresentationTimeUs) / MICROSECONDS_PER_SECOND).toInt()
                }?.coerceAtLeast(1) ?: 1
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"$prefix/init.mp4\"")
            delegate.fragments.forEach { fragment ->
                val duration =
                    (fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) /
                        MICROSECONDS_PER_SECOND
                appendLine("#EXTINF:${String.format(Locale.US, "%.6f", duration)},")
                appendLine("$prefix/segment/${fragment.index}.m4s")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    override suspend fun segment(index: Int): ByteArray =
        when (val result = delegate.convertFragment(index)) {
            is FlatMp4DolbyVisionFragmentResult.Success -> result.payload
            is FlatMp4DolbyVisionFragmentResult.Failure -> error(result.message)
        }
}

private class AndroidMatroskaHlsSession(
    private val delegate: MatroskaDolbyVisionSession,
) : AndroidDolbyVisionHlsSession {
    override val initializationSegment: ByteArray = delegate.initializationSegment

    override fun playlist(resourcePrefix: String): String {
        val prefix = resourcePrefix.trimEnd('/')
        val targetDuration =
            delegate.fragments
                .maxOfOrNull {
                    ceil((it.endPresentationTimeUs - it.startPresentationTimeUs) / MICROSECONDS_PER_SECOND).toInt()
                }?.coerceAtLeast(1) ?: 1
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"$prefix/init.mp4\"")
            delegate.fragments.forEach { fragment ->
                val duration =
                    (fragment.endPresentationTimeUs - fragment.startPresentationTimeUs) /
                        MICROSECONDS_PER_SECOND
                appendLine("#EXTINF:${String.format(Locale.US, "%.6f", duration)},")
                appendLine("$prefix/segment/${fragment.index}.m4s")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    override suspend fun segment(index: Int): ByteArray =
        when (val result = delegate.convertFragment(index)) {
            is MatroskaDolbyVisionFragmentResult.Success -> result.payload
            is MatroskaDolbyVisionFragmentResult.Failure -> error(result.message)
        }
}

private class AndroidDolbyVisionMediaSource(
    private val session: AndroidDolbyVisionHlsSession,
    override val outputColorInfo: VideoColorInfo,
    override val detail: String,
) : AndroidPreparedVideoPipelineSource {
    private val token = UUID.randomUUID().toString()
    private val resourcePrefix = "$SOURCE_SCHEME://$token"

    @Volatile private var closed = false

    override val uri: String = "$resourcePrefix/stream.m3u8"
    override val requestHeaders: Map<String, String> = emptyMap()
    override val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.CONVERTED

    override fun createMediaSource(): MediaSource {
        check(!closed) { "The Dolby Vision Media3 source is closed." }
        return HlsMediaSource
            .Factory(DataSource.Factory { AndroidDolbyVisionDataSource(this) })
            .createMediaSource(MediaItem.fromUri(uri))
    }

    override fun close() {
        closed = true
    }

    fun resource(resourceUri: Uri): ByteArray {
        val path = validatedResourcePath(resourceUri)
        return when {
            path == "stream.m3u8" -> session.playlist(resourcePrefix).encodeToByteArray()
            path == "init.mp4" -> session.initializationSegment
            SEGMENT_PATH.matches(path) -> {
                val index = SEGMENT_PATH.matchEntire(path)!!.groupValues[1].toInt()
                runBlocking(Dispatchers.IO) { session.segment(index) }
            }
            else ->
                runBlocking(Dispatchers.IO) { session.additionalResource(path, resourcePrefix) }?.payload
                    ?: throw IOException("Unknown Dolby Vision Media3 resource path.")
        }
    }

    private fun validatedResourcePath(resourceUri: Uri): String {
        if (closed) throw IOException("The Dolby Vision Media3 source is closed.")
        if (resourceUri.scheme != SOURCE_SCHEME || resourceUri.host != token) {
            throw IOException("Unknown Dolby Vision Media3 resource.")
        }
        return resourceUri.path.orEmpty().trimStart('/')
    }
}

private class AndroidDolbyVisionDataSource(
    private val source: AndroidDolbyVisionMediaSource,
) : BaseDataSource(false) {
    private var openedUri: Uri? = null
    private var bytes = ByteArray(0)
    private var position = 0
    private var limit = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val payload = source.resource(dataSpec.uri)
        if (dataSpec.position > payload.size) throw IOException("Media3 requested a range outside the bridge resource.")
        position = dataSpec.position.toInt()
        val requestedEnd =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                payload.size
            } else {
                (dataSpec.position + dataSpec.length).coerceAtMost(payload.size.toLong()).toInt()
            }
        bytes = payload
        limit = requestedEnd
        openedUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return (limit - position).toLong()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (position >= limit) return C.RESULT_END_OF_INPUT
        val count = minOf(length, limit - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri = null
        bytes = ByteArray(0)
        position = 0
        limit = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

private class AndroidDolbyVisionInput(
    private val context: Context,
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

    override suspend fun size(): Long = withContext(Dispatchers.IO) { sourceSize(rootUri) }

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

    private fun sourceSize(uri: String): Long {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            null, "" -> File(uri).length()
            "file" -> File(requireNotNull(parsed.path)).length()
            "content" ->
                context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0 }
                } ?: error("The content source does not expose its length.")
            "asset" -> context.assets.openFd(parsed.path.orEmpty().trimStart('/')).length
            "http", "https" -> httpContentLength(uri)
            else -> error("Unsupported Android Dolby Vision source scheme: ${parsed.scheme}.")
        }
    }

    private fun readUri(
        uri: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            null, "" -> readFile(File(uri), range, maximumBytes)
            "file" -> readFile(File(requireNotNull(parsed.path)), range, maximumBytes)
            "content" -> readStream(openContent(parsed), range, maximumBytes)
            "asset" -> readStream(context.assets.open(parsed.path.orEmpty().trimStart('/')), range, maximumBytes)
            "http", "https" -> readHttp(uri, range, maximumBytes)
            else -> error("Unsupported Android Dolby Vision source scheme: ${parsed.scheme}.")
        }
    }

    private fun openContent(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Unable to open Android content URI.")

    private fun readFile(
        file: File,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        val size = file.length()
        if (range == null && size > maximumBytes) error("The local media resource exceeds its byte limit.")
        val offset = range?.offset ?: 0L
        val length = range?.length?.toInt() ?: size.toInt()
        require(length <= maximumBytes && offset <= size - length) { "The requested local media range is unavailable." }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            ByteArray(length).also(input::readFully)
        }
    }

    private fun readStream(
        stream: InputStream,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray =
        stream.use { input ->
            input.skipExactly(range?.offset ?: 0L)
            val limit = range?.length?.toInt() ?: maximumBytes
            if (range == null) input.readAtMost(limit) else input.readExactly(limit)
        }

    private fun readHttp(
        uri: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        val connection = openHttp(uri, "GET", range)
        try {
            val status = connection.responseCode
            require(status in HTTP_SUCCESS) { "HTTP $status while reading $uri" }
            if (range != null && status != HttpURLConnection.HTTP_PARTIAL) {
                error("The HTTP server ignored a byte-range request.")
            }
            val limit = range?.length?.toInt() ?: maximumBytes
            return connection.inputStream.use { input ->
                if (range == null) input.readAtMost(limit) else input.readExactly(limit)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpContentLength(uri: String): Long {
        val head = openHttp(uri, "HEAD", null)
        try {
            if (head.responseCode in HTTP_SUCCESS) head.contentLengthLong.takeIf { it >= 0 }?.let { return it }
        } finally {
            head.disconnect()
        }
        val ranged = openHttp(uri, "GET", DolbyVisionByteRange(0, 1))
        try {
            return CONTENT_RANGE_TOTAL
                .find(ranged.getHeaderField("Content-Range").orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?: error("The HTTP source does not expose its content length.")
        } finally {
            ranged.disconnect()
        }
    }

    private fun openHttp(
        uri: String,
        method: String,
        range: DolbyVisionByteRange?,
    ): HttpURLConnection {
        val connection = URI(uri).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = true
        connection.connectTimeout = HTTP_TIMEOUT_MILLIS
        connection.readTimeout = HTTP_TIMEOUT_MILLIS
        requestHeaders.forEach(connection::setRequestProperty)
        range?.let {
            connection.setRequestProperty("Range", "bytes=${it.offset}-${it.offset + it.length - 1}")
        }
        return connection
    }
}

private fun InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() < 0) {
            error("The requested Android source offset is unavailable.")
        } else {
            remaining--
        }
    }
}

private fun InputStream.readAtMost(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, READ_BUFFER_BYTES))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var remaining = maximumBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
        remaining -= count
    }
    if (read() >= 0) error("The Android media resource exceeds its byte limit.")
    return output.toByteArray()
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) error("The Android source byte range was truncated.")
        offset += count
    }
    return result
}

private fun detectAndroidContainer(
    uri: String,
    mimeType: String?,
): DolbyVisionContainer {
    val mime = mimeType.orEmpty().lowercase()
    if (mime.contains("mpegurl")) return DolbyVisionContainer.HLS_VOD
    if (mime.contains("matroska")) return DolbyVisionContainer.MATROSKA
    if (mime.contains("mp4")) return DolbyVisionContainer.MP4
    return when (uri.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "m3u8" -> DolbyVisionContainer.HLS_VOD
        "mp4", "m4v", "mov" -> DolbyVisionContainer.MP4
        "mkv", "webm" -> DolbyVisionContainer.MATROSKA
        else -> DolbyVisionContainer.UNKNOWN
    }
}

private const val SOURCE_SCHEME = "cmpdovi"
private const val MAXIMUM_PLAYLIST_BYTES = 8 * 1024 * 1024
private const val MAXIMUM_MEDIA_BYTES = 64 * 1024 * 1024
private const val READ_BUFFER_BYTES = 64 * 1024
private const val HTTP_TIMEOUT_MILLIS = 15_000
private const val MICROSECONDS_PER_SECOND = 1_000_000.0
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private val HTTP_SUCCESS = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX
private val SEGMENT_PATH = Regex("segment/(\\d+)\\.m4s")
private val CONTENT_RANGE_TOTAL = Regex("/([0-9]+)$")
