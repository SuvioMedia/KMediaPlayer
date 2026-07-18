package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VideoColorPipelineControllerTest {
    @Test
    fun `strict policy rejects an unknown source signal`() {
        val controller =
            VideoColorPipelineController(
                playbackOptions = VideoPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR),
            )

        val plan = controller.updateSource(VideoColorInfo())

        assertEquals(ColorPipelineRoute.UNSUPPORTED, plan?.route)
        assertEquals(ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN, plan?.fallbackReason)
        assertNotNull(controller.pipelineErrorOrNull())
    }

    @Test
    fun `automatic policy keeps unknown browser managed output unconfirmed`() {
        val controller = VideoColorPipelineController(playbackOptions = VideoPlaybackOptions())

        val plan = controller.updateSource(VideoColorInfo())

        assertNull(plan)
        assertNull(controller.pipelineErrorOrNull())
        assertEquals(VideoDynamicRange.UNKNOWN, controller.status.value.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN, controller.status.value.fallbackReason)
    }
}
