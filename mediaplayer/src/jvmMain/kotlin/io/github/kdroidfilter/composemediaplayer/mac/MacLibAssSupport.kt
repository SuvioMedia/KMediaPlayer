package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import java.util.concurrent.ConcurrentHashMap

internal object MacEmbeddedAssExtractor {
    private val cache = ConcurrentHashMap<String, MacAssSubtitleData>()

    fun extract(
        uri: String,
        streamIndex: Int,
        playbackTimeMs: Long = 0L,
        requestHeaders: Map<String, String> = emptyMap(),
    ): MacAssSubtitleData {
        val headers = requestHeaders.sanitizedRequestHeaders()
        val cacheKey = cacheKey(uri, streamIndex, headers)
        cache[cacheKey]?.let { return it }

        MacMatroskaAssExtractor
            .extractPartial(
                uri = uri,
                streamIndex = streamIndex,
                playbackTimeMs = playbackTimeMs,
                requestHeaders = headers,
            )?.let { return it }

        return extractComplete(uri = uri, streamIndex = streamIndex, requestHeaders = headers)
    }

    fun extractComplete(
        uri: String,
        streamIndex: Int,
        requestHeaders: Map<String, String> = emptyMap(),
    ): MacAssSubtitleData {
        val headers = requestHeaders.sanitizedRequestHeaders()
        val cacheKey = cacheKey(uri, streamIndex, headers)
        cache[cacheKey]?.let { return it }

        var builtInExtractorFailure: Throwable? = null
        try {
            MacMatroskaAssExtractor
                .extract(uri = uri, streamIndex = streamIndex, requestHeaders = headers)
                ?.let { data ->
                    cache[cacheKey] = data
                    return data
                }
        } catch (e: Throwable) {
            builtInExtractorFailure = e
        }

        throw UnsupportedOperationException(
            "Embedded ASS subtitle extraction failed in the built-in Matroska reader" +
                builtInExtractorFailure?.message?.let { ": $it" }.orEmpty() +
                ". The configured media bridge does not expose subtitle extraction to this canvas path; " +
                "use the VLC fallback.",
        )
    }

    private fun cacheKey(
        uri: String,
        streamIndex: Int,
        requestHeaders: Map<String, String>,
    ): String {
        val headerToken = requestHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).hashCode()
        return "$uri#$streamIndex#$headerToken"
    }
}

internal data class MacAssSubtitleData(
    val content: String,
    val fonts: List<MacAssFontAttachment> = emptyList(),
    val isPartial: Boolean = false,
)

internal data class MacAssFontAttachment(
    val name: String,
    val data: ByteArray,
)
