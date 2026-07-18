package io.github.kdroidfilter.composemediaplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Preserves the HLS `VIDEO-RANGE` attribute that Media3 only maps to [ColorInfo] for Dolby Vision
 * variants. Without this annotation ordinary PQ, HLG and SDR renditions become one UNKNOWN
 * adaptive class and ExoPlayer may switch the physical display between HDR and SDR mid-playback.
 */
@UnstableApi
internal class AndroidColorAwareHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        ColorAwareMasterPlaylistParser(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}

@UnstableApi
private class ColorAwareMasterPlaylistParser(
    private val delegate: ParsingLoadable.Parser<HlsPlaylist>,
) : ParsingLoadable.Parser<HlsPlaylist> {
    override fun parse(
        uri: Uri,
        inputStream: InputStream,
    ): HlsPlaylist {
        val buffered = BufferedInputStream(inputStream, PLAYLIST_READ_BUFFER_BYTES)
        buffered.mark(MAX_ANNOTATED_MASTER_PLAYLIST_BYTES + 2)
        val bytes = buffered.readAtMost(MAX_ANNOTATED_MASTER_PLAYLIST_BYTES + 1)
        if (bytes.size > MAX_ANNOTATED_MASTER_PLAYLIST_BYTES) {
            // Keep memory bounded. Oversized manifests remain fully playable through Media3, but
            // are deliberately not rewritten from a truncated prefix.
            buffered.reset()
            return delegate.parse(uri, buffered)
        }

        val parsed = delegate.parse(uri, ByteArrayInputStream(bytes))
        return if (parsed is HlsMultivariantPlaylist) {
            parsed.withVideoRangeAnnotations(
                masterPlaylistUri = uri,
                manifestText = bytes.toString(Charsets.UTF_8),
            )
        } else {
            parsed
        }
    }
}

@UnstableApi
private fun HlsMultivariantPlaylist.withVideoRangeAnnotations(
    masterPlaylistUri: Uri,
    manifestText: String,
): HlsMultivariantPlaylist {
    val rangesByUri =
        parseHlsVideoRanges(
            baseUri = baseUri.ifBlank { masterPlaylistUri.toString() },
            manifestText = manifestText,
            variableDefinitions = variableDefinitions,
        )
    if (rangesByUri.isEmpty()) return this

    var changed = false
    val annotatedVariants =
        variants.map { variant ->
            val range = rangesByUri[variant.url] ?: return@map variant
            val annotatedFormat = variant.format.withDynamicRangeColorInfo(range)
            if (annotatedFormat === variant.format) {
                variant
            } else {
                changed = true
                variant.copyWithFormat(annotatedFormat)
            }
        }

    // A master may put video in EXT-X-MEDIA renditions. Propagate a range only when every variant
    // that references the group agrees, so ambiguous publisher signaling is never guessed.
    val rangesByVideoGroup =
        annotatedVariants
            .mapNotNull { variant ->
                val groupId = variant.videoGroupId ?: return@mapNotNull null
                val range = variant.format.toVideoColorInfo().dynamicRange
                range.takeUnless { it == VideoDynamicRange.UNKNOWN }?.let { groupId to it }
            }.groupBy({ it.first }, { it.second })
            .mapNotNull { (groupId, ranges) ->
                ranges.distinct().singleOrNull()?.let { groupId to it }
            }.toMap()
    val annotatedVideos =
        videos.map { rendition ->
            val range = rangesByVideoGroup[rendition.groupId] ?: return@map rendition
            val annotatedFormat = rendition.format.withDynamicRangeColorInfo(range)
            if (annotatedFormat === rendition.format) {
                rendition
            } else {
                changed = true
                HlsMultivariantPlaylist.Rendition(
                    rendition.url,
                    annotatedFormat,
                    rendition.groupId,
                    rendition.name,
                    rendition.stableRenditionId,
                )
            }
        }
    if (!changed) return this

    return HlsMultivariantPlaylist(
        baseUri,
        tags,
        annotatedVariants,
        annotatedVideos,
        audios,
        subtitles,
        closedCaptions,
        muxedAudioFormat,
        muxedCaptionFormats,
        hasIndependentSegments,
        variableDefinitions,
        sessionKeyDrmInitData,
        contentSteeringInfo,
    )
}

