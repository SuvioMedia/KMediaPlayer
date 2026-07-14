package io.github.kdroidfilter.composemediaplayer

import android.content.pm.PackageManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.kdroid.androidcontextprovider.ContextProvider

internal actual fun platformPlayerCapabilities(): PlayerCapabilities =
    PlayerCapabilities(
        supportsMkv = true,
        supportsPiP = isAndroidPictureInPictureSupported(),
        hdr = queryAndroidHdrCapabilities(),
        supportedUriSchemes = ANDROID_SUPPORTED_URI_SCHEMES,
    )

internal actual fun platformSupportsHls(): Boolean = true

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)

private val ANDROID_SUPPORTED_URI_SCHEMES =
    setOf("android.resource", "asset", "content", "data", "file", "http", "https", "rawresource")

private fun isAndroidPictureInPictureSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

    return runCatching {
        ContextProvider
            .getContext()
            .packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }.getOrDefault(false)
}

@Suppress("DEPRECATION")
private fun queryAndroidHdrCapabilities(): HdrCapabilities {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return HdrCapabilities()

    val display =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ContextProvider.getContext().display
            } else {
                val windowManager =
                    ContextProvider.getContext().getSystemService(WindowManager::class.java)
                windowManager.defaultDisplay
            }
        }.getOrNull() ?: return HdrCapabilities()

    val hdrTypes =
        display.hdrCapabilities
            ?.supportedHdrTypes
            ?.toSet()
            .orEmpty()
    val hasHdr = hdrTypes.isNotEmpty()

    fun formatSupport(type: Int): HdrSupport =
        if (type in hdrTypes) {
            HdrSupport.SUPPORTED
        } else {
            HdrSupport.UNSUPPORTED
        }

    return HdrCapabilities(
        hdr = if (hasHdr) HdrSupport.SUPPORTED else HdrSupport.UNSUPPORTED,
        hdr10 = formatSupport(Display.HdrCapabilities.HDR_TYPE_HDR10),
        hlg = formatSupport(Display.HdrCapabilities.HDR_TYPE_HLG),
        dolbyVision = formatSupport(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION),
        supportsNativeHdrPlayback = hasHdr,
        supportsToneMappingToSdr = false,
        maxExtendedDynamicRange = if (hasHdr) 2f else 1f,
    )
}
