// jni_bridge.c — JNI bridge for Linux NativeVideoPlayer
// Maps Kotlin external methods to the native C API and registers via JNI_OnLoad.

#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "LibVlcCanvas.h"
#include "NativeVideoPlayer.h"

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

static inline VideoPlayer* toCtx(jlong h) {
    return (VideoPlayer*)(uintptr_t)(uint64_t)h;
}

static inline LibVlcCanvasPlayer* toLibVlc(jlong h) {
    return (LibVlcCanvasPlayer*)(uintptr_t)(uint64_t)h;
}

static uint32_t awt_component_xwindow(JNIEnv* env, jobject component) {
    if (!component) return 0;

    JAWT awt;
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_4;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) return 0;

    JAWT_DrawingSurface* surface = awt.GetDrawingSurface(env, component);
    if (!surface) return 0;

    uint32_t xwindow = 0;
    jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo* surface_info = surface->GetDrawingSurfaceInfo(surface);
        if (surface_info && surface_info->platformInfo) {
            JAWT_X11DrawingSurfaceInfo* x11_info = (JAWT_X11DrawingSurfaceInfo*)surface_info->platformInfo;
            xwindow = (uint32_t)x11_info->drawable;
        }
        if (surface_info) {
            surface->FreeDrawingSurfaceInfo(surface_info);
        }
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    return xwindow;
}

// ---------------------------------------------------------------------------
// JNI implementations
// ---------------------------------------------------------------------------

static jlong JNICALL jni_CreatePlayer(JNIEnv* env, jclass cls) {
    VideoPlayer* p = nvp_create();
    return p ? (jlong)(uintptr_t)p : 0L;
}

static jint JNICALL jni_GetNativeVersion(JNIEnv* env, jclass cls) {
    return (jint)nvp_get_native_version();
}

static jlong JNICALL jni_CreateLibVlcPlayer(
    JNIEnv* env,
    jclass cls,
    jstring libPath,
    jstring pluginPath,
    jboolean nativeVideoOutput
) {
    if (!libPath || !pluginPath) return 0L;
    const char* cLibPath = (*env)->GetStringUTFChars(env, libPath, NULL);
    if (!cLibPath) return 0L;
    const char* cPluginPath = (*env)->GetStringUTFChars(env, pluginPath, NULL);
    if (!cPluginPath) {
        (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
        return 0L;
    }
    LibVlcCanvasPlayer* p = lvc_create(cLibPath, cPluginPath, nativeVideoOutput == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
    (*env)->ReleaseStringUTFChars(env, pluginPath, cPluginPath);
    return p ? (jlong)(uintptr_t)p : 0L;
}

static jboolean JNICALL jni_AttachLibVlcNativeView(JNIEnv* env, jclass cls, jlong handle, jobject component) {
    if (!handle || !component) return JNI_FALSE;
    uint32_t xwindow = awt_component_xwindow(env, component);
    if (!xwindow) return JNI_FALSE;
    return (jboolean)(lvc_set_native_window(toLibVlc(handle), xwindow) != 0);
}

static void JNICALL jni_DetachLibVlcNativeView(JNIEnv* env, jclass cls, jlong handle, jobject component) {
    if (handle) {
        lvc_set_native_window(toLibVlc(handle), 0);
    }
}

static jboolean JNICALL jni_OpenLibVlcUriWithHeaders(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jstring uri,
    jstring requestHeaders,
    jboolean startPlayback
) {
    if (!handle || !uri) return JNI_FALSE;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return JNI_FALSE;
    const char* cHeaders = requestHeaders ? (*env)->GetStringUTFChars(env, requestHeaders, NULL) : NULL;
    int result = lvc_open_uri_with_headers(toLibVlc(handle), cUri, cHeaders, startPlayback == JNI_TRUE);
    if (cHeaders) (*env)->ReleaseStringUTFChars(env, requestHeaders, cHeaders);
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
    return (jboolean)(result != 0);
}

static void JNICALL jni_PlayLibVlc(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) lvc_play(toLibVlc(handle));
}

static void JNICALL jni_PauseLibVlc(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) lvc_pause(toLibVlc(handle));
}

static void JNICALL jni_SetLibVlcVolume(JNIEnv* env, jclass cls, jlong handle, jfloat volume) {
    if (handle) lvc_set_volume(toLibVlc(handle), (float)volume);
}

static jfloat JNICALL jni_GetLibVlcVolume(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? lvc_get_volume(toLibVlc(handle)) : 0.0f;
}

