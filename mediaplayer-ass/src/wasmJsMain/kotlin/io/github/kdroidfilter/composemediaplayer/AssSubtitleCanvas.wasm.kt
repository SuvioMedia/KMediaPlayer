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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

@Composable
internal fun AssSubtitleCanvas(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    modifier: Modifier,
    onActiveChanged: (Boolean) -> Unit,
) {
    val subtitleTrack = playerState.currentSubtitleTrack
    val shouldRenderAss =
        AssSubtitleRendererConfig.enabled &&
            playerState.subtitlesEnabled &&
            subtitleTrack?.resolvedFormat()?.isAssFamily == true &&
            subtitleTrack.src.isNotBlank() &&
            videoElement != null

    if (!shouldRenderAss) {
        SideEffect { onActiveChanged(false) }
        return
    }
    val video = videoElement

    var canvasElement by remember(subtitleTrack) { mutableStateOf<HTMLCanvasElement?>(null) }
    val scope = rememberCoroutineScope()

    key(subtitleTrack.src) {
        HtmlElementView(
            factory = { createAssSubtitleCanvasElement() },
            modifier = if (playerState.isFullscreen) Modifier.fillMaxSize() else modifier,
            update = { canvas ->
                canvasElement = canvas
                canvas.applyAssSubtitleCanvasStyle()
            },
            onRelease = { canvas ->
                clearAssSubtitleCanvas(canvas)
                canvasElement = null
            },
        )
    }

    DisposableEffect(videoElement, canvasElement, subtitleTrack.src) {
        val canvas = canvasElement
        if (canvas == null) {
            onDispose {}
        } else {
            var instance: JsAny? = null
            val job =
                scope.launch {
                    if (!ensureAssRendererScriptLoaded()) {
                        onActiveChanged(false)
                        println("KMediaPlayer ASS: subtitle renderer script failed to load")
                        return@launch
                    }
                    instance =
                        createAssSubtitleRenderer(
                            video = video,
                            canvas = canvas,
                            subUrl = subtitleTrack.src,
                            debug = AssSubtitleRendererConfig.debug,
                            onReady = {
                                onActiveChanged(true)
                                resizeAssSubtitleRenderer(instance)
                            },
                            onError = { message ->
                                onActiveChanged(false)
                                println("KMediaPlayer ASS: subtitle renderer error: $message")
                            },
                        )
                }

            onDispose {
                onActiveChanged(false)
                job.cancel()
                instance?.let { disposeAssSubtitleRenderer(it) }
                clearAssSubtitleCanvas(canvas)
            }
        }
    }

    LaunchedEffect(playerState.isFullscreen, canvasElement) {
        canvasElement?.let { canvas ->
            resizeAssSubtitleCanvas(canvas)
        }
    }
}
