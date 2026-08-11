// SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

#ifndef COMPOSE_MEDIA_PLAYER_MPV_BRIDGE_H
#define COMPOSE_MEDIA_PLAYER_MPV_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cmp_mpv_player cmp_mpv_player;

enum {
    CMP_MPV_OK = 0,
    CMP_MPV_LIBRARY_NOT_FOUND = 1,
    CMP_MPV_REQUIRED_SYMBOL_MISSING = 2,
    CMP_MPV_INCOMPATIBLE_CLIENT_API = 3,
    CMP_MPV_INITIALIZATION_FAILED = 4,
    CMP_MPV_INVALID_ARGUMENT = 5,
    CMP_MPV_COMMAND_FAILED = 6,
    CMP_MPV_RENDER_FAILED = 7,
};

enum {
    CMP_MPV_RENDERER_SOFTWARE = 0,
    CMP_MPV_RENDERER_IOSVK = 1,
};

enum {
    CMP_MPV_EVENT_NONE = 0,
    CMP_MPV_EVENT_SHUTDOWN = 1,
    CMP_MPV_EVENT_END_FILE = 7,
    CMP_MPV_EVENT_FILE_LOADED = 8,
    CMP_MPV_EVENT_SEEK = 20,
    CMP_MPV_EVENT_PLAYBACK_RESTART = 21,
};

typedef struct cmp_mpv_event {
    int event_id;
    int end_file_reason;
    int error_code;
} cmp_mpv_event;

/**
 * Probes libmpv without creating a player. A NULL path resolves symbols already
 * linked into the process. A non-NULL path is opened with the platform loader.
 */
int cmp_mpv_probe(
    const char *library_path,
    int *client_api_major,
    int *client_api_minor
);

/**
 * Creates a software- or MoltenVK-rendered libmpv player.
 *
 * library_path follows cmp_mpv_probe. subtitle_fonts_directory may be NULL.
 * CMP_MPV_RENDERER_IOSVK requires a positive CAMetalLayer pointer in surface_layer
 * and KMediaMpv's versioned embedded-iosvk capability.
 * status receives a CMP_MPV_* value on both success and failure.
 */
cmp_mpv_player *cmp_mpv_player_create(
    const char *library_path,
    const char *subtitle_fonts_directory,
    int preserve_ass_styles,
    int use_embedded_fonts,
    int renderer,
    uintptr_t surface_layer,
    int *status
);

void cmp_mpv_player_destroy(cmp_mpv_player *player);

int cmp_mpv_player_command(
    cmp_mpv_player *player,
    const char *const *arguments
);

int cmp_mpv_player_set_property(
    cmp_mpv_player *player,
    const char *name,
    const char *value
);

/** Sets an mpv string-list property as an MPV_FORMAT_NODE_ARRAY. */
int cmp_mpv_player_set_string_list_property(
    cmp_mpv_player *player,
    const char *name,
    const char *const *values,
    size_t count
);

/**
 * Returns an mpv-owned UTF-8 value. Release it with
 * cmp_mpv_player_free_property.
 */
char *cmp_mpv_player_get_property(
    cmp_mpv_player *player,
    const char *name
);

void cmp_mpv_player_free_property(
    cmp_mpv_player *player,
    char *value
);

int cmp_mpv_player_wait_event(
    cmp_mpv_player *player,
    double timeout_seconds,
    cmp_mpv_event *event
);

void cmp_mpv_player_wakeup(cmp_mpv_player *player);

int cmp_mpv_player_render_bgr0(
    cmp_mpv_player *player,
    int width,
    int height,
    size_t row_bytes,
    void *pixels
);

/** Replaces the embedded iosvk VO with the bounded software render context. */
int cmp_mpv_player_switch_to_software(cmp_mpv_player *player);

#ifdef __cplusplus
}
#endif

#endif
