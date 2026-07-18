@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.removeOutput
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.kCGColorSpaceExtendedLinearITUR_2020
import platform.CoreGraphics.kCGColorSpaceExtendedLinearSRGB
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.kCMSampleAttachmentKey_HDR10PlusPerFrameData
import platform.CoreVideo.CVBufferGetAttachment
import platform.CoreVideo.CVBufferRelease
import platform.CoreVideo.CVMetalTextureCacheCreate
import platform.CoreVideo.CVMetalTextureCacheCreateTextureFromImage
import platform.CoreVideo.CVMetalTextureCacheFlush
import platform.CoreVideo.CVMetalTextureCacheRef
import platform.CoreVideo.CVMetalTextureCacheRefVar
import platform.CoreVideo.CVMetalTextureGetTexture
import platform.CoreVideo.CVMetalTextureRef
import platform.CoreVideo.CVMetalTextureRefVar
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSError
import platform.Metal.MTLClearColorMake
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatR16Unorm
import platform.Metal.MTLPixelFormatR8Unorm
import platform.Metal.MTLPixelFormatRG16Unorm
import platform.Metal.MTLPixelFormatRG8Unorm
import platform.Metal.MTLPixelFormatRGBA16Float
import platform.Metal.MTLPixelFormatRGBA32Float
import platform.Metal.MTLPrimitiveTypeTriangleStrip
import platform.Metal.MTLRegionMake3D
import platform.Metal.MTLRenderPipelineDescriptor
import platform.Metal.MTLRenderPipelineStateProtocol
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureType3D
import platform.Metal.MTLTextureUsageShaderRead
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.UIColor
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal data class AppleMetalProjectionRendererCreation(
    val renderer: AppleMetalProjectionRenderer?,
    val failureDetail: String? = null,
)

