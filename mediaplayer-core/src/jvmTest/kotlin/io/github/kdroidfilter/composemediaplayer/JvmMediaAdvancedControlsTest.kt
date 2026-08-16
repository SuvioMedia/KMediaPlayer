package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration

class JvmMediaAdvancedControlsTest {
    @Test
    fun capabilityResolvesThroughDecorators() {
        val owner = AdvancedTestPlayerState()
        val decorated = AdvancedTestPlayerDecorator(AdvancedTestPlayerDecorator(owner))

        assertSame(owner, decorated.jvmMediaAdvancedControls)
    }

    @Test
    fun unsupportedStateHasNoCapability() {
        assertNull(PreviewableVideoPlayerState().jvmMediaAdvancedControls)
    }
}

private class AdvancedTestPlayerState(
    private val preview: VideoPlayerState = PreviewableVideoPlayerState(),
) : VideoPlayerState by preview,
    JvmMediaAdvancedControls {
    override suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int,
        emit: suspend (index: Int, thumbnail: JvmMediaThumbnail?) -> Unit,
    ) = Unit
}

private class AdvancedTestPlayerDecorator(
    override val delegateState: VideoPlayerState,
) : DelegatingVideoPlayerState,
    VideoPlayerState by delegateState
