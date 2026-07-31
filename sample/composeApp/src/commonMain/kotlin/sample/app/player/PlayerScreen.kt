package sample.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.PlaybackEvent
import io.github.kdroidfilter.composemediaplayer.RenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberRenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    playerState: RenderableVideoPlayerState = rememberRenderableVideoPlayerState(),
    initialVideoUrl: String? = null,
    initialSubtitleUrl: String? = null,
    demoSubtitleEnabled: Boolean = true,
) {
    // Pause when leaving the screen, resume when coming back
    DisposableEffect(playerState) {
        onDispose {
            if (playerState.isPlaying) {
                playerState.pause()
            }
            restoreDesktopMkvPlaybackBackend()
        }
    }

    val scope = rememberCoroutineScope()

    val startupVideoUrl = remember(initialVideoUrl) { initialVideoUrl?.takeIf { it.isNotBlank() } ?: SAMPLE_VIDEOS.first().second }
    val availableSampleVideos =
        remember(startupVideoUrl) {
            buildList {
                if (startupVideoUrl.startsWith("blob:")) {
                    add("Bundled MKV / VP9 / dual Opus" to startupVideoUrl)
                }
                addAll(SAMPLE_VIDEOS)
            }
        }
    var videoUrl by remember(startupVideoUrl) { mutableStateOf(startupVideoUrl) }
    var initialPlayerState by remember { mutableStateOf(InitialPlayerState.PLAY) }
    var selectedContentScale by remember { mutableStateOf(ContentScale.Fit) }
    var selectedDesktopMkvBackend by remember { mutableStateOf(DesktopMkvPlaybackBackend.AUTO) }

    val controlsVisible = true
    var showSourceSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }

    // Flags to launch pickers after the bottom sheet is fully dismissed.
    // On iOS, presenting a file picker while a ModalBottomSheet is still
    // visible fails silently because iOS cannot stack two modals.
    var pendingPickVideo by remember { mutableStateOf(false) }
    var pendingPickSubtitle by remember { mutableStateOf(false) }
    val demoSubtitleUrl = initialSubtitleUrl ?: DEFAULT_DEMO_ASS_SUBTITLE_URL
    var demoLoaded by remember { mutableStateOf(false) }
    var playbackEndedVisible by remember { mutableStateOf(false) }

    fun applyDesktopMkvBackend() {
        applyDesktopMkvPlaybackBackend(selectedDesktopMkvBackend)
    }

    fun openVideoUrl(url: String) {
        playbackEndedVisible = false
        applyDesktopMkvBackend()
        playerState.openUri(url, initialPlayerState)
    }

    val videoFileLauncher = rememberFilePickerLauncher(type = FileKitType.Video) { file ->
        file?.let {
            playbackEndedVisible = false
            applyDesktopMkvBackend()
            playerState.openFile(it, initialPlayerState)
        }
    }
    val subtitleFileLauncher = rememberFilePickerLauncher(
        type = FileKitType.File("vtt", "srt", "ass", "ssa"),
    ) { file ->
        file?.let {
            val track =
                SubtitleTrack(
                    label = it.name,
                    language = "en",
                    src = it.getUri(),
                    format = SubtitleFormat.fromSource(src = it.getUri(), label = it.name),
                )
            playerState.addSubtitleTrack(track)
            playerState.selectSubtitleTrack(track)
        }
    }

    LaunchedEffect(playerState) {
        if (!demoLoaded && !playerState.hasMedia) {
            if (demoSubtitleEnabled) {
                val track =
                    SubtitleTrack(
                        label = "ASS demo",
                        language = "en",
                        src = demoSubtitleUrl,
                        format = SubtitleFormat.ASS,
                    )
                playerState.addSubtitleTrack(track)
                playerState.selectSubtitleTrack(track)
            }
            openVideoUrl(videoUrl)
            demoLoaded = true
        }
    }

    fun disableDemoSubtitleForNewSource() {
        if (playerState.currentSubtitleTrack?.src == demoSubtitleUrl) {
            playerState.disableSubtitles()
        }
        playerState.removeSubtitleTrack(demoSubtitleUrl)
    }

    // Launch pickers only after the sheet is gone
    LaunchedEffect(pendingPickVideo, showSourceSheet) {
        if (pendingPickVideo && !showSourceSheet) {
            pendingPickVideo = false
            videoFileLauncher.launch()
        }
    }

    LaunchedEffect(pendingPickSubtitle, showSubtitleSheet) {
        if (pendingPickSubtitle && !showSubtitleSheet) {
            pendingPickSubtitle = false
            subtitleFileLauncher.launch()
        }
    }

    LaunchedEffect(playerState) {
        playerState.playbackEvents.collect { event ->
            when (event) {
                is PlaybackEvent.PlaybackEnded -> playbackEndedVisible = true
                is PlaybackEvent.SourcePreparing,
                is PlaybackEvent.SourceReleased,
                -> playbackEndedVisible = false

                else -> Unit
            }
        }
    }

    LaunchedEffect(playerState) {
        playerState.colorPipelineStatus.collect { status ->
            println("KMP_COLOR_PIPELINE_STATUS=$status")
        }
    }

    LaunchedEffect(playerState.diagnostics) {
        println("KMP_PLAYBACK_DIAGNOSTICS=${playerState.diagnostics}")
    }

    LaunchedEffect(playerState.subtitlesEnabled, playerState.currentSubtitleTrack) {
        val track = playerState.currentSubtitleTrack
        println(
            "KMP_SUBTITLE_STATUS=enabled=${playerState.subtitlesEnabled}," +
                "selected=${track != null},embedded=${track?.isEmbedded},format=${track?.resolvedFormat()}",
        )
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Video fills the entire area
        VideoPlayerSurface(
            playerState = playerState,
            modifier = Modifier.fillMaxSize(),
            contentScale = selectedContentScale,
        ) {
            when {
                playerState.isPipActive -> Unit
                playerState.isFullscreen -> FullscreenOverlay(playerState)
                else -> {
                    AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(250)),
                    ) {
                        ControlsOverlay(
                            playerState = playerState,
                            onSourceClick = { showSourceSheet = true },
                            onSubtitlesClick = { showSubtitleSheet = true },
                            onSettingsClick = { showSettingsSheet = true },
                            onPipClick = { scope.launch { playerState.enterPip() } },
                        )
                    }
                }
            }
        }

        // Empty state placeholder
        if (!playerState.isPipActive && !playerState.hasMedia && !playerState.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Load a video to get started",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Loading
        if (!playerState.isPipActive && playerState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 3.dp,
            )
        }

        // Error snackbar
        playerState.error?.takeUnless { playerState.isPipActive }?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { playerState.clearError() }) { Text("Dismiss") }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    text = when (error) {
                        is VideoPlayerError.CodecError -> "Codec: ${error.message}"
                        is VideoPlayerError.UnsupportedCodecError -> "Codec: ${error.message}"
                        is VideoPlayerError.NetworkError -> "Network: ${error.message}"
                        is VideoPlayerError.CorsError -> "CORS: ${error.message}"
                        is VideoPlayerError.SourceError -> "Source: ${error.message}"
                        is VideoPlayerError.NoSourceError -> "Source: ${error.message}"
                        is VideoPlayerError.TimeoutError -> "Timeout: ${error.message}"
                        is VideoPlayerError.HlsError -> "HLS: ${error.message}"
                        is VideoPlayerError.DrmError -> "DRM: ${error.message}"
                        is VideoPlayerError.ColorPipelineError ->
                            "Color pipeline (${error.reason.name}): ${error.message}"
                        is VideoPlayerError.UnknownError -> "Error: ${error.message}"
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (
            !playerState.isPipActive &&
            playbackEndedVisible &&
            playerState.hasMedia &&
            playerState.error == null
        ) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(
                        onClick = {
                            playbackEndedVisible = false
                            playerState.restart()
                        },
                    ) {
                        Text("Replay")
                    }
                },
            ) {
                Text("Playback ended")
            }
        }
    }

    // Bottom sheets
    if (showSourceSheet) {
        val desktopMkvBackendOptions = remember(showSourceSheet) { desktopMkvPlaybackBackendOptions() }
        MediaSourceSheet(
            videoUrl = videoUrl,
            sampleVideos = availableSampleVideos,
            desktopMkvBackendAvailable = desktopMkvPlaybackBackendSelectionAvailable,
            desktopMkvBackendOptions = desktopMkvBackendOptions,
            selectedDesktopMkvBackend = selectedDesktopMkvBackend,
            onUrlChange = { videoUrl = it },
            onDesktopMkvBackendChange = { backend ->
                selectedDesktopMkvBackend = backend
                applyDesktopMkvPlaybackBackend(backend)
            },
            onLoadUrl = {
                if (videoUrl.isNotEmpty()) {
                    disableDemoSubtitleForNewSource()
                    openVideoUrl(videoUrl)
                }
                showSourceSheet = false
            },
            onPickFile = {
                disableDemoSubtitleForNewSource()
                pendingPickVideo = true
                showSourceSheet = false
            },
            onSelectPreset = { url ->
                videoUrl = url
                disableDemoSubtitleForNewSource()
                openVideoUrl(url)
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false },
        )
    }
    if (showSettingsSheet) {
        SettingsSheet(
            playerState = playerState,
            selectedContentScale = selectedContentScale,
            onContentScaleChange = { selectedContentScale = it },
            initialPlayerState = initialPlayerState,
            onInitialPlayerStateChange = { initialPlayerState = it },
            onDismiss = { showSettingsSheet = false },
        )
    }
    if (showSubtitleSheet) {
        SubtitleSheet(
            audioTracks = playerState.availableAudioTracks,
            selectedAudioTrack = playerState.currentAudioTrack,
            controlsEnabled = !playerState.isLoading,
            onAudioTrackSelected = { track ->
                playerState.selectAudioTrack(track)
            },
            subtitleTracks = playerState.availableSubtitleTracks,
            selectedSubtitleTrack = playerState.currentSubtitleTrack,
            onSubtitleTrackSelected = { track ->
                playerState.selectSubtitleTrack(track)
            },
            onDisableSubtitles = {
                playerState.disableSubtitles()
            },
            onPickFile = {
                pendingPickSubtitle = true
                showSubtitleSheet = false
            },
            onAddTrack = { track ->
                playerState.addSubtitleTrack(track)
                playerState.selectSubtitleTrack(track)
            },
            onDismiss = { showSubtitleSheet = false },
        )
    }
}

