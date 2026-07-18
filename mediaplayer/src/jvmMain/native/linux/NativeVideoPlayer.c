// NativeVideoPlayer.c — Linux GStreamer-based native video player
// Uses GStreamer C API directly: playbin + appsink/waylandsink + level element.
// Bus messages are processed by a dedicated polling thread (no GLib main loop needed).

#include "NativeVideoPlayer.h"
#include "LinuxVulkanProjection.h"

#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/allocators/gstdmabuf.h>
#include <gst/video/video-info.h>
#include <gst/video/video-info-dma.h>
#include <gst/video/videooverlay.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>

#define NVP_DRM_FOURCC(a, b, c, d) \
    ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define NVP_DRM_FORMAT_P010 NVP_DRM_FOURCC('P', '0', '1', '0')
#define NVP_GST_VIDEO_HDR_FORMAT_HDR10_PLUS 2
#define NVP_HDR10_PLUS_MAX_BYTES 1024u

typedef GType (*GstVideoHdrMetaApiGetTypeFunction)(void);

typedef struct GstVideoHdrMetaCompat {
    GstMeta meta;
    int format;
    guint8* data;
    gsize size;
} GstVideoHdrMetaCompat;

static pthread_once_t hdr_meta_api_once = PTHREAD_ONCE_INIT;
static GstVideoHdrMetaApiGetTypeFunction hdr_meta_api_get_type;
static void* hdr_meta_library;

static void resolve_hdr_meta_api(void) {
    hdr_meta_api_get_type =
        (GstVideoHdrMetaApiGetTypeFunction)dlsym(RTLD_DEFAULT, "gst_video_hdr_meta_api_get_type");
    if (!hdr_meta_api_get_type) {
        hdr_meta_library = dlopen("libgstvideo-1.0.so.0", RTLD_LAZY | RTLD_LOCAL);
        if (hdr_meta_library) {
            hdr_meta_api_get_type =
                (GstVideoHdrMetaApiGetTypeFunction)dlsym(
                    hdr_meta_library,
                    "gst_video_hdr_meta_api_get_type"
                );
        }
    }
}

static const uint8_t* hdr10_plus_payload_from_buffer(GstBuffer* buffer, size_t* payload_size) {
    if (payload_size) *payload_size = 0;
    if (!buffer || !payload_size) return NULL;
    pthread_once(&hdr_meta_api_once, resolve_hdr_meta_api);
    if (!hdr_meta_api_get_type) return NULL;
    const GType api_type = hdr_meta_api_get_type();
    if (api_type == 0) return NULL;
    GstVideoHdrMetaCompat* metadata =
        (GstVideoHdrMetaCompat*)gst_buffer_get_meta(buffer, api_type);
    if (!metadata ||
        metadata->format != NVP_GST_VIDEO_HDR_FORMAT_HDR10_PLUS ||
        !metadata->data ||
        metadata->size == 0 ||
        metadata->size > NVP_HDR10_PLUS_MAX_BYTES) return NULL;
    *payload_size = (size_t)metadata->size;
    return metadata->data;
}

// ---------------------------------------------------------------------------
// Internal structures
// ---------------------------------------------------------------------------

struct VideoPlayer {
    GstElement* pipeline;   // playbin
    GstElement* video_sink; // appsink
    GstElement* memory_video_bin;
    GstElement* wayland_sink;
    LinuxVulkanProjection* projection_renderer;
    GstElement* audio_bin;  // custom audio bin with scaletempo + level
    GstElement* level;      // level element reference

    // Frame buffer (BGRA)
    pthread_mutex_t frame_lock;
    uint8_t* frame_buffer;
    int32_t  frame_width;
    int32_t  frame_height;
    size_t   frame_size;

    // Output scaling
    int32_t output_width;
    int32_t output_height;

    // Metadata
    pthread_mutex_t meta_lock;
    char*   title;
    int64_t bitrate;
    char*   mime_type;
    char*   video_decoder_name;
    int32_t audio_channels;
    int32_t audio_sample_rate;
    float   frame_rate;

    // Playback state
    float   volume;
    float   playback_speed;
    int     did_play_to_end; // atomic flag
    pthread_mutex_t headers_lock;
    char*   request_headers;

    // Direct Wayland output. The wl_display and parent wl_surface are owned by JBR.
    pthread_mutex_t output_lock;
    uintptr_t wayland_display;
    uintptr_t wayland_parent_surface;
    int32_t wayland_x;
    int32_t wayland_y;
    int32_t wayland_width;
    int32_t wayland_height;
    int32_t wayland_output_state;
    gulong wayland_probe_id;
    int32_t decoded_color_generation;
    int32_t decoded_bit_depth;
    int32_t decoded_primaries;
    int32_t decoded_transfer;
    int32_t decoded_matrix;
    int32_t decoded_range;
    int32_t decoded_authoritative_unknowns;

    // Bus polling thread
    pthread_t bus_thread;
    volatile int bus_thread_running;
};

// ---------------------------------------------------------------------------
// Forward declarations
// ---------------------------------------------------------------------------

static void  process_bus_message(VideoPlayer* p, GstMessage* msg);
static void  gst_init_func(void);
static void* bus_thread_func(void* data);
static GstFlowReturn on_new_sample(GstAppSink* sink, gpointer data);
static void  on_source_setup(GstElement* playbin, GstElement* source, gpointer data);
static void  on_element_setup(GstElement* playbin, GstElement* element, gpointer data);
static void  apply_request_headers_to_source(GstElement* source, const char* header_lines);
static void  update_metadata_from_tags(VideoPlayer* p, GstTagList* tags);
static void  update_stream_metadata(VideoPlayer* p);
static void  update_wayland_output_status(VideoPlayer* p, GstCaps* caps);
static void  update_decoded_color_info(VideoPlayer* p, GstCaps* caps);
static GstPadProbeReturn on_wayland_sink_buffer(GstPad* pad, GstPadProbeInfo* info, gpointer data);
static GstPadProbeReturn on_decoder_caps_event(GstPad* pad, GstPadProbeInfo* info, gpointer data);

static char* duplicate_string(const char* value) {
    if (!value) return NULL;
    const size_t size = strlen(value) + 1;
    char* result = malloc(size);
    if (result) memcpy(result, value, size);
    return result;
}

// ---------------------------------------------------------------------------
// GStreamer init (once)
// ---------------------------------------------------------------------------

static pthread_once_t gst_init_once = PTHREAD_ONCE_INIT;

int nvp_get_native_version(void) {
    return NATIVE_VIDEO_PLAYER_VERSION;
}

static int has_element_factory(const char* name) {
    GstElementFactory* factory = gst_element_factory_find(name);
    if (!factory) return 0;
    gst_object_unref(factory);
    return 1;
}

void nvp_get_gstreamer_runtime_info(uint32_t out_info[5]) {
    if (!out_info) return;
    pthread_once(&gst_init_once, gst_init_func);
    guint major = 0;
    guint minor = 0;
    guint micro = 0;
    guint nano = 0;
    gst_version(&major, &minor, &micro, &nano);
    uint32_t flags = 0;
    if (has_element_factory("waylandsink")) flags |= NVP_GSTREAMER_WAYLAND_SINK;
    if (has_element_factory("vulkanupload")) flags |= NVP_GSTREAMER_VULKAN_UPLOAD;
    if (has_element_factory("vulkancolorconvert")) flags |= NVP_GSTREAMER_VULKAN_COLOR_CONVERT;
    if (has_element_factory("vulkanshaderspv")) flags |= NVP_GSTREAMER_VULKAN_SHADER_SPV;
    if (has_element_factory("vulkanoverlaycompositor")) {
        flags |= NVP_GSTREAMER_VULKAN_OVERLAY_COMPOSITOR;
    }
    out_info[0] = major;
    out_info[1] = minor;
    out_info[2] = micro;
    out_info[3] = nano;
    out_info[4] = flags;
}

