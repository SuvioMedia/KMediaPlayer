@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

private const val ASS_RENDERER_SCRIPT_ID = "compose-media-player-libass"

private var assRendererScriptLoad: CompletableDeferred<Boolean>? = null

internal fun createAssSubtitleCanvasElement(): HTMLCanvasElement =
    (document.createElement("canvas") as HTMLCanvasElement).apply {
        className = "compose-media-player-ass-subtitles"
        applyAssSubtitleCanvasStyle()
    }

internal fun HTMLCanvasElement.applyAssSubtitleCanvasStyle() {
    val wrapper = parentElement as? HTMLElement
    wrapper?.style?.apply {
        setProperty("z-index", "-1", "important")
        setProperty("pointer-events", "none")
        backgroundColor = "transparent"
        display = "block"
    }
    (wrapper?.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")

    style.apply {
        position = "absolute"
        display = "block"
        setProperty("pointer-events", "none")
        backgroundColor = "transparent"
    }
}

internal suspend fun ensureAssRendererScriptLoaded(): Boolean {
    if (isAssSubtitleRendererLoaded()) return true

    assRendererScriptLoad?.let { return it.await() }

    val deferred = CompletableDeferred<Boolean>()
    assRendererScriptLoad = deferred

    val script =
        (document.getElementById(ASS_RENDERER_SCRIPT_ID) as? HTMLScriptElement)
            ?: (document.createElement("script") as HTMLScriptElement).apply {
                id = ASS_RENDERER_SCRIPT_ID
                src = AssSubtitleRendererConfig.scriptUrl
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
            webVideoLogger.e { "Failed to load ${AssSubtitleRendererConfig.scriptUrl}" }
            deferred.complete(false)
        })
    }

    if (script.parentNode == null) {
        (document.head ?: document.body)?.appendChild(script)
    }

    val loaded = deferred.await()
    if (!loaded) assRendererScriptLoad = null
    return loaded && isAssSubtitleRendererLoaded()
}

private fun isAssSubtitleRendererLoaded(): Boolean =
    js("typeof globalThis.SubtitlesOctopus === 'function'")

internal fun createAssSubtitleRenderer(
    video: HTMLVideoElement,
    canvas: HTMLCanvasElement,
    subUrl: String,
    workerUrl: String,
    legacyWorkerUrl: String,
    fallbackFontUrl: String,
    debug: Boolean,
    onReady: () -> Unit,
    onError: (String) -> Unit,
): JsAny =
    js(
        """
        (function() {
            function toWorkerUrl(url) {
                const resolvedUrl = new URL(url, document.baseURI);
                if (
                    resolvedUrl.origin === globalThis.location.origin ||
                    resolvedUrl.protocol === "blob:" ||
                    resolvedUrl.protocol === "data:"
                ) {
                    return resolvedUrl.toString();
                }

                const workerBaseUrl = new URL(".", resolvedUrl).toString();
                const source = [
                    "self.Module = self.Module || {};",
                    "self.Module.locateFile = function(path) { return " + JSON.stringify(workerBaseUrl) + " + path; };",
                    "importScripts(" + JSON.stringify(resolvedUrl.toString()) + ");"
                ].join("\n");
                return URL.createObjectURL(new Blob([source], { type: "application/javascript" }));
            }

            const effectiveWorkerUrl = toWorkerUrl(workerUrl);
            const effectiveLegacyWorkerUrl = toWorkerUrl(legacyWorkerUrl);
            function decodeDataUrl(url) {
                if (typeof url !== "string" || url.indexOf("data:") !== 0) return null;
                const comma = url.indexOf(",");
                if (comma === -1) return null;

                const metadata = url.substring(5, comma).toLowerCase();
                const payload = url.substring(comma + 1);
                if (metadata.indexOf(";base64") !== -1) {
                    const binary = atob(payload);
                    const bytes = new Uint8Array(binary.length);
                    for (let index = 0; index < binary.length; index += 1) {
                        bytes[index] = binary.charCodeAt(index);
                    }
                    return new TextDecoder("utf-8").decode(bytes);
                }
                return decodeURIComponent(payload);
            }

            const subtitleOptions = {};
            const subContent = decodeDataUrl(subUrl);
            if (subContent != null) {
                subtitleOptions.subContent = subContent;
            } else {
                subtitleOptions.subUrl = new URL(subUrl, document.baseURI).toString();
            }

            const instance =
                new globalThis.SubtitlesOctopus(Object.assign({
                    video: video,
                    canvas: canvas,
                    workerUrl: effectiveWorkerUrl,
                    legacyWorkerUrl: effectiveLegacyWorkerUrl,
                    fallbackFont: fallbackFontUrl,
                    renderMode: "wasm-blend",
                    targetFps: 60,
                    debug: debug,
                    onReady: onReady,
                    onError: function(err) {
                        onError(err && err.message ? err.message : String(err));
                    }
                }, subtitleOptions));
            instance.__composeMediaPlayerWorkerUrls = [effectiveWorkerUrl, effectiveLegacyWorkerUrl]
                .filter(function(url) { return url.indexOf("blob:") === 0; });
            if (!instance.canvasParent && canvas.parentElement) {
                instance.canvasParent = canvas.parentElement;
            }
            if (typeof instance.setVideo === "function") {
                instance.setVideo(video);
            }
            if (typeof instance.resize === "function") {
                instance.resize();
            }
            return instance;
        })()
        """,
    )

internal fun disposeAssSubtitleRenderer(instance: JsAny): Unit =
    js(
        """
        {
            if (instance && typeof instance.dispose === "function") {
                try {
                    instance.dispose();
                } catch (err) {
                    // SubtitlesOctopus may already have had its canvas moved or removed
                    // by Compose WebElementView during media element recreation.
                }
            }
            if (instance && instance.__composeMediaPlayerWorkerUrls) {
                instance.__composeMediaPlayerWorkerUrls.forEach(function(url) {
                    URL.revokeObjectURL(url);
                });
                instance.__composeMediaPlayerWorkerUrls = null;
            }
        }
        """,
    )

internal fun resizeAssSubtitleRenderer(instance: JsAny?): Unit =
    js(
        """
        {
            if (instance && typeof instance.resize === "function") {
                instance.resize();
            }
        }
        """,
    )

internal fun resizeAssSubtitleCanvas(canvas: HTMLCanvasElement): Unit =
    js(
        """
        {
            if (canvas && canvas.width > 0 && canvas.height > 0) {
                const event = new Event("resize");
                globalThis.dispatchEvent(event);
            }
        }
        """,
    )

internal fun clearAssSubtitleCanvas(canvas: HTMLCanvasElement): Unit =
    js(
        """
        {
            if (canvas) {
                const context = canvas.getContext("2d");
                if (context) {
                    context.clearRect(0, 0, canvas.width, canvas.height);
                }
            }
        }
        """,
    )
