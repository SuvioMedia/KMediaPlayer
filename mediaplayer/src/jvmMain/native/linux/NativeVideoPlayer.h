// NativeVideoPlayer.h — Linux GStreamer-based native video player
// Pure C API for JNI consumption.

#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include <stdint.h>

#include "LinuxVulkanProjection.h"

#ifdef __cplusplus
extern "C" {
#endif

// Native API version — bump when the exported JNI/native API changes.
#define NATIVE_VIDEO_PLAYER_VERSION 11

enum {
    NVP_GSTREAMER_WAYLAND_SINK = 1 << 0,
    NVP_GSTREAMER_VULKAN_UPLOAD = 1 << 1,
    NVP_GSTREAMER_VULKAN_COLOR_CONVERT = 1 << 2,
    NVP_GSTREAMER_VULKAN_SHADER_SPV = 1 << 3,
    NVP_GSTREAMER_VULKAN_OVERLAY_COMPOSITOR = 1 << 4,
};

enum {
    NVP_WAYLAND_OUTPUT_ATTACHED = 1 << 0,
    NVP_WAYLAND_OUTPUT_CAPS_NEGOTIATED = 1 << 1,
    NVP_WAYLAND_OUTPUT_TEN_BIT = 1 << 2,
    NVP_WAYLAND_OUTPUT_PQ = 1 << 3,
    NVP_WAYLAND_OUTPUT_HLG = 1 << 4,
    NVP_WAYLAND_OUTPUT_DMABUF = 1 << 5,
    NVP_WAYLAND_OUTPUT_ERROR = 1 << 6,
    NVP_WAYLAND_OUTPUT_FIRST_FRAME = 1 << 7,
    NVP_WAYLAND_OUTPUT_HDR10_PLUS_APPLIED = 1 << 8,
    NVP_WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE = 1 << 9,
};

// Opaque player handle
typedef struct VideoPlayer VideoPlayer;

int nvp_get_native_version(void);
void nvp_get_gstreamer_runtime_info(uint32_t out_info[5]);

// Lifecycle
VideoPlayer* nvp_create(void);
void         nvp_destroy(VideoPlayer* p);

// Playback control
int  nvp_open_uri(VideoPlayer* p, const char* uri);
int  nvp_open_uri_with_headers(VideoPlayer* p, const char* uri, const char* request_headers);
void nvp_play(VideoPlayer* p);
void nvp_pause(VideoPlayer* p);
void nvp_set_volume(VideoPlayer* p, float volume);
float nvp_get_volume(VideoPlayer* p);
void nvp_seek_to(VideoPlayer* p, double time_seconds);
void nvp_set_playback_speed(VideoPlayer* p, float speed);
float nvp_get_playback_speed(VideoPlayer* p);

// Frame access
void*   nvp_lock_latest_frame(VideoPlayer* p, int32_t out_info[3]);
void    nvp_unlock_latest_frame(VideoPlayer* p);
int32_t nvp_get_frame_width(VideoPlayer* p);
int32_t nvp_get_frame_height(VideoPlayer* p);
int32_t nvp_set_output_size(VideoPlayer* p, int32_t width, int32_t height);

// JBR WLToolkit + GStreamer waylandsink direct-output path.
int32_t nvp_attach_wayland_output(
    VideoPlayer* p,
    uintptr_t display,
    uintptr_t parent_surface,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height
);
int32_t nvp_attach_wayland_projection_output(
    VideoPlayer* p,
    uintptr_t display,
    uintptr_t parent_surface,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    const LinuxVulkanProjectionConfiguration* configuration
);
void nvp_update_wayland_projection_configuration(
    VideoPlayer* p,
    const LinuxVulkanProjectionConfiguration* configuration
);
void nvp_detach_wayland_output(VideoPlayer* p);
int32_t nvp_get_wayland_output_state(VideoPlayer* p);

// Active decoded color snapshot. Values are generation, bit depth, primaries,
// transfer, matrix, range and an authoritative-unknown marker. Enum values are
// the stable JNI wire values documented by JvmDecodedVideoColorSignalCodec.
void nvp_get_decoded_color_info(VideoPlayer* p, int32_t out_info[7]);

// Timing
double nvp_get_duration(VideoPlayer* p);
double nvp_get_current_time(VideoPlayer* p);

// Metadata (caller must free returned strings with free())
char*   nvp_get_title(VideoPlayer* p);
int64_t nvp_get_bitrate(VideoPlayer* p);
char*   nvp_get_mime_type(VideoPlayer* p);
char*   nvp_get_video_decoder_name(VideoPlayer* p);
int32_t nvp_get_audio_channels(VideoPlayer* p);
int32_t nvp_get_audio_sample_rate(VideoPlayer* p);
float   nvp_get_frame_rate(VideoPlayer* p);

// End-of-stream notification (consumes the flag)
int32_t nvp_consume_did_play_to_end(VideoPlayer* p);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
