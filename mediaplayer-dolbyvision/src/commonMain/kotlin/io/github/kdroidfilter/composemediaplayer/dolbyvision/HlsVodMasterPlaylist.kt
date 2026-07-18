@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MaxLineLength",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.CancellationException

internal sealed interface HlsVodPlaylistResolution {
    data class Success(
        val playlistUri: String,
        val playlist: String,
        val master: ParsedHlsVodMaster?,
    ) : HlsVodPlaylistResolution

    data class Failure(
        val message: String,
        val reason: HlsVodDolbyVisionFailureReason,
    ) : HlsVodPlaylistResolution
}

internal data class HlsVodBridgeResource(
    val payload: ByteArray,
    val contentType: String,
)

internal enum class HlsVodRenditionType {
    AUDIO,
    SUBTITLES,
    CLOSED_CAPTIONS,
}

internal data class ParsedHlsVodMaster(
    val streamInfLine: String,
    val independentSegments: Boolean,
    val version: Int?,
    val renditions: List<ParsedHlsVodRendition>,
) {
    val hasExternalAudioRenditions: Boolean
        get() = renditions.any { it.type == HlsVodRenditionType.AUDIO && it.playlist != null }

    val preferredExternalAudioRendition: ParsedHlsVodRendition?
        get() =
            renditions
                .filter { it.type == HlsVodRenditionType.AUDIO && it.playlist != null }
                .maxWithOrNull(
                    compareBy<ParsedHlsVodRendition> { it.isDefault }.thenBy { it.isAutoSelect }.thenBy { -it.id },
                )

    fun render(resourcePrefix: String): String {
        val prefix = resourcePrefix.trimEnd('/')
        return buildString {
            appendLine(HLS_MASTER_HEADER)
            version?.let { appendLine("#EXT-X-VERSION:$it") }
            if (independentSegments) appendLine(HLS_INDEPENDENT_SEGMENTS)
            renditions.forEach { rendition ->
                val rewritten =
                    if (rendition.playlist == null) {
                        rendition.mediaLine
                    } else {
                        replaceHlsQuotedAttribute(
                            rendition.mediaLine,
                            "URI",
                            "$prefix/rendition/${rendition.id}/playlist.m3u8",
                        )
                    }
                appendLine(rewritten)
            }
            appendLine(streamInfLine)
            appendLine("$prefix/video.m3u8")
        }
    }
}

internal data class ParsedHlsVodRendition(
    val id: Int,
    val type: HlsVodRenditionType,
    val mediaLine: String,
    val playlist: ParsedHlsVodPassthroughPlaylist?,
    val codec: String?,
    val isDefault: Boolean,
    val isAutoSelect: Boolean,
)

internal data class ParsedHlsVodPassthroughPlaylist(
    val originalLines: List<String>,
    val mapLineToResourceIndex: Map<Int, Int>,
    val maps: List<HlsVodPassthroughResource>,
    val segmentLineToResourceIndex: Map<Int, Int>,
    val segments: List<HlsVodPassthroughSegment>,
    val byteRangeLineIndices: Set<Int>,
) {
    fun render(resourcePrefix: String): String {
        val prefix = resourcePrefix.trimEnd('/')
        return buildString {
            originalLines.forEachIndexed { lineIndex, line ->
                if (lineIndex in byteRangeLineIndices) return@forEachIndexed
                val replacement =
                    mapLineToResourceIndex[lineIndex]?.let { "$HLS_MAP_URI_PREFIX$prefix/init/$it\"" }
                        ?: segmentLineToResourceIndex[lineIndex]?.let { "$prefix/segment/$it" }
                        ?: line
                append(replacement)
                append('\n')
            }
        }
    }
}

internal data class HlsVodPassthroughResource(
    val uri: String,
    val byteRange: DolbyVisionByteRange?,
)

internal data class HlsVodPassthroughSegment(
    val resource: HlsVodPassthroughResource,
    val startPresentationTimeUs: Long,
    val durationUs: Long,
    val initializationIndex: Int?,
    val discontinuity: Boolean,
)

