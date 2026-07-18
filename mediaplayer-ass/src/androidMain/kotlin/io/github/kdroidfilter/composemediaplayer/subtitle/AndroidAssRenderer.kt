package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.NoSampleRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource

/** Consumes selected raw ASS/SSA samples without flattening authored effects into Media3 cues. */
@UnstableApi
internal class AndroidAssRenderer(
    private val controller: AndroidAssController,
) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private var currentFormat: Format? = null
    private var streamToken = INVALID_TOKEN
    private var inputEnded = false

    override fun getName(): String = "KMediaLibassRenderer"

    override fun supportsFormat(format: Format): Int =
        RendererCapabilities.create(
            when {
                !format.isRawMatroskaAss -> C.FORMAT_UNSUPPORTED_TYPE
                !AndroidAssNativeBridge.isAvailable -> C.FORMAT_UNSUPPORTED_SUBTYPE
                format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
                else -> C.FORMAT_HANDLED
            },
        )

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = inputEnded

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        val format = formats.firstOrNull(Format::isRawMatroskaAss) ?: return
        currentFormat = format
        inputEnded = false
        streamToken = controller.activateEmbedded(format)
    }

    override fun onPositionReset(
        positionUs: Long,
        joining: Boolean,
        sampleStreamIsResetToKeyFrame: Boolean,
    ) {
        inputEnded = false
        currentFormat?.let { format ->
            if (streamToken != INVALID_TOKEN) {
                streamToken = controller.resetEmbedded(streamToken, format)
            }
        }
    }

    override fun render(
        positionUs: Long,
        elapsedRealtimeUs: Long,
    ) {
        if (inputEnded || streamToken == INVALID_TOKEN) return

        repeat(MAX_SAMPLES_PER_RENDER_CALL) {
            inputBuffer.clear()
            when (readSource(formatHolder, inputBuffer, 0)) {
                C.RESULT_NOTHING_READ -> return
                C.RESULT_FORMAT_READ -> {
                    val format = formatHolder.format ?: return@repeat
                    if (format.isRawMatroskaAss && format != currentFormat) {
                        controller.deactivateEmbedded(streamToken)
                        currentFormat = format
                        streamToken = controller.activateEmbedded(format)
                    }
                }

                C.RESULT_BUFFER_READ -> {
                    if (inputBuffer.isEndOfStream) {
                        inputEnded = true
                        return
                    }
                    inputBuffer.flip()
                    val data = inputBuffer.data ?: return@repeat
                    val size = data.remaining()
                    if (size <= 0 || size > MAX_ASS_SAMPLE_BYTES) return@repeat

                    val sample = ByteArray(size)
                    data.get(sample)
                    val chunk = parseAndroidAssChunk(sample) ?: return@repeat
                    val sampleTimeUs = inputBuffer.timeUs
                    if (sampleTimeUs != C.TIME_UNSET) {
                        val mediaTimeUs = sampleTimeUs - streamOffsetUs
                        if (
                            !controller.appendEmbeddedChunk(
                                token = streamToken,
                                startMs = mediaTimeUs.coerceAtLeast(0L) / MICROS_PER_MILLISECOND,
                                chunk = chunk,
                            )
                        ) {
                            return
                        }
                    }
                }
            }
        }
    }

    override fun onDisabled() {
        controller.deactivateEmbedded(streamToken)
        streamToken = INVALID_TOKEN
        currentFormat = null
        inputEnded = false
        inputBuffer.clear()
    }

    private companion object {
        const val INVALID_TOKEN = -1L
        const val MAX_SAMPLES_PER_RENDER_CALL = 64
        const val MICROS_PER_MILLISECOND = 1_000L
    }
}

/** Accurate media clock for both embedded and external ASS, including playlist renderer offsets. */
@UnstableApi
internal class AndroidAssClockRenderer(
    private val controller: AndroidAssController,
) : NoSampleRenderer() {
    private var rendererOffsetUs = 0L

    override fun getName(): String = "KMediaLibassClock"

    override fun onRendererOffsetChanged(offsetUs: Long) {
        rendererOffsetUs = offsetUs
    }

    override fun onPositionReset(
        positionUs: Long,
        joining: Boolean,
        sampleStreamIsResetToKeyFrame: Boolean,
    ) {
        controller.updateMediaPositionUs(positionUs - rendererOffsetUs)
    }

    override fun render(
        positionUs: Long,
        elapsedRealtimeUs: Long,
    ) {
        controller.updateMediaPositionUs(positionUs - rendererOffsetUs)
    }
}
