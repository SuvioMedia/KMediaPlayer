package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Tests for the JVM implementation of VideoPlayerSurface
 *
 * Rendering itself is covered by platform integration tests; this verifies the public compile-time
 * contract without constructing a native player.
 */
class VideoPlayerSurfaceTest {
    /** The strict overload is additive while the original 1.x function type stays available. */
    @Test
    fun videoPlayerSurfaceSupportsStrictAndLegacyStateTypes() {
        val strictSurface:
            @Composable (
                RenderableVideoPlayerState,
                Modifier,
                ContentScale,
                @Composable () -> Unit,
            ) -> Unit = ::VideoPlayerSurface
        val legacySurface:
            @Composable (
                VideoPlayerState,
                Modifier,
                ContentScale,
                @Composable () -> Unit,
            ) -> Unit = ::VideoPlayerSurface

        assertNotNull(strictSurface)
        assertNotNull(legacySurface)
    }

    @Test
    fun eventingStateResolvesToItsNativeSurfaceOwner() {
        val platformState = PreviewableVideoPlayerState()
        val wrappedState = EventingVideoPlayerState(EventingVideoPlayerState(platformState))

        assertSame(platformState, wrappedState.resolveJvmSurfaceState())

        wrappedState.dispose()
    }
}
