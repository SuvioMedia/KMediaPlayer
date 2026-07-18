package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.max

/**
 * Projection output used by ExoPlayer's Media3 video graph.
 *
 * HDR input is sampled through `EXT_YUV_target`, intermediate frames use RGBA16F, and HDR output
 * uses Media3's RGBA_1010102 EGL surface with an explicit BT.2020 output colorspace. HLG may be
 * mapped through the linear graph to a PQ surface when the Android HLG EGL path is unreliable. The
 * decoder is connected to Media3's `PlaybackVideoGraphWrapper`, which owns input-frame registration
 * and backpressure; this view is only the graph's final output surface.
 */
@UnstableApi
internal class AndroidProjectionGlSurfaceView(
    context: Context,
    private val effectController: AndroidProjectionEffectController,
) : SurfaceView(context),
    SurfaceHolder.Callback,
    AndroidProjectionEffectController.Listener {
    interface Callback {
        fun onVideoEffectConfigured(
            outputDynamicRange: VideoDynamicRange,
            requireHdr: Boolean,
        ) = Unit

        fun onColorRendererConfigured(outputDynamicRange: VideoDynamicRange) = Unit

        fun onHdrRendererUnavailable(
            outputDynamicRange: VideoDynamicRange,
            message: String,
        ) = Unit

        fun onProjectionRendererError(message: String) = Unit
    }

    var callback: Callback? = null
    private var released = false

    init {
        holder.addCallback(this)
        setBackgroundColor(android.graphics.Color.BLACK)
        effectController.attachListener(this)
    }

    fun configure(
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
        sourceColorInfo: VideoColorInfo,
        sourceFormat: Format?,
        plannedOutputDynamicRange: VideoDynamicRange,
        plannedMetadataHandling: DynamicMetadataHandling,
        dynamicRangePolicy: DynamicRangePolicy,
        displayPeakLuminanceNits: Float?,
    ) {
        val configuration =
            effectController.configure(
                projection = projection,
                projectionView = projectionView,
                textureCrop = textureCrop,
                sourceColorInfo = sourceColorInfo,
                sourceFormat = sourceFormat,
                plannedOutputDynamicRange = plannedOutputDynamicRange,
                plannedMetadataHandling = plannedMetadataHandling,
                dynamicRangePolicy = dynamicRangePolicy,
                displayPeakLuminanceNits = displayPeakLuminanceNits,
            ) ?: return
        callback?.onVideoEffectConfigured(
            configuration.outputDynamicRange,
            configuration.requireHdr,
        )
    }

    fun onResume() = Unit

    fun onPause() = Unit

    fun updateHdr10PlusMetadata(
        presentationTimeUs: Long,
        payload: ByteArray,
    ) {
        effectController.putHdr10PlusMetadata(presentationTimeUs, payload)
    }

    fun clearHdr10PlusMetadata() = effectController.clearHdr10PlusMetadata()

    fun releaseRenderer() {
        if (released) return
        released = true
        holder.removeCallback(this)
        effectController.detachListener(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        released = false
        effectController.attachListener(this)
        androidVideoLogger.d { "Controlled color renderer output surface created." }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        androidVideoLogger.d { "Controlled color renderer output surface destroyed." }
    }

    override fun onColorRendererConfigured(outputDynamicRange: VideoDynamicRange) {
        callback?.onColorRendererConfigured(outputDynamicRange)
    }

    override fun onHdrRendererUnavailable(
        outputDynamicRange: VideoDynamicRange,
        message: String,
    ) {
        callback?.onHdrRendererUnavailable(outputDynamicRange, message)
    }

    override fun onProjectionRendererError(message: String) {
        callback?.onProjectionRendererError(message)
    }
}

internal class AndroidProjectionEffectController(
    initialOutputDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
    initialRequireHdr: Boolean = false,
) {
    interface Listener {
        fun onColorRendererConfigured(outputDynamicRange: VideoDynamicRange)

        fun onHdrRendererUnavailable(
            outputDynamicRange: VideoDynamicRange,
            message: String,
        )

        fun onProjectionRendererError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val configurationLock = Any()
    private val projectionState = ProjectionStateStore()
    private val projectionEffect = AndroidProjectionGlEffect(this)

    @Volatile
    private var listener: Listener? = null

    private var configuration =
        ControlledEffectConfiguration(
            inputFormat = null,
            outputDynamicRange = initialOutputDynamicRange,
            requireHdr = initialRequireHdr,
        )
    private var generation = 0L
    private var reportedGeneration = Long.MIN_VALUE

    internal val effect: Effect get() = projectionEffect

    fun attachListener(listener: Listener) {
        synchronized(configurationLock) {
            this.listener = listener
            reportedGeneration = Long.MIN_VALUE
        }
    }

    fun detachListener(listener: Listener) {
        synchronized(configurationLock) {
            if (this.listener === listener) this.listener = null
        }
    }

    fun prepareOutput(
        outputDynamicRange: VideoDynamicRange,
        requireHdr: Boolean,
    ) {
        synchronized(configurationLock) {
            val updated =
                configuration.copy(
                    outputDynamicRange = outputDynamicRange,
                    requireHdr = requireHdr,
                )
            if (updated != configuration) {
                configuration = updated
                generation += 1
                reportedGeneration = Long.MIN_VALUE
            }
        }
    }

    fun configure(
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
        sourceColorInfo: VideoColorInfo,
        sourceFormat: Format?,
        plannedOutputDynamicRange: VideoDynamicRange,
        plannedMetadataHandling: DynamicMetadataHandling,
        dynamicRangePolicy: DynamicRangePolicy,
        displayPeakLuminanceNits: Float?,
    ): ControlledEffectConfiguration? {
        projectionState.snapshot =
            ProjectionSnapshot(
                projection = projection.normalized(),
                projectionView = projectionView.normalized(),
                textureCrop = textureCrop.normalized(),
                sourceIsHdr = sourceColorInfo.isHdr,
            )
        projectionState.configureHdr10Plus(
            required =
                sourceColorInfo.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                    plannedMetadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER,
            displayPeakLuminanceNits = displayPeakLuminanceNits,
        )
        val inputFormat =
            sourceFormat.toProjectionInputFormat(sourceColorInfo) ?: run {
                androidVideoLogger.d {
                    "Controlled color renderer waiting for a complete video format " +
                        "(sourceFormat=${sourceFormat != null}, range=${sourceColorInfo.dynamicRange})."
                }
                return null
            }
        val updated =
            ControlledEffectConfiguration(
                inputFormat = inputFormat,
                outputDynamicRange = plannedOutputDynamicRange.toProcessorOutputDynamicRange(sourceColorInfo),
                requireHdr = dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR,
            )
        synchronized(configurationLock) {
            if (updated != configuration) {
                configuration = updated
                generation += 1
                reportedGeneration = Long.MIN_VALUE
            }
            return configuration
        }
    }

    fun putHdr10PlusMetadata(
        presentationTimeUs: Long,
        payload: ByteArray,
    ) = projectionState.putHdr10PlusMetadata(presentationTimeUs, payload)

    fun clearHdr10PlusMetadata() = projectionState.clearHdr10PlusMetadata()

    internal fun createShaderProgram(useHdr: Boolean): GlShaderProgram {
        val runtime =
            synchronized(configurationLock) {
                ShaderRuntime(
                    generation = generation,
                    outputDynamicRange = configuration.outputDynamicRange,
                )
            }
        return try {
            AndroidProjectionShaderProgram(
                projectionState = projectionState,
                useHdrOutput = useHdr,
                controller = this,
                runtime = runtime,
            )
        } catch (failure: VideoFrameProcessingException) {
            reportFailure(runtime, failure)
            throw failure
        }
    }

    internal fun reportFirstFrame(runtime: ShaderRuntime) {
        val targetListener =
            synchronized(configurationLock) {
                if (runtime.generation != generation || reportedGeneration == generation) return
                val currentListener = listener ?: return
                reportedGeneration = generation
                currentListener
            }
        mainHandler.post {
            synchronized(configurationLock) {
                if (runtime.generation != generation || listener !== targetListener) return@post
            }
            targetListener.onColorRendererConfigured(runtime.outputDynamicRange)
        }
    }

    internal fun reportFailure(
        runtime: ShaderRuntime,
        failure: Throwable,
    ) {
        val targetListener = synchronized(configurationLock) { listener } ?: return
        val message = failure.toProjectionRendererMessage(runtime.outputDynamicRange)
        mainHandler.post {
            synchronized(configurationLock) {
                if (runtime.generation != generation || listener !== targetListener) return@post
            }
            if (runtime.outputDynamicRange.isControlledHdrOutput()) {
                targetListener.onHdrRendererUnavailable(runtime.outputDynamicRange, message)
            } else {
                targetListener.onProjectionRendererError(message)
            }
        }
    }
}

private fun VideoDynamicRange.isControlledHdrOutput(): Boolean =
    this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

internal data class ControlledEffectConfiguration(
    val inputFormat: Format?,
    val outputDynamicRange: VideoDynamicRange,
    val requireHdr: Boolean,
)

internal data class ShaderRuntime(
    val generation: Long,
    val outputDynamicRange: VideoDynamicRange,
)

private data class ProjectionSnapshot(
    val projection: VideoProjectionSettings = VideoProjectionSettings(),
    val projectionView: VideoProjectionViewSettings = VideoProjectionViewSettings(),
    val textureCrop: VideoTextureCrop = VideoTextureCrop(),
    val sourceIsHdr: Boolean = false,
)

private class ProjectionStateStore {
    @Volatile
    var snapshot: ProjectionSnapshot = ProjectionSnapshot()

    private val hdr10PlusLock = Any()
    private val hdr10PlusCurves = mutableListOf<Hdr10PlusToneCurve>()
    private var hdr10PlusRequired = false
    private var hdr10PlusTargetPeakNits = DEFAULT_HDR_TARGET_PEAK_NITS

    fun configureHdr10Plus(
        required: Boolean,
        displayPeakLuminanceNits: Float?,
    ) {
        val targetPeak =
            displayPeakLuminanceNits
                ?.takeIf { it.isFinite() && it > 0f }
                ?.toDouble() ?: DEFAULT_HDR_TARGET_PEAK_NITS
        synchronized(hdr10PlusLock) {
            if (targetPeak != hdr10PlusTargetPeakNits) {
                hdr10PlusCurves.clear()
            }
            hdr10PlusRequired = required
            hdr10PlusTargetPeakNits = targetPeak
        }
    }

    fun putHdr10PlusMetadata(
        presentationTimeUs: Long,
        payload: ByteArray,
    ) {
        // The first valid payload is also what promotes an HDR10 source to HDR10+. Cache it
        // before the planner has observed that promotion so it is available to the first frame.
        val targetPeak = synchronized(hdr10PlusLock) { hdr10PlusTargetPeakNits }
        val metadata =
            when (val parsed = Hdr10PlusMetadataParser.parse(payload, presentationTimeUs)) {
                is Hdr10PlusParseResult.Success -> parsed.metadata
                is Hdr10PlusParseResult.Invalid -> return
            }
        val curve = metadata.toToneCurve(targetPeak) ?: return
        synchronized(hdr10PlusLock) {
            if (targetPeak != hdr10PlusTargetPeakNits) return
            hdr10PlusCurves.removeAll { it.presentationTimeUs == presentationTimeUs }
            val insertionIndex =
                hdr10PlusCurves
                    .indexOfFirst { it.presentationTimeUs > presentationTimeUs }
                    .takeIf { it >= 0 } ?: hdr10PlusCurves.size
            hdr10PlusCurves.add(insertionIndex, curve)
            while (hdr10PlusCurves.size > HDR10_PLUS_CURVE_CAPACITY) hdr10PlusCurves.removeAt(0)
        }
    }

    fun hdr10PlusCurveAt(presentationTimeUs: Long): Hdr10PlusToneCurve? =
        synchronized(hdr10PlusLock) {
            // Android defines HDR10+ metadata as persistent from its associated frame until the
            // next metadata-bearing frame in display order. Keep the active curve and discard only
            // older superseded entries.
            val index = hdr10PlusCurves.indexOfLast { it.presentationTimeUs <= presentationTimeUs }
            if (index < 0) {
                null
            } else {
                repeat(index) { hdr10PlusCurves.removeAt(0) }
                hdr10PlusCurves.first()
            }
        }

    fun requiresHdr10PlusMetadata(): Boolean = synchronized(hdr10PlusLock) { hdr10PlusRequired }

    fun clearHdr10PlusMetadata() = synchronized(hdr10PlusLock) { hdr10PlusCurves.clear() }
}

@UnstableApi
private class AndroidProjectionGlEffect(
    private val controller: AndroidProjectionEffectController,
) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ) = controller.createShaderProgram(useHdr)
}

