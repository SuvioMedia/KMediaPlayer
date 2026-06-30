package io.github.kdroidfilter.composemediaplayer

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
}
