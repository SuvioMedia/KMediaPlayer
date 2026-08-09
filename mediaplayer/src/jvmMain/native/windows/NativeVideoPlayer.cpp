// NativeVideoPlayer.cpp
#include "NativeVideoPlayer.h"
#include "VideoPlayerInstance.h"
#include "Utils.h"
#include "MediaFoundationManager.h"
#include "AudioManager.h"
#include "HLSPlayer.h"
#include "NativeLogging.h"
#include "WindowsHdrPresenter.h"
#include "Hdr10PlusHevcParser.h"
#include "Hdr10PlusToneCurve.h"
#include <algorithm>
#include <codecapi.h>
#include <cstring>
#include <cstdint>
#include <limits>
#include <memory>
#include <mfapi.h>
#include <mferror.h>
#include <propsys.h>
#include <propvarutil.h>
#include <wininet.h>
#include <string>
#include <cctype>
#include <cwctype>
#include <evr.h>
#include <wrl/client.h>
#include <intrin.h>
#if defined(_M_IX86) || defined(_M_X64)
  #include <immintrin.h>
  #define NVP_HAS_AVX2_INTRINSICS 1
#else
  #define NVP_HAS_AVX2_INTRINSICS 0
#endif

using Microsoft::WRL::ComPtr;
using namespace VideoPlayerUtils;
using namespace MediaFoundation;
using namespace AudioManager;

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
static constexpr UINT   kDefaultFrameRateNum    = 30;
static constexpr UINT   kDefaultFrameRateDenom  = 1;
// A frame that is more than a quarter of a frame interval late cannot be
// copied into the Compose surface inside the 45 ms lip-sync budget. Drop it
// here and let the decoder catch up.
static constexpr double kFrameSkipThreshold     = 0.25; // frame intervals
static constexpr double kFrameAheadMinMs        = 1.0;
static constexpr LONGLONG kHdrMetadataTimestampTolerance = 10000; // 1 ms in MF ticks.
static constexpr int kHdrMetadataMaximumReadIterations = 128;
static constexpr int kHdr10PlusProbeMaximumReadIterations = 32;
static constexpr int32_t kDecodedColorFlagValidatedHdr10Plus = 1 << 1;

// ---------------------------------------------------------------------------
// Debug printing
// ---------------------------------------------------------------------------
#define PrintHR(msg, hr) \
    ComposeMediaPlayer::NativeLogging::Logf("%s (hr=0x%08x)\n", msg, static_cast<unsigned int>(hr))


// ---------------------------------------------------------------------------
// VideoPlayerInstance dtor — RAII teardown
// ---------------------------------------------------------------------------
VideoPlayerInstance::~VideoPlayerInstance() {
    CloseMedia(this);
}

// ---------------------------------------------------------------------------
// Alpha fix (MFVideoFormat_RGB32 leaves the alpha byte undefined).
// On x86/x64: runtime-dispatched AVX2 with scalar fallback.
// On ARM64 (and anywhere AVX2 intrinsics aren't available): scalar only.
// ---------------------------------------------------------------------------
#if NVP_HAS_AVX2_INTRINSICS
static bool DetectAvx2() {
    int info[4] = {};
    __cpuid(info, 0);
    if (info[0] < 7) return false;
    __cpuidex(info, 7, 0);
    return (info[1] & (1 << 5)) != 0; // EBX bit 5 = AVX2
}
#endif

static void ForceAlphaOpaque(BYTE* data, size_t pixelCount) {
    uint32_t* px = reinterpret_cast<uint32_t*>(data);
    size_t i = 0;

#if NVP_HAS_AVX2_INTRINSICS
    static const bool kHasAvx2 = DetectAvx2();
    if (kHasAvx2) {
        const __m256i mask = _mm256_set1_epi32(static_cast<int>(0xFF000000u));
        for (; i + 8 <= pixelCount; i += 8) {
            __m256i v = _mm256_loadu_si256(reinterpret_cast<__m256i*>(px + i));
            v = _mm256_or_si256(v, mask);
            _mm256_storeu_si256(reinterpret_cast<__m256i*>(px + i), v);
        }
    }
#endif

    for (; i < pixelCount; ++i) px[i] |= 0xFF000000u;
}

// ---------------------------------------------------------------------------
// URL helpers
// ---------------------------------------------------------------------------
static bool IsNetworkUrl(const wchar_t* url) {
    return _wcsnicmp(url, L"http://", 7) == 0 || _wcsnicmp(url, L"https://", 8) == 0;
}

static bool IsHLSUrl(const wchar_t* url) {
    if (!url) return false;
    std::wstring lower(url);
    for (auto& ch : lower) ch = static_cast<wchar_t>(towlower(ch));
    return lower.find(L".m3u8") != std::wstring::npos;
}

static std::wstring TrimHeaderPart(const std::wstring& value) {
    size_t first = 0;
    while (first < value.size() && iswspace(value[first])) ++first;

    size_t last = value.size();
    while (last > first && iswspace(value[last - 1])) --last;

    return value.substr(first, last - first);
}

static bool HeaderNameEquals(const std::wstring& left, const wchar_t* right) {
    return _wcsicmp(left.c_str(), right) == 0;
}

static std::wstring FindHeaderValue(const wchar_t* requestHeaders, const wchar_t* wantedName) {
    if (!requestHeaders || !requestHeaders[0]) return std::wstring();

    const wchar_t* lineStart = requestHeaders;
    while (*lineStart) {
        const wchar_t* lineEnd = wcschr(lineStart, L'\n');
        std::wstring line =
            lineEnd
                ? std::wstring(lineStart, static_cast<size_t>(lineEnd - lineStart))
                : std::wstring(lineStart);
        if (!line.empty() && line.back() == L'\r') {
            line.pop_back();
        }

        size_t separator = line.find(L':');
        if (separator != std::wstring::npos) {
            std::wstring name = TrimHeaderPart(line.substr(0, separator));
            if (HeaderNameEquals(name, wantedName)) {
                return TrimHeaderPart(line.substr(separator + 1));
            }
        }

        if (!lineEnd) break;
        lineStart = lineEnd + 1;
    }

    return std::wstring();
}

static HRESULT SetPropStoreString(IPropertyStore* store, REFGUID keyGuid, const std::wstring& value) {
    if (!store || value.empty()) return S_OK;

    PROPERTYKEY key = { keyGuid, 0 };
    PROPVARIANT variant;
    HRESULT hr = InitPropVariantFromString(value.c_str(), &variant);
    if (FAILED(hr)) return hr;

    hr = store->SetValue(key, variant);
    PropVariantClear(&variant);
    return hr;
}

static HRESULT ApplyRequestHeadersToSourceReaderAttributes(
    IMFAttributes* attrs,
    const wchar_t* requestHeaders
) {
    if (!attrs || !requestHeaders || !requestHeaders[0]) return S_OK;

    std::wstring userAgent = FindHeaderValue(requestHeaders, L"User-Agent");
    std::wstring referer = FindHeaderValue(requestHeaders, L"Referer");
    if (referer.empty()) {
        referer = FindHeaderValue(requestHeaders, L"Referrer");
    }

    if (userAgent.empty() && referer.empty()) return S_OK;

    ComPtr<IPropertyStore> sourceConfig;
    HRESULT hr = PSCreateMemoryPropertyStore(IID_PPV_ARGS(sourceConfig.GetAddressOf()));
    if (FAILED(hr)) return hr;

    hr = SetPropStoreString(sourceConfig.Get(), MFNETSOURCE_BROWSERUSERAGENT, userAgent);
    if (SUCCEEDED(hr)) {
        hr = SetPropStoreString(sourceConfig.Get(), MFNETSOURCE_PLAYERUSERAGENT, userAgent);
    }
    if (SUCCEEDED(hr)) {
        hr = SetPropStoreString(sourceConfig.Get(), MFNETSOURCE_BROWSERWEBPAGE, referer);
    }
    if (FAILED(hr)) return hr;

    return attrs->SetUnknown(MF_SOURCE_READER_MEDIASOURCE_CONFIG, sourceConfig.Get());
}

static void ApplyCookieHeaderToWinInet(const wchar_t* url, const wchar_t* requestHeaders) {
    if (!url || !requestHeaders || !requestHeaders[0]) return;

    const std::wstring cookieHeader = FindHeaderValue(requestHeaders, L"Cookie");
    if (cookieHeader.empty()) return;

    size_t start = 0;
    while (start < cookieHeader.size()) {
        const size_t separator = cookieHeader.find(L';', start);
        const size_t length =
            separator == std::wstring::npos ? std::wstring::npos : separator - start;
        const std::wstring cookie = TrimHeaderPart(cookieHeader.substr(start, length));
        if (!cookie.empty()) {
            InternetSetCookieExW(url, nullptr, cookie.c_str(), 0, 0);
        }
        if (separator == std::wstring::npos) break;
        start = separator + 1;
    }
}

static void ResetHdrMetadataReaderState(VideoPlayerInstance* inst) {
    if (!inst) return;
    inst->pHdrMetadataPendingSample.Reset();
    inst->llHdrMetadataPendingTimestamp = 0;
    inst->hdrMetadataLastPayload.clear();
    inst->llHdrMetadataLastTimestamp = (std::numeric_limits<LONGLONG>::min)();
}

static UINT32 HdrNalLengthSize(IMFMediaType* mediaType) {
    if (!mediaType) return 4;

    UINT32 sequenceHeaderSize = 0;
    if (FAILED(mediaType->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &sequenceHeaderSize)) ||
        sequenceHeaderSize < 22 || sequenceHeaderSize > 1024 * 1024) {
        return 4;
    }
    std::vector<BYTE> sequenceHeader(sequenceHeaderSize);
    UINT32 copied = 0;
    if (FAILED(mediaType->GetBlob(
            MF_MT_MPEG_SEQUENCE_HEADER,
            sequenceHeader.data(),
            sequenceHeaderSize,
            &copied)) ||
        copied < 22 || sequenceHeader[0] != 1) {
        return 4;
    }
    return (sequenceHeader[21] & 0x03u) + 1u;
}

static void UpdateHdrNalLengthSize(VideoPlayerInstance* inst, IMFMediaType* mediaType) {
    if (!inst || !mediaType) return;
    inst->hdrNalLengthSize = HdrNalLengthSize(mediaType);
}

static bool IsValidatedHdr10PlusPayload(const std::vector<uint8_t>& payload) {
    if (payload.empty()) return false;
    float sourcePeakNits = 0.0f;
    float curve[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT] = {};
    char error[256] = {};
    return kmp_hdr10_plus_parse_tone_curve(
               payload.data(),
               payload.size(),
               1000.0,
               &sourcePeakNits,
               curve,
               error,
               sizeof(error)) != 0;
}

/**
 * Reads a bounded number of compressed HEVC access units and promotes the
 * source only after both the SEI extractor and the complete ST 2094-40 parser
 * accept a payload. PQ signalling alone is deliberately insufficient.
 */
static bool ProbeValidatedHdr10PlusMetadata(
    IMFSourceReader* reader,
    IMFMediaType* selectedType
) {
    if (!reader || !selectedType) return false;
    GUID subtype = GUID_NULL;
    if (FAILED(selectedType->GetGUID(MF_MT_SUBTYPE, &subtype)) ||
        (subtype != MFVideoFormat_HEVC && subtype != MFVideoFormat_HEVC_ES)) {
        return false;
    }
    if (FAILED(reader->SetCurrentMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM,
            nullptr,
            selectedType))) {
        return false;
    }

    UINT32 nalLengthSize = HdrNalLengthSize(selectedType);
    for (int iteration = 0; iteration < kHdr10PlusProbeMaximumReadIterations; ++iteration) {
        DWORD streamIndex = 0;
        DWORD flags = 0;
        LONGLONG timestamp = 0;
        ComPtr<IMFSample> sample;
        const HRESULT hr = reader->ReadSample(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM,
            0,
            &streamIndex,
            &flags,
            &timestamp,
            sample.GetAddressOf());
        if (FAILED(hr) || (flags & MF_SOURCE_READERF_ENDOFSTREAM)) return false;
        if (flags & (MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED |
                     MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED)) {
            ComPtr<IMFMediaType> currentType;
            if (SUCCEEDED(reader->GetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                    currentType.GetAddressOf()))) {
                nalLengthSize = HdrNalLengthSize(currentType.Get());
            }
        }
        if (!sample) continue;

        ComPtr<IMFMediaBuffer> contiguousBuffer;
        if (FAILED(sample->ConvertToContiguousBuffer(contiguousBuffer.GetAddressOf()))) continue;
        BYTE* bytes = nullptr;
        DWORD currentLength = 0;
        if (FAILED(contiguousBuffer->Lock(&bytes, nullptr, &currentLength))) continue;
        std::vector<uint8_t> payload;
        const bool found = Hdr10PlusHevc::ExtractPayload(
            bytes,
            currentLength,
            static_cast<uint8_t>(nalLengthSize),
            payload);
        contiguousBuffer->Unlock();
        if (found && IsValidatedHdr10PlusPayload(payload)) return true;
    }
    return false;
}