static void gst_init_func(void) {
    gst_init(NULL, NULL);
}

// ---------------------------------------------------------------------------
// Bus polling thread
// ---------------------------------------------------------------------------

static void* bus_thread_func(void* data) {
    VideoPlayer* p = (VideoPlayer*)data;
    GstBus* bus = gst_element_get_bus(p->pipeline);

    while (p->bus_thread_running) {
        // Block up to 100ms waiting for a message
        GstMessage* msg = gst_bus_timed_pop(bus, 100 * GST_MSECOND);
        if (msg) {
            process_bus_message(p, msg);
            gst_message_unref(msg);
        }
    }

    gst_object_unref(bus);
    return NULL;
}

static void process_bus_message(VideoPlayer* p, GstMessage* msg) {
    switch (GST_MESSAGE_TYPE(msg)) {
    case GST_MESSAGE_EOS:
        __sync_lock_test_and_set(&p->did_play_to_end, 1);
        break;

    case GST_MESSAGE_ERROR: {
        GError* err = NULL;
        gchar* debug = NULL;
        gst_message_parse_error(msg, &err, &debug);
        if (err) {
            g_printerr("GStreamer error: %s\n", err->message);
            g_error_free(err);
        }
        if (debug) g_free(debug);
        pthread_mutex_lock(&p->output_lock);
        if (p->wayland_sink || p->projection_renderer) {
            p->wayland_output_state |= NVP_WAYLAND_OUTPUT_ERROR;
        }
        pthread_mutex_unlock(&p->output_lock);
        break;
    }

    case GST_MESSAGE_TAG: {
        GstTagList* tags = NULL;
        gst_message_parse_tag(msg, &tags);
        if (tags) {
            update_metadata_from_tags(p, tags);
            gst_tag_list_unref(tags);
        }
        break;
    }

    case GST_MESSAGE_STATE_CHANGED: {
        if (GST_MESSAGE_SRC(msg) == GST_OBJECT(p->pipeline)) {
            GstState old_state, new_state;
            gst_message_parse_state_changed(msg, &old_state, &new_state, NULL);
            if (new_state == GST_STATE_PAUSED || new_state == GST_STATE_PLAYING) {
                update_stream_metadata(p);
                if (p->wayland_sink) {
                    GstPad* pad = gst_element_get_static_pad(p->wayland_sink, "sink");
                    GstCaps* caps = pad ? gst_pad_get_current_caps(pad) : NULL;
                    update_wayland_output_status(p, caps);
                    if (caps) gst_caps_unref(caps);
                    if (pad) gst_object_unref(pad);
                }
            }
        }
        break;
    }

    default:
        break;
    }
}

// ---------------------------------------------------------------------------
// HTTP request headers
// ---------------------------------------------------------------------------

static void on_source_setup(GstElement* playbin, GstElement* source, gpointer data) {
    (void)playbin;
    VideoPlayer* p = (VideoPlayer*)data;
    if (!p || !source) return;

    char* headers = NULL;
    pthread_mutex_lock(&p->headers_lock);
    if (p->request_headers) {
        headers = g_strdup(p->request_headers);
    }
    pthread_mutex_unlock(&p->headers_lock);

    if (!headers) return;
    apply_request_headers_to_source(source, headers);
    g_free(headers);
}

static void on_element_setup(GstElement* playbin, GstElement* element, gpointer data) {
    (void)playbin;
    VideoPlayer* p = (VideoPlayer*)data;
    if (!p || !element) return;

    GstElementFactory* factory = gst_element_get_factory(element);
    if (factory) {
        const gchar* classification =
            gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
        if (classification && strstr(classification, "Decoder") && strstr(classification, "Video")) {
            const gchar* factory_name = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
            const gchar* long_name =
                gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_LONGNAME);
            gchar* reported_name =
                long_name && long_name[0]
                    ? g_strdup_printf("%s (%s)", factory_name, long_name)
                    : g_strdup(factory_name);
            if (reported_name) {
                pthread_mutex_lock(&p->meta_lock);
                free(p->video_decoder_name);
                p->video_decoder_name = duplicate_string(reported_name);
                pthread_mutex_unlock(&p->meta_lock);
                g_free(reported_name);
            }
            GstPad* source_pad = gst_element_get_static_pad(element, "src");
            if (source_pad) {
                gst_pad_add_probe(
                    source_pad,
                    GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM,
                    on_decoder_caps_event,
                    p,
                    NULL
                );
                gst_object_unref(source_pad);
            }
        }
    }

    char* headers = NULL;
    pthread_mutex_lock(&p->headers_lock);
    if (p->request_headers) {
        headers = g_strdup(p->request_headers);
    }
    pthread_mutex_unlock(&p->headers_lock);

    if (!headers) return;
    apply_request_headers_to_source(element, headers);
    g_free(headers);
}

