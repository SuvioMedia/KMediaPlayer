package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DelegatingVideoPlayerStateTest {
    @Test
    fun nestedDecoratorsResolveToTheSurfaceOwner() {
        val owner = PreviewableVideoPlayerState()
        val decorated = TestPlayerStateDecorator(TestPlayerStateDecorator(owner))

        assertSame(owner, decorated.unwrapDelegatingState())
    }

    @Test
    fun selfDelegationIsRejected() {
        val decorated = SelfDelegatingVideoPlayerState()

        assertFailsWith<IllegalArgumentException> { decorated.unwrapDelegatingState() }
    }
}

private class TestPlayerStateDecorator(
    override val delegateState: VideoPlayerState,
) : DelegatingVideoPlayerState,
    VideoPlayerState by delegateState

private class SelfDelegatingVideoPlayerState(
    private val preview: VideoPlayerState = PreviewableVideoPlayerState(),
) : DelegatingVideoPlayerState,
    VideoPlayerState by preview {
    override val delegateState: VideoPlayerState
        get() = this
}
