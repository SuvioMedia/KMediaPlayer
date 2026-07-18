#include "KMediaAssRenderer.h"

#include "AssRgbaCompositor.h"

#include <ass/ass.h>
#include <CoreFoundation/CoreFoundation.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REQUIRED_LIBASS_VERSION 0x01705000U
#define MAX_RGBA_FRAME_BYTES (64U * 1024U * 1024U)
#define MAX_INPUT_BYTES (64U * 1024U * 1024U)
#define MAX_ASS_IMAGES 65536U
#define MAX_FRAME_DIMENSION 32768
#define MAX_FONT_NAME_BYTES 4096U
#define MAX_GLYPH_CACHE 1000000
#define MAX_BITMAP_CACHE_MIB 64

struct KMediaAssRenderer {
    pthread_mutex_t mutex;
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int32_t frame_width;
    int32_t frame_height;
    AssRgbaBuffer rgba;
    AssRgbaImage *image_views;
    size_t image_view_capacity;
};

static void libass_log_callback(int level, const char *format,
                                va_list arguments, void *opaque)
{
    (void) opaque;
    if (level <= 1)
        vfprintf(stderr, format, arguments);
}

static void clear_frame(KMediaAssFrame *frame)
{
    if (frame)
        memset(frame, 0, sizeof(*frame));
}

static void destroy_resources(KMediaAssRenderer *renderer)
{
    if (renderer->track)
        ass_free_track(renderer->track);
    if (renderer->renderer)
        ass_renderer_done(renderer->renderer);
    if (renderer->library)
        ass_library_done(renderer->library);
    free(renderer->image_views);
    ass_rgba_buffer_release(&renderer->rgba);
}

static int ensure_image_view_capacity(KMediaAssRenderer *renderer,
                                      size_t count)
{
    if (count <= renderer->image_view_capacity)
        return 1;
    if (count > MAX_ASS_IMAGES ||
        count > SIZE_MAX / sizeof(*renderer->image_views))
        return 0;

    AssRgbaImage *views = (AssRgbaImage *) realloc(
        renderer->image_views, count * sizeof(*views));
    if (!views)
        return 0;
    renderer->image_views = views;
    renderer->image_view_capacity = count;
    return 1;
}

static int copy_image_views(KMediaAssRenderer *renderer,
                            const ASS_Image *head,
                            size_t *count_out)
{
    size_t count = 0;
    for (const ASS_Image *image = head; image; image = image->next) {
        if (count == MAX_ASS_IMAGES)
            return 0;
        ++count;
    }
    if (!ensure_image_view_capacity(renderer, count))
        return 0;

    size_t index = 0;
    for (const ASS_Image *image = head; image; image = image->next) {
        AssRgbaImage *view = &renderer->image_views[index++];
        view->width = image->w;
        view->height = image->h;
        view->stride = image->stride;
        view->bitmap = image->bitmap;
        view->color_rgba = image->color;
        view->dst_x = image->dst_x;
        view->dst_y = image->dst_y;
    }
    *count_out = count;
    return 1;
}

static KMediaAssRenderStatus render_locked(KMediaAssRenderer *renderer,
                                            int32_t frame_width,
                                            int32_t frame_height,
                                            int64_t time_ms,
                                            KMediaAssFrame *frame)
{
    clear_frame(frame);
    if (!renderer->track || !frame ||
        frame_width <= 0 || frame_height <= 0 ||
        frame_width > MAX_FRAME_DIMENSION ||
        frame_height > MAX_FRAME_DIMENSION)
        return KMEDIA_ASS_RENDER_ERROR;

    if (renderer->frame_width != frame_width ||
        renderer->frame_height != frame_height) {
        ass_set_frame_size(renderer->renderer, frame_width, frame_height);
        ass_set_storage_size(renderer->renderer, frame_width, frame_height);
        renderer->frame_width = frame_width;
        renderer->frame_height = frame_height;
    }

    int changed = 0;
    ASS_Image *images = ass_render_frame(
        renderer->renderer, renderer->track, (long long) time_ms, &changed);
    (void) changed;

    size_t image_count = 0;
    if (!copy_image_views(renderer, images, &image_count))
        return KMEDIA_ASS_RENDER_ERROR;

    AssRgbaResult result = ass_rgba_composite(
        renderer->image_views,
        image_count,
        frame_width,
        frame_height,
        MAX_RGBA_FRAME_BYTES,
        &renderer->rgba);
    if (result == ASS_RGBA_ERROR)
        return KMEDIA_ASS_RENDER_ERROR;
    if (result == ASS_RGBA_EMPTY)
        return KMEDIA_ASS_RENDER_EMPTY;

    frame->pixels = renderer->rgba.pixels;
    frame->size = renderer->rgba.size;
    frame->x = renderer->rgba.x;
    frame->y = renderer->rgba.y;
    frame->width = renderer->rgba.width;
    frame->height = renderer->rgba.height;
    frame->stride = renderer->rgba.stride;
    return KMEDIA_ASS_RENDER_PIXELS;
}

