// jni_bridge.cpp — JNI bridge for NativeVideoPlayer
// Maps Kotlin external functions to the existing C API.

#include <jni.h>
#include "LibVlcCanvas.h"
#include "NativeVideoPlayer.h"
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <windows.h>

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
static inline VideoPlayerInstance* toInstance(jlong handle) {
    return reinterpret_cast<VideoPlayerInstance*>(handle);
}

static inline LibVlcCanvasPlayer* toLibVlc(jlong handle) {
    return reinterpret_cast<LibVlcCanvasPlayer*>(handle);
}

static constexpr double HUNDRED_NANOSECOND_TICKS_PER_SECOND = 10000000.0;

static HWND createNativeVideoWindow() {
    return CreateWindowExW(
        0,
        L"STATIC",
        L"",
        WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS,
        0,
        0,
        1,
        1,
        GetDesktopWindow(),
        nullptr,
        GetModuleHandleW(nullptr),
        nullptr);
}

// ---------------------------------------------------------------------------
// JNI implementations
// ---------------------------------------------------------------------------

static jint JNICALL jni_GetNativeVersion(JNIEnv*, jclass) {
    return GetNativeVersion();
}

static jint JNICALL jni_InitMediaFoundation(JNIEnv*, jclass) {
    return InitMediaFoundation();
}

static jlong JNICALL jni_CreateInstance(JNIEnv*, jclass) {
    VideoPlayerInstance* p = nullptr;
    HRESULT hr = CreateVideoPlayerInstance(&p);
    if (FAILED(hr) || !p) return 0;
    return reinterpret_cast<jlong>(p);
}

static void JNICALL jni_DestroyInstance(JNIEnv*, jclass, jlong handle) {
    if (handle) DestroyVideoPlayerInstance(toInstance(handle));
}

static jint JNICALL jni_OpenMedia(JNIEnv* env, jclass, jlong handle, jstring url, jboolean startPlayback) {
    if (!handle || !url) return OP_E_INVALID_PARAMETER;
    const jchar* chars = env->GetStringChars(url, nullptr);
    if (!chars) return E_OUTOFMEMORY;
    HRESULT hr = OpenMedia(toInstance(handle),
                           reinterpret_cast<const wchar_t*>(chars),
                           startPlayback ? TRUE : FALSE);
    env->ReleaseStringChars(url, chars);
    return hr;
}

static jint JNICALL jni_OpenMediaWithHeaders(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring url,
    jstring requestHeaders,
    jboolean startPlayback
) {
    if (!handle || !url) return OP_E_INVALID_PARAMETER;
    const jchar* chars = env->GetStringChars(url, nullptr);
    if (!chars) return E_OUTOFMEMORY;

    const jchar* headerChars = nullptr;
    if (requestHeaders) {
        headerChars = env->GetStringChars(requestHeaders, nullptr);
        if (!headerChars) {
            env->ReleaseStringChars(url, chars);
            return E_OUTOFMEMORY;
        }
    }

    HRESULT hr = OpenMediaWithHeaders(
        toInstance(handle),
        reinterpret_cast<const wchar_t*>(chars),
        headerChars ? reinterpret_cast<const wchar_t*>(headerChars) : nullptr,
        startPlayback ? TRUE : FALSE
    );

    if (headerChars) {
        env->ReleaseStringChars(requestHeaders, headerChars);
    }
    env->ReleaseStringChars(url, chars);
    return hr;
}

// Returns a direct ByteBuffer wrapping the locked frame, or null.
// outResult[0] receives the HRESULT.
static jobject JNICALL jni_ReadVideoFrame(JNIEnv* env, jclass, jlong handle, jintArray outResult) {
    if (!handle) {
        if (outResult) { jint v = OP_E_NOT_INITIALIZED; env->SetIntArrayRegion(outResult, 0, 1, &v); }
        return nullptr;
    }
    BYTE* pData = nullptr;
    DWORD dataSize = 0;
    HRESULT hr = ReadVideoFrame(toInstance(handle), &pData, &dataSize);
    if (outResult) { jint v = static_cast<jint>(hr); env->SetIntArrayRegion(outResult, 0, 1, &v); }
    if (FAILED(hr) || !pData || dataSize == 0) return nullptr;
    return env->NewDirectByteBuffer(pData, dataSize);
}

static jint JNICALL jni_UnlockVideoFrame(JNIEnv*, jclass, jlong handle) {
    return handle ? UnlockVideoFrame(toInstance(handle)) : E_INVALIDARG;
}

