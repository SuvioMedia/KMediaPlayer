/* SPDX-License-Identifier: ISC */

#ifndef KMEDIAVLC_CLIENT_H
#define KMEDIAVLC_CLIENT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(KMEDIAVLC_BUILDING_BRIDGE)
#    define KMEDIAVLC_API __declspec(dllexport)
#  else
#    define KMEDIAVLC_API __declspec(dllimport)
#  endif
#else
#  define KMEDIAVLC_API __attribute__((visibility("default")))
#endif

#define KMEDIAVLC_BRIDGE_ABI_VERSION 2u

typedef struct kmediavlc_player kmediavlc_player;
typedef struct kmediavlc_frame kmediavlc_frame;

typedef enum kmediavlc_delivery_mode {
    KMEDIAVLC_GPU_PUSH = 1,
    KMEDIAVLC_CPU_PULL = 2,
} kmediavlc_delivery_mode;

typedef enum kmediavlc_pixel_format {
    KMEDIAVLC_RGBA8_SRGB = 1,
    KMEDIAVLC_RGBA16F_LINEAR_SRGB = 2,
} kmediavlc_pixel_format;

typedef enum kmediavlc_source_dynamic_range {
    KMEDIAVLC_SOURCE_DYNAMIC_RANGE_UNKNOWN = 0,
    KMEDIAVLC_SOURCE_DYNAMIC_RANGE_SDR = 1,
    KMEDIAVLC_SOURCE_DYNAMIC_RANGE_HDR10 = 2,
    KMEDIAVLC_SOURCE_DYNAMIC_RANGE_HLG = 3,
} kmediavlc_source_dynamic_range;

typedef enum kmediavlc_platform_handle_type {
    KMEDIAVLC_CPU_ADDRESS = 1,
    KMEDIAVLC_D3D11_SHARED_HANDLE = 2,
    KMEDIAVLC_IOSURFACE_ID = 3,
    KMEDIAVLC_DMABUF = 4,
} kmediavlc_platform_handle_type;

typedef enum kmediavlc_playback_state {
    KMEDIAVLC_STATE_IDLE = 0,
    KMEDIAVLC_STATE_OPENING = 1,
    KMEDIAVLC_STATE_BUFFERING = 2,
    KMEDIAVLC_STATE_PLAYING = 3,
    KMEDIAVLC_STATE_PAUSED = 4,
    KMEDIAVLC_STATE_STOPPED = 5,
    KMEDIAVLC_STATE_ENDED = 6,
    KMEDIAVLC_STATE_ERROR = 7,
} kmediavlc_playback_state;

typedef enum kmediavlc_output_target_type {
    KMEDIAVLC_OUTPUT_UNAVAILABLE = 0,
    KMEDIAVLC_OUTPUT_WINDOWS_D3D11 = 1,
    KMEDIAVLC_OUTPUT_MACOS_IOSURFACE = 2,
    KMEDIAVLC_OUTPUT_LINUX_DMABUF = 3,
} kmediavlc_output_target_type;

typedef struct kmediavlc_drm_format_modifier {
    uint32_t format;
    uint64_t modifier;
} kmediavlc_drm_format_modifier;

typedef struct kmediavlc_output_target {
    uint32_t struct_size;
    uint32_t bridge_abi_version;
    kmediavlc_output_target_type type;
    uint64_t generation;
    uint32_t width;
    uint32_t height;
    bool request_hdr;
    float sdr_white_nits;
    float display_peak_nits;
    uint64_t adapter_luid;
    uintptr_t metal_device;
    uintptr_t metal_command_queue;
    const char *render_node_utf8;
    const kmediavlc_drm_format_modifier *drm_formats;
    size_t drm_format_count;
    bool acquire_fences;
    bool release_fences;
} kmediavlc_output_target;

typedef struct kmediavlc_frame_info {
    uint32_t struct_size;
    uint32_t bridge_abi_version;
    uint64_t serial;
    uint64_t output_generation;
    int64_t pts_microseconds;
    uint32_t width;
    uint32_t height;
    kmediavlc_pixel_format pixel_format;
    kmediavlc_source_dynamic_range source_dynamic_range;
    kmediavlc_platform_handle_type handle_type;
    /* The frame retains a DMA-BUF platform_handle until frame_release(). */
    uintptr_t platform_handle;
    /* Ownership of a non-negative sync-file descriptor transfers to the caller. */
    intptr_t acquire_fence;
    uint32_t stride;
    uint32_t fourcc;
    uint32_t offset;
    uint64_t modifier;
    uint64_t cpu_byte_count;
    float sdr_white_nits;
    float content_peak_nits;
    bool premultiplied_alpha;
} kmediavlc_frame_info;

