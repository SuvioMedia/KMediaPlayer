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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import sample.app.feed.FeedScreen
import sample.app.gallery.GalleryScreen
import sample.app.player.PlayerScreen
import sample.app.theme.AppTheme

private enum class Screen(val label: String, val icon: ImageVector) {
    Player("Player", Icons.Default.PlayCircle),
    Gallery("Gallery", Icons.AutoMirrored.Filled.List),
    Feed("Feed", Icons.AutoMirrored.Filled.Article),
}

@Composable
fun App(
    initialVideoUrl: String? = null,
    demoSubtitleEnabled: Boolean = true,
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) {
    AppTheme {
        var currentScreen by remember { mutableStateOf(Screen.Player) }
        val playerState = rememberVideoPlayerState(playbackOptions = playbackOptions)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            if (useRail) {
                RailLayout(
                    currentScreen,
                    onScreenChange = { currentScreen = it },
                    playerState = playerState,
                    initialVideoUrl = initialVideoUrl,
                    demoSubtitleEnabled = demoSubtitleEnabled,
                )
            } else {
                BarLayout(
                    currentScreen,
                    onScreenChange = { currentScreen = it },
                    playerState = playerState,
                    initialVideoUrl = initialVideoUrl,
                    demoSubtitleEnabled = demoSubtitleEnabled,
                )
            }
        }
    }
}

// Compact: bottom NavigationBar
@Composable
private fun BarLayout(
    current: Screen,
    onScreenChange: (Screen) -> Unit,
    playerState: VideoPlayerState,
    initialVideoUrl: String?,
    demoSubtitleEnabled: Boolean,
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
            playerState,
            initialVideoUrl,
            demoSubtitleEnabled,
        )
    }
}

// Medium+: side NavigationRail
@Composable
private fun RailLayout(
    current: Screen,
    onScreenChange: (Screen) -> Unit,
    playerState: VideoPlayerState,
    initialVideoUrl: String?,
    demoSubtitleEnabled: Boolean,
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
            playerState,
            initialVideoUrl,
            demoSubtitleEnabled,
        )
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    modifier: Modifier,
    playerState: VideoPlayerState,
    initialVideoUrl: String?,
    demoSubtitleEnabled: Boolean,
) {
    when (screen) {
        Screen.Player ->
            PlayerScreen(
                modifier,
                playerState,
                initialVideoUrl = initialVideoUrl,
                demoSubtitleEnabled = demoSubtitleEnabled,
            )
        Screen.Gallery -> GalleryScreen(modifier)
        Screen.Feed -> FeedScreen(modifier)
    }
}
