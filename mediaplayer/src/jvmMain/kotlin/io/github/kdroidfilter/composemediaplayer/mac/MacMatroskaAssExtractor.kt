package io.github.kdroidfilter.composemediaplayer.mac

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

private const val EBML_ID_SEGMENT = 0x18538067L
private const val EBML_ID_INFO = 0x1549A966L
private const val EBML_ID_TRACKS = 0x1654AE6BL
private const val EBML_ID_TRACK_ENTRY = 0xAEL
private const val EBML_ID_ATTACHMENTS = 0x1941A469L
private const val EBML_ID_ATTACHED_FILE = 0x61A7L
private const val EBML_ID_CLUSTER = 0x1F43B675L
private const val EBML_ID_TIMECODE_SCALE = 0x2AD7B1L
private const val EBML_ID_DURATION = 0x4489L
private const val EBML_ID_TRACK_NUMBER = 0xD7L
private const val EBML_ID_TRACK_TYPE = 0x83L
private const val EBML_ID_FLAG_DEFAULT = 0x88L
private const val EBML_ID_TRACK_NAME = 0x536EL
private const val EBML_ID_TRACK_LANGUAGE = 0x22B59CL
private const val EBML_ID_TRACK_LANGUAGE_IETF = 0x22B59DL
private const val EBML_ID_CODEC_ID = 0x86L
private const val EBML_ID_CODEC_PRIVATE = 0x63A2L
private const val EBML_ID_VIDEO = 0xE0L
private const val EBML_ID_PIXEL_WIDTH = 0xB0L
private const val EBML_ID_PIXEL_HEIGHT = 0xBAL
private const val EBML_ID_AUDIO = 0xE1L
private const val EBML_ID_AUDIO_SAMPLING_FREQUENCY = 0xB5L
private const val EBML_ID_AUDIO_CHANNELS = 0x9FL
private const val EBML_ID_FILE_NAME = 0x466EL
private const val EBML_ID_FILE_MIME_TYPE = 0x4660L
private const val EBML_ID_FILE_DATA = 0x465CL
private const val EBML_ID_CLUSTER_TIMECODE = 0xE7L
private const val EBML_ID_SIMPLE_BLOCK = 0xA3L
private const val EBML_ID_BLOCK_GROUP = 0xA0L
private const val EBML_ID_BLOCK = 0xA1L
private const val EBML_ID_BLOCK_DURATION = 0x9BL

private const val MATROSKA_TRACK_TYPE_VIDEO = 1
private const val MATROSKA_TRACK_TYPE_AUDIO = 2
private const val MATROSKA_TRACK_TYPE_SUBTITLE = 17
private const val MATROSKA_DEFAULT_TIMECODE_SCALE = 1_000_000L
private const val DEFAULT_SUBTITLE_DURATION_MS = 5_000L
private const val MAX_TEXT_ELEMENT_BYTES = 4L * 1024L * 1024L
private const val MAX_BINARY_ELEMENT_BYTES = 64L * 1024L * 1024L
private const val FAST_HEADER_RANGE_BYTES = 64L * 1024L
private const val FAST_MAX_HEADER_RANGE_BYTES = 1024L * 1024L
private const val FAST_INITIAL_CUES_RANGE_BYTES = 2L * 1024L * 1024L
private const val FAST_MAX_CUES_RANGE_BYTES = 8L * 1024L * 1024L
private const val FAST_MAX_CLUSTER_RANGE_BYTES = 4L * 1024L * 1024L
private const val FAST_SUBTITLE_LOOKAHEAD_MS = 180_000L
private const val FAST_MAX_CLUSTERS = 12

internal object MacMatroskaAssExtractor {
    fun probe(uri: String): MacMatroskaProbeInfo? =
        scan(uri = uri, targetStreamIndex = null, stopAfterTracks = true)?.probeInfo

    fun extractPartial(
        uri: String,
        streamIndex: Int,
        playbackTimeMs: Long,
    ): MacAssSubtitleData? =
        runCatching {
            MatroskaRangeSource.open(uri).use { source ->
                val header = readFastHeader(source) ?: return@use null
                val targetTrack =
                    header.tracks.firstOrNull { it.streamIndex == streamIndex }
                        ?: return@use null
                if (!targetTrack.isAssLikeSubtitle()) return@use null

                val fileSize = source.length ?: return@use null
                val cues = readFastCues(source, fileSize, header.timecodeScale)
                if (cues.isEmpty()) return@use null

                extractFastCueClusters(
                    source = source,
                    fileSize = fileSize,
                    header = header,
                    targetTrack = targetTrack,
                    cues = cues,
                    playbackTimeMs = playbackTimeMs,
                )
            }
        }.onFailure { error ->
            macLogger.d { "Fast Matroska ASS extraction failed: ${error.message}" }
        }.getOrNull()

    fun extract(
        uri: String,
        streamIndex: Int,
    ): MacAssSubtitleData? =
        scan(uri = uri, targetStreamIndex = streamIndex, stopAfterTracks = false)?.subtitleData

