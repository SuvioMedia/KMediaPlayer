#include "LibVlcCanvas.h"

#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

typedef long long libvlc_time_t;
typedef struct libvlc_instance_t libvlc_instance_t;
typedef struct libvlc_media_t libvlc_media_t;
typedef struct libvlc_media_player_t libvlc_media_player_t;
typedef struct libvlc_track_description_t {
    int i_id;
    char* psz_name;
    struct libvlc_track_description_t* p_next;
} libvlc_track_description_t;

typedef enum {
    LIBVLC_STATE_NOTHING_SPECIAL = 0,
    LIBVLC_STATE_OPENING = 1,
    LIBVLC_STATE_BUFFERING = 2,
    LIBVLC_STATE_PLAYING = 3,
    LIBVLC_STATE_PAUSED = 4,
    LIBVLC_STATE_STOPPED = 5,
    LIBVLC_STATE_ENDED = 6,
    LIBVLC_STATE_ERROR = 7
} libvlc_state_t;

typedef struct {
    void* dylib;
    void* core_dylib;
    libvlc_instance_t* (*new_instance)(int, const char* const*);
    void (*release_instance)(libvlc_instance_t*);
    libvlc_media_t* (*media_new_location)(libvlc_instance_t*, const char*);
    libvlc_media_t* (*media_new_path)(libvlc_instance_t*, const char*);
    void (*media_add_option)(libvlc_media_t*, const char*);
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
    libvlc_state_t (*media_player_get_state)(libvlc_media_player_t*);
    void (*media_player_set_xwindow)(libvlc_media_player_t*, uint32_t);
    void (*video_set_callbacks)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*);
    void (*video_set_format_callbacks)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*));
    libvlc_track_description_t* (*audio_get_track_description)(libvlc_media_player_t*);
    int (*audio_set_track)(libvlc_media_player_t*, int);
    libvlc_track_description_t* (*video_get_spu_description)(libvlc_media_player_t*);
    int (*video_set_spu)(libvlc_media_player_t*, int);
    void (*track_description_list_release)(libvlc_track_description_t*);
} LibVlcApi;

struct LibVlcCanvasPlayer {
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
    size_t u_offset;
    size_t v_offset;
    int frame_ready;
    int native_video_output;
    uint32_t native_window;
    int pending_audio_ordinal;
    int pending_spu_ordinal;
    int did_play_to_end;
};

static int native_logging_enabled(void) {
    static int initialized = 0;
    static int enabled = 0;
    if (!initialized) {
        const char* value = getenv("COMPOSE_MEDIA_PLAYER_NATIVE_LOGGING");
        enabled = value && value[0] && (
            strcasecmp(value, "1") == 0 ||
            strcasecmp(value, "true") == 0 ||
            strcasecmp(value, "yes") == 0 ||
            strcasecmp(value, "on") == 0
        );
        initialized = 1;
    }
    return enabled;
}

static void native_logf(const char* format, ...) {
    if (!native_logging_enabled()) return;

    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
}

static void* vlc_sym(void* dylib, const char* name) {
    return dlsym(dylib, name);
}

static void* dlopen_libvlccore_next_to_libvlc(const char* libvlc_path) {
    if (!libvlc_path) return NULL;
    const char* slash = strrchr(libvlc_path, '/');
    if (!slash) return NULL;

    const char* names[] = {"libvlccore.so.9", "libvlccore.so", "libvlccore.so.8"};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        char core_path[PATH_MAX];
        int directory_length = (int)(slash - libvlc_path);
        int written = snprintf(core_path, sizeof(core_path), "%.*s/%s", directory_length, libvlc_path, names[i]);
        if (written > 0 && written < (int)sizeof(core_path)) {
            void* core = dlopen(core_path, RTLD_NOW | RTLD_GLOBAL);
            if (core) return core;
        }
    }
    return NULL;
}

