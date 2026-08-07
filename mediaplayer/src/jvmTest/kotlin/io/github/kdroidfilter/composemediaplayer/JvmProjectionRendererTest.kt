package io.github.kdroidfilter.composemediaplayer

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmProjectionRendererTest {
    @Test
    fun flatVideoWithoutTextureCropUsesPlainCanvasRenderer() {
        val projection = VideoProjectionSettings()
        val textureCrop = VideoTextureCrop()

        assertFalse(projection.usesJvmCanvasProjectionRenderer(textureCrop))
        assertEquals("Compose Canvas (Skia)", projection.jvmCanvasRendererLabel(textureCrop))
    }

    @Test
    fun flatVideoWithTextureCropUsesProjectionShader() {
        val projection = VideoProjectionSettings()
        val textureCrop = VideoTextureCrop(left = 0.1f)

        assertTrue(projection.usesJvmCanvasProjectionRenderer(textureCrop))
        assertEquals("Compose Canvas -> Skia projection shader", projection.jvmCanvasRendererLabel(textureCrop))
    }

    @Test
    fun rendererLabelPreservesExistingBackendPrefix() {
        val projection = VideoProjectionSettings()

        assertEquals(
            "libVLC vmem -> Compose Canvas -> Skia projection shader",
            projection.jvmCanvasRendererLabel(
                baseRenderer = "libVLC vmem -> Compose Canvas (Skia)",
                textureCrop = VideoTextureCrop(top = 0.1f),
            ),
        )
    }

    @Test
    fun projectionPaintReleasesEveryPerDrawNativeResource() {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(2, 2, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        }
        try {
            bitmap.makeShader().use { textureShader ->
                lateinit var capturedBuilder: RuntimeShaderBuilder
                lateinit var capturedProjectionShader: Shader
                lateinit var capturedPaint: Paint

                withJvmProjectionPaint(
                    textureShader = textureShader,
                    configure = { capturedBuilder = it },
                    draw = { paint, projectionShader ->
                        capturedPaint = paint
                        capturedProjectionShader = projectionShader
                        assertFalse(capturedBuilder.isClosed)
                        assertFalse(capturedProjectionShader.isClosed)
                        assertFalse(capturedPaint.isClosed)
                    },
                )

                assertTrue(capturedBuilder.isClosed)
                assertTrue(capturedProjectionShader.isClosed)
                assertTrue(capturedPaint.isClosed)
                assertFalse(textureShader.isClosed)
            }
        } finally {
            bitmap.close()
        }
    }
}
