@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

private const val MATROSKA_SUBTITLES_SCRIPT_ID = "compose-media-player-matroska-subtitles"
private const val MATROSKA_SUBTITLES_SCRIPT_URL =
    "https://cdn.jsdelivr.net/npm/matroska-subtitles@3.x/dist/matroska-subtitles.min.js"
private const val MKV_TRACK_PROBE_RANGE_HEADER = "bytes=0-65535"

internal const val MKV_AUDIO_TRACK_ID_PREFIX = "mkv:audio:"
internal const val MKV_SUBTITLE_TRACK_ID_PREFIX = "mkv:subtitle:"

private var matroskaSubtitlesScriptLoad: CompletableDeferred<Boolean>? = null

internal fun String.isLikelyMkvSource(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mkv") ||
        contains("content-disposition", ignoreCase = true) ||
        contains("nexus-", ignoreCase = true)
}

internal suspend fun HTMLVideoElement.configureMkvSidecarTracks(
    playerState: DefaultVideoPlayerState,
    sourceUri: String,
    scope: CoroutineScope,
) {
    destroyMkvSidecarTracks()
    if (!sourceUri.isLikelyMkvSource()) return

    startMkvSidecarProbe(
        video = this,
        sourceUri = sourceUri,
        onTracksChanged = {
            scope.launch {
                playerState.syncWebMediaTracks(this@configureMkvSidecarTracks)
                this@configureMkvSidecarTracks.applySelectedAudioTrack(playerState.currentAudioTrack)
            }
        },
    )
}

private suspend fun ensureMatroskaSubtitlesScriptLoaded(): Boolean {
    if (isMatroskaSubtitlesLoaded()) return true

    matroskaSubtitlesScriptLoad?.let { return it.await() }

    val deferred = CompletableDeferred<Boolean>()
    matroskaSubtitlesScriptLoad = deferred

    val script =
        (document.getElementById(MATROSKA_SUBTITLES_SCRIPT_ID) as? HTMLScriptElement)
            ?: (document.createElement("script") as HTMLScriptElement).apply {
                id = MATROSKA_SUBTITLES_SCRIPT_ID
                src = MATROSKA_SUBTITLES_SCRIPT_URL
                setAttribute("async", "true")
            }

    if (script.getAttribute("data-loaded") == "true") {
        deferred.complete(true)
    } else {
        script.addEventListener("load", {
            script.setAttribute("data-loaded", "true")
            deferred.complete(true)
        })
        script.addEventListener("error", {
            webVideoLogger.e { "Failed to load $MATROSKA_SUBTITLES_SCRIPT_URL" }
            deferred.complete(false)
        })
    }

    if (script.parentNode == null) {
        (document.head ?: document.body)?.appendChild(script)
    }

    val loaded = deferred.await()
    if (!loaded) matroskaSubtitlesScriptLoad = null
    return loaded && isMatroskaSubtitlesLoaded()
}

private fun isMatroskaSubtitlesLoaded(): Boolean =
    js("typeof globalThis.MatroskaSubtitles === 'object' && typeof globalThis.MatroskaSubtitles.SubtitleParser === 'function'")