static void apply_request_headers_to_source(GstElement* source, const char* header_lines) {
    if (!source || !header_lines || !header_lines[0]) return;

    GParamSpec* extra_headers_property =
        g_object_class_find_property(G_OBJECT_GET_CLASS(source), "extra-headers");
    GParamSpec* user_agent_property =
        g_object_class_find_property(G_OBJECT_GET_CLASS(source), "user-agent");
    if (extra_headers_property && extra_headers_property->value_type != GST_TYPE_STRUCTURE) {
        extra_headers_property = NULL;
    }
    if (user_agent_property && user_agent_property->value_type != G_TYPE_STRING) {
        user_agent_property = NULL;
    }

    if (!extra_headers_property && !user_agent_property) return;

    GstStructure* headers = gst_structure_new_empty("request-headers");
    gboolean has_headers = FALSE;
    gchar** lines = g_strsplit(header_lines, "\n", -1);

    for (gchar** cursor = lines; cursor && *cursor; cursor++) {
        gchar* line = g_strstrip(*cursor);
        if (!line[0]) continue;

        gchar* separator = strchr(line, ':');
        if (!separator) continue;

        *separator = '\0';
        gchar* name = g_strstrip(line);
        gchar* value = g_strstrip(separator + 1);
        if (!name[0] || !value[0]) continue;

        if (extra_headers_property) {
            gst_structure_set(headers, name, G_TYPE_STRING, value, NULL);
            has_headers = TRUE;
        }
        if (user_agent_property && g_ascii_strcasecmp(name, "User-Agent") == 0) {
            g_object_set(source, "user-agent", value, NULL);
        }
    }

    if (extra_headers_property && has_headers) {
        g_object_set(source, "extra-headers", headers, NULL);
    }

    g_strfreev(lines);
    gst_structure_free(headers);
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

VideoPlayer* nvp_create(void) {
    pthread_once(&gst_init_once, gst_init_func);

    VideoPlayer* p = calloc(1, sizeof(VideoPlayer));
    if (!p) return NULL;

    pthread_mutex_init(&p->frame_lock, NULL);
    pthread_mutex_init(&p->meta_lock, NULL);
    pthread_mutex_init(&p->headers_lock, NULL);
    pthread_mutex_init(&p->output_lock, NULL);
    p->volume = 1.0f;
    p->playback_speed = 1.0f;

    // Create playbin
    p->pipeline = gst_element_factory_make("playbin", NULL);
    if (!p->pipeline) {
        pthread_mutex_destroy(&p->frame_lock);
        pthread_mutex_destroy(&p->meta_lock);
        pthread_mutex_destroy(&p->headers_lock);
        pthread_mutex_destroy(&p->output_lock);
        free(p);
        return NULL;
    }

    if (g_signal_lookup("source-setup", G_OBJECT_TYPE(p->pipeline)) != 0) {
        g_signal_connect(p->pipeline, "source-setup", G_CALLBACK(on_source_setup), p);
    }
    if (g_signal_lookup("element-setup", G_OBJECT_TYPE(p->pipeline)) != 0) {
        g_signal_connect(p->pipeline, "element-setup", G_CALLBACK(on_element_setup), p);
    }

    // Create appsink for video frames
    p->video_sink = gst_element_factory_make("appsink", NULL);
    if (!p->video_sink) {
        gst_object_unref(p->pipeline);
        pthread_mutex_destroy(&p->frame_lock);
        pthread_mutex_destroy(&p->meta_lock);
        pthread_mutex_destroy(&p->headers_lock);
        pthread_mutex_destroy(&p->output_lock);
        free(p);
        return NULL;
    }

    // Configure appsink: BGRA format for direct Skia consumption
    GstCaps* caps = gst_caps_new_simple("video/x-raw",
        "format", G_TYPE_STRING, "BGRA",
        NULL);
    gst_app_sink_set_caps(GST_APP_SINK(p->video_sink), caps);
    gst_caps_unref(caps);

    // Configure appsink for media playback:
    // - sync=true: deliver frames at correct presentation time (default)
    // - drop=true: skip stale frames if app is slow to consume
    // - max-buffers=2: small buffer to smooth out jitter without adding latency
    // - emit-signals=true: callback from streaming thread
    gst_app_sink_set_emit_signals(GST_APP_SINK(p->video_sink), TRUE);
    gst_app_sink_set_drop(GST_APP_SINK(p->video_sink), TRUE);
    gst_app_sink_set_max_buffers(GST_APP_SINK(p->video_sink), 2);

    // Connect new-sample callback (called from GStreamer's streaming thread)
    g_signal_connect(p->video_sink, "new-sample", G_CALLBACK(on_new_sample), p);

    // Insert a queue before the appsink to decouple the decoder streaming
    // thread from frame extraction. This prevents jitter from the memcpy/mutex
    // in on_new_sample blocking the upstream decoder.
    GstElement* video_queue = gst_element_factory_make("queue", NULL);
    if (video_queue) {
        g_object_set(video_queue,
            "max-size-buffers", (guint)3,
            "max-size-bytes",   (guint)0,
            "max-size-time",    (guint64)0,
            NULL);

        GstBin* video_bin = GST_BIN(gst_bin_new("videobin"));
        gst_bin_add_many(video_bin, video_queue, p->video_sink, NULL);
        gst_element_link(video_queue, p->video_sink);

        // Ghost pad for the bin input
        GstPad* queue_sink = gst_element_get_static_pad(video_queue, "sink");
        GstPad* ghost_pad = gst_ghost_pad_new("sink", queue_sink);
        gst_element_add_pad(GST_ELEMENT(video_bin), ghost_pad);
        gst_object_unref(queue_sink);

        g_object_set(p->pipeline, "video-sink", video_bin, NULL);
        p->memory_video_bin = gst_object_ref(video_bin);
    } else {
        // Fallback: direct appsink without queue
        g_object_set(p->pipeline, "video-sink", p->video_sink, NULL);
        p->memory_video_bin = gst_object_ref(p->video_sink);
    }

    // Build audio bin: scaletempo -> level -> autoaudiosink
    p->audio_bin = gst_bin_new("audiobin");
    GstElement* scaletempo = gst_element_factory_make("scaletempo", NULL);
    p->level = gst_element_factory_make("level", NULL);
    GstElement* audio_sink = gst_element_factory_make("autoaudiosink", NULL);

    if (!scaletempo || !p->level || !audio_sink) {
        if (scaletempo) gst_object_unref(scaletempo);
        if (p->level) { gst_object_unref(p->level); p->level = NULL; }
        if (audio_sink) gst_object_unref(audio_sink);
        gst_object_unref(p->audio_bin);
        p->audio_bin = NULL;
    } else {
        g_object_set(p->level, "post-messages", TRUE, NULL);
        g_object_set(p->level, "interval", (guint64)(100 * GST_MSECOND), NULL);

        gst_bin_add_many(GST_BIN(p->audio_bin), scaletempo, p->level, audio_sink, NULL);
        gst_element_link_many(scaletempo, p->level, audio_sink, NULL);

        GstPad* sink_pad = gst_element_get_static_pad(scaletempo, "sink");
        GstPad* ghost = gst_ghost_pad_new("sink", sink_pad);
        gst_element_add_pad(p->audio_bin, ghost);
        gst_object_unref(sink_pad);

        g_object_set(p->pipeline, "audio-sink", p->audio_bin, NULL);
    }

    // Start bus polling thread
    p->bus_thread_running = 1;
    pthread_create(&p->bus_thread, NULL, bus_thread_func, p);

    return p;
}

void nvp_destroy(VideoPlayer* p) {
    if (!p) return;

    // Stop pipeline first — this flushes all streaming threads and ensures
    // on_new_sample will no longer be called before we free resources.
    gst_element_set_state(p->pipeline, GST_STATE_NULL);

    // Now stop bus thread (no more messages will arrive after NULL state)
    p->bus_thread_running = 0;
    pthread_join(p->bus_thread, NULL);

    gst_object_unref(p->pipeline);
    if (p->memory_video_bin) gst_object_unref(p->memory_video_bin);
    if (p->wayland_sink) gst_object_unref(p->wayland_sink);
    if (p->projection_renderer) {
        linux_vulkan_projection_destroy(p->projection_renderer);
        p->projection_renderer = NULL;
    }

    pthread_mutex_lock(&p->frame_lock);
    free(p->frame_buffer);
    p->frame_buffer = NULL;
    pthread_mutex_unlock(&p->frame_lock);
    pthread_mutex_destroy(&p->frame_lock);

    pthread_mutex_lock(&p->meta_lock);
    free(p->title);
    free(p->mime_type);
    free(p->video_decoder_name);
    pthread_mutex_unlock(&p->meta_lock);
    pthread_mutex_destroy(&p->meta_lock);

    pthread_mutex_lock(&p->headers_lock);
    g_free(p->request_headers);
    p->request_headers = NULL;
    pthread_mutex_unlock(&p->headers_lock);
    pthread_mutex_destroy(&p->headers_lock);
    pthread_mutex_destroy(&p->output_lock);

    free(p);
}

// ---------------------------------------------------------------------------
// Direct JBR Wayland output
// ---------------------------------------------------------------------------

static int is_ten_bit_format_name(const gchar* format) {
    if (!format) return 0;
    return strstr(format, "P010") != NULL ||
        strstr(format, "10LE") != NULL ||
        strstr(format, "10BE") != NULL ||
        strstr(format, "10A2") != NULL ||
        strstr(format, "10x2") != NULL;
}

static int normalized_primaries(GstVideoColorPrimaries primaries) {
    switch (primaries) {
    case GST_VIDEO_COLOR_PRIMARIES_BT470M:
    case GST_VIDEO_COLOR_PRIMARIES_SMPTE170M:
        return 1; // BT.601 525
    case GST_VIDEO_COLOR_PRIMARIES_BT470BG:
        return 2; // BT.601 625
    case GST_VIDEO_COLOR_PRIMARIES_BT709:
        return 3;
    case GST_VIDEO_COLOR_PRIMARIES_BT2020:
        return 4;
    case GST_VIDEO_COLOR_PRIMARIES_SMPTEEG432:
        return 5; // Display P3 / D65
    default:
        return 0;
    }
}

static int normalized_transfer(GstVideoTransferFunction transfer) {
    switch (transfer) {
    case GST_VIDEO_TRANSFER_SMPTE2084:
        return 4;
    case GST_VIDEO_TRANSFER_ARIB_STD_B67:
        return 5;
    case GST_VIDEO_TRANSFER_SRGB:
        return 2;
    case GST_VIDEO_TRANSFER_GAMMA10:
        return 3;
    case GST_VIDEO_TRANSFER_BT709:
    case GST_VIDEO_TRANSFER_BT601:
    case GST_VIDEO_TRANSFER_BT2020_10:
    case GST_VIDEO_TRANSFER_BT2020_12:
        return 1;
    default:
        return 0;
    }
}

static int normalized_matrix(GstVideoColorMatrix matrix) {
    switch (matrix) {
    case GST_VIDEO_COLOR_MATRIX_RGB:
        return 1;
    case GST_VIDEO_COLOR_MATRIX_BT601:
        return 2;
    case GST_VIDEO_COLOR_MATRIX_BT709:
        return 3;
    case GST_VIDEO_COLOR_MATRIX_BT2020:
        return 4;
    default:
        return 0;
    }
}

static int normalized_range(GstVideoColorRange range) {
    switch (range) {
    case GST_VIDEO_COLOR_RANGE_16_235:
        return 1;
    case GST_VIDEO_COLOR_RANGE_0_255:
        return 2;
    default:
        return 0;
    }
}

static int video_info_from_any_caps(GstCaps* caps, GstVideoInfo* info) {
    gst_video_info_init(info);
    if (gst_video_info_from_caps(info, caps)) return 1;
    if (!gst_video_is_dma_drm_caps(caps)) return 0;
    GstVideoInfoDmaDrm dma_info;
    gst_video_info_dma_drm_init(&dma_info);
    return gst_video_info_dma_drm_from_caps(&dma_info, caps) &&
        gst_video_info_dma_drm_to_video_info(&dma_info, info);
}

static void update_decoded_color_info(VideoPlayer* p, GstCaps* caps) {
    if (!p || !caps || gst_caps_is_empty(caps) || gst_caps_is_any(caps)) return;
    GstVideoInfo info;
    if (!video_info_from_any_caps(caps, &info)) return;

    int32_t bit_depth = 0;
    if (info.finfo) bit_depth = GST_VIDEO_FORMAT_INFO_DEPTH(info.finfo, 0);
    const int32_t primaries = normalized_primaries(info.colorimetry.primaries);
    const int32_t transfer = normalized_transfer(info.colorimetry.transfer);
    const int32_t matrix = normalized_matrix(info.colorimetry.matrix);
    const int32_t range = normalized_range(info.colorimetry.range);

    pthread_mutex_lock(&p->output_lock);
    const int32_t authoritative_unknowns =
        (p->decoded_transfer > 0 && transfer == 0) ||
        (p->decoded_authoritative_unknowns && transfer == 0);
    if (p->decoded_bit_depth != bit_depth ||
        p->decoded_primaries != primaries ||
        p->decoded_transfer != transfer ||
        p->decoded_matrix != matrix ||
        p->decoded_range != range ||
        p->decoded_authoritative_unknowns != authoritative_unknowns) {
        p->decoded_bit_depth = bit_depth;
        p->decoded_primaries = primaries;
        p->decoded_transfer = transfer;
        p->decoded_matrix = matrix;
        p->decoded_range = range;
        p->decoded_authoritative_unknowns = authoritative_unknowns;
        p->decoded_color_generation =
            p->decoded_color_generation == INT32_MAX ? 1 : p->decoded_color_generation + 1;
    }
    pthread_mutex_unlock(&p->output_lock);
}

static GstPadProbeReturn on_decoder_caps_event(GstPad* pad, GstPadProbeInfo* info, gpointer data) {
    (void)pad;
    VideoPlayer* p = (VideoPlayer*)data;
    if (!p || !(GST_PAD_PROBE_INFO_TYPE(info) & GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM)) {
        return GST_PAD_PROBE_OK;
    }
    GstEvent* event = GST_PAD_PROBE_INFO_EVENT(info);
    if (event && GST_EVENT_TYPE(event) == GST_EVENT_CAPS) {
        GstCaps* caps = NULL;
        gst_event_parse_caps(event, &caps);
        update_decoded_color_info(p, caps);
    }
    return GST_PAD_PROBE_OK;
}

static void update_wayland_output_status(VideoPlayer* p, GstCaps* caps) {
    if (!p) return;

    update_decoded_color_info(p, caps);

    int32_t negotiated = 0;
    if (caps && !gst_caps_is_empty(caps) && !gst_caps_is_any(caps)) {
        negotiated |= NVP_WAYLAND_OUTPUT_CAPS_NEGOTIATED;
        const GstStructure* structure = gst_caps_get_structure(caps, 0);
        const gchar* format = gst_structure_get_string(structure, "format");
        const gchar* drm_format = gst_structure_get_string(structure, "drm-format");
        if (is_ten_bit_format_name(format) || is_ten_bit_format_name(drm_format)) {
            negotiated |= NVP_WAYLAND_OUTPUT_TEN_BIT;
        }

        const GstCapsFeatures* features = gst_caps_get_features(caps, 0);
        if (features && gst_caps_features_contains(features, "memory:DMABuf")) {
            negotiated |= NVP_WAYLAND_OUTPUT_DMABUF;
        }

        GstVideoInfo info;
        gst_video_info_init(&info);
        if (gst_video_info_from_caps(&info, caps)) {
            if (info.colorimetry.transfer == GST_VIDEO_TRANSFER_SMPTE2084) {
                negotiated |= NVP_WAYLAND_OUTPUT_PQ;
            } else if (info.colorimetry.transfer == GST_VIDEO_TRANSFER_ARIB_STD_B67) {
                negotiated |= NVP_WAYLAND_OUTPUT_HLG;
            }
            if (info.finfo && GST_VIDEO_FORMAT_INFO_DEPTH(info.finfo, 0) >= 10) {
                negotiated |= NVP_WAYLAND_OUTPUT_TEN_BIT;
            }

            pthread_mutex_lock(&p->frame_lock);
            if (info.width > 0 && info.height > 0) {
                p->frame_width = info.width;
                p->frame_height = info.height;
            }
            pthread_mutex_unlock(&p->frame_lock);
            if (info.fps_d > 0) p->frame_rate = (float)info.fps_n / (float)info.fps_d;
        }
    }

    pthread_mutex_lock(&p->output_lock);
    int32_t persistent = p->wayland_output_state &
        (NVP_WAYLAND_OUTPUT_ATTACHED | NVP_WAYLAND_OUTPUT_ERROR | NVP_WAYLAND_OUTPUT_FIRST_FRAME);
    p->wayland_output_state = persistent | negotiated;
    pthread_mutex_unlock(&p->output_lock);
}

static GstPadProbeReturn on_wayland_sink_buffer(GstPad* pad, GstPadProbeInfo* info, gpointer data) {
    VideoPlayer* p = (VideoPlayer*)data;
    if (!p || !(GST_PAD_PROBE_INFO_TYPE(info) & GST_PAD_PROBE_TYPE_BUFFER)) return GST_PAD_PROBE_OK;

    GstCaps* caps = gst_pad_get_current_caps(pad);
    update_wayland_output_status(p, caps);
    if (caps) gst_caps_unref(caps);

    pthread_mutex_lock(&p->output_lock);
    if (p->wayland_sink) p->wayland_output_state |= NVP_WAYLAND_OUTPUT_FIRST_FRAME;
    pthread_mutex_unlock(&p->output_lock);
    return GST_PAD_PROBE_OK;
}

static void snapshot_pipeline_state(VideoPlayer* p, GstState* target_state, gint64* position) {
    GstState current = GST_STATE_NULL;
    GstState pending = GST_STATE_VOID_PENDING;
    gst_element_get_state(p->pipeline, &current, &pending, 0);
    *target_state = pending != GST_STATE_VOID_PENDING ? pending : current;
    *position = 0;
    gst_element_query_position(p->pipeline, GST_FORMAT_TIME, position);
}

static void restore_pipeline_state(VideoPlayer* p, GstState target_state, gint64 position) {
    gst_element_set_state(p->pipeline, target_state);
    if (position > 0) {
        gst_element_seek_simple(
            p->pipeline,
            GST_FORMAT_TIME,
            GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT,
            position
        );
    }
}

int32_t nvp_attach_wayland_output(
    VideoPlayer* p,
    uintptr_t display,
    uintptr_t parent_surface,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height
) {
    if (!p || !display || !parent_surface || width <= 0 || height <= 0) return 0;

    guint major = 0;
    guint minor = 0;
    guint micro = 0;
    guint nano = 0;
    gst_version(&major, &minor, &micro, &nano);
    if (major < 1 || (major == 1 && (minor < 28 || (minor == 28 && micro < 5)))) return 0;

    pthread_mutex_lock(&p->output_lock);
    GstElement* existing_sink = p->wayland_sink;
    LinuxVulkanProjection* existing_projection_renderer = p->projection_renderer;
    uintptr_t existing_display = p->wayland_display;
    uintptr_t existing_surface = p->wayland_parent_surface;
    pthread_mutex_unlock(&p->output_lock);

    if (existing_sink && existing_display == display && existing_surface == parent_surface) {
        gboolean updated = gst_video_overlay_set_render_rectangle(
            GST_VIDEO_OVERLAY(existing_sink), x, y, width, height);
        if (updated) gst_video_overlay_expose(GST_VIDEO_OVERLAY(existing_sink));
        pthread_mutex_lock(&p->output_lock);
        p->wayland_x = x;
        p->wayland_y = y;
        p->wayland_width = width;
        p->wayland_height = height;
        p->wayland_output_state = NVP_WAYLAND_OUTPUT_ATTACHED;
        pthread_mutex_unlock(&p->output_lock);
        return updated ? 1 : 0;
    }

    if (existing_sink || existing_projection_renderer) nvp_detach_wayland_output(p);

    GstElement* sink = gst_element_factory_make("waylandsink", "compose-media-player-wayland-hdr-sink");
    if (!sink) return 0;
    gst_object_ref_sink(sink);

    if (g_object_class_find_property(G_OBJECT_GET_CLASS(sink), "force-aspect-ratio")) {
        g_object_set(sink, "force-aspect-ratio", TRUE, NULL);
    }
    g_object_set(sink, "sync", TRUE, NULL);

    GstContext* context = gst_context_new("GstWaylandDisplayHandleContextType", TRUE);
    GstStructure* context_structure = gst_context_writable_structure(context);
    gst_structure_set(context_structure, "display", G_TYPE_POINTER, (gpointer)display, NULL);
    gst_element_set_context(sink, context);
    gst_context_unref(context);

    gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(sink), (guintptr)parent_surface);
    if (!gst_video_overlay_set_render_rectangle(GST_VIDEO_OVERLAY(sink), x, y, width, height)) {
        gst_object_unref(sink);
        return 0;
    }
    gst_video_overlay_handle_events(GST_VIDEO_OVERLAY(sink), FALSE);

    GstPad* sink_pad = gst_element_get_static_pad(sink, "sink");
    gulong probe_id = 0;
    if (sink_pad) {
        probe_id = gst_pad_add_probe(
            sink_pad,
            GST_PAD_PROBE_TYPE_BUFFER,
            on_wayland_sink_buffer,
            p,
            NULL
        );
        gst_object_unref(sink_pad);
    }

    GstState target_state;
    gint64 position;
    snapshot_pipeline_state(p, &target_state, &position);
    gst_element_set_state(p->pipeline, GST_STATE_NULL);
    g_object_set(p->pipeline, "video-sink", sink, NULL);

    pthread_mutex_lock(&p->frame_lock);
    free(p->frame_buffer);
    p->frame_buffer = NULL;
    p->frame_size = 0;
    pthread_mutex_unlock(&p->frame_lock);

    pthread_mutex_lock(&p->output_lock);
    p->wayland_sink = sink;
    p->wayland_display = display;
    p->wayland_parent_surface = parent_surface;
    p->wayland_x = x;
    p->wayland_y = y;
    p->wayland_width = width;
    p->wayland_height = height;
    p->wayland_probe_id = probe_id;
    p->wayland_output_state = NVP_WAYLAND_OUTPUT_ATTACHED;
    pthread_mutex_unlock(&p->output_lock);

    restore_pipeline_state(p, target_state, position);
    return 1;
}