    private fun scan(
        uri: String,
        targetStreamIndex: Int?,
        stopAfterTracks: Boolean,
    ): MacMatroskaScanResult? {
        openInput(uri).use { input ->
            val state = MatroskaScanState(targetStreamIndex = targetStreamIndex)
            while (!input.isAtEnd()) {
                val element = input.readElementHeaderOrNull() ?: break
                when (element.id) {
                    EBML_ID_SEGMENT -> {
                        parseSegment(input, element.endPosition, state, stopAfterTracks)
                        break
                    }
                    else -> input.skipElement(element)
                }
            }

            if (state.tracks.isEmpty()) return null
            return MacMatroskaScanResult(
                probeInfo =
                    MacMatroskaProbeInfo(
                        durationSeconds = state.durationSeconds(),
                        tracks = state.tracks.toList(),
                    ),
                subtitleData =
                    targetStreamIndex?.let {
                        val targetTrack = state.targetTrack ?: return@let null
                        if (!targetTrack.isAssLikeSubtitle()) return@let null
                        if (state.dialogueLines.isEmpty()) return@let null
                        MacAssSubtitleData(
                            content = buildAssContent(targetTrack.codecPrivate, state.dialogueLines),
                            fonts = state.fonts.toList(),
                        )
                    },
            )
        }
    }

    private fun readFastHeader(source: MatroskaRangeSource): MacMatroskaFastHeader? {
        var rangeSize = FAST_HEADER_RANGE_BYTES
        while (rangeSize <= FAST_MAX_HEADER_RANGE_BYTES) {
            val end = ((source.length ?: rangeSize) - 1L).coerceAtMost(rangeSize - 1L)
            val bytes = source.readRange(0L, end) ?: return null
            parseFastHeader(bytes)?.let { header ->
                if (header.tracks.isNotEmpty()) return header
            }
            if (bytes.size.toLong() < rangeSize) break
            rangeSize *= 4L
        }
        return null
    }

    private fun parseFastHeader(bytes: ByteArray): MacMatroskaFastHeader? {
        var segmentDataStart = 0L
        var timestampScale = MATROSKA_DEFAULT_TIMECODE_SCALE
        var durationTicks: Double? = null
        var nextStreamIndex = 0
        val tracks = mutableListOf<MacMatroskaTrack>()
        val fonts = mutableListOf<MacAssFontAttachment>()

        parseByteElements(bytes, 0, bytes.size, depth = 0) { id, payloadStart, payloadEnd, _, _ ->
            when (id) {
                EBML_ID_SEGMENT -> segmentDataStart = payloadStart.toLong()
                EBML_ID_TIMECODE_SCALE -> timestampScale = readUInt(bytes, payloadStart, payloadEnd)
                EBML_ID_DURATION -> durationTicks = readFloat(bytes, payloadStart, payloadEnd)
                EBML_ID_TRACK_ENTRY -> {
                    parseTrackEntryBytes(bytes, payloadStart, payloadEnd, nextStreamIndex)?.let(tracks::add)
                    nextStreamIndex += 1
                }
                EBML_ID_ATTACHED_FILE -> parseAttachedFileBytes(bytes, payloadStart, payloadEnd)?.let(fonts::add)
            }
        }

        if (segmentDataStart <= 0L || tracks.isEmpty()) return null
        return MacMatroskaFastHeader(
            segmentDataStart = segmentDataStart,
            timecodeScale = timestampScale,
            durationTicks = durationTicks,
            tracks = tracks,
            fonts = fonts,
        )
    }

    private fun parseTrackEntryBytes(
        bytes: ByteArray,
        start: Int,
        end: Int,
        streamIndex: Int,
    ): MacMatroskaTrack? {
        var trackNumber: Long? = null
        var trackType: Int? = null
        var isDefault = false
        var name = ""
        var language = ""
        var codecId = ""
        var codecPrivate = ""
        var videoWidth: Int? = null
        var videoHeight: Int? = null
        var audioChannels: Int? = null
        var audioSampleRate: Int? = null

        parseByteElements(bytes, start, end, depth = 0) { id, payloadStart, payloadEnd, _, _ ->
            when (id) {
                EBML_ID_TRACK_NUMBER -> trackNumber = readUInt(bytes, payloadStart, payloadEnd)
                EBML_ID_TRACK_TYPE -> trackType = readUInt(bytes, payloadStart, payloadEnd).toInt()
                EBML_ID_FLAG_DEFAULT -> isDefault = readUInt(bytes, payloadStart, payloadEnd) == 1L
                EBML_ID_TRACK_NAME -> name = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_TRACK_LANGUAGE -> language = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_TRACK_LANGUAGE_IETF -> language = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_CODEC_ID -> codecId = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_CODEC_PRIVATE -> codecPrivate = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_PIXEL_WIDTH -> videoWidth = readUInt(bytes, payloadStart, payloadEnd).toInt()
                EBML_ID_PIXEL_HEIGHT -> videoHeight = readUInt(bytes, payloadStart, payloadEnd).toInt()
                EBML_ID_AUDIO_CHANNELS -> audioChannels = readUInt(bytes, payloadStart, payloadEnd).toInt()
                EBML_ID_AUDIO_SAMPLING_FREQUENCY -> audioSampleRate = readFloat(bytes, payloadStart, payloadEnd).roundToLong().toInt()
            }
        }

        val number = trackNumber ?: return null
        val type = trackType ?: return null
        return MacMatroskaTrack(
            streamIndex = streamIndex,
            trackNumber = number,
            type = type,
            codecId = codecId,
            codecPrivate = codecPrivate,
            name = name,
            language = language,
            isDefault = isDefault,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            audioChannels = audioChannels,
            audioSampleRate = audioSampleRate,
        )
    }

