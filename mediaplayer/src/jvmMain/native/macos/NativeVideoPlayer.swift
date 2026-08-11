import AVFoundation
import CoreImage
import CoreGraphics
import CoreVideo
import Foundation
import AppKit
import Metal
import QuartzCore

func hdrMetalLog(_ message: String) {
    if let data = "HDR Metal: \(message)\n".data(using: .utf8) {
        FileHandle.standardError.write(data)
    }
}

private let nativeVideoLoggingEnabled: Bool = {
    guard let value = ProcessInfo.processInfo.environment["COMPOSE_MEDIA_PLAYER_NATIVE_LOGGING"]?.lowercased()
    else {
        return false
    }
    return value == "1" || value == "true" || value == "yes" || value == "on"
}()

private func nativeVideoLog(_ message: @autoclosure () -> String) {
    guard nativeVideoLoggingEnabled else { return }
    if let data = "\(message())\n".data(using: .utf8) {
        FileHandle.standardError.write(data)
    }
}

enum HdrMetalScaleMode: Int32 {
    case fit = 0
    case crop = 1
    case fill = 2
}

private func syncOnMain<T>(_ body: () -> T) -> T {
    if Thread.isMainThread {
        return body()
    }
    return DispatchQueue.main.sync(execute: body)
}

private struct DolbyVisionConfiguration {
    let profile: Int
    let level: Int
    let hasRpu: Bool
    let hasEnhancementLayer: Bool
    let hasBaseLayer: Bool
    let baseLayerSignalCompatibilityId: Int?
}

private struct HLSVariantColorDescriptor {
    let peakBitRate: Double?
    let averageBitRate: Double?
    let colorInfo: String

    func relativeDistance(to indicatedBitRate: Double) -> Double? {
        let declaredRates = [peakBitRate, averageBitRate].compactMap { rate in
            rate.flatMap { $0 > 0 ? $0 : nil }
        }
        guard indicatedBitRate > 0, !declaredRates.isEmpty else { return nil }
        return declaredRates.map { abs($0 - indicatedBitRate) / max($0, indicatedBitRate) }.min()
    }
}

private func readUInt16BE(_ bytes: [UInt8], _ offset: Int) -> Int {
    guard offset >= 0, offset + 1 < bytes.count else { return 0 }
    return (Int(bytes[offset]) << 8) | Int(bytes[offset + 1])
}

private func readUInt32BE(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
    guard offset >= 0, offset + 3 < bytes.count else { return 0 }
    return (UInt32(bytes[offset]) << 24)
        | (UInt32(bytes[offset + 1]) << 16)
        | (UInt32(bytes[offset + 2]) << 8)
        | UInt32(bytes[offset + 3])
}

private func fourCCString(_ value: FourCharCode) -> String {
    let bytes: [UInt8] = [
        UInt8((value >> 24) & 0xff),
        UInt8((value >> 16) & 0xff),
        UInt8((value >> 8) & 0xff),
        UInt8(value & 0xff),
    ]
    return String(bytes: bytes, encoding: .ascii) ?? ""
}

private func firstDataValue(_ value: Any?) -> Data? {
    if let data = value as? Data { return data }
    if let values = value as? [Data] { return values.first }
    return nil
}

/** Returns bits per component; kCMFormatDescriptionExtension_Depth may instead be bits per pixel. */
private func componentBitDepth(from extensions: NSDictionary, dynamicRange: String) -> Int {
    if let atoms = extensions.object(forKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms)
            as? NSDictionary,
       let hevcConfiguration = firstDataValue(atoms.object(forKey: "hvcC")) {
        let bytes = [UInt8](hevcConfiguration)
        if bytes.count >= 19 {
            // ISO/IEC 14496-15 HEVCDecoderConfigurationRecord stores these as minus eight.
            let lumaDepth = 8 + Int(bytes[17] & 0x07)
            let chromaDepth = 8 + Int(bytes[18] & 0x07)
            if (8...16).contains(lumaDepth), (8...16).contains(chromaDepth) {
                return max(lumaDepth, chromaDepth)
            }
        }
    }

    if let reportedDepth =
        (extensions.object(forKey: kCMFormatDescriptionExtension_Depth) as? NSNumber)?.intValue,
       (1...16).contains(reportedDepth) {
        return reportedDepth
    }

    // HDR10 and the AVFoundation HLG/Dolby Vision profiles handled here use a 10-bit base layer.
    return (dynamicRange == "HDR10" || dynamicRange == "HLG" || dynamicRange == "DOLBY_VISION") ? 10 : 0
}

private func dolbyVisionConfiguration(from extensions: NSDictionary) -> DolbyVisionConfiguration? {
    guard let atoms = extensions.object(forKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms)
        as? NSDictionary
    else {
        return nil
    }
    let data = firstDataValue(atoms.object(forKey: "dvcC"))
        ?? firstDataValue(atoms.object(forKey: "dvvC"))
    guard let data = data else { return nil }
    let bytes = [UInt8](data)
    guard bytes.count >= 4 else { return nil }
    return DolbyVisionConfiguration(
        profile: Int((bytes[2] >> 1) & 0x7f),
        level: Int((bytes[2] & 0x01) << 5) | Int((bytes[3] >> 3) & 0x1f),
        hasRpu: (bytes[3] & 0x04) != 0,
        hasEnhancementLayer: (bytes[3] & 0x02) != 0,
        hasBaseLayer: (bytes[3] & 0x01) != 0,
        baseLayerSignalCompatibilityId: bytes.count >= 5 ? Int((bytes[4] >> 4) & 0x0f) : nil
    )
}

/**
 * Stable key/value representation consumed by the Kotlin bridge. Values come from the selected
 * CMFormatDescription; no HDR mode is inferred merely from the codec being HEVC.
 */
private func colorInfoString(from formatDescription: CMFormatDescription) -> String {
    let extensions = (CMFormatDescriptionGetExtensions(formatDescription) as NSDictionary?) ?? NSDictionary()
    let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)
    let codec = fourCCString(mediaSubType)
    let transferValue = extensions.object(forKey: kCMFormatDescriptionExtension_TransferFunction)
    let transferDescription = transferValue.map { String(describing: $0) } ?? ""
    let primariesValue = extensions.object(forKey: kCMFormatDescriptionExtension_ColorPrimaries)
    let primariesDescription = primariesValue.map { String(describing: $0) } ?? ""
    let matrixValue = extensions.object(forKey: kCMFormatDescriptionExtension_YCbCrMatrix)
    let matrixDescription = matrixValue.map { String(describing: $0) } ?? ""
    let dolbyVision = dolbyVisionConfiguration(from: extensions)
    let isDolbyVisionCodec = mediaSubType == kCMVideoCodecType_DolbyVisionHEVC || codec == "dvhe"

    let transfer: String
    let dynamicRange: String
    if isDolbyVisionCodec {
        transfer = transferDescription.contains("2100_HLG") ? "HLG" : "PQ"
        dynamicRange = "DOLBY_VISION"
    } else if transferDescription.contains("2084") {
        transfer = "PQ"
        dynamicRange = "HDR10"
    } else if transferDescription.contains("2100_HLG") {
        transfer = "HLG"
        dynamicRange = "HLG"
    } else if transferDescription.contains("Linear") {
        transfer = "LINEAR"
        dynamicRange = "SDR"
    } else if transferDescription.lowercased().contains("srgb") {
        transfer = "SRGB"
        dynamicRange = "SDR"
    } else if !transferDescription.isEmpty {
        transfer = "SDR"
        dynamicRange = "SDR"
    } else if mediaSubType == kCMVideoCodecType_H264 || mediaSubType == kCMVideoCodecType_MPEG4Video
        || mediaSubType == kCMVideoCodecType_MPEG2Video
    {
        transfer = "SDR"
        dynamicRange = "SDR"
    } else {
        transfer = "UNKNOWN"
        dynamicRange = "UNKNOWN"
    }

    let primaries: String
    if primariesDescription.contains("2020") {
        primaries = "BT2020"
    } else if primariesDescription.contains("P3_D65") || primariesDescription.contains("DCI_P3") {
        primaries = "DISPLAY_P3"
    } else if primariesDescription.contains("709") {
        primaries = "BT709"
    } else if primariesDescription.contains("EBU_3213") {
        primaries = "BT601_625"
    } else if primariesDescription.contains("SMPTE_C") {
        primaries = "BT601_525"
    } else {
        primaries = "UNKNOWN"
    }

    let matrix: String
    if matrixDescription.contains("2020") {
        matrix = "BT2020_NCL"
    } else if matrixDescription.contains("709") {
        matrix = "BT709"
    } else if matrixDescription.contains("601") || matrixDescription.contains("240M") {
        matrix = "BT601"
    } else {
        matrix = "UNKNOWN"
    }

    let fullRangeValue = extensions.object(forKey: kCMFormatDescriptionExtension_FullRangeVideo)
    let isFullRange = (fullRangeValue as? NSNumber)?.boolValue ?? false
    let depth = componentBitDepth(from: extensions, dynamicRange: dynamicRange)

    var fields = [
        "dynamicRange=\(dynamicRange)",
        "bitDepth=\(depth)",
        "primaries=\(primaries)",
        "transfer=\(transfer)",
        "matrix=\(matrix)",
        "range=\(isFullRange ? "FULL" : "LIMITED")",
        "codec=\(codec)",
    ]

    if let data = extensions.object(forKey: kCMFormatDescriptionExtension_MasteringDisplayColorVolume) as? Data {
        let bytes = [UInt8](data)
        if bytes.count >= 24 {
            // HEVC mastering_display_colour_volume() order is G, B, R, white point, max/min luminance.
            fields += [
                "masterRedX=\(Double(readUInt16BE(bytes, 8)) / 50_000.0)",
                "masterRedY=\(Double(readUInt16BE(bytes, 10)) / 50_000.0)",
                "masterGreenX=\(Double(readUInt16BE(bytes, 0)) / 50_000.0)",
                "masterGreenY=\(Double(readUInt16BE(bytes, 2)) / 50_000.0)",
                "masterBlueX=\(Double(readUInt16BE(bytes, 4)) / 50_000.0)",
                "masterBlueY=\(Double(readUInt16BE(bytes, 6)) / 50_000.0)",
                "masterWhiteX=\(Double(readUInt16BE(bytes, 12)) / 50_000.0)",
                "masterWhiteY=\(Double(readUInt16BE(bytes, 14)) / 50_000.0)",
                "masterMaxNits=\(Double(readUInt32BE(bytes, 16)) / 10_000.0)",
                "masterMinNits=\(Double(readUInt32BE(bytes, 20)) / 10_000.0)",
            ]
        }
    }
    if let data = extensions.object(forKey: kCMFormatDescriptionExtension_ContentLightLevelInfo) as? Data {
        let bytes = [UInt8](data)
        if bytes.count >= 4 {
            fields += [
                "maxCll=\(readUInt16BE(bytes, 0))",
                "maxFall=\(readUInt16BE(bytes, 2))",
            ]
        }
    }
    if isDolbyVisionCodec {
        fields += [
            "dvProfile=\(dolbyVision?.profile ?? 0)",
            "dvLevel=\(dolbyVision?.level ?? 0)",
            "dvHasRpu=\((dolbyVision?.hasRpu ?? false) ? 1 : 0)",
            "dvHasEl=\((dolbyVision?.hasEnhancementLayer ?? false) ? 1 : 0)",
            "dvHasBase=\((dolbyVision?.hasBaseLayer ?? false) ? 1 : 0)",
        ]
        if let compatibilityId = dolbyVision?.baseLayerSignalCompatibilityId {
            fields.append("dvCompatibilityId=\(compatibilityId)")
        }
    }
    return fields.joined(separator: ";")
}

/**
 * A variant declaration is less detailed than a selected CMFormatDescription, but it is the
 * authoritative adaptive-range signal exposed by AVFoundation for HLS. Do not infer HDR from
 * HEVC alone: only AVVideoRange or an explicit Dolby Vision sample entry may select an HDR range.
 */
private func colorInfoString(from attributes: AVAssetVariant.VideoAttributes) -> String {
    let codecs = attributes.codecTypes
    let isDolbyVision = codecs.contains(kCMVideoCodecType_DolbyVisionHEVC)
        || codecs.contains(0x64766865) // dvhe
        || codecs.contains(0x64766831) // dvh1
    let dynamicRange: String
    let transfer: String
    let primaries: String
    let matrix: String
    let bitDepth: Int

    if isDolbyVision {
        dynamicRange = "DOLBY_VISION"
        transfer = attributes.videoRange == .hlg ? "HLG" : "PQ"
        primaries = "BT2020"
        matrix = "BT2020_NCL"
        bitDepth = 10
    } else if attributes.videoRange == .pq {
        dynamicRange = "HDR10"
        transfer = "PQ"
        primaries = "BT2020"
        matrix = "BT2020_NCL"
        bitDepth = 10
    } else if attributes.videoRange == .hlg {
        dynamicRange = "HLG"
        transfer = "HLG"
        primaries = "BT2020"
        matrix = "BT2020_NCL"
        bitDepth = 10
    } else {
        dynamicRange = "SDR"
        transfer = "SDR"
        primaries = "UNKNOWN"
        matrix = "UNKNOWN"
        bitDepth = 8
    }

    var fields = [
        "dynamicRange=\(dynamicRange)",
        "bitDepth=\(bitDepth)",
        "primaries=\(primaries)",
        "transfer=\(transfer)",
        "matrix=\(matrix)",
        "range=LIMITED",
    ]
    if isDolbyVision {
        // AVAssetVariant does not expose dvcC/dvvC. Keep profile/base-layer fields unknown while
        // reporting the explicit DV sample entry and its RPU-bearing decoder route.
        fields += ["dvProfile=0", "dvLevel=0", "dvHasRpu=1", "dvHasEl=0", "dvHasBase=0"]
    }
    return fields.joined(separator: ";")
}