@UnstableApi
private class AndroidProjectionShaderProgram(
    private val projectionState: ProjectionStateStore,
    useHdrOutput: Boolean,
    private val controller: AndroidProjectionEffectController,
    private val runtime: ShaderRuntime,
) : BaseGlShaderProgram(
        useHdrOutput || projectionState.snapshot.sourceIsHdr,
        1,
    ) {
    private val glProgram =
        try {
            GlProgram(ANDROID_PROJECTION_VERTEX_SHADER, ANDROID_PROJECTION_FRAGMENT_SHADER)
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    private var outputWidth = 1
    private var outputHeight = 1

    init {
        glProgram.setBufferAttribute("aPosition", FULLSCREEN_QUAD_COORDS, 2)
    }

    override fun configure(
        inputWidth: Int,
        inputHeight: Int,
    ): Size {
        outputWidth = max(1, inputWidth)
        outputHeight = max(1, inputHeight)
        return Size(outputWidth, outputHeight)
    }

    override fun drawFrame(
        inputTexId: Int,
        presentationTimeUs: Long,
    ) {
        try {
            val snapshot = projectionState.snapshot
            val hdr10PlusCurve = projectionState.hdr10PlusCurveAt(presentationTimeUs)
            if (projectionState.requiresHdr10PlusMetadata() && hdr10PlusCurve == null) {
                throw VideoFrameProcessingException(
                    IllegalStateException("No valid ST 2094-40 metadata was associated with this HDR10+ frame."),
                    presentationTimeUs,
                )
            }
            val plan =
                snapshot.projection.toVideoProjectionRenderPlan(
                    VideoProjectionRenderOptions(textureCrop = snapshot.textureCrop),
                )
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            glProgram.setIntUniform("uProjectionType", snapshot.projection.projectionType.projectionShaderCode)
            glProgram.setFloatUniform("uFovDegrees", plan.mesh.horizontalFovDegrees)
            glProgram.setFloatUniform("uViewYawDegrees", snapshot.projectionView.yawDegrees)
            glProgram.setFloatUniform("uViewPitchDegrees", snapshot.projectionView.pitchDegrees)
            glProgram.setFloatUniform("uViewRollDegrees", snapshot.projectionView.rollDegrees)
            glProgram.setFloatUniform("uViewZoom", snapshot.projectionView.zoom)
            glProgram.setIntUniform("uHdr10PlusEnabled", if (hdr10PlusCurve == null) 0 else 1)
            glProgram.setFloatUniform("uHdr10PlusSourcePeakNits", hdr10PlusCurve?.sourcePeakNits ?: 1f)
            hdr10PlusCurve?.normalizedOutputLuminance?.forEachIndexed { index, value ->
                glProgram.setFloatUniform("uHdr10PlusCurve$index", value)
            }

            if (plan.stereo) {
                val leftWidth = outputWidth / 2
                drawEye(plan.leftEyeTexture, 0, leftWidth)
                drawEye(plan.rightEyeTexture, leftWidth, outputWidth - leftWidth)
            } else {
                drawEye(plan.leftEyeTexture, 0, outputWidth)
            }
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GlUtil.checkGlError()
            controller.reportFirstFrame(runtime)
        } catch (error: VideoFrameProcessingException) {
            failFrame(error)
        } catch (error: GlUtil.GlException) {
            failFrame(VideoFrameProcessingException(error, presentationTimeUs))
        }
    }

    private fun failFrame(failure: VideoFrameProcessingException): Nothing {
        controller.reportFailure(runtime, failure)
        throw failure
    }

    private fun drawEye(
        textureWindow: VideoTextureWindow,
        x: Int,
        width: Int,
    ) {
        GLES20.glViewport(x, 0, width, outputHeight)
        glProgram.setFloatsUniform(
            "uEyeWindow",
            floatArrayOf(textureWindow.left, textureWindow.top, textureWindow.right, textureWindow.bottom),
        )
        glProgram.setIntUniform("uRotation", textureWindow.rotation.ordinal)
        glProgram.setFloatUniform("uViewportAspect", width.toFloat() / outputHeight.toFloat())
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, FULLSCREEN_QUAD_VERTEX_COUNT)
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    }
}

