import AVFoundation
import AppKit
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import IOSurface
import Metal
import QuartzCore

private struct HdrMetalTextureWindow: Equatable {
    let left: Float
    let top: Float
    let right: Float
    let bottom: Float
    let rotation: Float
}

private struct HdrMetalProjectionConfiguration: Equatable {
    let projectionType: Float
    let fieldOfView: Float
    let stereo: Float
    let leftEye: HdrMetalTextureWindow
    let rightEye: HdrMetalTextureWindow
    let yaw: Float
    let pitch: Float
    let roll: Float
    let zoom: Float
    let transfer: Float
    let matrix: Float
    let primaries: Float
    let outputHdr: Bool
    let sourcePeakNits: Float
    let displayPeakNits: Float
    let appliesHdr10Plus: Bool
    let tenBit: Bool
    let fullRange: Bool

    var colorKey: String {
        "\(transfer):\(matrix):\(primaries):\(outputHdr):\(sourcePeakNits):\(displayPeakNits):\(appliesHdr10Plus):\(tenBit):\(fullRange)"
    }

    static func parse(_ serialized: String) -> HdrMetalProjectionConfiguration? {
        let values = Dictionary(
            uniqueKeysWithValues: serialized.split(separator: ";").compactMap { entry in
                let pair = entry.split(separator: "=", maxSplits: 1).map(String.init)
                return pair.count == 2 ? (pair[0], pair[1]) : nil
            }
        )
        guard values["enabled"] == "1",
              let projectionType = values.float("type"),
              let fieldOfView = values.float("fov"),
              let stereo = values.float("stereo"),
              let leftEye = HdrMetalTextureWindow.parse(values["left"]),
              let rightEye = HdrMetalTextureWindow.parse(values["right"]),
              let yaw = values.float("yaw"),
              let pitch = values.float("pitch"),
              let roll = values.float("roll"),
              let zoom = values.float("zoom"),
              let transfer = values.float("transfer"),
              let matrix = values.float("matrix"),
              let primaries = values.float("primaries"),
              let sourcePeak = values.float("peak"),
              let displayPeak = values.float("displayPeak")
        else {
            return nil
        }
        return HdrMetalProjectionConfiguration(
            projectionType: projectionType,
            fieldOfView: fieldOfView,
            stereo: stereo,
            leftEye: leftEye,
            rightEye: rightEye,
            yaw: yaw,
            pitch: pitch,
            roll: roll,
            zoom: max(zoom, 0.01),
            transfer: transfer,
            matrix: matrix,
            primaries: primaries,
            outputHdr: values["outputHdr"] == "1",
            sourcePeakNits: min(max(sourcePeak, 100), 10_000),
            displayPeakNits: min(max(displayPeak, 1), 10_000),
            appliesHdr10Plus: values["hdr10Plus"] == "1",
            tenBit: values["tenBit"] == "1",
            fullRange: values["fullRange"] == "1"
        )
    }
}

private struct Hdr10PlusToneCurve {
    let sourcePeakNits: Float
    let normalizedOutputLuminance: [Float]
}

private extension Dictionary where Key == String, Value == String {
    func float(_ key: String) -> Float? {
        guard let value = self[key], let parsed = Float(value), parsed.isFinite else { return nil }
        return parsed
    }
}

private extension HdrMetalTextureWindow {
    static func parse(_ value: String?) -> HdrMetalTextureWindow? {
        guard let components = value?.split(separator: ",").compactMap({ Float($0) }),
              components.count == 5,
              components.allSatisfy(\.isFinite)
        else {
            return nil
        }
        return HdrMetalTextureWindow(
            left: components[0],
            top: components[1],
            right: components[2],
            bottom: components[3],
            rotation: components[4]
        )
    }
}

final class HdrMetalVideoRenderer {
    static var isAvailable: Bool { MTLCreateSystemDefaultDevice() != nil }

    let layer: CAMetalLayer

