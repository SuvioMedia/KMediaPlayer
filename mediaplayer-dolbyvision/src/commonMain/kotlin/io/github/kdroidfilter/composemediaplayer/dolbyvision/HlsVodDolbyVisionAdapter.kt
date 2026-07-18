@file:Suppress("LoopWithTooManyJumpStatements", "MaxLineLength")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DolbyVisionByteRange(
    val offset: Long,
    val length: Long,
) {
    init {
        require(offset >= 0) { "offset must be non-negative." }
        require(length > 0) { "length must be positive." }
        require(offset <= Long.MAX_VALUE - length) { "The byte range end overflows." }
    }
}

/**
 * Transport supplied by the host application (HTTP, file, asset, or browser fetch).
 *
 * Implementations must reject a response before allocating or returning more than [maximumBytes].
 * A byte-range response must additionally contain exactly the requested range.
 */
fun interface DolbyVisionMediaDataSource {
    suspend fun read(
        uri: String,
        byteRange: DolbyVisionByteRange?,
        maximumBytes: Int,
    ): ByteArray
}

enum class HlsVodDolbyVisionFailureReason {
    INVALID_PLAYLIST,
    LIVE_SOURCE,
    DRM_PROTECTED,
    UNSUPPORTED_VARIANT,
    RESOURCE_IO,
    CONVERSION_FAILED,
}

internal fun HlsVodDolbyVisionFailureReason.toColorPipelineFallbackReason(): ColorPipelineFallbackReason =
    when (this) {
        HlsVodDolbyVisionFailureReason.LIVE_SOURCE -> ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED
        HlsVodDolbyVisionFailureReason.DRM_PROTECTED -> ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED
        else -> ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE
    }

data class HlsVodDolbyVisionSegment(
    val index: Int,
    val sourceUri: String,
    val byteRange: DolbyVisionByteRange?,
    val startPresentationTimeUs: Long,
    val durationUs: Long,
    val discontinuity: Boolean,
)

sealed interface HlsVodDolbyVisionOpenResult {
    data class Success(
        val session: HlsVodDolbyVisionSession,
    ) : HlsVodDolbyVisionOpenResult

    data class Failure(
        val message: String,
        val reason: HlsVodDolbyVisionFailureReason = HlsVodDolbyVisionFailureReason.CONVERSION_FAILED,
    ) : HlsVodDolbyVisionOpenResult
}

sealed interface HlsVodDolbyVisionSegmentResult {
    data class Success(
        val payload: ByteArray,
        val segment: HlsVodDolbyVisionSegment,
    ) : HlsVodDolbyVisionSegmentResult

    data class Failure(
        val message: String,
    ) : HlsVodDolbyVisionSegmentResult
}

