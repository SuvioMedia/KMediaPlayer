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

private const val HLS_SCRIPT_ID = "compose-media-player-hls-js"
private const val HLS_SCRIPT_URL = "https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js"

internal const val HLS_AUDIO_TRACK_ID_PREFIX = "hls:audio:"
private const val HLS_SUBTITLE_TRACK_ID_PREFIX = "hls:subtitle:"

private var hlsScriptLoad: CompletableDeferred<Boolean>? = null

internal fun String.isHlsSource(): Boolean =
    substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

internal suspend fun ensureHlsScriptLoaded(): Boolean {
    if (isHlsLoaded()) return true

    hlsScriptLoad?.let { return it.await() }

    val deferred = CompletableDeferred<Boolean>()
    hlsScriptLoad = deferred

    val script =
        (document.getElementById(HLS_SCRIPT_ID) as? HTMLScriptElement)
            ?: (document.createElement("script") as HTMLScriptElement).apply {
                id = HLS_SCRIPT_ID
                src = HLS_SCRIPT_URL
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
            webVideoLogger.e { "Failed to load $HLS_SCRIPT_URL" }
            deferred.complete(false)
        })
    }

    if (script.parentNode == null) {
        (document.head ?: document.body)?.appendChild(script)
    }

    val loaded = deferred.await()
    if (!loaded) hlsScriptLoad = null
    return loaded && isHlsLoaded()
}

private fun isHlsLoaded(): Boolean =
    js("typeof globalThis.Hls === 'function'")

internal suspend fun HTMLVideoElement.configureHlsSource(
    playerState: DefaultVideoPlayerState,
    sourceUri: String,
    scope: CoroutineScope,
): Boolean {
    if (!sourceUri.isHlsSource()) return false
    if (!ensureHlsScriptLoaded()) return false

    setupHlsSource(
        video = this,
        sourceUri = sourceUri,
        onTracksChanged = {
            scope.launch {
                playerState.syncWebMediaTracks(this@configureHlsSource)
                this@configureHlsSource.applySelectedAudioTrack(playerState.currentAudioTrack)
                this@configureHlsSource.applySelectedSubtitleTrack(
                    if (playerState.subtitlesEnabled) playerState.currentSubtitleTrack else null,
                )
            }
        },
        onError = { message ->
            scope.launch {
                playerState.setError(VideoPlayerError.SourceError(message))
            }
        },
    )
    return true
}

@Suppress("UNUSED_PARAMETER")
private fun setupHlsSource(
    video: HTMLVideoElement,
    sourceUri: String,
    onTracksChanged: () -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            const Hls = globalThis.Hls;
            if (!Hls || typeof Hls.isSupported !== "function" || !Hls.isSupported()) {
                video.src = sourceUri;
                video.load();
                onTracksChanged();
            } else {
                if (video.__composeMediaPlayerHls) {
                    try { video.__composeMediaPlayerHls.destroy(); } catch (_) {}
                }

                const hls = new Hls({ renderTextTracksNatively: false });
                video.__composeMediaPlayerHls = hls;
                video.__composeMediaPlayerHlsSubtitleRows = "";

                const sync = function() {
                    try { onTracksChanged(); } catch (error) { console.error(error); }
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
                        const id = "${HLS_SUBTITLE_TRACK_ID_PREFIX}" + rows.length + ":" + encodeURIComponent(label);
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
                hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, sync);
                hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, sync);
                hls.on(Hls.Events.ERROR, function(_, data) {
                    if (data && data.fatal) {
                        try { onError(String(data.details || data.type || "HLS playback error")); } catch (_) {}
                    }
                });
                hls.attachMedia(video);

                fetch(sourceUri)
                    .then(function(response) { return response.ok ? response.text() : ""; })
                    .then(function(text) {
                        video.__composeMediaPlayerHlsSubtitleRows = parseSubtitleRows(text, sourceUri);
                        sync();
                    })
                    .catch(function() {
                        video.__composeMediaPlayerHlsSubtitleRows = "";
                        sync();
                    });
            }
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
        }
        """,
    )