/**
 * Opens a decoder-free reader over the same VOD source. It exposes compressed
 * HEVC access units so HDR10+ SEI can be associated with the decoded P010
 * sample by presentation timestamp. Failure is deliberately non-fatal: the
 * Kotlin planner will switch this source to the static HDR10 route after the
 * presenter reports metadata unavailable.
 */
static HRESULT OpenHdrMetadataReader(
    VideoPlayerInstance* inst,
    const wchar_t* url,
    const wchar_t* requestHeaders,
    bool isNetwork
) {
    if (!inst || !url || !inst->hdrPresenter ||
        !inst->hdrPresenter->RequiresHdr10PlusMetadata()) {
        return S_FALSE;
    }

    ComPtr<IMFAttributes> attributes;
    HRESULT hr = MFCreateAttributes(attributes.GetAddressOf(), 4);
    if (FAILED(hr)) return hr;
    attributes->SetUINT32(MF_READWRITE_DISABLE_CONVERTERS, TRUE);
    attributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, FALSE);
    if (isNetwork) {
        attributes->SetUINT32(MF_LOW_LATENCY, TRUE);
        hr = ApplyRequestHeadersToSourceReaderAttributes(attributes.Get(), requestHeaders);
        if (FAILED(hr)) return hr;
    }

    ComPtr<IMFSourceReader> reader;
    hr = MFCreateSourceReaderFromURL(url, attributes.Get(), reader.GetAddressOf());
    if (FAILED(hr)) return hr;
    hr = reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
    if (SUCCEEDED(hr)) {
        hr = reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);
    }
    if (FAILED(hr)) return hr;

    ComPtr<IMFMediaType> hevcType;
    for (DWORD typeIndex = 0; ; ++typeIndex) {
        ComPtr<IMFMediaType> candidate;
        hr = reader->GetNativeMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM,
            typeIndex,
            candidate.GetAddressOf());
        if (hr == MF_E_NO_MORE_TYPES) break;
        if (FAILED(hr)) return hr;
        GUID majorType = GUID_NULL;
        GUID subtype = GUID_NULL;
        if (SUCCEEDED(candidate->GetGUID(MF_MT_MAJOR_TYPE, &majorType)) &&
            SUCCEEDED(candidate->GetGUID(MF_MT_SUBTYPE, &subtype)) &&
            majorType == MFMediaType_Video &&
            (subtype == MFVideoFormat_HEVC || subtype == MFVideoFormat_HEVC_ES)) {
            hevcType = candidate;
            break;
        }
    }
    if (!hevcType) return MF_E_INVALIDMEDIATYPE;
    hr = reader->SetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM,
        nullptr,
        hevcType.Get());
    if (FAILED(hr)) return hr;

    inst->pHdrMetadataReader = reader;
    UpdateHdrNalLengthSize(inst, hevcType.Get());
    ResetHdrMetadataReaderState(inst);
    return S_OK;
}

static HRESULT ExtractHdr10PlusPayloadForTimestamp(
    VideoPlayerInstance* inst,
    LONGLONG targetTimestamp,
    std::vector<uint8_t>& payload
) {
    payload.clear();
    if (!inst || !inst->pHdrMetadataReader) {
        return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
    }
    if (inst->llHdrMetadataLastTimestamp == targetTimestamp &&
        !inst->hdrMetadataLastPayload.empty()) {
        payload.assign(
            inst->hdrMetadataLastPayload.begin(),
            inst->hdrMetadataLastPayload.end());
        return S_OK;
    }

    for (int iteration = 0; iteration < kHdrMetadataMaximumReadIterations; ++iteration) {
        ComPtr<IMFSample> compressedSample;
        LONGLONG compressedTimestamp = 0;
        if (inst->pHdrMetadataPendingSample) {
            compressedTimestamp = inst->llHdrMetadataPendingTimestamp;
            if (compressedTimestamp > targetTimestamp + kHdrMetadataTimestampTolerance) {
                return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
            }
            compressedSample = inst->pHdrMetadataPendingSample;
            inst->pHdrMetadataPendingSample.Reset();
            inst->llHdrMetadataPendingTimestamp = 0;
        } else {
            DWORD streamIndex = 0;
            DWORD flags = 0;
            HRESULT hr = inst->pHdrMetadataReader->ReadSample(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                0,
                &streamIndex,
                &flags,
                &compressedTimestamp,
                compressedSample.GetAddressOf());
            if (FAILED(hr)) return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
            if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
                return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
            }
            if (flags & (MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED |
                         MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED)) {
                ComPtr<IMFMediaType> currentType;
                if (SUCCEEDED(inst->pHdrMetadataReader->GetCurrentMediaType(
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                        currentType.GetAddressOf()))) {
                    UpdateHdrNalLengthSize(inst, currentType.Get());
                }
            }
            if (!compressedSample) continue;
        }

        LONGLONG sampleTime = 0;
        if (SUCCEEDED(compressedSample->GetSampleTime(&sampleTime))) {
            compressedTimestamp = sampleTime;
        }
        if (compressedTimestamp < targetTimestamp - kHdrMetadataTimestampTolerance) {
            continue;
        }
        if (compressedTimestamp > targetTimestamp + kHdrMetadataTimestampTolerance) {
            inst->pHdrMetadataPendingSample = compressedSample;
            inst->llHdrMetadataPendingTimestamp = compressedTimestamp;
            return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
        }

        ComPtr<IMFMediaBuffer> contiguousBuffer;
        if (FAILED(compressedSample->ConvertToContiguousBuffer(contiguousBuffer.GetAddressOf()))) {
            return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
        }
        BYTE* bytes = nullptr;
        DWORD currentLength = 0;
        if (FAILED(contiguousBuffer->Lock(&bytes, nullptr, &currentLength))) {
            return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
        }
        const bool found = Hdr10PlusHevc::ExtractPayload(
            bytes,
            currentLength,
            static_cast<uint8_t>(inst->hdrNalLengthSize),
            payload);
        contiguousBuffer->Unlock();
        if (!found) return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;

        inst->hdrMetadataLastPayload.assign(payload.begin(), payload.end());
        inst->llHdrMetadataLastTimestamp = targetTimestamp;
        return S_OK;
    }
    return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
}

// ---------------------------------------------------------------------------
// MediaType change handler — extracted to kill duplication.
// ---------------------------------------------------------------------------
static const GUID& RequestedVideoSubtype(const VideoPlayerInstance* inst) {
    if (!inst || !inst->bHdrOutputRequested) return MFVideoFormat_RGB32;
    return inst->hdrPresenter && !inst->hdrPresenter->RequiresP010Input()
        ? MFVideoFormat_NV12
        : MFVideoFormat_P010;
}

static int32_t DecodedBitDepth(IMFMediaType* mediaType) {
    GUID subtype = GUID_NULL;
    if (!mediaType || FAILED(mediaType->GetGUID(MF_MT_SUBTYPE, &subtype))) return 0;
    if (subtype == MFVideoFormat_P010) return 10;
    if (subtype == MFVideoFormat_P016) return 16;
    if (subtype == MFVideoFormat_NV12 || subtype == MFVideoFormat_RGB32) return 8;
    return 0;
}

static int32_t EncodedBitDepth(IMFMediaType* mediaType) {
    const int32_t decodedBitDepth = DecodedBitDepth(mediaType);
    if (decodedBitDepth > 0) return decodedBitDepth;

    GUID subtype = GUID_NULL;
    if (!mediaType ||
        FAILED(mediaType->GetGUID(MF_MT_SUBTYPE, &subtype))) {
        return 0;
    }

    UINT32 profile = 0;
    const bool hasProfile = SUCCEEDED(mediaType->GetUINT32(MF_MT_VIDEO_PROFILE, &profile));
    if (subtype == MFVideoFormat_H264 || subtype == MFVideoFormat_H264_ES) {
        // Media Foundation commonly omits MF_MT_VIDEO_PROFILE for H.264 files.
        // Its built-in H.264 decoder supports the ordinary 8-bit profiles below;
        // an omitted profile is therefore a safe 8-bit SDR fallback. Explicit
        // High10/4:2:2/4:4:4 profiles stay unknown so they cannot accidentally
        // bypass the managed colour pipeline.
        if (!hasProfile) return 8;
        switch (profile) {
        case eAVEncH264VProfile_Simple:
        case eAVEncH264VProfile_Main:
        case eAVEncH264VProfile_High:
        case eAVEncH264VProfile_Extended:
        case eAVEncH264VProfile_ScalableBase:
        case eAVEncH264VProfile_ScalableHigh:
        case eAVEncH264VProfile_MultiviewHigh:
        case eAVEncH264VProfile_StereoHigh:
        case eAVEncH264VProfile_ConstrainedBase:
        case eAVEncH264VProfile_UCConstrainedHigh:
        case eAVEncH264VProfile_UCScalableConstrainedBase:
        case eAVEncH264VProfile_UCScalableConstrainedHigh:
            return 8;
        default:
            return 0;
        }
    }

    if (subtype != MFVideoFormat_HEVC && subtype != MFVideoFormat_HEVC_ES) return 0;
    if (!hasProfile) return 0;

    // Values follow eAVEncH265VProfile. The source reader exposes the same
    // profile identifiers on compressed HEVC media types.
    switch (profile) {
    case 1:
    case 6:
    case 11:
    case 16:
    case 20:
    case 21:
        return 8;
    case 2:
    case 4:
    case 7:
    case 12:
    case 14:
    case 17:
        return 10;
    case 3:
    case 5:
    case 8:
    case 9:
    case 13:
    case 15:
    case 18:
        return 12;
    case 10:
    case 19:
    case 22:
        return 16;
    default:
        return 0;
    }
}

static int32_t DecodedPrimaries(IMFMediaType* mediaType) {
    UINT32 value = MFVideoPrimaries_Unknown;
    if (!mediaType || FAILED(mediaType->GetUINT32(MF_MT_VIDEO_PRIMARIES, &value))) return 0;
    switch (value) {
    case MFVideoPrimaries_BT470_2_SysM:
    case MFVideoPrimaries_SMPTE170M:
        return 1;
    case MFVideoPrimaries_BT470_2_SysBG:
        return 2;
    case MFVideoPrimaries_BT709:
        return 3;
    case MFVideoPrimaries_BT2020:
        return 4;
    case MFVideoPrimaries_DCI_P3:
        return 5;
    default:
        return 0;
    }
}

static int32_t DecodedTransfer(IMFMediaType* mediaType) {
    UINT32 value = MFVideoTransFunc_Unknown;
    if (!mediaType || FAILED(mediaType->GetUINT32(MF_MT_TRANSFER_FUNCTION, &value))) return 0;
    switch (value) {
    case MFVideoTransFunc_2084:
        return 4;
    case MFVideoTransFunc_HLG:
        return 5;
    case MFVideoTransFunc_sRGB:
        return 2;
    case MFVideoTransFunc_10:
        return 3;
    case MFVideoTransFunc_18:
    case MFVideoTransFunc_20:
    case MFVideoTransFunc_22:
    case MFVideoTransFunc_709:
    case MFVideoTransFunc_240M:
    case MFVideoTransFunc_28:
    case MFVideoTransFunc_709_sym:
    case MFVideoTransFunc_2020_const:
    case MFVideoTransFunc_2020:
    case MFVideoTransFunc_26:
        return 1;
    default:
        return 0;
    }
}

