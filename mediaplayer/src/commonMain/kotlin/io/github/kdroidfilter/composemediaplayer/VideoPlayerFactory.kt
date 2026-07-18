@file:JvmName("VideoPlayerStateKt")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import kotlin.jvm.JvmName

/**
 * Creates the default platform-specific video player state.
 *
 * Alternative backends are published as separate modules and expose their own
 * factory functions while sharing the contracts from `composemediaplayer-core`.
 */
expect fun createVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): VideoPlayerState

/**
 * Creates an injectable descriptor for the default platform backend.
 */
fun defaultVideoPlayerBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): VideoPlayerBackend =
    DefaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions = playbackOptions,
    )

/**
 * Creates a default platform-backed state accepted by the strict surface overload.
 */
fun createRenderableVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): RenderableVideoPlayerState =
    createVideoPlayerState(audioMode, cacheConfig, playbackOptions).asRenderableVideoPlayerState()

/**
 * Creates and remembers the default platform player and disposes it with the composition.
 */
@Composable
fun rememberVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): VideoPlayerState {
    val playerState =
        remember(audioMode, cacheConfig, playbackOptions) {
            createVideoPlayerState(audioMode, cacheConfig, playbackOptions)
        }
    DisposableEffect(playerState) {
        onDispose(playerState::dispose)
    }
    return playerState
}

/**
 * Remembers a default platform-backed state accepted by the strict surface overload.
 */
@Composable
fun rememberRenderableVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): RenderableVideoPlayerState {
    val playerState = rememberVideoPlayerState(audioMode, cacheConfig, playbackOptions)
    return remember(playerState) { playerState.asRenderableVideoPlayerState() }
}

internal fun VideoPlayerState.asRenderableVideoPlayerState(): RenderableVideoPlayerState =
    this as? RenderableVideoPlayerState ?: RenderableVideoPlayerState(this)

/*
 * JVM linkage bridges for the public top-level helpers that moved to
 * composemediaplayer-core. Their Kotlin declarations remain in core, while these
 * differently named internal functions preserve the v1.x VideoPlayerStateKt methods.
 */
@JvmName("normalizedAssetPath")
internal fun String.compatNormalizedAssetPath(): String = normalizedAssetPath()

@JvmName("normalizedAssetUri")
internal fun String.compatNormalizedAssetUri(): String = normalizedAssetUri()

@JvmName("sanitizedRequestHeaders")
internal fun Map<String, String>.compatSanitizedRequestHeaders(): Map<String, String> = sanitizedRequestHeaders()

@JvmName("requestHeadersLineString")
internal fun Map<String, String>.compatRequestHeadersLineString(lineSeparator: String = "\r\n"): String =
    requestHeadersLineString(lineSeparator)

@JvmName("requestHeadersJsonObjectString")
internal fun Map<String, String>.compatRequestHeadersJsonObjectString(): String = requestHeadersJsonObjectString()

@Stable
private data class DefaultVideoPlayerBackend(
    val audioMode: AudioMode,
    val cacheConfig: CacheConfig,
    val playbackOptions: VideoPlaybackOptions,
) : VideoPlayerBackend {
    override val info =
        VideoPlayerBackendInfo(
            id = "default",
            displayName = "Default platform backend",
            capabilities = platformPlayerCapabilities(playbackOptions),
        )

    override fun createPlayerState(): VideoPlayerState =
        createVideoPlayerState(
            audioMode = audioMode,
            cacheConfig = cacheConfig,
            playbackOptions = playbackOptions,
        )
}
