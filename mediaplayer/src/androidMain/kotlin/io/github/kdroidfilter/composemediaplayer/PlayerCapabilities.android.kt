package io.github.kdroidfilter.composemediaplayer

import android.content.pm.PackageManager
import android.os.Build
import com.kdroid.androidcontextprovider.ContextProvider

internal actual fun platformPlayerCapabilities(): PlayerCapabilities =
    PlayerCapabilities(
        supportsMkv = true,
        supportsPiP = isAndroidPictureInPictureSupported(),
    )

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)

private fun isAndroidPictureInPictureSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

    return runCatching {
        ContextProvider
            .getContext()
            .packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }.getOrDefault(false)
}
