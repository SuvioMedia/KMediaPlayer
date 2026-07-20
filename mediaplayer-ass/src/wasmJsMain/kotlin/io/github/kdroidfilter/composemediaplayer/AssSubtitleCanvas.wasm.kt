@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.coroutines.isActive
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.DurationUnit

@Composable
internal fun AssSubtitleCanvas(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    displayElement: HTMLElement?,
    contentScale: ContentScale,
    modifier: Modifier,
    config: AssSubtitleRendererConfig,
    onActiveChanged: (Boolean) -> Unit,
) {
    val subtitleTrack = playerState.currentSubtitleTrack
    val shouldRenderAss =
        config.enabled &&
            playerState.subtitlesEnabled &&
            subtitleTrack?.resolvedFormat()?.isAssFamily == true &&
            subtitleTrack.src.isNotBlank() &&
            displayElement != null

    if (!shouldRenderAss) {
        SideEffect { onActiveChanged(false) }
        return
    }
    val video = videoElement
    val display = displayElement
    val track = subtitleTrack
    var canvasGeneration by
        remember(video, track.id, config) {
            mutableStateOf(0)
        }
    var canvasElement by
        remember(video, track.id, canvasGeneration) {
            mutableStateOf<HTMLCanvasElement?>(null)
        }
    var session by
        remember(video, track.id, canvasGeneration) {
            mutableStateOf<JsAny?>(null)
        }

    key(track.id, canvasGeneration) {
        HtmlElementView(
            factory = { createAssSubtitleCanvasElement() },
            modifier = if (playerState.isFullscreen) Modifier.fillMaxSize() else modifier,
            update = { canvas ->
                canvasElement = canvas
                canvas.applyAssSubtitleCanvasStyle()
            },
            onRelease = { canvas ->
                if (canvasElement === canvas) {
                    canvasElement = null
                }
                clearAssSubtitleCanvas(canvas)
            },
        )
    }

    DisposableEffect(video, canvasElement, track.id, canvasGeneration, config) {
        val canvas = canvasElement
        if (canvas == null) {
            onDispose {}
        } else {
            var disposed = false
            var createdSession: JsAny? = null
            createdSession =
                createAssSubtitleRendererSession(
                    video = video,
                    canvas = canvas,
                    displayElement = display,
                    subUrl = track.src,
                    config = config,
                    timeOffsetSeconds = playerState.subtitleOffset.toDouble(DurationUnit.SECONDS),
                    contentScaleMode = contentScale.toAssSubtitleContentScaleMode(),
                    onReady = {
                        if (!disposed && session === createdSession) {
                            onActiveChanged(true)
                        }
                    },
                    onError = { message ->
                        if (!disposed && (createdSession == null || session === createdSession)) {
                            onActiveChanged(false)
                            println("KMediaPlayer ASS: subtitle renderer error: $message")
                        }
                    },
                    onRestartRequired = {
                        if (!disposed && session === createdSession) {
                            onActiveChanged(false)
                            canvasGeneration += 1
                        }
                    },
                )
            session = createdSession

            onDispose {
                disposed = true
                onActiveChanged(false)
                if (session === createdSession) {
                    session = null
                }
                disposeAssSubtitleRendererSession(createdSession)
            }
        }
    }

    LaunchedEffect(session, track.src) {
        updateAssSubtitleRendererSource(session, track.src)
    }

    LaunchedEffect(session, playerState.subtitleOffset) {
        updateAssSubtitleRendererOffset(
            session = session,
            timeOffsetSeconds = playerState.subtitleOffset.toDouble(DurationUnit.SECONDS),
        )
    }

    LaunchedEffect(session, display, contentScale, playerState.isFullscreen) {
        updateAssSubtitleRendererLayout(
            session = session,
            displayElement = display,
            contentScaleMode = contentScale.toAssSubtitleContentScaleMode(),
        )
    }

    AssSubtitleManualRenderEffects(
        playerState = playerState,
        session = session,
        video = video,
        display = display,
        contentScale = contentScale,
    )
}

@Composable
private fun AssSubtitleManualRenderEffects(
    playerState: VideoPlayerState,
    session: JsAny?,
    video: HTMLVideoElement?,
    display: HTMLElement,
    contentScale: ContentScale,
) {
    LaunchedEffect(session, video, display, playerState.isPlaying) {
        if (session == null || video != null || !playerState.isPlaying) return@LaunchedEffect
        while (isActive) {
            withFrameNanos {
                renderAssSubtitleFrame(
                    session = session,
                    mediaTimeSeconds = playerState.assSubtitleMediaTimeSeconds(),
                    repaint = false,
                )
            }
        }
    }

    LaunchedEffect(
        session,
        video,
        display,
        playerState.isPlaying,
        playerState.currentTime,
        playerState.userDragging,
        playerState.sliderPos,
        playerState.duration,
        playerState.subtitleOffset,
        contentScale,
        playerState.isFullscreen,
    ) {
        if (session == null || video != null || playerState.isPlaying) return@LaunchedEffect
        withFrameNanos {
            renderAssSubtitleFrame(
                session = session,
                mediaTimeSeconds = playerState.assSubtitleMediaTimeSeconds(),
                repaint = true,
            )
        }
    }
}

private fun VideoPlayerState.assSubtitleMediaTimeSeconds(): Double {
    val position =
        if (userDragging) {
            duration * (sliderPos / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
        } else {
            preciseCurrentTime
        }
    return position.toDouble(DurationUnit.SECONDS)
}
