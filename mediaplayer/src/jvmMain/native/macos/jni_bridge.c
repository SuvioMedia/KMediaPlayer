// jni_bridge.c — JNI bridge for macOS NativeVideoPlayer
// Calls Swift @_cdecl exports and registers them as JNI native methods.

#include <jni.h>
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Forward declarations of Swift C exports
// ---------------------------------------------------------------------------

extern void*  createVideoPlayer(void);
extern void   openUri(void* ctx, const char* uri);
extern void   playVideo(void* ctx);
extern void   pauseVideo(void* ctx);
extern void   setVolume(void* ctx, float volume);
extern float  getVolume(void* ctx);
extern void*  lockLatestFrame(void* ctx, int32_t* outInfo);
extern void   unlockLatestFrame(void* ctx);
extern int32_t getFrameWidth(void* ctx);
extern int32_t getFrameHeight(void* ctx);
extern int32_t setOutputSize(void* ctx, int32_t width, int32_t height);
extern float  getVideoFrameRate(void* ctx);
extern float  getScreenRefreshRate(void* ctx);
extern float  getCaptureFrameRate(void* ctx);
extern double getVideoDuration(void* ctx);
extern double getCurrentTime(void* ctx);
extern void   seekTo(void* ctx, double time);
extern void   disposeVideoPlayer(void* ctx);
extern void   setPlaybackSpeed(void* ctx, float speed);
extern float  getPlaybackSpeed(void* ctx);
extern const char* getVideoTitle(void* ctx);
extern int64_t     getVideoBitrate(void* ctx);
extern const char* getVideoMimeType(void* ctx);
extern int32_t getAudioChannels(void* ctx);
extern int32_t getAudioSampleRate(void* ctx);
extern int32_t consumeDidPlayToEnd(void* ctx);

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

static inline void* toCtx(jlong h) {
    return (void*)(uintptr_t)(uint64_t)h;
}

typedef enum {
    PLAYER_KIND_AV = 1,
    PLAYER_KIND_LIBVLC = 2
} PlayerKind;

typedef struct {
    PlayerKind kind;
    void* ctx;
} NativePlayerHandle;

static NativePlayerHandle* toHandle(jlong h) {
    return (NativePlayerHandle*)(uintptr_t)(uint64_t)h;
}

static void* avCtx(NativePlayerHandle* handle) {
    return (handle && handle->kind == PLAYER_KIND_AV) ? handle->ctx : NULL;
}

// ---------------------------------------------------------------------------
// Optional libVLC backend
// ---------------------------------------------------------------------------

typedef long long libvlc_time_t;
typedef struct libvlc_instance_t libvlc_instance_t;
typedef struct libvlc_media_t libvlc_media_t;
typedef struct libvlc_media_player_t libvlc_media_player_t;
typedef struct libvlc_track_description_t {
    int i_id;
    char* psz_name;
    struct libvlc_track_description_t* p_next;
} libvlc_track_description_t;

typedef struct {
    void* dylib;
    void* core_dylib;
    libvlc_instance_t* (*new_instance)(int, const char* const*);
    void (*release_instance)(libvlc_instance_t*);
    libvlc_media_t* (*media_new_location)(libvlc_instance_t*, const char*);
    libvlc_media_t* (*media_new_path)(libvlc_instance_t*, const char*);
    void (*media_release)(libvlc_media_t*);
    libvlc_media_player_t* (*media_player_new_from_media)(libvlc_media_t*);
    void (*media_player_release)(libvlc_media_player_t*);
    int (*media_player_play)(libvlc_media_player_t*);
    void (*media_player_pause)(libvlc_media_player_t*);
    void (*media_player_stop)(libvlc_media_player_t*);
    libvlc_time_t (*media_player_get_time)(libvlc_media_player_t*);
    void (*media_player_set_time)(libvlc_media_player_t*, libvlc_time_t);
    libvlc_time_t (*media_player_get_length)(libvlc_media_player_t*);
    int (*audio_set_volume)(libvlc_media_player_t*, int);
    int (*audio_get_volume)(libvlc_media_player_t*);
    int (*media_player_set_rate)(libvlc_media_player_t*, float);
    float (*media_player_get_rate)(libvlc_media_player_t*);
    void (*video_set_callbacks)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*);
    void (*video_set_format_callbacks)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*));
    libvlc_track_description_t* (*audio_get_track_description)(libvlc_media_player_t*);
    int (*audio_set_track)(libvlc_media_player_t*, int);
    libvlc_track_description_t* (*video_get_spu_description)(libvlc_media_player_t*);
    int (*video_set_spu)(libvlc_media_player_t*, int);
    void (*track_description_list_release)(libvlc_track_description_t*);
} LibVlcApi;

