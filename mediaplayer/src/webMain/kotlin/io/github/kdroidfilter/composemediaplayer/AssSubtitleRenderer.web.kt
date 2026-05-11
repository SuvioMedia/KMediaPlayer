@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

internal expect fun createJassubRenderer(options: JsAny): JsAny

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

internal suspend fun ensureAssRendererScriptLoaded(): Boolean = true

internal fun createAssSubtitleRenderer(
    video: HTMLVideoElement,
    canvas: HTMLCanvasElement,
    subUrl: String,
    debug: Boolean,
    onReady: () -> Unit,
    onError: (String) -> Unit,
): JsAny {
    val options =
        createJassubRendererOptions(
            video = video,
            canvas = canvas,
            subUrl = subUrl,
            workerUrl = AssSubtitleRendererConfig.workerUrl,
            wasmUrl = AssSubtitleRendererConfig.wasmUrl,
            modernWasmUrl = AssSubtitleRendererConfig.modernWasmUrl,
            fallbackFontUrl = AssSubtitleRendererConfig.fallbackFontUrl,
            fallbackFontFamily = AssSubtitleRendererConfig.fallbackFontFamily,
            queryFonts = AssSubtitleRendererConfig.queryFonts,
            debug = debug,
            onError = onError,
        )
    val instance = createJassubRenderer(options)
    attachJassubRendererWorkerUrls(instance, options)
    configureJassubRendererInstance(instance, onReady, onError)
    return instance
}

@Suppress("UNUSED_PARAMETER")
private fun createJassubRendererOptions(
    video: HTMLVideoElement,
    canvas: HTMLCanvasElement,
    subUrl: String,
    workerUrl: String,
    wasmUrl: String,
    modernWasmUrl: String,
    fallbackFontUrl: String,
    fallbackFontFamily: String,
    queryFonts: Boolean,
    debug: Boolean,
    onError: (String) -> Unit,
): JsAny =
    js(
        """
        (function() {
            function toModuleWorkerUrl(url) {
                const resolvedUrl = new URL(url, document.baseURI);
                if (
                    resolvedUrl.origin === globalThis.location.origin ||
                    resolvedUrl.protocol === "blob:" ||
                    resolvedUrl.protocol === "data:"
                ) {
                    return resolvedUrl.toString();
                }

                const source = "import " + JSON.stringify(resolvedUrl.toString()) + ";";
                return URL.createObjectURL(new Blob([source], { type: "application/javascript" }));
            }

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

            if (!canvas || typeof canvas.transferControlToOffscreen !== "function") {
                onError("JASSUB requires canvas.transferControlToOffscreen support");
            }

            const options = {
                video: video,
                canvas: canvas,
                queryFonts: queryFonts,
                debug: debug
            };
            const workerUrls = [];

            const subContent = decodeDataUrl(subUrl);
            if (subContent != null) {
                options.subContent = subContent;
            } else {
                options.subUrl = new URL(subUrl, document.baseURI).toString();
            }

            if (workerUrl) {
                options.workerUrl = toModuleWorkerUrl(workerUrl);
                if (options.workerUrl.indexOf("blob:") === 0) {
                    workerUrls.push(options.workerUrl);
                }
            }
            if (wasmUrl) {
                options.wasmUrl = new URL(wasmUrl, document.baseURI).toString();
            }
            if (modernWasmUrl) {
                options.modernWasmUrl = new URL(modernWasmUrl, document.baseURI).toString();
            }
            if (fallbackFontUrl) {
                const fontFamily = (fallbackFontFamily || "liberation sans").trim().toLowerCase();
                const availableFonts = {};
                availableFonts[fontFamily] = new URL(fallbackFontUrl, document.baseURI).toString();
                options.availableFonts = availableFonts;
                options.defaultFont = fontFamily;
            }

            options.__composeMediaPlayerWorkerUrls = workerUrls;
            return options;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun attachJassubRendererWorkerUrls(
    instance: JsAny,
    options: JsAny,
): Unit =
    js(
        """
        {
            if (instance && options && options.__composeMediaPlayerWorkerUrls) {
                instance.__composeMediaPlayerWorkerUrls = options.__composeMediaPlayerWorkerUrls;
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun configureJassubRendererInstance(
    instance: JsAny,
    onReady: () -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            if (instance && instance.ready && typeof instance.ready.then === "function") {
                instance.ready.then(function() {
                    onReady();
                    if (typeof instance.resize === "function") {
                        instance.resize(true);
                    }
                }).catch(function(error) {
                    onError(error && error.message ? error.message : String(error));
                });
            } else {
                onReady();
            }
        }
        """,
    )

internal fun disposeAssSubtitleRenderer(instance: JsAny): Unit =
    js(
        """
        {
            if (instance && typeof instance.destroy === "function") {
                try {
                    instance.destroy();
                } catch (err) {
                    // JASSUB may already be shutting down after its canvas was released.
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
                instance.resize(true);
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
                try {
                    const context = canvas.getContext("2d");
                    if (context) {
                        context.clearRect(0, 0, canvas.width, canvas.height);
                    }
                } catch (_) {
                    // JASSUB transfers the canvas to OffscreenCanvas, so getContext()
                    // is no longer valid on the DOM-side canvas after initialization.
                }
            }
        }
        """,
    )