/** Pulls decoded P010/NV12 frames and projects them without passing HDR through UIKit/SceneKit. */
internal class AppleMetalProjectionRenderer private constructor(
    val view: MTKView,
    private val device: MTLDeviceProtocol,
    private val pipeline: MTLRenderPipelineStateProtocol,
    private val textureCache: CVMetalTextureCacheRef,
    private val gamutLut: MTLTextureProtocol,
    private val hdrColorSpace: CGColorSpaceRef,
    private val sdrColorSpace: CGColorSpaceRef,
) : NSObject(),
    MTKViewDelegateProtocol {
    private val commandQueue = requireNotNull(device.newCommandQueue())
    private var player: AVPlayer? = null
    private var item: AVPlayerItem? = null
    private var output: AVPlayerItemVideoOutput? = null
    private var requestedPixelFormat: UInt? = null
    private var latestPixelBuffer: CVPixelBufferRef? = null
    private var latestHdr10PlusCurve: Hdr10PlusToneCurve? = null
    private var lastObservedHdr10PlusInfo: Hdr10PlusInfo? = null
    private var projectionPlan: VideoProjectionRenderPlan? = null
    private var projectionTypeCode = VideoProjectionType.Flat.projectionShaderCode
    private var projectionView = VideoProjectionViewSettings()
    private var sourceColorInfo = VideoColorInfo()
    private var outputDynamicRange = VideoDynamicRange.UNKNOWN
    private var plannedMetadataHandling = DynamicMetadataHandling.NONE
    private var displayPeakLuminanceNits: Float? = null
    private var configuredOutput = VideoDynamicRange.UNKNOWN
    private var configurationGeneration = 0L
    private var missingFrameCount = 0
    private var failureReported = false
    private var disposed = false
    private var onConfigured: (VideoDynamicRange) -> Unit = {}
    private var onHdrUnavailable: (VideoDynamicRange, String) -> Unit = { _, _ -> }
    private var onHdr10PlusObserved: (Hdr10PlusInfo) -> Unit = {}
    private var onError: (String) -> Unit = {}

    init {
        view.delegate = this
        view.device = device
        view.colorPixelFormat = MTLPixelFormatRGBA16Float
        view.framebufferOnly = true
        view.autoResizeDrawable = true
        view.preferredFramesPerSecond = IOS_METAL_TARGET_FPS
        view.enableSetNeedsDisplay = false
        view.paused = true
        view.clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0)
        view.backgroundColor = UIColor.blackColor
        (view.layer as? CAMetalLayer)?.apply {
            pixelFormat = MTLPixelFormatRGBA16Float
            framebufferOnly = true
            colorspace = sdrColorSpace
            configureAppleDynamicRange(hdr = false)
        }
    }

    @Suppress("LongParameterList")
    fun configure(
        player: AVPlayer?,
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
        sourceColorInfo: VideoColorInfo,
        outputDynamicRange: VideoDynamicRange,
        plannedMetadataHandling: DynamicMetadataHandling,
        displayPeakLuminanceNits: Float?,
        onConfigured: (VideoDynamicRange) -> Unit,
        onHdrUnavailable: (VideoDynamicRange, String) -> Unit,
        onHdr10PlusObserved: (Hdr10PlusInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (disposed) return
        this.onConfigured = onConfigured
        this.onHdrUnavailable = onHdrUnavailable
        this.onHdr10PlusObserved = onHdr10PlusObserved
        this.onError = onError
        this.projectionView = projectionView.normalized()
        projectionPlan =
            projection.normalized().toVideoProjectionRenderPlan(
                VideoProjectionRenderOptions(textureCrop = textureCrop),
            )
        projectionTypeCode = projection.normalized().projectionType.projectionShaderCode

        val newItem = player?.currentItem
        if (
            newItem == null ||
            sourceColorInfo.dynamicRange == VideoDynamicRange.UNKNOWN ||
            outputDynamicRange == VideoDynamicRange.UNKNOWN
        ) {
            detachOutput()
            view.paused = true
            return
        }

        val sourceChanged = this.sourceColorInfo != sourceColorInfo
        val outputChanged = this.outputDynamicRange != outputDynamicRange
        val metadataHandlingChanged = this.plannedMetadataHandling != plannedMetadataHandling
        val displayPeakChanged = this.displayPeakLuminanceNits != displayPeakLuminanceNits
        this.player = player
        this.sourceColorInfo = sourceColorInfo
        this.outputDynamicRange = outputDynamicRange
        this.plannedMetadataHandling = plannedMetadataHandling
        this.displayPeakLuminanceNits = displayPeakLuminanceNits
        configureLayerForOutput(outputDynamicRange)

        val pixelFormat =
            if (sourceColorInfo.isHdr) {
                kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            } else {
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            }
        if (item !== newItem || requestedPixelFormat != pixelFormat || sourceChanged) {
            attachOutput(newItem, pixelFormat)
        }
        if (sourceChanged || outputChanged || metadataHandlingChanged || displayPeakChanged) resetConfirmation()
        view.paused = false
        view.draw()
    }

    fun release() {
        if (disposed) return
        disposed = true
        configurationGeneration++
        view.paused = true
        view.delegate = null
        detachOutput()
        CVMetalTextureCacheFlush(textureCache, 0u)
        CFRelease(textureCache)
        CFRelease(hdrColorSpace)
        CFRelease(sdrColorSpace)
    }

    override fun mtkView(
        view: MTKView,
        drawableSizeWillChange: CValue<CGSize>,
    ) = Unit

    override fun drawInMTKView(view: MTKView) {
        if (disposed || failureReported) return
        val activeOutput = output ?: return
        val activeItem = item ?: return
        val plan = projectionPlan ?: return

        if (outputDynamicRange.isHdrOutput && (view.window?.screen?.potentialEDRHeadroom ?: 0.0) <= 1.0) {
            reportHdrUnavailable("The active iOS screen no longer exposes EDR headroom for Metal projection.")
            return
        }

        val itemTime = activeOutput.itemTimeForHostTime(CACurrentMediaTime())
        val newPixelBuffer =
            if (activeOutput.hasNewPixelBufferForItemTime(itemTime) || latestPixelBuffer == null) {
                memScoped {
                    val displayTime = alloc<CMTime>()
                    activeOutput.copyPixelBufferForItemTime(itemTime, displayTime.ptr)
                }
            } else {
                null
            }
        if (newPixelBuffer != null) {
            if (!acceptDecodedFrame(view, itemTime, newPixelBuffer)) return
        }
        val pixelBuffer = latestPixelBuffer
        if (pixelBuffer == null) {
            if (activeItem.status == AVPlayerItemStatusReadyToPlay) {
                missingFrameCount++
                if (missingFrameCount >= IOS_METAL_MISSING_FRAME_LIMIT) {
                    reportRendererFailure("AVPlayerItemVideoOutput produced no Metal-compatible frame for 10 seconds.")
                }
            }
            return
        }

        renderPixelBuffer(view, pixelBuffer, plan)
    }

    private fun acceptDecodedFrame(
        view: MTKView,
        itemTime: CValue<CMTime>,
        pixelBuffer: CVPixelBufferRef,
    ): Boolean {
        latestPixelBuffer?.let(::CVPixelBufferRelease)
        latestPixelBuffer = pixelBuffer
        val observedHdr10Plus =
            if (sourceCanContainHdr10PlusMetadata()) {
                pixelBuffer.hdr10PlusFrameMetadata(
                    presentationTimeUs = CMTimeGetSeconds(itemTime).secondsToMicroseconds(),
                    displayPeakNits = hdr10PlusTargetPeakNits(view),
                )
            } else {
                null
            }
        observedHdr10Plus
            ?.info
            ?.takeIf { it != lastObservedHdr10PlusInfo }
            ?.let { info ->
                lastObservedHdr10PlusInfo = info
                onHdr10PlusObserved(info)
            }
        latestHdr10PlusCurve = observedHdr10Plus?.toneCurve.takeIf { requiresHdr10PlusMetadata() }
        if (requiresHdr10PlusMetadata() && latestHdr10PlusCurve == null) {
            reportHdr10PlusUnavailable(
                "AVFoundation did not expose valid ST 2094-40 metadata for this HDR10+ frame.",
            )
            return false
        }
        missingFrameCount = 0
        return true
    }

    private fun sourceCanContainHdr10PlusMetadata(): Boolean =
        sourceColorInfo.transfer == VideoColorTransfer.PQ &&
            (
                sourceColorInfo.dynamicRange == VideoDynamicRange.HDR10 ||
                    sourceColorInfo.dynamicRange == VideoDynamicRange.HDR10_PLUS
            )

    @Suppress("LongMethod")
    private fun renderPixelBuffer(
        view: MTKView,
        pixelBuffer: CVPixelBufferRef,
        plan: VideoProjectionRenderPlan,
    ) {
        if (CVPixelBufferGetPlaneCount(pixelBuffer) != IOS_EXPECTED_YUV_PLANES.toULong()) {
            reportRendererFailure("AVFoundation returned a non-bi-planar frame to the Metal projection renderer.")
            return
        }
        val pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer)
        val isTenBit =
            pixelFormat == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange ||
                pixelFormat == kCVPixelFormatType_420YpCbCr10BiPlanarFullRange
        val isEightBit =
            pixelFormat == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
                pixelFormat == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        if (!isTenBit && !isEightBit) {
            reportRendererFailure("Unsupported AVFoundation pixel format: $pixelFormat (expected P010 or NV12).")
            return
        }
        val fullRange =
            pixelFormat == kCVPixelFormatType_420YpCbCr10BiPlanarFullRange ||
                pixelFormat == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        val lumaFormat = if (isTenBit) MTLPixelFormatR16Unorm else MTLPixelFormatR8Unorm
        val chromaFormat = if (isTenBit) MTLPixelFormatRG16Unorm else MTLPixelFormatRG8Unorm

        val textures = createFrameTextures(pixelBuffer, lumaFormat, chromaFormat) ?: return
        val renderPass = view.currentRenderPassDescriptor
        val drawable = view.currentDrawable
        val commandBuffer = commandQueue.commandBuffer()
        if (renderPass == null || drawable == null || commandBuffer == null) {
            textures.release()
            return
        }
        val encoder = commandBuffer.renderCommandEncoderWithDescriptor(renderPass)
        if (encoder == null) {
            textures.release()
            reportRendererFailure("Metal could not create a projection render-command encoder.")
            return
        }

        val parameters = projectionParameters(view, plan, isTenBit, fullRange, latestHdr10PlusCurve)
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(textures.lumaTexture, atIndex = 0u)
        encoder.setFragmentTexture(textures.chromaTexture, atIndex = 1u)
        encoder.setFragmentTexture(gamutLut, atIndex = 2u)
        parameters.usePinned { pinned ->
            encoder.setFragmentBytes(
                bytes = pinned.addressOf(0),
                length = (parameters.size * Float.SIZE_BYTES).toULong(),
                atIndex = 0u,
            )
        }
        encoder.drawPrimitives(
            primitiveType = MTLPrimitiveTypeTriangleStrip,
            vertexStart = 0u,
            vertexCount = IOS_METAL_QUAD_VERTEX_COUNT.toULong(),
        )
        encoder.endEncoding()
        commandBuffer.presentDrawable(drawable)
        val generation = configurationGeneration
        commandBuffer.addCompletedHandler { completedBuffer ->
            textures.release()
            val error = completedBuffer?.error
            dispatch_async(dispatch_get_main_queue()) {
                if (disposed || generation != configurationGeneration || failureReported) return@dispatch_async
                if (error != null) {
                    reportRendererFailure("Metal projection command failed: ${error.localizedDescription}")
                } else if (
                    (view.layer as? CAMetalLayer)
                        ?.isAppleDynamicRangeConfigured(outputDynamicRange.isHdrOutput) != true
                ) {
                    reportRendererFailure(
                        "The iOS layer did not retain the requested dynamic-range configuration.",
                    )
                } else if (configuredOutput != outputDynamicRange) {
                    configuredOutput = outputDynamicRange
                    onConfigured(outputDynamicRange)
                }
            }
        }
        commandBuffer.commit()
    }

    private fun createFrameTextures(
        pixelBuffer: CVPixelBufferRef,
        lumaFormat: ULong,
        chromaFormat: ULong,
    ): AppleMetalFrameTextures? =
        memScoped {
            val lumaRefVar = alloc<CVMetalTextureRefVar>()
            val chromaRefVar = alloc<CVMetalTextureRefVar>()
            val lumaStatus =
                CVMetalTextureCacheCreateTextureFromImage(
                    allocator = null,
                    textureCache = textureCache,
                    sourceImage = pixelBuffer,
                    textureAttributes = null,
                    pixelFormat = lumaFormat,
                    width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0u),
                    height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0u),
                    planeIndex = 0u,
                    textureOut = lumaRefVar.ptr,
                )
            val lumaRef = lumaRefVar.value
            if (lumaStatus != 0 || lumaRef == null) {
                reportRendererFailure("CoreVideo could not create the Metal luma texture (CVReturn=$lumaStatus).")
                return@memScoped null
            }
            val chromaStatus =
                CVMetalTextureCacheCreateTextureFromImage(
                    allocator = null,
                    textureCache = textureCache,
                    sourceImage = pixelBuffer,
                    textureAttributes = null,
                    pixelFormat = chromaFormat,
                    width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 1u),
                    height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1u),
                    planeIndex = 1u,
                    textureOut = chromaRefVar.ptr,
                )
            val chromaRef = chromaRefVar.value
            if (chromaStatus != 0 || chromaRef == null) {
                CVBufferRelease(lumaRef)
                reportRendererFailure("CoreVideo could not create the Metal chroma texture (CVReturn=$chromaStatus).")
                return@memScoped null
            }
            val lumaTexture = CVMetalTextureGetTexture(lumaRef)
            val chromaTexture = CVMetalTextureGetTexture(chromaRef)
            if (lumaTexture == null || chromaTexture == null) {
                CVBufferRelease(lumaRef)
                CVBufferRelease(chromaRef)
                reportRendererFailure("CoreVideo returned empty Metal plane textures.")
                return@memScoped null
            }
            @Suppress("UNCHECKED_CAST")
            AppleMetalFrameTextures(
                lumaRef,
                chromaRef,
                lumaTexture as platform.Metal.MTLTextureProtocol,
                chromaTexture as platform.Metal.MTLTextureProtocol,
            )
        }

    private fun projectionParameters(
        view: MTKView,
        plan: VideoProjectionRenderPlan,
        isTenBit: Boolean,
        fullRange: Boolean,
        hdr10PlusCurve: Hdr10PlusToneCurve?,
    ): FloatArray {
        val drawableSize = view.drawableSize
        val viewportAspect =
            drawableSize.useContents {
                val eyeWidth = if (plan.stereo) width / 2.0 else width
                (eyeWidth / height.coerceAtLeast(1.0)).toFloat()
            }
        val sourcePeak =
            (
                sourceColorInfo.masteringDisplay?.maxLuminanceNits
                    ?: sourceColorInfo.contentLightLevel?.maxContentLightLevelNits?.toFloat()
                    ?: IOS_DEFAULT_HDR_PEAK_NITS
            ).coerceIn(IOS_MIN_HDR_PEAK_NITS, IOS_MAX_HDR_PEAK_NITS)
        val base =
            floatArrayOf(
                projectionTypeCode.toFloat(),
                plan.mesh.horizontalFovDegrees,
                if (plan.stereo) 1f else 0f,
                viewportAspect,
                plan.leftEyeTexture.left,
                plan.leftEyeTexture.top,
                plan.leftEyeTexture.right,
                plan.leftEyeTexture.bottom,
                plan.leftEyeTexture.rotation.ordinal
                    .toFloat(),
                plan.rightEyeTexture.left,
                plan.rightEyeTexture.top,
                plan.rightEyeTexture.right,
                plan.rightEyeTexture.bottom,
                plan.rightEyeTexture.rotation.ordinal
                    .toFloat(),
                projectionView.yawDegrees,
                projectionView.pitchDegrees,
                projectionView.rollDegrees,
                projectionView.zoom,
                sourceColorInfo.transfer.appleMetalTransferCode,
                sourceColorInfo.matrix.appleMetalMatrixCode,
                if (outputDynamicRange.isHdrOutput) 1f else 0f,
                sourcePeak,
                if (isTenBit) 1f else 0f,
                if (fullRange) 1f else 0f,
            )
        val hdr10Plus =
            FloatArray(IOS_HDR10_PLUS_PARAMETER_COUNT).also { values ->
                if (hdr10PlusCurve != null) {
                    values[0] = 1f
                    values[1] = hdr10PlusCurve.sourcePeakNits
                    hdr10PlusCurve.normalizedOutputLuminance.copyInto(values, destinationOffset = 2)
                }
            }
        return base + hdr10Plus + floatArrayOf(sourceColorInfo.appleMetalPrimariesCode)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun attachOutput(
        newItem: AVPlayerItem,
        pixelFormat: UInt,
    ) {
        detachOutput()
        try {
            val attributes: Map<Any?, Any?> =
                mapOf(
                    "PixelFormatType" to pixelFormat.toLong(),
                    "MetalCompatibility" to true,
                    "IOSurfaceProperties" to emptyMap<Any?, Any?>(),
                )
            val newOutput = AVPlayerItemVideoOutput(pixelBufferAttributes = attributes)
            newOutput.suppressesPlayerRendering = true
            newItem.addOutput(newOutput)
            newOutput.requestNotificationOfMediaDataChangeWithAdvanceInterval(IOS_VIDEO_OUTPUT_ADVANCE_SECONDS)
            item = newItem
            output = newOutput
            requestedPixelFormat = pixelFormat
            missingFrameCount = 0
            failureReported = false
        } catch (error: Throwable) {
            reportRendererFailure("AVPlayerItemVideoOutput setup failed: ${error.message ?: error}")
        }
    }

    private fun detachOutput() {
        output?.suppressesPlayerRendering = false
        val oldItem = item
        val oldOutput = output
        if (oldItem != null && oldOutput != null) oldItem.removeOutput(oldOutput)
        latestPixelBuffer?.let(::CVPixelBufferRelease)
        latestPixelBuffer = null
        latestHdr10PlusCurve = null
        lastObservedHdr10PlusInfo = null
        item = null
        output = null
        requestedPixelFormat = null
        missingFrameCount = 0
    }

    private fun configureLayerForOutput(dynamicRange: VideoDynamicRange) {
        val layer = view.layer as? CAMetalLayer ?: return
        val hdr = dynamicRange.isHdrOutput
        layer.pixelFormat = MTLPixelFormatRGBA16Float
        layer.colorspace = if (hdr) hdrColorSpace else sdrColorSpace
        layer.configureAppleDynamicRange(
            hdr = hdr,
            contentHeadroom = if (hdr) sourceContentHeadroom() else 1.0,
        )
    }

    private fun sourceContentHeadroom(): Double {
        val sourcePeakNits =
            (
                sourceColorInfo.masteringDisplay?.maxLuminanceNits
                    ?: sourceColorInfo.contentLightLevel?.maxContentLightLevelNits?.toFloat()
                    ?: IOS_DEFAULT_HDR_PEAK_NITS
            ).coerceIn(IOS_MIN_HDR_PEAK_NITS, IOS_MAX_HDR_PEAK_NITS)
        return (sourcePeakNits / IOS_SDR_REFERENCE_WHITE_NITS)
            .coerceIn(1.0, IOS_MAX_CONTENT_HEADROOM)
    }

    private fun resetConfirmation() {
        configurationGeneration++
        configuredOutput = VideoDynamicRange.UNKNOWN
        latestHdr10PlusCurve = null
        failureReported = false
        missingFrameCount = 0
    }

    private fun requiresHdr10PlusMetadata(): Boolean =
        sourceColorInfo.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
            plannedMetadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER

    private fun hdr10PlusTargetPeakNits(view: MTKView): Double {
        if (!outputDynamicRange.isHdrOutput) return IOS_SDR_REFERENCE_WHITE_NITS
        displayPeakLuminanceNits
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { return it.toDouble() }
        val screen = view.window?.screen
        val headroom = screen?.currentEDRHeadroom ?: screen?.potentialEDRHeadroom ?: 1.0
        return (headroom.coerceAtLeast(1.0) * IOS_SDR_REFERENCE_WHITE_NITS)
            .coerceAtMost(IOS_MAX_HDR_PEAK_NITS.toDouble())
    }

    private fun reportHdrUnavailable(detail: String) {
        if (failureReported) return
        failureReported = true
        view.paused = true
        val failedRange = outputDynamicRange
        onHdrUnavailable(failedRange, detail)
    }

    private fun reportHdr10PlusUnavailable(detail: String) {
        if (failureReported) return
        failureReported = true
        view.paused = true
        onHdrUnavailable(VideoDynamicRange.HDR10_PLUS, detail)
    }

    private fun reportRendererFailure(detail: String) {
        if (failureReported) return
        if (outputDynamicRange.isHdrOutput) {
            reportHdrUnavailable(detail)
        } else {
            failureReported = true
            view.paused = true
            onError(detail)
        }
    }

    companion object {
        @Suppress("ReturnCount")
        fun create(): AppleMetalProjectionRendererCreation {
            val device =
                MTLCreateSystemDefaultDevice()
                    ?: return AppleMetalProjectionRendererCreation(
                        renderer = null,
                        failureDetail = "No Metal device is available for projected video.",
                    )
            val commandQueue = device.newCommandQueue()
            if (commandQueue == null) {
                return AppleMetalProjectionRendererCreation(
                    renderer = null,
                    failureDetail = "Metal could not create a video command queue.",
                )
            }
            // Validate command-queue creation before constructing the renderer, whose own queue is retained.
            commandQueue.label = "KMediaPlayer projection validation"

            val libraryResult = compileAppleProjectionLibrary(device)
            val library =
                libraryResult.first
                    ?: return AppleMetalProjectionRendererCreation(
                        renderer = null,
                        failureDetail = libraryResult.second,
                    )
            val vertex = library.newFunctionWithName("projection_vertex")
            val fragment = library.newFunctionWithName("projection_fragment")
            if (vertex == null || fragment == null) {
                return AppleMetalProjectionRendererCreation(
                    renderer = null,
                    failureDetail = "The Metal projection shader entry points are unavailable.",
                )
            }
            val pipelineDescriptor =
                MTLRenderPipelineDescriptor().apply {
                    vertexFunction = vertex
                    fragmentFunction = fragment
                    colorAttachments.objectAtIndexedSubscript(0u).pixelFormat = MTLPixelFormatRGBA16Float
                }
            val pipelineResult = createAppleProjectionPipeline(device, pipelineDescriptor)
            val pipeline =
                pipelineResult.first
                    ?: return AppleMetalProjectionRendererCreation(
                        renderer = null,
                        failureDetail = pipelineResult.second,
                    )
            val textureCache =
                createAppleMetalTextureCache(device)
                    ?: return AppleMetalProjectionRendererCreation(
                        renderer = null,
                        failureDetail = "CoreVideo could not create a Metal texture cache.",
                    )
            val gamutLut =
                createAppleIctcpGamutLut(device)
                    ?: return AppleMetalProjectionRendererCreation(
                        renderer = null,
                        failureDetail = "Metal could not create the ICtCp gamut-mapping LUT.",
                    )
            val hdrColorSpace = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearITUR_2020)
            val sdrColorSpace = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearSRGB)
            if (hdrColorSpace == null || sdrColorSpace == null) {
                CFRelease(textureCache)
                hdrColorSpace?.let(::CFRelease)
                sdrColorSpace?.let(::CFRelease)
                return AppleMetalProjectionRendererCreation(
                    renderer = null,
                    failureDetail = "The required extended-linear Apple color spaces are unavailable.",
                )
            }
            val view = MTKView(frame = cValue(), device = device)
            return AppleMetalProjectionRendererCreation(
                renderer =
                    AppleMetalProjectionRenderer(
                        view = view,
                        device = device,
                        pipeline = pipeline,
                        textureCache = textureCache,
                        gamutLut = gamutLut,
                        hdrColorSpace = hdrColorSpace,
                        sdrColorSpace = sdrColorSpace,
                    ),
            )
        }
    }
}