int32_t nvp_attach_wayland_projection_output(
    VideoPlayer* p,
    uintptr_t display,
    uintptr_t parent_surface,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    const LinuxVulkanProjectionConfiguration* configuration
) {
    if (!p || !display || !parent_surface || width <= 0 || height <= 0 || !configuration) return 0;

    pthread_mutex_lock(&p->output_lock);
    LinuxVulkanProjection* existing = p->projection_renderer;
    GstElement* existing_sink = p->wayland_sink;
    uintptr_t existing_display = p->wayland_display;
    uintptr_t existing_surface = p->wayland_parent_surface;
    pthread_mutex_unlock(&p->output_lock);
    if (existing && existing_display == display && existing_surface == parent_surface) {
        linux_vulkan_projection_update_configuration(existing, configuration);
        int updated = linux_vulkan_projection_update_geometry(existing, x, y, width, height);
        pthread_mutex_lock(&p->output_lock);
        p->wayland_x = x;
        p->wayland_y = y;
        p->wayland_width = width;
        p->wayland_height = height;
        p->wayland_output_state = NVP_WAYLAND_OUTPUT_ATTACHED;
        pthread_mutex_unlock(&p->output_lock);
        return updated;
    }
    if (existing || existing_sink) nvp_detach_wayland_output(p);

    GstState target_state;
    gint64 position;
    snapshot_pipeline_state(p, &target_state, &position);
    gst_element_set_state(p->pipeline, GST_STATE_NULL);

    LinuxVulkanProjection* renderer = linux_vulkan_projection_create(
        display,
        parent_surface,
        x,
        y,
        width,
        height,
        configuration
    );
    if (!renderer) {
        restore_pipeline_state(p, target_state, position);
        return 0;
    }

    GstCaps* caps = gst_caps_from_string(
        "video/x-raw(memory:DMABuf),format=(string)DMA_DRM,drm-format=(string)P010;"
        "video/x-raw,format=(string)P010_10LE"
    );
    gst_app_sink_set_caps(GST_APP_SINK(p->video_sink), caps);
    gst_caps_unref(caps);
    g_object_set(p->pipeline, "video-sink", p->memory_video_bin, NULL);

    pthread_mutex_lock(&p->frame_lock);
    free(p->frame_buffer);
    p->frame_buffer = NULL;
    p->frame_size = 0;
    pthread_mutex_unlock(&p->frame_lock);

    pthread_mutex_lock(&p->output_lock);
    p->projection_renderer = renderer;
    p->wayland_display = display;
    p->wayland_parent_surface = parent_surface;
    p->wayland_x = x;
    p->wayland_y = y;
    p->wayland_width = width;
    p->wayland_height = height;
    p->wayland_output_state = NVP_WAYLAND_OUTPUT_ATTACHED;
    pthread_mutex_unlock(&p->output_lock);
    restore_pipeline_state(p, target_state, position);
    return 1;
}