/**
 * A replacement player prepared beside the active player.
 *
 * The active AVPlayer and its AVPlayerLayer stay untouched until this candidate has decoded a
 * real video frame. All fields are accessed on AppKit's main thread.
 */
private final class PreparedPlaybackReplacement {
    let token: UInt64
    let asset: AVURLAsset
    let isHLS: Bool
    let item: AVPlayerItem
    let player: AVPlayer
    var warmupOutput: AVPlayerItemVideoOutput?
    var statusObserver: NSKeyValueObservation?
    var framePollTimer: Timer?
    var firstFrame: CVPixelBuffer?
    var status: Int32 = 0
    var errorMessage: String?
    var warmupStartRequested = false
    var warmupOriginTime: CMTime = .zero

    init(
        token: UInt64,
        asset: AVURLAsset,
        isHLS: Bool,
        item: AVPlayerItem,
        player: AVPlayer,
        warmupOutput: AVPlayerItemVideoOutput
    ) {
        self.token = token
        self.asset = asset
        self.isHLS = isHLS
        self.item = item
        self.player = player
        self.warmupOutput = warmupOutput
    }

    func stopWarmup(removeOutput: Bool) {
        framePollTimer?.invalidate()
        framePollTimer = nil
        statusObserver?.invalidate()
        statusObserver = nil
        player.pause()
        if removeOutput, let output = warmupOutput {
            item.remove(output)
        }
        warmupOutput = nil
    }
}

/// Class that manages video playback and frame capture into an optimized shared buffer.
/// Frame capture rate adapts to the lower of screen refresh rate and video frame rate.
/// Includes full HLS (HTTP Live Streaming) support with adaptive bitrate streaming.
class MacVideoPlayer {
    private var player: AVPlayer?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var hdrMetalRenderer: HdrMetalVideoRenderer?
    private var hdrPlayerLayer: AVPlayerLayer?
    private var hdr10PlusProbeItem: AVPlayerItem?
    private var hdr10PlusProbeOutput: AVPlayerItemVideoOutput?
    private var hdr10PlusProbeTimer: Timer?
    private var prefersHdrMetalOutput: Bool = false
    private var toneMapsHdrToSdr: Bool = false
    private var usesMetalProjectionSurface: Bool = false
    private var metalProjectionConfiguration: String?
    // Desktop presentation is always exported through Metal -> IOSurface -> TextureView.
    // AVPlayerLayer is no longer an automatic flat-video fallback.
    private var useHdrPlayerLayerForSurface: Bool { false }

    // Timer for capturing frames at adaptive rate
    private var displayLink: Timer?

    // Track the video's native frame rate
    private var videoFrameRate: Float = 0.0

    // Track the screen's refresh rate
    private var screenRefreshRate: Float = 60.0

    // The actual capture frame rate (minimum of video and screen rates)
    private var captureFrameRate: Float = 0.0

    // Latest decoded CVPixelBuffer retained directly — no intermediate copy.
    // The JNI side locks it for reading, copies to the Skia bitmap, then unlocks.
    private var latestPixelBuffer: CVPixelBuffer? = nil
    private var lockedPixelBuffer: CVPixelBuffer? = nil
    private let bufferLock = NSLock()

    // Frame dimensions (scaled output — may be smaller than native to save RAM)
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    // Native video resolution (unscaled, as reported by the asset)
    private var nativeVideoWidth: Int = 0
    private var nativeVideoHeight: Int = 0

    // Audio volume control (0.0 to 1.0)
    private var volume: Float = 1.0

    // Flag to track if playback is active
    private var isPlaying: Bool = false
    private var isReadyForPlayback = false
    private var pendingPlay = false
    private var replacementSequence: UInt64 = 0
    private var pendingReplacement: PreparedPlaybackReplacement?

    // Playback speed control (1.0 is normal speed)
    private var playbackSpeed: Float = 1.0

    // Metadata properties
    private var videoTitle: String? = nil
    private var videoBitrate: Int64 = 0
    private var videoMimeType: String? = nil
    private let videoColorInfoLock = NSLock()
    private var videoColorInfo: String = "dynamicRange=UNKNOWN"
    private var audioChannels: Int = 0
    private var audioSampleRate: Int = 0

    // HLS-specific properties
    private var isHLSStream: Bool = false
    private var availableBitrates: [Float] = []
    private var currentBitrate: Float = 0
    private var preferredPeakBitRate: Double = 0
    private let hlsVariantColorLock = NSLock()
    private var hlsVariantColorDescriptors: [HLSVariantColorDescriptor] = []
    private var sourceGeneration: UInt64 = 0
    private var bufferStatus: Float = 0.0
    private var isBuffering: Bool = false
    private var networkStatus: String = "Unknown"

    // Playback diagnostics are sampled independently from the Compose/JVM polling loop. The
    // AVPlayerItemVideoOutput clock is compared with AVPlayer's active playback clock. New buffers
    // sampled alongside AVPlayerLayer provide a real decoded/displayable-frame count; access-log
    // drops are used when AVFoundation exposes them and otherwise inferred from the source clock.
    private let playbackMetricsLock = NSLock()
    private var metricsPlayedSeconds: Double = 0
    private var metricsLastHostTime: CFTimeInterval?
    private var metricsRenderedVideoFrames: Int64 = 0
    private var metricsDroppedVideoFrames: Int64 = -1
    private var metricsMaximumAvSyncOffsetSeconds: Double = -1

    // Observers for HLS monitoring
    private var playerItemObserver: NSKeyValueObservation?
    private var playerItemStatusObserver: NSKeyValueObservation?
    private var playerObserver: NSKeyValueObservation?
    private var timeControlStatusObserver: NSKeyValueObservation?
    private var bufferEmptyObserver: NSKeyValueObservation?
    private var bufferLikelyToKeepUpObserver: NSKeyValueObservation?
    private var bufferFullObserver: NSKeyValueObservation?
    private var presentationSizeObserver: NSKeyValueObservation?

    // AVFoundation applies clean aperture and pixel aspect ratio to presentationSize. Cache the
    // resulting display ratio from KVO so frame readers never touch AVPlayerItem off-main.
    private let aspectLock = NSLock()
    private var cachedDisplayAspectRatio: Double = 0.0

    // End-of-playback flag (set by AVPlayerItemDidPlayToEndTime, consumed once by the Kotlin side)
    private let playbackEndLock = NSLock()
    private var didPlayToEnd: Bool = false
    private var playbackEndObserver: NSObjectProtocol?

    // HLS Error tracking
    private var lastError: String? = nil
    private var errorCount: Int = 0

    private func setVideoColorInfo(_ value: String) {
        videoColorInfoLock.lock()
        videoColorInfo = value
        videoColorInfoLock.unlock()
    }

    /**
     * A PQ format description cannot distinguish static HDR10 from HDR10+. Promote the selected
     * source only after the projection renderer has parsed a real per-frame ST 2094-40 payload.
     */
    private func promoteVideoColorInfoToHdr10Plus() {
        videoColorInfoLock.lock()
        defer { videoColorInfoLock.unlock() }
        guard videoColorInfo.contains("dynamicRange=HDR10;"),
              videoColorInfo.contains("transfer=PQ")
        else {
            return
        }
        videoColorInfo = videoColorInfo.replacingOccurrences(
            of: "dynamicRange=HDR10;",
            with: "dynamicRange=HDR10_PLUS;"
        )
        videoColorInfo += ";hdr10PlusAppId=4;hdr10PlusAppVersion=1;hdr10PlusPerFrame=1"
    }

    private func makeHdrMetalRenderer() -> HdrMetalVideoRenderer? {
        let renderer = HdrMetalVideoRenderer()
        renderer?.onHdr10PlusObserved = { [weak self] in
            self?.promoteVideoColorInfoToHdr10Plus()
        }
        renderer?.onFrameRendered = { [weak self] itemTime, hostTime in
            guard let self = self else { return }
            self.samplePlaybackMetrics(itemTime: itemTime, hostTime: hostTime)
            self.recordRenderedVideoFrame()
        }
        return renderer
    }