    private fun parseAttachedFileBytes(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): MacAssFontAttachment? {
        var fileName = ""
        var mimeType = ""
        var data: ByteArray? = null
        parseByteElements(bytes, start, end, depth = 0) { id, payloadStart, payloadEnd, _, _ ->
            when (id) {
                EBML_ID_FILE_NAME -> fileName = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_FILE_MIME_TYPE -> mimeType = decodeText(bytes, payloadStart, payloadEnd)
                EBML_ID_FILE_DATA -> {
                    val size = payloadEnd - payloadStart
                    if (size <= MAX_BINARY_ELEMENT_BYTES) {
                        data = bytes.copyOfRange(payloadStart, payloadEnd)
                    }
                }
            }
        }
        if (!isFontAttachment(fileName, mimeType)) return null
        val fontBytes = data ?: return null
        return MacAssFontAttachment(name = fileName.ifBlank { "font-${fontBytes.contentHashCode()}" }, data = fontBytes)
    }

    private fun readFastCues(
        source: MatroskaRangeSource,
        fileSize: Long,
        timestampScale: Long,
    ): List<MatroskaCue> {
        var rangeSize = FAST_INITIAL_CUES_RANGE_BYTES.coerceAtMost(fileSize)
        while (rangeSize <= FAST_MAX_CUES_RANGE_BYTES.coerceAtMost(fileSize)) {
            val start = (fileSize - rangeSize).coerceAtLeast(0L)
            val bytes = source.readRange(start, fileSize - 1L) ?: return emptyList()
            val cuesStart = findCuesStart(bytes)
            if (cuesStart >= 0) {
                return parseCues(bytes.copyOfRange(cuesStart, bytes.size), timestampScale)
            }
            if (rangeSize == fileSize) break
            rangeSize = (rangeSize * 4L).coerceAtMost(fileSize)
        }
        return emptyList()
    }

    private fun parseCues(
        bytes: ByteArray,
        timestampScale: Long,
    ): List<MatroskaCue> {
        val cues = mutableListOf<MatroskaCue>()
        var currentTimeMs: Long? = null
        var currentTrack: Long? = null
        var currentCluster: Long? = null
        var currentRelative: Long = 0L

        fun flushPosition() {
            val time = currentTimeMs
            val track = currentTrack
            val cluster = currentCluster
            if (time != null && track != null && cluster != null) {
                cues.add(MatroskaCue(timeMs = time, trackNumber = track, clusterPosition = cluster, relativePosition = currentRelative))
            }
        }

        parseByteElements(bytes, 0, bytes.size, depth = 0) { id, payloadStart, payloadEnd, _, _ ->
            when (id) {
                0xBBL -> {
                    flushPosition()
                    currentTimeMs = null
                    currentTrack = null
                    currentCluster = null
                    currentRelative = 0L
                }
                0xB3L -> currentTimeMs = (readUInt(bytes, payloadStart, payloadEnd) * timestampScale.toDouble() / 1_000_000.0).roundToLong()
                0xB7L -> {
                    flushPosition()
                    currentTrack = null
                    currentCluster = null
                    currentRelative = 0L
                }
                0xF7L -> currentTrack = readUInt(bytes, payloadStart, payloadEnd)
                0xF1L -> currentCluster = readUInt(bytes, payloadStart, payloadEnd)
                0xF0L -> currentRelative = readUInt(bytes, payloadStart, payloadEnd)
            }
        }
        flushPosition()
        return cues.sortedWith(compareBy<MatroskaCue> { it.timeMs }.thenBy { it.clusterPosition })
    }

    private fun extractFastCueClusters(
        source: MatroskaRangeSource,
        fileSize: Long,
        header: MacMatroskaFastHeader,
        targetTrack: MacMatroskaTrack,
        cues: List<MatroskaCue>,
        playbackTimeMs: Long,
    ): MacAssSubtitleData? {
        val targetCues = chooseFastCues(cues, targetTrack.trackNumber, playbackTimeMs)
        if (targetCues.isEmpty()) return null

        val allClusters = cues.map { it.clusterPosition }.distinct().sorted()
        val state =
            MatroskaScanState(targetStreamIndex = targetTrack.streamIndex).apply {
                timecodeScale = header.timecodeScale
                durationTicks = header.durationTicks
                nextStreamIndex = header.tracks.size
                this.targetTrack = targetTrack
                tracks.addAll(header.tracks)
                fonts.addAll(header.fonts)
            }

        targetCues.forEach { cue ->
            val nextCluster = allClusters.firstOrNull { it > cue.clusterPosition }
            val rangeStart = header.segmentDataStart + cue.clusterPosition
            val cappedRangeEnd = (rangeStart + FAST_MAX_CLUSTER_RANGE_BYTES - 1L).coerceAtMost(fileSize - 1L)
            val rangeEnd =
                if (nextCluster != null) {
                    (header.segmentDataStart + nextCluster - 1L).coerceAtMost(cappedRangeEnd)
                } else {
                    cappedRangeEnd
                }
            val bytes = source.readRange(rangeStart, rangeEnd) ?: return@forEach
            parseClustersFromBytes(bytes, state)
        }

        if (state.dialogueLines.isEmpty()) return null
        return MacAssSubtitleData(
            content = buildAssContent(targetTrack.codecPrivate, state.dialogueLines),
            fonts = state.fonts.toList(),
            isPartial = true,
        )
    }

