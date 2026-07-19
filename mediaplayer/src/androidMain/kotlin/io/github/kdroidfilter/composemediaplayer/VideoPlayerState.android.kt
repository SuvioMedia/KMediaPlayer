package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Display
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.millisecondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState =
    createAndroidVideoPlayerState {
        requireSupportedAndroidRuntime()
        DefaultVideoPlayerState(audioMode, cacheConfig, playbackOptions)
    }

@UnstableApi
fun interface AndroidMediaSourceProvider {
    fun createMediaSource(request: AndroidMediaSourceRequest): MediaSource?
}

@UnstableApi
data class AndroidMediaSourceRequest(
    val mediaItem: MediaItem,
    val requestHeaders: Map<String, String>,
)

@Composable
@UnstableApi
fun rememberVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
    androidMediaSourceProvider: AndroidMediaSourceProvider,
): VideoPlayerState {
    val currentAndroidMediaSourceProvider = rememberUpdatedState(androidMediaSourceProvider)
    val playerState =
        remember(audioMode, cacheConfig, playbackOptions) {
            createConfiguredVideoPlayerState(
                audioMode = audioMode,
                cacheConfig = cacheConfig,
                playbackOptions = playbackOptions,
                androidMediaSourceProvider = currentAndroidMediaSourceProvider,
            )
        }
    DisposableEffect(playerState) {
        onDispose {
            playerState.dispose()
        }
    }
    return playerState
}

@Composable
@UnstableApi
fun rememberRenderableVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
    androidMediaSourceProvider: AndroidMediaSourceProvider,
): RenderableVideoPlayerState {
    val playerState =
        rememberVideoPlayerState(
            audioMode = audioMode,
            cacheConfig = cacheConfig,
            playbackOptions = playbackOptions,
            androidMediaSourceProvider = androidMediaSourceProvider,
        )
    return remember(playerState) { playerState.asRenderableVideoPlayerState() }
}

@UnstableApi
private fun createConfiguredVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
    androidMediaSourceProvider: State<AndroidMediaSourceProvider>,
): VideoPlayerState =
    createAndroidVideoPlayerState {
        requireSupportedAndroidRuntime()
        ConfiguredAndroidVideoPlayerState(
            audioMode = audioMode,
            cacheConfig = cacheConfig,
            playbackOptions = playbackOptions,
            androidMediaSourceProvider = androidMediaSourceProvider,
        )
    }

internal inline fun createAndroidVideoPlayerState(
    ensureContextAvailable: () -> Unit = { ContextProvider.getContext() },
    createState: () -> VideoPlayerState,
): VideoPlayerState {
    try {
        ensureContextAvailable()
    } catch (_: IllegalStateException) {
        return missingAndroidContextPlayerState()
    }

    // Do not turn unrelated initialization failures into an inert preview player. Once the context is available,
    // constructor failures are real errors and must remain visible to the caller.
    return createState()
}

private fun missingAndroidContextPlayerState(): VideoPlayerState =
    PreviewableVideoPlayerState(
        hasMedia = false,
        isPlaying = false,
        isLoading = false,
        volume = 1f,
        sliderPos = 0f,
        userDragging = false,
        loop = false,
        playbackSpeed = 1f,
        positionText = "00:00",
        durationText = "00:00",
        currentTime = Duration.ZERO,
        duration = Duration.ZERO,
        isFullscreen = false,
        aspectRatio = 16f / 9f,
        error =
            VideoPlayerError.UnknownError(
                "Android context is not available (preview or missing ContextProvider initialization).",
            ),
        metadata = VideoMetadata(),
        subtitlesEnabled = false,
        currentSubtitleTrack = null,
        availableSubtitleTracks = mutableListOf(),
        subtitleTextStyle = TextStyle.Default,
        subtitleBackgroundColor = Color.Transparent,
    )

internal val androidVideoLogger = TaggedLogger("AndroidVideoPlayerSurface")

@UnstableApi
private class ConfiguredAndroidVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
    private val androidMediaSourceProvider: State<AndroidMediaSourceProvider>,
) : DefaultVideoPlayerState(audioMode, cacheConfig, playbackOptions) {
    override fun createMediaSource(
        mediaItem: MediaItem,
        requestHeaders: Map<String, String>,
    ): MediaSource? =
        androidMediaSourceProvider.value.createMediaSource(
            AndroidMediaSourceRequest(
                mediaItem = mediaItem,
                requestHeaders = requestHeaders,
            ),
        ) ?: super.createMediaSource(mediaItem, requestHeaders)
}

private object AndroidPlayerActivityRegistry {
    private val activities = WeakHashMap<DefaultVideoPlayerState, WeakReference<Activity>>()

    fun attach(
        state: DefaultVideoPlayerState,
        activity: Activity,
    ) {
        activities[state] = WeakReference(activity)
    }

    fun detach(
        state: DefaultVideoPlayerState,
        activity: Activity,
    ) {
        if (activities[state]?.get() === activity) {
            activities.remove(state)
        }
    }

    fun activityFor(state: DefaultVideoPlayerState): Activity? = activities[state]?.get()
}

internal fun DefaultVideoPlayerState.attachActivity(activity: Activity) {
    AndroidPlayerActivityRegistry.attach(state = this, activity = activity)
}

internal fun DefaultVideoPlayerState.detachActivity(activity: Activity) {
    AndroidPlayerActivityRegistry.detach(state = this, activity = activity)
}

internal fun DefaultVideoPlayerState.onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
    updatePipActiveFromPlatform(isInPictureInPictureMode)
}

private fun DefaultVideoPlayerState.attachedActivity(): Activity? = AndroidPlayerActivityRegistry.activityFor(this)

private val conservativeCodecHandlingDevices =
    setOf(
        "SM-A155F", // Galaxy A15
        "SM-A156B", // Galaxy A15 5G
    )

private fun shouldUseConservativeAndroidCodecHandling(): Boolean =
    Build.DEVICE in conservativeCodecHandlingDevices ||
        Build.MODEL in conservativeCodecHandlingDevices ||
        Build.MANUFACTURER.equals("mediatek", ignoreCase = true)

internal enum class AndroidAudioFocusStrategy {
    EXCLUSIVE,
    MIX,
    DUCK_OTHERS,
}

internal fun AudioMode.androidAudioFocusStrategy(): AndroidAudioFocusStrategy =
    when (interruptionMode) {
        InterruptionMode.DoNotMix -> AndroidAudioFocusStrategy.EXCLUSIVE
        InterruptionMode.MixWithOthers -> AndroidAudioFocusStrategy.MIX
        InterruptionMode.DuckOthers -> AndroidAudioFocusStrategy.DUCK_OTHERS
    }

private data class AndroidSourceSpec(
    val mediaItem: MediaItem,
    val requestHeaders: Map<String, String>,
    val preparedPipelineSource: PreparedVideoPipelineSource? = null,
)

private data class AndroidExtendedSourcePreparationMode(
    val automaticDolbyVisionConversion: Boolean,
    val sourceBridgeToneMap: Boolean,
) {
    val requestedOutputDynamicRange: VideoDynamicRange?
        get() = VideoDynamicRange.SDR.takeIf { sourceBridgeToneMap }

    val failureReason: ColorPipelineFallbackReason
        get() =
            if (sourceBridgeToneMap) {
                ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE
            } else {
                ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE
            }

    val unavailableDetail: String
        get() =
            if (sourceBridgeToneMap) {
                "No installed Android source extension can tone-map this HDR source to SDR."
            } else {
                "No installed Android source extension can perform the requested Profile 7 conversion."
            }
}

internal data class ScreenLockResumeTicket(
    val sourceGeneration: Long,
    val playbackIntentGeneration: Long,
)

internal fun ScreenLockResumeTicket.isCurrent(
    sourceGeneration: Long,
    playbackIntentGeneration: Long,
    hasMedia: Boolean,
    isDisposed: Boolean,
): Boolean =
    !isDisposed &&
        hasMedia &&
        this.sourceGeneration == sourceGeneration &&
        this.playbackIntentGeneration == playbackIntentGeneration

internal inline fun initializeAndroidPlayerResources(
    initializePlayer: () -> Unit,
    registerReceiver: () -> Unit,
    cleanup: () -> Unit,
) {
    var completed = false
    try {
        initializePlayer()
        registerReceiver()
        completed = true
    } finally {
        if (!completed) cleanup()
    }
}

private fun MediaFormat.hdr10PlusPayloadOrNull(): ByteArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) return null
    return runCatching {
        val metadata = getByteBuffer(MediaFormat.KEY_HDR10_PLUS_INFO)?.duplicate() ?: return@runCatching null
        metadata.position(0)
        ByteArray(metadata.remaining()).also(metadata::get).takeIf(ByteArray::isNotEmpty)
    }.getOrNull()
}

private data class AndroidPlaybackClockSnapshot(
    val positionUs: Long = 0L,
    val sampledAtNs: Long = 0L,
    val playbackSpeed: Float = 1f,
)

