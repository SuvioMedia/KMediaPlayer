@file:Suppress("UnstableApiUsage")

package io.github.kdroidfilter.composemediaplayer

import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AndroidMediaThumbnailDeviceTest {
    @Test
    fun isolatedFrameExtractorProducesCompressedFrames() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val mediaFile = context.cacheDir.resolve("thumbnail-device-test.mp4")
            mediaFile.writeBytes(Base64.decode(TEST_VIDEO_BASE64, Base64.DEFAULT))
            try {
                val thumbnails = mutableListOf<AndroidMediaThumbnail?>()
                withTimeout(30.seconds) {
                    generateAndroidMediaThumbnails(
                        context = context,
                        mediaItem = MediaItem.fromUri(Uri.fromFile(mediaFile)),
                        mediaSourceFactory = DefaultMediaSourceFactory(context),
                        positions = listOf(250.milliseconds, 1.seconds),
                        maximumWidth = 80,
                    ) { index, thumbnail ->
                        assertEquals(thumbnails.size, index)
                        thumbnails += thumbnail
                    }
                }

                assertEquals(2, thumbnails.size)
                thumbnails.forEach { thumbnail ->
                    val generated = checkNotNull(thumbnail)
                    assertEquals("image/jpeg", generated.mimeType)
                    assertTrue(generated.width in 1..80)
                    assertTrue(generated.height in 1..160)
                    assertTrue(generated.bytes.size >= 4)
                    assertEquals(0xff.toByte(), generated.bytes[0])
                    assertEquals(0xd8.toByte(), generated.bytes[1])
                }
            } finally {
                mediaFile.delete()
            }
        }
}

private val TEST_VIDEO_BASE64 =
    """
    AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMvbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAABdwAAQAAAQAA
    AAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAA
    Alp0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAABdwAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAA
    AAAAAAAAAAAAAABAAAAAAEAAAAAkAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAXcAAAAAAABAAAAAAHSbWRpYQAAACBtZGhk
    AAAAAAAAAAAAAAAAAABAAAAAYABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABfW1p
    bmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAT1zdGJsAAAAuXN0c2QA
    AAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAJABIAAAASAAAAAAAAAABFExhdmM2My4xLjEwMSBsaWJ4
    MjY0AAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAF2dCwAraEf58BEAAAAMAQAAAAwEDxImoAQAFaM4BNyAAAAAQcGFzcAAA
    AAEAAAABAAAAFGJ0cnQAAAAAAAAZ6gAAAAAAAAAYc3R0cwAAAAAAAAABAAAAAwAAIAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxz
    dHNjAAAAAAAAAAEAAAABAAAAAwAAAAEAAAAgc3RzegAAAAAAAAAAAAAAAwAAA5QAAACjAAAApQAAABRzdGNvAAAAAAAAAAEAAANf
    AAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAAHGRh
    dGEAAAABAAAAAExhdmY2My4xLjEwMQAAAAhmcmVlAAAE5G1kYXQAAAJTBgX//0/cRem95tlIt5Ys2CDZI+7veDI2NCAtIGNvcmUg
    MTY1IHIzMjIyIGIzNTYwNWEgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDI1IC0gaHR0cDovL3d3
    dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MCByZWY9MSBkZWJsb2NrPTA6MDowIGFuYWx5c2U9MDow
    IG1lPWRpYSBzdWJtZT0wIHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4ZWRfcmVmPTAgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEg
    dHJlbGxpcz0wIDh4OGRjdD0wIGNxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PTAgdGhy
    ZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJs
    dXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTI1MCBrZXlpbnRfbWlu
    PTIgc2NlbmVjdXQ9MCBpbnRyYV9yZWZyZXNoPTAgcmM9Y3JmIG1idHJlZT0wIGNyZj00NS4wIHFjb21wPTAuNjAgcXBtaW49MCBx
    cG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTAAgAAAATlliIQ6DGBALYA+spqEzzMpiZk0eZnK6VSFcU2UHddkVzJY
    qLDut+ECERMoYwKAm2k/cDQMYaiskgITZSQH1cqsGyAvgb1X1Pl6JmDQo02EczjsD/iyhNJTyF2sodMkmJGMD3As9OyuXwdHD735
    vnOzf7tYDGAEZr84BuYEv59xG/vuKcX8+4+Y7D07oPRO8dlkZ1DssmOw8r0id47LKjwqAwBoQXAPoVjsBpwTyQbhQcs3zg5kLN1M
    lRWXRhfAF28jUaOyUrCD5k+ZUula6VGi5lVY0KmZAg1rqlzKmUgQbMgQYk4VkGs1w9Mx2cb5xaIrIyM1I0+IccvC5pRFZBhweA2F
    1/WvxoYhHcuTiyWXGWuLLhVrgvdxVOv77jSTrY3rBICgsfzXe4xX8diEH/1Vz1VzT08z+mf2AAAAn0GaICK8bHM+Rl87jISu6y9F
    HJ16K701M7VYvSQgFXUKRIAYbdmYOTbGj0iz+EAR1yQJeFxBfxsVsk90n4JvvHG46ak7kNr1uY2ysg63IdxmvabnqOy2v97YjBAF
    gt4+77wbBxxk+GwIiJB34UrGVKR0dunukpV0lWZOQQ0qXtcgcgMHL0mqxek5XwQ3vlwhPkTfB++D99j3YurX8W5/2AAAAKFBmkAu
    vBPYOMhbUBboE0MYhSAG3wXyYzMwY2pOTJlAQ+s//+nn/D2JIogtEFK0QaEPCQtCNTjUBxDQPFiOkOcE/8/f28EFpoZC2BIWaWH+
    3awNDAgLgixK8MC3DCOpsIGBqnyaD3L7amktKUAYRaIzvxa0rqilaQ/OK40O+F+epXmq1//hafG7du3Fwd8cucL6xfWLHuRS/Wq1
    x7nXX2Ox3A==
    """.trimIndent()
