@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal sealed interface MatroskaParseResult {
    data class Success(
        val movie: ParsedMatroskaMovie,
    ) : MatroskaParseResult

    data class Failure(
        val message: String,
    ) : MatroskaParseResult
}

internal data class ParsedMatroskaMovie(
    val tracks: List<MatroskaTrack>,
    val videoTrack: MatroskaTrack,
    val fragments: List<MatroskaFragmentPlan>,
)

internal enum class MatroskaTrackKind {
    VIDEO,
    AUDIO,
}

internal enum class MatroskaAudioCodec {
    AAC,
    OPUS,
    AC3,
    EAC3,
}

internal data class MatroskaColour(
    val matrix: Int = 2,
    val bitsPerChannel: Int = 0,
    val range: Int = 0,
    val transfer: Int = 2,
    val primaries: Int = 2,
    val maxCll: Int? = null,
    val maxFall: Int? = null,
    val mastering: MatroskaMasteringMetadata? = null,
)

internal data class MatroskaMasteringMetadata(
    val redX: Double?,
    val redY: Double?,
    val greenX: Double?,
    val greenY: Double?,
    val blueX: Double?,
    val blueY: Double?,
    val whiteX: Double?,
    val whiteY: Double?,
    val maxLuminance: Double?,
    val minLuminance: Double?,
)

internal data class MatroskaTrack(
    val trackNumber: Long,
    val trackId: Int,
    val kind: MatroskaTrackKind,
    val codecId: String,
    val codecPrivate: ByteArray,
    val defaultDurationNs: Long?,
    val codecDelayNs: Long,
    val seekPreRollNs: Long,
    val timestampScale: Double,
    val name: String?,
    val language: String,
    val width: Int = 0,
    val height: Int = 0,
    val colour: MatroskaColour? = null,
    val dolbyVisionConfiguration: ByteArray? = null,
    val audioCodec: MatroskaAudioCodec? = null,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val samples: List<MatroskaSample> = emptyList(),
) {
    val timescale: Long get() = MATROSKA_CMAF_TIMESCALE
}

internal data class MatroskaSample(
    val offset: Long,
    val size: Int,
    val decodeTime: Long,
    val duration: Long,
    val compositionOffset: Long,
    val presentationTime: Long,
    val isSync: Boolean,
)

internal data class MatroskaFragmentPlan(
    val index: Int,
    val startPresentationTimeUs: Long,
    val endPresentationTimeUs: Long,
    val sampleIndicesByTrackId: Map<Int, List<Int>>,
) {
    val startsWithRandomAccessPoint: Boolean = true
}

private data class RawMatroskaTrack(
    val trackNumber: Long,
    val kind: MatroskaTrackKind,
    val codecId: String,
    val codecPrivate: ByteArray,
    val defaultDurationNs: Long?,
    val codecDelayNs: Long,
    val seekPreRollNs: Long,
    val timestampScale: Double,
    val name: String?,
    val language: String,
    val width: Int,
    val height: Int,
    val colour: MatroskaColour?,
    val dolbyVisionConfiguration: ByteArray?,
    val audioCodec: MatroskaAudioCodec?,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
)

private data class RawMatroskaSample(
    val offset: Long,
    val size: Int,
    val presentationTimeNs: Long,
    val explicitDurationNs: Long?,
    val isSync: Boolean,
    val encounterIndex: Long,
)

private data class EbmlElement(
    val id: Long,
    val offset: Long,
    val headerSize: Int,
    val dataOffset: Long,
    val dataSize: Long?,
    val endOffset: Long,
)

private data class EbmlVint(
    val value: Long,
    val length: Int,
    val unknown: Boolean,
)

private data class ParsedBlock(
    val trackNumber: Long,
    val relativeTimestamp: Int,
    val keyframe: Boolean,
    val frames: List<BlockFrame>,
)

private data class BlockFrame(
    val offset: Long,
    val size: Int,
)

private data class ClusterRange(
    val element: EbmlElement,
)

@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal suspend fun parseMatroska(
    source: DolbyVisionRandomAccessDataSource,
    targetFragmentDurationUs: Long,
    maximumMetadataBytes: Int,
    maximumSamples: Int,
): MatroskaParseResult {
    if (targetFragmentDurationUs <= 0 || maximumMetadataBytes <= 0 || maximumSamples <= 0) {
        return matroskaFailure("Matroska duration, metadata and sample limits must be positive.")
    }
    return try {
        parseMatroskaChecked(source, targetFragmentDurationUs, maximumMetadataBytes, maximumSamples)
    } catch (error: IllegalArgumentException) {
        matroskaFailure(error.message ?: "The Matroska source is invalid.")
    }
}

@Suppress("LongMethod")
private suspend fun parseMatroskaChecked(
    source: DolbyVisionRandomAccessDataSource,
    targetFragmentDurationUs: Long,
    maximumMetadataBytes: Int,
    maximumSamples: Int,
): MatroskaParseResult {
    val sourceSize = source.size()
    if (sourceSize <= 0) return matroskaFailure("The Matroska source is empty.")
    val ebml = source.readEbmlElement(0, sourceSize) ?: return matroskaFailure("The EBML header is missing.")
    if (ebml.id != ID_EBML || ebml.dataSize == null) return matroskaFailure("The Matroska EBML header is invalid.")
    val docType =
        source.children(ebml).firstOrNull { it.id == ID_DOC_TYPE }?.let {
            source.readString(it, MAXIMUM_TEXT_BYTES)
        }
    if (docType != "matroska") return matroskaFailure("The EBML document type is not Matroska.")

    val segment =
        source.readEbmlElement(ebml.endOffset, sourceSize)
            ?: return matroskaFailure("The Matroska Segment is missing.")
    if (segment.id != ID_SEGMENT) return matroskaFailure("The EBML root element is not a Matroska Segment.")
    val segmentEnd = segment.dataSize?.let { segment.endOffset } ?: sourceSize
    val topLevel = source.scanSegmentChildren(segment.dataOffset, segmentEnd)
    val info =
        topLevel.firstOrNull { it.id == ID_INFO }
            ?: return matroskaFailure("The Matroska Segment has no Info element.")
    val tracksElement =
        topLevel.firstOrNull { it.id == ID_TRACKS }
            ?: return matroskaFailure("The Matroska Segment has no Tracks element.")
    if ((info.dataSize ?: Long.MAX_VALUE) > maximumMetadataBytes ||
        (tracksElement.dataSize ?: Long.MAX_VALUE) > maximumMetadataBytes
    ) {
        return matroskaFailure("Matroska Info or Tracks exceeds the metadata byte limit.")
    }
    val timestampScale = source.parseTimestampScale(info)
    val rawTracks = source.parseTrackEntries(tracksElement, maximumMetadataBytes)
    val selected = selectMatroskaTracks(rawTracks)
    val videoRaw = selected.first
    val selectedTracks = selected.second
    val clusters = topLevel.filter { it.id == ID_CLUSTER }.map(::ClusterRange)
    if (clusters.isEmpty()) return matroskaFailure("The Matroska Segment has no Cluster elements.")

    val rawSamples = selectedTracks.associate { it.trackNumber to mutableListOf<RawMatroskaSample>() }
    var encounterIndex = 0L
    for (cluster in clusters) {
        encounterIndex =
            source.parseCluster(
                range = cluster,
                timestampScaleNs = timestampScale,
                selectedTracks = selectedTracks.associateBy(RawMatroskaTrack::trackNumber),
                output = rawSamples,
                encounterIndex = encounterIndex,
                maximumSamples = maximumSamples,
            )
    }
    if (rawSamples.getValue(videoRaw.trackNumber).isEmpty()) {
        return matroskaFailure("The Dolby Vision Matroska track contains no samples.")
    }
    if (!rawSamples.getValue(videoRaw.trackNumber).first().isSync) {
        return matroskaFailure("The first Dolby Vision Matroska sample is not a random-access point.")
    }

    val finalized = finalizeMatroskaTracks(source, selectedTracks, rawSamples)
    val video = finalized.single { it.trackNumber == videoRaw.trackNumber }
    val fragments = buildMatroskaFragmentPlans(finalized, video, targetFragmentDurationUs)
    if (fragments.isEmpty()) return matroskaFailure("The Matroska source has no fragmentable video samples.")
    return MatroskaParseResult.Success(ParsedMatroskaMovie(finalized, video, fragments))
}

