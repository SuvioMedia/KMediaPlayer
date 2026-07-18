package io.github.kdroidfilter.composemediaplayer.subtitle

import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleFont
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleRenderer
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtension
import io.github.kdroidfilter.composemediaplayer.mac.MacEmbeddedAssExtractor
import java.nio.ByteBuffer

/**
 * Thread-safe owner for the optional JVM ASS renderer.
 *
 * Platform player states keep lifecycle and track-selection authority. This
 * class only validates authored subtitle input, creates the configured
 * extension renderer and serializes frame blending with replacement/close.
 */
internal class DesktopAssSubtitleSession(
    extensions: List<VideoPipelineExtension>,
) : AutoCloseable {
    private val lock = Any()
    private val extensions = extensions.filterIsInstance<DesktopSubtitlePipelineExtension>()
    private var renderer: DesktopSubtitleRenderer? = null
    private var trackKey: String? = null
    private var ownerToken: Long? = null

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var backendDescription: String? = null
        private set

    fun configure(
        track: SubtitleTrack,
        content: String,
        fonts: List<DesktopSubtitleFont> = emptyList(),
        streamIndex: Int? = null,
        ownerToken: Long? = null,
    ): String {
        require(content.isNotBlank()) { "The selected subtitle track is empty." }
        require(content.contains("[Events]", ignoreCase = true)) {
            "The selected subtitle track is not valid ASS/SSA content."
        }

        val format =
            track
                .resolvedFormat()
                .takeUnless { it == SubtitleFormat.AUTO }
                ?: SubtitleFormat.fromContent(content)
        require(format.isAssFamily) { "The selected subtitle track is not ASS/SSA." }

        val extension =
            extensions.firstOrNull { candidate -> candidate.supportsSubtitleFormat(format) }
                ?: throw UnsupportedOperationException(
                    "Full ASS/SSA rendering requires an available DesktopSubtitlePipelineExtension.",
                )
        val key =
            buildString {
                append(track.id)
                append('|')
                append(track.src)
                append('|')
                append(streamIndex ?: -1)
                append('|')
                append(content.hashCode())
                fonts.forEach { font ->
                    append('|')
                    append(font.name)
                    append(':')
                    append(font.data.contentHashCode())
                }
            }

        return synchronized(lock) {
            val current = renderer?.takeIf { active && trackKey == key }
            if (current != null) {
                this.ownerToken = ownerToken
                return@synchronized current.backendDescription
            }

            val replacement =
                extension.createRenderer()
                    ?: throw UnsupportedOperationException(
                        "The configured desktop subtitle extension could not create its renderer.",
                    )
            runCatching {
                fonts.forEach { font ->
                    check(replacement.addFont(font)) {
                        "Failed to load the embedded subtitle font '${font.name}'."
                    }
                }
                check(replacement.setTrack(content.encodeToByteArray())) {
                    "Failed to load ASS/SSA subtitle data into libass."
                }
            }.onFailure {
                runCatching { replacement.close() }
            }.getOrThrow()

            val previous = renderer
            renderer = replacement
            trackKey = key
            this.ownerToken = ownerToken
            backendDescription = replacement.backendDescription
            active = true
            runCatching { previous?.close() }
            replacement.backendDescription
        }
    }

    fun blend(
        pixels: ByteBuffer,
        rowBytes: Int,
        width: Int,
        height: Int,
        timeMs: Long,
    ): DesktopAssBlendResult =
        synchronized(lock) {
            val current = renderer?.takeIf { active } ?: return@synchronized DesktopAssBlendResult.Inactive
            val rendered =
                runCatching {
                    current.blendBgraFrame(
                        pixels = pixels,
                        rowBytes = rowBytes,
                        width = width,
                        height = height,
                        timeMs = timeMs,
                    )
                }.getOrDefault(false)
            if (rendered) {
                DesktopAssBlendResult.Rendered
            } else {
                renderer = null
                trackKey = null
                ownerToken = null
                backendDescription = null
                active = false
                runCatching { current.close() }
                DesktopAssBlendResult.Failed
            }
        }

    fun clear(ownerToken: Long? = null) {
        synchronized(lock) {
            if (ownerToken != null && this.ownerToken != ownerToken) return
            val previous = renderer
            renderer = null
            trackKey = null
            this.ownerToken = null
            backendDescription = null
            active = false
            runCatching { previous?.close() }
        }
    }

    override fun close() = clear()
}

internal enum class DesktopAssBlendResult {
    Inactive,
    Rendered,
    Failed,
}

internal fun isAssLikeDesktopTrack(track: SubtitleTrack): Boolean =
    when (track.resolvedFormat()) {
        SubtitleFormat.ASS,
        SubtitleFormat.SSA,
        -> true
        SubtitleFormat.AUTO ->
            track.label.endsWith(".ass", ignoreCase = true) ||
                track.label.endsWith(".ssa", ignoreCase = true) ||
                track.src.endsWith(".ass", ignoreCase = true) ||
                track.src.endsWith(".ssa", ignoreCase = true)
        else -> false
    }

internal fun extractEmbeddedDesktopAssSubtitle(
    uri: String,
    streamIndex: Int,
    requestHeaders: Map<String, String>,
): DesktopAssSubtitlePayload {
    val extracted =
        MacEmbeddedAssExtractor.extractComplete(
            uri = uri,
            streamIndex = streamIndex,
            requestHeaders = requestHeaders,
        )
    return DesktopAssSubtitlePayload(
        content = extracted.content,
        fonts =
            extracted.fonts.map { font ->
                DesktopSubtitleFont(name = font.name, data = font.data)
            },
    )
}

internal data class DesktopAssSubtitlePayload(
    val content: String,
    val fonts: List<DesktopSubtitleFont>,
)
