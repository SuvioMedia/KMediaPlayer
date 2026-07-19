package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssSubtitleRendererConfigTest {
    @Test
    fun defaultsUseBundledAssetsAndDoNotQueryFonts() {
        val config = AssSubtitleRendererConfig()

        assertTrue(config.enabled)
        assertNull(config.workerUrl)
        assertNull(config.wasmUrl)
        assertNull(config.modernWasmUrl)
        assertNull(config.fallbackFontUrl)
        assertEquals(AssFontQueryMode.DISABLED, config.fontQueryMode)
        assertTrue(config.preloadFontUrls.isEmpty())
        assertTrue(config.availableFontUrls.isEmpty())
    }

    @Test
    fun blankOptionalUrlsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            AssSubtitleRendererConfig(workerUrl = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            AssSubtitleRendererConfig(preloadFontUrls = listOf(""))
        }
        assertFailsWith<IllegalArgumentException> {
            AssSubtitleRendererConfig(availableFontUrls = mapOf("Example" to ""))
        }
    }

    @Test
    fun availableFontFamiliesMustBeUniqueAfterRuntimeNormalization() {
        assertFailsWith<IllegalArgumentException> {
            AssSubtitleRendererConfig(
                availableFontUrls =
                    linkedMapOf(
                        "Example Sans" to "/fonts/regular.woff2",
                        " example sans " to "/fonts/medium.woff2",
                    ),
            )
        }
    }

    @Test
    fun mutableInputCollectionsAreDefensivelyCopied() {
        val preloadFonts = mutableListOf("/fonts/first.woff2", "/fonts/second.woff2")
        val availableFonts =
            mutableMapOf(
                "Example Sans" to "/fonts/first.woff2",
                "Other Sans" to "/fonts/second.woff2",
            )
        val config =
            AssSubtitleRendererConfig(
                preloadFontUrls = preloadFonts,
                availableFontUrls = availableFonts,
            )

        preloadFonts += "/fonts/third.woff2"
        availableFonts["Third Sans"] = "/fonts/third.woff2"

        assertEquals(listOf("/fonts/first.woff2", "/fonts/second.woff2"), config.preloadFontUrls)
        assertEquals(
            mapOf(
                "Example Sans" to "/fonts/first.woff2",
                "Other Sans" to "/fonts/second.woff2",
            ),
            config.availableFontUrls,
        )
        assertEquals(config, config.copy())
    }

    @Test
    fun returnedCollectionsCannotMutateConfigurationState() {
        val config =
            AssSubtitleRendererConfig(
                preloadFontUrls = listOf("/fonts/first.woff2", "/fonts/second.woff2"),
                availableFontUrls =
                    mapOf(
                        "Example Sans" to "/fonts/first.woff2",
                        "Other Sans" to "/fonts/second.woff2",
                    ),
            )
        val originalHashCode = config.hashCode()

        (config.preloadFontUrls as MutableList<String>) += "/fonts/injected.woff2"
        (config.availableFontUrls as MutableMap<String, String>)["Injected Sans"] =
            "/fonts/injected.woff2"

        assertEquals(listOf("/fonts/first.woff2", "/fonts/second.woff2"), config.preloadFontUrls)
        assertEquals(setOf("Example Sans", "Other Sans"), config.availableFontUrls.keys)
        assertEquals(originalHashCode, config.hashCode())
        assertEquals(config, config.copy())
    }

    @Test
    fun stringRepresentationDoesNotExposeResourceUrls() {
        val config =
            AssSubtitleRendererConfig(
                workerUrl = "https://example.invalid/worker.js?token=private",
                preloadFontUrls = listOf("https://example.invalid/font.woff2?token=private"),
                availableFontUrls =
                    mapOf(
                        "Example Sans" to "https://example.invalid/available.woff2?token=private",
                    ),
            )

        assertTrue("token=private" !in config.toString())
        assertTrue("workerUrlConfigured=true" in config.toString())
    }
}