    private func attachHdr10PlusProbe(to item: AVPlayerItem) {
        if hdr10PlusProbeItem === item, hdr10PlusProbeOutput != nil { return }
        detachHdr10PlusProbe()
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            kCVPixelBufferMetalCompatibilityKey as String: true,
        ]
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: attributes)
        output.suppressesPlayerRendering = false
        item.add(output)
        output.requestNotificationOfMediaDataChange(withAdvanceInterval: hdr10PlusProbeAdvanceSeconds)
        hdr10PlusProbeItem = item
        hdr10PlusProbeOutput = output
        let timer = Timer(timeInterval: hdr10PlusProbeIntervalSeconds, repeats: true) { [weak self] _ in
            self?.sampleHdr10PlusProbe()
        }
        RunLoop.main.add(timer, forMode: .common)
        hdr10PlusProbeTimer = timer
    }

    private func sampleHdr10PlusProbe() {
        guard let item = hdr10PlusProbeItem,
              player?.currentItem === item,
              let output = hdr10PlusProbeOutput
        else {
            return
        }
        let hostTime = CACurrentMediaTime()
        let itemTime = output.itemTime(forHostTime: hostTime)
        samplePlaybackMetrics(itemTime: itemTime, hostTime: hostTime)
        guard output.hasNewPixelBuffer(forItemTime: itemTime) else { return }
        var displayTime = CMTime.invalid
        guard let pixelBuffer = output.copyPixelBuffer(
            forItemTime: itemTime,
            itemTimeForDisplay: &displayTime
        ) else {
            return
        }
        recordRenderedVideoFrame()
        if HdrMetalVideoRenderer.containsValidHdr10PlusMetadata(pixelBuffer) {
            promoteVideoColorInfoToHdr10Plus()
        }
    }

    private func detachHdr10PlusProbe() {
        hdr10PlusProbeTimer?.invalidate()
        hdr10PlusProbeTimer = nil
        if let item = hdr10PlusProbeItem, let output = hdr10PlusProbeOutput {
            item.remove(output)
        }
        hdr10PlusProbeItem = nil
        hdr10PlusProbeOutput = nil
    }

    private func resetPlaybackMetrics() {
        playbackMetricsLock.lock()
        metricsPlayedSeconds = 0
        metricsLastHostTime = nil
        metricsRenderedVideoFrames = 0
        metricsDroppedVideoFrames = -1
        metricsMaximumAvSyncOffsetSeconds = -1
        playbackMetricsLock.unlock()
    }

    private func samplePlaybackMetrics(itemTime: CMTime, hostTime: CFTimeInterval) {
        let playerSeconds: Double
        if let timebase = player?.currentItem?.timebase {
            playerSeconds = CMTimeGetSeconds(CMTimebaseGetTime(timebase))
        } else {
            playerSeconds = player.map { CMTimeGetSeconds($0.currentTime()) } ?? .nan
        }
        let videoSeconds = CMTimeGetSeconds(itemTime)
        playbackMetricsLock.lock()
        if isPlaying, let previousHostTime = metricsLastHostTime {
            let elapsed = hostTime - previousHostTime
            if elapsed > 0, elapsed <= maximumPlaybackMetricsSampleGapSeconds {
                metricsPlayedSeconds += elapsed * Double(playbackSpeed)
            }
        }
        metricsLastHostTime = hostTime
        if metricsPlayedSeconds >= playbackMetricsWarmupSeconds,
           playerSeconds.isFinite,
           videoSeconds.isFinite {
            let offset = abs(videoSeconds - playerSeconds)
            if offset <= maximumValidAvSyncOffsetSeconds {
                metricsMaximumAvSyncOffsetSeconds = max(metricsMaximumAvSyncOffsetSeconds, offset)
            }
        }
        playbackMetricsLock.unlock()
    }

    private func updateDroppedVideoFrames(_ value: Int) {
        guard value >= 0 else { return }
        playbackMetricsLock.lock()
        metricsDroppedVideoFrames = max(metricsDroppedVideoFrames, Int64(value))
        playbackMetricsLock.unlock()
    }

    private func recordRenderedVideoFrame() {
        playbackMetricsLock.lock()
        metricsRenderedVideoFrames += 1
        playbackMetricsLock.unlock()
    }

    func getPlaybackDiagnostics() -> String {
        playbackMetricsLock.lock()
        let playedSeconds = metricsPlayedSeconds
        let renderedFrames = metricsRenderedVideoFrames
        let reportedDroppedFrames = metricsDroppedVideoFrames
        let maximumOffsetSeconds = metricsMaximumAvSyncOffsetSeconds
        playbackMetricsLock.unlock()

        let expectedFrames = videoFrameRate > 0 ? Int64(floor(playedSeconds * Double(videoFrameRate))) : -1
        let inferredDroppedFrames = expectedFrames >= 0 ? max(0, expectedFrames - renderedFrames) : 0
        let droppedFrames = reportedDroppedFrames >= 0
            ? max(reportedDroppedFrames, inferredDroppedFrames)
            : inferredDroppedFrames
        let totalFrames = renderedFrames + droppedFrames
        let maximumOffsetMs = maximumOffsetSeconds >= 0 ? maximumOffsetSeconds * 1_000 : -1
        return "totalFrames=\(totalFrames);renderedFrames=\(renderedFrames);" +
            "droppedFrames=\(droppedFrames);maxAvSyncMs=\(maximumOffsetMs);" +
            "playedSeconds=\(playedSeconds)"
    }

    init() {
        // Detect screen refresh rate
        detectScreenRefreshRate()

        // Configure AVAudioSession for better HLS audio handling
        configureAudioSession()
    }

    /// Configures the audio session for optimal HLS playback
    private func configureAudioSession() {
        // Note: AVAudioSession is iOS/tvOS only. For macOS, we'll use different audio configuration
        // macOS handles audio differently through Core Audio
    }

    /// Detects the current screen refresh rate
    private func detectScreenRefreshRate() {
        if let mainScreen = NSScreen.main {
            // Use CoreVideo DisplayLink to get refresh rate on macOS
            var displayID: CGDirectDisplayID = CGMainDisplayID()
            if let screenNumber = mainScreen.deviceDescription[
                NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber
            {
                displayID = CGDirectDisplayID(screenNumber.uint32Value)
            }

            var displayLink: CVDisplayLink?
            let error = CVDisplayLinkCreateWithCGDisplay(displayID, &displayLink)

            if error == kCVReturnSuccess, let link = displayLink {
                let period = CVDisplayLinkGetNominalOutputVideoRefreshPeriod(link)
                let timeValue = period.timeValue
                let timeScale = period.timeScale

                if timeValue > 0 && timeScale > 0 {
                    // Convert to Hz (frames per second)
                    let refreshRate = Double(timeScale) / Double(timeValue)
                    screenRefreshRate = Float(refreshRate)
                }
            } else {
                // Fallback if we can't get the refresh rate
                screenRefreshRate = 60.0
            }
        } else {
            screenRefreshRate = 60.0
        }
    }

    /// Checks if the URL is an HLS stream
    private func isHLSUrl(_ url: URL) -> Bool {
        let urlString = url.absoluteString.lowercased()
        return urlString.contains(".m3u8") ||
            urlString.contains("/playlist.m3u8") ||
            urlString.contains("/master.m3u8") ||
            urlString.contains("format=m3u8")
    }

    /// Configures the asset for HLS streaming
    private func configureHLSAsset(_ asset: AVURLAsset, requestHeaders: [String: String]) -> AVURLAsset {
        // Configure asset for optimal HLS streaming
        var options: [String: Any] = [
            AVURLAssetPreferPreciseDurationAndTimingKey: true
        ]
        if !requestHeaders.isEmpty {
            options["AVURLAssetHTTPHeaderFieldsKey"] = requestHeaders
        }

        // Create new asset with HLS-optimized options
        return AVURLAsset(url: asset.url, options: options)
    }

    /// Sets up HLS-specific monitoring
    private func setupHLSMonitoring(for item: AVPlayerItem) {
        // Monitor playback buffer
        bufferEmptyObserver = item.observe(\.isPlaybackBufferEmpty, options: [.new]) { [weak self] item, _ in
            self?.handleBufferEmpty(item.isPlaybackBufferEmpty)
        }

        bufferLikelyToKeepUpObserver = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            self?.handleBufferLikelyToKeepUp(item.isPlaybackLikelyToKeepUp)
        }

        bufferFullObserver = item.observe(\.isPlaybackBufferFull, options: [.new]) { [weak self] item, _ in
            self?.handleBufferFull(item.isPlaybackBufferFull)
        }

        // Monitor loaded time ranges for buffer status
        playerItemObserver = item.observe(\.loadedTimeRanges, options: [.new]) { [weak self] item, _ in
            self?.updateBufferStatus(from: item)
        }

        // Monitor access log for bitrate changes
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAccessLog(_:)),
            name: .AVPlayerItemNewAccessLogEntry,
            object: item
        )

        // Monitor error log
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleErrorLog(_:)),
            name: .AVPlayerItemNewErrorLogEntry,
            object: item
        )

        // Monitor playback stalls
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePlaybackStall(_:)),
            name: .AVPlayerItemPlaybackStalled,
            object: item
        )
    }

    /// Handles buffer empty state
    private func handleBufferEmpty(_ isEmpty: Bool) {
        if isEmpty {
            isBuffering = true
            nativeVideoLog("HLS: Buffer empty, buffering...")
        }
    }

    /// Handles buffer likely to keep up state
    private func handleBufferLikelyToKeepUp(_ isLikely: Bool) {
        if isLikely {
            isBuffering = false
            nativeVideoLog("HLS: Buffer recovered, playback can continue")
        }
    }

    /// Handles buffer full state
    private func handleBufferFull(_ isFull: Bool) {
        if isFull {
            nativeVideoLog("HLS: Buffer is full")
        }
    }

    /// Handles time control status changes
    private func handleTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .paused:
            networkStatus = "Paused"
        case .waitingToPlayAtSpecifiedRate:
            networkStatus = "Buffering"
            isBuffering = true
        case .playing:
            networkStatus = "Playing"
            isBuffering = false
        @unknown default:
            networkStatus = "Unknown"
        }
    }

    /// Updates buffer status from loaded time ranges
    private func updateBufferStatus(from item: AVPlayerItem) {
        guard let timeRange = item.loadedTimeRanges.first?.timeRangeValue else {
            bufferStatus = 0.0
            return
        }

        let startSeconds = CMTimeGetSeconds(timeRange.start)
        let durationSeconds = CMTimeGetSeconds(timeRange.duration)
        let currentSeconds = CMTimeGetSeconds(item.currentTime())

        if currentSeconds > 0 {
            let bufferedSeconds = startSeconds + durationSeconds - currentSeconds
            // Normalize to 0-1 range (assuming 10 seconds is "full" buffer)
            bufferStatus = Float(min(bufferedSeconds / 10.0, 1.0))
        }
    }

    /// Handles access log entries for HLS monitoring
    @objc private func handleAccessLog(_ notification: Notification) {
        guard let item = notification.object as? AVPlayerItem,
              item === player?.currentItem,
              let accessLog = item.accessLog(),
              let lastEvent = accessLog.events.last else { return }

        // Update current bitrate
        if lastEvent.indicatedBitrate > 0 {
            currentBitrate = Float(lastEvent.indicatedBitrate)
            updateSelectedHLSVariantColor(indicatedBitRate: lastEvent.indicatedBitrate)
        }
        updateDroppedVideoFrames(lastEvent.numberOfDroppedVideoFrames)

        // Log HLS streaming statistics
        nativeVideoLog("""
                  HLS Access Log:
                  - Indicated Bitrate: \(lastEvent.indicatedBitrate) bps
                  - Observed Bitrate: \(lastEvent.observedBitrate) bps
                  - Stall Count: \(lastEvent.numberOfStalls)
                  - Downloaded Bytes: \(lastEvent.numberOfBytesTransferred)
                  - Segments Downloaded: \(lastEvent.numberOfMediaRequests)
              """)
    }

    /// Handles error log entries
    @objc private func handleErrorLog(_ notification: Notification) {
        guard let item = notification.object as? AVPlayerItem,
              let errorLog = item.errorLog(),
              let lastEvent = errorLog.events.last else { return }

        errorCount += 1
        lastError = lastEvent.errorComment ?? "Unknown HLS error"

        nativeVideoLog("""
                  HLS Error Log:
                  - Error Domain: \(lastEvent.errorDomain)
                  - Error Code: \(lastEvent.errorStatusCode)
                  - Error Comment: \(lastEvent.errorComment ?? "None")
                  - Server Address: \(lastEvent.serverAddress ?? "Unknown")
              """)
    }

    /// Handles playback stalls
    @objc private func handlePlaybackStall(_ notification: Notification) {
        nativeVideoLog("HLS: Playback stalled, attempting to recover...")
        isBuffering = true

        // Attempt to recover from stall
        if let player = player {
            player.play()
        }
    }

    private func resetHLSVariantColorDescriptors() {
        hlsVariantColorLock.lock()
        hlsVariantColorDescriptors.removeAll(keepingCapacity: false)
        hlsVariantColorLock.unlock()
    }

    private func replaceHLSVariantColorDescriptors(_ descriptors: [HLSVariantColorDescriptor]) {
        hlsVariantColorLock.lock()
        hlsVariantColorDescriptors = descriptors
        hlsVariantColorLock.unlock()
    }

    private func updateSelectedHLSVariantColor(indicatedBitRate: Double) {
        hlsVariantColorLock.lock()
        let descriptors = hlsVariantColorDescriptors
        hlsVariantColorLock.unlock()
        guard !descriptors.isEmpty else { return }

        let selected: HLSVariantColorDescriptor?
        if descriptors.count == 1 {
            selected = descriptors[0]
        } else {
            let ranked = descriptors.compactMap { descriptor -> (HLSVariantColorDescriptor, Double)? in
                descriptor.relativeDistance(to: indicatedBitRate).map { (descriptor, $0) }
            }.sorted { $0.1 < $1.1 }
            // AVPlayerItemAccessLogEvent.indicatedBitrate should match the declared variant. A
            // generous tolerance permits muxed audio rounding without guessing between variants.
            selected = ranked.first.flatMap { $0.1 <= 0.10 ? $0.0 : nil }
        }
        if let selected = selected {
            setVideoColorInfo(selected.colorInfo)
        }
    }

    /// Extracts both quality choices and their declared dynamic range from HLS variants.
    private func extractHLSVariants(from asset: AVAsset, generation: UInt64) {
        if #available(macOS 13.0, *) {
            Task {
                do {
                    // For HLS streams, try to get variant information
                    if let urlAsset = asset as? AVURLAsset {
                        let variants = try await urlAsset.load(.variants)
                        var bitrates: [Float] = []
                        var colorDescriptors: [HLSVariantColorDescriptor] = []
                        for variant in variants {
                            if let peakBitRate = variant.peakBitRate {
                                bitrates.append(Float(peakBitRate))
                            }
                            if let attributes = variant.videoAttributes {
                                colorDescriptors.append(
                                    HLSVariantColorDescriptor(
                                        peakBitRate: variant.peakBitRate,
                                        averageBitRate: variant.averageBitRate,
                                        colorInfo: colorInfoString(from: attributes)
                                    )
                                )
                            }
                        }

                        guard sourceGeneration == generation else { return }
                        availableBitrates = bitrates.sorted()
                        replaceHLSVariantColorDescriptors(colorDescriptors)
                        updateSelectedHLSVariantColor(indicatedBitRate: Double(currentBitrate))

                        if !availableBitrates.isEmpty {
                            nativeVideoLog("HLS: Available bitrates: \(availableBitrates)")
                        }
                    }
                } catch {
                    nativeVideoLog("Error loading HLS variants: \(error.localizedDescription)")
                }
            }
        }
    }

    /// Sets the preferred maximum bitrate for HLS streams
    func setPreferredMaxBitrate(_ bitrate: Double) {
        preferredPeakBitRate = bitrate
        player?.currentItem?.preferredPeakBitRate = bitrate
        nativeVideoLog("HLS: Set preferred max bitrate to \(bitrate) bps")
    }

    /// Forces a specific bitrate (if available)
    func forceQuality(bitrate: Float) {
        guard isHLSStream else { return }

        // Find the closest available bitrate
        let closest = availableBitrates.min(by: { abs($0 - bitrate) < abs($1 - bitrate) })

        if let targetBitrate = closest {
            setPreferredMaxBitrate(Double(targetBitrate))
        }
    }

    /// Detects the MIME type of a file by reading its magic bytes (file signature)
    private func detectMimeType(at url: URL) -> String? {
        guard url.isFileURL else { return nil }

        do {
            let fileHandle = try FileHandle(forReadingFrom: url)
            defer { try? fileHandle.close() }

            // Read the first 12 bytes to identify the file format
            guard let data = try fileHandle.read(upToCount: 12), data.count >= 4 else {
                return nil
            }

            let bytes = [UInt8](data)

            // MP4/MOV files start with size and 'ftyp' box
            if data.count >= 8 {
                let fourcc = String(bytes: bytes[4..<8], encoding: .ascii) ?? ""
                if fourcc == "ftyp" {
                    // Check the brand to differentiate between MP4 and MOV
                    if data.count >= 12 {
                        let brand = String(bytes: bytes[8..<12], encoding: .ascii) ?? ""
                        if brand.contains("qt") {
                            return "video/quicktime"
                        }
                    }
                    return "video/mp4"
                }
            }

            // WebM/Matroska files start with 0x1A 0x45 0xDF 0xA3
            if bytes.count >= 4 && bytes[0] == 0x1A && bytes[1] == 0x45 && bytes[2] == 0xDF && bytes[3] == 0xA3 {
                return "video/webm"
            }

            // FLV files start with 'FLV'
            if bytes.count >= 3 && bytes[0] == 0x46 && bytes[1] == 0x4C && bytes[2] == 0x56 {
                return "video/x-flv"
            }

            // AVI files start with 'RIFF' ... 'AVI '
            if bytes.count >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 &&
               bytes[8] == 0x41 && bytes[9] == 0x56 && bytes[10] == 0x49 && bytes[11] == 0x20 {
                return "video/x-msvideo"
            }

            // MPEG-TS files start with 0x47 (sync byte)
            if bytes[0] == 0x47 {
                return "video/mp2t"
            }

            return nil
        } catch {
            nativeVideoLog("Error detecting MIME type: \(error.localizedDescription)")
            return nil
        }
    }


    /// Extracts metadata from the asset
    private func extractMetadata(from asset: AVAsset) {
        // Reset metadata values
        videoTitle = nil
        videoBitrate = 0
        videoMimeType = nil
        setVideoColorInfo("dynamicRange=UNKNOWN")
        availableBitrates = []
        currentBitrate = 0
        resetHLSVariantColorDescriptors()
        audioChannels = 0
        audioSampleRate = 0

        // Extract title from metadata
        if #available(macOS 13.0, *) {
            Task {
                do {
                    let commonMetadata = try await asset.load(.commonMetadata)
                    if let titleItem = AVMetadataItem.metadataItems(from: commonMetadata, filteredByIdentifier: .commonIdentifierTitle).first {
                        let titleValue = try await titleItem.load(.value)
                        if let title = titleValue as? String {
                            videoTitle = title
                        }
                    }
                } catch {
                    nativeVideoLog("Error loading metadata: \(error.localizedDescription)")
                }
            }
        } else {
            // Fallback for older OS versions
            let commonMetadata = asset.commonMetadata
            if let titleItem = AVMetadataItem.metadataItems(from: commonMetadata, filteredByIdentifier: .commonIdentifierTitle).first,
               let title = titleItem.value as? String {
                videoTitle = title
            }
        }

        // For HLS streams, extract variant information
        if isHLSStream {
            extractHLSVariants(from: asset, generation: sourceGeneration)
            videoMimeType = "application/x-mpegURL"
        }

        // Try to get bitrate from the asset directly
        if let urlAsset = asset as? AVURLAsset, !isHLSStream {
            // Try to get file size for non-HLS content
            do {
                let fileAttributes = try FileManager.default.attributesOfItem(atPath: urlAsset.url.path)
                if let fileSize = fileAttributes[.size] as? NSNumber {
                    let fileSizeInBytes = fileSize.int64Value

                    // Get duration in seconds
                    if #available(macOS 13.0, *) {
                        Task {
                            do {
                                let duration = try await asset.load(.duration)
                                let durationInSeconds = CMTimeGetSeconds(duration)

                                if durationInSeconds > 0 {
                                    // Calculate bitrate: (fileSize * 8) / durationInSeconds
                                    let calculatedBitrate = Int64(Double(fileSizeInBytes * 8) / durationInSeconds)
                                    videoBitrate = calculatedBitrate
                                    nativeVideoLog("Calculated bitrate from file size: \(calculatedBitrate) bits/s")
                                }
                            } catch {
                                nativeVideoLog("Error loading duration: \(error.localizedDescription)")
                            }
                        }
                    } else {
                        let durationInSeconds = CMTimeGetSeconds(asset.duration)

                        if durationInSeconds > 0 {
                            // Calculate bitrate: (fileSize * 8) / durationInSeconds
                            let calculatedBitrate = Int64(Double(fileSizeInBytes * 8) / durationInSeconds)
                            videoBitrate = calculatedBitrate
                            nativeVideoLog("Calculated bitrate from file size: \(calculatedBitrate) bits/s")
                        }
                    }
                }
            } catch {
                // This is expected for HLS streams
                if !isHLSStream {
                    nativeVideoLog("Error getting file attributes: \(error.localizedDescription)")
                }
            }
        }

        // Extract format information
        if #available(macOS 13.0, *) {
            Task {
                do {
                    // Load tracks asynchronously
                    let videoTracks = try await asset.loadTracks(withMediaType: .video)
                    let audioTracks = try await asset.loadTracks(withMediaType: .audio)

                    // Extract video bitrate and format
                    if let videoTrack = videoTracks.first {
                        // Try to get estimated data rate directly from the track
                        if #available(macOS 13.0, *) {
                            do {
                                let estimatedDataRate = try await videoTrack.load(.estimatedDataRate)
                                if estimatedDataRate > 0 && !isHLSStream {
                                    videoBitrate = Int64(estimatedDataRate)
                                    nativeVideoLog("Got bitrate from estimatedDataRate: \(videoBitrate) bits/s")
                                }
                            } catch {
                                nativeVideoLog("Error getting estimatedDataRate: \(error.localizedDescription)")
                            }
                        }

                        // Get estimated data rate (bitrate) from format description
                        let formatDescriptions = try await videoTrack.load(.formatDescriptions)
                        if let formatDescription = formatDescriptions.first {
                            setVideoColorInfo(colorInfoString(from: formatDescription))
                            let extensions = CMFormatDescriptionGetExtensions(formatDescription) as Dictionary?
                            if let dict = extensions,
                               let bitrate = dict[kCMFormatDescriptionExtension_VerbatimSampleDescription] as? Dictionary<String, Any>,
                               let avgBitrate = bitrate["avg-bitrate"] as? Int64 {
                                videoBitrate = avgBitrate
                                nativeVideoLog("Got bitrate from format description: \(videoBitrate) bits/s")
                            }

                            // Get MIME type for non-HLS content
                            if !isHLSStream {
                                let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)
                                let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

                                if mediaType == kCMMediaType_Video {
                                    switch mediaSubType {
                                    case kCMVideoCodecType_DolbyVisionHEVC:
                                        videoMimeType = "video/hevc"
                                    case kCMVideoCodecType_H264:
                                        videoMimeType = "video/h264"
                                    case kCMVideoCodecType_HEVC:
                                        videoMimeType = "video/hevc"
                                    case kCMVideoCodecType_MPEG4Video:
                                        videoMimeType = "video/mp4v-es"
                                    case kCMVideoCodecType_MPEG2Video:
                                        videoMimeType = "video/mpeg2"
                                    default:
                                        videoMimeType = "video/mp4"
                                    }
                                }
                            }
                        }
                    }

                    // Extract audio channels and sample rate
                    if let audioTrack = audioTracks.first {
                        let formatDescriptions = try await audioTrack.load(.formatDescriptions)
                        if let formatDescription = formatDescriptions.first  {
                            let basicDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
                            if let basicDesc = basicDescription {
                                audioChannels = Int(basicDesc.pointee.mChannelsPerFrame)
                                audioSampleRate = Int(basicDesc.pointee.mSampleRate)
                            }
                        }
                    }
                } catch {
                    nativeVideoLog("Error extracting metadata: \(error.localizedDescription)")
                }
            }
        } else {
            // Fallback for older OS versions
            // Extract video bitrate and format
            if let videoTrack = asset.tracks(withMediaType: .video).first {
                // Try to get estimated data rate directly from the track
                let estimatedDataRate = videoTrack.estimatedDataRate
                if estimatedDataRate > 0 && !isHLSStream {
                    videoBitrate = Int64(estimatedDataRate)
                    nativeVideoLog("Got bitrate from estimatedDataRate (legacy): \(videoBitrate) bits/s")
                }

                if let formatDescriptions = videoTrack.formatDescriptions as? [CMFormatDescription],
                   let formatDescription = formatDescriptions.first {
                    setVideoColorInfo(colorInfoString(from: formatDescription))
                    let extensions = CMFormatDescriptionGetExtensions(formatDescription) as Dictionary?
                    if let dict = extensions,
                       let bitrate = dict[kCMFormatDescriptionExtension_VerbatimSampleDescription] as? Dictionary<String, Any>,
                       let avgBitrate = bitrate["avg-bitrate"] as? Int64 {
                        videoBitrate = avgBitrate
                        nativeVideoLog("Got bitrate from format description (legacy): \(videoBitrate) bits/s")
                    }

                    // Get MIME type for non-HLS content
                    if !isHLSStream {
                        let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)
                        let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

                        if mediaType == kCMMediaType_Video {
                            switch mediaSubType {
                            case kCMVideoCodecType_DolbyVisionHEVC:
                                videoMimeType = "video/hevc"
                            case kCMVideoCodecType_H264:
                                videoMimeType = "video/h264"
                            case kCMVideoCodecType_HEVC:
                                videoMimeType = "video/hevc"
                            case kCMVideoCodecType_MPEG4Video:
                                videoMimeType = "video/mp4v-es"
                            case kCMVideoCodecType_MPEG2Video:
                                videoMimeType = "video/mpeg2"
                            default:
                                videoMimeType = "video/mp4"
                            }
                        }
                    }
                }
            }

            // Extract audio channels and sample rate
            if let audioTrack = asset.tracks(withMediaType: .audio).first {
                if let formatDescriptions = audioTrack.formatDescriptions as? [CMAudioFormatDescription],
                   let formatDescription = formatDescriptions.first {
                    let basicDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
                    if let basicDesc = basicDescription {
                        audioChannels = Int(basicDesc.pointee.mChannelsPerFrame)
                        audioSampleRate = Int(basicDesc.pointee.mSampleRate)
                    }
                }
            }
        }
    }

    /// Detects the video's native frame rate from its asset
    private func detectVideoFrameRate(from asset: AVAsset) {
        // Poll HLS at the display cadence until AVFoundation exposes the selected
        // variant's nominal rate. This preserves 50/60 fps bounded VOD bridges.
        if isHLSStream {
            videoFrameRate = screenRefreshRate > 0 ? screenRefreshRate : 60.0
            updateCaptureFrameRate()
        }
        let fallbackFrameRate = isHLSStream && screenRefreshRate > 0 ? screenRefreshRate : 30.0

        asset.loadTracks(withMediaType: .video) { [self] tracks, error in
            guard let videoTrack = tracks?.first, error == nil else {
                nativeVideoLog(
                    "Error loading video tracks: \(error?.localizedDescription ?? "Unknown")"
                )
                return
            }

            // Replace deprecated nominalFrameRate property
            if #available(macOS 13.0, *) {
                Task {
                    do {
                        let frameRate = try await videoTrack.load(.nominalFrameRate)
                        self.videoFrameRate = Float(frameRate)
                        if self.videoFrameRate <= 0 {
                            self.videoFrameRate = fallbackFrameRate
                        }

                        // Set capture rate to the lower of the two rates
                        self.updateCaptureFrameRate()
                    } catch {
                        nativeVideoLog("Error loading nominal frame rate: \(error.localizedDescription)")
                        self.videoFrameRate = fallbackFrameRate
                        self.updateCaptureFrameRate()
                    }
                }
            } else {
                // Use deprecated property for older OS versions
                videoFrameRate = Float(videoTrack.nominalFrameRate)
                if videoFrameRate <= 0 {
                    videoFrameRate = fallbackFrameRate
                }

                // Set capture rate to the lower of the two rates
                updateCaptureFrameRate()
            }
        }
    }

    /// Updates the capture frame rate based on screen and video rates
    private func updateCaptureFrameRate() {
        captureFrameRate = min(screenRefreshRate, videoFrameRate)
        // Update display link if it exists
        if isPlaying {
            configureDisplayLink()
        }
    }

    /// Opens the video from the given URI (local or network)
    func openUri(_ uri: String) {
        openUri(uri, requestHeaders: [:])
    }

    /// Invalidates readiness synchronously before an asynchronous source replacement starts.
    /// Without this edge, a JVM poll can observe the previous item as ready and resume playback
    /// before the replacement AVPlayer has been installed.
    func beginOpening() {
        cancelPendingReplacement()
        isReadyForPlayback = false
        pendingPlay = false
    }

    func getIsReadyForPlayback() -> Bool { return isReadyForPlayback }

    /** Starts warming a second AVPlayer without touching the active player or its layer. */
    func prepareUriReplacement(_ uri: String, requestHeaders: [String: String]) -> UInt64 {
        cancelPendingReplacement()
        replacementSequence &+= 1
        if replacementSequence == 0 { replacementSequence = 1 }
        let token = replacementSequence

        let url: URL = {
            if let parsedURL = URL(string: uri), parsedURL.scheme != nil {
                return parsedURL
            }
            return URL(fileURLWithPath: uri)
        }()
        let replacementIsHLS = isHLSUrl(url)
        var assetOptions: [String: Any] = [:]
        if let mimeType = detectMimeType(at: url) {
            assetOptions["AVURLAssetOutOfBandMIMETypeKey"] = mimeType
        }
        if !requestHeaders.isEmpty {
            assetOptions["AVURLAssetHTTPHeaderFieldsKey"] = requestHeaders
        }
        var asset = AVURLAsset(url: url, options: assetOptions.isEmpty ? nil : assetOptions)
        if replacementIsHLS {
            asset = configureHLSAsset(asset, requestHeaders: requestHeaders)
        }

        let item = AVPlayerItem(asset: asset)
        if replacementIsHLS {
            item.preferredForwardBufferDuration = 5.0
            if preferredPeakBitRate > 0 {
                item.preferredPeakBitRate = preferredPeakBitRate
            }
            if #available(macOS 13.0, *) {
                item.automaticallyPreservesTimeOffsetFromLive = true
            }
        }

        // This output is only a readiness probe. It proves that the candidate has decoded a real
        // frame before the visible AVPlayerLayer is switched. The final HDR/SDR output is attached
        // by configureVideoOutput only after commit.
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]
        let warmupOutput = AVPlayerItemVideoOutput(pixelBufferAttributes: attributes)
        warmupOutput.suppressesPlayerRendering = false
        warmupOutput.requestNotificationOfMediaDataChange(withAdvanceInterval: 0.03)
        item.add(warmupOutput)

        let candidatePlayer = AVPlayer(playerItem: item)
        candidatePlayer.volume = 0
        candidatePlayer.automaticallyWaitsToMinimizeStalling = true
        let replacement = PreparedPlaybackReplacement(
            token: token,
            asset: asset,
            isHLS: replacementIsHLS,
            item: item,
            player: candidatePlayer,
            warmupOutput: warmupOutput
        )
        pendingReplacement = replacement
        replacement.statusObserver = item.observe(\.status, options: [.initial, .new]) {
            [weak self, weak replacement] item, _ in
            DispatchQueue.main.async {
                guard let self = self, let replacement = replacement else { return }
                self.handleReplacementItemStatus(item, replacement: replacement)
            }
        }
        return token
    }

    private func handleReplacementItemStatus(
        _ item: AVPlayerItem,
        replacement: PreparedPlaybackReplacement
    ) {
        guard pendingReplacement === replacement, replacement.status == 0 else { return }
        switch item.status {
        case .readyToPlay:
            guard !replacement.warmupStartRequested else { return }
            replacement.warmupStartRequested = true
            if replacement.isHLS {
                // A growing bounded playlist looks live to AVPlayer. Without this explicit seek,
                // the invisible candidate may warm up at its temporary live edge and commit
                // several seconds past the requested source position.
                let earliestTime = replacement.item.seekableTimeRanges
                    .compactMap { $0.timeRangeValue.start }
                    .first ?? .zero
                replacement.warmupOriginTime = earliestTime
                replacement.player.seek(
                    to: earliestTime,
                    toleranceBefore: .zero,
                    toleranceAfter: .zero
                ) { [weak self, weak replacement] _ in
                    DispatchQueue.main.async {
                        guard let self = self, let replacement = replacement else { return }
                        self.beginReplacementFramePolling(replacement)
                    }
                }
            } else {
                beginReplacementFramePolling(replacement)
            }
        case .failed:
            failReplacement(
                replacement,
                message: item.error?.localizedDescription ?? "The replacement AVPlayerItem failed"
            )
        case .unknown:
            break
        @unknown default:
            failReplacement(replacement, message: "The replacement AVPlayerItem has an unknown status")
        }
    }

    private func beginReplacementFramePolling(_ replacement: PreparedPlaybackReplacement) {
        guard pendingReplacement === replacement,
              replacement.status == 0,
              replacement.framePollTimer == nil
        else {
            return
        }
        replacement.player.playImmediately(atRate: max(playbackSpeed, 0.5))
        let timer = Timer(timeInterval: 1.0 / 120.0, repeats: true) { [weak self, weak replacement] _ in
            guard let self = self, let replacement = replacement else { return }
            self.pollReplacementFrame(replacement)
        }
        RunLoop.main.add(timer, forMode: .common)
        replacement.framePollTimer = timer
        pollReplacementFrame(replacement)
    }

    private func pollReplacementFrame(_ replacement: PreparedPlaybackReplacement) {
        guard pendingReplacement === replacement, replacement.status == 0 else { return }
        if replacement.item.status == .failed {
            failReplacement(
                replacement,
                message: replacement.item.error?.localizedDescription ?? "The replacement AVPlayerItem failed"
            )
            return
        }
        guard let output = replacement.warmupOutput else {
            failReplacement(replacement, message: "The replacement frame probe was detached")
            return
        }

        let hostTime = CACurrentMediaTime()
        let hostMappedTime = output.itemTime(forHostTime: hostTime)
        let itemTime = replacement.item.currentTime()
        let candidateTimes = [hostMappedTime, itemTime]
        for time in candidateTimes {
            guard output.hasNewPixelBuffer(forItemTime: time),
                  let pixelBuffer = output.copyPixelBuffer(
                      forItemTime: time,
                      itemTimeForDisplay: nil
                  )
            else {
                continue
            }
            replacement.firstFrame = pixelBuffer
            replacement.player.pause()
            replacement.framePollTimer?.invalidate()
            replacement.framePollTimer = nil
            // Playing the invisible HLS candidate is the most reliable way to prove that
            // AVFoundation can decode it, but the probe can advance several seconds before the
            // first frame becomes observable. Rewind to the candidate's original timeline point
            // before publishing readiness so a paused bridge seek commits at the requested source
            // position instead of inheriting warm-up drift.
            replacement.player.seek(
                to: replacement.warmupOriginTime,
                toleranceBefore: .zero,
                toleranceAfter: .zero
            ) { [weak self, weak replacement] finished in
                DispatchQueue.main.async {
                    guard let self = self,
                          let replacement = replacement,
                          self.pendingReplacement === replacement,
                          replacement.status == 0
                    else {
                        return
                    }
                    if finished {
                        replacement.status = 1
                    } else {
                        self.failReplacement(
                            replacement,
                            message: "AVFoundation could not rewind the decoded replacement frame"
                        )
                    }
                }
            }
            return
        }
    }

    private func failReplacement(_ replacement: PreparedPlaybackReplacement, message: String) {
        guard pendingReplacement === replacement else { return }
        replacement.player.pause()
        replacement.framePollTimer?.invalidate()
        replacement.framePollTimer = nil
        replacement.status = -1
        replacement.errorMessage = message
    }

    func getUriReplacementStatus(_ token: UInt64) -> Int32 {
        guard let replacement = pendingReplacement, replacement.token == token else { return -2 }
        return replacement.status
    }

    func getUriReplacementError(_ token: UInt64) -> String? {
        guard let replacement = pendingReplacement, replacement.token == token else {
            return "The prepared replacement was superseded"
        }
        return replacement.errorMessage
    }

    /** Atomically points the existing native surface at the already-decoded replacement player. */
    func commitUriReplacement(_ token: UInt64) -> Bool {
        guard let replacement = pendingReplacement,
              replacement.token == token,
              replacement.status == 1,
              let firstFrame = replacement.firstFrame
        else {
            return false
        }

        replacement.stopWarmup(removeOutput: true)

        // Keep the old AVPlayerLayer content visible until all candidate preparation has finished.
        // Everything below executes in one AppKit-main-queue turn.
        stopDisplayLink()
        player?.pause()
        cleanupObservers()
        detachHdr10PlusProbe()
        hdrMetalRenderer?.detachFromItem()
        if let oldOutput = videoOutput, let oldItem = player?.currentItem {
            oldItem.remove(oldOutput)
        }
        videoOutput = nil

        sourceGeneration &+= 1
        let generation = sourceGeneration
        isHLSStream = replacement.isHLS
        let presentationSize = replacement.item.presentationSize
        let decodedWidth = CVPixelBufferGetWidth(firstFrame)
        let decodedHeight = CVPixelBufferGetHeight(firstFrame)
        frameWidth = presentationSize.width > 0 ? Int(presentationSize.width.rounded()) : decodedWidth
        frameHeight = presentationSize.height > 0 ? Int(presentationSize.height.rounded()) : decodedHeight
        nativeVideoWidth = frameWidth
        nativeVideoHeight = frameHeight
        resetPlaybackMetrics()
        extractMetadata(from: replacement.asset)
        detectVideoFrameRate(from: replacement.asset)
        installActivePlayer(
            replacement.player,
            item: replacement.item,
            generation: generation
        )
        retainLatestPixelBuffer(firstFrame)
        hdrMetalRenderer?.renderCurrentFrame()
        isReadyForPlayback = true
        isPlaying = false
        pendingPlay = false
        pendingReplacement = nil
        return true
    }

    func cancelUriReplacement(_ token: UInt64) {
        guard let replacement = pendingReplacement,
              token == 0 || replacement.token == token
        else {
            return
        }
        replacement.stopWarmup(removeOutput: true)
        pendingReplacement = nil
    }

    private func cancelPendingReplacement() {
        guard let replacement = pendingReplacement else { return }
        replacement.stopWarmup(removeOutput: true)
        pendingReplacement = nil
    }

    /// Opens the video from the given URI (local or network) with HTTP headers for remote assets.
    func openUri(_ uri: String, requestHeaders: [String: String]) {
        isReadyForPlayback = false
        pendingPlay = false
        sourceGeneration &+= 1
        let generation = sourceGeneration
        resetPlaybackMetrics()

        // Clean up previous observers
        cleanupObservers()

        // Determine the URL (local or network)
        let url: URL = {
            if let parsedURL = URL(string: uri), parsedURL.scheme != nil {
                return parsedURL
            } else {
                return URL(fileURLWithPath: uri)
            }
        }()

        // Check if this is an HLS stream
        isHLSStream = isHLSUrl(url)

        if isHLSStream {
            nativeVideoLog("Detected HLS stream: \(url)")
        }

        let mimeType = detectMimeType(at:url)
        var assetOptions: [String: Any] = [:]
        if let mimeType = mimeType {
            assetOptions["AVURLAssetOutOfBandMIMETypeKey"] = mimeType
        }
        if !requestHeaders.isEmpty {
            assetOptions["AVURLAssetHTTPHeaderFieldsKey"] = requestHeaders
        }
        var asset = AVURLAsset(url: url, options: assetOptions.isEmpty ? nil : assetOptions)
        // Configure asset for HLS if needed
        if isHLSStream {
            asset = configureHLSAsset(asset, requestHeaders: requestHeaders)
        }

        // Extract metadata from the asset
        extractMetadata(from: asset)

        // Detect video frame rate
        detectVideoFrameRate(from: asset)

        // Retrieve the video track to obtain the actual dimensions
        asset.loadTracks(withMediaType: .video) { [self] tracks, error in
            guard sourceGeneration == generation else { return }
            guard let videoTrack = tracks?.first, error == nil else {
                nativeVideoLog(
                    "Error loading video tracks: \(error?.localizedDescription ?? "Unknown")"
                )
                // For HLS streams without video track info yet, use default dimensions
                if isHLSStream {
                    frameWidth = 1920
                    frameHeight = 1080
                    nativeVideoWidth = frameWidth
                    nativeVideoHeight = frameHeight
                    setupVideoOutputAndPlayer(with: asset, generation: generation)
                }
                return
            }

            if #available(macOS 13.0, *) {
                Task { [weak self, asset] in
                    guard let self = self else { return }
                    do {
                        // Use the modern API to load naturalSize and preferredTransform
                        let naturalSize = try await videoTrack.load(.naturalSize)
                        let transform = try await videoTrack.load(.preferredTransform)
                        guard self.sourceGeneration == generation else { return }

                        let effectiveSize = naturalSize.applying(transform)
                        self.frameWidth = Int(abs(effectiveSize.width))
                        self.frameHeight = Int(abs(effectiveSize.height))
                        self.nativeVideoWidth = self.frameWidth
                        self.nativeVideoHeight = self.frameHeight

                        // Build a video composition that applies the preferred transform so
                        // that AVPlayerItemVideoOutput delivers pixel buffers already rotated
                        // to display orientation (fixes portrait videos rendering sideways).
                        let videoComposition: AVVideoComposition? = self.isHLSStream
                            ? nil
                            : (try? await AVVideoComposition.videoComposition(withPropertiesOf: asset))

                        // Continue with player setup
                        self.setupVideoOutputAndPlayer(
                            with: asset,
                            videoComposition: videoComposition,
                            generation: generation
                        )
                    } catch {
                        guard self.sourceGeneration == generation else { return }
                        nativeVideoLog("Error loading video track properties: \(error.localizedDescription)")
                        // Use default dimensions for HLS if loading fails
                        if self.isHLSStream {
                            self.frameWidth = 1920
                            self.frameHeight = 1080
                            self.setupVideoOutputAndPlayer(
                                with: asset,
                                videoComposition: nil,
                                generation: generation
                            )
                        }
                    }
                }
            } else {
                // Fallback for older OS versions using deprecated properties
                let naturalSize = videoTrack.naturalSize
                let transform = videoTrack.preferredTransform

                let effectiveSize = naturalSize.applying(transform)
                frameWidth = Int(abs(effectiveSize.width))
                frameHeight = Int(abs(effectiveSize.height))
                nativeVideoWidth = frameWidth
                nativeVideoHeight = frameHeight

                // Build a video composition that applies the preferred transform (see modern
                // path above for rationale). Skip for HLS streams.
                let videoComposition: AVVideoComposition? = isHLSStream
                    ? nil
                    : AVMutableVideoComposition(propertiesOf: asset)

                // Continue with player setup
                setupVideoOutputAndPlayer(
                    with: asset,
                    videoComposition: videoComposition,
                    generation: generation
                )
            }
        }
    }

    // Retains the latest CVPixelBuffer for zero-copy JNI access.
    // Updates frame dimensions for HLS streams where resolution may change dynamically.
    private func retainLatestPixelBuffer(_ pixelBuffer: CVPixelBuffer) {
        let w = CVPixelBufferGetWidth(pixelBuffer)
        let h = CVPixelBufferGetHeight(pixelBuffer)
        if isHLSStream && (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            nativeVideoWidth = w
            nativeVideoHeight = h
        }
        bufferLock.lock()
        latestPixelBuffer = pixelBuffer
        bufferLock.unlock()
    }

    // Locks the latest CVPixelBuffer and returns its base address for direct reading.
    // outInfo must point to an array of 3 int32_t: [width, height, bytesPerRow].
    // Caller MUST call unlockLatestFrame() after reading.
    func lockLatestFrame(_ outInfo: UnsafeMutablePointer<Int32>) -> UnsafeMutableRawPointer? {
        bufferLock.lock()
        guard let pb = latestPixelBuffer else {
            bufferLock.unlock()
            return nil
        }
        lockedPixelBuffer = pb
        bufferLock.unlock()

        CVPixelBufferLockBaseAddress(pb, .readOnly)
        guard let addr = CVPixelBufferGetBaseAddress(pb) else {
            CVPixelBufferUnlockBaseAddress(pb, .readOnly)
            lockedPixelBuffer = nil
            return nil
        }
        outInfo[0] = Int32(CVPixelBufferGetWidth(pb))
        outInfo[1] = Int32(CVPixelBufferGetHeight(pb))
        outInfo[2] = Int32(CVPixelBufferGetBytesPerRow(pb))
        return addr
    }

    // Unlocks the CVPixelBuffer previously locked by lockLatestFrame().
    func unlockLatestFrame() {
        if let pb = lockedPixelBuffer {
            CVPixelBufferUnlockBaseAddress(pb, .readOnly)
            lockedPixelBuffer = nil
        }
    }

    // Helper method to setup video output and player
    private func setupVideoOutputAndPlayer(
        with asset: AVAsset,
        videoComposition: AVVideoComposition? = nil,
        generation: UInt64
    ) {
        guard sourceGeneration == generation else { return }
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.setupVideoOutputAndPlayer(
                    with: asset,
                    videoComposition: videoComposition,
                    generation: generation
                )
            }
            return
        }
        guard sourceGeneration == generation else { return }
        let item = AVPlayerItem(asset: asset)

        // Apply the video composition (if any) so that pixel buffers delivered to
        // AVPlayerItemVideoOutput are pre-rotated to the display orientation.
        if let videoComposition = videoComposition {
            item.videoComposition = videoComposition
        }

        configureItemForActivePlayback(item)
        installActivePlayer(AVPlayer(playerItem: item), item: item, generation: generation)
    }

    private func configureItemForActivePlayback(_ item: AVPlayerItem) {
        guard isHLSStream else { return }
        item.preferredForwardBufferDuration = 5.0
        if preferredPeakBitRate > 0 {
            item.preferredPeakBitRate = preferredPeakBitRate
        }
        if #available(macOS 13.0, *) {
            item.automaticallyPreservesTimeOffsetFromLive = true
        }
    }

    /** Installs an already-created player into the existing renderer and observer graph. */
    private func installActivePlayer(
        _ newPlayer: AVPlayer,
        item: AVPlayerItem,
        generation: UInt64
    ) {
        configureItemForActivePlayback(item)
        if isHLSStream {
            setupHLSMonitoring(for: item)
        }
        configureVideoOutput(for: item)
        player = newPlayer
        isReadyForPlayback = item.status == .readyToPlay
        playerItemStatusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                self?.handlePlayerItemStatus(item, generation: generation)
            }
        }
        if prefersHdrMetalOutput, useHdrPlayerLayerForSurface {
            configureHdrPlayerLayer(with: player)
        }

        // Monitor time control status for all media types (buffering, paused, playing)
        timeControlStatusObserver = player?.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            self?.handleTimeControlStatus(player.timeControlStatus)
        }

        presentationSizeObserver = item.observe(\.presentationSize, options: [.initial, .new]) { [weak self] item, _ in
            self?.updateCachedDisplayAspectRatio(from: item.presentationSize)
        }

        // Observe end of playback for all media types
        playbackEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: nil
        ) { [weak self] _ in
            self?.markDidPlayToEnd()
        }

        // Configure player for HLS
        if isHLSStream {
            player?.automaticallyWaitsToMinimizeStalling = true
        }

        setupAudioTap(for: item)

        // Set initial volume
        player?.volume = volume

        // For non-HLS content, capture initial frame
        if !isHLSStream {
            captureInitialFrame()
        }
    }

    private func handlePlayerItemStatus(_ item: AVPlayerItem, generation: UInt64) {
        guard sourceGeneration == generation, player?.currentItem === item else { return }
        switch item.status {
        case .readyToPlay:
            isReadyForPlayback = true
            if pendingPlay {
                pendingPlay = false
                play()
            }
        case .failed:
            isReadyForPlayback = false
            pendingPlay = false
            nativeVideoLog("AVPlayerItem failed: \(item.error?.localizedDescription ?? "Unknown error")")
        case .unknown:
            isReadyForPlayback = false
        @unknown default:
            isReadyForPlayback = false
        }
    }

    /** Reconfigures the current item when the requested native-HDR/SDR route changes at runtime. */
    private func configureVideoOutput(for item: AVPlayerItem) {
        if let previousOutput = videoOutput {
            player?.currentItem?.remove(previousOutput)
            videoOutput = nil
        }

        if prefersHdrMetalOutput, HdrMetalVideoRenderer.isAvailable {
            videoOutput = nil
            if useHdrPlayerLayerForSurface {
                hdrMetalRenderer?.detachFromItem()
                attachHdr10PlusProbe(to: item)
            } else {
                detachHdr10PlusProbe()
                if hdrMetalRenderer == nil {
                    hdrMetalRenderer = makeHdrMetalRenderer()
                }
                if let configuration = metalProjectionConfiguration {
                    _ = hdrMetalRenderer?.configure(configuration)
                }
                hdrMetalRenderer?.attach(to: item)
            }
        } else {
            if useHdrPlayerLayerForSurface {
                attachHdr10PlusProbe(to: item)
            } else {
                detachHdr10PlusProbe()
            }
            hdrMetalRenderer?.detachFromItem()
            var outputSettings: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            ]
            // HLS can expose its dimensions only after a variant is selected and may change
            // resolution later. Omitting a requested size preserves the decoded pixel-buffer
            // dimensions instead of scaling an unknown stream to the temporary 1920x1080 value.
            if !isHLSStream, frameWidth > 0, frameHeight > 0 {
                outputSettings[kCVPixelBufferWidthKey as String] = frameWidth
                outputSettings[kCVPixelBufferHeightKey as String] = frameHeight
            }
            if toneMapsHdrToSdr {
                outputSettings[AVVideoColorPropertiesKey] = [
                    AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                    AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                    AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
                ]
            }
            videoOutput = AVPlayerItemVideoOutput(outputSettings: outputSettings)
            if let output = videoOutput {
                item.add(output)
            }
        }
    }

    /// Captures initial frame to display without starting the display link
    private func captureInitialFrame() {
        guard let output = videoOutput, player?.currentItem != nil, !isHLSStream else { return }

        // Seek to the beginning to ensure we have a frame
        let zeroTime = CMTime.zero
        player?.seek(to: zeroTime)

        // Try to get the first frame
        if output.hasNewPixelBuffer(forItemTime: zeroTime),
           let pixelBuffer = output.copyPixelBuffer(forItemTime: zeroTime, itemTimeForDisplay: nil)
        {
            retainLatestPixelBuffer(pixelBuffer)
        }
    }

    /// Configures the timer with the appropriate frame rate
    private func configureDisplayLink() {
        stopDisplayLink()  // Ensure previous link is invalidated

        // For macOS, use a timer with the appropriate interval
        let interval = 1.0 / Double(captureFrameRate)
        displayLink = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            self?.captureFrame()
        }
    }

    /// Stops the timer
    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    /// Captures the latest frame from the video output if available.
    @objc private func captureFrame() {
        guard let output = videoOutput,
              let item = player?.currentItem,
              isPlaying == true
        else { return }  // Skip capture if video is not playing

        let currentTime = item.currentTime()
        if output.hasNewPixelBuffer(forItemTime: currentTime),
           let pixelBuffer = output.copyPixelBuffer(
               forItemTime: currentTime, itemTimeForDisplay: nil)
        {
            retainLatestPixelBuffer(pixelBuffer)
        }
    }


    // MARK: - Audio Tap Callbacks

    /// Callback: Initialization of the tap.
    private let tapInit: MTAudioProcessingTapInitCallback = { (tap, clientInfo, tapStorageOut) in
        // Initialize tap storage (e.g. to store cumulative values if needed)
        tapStorageOut.pointee = clientInfo
    }

    /// Callback: Finalize the tap.
    private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { (tap) in
        // Cleanup if necessary.
    }

    /// Callback: Prepare the tap (called before processing).
    private let tapPrepare: MTAudioProcessingTapPrepareCallback = {
        (tap, maxFrames, processingFormat) in
        // You can set up buffers or other resources here if needed.
    }

    /// Callback: Unprepare the tap (called after processing).
    private let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { (tap) in
        // Release any resources allocated in prepare.
    }

    /// Callback: Process audio (pass-through).
    private let tapProcess: MTAudioProcessingTapProcessCallback = {
        (tap, numberFrames, flags, bufferListInOut, numberFramesOut, flagsOut) in

        // Retrieve the audio buffers so they flow through the pipeline
        let status = MTAudioProcessingTapGetSourceAudio(
            tap, numberFrames, bufferListInOut, flagsOut, nil, nil)
        if status != noErr {
            nativeVideoLog("MTAudioProcessingTapGetSourceAudio failed with status: \(status)")
            return
        }

        numberFramesOut.pointee = numberFrames
    }

    // In the setupAudioTap method, add audio format verification and logging
    private func setupAudioTap(for playerItem: AVPlayerItem) {
        guard let asset = playerItem.asset as? AVURLAsset else {
            nativeVideoLog("Asset is not an AVURLAsset")
            return
        }

        // Load audio tracks asynchronously
        asset.loadTracks(withMediaType: .audio) { tracks, error in
            guard let audioTrack = tracks?.first, error == nil else {
                nativeVideoLog("No audio track found or error: \(error?.localizedDescription ?? "unknown")")
                return
            }

            nativeVideoLog("Audio track found, setting up tap")

            // Create input parameters with a processing tap
            let inputParams = AVMutableAudioMixInputParameters(track: audioTrack)

            var callbacks = MTAudioProcessingTapCallbacks(
                version: kMTAudioProcessingTapCallbacksVersion_0,
                clientInfo: UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()),
                init: self.tapInit,
                finalize: self.tapFinalize,
                prepare: self.tapPrepare,
                unprepare: self.tapUnprepare,
                process: self.tapProcess
            )

            // Create the audio processing tap
            // On macOS 26+ (Swift 6.2+), MTAudioProcessingTapCreate returns
            // MTAudioProcessingTap? directly instead of Unmanaged<MTAudioProcessingTap>?
            #if compiler(>=6.2)
            var tap: MTAudioProcessingTap?
            let status = MTAudioProcessingTapCreate(
                kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
            )
            if status == noErr, let tap = tap {
                nativeVideoLog("Audio tap created successfully")
                inputParams.audioTapProcessor = tap
                let audioMix = AVMutableAudioMix()
                audioMix.inputParameters = [inputParams]
                playerItem.audioMix = audioMix
            } else {
                nativeVideoLog("Audio Tap creation failed with status: \(status)")
            }
            #else
            var tap: Unmanaged<MTAudioProcessingTap>?
            let status = MTAudioProcessingTapCreate(
                kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
            )
            if status == noErr, let tap = tap?.takeRetainedValue() {
                nativeVideoLog("Audio tap created successfully")
                inputParams.audioTapProcessor = tap
                let audioMix = AVMutableAudioMix()
                audioMix.inputParameters = [inputParams]
                playerItem.audioMix = audioMix
            } else {
                nativeVideoLog("Audio Tap creation failed with status: \(status)")
            }
            #endif
        }
    }

    /// Starts video playback and begins frame capture at the optimized frame rate.
    func play() {
        if isReadyForPlayback {
            isPlaying = true
            player?.play()
            // Replacing an AVPlayerItem creates a fresh AVPlayer whose rate starts at 1.0.
            // Reapply the persisted user speed after every source/bridge restart.
            player?.rate = playbackSpeed
            if videoOutput != nil {
                configureDisplayLink()
            }
            hdrMetalRenderer?.start()
        } else {
            // Mark that playback is pending
            pendingPlay = true
        }
    }

    /// Pauses video playback and stops frame capture.
    func pause() {
        isPlaying = false
        player?.pause()
        stopDisplayLink()
        hdrMetalRenderer?.renderCurrentFrame()

        // Capture the current frame to display while paused (not for HLS)
        if !isHLSStream, let output = videoOutput, let item = player?.currentItem {
            let currentTime = item.currentTime()
            if output.hasNewPixelBuffer(forItemTime: currentTime),
               let pixelBuffer = output.copyPixelBuffer(
                   forItemTime: currentTime, itemTimeForDisplay: nil)
            {
                retainLatestPixelBuffer(pixelBuffer)
            }
        }
    }

    /// Sets the volume level (0.0 to 1.0)
    func setVolume(level: Float) {
        volume = max(0.0, min(1.0, level))  // Clamp between 0.0 and 1.0

        // Manage the multi-channel case (>2 channels)
        if let playerItem = player?.currentItem, audioChannels > 2 {
            // Apply volume via an AudioMix if we have more than 2 channels
            if #available(macOS 13.0, *) {
                Task { @MainActor in
                    do {
                        let audioTracks = try await playerItem.asset.loadTracks(withMediaType: .audio)
                        if let audioTrack = audioTracks.first {
                            let parameters = AVMutableAudioMixInputParameters(track: audioTrack)
                            parameters.setVolume(volume, at: CMTime.zero)

                            let audioMix = AVMutableAudioMix()
                            audioMix.inputParameters = [parameters]
                            playerItem.audioMix = audioMix
                        }
                    } catch {
                        nativeVideoLog("Error loading audio tracks for volume adjustment: \(error.localizedDescription)")
                    }
                }
            } else {
                // Fallback for older OS versions
                if let audioTrack = playerItem.asset.tracks(withMediaType: .audio).first {
                    let parameters = AVMutableAudioMixInputParameters(track: audioTrack)
                    parameters.setVolume(volume, at: CMTime.zero)

                    let audioMix = AVMutableAudioMix()
                    audioMix.inputParameters = [parameters]
                    playerItem.audioMix = audioMix
                }
            }
        } else {
            // For stereo and mono channels, use the standard method
            player?.volume = volume
        }
    }

    /// Gets the current volume level (0.0 to 1.0)
    func getVolume() -> Float {
        return volume
    }

    /// Sets the playback speed (0.5 to 2.0, where 1.0 is normal speed)
    func setPlaybackSpeed(speed: Float) {
        playbackSpeed = max(0.5, min(2.0, speed))  // Clamp between 0.5 and 2.0
        player?.rate = playbackSpeed
    }

    /// Gets the current playback speed (0.5 to 2.0, where 1.0 is normal speed)
    func getPlaybackSpeed() -> Float {
        return playbackSpeed
    }

    /// Returns the width of the video frame in pixels
    func getFrameWidth() -> Int { return frameWidth }

    /// Returns the height of the video frame in pixels
    func getFrameHeight() -> Int { return frameHeight }

    private func updateCachedDisplayAspectRatio(from size: CGSize) {
        let ratio = size.width > 0 && size.height > 0 ? Double(size.width) / Double(size.height) : 0.0
        aspectLock.lock()
        cachedDisplayAspectRatio = ratio
        aspectLock.unlock()
    }

    /// Display width / height with clean aperture and non-square pixels already applied.
    func getDisplayAspectRatio() -> Double {
        aspectLock.lock()
        defer { aspectLock.unlock() }
        return cachedDisplayAspectRatio
    }

    /// Scales the output to fit within (width, height) while preserving the native aspect ratio.
    /// Never upscales beyond the native resolution. Recreates the pixel buffer output at the new size.
    /// Returns true if dimensions actually changed.
    func setOutputSize(width: Int, height: Int) -> Bool {
        guard width > 0, height > 0 else { return false }
        guard nativeVideoWidth > 0, nativeVideoHeight > 0 else { return false }
        if prefersHdrMetalOutput {
            return false
        }

        let scaleX = Double(width) / Double(nativeVideoWidth)
        let scaleY = Double(height) / Double(nativeVideoHeight)
        let scale = min(scaleX, scaleY, 1.0) // never upscale

        // Enforce even dimensions (required by many codecs)
        let newWidth = max(2, (Int(Double(nativeVideoWidth) * scale) / 2) * 2)
        let newHeight = max(2, (Int(Double(nativeVideoHeight) * scale) / 2) * 2)

        if newWidth == frameWidth && newHeight == frameHeight { return false }

        frameWidth = newWidth
        frameHeight = newHeight

        // Recreate AVPlayerItemVideoOutput with updated hint dimensions
        if let item = player?.currentItem {
            if let old = videoOutput {
                item.remove(old)
            }
            let attrs: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: newWidth,
                kCVPixelBufferHeightKey as String: newHeight,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:]
            ]
            let newOutput = AVPlayerItemVideoOutput(pixelBufferAttributes: attrs)
            item.add(newOutput)
            videoOutput = newOutput
        }

        return true
    }

    /// Returns the detected video frame rate
    func getVideoFrameRate() -> Float { return videoFrameRate }

    /// Returns the detected screen refresh rate
    func getScreenRefreshRate() -> Float { return screenRefreshRate }

    /// Returns the current capture frame rate (minimum of video and screen rates)
    func getCaptureFrameRate() -> Float { return captureFrameRate }

    /// Returns the video title if available
    func getVideoTitle() -> String? { return videoTitle }

    /// Returns the video bitrate in bits per second
    func getVideoBitrate() -> Int64 { return videoBitrate }

    /// Returns the video MIME type if available
    func getVideoMimeType() -> String? { return videoMimeType }

    /// Returns a snapshot of the selected track's typed color description.
    func getVideoColorInfo() -> String {
        videoColorInfoLock.lock()
        defer { videoColorInfoLock.unlock() }
        return videoColorInfo
    }

    /// Returns the number of audio channels
    func getAudioChannels() -> Int { return audioChannels }

    /// Returns the audio sample rate in Hz
    func getAudioSampleRate() -> Int { return audioSampleRate }

    /// Returns true if this is an HLS stream
    func getIsHLSStream() -> Bool { return isHLSStream }

    /// Returns available bitrates for HLS streams
    func getAvailableBitrates() -> [Float] { return availableBitrates }

    /// Returns current bitrate for HLS streams
    func getCurrentBitrate() -> Float { return currentBitrate }

    /// Returns buffer status (0.0 to 1.0)
    func getBufferStatus() -> Float { return bufferStatus }

    /// Returns whether the player is currently buffering
    func getIsBuffering() -> Bool { return isBuffering }

    /// Returns network status string
    func getNetworkStatus() -> String { return networkStatus }

    /// Returns last error if any
    func getLastError() -> String? { return lastError }

    /// Returns the duration of the video in seconds.
    func getDuration() -> Double {
        guard let item = player?.currentItem else { return 0 }

        // For live HLS streams, duration might be indefinite
        if isHLSStream && item.duration.isIndefinite {
            return -1  // Indicate live stream
        }

        // Use item.duration which is not deprecated
        return CMTimeGetSeconds(item.duration)
    }

    /// Returns the current playback time in seconds.
    func getCurrentTime() -> Double {
        guard let item = player?.currentItem else { return 0 }
        return CMTimeGetSeconds(item.currentTime())
    }

    /// Seeks to the specified time (in seconds).
    func seekTo(time: Double) {
        guard let player = player else { return }
        let newTime = CMTime(seconds: time, preferredTimescale: 600)
        let completion: (Bool) -> Void = { [weak self] finished in
            guard finished else { return }
            self?.capturePausedFrameAfterSeek()
        }

        // For HLS, use tolerance for more efficient seeking
        if isHLSStream {
            let tolerance = CMTime(seconds: 1.0, preferredTimescale: 600)
            player.seek(
                to: newTime,
                toleranceBefore: tolerance,
                toleranceAfter: tolerance,
                completionHandler: completion
            )
        } else {
            player.seek(to: newTime, completionHandler: completion)
        }
    }

    /// AVPlayer seek is asynchronous. Capture only after it completes so paused canvas/HLS
    /// playback exposes the frame at the destination instead of retaining the pre-seek bitmap.
    private func capturePausedFrameAfterSeek() {
        guard !isPlaying else { return }
        if let output = videoOutput, let item = player?.currentItem {
            let currentTime = item.currentTime()
            if output.hasNewPixelBuffer(forItemTime: currentTime),
               let pixelBuffer = output.copyPixelBuffer(
                   forItemTime: currentTime, itemTimeForDisplay: nil)
            {
                retainLatestPixelBuffer(pixelBuffer)
            }
        }
        hdrMetalRenderer?.renderCurrentFrame()
    }

    /// Consumes the end-of-playback flag. Returns true once per playback completion.
    func consumeDidPlayToEnd() -> Bool {
        playbackEndLock.lock()
        defer { playbackEndLock.unlock() }
        let ended = didPlayToEnd
        didPlayToEnd = false
        return ended
    }

    private func markDidPlayToEnd() {
        playbackEndLock.lock()
        didPlayToEnd = true
        playbackEndLock.unlock()
    }

    private func resetDidPlayToEnd() {
        playbackEndLock.lock()
        didPlayToEnd = false
        playbackEndLock.unlock()
    }

    /// Clean up observers
    private func cleanupObservers() {
        playerItemObserver?.invalidate()
        playerItemStatusObserver?.invalidate()
        playerItemStatusObserver = nil
        playerObserver?.invalidate()
        timeControlStatusObserver?.invalidate()
        bufferEmptyObserver?.invalidate()
        bufferLikelyToKeepUpObserver?.invalidate()
        bufferFullObserver?.invalidate()
        presentationSizeObserver?.invalidate()
        presentationSizeObserver = nil

        aspectLock.lock()
        cachedDisplayAspectRatio = 0.0
        aspectLock.unlock()

        if let observer = playbackEndObserver {
            NotificationCenter.default.removeObserver(observer)
            playbackEndObserver = nil
        }
        resetDidPlayToEnd()

        NotificationCenter.default.removeObserver(self)
    }

    /// Disposes of the video player and releases resources.
    func dispose() {
        cancelPendingReplacement()
        pause()
        cleanupObservers()
        detachHdr10PlusProbe()
        hdrMetalRenderer?.detachFromItem()
        hdrMetalRenderer = nil
        hdrPlayerLayer?.player = nil
        hdrPlayerLayer = nil
        player = nil
        videoOutput = nil
        if let pb = lockedPixelBuffer {
            CVPixelBufferUnlockBaseAddress(pb, .readOnly)
            lockedPixelBuffer = nil
        }
        latestPixelBuffer = nil
    }

    private func configureHdrPlayerLayer(with player: AVPlayer?) {
        let layer = hdrPlayerLayer ?? AVPlayerLayer()
        layer.player = player
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
        layer.isOpaque = true
        layer.wantsExtendedDynamicRangeContent = true
        hdrPlayerLayer = layer
    }

    func getHdrMetalLayer() -> CALayer? {
        if useHdrPlayerLayerForSurface {
            configureHdrPlayerLayer(with: player)
            return hdrPlayerLayer
        }
        if hdrMetalRenderer == nil {
            hdrMetalRenderer = makeHdrMetalRenderer()
            if let item = player?.currentItem {
                hdrMetalRenderer?.attach(to: item)
            }
        }
        hdrMetalRenderer?.start()
        return hdrMetalRenderer?.layer
    }

    func setHdrMetalLayerSize(width: Int32, height: Int32, scale: Double) {
        if useHdrPlayerLayerForSurface {
            let logicalSize = CGSize(width: CGFloat(width), height: CGFloat(height))
            let layer = hdrPlayerLayer ?? AVPlayerLayer()
            layer.bounds = CGRect(origin: .zero, size: logicalSize)
            layer.frame = CGRect(origin: .zero, size: logicalSize)
            layer.contentsScale = max(scale, 1.0)
            hdrPlayerLayer = layer
            return
        }
        hdrMetalRenderer?.setDrawableSize(width: width, height: height, scale: scale)
        // The renderer's common-run-loop timer presents the next frame at the new size.
        // Rendering synchronously here made every AppKit live-resize callback decode and
        // encode an additional frame, which stalls interaction badly for 8K sources.
    }

    @discardableResult
    func setHdrMetalTextureOutput(commandQueue: UnsafeMutableRawPointer?) -> Bool {
        if commandQueue == nil {
            return hdrMetalRenderer?.setTextureOutput(commandQueuePointer: nil) ?? true
        }
        guard usesMetalProjectionSurface else { return false }
        if hdrMetalRenderer == nil {
            hdrMetalRenderer = makeHdrMetalRenderer()
        }
        guard let renderer = hdrMetalRenderer else { return false }
        if let item = player?.currentItem {
            renderer.attach(to: item)
        }
        return renderer.setTextureOutput(commandQueuePointer: commandQueue)
    }

    func setHdrMetalTextureViewportSize(width: Int32, height: Int32) {
        hdrMetalRenderer?.setTextureViewportSize(width: width, height: height)
    }

    func getHdrMetalTextureOutputInfo() -> (
        surface: UnsafeMutableRawPointer,
        width: Int32,
        height: Int32,
        frameSerial: UInt64
    )? {
        guard let info = hdrMetalRenderer?.textureOutputInfo() else { return nil }
        return (
            Unmanaged.passUnretained(info.surface).toOpaque(),
            Int32(clamping: info.width),
            Int32(clamping: info.height),
            info.frameSerial
        )
    }

    func setHdrMetalContentScaleMode(_ mode: Int32) {
        if useHdrPlayerLayerForSurface {
            switch HdrMetalScaleMode(rawValue: mode) ?? .fit {
            case .fit:
                hdrPlayerLayer?.videoGravity = .resizeAspect
            case .crop:
                hdrPlayerLayer?.videoGravity = .resizeAspectFill
            case .fill:
                hdrPlayerLayer?.videoGravity = .resize
            }
            return
        }
        hdrMetalRenderer?.setContentScaleMode(mode)
        hdrMetalRenderer?.renderCurrentFrame()
    }

    func detachHdrMetalLayer() {
        if useHdrPlayerLayerForSurface {
            hdrPlayerLayer?.player = nil
            return
        }
        hdrMetalRenderer?.stop()
    }

    func isHdrMetalAvailable() -> Bool {
        if hdrMetalRenderer != nil { return true }
        return HdrMetalVideoRenderer.isAvailable
    }

    func isHdrOutputReady() -> Bool {
        if useHdrPlayerLayerForSurface {
            return hdrPlayerLayer?.isReadyForDisplay ?? false
        }
        return hdrMetalRenderer?.hasRenderedFrame ?? false
    }

    func getHdrRendererFailure() -> String? {
        guard usesMetalProjectionSurface else { return nil }
        return hdrMetalRenderer?.rendererFailureDetail
    }

    @discardableResult
    func setHdrMetalProjectionConfiguration(_ serialized: String) -> Bool {
        let enabled = serialized.split(separator: ";").contains("enabled=1")
        let modeChanged = usesMetalProjectionSurface != enabled
        usesMetalProjectionSurface = enabled
        metalProjectionConfiguration = enabled ? serialized : nil

        if enabled {
            if hdrMetalRenderer == nil {
                hdrMetalRenderer = makeHdrMetalRenderer()
            }
            guard hdrMetalRenderer?.configure(serialized) == true else { return false }
            hdrPlayerLayer?.player = nil
        } else {
            if hdrMetalRenderer == nil {
                hdrMetalRenderer = makeHdrMetalRenderer()
            }
            guard hdrMetalRenderer?.configure(serialized) == true else { return false }
        }

        if modeChanged, prefersHdrMetalOutput, let item = player?.currentItem {
            configureVideoOutput(for: item)
        }
        return true
    }

    func setHdrMetalPreferred(_ preferred: Bool) {
        guard prefersHdrMetalOutput != preferred else { return }
        prefersHdrMetalOutput = preferred
        if let item = player?.currentItem {
            configureVideoOutput(for: item)
            if preferred, useHdrPlayerLayerForSurface {
                configureHdrPlayerLayer(with: player)
            } else {
                hdrPlayerLayer?.player = nil
            }
        }
    }

    func setHdrToneMappingEnabled(_ enabled: Bool) {
        guard toneMapsHdrToSdr != enabled else { return }
        toneMapsHdrToSdr = enabled
        if !prefersHdrMetalOutput, let item = player?.currentItem {
            configureVideoOutput(for: item)
        }
    }
}

