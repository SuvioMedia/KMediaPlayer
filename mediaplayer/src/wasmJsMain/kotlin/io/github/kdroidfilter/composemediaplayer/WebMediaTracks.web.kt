@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

private data class AudioTrackSnapshot(
    val tracks: List<AudioTrack>,
    val selectedId: String?,
)

private data class SubtitleTrackSnapshot(
    val tracks: List<SubtitleTrack>,
    val selectedId: String?,
)

internal fun DefaultVideoPlayerState.syncWebMediaTracks(video: HTMLVideoElement) {
    val hlsAudioRows = readHlsAudioTrackRows(video)
    val webAudioRows = readWebAudioTrackRows(video)
    val audioSnapshot = parseAudioTrackRows(hlsAudioRows.ifBlank { webAudioRows.ifBlank { readMkvAudioTrackRows(video) } })
    replaceAvailableAudioTracks(audioSnapshot.tracks)
    audioSnapshot.selectedId?.let { selectedId ->
        currentAudioTrack = audioSnapshot.tracks.firstOrNull { it.id == selectedId } ?: currentAudioTrack
    }

    val subtitleRows =
        listOf(readWebTextTrackRows(video), readHlsSubtitleTrackRows(video))
            .plus(readMkvSubtitleTrackRows(video))
            .filter { it.isNotBlank() }
            .joinToString("\n")
    val subtitleSnapshot = parseSubtitleTrackRows(subtitleRows)
    replaceEmbeddedSubtitleTracks(subtitleSnapshot.tracks)
    subtitleSnapshot.selectedId?.let { selectedId ->
        val selectedTrack = subtitleSnapshot.tracks.firstOrNull { it.id == selectedId }
        if (selectedTrack != null) {
            currentSubtitleTrack = selectedTrack
            subtitlesEnabled = true
        }
    }
}

internal fun HTMLVideoElement.applySelectedAudioTrack(track: AudioTrack?) {
    if (track?.id?.startsWith(HLS_AUDIO_TRACK_ID_PREFIX) == true) {
        selectHlsAudioTrackById(video = this, selectedId = track.id)
    } else if (track?.id?.startsWith(MKV_AUDIO_TRACK_ID_PREFIX) == true) {
        selectMkvAudioTrackById(video = this, selectedId = track.id)
    } else {
        selectWebAudioTrackById(video = this, selectedId = track?.id)
    }
}

internal fun HTMLVideoElement.applySelectedSubtitleTrack(track: SubtitleTrack?) {
    selectWebTextTrackById(video = this, selectedId = track?.takeIf { it.isEmbedded }?.id)
}

internal fun addWebMediaTrackListeners(
    video: HTMLVideoElement,
    onChange: () -> Unit,
) {
    attachWebMediaTrackListeners(video, onChange)
}

private fun parseAudioTrackRows(rows: String): AudioTrackSnapshot {
    var selectedId: String? = null
    val tracks =
        rows
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val columns = row.split('|').map(::decodeUriComponent)
                if (columns.size < 5) return@mapNotNull null

                val id = columns[0]
                val label = columns[1].ifBlank { "Audio ${columns[4].toIntOrNull()?.plus(1) ?: ""}".trim() }
                if (columns[3] == "1") selectedId = id

                AudioTrack(
                    id = id,
                    label = label,
                    language = columns[2],
                    channels = columns.getOrNull(5)?.toIntOrNull(),
                    isDefault = columns.getOrNull(6) == "1",
                )
            }.toList()

    return AudioTrackSnapshot(tracks = tracks, selectedId = selectedId)
}

private fun parseSubtitleTrackRows(rows: String): SubtitleTrackSnapshot {
    var selectedId: String? = null
    val tracks =
        rows
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val columns = row.split('|').map(::decodeUriComponent)
                if (columns.size < 6) return@mapNotNull null

                val id = columns[0]
                val label = columns[1].ifBlank { "Subtitle ${columns[5].toIntOrNull()?.plus(1) ?: ""}".trim() }
                val kind = columns[3].ifBlank { "subtitles" }
                if (columns[4] == "showing") selectedId = id

                val src = columns.getOrNull(6).orEmpty()
                val format =
                    columns
                        .getOrNull(7)
                        ?.let { runCatching { SubtitleFormat.valueOf(it) }.getOrNull() }
                        ?: SubtitleFormat.AUTO

                SubtitleTrack(
                    label = label,
                    language = columns[2],
                    src = src,
                    format = format,
                    id = id,
                    isEmbedded = true,
                    kind = kind,
                )
            }.toList()

    return SubtitleTrackSnapshot(tracks = tracks, selectedId = selectedId)
}

private fun decodeUriComponent(value: String): String =
    js("decodeURIComponent(value)")

private fun readWebAudioTrackRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            const list = video.audioTracks;
            if (!list || typeof list.length !== "number") return "";

            const rows = [];
            for (let i = 0; i < list.length; i += 1) {
                const track = list[i];
                const sourceId = track && track.id ? String(track.id) : "";
                const id = "web:audio:" + i + ":" + sourceId;
                rows.push([
                    id,
                    track && track.label ? String(track.label) : "",
                    track && track.language ? String(track.language) : "",
                    track && track.enabled ? "1" : "0",
                    String(i),
                    "",
                    track && track.enabled ? "1" : "0"
                ].map(encodeURIComponent).join("|"));
            }
            return rows.join("\n");
        })()
        """,
    )

private fun readWebTextTrackRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            const list = video.textTracks;
            if (!list || typeof list.length !== "number") return "";

            const rows = [];
            for (let i = 0; i < list.length; i += 1) {
                const track = list[i];
                const kind = track && track.kind ? String(track.kind) : "";
                if (kind && ["subtitles", "captions"].indexOf(kind) === -1) continue;

                const sourceId = track && track.id ? String(track.id) : "";
                const id = "web:text:" + i + ":" + sourceId;
                rows.push([
                    id,
                    track && track.label ? String(track.label) : "",
                    track && track.language ? String(track.language) : "",
                    kind,
                    track && track.mode ? String(track.mode) : "disabled",
                    String(i)
                ].map(encodeURIComponent).join("|"));
            }
            return rows.join("\n");
        })()
        """,
    )