static int32_t EncodedTransfer(IMFMediaType* mediaType) {
    const int32_t declaredTransfer = DecodedTransfer(mediaType);
    if (declaredTransfer > 0) return declaredTransfer;

    GUID subtype = GUID_NULL;
    if (!mediaType || FAILED(mediaType->GetGUID(MF_MT_SUBTYPE, &subtype))) return 0;
    if ((subtype == MFVideoFormat_H264 || subtype == MFVideoFormat_H264_ES) &&
        EncodedBitDepth(mediaType) == 8) {
        // Match Media Foundation's playback convention for AVC with no VUI
        // transfer tag. Explicit PQ/HLG wins above, while High10/4:2:2/4:4:4
        // remains unknown because EncodedBitDepth deliberately rejects it.
        return 1;
    }
    return 0;
}

static int32_t DecodedMatrix(IMFMediaType* mediaType) {
    UINT32 value = MFVideoTransferMatrix_Unknown;
    if (!mediaType || FAILED(mediaType->GetUINT32(MF_MT_YUV_MATRIX, &value))) return 0;
    switch (value) {
    case 6: // MFVideoTransferMatrix_Identity (not named by older MinGW SDKs)
        return 1;
    case MFVideoTransferMatrix_BT601:
        return 2;
    case MFVideoTransferMatrix_BT709:
        return 3;
    case MFVideoTransferMatrix_BT2020_10:
    case MFVideoTransferMatrix_BT2020_12:
        return 4;
    case 11: // MFVideoTransferMatrix_Chroma_const
        return 5;
    case 12: // MFVideoTransferMatrix_ICtCp
        return 6;
    default:
        return 0;
    }
}

static int32_t DecodedRange(IMFMediaType* mediaType) {
    UINT32 value = MFNominalRange_Unknown;
    if (!mediaType || FAILED(mediaType->GetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, &value))) return 0;
    if (value == MFNominalRange_16_235 || value == MFNominalRange_Wide) return 1;
    if (value == MFNominalRange_0_255 || value == MFNominalRange_Normal) return 2;
    return 0;
}

static void UpdateDecodedVideoColorInfo(VideoPlayerInstance* inst, IMFMediaType* mediaType) {
    if (!inst || !mediaType) return;
    const int32_t bitDepth = DecodedBitDepth(mediaType);
    const int32_t primaries = DecodedPrimaries(mediaType);
    const int32_t transfer = DecodedTransfer(mediaType);
    const int32_t matrix = DecodedMatrix(mediaType);
    const int32_t range = DecodedRange(mediaType);
    const int32_t previousTransfer = inst->decodedTransfer.load(std::memory_order_relaxed);
    const int32_t authoritativeUnknowns =
        (previousTransfer > 0 && transfer == 0) ||
        (inst->decodedAuthoritativeUnknowns.load(std::memory_order_relaxed) != 0 && transfer == 0);
    if (inst->decodedBitDepth.load(std::memory_order_relaxed) == bitDepth &&
        inst->decodedPrimaries.load(std::memory_order_relaxed) == primaries &&
        inst->decodedTransfer.load(std::memory_order_relaxed) == transfer &&
        inst->decodedMatrix.load(std::memory_order_relaxed) == matrix &&
        inst->decodedRange.load(std::memory_order_relaxed) == range &&
        inst->decodedAuthoritativeUnknowns.load(std::memory_order_relaxed) == authoritativeUnknowns) {
        return;
    }
    inst->decodedBitDepth.store(bitDepth, std::memory_order_relaxed);
    inst->decodedPrimaries.store(primaries, std::memory_order_relaxed);
    inst->decodedTransfer.store(transfer, std::memory_order_relaxed);
    inst->decodedMatrix.store(matrix, std::memory_order_relaxed);
    inst->decodedRange.store(range, std::memory_order_relaxed);
    inst->decodedAuthoritativeUnknowns.store(authoritativeUnknowns, std::memory_order_relaxed);
    const int32_t previousGeneration =
        inst->decodedColorGeneration.load(std::memory_order_relaxed);
    const int32_t generation =
        previousGeneration == (std::numeric_limits<int32_t>::max)() ? 1 : previousGeneration + 1;
    inst->decodedColorGeneration.store(generation, std::memory_order_release);
}

NATIVEVIDEOPLAYER_API HRESULT ProbeVideoColorInfoWithHeaders(
    const wchar_t* url,
    const wchar_t* requestHeaders,
    int32_t outInfo[7]) {
    if (!url || !url[0] || !outInfo) return E_INVALIDARG;
    std::fill(outInfo, outInfo + 7, 0);

    const bool isNetwork = IsNetworkUrl(url);
    if (isNetwork) {
        ApplyCookieHeaderToWinInet(url, requestHeaders);
    }

    ComPtr<IMFAttributes> attributes;
    HRESULT hr = MFCreateAttributes(attributes.GetAddressOf(), 4);
    if (FAILED(hr)) return hr;
    hr = attributes->SetUINT32(MF_READWRITE_DISABLE_CONVERTERS, TRUE);
    if (SUCCEEDED(hr)) {
        hr = attributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, FALSE);
    }
    if (SUCCEEDED(hr) && isNetwork) {
        hr = attributes->SetUINT32(MF_LOW_LATENCY, TRUE);
    }
    if (SUCCEEDED(hr) && isNetwork) {
        hr = ApplyRequestHeadersToSourceReaderAttributes(attributes.Get(), requestHeaders);
    }
    if (FAILED(hr)) return hr;

    ComPtr<IMFSourceReader> reader;
    hr = MFCreateSourceReaderFromURL(url, attributes.Get(), reader.GetAddressOf());
    if (FAILED(hr)) return hr;
    hr = reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
    if (SUCCEEDED(hr)) {
        hr = reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);
    }
    if (FAILED(hr)) return hr;

    ComPtr<IMFMediaType> selectedType;
    for (DWORD typeIndex = 0; ; ++typeIndex) {
        ComPtr<IMFMediaType> candidate;
        hr = reader->GetNativeMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM,
            typeIndex,
            candidate.GetAddressOf());
        if (hr == MF_E_NO_MORE_TYPES) break;
        if (FAILED(hr)) return hr;

        GUID majorType = GUID_NULL;
        if (FAILED(candidate->GetGUID(MF_MT_MAJOR_TYPE, &majorType)) ||
            majorType != MFMediaType_Video) {
            continue;
        }
        if (!selectedType) selectedType = candidate;
        if (DecodedTransfer(candidate.Get()) > 0) {
            selectedType = candidate;
            break;
        }
    }
    if (!selectedType) return MF_E_INVALIDMEDIATYPE;

    outInfo[0] = 1;
    outInfo[1] = EncodedBitDepth(selectedType.Get());
    outInfo[2] = DecodedPrimaries(selectedType.Get());
    outInfo[3] = EncodedTransfer(selectedType.Get());
    outInfo[4] = DecodedMatrix(selectedType.Get());
    outInfo[5] = DecodedRange(selectedType.Get());
    outInfo[6] =
        ProbeValidatedHdr10PlusMetadata(reader.Get(), selectedType.Get())
            ? kDecodedColorFlagValidatedHdr10Plus
            : 0;
    return S_OK;
}

static void HandleMediaTypeChanges(VideoPlayerInstance* inst, DWORD flags) {
    if (flags & MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED) {
        ComPtr<IMFMediaType> nativeType;
        if (SUCCEEDED(inst->pSourceReader->GetNativeMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, nativeType.GetAddressOf()))) {
            UpdateDecodedVideoColorInfo(inst, nativeType.Get());
        }
        ComPtr<IMFMediaType> newType;
        if (SUCCEEDED(MFCreateMediaType(newType.GetAddressOf()))) {
            newType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            newType->SetGUID(MF_MT_SUBTYPE, RequestedVideoSubtype(inst));
            inst->pSourceReader->SetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, newType.Get());
        }
    }
    if (flags & (MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED |
                 MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED)) {
        ComPtr<IMFMediaType> current;
        if (SUCCEEDED(inst->pSourceReader->GetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, current.GetAddressOf()))) {
            UINT32 newW = 0, newH = 0;
            MFGetAttributeSize(current.Get(), MF_MT_FRAME_SIZE, &newW, &newH);
            if (newW > 0 && newH > 0) {
                inst->videoWidth  = newW;
                inst->videoHeight = newH;
            }
            UpdateDecodedVideoColorInfo(inst, current.Get());
        }
    }
}

// ---------------------------------------------------------------------------
// Copy a decoded frame into a caller-provided buffer.
// ---------------------------------------------------------------------------
static void CopyPlane(const BYTE* src, LONG srcPitch,
                      BYTE* dst, DWORD dstPitch,
                      DWORD rowBytes, UINT32 height) {
    if (static_cast<LONG>(dstPitch) == srcPitch && static_cast<LONG>(rowBytes) == srcPitch) {
        memcpy(dst, src, static_cast<size_t>(rowBytes) * height);
        return;
    }
    const DWORD copyBytes = (std::min)(rowBytes, dstPitch);
    for (UINT32 y = 0; y < height; ++y) {
        memcpy(dst, src, copyBytes);
        src += srcPitch;
        dst += dstPitch;
    }
}

// ---------------------------------------------------------------------------
// HLS fallback for network URLs
// ---------------------------------------------------------------------------
static HRESULT OpenMediaHLS(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback) {
    // HLSPlayer starts at refcount 1 — Attach takes ownership without AddRef.
    ComPtr<HLSPlayer> hls;
    hls.Attach(new (std::nothrow) HLSPlayer());
    if (!hls) return E_OUTOFMEMORY;

    HRESULT hr = hls->Initialize(GetD3DDevice(), GetDXGIDeviceManager());
    if (SUCCEEDED(hr)) hr = hls->Open(url);
    if (FAILED(hr)) return hr; // ComPtr releases on scope exit.

    pInstance->pHLSPlayer       = hls;
    pInstance->bIsNetworkSource = true;

    hls->GetVideoSize(&pInstance->videoWidth, &pInstance->videoHeight);
    pInstance->nativeWidth  = pInstance->videoWidth;
    pInstance->nativeHeight = pInstance->videoHeight;

    LONGLONG duration = 0;
    hls->GetDuration(&duration);
    pInstance->bIsLiveStream = (duration == 0);
    pInstance->bHasAudio = true;

    if (startPlayback) {
        hls->SetPlaying(TRUE);
        pInstance->llPlaybackStartTime.store(GetCurrentTimeMs(), std::memory_order_relaxed);
        pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
        pInstance->llPauseStart.store(0, std::memory_order_relaxed);
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
// Audio format configuration
// ---------------------------------------------------------------------------
static HRESULT ConfigureAudioType(IMFMediaType* pType, UINT32 channels, UINT32 sampleRate) {
    if (channels == 0)   channels = 2;
    if (sampleRate == 0) sampleRate = 48000;

    const UINT32 bitsPerSample  = 16;
    const UINT32 blockAlign     = channels * (bitsPerSample / 8);
    const UINT32 avgBytesPerSec = sampleRate * blockAlign;

    pType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    pType->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
    pType->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
    pType->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, sampleRate);
    pType->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, blockAlign);
    pType->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, avgBytesPerSec);
    pType->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, bitsPerSample);
    return S_OK;
}

static void QueryNativeAudioParams(IMFSourceReader* reader, UINT32* channels, UINT32* sampleRate) {
    *channels = 0;
    *sampleRate = 0;
    if (!reader) return;

    ComPtr<IMFMediaType> nativeType;
    if (SUCCEEDED(reader->GetNativeMediaType(
            MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, nativeType.GetAddressOf()))) {
        nativeType->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
        nativeType->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, sampleRate);
    }
}

