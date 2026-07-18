package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Leaves ASS/SSA samples untouched so [AndroidAssRenderer] can pass them to libass.
 * Every other subtitle format keeps Media3's normal cue transcoding path.
 */
@UnstableApi
internal class AndroidAssSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
    private val nativeAssAvailable: () -> Boolean = { AndroidAssNativeBridge.isAvailable },
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean =
        !(format.isRawMatroskaAss && nativeAssAvailable()) && delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (format.isRawMatroskaAss && nativeAssAvailable()) {
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        } else {
            delegate.getCueReplacementBehavior(format)
        }

    override fun create(format: Format): SubtitleParser {
        require(!(format.isRawMatroskaAss && nativeAssAvailable())) {
            "Raw ASS/SSA must be consumed by AndroidAssRenderer, not converted to Media3 cues."
        }
        return delegate.create(format)
    }
}

internal val Format.isAssSubtitle: Boolean
    get() = sampleMimeType == MimeTypes.TEXT_SSA || codecs == MimeTypes.TEXT_SSA
internal val Format.isRawMatroskaAss: Boolean
    get() =
        isAssSubtitle &&
            initializationData.size >= MIN_MATROSKA_ASS_INITIALIZATION_ENTRIES &&
            initializationData[0].contentEquals(MATROSKA_ASS_DIALOGUE_FORMAT)

private const val MIN_MATROSKA_ASS_INITIALIZATION_ENTRIES = 2
private val MATROSKA_ASS_DIALOGUE_FORMAT =
    "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        .encodeToByteArray()
