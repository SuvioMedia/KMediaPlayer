@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("LongMethod")

package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.AssFontQueryMode
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.WebEmbeddedSubtitleRenderer
import io.github.kdroidfilter.composemediaplayer.WebSubtitlePacket
import io.github.kdroidfilter.composemediaplayer.WebSubtitleRendererConfiguration
import io.github.kdroidfilter.composemediaplayer.assSubtitleRendererUnavailableReason
import io.github.kdroidfilter.composemediaplayer.createJassubRenderer
import io.github.kdroidfilter.composemediaplayer.hardenJassubLocalFontQuery
import kotlinx.coroutines.CompletableDeferred
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.time.Duration

private const val MILLISECONDS_PER_SECOND = 1_000.0

internal fun createWasmMediaAssSubtitleRenderer(
    config: AssSubtitleRendererConfig,
    onError: (String) -> Unit,
): WebEmbeddedSubtitleRenderer? {
    val unavailableReason = assSubtitleRendererUnavailableReason(config.enabled)
    if (unavailableReason.isNotEmpty()) return null

    val settings =
        createWasmMediaJassubSettings(
            workerUrl = config.workerUrl,
            wasmUrl = config.wasmUrl,
            modernWasmUrl = config.modernWasmUrl,
            fallbackFontUrl = config.fallbackFontUrl,
            fallbackFontFamily = config.fallbackFontFamily,
            queryFonts = config.fontQueryMode.toJassubQueryFonts(),
            debug = config.debug,
        )
    config.preloadFontUrls.forEach { url ->
        addWasmMediaJassubPreloadFont(settings, url)
    }
    config.availableFontUrls.forEach { (family, url) ->
        addWasmMediaJassubAvailableFont(settings, family, url)
    }

    return WasmMediaAssSubtitleRenderer(
        adapter =
            createWasmMediaAssSubtitleRendererAdapter(
                settings = settings,
                createRenderer = ::createJassubRenderer,
                hardenRenderer = { instance ->
                    hardenJassubLocalFontQuery(
                        instance = instance,
                        enabled = config.fontQueryMode != AssFontQueryMode.DISABLED,
                        debug = config.debug,
                    )
                },
                onError = onError,
            ),
        onError = onError,
    )
}

private class WasmMediaAssSubtitleRenderer(
    private val adapter: JsAny,
    private val onError: (String) -> Unit,
) : WebEmbeddedSubtitleRenderer {
    private var width: Int = 0
    private var height: Int = 0
    private var closed: Boolean = false

    override suspend fun configure(
        configuration: WebSubtitleRendererConfiguration,
        overlay: HTMLElement,
    ) {
        check(!closed) { "The ASS subtitle renderer is closed." }
        width = configuration.width
        height = configuration.height
        val deferred = CompletableDeferred<Unit>()
        val fonts = createByteArrayList()
        configuration.attachments.forEach { attachment ->
            addByteArray(fonts, attachment.data.toInt8Array())
        }
        configureWasmMediaAssAdapter(
            adapter = adapter,
            overlay = overlay,
            codec = configuration.codec,
            extradata = configuration.codecPrivate?.toInt8Array(),
            fonts = fonts,
            onComplete = { deferred.complete(Unit) },
            onFailure = { message ->
                onError(message)
                deferred.completeExceptionally(IllegalStateException(message))
            },
        )
        deferred.await()
    }

    override suspend fun pushPacket(packet: WebSubtitlePacket) {
        if (closed) return
        val deferred = CompletableDeferred<Unit>()
        pushWasmMediaAssPacket(
            adapter = adapter,
            data = packet.data.toInt8Array(),
            timestamp = packet.presentationTime.inWholeMilliseconds / MILLISECONDS_PER_SECOND,
            duration = packet.duration.inWholeMilliseconds / MILLISECONDS_PER_SECOND,
            onComplete = { deferred.complete(Unit) },
            onFailure = { message ->
                onError(message)
                deferred.completeExceptionally(IllegalStateException(message))
            },
        )
        deferred.await()
    }

    override fun render(position: Duration) {
        if (!closed) {
            renderWasmMediaAssAdapter(
                adapter = adapter,
                timestamp = position.inWholeMilliseconds / MILLISECONDS_PER_SECOND,
                width = width,
                height = height,
            )
        }
    }

    override fun setDelay(delay: Duration) {
        if (!closed) {
            setWasmMediaAssDelay(adapter, delay.inWholeMilliseconds / MILLISECONDS_PER_SECOND)
        }
    }

    override fun clear() {
        if (!closed) clearWasmMediaAssAdapter(adapter, onError)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        val deferred = CompletableDeferred<Unit>()
        destroyWasmMediaAssAdapter(
            adapter = adapter,
            onComplete = { deferred.complete(Unit) },
            onFailure = { message ->
                onError(message)
                deferred.complete(Unit)
            },
        )
        deferred.await()
    }
}

