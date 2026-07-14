package io.github.kdroidfilter.composemediaplayer

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Guards the Android-only surface descriptor published by v1.0.15. */
class V1015AndroidBinaryCompatibilityTest {
    @Test
    fun surfaceTypeOverloadKeepsItsV1015VideoPlayerStateDescriptor() {
        val surfaceFacade = Class.forName("io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface_androidKt")
        assertNotNull(
            surfaceFacade.getDeclaredMethod(
                "VideoPlayerSurface",
                VideoPlayerState::class.java,
                Modifier::class.java,
                ContentScale::class.java,
                SurfaceType::class.java,
                Function2::class.java,
                Composer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
        )
        assertNotNull(
            surfaceFacade.getDeclaredMethod(
                "VideoPlayerSurface",
                RenderableVideoPlayerState::class.java,
                Modifier::class.java,
                ContentScale::class.java,
                SurfaceType::class.java,
                Function2::class.java,
                Composer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
        )
    }

    @Suppress("UnusedPrivateMember")
    @OptIn(UnstableApi::class)
    @Composable
    private fun surfaceTypeOverloadsRemainUnambiguous(
        legacyState: VideoPlayerState,
        renderableState: RenderableVideoPlayerState,
    ) {
        VideoPlayerSurface(playerState = legacyState, surfaceType = SurfaceType.Auto)
        VideoPlayerSurface(playerState = renderableState, surfaceType = SurfaceType.Auto)
    }
}
