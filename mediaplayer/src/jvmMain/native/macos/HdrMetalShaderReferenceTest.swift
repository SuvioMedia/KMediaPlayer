import Darwin
import Foundation
import Metal

private let hdrReferenceKernels = """

kernel void kmp_pq_reference(
    const device float4 *input [[buffer(0)]],
    device float4 *output [[buffer(1)]],
    uint index [[thread_position_in_grid]]
) {
    const float4 value = input[index];
    output[index] = float4(pq_eotf(value.x), pq_eotf(value.y), pq_eotf(value.z), 1.0);
}

kernel void kmp_hlg_reference(
    const device float4 *input [[buffer(0)]],
    device float4 *output [[buffer(1)]],
    uint index [[thread_position_in_grid]]
) {
    const float4 value = input[index];
    const float peak = value.w;
    output[index] = float4(
        peak * pow(max(hlg_inverse_oetf(value.x), 0.0), 1.2),
        peak * pow(max(hlg_inverse_oetf(value.y), 0.0), 1.2),
        peak * pow(max(hlg_inverse_oetf(value.z), 0.0), 1.2),
        1.0
    );
}

kernel void kmp_bt2390_reference(
    const device float4 *input [[buffer(0)]],
    device float4 *output [[buffer(1)]],
    uint index [[thread_position_in_grid]]
) {
    const float4 value = input[index];
    output[index] = float4(bt2390(value.x, value.y, value.z), 0.0, 0.0, 1.0);
}

kernel void kmp_primaries_reference(
    const device float4 *input [[buffer(0)]],
    device float4 *output [[buffer(1)]],
    uint index [[thread_position_in_grid]]
) {
    const float4 value = input[index];
    output[index] = float4(source_primaries_to_bt2020(value.xyz, value.w), 1.0);
}

kernel void kmp_hdr10_plus_reference(
    const device float4 *input [[buffer(0)]],
    device float4 *output [[buffer(1)]],
    constant float *parameters [[buffer(2)]],
    uint index [[thread_position_in_grid]]
) {
    output[index] = float4(apply_hdr10_plus(input[index].xyz, parameters), 1.0);
}

kernel void kmp_yuv_range_reference(
    texture2d<float, access::sample> luma [[texture(0)]],
    texture2d<float, access::sample> chroma [[texture(1)]],
    device float4 *output [[buffer(0)]],
    constant float *parameters [[buffer(1)]]
) {
    output[0] = float4(yuv_to_rgb(float2(0.5), luma, chroma, parameters), 1.0);
}
"""

private enum HdrMetalReferenceFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(detail): detail
        }
    }
}

private struct Hdr10PlusBitWriter {
    private(set) var bytes = [UInt8](repeating: 0, count: 256)
    private(set) var bitCount = 0

    mutating func write(_ value: UInt32, count: Int) {
        for shift in stride(from: count - 1, through: 0, by: -1) {
            let bit = (value >> UInt32(shift)) & 1
            let byteIndex = bitCount >> 3
            let bitIndex = 7 - (bitCount & 7)
            if bit != 0 {
                bytes[byteIndex] |= UInt8(1 << bitIndex)
            }
            bitCount += 1
        }
    }

    var payload: [UInt8] { Array(bytes.prefix((bitCount + 7) / 8)) }
}

private final class HdrMetalShaderReferenceTest {
    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let library: MTLLibrary