private fun createByteArrayList(): JsAny = js("[]")

@Suppress("UNUSED_PARAMETER")
private fun addByteArray(
    list: JsAny,
    value: Int8Array,
): Unit = js("list.push(value)")

@Suppress("UNUSED_PARAMETER", "LongParameterList")
private fun configureWasmMediaAssAdapter(
    adapter: JsAny,
    overlay: HTMLElement,
    codec: String,
    extradata: Int8Array?,
    fonts: JsAny,
    onComplete: () -> Unit,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            try {
                adapter.mount(overlay);
                const track = { codec: codec, codecString: codec, subtitleType: "text" };
                Promise.resolve(adapter.configure(track, extradata || null, fonts))
                    .then(function() { onComplete(); })
                    .catch(function(error) {
                        onFailure(String(error && error.message ? error.message : error));
                    });
            } catch (error) {
                onFailure(String(error && error.message ? error.message : error));
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER", "LongParameterList")
private fun pushWasmMediaAssPacket(
    adapter: JsAny,
    data: Int8Array,
    timestamp: Double,
    duration: Double,
    onComplete: () -> Unit,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            try {
                Promise.resolve(adapter.pushPacket({
                    data: data,
                    timestamp: timestamp,
                    duration: duration
                }))
                    .then(function() { onComplete(); })
                    .catch(function(error) {
                        onFailure(String(error && error.message ? error.message : error));
                    });
            } catch (error) {
                onFailure(String(error && error.message ? error.message : error));
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun renderWasmMediaAssAdapter(
    adapter: JsAny,
    timestamp: Double,
    width: Int,
    height: Int,
): Unit = js("adapter.render(timestamp, width, height)")

@Suppress("UNUSED_PARAMETER")
private fun setWasmMediaAssDelay(
    adapter: JsAny,
    delay: Double,
): Unit = js("adapter.setDelay(delay)")

@Suppress("UNUSED_PARAMETER")
private fun clearWasmMediaAssAdapter(
    adapter: JsAny,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            try {
                Promise.resolve(adapter.clear()).catch(function(error) {
                    onFailure(String(error && error.message ? error.message : error));
                });
            } catch (error) {
                onFailure(String(error && error.message ? error.message : error));
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun destroyWasmMediaAssAdapter(
    adapter: JsAny,
    onComplete: () -> Unit,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            try {
                Promise.resolve(adapter.destroy())
                    .then(function() { onComplete(); })
                    .catch(function(error) {
                        onFailure(String(error && error.message ? error.message : error));
                    });
            } catch (error) {
                onFailure(String(error && error.message ? error.message : error));
            }
        }
        """,
    )

private fun AssFontQueryMode.toJassubQueryFonts(): String =
    when (this) {
        AssFontQueryMode.DISABLED -> "disabled"
        AssFontQueryMode.LOCAL -> "local"
        AssFontQueryMode.LOCAL_AND_REMOTE -> "localandremote"
    }

@Suppress("UNUSED_PARAMETER")
private fun createWasmMediaJassubSettings(
    workerUrl: String?,
    wasmUrl: String?,
    modernWasmUrl: String?,
    fallbackFontUrl: String?,
    fallbackFontFamily: String,
    queryFonts: String,
    debug: Boolean,
): JsAny =
    js(
        """
        ({
            workerUrl: workerUrl,
            wasmUrl: wasmUrl,
            modernWasmUrl: modernWasmUrl,
            fallbackFontUrl: fallbackFontUrl,
            fallbackFontFamily: fallbackFontFamily,
            queryFonts: queryFonts,
            debug: debug,
            preloadFonts: [],
            availableFonts: {}
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun addWasmMediaJassubPreloadFont(
    settings: JsAny,
    fontUrl: String,
): Unit =
    js(
        """
        {
            settings.preloadFonts.push(fontUrl);
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun addWasmMediaJassubAvailableFont(
    settings: JsAny,
    fontFamily: String,
    fontUrl: String,
): Unit =
    js(
        """
        {
            settings.availableFonts[fontFamily.trim().toLowerCase()] = fontUrl;
        }
        """,
    )

/**
 * Implements kmedia-wasm-engine's typed Kotlin/Wasm subtitle renderer contract.
 *
 * Kept separate from [createWasmMediaAssSubtitleRenderer] so Wasm tests can inject a
 * deterministic fake JASSUB constructor without starting a worker.
 */
@Suppress("UNUSED_PARAMETER")
internal fun createWasmMediaAssSubtitleRendererAdapter(
    settings: JsAny,
    createRenderer: (JsAny) -> JsAny,
    hardenRenderer: (JsAny) -> Unit,
    onError: (String) -> Unit,
    destroyTimeoutMillis: Int = 2_000,
): JsAny =
    js(
        """
        (function() {
            const DEFAULT_ASS_HEADER =
                "[Script Info]\n" +
                "ScriptType: v4.00+\n\n" +
                "[V4+ Styles]\n" +
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, " +
                "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, " +
                "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                "Alignment, MarginL, MarginR, MarginV, Encoding\n" +
                "Style: Default,Arial,24,&H00FFFFFF,&H000000FF,&H00000000," +
                "&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1\n\n" +
                "[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, " +
                "MarginV, Effect, Text\n";
            const DEFAULT_SSA_HEADER =
                "[Script Info]\n" +
                "ScriptType: v4.00\n\n" +
                "[V4 Styles]\n" +
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, " +
                "TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, " +
                "Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding\n" +
                "Style: Default,Arial,24,16777215,65535,65535,0,0,0,1,2,0,2," +
                "10,10,10,0,1\n\n" +
                "[Events]\n" +
                "Format: Marked, Start, End, Style, Name, MarginL, MarginR, " +
                "MarginV, Effect, Text\n";

            const state = {
                container: null,
                canvas: null,
                instance: null,
                instanceWorkerUrls: [],
                generation: 0,
                eventEpoch: 0,
                queue: Promise.resolve(),
                packetQueue: Promise.resolve(),
                destroyPromise: null,
                disposed: false,
                header: DEFAULT_ASS_HEADER,
                codec: "ass",
                delay: 0
            };

            function messageOf(error) {
                return error && error.message ? String(error.message) : String(error);
            }

            function report(error) {
                if (state.disposed) return;
                try {
                    onError(messageOf(error) || "JASSUB embedded subtitle rendering failed.");
                } catch (_) {}
            }

            function codecOf(track) {
                return String(track && (track.codec || track.codecString) || "")
                    .trim()
                    .toLowerCase();
            }

            function supports(track) {
                if (!track || track.subtitleType !== "text") return false;
                const codec = codecOf(track);
                return codec === "ass" ||
                    codec === "ssa" ||
                    codec === "s_text/ass" ||
                    codec === "s_text/ssa";
            }

            function decodeHeader(extradata, codec) {
                const fallback = codec.indexOf("ssa") !== -1
                    ? DEFAULT_SSA_HEADER
                    : DEFAULT_ASS_HEADER;
                if (!extradata || !Number(extradata.byteLength || extradata.length || 0)) {
                    return fallback;
                }
                try {
                    const text = new TextDecoder("utf-8")
                        .decode(extradata)
                        .replace(/\u0000+$/g, "")
                        .trim();
                    return text ? text + "\n" : fallback;
                } catch (_) {
                    return fallback;
                }
            }

            function resolveUrl(value) {
                return value
                    ? new URL(String(value), document.baseURI).toString()
                    : null;
            }

            function resolveWorkerUrl(value, ownedUrls) {
                const resolved = resolveUrl(value);
                if (!resolved) return null;
                const parsed = new URL(resolved);
                const locationOrigin =
                    globalThis.location && globalThis.location.origin
                        ? globalThis.location.origin
                        : parsed.origin;
                if (
                    parsed.origin === locationOrigin ||
                    parsed.protocol === "blob:" ||
                    parsed.protocol === "data:"
                ) {
                    return resolved;
                }
                const source = "import " + JSON.stringify(resolved) + ";";
                const wrapper = URL.createObjectURL(
                    new Blob([source], { type: "application/javascript" })
                );
                ownedUrls.push(wrapper);
                return wrapper;
            }

            function createCanvas() {
                if (!state.container) {
                    throw new Error("WasmMedia did not mount the embedded subtitle renderer.");
                }
                const canvas = document.createElement("canvas");
                canvas.setAttribute("aria-hidden", "true");
                canvas.style.position = "absolute";
                canvas.style.inset = "0";
                canvas.style.width = "100%";
                canvas.style.height = "100%";
                canvas.style.pointerEvents = "none";
                canvas.style.display = "block";
                state.container.style.pointerEvents = "none";
                state.container.style.overflow = "hidden";
                state.container.style.display = "block";
                state.container.appendChild(canvas);
                return canvas;
            }

            function ensureMounted() {
                if (!state.container || !state.canvas) return;
                if (state.canvas.parentElement !== state.container) {
                    state.container.appendChild(state.canvas);
                }
                state.container.style.display = "block";
            }

            function forceTerminate(instance) {
                try {
                    if (
                        instance &&
                        instance._worker &&
                        typeof instance._worker.terminate === "function"
                    ) {
                        instance._worker.terminate();
                    }
                } catch (_) {}
            }

            function teardownCurrent() {
                const instance = state.instance;
                const canvas = state.canvas;
                const ownedUrls = state.instanceWorkerUrls;
                state.instance = null;
                state.canvas = null;
                state.instanceWorkerUrls = [];
                if (!instance && !canvas && !ownedUrls.length) {
                    return Promise.resolve();
                }

                let timeoutId = 0;
                const graceful = Promise.resolve().then(function() {
                    if (instance && typeof instance.destroy === "function") {
                        return instance.destroy();
                    }
                }).then(function() { return true; });
                const deadline = new Promise(function(resolve) {
                    timeoutId = setTimeout(function() { resolve(false); }, destroyTimeoutMillis);
                });
                return Promise.race([graceful, deadline])
                    .catch(function() { return false; })
                    .finally(function() {
                        if (timeoutId) clearTimeout(timeoutId);
                        forceTerminate(instance);
                        if (canvas && canvas.parentElement) canvas.remove();
                        ownedUrls.forEach(function(url) {
                            try { URL.revokeObjectURL(url); } catch (_) {}
                        });
                    });
            }

            function copyFonts(fonts) {
                return (Array.isArray(fonts) ? fonts : [])
                    .filter(function(font) { return font && font.byteLength > 0; })
                    .map(function(font) { return new Uint8Array(font); });
            }

            function buildOptions(canvas, header, fonts, ownedUrls) {
                const availableFonts = {};
                Object.keys(settings.availableFonts || {}).forEach(function(family) {
                    availableFonts[family] = resolveUrl(settings.availableFonts[family]);
                });
                if (settings.fallbackFontUrl) {
                    const family = String(
                        settings.fallbackFontFamily || "liberation sans"
                    ).trim().toLowerCase();
                    availableFonts[family] = resolveUrl(settings.fallbackFontUrl);
                }
                const options = {
                    canvas: canvas,
                    subContent: header,
                    fonts: copyFonts(fonts).concat(
                        (settings.preloadFonts || []).map(resolveUrl)
                    ),
                    availableFonts: availableFonts,
                    queryFonts:
                        settings.queryFonts === "disabled"
                            ? false
                            : settings.queryFonts,
                    debug: Boolean(settings.debug),
                    timeOffset: -state.delay
                };
                const workerUrl = resolveWorkerUrl(settings.workerUrl, ownedUrls);
                if (workerUrl) options.workerUrl = workerUrl;
                const wasmUrl = resolveUrl(settings.wasmUrl);
                if (wasmUrl) options.wasmUrl = wasmUrl;
                const modernWasmUrl = resolveUrl(settings.modernWasmUrl);
                if (modernWasmUrl) options.modernWasmUrl = modernWasmUrl;
                if (settings.fallbackFontUrl) {
                    options.defaultFont = String(
                        settings.fallbackFontFamily || "liberation sans"
                    ).trim().toLowerCase();
                }
                return options;
            }

            function formatAssTime(value) {
                const centiseconds = Math.max(
                    0,
                    Math.round(Number.isFinite(value) ? value * 100 : 0)
                );
                const hours = Math.floor(centiseconds / 360000);
                const minutes = Math.floor((centiseconds % 360000) / 6000);
                const seconds = Math.floor((centiseconds % 6000) / 100);
                const fraction = centiseconds % 100;
                return String(hours) + ":" +
                    String(minutes).padStart(2, "0") + ":" +
                    String(seconds).padStart(2, "0") + "." +
                    String(fraction).padStart(2, "0");
            }

            function splitMatroskaAssPayload(payload) {
                const fields = [];
                let start = 0;
                for (let index = 0; index < 8; index += 1) {
                    const comma = payload.indexOf(",", start);
                    if (comma < 0) return null;
                    fields.push(payload.substring(start, comma));
                    start = comma + 1;
                }
                fields.push(payload.substring(start));
                return fields;
            }

            function packetToDialogue(packet) {
                if (!packet || !packet.data) return "";
                let payload;
                try {
                    payload = new TextDecoder("utf-8")
                        .decode(packet.data)
                        .replace(/\u0000+$/g, "")
                        .trim();
                } catch (_) {
                    return "";
                }
                if (!payload) return "";
                if (/^Dialogue\s*:/i.test(payload)) return payload + "\n";

                const fields = splitMatroskaAssPayload(payload);
                if (!fields) return "";
                const start = Number(packet.timestamp);
                const duration = Number(packet.duration);
                const end = Math.max(
                    Number.isFinite(start) ? start : 0,
                    (Number.isFinite(start) ? start : 0) +
                        (Number.isFinite(duration) && duration > 0 ? duration : 0.01)
                );
                const layerOrMarked =
                    state.codec.indexOf("ssa") !== -1 &&
                    fields[1].indexOf("Marked=") !== 0
                        ? "Marked=" + fields[1]
                        : fields[1];
                return "Dialogue: " +
                    [
                        layerOrMarked,
                        formatAssTime(start),
                        formatAssTime(end),
                        fields[2],
                        fields[3],
                        fields[4],
                        fields[5],
                        fields[6],
                        fields[7],
                        fields[8]
                    ].join(",") +
                    "\n";
            }

            const renderer = {
                supports: supports,

                mount: function(container) {
                    if (state.disposed || !container) return;
                    state.container = container;
                    ensureMounted();
                },

                configure: function(track, extradata, fonts) {
                    if (state.disposed) {
                        return Promise.reject(
                            new Error("The embedded subtitle renderer was destroyed.")
                        );
                    }
                    const generation = ++state.generation;
                    state.eventEpoch += 1;
                    state.packetQueue = Promise.resolve();
                    const task = state.queue
                        .catch(function() {})
                        .then(function() {
                            return teardownCurrent();
                        })
                        .then(function() {
                            if (state.disposed || generation !== state.generation) return;
                            state.codec = codecOf(track);
                            state.header = decodeHeader(extradata, state.codec);
                            const ownedUrls = [];
                            const canvas = createCanvas();
                            // Publish every owned resource before constructing JASSUB. Its
                            // constructor and option hardening are allowed to throw, and the
                            // common teardown path must still remove the canvas and revoke a
                            // cross-origin worker wrapper in that case.
                            state.canvas = canvas;
                            state.instanceWorkerUrls = ownedUrls;
                            const instance = createRenderer(
                                buildOptions(
                                    canvas,
                                    state.header,
                                    fonts,
                                    ownedUrls
                                )
                            );
                            state.instance = instance;
                            hardenRenderer(instance);
                            instance.timeOffset = -state.delay;
                            return Promise.resolve(instance.ready).then(function() {
                                if (
                                    state.disposed ||
                                    generation !== state.generation ||
                                    state.instance !== instance
                                ) {
                                    return teardownCurrent();
                                }
                                ensureMounted();
                            });
                        })
                        .catch(function(error) {
                            return teardownCurrent().then(function() {
                                throw error;
                            });
                        });
                    const reported = task.catch(function(error) {
                        report(error);
                        throw error;
                    });
                    state.queue = reported.catch(function() {});
                    return reported;
                },

                pushPacket: function(packet) {
                    const generation = state.generation;
                    const epoch = state.eventEpoch;
                    const dialogue = packetToDialogue(packet);
                    if (!dialogue) return;
                    const task = state.packetQueue
                        .catch(function() {})
                        .then(function() {
                            const instance = state.instance;
                            if (
                                state.disposed ||
                                generation !== state.generation ||
                                epoch !== state.eventEpoch ||
                                !instance
                            ) {
                                return;
                            }
                            return Promise.resolve(instance.ready).then(function() {
                                if (
                                    state.disposed ||
                                    generation !== state.generation ||
                                    epoch !== state.eventEpoch ||
                                    state.instance !== instance
                                ) {
                                    return;
                                }
                                return instance.renderer.processData(dialogue);
                            });
                        });
                    state.packetQueue = task.catch(report);
                    return task;
                },

                render: function(mediaTime, videoWidth, videoHeight) {
                    const instance = state.instance;
                    if (
                        state.disposed ||
                        !instance ||
                        !Number.isFinite(Number(mediaTime))
                    ) {
                        return;
                    }
                    ensureMounted();
                    const rect =
                        state.container && state.container.getBoundingClientRect
                            ? state.container.getBoundingClientRect()
                            : null;
                    const ratio = Number(globalThis.devicePixelRatio || 1);
                    const width = Math.max(
                        1,
                        Math.round(
                            Number(videoWidth) ||
                            Number(rect && rect.width || 0) * ratio
                        )
                    );
                    const height = Math.max(
                        1,
                        Math.round(
                            Number(videoHeight) ||
                            Number(rect && rect.height || 0) * ratio
                        )
                    );
                    const task = Promise.resolve(
                        instance.manualRender(
                            {
                                expectedDisplayTime: performance.now(),
                                width: width,
                                height: height,
                                mediaTime: Number(mediaTime)
                            },
                            false
                        )
                    );
                    task.catch(report);
                    return task;
                },

                setDelay: function(seconds) {
                    if (!Number.isFinite(Number(seconds))) return;
                    state.delay = Number(seconds);
                    if (state.instance) {
                        state.instance.timeOffset = -state.delay;
                    }
                },

                clear: function() {
                    const generation = state.generation;
                    const epoch = ++state.eventEpoch;
                    const task = state.packetQueue
                        .catch(function() {})
                        .then(function() {
                            const instance = state.instance;
                            if (
                                state.disposed ||
                                generation !== state.generation ||
                                epoch !== state.eventEpoch ||
                                !instance
                            ) {
                                return;
                            }
                            return Promise.resolve(instance.ready).then(function() {
                                if (
                                    state.disposed ||
                                    generation !== state.generation ||
                                    epoch !== state.eventEpoch ||
                                    state.instance !== instance
                                ) {
                                    return;
                                }
                                return instance.renderer.setTrack(state.header);
                            });
                        });
                    state.packetQueue = task.catch(report);
                    return task;
                },

                destroy: function() {
                    if (state.destroyPromise) return state.destroyPromise;
                    state.disposed = true;
                    state.generation += 1;
                    state.eventEpoch += 1;
                    // Do not wait for `instance.ready`: a broken worker may leave that
                    // promise pending forever. teardownCurrent atomically detaches the
                    // active resources and applies its own bounded destroy deadline.
                    state.destroyPromise = teardownCurrent()
                        .finally(function() {
                            if (state.container) {
                                state.container.innerHTML = "";
                                state.container.style.display = "none";
                            }
                            state.container = null;
                        });
                    return state.destroyPromise;
                }
            };
            return renderer;
        })()
        """,
    )
