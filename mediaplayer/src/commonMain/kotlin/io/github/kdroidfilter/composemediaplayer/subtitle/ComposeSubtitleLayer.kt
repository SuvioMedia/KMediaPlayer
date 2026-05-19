package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.MAC_FFMPEG_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A composable function that displays subtitles over a video player.
 * This component handles loading and parsing subtitle files, and displaying
 * the active subtitles at the current playback time.
 *
 * @param currentTime The current playback time
 * @param duration The total duration of the media
 * @param isPlaying Whether the video is currently playing
 * @param subtitleTrack The current subtitle track, or null if no subtitle is selected
 * @param subtitlesEnabled Whether subtitles are enabled
 * @param modifier The modifier to be applied to the layout
 * @param textStyle The text style to be applied to the subtitle text
 * @param backgroundColor The background color of the subtitle box
 */
@Composable
fun ComposeSubtitleLayer(
    currentTime: Duration,
    duration: Duration,
    isPlaying: Boolean,
    subtitleTrack: SubtitleTrack?,
    subtitlesEnabled: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle =
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    backgroundColor: Color = Color.Black.copy(alpha = 0.5f),
) {
    // State to hold the parsed subtitle cues
    var subtitles by remember { mutableStateOf<SubtitleCueList?>(null) }

    // Load subtitles when the subtitle track changes
    LaunchedEffect(subtitleTrack, subtitlesEnabled) {
        if (subtitleTrack == null || !subtitlesEnabled) {
            subtitles = null
            return@LaunchedEffect
        }

        val shouldRefreshLiveSidecar = subtitleTrack.id.startsWith(MAC_FFMPEG_SUBTITLE_TRACK_ID_PREFIX)
        do {
            subtitles = loadAndParseSubtitles(subtitleTrack)
            if (shouldRefreshLiveSidecar) {
                delay(2_000.milliseconds)
            }
        } while (shouldRefreshLiveSidecar)
    }

    // Display the subtitles if available
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        subtitles?.let { cueList ->
            if (subtitlesEnabled) {
                AutoUpdatingSubtitleDisplay(
                    subtitles = cueList,
                    currentTime = currentTime,
                    isPlaying = isPlaying,
                    textStyle = textStyle,
                    backgroundColor = backgroundColor,
                )
            }
        }
    }
}

private suspend fun loadAndParseSubtitles(subtitleTrack: SubtitleTrack): SubtitleCueList =
    try {
        withContext(Dispatchers.Default) {
            // Load and parse the subtitle file
            val content = loadSubtitleContent(subtitleTrack.src)

            // Determine the subtitle format based on file extension and content
            val resolvedFormat =
                subtitleTrack.resolvedFormat().takeUnless { it == SubtitleFormat.AUTO }
                    ?: SubtitleFormat.fromContent(content)

            when (resolvedFormat) {
                SubtitleFormat.SRT -> SrtParser.parse(content)
                SubtitleFormat.ASS,
                SubtitleFormat.SSA,
                -> AssParser.parse(content)
                SubtitleFormat.WEBVTT,
                SubtitleFormat.AUTO,
                -> WebVttParser.parse(content)
            }
        }
    } catch (e: Exception) {
        // If there's an error loading or parsing the subtitle file,
        // return an empty subtitle list
        SubtitleCueList()
    }

/**
 * Loads the content of a subtitle file from the given source.
 * This is implemented in a platform-specific way.
 */
expect suspend fun loadSubtitleContent(src: String): String