private fun createAppleIctcpGamutLut(device: MTLDeviceProtocol): MTLTextureProtocol? {
    val edge = IctcpGamutLut3D.DEFAULT_EDGE
    val descriptor =
        MTLTextureDescriptor().apply {
            textureType = MTLTextureType3D
            pixelFormat = MTLPixelFormatRGBA32Float
            width = edge.toULong()
            height = edge.toULong()
            depth = edge.toULong()
            mipmapLevelCount = 1u
            usage = MTLTextureUsageShaderRead
        }
    val texture = device.newTextureWithDescriptor(descriptor) ?: return null
    val values = IctcpGamutLut3D.defaultRgba32f
    val bytesPerTexel = Float.SIZE_BYTES * IctcpGamutLut3D.CHANNEL_COUNT
    values.usePinned { pinned ->
        texture.replaceRegion(
            region = MTLRegionMake3D(0u, 0u, 0u, edge.toULong(), edge.toULong(), edge.toULong()),
            mipmapLevel = 0u,
            slice = 0u,
            withBytes = pinned.addressOf(0),
            bytesPerRow = (edge * bytesPerTexel).toULong(),
            bytesPerImage = (edge * edge * bytesPerTexel).toULong(),
        )
    }
    return texture
}

private data class AppleMetalFrameTextures(
    val lumaRef: CVMetalTextureRef,
    val chromaRef: CVMetalTextureRef,
    val lumaTexture: MTLTextureProtocol,
    val chromaTexture: MTLTextureProtocol,
) {
    fun release() {
        CVBufferRelease(lumaRef)
        CVBufferRelease(chromaRef)
    }
}

