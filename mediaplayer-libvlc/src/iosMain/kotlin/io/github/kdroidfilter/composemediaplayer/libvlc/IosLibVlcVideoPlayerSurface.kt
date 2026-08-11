@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitView
import platform.UIKit.UIColor
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

@Composable
internal fun IosLibVlcVideoPlayerSurface(
    playerState: IosLibVlcVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
) {
    val frame by playerState.currentFrame
    val projection = playerState.projection
    val textureCrop = playerState.projectionTextureCrop
    val projectionUnsupported = playerState.projectionUnsupported

    LaunchedEffect(playerState, projection, textureCrop) {
        playerState.updateRenderingRoute()
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (!projectionUnsupported) {
            UIKitView(
                factory = {
                    UIImageView().apply {
                        backgroundColor = UIColor.blackColor
                        clipsToBounds = true
                        contentMode = contentScale.toUiContentMode()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { imageView ->
                    imageView.image = frame
                    imageView.contentMode = contentScale.toUiContentMode()
                },
                onRelease = { imageView ->
                    imageView.image = null
                },
            )
        }
        overlay()
    }
}

private fun ContentScale.toUiContentMode(): UIViewContentMode =
    when (this) {
        ContentScale.Crop,
        ContentScale.FillHeight,
        ContentScale.FillWidth,
        -> UIViewContentMode.UIViewContentModeScaleAspectFill
        ContentScale.FillBounds -> UIViewContentMode.UIViewContentModeScaleToFill
        else -> UIViewContentMode.UIViewContentModeScaleAspectFit
    }
