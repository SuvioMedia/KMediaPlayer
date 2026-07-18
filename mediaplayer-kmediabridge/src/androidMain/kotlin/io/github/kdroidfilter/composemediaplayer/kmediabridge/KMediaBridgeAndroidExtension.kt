@file:Suppress("UnstableApiUsage")

package io.github.kdroidfilter.composemediaplayer.kmediabridge

import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.kdroidfilter.composemediaplayer.AndroidPreparedVideoPipelineSource
import io.github.kdroidfilter.composemediaplayer.ColorConversionCapabilities
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import io.github.kdroidfilter.composemediaplayer.VideoSourcePipelineExtension
import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.BridgeOutput
import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.BridgeSupport
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.ffmpeg.AndroidFfmpegNativeDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.github.shusek.kmediabridge.ffmpeg.AndroidFfmpegRuntimeSelection as BridgeAndroidFfmpegRuntimeSelection

/** Selects the Android FFmpeg payload without leaking KMediaBridge runtime types into this artifact's ABI. */
public sealed interface KMediaBridgeAndroidRuntimeSelection {
    /** Uses the separately published, audited runtime AAR. */
    public data object Bundled : KMediaBridgeAndroidRuntimeSelection

    /**
     * Uses an application-controlled compatible KMediaBridge runtime directory.
     *
     * The caller owns the effective license and obligations of that external payload.
     */
    public class ExternalDirectory(
        public val rootDirectory: File,
    ) : KMediaBridgeAndroidRuntimeSelection {
        init {
            require(rootDirectory.isDirectory) {
                "The external Android KMediaBridge runtime directory does not exist."
            }
        }
    }
}

/**
 * Optional Android source bridge for controlled HDR10, HDR10+ and HLG conversion to BT.709 SDR.
 *
 * This adapter does not package native code. [runtimeSelection] chooses either the separately
 * installed audited LGPL runtime AAR or an application-controlled replacement directory.
 */