private let hdr10PlusProbeAdvanceSeconds = 0.03
private let hdr10PlusProbeIntervalSeconds = 1.0 / 120.0
private let maximumPlaybackMetricsSampleGapSeconds = 1.0
private let playbackMetricsWarmupSeconds = 1.0
private let maximumValidAvSyncOffsetSeconds = 1.0

/// MARK: - C Exported Functions for JNA

@_cdecl("createVideoPlayer")
public func createVideoPlayer() -> UnsafeMutableRawPointer? {
    let player = MacVideoPlayer()
    return Unmanaged.passRetained(player).toOpaque()
}

@_cdecl("openUri")
public func openUri(_ context: UnsafeMutableRawPointer?, _ uri: UnsafePointer<CChar>?) {
    guard let context = context,
          let uriCStr = uri,
          let swiftUri = String(validatingUTF8: uriCStr)
    else {
        nativeVideoLog("Invalid parameters for openUri")
        return
    }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.beginOpening()
    }
    // Use a background queue for heavy operations to avoid blocking the main thread
    DispatchQueue.global(qos: .userInitiated).async {
        player.openUri(swiftUri)
    }
}

@_cdecl("openUriWithHeaders")
public func openUriWithHeaders(
    _ context: UnsafeMutableRawPointer?,
    _ uri: UnsafePointer<CChar>?,
    _ requestHeadersJson: UnsafePointer<CChar>?
) {
    guard let context = context,
          let uriCStr = uri,
          let swiftUri = String(validatingUTF8: uriCStr)
    else {
        nativeVideoLog("Invalid parameters for openUriWithHeaders")
        return
    }
    let requestHeaders = parseRequestHeadersJson(requestHeadersJson)
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.beginOpening()
    }
    DispatchQueue.global(qos: .userInitiated).async {
        player.openUri(swiftUri, requestHeaders: requestHeaders)
    }
}

