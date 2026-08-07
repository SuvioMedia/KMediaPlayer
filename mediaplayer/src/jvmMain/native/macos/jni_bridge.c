// jni_bridge.c — JNI bridge for macOS NativeVideoPlayer
// Calls Swift @_cdecl exports and registers them as JNI native methods.

#include <jni.h>
#include <ctype.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <limits.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#ifdef __OBJC__
#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import <VideoToolbox/VideoToolbox.h>
#import <objc/runtime.h>
#endif

// ---------------------------------------------------------------------------
// Forward declarations of Swift C exports
// ---------------------------------------------------------------------------

extern void*  createVideoPlayer(void);
extern void   openUri(void* ctx, const char* uri);
extern void   openUriWithHeaders(void* ctx, const char* uri, const char* headersJson);
extern uint64_t prepareUriReplacement(void* ctx, const char* uri, const char* headersJson);
extern int32_t getUriReplacementStatus(void* ctx, uint64_t token);
extern const char* getUriReplacementError(void* ctx, uint64_t token);
extern int32_t commitUriReplacement(void* ctx, uint64_t token);
extern void cancelUriReplacement(void* ctx, uint64_t token);
extern void   playVideo(void* ctx);
extern void   pauseVideo(void* ctx);
extern int32_t isReadyForPlayback(void* ctx);
extern void   setVolume(void* ctx, float volume);
extern float  getVolume(void* ctx);
extern void*  lockLatestFrame(void* ctx, int32_t* outInfo);
extern void   unlockLatestFrame(void* ctx);
extern int32_t getFrameWidth(void* ctx);
extern int32_t getFrameHeight(void* ctx);
extern double  getDisplayAspectRatio(void* ctx);
extern int32_t setOutputSize(void* ctx, int32_t width, int32_t height);
extern void*   getHdrMetalLayer(void* ctx);
extern void    setHdrMetalLayerSize(void* ctx, int32_t width, int32_t height, double scale);
extern int32_t setHdrMetalTextureOutput(void* ctx, void* commandQueue);
extern void    setHdrMetalTextureViewportSize(void* ctx, int32_t width, int32_t height);
extern int32_t getHdrMetalTextureOutputInfo(void* ctx, int64_t* values);
extern void    setHdrMetalPreferred(void* ctx, int32_t preferred);
extern void    setHdrToneMappingEnabled(void* ctx, int32_t enabled);
extern int32_t setHdrMetalProjectionConfiguration(void* ctx, const char* configuration);
extern const char* getHdrRendererFailure(void* ctx);
extern void    setHdrMetalContentScaleMode(void* ctx, int32_t mode);
extern void    detachHdrMetalLayer(void* ctx);
extern int32_t isHdrMetalAvailable(void* ctx);
extern int32_t isHdrOutputReady(void* ctx);
extern float  getVideoFrameRate(void* ctx);
extern float  getScreenRefreshRate(void* ctx);
extern float  getCaptureFrameRate(void* ctx);
extern const char* getPlaybackDiagnostics(void* ctx);
extern double getVideoDuration(void* ctx);
extern double getCurrentTime(void* ctx);
extern void   seekTo(void* ctx, double time);
extern void   disposeVideoPlayer(void* ctx);
extern void   setPlaybackSpeed(void* ctx, float speed);
extern float  getPlaybackSpeed(void* ctx);
extern const char* getVideoTitle(void* ctx);
extern int64_t     getVideoBitrate(void* ctx);
extern const char* getVideoMimeType(void* ctx);
extern const char* getVideoColorInfo(void* ctx);
extern int32_t getAudioChannels(void* ctx);
extern int32_t getAudioSampleRate(void* ctx);
extern int32_t consumeDidPlayToEnd(void* ctx);

#ifdef __OBJC__
/**
 * Native AppKit child mounted and sized by Nucleus Tao. The backend owns the view and its
 * rendering layer; Compose controls are rendered in Nucleus' sibling overlay.
 */
@interface KMPNativeVideoView : NSView {
    void* _kmpHdrContext;
    CALayer* _kmpHostedLayer;
}
- (void)setKmpHdrContext:(void*)context;
- (void)setKmpHostedLayer:(CALayer*)layer;
- (void)updateKmpHostedLayerSize;
@end

@implementation KMPNativeVideoView
- (NSView*)hitTest:(NSPoint)point {
    (void)point;
    return nil;
}

- (BOOL)acceptsFirstResponder {
    return NO;
}

- (void)setKmpHdrContext:(void*)context {
    _kmpHdrContext = context;
    [self updateKmpHostedLayerSize];
}

- (void)setKmpHostedLayer:(CALayer*)layer {
    if (_kmpHostedLayer == layer) return;
    [_kmpHostedLayer removeFromSuperlayer];
    [_kmpHostedLayer release];
    _kmpHostedLayer = [layer retain];

    [self setWantsLayer:YES];
    CALayer* container = [self layer];
    [container setMasksToBounds:YES];
    [container setBackgroundColor:[[NSColor blackColor] CGColor]];
    if (_kmpHostedLayer) [container addSublayer:_kmpHostedLayer];
    [self updateKmpHostedLayerSize];
}

- (void)dealloc {
    [self setKmpHostedLayer:nil];
    [super dealloc];
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    [self updateKmpHostedLayerSize];
}

- (void)setFrameSize:(NSSize)newSize {
    [super setFrameSize:newSize];
    [self updateKmpHostedLayerSize];
}

- (void)viewDidChangeBackingProperties {
    [super viewDidChangeBackingProperties];
    [self updateKmpHostedLayerSize];
}

- (void)updateKmpHostedLayerSize {
    if (!_kmpHdrContext || !_kmpHostedLayer) return;
    NSRect bounds = [self bounds];
    CGFloat scale = [[self window] backingScaleFactor];
    if (scale <= 0.0) scale = 1.0;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [_kmpHostedLayer setFrame:bounds];
    [_kmpHostedLayer setContentsScale:scale];
    [CATransaction commit];
    setHdrMetalLayerSize(
        _kmpHdrContext,
        (int32_t)llround(bounds.size.width),
        (int32_t)llround(bounds.size.height),
        (double)scale
    );
}
@end
#endif

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

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

typedef enum {
    PLAYER_KIND_AV = 1,
    PLAYER_KIND_LIBVLC = 2
} PlayerKind;

typedef struct {
    PlayerKind kind;
    void* ctx;
    // AVPlayerLayer host owned by this player. Keeping it on the handle avoids
    // one player's attach/detach operation stealing another player's layer.
    void* native_view;
} NativePlayerHandle;

static NativePlayerHandle* toHandle(jlong h) {
    return (NativePlayerHandle*)(uintptr_t)(uint64_t)h;
}

static void* avCtx(NativePlayerHandle* handle) {
    return (handle && handle->kind == PLAYER_KIND_AV) ? handle->ctx : NULL;
}

static void run_on_appkit_main_sync(dispatch_function_t operation, void* context) {
#ifdef __OBJC__
    if (pthread_main_np()) {
        operation(context);
    } else {
        dispatch_sync_f(dispatch_get_main_queue(), context, operation);
    }
#else
    operation(context);
#endif
}

#ifdef __OBJC__
typedef struct {
    NSView* view;
} KMPReleaseNativeViewContext;

static void release_native_view_on_appkit_main(void* raw_context) {
    KMPReleaseNativeViewContext* context = (KMPReleaseNativeViewContext*)raw_context;
    if (!context || !context->view) return;
    @autoreleasepool {
        [context->view removeFromSuperview];
        [context->view setLayer:nil];
        [context->view release];
        context->view = nil;
    }
}
#endif

// ---------------------------------------------------------------------------
// Optional libVLC backend
// ---------------------------------------------------------------------------

enum {
    LIBVLC_MEMORY_MAX_DIMENSION = 2560,
    BGRA_BYTES_PER_PIXEL = 4,
};

typedef long long libvlc_time_t;
typedef struct libvlc_instance_t libvlc_instance_t;
typedef struct libvlc_media_t libvlc_media_t;
typedef struct libvlc_media_player_t libvlc_media_player_t;
typedef struct libvlc_track_description_t {
    int i_id;
    char* psz_name;
    struct libvlc_track_description_t* p_next;
} libvlc_track_description_t;
typedef struct libvlc_media_stats_t {
    int i_read_bytes;
    float f_input_bitrate;
    int i_demux_read_bytes;
    float f_demux_bitrate;
    int i_demux_corrupted;
    int i_demux_discontinuity;
    int i_decoded_video;
    int i_decoded_audio;
    int i_displayed_pictures;
    int i_lost_pictures;
    int i_played_abuffers;
    int i_lost_abuffers;
    int i_sent_packets;
    int i_sent_bytes;
    float f_send_bitrate;
} libvlc_media_stats_t;

typedef struct {
    void* dylib;
    void* core_dylib;
    int dylib_process_retained;
    int core_dylib_process_retained;
    libvlc_instance_t* (*new_instance)(int, const char* const*);
    void (*release_instance)(libvlc_instance_t*);
    libvlc_media_t* (*media_new_location)(libvlc_instance_t*, const char*);
    libvlc_media_t* (*media_new_path)(libvlc_instance_t*, const char*);
    void (*media_add_option)(libvlc_media_t*, const char*);
    void (*media_release)(libvlc_media_t*);
    int (*media_get_stats)(libvlc_media_t*, libvlc_media_stats_t*);
    libvlc_media_player_t* (*media_player_new_from_media)(libvlc_media_t*);
    libvlc_media_t* (*media_player_get_media)(libvlc_media_player_t*);
    void (*media_player_set_media)(libvlc_media_player_t*, libvlc_media_t*);
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
    void (*media_player_set_nsobject)(libvlc_media_player_t*, void*);
    void (*video_set_callbacks)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*);
    void (*video_set_format_callbacks)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*));
    int (*video_get_size)(libvlc_media_player_t*, unsigned, unsigned*, unsigned*);
    libvlc_track_description_t* (*audio_get_track_description)(libvlc_media_player_t*);
    int (*audio_set_track)(libvlc_media_player_t*, int);
    libvlc_track_description_t* (*video_get_spu_description)(libvlc_media_player_t*);
    int (*video_set_spu)(libvlc_media_player_t*, int);
    void (*track_description_list_release)(libvlc_track_description_t*);
} LibVlcApi;

