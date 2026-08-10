// SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

#include "ComposeMediaPlayerLibVlcBridge.h"
#include "kmediavlc_client.h"

#include <dlfcn.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

_Static_assert(KMEDIAVLC_BRIDGE_ABI_VERSION == 2u, "Update the iOS adapter for the new KMediaVlc ABI.");
_Static_assert(KMEDIAVLC_STATE_IDLE == CMP_VLC_STATE_IDLE, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_OPENING == CMP_VLC_STATE_OPENING, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_BUFFERING == CMP_VLC_STATE_BUFFERING, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_PLAYING == CMP_VLC_STATE_PLAYING, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_PAUSED == CMP_VLC_STATE_PAUSED, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_STOPPED == CMP_VLC_STATE_STOPPED, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_ENDED == CMP_VLC_STATE_ENDED, "Playback state ABI mismatch.");
_Static_assert(KMEDIAVLC_STATE_ERROR == CMP_VLC_STATE_ERROR, "Playback state ABI mismatch.");

typedef kmediavlc_player *(*kmediavlc_player_create_fn)(const kmediavlc_player_config *);
typedef bool (*kmediavlc_player_open_fn)(
    kmediavlc_player *,
    const char *,
    const char *const *,
    size_t,
    bool
);
typedef bool (*kmediavlc_player_simple_fn)(kmediavlc_player *);
typedef bool (*kmediavlc_player_seek_fn)(kmediavlc_player *, int64_t, bool);
typedef bool (*kmediavlc_player_float_fn)(kmediavlc_player *, float);
typedef bool (*kmediavlc_player_loop_fn)(kmediavlc_player *, bool);
typedef bool (*kmediavlc_player_snapshot_fn)(kmediavlc_player *, kmediavlc_player_snapshot *);
typedef const char *(*kmediavlc_player_last_error_fn)(kmediavlc_player *);
typedef kmediavlc_frame *(*kmediavlc_player_acquire_frame_fn)(
    kmediavlc_player *,
    kmediavlc_frame_info *
);
typedef const void *(*kmediavlc_frame_pixels_fn)(kmediavlc_frame *, size_t *);
typedef void (*kmediavlc_frame_release_fn)(kmediavlc_frame *, intptr_t);
typedef void (*kmediavlc_player_destroy_fn)(kmediavlc_player *);

typedef struct cmp_vlc_shared_api {
    _Atomic size_t references;
    void *library;
    kmediavlc_player_create_fn player_create;
    kmediavlc_player_open_fn player_open;
    kmediavlc_player_simple_fn player_play;
    kmediavlc_player_simple_fn player_pause;
    kmediavlc_player_simple_fn player_stop;
    kmediavlc_player_seek_fn player_seek;
    kmediavlc_player_float_fn player_set_volume;
    kmediavlc_player_float_fn player_set_rate;
    kmediavlc_player_loop_fn player_set_loop;
    kmediavlc_player_snapshot_fn player_get_snapshot;
    kmediavlc_player_last_error_fn player_last_error;
    kmediavlc_player_acquire_frame_fn player_acquire_latest_frame;
    kmediavlc_frame_pixels_fn frame_cpu_pixels;
    kmediavlc_frame_release_fn frame_release;
    kmediavlc_player_destroy_fn player_destroy;
} cmp_vlc_shared_api;

struct cmp_vlc_player {
    cmp_vlc_shared_api *api;
    kmediavlc_player *native;
};

struct cmp_vlc_frame {
    cmp_vlc_shared_api *api;
    kmediavlc_frame *native;
};

static void cmp_vlc_release_api(cmp_vlc_shared_api *api) {
    if (api == NULL) {
        return;
    }
    if (atomic_fetch_sub_explicit(&api->references, 1u, memory_order_acq_rel) != 1u) {
        return;
    }
    if (api->library != NULL) {
        dlclose(api->library);
    }
    free(api);
}

static void cmp_vlc_retain_api(cmp_vlc_shared_api *api) {
    atomic_fetch_add_explicit(&api->references, 1u, memory_order_relaxed);
}