typedef struct {
    LibVlcApi api;
    libvlc_instance_t* instance;
    libvlc_media_player_t* player;
    pthread_mutex_t frame_mutex;
    uint8_t* frame;
    unsigned width;
    unsigned height;
    unsigned pitch;
    int pending_audio_ordinal;
    int pending_spu_ordinal;
} LibVlcPlayer;

static void* vlc_sym(void* dylib, const char* name) {
    return dlsym(dylib, name);
}

static void* dlopen_libvlccore_next_to_libvlc(const char* libvlc_path) {
    if (!libvlc_path) return NULL;
    const char* slash = strrchr(libvlc_path, '/');
    if (!slash) return NULL;

    char core_path[PATH_MAX];
    int directory_length = (int)(slash - libvlc_path);
    int written = snprintf(core_path, sizeof(core_path), "%.*s/libvlccore.dylib", directory_length, libvlc_path);
    if (written > 0 && written < (int)sizeof(core_path)) {
        void* core = dlopen(core_path, RTLD_NOW | RTLD_GLOBAL);
        if (core) return core;
    }

    written = snprintf(core_path, sizeof(core_path), "%.*s/libvlccore.9.dylib", directory_length, libvlc_path);
    if (written > 0 && written < (int)sizeof(core_path)) {
        return dlopen(core_path, RTLD_NOW | RTLD_GLOBAL);
    }

    return NULL;
}

