// SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

#ifndef COMPOSE_MEDIA_PLAYER_LIBVLC_BRIDGE_H
#define COMPOSE_MEDIA_PLAYER_LIBVLC_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cmp_vlc_player cmp_vlc_player;
typedef struct cmp_vlc_frame cmp_vlc_frame;

enum {
    CMP_VLC_OK = 0,
    CMP_VLC_LIBRARY_NOT_FOUND = 1,
    CMP_VLC_REQUIRED_SYMBOL_MISSING = 2,
    CMP_VLC_INCOMPATIBLE_BRIDGE_ABI = 3,
    CMP_VLC_INITIALIZATION_FAILED = 4,
    CMP_VLC_INVALID_ARGUMENT = 5,
    CMP_VLC_COMMAND_FAILED = 6,
    CMP_VLC_SNAPSHOT_FAILED = 7,
};

enum {
    CMP_VLC_STATE_IDLE = 0,
    CMP_VLC_STATE_OPENING = 1,
    CMP_VLC_STATE_BUFFERING = 2,
    CMP_VLC_STATE_PLAYING = 3,
    CMP_VLC_STATE_PAUSED = 4,
    CMP_VLC_STATE_STOPPED = 5,
    CMP_VLC_STATE_ENDED = 6,
    CMP_VLC_STATE_ERROR = 7,
};

enum {
    CMP_VLC_SOURCE_DYNAMIC_RANGE_UNKNOWN = 0,
    CMP_VLC_SOURCE_DYNAMIC_RANGE_SDR = 1,
    CMP_VLC_SOURCE_DYNAMIC_RANGE_HDR10 = 2,
    CMP_VLC_SOURCE_DYNAMIC_RANGE_HLG = 3,
};

typedef struct cmp_vlc_player_snapshot {
    int state;
    uint64_t media_generation;
    int64_t position_microseconds;
    int64_t duration_microseconds;
    uint32_t video_width;
    uint32_t video_height;
    uint32_t buffered_permille;
    bool seekable;
} cmp_vlc_player_snapshot;

typedef struct cmp_vlc_frame_info {
    uint64_t serial;
    uint64_t output_generation;
    int64_t pts_microseconds;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint64_t byte_count;
    int source_dynamic_range;
    bool premultiplied_alpha;
} cmp_vlc_frame_info;

/** Opens the app-bundled bridge and verifies that it can create a CPU-pull player. */
int cmp_vlc_probe(
    const char *bridge_path,
    const char *libvlc_path,
    const char *plugin_directory
);

cmp_vlc_player *cmp_vlc_player_create(
    const char *bridge_path,
    const char *libvlc_path,
    const char *plugin_directory,
    int *status
);

int cmp_vlc_player_open(
    cmp_vlc_player *player,
    const char *uri,
    const char *const *headers,
    size_t header_entry_count,
    bool autoplay
);

int cmp_vlc_player_play(cmp_vlc_player *player);
int cmp_vlc_player_pause(cmp_vlc_player *player);
int cmp_vlc_player_stop(cmp_vlc_player *player);
int cmp_vlc_player_seek(cmp_vlc_player *player, int64_t time_microseconds, bool fast);
int cmp_vlc_player_set_volume(cmp_vlc_player *player, float volume);
int cmp_vlc_player_set_rate(cmp_vlc_player *player, float rate);
int cmp_vlc_player_set_loop(cmp_vlc_player *player, bool loop);
int cmp_vlc_player_get_snapshot(cmp_vlc_player *player, cmp_vlc_player_snapshot *snapshot);
const char *cmp_vlc_player_last_error(cmp_vlc_player *player);

cmp_vlc_frame *cmp_vlc_player_acquire_latest_frame(
    cmp_vlc_player *player,
    cmp_vlc_frame_info *info
);
const void *cmp_vlc_frame_pixels(cmp_vlc_frame *frame, size_t *byte_count);
void cmp_vlc_frame_release(cmp_vlc_frame *frame);
void cmp_vlc_player_destroy(cmp_vlc_player *player);

#ifdef __cplusplus
}
#endif

#endif