static void JNICALL jni_CloseMedia(JNIEnv*, jclass, jlong handle) {
    if (handle) CloseMedia(toInstance(handle));
}

static jboolean JNICALL jni_IsEOF(JNIEnv*, jclass, jlong handle) {
    return (handle && IsEOF(toInstance(handle))) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_GetVideoSize(JNIEnv* env, jclass, jlong handle, jintArray outSize) {
    UINT32 w = 0, h = 0;
    if (handle) GetVideoSize(toInstance(handle), &w, &h);
    jint vals[2] = { static_cast<jint>(w), static_cast<jint>(h) };
    env->SetIntArrayRegion(outSize, 0, 2, vals);
}

static jint JNICALL jni_GetVideoFrameRate(JNIEnv* env, jclass, jlong handle, jintArray outRate) {
    if (!handle) return E_INVALIDARG;
    UINT num = 0, denom = 0;
    HRESULT hr = GetVideoFrameRate(toInstance(handle), &num, &denom);
    jint vals[2] = { static_cast<jint>(num), static_cast<jint>(denom) };
    env->SetIntArrayRegion(outRate, 0, 2, vals);
    return hr;
}

static jint JNICALL jni_SeekMedia(JNIEnv*, jclass, jlong handle, jlong pos) {
    return handle ? SeekMedia(toInstance(handle), pos) : E_INVALIDARG;
}

static jint JNICALL jni_GetMediaDuration(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle) return E_INVALIDARG;
    LONGLONG v = 0;
    HRESULT hr = GetMediaDuration(toInstance(handle), &v);
    jlong jv = static_cast<jlong>(v);
    env->SetLongArrayRegion(out, 0, 1, &jv);
    return hr;
}

static jint JNICALL jni_GetMediaPosition(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle) return E_INVALIDARG;
    LONGLONG v = 0;
    HRESULT hr = GetMediaPosition(toInstance(handle), &v);
    jlong jv = static_cast<jlong>(v);
    env->SetLongArrayRegion(out, 0, 1, &jv);
    return hr;
}

static jint JNICALL jni_SetPlaybackState(JNIEnv*, jclass, jlong handle, jboolean playing, jboolean stop) {
    return handle ? SetPlaybackState(toInstance(handle), playing ? TRUE : FALSE, stop ? TRUE : FALSE) : E_INVALIDARG;
}

static jint JNICALL jni_ShutdownMediaFoundation(JNIEnv*, jclass) {
    return ShutdownMediaFoundation();
}

static jint JNICALL jni_SetAudioVolume(JNIEnv*, jclass, jlong handle, jfloat vol) {
    return handle ? SetAudioVolume(toInstance(handle), vol) : E_INVALIDARG;
}

static jint JNICALL jni_GetAudioVolume(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    if (!handle) return E_INVALIDARG;
    float v = 0;
    HRESULT hr = GetAudioVolume(toInstance(handle), &v);
    jfloat jv = v;
    env->SetFloatArrayRegion(out, 0, 1, &jv);
    return hr;
}

static jint JNICALL jni_SetPlaybackSpeed(JNIEnv*, jclass, jlong handle, jfloat speed) {
    return handle ? SetPlaybackSpeed(toInstance(handle), speed) : E_INVALIDARG;
}

static jint JNICALL jni_GetPlaybackSpeed(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    if (!handle) return E_INVALIDARG;
    float v = 0;
    HRESULT hr = GetPlaybackSpeed(toInstance(handle), &v);
    jfloat jv = v;
    env->SetFloatArrayRegion(out, 0, 1, &jv);
    return hr;
}