public class KMediaBridgeAndroidExtension
    @JvmOverloads
    constructor(
        public val runtimeSelection: KMediaBridgeAndroidRuntimeSelection =
            KMediaBridgeAndroidRuntimeSelection.Bundled,
        public val fragmentDurationUs: Long = DEFAULT_FRAGMENT_DURATION_US,
        public val maximumBufferedFragments: Int = DEFAULT_BUFFERED_FRAGMENTS,
        public val maximumFragmentBytes: Int = DEFAULT_MAXIMUM_FRAGMENT_BYTES,
    ) : VideoSourcePipelineExtension {
        init {
            require(fragmentDurationUs in MINIMUM_FRAGMENT_DURATION_US..MAXIMUM_FRAGMENT_DURATION_US) {
                "The fragment duration must be between 250 ms and 10 seconds."
            }
            require(maximumBufferedFragments in 1..MAXIMUM_BUFFERED_FRAGMENTS) {
                "The source bridge may buffer between one and eight fragments."
            }
            require(maximumFragmentBytes in MINIMUM_FRAGMENT_BYTES..MAXIMUM_FRAGMENT_BYTES) {
                "The per-fragment byte limit must be between 1 MiB and 128 MiB."
            }
        }

        override val id: String = "kmediabridge-android-hdr-to-sdr"

        private val driver: AndroidFfmpegNativeDriver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AndroidFfmpegNativeDriver.load(runtimeSelection.toBridgeSelection())
        }

        override val availability: VideoPipelineExtensionAvailability
            get() =
                if (!isSupportedAndroidBridgeAbi()) {
                    VideoPipelineExtensionAvailability.unavailable(
                        "The Android KMediaBridge extension requires arm64-v8a or armeabi-v7a.",
                    )
                } else {
                    runCatching { driver }
                        .fold(
                            onSuccess = { VideoPipelineExtensionAvailability.Available },
                            onFailure = { error ->
                                VideoPipelineExtensionAvailability.unavailable(
                                    "The selected Android KMediaBridge runtime is unavailable: " +
                                        (error.message ?: error::class.simpleName.orEmpty()),
                                )
                            },
                        )
                }

        override val colorConversionCapabilities: ColorConversionCapabilities
            get() =
                if (availability.canContribute) {
                    ColorConversionCapabilities(
                        supportsHdrToSdrSourceBridge = true,
                        supportsStreamingVOD = true,
                    )
                } else {
                    ColorConversionCapabilities()
                }

        override suspend fun prepareSource(request: VideoPipelineSourceRequest): VideoPipelineSourcePreparation {
            if (request.requestedOutputDynamicRange != VideoDynamicRange.SDR) {
                return VideoPipelineSourcePreparation.NotApplicable
            }
            if (!isSupportedAndroidBridgeAbi()) {
                return VideoPipelineSourcePreparation.Rejected(
                    ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
                    "The Android KMediaBridge extension requires arm64-v8a or armeabi-v7a.",
                )
            }
            rejectionBeforeRuntime(request)?.let { return it }
            val localPath = localFilePath(request.uri) ?: return unsupportedLocalInput()
            if (!File(localPath).isFile) return unsupportedLocalInput()
            val startPositionUs =
                request.startPositionMs.toMicrosecondsOrNull()
                    ?: return VideoPipelineSourcePreparation.Rejected(
                        ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
                        "The requested source-bridge start position is too large.",
                    )

            val input =
                MediaInput(
                    locator = localPath,
                    kind = MediaInputKind.FILE,
                    isLive = false,
                    isEncrypted = false,
                )
            val bridgeRequest =
                BridgeRequest(
                    output = BridgeOutput.CMAF_FRAGMENT_STREAM,
                    videoHandling = VideoHandling.TONE_MAP_TO_SDR,
                    audioHandling = AudioHandling.COPY,
                    subtitleHandling = SubtitleHandling.OMIT,
                    fragmentDurationUs = fragmentDurationUs,
                )
            when (val support = driver.evaluate(input, bridgeRequest)) {
                is BridgeSupport.Unsupported ->
                    return VideoPipelineSourcePreparation.Rejected(
                        ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
                        support.reason,
                    )
                is BridgeSupport.Supported -> Unit
            }

            val session = driver.open(input, bridgeRequest)
            return session.closeOnFailure {
                if (startPositionUs > 0L) session.seekTo(startPositionUs)
                VideoPipelineSourcePreparation.Ready(
                    AndroidKMediaBridgePreparedSource(
                        session = session,
                        inputDynamicRange = request.source.dynamicRange,
                        startPositionUs = startPositionUs,
                        maximumBufferedFragments = maximumBufferedFragments,
                        maximumFragmentBytes = maximumFragmentBytes,
                    ),
                )
            }
        }

        private fun rejectionBeforeRuntime(
            request: VideoPipelineSourceRequest,
        ): VideoPipelineSourcePreparation.Rejected? =
            when {
                request.isLive ->
                    VideoPipelineSourcePreparation.Rejected(
                        ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED,
                        "The Android KMediaBridge HDR-to-SDR adapter accepts VOD only.",
                    )
                request.isDrmProtected ->
                    VideoPipelineSourcePreparation.Rejected(
                        ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED,
                        "Encrypted and DRM-protected media is never passed through the conversion bridge.",
                    )
                request.requestHeaders.isNotEmpty() -> unsupportedLocalInput()
                request.source.dynamicRange == VideoDynamicRange.DOLBY_VISION ->
                    VideoPipelineSourcePreparation.Rejected(
                        ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
                        "Dolby Vision requires a profile-aware bridge before HDR-to-SDR conversion.",
                    )
                request.source.dynamicRange !in SUPPORTED_INPUT_DYNAMIC_RANGES ->
                    VideoPipelineSourcePreparation.Rejected(
                        ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
                        "The source is not explicitly identified as HDR10, HDR10+, or HLG.",
                    )
                else -> null
            }

        private fun unsupportedLocalInput(): VideoPipelineSourcePreparation.Rejected =
            VideoPipelineSourcePreparation.Rejected(
                ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
                "The current Android KMediaBridge transport accepts a local file only; " +
                    "bounded remote input is not enabled.",
            )

        private companion object {
            const val DEFAULT_FRAGMENT_DURATION_US = 2_000_000L
            const val MINIMUM_FRAGMENT_DURATION_US = 250_000L
            const val MAXIMUM_FRAGMENT_DURATION_US = 10_000_000L
            const val DEFAULT_BUFFERED_FRAGMENTS = 3
            const val MAXIMUM_BUFFERED_FRAGMENTS = 8
            const val DEFAULT_MAXIMUM_FRAGMENT_BYTES = 32 * 1024 * 1024
            const val MINIMUM_FRAGMENT_BYTES = 1024 * 1024
            const val MAXIMUM_FRAGMENT_BYTES = 128 * 1024 * 1024
        }
    }