private suspend fun DolbyVisionRandomAccessDataSource.scanSegmentChildren(
    start: Long,
    end: Long,
): List<EbmlElement> {
    val result = mutableListOf<EbmlElement>()
    var cursor = start
    while (cursor < end) {
        val header = readEbmlElement(cursor, end) ?: error("A Matroska top-level element is truncated.")
        val element =
            if (header.dataSize == null) {
                require(header.id == ID_CLUSTER) { "Only Segment and Cluster may use an unknown EBML size." }
                val clusterEnd = findUnknownClusterEnd(header.dataOffset, end)
                header.copy(endOffset = clusterEnd)
            } else {
                header
            }
        result += element
        require(element.endOffset > cursor) { "A Matroska top-level element has no progress." }
        cursor = element.endOffset
    }
    require(cursor == end) { "The Matroska Segment child structure is inconsistent." }
    return result
}

private suspend fun DolbyVisionRandomAccessDataSource.findUnknownClusterEnd(
    start: Long,
    segmentEnd: Long,
): Long {
    var cursor = start
    while (cursor < segmentEnd) {
        val element = readEbmlElement(cursor, segmentEnd) ?: error("An unknown-size Cluster is truncated.")
        if (element.id in MATROSKA_TOP_LEVEL_IDS) return cursor
        require(element.dataSize != null) { "A nested unknown-size Matroska element is unsupported." }
        cursor = element.endOffset
    }
    return segmentEnd
}

private suspend fun DolbyVisionRandomAccessDataSource.parseTimestampScale(info: EbmlElement): Long {
    val value =
        children(info).firstOrNull { it.id == ID_TIMESTAMP_SCALE }?.let { readUnsigned(it) }
            ?: DEFAULT_TIMESTAMP_SCALE_NS
    require(value in 1..MAXIMUM_TIMESTAMP_SCALE_NS) { "The Matroska TimestampScale is invalid." }
    return value
}

private suspend fun DolbyVisionRandomAccessDataSource.parseTrackEntries(
    tracks: EbmlElement,
    maximumMetadataBytes: Int,
): List<RawMatroskaTrack> {
    val entries = children(tracks).filter { it.id == ID_TRACK_ENTRY }
    require(entries.isNotEmpty()) { "The Matroska Tracks element contains no TrackEntry." }
    return entries.mapNotNull { parseTrackEntry(it, maximumMetadataBytes) }
}

@Suppress("LongMethod")
private suspend fun DolbyVisionRandomAccessDataSource.parseTrackEntry(
    entry: EbmlElement,
    maximumMetadataBytes: Int,
): RawMatroskaTrack? {
    require((entry.dataSize ?: Long.MAX_VALUE) <= maximumMetadataBytes) {
        "A Matroska TrackEntry exceeds the metadata byte limit."
    }
    val fields = children(entry)
    val enabled = fields.firstOrNull { it.id == ID_FLAG_ENABLED }?.let { readUnsigned(it) } ?: 1L
    if (enabled == 0L) return null
    val trackType =
        fields.singleOrNull { it.id == ID_TRACK_TYPE }?.let { readUnsigned(it) }
            ?: error("A Matroska TrackEntry has no TrackType.")
    val kind =
        when (trackType) {
            TRACK_TYPE_VIDEO -> MatroskaTrackKind.VIDEO
            TRACK_TYPE_AUDIO -> MatroskaTrackKind.AUDIO
            else -> return null
        }
    val trackNumber =
        fields.singleOrNull { it.id == ID_TRACK_NUMBER }?.let { readUnsigned(it) }
            ?: error("A Matroska TrackEntry has no TrackNumber.")
    require(trackNumber > 0) { "A Matroska TrackNumber must be positive." }
    val codecId =
        fields.singleOrNull { it.id == ID_CODEC_ID }?.let { readString(it, MAXIMUM_TEXT_BYTES) }
            ?: error("A Matroska TrackEntry has no CodecID.")
    if (fields.any { it.id == ID_CONTENT_ENCODINGS }) {
        error("Compressed or encrypted Matroska tracks are not converted.")
    }
    val codecPrivate =
        fields.singleOrNull { it.id == ID_CODEC_PRIVATE }?.let {
            readBytes(it, MAXIMUM_CODEC_PRIVATE_BYTES)
        } ?: ByteArray(0)
    val defaultDuration = fields.singleOrNull { it.id == ID_DEFAULT_DURATION }?.let { readUnsigned(it) }
    val codecDelay = fields.singleOrNull { it.id == ID_CODEC_DELAY }?.let { readUnsigned(it) } ?: 0L
    val seekPreRoll = fields.singleOrNull { it.id == ID_SEEK_PRE_ROLL }?.let { readUnsigned(it) } ?: 0L
    val timestampScale = fields.singleOrNull { it.id == ID_TRACK_TIMESTAMP_SCALE }?.let { readFloat(it) } ?: 1.0
    require(timestampScale.isFinite() && timestampScale > 0.0) { "A Matroska TrackTimestampScale is invalid." }
    val name = fields.singleOrNull { it.id == ID_NAME }?.let { readString(it, MAXIMUM_TEXT_BYTES) }
    val language =
        fields.singleOrNull { it.id == ID_LANGUAGE_BCP47 }?.let { readString(it, MAXIMUM_TEXT_BYTES) }
            ?: fields.singleOrNull { it.id == ID_LANGUAGE }?.let { readString(it, MAXIMUM_TEXT_BYTES) }
            ?: "und"
    val video = fields.singleOrNull { it.id == ID_VIDEO }
    val audio = fields.singleOrNull { it.id == ID_AUDIO }
    val videoFields = video?.let { children(it) }.orEmpty()
    val width =
        videoFields.singleOrNull { it.id == ID_PIXEL_WIDTH }?.let { readUnsigned(it).checkedInt("PixelWidth") } ?: 0
    val height =
        videoFields.singleOrNull { it.id == ID_PIXEL_HEIGHT }?.let { readUnsigned(it).checkedInt("PixelHeight") } ?: 0
    val colour = videoFields.singleOrNull { it.id == ID_COLOUR }?.let { parseColour(it) }
    val audioFields = audio?.let { children(it) }.orEmpty()
    val samplingFrequency =
        audioFields.singleOrNull { it.id == ID_SAMPLING_FREQUENCY }?.let { readFloat(it) } ?: 8_000.0
    val outputFrequency = audioFields.singleOrNull { it.id == ID_OUTPUT_SAMPLING_FREQUENCY }?.let { readFloat(it) }
    val sampleRate = (outputFrequency ?: samplingFrequency).roundToInt()
    val channels =
        audioFields.singleOrNull { it.id == ID_CHANNELS }?.let { readUnsigned(it).checkedInt("Channels") } ?: 1
    val bitDepth =
        audioFields.singleOrNull { it.id == ID_BIT_DEPTH }?.let { readUnsigned(it).checkedInt("BitDepth") } ?: 0
    val dovi = parseDolbyVisionMapping(fields)
    val audioCodec = codecId.toMatroskaAudioCodec()
    return RawMatroskaTrack(
        trackNumber = trackNumber,
        kind = kind,
        codecId = codecId,
        codecPrivate = codecPrivate,
        defaultDurationNs = defaultDuration,
        codecDelayNs = codecDelay,
        seekPreRollNs = seekPreRoll,
        timestampScale = timestampScale,
        name = name,
        language = language,
        width = width,
        height = height,
        colour = colour,
        dolbyVisionConfiguration = dovi,
        audioCodec = audioCodec,
        sampleRate = sampleRate,
        channels = channels,
        bitDepth = bitDepth,
    )
}