// Metadata — fills parallel arrays so the Kotlin side can construct VideoMetadata.
static jint JNICALL jni_GetVideoMetadata(JNIEnv* env, jclass, jlong handle,
        jcharArray outTitle, jcharArray outMimeType,
        jlongArray outLongVals, jintArray outIntVals,
        jfloatArray outFloatVals, jbooleanArray outHasFlags) {
    if (!handle) return E_INVALIDARG;

    VideoMetadata m;
    HRESULT hr = GetVideoMetadata(toInstance(handle), &m);
    if (FAILED(hr)) return hr;

    if (outTitle)
        env->SetCharArrayRegion(outTitle, 0, 256, reinterpret_cast<const jchar*>(m.title));
    if (outMimeType)
        env->SetCharArrayRegion(outMimeType, 0, 64, reinterpret_cast<const jchar*>(m.mimeType));
    if (outLongVals) {
        jlong lv[2] = { static_cast<jlong>(m.duration), static_cast<jlong>(m.bitrate) };
        env->SetLongArrayRegion(outLongVals, 0, 2, lv);
    }
    if (outIntVals) {
        jint iv[4] = { static_cast<jint>(m.width), static_cast<jint>(m.height),
                       static_cast<jint>(m.audioChannels), static_cast<jint>(m.audioSampleRate) };
        env->SetIntArrayRegion(outIntVals, 0, 4, iv);
    }
    if (outFloatVals) {
        jfloat fv = m.frameRate;
        env->SetFloatArrayRegion(outFloatVals, 0, 1, &fv);
    }
    if (outHasFlags) {
        jboolean flags[9] = {
            static_cast<jboolean>(m.hasTitle),  static_cast<jboolean>(m.hasDuration),
            static_cast<jboolean>(m.hasWidth),  static_cast<jboolean>(m.hasHeight),
            static_cast<jboolean>(m.hasBitrate), static_cast<jboolean>(m.hasFrameRate),
            static_cast<jboolean>(m.hasMimeType), static_cast<jboolean>(m.hasAudioChannels),
            static_cast<jboolean>(m.hasAudioSampleRate)
        };
        env->SetBooleanArrayRegion(outHasFlags, 0, 9, flags);
    }
    return hr;
}

// Wrap an arbitrary native address as a direct ByteBuffer (used for Skia pixel access).
static jobject JNICALL jni_WrapPointer(JNIEnv* env, jclass, jlong address, jlong size) {
    if (!address || size <= 0) return nullptr;
    return env->NewDirectByteBuffer(reinterpret_cast<void*>(address), static_cast<jlong>(size));
}

static jint JNICALL jni_SetOutputSize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
    return handle ? SetOutputSize(toInstance(handle),
                                  static_cast<UINT32>(width),
                                  static_cast<UINT32>(height))
                  : E_INVALIDARG;
}

static jint JNICALL jni_ConfigureHdrOutput(
    JNIEnv* env,
    jclass,
    jlong handle,
    jintArray integerConfiguration,
    jfloatArray floatingConfiguration
) {
    if (!handle || !integerConfiguration) return E_INVALIDARG;
    const jsize integerCount = env->GetArrayLength(integerConfiguration);
    const jsize floatingCount = floatingConfiguration ? env->GetArrayLength(floatingConfiguration) : 0;
    std::vector<jint> integers(static_cast<size_t>(integerCount));
    std::vector<jfloat> values(static_cast<size_t>(floatingCount));
    if (integerCount > 0) env->GetIntArrayRegion(integerConfiguration, 0, integerCount, integers.data());
    if (floatingCount > 0) env->GetFloatArrayRegion(floatingConfiguration, 0, floatingCount, values.data());
    if (env->ExceptionCheck()) return E_INVALIDARG;
    return ConfigureHdrOutput(
        toInstance(handle),
        reinterpret_cast<const int32_t*>(integers.data()),
        integers.size(),
        reinterpret_cast<const float*>(values.data()),
        values.size());
}

static jlong JNICALL jni_CreateNativeVideoWindow(JNIEnv*, jclass, jlong handle, jboolean libVlc) {
    if (!handle) return 0;
    HWND hwnd = createNativeVideoWindow();
    if (!hwnd) return 0;
    const bool attached = libVlc == JNI_TRUE
        ? lvc_set_native_window(toLibVlc(handle), hwnd)
        : SUCCEEDED(AttachHdrOutput(toInstance(handle), hwnd));
    if (!attached) {
        DestroyWindow(hwnd);
        return 0;
    }
    return reinterpret_cast<jlong>(hwnd);
}

static void JNICALL jni_DisposeNativeVideoWindow(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong hwndHandle,
    jboolean libVlc
) {
    if (handle) {
        if (libVlc == JNI_TRUE) {
            lvc_set_native_window(toLibVlc(handle), nullptr);
        } else {
            DetachHdrOutput(toInstance(handle));
        }
    }
    HWND hwnd = reinterpret_cast<HWND>(hwndHandle);
    if (hwnd && IsWindow(hwnd)) DestroyWindow(hwnd);
}

