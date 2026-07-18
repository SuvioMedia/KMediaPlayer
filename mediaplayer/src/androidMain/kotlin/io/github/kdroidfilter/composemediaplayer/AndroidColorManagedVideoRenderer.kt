package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * Media3 renderer that keeps decoder surfaces inside ExoPlayer's video graph.
 *
 * `PlaybackVideoGraphWrapper` is the component that registers every MediaCodec output frame,
 * applies backpressure before releasing the codec buffer and replaces realtime surface timestamps
 * with media presentation timestamps. An independently attached automatic-registration processor
 * cannot provide those guarantees for decoder output.
 */
@UnstableApi
internal class AndroidColorManagedVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val shouldToneMapToSdr: () -> Boolean,
    private val shouldDecoderToneMapHlgToSdr: () -> Boolean,
    private val convertHlgOutputToPq: Boolean,
    private val onVideoGraphError: (outputDynamicRange: VideoDynamicRange, message: String) -> Unit,
    private val onHdrStaticMetadata: (payload: ByteArray) -> Unit,
    private val onHdr10PlusMetadata: (presentationTimeUs: Long, payload: ByteArray) -> Unit,
) : MediaCodecVideoRenderer(builder) {
    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float,
    ): MediaCodecAdapter.Configuration =
        super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate).also { configuration ->
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                shouldDecoderToneMapHlgToSdr() &&
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG
            ) {
                configuration.mediaFormat.setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                )
            }
        }

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper {
        val decoderToneMapsHlgToSdr = shouldDecoderToneMapHlgToSdr()
        return PlaybackVideoGraphWrapper
            .Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .setClock(clock)
            .setVideoGraphFactory(
                AndroidColorManagedVideoGraphFactory(
                    convertHlgOutputToPq = convertHlgOutputToPq,
                    onVideoGraphError = onVideoGraphError,
                ),
            ).build()
            .also { graph ->
                graph.setIsInputSdrToneMapped(decoderToneMapsHlgToSdr)
                graph.setRequestOpenGlToneMapping(shouldToneMapToSdr() && !decoderToneMapsHlgToSdr)
            }
    }

    override fun handleInputBufferSupplementalData(buffer: DecoderInputBuffer) {
        val presentationTimeUs = buffer.timeUs
        val hdr10PlusPayload = buffer.supplementalData?.copyHdr10PlusPayloadOrNull()
        super.handleInputBufferSupplementalData(buffer)
        if (presentationTimeUs != C.TIME_UNSET && hdr10PlusPayload != null) {
            onHdr10PlusMetadata(presentationTimeUs, hdr10PlusPayload)
        }
    }

    override fun onOutputFormatChanged(
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        super.onOutputFormatChanged(format, mediaFormat)
        mediaFormat
            ?.copyHdrStaticInfoOrNull()
            ?.let(onHdrStaticMetadata)
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean {
        getCodecOutputMediaFormat()
            ?.copyHdr10PlusPayloadOrNull()
            ?.let { payload ->
                onHdr10PlusMetadata(
                    bufferPresentationTimeUs + getBufferTimestampAdjustmentUs(),
                    payload,
                )
            }
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format,
        )
    }
}

@UnstableApi
internal class AndroidColorManagedRenderersFactory(
    context: Context,
    private val audioSink: AudioSink,
    private val subtitleBackend: AndroidSubtitleBackend?,
    private val shouldToneMapToSdr: () -> Boolean,
    private val shouldDecoderToneMapHlgToSdr: () -> Boolean,
    private val convertHlgOutputToPq: Boolean,
    private val onVideoGraphError: (outputDynamicRange: VideoDynamicRange, message: String) -> Unit,
    private val onHdrStaticMetadata: (payload: ByteArray) -> Unit,
    private val onHdr10PlusMetadata: (presentationTimeUs: Long, payload: ByteArray) -> Unit,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = audioSink

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        val defaultRendererIndex =
            out.indexOfFirst { renderer -> renderer.javaClass == MediaCodecVideoRenderer::class.java }
        if (defaultRendererIndex < 0) return

        val builder =
            MediaCodecVideoRenderer
                .Builder(context)
                .setCodecAdapterFactory(getCodecAdapterFactory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out[defaultRendererIndex] =
            AndroidColorManagedVideoRenderer(
                builder = builder,
                shouldToneMapToSdr = shouldToneMapToSdr,
                shouldDecoderToneMapHlgToSdr = shouldDecoderToneMapHlgToSdr,
                convertHlgOutputToPq = convertHlgOutputToPq,
                onVideoGraphError = onVideoGraphError,
                onHdrStaticMetadata = onHdrStaticMetadata,
                onHdr10PlusMetadata = onHdr10PlusMetadata,
            )
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        // Styled extensions run ahead of Media3 so authored animation is never flattened to cues.
        out.addAll(subtitleBackend?.createTextRenderers().orEmpty())
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    }

    override fun buildMiscellaneousRenderers(
        context: Context,
        eventHandler: Handler,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        super.buildMiscellaneousRenderers(context, eventHandler, extensionRendererMode, out)
        out.addAll(subtitleBackend?.createMiscellaneousRenderers().orEmpty())
    }
}

/**
 * Media3 only applies its HLG-to-PQ compatibility path below API 34. Some newer Android drivers
 * advertise the HLG EGL colorspace but fail the first draw with `GL_INVALID_OPERATION`. Routing
 * HLG through Media3's linear BT.2020 graph and a PQ output surface is color-correct and gives the
 * compositor a transfer that can be verified independently.
 */
