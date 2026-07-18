#include "AssRgbaCompositor.h"

#include <assert.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define MAX_BYTES (64U * 1024U * 1024U)

static void expect_empty_frame(void)
{
    AssRgbaBuffer output = {0};
    assert(ass_rgba_composite(NULL, 0, 1920, 1080, MAX_BYTES, &output) ==
           ASS_RGBA_EMPTY);
    assert(output.size == 0);
    ass_rgba_buffer_release(&output);
}

static void expect_source_over_in_list_order(void)
{
    static const uint8_t opaque[] = {255};
    static const uint8_t half[] = {128};
    const AssRgbaImage images[] = {
        {1, 1, 1, opaque, UINT32_C(0x0000FF00), 4, 7},
        {1, 1, 1, half, UINT32_C(0xFF000000), 4, 7},
    };
    AssRgbaBuffer output = {0};
    assert(ass_rgba_composite(images, 2, 16, 16, MAX_BYTES, &output) ==
           ASS_RGBA_PIXELS);
    assert(output.x == 4 && output.y == 7);
    assert(output.width == 1 && output.height == 1 && output.stride == 4);
    static const uint8_t expected[] = {128, 0, 127, 255};
    assert(output.size == sizeof(expected));
    assert(memcmp(output.pixels, expected, sizeof(expected)) == 0);
    ass_rgba_buffer_release(&output);
}

static void expect_clipping_and_short_last_row(void)
{
    /* Exactly stride * (height - 1) + width bytes: no last-row padding. */
    static const uint8_t coverage[] = {0, 0, 0xA5, 0xA5, 0, 255};
    const AssRgbaImage image = {
        2, 2, 4, coverage, UINT32_C(0x00FF0000), -1, -1,
    };
    AssRgbaBuffer output = {0};
    assert(ass_rgba_composite(&image, 1, 2, 2, MAX_BYTES, &output) ==
           ASS_RGBA_PIXELS);
    assert(output.x == 0 && output.y == 0);
    assert(output.width == 1 && output.height == 1 && output.stride == 4);
    static const uint8_t expected[] = {0, 255, 0, 255};
    assert(output.size == sizeof(expected));
    assert(memcmp(output.pixels, expected, sizeof(expected)) == 0);
    ass_rgba_buffer_release(&output);
}

static void expect_transparent_and_offscreen_images_are_empty(void)
{
    static const uint8_t coverage[] = {255};
    const AssRgbaImage images[] = {
        {1, 1, 1, coverage, UINT32_C(0xFFFFFFFF), 0, 0},
        {1, 1, 1, coverage, UINT32_C(0xFFFFFFFF), -5, -5},
    };
    AssRgbaBuffer output = {0};
    assert(ass_rgba_composite(images, 2, 2, 2, MAX_BYTES, &output) ==
           ASS_RGBA_EMPTY);
    assert(output.size == 0);
    ass_rgba_buffer_release(&output);
}

static void expect_limits_and_invalid_stride_are_rejected(void)
{
    static const uint8_t coverage[] = {255, 255, 255, 255};
    const AssRgbaImage valid = {
        2, 2, 2, coverage, UINT32_C(0xFFFFFFFF) & ~UINT32_C(0xFF), 0, 0,
    };
    const AssRgbaImage invalid = {
        2, 2, 1, coverage, UINT32_C(0x00000000), 0, 0,
    };
    AssRgbaBuffer output = {0};
    assert(ass_rgba_composite(&valid, 1, 2, 2, 15, &output) ==
           ASS_RGBA_ERROR);
    assert(output.size == 0);
    assert(ass_rgba_composite(&invalid, 1, 2, 2, MAX_BYTES, &output) ==
           ASS_RGBA_ERROR);
    assert(output.size == 0);
    ass_rgba_buffer_release(&output);
}

static void expect_extreme_dimensions_are_rejected_before_bitmap_access(void)
{
    static const uint8_t one_byte[] = {255};
    const AssRgbaImage enormous = {
        INT32_MAX, 1, INT32_MAX, one_byte, UINT32_C(0xFFFFFF00), 0, 0,
    };
    AssRgbaBuffer output = {0};
    assert(ass_rgba_composite(&enormous, 1, INT32_MAX, 1,
                              MAX_BYTES, &output) == ASS_RGBA_ERROR);
    assert(output.size == 0);
    ass_rgba_buffer_release(&output);
}

int main(void)
{
    expect_empty_frame();
    expect_source_over_in_list_order();
    expect_clipping_and_short_last_row();
    expect_transparent_and_offscreen_images_are_empty();
    expect_limits_and_invalid_stride_are_rejected();
    expect_extreme_dimensions_are_rejected_before_bitmap_access();
    puts("kmedia ASS RGBA compositor smoke: PASS");
    return 0;
}
