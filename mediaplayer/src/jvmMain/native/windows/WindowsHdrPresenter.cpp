#include "WindowsHdrPresenter.h"

#include "Hdr10PlusToneCurve.h"
#include "MediaFoundationManager.h"
#include "NativeLogging.h"

#include <d3dcompiler.h>
#include <mferror.h>
#include <wrl/client.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>

using Microsoft::WRL::ComPtr;

namespace {

constexpr size_t kRequiredIntegerConfiguration = 10;
constexpr size_t kRequiredFloatingConfiguration = 22;
constexpr UINT kOutputRefreshIntervalFrames = 60;
constexpr float kScRgbReferenceWhiteNits = 80.0f;
constexpr float kSdrTargetPeakNits = 100.0f;

constexpr int kTransferPq = 0;
constexpr int kTransferHlg = 1;
constexpr int kOutputPq = 0;
constexpr int kOutputScRgb = 1;
constexpr int kOutputSdr = 2;
constexpr int kRangeLimited = 0;
constexpr int kRangeFull = 1;
constexpr int kMatrixBt2020 = 0;
constexpr int kMatrixBt709 = 1;
constexpr int kMatrixBt601 = 2;
constexpr int kPrimariesBt2020 = 0;
constexpr int kPrimariesBt709 = 1;
constexpr int kPrimariesDisplayP3 = 2;

const char* kHdrShader = R"hlsl(
cbuffer HdrConfiguration : register(b0) {
    int4 uModes;       // transfer, projection, stereo, rotation
    int4 uFlags;       // eye order, output mode, HDR10+ enabled, SDR requested
    int4 uColor;       // range, matrix, primaries, reserved
    float4 uProjection; // fov, yaw, pitch, roll
    float4 uView;       // zoom, source peak nits, target peak nits, scRGB white
    float4 uCrop;       // left, top, right, bottom
    float4 uHdr10PlusHeader; // enabled, source peak nits, reserved, reserved
    float4 uHdr10PlusCurve[9]; // 33 samples packed four per vector
};

Texture2D<float> uLuma : register(t0);
Texture2D<float2> uChroma : register(t1);
SamplerState uSampler : register(s0);

static const float PI = 3.14159265358979323846;
static const float CAMERA_FOV_DEGREES = 95.0;

struct VertexOutput {
    float4 position : SV_POSITION;
    float2 uv : TEXCOORD0;
};

VertexOutput vertexMain(uint id : SV_VertexID) {
    VertexOutput output;
    output.uv = float2((id << 1) & 2, id & 2);
    output.position = float4(output.uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
    return output;
}

float3 rotateDirection(float3 direction) {
    float yaw = uProjection.y * PI / 180.0;
    float pitch = uProjection.z * PI / 180.0;
    float roll = uProjection.w * PI / 180.0;
    float cy = cos(yaw);
    float sy = sin(yaw);
    direction = float3(cy * direction.x + sy * direction.z, direction.y,
                       -sy * direction.x + cy * direction.z);
    float cp = cos(pitch);
    float sp = sin(pitch);
    direction = float3(direction.x, cp * direction.y - sp * direction.z,
                       sp * direction.y + cp * direction.z);
    float cr = cos(roll);
    float sr = sin(roll);
    return normalize(float3(cr * direction.x - sr * direction.y,
                            sr * direction.x + cr * direction.y,
                            direction.z));
}

float3 rayForScreenUv(float2 screenUv, float viewportAspect) {
    float2 p = float2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
    float tanHalfFov = tan((CAMERA_FOV_DEGREES * PI / 180.0) * 0.5 / max(uView.x, 0.01));
    return rotateDirection(normalize(float3(p.x * viewportAspect * tanHalfFov,
                                             p.y * tanHalfFov, -1.0)));
}

float2 eacFaceUv(float sc, float tc, float cellX, float cellY) {
    float2 local = float2(0.5 + atan(sc) / (0.5 * PI),
                          0.5 - atan(tc) / (0.5 * PI));
    return float2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);
}

float2 eacUv(float3 direction) {
    float3 ad = abs(direction);
    float2 result;
    if (ad.z >= ad.x && ad.z >= ad.y) {
        result = direction.z < 0.0
            ? eacFaceUv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0)
            : eacFaceUv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
    } else if (ad.x >= ad.y) {
        result = direction.x > 0.0
            ? eacFaceUv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0)
            : eacFaceUv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
    } else {
        result = direction.y > 0.0
            ? eacFaceUv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0)
            : eacFaceUv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
    }
    return result;
}

float2 rotateUv(float2 uv) {
    if (uModes.w == 1) return float2(1.0 - uv.y, uv.x);
    if (uModes.w == 2) return float2(1.0 - uv.x, 1.0 - uv.y);
    if (uModes.w == 3) return float2(uv.y, 1.0 - uv.x);
    return uv;
}

bool projectionUv(float2 outputUv, out float2 sourceUv) {
    sourceUv = outputUv;
    bool valid = true;
    float2 eyeUv = outputUv;
    bool secondEye = false;
    float viewportAspect = 16.0 / 9.0;
    if (uModes.z != 0) {
        secondEye = outputUv.x >= 0.5;
        eyeUv.x = frac(outputUv.x * 2.0);
        viewportAspect *= 0.5;
    }

    float2 localUv = eyeUv;
    if (uModes.y != 0) {
        float3 direction = rayForScreenUv(eyeUv, viewportAspect);
        if (uModes.y == 1 || uModes.y == 2) {
            float horizontalFov = max(uProjection.x, 1.0) * PI / 180.0;
            float yaw = atan2(direction.x, -direction.z);
            float pitch = asin(clamp(direction.y, -1.0, 1.0));
            if (abs(yaw) > horizontalFov * 0.5) valid = false;
            localUv = float2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI);
        } else if (uModes.y >= 3 && uModes.y <= 6) {
            float maxTheta = max(uProjection.x, 1.0) * PI / 180.0 * 0.5;
            float theta = acos(clamp(-direction.z, -1.0, 1.0));
            if (theta > maxTheta) valid = false;
            float phi = atan2(direction.y, direction.x);
            float radius = theta / maxTheta * 0.5;
            localUv = float2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius);
        } else {
            localUv = eacUv(direction);
        }
    }

    localUv = rotateUv(localUv);
    if (any(localUv < 0.0) || any(localUv > 1.0)) valid = false;

    float4 eyeWindow = float4(0.0, 0.0, 1.0, 1.0);
    if (uModes.z == 1) {
        bool useSecond = secondEye;
        if (uFlags.x != 0) useSecond = !useSecond;
        eyeWindow = useSecond ? float4(0.5, 0.0, 1.0, 1.0)
                              : float4(0.0, 0.0, 0.5, 1.0);
    } else if (uModes.z == 2) {
        bool useSecond = secondEye;
        if (uFlags.x != 0) useSecond = !useSecond;
        eyeWindow = useSecond ? float4(0.0, 0.5, 1.0, 1.0)
                              : float4(0.0, 0.0, 1.0, 0.5);
    }
    float2 eyeSize = eyeWindow.zw - eyeWindow.xy;
    eyeWindow.xy += eyeSize * uCrop.xy;
    eyeWindow.zw -= eyeSize * uCrop.zw;
    sourceUv = lerp(eyeWindow.xy, eyeWindow.zw, localUv);
    return valid;
}

float3 sampleP010(float2 uv) {
    float y = uLuma.Sample(uSampler, uv);
    float2 cbcr = uChroma.Sample(uSampler, uv);
    // P010 stores each 10-bit code in the most-significant bits of an R16/R16G16
    // texel. Undo the UNORM16 normalization before applying video-range offsets;
    // treating the sample as code/1023 introduces a measurable black/white error.
    const float p010CodeScale = 65535.0 / 64.0;
    y *= p010CodeScale;
    cbcr *= p010CodeScale;
    if (uColor.x == 0) {
        y = saturate((y - 64.0) / 876.0);
        cbcr = (cbcr - 512.0) / 896.0;
    } else {
        y = saturate(y / 1023.0);
        cbcr = (cbcr - 512.0) / 1023.0;
    }
    if (uColor.y == 1) {
        return max(float3(
            y + 1.5748 * cbcr.y,
            y - 0.187324 * cbcr.x - 0.468124 * cbcr.y,
            y + 1.8556 * cbcr.x), 0.0);
    }
    if (uColor.y == 2) {
        return max(float3(
            y + 1.4020 * cbcr.y,
            y - 0.344136 * cbcr.x - 0.714136 * cbcr.y,
            y + 1.7720 * cbcr.x), 0.0);
    }
    return max(float3(
        y + 1.4746 * cbcr.y,
        y - 0.164553 * cbcr.x - 0.571353 * cbcr.y,
        y + 1.8814 * cbcr.x), 0.0);
}