private suspend fun DolbyVisionRandomAccessDataSource.parseDolbyVisionMapping(fields: List<EbmlElement>): ByteArray? {
    val mappings = fields.filter { it.id == ID_BLOCK_ADDITION_MAPPING }
    val matches =
        mappings.mapNotNull { mapping ->
            val children = children(mapping)
            val type = children.singleOrNull { it.id == ID_BLOCK_ADD_ID_TYPE }?.let { readUnsigned(it) } ?: 0L
            if (type != BLOCK_ADD_TYPE_DVCC) return@mapNotNull null
            children.singleOrNull { it.id == ID_BLOCK_ADD_ID_EXTRA_DATA }?.let {
                readBytes(it, MAXIMUM_CODEC_PRIVATE_BYTES).withoutIsoBoxHeader(BOX_DVCC)
            }
        }
    require(matches.size <= 1) { "A Matroska track contains multiple dvcC mappings." }
    return matches.singleOrNull()
}

private suspend fun DolbyVisionRandomAccessDataSource.parseColour(element: EbmlElement): MatroskaColour {
    val fields = children(element)

    suspend fun unsigned(id: Long): Int? =
        fields.singleOrNull { it.id == id }?.let { readUnsigned(it).checkedInt("colour") }
    val masteringElement = fields.singleOrNull { it.id == ID_MASTERING_METADATA }
    val mastering = masteringElement?.let { parseMasteringMetadata(it) }
    return MatroskaColour(
        matrix = unsigned(ID_MATRIX_COEFFICIENTS) ?: 2,
        bitsPerChannel = unsigned(ID_BITS_PER_CHANNEL) ?: 0,
        range = unsigned(ID_RANGE) ?: 0,
        transfer = unsigned(ID_TRANSFER_CHARACTERISTICS) ?: 2,
        primaries = unsigned(ID_PRIMARIES) ?: 2,
        maxCll = unsigned(ID_MAX_CLL),
        maxFall = unsigned(ID_MAX_FALL),
        mastering = mastering,
    )
}

private suspend fun DolbyVisionRandomAccessDataSource.parseMasteringMetadata(
    element: EbmlElement,
): MatroskaMasteringMetadata {
    val fields = children(element)

    suspend fun value(id: Long): Double? = fields.singleOrNull { it.id == id }?.let { readFloat(it) }
    return MatroskaMasteringMetadata(
        redX = value(ID_PRIMARY_R_X),
        redY = value(ID_PRIMARY_R_Y),
        greenX = value(ID_PRIMARY_G_X),
        greenY = value(ID_PRIMARY_G_Y),
        blueX = value(ID_PRIMARY_B_X),
        blueY = value(ID_PRIMARY_B_Y),
        whiteX = value(ID_WHITE_X),
        whiteY = value(ID_WHITE_Y),
        maxLuminance = value(ID_LUMINANCE_MAX),
        minLuminance = value(ID_LUMINANCE_MIN),
    )
}

private fun selectMatroskaTracks(tracks: List<RawMatroskaTrack>): Pair<RawMatroskaTrack, List<RawMatroskaTrack>> {
    val videoCandidates =
        tracks.filter {
            it.kind == MatroskaTrackKind.VIDEO &&
                it.codecId == CODEC_HEVC &&
                it.dolbyVisionConfiguration != null
        }
    require(videoCandidates.size == 1) {
        if (videoCandidates.isEmpty()) {
            "No Dolby Vision HEVC track with a dvcC BlockAdditionMapping was found in Matroska."
        } else {
            "Multiple Dolby Vision Matroska tracks require explicit selection."
        }
    }
    val video = videoCandidates.single()
    require(video.width > 0 && video.height > 0) { "The Dolby Vision Matroska track has invalid dimensions." }
    require(video.codecPrivate.size >= HEVC_CONFIGURATION_MINIMUM_BYTES) {
        "The Matroska HEVCDecoderConfigurationRecord is truncated."
    }
    val nalLengthBytes = (video.codecPrivate[HEVC_LENGTH_SIZE_OFFSET].toInt() and 0x03) + 1
    require(nalLengthBytes == 4) { "Only four-byte HEVC NAL lengths are supported; found $nalLengthBytes." }
    val dovi = video.dolbyVisionConfiguration!!
    require(dovi.size >= MINIMUM_DOVI_CONFIGURATION_BYTES) { "The Matroska dvcC configuration is truncated." }
    val doviWord = ((dovi[2].toInt() and 0xff) shl 8) or (dovi[3].toInt() and 0xff)
    require((doviWord ushr 9) and 0x7f == 7) { "Dolby Vision Profile 7 Matroska input is required." }

    val audio = tracks.filter { it.kind == MatroskaTrackKind.AUDIO }
    audio.forEach { track ->
        require(track.audioCodec != null) {
            "Matroska audio codec ${track.codecId} cannot be preserved by the common CMAF bridge."
        }
        require(track.sampleRate in 1..MAXIMUM_AUDIO_SAMPLE_RATE && track.channels in 1..MAXIMUM_AUDIO_CHANNELS) {
            "A Matroska audio track has invalid sampling or channel metadata."
        }
        if (track.audioCodec == MatroskaAudioCodec.OPUS) {
            require(track.codecPrivate.startsWith(OPUS_HEAD)) { "A Matroska Opus track has no valid OpusHead." }
        }
    }
    return video to (listOf(video) + audio)
}

