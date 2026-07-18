@file:Suppress(
    "CyclomaticComplexMethod",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact random-access transport used by the bounded flat-MP4 fragmenter. */
interface DolbyVisionRandomAccessDataSource {
    suspend fun size(): Long

    /** Returns exactly [length] bytes starting at [offset], or throws. */
    suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray
}

class ByteArrayDolbyVisionDataSource(
    private val bytes: ByteArray,
) : DolbyVisionRandomAccessDataSource {
    override suspend fun size(): Long = bytes.size.toLong()

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray {
        require(offset in 0..bytes.size.toLong() && length >= 0 && offset <= bytes.size.toLong() - length) {
            "The requested byte range is outside the in-memory source."
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

data class FlatMp4DolbyVisionFragment(
    val index: Int,
    val startPresentationTimeUs: Long,
    val endPresentationTimeUs: Long,
    val startsWithRandomAccessPoint: Boolean,
)

sealed interface FlatMp4DolbyVisionOpenResult {
    data class Success(
        val session: FlatMp4DolbyVisionSession,
    ) : FlatMp4DolbyVisionOpenResult

    data class Failure(
        val message: String,
    ) : FlatMp4DolbyVisionOpenResult
}

sealed interface FlatMp4DolbyVisionFragmentResult {
    data class Success(
        val payload: ByteArray,
        val fragment: FlatMp4DolbyVisionFragment,
    ) : FlatMp4DolbyVisionFragmentResult

    data class Failure(
        val message: String,
    ) : FlatMp4DolbyVisionFragmentResult
}

/** Converts unencrypted, non-fragmented MP4 into lazy CMAF fragments without decoding picture or audio. */
object FlatMp4DolbyVisionAdapter {
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    suspend fun open(
        source: DolbyVisionRandomAccessDataSource,
        converter: DolbyVisionRpuConverter,
        enhancementLayer: DolbyVisionEnhancementLayer,
        targetFragmentDurationUs: Long = DEFAULT_TARGET_FRAGMENT_DURATION_US,
        maximumInitializationBytes: Int = DEFAULT_MAXIMUM_MP4_INITIALIZATION_BYTES,
        maximumFragmentBytes: Int = DEFAULT_MAXIMUM_MP4_FRAGMENT_BYTES,
        maximumSamples: Int = DEFAULT_MAXIMUM_MP4_SAMPLES,
        maximumBufferedFragments: Int = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_FRAGMENTS,
        maximumBufferedBytes: Long = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_BYTES,
    ): FlatMp4DolbyVisionOpenResult {
        if (targetFragmentDurationUs <= 0 ||
            maximumInitializationBytes <= 0 ||
            maximumFragmentBytes <= 0 ||
            maximumSamples <= 0
        ) {
            return mp4OpenFailure("MP4 duration and resource limits must be positive.")
        }
        val parsed =
            try {
                parseFlatMp4(source, maximumInitializationBytes, targetFragmentDurationUs, maximumSamples)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return mp4OpenFailure("Unable to parse flat MP4: ${error.message ?: error::class.simpleName}.")
            }
        val movie =
            when (parsed) {
                is FlatMp4ParseResult.Success -> parsed.movie
                is FlatMp4ParseResult.Failure -> return mp4OpenFailure(parsed.message)
            }
        val prepared =
            when (val result = CmafDolbyVisionInitializationSegment.prepareProfile81(movie.fragmentedInitialization)) {
                is CmafDolbyVisionInitializationResult.Success -> result.configuration
                is CmafDolbyVisionInitializationResult.Failure -> return mp4OpenFailure(result.message)
            }
        val request =
            try {
                DolbyVisionConversionRequest(
                    container = DolbyVisionContainer.MP4,
                    profile = prepared.sourceProfile,
                    hasRpu = true,
                    enhancementLayer = enhancementLayer,
                    maximumBufferedFragments = maximumBufferedFragments,
                    maximumBufferedBytes = maximumBufferedBytes,
                )
            } catch (error: IllegalArgumentException) {
                return mp4OpenFailure(error.message ?: "Invalid MP4 buffer limits.")
            }
        return FlatMp4DolbyVisionOpenResult.Success(
            FlatMp4DolbyVisionSession(
                source = source,
                movie = movie,
                configuration = prepared,
                converter = converter,
                request = request,
                maximumFragmentBytes = maximumFragmentBytes,
            ),
        )
    }
}

class FlatMp4DolbyVisionSession internal constructor(
    private val source: DolbyVisionRandomAccessDataSource,
    private val movie: ParsedFlatMp4,
    configuration: CmafDolbyVisionTrackConfiguration,
    converter: DolbyVisionRpuConverter,
    request: DolbyVisionConversionRequest,
    private val maximumFragmentBytes: Int,
) {
    val initializationSegment: ByteArray = configuration.rewrittenInitializationSegment.copyOf()
    val fragments: List<FlatMp4DolbyVisionFragment> = movie.fragments.map(FlatMp4FragmentPlan::publicDescription)

    private val configuration = configuration
    private val demuxer = CmafDolbyVisionFragmentAdapter(configuration, maximumFragmentBytes)
    private val bridge =
        DolbyVisionStreamingBridge(
            request = request,
            converter = converter,
            remuxer = CmafDolbyVisionFragmentRemuxer(configuration, maximumFragmentBytes),
        )
    private val mutex = Mutex()
    private var lastConvertedIndex: Int? = null

    @Suppress("TooGenericExceptionCaught")
    suspend fun convertFragment(index: Int): FlatMp4DolbyVisionFragmentResult =
        mutex.withLock {
            val plan =
                movie.fragments.getOrNull(index)
                    ?: return@withLock FlatMp4DolbyVisionFragmentResult.Failure(
                        "MP4 fragment index $index is out of range.",
                    )
            if (lastConvertedIndex?.plus(1) != index) {
                demuxer.reset()
                bridge.reset()
            }
            val cmaf =
                try {
                    buildCmafFragment(source, movie, plan, maximumFragmentBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock FlatMp4DolbyVisionFragmentResult.Failure(
                        "Unable to read MP4 fragment $index: ${error.message ?: error::class.simpleName}.",
                    )
                }
            val demuxed =
                when (val result = demuxer.demux(cmaf)) {
                    is CmafDolbyVisionDemuxResult.Success -> result.fragment
                    is CmafDolbyVisionDemuxResult.Failure ->
                        return@withLock FlatMp4DolbyVisionFragmentResult.Failure(result.message)
                }
            val converted =
                when (val result = bridge.convert(demuxed)) {
                    is DolbyVisionFragmentConversionResult.Success -> result.value.fragment.payload
                    is DolbyVisionFragmentConversionResult.Failure ->
                        return@withLock FlatMp4DolbyVisionFragmentResult.Failure(result.message)
                }
            lastConvertedIndex = index
            FlatMp4DolbyVisionFragmentResult.Success(converted, plan.publicDescription())
        }

    fun restartFragmentIndexForSeek(targetPresentationTimeUs: Long): Int {
        require(targetPresentationTimeUs >= 0) { "targetPresentationTimeUs must be non-negative." }
        return fragments.indexOfLast { it.startPresentationTimeUs <= targetPresentationTimeUs }.coerceAtLeast(0)
    }

    suspend fun resetForSeek(targetPresentationTimeUs: Long): Int =
        mutex.withLock {
            val index = restartFragmentIndexForSeek(targetPresentationTimeUs)
            demuxer.reset()
            bridge.reset()
            lastConvertedIndex = null
            index
        }
}

private sealed interface FlatMp4ParseResult {
    data class Success(
        val movie: ParsedFlatMp4,
    ) : FlatMp4ParseResult

    data class Failure(
        val message: String,
    ) : FlatMp4ParseResult
}

internal data class ParsedFlatMp4(
    val tracks: List<FlatMp4Track>,
    val videoTrack: FlatMp4Track,
    val fragmentedInitialization: ByteArray,
    val fragments: List<FlatMp4FragmentPlan>,
)

internal data class FlatMp4Track(
    val trackId: Int,
    val timescale: Long,
    val handlerType: String,
    val samples: List<FlatMp4Sample>,
)

internal data class FlatMp4Sample(
    val offset: Long,
    val size: Int,
    val decodeTime: Long,
    val duration: Long,
    val compositionOffset: Long,
    val isSync: Boolean,
) {
    val presentationTime: Long get() = decodeTime + compositionOffset
}

internal data class FlatMp4FragmentPlan(
    val index: Int,
    val startPresentationTimeUs: Long,
    val endPresentationTimeUs: Long,
    val samplesByTrackId: Map<Int, IntRange>,
) {
    fun publicDescription() =
        FlatMp4DolbyVisionFragment(
            index = index,
            startPresentationTimeUs = startPresentationTimeUs,
            endPresentationTimeUs = endPresentationTimeUs,
            startsWithRandomAccessPoint = true,
        )
}

private data class SourceBox(
    val offset: Long,
    val size: Long,
    val headerSize: Int,
    val type: String,
)

private data class Mp4TrackBoxes(
    val track: IsoBmffBox,
    val tkhd: IsoBmffBox,
    val mdia: IsoBmffBox,
    val mdhd: IsoBmffBox,
    val minf: IsoBmffBox,
    val stbl: IsoBmffBox,
    val stsd: IsoBmffBox,
)

@Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
private suspend fun parseFlatMp4(
    source: DolbyVisionRandomAccessDataSource,
    maximumInitializationBytes: Int,
    targetFragmentDurationUs: Long,
    maximumSamples: Int,
): FlatMp4ParseResult {
    val sourceSize = source.size()
    if (sourceSize <= 0) return flatMp4Failure("The MP4 source is empty.")
    val top =
        scanSourceBoxes(source, sourceSize)
            ?: return flatMp4Failure("The MP4 top-level box structure is invalid.")
    if (top.any { it.type in MP4_ENCRYPTION_TOP_LEVEL_BOXES }) {
        return flatMp4Failure(
            "Encrypted/DRM MP4 is not converted.",
        )
    }
    if (top.any {
            it.type == BOX_MOOF
        }
    ) {
        return flatMp4Failure("The source is already fragmented MP4; use the CMAF adapter.")
    }
    val moovSource =
        top.singleOrNull { it.type == BOX_MOOV }
            ?: return flatMp4Failure("A single moov box is required.")
    if (moovSource.size > maximumInitializationBytes || moovSource.size > Int.MAX_VALUE) {
        return flatMp4Failure("The MP4 moov box exceeds the initialization byte limit.")
    }
    val moovBytes = source.read(moovSource.offset, moovSource.size.toInt())
    if (moovBytes.size != moovSource.size.toInt()) return flatMp4Failure("The MP4 moov read was truncated.")
    val moov =
        when (val parsed = moovBytes.parseIsoBmffBoxes()) {
            is IsoBmffParseResult.Success -> parsed.boxes.singleOrNull { it.type == BOX_MOOV }
            is IsoBmffParseResult.Failure -> null
        } ?: return flatMp4Failure("The MP4 moov box is malformed.")
    val moovChildren = moovBytes.childrenOf(moov) ?: return flatMp4Failure("The MP4 moov children are malformed.")
    if (moovChildren.any { it.type == BOX_PSSH }) return flatMp4Failure("Encrypted/DRM MP4 is not converted.")
    val trackBoxes = mutableListOf<Mp4TrackBoxes>()
    val tracks = mutableListOf<FlatMp4Track>()
    var totalSamples = 0
    for (trak in moovChildren.filter { it.type == BOX_TRAK }) {
        if (moovBytes.childrenOf(trak)?.any { it.type == BOX_EDTS } == true) {
            return flatMp4Failure("MP4 edit lists are not rewritten because dropping them would change PTS.")
        }
        val boxes =
            locateTrackBoxes(moovBytes, trak) ?: return flatMp4Failure("An MP4 track has incomplete sample tables.")
        val remainingSamples = maximumSamples - totalSamples
        val track =
            parseFlatMp4Track(moovBytes, boxes, remainingSamples)
                ?: return flatMp4Failure("An MP4 track has unsupported or inconsistent sample tables.")
        totalSamples += track.samples.size
        trackBoxes += boxes
        tracks += track
    }
    if (tracks.isEmpty()) return flatMp4Failure("The MP4 source contains no tracks.")
    val videoCandidates =
        trackBoxes.zip(tracks).filter { (boxes, track) ->
            track.handlerType == HANDLER_VIDEO && moovBytes.stsdContainsDolbyVision(boxes.stsd)
        }
    if (videoCandidates.size != 1) {
        return flatMp4Failure(
            if (videoCandidates.isEmpty()) {
                "No Dolby Vision HEVC track was found."
            } else {
                "Multiple Dolby Vision tracks require explicit selection."
            },
        )
    }
    val videoTrack = videoCandidates.single().second
    if (videoTrack.samples.isEmpty() || !videoTrack.samples.first().isSync) {
        return flatMp4Failure("The first Dolby Vision sample must be a random-access point.")
    }
    val ftypSource = top.firstOrNull { it.type == BOX_FTYP }
    if (ftypSource != null && ftypSource.size > maximumInitializationBytes) {
        return flatMp4Failure("The MP4 ftyp box exceeds the initialization byte limit.")
    }
    val ftyp = ftypSource?.let { source.read(it.offset, it.size.toInt()) } ?: ByteArray(0)
    val fragmentedMoov =
        buildFragmentedMoov(
            bytes = moovBytes,
            moovChildren = moovChildren,
            trackBoxes = trackBoxes,
            tracks = tracks,
            maximumBytes = maximumInitializationBytes,
        )
    val fragments = buildFlatMp4FragmentPlans(tracks, videoTrack, targetFragmentDurationUs)
    if (fragments.isEmpty()) return flatMp4Failure("The MP4 source has no fragmentable video samples.")
    return FlatMp4ParseResult.Success(
        ParsedFlatMp4(
            tracks = tracks,
            videoTrack = videoTrack,
            fragmentedInitialization =
                flatMp4Concat(listOf(ftyp, fragmentedMoov), maximumInitializationBytes),
            fragments = fragments,
        ),
    )
}

private suspend fun scanSourceBoxes(
    source: DolbyVisionRandomAccessDataSource,
    sourceSize: Long,
): List<SourceBox>? {
    val boxes = mutableListOf<SourceBox>()
    var offset = 0L
    while (offset < sourceSize) {
        if (sourceSize - offset < ISO_BOX_HEADER_BYTES) return null
        val header = source.read(offset, minOf(16L, sourceSize - offset).toInt())
        if (header.size < ISO_BOX_HEADER_BYTES) return null
        val shortSize = header.readUnsignedInt(0)
        val type = header.readFourCc(4)
        val headerSize: Int
        val size =
            when (shortSize) {
                0L -> {
                    headerSize = ISO_BOX_HEADER_BYTES
                    sourceSize - offset
                }
                1L -> {
                    if (header.size < 16) return null
                    headerSize = 16
                    header.readUnsignedLong(8)
                }
                else -> {
                    headerSize = ISO_BOX_HEADER_BYTES
                    shortSize
                }
            }
        if (size < headerSize || size > sourceSize - offset) return null
        boxes += SourceBox(offset, size, headerSize, type)
        offset += size
    }
    return boxes
}

private fun locateTrackBoxes(
    bytes: ByteArray,
    track: IsoBmffBox,
): Mp4TrackBoxes? {
    val trackChildren = bytes.childrenOf(track) ?: return null
    val tkhd = trackChildren.singleOrNull { it.type == BOX_TKHD } ?: return null
    val mdia = trackChildren.singleOrNull { it.type == BOX_MDIA } ?: return null
    val mdiaChildren = bytes.childrenOf(mdia) ?: return null
    val mdhd = mdiaChildren.singleOrNull { it.type == BOX_MDHD } ?: return null
    val minf = mdiaChildren.singleOrNull { it.type == BOX_MINF } ?: return null
    val minfChildren = bytes.childrenOf(minf) ?: return null
    val stbl = minfChildren.singleOrNull { it.type == BOX_STBL } ?: return null
    val stblChildren = bytes.childrenOf(stbl) ?: return null
    val stsd = stblChildren.singleOrNull { it.type == BOX_STSD } ?: return null
    return Mp4TrackBoxes(track, tkhd, mdia, mdhd, minf, stbl, stsd)
}

@Suppress("LongMethod", "ReturnCount")
private fun parseFlatMp4Track(
    bytes: ByteArray,
    boxes: Mp4TrackBoxes,
    maximumSamples: Int,
): FlatMp4Track? {
    val trackId = bytes.readTrackId(boxes.tkhd) ?: return null
    val timescale = bytes.readMediaTimescale(boxes.mdhd) ?: return null
    val mdiaChildren = bytes.childrenOf(boxes.mdia) ?: return null
    val hdlr = mdiaChildren.singleOrNull { it.type == BOX_HDLR } ?: return null
    if (hdlr.contentSize < 12) return null
    val handler = bytes.readFourCc(hdlr.contentOffset + 8)
    val stblChildren = bytes.childrenOf(boxes.stbl) ?: return null
    if (stblChildren.any { it.type in MP4_ENCRYPTION_SAMPLE_BOXES }) return null
    val stts = stblChildren.singleOrNull { it.type == BOX_STTS } ?: return null
    val stsc = stblChildren.singleOrNull { it.type == BOX_STSC } ?: return null
    val chunkOffsetsBox = stblChildren.singleOrNull { it.type == BOX_STCO || it.type == BOX_CO64 } ?: return null
    val sampleSizesBox = stblChildren.singleOrNull { it.type == BOX_STSZ || it.type == BOX_STZ2 } ?: return null
    val sizes = bytes.parseSampleSizes(sampleSizesBox, maximumSamples) ?: return null
    val durations = bytes.expandTimeTable(stts, sizes.size, signed = false) ?: return null
    val ctts = stblChildren.singleOrNull { it.type == BOX_CTTS }
    val composition =
        if (ctts == null) {
            List(sizes.size) { 0L }
        } else {
            bytes.expandTimeTable(ctts, sizes.size, signed = true) ?: return null
        }
    val chunks = bytes.parseChunkOffsets(chunkOffsetsBox, sizes.size) ?: return null
    val chunkMap = bytes.parseSampleToChunk(stsc, sizes.size) ?: return null
    val offsets = buildSampleOffsets(sizes, chunks, chunkMap) ?: return null
    val stss = stblChildren.singleOrNull { it.type == BOX_STSS }
    val syncSamples = if (stss == null) null else bytes.parseSyncSamples(stss, sizes.size) ?: return null
    if (stblChildren.any { it.type == BOX_SAIZ || it.type == BOX_SAIO || it.type == BOX_SENC }) return null
    val samples = ArrayList<FlatMp4Sample>(sizes.size)
    var decodeTime = 0L
    sizes.indices.forEach { index ->
        val duration = durations[index]
        if (duration <= 0) return null
        samples +=
            FlatMp4Sample(
                offset = offsets[index],
                size = sizes[index],
                decodeTime = decodeTime,
                duration = duration,
                compositionOffset = composition[index],
                isSync = syncSamples == null || index + 1 in syncSamples,
            )
        decodeTime += duration
    }
    return FlatMp4Track(trackId, timescale, handler, samples)
}

private data class SampleToChunkEntry(
    val firstChunk: Int,
    val samplesPerChunk: Int,
)

private fun ByteArray.parseSampleSizes(
    box: IsoBmffBox,
    maximumSamples: Int,
): List<Int>? {
    require(maximumSamples >= 0)
    if (box.type == BOX_STSZ) {
        if (box.contentSize < 12) return null
        val fixedSize = readUnsignedInt(box.contentOffset + 4)
        val declaredCount = readUnsignedInt(box.contentOffset + 8)
        require(declaredCount <= maximumSamples) { "The global MP4 sample-count limit was exceeded." }
        val count = declaredCount.toInt()
        if (fixedSize > 0) {
            val size = fixedSize.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
            return List(count) { size }
        }
        if (box.contentOffset + 12L + count.toLong() * 4 > box.endOffset) return null
        return List(count) { index ->
            readUnsignedInt(box.contentOffset + 12 + index * 4).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                ?: return null
        }
    }
    if (box.contentSize < 12) return null
    val fieldSize = this[box.contentOffset + 7].toInt() and 0xff
    val declaredCount = readUnsignedInt(box.contentOffset + 8)
    require(declaredCount <= maximumSamples) { "The global MP4 sample-count limit was exceeded." }
    val count = declaredCount.toInt()
    val start = box.contentOffset + 12
    return when (fieldSize) {
        4 ->
            List(count) { index ->
                val value = this[start + index / 2].toInt() and 0xff
                if (index % 2 == 0) value ushr 4 else value and 0x0f
            }
        8 -> List(count) { index -> this[start + index].toInt() and 0xff }
        16 ->
            List(count) { index ->
                val offset = start + index * 2
                ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
            }
        else -> null
    }?.takeIf { values -> values.all { it > 0 } }
}

private fun ByteArray.expandTimeTable(
    box: IsoBmffBox,
    expectedSamples: Int,
    signed: Boolean,
): List<Long>? {
    if (box.contentSize < 8) return null
    val version = this[box.contentOffset].toInt() and 0xff
    val entryCount =
        readUnsignedInt(box.contentOffset + 4).takeIf { it <= expectedSamples.coerceAtLeast(1) }?.toInt() ?: return null
    if (box.contentOffset + 8L + entryCount.toLong() * 8 > box.endOffset) return null
    val result = ArrayList<Long>(expectedSamples)
    repeat(entryCount) { index ->
        val offset = box.contentOffset + 8 + index * 8
        val count = readUnsignedInt(offset).takeIf { it <= expectedSamples }?.toInt() ?: return null
        val raw = if (signed && version == 1) readSignedInt(offset + 4).toLong() else readUnsignedInt(offset + 4)
        if (!signed && raw <= 0) return null
        repeat(count) {
            if (result.size >= expectedSamples) return null
            result += raw
        }
    }
    return result.takeIf { it.size == expectedSamples }
}

private fun ByteArray.parseChunkOffsets(
    box: IsoBmffBox,
    maximumEntries: Int,
): List<Long>? {
    if (box.contentSize < 8) return null
    val count =
        readUnsignedInt(box.contentOffset + 4).takeIf { it <= maximumEntries.coerceAtLeast(1) }?.toInt() ?: return null
    val width = if (box.type == BOX_CO64) 8 else 4
    if (box.contentOffset + 8L + count.toLong() * width > box.endOffset) return null
    return List(count) { index ->
        val offset = box.contentOffset + 8 + index * width
        if (width == 8) readUnsignedLong(offset) else readUnsignedInt(offset)
    }
}

private fun ByteArray.parseSampleToChunk(
    box: IsoBmffBox,
    maximumEntries: Int,
): List<SampleToChunkEntry>? {
    if (box.contentSize < 8) return null
    val count =
        readUnsignedInt(box.contentOffset + 4).takeIf { it <= maximumEntries.coerceAtLeast(1) }?.toInt() ?: return null
    if (box.contentOffset + 8L + count.toLong() * 12 > box.endOffset) return null
    val result =
        List(count) { index ->
            val offset = box.contentOffset + 8 + index * 12
            val first = readUnsignedInt(offset).takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
            val samples = readUnsignedInt(offset + 4).takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
            val description = readUnsignedInt(offset + 8)
            if (description != 1L) return null
            SampleToChunkEntry(first, samples)
        }
    return result.takeIf { entries ->
        entries.firstOrNull()?.firstChunk == 1 &&
            entries.zipWithNext().all { it.first.firstChunk < it.second.firstChunk }
    }
}

private fun buildSampleOffsets(
    sizes: List<Int>,
    chunks: List<Long>,
    mapping: List<SampleToChunkEntry>,
): List<Long>? {
    val result = ArrayList<Long>(sizes.size)
    var sampleIndex = 0
    chunks.indices.forEach { chunkIndex ->
        val chunkNumber = chunkIndex + 1
        val entry = mapping.lastOrNull { it.firstChunk <= chunkNumber } ?: return null
        var offset = chunks[chunkIndex]
        repeat(entry.samplesPerChunk) {
            if (sampleIndex >= sizes.size) return null
            result += offset
            offset += sizes[sampleIndex]
            sampleIndex++
        }
    }
    return result.takeIf { sampleIndex == sizes.size }
}

private fun ByteArray.parseSyncSamples(
    box: IsoBmffBox,
    maximumEntries: Int,
): Set<Int>? {
    if (box.contentSize < 8) return null
    val count = readUnsignedInt(box.contentOffset + 4).takeIf { it <= maximumEntries }?.toInt() ?: return null
    if (box.contentOffset + 8L + count.toLong() * 4 > box.endOffset) return null
    return buildSet(count) {
        repeat(count) { index ->
            add(
                readUnsignedInt(box.contentOffset + 8 + index * 4).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                    ?: return null,
            )
        }
    }
}

private fun buildFlatMp4FragmentPlans(
    tracks: List<FlatMp4Track>,
    video: FlatMp4Track,
    targetDurationUs: Long,
): List<FlatMp4FragmentPlan> {
    val boundaries = mutableListOf(0)
    var lastBoundaryUs =
        video.samples
            .first()
            .presentationTime
            .toUs(video.timescale)
    video.samples.indices.drop(1).forEach { index ->
        val sample = video.samples[index]
        val timeUs = sample.presentationTime.toUs(video.timescale)
        if (sample.isSync && timeUs - lastBoundaryUs >= targetDurationUs) {
            boundaries += index
            lastBoundaryUs = timeUs
        }
    }
    boundaries += video.samples.size
    return boundaries.zipWithNext().mapIndexed { fragmentIndex, (videoStart, videoEnd) ->
        val selectedVideo = video.samples.subList(videoStart, videoEnd)
        val startUs = selectedVideo.minOf { it.presentationTime.toUs(video.timescale) }
        val endUs = selectedVideo.maxOf { (it.presentationTime + it.duration).toUs(video.timescale) }
        val ranges = linkedMapOf<Int, IntRange>()
        ranges[video.trackId] = videoStart until videoEnd
        tracks.filter { it.trackId != video.trackId }.forEach { track ->
            val indices =
                track.samples.indices.filter { index ->
                    val sample = track.samples[index]
                    val sampleStart = sample.presentationTime.toUs(track.timescale)
                    val sampleEnd = (sample.presentationTime + sample.duration).toUs(track.timescale)
                    (sampleStart >= startUs && sampleStart < endUs) ||
                        (fragmentIndex == 0 && sampleStart < startUs && sampleEnd > startUs)
                }
            if (indices.isNotEmpty()) ranges[track.trackId] = indices.first()..indices.last()
        }
        FlatMp4FragmentPlan(fragmentIndex, startUs, endUs, ranges)
    }
}

@Suppress("LongMethod")
private suspend fun buildCmafFragment(
    source: DolbyVisionRandomAccessDataSource,
    movie: ParsedFlatMp4,
    plan: FlatMp4FragmentPlan,
    maximumBytes: Int,
): ByteArray {
    data class TrackPayload(
        val track: FlatMp4Track,
        val samples: List<FlatMp4Sample>,
        val size: Int,
    )

    val trackPayloads = mutableListOf<TrackPayload>()
    var payloadBytes = 0L
    movie.tracks.forEach { track ->
        val range = plan.samplesByTrackId[track.trackId] ?: return@forEach
        val selected = range.map(track.samples::get)
        val size = selected.sumOf { it.size.toLong() }
        if (size > Int.MAX_VALUE ||
            payloadBytes + size > maximumBytes
        ) {
            error("The MP4 fragment exceeds the byte limit.")
        }
        payloadBytes += size
        trackPayloads += TrackPayload(track, selected, size.toInt())
    }

    fun makeMoof(dataOffsets: Map<Int, Int>): ByteArray {
        val mfhd = fullBox(BOX_MFHD, uint32((plan.index + 1).toLong()))
        val trafs =
            flatMp4Concat(
                trackPayloads.map { payload ->
                    val samples = payload.samples
                    val tfhd =
                        fullBox(
                            BOX_TFHD,
                            uint32(payload.track.trackId.toLong()),
                            flags = TFHD_DEFAULT_BASE_IS_MOOF,
                        )
                    val tfdt = fullBox(BOX_TFDT, uint64(samples.first().decodeTime), version = 1)
                    val entries = buildFlatMp4SampleEntries(samples, maximumBytes)
                    val trun =
                        fullBox(
                            BOX_TRUN,
                            uint32(samples.size.toLong()) + int32(dataOffsets[payload.track.trackId]?.toLong() ?: 0L) +
                                entries,
                            version = 1,
                            flags = TRUN_ALL_SAMPLE_FIELDS,
                        )
                    box(BOX_TRAF, flatMp4Concat(listOf(tfhd, tfdt, trun), maximumBytes))
                },
                maximumBytes,
            )
        return box(BOX_MOOF, mfhd + trafs)
    }

    val placeholder = makeMoof(emptyMap())
    var dataOffset = placeholder.size + ISO_BOX_HEADER_BYTES
    val offsets = linkedMapOf<Int, Int>()
    trackPayloads.forEach { payload ->
        offsets[payload.track.trackId] = dataOffset
        dataOffset += payload.size
    }
    val moof = makeMoof(offsets)
    val resultSize = moof.size.toLong() + ISO_BOX_HEADER_BYTES + payloadBytes
    if (resultSize > maximumBytes) error("The MP4 fragment exceeds the byte limit.")
    val result = ByteArray(resultSize.toInt())
    moof.copyInto(result)
    uint32(payloadBytes + ISO_BOX_HEADER_BYTES).copyInto(result, moof.size)
    BOX_MDAT.encodeToByteArray().copyInto(result, moof.size + FLAT_MP4_UINT32_BYTES)
    var cursor = moof.size + ISO_BOX_HEADER_BYTES
    trackPayloads.forEach { payload ->
        payload.samples.forEach { sample ->
            val sampleBytes = source.read(sample.offset, sample.size)
            if (sampleBytes.size != sample.size) error("A source MP4 sample read was truncated.")
            sampleBytes.copyInto(result, cursor)
            cursor += sampleBytes.size
        }
    }
    check(cursor == result.size) { "The MP4 fragment size changed while reading samples." }
    return result
}

private fun buildFlatMp4SampleEntries(
    samples: List<FlatMp4Sample>,
    maximumBytes: Int,
): ByteArray {
    val size = samples.size.toLong() * TRUN_SAMPLE_BYTES
    require(size <= maximumBytes && size <= Int.MAX_VALUE) { "The MP4 sample table exceeds the byte limit." }
    val result = ByteArray(size.toInt())
    var cursor = 0
    samples.forEach { sample ->
        cursor = result.writeFlatMp4UInt32(cursor, sample.duration)
        cursor = result.writeFlatMp4UInt32(cursor, sample.size.toLong())
        cursor = result.writeFlatMp4UInt32(cursor, if (sample.isSync) 0L else SAMPLE_IS_NON_SYNC_FLAG)
        cursor = result.writeFlatMp4UInt32(cursor, sample.compositionOffset and UINT32_MAX)
    }
    return result
}

private fun ByteArray.writeFlatMp4UInt32(
    offset: Int,
    value: Long,
): Int {
    require(value in 0..UINT32_MAX && offset in 0..size - FLAT_MP4_UINT32_BYTES)
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
    return offset + FLAT_MP4_UINT32_BYTES
}

private fun flatMp4Concat(
    parts: List<ByteArray>,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes > 0)
    val size = parts.sumOf { it.size.toLong() }
    require(size <= maximumBytes && size <= Int.MAX_VALUE) { "Generated MP4 data exceeds the byte limit." }
    val result = ByteArray(size.toInt())
    var cursor = 0
    parts.forEach { part ->
        part.copyInto(result, cursor)
        cursor += part.size
    }
    return result
}

private fun buildFragmentedMoov(
    bytes: ByteArray,
    moovChildren: List<IsoBmffBox>,
    trackBoxes: List<Mp4TrackBoxes>,
    tracks: List<FlatMp4Track>,
    maximumBytes: Int,
): ByteArray {
    val boxesByTrackOffset = trackBoxes.associateBy { it.track.offset }
    val rebuiltChildren =
        flatMp4Concat(
            moovChildren.filter { it.type != BOX_MVEX }.map { child ->
                when (child.type) {
                    BOX_MVHD -> bytes.copyBoxWithZeroDuration(child, MVHD_DURATION_OFFSET_V0, MVHD_DURATION_OFFSET_V1)
                    BOX_TRAK -> rebuildTrack(bytes, boxesByTrackOffset.getValue(child.offset), maximumBytes)
                    else -> bytes.copyOfRange(child.offset, child.endOffset)
                }
            },
            maximumBytes,
        )
    val trex =
        flatMp4Concat(
            tracks.map { track ->
                fullBox(
                    BOX_TREX,
                    uint32(track.trackId.toLong()) + uint32(1) + uint32(0) + uint32(0) + uint32(0),
                )
            },
            maximumBytes,
        )
    return flatMp4Box(BOX_MOOV, listOf(rebuiltChildren, flatMp4Box(BOX_MVEX, listOf(trex), maximumBytes)), maximumBytes)
}

private fun rebuildTrack(
    bytes: ByteArray,
    boxes: Mp4TrackBoxes,
    maximumBytes: Int,
): ByteArray {
    val trackChildren = bytes.childrenOf(boxes.track).orEmpty()
    val content =
        flatMp4Concat(
            trackChildren.map { child ->
                when (child.type) {
                    BOX_TKHD -> bytes.copyBoxWithZeroDuration(child, TKHD_DURATION_OFFSET_V0, TKHD_DURATION_OFFSET_V1)
                    BOX_MDIA -> rebuildMdia(bytes, boxes, maximumBytes)
                    BOX_EDTS -> ByteArray(0)
                    else -> bytes.copyOfRange(child.offset, child.endOffset)
                }
            },
            maximumBytes - ISO_BOX_HEADER_BYTES,
        )
    return box(BOX_TRAK, content)
}

private fun rebuildMdia(
    bytes: ByteArray,
    boxes: Mp4TrackBoxes,
    maximumBytes: Int,
): ByteArray {
    val children = bytes.childrenOf(boxes.mdia).orEmpty()
    return flatMp4Box(
        BOX_MDIA,
        children.map { child ->
            when (child.type) {
                BOX_MDHD -> bytes.copyBoxWithZeroDuration(child, MDHD_DURATION_OFFSET_V0, MDHD_DURATION_OFFSET_V1)
                BOX_MINF -> rebuildMinf(bytes, boxes, maximumBytes)
                else -> bytes.copyOfRange(child.offset, child.endOffset)
            }
        },
        maximumBytes,
    )
}

private fun rebuildMinf(
    bytes: ByteArray,
    boxes: Mp4TrackBoxes,
    maximumBytes: Int,
): ByteArray {
    val children = bytes.childrenOf(boxes.minf).orEmpty()
    return flatMp4Box(
        BOX_MINF,
        children.map { child ->
            if (child.type ==
                BOX_STBL
            ) {
                emptySampleTable(bytes, boxes.stsd, maximumBytes)
            } else {
                bytes.copyOfRange(child.offset, child.endOffset)
            }
        },
        maximumBytes,
    )
}

private fun emptySampleTable(
    bytes: ByteArray,
    stsd: IsoBmffBox,
    maximumBytes: Int,
): ByteArray =
    flatMp4Box(
        BOX_STBL,
        listOf(
            bytes.copyOfRange(stsd.offset, stsd.endOffset),
            fullBox(BOX_STTS, uint32(0)),
            fullBox(BOX_STSC, uint32(0)),
            fullBox(BOX_STSZ, uint32(0) + uint32(0)),
            fullBox(BOX_STCO, uint32(0)),
        ),
        maximumBytes,
    )

private fun flatMp4Box(
    type: String,
    parts: List<ByteArray>,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes > ISO_BOX_HEADER_BYTES) { "The MP4 initialization byte limit is too small." }
    return box(type, flatMp4Concat(parts, maximumBytes - ISO_BOX_HEADER_BYTES))
}

private fun ByteArray.copyBoxWithZeroDuration(
    source: IsoBmffBox,
    version0Offset: Int,
    version1Offset: Int,
): ByteArray {
    val copy = copyOfRange(source.offset, source.endOffset)
    val content = source.headerSize
    val version = copy[content].toInt() and 0xff
    val offset = content + if (version == 1) version1Offset else version0Offset
    if (version == 1) copy.writeUnsignedLong(offset, 0) else copy.writeUnsignedInt(offset, 0)
    return copy
}

private fun ByteArray.stsdContainsDolbyVision(stsd: IsoBmffBox): Boolean {
    if (stsd.contentSize < 8 || readUnsignedInt(stsd.contentOffset + 4) != 1L) return false
    val entryStart = stsd.contentOffset + 8
    val entry =
        (parseIsoBmffBoxes(entryStart, stsd.endOffset) as? IsoBmffParseResult.Success)?.boxes?.singleOrNull()
            ?: return false
    if (entry.type == "encv" || entry.contentSize < VISUAL_SAMPLE_ENTRY_FIELDS_BYTES) return false
    val childStart = entry.contentOffset + VISUAL_SAMPLE_ENTRY_FIELDS_BYTES
    val children =
        (parseIsoBmffBoxes(childStart, entry.endOffset) as? IsoBmffParseResult.Success)?.boxes ?: return false
    return children.any { it.type == "dvcC" || it.type == "dvvC" } && children.any { it.type == "hvcC" }
}

private fun ByteArray.readTrackId(box: IsoBmffBox): Int? {
    val version = this[box.contentOffset].toInt() and 0xff
    val offset = box.contentOffset + if (version == 1) 20 else 12
    if (offset > box.endOffset - 4) return null
    return readUnsignedInt(offset).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}

private fun ByteArray.readMediaTimescale(box: IsoBmffBox): Long? {
    val version = this[box.contentOffset].toInt() and 0xff
    val offset = box.contentOffset + if (version == 1) 20 else 12
    if (offset > box.endOffset - 4) return null
    return readUnsignedInt(offset).takeIf { it > 0 }
}

private fun ByteArray.childrenOf(box: IsoBmffBox): List<IsoBmffBox>? =
    (parseIsoBmffBoxes(box.contentOffset, box.endOffset) as? IsoBmffParseResult.Success)?.boxes

private val IsoBmffBox.contentSize: Int get() = size - headerSize

private fun Long.toUs(timescale: Long): Long {
    require(this >= 0 && timescale > 0) { "Invalid media timestamp." }
    val seconds = this / timescale
    val remainder = this % timescale
    require(seconds <= Long.MAX_VALUE / MICROSECONDS_PER_SECOND_LONG) { "Media timestamp overflows microseconds." }
    return seconds * MICROSECONDS_PER_SECOND_LONG + remainder * MICROSECONDS_PER_SECOND_LONG / timescale
}

private fun box(
    type: String,
    content: ByteArray,
): ByteArray {
    val size = content.size.toLong() + ISO_BOX_HEADER_BYTES
    require(size <= UINT32_MAX) { "Generated ISO BMFF box exceeds 32-bit size." }
    return uint32(size) + type.encodeToByteArray() + content
}

private fun fullBox(
    type: String,
    content: ByteArray,
    version: Int = 0,
    flags: Int = 0,
): ByteArray =
    box(
        type,
        byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + content,
    )

private fun uint32(value: Long): ByteArray {
    require(value in 0..UINT32_MAX)
    return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}

private fun uint64(value: Long): ByteArray {
    require(value >= 0)
    return uint32(value ushr 32) + uint32(value and UINT32_MAX)
}

private fun int32(value: Long): ByteArray {
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return uint32(value and UINT32_MAX)
}

private fun flatMp4Failure(message: String) = FlatMp4ParseResult.Failure(message)

private fun mp4OpenFailure(message: String) = FlatMp4DolbyVisionOpenResult.Failure(message)

private const val DEFAULT_TARGET_FRAGMENT_DURATION_US = 2_000_000L
private const val DEFAULT_MAXIMUM_MP4_INITIALIZATION_BYTES = 32 * 1024 * 1024
private const val DEFAULT_MAXIMUM_MP4_FRAGMENT_BYTES = 64 * 1024 * 1024
private const val DEFAULT_MAXIMUM_MP4_SAMPLES = 2_000_000
private const val MICROSECONDS_PER_SECOND_LONG = 1_000_000L
private const val VISUAL_SAMPLE_ENTRY_FIELDS_BYTES = 78
private const val FLAT_MP4_UINT32_BYTES = 4
private const val TRUN_SAMPLE_BYTES = 16L
private const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
private const val TRUN_ALL_SAMPLE_FIELDS = 0x000f01
private const val SAMPLE_IS_NON_SYNC_FLAG = 0x0001_0000L
private const val MVHD_DURATION_OFFSET_V0 = 16
private const val MVHD_DURATION_OFFSET_V1 = 24
private const val TKHD_DURATION_OFFSET_V0 = 20
private const val TKHD_DURATION_OFFSET_V1 = 28
private const val MDHD_DURATION_OFFSET_V0 = 16
private const val MDHD_DURATION_OFFSET_V1 = 24
private const val BOX_FTYP = "ftyp"
private const val BOX_MOOV = "moov"
private const val BOX_MOOF = "moof"
private const val BOX_MDAT = "mdat"
private const val BOX_MVHD = "mvhd"
private const val BOX_PSSH = "pssh"
private const val BOX_MVEX = "mvex"
private const val BOX_TREX = "trex"
private const val BOX_TRAK = "trak"
private const val BOX_TKHD = "tkhd"
private const val BOX_EDTS = "edts"
private const val BOX_MDIA = "mdia"
private const val BOX_MDHD = "mdhd"
private const val BOX_HDLR = "hdlr"
private const val BOX_MINF = "minf"
private const val BOX_STBL = "stbl"
private const val BOX_STSD = "stsd"
private const val BOX_STTS = "stts"
private const val BOX_CTTS = "ctts"
private const val BOX_STSC = "stsc"
private const val BOX_STSZ = "stsz"
private const val BOX_STZ2 = "stz2"
private const val BOX_STCO = "stco"
private const val BOX_CO64 = "co64"
private const val BOX_STSS = "stss"
private const val BOX_SAIZ = "saiz"
private const val BOX_SAIO = "saio"
private const val BOX_SENC = "senc"
private const val BOX_MFHD = "mfhd"
private const val BOX_TRAF = "traf"
private const val BOX_TFHD = "tfhd"
private const val BOX_TFDT = "tfdt"
private const val BOX_TRUN = "trun"
private const val HANDLER_VIDEO = "vide"
private val MP4_ENCRYPTION_TOP_LEVEL_BOXES = setOf("pssh")
private val MP4_ENCRYPTION_SAMPLE_BOXES = setOf("senc", "saiz", "saio", "sinf")