@Suppress("UNUSED_PARAMETER")
private fun startMkvSidecarProbe(
    video: HTMLVideoElement,
    sourceUri: String,
    onTracksChanged: () -> Unit,
): Unit =
    js(
        """
        {
            const notify = function() {
                try { onTracksChanged(); } catch (error) { console.error(error); }
            };

            if (video.__composeMediaPlayerMkvAbort) {
                try { video.__composeMediaPlayerMkvAbort.abort(); } catch (_) {}
            }
            if (video.__composeMediaPlayerMkvBlobUrls) {
                video.__composeMediaPlayerMkvBlobUrls.forEach(function(url) {
                    try { URL.revokeObjectURL(url); } catch (_) {}
                });
            }

            const abort = new AbortController();
            video.__composeMediaPlayerMkvAbort = abort;
            video.__composeMediaPlayerMkvAudioRows = "";
            video.__composeMediaPlayerMkvSubtitleRows = "";
            video.__composeMediaPlayerMkvAudioTrackData = [];
            video.__composeMediaPlayerMkvSubtitleTrackData = [];
            video.__composeMediaPlayerMkvBlobUrls = [];
            video.__composeMediaPlayerMkvSelectedAudioTrackId = "";
            video.__composeMediaPlayerMkvSelectedSubtitleTrackId = "";
            video.__composeMediaPlayerMkvSourceUri = sourceUri;
            video.__composeMediaPlayerMkvExtractingSubtitleTrackId = "";
            if (video.__composeMediaPlayerMkvExtractAbort) {
                try { video.__composeMediaPlayerMkvExtractAbort.abort(); } catch (_) {}
                video.__composeMediaPlayerMkvExtractAbort = null;
            }

            const encodeRow = function(values) {
                return values.map(function(value) { return encodeURIComponent(value == null ? "" : String(value)); }).join("|");
            };

            const decodeText = function(bytes) {
                return new TextDecoder("utf-8").decode(bytes).replace(/\u0000+$/g, "");
            };

            const readVint = function(data, offset, keepMarker) {
                const first = data[offset];
                if (!first) return null;
                let mask = 0x80;
                let length = 1;
                while (length <= 8 && (first & mask) === 0) {
                    mask >>= 1;
                    length += 1;
                }
                if (length > 8 || offset + length > data.length) return null;
                let value = keepMarker ? first : (first & (mask - 1));
                for (let i = 1; i < length; i += 1) {
                    value = value * 256 + data[offset + i];
                }
                return { length, value };
            };

            const readUInt = function(bytes) {
                let value = 0;
                for (let i = 0; i < bytes.length; i += 1) value = value * 256 + bytes[i];
                return value;
            };

            const readFloat = function(bytes) {
                const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
                if (bytes.length === 4) return view.getFloat32(0, false);
                if (bytes.length === 8) return view.getFloat64(0, false);
                return undefined;
            };

            const parseChildren = function(data, start, end, callback, depth) {
                let offset = start;
                const limit = Math.min(end, data.length);
                while (offset + 2 <= limit) {
                    const id = readVint(data, offset, true);
                    if (!id) break;
                    const size = readVint(data, offset + id.length, false);
                    if (!size) break;
                    const payloadStart = offset + id.length + size.length;
                    const payloadEnd = payloadStart + size.value;
                    if (payloadStart > data.length) break;
                    callback(id.value, payloadStart, Math.min(payloadEnd, data.length), size.value);
                    if (
                        depth < 8 &&
                        [0x1a45dfa3, 0x18538067, 0x1549a966, 0x1654ae6b, 0xae, 0xe1].indexOf(id.value) !== -1
                    ) {
                        parseChildren(data, payloadStart, payloadEnd, callback, depth + 1);
                    }
                    offset = payloadEnd;
                }
            };

            const parseTrackEntry = function(data, start, end) {
                const track = { number: "", type: 0, codec: "", language: "", name: "", channels: "", isDefault: true };
                parseChildren(data, start, end, function(id, payloadStart, payloadEnd) {
                    const bytes = data.slice(payloadStart, payloadEnd);
                    if (id === 0xd7) track.number = String(readUInt(bytes));
                    if (id === 0x83) track.type = readUInt(bytes);
                    if (id === 0x86) track.codec = decodeText(bytes);
                    if (id === 0x22b59c) track.language = decodeText(bytes);
                    if (id === 0x22b59d) track.language = decodeText(bytes);
                    if (id === 0x536e) track.name = decodeText(bytes);
                    if (id === 0x88) track.isDefault = readUInt(bytes) !== 0;
                    if (id === 0x9f) track.channels = String(readUInt(bytes));
                    if (id === 0xb5) track.sampleRate = String(Math.round(readFloat(bytes) || 0));
                }, 0);
                return track;
            };

            const probeContainerTracks = function() {
                return fetch(sourceUri, {
                    headers: { Range: "${MKV_TRACK_PROBE_RANGE_HEADER}" },
                    signal: abort.signal
                }).then(function(response) {
                    return response.arrayBuffer();
                }).then(function(buffer) {
                    const bytes = new Uint8Array(buffer);
                    const audio = [];
                    const subtitles = [];
                    parseChildren(bytes, 0, bytes.length, function(id, payloadStart, payloadEnd) {
                        if (id !== 0xae) return;
                        const track = parseTrackEntry(bytes, payloadStart, payloadEnd);
                        if (track.type === 2) {
                            const index = audio.length;
                            const label = track.name || track.language || ("Audio " + (index + 1));
                            const idValue = "${MKV_AUDIO_TRACK_ID_PREFIX}" + track.number;
                            audio.push({
                                id: idValue,
                                label,
                                language: track.language || "",
                                channels: track.channels || "",
                                sampleRate: track.sampleRate || "",
                                isDefault: track.isDefault
                            });
                        } else if (track.type === 17) {
                            const codec = (track.codec || "").toUpperCase();
                            let subtitleType = "";
                            if (codec.indexOf("ASS") !== -1) subtitleType = "ass";
                            else if (codec.indexOf("SSA") !== -1) subtitleType = "ssa";
                            else if (codec.indexOf("UTF8") !== -1 || codec.indexOf("SRT") !== -1) subtitleType = "utf8";
                            if (!subtitleType) return;
                            subtitles.push({
                                number: track.number,
                                type: subtitleType,
                                name: track.name || "",
                                language: track.language || "",
                                isDefault: track.isDefault,
                                _blobUrl: ""
                            });
                        }
                    }, 0);

                    video.__composeMediaPlayerMkvAudioTrackData = audio;
                    video.__composeMediaPlayerMkvSubtitleTrackData = subtitles;
                    const fallbackSelectedAudio = audio.find(function(track) { return track.isDefault; }) || audio[0] || null;
                    const fallbackSelectedAudioId = fallbackSelectedAudio ? fallbackSelectedAudio.id : "";
                    video.__composeMediaPlayerMkvAudioRows = audio.map(function(track, index) {
                        return encodeRow([
                            track.id,
                            track.label,
                            track.language,
                            video.__composeMediaPlayerMkvSelectedAudioTrackId
                                ? (track.id === video.__composeMediaPlayerMkvSelectedAudioTrackId ? "1" : "0")
                                : (track.id === fallbackSelectedAudioId ? "1" : "0"),
                            String(index),
                            track.channels,
                            track.isDefault ? "1" : "0"
                        ]);
                    }).join("\n");
                    updateSubtitleRows(subtitles);
                    notify();
                });
            };

            const updateSubtitleRows = function(tracks) {
                const rows = tracks.map(function(track, index) {
                    const idValue = "${MKV_SUBTITLE_TRACK_ID_PREFIX}" + track.number;
                    return encodeRow([
                        idValue,
                        track.name || (track.language ? track.language.toUpperCase() : "Subtitle " + (index + 1)),
                        track.language || "",
                        "subtitles",
                        video.__composeMediaPlayerMkvSelectedSubtitleTrackId === idValue ? "showing" : "disabled",
                        String(index),
                        track._blobUrl || "",
                        track.type === "ssa" ? "SSA" : (track.type === "ass" ? "ASS" : "SRT")
                    ]);
                });
                video.__composeMediaPlayerMkvSubtitleRows = rows.join("\n");
                notify();
            };

            probeContainerTracks().catch(function(error) {
                if (error && error.name !== "AbortError") console.warn("[compose-media-player] MKV track probe", error);
            });
        }
        """,
    )