@Suppress("LongParameterList")
private suspend fun DolbyVisionRandomAccessDataSource.parseCluster(
    range: ClusterRange,
    timestampScaleNs: Long,
    selectedTracks: Map<Long, RawMatroskaTrack>,
    output: Map<Long, MutableList<RawMatroskaSample>>,
    encounterIndex: Long,
    maximumSamples: Int,
): Long {
    val children = children(range.element)
    val clusterTimestamp =
        children.singleOrNull { it.id == ID_CLUSTER_TIMESTAMP }?.let { readUnsigned(it) }
            ?: error("A Matroska Cluster has no Timestamp.")
    var nextEncounter = encounterIndex
    for (element in children) {
        nextEncounter =
            when (element.id) {
                ID_SIMPLE_BLOCK ->
                    appendSimpleBlock(
                        element = element,
                        selectedTracks = selectedTracks,
                        output = output,
                        clusterTimestamp = clusterTimestamp,
                        timestampScaleNs = timestampScaleNs,
                        encounterIndex = nextEncounter,
                        maximumSamples = maximumSamples,
                    )
                ID_BLOCK_GROUP ->
                    appendBlockGroup(
                        element = element,
                        selectedTracks = selectedTracks,
                        output = output,
                        clusterTimestamp = clusterTimestamp,
                        timestampScaleNs = timestampScaleNs,
                        encounterIndex = nextEncounter,
                        maximumSamples = maximumSamples,
                    )
                else -> nextEncounter
            }
    }
    return nextEncounter
}

@Suppress("LongParameterList")
private suspend fun DolbyVisionRandomAccessDataSource.appendSimpleBlock(
    element: EbmlElement,
    selectedTracks: Map<Long, RawMatroskaTrack>,
    output: Map<Long, MutableList<RawMatroskaSample>>,
    clusterTimestamp: Long,
    timestampScaleNs: Long,
    encounterIndex: Long,
    maximumSamples: Int,
): Long {
    val parsed = parseBlock(element, simpleBlock = true)
    val track = selectedTracks[parsed.trackNumber] ?: return encounterIndex
    return appendParsedBlock(
        source = this,
        parsed = parsed,
        track = track,
        clusterTimestamp = clusterTimestamp,
        timestampScaleNs = timestampScaleNs,
        explicitBlockDurationTicks = null,
        discardPaddingNs = 0,
        output = output.getValue(track.trackNumber),
        encounterIndex = encounterIndex,
        maximumSamples = maximumSamples,
    )
}

@Suppress("LongParameterList")
private suspend fun DolbyVisionRandomAccessDataSource.appendBlockGroup(
    element: EbmlElement,
    selectedTracks: Map<Long, RawMatroskaTrack>,
    output: Map<Long, MutableList<RawMatroskaSample>>,
    clusterTimestamp: Long,
    timestampScaleNs: Long,
    encounterIndex: Long,
    maximumSamples: Int,
): Long {
    val group = children(element)
    val blockElement =
        group.singleOrNull { it.id == ID_BLOCK }
            ?: error("A Matroska BlockGroup has no single Block.")
    val parsed = parseBlock(blockElement, simpleBlock = false)
    val track = selectedTracks[parsed.trackNumber] ?: return encounterIndex
    val duration = group.singleOrNull { it.id == ID_BLOCK_DURATION }?.let { readUnsigned(it) }
    val padding = group.singleOrNull { it.id == ID_DISCARD_PADDING }?.let { readSigned(it) } ?: 0L
    return appendParsedBlock(
        source = this,
        parsed = parsed.copy(keyframe = group.none { it.id == ID_REFERENCE_BLOCK }),
        track = track,
        clusterTimestamp = clusterTimestamp,
        timestampScaleNs = timestampScaleNs,
        explicitBlockDurationTicks = duration,
        discardPaddingNs = padding,
        output = output.getValue(track.trackNumber),
        encounterIndex = encounterIndex,
        maximumSamples = maximumSamples,
    )
}

@Suppress("LongParameterList")
private suspend fun appendParsedBlock(
    source: DolbyVisionRandomAccessDataSource,
    parsed: ParsedBlock,
    track: RawMatroskaTrack,
    clusterTimestamp: Long,
    timestampScaleNs: Long,
    explicitBlockDurationTicks: Long?,
    discardPaddingNs: Long,
    output: MutableList<RawMatroskaSample>,
    encounterIndex: Long,
    maximumSamples: Int,
): Long {
    require(track.kind != MatroskaTrackKind.VIDEO || parsed.frames.size == 1) {
        "Laced Dolby Vision video blocks are not supported."
    }
    val scaledRelative = (parsed.relativeTimestamp.toDouble() * track.timestampScale).roundToLong()
    val absoluteTicks = clusterTimestamp + scaledRelative
    require(absoluteTicks >= Long.MIN_VALUE / timestampScaleNs && absoluteTicks <= Long.MAX_VALUE / timestampScaleNs) {
        "A Matroska block timestamp overflows nanoseconds."
    }
    var presentationNs = absoluteTicks * timestampScaleNs - track.codecDelayNs
    val explicitTotalNs =
        explicitBlockDurationTicks?.let {
            (it.toDouble() * track.timestampScale * timestampScaleNs).roundToLong()
        }
    val durations = resolveFrameDurations(source, track, parsed.frames, explicitTotalNs)
    var nextEncounter = encounterIndex
    parsed.frames.forEachIndexed { index, frame ->
        require(nextEncounter < maximumSamples.toLong()) {
            "The selected Matroska tracks exceed the configured global sample-count limit."
        }
        var duration = durations[index]
        if (index == parsed.frames.lastIndex && discardPaddingNs > 0 && duration != null) {
            duration = (duration - discardPaddingNs).takeIf { it > 0 }
                ?: error("Matroska DiscardPadding removes the complete final frame.")
        }
        output +=
            RawMatroskaSample(
                offset = frame.offset,
                size = frame.size,
                presentationTimeNs = presentationNs,
                explicitDurationNs = duration,
                isSync = parsed.keyframe || track.kind == MatroskaTrackKind.AUDIO,
                encounterIndex = nextEncounter++,
            )
        presentationNs += duration ?: 0L
    }
    return nextEncounter
}

private suspend fun resolveFrameDurations(
    source: DolbyVisionRandomAccessDataSource,
    track: RawMatroskaTrack,
    frames: List<BlockFrame>,
    explicitTotalNs: Long?,
): MutableList<Long?> {
    val durations = frames.mapTo(mutableListOf()) { frame -> frameDurationNs(source, track, frame) }
    if (explicitTotalNs != null && explicitTotalNs > 0) {
        applyExplicitFrameDuration(durations, frames.size, explicitTotalNs)
    }
    if (frames.size > 1 && durations.any { it == null }) {
        error("Laced Matroska frames need DefaultDuration, BlockDuration, or a duration-known audio codec.")
    }
    return durations
}