@_cdecl("prepareUriReplacement")
public func prepareUriReplacement(
    _ context: UnsafeMutableRawPointer?,
    _ uri: UnsafePointer<CChar>?,
    _ requestHeadersJson: UnsafePointer<CChar>?
) -> UInt64 {
    guard let context = context,
          let uriCStr = uri,
          let swiftUri = String(validatingUTF8: uriCStr)
    else {
        return 0
    }
    let requestHeaders = parseRequestHeadersJson(requestHeadersJson)
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        player.prepareUriReplacement(swiftUri, requestHeaders: requestHeaders)
    }
}

@_cdecl("getUriReplacementStatus")
public func getUriReplacementStatus(
    _ context: UnsafeMutableRawPointer?,
    _ token: UInt64
) -> Int32 {
    guard let context = context else { return -2 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain { player.getUriReplacementStatus(token) }
}

@_cdecl("getUriReplacementError")
public func getUriReplacementError(
    _ context: UnsafeMutableRawPointer?,
    _ token: UInt64
) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        guard let message = player.getUriReplacementError(token) else { return nil }
        return UnsafePointer<CChar>(strdup(message))
    }
}

@_cdecl("commitUriReplacement")
public func commitUriReplacement(
    _ context: UnsafeMutableRawPointer?,
    _ token: UInt64
) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain { player.commitUriReplacement(token) ? 1 : 0 }
}