static int load_libvlc_api(const char* libvlc_path, LibVlcApi* api) {
    memset(api, 0, sizeof(*api));
    api->core_dylib = dlopen_libvlccore_next_to_libvlc(libvlc_path);
    void* dylib = dlopen(libvlc_path, RTLD_NOW | RTLD_LOCAL);
    if (!dylib) {
        fprintf(stderr, "Failed to dlopen libVLC: %s\n", dlerror());
        if (api->core_dylib) dlclose(api->core_dylib);
        return 0;
    }

    api->dylib = dylib;
    api->new_instance = (libvlc_instance_t* (*)(int, const char* const*))vlc_sym(dylib, "libvlc_new");
    api->release_instance = (void (*)(libvlc_instance_t*))vlc_sym(dylib, "libvlc_release");
    api->media_new_location = (libvlc_media_t* (*)(libvlc_instance_t*, const char*))vlc_sym(dylib, "libvlc_media_new_location");
    api->media_new_path = (libvlc_media_t* (*)(libvlc_instance_t*, const char*))vlc_sym(dylib, "libvlc_media_new_path");
    api->media_release = (void (*)(libvlc_media_t*))vlc_sym(dylib, "libvlc_media_release");
    api->media_player_new_from_media = (libvlc_media_player_t* (*)(libvlc_media_t*))vlc_sym(dylib, "libvlc_media_player_new_from_media");
    api->media_player_release = (void (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_release");
    api->media_player_play = (int (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_play");
    api->media_player_pause = (void (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_pause");
    api->media_player_stop = (void (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_stop");
    api->media_player_get_time = (libvlc_time_t (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_time");
    api->media_player_set_time = (void (*)(libvlc_media_player_t*, libvlc_time_t))vlc_sym(dylib, "libvlc_media_player_set_time");
    api->media_player_get_length = (libvlc_time_t (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_length");
    api->audio_set_volume = (int (*)(libvlc_media_player_t*, int))vlc_sym(dylib, "libvlc_audio_set_volume");
    api->audio_get_volume = (int (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_audio_get_volume");
    api->media_player_set_rate = (int (*)(libvlc_media_player_t*, float))vlc_sym(dylib, "libvlc_media_player_set_rate");
    api->media_player_get_rate = (float (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_rate");
    api->video_set_callbacks = (void (*)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*))vlc_sym(dylib, "libvlc_video_set_callbacks");
    api->video_set_format_callbacks = (void (*)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*)))vlc_sym(dylib, "libvlc_video_set_format_callbacks");
    api->audio_get_track_description = (libvlc_track_description_t* (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_audio_get_track_description");
    api->audio_set_track = (int (*)(libvlc_media_player_t*, int))vlc_sym(dylib, "libvlc_audio_set_track");
    api->video_get_spu_description = (libvlc_track_description_t* (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_video_get_spu_description");
    api->video_set_spu = (int (*)(libvlc_media_player_t*, int))vlc_sym(dylib, "libvlc_video_set_spu");
    api->track_description_list_release = (void (*)(libvlc_track_description_t*))vlc_sym(dylib, "libvlc_track_description_list_release");

    if (!api->new_instance || !api->release_instance || !api->media_new_location ||
        !api->media_new_path || !api->media_release || !api->media_player_new_from_media ||
        !api->media_player_release || !api->media_player_play || !api->media_player_pause ||
        !api->media_player_stop || !api->media_player_get_time || !api->media_player_set_time ||
        !api->media_player_get_length || !api->audio_set_volume || !api->audio_get_volume ||
        !api->media_player_set_rate || !api->media_player_get_rate || !api->video_set_callbacks ||
        !api->video_set_format_callbacks || !api->audio_get_track_description ||
        !api->audio_set_track || !api->video_get_spu_description || !api->video_set_spu ||
        !api->track_description_list_release) {
        fprintf(stderr, "libVLC is missing required API symbols\n");
        dlclose(dylib);
        if (api->core_dylib) dlclose(api->core_dylib);
        memset(api, 0, sizeof(*api));
        return 0;
    }

    return 1;
}

static unsigned vlc_format_cb(void** opaque, char* chroma, unsigned* width, unsigned* height, unsigned* pitches, unsigned* lines) {
    LibVlcPlayer* player = (LibVlcPlayer*)(*opaque);
    if (!player || !width || !height || *width == 0 || *height == 0) return 0;

    memcpy(chroma, "RV32", 4);
    unsigned pitch = (*width) * 4;

    pthread_mutex_lock(&player->frame_mutex);
    size_t size = (size_t)pitch * (size_t)(*height);
    uint8_t* new_frame = (uint8_t*)realloc(player->frame, size);
    if (!new_frame) {
        pthread_mutex_unlock(&player->frame_mutex);
        return 0;
    }
    player->frame = new_frame;
    player->width = *width;
    player->height = *height;
    player->pitch = pitch;
    memset(player->frame, 0, size);
    pthread_mutex_unlock(&player->frame_mutex);

    pitches[0] = pitch;
    lines[0] = *height;
    return 1;
}

static void vlc_format_cleanup_cb(void* opaque) {
    (void)opaque;
}

static void* vlc_lock_cb(void* opaque, void** planes) {
    LibVlcPlayer* player = (LibVlcPlayer*)opaque;
    if (!player || !planes) return NULL;
    pthread_mutex_lock(&player->frame_mutex);
    planes[0] = player->frame;
    return NULL;
}

static void vlc_unlock_cb(void* opaque, void* picture, void* const* planes) {
    (void)picture;
    (void)planes;
    LibVlcPlayer* player = (LibVlcPlayer*)opaque;
    if (player) pthread_mutex_unlock(&player->frame_mutex);
}

static void vlc_display_cb(void* opaque, void* picture) {
    (void)opaque;
    (void)picture;
}

static int vlc_select_description_ordinal(libvlc_track_description_t* descriptions, int ordinal) {
    int current = 0;
    for (libvlc_track_description_t* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;
        if (current == ordinal) return item->i_id;
        current++;
    }
    return -2;
}

static int vlc_apply_audio_ordinal(LibVlcPlayer* player, int ordinal) {
    if (!player || !player->player || ordinal < 0) return 0;
    libvlc_track_description_t* descriptions = player->api.audio_get_track_description(player->player);
    int id = vlc_select_description_ordinal(descriptions, ordinal);
    if (descriptions) player->api.track_description_list_release(descriptions);
    if (id == -2) return 0;
    return player->api.audio_set_track(player->player, id) == 0;
}

static int vlc_apply_spu_ordinal(LibVlcPlayer* player, int ordinal) {
    if (!player || !player->player) return 0;
    if (ordinal < 0) return player->api.video_set_spu(player->player, -1) == 0;
    libvlc_track_description_t* descriptions = player->api.video_get_spu_description(player->player);
    int id = vlc_select_description_ordinal(descriptions, ordinal);
    if (descriptions) player->api.track_description_list_release(descriptions);
    if (id == -2) return 0;
    return player->api.video_set_spu(player->player, id) == 0;
}

static void vlc_apply_pending_tracks(LibVlcPlayer* player) {
    if (!player || !player->player) return;
    if (player->pending_audio_ordinal >= 0) {
        vlc_apply_audio_ordinal(player, player->pending_audio_ordinal);
    }
    if (player->pending_spu_ordinal >= -1) {
        vlc_apply_spu_ordinal(player, player->pending_spu_ordinal);
    }
}

static int has_uri_scheme(const char* uri) {
    return uri && strstr(uri, "://") != NULL;
}

static LibVlcPlayer* create_libvlc_player(const char* libvlc_path, const char* plugin_path) {
    if (!libvlc_path || !plugin_path) return NULL;

    LibVlcPlayer* player = (LibVlcPlayer*)calloc(1, sizeof(LibVlcPlayer));
    if (!player) return NULL;
    player->pending_audio_ordinal = -2;
    player->pending_spu_ordinal = -2;
    pthread_mutex_init(&player->frame_mutex, NULL);

    if (!load_libvlc_api(libvlc_path, &player->api)) {
        pthread_mutex_destroy(&player->frame_mutex);
        free(player);
        return NULL;
    }

    setenv("VLC_PLUGIN_PATH", plugin_path, 1);
    const char* args[] = {
        "--no-video-title-show",
        "--no-osd",
        "--quiet",
        "--vout=vmem",
        "--aout=auhal"
    };
    player->instance = player->api.new_instance((int)(sizeof(args) / sizeof(args[0])), args);
    if (!player->instance) {
        dlclose(player->api.dylib);
        if (player->api.core_dylib) dlclose(player->api.core_dylib);
        pthread_mutex_destroy(&player->frame_mutex);
        free(player);
        return NULL;
    }

    return player;
}

static void dispose_libvlc_player(LibVlcPlayer* player) {
    if (!player) return;
    if (player->player) {
        player->api.media_player_stop(player->player);
        player->api.media_player_release(player->player);
        player->player = NULL;
    }
    if (player->instance) {
        player->api.release_instance(player->instance);
        player->instance = NULL;
    }
    pthread_mutex_lock(&player->frame_mutex);
    free(player->frame);
    player->frame = NULL;
    pthread_mutex_unlock(&player->frame_mutex);
    pthread_mutex_destroy(&player->frame_mutex);
    if (player->api.dylib) dlclose(player->api.dylib);
    if (player->api.core_dylib) dlclose(player->api.core_dylib);
    free(player);
}

static void libvlc_open_uri(LibVlcPlayer* player, const char* uri) {
    if (!player || !player->instance || !uri) return;
    if (player->player) {
        player->api.media_player_stop(player->player);
        player->api.media_player_release(player->player);
        player->player = NULL;
    }

    libvlc_media_t* media = has_uri_scheme(uri)
        ? player->api.media_new_location(player->instance, uri)
        : player->api.media_new_path(player->instance, uri);
    if (!media) return;

    player->player = player->api.media_player_new_from_media(media);
    player->api.media_release(media);
    if (!player->player) return;

    player->api.video_set_callbacks(player->player, vlc_lock_cb, vlc_unlock_cb, vlc_display_cb, player);
    void* opaque = player;
    (void)opaque;
    player->api.video_set_format_callbacks(player->player, vlc_format_cb, vlc_format_cleanup_cb);

    player->api.media_player_play(player->player);
    vlc_apply_pending_tracks(player);
}

static inline LibVlcPlayer* vlcCtx(NativePlayerHandle* handle) {
    return (handle && handle->kind == PLAYER_KIND_LIBVLC) ? (LibVlcPlayer*)handle->ctx : NULL;
}

// ---------------------------------------------------------------------------
// JNI implementations
// ---------------------------------------------------------------------------

static jlong JNICALL jni_CreatePlayer(JNIEnv* env, jclass cls) {
    void* ctx = createVideoPlayer();
    if (!ctx) return 0L;
    NativePlayerHandle* handle = (NativePlayerHandle*)calloc(1, sizeof(NativePlayerHandle));
    if (!handle) {
        disposeVideoPlayer(ctx);
        return 0L;
    }
    handle->kind = PLAYER_KIND_AV;
    handle->ctx = ctx;
    return (jlong)(uintptr_t)handle;
}

static jlong JNICALL jni_CreateLibVlcPlayer(JNIEnv* env, jclass cls, jstring libPath, jstring pluginPath) {
    if (!libPath || !pluginPath) return 0L;
    const char* cLibPath = (*env)->GetStringUTFChars(env, libPath, NULL);
    if (!cLibPath) return 0L;
    const char* cPluginPath = (*env)->GetStringUTFChars(env, pluginPath, NULL);
    if (!cPluginPath) {
        (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
        return 0L;
    }

    LibVlcPlayer* player = create_libvlc_player(cLibPath, cPluginPath);
    (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
    (*env)->ReleaseStringUTFChars(env, pluginPath, cPluginPath);
    if (!player) return 0L;

    NativePlayerHandle* handle = (NativePlayerHandle*)calloc(1, sizeof(NativePlayerHandle));
    if (!handle) {
        dispose_libvlc_player(player);
        return 0L;
    }
    handle->kind = PLAYER_KIND_LIBVLC;
    handle->ctx = player;
    return (jlong)(uintptr_t)handle;
}

static void JNICALL jni_OpenUri(JNIEnv* env, jclass cls, jlong handle, jstring uri) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;
    NativePlayerHandle* native = toHandle(handle);
    if (native && native->kind == PLAYER_KIND_LIBVLC) {
        libvlc_open_uri((LibVlcPlayer*)native->ctx, cUri);
    } else {
        void* ctx = avCtx(native);
        if (ctx) openUri(ctx, cUri);
    }
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static void JNICALL jni_Play(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_play(vlc->player);
        vlc_apply_pending_tracks(vlc);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) playVideo(ctx);
}

static void JNICALL jni_Pause(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_pause(vlc->player);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) pauseVideo(ctx);
}

static void JNICALL jni_SetVolume(JNIEnv* env, jclass cls, jlong handle, jfloat volume) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        int scaled = (int)((float)volume * 100.0f);
        if (scaled < 0) scaled = 0;
        if (scaled > 100) scaled = 100;
        vlc->api.audio_set_volume(vlc->player, scaled);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) setVolume(ctx, (float)volume);
}

static jfloat JNICALL jni_GetVolume(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        return (jfloat)((float)vlc->api.audio_get_volume(vlc->player) / 100.0f);
    }
    void* ctx = avCtx(native);
    return ctx ? getVolume(ctx) : 0.0f;
}

// Locks the latest CVPixelBuffer and fills outInfo[3] = {width, height, bytesPerRow}.
// Returns the base address of the locked buffer, or 0 on failure.
// Caller MUST call jni_UnlockFrame after reading.
static jlong JNICALL jni_LockFrame(JNIEnv* env, jclass cls, jlong handle, jintArray outInfo) {
    if (!handle || !outInfo) return 0L;
    int32_t info[3] = {0, 0, 0};
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    void* addr = NULL;
    if (vlc) {
        pthread_mutex_lock(&vlc->frame_mutex);
        if (vlc->frame && vlc->width > 0 && vlc->height > 0 && vlc->pitch > 0) {
            info[0] = (int32_t)vlc->width;
            info[1] = (int32_t)vlc->height;
            info[2] = (int32_t)vlc->pitch;
            addr = vlc->frame;
        } else {
            pthread_mutex_unlock(&vlc->frame_mutex);
        }
    } else {
        void* ctx = avCtx(native);
        if (ctx) addr = lockLatestFrame(ctx, info);
    }
    if (!addr) return 0L;
    (*env)->SetIntArrayRegion(env, outInfo, 0, 3, (jint*)info);
    return (jlong)(uintptr_t)addr;
}

static void JNICALL jni_UnlockFrame(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) {
        pthread_mutex_unlock(&vlc->frame_mutex);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) unlockLatestFrame(ctx);
}

static jobject JNICALL jni_WrapPointer(JNIEnv* env, jclass cls, jlong address, jlong size) {
    if (!address || size <= 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)(uintptr_t)(uint64_t)address, (jlong)size);
}

static jint JNICALL jni_GetFrameWidth(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) return (jint)vlc->width;
    void* ctx = avCtx(native);
    return ctx ? (jint)getFrameWidth(ctx) : 0;
}

static jint JNICALL jni_GetFrameHeight(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) return (jint)vlc->height;
    void* ctx = avCtx(native);
    return ctx ? (jint)getFrameHeight(ctx) : 0;
}

static jint JNICALL jni_SetOutputSize(JNIEnv* env, jclass cls, jlong handle, jint width, jint height) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0;
    void* ctx = avCtx(native);
    return ctx ? (jint)setOutputSize(ctx, (int32_t)width, (int32_t)height) : 0;
}

static jfloat JNICALL jni_GetVideoFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0.0f;
    void* ctx = avCtx(native);
    return ctx ? getVideoFrameRate(ctx) : 0.0f;
}

static jfloat JNICALL jni_GetScreenRefreshRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 60.0f;
    void* ctx = avCtx(native);
    return ctx ? getScreenRefreshRate(ctx) : 0.0f;
}

static jfloat JNICALL jni_GetCaptureFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 30.0f;
    void* ctx = avCtx(native);
    return ctx ? getCaptureFrameRate(ctx) : 0.0f;
}

static jdouble JNICALL jni_GetVideoDuration(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        libvlc_time_t length = vlc->api.media_player_get_length(vlc->player);
        return length > 0 ? (jdouble)length / 1000.0 : 0.0;
    }
    void* ctx = avCtx(native);
    return ctx ? getVideoDuration(ctx) : 0.0;
}

static jdouble JNICALL jni_GetCurrentTime(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        libvlc_time_t time = vlc->api.media_player_get_time(vlc->player);
        return time > 0 ? (jdouble)time / 1000.0 : 0.0;
    }
    void* ctx = avCtx(native);
    return ctx ? getCurrentTime(ctx) : 0.0;
}

static void JNICALL jni_SeekTo(JNIEnv* env, jclass cls, jlong handle, jdouble time) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_set_time(vlc->player, (libvlc_time_t)(time * 1000.0));
        vlc_apply_pending_tracks(vlc);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) seekTo(ctx, (double)time);
}

static void JNICALL jni_DisposePlayer(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (!native) return;
    if (native->kind == PLAYER_KIND_LIBVLC) {
        dispose_libvlc_player((LibVlcPlayer*)native->ctx);
    } else if (native->kind == PLAYER_KIND_AV && native->ctx) {
        disposeVideoPlayer(native->ctx);
    }
    free(native);
}

static void JNICALL jni_SetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle, jfloat speed) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_set_rate(vlc->player, (float)speed);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) setPlaybackSpeed(ctx, (float)speed);
}

