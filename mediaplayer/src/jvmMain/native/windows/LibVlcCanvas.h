#pragma once

#include <cstdint>

struct LibVlcCanvasPlayer;

LibVlcCanvasPlayer* lvc_create(const char* libvlcPath, const char* pluginPath, bool nativeVideoOutput);
void lvc_destroy(LibVlcCanvasPlayer* player);
bool lvc_open_uri_with_headers(LibVlcCanvasPlayer* player, const char* uri, const char* requestHeaders, bool startPlayback);
void lvc_close(LibVlcCanvasPlayer* player);
void lvc_play(LibVlcCanvasPlayer* player);
void lvc_pause(LibVlcCanvasPlayer* player);
void lvc_set_volume(LibVlcCanvasPlayer* player, float volume);
float lvc_get_volume(LibVlcCanvasPlayer* player);
void lvc_seek_to(LibVlcCanvasPlayer* player, double timeSeconds);
void lvc_set_playback_speed(LibVlcCanvasPlayer* player, float speed);
float lvc_get_playback_speed(LibVlcCanvasPlayer* player);
void* lvc_lock_frame(LibVlcCanvasPlayer* player, int32_t outInfo[3]);
void lvc_unlock_frame(LibVlcCanvasPlayer* player);
int32_t lvc_get_frame_width(LibVlcCanvasPlayer* player);
int32_t lvc_get_frame_height(LibVlcCanvasPlayer* player);
float lvc_get_frame_rate(LibVlcCanvasPlayer* player);
double lvc_get_duration(LibVlcCanvasPlayer* player);
double lvc_get_current_time(LibVlcCanvasPlayer* player);
bool lvc_is_ended(LibVlcCanvasPlayer* player);
bool lvc_select_audio_track(LibVlcCanvasPlayer* player, int32_t ordinal);
bool lvc_select_subtitle_track(LibVlcCanvasPlayer* player, int32_t ordinal);
bool lvc_disable_subtitles(LibVlcCanvasPlayer* player);
char* lvc_get_audio_track_descriptions(LibVlcCanvasPlayer* player);
char* lvc_get_subtitle_track_descriptions(LibVlcCanvasPlayer* player);
bool lvc_set_native_window(LibVlcCanvasPlayer* player, void* hwnd);