internal suspend fun resolveHlsVodPlaylist(
    playlistUri: String,
    playlist: String,
    dataSource: DolbyVisionMediaDataSource,
): HlsVodPlaylistResolution {
    val lines = normalizedHlsLines(playlist)
    if (lines.firstOrNull()?.trim() != HLS_MASTER_HEADER) {
        return hlsResolutionFailure("The HLS playlist has no #EXTM3U header.")
    }
    if (lines.none { it.trim().startsWith(HLS_MASTER_STREAM_INF) }) {
        return HlsVodPlaylistResolution.Success(playlistUri, playlist, null)
    }
    if (lines.size > MAXIMUM_HLS_PLAYLIST_LINES) {
        return hlsResolutionFailure("The HLS master playlist exceeds the line-count limit.")
    }
    val masterKeyFailure = encryptedHlsFailure(lines)
    if (masterKeyFailure != null) return masterKeyFailure

    val variants = mutableListOf<HlsMasterVariant>()
    var pendingStreamInf: String? = null
    lines.forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith(HLS_MASTER_STREAM_INF) -> {
                if (pendingStreamInf != null) {
                    return hlsResolutionFailure("An HLS master variant has no media-playlist URI.")
                }
                pendingStreamInf = line
            }
            line.isNotEmpty() && !line.startsWith('#') -> {
                val streamInf =
                    pendingStreamInf
                        ?: return hlsResolutionFailure("The HLS master contains a URI without EXT-X-STREAM-INF.")
                val attributes =
                    parseHlsAttributeList(streamInf.substringAfter(':'))
                        ?: return hlsResolutionFailure("An HLS master variant has an invalid attribute list.")
                val bandwidthValue = attributes["AVERAGE-BANDWIDTH"] ?: attributes["BANDWIDTH"]
                val bandwidth = bandwidthValue?.toLongOrNull()
                if (bandwidth == null || bandwidth <= 0) {
                    return hlsResolutionFailure("An HLS master variant has invalid or missing bandwidth.")
                }
                variants +=
                    HlsMasterVariant(
                        streamInfLine = streamInf,
                        playlistUri = resolveHlsUri(playlistUri, line),
                        codecs = attributes["CODECS"],
                        bandwidth = bandwidth,
                        referencedGroups =
                            listOf("AUDIO", "SUBTITLES", "CLOSED-CAPTIONS")
                                .mapNotNull { type ->
                                    attributes[type]?.takeUnless { it.equals("NONE", true) }?.let {
                                        type to
                                            it
                                    }
                                }.toMap(),
                    )
                pendingStreamInf = null
                if (variants.size > MAXIMUM_HLS_MASTER_VARIANTS) {
                    return hlsResolutionFailure("The HLS master exceeds the variant-count limit.")
                }
            }
        }
    }
    if (pendingStreamInf != null) return hlsResolutionFailure("An HLS master variant has no media-playlist URI.")
    if (variants.isEmpty()) return hlsResolutionFailure("The HLS master playlist contains no variants.")

    val profile7Variants = variants.filter(HlsMasterVariant::explicitlyCarriesProfile7)
    val unspecifiedVariants = variants.filter { it.codecs.isNullOrBlank() }
    val candidates = profile7Variants.ifEmpty { unspecifiedVariants }
    if (candidates.isEmpty()) {
        return hlsResolutionFailure(
            "The HLS master has no Dolby Vision Profile 7 variant and no safely probeable variant.",
            HlsVodDolbyVisionFailureReason.UNSUPPORTED_VARIANT,
        )
    }
    val selected = candidates.maxWith(compareBy<HlsMasterVariant> { it.bandwidth }.thenBy { it.playlistUri })
    val selectedPlaylist =
        try {
            dataSource
                .readHlsBounded(selected.playlistUri, null, MAXIMUM_DOVI_HLS_PLAYLIST_BYTES)
                .decodeToString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return hlsResolutionFailure(
                "Unable to read the selected HLS media playlist: ${error.message ?: error::class.simpleName}.",
                HlsVodDolbyVisionFailureReason.RESOURCE_IO,
            )
        }
    if (normalizedHlsLines(selectedPlaylist).any { it.trim().startsWith(HLS_MASTER_STREAM_INF) }) {
        return hlsResolutionFailure("Nested HLS master playlists are not supported.")
    }

    val renditionResult =
        resolveSelectedRenditions(
            masterUri = playlistUri,
            masterLines = lines,
            referencedGroups = selected.referencedGroups,
            selectedAudioCodec = selected.audioCodec,
            dataSource = dataSource,
        )
    val renditions =
        when (renditionResult) {
            is HlsRenditionResolution.Success -> renditionResult.renditions
            is HlsRenditionResolution.Failure -> return hlsResolutionFailure(
                renditionResult.message,
                renditionResult.reason,
            )
        }
    val version =
        lines
            .firstOrNull { it.trim().startsWith(HLS_VERSION_PREFIX) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 1 }
    return HlsVodPlaylistResolution.Success(
        playlistUri = selected.playlistUri,
        playlist = selectedPlaylist,
        master =
            ParsedHlsVodMaster(
                streamInfLine = selected.streamInfLine,
                independentSegments = lines.any { it.trim() == HLS_INDEPENDENT_SEGMENTS },
                version = version,
                renditions = renditions,
            ),
    )
}