private fun applyExplicitFrameDuration(
    durations: MutableList<Long?>,
    frameCount: Int,
    explicitTotalNs: Long,
) {
    val known = durations.mapNotNull { it }.sum()
    if (durations.all { it != null }) {
        if (frameCount == 1) durations[0] = explicitTotalNs
        return
    }
    val missing = durations.count { it == null }
    val remaining = explicitTotalNs - known
    require(remaining > 0) { "A Matroska BlockDuration is shorter than its known laced frames." }
    val base = remaining / missing
    var remainder = remaining % missing
    durations.indices.forEach { index ->
        if (durations[index] == null) {
            durations[index] = base + if (remainder-- > 0) 1 else 0
        }
    }
}

private suspend fun frameDurationNs(
    source: DolbyVisionRandomAccessDataSource,
    track: RawMatroskaTrack,
    frame: BlockFrame,
): Long? {
    if (track.audioCodec == MatroskaAudioCodec.AC3 || track.audioCodec == MatroskaAudioCodec.EAC3) {
        require(frame.size <= MAXIMUM_DOLBY_AUDIO_PACKET_BYTES) {
            "A Matroska Dolby audio packet exceeds its byte limit."
        }
        return parseDolbyAudioPacketConfiguration(
            source.read(frame.offset, frame.size),
            eac3 = track.audioCodec == MatroskaAudioCodec.EAC3,
        ).durationNs
    }
    track.defaultDurationNs?.let { return it }
    return when (track.audioCodec) {
        MatroskaAudioCodec.AAC -> aacFrameDurationNs(track)
        MatroskaAudioCodec.OPUS -> {
            val prefix = source.read(frame.offset, minOf(frame.size, OPUS_DURATION_PREFIX_BYTES))
            opusPacketDurationNs(prefix)
        }
        MatroskaAudioCodec.AC3,
        MatroskaAudioCodec.EAC3,
        -> error("Dolby audio duration was not parsed before DefaultDuration.")
        null -> null
    }
}

private fun aacFrameDurationNs(track: RawMatroskaTrack): Long {
    val samplesPerFrame = if (track.codecId.contains("SBR")) 2048L else 1024L
    return samplesPerFrame * NANOSECONDS_PER_SECOND / track.sampleRate
}

private fun opusPacketDurationNs(packet: ByteArray): Long {
    require(packet.isNotEmpty()) { "A Matroska Opus packet is empty." }
    val toc = packet[0].toInt() and 0xff
    val config = toc ushr 3
    val frameDurationUs =
        when {
            config < 12 ->
                when (config and 0x03) {
                    0 -> 10_000L
                    1 -> 20_000L
                    2 -> 40_000L
                    else -> 60_000L
                }
            config < 16 -> if (config and 0x01 == 0) 10_000L else 20_000L
            else ->
                when (config and 0x03) {
                    0 -> 2_500L
                    1 -> 5_000L
                    2 -> 10_000L
                    else -> 20_000L
                }
        }
    val frameCount =
        when (toc and 0x03) {
            0 -> 1
            1, 2 -> 2
            else -> {
                require(packet.size >= 2) { "A code-3 Opus packet is truncated." }
                packet[1].toInt() and 0x3f
            }
        }
    require(frameCount > 0 && frameDurationUs * frameCount <= MAXIMUM_OPUS_PACKET_DURATION_US) {
        "A Matroska Opus packet has an invalid duration."
    }
    return frameDurationUs * frameCount * NANOSECONDS_PER_MICROSECOND
}

private suspend fun DolbyVisionRandomAccessDataSource.parseBlock(
    element: EbmlElement,
    simpleBlock: Boolean,
): ParsedBlock {
    val size = element.dataSize?.checkedInt("Matroska block") ?: error("A Matroska Block has unknown size.")
    require(size >= MINIMUM_BLOCK_BYTES) { "A Matroska Block is truncated." }
    val prefix = read(element.dataOffset, minOf(size, MAXIMUM_LACING_HEADER_BYTES))
    val trackVint =
        prefix.readEbmlVint(0, preserveMarker = false)
            ?: error("A Matroska Block has an invalid TrackNumber.")
    require(!trackVint.unknown && trackVint.value > 0) { "A Matroska Block TrackNumber is invalid." }
    var cursor = trackVint.length
    require(cursor + 3 <= prefix.size) { "A Matroska Block header is truncated." }
    val timestamp = ((prefix[cursor].toInt() shl 8) or (prefix[cursor + 1].toInt() and 0xff)).toShort().toInt()
    cursor += 2
    val flags = prefix[cursor++].toInt() and 0xff
    val lacing = (flags ushr 1) and 0x03
    val keyframe = simpleBlock && flags and 0x80 != 0
    val frameSizes =
        when (lacing) {
            LACING_NONE -> listOf(size - cursor)
            LACING_XIPH -> parseXiphLacing(prefix, size, cursor)
            LACING_FIXED -> parseFixedLacing(prefix, size, cursor)
            LACING_EBML -> parseEbmlLacing(prefix, size, cursor)
            else -> error("Unknown Matroska lacing mode.")
        }
    val lacingHeaderBytes = lacingHeaderBytes(prefix, lacing, frameSizes.size, cursor)
    var frameOffset = element.dataOffset + lacingHeaderBytes
    val frames =
        frameSizes.map { frameSize ->
            require(frameSize > 0 && frameOffset <= element.endOffset - frameSize) {
                "A Matroska laced frame is outside its Block."
            }
            BlockFrame(frameOffset, frameSize).also { frameOffset += frameSize }
        }
    require(frameOffset == element.endOffset) { "Matroska lacing sizes do not consume the complete Block." }
    return ParsedBlock(trackVint.value, timestamp, keyframe, frames)
}

private fun parseXiphLacing(
    prefix: ByteArray,
    blockSize: Int,
    initialCursor: Int,
): List<Int> {
    var cursor = initialCursor
    require(cursor < prefix.size) { "A Xiph lace is truncated." }
    val count = (prefix[cursor++].toInt() and 0xff) + 1
    val sizes = mutableListOf<Int>()
    repeat(count - 1) {
        var size = 0L
        while (true) {
            require(cursor < prefix.size) { "A Xiph lace header exceeds its byte limit." }
            val value = prefix[cursor++].toInt() and 0xff
            size += value
            require(size <= Int.MAX_VALUE) { "A Xiph laced frame is too large." }
            if (value != 255) break
        }
        sizes += size.toInt()
    }
    val last = blockSize - cursor - sizes.sum()
    require(last > 0) { "A Xiph lace has invalid frame sizes." }
    return sizes + last
}

private fun parseFixedLacing(
    prefix: ByteArray,
    blockSize: Int,
    initialCursor: Int,
): List<Int> {
    require(initialCursor < prefix.size) { "A fixed lace is truncated." }
    val count = (prefix[initialCursor].toInt() and 0xff) + 1
    val payload = blockSize - initialCursor - 1
    require(payload > 0 && payload % count == 0) { "A fixed lace has unequal frame sizes." }
    return List(count) { payload / count }
}