void nvp_update_wayland_projection_configuration(
    VideoPlayer* p,
    const LinuxVulkanProjectionConfiguration* configuration
) {
    if (!p || !configuration) return;
    pthread_mutex_lock(&p->output_lock);
    LinuxVulkanProjection* renderer = p->projection_renderer;
    pthread_mutex_unlock(&p->output_lock);
    if (renderer) linux_vulkan_projection_update_configuration(renderer, configuration);
}

void nvp_detach_wayland_output(VideoPlayer* p) {
    if (!p) return;

    pthread_mutex_lock(&p->output_lock);
    GstElement* sink = p->wayland_sink;
    LinuxVulkanProjection* projection_renderer = p->projection_renderer;
    gulong probe_id = p->wayland_probe_id;
    pthread_mutex_unlock(&p->output_lock);
    if (!sink && !projection_renderer) return;

    GstState target_state;
    gint64 position;
    snapshot_pipeline_state(p, &target_state, &position);
    gst_element_set_state(p->pipeline, GST_STATE_NULL);

    GstPad* sink_pad = sink ? gst_element_get_static_pad(sink, "sink") : NULL;
    if (sink_pad && probe_id != 0) gst_pad_remove_probe(sink_pad, probe_id);
    if (sink_pad) gst_object_unref(sink_pad);
    g_object_set(p->pipeline, "video-sink", p->memory_video_bin, NULL);
    GstCaps* memory_caps = gst_caps_new_simple(
        "video/x-raw",
        "format", G_TYPE_STRING, "BGRA",
        NULL
    );
    gst_app_sink_set_caps(GST_APP_SINK(p->video_sink), memory_caps);
    gst_caps_unref(memory_caps);

    pthread_mutex_lock(&p->output_lock);
    p->wayland_sink = NULL;
    p->projection_renderer = NULL;
    p->wayland_display = 0;
    p->wayland_parent_surface = 0;
    p->wayland_probe_id = 0;
    p->wayland_output_state = 0;
    pthread_mutex_unlock(&p->output_lock);

    if (sink) gst_object_unref(sink);
    if (projection_renderer) linux_vulkan_projection_destroy(projection_renderer);
    restore_pipeline_state(p, target_state, position);
}