internal suspend fun readHlsMasterResource(
    master: ParsedHlsVodMaster,
    path: String,
    resourcePrefix: String,
    dataSource: DolbyVisionMediaDataSource,
    maximumResourceBytes: Int,
): HlsVodBridgeResource? {
    val match = HLS_RENDITION_RESOURCE.matchEntire(path) ?: return null
    val rendition = master.renditions.getOrNull(match.groupValues[1].toIntOrNull() ?: return null) ?: return null
    val playlist = rendition.playlist ?: return null
    val kind = match.groupValues[2]
    val indexText = match.groupValues[3]
    if (kind == "playlist.m3u8") {
        return HlsVodBridgeResource(
            playlist.render("${resourcePrefix.trimEnd('/')}/rendition/${rendition.id}").encodeToByteArray(),
            DOVI_HLS_PLAYLIST_CONTENT_TYPE,
        )
    }
    val index = indexText.toIntOrNull() ?: return null
    val resource =
        when (kind) {
            "init" -> playlist.maps.getOrNull(index)
            "segment" -> playlist.segments.getOrNull(index)?.resource
            else -> null
        } ?: return null
    val payload = dataSource.readHlsBounded(resource.uri, resource.byteRange, maximumResourceBytes)
    return HlsVodBridgeResource(payload, resource.uri.hlsContentType(rendition.type))
}

