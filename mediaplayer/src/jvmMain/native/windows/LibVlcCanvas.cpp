#include "LibVlcCanvas.h"

#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

typedef long long libvlc_time_t;
typedef struct libvlc_instance_t libvlc_instance_t;
typedef struct libvlc_media_t libvlc_media_t;
typedef struct libvlc_media_player_t libvlc_media_player_t;
typedef struct libvlc_track_description_t {
    int i_id;
    char* psz_name;
    struct libvlc_track_description_t* p_next;
} libvlc_track_description_t;

enum libvlc_state_t {
    LIBVLC_STATE_NOTHING_SPECIAL = 0,
    LIBVLC_STATE_OPENING = 1,
    LIBVLC_STATE_BUFFERING = 2,
    LIBVLC_STATE_PLAYING = 3,
    LIBVLC_STATE_PAUSED = 4,
    LIBVLC_STATE_STOPPED = 5,
    LIBVLC_STATE_ENDED = 6,
    LIBVLC_STATE_ERROR = 7,
};

struct LibVlcApi {
    HMODULE module = nullptr;
    HMODULE core = nullptr;
    libvlc_instance_t* (*new_instance)(int, const char* const*) = nullptr;
    void (*release_instance)(libvlc_instance_t*) = nullptr;
    libvlc_media_t* (*media_new_location)(libvlc_instance_t*, const char*) = nullptr;
    libvlc_media_t* (*media_new_path)(libvlc_instance_t*, const char*) = nullptr;
    void (*media_add_option)(libvlc_media_t*, const char*) = nullptr;
    void (*media_release)(libvlc_media_t*) = nullptr;
    libvlc_media_player_t* (*media_player_new_from_media)(libvlc_media_t*) = nullptr;
    void (*media_player_release)(libvlc_media_player_t*) = nullptr;
    int (*media_player_play)(libvlc_media_player_t*) = nullptr;
    void (*media_player_pause)(libvlc_media_player_t*) = nullptr;
    void (*media_player_stop)(libvlc_media_player_t*) = nullptr;
    libvlc_time_t (*media_player_get_time)(libvlc_media_player_t*) = nullptr;
    void (*media_player_set_time)(libvlc_media_player_t*, libvlc_time_t) = nullptr;
    libvlc_time_t (*media_player_get_length)(libvlc_media_player_t*) = nullptr;
    int (*audio_set_volume)(libvlc_media_player_t*, int) = nullptr;
    int (*audio_get_volume)(libvlc_media_player_t*) = nullptr;
    int (*media_player_set_rate)(libvlc_media_player_t*, float) = nullptr;
    float (*media_player_get_rate)(libvlc_media_player_t*) = nullptr;
    libvlc_state_t (*media_player_get_state)(libvlc_media_player_t*) = nullptr;
    void (*video_set_callbacks)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*) = nullptr;
    void (*video_set_format_callbacks)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*)) = nullptr;
    libvlc_track_description_t* (*audio_get_track_description)(libvlc_media_player_t*) = nullptr;
    int (*audio_set_track)(libvlc_media_player_t*, int) = nullptr;
    libvlc_track_description_t* (*video_get_spu_description)(libvlc_media_player_t*) = nullptr;
    int (*video_set_spu)(libvlc_media_player_t*, int) = nullptr;
    void (*track_description_list_release)(libvlc_track_description_t*) = nullptr;
};

struct LibVlcCanvasPlayer {
    LibVlcApi api;
    libvlc_instance_t* instance = nullptr;
    libvlc_media_player_t* player = nullptr;
    CRITICAL_SECTION frameLock{};
    unsigned char* frame = nullptr;
    unsigned char* bgraFrame = nullptr;
    unsigned char* readFrame = nullptr;
    size_t bgraFrameSize = 0;
    unsigned width = 0;
    unsigned height = 0;
    unsigned pitch = 0;
    unsigned yPitch = 0;
    unsigned uPitch = 0;
    unsigned vPitch = 0;
    size_t uOffset = 0;
    size_t vOffset = 0;
    bool frameReady = false;
    int pendingAudioOrdinal = -2;
    int pendingSpuOrdinal = -2;
};