@Suppress("LargeClass", "TooManyFunctions")
@UnstableApi
@Stable
open class DefaultVideoPlayerState(
    private val audioMode: AudioMode = AudioMode(),
    private val cacheConfig: CacheConfig = CacheConfig(),
    private val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    companion object {
        private const val PERCENT_SCALE = 100f
        private const val PLAYER_THREAD_DISPATCH_TIMEOUT_MS = 5_000L
        private const val REQUIRED_HDR_VERIFICATION_TIMEOUT_MS = 1_000L
        private const val CONTROLLED_HDR_STARTUP_TIMEOUT_MS = 2_500L
        private const val CONTROLLED_HDR_MIN_PLAYBACK_PROGRESS_MS = 500L
        private const val MIN_CONFIRMED_HDR_SDR_RATIO = 1.01f
        private const val MAX_VALID_AV_SYNC_SAMPLE_US = 1_000_000L
        private const val MICROSECONDS_PER_MILLISECOND = 1_000f
        private const val DOLBY_VISION_PROFILE_7 = 7
    }

    private val context: Context = ContextProvider.getContext()
    internal val androidSubtitleBackend: AndroidSubtitleBackend? =
        playbackOptions.createAndroidSubtitleBackend(context)
    internal var exoPlayer: ExoPlayer? = null
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val playbackEventDispatcher = PlaybackEventDispatcher()
    override val mediaSessionId: Long get() = playbackEventDispatcher.mediaSessionId
    override val playbackEvents = playbackEventDispatcher.events
    private var decoderColorCapabilities by mutableStateOf(DecoderColorCapabilities())
    private var displayColorCapabilities by
        mutableStateOf(
            queryAndroidDisplayColorCapabilities(
                context
                    .getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY),
            ),
        )
    private var rendererColorCapabilities by
        mutableStateOf(queryAndroidRendererColorCapabilities(displayColorCapabilities))
    private val colorPipelineController =
        VideoColorPipelineController(
            playbackOptions = playbackOptions,
            initialCapabilities =
                platformPlayerCapabilities().copy(
                    decoderColorCapabilities = decoderColorCapabilities,
                    displayColorCapabilities = displayColorCapabilities,
                    rendererColorCapabilities = rendererColorCapabilities,
                ),
        )
    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> = colorPipelineController.status
    private var activeColorSurface = VideoSurfaceKind.UNKNOWN
    private var activeSurfaceType: SurfaceType? = null
    private var activeColorSurfaceIsNative = false
    private var controlledColorRendererConfigured = false
    private var controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
    private var controlledColorRendererError: String? = null
    private var controlledHdrRendererFallbackDetail: String? = null
    private val unavailableControlledHdrRanges = mutableSetOf<VideoDynamicRange>()
    private var unavailableNativeSurfaceDataSpaceRanges by mutableStateOf(emptySet<VideoDynamicRange>())
    private var nativeSurfaceDataSpaceFailureDetail: String? = null
    private val systemReportedNativeHdrOutput = AndroidSystemReportedHdrConfirmation()
    private var rendererCapabilitiesDisplayId: Int? = null
    private var activeDisplayId: Int? = null
    private var activeColorDisplay: android.view.Display? = null
    private var hdrRatioListenerDisplay: android.view.Display? = null
    private var requiredHdrVerificationJob: Job? = null
    private var requiredHdrVerificationFailureDetail: String? = null
    private var controlledHdrStartupJob: Job? = null
    private var currentVideoFormat: Format? = null
    private var currentVideoDecoderName: String? = null
    private val renderedVideoFrameCount = AtomicLong()
    private val droppedVideoFrameCount = AtomicLong()
    private val maximumAvSyncOffsetUs = AtomicLong(-1L)

    @Volatile
    private var playbackClockSnapshot = AndroidPlaybackClockSnapshot()
    private val controlledVideoEffectsInitiallyActive =
        playbackOptions.projection.requiresProjectionRenderer

    @Volatile
    private var controlledRendererToneMapToSdr =
        playbackOptions.projection.requiresProjectionRenderer &&
            (
                playbackOptions.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR ||
                    (
                        playbackOptions.dynamicRangePolicy != DynamicRangePolicy.REQUIRE_HDR &&
                            displayColorCapabilities.supportedDynamicRanges.none { range ->
                                range != VideoDynamicRange.SDR && range != VideoDynamicRange.UNKNOWN
                            }
                    )
            )

    @Volatile
    private var decoderToneMapHlgToSdr = false

    @Volatile
    private var controlledVideoGraphFailurePending = false

    @Volatile
    private var playerInstanceGeneration = 0L
    private var controlledVideoEffectsActive = controlledVideoEffectsInitiallyActive
    private var controlledRendererRequestedOutputDynamicRange =
        if (controlledRendererToneMapToSdr) VideoDynamicRange.SDR else VideoDynamicRange.UNKNOWN
    internal val androidProjectionEffectController =
        AndroidProjectionEffectController(
            initialOutputDynamicRange =
                if (controlledRendererToneMapToSdr) VideoDynamicRange.SDR else VideoDynamicRange.UNKNOWN,
            initialRequireHdr = playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR,
        )

    @Volatile
    private var hdr10PlusMetadataConsumer: ((Long, ByteArray) -> Unit)? = null
    private var hdr10PlusMetadataReset: (() -> Unit)? = null
    private var hasRenderedFirstVideoFrame = false
    private var nativeSurfaceConfiguredOutputDynamicRange = VideoDynamicRange.UNKNOWN
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var displayListenerRegistered = false
    private val activeDisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == activeDisplayId) updateActiveColorDisplay(null)
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == activeDisplayId) updateActiveColorDisplay(displayManager.getDisplay(displayId))
            }
        }
    private val hdrRatioChangedListener =
        Consumer<android.view.Display> { display ->
            if (!isPlayerReleased && display.displayId == activeDisplayId) {
                activeColorDisplay = display
                requiredHdrVerificationFailureDetail = null
                refreshColorPipelineOutput()
            }
        }
    private val colorAnalyticsListener =
        object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (!isPlayerReleased && currentSourceSpec != null) {
                    updateVideoColorFormat(format, resetHdr10PlusObservation = true)
                }
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (isPlayerReleased || currentSourceSpec == null) return
                currentVideoDecoderName = decoderName
                updateVideoColorFormat(currentVideoFormat)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                if (isPlayerReleased || currentSourceSpec == null) return
                hasRenderedFirstVideoFrame = true
                refreshColorPipelineOutput()
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                if (!isPlayerReleased && currentSourceSpec != null && droppedFrames > 0) {
                    droppedVideoFrameCount.addAndGet(droppedFrames.toLong())
                }
            }
        }
    private val colorFrameMetadataListener =
        VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, frameFormat, mediaFormat ->
            recordVideoFrameTiming(presentationTimeUs, releaseTimeNs)
            mediaFormat
                ?.hdr10PlusPayloadOrNull()
                ?.let { payload -> handleObservedHdr10PlusMetadata(presentationTimeUs, payload, frameFormat) }
        }

    // Protection against race conditions
    @Volatile
    private var isPlayerReleased = false
    private val playerInitializationLock = Any()
    private var playerListener: Player.Listener? = null
    private var sourceGeneration = 0L
    private var currentSourceSpec: AndroidSourceSpec? = null
    private var sourceConversionJob: Job? = null
    private var hlsChapterDiscovery: AndroidHlsChapterDiscovery? = null
    private var externalAssLoadJob: Job? = null
    private var sourceConversionAttemptGeneration: Long? = null
    private var allowAutomaticDolbyVisionConversion = true
    private var originalPipelineColorInfo: VideoColorInfo? = null
    private var observedHdr10PlusInfo: Hdr10PlusInfo? = null
    private var observedHdrStaticMetadata: Pair<MasteringDisplayMetadata?, ContentLightLevelMetadata?>? = null
    private var sourceLoadedSessionId = 0L
    private var wasStalled = false
    private var cacheLease: VideoCache.Lease? = null

    private val audioFocusStrategy = audioMode.androidAudioFocusStrategy()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var duckAudioFocusRequest: AudioFocusRequest? = null
    private var hasDuckAudioFocus = false
    private var isDuckedByAnotherApp = false
    private val duckAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            coroutineScope.launch {
                synchronized(playerInitializationLock) {
                    if (isPlayerReleased) return@synchronized
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (hasDuckAudioFocus) {
                                isDuckedByAnotherApp = false
                                applyPlayerVolume()
                            }
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            if (hasDuckAudioFocus) {
                                isDuckedByAnotherApp = true
                                applyPlayerVolume()
                            }
                        }

                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        -> {
                            hasDuckAudioFocus = false
                            isDuckedByAnotherApp = false
                            invalidateScreenAutoResume()
                            exoPlayer?.pause()
                            applyPlayerVolume()
                        }
                    }
                }
            }
        }

    // Screen lock detection
    private var screenLockReceiver: BroadcastReceiver? = null
    private var playbackIntentGeneration = 0L
    private var screenLockResumeTicket: ScreenLockResumeTicket? = null
    private var screenResumeJob: Job? = null

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // State properties
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean get() = _isLoading
    private var _isSeeking by mutableStateOf(false)
    override val isSeeking: Boolean get() = _isSeeking

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error
    override val capabilities: PlayerCapabilities
        get() =
            platformPlayerCapabilities()
                .copy(
                    supportsPiP = isPipSupported,
                    decoderColorCapabilities = decoderColorCapabilities,
                    displayColorCapabilities = displayColorCapabilities,
                    rendererColorCapabilities = rendererColorCapabilities,
                ).withPipelineExtensions(playbackOptions)

    private val _metadata = VideoMetadata()
    override val metadata: VideoMetadata get() = _metadata
    override val diagnostics: PlaybackDiagnostics
        get() =
            PlaybackDiagnostics(
                totalVideoFrames = renderedVideoFrameCount.get() + droppedVideoFrameCount.get(),
                renderedVideoFrames = renderedVideoFrameCount.get(),
                droppedVideoFrames = droppedVideoFrameCount.get(),
                maximumAvSyncOffsetMs =
                    maximumAvSyncOffsetUs.get().takeIf { it >= 0L }?.div(MICROSECONDS_PER_MILLISECOND),
                videoWidth = metadata.width,
                videoHeight = metadata.height,
                bitrate = metadata.bitrate?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                currentHlsQuality = currentHlsQuality,
                bufferedRanges = bufferedRanges,
                notes = renderingInfo.notes,
            )
    private var projectionAutoDetectionEnabled = playbackOptions.usesAutoProjectionDetection()
    private var _projection by mutableStateOf(playbackOptions.projection.normalized())
    override var projection: VideoProjectionSettings
        get() = _projection
        set(value) {
            checkNotDisposed()
            projectionAutoDetectionEnabled = false
            applyProjectionSettings(value)
        }
    private var _projectionView by mutableStateOf(playbackOptions.projectionView.normalized())
    override var projectionView: VideoProjectionViewSettings
        get() = _projectionView
        set(value) {
            checkNotDisposed()
            _projectionView = value.normalized()
        }
    private var _projectionViewControlMode by mutableStateOf(playbackOptions.projectionViewControlMode)
    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = _projectionViewControlMode
        set(value) {
            checkNotDisposed()
            _projectionViewControlMode = value
        }
    private var _projectionTextureCrop by mutableStateOf(playbackOptions.projectionTextureCrop.normalized())
    override var projectionTextureCrop: VideoTextureCrop
        get() = _projectionTextureCrop
        set(value) {
            checkNotDisposed()
            _projectionTextureCrop = value.normalized()
            applyProjectionSettings(projection)
        }
    override val renderingInfo: VideoRenderingInfo =
        VideoRenderingInfo(
            backend = "Media3 ExoPlayer",
            videoRenderer = projection.androidVideoRendererLabel(),
            audioRenderer = "AudioTrack",
            videoProjection = projection.renderingInfoLabel(),
        )

    // Subtitle state
    private var _subtitlesEnabled by mutableStateOf(false)
    override var subtitlesEnabled: Boolean
        get() = _subtitlesEnabled
        set(value) {
            checkNotDisposed()
            _subtitlesEnabled = value
        }
    private var _currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override var currentSubtitleTrack: SubtitleTrack?
        get() = _currentSubtitleTrack
        set(value) {
            checkNotDisposed()
            _currentSubtitleTrack = value
        }
    private val _availableSubtitleTracks = mutableStateListOf<SubtitleTrack>()
    override val availableSubtitleTracks: List<SubtitleTrack>
        get() = _availableSubtitleTracks
    private var _subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleTextStyle: TextStyle
        get() = _subtitleTextStyle
        set(value) {
            checkNotDisposed()
            _subtitleTextStyle = value
        }

    private var _subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override var subtitleBackgroundColor: Color
        get() = _subtitleBackgroundColor
        set(value) {
            checkNotDisposed()
            _subtitleBackgroundColor = value
        }
    private var _subtitleOffset by mutableStateOf(Duration.ZERO)
    override var subtitleOffset: Duration
        get() = _subtitleOffset
        set(value) {
            checkNotDisposed()
            _subtitleOffset = value
            androidSubtitleBackend?.updateSubtitleOffsetUs(value.inWholeMicroseconds)
        }

    // Audio track state
    private var _currentAudioTrack by mutableStateOf<AudioTrack?>(null)
    override var currentAudioTrack: AudioTrack?
        get() = _currentAudioTrack
        set(value) {
            checkNotDisposed()
            _currentAudioTrack = value
        }
    private val _availableAudioTracks = mutableStateListOf<AudioTrack>()
    override val availableAudioTracks: List<AudioTrack>
        get() = _availableAudioTracks

    private var playerView: PlayerView? = null
    private var projectionVideoSurfaceView: SurfaceView? = null

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        if (!isOnPlayerThread()) return runOnPlayerThreadBlocking { selectAudioTrack(track) }
        checkNotDisposed()
        val player = exoPlayer
        if (track == null) {
            currentAudioTrack = null
            player?.let {
                it.trackSelectionParameters =
                    it.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
            }
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.TrackChanged(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    kind = TrackKind.AUDIO,
                    trackId = null,
                )
            }
            return TrackSelectionResult.Auto
        }

        if (availableAudioTracks.none { it.id == track.id }) return TrackSelectionResult.NotFound(track.id)
        if (player == null) return TrackSelectionResult.NotSupported

        val trackSelectionOverride =
            track.toAndroidTrackSelectionOverride(player, C.TRACK_TYPE_AUDIO)
                ?: return TrackSelectionResult.NotFound(track.id)

        currentAudioTrack = track
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(trackSelectionOverride)
                .build()
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.AUDIO,
                trackId = track.id,
            )
        }
        return TrackSelectionResult.Selected(track.id)
    }

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult {
        if (!isOnPlayerThread()) return runOnPlayerThreadBlocking { selectAudioTrack(trackId) }
        checkNotDisposed()
        return trackId
            ?.let { id ->
                availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)
    }

    // Select an external subtitle track
    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        if (!isOnPlayerThread()) return runOnPlayerThreadBlocking { selectSubtitleTrack(track) }
        checkNotDisposed()
        if (track == null) {
            return disableSubtitles()
        }

        if (track.isEmbedded && availableSubtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        val player = exoPlayer
        if (track.isEmbedded && player == null) return TrackSelectionResult.NotSupported
        val embeddedTrackSelectionOverride =
            if (player != null && track.isEmbedded) {
                track.toAndroidTrackSelectionOverride(player, C.TRACK_TYPE_TEXT)
                    ?: return TrackSelectionResult.NotFound(track.id)
            } else {
                null
            }

        currentSubtitleTrack = track
        subtitlesEnabled = true
        externalAssLoadJob?.cancel()
        externalAssLoadJob = null

        if (player != null && track.isEmbedded) {
            androidSubtitleBackend?.deactivate()
            val trackSelectionOverride =
                embeddedTrackSelectionOverride ?: return TrackSelectionResult.NotFound(track.id)
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(trackSelectionOverride)
                    .build()

            playerView?.subtitleView?.visibility =
                if (usesAndroidSubtitleBackend(track)) android.view.View.GONE else android.view.View.VISIBLE
        } else if (player != null) {
            val trackParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
            player.trackSelectionParameters = trackParameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
        if (track.isExternal) {
            if (usesAndroidSubtitleBackend(track)) {
                loadExternalAssTrack(track)
            } else {
                androidSubtitleBackend?.deactivate()
            }
        }
        updateNativeSubtitleVisibility()
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.SUBTITLE,
                trackId = track.id,
            )
        }
        return TrackSelectionResult.Selected(track.id)
    }

    override fun selectSubtitleTrack(trackId: String?): TrackSelectionResult {
        if (!isOnPlayerThread()) return runOnPlayerThreadBlocking { selectSubtitleTrack(trackId) }
        checkNotDisposed()
        return trackId
            ?.let { id ->
                availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectSubtitleTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectSubtitleTrack(null as SubtitleTrack?)
    }

    override fun disableSubtitles(): TrackSelectionResult {
        if (!isOnPlayerThread()) return runOnPlayerThreadBlocking(::disableSubtitles)
        checkNotDisposed()
        currentSubtitleTrack = null
        subtitlesEnabled = false
        externalAssLoadJob?.cancel()
        externalAssLoadJob = null
        androidSubtitleBackend?.deactivate()

        exoPlayer?.let { player ->
            val parameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setPreferredTextLanguage(null)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
            player.trackSelectionParameters = parameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
        updateNativeSubtitleVisibility()
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.SUBTITLE,
                trackId = null,
            )
        }
        return TrackSelectionResult.Disabled
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        if (!isOnPlayerThread()) {
            runOnPlayerThreadBlocking { addSubtitleTrack(track) }
            return
        }
        checkNotDisposed()
        val externalTrack = track.copy(isEmbedded = false)
        _availableSubtitleTracks.removeAll { it.id == externalTrack.id }
        _availableSubtitleTracks.add(externalTrack)
    }

    override fun removeSubtitleTrack(trackId: String) {
        if (!isOnPlayerThread()) {
            runOnPlayerThreadBlocking { removeSubtitleTrack(trackId) }
            return
        }
        checkNotDisposed()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            disableSubtitles()
        }
    }

    override fun clearExternalSubtitleTracks() {
        if (!isOnPlayerThread()) {
            runOnPlayerThreadBlocking(::clearExternalSubtitleTracks)
            return
        }
        checkNotDisposed()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.isExternal }
        if (selectedTrack?.isExternal == true) {
            disableSubtitles()
        }
    }

    internal fun attachPlayerView(view: PlayerView?) {
        if (view == null) {
            // Detach the current view
            playerView?.player = null
            playerView = null
            return
        }

        playerView = view
        exoPlayer?.let { player ->
            try {
                view.player = player
                view.subtitleView?.setStyle(CaptionStyleCompat.DEFAULT)
                updateNativeSubtitleVisibility()
            } catch (e: Exception) {
                androidVideoLogger.e { "Error attaching player to view: ${e.message}" }
            }
        }
    }

    internal fun attachProjectionVideoSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceView == null) {
            projectionVideoSurfaceView?.let { oldSurfaceView ->
                runCatching { exoPlayer?.clearVideoSurfaceView(oldSurfaceView) }
                    .onFailure { e ->
                        androidVideoLogger.e { "Error clearing projection surface: ${e.message}" }
                    }
            }
            projectionVideoSurfaceView = null
            return
        }

        if (projectionVideoSurfaceView === surfaceView) return
        projectionVideoSurfaceView?.let { oldSurfaceView ->
            runCatching { exoPlayer?.clearVideoSurfaceView(oldSurfaceView) }
                .onFailure { e ->
                    androidVideoLogger.e { "Error clearing previous projection surface: ${e.message}" }
                }
        }
        playerView?.player = null
        playerView = null
        projectionVideoSurfaceView = surfaceView
        exoPlayer?.let { player ->
            runCatching {
                player.setVideoSurfaceView(surfaceView)
            }.onFailure { e ->
                androidVideoLogger.e { "Error attaching projection surface: ${e.message}" }
            }
        }
    }

    internal fun projectionInputFormat(): Format? = currentVideoFormat

    internal fun hasRenderedFirstVideoFrameForColorVerification(): Boolean = hasRenderedFirstVideoFrame

    internal fun activateControlledColorRenderer(
        outputDynamicRange: VideoDynamicRange,
        requireHdr: Boolean,
    ) {
        if (!isOnPlayerThread()) {
            runOnPlayerThreadBlocking {
                activateControlledColorRenderer(outputDynamicRange, requireHdr)
            }
            return
        }
        val outputDynamicRangeChanged = controlledRendererRequestedOutputDynamicRange != outputDynamicRange
        controlledRendererRequestedOutputDynamicRange = outputDynamicRange
        androidProjectionEffectController.prepareOutput(outputDynamicRange, requireHdr)
        val toneMapToSdr =
            playbackOptions.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR ||
                outputDynamicRange == VideoDynamicRange.SDR
        updateControlledVideoEffects(
            enabled = true,
            toneMapToSdr = toneMapToSdr,
            forceRecreate = outputDynamicRangeChanged,
        )
        scheduleControlledHdrStartupVerification(outputDynamicRange)
    }

    internal fun deactivateControlledColorRendererIfUnused() {
        if (
            (
                playbackOptions.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR &&
                    colorPipelineController.currentPlan?.route != ColorPipelineRoute.SOURCE_BRIDGE_SDR
            ) ||
            projection.requiresProjectionRenderer
        ) {
            return
        }
        if (!isOnPlayerThread()) {
            runOnPlayerThreadBlocking(::deactivateControlledColorRendererIfUnused)
            return
        }
        updateControlledVideoEffects(enabled = false, toneMapToSdr = false)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun updateControlledVideoEffects(
        enabled: Boolean,
        toneMapToSdr: Boolean,
        forceRecreate: Boolean = false,
    ) {
        if (!enabled) cancelControlledHdrStartupVerification()
        val modeChanged =
            forceRecreate ||
                controlledVideoEffectsActive != enabled ||
                (enabled && controlledRendererToneMapToSdr != toneMapToSdr)
        controlledVideoEffectsActive = enabled
        controlledRendererToneMapToSdr = toneMapToSdr
        if (!modeChanged) return

        val player = exoPlayer ?: return
        if (currentSourceSpec == null || player.playbackState == Player.STATE_IDLE) {
            player.setVideoEffects(
                if (enabled) listOf(androidProjectionEffectController.effect) else emptyList(),
            )
            return
        }

        // MediaCodecVideoRenderer retains its VideoSink across stop/prepare. Recreate the player so
        // a change between native output, HDR passthrough and OpenGL tone mapping also recreates
        // PlaybackVideoGraphWrapper with the new output ColorInfo.
        val sourceSpec = currentSourceSpec ?: return
        val positionMs = player.currentPosition
        val playWhenReady = player.playWhenReady
        try {
            invalidateSourceCallbacks(player)
            playerView?.player = null
            runCatching { player.stop() }
            try {
                player.release()
            } finally {
                exoPlayer = null
            }
            initializePlayer()
            exoPlayer?.apply {
                installSourceListener(this)
                setSource(sourceSpec)
                prepare()
                seekTo(positionMs)
                if (playWhenReady && requestDuckAudioFocus()) play() else pause()
            }
        } catch (failure: RuntimeException) {
            androidVideoLogger.e { "Failed to recreate the Android color renderer: ${failure.message}" }
            setError(VideoPlayerError.UnknownError("Color renderer restart failed: ${failure.message}"))
        }
    }

    internal fun attachHdr10PlusMetadataConsumer(
        consumer: ((Long, ByteArray) -> Unit)?,
        reset: (() -> Unit)? = null,
    ) {
        if (consumer == null) hdr10PlusMetadataReset?.invoke()
        hdr10PlusMetadataConsumer = consumer
        hdr10PlusMetadataReset = reset
    }

    private fun recordVideoFrameTiming(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
    ) {
        renderedVideoFrameCount.incrementAndGet()
        if (presentationTimeUs < 0L) return
        val clock = playbackClockSnapshot
        if (clock.sampledAtNs <= 0L || releaseTimeNs <= 0L) return
        val releaseDelayUs =
            ((releaseTimeNs - clock.sampledAtNs) / 1_000L)
                .coerceIn(-MAX_VALID_AV_SYNC_SAMPLE_US, MAX_VALID_AV_SYNC_SAMPLE_US)
        val playbackClockAtReleaseUs =
            clock.positionUs + (releaseDelayUs * clock.playbackSpeed).toLong()
        val absoluteOffsetUs = abs(presentationTimeUs - playbackClockAtReleaseUs)
        if (absoluteOffsetUs > MAX_VALID_AV_SYNC_SAMPLE_US) return
        var previousMaximum = maximumAvSyncOffsetUs.get()
        while (
            absoluteOffsetUs > previousMaximum &&
            !maximumAvSyncOffsetUs.compareAndSet(previousMaximum, absoluteOffsetUs)
        ) {
            previousMaximum = maximumAvSyncOffsetUs.get()
        }
    }

    private fun handleObservedHdr10PlusMetadata(
        presentationTimeUs: Long,
        payload: ByteArray,
        frameFormat: Format? = null,
    ) {
        // Both the projection timeline and Media3's video graph use media presentation time. The
        // custom renderer supplies decoder supplemental data before the codec output buffer enters
        // the graph; the ordinary frame metadata callback remains a fallback for native output.
        hdr10PlusMetadataConsumer?.invoke(presentationTimeUs, payload)
        coroutineScope.launch {
            if (isPlayerReleased || currentSourceSpec == null) return@launch
            val format = frameFormat ?: currentVideoFormat ?: return@launch
            if (frameFormat != null && currentVideoFormat != frameFormat) return@launch
            val promotedSource =
                format
                    .toVideoColorInfo()
                    .withObservedHdr10PlusPayload(payload, presentationTimeUs)
                    ?: return@launch
            if (originalPipelineColorInfo == null && observedHdr10PlusInfo != promotedSource.hdr10Plus) {
                observedHdr10PlusInfo = promotedSource.hdr10Plus
                updateVideoColorFormat(format)
            }
        }
    }

    private fun handleObservedHdrStaticMetadata(payload: ByteArray) {
        val metadata = payload.parseCta861StaticMetadata() ?: return
        coroutineScope.launch {
            if (
                isPlayerReleased ||
                currentSourceSpec == null ||
                originalPipelineColorInfo != null ||
                observedHdrStaticMetadata == metadata
            ) {
                return@launch
            }
            val format = currentVideoFormat ?: return@launch
            val detectedSource = format.toVideoColorInfo()
            if (!detectedSource.isHdr) return@launch
            observedHdrStaticMetadata = metadata
            updateVideoColorFormat(format)
        }
    }

    internal fun updateVideoOutputSurface(
        surfaceType: SurfaceType?,
        display: android.view.Display?,
    ) {
        if (activeSurfaceType != surfaceType) {
            controlledColorRendererConfigured = false
            controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
            nativeSurfaceConfiguredOutputDynamicRange = VideoDynamicRange.UNKNOWN
            systemReportedNativeHdrOutput.reset()
            controlledColorRendererError = null
            requiredHdrVerificationFailureDetail = null
        }
        activeSurfaceType = surfaceType
        activeColorSurface =
            when (surfaceType) {
                SurfaceType.SurfaceView -> VideoSurfaceKind.SURFACE_VIEW
                SurfaceType.TextureView -> VideoSurfaceKind.TEXTURE_VIEW
                SurfaceType.SphericalGlSurfaceView,
                SurfaceType.ProjectedGlSurfaceView,
                -> VideoSurfaceKind.CONTROLLED_GPU_SURFACE
                SurfaceType.Auto,
                null,
                -> VideoSurfaceKind.UNKNOWN
            }
        activeColorSurfaceIsNative = surfaceType == SurfaceType.SurfaceView
        activeDisplayId = display?.displayId
        updateActiveColorDisplay(display)
    }

    internal fun updateControlledColorRendererConfigured(configured: Boolean) {
        cancelControlledHdrStartupVerification()
        if (!configured) controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
        if (controlledColorRendererConfigured == configured) return
        controlledColorRendererConfigured = configured
        refreshColorPipelineOutput()
    }

    internal fun updateNativeSurfaceDataSpaceConfigured(outputDynamicRange: VideoDynamicRange?) {
        val configuredRange = outputDynamicRange ?: VideoDynamicRange.UNKNOWN
        if (outputDynamicRange != null) {
            unavailableNativeSurfaceDataSpaceRanges -= outputDynamicRange
            nativeSurfaceDataSpaceFailureDetail = null
        }
        if (nativeSurfaceConfiguredOutputDynamicRange == configuredRange) return
        nativeSurfaceConfiguredOutputDynamicRange = configuredRange
        refreshColorPipelineOutput()
    }

    internal fun updateNativeSurfaceDataSpaceConfigurationFailed(
        outputDynamicRange: VideoDynamicRange,
        detail: String,
    ) {
        nativeSurfaceConfiguredOutputDynamicRange = VideoDynamicRange.UNKNOWN
        if (systemReportedNativeHdrOutput.confirms(outputDynamicRange)) {
            // ANativeWindow exposes the consumer default on some MediaCodec SurfaceView paths,
            // while Android 14+ independently reports that this source already activated HDR
            // composition. Preserve that stronger positive observation for the current
            // source/surface instead of replacing valid HDR playback with an SDR bridge.
            unavailableNativeSurfaceDataSpaceRanges -= outputDynamicRange
            nativeSurfaceDataSpaceFailureDetail = null
            refreshColorPipelineOutput()
            return
        }
        unavailableNativeSurfaceDataSpaceRanges = unavailableNativeSurfaceDataSpaceRanges + outputDynamicRange
        nativeSurfaceDataSpaceFailureDetail = detail
        refreshColorPipelineOutput()
        // The source was initially planned for the native surface, so the regular format callback
        // has already run. If runtime verification changes that plan to SOURCE_BRIDGE_SDR, trigger
        // preparation now instead of waiting for a second format callback that Media3 will not emit.
        maybePrepareExtendedSource(colorPipelineStatus.value.source, currentVideoFormat)
    }

    internal fun updateControlledColorRendererConfigured(outputDynamicRange: VideoDynamicRange) {
        cancelControlledHdrStartupVerification()
        controlledVideoGraphFailurePending = false
        controlledColorRendererError = null
        controlledColorRendererConfigured = true
        controlledColorRendererOutputDynamicRange = outputDynamicRange
        if (outputDynamicRange.isHdrOutput()) controlledHdrRendererFallbackDetail = null
        refreshColorPipelineOutput()
    }

    internal fun updateControlledHdrRendererUnavailable(
        outputDynamicRange: VideoDynamicRange,
        message: String,
    ) {
        cancelControlledHdrStartupVerification()
        controlledColorRendererConfigured = false
        controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
        controlledHdrRendererFallbackDetail = message
        val failedSourceRange = colorPipelineStatus.value.source.dynamicRange
        val failedRanges =
            if (failedSourceRange.isHdrOutput()) {
                failedSourceRange.androidControlledFailureRanges()
            } else {
                outputDynamicRange.androidControlledFailureRanges()
            }
        if (!unavailableControlledHdrRanges.addAll(failedRanges)) return
        if (failedSourceRange == VideoDynamicRange.HLG) decoderToneMapHlgToSdr = true
        rendererColorCapabilities = currentAndroidRendererColorCapabilities()
        refreshColorPipelineOutput()
        exoPlayer?.let { player ->
            if (player.currentTracks.groups.isNotEmpty()) {
                applyDynamicRangeTrackSelection(player, player.currentTracks)
            }
        }
        colorPipelineController.currentPlan
            ?.takeIf { it.route == ColorPipelineRoute.CONTROLLED_SDR_RENDERER }
            ?.let { fallbackPlan ->
                activateControlledColorRenderer(
                    outputDynamicRange = fallbackPlan.outputDynamicRange,
                    requireHdr = playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR,
                )
            }
    }

    internal fun updateControlledColorRendererFailed(message: String) {
        cancelControlledHdrStartupVerification()
        controlledColorRendererConfigured = false
        controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
        controlledColorRendererError = message
        refreshColorPipelineOutput()
        val error =
            VideoPlayerError.ColorPipelineError(
                reason = ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED,
                message = message,
            )
        stopForColorPipelineError(error)
    }

    internal fun shouldUseControlledColorFallback(): Boolean {
        val plan = colorPipelineController.currentPlan
        if (
            plan?.route == ColorPipelineRoute.CONTROLLED_HDR_RENDERER ||
            plan?.route == ColorPipelineRoute.CONTROLLED_SDR_RENDERER
        ) {
            // The planner has already selected the Media3 video graph. This decision must not
            // depend on the native view completing its first layout, otherwise FORCE_SDR can get
            // stuck on a SurfaceView without ever creating the controlled renderer it planned.
            return true
        }
        if (activeColorSurface == VideoSurfaceKind.UNKNOWN) return false
        val probedControlledRenderer =
            queryAndroidRendererColorCapabilities(
                display = displayColorCapabilities,
                activeSurfaceType = SurfaceType.ProjectedGlSurfaceView,
            )
        val controlledRenderer =
            probedControlledRenderer.copy(
                controlledHdrDynamicRanges =
                    probedControlledRenderer.controlledHdrDynamicRanges - unavailableControlledHdrRanges,
            )
        val nativeOutputCanBeConfirmed = nativeHdrCompositionCanBeConfirmed(plan?.outputDynamicRange)
        if (
            shouldUseControlledHdrVerificationSurface(
                policy = playbackOptions.dynamicRangePolicy,
                plan = plan,
                nativeOutputCanBeConfirmed = nativeOutputCanBeConfirmed,
                controlledRenderer = controlledRenderer,
            )
        ) {
            return true
        }
        return shouldUseControlledToneMappingSurface(
            policy = playbackOptions.dynamicRangePolicy,
            plan = plan,
            rendererSupportsToneMapping = controlledRenderer.supportsToneMappingToSdr,
            sourceCanBeToneMapped = canUseAndroidToneMappingFallback(),
            nativeHdrUnavailable =
                plan?.route == ColorPipelineRoute.SYSTEM_NATIVE_SURFACE &&
                    plan.outputDynamicRange.isHdrOutput() &&
                    !nativeOutputCanBeConfirmed,
        )
    }

    private fun nativeHdrCompositionCanBeConfirmed(outputDynamicRange: VideoDynamicRange?): Boolean {
        if (
            activeSurfaceType == SurfaceType.SurfaceView &&
            outputDynamicRange != null &&
            outputDynamicRange !in unavailableNativeSurfaceDataSpaceRanges &&
            canConfirmAndroidNativeHdrWithSurfaceDataSpace(outputDynamicRange)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val display = activeColorDisplay ?: return false
        return runCatching { display.isHdrSdrRatioAvailable }.getOrDefault(false)
    }

    private fun canUseAndroidToneMappingFallback(): Boolean {
        val source = colorPipelineStatus.value.source
        if (!source.isHdr || playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) return false
        if (playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE) return false
        return source.dynamicRange != VideoDynamicRange.DOLBY_VISION ||
            source.dolbyVision?.hasHdr10CompatibleBaseLayer == true
    }

    private fun updateActiveColorDisplay(display: android.view.Display?) {
        requiredHdrVerificationFailureDetail = null
        val displayId = display?.displayId
        if (rendererCapabilitiesDisplayId != displayId) {
            cancelControlledHdrStartupVerification()
            unavailableControlledHdrRanges.clear()
            unavailableNativeSurfaceDataSpaceRanges = emptySet()
            nativeSurfaceDataSpaceFailureDetail = null
            systemReportedNativeHdrOutput.reset()
            controlledHdrRendererFallbackDetail = null
            decoderToneMapHlgToSdr = false
            rendererCapabilitiesDisplayId = displayId
        }
        activeColorDisplay = display
        updateHdrRatioListener(display)
        displayColorCapabilities =
            if (display == null) {
                DisplayColorCapabilities()
            } else {
                queryAndroidDisplayColorCapabilities(display)
            }
        rendererColorCapabilities = currentAndroidRendererColorCapabilities()
        refreshColorPipelineOutput()
    }

    private fun currentAndroidRendererColorCapabilities(): RendererColorCapabilities =
        queryAndroidRendererColorCapabilities(
            display = displayColorCapabilities,
            // A flat player must not advertise the projection-only GL graph to the planner. The
            // surface chooser probes that graph separately when it is a viable fallback and, once
            // attached, this method will publish its real controlled-renderer capabilities.
            activeSurfaceType =
                activeSurfaceType
                    ?: if (projection.requiresProjectionRenderer) {
                        SurfaceType.ProjectedGlSurfaceView
                    } else {
                        SurfaceType.SurfaceView
                    },
        ).let { capabilities ->
            capabilities.copy(
                controlledHdrDynamicRanges =
                    capabilities.controlledHdrDynamicRanges - unavailableControlledHdrRanges,
            )
        }

    @Suppress("CyclomaticComplexMethod")
    private fun updateVideoColorFormat(
        format: Format?,
        resetHdr10PlusObservation: Boolean = false,
    ) {
        if (resetHdr10PlusObservation) {
            observedHdr10PlusInfo = null
            observedHdrStaticMetadata = null
            hdr10PlusMetadataReset?.invoke()
        }
        val decoderFormatUnchanged = currentVideoFormat == format
        currentVideoFormat = format
        val rawDetectedSource =
            (format?.toVideoColorInfo() ?: VideoColorInfo()).let { source ->
                observedHdrStaticMetadata?.let { (masteringDisplay, contentLightLevel) ->
                    source.copy(
                        masteringDisplay = source.masteringDisplay ?: masteringDisplay,
                        contentLightLevel = source.contentLightLevel ?: contentLightLevel,
                    )
                } ?: source
            }
        val detectedSource =
            observedHdr10PlusInfo
                ?.takeIf {
                    rawDetectedSource.dynamicRange == VideoDynamicRange.HDR10 &&
                        rawDetectedSource.transfer == VideoColorTransfer.PQ
                }?.let { hdr10Plus ->
                    rawDetectedSource.copy(
                        dynamicRange = VideoDynamicRange.HDR10_PLUS,
                        hdr10Plus = hdr10Plus,
                    )
                } ?: rawDetectedSource
        val source = originalPipelineColorInfo ?: detectedSource
        val decoderInput = currentSourceSpec?.preparedPipelineSource?.outputColorInfo ?: detectedSource
        val appliedDolbyVisionProfileMapping = originalPipelineColorInfo?.profile7To81MappingOrNull(decoderInput)
        if (format != null) {
            exoPlayer?.let { player ->
                applyDynamicRangeTrackSelection(player, player.currentTracks)
            }
        }
        val previousSource = colorPipelineStatus.value.source
        val sourceColorChanged = previousSource != source
        val outputSignalChanged = !previousSource.hasSameAndroidOutputSignalAs(source)
        val isObservedHdr10PlusPromotion =
            originalPipelineColorInfo == null &&
                decoderFormatUnchanged &&
                previousSource.dynamicRange == VideoDynamicRange.HDR10 &&
                source.dynamicRange == VideoDynamicRange.HDR10_PLUS
        val isObservedStaticMetadataRefinement =
            originalPipelineColorInfo == null &&
                decoderFormatUnchanged &&
                previousSource.hasSameAndroidOutputSignalAs(source) &&
                (
                    previousSource.masteringDisplay != source.masteringDisplay ||
                        previousSource.contentLightLevel != source.contentLightLevel
                )
        val compatibleRanges =
            buildSet {
                if (decoderInput.dynamicRange != VideoDynamicRange.UNKNOWN) add(decoderInput.dynamicRange)
                if (decoderInput.dynamicRange == VideoDynamicRange.HDR10_PLUS) add(VideoDynamicRange.HDR10)
                if (decoderInput.dolbyVision?.hasHdr10CompatibleBaseLayer == true) add(VideoDynamicRange.HDR10)
            }
        val queriedDolbyVisionCapabilities =
            format
                ?.takeIf { decoderInput.dynamicRange == VideoDynamicRange.DOLBY_VISION }
                ?.queryAndroidDolbyVisionDecoderCapabilities()
        decoderColorCapabilities =
            if (currentVideoDecoderName != null && decoderInput.dynamicRange != VideoDynamicRange.UNKNOWN) {
                DecoderColorCapabilities(
                    isKnown = true,
                    supportedDynamicRanges = compatibleRanges,
                    maxBitDepth = decoderInput.bitDepth,
                    supportedDolbyVisionProfiles =
                        buildSet {
                            queriedDolbyVisionCapabilities
                                ?.supportedDolbyVisionProfiles
                                ?.takeIf { queriedDolbyVisionCapabilities.isDolbyVisionProfileSupportKnown }
                                ?.let(::addAll)
                        },
                    isDolbyVisionProfileSupportKnown =
                        queriedDolbyVisionCapabilities?.isDolbyVisionProfileSupportKnown == true,
                )
            } else if (queriedDolbyVisionCapabilities != null) {
                queriedDolbyVisionCapabilities.copy(
                    supportedDynamicRanges =
                        queriedDolbyVisionCapabilities.supportedDynamicRanges +
                            compatibleRanges.filter { it == VideoDynamicRange.HDR10 },
                )
            } else {
                DecoderColorCapabilities()
            }
        if (outputSignalChanged) {
            requiredHdrVerificationJob?.cancel()
            requiredHdrVerificationJob = null
            requiredHdrVerificationFailureDetail = null
            cancelControlledHdrStartupVerification()
            hasRenderedFirstVideoFrame = false
            // A positive HDR composition observation belongs to one decoded output signal.
            // Do not reuse it after an adaptive SDR/HDR or PQ/HLG variant switch.
            systemReportedNativeHdrOutput.reset()
        }
        if (sourceColorChanged && !isObservedHdr10PlusPromotion && !isObservedStaticMetadataRefinement) {
            hdr10PlusMetadataReset?.invoke()
            controlledColorRendererConfigured = false
            controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
            controlledColorRendererError = null
        }
        val plan =
            colorPipelineController.updateSource(
                source = source,
                decoderInput = decoderInput,
                appliedDolbyVisionProfileMapping = appliedDolbyVisionProfileMapping,
                decoderName = currentVideoDecoderName,
                decoderCapabilities = decoderColorCapabilities,
                // Prepared bridge streams are bounded VOD selected only after the original source
                // passed the live/DRM guards. A sequential fMP4 DataSource has unknown byte length,
                // which Media3 may otherwise classify as live and invalidate the applied route.
                isLive =
                    if (currentSourceSpec?.preparedPipelineSource != null) {
                        false
                    } else {
                        exoPlayer?.isCurrentMediaItemLive == true
                    },
                isDrmProtected =
                    if (currentSourceSpec?.preparedPipelineSource != null) {
                        false
                    } else {
                        format?.drmInitData != null
                    },
                allowAutomaticDolbyVisionConversion = allowAutomaticDolbyVisionConversion,
            )
        enforceRequiredColorPipeline(plan)
        refreshColorPipelineOutput()
        maybePrepareExtendedSource(source, format)
    }

    @Suppress("ReturnCount")
    private fun maybePrepareExtendedSource(
        source: VideoColorInfo,
        format: Format?,
    ) {
        val currentPlan = colorPipelineController.currentPlan
        val preparationMode = extendedSourcePreparationMode(source, currentPlan) ?: return
        val automaticConversion = preparationMode.automaticDolbyVisionConversion
        val sourceBridgeToneMap = preparationMode.sourceBridgeToneMap
        val sourceSpec = currentSourceSpec ?: return
        if (sourceSpec.preparedPipelineSource != null) return
        val generation = sourceGeneration
        if (sourceConversionAttemptGeneration == generation) return
        sourceConversionAttemptGeneration = generation
        sourceConversionJob?.cancel()

        val player = exoPlayer ?: return
        val sourceUri =
            sourceSpec.mediaItem.localConfiguration
                ?.uri
                ?.toString() ?: return
        val resumePlayback = player.playWhenReady
        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
        val isLiveSource = player.isCurrentMediaItemLive
        if (sourceBridgeToneMap) {
            // Do not allow an unsupported HDR frame to reach the native surface while the
            // color-safe replacement stream is being prepared.
            player.stop()
        } else {
            player.pause()
        }
        _isLoading = true

        sourceConversionJob =
            coroutineScope.launch(Dispatchers.IO) {
                val preparation =
                    playbackOptions.prepareSourceWithExtensions(
                        VideoPipelineSourceRequest(
                            uri = sourceUri,
                            requestHeaders = sourceSpec.requestHeaders,
                            mimeType = sourceSpec.mediaItem.localConfiguration?.mimeType,
                            source = source,
                            dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                            dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                            isLive = isLiveSource,
                            isDrmProtected = format?.drmInitData != null,
                            startPositionMs = resumePositionMs,
                            requestedOutputDynamicRange = preparationMode.requestedOutputDynamicRange,
                            automaticDolbyVisionConversionAllowed = automaticConversion,
                        ),
                    )
                when (preparation) {
                    is VideoPipelineSourcePreparation.Ready ->
                        installPreparedAndroidSource(
                            expectedGeneration = generation,
                            originalSource = source,
                            prepared = preparation.source,
                            resumePositionMs = resumePositionMs,
                            resumePlayback = resumePlayback,
                            preparationFailureReason = preparationMode.failureReason,
                        )
                    is VideoPipelineSourcePreparation.Rejected ->
                        rejectPreparedAndroidSource(
                            expectedGeneration = generation,
                            rejection = preparation,
                            automaticConversion = automaticConversion,
                            resumePlayback = resumePlayback,
                        )
                    VideoPipelineSourcePreparation.NotApplicable ->
                        rejectPreparedAndroidSource(
                            expectedGeneration = generation,
                            rejection =
                                VideoPipelineSourcePreparation.Rejected(
                                    preparationMode.failureReason,
                                    preparationMode.unavailableDetail,
                                ),
                            automaticConversion = automaticConversion,
                            resumePlayback = resumePlayback,
                        )
                }
            }
    }

    private fun extendedSourcePreparationMode(
        source: VideoColorInfo,
        currentPlan: VideoColorPipelinePlan?,
    ): AndroidExtendedSourcePreparationMode? {
        val automaticConversion =
            playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.AUTO &&
                allowAutomaticDolbyVisionConversion &&
                currentPlan?.route == ColorPipelineRoute.DOLBY_VISION_CONVERSION
        val explicitConversion =
            playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1
        val sourceBridgeToneMap =
            currentPlan?.route == ColorPipelineRoute.SOURCE_BRIDGE_SDR &&
                source.isHdr &&
                playbackOptions.dynamicRangePolicy != DynamicRangePolicy.REQUIRE_HDR
        val dolbyVisionConversion = explicitConversion || automaticConversion
        if (!dolbyVisionConversion && !sourceBridgeToneMap) return null
        if (
            dolbyVisionConversion &&
            (
                source.dynamicRange != VideoDynamicRange.DOLBY_VISION ||
                    source.dolbyVision?.profile != DOLBY_VISION_PROFILE_7
            )
        ) {
            return null
        }
        return AndroidExtendedSourcePreparationMode(
            automaticDolbyVisionConversion = automaticConversion,
            sourceBridgeToneMap = sourceBridgeToneMap,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun installPreparedAndroidSource(
        expectedGeneration: Long,
        originalSource: VideoColorInfo,
        prepared: PreparedVideoPipelineSource,
        resumePositionMs: Long,
        resumePlayback: Boolean,
        preparationFailureReason: ColorPipelineFallbackReason,
    ) {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                if (isPlayerReleased || expectedGeneration != sourceGeneration) {
                    prepared.close()
                    return@synchronized
                }
                val androidSource = prepared as? AndroidPreparedVideoPipelineSource
                if (androidSource == null) {
                    prepared.close()
                    setError(
                        VideoPlayerError.ColorPipelineError(
                            preparationFailureReason,
                            "The installed converter did not provide a Media3 source.",
                        ),
                    )
                    return@synchronized
                }
                val activePlayer =
                    exoPlayer ?: run {
                        prepared.close()
                        return@synchronized
                    }
                val current =
                    currentSourceSpec ?: run {
                        prepared.close()
                        return@synchronized
                    }
                try {
                    val preparedItem =
                        current.mediaItem
                            .buildUpon()
                            .setUri(prepared.uri)
                            .build()
                    invalidateSourceCallbacks(activePlayer)
                    originalPipelineColorInfo = originalSource
                    currentSourceSpec =
                        AndroidSourceSpec(
                            mediaItem = preparedItem,
                            requestHeaders = prepared.requestHeaders,
                            preparedPipelineSource = androidSource,
                        )
                    currentVideoDecoderName = null
                    decoderColorCapabilities = DecoderColorCapabilities()
                    val isSourceBridgeSdr =
                        colorPipelineController.currentPlan?.route == ColorPipelineRoute.SOURCE_BRIDGE_SDR &&
                            prepared.outputColorInfo.dynamicRange == VideoDynamicRange.SDR
                    if (isSourceBridgeSdr) {
                        cancelControlledHdrStartupVerification()
                        controlledVideoEffectsActive = false
                        controlledRendererToneMapToSdr = false
                        controlledRendererRequestedOutputDynamicRange = VideoDynamicRange.UNKNOWN
                        controlledColorRendererConfigured = false
                        controlledColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
                        controlledVideoGraphFailurePending = false
                    }
                    // Some Media3 decoders reuse their input Format across the source replacement and
                    // do not emit another onVideoInputFormatChanged callback. Publish the prepared
                    // decoder signal now so Profile 7 -> 8.1 is reported as applied, not merely planned.
                    updateVideoColorFormat(currentVideoFormat)
                    installSourceListener(activePlayer)
                    activePlayer.stop()
                    activePlayer.clearMediaItems()
                    activePlayer.setSource(currentSourceSpec!!)
                    activePlayer.prepare()
                    if (resumePositionMs > 0) activePlayer.seekTo(resumePositionMs)
                    activePlayer.playWhenReady = resumePlayback
                    _isLoading = true
                } catch (error: Exception) {
                    androidVideoLogger.e { "Failed to install converted Dolby Vision source: ${error.message}" }
                    invalidateSourceCallbacks(exoPlayer ?: activePlayer)
                    runCatching { exoPlayer?.stop() }
                    runCatching { exoPlayer?.clearMediaItems() }
                    runCatching { androidSource.close() }
                    currentSourceSpec = null
                    originalPipelineColorInfo = null
                    _isPlaying = false
                    _isLoading = false
                    _hasMedia = false
                    setError(
                        VideoPlayerError.ColorPipelineError(
                            preparationFailureReason,
                            "Failed to install the prepared Media3 source: " +
                                (error.message ?: error::class.simpleName.orEmpty()),
                        ),
                    )
                }
            }
        }
    }

    private fun rejectPreparedAndroidSource(
        expectedGeneration: Long,
        rejection: VideoPipelineSourcePreparation.Rejected,
        automaticConversion: Boolean,
        resumePlayback: Boolean,
    ) {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                if (isPlayerReleased || expectedGeneration != sourceGeneration) return@synchronized
                if (automaticConversion) {
                    allowAutomaticDolbyVisionConversion = false
                    updateVideoColorFormat(currentVideoFormat)
                    val fallbackPlan = colorPipelineController.currentPlan
                    if (fallbackPlan != null && fallbackPlan.route != ColorPipelineRoute.UNSUPPORTED) {
                        exoPlayer?.playWhenReady = resumePlayback
                        _isLoading = exoPlayer?.playbackState == Player.STATE_BUFFERING
                        androidVideoLogger.d {
                            "Automatic Profile 7 to 8.1 conversion was unavailable; " +
                                "continuing with ${fallbackPlan.route}: ${rejection.detail}"
                        }
                        return@synchronized
                    }
                }
                exoPlayer?.stop()
                _isPlaying = false
                _isLoading = false
                setError(VideoPlayerError.ColorPipelineError(rejection.reason, rejection.detail))
            }
        }
    }

    private fun refreshColorPipelineOutput() {
        val platformRuntimeFallbackReason =
            when {
                requiredHdrVerificationFailureDetail != null ->
                    ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
                controlledColorRendererError != null || controlledHdrRendererFallbackDetail != null ->
                    ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED
                nativeSurfaceDataSpaceFailureDetail != null ->
                    ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
                else -> null
            }
        val platformRuntimeDetail =
            requiredHdrVerificationFailureDetail
                ?: controlledColorRendererError
                ?: controlledHdrRendererFallbackDetail
                ?: nativeSurfaceDataSpaceFailureDetail
        val preliminaryPlan =
            colorPipelineController.updateOutput(
                displayCapabilities = displayColorCapabilities,
                rendererCapabilities = rendererColorCapabilities,
                surfaceKind = activeColorSurface,
                nativeSurfaceAvailable = activeColorSurfaceIsNative,
                isProjection = projection.requiresProjectionRenderer,
                verification = ColorPipelineVerification.NONE,
                platformRuntimeFallbackReason = platformRuntimeFallbackReason,
                platformRuntimeDetail = platformRuntimeDetail,
            )
        val verification = androidColorPipelineVerification(preliminaryPlan)
        val plan =
            if (verification == ColorPipelineVerification.NONE) {
                preliminaryPlan
            } else {
                colorPipelineController.updateOutput(
                    displayCapabilities = displayColorCapabilities,
                    rendererCapabilities = rendererColorCapabilities,
                    surfaceKind = activeColorSurface,
                    nativeSurfaceAvailable = activeColorSurfaceIsNative,
                    isProjection = projection.requiresProjectionRenderer,
                    verification = verification,
                    platformRuntimeFallbackReason = platformRuntimeFallbackReason,
                    platformRuntimeDetail = platformRuntimeDetail,
                )
            }
        enforceRequiredColorPipeline(plan)
    }

    private fun androidColorPipelineVerification(plan: VideoColorPipelinePlan?): ColorPipelineVerification {
        if (currentVideoDecoderName == null || plan == null || plan.route == ColorPipelineRoute.UNSUPPORTED) {
            return ColorPipelineVerification.NONE
        }
        if (
            activeColorSurface == VideoSurfaceKind.CONTROLLED_GPU_SURFACE &&
            controlledColorRendererConfigured &&
            controlledColorRendererOutputDynamicRange == plan.outputDynamicRange
        ) {
            return ColorPipelineVerification.RENDERER_CONFIGURED
        }
        if (!activeColorSurfaceIsNative || !hasRenderedFirstVideoFrame) return ColorPipelineVerification.NONE
        if (
            plan.route == ColorPipelineRoute.SYSTEM_NATIVE_SURFACE &&
            plan.outputDynamicRange == nativeSurfaceConfiguredOutputDynamicRange &&
            plan.outputDynamicRange.androidNativeSurfaceDataSpaceOrNull() != null
        ) {
            return ColorPipelineVerification.RENDERER_CONFIGURED
        }
        if (
            plan.route == ColorPipelineRoute.SOURCE_BRIDGE_SDR &&
            currentSourceSpec?.preparedPipelineSource?.outputColorInfo?.dynamicRange == VideoDynamicRange.SDR
        ) {
            return ColorPipelineVerification.RENDERER_CONFIGURED
        }
        if (plan.outputDynamicRange == VideoDynamicRange.SDR && !colorPipelineStatus.value.source.isHdr) {
            return ColorPipelineVerification.RENDERER_CONFIGURED
        }
        systemReportedNativeHdrOutput.observe(
            route = plan.route,
            outputDynamicRange = plan.outputDynamicRange,
            displayReportsActiveHdr = activeDisplayReportsHdrComposition(),
        )
        return if (systemReportedNativeHdrOutput.confirms(plan.outputDynamicRange)) {
            ColorPipelineVerification.SYSTEM_REPORTED
        } else {
            ColorPipelineVerification.INFERRED
        }
    }

    private fun activeDisplayReportsHdrComposition(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val display = activeColorDisplay ?: return false
        return runCatching {
            display.isHdrSdrRatioAvailable && display.hdrSdrRatio >= MIN_CONFIRMED_HDR_SDR_RATIO
        }.getOrDefault(false)
    }

    private fun VideoDynamicRange.isHdrOutput(): Boolean =
        this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

    private fun enforceRequiredColorPipeline(plan: VideoColorPipelinePlan?) {
        if (plan == null || activeColorSurface == VideoSurfaceKind.UNKNOWN || currentVideoDecoderName == null) return
        controlledColorRendererError?.let { message ->
            requiredHdrVerificationJob?.cancel()
            requiredHdrVerificationJob = null
            val error =
                VideoPlayerError.ColorPipelineError(
                    reason = ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED,
                    message = message,
                )
            stopForColorPipelineError(error)
            return
        }
        val colorError = colorPipelineController.pipelineErrorOrNull()
        if (colorError != null) {
            // Decoder discovery may precede the first input Format. SOURCE_COLOR_UNKNOWN is only
            // a probing state at that point, not evidence that REQUIRE_HDR cannot be satisfied.
            if (
                colorError.reason == ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN &&
                currentVideoFormat == null
            ) {
                return
            }
            requiredHdrVerificationJob?.cancel()
            requiredHdrVerificationJob = null
            if (
                activeColorSurface != VideoSurfaceKind.CONTROLLED_GPU_SURFACE &&
                shouldUseControlledColorFallback()
            ) {
                return
            }
            stopForColorPipelineError(colorError)
        } else if (requiresPendingHdrVerification()) {
            scheduleRequiredHdrVerificationTimeout()
        } else if (_error is VideoPlayerError.ColorPipelineError) {
            requiredHdrVerificationJob?.cancel()
            requiredHdrVerificationJob = null
            clearErrorState()
        } else {
            requiredHdrVerificationJob?.cancel()
            requiredHdrVerificationJob = null
        }
    }

    private fun stopForColorPipelineError(error: VideoPlayerError.ColorPipelineError) {
        if (_error != error) setError(error)
        exoPlayer?.let { player ->
            player.playWhenReady = false
            player.stop()
        }
        _isPlaying = false
        _isLoading = false
        _isSeeking = false
        abandonDuckAudioFocus()
    }

    private fun requiresPendingHdrVerification(): Boolean {
        val status = colorPipelineStatus.value
        val outputProduced =
            (activeColorSurfaceIsNative && hasRenderedFirstVideoFrame) ||
                (activeColorSurface == VideoSurfaceKind.CONTROLLED_GPU_SURFACE && controlledColorRendererConfigured)
        return playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
            status.source.isHdr &&
            status.plannedOutputDynamicRange.isHdrOutput() &&
            status.outputDynamicRange == VideoDynamicRange.UNKNOWN &&
            outputProduced
    }

    private fun scheduleRequiredHdrVerificationTimeout() {
        if (requiredHdrVerificationJob?.isActive == true) return
        requiredHdrVerificationJob =
            coroutineScope.launch {
                delay(REQUIRED_HDR_VERIFICATION_TIMEOUT_MS)
                requiredHdrVerificationJob = null
                if (!isPlayerReleased && requiresPendingHdrVerification()) {
                    requiredHdrVerificationFailureDetail =
                        "Android did not report an active HDR composition for REQUIRE_HDR " +
                        "after the first frame."
                    refreshColorPipelineOutput()
                }
            }
    }

    private fun scheduleControlledHdrStartupVerification(requestedOutputDynamicRange: VideoDynamicRange) {
        cancelControlledHdrStartupVerification()
        val sourceDynamicRange = colorPipelineStatus.value.source.dynamicRange
        val mappedOutputDynamicRange = rendererColorCapabilities.controlledOutputFor(sourceDynamicRange)
        if (
            sourceDynamicRange != VideoDynamicRange.HLG ||
            requestedOutputDynamicRange != mappedOutputDynamicRange ||
            requestedOutputDynamicRange == sourceDynamicRange ||
            !requestedOutputDynamicRange.isHdrOutput()
        ) {
            return
        }
        val initialPositionMs = exoPlayer?.currentPosition ?: return
        controlledHdrStartupJob =
            coroutineScope.launch {
                while (isControlledHdrStartupPending(requestedOutputDynamicRange, sourceDynamicRange)) {
                    delay(CONTROLLED_HDR_STARTUP_TIMEOUT_MS)
                    val player = exoPlayer
                    val playbackAdvanced = player?.currentPosition?.minus(initialPositionMs) ?: 0L
                    if (
                        isControlledHdrStartupPending(requestedOutputDynamicRange, sourceDynamicRange) &&
                        player?.playWhenReady == true &&
                        (player.isPlaying || playbackAdvanced >= CONTROLLED_HDR_MIN_PLAYBACK_PROGRESS_MS)
                    ) {
                        controlledHdrStartupJob = null
                        updateControlledHdrRendererUnavailable(
                            outputDynamicRange = requestedOutputDynamicRange,
                            message =
                                "Android controlled HLG-to-PQ graph produced no renderer-verified frame " +
                                    "within ${CONTROLLED_HDR_STARTUP_TIMEOUT_MS} ms while playback advanced.",
                        )
                        return@launch
                    }
                }
                controlledHdrStartupJob = null
            }
    }

    private fun isControlledHdrStartupPending(
        requestedOutputDynamicRange: VideoDynamicRange,
        sourceDynamicRange: VideoDynamicRange,
    ): Boolean =
        !isPlayerReleased &&
            currentSourceSpec != null &&
            !controlledColorRendererConfigured &&
            controlledRendererRequestedOutputDynamicRange == requestedOutputDynamicRange &&
            colorPipelineStatus.value.source.dynamicRange == sourceDynamicRange

    private fun cancelControlledHdrStartupVerification() {
        controlledHdrStartupJob?.cancel()
        controlledHdrStartupJob = null
    }

    private fun nextMediaSessionId(): Long = playbackEventDispatcher.nextMediaSessionId()

    private fun emitPlaybackEvent(factory: (Long, Long) -> PlaybackEvent) {
        playbackEventDispatcher.emit(factory)
    }

    private fun emitPlaybackEventForSession(
        sessionId: Long,
        factory: (Long, Long) -> PlaybackEvent,
    ) {
        playbackEventDispatcher.emitForSession(sessionId, factory)
    }

    private fun setError(error: VideoPlayerError) {
        _error = error
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.Error(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                error = error,
            )
        }
    }

    private fun clearErrorState() {
        _error = null
    }

    private fun emitSourceReleasedForSession(sessionId: Long) {
        if (sessionId == 0L) return
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.SourceReleased(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
            )
        }
    }

    private fun checkNotDisposed() {
        check(!isPlayerReleased) { "VideoPlayerState has been disposed" }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> runOnPlayerThreadBlocking(block: () -> T): T {
        val looper = exoPlayer?.applicationLooper ?: Looper.getMainLooper()
        if (Looper.myLooper() == looper) return block()

        val completed = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        val handler = Handler(looper)
        val operation =
            Runnable {
                try {
                    value = block()
                } catch (throwable: Throwable) {
                    failure = throwable
                } finally {
                    completed.countDown()
                }
            }
        check(handler.post(operation)) { "Unable to dispatch operation to the Android player thread." }
        if (!completed.await(PLAYER_THREAD_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            handler.removeCallbacks(operation)
            error("Timed out dispatching operation to the Android player thread.")
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun isOnPlayerThread(): Boolean {
        val looper = exoPlayer?.applicationLooper ?: Looper.getMainLooper()
        return Looper.myLooper() == looper
    }

    private fun invalidateScreenAutoResume() {
        playbackIntentGeneration += 1
        screenLockResumeTicket = null
        screenResumeJob?.cancel()
        screenResumeJob = null
    }

    private fun installSourceListener(player: ExoPlayer): Long {
        playerListener?.let(player::removeListener)
        sourceGeneration += 1
        val generation = sourceGeneration
        androidSubtitleBackend?.beginSource(generation)
        createPlayerListener(generation).also { listener ->
            playerListener = listener
            player.addListener(listener)
        }
        return generation
    }

    private fun invalidateSourceCallbacks(player: ExoPlayer?) {
        sourceGeneration += 1
        playerListener?.let { listener ->
            runCatching { player?.removeListener(listener) }
        }
        playerListener = null
    }

    private fun isCurrentSourceCallback(generation: Long): Boolean =
        !isPlayerReleased && generation == sourceGeneration && currentSourceSpec != null

    private fun requestDuckAudioFocus(): Boolean {
        if (audioFocusStrategy != AndroidAudioFocusStrategy.DUCK_OTHERS || hasDuckAudioFocus) return true
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request =
                    duckAudioFocusRequest ?: AudioFocusRequest
                        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            android.media.AudioAttributes
                                .Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build(),
                        ).setOnAudioFocusChangeListener(duckAudioFocusChangeListener)
                        .build()
                        .also { duckAudioFocusRequest = it }
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    duckAudioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        hasDuckAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasDuckAudioFocus) {
            isDuckedByAnotherApp = false
            applyPlayerVolume()
        }
        return hasDuckAudioFocus
    }

    private fun abandonDuckAudioFocus() {
        if (audioFocusStrategy != AndroidAudioFocusStrategy.DUCK_OTHERS) return
        if (hasDuckAudioFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                duckAudioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(duckAudioFocusChangeListener)
            }
        }
        hasDuckAudioFocus = false
        isDuckedByAnotherApp = false
        applyPlayerVolume()
    }

    private fun applyPlayerVolume() {
        val focusMultiplier = if (isDuckedByAnotherApp) 0.2f else 1f
        exoPlayer?.volume = _volume * focusMultiplier
    }

    private fun updateNativeSubtitleVisibility() {
        playerView?.subtitleView?.visibility =
            if (
                subtitlesEnabled &&
                currentSubtitleTrack?.isEmbedded == true &&
                !usesAndroidSubtitleBackend(currentSubtitleTrack)
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun usesAndroidSubtitleBackend(track: SubtitleTrack?): Boolean =
        track != null && androidSubtitleBackend?.supports(track) == true

    private fun loadExternalAssTrack(track: SubtitleTrack) {
        val backend = androidSubtitleBackend ?: return
        val expectedSourceGeneration = sourceGeneration
        externalAssLoadJob?.cancel()
        backend.deactivate()
        externalAssLoadJob =
            coroutineScope.launch {
                runCatching { backend.loadExternalSubtitle(track.src) }
                    .onSuccess { script ->
                        if (isPlayerReleased || expectedSourceGeneration != sourceGeneration) return@onSuccess
                        if (!subtitlesEnabled || currentSubtitleTrack != track) return@onSuccess
                        if (track.isExternal) backend.activateExternal(script)
                    }.onFailure { throwable ->
                        if (throwable !is CancellationException) {
                            androidVideoLogger.e {
                                "Failed to load external ASS/SSA '${track.label}': " +
                                    (throwable.message ?: throwable::class.simpleName)
                            }
                        }
                    }
            }
    }

    // Volume control
    private var _volume by mutableFloatStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            checkNotDisposed()
            _volume = value.coerceIn(0f, 1f)
            if (!isPlayerReleased) {
                runOnPlayerThreadBlocking {
                    if (!isPlayerReleased) applyPlayerVolume()
                }
            }
        }

    // Slider position
    private var _sliderPos by mutableFloatStateOf(0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            checkNotDisposed()
            _sliderPos = value.coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
        }

    // User interaction states
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            checkNotDisposed()
            _userDragging = value
        }

    private var playbackEndedCallback: (() -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)?
        get() = playbackEndedCallback
        set(value) {
            checkNotDisposed()
            playbackEndedCallback = value
        }
    private var restartCallback: (() -> Unit)? = null
    override var onRestart: (() -> Unit)?
        get() = restartCallback
        set(value) {
            checkNotDisposed()
            restartCallback = value
        }

    // Loop control
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            checkNotDisposed()
            _loop = value
            if (!isPlayerReleased) {
                runOnPlayerThreadBlocking {
                    if (!isPlayerReleased) {
                        exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    }
                }
            }
        }

    // Playback speed control
    private var _playbackSpeed by mutableFloatStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            checkNotDisposed()
            _playbackSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (!isPlayerReleased) {
                runOnPlayerThreadBlocking {
                    if (!isPlayerReleased) {
                        exoPlayer?.playbackParameters = PlaybackParameters(_playbackSpeed)
                    }
                }
            }
        }

    // Aspect ratio
    private var _aspectRatio by mutableFloatStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            checkNotDisposed()
            if (_isFullscreen == value) return
            _isFullscreen = value
            // A fullscreen transition may reparent the SurfaceView onto another display. Do not
            // retain first-frame verification from the previous surface while that happens.
            hasRenderedFirstVideoFrame = false
            refreshColorPipelineOutput()
        }

    private var _isPipFullScreen by mutableStateOf(false)
    var isPipFullScreen: Boolean
        get() = _isPipFullScreen
        set(value) {
            checkNotDisposed()
            _isPipFullScreen = value
        }

    // Time tracking
    private var _currentTime by mutableStateOf(Duration.ZERO)
    private var _duration by mutableStateOf(Duration.ZERO)
    private var _chapters by mutableStateOf(emptyList<MediaChapter>())
    private val discoveredChapterRows = mutableListOf<RawMediaChapter>()
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Duration get() = _currentTime
    override val preciseCurrentTime: Duration
        get() =
            if (!isPlayerReleased) {
                exoPlayer?.currentPosition?.millisecondsAsDuration() ?: _currentTime
            } else {
                _currentTime
            }
    override val duration: Duration get() = _duration
    override val chapters: List<MediaChapter> get() = _chapters
    override val bufferedRanges: List<BufferedRange>
        get() {
            val player = exoPlayer ?: return emptyList()
            val bufferedPosition = player.bufferedPosition
            if (bufferedPosition <= 0L) return emptyList()
            return listOf(BufferedRange(Duration.ZERO, bufferedPosition.millisecondsAsDuration()))
        }
    override val bufferedPercent: Float
        get() = exoPlayer?.bufferedPercentage?.toFloat()?.coerceIn(0f, PERCENT_SCALE) ?: 0f

    override val isPipSupported: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ctx = attachedActivity() ?: context
                return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            }
            return false
        }

    private var _isPipEnabled by mutableStateOf(false)
    override var isPipEnabled: Boolean
        get() = _isPipEnabled
        set(value) {
            checkNotDisposed()
            _isPipEnabled = value
        }
    private var _isPipActive by mutableStateOf(false)
    override var isPipActive: Boolean
        get() = _isPipActive
        set(value) {
            checkNotDisposed()
            _isPipActive = value
        }

    internal fun updatePipActiveFromPlatform(value: Boolean) {
        if (!isPlayerReleased) _isPipActive = value
    }

    init {
        runOnPlayerThreadBlocking {
            initializeAndroidPlayerResources(
                initializePlayer = ::initializePlayer,
                registerReceiver = ::registerScreenLockReceiver,
                cleanup = ::cleanupFailedInitialization,
            )
            registerActiveDisplayListener()
        }
    }

    private fun registerActiveDisplayListener() {
        if (displayListenerRegistered) return
        displayManager.registerDisplayListener(activeDisplayListener, Handler(Looper.getMainLooper()))
        displayListenerRegistered = true
    }

    private fun unregisterActiveDisplayListener() {
        if (displayListenerRegistered) {
            runCatching { displayManager.unregisterDisplayListener(activeDisplayListener) }
            displayListenerRegistered = false
        }
        unregisterHdrRatioListener()
    }

    private fun updateHdrRatioListener(display: android.view.Display?) {
        if (hdrRatioListenerDisplay === display) return
        unregisterHdrRatioListener()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || display == null) return
        val registered =
            runCatching {
                if (!display.isHdrSdrRatioAvailable) return@runCatching false
                display.registerHdrSdrRatioChangedListener(context.mainExecutor, hdrRatioChangedListener)
                true
            }.getOrDefault(false)
        if (registered) hdrRatioListenerDisplay = display
    }

    private fun unregisterHdrRatioListener() {
        val display = hdrRatioListenerDisplay ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { display.unregisterHdrSdrRatioChangedListener(hdrRatioChangedListener) }
        }
        hdrRatioListenerDisplay = null
    }

    private fun cleanupFailedInitialization() {
        synchronized(playerInitializationLock) {
            isPlayerReleased = true
            sourceGeneration += 1L
            currentSourceSpec = null
            screenResumeJob?.cancel()
            screenResumeJob = null
            screenLockResumeTicket = null
            stopPositionUpdates()
            abandonDuckAudioFocus()
            unregisterScreenLockReceiver()
            unregisterActiveDisplayListener()

            val player = exoPlayer
            val listener = playerListener
            playerListener = null
            playerView?.player = null
            playerView = null
            projectionVideoSurfaceView = null
            if (player != null) {
                listener?.let { currentListener -> runCatching { player.removeListener(currentListener) } }
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            exoPlayer = null
        }
        coroutineScope.cancel()
        runCatching { cacheLease?.close() }
        cacheLease = null
        androidSubtitleBackend?.release()
    }

    private fun registerScreenLockReceiver() {
        unregisterScreenLockReceiver()

        screenLockReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> handleScreenTurnedOff()
                        Intent.ACTION_SCREEN_ON -> handleScreenTurnedOn()
                    }
                }
            }

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        context.registerReceiver(screenLockReceiver, filter)
        androidVideoLogger.d { "Screen lock receiver registered" }
    }

    internal fun handleScreenTurnedOff() {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                if (isPlayerReleased || exoPlayer == null) return@synchronized
                screenResumeJob?.cancel()
                screenResumeJob = null
                screenLockResumeTicket =
                    if (_isPlaying && _hasMedia) {
                        ScreenLockResumeTicket(sourceGeneration, playbackIntentGeneration)
                    } else {
                        null
                    }
                if (screenLockResumeTicket != null) {
                    androidVideoLogger.d { "Pausing playback due to screen lock" }
                    exoPlayer?.pause()
                    abandonDuckAudioFocus()
                }
            }
        }
    }

    internal fun handleScreenTurnedOn() {
        runOnPlayerThreadBlocking {
            val ticket =
                synchronized(playerInitializationLock) {
                    if (isPlayerReleased) return@synchronized null
                    screenLockResumeTicket.also { screenLockResumeTicket = null }
                } ?: return@runOnPlayerThreadBlocking
            screenResumeJob?.cancel()
            screenResumeJob =
                coroutineScope.launch {
                    delay(200.milliseconds)
                    synchronized(playerInitializationLock) {
                        if (
                            ticket.isCurrent(
                                sourceGeneration = sourceGeneration,
                                playbackIntentGeneration = playbackIntentGeneration,
                                hasMedia = _hasMedia,
                                isDisposed = isPlayerReleased,
                            )
                        ) {
                            androidVideoLogger.d { "Resuming playback after screen unlock" }
                            if (requestDuckAudioFocus()) exoPlayer?.play()
                        }
                    }
                }
        }
    }

    private fun unregisterScreenLockReceiver() {
        screenLockReceiver?.let {
            try {
                context.unregisterReceiver(it)
                androidVideoLogger.d { "Screen lock receiver unregistered" }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error unregistering screen lock receiver: ${e.message}" }
            }
            screenLockReceiver = null
        }
    }

    private fun initializePlayer() {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            val rendererPlayerGeneration = ++playerInstanceGeneration

            val audioSink =
                DefaultAudioSink
                    .Builder(context)
                    .build()

            val renderersFactory =
                AndroidColorManagedRenderersFactory(
                    context = context,
                    audioSink = audioSink,
                    subtitleBackend = androidSubtitleBackend,
                    shouldToneMapToSdr = { controlledRendererToneMapToSdr },
                    shouldDecoderToneMapHlgToSdr = { decoderToneMapHlgToSdr },
                    convertHlgOutputToPq = AndroidHdrProjectionRuntimeProbe.get().supportsPqOutput,
                    onVideoGraphError = onVideoGraphError@{ outputDynamicRange, message ->
                        if (rendererPlayerGeneration != playerInstanceGeneration) return@onVideoGraphError
                        controlledVideoGraphFailurePending = true
                        coroutineScope.launch {
                            if (isPlayerReleased || currentSourceSpec == null) return@launch
                            if (outputDynamicRange.isHdrOutput()) {
                                updateControlledHdrRendererUnavailable(outputDynamicRange, message)
                            } else {
                                updateControlledColorRendererFailed(message)
                            }
                        }
                    },
                    onHdrStaticMetadata = ::handleObservedHdrStaticMetadata,
                    onHdr10PlusMetadata = { presentationTimeUs, payload ->
                        handleObservedHdr10PlusMetadata(presentationTimeUs, payload)
                    },
                ).apply {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    // Enable decoder fallback for better stability
                    setEnableDecoderFallback(true)

                    // On problematic devices, use more conservative settings
                    if (shouldUseConservativeAndroidCodecHandling()) {
                        // Cannot disable async queueing as the method does not exist
                        // But we can use the default MediaCodecSelector
                        setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                    }
                }

            val manageFocus = audioFocusStrategy == AndroidAudioFocusStrategy.EXCLUSIVE
            val handleBecomingNoisy = audioFocusStrategy != AndroidAudioFocusStrategy.MIX
            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

            val playerBuilder =
                ExoPlayer
                    .Builder(context)
                    .setRenderersFactory(renderersFactory)
                    .setHandleAudioBecomingNoisy(handleBecomingNoisy)
                    .setWakeMode(if (handleBecomingNoisy) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
                    .setAudioAttributes(audioAttributes, manageFocus)
                    .setPauseAtEndOfMediaItems(false)
                    .setReleaseTimeoutMs(2000) // Increase the release timeout

            val cache =
                if (cacheConfig.enabled) {
                    val lease =
                        cacheLease ?: VideoCache.acquire(context, cacheConfig.maxCacheSizeBytes).also {
                            cacheLease = it
                        }
                    lease.cache
                } else {
                    null
                }
            val defaultDataSourceFactory = buildAndroidDataSourceFactory(context, cache)
            playerBuilder.setMediaSourceFactory(buildAndroidMediaSourceFactory(defaultDataSourceFactory))

            exoPlayer =
                playerBuilder
                    .build()
                    .apply {
                        addAnalyticsListener(colorAnalyticsListener)
                        setVideoFrameMetadataListener(colorFrameMetadataListener)
                        if (controlledVideoEffectsActive) {
                            setVideoEffects(listOf(androidProjectionEffectController.effect))
                        }
                        volume = _volume
                        projectionVideoSurfaceView?.let(::setVideoSurfaceView)
                        playerView?.let { view ->
                            view.player = this
                            view.subtitleView?.setStyle(CaptionStyleCompat.DEFAULT)
                        }
                    }
        }
    }

    private fun createPlayerListener(generation: Long) =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrentSourceCallback(generation)) return

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (sourceLoadedSessionId == mediaSessionId && !wasStalled) {
                            wasStalled = true
                            emitPlaybackEvent { sessionId, sampledAtMs ->
                                PlaybackEvent.Stalled(
                                    mediaSessionId = sessionId,
                                    sampledAtMs = sampledAtMs,
                                )
                            }
                        }
                        _isLoading = true
                    }

                    Player.STATE_READY -> {
                        _isLoading = false
                        _isSeeking = false
                        exoPlayer?.let { player ->
                            if (!isPlayerReleased) {
                                _duration = player.duration.millisecondsAsDuration()
                                _isPlaying = player.isPlaying
                                if (player.isPlaying) startPositionUpdates()
                                extractFormatMetadata(player)
                                syncMediaChapters(player)
                                if (sourceLoadedSessionId != mediaSessionId && mediaSessionId != 0L && _hasMedia) {
                                    sourceLoadedSessionId = mediaSessionId
                                    emitPlaybackEvent { sessionId, sampledAtMs ->
                                        PlaybackEvent.SourceLoaded(
                                            mediaSessionId = sessionId,
                                            sampledAtMs = sampledAtMs,
                                            duration = _duration,
                                        )
                                    }
                                }
                                if (wasStalled) {
                                    wasStalled = false
                                    emitPlaybackEvent { sessionId, sampledAtMs ->
                                        PlaybackEvent.Recovered(
                                            mediaSessionId = sessionId,
                                            sampledAtMs = sampledAtMs,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Player.STATE_ENDED -> {
                        _isLoading = false
                        _isSeeking = false
                        stopPositionUpdates()
                        abandonDuckAudioFocus()
                        _isPlaying = false
                        emitPlaybackEvent { sessionId, sampledAtMs ->
                            PlaybackEvent.PlaybackEnded(
                                mediaSessionId = sessionId,
                                sampledAtMs = sampledAtMs,
                            )
                        }
                        onPlaybackEnded?.invoke()
                    }

                    Player.STATE_IDLE -> {
                        _isLoading = false
                        _isSeeking = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (isCurrentSourceCallback(generation)) {
                    _isPlaying = playing
                    if (playing) {
                        startPositionUpdates()
                    } else {
                        stopPositionUpdates()
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (!isCurrentSourceCallback(generation)) return
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && _loop) {
                    emitPlaybackEvent { sessionId, sampledAtMs ->
                        PlaybackEvent.PlaybackRestarted(
                            mediaSessionId = sessionId,
                            sampledAtMs = sampledAtMs,
                        )
                    }
                    onRestart?.invoke()
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    _isSeeking = false
                    emitPlaybackEvent { sessionId, sampledAtMs ->
                        PlaybackEvent.SeekCompleted(
                            mediaSessionId = sessionId,
                            sampledAtMs = sampledAtMs,
                            position = newPosition.positionMs.millisecondsAsDuration(),
                        )
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!isCurrentSourceCallback(generation)) return
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val pixelAspectRatio =
                        videoSize.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
                    val displayWidth =
                        (videoSize.width * pixelAspectRatio)
                            .coerceIn(1f, Int.MAX_VALUE.toFloat())
                            .toInt()
                    _aspectRatio = displayWidth.toFloat() / videoSize.height.toFloat()
                    _metadata.width = videoSize.width
                    _metadata.height = videoSize.height
                    androidSubtitleBackend?.updateVideoSize(displayWidth, videoSize.height)
                    updateAutoDetectedProjection(
                        exoPlayer
                            ?.currentMediaItem
                            ?.localConfiguration
                            ?.uri
                            ?.toString()
                            .orEmpty(),
                    )
                    updateRenderingInfo()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (isCurrentSourceCallback(generation)) {
                    exoPlayer?.let { player ->
                        applyDynamicRangeTrackSelection(player, tracks)
                        maybePrepareProfile7BeforeDecoder(tracks)
                        syncAvailableMediaTracks(player)
                        syncMediaChapters(player)
                    }
                }
            }

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                if (isCurrentSourceCallback(generation)) {
                    exoPlayer?.let(::syncMediaChapters)
                }
            }

            override fun onMetadata(metadata: Metadata) {
                if (!isCurrentSourceCallback(generation)) return
                exoPlayer?.let { player ->
                    mergeMediaChapterRows(player.media3ChapterRows(metadata))
                    publishMediaChapters()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentSourceCallback(generation)) return
                if (controlledVideoGraphFailurePending) {
                    controlledVideoGraphFailurePending = false
                    androidVideoLogger.d { "Ignoring the superseded ExoPlayer graph error during color fallback." }
                    return
                }
                val resumePlaybackAfterRecovery = exoPlayer?.playWhenReady == true
                androidVideoLogger.e { "Player error occurred: ${error.errorCode} - ${error.message}" }

                // Create a detailed error report
                val errorDetails =
                    mapOf(
                        "error_code" to error.errorCode.toString(),
                        "error_message" to (error.message ?: "Unknown"),
                        "device" to android.os.Build.DEVICE,
                        "model" to android.os.Build.MODEL,
                        "manufacturer" to android.os.Build.MANUFACTURER,
                        "android_version" to
                            android.os.Build.VERSION.SDK_INT
                                .toString(),
                        "codec_info" to error.cause?.message,
                    )

                // Log the error details (you can send this to your crash reporting service)
                androidVideoLogger.e { "Detailed error info: $errorDetails" }

                // Codec-specific error handling
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    -> {
                        setError(VideoPlayerError.CodecError("Decoder error: ${error.message}"))
                        // Attempt recovery for codec errors
                        attemptPlayerRecovery(resumePlayback = resumePlaybackAfterRecovery)
                    }
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    -> {
                        setError(VideoPlayerError.NetworkError("Network error: ${error.message}"))
                    }
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    -> {
                        setError(VideoPlayerError.SourceError("Invalid media source: ${error.message}"))
                    }
                    else -> {
                        setError(VideoPlayerError.UnknownError("Playback error: ${error.message}"))
                    }
                }
                _isPlaying = false
                _isLoading = false
                _isSeeking = false
                abandonDuckAudioFocus()
            }
        }

    internal fun attemptPlayerRecovery(
        delayBeforeRecovery: Duration = 100.milliseconds,
        resumePlayback: Boolean = exoPlayer?.playWhenReady == true,
    ): Job =
        coroutineScope.launch {
            val expectedGeneration = sourceGeneration
            val expectedPlaybackIntentGeneration = playbackIntentGeneration
            delay(delayBeforeRecovery)

            synchronized(playerInitializationLock) {
                if (
                    !isPlayerReleased &&
                    expectedGeneration == sourceGeneration &&
                    expectedPlaybackIntentGeneration == playbackIntentGeneration
                ) {
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val sourceSpec = currentSourceSpec ?: return@let

                        try {
                            invalidateSourceCallbacks(player)
                            playerView?.player = null
                            runCatching { player.stop() }
                            try {
                                player.release()
                            } finally {
                                exoPlayer = null
                            }
                            initializePlayer()

                            exoPlayer?.apply {
                                installSourceListener(this)
                                setSource(sourceSpec)
                                prepare()
                                seekTo(currentPosition)
                                if (resumePlayback && requestDuckAudioFocus()) {
                                    play()
                                } else {
                                    pause()
                                }
                            }
                        } catch (e: Exception) {
                            androidVideoLogger.e { "Error during player recovery: ${e.message}" }
                            setError(VideoPlayerError.UnknownError("Recovery failed: ${e.message}"))
                        }
                    }
                }
            }
        }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        updateJob =
            coroutineScope.launch {
                while (isActive) {
                    exoPlayer?.let { player ->
                        if (player.playbackState == Player.STATE_READY && !isPlayerReleased) {
                            val currentPositionMs = player.currentPosition
                            _currentTime = currentPositionMs.millisecondsAsDuration()
                            playbackClockSnapshot =
                                AndroidPlaybackClockSnapshot(
                                    positionUs = currentPositionMs * 1_000L,
                                    sampledAtNs = System.nanoTime(),
                                    playbackSpeed = player.playbackParameters.speed,
                                )
                            if (!userDragging && _duration > Duration.ZERO) {
                                _sliderPos =
                                    (
                                        _currentTime.toSecondsDouble() /
                                            _duration.toSecondsDouble() *
                                            VideoPlayerState.SLIDER_SCALE
                                    ).toFloat()
                            }
                        }
                    }
                    delay(16.milliseconds) // ~60fps update rate
                }
            }
    }

    private fun stopPositionUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                invalidateScreenAutoResume()
                resetProjectionForSource(uri)
                openFromMediaItem(MediaItem.Builder().setUri(uri).build(), initializePlayerState, requestHeaders)
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                invalidateScreenAutoResume()
                val videoUri: Uri =
                    when (val androidFile = file.androidFile) {
                        is AndroidFile.UriWrapper -> androidFile.uri
                        is AndroidFile.FileWrapper -> Uri.fromFile(androidFile.file)
                    }
                val mediaItem = MediaItem.Builder().setUri(videoUri).build()
                resetProjectionForSource(videoUri.toString())
                openFromMediaItem(mediaItem, initializePlayerState)
            }
        }
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        checkNotDisposed()
        openUri(fileName.normalizedAssetUri(), initializePlayerState)
    }

    private fun openFromMediaItem(
        mediaItem: MediaItem,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        checkNotDisposed()
        exoPlayer?.let { player ->
            sourceConversionJob?.cancel()
            sourceConversionJob = null
            sourceConversionAttemptGeneration = null
            allowAutomaticDolbyVisionConversion = true
            originalPipelineColorInfo = null
            observedHdr10PlusInfo = null
            observedHdrStaticMetadata = null
            hdr10PlusMetadataReset?.invoke()
            val previousPreparedSource = currentSourceSpec?.preparedPipelineSource
            val previousSessionId = mediaSessionId
            val hadPreviousSource = currentSourceSpec != null
            val sessionId = nextMediaSessionId()
            val sourceUri =
                mediaItem
                    .localConfiguration
                    ?.uri
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: mediaItem.mediaId
            sourceLoadedSessionId = 0L
            wasStalled = false
            clearMediaChapters()
            if (hadPreviousSource) {
                emitSourceReleasedForSession(previousSessionId)
            }
            emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
                PlaybackEvent.SourcePreparing(
                    mediaSessionId = eventSessionId,
                    sampledAtMs = sampledAtMs,
                    uri = sourceUri,
                )
            }
            invalidateSourceCallbacks(player)
            externalAssLoadJob?.cancel()
            externalAssLoadJob = null
            androidSubtitleBackend?.deactivate()
            stopPositionUpdates()
            abandonDuckAudioFocus()
            player.stop()
            player.clearMediaItems()
            previousPreparedSource?.close()
            try {
                clearErrorState()
                resetStates(keepMedia = true)
                updateRenderingInfo()
                clearDynamicRangeTrackSelection(player)
                val sourceSpec = AndroidSourceSpec(mediaItem, requestHeaders.sanitizedRequestHeaders())
                currentSourceSpec = sourceSpec
                installSourceListener(player)
                _hasMedia = true

                // Extract metadata before preparing the player
                extractMediaItemMetadata(mediaItem)

                player.setSource(sourceSpec)
                player.prepare()
                currentSubtitleTrack
                    ?.takeIf { track -> track.isExternal && usesAndroidSubtitleBackend(track) }
                    ?.let(::loadExternalAssTrack)
                player.volume = volume
                player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                // Control the initial playback state
                if (initializePlayerState == InitialPlayerState.PLAY) {
                    if (requestDuckAudioFocus()) player.play()
                } else {
                    player.pause()
                    _isPlaying = false
                }
            } catch (e: Exception) {
                androidVideoLogger.d { "Error opening media: ${e.message}" }
                invalidateSourceCallbacks(player)
                currentSourceSpec = null
                runCatching { player.clearMediaItems() }
                _isPlaying = false
                _hasMedia = false
                setError(VideoPlayerError.SourceError("Failed to load media: ${e.message}"))
            }
        }
    }

    private fun ExoPlayer.setSource(sourceSpec: AndroidSourceSpec) {
        val mediaSource =
            (sourceSpec.preparedPipelineSource as? AndroidPreparedVideoPipelineSource)?.createMediaSource()
                ?: createMediaSource(sourceSpec.mediaItem, sourceSpec.requestHeaders)
        if (mediaSource == null) {
            setMediaItem(sourceSpec.mediaItem)
        } else {
            setMediaSource(mediaSource)
        }
    }

    /**
     * Creates the Android Media3 source used by [openUri] and [openFile].
     *
     * Override this in Android subclasses when a source needs a custom [MediaSource], for example a custom
     * [androidx.media3.datasource.DataSource.Factory]. Return `null` to let ExoPlayer handle [mediaItem] through
     * `setMediaItem`.
     */
    protected open fun createMediaSource(
        mediaItem: MediaItem,
        requestHeaders: Map<String, String>,
    ): MediaSource? {
        val localConfiguration = mediaItem.localConfiguration
        val isHls =
            localConfiguration != null &&
                Util.inferContentTypeForUriAndMimeType(
                    localConfiguration.uri,
                    localConfiguration.mimeType,
                ) == C.CONTENT_TYPE_HLS
        if (isHls) {
            val dataSourceFactory =
                buildAndroidDataSourceFactory(
                    context = context,
                    cache = cacheLease?.cache,
                    requestHeaders = requestHeaders,
                )
            val expectedMediaSessionId = mediaSessionId
            val chapterDiscovery =
                AndroidHlsChapterDiscovery(
                    dataSourceFactory = dataSourceFactory,
                    scope = coroutineScope,
                    onRows = { rows ->
                        if (
                            !isPlayerReleased &&
                            mediaSessionId == expectedMediaSessionId &&
                            currentSourceSpec != null
                        ) {
                            mergeMediaChapterRows(rows)
                            publishMediaChapters()
                        }
                    },
                )
            hlsChapterDiscovery?.cancel()
            hlsChapterDiscovery = chapterDiscovery
            return HlsMediaSource
                .Factory(dataSourceFactory)
                .setPlaylistParserFactory(
                    AndroidColorAwareHlsPlaylistParserFactory(
                        onMultivariantPlaylist = chapterDiscovery::observeMultivariantPlaylist,
                        onMediaPlaylist = chapterDiscovery::observeMediaPlaylist,
                    ),
                ).createMediaSource(mediaItem)
        }
        if (requestHeaders.isEmpty()) return null
        return buildAndroidMediaSourceFactory(
            buildAndroidDataSourceFactory(
                context = context,
                cache = cacheLease?.cache,
                requestHeaders = requestHeaders,
            ),
        ).createMediaSource(mediaItem)
    }

    private fun buildAndroidMediaSourceFactory(dataSourceFactory: DataSource.Factory): MediaSource.Factory =
        androidSubtitleBackend?.createMediaSourceFactory(dataSourceFactory)
            ?: DefaultMediaSourceFactory(dataSourceFactory)

    override fun play() {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                invalidateScreenAutoResume()
                exoPlayer?.let { player ->
                    if (player.mediaItemCount == 0) return@let
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    } else if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0)
                    }
                    if (requestDuckAudioFocus()) player.play()
                }
            }
        }
    }

    override fun restart() {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                invalidateScreenAutoResume()
                exoPlayer?.let { player ->
                    if (player.mediaItemCount == 0) return@let
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.seekTo(0)
                    if (requestDuckAudioFocus()) player.play()
                    emitPlaybackEvent { sessionId, sampledAtMs ->
                        PlaybackEvent.PlaybackRestarted(
                            mediaSessionId = sessionId,
                            sampledAtMs = sampledAtMs,
                        )
                    }
                }
            }
        }
    }

    override fun pause() {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                invalidateScreenAutoResume()
                exoPlayer?.pause()
                abandonDuckAudioFocus()
            }
        }
    }

    override fun stop() {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                invalidateScreenAutoResume()
                abandonDuckAudioFocus()
                exoPlayer?.let { player ->
                    player.stop()
                    player.seekTo(0)
                }
                androidSubtitleBackend?.hideTimeline()
                resetStates(keepMedia = true)
                wasStalled = false
            }
        }
    }

    override fun releaseSource() {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                val releasedSessionId = mediaSessionId
                val hadSource = currentSourceSpec != null
                invalidateScreenAutoResume()
                val player = exoPlayer
                invalidateSourceCallbacks(player)
                val preparedSource = currentSourceSpec?.preparedPipelineSource
                currentSourceSpec = null
                sourceConversionJob?.cancel()
                sourceConversionJob = null
                sourceConversionAttemptGeneration = null
                allowAutomaticDolbyVisionConversion = true
                originalPipelineColorInfo = null
                externalAssLoadJob?.cancel()
                externalAssLoadJob = null
                androidSubtitleBackend?.deactivate()
                stopPositionUpdates()
                abandonDuckAudioFocus()
                if (player != null) {
                    runCatching { player.stop() }
                        .onFailure { androidVideoLogger.e { "Error stopping released source: ${it.message}" } }
                    runCatching { player.clearMediaItems() }
                        .onFailure { androidVideoLogger.e { "Error clearing released source: ${it.message}" } }
                }
                preparedSource?.close()
                resetStates()
                if (hadSource) {
                    emitSourceReleasedForSession(releasedSessionId)
                    nextMediaSessionId()
                }
            }
        }
    }

    fun togglePipFullScreen() {
        checkNotDisposed()
        isPipFullScreen = !isPipFullScreen
    }

    override suspend fun enterPip(): PipResult {
        checkNotDisposed()
        if (!isPipSupported) return PipResult.NotSupported
        if (!isPipEnabled) return PipResult.NotEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PipResult.NotPossible

        val currentActivity = attachedActivity() ?: return PipResult.NotPossible

        if (!isPipFullScreen) {
            togglePipFullScreen()
            // Wait for Compose to recompose with fullscreen layout
            withFrameNanos { }
            withFrameNanos { } // two frames to be safe
        }

        val params =
            PictureInPictureParams
                .Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(true)
                    }
                }.build()

        val result = currentActivity.enterPictureInPictureMode(params)

        return if (result) {
            isPipActive = true
            PipResult.Success
        } else {
            PipResult.NotPossible
        }
    }

    override fun seekTo(time: Duration) {
        runOnPlayerThreadBlocking {
            synchronized(playerInitializationLock) {
                checkNotDisposed()
                val player = exoPlayer ?: return@synchronized
                if (player.mediaItemCount == 0) return@synchronized
                val targetTime =
                    when {
                        time < Duration.ZERO -> Duration.ZERO
                        _duration > Duration.ZERO && time > _duration -> _duration
                        else -> time
                    }
                _isSeeking = true
                emitPlaybackEvent { sessionId, sampledAtMs ->
                    PlaybackEvent.SeekStarted(
                        mediaSessionId = sessionId,
                        sampledAtMs = sampledAtMs,
                        target = targetTime,
                    )
                }
                player.seekTo(targetTime.inWholeMilliseconds)
                val expectedGeneration = sourceGeneration
                coroutineScope.launch {
                    delay(250.milliseconds)
                    if (expectedGeneration == sourceGeneration && !isPlayerReleased) _isSeeking = false
                }
            }
        }
    }

    override fun seekToProgress(progress: Float) {
        checkNotDisposed()
        if (_duration > Duration.ZERO) {
            seekTo(_duration * progress.coerceIn(0f, 1f).toDouble())
        }
    }

    override fun seekStart(value: Float) {
        checkNotDisposed()
        userDragging = true
        sliderPos = value
    }

    override fun seekFinished() {
        checkNotDisposed()
        seekToProgress(sliderPos / VideoPlayerState.SLIDER_SCALE)
        userDragging = false
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        checkNotDisposed()
        if (_duration > Duration.ZERO) {
            val fraction = (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
            val targetTime = _duration * fraction
            seekTo(targetTime)
        }
    }

    override fun clearError() {
        checkNotDisposed()
        clearErrorState()
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        checkNotDisposed()
        return HlsQualitySelectionResult.NotSupported
    }

    override fun clearCache(): CacheClearResult {
        checkNotDisposed()
        return if (!cacheConfig.enabled) {
            CacheClearResult.Disabled
        } else {
            try {
                val lease = cacheLease ?: return CacheClearResult.Failed("Android video cache is not initialized.")
                lease.clear()
                CacheClearResult.Cleared
            } catch (e: Exception) {
                CacheClearResult.Failed(e.message ?: "Failed to clear Android video cache.")
            }
        }
    }

    override fun toggleFullscreen() {
        checkNotDisposed()
        isFullscreen = !isFullscreen
    }

    private fun extractFormatMetadata(player: Player) {
        try {
            if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                _metadata.duration = player.duration.millisecondsAsDuration()
            }

            syncAvailableMediaTracks(player)

            var selectedVideoFormat: Format? = null
            player.currentTracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val trackFormat = group.getTrackFormat(i)

                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> {
                            if (selectedVideoFormat == null || group.isTrackSelected(i)) {
                                selectedVideoFormat = trackFormat
                            }

                            if (trackFormat.frameRate > 0) {
                                _metadata.frameRate = trackFormat.frameRate
                            }

                            if (trackFormat.bitrate > 0) {
                                _metadata.bitrate = trackFormat.bitrate.toLong()
                            }

                            trackFormat.sampleMimeType?.let {
                                _metadata.mimeType = it
                            }
                        }

                        C.TRACK_TYPE_AUDIO -> {
                            if (trackFormat.channelCount > 0) {
                                _metadata.audioChannels = trackFormat.channelCount
                            }

                            if (trackFormat.sampleRate > 0) {
                                _metadata.audioSampleRate = trackFormat.sampleRate
                            }
                        }
                    }
                }
            }

            extractMediaItemMetadata(player.currentMediaItem)
            updateAutoDetectedProjection(
                player.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
                    .orEmpty(),
            )
            updateRenderingInfo(selectedVideoFormat)

            androidVideoLogger.d { "Metadata extracted: $_metadata" }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting format metadata: ${e.message}" }
        }
    }

    private fun updateRenderingInfo(videoFormat: Format? = null) {
        if (videoFormat != null && videoFormat != currentVideoFormat) updateVideoColorFormat(videoFormat)
        val sampleMimeType = videoFormat?.sampleMimeType ?: _metadata.mimeType
        val isDolbyVision =
            sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
                videoFormat?.codecs?.isDolbyVisionCodec() == true
        renderingInfo.update(
            backend = "Media3 ExoPlayer",
            container = videoFormat?.containerMimeType,
            videoDecoder = currentVideoDecoderName ?: sampleMimeType?.let { "MediaCodec ($it)" },
            videoRenderer = projection.androidVideoRendererLabel(),
            audioRenderer = "AudioTrack",
            videoProjection = projection.renderingInfoLabel(),
            notes = dolbyVisionPlaybackNote(isDolbyVision),
        )
    }

    private fun resetProjectionForSource(uri: String) {
        projectionAutoDetectionEnabled = playbackOptions.usesAutoProjectionDetection()
        applyProjectionSettings(playbackOptions.detectProjectionForSource(uri))
    }

    private fun updateAutoDetectedProjection(uri: String) {
        if (!projectionAutoDetectionEnabled) return
        applyProjectionSettings(
            playbackOptions.detectProjectionForSource(
                uri = uri,
                title = metadata.title,
                metadata = listOfNotNull(metadata.mimeType),
                videoSizes =
                    listOfNotNull(
                        metadata.width?.let { width ->
                            metadata.height?.let { height ->
                                VideoProjectionVideoSize(width, height)
                            }
                        },
                    ),
            ),
        )
    }

    private fun applyProjectionSettings(value: VideoProjectionSettings) {
        _projection = value.normalized()
        renderingInfo.videoProjection = projection.renderingInfoLabel()
        renderingInfo.videoRenderer = projection.androidVideoRendererLabel()
        refreshColorPipelineOutput()
    }

    private fun VideoProjectionSettings.androidVideoRendererLabel(): String =
        when {
            usesMedia3SphericalProjection(projectionTextureCrop) -> "MediaCodec spherical GL surface"
            usesAndroidCustomProjectionRenderer(projectionTextureCrop) -> "MediaCodec -> OpenGL projection shader"
            else -> "MediaCodec surface"
        }

    private fun dolbyVisionPlaybackNote(isDolbyVisionStream: Boolean): String? =
        when (playbackOptions.dolbyVisionPolicy) {
            DolbyVisionPolicy.AUTO ->
                if (isDolbyVisionStream) {
                    "Dolby Vision detected; Media3 is using the platform profile-compatible decoder path."
                } else {
                    null
                }
            DolbyVisionPolicy.REQUIRE_NATIVE ->
                if (isDolbyVisionStream) {
                    "Native Dolby Vision is required; the color pipeline status confirms whether it is preserved."
                } else {
                    null
                }
            DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER ->
                "An HDR10 base layer is preferred when the stream exposes one; no arbitrary HEVC decoder is substituted."
            DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1 ->
                "Dolby Vision Profile 7 to 8.1 conversion requires an installed converter extension."
        }

    private fun String.isDolbyVisionCodec(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("dvav") ||
            normalized.startsWith("dva1") ||
            normalized.startsWith("dvhe") ||
            normalized.startsWith("dvh1")
    }

    private fun applyDynamicRangeTrackSelection(
        player: Player,
        tracks: Tracks,
    ) {
        val videoGroups = tracks.toAndroidVideoTrackGroups()
        androidVideoLogger.d {
            videoGroups.joinToString(prefix = "Adaptive video groups: ", separator = "; ") { group ->
                val ranges = group.tracks.groupingBy(AndroidVideoTrackCandidate::dynamicRange).eachCount()
                val supportedRanges =
                    group.tracks
                        .filter(AndroidVideoTrackCandidate::isSupported)
                        .groupingBy(AndroidVideoTrackCandidate::dynamicRange)
                        .eachCount()
                "group=${group.index}, selected=${group.isSelected}, ranges=$ranges, supported=$supportedRanges"
            }
        }
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                hdrOutputSourceRanges = hdrOutputSourceRanges(),
                groups = videoGroups,
                currentDynamicRange =
                    currentVideoFormat
                        ?.toVideoColorInfo()
                        ?.dynamicRange
                        ?: VideoDynamicRange.UNKNOWN,
            ) ?: return
        val mediaTrackGroup = tracks.groups[selection.groupIndex].mediaTrackGroup
        val override = TrackSelectionOverride(mediaTrackGroup, selection.trackIndices)
        val currentVideoOverride =
            player.trackSelectionParameters.overrides.values
                .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        if (currentVideoOverride == override) return

        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(override)
                .build()
        androidVideoLogger.d {
            "Restricted adaptive video selection to ${selection.dynamicRange}: ${selection.trackIndices.size} variants."
        }
    }

    private fun maybePrepareProfile7BeforeDecoder(tracks: Tracks) {
        if (
            currentSourceSpec?.preparedPipelineSource != null ||
            sourceConversionAttemptGeneration == sourceGeneration
        ) {
            return
        }
        val selection =
            selectAndroidPreDecoderProfile7Track(
                dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                groups = tracks.toAndroidVideoTrackGroups(),
            ) ?: return
        val format =
            tracks.groups
                .getOrNull(selection.groupIndex)
                ?.takeIf { selection.trackIndex in 0 until it.length }
                ?.getTrackFormat(selection.trackIndex) ?: return
        androidVideoLogger.d {
            "Preparing Dolby Vision Profile 7 before decoder initialization for " +
                "${playbackOptions.dolbyVisionPolicy}."
        }
        updateVideoColorFormat(format)
    }

    private fun Tracks.toAndroidVideoTrackGroups(): List<AndroidVideoTrackGroupCandidate> =
        groups.mapIndexedNotNull { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@mapIndexedNotNull null
            AndroidVideoTrackGroupCandidate(
                index = groupIndex,
                isSelected = group.isSelected,
                tracks =
                    (0 until group.length).map { trackIndex ->
                        val format = group.getTrackFormat(trackIndex)
                        val colorInfo = format.toVideoColorInfo()
                        AndroidVideoTrackCandidate(
                            index = trackIndex,
                            dynamicRange = colorInfo.dynamicRange,
                            isSupported = group.isTrackSupported(trackIndex),
                            dolbyVisionProfile = colorInfo.dolbyVision?.profile,
                            pixelCount = format.pixelCount(),
                            bitrate = format.bitrate.takeIf { it > 0 } ?: 0,
                        )
                    },
            )
        }

    private fun clearDynamicRangeTrackSelection(player: Player) {
        val parameters = player.trackSelectionParameters
        if (parameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }) return
        player.trackSelectionParameters =
            parameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
    }

    private fun hdrOutputSourceRanges(): Set<VideoDynamicRange> =
        VideoDynamicRange.entries
            .filterTo(mutableSetOf()) { sourceRange ->
                if (sourceRange == VideoDynamicRange.UNKNOWN || sourceRange == VideoDynamicRange.SDR) {
                    return@filterTo false
                }
                val nativeOutput =
                    activeColorSurfaceIsNative &&
                        rendererColorCapabilities.supportsNative(sourceRange) &&
                        sourceRange in displayColorCapabilities.supportedDynamicRanges
                val controlledOutputRange = rendererColorCapabilities.controlledOutputFor(sourceRange)
                val controlledOutput =
                    rendererColorCapabilities.supportsControlled(
                        dynamicRange = sourceRange,
                        isProjection = projection.requiresProjectionRenderer,
                    ) &&
                        controlledOutputRange in displayColorCapabilities.supportedDynamicRanges
                nativeOutput || controlledOutput
            }

    private fun Format.pixelCount(): Long = if (width > 0 && height > 0) width.toLong() * height.toLong() else 0

    private fun syncAvailableMediaTracks(player: Player) {
        val audioTracks = mutableListOf<AudioTrack>()
        val embeddedSubtitleTracks = mutableListOf<SubtitleTrack>()
        val previousAudioTrackId = currentAudioTrack?.id
        val previousSubtitleTrackId = currentSubtitleTrack?.id
        var selectedAudioTrackId: String? = null
        var selectedSubtitleTrackId: String? = null
        var audioGroupIndex = 0
        var subtitleGroupIndex = 0

        player.currentTracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val id = androidTrackId(C.TRACK_TYPE_AUDIO, audioGroupIndex, trackIndex)
                        if (group.isTrackSelected(trackIndex)) selectedAudioTrackId = id
                        audioTracks.add(
                            AudioTrack(
                                id = id,
                                label = format.label ?: format.language ?: "Audio ${audioTracks.size + 1}",
                                language = format.language.orEmpty(),
                                channels = format.channelCount.takeIf { it > 0 },
                                sampleRate = format.sampleRate.takeIf { it > 0 },
                                bitrate = format.bitrate.takeIf { it > 0 },
                            ),
                        )
                    }
                    audioGroupIndex += 1
                }

                C.TRACK_TYPE_TEXT -> {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val id = androidTrackId(C.TRACK_TYPE_TEXT, subtitleGroupIndex, trackIndex)
                        if (group.isTrackSelected(trackIndex)) selectedSubtitleTrackId = id
                        embeddedSubtitleTracks.add(
                            SubtitleTrack(
                                label =
                                    format.label ?: format.language ?: "Subtitle ${embeddedSubtitleTracks.size + 1}",
                                language = format.language.orEmpty(),
                                src = "",
                                format =
                                    if (androidSubtitleBackend?.consumesRawEmbeddedFormat(format) == true) {
                                        SubtitleFormat.ASS
                                    } else {
                                        SubtitleFormat.AUTO
                                    },
                                id = id,
                                isEmbedded = true,
                                kind = format.sampleMimeType ?: "subtitles",
                            ),
                        )
                    }
                    subtitleGroupIndex += 1
                }
            }
        }

        _availableAudioTracks.clear()
        _availableAudioTracks.addAll(audioTracks)
        currentAudioTrack =
            selectedAudioTrackId
                ?.let { id -> audioTracks.firstOrNull { it.id == id } }
                ?: currentAudioTrack?.let { current -> audioTracks.firstOrNull { it.id == current.id } }
                ?: audioTracks.firstOrNull()

        val externalSubtitleTracks = _availableSubtitleTracks.filterNot { it.isEmbedded }
        _availableSubtitleTracks.clear()
        _availableSubtitleTracks.addAll(externalSubtitleTracks)
        _availableSubtitleTracks.addAll(embeddedSubtitleTracks)

        if (currentSubtitleTrack?.isEmbedded == true) {
            val refreshedTrack =
                currentSubtitleTrack
                    ?.let { current -> embeddedSubtitleTracks.firstOrNull { it.id == current.id } }
                    ?: selectedSubtitleTrackId?.let { id -> embeddedSubtitleTracks.firstOrNull { it.id == id } }
            if (refreshedTrack == null) {
                disableSubtitles()
            } else {
                currentSubtitleTrack = refreshedTrack
                subtitlesEnabled = true
            }
        } else if (currentSubtitleTrack == null && selectedSubtitleTrackId != null) {
            currentSubtitleTrack = embeddedSubtitleTracks.firstOrNull { it.id == selectedSubtitleTrackId }
            subtitlesEnabled = currentSubtitleTrack != null
        }
        updateNativeSubtitleVisibility()
        if (previousAudioTrackId != currentAudioTrack?.id) {
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.TrackChanged(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    kind = TrackKind.AUDIO,
                    trackId = currentAudioTrack?.id,
                )
            }
        }
        if (previousSubtitleTrackId != currentSubtitleTrack?.id) {
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.TrackChanged(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    kind = TrackKind.SUBTITLE,
                    trackId = currentSubtitleTrack?.id,
                )
            }
        }
    }

    private fun AudioTrack.toAndroidTrackSelectionOverride(
        player: Player,
        trackType: Int,
    ): TrackSelectionOverride? = toAndroidTrackSelectionOverride(id, player, trackType)

    private fun SubtitleTrack.toAndroidTrackSelectionOverride(
        player: Player,
        trackType: Int,
    ): TrackSelectionOverride? = toAndroidTrackSelectionOverride(id, player, trackType)

    private fun toAndroidTrackSelectionOverride(
        id: String,
        player: Player,
        trackType: Int,
    ): TrackSelectionOverride? {
        val (targetGroupIndex, targetTrackIndex) = id.toAndroidTrackIndices(trackType) ?: return null
        var groupIndex = 0
        player.currentTracks.groups.forEach { group ->
            if (group.type == trackType) {
                if (groupIndex == targetGroupIndex && targetTrackIndex in 0 until group.length) {
                    return TrackSelectionOverride(group.mediaTrackGroup, targetTrackIndex)
                }
                groupIndex += 1
            }
        }
        return null
    }

    private fun androidTrackId(
        trackType: Int,
        groupIndex: Int,
        trackIndex: Int,
    ): String = "android:$trackType:$groupIndex:$trackIndex"

    private fun String.toAndroidTrackIndices(trackType: Int): Pair<Int, Int>? {
        val parts = split(':')
        if (parts.size != 4 || parts[0] != "android" || parts[1].toIntOrNull() != trackType) return null
        val groupIndex = parts[2].toIntOrNull() ?: return null
        val trackIndex = parts[3].toIntOrNull() ?: return null
        return groupIndex to trackIndex
    }

    private fun extractMediaItemMetadata(mediaItem: MediaItem?) {
        try {
            mediaItem?.mediaMetadata?.let { metadata ->
                metadata.title?.toString()?.let { _metadata.title = it }
            }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting media item metadata: ${e.message}" }
        }
    }

    private fun syncMediaChapters(player: Player) {
        mergeMediaChapterRows(player.media3ChapterRows())
        publishMediaChapters()
    }

    private fun mergeMediaChapterRows(rows: List<RawMediaChapter>) {
        rows.forEach { row ->
            if (discoveredChapterRows.none { existing -> existing == row }) {
                discoveredChapterRows += row
            }
        }
    }

    private fun publishMediaChapters() {
        _chapters = normalizeMediaChapters(discoveredChapterRows, _duration)
    }

    private fun clearMediaChapters() {
        hlsChapterDiscovery?.cancel()
        hlsChapterDiscovery = null
        discoveredChapterRows.clear()
        _chapters = emptyList()
    }

    private fun resetStates(keepMedia: Boolean = false) {
        _currentTime = Duration.ZERO
        _duration = Duration.ZERO
        _sliderPos = 0f
        _isPlaying = false
        _isLoading = false
        _isSeeking = false
        clearErrorState()
        wasStalled = false
        _aspectRatio = 16f / 9f
        _playbackSpeed = 1.0f
        currentVideoFormat = null
        currentVideoDecoderName = null
        renderedVideoFrameCount.set(0L)
        droppedVideoFrameCount.set(0L)
        maximumAvSyncOffsetUs.set(-1L)
        playbackClockSnapshot = AndroidPlaybackClockSnapshot()
        observedHdr10PlusInfo = null
        observedHdrStaticMetadata = null
        hdr10PlusMetadataReset?.invoke()
        decoderColorCapabilities = DecoderColorCapabilities()
        requiredHdrVerificationJob?.cancel()
        requiredHdrVerificationJob = null
        requiredHdrVerificationFailureDetail = null
        hasRenderedFirstVideoFrame = false
        controlledColorRendererError = null
        decoderToneMapHlgToSdr = false
        controlledVideoGraphFailurePending = false
        controlledRendererRequestedOutputDynamicRange =
            if (controlledRendererToneMapToSdr) VideoDynamicRange.SDR else VideoDynamicRange.UNKNOWN
        unavailableControlledHdrRanges.clear()
        unavailableNativeSurfaceDataSpaceRanges = emptySet()
        nativeSurfaceDataSpaceFailureDetail = null
        systemReportedNativeHdrOutput.reset()
        controlledHdrRendererFallbackDetail = null
        colorPipelineController.resetSource()
        resetMetadata()
        updateRenderingInfo()
        exoPlayer?.playbackParameters = PlaybackParameters(_playbackSpeed)
        _currentAudioTrack = null
        _availableAudioTracks.clear()
        if (_currentSubtitleTrack?.isEmbedded == true) {
            _currentSubtitleTrack = null
            _subtitlesEnabled = false
        }
        _availableSubtitleTracks.removeAll { it.isEmbedded }
        updateNativeSubtitleVisibility()
        if (!keepMedia) {
            clearMediaChapters()
            _hasMedia = false
            sourceLoadedSessionId = 0L
        }
    }

    private fun resetMetadata() {
        _metadata.title = null
        _metadata.duration = null
        _metadata.width = null
        _metadata.height = null
        _metadata.bitrate = null
        _metadata.frameRate = null
        _metadata.mimeType = null
        _metadata.audioChannels = null
        _metadata.audioSampleRate = null
    }

    @Suppress("TooGenericExceptionCaught")
    override fun dispose() {
        val player: ExoPlayer?
        val listener: Player.Listener?
        val releasedSessionId: Long
        val hadSource: Boolean
        val preparedSource: PreparedVideoPipelineSource?
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return
            isPlayerReleased = true
            releasedSessionId = mediaSessionId
            hadSource = currentSourceSpec != null
            preparedSource = currentSourceSpec?.preparedPipelineSource
            currentSourceSpec = null
            sourceConversionJob?.cancel()
            sourceConversionJob = null
            sourceConversionAttemptGeneration = null
            allowAutomaticDolbyVisionConversion = true
            originalPipelineColorInfo = null
            externalAssLoadJob?.cancel()
            externalAssLoadJob = null
            sourceGeneration += 1
            invalidateScreenAutoResume()
            player = exoPlayer
            listener = playerListener
            playerListener = null
        }

        val releaseAttempted = AtomicBoolean(false)
        try {
            runOnPlayerThreadBlocking {
                stopPositionUpdates()
                abandonDuckAudioFocus()
                playerView?.player = null
                playerView = null
                projectionVideoSurfaceView = null
                unregisterScreenLockReceiver()
                unregisterActiveDisplayListener()
                if (player != null) {
                    listener?.let { currentListener ->
                        runCatching { player.removeListener(currentListener) }
                            .onFailure { androidVideoLogger.e { "Error removing player listener: ${it.message}" } }
                    }
                    runCatching { player.stop() }
                        .onFailure { androidVideoLogger.e { "Error stopping disposed player: ${it.message}" } }
                    runCatching { player.clearMediaItems() }
                        .onFailure { androidVideoLogger.e { "Error clearing disposed player: ${it.message}" } }
                    releaseAttempted.set(true)
                    runCatching { player.release() }
                        .onFailure { androidVideoLogger.e { "Error releasing disposed player: ${it.message}" } }
                }
                exoPlayer = null
                resetStates()
                if (hadSource) emitSourceReleasedForSession(releasedSessionId)
            }
        } catch (dispatchFailure: Throwable) {
            androidVideoLogger.e { "Error dispatching player disposal: ${dispatchFailure.message}" }
            if (player != null && !releaseAttempted.get()) {
                // The normal path always uses ExoPlayer's application looper. This is a last-resort attempt for a
                // looper that is already shutting down; release must never be skipped because an earlier step failed.
                runCatching { player.release() }
                    .onFailure { androidVideoLogger.e { "Fallback player release failed: ${it.message}" } }
            }
        } finally {
            preparedSource?.close()
            exoPlayer = null
            playbackEndedCallback = null
            restartCallback = null
            coroutineScope.cancel()
            runCatching { cacheLease?.close() }
                .onFailure { androidVideoLogger.e { "Error releasing video cache lease: ${it.message}" } }
            cacheLease = null
            androidSubtitleBackend?.release()
        }
    }
}