static jint JNICALL jni_RenderHdrFrame(JNIEnv*, jclass, jlong handle) {
    return handle ? RenderHdrFrame(toInstance(handle)) : E_INVALIDARG;
}

static jint JNICALL jni_GetHdrOutputStatus(
    JNIEnv* env,
    jclass,
    jlong handle,
    jintArray integerStatus,
    jfloatArray luminanceStatus
) {
    if (!handle || !integerStatus || !luminanceStatus ||
        env->GetArrayLength(integerStatus) < 10 || env->GetArrayLength(luminanceStatus) < 3) {
        return E_INVALIDARG;
    }
    HdrOutputStatus status{};
    const HRESULT hr = GetHdrOutputStatus(toInstance(handle), &status);
    if (FAILED(hr)) return hr;
    const jint integers[10] = {
        status.displayQueried ? 1 : 0,
        status.advancedColorEnabled ? 1 : 0,
        status.swapChainConfigured ? 1 : 0,
        status.firstFramePresented ? 1 : 0,
        status.p010InputConfirmed ? 1 : 0,
        static_cast<jint>(status.bitsPerColor),
        static_cast<jint>(status.displayColorSpace),
        static_cast<jint>(status.swapChainColorSpace),
        static_cast<jint>(status.monitorGeneration),
        static_cast<jint>(status.lastError),
    };
    const jfloat luminance[3] = {
        status.minLuminanceNits,
        status.maxLuminanceNits,
        status.maxFullFrameLuminanceNits,
    };
    env->SetIntArrayRegion(integerStatus, 0, 10, integers);
    env->SetFloatArrayRegion(luminanceStatus, 0, 3, luminance);
    return hr;
}

static jintArray JNICALL jni_GetDecodedVideoColorInfo(JNIEnv* env, jclass, jlong handle) {
    if (!handle) return nullptr;
    int32_t nativeValues[7] = {};
    GetDecodedVideoColorInfo(toInstance(handle), nativeValues);
    jint values[7] = {};
    for (size_t index = 0; index < 7; ++index) values[index] = static_cast<jint>(nativeValues[index]);
    jintArray result = env->NewIntArray(7);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, 7, values);
    return env->ExceptionCheck() ? nullptr : result;
}

static jlongArray JNICALL jni_GetVideoPlaybackDiagnostics(JNIEnv* env, jclass, jlong handle) {
    if (!handle) return nullptr;
    int64_t nativeValues[5] = {};
    GetVideoPlaybackDiagnostics(toInstance(handle), nativeValues);
    jlong values[5] = {};
    for (size_t index = 0; index < 5; ++index) {
        values[index] = static_cast<jlong>(nativeValues[index]);
    }
    jlongArray result = env->NewLongArray(5);
    if (!result) return nullptr;
    env->SetLongArrayRegion(result, 0, 5, values);
    return env->ExceptionCheck() ? nullptr : result;
}

static jintArray JNICALL jni_ProbeVideoColorInfo(
    JNIEnv* env,
    jclass,
    jstring url,
    jstring requestHeaders
) {
    if (!url) return nullptr;
    const jchar* urlChars = env->GetStringChars(url, nullptr);
    if (!urlChars) return nullptr;

    const jchar* headerChars = nullptr;
    if (requestHeaders) {
        headerChars = env->GetStringChars(requestHeaders, nullptr);
        if (!headerChars) {
            env->ReleaseStringChars(url, urlChars);
            return nullptr;
        }
    }

    int32_t nativeValues[7] = {};
    const HRESULT hr = ProbeVideoColorInfoWithHeaders(
        reinterpret_cast<const wchar_t*>(urlChars),
        headerChars ? reinterpret_cast<const wchar_t*>(headerChars) : nullptr,
        nativeValues);

    if (headerChars) {
        env->ReleaseStringChars(requestHeaders, headerChars);
    }
    env->ReleaseStringChars(url, urlChars);
    if (FAILED(hr)) return nullptr;

    jint values[7] = {};
    for (size_t index = 0; index < 7; ++index) {
        values[index] = static_cast<jint>(nativeValues[index]);
    }
    jintArray result = env->NewIntArray(7);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, 7, values);
    return env->ExceptionCheck() ? nullptr : result;
}