private fun parseEbmlLacing(
    prefix: ByteArray,
    blockSize: Int,
    initialCursor: Int,
): List<Int> {
    var cursor = initialCursor
    require(cursor < prefix.size) { "An EBML lace is truncated." }
    val count = (prefix[cursor++].toInt() and 0xff) + 1
    val first =
        prefix.readEbmlVint(cursor, preserveMarker = false)
            ?: error("An EBML lace has no first frame size.")
    require(!first.unknown && first.value in 1..Int.MAX_VALUE) { "An EBML lace first frame size is invalid." }
    cursor += first.length
    val sizes = mutableListOf(first.value.toInt())
    repeat(count - 2) {
        val delta = prefix.readEbmlSignedVint(cursor) ?: error("An EBML lace size delta is invalid.")
        cursor += delta.second
        val size = sizes.last().toLong() + delta.first
        require(size in 1..Int.MAX_VALUE) { "An EBML laced frame size is invalid." }
        sizes += size.toInt()
    }
    val last = blockSize - cursor - sizes.sum()
    require(last > 0) { "An EBML lace has invalid frame sizes." }
    return sizes + last
}

private fun lacingHeaderBytes(
    prefix: ByteArray,
    lacing: Int,
    frameCount: Int,
    initialCursor: Int,
): Int {
    if (lacing == LACING_NONE) return initialCursor
    var cursor = initialCursor + 1
    when (lacing) {
        LACING_XIPH ->
            repeat(frameCount - 1) {
                while (true) {
                    require(cursor < prefix.size) { "A Xiph lace header exceeds its byte limit." }
                    if ((prefix[cursor++].toInt() and 0xff) != 255) break
                }
            }
        LACING_FIXED -> Unit
        LACING_EBML -> {
            val first =
                prefix.readEbmlVint(cursor, preserveMarker = false)
                    ?: error("An EBML lace has no first frame size.")
            cursor += first.length
            repeat(frameCount - 2) {
                val delta = prefix.readEbmlSignedVint(cursor) ?: error("An EBML lace size delta is invalid.")
                cursor += delta.second
            }
        }
    }
    return cursor
}

private suspend fun finalizeMatroskaTracks(
    source: DolbyVisionRandomAccessDataSource,
    tracks: List<RawMatroskaTrack>,
    samplesByTrack: Map<Long, List<RawMatroskaSample>>,
): List<MatroskaTrack> {
    val allSamples = samplesByTrack.values.flatten()
    require(allSamples.isNotEmpty()) { "The selected Matroska tracks contain no samples." }
    val originNs = allSamples.minOf(RawMatroskaSample::presentationTimeNs)
    return tracks.mapIndexed { index, track ->
        val raw = samplesByTrack.getValue(track.trackNumber)
        require(raw.isNotEmpty() || track.kind != MatroskaTrackKind.VIDEO) {
            "The selected Matroska video track contains no samples."
        }
        val dolbyAudioConfiguration =
            if (track.audioCodec == MatroskaAudioCodec.AC3 || track.audioCodec == MatroskaAudioCodec.EAC3) {
                val first = raw.firstOrNull() ?: error("A Matroska Dolby audio track contains no samples.")
                require(first.size <= MAXIMUM_DOLBY_AUDIO_PACKET_BYTES) {
                    "A Matroska Dolby audio packet exceeds its byte limit."
                }
                parseDolbyAudioPacketConfiguration(
                    source.read(first.offset, first.size),
                    eac3 = track.audioCodec == MatroskaAudioCodec.EAC3,
                ).also { configuration ->
                    require(configuration.sampleRate == track.sampleRate) {
                        "Matroska Dolby audio sample rate differs from its syncframe."
                    }
                }
            } else {
                null
            }
        val durations = resolveDurations(raw, track.defaultDurationNs)
        var videoDecodeCursor =
            raw
                .firstOrNull()
                ?.presentationTimeNs
                ?.minus(originNs)
                ?.coerceAtLeast(0) ?: 0L
        val samples =
            raw.mapIndexed { sampleIndex, sample ->
                val presentation = sample.presentationTimeNs - originNs
                require(presentation >= 0) { "A normalized Matroska PTS is negative." }
                val duration = durations[sampleIndex]
                val decode =
                    if (track.kind == MatroskaTrackKind.VIDEO) {
                        videoDecodeCursor.also { videoDecodeCursor += duration }
                    } else {
                        presentation
                    }
                val composition = presentation - decode
                require(duration in 1..UINT32_MAX_MATROSKA) { "A Matroska sample duration does not fit CMAF." }
                require(composition in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    "A Matroska composition offset does not fit CMAF."
                }
                MatroskaSample(
                    offset = sample.offset,
                    size = sample.size,
                    decodeTime = decode,
                    duration = duration,
                    compositionOffset = composition,
                    presentationTime = presentation,
                    isSync = sample.isSync,
                )
            }
        MatroskaTrack(
            trackNumber = track.trackNumber,
            trackId = index + 1,
            kind = track.kind,
            codecId = track.codecId,
            codecPrivate = dolbyAudioConfiguration?.codecBoxPayload ?: track.codecPrivate,
            defaultDurationNs = track.defaultDurationNs,
            codecDelayNs = track.codecDelayNs,
            seekPreRollNs = track.seekPreRollNs,
            timestampScale = track.timestampScale,
            name = track.name,
            language = track.language,
            width = track.width,
            height = track.height,
            colour = track.colour,
            dolbyVisionConfiguration = track.dolbyVisionConfiguration,
            audioCodec = track.audioCodec,
            sampleRate = track.sampleRate,
            channels = track.channels,
            bitDepth = track.bitDepth,
            samples = samples,
        )
    }
}

private fun resolveDurations(
    samples: List<RawMatroskaSample>,
    defaultDurationNs: Long?,
): List<Long> {
    if (samples.isEmpty()) return emptyList()
    val presentationOrder = samples.indices.sortedBy { samples[it].presentationTimeNs }
    val inferred = LongArray(samples.size)
    presentationOrder.zipWithNext().forEach { (current, next) ->
        val delta = samples[next].presentationTimeNs - samples[current].presentationTimeNs
        if (delta > 0) inferred[current] = delta
    }
    val fallback =
        samples.mapNotNull(RawMatroskaSample::explicitDurationNs).filter { it > 0 }.medianOrNull()
            ?: inferred.filter { it > 0 }.medianOrNull()
            ?: defaultDurationNs
            ?: error("A Matroska track has no usable sample duration.")
    return samples.indices.map { index ->
        samples[index].explicitDurationNs?.takeIf { it > 0 }
            ?: inferred[index].takeIf { it > 0 }
            ?: fallback
    }
}

private fun List<Long>.medianOrNull(): Long? =
    takeIf { it.isNotEmpty() }?.sorted()?.let { sorted -> sorted[sorted.size / 2] }

private fun LongArray.medianOrNull(): Long? = toList().medianOrNull()

