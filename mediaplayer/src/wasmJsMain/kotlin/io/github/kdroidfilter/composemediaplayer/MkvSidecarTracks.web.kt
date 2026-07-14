@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import kotlin.time.Duration.Companion.seconds

private const val MATROSKA_SUBTITLES_SCRIPT_ID = "compose-media-player-matroska-subtitles"
private const val MKV_TRACK_PROBE_RANGE_HEADER = "bytes=0-65535"
private val MATROSKA_SCRIPT_LOAD_TIMEOUT = 15.seconds

internal const val MKV_AUDIO_TRACK_ID_PREFIX = "mkv:audio:"
internal const val MKV_SUBTITLE_TRACK_ID_PREFIX = "mkv:subtitle:"

private var matroskaSubtitlesScriptLoad: CompletableDeferred<Boolean>? = null
private var matroskaSubtitlesScriptKey: String? = null

internal fun String.isLikelyMkvSource(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mkv") ||
        contains("content-disposition", ignoreCase = true)
}

internal suspend fun HTMLVideoElement.configureMkvSidecarTracks(
    playerState: DefaultVideoPlayerState,
    sourceUri: String,
    requestHeadersJson: String,
    useCredentials: Boolean,
    scope: CoroutineScope,
    mediaSessionId: Long,
) {
    destroyMkvSidecarTracks()
    if (!sourceUri.isLikelyMkvSource()) return

    startMkvSidecarProbe(
        video = this,
        sourceUri = sourceUri,
        requestHeadersJson = requestHeadersJson,
        useCredentials = useCredentials,
        onTracksChanged = {
            scope.launch {
                if (!playerState.isCurrentMediaSession(mediaSessionId)) return@launch
                playerState.syncWebMediaTracks(this@configureMkvSidecarTracks)
                this@configureMkvSidecarTracks.applySelectedAudioTrack(playerState.currentAudioTrack)
                if (hasMkvSubtitleTracks(this@configureMkvSidecarTracks)) {
                    scope.launch {
                        if (!playerState.isCurrentMediaSession(mediaSessionId)) return@launch
                        ensureMatroskaSubtitlesModuleLoaded()
                        ensureAssRendererScriptLoaded()
                    }
                }
            }
        },
        onTracksChangedError = { message ->
            webVideoLogger.e { "MKV track sync callback failed: $message" }
        },
        onWarning = { message ->
            webVideoLogger.w { message }
        },
    )
}

internal suspend fun ensureMatroskaSubtitlesModuleLoaded(): Boolean {
    val scriptUrl = WebMediaDependencyConfig.matroskaSubtitlesScriptUrl.trim()
    if (scriptUrl.isEmpty()) return false
    if (isMatroskaSubtitlesLoaded()) return true
    val integrity = WebMediaDependencyConfig.matroskaSubtitlesScriptIntegrity.trim()
    val scriptKey = "$scriptUrl|$integrity"
    matroskaSubtitlesScriptLoad
        ?.takeIf { matroskaSubtitlesScriptKey == scriptKey }
        ?.let { pending ->
            return withTimeoutOrNull(MATROSKA_SCRIPT_LOAD_TIMEOUT) { pending.await() } == true &&
                isMatroskaSubtitlesLoaded()
        }

    val deferred = CompletableDeferred<Boolean>()
    matroskaSubtitlesScriptLoad = deferred
    matroskaSubtitlesScriptKey = scriptKey

    val existingScript = document.getElementById(MATROSKA_SUBTITLES_SCRIPT_ID) as? HTMLScriptElement
    val script =
        existingScript
            ?.takeIf { it.getAttribute("data-source-key") == scriptKey }
            ?: (document.createElement("script") as HTMLScriptElement).apply {
                existingScript?.parentNode?.removeChild(existingScript)
                id = MATROSKA_SUBTITLES_SCRIPT_ID
                src = scriptUrl
                setAttribute("data-source-key", scriptKey)
                setAttribute("async", "true")
                if (integrity.isNotEmpty()) {
                    setAttribute("integrity", integrity)
                    setAttribute("crossorigin", "anonymous")
                }
            }

    if (script.getAttribute("data-loaded") == "true") {
        deferred.complete(isMatroskaSubtitlesLoaded())
    } else {
        script.addEventListener("load", {
            script.setAttribute("data-loaded", "true")
            deferred.complete(isMatroskaSubtitlesLoaded())
        })
        script.addEventListener("error", {
            webVideoLogger.e { "Failed to load the configured Matroska subtitle parser" }
            deferred.complete(false)
        })
    }

    if (script.parentNode == null) {
        (document.head ?: document.body)?.appendChild(script)
    }

    val loaded = withTimeoutOrNull(MATROSKA_SCRIPT_LOAD_TIMEOUT) { deferred.await() } == true
    if (!loaded) {
        script.parentNode?.removeChild(script)
        if (matroskaSubtitlesScriptLoad === deferred) {
            matroskaSubtitlesScriptLoad = null
            matroskaSubtitlesScriptKey = null
        }
    }
    return loaded && isMatroskaSubtitlesLoaded()
}