@UnstableApi
private class AndroidColorManagedVideoGraphFactory(
    private val convertHlgOutputToPq: Boolean,
    private val onVideoGraphError: (outputDynamicRange: VideoDynamicRange, message: String) -> Unit,
) : VideoGraph.Factory {
    private val delegate = SingleInputVideoGraph.Factory()

    override fun create(
        context: Context,
        outputColorInfo: ColorInfo,
        debugViewProvider: DebugViewProvider,
        listener: VideoGraph.Listener,
        listenerExecutor: Executor,
        initialTimestampOffsetUs: Long,
        renderFramesAutomatically: Boolean,
    ): VideoGraph {
        val controlledOutputColorInfo = outputColorInfo.toAndroidControlledOutputColorInfo(convertHlgOutputToPq)
        val controlledOutputDynamicRange = controlledOutputColorInfo.toVideoDynamicRange()
        val forwardingListener =
            object : VideoGraph.Listener {
                override fun onOutputSizeChanged(
                    width: Int,
                    height: Int,
                ) = listener.onOutputSizeChanged(width, height)

                override fun onOutputFrameRateChanged(frameRate: Float) {
                    listener.onOutputFrameRateChanged(frameRate)
                }

                override fun onOutputFrameAvailableForRendering(
                    framePresentationTimeUs: Long,
                    isRedrawnFrame: Boolean,
                ) = listener.onOutputFrameAvailableForRendering(framePresentationTimeUs, isRedrawnFrame)

                override fun onEnded(finalFramePresentationTimeUs: Long) =
                    listener.onEnded(finalFramePresentationTimeUs)

                override fun onError(exception: VideoFrameProcessingException) {
                    onVideoGraphError(
                        controlledOutputDynamicRange,
                        exception.toAndroidVideoGraphFailureMessage(controlledOutputDynamicRange),
                    )
                    listener.onError(exception)
                }
            }
        return delegate.create(
            context,
            controlledOutputColorInfo,
            debugViewProvider,
            forwardingListener,
            listenerExecutor,
            initialTimestampOffsetUs,
            renderFramesAutomatically,
        )
    }

    override fun supportsMultipleInputs(): Boolean = false
}

internal fun ColorInfo.toAndroidControlledOutputColorInfo(convertHlgOutputToPq: Boolean): ColorInfo =
    if (convertHlgOutputToPq && colorTransfer == C.COLOR_TRANSFER_HLG) {
        buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
    } else {
        this
    }

private fun ColorInfo.toVideoDynamicRange(): VideoDynamicRange =
    when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> VideoDynamicRange.HDR10
        C.COLOR_TRANSFER_HLG -> VideoDynamicRange.HLG
        else -> VideoDynamicRange.SDR
    }

private fun Throwable.toAndroidVideoGraphFailureMessage(outputDynamicRange: VideoDynamicRange): String {
    val failureChain =
        generateSequence(this) { it.cause }
            .take(MAX_VIDEO_GRAPH_FAILURE_CAUSES)
            .joinToString(separator = " <- ") { failure ->
                val type = failure::class.simpleName ?: "Throwable"
                val detail = failure.message?.trim().orEmpty()
                if (detail.isEmpty()) type else "$type: $detail"
            }
    return "Android Media3 video graph failed for ${outputDynamicRange.name}: $failureChain"
}

internal fun ByteBuffer.copyHdr10PlusPayloadOrNull(): ByteArray? {
    val data = duplicate().apply { position(0) }
    if (data.remaining() < HDR10_PLUS_HEADER_SIZE) return null
    val payload = ByteArray(data.remaining()).also(data::get)
    val isHdr10Plus =
        payload[0].toUnsignedInt() == HDR10_PLUS_COUNTRY_CODE &&
            payload[1].toUnsignedInt() == 0x00 &&
            payload[2].toUnsignedInt() == HDR10_PLUS_PROVIDER_CODE &&
            payload[3].toUnsignedInt() == 0x00 &&
            payload[4].toUnsignedInt() == HDR10_PLUS_PROVIDER_ORIENTED_CODE &&
            payload[5].toUnsignedInt() == HDR10_PLUS_APPLICATION_IDENTIFIER
    return payload.takeIf { isHdr10Plus }
}

private fun MediaFormat.copyHdr10PlusPayloadOrNull(): ByteArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) return null
    return runCatching {
        val data = getByteBuffer(MediaFormat.KEY_HDR10_PLUS_INFO)?.duplicate() ?: return@runCatching null
        data.position(0)
        ByteArray(data.remaining()).also(data::get).takeIf(ByteArray::isNotEmpty)
    }.getOrNull()
}

internal fun MediaFormat.copyHdrStaticInfoOrNull(): ByteArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return null
    return runCatching {
        val data = getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.duplicate() ?: return@runCatching null
        data.position(0)
        ByteArray(data.remaining()).also(data::get).takeIf(ByteArray::isNotEmpty)
    }.getOrNull()
}

private fun Byte.toUnsignedInt(): Int = toInt() and BYTE_MASK

private const val HDR10_PLUS_HEADER_SIZE = 7
private const val HDR10_PLUS_COUNTRY_CODE = 0xB5
private const val HDR10_PLUS_PROVIDER_CODE = 0x3C
private const val HDR10_PLUS_PROVIDER_ORIENTED_CODE = 0x01
private const val HDR10_PLUS_APPLICATION_IDENTIFIER = 0x04
private const val BYTE_MASK = 0xFF
private const val MAX_VIDEO_GRAPH_FAILURE_CAUSES = 4
