@file:Suppress(
    "CyclomaticComplexMethod",
    "LargeClass",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooManyFunctions",
)

package io.github.kdroidfilter.composemediaplayer

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.roundToLong

internal data class JvmMediaChapterProbeResult(
    val rows: List<RawMediaChapter>,
    val durationMs: Long? = null,
)

/**
 * Bounded, dependency-free chapter fallback for JVM backends.
 *
 * Native engines remain the first source of runtime metadata. This reader covers formats whose
 * chapter tables can be obtained without decoding media and keeps that support identical across
 * AVFoundation, Media Foundation, GStreamer and optional libVLC desktop routes.
 */
internal object JvmMediaChapterProbe {
    fun probe(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JvmMediaChapterProbeResult? {
        val safeHeaders = requestHeaders.sanitizedRequestHeaders()
        if (uri.looksLikeHlsUri()) {
            parseHls(uri, safeHeaders)?.let { return it }
        }

        return runCatching {
            ChapterByteSource.open(uri, safeHeaders).use { source ->
                val prefix = source.read(0L, PROBE_PREFIX_BYTES) ?: return@use null
                when {
                    prefix.startsWith(ID3_SIGNATURE) -> parseId3ChapterRows(prefix).asResult()
                    prefix.startsWith(ASF_HEADER_GUID) -> parseAsf(source)
                    prefix.looksLikeIsoBmff() -> parseIsoBmff(source)
                    prefix.decodeToString().trimStart().startsWith("#EXTM3U") ->
                        parseHls(uri, safeHeaders)
                    else -> null
                }
            }
        }.getOrNull()
    }

    internal fun parseId3ChapterRows(bytes: ByteArray): List<RawMediaChapter> {
        if (bytes.size < ID3_HEADER_BYTES || !bytes.startsWith(ID3_SIGNATURE)) return emptyList()
        val majorVersion = bytes[3].uInt()
        if (majorVersion !in 3..4) return emptyList()
        val declaredSize = bytes.syncSafeInt(6) ?: return emptyList()
        val tagEnd = (ID3_HEADER_BYTES.toLong() + declaredSize).coerceAtMost(bytes.size.toLong()).toInt()
        if (tagEnd <= ID3_HEADER_BYTES) return emptyList()

        var payload = bytes.copyOfRange(ID3_HEADER_BYTES, tagEnd)
        if (bytes[5].uInt() and ID3_FLAG_UNSYNCHRONISATION != 0) {
            payload = payload.removeId3Unsynchronisation()
        }

        var cursor = id3FramesStart(payload, majorVersion, bytes[5].uInt())
        val chapters = mutableListOf<Id3Chapter>()
        val tablesOfContents = mutableListOf<Id3TableOfContents>()
        while (cursor + ID3_FRAME_HEADER_BYTES <= payload.size && chapters.size < MAX_CHAPTERS) {
            val id = payload.ascii(cursor, 4)
            if (!id.isId3FrameId()) break
            val frameSize =
                if (majorVersion == 4) {
                    payload.syncSafeInt(cursor + 4)
                } else {
                    payload.beInt(cursor + 4)
                } ?: break
            if (frameSize <= 0) {
                cursor += ID3_FRAME_HEADER_BYTES
                continue
            }
            val bodyStart = cursor + ID3_FRAME_HEADER_BYTES
            val bodyEnd = bodyStart.toLong() + frameSize
            if (bodyEnd > payload.size || bodyEnd < bodyStart) break
            when (id) {
                "CHAP" ->
                    parseId3ChapFrame(
                        payload = payload,
                        start = bodyStart,
                        end = bodyEnd.toInt(),
                        majorVersion = majorVersion,
                    )?.let(chapters::add)
                "CTOC" ->
                    parseId3TocFrame(
                        payload = payload,
                        start = bodyStart,
                        end = bodyEnd.toInt(),
                    )?.let(tablesOfContents::add)
            }
            cursor = bodyEnd.toInt()
        }
        return flattenId3Chapters(chapters, tablesOfContents)
            .sortedBy(RawMediaChapter::startMs)
    }

    private fun id3FramesStart(
        payload: ByteArray,
        majorVersion: Int,
        flags: Int,
    ): Int {
        if (flags and ID3_FLAG_EXTENDED_HEADER == 0 || payload.size < 4) return 0
        val size =
            if (majorVersion == 4) {
                payload.syncSafeInt(0)
            } else {
                payload.beInt(0)?.let { it + 4 }
            } ?: return payload.size
        return size.coerceIn(0, payload.size)
    }

    private fun parseId3ChapFrame(
        payload: ByteArray,
        start: Int,
        end: Int,
        majorVersion: Int,
    ): Id3Chapter? {
        val idEnd = payload.indexOf(0, start, end)
        if (idEnd < 0 || idEnd + 17 > end) return null
        val elementId = payload.ascii(start, idEnd - start).takeIf(String::isNotEmpty) ?: return null
        val valuesStart = idEnd + 1
        val startMs = payload.beUInt(valuesStart) ?: return null
        if (startMs == UINT32_MAX || startMs > Long.MAX_VALUE.toULong()) return null
        val endMsValue = payload.beUInt(valuesStart + 4) ?: return null
        val subFramesStart = valuesStart + 16
        val title =
            parseId3EmbeddedTitle(
                payload = payload,
                start = subFramesStart,
                end = end,
                majorVersion = majorVersion,
            )
        return Id3Chapter(
            elementId = elementId,
            row =
                RawMediaChapter(
                    startMs = startMs.toLong(),
                    endMs =
                        endMsValue
                            .takeUnless { it == UINT32_MAX }
                            ?.toLong()
                            ?.takeIf { it > startMs.toLong() },
                    title = title,
                ),
        )
    }

    private fun parseId3TocFrame(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): Id3TableOfContents? {
        val idEnd = payload.indexOf(0, start, end)
        if (idEnd < 0 || idEnd + 3 > end) return null
        val elementId = payload.ascii(start, idEnd - start).takeIf(String::isNotEmpty) ?: return null
        val flags = payload[idEnd + 1].uInt()
        val childCount = payload[idEnd + 2].uInt()
        var cursor = idEnd + 3
        val children =
            buildList {
                repeat(childCount) {
                    val childEnd = payload.indexOf(0, cursor, end)
                    if (childEnd < 0) return@buildList
                    payload
                        .ascii(cursor, childEnd - cursor)
                        .takeIf(String::isNotEmpty)
                        ?.let(::add)
                    cursor = childEnd + 1
                }
            }
        return Id3TableOfContents(
            elementId = elementId,
            isRoot = flags and ID3_CTOC_FLAG_TOP_LEVEL != 0,
            children = children,
        )
    }

    private fun flattenId3Chapters(
        chapters: List<Id3Chapter>,
        tablesOfContents: List<Id3TableOfContents>,
    ): List<RawMediaChapter> {
        val chaptersById = chapters.associateBy(Id3Chapter::elementId)
        val tablesById = tablesOfContents.associateBy(Id3TableOfContents::elementId)
        val addedChapterIds = mutableSetOf<String>()
        val visitedTableIds = mutableSetOf<String>()
        val result = mutableListOf<RawMediaChapter>()

        fun visit(elementId: String) {
            chaptersById[elementId]?.let { chapter ->
                if (addedChapterIds.add(elementId)) result += chapter.row
                return
            }
            val table = tablesById[elementId] ?: return
            if (!visitedTableIds.add(elementId)) return
            table.children.forEach(::visit)
        }

        tablesOfContents.filter(Id3TableOfContents::isRoot).forEach { table -> visit(table.elementId) }
        chapters.forEach { chapter ->
            if (addedChapterIds.add(chapter.elementId)) result += chapter.row
        }
        return result
    }

    private fun parseId3EmbeddedTitle(
        payload: ByteArray,
        start: Int,
        end: Int,
        majorVersion: Int,
    ): String? {
        var cursor = start
        while (cursor + ID3_FRAME_HEADER_BYTES <= end) {
            val id = payload.ascii(cursor, 4)
            if (!id.isId3FrameId()) return null
            val size =
                (
                    if (majorVersion == 4) {
                        payload.syncSafeInt(cursor + 4)
                    } else {
                        payload.beInt(cursor + 4)
                    }
                ) ?: return null
            val bodyStart = cursor + ID3_FRAME_HEADER_BYTES
            val bodyEnd = bodyStart.toLong() + size
            if (size < 1 || bodyEnd > end || bodyEnd < bodyStart) return null
            if (id == "TIT2" || id == "TT2") {
                return decodeId3Text(payload, bodyStart, bodyEnd.toInt())
            }
            cursor = bodyEnd.toInt()
        }
        return null
    }

    private fun decodeId3Text(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String? {
        if (start >= end) return null
        val encoding = bytes[start].uInt()
        val textStart = start + 1
        if (textStart >= end) return null
        val charset =
            when (encoding) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> return null
            }
        return bytes
            .copyOfRange(textStart, end)
            .toString(charset)
            .trim('\u0000', '\uFEFF', ' ', '\t', '\r', '\n')
            .takeIf(String::isNotEmpty)
    }

    internal fun parseNeroChapterRows(chplPayload: ByteArray): List<RawMediaChapter> {
        if (chplPayload.size < 5) return emptyList()
        val version = chplPayload[0].uInt()
        var cursor = 4
        if (version == 1) {
            if (cursor + 4 >= chplPayload.size) return emptyList()
            cursor += 4
        }
        val count = chplPayload[cursor++].uInt().coerceAtMost(MAX_CHAPTERS)
        return buildList {
            repeat(count) {
                if (cursor + 9 > chplPayload.size) return@buildList
                val start100ns = chplPayload.beLong(cursor) ?: return@buildList
                cursor += 8
                val titleLength = chplPayload[cursor++].uInt()
                if (cursor + titleLength > chplPayload.size) return@buildList
                val title =
                    chplPayload
                        .copyOfRange(cursor, cursor + titleLength)
                        .toString(Charsets.UTF_8)
                        .trim('\u0000')
                        .takeIf(String::isNotBlank)
                cursor += titleLength
                if (start100ns >= 0L) {
                    add(
                        RawMediaChapter(
                            startMs = (start100ns / HUNDRED_NANOSECONDS_PER_MILLISECOND),
                            title = title,
                        ),
                    )
                }
            }
        }
    }

    private fun parseIsoBmff(source: ChapterByteSource): JvmMediaChapterProbeResult? {
        val length = source.length ?: return null
        var cursor = 0L
        var moovHeader: SourceBoxHeader? = null
        var boxCount = 0
        while (cursor + ISO_BOX_HEADER_BYTES <= length && boxCount++ < MAX_TOP_LEVEL_BOXES) {
            val header = readSourceBoxHeader(source, cursor, length) ?: break
            if (header.type == "moov") {
                moovHeader = header
                break
            }
            cursor = header.end
        }
        val moov = moovHeader ?: return null
        if (moov.size > MAX_MOOV_BYTES || moov.size > Int.MAX_VALUE) return null
        val bytes = source.read(moov.start, moov.size.toInt()) ?: return null
        val root = parseIsoBox(bytes, 0, bytes.size) ?: return null
        if (root.type != "moov") return null

        val movieTiming = parseMovieTiming(bytes, root)
        val neroRows =
            root
                .children(bytes)
                .firstOrNull { it.type == "udta" }
                ?.children(bytes)
                ?.firstOrNull { it.type == "chpl" }
                ?.payload(bytes)
                ?.let(::parseNeroChapterRows)
                .orEmpty()

        val quickTimeRows = parseQuickTimeChapterTrackRows(source, bytes, root, movieTiming)
        val rows = quickTimeRows.ifEmpty { neroRows }
        if (rows.isEmpty()) return null
        return JvmMediaChapterProbeResult(
            rows = rows,
            durationMs = movieTiming.durationMs,
        )
    }

    private fun parseMovieTiming(
        bytes: ByteArray,
        moov: IsoBox,
    ): MovieTiming {
        val mvhd = moov.children(bytes).firstOrNull { it.type == "mvhd" } ?: return MovieTiming()
        val payloadStart = mvhd.contentStart
        if (payloadStart + 20 > mvhd.end) return MovieTiming()
        val version = bytes[payloadStart].uInt()
        val timescaleOffset = if (version == 1) payloadStart + 20 else payloadStart + 12
        val durationOffset = if (version == 1) payloadStart + 24 else payloadStart + 16
        val timescale = bytes.beUInt(timescaleOffset)?.toLong()?.takeIf { it > 0L }
        val durationUnits =
            if (version == 1) {
                bytes.beLong(durationOffset)?.takeIf { it >= 0L }
            } else {
                bytes.beUInt(durationOffset)?.toLong()
            }
        val durationMs =
            if (timescale != null && durationUnits != null) {
                durationUnits.scaledToMilliseconds(timescale)
            } else {
                null
            }
        return MovieTiming(timescale = timescale, durationMs = durationMs)
    }

    private fun parseQuickTimeChapterTrackRows(
        source: ChapterByteSource,
        bytes: ByteArray,
        moov: IsoBox,
        movieTiming: MovieTiming,
    ): List<RawMediaChapter> {
        val tracks = moov.children(bytes).filter { it.type == "trak" }
        val referencedTrackIds =
            tracks
                .flatMap { track ->
                    track
                        .children(bytes)
                        .firstOrNull { it.type == "tref" }
                        ?.children(bytes)
                        .orEmpty()
                        .filter { it.type == "chap" }
                        .flatMap { chapterReference ->
                            chapterReference
                                .payload(bytes)
                                .asList()
                                .chunked(4)
                                .mapNotNull { chunk ->
                                    if (chunk.size != 4) {
                                        null
                                    } else {
                                        byteArrayOf(chunk[0], chunk[1], chunk[2], chunk[3])
                                            .beUInt(0)
                                            ?.toLong()
                                    }
                                }
                        }
                }.toSet()
        if (referencedTrackIds.isEmpty()) return emptyList()

        val candidates =
            tracks.mapNotNull { track ->
                parseQuickTimeTrack(bytes, track, movieTiming)?.takeIf {
                    it.trackId in referencedTrackIds
                }
            }
        val selected = candidates.preferredChapterTrack() ?: return emptyList()
        return readQuickTimeChapterSamples(source, selected)
    }

    private fun parseQuickTimeTrack(
        bytes: ByteArray,
        track: IsoBox,
        movieTiming: MovieTiming,
    ): QuickTimeChapterTrack? {
        val children = track.children(bytes)
        val tkhd = children.firstOrNull { it.type == "tkhd" } ?: return null
        val mdia = children.firstOrNull { it.type == "mdia" } ?: return null
        val mdiaChildren = mdia.children(bytes)
        val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" } ?: return null
        val minf = mdiaChildren.firstOrNull { it.type == "minf" } ?: return null
        val stbl = minf.children(bytes).firstOrNull { it.type == "stbl" } ?: return null

        val trackId =
            tkhd.payload(bytes).let { payload ->
                val version = payload.firstOrNull()?.uInt() ?: return null
                payload.beUInt(if (version == 1) 20 else 12)?.toLong()
            } ?: return null
        val mdhdPayload = mdhd.payload(bytes)
        val version = mdhdPayload.firstOrNull()?.uInt() ?: return null
        val timescaleOffset = if (version == 1) 20 else 12
        val languageOffset = if (version == 1) 32 else 20
        val timescale = mdhdPayload.beUInt(timescaleOffset)?.toLong()?.takeIf { it > 0L } ?: return null
        val language = mdhdPayload.beUShort(languageOffset)?.decodeIso639Language()
        val stblChildren = stbl.children(bytes)

        val sampleSizes =
            parseSampleSizes(
                stblChildren.firstOrNull { it.type == "stsz" }?.payload(bytes) ?: return null,
            )
        if (sampleSizes.isEmpty()) return null
        val sampleTimes =
            parseSampleTimes(
                stblChildren.firstOrNull { it.type == "stts" }?.payload(bytes) ?: return null,
                sampleSizes.size,
            )
        if (sampleTimes.size != sampleSizes.size) return null
        val chunkOffsets =
            stblChildren.firstOrNull { it.type == "co64" }?.payload(bytes)?.let {
                parseChunkOffsets(it, is64Bit = true)
            } ?: stblChildren.firstOrNull { it.type == "stco" }?.payload(bytes)?.let {
                parseChunkOffsets(it, is64Bit = false)
            } ?: return null
        val sampleToChunk =
            parseSampleToChunk(
                stblChildren.firstOrNull { it.type == "stsc" }?.payload(bytes) ?: return null,
            )
        if (chunkOffsets.isEmpty() || sampleToChunk.isEmpty()) return null

        val editShiftMs =
            parseEditShiftMs(
                children.firstOrNull { it.type == "edts" },
                bytes,
                movieTiming.timescale,
                timescale,
            )
        return QuickTimeChapterTrack(
            trackId = trackId,
            timescale = timescale,
            language = language,
            sampleSizes = sampleSizes,
            sampleTimes = sampleTimes,
            sampleOffsets = buildSampleOffsets(sampleSizes, chunkOffsets, sampleToChunk),
            editShiftMs = editShiftMs,
        )
    }

    private fun parseSampleSizes(payload: ByteArray): List<Int> {
        if (payload.size < 12) return emptyList()
        val fixedSize = payload.beInt(4) ?: return emptyList()
        val count = (payload.beInt(8) ?: return emptyList()).coerceIn(0, MAX_QUICKTIME_CHAPTER_SAMPLES)
        if (fixedSize > 0) return List(count) { fixedSize }
        if (12L + count * 4L > payload.size) return emptyList()
        return List(count) { index -> payload.beInt(12 + index * 4) ?: 0 }
            .takeIf { sizes -> sizes.all { it in 1..MAX_CHAPTER_SAMPLE_BYTES } }
            .orEmpty()
    }

    private fun parseSampleTimes(
        payload: ByteArray,
        sampleCount: Int,
    ): List<SampleTime> {
        if (payload.size < 8) return emptyList()
        val entryCount = (payload.beInt(4) ?: return emptyList()).coerceIn(0, MAX_SAMPLE_TABLE_ENTRIES)
        if (8L + entryCount * 8L > payload.size) return emptyList()
        val result = ArrayList<SampleTime>(sampleCount)
        var startUnits = 0L
        for (entryIndex in 0 until entryCount) {
            val cursor = 8 + entryIndex * 8
            val count = payload.beUInt(cursor)?.toLong() ?: return emptyList()
            val duration = payload.beUInt(cursor + 4)?.toLong() ?: return emptyList()
            repeat(count.coerceAtMost((sampleCount - result.size).toLong()).toInt()) {
                result += SampleTime(startUnits = startUnits, durationUnits = duration)
                startUnits = startUnits.saturatedAdd(duration)
            }
            if (result.size == sampleCount) break
        }
        return result
    }

    private fun parseChunkOffsets(
        payload: ByteArray,
        is64Bit: Boolean,
    ): List<Long> {
        if (payload.size < 8) return emptyList()
        val entryBytes = if (is64Bit) 8 else 4
        val count = (payload.beInt(4) ?: return emptyList()).coerceIn(0, MAX_SAMPLE_TABLE_ENTRIES)
        if (8L + count.toLong() * entryBytes > payload.size) return emptyList()
        return List(count) { index ->
            val cursor = 8 + index * entryBytes
            if (is64Bit) {
                payload.beLong(cursor) ?: -1L
            } else {
                payload.beUInt(cursor)?.toLong() ?: -1L
            }
        }.takeIf { offsets -> offsets.all { it >= 0L } }.orEmpty()
    }

    private fun parseSampleToChunk(payload: ByteArray): List<SampleToChunk> {
        if (payload.size < 8) return emptyList()
        val count = (payload.beInt(4) ?: return emptyList()).coerceIn(0, MAX_SAMPLE_TABLE_ENTRIES)
        if (8L + count * 12L > payload.size) return emptyList()
        return List(count) { index ->
            val cursor = 8 + index * 12
            SampleToChunk(
                firstChunk = payload.beUInt(cursor)?.toLong() ?: 0L,
                samplesPerChunk = payload.beUInt(cursor + 4)?.toLong() ?: 0L,
            )
        }.takeIf { entries ->
            entries.isNotEmpty() &&
                entries.first().firstChunk == 1L &&
                entries.zipWithNext().all { (left, right) -> right.firstChunk > left.firstChunk } &&
                entries.all { it.samplesPerChunk > 0L }
        }.orEmpty()
    }

    private fun buildSampleOffsets(
        sampleSizes: List<Int>,
        chunkOffsets: List<Long>,
        sampleToChunk: List<SampleToChunk>,
    ): List<Long> {
        val result = ArrayList<Long>(sampleSizes.size)
        var sampleIndex = 0
        var tableIndex = 0
        for (chunkIndex in chunkOffsets.indices) {
            val oneBasedChunk = chunkIndex + 1L
            while (
                tableIndex + 1 < sampleToChunk.size &&
                sampleToChunk[tableIndex + 1].firstChunk <= oneBasedChunk
            ) {
                tableIndex += 1
            }
            var offset = chunkOffsets[chunkIndex]
            val count = sampleToChunk[tableIndex].samplesPerChunk
            repeat(count.coerceAtMost((sampleSizes.size - sampleIndex).toLong()).toInt()) {
                result += offset
                offset = offset.saturatedAdd(sampleSizes[sampleIndex].toLong())
                sampleIndex += 1
            }
            if (sampleIndex == sampleSizes.size) break
        }
        return result.takeIf { it.size == sampleSizes.size }.orEmpty()
    }

    private fun parseEditShiftMs(
        edts: IsoBox?,
        bytes: ByteArray,
        movieTimescale: Long?,
        trackTimescale: Long,
    ): Long {
        val elst = edts?.children(bytes)?.firstOrNull { it.type == "elst" } ?: return 0L
        val payload = elst.payload(bytes)
        if (payload.size < 8) return 0L
        val version = payload[0].uInt()
        val count = (payload.beInt(4) ?: return 0L).coerceIn(0, 2)
        var cursor = 8
        var emptyEditMs = 0L
        var mediaStartUnits = 0L
        repeat(count) {
            val entryBytes = if (version == 1) 20 else 12
            if (cursor + entryBytes > payload.size) return@repeat
            val segmentDuration =
                if (version == 1) payload.beLong(cursor) else payload.beUInt(cursor)?.toLong()
            val mediaTime =
                if (version == 1) {
                    payload.beLong(cursor + 8)
                } else {
                    payload.beInt(cursor + 4)?.toLong()
                }
            if (mediaTime == -1L && movieTimescale != null && segmentDuration != null) {
                emptyEditMs = segmentDuration.scaledToMilliseconds(movieTimescale)
            } else if (mediaTime != null && mediaTime >= 0L) {
                mediaStartUnits = mediaTime
            }
            cursor += entryBytes
        }
        return emptyEditMs - mediaStartUnits.scaledToMilliseconds(trackTimescale)
    }

    private fun readQuickTimeChapterSamples(
        source: ChapterByteSource,
        track: QuickTimeChapterTrack,
    ): List<RawMediaChapter> {
        if (track.sampleOffsets.size != track.sampleSizes.size) return emptyList()
        return buildList {
            track.sampleSizes.indices.forEach { index ->
                val sample = source.read(track.sampleOffsets[index], track.sampleSizes[index]) ?: return@forEach
                val title = decodeQuickTimeTextSample(sample) ?: return@forEach
                val sampleTime = track.sampleTimes[index]
                val startMs =
                    sampleTime.startUnits
                        .scaledToMilliseconds(track.timescale)
                        .saturatedAdd(track.editShiftMs)
                        .coerceAtLeast(0L)
                val endMs =
                    sampleTime.startUnits
                        .saturatedAdd(sampleTime.durationUnits)
                        .scaledToMilliseconds(track.timescale)
                        .saturatedAdd(track.editShiftMs)
                        .takeIf { it > startMs }
                add(
                    RawMediaChapter(
                        startMs = startMs,
                        endMs = endMs,
                        title = title,
                        language = track.language,
                    ),
                )
            }
        }
    }

    private fun decodeQuickTimeTextSample(sample: ByteArray): String? {
        if (sample.size < 2) return null
        val declaredLength = sample.beUShort(0) ?: return null
        if (declaredLength <= 0 || declaredLength > sample.size - 2) return null
        val textBytes = sample.copyOfRange(2, 2 + declaredLength)
        val utf8 = textBytes.toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
        if (utf8.isNotBlank() && !utf8.contains('\uFFFD')) return utf8
        return runCatching {
            textBytes
                .toString(Charset.forName("x-MacRoman"))
                .trim('\u0000', ' ', '\r', '\n')
                .takeIf(String::isNotBlank)
        }.getOrNull()
    }

    internal fun parseAsfMarkerRows(bytes: ByteArray): JvmMediaChapterProbeResult? {
        if (!bytes.startsWith(ASF_HEADER_GUID)) return null
        val prerollMs =
            bytes
                .indexOf(ASF_FILE_PROPERTIES_GUID)
                .takeIf { it >= 0 }
                ?.let { offset ->
                    bytes.leLong(offset + ASF_OBJECT_HEADER_BYTES + ASF_FILE_PROPERTIES_PREROLL_OFFSET)
                }?.takeIf { it >= 0L } ?: 0L
        val durationMs =
            bytes
                .indexOf(ASF_FILE_PROPERTIES_GUID)
                .takeIf { it >= 0 }
                ?.let { offset ->
                    bytes.leLong(offset + ASF_OBJECT_HEADER_BYTES + ASF_FILE_PROPERTIES_PLAY_DURATION_OFFSET)
                }?.takeIf { it > 0L }
                ?.div(HUNDRED_NANOSECONDS_PER_MILLISECOND)
                ?.minus(prerollMs)
                ?.takeIf { it > 0L }

        val markerOffset = bytes.indexOf(ASF_MARKER_GUID)
        if (markerOffset < 0 || markerOffset + ASF_MARKER_FIXED_BYTES > bytes.size) return null
        val objectSize = bytes.leLong(markerOffset + 16) ?: return null
        val objectEnd =
            (markerOffset.toLong() + objectSize)
                .coerceAtMost(bytes.size.toLong())
                .takeIf { it > markerOffset }
                ?.toInt() ?: return null
        var cursor = markerOffset + ASF_OBJECT_HEADER_BYTES
        cursor += 16 // reserved GUID
        val markerCount = (bytes.leInt(cursor) ?: return null).coerceIn(0, MAX_CHAPTERS)
        cursor += 4
        cursor += 2 // reserved
        val objectNameChars = bytes.leUShort(cursor) ?: return null
        cursor += 2 + objectNameChars * 2

        val rows =
            buildList {
                repeat(markerCount) {
                    if (cursor + ASF_MARKER_ENTRY_FIXED_BYTES > objectEnd) return@buildList
                    cursor += 8 // offset into the data object
                    val presentation100ns = bytes.leLong(cursor) ?: return@buildList
                    cursor += 8
                    bytes.leUShort(cursor) ?: return@buildList
                    cursor += 2
                    cursor += 4 // send time
                    cursor += 4 // flags
                    val descriptionChars = bytes.leInt(cursor) ?: return@buildList
                    cursor += 4
                    if (
                        descriptionChars < 0 ||
                        descriptionChars > MAX_ASF_MARKER_TITLE_CHARS ||
                        cursor.toLong() + descriptionChars * 2L > objectEnd
                    ) {
                        return@buildList
                    }
                    val title =
                        bytes
                            .copyOfRange(cursor, cursor + descriptionChars * 2)
                            .toString(Charsets.UTF_16LE)
                            .trimEnd('\u0000')
                            .takeIf(String::isNotBlank)
                    cursor += descriptionChars * 2
                    val startMs =
                        (presentation100ns / HUNDRED_NANOSECONDS_PER_MILLISECOND - prerollMs)
                            .coerceAtLeast(0L)
                    add(RawMediaChapter(startMs = startMs, title = title))
                }
            }
        if (rows.isEmpty()) return null
        return JvmMediaChapterProbeResult(rows = rows, durationMs = durationMs)
    }

    private fun parseAsf(source: ChapterByteSource): JvmMediaChapterProbeResult? {
        val bytesToRead =
            source.length
                ?.coerceAtMost(MAX_ASF_HEADER_BYTES.toLong())
                ?.toInt()
                ?: MAX_ASF_HEADER_BYTES
        val bytes = source.read(0L, bytesToRead) ?: return null
        return parseAsfMarkerRows(bytes)
    }

    private fun parseHls(
        uri: String,
        requestHeaders: Map<String, String>,
    ): JvmMediaChapterProbeResult? {
        val masterText = readBoundedText(uri, requestHeaders, MAX_HLS_RESOURCE_BYTES) ?: return null
        if (!masterText.trimStart().startsWith("#EXTM3U")) return null
        val chapterUri = parseHlsChapterJsonUri(masterText, uri) ?: return null
        val mediaPlaylistUri =
            if (masterText.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }) {
                firstHlsVariantUri(masterText, uri) ?: return null
            } else {
                uri
            }
        val mediaText =
            if (mediaPlaylistUri == uri) {
                masterText
            } else {
                readBoundedText(mediaPlaylistUri, requestHeaders, MAX_HLS_RESOURCE_BYTES) ?: return null
            }
        if (!mediaText.isSupportedChapterPlaylist()) return null

        val jsonText = readBoundedText(chapterUri, requestHeaders, MAX_HLS_RESOURCE_BYTES) ?: return null
        val preferredLanguages =
            listOfNotNull(
                Locale.getDefault().toLanguageTag().takeIf(String::isNotBlank),
                Locale.getDefault().language.takeIf(String::isNotBlank),
            )
        val rows = parseHlsChapterJson(jsonText, preferredLanguages)
        if (rows.isEmpty()) return null
        val durationMs =
            mediaText
                .lineSequence()
                .filter { it.startsWith("#EXTINF:") }
                .mapNotNull { it.substringAfter(':').substringBefore(',').toDoubleOrNull() }
                .sum()
                .takeIf { it > 0.0 }
                ?.times(1_000.0)
                ?.roundToLong()
        return JvmMediaChapterProbeResult(rows = rows, durationMs = durationMs)
    }

