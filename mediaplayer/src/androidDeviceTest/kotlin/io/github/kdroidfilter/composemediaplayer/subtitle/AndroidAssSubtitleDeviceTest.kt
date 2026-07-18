package io.github.kdroidfilter.composemediaplayer.subtitle

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AndroidAssSubtitleDeviceTest {
    @Test
    fun loadsAndParsesAssFromAnAppOwnedFile() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val subtitleFile = File(context.cacheDir, "composemediaplayer-test.ass")
            subtitleFile.writeText(ASS_CONTENT)

            val loaded = loadSubtitleContent(Uri.fromFile(subtitleFile).toString())
            assertEquals(ASS_CONTENT, loaded)

            val cues = AssParser.parse(loaded).cues
            assertEquals(2, cues.size)
            assertEquals(0L, cues.first().startTime)
            assertEquals(60_000L, cues.first().endTime)
        }

    private companion object {
        val ASS_CONTENT =
            """
            [Script Info]
            Title: Compose Media Player ASS sample
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,44

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,ASS subtitles rendered on Android.
            Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,Second active line.
            """.trimIndent()
    }
}