float3 pqEotf(float3 encoded) {
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    float3 p = pow(saturate(encoded), 1.0 / m2);
    return pow(max((p - c1) / max(c2 - c3 * p, 1e-6), 0.0), 1.0 / m1) * 10000.0;
}

float3 pqOetf(float3 nits) {
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    float3 p = pow(saturate(nits / 10000.0), m1);
    return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
}

float pqOetfScalar(float nits) {
    return pqOetf(float3(nits, nits, nits)).x;
}

float pqEotfScalar(float encoded) {
    return pqEotf(float3(encoded, encoded, encoded)).x;
}

float toneMapBt2390(float luminanceNits, float sourcePeak, float targetPeak) {
    sourcePeak = max(sourcePeak, 1.0);
    targetPeak = max(targetPeak, 1.0);
    if (targetPeak >= sourcePeak) return min(luminanceNits, sourcePeak);
    float sourcePeakCode = pqOetfScalar(sourcePeak);
    float target = saturate(pqOetfScalar(targetPeak) / sourcePeakCode);
    float knee = saturate(1.5 * target - 0.5);
    float input = saturate(pqOetfScalar(luminanceNits) / sourcePeakCode);
    float output = input;
    if (input > knee && knee < 1.0) {
        float t = saturate((input - knee) / (1.0 - knee));
        float t2 = t * t;
        float t3 = t2 * t;
        output = (2.0 * t3 - 3.0 * t2 + 1.0) * knee
               + (t3 - 2.0 * t2 + t) * (1.0 - knee)
               + (-2.0 * t3 + 3.0 * t2) * target;
    }
    return min(pqEotfScalar(saturate(output * sourcePeakCode)), targetPeak);
}

float3 toneMapNits(float3 nits) {
    float sourcePeak = max(uView.y, 1.0);
    float targetPeak = max(uView.z, 1.0);
    float luminance = max(dot(nits, float3(0.2627, 0.6780, 0.0593)), 1e-6);
    float mapped = uHdr10PlusHeader.x > 0.5
        ? min(luminance, targetPeak)
        : toneMapBt2390(luminance, sourcePeak, targetPeak);
    return max(nits * (mapped / luminance), 0.0);
}

float hdr10PlusCurveSample(uint index) {
    uint bounded = min(index, 32u);
    return uHdr10PlusCurve[bounded / 4u][bounded % 4u] * 10000.0;
}

float3 applyHdr10Plus(float3 nits) {
    if (uHdr10PlusHeader.x < 0.5) return nits;
    float luminance = max(dot(nits, float3(0.2627, 0.6780, 0.0593)), 0.0);
    float normalized = saturate(luminance / max(uHdr10PlusHeader.y, 1.0));
    float curvePosition = normalized * 32.0;
    uint lower = (uint)floor(curvePosition);
    uint upper = min(lower + 1u, 32u);
    float mapped = lerp(
        hdr10PlusCurveSample(lower),
        hdr10PlusCurveSample(upper),
        frac(curvePosition));
    float scale = luminance > 0.000001 ? mapped / luminance : 0.0;
    return max(nits * scale, 0.0);
}

float hlgInverse(float signal) {
    const float a = 0.17883277;
    const float b = 1.0 - 4.0 * a;
    const float c = 0.55991073;
    return signal <= 0.5 ? signal * signal / 3.0
                         : (exp((signal - c) / a) + b) / 12.0;
}

float sourceLuma(float3 value) {
    if (uColor.z == 1) return dot(value, float3(0.2126, 0.7152, 0.0722));
    if (uColor.z == 2) return dot(value, float3(0.2290, 0.6917, 0.0793));
    return dot(value, float3(0.2627, 0.6780, 0.0593));
}

float3 hlgToNits(float3 signal) {
    float3 scene = float3(hlgInverse(signal.r), hlgInverse(signal.g), hlgInverse(signal.b));
    float sceneLuma = max(sourceLuma(scene), 1e-6);
    float gamma = 1.2 + 0.42 * log10(max(uView.y, 1.0) / 1000.0);
    return scene * pow(sceneLuma, max(gamma, 0.0) - 1.0) * max(uView.y, 1.0);
}

float3 sourcePrimariesToBt2020(float3 linearRgb) {
    if (uColor.z == 1) {
        return float3(
            0.627404 * linearRgb.r + 0.329283 * linearRgb.g + 0.043313 * linearRgb.b,
            0.069097 * linearRgb.r + 0.919540 * linearRgb.g + 0.011362 * linearRgb.b,
            0.016391 * linearRgb.r + 0.088013 * linearRgb.g + 0.895595 * linearRgb.b);
    }
    if (uColor.z == 2) {
        return float3(
            0.753833 * linearRgb.r + 0.198597 * linearRgb.g + 0.047570 * linearRgb.b,
            0.045744 * linearRgb.r + 0.941777 * linearRgb.g + 0.012479 * linearRgb.b,
           -0.001210 * linearRgb.r + 0.017602 * linearRgb.g + 0.983609 * linearRgb.b);
    }
    return linearRgb;
}

float3 bt2020ToBt709(float3 rgb) {
    return float3(
        1.660491 * rgb.r - 0.587641 * rgb.g - 0.072850 * rgb.b,
       -0.124550 * rgb.r + 1.132900 * rgb.g - 0.008349 * rgb.b,
       -0.018151 * rgb.r - 0.100579 * rgb.g + 1.118730 * rgb.b);
}

float hashNoise(float2 position) {
    return frac(sin(dot(position, float2(12.9898, 78.233))) * 43758.5453);
}

float triangularDither(float2 position) {
    float first = hashNoise(position);
    float second = hashNoise(position + float2(37.0, 17.0));
    return (first - second) / 1023.0;
}

float triangularDither8(float2 position) {
    float first = hashNoise(position);
    float second = hashNoise(position + float2(37.0, 17.0));
    return (first - second) / 255.0;
}

float4 pixelMain(VertexOutput input) : SV_TARGET {
    float2 sourceUv;
    if (!projectionUv(input.uv, sourceUv)) return float4(0.0, 0.0, 0.0, 1.0);
    float3 encoded2020 = sampleP010(sourceUv);
    float3 nits = uModes.x == 1 ? hlgToNits(encoded2020) : pqEotf(encoded2020);
    nits = sourcePrimariesToBt2020(nits);
    nits = applyHdr10Plus(nits);
    nits = toneMapNits(nits);
    if (uFlags.y == 0) {
        float3 outputSignal = pqOetf(nits) + triangularDither(input.position.xy);
        return float4(saturate(outputSignal), 1.0);
    }
    if (uFlags.y == 1) {
        float3 linear709 = bt2020ToBt709(nits / max(uView.w, 1.0));
        return float4(linear709, 1.0);
    }
    float3 linear709 = saturate(bt2020ToBt709(nits / max(uView.z, 1.0)));
    float3 outputSignal = pow(linear709, 1.0 / 2.2) + triangularDither8(input.position.xy);
    return float4(saturate(outputSignal), 1.0);
}
)hlsl";

template<typename T>
T ClampTo(float value, float scale) {
    const double scaled = std::round(static_cast<double>(value) * scale);
    return static_cast<T>((std::max)(0.0, (std::min)(scaled, static_cast<double>((std::numeric_limits<T>::max)()))));
}

bool IsFinitePositive(float value) {
    return std::isfinite(value) && value > 0.0f;
}

