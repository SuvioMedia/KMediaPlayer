@file:Suppress("UnstableApiUsage")

package io.github.kdroidfilter.composemediaplayer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.media3.common.util.GlUtil
import com.kdroid.androidcontextprovider.ContextProvider

internal data class AndroidHdrProjectionRuntimeSupport(
    val supportsGles3: Boolean,
    val supportsYuvTarget: Boolean,
    val supportsPqOutput: Boolean,
    val supportsHlgOutput: Boolean,
    val supportsHdr10PlusMetadataInput: Boolean = false,
) {
    val supportsHdrInput: Boolean
        get() = supportsGles3 && supportsYuvTarget

    fun controlledOutputRanges(display: DisplayColorCapabilities): Set<VideoDynamicRange> =
        buildSet {
            if (!supportsHdrInput) return@buildSet
            if (supportsPqOutput && display.supports(VideoDynamicRange.HDR10)) add(VideoDynamicRange.HDR10)
            if (
                supportsPqOutput &&
                supportsHdr10PlusMetadataInput &&
                display.supports(VideoDynamicRange.HDR10)
            ) {
                add(VideoDynamicRange.HDR10_PLUS)
            }
            if (supportsHlgOutput && display.supports(VideoDynamicRange.HLG)) add(VideoDynamicRange.HLG)
        }
}

internal fun shouldUseControlledHdrVerificationSurface(
    policy: DynamicRangePolicy,
    plan: VideoColorPipelinePlan?,
    nativeOutputCanBeConfirmed: Boolean,
    controlledRenderer: RendererColorCapabilities,
): Boolean =
    policy != DynamicRangePolicy.FORCE_SDR &&
        plan?.outputDynamicRange?.isAndroidHdrOutput() == true &&
        (
            plan.route == ColorPipelineRoute.CONTROLLED_HDR_RENDERER ||
                (
                    plan.route == ColorPipelineRoute.SYSTEM_NATIVE_SURFACE &&
                        !nativeOutputCanBeConfirmed &&
                        controlledRenderer.supportsControlled(plan.outputDynamicRange, isProjection = true)
                )
        )

internal fun shouldUseControlledToneMappingSurface(
    policy: DynamicRangePolicy,
    plan: VideoColorPipelinePlan?,
    rendererSupportsToneMapping: Boolean,
    sourceCanBeToneMapped: Boolean,
    nativeHdrUnavailable: Boolean = false,
): Boolean =
    rendererSupportsToneMapping &&
        sourceCanBeToneMapped &&
        plan?.route != ColorPipelineRoute.SOURCE_BRIDGE_SDR &&
        (
            policy == DynamicRangePolicy.FORCE_SDR ||
                plan?.route == ColorPipelineRoute.UNSUPPORTED ||
                nativeHdrUnavailable
        )

private fun VideoDynamicRange.isAndroidHdrOutput(): Boolean =
    this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

internal fun VideoDynamicRange.androidControlledFailureRanges(): Set<VideoDynamicRange> =
    when (this) {
        VideoDynamicRange.HDR10,
        VideoDynamicRange.HDR10_PLUS,
        -> setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS)
        else -> setOf(this)
    }

internal object AndroidHdrProjectionRuntimeProbe {
    @Volatile
    private var cachedSupport: AndroidHdrProjectionRuntimeSupport? = null

    fun get(): AndroidHdrProjectionRuntimeSupport {
        cachedSupport?.let { return it }
        val context = runCatching { ContextProvider.getContext() }.getOrNull() ?: return UNSUPPORTED
        return synchronized(this) {
            cachedSupport ?: probe(context).also { cachedSupport = it }
        }
    }

    private fun probe(context: Context): AndroidHdrProjectionRuntimeSupport {
        val supportsGles3 =
            runCatching {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.deviceConfigurationInfo.reqGlEsVersion >= REQUIRED_GLES_3_VERSION
            }.getOrDefault(false)
        if (!supportsGles3) {
            return AndroidHdrProjectionRuntimeSupport(
                supportsGles3 = false,
                supportsYuvTarget = false,
                supportsPqOutput = false,
                supportsHlgOutput = false,
                supportsHdr10PlusMetadataInput = false,
            ).also { support -> support.logProbeResult() }
        }

        val supportsYuvTarget = runCatching { GlUtil.isYuvTargetExtensionSupported() }.getOrDefault(false)
        return AndroidHdrProjectionRuntimeSupport(
            supportsGles3 = true,
            supportsYuvTarget = supportsYuvTarget,
            supportsPqOutput =
                supportsYuvTarget && runCatching { GlUtil.isBt2020PqExtensionSupported() }.getOrDefault(false),
            supportsHlgOutput =
                supportsYuvTarget && runCatching { GlUtil.isBt2020HlgExtensionSupported() }.getOrDefault(false),
            supportsHdr10PlusMetadataInput = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        ).also { support -> support.logProbeResult() }
    }
}

private fun AndroidHdrProjectionRuntimeSupport.logProbeResult() {
    androidVideoLogger.d {
        "Controlled HDR runtime probe: GLES3=$supportsGles3, YUV_TARGET=$supportsYuvTarget, " +
            "PQ=$supportsPqOutput, HLG=$supportsHlgOutput, HDR10+ metadata=$supportsHdr10PlusMetadataInput."
    }
}

private val UNSUPPORTED =
    AndroidHdrProjectionRuntimeSupport(
        supportsGles3 = false,
        supportsYuvTarget = false,
        supportsPqOutput = false,
        supportsHlgOutput = false,
        supportsHdr10PlusMetadataInput = false,
    )

private const val REQUIRED_GLES_3_VERSION = 0x00030000