static FARPROC sym(HMODULE module, const char* name) {
    return GetProcAddress(module, name);
}

static std::string parentDir(const char* path) {
    if (!path) return {};
    std::string value(path);
    const size_t slash = value.find_last_of("\\/");
    return slash == std::string::npos ? std::string() : value.substr(0, slash);
}

static bool loadApi(const char* libvlcPath, LibVlcApi& api) {
    const std::string dir = parentDir(libvlcPath);
    if (!dir.empty()) {
        SetDllDirectoryA(dir.c_str());
        api.core = LoadLibraryA((dir + "\\libvlccore.dll").c_str());
    }
    api.module = LoadLibraryA(libvlcPath);
    if (!api.module) return false;

#define LOAD_VLC(field, symbol) api.field = reinterpret_cast<decltype(api.field)>(sym(api.module, symbol))
    LOAD_VLC(new_instance, "libvlc_new");
    LOAD_VLC(release_instance, "libvlc_release");
    LOAD_VLC(media_new_location, "libvlc_media_new_location");
    LOAD_VLC(media_new_path, "libvlc_media_new_path");
    LOAD_VLC(media_add_option, "libvlc_media_add_option");
    LOAD_VLC(media_release, "libvlc_media_release");
    LOAD_VLC(media_player_new_from_media, "libvlc_media_player_new_from_media");
    LOAD_VLC(media_player_release, "libvlc_media_player_release");
    LOAD_VLC(media_player_play, "libvlc_media_player_play");
    LOAD_VLC(media_player_pause, "libvlc_media_player_pause");
    LOAD_VLC(media_player_stop, "libvlc_media_player_stop");
    LOAD_VLC(media_player_get_time, "libvlc_media_player_get_time");
    LOAD_VLC(media_player_set_time, "libvlc_media_player_set_time");
    LOAD_VLC(media_player_get_length, "libvlc_media_player_get_length");
    LOAD_VLC(audio_set_volume, "libvlc_audio_set_volume");
    LOAD_VLC(audio_get_volume, "libvlc_audio_get_volume");
    LOAD_VLC(media_player_set_rate, "libvlc_media_player_set_rate");
    LOAD_VLC(media_player_get_rate, "libvlc_media_player_get_rate");
    LOAD_VLC(media_player_get_state, "libvlc_media_player_get_state");
    LOAD_VLC(video_set_callbacks, "libvlc_video_set_callbacks");
    LOAD_VLC(video_set_format_callbacks, "libvlc_video_set_format_callbacks");
    LOAD_VLC(audio_get_track_description, "libvlc_audio_get_track_description");
    LOAD_VLC(audio_set_track, "libvlc_audio_set_track");
    LOAD_VLC(video_get_spu_description, "libvlc_video_get_spu_description");
    LOAD_VLC(video_set_spu, "libvlc_video_set_spu");
    LOAD_VLC(track_description_list_release, "libvlc_track_description_list_release");
#undef LOAD_VLC

    return api.new_instance && api.release_instance && api.media_new_location && api.media_new_path &&
        api.media_add_option && api.media_release && api.media_player_new_from_media &&
        api.media_player_release && api.media_player_play && api.media_player_pause &&
        api.media_player_stop && api.media_player_get_time && api.media_player_set_time &&
        api.media_player_get_length && api.audio_set_volume && api.audio_get_volume &&
        api.media_player_set_rate && api.media_player_get_rate && api.media_player_get_state &&
        api.video_set_callbacks && api.video_set_format_callbacks && api.audio_get_track_description &&
        api.audio_set_track && api.video_get_spu_description && api.video_set_spu &&
        api.track_description_list_release;
}