int32_t nvp_get_wayland_output_state(VideoPlayer* p) {
    if (!p) return 0;

    pthread_mutex_lock(&p->output_lock);
    GstElement* sink = p->wayland_sink;
    LinuxVulkanProjection* projection_renderer = p->projection_renderer;
    pthread_mutex_unlock(&p->output_lock);
    if (projection_renderer) return linux_vulkan_projection_get_state(projection_renderer);
    if (sink) {
        GstPad* pad = gst_element_get_static_pad(sink, "sink");
        GstCaps* caps = pad ? gst_pad_get_current_caps(pad) : NULL;
        update_wayland_output_status(p, caps);
        if (caps) gst_caps_unref(caps);
        if (pad) gst_object_unref(pad);
    }

    pthread_mutex_lock(&p->output_lock);
    int32_t state = p->wayland_output_state;
    pthread_mutex_unlock(&p->output_lock);
    return state;
}

void nvp_get_decoded_color_info(VideoPlayer* p, int32_t out_info[7]) {
    if (!out_info) return;
    memset(out_info, 0, sizeof(int32_t) * 7);
    if (!p) return;
    pthread_mutex_lock(&p->output_lock);
    out_info[0] = p->decoded_color_generation;
    out_info[1] = p->decoded_bit_depth;
    out_info[2] = p->decoded_primaries;
    out_info[3] = p->decoded_transfer;
    out_info[4] = p->decoded_matrix;
    out_info[5] = p->decoded_range;
    out_info[6] = p->decoded_authoritative_unknowns;
    pthread_mutex_unlock(&p->output_lock);
}

// ---------------------------------------------------------------------------
// Playback control
// ---------------------------------------------------------------------------

int nvp_open_uri(VideoPlayer* p, const char* uri) {
    return nvp_open_uri_with_headers(p, uri, NULL);
}

int nvp_open_uri_with_headers(VideoPlayer* p, const char* uri, const char* request_headers) {
    if (!p || !uri) return 0;

    // Reset state
    gst_element_set_state(p->pipeline, GST_STATE_NULL);
    p->did_play_to_end = 0;
    pthread_mutex_lock(&p->output_lock);
    p->wayland_output_state =
        (p->wayland_sink || p->projection_renderer) ? NVP_WAYLAND_OUTPUT_ATTACHED : 0;
    p->decoded_bit_depth = 0;
    p->decoded_primaries = 0;
    p->decoded_transfer = 0;
    p->decoded_matrix = 0;
    p->decoded_range = 0;
    p->decoded_authoritative_unknowns = 0;
    p->decoded_color_generation =
        p->decoded_color_generation == INT32_MAX ? 1 : p->decoded_color_generation + 1;
    pthread_mutex_unlock(&p->output_lock);

    pthread_mutex_lock(&p->headers_lock);
    g_free(p->request_headers);
    p->request_headers = request_headers && request_headers[0] ? g_strdup(request_headers) : NULL;
    pthread_mutex_unlock(&p->headers_lock);

    // Clear old frame
    pthread_mutex_lock(&p->frame_lock);
    free(p->frame_buffer);
    p->frame_buffer = NULL;
    p->frame_width = 0;
    p->frame_height = 0;
    p->frame_size = 0;
    pthread_mutex_unlock(&p->frame_lock);

    // Clear metadata
    pthread_mutex_lock(&p->meta_lock);
    free(p->title);   p->title = NULL;
    free(p->mime_type); p->mime_type = NULL;
    free(p->video_decoder_name); p->video_decoder_name = NULL;
    p->bitrate = 0;
    p->audio_channels = 0;
    p->audio_sample_rate = 0;
    p->frame_rate = 0.0f;
    pthread_mutex_unlock(&p->meta_lock);

    // Convert raw file paths to file:// URIs if needed.
    // GStreamer playbin requires a valid URI scheme.
    gchar* resolved_uri = NULL;
    if (g_str_has_prefix(uri, "http://") || g_str_has_prefix(uri, "https://") ||
        g_str_has_prefix(uri, "rtsp://") || g_str_has_prefix(uri, "file://")) {
        resolved_uri = g_strdup(uri);
    } else {
        // Treat as a local file path — convert to file:// URI
        GError* err = NULL;
        resolved_uri = gst_filename_to_uri(uri, &err);
        if (!resolved_uri) {
            if (err) {
                g_printerr("Failed to convert path to URI: %s\n", err->message);
                g_error_free(err);
            }
            return 0;
        }
    }

    g_object_set(p->pipeline, "uri", resolved_uri, NULL);
    g_free(resolved_uri);
    g_object_set(p->pipeline, "volume", (gdouble)p->volume, NULL);

    // Pause to preroll (caller will call play() when ready)
    GstStateChangeReturn ret = gst_element_set_state(p->pipeline, GST_STATE_PAUSED);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        return 0;
    }

    return 1;
}

void nvp_play(VideoPlayer* p) {
    if (!p) return;
    g_object_set(p->pipeline, "volume", (gdouble)p->volume, NULL);
    gst_element_set_state(p->pipeline, GST_STATE_PLAYING);
}