private suspend fun resolveSelectedRenditions(
    masterUri: String,
    masterLines: List<String>,
    referencedGroups: Map<String, String>,
    selectedAudioCodec: String?,
    dataSource: DolbyVisionMediaDataSource,
): HlsRenditionResolution {
    val selected = mutableListOf<ParsedHlsVodRendition>()
    for (rawLine in masterLines) {
        val line = rawLine.trim()
        if (!line.startsWith(HLS_MEDIA_PREFIX)) continue
        val attributes =
            parseHlsAttributeList(line.substringAfter(':'))
                ?: return HlsRenditionResolution.Failure(
                    "An HLS rendition has an invalid attribute list.",
                    HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
                )
        val typeName = attributes["TYPE"]?.uppercase() ?: continue
        val groupId = attributes["GROUP-ID"] ?: continue
        if (referencedGroups[typeName] != groupId) continue
        val type =
            when (typeName) {
                "AUDIO" -> HlsVodRenditionType.AUDIO
                "SUBTITLES" -> HlsVodRenditionType.SUBTITLES
                "CLOSED-CAPTIONS" -> HlsVodRenditionType.CLOSED_CAPTIONS
                else -> continue
            }
        val uri = attributes["URI"]?.let { resolveHlsUri(masterUri, it) }
        if (uri != null && !line.hasQuotedHlsAttribute("URI")) {
            return HlsRenditionResolution.Failure(
                "An external HLS rendition URI must be quoted.",
                HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
            )
        }
        val parsedPlaylist =
            if (uri == null) {
                if (type != HlsVodRenditionType.CLOSED_CAPTIONS && type != HlsVodRenditionType.AUDIO) {
                    return HlsRenditionResolution.Failure(
                        "An external HLS subtitle rendition has no URI.",
                        HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
                    )
                }
                null
            } else {
                val payload =
                    try {
                        dataSource.readHlsBounded(uri, null, MAXIMUM_DOVI_HLS_PLAYLIST_BYTES).decodeToString()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        return HlsRenditionResolution.Failure(
                            "Unable to read HLS rendition '${attributes["NAME"] ?: groupId}': " +
                                "${error.message ?: error::class.simpleName}.",
                            HlsVodDolbyVisionFailureReason.RESOURCE_IO,
                        )
                    }
                when (val parsed = parseHlsPassthroughVodPlaylist(uri, payload)) {
                    is HlsPassthroughParseResult.Success -> parsed.playlist
                    is HlsPassthroughParseResult.Failure ->
                        return HlsRenditionResolution.Failure(parsed.message, parsed.reason)
                }
            }
        selected +=
            ParsedHlsVodRendition(
                id = selected.size,
                type = type,
                mediaLine = line,
                playlist = parsedPlaylist,
                codec = selectedAudioCodec.takeIf { type == HlsVodRenditionType.AUDIO },
                isDefault = attributes["DEFAULT"].equals("YES", ignoreCase = true),
                isAutoSelect = attributes["AUTOSELECT"].equals("YES", ignoreCase = true),
            )
        if (selected.size > MAXIMUM_HLS_MASTER_RENDITIONS) {
            return HlsRenditionResolution.Failure(
                "The selected HLS variant exceeds the rendition-count limit.",
                HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
            )
        }
    }
    val missingGroups =
        referencedGroups.filter { (type, group) ->
            selected.none { rendition ->
                rendition.type.name == type &&
                    parseHlsAttributeList(rendition.mediaLine.substringAfter(':'))?.get("GROUP-ID") == group
            }
        }
    if (missingGroups.isNotEmpty()) {
        return HlsRenditionResolution.Failure(
            "The selected HLS variant references missing rendition groups: ${missingGroups.values.joinToString()}.",
            HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
        )
    }
    return HlsRenditionResolution.Success(selected)
}