static unsigned formatCb(void** opaque, char* chroma, unsigned* width, unsigned* height, unsigned* pitches, unsigned* lines) {
    auto* p = static_cast<LibVlcCanvasPlayer*>(*opaque);
    if (!p || !width || !height || *width == 0 || *height == 0) return 0;
    const unsigned frameWidth = (*width) & ~1u;
    const unsigned frameHeight = (*height) & ~1u;
    if (frameWidth == 0 || frameHeight == 0) return 0;

    memcpy(chroma, "I420", 4);
    const unsigned yPitch = frameWidth;
    const unsigned uPitch = frameWidth / 2u;
    const unsigned vPitch = frameWidth / 2u;
    const size_t ySize = static_cast<size_t>(yPitch) * frameHeight;
    const size_t uSize = static_cast<size_t>(uPitch) * (frameHeight / 2u);
    const size_t vSize = static_cast<size_t>(vPitch) * (frameHeight / 2u);
    const unsigned bgraPitch = frameWidth * 4u;
    const size_t frameSize = ySize + uSize + vSize;
    const size_t bgraSize = static_cast<size_t>(bgraPitch) * frameHeight;

    auto* frame = static_cast<unsigned char*>(calloc(1, frameSize));
    auto* bgra = static_cast<unsigned char*>(calloc(1, bgraSize));
    auto* read = static_cast<unsigned char*>(calloc(1, bgraSize));
    if (!frame || !bgra || !read) {
        free(frame); free(bgra); free(read);
        return 0;
    }

    EnterCriticalSection(&p->frameLock);
    free(p->frame); free(p->bgraFrame); free(p->readFrame);
    p->frame = frame;
    p->bgraFrame = bgra;
    p->readFrame = read;
    p->bgraFrameSize = bgraSize;
    p->width = frameWidth;
    p->height = frameHeight;
    p->pitch = bgraPitch;
    p->yPitch = yPitch;
    p->uPitch = uPitch;
    p->vPitch = vPitch;
    p->uOffset = ySize;
    p->vOffset = ySize + uSize;
    p->frameReady = false;
    LeaveCriticalSection(&p->frameLock);

    pitches[0] = yPitch;
    pitches[1] = uPitch;
    pitches[2] = vPitch;
    lines[0] = frameHeight;
    lines[1] = frameHeight / 2u;
    lines[2] = frameHeight / 2u;
    return 1;
}

static void formatCleanupCb(void*) {}

static void* lockCb(void* opaque, void** planes) {
    auto* p = static_cast<LibVlcCanvasPlayer*>(opaque);
    if (!p || !planes) return nullptr;
    EnterCriticalSection(&p->frameLock);
    planes[0] = p->frame;
    planes[1] = p->frame ? p->frame + p->uOffset : nullptr;
    planes[2] = p->frame ? p->frame + p->vOffset : nullptr;
    return p;
}

static unsigned char clampU8(int value) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return static_cast<unsigned char>(value);
}

static void convertI420ToBgra(LibVlcCanvasPlayer* p) {
    if (!p || !p->frame || !p->bgraFrame || p->width == 0 || p->height == 0) return;
    const auto* yPlane = p->frame;
    const auto* uPlane = p->frame + p->uOffset;
    const auto* vPlane = p->frame + p->vOffset;
    for (unsigned y = 0; y < p->height; y++) {
        const auto* yRow = yPlane + static_cast<size_t>(y) * p->yPitch;
        const auto* uRow = uPlane + static_cast<size_t>(y / 2u) * p->uPitch;
        const auto* vRow = vPlane + static_cast<size_t>(y / 2u) * p->vPitch;
        auto* dst = p->bgraFrame + static_cast<size_t>(y) * p->pitch;
        for (unsigned x = 0; x < p->width; x++) {
            int c = static_cast<int>(yRow[x]) - 16;
            int d = static_cast<int>(uRow[x / 2u]) - 128;
            int e = static_cast<int>(vRow[x / 2u]) - 128;
            if (c < 0) c = 0;
            dst[2] = clampU8((298 * c + 409 * e + 128) >> 8);
            dst[1] = clampU8((298 * c - 100 * d - 208 * e + 128) >> 8);
            dst[0] = clampU8((298 * c + 516 * d + 128) >> 8);
            dst[3] = 255;
            dst += 4;
        }
    }
    p->frameReady = true;
}

