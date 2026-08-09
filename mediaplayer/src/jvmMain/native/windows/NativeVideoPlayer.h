// NativeVideoPlayer.h
#pragma once
#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include "ErrorCodes.h"
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <cstdint>

// Native API version — bump when the exported API changes.
#define NATIVE_VIDEO_PLAYER_VERSION 14

// Playback speed bounds — kept in sync with
// io.github.kdroidfilter.composemediaplayer.VideoPlayerState.{MIN,MAX}_PLAYBACK_SPEED.
static const float NVP_MIN_PLAYBACK_SPEED = 0.5f;
static const float NVP_MAX_PLAYBACK_SPEED = 2.0f;

typedef struct VideoMetadata {
    wchar_t title[256];
    LONGLONG duration;
    UINT32 width;
    UINT32 height;
    LONGLONG bitrate;
    float frameRate;
    wchar_t mimeType[64];
    UINT32 audioChannels;
    UINT32 audioSampleRate;
    BOOL hasTitle;
    BOOL hasDuration;
    BOOL hasWidth;
    BOOL hasHeight;
    BOOL hasBitrate;
    BOOL hasFrameRate;
    BOOL hasMimeType;
    BOOL hasAudioChannels;
    BOOL hasAudioSampleRate;
} VideoMetadata;

typedef struct HdrOutputStatus {
    BOOL displayQueried;
    BOOL advancedColorEnabled;
    BOOL swapChainConfigured;
    BOOL firstFramePresented;
    BOOL p010InputConfirmed;
    UINT32 bitsPerColor;
    UINT32 displayColorSpace;
    UINT32 swapChainColorSpace;
    UINT32 monitorGeneration;
    HRESULT lastError;
    float minLuminanceNits;
    float maxLuminanceNits;
    float maxFullFrameLuminanceNits;
} HdrOutputStatus;

/** Producer-owned texture consumed by the Nucleus Windows TextureView host. */
typedef struct HdrTextureOutputInfo {
    HANDLE sharedHandle;
    UINT32 width;
    UINT32 height;
    UINT32 format;
    UINT64 generation;
    UINT64 frameSerial;
    LUID adapterLuid;
    BOOL extendedLinear;
} HdrTextureOutputInfo;

#ifdef _WIN32
  #ifdef NATIVEVIDEOPLAYER_EXPORTS
    #define NATIVEVIDEOPLAYER_API __declspec(dllexport)
  #else
    #define NATIVEVIDEOPLAYER_API __declspec(dllimport)
  #endif
#else
  #define NATIVEVIDEOPLAYER_API
#endif

struct VideoPlayerInstance;

#ifdef __cplusplus
extern "C" {
#endif

NATIVEVIDEOPLAYER_API int     GetNativeVersion();
NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation();
NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance);
NATIVEVIDEOPLAYER_API void    DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback = TRUE);
NATIVEVIDEOPLAYER_API HRESULT OpenMediaWithHeaders(VideoPlayerInstance* pInstance, const wchar_t* url,
                                                   const wchar_t* requestHeaders, BOOL startPlayback = TRUE);
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize);
NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(VideoPlayerInstance* pInstance,
                                                  BYTE* pDst, DWORD dstRowBytes, DWORD dstCapacity,
                                                  LONGLONG* pTimestamp);
NATIVEVIDEOPLAYER_API void    CloseMedia(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API BOOL    IsEOF(const VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API void    GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight);
NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom);
NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPosition);
NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration);
NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition);
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop = FALSE);
NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation();
NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume);
NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume);
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed);
NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed);
NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata);
NATIVEVIDEOPLAYER_API HRESULT SetOutputSize(VideoPlayerInstance* pInstance, UINT32 targetWidth, UINT32 targetHeight);
NATIVEVIDEOPLAYER_API HRESULT ConfigureHdrOutput(
    VideoPlayerInstance* pInstance,
    const int32_t* integerConfiguration,
    size_t integerCount,
    const float* floatingConfiguration,
    size_t floatingCount);
NATIVEVIDEOPLAYER_API HRESULT RenderHdrFrame(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API HRESULT GetHdrOutputStatus(VideoPlayerInstance* pInstance, HdrOutputStatus* status);
NATIVEVIDEOPLAYER_API HRESULT GetHdrTextureOutputInfo(
    VideoPlayerInstance* pInstance,
    HdrTextureOutputInfo* output);
// Active decoded color snapshot: generation, bit depth, primaries, transfer,
// matrix, range and flags (authoritative unknowns / validated HDR10+).
// Values follow JvmDecodedVideoColorSignalCodec.
NATIVEVIDEOPLAYER_API void    GetDecodedVideoColorInfo(
    const VideoPlayerInstance* pInstance,
    int32_t outInfo[7]);
NATIVEVIDEOPLAYER_API HRESULT ProbeVideoColorInfoWithHeaders(
    const wchar_t* url,
    const wchar_t* requestHeaders,
    int32_t outInfo[7]);
NATIVEVIDEOPLAYER_API void    GetVideoPlaybackDiagnostics(
    const VideoPlayerInstance* pInstance,
    int64_t outDiagnostics[5]);
NATIVEVIDEOPLAYER_API HRESULT ValidateHdrPresenterShaders();

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