@_cdecl("cancelUriReplacement")
public func cancelUriReplacement(
    _ context: UnsafeMutableRawPointer?,
    _ token: UInt64
) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain { player.cancelUriReplacement(token) }
}

private func parseRequestHeadersJson(_ requestHeadersJson: UnsafePointer<CChar>?) -> [String: String] {
    guard let requestHeadersJson = requestHeadersJson,
          let json = String(validatingUTF8: requestHeadersJson),
          let data = json.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else {
        return [:]
    }
    return object.compactMapValues { value in value as? String }
}

@_cdecl("playVideo")
public func playVideo(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.play()
    }
}

@_cdecl("pauseVideo")
public func pauseVideo(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.pause()
    }
}

@_cdecl("setVolume")
public func setVolume(_ context: UnsafeMutableRawPointer?, _ volume: Float) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.setVolume(level: volume)
    }
}

@_cdecl("getVolume")
public func getVolume(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getVolume()
}

@_cdecl("lockLatestFrame")
public func lockLatestFrame(_ context: UnsafeMutableRawPointer?, _ outInfo: UnsafeMutablePointer<Int32>?) -> UnsafeMutableRawPointer? {
    guard let context = context, let outInfo = outInfo else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.lockLatestFrame(outInfo)
}

@_cdecl("unlockLatestFrame")
public func unlockLatestFrame(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    player.unlockLatestFrame()
}

