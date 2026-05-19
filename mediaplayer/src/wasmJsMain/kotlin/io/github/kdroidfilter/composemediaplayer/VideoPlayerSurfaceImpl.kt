@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.jsinterop.MediaError
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val webVideoLogger = TaggedLogger("WebVideoPlayerSurface")

// Cache mime type mappings for better performance
internal val EXTENSION_TO_MIME_TYPE =
    mapOf(
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "ogg" to "video/ogg",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska",
    )

// Helper functions for common operations
internal fun HTMLVideoElement.safePlay() {
    if (src.isEmpty() && currentSrc.isEmpty()) return

    try {
        playHtmlVideo(this)
    } catch (e: Exception) {
        webVideoLogger.e { "Error playing video: ${e.message}" }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun playHtmlVideo(video: HTMLVideoElement): Unit =
    js(
        """
        {
            const result = video.play();
            if (result && typeof result.catch === "function") {
                result.catch(function() {});
            }
        }
        """,
    )

internal fun HTMLVideoElement.safePause() {
    try {
        pause()
    } catch (e: Exception) {
        webVideoLogger.e { "Error pausing video: ${e.message}" }
    }
}

internal fun HTMLVideoElement.safeSetPlaybackRate(rate: Float) {
    try {
        playbackRate = rate.toDouble()
    } catch (e: Exception) {
        webVideoLogger.e { "Error setting playback rate: ${e.message}" }
    }
}

internal fun HTMLVideoElement.safeSetCurrentTime(time: Double) {
    try {
        currentTime = time
    } catch (e: Exception) {
        webVideoLogger.e { "Error seeking to ${time}s: ${e.message}" }
    }
}

internal fun HTMLVideoElement.addEventListeners(
    scope: CoroutineScope,
    playerState: VideoPlayerState,
    events: Map<String, (Event) -> Unit>,
    loadingEvents: Map<String, Boolean> = emptyMap(),
) {
    events.forEach { (event, handler) ->
        addEventListener(event, handler)
    }

    loadingEvents.forEach { (event, isLoading) ->
        if (playerState is DefaultVideoPlayerState) {
            addEventListener(event) {
                scope.launch {
                    playerState._isLoading = isLoading
                    if (!isLoading) {
                        playerState.clearError()
                    }
                }
            }
        }
    }
}

fun Modifier.videoRatioClip(
    videoRatio: Float?,
    contentScale: ContentScale = ContentScale.Fit,
): Modifier = drawBehind { videoRatio?.let { drawVideoRatioRect(it, contentScale) } }

// Optimized drawing function to reduce calculations during rendering
private fun DrawScope.drawVideoRatioRect(
    ratio: Float,
    contentScale: ContentScale,
) {
    val containerWidth = size.width
    val containerHeight = size.height
    val containerRatio = containerWidth / containerHeight

    when (contentScale) {
        ContentScale.Fit, ContentScale.Inside -> {
            val (rectWidth, rectHeight) =
                if (containerRatio > ratio) {
                    val height = containerHeight
                    val width = height * ratio
                    width to height
                } else {
                    val width = containerWidth
                    val height = width / ratio
                    width to height
                }
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight),
            )
        }
        ContentScale.Crop -> {
            val (rectWidth, rectHeight) =
                if (containerRatio < ratio) {
                    val height = containerHeight
                    val width = height * ratio
                    width to height
                } else {
                    val width = containerWidth
                    val height = width / ratio
                    width to height
                }
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight),
            )
        }
        ContentScale.FillWidth -> {
            val width = containerWidth
            val height = width / ratio
            val yOffset = (containerHeight - height) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(0f, yOffset),
                size = Size(width, height),
            )
        }
        ContentScale.FillHeight -> {
            val height = containerHeight
            val width = height * ratio
            val xOffset = (containerWidth - width) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, 0f),
                size = Size(width, height),
            )
        }
        ContentScale.FillBounds -> {
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(0f, 0f),
                size = Size(containerWidth, containerHeight),
            )
        }
        else -> {
            val (rectWidth, rectHeight) =
                if (containerRatio > ratio) {
                    val height = containerHeight
                    val width = height * ratio
                    width to height
                } else {
                    val width = containerWidth
                    val height = width / ratio
                    width to height
                }
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight),
            )
        }
    }
}

