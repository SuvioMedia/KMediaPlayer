package io.github.kdroidfilter.composemediaplayer.subtitle

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** Replaces only Media3's Matroska extractor so embedded ASS fonts can be collected safely. */
@UnstableApi
internal class AndroidAssExtractorsFactory(
    private val controller: AndroidAssController,
    initialSubtitleParserFactory: SubtitleParser.Factory,
) : ExtractorsFactory {
    private val delegate =
        DefaultExtractorsFactory()
            .setSubtitleParserFactory(DefaultSubtitleParserFactory())

    @Volatile
    private var subtitleParserFactory = initialSubtitleParserFactory

    @Synchronized
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        return this
    }

    override fun createExtractors(): Array<Extractor> = wrapMatroska(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrapMatroska(delegate.createExtractors(uri, responseHeaders))

    private fun wrapMatroska(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index ->
            if (extractors[index] is MatroskaExtractor && AndroidAssNativeBridge.isAvailable) {
                AndroidAssMatroskaExtractor(
                    subtitleParserFactory = subtitleParserFactory,
                    fontSink = controller.createFontAttachmentSink(),
                )
            } else {
                extractors[index]
            }
        }
}

@UnstableApi
private class AndroidAssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    private val fontSink: (AndroidAssFontAttachment) -> Unit,
) : MatroskaExtractor(subtitleParserFactory) {
    private var pendingAttachment: PendingAttachment? = null

    override fun getElementType(id: Int): Int =
        when (id) {
            ID_ATTACHMENTS,
            ID_ATTACHED_FILE,
            -> ELEMENT_TYPE_MASTER

            ID_FILE_NAME,
            ID_FILE_MIME_TYPE,
            -> ELEMENT_TYPE_STRING

            ID_FILE_DATA -> ELEMENT_TYPE_BINARY
            else -> super.getElementType(id)
        }

    override fun isLevel1Element(id: Int): Boolean = id == ID_ATTACHMENTS || super.isLevel1Element(id)

    override fun startMasterElement(
        id: Int,
        contentPosition: Long,
        contentSize: Long,
    ) {
        super.startMasterElement(id, contentPosition, contentSize)
        if (id == ID_ATTACHED_FILE) pendingAttachment = PendingAttachment()
    }

    override fun stringElement(
        id: Int,
        value: String,
    ) {
        when (id) {
            ID_FILE_NAME -> pendingAttachment?.name = value.safeFontFileName()
            ID_FILE_MIME_TYPE -> pendingAttachment?.mimeType = value.take(MAX_FONT_MIME_LENGTH)
            else -> super.stringElement(id, value)
        }
    }

    override fun binaryElement(
        id: Int,
        contentSize: Int,
        input: ExtractorInput,
    ) {
        if (id != ID_FILE_DATA) {
            super.binaryElement(id, contentSize, input)
            return
        }

        val pending = pendingAttachment
        if (pending == null || contentSize !in 1..MAX_EMBEDDED_FONT_BYTES || pending.data != null) {
            input.skipFully(contentSize)
            return
        }
        pending.data = ByteArray(contentSize).also { data -> input.readFully(data, 0, contentSize) }
    }

    override fun endMasterElement(id: Int) {
        super.endMasterElement(id)
        if (id != ID_ATTACHED_FILE) return

        val pending = pendingAttachment
        pendingAttachment = null
        val data = pending?.data ?: return
        if (!data.hasSupportedFontSignature()) return
        fontSink(
            AndroidAssFontAttachment(
                name = pending.name.ifBlank { DEFAULT_FONT_FILE_NAME },
                mimeType = pending.mimeType,
                data = data,
            ),
        )
    }

    private class PendingAttachment(
        var name: String = "",
        var mimeType: String = "",
        var data: ByteArray? = null,
    )

    private companion object {
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_ATTACHED_FILE = 0x61A7
        const val ID_FILE_NAME = 0x466E
        const val ID_FILE_MIME_TYPE = 0x4660
        const val ID_FILE_DATA = 0x465C

        const val ELEMENT_TYPE_MASTER = 1
        const val ELEMENT_TYPE_STRING = 3
        const val ELEMENT_TYPE_BINARY = 4

        const val MAX_FONT_MIME_LENGTH = 128
        const val DEFAULT_FONT_FILE_NAME = "embedded-font.ttf"
    }
}

internal data class AndroidAssFontAttachment(
    val name: String,
    val mimeType: String,
    val data: ByteArray,
)

private fun String.safeFontFileName(): String =
    substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot(Char::isISOControl)
        .take(MAX_FONT_FILE_NAME_LENGTH)

private fun ByteArray.hasSupportedFontSignature(): Boolean {
    if (size < FONT_SIGNATURE_BYTES) return false
    val signature = String(this, 0, FONT_SIGNATURE_BYTES, Charsets.US_ASCII)
    return SFNT_VERSION_1.indices.all { index -> this[index] == SFNT_VERSION_1[index] } ||
        signature in SUPPORTED_FONT_SIGNATURES
}

internal const val MAX_EMBEDDED_FONT_BYTES: Int = 16 * 1024 * 1024
internal const val MAX_EMBEDDED_FONTS_TOTAL_BYTES: Int = 32 * 1024 * 1024
internal const val MAX_EMBEDDED_FONT_COUNT: Int = 64
private const val MAX_FONT_FILE_NAME_LENGTH = 255
private const val FONT_SIGNATURE_BYTES = 4
private val SFNT_VERSION_1 = byteArrayOf(0, 1, 0, 0)
private val SUPPORTED_FONT_SIGNATURES = setOf("OTTO", "ttcf", "true", "typ1", "wOFF", "wOF2")
