package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSurfaceTypeTest {
    @Test
    fun `auto uses SurfaceView for flat video`() {
        assertEquals(
            SurfaceType.SurfaceView,
            SurfaceType.Auto.resolveFor(VideoProjectionSettings(), VideoTextureCrop()),
        )
    }

    @Test
    fun `TextureView remains an explicit HDR opt out`() {
        assertEquals(
            SurfaceType.TextureView,
            SurfaceType.TextureView.resolveFor(VideoProjectionSettings(), VideoTextureCrop()),
        )
    }

    @Test
    fun `auto sends every projection through the controlled color renderer`() {
        assertEquals(
            SurfaceType.ProjectedGlSurfaceView,
            SurfaceType.Auto.resolveFor(
                VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
                VideoTextureCrop(),
            ),
        )
    }
}