private fun buildMatroskaFragmentPlans(
    tracks: List<MatroskaTrack>,
    video: MatroskaTrack,
    targetDurationUs: Long,
): List<MatroskaFragmentPlan> {
    val boundaries = mutableListOf(0)
    var lastBoundaryUs = video.samples.first().presentationTime / NANOSECONDS_PER_MICROSECOND
    video.samples.indices.drop(1).forEach { index ->
        val sample = video.samples[index]
        val timeUs = sample.presentationTime / NANOSECONDS_PER_MICROSECOND
        if (sample.isSync && timeUs - lastBoundaryUs >= targetDurationUs) {
            boundaries += index
            lastBoundaryUs = timeUs
        }
    }
    boundaries += video.samples.size
    return boundaries.zipWithNext().mapIndexed { fragmentIndex, (videoStart, videoEnd) ->
        val selectedVideo = video.samples.subList(videoStart, videoEnd)
        val startNs = selectedVideo.minOf(MatroskaSample::presentationTime)
        val endNs = selectedVideo.maxOf { it.presentationTime + it.duration }
        val indices = linkedMapOf<Int, List<Int>>()
        indices[video.trackId] = (videoStart until videoEnd).toList()
        tracks.filter { it.trackId != video.trackId }.forEach { track ->
            val selected =
                track.samples.indices.filter { index ->
                    val sample = track.samples[index]
                    val sampleEnd = sample.presentationTime + sample.duration
                    (sample.presentationTime >= startNs && sample.presentationTime < endNs) ||
                        (fragmentIndex == 0 && sample.presentationTime < startNs && sampleEnd > startNs)
                }
            if (selected.isNotEmpty()) indices[track.trackId] = selected
        }
        MatroskaFragmentPlan(
            index = fragmentIndex,
            startPresentationTimeUs = startNs / NANOSECONDS_PER_MICROSECOND,
            endPresentationTimeUs = (endNs + NANOSECONDS_PER_MICROSECOND - 1) / NANOSECONDS_PER_MICROSECOND,
            sampleIndicesByTrackId = indices,
        )
    }
}

private suspend fun DolbyVisionRandomAccessDataSource.children(element: EbmlElement): List<EbmlElement> {
    require(element.dataSize != null || element.id == ID_CLUSTER) {
        "Cannot enumerate an unknown-size nested EBML element."
    }
    val output = mutableListOf<EbmlElement>()
    var cursor = element.dataOffset
    while (cursor < element.endOffset) {
        val child = readEbmlElement(cursor, element.endOffset) ?: error("An EBML child is truncated.")
        require(child.dataSize != null) { "A nested unknown-size EBML element is unsupported." }
        output += child
        cursor = child.endOffset
    }
    require(cursor == element.endOffset) { "An EBML child structure is inconsistent." }
    return output
}

private suspend fun DolbyVisionRandomAccessDataSource.readEbmlElement(
    offset: Long,
    parentEnd: Long,
): EbmlElement? {
    if (offset >= parentEnd) return null
    val prefixLength = minOf(MAXIMUM_EBML_HEADER_BYTES.toLong(), parentEnd - offset).toInt()
    val prefix = read(offset, prefixLength)
    if (prefix.isEmpty()) return null
    val id = prefix.readEbmlVint(0, preserveMarker = true) ?: return null
    require(id.length <= MAXIMUM_EBML_ID_BYTES && !id.unknown) { "An EBML element ID is invalid." }
    val size = prefix.readEbmlVint(id.length, preserveMarker = false) ?: return null
    require(size.length <= MAXIMUM_EBML_SIZE_BYTES) { "An EBML size VINT is invalid." }
    val headerSize = id.length + size.length
    val dataOffset = offset + headerSize
    val endOffset =
        if (size.unknown) {
            parentEnd
        } else {
            require(size.value <= parentEnd - dataOffset) { "An EBML element exceeds its parent." }
            dataOffset + size.value
        }
    return EbmlElement(id.value, offset, headerSize, dataOffset, size.value.takeUnless { size.unknown }, endOffset)
}

private fun ByteArray.readEbmlVint(
    offset: Int,
    preserveMarker: Boolean,
): EbmlVint? {
    if (offset !in indices) return null
    val first = this[offset].toInt() and 0xff
    if (first == 0) return null
    var marker = 0x80
    var length = 1
    while (first and marker == 0) {
        marker = marker ushr 1
        length++
        if (marker == 0 || length > 8) return null
    }
    if (offset > size - length) return null
    var value = if (preserveMarker) first.toLong() else (first and (marker - 1)).toLong()
    for (index in 1 until length) value = (value shl 8) or (this[offset + index].toLong() and 0xff)
    val valueBits = 7 * length
    val unknown = !preserveMarker && valueBits < 63 && value == (1L shl valueBits) - 1
    return EbmlVint(value, length, unknown)
}

private fun ByteArray.readEbmlSignedVint(offset: Int): Pair<Long, Int>? {
    val encoded = readEbmlVint(offset, preserveMarker = false) ?: return null
    if (encoded.unknown) return null
    val bits = 7 * encoded.length
    if (bits >= 63) return null
    val bias = (1L shl (bits - 1)) - 1
    return (encoded.value - bias) to encoded.length
}

private suspend fun DolbyVisionRandomAccessDataSource.readUnsigned(element: EbmlElement): Long {
    val size = element.dataSize?.checkedInt("EBML unsigned integer") ?: error("Unknown EBML integer size.")
    require(size in 1..8) { "An EBML unsigned integer has invalid size." }
    val bytes = read(element.dataOffset, size)
    var value = 0L
    bytes.forEach { byte ->
        require(
            value <= (Long.MAX_VALUE - (byte.toInt() and 0xff)) ushr 8,
        ) { "An EBML integer exceeds signed 64-bit range." }
        value = (value shl 8) or (byte.toLong() and 0xff)
    }
    return value
}

private suspend fun DolbyVisionRandomAccessDataSource.readSigned(element: EbmlElement): Long {
    val size = element.dataSize?.checkedInt("EBML signed integer") ?: error("Unknown EBML integer size.")
    require(size in 1..8) { "An EBML signed integer has invalid size." }
    val bytes = read(element.dataOffset, size)
    var value = if (bytes.first().toInt() and 0x80 != 0) -1L else 0L
    bytes.forEach { byte -> value = (value shl 8) or (byte.toLong() and 0xff) }
    return value
}

private suspend fun DolbyVisionRandomAccessDataSource.readFloat(element: EbmlElement): Double {
    val size = element.dataSize?.checkedInt("EBML float") ?: error("Unknown EBML float size.")
    val bytes = read(element.dataOffset, size)
    return when (size) {
        4 -> Float.fromBits(bytes.readIntBigEndian()).toDouble()
        8 -> Double.fromBits(bytes.readLongBigEndian())
        else -> error("An EBML float must contain four or eight bytes.")
    }
}

private suspend fun DolbyVisionRandomAccessDataSource.readString(
    element: EbmlElement,
    maximumBytes: Int,
): String = readBytes(element, maximumBytes).decodeToString().trimEnd('\u0000')

private suspend fun DolbyVisionRandomAccessDataSource.readBytes(
    element: EbmlElement,
    maximumBytes: Int,
): ByteArray {
    val size = element.dataSize?.checkedInt("EBML binary element") ?: error("Unknown EBML binary size.")
    require(size <= maximumBytes) { "An EBML metadata element exceeds its byte limit." }
    return read(element.dataOffset, size)
}

private fun ByteArray.readIntBigEndian(): Int {
    require(size == 4)
    return fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
}

