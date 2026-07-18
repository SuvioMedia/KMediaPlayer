package io.github.kdroidfilter.composemediaplayer.subtitle

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
internal fun AndroidAssOverlay(
    controller: AndroidAssController,
    modifier: Modifier,
    cropToFill: Boolean,
    videoAspectRatio: Float,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AndroidAssTextureView(context, controller).also { view ->
                view.updateVideoGeometry(
                    cropToFill = cropToFill,
                    videoAspectRatio = videoAspectRatio,
                )
                view.attachOverlay()
            }
        },
        update = { view ->
            view.visibility = View.VISIBLE
            view.updateVideoGeometry(
                cropToFill = cropToFill,
                videoAspectRatio = videoAspectRatio,
            )
            view.attachOverlay()
        },
        onReset = { view ->
            view.visibility = View.INVISIBLE
            controller.detachOverlay(view)
        },
        onRelease = { view -> view.releaseOverlay() },
    )
}
