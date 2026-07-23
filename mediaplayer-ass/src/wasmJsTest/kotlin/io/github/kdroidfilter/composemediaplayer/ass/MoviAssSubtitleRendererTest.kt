@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer.ass

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MoviAssSubtitleRendererTest {
    @Test
    fun supportsOnlyEmbeddedAssFamilyTextTracks() {
        val adapter = createAdapter(createFakeJassubCallbacks())

        assertTrue(adapterSupports(adapter, codec = "ass", subtitleType = "text"))
        assertTrue(adapterSupports(adapter, codec = "ssa", subtitleType = "text"))
        assertTrue(adapterSupports(adapter, codec = "s_text/ass", subtitleType = "text"))
        assertTrue(adapterSupports(adapter, codec = "s_text/ssa", subtitleType = "text"))
        assertFalse(adapterSupports(adapter, codec = "subrip", subtitleType = "text"))
        assertFalse(adapterSupports(adapter, codec = "ass", subtitleType = "image"))
    }

    @Test
    fun convertsMatroskaPacketsPassesFontsRendersClearsAndDestroysOnce() =
        runTest {
            val host = document.createElement("div") as HTMLElement
            document.body?.appendChild(host)
            val configured = CompletableDeferred<String>()
            val configurationCompleted = CompletableDeferred<Unit>()
            val processed = CompletableDeferred<String>()
            val rendered = CompletableDeferred<String>()
            val cleared = CompletableDeferred<String>()
            val destroyed = CompletableDeferred<Unit>()
            val terminated = CompletableDeferred<Unit>()
            val failure = CompletableDeferred<String>()
            val callbacks =
                createFakeJassubCallbacks(
                    onConfigured = configured::complete,
                    onProcessed = processed::complete,
                    onRendered = rendered::complete,
                    onCleared = cleared::complete,
                    onDestroyed = { destroyed.complete(Unit) },
                    onTerminated = { terminated.complete(Unit) },
                )
            val adapter = createAdapter(callbacks, failure::complete)

            try {
                configureAdapter(
                    adapter = adapter,
                    host = host,
                    onSuccess = { configurationCompleted.complete(Unit) },
                    onFailure = failure::complete,
                )

                val options = configured.awaitReal()
                configurationCompleted.awaitReal()
                assertContains(options, "[Events]")
                assertTrue(options.endsWith("|1"), "Expected one embedded font, got: $options")
                assertEquals(1, host.childElementCount)

                pushMatroskaAssPacket(adapter)
                assertEquals(
                    "Dialogue: 0,0:00:01.25,0:00:03.75,Default,Speaker,0,0,0,,Hello, world\n",
                    processed.awaitReal(),
                )

                setAdapterDelay(adapter, 1.5)
                assertEquals(-1.5, readFakeTimeOffset(callbacks))

                renderAdapter(adapter)
                assertEquals("2.5|1920|1080|false", rendered.awaitReal())

                clearAdapter(adapter)
                assertContains(cleared.awaitReal(), "[Events]")

                destroyAdapter(adapter)
                destroyAdapter(adapter)
                destroyed.awaitReal()
                terminated.awaitReal()
                assertEquals(0, host.childElementCount)
                assertFalse(failure.isCompleted)
            } finally {
                destroyAdapter(adapter)
                host.remove()
            }
        }

    @Test
    fun constructorFailureRemovesTheOwnedCanvasAndReportsTheError() =
        runTest {
            val host = document.createElement("div") as HTMLElement
            document.body?.appendChild(host)
            val rejected = CompletableDeferred<String>()
            val reported = CompletableDeferred<String>()
            val adapter =
                createMoviAssSubtitleRendererAdapter(
                    settings = createFakeSettings(),
                    createRenderer = {
                        throw IllegalStateException("fake JASSUB constructor failure")
                    },
                    hardenRenderer = {},
                    onError = reported::complete,
                    destroyTimeoutMillis = 100,
                )

            try {
                configureAdapter(
                    adapter = adapter,
                    host = host,
                    onSuccess = { rejected.complete("unexpected success") },
                    onFailure = rejected::complete,
                )

                assertContains(rejected.awaitReal(), "fake JASSUB constructor failure")
                assertContains(reported.awaitReal(), "fake JASSUB constructor failure")
                assertEquals(0, host.childElementCount)
            } finally {
                destroyAdapter(adapter)
                host.remove()
            }
        }

    @Test
    fun destroyDoesNotWaitForAStuckReadyOrWorkerDestroyPromise() =
        runTest {
            val host = document.createElement("div") as HTMLElement
            document.body?.appendChild(host)
            val configured = CompletableDeferred<String>()
            val terminated = CompletableDeferred<Unit>()
            val destroyCompleted = CompletableDeferred<Unit>()
            val failure = CompletableDeferred<String>()
            val callbacks =
                createFakeJassubCallbacks(
                    onConfigured = configured::complete,
                    onTerminated = { terminated.complete(Unit) },
                )
            val adapter =
                createMoviAssSubtitleRendererAdapter(
                    settings = createFakeSettings(),
                    createRenderer = { options -> createStuckFakeJassub(options, callbacks) },
                    hardenRenderer = {},
                    onError = failure::complete,
                    destroyTimeoutMillis = 25,
                )

            try {
                configureAdapter(
                    adapter = adapter,
                    host = host,
                    onSuccess = {},
                    onFailure = failure::complete,
                )
                configured.awaitReal()

                destroyAdapter(
                    adapter = adapter,
                    onSuccess = { destroyCompleted.complete(Unit) },
                    onFailure = failure::complete,
                )
                destroyCompleted.awaitReal()
                terminated.awaitReal()
                assertEquals(0, host.childElementCount)
                assertFalse(failure.isCompleted)
            } finally {
                destroyAdapter(adapter)
                host.remove()
            }
        }

    private fun createAdapter(
        callbacks: JsAny,
        onError: (String) -> Unit = {},
    ): JsAny =
        createMoviAssSubtitleRendererAdapter(
            settings = createFakeSettings(),
            createRenderer = { options -> createFakeJassub(options, callbacks) },
            hardenRenderer = {},
            onError = onError,
            destroyTimeoutMillis = 100,
        )

    private suspend fun CompletableDeferred<String>.awaitReal(): String =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) { await() }
        }

    private suspend fun CompletableDeferred<Unit>.awaitReal() {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) { await() }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun createFakeSettings(): JsAny =
    js(
        """
        ({
            workerUrl: null,
            wasmUrl: null,
            modernWasmUrl: null,
            fallbackFontUrl: null,
            fallbackFontFamily: "liberation sans",
            queryFonts: "disabled",
            debug: false,
            preloadFonts: [],
            availableFonts: {}
        })
        """,
    )