static void unlockCb(void* opaque, void*, void* const*) {
    auto* p = static_cast<LibVlcCanvasPlayer*>(opaque);
    if (p) {
        convertI420ToBgra(p);
        LeaveCriticalSection(&p->frameLock);
    }
}

static void displayCb(void*, void*) {}

static bool hasUriScheme(const char* uri) {
    return uri && strstr(uri, "://") != nullptr;
}

static int selectDescriptionOrdinal(libvlc_track_description_t* descriptions, int ordinal) {
    int current = 0;
    for (auto* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;
        if (current == ordinal) return item->i_id;
        current++;
    }
    return -2;
}

static bool applyAudioOrdinal(LibVlcCanvasPlayer* p, int ordinal) {
    if (!p || !p->player || ordinal < 0) return false;
    auto* descriptions = p->api.audio_get_track_description(p->player);
    int id = selectDescriptionOrdinal(descriptions, ordinal);
    if (descriptions) p->api.track_description_list_release(descriptions);
    return id != -2 && p->api.audio_set_track(p->player, id) == 0;
}

static bool applySpuOrdinal(LibVlcCanvasPlayer* p, int ordinal) {
    if (!p || !p->player) return false;
    if (ordinal < 0) return p->api.video_set_spu(p->player, -1) == 0;
    auto* descriptions = p->api.video_get_spu_description(p->player);
    int id = selectDescriptionOrdinal(descriptions, ordinal);
    if (descriptions) p->api.track_description_list_release(descriptions);
    return id != -2 && p->api.video_set_spu(p->player, id) == 0;
}

static void applyPendingTracks(LibVlcCanvasPlayer* p) {
    if (!p || !p->player) return;
    if (p->pendingAudioOrdinal >= 0) applyAudioOrdinal(p, p->pendingAudioOrdinal);
    if (p->pendingSpuOrdinal >= -1) applySpuOrdinal(p, p->pendingSpuOrdinal);
}

static void addHeaderOptions(libvlc_media_t* media, LibVlcCanvasPlayer* p, const char* headers) {
    if (!media || !p || !headers || !headers[0]) return;
    char* copy = _strdup(headers);
    if (!copy) return;
    char* context = nullptr;
    for (char* line = strtok_s(copy, "\n", &context); line; line = strtok_s(nullptr, "\n", &context)) {
        while (*line == ' ' || *line == '\t' || *line == '\r') line++;
        char* separator = strchr(line, ':');
        if (!separator) continue;
        *separator = '\0';
        char* value = separator + 1;
        while (*value == ' ' || *value == '\t') value++;
        if (!line[0] || !value[0]) continue;

        const char* optionName = nullptr;
        if (_stricmp(line, "User-Agent") == 0) optionName = ":http-user-agent=";
        else if (_stricmp(line, "Referer") == 0 || _stricmp(line, "Referrer") == 0) optionName = ":http-referrer=";
        else if (_stricmp(line, "Cookie") == 0) optionName = ":http-cookie=";

        if (optionName) {
            std::string option = std::string(optionName) + value;
            p->api.media_add_option(media, option.c_str());
        }
        std::string custom = std::string(":http-custom-header=") + line + ": " + value;
        p->api.media_add_option(media, custom.c_str());
    }
    free(copy);
}

