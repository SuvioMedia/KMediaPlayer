package io.github.kdroidfilter.composemediaplayer

import android.hardware.DataSpace
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

internal fun VideoDynamicRange.androidNativeSurfaceDataSpaceOrNull(): Int? =
    when (this) {
        VideoDynamicRange.HDR10,
        VideoDynamicRange.HDR10_PLUS,
        -> DataSpace.DATASPACE_BT2020_PQ

        VideoDynamicRange.HLG -> DataSpace.DATASPACE_BT2020_HLG
        VideoDynamicRange.UNKNOWN,
        VideoDynamicRange.SDR,
        VideoDynamicRange.DOLBY_VISION,
        -> null
    }

internal fun canConfirmAndroidNativeHdrWithSurfaceDataSpace(
    dynamicRange: VideoDynamicRange,
    sdkInt: Int = Build.VERSION.SDK_INT,
    nativeBridgeAvailable: Boolean = AndroidNativeSurfaceDataSpaceBridge.isAvailable(sdkInt),
): Boolean =
    sdkInt >= Build.VERSION_CODES.P &&
        nativeBridgeAvailable &&
        dynamicRange.androidNativeSurfaceDataSpaceOrNull() != null

/** Latches a positive Android 14+ composition report for the current source/surface lifetime. */
internal class AndroidSystemReportedHdrConfirmation {
    private var confirmedDynamicRange = VideoDynamicRange.UNKNOWN

    fun observe(
        route: ColorPipelineRoute,
        outputDynamicRange: VideoDynamicRange,
        displayReportsActiveHdr: Boolean,
    ) {
        if (
            route == ColorPipelineRoute.SYSTEM_NATIVE_SURFACE &&
            outputDynamicRange != VideoDynamicRange.UNKNOWN &&
            outputDynamicRange != VideoDynamicRange.SDR &&
            displayReportsActiveHdr
        ) {
            confirmedDynamicRange = outputDynamicRange
        }
    }

    fun confirms(outputDynamicRange: VideoDynamicRange): Boolean = confirmedDynamicRange == outputDynamicRange

    fun reset() {
        confirmedDynamicRange = VideoDynamicRange.UNKNOWN
    }
}

internal object AndroidNativeSurfaceDataSpaceBridge {
    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("composemediaplayer_android_color")
            true
        }.getOrElse { false }
    }

    fun isAvailable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.P && libraryLoaded

    fun read(surface: Surface): Int? =
        if (!surface.isValid || !isAvailable()) {
            null
        } else {
            runCatching { nativeReadDataSpace(surface) }.getOrNull()
        }

    @JvmStatic
    private external fun nativeReadDataSpace(surface: Surface): Int
}

/**
 * Confirms the dataspace produced by MediaCodec for Media3's native [SurfaceView].
 *
 * The callback is emitted only when the producer readback matches the requested HDR signal.
 * Dolby Vision remains vendor-managed because its decoder may use a vendor-specific dataspace.
 */
