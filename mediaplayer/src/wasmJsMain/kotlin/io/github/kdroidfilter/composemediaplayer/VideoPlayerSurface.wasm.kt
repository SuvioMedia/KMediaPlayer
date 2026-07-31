@file:OptIn(ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.HtmlElementView
import io.github.shusek.kmedia.engine.wasm.PlayerSurface
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    if (playerState is PreviewableVideoPlayerState) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }
    if (playerState is VideoPlayerSurfaceProvider) {
        playerState.RenderVideoPlayerSurface(
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
        return
    }
    require(playerState is DefaultVideoPlayerState) {
        "Unsupported video player state: ${playerState::class}"
    }
    WasmEngineVideoPlayerSurface(playerState, modifier, contentScale, overlay)
}

@Composable
private fun WasmEngineVideoPlayerSurface(
    playerState: DefaultVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    if (!playerState.hasMedia) return

    val mediaSessionId = playerState.mediaSessionId
    val sourceUri = playerState.sourceUri
    val subtitleTrack = playerState.currentSubtitleTrack
    val subtitleExtension =
        playerState
            .activeWebSubtitleExtension(subtitleTrack)
            .takeIf { subtitleTrack?.isEmbedded != true }
    val scope = rememberCoroutineScope()
    var styledSubtitleActive by
        remember(mediaSessionId, subtitleTrack?.id, subtitleExtension?.id) {
            mutableStateOf(false)
        }
    var containerElement by remember(mediaSessionId) { mutableStateOf<HTMLElement?>(null) }
    var engineSurface by remember(mediaSessionId) { mutableStateOf<PlayerSurface?>(null) }
    var session by remember(mediaSessionId) { mutableStateOf<WasmEnginePlaybackSession?>(null) }
    var videoRatio by remember(mediaSessionId) { mutableStateOf<Float?>(null) }

    WebProjectionDeviceMotionEffect(
        playerState = playerState,
        enabled = playerState.projection.requiresProjectionRenderer,
    )

    DisposableEffect(playerState, mediaSessionId, sourceUri, containerElement) {
        containerElement ?: return@DisposableEffect onDispose {}
        val playableUri = sourceUri ?: return@DisposableEffect onDispose {}
        val createdSession =
            WasmEnginePlaybackSession(
                playerState = playerState,
                mediaSessionId = mediaSessionId,
                onSurface = { surface ->
                    if (playerState.isCurrentMediaSession(mediaSessionId)) {
                        engineSurface = surface
                    }
                },
                onVideoRatio = { ratio ->
                    if (playerState.isCurrentMediaSession(mediaSessionId)) {
                        videoRatio = ratio
                    }
                },
            )
        session = createdSession
        val loadJob =
            scope.launch {
                createdSession.load(
                    sourceUri = playableUri,
                    sourceMimeType = playerState.sourceMimeType,
                    sourceFile = playerState.sourceFile,
                    mediaHeaders = playerState.requestHeaders,
                    drmConfiguration = playerState.playbackOptions.webDrmConfiguration,
                )
            }
        onDispose {
            loadJob.cancel()
            createdSession.destroy()
            if (session === createdSession) session = null
            engineSurface = null
        }
    }

    DisposableEffect(containerElement, engineSurface, contentScale) {
        val container = containerElement
        val surface = engineSurface
        if (container != null) {
            container.clearEngineSurface()
            when (surface) {
                is PlayerSurface.NativeVideo -> {
                    surface.element.configureAsEngineVideo(contentScale, visible = true)
                    container.appendChild(surface.element)
                }
                is PlayerSurface.Canvas -> {
                    surface.element.applyEngineCanvasContentScale(contentScale)
                    container.appendChild(surface.element)
                    surface.mediaElement?.let { timingElement ->
                        timingElement.configureAsEngineVideo(contentScale, visible = false)
                        container.appendChild(timingElement)
                    }
                }
                null -> Unit
            }
        }
        onDispose {}
    }

    ApplyWasmEngineSessionEffects(session, playerState)
    SideEffect {
        session?.applyContentScale(contentScale)
        (engineSurface as? PlayerSurface.Canvas)?.element?.applyEngineCanvasContentScale(contentScale)
    }

    val videoElement =
        when (val surface = engineSurface) {
            is PlayerSurface.Canvas -> surface.mediaElement
            is PlayerSurface.NativeVideo -> surface.element
            null -> null
        }
    val displayElement: HTMLElement? =
        when (val surface = engineSurface) {
            is PlayerSurface.Canvas -> surface.element
            is PlayerSurface.NativeVideo -> surface.element
            null -> null
        }

    VideoContentLayout(
        playerState = playerState,
        modifier = modifier,
        videoRatio = videoRatio,
        contentScale = contentScale,
        suppressComposeAss = styledSubtitleActive,
        overlay = overlay,
    ) {
        key(mediaSessionId) {
            HtmlElementView(
                factory = ::createEngineSurfaceElement,
                modifier = Modifier.fillMaxSize(),
                update = { container ->
                    containerElement = container
                    container.applyEngineSurfaceStyle()
                },
                onRelease = { container ->
                    if (containerElement === container) containerElement = null
                    container.clearEngineSurface()
                },
            )
            subtitleExtension?.SubtitleOverlay(
                playerState = playerState,
                videoElement = videoElement,
                displayElement = displayElement,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onActiveChanged = { active -> styledSubtitleActive = active },
            )
        }
    }
}