struct alignas(16) ReferenceShaderConfiguration {
    int32_t modes[4] = {0, 0, 0, 0};
    int32_t flags[4] = {0, 0, 0, 0};
    int32_t color[4] = {0, 0, 0, 0};
    float projection[4] = {360.0f, 0.0f, 0.0f, 0.0f};
    float view[4] = {1.0f, 1000.0f, 1000.0f, 80.0f};
    float crop[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float hdr10PlusHeader[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float hdr10PlusCurve[9][4] = {};
};

static_assert(sizeof(ReferenceShaderConfiguration) == 256,
              "The D3D reference constant buffer must match the production HLSL layout");

struct ReferenceGpu {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11VertexShader> vertexShader;
    ComPtr<ID3D11PixelShader> pixelShader;
    ComPtr<ID3D11SamplerState> sampler;
};

double ReferencePqOetf(double nits) {
    constexpr double m1 = 2610.0 / 16384.0;
    constexpr double m2 = 2523.0 / 32.0;
    constexpr double c1 = 3424.0 / 4096.0;
    constexpr double c2 = 2413.0 / 128.0;
    constexpr double c3 = 2392.0 / 128.0;
    const double value = std::pow((std::max)(0.0, (std::min)(nits, 10000.0)) / 10000.0, m1);
    return std::pow((c1 + c2 * value) / (1.0 + c3 * value), m2);
}

double ReferencePqEotf(double encoded) {
    constexpr double m1 = 2610.0 / 16384.0;
    constexpr double m2 = 2523.0 / 32.0;
    constexpr double c1 = 3424.0 / 4096.0;
    constexpr double c2 = 2413.0 / 128.0;
    constexpr double c3 = 2392.0 / 128.0;
    const double value = std::pow((std::max)(0.0, (std::min)(encoded, 1.0)), 1.0 / m2);
    const double denominator = (std::max)(c2 - c3 * value, 0.000001);
    return std::pow((std::max)((value - c1) / denominator, 0.0), 1.0 / m1) * 10000.0;
}

double ReferenceBt2390(double nits, double sourcePeak, double targetPeak) {
    sourcePeak = (std::max)(sourcePeak, 1.0);
    targetPeak = (std::max)(targetPeak, 1.0);
    if (targetPeak >= sourcePeak) return (std::min)(nits, sourcePeak);
    const double sourcePeakCode = ReferencePqOetf(sourcePeak);
    const double target = (std::max)(0.0, (std::min)(ReferencePqOetf(targetPeak) / sourcePeakCode, 1.0));
    const double knee = (std::max)(0.0, (std::min)(1.5 * target - 0.5, 1.0));
    const double input = (std::max)(0.0, (std::min)(ReferencePqOetf(nits) / sourcePeakCode, 1.0));
    double output = input;
    if (input > knee && knee < 1.0) {
        const double t = (std::max)(0.0, (std::min)((input - knee) / (1.0 - knee), 1.0));
        const double t2 = t * t;
        const double t3 = t2 * t;
        output = (2.0 * t3 - 3.0 * t2 + 1.0) * knee
               + (t3 - 2.0 * t2 + t) * (1.0 - knee)
               + (-2.0 * t3 + 3.0 * t2) * target;
    }
    return (std::min)(ReferencePqEotf((std::max)(0.0, (std::min)(output * sourcePeakCode, 1.0))),
                      targetPeak);
}

double ReferenceHlgInverse(double signal) {
    constexpr double a = 0.17883277;
    constexpr double b = 1.0 - 4.0 * a;
    constexpr double c = 0.55991073;
    return signal <= 0.5 ? signal * signal / 3.0
                         : (std::exp((signal - c) / a) + b) / 12.0;
}

HRESULT CreateReferenceGpu(ID3DBlob* vertexBytecode, ID3DBlob* pixelBytecode, ReferenceGpu* output) {
    if (!vertexBytecode || !pixelBytecode || !output) return E_INVALIDARG;
    D3D_FEATURE_LEVEL featureLevel = D3D_FEATURE_LEVEL_11_0;
    HRESULT hr = D3D11CreateDevice(
        nullptr,
        D3D_DRIVER_TYPE_WARP,
        nullptr,
        0,
        &featureLevel,
        1,
        D3D11_SDK_VERSION,
        output->device.GetAddressOf(),
        nullptr,
        output->context.GetAddressOf());
    if (FAILED(hr)) return hr;
    hr = output->device->CreateVertexShader(
        vertexBytecode->GetBufferPointer(),
        vertexBytecode->GetBufferSize(),
        nullptr,
        output->vertexShader.GetAddressOf());
    if (FAILED(hr)) return hr;
    hr = output->device->CreatePixelShader(
        pixelBytecode->GetBufferPointer(),
        pixelBytecode->GetBufferSize(),
        nullptr,
        output->pixelShader.GetAddressOf());
    if (FAILED(hr)) return hr;
    D3D11_SAMPLER_DESC samplerDescription{};
    samplerDescription.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
    samplerDescription.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDescription.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDescription.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDescription.MaxLOD = D3D11_FLOAT32_MAX;
    return output->device->CreateSamplerState(&samplerDescription, output->sampler.GetAddressOf());
}

HRESULT CreateReferenceTexture(
    ID3D11Device* device,
    DXGI_FORMAT format,
    const void* values,
    UINT rowPitch,
    ID3D11ShaderResourceView** output) {
    if (!device || !values || !output) return E_INVALIDARG;
    D3D11_TEXTURE2D_DESC description{};
    description.Width = 1;
    description.Height = 1;
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = format;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    D3D11_SUBRESOURCE_DATA data{};
    data.pSysMem = values;
    data.SysMemPitch = rowPitch;
    ComPtr<ID3D11Texture2D> texture;
    HRESULT hr = device->CreateTexture2D(&description, &data, texture.GetAddressOf());
    if (FAILED(hr)) return hr;
    return device->CreateShaderResourceView(texture.Get(), nullptr, output);
}

HRESULT RenderReferenceCase(
    const ReferenceGpu& gpu,
    const ReferenceShaderConfiguration& configuration,
    UINT16 yCode,
    UINT16 cbCode,
    UINT16 crCode,
    float output[4]) {
    if (!gpu.device || !gpu.context || !gpu.vertexShader || !gpu.pixelShader || !gpu.sampler || !output) {
        return E_INVALIDARG;
    }
    const UINT16 lumaValue = static_cast<UINT16>(yCode << 6);
    const UINT16 chromaValues[2] = {
        static_cast<UINT16>(cbCode << 6),
        static_cast<UINT16>(crCode << 6),
    };
    ComPtr<ID3D11ShaderResourceView> lumaView;
    ComPtr<ID3D11ShaderResourceView> chromaView;
    HRESULT hr = CreateReferenceTexture(
        gpu.device.Get(), DXGI_FORMAT_R16_UNORM, &lumaValue, sizeof(lumaValue), lumaView.GetAddressOf());
    if (FAILED(hr)) return hr;
    hr = CreateReferenceTexture(
        gpu.device.Get(), DXGI_FORMAT_R16G16_UNORM, chromaValues, sizeof(chromaValues),
        chromaView.GetAddressOf());
    if (FAILED(hr)) return hr;

    D3D11_TEXTURE2D_DESC renderDescription{};
    renderDescription.Width = 1;
    renderDescription.Height = 1;
    renderDescription.MipLevels = 1;
    renderDescription.ArraySize = 1;
    renderDescription.Format = DXGI_FORMAT_R32G32B32A32_FLOAT;
    renderDescription.SampleDesc.Count = 1;
    renderDescription.Usage = D3D11_USAGE_DEFAULT;
    renderDescription.BindFlags = D3D11_BIND_RENDER_TARGET;
    ComPtr<ID3D11Texture2D> renderTexture;
    hr = gpu.device->CreateTexture2D(&renderDescription, nullptr, renderTexture.GetAddressOf());
    if (FAILED(hr)) return hr;
    ComPtr<ID3D11RenderTargetView> renderTarget;
    hr = gpu.device->CreateRenderTargetView(renderTexture.Get(), nullptr, renderTarget.GetAddressOf());
    if (FAILED(hr)) return hr;

    D3D11_BUFFER_DESC bufferDescription{};
    bufferDescription.ByteWidth = sizeof(configuration);
    bufferDescription.Usage = D3D11_USAGE_DEFAULT;
    bufferDescription.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    D3D11_SUBRESOURCE_DATA bufferData{};
    bufferData.pSysMem = &configuration;
    ComPtr<ID3D11Buffer> constantBuffer;
    hr = gpu.device->CreateBuffer(&bufferDescription, &bufferData, constantBuffer.GetAddressOf());
    if (FAILED(hr)) return hr;

    ID3D11RenderTargetView* target = renderTarget.Get();
    gpu.context->OMSetRenderTargets(1, &target, nullptr);
    D3D11_VIEWPORT viewport{};
    viewport.Width = 1.0f;
    viewport.Height = 1.0f;
    viewport.MaxDepth = 1.0f;
    gpu.context->RSSetViewports(1, &viewport);
    gpu.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    gpu.context->VSSetShader(gpu.vertexShader.Get(), nullptr, 0);
    gpu.context->PSSetShader(gpu.pixelShader.Get(), nullptr, 0);
    ID3D11ShaderResourceView* views[2] = {lumaView.Get(), chromaView.Get()};
    gpu.context->PSSetShaderResources(0, 2, views);
    ID3D11SamplerState* sampler = gpu.sampler.Get();
    gpu.context->PSSetSamplers(0, 1, &sampler);
    ID3D11Buffer* buffer = constantBuffer.Get();
    gpu.context->PSSetConstantBuffers(0, 1, &buffer);
    gpu.context->Draw(3, 0);
    ID3D11ShaderResourceView* emptyViews[2] = {nullptr, nullptr};
    gpu.context->PSSetShaderResources(0, 2, emptyViews);

    D3D11_TEXTURE2D_DESC stagingDescription = renderDescription;
    stagingDescription.Usage = D3D11_USAGE_STAGING;
    stagingDescription.BindFlags = 0;
    stagingDescription.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    ComPtr<ID3D11Texture2D> stagingTexture;
    hr = gpu.device->CreateTexture2D(&stagingDescription, nullptr, stagingTexture.GetAddressOf());
    if (FAILED(hr)) return hr;
    gpu.context->CopyResource(stagingTexture.Get(), renderTexture.Get());
    D3D11_MAPPED_SUBRESOURCE mapped{};
    hr = gpu.context->Map(stagingTexture.Get(), 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr)) return hr;
    std::memcpy(output, mapped.pData, sizeof(float) * 4);
    gpu.context->Unmap(stagingTexture.Get(), 0);
    return S_OK;
}

HRESULT RequireReferenceNear(float actual, double expected, double absolute, double relative) {
    const double tolerance = (std::max)(absolute, std::fabs(expected) * relative);
    return std::isfinite(actual) && std::fabs(static_cast<double>(actual) - expected) <= tolerance
        ? S_OK
        : HRESULT_FROM_WIN32(ERROR_INVALID_DATA);
}

UINT16 ReferenceP010Code(double signal, bool fullRange) {
    const double scaled = fullRange ? signal * 1023.0 : 64.0 + signal * 876.0;
    return static_cast<UINT16>(std::lround((std::max)(0.0, (std::min)(scaled, 1023.0))));
}

double ReferenceP010Signal(UINT16 code, bool fullRange) {
    return fullRange ? static_cast<double>(code) / 1023.0
                     : (static_cast<double>(code) - 64.0) / 876.0;
}

HRESULT ValidateReferenceGpuOutput(ID3DBlob* vertexBytecode, ID3DBlob* pixelBytecode) {
    ReferenceGpu gpu;
    HRESULT hr = CreateReferenceGpu(vertexBytecode, pixelBytecode, &gpu);
    if (FAILED(hr)) return hr;

    for (const double sourcePeak : {1000.0, 4000.0}) {
        for (const bool fullRange : {false, true}) {
            ReferenceShaderConfiguration configuration;
            configuration.color[0] = fullRange ? kRangeFull : kRangeLimited;
            configuration.view[1] = static_cast<float>(sourcePeak);
            configuration.view[2] = static_cast<float>(sourcePeak);
            const UINT16 code = ReferenceP010Code(ReferencePqOetf(sourcePeak), fullRange);
            float output[4]{};
            hr = RenderReferenceCase(gpu, configuration, code, 512, 512, output);
            if (FAILED(hr)) return hr;
            hr = RequireReferenceNear(output[0], ReferenceP010Signal(code, fullRange), 0.003, 0.002);
            if (FAILED(hr)) return hr;
        }
    }

    {
        ReferenceShaderConfiguration configuration;
        configuration.view[1] = 4000.0f;
        configuration.view[2] = 1000.0f;
        const UINT16 code = ReferenceP010Code(ReferencePqOetf(2500.0), false);
        const double signal = ReferenceP010Signal(code, false);
        const double expected = ReferencePqOetf(ReferenceBt2390(ReferencePqEotf(signal), 4000.0, 1000.0));
        float output[4]{};
        hr = RenderReferenceCase(gpu, configuration, code, 512, 512, output);
        if (FAILED(hr)) return hr;
        hr = RequireReferenceNear(output[0], expected, 0.003, 0.002);
        if (FAILED(hr)) return hr;
    }

    {
        ReferenceShaderConfiguration configuration;
        configuration.flags[1] = kOutputSdr;
        configuration.flags[3] = 1;
        configuration.view[1] = 1000.0f;
        configuration.view[2] = kSdrTargetPeakNits;
        const UINT16 code = ReferenceP010Code(ReferencePqOetf(50.0), false);
        const double signal = ReferenceP010Signal(code, false);
        const double mapped =
            ReferenceBt2390(ReferencePqEotf(signal), 1000.0, kSdrTargetPeakNits);
        const double expected =
            std::pow((std::max)(0.0, (std::min)(mapped / kSdrTargetPeakNits, 1.0)), 1.0 / 2.2);
        float output[4]{};
        hr = RenderReferenceCase(gpu, configuration, code, 512, 512, output);
        if (FAILED(hr)) return hr;
        for (size_t channel = 0; channel < 3; ++channel) {
            hr = RequireReferenceNear(output[channel], expected, 0.008, 0.002);
            if (FAILED(hr)) return hr;
        }
    }

    for (const double sourcePeak : {1000.0, 4000.0}) {
        ReferenceShaderConfiguration configuration;
        configuration.modes[0] = kTransferHlg;
        configuration.flags[1] = kOutputScRgb;
        configuration.view[1] = static_cast<float>(sourcePeak);
        configuration.view[2] = static_cast<float>(sourcePeak);
        const UINT16 code = ReferenceP010Code(0.75, false);
        const double signal = ReferenceP010Signal(code, false);
        const double gamma = 1.2 + 0.42 * std::log10(sourcePeak / 1000.0);
        const double nits = std::pow(ReferenceHlgInverse(signal), gamma) * sourcePeak;
        const double expected[3] = {
            nits * (1.660491 - 0.587641 - 0.072850) / 80.0,
            nits * (-0.124550 + 1.132900 - 0.008349) / 80.0,
            nits * (-0.018151 - 0.100579 + 1.118730) / 80.0,
        };
        float output[4]{};
        hr = RenderReferenceCase(gpu, configuration, code, 512, 512, output);
        if (FAILED(hr)) return hr;
        for (size_t channel = 0; channel < 3; ++channel) {
            hr = RequireReferenceNear(output[channel], expected[channel], 0.02, 0.001);
            if (FAILED(hr)) return hr;
        }
    }

    for (const double sourcePeak : {1000.0, 4000.0}) {
        ReferenceShaderConfiguration configuration;
        configuration.view[1] = static_cast<float>(sourcePeak);
        configuration.view[2] = sourcePeak == 1000.0 ? 600.0f : 1000.0f;
        configuration.hdr10PlusHeader[0] = 1.0f;
        configuration.hdr10PlusHeader[1] = static_cast<float>(sourcePeak);
        const double targetPeak = configuration.view[2];
        for (size_t index = 0; index < KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT; ++index) {
            configuration.hdr10PlusCurve[index / 4][index % 4] =
                static_cast<float>((targetPeak * static_cast<double>(index) / 32.0) / 10000.0);
        }
        const UINT16 code = ReferenceP010Code(ReferencePqOetf(sourcePeak * 0.5), false);
        const double signal = ReferenceP010Signal(code, false);
        const double normalized = (std::max)(0.0, (std::min)(ReferencePqEotf(signal) / sourcePeak, 1.0));
        const double expected = ReferencePqOetf(targetPeak * normalized);
        float output[4]{};
        hr = RenderReferenceCase(gpu, configuration, code, 512, 512, output);
        if (FAILED(hr)) return hr;
        hr = RequireReferenceNear(output[0], expected, 0.003, 0.002);
        if (FAILED(hr)) return hr;
    }

    for (const int primaries : {kPrimariesBt709, kPrimariesDisplayP3}) {
        ReferenceShaderConfiguration configuration;
        configuration.flags[1] = kOutputScRgb;
        configuration.color[2] = primaries;
        configuration.view[1] = 4000.0f;
        configuration.view[2] = 4000.0f;
        const UINT16 yCode = static_cast<UINT16>(std::lround(64.0 + 0.55 * 876.0));
        const UINT16 cbCode = static_cast<UINT16>(std::lround(512.0 + 0.05 * 896.0));
        const UINT16 crCode = static_cast<UINT16>(std::lround(512.0 - 0.04 * 896.0));
        const double y = ReferenceP010Signal(yCode, false);
        const double cb = (static_cast<double>(cbCode) - 512.0) / 896.0;
        const double cr = (static_cast<double>(crCode) - 512.0) / 896.0;
        const double encoded[3] = {
            (std::max)(y + 1.4746 * cr, 0.0),
            (std::max)(y - 0.164553 * cb - 0.571353 * cr, 0.0),
            (std::max)(y + 1.8814 * cb, 0.0),
        };
        const double decoded[3] = {
            ReferencePqEotf(encoded[0]),
            ReferencePqEotf(encoded[1]),
            ReferencePqEotf(encoded[2]),
        };
        double bt2020[3]{};
        if (primaries == kPrimariesBt709) {
            bt2020[0] = 0.627404 * decoded[0] + 0.329283 * decoded[1] + 0.043313 * decoded[2];
            bt2020[1] = 0.069097 * decoded[0] + 0.919540 * decoded[1] + 0.011362 * decoded[2];
            bt2020[2] = 0.016391 * decoded[0] + 0.088013 * decoded[1] + 0.895595 * decoded[2];
        } else {
            bt2020[0] = 0.753833 * decoded[0] + 0.198597 * decoded[1] + 0.047570 * decoded[2];
            bt2020[1] = 0.045744 * decoded[0] + 0.941777 * decoded[1] + 0.012479 * decoded[2];
            bt2020[2] = -0.001210 * decoded[0] + 0.017602 * decoded[1] + 0.983609 * decoded[2];
        }
        const double expected[3] = {
            (1.660491 * bt2020[0] - 0.587641 * bt2020[1] - 0.072850 * bt2020[2]) / 80.0,
            (-0.124550 * bt2020[0] + 1.132900 * bt2020[1] - 0.008349 * bt2020[2]) / 80.0,
            (-0.018151 * bt2020[0] - 0.100579 * bt2020[1] + 1.118730 * bt2020[2]) / 80.0,
        };
        float output[4]{};
        hr = RenderReferenceCase(gpu, configuration, yCode, cbCode, crCode, output);
        if (FAILED(hr)) return hr;
        for (size_t channel = 0; channel < 3; ++channel) {
            hr = RequireReferenceNear(output[channel], expected[channel], 0.04, 0.0015);
            if (FAILED(hr)) return hr;
        }
    }
    return S_OK;
}

} // namespace

