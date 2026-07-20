@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

internal const val ASS_MKV_FONTS_CHANGED_EVENT: String = "composemediaplayer:mkv-fonts-changed"

@Suppress("TooGenericExceptionCaught")
internal fun createAssSubtitleRendererSession(
    video: HTMLVideoElement?,
    canvas: HTMLCanvasElement,
    displayElement: HTMLElement,
    subUrl: String,
    config: AssSubtitleRendererConfig,
    timeOffsetSeconds: Double,
    contentScaleMode: String,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onRestartRequired: () -> Unit,
): JsAny? {
    val unavailableReason = assSubtitleRendererUnavailableReason(config.enabled)
    if (unavailableReason.isNotEmpty()) {
        onError(unavailableReason)
        return null
    }

    val queryFonts = config.fontQueryMode.toJassubQueryFonts()
    var pendingOptions: JsAny? = null
    val options =
        try {
            createJassubRendererOptions(
                video = video,
                fontCarrier = video ?: displayElement,
                canvas = canvas,
                subUrl = subUrl,
                workerUrl = config.workerUrl,
                wasmUrl = config.wasmUrl,
                modernWasmUrl = config.modernWasmUrl,
                fallbackFontUrl = config.fallbackFontUrl,
                fallbackFontFamily = config.fallbackFontFamily,
                queryFonts = queryFonts,
                timeOffsetSeconds = timeOffsetSeconds,
                debug = config.debug,
            ).also { rendererOptions ->
                pendingOptions = rendererOptions
                config.preloadFontUrls.forEach { fontUrl ->
                    addJassubPreloadFontUrl(rendererOptions, fontUrl)
                }
                config.availableFontUrls.forEach { (fontFamily, fontUrl) ->
                    addJassubAvailableFontUrl(rendererOptions, fontFamily, fontUrl)
                }
                finalizeJassubRendererOptions(rendererOptions)
            }
        } catch (error: Throwable) {
            pendingOptions?.let(::revokeJassubRendererOptionUrls)
            onError(error.message ?: "JASSUB options could not be created.")
            return null
        }

    val instance =
        try {
            createJassubRenderer(options).also { renderer ->
                hardenJassubLocalFontQuery(
                    instance = renderer,
                    enabled = queryFonts != "disabled",
                    debug = config.debug,
                )
            }
        } catch (error: Throwable) {
            revokeJassubRendererOptionUrls(options)
            onError(error.message ?: "JASSUB constructor failed.")
            return null
        }
    return configureJassubRendererSession(
        instance = instance,
        options = options,
        video = video,
        canvas = canvas,
        displayElement = displayElement,
        contentScaleMode = contentScaleMode,
        layoutCalculator = ::encodeAssSubtitleOverlayRect,
        streamUpdateCalculator = ::computeAssSubtitleStreamUpdate,
        renderDimensionCalculator = ::assSubtitleRenderDimension,
        onReady = onReady,
        onError = onError,
        onRestartRequired = onRestartRequired,
    )
}

private fun AssFontQueryMode.toJassubQueryFonts(): String =
    when (this) {
        AssFontQueryMode.DISABLED -> "disabled"
        AssFontQueryMode.LOCAL -> "local"
        AssFontQueryMode.LOCAL_AND_REMOTE -> "localandremote"
    }

