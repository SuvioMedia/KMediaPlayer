package io.github.kdroidfilter.composemediaplayer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmMediaChapterProbeTest {
    @Test
    fun `parses ID3v2 CHAP and embedded TIT2`() {
        val title = id3TextFrame("Opening")
        val chapterBody =
            "chapter-1".encodeToByteArray() +
                byteArrayOf(0) +
                beUInt(1_500) +
                beUInt(8_500) +
                beUInt(UInt.MAX_VALUE) +
                beUInt(UInt.MAX_VALUE) +
                title
        val chapterFrame = id3Frame("CHAP", chapterBody)
        val tag =
            byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
                syncSafe(chapterFrame.size) +
                chapterFrame

        val chapters = JvmMediaChapterProbe.parseId3ChapterRows(tag)

        assertEquals(1, chapters.size)
        assertEquals(1_500, chapters.single().startMs)
        assertEquals(8_500, chapters.single().endMs)
        assertEquals("Opening", chapters.single().title)
    }

    @Test
    fun `treats an ID3 unknown chapter end as absent`() {
        val chapterBody =
            "only".encodeToByteArray() +
                byteArrayOf(0) +
                beUInt(5_000) +
                beUInt(UInt.MAX_VALUE) +
                beUInt(UInt.MAX_VALUE) +
                beUInt(UInt.MAX_VALUE)
        val frame = id3Frame("CHAP", chapterBody)
        val tag =
            byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
                syncSafe(frame.size) +
                frame

        val chapter = JvmMediaChapterProbe.parseId3ChapterRows(tag).single()

        assertEquals(5_000, chapter.startMs)
        assertNull(chapter.endMs)
    }

    @Test
    fun `flattens an ID3 CTOC hierarchy in its declared order`() {
        val second = id3ChapterFrame(elementId = "second", startMs = 1_000, endMs = 3_000, title = "Second")
        val first = id3ChapterFrame(elementId = "first", startMs = 1_000, endMs = 2_000, title = "First")
        val tableOfContents =
            id3Frame(
                "CTOC",
                "root".encodeToByteArray() +
                    byteArrayOf(0, 0x03, 2) +
                    "first".encodeToByteArray() +
                    byteArrayOf(0) +
                    "second".encodeToByteArray() +
                    byteArrayOf(0),
            )
        val frames = second + first + tableOfContents
        val tag =
            byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
                syncSafe(frames.size) +
                frames

        val chapters = JvmMediaChapterProbe.parseId3ChapterRows(tag)

        assertEquals(listOf("First", "Second"), chapters.map(RawMediaChapter::title))
    }

    @Test
    fun `parses Nero chpl timestamps and UTF-8 titles`() {
        val payload =
            byteArrayOf(1, 0, 0, 0) +
                beUInt(0) +
                byteArrayOf(2) +
                beLong(0) +
                lengthPrefixedUtf8("Intro") +
                beLong(125_000_000L) +
                lengthPrefixedUtf8("Rozdział")

        val chapters = JvmMediaChapterProbe.parseNeroChapterRows(payload)

        assertEquals(listOf(0L, 12_500L), chapters.map(RawMediaChapter::startMs))
        assertEquals(listOf("Intro", "Rozdział"), chapters.map(RawMediaChapter::title))
    }

    @Test
    fun `parses localized Apple HLS chapter JSON`() {
        val json =
            """
            [
              {
                "start-time": 1.25,
                "duration": 4.5,
                "titles": [
                  {"language": "en", "title": "Intro"},
                  {"language": "pl-PL", "title": "Wstęp"}
                ]
              },
              {"start-time": 8, "titles": [{"language": "und", "title": "Finale"}]}
            ]
            """.trimIndent()

        val chapters = JvmMediaChapterProbe.parseHlsChapterJson(json, listOf("pl-PL"))

        assertEquals(2, chapters.size)
        assertEquals(1_250, chapters[0].startMs)
        assertEquals(5_750, chapters[0].endMs)
        assertEquals("Wstęp", chapters[0].title)
        assertEquals("pl-PL", chapters[0].language)
        assertEquals(8_000, chapters[1].startMs)
        assertNull(chapters[1].endMs)
    }

    @Test
    fun `resolves Apple HLS chapter URI variables against the master playlist`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-DEFINE:NAME="metadata",VALUE="sidecars"
            #EXT-X-SESSION-DATA:DATA-ID="com.apple.hls.chapters",URI="{${'$'}metadata}/chapters.json"
            """.trimIndent()

        val uri =
            JvmMediaChapterProbe.parseHlsChapterJsonUri(
                playlist = playlist,
                baseUri = "https://cdn.example/media/master.m3u8",
            )

        assertEquals("https://cdn.example/media/sidecars/chapters.json", uri)
    }

    @Test
    fun `parses ASF markers relative to preroll`() {
        val prerollMs = 3_000L
        val filePropertiesPayload =
            ByteArray(80).apply {
                putLeLong(40, 23_000L * 10_000L)
                putLeLong(56, prerollMs)
            }
        val fileProperties = asfObject(ASF_FILE_PROPERTIES_GUID, filePropertiesPayload)
        val titleBytes = "Chapter one\u0000".toByteArray(Charsets.UTF_16LE)
        val markerEntry =
            leLong(0) +
                leLong((5_000L + prerollMs) * 10_000L) +
                leUShort(titleBytes.size + 12) +
                leUInt(0) +
                leUInt(0) +
                leUInt(titleBytes.size / 2) +
                titleBytes
        val markerPayload =
            ByteArray(16) +
                leUInt(1) +
                leUShort(0) +
                leUShort(0) +
                markerEntry
        val marker = asfObject(ASF_MARKER_GUID, markerPayload)
        val bytes = ASF_HEADER_GUID + ByteArray(24) + fileProperties + marker

        val result = JvmMediaChapterProbe.parseAsfMarkerRows(bytes)

        assertEquals(20_000, result?.durationMs)
        assertEquals(5_000, result?.rows?.single()?.startMs)
        assertEquals("Chapter one", result?.rows?.single()?.title)
    }

    @Test
    fun `reads QuickTime chapter track referenced through tref chap`() {
        val firstSample = lengthPrefixedTextSample("Intro")
        val secondSample = lengthPrefixedTextSample("Ending")
        val ftyp = box("ftyp", "isom".encodeToByteArray() + beUInt(0) + "isom".encodeToByteArray())
        val sampleOffset = ftyp.size + 8
        val mdat = box("mdat", firstSample + secondSample)

        val mvhd =
            box(
                "mvhd",
                byteArrayOf(0, 0, 0, 0) +
                    beUInt(0) +
                    beUInt(0) +
                    beUInt(1_000) +
                    beUInt(20_000),
            )
        val mainTrack =
            box(
                "trak",
                box("tkhd", trackHeader(trackId = 1)) +
                    box("tref", box("chap", beUInt(2))),
            )
        val chapterTrack =
            box(
                "trak",
                box("tkhd", trackHeader(trackId = 2)) +
                    box(
                        "mdia",
                        box("mdhd", mediaHeader(timescale = 1_000, duration = 20_000, language = "eng")) +
                            box(
                                "minf",
                                box(
                                    "stbl",
                                    box(
                                        "stts",
                                        byteArrayOf(0, 0, 0, 0) +
                                            beUInt(1) +
                                            beUInt(2) +
                                            beUInt(10_000),
                                    ) +
                                        box(
                                            "stsc",
                                            byteArrayOf(0, 0, 0, 0) +
                                                beUInt(1) +
                                                beUInt(1) +
                                                beUInt(2) +
                                                beUInt(1),
                                        ) +
                                        box(
                                            "stsz",
                                            byteArrayOf(0, 0, 0, 0) +
                                                beUInt(0) +
                                                beUInt(2) +
                                                beUInt(firstSample.size) +
                                                beUInt(secondSample.size),
                                        ) +
                                        box(
                                            "stco",
                                            byteArrayOf(0, 0, 0, 0) +
                                                beUInt(1) +
                                                beUInt(sampleOffset),
                                        ),
                                ),
                            ),
                    ),
            )
        val file = File.createTempFile("kmp-chapters-", ".mov")
        file.writeBytes(ftyp + mdat + box("moov", mvhd + mainTrack + chapterTrack))

        try {
            val result = JvmMediaChapterProbe.probe(file.toURI().toString())

            assertEquals(20_000, result?.durationMs)
            assertEquals(listOf(0L, 10_000L), result?.rows?.map(RawMediaChapter::startMs))
            assertEquals(listOf(10_000L, 20_000L), result?.rows?.map(RawMediaChapter::endMs))
            assertEquals(listOf("Intro", "Ending"), result?.rows?.map(RawMediaChapter::title))
            assertEquals(listOf("eng", "eng"), result?.rows?.map(RawMediaChapter::language))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `reads the default Matroska edition and flattens nested chapters`() {
        val videoTrack =
            ebmlElement(
                "AE",
                ebmlElement("D7", byteArrayOf(1)) +
                    ebmlElement("83", byteArrayOf(1)) +
                    ebmlElement("86", "V_VP9".encodeToByteArray()),
            )
        val info =
            ebmlElement(
                "1549A966",
                ebmlElement(
                    "4489",
                    ByteBuffer
                        .allocate(Long.SIZE_BYTES)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putDouble(20_000.0)
                        .array(),
                ),
            )
        val nonDefaultEdition =
            matroskaEdition(
                isDefault = false,
                chapters =
                    listOf(
                        matroskaChapter(
                            startMs = 0,
                            endMs = 20_000,
                            title = "Wrong edition",
                            language = "eng",
                        ),
                    ),
            )
        val nested =
            matroskaChapter(
                startMs = 10_000,
                endMs = 15_000,
                title = "Detail",
                language = "eng",
                isHidden = true,
            )
        val defaultEdition =
            matroskaEdition(
                isDefault = true,
                chapters =
                    listOf(
                        matroskaChapter(
                            startMs = 0,
                            endMs = 5_000,
                            title = "Intro",
                            language = "und",
                        ),
                        matroskaChapter(
                            startMs = 5_000,
                            endMs = 15_000,
                            title = "Section",
                            language = "eng",
                            nested = nested,
                        ),
                    ),
            )
        val segment =
            ebmlElement(
                "18538067",
                info +
                    ebmlElement("1654AE6B", videoTrack) +
                    ebmlElement("1043A770", nonDefaultEdition + defaultEdition),
            )
        val file = File.createTempFile("kmp-chapters-", ".mkv")
        file.writeBytes(segment)

        try {
            val result = JvmLibVlcMediaProbe.probe(file.toURI().toString())

            assertEquals(20.0, result.durationSeconds)
            assertEquals(listOf("Intro", "Section", "Detail"), result.chapters.map(MediaChapter::title))
            assertEquals(listOf(0L, 5_000L, 10_000L), result.chapters.map(MediaChapter::startMs))
            assertEquals(listOf(5_000L, 15_000L, 15_000L), result.chapters.map(MediaChapter::endMs))
            assertFalse(result.chapters[0].isHidden)
            assertFalse(result.chapters[1].isHidden)
            assertTrue(result.chapters[2].isHidden)
        } finally {
            file.delete()
        }
    }

    private fun id3TextFrame(value: String): ByteArray = id3Frame("TIT2", byteArrayOf(3) + value.encodeToByteArray())

    private fun id3ChapterFrame(
        elementId: String,
        startMs: Int,
        endMs: Int,
        title: String,
    ): ByteArray =
        id3Frame(
            "CHAP",
            elementId.encodeToByteArray() +
                byteArrayOf(0) +
                beUInt(startMs) +
                beUInt(endMs) +
                beUInt(UInt.MAX_VALUE) +
                beUInt(UInt.MAX_VALUE) +
                id3TextFrame(title),
        )

    private fun id3Frame(
        id: String,
        body: ByteArray,
    ): ByteArray = id.encodeToByteArray() + syncSafe(body.size) + byteArrayOf(0, 0) + body

    private fun syncSafe(value: Int): ByteArray =
        byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray = beUInt(payload.size + 8) + type.encodeToByteArray() + payload

    private fun trackHeader(trackId: Int): ByteArray =
        byteArrayOf(0, 0, 0, 0) +
            beUInt(0) +
            beUInt(0) +
            beUInt(trackId) +
            beUInt(0) +
            beUInt(20_000)

    private fun mediaHeader(
        timescale: Int,
        duration: Int,
        language: String,
    ): ByteArray =
        byteArrayOf(0, 0, 0, 0) +
            beUInt(0) +
            beUInt(0) +
            beUInt(timescale) +
            beUInt(duration) +
            beUShort(packLanguage(language)) +
            beUShort(0)

    private fun packLanguage(language: String): Int =
        ((language[0].code - 0x60) shl 10) or
            ((language[1].code - 0x60) shl 5) or
            (language[2].code - 0x60)

    private fun lengthPrefixedUtf8(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    private fun lengthPrefixedTextSample(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return beUShort(bytes.size) + bytes
    }

    private fun asfObject(
        guid: ByteArray,
        payload: ByteArray,
    ): ByteArray = guid + leLong(payload.size + 24L) + payload

    private fun matroskaEdition(
        isDefault: Boolean,
        chapters: List<ByteArray>,
    ): ByteArray =
        ebmlElement(
            "45B9",
            ebmlElement("45DB", byteArrayOf(if (isDefault) 1 else 0)) +
                chapters.fold(ByteArray(0), ByteArray::plus),
        )

    private fun matroskaChapter(
        startMs: Long,
        endMs: Long,
        title: String,
        language: String,
        isHidden: Boolean = false,
        nested: ByteArray = ByteArray(0),
    ): ByteArray =
        ebmlElement(
            "B6",
            ebmlElement("91", minimalUnsignedBytes(startMs * 1_000_000L)) +
                ebmlElement("92", minimalUnsignedBytes(endMs * 1_000_000L)) +
                ebmlElement("98", byteArrayOf(if (isHidden) 1 else 0)) +
                ebmlElement(
                    "80",
                    ebmlElement("85", title.encodeToByteArray()) +
                        ebmlElement("437D", language.encodeToByteArray()),
                ) +
                nested,
        )

    private fun ebmlElement(
        id: String,
        payload: ByteArray,
    ): ByteArray = id.hexBytes() + ebmlSize(payload.size) + payload

    private fun String.hexBytes(): ByteArray =
        chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()

    private fun ebmlSize(value: Int): ByteArray =
        when {
            value <= 0x7E -> byteArrayOf((0x80 or value).toByte())
            value <= 0x3FFE ->
                byteArrayOf(
                    (0x40 or (value shr 8)).toByte(),
                    value.toByte(),
                )
            else ->
                byteArrayOf(
                    (0x20 or (value shr 16)).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
        }

    private fun minimalUnsignedBytes(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val byteCount = (Long.SIZE_BITS - value.countLeadingZeroBits() + 7) / 8
        return ByteArray(byteCount) { index ->
            (value shr ((byteCount - index - 1) * 8)).toByte()
        }
    }

    private fun beUInt(value: UInt): ByteArray = beUInt(value.toLong())

    private fun beUInt(value: Int): ByteArray = beUInt(value.toLong())

    private fun beUInt(value: Long): ByteArray =
        byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

    private fun beUShort(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun beLong(value: Long): ByteArray =
        ByteArray(8) { index -> ((value shr ((7 - index) * 8)) and 0xFF).toByte() }

    private fun leUInt(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private fun leUShort(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun leLong(value: Long): ByteArray = ByteArray(8) { index -> ((value shr (index * 8)) and 0xFF).toByte() }

    private fun ByteArray.putLeLong(
        offset: Int,
        value: Long,
    ) {
        leLong(value).copyInto(this, offset)
    }

    private companion object {
        val ASF_HEADER_GUID =
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
        val ASF_FILE_PROPERTIES_GUID =
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
        val ASF_MARKER_GUID =
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
}