private fun compileAppleProjectionLibrary(
    device: MTLDeviceProtocol,
): Pair<platform.Metal.MTLLibraryProtocol?, String?> =
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val library = device.newLibraryWithSource(APPLE_METAL_PROJECTION_SHADER, options = null, error = error.ptr)
        library to (error.value?.localizedDescription ?: "Metal could not compile the projection shader.")
    }

private fun createAppleProjectionPipeline(
    device: MTLDeviceProtocol,
    descriptor: MTLRenderPipelineDescriptor,
): Pair<MTLRenderPipelineStateProtocol?, String?> =
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val pipeline = device.newRenderPipelineStateWithDescriptor(descriptor, error.ptr)
        pipeline to (error.value?.localizedDescription ?: "Metal could not create the projection pipeline.")
    }

private fun createAppleMetalTextureCache(device: MTLDeviceProtocol): CVMetalTextureCacheRef? =
    memScoped {
        val cache = alloc<CVMetalTextureCacheRefVar>()

        @Suppress("UNCHECKED_CAST")
        val result =
            CVMetalTextureCacheCreate(
                null,
                null,
                device as objcnames.protocols.MTLDeviceProtocol,
                null,
                cache.ptr,
            )
        cache.value.takeIf { result == 0 }
    }

internal data class AppleHdr10PlusFrameMetadata(
    val info: Hdr10PlusInfo,
    val toneCurve: Hdr10PlusToneCurve?,
)