internal suspend fun HTMLVideoElement.extractMkvSubtitleTrack(
    track: SubtitleTrack?,
    playerState: DefaultVideoPlayerState,
    scope: CoroutineScope,
) {
    if (track?.id?.startsWith(MKV_SUBTITLE_TRACK_ID_PREFIX) != true) {
        cancelMkvSubtitleExtraction()
        return
    }
    if (!ensureMatroskaSubtitlesScriptLoaded()) return

    startMkvSubtitleExtraction(
        video = this,
        selectedId = track.id,
        onTracksChanged = {
            scope.launch {
                playerState.syncWebMediaTracks(this@extractMkvSubtitleTrack)
            }
        },
    )
}

@Suppress("UNUSED_PARAMETER")
private fun startMkvSubtitleExtraction(
    video: HTMLVideoElement,
    selectedId: String,
    onTracksChanged: () -> Unit,
): Unit =
    js(
        """
        {
            const notify = function() {
                try { onTracksChanged(); } catch (error) { console.error(error); }
            };
            const sourceUri = video.__composeMediaPlayerMkvSourceUri || "";
            const tracks = video.__composeMediaPlayerMkvSubtitleTrackData || [];

            const encodeRow = function(values) {
                return values.map(function(value) { return encodeURIComponent(value == null ? "" : String(value)); }).join("|");
            };
            const updateSubtitleRows = function() {
                video.__composeMediaPlayerMkvSubtitleRows = tracks.map(function(track, index) {
                    const idValue = "${MKV_SUBTITLE_TRACK_ID_PREFIX}" + track.number;
                    return encodeRow([
                        idValue,
                        track.name || (track.language ? track.language.toUpperCase() : "Subtitle " + (index + 1)),
                        track.language || "",
                        "subtitles",
                        video.__composeMediaPlayerMkvSelectedSubtitleTrackId === idValue ? "showing" : "disabled",
                        String(index),
                        track._blobUrl || "",
                        track.type === "ssa" ? "SSA" : (track.type === "ass" ? "ASS" : "SRT")
                    ]);
                }).join("\n");
            };

            video.__composeMediaPlayerMkvSelectedSubtitleTrackId = selectedId || "";
            updateSubtitleRows();
            notify();

            if (!sourceUri || !selectedId || selectedId.indexOf("${MKV_SUBTITLE_TRACK_ID_PREFIX}") !== 0) return;
            const targetNumber = selectedId.substring("${MKV_SUBTITLE_TRACK_ID_PREFIX}".length);
            const target = tracks.find(function(track) { return String(track.number) === targetNumber; });
            if (!target || target._blobUrl) return;

            if (video.__composeMediaPlayerMkvExtractAbort) {
                try { video.__composeMediaPlayerMkvExtractAbort.abort(); } catch (_) {}
            }
            const extractAbort = new AbortController();
            video.__composeMediaPlayerMkvExtractAbort = extractAbort;
            video.__composeMediaPlayerMkvExtractingSubtitleTrackId = selectedId;

            const formatTime = function(ms) {
                const totalCentis = Math.max(0, Math.round(ms / 10));
                const centis = totalCentis % 100;
                const totalSeconds = Math.floor(totalCentis / 100);
                const seconds = totalSeconds % 60;
                const totalMinutes = Math.floor(totalSeconds / 60);
                const minutes = totalMinutes % 60;
                const hours = Math.floor(totalMinutes / 60);
                return hours + ":" +
                    String(minutes).padStart(2, "0") + ":" +
                    String(seconds).padStart(2, "0") + "." +
                    String(centis).padStart(2, "0");
            };
            const buildAss = function(track, lines) {
                const header = track.header || "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,24,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,10,10,18,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text";
                return header.trimEnd() + "\n" + lines.join("\n") + "\n";
            };
            const publish = function() {
                const blobUrl = URL.createObjectURL(
                    new Blob([buildAss(target, lines)], { type: "text/plain;charset=utf-8" })
                );
                target._blobUrl = blobUrl;
                video.__composeMediaPlayerMkvBlobUrls.push(blobUrl);
                updateSubtitleRows();
                notify();
            };

            const Parser = globalThis.MatroskaSubtitles.SubtitleParser;
            const parser = new Parser();
            const lines = [];
            let publishedFirstLine = false;

            parser.once("tracks", function(parsedTracks) {
                const fresh = parsedTracks.find(function(track) { return String(track.number) === targetNumber; });
                if (fresh) {
                    target.header = fresh.header || target.header;
                    target.type = fresh.type || target.type;
                    target.name = fresh.name || target.name;
                    target.language = fresh.language || target.language;
                }
            });
            parser.on("subtitle", function(subtitle, trackNumber) {
                if (String(trackNumber) !== targetNumber) return;
                const end = subtitle.time + subtitle.duration;
                if (target.type === "ass" || target.type === "ssa") {
                    lines.push("Dialogue: " + [
                        subtitle.layer || "0",
                        formatTime(subtitle.time),
                        formatTime(end),
                        subtitle.style || "Default",
                        subtitle.name || "",
                        subtitle.marginL || "0",
                        subtitle.marginR || "0",
                        subtitle.marginV || "0",
                        subtitle.effect || "",
                        subtitle.text || ""
                    ].join(","));
                } else {
                    lines.push(
                        "Dialogue: 0," + formatTime(subtitle.time) + "," + formatTime(end) + ",Default,,0,0,0,," + (subtitle.text || "")
                    );
                }
                if (!publishedFirstLine || lines.length % 50 === 0) {
                    publishedFirstLine = true;
                    publish();
                }
            });
            parser.on("end", function() {
                if (lines.length > 0) publish();
                if (video.__composeMediaPlayerMkvExtractAbort === extractAbort) {
                    video.__composeMediaPlayerMkvExtractAbort = null;
                }
                video.__composeMediaPlayerMkvExtractingSubtitleTrackId = "";
            });
            parser.on("error", function(error) {
                console.warn("[compose-media-player] MKV selected subtitle parser", error);
            });

            fetch(sourceUri, { signal: extractAbort.signal }).then(function(response) {
                if (!response.body) {
                    return response.arrayBuffer().then(function(buffer) {
                        parser.write(new Uint8Array(buffer));
                        parser.end();
                    });
                }
                const reader = response.body.getReader();
                const pump = function() {
                    return reader.read().then(function(result) {
                        if (result.done) {
                            parser.end();
                            return;
                        }
                        parser.write(result.value);
                        return pump();
                    });
                };
                return pump();
            }).catch(function(error) {
                if (error && error.name !== "AbortError") {
                    console.warn("[compose-media-player] MKV selected subtitle fetch", error);
                }
            });
        }
        """,
    )