    private fun chooseFastCues(
        cues: List<MatroskaCue>,
        trackNumber: Long,
        playbackTimeMs: Long,
    ): List<MatroskaCue> {
        val trackCues = cues.filter { it.trackNumber == trackNumber }.sortedBy { it.timeMs }
        if (trackCues.isEmpty()) return emptyList()

        val selected = mutableListOf<MatroskaCue>()
        val previous = trackCues.lastOrNull { it.timeMs <= playbackTimeMs + 250L }
        if (previous != null && playbackTimeMs - previous.timeMs <= 5_000L) selected.add(previous)

        trackCues
            .asSequence()
            .filter { it.timeMs >= playbackTimeMs - 250L }
            .takeWhile { it.timeMs <= playbackTimeMs + FAST_SUBTITLE_LOOKAHEAD_MS }
            .take(FAST_MAX_CLUSTERS)
            .forEach {
                selected.add(it)
            }

        if (selected.isEmpty()) {
            trackCues.firstOrNull { it.timeMs >= playbackTimeMs - 250L }?.let(selected::add)
        }

        val seenClusters = mutableSetOf<Long>()
        return selected.filter { seenClusters.add(it.clusterPosition) }.take(FAST_MAX_CLUSTERS)
    }

    private fun parseClustersFromBytes(
        bytes: ByteArray,
        state: MatroskaScanState,
    ) {
        EbmlInput(ByteArrayInputStream(bytes)).use { input ->
            try {
                while (!input.isAtEnd()) {
                    val element = input.readElementHeaderOrNull() ?: break
                    when (element.id) {
                        EBML_ID_CLUSTER -> parseCluster(input, element.endPosition, state)
                        else -> input.skipElement(element)
                    }
                }
            } catch (_: EOFException) {
                // Range reads can intentionally cap the last cluster; keep any subtitle blocks already parsed.
            }
        }
    }

    private fun parseByteElements(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        callback: (id: Long, payloadStart: Int, payloadEnd: Int, size: Long, elementOffset: Int) -> Unit,
    ) {
        var offset = start
        val limit = end.coerceAtMost(data.size)
        while (offset + 2 <= limit) {
            val id = readVint(data, offset, keepMarker = true) ?: break
            val size = readVint(data, offset + id.length, keepMarker = false) ?: break
            val payloadStart = offset + id.length + size.length
            if (payloadStart > data.size) break
            val declaredEnd =
                if (size.value == Long.MAX_VALUE) {
                    data.size.toLong()
                } else {
                    payloadStart.toLong() + size.value
                }
            val payloadEnd = declaredEnd.coerceAtMost(data.size.toLong()).toInt()
            callback(id.value, payloadStart, payloadEnd.coerceAtMost(limit), size.value, offset)
            if (depth < 8 && id.value in RECURSIVE_BYTE_ELEMENT_IDS) {
                parseByteElements(data, payloadStart, payloadEnd.coerceAtMost(limit), depth + 1, callback)
            }
            if (declaredEnd > limit) break
            offset = declaredEnd.toInt()
        }
    }