typedef struct RetainedDylibHandle {
    void* handle;
    struct RetainedDylibHandle* next;
} RetainedDylibHandle;

/*
 * libVLC finishes some native worker/TLS teardown after the player and instance have returned
 * from their public release calls. Unmapping libvlc/libvlccore in that interval can leave those
 * threads returning through unloaded code, especially when a Compose session immediately creates
 * another backend. Keep exactly one dlopen reference for each module for the lifetime of the JVM;
 * later player instances still balance their own extra dlopen references normally.
 */
static pthread_mutex_t retained_dylib_mutex = PTHREAD_MUTEX_INITIALIZER;
static RetainedDylibHandle* retained_dylib_handles = NULL;

static int retain_first_dylib_reference_for_process(void* handle) {
    if (!handle) return 0;

    pthread_mutex_lock(&retained_dylib_mutex);
    for (RetainedDylibHandle* item = retained_dylib_handles; item; item = item->next) {
        if (item->handle == handle) {
            pthread_mutex_unlock(&retained_dylib_mutex);
            return 0;
        }
    }

    RetainedDylibHandle* retained = (RetainedDylibHandle*)calloc(1, sizeof(RetainedDylibHandle));
    if (!retained) {
        pthread_mutex_unlock(&retained_dylib_mutex);
        return 0;
    }
    retained->handle = handle;
    retained->next = retained_dylib_handles;
    retained_dylib_handles = retained;
    pthread_mutex_unlock(&retained_dylib_mutex);
    return 1;
}

static void release_libvlc_api_handles(LibVlcApi* api) {
    if (!api) return;
    if (api->dylib && !api->dylib_process_retained) dlclose(api->dylib);
    if (api->core_dylib && !api->core_dylib_process_retained) dlclose(api->core_dylib);
    api->dylib = NULL;
    api->core_dylib = NULL;
}

typedef struct {
    LibVlcApi api;
    libvlc_instance_t* instance;
    libvlc_media_player_t* player;
    pthread_mutex_t frame_mutex;
    uint8_t* frame;
    uint8_t* read_frame;
    size_t frame_size;
    unsigned width;
    unsigned height;
    unsigned pitch;
    unsigned requested_width;
    unsigned requested_height;
    int frame_ready;
    uint64_t decoded_frames;
    uint64_t displayed_frames;
    int pending_audio_ordinal;
    int pending_spu_ordinal;
    int native_video;
    void* native_view;
} LibVlcPlayer;

static void* vlc_sym(void* dylib, const char* name) {
    return dlsym(dylib, name);
}

static void* dlopen_libvlccore_next_to_libvlc(const char* libvlc_path) {
    if (!libvlc_path) return NULL;
    const char* slash = strrchr(libvlc_path, '/');
    if (!slash) return NULL;

    char core_path[PATH_MAX];
    int directory_length = (int)(slash - libvlc_path);
    int written = snprintf(core_path, sizeof(core_path), "%.*s/libvlccore.dylib", directory_length, libvlc_path);
    if (written > 0 && written < (int)sizeof(core_path)) {
        void* core = dlopen(core_path, RTLD_NOW | RTLD_GLOBAL);
        if (core) return core;
    }

    written = snprintf(core_path, sizeof(core_path), "%.*s/libvlccore.9.dylib", directory_length, libvlc_path);
    if (written > 0 && written < (int)sizeof(core_path)) {
        return dlopen(core_path, RTLD_NOW | RTLD_GLOBAL);
    }

    return NULL;
}

