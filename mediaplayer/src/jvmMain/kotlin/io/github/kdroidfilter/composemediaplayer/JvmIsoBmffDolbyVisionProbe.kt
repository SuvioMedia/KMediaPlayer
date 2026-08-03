@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI

/** Reads the Dolby Vision decoder configuration record from an ISO-BMFF `moov` box. */
internal object JvmIsoBmffDolbyVisionProbe {
    fun probe(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): VideoColorInfo? {
        if (!uri.hasIsoBmffExtension()) return null
        val source = openSource(uri, requestHeaders.sanitizedRequestHeaders()) ?: return null
        return source.use(::probe)
    }

    internal fun parseConfigurationBox(
        bytes: ByteArray,
        typeOffset: Int,
    ): VideoColorInfo? {
        val boxStart = typeOffset - BOX_SIZE_BYTES
        if (
            boxStart < 0 ||
            typeOffset + BOX_TYPE_BYTES + MINIMUM_DOVI_RECORD_BYTES > bytes.size
        ) {
            return null
        }
        val type = bytes.ascii(typeOffset, BOX_TYPE_BYTES)
        val boxSize = bytes.beUInt(boxStart)
        if (
            type == null ||
            type !in DOLBY_VISION_CONFIGURATION_BOX_TYPES ||
            boxSize == null ||
            boxSize !in MINIMUM_DOVI_BOX_BYTES.toLong()..MAXIMUM_DOVI_BOX_BYTES.toLong()
        ) {
            return null
        }

        val payloadOffset = typeOffset + BOX_TYPE_BYTES
        val packed =
            ((bytes[payloadOffset + 2].toInt() and 0xff) shl 8) or
                (bytes[payloadOffset + 3].toInt() and 0xff)
        val profile = (packed ushr 9) and 0x7f
        val level = (packed ushr 3) and 0x3f
        val rpuPresent = packed and 0x04 != 0
        val enhancementLayerPresent = packed and 0x02 != 0
        val baseLayerPresent = packed and 0x01 != 0
        val compatibilityId = (bytes[payloadOffset + 4].toInt() ushr 4) and 0x0f
        if (profile == 0 || !rpuPresent) return null

        return VideoColorInfo(
            dynamicRange = VideoDynamicRange.DOLBY_VISION,
            dolbyVision =
                DolbyVisionInfo(
                    profile = profile,
                    level = level,
                    hasRpu = true,
                    enhancementLayer =
                        if (enhancementLayerPresent) {
                            DolbyVisionEnhancementLayer.UNKNOWN
                        } else {
                            DolbyVisionEnhancementLayer.NONE
                        },
                    hasHdr10CompatibleBaseLayer =
                        baseLayerPresent &&
                            (profile == DOLBY_VISION_PROFILE_7 || compatibilityId == HDR10_COMPATIBILITY_ID),
                    hasHlgCompatibleBaseLayer =
                        baseLayerPresent && compatibilityId == HLG_COMPATIBILITY_ID,
                ),
        )
    }

    private fun probe(source: RandomAccessSource): VideoColorInfo? {
        var cursor = 0L
        var inspectedBoxes = 0
        while (cursor < source.length && inspectedBoxes < MAXIMUM_TOP_LEVEL_BOXES) {
            val box = source.readTopLevelBox(cursor) ?: break
            if (box.type == MOVIE_BOX_TYPE) {
                return scanMovieBox(
                    source = source,
                    start = cursor + box.headerSize,
                    length = box.size - box.headerSize,
                )
            }
            cursor += box.size
            inspectedBoxes++
        }
        return null
    }

    private fun RandomAccessSource.readTopLevelBox(cursor: Long): IsoBmffBox? {
        val header =
            read(cursor, EXTENDED_BOX_HEADER_BYTES)
                ?.takeIf { it.size >= BASIC_BOX_HEADER_BYTES }
                ?: return null
        val compactSize = header.beUInt(0)
        val type = header.ascii(BOX_SIZE_BYTES, BOX_TYPE_BYTES)
        if (compactSize == null || type == null) return null
        val headerSize =
            if (compactSize == EXTENDED_SIZE_MARKER) {
                EXTENDED_BOX_HEADER_BYTES
            } else {
                BASIC_BOX_HEADER_BYTES
            }
        val boxSize =
            when (compactSize) {
                EXTENDED_SIZE_MARKER -> header.beULong(BASIC_BOX_HEADER_BYTES)
                TO_END_OF_FILE_SIZE_MARKER -> length - cursor
                else -> compactSize
            }
        if (boxSize == null || boxSize < headerSize || boxSize > length - cursor) return null
        return IsoBmffBox(type = type, headerSize = headerSize, size = boxSize)
    }

    private fun scanMovieBox(
        source: RandomAccessSource,
        start: Long,
        length: Long,
    ): VideoColorInfo? {
        val end = start + length
        var cursor = start
        while (cursor < end) {
            val readStart = maxOf(start, cursor - SCAN_OVERLAP_BYTES)
            val byteCount = minOf(SCAN_CHUNK_BYTES.toLong(), end - readStart).toInt()
            val bytes = source.read(readStart, byteCount) ?: return null
            for (typeOffset in BOX_SIZE_BYTES until bytes.size - MINIMUM_DOVI_RECORD_BYTES) {
                if (!bytes.matchesAnyAscii(typeOffset, DOLBY_VISION_CONFIGURATION_BOX_TYPES)) continue
                parseConfigurationBox(bytes, typeOffset)?.let { return it }
            }
            cursor += SCAN_CHUNK_BYTES
        }
        return null
    }