@Suppress("LongParameterList", "UNUSED_PARAMETER")
private fun createFakeJassubCallbacks(
    onConfigured: (String) -> Unit = {},
    onProcessed: (String) -> Unit = {},
    onRendered: (String) -> Unit = {},
    onCleared: (String) -> Unit = {},
    onDestroyed: () -> Unit = {},
    onTerminated: () -> Unit = {},
): JsAny =
    js(
        """
        ({
            onConfigured: onConfigured,
            onProcessed: onProcessed,
            onRendered: onRendered,
            onCleared: onCleared,
            onDestroyed: onDestroyed,
            onTerminated: onTerminated,
            instance: null
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun createFakeJassub(
    options: JsAny,
    callbacks: JsAny,
): JsAny =
    js(
        """
        (function() {
            const instance = {
                ready: Promise.resolve(),
                timeOffset: Number(options.timeOffset || 0),
                renderer: {
                    processData: function(data) {
                        callbacks.onProcessed(String(data));
                        return Promise.resolve();
                    },
                    setTrack: function(content) {
                        callbacks.onCleared(String(content));
                        return Promise.resolve();
                    }
                },
                manualRender: function(frame, repaint) {
                    callbacks.onRendered([
                        frame.mediaTime,
                        frame.width,
                        frame.height,
                        Boolean(repaint)
                    ].join("|"));
                    return Promise.resolve();
                },
                destroy: function() {
                    callbacks.onDestroyed();
                    return Promise.resolve();
                },
                _worker: {
                    terminate: function() {
                        callbacks.onTerminated();
                    }
                }
            };
            callbacks.instance = instance;
            callbacks.onConfigured(
                String(options.subContent || "") + "|" +
                String((options.fonts || []).length)
            );
            return instance;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun createStuckFakeJassub(
    options: JsAny,
    callbacks: JsAny,
): JsAny =
    js(
        """
        (function() {
            const instance = {
                ready: new Promise(function() {}),
                timeOffset: Number(options.timeOffset || 0),
                renderer: {
                    processData: function() { return Promise.resolve(); },
                    setTrack: function() { return Promise.resolve(); }
                },
                manualRender: function() { return Promise.resolve(); },
                destroy: function() { return new Promise(function() {}); },
                _worker: {
                    terminate: function() {
                        callbacks.onTerminated();
                    }
                }
            };
            callbacks.instance = instance;
            callbacks.onConfigured(
                String(options.subContent || "") + "|" +
                String((options.fonts || []).length)
            );
            return instance;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun adapterSupports(
    adapter: JsAny,
    codec: String,
    subtitleType: String,
): Boolean =
    js(
        """
        Boolean(adapter.supports({
            type: "subtitle",
            codec: codec,
            subtitleType: subtitleType
        }))
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun configureAdapter(
    adapter: JsAny,
    host: HTMLElement,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            const header =
                "[Script Info]\nScriptType: v4.00+\n\n" +
                "[V4+ Styles]\nFormat: Name, Fontname, Fontsize\n" +
                "Style: Default,Arial,24\n\n" +
                "[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, " +
                "MarginV, Effect, Text\n";
            adapter.mount(host);
            Promise.resolve(
                adapter.configure(
                    {
                        id: 3,
                        type: "subtitle",
                        codec: "ass",
                        subtitleType: "text"
                    },
                    new TextEncoder().encode(header),
                    [new Uint8Array([1, 2, 3, 4])]
                )
            ).then(onSuccess).catch(function(error) {
                onFailure(error && error.message ? error.message : String(error));
            });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun pushMatroskaAssPacket(adapter: JsAny): Unit =
    js(
        """
        {
            adapter.pushPacket({
                streamIndex: 3,
                timestamp: 1.25,
                duration: 2.5,
                data: new TextEncoder().encode(
                    "0,0,Default,Speaker,0,0,0,,Hello, world"
                )
            });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun setAdapterDelay(
    adapter: JsAny,
    seconds: Double,
): Unit = js("adapter.setDelay(seconds)")

@Suppress("UNUSED_PARAMETER")
private fun readFakeTimeOffset(callbacks: JsAny): Double = js("Number(callbacks.instance.timeOffset)")

@Suppress("UNUSED_PARAMETER")
private fun renderAdapter(adapter: JsAny): Unit = js("adapter.render(2.5, 1920, 1080)")

@Suppress("UNUSED_PARAMETER")
private fun clearAdapter(adapter: JsAny): Unit = js("adapter.clear()")

@Suppress("UNUSED_PARAMETER")
private fun destroyAdapter(adapter: JsAny): Unit =
    js(
        """
        {
            const result = adapter.destroy();
            if (result && typeof result.catch === "function") {
                result.catch(function() {});
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun destroyAdapter(
    adapter: JsAny,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(adapter.destroy()).then(onSuccess).catch(function(error) {
                onFailure(error && error.message ? error.message : String(error));
            });
        }
        """,
    )
