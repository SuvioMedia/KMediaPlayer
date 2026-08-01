package sample.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaSourceSheet(
    videoUrl: String,
    sampleVideos: List<Pair<String, String>>,
    desktopMkvBackendAvailable: Boolean,
    desktopMkvBackendOptions: List<DesktopMkvPlaybackBackendOption>,
    selectedDesktopMkvBackend: DesktopMkvPlaybackBackend,
    onUrlChange: (String) -> Unit,
    onDesktopMkvBackendChange: (DesktopMkvPlaybackBackend) -> Unit,
    onLoadUrl: () -> Unit,
    onPickFile: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                text = "Media Source",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // URL input
            OutlinedTextField(
                value = videoUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Video URL") },
                trailingIcon = {
                    FilledTonalIconButton(onClick = onLoadUrl) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Load")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            if (desktopMkvBackendAvailable) {
                HorizontalDivider()

                val backendOptions =
                    desktopMkvBackendOptions.ifEmpty {
                        DesktopMkvPlaybackBackend.entries.map { backend ->
                            DesktopMkvPlaybackBackendOption(
                                backend = backend,
                                enabled = true,
                                status = "Availability was not reported by this platform.",
                            )
                        }
                    }
                val selectedBackendOption = backendOptions.firstOrNull { it.backend == selectedDesktopMkvBackend }

                Text(
                    text = "JVM playback backend",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    backendOptions.forEach { option ->
                        FilterChip(
                            enabled = option.enabled,
                            selected = selectedDesktopMkvBackend == option.backend,
                            onClick = { onDesktopMkvBackendChange(option.backend) },
                            label = { Text(option.backend.label) },
                        )
                    }
                }

                selectedBackendOption?.let { option ->
                    Text(
                        text = listOfNotNull(option.status, option.installHint).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                backendOptions
                    .filter { option -> !option.enabled && option.backend != DesktopMkvPlaybackBackend.AUTO }
                    .forEach { option ->
                        Text(
                            text = "${option.backend.label}: ${listOfNotNull(option.status, option.installHint).joinToString(" ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
            }

            // File picker
            OutlinedButton(
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open local file")
            }

            HorizontalDivider()

            // Sample videos
            Text(
                text = "Sample videos",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sampleVideos.forEach { (name, url) ->
                    FilterChip(
                        selected = videoUrl == url,
                        onClick = { onSelectPreset(url) },
                        label = { Text(name) },
                    )
                }
            }
        }
    }
}