    private fun parseSegment(
        input: EbmlInput,
        endPosition: Long,
        state: MatroskaScanState,
        stopAfterTracks: Boolean,
    ) {
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_INFO -> parseInfo(input, element.endPosition, state)
                EBML_ID_TRACKS -> {
                    parseTracks(input, element.endPosition, state)
                    if (stopAfterTracks) return
                }
                EBML_ID_ATTACHMENTS -> parseAttachments(input, element.endPosition, state)
                EBML_ID_CLUSTER -> {
                    if (state.targetTrack != null) {
                        parseCluster(input, element.endPosition, state)
                    } else {
                        input.skipElement(element)
                    }
                }
                else -> input.skipElement(element)
            }
        }
    }

    private fun parseInfo(
        input: EbmlInput,
        endPosition: Long,
        state: MatroskaScanState,
    ) {
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_TIMECODE_SCALE -> state.timecodeScale = input.readUIntElement(element)
                EBML_ID_DURATION -> state.durationTicks = input.readFloatElement(element)
                else -> input.skipElement(element)
            }
        }
    }

    private fun parseTracks(
        input: EbmlInput,
        endPosition: Long,
        state: MatroskaScanState,
    ) {
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_TRACK_ENTRY -> {
                    val streamIndex = state.nextStreamIndex
                    state.nextStreamIndex += 1
                    parseTrackEntry(input, element.endPosition, streamIndex)?.let { track ->
                        state.tracks.add(track)
                        if (track.streamIndex == state.targetStreamIndex) {
                            state.targetTrack = track
                        }
                    }
                }
                else -> input.skipElement(element)
            }
        }
    }

    private fun parseTrackEntry(
        input: EbmlInput,
        endPosition: Long,
        streamIndex: Int,
    ): MacMatroskaTrack? {
        var trackNumber: Long? = null
        var trackType: Int? = null
        var isDefault = false
        var name = ""
        var language = ""
        var codecId = ""
        var codecPrivate = ""
        var videoWidth: Int? = null
        var videoHeight: Int? = null
        var audioChannels: Int? = null
        var audioSampleRate: Int? = null

        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_TRACK_NUMBER -> trackNumber = input.readUIntElement(element)
                EBML_ID_TRACK_TYPE -> trackType = input.readUIntElement(element).toInt()
                EBML_ID_FLAG_DEFAULT -> isDefault = input.readUIntElement(element) == 1L
                EBML_ID_TRACK_NAME -> name = input.readStringElement(element)
                EBML_ID_TRACK_LANGUAGE -> language = input.readStringElement(element)
                EBML_ID_TRACK_LANGUAGE_IETF -> language = input.readStringElement(element)
                EBML_ID_CODEC_ID -> codecId = input.readStringElement(element)
                EBML_ID_CODEC_PRIVATE -> codecPrivate = input.readStringElement(element)
                EBML_ID_VIDEO -> {
                    val video = parseVideo(input, element.endPosition)
                    videoWidth = video.width
                    videoHeight = video.height
                }
                EBML_ID_AUDIO -> {
                    val audio = parseAudio(input, element.endPosition)
                    audioChannels = audio.channels
                    audioSampleRate = audio.sampleRate
                }
                else -> input.skipElement(element)
            }
        }

        val number = trackNumber ?: return null
        val type = trackType ?: return null
        return MacMatroskaTrack(
            streamIndex = streamIndex,
            trackNumber = number,
            type = type,
            codecId = codecId,
            codecPrivate = codecPrivate,
            name = name,
            language = language,
            isDefault = isDefault,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            audioChannels = audioChannels,
            audioSampleRate = audioSampleRate,
        )
    }

    private fun parseVideo(
        input: EbmlInput,
        endPosition: Long,
    ): MatroskaVideoInfo {
        var width: Int? = null
        var height: Int? = null
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_PIXEL_WIDTH -> width = input.readUIntElement(element).toInt()
                EBML_ID_PIXEL_HEIGHT -> height = input.readUIntElement(element).toInt()
                else -> input.skipElement(element)
            }
        }
        return MatroskaVideoInfo(width = width, height = height)
    }

    private fun parseAudio(
        input: EbmlInput,
        endPosition: Long,
    ): MatroskaAudioInfo {
        var channels: Int? = null
        var sampleRate: Int? = null
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_AUDIO_CHANNELS -> channels = input.readUIntElement(element).toInt()
                EBML_ID_AUDIO_SAMPLING_FREQUENCY -> sampleRate = input.readFloatElement(element).roundToLong().toInt()
                else -> input.skipElement(element)
            }
        }
        return MatroskaAudioInfo(channels = channels, sampleRate = sampleRate)
    }

    private fun parseAttachments(
        input: EbmlInput,
        endPosition: Long,
        state: MatroskaScanState,
    ) {
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_ATTACHED_FILE -> parseAttachedFile(input, element.endPosition)?.let(state.fonts::add)
                else -> input.skipElement(element)
            }
        }
    }

    private fun parseAttachedFile(
        input: EbmlInput,
        endPosition: Long,
    ): MacAssFontAttachment? {
        var fileName = ""
        var mimeType = ""
        var fileData: ByteArray? = null
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_FILE_NAME -> fileName = input.readStringElement(element)
                EBML_ID_FILE_MIME_TYPE -> mimeType = input.readStringElement(element)
                EBML_ID_FILE_DATA -> fileData = input.readBinaryElement(element, MAX_BINARY_ELEMENT_BYTES)
                else -> input.skipElement(element)
            }
        }

        if (!isFontAttachment(fileName, mimeType)) return null
        val data = fileData ?: return null
        return MacAssFontAttachment(name = fileName.ifBlank { "font-${data.contentHashCode()}" }, data = data)
    }

    private fun parseCluster(
        input: EbmlInput,
        endPosition: Long,
        state: MatroskaScanState,
    ) {
        var clusterTimeMs = 0L
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_CLUSTER_TIMECODE -> clusterTimeMs = state.ticksToMs(input.readUIntElement(element))
                EBML_ID_SIMPLE_BLOCK -> parseBlock(input.readBinaryElement(element), clusterTimeMs, null, state)
                EBML_ID_BLOCK_GROUP -> parseBlockGroup(input, element.endPosition, clusterTimeMs, state)
                else -> input.skipElement(element)
            }
        }
    }

    private fun parseBlockGroup(
        input: EbmlInput,
        endPosition: Long,
        clusterTimeMs: Long,
        state: MatroskaScanState,
    ) {
        var block: ByteArray? = null
        var durationMs: Long? = null
        while (input.position < endPosition && !input.isAtEnd()) {
            val element = input.readElementHeaderOrNull() ?: break
            when (element.id) {
                EBML_ID_BLOCK -> block = input.readBinaryElement(element)
                EBML_ID_BLOCK_DURATION -> durationMs = state.ticksToMs(input.readUIntElement(element))
                else -> input.skipElement(element)
            }
        }
        block?.let { parseBlock(it, clusterTimeMs, durationMs, state) }
    }

    private fun parseBlock(
        block: ByteArray,
        clusterTimeMs: Long,
        durationMs: Long?,
        state: MatroskaScanState,
    ) {
        val targetTrack = state.targetTrack ?: return
        val track = readVint(block, 0, keepMarker = false) ?: return
        if (track.value != targetTrack.trackNumber) return

        val timecodeOffset = track.length
        if (timecodeOffset + 3 > block.size) return
        val blockTimeTicks = readSignedInt16(block, timecodeOffset)
        val flags = block[timecodeOffset + 2].toInt() and 0xFF
        val lacing = (flags and 0x06) shr 1
        if (lacing != 0) return

        val payloadOffset = timecodeOffset + 3
        if (payloadOffset > block.size) return
        val payload = block.copyOfRange(payloadOffset, block.size).decodeUtf8()
        val startMs = clusterTimeMs + state.ticksToMs(blockTimeTicks.toLong())
        val endMs = startMs + (durationMs?.takeIf { it > 0L } ?: DEFAULT_SUBTITLE_DURATION_MS)
        val line =
            if (targetTrack.isAssLikeSubtitle()) {
                parseAssPayload(payload).toDialogueLine(startMs, endMs)
            } else {
                "Dialogue: 0,${formatAssTime(startMs)},${formatAssTime(endMs)},Default,,0,0,0,,$payload"
            }
        state.addDialogueLine(line)
    }

    private fun buildAssContent(
        codecPrivate: String,
        dialogueLines: List<String>,
    ): String {
        val header =
            codecPrivate
                .takeIf { it.contains("[Events]", ignoreCase = true) }
                ?: DEFAULT_ASS_HEADER
        return header.trimEnd() + "\n" + dialogueLines.joinToString("\n") + "\n"
    }

    private fun parseAssPayload(payload: String): MatroskaAssPayload {
        val parts = payload.split(",")
        if (parts.size < 9) {
            return MatroskaAssPayload(text = payload)
        }
        return MatroskaAssPayload(
            layer = parts.getOrNull(1).orEmpty().ifBlank { "0" },
            style = parts.getOrNull(2).orEmpty().ifBlank { "Default" },
            name = parts.getOrNull(3).orEmpty(),
            marginL = parts.getOrNull(4).orEmpty().ifBlank { "0" },
            marginR = parts.getOrNull(5).orEmpty().ifBlank { "0" },
            marginV = parts.getOrNull(6).orEmpty().ifBlank { "0" },
            effect = parts.getOrNull(7).orEmpty(),
            text = parts.drop(8).joinToString(","),
        )
    }

    private fun MatroskaAssPayload.toDialogueLine(
        startMs: Long,
        endMs: Long,
    ): String =
        "Dialogue: " +
            listOf(
                layer,
                formatAssTime(startMs),
                formatAssTime(endMs),
                style,
                name,
                marginL,
                marginR,
                marginV,
                effect,
                text,
            ).joinToString(",")

    private fun formatAssTime(ms: Long): String {
        val totalCentis = (ms.coerceAtLeast(0L) + 5L) / 10L
        val centis = totalCentis % 100L
        val totalSeconds = totalCentis / 100L
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}." +
            centis.toString().padStart(2, '0')
    }

    private fun isFontAttachment(
        fileName: String,
        mimeType: String,
    ): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()
        return lowerName.endsWith(".ttf") ||
            lowerName.endsWith(".otf") ||
            lowerName.endsWith(".ttc") ||
            lowerMime.contains("font") ||
            lowerMime == "application/x-truetype-font" ||
            lowerMime == "application/vnd.ms-opentype"
    }

    private fun openInput(uri: String): EbmlInput {
        val input =
            when {
                uri.startsWith("http://", ignoreCase = true) ||
                    uri.startsWith("https://", ignoreCase = true) -> {
                    val connection = URL(uri).openConnection()
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 300_000
                    connection.setRequestProperty("User-Agent", "ComposeMediaPlayer")
                    if (connection is HttpURLConnection) {
                        connection.instanceFollowRedirects = true
                    }
                    connection.getInputStream()
                }
                uri.startsWith("file:", ignoreCase = true) -> File(URI(uri)).inputStream()
                else -> File(uri).inputStream()
            }
        return EbmlInput(input)
    }

    private fun readUInt(
        data: ByteArray,
        start: Int,
        end: Int,
    ): Long {
        var value = 0L
        for (index in start until end.coerceAtMost(data.size)) {
            value = value * 256L + (data[index].toInt() and 0xFF)
        }
        return value
    }

    private fun readFloat(
        data: ByteArray,
        start: Int,
        end: Int,
    ): Double {
        if (start < 0 || end > data.size) return 0.0
        return when (end - start) {
            4 -> ByteBuffer.wrap(data, start, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble()
            8 -> ByteBuffer.wrap(data, start, 8).order(ByteOrder.BIG_ENDIAN).double
            else -> 0.0
        }
    }

    private fun decodeText(
        data: ByteArray,
        start: Int,
        end: Int,
    ): String =
        data.copyOfRange(start, end.coerceAtMost(data.size)).decodeUtf8()

    private fun findCuesStart(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 3) {
            if (
                bytes[index] == 0x1C.toByte() &&
                bytes[index + 1] == 0x53.toByte() &&
                bytes[index + 2] == 0xBB.toByte() &&
                bytes[index + 3] == 0x6B.toByte()
            ) {
                return index
            }
        }
        return -1
    }

    private fun readVint(
        data: ByteArray,
        offset: Int,
        keepMarker: Boolean,
    ): Vint? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var length = 1
        while (length <= 8 && (first and mask) == 0) {
            mask = mask shr 1
            length += 1
        }
        if (length > 8 || offset + length > data.size) return null
        var value = if (keepMarker) first.toLong() else (first and (mask - 1)).toLong()
        for (index in 1 until length) {
            value = value * 256L + (data[offset + index].toInt() and 0xFF)
        }
        return Vint(length = length, value = value)
    }

    private fun readSignedInt16(
        data: ByteArray,
        offset: Int,
    ): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()

    private fun ByteArray.decodeUtf8(): String =
        toString(Charsets.UTF_8).replace(Regex("\u0000+$"), "")

    private const val DEFAULT_ASS_HEADER =
        "[Script Info]\n" +
            "ScriptType: v4.00+\n\n" +
            "[V4+ Styles]\n" +
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
            "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, " +
            "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
            "Style: Default,Arial,24,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,10,10,18,1\n\n" +
            "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
}

