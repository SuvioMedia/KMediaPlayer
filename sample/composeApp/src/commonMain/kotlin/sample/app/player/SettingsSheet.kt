package sample.app.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DolbyVisionProfileMapping
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    playerState: VideoPlayerState,
    selectedContentScale: ContentScale,
    onContentScaleChange: (ContentScale) -> Unit,
    initialPlayerState: InitialPlayerState,
    onInitialPlayerStateChange: (InitialPlayerState) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // Volume
            Section("Volume") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        playerState.volume = if (playerState.volume > 0f) 0f else 1f
                    }) {
                        Icon(
                            imageVector = if (playerState.volume > 0f)
                                Icons.AutoMirrored.Filled.VolumeUp
                            else
                                Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Toggle mute",
                        )
                    }
                    Slider(
                        value = playerState.volume,
                        onValueChange = { playerState.volume = it },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(playerState.volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }

            // Speed
            Section("Playback Speed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = playerState.playbackSpeed,
                        onValueChange = { playerState.playbackSpeed = it },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(playerState.playbackSpeed * 10).toInt() / 10.0}x",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }

            HorizontalDivider()

            // Content scale
            Section("Content Scale") {
                val scales = listOf(
                    "Fit" to ContentScale.Fit,
                    "Crop" to ContentScale.Crop,
                    "Inside" to ContentScale.Inside,
                    "Fill" to ContentScale.FillBounds,
                    "Fill W" to ContentScale.FillWidth,
                    "Fill H" to ContentScale.FillHeight,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scales.forEach { (label, scale) ->
                        FilterChip(
                            selected = selectedContentScale == scale,
                            onClick = { onContentScaleChange(scale) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Toggles
            Section("Playback") {
                ToggleRow("Loop", playerState.loop) { playerState.loop = it }
                ToggleRow(
                    label = "Auto-play on load",
                    checked = initialPlayerState == InitialPlayerState.PLAY,
                    onCheckedChange = {
                        onInitialPlayerStateChange(
                            if (it) InitialPlayerState.PLAY else InitialPlayerState.PAUSE,
                        )
                    },
                )
                ToggleRow("Picture-in-Picture", playerState.isPipEnabled) {
                    playerState.isPipEnabled = it
                }
            }

            HorizontalDivider()
            Section("Color Pipeline") {
                val status = playerState.colorPipelineStatus.collectAsState().value
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("Policy", status.requestedDynamicRangePolicy.name)
                    InfoRow("Dolby Vision policy", status.requestedDolbyVisionPolicy.name)
                    InfoRow("Source", status.source.dynamicRange.name)
                    InfoRow("Decoder", status.decoderName ?: "Not confirmed")
                    InfoRow("Surface", status.surface.name)
                    InfoRow("Renderer", status.renderer.name)
                    InfoRow("Planned output", status.plannedOutputDynamicRange.name)
                    InfoRow("Active output", status.outputDynamicRange.name)
                    if (status.source.dolbyVision != null) {
                        InfoRow("Source DV", status.source.dolbyVision.profileLabel())
                        InfoRow("Planned DV output", status.plannedOutputDolbyVision.profileLabel())
                        InfoRow("Active DV output", status.outputDolbyVision.profileLabel())
                        status.plannedDolbyVisionProfileMapping?.let { mapping ->
                            InfoRow("Planned DV mapping", mapping.mappingLabel())
                            InfoRow("Planned DV loss", mapping.lossLabel())
                        }
                        status.dolbyVisionProfileMapping?.let { mapping ->
                            InfoRow("Applied DV mapping", mapping.mappingLabel())
                            InfoRow("Applied DV loss", mapping.lossLabel())
                        }
                    }
                    InfoRow("Verification", status.verification.name)
                    InfoRow("Planned metadata", status.plannedMetadataHandling.name)
                    InfoRow("Active metadata", status.metadataHandling.name)
                    InfoRow("Request honored", status.requestHonored.supportLabel())
                    InfoRow("Fallback", status.fallbackReason.name)
                    InfoRow(
                        "Display formats",
                        status.display.supportedDynamicRanges.namesOrUnknown(),
                    )
                    InfoRow(
                        "Decoder formats",
                        status.decoderCapabilities.supportedDynamicRanges.namesOrUnknown(),
                    )
                    if (status.decoderCapabilities.isDolbyVisionProfileSupportKnown) {
                        InfoRow(
                            "Decoder DV profiles",
                            status.decoderCapabilities.supportedDolbyVisionProfiles
                                .sorted()
                                .joinToString { "P$it" }
                                .ifEmpty { "None" },
                        )
                    }
                    InfoRow(
                        "Native renderer",
                        status.rendererCapabilities.nativeSurfaceDynamicRanges.namesOrUnknown(),
                    )
                    InfoRow(
                        "Controlled HDR",
                        status.rendererCapabilities.controlledHdrDynamicRanges.namesOrUnknown(),
                    )
                    InfoRow(
                        "Tone map SDR",
                        status.rendererCapabilities.supportsToneMappingToSdr.supportLabel(),
                    )
                    InfoRow(
                        "DV P7->8.1",
                        status.conversionCapabilities.supportsDolbyVisionProfile7To8.supportLabel(),
                    )
                    status.display.maxLuminanceNits?.let { maxNits ->
                        InfoRow("Display peak", "$maxNits nits")
                    }
                    status.detail?.let { InfoRow("Detail", it) }
                }
            }

            if (!playerState.metadata.isAllNull()) {
                HorizontalDivider()
                Section("Metadata") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        playerState.metadata.title?.let { InfoRow("Title", it) }
                        playerState.metadata.width?.let { w ->
                            playerState.metadata.height?.let { h ->
                                InfoRow("Resolution", "${w}x$h")
                            }
                        }
                        playerState.metadata.frameRate?.let { InfoRow("Frame Rate", "$it fps") }
                        playerState.metadata.bitrate?.let { InfoRow("Bitrate", "${it / 1000} kbps") }
                        playerState.metadata.mimeType?.let { InfoRow("Format", it) }
                        playerState.metadata.audioChannels?.let { ch ->
                            playerState.metadata.audioSampleRate?.let { sr ->
                                InfoRow("Audio", "$ch ch, ${sr / 1000} kHz")
                            }
                        }
                    }
                }
            }

            if (!playerState.renderingInfo.isAllNull()) {
                HorizontalDivider()
                Section("Rendering") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        playerState.renderingInfo.backend?.let { InfoRow("Backend", it) }
                        playerState.renderingInfo.container?.let { InfoRow("Container", it) }
                        playerState.renderingInfo.videoDecoder?.let { InfoRow("Video decode", it) }
                        playerState.renderingInfo.videoRenderer?.let { InfoRow("Video render", it) }
                        playerState.renderingInfo.audioRenderer?.let { InfoRow("Audio", it) }
                        playerState.renderingInfo.subtitleRenderer?.let { InfoRow("Subtitles", it) }
                        playerState.renderingInfo.subtitleSource?.let { InfoRow("Subtitle source", it) }
                        playerState.renderingInfo.notes?.let { InfoRow("Notes", it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun Set<Enum<*>>.namesOrUnknown(): String =
    if (isEmpty()) "Unknown" else joinToString { it.name }

private fun Boolean.supportLabel(): String = if (this) "Supported" else "Not supported"

private fun DolbyVisionInfo?.profileLabel(): String =
    when {
        this == null -> "None / pending"
        profile == 8 && hasHdr10CompatibleBaseLayer -> "Profile 8.1"
        profile != null -> "Profile $profile"
        else -> "Dolby Vision (profile unknown)"
    }

private fun DolbyVisionProfileMapping.mappingLabel(): String =
    "P${sourceProfile ?: "?"} -> " +
        if (outputProfile == 8 && outputHasHdr10CompatibleBaseLayer) "P8.1" else "P${outputProfile ?: "?"}"

private fun DolbyVisionProfileMapping.lossLabel(): String =
    when {
        felMappingDiscarded == true -> "FEL layer + FEL mapping discarded"
        enhancementLayerDiscarded == true -> "MEL enhancement layer discarded"
        enhancementLayerDiscarded == false -> "No enhancement layer"
        else -> "MEL/FEL not identified"
    }