    private fun openSource(
        uri: String,
        requestHeaders: Map<String, String>,
    ): RandomAccessSource? {
        val localFile = uri.localFileOrNull()
        if (localFile != null) {
            if (!localFile.isFile) return null
            return LocalRandomAccessSource(localFile)
        }
        if (!uri.isHttpUri()) return null
        val length =
            runCatching {
                uri.openHttpConnection(requestHeaders, method = "HEAD").run {
                    try {
                        contentLengthLong.takeIf { it > 0L }
                    } finally {
                        disconnect()
                    }
                }
            }.getOrNull() ?: return null
        return HttpRandomAccessSource(uri, requestHeaders, length)
    }

    private interface RandomAccessSource : Closeable {
        val length: Long

        fun read(
            offset: Long,
            byteCount: Int,
        ): ByteArray?
    }

    private data class IsoBmffBox(
        val type: String,
        val headerSize: Int,
        val size: Long,
    )

    private class LocalRandomAccessSource(
        file: File,
    ) : RandomAccessSource {
        private val input = RandomAccessFile(file, "r")

        override val length: Long = input.length()

        override fun read(
            offset: Long,
            byteCount: Int,
        ): ByteArray? {
            if (offset !in 0 until length || byteCount <= 0) return null
            val result = ByteArray(minOf(byteCount.toLong(), length - offset).toInt())
            input.seek(offset)
            input.readFully(result)
            return result
        }

        override fun close() = input.close()
    }

    private class HttpRandomAccessSource(
        private val uri: String,
        private val requestHeaders: Map<String, String>,
        override val length: Long,
    ) : RandomAccessSource {
        override fun read(
            offset: Long,
            byteCount: Int,
        ): ByteArray? {
            if (offset !in 0 until length || byteCount <= 0) return null
            val endInclusive = minOf(length - 1, offset + byteCount - 1)
            return runCatching {
                uri.openHttpConnection(requestHeaders, method = "GET").run {
                    setRequestProperty("Range", "bytes=$offset-$endInclusive")
                    try {
                        if (responseCode != HttpURLConnection.HTTP_PARTIAL) return@run null
                        inputStream.use { input ->
                            input.readNBytes((endInclusive - offset + 1).toInt())
                        }
                    } finally {
                        disconnect()
                    }
                }
            }.getOrNull()
        }

        override fun close() = Unit
    }

    private fun String.localFileOrNull(): File? {
        if (WINDOWS_DRIVE_PATH.matches(this)) return File(this)
        val parsed = runCatching { URI(this) }.getOrNull() ?: return File(this)
        return when (parsed.scheme?.lowercase()) {
            null, "" -> File(this)
            "file" -> runCatching { File(parsed) }.getOrNull()
            else -> null
        }
    }

    private fun String.hasIsoBmffExtension(): Boolean {
        val path = runCatching { URI(this).path }.getOrNull() ?: this
        return path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in ISO_BMFF_EXTENSIONS
    }

    private fun String.isHttpUri(): Boolean =
        runCatching { URI(this).scheme?.lowercase() }.getOrNull() in setOf("http", "https")

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

    private fun ByteArray.beUInt(offset: Int): Long? {
        if (offset < 0 || offset + 4 > size) return null
        return ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
    }

    private fun ByteArray.beULong(offset: Int): Long? {
        if (offset < 0 || offset + 8 > size || this[offset].toInt() and 0x80 != 0) return null
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (this[offset + index].toLong() and 0xff) }
        return value
    }

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ): String? =
        if (offset < 0 || offset + length > size) {
            null
        } else {
            String(this, offset, length, Charsets.ISO_8859_1)
        }

    private fun ByteArray.matchesAnyAscii(
        offset: Int,
        values: Set<String>,
    ): Boolean = values.any { value -> ascii(offset, value.length) == value }

    private const val BOX_SIZE_BYTES = 4
    private const val BOX_TYPE_BYTES = 4
    private const val BASIC_BOX_HEADER_BYTES = 8
    private const val EXTENDED_BOX_HEADER_BYTES = 16
    private const val MINIMUM_DOVI_RECORD_BYTES = 5
    private const val MINIMUM_DOVI_BOX_BYTES = BASIC_BOX_HEADER_BYTES + MINIMUM_DOVI_RECORD_BYTES
    private const val MAXIMUM_DOVI_BOX_BYTES = 256
    private const val EXTENDED_SIZE_MARKER = 1L
    private const val TO_END_OF_FILE_SIZE_MARKER = 0L
    private const val MAXIMUM_TOP_LEVEL_BOXES = 4_096
    private const val SCAN_CHUNK_BYTES = 256 * 1024
    private const val SCAN_OVERLAP_BYTES = 64L
    private const val REMOTE_PROBE_TIMEOUT_MS = 4_000
    private const val DOLBY_VISION_PROFILE_7 = 7
    private const val HDR10_COMPATIBILITY_ID = 1
    private const val HLG_COMPATIBILITY_ID = 4
    private const val MOVIE_BOX_TYPE = "moov"
    private const val USER_AGENT = "ComposeMediaPlayer/2.0"
    private val DOLBY_VISION_CONFIGURATION_BOX_TYPES = setOf("dvcC", "dvvC", "dvwC")
    private val ISO_BMFF_EXTENSIONS = setOf("mp4", "m4v", "mov", "cmfv")
    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
}