static jfloat JNICALL jni_GetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) return vlc->api.media_player_get_rate(vlc->player);
    void* ctx = avCtx(native);
    return ctx ? getPlaybackSpeed(ctx) : 1.0f;
}

static jstring JNICALL jni_GetVideoTitle(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return NULL;
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* s = getVideoTitle(ctx);
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return result;
}

static jlong JNICALL jni_GetVideoBitrate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0L;
    void* ctx = avCtx(native);
    return ctx ? (jlong)getVideoBitrate(ctx) : 0L;
}

static jstring JNICALL jni_GetVideoMimeType(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return NULL;
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* s = getVideoMimeType(ctx);
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return result;
}

static jint JNICALL jni_GetAudioChannels(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0;
    void* ctx = avCtx(native);
    return ctx ? (jint)getAudioChannels(ctx) : 0;
}

static jint JNICALL jni_GetAudioSampleRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0;
    void* ctx = avCtx(native);
    return ctx ? (jint)getAudioSampleRate(ctx) : 0;
}

static jboolean JNICALL jni_ConsumeDidPlayToEnd(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return JNI_FALSE;
    void* ctx = avCtx(native);
    return ctx ? (jboolean)(consumeDidPlayToEnd(ctx) != 0) : JNI_FALSE;
}

static jboolean JNICALL jni_SelectLibVlcAudioTrack(JNIEnv* env, jclass cls, jlong handle, jint ordinal) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc) return JNI_FALSE;
    vlc->pending_audio_ordinal = ordinal;
    return (jboolean)(vlc_apply_audio_ordinal(vlc, ordinal) != 0);
}

