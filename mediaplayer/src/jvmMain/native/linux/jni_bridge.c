// jni_bridge.c — JNI bridge for Linux NativeVideoPlayer
// Maps Kotlin external methods to the native C API and registers via JNI_OnLoad.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "GtkNativeVideoWidget.h"
#include "WaylandColorProbe.h"
#include "VulkanCapabilityProbe.h"
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

static jintArray JNICALL jni_GetGStreamerRuntimeInfo(JNIEnv* env, jclass cls) {
    (void)cls;
    uint32_t native_info[5] = {0, 0, 0, 0, 0};
    jint java_info[5] = {0, 0, 0, 0, 0};
    nvp_get_gstreamer_runtime_info(native_info);
    for (int index = 0; index < 5; index++) java_info[index] = (jint)native_info[index];
    jintArray result = (*env)->NewIntArray(env, 5);
    if (!result) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 5, java_info);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}

static jboolean JNICALL jni_IsGtkWaylandAdapterAvailable(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return kmp_gtk_wayland_available() ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL jni_IsGtkX11AdapterAvailable(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return kmp_gtk_x11_available() ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL jni_IsVulkanProjectionRendererAvailable(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return linux_vulkan_projection_library_available() ? JNI_TRUE : JNI_FALSE;
}

static jint JNICALL jni_QueryVulkanCapabilities(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)vulkan_capability_probe_query();
}

static jlongArray JNICALL jni_QueryGtkWaylandColorCapabilities(
    JNIEnv* env,
    jclass cls,
    jint output_id
) {
    (void)cls;
    WaylandColorProbeResult result;
    memset(&result, 0, sizeof(result));
    result.output_id = (int32_t)output_id;
    uintptr_t display = kmp_gtk_wayland_display();
    if (!display || !wayland_color_probe_query(display, (int32_t)output_id, &result)) {
        return NULL;
    }

    jlong values[5] = {
        (jlong)result.flags,
        (jlong)result.output_id,
        (jlong)result.min_luminance_x10000,
        (jlong)result.max_luminance,
        (jlong)result.reference_luminance,
    };
    jlongArray array = (*env)->NewLongArray(env, 5);
    if (!array) return NULL;
    (*env)->SetLongArrayRegion(env, array, 0, 5, values);
    return (*env)->ExceptionCheck(env) ? NULL : array;
}

static int read_projection_configuration(
    JNIEnv* env,
    jintArray integer_values,
    jfloatArray floating_values,
    LinuxVulkanProjectionConfiguration* configuration
) {
    if (!integer_values || !floating_values || !configuration ||
        (*env)->GetArrayLength(env, integer_values) < 9 ||
        (*env)->GetArrayLength(env, floating_values) < 24) return 0;
    jint integers[9];
    jfloat floats[24];
    (*env)->GetIntArrayRegion(env, integer_values, 0, 9, integers);
    (*env)->GetFloatArrayRegion(env, floating_values, 0, 24, floats);
    if ((*env)->ExceptionCheck(env)) return 0;
    memset(configuration, 0, sizeof(*configuration));
    configuration->transfer = integers[0];
    configuration->projection = integers[1];
    configuration->stereo = integers[2];
    configuration->eye_order = integers[3];
    configuration->rotation = integers[4];
    configuration->color_range = integers[5];
    configuration->color_matrix = integers[6];
    configuration->color_primaries = integers[7];
    configuration->applies_hdr10_plus = integers[8] != 0;
    configuration->output_transfer = integers[0];
    configuration->fov_degrees = floats[0];
    configuration->yaw_degrees = floats[1];
    configuration->pitch_degrees = floats[2];
    configuration->roll_degrees = floats[3];
    configuration->zoom = floats[4];
    configuration->crop_left = floats[5];
    configuration->crop_top = floats[6];
    configuration->crop_right = floats[7];
    configuration->crop_bottom = floats[8];
    configuration->source_peak_nits = floats[9];
    configuration->mastering_red_x = floats[10];
    configuration->mastering_red_y = floats[11];
    configuration->mastering_green_x = floats[12];
    configuration->mastering_green_y = floats[13];
    configuration->mastering_blue_x = floats[14];
    configuration->mastering_blue_y = floats[15];
    configuration->mastering_white_x = floats[16];
    configuration->mastering_white_y = floats[17];
    configuration->mastering_min_luminance_nits = floats[18];
    configuration->mastering_max_luminance_nits = floats[19];
    configuration->max_content_light_level_nits = floats[20];
    configuration->max_frame_average_light_level_nits = floats[21];
    configuration->target_peak_nits = floats[22];
    configuration->reference_white_nits = floats[23];
    return 1;
}

static jlong JNICALL jni_CreateNativeVideoWidget(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jboolean libvlc,
    jintArray integer_values,
    jfloatArray floating_values
) {
    (void)cls;
    if (!handle) return 0L;
    LinuxVulkanProjectionConfiguration configuration;
    LinuxVulkanProjectionConfiguration* configuration_ptr = NULL;
    if (integer_values && floating_values &&
        read_projection_configuration(env, integer_values, floating_values, &configuration)) {
        configuration_ptr = &configuration;
    }
    void* widget = kmp_gtk_video_widget_create(
        libvlc == JNI_TRUE ? NULL : toCtx(handle),
        libvlc == JNI_TRUE ? toLibVlc(handle) : NULL,
        configuration_ptr);
    return (jlong)(uintptr_t)widget;
}

static void JNICALL jni_DisposeNativeVideoWidget(
    JNIEnv* env,
    jclass cls,
    jlong widget
) {
    (void)env;
    (void)cls;
    if (widget) kmp_gtk_video_widget_destroy((void*)(uintptr_t)(uint64_t)widget);
}

static void JNICALL jni_UpdateWaylandHdrProjectionConfiguration(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jintArray integer_values,
    jfloatArray floating_values
) {
    (void)cls;
    if (!handle) return;
    LinuxVulkanProjectionConfiguration configuration;
    if (!read_projection_configuration(env, integer_values, floating_values, &configuration)) return;
    nvp_update_wayland_projection_configuration(toCtx(handle), &configuration);
}

static jint JNICALL jni_GetWaylandHdrOutputState(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    return handle ? (jint)nvp_get_wayland_output_state(toCtx(handle)) : 0;
}

static jintArray JNICALL jni_GetDecodedVideoColorInfo(JNIEnv* env, jclass cls, jlong handle) {
    (void)cls;
    if (!handle) return NULL;
    int32_t native_values[7] = {0};
    nvp_get_decoded_color_info(toCtx(handle), native_values);
    jint values[7];
    for (int index = 0; index < 7; index++) values[index] = (jint)native_values[index];
    jintArray result = (*env)->NewIntArray(env, 7);
    if (!result) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 7, values);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}