internal class AndroidNativeSurfaceColorConfigurator(
    private val onConfigured: (VideoDynamicRange?) -> Unit,
    private val onConfigurationFailed: (VideoDynamicRange, String) -> Unit = { _, _ -> },
) : SurfaceHolder.Callback {
    private var surfaceView: SurfaceView? = null
    private var requestedDynamicRange = VideoDynamicRange.UNKNOWN
    private var hasRenderedFirstFrame = false
    private var committedDataSpace = DataSpace.DATASPACE_UNKNOWN
    private var failedDataSpace: Int? = null
    private var readbackAttempts = 0
    private var retryRunnable: Runnable? = null
    private var generation = 0L

    fun attach(view: SurfaceView?) {
        if (surfaceView === view) {
            applyIfReady()
            return
        }
        cancelReadbackRetry()
        surfaceView?.holder?.removeCallback(this)
        generation++
        failedDataSpace = null
        readbackAttempts = 0
        committedDataSpace = DataSpace.DATASPACE_UNKNOWN
        surfaceView = view
        onConfigured(null)
        view?.holder?.addCallback(this)
        applyIfReady()
    }

    fun configure(
        dynamicRange: VideoDynamicRange,
        hasRenderedFirstFrame: Boolean,
    ) {
        if (requestedDynamicRange != dynamicRange) {
            cancelReadbackRetry()
            failedDataSpace = null
            readbackAttempts = 0
        }
        requestedDynamicRange = dynamicRange
        this.hasRenderedFirstFrame = hasRenderedFirstFrame
        androidVideoLogger.d {
            "Native dataspace request=$dynamicRange firstFrame=$hasRenderedFirstFrame " +
                "surfaceValid=${surfaceView?.holder?.surface?.isValid == true}."
        }
        applyIfReady()
    }

    fun detach(view: SurfaceView? = null) {
        if (view != null && surfaceView !== view) return
        attach(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        cancelReadbackRetry()
        generation++
        failedDataSpace = null
        readbackAttempts = 0
        clearCommittedDataSpace()
        applyIfReady()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        applyIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        cancelReadbackRetry()
        generation++
        failedDataSpace = null
        readbackAttempts = 0
        committedDataSpace = DataSpace.DATASPACE_UNKNOWN
        onConfigured(null)
    }

    private fun applyIfReady() {
        val view = surfaceView ?: return
        val requestedDataSpace =
            requestedDynamicRange.androidNativeSurfaceDataSpaceOrNull()
                ?: run {
                    clearCommittedDataSpace()
                    return
                }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            clearCommittedDataSpace()
            return
        }
        if (!view.holder.surface.isValid) return
        readNativeWindowDataSpace(view, requestedDataSpace)
    }

    private fun readNativeWindowDataSpace(
        view: SurfaceView,
        dataSpace: Int,
    ) {
        val observedDataSpace = AndroidNativeSurfaceDataSpaceBridge.read(view.holder.surface)
        androidVideoLogger.d {
            "Native dataspace readback=$observedDataSpace expected=$dataSpace " +
                "firstFrame=$hasRenderedFirstFrame attempt=$readbackAttempts."
        }
        if (observedDataSpace == dataSpace) {
            cancelReadbackRetry()
            failedDataSpace = null
            commitDataSpace(dataSpace)
            return
        }
        clearCommittedDataSpace()
        if (!hasRenderedFirstFrame || failedDataSpace == dataSpace) return
        readbackAttempts++
        if (readbackAttempts < MAXIMUM_READBACK_ATTEMPTS) {
            scheduleReadbackRetry(view)
            return
        }
        val failedRange =
            requestedDynamicRange.takeIf {
                it.androidNativeSurfaceDataSpaceOrNull() == dataSpace
            } ?: return
        failedDataSpace = dataSpace
        val detail =
            "Android did not expose the decoded $failedRange dataspace through its API 28 " +
                "native-window readback after the first frame (readback=$observedDataSpace)."
        onConfigurationFailed(failedRange, detail)
        androidVideoLogger.w { detail }
    }

    private fun scheduleReadbackRetry(view: SurfaceView) {
        if (retryRunnable != null) return
        val retryGeneration = generation
        val runnable =
            Runnable {
                retryRunnable = null
                if (retryGeneration == generation && surfaceView === view) applyIfReady()
            }
        retryRunnable = runnable
        view.postDelayed(runnable, READBACK_RETRY_DELAY_MS)
    }

    private fun cancelReadbackRetry() {
        retryRunnable?.let { runnable -> surfaceView?.removeCallbacks(runnable) }
        retryRunnable = null
    }

    private fun commitDataSpace(dataSpace: Int) {
        if (committedDataSpace == dataSpace) return
        committedDataSpace = dataSpace
        onConfigured(
            requestedDynamicRange.takeIf {
                it.androidNativeSurfaceDataSpaceOrNull() == dataSpace
            },
        )
    }

    private fun clearCommittedDataSpace() {
        if (committedDataSpace == DataSpace.DATASPACE_UNKNOWN) return
        committedDataSpace = DataSpace.DATASPACE_UNKNOWN
        onConfigured(null)
    }

    private companion object {
        const val MAXIMUM_READBACK_ATTEMPTS = 10
        const val READBACK_RETRY_DELAY_MS = 100L
    }
}
