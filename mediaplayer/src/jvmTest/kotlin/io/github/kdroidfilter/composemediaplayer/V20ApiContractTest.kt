package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class V20ApiContractTest {
    @Test
    fun `playback options expose only 2_0 color policies`() {
        val options = VideoPlaybackOptions()

        assertEquals(DynamicRangePolicy.AUTO, options.dynamicRangePolicy)
        assertEquals(DolbyVisionPolicy.AUTO, options.dolbyVisionPolicy)
        assertFailsWith<ClassNotFoundException> {
            Class.forName("io.github.kdroidfilter.composemediaplayer.VideoOutputMode")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("io.github.kdroidfilter.composemediaplayer.DolbyVisionMode")
        }
    }

    @Test
    fun `player capabilities keep decoder display renderer and conversion separate`() {
        val capabilities = PlayerCapabilities()

        assertNotNull(capabilities.decoderColorCapabilities)
        assertNotNull(capabilities.displayColorCapabilities)
        assertNotNull(capabilities.rendererColorCapabilities)
        assertNotNull(capabilities.colorConversionCapabilities)
        assertNotNull(capabilities.rendererColorCapabilities.controlledOutputConversions)
        assertEquals(
            VideoDynamicRange.HLG,
            capabilities.rendererColorCapabilities.controlledOutputFor(VideoDynamicRange.HLG),
        )
    }

    @Test
    fun `player state publishes typed color pipeline state flow`() {
        assertEquals(
            StateFlow::class.java,
            VideoPlayerState::class.java.getDeclaredMethod("getColorPipelineStatus").returnType,
        )
    }
}
