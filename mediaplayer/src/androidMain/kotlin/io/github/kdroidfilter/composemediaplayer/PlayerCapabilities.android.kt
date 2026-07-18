package io.github.kdroidfilter.composemediaplayer

import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.kdroid.androidcontextprovider.ContextProvider

internal actual fun platformPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities =
    if (currentAndroidRuntimeIsSupported()) {
        PlayerCapabilities(
            supportsMkv = true,
            supportsHls = true,
            supportsPiP = isAndroidPictureInPictureSupported(),
            displayColorCapabilities = queryAndroidDisplayColorCapabilities(),
            rendererColorCapabilities = queryAndroidRendererColorCapabilities(),
            supportedUriSchemes = ANDROID_SUPPORTED_URI_SCHEMES,
        )
    } else {
        PlayerCapabilities()
    }

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
internal fun queryAndroidDisplayColorCapabilities(targetDisplay: Display? = null): DisplayColorCapabilities {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return DisplayColorCapabilities(isKnown = true)

    val context = ContextProvider.getContext()
    val display =
        targetDisplay ?: runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                val windowManager = context.getSystemService(WindowManager::class.java)
                windowManager.defaultDisplay
            }
        }.getOrNull()
            ?: context
                .getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayColorCapabilities()

    val hdrCapabilities = display.hdrCapabilities ?: return DisplayColorCapabilities(isKnown = true)
    val hdrTypes = hdrCapabilities.supportedHdrTypes.toSet()
    val dynamicRanges =
        buildSet {
            add(VideoDynamicRange.SDR)
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) add(VideoDynamicRange.HDR10)
            if (Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes) add(VideoDynamicRange.HLG)
            if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) add(VideoDynamicRange.DOLBY_VISION)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes
            ) {
                add(VideoDynamicRange.HDR10_PLUS)
            }
            addAll(verifiedUnreportedAndroidDisplayDynamicRanges(currentAndroidDeviceColorIdentity()))
        }
    return DisplayColorCapabilities(
        isKnown = true,
        supportedDynamicRanges = dynamicRanges,
        minLuminanceNits = hdrCapabilities.desiredMinLuminance.takeIf { it.isFinite() && it >= 0f },
        maxLuminanceNits = hdrCapabilities.desiredMaxLuminance.takeIf { it.isFinite() && it > 0f },
    )
}

internal data class AndroidDeviceColorIdentity(
    val manufacturer: String,
    val brand: String,
    val product: String,
    val device: String,
)

private fun currentAndroidDeviceColorIdentity(): AndroidDeviceColorIdentity =
    AndroidDeviceColorIdentity(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        product = Build.PRODUCT,
        device = Build.DEVICE,
    )

/**
 * Corrections backed by the project's supported-device policy when vendor firmware under-reports
 * the panel through [Display.HdrCapabilities]. Philips Android TVs supported by KMediaPlayer are
 * required to support HDR10+, so Philips firmware gets that capability before playback.
 * Codec presence by itself must never add a display capability.
 */
internal fun verifiedUnreportedAndroidDisplayDynamicRanges(
    identity: AndroidDeviceColorIdentity,
): Set<VideoDynamicRange> =
    if (identity.brand.equals(PHILIPS_BRAND, ignoreCase = true)) {
        setOf(VideoDynamicRange.HDR10_PLUS)
    } else {
        emptySet()
    }

internal fun queryAndroidRendererColorCapabilities(
    display: DisplayColorCapabilities = queryAndroidDisplayColorCapabilities(),
    activeSurfaceType: SurfaceType? = null,
    projectionRuntime: AndroidHdrProjectionRuntimeSupport = AndroidHdrProjectionRuntimeProbe.get(),
): RendererColorCapabilities {
    val includesNativeSurface = activeSurfaceType == null || activeSurfaceType == SurfaceType.SurfaceView
    val includesControlledRenderer =
        activeSurfaceType == null || activeSurfaceType == SurfaceType.ProjectedGlSurfaceView
    val controlledHdrDynamicRanges =
        if (includesControlledRenderer) {
            projectionRuntime.controlledOutputRanges(display)
        } else {
            emptySet()
        }
    return RendererColorCapabilities(
        nativeSurfaceDynamicRanges =
            if (includesNativeSurface) display.supportedDynamicRanges - VideoDynamicRange.SDR else emptySet(),
        controlledHdrDynamicRanges = controlledHdrDynamicRanges,
        controlledOutputConversions =
            if (includesControlledRenderer && projectionRuntime.supportsPqOutput) {
                mapOf(VideoDynamicRange.HLG to VideoDynamicRange.HDR10)
            } else {
                emptyMap()
            },
        supportsToneMappingToSdr = includesControlledRenderer && projectionRuntime.supportsHdrInput,
        supportsHdrProjection = controlledHdrDynamicRanges.isNotEmpty(),
        supportsHdr10PlusPassthrough =
            includesNativeSurface && VideoDynamicRange.HDR10_PLUS in display.supportedDynamicRanges,
        supportsHdr10PlusApplication =
            includesControlledRenderer &&
                projectionRuntime.supportsHdr10PlusMetadataInput &&
                VideoDynamicRange.HDR10 in display.supportedDynamicRanges,
        supportsDolbyVisionMetadata =
            includesNativeSurface && VideoDynamicRange.DOLBY_VISION in display.supportedDynamicRanges,
    )
}

private const val PHILIPS_BRAND = "Philips"
