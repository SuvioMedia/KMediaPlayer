package io.github.kdroidfilter.composemediaplayer.subtitle

import android.content.Context
import androidx.media3.common.Format
import java.io.Closeable
import java.nio.ByteBuffer

/** Owns one native libass library, renderer and track behind a single opaque handle. */
internal class AndroidAssNativeSession private constructor(
    private var handle: Long,
) : Closeable {
    private val renderMetadata = IntArray(RENDER_METADATA_SIZE)
    private var configuredStorageWidth = 0
    private var configuredStorageHeight = 0
    private var configuredFrameWidth = 0
    private var configuredFrameHeight = 0

    fun appendChunk(
        startMs: Long,
        chunk: AndroidAssChunk,
    ) {
        val currentHandle = handle
        if (currentHandle == CLOSED_HANDLE) return
        check(
            AndroidAssNativeBridge.nativeProcessChunk(
                handle = currentHandle,
                startMs = startMs.coerceAtLeast(0L),
                durationMs = chunk.durationMs.coerceAtLeast(0L),
                data = chunk.payload,
                offset = 0,
                length = chunk.payload.size,
            ),
        ) { "libass rejected an embedded ASS/SSA packet." }
    }

    fun addFont(
        name: String,
        data: ByteArray,
    ) {
        val currentHandle = handle
        if (currentHandle == CLOSED_HANDLE || data.isEmpty()) return
        check(AndroidAssNativeBridge.nativeAddFont(currentHandle, name, data)) {
            "libass rejected an embedded font attachment."
        }
    }

    fun configure(
        storageWidth: Int,
        storageHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        val currentHandle = handle
        if (currentHandle == CLOSED_HANDLE) return
        if (
            storageWidth > 0 &&
            storageHeight > 0 &&
            (storageWidth != configuredStorageWidth || storageHeight != configuredStorageHeight)
        ) {
            AndroidAssNativeBridge.nativeSetStorageSize(currentHandle, storageWidth, storageHeight)
            configuredStorageWidth = storageWidth
            configuredStorageHeight = storageHeight
        }
        if (
            frameWidth > 0 &&
            frameHeight > 0 &&
            (frameWidth != configuredFrameWidth || frameHeight != configuredFrameHeight)
        ) {
            AndroidAssNativeBridge.nativeSetFrameSize(currentHandle, frameWidth, frameHeight)
            configuredFrameWidth = frameWidth
            configuredFrameHeight = frameHeight
        }
    }

    fun renderFrame(
        positionMs: Long,
        force: Boolean,
    ): AndroidAssRenderFrame {
        val currentHandle = handle
        if (currentHandle == CLOSED_HANDLE) return AndroidAssRenderFrame.Empty
        val pixels =
            AndroidAssNativeBridge.nativeRender(
                handle = currentHandle,
                timeMs = positionMs.coerceAtLeast(0L),
                force = force,
                metadata = renderMetadata,
            )
        return when (renderMetadata[METADATA_STATUS]) {
            RENDER_STATUS_UNCHANGED -> AndroidAssRenderFrame.Unchanged
            RENDER_STATUS_EMPTY -> AndroidAssRenderFrame.Empty
            RENDER_STATUS_PIXELS -> {
                val width = renderMetadata[METADATA_WIDTH]
                val height = renderMetadata[METADATA_HEIGHT]
                val stride = renderMetadata[METADATA_STRIDE]
                require(
                    width in 1..Int.MAX_VALUE / RGBA_BYTES_PER_PIXEL &&
                        height > 0 &&
                        stride == width * RGBA_BYTES_PER_PIXEL &&
                        height <= Int.MAX_VALUE / stride &&
                        pixels != null &&
                        pixels.capacity() >= stride * height,
                ) {
                    "libass returned invalid RGBA frame metadata."
                }
                pixels.position(0)
                pixels.limit(stride * height)
                AndroidAssRenderFrame.Pixels(
                    x = renderMetadata[METADATA_X],
                    y = renderMetadata[METADATA_Y],
                    width = width,
                    height = height,
                    stride = stride,
                    data = pixels,
                )
            }

            else -> error("Native libass rendering failed.")
        }
    }

    override fun close() {
        val currentHandle = handle
        if (currentHandle == CLOSED_HANDLE) return
        handle = CLOSED_HANDLE
        AndroidAssNativeBridge.nativeClose(currentHandle)
    }

    companion object {
        fun embedded(
            context: Context,
            format: Format,
            fonts: List<AndroidAssFontAttachment> = emptyList(),
        ): AndroidAssNativeSession {
            require(format.isRawMatroskaAss) { "Expected a raw Matroska ASS/SSA format." }
            val codecPrivate = format.initializationData.assCodecPrivate()
            return create(context, fonts) { nativeHandle ->
                AndroidAssNativeBridge.nativeProcessCodecPrivate(
                    nativeHandle,
                    codecPrivate,
                    0,
                    codecPrivate.size,
                )
            }
        }

        fun external(
            context: Context,
            script: ByteArray,
        ): AndroidAssNativeSession {
            require(script.isNotEmpty()) { "The external ASS/SSA script is empty." }
            require(script.size <= MAX_EXTERNAL_ASS_BYTES) { "The external ASS/SSA script is too large." }
            return create(context, emptyList()) { nativeHandle ->
                AndroidAssNativeBridge.nativeProcessData(nativeHandle, script, 0, script.size)
            }
        }

        private inline fun create(
            context: Context,
            fonts: List<AndroidAssFontAttachment>,
            loadTrack: (Long) -> Boolean,
        ): AndroidAssNativeSession {
            AndroidAssNativeBridge.requireAvailable()
            val nativeHandle = AndroidAssNativeBridge.nativeCreate()
            check(nativeHandle != CLOSED_HANDLE) { "Cannot initialize the bundled libass renderer." }
            var closeOnFailure = true
            try {
                fonts.forEach { font ->
                    check(AndroidAssNativeBridge.nativeAddFont(nativeHandle, font.name, font.data)) {
                        "libass rejected an embedded font attachment."
                    }
                }
                check(
                    AndroidAssNativeBridge.nativeConfigureFonts(
                        nativeHandle,
                        AndroidAssFontConfig.ensure(context.applicationContext),
                    ),
                ) { "Cannot configure Android system font discovery for libass." }
                AndroidAssNativeBridge.nativeSetCacheLimits(
                    nativeHandle,
                    DEFAULT_GLYPH_CACHE_LIMIT,
                    DEFAULT_BITMAP_CACHE_MIB,
                )
                check(loadTrack(nativeHandle)) { "libass rejected the ASS/SSA track header." }
                return AndroidAssNativeSession(nativeHandle).also { closeOnFailure = false }
            } finally {
                if (closeOnFailure) AndroidAssNativeBridge.nativeClose(nativeHandle)
            }
        }
    }
}