private fun ByteArray.readLongBigEndian(): Long {
    require(size == 8)
    return fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
}

private fun ByteArray.withoutIsoBoxHeader(expectedType: String): ByteArray {
    if (size < 8) return this
    val declared =
        ((this[0].toLong() and 0xff) shl 24) or
            ((this[1].toLong() and 0xff) shl 16) or
            ((this[2].toLong() and 0xff) shl 8) or
            (this[3].toLong() and 0xff)
    val type = copyOfRange(4, 8).decodeToString()
    return if (declared == size.toLong() && type == expectedType) copyOfRange(8, size) else this
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun String.toMatroskaAudioCodec(): MatroskaAudioCodec? =
    when {
        this == "A_AAC" || startsWith("A_AAC/") -> MatroskaAudioCodec.AAC
        this == "A_OPUS" -> MatroskaAudioCodec.OPUS
        this == "A_AC3" || startsWith("A_AC3/") -> MatroskaAudioCodec.AC3
        this == "A_EAC3" -> MatroskaAudioCodec.EAC3
        else -> null
    }

private fun Long.checkedInt(label: String): Int {
    require(this in 0..Int.MAX_VALUE) { "A Matroska $label value exceeds the supported range." }
    return toInt()
}

private fun matroskaFailure(message: String) = MatroskaParseResult.Failure(message)

internal const val MATROSKA_CMAF_TIMESCALE = 1_000_000_000L
private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
private const val MAXIMUM_TIMESTAMP_SCALE_NS = 1_000_000_000L
private const val NANOSECONDS_PER_SECOND = 1_000_000_000L
private const val NANOSECONDS_PER_MICROSECOND = 1_000L
private const val UINT32_MAX_MATROSKA = 0xffff_ffffL
private const val MAXIMUM_EBML_HEADER_BYTES = 12
private const val MAXIMUM_EBML_ID_BYTES = 4
private const val MAXIMUM_EBML_SIZE_BYTES = 8
private const val MAXIMUM_TEXT_BYTES = 64 * 1024
private const val MAXIMUM_CODEC_PRIVATE_BYTES = 4 * 1024 * 1024
private const val MAXIMUM_LACING_HEADER_BYTES = 64 * 1024
private const val MINIMUM_BLOCK_BYTES = 4
private const val HEVC_CONFIGURATION_MINIMUM_BYTES = 22
private const val HEVC_LENGTH_SIZE_OFFSET = 21
private const val MINIMUM_DOVI_CONFIGURATION_BYTES = 4
private const val MAXIMUM_AUDIO_SAMPLE_RATE = 65_535
private const val MAXIMUM_AUDIO_CHANNELS = 32
private const val OPUS_DURATION_PREFIX_BYTES = 2
private const val MAXIMUM_DOLBY_AUDIO_PACKET_BYTES = 1024 * 1024
private const val MAXIMUM_OPUS_PACKET_DURATION_US = 120_000L
private const val LACING_NONE = 0
private const val LACING_XIPH = 1
private const val LACING_FIXED = 2
private const val LACING_EBML = 3
private const val TRACK_TYPE_VIDEO = 1L
private const val TRACK_TYPE_AUDIO = 2L
private const val CODEC_HEVC = "V_MPEGH/ISO/HEVC"
private const val BOX_DVCC = "dvcC"
private val OPUS_HEAD = "OpusHead".encodeToByteArray()

private const val ID_EBML = 0x1A45DFA3L
private const val ID_DOC_TYPE = 0x4282L
private const val ID_SEGMENT = 0x18538067L
private const val ID_INFO = 0x1549A966L
private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
private const val ID_CLUSTER = 0x1F43B675L
private const val ID_CLUSTER_TIMESTAMP = 0xE7L
private const val ID_SIMPLE_BLOCK = 0xA3L
private const val ID_BLOCK_GROUP = 0xA0L
private const val ID_BLOCK = 0xA1L
private const val ID_BLOCK_DURATION = 0x9BL
private const val ID_REFERENCE_BLOCK = 0xFBL
private const val ID_DISCARD_PADDING = 0x75A2L
private const val ID_TRACKS = 0x1654AE6BL
private const val ID_TRACK_ENTRY = 0xAEL
private const val ID_TRACK_NUMBER = 0xD7L
private const val ID_TRACK_TYPE = 0x83L
private const val ID_FLAG_ENABLED = 0xB9L
private const val ID_DEFAULT_DURATION = 0x23E383L
private const val ID_TRACK_TIMESTAMP_SCALE = 0x23314FL
private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7L
private const val ID_BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL
private const val ID_NAME = 0x536EL
private const val ID_LANGUAGE = 0x22B59CL
private const val ID_LANGUAGE_BCP47 = 0x22B59DL
private const val ID_CODEC_ID = 0x86L
private const val ID_CODEC_PRIVATE = 0x63A2L
private const val ID_CODEC_DELAY = 0x56AAL
private const val ID_SEEK_PRE_ROLL = 0x56BBL
private const val ID_VIDEO = 0xE0L
private const val ID_PIXEL_WIDTH = 0xB0L
private const val ID_PIXEL_HEIGHT = 0xBAL
private const val ID_COLOUR = 0x55B0L
private const val ID_MATRIX_COEFFICIENTS = 0x55B1L
private const val ID_BITS_PER_CHANNEL = 0x55B2L
private const val ID_RANGE = 0x55B9L
private const val ID_TRANSFER_CHARACTERISTICS = 0x55BAL
private const val ID_PRIMARIES = 0x55BBL
private const val ID_MAX_CLL = 0x55BCL
private const val ID_MAX_FALL = 0x55BDL
private const val ID_MASTERING_METADATA = 0x55D0L
private const val ID_PRIMARY_R_X = 0x55D1L
private const val ID_PRIMARY_R_Y = 0x55D2L
private const val ID_PRIMARY_G_X = 0x55D3L
private const val ID_PRIMARY_G_Y = 0x55D4L
private const val ID_PRIMARY_B_X = 0x55D5L
private const val ID_PRIMARY_B_Y = 0x55D6L
private const val ID_WHITE_X = 0x55D7L
private const val ID_WHITE_Y = 0x55D8L
private const val ID_LUMINANCE_MAX = 0x55D9L
private const val ID_LUMINANCE_MIN = 0x55DAL
private const val ID_AUDIO = 0xE1L
private const val ID_SAMPLING_FREQUENCY = 0xB5L
private const val ID_OUTPUT_SAMPLING_FREQUENCY = 0x78B5L
private const val ID_CHANNELS = 0x9FL
private const val ID_BIT_DEPTH = 0x6264L
private const val ID_CONTENT_ENCODINGS = 0x6D80L
private const val BLOCK_ADD_TYPE_DVCC = 0x64766343L
private val MATROSKA_TOP_LEVEL_IDS =
    setOf(
        0x114D9B74L, // SeekHead
        ID_INFO,
        ID_CLUSTER,
        ID_TRACKS,
        0x1C53BB6BL, // Cues
        0x1941A469L, // Attachments
        0x1043A770L, // Chapters
        0x1254C367L, // Tags
    )