    internal fun parseHlsChapterJson(
        json: String,
        preferredLanguages: List<String>,
    ): List<RawMediaChapter> {
        val root =
            runCatching { SimpleJsonParser(json).parse() }.getOrNull() as? JsonValue.ArrayValue
                ?: return emptyList()
        return root.values.mapNotNull { chapterValue ->
            val chapter = chapterValue as? JsonValue.ObjectValue ?: return@mapNotNull null
            val startSeconds = chapter.number("start-time") ?: return@mapNotNull null
            if (!startSeconds.isFinite() || startSeconds < 0.0) return@mapNotNull null
            val durationSeconds = chapter.number("duration")?.takeIf { it.isFinite() && it > 0.0 }
            val labels =
                (chapter.values["titles"] as? JsonValue.ArrayValue)
                    ?.values
                    .orEmpty()
                    .mapNotNull { titleValue ->
                        val title = titleValue as? JsonValue.ObjectValue ?: return@mapNotNull null
                        val text = title.string("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        MediaChapterLabel(text = text, language = title.string("language"))
                    }
            val label = selectPreferredChapterLabel(labels, preferredLanguages)
            RawMediaChapter(
                startMs = (startSeconds * 1_000.0).roundToLong(),
                endMs = durationSeconds?.let { ((startSeconds + it) * 1_000.0).roundToLong() },
                title = label?.text,
                language = label?.language,
            )
        }
    }

    internal fun parseHlsChapterJsonUri(
        playlist: String,
        baseUri: String,
    ): String? {
        val variableDefinitions =
            playlist
                .lineSequence()
                .map(String::trim)
                .filter { it.startsWith(HLS_DEFINE_PREFIX) }
                .map { parseHlsAttributeList(it.removePrefix(HLS_DEFINE_PREFIX)) }
                .mapNotNull { attributes ->
                    val name = attributes["NAME"]?.takeIf(String::isNotBlank)
                    val value = attributes["VALUE"] ?: return@mapNotNull null
                    name?.let { it to value }
                }.toMap()
        return playlist
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith(HLS_SESSION_DATA_PREFIX) }
            .map { parseHlsAttributeList(it.removePrefix(HLS_SESSION_DATA_PREFIX)) }
            .firstOrNull { attributes ->
                attributes["DATA-ID"] == APPLE_HLS_CHAPTERS_DATA_ID && attributes["URI"] != null
            }?.get("URI")
            ?.replaceHlsVariableReferences(variableDefinitions)
            ?.let { URI.create(baseUri).resolve(it).toString() }
    }