private fun readHlsAudioTrackRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            const hls = video.__composeMediaPlayerHls;
            const list = hls && hls.audioTracks;
            if (!list || typeof list.length !== "number") return "";

            const rows = [];
            const selectedIndex = typeof hls.audioTrack === "number" ? hls.audioTrack : -1;
            for (let i = 0; i < list.length; i += 1) {
                const track = list[i] || {};
                const language = track.lang || track.language || "";
                const name = track.name || language || "";
                const id = "${HLS_AUDIO_TRACK_ID_PREFIX}" + i + ":" + encodeURIComponent(name);
                rows.push([
                    id,
                    name,
                    language,
                    selectedIndex === i ? "1" : "0",
                    String(i),
                    "",
                    track.default ? "1" : "0"
                ].map(encodeURIComponent).join("|"));
            }
            return rows.join("\n");
        })()
        """,
    )

private fun readHlsSubtitleTrackRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            return video.__composeMediaPlayerHlsSubtitleRows || "";
        })()
        """,
    )

private fun readMkvAudioTrackRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            return video.__composeMediaPlayerMkvAudioRows || "";
        })()
        """,
    )

private fun readMkvSubtitleTrackRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            return video.__composeMediaPlayerMkvSubtitleRows || "";
        })()
        """,
    )

private fun selectWebAudioTrackById(
    video: HTMLVideoElement,
    selectedId: String?,
): Unit =
    js(
        """
        {
            const list = video.audioTracks;
            if (list && typeof list.length === "number") {
                for (let i = 0; i < list.length; i += 1) {
                    const track = list[i];
                    const sourceId = track && track.id ? String(track.id) : "";
                    const id = "web:audio:" + i + ":" + sourceId;
                    try {
                        track.enabled = selectedId ? id === selectedId : i === 0;
                    } catch (_) {
                        // Some browsers expose audioTracks as read-only.
                    }
                }
            }
        }
        """,
    )

private fun selectWebTextTrackById(
    video: HTMLVideoElement,
    selectedId: String?,
): Unit =
    js(
        """
        {
            const list = video.textTracks;
            if (list && typeof list.length === "number") {
                for (let i = 0; i < list.length; i += 1) {
                    const track = list[i];
                    const sourceId = track && track.id ? String(track.id) : "";
                    const id = "web:text:" + i + ":" + sourceId;
                    try {
                        track.mode = selectedId && id === selectedId ? "showing" : "disabled";
                    } catch (_) {
                        // Ignore browser-specific native text track limitations.
                    }
                }
            }
        }
        """,
    )

private fun selectHlsAudioTrackById(
    video: HTMLVideoElement,
    selectedId: String?,
): Unit =
    js(
        """
        {
            const hls = video.__composeMediaPlayerHls;
            if (hls && selectedId && selectedId.indexOf("${HLS_AUDIO_TRACK_ID_PREFIX}") === 0) {
                const rest = selectedId.substring("${HLS_AUDIO_TRACK_ID_PREFIX}".length);
                const index = Number(rest.split(":")[0]);
                if (Number.isFinite(index)) {
                    hls.audioTrack = index;
                }
            }
        }
        """,
    )

private fun selectMkvAudioTrackById(
    video: HTMLVideoElement,
    selectedId: String?,
): Unit =
    js(
        """
        {
            video.__composeMediaPlayerMkvSelectedAudioTrackId = selectedId || "";
            const rows = video.__composeMediaPlayerMkvAudioTrackData || [];
            const fallbackSelectedAudio = rows.find(function(track) { return track.isDefault; }) || rows[0] || null;
            const fallbackSelectedAudioId = fallbackSelectedAudio ? fallbackSelectedAudio.id : "";
            video.__composeMediaPlayerMkvAudioRows = rows.map(function(track, index) {
                return [
                    track.id,
                    track.label,
                    track.language || "",
                    selectedId ? (track.id === selectedId ? "1" : "0") : (track.id === fallbackSelectedAudioId ? "1" : "0"),
                    String(index),
                    track.channels ? String(track.channels) : "",
                    track.isDefault ? "1" : "0"
                ].map(encodeURIComponent).join("|");
            }).join("\n");
        }
        """,
    )

private fun attachWebMediaTrackListeners(
    video: HTMLVideoElement,
    onChange: () -> Unit,
): Unit =
    js(
        """
        {
            const attach = function(list) {
                if (!list || typeof list.addEventListener !== "function") return;
                if (list.__composeMediaPlayerTrackListenerAttached) return;
                list.__composeMediaPlayerTrackListenerAttached = true;
                ["addtrack", "removetrack", "change"].forEach(function(eventName) {
                    list.addEventListener(eventName, function() { onChange(); });
                });
            };

            attach(video.audioTracks);
            attach(video.textTracks);
        }
        """,
    )
