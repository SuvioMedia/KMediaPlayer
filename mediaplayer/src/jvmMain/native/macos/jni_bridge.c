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
    uint8_t* bgra_frame;
    uint8_t* read_frame;
    size_t bgra_frame_size;
    unsigned width;
    unsigned height;
    unsigned pitch;
    unsigned y_pitch;
    unsigned u_pitch;
    unsigned v_pitch;
    unsigned y_lines;
    unsigned u_lines;
    unsigned v_lines;
    size_t u_offset;
    size_t v_offset;
    int frame_ready;
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

    unsigned frame_width = (*width) & ~1u;
    unsigned frame_height = (*height) & ~1u;
    if (frame_width == 0 || frame_height == 0) return 0;

    memcpy(chroma, "I420", 4);
    unsigned y_pitch = frame_width;
    unsigned u_pitch = frame_width / 2u;
    unsigned v_pitch = frame_width / 2u;
    unsigned y_lines = frame_height;
    unsigned u_lines = frame_height / 2u;
    unsigned v_lines = frame_height / 2u;
    size_t y_size = (size_t)y_pitch * (size_t)y_lines;
    size_t u_size = (size_t)u_pitch * (size_t)u_lines;
    size_t v_size = (size_t)v_pitch * (size_t)v_lines;
    unsigned bgra_pitch = frame_width * 4u;

    pthread_mutex_lock(&player->frame_mutex);
    size_t size = y_size + u_size + v_size;
    size_t bgra_size = (size_t)bgra_pitch * (size_t)frame_height;
    uint8_t* new_frame = (uint8_t*)malloc(size);
    uint8_t* new_bgra_frame = (uint8_t*)malloc(bgra_size);
    uint8_t* new_read_frame = (uint8_t*)malloc(bgra_size);
    if (!new_frame || !new_bgra_frame || !new_read_frame) {
        free(new_frame);
        free(new_bgra_frame);
        free(new_read_frame);
        pthread_mutex_unlock(&player->frame_mutex);
        return 0;
    }
    free(player->frame);
    free(player->bgra_frame);
    free(player->read_frame);
    player->frame = new_frame;
    player->bgra_frame = new_bgra_frame;
    player->read_frame = new_read_frame;
    player->bgra_frame_size = bgra_size;
    player->width = frame_width;
    player->height = frame_height;
    player->pitch = bgra_pitch;
    player->y_pitch = y_pitch;
    player->u_pitch = u_pitch;
    player->v_pitch = v_pitch;
    player->y_lines = y_lines;
    player->u_lines = u_lines;
    player->v_lines = v_lines;
    player->u_offset = y_size;
    player->v_offset = y_size + u_size;
    player->frame_ready = 0;
    memset(player->frame, 0, size);
    memset(player->bgra_frame, 0, bgra_size);
    memset(player->read_frame, 0, bgra_size);
    pthread_mutex_unlock(&player->frame_mutex);

    pitches[0] = y_pitch;
    pitches[1] = u_pitch;
    pitches[2] = v_pitch;
    lines[0] = y_lines;
    lines[1] = u_lines;
    lines[2] = v_lines;
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
    planes[1] = player->frame ? player->frame + player->u_offset : NULL;
    planes[2] = player->frame ? player->frame + player->v_offset : NULL;
    return player;
}

static inline uint8_t clamp_u8(int value) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return (uint8_t)value;
}

static void convert_i420_to_bgra(LibVlcPlayer* player) {
    if (!player || !player->frame || !player->bgra_frame || player->width == 0 || player->height == 0) return;

    const uint8_t* y_plane = player->frame;
    const uint8_t* u_plane = player->frame + player->u_offset;
    const uint8_t* v_plane = player->frame + player->v_offset;

    for (unsigned y = 0; y < player->height; y++) {
        const uint8_t* y_row = y_plane + (size_t)y * (size_t)player->y_pitch;
        const uint8_t* u_row = u_plane + (size_t)(y / 2u) * (size_t)player->u_pitch;
        const uint8_t* v_row = v_plane + (size_t)(y / 2u) * (size_t)player->v_pitch;
        uint8_t* dst = player->bgra_frame + (size_t)y * (size_t)player->pitch;

        for (unsigned x = 0; x < player->width; x++) {
            int c = (int)y_row[x] - 16;
            int d = (int)u_row[x / 2u] - 128;
            int e = (int)v_row[x / 2u] - 128;
            if (c < 0) c = 0;

            int r = (298 * c + 409 * e + 128) >> 8;
            int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
            int b = (298 * c + 516 * d + 128) >> 8;

            dst[0] = clamp_u8(b);
            dst[1] = clamp_u8(g);
            dst[2] = clamp_u8(r);
            dst[3] = 255;
            dst += 4;
        }
    }
    player->frame_ready = 1;
}

