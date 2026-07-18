@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import io.github.kdroidfilter.composemediaplayer.WebPreparedVideoPipelineSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.HTMLVideoElement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.toDouble
import kotlin.js.toInt
import kotlin.js.toJsNumber
import kotlin.js.toJsString

internal actual fun platformDolbyVisionSourceBridgeAvailable(): Boolean = webDolbyVisionMseAvailable()

internal actual suspend fun preparePlatformDolbyVisionSource(
    request: VideoPipelineSourceRequest,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation {
    if (!webDolbyVisionMseAvailable()) {
        return rejected("MediaSource does not expose a compatible Dolby Vision Profile 8 codec in this browser.")
    }
    val input = WebDolbyVisionInput(request.uri, request.requestHeaders)
    return when (detectWebContainer(request.uri, request.mimeType)) {
        DolbyVisionContainer.MP4 -> prepareWebFlatMp4(request, input, converter)
        DolbyVisionContainer.HLS_VOD -> prepareWebHlsVod(request, input, converter)
        DolbyVisionContainer.MATROSKA -> prepareWebMatroska(request, input, converter)
        else -> rejected("Only unencrypted MP4, Matroska and fMP4 HLS VOD can be bridged in the browser.")
    }
}

private suspend fun prepareWebFlatMp4(
    request: VideoPipelineSourceRequest,
    input: WebDolbyVisionInput,
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
        is FlatMp4DolbyVisionOpenResult.Success -> webReady(request, WebFlatMp4Session(opened.session))
        is FlatMp4DolbyVisionOpenResult.Failure -> rejected(opened.message)
    }

@Suppress("TooGenericExceptionCaught")
private suspend fun prepareWebHlsVod(
    request: VideoPipelineSourceRequest,
    input: WebDolbyVisionInput,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation {
    val playlist =
        try {
            input.readBounded(request.uri, MAXIMUM_PLAYLIST_BYTES).decodeToString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return rejected("Unable to read the browser HLS playlist: ${error.message ?: error::class.simpleName}.")
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
        is HlsVodDolbyVisionOpenResult.Success -> {
            when (val audio = prepareWebHlsAudio(opened.session)) {
                is WebHlsAudioPreparation.Ready ->
                    webReady(request, WebExistingHlsSession(opened.session), audio.session)
                is WebHlsAudioPreparation.Failure -> rejected(audio.message)
                WebHlsAudioPreparation.None -> webReady(request, WebExistingHlsSession(opened.session))
            }
        }
        is HlsVodDolbyVisionOpenResult.Failure ->
            VideoPipelineSourcePreparation.Rejected(opened.reason.toColorPipelineFallbackReason(), opened.message)
    }
}

private suspend fun prepareWebMatroska(
    request: VideoPipelineSourceRequest,
    input: WebDolbyVisionInput,
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
        is MatroskaDolbyVisionOpenResult.Success -> webReady(request, WebMatroskaSession(opened.session))
        is MatroskaDolbyVisionOpenResult.Failure -> rejected(opened.message)
    }

private fun webReady(
    request: VideoPipelineSourceRequest,
    session: WebDolbyVisionSession,
    audioSession: WebHlsAudioSession? = null,
): VideoPipelineSourcePreparation {
    val outputColor = request.profile81OutputColorInfo()
    val mimeType =
        selectWebDolbyVisionMimeType(session.initializationSegment, outputColor.dolbyVision?.level)
            ?: return rejected("No MediaSource MIME type matches the converted Dolby Vision initialization segment.")
    return VideoPipelineSourcePreparation.Ready(
        WebDolbyVisionPreparedSource(
            session = session,
            audioSession = audioSession,
            mimeType = mimeType,
            outputColorInfo = outputColor,
            detail =
                if (request.source.dolbyVision?.enhancementLayer == DolbyVisionEnhancementLayer.FEL) {
                    "Dolby Vision Profile 7 FEL was converted to Profile 8.1; " +
                        "the enhancement layer and FEL mapping were discarded."
                } else {
                    "Dolby Vision Profile 7 RPU was converted to Profile 8.1 without re-encoding the base-layer picture."
                } +
                    if (audioSession != null) {
                        " The default external HLS audio rendition is preserved in a synchronized MediaSource buffer."
                    } else {
                        ""
                    },
        ),
    )
}

private sealed interface WebHlsAudioPreparation {
    data object None : WebHlsAudioPreparation

    data class Ready(
        val session: WebHlsAudioSession,
    ) : WebHlsAudioPreparation

    data class Failure(
        val message: String,
    ) : WebHlsAudioPreparation
}

private fun prepareWebHlsAudio(session: HlsVodDolbyVisionSession): WebHlsAudioPreparation {
    if (!session.hasExternalAudioRenditions) return WebHlsAudioPreparation.None
    val rendition =
        session.preferredExternalAudioRendition
            ?: return WebHlsAudioPreparation.Failure("The HLS master has no usable external audio rendition.")
    val playlist =
        rendition.playlist
            ?: return WebHlsAudioPreparation.Failure("The selected external HLS audio rendition has no playlist.")
    val codec =
        rendition.codec
            ?: return WebHlsAudioPreparation.Failure(
                "The selected HLS variant does not declare its external audio codec.",
            )
    val mimeType =
        selectWebAudioMimeType(codec)
            ?: return WebHlsAudioPreparation.Failure(
                "MediaSource does not support the external HLS audio codec '$codec'.",
            )
    if (playlist.segments.any { it.initializationIndex == null }) {
        return WebHlsAudioPreparation.Failure(
            "Browser MediaSource requires fMP4 external audio with EXT-X-MAP; " +
                "elementary audio renditions are unsupported.",
        )
    }
    return WebHlsAudioPreparation.Ready(WebHlsAudioSession(session, rendition, mimeType))
}

private fun rejected(detail: String) =
    VideoPipelineSourcePreparation.Rejected(
        ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
        detail,
    )

private interface WebDolbyVisionSession {
    val initializationSegment: ByteArray
    val segmentCount: Int
    val durationSeconds: Double

    fun segmentStartSeconds(index: Int): Double

    fun segmentEndSeconds(index: Int): Double

    fun segmentIndexForTime(timeSeconds: Double): Int

    suspend fun verifiedSegmentIndexForTime(timeSeconds: Double): Int = segmentIndexForTime(timeSeconds)

    fun segmentDiscontinuity(index: Int): Boolean = false

    suspend fun segment(index: Int): WebMseSegment
}

private data class WebMseSegment(
    val payload: ByteArray,
    val firstPresentationTimeSeconds: Double,
    val startsWithSyncSample: Boolean,
)

private class WebExistingHlsSession(
    private val delegate: HlsVodDolbyVisionSession,
) : WebDolbyVisionSession {
    override val initializationSegment: ByteArray = delegate.initializationSegment
    override val segmentCount: Int = delegate.segments.size
    override val durationSeconds: Double =
        delegate.segments.lastOrNull()?.let { (it.startPresentationTimeUs + it.durationUs) / MICROSECONDS_PER_SECOND }
            ?: 0.0

    override fun segmentStartSeconds(index: Int): Double =
        delegate.segments[index].startPresentationTimeUs / MICROSECONDS_PER_SECOND

    override fun segmentEndSeconds(index: Int): Double =
        (delegate.segments[index].startPresentationTimeUs + delegate.segments[index].durationUs) /
            MICROSECONDS_PER_SECOND

    override fun segmentIndexForTime(timeSeconds: Double): Int =
        delegate.segments
            .indexOfLast { it.startPresentationTimeUs <= timeSeconds * MICROSECONDS_PER_SECOND }
            .coerceAtLeast(0)

    override fun segmentDiscontinuity(index: Int): Boolean = delegate.segments[index].discontinuity

    override suspend fun verifiedSegmentIndexForTime(timeSeconds: Double): Int =
        delegate.verifiedRestartSegmentIndexForSeek(
            (timeSeconds.coerceAtLeast(0.0) * MICROSECONDS_PER_SECOND).toLong(),
        )

    override suspend fun segment(index: Int): WebMseSegment {
        val result = delegate.convertSegmentWithTiming(index)
        return when (val converted = result.first) {
            is HlsVodDolbyVisionSegmentResult.Success -> {
                val timing = requireNotNull(result.second)
                WebMseSegment(
                    payload = converted.payload,
                    firstPresentationTimeSeconds = timing.firstPresentationTimeUs / MICROSECONDS_PER_SECOND,
                    startsWithSyncSample = timing.startsWithSyncSample,
                )
            }
            is HlsVodDolbyVisionSegmentResult.Failure -> error(converted.message)
        }
    }
}

private class WebHlsAudioSession(
    private val delegate: HlsVodDolbyVisionSession,
    rendition: ParsedHlsVodRendition,
    val mimeType: String,
) {
    private val playlist = requireNotNull(rendition.playlist)
    private var cachedInitializationIndex: Int? = null
    private var cachedInitialization: ByteArray? = null
    private val timingConfigurations = mutableMapOf<Int, CmafTrackTimingConfiguration>()

    val segmentCount: Int = playlist.segments.size
    val durationSeconds: Double =
        playlist.segments.lastOrNull()?.let { (it.startPresentationTimeUs + it.durationUs) / MICROSECONDS_PER_SECOND }
            ?: 0.0

    fun segmentStartSeconds(index: Int): Double =
        playlist.segments[index].startPresentationTimeUs / MICROSECONDS_PER_SECOND

    fun segmentIndexForTime(timeSeconds: Double): Int =
        playlist.segments
            .indexOfLast { it.startPresentationTimeUs <= timeSeconds * MICROSECONDS_PER_SECOND }
            .coerceAtLeast(0)

    fun initializationIndex(index: Int): Int = requireNotNull(playlist.segments[index].initializationIndex)

    fun segmentDiscontinuity(index: Int): Boolean = playlist.segments[index].discontinuity

    suspend fun initialization(index: Int): ByteArray {
        if (cachedInitializationIndex == index) return requireNotNull(cachedInitialization)
        val payload = delegate.readPassthroughResource(playlist.maps[index])
        cachedInitializationIndex = index
        cachedInitialization = payload
        return payload
    }

    suspend fun segment(index: Int): WebMseSegment {
        val initializationIndex = initializationIndex(index)
        val configuration =
            timingConfigurations[initializationIndex]
                ?: error("The fMP4 audio timing configuration has not been loaded.")
        val payload = delegate.readPassthroughResource(playlist.segments[index].resource)
        val timing =
            payload.readCmafTrackFragmentTiming(configuration)
                ?: error("The external HLS audio segment has invalid fMP4 timing metadata.")
        return WebMseSegment(
            payload = payload,
            firstPresentationTimeSeconds = timing.firstPresentationTimeUs / MICROSECONDS_PER_SECOND,
            startsWithSyncSample = timing.startsWithSyncSample,
        )
    }

    suspend fun prepareTimingConfiguration(index: Int) {
        if (index in timingConfigurations) return
        val initialization = initialization(index)
        val configuration =
            initialization.readCmafTrackTimingConfiguration(AUDIO_HANDLER_TYPE)
                ?: error("The external HLS initialization segment has no single valid audio track clock.")
        timingConfigurations[index] = configuration
    }
}

private class WebFlatMp4Session(
    private val delegate: FlatMp4DolbyVisionSession,
) : WebDolbyVisionSession {
    override val initializationSegment: ByteArray = delegate.initializationSegment
    override val segmentCount: Int = delegate.fragments.size
    override val durationSeconds: Double =
        delegate.fragments
            .lastOrNull()
            ?.endPresentationTimeUs
            ?.div(MICROSECONDS_PER_SECOND) ?: 0.0

    override fun segmentStartSeconds(index: Int): Double =
        delegate.fragments[index].startPresentationTimeUs / MICROSECONDS_PER_SECOND

    override fun segmentEndSeconds(index: Int): Double =
        delegate.fragments[index].endPresentationTimeUs / MICROSECONDS_PER_SECOND

    override fun segmentIndexForTime(timeSeconds: Double): Int =
        delegate.restartFragmentIndexForSeek((timeSeconds * MICROSECONDS_PER_SECOND).toLong().coerceAtLeast(0L))

    override suspend fun segment(index: Int): WebMseSegment =
        when (val result = delegate.convertFragment(index)) {
            is FlatMp4DolbyVisionFragmentResult.Success ->
                WebMseSegment(
                    payload = result.payload,
                    firstPresentationTimeSeconds = segmentStartSeconds(index),
                    startsWithSyncSample = true,
                )
            is FlatMp4DolbyVisionFragmentResult.Failure -> error(result.message)
        }
}

private class WebMatroskaSession(
    private val delegate: MatroskaDolbyVisionSession,
) : WebDolbyVisionSession {
    override val initializationSegment: ByteArray = delegate.initializationSegment
    override val segmentCount: Int = delegate.fragments.size
    override val durationSeconds: Double =
        delegate.fragments
            .lastOrNull()
            ?.endPresentationTimeUs
            ?.div(MICROSECONDS_PER_SECOND) ?: 0.0

    override fun segmentStartSeconds(index: Int): Double =
        delegate.fragments[index].startPresentationTimeUs / MICROSECONDS_PER_SECOND

    override fun segmentEndSeconds(index: Int): Double =
        delegate.fragments[index].endPresentationTimeUs / MICROSECONDS_PER_SECOND

    override fun segmentIndexForTime(timeSeconds: Double): Int =
        delegate.restartFragmentIndexForSeek((timeSeconds * MICROSECONDS_PER_SECOND).toLong().coerceAtLeast(0L))

    override suspend fun segment(index: Int): WebMseSegment =
        when (val result = delegate.convertFragment(index)) {
            is MatroskaDolbyVisionFragmentResult.Success ->
                WebMseSegment(
                    payload = result.payload,
                    firstPresentationTimeSeconds = segmentStartSeconds(index),
                    startsWithSyncSample = true,
                )
            is MatroskaDolbyVisionFragmentResult.Failure -> error(result.message)
        }
}

private class WebDolbyVisionPreparedSource(
    private val session: WebDolbyVisionSession,
    private val audioSession: WebHlsAudioSession?,
    private val mimeType: String,
    override val outputColorInfo: VideoColorInfo,
    override val detail: String,
) : WebPreparedVideoPipelineSource {
    private val mediaSource = createWebMediaSource()
    private val objectUrl = createWebMediaSourceObjectUrl(mediaSource)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val operationMutex = Mutex()
    private var sourceOpenRegistration: JsAny? = null
    private var videoRegistration: JsAny? = null
    private var sourceBuffer: JsAny? = null
    private var audioSourceBuffer: JsAny? = null
    private var attachedVideo: HTMLVideoElement? = null
    private var onFailure: ((String) -> Unit)? = null
    private var pumpJob: Job? = null
    private var initializationAppended = false
    private var nextSegmentIndex = 0
    private var nextAudioSegmentIndex = 0
    private var lastAudioInitializationIndex: Int? = null
    private var videoTimestampOffset: Double? = null
    private var audioTimestampOffset: Double? = null
    private var playbackTimeSeconds = 0.0
    private var closed = false

    override val uri: String = objectUrl
    override val requestHeaders: Map<String, String> = emptyMap()
    override val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.CONVERTED

    override fun attach(
        videoElement: HTMLVideoElement,
        onFailure: (String) -> Unit,
    ) {
        check(!closed) { "The browser Dolby Vision source is closed." }
        if (attachedVideo === videoElement) {
            this.onFailure = onFailure
            return
        }
        attachedVideo?.let(::detach)
        attachedVideo = videoElement
        this.onFailure = onFailure
        videoRegistration =
            attachWebMseVideoEvents(videoElement) { currentTime, seeking ->
                requestPump(currentTime, seeking)
            }
        sourceOpenRegistration =
            attachWebMediaSourceOpen(mediaSource) {
                initializeMediaSource()
            }
    }

    override fun detach(videoElement: HTMLVideoElement) {
        if (attachedVideo !== videoElement) return
        videoRegistration?.let { detachWebMseVideoEvents(videoElement, it) }
        videoRegistration = null
        sourceOpenRegistration?.let { detachWebMediaSourceOpen(mediaSource, it) }
        sourceOpenRegistration = null
        attachedVideo = null
        onFailure = null
    }

    override fun close() {
        if (closed) return
        closed = true
        attachedVideo?.let(::detach)
        pumpJob?.cancel()
        scope.cancel()
        sourceBuffer?.let(::abortWebSourceBuffer)
        audioSourceBuffer?.let(::abortWebSourceBuffer)
        sourceBuffer = null
        audioSourceBuffer = null
        revokeWebMediaSourceObjectUrl(objectUrl)
    }

    private fun initializeMediaSource() {
        if (closed || sourceBuffer != null) return
        val created = addWebSourceBuffer(mediaSource, mimeType)
        if (created == null) {
            fail("MediaSource rejected the converted Dolby Vision MIME type: $mimeType")
            return
        }
        sourceBuffer = created
        if (audioSession != null) {
            val audioBuffer = addWebSourceBuffer(mediaSource, audioSession.mimeType)
            if (audioBuffer == null) {
                fail("MediaSource rejected the external HLS audio MIME type: ${audioSession.mimeType}")
                return
            }
            audioSourceBuffer = audioBuffer
        }
        setWebMediaSourceDuration(mediaSource, playbackDurationSeconds())
        requestPump(playbackTimeSeconds, seeking = false)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun requestPump(
        currentTime: Double,
        seeking: Boolean,
    ) {
        if (closed || !currentTime.isFinite()) return
        playbackTimeSeconds = currentTime.coerceAtLeast(0.0)
        val activeBuffer = sourceBuffer ?: return
        val activeAudioBuffer = if (audioSession == null) null else audioSourceBuffer ?: return
        if (seeking && !webSourceBufferContains(activeBuffer, playbackTimeSeconds)) {
            nextSegmentIndex = session.segmentIndexForTime(playbackTimeSeconds)
            pumpJob?.cancel()
        }
        if (seeking && activeAudioBuffer != null && !webSourceBufferContains(activeAudioBuffer, playbackTimeSeconds)) {
            nextAudioSegmentIndex = audioSession!!.segmentIndexForTime(playbackTimeSeconds)
            pumpJob?.cancel()
        }
        if (pumpJob?.isActive == true) return
        pumpJob =
            scope.launch {
                try {
                    pump(activeBuffer, activeAudioBuffer, seeking)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    fail(error.message ?: "The browser Dolby Vision MediaSource pipeline failed.")
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pump(
        activeBuffer: JsAny,
        activeAudioBuffer: JsAny?,
        seeking: Boolean,
    ) = operationMutex.withLock {
        if (closed || sourceBuffer !== activeBuffer || audioSourceBuffer !== activeAudioBuffer) return@withLock
        reopenWebMediaSource(mediaSource)
        if (!initializationAppended) {
            appendWebSourceBuffer(mediaSource, activeBuffer, session.initializationSegment)
            initializationAppended = true
        }
        if (seeking && !webSourceBufferContains(activeBuffer, playbackTimeSeconds)) {
            abortWebSourceBuffer(activeBuffer)
            val bounds = webSourceBufferBounds(activeBuffer)
            if (bounds != null && bounds.second > bounds.first) {
                removeWebSourceBuffer(activeBuffer, bounds.first, bounds.second)
            }
            nextSegmentIndex = session.verifiedSegmentIndexForTime(playbackTimeSeconds)
            videoTimestampOffset = null
        }
        if (seeking && activeAudioBuffer != null && !webSourceBufferContains(activeAudioBuffer, playbackTimeSeconds)) {
            clearWebSourceBuffer(activeAudioBuffer)
            nextAudioSegmentIndex = audioSession!!.segmentIndexForTime(playbackTimeSeconds)
            lastAudioInitializationIndex = null
            audioTimestampOffset = null
        }

        val targetEnd = playbackTimeSeconds + BUFFER_AHEAD_SECONDS
        pumpVideo(activeBuffer, targetEnd)
        if (activeAudioBuffer != null) pumpAudio(activeAudioBuffer, targetEnd)

        val removeBefore = playbackTimeSeconds - BUFFER_BEHIND_SECONDS
        trimWebSourceBuffer(activeBuffer, removeBefore)
        activeAudioBuffer?.let { trimWebSourceBuffer(it, removeBefore) }
        setWebMediaSourceDuration(mediaSource, playbackDurationSeconds())
        if (nextSegmentIndex >= session.segmentCount &&
            (audioSession == null || nextAudioSegmentIndex >= audioSession.segmentCount)
        ) {
            endWebMediaSource(mediaSource)
        }
    }

    private suspend fun pumpVideo(
        activeBuffer: JsAny,
        targetEnd: Double,
    ) {
        while (
            nextSegmentIndex < session.segmentCount &&
            session.segmentStartSeconds(nextSegmentIndex) < targetEnd
        ) {
            val index = nextSegmentIndex
            val segment = session.segment(index)
            val mappingRequired = videoTimestampOffset == null || session.segmentDiscontinuity(index)
            if (mappingRequired) {
                if (!segment.startsWithSyncSample) {
                    error("The browser HLS/CMAF restart segment does not begin with a random-access video sample.")
                }
                videoTimestampOffset =
                    setWebTimelineMapping(
                        sourceBuffer = activeBuffer,
                        expectedPresentationTimeSeconds = session.segmentStartSeconds(index),
                        mediaPresentationTimeSeconds = segment.firstPresentationTimeSeconds,
                        trackName = "video",
                    )
            }
            appendWebSourceBuffer(mediaSource, activeBuffer, segment.payload)
            nextSegmentIndex++
        }
    }

    private suspend fun pumpAudio(
        activeAudioBuffer: JsAny,
        targetEnd: Double,
    ) {
        val activeAudio = requireNotNull(audioSession)
        while (
            nextAudioSegmentIndex < activeAudio.segmentCount &&
            activeAudio.segmentStartSeconds(nextAudioSegmentIndex) < targetEnd
        ) {
            val initializationIndex = activeAudio.initializationIndex(nextAudioSegmentIndex)
            if (lastAudioInitializationIndex != initializationIndex) {
                appendWebSourceBuffer(mediaSource, activeAudioBuffer, activeAudio.initialization(initializationIndex))
                activeAudio.prepareTimingConfiguration(initializationIndex)
                lastAudioInitializationIndex = initializationIndex
            }
            val index = nextAudioSegmentIndex
            val segment = activeAudio.segment(index)
            if (audioTimestampOffset == null || activeAudio.segmentDiscontinuity(index)) {
                audioTimestampOffset =
                    setWebTimelineMapping(
                        sourceBuffer = activeAudioBuffer,
                        expectedPresentationTimeSeconds = activeAudio.segmentStartSeconds(index),
                        mediaPresentationTimeSeconds = segment.firstPresentationTimeSeconds,
                        trackName = "audio",
                    )
            }
            appendWebSourceBuffer(mediaSource, activeAudioBuffer, segment.payload)
            nextAudioSegmentIndex++
        }
    }

    private suspend fun clearWebSourceBuffer(buffer: JsAny) {
        abortWebSourceBuffer(buffer)
        val bounds = webSourceBufferBounds(buffer)
        if (bounds != null && bounds.second > bounds.first) {
            removeWebSourceBuffer(buffer, bounds.first, bounds.second)
        }
    }

    private suspend fun trimWebSourceBuffer(
        buffer: JsAny,
        removeBefore: Double,
    ) {
        val bounds = webSourceBufferBounds(buffer)
        if (removeBefore > 0.0 && bounds != null && bounds.first < removeBefore) {
            removeWebSourceBuffer(buffer, bounds.first, minOf(removeBefore, bounds.second))
        }
    }

    private fun playbackDurationSeconds(): Double = maxOf(session.durationSeconds, audioSession?.durationSeconds ?: 0.0)

    private fun setWebTimelineMapping(
        sourceBuffer: JsAny,
        expectedPresentationTimeSeconds: Double,
        mediaPresentationTimeSeconds: Double,
        trackName: String,
    ): Double {
        val offset = expectedPresentationTimeSeconds - mediaPresentationTimeSeconds
        if (!offset.isFinite() || !setWebSourceBufferTimestampOffset(sourceBuffer, offset)) {
            error("MediaSource could not map the $trackName fMP4 timestamps onto the HLS presentation timeline.")
        }
        return offset
    }

    private fun fail(message: String) {
        if (closed) return
        pumpJob?.cancel()
        onFailure?.invoke(message)
    }
}

private suspend fun appendWebSourceBuffer(
    mediaSource: JsAny,
    sourceBuffer: JsAny,
    payload: ByteArray,
) = suspendCancellableCoroutine { continuation ->
    val bytes = payload.toJsNumbers()
    appendWebMseBytes(
        mediaSource = mediaSource,
        sourceBuffer = sourceBuffer,
        bytes = bytes,
        onComplete = { if (continuation.isActive) continuation.resume(Unit) },
        onError = { message ->
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
        },
    )
    continuation.invokeOnCancellation { abortWebSourceBuffer(sourceBuffer) }
}

private suspend fun removeWebSourceBuffer(
    sourceBuffer: JsAny,
    start: Double,
    end: Double,
) = suspendCancellableCoroutine { continuation ->
    removeWebMseRange(
        sourceBuffer = sourceBuffer,
        start = start,
        end = end,
        onComplete = { if (continuation.isActive) continuation.resume(Unit) },
        onError = { message ->
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
        },
    )
    continuation.invokeOnCancellation { abortWebSourceBuffer(sourceBuffer) }
}

private class WebDolbyVisionInput(
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

    override suspend fun size(): Long = httpContentLength(rootUri)

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray = readUri(rootUri, DolbyVisionByteRange(offset, length.toLong()), length)

    override suspend fun read(
        uri: String,
        byteRange: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        require(maximumBytes in 1..MAXIMUM_MEDIA_BYTES) { "The browser media byte limit is outside its range." }
        byteRange?.let { require(it.length <= maximumBytes) { "The browser media range exceeds its limit." } }
        return readUri(uri, byteRange, maximumBytes)
    }

    suspend fun readBounded(
        uri: String,
        maximumBytes: Int,
    ): ByteArray = readUri(uri, null, maximumBytes)

    private suspend fun readUri(
        uri: String,
        range: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray {
        val response = executeWebFetch(uri, "GET", range, requestHeaders, maximumBytes)
        require(response.status in HTTP_SUCCESS) { "HTTP ${response.status} while reading $uri." }
        if (range != null) require(response.status == HTTP_PARTIAL_CONTENT) { "The server ignored a byte range." }
        val expectedLength = range?.length?.toInt()
        if (expectedLength != null) {
            require(response.payload.size == expectedLength) { "The browser range was truncated." }
        }
        require(response.payload.size <= maximumBytes) { "The browser media resource exceeds its byte limit." }
        return response.payload
    }

    private suspend fun httpContentLength(uri: String): Long {
        val head = executeWebFetch(uri, "HEAD", null, requestHeaders, 1)
        if (head.status in HTTP_SUCCESS && head.expectedContentLength >= 0) return head.expectedContentLength
        val ranged = executeWebFetch(uri, "GET", DolbyVisionByteRange(0, 1), requestHeaders, 1)
        return CONTENT_RANGE_TOTAL
            .find(ranged.contentRange)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: error("The browser source does not expose a CORS-readable content length.")
    }
}

private data class WebFetchResponse(
    val status: Int,
    val expectedContentLength: Long,
    val contentRange: String,
    val payload: ByteArray,
)

private suspend fun executeWebFetch(
    uri: String,
    method: String,
    range: DolbyVisionByteRange?,
    requestHeaders: Map<String, String>,
    maximumBytes: Int,
): WebFetchResponse =
    suspendCancellableCoroutine { continuation ->
        require(maximumBytes in 1..MAXIMUM_MEDIA_BYTES) { "The browser fetch byte limit is outside its range." }
        val names = JsArray<JsString>()
        val values = JsArray<JsString>()
        requestHeaders.entries.forEachIndexed { index, entry ->
            names[index] = entry.key.toJsString()
            values[index] = entry.value.toJsString()
        }
        val rangeHeader = range?.let { "bytes=${it.offset}-${it.offset + it.length - 1}" }
        val operation =
            startWebFetch(
                uri = uri,
                method = method,
                headerNames = names,
                headerValues = values,
                rangeHeader = rangeHeader,
                maximumBytes = maximumBytes,
                onReady = { status, expectedLength, contentRange, bytes ->
                    if (!continuation.isActive) return@startWebFetch
                    continuation.resume(
                        WebFetchResponse(
                            status = status.toInt(),
                            expectedContentLength = expectedLength.toDouble().toLong(),
                            contentRange = contentRange,
                            payload = bytes.toByteArray(),
                        ),
                    )
                },
                onError = { message ->
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                },
            )
        continuation.invokeOnCancellation { abortWebFetch(operation) }
    }

private fun selectWebDolbyVisionMimeType(
    initializationSegment: ByteArray,
    level: Int?,
): String? {
    val videoCodec = if (initializationSegment.containsFourCc("dvh1")) "dvh1" else "dvhe"
    val codec = "$videoCodec.08.${(level ?: DEFAULT_DOLBY_VISION_LEVEL).toString().padStart(2, '0')}"
    val audioCodec =
        when {
            initializationSegment.containsFourCc("ec-3") -> "ec-3"
            initializationSegment.containsFourCc("ac-3") -> "ac-3"
            initializationSegment.containsFourCc("Opus") -> "opus"
            initializationSegment.containsFourCc("mp4a") -> "mp4a.40.2"
            else -> null
        }
    val candidates = JsArray<JsString>()
    var index = 0
    if (audioCodec != null) candidates[index++] = "video/mp4; codecs=\"$codec, $audioCodec\"".toJsString()
    candidates[index] = "video/mp4; codecs=\"$codec\"".toJsString()
    return firstSupportedWebMseType(candidates)
}

private fun selectWebAudioMimeType(codec: String): String? {
    val candidates = JsArray<JsString>()
    candidates[0] = "audio/mp4; codecs=\"$codec\"".toJsString()
    return firstSupportedWebMseType(candidates)
}

private fun ByteArray.containsFourCc(value: String): Boolean {
    if (value.length != FOUR_CC_LENGTH || size < FOUR_CC_LENGTH) return false
    val bytes = value.encodeToByteArray()
    for (index in 0..size - FOUR_CC_LENGTH) {
        if (indicesOfFourCcMatch(index, bytes)) return true
    }
    return false
}

@Suppress("MagicNumber")
private fun ByteArray.indicesOfFourCcMatch(
    offset: Int,
    fourCc: ByteArray,
): Boolean =
    this[offset] == fourCc[0] &&
        this[offset + 1] == fourCc[1] &&
        this[offset + 2] == fourCc[2] &&
        this[offset + 3] == fourCc[3]

private fun ByteArray.toJsNumbers(): JsArray<JsNumber> =
    JsArray<JsNumber>().also { array ->
        forEachIndexed { index, byte -> array[index] = (byte.toInt() and UNSIGNED_BYTE_MASK).toJsNumber() }
    }

private fun JsArray<JsNumber>.toByteArray(): ByteArray =
    ByteArray(length) { index -> this[index]?.toInt()?.toByte() ?: 0 }

private fun webSourceBufferBounds(sourceBuffer: JsAny): Pair<Double, Double>? =
    readWebSourceBufferBounds(sourceBuffer)
        .takeIf(String::isNotEmpty)
        ?.split('|', limit = 2)
        ?.takeIf { it.size == 2 }
        ?.let { parts ->
            val start = parts[0].toDoubleOrNull() ?: return@let null
            val end = parts[1].toDoubleOrNull() ?: return@let null
            start to end
        }

private fun detectWebContainer(
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

private const val MAXIMUM_PLAYLIST_BYTES = 8 * 1024 * 1024
private const val MAXIMUM_MEDIA_BYTES = 64 * 1024 * 1024
private const val MICROSECONDS_PER_SECOND = 1_000_000.0
private const val BUFFER_AHEAD_SECONDS = 30.0
private const val BUFFER_BEHIND_SECONDS = 15.0
private const val DEFAULT_DOLBY_VISION_LEVEL = 9
private const val AUDIO_HANDLER_TYPE = "soun"
private const val FOUR_CC_LENGTH = 4
private const val UNSIGNED_BYTE_MASK = 0xff
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private val HTTP_SUCCESS = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX
private val CONTENT_RANGE_TOTAL = Regex("/([0-9]+)$")

private fun webDolbyVisionMseAvailable(): Boolean =
    js(
        """
        typeof MediaSource === "function" && [
            'video/mp4; codecs="dvhe.08.09"',
            'video/mp4; codecs="dvh1.08.09"',
            'video/mp4; codecs="dvhe.08.09, mp4a.40.2"',
            'video/mp4; codecs="dvh1.08.09, mp4a.40.2"'
        ].some(function(type) { return MediaSource.isTypeSupported(type); })
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun firstSupportedWebMseType(candidates: JsArray<JsString>): String? =
    js(
        """
        (function() {
            if (typeof MediaSource !== "function") return null;
            for (const candidate of candidates) {
                if (MediaSource.isTypeSupported(candidate)) return candidate;
            }
            return null;
        })()
        """,
    )

private fun createWebMediaSource(): JsAny = js("new MediaSource()")

@Suppress("UNUSED_PARAMETER")
private fun createWebMediaSourceObjectUrl(mediaSource: JsAny): String = js("URL.createObjectURL(mediaSource)")

@Suppress("UNUSED_PARAMETER")
private fun revokeWebMediaSourceObjectUrl(url: String): Unit = js("URL.revokeObjectURL(url)")

@Suppress("UNUSED_PARAMETER")
private fun attachWebMediaSourceOpen(
    mediaSource: JsAny,
    onOpen: () -> Unit,
): JsAny =
    js(
        """
        (function() {
            const listener = function() { onOpen(); };
            mediaSource.addEventListener("sourceopen", listener);
            if (mediaSource.readyState === "open") queueMicrotask(listener);
            return listener;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun detachWebMediaSourceOpen(
    mediaSource: JsAny,
    registration: JsAny,
): Unit = js("mediaSource.removeEventListener('sourceopen', registration)")

@Suppress("UNUSED_PARAMETER")
private fun addWebSourceBuffer(
    mediaSource: JsAny,
    mimeType: String,
): JsAny? =
    js(
        """
        (function() {
            try {
                if (mediaSource.readyState !== "open") return null;
                return mediaSource.addSourceBuffer(mimeType);
            } catch (_) {
                return null;
            }
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun attachWebMseVideoEvents(
    video: HTMLVideoElement,
    onDemand: (Double, Boolean) -> Unit,
): JsAny =
    js(
        """
        (function() {
            const onTime = function() { onDemand(Number(video.currentTime) || 0, false); };
            const onSeeking = function() { onDemand(Number(video.currentTime) || 0, true); };
            video.addEventListener("timeupdate", onTime);
            video.addEventListener("progress", onTime);
            video.addEventListener("waiting", onTime);
            video.addEventListener("seeking", onSeeking);
            return { onTime: onTime, onSeeking: onSeeking };
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun detachWebMseVideoEvents(
    video: HTMLVideoElement,
    registration: JsAny,
): Unit =
    js(
        """
        (function() {
            video.removeEventListener("timeupdate", registration.onTime);
            video.removeEventListener("progress", registration.onTime);
            video.removeEventListener("waiting", registration.onTime);
            video.removeEventListener("seeking", registration.onSeeking);
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun appendWebMseBytes(
    mediaSource: JsAny,
    sourceBuffer: JsAny,
    bytes: JsArray<JsNumber>,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        (function() {
            const cleanup = function() {
                sourceBuffer.removeEventListener("updateend", complete);
                sourceBuffer.removeEventListener("error", failed);
                sourceBuffer.removeEventListener("abort", failed);
            };
            const complete = function() { cleanup(); onComplete(); };
            const failed = function(event) {
                cleanup();
                onError(event && event.type ? "MediaSource " + event.type : "MediaSource append failed");
            };
            try {
                if (mediaSource.readyState === "ended") mediaSource.duration = mediaSource.duration;
                sourceBuffer.addEventListener("updateend", complete, { once: true });
                sourceBuffer.addEventListener("error", failed, { once: true });
                sourceBuffer.addEventListener("abort", failed, { once: true });
                sourceBuffer.appendBuffer(Uint8Array.from(bytes));
            } catch (error) {
                cleanup();
                onError(error && error.message ? String(error.message) : String(error));
            }
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun removeWebMseRange(
    sourceBuffer: JsAny,
    start: Double,
    end: Double,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        (function() {
            if (!(end > start)) { onComplete(); return; }
            const cleanup = function() {
                sourceBuffer.removeEventListener("updateend", complete);
                sourceBuffer.removeEventListener("error", failed);
                sourceBuffer.removeEventListener("abort", failed);
            };
            const complete = function() { cleanup(); onComplete(); };
            const failed = function(event) {
                cleanup();
                onError(event && event.type ? "MediaSource " + event.type : "MediaSource removal failed");
            };
            try {
                sourceBuffer.addEventListener("updateend", complete, { once: true });
                sourceBuffer.addEventListener("error", failed, { once: true });
                sourceBuffer.addEventListener("abort", failed, { once: true });
                sourceBuffer.remove(start, end);
            } catch (error) {
                cleanup();
                onError(error && error.message ? String(error.message) : String(error));
            }
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun abortWebSourceBuffer(sourceBuffer: JsAny): Unit =
    js("(function() { try { if (sourceBuffer.updating) sourceBuffer.abort(); } catch (_) {} })()")

@Suppress("UNUSED_PARAMETER")
private fun setWebSourceBufferTimestampOffset(
    sourceBuffer: JsAny,
    offsetSeconds: Double,
): Boolean =
    js(
        """
        (function() {
            try {
                if (sourceBuffer.updating || !Number.isFinite(offsetSeconds)) return false;
                sourceBuffer.timestampOffset = offsetSeconds;
                return Math.abs(Number(sourceBuffer.timestampOffset) - offsetSeconds) < 0.000001;
            } catch (_) {
                return false;
            }
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun webSourceBufferContains(
    sourceBuffer: JsAny,
    timeSeconds: Double,
): Boolean =
    js(
        """
        (function() {
            const ranges = sourceBuffer.buffered;
            for (let index = 0; index < ranges.length; index++) {
                if (timeSeconds >= ranges.start(index) && timeSeconds <= ranges.end(index)) return true;
            }
            return false;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun readWebSourceBufferBounds(sourceBuffer: JsAny): String =
    js(
        """
        (function() {
            const ranges = sourceBuffer.buffered;
            if (!ranges || ranges.length === 0) return "";
            return String(ranges.start(0)) + "|" + String(ranges.end(ranges.length - 1));
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun setWebMediaSourceDuration(
    mediaSource: JsAny,
    durationSeconds: Double,
): Unit =
    js(
        """
        (function() {
            try {
                if (durationSeconds > 0 && Number.isFinite(durationSeconds) && mediaSource.readyState === "open") {
                    mediaSource.duration = durationSeconds;
                }
            } catch (_) {}
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun reopenWebMediaSource(mediaSource: JsAny): Unit =
    js(
        """
        (function() {
            try {
                if (mediaSource.readyState === "ended") mediaSource.duration = mediaSource.duration;
            } catch (_) {}
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun endWebMediaSource(mediaSource: JsAny): Unit =
    js(
        """
        (function() {
            try {
                if (mediaSource.readyState === "open" &&
                    Array.from(mediaSource.sourceBuffers).every(function(buffer) { return !buffer.updating; })) {
                    mediaSource.endOfStream();
                }
            } catch (_) {}
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun startWebFetch(
    uri: String,
    method: String,
    headerNames: JsArray<JsString>,
    headerValues: JsArray<JsString>,
    rangeHeader: String?,
    maximumBytes: Int,
    onReady: (JsNumber, JsNumber, String, JsArray<JsNumber>) -> Unit,
    onError: (String) -> Unit,
): JsAny =
    js(
        """
        (function() {
            const controller = new AbortController();
            const headers = new Headers();
            for (let index = 0; index < headerNames.length; index++) {
                headers.set(headerNames[index], headerValues[index]);
            }
            if (rangeHeader != null) headers.set("Range", rangeHeader);
            fetch(uri, {
                method: method,
                headers: headers,
                credentials: "same-origin",
                signal: controller.signal
            }).then(async function(response) {
                const contentLength = response.headers.get("Content-Length");
                const parsedLength = contentLength == null ? -1 : Number(contentLength);
                const expected = Number.isFinite(parsedLength) && parsedLength >= 0 ? parsedLength : -1;
                const contentRange = response.headers.get("Content-Range") || "";
                if (expected > maximumBytes) {
                    if (response.body) await response.body.cancel("byte limit exceeded");
                    throw new Error("The browser media resource exceeds its byte limit.");
                }
                if (method === "HEAD" || !response.body) {
                    onReady(response.status, expected, contentRange, []);
                    return;
                }
                const reader = response.body.getReader();
                const chunks = [];
                let total = 0;
                while (true) {
                    const result = await reader.read();
                    if (result.done) break;
                    const chunk = result.value;
                    if (total + chunk.byteLength > maximumBytes) {
                        await reader.cancel("byte limit exceeded");
                        throw new Error("The browser media resource exceeds its byte limit.");
                    }
                    chunks.push(chunk);
                    total += chunk.byteLength;
                }
                const bytes = new Uint8Array(total);
                let offset = 0;
                for (const chunk of chunks) {
                    bytes.set(chunk, offset);
                    offset += chunk.byteLength;
                }
                onReady(
                    response.status,
                    expected,
                    contentRange,
                    Array.from(bytes)
                );
            }).catch(function(error) {
                if (error && error.name === "AbortError") return;
                onError(error && error.message ? String(error.message) : String(error));
            });
            return controller;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun abortWebFetch(operation: JsAny): Unit = js("operation.abort()")