LibVlcCanvasPlayer* lvc_create(const char* libvlcPath, const char* pluginPath) {
    auto* p = new LibVlcCanvasPlayer();
    InitializeCriticalSection(&p->frameLock);
    if (!loadApi(libvlcPath, p->api)) {
        lvc_destroy(p);
        return nullptr;
    }
    _putenv_s("VLC_PLUGIN_PATH", pluginPath ? pluginPath : "");
    const char* args[] = {
        "--no-video-title-show",
        "--no-osd",
        "--quiet",
        "--codec=avcodec",
        "--avcodec-hw=none",
        "--no-avcodec-dr",
    };
    p->instance = p->api.new_instance(static_cast<int>(sizeof(args) / sizeof(args[0])), args);
    if (!p->instance) {
        lvc_destroy(p);
        return nullptr;
    }
    return p;
}

void lvc_destroy(LibVlcCanvasPlayer* p) {
    if (!p) return;
    if (p->player) {
        p->api.media_player_stop(p->player);
        p->api.media_player_release(p->player);
    }
    if (p->instance) p->api.release_instance(p->instance);
    EnterCriticalSection(&p->frameLock);
    free(p->frame);
    free(p->bgraFrame);
    free(p->readFrame);
    LeaveCriticalSection(&p->frameLock);
    DeleteCriticalSection(&p->frameLock);
    if (p->api.module) FreeLibrary(p->api.module);
    if (p->api.core) FreeLibrary(p->api.core);
    delete p;
}

bool lvc_open_uri_with_headers(LibVlcCanvasPlayer* p, const char* uri, const char* headers) {
    if (!p || !p->instance || !uri) return false;
    if (p->player) {
        p->api.media_player_stop(p->player);
        p->api.media_player_release(p->player);
        p->player = nullptr;
    }
    EnterCriticalSection(&p->frameLock);
    p->frameReady = false;
    LeaveCriticalSection(&p->frameLock);

    auto* media = hasUriScheme(uri) ? p->api.media_new_location(p->instance, uri) : p->api.media_new_path(p->instance, uri);
    if (!media) return false;
    addHeaderOptions(media, p, headers);
    p->player = p->api.media_player_new_from_media(media);
    p->api.media_release(media);
    if (!p->player) return false;
    p->api.video_set_callbacks(p->player, lockCb, unlockCb, displayCb, p);
    p->api.video_set_format_callbacks(p->player, formatCb, formatCleanupCb);
    if (p->api.media_player_play(p->player) != 0) return false;
    applyPendingTracks(p);
    return true;
}

void lvc_close(LibVlcCanvasPlayer* p) {
    if (!p || !p->player) return;
    p->api.media_player_stop(p->player);
    p->api.media_player_release(p->player);
    p->player = nullptr;
    EnterCriticalSection(&p->frameLock);
    p->frameReady = false;
    LeaveCriticalSection(&p->frameLock);
}

void lvc_play(LibVlcCanvasPlayer* p) {
    if (p && p->player) {
        p->api.media_player_play(p->player);
        applyPendingTracks(p);
    }
}

void lvc_pause(LibVlcCanvasPlayer* p) {
    if (p && p->player) p->api.media_player_pause(p->player);
}

void lvc_set_volume(LibVlcCanvasPlayer* p, float volume) {
    if (!p || !p->player) return;
    int scaled = static_cast<int>(volume * 100.0f);
    if (scaled < 0) scaled = 0;
    if (scaled > 100) scaled = 100;
    p->api.audio_set_volume(p->player, scaled);
}

float lvc_get_volume(LibVlcCanvasPlayer* p) {
    return (p && p->player) ? static_cast<float>(p->api.audio_get_volume(p->player)) / 100.0f : 0.0f;
}

void lvc_seek_to(LibVlcCanvasPlayer* p, double timeSeconds) {
    if (p && p->player) {
        p->api.media_player_set_time(p->player, static_cast<libvlc_time_t>(timeSeconds * 1000.0));
        applyPendingTracks(p);
    }
}

void lvc_set_playback_speed(LibVlcCanvasPlayer* p, float speed) {
    if (p && p->player) p->api.media_player_set_rate(p->player, speed);
}

float lvc_get_playback_speed(LibVlcCanvasPlayer* p) {
    return (p && p->player) ? p->api.media_player_get_rate(p->player) : 1.0f;
}