void nvp_pause(VideoPlayer* p) {
    if (!p) return;
    gst_element_set_state(p->pipeline, GST_STATE_PAUSED);
}

void nvp_set_volume(VideoPlayer* p, float volume) {
    if (!p) return;
    p->volume = volume;
    g_object_set(p->pipeline, "volume", (gdouble)volume, NULL);
}

float nvp_get_volume(VideoPlayer* p) {
    return p ? p->volume : 0.0f;
}

void nvp_seek_to(VideoPlayer* p, double time_seconds) {
    if (!p) return;
    gint64 pos = (gint64)(time_seconds * GST_SECOND);
    gst_element_seek(p->pipeline,
        (gdouble)p->playback_speed,
        GST_FORMAT_TIME,
        GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
        GST_SEEK_TYPE_SET, pos,
        GST_SEEK_TYPE_NONE, -1);
}

void nvp_set_playback_speed(VideoPlayer* p, float speed) {
    if (!p) return;
    p->playback_speed = speed;

    gint64 pos = 0;
    if (gst_element_query_position(p->pipeline, GST_FORMAT_TIME, &pos)) {
        gst_element_seek(p->pipeline,
            (gdouble)speed,
            GST_FORMAT_TIME,
            GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
            GST_SEEK_TYPE_SET, pos,
            GST_SEEK_TYPE_NONE, -1);
    }
}

float nvp_get_playback_speed(VideoPlayer* p) {
    return p ? p->playback_speed : 1.0f;
}

// ---------------------------------------------------------------------------
// Frame access
// ---------------------------------------------------------------------------

void* nvp_lock_latest_frame(VideoPlayer* p, int32_t out_info[3]) {
    if (!p || !out_info) return NULL;

    pthread_mutex_lock(&p->frame_lock);
    if (!p->frame_buffer || p->frame_width <= 0 || p->frame_height <= 0 || p->frame_size == 0) {
        pthread_mutex_unlock(&p->frame_lock);
        return NULL;
    }

    out_info[0] = p->frame_width;
    out_info[1] = p->frame_height;
    out_info[2] = p->frame_width * 4;
    return p->frame_buffer;
}

void nvp_unlock_latest_frame(VideoPlayer* p) {
    if (!p) return;
    pthread_mutex_unlock(&p->frame_lock);
}

int32_t nvp_get_frame_width(VideoPlayer* p) {
    return p ? p->frame_width : 0;
}

int32_t nvp_get_frame_height(VideoPlayer* p) {
    return p ? p->frame_height : 0;
}

int32_t nvp_set_output_size(VideoPlayer* p, int32_t width, int32_t height) {
    if (!p || width <= 0 || height <= 0) return 0;
    p->output_width = width;
    p->output_height = height;

    GstCaps* caps = gst_caps_new_simple("video/x-raw",
        "format", G_TYPE_STRING, "BGRA",
        "width", G_TYPE_INT, (gint)width,
        "height", G_TYPE_INT, (gint)height,
        NULL);
    gst_app_sink_set_caps(GST_APP_SINK(p->video_sink), caps);
    gst_caps_unref(caps);
    return 1;
}

// ---------------------------------------------------------------------------
// Timing
// ---------------------------------------------------------------------------

double nvp_get_duration(VideoPlayer* p) {
    if (!p) return 0.0;
    gint64 dur = 0;
    if (gst_element_query_duration(p->pipeline, GST_FORMAT_TIME, &dur) && dur > 0) {
        return (double)dur / (double)GST_SECOND;
    }
    return 0.0;
}

double nvp_get_current_time(VideoPlayer* p) {
    if (!p) return 0.0;
    gint64 pos = 0;
    if (gst_element_query_position(p->pipeline, GST_FORMAT_TIME, &pos) && pos >= 0) {
        return (double)pos / (double)GST_SECOND;
    }
    return 0.0;
}

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------

char* nvp_get_title(VideoPlayer* p) {
    if (!p) return NULL;
    pthread_mutex_lock(&p->meta_lock);
    char* result = duplicate_string(p->title);
    pthread_mutex_unlock(&p->meta_lock);
    return result;
}

int64_t nvp_get_bitrate(VideoPlayer* p) {
    return p ? p->bitrate : 0;
}

char* nvp_get_mime_type(VideoPlayer* p) {
    if (!p) return NULL;
    pthread_mutex_lock(&p->meta_lock);
    char* result = duplicate_string(p->mime_type);
    pthread_mutex_unlock(&p->meta_lock);
    return result;
}

char* nvp_get_video_decoder_name(VideoPlayer* p) {
    if (!p) return NULL;
    pthread_mutex_lock(&p->meta_lock);
    char* result = duplicate_string(p->video_decoder_name);
    pthread_mutex_unlock(&p->meta_lock);
    return result;
}

int32_t nvp_get_audio_channels(VideoPlayer* p) {
    return p ? p->audio_channels : 0;
}

int32_t nvp_get_audio_sample_rate(VideoPlayer* p) {
    return p ? p->audio_sample_rate : 0;
}

float nvp_get_frame_rate(VideoPlayer* p) {
    return p ? p->frame_rate : 0.0f;
}

// ---------------------------------------------------------------------------
// End-of-stream
// ---------------------------------------------------------------------------

int32_t nvp_consume_did_play_to_end(VideoPlayer* p) {
    if (!p) return 0;
    int val = __sync_lock_test_and_set(&p->did_play_to_end, 0);
    return val;
}

// ---------------------------------------------------------------------------
// Internal: new-sample callback (called from GStreamer streaming thread)
// ---------------------------------------------------------------------------