WindowsHdrPresenter::WindowsHdrPresenter() {
    std::memset(&status_, 0, sizeof(status_));
    status_.displayColorSpace = static_cast<UINT32>(DXGI_COLOR_SPACE_CUSTOM);
    status_.swapChainColorSpace = static_cast<UINT32>(DXGI_COLOR_SPACE_CUSTOM);
    status_.lastError = S_OK;
}

HRESULT WindowsHdrPresenter::ValidateShaders() {
    ComPtr<ID3DBlob> vertexShader;
    ComPtr<ID3DBlob> pixelShader;
    ComPtr<ID3DBlob> errors;
    HRESULT hr = D3DCompile(kHdrShader, std::strlen(kHdrShader), "KMediaPlayerHdrPresenter", nullptr, nullptr,
                            "vertexMain", "vs_5_0", D3DCOMPILE_ENABLE_STRICTNESS | D3DCOMPILE_WARNINGS_ARE_ERRORS,
                            0, vertexShader.GetAddressOf(), errors.GetAddressOf());
    if (FAILED(hr)) {
        if (errors) {
            std::fprintf(
                stderr,
                "Windows HDR vertex shader compilation failed: %.*s\n",
                static_cast<int>(errors->GetBufferSize()),
                static_cast<const char*>(errors->GetBufferPointer()));
        }
        return hr;
    }
    errors.Reset();
    hr = D3DCompile(kHdrShader, std::strlen(kHdrShader), "KMediaPlayerHdrPresenter", nullptr, nullptr,
                    "pixelMain", "ps_5_0", D3DCOMPILE_ENABLE_STRICTNESS | D3DCOMPILE_WARNINGS_ARE_ERRORS,
                    0, pixelShader.GetAddressOf(), errors.GetAddressOf());
    if (FAILED(hr)) {
        if (errors) {
            std::fprintf(
                stderr,
                "Windows HDR pixel shader compilation failed: %.*s\n",
                static_cast<int>(errors->GetBufferSize()),
                static_cast<const char*>(errors->GetBufferPointer()));
        }
        return hr;
    }
    return ValidateReferenceGpuOutput(vertexShader.Get(), pixelShader.Get());
}