static jlong JNICALL jni_LockLibVlcFrame(JNIEnv* env, jclass cls, jlong handle, jintArray outInfo) {
    if (!handle || !outInfo) return 0L;
    int32_t info[3] = {0, 0, 0};
    void* ptr = lvc_lock_frame(toLibVlc(handle), info);
    if (!ptr) return 0L;
    (*env)->SetIntArrayRegion(env, outInfo, 0, 3, (jint*)info);
    return (jlong)(uintptr_t)ptr;
}

static void JNICALL jni_UnlockLibVlcFrame(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) lvc_unlock_frame(toLibVlc(handle));
}

static jint JNICALL jni_GetLibVlcFrameWidth(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)lvc_get_frame_width(toLibVlc(handle)) : 0;
}

static jint JNICALL jni_GetLibVlcFrameHeight(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)lvc_get_frame_height(toLibVlc(handle)) : 0;
}

static void JNICALL jni_SeekLibVlcTo(JNIEnv* env, jclass cls, jlong handle, jdouble time) {
    if (handle) lvc_seek_to(toLibVlc(handle), (double)time);
}

static void JNICALL jni_DisposeLibVlcPlayer(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) lvc_destroy(toLibVlc(handle));
}

static void JNICALL jni_SetLibVlcPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle, jfloat speed) {
    if (handle) lvc_set_playback_speed(toLibVlc(handle), (float)speed);
}

static jfloat JNICALL jni_GetLibVlcPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? lvc_get_playback_speed(toLibVlc(handle)) : 1.0f;
}

static jdouble JNICALL jni_GetLibVlcVideoDuration(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? lvc_get_duration(toLibVlc(handle)) : 0.0;
}

static jdouble JNICALL jni_GetLibVlcCurrentTime(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? lvc_get_current_time(toLibVlc(handle)) : 0.0;
}

static jfloat JNICALL jni_GetLibVlcFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? lvc_get_frame_rate(toLibVlc(handle)) : 0.0f;
}

static jboolean JNICALL jni_ConsumeLibVlcDidPlayToEnd(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jboolean)(lvc_consume_did_play_to_end(toLibVlc(handle)) != 0) : JNI_FALSE;
}

static jboolean JNICALL jni_SelectLibVlcAudioTrack(JNIEnv* env, jclass cls, jlong handle, jint ordinal) {
    return handle ? (jboolean)(lvc_select_audio_track(toLibVlc(handle), (int32_t)ordinal) != 0) : JNI_FALSE;
}

static jboolean JNICALL jni_SelectLibVlcSubtitleTrack(JNIEnv* env, jclass cls, jlong handle, jint ordinal) {
    return handle ? (jboolean)(lvc_select_subtitle_track(toLibVlc(handle), (int32_t)ordinal) != 0) : JNI_FALSE;
}

static jboolean JNICALL jni_DisableLibVlcSubtitles(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jboolean)(lvc_disable_subtitles(toLibVlc(handle)) != 0) : JNI_FALSE;
}

static jstring JNICALL jni_GetLibVlcAudioTrackDescriptions(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* value = lvc_get_audio_track_descriptions(toLibVlc(handle));
    if (!value) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    free(value);
    return result;
}

static jstring JNICALL jni_GetLibVlcSubtitleTrackDescriptions(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* value = lvc_get_subtitle_track_descriptions(toLibVlc(handle));
    if (!value) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    free(value);
    return result;
}

static void JNICALL jni_OpenUri(JNIEnv* env, jclass cls, jlong handle, jstring uri) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;
    nvp_open_uri(toCtx(handle), cUri);
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static void JNICALL jni_OpenUriWithHeaders(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jstring uri,
    jstring requestHeaders
) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;

    const char* cHeaders = NULL;
    if (requestHeaders) {
        cHeaders = (*env)->GetStringUTFChars(env, requestHeaders, NULL);
        if (!cHeaders) {
            (*env)->ReleaseStringUTFChars(env, uri, cUri);
            return;
        }
    }

    nvp_open_uri_with_headers(toCtx(handle), cUri, cHeaders);

    if (cHeaders) {
        (*env)->ReleaseStringUTFChars(env, requestHeaders, cHeaders);
    }
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static void JNICALL jni_Play(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_play(toCtx(handle));
}

static void JNICALL jni_Pause(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_pause(toCtx(handle));
}

static void JNICALL jni_SetVolume(JNIEnv* env, jclass cls, jlong handle, jfloat volume) {
    if (handle) nvp_set_volume(toCtx(handle), (float)volume);
}

static jfloat JNICALL jni_GetVolume(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_volume(toCtx(handle)) : 0.0f;
}