static int cmp_vlc_symbol(
    void *library,
    const char *name,
    void *target,
    size_t target_size
) {
    void *symbol = dlsym(library, name);
    if (symbol == NULL || target_size != sizeof(symbol)) {
        return 0;
    }
    memcpy(target, &symbol, sizeof(symbol));
    return 1;
}

#define CMP_VLC_LOAD(api, field, symbol_name)                                      \
    do {                                                                            \
        if (!cmp_vlc_symbol(                                                        \
                (api)->library,                                                     \
                (symbol_name),                                                      \
                &(api)->field,                                                      \
                sizeof((api)->field))) {                                            \
            cmp_vlc_release_api((api));                                             \
            return CMP_VLC_REQUIRED_SYMBOL_MISSING;                                 \
        }                                                                           \
    } while (0)

static int cmp_vlc_open_api(
    const char *bridge_path,
    cmp_vlc_shared_api **output
) {
    if (bridge_path == NULL || bridge_path[0] == '\0' || output == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    *output = NULL;
    cmp_vlc_shared_api *api = calloc(1u, sizeof(*api));
    if (api == NULL) {
        return CMP_VLC_INITIALIZATION_FAILED;
    }
    atomic_init(&api->references, 1u);
    api->library = dlopen(bridge_path, RTLD_NOW | RTLD_LOCAL);
    if (api->library == NULL) {
        cmp_vlc_release_api(api);
        return CMP_VLC_LIBRARY_NOT_FOUND;
    }

    CMP_VLC_LOAD(api, player_create, "kmediavlc_player_create");
    CMP_VLC_LOAD(api, player_open, "kmediavlc_player_open");
    CMP_VLC_LOAD(api, player_play, "kmediavlc_player_play");
    CMP_VLC_LOAD(api, player_pause, "kmediavlc_player_pause");
    CMP_VLC_LOAD(api, player_stop, "kmediavlc_player_stop");
    CMP_VLC_LOAD(api, player_seek, "kmediavlc_player_seek");
    CMP_VLC_LOAD(api, player_set_volume, "kmediavlc_player_set_volume");
    CMP_VLC_LOAD(api, player_set_rate, "kmediavlc_player_set_rate");
    CMP_VLC_LOAD(api, player_set_loop, "kmediavlc_player_set_loop");
    CMP_VLC_LOAD(api, player_get_snapshot, "kmediavlc_player_get_snapshot");
    CMP_VLC_LOAD(api, player_last_error, "kmediavlc_player_last_error");
    CMP_VLC_LOAD(api, player_acquire_latest_frame, "kmediavlc_player_acquire_latest_frame");
    CMP_VLC_LOAD(api, frame_cpu_pixels, "kmediavlc_frame_cpu_pixels");
    CMP_VLC_LOAD(api, frame_release, "kmediavlc_frame_release");
    CMP_VLC_LOAD(api, player_destroy, "kmediavlc_player_destroy");
    *output = api;
    return CMP_VLC_OK;
}

static kmediavlc_player_config cmp_vlc_config(
    const char *libvlc_path,
    const char *plugin_directory
) {
    kmediavlc_player_config config;
    memset(&config, 0, sizeof(config));
    config.struct_size = (uint32_t)sizeof(config);
    config.bridge_abi_version = KMEDIAVLC_BRIDGE_ABI_VERSION;
    config.libvlc_path_utf8 = libvlc_path;
    config.plugin_directory_utf8 = plugin_directory;
    config.delivery_mode = KMEDIAVLC_CPU_PULL;
    config.request_hdr = false;
    config.sdr_white_nits = 203.0f;
    config.display_peak_nits = 203.0f;
    return config;
}

int cmp_vlc_probe(
    const char *bridge_path,
    const char *libvlc_path,
    const char *plugin_directory
) {
    if (libvlc_path == NULL || libvlc_path[0] == '\0' ||
        plugin_directory == NULL || plugin_directory[0] == '\0') {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    cmp_vlc_shared_api *api = NULL;
    int status = cmp_vlc_open_api(bridge_path, &api);
    if (status != CMP_VLC_OK) {
        return status;
    }
    kmediavlc_player_config config = cmp_vlc_config(libvlc_path, plugin_directory);
    kmediavlc_player *player = api->player_create(&config);
    if (player == NULL) {
        cmp_vlc_release_api(api);
        return CMP_VLC_INITIALIZATION_FAILED;
    }
    api->player_destroy(player);
    cmp_vlc_release_api(api);
    return CMP_VLC_OK;
}

cmp_vlc_player *cmp_vlc_player_create(
    const char *bridge_path,
    const char *libvlc_path,
    const char *plugin_directory,
    int *status
) {
    if (status == NULL || libvlc_path == NULL || libvlc_path[0] == '\0' ||
        plugin_directory == NULL || plugin_directory[0] == '\0') {
        if (status != NULL) {
            *status = CMP_VLC_INVALID_ARGUMENT;
        }
        return NULL;
    }
    *status = CMP_VLC_INITIALIZATION_FAILED;
    cmp_vlc_shared_api *api = NULL;
    *status = cmp_vlc_open_api(bridge_path, &api);
    if (*status != CMP_VLC_OK) {
        return NULL;
    }
    kmediavlc_player_config config = cmp_vlc_config(libvlc_path, plugin_directory);
    kmediavlc_player *native = api->player_create(&config);
    if (native == NULL) {
        cmp_vlc_release_api(api);
        *status = CMP_VLC_INITIALIZATION_FAILED;
        return NULL;
    }
    cmp_vlc_player *player = calloc(1u, sizeof(*player));
    if (player == NULL) {
        api->player_destroy(native);
        cmp_vlc_release_api(api);
        return NULL;
    }
    player->api = api;
    player->native = native;
    *status = CMP_VLC_OK;
    return player;
}

int cmp_vlc_player_open(
    cmp_vlc_player *player,
    const char *uri,
    const char *const *headers,
    size_t header_entry_count,
    bool autoplay
) {
    if (player == NULL || player->native == NULL || uri == NULL || uri[0] == '\0') {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_open(
               player->native,
               uri,
               headers,
               header_entry_count,
               autoplay)
        ? CMP_VLC_OK
        : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_play(cmp_vlc_player *player) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_play(player->native) ? CMP_VLC_OK : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_pause(cmp_vlc_player *player) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_pause(player->native) ? CMP_VLC_OK : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_stop(cmp_vlc_player *player) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_stop(player->native) ? CMP_VLC_OK : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_seek(cmp_vlc_player *player, int64_t time_microseconds, bool fast) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_seek(player->native, time_microseconds, fast)
        ? CMP_VLC_OK
        : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_set_volume(cmp_vlc_player *player, float volume) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_set_volume(player->native, volume)
        ? CMP_VLC_OK
        : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_set_rate(cmp_vlc_player *player, float rate) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_set_rate(player->native, rate)
        ? CMP_VLC_OK
        : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_set_loop(cmp_vlc_player *player, bool loop) {
    if (player == NULL || player->native == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    return player->api->player_set_loop(player->native, loop)
        ? CMP_VLC_OK
        : CMP_VLC_COMMAND_FAILED;
}

int cmp_vlc_player_get_snapshot(
    cmp_vlc_player *player,
    cmp_vlc_player_snapshot *snapshot
) {
    if (player == NULL || player->native == NULL || snapshot == NULL) {
        return CMP_VLC_INVALID_ARGUMENT;
    }
    kmediavlc_player_snapshot native;
    memset(&native, 0, sizeof(native));
    native.struct_size = (uint32_t)sizeof(native);
    native.bridge_abi_version = KMEDIAVLC_BRIDGE_ABI_VERSION;
    if (!player->api->player_get_snapshot(player->native, &native)) {
        return CMP_VLC_SNAPSHOT_FAILED;
    }
    if (native.bridge_abi_version != KMEDIAVLC_BRIDGE_ABI_VERSION) {
        return CMP_VLC_INCOMPATIBLE_BRIDGE_ABI;
    }
    switch (native.state) {
        case KMEDIAVLC_STATE_IDLE:
        case KMEDIAVLC_STATE_OPENING:
        case KMEDIAVLC_STATE_BUFFERING:
        case KMEDIAVLC_STATE_PLAYING:
        case KMEDIAVLC_STATE_PAUSED:
        case KMEDIAVLC_STATE_STOPPED:
        case KMEDIAVLC_STATE_ENDED:
        case KMEDIAVLC_STATE_ERROR:
            break;
        default:
            return CMP_VLC_INCOMPATIBLE_BRIDGE_ABI;
    }
    snapshot->state = (int)native.state;
    snapshot->media_generation = native.media_generation;
    snapshot->position_microseconds = native.position_microseconds;
    snapshot->duration_microseconds = native.duration_microseconds;
    snapshot->video_width = native.video_width;
    snapshot->video_height = native.video_height;
    snapshot->buffered_permille = native.buffered_permille;
    snapshot->seekable = native.seekable;
    return CMP_VLC_OK;
}

const char *cmp_vlc_player_last_error(cmp_vlc_player *player) {
    if (player == NULL || player->native == NULL) {
        return NULL;
    }
    return player->api->player_last_error(player->native);
}

cmp_vlc_frame *cmp_vlc_player_acquire_latest_frame(
    cmp_vlc_player *player,
    cmp_vlc_frame_info *info
) {
    if (player == NULL || player->native == NULL || info == NULL) {
        return NULL;
    }
    kmediavlc_frame_info native_info;
    memset(&native_info, 0, sizeof(native_info));
    native_info.struct_size = (uint32_t)sizeof(native_info);
    native_info.bridge_abi_version = KMEDIAVLC_BRIDGE_ABI_VERSION;
    kmediavlc_frame *native =
        player->api->player_acquire_latest_frame(player->native, &native_info);
    if (native == NULL) {
        return NULL;
    }
    if (native_info.bridge_abi_version != KMEDIAVLC_BRIDGE_ABI_VERSION ||
        native_info.pixel_format != KMEDIAVLC_RGBA8_SRGB ||
        native_info.handle_type != KMEDIAVLC_CPU_ADDRESS ||
        native_info.width == 0u || native_info.height == 0u ||
        (uint64_t)native_info.stride < (uint64_t)native_info.width * 4u ||
        native_info.cpu_byte_count < (uint64_t)native_info.stride * native_info.height) {
        player->api->frame_release(native, -1);
        return NULL;
    }
    cmp_vlc_frame *frame = calloc(1u, sizeof(*frame));
    if (frame == NULL) {
        player->api->frame_release(native, -1);
        return NULL;
    }
    cmp_vlc_retain_api(player->api);
    frame->api = player->api;
    frame->native = native;
    info->serial = native_info.serial;
    info->output_generation = native_info.output_generation;
    info->pts_microseconds = native_info.pts_microseconds;
    info->width = native_info.width;
    info->height = native_info.height;
    info->stride = native_info.stride;
    info->byte_count = native_info.cpu_byte_count;
    info->source_dynamic_range = (int)native_info.source_dynamic_range;
    info->premultiplied_alpha = native_info.premultiplied_alpha;
    return frame;
}

const void *cmp_vlc_frame_pixels(cmp_vlc_frame *frame, size_t *byte_count) {
    if (byte_count != NULL) {
        *byte_count = 0u;
    }
    if (frame == NULL || frame->native == NULL) {
        return NULL;
    }
    return frame->api->frame_cpu_pixels(frame->native, byte_count);
}

void cmp_vlc_frame_release(cmp_vlc_frame *frame) {
    if (frame == NULL) {
        return;
    }
    if (frame->native != NULL) {
        frame->api->frame_release(frame->native, -1);
        frame->native = NULL;
    }
    cmp_vlc_release_api(frame->api);
    free(frame);
}

void cmp_vlc_player_destroy(cmp_vlc_player *player) {
    if (player == NULL) {
        return;
    }
    if (player->native != NULL) {
        player->api->player_destroy(player->native);
        player->native = NULL;
    }
    cmp_vlc_release_api(player->api);
    free(player);
}
