package io.github.kdroidfilter.composemediaplayer.consumer

import io.github.kdroidfilter.composemediaplayer.CacheConfig
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaRuntimeSmokeTest {
    @Test
    fun consumerRunsOnJava25OrNewer() {
        assertTrue(
            Runtime.version().feature() >= 25,
            "The desktop artifact requires a Java 25 runtime.",
        )
    }

    @Test
    fun publishedLibraryLoadsAtRuntime() {
        assertFalse(CacheConfig().enabled)
    }

    @Test
    fun publishedAssExtensionReportsItsJvmRuntimeState() {
        val extension = AssSubtitleExtension()
        val status = extension.status()

        assertEquals("composemediaplayer-ass", extension.id)
        assertEquals(extension.id, status.id)
        assertEquals(extension.availability.canContribute, extension.supportedSubtitleFormats.isNotEmpty())
    }
}