private fun Format?.toProjectionInputFormat(source: VideoColorInfo): Format? {
    if (this == null && source.dynamicRange == VideoDynamicRange.UNKNOWN) return null
    val media3ColorInfo = this?.colorInfo ?: source.toMedia3ColorInfo() ?: return null
    val width = this?.width?.takeIf { it > 0 } ?: DEFAULT_VIDEO_TEXTURE_WIDTH
    val height = this?.height?.takeIf { it > 0 } ?: DEFAULT_VIDEO_TEXTURE_HEIGHT
    return (this ?: Format.Builder().build())
        .buildUpon()
        .setWidth(width)
        .setHeight(height)
        .setPixelWidthHeightRatio(this?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f)
        .setColorInfo(media3ColorInfo)
        .build()
}

private fun VideoColorInfo.toMedia3ColorInfo(): ColorInfo? {
    val colorSpace =
        when (primaries) {
            VideoColorPrimaries.BT2020 -> C.COLOR_SPACE_BT2020
            VideoColorPrimaries.BT709 -> C.COLOR_SPACE_BT709
            else -> if (isHdr) C.COLOR_SPACE_BT2020 else C.COLOR_SPACE_BT709
        }
    val colorTransfer =
        when (transfer) {
            VideoColorTransfer.PQ -> C.COLOR_TRANSFER_ST2084
            VideoColorTransfer.HLG -> C.COLOR_TRANSFER_HLG
            VideoColorTransfer.SDR,
            VideoColorTransfer.SRGB,
            -> C.COLOR_TRANSFER_SDR
            VideoColorTransfer.LINEAR -> C.COLOR_TRANSFER_LINEAR
            VideoColorTransfer.UNKNOWN -> return null
        }
    val colorRange =
        when (range) {
            VideoColorRange.FULL -> C.COLOR_RANGE_FULL
            VideoColorRange.LIMITED,
            VideoColorRange.UNKNOWN,
            -> C.COLOR_RANGE_LIMITED
        }
    return ColorInfo
        .Builder()
        .setColorSpace(colorSpace)
        .setColorRange(colorRange)
        .setColorTransfer(colorTransfer)
        .setLumaBitdepth(bitDepth ?: if (isHdr) HDR_BIT_DEPTH else SDR_BIT_DEPTH)
        .setChromaBitdepth(bitDepth ?: if (isHdr) HDR_BIT_DEPTH else SDR_BIT_DEPTH)
        .build()
}

