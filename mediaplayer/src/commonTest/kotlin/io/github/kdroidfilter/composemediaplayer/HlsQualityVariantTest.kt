package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HlsQualityVariantTest {
    @Test
    fun acceptsPositiveOptionalMetrics() {
        val variant =
            HlsQualityVariant(
                id = "720p",
                label = "720p",
                width = 1280,
                height = 720,
                bitrate = 2_500_000,
            )

        assertEquals("720p", variant.id)
    }

    @Test
    fun rejectsBlankIdOrLabel() {
        assertFailsWith<IllegalArgumentException> {
            HlsQualityVariant(id = "", label = "720p")
        }
        assertFailsWith<IllegalArgumentException> {
            HlsQualityVariant(id = "720p", label = " ")
        }
    }

    @Test
    fun rejectsNonPositiveOptionalMetrics() {
        assertFailsWith<IllegalArgumentException> {
            HlsQualityVariant(id = "bad-width", label = "Bad", width = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HlsQualityVariant(id = "bad-height", label = "Bad", height = -720)
        }
        assertFailsWith<IllegalArgumentException> {
            HlsQualityVariant(id = "bad-bitrate", label = "Bad", bitrate = 0)
        }
    }
}