// ---------------------------------------------------------------------------
// Compute the presentation reference time used to decide whether a decoded
// frame should be displayed now, skipped, or cached for later.
// ---------------------------------------------------------------------------
static double ComputeReferenceMs(const VideoPlayerInstance* inst) {
    if (inst->bHasAudio) {
        const double audioFedMs = inst->llCurrentPosition.load(std::memory_order_relaxed) / 10000.0;
        const double latencyMs  = inst->audioLatencyMs.load(std::memory_order_relaxed);
        return audioFedMs - latencyMs;
    }
    const LONGLONG now = static_cast<LONGLONG>(GetCurrentTimeMs());
    const LONGLONG start = static_cast<LONGLONG>(inst->llPlaybackStartTime.load(std::memory_order_relaxed));
    const LONGLONG pauseTotal = static_cast<LONGLONG>(inst->llTotalPauseTime.load(std::memory_order_relaxed));
    return static_cast<double>(now - start - pauseTotal) * inst->playbackSpeed.load(std::memory_order_relaxed);
}

static void RecordPresentedVideoSample(VideoPlayerInstance* inst, IMFSample* sample) {
    if (!inst || !sample) return;
    inst->renderedVideoFrames.fetch_add(1, std::memory_order_relaxed);
    if (!inst->bHasAudio) return;

    LONGLONG sampleTimestamp = 0;
    if (FAILED(sample->GetSampleTime(&sampleTimestamp)) || sampleTimestamp < 0) return;
    const double referenceMs = ComputeReferenceMs(inst);
    const double sampleTimestampMs = sampleTimestamp / 10000.0;
    const double offsetMs = std::abs(sampleTimestampMs - referenceMs);
    const int64_t offsetMicros =
        static_cast<int64_t>(std::llround(offsetMs * 1000.0));
    int64_t previous =
        inst->maximumAvSyncOffsetMicros.load(std::memory_order_relaxed);
    bool updated = false;
    while (offsetMicros > previous &&
           !inst->maximumAvSyncOffsetMicros.compare_exchange_weak(
               previous,
               offsetMicros,
               std::memory_order_relaxed,
               std::memory_order_relaxed)) {
    }
    if (offsetMicros > previous) {
        updated = true;
    }
    if (updated && offsetMs > 45.0) {
        ComposeMediaPlayer::NativeLogging::Logf(
            "[A/V] sample=%.3fms reference=%.3fms offset=%.3fms "
            "audioSample=%.3fms audioPadding=%.3fms rendered=%lld\n",
            sampleTimestampMs,
            referenceMs,
            offsetMs,
            inst->llCurrentPosition.load(std::memory_order_relaxed) / 10000.0,
            inst->audioLatencyMs.load(std::memory_order_relaxed),
            static_cast<long long>(
                inst->renderedVideoFrames.load(std::memory_order_relaxed)));
    }
}

// ---------------------------------------------------------------------------
// Read the next video frame. Returns a sample ready to be displayed or
// nullptr when the frame is not yet due (caller should try again later).
// No blocking sleeps: early frames are cached to avoid stalling the JNI
// render thread.
// ---------------------------------------------------------------------------
static HRESULT AcquireNextSample(VideoPlayerInstance* inst, IMFSample** ppOut) {
    *ppOut = nullptr;

    const bool isPaused = (inst->llPauseStart.load(std::memory_order_relaxed) != 0);
    ComPtr<IMFSample> sample;

    UINT frNum = kDefaultFrameRateNum, frDenom = kDefaultFrameRateDenom;
    GetVideoFrameRate(inst, &frNum, &frDenom);
    if (frNum == 0) { frNum = kDefaultFrameRateNum; frDenom = kDefaultFrameRateDenom; }
    const double frameIntervalMs = 1000.0 * frDenom / frNum;
    const double lateThresholdMs = -frameIntervalMs * kFrameSkipThreshold;

    // 1) Cached-sample path: a previously-read frame that was "too early".
    if (inst->pCachedSample) {
        if (isPaused) {
            inst->pCachedSample.CopyTo(sample.GetAddressOf());
        } else {
            const double frameTimeMs = inst->llCachedTimestamp / 10000.0;
            const double refMs = ComputeReferenceMs(inst);
            const ULONGLONG nowMs = GetCurrentTimeMs();
            const ULONGLONG insertedAt = inst->llCachedInsertedAtMs;
            // Guard against clock skew / reinit: nowMs < insertedAt would
            // wrap to a huge ULONGLONG and force-deliver a stale frame.
            const ULONGLONG heldMs = (insertedAt != 0 && nowMs >= insertedAt)
                ? (nowMs - insertedAt) : 0;
            // Deliver if due, OR if the sample has been sitting too long —
            // avoids an indefinite freeze when the audio clock stalls or
            // drifts (which would otherwise leave refMs permanently behind).
            if (frameTimeMs - refMs > kFrameAheadMinMs && heldMs < 300) {
                return S_OK; // still too early, wait
            }
            sample = std::move(inst->pCachedSample);
            inst->pCachedSample.Reset();
            inst->llCachedInsertedAtMs = 0;
        }
    }

    // 2) Fresh-read path: drop anything late, return the first in-window
    //    frame (or cache the first too-early one). We never hand a late
    //    sample to the caller — stale frames are pure waste, the picture
    //    should jump to "now", not replay what was missed.
    if (!sample) {
        constexpr int kMaxReadIterations = 64;
        constexpr ULONGLONG kMaxReadBudgetMs = 25;
        const ULONGLONG budgetStart = GetCurrentTimeMs();

        for (int iter = 0; iter < kMaxReadIterations; ++iter) {
            DWORD streamIndex = 0, flags = 0;
            LONGLONG sampleTs = 0;
            ComPtr<IMFSample> s;
            HRESULT hr = inst->pSourceReader->ReadSample(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                &streamIndex, &flags, &sampleTs, s.GetAddressOf());
            if (FAILED(hr)) return hr;

            // A synchronous SourceReader can report a terminal stream error
            // through the flag while returning a null sample. Treat it as a
            // failure immediately; otherwise the render loop retries forever
            // and turns the real decoder error into a misleading timeout.
            if (flags & MF_SOURCE_READERF_ERROR) {
                ComposeMediaPlayer::NativeLogging::Logf(
                    "[Video] SourceReader signaled a stream error "
                    "(stream=%lu, flags=0x%08lx, timestamp=%lld).\n",
                    static_cast<unsigned long>(streamIndex),
                    static_cast<unsigned long>(flags),
                    static_cast<long long>(sampleTs));
                return E_FAIL;
            }

            if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
                inst->bEOF.store(true);
                return S_FALSE;
            }

            HandleMediaTypeChanges(inst, flags);

            if (!s) {
                const uint32_t emptyReads =
                    inst->consecutiveEmptyVideoReads.fetch_add(1, std::memory_order_relaxed) + 1;
                if (emptyReads <= 3 || emptyReads % 5000 == 0) {
                    ComposeMediaPlayer::NativeLogging::Logf(
                        "[Video] SourceReader returned no sample "
                        "(count=%u, stream=%lu, flags=0x%08lx, timestamp=%lld).\n",
                        emptyReads,
                        static_cast<unsigned long>(streamIndex),
                        static_cast<unsigned long>(flags),
                        static_cast<long long>(sampleTs));
                }
                // Decoder starved — yield back to the caller.
                return S_OK;
            }

            inst->consecutiveEmptyVideoReads.store(0, std::memory_order_relaxed);

            // Paused path: cache the first frame for initial display.
            inst->totalVideoFrames.fetch_add(1, std::memory_order_relaxed);

            if (isPaused) {
                if (!inst->bHasInitialFrame) {
                    s.CopyTo(inst->pCachedSample.ReleaseAndGetAddressOf());
                    inst->llCachedTimestamp = sampleTs;
                    inst->llCachedInsertedAtMs = GetCurrentTimeMs();
                    inst->bHasInitialFrame = true;
                }
                sample = std::move(s);
                break;
            }

            inst->bHasInitialFrame = true;
            if (!inst->bHasAudio) {
                inst->llCurrentPosition.store(sampleTs, std::memory_order_relaxed);
            }

            // Zero is a valid timestamp for the first frame and must still go
            // through A/V scheduling. A negative value has no usable PTS.
            if (sampleTs < 0) {
                sample = std::move(s);
                break;
            }

            const double frameTimeMs = sampleTs / 10000.0;
            const double refMs = ComputeReferenceMs(inst);
            const double diffMs = frameTimeMs - refMs;

            if (diffMs < lateThresholdMs) {
                inst->droppedVideoFrames.fetch_add(1, std::memory_order_relaxed);
                // Stale — discard and keep reading. Do not cache, do not
                // deliver: we want to display what's happening NOW, not
                // replay pre-seek keyframes or frames skipped during a
                // UI stall.
                if (iter >= 3 && GetCurrentTimeMs() - budgetStart > kMaxReadBudgetMs) {
                    // Budget exhausted; yield so the caller can do something
                    // else. Next call resumes draining from here.
                    return S_OK;
                }
                continue;
            }

            if (diffMs > frameIntervalMs) {
                // Too early — cache so the next call on the normal render
                // cadence can deliver it.
                s.CopyTo(inst->pCachedSample.ReleaseAndGetAddressOf());
                inst->llCachedTimestamp = sampleTs;
                inst->llCachedInsertedAtMs = GetCurrentTimeMs();
                return S_OK;
            }

            // In display window — deliver.
            sample = std::move(s);
            break;
        }
    }

    if (!sample) return S_OK;
    sample.CopyTo(ppOut);
    return S_OK;
}

// ====================================================================
// Exported API
// ====================================================================

NATIVEVIDEOPLAYER_API int GetNativeVersion() { return NATIVE_VIDEO_PLAYER_VERSION; }

NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation() { return Initialize(); }

NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance) {
    if (!ppInstance) return E_INVALIDARG;

    if (!IsInitialized()) {
        HRESULT hr = Initialize();
        if (FAILED(hr)) return hr;
    }

    auto inst = std::unique_ptr<VideoPlayerInstance>(new (std::nothrow) VideoPlayerInstance());
    if (!inst) return E_OUTOFMEMORY;

    inst->bUseClockSync = true;
    IncrementInstanceCount();
    *ppInstance = inst.release();
    return S_OK;
}

NATIVEVIDEOPLAYER_API void DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance) {
    if (!pInstance) return;
    delete pInstance; // dtor calls CloseMedia
    DecrementInstanceCount();
}

NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback) {
    return OpenMediaWithHeaders(pInstance, url, nullptr, startPlayback);
}

