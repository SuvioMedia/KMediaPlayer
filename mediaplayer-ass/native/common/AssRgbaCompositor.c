#include "AssRgbaCompositor.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>

static void clear_current_frame(AssRgbaBuffer *buffer)
{
    buffer->size = 0;
    buffer->x = 0;
    buffer->y = 0;
    buffer->width = 0;
    buffer->height = 0;
    buffer->stride = 0;
}

static uint32_t divide_by_255(uint32_t value)
{
    return (value + 127U) / 255U;
}

static uint8_t blend_component(uint8_t source,
                               uint8_t destination,
                               uint32_t source_alpha)
{
    uint32_t inverse_alpha = 255U - source_alpha;
    uint32_t value = (uint32_t) source * source_alpha +
                     (uint32_t) destination * inverse_alpha;
    return (uint8_t) divide_by_255(value);
}

static int image_is_valid(const AssRgbaImage *image)
{
    if (image->width < 0 || image->height < 0 || image->stride < 0)
        return 0;
    if (image->width == 0 || image->height == 0)
        return 1;
    if (!image->bitmap || image->stride < image->width)
        return 0;

    /* This also guarantees that every row offset used below fits size_t. */
    size_t stride = (size_t) image->stride;
    size_t last_row = (size_t) image->height - 1U;
    size_t width = (size_t) image->width;
    if (last_row != 0 && stride > (SIZE_MAX - width) / last_row)
        return 0;
    return 1;
}

AssRgbaResult ass_rgba_composite(const AssRgbaImage *images,
                                 size_t image_count,
                                 int32_t frame_width,
                                 int32_t frame_height,
                                 size_t max_bytes,
                                 AssRgbaBuffer *buffer)
{
    if (!buffer)
        return ASS_RGBA_ERROR;
    clear_current_frame(buffer);

    if ((!images && image_count != 0) || frame_width <= 0 ||
        frame_height <= 0 || max_bytes == 0)
        return ASS_RGBA_ERROR;

    int have_union = 0;
    int64_t union_left = 0;
    int64_t union_top = 0;
    int64_t union_right = 0;
    int64_t union_bottom = 0;

    for (size_t i = 0; i < image_count; ++i) {
        const AssRgbaImage *image = &images[i];
        if (!image_is_valid(image))
            return ASS_RGBA_ERROR;
        if (image->width == 0 || image->height == 0 ||
            (image->color_rgba & 0xFFU) == 0xFFU)
            continue;

        int64_t left = image->dst_x;
        int64_t top = image->dst_y;
        int64_t right = left + image->width;
        int64_t bottom = top + image->height;

        if (left < 0)
            left = 0;
        if (top < 0)
            top = 0;
        if (right > frame_width)
            right = frame_width;
        if (bottom > frame_height)
            bottom = frame_height;
        if (left >= right || top >= bottom)
            continue;

        if (!have_union) {
            union_left = left;
            union_top = top;
            union_right = right;
            union_bottom = bottom;
            have_union = 1;
        } else {
            if (left < union_left)
                union_left = left;
            if (top < union_top)
                union_top = top;
            if (right > union_right)
                union_right = right;
            if (bottom > union_bottom)
                union_bottom = bottom;
        }
    }

    if (!have_union)
        return ASS_RGBA_EMPTY;

    int64_t width64 = union_right - union_left;
    int64_t height64 = union_bottom - union_top;
    if (width64 <= 0 || height64 <= 0 || width64 > INT32_MAX / 4 ||
        height64 > INT32_MAX)
        return ASS_RGBA_ERROR;

    size_t width = (size_t) width64;
    size_t height = (size_t) height64;
    size_t stride = width * 4U;
    if (height > SIZE_MAX / stride)
        return ASS_RGBA_ERROR;
    size_t size = stride * height;
    if (size > max_bytes)
        return ASS_RGBA_ERROR;

    if (buffer->capacity < size) {
        uint8_t *pixels = (uint8_t *) realloc(buffer->pixels, size);
        if (!pixels)
            return ASS_RGBA_ERROR;
        buffer->pixels = pixels;
        buffer->capacity = size;
    }
    memset(buffer->pixels, 0, size);

    for (size_t i = 0; i < image_count; ++i) {
        const AssRgbaImage *image = &images[i];
        if (image->width == 0 || image->height == 0 ||
            (image->color_rgba & 0xFFU) == 0xFFU)
            continue;

        int64_t left = image->dst_x;
        int64_t top = image->dst_y;
        int64_t right = left + image->width;
        int64_t bottom = top + image->height;
        if (left < union_left)
            left = union_left;
        if (top < union_top)
            top = union_top;
        if (right > union_right)
            right = union_right;
        if (bottom > union_bottom)
            bottom = union_bottom;
        if (left >= right || top >= bottom)
            continue;

        size_t source_x = (size_t) (left - image->dst_x);
        size_t source_y = (size_t) (top - image->dst_y);
        size_t copy_width = (size_t) (right - left);
        size_t copy_height = (size_t) (bottom - top);
        size_t destination_x = (size_t) (left - union_left);
        size_t destination_y = (size_t) (top - union_top);

        uint32_t red = image->color_rgba >> 24;
        uint32_t green = (image->color_rgba >> 16) & 0xFFU;
        uint32_t blue = (image->color_rgba >> 8) & 0xFFU;
        uint32_t opacity = 255U - (image->color_rgba & 0xFFU);

        for (size_t row = 0; row < copy_height; ++row) {
            size_t source_row = source_y + row;
            size_t source_offset = source_row * (size_t) image->stride + source_x;
            const uint8_t *coverage = image->bitmap + source_offset;
            size_t destination_offset =
                (destination_y + row) * stride + destination_x * 4U;
            uint8_t *destination = buffer->pixels + destination_offset;

            /* Read exactly copy_width bytes; never assume padding on last row. */
            for (size_t column = 0; column < copy_width; ++column) {
                uint32_t source_alpha =
                    divide_by_255((uint32_t) coverage[column] * opacity);
                if (source_alpha != 0) {
                    uint8_t *pixel = destination + column * 4U;
                    pixel[0] = blend_component((uint8_t) red, pixel[0], source_alpha);
                    pixel[1] = blend_component((uint8_t) green, pixel[1], source_alpha);
                    pixel[2] = blend_component((uint8_t) blue, pixel[2], source_alpha);
                    pixel[3] = blend_component(255U, pixel[3], source_alpha);
                }
            }
        }
    }

    buffer->size = size;
    buffer->x = (int32_t) union_left;
    buffer->y = (int32_t) union_top;
    buffer->width = (int32_t) width64;
    buffer->height = (int32_t) height64;
    buffer->stride = (int32_t) stride;
    return ASS_RGBA_PIXELS;
}

void ass_rgba_buffer_release(AssRgbaBuffer *buffer)
{
    if (!buffer)
        return;
    free(buffer->pixels);
    memset(buffer, 0, sizeof(*buffer));
}