void* lvc_lock_frame(LibVlcCanvasPlayer* p, int32_t outInfo[3]) {
    if (!p || !outInfo) return nullptr;
    void* result = nullptr;
    EnterCriticalSection(&p->frameLock);
    if (p->frameReady && p->bgraFrame && p->readFrame && p->width > 0 && p->height > 0) {
        const size_t size = static_cast<size_t>(p->pitch) * p->height;
        if (size <= p->bgraFrameSize) {
            memcpy(p->readFrame, p->bgraFrame, size);
            outInfo[0] = static_cast<int32_t>(p->width);
            outInfo[1] = static_cast<int32_t>(p->height);
            outInfo[2] = static_cast<int32_t>(p->pitch);
            result = p->readFrame;
        }
    }
    LeaveCriticalSection(&p->frameLock);
    return result;
}

void lvc_unlock_frame(LibVlcCanvasPlayer*) {}
int32_t lvc_get_frame_width(LibVlcCanvasPlayer* p) { return p ? static_cast<int32_t>(p->width) : 0; }
int32_t lvc_get_frame_height(LibVlcCanvasPlayer* p) { return p ? static_cast<int32_t>(p->height) : 0; }
float lvc_get_frame_rate(LibVlcCanvasPlayer*) { return 30.0f; }
double lvc_get_duration(LibVlcCanvasPlayer* p) {
    if (!p || !p->player) return 0.0;
    libvlc_time_t length = p->api.media_player_get_length(p->player);
    return length > 0 ? static_cast<double>(length) / 1000.0 : 0.0;
}
double lvc_get_current_time(LibVlcCanvasPlayer* p) {
    if (!p || !p->player) return 0.0;
    libvlc_time_t time = p->api.media_player_get_time(p->player);
    return time > 0 ? static_cast<double>(time) / 1000.0 : 0.0;
}
bool lvc_is_ended(LibVlcCanvasPlayer* p) {
    return p && p->player && p->api.media_player_get_state(p->player) == LIBVLC_STATE_ENDED;
}
bool lvc_select_audio_track(LibVlcCanvasPlayer* p, int32_t ordinal) {
    if (!p) return false;
    p->pendingAudioOrdinal = ordinal;
    return applyAudioOrdinal(p, ordinal);
}
bool lvc_select_subtitle_track(LibVlcCanvasPlayer* p, int32_t ordinal) {
    if (!p) return false;
    p->pendingSpuOrdinal = ordinal;
    return applySpuOrdinal(p, ordinal);
}
bool lvc_disable_subtitles(LibVlcCanvasPlayer* p) {
    if (!p) return false;
    p->pendingSpuOrdinal = -1;
    return applySpuOrdinal(p, -1);
}

static char* descriptionsToString(LibVlcCanvasPlayer* p, libvlc_track_description_t* descriptions) {
    if (!p || !descriptions) return nullptr;
    std::string out;
    int ordinal = 0;
    for (auto* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;
        out += std::to_string(ordinal++);
        out += '\t';
        out += item->psz_name ? item->psz_name : "";
        out += '\n';
    }
    if (out.empty()) return nullptr;
    char* result = static_cast<char*>(malloc(out.size() + 1));
    if (!result) return nullptr;
    memcpy(result, out.c_str(), out.size() + 1);
    return result;
}

char* lvc_get_audio_track_descriptions(LibVlcCanvasPlayer* p) {
    if (!p || !p->player) return nullptr;
    auto* descriptions = p->api.audio_get_track_description(p->player);
    char* result = descriptionsToString(p, descriptions);
    if (descriptions) p->api.track_description_list_release(descriptions);
    return result;
}

char* lvc_get_subtitle_track_descriptions(LibVlcCanvasPlayer* p) {
    if (!p || !p->player) return nullptr;
    auto* descriptions = p->api.video_get_spu_description(p->player);
    char* result = descriptionsToString(p, descriptions);
    if (descriptions) p->api.track_description_list_release(descriptions);
    return result;
}