static jlong JNICALL jni_LockFrame(JNIEnv* env, jclass cls, jlong handle, jintArray outInfo) {
    if (!handle || !outInfo) return 0L;
    int32_t info[3] = {0, 0, 0};
    VideoPlayer* p = toCtx(handle);
    void* ptr = nvp_lock_latest_frame(p, info);
    if (!ptr) return 0L;

    (*env)->SetIntArrayRegion(env, outInfo, 0, 3, (jint*)info);
    if ((*env)->ExceptionCheck(env)) {
        nvp_unlock_latest_frame(p);
        return 0L;
    }
    return (jlong)(uintptr_t)ptr;
}

static void JNICALL jni_UnlockFrame(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_unlock_latest_frame(toCtx(handle));
}

static jobject JNICALL jni_WrapPointer(JNIEnv* env, jclass cls, jlong address, jlong size) {
    if (!address || size <= 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)(uintptr_t)(uint64_t)address, (jlong)size);
}

static jint JNICALL jni_GetFrameWidth(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_frame_width(toCtx(handle)) : 0;
}

static jint JNICALL jni_GetFrameHeight(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_frame_height(toCtx(handle)) : 0;
}

static jint JNICALL jni_SetOutputSize(JNIEnv* env, jclass cls, jlong handle, jint width, jint height) {
    return handle ? (jint)nvp_set_output_size(toCtx(handle), (int32_t)width, (int32_t)height) : 0;
}

static jdouble JNICALL jni_GetVideoDuration(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_duration(toCtx(handle)) : 0.0;
}

static jdouble JNICALL jni_GetCurrentTime(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_current_time(toCtx(handle)) : 0.0;
}

static void JNICALL jni_SeekTo(JNIEnv* env, jclass cls, jlong handle, jdouble time) {
    if (handle) nvp_seek_to(toCtx(handle), (double)time);
}

static void JNICALL jni_DisposePlayer(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_destroy(toCtx(handle));
}

static void JNICALL jni_SetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle, jfloat speed) {
    if (handle) nvp_set_playback_speed(toCtx(handle), (float)speed);
}

static jfloat JNICALL jni_GetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_playback_speed(toCtx(handle)) : 1.0f;
}

static jstring JNICALL jni_GetVideoTitle(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* s = nvp_get_title(toCtx(handle));
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free(s);
    return result;
}

static jlong JNICALL jni_GetVideoBitrate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jlong)nvp_get_bitrate(toCtx(handle)) : 0L;
}

static jstring JNICALL jni_GetVideoMimeType(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* s = nvp_get_mime_type(toCtx(handle));
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free(s);
    return result;
}

static jint JNICALL jni_GetAudioChannels(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_audio_channels(toCtx(handle)) : 0;
}

static jint JNICALL jni_GetAudioSampleRate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_audio_sample_rate(toCtx(handle)) : 0;
}

static jfloat JNICALL jni_GetFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_frame_rate(toCtx(handle)) : 0.0f;
}