@Composable
internal fun SubtitleOverlay(playerState: VideoPlayerState) {
    val subtitleTrack = playerState.currentSubtitleTrack
    if (!playerState.subtitlesEnabled ||
        subtitleTrack == null ||
        subtitleTrack.isEmbedded ||
        subtitleTrack.resolvedFormat().isAssFamily
    ) {
        return
    }

    val currentTime =
        if (playerState.userDragging) {
            playerState.duration * (playerState.sliderPos / 1000.0).coerceIn(0.0, 1.0)
        } else {
            playerState.currentTime
        }

    ComposeSubtitleLayer(
        currentTime = currentTime,
        duration = playerState.duration,
        isPlaying = playerState.isPlaying,
        subtitleTrack = subtitleTrack,
        subtitlesEnabled = true,
        textStyle = playerState.subtitleTextStyle,
        backgroundColor = playerState.subtitleBackgroundColor,
    )
}

@Composable
internal fun VideoBox(
    playerState: VideoPlayerState,
    videoRatio: Float?,
    contentScale: ContentScale,
    isFullscreenMode: Boolean,
    overlay: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (isFullscreenMode) Color.Black else Color.Transparent)
                .videoRatioClip(videoRatio, contentScale),
    ) {
        SubtitleOverlay(playerState)
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}

@Composable
internal fun VideoContentLayout(
    playerState: VideoPlayerState,
    modifier: Modifier,
    videoRatio: Float?,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    videoElementContent: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(if (playerState.isFullscreen) Color.Black else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        VideoBox(playerState, videoRatio, contentScale, playerState.isFullscreen, overlay)
        videoElementContent()
    }
}