// region Overlay controls

@Composable
private fun ControlsOverlay(
    playerState: VideoPlayerState,
    onSourceClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPipClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.18f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    ),
                ),
        )

        // Bottom gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )

        // Top bar: title
        playerState.metadata.title?.let { title ->
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .fillMaxWidth(0.7f),
            )
        }

        // Center: large play/pause (only when media is loaded)
        if (playerState.hasMedia) {
            FilledIconButton(
                onClick = {
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                },
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.Center),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Bottom: seekbar + time + actions
        PlayerBottomControls(
            playerState = playerState,
            onSourceClick = onSourceClick,
            onSubtitlesClick = onSubtitlesClick,
            onSettingsClick = onSettingsClick,
            onPipClick = onPipClick,
            showPlaybackButton = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun PlayerBottomControls(
    playerState: VideoPlayerState,
    onSourceClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPipClick: () -> Unit,
    showPlaybackButton: Boolean,
    modifier: Modifier = Modifier,
) {
    val subtitlesActive = playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Slider(
            value = playerState.sliderPos,
            onValueChange = { playerState.seekStart(it) },
            onValueChangeFinished = { playerState.seekFinished() },
            valueRange = 0f..1000f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${playerState.positionText}  /  ${playerState.durationText}",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showPlaybackButton) {
                    OverlayIconButton(onClick = {
                        if (playerState.isPlaying) playerState.pause() else playerState.play()
                    }) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                OverlayIconButton(onClick = onSourceClick) {
                    Icon(Icons.Default.UploadFile, "Source", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                OverlayIconButton(onClick = onSubtitlesClick) {
                    Icon(
                        Icons.Default.Subtitles,
                        "Subtitles",
                        tint =
                            if (subtitlesActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
                OverlayIconButton(onClick = { playerState.toggleFullscreen() }) {
                    Icon(Icons.Default.Fullscreen, "Fullscreen", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                OverlayIconButton(onClick = onPipClick) {
                    Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                OverlayIconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun OverlayIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        content()
    }
}

// endregion

// region Fullscreen overlay

@Composable
private fun FullscreenOverlay(playerState: VideoPlayerState) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            delay(3.seconds)
            visible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { visible = true }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) visible = true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.large)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
            ) {
                IconButton(onClick = {
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                }) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { playerState.toggleFullscreen() }) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = "Exit fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}

// endregion

internal val SAMPLE_VIDEOS = listOf(
    "Flower (MP4)" to "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
    "Flower (WebM)" to "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.webm",
    "Big Buck Bunny (HLS)" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    "Envivio (DASH)" to "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd",
    "Apple HDR / Dolby Vision HLS" to "https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8",
)

private const val DEFAULT_DEMO_ASS_SUBTITLE_URL =
    "https://raw.githubusercontent.com/Shusek/KMediaPlayer/refs/heads/master/assets/subtitles/en.ass"