static jlong JNICALL jni_CreateLibVlcInstance(
    JNIEnv* env,
    jclass,
    jstring libPath,
    jstring pluginPath,
    jboolean nativeVideoOutput
) {
    if (!libPath || !pluginPath) return 0;
    const char* cLibPath = env->GetStringUTFChars(libPath, nullptr);
    if (!cLibPath) return 0;
    const char* cPluginPath = env->GetStringUTFChars(pluginPath, nullptr);
    if (!cPluginPath) {
        env->ReleaseStringUTFChars(libPath, cLibPath);
        return 0;
    }
    LibVlcCanvasPlayer* p = lvc_create(cLibPath, cPluginPath, nativeVideoOutput == JNI_TRUE);
    env->ReleaseStringUTFChars(libPath, cLibPath);
    env->ReleaseStringUTFChars(pluginPath, cPluginPath);
    return p ? reinterpret_cast<jlong>(p) : 0;
}

static void JNICALL jni_DestroyLibVlcInstance(JNIEnv*, jclass, jlong handle) {
    if (handle) lvc_destroy(toLibVlc(handle));
}

static jint JNICALL jni_OpenLibVlcMediaWithHeaders(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring url,
    jstring requestHeaders,
    jboolean startPlayback
) {
    if (!handle || !url) return E_INVALIDARG;
    const char* cUrl = env->GetStringUTFChars(url, nullptr);
    if (!cUrl) return E_OUTOFMEMORY;
    const char* cHeaders = requestHeaders ? env->GetStringUTFChars(requestHeaders, nullptr) : nullptr;
    if (requestHeaders && !cHeaders) {
        env->ReleaseStringUTFChars(url, cUrl);
        return E_OUTOFMEMORY;
    }

    bool ok = lvc_open_uri_with_headers(toLibVlc(handle), cUrl, cHeaders, startPlayback == JNI_TRUE);
    if (cHeaders) env->ReleaseStringUTFChars(requestHeaders, cHeaders);
    env->ReleaseStringUTFChars(url, cUrl);

    return ok ? S_OK : E_FAIL;
}

static jobject JNICALL jni_ReadLibVlcVideoFrame(JNIEnv* env, jclass, jlong handle, jintArray outResult) {
    if (!handle) {
        if (outResult) {
            jint v = OP_E_NOT_INITIALIZED;
            env->SetIntArrayRegion(outResult, 0, 1, &v);
        }
        return nullptr;
    }

    int32_t info[3] = {0, 0, 0};
    void* pData = lvc_lock_frame(toLibVlc(handle), info);
    if (!pData || info[0] <= 0 || info[1] <= 0 || info[2] <= 0) {
        if (outResult) {
            jint v = S_FALSE;
            env->SetIntArrayRegion(outResult, 0, 1, &v);
        }
        return nullptr;
    }

    if (outResult) {
        jint v = S_OK;
        env->SetIntArrayRegion(outResult, 0, 1, &v);
    }
    const jlong size = static_cast<jlong>(info[2]) * static_cast<jlong>(info[1]);
    return env->NewDirectByteBuffer(pData, size);
}

static jint JNICALL jni_UnlockLibVlcVideoFrame(JNIEnv*, jclass, jlong handle) {
    if (!handle) return E_INVALIDARG;
    lvc_unlock_frame(toLibVlc(handle));
    return S_OK;
}

static void JNICALL jni_CloseLibVlcMedia(JNIEnv*, jclass, jlong handle) {
    if (handle) lvc_close(toLibVlc(handle));
}

static jboolean JNICALL jni_IsLibVlcEOF(JNIEnv*, jclass, jlong handle) {
    return (handle && lvc_is_ended(toLibVlc(handle))) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_GetLibVlcVideoSize(JNIEnv* env, jclass, jlong handle, jintArray outSize) {
    jint vals[2] = {
        handle ? static_cast<jint>(lvc_get_frame_width(toLibVlc(handle))) : 0,
        handle ? static_cast<jint>(lvc_get_frame_height(toLibVlc(handle))) : 0
    };
    env->SetIntArrayRegion(outSize, 0, 2, vals);
}

static jfloat JNICALL jni_GetLibVlcVideoFrameRate(JNIEnv*, jclass, jlong handle) {
    return handle ? lvc_get_frame_rate(toLibVlc(handle)) : 0.0f;
}

static jint JNICALL jni_SeekLibVlcMedia(JNIEnv*, jclass, jlong handle, jlong pos) {
    if (!handle) return E_INVALIDARG;
    lvc_seek_to(toLibVlc(handle), static_cast<double>(pos) / HUNDRED_NANOSECOND_TICKS_PER_SECOND);
    return S_OK;
}

static jint JNICALL jni_GetLibVlcMediaDuration(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle) return E_INVALIDARG;
    const double seconds = lvc_get_duration(toLibVlc(handle));
    jlong value = seconds > 0.0 ? static_cast<jlong>(seconds * HUNDRED_NANOSECOND_TICKS_PER_SECOND) : 0L;
    env->SetLongArrayRegion(out, 0, 1, &value);
    return S_OK;
}