WindowsHdrPresenter::~WindowsHdrPresenter() {
    Detach();
}

HRESULT WindowsHdrPresenter::Configure(
    const int32_t* integers,
    size_t integerCount,
    const float* values,
    size_t valueCount) {
    if (!integers || !values || integerCount < kRequiredIntegerConfiguration ||
        valueCount < kRequiredFloatingConfiguration) {
        return E_INVALIDARG;
    }
    if ((integers[0] != kTransferPq && integers[0] != kTransferHlg) ||
        integers[1] < 0 || integers[1] > 7 ||
        integers[2] < 0 || integers[2] > 2 ||
        integers[3] < 0 || integers[3] > 1 ||
        integers[4] < 0 || integers[4] > 3 ||
        integers[5] < kRangeLimited || integers[5] > kRangeFull ||
        integers[6] < kMatrixBt2020 || integers[6] > kMatrixBt601 ||
        integers[7] < kPrimariesBt2020 || integers[7] > kPrimariesDisplayP3 ||
        integers[8] < 0 || integers[8] > 1 ||
        integers[9] < 0 || integers[9] > 1) {
        return E_INVALIDARG;
    }
    for (size_t index = 0; index < valueCount; ++index) {
        if (!std::isfinite(values[index])) return E_INVALIDARG;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    const bool outputConfigurationChanged =
        shaderConfiguration_.modes[0] != integers[0] ||
        shaderConfiguration_.flags[3] != integers[9];
    shaderConfiguration_.modes[0] = integers[0];
    shaderConfiguration_.modes[1] = integers[1];
    shaderConfiguration_.modes[2] = integers[2];
    shaderConfiguration_.modes[3] = integers[4];
    shaderConfiguration_.flags[0] = integers[3];
    shaderConfiguration_.flags[2] = integers[8];
    shaderConfiguration_.flags[3] = integers[9];
    shaderConfiguration_.color[0] = integers[5];
    shaderConfiguration_.color[1] = integers[6];
    shaderConfiguration_.color[2] = integers[7];
    shaderConfiguration_.projection[0] = (std::max)(1.0f, (std::min)(values[0], 360.0f));
    shaderConfiguration_.projection[1] = values[1];
    shaderConfiguration_.projection[2] = (std::max)(-89.0f, (std::min)(values[2], 89.0f));
    shaderConfiguration_.projection[3] = values[3];
    shaderConfiguration_.view[0] = (std::max)(0.5f, (std::min)(values[4], 4.0f));
    for (size_t index = 0; index < 4; ++index) {
        shaderConfiguration_.crop[index] = (std::max)(0.0f, (std::min)(values[5 + index], 0.49f));
    }
    shaderConfiguration_.view[1] = IsFinitePositive(values[9]) ? values[9] : 1000.0f;
    std::memset(shaderConfiguration_.hdr10PlusHeader, 0, sizeof(shaderConfiguration_.hdr10PlusHeader));
    std::memset(shaderConfiguration_.hdr10PlusCurve, 0, sizeof(shaderConfiguration_.hdr10PlusCurve));

    const float defaults[12] = {
        0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f,
        0.3127f, 0.3290f, 0.005f, 1000.0f, 1000.0f, 400.0f
    };
    float metadata[12];
    for (size_t index = 0; index < 12; ++index) {
        metadata[index] = IsFinitePositive(values[10 + index]) ? values[10 + index] : defaults[index];
    }
    masteringConfiguration_.redX = metadata[0];
    masteringConfiguration_.redY = metadata[1];
    masteringConfiguration_.greenX = metadata[2];
    masteringConfiguration_.greenY = metadata[3];
    masteringConfiguration_.blueX = metadata[4];
    masteringConfiguration_.blueY = metadata[5];
    masteringConfiguration_.whiteX = metadata[6];
    masteringConfiguration_.whiteY = metadata[7];
    masteringConfiguration_.minLuminanceNits = metadata[8];
    masteringConfiguration_.maxLuminanceNits = metadata[9];
    masteringConfiguration_.maxContentLightLevelNits = metadata[10];
    masteringConfiguration_.maxFrameAverageLightLevelNits = metadata[11];

    status_.firstFramePresented = FALSE;
    status_.p010InputConfirmed = FALSE;

    if (swapChain_) {
        HRESULT hr = RefreshOutputAndSwapChain(outputConfigurationChanged);
        if (SUCCEEDED(hr)) hr = UploadConfiguration();
        if (SUCCEEDED(hr)) hr = ApplySwapChainMetadata();
        RecordError(hr);
        return hr;
    }
    return S_OK;
}

HRESULT WindowsHdrPresenter::Attach(HWND hwnd) {
    if (!hwnd || !IsWindow(hwnd)) return E_INVALIDARG;
    std::lock_guard<std::mutex> lock(mutex_);
    const bool changedWindow = hwnd_ != hwnd;
    hwnd_ = hwnd;
    HRESULT hr = CreateDeviceResources();
    if (SUCCEEDED(hr)) hr = RefreshOutputAndSwapChain(changedWindow);
    RecordError(hr);
    return hr;
}

void WindowsHdrPresenter::Detach() {
    std::lock_guard<std::mutex> lock(mutex_);
    ReleaseSwapChainResources();
    hwnd_ = nullptr;
    monitor_ = nullptr;
    status_.displayQueried = FALSE;
    status_.advancedColorEnabled = FALSE;
    status_.swapChainConfigured = FALSE;
    status_.firstFramePresented = FALSE;
    status_.p010InputConfirmed = FALSE;
}

HRESULT WindowsHdrPresenter::CreateDeviceResources() {
    static_assert(sizeof(ShaderConfiguration) == 256,
                  "The HLSL and C++ HDR constant-buffer layouts must stay identical");
    if (device_ && context_ && vertexShader_ && pixelShader_ && sampler_ && configurationBuffer_) return S_OK;
    ID3D11Device* sharedDevice = MediaFoundation::GetD3DDevice();
    if (!sharedDevice) return MF_E_NOT_INITIALIZED;
    device_ = sharedDevice;
    device_->GetImmediateContext(context_.ReleaseAndGetAddressOf());
    if (!context_) return E_NOINTERFACE;

    ComPtr<IDXGIDevice> dxgiDevice;
    ComPtr<IDXGIAdapter> adapter;
    HRESULT hr = device_.As(&dxgiDevice);
    if (SUCCEEDED(hr)) hr = dxgiDevice->GetAdapter(adapter.GetAddressOf());
    if (SUCCEEDED(hr)) hr = adapter->GetParent(IID_PPV_ARGS(factory_.ReleaseAndGetAddressOf()));
    if (FAILED(hr)) return hr;

    ComPtr<ID3DBlob> vertexBlob;
    ComPtr<ID3DBlob> pixelBlob;
    ComPtr<ID3DBlob> errors;
    hr = D3DCompile(kHdrShader, std::strlen(kHdrShader), "KMediaPlayerHdrPresenter", nullptr, nullptr,
                    "vertexMain", "vs_5_0", D3DCOMPILE_ENABLE_STRICTNESS, 0,
                    vertexBlob.GetAddressOf(), errors.GetAddressOf());
    if (FAILED(hr)) {
        if (errors) ComposeMediaPlayer::NativeLogging::Logf("Windows HDR vertex shader: %s\n",
            static_cast<const char*>(errors->GetBufferPointer()));
        return hr;
    }
    errors.Reset();
    hr = D3DCompile(kHdrShader, std::strlen(kHdrShader), "KMediaPlayerHdrPresenter", nullptr, nullptr,
                    "pixelMain", "ps_5_0", D3DCOMPILE_ENABLE_STRICTNESS, 0,
                    pixelBlob.GetAddressOf(), errors.GetAddressOf());
    if (FAILED(hr)) {
        if (errors) ComposeMediaPlayer::NativeLogging::Logf("Windows HDR pixel shader: %s\n",
            static_cast<const char*>(errors->GetBufferPointer()));
        return hr;
    }
    hr = device_->CreateVertexShader(vertexBlob->GetBufferPointer(), vertexBlob->GetBufferSize(),
                                     nullptr, vertexShader_.ReleaseAndGetAddressOf());
    if (SUCCEEDED(hr)) {
        hr = device_->CreatePixelShader(pixelBlob->GetBufferPointer(), pixelBlob->GetBufferSize(),
                                        nullptr, pixelShader_.ReleaseAndGetAddressOf());
    }
    if (FAILED(hr)) return hr;

    D3D11_SAMPLER_DESC sampler{};
    sampler.Filter = D3D11_FILTER_MIN_MAG_LINEAR_MIP_POINT;
    sampler.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.MaxLOD = D3D11_FLOAT32_MAX;
    hr = device_->CreateSamplerState(&sampler, sampler_.ReleaseAndGetAddressOf());
    if (FAILED(hr)) return hr;

    D3D11_BUFFER_DESC buffer{};
    buffer.ByteWidth = static_cast<UINT>(sizeof(ShaderConfiguration));
    buffer.Usage = D3D11_USAGE_DYNAMIC;
    buffer.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    buffer.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    return device_->CreateBuffer(&buffer, nullptr, configurationBuffer_.ReleaseAndGetAddressOf());
}

HRESULT WindowsHdrPresenter::QueryActiveOutput() {
    if (!hwnd_ || !factory_) return E_HANDLE;
    const HMONITOR requestedMonitor = MonitorFromWindow(hwnd_, MONITOR_DEFAULTTONEAREST);
    if (!requestedMonitor) return DXGI_ERROR_NOT_FOUND;

    ComPtr<IDXGIOutput6> matchingOutput;
    for (UINT adapterIndex = 0; !matchingOutput; ++adapterIndex) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory_->EnumAdapters1(adapterIndex, adapter.GetAddressOf()) == DXGI_ERROR_NOT_FOUND) break;
        for (UINT outputIndex = 0; ; ++outputIndex) {
            ComPtr<IDXGIOutput> output;
            if (adapter->EnumOutputs(outputIndex, output.GetAddressOf()) == DXGI_ERROR_NOT_FOUND) break;
            DXGI_OUTPUT_DESC basic{};
            if (SUCCEEDED(output->GetDesc(&basic)) && basic.Monitor == requestedMonitor) {
                output.As(&matchingOutput);
                break;
            }
        }
    }
    if (!matchingOutput) return DXGI_ERROR_NOT_FOUND;

    DXGI_OUTPUT_DESC1 description{};
    HRESULT hr = matchingOutput->GetDesc1(&description);
    if (FAILED(hr)) return hr;
    const bool monitorChanged = monitor_ != requestedMonitor;
    monitor_ = requestedMonitor;
    outputDescription_ = description;
    status_.displayQueried = TRUE;
    status_.bitsPerColor = description.BitsPerColor;
    status_.displayColorSpace = static_cast<UINT32>(description.ColorSpace);
    status_.minLuminanceNits = description.MinLuminance;
    status_.maxLuminanceNits = description.MaxLuminance;
    status_.maxFullFrameLuminanceNits = description.MaxFullFrameLuminance;
    // DXGI defines the active output color space as the authoritative signal
    // for HDR/Advanced Color. BitsPerColor describes the active wire format
    // and may be reported as 8 when the driver uses dithering, even while the
    // desktop is actively composed in BT.2020/PQ.
    status_.advancedColorEnabled =
        description.ColorSpace == DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020;
    if (monitorChanged) ++status_.monitorGeneration;
    shaderConfiguration_.view[2] =
        shaderConfiguration_.flags[3] != 0
            ? kSdrTargetPeakNits
            : (IsFinitePositive(description.MaxLuminance)
                ? description.MaxLuminance
                : masteringConfiguration_.maxLuminanceNits);
    shaderConfiguration_.view[3] = kScRgbReferenceWhiteNits;
    return S_OK;
}

