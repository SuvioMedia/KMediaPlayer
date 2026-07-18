#ifndef KMEDIA_ASS_RGBA_COMPOSITOR_H
#define KMEDIA_ASS_RGBA_COMPOSITOR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Independent from libass on purpose, so this code can be tested on a host. */
typedef struct AssRgbaImage {
    int32_t width;
    int32_t height;
    int32_t stride;
    const uint8_t *bitmap;
    uint32_t color_rgba;
    int32_t dst_x;
    int32_t dst_y;
} AssRgbaImage;

typedef struct AssRgbaBuffer {
    uint8_t *pixels;
    size_t capacity;
    size_t size;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    int32_t stride;
} AssRgbaBuffer;

typedef enum AssRgbaResult {
    ASS_RGBA_ERROR = -1,
    ASS_RGBA_EMPTY = 0,
    ASS_RGBA_PIXELS = 1,
} AssRgbaResult;

/*
 * Composites images in array order into tightly packed premultiplied RGBA8.
 * color_rgba uses libass' RRGGBBAA representation, whose AA is transparency.
 * The result is clipped to the frame, cropped to the union of visible images,
 * and limited to max_bytes. The buffer allocation is retained on empty/error.
 */
AssRgbaResult ass_rgba_composite(const AssRgbaImage *images,
                                 size_t image_count,
                                 int32_t frame_width,
                                 int32_t frame_height,
                                 size_t max_bytes,
                                 AssRgbaBuffer *buffer);

void ass_rgba_buffer_release(AssRgbaBuffer *buffer);

#ifdef __cplusplus
}
#endif

#endif