private fun isSupportedAndroidBridgeAbi(): Boolean =
    Build.SUPPORTED_ABIS.any { abi ->
        abi == "arm64-v8a" || abi == "armeabi-v7a"
    }

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> MediaBridgeSession.closeOnFailure(block: suspend () -> T): T =
    try {
        block()
    } catch (failure: Throwable) {
        runCatching { close() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }

private fun KMediaBridgeAndroidRuntimeSelection.toBridgeSelection(): BridgeAndroidFfmpegRuntimeSelection =
    when (this) {
        KMediaBridgeAndroidRuntimeSelection.Bundled -> BridgeAndroidFfmpegRuntimeSelection.Bundled
        is KMediaBridgeAndroidRuntimeSelection.ExternalDirectory ->
            BridgeAndroidFfmpegRuntimeSelection.ExternalDirectory(rootDirectory)
    }

private class AndroidKMediaBridgePreparedSource(
    session: MediaBridgeSession,
    inputDynamicRange: VideoDynamicRange,
    startPositionUs: Long,
    maximumBufferedFragments: Int,
    maximumFragmentBytes: Int,
) : AndroidPreparedVideoPipelineSource {
    private val token = UUID.randomUUID().toString()
    private val streamUri = Uri.parse("kmediabridge-fmp4://$token/stream.mp4")
    private val stream =
        AndroidKMediaBridgeByteStream(
            expectedUri = streamUri,
            session = session,
            startPositionUs = startPositionUs,
            maximumBufferedFragments = maximumBufferedFragments,
            maximumFragmentBytes = maximumFragmentBytes,
        )

    override val uri: String = streamUri.toString()
    override val outputColorInfo: VideoColorInfo = SDR_BT709_COLOR_INFO
    override val metadataHandling: DynamicMetadataHandling =
        if (inputDynamicRange == VideoDynamicRange.HDR10_PLUS) {
            DynamicMetadataHandling.DROPPED
        } else {
            DynamicMetadataHandling.APPLIED_BY_SOURCE_BRIDGE
        }
    override val detail: String =
        if (inputDynamicRange == VideoDynamicRange.HDR10_PLUS) {
            "KMediaBridge emitted limited-range BT.709 SDR; HDR10+ dynamic metadata was dropped and the explicit PQ signal was tone-mapped."
        } else {
            "KMediaBridge applied the explicit HDR transfer and emitted limited-range BT.709 SDR."
        }

    override fun createMediaSource(): MediaSource =
        ProgressiveMediaSource
            .Factory(DataSource.Factory(stream::newDataSource))
            .createMediaSource(
                MediaItem
                    .Builder()
                    .setUri(streamUri)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build(),
            )

    override fun close(): Unit = stream.close()
}

private class AndroidKMediaBridgeByteStream(
    private val expectedUri: Uri,
    private val session: MediaBridgeSession,
    private val startPositionUs: Long,
    maximumBufferedFragments: Int,
    private val maximumFragmentBytes: Int,
) {
    private val queue = ArrayBlockingQueue<StreamItem>(maximumBufferedFragments)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readerClaimed = AtomicBoolean(false)
    private val collectionStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun newDataSource(): DataSource = AndroidKMediaBridgeDataSource(this)

    @Throws(IOException::class)
    fun open(dataSpec: DataSpec) {
        val rejection =
            when {
                closed.get() -> "The KMediaBridge stream is closed."
                dataSpec.uri != expectedUri || dataSpec.position != 0L ->
                    "The KMediaBridge stream does not support this resource or byte range."
                !readerClaimed.compareAndSet(false, true) ->
                    "The KMediaBridge stream supports one sequential Media3 reader."
                else -> null
            }
        if (rejection != null) {
            throw IOException(rejection)
        }
        startCollection()
    }

    @Throws(IOException::class)
    fun nextItem(): StreamItem {
        while (true) {
            if (closed.get() && queue.isEmpty()) return StreamItem.End
            try {
                queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)?.let { return it }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("KMediaBridge stream reading was interrupted.")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        queue.offer(StreamItem.End)
        runBlocking(Dispatchers.IO) { session.close() }
        scope.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startCollection() {
        if (!collectionStarted.compareAndSet(false, true)) return
        scope.launch {
            var outputConfigured = false
            try {
                session.events
                    .takeWhile { event -> event !is MediaBridgeEvent.EndOfStream }
                    .collect { event ->
                        when (event) {
                            is MediaBridgeEvent.OutputConfigured -> {
                                validateOutput(event)
                                outputConfigured = true
                            }
                            is MediaBridgeEvent.Fragment -> {
                                check(outputConfigured) { "KMediaBridge emitted media before its output contract." }
                                val bytes = event.value.bytes
                                check(bytes.isNotEmpty()) { "KMediaBridge emitted an empty media fragment." }
                                check(bytes.size <= maximumFragmentBytes) {
                                    "A KMediaBridge media fragment exceeded the configured memory limit."
                                }
                                enqueue(StreamItem.Bytes(bytes))
                            }
                            is MediaBridgeEvent.Discontinuity ->
                                check(event.resumeTimeUs == startPositionUs) {
                                    "KMediaBridge emitted an unexpected stream discontinuity."
                                }
                            MediaBridgeEvent.EndOfStream -> Unit
                        }
                    }
                enqueue(StreamItem.End)
            } catch (cancelled: CancellationException) {
                if (!closed.get()) {
                    enqueue(StreamItem.Failure(IOException("KMediaBridge conversion was cancelled.", cancelled)))
                }
                throw cancelled
            } catch (failure: Throwable) {
                enqueue(
                    StreamItem.Failure(
                        IOException("KMediaBridge failed to produce a verified SDR stream.", failure),
                    ),
                )
            } finally {
                session.close()
            }
        }
    }

    private fun validateOutput(event: MediaBridgeEvent.OutputConfigured) {
        val output = event.value.outputColorInfo
        check(event.value.videoHandling == VideoHandling.TONE_MAP_TO_SDR)
        check(output?.dynamicRange == DynamicRangeFormat.SDR)
        check(output.range == ColorRange.LIMITED)
        check(output.primaries == ColorPrimaries.BT709)
        check(output.transfer == ColorTransfer.BT709)
        check(output.matrix == ColorMatrix.BT709)
    }

    private fun enqueue(item: StreamItem) {
        while (!closed.get()) {
            try {
                if (queue.offer(item, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}

private class AndroidKMediaBridgeDataSource(
    private val stream: AndroidKMediaBridgeByteStream,
) : BaseDataSource(false) {
    private var opened = false
    private var openedUri: Uri? = null
    private var current = ByteArray(0)
    private var currentPosition = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        stream.open(dataSpec)
        opened = true
        openedUri = dataSpec.uri
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        check(opened) { "The KMediaBridge data source is not open." }
        while (currentPosition >= current.size) {
            when (val item = stream.nextItem()) {
                is StreamItem.Bytes -> {
                    current = item.value
                    currentPosition = 0
                }
                StreamItem.End -> return C.RESULT_END_OF_INPUT
                is StreamItem.Failure -> throw item.cause
            }
        }
        val count = minOf(length, current.size - currentPosition)
        current.copyInto(buffer, offset, currentPosition, currentPosition + count)
        currentPosition += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        current = ByteArray(0)
        currentPosition = 0
        openedUri = null
        if (opened) {
            opened = false
            transferEnded()
            stream.close()
        }
    }
}

private sealed interface StreamItem {
    data class Bytes(
        val value: ByteArray,
    ) : StreamItem

    data class Failure(
        val cause: IOException,
    ) : StreamItem

    data object End : StreamItem
}

private fun localFilePath(uri: String): String? {
    val parsed = Uri.parse(uri)
    return when (parsed.scheme?.lowercase()) {
        null, "" -> uri
        "file" -> parsed.path?.takeIf { parsed.authority.isNullOrEmpty() }
        else -> null
    }
}

private fun Long.toMicrosecondsOrNull(): Long? =
    if (this <= Long.MAX_VALUE / MICROSECONDS_PER_MILLISECOND) {
        this * MICROSECONDS_PER_MILLISECOND
    } else {
        null
    }

private val SUPPORTED_INPUT_DYNAMIC_RANGES =
    setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HLG)

private val SDR_BT709_COLOR_INFO =
    VideoColorInfo(
        dynamicRange = VideoDynamicRange.SDR,
        bitDepth = 8,
        primaries = VideoColorPrimaries.BT709,
        transfer = VideoColorTransfer.SDR,
        matrix = VideoColorMatrix.BT709,
        range = VideoColorRange.LIMITED,
    )

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
