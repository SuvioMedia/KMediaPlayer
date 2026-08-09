package io.github.kdroidfilter.composemediaplayer

import kotlin.time.Duration

/** Minimal audio-only transport used by platforms that cannot merge media sources natively. */
internal interface ExternalAudioPlaybackEngine {
    val isReady: Boolean

    val isPlaying: Boolean

    val currentTime: Duration

    val hasError: Boolean

    fun prepare(
        track: ExternalAudioTrack,
        position: Duration,
        volume: Float,
        playbackSpeed: Float,
        loop: Boolean,
    )

    fun play()

    fun pause()

    fun seekTo(time: Duration)

    fun setVolume(value: Float)

    fun setPlaybackSpeed(value: Float)

    fun setLoop(value: Boolean)

    fun release()

    fun dispose()
}

/** Creates the platform's headless audio transport for decorated optional backends. */
internal expect fun createPlatformExternalAudioPlaybackEngine(): ExternalAudioPlaybackEngine

/** Uses a second platform player as a headless audio transport. */
internal class VideoPlayerStateExternalAudioPlaybackEngine(
    private val playerState: VideoPlayerState,
) : ExternalAudioPlaybackEngine {
    override val isReady: Boolean
        get() = playerState.hasMedia && !playerState.isLoading && playerState.error == null

    override val isPlaying: Boolean
        get() = playerState.isPlaying

    override val currentTime: Duration
        get() = playerState.preciseCurrentTime

    override val hasError: Boolean
        get() = playerState.error != null

    override fun prepare(
        track: ExternalAudioTrack,
        position: Duration,
        volume: Float,
        playbackSpeed: Float,
        loop: Boolean,
    ) {
        playerState.openSource(
            source = track.source,
            initializePlayerState = InitialPlayerState.PAUSE,
            requestHeaders = track.requestHeaders,
        )
        playerState.volume = volume
        playerState.playbackSpeed = playbackSpeed
        playerState.loop = loop
        playerState.seekTo(position)
    }

    override fun play() = playerState.play()

    override fun pause() = playerState.pause()

    override fun seekTo(time: Duration) = playerState.seekTo(time)

    override fun setVolume(value: Float) {
        playerState.volume = value
    }

    override fun setPlaybackSpeed(value: Float) {
        playerState.playbackSpeed = value
    }

    override fun setLoop(value: Boolean) {
        playerState.loop = value
    }

    override fun release() {
        playerState.releaseSource()
    }

    override fun dispose() {
        playerState.dispose()
    }
}