HRESULT WindowsHdrPresenter::RefreshOutputAndSwapChain(bool forceRecreate) {
    const HMONITOR previousMonitor = monitor_;
    HRESULT hr = QueryActiveOutput();
    if (FAILED(hr)) return hr;
    const bool sdrOutput = shaderConfiguration_.flags[3] != 0;
    if (!sdrOutput && !status_.advancedColorEnabled) return DXGI_ERROR_UNSUPPORTED;

    RECT client{};
    if (!GetClientRect(hwnd_, &client)) return HRESULT_FROM_WIN32(GetLastError());
    const UINT width = static_cast<UINT>((std::max)(1L, client.right - client.left));
    const UINT height = static_cast<UINT>((std::max)(1L, client.bottom - client.top));
    const bool monitorChanged = previousMonitor != monitor_;
    const DXGI_FORMAT desiredFormat =
        sdrOutput
            ? DXGI_FORMAT_R8G8B8A8_UNORM
            : (shaderConfiguration_.modes[0] == kTransferPq
                ? DXGI_FORMAT_R10G10B10A2_UNORM
                : DXGI_FORMAT_R16G16B16A16_FLOAT);
    if (forceRecreate || monitorChanged || !swapChain_ || desiredFormat != swapChainFormat_) {
        return CreateSwapChain(width, height);
    }
    return ResizeSwapChainIfNeeded();
}