private fun parseHlsPassthroughVodPlaylist(
    playlistUri: String,
    text: String,
): HlsPassthroughParseResult {
    val lines = normalizedHlsLines(text)
    if (lines.firstOrNull()?.trim() != HLS_MASTER_HEADER) {
        return hlsPassthroughFailure("An HLS rendition playlist has no #EXTM3U header.")
    }
    if (lines.size > MAXIMUM_HLS_PLAYLIST_LINES) {
        return hlsPassthroughFailure("An HLS rendition playlist exceeds the line-count limit.")
    }
    if (lines.none { it.trim() == HLS_ENDLIST_TAG }) {
        return hlsPassthroughFailure(
            "Live HLS renditions are not converted; #EXT-X-ENDLIST is required.",
            HlsVodDolbyVisionFailureReason.LIVE_SOURCE,
        )
    }
    if (lines.any { it.trim().startsWith(HLS_MASTER_STREAM_INF) }) {
        return hlsPassthroughFailure("An HLS rendition URI resolved to another master playlist.")
    }
    encryptedHlsFailure(lines)?.let { return HlsPassthroughParseResult.Failure(it.message, it.reason) }
    if (lines.any { it.trim().startsWith(HLS_PART_PREFIX) || it.trim().startsWith(HLS_PRELOAD_PREFIX) }) {
        return hlsPassthroughFailure(
            "Low-latency/live HLS rendition parts are not converted.",
            HlsVodDolbyVisionFailureReason.LIVE_SOURCE,
        )
    }

    val maps = mutableListOf<HlsVodPassthroughResource>()
    val segments = mutableListOf<HlsVodPassthroughSegment>()
    val mapLines = mutableMapOf<Int, Int>()
    val segmentLines = mutableMapOf<Int, Int>()
    val rangeLines = mutableSetOf<Int>()
    var pendingDurationUs: Long? = null
    var pendingRange: Pair<Long, Long?>? = null
    var pendingRangeLine: Int? = null
    var previousRangeUri: String? = null
    var previousRangeEnd = 0L
    var activeMapIndex: Int? = null
    var currentStartUs = 0L
    var discontinuity = false

    lines.forEachIndexed { lineIndex, rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith(HLS_MAP_PREFIX) -> {
                val attributes =
                    parseHlsAttributeList(line.substringAfter(':'))
                        ?: return hlsPassthroughFailure("An HLS rendition map has an invalid attribute list.")
                val uri = attributes["URI"] ?: return hlsPassthroughFailure("An HLS rendition EXT-X-MAP has no URI.")
                if (!line.hasQuotedHlsAttribute("URI")) {
                    return hlsPassthroughFailure("An HLS rendition EXT-X-MAP URI must be quoted.")
                }
                val range =
                    attributes["BYTERANGE"]?.let(::parseMasterExplicitByteRange)
                        ?: if ("BYTERANGE" in attributes) {
                            return hlsPassthroughFailure("An HLS rendition has an invalid EXT-X-MAP BYTERANGE.")
                        } else {
                            null
                        }
                mapLines[lineIndex] = maps.size
                maps += HlsVodPassthroughResource(resolveHlsUri(playlistUri, uri), range)
                activeMapIndex = maps.lastIndex
                if (maps.size > MAXIMUM_HLS_MEDIA_RESOURCES) {
                    return hlsPassthroughFailure("An HLS rendition exceeds the initialization-resource limit.")
                }
            }
            line.startsWith(HLS_EXTINF_PREFIX) -> {
                val seconds =
                    line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: return hlsPassthroughFailure("An HLS rendition has an invalid EXTINF duration.")
                if (!seconds.isFinite() || seconds <= 0.0 || seconds > MAXIMUM_HLS_DURATION_SECONDS) {
                    return hlsPassthroughFailure("An HLS rendition EXTINF duration is outside the supported range.")
                }
                pendingDurationUs = (seconds * MICROSECONDS_PER_SECOND_LONG).toLong().coerceAtLeast(1L)
            }
            line.startsWith(HLS_BYTERANGE_PREFIX) -> {
                pendingRange = parseMasterImplicitByteRange(line.substringAfter(':'))
                    ?: return hlsPassthroughFailure("An HLS rendition has an invalid EXT-X-BYTERANGE.")
                pendingRangeLine = lineIndex
            }
            line == HLS_DISCONTINUITY_TAG -> discontinuity = true
            line.isNotEmpty() && !line.startsWith('#') -> {
                val durationUs =
                    pendingDurationUs
                        ?: return hlsPassthroughFailure("An HLS rendition URI has no preceding EXTINF.")
                val uri = resolveHlsUri(playlistUri, line)
                val range =
                    pendingRange?.let { (length, explicitOffset) ->
                        val offset =
                            explicitOffset ?: if (previousRangeUri == uri) {
                                previousRangeEnd
                            } else {
                                return hlsPassthroughFailure(
                                    "An implicit HLS byte range changed resource URI.",
                                )
                            }
                        runCatching { DolbyVisionByteRange(offset, length) }.getOrNull()
                            ?: return hlsPassthroughFailure("An HLS rendition byte range overflows.")
                    }
                segmentLines[lineIndex] = segments.size
                segments +=
                    HlsVodPassthroughSegment(
                        resource = HlsVodPassthroughResource(uri, range),
                        startPresentationTimeUs = currentStartUs,
                        durationUs = durationUs,
                        initializationIndex = activeMapIndex,
                        discontinuity = discontinuity,
                    )
                pendingRangeLine?.let(rangeLines::add)
                previousRangeUri = if (range == null) null else uri
                previousRangeEnd = range?.let { it.offset + it.length } ?: 0L
                currentStartUs += durationUs
                pendingDurationUs = null
                pendingRange = null
                pendingRangeLine = null
                discontinuity = false
                if (segments.size > MAXIMUM_HLS_MEDIA_RESOURCES) {
                    return hlsPassthroughFailure("An HLS rendition exceeds the segment-count limit.")
                }
            }
        }
    }
    if (pendingDurationUs != null || pendingRange != null) {
        return hlsPassthroughFailure("An HLS rendition ends with an incomplete segment declaration.")
    }
    if (segments.isEmpty()) return hlsPassthroughFailure("An HLS rendition contains no media segments.")
    return HlsPassthroughParseResult.Success(
        ParsedHlsVodPassthroughPlaylist(lines, mapLines, maps, segmentLines, segments, rangeLines),
    )
}