@Composable
private fun ApplyWasmEngineSessionEffects(
    session: WasmEnginePlaybackSession?,
    playerState: DefaultVideoPlayerState,
) {
    LaunchedEffect(session, playerState.seekRequestId) {
        session?.seekPending()
    }
    LaunchedEffect(session, playerState.subtitleOffset) {
        session?.applySubtitleOffset(playerState.subtitleOffset)
    }
    LaunchedEffect(
        session,
        playerState.projection,
        playerState.projectionView,
        playerState.projectionTextureCrop,
    ) {
        session?.applyProjection(
            playerState.projection,
            playerState.projectionView,
            playerState.projectionTextureCrop,
        )
    }
}

private fun DefaultVideoPlayerState.activeWebSubtitleExtension(
    subtitleTrack: SubtitleTrack?,
): WebSubtitlePipelineExtension? =
    webSubtitlePipelineExtensions.firstOrNull { extension ->
        subtitleTrack?.resolvedFormat()?.let(extension::supportsSubtitleFormat) == true
    }

private fun createEngineSurfaceElement(): HTMLElement =
    (document.createElement("div") as HTMLElement).apply {
        className = "compose-media-player-kmedia-wasm"
        applyEngineSurfaceStyle()
    }

private fun HTMLElement.applyEngineSurfaceStyle() {
    style.apply {
        position = "relative"
        width = "100%"
        height = "100%"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        setProperty("overflow", "hidden")
        backgroundColor = "black"
        setProperty("pointer-events", "none")
        setProperty("contain", "strict", "important")
    }
    (parentElement as? HTMLElement)?.style?.apply {
        setProperty("z-index", "-2", "important")
        setProperty("pointer-events", "none")
        setProperty("overflow", "hidden", "important")
    }
}

private fun HTMLCanvasElement.applyEngineCanvasContentScale(contentScale: ContentScale) {
    style.apply {
        width = "100%"
        height = "100%"
        display = "block"
        backgroundColor = "black"
        setProperty("pointer-events", "none")
        objectFit = contentScale.toCssObjectFit()
    }
}

private fun HTMLVideoElement.configureAsEngineVideo(
    contentScale: ContentScale,
    visible: Boolean,
) {
    controls = false
    setAttribute("playsinline", "")
    setAttribute("webkit-playsinline", "")
    style.apply {
        position = "absolute"
        left = "0"
        top = "0"
        width = "100%"
        height = "100%"
        display = "block"
        opacity = if (visible) "1" else "0"
        setProperty("z-index", if (visible) "0" else "-1")
        backgroundColor = "black"
        setProperty("pointer-events", "none")
        objectFit = contentScale.toCssObjectFit()
    }
}

private fun ContentScale.toCssObjectFit(): String =
    when (this) {
        ContentScale.Crop -> "cover"
        ContentScale.FillBounds -> "fill"
        else -> "contain"
    }

private fun HTMLElement.clearEngineSurface() {
    while (firstChild != null) {
        removeChild(firstChild ?: break)
    }
}