HRESULT WindowsHdrPresenter::CreateSwapChain(UINT width, UINT height) {
    ReleaseSwapChainResources();
    const bool sdrOutput = shaderConfiguration_.flags[3] != 0;
    if (sdrOutput) {
        swapChainFormat_ = DXGI_FORMAT_R8G8B8A8_UNORM;
        swapChainColorSpace_ = DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709;
        shaderConfiguration_.flags[1] = kOutputSdr;
    } else if (shaderConfiguration_.modes[0] == kTransferPq) {
        swapChainFormat_ = DXGI_FORMAT_R10G10B10A2_UNORM;
        swapChainColorSpace_ = DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020;
        shaderConfiguration_.flags[1] = kOutputPq;
    } else {
        swapChainFormat_ = DXGI_FORMAT_R16G16B16A16_FLOAT;
        swapChainColorSpace_ = DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709;
        shaderConfiguration_.flags[1] = kOutputScRgb;
    }

    DXGI_SWAP_CHAIN_DESC1 description{};
    description.Width = width;
    description.Height = height;
    description.Format = swapChainFormat_;
    description.SampleDesc.Count = 1;
    description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    description.BufferCount = 3;
    description.Scaling = DXGI_SCALING_STRETCH;
    description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    description.AlphaMode = DXGI_ALPHA_MODE_IGNORE;

    ComPtr<IDXGISwapChain1> swapChain1;
    HRESULT hr = factory_->CreateSwapChainForHwnd(
        device_.Get(), hwnd_, &description, nullptr, nullptr, swapChain1.GetAddressOf());
    if (FAILED(hr)) return hr;
    factory_->MakeWindowAssociation(hwnd_, DXGI_MWA_NO_ALT_ENTER);
    hr = swapChain1.As(&swapChain_);
    if (FAILED(hr)) return hr;

    UINT colorSpaceSupport = 0;
    hr = swapChain_->CheckColorSpaceSupport(swapChainColorSpace_, &colorSpaceSupport);
    if (FAILED(hr) || (colorSpaceSupport & DXGI_SWAP_CHAIN_COLOR_SPACE_SUPPORT_FLAG_PRESENT) == 0) {
        return FAILED(hr) ? hr : DXGI_ERROR_UNSUPPORTED;
    }
    hr = swapChain_->SetColorSpace1(swapChainColorSpace_);
    if (FAILED(hr)) return hr;
    swapChainWidth_ = width;
    swapChainHeight_ = height;
    status_.swapChainColorSpace = static_cast<UINT32>(swapChainColorSpace_);
    hr = CreateBackBufferView();
    if (SUCCEEDED(hr)) hr = UploadConfiguration();
    if (SUCCEEDED(hr)) hr = ApplySwapChainMetadata();
    if (SUCCEEDED(hr)) status_.swapChainConfigured = TRUE;
    return hr;
}

HRESULT WindowsHdrPresenter::CreateBackBufferView() {
    ComPtr<ID3D11Texture2D> backBuffer;
    HRESULT hr = swapChain_->GetBuffer(0, IID_PPV_ARGS(backBuffer.GetAddressOf()));
    if (FAILED(hr)) return hr;
    return device_->CreateRenderTargetView(backBuffer.Get(), nullptr, renderTargetView_.ReleaseAndGetAddressOf());
}

HRESULT WindowsHdrPresenter::ResizeSwapChainIfNeeded() {
    if (!swapChain_ || !hwnd_) return E_HANDLE;
    RECT client{};
    if (!GetClientRect(hwnd_, &client)) return HRESULT_FROM_WIN32(GetLastError());
    const UINT width = static_cast<UINT>((std::max)(1L, client.right - client.left));
    const UINT height = static_cast<UINT>((std::max)(1L, client.bottom - client.top));
    if (width == swapChainWidth_ && height == swapChainHeight_) return S_OK;
    context_->OMSetRenderTargets(0, nullptr, nullptr);
    renderTargetView_.Reset();
    HRESULT hr = swapChain_->ResizeBuffers(0, width, height, swapChainFormat_, 0);
    if (FAILED(hr)) return hr;
    swapChainWidth_ = width;
    swapChainHeight_ = height;
    return CreateBackBufferView();
}

HRESULT WindowsHdrPresenter::CreateInputViews(ID3D11Texture2D* source, UINT sourceSubresource) {
    if (!source) return E_INVALIDARG;
    D3D11_TEXTURE2D_DESC sourceDescription{};
    source->GetDesc(&sourceDescription);
    if (sourceDescription.Format != DXGI_FORMAT_P010) return MF_E_INVALIDMEDIATYPE;

    if (!shaderInputTexture_ || inputWidth_ != sourceDescription.Width || inputHeight_ != sourceDescription.Height) {
        shaderInputTexture_.Reset();
        lumaView_.Reset();
        chromaView_.Reset();
        D3D11_TEXTURE2D_DESC inputDescription{};
        inputDescription.Width = sourceDescription.Width;
        inputDescription.Height = sourceDescription.Height;
        inputDescription.MipLevels = 1;
        inputDescription.ArraySize = 1;
        inputDescription.Format = DXGI_FORMAT_P010;
        inputDescription.SampleDesc.Count = 1;
        inputDescription.Usage = D3D11_USAGE_DEFAULT;
        inputDescription.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        HRESULT hr = device_->CreateTexture2D(&inputDescription, nullptr, shaderInputTexture_.GetAddressOf());
        if (FAILED(hr)) return hr;

        D3D11_SHADER_RESOURCE_VIEW_DESC lumaDescription{};
        lumaDescription.Format = DXGI_FORMAT_R16_UNORM;
        lumaDescription.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
        lumaDescription.Texture2D.MipLevels = 1;
        hr = device_->CreateShaderResourceView(
            shaderInputTexture_.Get(), &lumaDescription, lumaView_.GetAddressOf());
        if (FAILED(hr)) return hr;
        D3D11_SHADER_RESOURCE_VIEW_DESC chromaDescription = lumaDescription;
        chromaDescription.Format = DXGI_FORMAT_R16G16_UNORM;
        hr = device_->CreateShaderResourceView(
            shaderInputTexture_.Get(), &chromaDescription, chromaView_.GetAddressOf());
        if (FAILED(hr)) return hr;
        inputWidth_ = sourceDescription.Width;
        inputHeight_ = sourceDescription.Height;
    }
    context_->CopySubresourceRegion(shaderInputTexture_.Get(), 0, 0, 0, 0,
                                    source, sourceSubresource, nullptr);
    status_.p010InputConfirmed = TRUE;
    return S_OK;
}