static jboolean JNICALL jni_SelectLibVlcSubtitleTrack(JNIEnv* env, jclass cls, jlong handle, jint ordinal) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc) return JNI_FALSE;
    vlc->pending_spu_ordinal = ordinal;
    return (jboolean)(vlc_apply_spu_ordinal(vlc, ordinal) != 0);
}

static jboolean JNICALL jni_DisableLibVlcSubtitles(JNIEnv* env, jclass cls, jlong handle) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc) return JNI_FALSE;
    vlc->pending_spu_ordinal = -1;
    return (jboolean)(vlc_apply_spu_ordinal(vlc, -1) != 0);
}

// ---------------------------------------------------------------------------
// Registration table
// ---------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "nCreatePlayer",           "()J",                         (void*)jni_CreatePlayer },
    { "nCreateLibVlcPlayer",     "(Ljava/lang/String;Ljava/lang/String;)J", (void*)jni_CreateLibVlcPlayer },
    { "nOpenUri",                "(JLjava/lang/String;)V",      (void*)jni_OpenUri },
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
    { "nGetVideoFrameRate",      "(J)F",                        (void*)jni_GetVideoFrameRate },
    { "nGetScreenRefreshRate",   "(J)F",                        (void*)jni_GetScreenRefreshRate },
    { "nGetCaptureFrameRate",    "(J)F",                        (void*)jni_GetCaptureFrameRate },
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
    { "nConsumeDidPlayToEnd",    "(J)Z",                        (void*)jni_ConsumeDidPlayToEnd },
    { "nSelectLibVlcAudioTrack", "(JI)Z",                       (void*)jni_SelectLibVlcAudioTrack },
    { "nSelectLibVlcSubtitleTrack", "(JI)Z",                    (void*)jni_SelectLibVlcSubtitleTrack },
    { "nDisableLibVlcSubtitles", "(J)Z",                        (void*)jni_DisableLibVlcSubtitles },
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        return -1;

    jclass cls = (*env)->FindClass(
        env, "io/github/kdroidfilter/composemediaplayer/mac/MacNativeBridge");
    if (!cls) return -1;

    int count = (int)(sizeof(g_methods) / sizeof(g_methods[0]));
    if ((*env)->RegisterNatives(env, cls, g_methods, count) < 0)
        return -1;

    return JNI_VERSION_1_6;
}
