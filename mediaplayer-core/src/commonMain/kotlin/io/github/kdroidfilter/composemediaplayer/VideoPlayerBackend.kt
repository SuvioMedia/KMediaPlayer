package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Marks contracts intended for backend implementations rather than regular player consumers.
 *
 * Backend authors may use these APIs to integrate a renderer without depending on the
 * platform-player module. The contract may grow before the next major release, but existing
 * members will remain source compatible within a release line.
 */
@RequiresOptIn(
    message = "This API is intended for Compose Media Player backend implementations.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class ExperimentalComposeMediaPlayerBackendApi

/**
 * Rendering extension point implemented by optional backend modules.
 *
 * The default `composemediaplayer` artifact checks this contract before using
 * its platform renderer, so it never needs to import a concrete extension.
 */
@Stable
@ExperimentalComposeMediaPlayerBackendApi
interface VideoPlayerSurfaceProvider {
    @Composable
    fun RenderVideoPlayerSurface(
        modifier: Modifier = Modifier,
        contentScale: ContentScale = ContentScale.Fit,
        overlay: @Composable () -> Unit = {},
    )
}

/**
 * Renders a state supplied by an optional backend without pulling the default-player module.
 *
 * This is the minimal surface entry point for applications that select an optional backend
 * exclusively. Applications that also use `composemediaplayer` may keep using its
 * `VideoPlayerSurface`, which delegates to the same provider contract.
 */
@Composable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
fun BackendVideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
) {
    require(playerState is VideoPlayerSurfaceProvider) {
        "The player state does not provide a backend-owned video surface: ${playerState::class}"
    }
    playerState.RenderVideoPlayerSurface(
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
    )
}

/**
 * Factory boundary used by dependency injection and optional backend modules.
 *
 * Applications can select a backend in their composition root without making
 * `composemediaplayer` depend on every available implementation.
 */
@Stable
interface VideoPlayerBackend {
    val info: VideoPlayerBackendInfo

    fun createPlayerState(): VideoPlayerState
}

/**
 * Remembers a state created by [backend] and owns its lifecycle.
 */
@Composable
fun rememberVideoPlayerState(backend: VideoPlayerBackend): VideoPlayerState {
    val state = remember(backend) { backend.createPlayerState() }
    DisposableEffect(state) {
        onDispose(state::dispose)
    }
    return state
}

/**
 * Stable identity and runtime availability reported by an optional backend.
 */
@Stable
data class VideoPlayerBackendInfo(
    val id: String,
    val displayName: String,
    val capabilities: PlayerCapabilities,
) {
    init {
        require(id.isNotBlank()) { "Backend id must not be blank." }
        require(displayName.isNotBlank()) { "Backend display name must not be blank." }
    }
}
