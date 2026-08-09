#ifndef LINUX_VULKAN_PROJECTION_H
#define LINUX_VULKAN_PROJECTION_H

#include <stdint.h>
#include <stddef.h>

typedef struct LinuxVulkanProjection LinuxVulkanProjection;

typedef struct LinuxVulkanTextureFrame {
    uint64_t serial;
    uint64_t generation;
    int32_t width;
    int32_t height;
    int32_t fourcc;
    int32_t dma_buf_fd;
    int32_t stride;
    int32_t offset;
    uint64_t modifier;
    int32_t acquire_fence_fd;
} LinuxVulkanTextureFrame;

typedef struct LinuxVulkanProjectionConfiguration {
    int32_t transfer;
    int32_t projection;
    int32_t stereo;
    int32_t rotation;
    int32_t eye_order;
    int32_t output_transfer;
    int32_t color_range;
    int32_t color_matrix;
    int32_t color_primaries;
    int32_t applies_hdr10_plus;
    float fov_degrees;
    float yaw_degrees;
    float pitch_degrees;
    float roll_degrees;
    float zoom;
    float source_peak_nits;
    float target_peak_nits;
    float reference_white_nits;
    float crop_left;
    float crop_top;
    float crop_right;
    float crop_bottom;
    float mastering_red_x;
    float mastering_red_y;
    float mastering_green_x;
    float mastering_green_y;
    float mastering_blue_x;
    float mastering_blue_y;
    float mastering_white_x;
    float mastering_white_y;
    float mastering_min_luminance_nits;
    float mastering_max_luminance_nits;
    float max_content_light_level_nits;
    float max_frame_average_light_level_nits;
} LinuxVulkanProjectionConfiguration;

int linux_vulkan_projection_library_available(void);

/** Creates a headless, exportable DMA-BUF texture producer. */
LinuxVulkanProjection* linux_vulkan_texture_create(
    int32_t width,
    int32_t height,
    int32_t input_p010,
    int32_t output_hdr,
    const LinuxVulkanProjectionConfiguration* configuration
);

int linux_vulkan_texture_update(
    LinuxVulkanProjection* renderer,
    int32_t width,
    int32_t height,
    int32_t input_p010,
    int32_t output_hdr,
    const LinuxVulkanProjectionConfiguration* configuration
);

int linux_vulkan_texture_acquire_frame(
    LinuxVulkanProjection* renderer,
    LinuxVulkanTextureFrame* frame
);

void linux_vulkan_texture_release_frame(
    LinuxVulkanProjection* renderer,
    uint64_t serial,
    int32_t dma_buf_fd,
    int32_t release_fence_fd
);

int linux_vulkan_texture_render_bgra(
    LinuxVulkanProjection* renderer,
    const uint8_t* pixels,
    int32_t stride,
    int32_t width,
    int32_t height
);

LinuxVulkanProjection* linux_vulkan_projection_create(
    uintptr_t display,
    uintptr_t parent_surface,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    const LinuxVulkanProjectionConfiguration* configuration
);

int linux_vulkan_projection_update_geometry(
    LinuxVulkanProjection* renderer,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height
);

void linux_vulkan_projection_update_configuration(
    LinuxVulkanProjection* renderer,
    const LinuxVulkanProjectionConfiguration* configuration
);

/**
 * Updates dynamic metadata for the exact frame about to be rendered.
 * Returns 1 when rendering may proceed. Returns 0 when HDR10+ application was
 * requested but the frame has no valid ST 2094-40 payload; callers should skip
 * that frame and let the JVM re-plan to the explicit static-HDR fallback.
 */
int linux_vulkan_projection_update_hdr10_plus_metadata(
    LinuxVulkanProjection* renderer,
    const uint8_t* payload,
    size_t payload_size
);

int linux_vulkan_projection_render_p010(
    LinuxVulkanProjection* renderer,
    const uint8_t* luma,
    int32_t luma_stride,
    const uint8_t* chroma,
    int32_t chroma_stride,
    int32_t width,
    int32_t height,
    int32_t input_transfer,
    int32_t input_is_dmabuf
);

int32_t linux_vulkan_projection_get_state(LinuxVulkanProjection* renderer);
void linux_vulkan_projection_destroy(LinuxVulkanProjection* renderer);

#endif