private val RECURSIVE_BYTE_ELEMENT_IDS =
    setOf(
        0x1A45DFA3L,
        EBML_ID_SEGMENT,
        EBML_ID_INFO,
        EBML_ID_TRACKS,
        EBML_ID_TRACK_ENTRY,
        EBML_ID_VIDEO,
        EBML_ID_AUDIO,
        EBML_ID_ATTACHMENTS,
        EBML_ID_ATTACHED_FILE,
        0x1C53BB6BL,
        0xBBL,
        0xB7L,
    )

internal data class MacMatroskaProbeInfo(
    val durationSeconds: Double?,
    val tracks: List<MacMatroskaTrack>,
)

internal data class MacMatroskaTrack(
    val streamIndex: Int,
    val trackNumber: Long,
    val type: Int,
    val codecId: String,
    val codecPrivate: String,
    val name: String,
    val language: String,
    val isDefault: Boolean,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
) {
    fun isVideo(): Boolean = type == MATROSKA_TRACK_TYPE_VIDEO

    fun isAudio(): Boolean = type == MATROSKA_TRACK_TYPE_AUDIO

    fun isSubtitle(): Boolean = type == MATROSKA_TRACK_TYPE_SUBTITLE

    fun isAssLikeSubtitle(): Boolean {
        val codec = codecId.uppercase()
        return isSubtitle() && (codec.contains("ASS") || codec.contains("SSA"))
    }

    fun isSrtSubtitle(): Boolean {
        val codec = codecId.uppercase()
        return isSubtitle() && (codec.contains("UTF8") || codec.contains("SRT"))
    }
}