static int load_libvlc_api(const char* libvlc_path, LibVlcApi* api) {
    memset(api, 0, sizeof(*api));
    api->core_dylib = dlopen_libvlccore_next_to_libvlc(libvlc_path);
    void* dylib = dlopen(libvlc_path, RTLD_NOW | RTLD_LOCAL);
    if (!dylib) {
        native_logf("Failed to dlopen libVLC: %s\n", dlerror());
        if (api->core_dylib) dlclose(api->core_dylib);
        return 0;
    }

    api->dylib = dylib;
    api->new_instance = (libvlc_instance_t* (*)(int, const char* const*))vlc_sym(dylib, "libvlc_new");
    api->release_instance = (void (*)(libvlc_instance_t*))vlc_sym(dylib, "libvlc_release");
    api->media_new_location = (libvlc_media_t* (*)(libvlc_instance_t*, const char*))vlc_sym(dylib, "libvlc_media_new_location");
    api->media_new_path = (libvlc_media_t* (*)(libvlc_instance_t*, const char*))vlc_sym(dylib, "libvlc_media_new_path");
    api->media_add_option = (void (*)(libvlc_media_t*, const char*))vlc_sym(dylib, "libvlc_media_add_option");
    api->media_release = (void (*)(libvlc_media_t*))vlc_sym(dylib, "libvlc_media_release");
    api->media_get_stats = (int (*)(libvlc_media_t*, libvlc_media_stats_t*))vlc_sym(dylib, "libvlc_media_get_stats");
    api->media_player_new_from_media = (libvlc_media_player_t* (*)(libvlc_media_t*))vlc_sym(dylib, "libvlc_media_player_new_from_media");
    api->media_player_get_media = (libvlc_media_t* (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_media");
    api->media_player_set_media = (void (*)(libvlc_media_player_t*, libvlc_media_t*))vlc_sym(dylib, "libvlc_media_player_set_media");
    api->media_player_release = (void (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_release");
    api->media_player_play = (int (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_play");
    api->media_player_pause = (void (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_pause");
    api->media_player_stop = (void (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_stop");
    api->media_player_get_time = (libvlc_time_t (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_time");
    api->media_player_set_time = (void (*)(libvlc_media_player_t*, libvlc_time_t))vlc_sym(dylib, "libvlc_media_player_set_time");
    api->media_player_get_length = (libvlc_time_t (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_length");
    api->audio_set_volume = (int (*)(libvlc_media_player_t*, int))vlc_sym(dylib, "libvlc_audio_set_volume");
    api->audio_get_volume = (int (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_audio_get_volume");
    api->media_player_set_rate = (int (*)(libvlc_media_player_t*, float))vlc_sym(dylib, "libvlc_media_player_set_rate");
    api->media_player_get_rate = (float (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_media_player_get_rate");
    api->media_player_set_nsobject = (void (*)(libvlc_media_player_t*, void*))vlc_sym(dylib, "libvlc_media_player_set_nsobject");
    api->video_set_callbacks = (void (*)(libvlc_media_player_t*, void* (*)(void*, void**), void (*)(void*, void*, void* const*), void (*)(void*, void*), void*))vlc_sym(dylib, "libvlc_video_set_callbacks");
    api->video_set_format_callbacks = (void (*)(libvlc_media_player_t*, unsigned (*)(void**, char*, unsigned*, unsigned*, unsigned*, unsigned*), void (*)(void*)))vlc_sym(dylib, "libvlc_video_set_format_callbacks");
    api->video_get_size = (int (*)(libvlc_media_player_t*, unsigned, unsigned*, unsigned*))vlc_sym(dylib, "libvlc_video_get_size");
    api->audio_get_track_description = (libvlc_track_description_t* (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_audio_get_track_description");
    api->audio_set_track = (int (*)(libvlc_media_player_t*, int))vlc_sym(dylib, "libvlc_audio_set_track");
    api->video_get_spu_description = (libvlc_track_description_t* (*)(libvlc_media_player_t*))vlc_sym(dylib, "libvlc_video_get_spu_description");
    api->video_set_spu = (int (*)(libvlc_media_player_t*, int))vlc_sym(dylib, "libvlc_video_set_spu");
    api->track_description_list_release = (void (*)(libvlc_track_description_t*))vlc_sym(dylib, "libvlc_track_description_list_release");

    if (!api->new_instance || !api->release_instance || !api->media_new_location ||
        !api->media_new_path || !api->media_add_option || !api->media_release || !api->media_player_new_from_media ||
        !api->media_player_set_media ||
        !api->media_player_release || !api->media_player_play || !api->media_player_pause ||
        !api->media_player_stop || !api->media_player_get_time || !api->media_player_set_time ||
        !api->media_player_get_length || !api->audio_set_volume || !api->audio_get_volume ||
        !api->media_player_set_rate || !api->media_player_get_rate || !api->media_player_set_nsobject ||
        !api->video_set_callbacks ||
        !api->video_set_format_callbacks || !api->audio_get_track_description ||
        !api->audio_set_track || !api->video_get_spu_description || !api->video_set_spu ||
        !api->track_description_list_release) {
        native_logf("libVLC is missing required API symbols\n");
        dlclose(dylib);
        if (api->core_dylib) dlclose(api->core_dylib);
        memset(api, 0, sizeof(*api));
        return 0;
    }

    api->dylib_process_retained = retain_first_dylib_reference_for_process(api->dylib);
    api->core_dylib_process_retained = retain_first_dylib_reference_for_process(api->core_dylib);

    return 1;
}

static void vlc_memory_output_size(
    LibVlcPlayer* player,
    unsigned input_width,
    unsigned input_height,
    unsigned* output_width,
    unsigned* output_height
) {
    unsigned requested_width = 0;
    unsigned requested_height = 0;
    pthread_mutex_lock(&player->frame_mutex);
    requested_width = player->requested_width;
    requested_height = player->requested_height;
    pthread_mutex_unlock(&player->frame_mutex);

    double scale = 1.0;
    if (requested_width > 0 && requested_height > 0) {
        scale = fmin(
            (double)requested_width / (double)input_width,
            (double)requested_height / (double)input_height
        );
    }
    scale = fmin(scale, (double)LIBVLC_MEMORY_MAX_DIMENSION / (double)input_width);
    scale = fmin(scale, (double)LIBVLC_MEMORY_MAX_DIMENSION / (double)input_height);
    scale = fmin(fmax(scale, 0.0), 1.0);

    unsigned scaled_width = (unsigned)floor((double)input_width * scale);
    unsigned scaled_height = (unsigned)floor((double)input_height * scale);
    *output_width = scaled_width < 2u ? 2u : scaled_width & ~1u;
    *output_height = scaled_height < 2u ? 2u : scaled_height & ~1u;
}

static unsigned vlc_format_cb(void** opaque, char* chroma, unsigned* width, unsigned* height, unsigned* pitches, unsigned* lines) {
    LibVlcPlayer* player = (LibVlcPlayer*)(*opaque);
    if (!player || !width || !height || *width == 0 || *height == 0) return 0;

    unsigned frame_width = 0;
    unsigned frame_height = 0;
    vlc_memory_output_size(player, *width, *height, &frame_width, &frame_height);
    if (frame_width == 0 || frame_height == 0) return 0;
    if (frame_width > (UINT_MAX - 31u) / BGRA_BYTES_PER_PIXEL) return 0;

    // Let libVLC's optimized converter both scale and produce native-endian BGRA. The previous
    // full-resolution I420 + scalar C conversion allocated three 8K frame copies and could not
    // sustain interactive projection. This output is bounded to the current viewport below.
    memcpy(chroma, "RV32", 4);
    unsigned bgra_pitch = (frame_width * BGRA_BYTES_PER_PIXEL + 31u) & ~31u;
    if ((size_t)frame_height > SIZE_MAX / (size_t)bgra_pitch) return 0;
    size_t bgra_size = (size_t)bgra_pitch * (size_t)frame_height;

    pthread_mutex_lock(&player->frame_mutex);
    uint8_t* new_frame = (uint8_t*)malloc(bgra_size);
    uint8_t* new_read_frame = (uint8_t*)malloc(bgra_size);
    if (!new_frame || !new_read_frame) {
        free(new_frame);
        free(new_read_frame);
        pthread_mutex_unlock(&player->frame_mutex);
        return 0;
    }
    free(player->frame);
    free(player->read_frame);
    player->frame = new_frame;
    player->read_frame = new_read_frame;
    player->frame_size = bgra_size;
    player->width = frame_width;
    player->height = frame_height;
    player->pitch = bgra_pitch;
    player->frame_ready = 0;
    memset(player->frame, 0, bgra_size);
    memset(player->read_frame, 0, bgra_size);
    pthread_mutex_unlock(&player->frame_mutex);

    *width = frame_width;
    *height = frame_height;
    pitches[0] = bgra_pitch;
    lines[0] = frame_height;
    return 1;
}

static void vlc_format_cleanup_cb(void* opaque) {
    (void)opaque;
}

static void* vlc_lock_cb(void* opaque, void** planes) {
    LibVlcPlayer* player = (LibVlcPlayer*)opaque;
    if (!player || !planes) return NULL;
    pthread_mutex_lock(&player->frame_mutex);
    planes[0] = player->frame;
    return player;
}

static void vlc_unlock_cb(void* opaque, void* picture, void* const* planes) {
    (void)picture;
    (void)planes;
    LibVlcPlayer* player = (LibVlcPlayer*)opaque;
    if (player) {
        player->frame_ready = 1;
        player->decoded_frames++;
        pthread_mutex_unlock(&player->frame_mutex);
    }
}

static void vlc_display_cb(void* opaque, void* picture) {
    (void)picture;
    LibVlcPlayer* player = (LibVlcPlayer*)opaque;
    if (!player) return;
    pthread_mutex_lock(&player->frame_mutex);
    player->displayed_frames++;
    pthread_mutex_unlock(&player->frame_mutex);
}

static int vlc_select_description_ordinal(libvlc_track_description_t* descriptions, int ordinal) {
    int current = 0;
    for (libvlc_track_description_t* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;
        if (current == ordinal) return item->i_id;
        current++;
    }
    return -2;
}

static int vlc_apply_audio_ordinal(LibVlcPlayer* player, int ordinal) {
    if (!player || !player->player || ordinal < 0) return 0;
    libvlc_track_description_t* descriptions = player->api.audio_get_track_description(player->player);
    int id = vlc_select_description_ordinal(descriptions, ordinal);
    if (descriptions) player->api.track_description_list_release(descriptions);
    if (id == -2) return 0;
    return player->api.audio_set_track(player->player, id) == 0;
}

static int vlc_apply_spu_ordinal(LibVlcPlayer* player, int ordinal) {
    if (!player || !player->player) return 0;
    if (ordinal < 0) return player->api.video_set_spu(player->player, -1) == 0;
    libvlc_track_description_t* descriptions = player->api.video_get_spu_description(player->player);
    int id = vlc_select_description_ordinal(descriptions, ordinal);
    if (descriptions) player->api.track_description_list_release(descriptions);
    if (id == -2) return 0;
    return player->api.video_set_spu(player->player, id) == 0;
}

static void vlc_apply_pending_tracks(LibVlcPlayer* player) {
    if (!player || !player->player) return;
    if (player->pending_audio_ordinal >= 0) {
        vlc_apply_audio_ordinal(player, player->pending_audio_ordinal);
    }
    if (player->pending_spu_ordinal >= -1) {
        vlc_apply_spu_ordinal(player, player->pending_spu_ordinal);
    }
}

typedef struct {
    char* data;
    size_t length;
    size_t capacity;
} StringBuilder;

static int sb_reserve(StringBuilder* builder, size_t extra) {
    if (!builder) return 0;
    size_t needed = builder->length + extra + 1;
    if (needed <= builder->capacity) return 1;

    size_t next_capacity = builder->capacity > 0 ? builder->capacity : 256;
    while (next_capacity < needed) next_capacity *= 2;
    char* next_data = (char*)realloc(builder->data, next_capacity);
    if (!next_data) return 0;
    builder->data = next_data;
    builder->capacity = next_capacity;
    return 1;
}

static int sb_append_char(StringBuilder* builder, char value) {
    if (!sb_reserve(builder, 1)) return 0;
    builder->data[builder->length++] = value;
    builder->data[builder->length] = '\0';
    return 1;
}

static int sb_append_cstr(StringBuilder* builder, const char* value) {
    if (!value) return 1;
    size_t value_length = strlen(value);
    if (!sb_reserve(builder, value_length)) return 0;
    memcpy(builder->data + builder->length, value, value_length);
    builder->length += value_length;
    builder->data[builder->length] = '\0';
    return 1;
}

static int sb_append_sanitized_cstr(StringBuilder* builder, const char* value) {
    if (!value) return 1;
    for (const char* cursor = value; *cursor; cursor++) {
        char ch = (*cursor == '\n' || *cursor == '\r' || *cursor == '\t') ? ' ' : *cursor;
        if (!sb_append_char(builder, ch)) return 0;
    }
    return 1;
}

static jstring vlc_track_descriptions_to_jstring(
    JNIEnv* env,
    libvlc_track_description_t* descriptions
) {
    if (!env || !descriptions) return NULL;

    StringBuilder builder;
    memset(&builder, 0, sizeof(builder));
    int ordinal = 0;
    for (libvlc_track_description_t* item = descriptions; item; item = item->p_next) {
        if (item->i_id < 0) continue;

        char ordinal_buffer[32];
        snprintf(ordinal_buffer, sizeof(ordinal_buffer), "%d", ordinal);
        if (!sb_append_cstr(&builder, ordinal_buffer) ||
            !sb_append_char(&builder, '\t') ||
            !sb_append_sanitized_cstr(&builder, item->psz_name ? item->psz_name : "") ||
            !sb_append_char(&builder, '\n')) {
            free(builder.data);
            return NULL;
        }
        ordinal++;
    }

    if (!builder.data || builder.length == 0) {
        free(builder.data);
        return NULL;
    }

    jstring result = (*env)->NewStringUTF(env, builder.data);
    free(builder.data);
    return result;
}

static int has_uri_scheme(const char* uri) {
    if (!uri || !isalpha((unsigned char)uri[0])) return 0;
    for (const unsigned char* cursor = (const unsigned char*)uri + 1; *cursor; cursor++) {
        if (*cursor == ':') return 1;
        if (!isalnum(*cursor) && *cursor != '+' && *cursor != '-' && *cursor != '.') return 0;
    }
    return 0;
}

static LibVlcPlayer* create_libvlc_player(const char* libvlc_path, const char* plugin_path, int native_video) {
    if (!libvlc_path || !plugin_path) return NULL;

    LibVlcPlayer* player = (LibVlcPlayer*)calloc(1, sizeof(LibVlcPlayer));
    if (!player) return NULL;
    player->pending_audio_ordinal = -2;
    player->pending_spu_ordinal = -2;
    player->native_video = native_video != 0;
    pthread_mutex_init(&player->frame_mutex, NULL);

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
        // Prefer VLC's native VideoToolbox decoder. Keeping avcodec first made
        // every HEVC frame go through the software decoder before the vmem
        // scaler, which is catastrophic for 8K/60 projection sources.
        "--codec=videotoolbox,avcodec",
        // The memory callback still needs an RV32 conversion for Compose/Skia, but decoding an
        // 8K HEVC source in software can retain gigabytes of reference frames and saturate every
        // performance core. VideoToolbox keeps the expensive decode in the macOS hardware path;
        // libVLC's converter only downloads/scales the bounded presentation frame below.
        // VLC 3 exposes this option as the enum {any, none}; naming the macOS
        // decoder directly is rejected and silently leaves this vmem path on
        // the software decoder. On macOS, "any" resolves to VideoToolbox when
        // the codec and source support it.
        "--avcodec-hw=any",
        "--aout=auhal"
    };
    const char* native_args[] = {
        "--no-video-title-show",
        "--no-osd",
        "--quiet",
        "--aout=auhal"
    };
    const char* const* args = player->native_video ? native_args : memory_args;
    int arg_count = player->native_video
        ? (int)(sizeof(native_args) / sizeof(native_args[0]))
        : (int)(sizeof(memory_args) / sizeof(memory_args[0]));
    player->instance = player->api.new_instance(arg_count, args);
    if (!player->instance) {
        release_libvlc_api_handles(&player->api);
        pthread_mutex_destroy(&player->frame_mutex);
        free(player);
        return NULL;
    }

    return player;
}

static void dispose_libvlc_player(LibVlcPlayer* player) {
    if (!player) return;
    if (player->player) {
        if (player->native_video && player->api.media_player_set_nsobject) {
            player->api.media_player_set_nsobject(player->player, NULL);
        }
        player->api.media_player_stop(player->player);
        player->api.media_player_release(player->player);
        player->player = NULL;
    }
#ifdef __OBJC__
    KMPReleaseNativeViewContext view_context = {
        .view = (NSView*)player->native_view,
    };
    player->native_view = NULL;
    if (view_context.view) {
        run_on_appkit_main_sync(release_native_view_on_appkit_main, &view_context);
    }
#else
    player->native_view = NULL;
#endif
    if (player->instance) {
        player->api.release_instance(player->instance);
        player->instance = NULL;
    }
    pthread_mutex_lock(&player->frame_mutex);
    free(player->frame);
    player->frame = NULL;
    free(player->read_frame);
    player->read_frame = NULL;
    player->frame_size = 0;
    player->frame_ready = 0;
    pthread_mutex_unlock(&player->frame_mutex);
    pthread_mutex_destroy(&player->frame_mutex);
    release_libvlc_api_handles(&player->api);
    free(player);
}

static void libvlc_add_header_options(libvlc_media_t* media, LibVlcPlayer* player, const char* request_headers) {
    if (!media || !player || !request_headers || !request_headers[0]) return;

    char* copy = strdup(request_headers);
    if (!copy) return;

    char* save = NULL;
    for (char* line = strtok_r(copy, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        while (*line == ' ' || *line == '\t' || *line == '\r') line++;
        char* end = line + strlen(line);
        while (end > line && (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\r')) {
            *--end = '\0';
        }
        if (!line[0]) continue;

        char* separator = strchr(line, ':');
        if (!separator) continue;
        *separator = '\0';

        char* name = line;
        char* value = separator + 1;
        while (*value == ' ' || *value == '\t') value++;
        if (!name[0] || !value[0]) continue;

        const char* option_name = NULL;
        if (strcasecmp(name, "User-Agent") == 0) {
            option_name = ":http-user-agent=";
        } else if (strcasecmp(name, "Referer") == 0 || strcasecmp(name, "Referrer") == 0) {
            option_name = ":http-referrer=";
        } else if (strcasecmp(name, "Cookie") == 0) {
            option_name = ":http-cookie=";
        }

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

static void libvlc_open_uri_with_headers(LibVlcPlayer* player, const char* uri, const char* request_headers) {
    if (!player || !player->instance || !uri) return;
    int reuse_media_player = player->player != NULL && player->api.media_player_set_media != NULL;
    if (player->player) {
        player->api.media_player_stop(player->player);
        if (!reuse_media_player) {
            player->api.media_player_release(player->player);
            player->player = NULL;
        }
    }
    pthread_mutex_lock(&player->frame_mutex);
    player->frame_ready = 0;
    player->width = 0;
    player->height = 0;
    player->pitch = 0;
    player->decoded_frames = 0;
    player->displayed_frames = 0;
    pthread_mutex_unlock(&player->frame_mutex);

    libvlc_media_t* media = has_uri_scheme(uri)
        ? player->api.media_new_location(player->instance, uri)
        : player->api.media_new_path(player->instance, uri);
    if (!media) return;

    libvlc_add_header_options(media, player, request_headers);

    if (reuse_media_player) {
        // Keep one media player bound to the AppKit drawable. Releasing it and attaching a newly
        // created player from this background JNI call races AppKit and can leave the retained
        // NSView permanently black after opening a second source.
        player->api.media_player_set_media(player->player, media);
    } else {
        player->player = player->api.media_player_new_from_media(media);
    }
    player->api.media_release(media);
    if (!player->player) return;

    if (player->native_video) {
        if (!reuse_media_player && player->native_view) {
            player->api.media_player_set_nsobject(player->player, player->native_view);
        }
        vlc_apply_pending_tracks(player);
        return;
    }

    player->api.video_set_callbacks(player->player, vlc_lock_cb, vlc_unlock_cb, vlc_display_cb, player);
    player->api.video_set_format_callbacks(player->player, vlc_format_cb, vlc_format_cleanup_cb);
    player->api.media_player_play(player->player);
    vlc_apply_pending_tracks(player);
}

static void libvlc_open_uri(LibVlcPlayer* player, const char* uri) {
    libvlc_open_uri_with_headers(player, uri, NULL);
}

static inline LibVlcPlayer* vlcCtx(NativePlayerHandle* handle) {
    return (handle && handle->kind == PLAYER_KIND_LIBVLC) ? (LibVlcPlayer*)handle->ctx : NULL;
}

// ---------------------------------------------------------------------------
// JNI implementations
// ---------------------------------------------------------------------------

static jlong JNICALL jni_CreatePlayer(JNIEnv* env, jclass cls) {
    void* ctx = createVideoPlayer();
    if (!ctx) return 0L;
    NativePlayerHandle* handle = (NativePlayerHandle*)calloc(1, sizeof(NativePlayerHandle));
    if (!handle) {
        disposeVideoPlayer(ctx);
        return 0L;
    }
    handle->kind = PLAYER_KIND_AV;
    handle->ctx = ctx;
    return (jlong)(uintptr_t)handle;
}

static jlong JNICALL jni_CreateLibVlcPlayer(
    JNIEnv* env,
    jclass cls,
    jstring libPath,
    jstring pluginPath,
    jboolean nativeVideoOutput
) {
    if (!libPath || !pluginPath) return 0L;
    const char* cLibPath = (*env)->GetStringUTFChars(env, libPath, NULL);
    if (!cLibPath) return 0L;
    const char* cPluginPath = (*env)->GetStringUTFChars(env, pluginPath, NULL);
    if (!cPluginPath) {
        (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
        return 0L;
    }

    LibVlcPlayer* player = create_libvlc_player(cLibPath, cPluginPath, nativeVideoOutput ? 1 : 0);
    (*env)->ReleaseStringUTFChars(env, libPath, cLibPath);
    (*env)->ReleaseStringUTFChars(env, pluginPath, cPluginPath);
    if (!player) return 0L;

    NativePlayerHandle* handle = (NativePlayerHandle*)calloc(1, sizeof(NativePlayerHandle));
    if (!handle) {
        dispose_libvlc_player(player);
        return 0L;
    }
    handle->kind = PLAYER_KIND_LIBVLC;
    handle->ctx = player;
    return (jlong)(uintptr_t)handle;
}

static void JNICALL jni_OpenUri(JNIEnv* env, jclass cls, jlong handle, jstring uri) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;
    NativePlayerHandle* native = toHandle(handle);
    if (native && native->kind == PLAYER_KIND_LIBVLC) {
        libvlc_open_uri((LibVlcPlayer*)native->ctx, cUri);
    } else {
        void* ctx = avCtx(native);
        if (ctx) openUri(ctx, cUri);
    }
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static void JNICALL jni_OpenUriWithHeaders(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jstring uri,
    jstring requestHeadersJson
) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;
    const char* cHeaders = requestHeadersJson
        ? (*env)->GetStringUTFChars(env, requestHeadersJson, NULL)
        : NULL;
    NativePlayerHandle* native = toHandle(handle);
    if (native && native->kind == PLAYER_KIND_LIBVLC) {
        libvlc_open_uri((LibVlcPlayer*)native->ctx, cUri);
    } else {
        void* ctx = avCtx(native);
        if (ctx) openUriWithHeaders(ctx, cUri, cHeaders ? cHeaders : "{}");
    }
    if (cHeaders) {
        (*env)->ReleaseStringUTFChars(env, requestHeadersJson, cHeaders);
    }
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static void JNICALL jni_OpenUriWithHeaderLines(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jstring uri,
    jstring requestHeaders
) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;
    const char* cHeaders = requestHeaders
        ? (*env)->GetStringUTFChars(env, requestHeaders, NULL)
        : NULL;
    NativePlayerHandle* native = toHandle(handle);
    if (native && native->kind == PLAYER_KIND_LIBVLC) {
        libvlc_open_uri_with_headers((LibVlcPlayer*)native->ctx, cUri, cHeaders);
    } else {
        void* ctx = avCtx(native);
        if (ctx) openUri(ctx, cUri);
    }
    if (cHeaders) {
        (*env)->ReleaseStringUTFChars(env, requestHeaders, cHeaders);
    }
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static jlong JNICALL jni_PrepareUriReplacement(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jstring uri,
    jstring requestHeadersJson
) {
    (void)cls;
    if (!handle || !uri) return 0L;
    NativePlayerHandle* native = toHandle(handle);
    void* ctx = avCtx(native);
    if (!ctx) return 0L;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return 0L;
    const char* cHeaders = requestHeadersJson
        ? (*env)->GetStringUTFChars(env, requestHeadersJson, NULL)
        : NULL;
    uint64_t token = prepareUriReplacement(ctx, cUri, cHeaders ? cHeaders : "{}");
    if (cHeaders) {
        (*env)->ReleaseStringUTFChars(env, requestHeadersJson, cHeaders);
    }
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
    return (jlong)token;
}

static jint JNICALL jni_GetUriReplacementStatus(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong token
) {
    (void)env;
    (void)cls;
    void* ctx = avCtx(toHandle(handle));
    return ctx ? (jint)getUriReplacementStatus(ctx, (uint64_t)token) : (jint)-2;
}

static jstring JNICALL jni_GetUriReplacementError(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong token
) {
    (void)cls;
    void* ctx = avCtx(toHandle(handle));
    if (!ctx) return NULL;
    const char* message = getUriReplacementError(ctx, (uint64_t)token);
    if (!message) return NULL;
    jstring result = (*env)->NewStringUTF(env, message);
    free((void*)message);
    return result;
}

static jboolean JNICALL jni_CommitUriReplacement(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong token
) {
    (void)env;
    (void)cls;
    void* ctx = avCtx(toHandle(handle));
    return ctx && commitUriReplacement(ctx, (uint64_t)token) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_CancelUriReplacement(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong token
) {
    (void)env;
    (void)cls;
    void* ctx = avCtx(toHandle(handle));
    if (ctx) cancelUriReplacement(ctx, (uint64_t)token);
}

static void JNICALL jni_Play(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_play(vlc->player);
        vlc_apply_pending_tracks(vlc);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) playVideo(ctx);
}

static void JNICALL jni_Pause(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_pause(vlc->player);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) pauseVideo(ctx);
}

/*
 * A Tao native child view can outlive the backend that produced it by one Compose disposal
 * pass. Do not let the retired decoder and its Cocoa/Metal vout keep running during that gap:
 * an 8K surface consumes enough unified memory for even a short overlap to be destructive.
 * The handle and NSView are intentionally retained until nDisposeNativeVideoView releases the
 * last owner, so this operation must only stop work and detach the drawable.
 */
static void JNICALL jni_RetirePlayer(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        if (vlc->native_video && vlc->api.media_player_set_nsobject) {
            vlc->api.media_player_set_nsobject(vlc->player, NULL);
        }
        vlc->api.media_player_stop(vlc->player);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) pauseVideo(ctx);
}

static jboolean JNICALL jni_IsReadyForPlayback(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) return (jboolean)(vlc->player != NULL);
    void* ctx = avCtx(native);
    return ctx ? (jboolean)(isReadyForPlayback(ctx) != 0) : JNI_FALSE;
}

static void JNICALL jni_SetVolume(JNIEnv* env, jclass cls, jlong handle, jfloat volume) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        int scaled = (int)((float)volume * 100.0f);
        if (scaled < 0) scaled = 0;
        if (scaled > 100) scaled = 100;
        vlc->api.audio_set_volume(vlc->player, scaled);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) setVolume(ctx, (float)volume);
}

static jfloat JNICALL jni_GetVolume(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        return (jfloat)((float)vlc->api.audio_get_volume(vlc->player) / 100.0f);
    }
    void* ctx = avCtx(native);
    return ctx ? getVolume(ctx) : 0.0f;
}

// Locks the latest CVPixelBuffer and fills outInfo[3] = {width, height, bytesPerRow}.
// Returns the base address of the locked buffer, or 0 on failure.
// Caller MUST call jni_UnlockFrame after reading.
static jlong JNICALL jni_LockFrame(JNIEnv* env, jclass cls, jlong handle, jintArray outInfo) {
    if (!handle || !outInfo) return 0L;
    int32_t info[3] = {0, 0, 0};
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    void* addr = NULL;
    if (vlc) {
        pthread_mutex_lock(&vlc->frame_mutex);
        if (vlc->frame_ready &&
            vlc->frame &&
            vlc->read_frame &&
            vlc->width > 0 &&
            vlc->height > 0 &&
            vlc->pitch > 0 &&
            vlc->frame_size >= (size_t)vlc->pitch * (size_t)vlc->height) {
            memcpy(vlc->read_frame, vlc->frame, (size_t)vlc->pitch * (size_t)vlc->height);
            info[0] = (int32_t)vlc->width;
            info[1] = (int32_t)vlc->height;
            info[2] = (int32_t)vlc->pitch;
            addr = vlc->read_frame;
        }
        pthread_mutex_unlock(&vlc->frame_mutex);
    } else {
        void* ctx = avCtx(native);
        if (ctx) addr = lockLatestFrame(ctx, info);
    }
    if (!addr) return 0L;
    (*env)->SetIntArrayRegion(env, outInfo, 0, 3, (jint*)info);
    return (jlong)(uintptr_t)addr;
}

static void JNICALL jni_UnlockFrame(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) {
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) unlockLatestFrame(ctx);
}

static jobject JNICALL jni_WrapPointer(JNIEnv* env, jclass cls, jlong address, jlong size) {
    if (!address || size <= 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)(uintptr_t)(uint64_t)address, (jlong)size);
}

static jint JNICALL jni_GetFrameWidth(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) {
        unsigned width = vlc->width;
        unsigned height = vlc->height;
        if (vlc->native_video && vlc->player && vlc->api.video_get_size) {
            unsigned queried_width = 0;
            unsigned queried_height = 0;
            if (vlc->api.video_get_size(vlc->player, 0, &queried_width, &queried_height) == 0) {
                width = queried_width;
                height = queried_height;
                pthread_mutex_lock(&vlc->frame_mutex);
                vlc->width = width;
                vlc->height = height;
                pthread_mutex_unlock(&vlc->frame_mutex);
            }
        }
        return (jint)width;
    }
    void* ctx = avCtx(native);
    return ctx ? (jint)getFrameWidth(ctx) : 0;
}

static jint JNICALL jni_GetFrameHeight(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) {
        unsigned width = vlc->width;
        unsigned height = vlc->height;
        if (vlc->native_video && vlc->player && vlc->api.video_get_size) {
            unsigned queried_width = 0;
            unsigned queried_height = 0;
            if (vlc->api.video_get_size(vlc->player, 0, &queried_width, &queried_height) == 0) {
                width = queried_width;
                height = queried_height;
                pthread_mutex_lock(&vlc->frame_mutex);
                vlc->width = width;
                vlc->height = height;
                pthread_mutex_unlock(&vlc->frame_mutex);
            }
        }
        return (jint)height;
    }
    void* ctx = avCtx(native);
    return ctx ? (jint)getFrameHeight(ctx) : 0;
}

static jdouble JNICALL jni_GetDisplayAspectRatio(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0.0;
    void* ctx = avCtx(native);
    return ctx ? (jdouble)getDisplayAspectRatio(ctx) : 0.0;
}

static jint JNICALL jni_SetOutputSize(JNIEnv* env, jclass cls, jlong handle, jint width, jint height) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) {
        if (vlc->native_video || width <= 0 || height <= 0) return 0;
        pthread_mutex_lock(&vlc->frame_mutex);
        vlc->requested_width = (unsigned)width;
        vlc->requested_height = (unsigned)height;
        pthread_mutex_unlock(&vlc->frame_mutex);
        return 1;
    }
    void* ctx = avCtx(native);
    return ctx ? (jint)setOutputSize(ctx, (int32_t)width, (int32_t)height) : 0;
}

static jboolean JNICALL jni_SetHdrMetalTextureOutput(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong command_queue
) {
    (void)env;
    (void)cls;
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return JNI_FALSE;
    void* ctx = avCtx(native);
    if (!ctx) return JNI_FALSE;
    return setHdrMetalTextureOutput(
        ctx,
        (void*)(uintptr_t)(uint64_t)command_queue
    ) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_SetHdrMetalTextureViewportSize(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jint width,
    jint height
) {
    (void)env;
    (void)cls;
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return;
    void* ctx = avCtx(native);
    if (ctx) setHdrMetalTextureViewportSize(ctx, (int32_t)width, (int32_t)height);
}

static jboolean JNICALL jni_GetHdrMetalTextureOutputInfo(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlongArray out_info
) {
    (void)cls;
    if (!out_info || (*env)->GetArrayLength(env, out_info) < 4) return JNI_FALSE;
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return JNI_FALSE;
    void* ctx = avCtx(native);
    if (!ctx) return JNI_FALSE;
    int64_t values[4] = { 0, 0, 0, 0 };
    if (!getHdrMetalTextureOutputInfo(ctx, values)) return JNI_FALSE;
    jlong result[4] = {
        (jlong)values[0],
        (jlong)values[1],
        (jlong)values[2],
        (jlong)values[3],
    };
    (*env)->SetLongArrayRegion(env, out_info, 0, 4, result);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

static void JNICALL jni_SetHdrMetalPreferred(JNIEnv* env, jclass cls, jlong handle, jboolean preferred) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return;
    void* ctx = avCtx(native);
    if (ctx) setHdrMetalPreferred(ctx, preferred ? 1 : 0);
}

static void JNICALL jni_SetHdrToneMappingEnabled(JNIEnv* env, jclass cls, jlong handle, jboolean enabled) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return;
    void* ctx = avCtx(native);
    if (ctx) setHdrToneMappingEnabled(ctx, enabled ? 1 : 0);
}

static jboolean JNICALL jni_SetHdrMetalProjectionConfiguration(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jstring configuration
) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native) || !configuration) return JNI_FALSE;
    void* ctx = avCtx(native);
    if (!ctx) return JNI_FALSE;
    const char* value = (*env)->GetStringUTFChars(env, configuration, NULL);
    if (!value) return JNI_FALSE;
    int32_t configured = setHdrMetalProjectionConfiguration(ctx, value);
    (*env)->ReleaseStringUTFChars(env, configuration, value);
    return configured ? JNI_TRUE : JNI_FALSE;
}

static jstring JNICALL jni_GetHdrRendererFailure(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return NULL;
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* detail = getHdrRendererFailure(ctx);
    if (!detail) return NULL;
    jstring result = (*env)->NewStringUTF(env, detail);
    free((void*)detail);
    return result;
}

#ifdef __OBJC__
typedef struct {
    NSView* view;
    BOOL available;
    char value[384];
} KMPDisplayColorCapabilitiesContext;

static void collect_display_color_capabilities_on_appkit_main(void* raw_context) {
    KMPDisplayColorCapabilitiesContext* context =
        (KMPDisplayColorCapabilitiesContext*)raw_context;
    if (!context || !context->view) return;

    @autoreleasepool {
        NSScreen* screen = [[context->view window] screen];
        if (screen) {
            double potential_edr = [screen maximumPotentialExtendedDynamicRangeColorComponentValue];
            double current_edr = [screen maximumExtendedDynamicRangeColorComponentValue];
            BOOL eligible = [AVPlayer eligibleForHDRPlayback];
            // The TextureView path is a controlled CAMetalLayer/Skia EDR renderer, not an
            // AVPlayerLayer. AVPlayer's eligibility flag describes system-player presentation
            // and must not veto a screen that explicitly reports EDR headroom to our renderer.
            int native_hdr = potential_edr > 1.0 ? 1 : 0;
            int dolby_vision_decode =
                VTIsHardwareDecodeSupported(kCMVideoCodecType_DolbyVisionHEVC) ? 1 : 0;
            NSNumber* screen_number = [[screen deviceDescription] objectForKey:@"NSScreenNumber"];
            snprintf(
                context->value,
                sizeof(context->value),
                "known=1;native=%d;eligible=%d;potentialEdr=%.6f;currentEdr=%.6f;screenId=%u;hdr10=%s;hlg=%s;dolbyVision=%s;dolbyVisionHardwareDecode=%d",
                native_hdr,
                eligible ? 1 : 0,
                potential_edr,
                current_edr,
                screen_number ? [screen_number unsignedIntValue] : 0u,
                native_hdr ? "SUPPORTED" : "UNSUPPORTED",
                native_hdr ? "SUPPORTED" : "UNSUPPORTED",
                native_hdr && dolby_vision_decode ? "SUPPORTED" : "UNSUPPORTED",
                dolby_vision_decode
            );
            context->available = YES;
        }
        [context->view release];
        context->view = nil;
    }
}

static jstring new_display_color_capabilities(JNIEnv* env, NSView* view) {
    if (!view) return NULL;
    KMPDisplayColorCapabilitiesContext context = {
        .view = [view retain],
        .available = NO,
        .value = {0},
    };
    run_on_appkit_main_sync(collect_display_color_capabilities_on_appkit_main, &context);
    return context.available ? (*env)->NewStringUTF(env, context.value) : NULL;
}
#endif

static jstring JNICALL jni_GetDisplayColorCapabilities(JNIEnv* env, jclass cls, jlong handle) {
#ifdef __OBJC__
    NativePlayerHandle* native = toHandle(handle);
    if (!native || native->kind != PLAYER_KIND_AV) return NULL;
    return new_display_color_capabilities(env, (NSView*)native->native_view);
#else
    (void)env;
    (void)cls;
    (void)handle;
    return NULL;
#endif
}

static jstring JNICALL jni_GetDisplayColorCapabilitiesForView(
    JNIEnv* env,
    jclass cls,
    jlong native_view
) {
#ifdef __OBJC__
    (void)cls;
    return new_display_color_capabilities(env, (NSView*)(uintptr_t)(uint64_t)native_view);
#else
    (void)env;
    (void)cls;
    (void)native_view;
    return NULL;
#endif
}

#ifdef __OBJC__
typedef struct {
    NativePlayerHandle* native;
    NSView* result;
} KMPCreateNativeVideoViewContext;

static void create_native_video_view_on_appkit_main(void* raw_context) {
    KMPCreateNativeVideoViewContext* context = (KMPCreateNativeVideoViewContext*)raw_context;
    @autoreleasepool {
        if (!context || !context->native) return;
        NativePlayerHandle* native = context->native;
        if (native->kind == PLAYER_KIND_AV) {
            void* hdr_context = avCtx(native);
            if (!hdr_context || !isHdrMetalAvailable(hdr_context)) return;
            if (native->native_view) {
                context->result = (NSView*)native->native_view;
                return;
            }
            CALayer* layer = (CALayer*)getHdrMetalLayer(hdr_context);
            if (!layer) return;
            KMPNativeVideoView* view =
                [[KMPNativeVideoView alloc] initWithFrame:NSMakeRect(0.0, 0.0, 1.0, 1.0)];
            [view setWantsLayer:YES];
            [view setKmpHostedLayer:layer];
            [view setKmpHdrContext:hdr_context];
            native->native_view = view;
            context->result = view;
            return;
        }

        LibVlcPlayer* vlc = vlcCtx(native);
        if (!vlc || !vlc->native_video) return;
        if (!vlc->native_view) {
            KMPNativeVideoView* view =
                [[KMPNativeVideoView alloc] initWithFrame:NSMakeRect(0.0, 0.0, 1.0, 1.0)];
            [view setWantsLayer:YES];
            [[view layer] setBackgroundColor:[[NSColor blackColor] CGColor]];
            vlc->native_view = view;
        }
        // Nucleus calls the factory before it reparents the returned NSView into the Tao window.
        // Binding libVLC to that still-detached view is timing-dependent: Cocoa vout may start
        // without a drawable and keep presenting black even though the playback clock advances.
        // MacVideoPlayerState calls this entry point again from NativeView's post-attach effect;
        // only that invocation is allowed to bind the drawable.
        NSView* native_view = (NSView*)vlc->native_view;
        if (vlc->player && vlc->api.media_player_set_nsobject && [native_view window] != nil) {
            vlc->api.media_player_set_nsobject(vlc->player, native_view);
        }
        context->result = native_view;
    }
}

typedef struct {
    NativePlayerHandle* native;
    NSView* view;
} KMPDisposeNativeVideoViewContext;

static void dispose_native_video_view_on_appkit_main(void* raw_context) {
    KMPDisposeNativeVideoViewContext* context = (KMPDisposeNativeVideoViewContext*)raw_context;
    @autoreleasepool {
        if (!context || !context->native || !context->view) return;
        NativePlayerHandle* native = context->native;
        if (native->kind == PLAYER_KIND_AV && native->native_view == context->view) {
            KMPNativeVideoView* view = (KMPNativeVideoView*)context->view;
            [view setKmpHdrContext:NULL];
            [view setKmpHostedLayer:nil];
            [view removeFromSuperview];
            [view setLayer:nil];
            [view release];
            native->native_view = NULL;
            detachHdrMetalLayer(avCtx(native));
            return;
        }
        LibVlcPlayer* vlc = vlcCtx(native);
        if (vlc && vlc->native_view == context->view) {
            if (vlc->player && vlc->api.media_player_set_nsobject) {
                vlc->api.media_player_set_nsobject(vlc->player, NULL);
            }
            [context->view removeFromSuperview];
            [context->view setLayer:nil];
            [context->view release];
            vlc->native_view = NULL;
        }
    }
}
#endif

static jlong JNICALL jni_CreateNativeVideoView(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
#ifdef __OBJC__
    KMPCreateNativeVideoViewContext context = {
        .native = toHandle(handle),
        .result = nil,
    };
    run_on_appkit_main_sync(create_native_video_view_on_appkit_main, &context);
    return (jlong)(uintptr_t)context.result;
#else
    return 0L;
#endif
}

static void JNICALL jni_DisposeNativeVideoView(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jlong native_view
) {
    (void)env;
    (void)cls;
#ifdef __OBJC__
    KMPDisposeNativeVideoViewContext context = {
        .native = toHandle(handle),
        .view = (NSView*)(uintptr_t)native_view,
    };
    run_on_appkit_main_sync(dispose_native_video_view_on_appkit_main, &context);
#else
    (void)handle;
    (void)native_view;
#endif
}

#ifdef __OBJC__
static char kKMPWindowedFrameKey;
static char kKMPWindowStyleMaskKey;
static char kKMPWindowLevelKey;
static char kKMPWindowMovableKey;
static char kKMPWindowMovableByBackgroundKey;
static char kKMPWindowShadowKey;
static char kKMPWindowTitleVisibilityKey;
static char kKMPWindowTitlebarTransparentKey;
static char kKMPWindowButtonVisibilityKey;
static char kKMPApplicationPresentationOptionsKey;

typedef struct {
    NSView* view;
    BOOL fullscreen;
    BOOL result;
} KMPNativeWindowFullscreenContext;

static void set_native_window_fullscreen_on_appkit_main(void* raw_context) {
    KMPNativeWindowFullscreenContext* context =
        (KMPNativeWindowFullscreenContext*)raw_context;
    if (!context || !context->view) return;

    @autoreleasepool {
        NSWindow* window = [context->view window];
        if (!window) return;

        NSValue* stored_frame = objc_getAssociatedObject(window, &kKMPWindowedFrameKey);
        if (context->fullscreen) {
            if (!stored_frame) {
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowedFrameKey,
                    [NSValue valueWithRect:[window frame]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowStyleMaskKey,
                    [NSNumber numberWithUnsignedLongLong:(unsigned long long)[window styleMask]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowLevelKey,
                    [NSNumber numberWithInteger:[window level]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowMovableKey,
                    [NSNumber numberWithBool:[window isMovable]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowMovableByBackgroundKey,
                    [NSNumber numberWithBool:[window isMovableByWindowBackground]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowShadowKey,
                    [NSNumber numberWithBool:[window hasShadow]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowTitleVisibilityKey,
                    [NSNumber numberWithInteger:[window titleVisibility]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowTitlebarTransparentKey,
                    [NSNumber numberWithBool:[window titlebarAppearsTransparent]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                NSArray* button_visibility = @[
                    [NSNumber numberWithBool:[[window standardWindowButton:NSWindowCloseButton] isHidden]],
                    [NSNumber numberWithBool:[[window standardWindowButton:NSWindowMiniaturizeButton] isHidden]],
                    [NSNumber numberWithBool:[[window standardWindowButton:NSWindowZoomButton] isHidden]]
                ];
                objc_setAssociatedObject(
                    window,
                    &kKMPWindowButtonVisibilityKey,
                    button_visibility,
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
                objc_setAssociatedObject(
                    window,
                    &kKMPApplicationPresentationOptionsKey,
                    [NSNumber numberWithUnsignedLongLong:
                        (unsigned long long)[NSApp presentationOptions]],
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC
                );
            }

            NSScreen* screen = [window screen] ?: [NSScreen mainScreen];
            if (!screen) return;
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            [NSApp setPresentationOptions:
                [NSApp presentationOptions] |
                NSApplicationPresentationAutoHideDock |
                NSApplicationPresentationAutoHideMenuBar];
            [window setStyleMask:[window styleMask] | NSWindowStyleMaskFullSizeContentView];
            [window setTitleVisibility:NSWindowTitleHidden];
            [window setTitlebarAppearsTransparent:YES];
            [[window standardWindowButton:NSWindowCloseButton] setHidden:YES];
            [[window standardWindowButton:NSWindowMiniaturizeButton] setHidden:YES];
            [[window standardWindowButton:NSWindowZoomButton] setHidden:YES];
            [window setMovable:NO];
            [window setMovableByWindowBackground:NO];
            [window setHasShadow:NO];
            [window setFrame:[screen frame] display:YES animate:NO];
            [window makeKeyAndOrderFront:nil];
            [CATransaction commit];
            context->result = YES;
            return;
        }

        if (!stored_frame) {
            context->result = YES;
            return;
        }

        NSNumber* style_mask = objc_getAssociatedObject(window, &kKMPWindowStyleMaskKey);
        NSNumber* level = objc_getAssociatedObject(window, &kKMPWindowLevelKey);
        NSNumber* movable = objc_getAssociatedObject(window, &kKMPWindowMovableKey);
        NSNumber* movable_by_background =
            objc_getAssociatedObject(window, &kKMPWindowMovableByBackgroundKey);
        NSNumber* shadow = objc_getAssociatedObject(window, &kKMPWindowShadowKey);
        NSNumber* title_visibility =
            objc_getAssociatedObject(window, &kKMPWindowTitleVisibilityKey);
        NSNumber* titlebar_transparent =
            objc_getAssociatedObject(window, &kKMPWindowTitlebarTransparentKey);
        NSArray* button_visibility =
            objc_getAssociatedObject(window, &kKMPWindowButtonVisibilityKey);
        NSNumber* presentation_options =
            objc_getAssociatedObject(window, &kKMPApplicationPresentationOptionsKey);

        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        if (presentation_options) {
            [NSApp setPresentationOptions:
                (NSApplicationPresentationOptions)[presentation_options unsignedLongLongValue]];
        }
        if (style_mask) {
            [window setStyleMask:(NSWindowStyleMask)[style_mask unsignedLongLongValue]];
        }
        if (title_visibility) {
            [window setTitleVisibility:(NSWindowTitleVisibility)[title_visibility integerValue]];
        }
        if (titlebar_transparent) {
            [window setTitlebarAppearsTransparent:[titlebar_transparent boolValue]];
        }
        if ([button_visibility count] == 3) {
            [[window standardWindowButton:NSWindowCloseButton]
                setHidden:[[button_visibility objectAtIndex:0] boolValue]];
            [[window standardWindowButton:NSWindowMiniaturizeButton]
                setHidden:[[button_visibility objectAtIndex:1] boolValue]];
            [[window standardWindowButton:NSWindowZoomButton]
                setHidden:[[button_visibility objectAtIndex:2] boolValue]];
        }
        if (movable) [window setMovable:[movable boolValue]];
        if (movable_by_background) {
            [window setMovableByWindowBackground:[movable_by_background boolValue]];
        }
        if (shadow) [window setHasShadow:[shadow boolValue]];
        if (level) [window setLevel:[level integerValue]];
        [window setFrame:[stored_frame rectValue] display:YES animate:NO];
        [window makeKeyAndOrderFront:nil];
        [CATransaction commit];

        objc_setAssociatedObject(window, &kKMPWindowedFrameKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowStyleMaskKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowLevelKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowMovableKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowMovableByBackgroundKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowShadowKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowTitleVisibilityKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowTitlebarTransparentKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(window, &kKMPWindowButtonVisibilityKey, nil, OBJC_ASSOCIATION_ASSIGN);
        objc_setAssociatedObject(
            window,
            &kKMPApplicationPresentationOptionsKey,
            nil,
            OBJC_ASSOCIATION_ASSIGN
        );
        context->result = YES;
    }
}

#endif

static jboolean JNICALL jni_SetNativeWindowFullscreen(
    JNIEnv* env,
    jclass cls,
    jlong native_view,
    jboolean fullscreen
) {
    (void)env;
    (void)cls;
#ifdef __OBJC__
    KMPNativeWindowFullscreenContext context = {
        .view = (NSView*)(uintptr_t)native_view,
        .fullscreen = fullscreen == JNI_TRUE,
        .result = NO,
    };
    run_on_appkit_main_sync(set_native_window_fullscreen_on_appkit_main, &context);
    return context.result ? JNI_TRUE : JNI_FALSE;
#else
    (void)native_view;
    (void)fullscreen;
    return JNI_FALSE;
#endif
}

static void JNICALL jni_SetHdrMetalContentScaleMode(JNIEnv* env, jclass cls, jlong handle, jint mode) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return;
    void* ctx = avCtx(native);
    if (ctx) setHdrMetalContentScaleMode(ctx, (int32_t)mode);
}

static jboolean JNICALL jni_IsHdrMetalAvailable(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return JNI_FALSE;
    void* ctx = avCtx(native);
    return ctx ? (jboolean)(isHdrMetalAvailable(ctx) != 0) : JNI_FALSE;
}

static jboolean JNICALL jni_IsHdrOutputReady(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return JNI_FALSE;
    void* ctx = avCtx(native);
    return ctx ? (jboolean)(isHdrOutputReady(ctx) != 0) : JNI_FALSE;
}

static jfloat JNICALL jni_GetVideoFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0.0f;
    void* ctx = avCtx(native);
    return ctx ? getVideoFrameRate(ctx) : 0.0f;
}

static jfloat JNICALL jni_GetScreenRefreshRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 60.0f;
    void* ctx = avCtx(native);
    return ctx ? getScreenRefreshRate(ctx) : 0.0f;
}

static jfloat JNICALL jni_GetCaptureFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 30.0f;
    void* ctx = avCtx(native);
    return ctx ? getCaptureFrameRate(ctx) : 0.0f;
}

static jstring JNICALL jni_GetPlaybackDiagnostics(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc) {
        if (vlc->native_video) {
            if (!vlc->player || !vlc->api.media_player_get_media || !vlc->api.media_get_stats) return NULL;
            libvlc_media_t* media = vlc->api.media_player_get_media(vlc->player);
            if (!media) return NULL;
            libvlc_media_stats_t stats;
            memset(&stats, 0, sizeof(stats));
            if (!vlc->api.media_get_stats(media, &stats)) return NULL;
            uint64_t decoded_frames = stats.i_decoded_video > 0 ? (uint64_t)stats.i_decoded_video : 0;
            uint64_t dropped_frames = stats.i_lost_pictures > 0 ? (uint64_t)stats.i_lost_pictures : 0;
            char value[256];
            snprintf(
                value,
                sizeof(value),
                // With macOS native video output, libVLC's i_displayed_pictures can stop at a
                // non-zero value while the AppKit drawable continues presenting new frames.
                // Report it as unavailable instead of exposing a misleading frozen counter.
                "totalFrames=%llu;renderedFrames=-1;droppedFrames=%llu;maxAvSyncMs=-1;playedSeconds=-1",
                (unsigned long long)decoded_frames,
                (unsigned long long)dropped_frames
            );
            return (*env)->NewStringUTF(env, value);
        }
        pthread_mutex_lock(&vlc->frame_mutex);
        uint64_t decoded_frames = vlc->decoded_frames;
        uint64_t displayed_frames = vlc->displayed_frames;
        pthread_mutex_unlock(&vlc->frame_mutex);
        uint64_t dropped_frames = decoded_frames > displayed_frames
            ? decoded_frames - displayed_frames
            : 0;
        char value[256];
        snprintf(
            value,
            sizeof(value),
            "totalFrames=%llu;renderedFrames=%llu;droppedFrames=%llu;maxAvSyncMs=-1;playedSeconds=-1",
            (unsigned long long)decoded_frames,
            (unsigned long long)displayed_frames,
            (unsigned long long)dropped_frames
        );
        return (*env)->NewStringUTF(env, value);
    }
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* value = getPlaybackDiagnostics(ctx);
    if (!value) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    free((void*)value);
    return result;
}

static jdouble JNICALL jni_GetVideoDuration(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        libvlc_time_t length = vlc->api.media_player_get_length(vlc->player);
        return length > 0 ? (jdouble)length / 1000.0 : 0.0;
    }
    void* ctx = avCtx(native);
    return ctx ? getVideoDuration(ctx) : 0.0;
}

static jdouble JNICALL jni_GetCurrentTime(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        libvlc_time_t time = vlc->api.media_player_get_time(vlc->player);
        return time > 0 ? (jdouble)time / 1000.0 : 0.0;
    }
    void* ctx = avCtx(native);
    return ctx ? getCurrentTime(ctx) : 0.0;
}

static void JNICALL jni_SeekTo(JNIEnv* env, jclass cls, jlong handle, jdouble time) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_set_time(vlc->player, (libvlc_time_t)(time * 1000.0));
        vlc_apply_pending_tracks(vlc);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) seekTo(ctx, (double)time);
}

static void JNICALL jni_DisposePlayer(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (!native) return;
    if (native->kind == PLAYER_KIND_AV && native->native_view) {
#ifdef __OBJC__
        KMPDisposeNativeVideoViewContext context = {
            .native = native,
            .view = (NSView*)native->native_view,
        };
        run_on_appkit_main_sync(dispose_native_video_view_on_appkit_main, &context);
#else
        native->native_view = NULL;
#endif
    }
    if (native->kind == PLAYER_KIND_LIBVLC) {
        dispose_libvlc_player((LibVlcPlayer*)native->ctx);
    } else if (native->kind == PLAYER_KIND_AV && native->ctx) {
        disposeVideoPlayer(native->ctx);
    }
    free(native);
}

static void JNICALL jni_SetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle, jfloat speed) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) {
        vlc->api.media_player_set_rate(vlc->player, (float)speed);
        return;
    }
    void* ctx = avCtx(native);
    if (ctx) setPlaybackSpeed(ctx, (float)speed);
}

static jfloat JNICALL jni_GetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    LibVlcPlayer* vlc = vlcCtx(native);
    if (vlc && vlc->player) return vlc->api.media_player_get_rate(vlc->player);
    void* ctx = avCtx(native);
    return ctx ? getPlaybackSpeed(ctx) : 1.0f;
}

static jstring JNICALL jni_GetVideoTitle(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return NULL;
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* s = getVideoTitle(ctx);
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return result;
}

static jlong JNICALL jni_GetVideoBitrate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0L;
    void* ctx = avCtx(native);
    return ctx ? (jlong)getVideoBitrate(ctx) : 0L;
}

static jstring JNICALL jni_GetVideoMimeType(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return NULL;
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* s = getVideoMimeType(ctx);
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return result;
}

static jstring JNICALL jni_GetVideoColorInfo(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return NULL;
    void* ctx = avCtx(native);
    if (!ctx) return NULL;
    const char* s = getVideoColorInfo(ctx);
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return result;
}

static jint JNICALL jni_GetAudioChannels(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0;
    void* ctx = avCtx(native);
    return ctx ? (jint)getAudioChannels(ctx) : 0;
}

static jint JNICALL jni_GetAudioSampleRate(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return 0;
    void* ctx = avCtx(native);
    return ctx ? (jint)getAudioSampleRate(ctx) : 0;
}

static jboolean JNICALL jni_ConsumeDidPlayToEnd(JNIEnv* env, jclass cls, jlong handle) {
    NativePlayerHandle* native = toHandle(handle);
    if (vlcCtx(native)) return JNI_FALSE;
    void* ctx = avCtx(native);
    return ctx ? (jboolean)(consumeDidPlayToEnd(ctx) != 0) : JNI_FALSE;
}

static jboolean JNICALL jni_SelectLibVlcAudioTrack(JNIEnv* env, jclass cls, jlong handle, jint ordinal) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc) return JNI_FALSE;
    vlc->pending_audio_ordinal = ordinal;
    return (jboolean)(vlc_apply_audio_ordinal(vlc, ordinal) != 0);
}

static jboolean JNICALL jni_SelectLibVlcSubtitleTrack(JNIEnv* env, jclass cls, jlong handle, jint ordinal) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc) return JNI_FALSE;
    vlc->pending_spu_ordinal = ordinal;
    return (jboolean)(vlc_apply_spu_ordinal(vlc, ordinal) != 0);
}

static jstring JNICALL jni_GetLibVlcAudioTrackDescriptions(JNIEnv* env, jclass cls, jlong handle) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc || !vlc->player) return NULL;

    libvlc_track_description_t* descriptions = vlc->api.audio_get_track_description(vlc->player);
    jstring result = vlc_track_descriptions_to_jstring(env, descriptions);
    if (descriptions) vlc->api.track_description_list_release(descriptions);
    return result;
}