static GstFlowReturn on_new_sample(GstAppSink* sink, gpointer data) {
    VideoPlayer* p = (VideoPlayer*)data;

    GstSample* sample = gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_OK;

    GstCaps* caps = gst_sample_get_caps(sample);
    if (!caps) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    GstStructure* s = gst_caps_get_structure(caps, 0);
    gint width = 0, height = 0;
    gst_structure_get_int(s, "width", &width);
    gst_structure_get_int(s, "height", &height);

    if (width <= 0 || height <= 0) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    pthread_mutex_lock(&p->output_lock);
    LinuxVulkanProjection* projection_renderer = p->projection_renderer;
    pthread_mutex_unlock(&p->output_lock);
    if (projection_renderer) {
        GstVideoInfo video_info;
        gst_video_info_init(&video_info);
        GstBuffer* projection_buffer = gst_sample_get_buffer(sample);
        size_t hdr10_plus_payload_size = 0;
        const uint8_t* hdr10_plus_payload =
            hdr10_plus_payload_from_buffer(projection_buffer, &hdr10_plus_payload_size);
        if (!linux_vulkan_projection_update_hdr10_plus_metadata(
                projection_renderer,
                hdr10_plus_payload,
                hdr10_plus_payload_size)) {
            gst_sample_unref(sample);
            return GST_FLOW_OK;
        }
        int input_is_dmabuf = gst_video_is_dma_drm_caps(caps);
        int video_info_valid = 0;
        if (input_is_dmabuf && projection_buffer) {
            GstVideoInfoDmaDrm dma_info;
            gst_video_info_dma_drm_init(&dma_info);
            video_info_valid =
                gst_video_info_dma_drm_from_caps(&dma_info, caps) &&
                dma_info.drm_fourcc == NVP_DRM_FORMAT_P010 &&
                dma_info.drm_modifier == 0 &&
                gst_video_info_dma_drm_to_video_info(&dma_info, &video_info);
            if (video_info_valid) {
                const guint memory_count = gst_buffer_n_memory(projection_buffer);
                for (guint memory_index = 0; memory_index < memory_count; memory_index++) {
                    GstMemory* memory = gst_buffer_peek_memory(projection_buffer, memory_index);
                    if (!memory || !gst_is_dmabuf_memory(memory)) {
                        video_info_valid = 0;
                        break;
                    }
                }
            }
        } else if (projection_buffer) {
            video_info_valid = gst_video_info_from_caps(&video_info, caps);
        }
        if (video_info_valid) update_decoded_color_info(p, caps);
        GstVideoFrame frame;
        if (!projection_buffer ||
            !video_info_valid ||
            GST_VIDEO_INFO_FORMAT(&video_info) != GST_VIDEO_FORMAT_P010_10LE ||
            !gst_video_frame_map(&frame, &video_info, projection_buffer, GST_MAP_READ)) {
            pthread_mutex_lock(&p->output_lock);
            p->wayland_output_state |= NVP_WAYLAND_OUTPUT_ERROR;
            pthread_mutex_unlock(&p->output_lock);
            gst_sample_unref(sample);
            return GST_FLOW_ERROR;
        }
        int32_t input_transfer = -1;
        if (video_info.colorimetry.transfer == GST_VIDEO_TRANSFER_SMPTE2084) {
            input_transfer = 0;
        } else if (video_info.colorimetry.transfer == GST_VIDEO_TRANSFER_ARIB_STD_B67) {
            input_transfer = 1;
        }
        int rendered = linux_vulkan_projection_render_p010(
            projection_renderer,
            GST_VIDEO_FRAME_PLANE_DATA(&frame, 0),
            GST_VIDEO_FRAME_PLANE_STRIDE(&frame, 0),
            GST_VIDEO_FRAME_PLANE_DATA(&frame, 1),
            GST_VIDEO_FRAME_PLANE_STRIDE(&frame, 1),
            width,
            height,
            input_transfer,
            input_is_dmabuf
        );
        gst_video_frame_unmap(&frame);
        if (rendered) {
            pthread_mutex_lock(&p->frame_lock);
            p->frame_width = width;
            p->frame_height = height;
            pthread_mutex_unlock(&p->frame_lock);
        } else {
            pthread_mutex_lock(&p->output_lock);
            p->wayland_output_state |= NVP_WAYLAND_OUTPUT_ERROR;
            pthread_mutex_unlock(&p->output_lock);
        }
        gst_sample_unref(sample);
        return rendered ? GST_FLOW_OK : GST_FLOW_ERROR;
    }

    // Extract frame rate from caps
    gint fps_n = 0, fps_d = 1;
    if (gst_structure_get_fraction(s, "framerate", &fps_n, &fps_d) && fps_d > 0) {
        p->frame_rate = (float)fps_n / (float)fps_d;
    }

    GstBuffer* buffer = gst_sample_get_buffer(sample);
    if (!buffer) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    GstMapInfo map;
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    size_t expected = (size_t)width * (size_t)height * 4;
    if (map.size < expected) {
        gst_buffer_unmap(buffer, &map);
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    pthread_mutex_lock(&p->frame_lock);

    if (p->frame_width != width || p->frame_height != height || !p->frame_buffer) {
        free(p->frame_buffer);
        p->frame_buffer = (uint8_t*)malloc(expected);
        if (!p->frame_buffer) {
            p->frame_width = 0;
            p->frame_height = 0;
            p->frame_size = 0;
            pthread_mutex_unlock(&p->frame_lock);
            gst_buffer_unmap(buffer, &map);
            gst_sample_unref(sample);
            return GST_FLOW_OK;
        }
        p->frame_width = width;
        p->frame_height = height;
        p->frame_size = expected;
    }

    memcpy(p->frame_buffer, map.data, expected);

    pthread_mutex_unlock(&p->frame_lock);

    gst_buffer_unmap(buffer, &map);
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

// ---------------------------------------------------------------------------
// Internal: metadata extraction from tags
// ---------------------------------------------------------------------------

static void update_metadata_from_tags(VideoPlayer* p, GstTagList* tags) {
    gchar* str = NULL;

    pthread_mutex_lock(&p->meta_lock);

    if (gst_tag_list_get_string(tags, GST_TAG_TITLE, &str)) {
        free(p->title);
        p->title = duplicate_string(str);
        g_free(str);
    }

    guint bitrate = 0;
    if (gst_tag_list_get_uint(tags, GST_TAG_BITRATE, &bitrate) ||
        gst_tag_list_get_uint(tags, GST_TAG_NOMINAL_BITRATE, &bitrate)) {
        p->bitrate = (int64_t)bitrate;
    }

    str = NULL;
    if (gst_tag_list_get_string(tags, GST_TAG_CONTAINER_FORMAT, &str)) {
        free(p->mime_type);
        p->mime_type = duplicate_string(str);
        g_free(str);
    } else if (gst_tag_list_get_string(tags, GST_TAG_AUDIO_CODEC, &str)) {
        if (!p->mime_type) {
            p->mime_type = duplicate_string(str);
        }
        g_free(str);
    } else if (gst_tag_list_get_string(tags, GST_TAG_VIDEO_CODEC, &str)) {
        if (!p->mime_type) {
            p->mime_type = duplicate_string(str);
        }
        g_free(str);
    }

    pthread_mutex_unlock(&p->meta_lock);
}

// ---------------------------------------------------------------------------
// Internal: stream metadata from pads (channels, sample rate, resolution)
// ---------------------------------------------------------------------------

static void update_stream_metadata(VideoPlayer* p) {
    // Video info from the active output pad.
    pthread_mutex_lock(&p->output_lock);
    GstElement* active_video_sink = p->wayland_sink ? p->wayland_sink : p->video_sink;
    pthread_mutex_unlock(&p->output_lock);
    GstPad* vpad = gst_element_get_static_pad(active_video_sink, "sink");
    if (vpad) {
        GstCaps* vcaps = gst_pad_get_current_caps(vpad);
        if (vcaps && gst_caps_get_size(vcaps) > 0) {
            GstStructure* vs = gst_caps_get_structure(vcaps, 0);
            gint w = 0, h = 0;
            gst_structure_get_int(vs, "width", &w);
            gst_structure_get_int(vs, "height", &h);

            // Only update dimensions if not already set by frame callback
            if (w > 0 && h > 0 && (p->frame_width == 0 || p->frame_height == 0)) {
                pthread_mutex_lock(&p->frame_lock);
                if (p->frame_width == 0 || p->frame_height == 0) {
                    p->frame_width = w;
                    p->frame_height = h;
                }
                pthread_mutex_unlock(&p->frame_lock);
            }

            gint fps_n = 0, fps_d = 1;
            if (gst_structure_get_fraction(vs, "framerate", &fps_n, &fps_d) && fps_d > 0) {
                p->frame_rate = (float)fps_n / (float)fps_d;
            }
        }
        if (vcaps) gst_caps_unref(vcaps);
        gst_object_unref(vpad);
    }

    // Audio info from the level element's sink pad
    if (p->level) {
        GstPad* apad = gst_element_get_static_pad(p->level, "sink");
        if (apad) {
            GstCaps* acaps = gst_pad_get_current_caps(apad);
            if (acaps && gst_caps_get_size(acaps) > 0) {
                GstStructure* as_ = gst_caps_get_structure(acaps, 0);
                gint channels = 0, rate = 0;
                if (gst_structure_get_int(as_, "channels", &channels)) {
                    p->audio_channels = channels;
                }
                if (gst_structure_get_int(as_, "rate", &rate)) {
                    p->audio_sample_rate = rate;
                }
            }
            if (acaps) gst_caps_unref(acaps);
            gst_object_unref(apad);
        }
    }
}