private data class MacMatroskaScanResult(
    val probeInfo: MacMatroskaProbeInfo,
    val subtitleData: MacAssSubtitleData?,
)

private data class MacMatroskaFastHeader(
    val segmentDataStart: Long,
    val timecodeScale: Long,
    val durationTicks: Double?,
    val tracks: List<MacMatroskaTrack>,
    val fonts: List<MacAssFontAttachment>,
)

private data class MatroskaCue(
    val timeMs: Long,
    val trackNumber: Long,
    val clusterPosition: Long,
    val relativePosition: Long,
)

private data class MatroskaScanState(
    val targetStreamIndex: Int?,
) {
    var timecodeScale: Long = MATROSKA_DEFAULT_TIMECODE_SCALE
    var durationTicks: Double? = null
    var nextStreamIndex: Int = 0
    var targetTrack: MacMatroskaTrack? = null
    val tracks = mutableListOf<MacMatroskaTrack>()
    val fonts = mutableListOf<MacAssFontAttachment>()
    val dialogueLines = mutableListOf<String>()
    private val dialogueKeys = mutableSetOf<String>()

    fun ticksToMs(ticks: Long): Long =
        (ticks * timecodeScale.toDouble() / 1_000_000.0).roundToLong()

    fun durationSeconds(): Double? =
        durationTicks?.let { it * timecodeScale.toDouble() / 1_000_000_000.0 }

    fun addDialogueLine(line: String) {
        if (dialogueKeys.add(line)) dialogueLines.add(line)
    }
}

private data class MatroskaVideoInfo(
    val width: Int?,
    val height: Int?,
)

private data class MatroskaAudioInfo(
    val channels: Int?,
    val sampleRate: Int?,
)

private data class MatroskaAssPayload(
    val layer: String = "0",
    val style: String = "Default",
    val name: String = "",
    val marginL: String = "0",
    val marginR: String = "0",
    val marginV: String = "0",
    val effect: String = "",
    val text: String,
)

private data class EbmlElement(
    val id: Long,
    val size: Long,
    val payloadPosition: Long,
) {
    val endPosition: Long =
        if (size == Long.MAX_VALUE) Long.MAX_VALUE else payloadPosition + size
}

private data class Vint(
    val length: Int,
    val value: Long,
)

private interface MatroskaRangeSource : Closeable {
    val length: Long?

    fun readRange(
        start: Long,
        endInclusive: Long,
    ): ByteArray?