static jstring JNICALL jni_GetLibVlcSubtitleTrackDescriptions(JNIEnv* env, jclass cls, jlong handle) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc || !vlc->player) return NULL;

    libvlc_track_description_t* descriptions = vlc->api.video_get_spu_description(vlc->player);
    jstring result = vlc_track_descriptions_to_jstring(env, descriptions);
    if (descriptions) vlc->api.track_description_list_release(descriptions);
    return result;
}

static jboolean JNICALL jni_DisableLibVlcSubtitles(JNIEnv* env, jclass cls, jlong handle) {
    LibVlcPlayer* vlc = vlcCtx(toHandle(handle));
    if (!vlc) return JNI_FALSE;
    vlc->pending_spu_ordinal = -1;
    return (jboolean)(vlc_apply_spu_ordinal(vlc, -1) != 0);
}

// ---------------------------------------------------------------------------
// Registration table
// ---------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "nCreatePlayer",           "()J",                         (void*)jni_CreatePlayer },
    { "nCreateLibVlcPlayer",     "(Ljava/lang/String;Ljava/lang/String;Z)J", (void*)jni_CreateLibVlcPlayer },
    { "nOpenUri",                "(JLjava/lang/String;)V",      (void*)jni_OpenUri },
    { "nOpenUriWithHeaders",     "(JLjava/lang/String;Ljava/lang/String;)V", (void*)jni_OpenUriWithHeaders },
    { "nOpenUriWithHeaderLines", "(JLjava/lang/String;Ljava/lang/String;)V", (void*)jni_OpenUriWithHeaderLines },
    { "nPrepareUriReplacement",  "(JLjava/lang/String;Ljava/lang/String;)J", (void*)jni_PrepareUriReplacement },
    { "nGetUriReplacementStatus", "(JJ)I",                       (void*)jni_GetUriReplacementStatus },
    { "nGetUriReplacementError", "(JJ)Ljava/lang/String;",       (void*)jni_GetUriReplacementError },
    { "nCommitUriReplacement",   "(JJ)Z",                        (void*)jni_CommitUriReplacement },
    { "nCancelUriReplacement",   "(JJ)V",                        (void*)jni_CancelUriReplacement },
    { "nPlay",                   "(J)V",                        (void*)jni_Play },
    { "nPause",                  "(J)V",                        (void*)jni_Pause },
    { "nRetirePlayer",           "(J)V",                        (void*)jni_RetirePlayer },
    { "nIsReadyForPlayback",     "(J)Z",                        (void*)jni_IsReadyForPlayback },
    { "nSetVolume",              "(JF)V",                       (void*)jni_SetVolume },
    { "nGetVolume",              "(J)F",                        (void*)jni_GetVolume },
    { "nLockFrame",              "(J[I)J",                      (void*)jni_LockFrame },
    { "nUnlockFrame",            "(J)V",                        (void*)jni_UnlockFrame },
    { "nWrapPointer",            "(JJ)Ljava/nio/ByteBuffer;",   (void*)jni_WrapPointer },
    { "nGetFrameWidth",          "(J)I",                        (void*)jni_GetFrameWidth },
    { "nGetFrameHeight",         "(J)I",                        (void*)jni_GetFrameHeight },
    { "nGetDisplayAspectRatio",  "(J)D",                        (void*)jni_GetDisplayAspectRatio },
    { "nSetOutputSize",          "(JII)I",                      (void*)jni_SetOutputSize },
    { "nSetHdrMetalTextureOutput", "(JJ)Z",                     (void*)jni_SetHdrMetalTextureOutput },
    { "nSetHdrMetalTextureViewportSize", "(JII)V",              (void*)jni_SetHdrMetalTextureViewportSize },
    { "nGetHdrMetalTextureOutputInfo", "(J[J)Z",                (void*)jni_GetHdrMetalTextureOutputInfo },
    { "nSetHdrMetalPreferred",   "(JZ)V",                       (void*)jni_SetHdrMetalPreferred },
    { "nSetHdrToneMappingEnabled", "(JZ)V",                     (void*)jni_SetHdrToneMappingEnabled },
    { "nSetHdrMetalProjectionConfiguration", "(JLjava/lang/String;)Z", (void*)jni_SetHdrMetalProjectionConfiguration },
    { "nGetHdrRendererFailure", "(J)Ljava/lang/String;",       (void*)jni_GetHdrRendererFailure },
    { "nGetDisplayColorCapabilities", "(J)Ljava/lang/String;",   (void*)jni_GetDisplayColorCapabilities },
    { "nGetDisplayColorCapabilitiesForView", "(J)Ljava/lang/String;", (void*)jni_GetDisplayColorCapabilitiesForView },
    { "nCreateNativeVideoView",  "(J)J",                        (void*)jni_CreateNativeVideoView },
    { "nDisposeNativeVideoView", "(JJ)V",                       (void*)jni_DisposeNativeVideoView },
    { "nSetNativeWindowFullscreen", "(JZ)Z",                    (void*)jni_SetNativeWindowFullscreen },
    { "nSetHdrMetalContentScaleMode", "(JI)V",                  (void*)jni_SetHdrMetalContentScaleMode },
    { "nIsHdrMetalAvailable",    "(J)Z",                        (void*)jni_IsHdrMetalAvailable },
    { "nIsHdrOutputReady",       "(J)Z",                        (void*)jni_IsHdrOutputReady },
    { "nGetVideoFrameRate",      "(J)F",                        (void*)jni_GetVideoFrameRate },
    { "nGetScreenRefreshRate",   "(J)F",                        (void*)jni_GetScreenRefreshRate },
    { "nGetCaptureFrameRate",    "(J)F",                        (void*)jni_GetCaptureFrameRate },
    { "nGetPlaybackDiagnostics", "(J)Ljava/lang/String;",       (void*)jni_GetPlaybackDiagnostics },
    { "nGetVideoDuration",       "(J)D",                        (void*)jni_GetVideoDuration },
    { "nGetCurrentTime",         "(J)D",                        (void*)jni_GetCurrentTime },
    { "nSeekTo",                 "(JD)V",                       (void*)jni_SeekTo },
    { "nDisposePlayer",          "(J)V",                        (void*)jni_DisposePlayer },
    { "nSetPlaybackSpeed",       "(JF)V",                       (void*)jni_SetPlaybackSpeed },
    { "nGetPlaybackSpeed",       "(J)F",                        (void*)jni_GetPlaybackSpeed },
    { "nGetVideoTitle",          "(J)Ljava/lang/String;",       (void*)jni_GetVideoTitle },
    { "nGetVideoBitrate",        "(J)J",                        (void*)jni_GetVideoBitrate },
    { "nGetVideoMimeType",       "(J)Ljava/lang/String;",       (void*)jni_GetVideoMimeType },
    { "nGetVideoColorInfo",      "(J)Ljava/lang/String;",       (void*)jni_GetVideoColorInfo },
    { "nGetAudioChannels",       "(J)I",                        (void*)jni_GetAudioChannels },
    { "nGetAudioSampleRate",     "(J)I",                        (void*)jni_GetAudioSampleRate },
    { "nConsumeDidPlayToEnd",    "(J)Z",                        (void*)jni_ConsumeDidPlayToEnd },
    { "nSelectLibVlcAudioTrack", "(JI)Z",                       (void*)jni_SelectLibVlcAudioTrack },
    { "nSelectLibVlcSubtitleTrack", "(JI)Z",                    (void*)jni_SelectLibVlcSubtitleTrack },
    { "nGetLibVlcAudioTrackDescriptions", "(J)Ljava/lang/String;", (void*)jni_GetLibVlcAudioTrackDescriptions },
    { "nGetLibVlcSubtitleTrackDescriptions", "(J)Ljava/lang/String;", (void*)jni_GetLibVlcSubtitleTrackDescriptions },
    { "nDisableLibVlcSubtitles", "(J)Z",                        (void*)jni_DisableLibVlcSubtitles },
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        return -1;

    jclass cls = (*env)->FindClass(
        env, "io/github/kdroidfilter/composemediaplayer/mac/MacNativeBridge");
    if (!cls) return -1;

    int count = (int)(sizeof(g_methods) / sizeof(g_methods[0]));
    if ((*env)->RegisterNatives(env, cls, g_methods, count) < 0)
        return -1;

    return JNI_VERSION_1_6;
}