internal fun CVPixelBufferRef.hdr10PlusFrameMetadata(
    presentationTimeUs: Long,
    displayPeakNits: Double,
): AppleHdr10PlusFrameMetadata? {
    val attachment =
        CVBufferGetAttachment(
            buffer = this,
            key = kCMSampleAttachmentKey_HDR10PlusPerFrameData,
            attachmentMode = null,
        ) ?: return null
    val data: CFDataRef = attachment.reinterpret()
    val length = CFDataGetLength(data).toInt()
    if (length !in 1..IOS_MAX_HDR10_PLUS_PAYLOAD_BYTES) return null
    val bytes = CFDataGetBytePtr(data) ?: return null
    val payload = ByteArray(length) { index -> bytes[index].toByte() }
    val metadata =
        when (val parsed = Hdr10PlusMetadataParser.parse(payload, presentationTimeUs.coerceAtLeast(0L))) {
            is Hdr10PlusParseResult.Success -> parsed.metadata
            is Hdr10PlusParseResult.Invalid -> return null
        }
    return AppleHdr10PlusFrameMetadata(
        info =
            Hdr10PlusInfo(
                applicationIdentifier = metadata.applicationIdentifier,
                applicationVersion = metadata.applicationVersion,
                hasPerFrameMetadata = true,
            ),
        toneCurve = metadata.toToneCurve(displayPeakNits),
    )
}