private sealed interface HlsRenditionResolution {
    data class Success(
        val renditions: List<ParsedHlsVodRendition>,
    ) : HlsRenditionResolution

    data class Failure(
        val message: String,
        val reason: HlsVodDolbyVisionFailureReason,
    ) : HlsRenditionResolution
}

private sealed interface HlsPassthroughParseResult {
    data class Success(
        val playlist: ParsedHlsVodPassthroughPlaylist,
    ) : HlsPassthroughParseResult

    data class Failure(
        val message: String,
        val reason: HlsVodDolbyVisionFailureReason,
    ) : HlsPassthroughParseResult
}

private data class HlsMasterVariant(
    val streamInfLine: String,
    val playlistUri: String,
    val codecs: String?,
    val bandwidth: Long,
    val referencedGroups: Map<String, String>,
) {
    val audioCodec: String?
        get() = codecs?.split(',')?.map(String::trim)?.firstOrNull(AUDIO_CODEC::matches)

    fun explicitlyCarriesProfile7(): Boolean =
        codecs
            ?.split(',')
            ?.map(String::trim)
            ?.any { DOLBY_VISION_PROFILE_7_CODEC.matches(it) } == true
}

private fun normalizedHlsLines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

private fun encryptedHlsFailure(lines: List<String>): HlsVodPlaylistResolution.Failure? {
    lines.forEach { rawLine ->
        val line = rawLine.trim()
        if (!line.startsWith(HLS_KEY_PREFIX) && !line.startsWith(HLS_SESSION_KEY_PREFIX)) return@forEach
        val attributes =
            parseHlsAttributeList(line.substringAfter(':'))
                ?: return hlsResolutionFailure("An HLS key tag has an invalid attribute list.")
        val method = attributes["METHOD"]?.uppercase()
        if (method != "NONE") {
            return hlsResolutionFailure(
                "Encrypted/DRM HLS is not converted.",
                HlsVodDolbyVisionFailureReason.DRM_PROTECTED,
            )
        }
    }
    return null
}

private fun replaceHlsQuotedAttribute(
    line: String,
    name: String,
    value: String,
): String {
    require('"' !in value && '\r' !in value && '\n' !in value)
    val expression = Regex("(?i)($name\\s*=\\s*\")[^\"]*(\")")
    check(expression.containsMatchIn(line)) { "The HLS tag has no quoted $name attribute." }
    return expression.replace(line, "\$1${Regex.escapeReplacement(value)}\$2")
}

private fun parseMasterExplicitByteRange(value: String): DolbyVisionByteRange? {
    val parts = value.trim().trim('"').split('@', limit = 2)
    if (parts.size != 2) return null
    val length = parts[0].toLongOrNull() ?: return null
    val offset = parts[1].toLongOrNull() ?: return null
    return runCatching { DolbyVisionByteRange(offset, length) }.getOrNull()
}

private fun parseMasterImplicitByteRange(value: String): Pair<Long, Long?>? {
    val parts = value.trim().split('@', limit = 2)
    val length = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val offset = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
    if (parts.size == 2 && offset == null) return null
    return length to offset
}

private fun String.hlsContentType(type: HlsVodRenditionType): String {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".m4a") -> DOVI_MP4_CONTENT_TYPE
        path.endsWith(".vtt") || path.endsWith(".webvtt") -> WEBVTT_CONTENT_TYPE
        path.endsWith(".aac") -> AAC_CONTENT_TYPE
        path.endsWith(".ac3") -> AC3_CONTENT_TYPE
        path.endsWith(".ec3") -> EAC3_CONTENT_TYPE
        path.endsWith(".ts") -> MPEG_TS_CONTENT_TYPE
        type == HlsVodRenditionType.SUBTITLES -> WEBVTT_CONTENT_TYPE
        else -> OCTET_STREAM_CONTENT_TYPE
    }
}

