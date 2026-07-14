package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Surface
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState =
    createAndroidVideoPlayerState {
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
)

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
    }

    private val context: Context = ContextProvider.getContext()
    internal var exoPlayer: ExoPlayer? = null
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val playbackEventDispatcher = PlaybackEventDispatcher()
    override val mediaSessionId: Long get() = playbackEventDispatcher.mediaSessionId
    override val playbackEvents = playbackEventDispatcher.events

    // Protection against race conditions
    @Volatile
    private var isPlayerReleased = false
    private val playerInitializationLock = Any()
    private var playerListener: Player.Listener? = null
    private var sourceGeneration = 0L
    private var currentSourceSpec: AndroidSourceSpec? = null
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
        get() = platformPlayerCapabilities().copy(supportsPiP = isPipSupported)

    private val _metadata = VideoMetadata()
    override val metadata: VideoMetadata get() = _metadata
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
    private var projectionVideoSurface: Surface? = null

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

        if (player != null && track.isEmbedded) {
            val trackSelectionOverride =
                embeddedTrackSelectionOverride ?: return TrackSelectionResult.NotFound(track.id)
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(trackSelectionOverride)
                    .build()

            playerView?.subtitleView?.visibility = android.view.View.VISIBLE
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

    internal fun attachProjectionVideoSurface(surface: Surface?) {
        if (surface == null) {
            projectionVideoSurface?.let { oldSurface ->
                runCatching { exoPlayer?.clearVideoSurface(oldSurface) }
                    .onFailure { e ->
                        androidVideoLogger.e { "Error clearing projection surface: ${e.message}" }
                    }
            }
            projectionVideoSurface = null
            return
        }

        if (projectionVideoSurface == surface) return
        projectionVideoSurface?.let { oldSurface ->
            runCatching { exoPlayer?.clearVideoSurface(oldSurface) }
                .onFailure { e ->
                    androidVideoLogger.e { "Error clearing previous projection surface: ${e.message}" }
                }
        }
        playerView?.player = null
        playerView = null
        projectionVideoSurface = surface
        exoPlayer?.let { player ->
            runCatching {
                player.setVideoSurface(surface)
            }.onFailure { e ->
                androidVideoLogger.e { "Error attaching projection surface: ${e.message}" }
            }
        }
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
            if (subtitlesEnabled && currentSubtitleTrack?.isEmbedded == true) {
                View.VISIBLE
            } else {
                View.GONE
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
            _isFullscreen = value
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
        }
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

            val player = exoPlayer
            val listener = playerListener
            playerListener = null
            playerView?.player = null
            playerView = null
            projectionVideoSurface = null
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

            val audioSink =
                DefaultAudioSink
                    .Builder(context)
                    .build()

            val renderersFactory =
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = audioSink
                }.apply {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    // Enable decoder fallback for better stability
                    setEnableDecoderFallback(true)

                    if (usesDolbyVisionHevcFallbackSelector()) {
                        setMediaCodecSelector(dolbyVisionHevcFallbackSelector())
                    }

                    // On problematic devices, use more conservative settings
                    if (shouldUseConservativeAndroidCodecHandling() && !usesDolbyVisionHevcFallbackSelector()) {
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

            if (cacheConfig.enabled) {
                val lease =
                    cacheLease ?: VideoCache.acquire(context, cacheConfig.maxCacheSizeBytes).also {
                        cacheLease = it
                    }
                val cacheDataSourceFactory = buildAndroidDataSourceFactory(context, lease.cache)
                playerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            }

            exoPlayer =
                playerBuilder
                    .build()
                    .apply {
                        volume = _volume
                        projectionVideoSurface?.let(::setVideoSurface)
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
                    _aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    _metadata.width = videoSize.width
                    _metadata.height = videoSize.height
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
                        syncAvailableMediaTracks(player)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentSourceCallback(generation)) return
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
                            _currentTime = player.currentPosition.millisecondsAsDuration()
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
            stopPositionUpdates()
            abandonDuckAudioFocus()
            player.stop()
            player.clearMediaItems()
            try {
                clearErrorState()
                resetStates(keepMedia = true)
                updateRenderingInfo()
                val sourceSpec = AndroidSourceSpec(mediaItem, requestHeaders.sanitizedRequestHeaders())
                currentSourceSpec = sourceSpec
                installSourceListener(player)
                _hasMedia = true

                // Extract metadata before preparing the player
                extractMediaItemMetadata(mediaItem)

                player.setSource(sourceSpec)
                player.prepare()
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
        val mediaSource = createMediaSource(sourceSpec.mediaItem, sourceSpec.requestHeaders)
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
    ): MediaSource? =
        requestHeaders
            .takeIf { it.isNotEmpty() }
            ?.let { headers ->
                DefaultMediaSourceFactory(
                    buildAndroidDataSourceFactory(
                        context = context,
                        cache = cacheLease?.cache,
                        requestHeaders = headers,
                    ),
                )
            }?.createMediaSource(mediaItem)

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
                currentSourceSpec = null
                stopPositionUpdates()
                abandonDuckAudioFocus()
                if (player != null) {
                    runCatching { player.stop() }
                        .onFailure { androidVideoLogger.e { "Error stopping released source: ${it.message}" } }
                    runCatching { player.clearMediaItems() }
                        .onFailure { androidVideoLogger.e { "Error clearing released source: ${it.message}" } }
                }
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
        _isFullscreen = !_isFullscreen
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
        val sampleMimeType = videoFormat?.sampleMimeType ?: _metadata.mimeType
        val isDolbyVision =
            sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
                videoFormat?.codecs?.isDolbyVisionCodec() == true
        renderingInfo.update(
            backend = "Media3 ExoPlayer",
            container = videoFormat?.containerMimeType,
            videoDecoder = sampleMimeType?.let { "MediaCodec ($it)" },
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
    }

    private fun VideoProjectionSettings.androidVideoRendererLabel(): String =
        when {
            usesMedia3SphericalProjection(projectionTextureCrop) -> "MediaCodec spherical GL surface"
            usesAndroidCustomProjectionRenderer(projectionTextureCrop) -> "MediaCodec -> OpenGL projection shader"
            else -> "MediaCodec surface"
        }

    private fun dolbyVisionPlaybackNote(isDolbyVisionStream: Boolean): String? =
        when (playbackOptions.dolbyVisionMode) {
            DolbyVisionMode.AUTO ->
                if (isDolbyVisionStream) {
                    "Dolby Vision detected; using platform decoder fallback policy."
                } else {
                    null
                }
            DolbyVisionMode.PASSTHROUGH ->
                if (isDolbyVisionStream) {
                    "Dolby Vision passthrough requested; relying on platform decoder and display support."
                } else {
                    null
                }
            DolbyVisionMode.PREFER_HDR10_COMPATIBLE ->
                "Dolby Vision HDR10-compatible fallback requested; decoder queries include HEVC candidates."
            DolbyVisionMode.TRANSCODE_PROFILE_7_TO_8_1 ->
                "Dolby Vision Profile 7 to 8.1 transcoding requested, but this build has no libdovi bridge; " +
                    "using HEVC-compatible decoder fallback."
        }

    private fun usesDolbyVisionHevcFallbackSelector(): Boolean =
        playbackOptions.dolbyVisionMode == DolbyVisionMode.PREFER_HDR10_COMPATIBLE ||
            playbackOptions.dolbyVisionMode == DolbyVisionMode.TRANSCODE_PROFILE_7_TO_8_1

    private fun dolbyVisionHevcFallbackSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultDecoders =
                MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
            if (mimeType != MimeTypes.VIDEO_DOLBY_VISION) {
                return@MediaCodecSelector defaultDecoders
            }

            val hevcDecoders =
                MediaCodecSelector.DEFAULT.getDecoderInfos(
                    MimeTypes.VIDEO_H265,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
            (hevcDecoders + defaultDecoders).distinctBy { "${it.name}:${it.mimeType}:${it.secure}" }
        }

    private fun String.isDolbyVisionCodec(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("dvav") ||
            normalized.startsWith("dva1") ||
            normalized.startsWith("dvhe") ||
            normalized.startsWith("dvh1")
    }

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
                                format = SubtitleFormat.AUTO,
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
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return
            isPlayerReleased = true
            releasedSessionId = mediaSessionId
            hadSource = currentSourceSpec != null
            currentSourceSpec = null
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
                projectionVideoSurface = null
                unregisterScreenLockReceiver()
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
            exoPlayer = null
            playbackEndedCallback = null
            restartCallback = null
            coroutineScope.cancel()
            runCatching { cacheLease?.close() }
                .onFailure { androidVideoLogger.e { "Error releasing video cache lease: ${it.message}" } }
            cacheLease = null
        }
    }
}