internal sealed interface AndroidAssRenderFrame {
    data object Unchanged : AndroidAssRenderFrame

    data object Empty : AndroidAssRenderFrame

    data class Pixels(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val stride: Int,
        val data: ByteBuffer,
    ) : AndroidAssRenderFrame
}

private fun List<ByteArray>.assCodecPrivate(): ByteArray {
    val codecPrivate = getOrNull(1) ?: error("Media3 did not expose Matroska CodecPrivate for ASS/SSA.")
    var size = codecPrivate.size
    while (size > 0 && codecPrivate[size - 1] == 0.toByte()) size -= 1
    require(size > 0) { "The Matroska ASS/SSA CodecPrivate header is empty." }
    return codecPrivate.copyOf(size)
}

internal const val MAX_EXTERNAL_ASS_BYTES: Int = 16 * 1024 * 1024

private const val CLOSED_HANDLE = 0L
private const val DEFAULT_GLYPH_CACHE_LIMIT = 10_000
private const val DEFAULT_BITMAP_CACHE_MIB = 32
private const val RENDER_METADATA_SIZE = 6
private const val METADATA_STATUS = 0
private const val METADATA_X = 1
private const val METADATA_Y = 2
private const val METADATA_WIDTH = 3
private const val METADATA_HEIGHT = 4
private const val METADATA_STRIDE = 5
private const val RENDER_STATUS_UNCHANGED = 0
private const val RENDER_STATUS_EMPTY = 1
private const val RENDER_STATUS_PIXELS = 2
private const val RGBA_BYTES_PER_PIXEL = 4