uint32_t kmedia_ass_library_version(void)
{
    return (uint32_t) ass_library_version();
}

KMediaAssRenderer *kmedia_ass_renderer_create(void)
{
    if (kmedia_ass_library_version() < REQUIRED_LIBASS_VERSION)
        return NULL;

    KMediaAssRenderer *renderer =
        (KMediaAssRenderer *) calloc(1, sizeof(*renderer));
    if (!renderer)
        return NULL;
    if (pthread_mutex_init(&renderer->mutex, NULL) != 0) {
        free(renderer);
        return NULL;
    }

    renderer->library = ass_library_init();
    if (renderer->library)
        ass_set_message_cb(
            renderer->library, libass_log_callback, NULL);
    if (renderer->library)
        renderer->renderer = ass_renderer_init(renderer->library);
    if (renderer->library)
        renderer->track = ass_new_track(renderer->library);
    if (!renderer->library || !renderer->renderer || !renderer->track) {
        destroy_resources(renderer);
        pthread_mutex_destroy(&renderer->mutex);
        free(renderer);
        return NULL;
    }

    ass_set_shaper(renderer->renderer, ASS_SHAPING_COMPLEX);
    ass_set_cache_limits(
        renderer->renderer, MAX_GLYPH_CACHE, MAX_BITMAP_CACHE_MIB);
    ass_set_fonts(
        renderer->renderer,
        NULL,
        "Arial",
        ASS_FONTPROVIDER_AUTODETECT,
        NULL,
        1);
    return renderer;
}

void kmedia_ass_renderer_destroy(KMediaAssRenderer *renderer)
{
    if (!renderer)
        return;
    pthread_mutex_lock(&renderer->mutex);
    destroy_resources(renderer);
    pthread_mutex_unlock(&renderer->mutex);
    pthread_mutex_destroy(&renderer->mutex);
    free(renderer);
}

int kmedia_ass_renderer_add_font(KMediaAssRenderer *renderer,
                                 const char *name,
                                 const uint8_t *data,
                                 size_t size)
{
    if (!renderer || !name || !data || size == 0 ||
        size > MAX_INPUT_BYTES ||
        strnlen(name, MAX_FONT_NAME_BYTES + 1U) > MAX_FONT_NAME_BYTES)
        return 0;

    if (pthread_mutex_lock(&renderer->mutex) != 0)
        return 0;
    ass_add_font(renderer->library, name, (const char *) data, (int) size);
    ass_set_fonts(
        renderer->renderer,
        NULL,
        "Arial",
        ASS_FONTPROVIDER_AUTODETECT,
        NULL,
        0);
    pthread_mutex_unlock(&renderer->mutex);
    return 1;
}

int kmedia_ass_renderer_set_track(KMediaAssRenderer *renderer,
                                  const uint8_t *data,
                                  size_t size)
{
    if (!renderer || !data || size == 0 ||
        size > MAX_INPUT_BYTES || size > INT_MAX)
        return 0;

    char *mutable_data = (char *) malloc(size);
    if (!mutable_data)
        return 0;
    memcpy(mutable_data, data, size);

    if (pthread_mutex_lock(&renderer->mutex) != 0) {
        free(mutable_data);
        return 0;
    }
    ASS_Track *track = ass_new_track(renderer->library);
    if (track)
        ass_process_data(track, mutable_data, (int) size);
    free(mutable_data);
    if (!track) {
        pthread_mutex_unlock(&renderer->mutex);
        return 0;
    }

    ass_free_track(renderer->track);
    renderer->track = track;
    renderer->frame_width = 0;
    renderer->frame_height = 0;
    pthread_mutex_unlock(&renderer->mutex);
    return 1;
}

