package sample.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack

private const val DEFAULT_SUBTITLE_URL =
    "https://raw.githubusercontent.com/kdroidFilter/ComposeMediaPlayer/refs/heads/master/assets/subtitles/en.vtt"

private const val DEFAULT_ASS_SUBTITLE_URL =
    "/assets/subtitles/en.ass"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleSheet(
    audioTracks: List<AudioTrack>,
    selectedAudioTrack: AudioTrack?,
    onAudioTrackSelected: (AudioTrack) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleTrack: SubtitleTrack?,
    onSubtitleTrackSelected: (SubtitleTrack) -> Unit,
    onDisableSubtitles: () -> Unit,
    onPickFile: () -> Unit,
    onAddTrack: (SubtitleTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    var subtitleUrl by remember { mutableStateOf(DEFAULT_SUBTITLE_URL) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Tracks",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            if (audioTracks.isNotEmpty()) {
                Text(
                    text = "Audio tracks",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column {
                    audioTracks.forEach { track ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAudioTrackSelected(track)
                                        onDismiss()
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedAudioTrack?.id == track.id,
                                onClick = {
                                    onAudioTrackSelected(track)
                                    onDismiss()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(track.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                HorizontalDivider()
            }

            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = subtitleUrl,
                onValueChange = { subtitleUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Subtitle URL") },
                singleLine = true,
                trailingIcon = {
                    FilledTonalIconButton(onClick = {
                        if (subtitleUrl.isNotBlank()) {
                            onAddTrack(
                                SubtitleTrack(
                                    label = "URL Subtitles",
                                    language = "en",
                                    src = subtitleUrl,
                                    isEmbedded = false,
                                ),
                            )
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add subtitle")
                    }
                },
            )

            OutlinedButton(
                onClick = {
                    subtitleUrl = DEFAULT_ASS_SUBTITLE_URL
                    onAddTrack(
                        SubtitleTrack(
                            label = "ASS sample",
                            language = "en",
                            src = DEFAULT_ASS_SUBTITLE_URL,
                            isEmbedded = false,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Subtitles, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add ASS sample")
            }

            // File picker
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Select local file (VTT / SRT / ASS)")
            }

            // Track selection
            if (subtitleTracks.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Subtitle tracks",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column {
                    subtitleTracks.forEach { track ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSubtitleTrackSelected(track)
                                        onDismiss()
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedSubtitleTrack?.id == track.id,
                                onClick = {
                                    onSubtitleTrackSelected(track)
                                    onDismiss()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Subtitles, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(track.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDisableSubtitles()
                                    onDismiss()
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedSubtitleTrack == null,
                            onClick = {
                                onDisableSubtitles()
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun AudioTrack.displayLabel(): String {
    val details =
        listOfNotNull(
            "default".takeIf { isDefault },
            language.takeIf { it.isNotBlank() && !it.equals(label, ignoreCase = true) },
            channels?.let { "$it ch" },
        )
    return if (details.isEmpty()) label else "$label (${details.joinToString(", ")})"
}

private fun SubtitleTrack.displayLabel(): String {
    val format = resolvedFormat().name.lowercase()
    val source = if (isEmbedded) "$format, embedded" else format
    val details =
        listOfNotNull(
            language.takeIf { it.isNotBlank() },
            source,
        )
    return if (details.isEmpty()) label else "$label (${details.joinToString(", ")})"
}
