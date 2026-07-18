package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAssSubtitleParserFactoryTest {
    private val factory = AndroidAssSubtitleParserFactory(nativeAssAvailable = { true })

    @Test
    fun `leaves raw Matroska SSA samples unparsed for the libass renderer`() {
        val format = rawMatroskaAssFormat()

        assertTrue(format.isRawMatroskaAss)
        assertFalse(factory.supportsFormat(format))
        assertEquals(
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE,
            factory.getCueReplacementBehavior(format),
        )
        assertFailsWith<IllegalArgumentException> { factory.create(format) }
    }

    @Test
    fun `keeps non Matroska SSA on the Media3 path`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()

        assertTrue(format.isAssSubtitle)
        assertFalse(format.isRawMatroskaAss)
        assertTrue(factory.supportsFormat(format))
        factory.create(format)
    }

    @Test
    fun `falls back to Media3 when the native backend is unavailable`() {
        val fallbackFactory = AndroidAssSubtitleParserFactory(nativeAssAvailable = { false })
        val format = rawMatroskaAssFormat()

        assertTrue(fallbackFactory.supportsFormat(format))
        fallbackFactory.create(format)
    }

    @Test
    fun `delegates supported non ASS formats to Media3`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()

        assertFalse(format.isAssSubtitle)
        assertTrue(factory.supportsFormat(format))
        factory.create(format)
    }

    private fun rawMatroskaAssFormat(): Format =
        Format
            .Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setInitializationData(
                listOf(
                    MATROSKA_DIALOGUE_FORMAT.encodeToByteArray(),
                    "[Script Info]\n[Events]".encodeToByteArray(),
                ),
            ).build()

    private companion object {
        const val MATROSKA_DIALOGUE_FORMAT =
            "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
    }
}