internal fun HTMLVideoElement.applyInteropBehindCanvas() {
    val wrapper = parentElement as? HTMLElement ?: return
    wrapper.style.apply {
        setProperty("z-index", "-2", "important")
        setProperty("pointer-events", "none")
        setProperty("contain", "layout paint style", "important")
        setProperty("overflow", "hidden", "important")
        backgroundColor = "transparent"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
    }
    (wrapper.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")
}

internal fun HTMLVideoElement.applyContentScale(
    contentScale: ContentScale,
    videoRatio: Float?,
) {
    style.apply {
        backgroundColor = "black"
        setProperty("pointer-events", "none")
        setProperty("contain", "strict", "important")
        setProperty("transform", "translateZ(0)", "important")
        setProperty("will-change", "transform", "important")
        setProperty("backface-visibility", "hidden", "important")
        display = "block"

        when (contentScale) {
            ContentScale.Crop -> {
                width = "100%"
                height = "100%"
                objectFit = "cover"
            }
            ContentScale.FillBounds -> {
                width = "100%"
                height = "100%"
                objectFit = "fill"
            }
            ContentScale.FillWidth -> {
                objectFit = "contain"
                if (videoRatio != null) {
                    width = "100%"
                    height = "auto"
                } else {
                    width = "100%"
                    height = "100%"
                }
            }
            ContentScale.FillHeight -> {
                objectFit = "contain"
                if (videoRatio != null) {
                    width = "auto"
                    height = "100%"
                } else {
                    width = "100%"
                    height = "100%"
                }
            }
            else -> {
                width = "100%"
                height = "100%"
                objectFit = "contain"
            }
        }
    }
}

internal fun createVideoElement(useCors: Boolean = true): HTMLVideoElement =
    (document.createElement("video") as HTMLVideoElement).apply {
        controls = false
        style.width = "100%"
        style.height = "100%"
        style.backgroundColor = "black"
        style.setProperty("pointer-events", "none")
        style.setProperty("contain", "strict", "important")
        style.setProperty("transform", "translateZ(0)", "important")
        style.setProperty("will-change", "transform", "important")
        style.setProperty("backface-visibility", "hidden", "important")
        style.display = "block"

        if (useCors) {
            crossOrigin = "anonymous"
        } else {
            removeAttribute("crossorigin")
        }

        setAttribute("playsinline", "")
        setAttribute("webkit-playsinline", "")
        setAttribute("preload", "auto")
        setAttribute("fetchpriority", "high")
        setAttribute("x-webkit-airplay", "allow")
    }

internal fun HTMLVideoElement.startPlaybackQualityDiagnostics(playerState: DefaultVideoPlayerState) {
    startPlaybackQualityDiagnostics(this) { notes ->
        playerState.renderingInfo.notes = notes
    }
}

@Suppress("UNUSED_PARAMETER")
private fun startPlaybackQualityDiagnostics(
    video: HTMLVideoElement,
    onNotesChanged: (String) -> Unit,
): Unit =
    js(
        """
        {
            if (video.__composeMediaPlayerQualityTimer) {
                clearInterval(video.__composeMediaPlayerQualityTimer);
                video.__composeMediaPlayerQualityTimer = null;
            }

            const readQuality = function() {
                let total = 0;
                let dropped = 0;
                let corrupted = 0;

                if (typeof video.getVideoPlaybackQuality === "function") {
                    const quality = video.getVideoPlaybackQuality();
                    total = Number(quality.totalVideoFrames || 0);
                    dropped = Number(quality.droppedVideoFrames || 0);
                    corrupted = Number(quality.corruptedVideoFrames || 0);
                } else {
                    total = Number(video.webkitDecodedFrameCount || 0);
                    dropped = Number(video.webkitDroppedFrameCount || 0);
                }

                const droppedRatio = total > 0 ? Math.round((dropped / total) * 1000) / 10 : 0;
                const resolution =
                    video.videoWidth && video.videoHeight
                        ? video.videoWidth + "x" + video.videoHeight
                        : "unknown";
                const parts = [
                    "resolution=" + resolution,
                    "dropped=" + dropped + "/" + total + " (" + droppedRatio + "%)",
                    "readyState=" + video.readyState,
                    "networkState=" + video.networkState
                ];

                if (corrupted > 0) {
                    parts.push("corrupted=" + corrupted);
                }

                if (video.videoWidth >= 7680 || video.videoHeight >= 3840) {
                    parts.push("8K HEVC needs browser hardware decode");
                }

                try {
                    onNotesChanged(parts.join("; "));
                } catch (_) {
                }
            };

            readQuality();
            video.__composeMediaPlayerQualityTimer = setInterval(readQuality, 3000);
        }
        """,
    )

internal fun HTMLVideoElement.stopPlaybackQualityDiagnostics() {
    stopPlaybackQualityDiagnostics(this)
}

@Suppress("UNUSED_PARAMETER")
private fun stopPlaybackQualityDiagnostics(video: HTMLVideoElement): Unit =
    js(
        """
        {
            if (video.__composeMediaPlayerQualityTimer) {
                clearInterval(video.__composeMediaPlayerQualityTimer);
                video.__composeMediaPlayerQualityTimer = null;
            }
        }
        """,
    )

internal fun setupVideoElement(
    video: HTMLVideoElement,
    playerState: VideoPlayerState,
    scope: CoroutineScope,
    useCors: Boolean = true,
    onCorsError: () -> Unit = {},
) {
    var corsErrorDetected = false
    var mediaLoaded = false

    playerState.clearError()
    playerState.metadata.audioChannels = null
    playerState.metadata.audioSampleRate = null

    val syncMediaTracks = {
        if (playerState is DefaultVideoPlayerState) {
            scope.launch {
                playerState.syncWebMediaTracks(video)
                video.applySelectedAudioTrack(playerState.currentAudioTrack)
                video.applySelectedSubtitleTrack(
                    if (playerState.subtitlesEnabled) playerState.currentSubtitleTrack else null,
                )
            }
        }
    }

    if (playerState is DefaultVideoPlayerState) {
        video.startPlaybackQualityDiagnostics(playerState)

        video.addEventListeners(
            scope = scope,
            playerState = playerState,
            events =
                mapOf(
                    "timeupdate" to { event -> playerState.onTimeUpdateEvent(event) },
                    "ended" to {
                        scope.launch {
                            if (playerState.loop) {
                                video.safeSetCurrentTime(0.0)
                                video.safePlay()
                                playerState.sliderPos = 0f
                                playerState.onRestart?.invoke()
                            } else {
                                playerState.pause()
                                playerState.onPlaybackEnded?.invoke()
                            }
                        }
                    },
                ),
            loadingEvents =
                mapOf(
                    "seeking" to true,
                    "waiting" to true,
                    "playing" to false,
                    "seeked" to false,
                    "canplaythrough" to false,
                    "canplay" to false,
                ),
        )
    }

    val conditionalLoadingEvents =
        mapOf(
            "suspend" to { video.readyState >= 3 },
            "loadedmetadata" to { true },
        )

    conditionalLoadingEvents.forEach { (event, condition) ->
        video.addEventListener(event) {
            scope.launch {
                if (playerState is DefaultVideoPlayerState && condition()) {
                    mediaLoaded = true
                    playerState._isLoading = false
                    playerState.clearError()
                }

                if (event == "loadedmetadata") {
                    syncMediaTracks()
                    if (playerState.isPlaying) {
                        video.safePlay()
                    }
                }
            }
        }
    }

    listOf("loadeddata", "canplay", "canplaythrough").forEach { event ->
        video.addEventListener(event) {
            mediaLoaded = true
            if (playerState is DefaultVideoPlayerState) {
                playerState.clearError()
            }
            syncMediaTracks()
        }
    }
    video.addEventListener("playing") {
        mediaLoaded = true
        if (playerState is DefaultVideoPlayerState) {
            playerState.clearError()
        }
    }
    addWebMediaTrackListeners(video, syncMediaTracks)

    video.addEventListener("error") {
        scope.launch {
            if (playerState is DefaultVideoPlayerState) {
                playerState._isLoading = false
            }
            corsErrorDetected = true

            val error = video.error
            if (error != null) {
                if (useCors) {
                    playerState.clearError()
                    onCorsError()
                } else if (
                    error.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED &&
                    (mediaLoaded || video.readyState > 0 || video.duration > 0.0)
                ) {
                    playerState.clearError()
                } else {
                    if (playerState is DefaultVideoPlayerState) {
                        delay(500.milliseconds)
                        if (mediaLoaded || video.readyState > 0 || video.duration > 0.0) {
                            playerState.clearError()
                        } else {
                            val errorMsg =
                                if (error.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
                                    "Failed to load because the video format is not supported"
                                } else {
                                    "Failed to load because no supported source was found"
                                }
                            playerState.setError(VideoPlayerError.SourceError(errorMsg))
                        }
                    }
                }
            }
        }
    }

    video.loop = playerState.loop

    if (video.src.isNotEmpty() && playerState.isPlaying) {
        video.safePlay()
    }
}

internal fun DefaultVideoPlayerState.onTimeUpdateEvent(event: Event) {
    (event.target as? HTMLVideoElement)?.let {
        onTimeUpdate(it.currentTime.secondsAsDuration(), it.duration.secondsAsDuration())
    }
}

internal fun HTMLVideoElement.setupMetadataListener(
    playerState: VideoPlayerState,
    onVideoRatioChange: (Float) -> Unit,
) {
    addEventListener("loadedmetadata") {
        val width = videoWidth
        val height = videoHeight
        if (height != 0) {
            onVideoRatioChange(width.toFloat() / height.toFloat())

            with(playerState.metadata) {
                this.width = width
                this.height = height
                duration = this@setupMetadataListener.duration.secondsAsDuration()

                val src = this@setupMetadataListener.src
                if (src.isNotEmpty()) {
                    val lastDotIndex = src.lastIndexOf('.')
                    if (lastDotIndex > 0 && lastDotIndex < src.length - 1) {
                        val extension = src.substring(lastDotIndex + 1).lowercase()
                        mimeType = EXTENSION_TO_MIME_TYPE[extension]
                    }

                    try {
                        val lastSlashIndex = src.lastIndexOf('/')
                        val lastBackslashIndex = src.lastIndexOf('\\')
                        val startIndex = maxOf(lastSlashIndex, lastBackslashIndex) + 1

                        if (startIndex > 0 && startIndex < src.length) {
                            val endIndex = if (lastDotIndex > startIndex) lastDotIndex else src.length
                            val filename = src.substring(startIndex, endIndex)
                            if (filename.isNotEmpty()) {
                                title = filename
                            }
                        }
                    } catch (e: Exception) {
                        webVideoLogger.w { "Failed to extract title from filename: ${e.message}" }
                    }
                }
            }

            if (playerState is DefaultVideoPlayerState) {
                playerState.renderingInfo.update(
                    container = playerState.metadata.mimeType,
                    videoDecoder = "Browser native decoder (${width}x$height)",
                    videoRenderer = "HTMLVideoElement + browser compositor",
                    audioRenderer = "Browser native audio",
                )
            }
        }
    }
}

@Composable
internal fun VideoPlayerEffects(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    scope: CoroutineScope,
    useCors: Boolean,
    onLastPositionChange: (Double) -> Unit,
    onWasPlayingChange: (Boolean) -> Unit,
    lastPosition: Double,
    wasPlaying: Boolean,
) {
    // Handle fullscreen
    LaunchedEffect(playerState.isFullscreen) {
        try {
            if (!playerState.isFullscreen) {
                FullscreenManager.exitFullscreen()
            }
        } catch (e: Exception) {
            webVideoLogger.e { "Error handling fullscreen: ${e.message}" }
        }
    }

    // Listen for fullscreen change events
    DisposableEffect(Unit) {
        val fullscreenChangeListener: (Event) -> Unit = {
            videoElement?.let { video ->
                val isDocumentFullscreen = video.ownerDocument?.fullscreenElement != null
                if (!isDocumentFullscreen && playerState.isFullscreen) {
                    playerState.isFullscreen = false
                }
            }
        }

        val fullscreenEvents =
            listOf(
                "fullscreenchange",
                "webkitfullscreenchange",
                "mozfullscreenchange",
                "MSFullscreenChange",
            )

        fullscreenEvents.forEach { event ->
            document.addEventListener(event, fullscreenChangeListener)
        }

        onDispose {
            fullscreenEvents.forEach { event ->
                document.removeEventListener(event, fullscreenChangeListener)
            }
        }
    }

    // Handle source change effect

    if (playerState is DefaultVideoPlayerState) {
        LaunchedEffect(videoElement, playerState.sourceUri) {
            videoElement?.let { video ->
                val sourceUri = playerState.sourceUri ?: ""
                if (sourceUri.isNotEmpty()) {
                    playerState.clearError()
                    if (sourceUri.isHlsSource()) {
                        video.destroyMkvSidecarTracks()
                        val configured =
                            video.configureHlsSource(
                                playerState = playerState,
                                sourceUri = sourceUri,
                                scope = scope,
                            )
                        if (configured) {
                            if (playerState.isPlaying) video.safePlay() else video.safePause()
                            return@let
                        }
                    }

                    video.destroyHlsController()
                    video.destroyMkvSidecarTracks()
                    video.src = sourceUri
                    video.load()
                    video.configureMkvSidecarTracks(
                        playerState = playerState,
                        sourceUri = sourceUri,
                        scope = scope,
                    )
                    if (playerState.isPlaying) video.safePlay() else video.safePause()
                }
            }
        }
    }
    // Handle play/pause
    LaunchedEffect(videoElement, playerState.isPlaying) {
        videoElement?.let { video ->
            if (playerState.isPlaying) video.safePlay() else video.safePause()
        }
    }

    DisposableEffect(videoElement, playerState) {
        val webPlayerState = playerState as? DefaultVideoPlayerState
        val video = videoElement

        if (webPlayerState != null && video != null) {
            webPlayerState.preciseCurrentTimeProvider = { video.currentTime.secondsAsDuration() }
            webPlayerState.durationProvider = { video.duration.secondsAsDuration() }
        }

        onDispose {
            if (webPlayerState != null) {
                webPlayerState.preciseCurrentTimeProvider = null
                webPlayerState.durationProvider = null
            }
        }
    }

    // Loop is handled manually via the "ended" event to support the onRestart callback

    // Store state before video element recreation
    LaunchedEffect(useCors) {
        videoElement?.let {
            onLastPositionChange(it.currentTime)
            onWasPlayingChange(playerState.isPlaying)
        }
    }

    // Restore state after video element recreation
    LaunchedEffect(videoElement, useCors) {
        videoElement?.let { video ->
            val restorePosition = lastPosition
            val restoreWasPlaying = wasPlaying

            fun restorePlaybackState() {
                if (restorePosition > 0) {
                    video.safeSetCurrentTime(restorePosition)
                }
                if (restoreWasPlaying) {
                    playerState.play()
                    video.safePlay()
                }
            }

            if (restorePosition > 0 && video.readyState < 1) {
                lateinit var metadataListener: (Event) -> Unit
                metadataListener = {
                    video.removeEventListener("loadedmetadata", metadataListener)
                    restorePlaybackState()
                }
                video.addEventListener("loadedmetadata", metadataListener)
            } else {
                restorePlaybackState()
            }

            if (restorePosition > 0) {
                onLastPositionChange(0.0)
            }

            if (restoreWasPlaying) {
                onWasPlayingChange(false)
            }
        }
    }

    // Handle seeking only for explicit seek requests. Passive slider updates must never seek the video element.
    LaunchedEffect(
        videoElement,
        playerState.hasMedia,
        (playerState as? DefaultVideoPlayerState)?.seekRequestId,
        playerState.userDragging,
    ) {
        if (playerState is DefaultVideoPlayerState &&
            playerState.hasPendingSeekRequest() &&
            !playerState.userDragging &&
            playerState.hasMedia
        ) {
            playerState.seekJob?.cancel()

            videoElement?.let { video ->
                val duration = video.duration.secondsAsDuration()
                if (duration > Duration.ZERO) {
                    val newTime =
                        (duration * (playerState.sliderPos / DefaultVideoPlayerState.PERCENTAGE_MULTIPLIER).toDouble())
                            .toSecondsDouble()
                    playerState.consumePendingSeekRequest()

                    playerState.seekJob =
                        scope.launch {
                            video.safeSetCurrentTime(newTime)
                        }
                }
            }
        }
    }

    // Listen for external play/pause events
    DisposableEffect(videoElement) {
        val video = videoElement ?: return@DisposableEffect onDispose {}

        val playListener: (Event) -> Unit = {
            if (!playerState.isPlaying) scope.launch { playerState.play() }
        }

        val pauseListener: (Event) -> Unit = {
            if (playerState.isPlaying) scope.launch { playerState.pause() }
        }

        video.addEventListener("play", playListener)
        video.addEventListener("pause", pauseListener)

        onDispose {
            video.removeEventListener("play", playListener)
            video.removeEventListener("pause", pauseListener)
        }
    }
}

@Composable
internal fun VideoVolumeAndSpeedEffects(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
) {
    if (playerState !is DefaultVideoPlayerState) {
        webVideoLogger.e {
            "Expected ${DefaultVideoPlayerState::class} but found ${playerState::class}. " +
                "Volume and speed settings won't work."
        }
        return
    }

    DisposableEffect(videoElement) {
        val video = videoElement ?: return@DisposableEffect onDispose {}

        // Init video element state when video is loaded
        // Volume could be changed any time but playbackRate require video to be loaded
        val videoLoadedListener: (Event) -> Unit = {
            video.volume = playerState.volume.toDouble()
            video.safeSetPlaybackRate(playerState.playbackSpeed)
        }

        video.addEventListener("canplay", videoLoadedListener)

        // They should change only `playerState` while the video is loading
        // The video element will be updated in `videoLoadedListener`
        playerState.applyVolumeCallback = { value ->
            if (!playerState.isLoading) {
                video.volume = value.toDouble()
            }
        }

        playerState.applyPlaybackSpeedCallback = { value ->
            if (!playerState.isLoading) {
                video.safeSetPlaybackRate(value)
            }
        }

        onDispose {
            video.removeEventListener("canplay", videoLoadedListener)
            playerState.applyVolumeCallback = null
            playerState.applyPlaybackSpeedCallback = null
        }
    }
}

@Composable
internal fun VideoMediaTrackEffects(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    scope: CoroutineScope,
) {
    if (playerState !is DefaultVideoPlayerState) return

    LaunchedEffect(
        videoElement,
        playerState.subtitlesEnabled,
        playerState.currentSubtitleTrack?.id,
    ) {
        val video = videoElement ?: return@LaunchedEffect
        val track = if (playerState.subtitlesEnabled) playerState.currentSubtitleTrack else null
        if (track?.id?.startsWith(MKV_SUBTITLE_TRACK_ID_PREFIX) == true) {
            video.extractMkvSubtitleTrack(track, playerState, scope)
        } else {
            video.cancelMkvSubtitleExtraction()
        }
    }

    DisposableEffect(videoElement) {
        val video = videoElement ?: return@DisposableEffect onDispose {}
        val refreshMkvSubtitleAfterSeek: (Event) -> Unit = {
            val track = if (playerState.subtitlesEnabled) playerState.currentSubtitleTrack else null
            if (track?.id?.startsWith(MKV_SUBTITLE_TRACK_ID_PREFIX) == true) {
                scope.launch {
                    video.extractMkvSubtitleTrack(track, playerState, scope)
                }
            }
        }

        playerState.applyAudioTrackCallback = { track ->
            video.applySelectedAudioTrack(track)
            playerState.syncWebMediaTracks(video)
        }
        playerState.applySubtitleTrackCallback = { track ->
            video.applySelectedSubtitleTrack(track)
            scope.launch {
                video.extractMkvSubtitleTrack(track, playerState, scope)
            }
            playerState.syncWebMediaTracks(video)
        }

        playerState.syncWebMediaTracks(video)
        video.applySelectedAudioTrack(playerState.currentAudioTrack)
        video.applySelectedSubtitleTrack(
            if (playerState.subtitlesEnabled) playerState.currentSubtitleTrack else null,
        )
        video.addEventListener("seeked", refreshMkvSubtitleAfterSeek)

        onDispose {
            video.removeEventListener("seeked", refreshMkvSubtitleAfterSeek)
            playerState.applyAudioTrackCallback = null
            playerState.applySubtitleTrackCallback = null
        }
    }
}
