package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPipelineExtensionStatusTest {
    @Test
    fun unavailableExtensionCannotContributeCapabilities() {
        val extension =
            object : VideoPipelineExtension {
                override val id = "unavailable"
                override val availability =
                    VideoPipelineExtensionAvailability.unavailable("Runtime missing.")
                override val colorConversionCapabilities =
                    ColorConversionCapabilities(
                        supportsDolbyVisionProfile7To8 = true,
                        supportsHdrToSdrSourceBridge = true,
                    )
            }

        val status = extension.status()

        assertFalse(status.availability.canContribute)
        assertFalse(status.colorConversionCapabilities.supportsDolbyVisionProfile7To8)
        assertFalse(status.colorConversionCapabilities.supportsHdrToSdrSourceBridge)
    }

    @Test
    fun degradedExtensionRemainsUsableAndExplainsWhy() {
        val availability = VideoPipelineExtensionAvailability.degraded("Metadata passthrough is unavailable.")

        assertTrue(availability.canContribute)
        assertEquals(VideoPipelineExtensionState.DEGRADED, availability.state)
    }

    @Test
    fun unavailableOrDegradedStateRequiresDetail() {
        assertFailsWith<IllegalArgumentException> {
            VideoPipelineExtensionAvailability(VideoPipelineExtensionState.UNAVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoPipelineExtensionAvailability(VideoPipelineExtensionState.DEGRADED, " ")
        }
    }
}
