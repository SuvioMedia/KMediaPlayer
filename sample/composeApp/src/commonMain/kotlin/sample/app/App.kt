package sample.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import kotlinx.coroutines.delay
import sample.app.feed.FeedScreen
import sample.app.gallery.GalleryScreen
import sample.app.player.DesktopMediaSourceAdapter
import sample.app.player.DesktopMkvPlaybackBackend
import sample.app.player.PlayerScreen
import sample.app.player.SampleVideoPlayerHandle
import sample.app.player.rememberSampleVideoPlayer
import sample.app.theme.AppTheme

private enum class Screen(val label: String, val icon: ImageVector) {
    Player("Player", Icons.Default.PlayCircle),
    Gallery("Gallery", Icons.AutoMirrored.Filled.List),
    Feed("Feed", Icons.AutoMirrored.Filled.Article),
}

@Composable
fun App(
    initialVideoUrl: String? = null,
    initialSubtitleUrl: String? = null,
    demoSubtitleEnabled: Boolean = true,
    initialMuted: Boolean = false,
    initialLoop: Boolean = false,
    initialFullscreen: Boolean = false,
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
    initialProjection: VideoProjectionSettings = VideoProjectionSettings(),
    initialDesktopBackendName: String? = null,
    initialDesktopSourceAdapterName: String? = null,
) {
    AppTheme {
        var currentScreen by remember { mutableStateOf(Screen.Player) }
        var selectedDesktopBackend by
            remember(initialDesktopBackendName) {
                mutableStateOf(
                    DesktopMkvPlaybackBackend.entries.firstOrNull { backend ->
                        backend.name.equals(initialDesktopBackendName, ignoreCase = true)
                    } ?: DesktopMkvPlaybackBackend.AUTO,
                )
            }
        var selectedDesktopSourceAdapter by
            remember(initialDesktopSourceAdapterName) {
                mutableStateOf(
                    DesktopMediaSourceAdapter.entries.firstOrNull { adapter ->
                        adapter.name.equals(initialDesktopSourceAdapterName, ignoreCase = true)
                    } ?: DesktopMediaSourceAdapter.AUTO,
                )
            }
        val player =
            rememberSampleVideoPlayer(
                selectedDesktopBackend,
                selectedDesktopSourceAdapter,
                playbackOptions,
            )
        val playerState = player.playerState
        var initialFullscreenApplied by remember { mutableStateOf(false) }
        LaunchedEffect(playerState, initialMuted, initialLoop) {
            if (initialMuted) playerState.volume = 0f
            playerState.loop = initialLoop
        }
        LaunchedEffect(playerState, initialProjection) {
            playerState.projection = initialProjection
        }
        LaunchedEffect(playerState, initialFullscreen) {
            if (!initialFullscreen || initialFullscreenApplied) return@LaunchedEffect
            while (!playerState.hasMedia && playerState.error == null) {
                delay(INITIAL_FULLSCREEN_POLL_MILLIS)
            }
            if (playerState.hasMedia) {
                playerState.isFullscreen = true
                initialFullscreenApplied = true
            }
        }
        LaunchedEffect(currentScreen, playerState) {
            if (currentScreen != Screen.Player) playerState.stop()
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            if (useRail) {
                RailLayout(
                    currentScreen,
                    onScreenChange = { currentScreen = it },
                    player = player,
                    initialVideoUrl = initialVideoUrl,
                    initialSubtitleUrl = initialSubtitleUrl,
                    demoSubtitleEnabled = demoSubtitleEnabled,
                    selectedDesktopBackend = selectedDesktopBackend,
                    onDesktopBackendChange = { selectedDesktopBackend = it },
                    selectedDesktopSourceAdapter = selectedDesktopSourceAdapter,
                    onDesktopSourceAdapterChange = { selectedDesktopSourceAdapter = it },
                )
            } else {
                BarLayout(
                    currentScreen,
                    onScreenChange = { currentScreen = it },
                    player = player,
                    initialVideoUrl = initialVideoUrl,
                    initialSubtitleUrl = initialSubtitleUrl,
                    demoSubtitleEnabled = demoSubtitleEnabled,
                    selectedDesktopBackend = selectedDesktopBackend,
                    onDesktopBackendChange = { selectedDesktopBackend = it },
                    selectedDesktopSourceAdapter = selectedDesktopSourceAdapter,
                    onDesktopSourceAdapterChange = { selectedDesktopSourceAdapter = it },
                )
            }
        }
    }
}