internal suspend fun DolbyVisionMediaDataSource.readHlsBounded(
    uri: String,
    byteRange: DolbyVisionByteRange?,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes > 0) { "maximumBytes must be positive." }
    byteRange?.let { require(it.length <= maximumBytes) { "The requested HLS byte range exceeds its limit." } }
    val payload = read(uri, byteRange, maximumBytes)
    require(payload.size <= maximumBytes) { "The HLS media source returned more than its requested byte limit." }
    byteRange?.let { require(payload.size.toLong() == it.length) { "The HLS byte-range response was truncated." } }
    return payload
}

private fun hlsResolutionFailure(
    message: String,
    reason: HlsVodDolbyVisionFailureReason = HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
) = HlsVodPlaylistResolution.Failure(message, reason)

private fun hlsPassthroughFailure(
    message: String,
    reason: HlsVodDolbyVisionFailureReason = HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST,
) = HlsPassthroughParseResult.Failure(message, reason)

internal const val MAXIMUM_DOVI_HLS_PLAYLIST_BYTES = 8 * 1024 * 1024
internal const val DEFAULT_MAXIMUM_HLS_RESOURCE_BYTES = 64 * 1024 * 1024
internal const val DOVI_HLS_PLAYLIST_CONTENT_TYPE = "application/vnd.apple.mpegurl"
internal const val DOVI_MP4_CONTENT_TYPE = "video/mp4"

private const val MAXIMUM_HLS_MASTER_VARIANTS = 128
private const val MAXIMUM_HLS_MASTER_RENDITIONS = 64
private const val MAXIMUM_HLS_PLAYLIST_LINES = 100_000
private const val MAXIMUM_HLS_MEDIA_RESOURCES = 100_000
private const val MAXIMUM_HLS_DURATION_SECONDS = 86_400.0
private const val HLS_MASTER_HEADER = "#EXTM3U"
private const val HLS_MASTER_STREAM_INF = "#EXT-X-STREAM-INF:"
private const val HLS_MEDIA_PREFIX = "#EXT-X-MEDIA:"
private const val HLS_VERSION_PREFIX = "#EXT-X-VERSION:"
private const val HLS_INDEPENDENT_SEGMENTS = "#EXT-X-INDEPENDENT-SEGMENTS"
private const val HLS_ENDLIST_TAG = "#EXT-X-ENDLIST"
private const val HLS_PART_PREFIX = "#EXT-X-PART:"
private const val HLS_PRELOAD_PREFIX = "#EXT-X-PRELOAD-HINT:"
private const val HLS_KEY_PREFIX = "#EXT-X-KEY:"
private const val HLS_SESSION_KEY_PREFIX = "#EXT-X-SESSION-KEY:"
private const val HLS_MAP_PREFIX = "#EXT-X-MAP:"
private const val HLS_MAP_URI_PREFIX = "#EXT-X-MAP:URI=\""
private const val HLS_EXTINF_PREFIX = "#EXTINF:"
private const val HLS_BYTERANGE_PREFIX = "#EXT-X-BYTERANGE:"
private const val HLS_DISCONTINUITY_TAG = "#EXT-X-DISCONTINUITY"
private const val MICROSECONDS_PER_SECOND_LONG = 1_000_000L
private const val WEBVTT_CONTENT_TYPE = "text/vtt"
private const val AAC_CONTENT_TYPE = "audio/aac"
private const val AC3_CONTENT_TYPE = "audio/ac3"
private const val EAC3_CONTENT_TYPE = "audio/eac3"
private const val MPEG_TS_CONTENT_TYPE = "video/mp2t"
private const val OCTET_STREAM_CONTENT_TYPE = "application/octet-stream"
private val DOLBY_VISION_PROFILE_7_CODEC = Regex("(?:dvh1|dvhe)\\.0?7(?:\\..+)?", RegexOption.IGNORE_CASE)
private val AUDIO_CODEC = Regex("(?:mp4a(?:\\.[A-Za-z0-9]+)+|ac-3|ec-3|opus|flac)", RegexOption.IGNORE_CASE)
private val HLS_RENDITION_RESOURCE = Regex("^rendition/([0-9]+)/(playlist\\.m3u8|init|segment)(?:/([0-9]+))?$")