@_cdecl("getFrameWidth")
public func getFrameWidth(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getFrameWidth())
}

@_cdecl("getFrameHeight")
public func getFrameHeight(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getFrameHeight())
}

@_cdecl("getDisplayAspectRatio")
public func getDisplayAspectRatio(_ context: UnsafeMutableRawPointer?) -> Double {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getDisplayAspectRatio()
}

@_cdecl("setOutputSize")
public func setOutputSize(_ context: UnsafeMutableRawPointer?, _ width: Int32, _ height: Int32) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.setOutputSize(width: Int(width), height: Int(height)) ? 1 : 0
}

@_cdecl("getHdrMetalLayer")
public func getHdrMetalLayer(_ context: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        guard let layer = player.getHdrMetalLayer() else { return nil }
        return Unmanaged.passUnretained(layer).toOpaque()
    }
}

@_cdecl("setHdrMetalLayerSize")
public func setHdrMetalLayerSize(
    _ context: UnsafeMutableRawPointer?,
    _ width: Int32,
    _ height: Int32,
    _ scale: Double
) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.setHdrMetalLayerSize(width: width, height: height, scale: scale)
    }
}

@_cdecl("setHdrMetalTextureOutput")
public func setHdrMetalTextureOutput(
    _ context: UnsafeMutableRawPointer?,
    _ commandQueue: UnsafeMutableRawPointer?
) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        player.setHdrMetalTextureOutput(commandQueue: commandQueue) ? 1 : 0
    }
}