private fun isMatroskaSubtitlesLoaded(): Boolean =
    js(
        "typeof globalThis.MatroskaSubtitles === 'object' && typeof globalThis.MatroskaSubtitles.SubtitleParser === 'function'",
    )

@Suppress("UNUSED_PARAMETER")
private fun hasMkvSubtitleTracks(video: HTMLVideoElement): Boolean =
    js(
        """
        Array.isArray(video.__composeMediaPlayerMkvSubtitleTrackData) &&
            video.__composeMediaPlayerMkvSubtitleTrackData.length > 0
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun startMkvSidecarProbe(
    video: HTMLVideoElement,
    sourceUri: String,
    requestHeadersJson: String,
    useCredentials: Boolean,
    onTracksChanged: () -> Unit,
    onTracksChangedError: (String) -> Unit,
    onWarning: (String) -> Unit,
): Unit =
    js(
        """
        {
            const describeError = function(error) {
                return error && error.stack ? error.stack : (error && error.message ? error.message : String(error));
            };
            const warn = function(context, error) {
                try { onWarning(context + ": " + describeError(error)); } catch (_) {}
            };
            const notify = function() {
                try {
                    onTracksChanged();
                } catch (error) {
                    try { onTracksChangedError(String(describeError(error))); } catch (_) {}
                }
            };
            const requestHeaders = (function() {
                try { return JSON.parse(requestHeadersJson || "{}") || {}; } catch (_) { return {}; }
            })();
            const fetchOptions = function(headers, signal, method) {
                const mergedHeaders = {};
                Object.keys(requestHeaders).forEach(function(name) {
                    mergedHeaders[name] = requestHeaders[name];
                });
                Object.keys(headers || {}).forEach(function(name) {
                    mergedHeaders[name] = headers[name];
                });
                const options = {};
                if (Object.keys(mergedHeaders).length > 0) options.headers = mergedHeaders;
                if (signal) options.signal = signal;
                if (method) options.method = method;
                if (useCredentials) options.credentials = "include";
                return options;
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
                    callback(id.value, payloadStart, Math.min(payloadEnd, data.length), size.value, offset);
                    if (
                        depth < 8 &&
                        [0x1a45dfa3, 0x18538067, 0x1549a966, 0x1654ae6b, 0xae, 0xe1, 0x1c53bb6b, 0xbb, 0xb7].indexOf(id.value) !== -1
                    ) {
                        parseChildren(data, payloadStart, payloadEnd, callback, depth + 1);
                    }
                    offset = payloadEnd;
                }
            };

            const parseTrackEntry = function(data, start, end) {
                const track = { number: "", type: 0, codec: "", language: "", name: "", channels: "", isDefault: true, header: "" };
                parseChildren(data, start, end, function(id, payloadStart, payloadEnd) {
                    const bytes = data.slice(payloadStart, payloadEnd);
                    if (id === 0xd7) track.number = String(readUInt(bytes));
                    if (id === 0x83) track.type = readUInt(bytes);
                    if (id === 0x86) track.codec = decodeText(bytes);
                    if (id === 0x22b59c) track.language = decodeText(bytes);
                    if (id === 0x22b59d) track.language = decodeText(bytes);
                    if (id === 0x536e) track.name = decodeText(bytes);
                    if (id === 0x63a2) track.header = decodeText(bytes);
                    if (id === 0x88) track.isDefault = readUInt(bytes) !== 0;
                    if (id === 0x9f) track.channels = String(readUInt(bytes));
                    if (id === 0xb5) track.sampleRate = String(Math.round(readFloat(bytes) || 0));
                }, 0);
                return track;
            };

            const parseCues = function(data, timestampScale) {
                const cues = [];
                let current = null;
                let currentTrack = null;
                parseChildren(data, 0, data.length, function(id, payloadStart, payloadEnd) {
                    const bytes = data.slice(payloadStart, payloadEnd);
                    if (id === 0xbb) {
                        if (current) cues.push(current);
                        current = { timeMs: null, positions: [] };
                        currentTrack = null;
                    } else if (id === 0xb3 && current) {
                        current.timeMs = readUInt(bytes) * timestampScale / 1000000;
                    } else if (id === 0xb7 && current) {
                        currentTrack = {};
                        current.positions.push(currentTrack);
                    } else if (id === 0xf7 && currentTrack) {
                        currentTrack.track = readUInt(bytes);
                    } else if (id === 0xf1 && currentTrack) {
                        currentTrack.cluster = readUInt(bytes);
                    } else if (id === 0xf0 && currentTrack) {
                        currentTrack.relative = readUInt(bytes);
                    }
                }, 0);
                if (current) cues.push(current);
                return cues
                    .flatMap(function(cue) {
                        return cue.positions.map(function(position) {
                            return {
                                timeMs: cue.timeMs,
                                track: position.track,
                                cluster: position.cluster,
                                relative: position.relative || 0
                            };
                        });
                    })
                    .filter(function(cue) {
                        return Number.isFinite(cue.timeMs) &&
                            Number.isFinite(cue.track) &&
                            Number.isFinite(cue.cluster);
                    })
                    .sort(function(left, right) { return left.timeMs - right.timeMs; });
            };

            const loadMkvFileSize = function() {
                if (Number.isFinite(video.__composeMediaPlayerMkvFileSize)) {
                    return Promise.resolve(video.__composeMediaPlayerMkvFileSize);
                }
                return fetch(sourceUri, fetchOptions(null, abort.signal, "HEAD")).then(function(response) {
                    const fileSize = Number(response.headers.get("Content-Length") || "0");
                    video.__composeMediaPlayerMkvFileSize = Number.isFinite(fileSize) && fileSize > 0 ? fileSize : null;
                    return video.__composeMediaPlayerMkvFileSize;
                }).catch(function() {
                    video.__composeMediaPlayerMkvFileSize = null;
                    return null;
                });
            };

            const findCuesStart = function(bytes) {
                for (let index = 0; index < bytes.length - 4; index += 1) {
                    if (bytes[index] === 0x1c &&
                        bytes[index + 1] === 0x53 &&
                        bytes[index + 2] === 0xbb &&
                        bytes[index + 3] === 0x6b) {
                        return index;
                    }
                }
                return -1;
            };

            const probeCues = function(fileSize, rangeSize) {
                if (!Number.isFinite(fileSize) || fileSize <= 0) return Promise.resolve([]);
                const size = Math.min(fileSize, rangeSize || 2097152);
                const start = Math.max(0, fileSize - size);
                return fetch(
                    sourceUri,
                    fetchOptions({ Range: "bytes=" + start + "-" + (fileSize - 1) }, abort.signal)
                ).then(function(response) {
                    return response.arrayBuffer();
                }).then(function(buffer) {
                    const bytes = new Uint8Array(buffer);
                    const cuesStart = findCuesStart(bytes);
                    if (cuesStart === -1 && size < Math.min(fileSize, 8388608)) {
                        return probeCues(fileSize, size * 4);
                    }
                    if (cuesStart === -1) return [];
                    return parseCues(
                        bytes.slice(cuesStart),
                        video.__composeMediaPlayerMkvTimestampScale || 1000000
                    );
                }).then(function(cues) {
                    video.__composeMediaPlayerMkvCues = cues;
                    video.__composeMediaPlayerMkvCueClusters = Array.from(
                        new Set(cues.map(function(cue) { return cue.cluster; }))
                    ).sort(function(left, right) { return left - right; });
                    return cues;
                }).catch(function(error) {
                    if (error && error.name !== "AbortError") warn("MKV cue probe failed", error);
                    video.__composeMediaPlayerMkvCues = [];
                    video.__composeMediaPlayerMkvCueClusters = [];
                    return [];
                });
            };

            const probeContainerTracks = function() {
                return fetch(
                    sourceUri,
                    fetchOptions({ Range: "${MKV_TRACK_PROBE_RANGE_HEADER}" }, abort.signal)
                ).then(function(response) {
                    return response.arrayBuffer();
                }).then(function(buffer) {
                    const bytes = new Uint8Array(buffer);
                    const audio = [];
                    const subtitles = [];
                    let segmentDataStart = 0;
                    let timestampScale = 1000000;
                    parseChildren(bytes, 0, bytes.length, function(id, payloadStart, payloadEnd) {
                        if (id === 0x18538067) segmentDataStart = payloadStart;
                        if (id === 0x2ad7b1) timestampScale = readUInt(bytes.slice(payloadStart, payloadEnd));
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
                                header: track.header || "",
                                isDefault: track.isDefault,
                                _blobUrl: "",
                                _isPartial: false
                            });
                        }
                    }, 0);

                    video.__composeMediaPlayerMkvHeaderBytes = bytes;
                    video.__composeMediaPlayerMkvSegmentDataStart = segmentDataStart;
                    video.__composeMediaPlayerMkvTimestampScale = timestampScale;
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

                    video.__composeMediaPlayerMkvCuesPromise =
                        loadMkvFileSize().then(function(fileSize) {
                            return probeCues(fileSize, 2097152);
                        });
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
                if (error && error.name !== "AbortError") warn("MKV track probe failed", error);
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
    if (!ensureMatroskaSubtitlesModuleLoaded()) return

    startMkvSubtitleExtraction(
        video = this,
        selectedId = track.id,
        onTracksChanged = {
            scope.launch {
                playerState.syncWebMediaTracks(this@extractMkvSubtitleTrack)
            }
        },
        onTracksChangedError = { message ->
            webVideoLogger.e { "MKV subtitle extraction sync callback failed: $message" }
        },
        onWarning = { message ->
            webVideoLogger.w { message }
        },
    )
}