internal fun Double.secondsToMicroseconds(): Long =
    if (isFinite() && this >= 0.0) {
        (this * MICROSECONDS_PER_SECOND).toLong()
    } else {
        0L
    }

private val VideoDynamicRange.isHdrOutput: Boolean
    get() = this == VideoDynamicRange.HDR10 || this == VideoDynamicRange.HLG

private val VideoColorTransfer.appleMetalTransferCode: Float
    get() =
        when (this) {
            VideoColorTransfer.PQ -> 1f
            VideoColorTransfer.HLG -> 2f
            VideoColorTransfer.SRGB -> 3f
            VideoColorTransfer.LINEAR -> 4f
            else -> 0f
        }

private val VideoColorInfo.appleMetalPrimariesCode: Float
    get() =
        when (primaries) {
            VideoColorPrimaries.BT2020 -> 0f
            VideoColorPrimaries.BT709 -> 1f
            VideoColorPrimaries.DISPLAY_P3 -> 2f
            VideoColorPrimaries.BT601_525 -> 3f
            VideoColorPrimaries.BT601_625 -> 4f
            VideoColorPrimaries.UNKNOWN ->
                when (matrix) {
                    VideoColorMatrix.BT2020_NCL, VideoColorMatrix.BT2020_CL, VideoColorMatrix.ICTCP -> 0f
                    VideoColorMatrix.BT601 -> 4f
                    else -> 1f
                }
        }

private val VideoColorMatrix.appleMetalMatrixCode: Float
    get() =
        when (this) {
            VideoColorMatrix.BT2020_NCL, VideoColorMatrix.BT2020_CL -> 1f
            VideoColorMatrix.BT601 -> 2f
            else -> 0f
        }

private const val IOS_METAL_TARGET_FPS = 60L
private const val IOS_METAL_QUAD_VERTEX_COUNT = 4
private const val IOS_EXPECTED_YUV_PLANES = 2
private const val IOS_METAL_MISSING_FRAME_LIMIT = 600
private const val IOS_VIDEO_OUTPUT_ADVANCE_SECONDS = 0.03
private const val IOS_DEFAULT_HDR_PEAK_NITS = 1_000f
private const val IOS_MIN_HDR_PEAK_NITS = 100f
private const val IOS_MAX_HDR_PEAK_NITS = 10_000f
private const val IOS_SDR_REFERENCE_WHITE_NITS = 100.0
private const val IOS_MAX_CONTENT_HEADROOM = IOS_MAX_HDR_PEAK_NITS / IOS_SDR_REFERENCE_WHITE_NITS
private const val IOS_HDR10_PLUS_PARAMETER_COUNT = 2 + HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT
private const val IOS_MAX_HDR10_PLUS_PAYLOAD_BYTES = 1_024
private const val MICROSECONDS_PER_SECOND = 1_000_000.0

