@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AssSubtitleRendererTest {
    @Test
    fun containGeometryCentersTheAuthoredVideoRect() {
        val rect =
            computeAssSubtitleOverlayRect(
                containerWidth = 1920.0,
                containerHeight = 1200.0,
                videoWidth = 1920.0,
                videoHeight = 1080.0,
                contentScaleMode = "contain",
                useWholeDisplayElement = false,
            )

        assertEquals(0.0, rect.x)
        assertEquals(60.0, rect.y)
        assertEquals(1920.0, rect.width)
        assertEquals(1080.0, rect.height)
    }

    @Test
    fun coverGeometryExtendsPastAndIsClippedByTheOverlayHost() {
        val rect =
            computeAssSubtitleOverlayRect(
                containerWidth = 1000.0,
                containerHeight = 1000.0,
                videoWidth = 1920.0,
                videoHeight = 1080.0,
                contentScaleMode = "cover",
                useWholeDisplayElement = false,
            )

        assertEquals(-388.8888888888889, rect.x, absoluteTolerance = 0.000001)
        assertEquals(0.0, rect.y)
        assertEquals(1777.7777777777778, rect.width, absoluteTolerance = 0.000001)
        assertEquals(1000.0, rect.height)
    }

    @Test
    fun controlledCanvasAndFillBoundsUseTheWholeDisplayElement() {
        val projectionRect =
            computeAssSubtitleOverlayRect(
                containerWidth = 1280.0,
                containerHeight = 720.0,
                videoWidth = 3840.0,
                videoHeight = 1920.0,
                contentScaleMode = "contain",
                useWholeDisplayElement = true,
            )
        val fillRect =
            computeAssSubtitleOverlayRect(
                containerWidth = 900.0,
                containerHeight = 600.0,
                videoWidth = 1920.0,
                videoHeight = 1080.0,
                contentScaleMode = "fill",
                useWholeDisplayElement = false,
            )

        assertEquals(AssSubtitleOverlayRect(0.0, 0.0, 1280.0, 720.0), projectionRect)
        assertEquals(AssSubtitleOverlayRect(0.0, 0.0, 900.0, 600.0), fillRect)
    }

    @Test
    fun streamUpdateAppendsOnlyTheNewOrderedEvents() {
        val previous = assScript(dialogues = listOf("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,One"))
        val added = "Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Two"
        val next =
            assScript(
                dialogues =
                    listOf(
                        "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,One",
                        added,
                    ),
            )

        assertEquals(
            "append\u0000$added\n",
            computeAssSubtitleStreamUpdate(previous, next),
        )
    }

    @Test
    fun streamUpdateRestartsForHeaderOrNonPrefixChanges() {
        val previous = assScript(dialogues = listOf("Dialogue: first"))
        val changedHeader = assScript(styleName = "Alternate", dialogues = listOf("Dialogue: first"))
        val replacedEvent = assScript(dialogues = listOf("Dialogue: replacement"))

        assertEquals(
            "restart",
            computeAssSubtitleStreamUpdate(previous, changedHeader),
        )
        assertEquals(
            "restart",
            computeAssSubtitleStreamUpdate(previous, replacedEvent),
        )
    }

    @Test
    fun rendererDimensionUsesDevicePixelRatioAndNeverReturnsZero() {
        assertEquals(1500, assSubtitleRenderDimension(cssPixels = 1000.0, devicePixelRatio = 1.5))
        assertEquals(1, assSubtitleRenderDimension(cssPixels = 0.0, devicePixelRatio = 2.0))
    }

    @Test
    fun disabledConfigurationHasDeterministicUnavailableReason() {
        assertTrue(assSubtitleRendererUnavailableReason(enabled = false).contains("disabled"))
    }

    @Test
    fun sessionQueuesStreamingFontsRepaintOffsetAndCleanup() =
        runTest {
            val wrapper = document.createElement("div") as HTMLElement
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val video = document.createElement("video") as HTMLVideoElement
            wrapper.appendChild(canvas)
            document.body?.appendChild(wrapper)
            document.body?.appendChild(video)
            stubElementRect(wrapper, width = 320.0, height = 180.0)
            stubElementRect(video, width = 320.0, height = 180.0)

            val originalResizeObserver = installNoopResizeObserver()
            var session: JsAny? = null
            try {
                val ready = CompletableDeferred<Unit>()
                val processed = CompletableDeferred<String>()
                val fontsAdded = CompletableDeferred<Int>()
                val destroyed = CompletableDeferred<Unit>()
                val terminated = CompletableDeferred<Unit>()
                val failure = CompletableDeferred<String>()
                var resizeCount = 0
                var expectedResize: CompletableDeferred<Int>? = null
                val previous = assScript(dialogues = listOf("Dialogue: first"))
                val added = "Dialogue: second"
                val next = assScript(dialogues = listOf("Dialogue: first", added))
                val instance =
                    createFakeRenderer(
                        onResize = { _, _ ->
                            resizeCount += 1
                            expectedResize?.complete(resizeCount)
                        },
                        onProcessData = { delta -> processed.complete(delta) },
                        onAddFonts = { count -> fontsAdded.complete(count) },
                        onDestroy = { destroyed.complete(Unit) },
                        onTerminate = { terminated.complete(Unit) },
                    )
                session =
                    configureJassubRendererSession(
                        instance = instance,
                        options = createFakeSessionOptions("initial", previous),
                        video = video,
                        canvas = canvas,
                        displayElement = video,
                        contentScaleMode = "contain",
                        layoutCalculator = { width, height, _, _, _, _ -> "0|0|$width|$height" },
                        streamUpdateCalculator = ::computeAssSubtitleStreamUpdate,
                        renderDimensionCalculator = ::assSubtitleRenderDimension,
                        onReady = { ready.complete(Unit) },
                        onError = { message -> failure.complete(message) },
                        onRestartRequired = { failure.complete("unexpected restart") },
                    )

                ready.awaitReal()
                assertEquals(1, resizeCount, "The ready callback must follow the initial resize.")

                val streamRepaint = CompletableDeferred<Int>()
                expectedResize = streamRepaint
                updateAssSubtitleRendererSource(session, subtitleDataUrl(next))
                assertEquals("$added\n", processed.awaitReal())
                assertEquals(2, streamRepaint.awaitReal(), "Streaming must schedule its own repaint.")
                expectedResize = null

                val offsetRepaint = CompletableDeferred<Int>()
                expectedResize = offsetRepaint
                updateAssSubtitleRendererOffset(session, 1.25)
                assertEquals(1.25, readFakeRendererTimeOffset(instance))
                assertEquals(3, offsetRepaint.awaitReal(), "An offset change must schedule its own repaint.")
                expectedResize = null

                val fontRepaint = CompletableDeferred<Int>()
                expectedResize = fontRepaint
                publishFakeMkvFont(video)
                assertEquals(1, fontsAdded.awaitReal())
                assertEquals(4, fontRepaint.awaitReal(), "Adding fonts must schedule its own repaint.")
                expectedResize = null

                disposeAssSubtitleRendererSession(session)
                destroyed.awaitReal()
                terminated.awaitReal()
                assertTrue(!failure.isCompleted)
            } finally {
                disposeAssSubtitleRendererSession(session)
                restoreResizeObserver(originalResizeObserver)
                wrapper.remove()
                video.remove()
            }
        }

    @Test
    fun canvasOnlySessionRendersFromTheSuppliedMoviClockAndCanvasSize() =
        runTest {
            val wrapper = document.createElement("div") as HTMLElement
            val moviCanvas =
                (document.createElement("canvas") as HTMLCanvasElement).apply {
                    width = 1_920
                    height = 1_080
                }
            val subtitleCanvas = document.createElement("canvas") as HTMLCanvasElement
            wrapper.appendChild(moviCanvas)
            wrapper.appendChild(subtitleCanvas)
            document.body?.appendChild(wrapper)
            stubElementRect(wrapper, width = 320.0, height = 180.0)
            stubElementRect(moviCanvas, width = 320.0, height = 180.0)

            val originalResizeObserver = installNoopResizeObserver()
            var session: JsAny? = null
            try {
                val rendered = CompletableDeferred<ManualRenderCall>()
                val fontsAdded = CompletableDeferred<Int>()
                val failure = CompletableDeferred<String>()
                val instance =
                    createFakeRenderer(
                        onResize = { _, _ -> },
                        onProcessData = {},
                        onAddFonts = { count -> fontsAdded.complete(count) },
                        onDestroy = {},
                        onTerminate = {},
                        onManualRender = { mediaTime, width, height, repaint ->
                            rendered.complete(
                                ManualRenderCall(
                                    mediaTime = mediaTime,
                                    width = width,
                                    height = height,
                                    repaint = repaint,
                                ),
                            )
                        },
                    )
                session =
                    configureJassubRendererSession(
                        instance = instance,
                        options = createFakeSessionOptions("initial", assScript(dialogues = emptyList())),
                        video = null,
                        canvas = subtitleCanvas,
                        displayElement = moviCanvas,
                        contentScaleMode = "contain",
                        layoutCalculator = { width, height, _, _, _, _ -> "0|0|$width|$height" },
                        streamUpdateCalculator = ::computeAssSubtitleStreamUpdate,
                        renderDimensionCalculator = ::assSubtitleRenderDimension,
                        onReady = {},
                        onError = { message -> failure.complete(message) },
                        onRestartRequired = { failure.complete("unexpected restart") },
                    )

                renderAssSubtitleFrame(
                    session = session,
                    mediaTimeSeconds = 12.5,
                    repaint = true,
                )

                assertEquals(
                    ManualRenderCall(mediaTime = 12.5, width = 1_920, height = 1_080, repaint = true),
                    rendered.awaitReal(),
                )
                publishFakeMkvFont(moviCanvas)
                assertEquals(1, fontsAdded.awaitReal())
                assertTrue(!failure.isCompleted)
            } finally {
                disposeAssSubtitleRendererSession(session)
                restoreResizeObserver(originalResizeObserver)
                wrapper.remove()
            }
        }

    @Test
    fun invalidCustomResourceUrlReportsFallbackInsteadOfEscaping() {
        val video = document.createElement("video") as HTMLVideoElement
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        var error: String? = null

        val session =
            createAssSubtitleRendererSession(
                video = video,
                canvas = canvas,
                displayElement = video,
                subUrl = subtitleDataUrl(assScript(dialogues = listOf("Dialogue: test"))),
                config = AssSubtitleRendererConfig(workerUrl = "http://["),
                timeOffsetSeconds = 0.0,
                contentScaleMode = "contain",
                onReady = {},
                onError = { message -> error = message },
                onRestartRequired = {},
            )

        assertNull(session)
        assertTrue(
            error.orEmpty().contains("invalid url", ignoreCase = true),
            "Expected custom URL validation to fail, but got: $error",
        )
    }

    @Test
    fun hangingDestroyIsBoundedByForcedWorkerTermination() =
        runTest {
            val wrapper = document.createElement("div") as HTMLElement
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val video = document.createElement("video") as HTMLVideoElement
            wrapper.appendChild(canvas)
            stubElementRect(wrapper, width = 320.0, height = 180.0)
            stubElementRect(video, width = 320.0, height = 180.0)
            val terminated = CompletableDeferred<Unit>()
            val instance =
                createFakeRenderer(
                    onResize = { _, _ -> },
                    onProcessData = {},
                    onAddFonts = {},
                    onDestroy = {},
                    onTerminate = { terminated.complete(Unit) },
                    destroyCompletes = false,
                )
            val session =
                configureJassubRendererSession(
                    instance = instance,
                    options = createFakeSessionOptions("initial", assScript(dialogues = emptyList())),
                    video = video,
                    canvas = canvas,
                    displayElement = video,
                    contentScaleMode = "contain",
                    layoutCalculator = { width, height, _, _, _, _ -> "0|0|$width|$height" },
                    streamUpdateCalculator = ::computeAssSubtitleStreamUpdate,
                    renderDimensionCalculator = ::assSubtitleRenderDimension,
                    onReady = {},
                    onError = {},
                    onRestartRequired = {},
                    destroyTimeoutMillis = 50,
                )

            disposeAssSubtitleRendererSession(session)

            terminated.awaitReal()
            wrapper.remove()
        }

    @Test
    fun unavailableLocalFontApiDoesNotBlockRemoteFallback() =
        runTest {
            val instance = createRejectingLocalFontRenderer()
            val completion = CompletableDeferred<Boolean>()
            hardenJassubLocalFontQuery(instance = instance, enabled = true, debug = false)

            invokeFakeLocalFontQuery(
                instance = instance,
                onSuccess = { returnedNoFont -> completion.complete(returnedNoFont) },
                onFailure = { completion.complete(false) },
            )

            assertTrue(completion.awaitReal())
        }

    @Test
    fun bundledJassubWorkerAndWasmCompleteARealRenderLifecycle() =
        runTest {
            assertEquals("", assSubtitleRendererUnavailableReason(enabled = true))
            val canvas =
                (document.createElement("canvas") as HTMLCanvasElement).apply {
                    width = 320
                    height = 180
                    style.width = "320px"
                    style.height = "180px"
                }
            document.body?.appendChild(canvas)
            val completion = CompletableDeferred<String?>()
            var instance: JsAny? = null
            try {
                val renderer =
                    createJassubRenderer(
                        createSmokeRendererOptions(
                            canvas = canvas,
                            subtitleContent =
                                assScript(
                                    dialogues =
                                        listOf(
                                            "Dialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,Smoke",
                                        ),
                                ),
                        ),
                    )
                instance = renderer
                exerciseSmokeRenderer(
                    instance = renderer,
                    onSuccess = { completion.complete(null) },
                    onFailure = { message -> completion.complete(message) },
                )

                val failure =
                    withContext(Dispatchers.Default.limitedParallelism(1)) {
                        withTimeout(30.seconds) { completion.await() }
                    }
                assertNull(failure, failure ?: "")
            } finally {
                forceCleanupSmokeRenderer(instance)
                canvas.remove()
            }
        }

    private fun assScript(
        styleName: String = "Default",
        dialogues: List<String>,
    ): String {
        val header =
            """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: $styleName,Arial,24

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """.trimIndent()
        return "$header\n${dialogues.joinToString("\n")}"
    }

    private suspend fun <T> CompletableDeferred<T>.awaitReal(): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) { await() }
        }
}