KMediaAssRenderStatus kmedia_ass_renderer_render_rgba(
    KMediaAssRenderer *renderer,
    int32_t frame_width,
    int32_t frame_height,
    int64_t time_ms,
    KMediaAssFrame *frame)
{
    clear_frame(frame);
    if (!renderer)
        return KMEDIA_ASS_RENDER_ERROR;
    if (pthread_mutex_lock(&renderer->mutex) != 0)
        return KMEDIA_ASS_RENDER_ERROR;
    KMediaAssRenderStatus status = render_locked(
        renderer, frame_width, frame_height, time_ms, frame);
    pthread_mutex_unlock(&renderer->mutex);
    return status;
}

CGImageRef kmedia_ass_frame_copy_cg_image(const KMediaAssFrame *frame)
{
    if (!frame || !frame->pixels || frame->size == 0 ||
        frame->width <= 0 || frame->height <= 0 ||
        frame->stride < frame->width * 4)
        return NULL;

    CFDataRef data = CFDataCreate(
        kCFAllocatorDefault, frame->pixels, (CFIndex) frame->size);
    if (!data)
        return NULL;
    CGDataProviderRef provider = CGDataProviderCreateWithCFData(data);
    CFRelease(data);
    if (!provider)
        return NULL;

    CGColorSpaceRef color_space =
        CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!color_space) {
        CGDataProviderRelease(provider);
        return NULL;
    }
    CGBitmapInfo bitmap_info =
        kCGBitmapByteOrder32Big | kCGImageAlphaPremultipliedLast;
    CGImageRef image = CGImageCreate(
        (size_t) frame->width,
        (size_t) frame->height,
        8,
        32,
        (size_t) frame->stride,
        color_space,
        bitmap_info,
        provider,
        NULL,
        false,
        kCGRenderingIntentDefault);
    CGColorSpaceRelease(color_space);
    CGDataProviderRelease(provider);
    return image;
}

static uint8_t blend_premultiplied(uint8_t source,
                                   uint8_t destination,
                                   uint32_t inverse_alpha)
{
    uint32_t value = (uint32_t) source * 255U +
                     (uint32_t) destination * inverse_alpha;
    return (uint8_t) ((value + 127U) / 255U);
}

int kmedia_ass_renderer_blend_bgra(KMediaAssRenderer *renderer,
                                   uint8_t *pixels,
                                   size_t pixels_size,
                                   int32_t row_bytes,
                                   int32_t frame_width,
                                   int32_t frame_height,
                                   int64_t time_ms)
{
    if (!renderer || !pixels || row_bytes <= 0 ||
        frame_width <= 0 || frame_height <= 0 ||
        frame_width > INT32_MAX / 4 ||
        row_bytes < frame_width * 4 ||
        (size_t) frame_height > SIZE_MAX / (size_t) row_bytes ||
        pixels_size < (size_t) frame_height * (size_t) row_bytes)
        return 0;

    if (pthread_mutex_lock(&renderer->mutex) != 0)
        return 0;
    KMediaAssFrame frame;
    KMediaAssRenderStatus status = render_locked(
        renderer, frame_width, frame_height, time_ms, &frame);
    if (status == KMEDIA_ASS_RENDER_PIXELS) {
        for (int32_t row = 0; row < frame.height; ++row) {
            const uint8_t *source =
                frame.pixels + (size_t) row * (size_t) frame.stride;
            uint8_t *destination =
                pixels +
                (size_t) (frame.y + row) * (size_t) row_bytes +
                (size_t) frame.x * 4U;
            for (int32_t column = 0; column < frame.width; ++column) {
                uint32_t inverse_alpha = 255U - source[3];
                destination[0] = blend_premultiplied(
                    source[2], destination[0], inverse_alpha);
                destination[1] = blend_premultiplied(
                    source[1], destination[1], inverse_alpha);
                destination[2] = blend_premultiplied(
                    source[0], destination[2], inverse_alpha);
                destination[3] = 255U;
                source += 4;
                destination += 4;
            }
        }
    }
    pthread_mutex_unlock(&renderer->mutex);
    return status != KMEDIA_ASS_RENDER_ERROR;
}
