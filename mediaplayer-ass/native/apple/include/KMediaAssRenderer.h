#ifndef KMEDIA_ASS_RENDERER_H
#define KMEDIA_ASS_RENDERER_H

#include <stddef.h>
#include <stdint.h>
#include <CoreGraphics/CoreGraphics.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__GNUC__)
#define KMEDIA_ASS_EXPORT __attribute__((visibility("default")))
#else
#define KMEDIA_ASS_EXPORT
#endif

typedef struct KMediaAssRenderer KMediaAssRenderer;

typedef enum KMediaAssRenderStatus {
    KMEDIA_ASS_RENDER_ERROR = -1,
    KMEDIA_ASS_RENDER_EMPTY = 0,
    KMEDIA_ASS_RENDER_PIXELS = 1,
} KMediaAssRenderStatus;

/*
 * Borrowed premultiplied RGBA8 storage. The pointer remains valid until the
 * next call on this renderer or until the renderer is destroyed.
 */
typedef struct KMediaAssFrame {
    const uint8_t *pixels;
    size_t size;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    int32_t stride;
} KMediaAssFrame;

KMEDIA_ASS_EXPORT uint32_t kmedia_ass_library_version(void);
KMEDIA_ASS_EXPORT const char *kmedia_ass_shared_runtime_id(void);

KMEDIA_ASS_EXPORT KMediaAssRenderer *kmedia_ass_renderer_create(void);

KMEDIA_ASS_EXPORT void kmedia_ass_renderer_destroy(
    KMediaAssRenderer *renderer);

KMEDIA_ASS_EXPORT int kmedia_ass_renderer_add_font(
    KMediaAssRenderer *renderer,
    const char *name,
    const uint8_t *data,
    size_t size);

KMEDIA_ASS_EXPORT int kmedia_ass_renderer_set_track(
    KMediaAssRenderer *renderer,
    const uint8_t *data,
    size_t size);

KMEDIA_ASS_EXPORT KMediaAssRenderStatus kmedia_ass_renderer_render_rgba(
    KMediaAssRenderer *renderer,
    int32_t frame_width,
    int32_t frame_height,
    int64_t time_ms,
    KMediaAssFrame *frame);

/** Creates an owned CGImage copy of a non-empty rendered frame. */
KMEDIA_ASS_EXPORT CGImageRef kmedia_ass_frame_copy_cg_image(
    const KMediaAssFrame *frame);

/*
 * Blends the current subtitle image into a writable BGRA8 frame. `pixels_size`
 * is checked against row_bytes * frame_height before any write.
 */
KMEDIA_ASS_EXPORT int kmedia_ass_renderer_blend_bgra(
    KMediaAssRenderer *renderer,
    uint8_t *pixels,
    size_t pixels_size,
    int32_t row_bytes,
    int32_t frame_width,
    int32_t frame_height,
    int64_t time_ms);

#ifdef __cplusplus
}
#endif

#endif