static jint JNICALL jni_GetLibVlcMediaPosition(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle) return E_INVALIDARG;
    const double seconds = lvc_get_current_time(toLibVlc(handle));
    jlong value = seconds > 0.0 ? static_cast<jlong>(seconds * HUNDRED_NANOSECOND_TICKS_PER_SECOND) : 0L;
    env->SetLongArrayRegion(out, 0, 1, &value);
    return S_OK;
}

static jint JNICALL jni_SetLibVlcPlaybackState(JNIEnv*, jclass, jlong handle, jboolean playing, jboolean stop) {
    if (!handle) return E_INVALIDARG;
    if (stop) {
        lvc_close(toLibVlc(handle));
    } else if (playing) {
        lvc_play(toLibVlc(handle));
    } else {
        lvc_pause(toLibVlc(handle));
    }
    return S_OK;
}

static jint JNICALL jni_SetLibVlcAudioVolume(JNIEnv*, jclass, jlong handle, jfloat vol) {
    if (!handle) return E_INVALIDARG;
    lvc_set_volume(toLibVlc(handle), vol);
    return S_OK;
}

static jint JNICALL jni_GetLibVlcAudioVolume(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    if (!handle) return E_INVALIDARG;
    jfloat value = lvc_get_volume(toLibVlc(handle));
    env->SetFloatArrayRegion(out, 0, 1, &value);
    return S_OK;
}

static jint JNICALL jni_SetLibVlcPlaybackSpeed(JNIEnv*, jclass, jlong handle, jfloat speed) {
    if (!handle) return E_INVALIDARG;
    lvc_set_playback_speed(toLibVlc(handle), speed);
    return S_OK;
}

static jint JNICALL jni_GetLibVlcPlaybackSpeed(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    if (!handle) return E_INVALIDARG;
    jfloat value = lvc_get_playback_speed(toLibVlc(handle));
    env->SetFloatArrayRegion(out, 0, 1, &value);
    return S_OK;
}