    companion object {
        fun open(uri: String): MatroskaRangeSource =
            when {
                uri.startsWith("http://", ignoreCase = true) ||
                    uri.startsWith("https://", ignoreCase = true) -> HttpMatroskaRangeSource(uri)
                uri.startsWith("file:", ignoreCase = true) -> FileMatroskaRangeSource(File(URI(uri)))
                else -> FileMatroskaRangeSource(File(uri))
            }
    }
}

private class FileMatroskaRangeSource(file: File) : MatroskaRangeSource {
    private val file = RandomAccessFile(file, "r")
    override val length: Long = file.length()

    override fun readRange(
        start: Long,
        endInclusive: Long,
    ): ByteArray? {
        if (start < 0L || start >= length || endInclusive < start) return null
        val end = endInclusive.coerceAtMost(length - 1L)
        val size = end - start + 1L
        if (size > Int.MAX_VALUE) return null
        val bytes = ByteArray(size.toInt())
        file.seek(start)
        file.readFully(bytes)
        return bytes
    }

    override fun close() {
        file.close()
    }
}

private class HttpMatroskaRangeSource(private val uri: String) : MatroskaRangeSource {
    override val length: Long? by lazy { readLength() }

    override fun readRange(
        start: Long,
        endInclusive: Long,
    ): ByteArray? {
        if (start < 0L || endInclusive < start) return null
        val expectedSize = endInclusive - start + 1L
        if (expectedSize > Int.MAX_VALUE) return null

        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ComposeMediaPlayer")
        connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL && start > 0L) return null
            connection.inputStream.use { input -> input.readAtMost(expectedSize.toInt()) }
        } finally {
            connection.disconnect()
        }
    }

    override fun close() = Unit

    private fun readLength(): Long? {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ComposeMediaPlayer")
        return try {
            connection.inputStream.close()
            connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        val output = ByteArray(maxBytes)
        var offset = 0
        while (offset < maxBytes) {
            val read = read(output, offset, maxBytes - offset)
            if (read < 0) break
            offset += read
        }
        return output.copyOf(offset)
    }
}

private class EbmlInput(input: InputStream) : AutoCloseable {
    private val input = BufferedInputStream(input, 64 * 1024)
    var position: Long = 0L
        private set
    private var endReached = false

    fun isAtEnd(): Boolean = endReached

    fun readElementHeaderOrNull(): EbmlElement? {
        val id = readVint(keepMarker = true) ?: return null
        val size = readVint(keepMarker = false) ?: return null
        return EbmlElement(id = id.value, size = size.value, payloadPosition = position)
    }

    fun readUIntElement(element: EbmlElement): Long {
        val bytes = readBinaryElement(element, maxBytes = 8)
        var value = 0L
        for (byte in bytes) value = value * 256L + (byte.toInt() and 0xFF)
        return value
    }

    fun readFloatElement(element: EbmlElement): Double {
        val bytes = readBinaryElement(element, maxBytes = 8)
        return when (bytes.size) {
            4 -> ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).float.toDouble()
            8 -> ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).double
            else -> 0.0
        }
    }

    fun readStringElement(element: EbmlElement): String =
        readBinaryElement(element, MAX_TEXT_ELEMENT_BYTES).toString(Charsets.UTF_8).replace(Regex("\u0000+$"), "")

    fun readBinaryElement(
        element: EbmlElement,
        maxBytes: Long = MAX_BINARY_ELEMENT_BYTES,
    ): ByteArray {
        if (element.size == Long.MAX_VALUE || element.size > maxBytes || element.size > Int.MAX_VALUE) {
            skipElement(element)
            return ByteArray(0)
        }
        return readBytes(element.size.toInt())
    }

    fun skipElement(element: EbmlElement) {
        if (element.size == Long.MAX_VALUE) {
            endReached = true
            return
        }
        skipFully(element.size)
    }

    private fun readVint(keepMarker: Boolean): Vint? {
        val first = readByteOrNull() ?: return null
        if (first == 0) return null
        var mask = 0x80
        var length = 1
        while (length <= 8 && (first and mask) == 0) {
            mask = mask shr 1
            length += 1
        }
        if (length > 8) return null
        var value = if (keepMarker) first.toLong() else (first and (mask - 1)).toLong()
        for (index in 1 until length) {
            val byte = readByteOrNull() ?: return null
            value = value * 256L + byte.toLong()
        }
        if (!keepMarker && value == unknownSizeValue(length)) {
            value = Long.MAX_VALUE
        }
        return Vint(length = length, value = value)
    }

    private fun unknownSizeValue(length: Int): Long =
        (1L shl (7 * length)) - 1L

    private fun readByteOrNull(): Int? {
        val value = input.read()
        if (value < 0) {
            endReached = true
            return null
        }
        position += 1
        return value
    }

    private fun readBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) {
                endReached = true
                throw EOFException("Unexpected end of Matroska stream")
            }
            offset += read
            position += read.toLong()
        }
        return bytes
    }

    private fun skipFully(length: Long) {
        var remaining = length
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                position += skipped
            } else if (readByteOrNull() == null) {
                return
            } else {
                remaining -= 1L
            }
        }
    }

    override fun close() {
        input.close()
    }
}