@Suppress("UNUSED_PARAMETER")
private fun stubElementRect(
    element: HTMLElement,
    width: Double,
    height: Double,
): Unit =
    js(
        """
        {
            element.getBoundingClientRect = function() {
                return {
                    x: 0,
                    y: 0,
                    left: 0,
                    top: 0,
                    right: width,
                    bottom: height,
                    width: width,
                    height: height,
                    toJSON: function() { return this; }
                };
            };
        }
        """,
    )

private fun installNoopResizeObserver(): JsAny =
    js(
        """
        (function() {
            const original = globalThis.ResizeObserver;
            globalThis.ResizeObserver = class {
                observe() {}
                unobserve() {}
                disconnect() {}
            };
            return original;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun restoreResizeObserver(original: JsAny): Unit =
    js(
        """
        {
            globalThis.ResizeObserver = original;
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun createFakeRenderer(
    onResize: (Int, Int) -> Unit,
    onProcessData: (String) -> Unit,
    onAddFonts: (Int) -> Unit,
    onDestroy: () -> Unit,
    onTerminate: () -> Unit,
    onManualRender: (Double, Int, Int, Boolean) -> Unit = { _, _, _, _ -> },
    destroyCompletes: Boolean = true,
): JsAny =
    js(
        """
        ({
            ready: Promise.resolve(),
            timeOffset: 0,
            resize: function(force, width, height) {
                onResize(width, height);
                return Promise.resolve();
            },
            renderer: {
                processData: function(data) {
                    onProcessData(data);
                    return Promise.resolve();
                },
                addFonts: function(fonts) {
                    onAddFonts(fonts.length);
                    return Promise.resolve(true);
                }
            },
            manualRender: function(frame, repaint) {
                onManualRender(frame.mediaTime, frame.width, frame.height, Boolean(repaint));
                return Promise.resolve();
            },
            destroy: function() {
                onDestroy();
                return destroyCompletes ? Promise.resolve() : new Promise(function() {});
            },
            _worker: {
                terminate: function() {
                    onTerminate();
                }
            }
        })
        """,
    )

private data class ManualRenderCall(
    val mediaTime: Double,
    val width: Int,
    val height: Int,
    val repaint: Boolean,
)

private fun createRejectingLocalFontRenderer(): JsAny =
    js(
        """
        ({
            _getLocalFont: function() {
                return Promise.reject(new Error("Local Font Access is unavailable"));
            }
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun invokeFakeLocalFontQuery(
    instance: JsAny,
    onSuccess: (Boolean) -> Unit,
    onFailure: () -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(instance._getLocalFont("example sans", "regular")).then(
                function(value) { onSuccess(value === undefined); },
                function() { onFailure(); }
            );
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun createFakeSessionOptions(
    source: String,
    content: String,
): JsAny =
    js(
        """
        ({
            __composeMediaPlayerOriginalSource: source,
            __composeMediaPlayerSubContent: content,
            __composeMediaPlayerInitialFonts: [],
            __composeMediaPlayerWorkerUrls: []
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun subtitleDataUrl(content: String): String =
    js("\"data:text/plain;charset=utf-8,\" + encodeURIComponent(content)")

@Suppress("UNUSED_PARAMETER")
private fun readFakeRendererTimeOffset(instance: JsAny): Double = js("Number(instance.timeOffset)")

@Suppress("UNUSED_PARAMETER")
private fun publishFakeMkvFont(video: HTMLElement): Unit =
    js(
        """
        {
            video.__composeMediaPlayerMkvFontFiles = [new Uint8Array([0, 1, 2, 3])];
            video.dispatchEvent(new Event("composemediaplayer:mkv-fonts-changed"));
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun createSmokeRendererOptions(
    canvas: HTMLCanvasElement,
    subtitleContent: String,
): JsAny =
    js(
        """
        ({
            canvas: canvas,
            subContent: subtitleContent,
            queryFonts: false
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun exerciseSmokeRenderer(
    instance: JsAny,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(instance.ready)
                .then(function() {
                    return instance.manualRender(
                        {
                            expectedDisplayTime: performance.now(),
                            width: 320,
                            height: 180,
                            mediaTime: 1
                        },
                        true
                    );
                })
                .then(function() {
                    return instance.destroy();
                })
                .then(onSuccess)
                .catch(function(error) {
                    try {
                        if (instance && instance._worker && typeof instance._worker.terminate === "function") {
                            instance._worker.terminate();
                        }
                    } catch (_) {}
                    onFailure(error && error.message ? error.message : String(error));
                });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun forceCleanupSmokeRenderer(instance: JsAny?): Unit =
    js(
        """
        {
            if (instance) {
                try {
                    if (!instance._destroyed && typeof instance.destroy === "function") {
                        const result = instance.destroy();
                        if (result && typeof result.catch === "function") {
                            result.catch(function() {});
                        }
                    }
                } catch (_) {}
                try {
                    if (instance._worker && typeof instance._worker.terminate === "function") {
                        instance._worker.terminate();
                    }
                } catch (_) {}
            }
        }
        """,
    )