private const val INITIAL_FULLSCREEN_POLL_MILLIS = 25L

// Compact: bottom NavigationBar
@Composable
private fun BarLayout(
    current: Screen,
    onScreenChange: (Screen) -> Unit,
    player: SampleVideoPlayerHandle,
    initialVideoUrl: String?,
    initialSubtitleUrl: String?,
    demoSubtitleEnabled: Boolean,
    selectedDesktopBackend: DesktopMkvPlaybackBackend,
    onDesktopBackendChange: (DesktopMkvPlaybackBackend) -> Unit,
    selectedDesktopSourceAdapter: DesktopMediaSourceAdapter,
    onDesktopSourceAdapterChange: (DesktopMediaSourceAdapter) -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.zIndex(2f)) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = current == screen,
                        onClick = { onScreenChange(screen) },
                    )
                }
            }
        },
    ) { padding ->
        ScreenContent(
            current,
            Modifier.fillMaxSize().padding(padding).zIndex(0f),
            player,
            initialVideoUrl,
            initialSubtitleUrl,
            demoSubtitleEnabled,
            selectedDesktopBackend,
            onDesktopBackendChange,
            selectedDesktopSourceAdapter,
            onDesktopSourceAdapterChange,
        )
    }
}

// Medium+: side NavigationRail
@Composable
private fun RailLayout(
    current: Screen,
    onScreenChange: (Screen) -> Unit,
    player: SampleVideoPlayerHandle,
    initialVideoUrl: String?,
    initialSubtitleUrl: String?,
    demoSubtitleEnabled: Boolean,
    selectedDesktopBackend: DesktopMkvPlaybackBackend,
    onDesktopBackendChange: (DesktopMkvPlaybackBackend) -> Unit,
    selectedDesktopSourceAdapter: DesktopMediaSourceAdapter,
    onDesktopSourceAdapterChange: (DesktopMediaSourceAdapter) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(modifier = Modifier.zIndex(2f)) {
            Spacer(Modifier.weight(1f))
            Screen.entries.forEach { screen ->
                NavigationRailItem(
                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                    label = { Text(screen.label) },
                    selected = current == screen,
                    onClick = { onScreenChange(screen) },
                )
            }
            Spacer(Modifier.weight(1f))
        }
        ScreenContent(
            current,
            Modifier.weight(1f).fillMaxHeight().zIndex(0f),
            player,
            initialVideoUrl,
            initialSubtitleUrl,
            demoSubtitleEnabled,
            selectedDesktopBackend,
            onDesktopBackendChange,
            selectedDesktopSourceAdapter,
            onDesktopSourceAdapterChange,
        )
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    modifier: Modifier,
    player: SampleVideoPlayerHandle,
    initialVideoUrl: String?,
    initialSubtitleUrl: String?,
    demoSubtitleEnabled: Boolean,
    selectedDesktopBackend: DesktopMkvPlaybackBackend,
    onDesktopBackendChange: (DesktopMkvPlaybackBackend) -> Unit,
    selectedDesktopSourceAdapter: DesktopMediaSourceAdapter,
    onDesktopSourceAdapterChange: (DesktopMediaSourceAdapter) -> Unit,
) {
    when (screen) {
        Screen.Player ->
            PlayerScreen(
                modifier,
                player = player,
                initialVideoUrl = initialVideoUrl,
                initialSubtitleUrl = initialSubtitleUrl,
                demoSubtitleEnabled = demoSubtitleEnabled,
                selectedDesktopMkvBackend = selectedDesktopBackend,
                onDesktopMkvBackendChange = onDesktopBackendChange,
                selectedDesktopSourceAdapter = selectedDesktopSourceAdapter,
                onDesktopSourceAdapterChange = onDesktopSourceAdapterChange,
            )
        Screen.Gallery -> GalleryScreen(modifier)
        Screen.Feed -> FeedScreen(modifier)
    }
}