private fun VideoDynamicRange.toProcessorOutputDynamicRange(source: VideoColorInfo): VideoDynamicRange =
    when {
        this != VideoDynamicRange.UNKNOWN -> this
        source.isHdr -> VideoDynamicRange.SDR
        else -> VideoDynamicRange.SDR
    }

private fun Throwable.toProjectionRendererMessage(outputDynamicRange: VideoDynamicRange): String {
    val failureChain =
        generateSequence(this) { it.cause }
            .take(MAX_REPORTED_FAILURE_CAUSES)
            .joinToString(separator = " <- ") { failure ->
                val type = failure::class.simpleName ?: "Throwable"
                val detail = failure.message?.trim().orEmpty()
                if (detail.isEmpty()) type else "$type: $detail"
            }
    return "Android Media3 projection renderer failed for ${outputDynamicRange.name}: $failureChain"
}

private val FULLSCREEN_QUAD_COORDS =
    floatArrayOf(
        -1f,
        -1f,
        1f,
        -1f,
        -1f,
        1f,
        1f,
        1f,
    )

internal const val ANDROID_PROJECTION_VERTEX_SHADER =
    """
    attribute vec2 aPosition;
    varying vec2 vUv;

    void main() {
        vUv = (aPosition + vec2(1.0)) * 0.5;
        gl_Position = vec4(aPosition, 0.0, 1.0);
    }
    """

