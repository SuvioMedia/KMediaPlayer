#ifndef VULKAN_CAPABILITY_PROBE_H
#define VULKAN_CAPABILITY_PROBE_H

#include <stdint.h>

#define VCP_AVAILABLE                    (1U << 0)
#define VCP_WAYLAND_SURFACE              (1U << 1)
#define VCP_EXTERNAL_MEMORY_DMA_BUF       (1U << 2)
#define VCP_IMAGE_DRM_FORMAT_MODIFIER     (1U << 3)
#define VCP_EXTERNAL_MEMORY_FD            (1U << 4)
#define VCP_SHADER_FLOAT16                (1U << 5)
#define VCP_SAMPLER_YCBCR_CONVERSION      (1U << 6)

uint32_t vulkan_capability_probe_query(void);

#endif