HRESULT WindowsHdrPresenter::UploadConfiguration() {
    if (!configurationBuffer_ || !context_) return MF_E_NOT_INITIALIZED;
    D3D11_MAPPED_SUBRESOURCE mapped{};
    HRESULT hr = context_->Map(configurationBuffer_.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped);
    if (FAILED(hr)) return hr;
    std::memcpy(mapped.pData, &shaderConfiguration_, sizeof(shaderConfiguration_));
    context_->Unmap(configurationBuffer_.Get(), 0);
    return S_OK;
}

HRESULT WindowsHdrPresenter::ApplySwapChainMetadata() {
    if (!swapChain_) return MF_E_NOT_INITIALIZED;
    if (swapChainColorSpace_ != DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020) {
        return swapChain_->SetHDRMetaData(DXGI_HDR_METADATA_TYPE_NONE, 0, nullptr);
    }
    DXGI_HDR_METADATA_HDR10 metadata{};
    metadata.RedPrimary[0] = ClampTo<UINT16>(masteringConfiguration_.redX, 50000.0f);
    metadata.RedPrimary[1] = ClampTo<UINT16>(masteringConfiguration_.redY, 50000.0f);
    metadata.GreenPrimary[0] = ClampTo<UINT16>(masteringConfiguration_.greenX, 50000.0f);
    metadata.GreenPrimary[1] = ClampTo<UINT16>(masteringConfiguration_.greenY, 50000.0f);
    metadata.BluePrimary[0] = ClampTo<UINT16>(masteringConfiguration_.blueX, 50000.0f);
    metadata.BluePrimary[1] = ClampTo<UINT16>(masteringConfiguration_.blueY, 50000.0f);
    metadata.WhitePoint[0] = ClampTo<UINT16>(masteringConfiguration_.whiteX, 50000.0f);
    metadata.WhitePoint[1] = ClampTo<UINT16>(masteringConfiguration_.whiteY, 50000.0f);
    metadata.MaxMasteringLuminance = ClampTo<UINT32>(masteringConfiguration_.maxLuminanceNits, 10000.0f);
    metadata.MinMasteringLuminance = ClampTo<UINT32>(masteringConfiguration_.minLuminanceNits, 10000.0f);
    metadata.MaxContentLightLevel = ClampTo<UINT16>(masteringConfiguration_.maxContentLightLevelNits, 1.0f);
    metadata.MaxFrameAverageLightLevel =
        ClampTo<UINT16>(masteringConfiguration_.maxFrameAverageLightLevelNits, 1.0f);
    return swapChain_->SetHDRMetaData(DXGI_HDR_METADATA_TYPE_HDR10, sizeof(metadata), &metadata);
}

bool WindowsHdrPresenter::RequiresHdr10PlusMetadata() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return shaderConfiguration_.flags[2] != 0;
}

HRESULT WindowsHdrPresenter::Render(
    IMFSample* sample,
    UINT32 width,
    UINT32 height,
    const uint8_t* hdr10PlusPayload,
    size_t hdr10PlusPayloadSize) {
    if (!sample || width == 0 || height == 0) return E_INVALIDARG;
    std::lock_guard<std::mutex> lock(mutex_);
    if (!hwnd_) return MF_E_NOT_INITIALIZED;
    if (++frameCounter_ >= kOutputRefreshIntervalFrames) {
        frameCounter_ = 0;
        HRESULT refresh = RefreshOutputAndSwapChain(false);
        if (FAILED(refresh)) {
            RecordError(refresh);
            return refresh;
        }
    } else {
        HRESULT resize = ResizeSwapChainIfNeeded();
        if (FAILED(resize)) {
            RecordError(resize);
            return resize;
        }
    }

    if (shaderConfiguration_.flags[2] != 0) {
        float sourcePeakNits = 0.0f;
        float curve[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT] = {};
        char error[256] = {};
        if (!hdr10PlusPayload || hdr10PlusPayloadSize == 0 ||
            !kmp_hdr10_plus_parse_tone_curve(
                hdr10PlusPayload,
                hdr10PlusPayloadSize,
                IsFinitePositive(shaderConfiguration_.view[2]) ? shaderConfiguration_.view[2] : 1000.0,
                &sourcePeakNits,
                curve,
                error,
                sizeof(error))) {
            shaderConfiguration_.hdr10PlusHeader[0] = 0.0f;
            return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
        }
        shaderConfiguration_.hdr10PlusHeader[0] = 1.0f;
        shaderConfiguration_.hdr10PlusHeader[1] = sourcePeakNits;
        std::memset(shaderConfiguration_.hdr10PlusCurve, 0, sizeof(shaderConfiguration_.hdr10PlusCurve));
        for (size_t index = 0; index < KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT; ++index) {
            shaderConfiguration_.hdr10PlusCurve[index / 4][index % 4] = curve[index];
        }
    } else {
        shaderConfiguration_.hdr10PlusHeader[0] = 0.0f;
    }

    ComPtr<IMFMediaBuffer> mediaBuffer;
    HRESULT hr = sample->GetBufferByIndex(0, mediaBuffer.GetAddressOf());
    if (FAILED(hr)) return hr;
    ComPtr<IMFDXGIBuffer> dxgiBuffer;
    hr = mediaBuffer.As(&dxgiBuffer);
    if (FAILED(hr)) {
        RecordError(hr);
        return hr;
    }
    ComPtr<ID3D11Texture2D> texture;
    hr = dxgiBuffer->GetResource(IID_PPV_ARGS(texture.GetAddressOf()));
    UINT subresource = 0;
    if (SUCCEEDED(hr)) hr = dxgiBuffer->GetSubresourceIndex(&subresource);
    if (SUCCEEDED(hr)) hr = CreateInputViews(texture.Get(), subresource);
    if (SUCCEEDED(hr)) hr = UploadConfiguration();
    if (FAILED(hr)) {
        RecordError(hr);
        return hr;
    }

    const float clear[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    context_->ClearRenderTargetView(renderTargetView_.Get(), clear);
    ID3D11RenderTargetView* target = renderTargetView_.Get();
    context_->OMSetRenderTargets(1, &target, nullptr);
    D3D11_VIEWPORT viewport{};
    viewport.Width = static_cast<float>(swapChainWidth_);
    viewport.Height = static_cast<float>(swapChainHeight_);
    viewport.MaxDepth = 1.0f;
    context_->RSSetViewports(1, &viewport);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->VSSetShader(vertexShader_.Get(), nullptr, 0);
    context_->PSSetShader(pixelShader_.Get(), nullptr, 0);
    ID3D11ShaderResourceView* views[2] = {lumaView_.Get(), chromaView_.Get()};
    context_->PSSetShaderResources(0, 2, views);
    ID3D11SamplerState* sampler = sampler_.Get();
    context_->PSSetSamplers(0, 1, &sampler);
    ID3D11Buffer* configuration = configurationBuffer_.Get();
    context_->PSSetConstantBuffers(0, 1, &configuration);
    context_->Draw(3, 0);
    ID3D11ShaderResourceView* emptyViews[2] = {nullptr, nullptr};
    context_->PSSetShaderResources(0, 2, emptyViews);

    hr = swapChain_->Present(1, 0);
    if (SUCCEEDED(hr)) status_.firstFramePresented = TRUE;
    RecordError(hr);
    return hr;
}

HdrOutputStatus WindowsHdrPresenter::GetStatus() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return status_;
}

void WindowsHdrPresenter::ReleaseSwapChainResources() {
    if (context_) {
        context_->OMSetRenderTargets(0, nullptr, nullptr);
        context_->Flush();
    }
    renderTargetView_.Reset();
    swapChain_.Reset();
    swapChainWidth_ = 0;
    swapChainHeight_ = 0;
    swapChainFormat_ = DXGI_FORMAT_UNKNOWN;
    swapChainColorSpace_ = DXGI_COLOR_SPACE_CUSTOM;
    status_.swapChainConfigured = FALSE;
    status_.firstFramePresented = FALSE;
    status_.swapChainColorSpace = static_cast<UINT32>(DXGI_COLOR_SPACE_CUSTOM);
}

void WindowsHdrPresenter::RecordError(HRESULT error) {
    status_.lastError = error;
}