private const val APPLE_METAL_PROJECTION_SHADER = """
#include <metal_stdlib>
using namespace metal;

struct VertexOutput {
    float4 position [[position]];
    float2 uv;
};

vertex VertexOutput projection_vertex(uint vertex_id [[vertex_id]]) {
    const float2 positions[4] = {
        float2(-1.0, -1.0), float2(1.0, -1.0),
        float2(-1.0, 1.0), float2(1.0, 1.0)
    };
    VertexOutput output;
    const float2 position = positions[vertex_id];
    output.position = float4(position, 0.0, 1.0);
    output.uv = float2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
    return output;
}

constant float PI = 3.14159265358979323846;
constant float CAMERA_FOV_DEGREES = 95.0;
constexpr sampler video_sampler(coord::normalized, address::clamp_to_edge, filter::linear);

float2 rotate_uv(float2 uv, float rotation) {
    if (rotation > 0.5 && rotation < 1.5) return float2(1.0 - uv.y, uv.x);
    if (rotation >= 1.5 && rotation < 2.5) return float2(1.0 - uv.x, 1.0 - uv.y);
    if (rotation >= 2.5) return float2(uv.y, 1.0 - uv.x);
    return uv;
}

float3 yuv_to_rgb(
    float2 source_uv,
    texture2d<float, access::sample> luma,
    texture2d<float, access::sample> chroma,
    constant float *p
) {
    const bool ten_bit = p[22] > 0.5;
    const bool full_range = p[23] > 0.5;
    const float maximum_code = ten_bit ? 1023.0 : 255.0;
    const float code_scale = ten_bit ? (65535.0 / 64.0) : 255.0;
    const float y_code = luma.sample(video_sampler, source_uv).r * code_scale;
    const float2 c_code = chroma.sample(video_sampler, source_uv).rg * code_scale;
    float y;
    float2 cbcr;
    if (full_range) {
        y = y_code / maximum_code;
        cbcr = c_code / maximum_code - 0.5;
    } else if (ten_bit) {
        y = (y_code - 64.0) / 876.0;
        cbcr = (c_code - 512.0) / 896.0;
    } else {
        y = (y_code - 16.0) / 219.0;
        cbcr = (c_code - 128.0) / 224.0;
    }
    y = max(y, 0.0);
    const float cb = cbcr.x;
    const float cr = cbcr.y;
    if (p[19] > 0.5 && p[19] < 1.5) {
        return float3(y + 1.4746 * cr, y - 0.164553 * cb - 0.571353 * cr, y + 1.8814 * cb);
    }
    if (p[19] >= 1.5) {
        return float3(y + 1.4020 * cr, y - 0.344136 * cb - 0.714136 * cr, y + 1.7720 * cb);
    }
    return float3(y + 1.5748 * cr, y - 0.187324 * cb - 0.468124 * cr, y + 1.8556 * cb);
}

float pq_eotf(float signal) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    const float value = pow(clamp(signal, 0.0, 1.0), 1.0 / m2);
    return pow(max(value - c1, 0.0) / max(c2 - c3 * value, 0.000001), 1.0 / m1) * 10000.0;
}

float pq_oetf(float nits) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    const float value = pow(clamp(nits, 0.0, 10000.0) / 10000.0, m1);
    return pow((c1 + c2 * value) / (1.0 + c3 * value), m2);
}

float hlg_inverse_oetf(float signal) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    return signal <= 0.5 ? signal * signal / 3.0 : (exp((signal - c) / a) + b) / 12.0;
}

float sdr_inverse_oetf(float signal) {
    signal = max(signal, 0.0);
    return signal <= 0.081 ? signal / 4.5 : pow((signal + 0.099) / 1.099, 1.0 / 0.45);
}

float srgb_inverse_eotf(float signal) {
    signal = max(signal, 0.0);
    return signal <= 0.04045 ? signal / 12.92 : pow((signal + 0.055) / 1.055, 2.4);
}

float3 source_primaries_to_bt2020(float3 color, float primaries) {
    if (primaries < 0.5) return color;
    if (primaries < 1.5) {
        return float3(
            0.627403896 * color.r + 0.329283038 * color.g + 0.043313066 * color.b,
            0.069097289 * color.r + 0.919540395 * color.g + 0.011362316 * color.b,
            0.016391439 * color.r + 0.088013308 * color.g + 0.895595253 * color.b
        );
    }
    if (primaries < 2.5) {
        return float3(
            0.753833034 * color.r + 0.198597369 * color.g + 0.047569597 * color.b,
            0.045743849 * color.r + 0.941777220 * color.g + 0.012478931 * color.b,
            -0.001210340 * color.r + 0.017601717 * color.g + 0.983608623 * color.b
        );
    }
    if (primaries < 3.5) {
        return float3(
            0.595254206 * color.r + 0.349313920 * color.g + 0.055431874 * color.b,
            0.081243662 * color.r + 0.891503296 * color.g + 0.027253043 * color.b,
            0.015512341 * color.r + 0.081911642 * color.g + 0.902576017 * color.b
        );
    }
    return float3(
        0.655036777 * color.r + 0.302160965 * color.g + 0.042802258 * color.b,
        0.072140556 * color.r + 0.916631129 * color.g + 0.011228315 * color.b,
        0.017113370 * color.r + 0.097853470 * color.g + 0.885033160 * color.b
    );
}

float3 gamut_map_to_bt709(
    float3 normalized_bt2020,
    texture3d<float, access::sample> gamut_lut
) {
    const float lut_edge = float(gamut_lut.get_width());
    const float3 coordinates = (clamp(normalized_bt2020, 0.0, 1.0) * (lut_edge - 1.0) + 0.5) / lut_edge;
    return gamut_lut.sample(video_sampler, coordinates).rgb;
}

float bt2390(float nits, float source_peak, float target_peak) {
    const float source_code = pq_oetf(source_peak);
    const float normalized_target = clamp(pq_oetf(target_peak) / max(source_code, 0.000001), 0.0, 1.0);
    const float knee = clamp(1.5 * normalized_target - 0.5, 0.0, 1.0);
    const float input_code = clamp(pq_oetf(nits) / max(source_code, 0.000001), 0.0, 1.0);
    if (input_code <= knee || knee >= 1.0) return min(nits, target_peak);
    const float t = clamp((input_code - knee) / max(1.0 - knee, 0.000001), 0.0, 1.0);
    const float t2 = t * t;
    const float t3 = t2 * t;
    const float output_code =
        (2.0 * t3 - 3.0 * t2 + 1.0) * knee +
        (t3 - 2.0 * t2 + t) * (1.0 - knee) +
        (-2.0 * t3 + 3.0 * t2) * normalized_target;
    return min(pq_eotf(clamp(output_code * source_code, 0.0, 1.0)), target_peak);
}

float hdr10_plus_curve_sample(int index, constant float *p) {
    return p[26 + clamp(index, 0, 32)] * 10000.0;
}

float3 apply_hdr10_plus(float3 linear_nits, constant float *p) {
    if (p[24] < 0.5) return linear_nits;
    const float luminance = max(dot(linear_nits, float3(0.2627, 0.6780, 0.0593)), 0.0);
    const float normalized = clamp(luminance / max(p[25], 1.0), 0.0, 1.0);
    const float curve_position = normalized * 32.0;
    const int lower = int(floor(curve_position));
    const int upper = min(lower + 1, 32);
    const float mapped = mix(
        hdr10_plus_curve_sample(lower, p),
        hdr10_plus_curve_sample(upper, p),
        fract(curve_position)
    );
    const float scale = luminance > 0.000001 ? mapped / luminance : 0.0;
    return max(linear_nits * scale, float3(0.0));
}

float3 color_manage(
    float3 encoded,
    texture3d<float, access::sample> gamut_lut,
    constant float *p
) {
    if (p[18] < 0.5 || p[18] >= 2.5) {
        float3 linear_source;
        if (p[18] < 0.5) {
            linear_source = float3(
                sdr_inverse_oetf(encoded.r),
                sdr_inverse_oetf(encoded.g),
                sdr_inverse_oetf(encoded.b)
            );
        } else if (p[18] < 3.5) {
            linear_source = float3(
                srgb_inverse_eotf(encoded.r),
                srgb_inverse_eotf(encoded.g),
                srgb_inverse_eotf(encoded.b)
            );
        } else {
            linear_source = max(encoded, float3(0.0));
        }
        const float3 linear_bt2020 = source_primaries_to_bt2020(linear_source, p[59]);
        if (p[20] > 0.5) return max(linear_bt2020, float3(0.0));
        return gamut_map_to_bt709(linear_bt2020, gamut_lut);
    }
    float3 linear_nits;
    if (p[18] < 1.5) {
        linear_nits = float3(pq_eotf(encoded.r), pq_eotf(encoded.g), pq_eotf(encoded.b));
    } else {
        linear_nits = float3(
            hlg_inverse_oetf(encoded.r),
            hlg_inverse_oetf(encoded.g),
            hlg_inverse_oetf(encoded.b)
        );
        linear_nits = p[21] * pow(max(linear_nits, float3(0.0)), float3(1.2));
    }
    linear_nits = source_primaries_to_bt2020(linear_nits, p[59]);
    linear_nits = apply_hdr10_plus(linear_nits, p);
    if (p[20] > 0.5) return max(linear_nits / 100.0, 0.0);

    const float luminance = max(dot(linear_nits, float3(0.2627, 0.6780, 0.0593)), 0.000001);
    const float mapped = p[24] > 0.5
        ? min(luminance, 100.0)
        : bt2390(luminance, max(p[21], 100.0), 100.0);
    const float3 normalized_bt2020 = linear_nits * (mapped / luminance) / 100.0;
    return gamut_map_to_bt709(normalized_bt2020, gamut_lut);
}

float3 ray_for_screen_uv(float2 screen_uv, constant float *p) {
    const float2 position = float2(screen_uv.x * 2.0 - 1.0, 1.0 - screen_uv.y * 2.0);
    const float tan_half_fov = tan(CAMERA_FOV_DEGREES * PI / 360.0 / max(p[17], 0.01));
    float3 direction = normalize(float3(position.x * p[3] * tan_half_fov, position.y * tan_half_fov, -1.0));
    const float yaw = p[14] * PI / 180.0;
    const float pitch = p[15] * PI / 180.0;
    const float roll = p[16] * PI / 180.0;
    direction = float3(cos(yaw) * direction.x + sin(yaw) * direction.z, direction.y,
        -sin(yaw) * direction.x + cos(yaw) * direction.z);
    direction = float3(direction.x, cos(pitch) * direction.y - sin(pitch) * direction.z,
        sin(pitch) * direction.y + cos(pitch) * direction.z);
    return normalize(float3(cos(roll) * direction.x - sin(roll) * direction.y,
        sin(roll) * direction.x + cos(roll) * direction.y, direction.z));
}

float2 eac_face_uv(float sc, float tc, float cell_x, float cell_y) {
    const float2 local = float2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));
    return float2((cell_x + local.x) / 3.0, (cell_y + local.y) / 2.0);
}

float2 eac_uv(float3 direction) {
    const float3 a = abs(direction);
    if (a.z >= a.x && a.z >= a.y) {
        if (direction.z < 0.0) return eac_face_uv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);
        return eac_face_uv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
    }
    if (a.x >= a.y) {
        if (direction.x > 0.0) return eac_face_uv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);
        return eac_face_uv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
    }
    if (direction.y > 0.0) return eac_face_uv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);
    return eac_face_uv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
}

float4 sample_local(
    float2 local_uv,
    float eye,
    texture2d<float, access::sample> luma,
    texture2d<float, access::sample> chroma,
    texture3d<float, access::sample> gamut_lut,
    constant float *p
) {
    if (any(local_uv < 0.0) || any(local_uv > 1.0)) return float4(0.0, 0.0, 0.0, 1.0);
    float4 window = float4(p[4], p[5], p[6], p[7]);
    float rotation = p[8];
    if (eye > 0.5) {
        window = float4(p[9], p[10], p[11], p[12]);
        rotation = p[13];
    }
    const float2 rotated = rotate_uv(local_uv, rotation);
    const float2 source_uv = mix(window.xy, window.zw, rotated);
    return float4(color_manage(yuv_to_rgb(source_uv, luma, chroma, p), gamut_lut, p), 1.0);
}

fragment float4 projection_fragment(
    VertexOutput input [[stage_in]],
    texture2d<float, access::sample> luma [[texture(0)]],
    texture2d<float, access::sample> chroma [[texture(1)]],
    texture3d<float, access::sample> gamut_lut [[texture(2)]],
    constant float *p [[buffer(0)]]
) {
    float2 screen_uv = input.uv;
    float eye = 0.0;
    if (p[2] > 0.5) {
        if (screen_uv.x < 0.5) screen_uv.x *= 2.0;
        else { screen_uv.x = (screen_uv.x - 0.5) * 2.0; eye = 1.0; }
    }
    if (p[0] < 0.5) return sample_local(screen_uv, eye, luma, chroma, gamut_lut, p);
    const float3 direction = ray_for_screen_uv(screen_uv, p);
    if (p[0] < 2.5) {
        const float horizontal_fov = max(p[1], 1.0) * PI / 180.0;
        const float yaw = atan2(direction.x, -direction.z);
        const float pitch = asin(clamp(direction.y, -1.0, 1.0));
        if (abs(yaw) > horizontal_fov * 0.5) return float4(0.0, 0.0, 0.0, 1.0);
        return sample_local(float2(yaw / horizontal_fov + 0.5, 0.5 - pitch / PI), eye, luma, chroma, gamut_lut, p);
    }
    if (p[0] < 6.5) {
        const float max_theta = max(p[1], 1.0) * PI / 360.0;
        const float theta = acos(clamp(-direction.z, -1.0, 1.0));
        if (theta > max_theta) return float4(0.0, 0.0, 0.0, 1.0);
        const float phi = atan2(direction.y, direction.x);
        const float radius = theta / max_theta * 0.5;
        return sample_local(float2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius), eye, luma, chroma, gamut_lut, p);
    }
    return sample_local(eac_uv(direction), eye, luma, chroma, gamut_lut, p);
}
"""