    init() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw HdrMetalReferenceFailure.message("No Metal device is available for shader reference tests.")
        }
        guard let queue = device.makeCommandQueue() else {
            throw HdrMetalReferenceFailure.message("Metal could not create the reference command queue.")
        }
        do {
            library = try device.makeLibrary(source: macMetalProjectionShader + hdrReferenceKernels, options: nil)
        } catch {
            throw HdrMetalReferenceFailure.message("Metal reference shader compilation failed: \(error)")
        }
        self.device = device
        self.queue = queue
    }

    func run() throws {
        try verifyPqAndBt2390()
        try verifyHlgBt2446Ootf()
        try verifyBt2020PrimaryConversion()
        try verifyLimitedAndFullRangeYuv()
        try verifyHdr10PlusCurves()
    }

    private func verifyPqAndBt2390() throws {
        let luminances: [Float] = [100, 1_000, 4_000]
        let encoded = luminances.map { value in
            let signal = Float(pqOetf(Double(value)))
            return SIMD4<Float>(signal, signal, signal, 0)
        }
        let decoded = try runVectorKernel("kmp_pq_reference", input: encoded)
        for index in luminances.indices {
            try near(decoded[index].x, luminances[index], absolute: 0.35, relative: 0.0003,
                     label: "PQ \(luminances[index])-nit reference")
        }

        let bt2390Cases = [
            SIMD4<Float>(1_000, 1_000, 600, 0),
            SIMD4<Float>(4_000, 4_000, 1_000, 0),
            SIMD4<Float>(2_500, 4_000, 600, 0),
        ]
        let mapped = try runVectorKernel("kmp_bt2390_reference", input: bt2390Cases)
        for index in bt2390Cases.indices {
            let value = bt2390Cases[index]
            let expected = Float(bt2390(Double(value.x), sourcePeak: Double(value.y), targetPeak: Double(value.z)))
            try near(mapped[index].x, expected, absolute: 0.4, relative: 0.0005,
                     label: "BT.2390 case \(index)")
        }
    }

    private func verifyHlgBt2446Ootf() throws {
        let cases = [
            SIMD4<Float>(0.25, 0.50, 0.75, 1_000),
            SIMD4<Float>(0.10, 0.60, 0.90, 4_000),
        ]
        let output = try runVectorKernel("kmp_hlg_reference", input: cases)
        for index in cases.indices {
            for channel in 0..<3 {
                let signal = Double(cases[index][channel])
                let expected = Float(Double(cases[index].w) * pow(max(hlgInverseOetf(signal), 0), 1.2))
                try near(output[index][channel], expected, absolute: 0.08, relative: 0.0004,
                         label: "HLG/BT.2446 case \(index) channel \(channel)")
            }
        }
    }

    private func verifyBt2020PrimaryConversion() throws {
        let cases = [
            SIMD4<Float>(0.70, 0.20, 0.05, 1),
            SIMD4<Float>(0.70, 0.20, 0.05, 2),
        ]
        let output = try runVectorKernel("kmp_primaries_reference", input: cases)
        let source = SIMD3<Float>(0.70, 0.20, 0.05)
        let rec709Red = 0.627403896 * source.x + 0.329283038 * source.y + 0.043313066 * source.z
        let rec709Green = 0.069097289 * source.x + 0.919540395 * source.y + 0.011362316 * source.z
        let rec709Blue = 0.016391439 * source.x + 0.088013308 * source.y + 0.895595253 * source.z
        let displayP3Red = 0.753833034 * source.x + 0.198597369 * source.y + 0.047569597 * source.z
        let displayP3Green = 0.045743849 * source.x + 0.941777220 * source.y + 0.012478931 * source.z
        let displayP3Blue = -0.001210340 * source.x + 0.017601717 * source.y + 0.983608623 * source.z
        let expected: [SIMD3<Float>] = [
            SIMD3<Float>(rec709Red, rec709Green, rec709Blue),
            SIMD3<Float>(displayP3Red, displayP3Green, displayP3Blue),
        ]
        for index in cases.indices {
            for channel in 0..<3 {
                try near(output[index][channel], expected[index][channel], absolute: 0.000_003,
                         label: "BT.2020 primary conversion case \(index) channel \(channel)")
            }
        }
    }

    private func verifyLimitedAndFullRangeYuv() throws {
        try verifyYuv(tenBit: true, fullRange: false, yCode: 502, cbCode: 512, crCode: 512)
        try verifyYuv(tenBit: true, fullRange: true, yCode: 700, cbCode: 512, crCode: 512)
        try verifyYuv(tenBit: false, fullRange: false, yCode: 126, cbCode: 128, crCode: 128)
        try verifyYuv(tenBit: false, fullRange: true, yCode: 180, cbCode: 128, crCode: 128)
    }

    private func verifyYuv(
        tenBit: Bool,
        fullRange: Bool,
        yCode: Int,
        cbCode: Int,
        crCode: Int
    ) throws {
        let lumaFormat: MTLPixelFormat = tenBit ? .r16Unorm : .r8Unorm
        let chromaFormat: MTLPixelFormat = tenBit ? .rg16Unorm : .rg8Unorm
        let luma = try makeTexture(pixelFormat: lumaFormat)
        let chroma = try makeTexture(pixelFormat: chromaFormat)
        if tenBit {
            try replace(texture: luma, values: [UInt16(yCode << 6)])
            try replace(texture: chroma, values: [UInt16(cbCode << 6), UInt16(crCode << 6)])
        } else {
            try replace(texture: luma, values: [UInt8(yCode)])
            try replace(texture: chroma, values: [UInt8(cbCode), UInt8(crCode)])
        }
        var parameters = [Float](repeating: 0, count: 60)
        parameters[19] = 1
        parameters[22] = tenBit ? 1 : 0
        parameters[23] = fullRange ? 1 : 0
        let actual = try runYuvKernel(luma: luma, chroma: chroma, parameters: parameters)
        let maximum = tenBit ? 1_023.0 : 255.0
        let y: Double
        let cb: Double
        let cr: Double
        if fullRange {
            y = Double(yCode) / maximum
            cb = Double(cbCode) / maximum - 0.5
            cr = Double(crCode) / maximum - 0.5
        } else if tenBit {
            y = Double(yCode - 64) / 876.0
            cb = Double(cbCode - 512) / 896.0
            cr = Double(crCode - 512) / 896.0
        } else {
            y = Double(yCode - 16) / 219.0
            cb = Double(cbCode - 128) / 224.0
            cr = Double(crCode - 128) / 224.0
        }
        let expected = SIMD3<Float>(
            Float(max(y, 0) + 1.4746 * cr),
            Float(max(y, 0) - 0.164553 * cb - 0.571353 * cr),
            Float(max(y, 0) + 1.8814 * cb)
        )
        for channel in 0..<3 {
            try near(actual[channel], expected[channel], absolute: 0.000_06,
                     label: "\(tenBit ? 10 : 8)-bit \(fullRange ? "full" : "limited") range channel \(channel)")
        }
    }

    private func verifyHdr10PlusCurves() throws {
        for maxSclBase in [10_000, 40_000] {
            let parsed = try hdr10PlusCurve(maxSclBase: UInt32(maxSclBase))
            var parameters = [Float](repeating: 0, count: 60)
            parameters[24] = 1
            parameters[25] = parsed.sourcePeak
            for index in parsed.curve.indices {
                parameters[26 + index] = parsed.curve[index]
            }
            let input = [SIMD4<Float>(parsed.sourcePeak * 0.20, parsed.sourcePeak * 0.45, parsed.sourcePeak * 0.05, 0)]
            let output = try runVectorKernel("kmp_hdr10_plus_reference", input: input, parameters: parameters)[0]
            let expected = applyHdr10PlusCpu(SIMD3<Float>(input[0].x, input[0].y, input[0].z), parsed: parsed)
            for channel in 0..<3 {
                try near(output[channel], expected[channel], absolute: 0.12, relative: 0.0005,
                         label: "ST 2094-40 \(maxSclBase / 10)-nit curve channel \(channel)")
            }
        }
    }

    private func runVectorKernel(
        _ name: String,
        input: [SIMD4<Float>],
        parameters: [Float]? = nil
    ) throws -> [SIMD4<Float>] {
        guard let function = library.makeFunction(name: name) else {
            throw HdrMetalReferenceFailure.message("Missing Metal reference kernel \(name).")
        }
        let pipeline = try device.makeComputePipelineState(function: function)
        guard let inputBuffer = device.makeBuffer(
            bytes: input,
            length: input.count * MemoryLayout<SIMD4<Float>>.stride,
            options: .storageModeShared
        ), let outputBuffer = device.makeBuffer(
            length: input.count * MemoryLayout<SIMD4<Float>>.stride,
            options: .storageModeShared
        ), let commandBuffer = queue.makeCommandBuffer(),
           let encoder = commandBuffer.makeComputeCommandEncoder()
        else {
            throw HdrMetalReferenceFailure.message("Metal could not allocate \(name) reference resources.")
        }
        encoder.setComputePipelineState(pipeline)
        encoder.setBuffer(inputBuffer, offset: 0, index: 0)
        encoder.setBuffer(outputBuffer, offset: 0, index: 1)
        if let parameters {
            guard let parameterBuffer = device.makeBuffer(
                bytes: parameters,
                length: parameters.count * MemoryLayout<Float>.stride,
                options: .storageModeShared
            ) else {
                throw HdrMetalReferenceFailure.message("Metal could not allocate \(name) parameters.")
            }
            encoder.setBuffer(parameterBuffer, offset: 0, index: 2)
        }
        encoder.dispatchThreads(
            MTLSize(width: input.count, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: min(input.count, pipeline.maxTotalThreadsPerThreadgroup), height: 1, depth: 1)
        )
        encoder.endEncoding()
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        guard commandBuffer.status == .completed, commandBuffer.error == nil else {
            throw HdrMetalReferenceFailure.message("Metal kernel \(name) failed: \(String(describing: commandBuffer.error))")
        }
        let pointer = outputBuffer.contents().bindMemory(to: SIMD4<Float>.self, capacity: input.count)
        return Array(UnsafeBufferPointer(start: pointer, count: input.count))
    }

    private func runYuvKernel(
        luma: MTLTexture,
        chroma: MTLTexture,
        parameters: [Float]
    ) throws -> SIMD4<Float> {
        guard let function = library.makeFunction(name: "kmp_yuv_range_reference") else {
            throw HdrMetalReferenceFailure.message("Missing Metal YUV range reference kernel.")
        }
        let pipeline = try device.makeComputePipelineState(function: function)
        guard let output = device.makeBuffer(length: MemoryLayout<SIMD4<Float>>.stride, options: .storageModeShared),
              let parameterBuffer = device.makeBuffer(
                  bytes: parameters,
                  length: parameters.count * MemoryLayout<Float>.stride,
                  options: .storageModeShared
              ), let commandBuffer = queue.makeCommandBuffer(),
              let encoder = commandBuffer.makeComputeCommandEncoder()
        else {
            throw HdrMetalReferenceFailure.message("Metal could not allocate YUV reference resources.")
        }
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(luma, index: 0)
        encoder.setTexture(chroma, index: 1)
        encoder.setBuffer(output, offset: 0, index: 0)
        encoder.setBuffer(parameterBuffer, offset: 0, index: 1)
        encoder.dispatchThreads(MTLSize(width: 1, height: 1, depth: 1),
                                threadsPerThreadgroup: MTLSize(width: 1, height: 1, depth: 1))
        encoder.endEncoding()
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        guard commandBuffer.status == .completed, commandBuffer.error == nil else {
            throw HdrMetalReferenceFailure.message("Metal YUV range kernel failed: \(String(describing: commandBuffer.error))")
        }
        return output.contents().bindMemory(to: SIMD4<Float>.self, capacity: 1).pointee
    }

    private func makeTexture(pixelFormat: MTLPixelFormat) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: pixelFormat,
            width: 1,
            height: 1,
            mipmapped: false
        )
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw HdrMetalReferenceFailure.message("Metal could not allocate a \(pixelFormat) reference texture.")
        }
        return texture
    }

    private func replace<T>(texture: MTLTexture, values: [T]) throws {
        let bytesPerRow = values.count * MemoryLayout<T>.stride
        values.withUnsafeBytes { bytes in
            texture.replace(
                region: MTLRegionMake2D(0, 0, 1, 1),
                mipmapLevel: 0,
                withBytes: bytes.baseAddress!,
                bytesPerRow: bytesPerRow
            )
        }
    }

    private func hdr10PlusCurve(maxSclBase: UInt32) throws -> (sourcePeak: Float, curve: [Float]) {
        var writer = Hdr10PlusBitWriter()
        writer.write(0xb5, count: 8)
        writer.write(0x003c, count: 16)
        writer.write(0x0001, count: 16)
        writer.write(4, count: 8)
        writer.write(1, count: 8)
        writer.write(1, count: 2)
        writer.write(1_000, count: 27)
        writer.write(0, count: 1)
        writer.write(maxSclBase, count: 17)
        writer.write(maxSclBase + 1_000, count: 17)
        writer.write(maxSclBase + 2_000, count: 17)
        writer.write(maxSclBase, count: 17)
        writer.write(9, count: 4)
        for (index, percentile) in [1, 5, 10, 25, 50, 75, 90, 95, 99].enumerated() {
            writer.write(UInt32(percentile), count: 7)
            writer.write(UInt32(index + 1) * maxSclBase / 10, count: 17)
        }
        writer.write(64, count: 10)
        writer.write(0, count: 1)
        writer.write(1, count: 1)
        writer.write(1_024, count: 12)
        writer.write(1_600, count: 12)
        writer.write(2, count: 4)
        writer.write(320, count: 10)
        writer.write(700, count: 10)
        writer.write(0, count: 1)

        let payload = writer.payload
        var sourcePeak: Float = 0
        var curve = [Float](repeating: 0, count: Int(KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT))
        var error = [CChar](repeating: 0, count: 256)
        let parsed = payload.withUnsafeBufferPointer { payloadBuffer in
            curve.withUnsafeMutableBufferPointer { curveBuffer in
                error.withUnsafeMutableBufferPointer { errorBuffer in
                    kmp_hdr10_plus_parse_tone_curve(
                        payloadBuffer.baseAddress,
                        payloadBuffer.count,
                        600,
                        &sourcePeak,
                        curveBuffer.baseAddress,
                        errorBuffer.baseAddress,
                        errorBuffer.count
                    )
                }
            }
        }
        guard parsed == 1 else {
            throw HdrMetalReferenceFailure.message("ST 2094-40 parser rejected the GPU fixture: \(String(cString: error))")
        }
        return (sourcePeak, curve)
    }

    private func applyHdr10PlusCpu(
        _ input: SIMD3<Float>,
        parsed: (sourcePeak: Float, curve: [Float])
    ) -> SIMD3<Float> {
        let luminance = max(input.x * 0.2627 + input.y * 0.6780 + input.z * 0.0593, 0)
        let normalized = min(max(luminance / max(parsed.sourcePeak, 1), 0), 1)
        let position = normalized * 32
        let lower = min(max(Int(floor(position)), 0), 32)
        let upper = min(lower + 1, 32)
        let amount = position - Float(lower)
        let mapped = (parsed.curve[lower] + (parsed.curve[upper] - parsed.curve[lower]) * amount) * 10_000
        let scale = luminance > 0.000_001 ? mapped / luminance : 0
        return SIMD3<Float>(max(input.x * scale, 0), max(input.y * scale, 0), max(input.z * scale, 0))
    }

    private func near(
        _ actual: Float,
        _ expected: Float,
        absolute: Float,
        relative: Float = 0,
        label: String
    ) throws {
        let tolerance = max(absolute, abs(expected) * relative)
        guard actual.isFinite, expected.isFinite, abs(actual - expected) <= tolerance else {
            throw HdrMetalReferenceFailure.message(
                "\(label) mismatch: GPU=\(actual), CPU=\(expected), tolerance=\(tolerance)."
            )
        }
    }

    private func pqOetf(_ nits: Double) -> Double {
        let m1 = 0.1593017578125
        let m2 = 78.84375
        let c1 = 0.8359375
        let c2 = 18.8515625
        let c3 = 18.6875
        let value = pow(min(max(nits, 0), 10_000) / 10_000, m1)
        return pow((c1 + c2 * value) / (1 + c3 * value), m2)
    }

    private func pqEotf(_ signal: Double) -> Double {
        let m1 = 0.1593017578125
        let m2 = 78.84375
        let c1 = 0.8359375
        let c2 = 18.8515625
        let c3 = 18.6875
        let value = pow(min(max(signal, 0), 1), 1 / m2)
        return pow(max(value - c1, 0) / max(c2 - c3 * value, 0.000_001), 1 / m1) * 10_000
    }

    private func bt2390(_ nits: Double, sourcePeak: Double, targetPeak: Double) -> Double {
        let sourceCode = pqOetf(sourcePeak)
        let normalizedTarget = min(max(pqOetf(targetPeak) / max(sourceCode, 0.000_001), 0), 1)
        let knee = min(max(1.5 * normalizedTarget - 0.5, 0), 1)
        let inputCode = min(max(pqOetf(nits) / max(sourceCode, 0.000_001), 0), 1)
        if inputCode <= knee || knee >= 1 { return min(nits, targetPeak) }
        let t = min(max((inputCode - knee) / max(1 - knee, 0.000_001), 0), 1)
        let t2 = t * t
        let t3 = t2 * t
        let outputCode =
            (2 * t3 - 3 * t2 + 1) * knee +
            (t3 - 2 * t2 + t) * (1 - knee) +
            (-2 * t3 + 3 * t2) * normalizedTarget
        return min(pqEotf(min(max(outputCode * sourceCode, 0), 1)), targetPeak)
    }

    private func hlgInverseOetf(_ signal: Double) -> Double {
        let a = 0.17883277
        let b = 0.28466892
        let c = 0.55991073
        return signal <= 0.5 ? signal * signal / 3 : (exp((signal - c) / a) + b) / 12
    }
}

@main
private struct HdrMetalShaderReferenceTestMain {
    static func main() {
        do {
            try HdrMetalShaderReferenceTest().run()
            print("Metal CPU/GPU HDR reference validation passed.")
        } catch {
            fputs("Metal CPU/GPU HDR reference validation failed: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
