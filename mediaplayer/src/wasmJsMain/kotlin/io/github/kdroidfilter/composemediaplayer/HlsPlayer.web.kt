@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsModule
import kotlin.js.JsName
import kotlin.js.js

@JsModule("hls.js")
@JsName("default")
private external val bundledHls: JsAny

internal const val HLS_AUDIO_TRACK_ID_PREFIX = "hls:audio:"
private const val HLS_SUBTITLE_TRACK_ID_PREFIX = "hls:subtitle:"

internal fun String.isHlsSource(): Boolean = substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

internal fun ensureHlsModuleLoaded(): Boolean = isBundledHlsModuleAvailable(bundledHls)

internal fun isWebHlsPlaybackSupported(): Boolean =
    combineWebHlsSupport(
        nativeHlsSupported = canPlayNativeHls(),
        bundledHlsSupported = ensureHlsModuleLoaded() && canUseBundledHlsModule(bundledHls),
    )

internal fun combineWebHlsSupport(
    nativeHlsSupported: Boolean,
    bundledHlsSupported: Boolean,
): Boolean = nativeHlsSupported || bundledHlsSupported

private fun canPlayNativeHls(): Boolean =
    js(
        """
        (function() {
            if (typeof document === "undefined") return false;
            const video = document.createElement("video");
            if (!video || typeof video.canPlayType !== "function") return false;
            const mimeTypes = [
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl"
            ];
            return mimeTypes.some(function(mimeType) {
                const result = video.canPlayType(mimeType);
                return result === "probably" || result === "maybe";
            });
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun canUseBundledHlsModule(module: JsAny): Boolean =
    js(
        """
        (function() {
            const Hls = module && (module.default || module.Hls || module);
            if (!Hls || typeof Hls.isSupported !== "function") return false;
            try { return !!Hls.isSupported(); } catch (_) { return false; }
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun isBundledHlsModuleAvailable(module: JsAny): Boolean =
    js(
        """
        (function() {
            const Hls = module && (module.default || module.Hls || module);
            return typeof Hls === "function";
        })()
        """,
    )

internal suspend fun HTMLVideoElement.configureHlsSource(
    playerState: DefaultVideoPlayerState,
    sourceUri: String,
    requestHeadersJson: String,
    useCredentials: Boolean,
    scope: CoroutineScope,
    mediaSessionId: Long,
): Boolean {
    if (!sourceUri.isHlsSource()) return false
    if (!ensureHlsModuleLoaded()) return false

    setupHlsSource(
        hlsModule = bundledHls,
        video = this,
        sourceUri = sourceUri,
        requestHeadersJson = requestHeadersJson,
        useCredentials = useCredentials,
        onTracksChanged = {
            scope.launch {
                if (!playerState.isCurrentMediaSession(mediaSessionId)) return@launch
                playerState.syncWebMediaTracks(this@configureHlsSource)
                playerState.syncHlsQualities(this@configureHlsSource)
                playerState.refreshWebVideoColorInfo(this@configureHlsSource)
                this@configureHlsSource.applySelectedAudioTrack(playerState.currentAudioTrack)
                this@configureHlsSource.applySelectedSubtitleTrack(
                    if (playerState.subtitlesEnabled) playerState.currentSubtitleTrack else null,
                )
                this@configureHlsSource.applySelectedHlsQuality(
                    if (playerState.hlsQualityMode == HlsQualityMode.AUTO) {
                        null
                    } else {
                        playerState.currentHlsQuality?.id
                    },
                )
            }
        },
        onError = { message, type, details, fatal ->
            scope.launch {
                if (!playerState.isCurrentMediaSession(mediaSessionId)) return@launch
                playerState.setError(
                    VideoPlayerError.HlsError(
                        message = message,
                        type = type,
                        details = details,
                        fatal = fatal,
                    ),
                )
            }
        },
        onTracksChangedError = { message ->
            webVideoLogger.e { "HLS track sync callback failed: $message" }
        },
    )
    playerState.applyHlsQualityCallback = { variantId ->
        applySelectedHlsQuality(variantId)
        playerState.syncHlsQualities(this)
    }
    return true
}

@Suppress("LongMethod", "UNUSED_PARAMETER")
private fun setupHlsSource(
    hlsModule: JsAny,
    video: HTMLVideoElement,
    sourceUri: String,
    requestHeadersJson: String,
    useCredentials: Boolean,
    onTracksChanged: () -> Unit,
    onError: (String, String?, String?, Boolean) -> Unit,
    onTracksChangedError: (String) -> Unit,
): Unit =
    js(
        """
        {
            const Hls = hlsModule && (hlsModule.default || hlsModule.Hls || hlsModule);
            const requestHeaders = (function() {
                try { return JSON.parse(requestHeadersJson || "{}") || {}; } catch (_) { return {}; }
            })();
            const hasRequestHeaders = Object.keys(requestHeaders).length > 0;
            const applyRequestHeaders = function(xhr) {
                if (useCredentials) xhr.withCredentials = true;
                if (!hasRequestHeaders) return;
                Object.keys(requestHeaders).forEach(function(name) {
                    try { xhr.setRequestHeader(name, requestHeaders[name]); } catch (_) {}
                });
            };
            const fetchOptions = function() {
                const options = {};
                if (hasRequestHeaders) options.headers = requestHeaders;
                if (useCredentials) options.credentials = "include";
                return options;
            };
            video.__composeMediaPlayerHlsSourceUri = sourceUri;
            video.__composeMediaPlayerHlsChapterRows = "";

            const notifyTracksChanged = function() {
                if (video.__composeMediaPlayerHlsSourceUri !== sourceUri) return;
                try {
                    onTracksChanged();
                } catch (error) {
                    const message = error && error.stack ? error.stack : (error && error.message ? error.message : String(error));
                    try { onTracksChangedError(String(message)); } catch (_) {}
                }
            };
            const parseSessionAttributes = function(value) {
                const attrs = {};
                const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
                let match;
                while ((match = re.exec(value)) !== null) {
                    const raw = match[2] || "";
                    attrs[match[1]] = raw.charAt(0) === "\"" ? raw.slice(1, -1) : raw;
                }
                return attrs;
            };
            const hlsVariableDefinitions = function(lines) {
                const definitions = {};
                for (let i = 0; i < lines.length; i += 1) {
                    const line = lines[i].trim();
                    if (line.indexOf("#EXT-X-DEFINE:") !== 0) continue;
                    const attrs = parseSessionAttributes(line.substring("#EXT-X-DEFINE:".length));
                    if (attrs.NAME && Object.prototype.hasOwnProperty.call(attrs, "VALUE")) {
                        definitions[attrs.NAME] = attrs.VALUE;
                    }
                }
                return definitions;
            };
            const expandHlsVariables = function(value, definitions) {
                let result = String(value || "");
                for (let pass = 0; pass < 8; pass += 1) {
                    const before = result;
                    Object.keys(definitions).forEach(function(name) {
                        const marker = "{" + String.fromCharCode(36) + name + "}";
                        result = result.split(marker).join(definitions[name]);
                    });
                    if (result === before) break;
                }
                return result;
            };
            const chapterJsonUri = function(playlistText, baseUrl) {
                const lines = String(playlistText || "").split(/\r?\n/);
                const definitions = hlsVariableDefinitions(lines);
                for (let i = 0; i < lines.length; i += 1) {
                    const line = lines[i].trim();
                    if (line.indexOf("#EXT-X-SESSION-DATA:") !== 0) continue;
                    const attrs = parseSessionAttributes(line.substring("#EXT-X-SESSION-DATA:".length));
                    if (attrs["DATA-ID"] !== "com.apple.hls.chapters" || !attrs.URI) continue;
                    try {
                        return new URL(expandHlsVariables(attrs.URI, definitions), baseUrl).toString();
                    } catch (_) {
                        return "";
                    }
                }
                return "";
            };
            const firstVariantUri = function(playlistText, baseUrl) {
                const lines = String(playlistText || "").split(/\r?\n/);
                const definitions = hlsVariableDefinitions(lines);
                for (let i = 0; i < lines.length; i += 1) {
                    if (lines[i].trim().indexOf("#EXT-X-STREAM-INF:") !== 0) continue;
                    for (let next = i + 1; next < lines.length; next += 1) {
                        const candidate = lines[next].trim();
                        if (!candidate || candidate.charAt(0) === "#") continue;
                        try {
                            return new URL(expandHlsVariables(candidate, definitions), baseUrl).toString();
                        } catch (_) {
                            return "";
                        }
                    }
                }
                return "";
            };
            const isStableChapterTimeline = function(playlistText) {
                const text = String(playlistText || "");
                return /#EXT-X-PLAYLIST-TYPE\s*:\s*(VOD|EVENT)(?:\s|$)/i.test(text) ||
                    /#EXT-X-ENDLIST(?:\s|$)/i.test(text);
            };
            const chooseChapterTitle = function(titles) {
                if (!Array.isArray(titles)) return { title: "", language: "" };
                const usable = titles.filter(function(item) {
                    return item && typeof item.title === "string" && item.title.trim().length > 0;
                });
                if (!usable.length) return { title: "", language: "" };
                const preferred =
                    typeof navigator !== "undefined" && Array.isArray(navigator.languages)
                        ? navigator.languages.map(function(value) { return String(value || "").toLowerCase(); })
                        : [];
                for (let i = 0; i < preferred.length; i += 1) {
                    const exact = usable.find(function(item) {
                        return String(item.language || "").toLowerCase() === preferred[i];
                    });
                    if (exact) return { title: exact.title.trim(), language: String(exact.language || "") };
                }
                for (let i = 0; i < preferred.length; i += 1) {
                    const primary = preferred[i].split("-")[0];
                    const matched = usable.find(function(item) {
                        return primary && String(item.language || "").toLowerCase().split("-")[0] === primary;
                    });
                    if (matched) return { title: matched.title.trim(), language: String(matched.language || "") };
                }
                const neutral = usable.find(function(item) {
                    const language = String(item.language || "").toLowerCase();
                    return !language || language === "und";
                });
                const selected = neutral || usable[0];
                return { title: selected.title.trim(), language: String(selected.language || "") };
            };
            const serializeChapterJson = function(payload) {
                if (!Array.isArray(payload)) return "";
                const rows = [];
                payload.forEach(function(entry) {
                    if (!entry || typeof entry !== "object") return;
                    const startSeconds = Number(entry["start-time"]);
                    if (!Number.isFinite(startSeconds) || startSeconds < 0) return;
                    const startMs = Math.round(startSeconds * 1000);
                    if (!Number.isSafeInteger(startMs)) return;
                    const durationSeconds = Number(entry.duration);
                    let endMs = "";
                    if (Number.isFinite(durationSeconds) && durationSeconds > 0) {
                        const candidate = Math.round((startSeconds + durationSeconds) * 1000);
                        if (Number.isSafeInteger(candidate) && candidate > startMs) endMs = String(candidate);
                    }
                    const label = chooseChapterTitle(entry.titles);
                    rows.push([
                        String(startMs),
                        endMs,
                        label.title,
                        label.language,
                        "0"
                    ].map(encodeURIComponent).join("|"));
                });
                return rows.join("\n");
            };
            const fetchText = function(uri) {
                return fetch(uri, fetchOptions())
                    .then(function(response) { return response.ok ? response.text() : ""; });
            };
            const loadHlsChapterRows = function() {
                fetchText(sourceUri)
                    .then(function(masterText) {
                        if (video.__composeMediaPlayerHlsSourceUri !== sourceUri) return null;
                        const jsonUri = chapterJsonUri(masterText, sourceUri);
                        if (!jsonUri) return null;
                        const mediaUri = firstVariantUri(masterText, sourceUri);
                        const stableTimeline =
                            mediaUri
                                ? fetchText(mediaUri).then(isStableChapterTimeline)
                                : Promise.resolve(isStableChapterTimeline(masterText));
                        return stableTimeline.then(function(isStable) {
                            if (!isStable || video.__composeMediaPlayerHlsSourceUri !== sourceUri) return null;
                            return fetchText(jsonUri).then(function(jsonText) {
                                if (!jsonText || jsonText.length > 4194304) return null;
                                try { return serializeChapterJson(JSON.parse(jsonText)); } catch (_) { return null; }
                            });
                        });
                    })
                    .then(function(rows) {
                        if (video.__composeMediaPlayerHlsSourceUri !== sourceUri) return;
                        video.__composeMediaPlayerHlsChapterRows = rows || "";
                        notifyTracksChanged();
                    })
                    .catch(function() {
                        if (video.__composeMediaPlayerHlsSourceUri !== sourceUri) return;
                        video.__composeMediaPlayerHlsChapterRows = "";
                        notifyTracksChanged();
                    });
            };
            if (!Hls || typeof Hls.isSupported !== "function" || !Hls.isSupported()) {
                video.src = sourceUri;
                video.load();
                notifyTracksChanged();
            } else {
                if (video.__composeMediaPlayerHls) {
                    try { video.__composeMediaPlayerHls.destroy(); } catch (_) {}
                }

                const hls = new Hls({
                    renderTextTracksNatively: false,
                    xhrSetup: function(xhr) {
                        applyRequestHeaders(xhr);
                    },
                    fetchSetup: function(context, initParams) {
                        const init = Object.assign({}, initParams || {});
                        const headers = new Headers(init.headers || {});
                        Object.keys(requestHeaders).forEach(function(name) {
                            try { headers.set(name, requestHeaders[name]); } catch (_) {}
                        });
                        init.headers = headers;
                        if (useCredentials) init.credentials = "include";
                        return new Request(context.url, init);
                    }
                });
                video.__composeMediaPlayerHls = hls;
                video.__composeMediaPlayerHlsSubtitleRows = "";

                const sync = notifyTracksChanged;
                const syncAfterDecodedFrame = function() {
                    sync();
                    if (typeof video.requestVideoFrameCallback === "function") {
                        video.requestVideoFrameCallback(function() { sync(); });
                    }
                };

                const parseAttributes = function(value) {
                    const attrs = {};
                    const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
                    let match;
                    while ((match = re.exec(value)) !== null) {
                        const raw = match[2] || "";
                        attrs[match[1]] = raw.charAt(0) === "\"" ? raw.slice(1, -1) : raw;
                    }
                    return attrs;
                };

                const subtitleFormat = function(uri, explicitFormat) {
                    const raw = (explicitFormat || uri.split("?")[0].split("#")[0].split(".").pop() || "").toUpperCase();
                    if (raw === "SSA") return "SSA";
                    if (raw === "ASS") return "ASS";
                    if (raw === "SRT") return "SRT";
                    if (raw === "VTT" || raw === "WEBVTT") return "WEBVTT";
                    return "AUTO";
                };

                const parseSubtitleRows = function(playlistText, baseUrl) {
                    const rows = [];
                    playlistText.split(/\r?\n/).forEach(function(line) {
                        if (line.indexOf("#EXT-X-MEDIA:") !== 0) return;
                        const attrs = parseAttributes(line.substring("#EXT-X-MEDIA:".length));
                        if (attrs.TYPE !== "SUBTITLES" || !attrs.URI) return;

                        const uri = new URL(attrs.URI, baseUrl).toString();
                        const label = attrs.NAME || attrs.LANGUAGE || ("Subtitle " + (rows.length + 1));
                        const language = attrs.LANGUAGE || "";
                        const stableKey = attrs.URI || attrs.NAME || attrs.LANGUAGE || String(rows.length);
                        const id = "${HLS_SUBTITLE_TRACK_ID_PREFIX}" + encodeURIComponent(stableKey);
                        rows.push([
                            id,
                            label,
                            language,
                            "subtitles",
                            "disabled",
                            String(rows.length),
                            uri,
                            subtitleFormat(uri, attrs.FORMAT)
                        ].map(encodeURIComponent).join("|"));
                    });
                    return rows.join("\n");
                };

                hls.on(Hls.Events.MEDIA_ATTACHED, function() {
                    hls.loadSource(sourceUri);
                });
                hls.on(Hls.Events.MANIFEST_PARSED, sync);
                hls.on(Hls.Events.LEVEL_SWITCHED, syncAfterDecodedFrame);
                hls.on(Hls.Events.LEVEL_UPDATED, sync);
                hls.on(Hls.Events.FRAG_CHANGED, syncAfterDecodedFrame);
                hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, sync);
                hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, sync);
                hls.on(Hls.Events.ERROR, function(_, data) {
                    if (data && data.fatal) {
                        try {
                            const type = data.type ? String(data.type) : null;
                            const details = data.details ? String(data.details) : null;
                            const message = details || type || "HLS playback error";
                            onError(message, type, details, Boolean(data.fatal));
                        } catch (_) {}
                    }
                });
                hls.attachMedia(video);

                fetch(sourceUri, fetchOptions())
                    .then(function(response) { return response.ok ? response.text() : ""; })
                    .then(function(text) {
                        if (video.__composeMediaPlayerHlsSourceUri !== sourceUri) return;
                        video.__composeMediaPlayerHlsSubtitleRows = parseSubtitleRows(text, sourceUri);
                        sync();
                    })
                    .catch(function() {
                        if (video.__composeMediaPlayerHlsSourceUri !== sourceUri) return;
                        video.__composeMediaPlayerHlsSubtitleRows = "";
                        sync();
                    });
            }
            loadHlsChapterRows();
        }
        """,
    )

internal fun HTMLVideoElement.destroyHlsController() {
    destroyHlsController(this)
}

@Suppress("UNUSED_PARAMETER")
private fun destroyHlsController(video: HTMLVideoElement): Unit =
    js(
        """
        {
            if (video.__composeMediaPlayerHls) {
                try { video.__composeMediaPlayerHls.destroy(); } catch (_) {}
                video.__composeMediaPlayerHls = null;
            }
            video.__composeMediaPlayerHlsSubtitleRows = "";
            video.__composeMediaPlayerHlsChapterRows = "";
            video.__composeMediaPlayerHlsSourceUri = "";
        }
        """,
    )

internal data class HlsQualitySnapshot(
    val variants: List<HlsQualityVariant>,
    val selectedId: String?,
    val autoMode: Boolean,
)

internal fun DefaultVideoPlayerState.syncHlsQualities(video: HTMLVideoElement) {
    val snapshot = parseHlsQualityRows(readHlsQualityRows(video))
    replaceHlsQualities(
        variants = snapshot.variants,
        selectedId = snapshot.selectedId,
        autoMode = snapshot.autoMode,
    )
}

internal fun HTMLVideoElement.applySelectedHlsQuality(variantId: String?) {
    selectHlsQualityById(video = this, selectedId = variantId)
}

internal fun parseHlsQualityRows(rows: String): HlsQualitySnapshot {
    var selectedId: String? = null
    var autoMode = true
    val variants =
        rows
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val columns = row.split('|').map(::decodeUriComponent)
                if (columns.size < 8) return@mapNotNull null

                val id = columns[0]
                val label = columns[1]
                if (id.isBlank() || label.isBlank()) return@mapNotNull null
                if (columns[6] == "1") selectedId = id
                autoMode = columns[7] == "1"

                HlsQualityVariant(
                    id = id,
                    label = label,
                    width = columns[2].toPositiveIntOrNull(),
                    height = columns[3].toPositiveIntOrNull(),
                    bitrate = columns[4].toPositiveIntOrNull(),
                    codecs = columns[5].ifBlank { null },
                )
            }.toList()

    return HlsQualitySnapshot(
        variants = variants,
        selectedId = selectedId,
        autoMode = autoMode,
    )
}

private fun String.toPositiveIntOrNull(): Int? = toIntOrNull()?.takeIf { it > 0 }

@Suppress("UNUSED_PARAMETER")
private fun readHlsQualityRows(video: HTMLVideoElement): String =
    js(
        """
        (function() {
            const hls = video.__composeMediaPlayerHls;
            const levels = hls && hls.levels;
            if (!levels || typeof levels.length !== "number") return "";

            const selectedIndex = typeof hls.currentLevel === "number" ? hls.currentLevel : -1;
            const autoMode = hls.autoLevelEnabled || selectedIndex < 0;
            const rows = [];
            for (let i = 0; i < levels.length; i += 1) {
                const level = levels[i] || {};
                const width = Number(level.width || 0);
                const height = Number(level.height || 0);
                const bitrate = Number(level.bitrate || level.maxBitrate || 0);
                const codecs = level.codecs || [level.videoCodec || "", level.audioCodec || ""].filter(Boolean).join(",");
                const stableKey = [String(width), String(height), String(bitrate), codecs, String(i)].join("|");
                const id = "hls:quality:" + encodeURIComponent(stableKey);
                const label = height > 0 ? String(height) + "p" : (bitrate > 0 ? Math.round(bitrate / 1000) + " kbps" : "Level " + (i + 1));
                rows.push([
                    id,
                    label,
                    width > 0 ? String(width) : "",
                    height > 0 ? String(height) : "",
                    bitrate > 0 ? String(bitrate) : "",
                    codecs || "",
                    selectedIndex === i ? "1" : "0",
                    autoMode ? "1" : "0"
                ].map(encodeURIComponent).join("|"));
            }
            return rows.join("\n");
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun selectHlsQualityById(
    video: HTMLVideoElement,
    selectedId: String?,
): Unit =
    js(
        """
        {
            const hls = video.__composeMediaPlayerHls;
            if (!hls || !hls.levels) return;
            if (!selectedId) {
                hls.currentLevel = -1;
                return;
            }

            for (let i = 0; i < hls.levels.length; i += 1) {
                const level = hls.levels[i] || {};
                const width = Number(level.width || 0);
                const height = Number(level.height || 0);
                const bitrate = Number(level.bitrate || level.maxBitrate || 0);
                const codecs = level.codecs || [level.videoCodec || "", level.audioCodec || ""].filter(Boolean).join(",");
                const stableKey = [String(width), String(height), String(bitrate), codecs, String(i)].join("|");
                const id = "hls:quality:" + encodeURIComponent(stableKey);
                if (id === selectedId) {
                    hls.currentLevel = i;
                    return;
                }
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun decodeUriComponent(value: String): String = js("decodeURIComponent(value)")
