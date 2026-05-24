package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
): VideoPlayerState =
    try {
        DefaultVideoPlayerState(audioMode, cacheConfig)
    } catch (e: IllegalStateException) {
        PreviewableVideoPlayerState(
            hasMedia = false,
            isPlaying = false,
            isLoading = false,
            volume = 1f,
            sliderPos = 0f,
            userDragging = false,
            loop = false,
            playbackSpeed = 1f,
            positionText = "00:00.000",
            durationText = "00:00.000",
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
    }

internal val androidVideoLogger = TaggedLogger("AndroidVideoPlayerSurface")

@UnstableApi
@Stable
open class DefaultVideoPlayerState(
    private val audioMode: AudioMode = AudioMode(),
    private val cacheConfig: CacheConfig = CacheConfig(),
) : VideoPlayerState {
    companion object {
        var activity: WeakReference<Activity> = WeakReference(null)

        private var currentPlayerState: WeakReference<DefaultVideoPlayerState>? = null
        private const val PERCENT_SCALE = 100f

        /**
         * Call this from Activity.onPictureInPictureModeChanged()
         */
        fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
            currentPlayerState?.get()?.isPipActive = isInPictureInPictureMode
        }

        internal fun register(state: DefaultVideoPlayerState) {
            currentPlayerState = WeakReference(state)
        }
    }

    private val context: Context = ContextProvider.getContext()
    internal var exoPlayer: ExoPlayer? = null
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Protection against race conditions
    private var isPlayerReleased = false
    private val playerInitializationLock = Any()
    private var playerListener: Player.Listener? = null

    // Screen lock detection
    private var screenLockReceiver: BroadcastReceiver? = null
    private var wasPlayingBeforeScreenLock: Boolean = false

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

    private var _metadata = VideoMetadata()
    override val metadata: VideoMetadata get() = _metadata

    // Subtitle state
    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override val availableSubtitleTracks = mutableStateListOf<SubtitleTrack>()
    override var subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )

    override var subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override var subtitleOffset by mutableStateOf(Duration.ZERO)

    // Audio track state
    override var currentAudioTrack by mutableStateOf<AudioTrack?>(null)
    override val availableAudioTracks = mutableStateListOf<AudioTrack>()

    private var playerView: PlayerView? = null

    override fun selectAudioTrack(track: AudioTrack?) {
        currentAudioTrack = track

        exoPlayer?.let { player ->
            if (track == null) {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                return@let
            }

            val trackSelectionOverride = track.toAndroidTrackSelectionOverride(player, C.TRACK_TYPE_AUDIO)
            if (trackSelectionOverride != null) {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(trackSelectionOverride)
                        .build()
            }
        }
    }

    // Select an external subtitle track
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null) {
            disableSubtitles()
            return
        }

        currentSubtitleTrack = track
        subtitlesEnabled = true

        exoPlayer?.let { player ->
            if (track.isEmbedded) {
                val trackSelectionOverride = track.toAndroidTrackSelectionOverride(player, C.TRACK_TYPE_TEXT)
                if (trackSelectionOverride != null) {
                    player.trackSelectionParameters =
                        player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(trackSelectionOverride)
                            .build()

                    playerView?.subtitleView?.visibility = android.view.View.VISIBLE
                }
            } else {
                val trackParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                player.trackSelectionParameters = trackParameters

                playerView?.subtitleView?.visibility = android.view.View.GONE
            }
        }
    }

    override fun disableSubtitles() {
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
            } catch (e: Exception) {
                androidVideoLogger.e { "Error attaching player to view: ${e.message}" }
            }
        }
    }

    // Volume control
    private var _volume by mutableFloatStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            exoPlayer?.volume = _volume
        }

    // Slider position
    private var _sliderPos by mutableFloatStateOf(0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            _sliderPos = value.coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
        }

    // User interaction states
    override var userDragging by mutableStateOf(false)

    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null

    // Loop control
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
            exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }

    // Playback speed control
    private var _playbackSpeed by mutableFloatStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            exoPlayer?.let { player ->
                player.playbackParameters = PlaybackParameters(_playbackSpeed)
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
            _isFullscreen = value
        }

    var isPipFullScreen by mutableStateOf(false)

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
                val ctx = activity.get() ?: ContextProvider.getContext()
                return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            }
            return false
        }

    override var isPipEnabled by mutableStateOf(false)
    override var isPipActive by mutableStateOf(false)

    init {
        register(this)
        initializePlayer()
        registerScreenLockReceiver()
    }

    private fun shouldUseConservativeCodecHandling(): Boolean {
        val device = android.os.Build.DEVICE
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL

        // List of devices known to have MediaCodec issues
        val problematicDevices =
            setOf(
                "SM-A155F", // Galaxy A15
                "SM-A156B", // Galaxy A15 5G
                // Add other problematic models here
            )

        return device in problematicDevices ||
            model in problematicDevices ||
            manufacturer.equals("mediatek", ignoreCase = true)
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
                        Intent.ACTION_SCREEN_OFF -> {
                            androidVideoLogger.d { "Screen turned off (locked)" }
                            synchronized(playerInitializationLock) {
                                if (!isPlayerReleased && exoPlayer != null) {
                                    wasPlayingBeforeScreenLock = _isPlaying
                                    if (_isPlaying) {
                                        try {
                                            androidVideoLogger.d { "Pausing playback due to screen lock" }
                                            exoPlayer?.pause()
                                        } catch (e: Exception) {
                                            androidVideoLogger.e { "Error pausing on screen lock: ${e.message}" }
                                        }
                                    }
                                }
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            androidVideoLogger.d { "Screen turned on (unlocked)" }
                            synchronized(playerInitializationLock) {
                                if (!isPlayerReleased && wasPlayingBeforeScreenLock && exoPlayer != null) {
                                    try {
                                        // Add a small delay to ensure the system is ready
                                        coroutineScope.launch {
                                            delay(200.milliseconds)
                                            if (!isPlayerReleased) {
                                                androidVideoLogger.d { "Resuming playback after screen unlock" }
                                                exoPlayer?.play()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        androidVideoLogger.e { "Error resuming after screen unlock: ${e.message}" }
                                    }
                                }
                            }
                        }
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

                    // On problematic devices, use more conservative settings
                    if (shouldUseConservativeCodecHandling()) {
                        // Cannot disable async queueing as the method does not exist
                        // But we can use the default MediaCodecSelector
                        setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                    }
                }

            val manageFocus = audioMode.interruptionMode == InterruptionMode.DoNotMix
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
                    .setHandleAudioBecomingNoisy(manageFocus)
                    .setWakeMode(if (manageFocus) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
                    .setAudioAttributes(audioAttributes, manageFocus)
                    .setPauseAtEndOfMediaItems(false)
                    .setReleaseTimeoutMs(2000) // Increase the release timeout

            if (cacheConfig.enabled) {
                val cacheDataSourceFactory = buildCachingDataSourceFactory(context, cacheConfig.maxCacheSizeBytes)
                playerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            }

            exoPlayer =
                playerBuilder
                    .build()
                    .apply {
                        playerListener = createPlayerListener()
                        addListener(playerListener!!)
                        volume = _volume
                    }
        }
    }

    private fun createPlayerListener() =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Add a safety check
                if (isPlayerReleased) return

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
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
                            }
                        }
                    }

                    Player.STATE_ENDED -> {
                        _isLoading = false
                        _isSeeking = false
                        stopPositionUpdates()
                        _isPlaying = false
                        onPlaybackEnded?.invoke()
                    }

                    Player.STATE_IDLE -> {
                        _isLoading = false
                        _isSeeking = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (!isPlayerReleased) {
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
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && _loop) {
                    onRestart?.invoke()
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    _isSeeking = false
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    _metadata.width = videoSize.width
                    _metadata.height = videoSize.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!isPlayerReleased) {
                    exoPlayer?.let { player ->
                        syncAvailableMediaTracks(player)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
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
                        _error = VideoPlayerError.CodecError("Decoder error: ${error.message}")
                        // Attempt recovery for codec errors
                        attemptPlayerRecovery()
                    }
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    -> {
                        _error = VideoPlayerError.NetworkError("Network error: ${error.message}")
                    }
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    -> {
                        _error = VideoPlayerError.SourceError("Invalid media source: ${error.message}")
                    }
                    else -> {
                        _error = VideoPlayerError.UnknownError("Playback error: ${error.message}")
                    }
                }
                _isPlaying = false
                _isLoading = false
                _isSeeking = false
            }
        }

    private fun attemptPlayerRecovery() {
        coroutineScope.launch {
            delay(100.milliseconds) // Small delay to let the system clean up

            synchronized(playerInitializationLock) {
                if (!isPlayerReleased) {
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val currentMediaItem = player.currentMediaItem
                        val wasPlaying = player.isPlaying

                        try {
                            // Remove the listener before releasing
                            playerListener?.let { player.removeListener(it) }

                            // Release the current player
                            player.release()

                            // Reinitialize
                            initializePlayer()

                            // Restore the media item and position
                            currentMediaItem?.let {
                                exoPlayer?.apply {
                                    setMediaItem(it)
                                    prepare()
                                    seekTo(currentPosition)
                                    // Restore the playback state if needed
                                    if (wasPlaying) {
                                        play()
                                    } else {
                                        pause()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            androidVideoLogger.e { "Error during player recovery: ${e.message}" }
                            _error = VideoPlayerError.UnknownError("Recovery failed: ${e.message}")
                        }
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
        initializeplayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState, requestHeaders)
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        val mediaItemBuilder = MediaItem.Builder()
        val videoUri: Uri =
            when (val androidFile = file.androidFile) {
                is AndroidFile.UriWrapper -> androidFile.uri
                is AndroidFile.FileWrapper -> Uri.fromFile(androidFile.file)
            }
        mediaItemBuilder.setUri(videoUri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    override fun openAsset(
        fileName: String,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri("asset:///$fileName", initializeplayerState)
    }

    private fun openFromMediaItem(
        mediaItem: MediaItem,
        initializeplayerState: InitialPlayerState,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            exoPlayer?.let { player ->
                player.stop()
                player.clearMediaItems()
                try {
                    _error = null
                    resetStates(keepMedia = true)

                    // Extract metadata before preparing the player
                    extractMediaItemMetadata(mediaItem)

                    if (requestHeaders.isEmpty()) {
                        player.setMediaItem(mediaItem)
                    } else {
                        player.setMediaSource(requestHeaders.mediaSourceFactory().createMediaSource(mediaItem))
                    }
                    player.prepare()
                    player.volume = volume
                    player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                    // Control the initial playback state
                    if (initializeplayerState == InitialPlayerState.PLAY) {
                        player.play()
                        _hasMedia = true
                    } else {
                        player.pause()
                        _isPlaying = false
                        _hasMedia = true
                    }
                } catch (e: Exception) {
                    androidVideoLogger.d { "Error opening media: ${e.message}" }
                    _isPlaying = false
                    _hasMedia = false
                    _error = VideoPlayerError.SourceError("Failed to load media: ${e.message}")
                }
            }
        }
    }

    private fun Map<String, String>.mediaSourceFactory(): DefaultMediaSourceFactory {
        val httpFactory =
            DefaultHttpDataSource
                .Factory()
                .setDefaultRequestProperties(sanitizedRequestHeaders())
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
    }

    override fun play() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    } else if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0)
                    }
                    player.play()
                }
                _hasMedia = true
            }
        }
    }

    override fun restart() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.seekTo(0)
                    player.play()
                }
            }
        }
    }

    override fun pause() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.pause()
            }
        }
    }

    override fun stop() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    player.stop()
                    player.seekTo(0)
                }
                _hasMedia = false
                resetStates(keepMedia = true)
            }
        }
    }

    fun togglePipFullScreen() {
        isPipFullScreen = !isPipFullScreen
    }

    override suspend fun enterPip(): PipResult {
        if (!isPipSupported) return PipResult.NotSupported
        if (!isPipEnabled) return PipResult.NotEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PipResult.NotPossible

        val currentActivity = activity.get() ?: return PipResult.NotPossible

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
        if (!isPlayerReleased) {
            val targetTime =
                when {
                    time < Duration.ZERO -> Duration.ZERO
                    _duration > Duration.ZERO && time > _duration -> _duration
                    else -> time
                }
            _isSeeking = true
            exoPlayer?.seekTo(targetTime.inWholeMilliseconds)
            coroutineScope.launch {
                delay(250.milliseconds)
                _isSeeking = false
            }
        }
    }

    override fun seekToProgress(progress: Float) {
        if (_duration > Duration.ZERO) {
            seekTo(_duration * progress.coerceIn(0f, 1f).toDouble())
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        if (_duration > Duration.ZERO && !isPlayerReleased) {
            val fraction = (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
            val targetTime = _duration * fraction
            seekTo(targetTime)
        }
    }

    override fun clearError() {
        _error = null
    }

    override fun clearCache() {
        if (cacheConfig.enabled) {
            VideoCache.clear(context, cacheConfig.maxCacheSizeBytes)
        }
    }

    override fun toggleFullscreen() {
        _isFullscreen = !_isFullscreen
    }

    private fun extractFormatMetadata(player: Player) {
        try {
            if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                _metadata.duration = player.duration.millisecondsAsDuration()
            }

            syncAvailableMediaTracks(player)

            player.currentTracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val trackFormat = group.getTrackFormat(i)

                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> {
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

            androidVideoLogger.d { "Metadata extracted: $_metadata" }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting format metadata: ${e.message}" }
        }
    }

    private fun syncAvailableMediaTracks(player: Player) {
        val audioTracks = mutableListOf<AudioTrack>()
        val embeddedSubtitleTracks = mutableListOf<SubtitleTrack>()
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

        availableAudioTracks.clear()
        availableAudioTracks.addAll(audioTracks)
        currentAudioTrack =
            selectedAudioTrackId
                ?.let { id -> audioTracks.firstOrNull { it.id == id } }
                ?: currentAudioTrack?.let { current -> audioTracks.firstOrNull { it.id == current.id } }
                ?: audioTracks.firstOrNull()

        val externalSubtitleTracks = availableSubtitleTracks.filterNot { it.isEmbedded }
        availableSubtitleTracks.clear()
        availableSubtitleTracks.addAll(externalSubtitleTracks)
        availableSubtitleTracks.addAll(embeddedSubtitleTracks)

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
        _error = null
        _aspectRatio = 16f / 9f
        _playbackSpeed = 1.0f
        _metadata = VideoMetadata()
        exoPlayer?.playbackParameters = PlaybackParameters(_playbackSpeed)
        currentAudioTrack = null
        availableAudioTracks.clear()
        if (currentSubtitleTrack?.isEmbedded == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
        availableSubtitleTracks.removeAll { it.isEmbedded }
        if (!keepMedia) {
            _hasMedia = false
        }
    }

    override fun dispose() {
        synchronized(playerInitializationLock) {
            isPlayerReleased = true
            stopPositionUpdates()
            coroutineScope.cancel()
            playerView?.player = null
            playerView = null

            try {
                exoPlayer?.let { player ->
                    // Remove the listener specifically
                    playerListener?.let { listener ->
                        player.removeListener(listener)
                    }
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error during player disposal: ${e.message}" }
            }

            playerListener = null
            exoPlayer = null
            unregisterScreenLockReceiver()
            resetStates()
        }
    }
}