@_cdecl("setHdrMetalTextureViewportSize")
public func setHdrMetalTextureViewportSize(
    _ context: UnsafeMutableRawPointer?,
    _ width: Int32,
    _ height: Int32
) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.setHdrMetalTextureViewportSize(width: width, height: height)
    }
}

@_cdecl("getHdrMetalTextureOutputInfo")
public func getHdrMetalTextureOutputInfo(
    _ context: UnsafeMutableRawPointer?,
    _ values: UnsafeMutablePointer<Int64>?
) -> Int32 {
    guard let context = context, let values = values else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    // TextureView polls this from a worker thread. The renderer publishes its IOSurface snapshot
    // under its own lock, so dispatching synchronously to AppKit here is both unnecessary and can
    // deadlock live resize: the poller owns the JVM player lock while AppKit needs that same lock
    // to deliver the new viewport size.
    guard let info = player.getHdrMetalTextureOutputInfo() else { return 0 }
    values[0] = Int64(Int(bitPattern: info.surface))
    values[1] = Int64(info.width)
    values[2] = Int64(info.height)
    values[3] = Int64(bitPattern: info.frameSerial)
    return 1
}

@_cdecl("setHdrMetalPreferred")
public func setHdrMetalPreferred(_ context: UnsafeMutableRawPointer?, _ preferred: Int32) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.setHdrMetalPreferred(preferred != 0)
    }
}

@_cdecl("setHdrToneMappingEnabled")
public func setHdrToneMappingEnabled(_ context: UnsafeMutableRawPointer?, _ enabled: Int32) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.setHdrToneMappingEnabled(enabled != 0)
    }
}

@_cdecl("setHdrMetalProjectionConfiguration")
public func setHdrMetalProjectionConfiguration(
    _ context: UnsafeMutableRawPointer?,
    _ configuration: UnsafePointer<CChar>?
) -> Int32 {
    guard let context = context,
          let configuration = configuration,
          let serialized = String(validatingUTF8: configuration)
    else {
        return 0
    }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        player.setHdrMetalProjectionConfiguration(serialized) ? 1 : 0
    }
}

@_cdecl("getHdrRendererFailure")
public func getHdrRendererFailure(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        guard let detail = player.getHdrRendererFailure() else { return nil }
        return UnsafePointer<CChar>(strdup(detail))
    }
}

@_cdecl("setHdrMetalContentScaleMode")
public func setHdrMetalContentScaleMode(_ context: UnsafeMutableRawPointer?, _ mode: Int32) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.setHdrMetalContentScaleMode(mode)
    }
}

@_cdecl("detachHdrMetalLayer")
public func detachHdrMetalLayer(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    syncOnMain {
        player.detachHdrMetalLayer()
    }
}

@_cdecl("isHdrMetalAvailable")
public func isHdrMetalAvailable(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        player.isHdrMetalAvailable() ? 1 : 0
    }
}

@_cdecl("isHdrOutputReady")
public func isHdrOutputReady(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        player.isHdrOutputReady() ? 1 : 0
    }
}

@_cdecl("isReadyForPlayback")
public func isReadyForPlayback(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return syncOnMain {
        player.getIsReadyForPlayback() ? 1 : 0
    }
}

@_cdecl("getVideoFrameRate")
public func getVideoFrameRate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getVideoFrameRate()
}

@_cdecl("getScreenRefreshRate")
public func getScreenRefreshRate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getScreenRefreshRate()
}

@_cdecl("getCaptureFrameRate")
public func getCaptureFrameRate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getCaptureFrameRate()
}

@_cdecl("getPlaybackDiagnostics")
public func getPlaybackDiagnostics(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return UnsafePointer<CChar>(strdup(player.getPlaybackDiagnostics()))
}

@_cdecl("getVideoDuration")
public func getVideoDuration(_ context: UnsafeMutableRawPointer?) -> Double {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getDuration()
}

@_cdecl("getCurrentTime")
public func getCurrentTime(_ context: UnsafeMutableRawPointer?) -> Double {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getCurrentTime()
}

@_cdecl("seekTo")
public func seekTo(_ context: UnsafeMutableRawPointer?, _ time: Double) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.seekTo(time: time)
    }
}

@_cdecl("disposeVideoPlayer")
public func disposeVideoPlayer(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeRetainedValue()
    DispatchQueue.main.async {
        player.dispose()
    }
}

@_cdecl("setPlaybackSpeed")
public func setPlaybackSpeed(_ context: UnsafeMutableRawPointer?, _ speed: Float) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.setPlaybackSpeed(speed: speed)
    }
}

@_cdecl("getPlaybackSpeed")
public func getPlaybackSpeed(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 1.0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getPlaybackSpeed()
}

@_cdecl("getVideoTitle")
public func getVideoTitle(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    if let title = player.getVideoTitle() {
        let cString = strdup(title)
        return UnsafePointer<CChar>(cString)
    }
    return nil
}

@_cdecl("getVideoBitrate")
public func getVideoBitrate(_ context: UnsafeMutableRawPointer?) -> Int64 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getVideoBitrate()
}

@_cdecl("getVideoMimeType")
public func getVideoMimeType(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    if let mimeType = player.getVideoMimeType() {
        let cString = strdup(mimeType)
        return UnsafePointer<CChar>(cString)
    }
    return nil
}

@_cdecl("getVideoColorInfo")
public func getVideoColorInfo(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return UnsafePointer<CChar>(strdup(player.getVideoColorInfo()))
}

@_cdecl("getAudioChannels")
public func getAudioChannels(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getAudioChannels())
}

@_cdecl("getAudioSampleRate")
public func getAudioSampleRate(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getAudioSampleRate())
}

@_cdecl("consumeDidPlayToEnd")
public func consumeDidPlayToEnd(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.consumeDidPlayToEnd() ? 1 : 0
}

// HLS-specific C exports
@_cdecl("getIsHLSStream")
public func getIsHLSStream(_ context: UnsafeMutableRawPointer?) -> Bool {
    guard let context = context else { return false }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getIsHLSStream()
}

@_cdecl("getAvailableBitrates")
public func getAvailableBitrates(_ context: UnsafeMutableRawPointer?, _ buffer: UnsafeMutablePointer<Float>?, _ maxCount: Int32) -> Int32 {
    guard let context = context, let buffer = buffer else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    let bitrates = player.getAvailableBitrates()
    let count = min(Int(maxCount), bitrates.count)
    for i in 0..<count {
        buffer[i] = bitrates[i]
    }
    return Int32(count)
}

@_cdecl("getCurrentBitrate")
public func getCurrentBitrate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getCurrentBitrate()
}

@_cdecl("setPreferredMaxBitrate")
public func setPreferredMaxBitrate(_ context: UnsafeMutableRawPointer?, _ bitrate: Double) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.setPreferredMaxBitrate(bitrate)
    }
}

@_cdecl("forceQuality")
public func forceQuality(_ context: UnsafeMutableRawPointer?, _ bitrate: Float) {
    guard let context = context else { return }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.forceQuality(bitrate: bitrate)
    }
}

@_cdecl("getBufferStatus")
public func getBufferStatus(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0 }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getBufferStatus()
}

@_cdecl("getIsBuffering")
public func getIsBuffering(_ context: UnsafeMutableRawPointer?) -> Bool {
    guard let context = context else { return false }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getIsBuffering()
}

@_cdecl("getNetworkStatus")
public func getNetworkStatus(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    let status = player.getNetworkStatus()
    let cString = strdup(status)
    return UnsafePointer<CChar>(cString)
}

@_cdecl("getLastError")
public func getLastError(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<MacVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    if let error = player.getLastError() {
        let cString = strdup(error)
        return UnsafePointer<CChar>(cString)
    }
    return nil
}
