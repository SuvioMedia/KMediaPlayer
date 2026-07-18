package io.github.kdroidfilter.composemediaplayer

import java.nio.ByteBuffer

/** Font attachment passed to an optional desktop subtitle renderer. */
public data class DesktopSubtitleFont(
    public val name: String,
    public val data: ByteArray,
) {
    init {
        require(name.isNotBlank()) { "A subtitle font name must not be blank." }
        require(data.isNotEmpty()) { "A subtitle font attachment must not be empty." }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is DesktopSubtitleFont &&
                    name == other.name &&
                    data.contentEquals(other.data)
            )

    override fun hashCode(): Int = 31 * name.hashCode() + data.contentHashCode()
}

/**
 * Renderer session owned by an optional JVM subtitle artifact.
 *
 * The player serializes calls and closes the session before releasing its frame
 * buffers. Implementations must not retain [pixels] after [blendBgraFrame]
 * returns.
 */
public interface DesktopSubtitleRenderer : AutoCloseable {
    /** Human-readable backend name used by diagnostics. */
    public val backendDescription: String

    /** Adds a font attachment before loading or replacing the ASS/SSA track. */
    public fun addFont(font: DesktopSubtitleFont): Boolean

    /** Replaces the active complete ASS/SSA script encoded as UTF-8. */
    public fun setTrack(data: ByteArray): Boolean

    /**
     * Blends the authored subtitle image into a writable direct BGRA8 frame.
     *
     * Returns true for a valid frame even when no subtitle is visible at
     * [timeMs], and false only when the renderer can no longer continue.
     */
    public fun blendBgraFrame(
        pixels: ByteBuffer,
        rowBytes: Int,
        width: Int,
        height: Int,
        timeMs: Long,
    ): Boolean

    override fun close()
}

/** JVM hook implemented by an optional styled-subtitle artifact. */
public interface DesktopSubtitlePipelineExtension : SubtitlePipelineExtension {
    /** Creates an independent renderer session, or returns null when unavailable. */
    public fun createRenderer(): DesktopSubtitleRenderer?
}