/** Opens only complete, unencrypted fMP4 HLS VOD playlists. MPEG-TS, live and DRM fail closed. */
object HlsVodDolbyVisionAdapter {
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    suspend fun open(
        playlistUri: String,
        playlist: String,
        dataSource: DolbyVisionMediaDataSource,
        converter: DolbyVisionRpuConverter,
        enhancementLayer: DolbyVisionEnhancementLayer,
        maximumBufferedFragments: Int = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_FRAGMENTS,
        maximumBufferedBytes: Long = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_BYTES,
        maximumSegmentBytes: Int = DEFAULT_MAXIMUM_HLS_SEGMENT_BYTES,
    ): HlsVodDolbyVisionOpenResult {
        if (maximumSegmentBytes !in 1..DEFAULT_MAXIMUM_HLS_RESOURCE_BYTES) {
            return HlsVodDolbyVisionOpenResult.Failure(
                "maximumSegmentBytes must be between 1 and $DEFAULT_MAXIMUM_HLS_RESOURCE_BYTES.",
                HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
            )
        }
        val resolved =
            when (val result = resolveHlsVodPlaylist(playlistUri, playlist, dataSource)) {
                is HlsVodPlaylistResolution.Success -> result
                is HlsVodPlaylistResolution.Failure ->
                    return HlsVodDolbyVisionOpenResult.Failure(result.message, result.reason)
            }
        val parsed =
            when (val result = parseHlsVodPlaylist(resolved.playlistUri, resolved.playlist)) {
                is HlsPlaylistParseResult.Success -> result.value
                is HlsPlaylistParseResult.Failure ->
                    return HlsVodDolbyVisionOpenResult.Failure(result.message, result.reason)
            }
        val initializationPayload =
            try {
                dataSource.readHlsBounded(
                    parsed.initializationUri,
                    parsed.initializationByteRange,
                    maximumSegmentBytes,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return HlsVodDolbyVisionOpenResult.Failure(
                    "Unable to read the HLS initialization segment: ${error.message ?: error::class.simpleName}.",
                    HlsVodDolbyVisionFailureReason.RESOURCE_IO,
                )
            }
        val prepared =
            when (val result = CmafDolbyVisionInitializationSegment.prepareProfile81(initializationPayload)) {
                is CmafDolbyVisionInitializationResult.Success -> result.configuration
                is CmafDolbyVisionInitializationResult.Failure ->
                    return HlsVodDolbyVisionOpenResult.Failure(
                        result.message,
                        HlsVodDolbyVisionFailureReason.CONVERSION_FAILED,
                    )
            }
        val request =
            try {
                DolbyVisionConversionRequest(
                    container = DolbyVisionContainer.HLS_VOD,
                    profile = prepared.sourceProfile,
                    hasRpu = true,
                    enhancementLayer = enhancementLayer,
                    maximumBufferedFragments = maximumBufferedFragments,
                    maximumBufferedBytes = maximumBufferedBytes,
                )
            } catch (error: IllegalArgumentException) {
                return HlsVodDolbyVisionOpenResult.Failure(
                    error.message ?: "Invalid HLS buffer limits.",
                    HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
                )
            }
        return HlsVodDolbyVisionOpenResult.Success(
            HlsVodDolbyVisionSession(
                parsed = parsed,
                configuration = prepared,
                dataSource = dataSource,
                converter = converter,
                request = request,
                maximumSegmentBytes = maximumSegmentBytes,
                master = resolved.master,
            ),
        )
    }
}

class HlsVodDolbyVisionSession internal constructor(
    private val parsed: ParsedHlsVodPlaylist,
    private val configuration: CmafDolbyVisionTrackConfiguration,
    private val dataSource: DolbyVisionMediaDataSource,
    converter: DolbyVisionRpuConverter,
    request: DolbyVisionConversionRequest,
    private val maximumSegmentBytes: Int,
    private val master: ParsedHlsVodMaster?,
) {
    val segments: List<HlsVodDolbyVisionSegment> = parsed.segments
    val initializationSegment: ByteArray = configuration.rewrittenInitializationSegment.copyOf()

    private val demuxer = CmafDolbyVisionFragmentAdapter(configuration, maximumSegmentBytes)
    private val bridge =
        DolbyVisionStreamingBridge(
            request = request,
            converter = converter,
            remuxer = CmafDolbyVisionFragmentRemuxer(configuration, maximumSegmentBytes),
        )
    private val mutex = Mutex()
    private var lastConvertedIndex: Int? = null

    val usesMasterPlaylist: Boolean get() = master != null
    val hasExternalAudioRenditions: Boolean get() = master?.hasExternalAudioRenditions == true

    internal val preferredExternalAudioRendition: ParsedHlsVodRendition?
        get() = master?.preferredExternalAudioRendition

    internal suspend fun readPassthroughResource(resource: HlsVodPassthroughResource): ByteArray =
        dataSource.readHlsBounded(resource.uri, resource.byteRange, maximumSegmentBytes)

    suspend fun convertSegment(index: Int): HlsVodDolbyVisionSegmentResult = convertSegmentWithTiming(index).first

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun convertSegmentWithTiming(
        index: Int,
    ): Pair<HlsVodDolbyVisionSegmentResult, CmafTrackFragmentTiming?> =
        mutex.withLock {
            val segment =
                segments.getOrNull(index)
                    ?: return@withLock HlsVodDolbyVisionSegmentResult.Failure(
                        "HLS segment index $index is out of range.",
                    ) to null
            if (lastConvertedIndex?.plus(1) != index || segment.discontinuity) {
                demuxer.reset()
                bridge.reset()
            }
            val payload =
                try {
                    dataSource.readHlsBounded(segment.sourceUri, segment.byteRange, maximumSegmentBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock HlsVodDolbyVisionSegmentResult.Failure(
                        "Unable to read HLS segment $index: ${error.message ?: error::class.simpleName}.",
                    ) to null
                }
            if (payload.size > maximumSegmentBytes) {
                return@withLock HlsVodDolbyVisionSegmentResult.Failure(
                    "HLS segment $index exceeds the byte limit.",
                ) to null
            }
            val demuxed =
                when (val result = demuxer.demux(payload)) {
                    is CmafDolbyVisionDemuxResult.Success -> result.fragment
                    is CmafDolbyVisionDemuxResult.Failure ->
                        return@withLock HlsVodDolbyVisionSegmentResult.Failure(result.message) to null
                }
            val timing =
                demuxer.lastFragmentTiming
                    ?: return@withLock HlsVodDolbyVisionSegmentResult.Failure(
                        "The CMAF video fragment has no usable timestamp/keyframe information.",
                    ) to null
            val converted =
                when (val result = bridge.convert(demuxed)) {
                    is DolbyVisionFragmentConversionResult.Success -> result.value.fragment.payload
                    is DolbyVisionFragmentConversionResult.Failure ->
                        return@withLock HlsVodDolbyVisionSegmentResult.Failure(result.message) to null
                }
            lastConvertedIndex = index
            HlsVodDolbyVisionSegmentResult.Success(converted, segment) to timing
        }

    /** Playlist-time candidate; call [resetForSeek] when a verified random-access restart is required. */
    fun restartSegmentIndexForSeek(targetPresentationTimeUs: Long): Int {
        require(targetPresentationTimeUs >= 0) { "targetPresentationTimeUs must be non-negative." }
        return segments.indexOfLast { it.startPresentationTimeUs <= targetPresentationTimeUs }.coerceAtLeast(0)
    }

    suspend fun resetForSeek(targetPresentationTimeUs: Long): Int {
        val index = verifiedRestartSegmentIndexForSeek(targetPresentationTimeUs)
        return mutex.withLock {
            demuxer.reset()
            bridge.reset()
            lastConvertedIndex = null
            index
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun verifiedRestartSegmentIndexForSeek(targetPresentationTimeUs: Long): Int {
        val candidate = restartSegmentIndexForSeek(targetPresentationTimeUs)
        if (parsed.independentSegments || master?.independentSegments == true) return candidate
        val discontinuityStart =
            (candidate downTo 0).firstOrNull { segments[it].discontinuity } ?: 0
        val scanStart = maxOf(discontinuityStart, candidate - MAXIMUM_HLS_KEYFRAME_SCAN_SEGMENTS + 1)
        for (index in candidate downTo scanStart) {
            if (inspectSegmentForSeek(index).startsWithSyncSample) return index
        }
        val boundary =
            if (scanStart == discontinuityStart) {
                "the current discontinuity boundary"
            } else {
                "the bounded $MAXIMUM_HLS_KEYFRAME_SCAN_SEGMENTS-segment scan window"
            }
        error("No random-access CMAF segment was found before the seek target within $boundary.")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun inspectSegmentForSeek(index: Int): CmafTrackFragmentTiming {
        val segment = segments[index]
        val payload =
            try {
                dataSource.readHlsBounded(segment.sourceUri, segment.byteRange, maximumSegmentBytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "Unable to inspect HLS segment $index for a seek keyframe: " +
                        "${error.message ?: error::class.simpleName}.",
                    error,
                )
            }
        val probe = CmafDolbyVisionFragmentAdapter(configuration, maximumSegmentBytes)
        when (val result = probe.demux(payload)) {
            is CmafDolbyVisionDemuxResult.Failure ->
                error(
                    "Unable to inspect HLS segment $index for seek: ${result.message}",
                )
            is CmafDolbyVisionDemuxResult.Success -> Unit
        }
        return probe.lastFragmentTiming ?: error("HLS segment $index has no usable timestamp/keyframe information.")
    }

    /** Rewrites only media resource lines; all unrelated HLS tags remain byte-for-byte equivalent text. */
    fun renderMediaPlaylist(resourcePrefix: String): String {
        resourcePrefix.requireValidHlsResourcePrefix()
        val removedLines = parsed.byteRangeLineIndices
        return buildString {
            parsed.originalLines.forEachIndexed { lineIndex, line ->
                if (lineIndex in removedLines) return@forEachIndexed
                val replacement =
                    when (lineIndex) {
                        in parsed.initializationMapLineIndices ->
                            "#EXT-X-MAP:URI=\"${resourcePrefix.trimEnd('/')}/init.mp4\""
                        else -> {
                            val segmentIndex = parsed.segmentLineToIndex[lineIndex]
                            if (segmentIndex ==
                                null
                            ) {
                                line
                            } else {
                                "${resourcePrefix.trimEnd('/')}/segment/$segmentIndex.m4s"
                            }
                        }
                    }
                append(replacement)
                append('\n')
            }
        }
    }

    /** Returns a filtered master when one was supplied, otherwise the converted media playlist. */
    fun renderEntryPlaylist(resourcePrefix: String): String {
        resourcePrefix.requireValidHlsResourcePrefix()
        return master?.render(resourcePrefix) ?: renderMediaPlaylist(resourcePrefix)
    }

    internal suspend fun readMasterResource(
        path: String,
        resourcePrefix: String,
    ): HlsVodBridgeResource? {
        val activeMaster = master ?: return null
        resourcePrefix.requireValidHlsResourcePrefix()
        val prefix = resourcePrefix.trimEnd('/')
        return when {
            path == "video.m3u8" ->
                HlsVodBridgeResource(
                    renderMediaPlaylist("$prefix/video").encodeToByteArray(),
                    DOVI_HLS_PLAYLIST_CONTENT_TYPE,
                )
            path == "video/init.mp4" -> HlsVodBridgeResource(initializationSegment, DOVI_MP4_CONTENT_TYPE)
            MASTER_VIDEO_SEGMENT_PATH.matches(path) -> {
                val index = MASTER_VIDEO_SEGMENT_PATH.matchEntire(path)!!.groupValues[1].toIntOrNull() ?: return null
                when (val converted = convertSegment(index)) {
                    is HlsVodDolbyVisionSegmentResult.Success ->
                        HlsVodBridgeResource(converted.payload, DOVI_MP4_CONTENT_TYPE)
                    is HlsVodDolbyVisionSegmentResult.Failure -> error(converted.message)
                }
            }
            else ->
                readHlsMasterResource(
                    master = activeMaster,
                    path = path,
                    resourcePrefix = prefix,
                    dataSource = dataSource,
                    maximumResourceBytes = maximumSegmentBytes,
                )
        }
    }
}

private sealed interface HlsPlaylistParseResult {
    data class Success(
        val value: ParsedHlsVodPlaylist,
    ) : HlsPlaylistParseResult

    data class Failure(
        val message: String,
        val reason: HlsVodDolbyVisionFailureReason,
    ) : HlsPlaylistParseResult
}

internal data class ParsedHlsVodPlaylist(
    val initializationUri: String,
    val initializationByteRange: DolbyVisionByteRange?,
    val initializationMapLineIndices: Set<Int>,
    val segments: List<HlsVodDolbyVisionSegment>,
    val originalLines: List<String>,
    val segmentLineToIndex: Map<Int, Int>,
    val byteRangeLineIndices: Set<Int>,
    val independentSegments: Boolean,
)

private data class HlsMap(
    val uri: String,
    val range: DolbyVisionByteRange?,
)

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private fun parseHlsVodPlaylist(
    playlistUri: String,
    text: String,
): HlsPlaylistParseResult {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    if (lines.firstOrNull()?.trim() != HLS_HEADER) return hlsFailure("The HLS playlist has no #EXTM3U header.")
    if (lines.none { it.trim() == HLS_ENDLIST }) {
        return hlsFailure(
            "Live HLS is not converted; #EXT-X-ENDLIST is required.",
            HlsVodDolbyVisionFailureReason.LIVE_SOURCE,
        )
    }
    if (lines.any { it.trim().startsWith(HLS_STREAM_INF) }) {
        return hlsFailure("A media playlist must be selected before Dolby Vision HLS conversion.")
    }
    if (lines.any { it.trim().startsWith(HLS_PART) || it.trim().startsWith(HLS_PRELOAD_HINT) }) {
        return hlsFailure(
            "Low-latency/live HLS parts are not converted.",
            HlsVodDolbyVisionFailureReason.LIVE_SOURCE,
        )
    }

    var mediaSequence = 0L
    var activeMap: HlsMap? = null
    var pendingDurationUs: Long? = null
    var pendingByteRange: Pair<Long, Long?>? = null
    var pendingByteRangeLine: Int? = null
    var nextImplicitByteOffset = 0L
    var previousByteRangeUri: String? = null
    var currentStartUs = 0L
    var discontinuity = false
    val segments = mutableListOf<HlsVodDolbyVisionSegment>()
    val segmentLines = mutableMapOf<Int, Int>()
    val removedByteRangeLines = mutableSetOf<Int>()
    val initializationMapLines = mutableSetOf<Int>()

    lines.forEachIndexed { lineIndex, rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith(HLS_MEDIA_SEQUENCE) -> {
                mediaSequence = line.substringAfter(':').toLongOrNull()
                    ?: return hlsFailure("Invalid EXT-X-MEDIA-SEQUENCE.")
            }
            line.startsWith(HLS_KEY) || line.startsWith(HLS_SESSION_KEY) -> {
                val attributes =
                    parseHlsAttributeList(line.substringAfter(':'))
                        ?: return hlsFailure("An HLS key tag has an invalid attribute list.")
                val method = attributes["METHOD"]?.uppercase()
                if (method != "NONE") {
                    return hlsFailure(
                        "Encrypted/DRM HLS is not converted.",
                        HlsVodDolbyVisionFailureReason.DRM_PROTECTED,
                    )
                }
            }
            line.startsWith(HLS_MAP) -> {
                val attributes =
                    parseHlsAttributeList(line.substringAfter(':'))
                        ?: return hlsFailure("EXT-X-MAP has an invalid attribute list.")
                val uri = attributes["URI"] ?: return hlsFailure("EXT-X-MAP has no URI.")
                if (!line.hasQuotedHlsAttribute("URI")) return hlsFailure("EXT-X-MAP URI must be quoted.")
                val range =
                    attributes["BYTERANGE"]?.let(::parseExplicitByteRange)
                        ?: if ("BYTERANGE" in attributes) return hlsFailure("Invalid EXT-X-MAP BYTERANGE.") else null
                val candidate = HlsMap(resolveHlsUri(playlistUri, uri), range)
                if (activeMap != null && activeMap != candidate) {
                    return hlsFailure(
                        "Multiple changing EXT-X-MAP resources are not supported in one conversion session.",
                    )
                }
                activeMap = candidate
                initializationMapLines += lineIndex
            }
            line.startsWith(HLS_EXTINF) -> {
                val seconds =
                    line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: return hlsFailure("Invalid EXTINF duration.")
                if (!seconds.isFinite() || seconds <= 0.0 || seconds > MAXIMUM_HLS_SEGMENT_DURATION_SECONDS) {
                    return hlsFailure("EXTINF duration is outside the supported range.")
                }
                pendingDurationUs = (seconds * MICROSECONDS_PER_SECOND).toLong().coerceAtLeast(1L)
            }
            line.startsWith(HLS_BYTERANGE) -> {
                val parsed =
                    parseImplicitByteRange(line.substringAfter(':'))
                        ?: return hlsFailure("Invalid EXT-X-BYTERANGE.")
                pendingByteRange = parsed
                pendingByteRangeLine = lineIndex
            }
            line == HLS_DISCONTINUITY -> discontinuity = true
            line.isNotEmpty() && !line.startsWith('#') -> {
                val duration = pendingDurationUs ?: return hlsFailure("A media URI has no preceding EXTINF.")
                val resolvedUri = resolveHlsUri(playlistUri, line)
                val (length, explicitOffset) = pendingByteRange ?: (0L to null)
                val range =
                    if (length > 0) {
                        val offset =
                            explicitOffset ?: if (previousByteRangeUri == resolvedUri) {
                                nextImplicitByteOffset
                            } else {
                                return hlsFailure("An implicit HLS byte range changed resource URI.")
                            }
                        DolbyVisionByteRange(offset, length).also { nextImplicitByteOffset = offset + length }
                    } else {
                        nextImplicitByteOffset = 0L
                        null
                    }
                val segmentIndex = segments.size
                segments +=
                    HlsVodDolbyVisionSegment(
                        index = segmentIndex,
                        sourceUri = resolvedUri,
                        byteRange = range,
                        startPresentationTimeUs = currentStartUs,
                        durationUs = duration,
                        discontinuity = discontinuity,
                    )
                segmentLines[lineIndex] = segmentIndex
                pendingByteRangeLine?.let(removedByteRangeLines::add)
                currentStartUs += duration
                pendingDurationUs = null
                pendingByteRange = null
                pendingByteRangeLine = null
                discontinuity = false
                previousByteRangeUri = if (range == null) null else resolvedUri
            }
        }
    }
    if (mediaSequence < 0) return hlsFailure("EXT-X-MEDIA-SEQUENCE must be non-negative.")
    val map = activeMap ?: return hlsFailure("Only fMP4 HLS with EXT-X-MAP is supported; MPEG-TS is not converted.")
    if (segments.isEmpty()) return hlsFailure("The HLS VOD playlist contains no media segments.")
    if (pendingDurationUs != null ||
        pendingByteRange != null
    ) {
        return hlsFailure("The HLS playlist ends with an incomplete segment declaration.")
    }
    return HlsPlaylistParseResult.Success(
        ParsedHlsVodPlaylist(
            initializationUri = map.uri,
            initializationByteRange = map.range,
            initializationMapLineIndices = initializationMapLines,
            segments = segments,
            originalLines = lines,
            segmentLineToIndex = segmentLines,
            byteRangeLineIndices = removedByteRangeLines,
            independentSegments = lines.any { it.trim() == HLS_INDEPENDENT_SEGMENTS },
        ),
    )
}

private fun parseExplicitByteRange(value: String): DolbyVisionByteRange? {
    val parts = value.trim().trim('"').split('@', limit = 2)
    if (parts.size != 2) return null
    val length = parts[0].toLongOrNull() ?: return null
    val offset = parts[1].toLongOrNull() ?: return null
    return runCatching { DolbyVisionByteRange(offset, length) }.getOrNull()
}

private fun parseImplicitByteRange(value: String): Pair<Long, Long?>? {
    val parts = value.trim().split('@', limit = 2)
    val length = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val offset = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
    if (parts.size == 2 && offset == null) return null
    return length to offset
}

internal fun parseHlsAttributeList(value: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    var cursor = value.skipHlsWhitespace(0)
    while (cursor < value.length) {
        val name = value.parseHlsAttributeName(cursor) ?: return null
        if (name.value in result) return null
        val attributeValue = value.parseHlsAttributeValue(name.nextIndex) ?: return null
        result[name.value] = attributeValue.value
        cursor = value.skipHlsWhitespace(attributeValue.nextIndex)
        if (cursor == value.length) break
        if (value[cursor] != ',') return null
        cursor = value.skipHlsWhitespace(cursor + 1)
        if (cursor == value.length) return null
    }
    return result
}

private data class ParsedHlsAttributePart(
    val value: String,
    val nextIndex: Int,
)

private fun String.parseHlsAttributeName(startIndex: Int): ParsedHlsAttributePart? {
    val equals = indexOf('=', startIndex)
    if (equals < 0 || indexOf(',', startIndex).let { it in startIndex until equals }) return null
    val name = substring(startIndex, equals).trim().uppercase()
    if (!HLS_ATTRIBUTE_NAME.matches(name)) return null
    return ParsedHlsAttributePart(name, equals + 1)
}

private fun String.parseHlsAttributeValue(startIndex: Int): ParsedHlsAttributePart? =
    if (getOrNull(startIndex) == '"') {
        parseHlsQuotedAttributeValue(startIndex)
    } else {
        parseHlsUnquotedAttributeValue(startIndex)
    }

private fun String.parseHlsQuotedAttributeValue(startIndex: Int): ParsedHlsAttributePart? {
    val endQuote = indexOf('"', startIndex + 1)
    if (endQuote < 0) return null
    return validatedHlsAttributePart(substring(startIndex + 1, endQuote), endQuote + 1)
}

private fun String.parseHlsUnquotedAttributeValue(startIndex: Int): ParsedHlsAttributePart? {
    val comma = indexOf(',', startIndex).let { if (it < 0) length else it }
    return validatedHlsAttributePart(substring(startIndex, comma).trim(), comma)
}

private fun validatedHlsAttributePart(
    value: String,
    nextIndex: Int,
): ParsedHlsAttributePart? =
    value.takeIf { it.isNotEmpty() && '\r' !in it && '\n' !in it }?.let {
        ParsedHlsAttributePart(it, nextIndex)
    }

private fun String.skipHlsWhitespace(startIndex: Int): Int {
    var index = startIndex
    while (index < length && this[index].isWhitespace()) index++
    return index
}

internal fun String.hasQuotedHlsAttribute(name: String): Boolean =
    Regex("(?i)(?:^|,)\\s*${Regex.escape(name)}\\s*=\\s*\"[^\"\\r\\n]*\"")
        .containsMatchIn(substringAfter(':'))

internal fun resolveHlsUri(
    baseUri: String,
    reference: String,
): String {
    val trimmed = reference.trim()
    if (URI_SCHEME.matches(trimmed)) return trimmed
    val baseWithoutFragment = baseUri.substringBefore('#')
    val scheme = baseWithoutFragment.substringBefore(':', missingDelimiterValue = "")
    if (trimmed.startsWith("//") && scheme.isNotEmpty()) return "$scheme:$trimmed"
    val originEnd =
        baseWithoutFragment.indexOf(
            '/',
            startIndex =
                baseWithoutFragment.indexOf("://").let {
                    if (it <
                        0
                    ) {
                        0
                    } else {
                        it + 3
                    }
                },
        )
    val origin = if (originEnd < 0) baseWithoutFragment else baseWithoutFragment.substring(0, originEnd)
    val rawPath =
        if (trimmed.startsWith('/')) {
            trimmed
        } else {
            val directory =
                baseWithoutFragment
                    .substringBefore(
                        '?',
                    ).substringBeforeLast('/', missingDelimiterValue = "")
            if (directory.isEmpty()) trimmed else "$directory/$trimmed"
        }
    val prefix = if (rawPath.startsWith(origin) && origin.isNotEmpty()) origin else ""
    val path = rawPath.removePrefix(prefix)
    val normalized = mutableListOf<String>()
    path.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
            else -> normalized += part
        }
    }
    return prefix + "/" + normalized.joinToString("/")
}

private fun hlsFailure(
    message: String,
    reason: HlsVodDolbyVisionFailureReason = HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
) = HlsPlaylistParseResult.Failure(message, reason)

private fun String.requireValidHlsResourcePrefix() {
    require(isNotBlank() && '"' !in this && '\n' !in this && '\r' !in this) {
        "resourcePrefix must be a non-empty URI prefix without quotes or line breaks."
    }
}

private const val DEFAULT_MAXIMUM_HLS_SEGMENT_BYTES = 64 * 1024 * 1024
private const val MICROSECONDS_PER_SECOND = 1_000_000.0
private const val MAXIMUM_HLS_SEGMENT_DURATION_SECONDS = 86_400.0
private const val HLS_HEADER = "#EXTM3U"
private const val HLS_ENDLIST = "#EXT-X-ENDLIST"
private const val HLS_STREAM_INF = "#EXT-X-STREAM-INF:"
private const val HLS_PART = "#EXT-X-PART:"
private const val HLS_PRELOAD_HINT = "#EXT-X-PRELOAD-HINT:"
private const val HLS_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
private const val HLS_KEY = "#EXT-X-KEY:"
private const val HLS_SESSION_KEY = "#EXT-X-SESSION-KEY:"
private const val HLS_MAP = "#EXT-X-MAP:"
private const val HLS_EXTINF = "#EXTINF:"
private const val HLS_BYTERANGE = "#EXT-X-BYTERANGE:"
private const val HLS_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
private const val HLS_INDEPENDENT_SEGMENTS = "#EXT-X-INDEPENDENT-SEGMENTS"
private const val MAXIMUM_HLS_KEYFRAME_SCAN_SEGMENTS = 32
private val MASTER_VIDEO_SEGMENT_PATH = Regex("^video/segment/([0-9]+)\\.m4s$")
private val HLS_ATTRIBUTE_NAME = Regex("[A-Z0-9-]+")
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")
