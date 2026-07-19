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
    val audioSnapshot =
        parseAudioTrackRows(hlsAudioRows.ifBlank { webAudioRows.ifBlank { readMkvAudioTrackRows(video) } })
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

    replaceWebTextTrackChapters(parseWebMediaChapterRows(readWebChapterRows(video)))
    replaceWebHlsChapters(parseWebMediaChapterRows(readHlsChapterRows(video)))
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

internal fun removeWebMediaTrackListeners(video: HTMLVideoElement) {
    detachWebMediaTrackListeners(video)
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

internal fun parseWebMediaChapterRows(rows: String): List<RawMediaChapter> =
    rows
        .lineSequence()
        .filter(String::isNotBlank)
        .mapNotNull { row ->
            val columns = row.split('|').map(::decodeUriComponent)
            if (columns.size < WEB_CHAPTER_COLUMN_COUNT) return@mapNotNull null
            RawMediaChapter(
                startMs = columns[0].toLongOrNull() ?: return@mapNotNull null,
                endMs = columns[1].toLongOrNull(),
                title = columns[2].ifBlank { null },
                language = columns[3].ifBlank { null },
                isHidden = columns[4] == "1",
            )
        }.toList()

private fun decodeUriComponent(value: String): String = js("decodeURIComponent(value)")

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
                const stableKey = sourceId || [
                    track && track.label ? String(track.label) : "",
                    track && track.language ? String(track.language) : "",
                    String(i)
                ].join("|");
                const id = "web:audio:" + encodeURIComponent(stableKey);
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
                const stableKey = sourceId || [
                    track && track.label ? String(track.label) : "",
                    track && track.language ? String(track.language) : "",
                    kind,
                    String(i)
                ].join("|");
                const id = "web:text:" + encodeURIComponent(stableKey);
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

private fun readWebChapterRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            const list = video.textTracks;
            if (!list || typeof list.length !== "number") return "";

            const preferredLanguages =
                typeof navigator !== "undefined" && Array.isArray(navigator.languages)
                    ? navigator.languages.map(function(value) { return String(value || "").toLowerCase(); })
                    : [];
            const languageScore = function(language) {
                const candidate = String(language || "").toLowerCase();
                for (let i = 0; i < preferredLanguages.length; i += 1) {
                    if (candidate && candidate === preferredLanguages[i]) return i;
                }
                const candidatePrimary = candidate.split("-")[0];
                for (let i = 0; i < preferredLanguages.length; i += 1) {
                    if (candidatePrimary && candidatePrimary === preferredLanguages[i].split("-")[0]) {
                        return 100 + i;
                    }
                }
                return candidate && candidate !== "und" ? 300 : 200;
            };

            const chapterTracks = [];
            for (let i = 0; i < list.length; i += 1) {
                const track = list[i];
                if (!track || String(track.kind || "").toLowerCase() !== "chapters") continue;
                try {
                    if (track.mode === "disabled") track.mode = "hidden";
                } catch (_) {}
                chapterTracks.push({ track: track, index: i, score: languageScore(track.language) });
            }
            chapterTracks.sort(function(left, right) {
                return left.score - right.score || left.index - right.index;
            });
            if (!chapterTracks.length) return "";

            const selected = chapterTracks[0].track;
            const cues = selected.cues;
            if (!cues || typeof cues.length !== "number") return "";
            const rows = [];
            for (let i = 0; i < cues.length; i += 1) {
                const cue = cues[i];
                if (!cue) continue;
                const startSeconds = Number(cue.startTime);
                const endSeconds = Number(cue.endTime);
                if (!Number.isFinite(startSeconds) || startSeconds < 0) continue;
                const startMs = Math.round(startSeconds * 1000);
                const endMs =
                    Number.isFinite(endSeconds) && endSeconds > startSeconds
                        ? String(Math.round(endSeconds * 1000))
                        : "";
                let title = cue.text == null ? "" : String(cue.text);
                if (typeof cue.getCueAsHTML === "function") {
                    try {
                        const fragment = cue.getCueAsHTML();
                        if (fragment && fragment.textContent != null) title = String(fragment.textContent);
                    } catch (_) {}
                }
                rows.push([
                    String(startMs),
                    endMs,
                    title,
                    selected.language ? String(selected.language) : "",
                    "0"
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
                const stableKey = track.id || track.url || track.uri || [name, language, track.groupId || "", String(i)].join("|");
                const id = "${HLS_AUDIO_TRACK_ID_PREFIX}" + encodeURIComponent(stableKey);
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

private fun readHlsChapterRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            return video.__composeMediaPlayerHlsChapterRows || "";
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
                    const stableKey = sourceId || [
                        track && track.label ? String(track.label) : "",
                        track && track.language ? String(track.language) : "",
                        String(i)
                    ].join("|");
                    const id = "web:audio:" + encodeURIComponent(stableKey);
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
                    const kind = track && track.kind ? String(track.kind).toLowerCase() : "";
                    if (kind === "chapters") {
                        try {
                            if (track.mode === "disabled") track.mode = "hidden";
                        } catch (_) {}
                        continue;
                    }
                    if (kind && ["subtitles", "captions"].indexOf(kind) === -1) continue;
                    const sourceId = track && track.id ? String(track.id) : "";
                    const stableKey = sourceId || [
                        track && track.label ? String(track.label) : "",
                        track && track.language ? String(track.language) : "",
                        track && track.kind ? String(track.kind) : "",
                        String(i)
                    ].join("|");
                    const id = "web:text:" + encodeURIComponent(stableKey);
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
                const list = hls.audioTracks || [];
                for (let i = 0; i < list.length; i += 1) {
                    const track = list[i] || {};
                    const language = track.lang || track.language || "";
                    const name = track.name || language || "";
                    const stableKey = track.id || track.url || track.uri || [name, language, track.groupId || "", String(i)].join("|");
                    const id = "${HLS_AUDIO_TRACK_ID_PREFIX}" + encodeURIComponent(stableKey);
                    if (id === selectedId) {
                        hls.audioTrack = i;
                        return;
                    }
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
            if (video.__composeMediaPlayerTrackListenerRecords) {
                video.__composeMediaPlayerTrackListenerRecords.forEach(function(record) {
                    try { record.list.removeEventListener(record.eventName, record.handler); } catch (_) {}
                });
            }
            video.__composeMediaPlayerTrackListenerRecords = [];

            const records = video.__composeMediaPlayerTrackListenerRecords;
            const attachChapterCueListeners = function() {
                const tracks = video.textTracks;
                if (!tracks || typeof tracks.length !== "number") return;
                for (let i = 0; i < tracks.length; i += 1) {
                    const track = tracks[i];
                    if (!track || String(track.kind || "").toLowerCase() !== "chapters") continue;
                    try {
                        if (track.mode === "disabled") track.mode = "hidden";
                    } catch (_) {}
                    if (typeof track.addEventListener !== "function") continue;
                    const alreadyAttached = records.some(function(record) {
                        return record.list === track && record.eventName === "cuechange";
                    });
                    if (alreadyAttached) continue;
                    const handler = function() { onChange(); };
                    track.addEventListener("cuechange", handler);
                    records.push({ list: track, eventName: "cuechange", handler: handler });
                }
            };

            const attach = function(list) {
                if (!list || typeof list.addEventListener !== "function") return;
                ["addtrack", "removetrack", "change"].forEach(function(eventName) {
                    const handler = function() {
                        attachChapterCueListeners();
                        onChange();
                    };
                    list.addEventListener(eventName, handler);
                    records.push({ list, eventName, handler });
                });
            };

            attach(video.audioTracks);
            attach(video.textTracks);
            attachChapterCueListeners();
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun detachWebMediaTrackListeners(video: HTMLVideoElement): Unit =
    js(
        """
        {
            if (video.__composeMediaPlayerTrackListenerRecords) {
                video.__composeMediaPlayerTrackListenerRecords.forEach(function(record) {
                    try { record.list.removeEventListener(record.eventName, record.handler); } catch (_) {}
                });
            }
            video.__composeMediaPlayerTrackListenerRecords = [];
        }
        """,
    )

private const val WEB_CHAPTER_COLUMN_COUNT = 5
