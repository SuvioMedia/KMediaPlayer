package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration

class AndroidMediaAdvancedControlsTest {
    @Test
    fun capabilityResolvesThroughDecorators() {
        val owner = AndroidAdvancedTestPlayerState()
        val decorated = AndroidAdvancedTestPlayerDecorator(AndroidAdvancedTestPlayerDecorator(owner))

        assertSame(owner, decorated.androidMediaAdvancedControls)
    }

    @Test
    fun unsupportedStateHasNoCapability() {
        assertNull(PreviewableVideoPlayerState().androidMediaAdvancedControls)
    }
}

private class AndroidAdvancedTestPlayerState(
    private val preview: VideoPlayerState = PreviewableVideoPlayerState(),
) : VideoPlayerState by preview,
    AndroidMediaAdvancedControls {
    override suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int,
        emit: suspend (index: Int, thumbnail: AndroidMediaThumbnail?) -> Unit,
    ) = Unit
}

private class AndroidAdvancedTestPlayerDecorator(
    override val delegateState: VideoPlayerState,
) : DelegatingVideoPlayerState,
    VideoPlayerState by delegateState