@Suppress("UNUSED_PARAMETER")
private fun createJassubRendererOptions(
    video: HTMLVideoElement?,
    fontCarrier: HTMLElement,
    canvas: HTMLCanvasElement,
    subUrl: String,
    workerUrl: String?,
    wasmUrl: String?,
    modernWasmUrl: String?,
    fallbackFontUrl: String?,
    fallbackFontFamily: String,
    queryFonts: String,
    timeOffsetSeconds: Double,
    debug: Boolean,
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

                try {
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
                } catch (_) {
                    return null;
                }
            }

            const workerUrls = [];
            try {
                if (!canvas || typeof canvas.transferControlToOffscreen !== "function") {
                    throw new Error("JASSUB requires canvas.transferControlToOffscreen support.");
                }

                const subContent = decodeDataUrl(subUrl);
                const embeddedFonts =
                    fontCarrier && Array.isArray(fontCarrier.__composeMediaPlayerMkvFontFiles)
                        ? fontCarrier.__composeMediaPlayerMkvFontFiles.slice()
                        : [];
                const options = {
                    canvas: canvas,
                    queryFonts: queryFonts === "disabled" ? false : queryFonts,
                    timeOffset: timeOffsetSeconds,
                    debug: debug,
                    fonts: embeddedFonts,
                    availableFonts: {},
                    __composeMediaPlayerOriginalSource: subUrl,
                    __composeMediaPlayerSubContent: subContent,
                    __composeMediaPlayerInitialFonts: embeddedFonts.slice(),
                    __composeMediaPlayerWorkerUrls: workerUrls,
                    __composeMediaPlayerFallbackFontUrl: fallbackFontUrl,
                    __composeMediaPlayerFallbackFontFamily: fallbackFontFamily
                };
                if (video) {
                    options.video = video;
                }

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
                return options;
            } catch (error) {
                workerUrls.forEach(function(url) {
                    try { URL.revokeObjectURL(url); } catch (_) {}
                });
                throw error;
            }
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun addJassubPreloadFontUrl(
    options: JsAny,
    fontUrl: String,
): Unit =
    js(
        """
        {
            options.fonts.push(new URL(fontUrl, document.baseURI).toString());
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun addJassubAvailableFontUrl(
    options: JsAny,
    fontFamily: String,
    fontUrl: String,
): Unit =
    js(
        """
        {
            options.availableFonts[fontFamily.trim().toLowerCase()] =
                new URL(fontUrl, document.baseURI).toString();
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun finalizeJassubRendererOptions(options: JsAny): Unit =
    js(
        """
        {
            if (options.__composeMediaPlayerFallbackFontUrl) {
                const family =
                    (options.__composeMediaPlayerFallbackFontFamily || "liberation sans")
                        .trim()
                        .toLowerCase();
                options.availableFonts[family] =
                    new URL(options.__composeMediaPlayerFallbackFontUrl, document.baseURI).toString();
                options.defaultFont = family;
            }
            delete options.__composeMediaPlayerFallbackFontUrl;
            delete options.__composeMediaPlayerFallbackFontFamily;
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun revokeJassubRendererOptionUrls(options: JsAny): Unit =
    js(
        """
        {
            const urls = options && options.__composeMediaPlayerWorkerUrls;
            if (Array.isArray(urls)) {
                urls.forEach(function(url) {
                    try { URL.revokeObjectURL(url); } catch (_) {}
                });
                options.__composeMediaPlayerWorkerUrls = [];
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun hardenJassubLocalFontQuery(
    instance: JsAny,
    enabled: Boolean,
    debug: Boolean,
): Unit =
    js(
        """
        {
            if (
                enabled &&
                instance &&
                typeof instance._getLocalFont === "function" &&
                !instance.__composeMediaPlayerLocalFontQueryHardened
            ) {
                const originalGetLocalFont = instance._getLocalFont.bind(instance);
                instance._getLocalFont = async function(font, weight) {
                    try {
                        return await originalGetLocalFont(font, weight);
                    } catch (error) {
                        if (debug) {
                            console.warn(
                                "KMediaPlayer ASS: local font lookup is unavailable; continuing without it.",
                                error
                            );
                        }
                        return undefined;
                    }
                };
                instance.__composeMediaPlayerLocalFontQueryHardened = true;
            }
        }
        """,
    )

@Suppress("LongMethod", "UNUSED_PARAMETER")
internal fun configureJassubRendererSession(
    instance: JsAny,
    options: JsAny,
    video: HTMLVideoElement?,
    canvas: HTMLCanvasElement,
    displayElement: HTMLElement,
    contentScaleMode: String,
    layoutCalculator: (Double, Double, Double, Double, String, Boolean) -> String,
    streamUpdateCalculator: (String?, String?) -> String,
    renderDimensionCalculator: (Double, Double) -> Int,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onRestartRequired: () -> Unit,
    destroyTimeoutMillis: Int = 2_000,
): JsAny =
    js(
        """
        (function() {
            const fontCarrier = video || displayElement;
            const session = {
                disposed: false,
                failed: false,
                active: false,
                restartRequested: false,
                instance: instance,
                video: video,
                canvas: canvas,
                displayElement: displayElement,
                contentScaleMode: contentScaleMode,
                currentSource: options.__composeMediaPlayerOriginalSource,
                currentContent: options.__composeMediaPlayerSubContent,
                targetStyle: null,
                layoutDirty: true,
                layoutFrame: 0,
                layoutRunning: false,
                lastManualFrame: null,
                lastManualWidth: 0,
                lastManualHeight: 0,
                destroyPromise: null,
                queue: Promise.resolve(instance.ready),
                loadedFonts: new Set(options.__composeMediaPlayerInitialFonts || [])
            };
            const workerUrls = options.__composeMediaPlayerWorkerUrls || [];
            delete options.__composeMediaPlayerWorkerUrls;
            delete options.__composeMediaPlayerInitialFonts;
            delete options.__composeMediaPlayerOriginalSource;
            delete options.__composeMediaPlayerSubContent;

            function messageOf(error) {
                return error && error.message ? error.message : String(error);
            }

            function decodeDataUrl(url) {
                if (typeof url !== "string" || url.indexOf("data:") !== 0) return null;
                const comma = url.indexOf(",");
                if (comma === -1) return null;
                try {
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
                } catch (_) {
                    return null;
                }
            }

            function invokeInactive() {
                if (session.active) session.active = false;
            }

            function revokeWorkerUrls() {
                workerUrls.forEach(function(url) {
                    try { URL.revokeObjectURL(url); } catch (_) {}
                });
                workerUrls.length = 0;
            }

            function removeObservers() {
                if (session.resizeObserver) {
                    session.resizeObserver.disconnect();
                    session.resizeObserver = null;
                }
                if (session.styleObserver) {
                    session.styleObserver.disconnect();
                    session.styleObserver = null;
                }
                if (fontCarrier) {
                    fontCarrier.removeEventListener("${ASS_MKV_FONTS_CHANGED_EVENT}", session.syncFonts);
                }
                if (video) {
                    video.removeEventListener("loadedmetadata", session.scheduleLayout);
                    video.removeEventListener("resize", session.scheduleLayout);
                    video.removeEventListener("seeked", session.scheduleLayout);
                    video.removeEventListener("pause", session.scheduleLayout);
                }
                if (session.layoutFrame) {
                    cancelAnimationFrame(session.layoutFrame);
                    session.layoutFrame = 0;
                }
            }

            session.dispose = function() {
                if (session.destroyPromise) return session.destroyPromise;
                session.disposed = true;
                invokeInactive();
                removeObservers();
                let destroyTimeout = 0;
                const gracefulDestroy = Promise.resolve()
                    .then(function() {
                        if (instance && typeof instance.destroy === "function") {
                            return instance.destroy();
                        }
                    })
                    .then(function() { return true; });
                const destroyDeadline = new Promise(function(resolve) {
                    destroyTimeout = setTimeout(function() { resolve(false); }, destroyTimeoutMillis);
                });
                session.destroyPromise = Promise.race([gracefulDestroy, destroyDeadline])
                    .then(function(completedGracefully) {
                        if (!completedGracefully) {
                            console.warn("KMediaPlayer ASS: JASSUB destroy timed out; terminating its worker.");
                        }
                    })
                    .catch(function(error) {
                        console.warn("KMediaPlayer ASS: JASSUB destroy failed:", error);
                    })
                    .finally(function() {
                        if (destroyTimeout) clearTimeout(destroyTimeout);
                        try {
                            if (instance && instance._worker && typeof instance._worker.terminate === "function") {
                                instance._worker.terminate();
                            }
                        } catch (_) {}
                        revokeWorkerUrls();
                    });
                return session.destroyPromise;
            };

            function fail(error) {
                if (session.disposed || session.failed) return;
                session.failed = true;
                invokeInactive();
                onError(messageOf(error));
                session.dispose();
            }

            function requestRestart() {
                if (session.disposed || session.restartRequested) return;
                session.restartRequested = true;
                invokeInactive();
                onRestartRequired();
                session.dispose();
            }

            function enqueue(operation, failureMode) {
                const task = session.queue.then(function() {
                    if (session.disposed) return;
                    return operation();
                });
                session.queue = task.catch(function() {});
                task.catch(function(error) {
                    if (session.disposed) return;
                    if (failureMode === "restart") requestRestart();
                    else if (failureMode === "warn") {
                        console.warn("KMediaPlayer ASS:", error);
                    } else {
                        fail(error);
                    }
                });
                return task;
            }

            function readLayout() {
                const wrapper = canvas.parentElement;
                const target = session.displayElement || video || canvas;
                if (!wrapper || !target) return null;
                const wrapperRect = wrapper.getBoundingClientRect();
                const targetRect = target.getBoundingClientRect();
                if (
                    wrapperRect.width <= 0 ||
                    wrapperRect.height <= 0 ||
                    targetRect.width <= 0 ||
                    targetRect.height <= 0
                ) {
                    return null;
                }

                const mediaWidth = video
                    ? Number(video.videoWidth || 0)
                    : Number(target.width || canvas.width || 0);
                const mediaHeight = video
                    ? Number(video.videoHeight || 0)
                    : Number(target.height || canvas.height || 0);
                const wholeDisplay = Boolean(video && target !== video);
                const encoded = layoutCalculator(
                    targetRect.width,
                    targetRect.height,
                    mediaWidth,
                    mediaHeight,
                    session.contentScaleMode,
                    wholeDisplay
                );
                const values = encoded.split("|").map(Number);
                if (values.length !== 4 || values.some(function(value) { return !Number.isFinite(value); })) {
                    return null;
                }
                return {
                    x: targetRect.left - wrapperRect.left + values[0],
                    y: targetRect.top - wrapperRect.top + values[1],
                    width: values[2],
                    height: values[3]
                };
            }

            function styleValue(value) {
                return Math.round(value * 1000) / 1000 + "px";
            }

            function applyTargetStyle(target) {
                if (!target || session.disposed) return;
                const left = styleValue(target.x);
                const top = styleValue(target.y);
                const width = styleValue(target.width);
                const height = styleValue(target.height);
                if (canvas.style.left !== left) canvas.style.left = left;
                if (canvas.style.top !== top) canvas.style.top = top;
                if (canvas.style.width !== width) canvas.style.width = width;
                if (canvas.style.height !== height) canvas.style.height = height;
            }

            function styleMatchesTarget() {
                const target = session.targetStyle;
                return !target ||
                    (
                        canvas.style.left === styleValue(target.x) &&
                        canvas.style.top === styleValue(target.y) &&
                        canvas.style.width === styleValue(target.width) &&
                        canvas.style.height === styleValue(target.height)
                    );
            }

            function finishLayout(target) {
                if (session.disposed) return;
                session.targetStyle = target;
                applyTargetStyle(target);
                if (!session.active) {
                    session.active = true;
                    onReady();
                }
                if (!video && session.lastManualFrame) {
                    renderManualFrame(session.lastManualFrame, true);
                }
            }

            function readManualFrameSize() {
                const target = session.displayElement || canvas;
                const ratio = Number(globalThis.devicePixelRatio || 1);
                let width = Number(target && target.width || 0);
                let height = Number(target && target.height || 0);
                if (width > 0 && height > 0) return { width: width, height: height };

                const rect = target && target.getBoundingClientRect
                    ? target.getBoundingClientRect()
                    : null;
                width = Number(rect && rect.width || session.targetStyle && session.targetStyle.width || 0);
                height = Number(rect && rect.height || session.targetStyle && session.targetStyle.height || 0);
                return {
                    width: Math.max(1, Math.round(width * ratio)),
                    height: Math.max(1, Math.round(height * ratio))
                };
            }

            function renderManualFrame(frame, repaint) {
                if (
                    video ||
                    session.disposed ||
                    session.failed ||
                    !session.active ||
                    !frame ||
                    !Number.isFinite(frame.mediaTime)
                ) {
                    return;
                }
                if (!instance || typeof instance.manualRender !== "function") {
                    fail(new Error("JASSUB canvas-only rendering is unavailable."));
                    return;
                }
                const size = readManualFrameSize();
                if (
                    size.width !== session.lastManualWidth ||
                    size.height !== session.lastManualHeight
                ) {
                    session.lastManualWidth = size.width;
                    session.lastManualHeight = size.height;
                    session.scheduleLayout();
                }
                const task = Promise.resolve(
                    instance.manualRender(
                        {
                            expectedDisplayTime: performance.now(),
                            width: size.width,
                            height: size.height,
                            mediaTime: frame.mediaTime
                        },
                        Boolean(repaint)
                    )
                );
                task.catch(fail);
            }

            function runLayout() {
                if (session.disposed || session.layoutRunning) return;
                const target = readLayout();
                if (!target || target.width <= 0 || target.height <= 0) return;
                session.layoutDirty = false;
                session.layoutRunning = true;
                session.targetStyle = target;
                applyTargetStyle(target);
                const ratio = Number(globalThis.devicePixelRatio || 1);
                const renderWidth = renderDimensionCalculator(target.width, ratio);
                const renderHeight = renderDimensionCalculator(target.height, ratio);
                const task = enqueue(function() {
                    return instance.resize(true, renderWidth, renderHeight);
                }, "fatal");
                task.then(
                    function() {
                        session.layoutRunning = false;
                        finishLayout(target);
                        if (session.layoutDirty) session.scheduleLayout();
                    },
                    function() {
                        session.layoutRunning = false;
                    }
                );
            }

            session.scheduleLayout = function() {
                if (session.disposed) return;
                session.layoutDirty = true;
                if (session.layoutFrame) return;
                session.layoutFrame = requestAnimationFrame(function() {
                    session.layoutFrame = 0;
                    runLayout();
                });
            };

            session.updateLayout = function(nextDisplayElement, nextContentScaleMode) {
                if (session.disposed) return;
                session.displayElement = nextDisplayElement || video || canvas;
                session.contentScaleMode = nextContentScaleMode;
                if (session.resizeObserver) {
                    session.resizeObserver.disconnect();
                    const wrapper = canvas.parentElement;
                    if (wrapper) session.resizeObserver.observe(wrapper);
                    if (session.displayElement) session.resizeObserver.observe(session.displayElement);
                    if (video) session.resizeObserver.observe(video);
                }
                session.scheduleLayout();
            };

            session.renderFrame = function(mediaTime, repaint) {
                if (session.disposed || !Number.isFinite(mediaTime)) return;
                session.lastManualFrame = { mediaTime: mediaTime };
                renderManualFrame(session.lastManualFrame, repaint);
            };

            session.updateOffset = function(offsetSeconds) {
                if (session.disposed || !Number.isFinite(offsetSeconds)) return;
                instance.timeOffset = offsetSeconds;
                if (session.active) session.scheduleLayout();
            };

            session.updateSource = function(nextSource) {
                if (session.disposed || typeof nextSource !== "string") return;
                const nextContent = decodeDataUrl(nextSource);
                if (nextSource === session.currentSource && nextContent === session.currentContent) return;
                if (session.currentContent == null && nextContent == null) {
                    requestRestart();
                    return;
                }
                const update = streamUpdateCalculator(session.currentContent, nextContent);
                session.currentSource = nextSource;
                session.currentContent = nextContent;
                if (update === "restart") {
                    requestRestart();
                    return;
                }
                if (update === "none") return;
                if (update.indexOf("append\u0000") !== 0) {
                    requestRestart();
                    return;
                }
                const delta = update.substring(7);
                const task = enqueue(function() {
                    return instance.renderer.processData(delta);
                }, "restart");
                task.then(
                    function() { session.scheduleLayout(); },
                    function() {}
                );
            };

            session.syncFonts = function() {
                if (session.disposed || !fontCarrier) return;
                const available =
                    Array.isArray(fontCarrier.__composeMediaPlayerMkvFontFiles)
                        ? fontCarrier.__composeMediaPlayerMkvFontFiles
                        : [];
                const additions = [];
                available.forEach(function(font) {
                    if (!session.loadedFonts.has(font)) {
                        session.loadedFonts.add(font);
                        additions.push(font);
                    }
                });
                if (!additions.length) return;
                const task = enqueue(function() {
                    return instance.renderer.addFonts(additions);
                }, "warn");
                task.then(
                    function() { session.scheduleLayout(); },
                    function() {}
                );
            };

            session.resizeObserver = new ResizeObserver(session.scheduleLayout);
            const initialWrapper = canvas.parentElement;
            if (initialWrapper) session.resizeObserver.observe(initialWrapper);
            session.resizeObserver.observe(displayElement);
            if (video) session.resizeObserver.observe(video);
            session.styleObserver = new MutationObserver(function() {
                if (session.disposed || styleMatchesTarget()) return;
                requestAnimationFrame(function() {
                    if (!session.disposed) applyTargetStyle(session.targetStyle);
                });
            });
            session.styleObserver.observe(canvas, { attributes: true, attributeFilter: ["style"] });
            if (video) {
                video.addEventListener("loadedmetadata", session.scheduleLayout);
                video.addEventListener("resize", session.scheduleLayout);
                video.addEventListener("seeked", session.scheduleLayout);
                video.addEventListener("pause", session.scheduleLayout);
            }
            if (fontCarrier) {
                fontCarrier.addEventListener("${ASS_MKV_FONTS_CHANGED_EVENT}", session.syncFonts);
            }

            Promise.resolve(instance.ready).then(
                function() {
                    if (!session.disposed) session.scheduleLayout();
                },
                fail
            );
            return session;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun updateAssSubtitleRendererSource(
    session: JsAny?,
    subUrl: String,
): Unit =
    js(
        """
        {
            if (session && typeof session.updateSource === "function") {
                session.updateSource(subUrl);
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun updateAssSubtitleRendererOffset(
    session: JsAny?,
    timeOffsetSeconds: Double,
): Unit =
    js(
        """
        {
            if (session && typeof session.updateOffset === "function") {
                session.updateOffset(timeOffsetSeconds);
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun updateAssSubtitleRendererLayout(
    session: JsAny?,
    displayElement: HTMLElement?,
    contentScaleMode: String,
): Unit =
    js(
        """
        {
            if (session && typeof session.updateLayout === "function") {
                session.updateLayout(displayElement, contentScaleMode);
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun renderAssSubtitleFrame(
    session: JsAny?,
    mediaTimeSeconds: Double,
    repaint: Boolean,
): Unit =
    js(
        """
        {
            if (session && typeof session.renderFrame === "function") {
                session.renderFrame(mediaTimeSeconds, repaint);
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun disposeAssSubtitleRendererSession(session: JsAny?): Unit =
    js(
        """
        {
            if (session && typeof session.dispose === "function") {
                const result = session.dispose();
                if (result && typeof result.catch === "function") {
                    result.catch(function() {});
                }
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun clearAssSubtitleCanvas(canvas: HTMLCanvasElement): Unit =
    js(
        """
        {
            if (canvas) {
                try {
                    const context = canvas.getContext("2d");
                    if (context) context.clearRect(0, 0, canvas.width, canvas.height);
                } catch (_) {
                    // The DOM canvas no longer exposes a context after OffscreenCanvas transfer.
                }
                try {
                    canvas.width = 0;
                    canvas.height = 0;
                } catch (_) {}
            }
        }
        """,
    )