private fun parseHlsVideoRanges(
    baseUri: String,
    manifestText: String,
    variableDefinitions: Map<String, String>,
): Map<Uri, VideoDynamicRange> {
    val lines = manifestText.lineSequence().map(String::trim).toList()
    val ranges = mutableMapOf<Uri, VideoDynamicRange>()
    val conflictingUris = mutableSetOf<Uri>()
    lines.forEachIndexed { index, line ->
        val isRegularVariant = line.startsWith(HLS_STREAM_INF_PREFIX)
        val isIFrameVariant = line.startsWith(HLS_I_FRAME_STREAM_INF_PREFIX)
        if (!isRegularVariant && !isIFrameVariant) return@forEachIndexed
        val range =
            VIDEO_RANGE_REGEX
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toVideoDynamicRange() ?: return@forEachIndexed
        val rawReference =
            if (isIFrameVariant) {
                URI_ATTRIBUTE_REGEX.find(line)?.groupValues?.get(1)
            } else {
                lines.getOrNull(index + 1)
            }?.takeIf { it.isNotBlank() && !it.startsWith('#') } ?: return@forEachIndexed
        val reference = rawReference.replaceHlsVariableReferences(variableDefinitions)
        val resolvedUri = UriUtil.resolveToUri(baseUri, reference)
        if (resolvedUri in conflictingUris) return@forEachIndexed
        val previous = ranges[resolvedUri]
        if (previous != null && previous != range) {
            ranges.remove(resolvedUri)
            conflictingUris += resolvedUri
        } else {
            ranges[resolvedUri] = range
        }
    }
    return ranges
}

private fun String.replaceHlsVariableReferences(definitions: Map<String, String>): String {
    var resolved = this
    repeat(MAX_VARIABLE_EXPANSION_PASSES) {
        val next =
            HLS_VARIABLE_REFERENCE_REGEX.replace(resolved) { match ->
                definitions[match.groupValues[1]] ?: match.value
            }
        if (next == resolved) return resolved
        resolved = next
    }
    return resolved
}

@UnstableApi
private fun Format.withDynamicRangeColorInfo(dynamicRange: VideoDynamicRange): Format {
    if (colorInfo != null) return this
    val annotatedColorInfo =
        when (dynamicRange) {
            VideoDynamicRange.SDR -> ColorInfo.SDR_BT709_LIMITED
            VideoDynamicRange.HDR10,
            VideoDynamicRange.HDR10_PLUS,
            -> hdrColorInfo(C.COLOR_TRANSFER_ST2084)
            VideoDynamicRange.HLG -> hdrColorInfo(C.COLOR_TRANSFER_HLG)
            VideoDynamicRange.DOLBY_VISION,
            VideoDynamicRange.UNKNOWN,
            -> null
        } ?: return this
    return buildUpon().setColorInfo(annotatedColorInfo).build()
}

@UnstableApi
private fun hdrColorInfo(transfer: Int): ColorInfo =
    ColorInfo
        .Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(transfer)
        .setLumaBitdepth(HDR_BIT_DEPTH)
        .setChromaBitdepth(HDR_BIT_DEPTH)
        .build()

private fun String.toVideoDynamicRange(): VideoDynamicRange =
    when (this) {
        "SDR" -> VideoDynamicRange.SDR
        "PQ" -> VideoDynamicRange.HDR10
        "HLG" -> VideoDynamicRange.HLG
        else -> VideoDynamicRange.UNKNOWN
    }

private fun InputStream.readAtMost(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, PLAYLIST_READ_BUFFER_BYTES))
    val buffer = ByteArray(PLAYLIST_READ_BUFFER_BYTES)
    var remaining = maximumBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count > 0) {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    return output.toByteArray()
}

private val VIDEO_RANGE_REGEX = Regex("VIDEO-RANGE=(SDR|PQ|HLG)(?:,|$)")
private val URI_ATTRIBUTE_REGEX = Regex("(?:^|[:,])URI=\"([^\"]+)\"")
private val HLS_VARIABLE_REFERENCE_REGEX = Regex("\\{\\$([A-Za-z0-9_-]+)\\}")
private const val HLS_STREAM_INF_PREFIX = "#EXT-X-STREAM-INF:"
private const val HLS_I_FRAME_STREAM_INF_PREFIX = "#EXT-X-I-FRAME-STREAM-INF:"
private const val MAX_ANNOTATED_MASTER_PLAYLIST_BYTES = 4 * 1024 * 1024
private const val PLAYLIST_READ_BUFFER_BYTES = 16 * 1024
private const val MAX_VARIABLE_EXPANSION_PASSES = 16
private const val HDR_BIT_DEPTH = 10