static jboolean JNICALL jni_SelectLibVlcAudioTrack(JNIEnv*, jclass, jlong handle, jint ordinal) {
    return (handle && lvc_select_audio_track(toLibVlc(handle), ordinal)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL jni_SelectLibVlcSubtitleTrack(JNIEnv*, jclass, jlong handle, jint ordinal) {
    return (handle && lvc_select_subtitle_track(toLibVlc(handle), ordinal)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL jni_DisableLibVlcSubtitles(JNIEnv*, jclass, jlong handle) {
    return (handle && lvc_disable_subtitles(toLibVlc(handle))) ? JNI_TRUE : JNI_FALSE;
}

static jstring JNICALL jni_GetLibVlcAudioTrackDescriptions(JNIEnv* env, jclass, jlong handle) {
    if (!handle) return nullptr;
    char* value = lvc_get_audio_track_descriptions(toLibVlc(handle));
    if (!value) return nullptr;
    jstring result = env->NewStringUTF(value);
    free(value);
    return result;
}

static jstring JNICALL jni_GetLibVlcSubtitleTrackDescriptions(JNIEnv* env, jclass, jlong handle) {
    if (!handle) return nullptr;
    char* value = lvc_get_subtitle_track_descriptions(toLibVlc(handle));
    if (!value) return nullptr;
    jstring result = env->NewStringUTF(value);
    free(value);
    return result;
}

// ---------------------------------------------------------------------------
// Registration table
// ---------------------------------------------------------------------------
static const JNINativeMethod g_methods[] = {
    { const_cast<char*>("nGetNativeVersion"),   const_cast<char*>("()I"),                          (void*)jni_GetNativeVersion },
    { const_cast<char*>("nInitMediaFoundation"),const_cast<char*>("()I"),                          (void*)jni_InitMediaFoundation },
    { const_cast<char*>("nCreateInstance"),      const_cast<char*>("()J"),                          (void*)jni_CreateInstance },
    { const_cast<char*>("nDestroyInstance"),     const_cast<char*>("(J)V"),                         (void*)jni_DestroyInstance },
    { const_cast<char*>("nOpenMedia"),           const_cast<char*>("(JLjava/lang/String;Z)I"),      (void*)jni_OpenMedia },
    { const_cast<char*>("nOpenMediaWithHeaders"), const_cast<char*>("(JLjava/lang/String;Ljava/lang/String;Z)I"), (void*)jni_OpenMediaWithHeaders },
    { const_cast<char*>("nReadVideoFrame"),      const_cast<char*>("(J[I)Ljava/nio/ByteBuffer;"),   (void*)jni_ReadVideoFrame },
    { const_cast<char*>("nUnlockVideoFrame"),    const_cast<char*>("(J)I"),                         (void*)jni_UnlockVideoFrame },
    { const_cast<char*>("nCloseMedia"),          const_cast<char*>("(J)V"),                         (void*)jni_CloseMedia },
    { const_cast<char*>("nIsEOF"),               const_cast<char*>("(J)Z"),                         (void*)jni_IsEOF },
    { const_cast<char*>("nGetVideoSize"),        const_cast<char*>("(J[I)V"),                       (void*)jni_GetVideoSize },
    { const_cast<char*>("nGetVideoFrameRate"),   const_cast<char*>("(J[I)I"),                       (void*)jni_GetVideoFrameRate },
    { const_cast<char*>("nSeekMedia"),           const_cast<char*>("(JJ)I"),                        (void*)jni_SeekMedia },
    { const_cast<char*>("nGetMediaDuration"),    const_cast<char*>("(J[J)I"),                       (void*)jni_GetMediaDuration },
    { const_cast<char*>("nGetMediaPosition"),    const_cast<char*>("(J[J)I"),                       (void*)jni_GetMediaPosition },
    { const_cast<char*>("nSetPlaybackState"),    const_cast<char*>("(JZZ)I"),                       (void*)jni_SetPlaybackState },
    { const_cast<char*>("nShutdownMediaFoundation"), const_cast<char*>("()I"),                      (void*)jni_ShutdownMediaFoundation },
    { const_cast<char*>("nSetAudioVolume"),      const_cast<char*>("(JF)I"),                        (void*)jni_SetAudioVolume },
    { const_cast<char*>("nGetAudioVolume"),      const_cast<char*>("(J[F)I"),                       (void*)jni_GetAudioVolume },
    { const_cast<char*>("nSetPlaybackSpeed"),    const_cast<char*>("(JF)I"),                        (void*)jni_SetPlaybackSpeed },
    { const_cast<char*>("nGetPlaybackSpeed"),    const_cast<char*>("(J[F)I"),                       (void*)jni_GetPlaybackSpeed },
    { const_cast<char*>("nGetVideoMetadata"),    const_cast<char*>("(J[C[C[J[I[F[Z)I"),             (void*)jni_GetVideoMetadata },
    { const_cast<char*>("nWrapPointer"),         const_cast<char*>("(JJ)Ljava/nio/ByteBuffer;"),    (void*)jni_WrapPointer },
    { const_cast<char*>("nSetOutputSize"),      const_cast<char*>("(JII)I"),                       (void*)jni_SetOutputSize },
    { const_cast<char*>("nConfigureHdrOutput"), const_cast<char*>("(J[I[F)I"),                    (void*)jni_ConfigureHdrOutput },
    { const_cast<char*>("nCreateNativeVideoWindow"), const_cast<char*>("(JZ)J"),                  (void*)jni_CreateNativeVideoWindow },
    { const_cast<char*>("nDisposeNativeVideoWindow"), const_cast<char*>("(JJZ)V"),                (void*)jni_DisposeNativeVideoWindow },
    { const_cast<char*>("nRenderHdrFrame"),     const_cast<char*>("(J)I"),                         (void*)jni_RenderHdrFrame },
    { const_cast<char*>("nGetHdrOutputStatus"), const_cast<char*>("(J[I[F)I"),                    (void*)jni_GetHdrOutputStatus },
    { const_cast<char*>("nGetDecodedVideoColorInfo"), const_cast<char*>("(J)[I"),              (void*)jni_GetDecodedVideoColorInfo },
    { const_cast<char*>("nGetVideoPlaybackDiagnostics"), const_cast<char*>("(J)[J"),            (void*)jni_GetVideoPlaybackDiagnostics },
    { const_cast<char*>("nProbeVideoColorInfo"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)[I"), (void*)jni_ProbeVideoColorInfo },
    { const_cast<char*>("nCreateLibVlcInstance"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Z)J"), (void*)jni_CreateLibVlcInstance },
    { const_cast<char*>("nDestroyLibVlcInstance"), const_cast<char*>("(J)V"),                       (void*)jni_DestroyLibVlcInstance },
    { const_cast<char*>("nOpenLibVlcMediaWithHeaders"), const_cast<char*>("(JLjava/lang/String;Ljava/lang/String;Z)I"), (void*)jni_OpenLibVlcMediaWithHeaders },
    { const_cast<char*>("nReadLibVlcVideoFrame"), const_cast<char*>("(J[I)Ljava/nio/ByteBuffer;"),  (void*)jni_ReadLibVlcVideoFrame },
    { const_cast<char*>("nUnlockLibVlcVideoFrame"), const_cast<char*>("(J)I"),                      (void*)jni_UnlockLibVlcVideoFrame },
    { const_cast<char*>("nCloseLibVlcMedia"),    const_cast<char*>("(J)V"),                         (void*)jni_CloseLibVlcMedia },
    { const_cast<char*>("nIsLibVlcEOF"),         const_cast<char*>("(J)Z"),                         (void*)jni_IsLibVlcEOF },
    { const_cast<char*>("nGetLibVlcVideoSize"),  const_cast<char*>("(J[I)V"),                       (void*)jni_GetLibVlcVideoSize },
    { const_cast<char*>("nGetLibVlcVideoFrameRate"), const_cast<char*>("(J)F"),                     (void*)jni_GetLibVlcVideoFrameRate },
    { const_cast<char*>("nSeekLibVlcMedia"),     const_cast<char*>("(JJ)I"),                        (void*)jni_SeekLibVlcMedia },
    { const_cast<char*>("nGetLibVlcMediaDuration"), const_cast<char*>("(J[J)I"),                    (void*)jni_GetLibVlcMediaDuration },
    { const_cast<char*>("nGetLibVlcMediaPosition"), const_cast<char*>("(J[J)I"),                    (void*)jni_GetLibVlcMediaPosition },
    { const_cast<char*>("nSetLibVlcPlaybackState"), const_cast<char*>("(JZZ)I"),                    (void*)jni_SetLibVlcPlaybackState },
    { const_cast<char*>("nSetLibVlcAudioVolume"), const_cast<char*>("(JF)I"),                       (void*)jni_SetLibVlcAudioVolume },
    { const_cast<char*>("nGetLibVlcAudioVolume"), const_cast<char*>("(J[F)I"),                      (void*)jni_GetLibVlcAudioVolume },
    { const_cast<char*>("nSetLibVlcPlaybackSpeed"), const_cast<char*>("(JF)I"),                     (void*)jni_SetLibVlcPlaybackSpeed },
    { const_cast<char*>("nGetLibVlcPlaybackSpeed"), const_cast<char*>("(J[F)I"),                    (void*)jni_GetLibVlcPlaybackSpeed },
    { const_cast<char*>("nSelectLibVlcAudioTrack"), const_cast<char*>("(JI)Z"),                     (void*)jni_SelectLibVlcAudioTrack },
    { const_cast<char*>("nSelectLibVlcSubtitleTrack"), const_cast<char*>("(JI)Z"),                  (void*)jni_SelectLibVlcSubtitleTrack },
    { const_cast<char*>("nDisableLibVlcSubtitles"), const_cast<char*>("(J)Z"),                      (void*)jni_DisableLibVlcSubtitles },
    { const_cast<char*>("nGetLibVlcAudioTrackDescriptions"), const_cast<char*>("(J)Ljava/lang/String;"), (void*)jni_GetLibVlcAudioTrackDescriptions },
    { const_cast<char*>("nGetLibVlcSubtitleTrackDescriptions"), const_cast<char*>("(J)Ljava/lang/String;"), (void*)jni_GetLibVlcSubtitleTrackDescriptions },
};

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return -1;

    jclass cls = env->FindClass("io/github/kdroidfilter/composemediaplayer/windows/WindowsNativeBridge");
    if (!cls) return -1;

    if (env->RegisterNatives(cls, g_methods, sizeof(g_methods) / sizeof(g_methods[0])) < 0)
        return -1;

    return JNI_VERSION_1_6;
}