internal const val ANDROID_PROJECTION_FRAGMENT_SHADER =
    """
    precision highp float;

    uniform sampler2D uTexture;
    uniform int uProjectionType;
    uniform float uFovDegrees;
    uniform vec4 uEyeWindow;
    uniform int uRotation;
    uniform float uViewportAspect;
    uniform float uViewYawDegrees;
    uniform float uViewPitchDegrees;
    uniform float uViewRollDegrees;
    uniform float uViewZoom;
    uniform int uHdr10PlusEnabled;
    uniform float uHdr10PlusSourcePeakNits;
    uniform float uHdr10PlusCurve0;
    uniform float uHdr10PlusCurve1;
    uniform float uHdr10PlusCurve2;
    uniform float uHdr10PlusCurve3;
    uniform float uHdr10PlusCurve4;
    uniform float uHdr10PlusCurve5;
    uniform float uHdr10PlusCurve6;
    uniform float uHdr10PlusCurve7;
    uniform float uHdr10PlusCurve8;
    uniform float uHdr10PlusCurve9;
    uniform float uHdr10PlusCurve10;
    uniform float uHdr10PlusCurve11;
    uniform float uHdr10PlusCurve12;
    uniform float uHdr10PlusCurve13;
    uniform float uHdr10PlusCurve14;
    uniform float uHdr10PlusCurve15;
    uniform float uHdr10PlusCurve16;
    uniform float uHdr10PlusCurve17;
    uniform float uHdr10PlusCurve18;
    uniform float uHdr10PlusCurve19;
    uniform float uHdr10PlusCurve20;
    uniform float uHdr10PlusCurve21;
    uniform float uHdr10PlusCurve22;
    uniform float uHdr10PlusCurve23;
    uniform float uHdr10PlusCurve24;
    uniform float uHdr10PlusCurve25;
    uniform float uHdr10PlusCurve26;
    uniform float uHdr10PlusCurve27;
    uniform float uHdr10PlusCurve28;
    uniform float uHdr10PlusCurve29;
    uniform float uHdr10PlusCurve30;
    uniform float uHdr10PlusCurve31;
    uniform float uHdr10PlusCurve32;
    varying vec2 vUv;

    const float PI = 3.14159265358979323846264;
    const float CAMERA_FOV_DEGREES = 95.0;

    float hdr10PlusCurveSample(int index) {
        if (index <= 0) return uHdr10PlusCurve0;
        if (index == 1) return uHdr10PlusCurve1;
        if (index == 2) return uHdr10PlusCurve2;
        if (index == 3) return uHdr10PlusCurve3;
        if (index == 4) return uHdr10PlusCurve4;
        if (index == 5) return uHdr10PlusCurve5;
        if (index == 6) return uHdr10PlusCurve6;
        if (index == 7) return uHdr10PlusCurve7;
        if (index == 8) return uHdr10PlusCurve8;
        if (index == 9) return uHdr10PlusCurve9;
        if (index == 10) return uHdr10PlusCurve10;
        if (index == 11) return uHdr10PlusCurve11;
        if (index == 12) return uHdr10PlusCurve12;
        if (index == 13) return uHdr10PlusCurve13;
        if (index == 14) return uHdr10PlusCurve14;
        if (index == 15) return uHdr10PlusCurve15;
        if (index == 16) return uHdr10PlusCurve16;
        if (index == 17) return uHdr10PlusCurve17;
        if (index == 18) return uHdr10PlusCurve18;
        if (index == 19) return uHdr10PlusCurve19;
        if (index == 20) return uHdr10PlusCurve20;
        if (index == 21) return uHdr10PlusCurve21;
        if (index == 22) return uHdr10PlusCurve22;
        if (index == 23) return uHdr10PlusCurve23;
        if (index == 24) return uHdr10PlusCurve24;
        if (index == 25) return uHdr10PlusCurve25;
        if (index == 26) return uHdr10PlusCurve26;
        if (index == 27) return uHdr10PlusCurve27;
        if (index == 28) return uHdr10PlusCurve28;
        if (index == 29) return uHdr10PlusCurve29;
        if (index == 30) return uHdr10PlusCurve30;
        if (index == 31) return uHdr10PlusCurve31;
        return uHdr10PlusCurve32;
    }

    vec3 applyHdr10Plus(vec3 linearBt2020) {
        if (uHdr10PlusEnabled == 0) return linearBt2020;
        float inputLuminance = max(dot(linearBt2020, vec3(0.2627, 0.6780, 0.0593)), 0.0);
        float normalized = clamp(inputLuminance * 10000.0 / max(uHdr10PlusSourcePeakNits, 1.0), 0.0, 1.0);
        float curvePosition = normalized * 32.0;
        int lowerIndex = int(floor(curvePosition));
        int upperIndex = lowerIndex < 32 ? lowerIndex + 1 : 32;
        float mappedLuminance = mix(
            hdr10PlusCurveSample(lowerIndex),
            hdr10PlusCurveSample(upperIndex),
            fract(curvePosition)
        );
        float scale = inputLuminance > 0.0000001 ? mappedLuminance / inputLuminance : 0.0;
        return max(linearBt2020 * scale, vec3(0.0));
    }

    vec2 rotateUv(vec2 uv) {
        if (uRotation == 1) return vec2(1.0 - uv.y, uv.x);
        if (uRotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
        if (uRotation == 3) return vec2(uv.y, 1.0 - uv.x);
        return uv;
    }

    vec4 sampleLocal(vec2 localUv) {
        if (localUv.x < 0.0 || localUv.x > 1.0 || localUv.y < 0.0 || localUv.y > 1.0) {
            return vec4(0.0, 0.0, 0.0, 1.0);
        }
        vec2 rotated = rotateUv(localUv);
        vec2 topLeftUv = mix(uEyeWindow.xy, uEyeWindow.zw, rotated);
        vec4 sampled = texture2D(uTexture, vec2(topLeftUv.x, 1.0 - topLeftUv.y));
        return vec4(applyHdr10Plus(sampled.rgb), sampled.a);
    }

    vec3 rayForScreenUv(vec2 screenUv) {
        vec2 p = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
        float tanHalfFov = tan(radians(CAMERA_FOV_DEGREES) * 0.5 / max(uViewZoom, 0.01));
        vec3 direction = normalize(vec3(p.x * uViewportAspect * tanHalfFov, p.y * tanHalfFov, -1.0));
        float yaw = radians(uViewYawDegrees);
        float pitch = radians(uViewPitchDegrees);
        float roll = radians(uViewRollDegrees);
        float cy = cos(yaw);
        float sy = sin(yaw);
        direction = vec3(cy * direction.x + sy * direction.z, direction.y, -sy * direction.x + cy * direction.z);
        float cp = cos(pitch);
        float sp = sin(pitch);
        direction = vec3(direction.x, cp * direction.y - sp * direction.z, sp * direction.y + cp * direction.z);
        float cr = cos(roll);
        float sr = sin(roll);
        return normalize(vec3(cr * direction.x - sr * direction.y, sr * direction.x + cr * direction.y, direction.z));
    }

    vec2 eacFaceUv(float sc, float tc, float cellX, float cellY) {
        vec2 local = vec2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));
        return vec2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);
    }

    vec2 eacUv(vec3 direction) {
        vec3 ad = abs(direction);
        if (ad.z >= ad.x && ad.z >= ad.y) {
            if (direction.z < 0.0) return eacFaceUv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);
            return eacFaceUv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
        }
        if (ad.x >= ad.y) {
            if (direction.x > 0.0) return eacFaceUv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);
            return eacFaceUv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
        }
        if (direction.y > 0.0) return eacFaceUv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);
        return eacFaceUv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
    }

    void main() {
        vec2 screenUv = vec2(vUv.x, 1.0 - vUv.y);
        if (uProjectionType == 0) {
            gl_FragColor = sampleLocal(screenUv);
            return;
        }
        vec3 direction = rayForScreenUv(screenUv);
        if (uProjectionType == 1 || uProjectionType == 2) {
            float horizontalFov = radians(max(uFovDegrees, 1.0));
            float yaw = atan(direction.x, -direction.z);
            float pitch = asin(clamp(direction.y, -1.0, 1.0));
            if (abs(yaw) > horizontalFov * 0.5) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            gl_FragColor = sampleLocal(vec2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI));
            return;
        }
        if (uProjectionType >= 3 && uProjectionType <= 6) {
            float maxTheta = radians(max(uFovDegrees, 1.0)) * 0.5;
            float theta = acos(clamp(-direction.z, -1.0, 1.0));
            if (theta > maxTheta) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            float phi = atan(direction.y, direction.x);
            float radius = theta / maxTheta * 0.5;
            gl_FragColor = sampleLocal(vec2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius));
            return;
        }
        gl_FragColor = sampleLocal(eacUv(direction));
    }
    """

private const val FULLSCREEN_QUAD_VERTEX_COUNT = 4
private const val DEFAULT_VIDEO_TEXTURE_WIDTH = 3840
private const val DEFAULT_VIDEO_TEXTURE_HEIGHT = 2160
private const val HDR_BIT_DEPTH = 10
private const val SDR_BIT_DEPTH = 8
private const val HDR10_PLUS_CURVE_CAPACITY = 240
private const val DEFAULT_HDR_TARGET_PEAK_NITS = 1_000.0
private const val MAX_REPORTED_FAILURE_CAUSES = 4