static int load_libvlc_api(const char* libvlc_path, LibVlcApi* api) {
    memset(api, 0, sizeof(*api));
    api->core_dylib = dlopen_libvlccore_next_to_libvlc(libvlc_path);
    api->dylib = dlopen(libvlc_path, RTLD_NOW | RTLD_LOCAL);
    if (!api->dylib) {
        native_logf("Failed to dlopen libVLC: %s\n", dlerror());
        if (api->core_dylib) dlclose(api->core_dylib);
        memset(api, 0, sizeof(*api));
        return 0;
    }

    api->new_instance = (libvlc_instance_t* (*)(int, const char* const*))vlc_sym(api->dylib, "libvlc_new");
    api->release_instance = (void (*)(libvlc_instance_t*))vlc_sym(api->dylib, "libvlc_release");
    api->media_new_location = (libvlc_media_t* (*)(libvlc_instance_t*, const char*))vlc_sym(api->dylib, "libvlc_media_new_location");
    api->media_new_path = (libvlc_media_t* (*)(libvlc_instance_t*, const char*))vlc_sym(api->dylib, "libvlc_media_new_path");
    api->media_add_option = (void (*)(libvlc_media_t*, const char*))vlc_sym(api->dylib, "libvlc_media_add_option");
    api->media_release = (void (*)(libvlc_media_t*))vlc_sym(api->dylib, "libvlc_media_release");
    api->media_player_new_from_media = (libvlc_media_player_t* (*)(libvlc_media_t*))vlc_sym(api->dylib, "libvlc_media_player_new_from_media");
    api->media_player_release = (void (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_release");
    api->media_player_play = (int (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_play");
    api->media_player_pause = (void (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_pause");
    api->media_player_stop = (void (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_stop");
    api->media_player_get_time = (libvlc_time_t (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_get_time");
    api->media_player_set_time = (void (*)(libvlc_media_player_t*, libvlc_time_t))vlc_sym(api->dylib, "libvlc_media_player_set_time");
    api->media_player_get_length = (libvlc_time_t (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_get_length");
    api->audio_set_volume = (int (*)(libvlc_media_player_t*, int))vlc_sym(api->dylib, "libvlc_audio_set_volume");
    api->audio_get_volume = (int (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_audio_get_volume");
    api->media_player_set_rate = (int (*)(libvlc_media_player_t*, float))vlc_sym(api->dylib, "libvlc_media_player_set_rate");
    api->media_player_get_rate = (float (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_get_rate");
    api->media_player_get_state = (libvlc_state_t (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_media_player_get_state");
    api->media_player_set_xwindow = (void (*)(libvlc_media_player_t*, uint32_t))vlc_sym(api->dylib, "libvlc_media_player_set_xwindow");
    api->video_set_callbacks = (void (*)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*))vlc_sym(api->dylib, "libvlc_video_set_callbacks");
    api->video_set_format_callbacks = (void (*)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*)))vlc_sym(api->dylib, "libvlc_video_set_format_callbacks");
    api->audio_get_track_description = (libvlc_track_description_t* (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_audio_get_track_description");
    api->audio_set_track = (int (*)(libvlc_media_player_t*, int))vlc_sym(api->dylib, "libvlc_audio_set_track");
    api->video_get_spu_description = (libvlc_track_description_t* (*)(libvlc_media_player_t*))vlc_sym(api->dylib, "libvlc_video_get_spu_description");
    api->video_set_spu = (int (*)(libvlc_media_player_t*, int))vlc_sym(api->dylib, "libvlc_video_set_spu");
    api->track_description_list_release = (void (*)(libvlc_track_description_t*))vlc_sym(api->dylib, "libvlc_track_description_list_release");

    if (!api->new_instance || !api->release_instance || !api->media_new_location ||
        !api->media_new_path || !api->media_add_option || !api->media_release ||
        !api->media_player_new_from_media || !api->media_player_release ||
        !api->media_player_play || !api->media_player_pause || !api->media_player_stop ||
        !api->media_player_get_time || !api->media_player_set_time || !api->media_player_get_length ||
        !api->audio_set_volume || !api->audio_get_volume || !api->media_player_set_rate ||
        !api->media_player_get_rate || !api->media_player_get_state || !api->media_player_set_xwindow ||
        !api->video_set_callbacks || !api->video_set_format_callbacks || !api->audio_get_track_description ||
        !api->audio_set_track || !api->video_get_spu_description || !api->video_set_spu ||
        !api->track_description_list_release) {
        native_logf("libVLC is missing required API symbols\n");
        dlclose(api->dylib);
        if (api->core_dylib) dlclose(api->core_dylib);
        memset(api, 0, sizeof(*api));
        return 0;
    }
    return 1;
}

static unsigned vlc_format_cb(void** opaque, char* chroma, unsigned* width, unsigned* height, unsigned* pitches, unsigned* lines) {
    LibVlcCanvasPlayer* player = (LibVlcCanvasPlayer*)(*opaque);
    if (!player || !width || !height || *width == 0 || *height == 0) return 0;

    unsigned frame_width = (*width) & ~1u;
    unsigned frame_height = (*height) & ~1u;
    if (frame_width == 0 || frame_height == 0) return 0;

    memcpy(chroma, "I420", 4);
    unsigned y_pitch = frame_width;
    unsigned u_pitch = frame_width / 2u;
    unsigned v_pitch = frame_width / 2u;
    size_t y_size = (size_t)y_pitch * frame_height;
    size_t u_size = (size_t)u_pitch * (frame_height / 2u);
    size_t v_size = (size_t)v_pitch * (frame_height / 2u);
    unsigned bgra_pitch = frame_width * 4u;
    size_t frame_size = y_size + u_size + v_size;
    size_t bgra_size = (size_t)bgra_pitch * frame_height;

    uint8_t* new_frame = (uint8_t*)calloc(1, frame_size);
    uint8_t* new_bgra = (uint8_t*)calloc(1, bgra_size);
    uint8_t* new_read = (uint8_t*)calloc(1, bgra_size);
    if (!new_frame || !new_bgra || !new_read) {
        free(new_frame);
        free(new_bgra);
        free(new_read);
        return 0;
    }

    pthread_mutex_lock(&player->frame_mutex);
    free(player->frame);
    free(player->bgra_frame);
    free(player->read_frame);
    player->frame = new_frame;
    player->bgra_frame = new_bgra;
    player->read_frame = new_read;
    player->bgra_frame_size = bgra_size;
    player->width = frame_width;
    player->height = frame_height;
    player->pitch = bgra_pitch;
    player->y_pitch = y_pitch;
    player->u_pitch = u_pitch;
    player->v_pitch = v_pitch;
    player->u_offset = y_size;
    player->v_offset = y_size + u_size;
    player->frame_ready = 0;
    pthread_mutex_unlock(&player->frame_mutex);

    pitches[0] = y_pitch;
    pitches[1] = u_pitch;
    pitches[2] = v_pitch;
    lines[0] = frame_height;
    lines[1] = frame_height / 2u;
    lines[2] = frame_height / 2u;
    return 1;
}

static void vlc_format_cleanup_cb(void* opaque) {
    (void)opaque;
}

static void* vlc_lock_cb(void* opaque, void** planes) {
    LibVlcCanvasPlayer* player = (LibVlcCanvasPlayer*)opaque;
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

static void convert_i420_to_bgra(LibVlcCanvasPlayer* player) {
    if (!player || !player->frame || !player->bgra_frame || player->width == 0 || player->height == 0) return;

    const uint8_t* y_plane = player->frame;
    const uint8_t* u_plane = player->frame + player->u_offset;
    const uint8_t* v_plane = player->frame + player->v_offset;
    for (unsigned y = 0; y < player->height; y++) {
        const uint8_t* y_row = y_plane + (size_t)y * player->y_pitch;
        const uint8_t* u_row = u_plane + (size_t)(y / 2u) * player->u_pitch;
        const uint8_t* v_row = v_plane + (size_t)(y / 2u) * player->v_pitch;
        uint8_t* dst = player->bgra_frame + (size_t)y * player->pitch;
        for (unsigned x = 0; x < player->width; x++) {
            int c = (int)y_row[x] - 16;
            int d = (int)u_row[x / 2u] - 128;
            int e = (int)v_row[x / 2u] - 128;
            if (c < 0) c = 0;
            dst[2] = clamp_u8((298 * c + 409 * e + 128) >> 8);
            dst[1] = clamp_u8((298 * c - 100 * d - 208 * e + 128) >> 8);
            dst[0] = clamp_u8((298 * c + 516 * d + 128) >> 8);
            dst[3] = 255;
            dst += 4;
        }
    }
    player->frame_ready = 1;
}

static void vlc_unlock_cb(void* opaque, void* picture, void* const* planes) {
    (void)picture;
    (void)planes;
    LibVlcCanvasPlayer* player = (LibVlcCanvasPlayer*)opaque;
    if (player) {
        convert_i420_to_bgra(player);
        pthread_mutex_unlock(&player->frame_mutex);
    }
}

static void vlc_display_cb(void* opaque, void* picture) {
    (void)opaque;
    (void)picture;
}

static int has_uri_scheme(const char* uri) {
    return uri && strstr(uri, "://") != NULL;
}

static int select_description_ordinal(libvlc_track_description_t* descriptions, int ordinal) {
    int current = 0;
    for (libvlc_track_description_t* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;
        if (current == ordinal) return item->i_id;
        current++;
    }
    return -2;
}

static int apply_audio_ordinal(LibVlcCanvasPlayer* player, int ordinal) {
    if (!player || !player->player || ordinal < 0) return 0;
    libvlc_track_description_t* descriptions = player->api.audio_get_track_description(player->player);
    int id = select_description_ordinal(descriptions, ordinal);
    if (descriptions) player->api.track_description_list_release(descriptions);
    return id != -2 && player->api.audio_set_track(player->player, id) == 0;
}

static int apply_spu_ordinal(LibVlcCanvasPlayer* player, int ordinal) {
    if (!player || !player->player) return 0;
    if (ordinal < 0) return player->api.video_set_spu(player->player, -1) == 0;
    libvlc_track_description_t* descriptions = player->api.video_get_spu_description(player->player);
    int id = select_description_ordinal(descriptions, ordinal);
    if (descriptions) player->api.track_description_list_release(descriptions);
    return id != -2 && player->api.video_set_spu(player->player, id) == 0;
}

static void apply_pending_tracks(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return;
    if (player->pending_audio_ordinal >= 0) apply_audio_ordinal(player, player->pending_audio_ordinal);
    if (player->pending_spu_ordinal >= -1) apply_spu_ordinal(player, player->pending_spu_ordinal);
}

static void add_header_options(libvlc_media_t* media, LibVlcCanvasPlayer* player, const char* request_headers) {
    if (!media || !player || !request_headers || !request_headers[0]) return;
    char* copy = strdup(request_headers);
    if (!copy) return;

    char* save = NULL;
    for (char* line = strtok_r(copy, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        while (*line == ' ' || *line == '\t' || *line == '\r') line++;
        char* separator = strchr(line, ':');
        if (!separator) continue;
        *separator = '\0';
        char* name = line;
        char* value = separator + 1;
        while (*value == ' ' || *value == '\t') value++;
        if (!name[0] || !value[0]) continue;

        const char* option_name = NULL;
        if (strcasecmp(name, "User-Agent") == 0) option_name = ":http-user-agent=";
        else if (strcasecmp(name, "Referer") == 0 || strcasecmp(name, "Referrer") == 0) option_name = ":http-referrer=";
        else if (strcasecmp(name, "Cookie") == 0) option_name = ":http-cookie=";

        if (option_name) {
            size_t option_len = strlen(option_name) + strlen(value) + 1;
            char* option = (char*)malloc(option_len);
            if (option) {
                snprintf(option, option_len, "%s%s", option_name, value);
                player->api.media_add_option(media, option);
                free(option);
            }
        }

        size_t custom_len = strlen(":http-custom-header=") + strlen(name) + strlen(value) + 3;
        char* custom = (char*)malloc(custom_len);
        if (custom) {
            snprintf(custom, custom_len, ":http-custom-header=%s: %s", name, value);
            player->api.media_add_option(media, custom);
            free(custom);
        }
    }
    free(copy);
}

LibVlcCanvasPlayer* lvc_create(const char* libvlc_path, const char* plugin_path, int native_video_output) {
    if (!libvlc_path || !plugin_path) return NULL;
    LibVlcCanvasPlayer* player = (LibVlcCanvasPlayer*)calloc(1, sizeof(LibVlcCanvasPlayer));
    if (!player) return NULL;
    pthread_mutex_init(&player->frame_mutex, NULL);
    player->native_video_output = native_video_output != 0;
    player->pending_audio_ordinal = -2;
    player->pending_spu_ordinal = -2;

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
        "--no-avcodec-dr"
    };
    const char* native_args[] = {
        "--no-video-title-show",
        "--no-osd",
        "--quiet",
        "--vout=xcb_x11"
    };
    const char* const* args = player->native_video_output ? native_args : memory_args;
    int arg_count =
        player->native_video_output
            ? (int)(sizeof(native_args) / sizeof(native_args[0]))
            : (int)(sizeof(memory_args) / sizeof(memory_args[0]));
    player->instance = player->api.new_instance(arg_count, args);
    if (!player->instance) {
        lvc_destroy(player);
        return NULL;
    }
    return player;
}

void lvc_destroy(LibVlcCanvasPlayer* player) {
    if (!player) return;
    if (player->player) {
        player->api.media_player_stop(player->player);
        player->api.media_player_release(player->player);
    }
    if (player->instance) player->api.release_instance(player->instance);
    pthread_mutex_lock(&player->frame_mutex);
    free(player->frame);
    free(player->bgra_frame);
    free(player->read_frame);
    pthread_mutex_unlock(&player->frame_mutex);
    pthread_mutex_destroy(&player->frame_mutex);
    if (player->api.dylib) dlclose(player->api.dylib);
    if (player->api.core_dylib) dlclose(player->api.core_dylib);
    free(player);
}

int lvc_open_uri_with_headers(
    LibVlcCanvasPlayer* player,
    const char* uri,
    const char* request_headers,
    int start_playback
) {
    if (!player || !player->instance || !uri) return 0;
    if (player->player) {
        player->api.media_player_stop(player->player);
        player->api.media_player_release(player->player);
        player->player = NULL;
    }
    pthread_mutex_lock(&player->frame_mutex);
    player->frame_ready = 0;
    player->did_play_to_end = 0;
    pthread_mutex_unlock(&player->frame_mutex);

    libvlc_media_t* media = has_uri_scheme(uri)
        ? player->api.media_new_location(player->instance, uri)
        : player->api.media_new_path(player->instance, uri);
    if (!media) return 0;
    add_header_options(media, player, request_headers);

    player->player = player->api.media_player_new_from_media(media);
    player->api.media_release(media);
    if (!player->player) return 0;

    if (player->native_video_output) {
        player->api.media_player_set_xwindow(player->player, player->native_window);
    } else {
        player->api.video_set_callbacks(player->player, vlc_lock_cb, vlc_unlock_cb, vlc_display_cb, player);
        player->api.video_set_format_callbacks(player->player, vlc_format_cb, vlc_format_cleanup_cb);
    }
    int should_start = start_playback || !player->native_video_output;
    if (should_start && player->api.media_player_play(player->player) != 0) return 0;
    if (!start_playback && !player->native_video_output) {
        player->api.media_player_pause(player->player);
    }
    apply_pending_tracks(player);
    return 1;
}

void lvc_play(LibVlcCanvasPlayer* player) {
    if (player && player->player) {
        player->api.media_player_play(player->player);
        apply_pending_tracks(player);
    }
}

void lvc_pause(LibVlcCanvasPlayer* player) {
    if (player && player->player) player->api.media_player_pause(player->player);
}

void lvc_set_volume(LibVlcCanvasPlayer* player, float volume) {
    if (!player || !player->player) return;
    int scaled = (int)(volume * 100.0f);
    if (scaled < 0) scaled = 0;
    if (scaled > 100) scaled = 100;
    player->api.audio_set_volume(player->player, scaled);
}

float lvc_get_volume(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return 0.0f;
    return (float)player->api.audio_get_volume(player->player) / 100.0f;
}

void lvc_seek_to(LibVlcCanvasPlayer* player, double time_seconds) {
    if (player && player->player) {
        player->api.media_player_set_time(player->player, (libvlc_time_t)(time_seconds * 1000.0));
        apply_pending_tracks(player);
    }
}

void lvc_set_playback_speed(LibVlcCanvasPlayer* player, float speed) {
    if (player && player->player) player->api.media_player_set_rate(player->player, speed);
}

float lvc_get_playback_speed(LibVlcCanvasPlayer* player) {
    return (player && player->player) ? player->api.media_player_get_rate(player->player) : 1.0f;
}

void* lvc_lock_frame(LibVlcCanvasPlayer* player, int32_t out_info[3]) {
    if (!player || !out_info) return NULL;
    void* result = NULL;
    pthread_mutex_lock(&player->frame_mutex);
    if (player->frame_ready && player->bgra_frame && player->read_frame && player->width > 0 && player->height > 0) {
        size_t size = (size_t)player->pitch * player->height;
        if (size <= player->bgra_frame_size) {
            memcpy(player->read_frame, player->bgra_frame, size);
            out_info[0] = (int32_t)player->width;
            out_info[1] = (int32_t)player->height;
            out_info[2] = (int32_t)player->pitch;
            result = player->read_frame;
        }
    }
    pthread_mutex_unlock(&player->frame_mutex);
    return result;
}

void lvc_unlock_frame(LibVlcCanvasPlayer* player) {
    (void)player;
}

int32_t lvc_get_frame_width(LibVlcCanvasPlayer* player) {
    return player ? (int32_t)player->width : 0;
}

int32_t lvc_get_frame_height(LibVlcCanvasPlayer* player) {
    return player ? (int32_t)player->height : 0;
}

double lvc_get_duration(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return 0.0;
    libvlc_time_t length = player->api.media_player_get_length(player->player);
    return length > 0 ? (double)length / 1000.0 : 0.0;
}

double lvc_get_current_time(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return 0.0;
    libvlc_time_t time = player->api.media_player_get_time(player->player);
    return time > 0 ? (double)time / 1000.0 : 0.0;
}

float lvc_get_frame_rate(LibVlcCanvasPlayer* player) {
    (void)player;
    return 30.0f;
}

int32_t lvc_consume_did_play_to_end(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return 0;
    int ended = player->api.media_player_get_state(player->player) == LIBVLC_STATE_ENDED;
    if (ended && !player->did_play_to_end) {
        player->did_play_to_end = 1;
        return 1;
    }
    if (!ended) player->did_play_to_end = 0;
    return 0;
}

int32_t lvc_select_audio_track(LibVlcCanvasPlayer* player, int32_t ordinal) {
    if (!player) return 0;
    player->pending_audio_ordinal = ordinal;
    return apply_audio_ordinal(player, ordinal);
}

int32_t lvc_select_subtitle_track(LibVlcCanvasPlayer* player, int32_t ordinal) {
    if (!player) return 0;
    player->pending_spu_ordinal = ordinal;
    return apply_spu_ordinal(player, ordinal);
}

int32_t lvc_disable_subtitles(LibVlcCanvasPlayer* player) {
    if (!player) return 0;
    player->pending_spu_ordinal = -1;
    return apply_spu_ordinal(player, -1);
}

typedef struct {
    char* data;
    size_t length;
    size_t capacity;
} StringBuilder;

static int sb_append(StringBuilder* builder, const char* value) {
    if (!value) return 1;
    size_t value_len = strlen(value);
    size_t needed = builder->length + value_len + 1;
    if (needed > builder->capacity) {
        size_t next = builder->capacity ? builder->capacity : 256;
        while (next < needed) next *= 2;
        char* data = (char*)realloc(builder->data, next);
        if (!data) return 0;
        builder->data = data;
        builder->capacity = next;
    }
    memcpy(builder->data + builder->length, value, value_len);
    builder->length += value_len;
    builder->data[builder->length] = '\0';
    return 1;
}

static char* descriptions_to_string(LibVlcCanvasPlayer* player, libvlc_track_description_t* descriptions) {
    if (!player || !descriptions) return NULL;
    StringBuilder builder = {0};
    int ordinal = 0;
    for (libvlc_track_description_t* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;
        char line[64];
        snprintf(line, sizeof(line), "%d\t", ordinal);
        if (!sb_append(&builder, line) || !sb_append(&builder, item->psz_name ? item->psz_name : "") || !sb_append(&builder, "\n")) {
            free(builder.data);
            return NULL;
        }
        ordinal++;
    }
    return builder.data;
}

char* lvc_get_audio_track_descriptions(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return NULL;
    libvlc_track_description_t* descriptions = player->api.audio_get_track_description(player->player);
    char* result = descriptions_to_string(player, descriptions);
    if (descriptions) player->api.track_description_list_release(descriptions);
    return result;
}

char* lvc_get_subtitle_track_descriptions(LibVlcCanvasPlayer* player) {
    if (!player || !player->player) return NULL;
    libvlc_track_description_t* descriptions = player->api.video_get_spu_description(player->player);
    char* result = descriptions_to_string(player, descriptions);
    if (descriptions) player->api.track_description_list_release(descriptions);
    return result;
}

int lvc_set_native_window(LibVlcCanvasPlayer* player, uint32_t xwindow) {
    if (!player || !player->native_video_output) return 0;
    player->native_window = xwindow;
    if (player->player && player->api.media_player_set_xwindow) {
        player->api.media_player_set_xwindow(player->player, xwindow);
    }
    return 1;
}