NATIVEVIDEOPLAYER_API HRESULT OpenMediaWithHeaders(
    VideoPlayerInstance* pInstance,
    const wchar_t* url,
    const wchar_t* requestHeaders,
    BOOL startPlayback
) {
    if (!pInstance || !url) return OP_E_INVALID_PARAMETER;
    if (!IsInitialized()) return OP_E_NOT_INITIALIZED;

    CloseMedia(pInstance);
    pInstance->decodedBitDepth.store(0, std::memory_order_relaxed);
    pInstance->decodedPrimaries.store(0, std::memory_order_relaxed);
    pInstance->decodedTransfer.store(0, std::memory_order_relaxed);
    pInstance->decodedMatrix.store(0, std::memory_order_relaxed);
    pInstance->decodedRange.store(0, std::memory_order_relaxed);
    pInstance->decodedAuthoritativeUnknowns.store(0, std::memory_order_relaxed);
    pInstance->consecutiveEmptyVideoReads.store(0, std::memory_order_relaxed);
    const int32_t previousGeneration =
        pInstance->decodedColorGeneration.load(std::memory_order_relaxed);
    const int32_t resetGeneration =
        previousGeneration == (std::numeric_limits<int32_t>::max)() ? 1 : previousGeneration + 1;
    pInstance->decodedColorGeneration.store(resetGeneration, std::memory_order_release);
    pInstance->bEOF.store(false);
    pInstance->videoWidth = pInstance->videoHeight = 0;
    pInstance->bHasAudio = false;
    pInstance->bHasInitialFrame = false;
    pInstance->pCachedSample.Reset();

    const bool isNetwork = IsNetworkUrl(url);
    pInstance->bIsNetworkSource = isNetwork;
    pInstance->bIsLiveStream = false;

    if (isNetwork) {
        ApplyCookieHeaderToWinInet(url, requestHeaders);
    }

    if (isNetwork && IsHLSUrl(url) && pInstance->bHdrOutputRequested)
        return MF_E_UNSUPPORTED_BYTESTREAM_TYPE;

    if (isNetwork && IsHLSUrl(url))
        return OpenMediaHLS(pInstance, url, startPlayback);

    // ---- Configure and open source reader ----
    ComPtr<IMFAttributes> attrs;
    HRESULT hr = MFCreateAttributes(attrs.GetAddressOf(), 7);
    if (FAILED(hr)) return hr;

    hr = attrs->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE);
    if (SUCCEEDED(hr)) hr = attrs->SetUINT32(MF_SOURCE_READER_DISABLE_DXVA, FALSE);
    IMFDXGIDeviceManager* dxgiManager = GetDXGIDeviceManager();
    if (SUCCEEDED(hr) && !dxgiManager) hr = MF_E_NOT_INITIALIZED;
    if (SUCCEEDED(hr)) hr = attrs->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, dxgiManager);
    if (SUCCEEDED(hr) && !pInstance->bHdrOutputRequested) {
        // The Compose/BGRA route needs Media Foundation's video processor.
        // The controlled color renderer instead requests P010 for HDR or NV12
        // for SDR directly from the decoder and owns all transfer, gamut and
        // tone-mapping work. Inserting XVP into that route can consume HLG input
        // without ever producing a sample for the custom presenter.
        hr = attrs->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE);
    }
    if (SUCCEEDED(hr) && isNetwork) hr = attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
    if (FAILED(hr)) return hr;
    if (isNetwork) {
        hr = ApplyRequestHeadersToSourceReaderAttributes(attrs.Get(), requestHeaders);
        if (FAILED(hr)) return hr;
    }

    hr = MFCreateSourceReaderFromURL(url, attrs.Get(), pInstance->pSourceReader.ReleaseAndGetAddressOf());
    if (FAILED(hr)) {
        if (isNetwork && hr == MF_E_UNSUPPORTED_BYTESTREAM_TYPE)
            return OpenMediaHLS(pInstance, url, startPlayback);
        return hr;
    }

    // ---- Video stream: P010 HDR/NV12 SDR on the controlled texture route, RGB32 otherwise ----
    hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
    if (SUCCEEDED(hr))
        hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);
    if (FAILED(hr)) return hr;

    {
        ComPtr<IMFMediaType> type;
        hr = MFCreateMediaType(type.GetAddressOf());
        if (SUCCEEDED(hr)) {
            type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            type->SetGUID(MF_MT_SUBTYPE, RequestedVideoSubtype(pInstance));
            hr = pInstance->pSourceReader->SetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type.Get());
        }
        if (FAILED(hr)) return hr;
    }

    {
        ComPtr<IMFMediaType> current;
        if (SUCCEEDED(pInstance->pSourceReader->GetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, current.GetAddressOf()))) {
            MFGetAttributeSize(current.Get(), MF_MT_FRAME_SIZE,
                               &pInstance->videoWidth, &pInstance->videoHeight);
            pInstance->nativeWidth  = pInstance->videoWidth;
            pInstance->nativeHeight = pInstance->videoHeight;
            UpdateDecodedVideoColorInfo(pInstance, current.Get());
        }
    }

    if (pInstance->hdrPresenter && pInstance->hdrPresenter->RequiresHdr10PlusMetadata()) {
        const HRESULT metadataHr = OpenHdrMetadataReader(
            pInstance,
            url,
            requestHeaders,
            isNetwork);
        if (FAILED(metadataHr)) {
            PrintHR("HDR10+ compressed metadata reader unavailable", metadataHr);
            pInstance->pHdrMetadataReader.Reset();
            ResetHdrMetadataReaderState(pInstance);
        }
    }

    // ---- Audio stream (best effort) ----
    if (SUCCEEDED(pInstance->pSourceReader->SetStreamSelection(
            MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE))) {

        UINT32 nativeCh = 0, nativeSr = 0;
        QueryNativeAudioParams(pInstance->pSourceReader.Get(), &nativeCh, &nativeSr);
        // ConfigureAudioType normalizes 0/0 to 2/48000 internally, so do the
        // same here to avoid issuing a redundant fallback attempt with the
        // exact same parameters.
        if (nativeCh == 0)   nativeCh = 2;
        if (nativeSr == 0)   nativeSr = 48000;

        auto tryAudioMediaType = [&](IMFMediaType* wanted) -> bool {
            if (!wanted) return false;
            if (FAILED(pInstance->pSourceReader->SetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, wanted))) return false;

            ComPtr<IMFMediaType> actual;
            if (FAILED(pInstance->pSourceReader->GetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_AUDIO_STREAM, actual.GetAddressOf())) || !actual)
                return false;

            WAVEFORMATEX* pWfx = nullptr;
            UINT32 size = 0;
            if (FAILED(MFCreateWaveFormatExFromMFMediaType(actual.Get(), &pWfx, &size)) || !pWfx)
                return false;

            HRESULT hrInit = InitWASAPI(pInstance, pWfx);
            if (FAILED(hrInit)) {
                PrintHR("InitWASAPI failed", hrInit);
                CoTaskMemFree(pWfx);
                return false;
            }
            // Transfer ownership of pWfx to the instance — InitWASAPI does
            // not copy it.
            if (pInstance->pSourceAudioFormat) CoTaskMemFree(pInstance->pSourceAudioFormat);
            pInstance->pSourceAudioFormat = pWfx;
            pInstance->bHasAudio = true;
            return true;
        };

        auto tryAudioFormat = [&](UINT32 ch, UINT32 sr) -> bool {
            ComPtr<IMFMediaType> wanted;
            if (FAILED(MFCreateMediaType(wanted.GetAddressOf()))) return false;
            ConfigureAudioType(wanted.Get(), ch, sr);
            return tryAudioMediaType(wanted.Get());
        };

        bool audioConfigured = false;
        WAVEFORMATEX* mixFormat = nullptr;
        if (SUCCEEDED(GetDefaultAudioMixFormat(&mixFormat)) && mixFormat) {
            ComPtr<IMFMediaType> wanted;
            const UINT32 mixFormatSize =
                static_cast<UINT32>(sizeof(WAVEFORMATEX) + mixFormat->cbSize);
            if (SUCCEEDED(MFCreateMediaType(wanted.GetAddressOf())) &&
                SUCCEEDED(MFInitMediaTypeFromWaveFormatEx(
                    wanted.Get(), mixFormat, mixFormatSize))) {
                audioConfigured = tryAudioMediaType(wanted.Get());
            }
            CoTaskMemFree(mixFormat);
        }

        if (!audioConfigured) {
            audioConfigured = tryAudioFormat(nativeCh, nativeSr);
        }
        if (!audioConfigured) {
            // Only retry with the canonical fallback if the first attempt
            // actually differed from it.
            if (nativeCh != 2 || nativeSr != 48000) {
                audioConfigured = tryAudioFormat(2, 48000);
            }
        }

        // Dedicated audio SourceReader so the audio thread is never blocked
        // by the video decoding path (ReadSample serializes within a reader).
        if (pInstance->bHasAudio) {
            ComPtr<IMFAttributes> audioAttrs;
            if (SUCCEEDED(MFCreateAttributes(audioAttrs.GetAddressOf(), 2))) {
                if (isNetwork) audioAttrs->SetUINT32(MF_LOW_LATENCY, TRUE);
                HRESULT hrA = MFCreateSourceReaderFromURL(
                    url, audioAttrs.Get(), pInstance->pSourceReaderAudio.ReleaseAndGetAddressOf());
                if (SUCCEEDED(hrA) && pInstance->pSourceReaderAudio) {
                    HRESULT configureReaderHr =
                        pInstance->pSourceReaderAudio->SetStreamSelection(
                            MF_SOURCE_READER_ALL_STREAMS,
                            FALSE);
                    if (SUCCEEDED(configureReaderHr)) {
                        configureReaderHr =
                            pInstance->pSourceReaderAudio->SetStreamSelection(
                                MF_SOURCE_READER_FIRST_AUDIO_STREAM,
                                TRUE);
                    }
                    ComPtr<IMFMediaType> wanted;
                    if (SUCCEEDED(configureReaderHr)) {
                        configureReaderHr = MFCreateMediaType(wanted.GetAddressOf());
                    }
                    if (SUCCEEDED(configureReaderHr)) {
                        const UINT32 sourceFormatSize =
                            static_cast<UINT32>(
                                sizeof(WAVEFORMATEX) +
                                pInstance->pSourceAudioFormat->cbSize);
                        configureReaderHr = MFInitMediaTypeFromWaveFormatEx(
                            wanted.Get(),
                            pInstance->pSourceAudioFormat,
                            sourceFormatSize);
                    }
                    if (SUCCEEDED(configureReaderHr)) {
                        configureReaderHr =
                            pInstance->pSourceReaderAudio->SetCurrentMediaType(
                                MF_SOURCE_READER_FIRST_AUDIO_STREAM,
                                nullptr,
                                wanted.Get());
                    }
                    if (FAILED(configureReaderHr)) {
                        PrintHR("Failed to configure dedicated audio source reader", configureReaderHr);
                        pInstance->pSourceReaderAudio.Reset();
                    }
                } else {
                    PrintHR("Failed to create audio source reader", hrA);
                }
            }
        }
    }

    // ---- Presentation clock ----
    if (pInstance->bUseClockSync) {
        if (SUCCEEDED(pInstance->pSourceReader->GetServiceForStream(
                MF_SOURCE_READER_MEDIASOURCE, GUID_NULL,
                IID_PPV_ARGS(pInstance->pMediaSource.ReleaseAndGetAddressOf())))) {

            if (SUCCEEDED(MFCreatePresentationClock(pInstance->pPresentationClock.ReleaseAndGetAddressOf()))) {
                ComPtr<IMFPresentationTimeSource> timeSource;
                if (SUCCEEDED(MFCreateSystemTimeSource(timeSource.GetAddressOf()))) {
                    pInstance->pPresentationClock->SetTimeSource(timeSource.Get());

                    ComPtr<IMFRateControl> rateControl;
                    if (SUCCEEDED(pInstance->pPresentationClock.As(&rateControl)))
                        rateControl->SetRate(FALSE, 1.0f);

                    if (startPlayback) pInstance->pPresentationClock->Start(0);
                    else               pInstance->pPresentationClock->Pause();
                }
            }
        }
    }

    // ---- Timing init + audio thread start ----
    if (startPlayback) {
        pInstance->llPlaybackStartTime.store(GetCurrentTimeMs(), std::memory_order_relaxed);
        pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
        pInstance->llPauseStart.store(0, std::memory_order_relaxed);

        if (pInstance->bHasAudio && pInstance->bAudioInitialized) {
            PreFillAudioBuffer(pInstance);
            if (pInstance->pSourceReaderAudio) StartAudioThread(pInstance);
        }
    } else if (pInstance->bHasAudio && pInstance->bAudioInitialized && pInstance->pSourceReaderAudio) {
        // Start the thread but leave it suspended until SetPlaybackState(TRUE).
        StartAudioThread(pInstance);
        SignalPause(pInstance);
    }

    return S_OK;
}

// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize) {
    if (!pInstance || !pData || !pDataSize) return OP_E_NOT_INITIALIZED;

    // Controlled P010/NV12 texture frames must never fall through to the JVM BGRA canvas.
    if (pInstance->bHdrOutputRequested) return MF_E_INVALIDREQUEST;

    if (pInstance->pHLSPlayer) {
        const HRESULT hlsHr = pInstance->pHLSPlayer->ReadFrame(pData, pDataSize);
        if (SUCCEEDED(hlsHr) && *pData != nullptr && *pDataSize > 0) {
            pInstance->totalVideoFrames.fetch_add(1, std::memory_order_relaxed);
            pInstance->renderedVideoFrames.fetch_add(1, std::memory_order_relaxed);
        }
        return hlsHr;
    }

    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;
    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);

    if (pInstance->bEOF.load()) { *pData = nullptr; *pDataSize = 0; return S_FALSE; }

    ComPtr<IMFSample> sample;
    HRESULT hr = AcquireNextSample(pInstance, sample.GetAddressOf());
    if (hr == S_FALSE) { *pData = nullptr; *pDataSize = 0; return S_FALSE; }
    if (FAILED(hr)) return hr;
    if (!sample)    { *pData = nullptr; *pDataSize = 0; return S_OK; }

    ComPtr<IMFMediaBuffer> buffer;
    DWORD bufferCount = 0;
    if (SUCCEEDED(sample->GetBufferCount(&bufferCount)) && bufferCount == 1) {
        hr = sample->GetBufferByIndex(0, buffer.GetAddressOf());
    } else {
        hr = sample->ConvertToContiguousBuffer(buffer.GetAddressOf());
    }
    if (FAILED(hr)) { PrintHR("GetBuffer failed", hr); return hr; }

    BYTE* bytes = nullptr;
    DWORD maxSz = 0, curSz = 0;
    hr = buffer->Lock(&bytes, &maxSz, &curSz);
    if (FAILED(hr)) { PrintHR("Lock failed", hr); return hr; }

    ForceAlphaOpaque(bytes, curSz / 4);

    pInstance->pLockedBuffer   = buffer;
    pInstance->pLockedBytes    = bytes;
    pInstance->lockedMaxSize   = maxSz;
    pInstance->lockedCurrSize  = curSz;
    *pData = bytes;
    *pDataSize = curSz;
    RecordPresentedVideoSample(pInstance, sample.Get());
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance) {
    if (!pInstance) return E_INVALIDARG;
    if (pInstance->pHLSPlayer) { pInstance->pHLSPlayer->UnlockFrame(); return S_OK; }

    if (pInstance->pLockedBuffer) {
        pInstance->pLockedBuffer->Unlock();
        pInstance->pLockedBuffer.Reset();
    }
    pInstance->pLockedBytes = nullptr;
    pInstance->lockedMaxSize = pInstance->lockedCurrSize = 0;
    return S_OK;
}

// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(VideoPlayerInstance* pInstance,
                                                  BYTE* pDst, DWORD dstRowBytes, DWORD dstCapacity,
                                                  LONGLONG* pTimestamp) {
    if (!pInstance || !pDst || dstRowBytes == 0 || dstCapacity == 0)
        return OP_E_INVALID_PARAMETER;
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;
    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);

    if (pInstance->bEOF.load()) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
        return S_FALSE;
    }

    ComPtr<IMFSample> sample;
    HRESULT hr = AcquireNextSample(pInstance, sample.GetAddressOf());
    if (hr == S_FALSE) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
        return S_FALSE;
    }
    if (FAILED(hr)) return hr;
    if (!sample) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
        return S_OK;
    }

    if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);

    const UINT32 width  = pInstance->videoWidth;
    const UINT32 height = pInstance->videoHeight;
    if (width == 0 || height == 0) return S_FALSE;

    const DWORD requiredDst = dstRowBytes * height;
    if (dstCapacity < requiredDst) return OP_E_INVALID_PARAMETER;

    ComPtr<IMFMediaBuffer> buffer;
    hr = sample->ConvertToContiguousBuffer(buffer.GetAddressOf());
    if (FAILED(hr)) return hr;

    const DWORD srcRowBytes = width * 4;
    bool copied = false;

    // Preferred path: IMF2DBuffer2.
    {
        ComPtr<IMF2DBuffer2> b2;
        if (SUCCEEDED(buffer.As(&b2))) {
            BYTE* scan0 = nullptr;
            LONG  pitch = 0;
            BYTE* bufStart = nullptr;
            DWORD cbLen = 0;
            if (SUCCEEDED(b2->Lock2DSize(MF2DBuffer_LockFlags_Read, &scan0, &pitch, &bufStart, &cbLen))) {
                CopyPlane(scan0, pitch, pDst, dstRowBytes, srcRowBytes, height);
                b2->Unlock2D();
                copied = true;
            }
        }
    }

    // Fallback: IMF2DBuffer.
    if (!copied) {
        ComPtr<IMF2DBuffer> b2;
        if (SUCCEEDED(buffer.As(&b2))) {
            BYTE* scan0 = nullptr;
            LONG  pitch = 0;
            if (SUCCEEDED(b2->Lock2D(&scan0, &pitch))) {
                CopyPlane(scan0, pitch, pDst, dstRowBytes, srcRowBytes, height);
                b2->Unlock2D();
                copied = true;
            }
        }
    }

    // Final fallback: linear Lock.
    if (!copied) {
        BYTE* bytes = nullptr;
        DWORD maxSz = 0, curSz = 0;
        if (SUCCEEDED(buffer->Lock(&bytes, &maxSz, &curSz))) {
            if (curSz >= srcRowBytes * height)
                MFCopyImage(pDst, dstRowBytes, bytes, srcRowBytes, srcRowBytes, height);
            buffer->Unlock();
        }
    }

    ForceAlphaOpaque(pDst, (dstRowBytes * height) / 4);
    RecordPresentedVideoSample(pInstance, sample.Get());
    return S_OK;
}

NATIVEVIDEOPLAYER_API BOOL IsEOF(const VideoPlayerInstance* pInstance) {
    if (!pInstance) return FALSE;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->IsEOF();
    return pInstance->bEOF.load();
}

NATIVEVIDEOPLAYER_API void GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight) {
    if (!pInstance) return;
    if (pInstance->pHLSPlayer) { pInstance->pHLSPlayer->GetVideoSize(pWidth, pHeight); return; }
    if (pWidth)  *pWidth  = pInstance->videoWidth;
    if (pHeight) *pHeight = pInstance->videoHeight;
}

NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom) {
    if (!pInstance || !pNum || !pDenom) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer) { *pNum = 30; *pDenom = 1; return S_OK; }
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    ComPtr<IMFMediaType> type;
    HRESULT hr = pInstance->pSourceReader->GetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, type.GetAddressOf());
    if (SUCCEEDED(hr))
        hr = MFGetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, pNum, pDenom);
    return hr;
}

// ---------------------------------------------------------------------------
// SeekMedia — robust seek with full reader / WASAPI synchronization.
//
// Contract with the caller: no other thread may call ReadVideoFrame (video
// reader) while SeekMedia is running. The Kotlin side cancels its producer
// coroutine before invoking this. The audio reader is protected internally
// by csAudioFeed.
//
// Flow:
//   1. Snapshot wasPlaying under csClockSync (consistent with timing fields).
//   2. Raise bSeekInProgress so the audio thread discards any sample it is
//      currently decoding and stops feeding WASAPI.
//   3. Stop the presentation clock.
//   4. Seek the video reader.
//   5. Under csAudioFeed: Stop WASAPI, seek audio reader, Reset WASAPI buffer.
//      Holding csAudioFeed for all three ops guarantees the audio thread
//      (which also takes this lock around ReadSample and GetBuffer) cannot
//      interleave a stale sample into the freshly reset buffer.
//   6. Reset timing / audio state atomically.
//   7. If wasPlaying: pre-fill WASAPI, Start WASAPI (under lock), Start clock.
//      If paused: leave WASAPI stopped, clock stopped, player quiet.
//   8. Clear bSeekInProgress (release barrier) and SignalResume if playing.
// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPosition) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->Seek(llPosition);
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    if (llPosition < 0) llPosition = 0;

    // 1. Snapshot current playing state.
    bool wasPlaying;
    {
        ScopedLock lock(pInstance->csClockSync);
        wasPlaying = (pInstance->llPauseStart.load(std::memory_order_relaxed) == 0)
                  && (pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) != 0);
    }

    // 2. Announce seek. Audio thread will:
    //    - break out of its feed loop on next inner-loop iteration,
    //    - drop any post-ReadSample sample as stale.
    pInstance->bSeekInProgress.store(true, std::memory_order_release);

    // Defensive cleanups.
    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);
    pInstance->pCachedSample.Reset();
    pInstance->bHasInitialFrame = false;

    // 3. Stop presentation clock.
    if (pInstance->bUseClockSync && pInstance->pPresentationClock)
        pInstance->pPresentationClock->Stop();

    // 4. Seek video reader (no concurrent ReadVideoFrame thanks to Kotlin contract).
    PROPVARIANT var;
    PropVariantInit(&var);
    var.vt = VT_I8;
    var.hVal.QuadPart = llPosition;
    HRESULT hr = pInstance->pSourceReader->SetCurrentPosition(GUID_NULL, var);
    if (FAILED(hr)) {
        pInstance->bSeekInProgress.store(false, std::memory_order_release);
        PropVariantClear(&var);
        if (wasPlaying) SignalResume(pInstance);
        return hr;
    }
    if (pInstance->pHdrMetadataReader) {
        const HRESULT metadataSeekHr =
            pInstance->pHdrMetadataReader->SetCurrentPosition(GUID_NULL, var);
        ResetHdrMetadataReaderState(pInstance);
        if (FAILED(metadataSeekHr)) {
            PrintHR("HDR10+ metadata reader seek failed", metadataSeekHr);
            pInstance->pHdrMetadataReader.Reset();
        }
    }

    // Catch-up is now handled inside AcquireNextSample's internal loop; no
    // separate fast-forward is needed here.

    // 5. Atomic audio-side seek: Stop + SetCurrentPosition + Reset under one lock.
    // Wake any audio-thread wait so it notices bSeekInProgress and bails out
    // of its feed loop promptly — otherwise it may hold csAudioFeed for up to
    // 10 ms (hAudioSamplesReadyEvent wait budget) while we're stuck waiting
    // for the lock below.
    if (pInstance->bHasAudio) {
        if (pInstance->hAudioSamplesReadyEvent)
            SetEvent(pInstance->hAudioSamplesReadyEvent.Get());
        ScopedLock lock(pInstance->csAudioFeed);
        if (pInstance->pAudioClient) pInstance->pAudioClient->Stop();
        if (pInstance->pSourceReaderAudio)
            pInstance->pSourceReaderAudio->SetCurrentPosition(GUID_NULL, var);
        if (pInstance->pAudioClient) pInstance->pAudioClient->Reset();
    }
    PropVariantClear(&var);

    // 6. Reset state.
    pInstance->bEOF.store(false, std::memory_order_relaxed);
    pInstance->resampleFracPos = 0.0;
    pInstance->audioLatencyMs.store(0.0, std::memory_order_relaxed);
    pInstance->llCurrentPosition.store(llPosition, std::memory_order_relaxed);

    {
        ScopedLock lock(pInstance->csClockSync);
        const float speed = pInstance->playbackSpeed.load(std::memory_order_relaxed);
        const ULONGLONG now = GetCurrentTimeMs();
        const double posMs = llPosition / 10000.0;
        const double adjMs = posMs / static_cast<double>(speed);
        const ULONGLONG startT = (static_cast<ULONGLONG>(adjMs) >= now)
            ? 0ULL : (now - static_cast<ULONGLONG>(adjMs));
        pInstance->llPlaybackStartTime.store(startT, std::memory_order_relaxed);
        pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
        pInstance->llPauseStart.store(wasPlaying ? 0 : now, std::memory_order_relaxed);
    }

    // 7. Resume or stay paused.
    if (wasPlaying) {
        if (pInstance->bHasAudio && pInstance->bAudioInitialized) {
            // Pre-fill runs under csAudioFeed (recursive CS, safe to re-enter).
            PreFillAudioBuffer(pInstance);
        }

        if (pInstance->bHasAudio && pInstance->pAudioClient) {
            ScopedLock lock(pInstance->csAudioFeed);
            pInstance->pAudioClient->Start();
        }
        if (pInstance->bUseClockSync && pInstance->pPresentationClock)
            pInstance->pPresentationClock->Start(llPosition);
    }

    // 8. Release barrier.
    pInstance->bSeekInProgress.store(false, std::memory_order_release);
    if (wasPlaying) SignalResume(pInstance);
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration) {
    if (!pInstance || !pDuration) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetDuration(pDuration);
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    *pDuration = 0;

    ComPtr<IMFMediaSource> source;
    HRESULT hr = pInstance->pSourceReader->GetServiceForStream(
        MF_SOURCE_READER_MEDIASOURCE, GUID_NULL, IID_PPV_ARGS(source.GetAddressOf()));
    if (FAILED(hr)) return hr;

    ComPtr<IMFPresentationDescriptor> pd;
    hr = source->CreatePresentationDescriptor(pd.GetAddressOf());
    if (FAILED(hr)) return hr;

    UINT64 dur = 0;
    hr = pd->GetUINT64(MF_PD_DURATION, &dur);
    if (FAILED(hr)) {
        // Live / duration-less source — distinguish from a hard error by
        // returning S_FALSE with pDuration=0 so callers can gate HLS-style
        // behavior without treating it as a failure.
        return S_FALSE;
    }
    *pDuration = static_cast<LONGLONG>(dur);
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition) {
    if (!pInstance || !pPosition) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetPosition(pPosition);
    *pPosition = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->SetPlaying(bPlaying, bStop);

    HRESULT hr = S_OK;

    if (bStop && !bPlaying) {
        if (pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) != 0) {
            pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
            pInstance->llPauseStart.store(0, std::memory_order_relaxed);
            pInstance->llPlaybackStartTime.store(0, std::memory_order_relaxed);

            // Stop the audio thread BEFORE the presentation clock: otherwise
            // the audio thread keeps calling GetCurrentPadding on an audio
            // client whose clock was just stopped, yielding spurious errors.
            if (pInstance->bAudioThreadRunning.load()) StopAudioThread(pInstance);

            if (pInstance->bUseClockSync && pInstance->pPresentationClock)
                pInstance->pPresentationClock->Stop();

            pInstance->bHasInitialFrame = false;
            pInstance->pCachedSample.Reset();
        }
    } else if (bPlaying) {
        if (pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) == 0) {
            pInstance->llPlaybackStartTime.store(GetCurrentTimeMs(), std::memory_order_relaxed);
        } else {
            const ULONGLONG ps = pInstance->llPauseStart.load(std::memory_order_relaxed);
            if (ps != 0) {
                pInstance->llTotalPauseTime.fetch_add(GetCurrentTimeMs() - ps, std::memory_order_relaxed);
                pInstance->llPauseStart.store(0, std::memory_order_relaxed);
            }
        }

        pInstance->bHasInitialFrame = false;

        if (pInstance->pAudioClient && pInstance->bAudioInitialized) {
            ScopedLock lock(pInstance->csAudioFeed);
            hr = pInstance->pAudioClient->Start();
            // SetPlaybackState(TRUE) is intentionally idempotent. WASAPI
            // reports an already-running shared client as an error even
            // though the requested state has been reached.
            if (hr == AUDCLNT_E_NOT_STOPPED) {
                hr = S_OK;
            } else if (FAILED(hr)) {
                PrintHR("Failed to start audio client", hr);
            }
        }

        if (pInstance->bHasAudio && pInstance->bAudioInitialized && pInstance->pSourceReaderAudio) {
            if (!pInstance->bAudioThreadRunning.load() || !pInstance->hAudioThread) {
                HRESULT hrT = StartAudioThread(pInstance);
                if (FAILED(hrT)) PrintHR("Failed to start audio thread", hrT);
            }
        }

        if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
            hr = pInstance->pPresentationClock->Start(
                pInstance->llCurrentPosition.load(std::memory_order_relaxed));
            if (FAILED(hr)) PrintHR("Failed to start presentation clock", hr);
        }

        SignalResume(pInstance);
    } else {
        if (pInstance->llPauseStart.load(std::memory_order_relaxed) == 0)
            pInstance->llPauseStart.store(GetCurrentTimeMs(), std::memory_order_relaxed);

        pInstance->bHasInitialFrame = false;

        if (pInstance->pAudioClient && pInstance->bAudioInitialized) {
            ScopedLock lock(pInstance->csAudioFeed);
            pInstance->pAudioClient->Stop();
        }
        if (pInstance->bUseClockSync && pInstance->pPresentationClock)
            pInstance->pPresentationClock->Pause();

        SignalPause(pInstance);
    }
    return hr;
}

NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation() { return Shutdown(); }

NATIVEVIDEOPLAYER_API void CloseMedia(VideoPlayerInstance* pInstance) {
    if (!pInstance) return;

    if (pInstance->pHLSPlayer) {
        pInstance->pHLSPlayer.Reset(); // dtor handles Close()
    }

    StopAudioThread(pInstance);

    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);
    pInstance->pCachedSample.Reset();
    pInstance->bHasInitialFrame = false;

    if (pInstance->pAudioClient) {
        pInstance->pAudioClient->Stop();
        pInstance->pAudioClient.Reset();
    }

    if (pInstance->pPresentationClock) {
        pInstance->pPresentationClock->Stop();
        pInstance->pPresentationClock.Reset();
    }

    pInstance->pMediaSource.Reset();
    pInstance->pRenderClient.Reset();
    pInstance->pDevice.Reset();
    pInstance->pAudioEndpointVolume.Reset();
    pInstance->pSourceReader.Reset();
    pInstance->pSourceReaderAudio.Reset();
    pInstance->pHdrMetadataReader.Reset();
    ResetHdrMetadataReaderState(pInstance);
    pInstance->hdrNalLengthSize = 4;

    if (pInstance->pSourceAudioFormat) {
        CoTaskMemFree(pInstance->pSourceAudioFormat);
        pInstance->pSourceAudioFormat = nullptr;
    }

    pInstance->hAudioSamplesReadyEvent.Reset();
    pInstance->hAudioResumeEvent.Reset();

    pInstance->bEOF.store(false);
    pInstance->videoWidth = pInstance->videoHeight = 0;
    pInstance->bHasAudio = false;
    pInstance->bAudioInitialized = false;
    pInstance->llPlaybackStartTime.store(0, std::memory_order_relaxed);
    pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
    pInstance->llPauseStart.store(0, std::memory_order_relaxed);
    pInstance->llCurrentPosition.store(0, std::memory_order_relaxed);
    pInstance->bSeekInProgress.store(false, std::memory_order_relaxed);
    pInstance->playbackSpeed.store(1.0f, std::memory_order_relaxed);
    pInstance->resampleFracPos = 0.0;
    pInstance->audioLatencyMs.store(0.0, std::memory_order_relaxed);
    pInstance->totalVideoFrames.store(0, std::memory_order_relaxed);
    pInstance->renderedVideoFrames.store(0, std::memory_order_relaxed);
    pInstance->droppedVideoFrames.store(0, std::memory_order_relaxed);
    pInstance->maximumAvSyncOffsetMicros.store(0, std::memory_order_relaxed);
    pInstance->bIsNetworkSource = false;
    pInstance->bIsLiveStream = false;
}

NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume) {
    if (pInstance && pInstance->pHLSPlayer) return pInstance->pHLSPlayer->SetVolume(volume);
    return SetVolume(pInstance, volume);
}

NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume) {
    if (pInstance && pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetVolume(volume);
    return GetVolume(pInstance, volume);
}

NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->SetPlaybackSpeed(speed);

    speed = std::clamp(speed, NVP_MIN_PLAYBACK_SPEED, NVP_MAX_PLAYBACK_SPEED);

    if (pInstance->bUseClockSync
        && pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) != 0) {
        const float oldSpeed = pInstance->playbackSpeed.load(std::memory_order_relaxed);
        ScopedLock lock(pInstance->csClockSync);
        const ULONGLONG now = GetCurrentTimeMs();
        const ULONGLONG startT = pInstance->llPlaybackStartTime.load(std::memory_order_relaxed);
        const ULONGLONG pauseT = pInstance->llTotalPauseTime.load(std::memory_order_relaxed);
        const LONGLONG elapsedMs = static_cast<LONGLONG>(now - startT - pauseT);
        const double currentPosMs = elapsedMs * static_cast<double>(oldSpeed);
        pInstance->llPlaybackStartTime.store(
            now - pauseT - static_cast<ULONGLONG>(currentPosMs / speed),
            std::memory_order_relaxed);
    }

    pInstance->playbackSpeed.store(speed, std::memory_order_relaxed);
    pInstance->resampleFracPos = 0.0;

    if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
        ComPtr<IMFRateControl> rateControl;
        if (SUCCEEDED(pInstance->pPresentationClock.As(&rateControl)))
            rateControl->SetRate(FALSE, speed);
    }
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed) {
    if (!pInstance || !pSpeed) return OP_E_INVALID_PARAMETER;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetPlaybackSpeed(pSpeed);
    *pSpeed = pInstance->playbackSpeed.load(std::memory_order_relaxed);
    return S_OK;
}

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------
static const wchar_t* MimeTypeForSubtype(const GUID& s) {
    if (s == MFVideoFormat_H264)  return L"video/h264";
    if (s == MFVideoFormat_HEVC || s == MFVideoFormat_HEVC_ES) return L"video/hevc";
    if (s == MFVideoFormat_MPEG2) return L"video/mpeg2";
    if (s == MFVideoFormat_WMV3 || s == MFVideoFormat_WMV2 || s == MFVideoFormat_WMV1)
        return L"video/x-ms-wmv";
    if (s == MFVideoFormat_VP80)  return L"video/vp8";
    if (s == MFVideoFormat_VP90)  return L"video/vp9";
    if (s == MFVideoFormat_MJPG)  return L"video/x-motion-jpeg";
    if (s == MFVideoFormat_MP4V)  return L"video/mp4v-es";
    if (s == MFVideoFormat_MP43)  return L"video/x-msmpeg4v3";
    return L"video/unknown";
}

NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata) {
    if (!pInstance || !pMetadata) return OP_E_INVALID_PARAMETER;

    if (pInstance->pHLSPlayer) {
        ZeroMemory(pMetadata, sizeof(VideoMetadata));
        pInstance->pHLSPlayer->GetVideoSize(&pMetadata->width, &pMetadata->height);
        pMetadata->hasWidth  = pMetadata->width  > 0;
        pMetadata->hasHeight = pMetadata->height > 0;
        LONGLONG dur = 0;
        if (SUCCEEDED(pInstance->pHLSPlayer->GetDuration(&dur)) && dur > 0) {
            pMetadata->duration = dur;
            pMetadata->hasDuration = TRUE;
        }
        wcscpy_s(pMetadata->mimeType, L"application/x-mpegURL");
        pMetadata->hasMimeType = TRUE;
        return S_OK;
    }

    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    ZeroMemory(pMetadata, sizeof(VideoMetadata));

    ComPtr<IMFMediaSource> source;
    HRESULT hr = pInstance->pSourceReader->GetServiceForStream(
        MF_SOURCE_READER_MEDIASOURCE, GUID_NULL, IID_PPV_ARGS(source.GetAddressOf()));

    if (SUCCEEDED(hr) && source) {
        ComPtr<IMFPresentationDescriptor> pd;
        if (SUCCEEDED(source->CreatePresentationDescriptor(pd.GetAddressOf()))) {
            UINT64 duration = 0;
            if (SUCCEEDED(pd->GetUINT64(MF_PD_DURATION, &duration))) {
                pMetadata->duration = static_cast<LONGLONG>(duration);
                pMetadata->hasDuration = TRUE;
            }

            // Title
            ComPtr<IMFMetadataProvider> metaProvider;
            if (SUCCEEDED(MFGetService(source.Get(), MF_METADATA_PROVIDER_SERVICE,
                                        IID_PPV_ARGS(metaProvider.GetAddressOf())))) {
                ComPtr<IMFMetadata> meta;
                if (SUCCEEDED(metaProvider->GetMFMetadata(pd.Get(), 0, 0, meta.GetAddressOf())) && meta) {
                    PROPVARIANT valTitle;
                    PropVariantInit(&valTitle);
                    if (SUCCEEDED(meta->GetProperty(L"Title", &valTitle))
                        && valTitle.vt == VT_LPWSTR && valTitle.pwszVal) {
                        wcsncpy_s(pMetadata->title, valTitle.pwszVal, _TRUNCATE);
                        pMetadata->hasTitle = TRUE;
                    }
                    PropVariantClear(&valTitle);
                }
            }

            // Streams
            DWORD streamCount = 0;
            pd->GetStreamDescriptorCount(&streamCount);
            LONGLONG totalBitrate = 0;
            bool hasBitrate = false;

            for (DWORD i = 0; i < streamCount; ++i) {
                BOOL selected = FALSE;
                ComPtr<IMFStreamDescriptor> sd;
                if (FAILED(pd->GetStreamDescriptorByIndex(i, &selected, sd.GetAddressOf()))) continue;

                ComPtr<IMFMediaTypeHandler> handler;
                if (FAILED(sd->GetMediaTypeHandler(handler.GetAddressOf()))) continue;

                GUID major{};
                if (FAILED(handler->GetMajorType(&major))) continue;

                ComPtr<IMFMediaType> mt;
                if (FAILED(handler->GetCurrentMediaType(mt.GetAddressOf()))) continue;

                if (major == MFMediaType_Video) {
                    UINT32 w = 0, h = 0;
                    if (SUCCEEDED(MFGetAttributeSize(mt.Get(), MF_MT_FRAME_SIZE, &w, &h))) {
                        pMetadata->width = w; pMetadata->height = h;
                        pMetadata->hasWidth = TRUE; pMetadata->hasHeight = TRUE;
                    }
                    UINT32 num = 0, den = 1;
                    if (SUCCEEDED(MFGetAttributeRatio(mt.Get(), MF_MT_FRAME_RATE, &num, &den)) && den > 0) {
                        pMetadata->frameRate = static_cast<float>(num) / den;
                        pMetadata->hasFrameRate = TRUE;
                    }
                    UINT32 vb = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AVG_BITRATE, &vb))) {
                        totalBitrate += vb;
                        hasBitrate = true;
                    }
                    GUID sub{};
                    if (SUCCEEDED(mt->GetGUID(MF_MT_SUBTYPE, &sub))) {
                        wcscpy_s(pMetadata->mimeType, MimeTypeForSubtype(sub));
                        pMetadata->hasMimeType = TRUE;
                    }
                } else if (major == MFMediaType_Audio) {
                    UINT32 ch = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &ch))) {
                        pMetadata->audioChannels = ch;
                        pMetadata->hasAudioChannels = TRUE;
                    }
                    UINT32 sr = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &sr))) {
                        pMetadata->audioSampleRate = sr;
                        pMetadata->hasAudioSampleRate = TRUE;
                    }
                    UINT32 abps = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, &abps))) {
                        totalBitrate += static_cast<LONGLONG>(abps) * 8;
                        hasBitrate = true;
                    }
                }
            }

            if (hasBitrate) {
                pMetadata->bitrate = totalBitrate;
                pMetadata->hasBitrate = TRUE;
            }
        }
    }

    // Fallbacks
    if (!pMetadata->hasWidth || !pMetadata->hasHeight) {
        if (pInstance->videoWidth > 0 && pInstance->videoHeight > 0) {
            pMetadata->width = pInstance->videoWidth;
            pMetadata->height = pInstance->videoHeight;
            pMetadata->hasWidth = TRUE; pMetadata->hasHeight = TRUE;
        }
    }
    if (!pMetadata->hasFrameRate) {
        UINT num = 0, den = 1;
        if (SUCCEEDED(GetVideoFrameRate(pInstance, &num, &den)) && den > 0) {
            pMetadata->frameRate = static_cast<float>(num) / den;
            pMetadata->hasFrameRate = TRUE;
        }
    }
    if (!pMetadata->hasDuration) {
        LONGLONG dur = 0;
        if (SUCCEEDED(GetMediaDuration(pInstance, &dur))) {
            pMetadata->duration = dur;
            pMetadata->hasDuration = TRUE;
        }
    }
    if (!pMetadata->hasAudioChannels && pInstance->bHasAudio && pInstance->pSourceAudioFormat) {
        pMetadata->audioChannels = pInstance->pSourceAudioFormat->nChannels;
        pMetadata->hasAudioChannels = TRUE;
        pMetadata->audioSampleRate = pInstance->pSourceAudioFormat->nSamplesPerSec;
        pMetadata->hasAudioSampleRate = TRUE;
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT SetOutputSize(VideoPlayerInstance* pInstance, UINT32 targetWidth, UINT32 targetHeight) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer) {
        HRESULT hr = pInstance->pHLSPlayer->SetOutputSize(targetWidth, targetHeight);
        if (SUCCEEDED(hr))
            pInstance->pHLSPlayer->GetVideoSize(&pInstance->videoWidth, &pInstance->videoHeight);
        return hr;
    }
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;
    // The color-managed texture presenter keeps decoded resolution native.
    // Reconfiguring here would risk inserting an RGB video processor into the
    // P010/NV12 GPU path.
    if (pInstance->bHdrOutputRequested) return S_OK;

    if (targetWidth == 0 || targetHeight == 0) {
        targetWidth  = pInstance->nativeWidth;
        targetHeight = pInstance->nativeHeight;
    }
    if (targetWidth > pInstance->nativeWidth || targetHeight > pInstance->nativeHeight) {
        targetWidth  = pInstance->nativeWidth;
        targetHeight = pInstance->nativeHeight;
    }

    if (pInstance->nativeWidth > 0 && pInstance->nativeHeight > 0) {
        const double srcAspect = static_cast<double>(pInstance->nativeWidth) / pInstance->nativeHeight;
        const double dstAspect = static_cast<double>(targetWidth) / targetHeight;
        if (srcAspect > dstAspect) targetHeight = static_cast<UINT32>(targetWidth / srcAspect);
        else                       targetWidth  = static_cast<UINT32>(targetHeight * srcAspect);
    }

    targetWidth  = (targetWidth  + 1) & ~1u;
    targetHeight = (targetHeight + 1) & ~1u;

    if (targetWidth == pInstance->videoWidth && targetHeight == pInstance->videoHeight) return S_OK;
    if (targetWidth < 2 || targetHeight < 2) return E_INVALIDARG;

    ComPtr<IMFMediaType> type;
    HRESULT hr = MFCreateMediaType(type.GetAddressOf());
    if (FAILED(hr)) return hr;

    type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, targetWidth, targetHeight);
    hr = pInstance->pSourceReader->SetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type.Get());
    if (FAILED(hr)) return hr;

    ComPtr<IMFMediaType> actual;
    if (SUCCEEDED(pInstance->pSourceReader->GetCurrentMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM, actual.GetAddressOf()))) {
        MFGetAttributeSize(actual.Get(), MF_MT_FRAME_SIZE,
                           &pInstance->videoWidth, &pInstance->videoHeight);
    }

    pInstance->pCachedSample.Reset();
    pInstance->bHasInitialFrame = false;
    return S_OK;
}

// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ConfigureHdrOutput(
    VideoPlayerInstance* pInstance,
    const int32_t* integerConfiguration,
    size_t integerCount,
    const float* floatingConfiguration,
    size_t floatingCount) {
    if (!pInstance) return E_INVALIDARG;
    // A single negative transfer value explicitly restores the SDR/BGRA path.
    if (integerConfiguration && integerCount == 1 && integerConfiguration[0] < 0) {
        pInstance->bHdrOutputRequested = false;
        if (pInstance->hdrPresenter) pInstance->hdrPresenter->ResetOutput();
        pInstance->hdrPresenter.reset();
        return S_OK;
    }
    if (!pInstance->hdrPresenter) {
        pInstance->hdrPresenter = std::make_unique<WindowsHdrPresenter>();
    }
    HRESULT hr = pInstance->hdrPresenter->Configure(
        integerConfiguration, integerCount, floatingConfiguration, floatingCount);
    if (SUCCEEDED(hr)) pInstance->bHdrOutputRequested = true;
    return hr;
}

NATIVEVIDEOPLAYER_API HRESULT RenderHdrFrame(VideoPlayerInstance* pInstance) {
    if (!pInstance || !pInstance->pSourceReader || !pInstance->hdrPresenter ||
        !pInstance->bHdrOutputRequested) {
        return MF_E_NOT_INITIALIZED;
    }
    if (pInstance->bEOF.load()) return S_FALSE;
    const int32_t colorGenerationBefore =
        pInstance->decodedColorGeneration.load(std::memory_order_acquire);
    ComPtr<IMFSample> sample;
    HRESULT hr = AcquireNextSample(pInstance, sample.GetAddressOf());
    if (hr == S_FALSE || FAILED(hr) || !sample) return hr;
    const int32_t colorGenerationAfter =
        pInstance->decodedColorGeneration.load(std::memory_order_acquire);
    if (colorGenerationAfter != colorGenerationBefore) {
        pInstance->pCachedSample = sample;
        sample->GetSampleTime(&pInstance->llCachedTimestamp);
        pInstance->llCachedInsertedAtMs = GetCurrentTimeMs();
        return S_FALSE;
    }
    std::vector<uint8_t> hdr10PlusPayload;
    if (pInstance->hdrPresenter->RequiresHdr10PlusMetadata()) {
        LONGLONG timestamp = 0;
        if (FAILED(sample->GetSampleTime(&timestamp))) {
            return OP_E_HDR10_PLUS_METADATA_UNAVAILABLE;
        }
        hr = ExtractHdr10PlusPayloadForTimestamp(pInstance, timestamp, hdr10PlusPayload);
        if (FAILED(hr)) return hr;
    }
    hr = pInstance->hdrPresenter->Render(
        sample.Get(),
        pInstance->videoWidth,
        pInstance->videoHeight,
        hdr10PlusPayload.empty() ? nullptr : hdr10PlusPayload.data(),
        hdr10PlusPayload.size());
    if (hr == S_OK) RecordPresentedVideoSample(pInstance, sample.Get());
    return hr;
}

NATIVEVIDEOPLAYER_API HRESULT GetHdrOutputStatus(
    VideoPlayerInstance* pInstance,
    HdrOutputStatus* status) {
    if (!pInstance || !status || !pInstance->hdrPresenter) return E_INVALIDARG;
    *status = pInstance->hdrPresenter->GetStatus();
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetHdrTextureOutputInfo(
    VideoPlayerInstance* pInstance,
    HdrTextureOutputInfo* output) {
    if (!pInstance || !output || !pInstance->hdrPresenter) return E_INVALIDARG;
    return pInstance->hdrPresenter->GetTextureOutputInfo(output) ? S_OK : S_FALSE;
}

NATIVEVIDEOPLAYER_API void GetDecodedVideoColorInfo(
    const VideoPlayerInstance* pInstance,
    int32_t outInfo[7]) {
    if (!outInfo) return;
    std::fill(outInfo, outInfo + 7, 0);
    if (!pInstance) return;
    for (;;) {
        const int32_t before = pInstance->decodedColorGeneration.load(std::memory_order_acquire);
        outInfo[1] = pInstance->decodedBitDepth.load(std::memory_order_relaxed);
        outInfo[2] = pInstance->decodedPrimaries.load(std::memory_order_relaxed);
        outInfo[3] = pInstance->decodedTransfer.load(std::memory_order_relaxed);
        outInfo[4] = pInstance->decodedMatrix.load(std::memory_order_relaxed);
        outInfo[5] = pInstance->decodedRange.load(std::memory_order_relaxed);
        outInfo[6] = pInstance->decodedAuthoritativeUnknowns.load(std::memory_order_relaxed);
        const int32_t after = pInstance->decodedColorGeneration.load(std::memory_order_acquire);
        if (before == after) {
            outInfo[0] = after;
            return;
        }
    }
}

NATIVEVIDEOPLAYER_API void GetVideoPlaybackDiagnostics(
    const VideoPlayerInstance* pInstance,
    int64_t outDiagnostics[5]) {
    if (!outDiagnostics) return;
    std::fill(outDiagnostics, outDiagnostics + 5, 0);
    if (!pInstance) return;
    outDiagnostics[0] = pInstance->totalVideoFrames.load(std::memory_order_relaxed);
    outDiagnostics[1] = pInstance->renderedVideoFrames.load(std::memory_order_relaxed);
    outDiagnostics[2] = pInstance->droppedVideoFrames.load(std::memory_order_relaxed);
    outDiagnostics[3] = pInstance->maximumAvSyncOffsetMicros.load(std::memory_order_relaxed);
    outDiagnostics[4] = pInstance->bHasAudio ? 1 : 0;
}

NATIVEVIDEOPLAYER_API HRESULT ValidateHdrPresenterShaders() {
    return WindowsHdrPresenter::ValidateShaders();
}
