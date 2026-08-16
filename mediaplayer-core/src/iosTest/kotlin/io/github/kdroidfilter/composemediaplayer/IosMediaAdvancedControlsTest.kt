package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration

class IosMediaAdvancedControlsTest {
    @Test
    fun capabilityResolvesThroughDecorators() {
        val owner = IosAdvancedTestPlayerState()
        val decorated = IosAdvancedTestPlayerDecorator(IosAdvancedTestPlayerDecorator(owner))

        assertSame(owner, decorated.iosMediaAdvancedControls)
    }

    @Test
    fun unsupportedStateHasNoCapability() {
        assertNull(PreviewableVideoPlayerState().iosMediaAdvancedControls)
    }
}

private class IosAdvancedTestPlayerState(
    private val preview: VideoPlayerState = PreviewableVideoPlayerState(),
) : VideoPlayerState by preview,
    IosMediaAdvancedControls {
    override suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int,
        emit: suspend (index: Int, thumbnail: IosMediaThumbnail?) -> Unit,
    ) = Unit
}

private class IosAdvancedTestPlayerDecorator(
    override val delegateState: VideoPlayerState,
) : DelegatingVideoPlayerState,
    VideoPlayerState by delegateState
