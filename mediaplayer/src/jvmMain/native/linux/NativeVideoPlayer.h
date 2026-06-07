// NativeVideoPlayer.h — Linux GStreamer-based native video player
// Pure C API for JNI consumption.

#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Native API version — bump when the exported JNI/native API changes.
#define NATIVE_VIDEO_PLAYER_VERSION 2

// Opaque player handle
typedef struct VideoPlayer VideoPlayer;

int nvp_get_native_version(void);

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

// Timing
double nvp_get_duration(VideoPlayer* p);
double nvp_get_current_time(VideoPlayer* p);

// Metadata (caller must free returned strings with free())
char*   nvp_get_title(VideoPlayer* p);
int64_t nvp_get_bitrate(VideoPlayer* p);
char*   nvp_get_mime_type(VideoPlayer* p);
int32_t nvp_get_audio_channels(VideoPlayer* p);
int32_t nvp_get_audio_sample_rate(VideoPlayer* p);
float   nvp_get_frame_rate(VideoPlayer* p);

// End-of-stream notification (consumes the flag)
int32_t nvp_consume_did_play_to_end(VideoPlayer* p);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