typedef void (*kmediavlc_frame_available_cb)(
    void *opaque,
    uint64_t serial,
    uint64_t output_generation);

typedef void (*kmediavlc_playback_state_cb)(
    void *opaque,
    kmediavlc_playback_state state,
    uint64_t media_generation);

typedef struct kmediavlc_player_snapshot {
    uint32_t struct_size;
    uint32_t bridge_abi_version;
    kmediavlc_playback_state state;
    uint64_t media_generation;
    int64_t position_microseconds;
    int64_t duration_microseconds;
    uint32_t video_width;
    uint32_t video_height;
    uint32_t video_frame_rate_num;
    uint32_t video_frame_rate_den;
    uint32_t buffered_permille;
    bool seekable;
} kmediavlc_player_snapshot;

typedef struct kmediavlc_player_config {
    uint32_t struct_size;
    uint32_t bridge_abi_version;
    const char *libvlc_path_utf8;
    const char *plugin_directory_utf8;
    kmediavlc_delivery_mode delivery_mode;
    bool request_hdr;
    float sdr_white_nits;
    float display_peak_nits;
    kmediavlc_frame_available_cb frame_available;
    kmediavlc_playback_state_cb playback_state_changed;
    void *callback_opaque;
} kmediavlc_player_config;

/* Creates a player without loading a child NSView, HWND, GTK widget, or subsurface. */
KMEDIAVLC_API kmediavlc_player *kmediavlc_player_create(const kmediavlc_player_config *config);

/* All strings are UTF-8. Headers are alternating name/value entries. */
KMEDIAVLC_API bool kmediavlc_player_open(
    kmediavlc_player *player,
    const char *uri_utf8,
    const char *const *headers_utf8,
    size_t header_entry_count,
    bool autoplay);

KMEDIAVLC_API bool kmediavlc_player_play(kmediavlc_player *player);
KMEDIAVLC_API bool kmediavlc_player_pause(kmediavlc_player *player);
KMEDIAVLC_API bool kmediavlc_player_stop(kmediavlc_player *player);
KMEDIAVLC_API bool kmediavlc_player_seek(kmediavlc_player *player, int64_t time_microseconds, bool fast);
KMEDIAVLC_API bool kmediavlc_player_set_volume(kmediavlc_player *player, float volume);
KMEDIAVLC_API bool kmediavlc_player_set_rate(kmediavlc_player *player, float rate);
KMEDIAVLC_API bool kmediavlc_player_set_loop(kmediavlc_player *player, bool loop);

/* Updates the producer target and the output luminance negotiation. */
KMEDIAVLC_API bool kmediavlc_player_resize(kmediavlc_player *player, uint32_t width, uint32_t height);
KMEDIAVLC_API bool kmediavlc_player_update_output(
    kmediavlc_player *player,
    const kmediavlc_output_target *target);

KMEDIAVLC_API bool kmediavlc_player_get_snapshot(
    kmediavlc_player *player,
    kmediavlc_player_snapshot *out_snapshot);

/* Returns bridge-owned diagnostics. The pointer stays valid until the next call on this player. */
KMEDIAVLC_API const char *kmediavlc_player_last_error(kmediavlc_player *player);

/*
 * Pulls and transfers ownership of the newest frame; skipped frames are released internally.
 * A returned acquire_fence is caller-owned and must be consumed or closed exactly once.
 */
KMEDIAVLC_API kmediavlc_frame *kmediavlc_player_acquire_latest_frame(
    kmediavlc_player *player,
    kmediavlc_frame_info *out_info);

/*
 * Releases one acquired frame and transfers ownership of the consumer completion fence when
 * available. The bridge always closes or retains a supplied non-negative descriptor.
 */
KMEDIAVLC_API void kmediavlc_frame_release(kmediavlc_frame *frame, intptr_t release_fence);

/* Returns the CPU buffer only for KMEDIAVLC_CPU_ADDRESS frames. */
KMEDIAVLC_API const void *kmediavlc_frame_cpu_pixels(kmediavlc_frame *frame, size_t *out_byte_count);

/* Invalidates callbacks, releases pending frames, then destroys the player. */
KMEDIAVLC_API void kmediavlc_player_destroy(kmediavlc_player *player);

#ifdef __cplusplus
}
#endif

#endif
