#ifndef LIBVLC_CANVAS_H
#define LIBVLC_CANVAS_H

#include <stdint.h>

typedef struct LibVlcCanvasPlayer LibVlcCanvasPlayer;

LibVlcCanvasPlayer* lvc_create(const char* libvlc_path, const char* plugin_path, int native_video_output);
void lvc_destroy(LibVlcCanvasPlayer* player);
int lvc_open_uri_with_headers(LibVlcCanvasPlayer* player, const char* uri, const char* request_headers, int start_playback);
void lvc_play(LibVlcCanvasPlayer* player);
void lvc_pause(LibVlcCanvasPlayer* player);
void lvc_set_volume(LibVlcCanvasPlayer* player, float volume);
float lvc_get_volume(LibVlcCanvasPlayer* player);
void lvc_seek_to(LibVlcCanvasPlayer* player, double time_seconds);
void lvc_set_playback_speed(LibVlcCanvasPlayer* player, float speed);
float lvc_get_playback_speed(LibVlcCanvasPlayer* player);
void* lvc_lock_frame(LibVlcCanvasPlayer* player, int32_t out_info[3]);
void lvc_unlock_frame(LibVlcCanvasPlayer* player);
int32_t lvc_get_frame_width(LibVlcCanvasPlayer* player);
int32_t lvc_get_frame_height(LibVlcCanvasPlayer* player);
double lvc_get_duration(LibVlcCanvasPlayer* player);
double lvc_get_current_time(LibVlcCanvasPlayer* player);
float lvc_get_frame_rate(LibVlcCanvasPlayer* player);
int32_t lvc_consume_did_play_to_end(LibVlcCanvasPlayer* player);
int32_t lvc_select_audio_track(LibVlcCanvasPlayer* player, int32_t ordinal);
int32_t lvc_select_subtitle_track(LibVlcCanvasPlayer* player, int32_t ordinal);
int32_t lvc_disable_subtitles(LibVlcCanvasPlayer* player);
char* lvc_get_audio_track_descriptions(LibVlcCanvasPlayer* player);
char* lvc_get_subtitle_track_descriptions(LibVlcCanvasPlayer* player);
int lvc_set_native_window(LibVlcCanvasPlayer* player, uint32_t xwindow);

#endif
