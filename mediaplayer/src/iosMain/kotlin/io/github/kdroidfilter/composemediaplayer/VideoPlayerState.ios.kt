@file:OptIn(ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.*
import platform.AVKit.AVPictureInPictureController
import platform.CoreGraphics.CGFloat
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSKeyValueChangeNewKey
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingOptions
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSEC_PER_SEC
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.time.Duration

actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState = DefaultVideoPlayerState(audioMode, cacheConfig, playbackOptions)

private val iosLogger = TaggedLogger("iOSVideoPlayerState")

internal class IosSurfaceOwnership<T : Any> {
    private data class Binding<T : Any>(
        val owner: T,
        val isFullscreen: Boolean,
    )

    private val bindings = mutableListOf<Binding<T>>()

    val activeOwner: T?
        get() = bindings.lastOrNull { it.isFullscreen }?.owner ?: bindings.lastOrNull()?.owner

    fun bind(
        owner: T,
        isFullscreen: Boolean,
    ) {
        val existingIndex = bindings.indexOfFirst { it.owner === owner }
        if (existingIndex >= 0) bindings.removeAt(existingIndex)
        bindings += Binding(owner, isFullscreen)
    }

    fun release(owner: T) {
        bindings.removeAll { it.owner === owner }
    }

    fun clear(): List<T> = bindings.map { it.owner }.also { bindings.clear() }
}

private data class IosPipLayerBinding(
    val layer: AVPlayerLayer,
    val controller: AVPictureInPictureController?,
)

@Suppress("LargeClass", "TooManyFunctions")
@Stable
open class DefaultVideoPlayerState(
    private val audioMode: AudioMode = AudioMode(),
    private val cacheConfig: CacheConfig = CacheConfig(),
    private val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    private var projectionAutoDetectionEnabled = playbackOptions.usesAutoProjectionDetection()
    private var projectionSourceUri: String = ""
    private var _projection by mutableStateOf(playbackOptions.projection.normalized())
    override var projection: VideoProjectionSettings
        get() = _projection
        set(value) {
            ensureNotDisposed()
            projectionAutoDetectionEnabled = false
            applyProjectionSettings(value)
        }
    private var _projectionView by mutableStateOf(playbackOptions.projectionView.normalized())
    override var projectionView: VideoProjectionViewSettings
        get() = _projectionView
        set(value) {
            ensureNotDisposed()
            _projectionView = value.normalized()
        }
    private var _projectionViewControlMode by mutableStateOf(playbackOptions.projectionViewControlMode)
    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = _projectionViewControlMode
        set(value) {
            ensureNotDisposed()
            _projectionViewControlMode = value
        }
    private var _projectionTextureCrop by mutableStateOf(playbackOptions.projectionTextureCrop.normalized())
    override var projectionTextureCrop: VideoTextureCrop
        get() = _projectionTextureCrop
        set(value) {
            ensureNotDisposed()
            _projectionTextureCrop = value.normalized()
            applyProjectionSettings(projection)
        }
    override val renderingInfo: VideoRenderingInfo =
        VideoRenderingInfo(
            backend = "AVFoundation AVPlayer",
            videoRenderer = projection.iosVideoRendererLabel(projectionTextureCrop),
            audioRenderer = "AVAudioSession",
            videoProjection = projection.renderingInfoLabel(),
            notes = null,
        )

    // Base states
    private var _volume = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volume.value
        set(value) {
            ensureNotDisposed()
            val clampedValue = value.coerceIn(0f, 1f)
            _volume.value = clampedValue
            player?.volume = clampedValue
        }

    private var playbackEndedCallback: (() -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)?
        get() = playbackEndedCallback
        set(value) {
            ensureNotDisposed()
            playbackEndedCallback = value
        }
    private var restartCallback: (() -> Unit)? = null
    override var onRestart: (() -> Unit)?
        get() = restartCallback
        set(value) {
            ensureNotDisposed()
            restartCallback = value
        }
    private var _sliderPos by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            ensureNotDisposed()
            _sliderPos = value
        }
    private var _userDragging = false
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            ensureNotDisposed()
            _userDragging = value
        }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            ensureNotDisposed()
            _loop = value
            iosLogger.d { "Loop setting changed to: $value" }
        }

    // Playback speed control
    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            ensureNotDisposed()
            _playbackSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            // Only update player rate if we are playing to avoid auto-play
            if (_isPlaying) {
                player?.rate = _playbackSpeed
            }
        }

    // Playback states
    override val hasMedia: Boolean get() = _hasMedia
    override val isPlaying: Boolean get() = _isPlaying
    private var _hasMedia by mutableStateOf(false)
    private var _isPlaying by mutableStateOf(false)

    // Displayed texts for position and duration
    private var _positionText: String by mutableStateOf("00:00")
    override val positionText: String get() = _positionText
    private var _durationText: String by mutableStateOf("00:00")
    override val durationText: String get() = _durationText

    // Loading state
    private var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean
        get() = _isLoading
    private var _isSeeking by mutableStateOf(false)
    override val isSeeking: Boolean
        get() = _isSeeking

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            ensureNotDisposed()
            _isFullscreen = value
        }

    override val isPipSupported: Boolean
        get() = AVPictureInPictureController.isPictureInPictureSupported()

    private var _isPipEnabled by mutableStateOf(false)

    override var isPipEnabled: Boolean
        get() = _isPipEnabled
        set(value) {
            ensureNotDisposed()
            pipController?.setCanStartPictureInPictureAutomaticallyFromInline(value)
            _isPipEnabled = value
        }

    private var _isPipActive by mutableStateOf(false)
    override var isPipActive: Boolean
        get() = pipController?.pictureInPictureActive ?: _isPipActive
        set(value) {
            ensureNotDisposed()
            _isPipActive = value
        }

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error
    override val capabilities: PlayerCapabilities
        get() = platformPlayerCapabilities().copy(supportsPiP = isPipSupported)

    // Observable instance of AVPlayer
    var player: AVPlayer? by mutableStateOf(null)
        private set

    var playerLayer: AVPlayerLayer? by mutableStateOf(null)
        internal set

    internal var pipController: AVPictureInPictureController? = null
        private set
    private val surfaceOwnership = IosSurfaceOwnership<AVPlayerLayer>()
    private val pipLayerBindings = mutableListOf<IosPipLayerBinding>()

    internal fun bindPlayerLayer(
        layer: AVPlayerLayer,
        isFullscreen: Boolean,
    ) {
        if (isDisposed) {
            layer.player = null
            return
        }
        layer.player = player
        surfaceOwnership.bind(layer, isFullscreen)
        if (pipLayerBindings.none { it.layer === layer }) {
            pipLayerBindings +=
                IosPipLayerBinding(
                    layer = layer,
                    controller =
                        if (AVPictureInPictureController.isPictureInPictureSupported()) {
                            AVPictureInPictureController(playerLayer = layer)
                        } else {
                            null
                        },
                )
        }
        applyActivePlayerLayer()
    }

    internal fun releasePlayerLayer(layer: AVPlayerLayer) {
        layer.player = null
        surfaceOwnership.release(layer)
        pipLayerBindings.removeAll { it.layer === layer }
        applyActivePlayerLayer()
    }

    private fun applyActivePlayerLayer() {
        val activeLayer = surfaceOwnership.activeOwner
        playerLayer = activeLayer
        pipController = pipLayerBindings.firstOrNull { it.layer === activeLayer }?.controller
        activeLayer?.player = player
        pipController?.setCanStartPictureInPictureAutomaticallyFromInline(_isPipEnabled)
    }

    private fun clearPlayerLayers() {
        surfaceOwnership.clear().forEach { layer -> layer.player = null }
        pipLayerBindings.clear()
        playerLayer = null
        pipController = null
    }

    // Periodic observer for position updates (≈60 fps)
    private var timeObserverToken: Any? = null
    private var timeObserverPlayer: AVPlayer? = null

    // KVO Observers
    private var timeControlStatusObserver: KVOObservation? = null
    private var statusObserver: KVOObservation? = null

    // End-of-playback notification observer
    private var endObserver: Any? = null

    // App lifecycle notification observers
    private var backgroundObserver: Any? = null
    private var foregroundObserver: Any? = null

    // Flag to track if player was playing before going to background
    private var foregroundResumeGeneration: Long? = null
    private var foregroundResumePlayer: AVPlayer? = null
    private var desiredPlayback: Boolean = false

    // Flag to track if the state has been disposed
    private var isDisposed = false
    private var sourceGeneration = 0L
    private var audioSessionLeaseId: Long? = null
    private val playbackEventDispatcher = PlaybackEventDispatcher()
    override val mediaSessionId: Long get() = playbackEventDispatcher.mediaSessionId
    override val playbackEvents = playbackEventDispatcher.events
    private var sourceLoadedSessionId = 0L
    private var wasStalled = false

    init {
        if (cacheConfig.enabled) {
            iosLogger.w {
                "iOS video caching is disabled because AVPlayer does not use an isolated library-owned URL cache"
            }
        }
    }

    private var _currentTime by mutableStateOf(Duration.ZERO)
    private var _duration by mutableStateOf(Duration.ZERO)
    override val currentTime: Duration get() = _currentTime
    override val preciseCurrentTime: Duration
        get() {
            val item = player?.currentItem ?: return _currentTime
            if (item.status != AVPlayerItemStatusReadyToPlay) return _currentTime
            return CMTimeGetSeconds(item.currentTime()).secondsAsDuration()
        }
    override val duration: Duration get() = _duration

    // Observable video aspect ratio (default to 16:9)
    private var _videoAspectRatio by mutableStateOf(16.0 / 9.0)
    val videoAspectRatio: CGFloat
        get() = _videoAspectRatio

    override val aspectRatio: Float get() = _videoAspectRatio.toFloat()

    // Video metadata
    private val _metadata = VideoMetadata(audioChannels = 2)

    private fun ensureNotDisposed() {
        check(!isDisposed) { "VideoPlayerState has been disposed" }
    }

    private fun nextSourceGeneration(): Long {
        sourceGeneration += 1L
        return sourceGeneration
    }

    private fun isCurrentSource(
        generation: Long,
        expectedPlayer: AVPlayer,
        expectedItem: AVPlayerItem? = expectedPlayer.currentItem,
    ): Boolean =
        !isDisposed &&
            sourceGeneration == generation &&
            player === expectedPlayer &&
            (expectedItem == null || expectedPlayer.currentItem === expectedItem)

    private fun ensureAudioSession() {
        audioSessionLeaseId = IosAudioSessionManager.acquire(audioSessionLeaseId, audioMode)
    }

    private fun releaseAudioSession() {
        IosAudioSessionManager.release(audioSessionLeaseId)
        audioSessionLeaseId = null
    }

    private fun resetMetadata() {
        _metadata.title = null
        _metadata.duration = null
        _metadata.width = null
        _metadata.height = null
        _metadata.bitrate = null
        _metadata.frameRate = null
        _metadata.mimeType = null
        _metadata.audioChannels = 2
        _metadata.audioSampleRate = null
    }

    private fun startPositionUpdates(
        player: AVPlayer,
        item: AVPlayerItem,
        generation: Long,
    ) {
        val interval = CMTimeMakeWithSeconds(1.0 / 60.0, NSEC_PER_SEC.toInt()) // ~60 fps
        timeObserverPlayer = player
        timeObserverToken =
            player.addPeriodicTimeObserverForInterval(
                interval = interval,
                queue = dispatch_get_main_queue(),
                usingBlock = block@{ time ->
                    if (!isCurrentSource(generation, player, item)) return@block
                    // Only access item properties when the item is ready to play.
                    // Accessing duration/presentationSize on a failed or loading item
                    // can throw an ObjC NSException (abort).
                    val item = player.currentItem ?: return@block
                    if (item.status != AVPlayerItemStatusReadyToPlay) return@block

                    val currentSeconds = CMTimeGetSeconds(time)
                    val durationSeconds = CMTimeGetSeconds(item.duration)
                    val currentTime = currentSeconds.secondsAsDuration()
                    val duration = durationSeconds.secondsAsDuration()
                    _currentTime = currentTime
                    _duration = duration

                    if (duration > Duration.ZERO) {
                        _metadata.duration = duration
                    }

                    if (!(userDragging || isLoading) &&
                        duration > Duration.ZERO &&
                        currentTime >= Duration.ZERO
                    ) {
                        sliderPos =
                            (
                                (currentTime.toSecondsDouble() / duration.toSecondsDouble()) *
                                    VideoPlayerState.SLIDER_SCALE
                            ).toFloat()
                    }
                    _positionText = formatTime(currentTime)
                    _durationText = formatTime(duration)

                    item.presentationSize.useContents {
                        if (width > 0 && height > 0) {
                            _videoAspectRatio = width / height

                            if (_metadata.width == null ||
                                _metadata.width == 0 ||
                                _metadata.height == null ||
                                _metadata.height == 0
                            ) {
                                _metadata.width = width.toInt()
                                _metadata.height = height.toInt()
                                iosLogger.d {
                                    "Video resolution updated during playback: ${width.toInt()}x${height.toInt()}"
                                }
                            }
                        }
                    }
                },
            )
    }

    @Suppress("ReturnCount")
    override suspend fun enterPip(): PipResult {
        ensureNotDisposed()
        if (!isPipEnabled) return PipResult.NotEnabled
        val currentPlayer = player ?: return PipResult.NotPossible
        if (currentPlayer.currentItem == null) return PipResult.NotPossible
        val currentLayer = playerLayer ?: return PipResult.NotPossible
        if (currentLayer.player !== currentPlayer) return PipResult.NotPossible
        val controller = pipController ?: return PipResult.NotPossible
        if (!isPipSupported) return PipResult.NotSupported
        if (!controller.pictureInPicturePossible) return PipResult.NotPossible
        controller.setCanStartPictureInPictureAutomaticallyFromInline(true)
        controller.startPictureInPicture()
        _isPipActive = controller.pictureInPictureActive
        return PipResult.Success
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

    private fun setupObservers(
        player: AVPlayer,
        item: AVPlayerItem,
        generation: Long,
    ) {
        // KVO for timeControlStatus (Playing, Paused, Loading)
        // Only read primitive/enum values in the callback — accessing ObjC object
        // properties (like reasonForWaitingToPlay) can throw NSExceptions.
        timeControlStatusObserver =
            player.observe("timeControlStatus") { _ ->
                val status = player.timeControlStatus
                dispatch_async(dispatch_get_main_queue()) {
                    if (!isCurrentSource(generation, player, item)) return@dispatch_async
                    when (status) {
                        AVPlayerTimeControlStatusPlaying -> {
                            _isPlaying = true
                            _isLoading = false
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
                        AVPlayerTimeControlStatusPaused -> {
                            _isPlaying = false
                            _isLoading = false
                        }
                        AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> {
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
                    }
                }
            }

        // KVO for status (Ready, Failed)
        // Only capture status here — accessing item.error in the KVO callback
        // throws an ObjC NSException (ForeignException) that crashes the app.
        // Error details are read safely on the main thread.
        statusObserver =
            item.observe("status") { _ ->
                val currentStatus = item.status
                dispatch_async(dispatch_get_main_queue()) {
                    if (!isCurrentSource(generation, player, item)) return@dispatch_async
                    when (currentStatus) {
                        AVPlayerItemStatusReadyToPlay -> {
                            _hasMedia = true
                            _isLoading = false
                            extractMetadata(item)
                            _metadata.duration?.let { duration ->
                                if (duration > Duration.ZERO) {
                                    _duration = duration
                                    _durationText = formatTime(duration)
                                }
                            }
                            if (sourceLoadedSessionId != mediaSessionId && mediaSessionId != 0L) {
                                sourceLoadedSessionId = mediaSessionId
                                emitPlaybackEvent { sessionId, sampledAtMs ->
                                    PlaybackEvent.SourceLoaded(
                                        mediaSessionId = sessionId,
                                        sampledAtMs = sampledAtMs,
                                        duration = _duration,
                                    )
                                }
                            }
                            iosLogger.d { "Player Item Ready" }
                        }
                        AVPlayerItemStatusFailed -> {
                            desiredPlayback = false
                            releaseAudioSession()
                            _isLoading = false
                            _isPlaying = false
                            setError(VideoPlayerError.SourceError("Playback failed"))
                            iosLogger.e { "Player Item Failed" }
                        }
                    }
                }
            }

        // Periodic Time Observer
        startPositionUpdates(player, item, generation)

        // Notification for End of Playback
        endObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (!isCurrentSource(generation, player, item)) return@addObserverForName
                if (_loop) {
                    val zeroTime = CMTimeMake(0, 1)
                    player.seekToTime(
                        time = CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()),
                        toleranceBefore = zeroTime,
                        toleranceAfter = zeroTime,
                    ) { finished ->
                        if (finished) {
                            dispatch_async(dispatch_get_main_queue()) {
                                if (!isCurrentSource(generation, player, item)) return@dispatch_async
                                player.playImmediatelyAtRate(_playbackSpeed)
                                emitPlaybackEvent { sessionId, sampledAtMs ->
                                    PlaybackEvent.PlaybackRestarted(
                                        mediaSessionId = sessionId,
                                        sampledAtMs = sampledAtMs,
                                    )
                                }
                                onRestart?.invoke()
                            }
                        }
                    }
                } else {
                    desiredPlayback = false
                    releaseAudioSession()
                    player.pause()
                    _isPlaying = false
                    emitPlaybackEvent { sessionId, sampledAtMs ->
                        PlaybackEvent.PlaybackEnded(
                            mediaSessionId = sessionId,
                            sampledAtMs = sampledAtMs,
                        )
                    }
                    onPlaybackEnded?.invoke()
                }
            }

        setupAppLifecycleObservers(player, generation)
    }

    private fun stopPositionUpdates() {
        val observerPlayer = timeObserverPlayer
        timeObserverToken?.let { token ->
            observerPlayer?.removeTimeObserver(token)
        }
        timeObserverToken = null
        timeObserverPlayer = null
    }

    private fun setupAppLifecycleObservers(
        observedPlayer: AVPlayer,
        generation: Long,
    ) {
        // Remove any existing observers first
        removeAppLifecycleObservers()

        // Add observer for when app goes to background (screen lock)
        backgroundObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = UIApplication.sharedApplication,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (!isCurrentSource(generation, observedPlayer)) return@addObserverForName
                iosLogger.d { "App entered background (screen locked)" }
                foregroundResumeGeneration = generation.takeIf { desiredPlayback && _isPlaying }
                foregroundResumePlayer = observedPlayer.takeIf { foregroundResumeGeneration != null }
                if (foregroundResumeGeneration != null) {
                    releaseAudioSession()
                }

                // If player is paused by the system, update our state to match
                player?.let { player ->
                    if (player.rate == 0.0f) {
                        iosLogger.d { "Player was paused by system, updating isPlaying state" }
                        _isPlaying = false
                    }
                }
            }

        // Add observer for when app comes to foreground (screen unlock)
        foregroundObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationWillEnterForegroundNotification,
                `object` = UIApplication.sharedApplication,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (!isCurrentSource(generation, observedPlayer)) return@addObserverForName
                iosLogger.d { "App will enter foreground (screen unlocked)" }
                val shouldResume =
                    desiredPlayback &&
                        foregroundResumeGeneration == generation &&
                        foregroundResumePlayer === observedPlayer
                foregroundResumeGeneration = null
                foregroundResumePlayer = null
                if (shouldResume) {
                    iosLogger.d { "Player was playing before background, resuming" }
                    ensureAudioSession()
                    if (observedPlayer.rate == 0.0f) {
                        observedPlayer.playImmediatelyAtRate(_playbackSpeed)
                    }
                }
            }

        iosLogger.d { "App lifecycle observers set up" }
    }

    /**
     * Extracts metadata from a player item once it has reached readyToPlay status.
     * Must be called on the main thread.
     */
    private fun extractMetadata(item: AVPlayerItem) {
        val asset = item.asset
        val durationSeconds = CMTimeGetSeconds(item.duration)
        val duration = durationSeconds.secondsAsDuration()
        if (duration > Duration.ZERO) {
            _metadata.duration = duration
        }

        val videoTracks = asset.tracksWithMediaType(AVMediaTypeVideo)
        if (videoTracks.isNotEmpty()) {
            val videoTrack = videoTracks.firstOrNull() as? AVAssetTrack
            videoTrack?.let { track ->
                val nominalFrameRate = track.nominalFrameRate
                if (nominalFrameRate > 0) {
                    _metadata.frameRate = nominalFrameRate
                }

                val trackBitrate = track.estimatedDataRate
                if (trackBitrate > 0) {
                    _metadata.bitrate = trackBitrate.toLong()
                }

                track.naturalSize.useContents {
                    if (width > 0 && height > 0) {
                        _metadata.width = width.toInt()
                        _metadata.height = height.toInt()
                        _videoAspectRatio = width / height
                        updateAutoDetectedProjection()
                        iosLogger.d { "Video resolution: ${width.toInt()}x${height.toInt()}" }
                    }
                }
            }
        }

        val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
        if (audioTracks.isNotEmpty()) {
            _metadata.audioChannels = 2
            _metadata.audioSampleRate = 44100
        }
    }

    private fun removeAppLifecycleObservers() {
        backgroundObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            backgroundObserver = null
        }

        foregroundObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            foregroundObserver = null
        }
    }

    private fun removeObservers() {
        // Remove KVOs
        timeControlStatusObserver?.invalidate()
        timeControlStatusObserver = null

        statusObserver?.invalidate()
        statusObserver = null

        endObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            endObserver = null
        }

        removeAppLifecycleObservers()
    }

    /**
     * Clean up all resources associated with the current player
     */
    private fun cleanupCurrentPlayer() {
        stopPositionUpdates()
        removeObservers()
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
    }

    /**
     * Opens a media source from the given URI.
     *
     * IMPORTANT: iOS AVPlayer has a tendency to auto-play when certain properties are set.
     * To ensure proper behavior with InitialPlayerState.PAUSE, we need to:
     * 1. Explicitly call pause() on the player
     * 2. Set rate to 0
     * 3. Not set rate during initialization
     * 4. Update all relevant state variables
     *
     * @param uri The URI of the media to open
     * @param initializePlayerState Controls whether playback should start automatically after opening
     */
    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureNotDisposed()
        iosLogger.d { "openUri called with uri: $uri, initializePlayerState: $initializePlayerState" }
        val generation = nextSourceGeneration()
        val previousSessionId = mediaSessionId
        val hadPreviousSource = _hasMedia || player != null
        val sessionId = nextMediaSessionId()
        sourceLoadedSessionId = 0L
        wasStalled = false
        if (hadPreviousSource) {
            emitSourceReleasedForSession(previousSessionId)
        }
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.SourcePreparing(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
                uri = uri,
            )
        }
        clearErrorState()
        resetProjectionForSource(uri)
        desiredPlayback = initializePlayerState == InitialPlayerState.PLAY
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        releaseAudioSession()

        _playbackSpeed = 1.0f
        _isLoading = true
        resetMetadata()
        _hasMedia = false

        cleanupCurrentPlayer()

        val nsUrl =
            NSURL.URLWithString(uri) ?: run {
                iosLogger.d { "Failed to create NSURL from uri: $uri" }
                desiredPlayback = false
                _isLoading = false
                setError(VideoPlayerError.SourceError("Invalid media source: $uri"))
                return
            }

        // AVPlayer handles async loading internally — metadata is extracted
        // safely in the KVO readyToPlay callback, avoiding ObjC exceptions
        // from accessing track properties on an unloaded/failed asset.
        val asset = AVURLAsset.URLAssetWithURL(nsUrl, requestHeaders.avAssetOptions())
        val playerItem = AVPlayerItem(asset)

        nsUrl.lastPathComponent?.let { _metadata.title = it }
        updateAutoDetectedProjection()

        val newPlayer =
            AVPlayer(playerItem = playerItem).apply {
                volume = this@DefaultVideoPlayerState.volume
                actionAtItemEnd = AVPlayerActionAtItemEndNone
                automaticallyWaitsToMinimizeStalling = true
                allowsExternalPlayback = false
            }

        player = newPlayer

        setupObservers(newPlayer, playerItem, generation)

        if (initializePlayerState == InitialPlayerState.PLAY) {
            play()
        } else {
            newPlayer.pause()
        }
    }

    private fun Map<String, String>.avAssetOptions(): Map<Any?, *>? {
        val headers = sanitizedRequestHeaders()
        if (headers.isEmpty()) return null
        return mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to headers)
    }

    override fun play() {
        ensureNotDisposed()
        iosLogger.d { "play called" }
        val currentPlayer =
            player ?: run {
                iosLogger.d { "play: player is null" }
                return
            }
        val generation = sourceGeneration
        val currentItem = currentPlayer.currentItem
        desiredPlayback = true
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        _hasMedia = currentItem != null
        ensureAudioSession()

        // Only access item timing properties when ready — ObjC throws on failed items
        if (currentItem != null && currentItem.status == AVPlayerItemStatusReadyToPlay) {
            val currentTime = CMTimeGetSeconds(currentItem.currentTime())
            val duration = CMTimeGetSeconds(currentItem.duration)
            if (duration > 0 && currentTime >= duration) {
                val zeroTime = CMTimeMake(0, 1)
                currentPlayer.seekToTime(
                    time = CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()),
                    toleranceBefore = zeroTime,
                    toleranceAfter = zeroTime,
                ) { finished ->
                    if (finished) {
                        dispatch_async(dispatch_get_main_queue()) {
                            if (!isCurrentSource(generation, currentPlayer, currentItem) || !desiredPlayback) {
                                return@dispatch_async
                            }
                            currentPlayer.playImmediatelyAtRate(_playbackSpeed)
                        }
                    }
                }
                return
            }
        }
        currentPlayer.playImmediatelyAtRate(_playbackSpeed)
    }

    override fun restart() {
        ensureNotDisposed()
        iosLogger.d { "restart called" }
        val currentPlayer = player ?: return
        val currentItem = currentPlayer.currentItem ?: return
        val generation = sourceGeneration
        desiredPlayback = true
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        _hasMedia = true
        ensureAudioSession()
        val zeroTime = CMTimeMake(0, 1)
        currentPlayer.seekToTime(
            time = CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()),
            toleranceBefore = zeroTime,
            toleranceAfter = zeroTime,
        ) { finished ->
            if (finished) {
                dispatch_async(dispatch_get_main_queue()) {
                    if (!isCurrentSource(generation, currentPlayer, currentItem) || !desiredPlayback) {
                        return@dispatch_async
                    }
                    currentPlayer.playImmediatelyAtRate(_playbackSpeed)
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
        ensureNotDisposed()
        iosLogger.d { "pause called" }
        desiredPlayback = false
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        player?.pause()
        releaseAudioSession()
        // KVO will update isPlaying
    }

    override fun stop() {
        ensureNotDisposed()
        iosLogger.d { "stop called" }
        desiredPlayback = false
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        releaseAudioSession()
        player?.pause()
        player?.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
        _isPlaying = false
        _isLoading = false
        _isSeeking = false
        _hasMedia = false
        _currentTime = Duration.ZERO
        _duration = Duration.ZERO
        _positionText = "00:00"
        _durationText = "00:00"
        wasStalled = false
    }

    override fun releaseSource() {
        ensureNotDisposed()
        val releasedSessionId = mediaSessionId
        val hadSource = player != null || _hasMedia
        nextSourceGeneration()
        desiredPlayback = false
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        releaseAudioSession()
        cleanupCurrentPlayer()
        _isPlaying = false
        _isLoading = false
        _isSeeking = false
        _hasMedia = false
        _currentTime = Duration.ZERO
        _duration = Duration.ZERO
        _positionText = "00:00"
        _durationText = "00:00"
        sliderPos = 0f
        resetMetadata()
        sourceLoadedSessionId = 0L
        wasStalled = false
        if (hadSource) {
            emitSourceReleasedForSession(releasedSessionId)
            nextMediaSessionId()
        }
    }

    override fun seekTo(time: Duration) {
        ensureNotDisposed()
        val currentPlayer = player ?: return
        if (_duration > Duration.ZERO) {
            val targetTime =
                when {
                    time < Duration.ZERO -> Duration.ZERO
                    time > _duration -> _duration
                    else -> time
                }.toSecondsDouble()
            seekToSeconds(currentPlayer, targetTime)
        }
    }

    override fun seekToProgress(progress: Float) {
        ensureNotDisposed()
        if (_duration > Duration.ZERO) {
            seekTo(_duration * progress.coerceIn(0f, 1f).toDouble())
        }
    }

    override fun seekStart(value: Float) {
        ensureNotDisposed()
        userDragging = true
        sliderPos = value
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        ensureNotDisposed()
        val currentPlayer = player ?: return
        if (_duration > Duration.ZERO) {
            val targetTime =
                (_duration * (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0))
                    .toSecondsDouble()
            seekToSeconds(currentPlayer, targetTime)
        }
    }

    private fun seekToSeconds(
        currentPlayer: AVPlayer,
        targetTime: Double,
    ) {
        if (_duration > Duration.ZERO) {
            val currentItem = currentPlayer.currentItem ?: return
            val generation = sourceGeneration
            _isLoading = true
            _isSeeking = true
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.SeekStarted(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    target = targetTime.secondsAsDuration(),
                )
            }

            val seekTime = CMTimeMakeWithSeconds(targetTime, NSEC_PER_SEC.toInt())
            val wasPlaying = _isPlaying

            // Create a zero time value for tolerance to ensure precise seeking
            val zeroTime = CMTimeMake(0, 1)

            currentPlayer.seekToTime(
                time = seekTime,
                toleranceBefore = zeroTime,
                toleranceAfter = zeroTime,
            ) { finished ->
                if (finished) {
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!isCurrentSource(generation, currentPlayer, currentItem)) return@dispatch_async
                        _isLoading = false
                        _isSeeking = false
                        emitPlaybackEvent { sessionId, sampledAtMs ->
                            PlaybackEvent.SeekCompleted(
                                mediaSessionId = sessionId,
                                sampledAtMs = sampledAtMs,
                                position = targetTime.secondsAsDuration(),
                            )
                        }
                        if (wasPlaying && desiredPlayback) {
                            currentPlayer.playImmediatelyAtRate(_playbackSpeed)
                        }
                    }
                }
            }
        }
    }

    override fun clearError() {
        ensureNotDisposed()
        iosLogger.d { "clearError called" }
        clearErrorState()
    }

    override fun clearCache(): CacheClearResult {
        ensureNotDisposed()
        return CacheClearResult.NotSupported
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        ensureNotDisposed()
        iosLogger.d { "toggleFullscreen called" }
        _isFullscreen = !_isFullscreen
    }

    override fun dispose() {
        if (isDisposed) return
        iosLogger.d { "dispose called" }
        val releasedSessionId = mediaSessionId
        val hadMedia = _hasMedia || player != null
        isDisposed = true
        nextSourceGeneration()
        desiredPlayback = false
        foregroundResumeGeneration = null
        foregroundResumePlayer = null
        releaseAudioSession()
        cleanupCurrentPlayer()
        clearPlayerLayers()
        _isPipActive = false
        _isPipEnabled = false
        _hasMedia = false
        _isPlaying = false
        _isLoading = false
        _isSeeking = false
        sourceLoadedSessionId = 0L
        wasStalled = false
        if (hadMedia) {
            emitSourceReleasedForSession(releasedSessionId)
        }

        resetMetadata()
        playbackEndedCallback = null
        restartCallback = null
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureNotDisposed()
        iosLogger.d { "openFile called with file: $file, initializePlayerState: $initializePlayerState" }
        val fileUrl = file.getUri()
        iosLogger.d { "Opening file with URL: $fileUrl" }
        openUri(fileUrl, initializePlayerState)
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureNotDisposed()
        val assetPath = fileName.normalizedAssetPath()
        val directory = assetPath.substringBeforeLast("/", missingDelimiterValue = "").ifEmpty { null }
        val leafName = assetPath.substringAfterLast("/")
        val name = leafName.substringBeforeLast(".")
        val ext = leafName.substringAfterLast(".", "")
        val bundle = platform.Foundation.NSBundle.mainBundle
        val path =
            if (directory == null) {
                bundle.pathForResource(name, ext.ifEmpty { null })
            } else {
                bundle.pathForResource(name, ext.ifEmpty { null }, directory)
            }
                ?: throw IllegalArgumentException("Asset not found in app bundle: $fileName")
        openUri("file://$path", initializePlayerState)
    }

    override val metadata: VideoMetadata
        get() = _metadata

    private fun resetProjectionForSource(uri: String) {
        projectionSourceUri = uri
        projectionAutoDetectionEnabled = playbackOptions.usesAutoProjectionDetection()
        applyProjectionSettings(playbackOptions.detectProjectionForSource(uri))
    }

    private fun updateAutoDetectedProjection() {
        if (!projectionAutoDetectionEnabled) return
        applyProjectionSettings(
            playbackOptions.detectProjectionForSource(
                uri = projectionSourceUri,
                title = metadata.title,
                metadata = emptyList(),
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
        renderingInfo.videoRenderer = projection.iosVideoRendererLabel(projectionTextureCrop)
        renderingInfo.notes = null
    }

    // Audio track state. Native AVFoundation track selection can be added here later;
    // for now the common API exposes an empty list on iOS.
    private var _currentAudioTrack by mutableStateOf<AudioTrack?>(null)
    override var currentAudioTrack: AudioTrack?
        get() = _currentAudioTrack
        set(value) {
            ensureNotDisposed()
            _currentAudioTrack = value
        }

    private val _availableAudioTracks = mutableStateListOf<AudioTrack>()
    override val availableAudioTracks: List<AudioTrack>
        get() = _availableAudioTracks

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        ensureNotDisposed()
        if (track != null) return TrackSelectionResult.NotSupported

        _currentAudioTrack = track
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

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult {
        ensureNotDisposed()
        return trackId
            ?.let { id ->
                availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)
    }

    // Subtitle state
    private var _subtitlesEnabled by mutableStateOf(false)
    override var subtitlesEnabled: Boolean
        get() = _subtitlesEnabled
        set(value) {
            ensureNotDisposed()
            _subtitlesEnabled = value
        }

    private var _currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override var currentSubtitleTrack: SubtitleTrack?
        get() = _currentSubtitleTrack
        set(value) {
            ensureNotDisposed()
            _currentSubtitleTrack = value
        }

    private val _availableSubtitleTracks = mutableStateListOf<SubtitleTrack>()
    override val availableSubtitleTracks: List<SubtitleTrack>
        get() = _availableSubtitleTracks

    private var _subtitleTextStyle: TextStyle =
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    override var subtitleTextStyle: TextStyle
        get() = _subtitleTextStyle
        set(value) {
            ensureNotDisposed()
            _subtitleTextStyle = value
        }

    private var _subtitleBackgroundColor: Color = Color.Black.copy(alpha = 0.5f)
    override var subtitleBackgroundColor: Color
        get() = _subtitleBackgroundColor
        set(value) {
            ensureNotDisposed()
            _subtitleBackgroundColor = value
        }
    private var _subtitleOffset: Duration by mutableStateOf(Duration.ZERO)
    override var subtitleOffset: Duration
        get() = _subtitleOffset
        set(value) {
            ensureNotDisposed()
            _subtitleOffset = value
        }

    /**
     * Selects a subtitle track for display.
     * If track is null, disables subtitles.
     *
     * @param track The subtitle track to select, or null to disable subtitles
     */
    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        ensureNotDisposed()
        iosLogger.d { "selectSubtitleTrack called with track: $track" }
        if (track == null) {
            return disableSubtitles()
        }
        if (track.isEmbedded && availableSubtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }

        // Update current track and enable flag
        currentSubtitleTrack = track
        subtitlesEnabled = true
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.SUBTITLE,
                trackId = track.id,
            )
        }

        // iOS uses Compose-based subtitles, so we don't need to configure
        // the native player for subtitle display
        return TrackSelectionResult.Selected(track.id)
    }

    override fun selectSubtitleTrack(trackId: String?): TrackSelectionResult {
        ensureNotDisposed()
        return trackId
            ?.let { id ->
                availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectSubtitleTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectSubtitleTrack(null as SubtitleTrack?)
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        ensureNotDisposed()
        val externalTrack = track.copy(isEmbedded = false)
        _availableSubtitleTracks.removeAll { it.id == externalTrack.id }
        _availableSubtitleTracks.add(externalTrack)
    }

    override fun removeSubtitleTrack(trackId: String) {
        ensureNotDisposed()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            disableSubtitles()
        }
    }

    override fun clearExternalSubtitleTracks() {
        ensureNotDisposed()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.isExternal }
        if (selectedTrack?.isExternal == true) {
            disableSubtitles()
        }
    }

    /**
     * Disables subtitle display.
     */
    override fun disableSubtitles(): TrackSelectionResult {
        ensureNotDisposed()
        iosLogger.d { "disableSubtitles called" }
        // Update state
        currentSubtitleTrack = null
        subtitlesEnabled = false
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.SUBTITLE,
                trackId = null,
            )
        }

        // iOS uses Compose-based subtitles, so we don't need to configure
        // the native player for subtitle display
        return TrackSelectionResult.Disabled
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        ensureNotDisposed()
        return HlsQualitySelectionResult.NotSupported
    }

    override fun selectAutoHlsQuality(): HlsQualitySelectionResult {
        ensureNotDisposed()
        return selectHlsQuality(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class KVOObserver(
    private val block: (Any?) -> Unit,
) : NSObject(),
    NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        block(change?.get(NSKeyValueChangeNewKey))
    }
}

private class KVOObservation(
    private val observedObject: NSObject,
    private val observer: NSObject,
    private val keyPath: String,
) {
    private var invalidated = false

    fun invalidate() {
        if (invalidated) return
        observedObject.removeObserver(observer, forKeyPath = keyPath)
        invalidated = true
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSObject.observe(
    keyPath: String,
    options: NSKeyValueObservingOptions = NSKeyValueObservingOptionNew,
    block: (Any?) -> Unit,
): KVOObservation {
    val observer = KVOObserver(block)
    this.addObserver(
        observer,
        forKeyPath = keyPath,
        options = options,
        context = null,
    )
    return KVOObservation(
        observedObject = this,
        observer = observer,
        keyPath = keyPath,
    )
}