@Suppress("UNUSED_PARAMETER")
private fun startMkvSubtitleExtraction(
    video: HTMLVideoElement,
    selectedId: String,
    onTracksChanged: () -> Unit,
    onTracksChangedError: (String) -> Unit,
    onWarning: (String) -> Unit,
): Unit =
    js(
        """
        {
            const describeError = function(error) {
                return error && error.stack ? error.stack : (error && error.message ? error.message : String(error));
            };
            const warn = function(context, error) {
                try { onWarning(context + ": " + describeError(error)); } catch (_) {}
            };
            const notify = function() {
                try {
                    onTracksChanged();
                } catch (error) {
                    try { onTracksChangedError(String(describeError(error))); } catch (_) {}
                }
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
            if (!target || (target._blobUrl && !target._isPartial)) return;

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
            const publish = function(isPartial) {
                const content = buildAss(target, lines);
                const subtitleUrl = "data:text/plain;charset=utf-8," + encodeURIComponent(content);
                target._blobUrl = subtitleUrl;
                target._isPartial = Boolean(isPartial);
                updateSubtitleRows();
                notify();
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
                for (let index = 1; index < length; index += 1) {
                    value = value * 256 + data[offset + index];
                }
                return { length, value };
            };
            const readUInt = function(bytes) {
                let value = 0;
                for (let index = 0; index < bytes.length; index += 1) value = value * 256 + bytes[index];
                return value;
            };
            const readInt16 = function(data, offset) {
                return new DataView(data.buffer, data.byteOffset + offset, 2).getInt16(0, false);
            };
            const decodeText = function(bytes) {
                return new TextDecoder("utf-8").decode(bytes).replace(/\u0000+$/g, "");
            };
            const parseElements = function(data, start, end, callback) {
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
                    callback(id.value, payloadStart, Math.min(payloadEnd, data.length), size.value, offset);
                    offset = payloadEnd;
                }
            };
            const parseAssPayload = function(payload) {
                const parts = payload.split(",");
                if (parts.length < 9) {
                    return {
                        layer: "0",
                        style: "Default",
                        name: "",
                        marginL: "0",
                        marginR: "0",
                        marginV: "0",
                        effect: "",
                        text: payload
                    };
                }
                return {
                    layer: parts[1] || "0",
                    style: parts[2] || "Default",
                    name: parts[3] || "",
                    marginL: parts[4] || "0",
                    marginR: parts[5] || "0",
                    marginV: parts[6] || "0",
                    effect: parts[7] || "",
                    text: parts.slice(8).join(",")
                };
            };
            const parseBlock = function(data, payloadStart, payloadEnd, clusterTimeMs, blockDurationMs) {
                const track = readVint(data, payloadStart, false);
                if (!track || String(track.value) !== targetNumber) return;
                const blockTime = readInt16(data, payloadStart + track.length);
                const flags = data[payloadStart + track.length + 2] || 0;
                const lacing = (flags & 0x06) >> 1;
                if (lacing !== 0) return;
                const textStart = payloadStart + track.length + 3;
                const payload = decodeText(data.slice(textStart, payloadEnd));
                const startMs = clusterTimeMs + blockTime;
                const endMs = startMs + (Number.isFinite(blockDurationMs) && blockDurationMs > 0 ? blockDurationMs : 5000);
                if (target.type === "ass" || target.type === "ssa") {
                    const ass = parseAssPayload(payload);
                    addLine("Dialogue: " + [
                        ass.layer,
                        formatTime(startMs),
                        formatTime(endMs),
                        ass.style,
                        ass.name,
                        ass.marginL,
                        ass.marginR,
                        ass.marginV,
                        ass.effect,
                        ass.text
                    ].join(","));
                } else {
                    addLine(
                        "Dialogue: 0," + formatTime(startMs) + "," + formatTime(endMs) + ",Default,,0,0,0,," + payload
                    );
                }
            };
            const parseFastCluster = function(data) {
                let clusterTimeMs = 0;
                parseElements(data, 0, data.length, function(id, payloadStart, payloadEnd) {
                    if (id !== 0x1f43b675) return;
                    parseElements(data, payloadStart, payloadEnd, function(childId, childStart, childEnd) {
                        if (childId === 0xe7) {
                            clusterTimeMs =
                                readUInt(data.slice(childStart, childEnd)) *
                                (video.__composeMediaPlayerMkvTimestampScale || 1000000) /
                                1000000;
                        } else if (childId === 0xa3) {
                            parseBlock(data, childStart, childEnd, clusterTimeMs, null);
                        } else if (childId === 0xa0) {
                            let block = null;
                            let durationMs = null;
                            parseElements(data, childStart, childEnd, function(groupId, groupStart, groupEnd) {
                                if (groupId === 0x9b) {
                                    durationMs =
                                        readUInt(data.slice(groupStart, groupEnd)) *
                                        (video.__composeMediaPlayerMkvTimestampScale || 1000000) /
                                        1000000;
                                } else if (groupId === 0xa1) {
                                    block = { start: groupStart, end: groupEnd };
                                }
                            });
                            if (block) parseBlock(data, block.start, block.end, clusterTimeMs, durationMs);
                        }
                    });
                });
            };
            const chooseFastCues = function(cues, currentMs) {
                const trackCues = cues
                    .filter(function(cue) { return String(cue.track) === targetNumber; })
                    .sort(function(left, right) { return left.timeMs - right.timeMs; });
                const selected = [];
                let previous = null;
                for (let index = 0; index < trackCues.length; index += 1) {
                    const cue = trackCues[index];
                    if (cue.timeMs <= currentMs + 250) {
                        previous = cue;
                        continue;
                    }
                    break;
                }
                if (previous && currentMs - previous.timeMs <= 5000) selected.push(previous);
                for (let index = 0; index < trackCues.length; index += 1) {
                    const cue = trackCues[index];
                    if (cue.timeMs < currentMs - 250) continue;
                    if (cue.timeMs > currentMs + 15000) break;
                    selected.push(cue);
                    if (selected.length >= 4) break;
                }
                if (selected.length === 0) {
                    const next = trackCues.find(function(cue) { return cue.timeMs >= currentMs - 250; });
                    if (next) selected.push(next);
                }
                const seenClusters = new Set();
                return selected.filter(function(cue) {
                    if (seenClusters.has(cue.cluster)) return false;
                    seenClusters.add(cue.cluster);
                    return true;
                });
            };
            const startFastCueExtraction = function() {
                const cuesPromise =
                    video.__composeMediaPlayerMkvCuesPromise ||
                    Promise.resolve(video.__composeMediaPlayerMkvCues || []);
                cuesPromise.then(function(cues) {
                    if (extractAbort.signal.aborted) return;
                    const fastCues = chooseFastCues(cues || [], (video.currentTime || 0) * 1000);
                    if (fastCues.length === 0) return;
                    const clusters = video.__composeMediaPlayerMkvCueClusters || [];
                    const fileSize = video.__composeMediaPlayerMkvFileSize;
                    const segmentDataStart = video.__composeMediaPlayerMkvSegmentDataStart || 0;
                    if (!Number.isFinite(segmentDataStart)) return;
                    let chain = Promise.resolve();
                    fastCues.forEach(function(cue) {
                        chain = chain.then(function() {
                            if (extractAbort.signal.aborted) return;
                            const nextCluster = clusters.find(function(cluster) { return cluster > cue.cluster; });
                            const rangeStart = segmentDataStart + cue.cluster;
                            const rangeEnd =
                                Number.isFinite(nextCluster)
                                    ? segmentDataStart + nextCluster - 1
                                    : Math.min(
                                        Number.isFinite(fileSize) ? fileSize - 1 : rangeStart + 4194303,
                                        rangeStart + 4194303
                                    );
                            return fetch(
                                sourceUri,
                                fetchOptions({ Range: "bytes=" + rangeStart + "-" + rangeEnd }, extractAbort.signal)
                            ).then(function(response) {
                                return response.arrayBuffer();
                            }).then(function(buffer) {
                                if (extractAbort.signal.aborted) return;
                                target.header = target.header || "";
                                parseFastCluster(new Uint8Array(buffer));
                            });
                        });
                    });
                    return chain.then(function() {
                        if (!extractAbort.signal.aborted && lines.length > 0) publish(true);
                    });
                }).catch(function(error) {
                    if (error && error.name !== "AbortError") {
                        warn("MKV fast subtitle extraction failed", error);
                    }
                });
            };

            const Parser = globalThis.MatroskaSubtitles.SubtitleParser;
            const parser = new Parser();
            const lines = [];
            const lineKeys = new Set();
            let publishedFirstLine = false;

            const addLine = function(line) {
                if (lineKeys.has(line)) return;
                lineKeys.add(line);
                lines.push(line);
            };

            startFastCueExtraction();

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
                    addLine("Dialogue: " + [
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
                    addLine(
                        "Dialogue: 0," + formatTime(subtitle.time) + "," + formatTime(end) + ",Default,,0,0,0,," + (subtitle.text || "")
                    );
                }
                if (!publishedFirstLine || lines.length % 50 === 0) {
                    publishedFirstLine = true;
                    publish(true);
                }
            });
            parser.on("end", function() {
                if (lines.length > 0) publish(false);
                if (video.__composeMediaPlayerMkvExtractAbort === extractAbort) {
                    video.__composeMediaPlayerMkvExtractAbort = null;
                }
                video.__composeMediaPlayerMkvExtractingSubtitleTrackId = "";
            });
            parser.on("error", function(error) {
                warn("MKV selected subtitle parser failed", error);
            });

            fetch(
                sourceUri,
                fetchOptions({ Range: "bytes=0-" }, extractAbort.signal)
            ).then(function(response) {
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
                    warn("MKV selected subtitle fetch failed", error);
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
