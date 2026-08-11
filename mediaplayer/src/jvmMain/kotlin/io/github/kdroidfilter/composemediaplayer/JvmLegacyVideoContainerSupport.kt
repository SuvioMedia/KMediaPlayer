@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import kotlin.math.absoluteValue

/** Legacy desktop containers that need a general-purpose decoder on macOS. */
internal enum class JvmLegacyVideoContainer {
    AVI,
    ASF,
}

/**
 * Detects AVI/ASF sources and reads the small amount of container metadata needed before selecting
 * an unmanaged desktop decoder. This deliberately does not classify H.264/HEVC-in-AVI as SDR:
 * those codecs can carry high-precision or HDR signals and need a richer probe.
 */
internal object JvmLegacyVideoContainerSupport {
    suspend fun containerFor(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JvmLegacyVideoContainer? =
        withContext(Dispatchers.IO) {
            val headers = requestHeaders.sanitizedRequestHeaders()
            val localFile = localFile(uri)
            if (localFile != null) {
                containerFromName(localFile.name)
                    ?: readPrefix(localFile, SIGNATURE_PROBE_BYTES)?.let(::containerFromSignature)
            } else {
                containerFromName(runCatching { URI(uri).path }.getOrNull())
                    ?: if (uri.isHttpLegacyUri()) {
                        val remoteHeaders = readRemoteHeaders(uri, headers)
                        containerFromContentType(remoteHeaders?.contentType)
                            ?: containerFromName(remoteHeaders?.contentDispositionFilename)
                            ?: readRemotePrefix(uri, headers, SIGNATURE_PROBE_BYTES)
                                ?.let(::containerFromSignature)
                    } else {
                        null
                    }
            }
        }

    fun probe(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JvmLibVlcTrackInfo? {
        val headers = requestHeaders.sanitizedRequestHeaders()
        val bytes =
            localFile(uri)
                ?.let { file -> readPrefix(file, MAX_CONTAINER_PROBE_BYTES) }
                ?: if (uri.isHttpLegacyUri()) {
                    readRemotePrefix(uri, headers, MAX_CONTAINER_PROBE_BYTES)
                } else {
                    null
                }
                ?: return null
        return parseContainerPrefix(bytes)
    }

    internal fun parseContainerPrefix(bytes: ByteArray): JvmLibVlcTrackInfo? =
        when (containerFromSignature(bytes)) {
            JvmLegacyVideoContainer.AVI -> parseAvi(bytes)
            JvmLegacyVideoContainer.ASF -> parseAsf(bytes)
            null -> null
        }

    internal fun hasAviSignature(bytes: ByteArray): Boolean =
        bytes.matchesAscii(0, "RIFF") && bytes.matchesAscii(8, "AVI ")

    internal fun hasAsfSignature(bytes: ByteArray): Boolean = bytes.matchesAt(0, asfHeaderGuid)

    private fun parseAvi(bytes: ByteArray): JvmLibVlcTrackInfo? {
        if (!hasAviSignature(bytes)) return null
        val declaredEnd = bytes.leUInt(4)?.plus(CHUNK_HEADER_BYTES) ?: bytes.size.toLong()
        val accumulator = AviProbeAccumulator()
        parseAviChunkRange(
            bytes = bytes,
            start = RIFF_HEADER_BYTES,
            endExclusive = minOf(bytes.size.toLong(), declaredEnd).toInt(),
            accumulator = accumulator,
        )
        val codec = accumulator.codecFourCc?.let(::knownEightBitCodec)
        return JvmLibVlcTrackInfo(
            durationSeconds = accumulator.durationSeconds,
            videoWidth = accumulator.width?.takeIf { it > 0 },
            videoHeight = accumulator.height?.takeIf { it > 0 },
            videoCodecName = codec?.codecName ?: accumulator.codecFourCc?.trim()?.lowercase(),
            videoColorInfo = codec?.knownSdrColorInfo ?: VideoColorInfo(),
            audioStreams = accumulator.audioStreams.toLibVlcAudioStreams(),
        )
    }

    private fun parseAviChunkRange(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        accumulator: AviProbeAccumulator,
    ) {
        var cursor = start
        while (cursor + CHUNK_HEADER_BYTES <= endExclusive) {
            val chunkId = bytes.ascii(cursor, FOUR_CC_BYTES) ?: return
            val declaredSize = bytes.leUInt(cursor + FOUR_CC_BYTES) ?: return
            val dataStart = cursor + CHUNK_HEADER_BYTES
            val declaredEnd = dataStart.toLong() + declaredSize
            val dataEnd = minOf(endExclusive.toLong(), declaredEnd).toInt()
            if (dataEnd < dataStart) return

            when (chunkId) {
                "avih" -> parseAviMainHeader(bytes, dataStart, dataEnd, accumulator)
                "LIST" -> {
                    val listType = bytes.ascii(dataStart, FOUR_CC_BYTES)
                    when (listType) {
                        "hdrl", "odml" ->
                            parseAviChunkRange(
                                bytes,
                                dataStart + FOUR_CC_BYTES,
                                dataEnd,
                                accumulator,
                            )
                        "strl" -> parseAviStreamList(bytes, dataStart + FOUR_CC_BYTES, dataEnd, accumulator)
                        "movi" -> return
                    }
                }
            }

            if (declaredEnd > endExclusive) return
            val paddedEnd = declaredEnd + (declaredSize and 1L)
            if (paddedEnd <= cursor || paddedEnd > Int.MAX_VALUE) return
            cursor = paddedEnd.toInt()
        }
    }

    private fun parseAviMainHeader(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        accumulator: AviProbeAccumulator,
    ) {
        if (start + AVI_MAIN_HEADER_MIN_BYTES > endExclusive) return
        val microsecondsPerFrame = bytes.leUInt(start)
        val frameCount = bytes.leUInt(start + 16)
        accumulator.width = accumulator.width ?: bytes.leInt(start + 32)?.positiveMagnitude()
        accumulator.height = accumulator.height ?: bytes.leInt(start + 36)?.positiveMagnitude()
        if (microsecondsPerFrame != null && frameCount != null) {
            accumulator.durationSeconds =
                accumulator.durationSeconds
                    ?: (microsecondsPerFrame.toDouble() * frameCount.toDouble() / MICROSECONDS_PER_SECOND)
                        .takeIf { it.isFinite() && it > 0.0 }
        }
    }

    private fun parseAviStreamList(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        accumulator: AviProbeAccumulator,
    ) {
        var cursor = start
        val stream = AviStreamProbeAccumulator(streamIndex = accumulator.nextStreamIndex++)

        while (cursor + CHUNK_HEADER_BYTES <= endExclusive) {
            val chunkId = bytes.ascii(cursor, FOUR_CC_BYTES) ?: return
            val declaredSize = bytes.leUInt(cursor + FOUR_CC_BYTES) ?: return
            val dataStart = cursor + CHUNK_HEADER_BYTES
            val declaredEnd = dataStart.toLong() + declaredSize
            val dataEnd = minOf(endExclusive.toLong(), declaredEnd).toInt()
            if (dataEnd < dataStart) return

            when (chunkId) {
                "strh" -> parseAviStreamHeader(bytes, dataStart, dataEnd, stream)
                "strf" -> parseAviStreamFormat(bytes, dataStart, dataEnd, stream)
            }

            if (declaredEnd > endExclusive) return
            val paddedEnd = declaredEnd + (declaredSize and 1L)
            if (paddedEnd <= cursor || paddedEnd > Int.MAX_VALUE) return
            cursor = paddedEnd.toInt()
        }

        mergeAviStream(stream, accumulator)
    }

    private fun parseAviStreamHeader(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        stream: AviStreamProbeAccumulator,
    ) {
        if (start + AVI_STREAM_HEADER_MIN_BYTES > endExclusive) return
        stream.streamType = bytes.ascii(start, FOUR_CC_BYTES)
        stream.handler = bytes.printableFourCc(start + FOUR_CC_BYTES)
        val scale = bytes.leUInt(start + 20)
        val rate = bytes.leUInt(start + 24)
        val length = bytes.leUInt(start + 32)
        if (scale != null && rate != null && length != null && rate > 0L) {
            stream.durationSeconds =
                (length.toDouble() * scale.toDouble() / rate.toDouble())
                    .takeIf { it.isFinite() && it > 0.0 }
        }
    }

    private fun parseAviStreamFormat(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        stream: AviStreamProbeAccumulator,
    ) {
        when (stream.streamType) {
            AVI_VIDEO_STREAM_TYPE -> {
                if (start + BITMAP_INFO_HEADER_MIN_BYTES > endExclusive) return
                stream.width = bytes.leInt(start + 4)?.positiveMagnitude()
                stream.height = bytes.leInt(start + 8)?.positiveMagnitude()
                val bitmapBitCount = bytes.leUShort(start + 14)
                stream.compression =
                    if (bytes.leUInt(start + 16) == 0L && bitmapBitCount in 1..32) {
                        RAW_AVI_CODEC
                    } else {
                        bytes.printableFourCc(start + 16)
                    }
            }

            AVI_AUDIO_STREAM_TYPE -> parseWaveFormat(bytes, start, endExclusive, stream)
        }
    }

    private fun parseWaveFormat(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        stream: AviStreamProbeAccumulator,
    ) {
        if (start + WAVE_FORMAT_MIN_BYTES > endExclusive) return
        val formatTag = waveFormatCodecTag(bytes, start, endExclusive)
        stream.audioCodecName = formatTag?.let(::waveFormatCodecName)
        stream.audioCodecLabel = formatTag?.let(::waveFormatCodecLabel)
        stream.audioChannels = bytes.leUShort(start + 2)?.takeIf { it > 0 }
        stream.audioSampleRate = bytes.leUInt(start + 4).toPositiveIntOrNull()
        stream.audioBitrate = bytes.leUInt(start + 8).bytesPerSecondToBitrate()
    }

    private fun mergeAviStream(
        stream: AviStreamProbeAccumulator,
        accumulator: AviProbeAccumulator,
    ) {
        when (stream.streamType) {
            AVI_VIDEO_STREAM_TYPE -> {
                val selectedCodec =
                    listOfNotNull(stream.compression, stream.handler).firstOrNull {
                        knownEightBitCodec(it) != null
                    } ?: stream.compression ?: stream.handler
                accumulator.codecFourCc = accumulator.codecFourCc ?: selectedCodec
                accumulator.width = accumulator.width ?: stream.width
                accumulator.height = accumulator.height ?: stream.height
                accumulator.durationSeconds = accumulator.durationSeconds ?: stream.durationSeconds
            }

            AVI_AUDIO_STREAM_TYPE ->
                accumulator.audioStreams +=
                    LegacyAudioStream(
                        streamIndex = stream.streamIndex,
                        codecName = stream.audioCodecName,
                        label = stream.audioCodecLabel,
                        channels = stream.audioChannels,
                        sampleRate = stream.audioSampleRate,
                        bitrate = stream.audioBitrate,
                    )
        }
    }

    private fun parseAsf(bytes: ByteArray): JvmLibVlcTrackInfo? {
        if (!hasAsfSignature(bytes) || bytes.size < ASF_HEADER_FIXED_BYTES) return null
        val declaredHeaderEnd = bytes.leLong(16)?.takeIf { it >= ASF_HEADER_FIXED_BYTES } ?: bytes.size.toLong()
        val objectCount = bytes.leUInt(24)?.coerceAtMost(MAX_ASF_OBJECTS.toLong())?.toInt() ?: return null
        val headerEnd = minOf(bytes.size.toLong(), declaredHeaderEnd).toInt()
        var cursor = ASF_HEADER_FIXED_BYTES
        val accumulator = AsfProbeAccumulator()

        var objectsRemaining = objectCount
        while (objectsRemaining > 0 && cursor + ASF_OBJECT_HEADER_BYTES <= headerEnd) {
            objectsRemaining--
            val objectEnd = asfObjectEnd(bytes, cursor, headerEnd) ?: break
            parseAsfObject(bytes, cursor, objectEnd, accumulator)
            cursor = objectEnd
        }

        val codec = accumulator.codecFourCc?.let(::knownEightBitCodec)
        return JvmLibVlcTrackInfo(
            durationSeconds = accumulator.durationSeconds,
            videoWidth = accumulator.width?.takeIf { it > 0 },
            videoHeight = accumulator.height?.takeIf { it > 0 },
            videoCodecName = codec?.codecName ?: accumulator.codecFourCc?.trim()?.lowercase(),
            videoColorInfo = codec?.knownSdrColorInfo ?: VideoColorInfo(),
            audioStreams = accumulator.audioStreams.toLibVlcAudioStreams(),
        )
    }

    private fun asfObjectEnd(
        bytes: ByteArray,
        cursor: Int,
        headerEnd: Int,
    ): Int? {
        val objectSize = bytes.leLong(cursor + ASF_GUID_BYTES)?.takeIf { it >= ASF_OBJECT_HEADER_BYTES } ?: return null
        val objectEnd = cursor.toLong() + objectSize
        return objectEnd
            .takeIf { it > cursor && it <= headerEnd && it <= Int.MAX_VALUE }
            ?.toInt()
    }

    private fun parseAsfObject(
        bytes: ByteArray,
        objectStart: Int,
        objectEnd: Int,
        accumulator: AsfProbeAccumulator,
    ) {
        val payloadStart = objectStart + ASF_OBJECT_HEADER_BYTES
        when {
            bytes.matchesAt(objectStart, asfFilePropertiesGuid) ->
                parseAsfFileProperties(bytes, payloadStart, accumulator)
            bytes.matchesAt(objectStart, asfStreamPropertiesGuid) ->
                when {
                    bytes.matchesAt(payloadStart, asfVideoMediaGuid) ->
                        parseAsfVideoStreamProperties(bytes, payloadStart, objectEnd, accumulator)
                    bytes.matchesAt(payloadStart, asfAudioMediaGuid) ->
                        parseAsfAudioStreamProperties(bytes, payloadStart, objectEnd, accumulator)
                }
        }
    }

    private fun parseAsfFileProperties(
        bytes: ByteArray,
        payloadStart: Int,
        accumulator: AsfProbeAccumulator,
    ) {
        val playDuration = bytes.leLong(payloadStart + ASF_FILE_PLAY_DURATION_OFFSET)
        val prerollMs = bytes.leLong(payloadStart + ASF_FILE_PREROLL_OFFSET)
        if (playDuration == null || prerollMs == null) return
        val adjustedDurationSeconds =
            playDuration.toDouble() / HUNDRED_NANOSECONDS_PER_SECOND -
                prerollMs.toDouble() / MILLISECONDS_PER_SECOND
        accumulator.durationSeconds = adjustedDurationSeconds.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun parseAsfVideoStreamProperties(
        bytes: ByteArray,
        payloadStart: Int,
        objectEnd: Int,
        accumulator: AsfProbeAccumulator,
    ) {
        val typeSpecificLength = bytes.leUInt(payloadStart + ASF_TYPE_SPECIFIC_LENGTH_OFFSET) ?: return
        val typeDataStart = payloadStart + ASF_STREAM_PROPERTIES_FIXED_BYTES
        val typeDataEnd = minOf(objectEnd.toLong(), typeDataStart.toLong() + typeSpecificLength).toInt()
        if (typeDataStart + ASF_VIDEO_TYPE_MIN_BYTES > typeDataEnd) return
        accumulator.width = accumulator.width ?: bytes.leInt(typeDataStart)?.positiveMagnitude()
        accumulator.height = accumulator.height ?: bytes.leInt(typeDataStart + 4)?.positiveMagnitude()
        accumulator.codecFourCc =
            accumulator.codecFourCc ?: bytes.printableFourCc(typeDataStart + ASF_VIDEO_CODEC_OFFSET)
    }

    private fun parseAsfAudioStreamProperties(
        bytes: ByteArray,
        payloadStart: Int,
        objectEnd: Int,
        accumulator: AsfProbeAccumulator,
    ) {
        val typeSpecificLength = bytes.leUInt(payloadStart + ASF_TYPE_SPECIFIC_LENGTH_OFFSET) ?: return
        val typeDataStart = payloadStart + ASF_STREAM_PROPERTIES_FIXED_BYTES
        val typeDataEnd = minOf(objectEnd.toLong(), typeDataStart.toLong() + typeSpecificLength).toInt()
        if (typeDataStart + WAVE_FORMAT_MIN_BYTES > typeDataEnd) return
        val streamNumber =
            bytes
                .leUShort(payloadStart + ASF_STREAM_FLAGS_OFFSET)
                ?.and(ASF_STREAM_NUMBER_MASK)
                ?.takeIf { it > 0 }
                ?: return
        val formatTag = waveFormatCodecTag(bytes, typeDataStart, typeDataEnd)
        accumulator.audioStreams +=
            LegacyAudioStream(
                streamIndex = streamNumber - 1,
                codecName = formatTag?.let(::waveFormatCodecName),
                label = formatTag?.let(::waveFormatCodecLabel),
                channels = bytes.leUShort(typeDataStart + 2)?.takeIf { it > 0 },
                sampleRate = bytes.leUInt(typeDataStart + 4).toPositiveIntOrNull(),
                bitrate = bytes.leUInt(typeDataStart + 8).bytesPerSecondToBitrate(),
            )
    }

    private fun List<LegacyAudioStream>.toLibVlcAudioStreams(): List<JvmLibVlcAudioStream> =
        distinctBy(LegacyAudioStream::streamIndex)
            .sortedBy(LegacyAudioStream::streamIndex)
            .mapIndexed { audioOrdinal, stream ->
                JvmLibVlcAudioStream(
                    streamIndex = stream.streamIndex,
                    ordinal = audioOrdinal,
                    codecName = stream.codecName,
                    track =
                        AudioTrack(
                            id = "$LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX${stream.streamIndex}",
                            label = stream.label ?: "Audio ${audioOrdinal + 1}",
                            channels = stream.channels,
                            sampleRate = stream.sampleRate,
                            bitrate = stream.bitrate,
                            isDefault = audioOrdinal == 0,
                            codec = stream.codecName,
                        ),
                )
            }

    private fun waveFormatCodecName(formatTag: Int): String =
        when (formatTag) {
            0x0001 -> "pcm"
            0x0050 -> "mp2"
            0x0055 -> "mp3"
            0x00FF -> "aac"
            0x0160 -> "wmav1"
            0x0161 -> "wmav2"
            0x0162 -> "wmapro"
            0x0163 -> "wmalossless"
            0x2000 -> "ac3"
            else -> "wave-0x${formatTag.toString(16).padStart(4, '0')}"
        }

    private fun waveFormatCodecTag(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
    ): Int? {
        val formatTag = bytes.leUShort(start) ?: return null
        if (formatTag != WAVE_FORMAT_EXTENSIBLE) return formatTag
        if (start + WAVE_FORMAT_EXTENSIBLE_MIN_BYTES > endExclusive) return formatTag
        return bytes.leUShort(start + WAVE_SUBFORMAT_TAG_OFFSET) ?: formatTag
    }

    private fun waveFormatCodecLabel(formatTag: Int): String =
        when (formatTag) {
            0x0001 -> "PCM"
            0x0050 -> "MPEG audio"
            0x0055 -> "MP3"
            0x00FF -> "AAC"
            0x0160 -> "Windows Media Audio"
            0x0161 -> "Windows Media Audio 2"
            0x0162 -> "Windows Media Audio Pro"
            0x0163 -> "Windows Media Audio Lossless"
            0x2000 -> "AC-3"
            else -> "Audio 0x${formatTag.toString(16).uppercase().padStart(4, '0')}"
        }

    private fun Long?.toPositiveIntOrNull(): Int? = this?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private fun Long?.bytesPerSecondToBitrate(): Int? =
        this
            ?.takeIf { it > 0 && it <= Int.MAX_VALUE.toLong() / BITS_PER_BYTE }
            ?.times(BITS_PER_BYTE)
            ?.toInt()

    private fun knownEightBitCodec(fourCc: String): KnownLegacyCodec? = KNOWN_EIGHT_BIT_CODECS[fourCc.uppercase()]

    private fun containerFromSignature(bytes: ByteArray): JvmLegacyVideoContainer? =
        when {
            hasAviSignature(bytes) -> JvmLegacyVideoContainer.AVI
            hasAsfSignature(bytes) -> JvmLegacyVideoContainer.ASF
            else -> null
        }

    private fun containerFromName(value: String?): JvmLegacyVideoContainer? {
        val path = value?.substringBefore('?')?.substringBefore('#') ?: return null
        return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "avi", "divx" -> JvmLegacyVideoContainer.AVI
            "asf", "wmv" -> JvmLegacyVideoContainer.ASF
            else -> null
        }
    }

    private fun containerFromContentType(value: String?): JvmLegacyVideoContainer? =
        when (value?.substringBefore(';')?.trim()?.lowercase()) {
            "video/avi", "video/msvideo", "video/x-msvideo", "application/x-troff-msvideo" ->
                JvmLegacyVideoContainer.AVI
            "video/x-ms-asf", "video/x-ms-wmv", "application/vnd.ms-asf" -> JvmLegacyVideoContainer.ASF
            else -> null
        }

    private fun localFile(uri: String): File? {
        if (WINDOWS_DRIVE_PATH.matches(uri)) return File(uri)
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return File(uri)
        return when (parsed.scheme?.lowercase()) {
            null, "" -> File(uri)
            "file" -> runCatching { File(parsed) }.getOrNull()
            else -> null
        }
    }

    private fun String.isHttpLegacyUri(): Boolean =
        runCatching { URI(this).scheme?.lowercase() }
            .getOrNull() in setOf("http", "https")

    private fun readPrefix(
        file: File,
        maximumBytes: Int,
    ): ByteArray? {
        if (!file.isFile) return null
        return runCatching { file.inputStream().use { it.readNBytes(maximumBytes) } }
            .getOrNull()
            ?.takeIf(ByteArray::isNotEmpty)
    }

    private fun readRemoteHeaders(
        uri: String,
        requestHeaders: Map<String, String>,
    ): RemoteHeaders? =
        runCatching {
            uri.openHttpConnection(requestHeaders, method = "HEAD").run {
                try {
                    RemoteHeaders(
                        contentType = contentType,
                        contentDispositionFilename =
                            contentDispositionFilename(getHeaderField("Content-Disposition")),
                    )
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()

    private fun readRemotePrefix(
        uri: String,
        requestHeaders: Map<String, String>,
        maximumBytes: Int,
    ): ByteArray? =
        runCatching {
            uri.openHttpConnection(requestHeaders, method = "GET").run {
                setRequestProperty("Range", "bytes=0-${maximumBytes - 1}")
                try {
                    inputStream
                        .use { it.readNBytes(maximumBytes) }
                        .takeIf(ByteArray::isNotEmpty)
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()

    private fun String.openHttpConnection(
        requestHeaders: Map<String, String>,
        method: String,
    ): HttpURLConnection =
        (URI.create(this).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = method
            connectTimeout = REMOTE_PROBE_TIMEOUT_MS
            readTimeout = REMOTE_PROBE_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun contentDispositionFilename(value: String?): String? {
        val item =
            value
                ?.split(';')
                ?.map(String::trim)
                ?.firstOrNull {
                    it.startsWith("filename*=", ignoreCase = true) ||
                        it.startsWith("filename=", ignoreCase = true)
                } ?: return null
        return URLDecoder.decode(
            item
                .substringAfter('=')
                .substringAfter("''")
                .trim()
                .trim('"'),
            Charsets.UTF_8,
        )
    }

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ): String? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        return String(this, offset, length, Charsets.ISO_8859_1)
    }

    private fun ByteArray.printableFourCc(offset: Int): String? =
        ascii(offset, FOUR_CC_BYTES)?.takeIf { value -> value.all { it.code in 0x20..0x7E } }

    private fun ByteArray.matchesAscii(
        offset: Int,
        value: String,
    ): Boolean = matchesAt(offset, value.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArray.matchesAt(
        offset: Int,
        value: ByteArray,
    ): Boolean {
        if (offset < 0 || offset + value.size > size) return false
        return value.indices.all { index -> this[offset + index] == value[index] }
    }

    private fun ByteArray.leUShort(offset: Int): Int? {
        if (offset < 0 || offset + 2 > size) return null
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.leInt(offset: Int): Int? = leUInt(offset)?.toInt()

    private fun ByteArray.leUInt(offset: Int): Long? {
        if (offset < 0 || offset + 4 > size) return null
        return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.leLong(offset: Int): Long? {
        if (offset < 0 || offset + 8 > size) return null
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((this[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        return value.takeIf { it >= 0L }
    }

    private fun Int.positiveMagnitude(): Int? =
        takeUnless { it == Int.MIN_VALUE }
            ?.absoluteValue
            ?.takeIf { it > 0 }

    private data class AviProbeAccumulator(
        var durationSeconds: Double? = null,
        var width: Int? = null,
        var height: Int? = null,
        var codecFourCc: String? = null,
        var nextStreamIndex: Int = 0,
        val audioStreams: MutableList<LegacyAudioStream> = mutableListOf(),
    )

    private data class AviStreamProbeAccumulator(
        val streamIndex: Int,
        var streamType: String? = null,
        var handler: String? = null,
        var compression: String? = null,
        var durationSeconds: Double? = null,
        var width: Int? = null,
        var height: Int? = null,
        var audioCodecName: String? = null,
        var audioCodecLabel: String? = null,
        var audioChannels: Int? = null,
        var audioSampleRate: Int? = null,
        var audioBitrate: Int? = null,
    )

    private data class AsfProbeAccumulator(
        var durationSeconds: Double? = null,
        var width: Int? = null,
        var height: Int? = null,
        var codecFourCc: String? = null,
        val audioStreams: MutableList<LegacyAudioStream> = mutableListOf(),
    )

    private data class LegacyAudioStream(
        val streamIndex: Int,
        val codecName: String?,
        val label: String?,
        val channels: Int?,
        val sampleRate: Int?,
        val bitrate: Int?,
    )

    private data class KnownLegacyCodec(
        val codecName: String,
    ) {
        val knownSdrColorInfo: VideoColorInfo
            get() = VideoColorInfo(dynamicRange = VideoDynamicRange.SDR, bitDepth = 8)
    }

    private data class RemoteHeaders(
        val contentType: String?,
        val contentDispositionFilename: String?,
    )

    private val KNOWN_EIGHT_BIT_CODECS =
        buildMap {
            fun register(
                codecName: String,
                vararg fourCcs: String,
            ) {
                fourCcs.forEach { fourCc -> put(fourCc, KnownLegacyCodec(codecName)) }
            }
            register("mpeg4", "FMP4", "MP4V", "DIVX", "DX50", "XVID", "3IV2", "M4S2", "MP43")
            register("mpeg4", "DIV3", "DIV4", "DIV5", "AP41", "COL1")
            register("mjpeg", "MJPG", "JPEG", "MJPA", "MJPB")
            register("wmv1", "WMV1")
            register("wmv2", "WMV2")
            register("wmv3", "WMV3", "WMVA")
            register("vc1", "WVC1")
            register("mpeg1video", "MPG1", "PIM1")
            register("mpeg2video", "MPG2", "PIM2")
            register("h263", "H263", "U263", "I263")
            register("rawvideo", RAW_AVI_CODEC, "YUY2", "UYVY", "I420", "IYUV", "YV12", "NV12")
            register("cinepak", "CVID")
            register("msvideo1", "MSVC", "CRAM")
        }

    private const val FOUR_CC_BYTES = 4
    private const val CHUNK_HEADER_BYTES = 8
    private const val RIFF_HEADER_BYTES = 12
    private const val AVI_MAIN_HEADER_MIN_BYTES = 40
    private const val AVI_STREAM_HEADER_MIN_BYTES = 36
    private const val BITMAP_INFO_HEADER_MIN_BYTES = 20
    private const val WAVE_FORMAT_MIN_BYTES = 16
    private const val WAVE_FORMAT_EXTENSIBLE_MIN_BYTES = 40
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val WAVE_SUBFORMAT_TAG_OFFSET = 24
    private const val AVI_VIDEO_STREAM_TYPE = "vids"
    private const val AVI_AUDIO_STREAM_TYPE = "auds"
    private const val RAW_AVI_CODEC = "DIB "
    private const val MICROSECONDS_PER_SECOND = 1_000_000.0
    private const val BITS_PER_BYTE = 8L

    private const val ASF_GUID_BYTES = 16
    private const val ASF_OBJECT_HEADER_BYTES = 24
    private const val ASF_HEADER_FIXED_BYTES = 30
    private const val ASF_FILE_PLAY_DURATION_OFFSET = 40
    private const val ASF_FILE_PREROLL_OFFSET = 56
    private const val ASF_TYPE_SPECIFIC_LENGTH_OFFSET = 40
    private const val ASF_STREAM_FLAGS_OFFSET = 48
    private const val ASF_STREAM_NUMBER_MASK = 0x7F
    private const val ASF_STREAM_PROPERTIES_FIXED_BYTES = 54
    private const val ASF_VIDEO_CODEC_OFFSET = 27
    private const val ASF_VIDEO_TYPE_MIN_BYTES = 31
    private const val MAX_ASF_OBJECTS = 1_024
    private const val HUNDRED_NANOSECONDS_PER_SECOND = 10_000_000.0
    private const val MILLISECONDS_PER_SECOND = 1_000.0

    private const val SIGNATURE_PROBE_BYTES = 64
    private const val MAX_CONTAINER_PROBE_BYTES = 2 * 1024 * 1024
    private const val REMOTE_PROBE_TIMEOUT_MS = 3_500
    private const val USER_AGENT = "ComposeMediaPlayer/2.0"
    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

    private val asfHeaderGuid =
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
    private val asfFilePropertiesGuid =
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
    private val asfStreamPropertiesGuid =
        byteArrayOf(
            0x91.toByte(),
            0x07,
            0xDC.toByte(),
            0xB7.toByte(),
            0xB7.toByte(),
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
    private val asfVideoMediaGuid =
        byteArrayOf(
            0xC0.toByte(),
            0xEF.toByte(),
            0x19,
            0xBC.toByte(),
            0x4D,
            0x5B,
            0xCF.toByte(),
            0x11,
            0xA8.toByte(),
            0xFD.toByte(),
            0x00,
            0x80.toByte(),
            0x5F,
            0x5C,
            0x44,
            0x2B,
        )
    private val asfAudioMediaGuid =
        byteArrayOf(
            0x40,
            0x9E.toByte(),
            0x69,
            0xF8.toByte(),
            0x4D,
            0x5B,
            0xCF.toByte(),
            0x11,
            0xA8.toByte(),
            0xFD.toByte(),
            0x00,
            0x80.toByte(),
            0x5F,
            0x5C,
            0x44,
            0x2B,
        )
}