static void vlc_unlock_cb(void* opaque, void* picture, void* const* planes) {
    (void)picture;
    (void)planes;
    LibVlcPlayer* player = (LibVlcPlayer*)opaque;
    if (player) {
        convert_i420_to_bgra(player);
        pthread_mutex_unlock(&player->frame_mutex);
    }
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

typedef struct {
    char* data;
    size_t length;
    size_t capacity;
} StringBuilder;

static int sb_reserve(StringBuilder* builder, size_t extra) {
    if (!builder) return 0;
    size_t needed = builder->length + extra + 1;
    if (needed <= builder->capacity) return 1;

    size_t next_capacity = builder->capacity > 0 ? builder->capacity : 256;
    while (next_capacity < needed) next_capacity *= 2;
    char* next_data = (char*)realloc(builder->data, next_capacity);
    if (!next_data) return 0;
    builder->data = next_data;
    builder->capacity = next_capacity;
    return 1;
}

static int sb_append_char(StringBuilder* builder, char value) {
    if (!sb_reserve(builder, 1)) return 0;
    builder->data[builder->length++] = value;
    builder->data[builder->length] = '\0';
    return 1;
}

static int sb_append_cstr(StringBuilder* builder, const char* value) {
    if (!value) return 1;
    size_t value_length = strlen(value);
    if (!sb_reserve(builder, value_length)) return 0;
    memcpy(builder->data + builder->length, value, value_length);
    builder->length += value_length;
    builder->data[builder->length] = '\0';
    return 1;
}

static int sb_append_sanitized_cstr(StringBuilder* builder, const char* value) {
    if (!value) return 1;
    for (const char* cursor = value; *cursor; cursor++) {
        char ch = (*cursor == '\n' || *cursor == '\r' || *cursor == '\t') ? ' ' : *cursor;
        if (!sb_append_char(builder, ch)) return 0;
    }
    return 1;
}

static jstring vlc_track_descriptions_to_jstring(
    JNIEnv* env,
    libvlc_track_description_t* descriptions
) {
    if (!env || !descriptions) return NULL;

    StringBuilder builder;
    memset(&builder, 0, sizeof(builder));
    int ordinal = 0;
    for (libvlc_track_description_t* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;

        char ordinal_buffer[32];
        snprintf(ordinal_buffer, sizeof(ordinal_buffer), "%d", ordinal);
        if (!sb_append_cstr(&builder, ordinal_buffer) ||
            !sb_append_char(&builder, '\t') ||
            !sb_append_sanitized_cstr(&builder, item->psz_name ? item->psz_name : "") ||
            !sb_append_char(&builder, '\n')) {
            free(builder.data);
            return NULL;
        }
        ordinal++;
    }

    if (!builder.data || builder.length == 0) {
        free(builder.data);
        return NULL;
    }

    jstring result = (*env)->NewStringUTF(env, builder.data);
    free(builder.data);
    return result;
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
    const char* memory_args[] = {
        "--no-video-title-show",
        "--no-osd",
        "--quiet",
        "--codec=avcodec",
        "--avcodec-hw=none",
        "--no-avcodec-dr",
        "--no-videotoolbox",
        "--aout=auhal"
    };
    player->instance = player->api.new_instance((int)(sizeof(memory_args) / sizeof(memory_args[0])), memory_args);
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
    free(player->bgra_frame);
    player->bgra_frame = NULL;
    free(player->read_frame);
    player->read_frame = NULL;
    player->bgra_frame_size = 0;
    player->frame_ready = 0;
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
// Optional libass renderer
// ---------------------------------------------------------------------------

typedef struct ass_library ASS_Library;
typedef struct ass_renderer ASS_Renderer;
typedef struct ass_track ASS_Track;
typedef struct ass_image {
    int w;
    int h;
    int stride;
    unsigned char* bitmap;
    uint32_t color;
    int dst_x;
    int dst_y;
    struct ass_image* next;
} ASS_Image;

typedef struct {
    void* dylib;
    ASS_Library* (*library_init)(void);
    void (*library_done)(ASS_Library*);
    ASS_Renderer* (*renderer_init)(ASS_Library*);
    void (*renderer_done)(ASS_Renderer*);
    void (*set_frame_size)(ASS_Renderer*, int, int);
    void (*set_fonts)(ASS_Renderer*, const char*, const char*, int, const char*, int);
    void (*add_font)(ASS_Library*, const char*, const char*, int);
    ASS_Track* (*new_track)(ASS_Library*);
    void (*free_track)(ASS_Track*);
    void (*process_data)(ASS_Track*, char*, int);
    ASS_Image* (*render_frame)(ASS_Renderer*, ASS_Track*, long long, int*);
} LibAssApi;

typedef struct {
    LibAssApi api;
    ASS_Library* library;
    ASS_Renderer* renderer;
    ASS_Track* track;
    int frame_width;
    int frame_height;
} LibAssContext;

static int load_libass_api(const char* libass_path, LibAssApi* api) {
    if (!libass_path || !api) return 0;
    memset(api, 0, sizeof(*api));

    void* dylib = dlopen(libass_path, RTLD_NOW | RTLD_LOCAL);
    if (!dylib) {
        fprintf(stderr, "Failed to dlopen libass: %s\n", dlerror());
        return 0;
    }

    api->dylib = dylib;
    api->library_init = (ASS_Library* (*)(void))dlsym(dylib, "ass_library_init");
    api->library_done = (void (*)(ASS_Library*))dlsym(dylib, "ass_library_done");
    api->renderer_init = (ASS_Renderer* (*)(ASS_Library*))dlsym(dylib, "ass_renderer_init");
    api->renderer_done = (void (*)(ASS_Renderer*))dlsym(dylib, "ass_renderer_done");
    api->set_frame_size = (void (*)(ASS_Renderer*, int, int))dlsym(dylib, "ass_set_frame_size");
    api->set_fonts = (void (*)(ASS_Renderer*, const char*, const char*, int, const char*, int))dlsym(dylib, "ass_set_fonts");
    api->add_font = (void (*)(ASS_Library*, const char*, const char*, int))dlsym(dylib, "ass_add_font");
    api->new_track = (ASS_Track* (*)(ASS_Library*))dlsym(dylib, "ass_new_track");
    api->free_track = (void (*)(ASS_Track*))dlsym(dylib, "ass_free_track");
    api->process_data = (void (*)(ASS_Track*, char*, int))dlsym(dylib, "ass_process_data");
    api->render_frame = (ASS_Image* (*)(ASS_Renderer*, ASS_Track*, long long, int*))dlsym(dylib, "ass_render_frame");

    if (!api->library_init || !api->library_done || !api->renderer_init || !api->renderer_done ||
        !api->set_frame_size || !api->set_fonts || !api->new_track || !api->free_track ||
        !api->process_data || !api->render_frame) {
        fprintf(stderr, "libass is missing required API symbols\n");
        dlclose(dylib);
        memset(api, 0, sizeof(*api));
        return 0;
    }

    return 1;
}

static LibAssContext* create_libass_renderer(const char* libass_path) {
    LibAssContext* ctx = (LibAssContext*)calloc(1, sizeof(LibAssContext));
    if (!ctx) return NULL;

    if (!load_libass_api(libass_path, &ctx->api)) {
        free(ctx);
        return NULL;
    }

    ctx->library = ctx->api.library_init();
    if (!ctx->library) {
        dlclose(ctx->api.dylib);
        free(ctx);
        return NULL;
    }

    ctx->renderer = ctx->api.renderer_init(ctx->library);
    if (!ctx->renderer) {
        ctx->api.library_done(ctx->library);
        dlclose(ctx->api.dylib);
        free(ctx);
        return NULL;
    }

    ctx->api.set_fonts(ctx->renderer, NULL, "Arial", 1, NULL, 1);
    return ctx;
}

static void dispose_libass_renderer(LibAssContext* ctx) {
    if (!ctx) return;
    if (ctx->track) {
        ctx->api.free_track(ctx->track);
        ctx->track = NULL;
    }
    if (ctx->renderer) {
        ctx->api.renderer_done(ctx->renderer);
        ctx->renderer = NULL;
    }
    if (ctx->library) {
        ctx->api.library_done(ctx->library);
        ctx->library = NULL;
    }
    if (ctx->api.dylib) {
        dlclose(ctx->api.dylib);
        ctx->api.dylib = NULL;
    }
    free(ctx);
}

static int configure_libass_track(LibAssContext* ctx, const char* ass_data) {
    if (!ctx || !ctx->library || !ass_data) return 0;

    if (ctx->track) {
        ctx->api.free_track(ctx->track);
        ctx->track = NULL;
    }

    ctx->track = ctx->api.new_track(ctx->library);
    if (!ctx->track) return 0;

    size_t data_length = strlen(ass_data);
    char* mutable_data = (char*)malloc(data_length + 1);
    if (!mutable_data) return 0;
    memcpy(mutable_data, ass_data, data_length + 1);
    ctx->api.process_data(ctx->track, mutable_data, (int)data_length);
    free(mutable_data);
    return 1;
}

static int add_libass_font(LibAssContext* ctx, const char* name, const char* data, int data_size) {
    if (!ctx || !ctx->library || !ctx->api.add_font || !name || !data || data_size <= 0) return 0;

    char* mutable_data = (char*)malloc((size_t)data_size);
    if (!mutable_data) {
        return 0;
    }

    memcpy(mutable_data, data, (size_t)data_size);
    ctx->api.add_font(ctx->library, name, mutable_data, data_size);
    free(mutable_data);
    return 1;
}

static inline uint8_t blend_channel(uint8_t src, uint8_t dst, int alpha) {
    return (uint8_t)((src * alpha + dst * (255 - alpha) + 127) / 255);
}

static int blend_libass_frame(
    LibAssContext* ctx,
    uint8_t* pixels,
    int row_bytes,
    int width,
    int height,
    long long time_ms
) {
    if (!ctx || !ctx->renderer || !ctx->track || !pixels || row_bytes <= 0 || width <= 0 || height <= 0) {
        return 0;
    }

    if (ctx->frame_width != width || ctx->frame_height != height) {
        ctx->api.set_frame_size(ctx->renderer, width, height);
        ctx->frame_width = width;
        ctx->frame_height = height;
    }

    int detect_change = 0;
    ASS_Image* image = ctx->api.render_frame(ctx->renderer, ctx->track, time_ms, &detect_change);
    for (ASS_Image* item = image; item; item = item->next) {
        if (!item->bitmap || item->w <= 0 || item->h <= 0) continue;

        uint8_t r = (uint8_t)((item->color >> 24) & 0xff);
        uint8_t g = (uint8_t)((item->color >> 16) & 0xff);
        uint8_t b = (uint8_t)((item->color >> 8) & 0xff);
        int color_alpha = 255 - (int)(item->color & 0xff);
        if (color_alpha <= 0) continue;

        int start_x = item->dst_x < 0 ? -item->dst_x : 0;
        int start_y = item->dst_y < 0 ? -item->dst_y : 0;
        int end_x = item->w;
        int end_y = item->h;
        if (item->dst_x + end_x > width) end_x = width - item->dst_x;
        if (item->dst_y + end_y > height) end_y = height - item->dst_y;

        for (int y = start_y; y < end_y; y++) {
            uint8_t* src_row = item->bitmap + (size_t)y * (size_t)item->stride;
            uint8_t* dst_row = pixels + (size_t)(item->dst_y + y) * (size_t)row_bytes + (size_t)(item->dst_x + start_x) * 4u;
            for (int x = start_x; x < end_x; x++) {
                int alpha = ((int)src_row[x] * color_alpha + 127) / 255;
                if (alpha <= 0) {
                    dst_row += 4;
                    continue;
                }

                dst_row[0] = blend_channel(b, dst_row[0], alpha);
                dst_row[1] = blend_channel(g, dst_row[1], alpha);
                dst_row[2] = blend_channel(r, dst_row[2], alpha);
                dst_row[3] = 255;
                dst_row += 4;
            }
        }
    }

    return 1;
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

static jlong JNICALL jni_CreateLibAssRenderer(JNIEnv* env, jclass cls, jstring libPath) {
    if (!libPath) return 0L;
    const char* cLibPath = (*env)->GetStringUTFChars(env, libPath, NULL);
    if (!cLibPath) return 0L;
    LibAssContext* ctx = create_libass_renderer(cLibPath);
    (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
    return (jlong)(uintptr_t)ctx;
}

static jboolean JNICALL jni_SetLibAssTrack(JNIEnv* env, jclass cls, jlong handle, jstring assData) {
    if (!handle || !assData) return JNI_FALSE;
    const char* cAssData = (*env)->GetStringUTFChars(env, assData, NULL);
    if (!cAssData) return JNI_FALSE;
    LibAssContext* ctx = (LibAssContext*)(uintptr_t)(uint64_t)handle;
    int result = configure_libass_track(ctx, cAssData);
    (*env)->ReleaseStringUTFChars(env, assData, cAssData);
    return (jboolean)(result != 0);
}

static jboolean JNICALL jni_AddLibAssFont(JNIEnv* env, jclass cls, jlong handle, jstring name, jbyteArray data) {
    if (!handle || !name || !data) return JNI_FALSE;
    LibAssContext* ctx = (LibAssContext*)(uintptr_t)(uint64_t)handle;
    const char* cName = (*env)->GetStringUTFChars(env, name, NULL);
    if (!cName) return JNI_FALSE;

    jsize dataSize = (*env)->GetArrayLength(env, data);
    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        (*env)->ReleaseStringUTFChars(env, name, cName);
        return JNI_FALSE;
    }

    int result = add_libass_font(ctx, cName, (const char*)bytes, (int)dataSize);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, cName);
    return (jboolean)(result != 0);
}

static jboolean JNICALL jni_BlendLibAssFrame(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong pixelsAddress,
    jint rowBytes,
    jint width,
    jint height,
    jlong timeMs
) {
    (void)env;
    (void)cls;
    LibAssContext* ctx = (LibAssContext*)(uintptr_t)(uint64_t)handle;
    uint8_t* pixels = (uint8_t*)(uintptr_t)(uint64_t)pixelsAddress;
    return (jboolean)(blend_libass_frame(ctx, pixels, rowBytes, width, height, (long long)timeMs) != 0);
}

static void JNICALL jni_DisposeLibAssRenderer(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    dispose_libass_renderer((LibAssContext*)(uintptr_t)(uint64_t)handle);
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
        if (vlc->frame_ready &&
            vlc->bgra_frame &&
            vlc->read_frame &&
            vlc->width > 0 &&
            vlc->height > 0 &&
            vlc->pitch > 0 &&
            vlc->bgra_frame_size >= (size_t)vlc->pitch * (size_t)vlc->height) {
            memcpy(vlc->read_frame, vlc->bgra_frame, (size_t)vlc->pitch * (size_t)vlc->height);
            info[0] = (int32_t)vlc->width;
            info[1] = (int32_t)vlc->height;
            info[2] = (int32_t)vlc->pitch;
            addr = vlc->read_frame;
        }
        pthread_mutex_unlock(&vlc->frame_mutex);
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

static jstring JNICALL jni_GetLibVlcAudioTrackDescriptions(JNIEnv* env, jclass cls, jlong handle) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc || !vlc->player) return NULL;

    libvlc_track_description_t* descriptions = vlc->api.audio_get_track_description(vlc->player);
    jstring result = vlc_track_descriptions_to_jstring(env, descriptions);
    if (descriptions) vlc->api.track_description_list_release(descriptions);
    return result;
}

static jstring JNICALL jni_GetLibVlcSubtitleTrackDescriptions(JNIEnv* env, jclass cls, jlong handle) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc || !vlc->player) return NULL;

    libvlc_track_description_t* descriptions = vlc->api.video_get_spu_description(vlc->player);
    jstring result = vlc_track_descriptions_to_jstring(env, descriptions);
    if (descriptions) vlc->api.track_description_list_release(descriptions);
    return result;
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
    { "nCreateLibAssRenderer",   "(Ljava/lang/String;)J",       (void*)jni_CreateLibAssRenderer },
    { "nSetLibAssTrack",         "(JLjava/lang/String;)Z",      (void*)jni_SetLibAssTrack },
    { "nAddLibAssFont",          "(JLjava/lang/String;[B)Z",    (void*)jni_AddLibAssFont },
    { "nBlendLibAssFrame",       "(JJIIIJ)Z",                   (void*)jni_BlendLibAssFrame },
    { "nDisposeLibAssRenderer",  "(J)V",                        (void*)jni_DisposeLibAssRenderer },
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
    { "nGetLibVlcAudioTrackDescriptions", "(J)Ljava/lang/String;", (void*)jni_GetLibVlcAudioTrackDescriptions },
    { "nGetLibVlcSubtitleTrackDescriptions", "(J)Ljava/lang/String;", (void*)jni_GetLibVlcSubtitleTrackDescriptions },
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