    private let device: MTLDevice
    private let defaultCommandQueue: MTLCommandQueue
    private var commandQueue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let textureCache: CVMetalTextureCache
    private let gamutLut: MTLTexture
    private let hdrColorSpace: CGColorSpace
    private let sdrColorSpace: CGColorSpace
    // Bound both CAMetalLayer and TextureView submissions. TextureView renders into one shared
    // IOSurface and therefore does not get CAMetalLayer's drawable back-pressure for free. Without
    // this gate an 8K/60 Hz timer can enqueue decoded CVPixelBuffers faster than Metal consumes
    // them, retaining hundreds of megabytes per pending frame.
    private let frameInFlightSemaphore = DispatchSemaphore(value: 2)
    // CoreVideo textures must not be destroyed from Metal's IOGPU completion callback. Retire
    // them on a dedicated serial queue instead of the AppKit main queue: the latter can stop
    // draining during window tracking and previously accumulated an unbounded 8K-frame backlog.
    private let frameRetirementQueue = DispatchQueue(
        label: "io.github.kdroidfilter.composemediaplayer.macos.metal-frame-retirement",
        qos: .userInteractive
    )
    private weak var item: AVPlayerItem?
    private var output: AVPlayerItemVideoOutput?
    private var configuration: HdrMetalProjectionConfiguration?
    private var requestedPixelFormat: OSType = 0
    private var renderTimer: Timer?
    private var contentScaleMode: Int32 = HdrMetalScaleMode.fit.rawValue
    private var renderedFrameCount = 0
    private var skippedFrameCount = 0
    private var generation: UInt64 = 0
    private var failureDetail: String?
    private let textureOutputStateLock = NSLock()
    private var textureOutputEnabled = false
    private var textureOutputSurface: IOSurfaceRef?
    private var textureOutputTexture: MTLTexture?
    private var textureOutputWidth = 0
    private var textureOutputHeight = 0
    private var textureOutputFrameSerial: UInt64 = 0
    private var requestedViewportWidth = 0
    private var requestedViewportHeight = 0
    // A viewport transition may happen while AVPlayerItemVideoOutput reports no *new* decoded
    // frame (paused playback and AppKit fullscreen transitions are the common cases). Track the
    // requested geometry separately so the display timer can redraw the current frame once,
    // without doing any decode/Metal work synchronously from an AppKit layout callback.
    private var requestedViewportRevision: UInt64 = 0
    private var completedViewportRevision: UInt64 = 0

    /** Called only after a decoded PQ frame contains a fully validated ST 2094-40 payload. */
    var onHdr10PlusObserved: (() -> Void)?

    /** Reports frames that completed on the GPU so public playback diagnostics cover TextureView. */
    var onFrameRendered: ((CMTime, CFTimeInterval) -> Void)?

    var hasRenderedFrame: Bool { renderedFrameCount > 0 && failureDetail == nil }
    var rendererFailureDetail: String? { failureDetail }