internal fun HTMLVideoElement.cancelMkvSubtitleExtraction() {
    cancelMkvSubtitleExtraction(this)
}

@Suppress("UNUSED_PARAMETER")
private fun cancelMkvSubtitleExtraction(video: HTMLVideoElement): Unit =
    js(
        """
        {
            if (video.__composeMediaPlayerMkvExtractAbort) {
                try { video.__composeMediaPlayerMkvExtractAbort.abort(); } catch (_) {}
                video.__composeMediaPlayerMkvExtractAbort = null;
            }
            video.__composeMediaPlayerMkvExtractingSubtitleTrackId = "";
            video.__composeMediaPlayerMkvSelectedSubtitleTrackId = "";
        }
        """,
    )

internal fun HTMLVideoElement.destroyMkvSidecarTracks() {
    destroyMkvSidecarTracks(this)
}

@Suppress("UNUSED_PARAMETER")
private fun destroyMkvSidecarTracks(video: HTMLVideoElement): Unit =
    js(
        """
        {
            if (video.__composeMediaPlayerMkvAbort) {
                try { video.__composeMediaPlayerMkvAbort.abort(); } catch (_) {}
                video.__composeMediaPlayerMkvAbort = null;
            }
            if (video.__composeMediaPlayerMkvExtractAbort) {
                try { video.__composeMediaPlayerMkvExtractAbort.abort(); } catch (_) {}
                video.__composeMediaPlayerMkvExtractAbort = null;
            }
            if (video.__composeMediaPlayerMkvBlobUrls) {
                video.__composeMediaPlayerMkvBlobUrls.forEach(function(url) {
                    try { URL.revokeObjectURL(url); } catch (_) {}
                });
            }
            video.__composeMediaPlayerMkvAudioRows = "";
            video.__composeMediaPlayerMkvSubtitleRows = "";
            video.__composeMediaPlayerMkvAudioTrackData = [];
            video.__composeMediaPlayerMkvSubtitleTrackData = [];
            video.__composeMediaPlayerMkvBlobUrls = [];
            video.__composeMediaPlayerMkvSelectedAudioTrackId = "";
            video.__composeMediaPlayerMkvSelectedSubtitleTrackId = "";
            video.__composeMediaPlayerMkvExtractingSubtitleTrackId = "";
            video.__composeMediaPlayerMkvSourceUri = "";
        }
        """,
    )