static jboolean JNICALL jni_ConsumeDidPlayToEnd(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jboolean)(nvp_consume_did_play_to_end(toCtx(handle)) != 0) : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Registration table
// ---------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "nGetNativeVersion",       "()I",                         (void*)jni_GetNativeVersion },
    { "nCreatePlayer",           "()J",                         (void*)jni_CreatePlayer },
    { "nCreateLibVlcPlayer",     "(Ljava/lang/String;Ljava/lang/String;Z)J", (void*)jni_CreateLibVlcPlayer },
    { "nOpenLibVlcUriWithHeaders", "(JLjava/lang/String;Ljava/lang/String;Z)Z", (void*)jni_OpenLibVlcUriWithHeaders },
    { "nPlayLibVlc",             "(J)V",                        (void*)jni_PlayLibVlc },
    { "nPauseLibVlc",            "(J)V",                        (void*)jni_PauseLibVlc },
    { "nSetLibVlcVolume",        "(JF)V",                       (void*)jni_SetLibVlcVolume },
    { "nGetLibVlcVolume",        "(J)F",                        (void*)jni_GetLibVlcVolume },
    { "nLockLibVlcFrame",        "(J[I)J",                      (void*)jni_LockLibVlcFrame },
    { "nUnlockLibVlcFrame",      "(J)V",                        (void*)jni_UnlockLibVlcFrame },
    { "nGetLibVlcFrameWidth",    "(J)I",                        (void*)jni_GetLibVlcFrameWidth },
    { "nGetLibVlcFrameHeight",   "(J)I",                        (void*)jni_GetLibVlcFrameHeight },
    { "nSeekLibVlcTo",           "(JD)V",                       (void*)jni_SeekLibVlcTo },
    { "nDisposeLibVlcPlayer",    "(J)V",                        (void*)jni_DisposeLibVlcPlayer },
    { "nSetLibVlcPlaybackSpeed", "(JF)V",                       (void*)jni_SetLibVlcPlaybackSpeed },
    { "nGetLibVlcPlaybackSpeed", "(J)F",                        (void*)jni_GetLibVlcPlaybackSpeed },
    { "nGetLibVlcVideoDuration", "(J)D",                        (void*)jni_GetLibVlcVideoDuration },
    { "nGetLibVlcCurrentTime",   "(J)D",                        (void*)jni_GetLibVlcCurrentTime },
    { "nGetLibVlcFrameRate",     "(J)F",                        (void*)jni_GetLibVlcFrameRate },
    { "nConsumeLibVlcDidPlayToEnd", "(J)Z",                     (void*)jni_ConsumeLibVlcDidPlayToEnd },
    { "nSelectLibVlcAudioTrack", "(JI)Z",                       (void*)jni_SelectLibVlcAudioTrack },
    { "nSelectLibVlcSubtitleTrack", "(JI)Z",                    (void*)jni_SelectLibVlcSubtitleTrack },
    { "nDisableLibVlcSubtitles", "(J)Z",                        (void*)jni_DisableLibVlcSubtitles },
    { "nGetLibVlcAudioTrackDescriptions", "(J)Ljava/lang/String;", (void*)jni_GetLibVlcAudioTrackDescriptions },
    { "nGetLibVlcSubtitleTrackDescriptions", "(J)Ljava/lang/String;", (void*)jni_GetLibVlcSubtitleTrackDescriptions },
    { "nAttachLibVlcNativeView", "(JLjava/awt/Component;)Z",     (void*)jni_AttachLibVlcNativeView },
    { "nDetachLibVlcNativeView", "(JLjava/awt/Component;)V",     (void*)jni_DetachLibVlcNativeView },
    { "nOpenUri",                "(JLjava/lang/String;)V",      (void*)jni_OpenUri },
    { "nOpenUriWithHeaders",     "(JLjava/lang/String;Ljava/lang/String;)V", (void*)jni_OpenUriWithHeaders },
    { "nPlay",                   "(J)V",                        (void*)jni_Play },
    { "nPause",                  "(J)V",                        (void*)jni_Pause },
    { "nSetVolume",              "(JF)V",                       (void*)jni_SetVolume },
    { "nGetVolume",              "(J)F",                        (void*)jni_GetVolume },
    { "nLockFrame",              "(J[I)J",                      (void*)jni_LockFrame },
    { "nUnlockFrame",            "(J)V",                        (void*)jni_UnlockFrame },
    { "nWrapPointer",            "(JJ)Ljava/nio/ByteBuffer;",   (void*)jni_WrapPointer },
    { "nGetFrameWidth",          "(J)I",                        (void*)jni_GetFrameWidth },
    { "nGetFrameHeight",         "(J)I",                        (void*)jni_GetFrameHeight },
    { "nSetOutputSize",          "(JII)I",                      (void*)jni_SetOutputSize },
    { "nGetVideoDuration",       "(J)D",                        (void*)jni_GetVideoDuration },
    { "nGetCurrentTime",         "(J)D",                        (void*)jni_GetCurrentTime },
    { "nSeekTo",                 "(JD)V",                       (void*)jni_SeekTo },
    { "nDisposePlayer",          "(J)V",                        (void*)jni_DisposePlayer },
    { "nSetPlaybackSpeed",       "(JF)V",                       (void*)jni_SetPlaybackSpeed },
    { "nGetPlaybackSpeed",       "(J)F",                        (void*)jni_GetPlaybackSpeed },
    { "nGetVideoTitle",          "(J)Ljava/lang/String;",       (void*)jni_GetVideoTitle },
    { "nGetVideoBitrate",        "(J)J",                        (void*)jni_GetVideoBitrate },
    { "nGetVideoMimeType",       "(J)Ljava/lang/String;",       (void*)jni_GetVideoMimeType },
    { "nGetAudioChannels",       "(J)I",                        (void*)jni_GetAudioChannels },
    { "nGetAudioSampleRate",     "(J)I",                        (void*)jni_GetAudioSampleRate },
    { "nGetFrameRate",           "(J)F",                        (void*)jni_GetFrameRate },
    { "nConsumeDidPlayToEnd",    "(J)Z",                        (void*)jni_ConsumeDidPlayToEnd },
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        return -1;

    jclass cls = (*env)->FindClass(
        env, "io/github/kdroidfilter/composemediaplayer/linux/LinuxNativeBridge");
    if (!cls) return -1;

    int count = (int)(sizeof(g_methods) / sizeof(g_methods[0]));
    if ((*env)->RegisterNatives(env, cls, g_methods, count) < 0)
        return -1;

    return JNI_VERSION_1_6;
}