    init?() {
        guard let device = MTLCreateSystemDefaultDevice() else {
            hdrMetalLog("No Metal device is available for the macOS projection renderer.")
            return nil
        }
        guard let commandQueue = device.makeCommandQueue() else {
            hdrMetalLog("Metal could not create the macOS projection command queue.")
            return nil
        }
        let library: MTLLibrary
        do {
            library = try device.makeLibrary(source: macMetalProjectionShader, options: nil)
        } catch {
            hdrMetalLog("Metal projection shader compilation failed: \(error.localizedDescription)")
            return nil
        }
        guard let vertex = library.makeFunction(name: "projection_vertex"),
              let fragment = library.makeFunction(name: "projection_fragment")
        else {
            hdrMetalLog("The macOS Metal projection shader entry points are unavailable.")
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.colorAttachments[0].pixelFormat = .rgba16Float
        let pipeline: MTLRenderPipelineState
        do {
            pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        } catch {
            hdrMetalLog("Metal projection pipeline creation failed: \(error.localizedDescription)")
            return nil
        }

        var cache: CVMetalTextureCache?
        guard CVMetalTextureCacheCreate(nil, nil, device, nil, &cache) == kCVReturnSuccess,
              let textureCache = cache
        else {
            hdrMetalLog("CoreVideo could not create the macOS Metal texture cache.")
            return nil
        }
        guard let hdrColorSpace = CGColorSpace(name: CGColorSpace.extendedLinearITUR_2020),
              let sdrColorSpace = CGColorSpace(name: CGColorSpace.extendedLinearSRGB)
        else {
            hdrMetalLog("The required macOS extended-linear color spaces are unavailable.")
            return nil
        }
        guard let gamutLut = HdrMetalVideoRenderer.makeIctcpGamutLut(device: device) else {
            hdrMetalLog("Metal could not create the macOS ICtCp gamut-mapping LUT.")
            return nil
        }

        self.device = device
        self.defaultCommandQueue = commandQueue
        self.commandQueue = commandQueue
        self.pipeline = pipeline
        self.textureCache = textureCache
        self.gamutLut = gamutLut
        self.hdrColorSpace = hdrColorSpace
        self.sdrColorSpace = sdrColorSpace
        self.layer = CAMetalLayer()
        layer.device = device
        layer.pixelFormat = .rgba16Float
        layer.framebufferOnly = true
        layer.isOpaque = true
        layer.colorspace = sdrColorSpace
        layer.wantsExtendedDynamicRangeContent = false
        // During live resize Core Animation may briefly scale the last drawable while the next
        // Metal frame is still in flight. Preserve that drawable's aspect ratio instead of
        // stretching it to the new window bounds.
        layer.contentsGravity = .resizeAspect
        layer.presentsWithTransaction = false
        layer.displaySyncEnabled = true
        // Keep one drawable free so nextDrawable() cannot throttle AppKit when an 8K
        // projection takes longer than a display interval. A late video frame is cheaper
        // than blocking every Compose control and native window-resize event.
        layer.maximumDrawableCount = 3
        layer.backgroundColor = NSColor.black.cgColor
    }

    @discardableResult
    func configure(_ serialized: String) -> Bool {
        guard let next = HdrMetalProjectionConfiguration.parse(serialized) else {
            reportFailure("The macOS Metal projection configuration is invalid.")
            return false
        }
        let previous = configuration
        configuration = next
        let colorChanged = previous?.colorKey != next.colorKey
        let pixelFormatChanged = previous?.tenBit != next.tenBit
        if colorChanged {
            generation &+= 1
            renderedFrameCount = 0
            skippedFrameCount = 0
            failureDetail = nil
            layer.colorspace = next.outputHdr ? hdrColorSpace : sdrColorSpace
            layer.wantsExtendedDynamicRangeContent = next.outputHdr
        }
        if pixelFormatChanged, let item = item {
            attach(to: item, force: true)
        } else {
            renderCurrentFrame()
        }
        return true
    }

    func attach(to item: AVPlayerItem, force: Bool = false) {
        guard let configuration = configuration else { return }
        let pixelFormat: OSType = configuration.tenBit
            ? kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        if !force, self.item === item, output != nil, requestedPixelFormat == pixelFormat {
            start()
            return
        }
        detachFromItem()
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: pixelFormat,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            kCVPixelBufferMetalCompatibilityKey as String: true,
        ]
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: attributes)
        output.suppressesPlayerRendering = true
        item.add(output)
        output.requestNotificationOfMediaDataChange(withAdvanceInterval: 0.03)
        self.item = item
        self.output = output
        requestedPixelFormat = pixelFormat
        generation &+= 1
        renderedFrameCount = 0
        skippedFrameCount = 0
        failureDetail = nil
        start()
    }

    func detachFromItem() {
        stop()
        output?.suppressesPlayerRendering = false
        if let item = item, let output = output {
            item.remove(output)
        }
        item = nil
        output = nil
        requestedPixelFormat = 0
        generation &+= 1
        renderedFrameCount = 0
    }

    func setContentScaleMode(_ mode: Int32) {
        contentScaleMode = mode
        switch HdrMetalScaleMode(rawValue: mode) ?? .fit {
        case .fit:
            layer.contentsGravity = .resizeAspect
        case .crop:
            layer.contentsGravity = .resizeAspectFill
        case .fill:
            layer.contentsGravity = .resize
        }
    }

    func setDrawableSize(width: Int32, height: Int32, scale: Double) {
        guard width > 0, height > 0 else { return }
        let backingScale = max(scale, 1.0)
        let logicalSize = CGSize(width: CGFloat(width), height: CGFloat(height))
        layer.bounds = CGRect(origin: .zero, size: logicalSize)
        layer.frame = CGRect(origin: .zero, size: logicalSize)
        layer.contentsScale = backingScale
        layer.drawableSize = CGSize(
            width: logicalSize.width * backingScale,
            height: logicalSize.height * backingScale
        )
    }

    /**
     * Routes rendering into an IOSurface-backed RGBA16Float texture and, when supplied,
     * submits it on the Tao/Skia command queue. Sharing the queue serializes decoder writes
     * with TextureView's GPU snapshot without a CPU readback or a blocking GPU wait.
     */
    @discardableResult
    func setTextureOutput(commandQueuePointer: UnsafeMutableRawPointer?) -> Bool {
        guard let commandQueuePointer = commandQueuePointer else {
            withTextureOutputStateLock {
                textureOutputEnabled = false
            }
            commandQueue = defaultCommandQueue
            releaseTextureOutput()
            return true
        }
        let object = Unmanaged<AnyObject>.fromOpaque(commandQueuePointer).takeUnretainedValue()
        guard let sharedQueue = object as? MTLCommandQueue else {
            reportFailure("The Tao Metal queue pointer is not an MTLCommandQueue.")
            return false
        }
        // MTLCreateSystemDefaultDevice() and Skia may expose distinct Objective-C wrapper
        // identities for the same physical GPU. Metal resources are compatible by registry ID;
        // pointer identity is unnecessarily strict and made a valid Tao queue look foreign.
        guard sharedQueue.device.registryID == device.registryID else {
            reportFailure("The Tao Metal queue belongs to a different GPU device.")
            return false
        }
        commandQueue = sharedQueue
        let needsCurrentFrame = withTextureOutputStateLock {
            textureOutputEnabled = true
            guard requestedViewportWidth > 0, requestedViewportHeight > 0 else { return false }
            requestedViewportRevision &+= 1
            return true
        }
        failureDetail = nil
        if needsCurrentFrame {
            output?.requestNotificationOfMediaDataChange(withAdvanceInterval: 0.03)
        }
        start()
        return true
    }

    func setTextureViewportSize(width: Int32, height: Int32) {
        guard width > 0, height > 0 else { return }
        let didChange = withTextureOutputStateLock {
            let nextWidth = Int(width)
            let nextHeight = Int(height)
            guard requestedViewportWidth != nextWidth || requestedViewportHeight != nextHeight else {
                return false
            }
            requestedViewportWidth = nextWidth
            requestedViewportHeight = nextHeight
            requestedViewportRevision &+= 1
            return true
        }
        if didChange {
            output?.requestNotificationOfMediaDataChange(withAdvanceInterval: 0.03)
        }
        // The 60 Hz render tick adopts the requested size. Releasing and rebuilding the IOSurface
        // synchronously from every Compose layout callback made live resize enter AppKit/Metal
        // recursively and exposed an uninitialised surface to TextureView. The revision above
        // makes the timer redraw the current decoded frame even when playback is paused or
        // AVPlayerItemVideoOutput has no newly decoded frame after a fullscreen transition.
    }

    func textureOutputInfo() -> (surface: IOSurfaceRef, width: Int, height: Int, frameSerial: UInt64)? {
        withTextureOutputStateLock {
            guard textureOutputEnabled,
                  let surface = textureOutputSurface,
                  textureOutputWidth > 0,
                  textureOutputHeight > 0,
                  textureOutputFrameSerial > 0
            else {
                return nil
            }
            return (surface, textureOutputWidth, textureOutputHeight, textureOutputFrameSerial)
        }
    }

    func start() {
        guard renderTimer == nil else { return }
        let timer = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            self?.renderFrame()
        }
        RunLoop.main.add(timer, forMode: .common)
        renderTimer = timer
    }

    func stop() {
        renderTimer?.invalidate()
        renderTimer = nil
    }

    func renderCurrentFrame() {
        guard let item = item else { return }
        renderFrame(
            at: item.currentTime(),
            onlyIfNew: false,
            viewportRevision: pendingTextureViewportRevision()
        )
    }

    private func renderFrame() {
        guard let output = output else { return }
        if let viewportRevision = pendingTextureViewportRevision(), let item = item {
            renderFrame(
                at: item.currentTime(),
                onlyIfNew: false,
                viewportRevision: viewportRevision
            )
            return
        }
        renderFrame(
            at: output.itemTime(forHostTime: CACurrentMediaTime()),
            onlyIfNew: true,
            viewportRevision: nil
        )
    }

    private func renderFrame(
        at itemTime: CMTime,
        onlyIfNew: Bool,
        viewportRevision: UInt64?
    ) {
        guard failureDetail == nil, let output = output else { return }
        // The display timer can run at 60/120 Hz while the asset contains 24/30 fps video.
        // Submitting the same decoded 8K buffer repeatedly wastes GPU time and amplifies memory
        // pressure. Explicit redraws (configuration changes and paused seeks) bypass this check.
        if onlyIfNew && !output.hasNewPixelBuffer(forItemTime: itemTime) {
            return
        }
        var displayTime = CMTime.invalid
        guard let pixelBuffer = output.copyPixelBuffer(
            forItemTime: itemTime,
            itemTimeForDisplay: &displayTime
        ) else {
            skippedFrameCount += 1
            if skippedFrameCount >= 600 {
                reportFailure("AVPlayerItemVideoOutput produced no P010/NV12 projection frame for 10 seconds.")
            }
            return
        }
        skippedFrameCount = 0
        render(
            pixelBuffer,
            itemTime: itemTime,
            hostTime: CACurrentMediaTime(),
            viewportRevision: viewportRevision
        )
    }

    private func render(
        _ pixelBuffer: CVPixelBuffer,
        itemTime: CMTime,
        hostTime: CFTimeInterval,
        viewportRevision: UInt64?
    ) {
        guard CVPixelBufferGetPlaneCount(pixelBuffer) == 2 else {
            reportFailure("AVFoundation returned a non-bi-planar frame to the macOS Metal projection renderer.")
            return
        }
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let tenBit = format == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange ||
            format == kCVPixelFormatType_420YpCbCr10BiPlanarFullRange
        let eightBit = format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
            format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        guard tenBit || eightBit else {
            reportFailure("Unsupported AVFoundation projection pixel format \(format); expected P010 or NV12.")
            return
        }
        guard frameInFlightSemaphore.wait(timeout: .now()) == .success else {
            // This is a video renderer, so dropping a late frame is preferable to retaining an
            // unbounded queue of 8K CVPixelBuffers and eventually stalling the whole process.
            return
        }
        var submittedFrame = false
        defer {
            if !submittedFrame {
                frameInFlightSemaphore.signal()
            }
        }
        guard let commandBuffer = commandQueue.makeCommandBuffer(),
              let configuration = configuration,
              let textures = makePlaneTextures(pixelBuffer, tenBit: tenBit)
        else {
            return
        }
        let drawable: CAMetalDrawable?
        let targetTexture: MTLTexture
        let rendersToTexture = withTextureOutputStateLock { textureOutputEnabled }
        if rendersToTexture {
            // Projection output is viewport-dependent. Waiting for the first real viewport avoids
            // allocating a temporary full-resolution FP16 IOSurface (256 MB for an 8000x4000
            // source) only to replace it on the next Compose layout pass.
            let hasViewport = withTextureOutputStateLock {
                requestedViewportWidth > 0 && requestedViewportHeight > 0
            }
            guard hasViewport else { return }
            let targetSize = textureOutputSize(for: pixelBuffer, configuration: configuration)
            guard let texture = ensureTextureOutput(width: targetSize.width, height: targetSize.height) else {
                return
            }
            drawable = nil
            targetTexture = texture
        } else {
            guard layer.drawableSize.width > 0,
                  layer.drawableSize.height > 0,
                  let nextDrawable = layer.nextDrawable()
            else {
                return
            }
            drawable = nextDrawable
            targetTexture = nextDrawable.texture
        }
        let parsedHdr10Plus: (curve: Hdr10PlusToneCurve?, error: String?) =
            configuration.transfer == hdr10PlusPqTransferCode
                ? Self.parseHdr10PlusToneCurve(pixelBuffer, displayPeakNits: configuration.displayPeakNits)
                : (nil, nil)
        if parsedHdr10Plus.curve != nil {
            onHdr10PlusObserved?()
        }
        let hdr10PlusCurve: Hdr10PlusToneCurve?
        if configuration.appliesHdr10Plus {
            let parsed = parsedHdr10Plus
            guard let curve = parsed.curve else {
                reportFailure("\(hdr10PlusMetadataFailurePrefix) \(parsed.error ?? "Missing per-frame ST 2094-40 metadata.")")
                return
            }
            hdr10PlusCurve = curve
        } else {
            hdr10PlusCurve = nil
        }
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = targetTexture
        descriptor.colorAttachments[0].loadAction = .clear
        descriptor.colorAttachments[0].storeAction = .store
        descriptor.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            reportFailure("Metal could not create the macOS projection command encoder.")
            return
        }
        let fullRange = format == kCVPixelFormatType_420YpCbCr10BiPlanarFullRange ||
            format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        let parameters = makeParameters(
            configuration,
            tenBit: tenBit,
            fullRange: fullRange,
            hdr10PlusCurve: hdr10PlusCurve,
            targetWidth: targetTexture.width,
            targetHeight: targetTexture.height,
            outputScRgb: rendersToTexture
        )
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(textures.luma, index: 0)
        encoder.setFragmentTexture(textures.chroma, index: 1)
        encoder.setFragmentTexture(gamutLut, index: 2)
        parameters.withUnsafeBytes { bytes in
            encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
        if let drawable = drawable {
            commandBuffer.present(drawable)
        }
        let submittedGeneration = generation
        let submittedToTexture = rendersToTexture
        let frameSemaphore = frameInFlightSemaphore
        let retirementQueue = frameRetirementQueue
        let retainedTextureCache = textureCache
        commandBuffer.addCompletedHandler { [weak self, pixelBuffer, textures, retainedTextureCache, frameSemaphore, retirementQueue] completed in
            let completionError = completed.error?.localizedDescription
            // Releasing CVMetalTexture from inside Metal's IOGPU completion callback can re-enter
            // CoreVideo while the command queue is still unwinding. A renderer/surface replacement
            // during fullscreen made that race observable as CVMetalTexture::finalize crashing in
            // CoreFoundation. Retire the heavy frame resources off-main, then enqueue only the
            // tiny state notification on AppKit. Signal after retirement so this queue is the
            // authoritative two-frame memory bound for both native output modes.
            retirementQueue.async { [weak self, pixelBuffer, textures, retainedTextureCache, frameSemaphore] in
                autoreleasepool {
                    _ = pixelBuffer
                    _ = textures
                    _ = retainedTextureCache
                }
                frameSemaphore.signal()
                DispatchQueue.main.async { [weak self] in
                    guard let self = self, self.generation == submittedGeneration else { return }
                    if let completionError = completionError {
                        self.reportFailure("Metal projection command failed: \(completionError)")
                    } else {
                        self.renderedFrameCount += 1
                        if submittedToTexture {
                            self.withTextureOutputStateLock {
                                self.textureOutputFrameSerial &+= 1
                                if let viewportRevision = viewportRevision {
                                    self.completedViewportRevision = max(
                                        self.completedViewportRevision,
                                        viewportRevision
                                    )
                                }
                            }
                        }
                        self.onFrameRendered?(itemTime, hostTime)
                    }
                }
            }
        }
        submittedFrame = true
        commandBuffer.commit()
    }

    private func textureOutputSize(
        for pixelBuffer: CVPixelBuffer,
        configuration: HdrMetalProjectionConfiguration
    ) -> (width: Int, height: Int) {
        let sourceWidth = max(CVPixelBufferGetWidth(pixelBuffer), 1)
        let sourceHeight = max(CVPixelBufferGetHeight(pixelBuffer), 1)
        let requestedViewport = withTextureOutputStateLock {
            (width: requestedViewportWidth, height: requestedViewportHeight)
        }
        guard requestedViewport.width > 0,
              requestedViewport.height > 0
        else {
            return (sourceWidth, sourceHeight)
        }
        // Kotlin supplies a viewport fitted to the media aspect ratio. Keep that aspect in the
        // IOSurface for both flat and projected video; TextureView can then implement Fit, Crop,
        // and FillBounds immediately without changing the projection camera or allocating a full
        // 8K FP16 intermediate for an ordinary flat source.
        // macOS Metal GPUs support at least 16K 2D textures on the deployment target.
        let maximum = 16_384
        let scale = min(1.0, Double(maximum) / Double(max(requestedViewport.width, requestedViewport.height)))
        return (
            max(Int(Double(requestedViewport.width) * scale), 1),
            max(Int(Double(requestedViewport.height) * scale), 1)
        )
    }

    private func ensureTextureOutput(width: Int, height: Int) -> MTLTexture? {
        withTextureOutputStateLock {
            if textureOutputWidth == width,
               textureOutputHeight == height,
               let texture = textureOutputTexture
            {
                return texture
            }
            let bytesPerElement = 8
            let minimumRowBytes = width * bytesPerElement
            let rowBytes = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, minimumRowBytes)
            let properties: [String: Any] = [
                kIOSurfaceWidth as String: width,
                kIOSurfaceHeight as String: height,
                kIOSurfaceBytesPerElement as String: bytesPerElement,
                kIOSurfaceBytesPerRow as String: rowBytes,
                kIOSurfaceAllocSize as String: rowBytes * height,
                kIOSurfacePixelFormat as String: Int(kCVPixelFormatType_64RGBAHalf),
            ]
            guard let surface = IOSurfaceCreate(properties as CFDictionary) else {
                reportFailure("IOSurface could not allocate the RGBA16Float TextureView output.")
                return nil
            }
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rgba16Float,
                width: width,
                height: height,
                mipmapped: false
            )
            descriptor.storageMode = .shared
            descriptor.usage = [.renderTarget, .shaderRead]
            guard let texture = device.makeTexture(descriptor: descriptor, iosurface: surface, plane: 0) else {
                reportFailure("Metal could not map the RGBA16Float TextureView IOSurface.")
                return nil
            }
            generation &+= 1
            textureOutputSurface = surface
            textureOutputTexture = texture
            textureOutputWidth = width
            textureOutputHeight = height
            textureOutputFrameSerial = 0
            return texture
        }
    }

    private func releaseTextureOutput() {
        withTextureOutputStateLock {
            generation &+= 1
            textureOutputTexture = nil
            textureOutputSurface = nil
            textureOutputWidth = 0
            textureOutputHeight = 0
            textureOutputFrameSerial = 0
            completedViewportRevision = requestedViewportRevision
        }
    }

    private func pendingTextureViewportRevision() -> UInt64? {
        withTextureOutputStateLock {
            guard textureOutputEnabled,
                  requestedViewportWidth > 0,
                  requestedViewportHeight > 0,
                  requestedViewportRevision > completedViewportRevision
            else {
                return nil
            }
            return requestedViewportRevision
        }
    }

    private func withTextureOutputStateLock<T>(_ body: () -> T) -> T {
        textureOutputStateLock.lock()
        defer { textureOutputStateLock.unlock() }
        return body()
    }

    private static func makeIctcpGamutLut(device: MTLDevice) -> MTLTexture? {
        let edge = Int(KMP_ICTCP_GAMUT_LUT_DEFAULT_EDGE)
        let valueCount = kmp_ictcp_gamut_lut_value_count(UInt32(edge))
        guard valueCount > 0 else { return nil }
        var values = [Float](repeating: 0, count: valueCount)
        let generated = values.withUnsafeMutableBufferPointer { buffer in
            kmp_generate_ictcp_gamut_lut_rgba32f(
                buffer.baseAddress,
                buffer.count,
                UInt32(edge),
                100.0
            )
        }
        guard generated != 0 else { return nil }

        let descriptor = MTLTextureDescriptor()
        descriptor.textureType = .type3D
        descriptor.pixelFormat = .rgba32Float
        descriptor.width = edge
        descriptor.height = edge
        descriptor.depth = edge
        descriptor.mipmapLevelCount = 1
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        let bytesPerTexel = MemoryLayout<Float>.stride * Int(KMP_ICTCP_GAMUT_LUT_CHANNELS)
        values.withUnsafeBytes { bytes in
            texture.replace(
                region: MTLRegionMake3D(0, 0, 0, edge, edge, edge),
                mipmapLevel: 0,
                slice: 0,
                withBytes: bytes.baseAddress!,
                bytesPerRow: edge * bytesPerTexel,
                bytesPerImage: edge * edge * bytesPerTexel
            )
        }
        return texture
    }

    private func makePlaneTextures(
        _ pixelBuffer: CVPixelBuffer,
        tenBit: Bool
    ) -> (luma: MTLTexture, chroma: MTLTexture, lumaRef: CVMetalTexture, chromaRef: CVMetalTexture)? {
        let lumaFormat: MTLPixelFormat = tenBit ? .r16Unorm : .r8Unorm
        let chromaFormat: MTLPixelFormat = tenBit ? .rg16Unorm : .rg8Unorm
        var lumaRef: CVMetalTexture?
        var chromaRef: CVMetalTexture?
        let lumaStatus = CVMetalTextureCacheCreateTextureFromImage(
            nil,
            textureCache,
            pixelBuffer,
            nil,
            lumaFormat,
            CVPixelBufferGetWidthOfPlane(pixelBuffer, 0),
            CVPixelBufferGetHeightOfPlane(pixelBuffer, 0),
            0,
            &lumaRef
        )
        let chromaStatus = CVMetalTextureCacheCreateTextureFromImage(
            nil,
            textureCache,
            pixelBuffer,
            nil,
            chromaFormat,
            CVPixelBufferGetWidthOfPlane(pixelBuffer, 1),
            CVPixelBufferGetHeightOfPlane(pixelBuffer, 1),
            1,
            &chromaRef
        )
        guard lumaStatus == kCVReturnSuccess,
              chromaStatus == kCVReturnSuccess,
              let retainedLuma = lumaRef,
              let retainedChroma = chromaRef,
              let luma = CVMetalTextureGetTexture(retainedLuma),
              let chroma = CVMetalTextureGetTexture(retainedChroma)
        else {
            reportFailure("CoreVideo could not create P010/NV12 Metal plane textures.")
            return nil
        }
        return (luma, chroma, retainedLuma, retainedChroma)
    }

    private func makeParameters(
        _ configuration: HdrMetalProjectionConfiguration,
        tenBit: Bool,
        fullRange: Bool,
        hdr10PlusCurve: Hdr10PlusToneCurve?,
        targetWidth: Int,
        targetHeight: Int,
        outputScRgb: Bool
    ) -> [Float] {
        let eyeWidth = configuration.stereo > 0.5 ? Float(targetWidth) / 2 : Float(targetWidth)
        let viewportAspect = eyeWidth / max(Float(targetHeight), 1)
        var values: [Float] = [
            configuration.projectionType, configuration.fieldOfView, configuration.stereo, viewportAspect,
            configuration.leftEye.left, configuration.leftEye.top,
            configuration.leftEye.right, configuration.leftEye.bottom, configuration.leftEye.rotation,
            configuration.rightEye.left, configuration.rightEye.top,
            configuration.rightEye.right, configuration.rightEye.bottom, configuration.rightEye.rotation,
            configuration.yaw, configuration.pitch, configuration.roll, configuration.zoom,
            configuration.transfer, configuration.matrix, configuration.outputHdr ? 1 : 0,
            configuration.sourcePeakNits, tenBit ? 1 : 0, fullRange ? 1 : 0,
        ]
        values.append(hdr10PlusCurve == nil ? 0 : 1)
        values.append(hdr10PlusCurve?.sourcePeakNits ?? 0)
        values.append(contentsOf: hdr10PlusCurve?.normalizedOutputLuminance ?? hdr10PlusEmptyCurve)
        values.append(configuration.primaries)
        values.append(outputScRgb ? 1 : 0)
        return values
    }

    static func containsValidHdr10PlusMetadata(_ pixelBuffer: CVPixelBuffer) -> Bool {
        parseHdr10PlusToneCurve(pixelBuffer, displayPeakNits: hdr10PlusProbePeakNits).curve != nil
    }

    private static func parseHdr10PlusToneCurve(
        _ pixelBuffer: CVPixelBuffer,
        displayPeakNits: Float
    ) -> (curve: Hdr10PlusToneCurve?, error: String?) {
        guard let attachment = CVBufferCopyAttachment(
            pixelBuffer,
            kCMSampleAttachmentKey_HDR10PlusPerFrameData,
            nil
        ) else {
            return (nil, "AVFoundation did not expose HDR10+ metadata on the decoded frame.")
        }
        guard CFGetTypeID(attachment) == CFDataGetTypeID() else {
            return (nil, "AVFoundation exposed HDR10+ metadata with an unexpected attachment type.")
        }
        let data = unsafeBitCast(attachment, to: CFData.self)
        let length = CFDataGetLength(data)
        guard length > 0, length <= hdr10PlusMaximumPayloadSize,
              let payload = CFDataGetBytePtr(data)
        else {
            return (nil, "AVFoundation exposed an empty or oversized HDR10+ payload.")
        }

        var sourcePeakNits: Float = 0
        var curve = [Float](repeating: 0, count: hdr10PlusToneCurveSampleCount)
        var error = [CChar](repeating: 0, count: hdr10PlusErrorCapacity)
        let parsed = curve.withUnsafeMutableBufferPointer { curveBuffer in
            error.withUnsafeMutableBufferPointer { errorBuffer in
                kmp_hdr10_plus_parse_tone_curve(
                    payload,
                    length,
                    Double(displayPeakNits),
                    &sourcePeakNits,
                    curveBuffer.baseAddress,
                    errorBuffer.baseAddress,
                    errorBuffer.count
                )
            }
        }
        guard parsed == 1 else {
            let detail = error.withUnsafeBufferPointer { buffer -> String in
                guard let address = buffer.baseAddress, address.pointee != 0 else {
                    return "The decoded frame contains invalid ST 2094-40 metadata."
                }
                return String(cString: address)
            }
            return (nil, detail)
        }
        return (
            Hdr10PlusToneCurve(
                sourcePeakNits: sourcePeakNits,
                normalizedOutputLuminance: curve
            ),
            nil
        )
    }

    private func reportFailure(_ detail: String) {
        guard failureDetail == nil else { return }
        failureDetail = detail
        renderedFrameCount = 0
        stop()
        hdrMetalLog(detail)
    }
}

private let hdr10PlusEmptyCurve = [Float](repeating: 0, count: hdr10PlusToneCurveSampleCount)
private let hdr10PlusMetadataFailurePrefix = "HDR10_PLUS_METADATA:"
private let hdr10PlusPqTransferCode: Float = 1
private let hdr10PlusProbePeakNits: Float = 1_000
private let hdr10PlusToneCurveSampleCount = 33
private let hdr10PlusMaximumPayloadSize = 1_024
private let hdr10PlusErrorCapacity = 256
