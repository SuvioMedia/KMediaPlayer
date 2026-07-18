@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.IosPreparedVideoPipelineSource
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.data
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.memcpy
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

internal actual fun platformDolbyVisionSourceBridgeAvailable(): Boolean = true

internal actual suspend fun preparePlatformDolbyVisionSource(
    request: VideoPipelineSourceRequest,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation =
    withContext(Dispatchers.Default) {
        val input = IosDolbyVisionInput(request.uri, request.requestHeaders)
        when (detectIosContainer(request.uri, request.mimeType)) {
            DolbyVisionContainer.MP4 -> prepareIosFlatMp4(request, input, converter)
            DolbyVisionContainer.HLS_VOD -> prepareIosHlsVod(request, input, converter)
            DolbyVisionContainer.MATROSKA -> prepareIosMatroska(request, input, converter)
            else -> rejected("Only unencrypted MP4, Matroska and fMP4 HLS VOD can be bridged on iOS.")
        }
    }

private suspend fun prepareIosFlatMp4(
    request: VideoPipelineSourceRequest,
    input: IosDolbyVisionInput,
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
        is FlatMp4DolbyVisionOpenResult.Success ->
            iosReady(request, IosFlatMp4HlsSession(opened.session))
        is FlatMp4DolbyVisionOpenResult.Failure -> rejected(opened.message)
    }

@Suppress("TooGenericExceptionCaught")
private suspend fun prepareIosHlsVod(
    request: VideoPipelineSourceRequest,
    input: IosDolbyVisionInput,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation {
    val playlist =
        try {
            input.readBounded(request.uri, MAXIMUM_PLAYLIST_BYTES).decodeToString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return rejected("Unable to read the HLS playlist: ${error.message ?: error::class.simpleName}.")
        }
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
        is HlsVodDolbyVisionOpenResult.Success ->
            iosReady(request, IosExistingHlsSession(opened.session))
        is HlsVodDolbyVisionOpenResult.Failure ->
            VideoPipelineSourcePreparation.Rejected(opened.reason.toColorPipelineFallbackReason(), opened.message)
    }
}

private suspend fun prepareIosMatroska(
    request: VideoPipelineSourceRequest,
    input: IosDolbyVisionInput,
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
            iosReady(request, IosMatroskaHlsSession(opened.session))
        is MatroskaDolbyVisionOpenResult.Failure -> rejected(opened.message)
    }

private fun iosReady(
    request: VideoPipelineSourceRequest,
    session: IosDolbyVisionHlsSession,
): VideoPipelineSourcePreparation =
    VideoPipelineSourcePreparation.Ready(
        IosDolbyVisionPreparedSource(
            session = session,
            outputColorInfo = request.profile81OutputColorInfo(),
            detail =
                if (request.source.dolbyVision?.enhancementLayer == DolbyVisionEnhancementLayer.FEL) {
                    "Dolby Vision Profile 7 FEL was converted to Profile 8.1; " +
                        "the enhancement layer and FEL mapping were discarded."
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

private interface IosDolbyVisionHlsSession {
    val initializationSegment: ByteArray

    fun playlist(resourcePrefix: String): String

    suspend fun segment(index: Int): ByteArray

    suspend fun additionalResource(
        path: String,
        resourcePrefix: String,
    ): HlsVodBridgeResource? = null
}

private class IosExistingHlsSession(
    private val delegate: HlsVodDolbyVisionSession,
) : IosDolbyVisionHlsSession {
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

private class IosFlatMp4HlsSession(
    private val delegate: FlatMp4DolbyVisionSession,
) : IosDolbyVisionHlsSession {
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
                appendLine("#EXTINF:$duration,")
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

private class IosMatroskaHlsSession(
    private val delegate: MatroskaDolbyVisionSession,
) : IosDolbyVisionHlsSession {
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
                appendLine("#EXTINF:$duration,")
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

private class IosDolbyVisionPreparedSource(
    session: IosDolbyVisionHlsSession,
    override val outputColorInfo: VideoColorInfo,
    override val detail: String,
) : IosPreparedVideoPipelineSource {
    private val token =
        platform.Foundation.NSUUID
            .UUID()
            .UUIDString
    private val resourcePrefix = "$SOURCE_SCHEME://$token"
    private val loader = IosDolbyVisionResourceLoader(token, resourcePrefix, session)
    private val delegateQueue = dispatch_queue_create("io.github.shusek.composemediaplayer.dolbyvision.loader", null)
    private var asset: AVURLAsset? = null
    private var closed = false

    override val uri: String = "$resourcePrefix/stream.m3u8"
    override val requestHeaders: Map<String, String> = emptyMap()
    override val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.CONVERTED

    override fun createPlayerItem(): AVPlayerItem {
        check(!closed) { "The iOS Dolby Vision source is closed." }
        val url = requireNotNull(NSURL.URLWithString(uri)) { "Unable to construct the iOS bridge URL." }
        val createdAsset = AVURLAsset.URLAssetWithURL(url, null)
        createdAsset.resourceLoader().setDelegate(loader, delegateQueue)
        asset = createdAsset
        return AVPlayerItem(createdAsset)
    }

    override fun close() {
        if (closed) return
        closed = true
        asset?.resourceLoader()?.setDelegate(null, null)
        asset = null
        loader.close()
    }
}

private class IosDolbyVisionResourceLoader(
    private val token: String,
    private val resourcePrefix: String,
    private val session: IosDolbyVisionHlsSession,
) : NSObject(),
    AVAssetResourceLoaderDelegateProtocol {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<AVAssetResourceLoadingRequest, Job>()
    private val jobsLock = NSLock()
    private var closed = false

    @ObjCSignatureOverride
    @Suppress("TooGenericExceptionCaught")
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        if (closed) return false
        val request = shouldWaitForLoadingOfRequestedResource
        val job =
            scope.launch {
                try {
                    val resource = resourceFor(request)
                    if (!request.cancelled) respond(request, resource)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (!request.cancelled) request.finishLoadingWithError(error.toNSError())
                } finally {
                    removeJob(request)
                }
            }
        putJob(request, job)
        return true
    }

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        didCancelLoadingRequest: AVAssetResourceLoadingRequest,
    ) {
        takeJob(didCancelLoadingRequest)?.cancel()
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        jobsLock.lock()
        jobs.clear()
        jobsLock.unlock()
    }

    private suspend fun resourceFor(request: AVAssetResourceLoadingRequest): IosBridgeResource {
        val url = request.request.URL ?: error("AVFoundation requested a bridge resource without a URL.")
        require(url.scheme == SOURCE_SCHEME && url.host == token) { "Unknown iOS Dolby Vision bridge resource." }
        val path = url.path.orEmpty().trimStart('/')
        return when {
            path == "stream.m3u8" ->
                IosBridgeResource(
                    session.playlist(resourcePrefix).encodeToByteArray(),
                    HLS_PLAYLIST_CONTENT_TYPE,
                )
            path == "init.mp4" -> IosBridgeResource(session.initializationSegment, MPEG4_CONTENT_TYPE)
            SEGMENT_PATH.matches(path) -> {
                val index = SEGMENT_PATH.matchEntire(path)!!.groupValues[1].toInt()
                IosBridgeResource(session.segment(index), MPEG4_CONTENT_TYPE)
            }
            else ->
                session.additionalResource(path, resourcePrefix)?.let { resource ->
                    IosBridgeResource(resource.payload, resource.contentType.toIosBridgeContentType())
                } ?: error("Unknown iOS Dolby Vision bridge path.")
        }
    }

    private fun respond(
        request: AVAssetResourceLoadingRequest,
        resource: IosBridgeResource,
    ) {
        request.contentInformationRequest?.let { information ->
            information.contentType = resource.contentType
            information.contentLength = resource.payload.size.toLong()
            information.byteRangeAccessSupported = true
            information.entireLengthAvailableOnDemand = true
        }
        request.dataRequest?.let { dataRequest ->
            val start = maxOf(dataRequest.currentOffset, dataRequest.requestedOffset)
            require(start in 0..resource.payload.size.toLong()) { "AVFoundation requested an invalid byte offset." }
            val end =
                if (dataRequest.requestsAllDataToEndOfResource) {
                    resource.payload.size.toLong()
                } else {
                    (start + dataRequest.requestedLength).coerceAtMost(resource.payload.size.toLong())
                }
            require(end >= start && end - start <= Int.MAX_VALUE) { "AVFoundation requested an invalid byte range." }
            val bytes = resource.payload.copyOfRange(start.toInt(), end.toInt())
            dataRequest.respondWithData(bytes.toNSData())
        }
        request.finishLoading()
    }

    private fun putJob(
        request: AVAssetResourceLoadingRequest,
        job: Job,
    ) {
        jobsLock.lock()
        jobs[request] = job
        jobsLock.unlock()
    }

    private fun takeJob(request: AVAssetResourceLoadingRequest): Job? {
        jobsLock.lock()
        val job = jobs.remove(request)
        jobsLock.unlock()
        return job
    }

    private fun removeJob(request: AVAssetResourceLoadingRequest) {
        jobsLock.lock()
        jobs.remove(request)
        jobsLock.unlock()
    }
}

private data class IosBridgeResource(
    val payload: ByteArray,
    val contentType: String,
)

private class IosDolbyVisionInput(
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

    override suspend fun size(): Long = sourceSize(rootUri)

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray = readUri(rootUri, DolbyVisionByteRange(offset, length.toLong()), length)

    override suspend fun read(
        uri: String,
        byteRange: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        require(maximumBytes in 1..MAXIMUM_MEDIA_BYTES) { "The media byte limit is outside the supported range." }
        byteRange?.let { require(it.length <= maximumBytes) { "The media byte range exceeds its limit." } }
        return readUri(uri, byteRange, maximumBytes)
    }

    suspend fun readBounded(
        uri: String,
        maximumBytes: Int,
    ): ByteArray = readUri(uri, null, maximumBytes)

    private suspend fun sourceSize(uri: String): Long {
        val url = sourceUrl(uri)
        return when (url.scheme?.lowercase()) {
            "http", "https" -> httpContentLength(uri)
            "file", null -> localFileSize(requireNotNull(url.path))
            else -> error("Unsupported iOS Dolby Vision source scheme: ${url.scheme}.")
        }
    }

    private suspend fun readUri(
        uri: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        val url = sourceUrl(uri)
        return when (url.scheme?.lowercase()) {
            "http", "https" -> readHttp(uri, range, maximumBytes)
            "file", null -> readLocalFile(requireNotNull(url.path), range, maximumBytes)
            else -> error("Unsupported iOS Dolby Vision source scheme: ${url.scheme}.")
        }
    }

    private suspend fun readHttp(
        uri: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        val response = executeHttp(uri, "GET", range, maximumBytes)
        require(response.status in HTTP_SUCCESS) { "HTTP ${response.status} while reading $uri." }
        if (range != null) require(response.status == HTTP_PARTIAL_CONTENT) { "The HTTP server ignored a byte range." }
        val expectedLength = range?.length?.toInt()
        if (expectedLength != null) require(response.payload.size == expectedLength) { "The HTTP range was truncated." }
        require(response.payload.size <= maximumBytes) { "The iOS media resource exceeds its byte limit." }
        return response.payload
    }

    private suspend fun httpContentLength(uri: String): Long {
        val head = executeHttp(uri, "HEAD", null, 1)
        if (head.status in HTTP_SUCCESS && head.expectedContentLength >= 0) return head.expectedContentLength
        val ranged = executeHttp(uri, "GET", DolbyVisionByteRange(0, 1), 1)
        return CONTENT_RANGE_TOTAL
            .find(ranged.header("Content-Range").orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: error("The HTTP source does not expose its content length.")
    }

    private suspend fun executeHttp(
        uri: String,
        method: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): IosHttpResponse =
        suspendCancellableCoroutine { continuation ->
            require(maximumBytes in 1..MAXIMUM_MEDIA_BYTES) { "The iOS fetch byte limit is outside its range." }
            val request =
                NSMutableURLRequest(sourceUrl(uri)).apply {
                    setHTTPMethod(method)
                    setTimeoutInterval(HTTP_TIMEOUT_SECONDS)
                    requestHeaders.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
                    range?.let { setValue("bytes=${it.offset}-${it.offset + it.length - 1}", "Range") }
                }
            lateinit var session: NSURLSession
            val delegate =
                IosBoundedHttpDelegate(maximumBytes) { response, error ->
                    if (!continuation.isActive) return@IosBoundedHttpDelegate
                    if (error == null) {
                        continuation.resume(requireNotNull(response))
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            session =
                NSURLSession.sessionWithConfiguration(
                    NSURLSessionConfiguration.ephemeralSessionConfiguration,
                    delegate,
                    null,
                )
            val task = session.dataTaskWithRequest(request)
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}

private class IosBoundedHttpDelegate(
    private val maximumBytes: Int,
    private val onComplete: (IosHttpResponse?, Throwable?) -> Unit,
) : NSObject(),
    NSURLSessionDataDelegateProtocol {
    private val chunks = mutableListOf<ByteArray>()
    private var payloadSize = 0
    private var terminalFailure: Throwable? = null
    private var completed = false

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        if (terminalFailure != null || completed) return
        val chunkSize = didReceiveData.length.toLong()
        if (chunkSize > maximumBytes - payloadSize) {
            terminalFailure = IllegalStateException("The iOS media resource exceeds its byte limit.")
            dataTask.cancel()
            return
        }
        chunks += didReceiveData.toByteArray()
        payloadSize += chunkSize.toInt()
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        if (completed) return
        completed = true
        val failure = terminalFailure ?: didCompleteWithError?.let { IllegalStateException(it.localizedDescription()) }
        val http = task.response as? NSHTTPURLResponse
        when {
            failure != null -> onComplete(null, failure)
            http == null -> onComplete(null, IllegalStateException("The source returned no HTTP response."))
            else ->
                onComplete(
                    IosHttpResponse(
                        status = http.statusCode.toInt(),
                        expectedContentLength = http.expectedContentLength,
                        headers =
                            http.allHeaderFields
                                .mapKeys { it.key.toString() }
                                .mapValues { it.value.toString() },
                        payload = combineChunks(),
                    ),
                    null,
                )
        }
        session.finishTasksAndInvalidate()
    }

    private fun combineChunks(): ByteArray =
        ByteArray(payloadSize).also { output ->
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(output, offset)
                offset += chunk.size
            }
        }
}

private data class IosHttpResponse(
    val status: Int,
    val expectedContentLength: Long,
    val headers: Map<String, String>,
    val payload: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

private fun sourceUrl(uri: String): NSURL =
    NSURL.URLWithString(uri)?.takeIf { it.scheme != null } ?: NSURL.fileURLWithPath(uri)

private fun localFileSize(path: String): Long =
    memScoped {
        val value = alloc<stat>()
        require(platform.posix.stat(path, value.ptr) == 0) { "Unable to stat the iOS media file." }
        value.st_size
    }

private fun readLocalFile(
    path: String,
    range: DolbyVisionByteRange?,
    maximumBytes: Int,
): ByteArray {
    val size = localFileSize(path)
    if (range == null) require(size <= maximumBytes) { "The local iOS media resource exceeds its byte limit." }
    val offset = range?.offset ?: 0L
    val length = range?.length?.toInt() ?: size.toInt()
    require(length <= maximumBytes && offset >= 0 && offset <= size - length) {
        "The requested local iOS media range is unavailable."
    }
    val descriptor = open(path, O_RDONLY)
    require(descriptor >= 0) { "Unable to open the local iOS media file." }
    return try {
        ByteArray(length).also { output ->
            output.usePinned { pinned ->
                var completed = 0
                while (completed < length) {
                    val count =
                        pread(
                            descriptor,
                            pinned.addressOf(completed),
                            (length - completed).convert(),
                            offset + completed,
                        )
                    require(count > 0) { "The local iOS media range was truncated." }
                    completed += count.toInt()
                }
            }
        }
    } finally {
        close(descriptor)
    }
}

private fun NSData.toByteArray(): ByteArray {
    require(length <= Int.MAX_VALUE.toULong()) { "The iOS response is too large." }
    if (length == 0UL) return ByteArray(0)
    return ByteArray(length.toInt()).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.data()
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

private fun Throwable.toNSError(): NSError =
    NSError.errorWithDomain(
        domain = "io.github.shusek.composemediaplayer.dolbyvision",
        code = 1,
        userInfo = mapOf(NSLocalizedDescriptionKey to (message ?: "Dolby Vision bridge failure.")),
    )

private fun detectIosContainer(
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

private fun String.toIosBridgeContentType(): String =
    when (this) {
        DOVI_HLS_PLAYLIST_CONTENT_TYPE -> HLS_PLAYLIST_CONTENT_TYPE
        DOVI_MP4_CONTENT_TYPE -> MPEG4_CONTENT_TYPE
        "text/vtt" -> "public.webvtt"
        "audio/aac" -> "public.aac-audio"
        "video/mp2t" -> "public.mpeg-2-transport-stream"
        "audio/ac3", "audio/eac3" -> "public.audio"
        else -> "public.data"
    }

private const val SOURCE_SCHEME = "cmpdovi"
private const val HLS_PLAYLIST_CONTENT_TYPE = "public.m3u-playlist"
private const val MPEG4_CONTENT_TYPE = "public.mpeg-4"
private const val MAXIMUM_PLAYLIST_BYTES = 8 * 1024 * 1024
private const val MAXIMUM_MEDIA_BYTES = 64 * 1024 * 1024
private const val MICROSECONDS_PER_SECOND = 1_000_000.0
private const val HTTP_TIMEOUT_SECONDS = 15.0
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private val HTTP_SUCCESS = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX
private val SEGMENT_PATH = Regex("segment/(\\d+)\\.m4s")
private val CONTENT_RANGE_TOTAL = Regex("/([0-9]+)$")