    private fun String.replaceHlsVariableReferences(definitions: Map<String, String>): String {
        var result = this
        repeat(MAX_HLS_VARIABLE_EXPANSION_PASSES) {
            val expanded =
                HLS_VARIABLE_REFERENCE_REGEX.replace(result) { match ->
                    definitions[match.groupValues[1]] ?: match.value
                }
            if (expanded == result) return result
            result = expanded
        }
        return result
    }

    private fun firstHlsVariantUri(
        playlist: String,
        baseUri: String,
    ): String? {
        var expectsVariant = false
        playlist.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (expectsVariant && line.isNotEmpty() && !line.startsWith('#')) {
                return URI.create(baseUri).resolve(line).toString()
            }
            expectsVariant = line.startsWith("#EXT-X-STREAM-INF:")
        }
        return null
    }

    private fun String.isSupportedChapterPlaylist(): Boolean =
        lineSequence().any { it.trim() == "#EXT-X-ENDLIST" } ||
            lineSequence().any {
                val value = it.substringAfter("#EXT-X-PLAYLIST-TYPE:", "").trim()
                value.equals("VOD", ignoreCase = true) || value.equals("EVENT", ignoreCase = true)
            }

    private fun parseHlsAttributeList(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < value.length) {
            while (cursor < value.length && (value[cursor] == ',' || value[cursor].isWhitespace())) cursor++
            val equals = value.indexOf('=', cursor)
            if (equals < 0) break
            val key = value.substring(cursor, equals).trim()
            cursor = equals + 1
            val parsedValue =
                if (cursor < value.length && value[cursor] == '"') {
                    cursor++
                    buildString {
                        while (cursor < value.length) {
                            val char = value[cursor++]
                            when {
                                char == '"' -> break
                                char == '\\' && cursor < value.length -> append(value[cursor++])
                                else -> append(char)
                            }
                        }
                    }
                } else {
                    val comma = value.indexOf(',', cursor).let { if (it < 0) value.length else it }
                    value.substring(cursor, comma).trim().also { cursor = comma }
                }
            if (key.isNotEmpty()) result[key] = parsedValue
            while (cursor < value.length && value[cursor] != ',') cursor++
        }
        return result
    }

    private fun readBoundedText(
        uri: String,
        requestHeaders: Map<String, String>,
        maxBytes: Int,
    ): String? =
        runCatching {
            val parsed = URI.create(uri)
            when (parsed.scheme?.lowercase()) {
                "http", "https" -> {
                    val connection = parsed.toURL().openConnection() as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = true
                        connection.connectTimeout = NETWORK_TIMEOUT_MS
                        connection.readTimeout = NETWORK_TIMEOUT_MS
                        connection.setRequestProperty("User-Agent", USER_AGENT)
                        requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                        connection.inputStream.use { input ->
                            input
                                .readNBytes(maxBytes + 1)
                                .takeIf { it.size <= maxBytes }
                                ?.toString(Charsets.UTF_8)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                "file" ->
                    File(parsed).inputStream().use { input ->
                        input
                            .readNBytes(maxBytes + 1)
                            .takeIf { it.size <= maxBytes }
                            ?.toString(Charsets.UTF_8)
                    }
                else ->
                    File(uri).inputStream().use { input ->
                        input
                            .readNBytes(maxBytes + 1)
                            .takeIf { it.size <= maxBytes }
                            ?.toString(Charsets.UTF_8)
                    }
            }
        }.getOrNull()

    private fun List<QuickTimeChapterTrack>.preferredChapterTrack(): QuickTimeChapterTrack? {
        if (isEmpty()) return null
        val preferred = Locale.getDefault().toLanguageTag()
        firstOrNull { it.language.equals(preferred, ignoreCase = true) }?.let { return it }
        val primary = preferred.substringBefore('-')
        firstOrNull { it.language?.substringBefore('-').equals(primary, ignoreCase = true) }?.let {
            return it
        }
        return firstOrNull { it.language.isNullOrBlank() || it.language == "und" } ?: first()
    }

    private fun Long.scaledToMilliseconds(timescale: Long): Long {
        if (timescale <= 0L) return 0L
        val whole = this / timescale
        val remainder = this % timescale
        return whole
            .saturatedMultiply(1_000L)
            .saturatedAdd((remainder.toDouble() * 1_000.0 / timescale).roundToLong())
    }

    private fun Long.saturatedMultiply(other: Long): Long =
        runCatching { Math.multiplyExact(this, other) }.getOrElse {
            if ((this < 0L) xor (other < 0L)) Long.MIN_VALUE else Long.MAX_VALUE
        }

    private fun Long.saturatedAdd(other: Long): Long =
        runCatching { Math.addExact(this, other) }.getOrElse {
            if (other < 0L) Long.MIN_VALUE else Long.MAX_VALUE
        }

    private fun List<RawMediaChapter>.asResult(): JvmMediaChapterProbeResult? =
        takeIf(List<*>::isNotEmpty)?.let(::JvmMediaChapterProbeResult)

    private fun ByteArray.looksLikeIsoBmff(): Boolean {
        if (size < 12) return false
        return ascii(4, 4) in ISO_FIRST_BOX_TYPES ||
            parseIsoBox(this, 0, size)?.type in ISO_FIRST_BOX_TYPES
    }

    private fun String.looksLikeHlsUri(): Boolean =
        substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    private fun readSourceBoxHeader(
        source: ChapterByteSource,
        offset: Long,
        parentEnd: Long,
    ): SourceBoxHeader? {
        val header = source.read(offset, ISO_EXTENDED_BOX_HEADER_BYTES) ?: return null
        if (header.size < ISO_BOX_HEADER_BYTES) return null
        val size32 = header.beUInt(0)?.toLong() ?: return null
        val type = header.ascii(4, 4)
        val headerBytes: Int
        val size: Long
        when (size32) {
            0L -> {
                headerBytes = ISO_BOX_HEADER_BYTES
                size = parentEnd - offset
            }
            1L -> {
                if (header.size < ISO_EXTENDED_BOX_HEADER_BYTES) return null
                headerBytes = ISO_EXTENDED_BOX_HEADER_BYTES
                size = header.beLong(8)?.takeIf { it >= ISO_EXTENDED_BOX_HEADER_BYTES } ?: return null
            }
            else -> {
                headerBytes = ISO_BOX_HEADER_BYTES
                size = size32
            }
        }
        val end = offset.saturatedAdd(size)
        if (size < headerBytes || end <= offset || end > parentEnd) return null
        return SourceBoxHeader(type = type, start = offset, size = size, end = end)
    }

    private fun parseIsoBox(
        bytes: ByteArray,
        offset: Int,
        parentEnd: Int,
    ): IsoBox? {
        if (offset < 0 || offset + ISO_BOX_HEADER_BYTES > parentEnd || parentEnd > bytes.size) return null
        val size32 = bytes.beUInt(offset)?.toLong() ?: return null
        val type = bytes.ascii(offset + 4, 4)
        val headerSize: Int
        val size: Long
        when (size32) {
            0L -> {
                headerSize = ISO_BOX_HEADER_BYTES
                size = (parentEnd - offset).toLong()
            }
            1L -> {
                if (offset + ISO_EXTENDED_BOX_HEADER_BYTES > parentEnd) return null
                headerSize = ISO_EXTENDED_BOX_HEADER_BYTES
                size = bytes.beLong(offset + 8) ?: return null
            }
            else -> {
                headerSize = ISO_BOX_HEADER_BYTES
                size = size32
            }
        }
        val end = offset.toLong() + size
        if (size < headerSize || end > parentEnd || end <= offset || end > Int.MAX_VALUE) return null
        return IsoBox(type = type, start = offset, contentStart = offset + headerSize, end = end.toInt())
    }

    private fun IsoBox.children(bytes: ByteArray): List<IsoBox> {
        val initialCursor = contentStart + if (type == "meta") 4 else 0
        if (initialCursor > end) return emptyList()
        var cursor = initialCursor
        var count = 0
        return buildList {
            while (cursor + ISO_BOX_HEADER_BYTES <= end && count++ < MAX_CHILD_BOXES) {
                val child = parseIsoBox(bytes, cursor, end) ?: break
                add(child)
                cursor = child.end
            }
        }
    }

    private fun IsoBox.payload(bytes: ByteArray): ByteArray =
        if (contentStart in 0..end && end <= bytes.size) {
            bytes.copyOfRange(contentStart, end)
        } else {
            byteArrayOf()
        }

    private fun ByteArray.removeId3Unsynchronisation(): ByteArray {
        val result = ByteArray(size)
        var output = 0
        var cursor = 0
        while (cursor < size) {
            val value = this[cursor++]
            result[output++] = value
            if (value == 0xFF.toByte() && cursor < size && this[cursor] == 0.toByte()) cursor++
        }
        return result.copyOf(output)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.indexOf(
        value: Int,
        start: Int,
        end: Int,
    ): Int {
        for (index in start until end.coerceAtMost(size)) {
            if (this[index].uInt() == value) return index
        }
        return -1
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (index in 0..size - needle.size) {
            for (needleIndex in needle.indices) {
                if (this[index + needleIndex] != needle[needleIndex]) continue@outer
            }
            return index
        }
        return -1
    }

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ): String =
        if (offset >= 0 && length >= 0 && offset + length <= size) {
            copyOfRange(offset, offset + length).toString(Charsets.ISO_8859_1)
        } else {
            ""
        }

    private fun ByteArray.beUShort(offset: Int): Int? {
        if (offset < 0 || offset + 2 > size) return null
        return (this[offset].uInt() shl 8) or this[offset + 1].uInt()
    }

    private fun ByteArray.leUShort(offset: Int): Int? {
        if (offset < 0 || offset + 2 > size) return null
        return this[offset].uInt() or (this[offset + 1].uInt() shl 8)
    }

    private fun ByteArray.beInt(offset: Int): Int? = beUInt(offset)?.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt()

    private fun ByteArray.leInt(offset: Int): Int? {
        if (offset < 0 || offset + 4 > size) return null
        return this[offset].uInt() or
            (this[offset + 1].uInt() shl 8) or
            (this[offset + 2].uInt() shl 16) or
            (this[offset + 3].uInt() shl 24)
    }

    private fun ByteArray.beUInt(offset: Int): UInt? {
        if (offset < 0 || offset + 4 > size) return null
        return (
            (this[offset].uInt().toUInt() shl 24) or
                (this[offset + 1].uInt().toUInt() shl 16) or
                (this[offset + 2].uInt().toUInt() shl 8) or
                this[offset + 3].uInt().toUInt()
        )
    }

    private fun ByteArray.beLong(offset: Int): Long? {
        if (offset < 0 || offset + 8 > size) return null
        var result = 0L
        repeat(8) { index -> result = (result shl 8) or this[offset + index].uInt().toLong() }
        return result
    }

    private fun ByteArray.leLong(offset: Int): Long? {
        if (offset < 0 || offset + 8 > size) return null
        var result = 0L
        repeat(8) { index -> result = result or (this[offset + index].uInt().toLong() shl (index * 8)) }
        return result
    }

    private fun ByteArray.syncSafeInt(offset: Int): Int? {
        if (offset < 0 || offset + 4 > size) return null
        if ((0 until 4).any { this[offset + it].uInt() and 0x80 != 0 }) return null
        return (this[offset].uInt() shl 21) or
            (this[offset + 1].uInt() shl 14) or
            (this[offset + 2].uInt() shl 7) or
            this[offset + 3].uInt()
    }

    private fun Int.decodeIso639Language(): String? {
        if (this == 0 || this == 0x7FFF) return null
        val first = ((this shr 10) and 0x1F) + 0x60
        val second = ((this shr 5) and 0x1F) + 0x60
        val third = (this and 0x1F) + 0x60
        if (first !in 'a'.code..'z'.code || second !in 'a'.code..'z'.code || third !in 'a'.code..'z'.code) {
            return null
        }
        return "${first.toChar()}${second.toChar()}${third.toChar()}"
    }

    private fun Byte.uInt(): Int = toInt() and 0xFF

    private fun String.isId3FrameId(): Boolean = length == 4 && all { it in 'A'..'Z' || it in '0'..'9' }

    private data class SourceBoxHeader(
        val type: String,
        val start: Long,
        val size: Long,
        val end: Long,
    )

    private data class IsoBox(
        val type: String,
        val start: Int,
        val contentStart: Int,
        val end: Int,
    )

    private data class MovieTiming(
        val timescale: Long? = null,
        val durationMs: Long? = null,
    )

    private data class SampleTime(
        val startUnits: Long,
        val durationUnits: Long,
    )

    private data class SampleToChunk(
        val firstChunk: Long,
        val samplesPerChunk: Long,
    )

    private data class Id3Chapter(
        val elementId: String,
        val row: RawMediaChapter,
    )

    private data class Id3TableOfContents(
        val elementId: String,
        val isRoot: Boolean,
        val children: List<String>,
    )

    private data class QuickTimeChapterTrack(
        val trackId: Long,
        val timescale: Long,
        val language: String?,
        val sampleSizes: List<Int>,
        val sampleTimes: List<SampleTime>,
        val sampleOffsets: List<Long>,
        val editShiftMs: Long,
    )

    private sealed interface JsonValue {
        data class ObjectValue(
            val values: Map<String, JsonValue>,
        ) : JsonValue {
            fun string(name: String): String? = (values[name] as? StringValue)?.value

            fun number(name: String): Double? = (values[name] as? NumberValue)?.value
        }

        data class ArrayValue(
            val values: List<JsonValue>,
        ) : JsonValue

        data class StringValue(
            val value: String,
        ) : JsonValue

        data class NumberValue(
            val value: Double,
        ) : JsonValue

        data class BooleanValue(
            val value: Boolean,
        ) : JsonValue

        data object NullValue : JsonValue
    }

    private class SimpleJsonParser(
        private val source: String,
    ) {
        private var cursor = 0

        fun parse(): JsonValue {
            val result = parseValue(depth = 0)
            skipWhitespace()
            require(cursor == source.length)
            return result
        }

        private fun parseValue(depth: Int): JsonValue {
            require(depth <= MAX_JSON_DEPTH)
            skipWhitespace()
            require(cursor < source.length)
            return when (source[cursor]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): JsonValue.ObjectValue {
            cursor++
            skipWhitespace()
            val result = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(result)
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(consume(':'))
                result[key] = parseValue(depth)
                skipWhitespace()
                if (consume('}')) break
                require(consume(','))
            }
            return JsonValue.ObjectValue(result)
        }

        private fun parseArray(depth: Int): JsonValue.ArrayValue {
            cursor++
            skipWhitespace()
            val result = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(result)
            while (true) {
                result += parseValue(depth)
                skipWhitespace()
                if (consume(']')) break
                require(consume(','))
            }
            return JsonValue.ArrayValue(result)
        }

        private fun parseString(): String {
            require(consume('"'))
            return buildString {
                while (cursor < source.length) {
                    val char = source[cursor++]
                    when (char) {
                        '"' -> return@buildString
                        '\\' -> {
                            require(cursor < source.length)
                            when (val escaped = source[cursor++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('\u000C')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    require(cursor + 4 <= source.length)
                                    append(source.substring(cursor, cursor + 4).toInt(16).toChar())
                                    cursor += 4
                                }
                                else -> error("Invalid JSON escape")
                            }
                        }
                        else -> {
                            require(char.code >= 0x20)
                            append(char)
                        }
                    }
                }
                error("Unterminated JSON string")
            }
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = cursor
            if (source.getOrNull(cursor) == '-') cursor++
            while (source.getOrNull(cursor)?.isDigit() == true) cursor++
            if (source.getOrNull(cursor) == '.') {
                cursor++
                while (source.getOrNull(cursor)?.isDigit() == true) cursor++
            }
            if (source.getOrNull(cursor) == 'e' || source.getOrNull(cursor) == 'E') {
                cursor++
                if (source.getOrNull(cursor) == '+' || source.getOrNull(cursor) == '-') cursor++
                while (source.getOrNull(cursor)?.isDigit() == true) cursor++
            }
            require(cursor > start)
            return JsonValue.NumberValue(source.substring(start, cursor).toDouble())
        }

        private fun <T : JsonValue> parseLiteral(
            literal: String,
            value: T,
        ): T {
            require(source.startsWith(literal, cursor))
            cursor += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        }

        private fun consume(char: Char): Boolean =
            if (source.getOrNull(cursor) == char) {
                cursor++
                true
            } else {
                false
            }
    }

    private const val PROBE_PREFIX_BYTES = 16 * 1024 * 1024
    private const val ID3_HEADER_BYTES = 10
    private const val ID3_FRAME_HEADER_BYTES = 10
    private const val ID3_FLAG_UNSYNCHRONISATION = 0x80
    private const val ID3_FLAG_EXTENDED_HEADER = 0x40
    private const val ID3_CTOC_FLAG_TOP_LEVEL = 0x02
    private const val ISO_BOX_HEADER_BYTES = 8
    private const val ISO_EXTENDED_BOX_HEADER_BYTES = 16
    private const val MAX_MOOV_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOP_LEVEL_BOXES = 4_096
    private const val MAX_CHILD_BOXES = 65_536
    private const val MAX_CHAPTERS = 10_000
    private const val MAX_QUICKTIME_CHAPTER_SAMPLES = 10_000
    private const val MAX_CHAPTER_SAMPLE_BYTES = 4 * 1024 * 1024
    private const val MAX_SAMPLE_TABLE_ENTRIES = 100_000
    private const val HUNDRED_NANOSECONDS_PER_MILLISECOND = 10_000L
    private const val ASF_OBJECT_HEADER_BYTES = 24
    private const val ASF_MARKER_FIXED_BYTES = 48
    private const val ASF_MARKER_ENTRY_FIXED_BYTES = 30
    private const val ASF_FILE_PROPERTIES_PLAY_DURATION_OFFSET = 40
    private const val ASF_FILE_PROPERTIES_PREROLL_OFFSET = 56
    private const val MAX_ASF_HEADER_BYTES = 16 * 1024 * 1024
    private const val MAX_ASF_MARKER_TITLE_CHARS = 32_768
    private const val MAX_HLS_RESOURCE_BYTES = 4 * 1024 * 1024
    private const val NETWORK_TIMEOUT_MS = 5_000
    private const val USER_AGENT = "ComposeMediaPlayer/2.0"
    private const val APPLE_HLS_CHAPTERS_DATA_ID = "com.apple.hls.chapters"
    private const val HLS_DEFINE_PREFIX = "#EXT-X-DEFINE:"
    private const val HLS_SESSION_DATA_PREFIX = "#EXT-X-SESSION-DATA:"
    private const val MAX_HLS_VARIABLE_EXPANSION_PASSES = 8
    private const val MAX_JSON_DEPTH = 32
    private val HLS_VARIABLE_REFERENCE_REGEX = Regex("\\{\\$([A-Za-z0-9_-]+)}")
    private val UINT32_MAX = UInt.MAX_VALUE
    private val ID3_SIGNATURE = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
    private val ISO_FIRST_BOX_TYPES = setOf("ftyp", "moov", "free", "wide", "skip", "uuid")
    private val ASF_HEADER_GUID =
        byteArrayOf(
            0x30,
            0x26,
            0xB2.toByte(),
            0x75,
            0x8E.toByte(),
            0x66,
            0xCF.toByte(),
            0x11,
            0xA6.toByte(),
            0xD9.toByte(),
            0x00,
            0xAA.toByte(),
            0x00,
            0x62,
            0xCE.toByte(),
            0x6C,
        )
    private val ASF_FILE_PROPERTIES_GUID =
        byteArrayOf(
            0xA1.toByte(),
            0xDC.toByte(),
            0xAB.toByte(),
            0x8C.toByte(),
            0x47,
            0xA9.toByte(),
            0xCF.toByte(),
            0x11,
            0x8E.toByte(),
            0xE4.toByte(),
            0x00,
            0xC0.toByte(),
            0x0C,
            0x20,
            0x53,
            0x65,
        )
    private val ASF_MARKER_GUID =
        byteArrayOf(
            0x01,
            0xCD.toByte(),
            0x87.toByte(),
            0xF4.toByte(),
            0x51,
            0xA9.toByte(),
            0xCF.toByte(),
            0x11,
            0x8E.toByte(),
            0xE6.toByte(),
            0x00,
            0xC0.toByte(),
            0x0C,
            0x20,
            0x53,
            0x65,
        )
}

private interface ChapterByteSource : Closeable {
    val length: Long?

    fun read(
        offset: Long,
        byteCount: Int,
    ): ByteArray?

    companion object {
        fun open(
            uri: String,
            requestHeaders: Map<String, String>,
        ): ChapterByteSource {
            val parsed = runCatching { URI.create(uri) }.getOrNull()
            return when (parsed?.scheme?.lowercase()) {
                "http", "https" -> HttpChapterByteSource(parsed, requestHeaders)
                "file" -> FileChapterByteSource(File(parsed))
                null -> FileChapterByteSource(File(uri))
                else -> throw IllegalArgumentException("Unsupported chapter probe URI")
            }
        }
    }
}

private class FileChapterByteSource(
    file: File,
) : ChapterByteSource {
    private val randomAccess = RandomAccessFile(file, "r")
    override val length: Long = randomAccess.length()

    override fun read(
        offset: Long,
        byteCount: Int,
    ): ByteArray? {
        if (offset < 0L || byteCount <= 0 || offset >= length) return null
        val count = byteCount.toLong().coerceAtMost(length - offset).toInt()
        val result = ByteArray(count)
        synchronized(randomAccess) {
            randomAccess.seek(offset)
            randomAccess.readFully(result)
        }
        return result
    }

    override fun close() = randomAccess.close()
}

private class HttpChapterByteSource(
    private val uri: URI,
    private val requestHeaders: Map<String, String>,
) : ChapterByteSource {
    private var discoveredLength: Long? = null
    override val length: Long?
        get() = discoveredLength ?: probeLength().also { discoveredLength = it }

    override fun read(
        offset: Long,
        byteCount: Int,
    ): ByteArray? {
        if (offset < 0L || byteCount <= 0) return null
        val end = offset + byteCount - 1L
        val connection = openConnection("GET")
        return try {
            connection.setRequestProperty("Range", "bytes=$offset-$end")
            val response = connection.responseCode
            if (response !in 200..299) return null
            connection
                .getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { discoveredLength = it }
            if (response == HttpURLConnection.HTTP_OK) {
                connection.contentLengthLong.takeIf { it > 0L }?.let { discoveredLength = it }
            }
            connection.inputStream.use { input ->
                if (response == HttpURLConnection.HTTP_OK && offset > 0L) {
                    var remaining = offset
                    while (remaining > 0L) {
                        val skipped = input.skip(remaining)
                        if (skipped > 0L) {
                            remaining -= skipped
                        } else if (input.read() >= 0) {
                            remaining--
                        } else {
                            return null
                        }
                    }
                }
                input.readNBytes(byteCount).takeIf(ByteArray::isNotEmpty)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun probeLength(): Long? {
        val connection = openConnection("HEAD")
        return try {
            connection.responseCode
            connection.contentLengthLong.takeIf { it > 0L }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(method: String): HttpURLConnection =
        (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", "ComposeMediaPlayer/2.0")
            requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    override fun close() = Unit
}