static jint JNICALL jni_GetWaylandOutputId(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    (void)handle;
    return -1;
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

static jboolean JNICALL jni_ConfigureTextureOutput(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jint width,
    jint height,
    jboolean input_p010,
    jboolean output_hdr,
    jintArray integer_values,
    jfloatArray floating_values
) {
    (void)cls;
    if (!handle) return JNI_FALSE;
    LinuxVulkanProjectionConfiguration configuration;
    if (!read_projection_configuration(env, integer_values, floating_values, &configuration)) {
        return JNI_FALSE;
    }
    return nvp_configure_texture_output(
        toCtx(handle),
        (int32_t)width,
        (int32_t)height,
        input_p010 == JNI_TRUE,
        output_hdr == JNI_TRUE,
        &configuration
    ) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_DetachTextureOutput(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    if (handle) nvp_detach_texture_output(toCtx(handle));
}

static jlongArray JNICALL jni_AcquireTextureFrame(JNIEnv* env, jclass cls, jlong handle) {
    (void)cls;
    if (!handle) return NULL;
    LinuxVulkanTextureFrame frame;
    memset(&frame, 0, sizeof(frame));
    if (!nvp_acquire_texture_frame(toCtx(handle), &frame)) return NULL;
    jlong values[10] = {
        (jlong)frame.serial,
        (jlong)frame.generation,
        (jlong)frame.width,
        (jlong)frame.height,
        (jlong)frame.fourcc,
        (jlong)frame.dma_buf_fd,
        (jlong)frame.stride,
        (jlong)frame.offset,
        (jlong)frame.modifier,
        (jlong)frame.acquire_fence_fd,
    };
    jlongArray result = (*env)->NewLongArray(env, 10);
    if (!result) {
        nvp_release_texture_frame(
            toCtx(handle),
            frame.generation,
            frame.serial,
            frame.dma_buf_fd,
            frame.acquire_fence_fd
        );
        return NULL;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 10, values);
    if ((*env)->ExceptionCheck(env)) {
        nvp_release_texture_frame(
            toCtx(handle),
            frame.generation,
            frame.serial,
            frame.dma_buf_fd,
            frame.acquire_fence_fd
        );
        return NULL;
    }
    return result;
}

static void JNICALL jni_ReleaseTextureFrame(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong generation,
    jlong serial,
    jint dma_buf_fd,
    jint release_fence_fd
) {
    (void)env;
    (void)cls;
    nvp_release_texture_frame(
        handle ? toCtx(handle) : NULL,
        (uint64_t)generation,
        (uint64_t)serial,
        (int32_t)dma_buf_fd,
        (int32_t)release_fence_fd
    );
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
    if (handle) {
        VideoPlayer* player = toCtx(handle);
        nvp_detach_texture_output(player);
        nvp_detach_wayland_output(player);
        nvp_destroy(player);
    }
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

static jstring JNICALL jni_GetVideoDecoderName(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* s = nvp_get_video_decoder_name(toCtx(handle));
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
    { "nGetGStreamerRuntimeInfo", "()[I",                      (void*)jni_GetGStreamerRuntimeInfo },
    { "nIsGtkWaylandAdapterAvailable", "()Z",                   (void*)jni_IsGtkWaylandAdapterAvailable },
    { "nIsGtkX11AdapterAvailable", "()Z",                       (void*)jni_IsGtkX11AdapterAvailable },
    { "nIsVulkanProjectionRendererAvailable", "()Z",           (void*)jni_IsVulkanProjectionRendererAvailable },
    { "nQueryVulkanCapabilities", "()I",                       (void*)jni_QueryVulkanCapabilities },
    { "nQueryGtkWaylandColorCapabilities", "(I)[J",            (void*)jni_QueryGtkWaylandColorCapabilities },
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
    { "nCreateNativeVideoWidget", "(JZ[I[F)J",                  (void*)jni_CreateNativeVideoWidget },
    { "nDisposeNativeVideoWidget", "(J)V",                      (void*)jni_DisposeNativeVideoWidget },
    { "nUpdateWaylandHdrProjectionConfiguration", "(J[I[F)V", (void*)jni_UpdateWaylandHdrProjectionConfiguration },
    { "nGetWaylandHdrOutputState", "(J)I",                      (void*)jni_GetWaylandHdrOutputState },
    { "nGetDecodedVideoColorInfo", "(J)[I",                    (void*)jni_GetDecodedVideoColorInfo },
    { "nGetWaylandOutputId",     "(J)I",                        (void*)jni_GetWaylandOutputId },
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
    { "nConfigureTextureOutput", "(JIIZZ[I[F)Z",               (void*)jni_ConfigureTextureOutput },
    { "nDetachTextureOutput",    "(J)V",                        (void*)jni_DetachTextureOutput },
    { "nAcquireTextureFrame",    "(J)[J",                       (void*)jni_AcquireTextureFrame },
    { "nReleaseTextureFrame",    "(JJJII)V",                   (void*)jni_ReleaseTextureFrame },
    { "nGetVideoDuration",       "(J)D",                        (void*)jni_GetVideoDuration },
    { "nGetCurrentTime",         "(J)D",                        (void*)jni_GetCurrentTime },
    { "nSeekTo",                 "(JD)V",                       (void*)jni_SeekTo },
    { "nDisposePlayer",          "(J)V",                        (void*)jni_DisposePlayer },
    { "nSetPlaybackSpeed",       "(JF)V",                       (void*)jni_SetPlaybackSpeed },
    { "nGetPlaybackSpeed",       "(J)F",                        (void*)jni_GetPlaybackSpeed },
    { "nGetVideoTitle",          "(J)Ljava/lang/String;",       (void*)jni_GetVideoTitle },
    { "nGetVideoBitrate",        "(J)J",                        (void*)jni_GetVideoBitrate },
    { "nGetVideoMimeType",       "(J)Ljava/lang/String;",       (void*)jni_GetVideoMimeType },
    { "nGetVideoDecoderName",    "(J)Ljava/lang/String;",       (void*)jni_GetVideoDecoderName },
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
