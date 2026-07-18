#include "LinuxVulkanProjection.h"

#include "Hdr10PlusToneCurve.h"
#include "NativeVideoPlayer.h"
#include "LinuxHdrProjection.frag.spv.h"
#include "LinuxHdrProjection.vert.spv.h"

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_wayland.h>
#include <wayland-client.h>

#include <pthread.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct HdrUniforms {
    int32_t modes[4];
    int32_t flags[4];
    int32_t color[4];
    float projection[4];
    float view[4];
    float crop[4];
    float hdr10_plus_header[4];
    float hdr10_plus_curve[9][4];
} HdrUniforms;

_Static_assert(sizeof(HdrUniforms) == 256, "Vulkan std140 HDR uniform layout must stay stable");

struct LinuxVulkanProjection {
    pthread_mutex_t lock;
    struct wl_display* display;
    struct wl_surface* parent_surface;
    struct wl_event_queue* event_queue;
    struct wl_registry* registry;
    struct wl_compositor* compositor;
    struct wl_subcompositor* subcompositor;
    struct wl_surface* surface;
    struct wl_subsurface* subsurface;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;

    VkInstance instance;
    VkPhysicalDevice physical_device;
    VkDevice device;
    uint32_t queue_family;
    VkQueue queue;
    VkSurfaceKHR vk_surface;
    VkSwapchainKHR swapchain;
    VkFormat swapchain_format;
    VkColorSpaceKHR swapchain_color_space;
    VkExtent2D extent;
    VkImage* swapchain_images;
    VkImageView* swapchain_views;
    VkFramebuffer* framebuffers;
    uint32_t swapchain_image_count;
    VkRenderPass render_pass;
    VkDescriptorSetLayout descriptor_layout;
    VkPipelineLayout pipeline_layout;
    VkPipeline pipeline;
    VkDescriptorPool descriptor_pool;
    VkDescriptorSet descriptor_set;
    VkSampler sampler;
    VkCommandPool command_pool;
    VkCommandBuffer command_buffer;
    VkSemaphore image_available;
    VkSemaphore render_finished;
    VkFence render_fence;

    VkBuffer uniform_buffer;
    VkDeviceMemory uniform_memory;
    void* uniform_mapped;
    VkBuffer staging_buffer;
    VkDeviceMemory staging_memory;
    void* staging_mapped;
    VkDeviceSize staging_size;
    VkImage luma_image;
    VkDeviceMemory luma_memory;
    VkImageView luma_view;
    VkImage chroma_image;
    VkDeviceMemory chroma_memory;
    VkImageView chroma_view;
    int32_t input_width;
    int32_t input_height;
    int input_initialized;

    LinuxVulkanProjectionConfiguration configuration;
    float hdr10_plus_source_peak_nits;
    float hdr10_plus_curve[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT];
    int hdr10_plus_curve_valid;
    int32_t state;
    int failed;
};

static int extension_available(const VkExtensionProperties* properties, uint32_t count, const char* name) {
    for (uint32_t index = 0; index < count; index++) {
        if (strcmp(properties[index].extensionName, name) == 0) return 1;
    }
    return 0;
}

static void registry_global(
    void* data,
    struct wl_registry* registry,
    uint32_t name,
    const char* interface,
    uint32_t version
) {
    LinuxVulkanProjection* renderer = data;
    if (strcmp(interface, wl_compositor_interface.name) == 0 && !renderer->compositor) {
        renderer->compositor = wl_registry_bind(
            registry,
            name,
            &wl_compositor_interface,
            version < 4 ? version : 4
        );
        if (renderer->compositor) {
            wl_proxy_set_queue((struct wl_proxy*)renderer->compositor, renderer->event_queue);
        }
    } else if (strcmp(interface, wl_subcompositor_interface.name) == 0 && !renderer->subcompositor) {
        renderer->subcompositor = wl_registry_bind(
            registry,
            name,
            &wl_subcompositor_interface,
            version < 1 ? version : 1
        );
        if (renderer->subcompositor) {
            wl_proxy_set_queue((struct wl_proxy*)renderer->subcompositor, renderer->event_queue);
        }
    }
}

static void registry_global_remove(void* data, struct wl_registry* registry, uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

static int create_wayland_surface(LinuxVulkanProjection* renderer) {
    renderer->event_queue = wl_display_create_queue(renderer->display);
    if (!renderer->event_queue) return 0;
    renderer->registry = wl_display_get_registry(renderer->display);
    if (!renderer->registry) return 0;
    wl_proxy_set_queue((struct wl_proxy*)renderer->registry, renderer->event_queue);
    if (wl_registry_add_listener(renderer->registry, &registry_listener, renderer) != 0) return 0;
    if (wl_display_roundtrip_queue(renderer->display, renderer->event_queue) < 0) return 0;
    if (!renderer->compositor || !renderer->subcompositor) return 0;

    renderer->surface = wl_compositor_create_surface(renderer->compositor);
    if (!renderer->surface) return 0;
    wl_proxy_set_queue((struct wl_proxy*)renderer->surface, renderer->event_queue);
    struct wl_region* empty_input_region =
        wl_compositor_create_region(renderer->compositor);
    if (!empty_input_region) return 0;
    wl_surface_set_input_region(renderer->surface, empty_input_region);
    wl_region_destroy(empty_input_region);
    renderer->subsurface = wl_subcompositor_get_subsurface(
        renderer->subcompositor,
        renderer->surface,
        renderer->parent_surface
    );
    if (!renderer->subsurface) return 0;
    wl_proxy_set_queue((struct wl_proxy*)renderer->subsurface, renderer->event_queue);
    wl_subsurface_set_desync(renderer->subsurface);
    wl_subsurface_set_position(renderer->subsurface, renderer->x, renderer->y);
    wl_surface_commit(renderer->surface);
    wl_surface_commit(renderer->parent_surface);
    return wl_display_flush(renderer->display) >= 0;
}

static uint32_t find_memory_type(
    LinuxVulkanProjection* renderer,
    uint32_t type_bits,
    VkMemoryPropertyFlags required
) {
    VkPhysicalDeviceMemoryProperties memory_properties;
    vkGetPhysicalDeviceMemoryProperties(renderer->physical_device, &memory_properties);
    for (uint32_t index = 0; index < memory_properties.memoryTypeCount; index++) {
        if ((type_bits & (1u << index)) != 0 &&
            (memory_properties.memoryTypes[index].propertyFlags & required) == required) {
            return index;
        }
    }
    return UINT32_MAX;
}

static int create_buffer(
    LinuxVulkanProjection* renderer,
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags properties,
    VkBuffer* buffer,
    VkDeviceMemory* memory,
    void** mapped
) {
    VkBufferCreateInfo buffer_info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (vkCreateBuffer(renderer->device, &buffer_info, NULL, buffer) != VK_SUCCESS) return 0;
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(renderer->device, *buffer, &requirements);
    uint32_t memory_type = find_memory_type(renderer, requirements.memoryTypeBits, properties);
    if (memory_type == UINT32_MAX) return 0;
    VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    if (vkAllocateMemory(renderer->device, &allocation, NULL, memory) != VK_SUCCESS) return 0;
    if (vkBindBufferMemory(renderer->device, *buffer, *memory, 0) != VK_SUCCESS) return 0;
    if (mapped && vkMapMemory(renderer->device, *memory, 0, size, 0, mapped) != VK_SUCCESS) return 0;
    return 1;
}

static int create_image(
    LinuxVulkanProjection* renderer,
    uint32_t width,
    uint32_t height,
    VkFormat format,
    VkImage* image,
    VkDeviceMemory* memory,
    VkImageView* view
) {
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = {width, height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (vkCreateImage(renderer->device, &image_info, NULL, image) != VK_SUCCESS) return 0;
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(renderer->device, *image, &requirements);
    uint32_t memory_type = find_memory_type(
        renderer,
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    if (memory_type == UINT32_MAX) return 0;
    VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    if (vkAllocateMemory(renderer->device, &allocation, NULL, memory) != VK_SUCCESS) return 0;
    if (vkBindImageMemory(renderer->device, *image, *memory, 0) != VK_SUCCESS) return 0;
    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = *image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    return vkCreateImageView(renderer->device, &view_info, NULL, view) == VK_SUCCESS;
}

static void destroy_input_images(LinuxVulkanProjection* renderer) {
    if (renderer->luma_view) vkDestroyImageView(renderer->device, renderer->luma_view, NULL);
    if (renderer->luma_image) vkDestroyImage(renderer->device, renderer->luma_image, NULL);
    if (renderer->luma_memory) vkFreeMemory(renderer->device, renderer->luma_memory, NULL);
    if (renderer->chroma_view) vkDestroyImageView(renderer->device, renderer->chroma_view, NULL);
    if (renderer->chroma_image) vkDestroyImage(renderer->device, renderer->chroma_image, NULL);
    if (renderer->chroma_memory) vkFreeMemory(renderer->device, renderer->chroma_memory, NULL);
    if (renderer->staging_mapped) vkUnmapMemory(renderer->device, renderer->staging_memory);
    if (renderer->staging_buffer) vkDestroyBuffer(renderer->device, renderer->staging_buffer, NULL);
    if (renderer->staging_memory) vkFreeMemory(renderer->device, renderer->staging_memory, NULL);
    renderer->luma_view = VK_NULL_HANDLE;
    renderer->luma_image = VK_NULL_HANDLE;
    renderer->luma_memory = VK_NULL_HANDLE;
    renderer->chroma_view = VK_NULL_HANDLE;
    renderer->chroma_image = VK_NULL_HANDLE;
    renderer->chroma_memory = VK_NULL_HANDLE;
    renderer->staging_mapped = NULL;
    renderer->staging_buffer = VK_NULL_HANDLE;
    renderer->staging_memory = VK_NULL_HANDLE;
    renderer->staging_size = 0;
    renderer->input_width = 0;
    renderer->input_height = 0;
    renderer->input_initialized = 0;
}

static int create_input_images(LinuxVulkanProjection* renderer, int32_t width, int32_t height) {
    if (width <= 0 || height <= 0) return 0;
    if (renderer->input_width == width && renderer->input_height == height) return 1;
    vkDeviceWaitIdle(renderer->device);
    destroy_input_images(renderer);

    uint32_t chroma_width = ((uint32_t)width + 1u) / 2u;
    uint32_t chroma_height = ((uint32_t)height + 1u) / 2u;
    VkDeviceSize luma_size = (VkDeviceSize)(uint32_t)width * (uint32_t)height * 2u;
    VkDeviceSize chroma_size = (VkDeviceSize)chroma_width * chroma_height * 4u;
    if (luma_size > SIZE_MAX - chroma_size) return 0;
    renderer->staging_size = luma_size + chroma_size;
    if (!create_buffer(
            renderer,
            renderer->staging_size,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &renderer->staging_buffer,
            &renderer->staging_memory,
            &renderer->staging_mapped)) return 0;
    if (!create_image(
            renderer,
            (uint32_t)width,
            (uint32_t)height,
            VK_FORMAT_R16_UNORM,
            &renderer->luma_image,
            &renderer->luma_memory,
            &renderer->luma_view)) return 0;
    if (!create_image(
            renderer,
            chroma_width,
            chroma_height,
            VK_FORMAT_R16G16_UNORM,
            &renderer->chroma_image,
            &renderer->chroma_memory,
            &renderer->chroma_view)) return 0;

    VkDescriptorImageInfo luma_info = {
        .sampler = renderer->sampler,
        .imageView = renderer->luma_view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkDescriptorImageInfo chroma_info = {
        .sampler = renderer->sampler,
        .imageView = renderer->chroma_view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet writes[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = renderer->descriptor_set,
            .dstBinding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &luma_info,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = renderer->descriptor_set,
            .dstBinding = 2,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &chroma_info,
        },
    };
    vkUpdateDescriptorSets(renderer->device, 2, writes, 0, NULL);
    renderer->input_width = width;
    renderer->input_height = height;
    return 1;
}

static VkShaderModule create_shader_module(LinuxVulkanProjection* renderer, const uint8_t* bytes, size_t size) {
    if (!bytes || size == 0 || size % sizeof(uint32_t) != 0) return VK_NULL_HANDLE;
    VkShaderModuleCreateInfo create_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = size,
        .pCode = (const uint32_t*)bytes,
    };
    VkShaderModule shader = VK_NULL_HANDLE;
    if (vkCreateShaderModule(renderer->device, &create_info, NULL, &shader) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return shader;
}

static void destroy_swapchain(LinuxVulkanProjection* renderer) {
    if (!renderer->device) return;
    if (renderer->framebuffers) {
        for (uint32_t index = 0; index < renderer->swapchain_image_count; index++) {
            if (renderer->framebuffers[index]) {
                vkDestroyFramebuffer(renderer->device, renderer->framebuffers[index], NULL);
            }
        }
    }
    if (renderer->pipeline) vkDestroyPipeline(renderer->device, renderer->pipeline, NULL);
    if (renderer->render_pass) vkDestroyRenderPass(renderer->device, renderer->render_pass, NULL);
    if (renderer->swapchain_views) {
        for (uint32_t index = 0; index < renderer->swapchain_image_count; index++) {
            if (renderer->swapchain_views[index]) {
                vkDestroyImageView(renderer->device, renderer->swapchain_views[index], NULL);
            }
        }
    }
    if (renderer->swapchain) vkDestroySwapchainKHR(renderer->device, renderer->swapchain, NULL);
    free(renderer->framebuffers);
    free(renderer->swapchain_views);
    free(renderer->swapchain_images);
    renderer->framebuffers = NULL;
    renderer->swapchain_views = NULL;
    renderer->swapchain_images = NULL;
    renderer->swapchain_image_count = 0;
    renderer->pipeline = VK_NULL_HANDLE;
    renderer->render_pass = VK_NULL_HANDLE;
    renderer->swapchain = VK_NULL_HANDLE;
}

static int choose_surface_format(
    LinuxVulkanProjection* renderer,
    const VkSurfaceFormatKHR* formats,
    uint32_t count,
    VkSurfaceFormatKHR* selected
) {
    VkColorSpaceKHR desired = renderer->configuration.output_transfer == 1
        ? VK_COLOR_SPACE_HDR10_HLG_EXT
        : VK_COLOR_SPACE_HDR10_ST2084_EXT;
    const VkFormat preferred[] = {
        VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        VK_FORMAT_A2R10G10B10_UNORM_PACK32,
    };
    for (size_t format_index = 0; format_index < sizeof(preferred) / sizeof(preferred[0]); format_index++) {
        for (uint32_t index = 0; index < count; index++) {
            if (formats[index].format == preferred[format_index] && formats[index].colorSpace == desired) {
                *selected = formats[index];
                return 1;
            }
        }
    }
    return 0;
}

static void set_hdr_metadata(LinuxVulkanProjection* renderer) {
    if (renderer->configuration.output_transfer != 0 || !renderer->swapchain) return;
    PFN_vkSetHdrMetadataEXT set_metadata =
        (PFN_vkSetHdrMetadataEXT)vkGetDeviceProcAddr(renderer->device, "vkSetHdrMetadataEXT");
    if (!set_metadata) return;
    const LinuxVulkanProjectionConfiguration* configuration = &renderer->configuration;
    VkHdrMetadataEXT metadata = {
        .sType = VK_STRUCTURE_TYPE_HDR_METADATA_EXT,
        .displayPrimaryRed = {
            configuration->mastering_red_x > 0.0f ? configuration->mastering_red_x : 0.708f,
            configuration->mastering_red_y > 0.0f ? configuration->mastering_red_y : 0.292f,
        },
        .displayPrimaryGreen = {
            configuration->mastering_green_x > 0.0f ? configuration->mastering_green_x : 0.170f,
            configuration->mastering_green_y > 0.0f ? configuration->mastering_green_y : 0.797f,
        },
        .displayPrimaryBlue = {
            configuration->mastering_blue_x > 0.0f ? configuration->mastering_blue_x : 0.131f,
            configuration->mastering_blue_y > 0.0f ? configuration->mastering_blue_y : 0.046f,
        },
        .whitePoint = {
            configuration->mastering_white_x > 0.0f ? configuration->mastering_white_x : 0.3127f,
            configuration->mastering_white_y > 0.0f ? configuration->mastering_white_y : 0.3290f,
        },
        .maxLuminance = configuration->mastering_max_luminance_nits > 0.0f
            ? configuration->mastering_max_luminance_nits
            : configuration->source_peak_nits,
        .minLuminance = configuration->mastering_min_luminance_nits >= 0.0f
            ? configuration->mastering_min_luminance_nits
            : 0.0f,
        .maxContentLightLevel = configuration->max_content_light_level_nits,
        .maxFrameAverageLightLevel = configuration->max_frame_average_light_level_nits,
    };
    set_metadata(renderer->device, 1, &renderer->swapchain, &metadata);
}

static int create_graphics_pipeline(LinuxVulkanProjection* renderer) {
    VkAttachmentDescription color_attachment = {
        .format = renderer->swapchain_format,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
    };
    VkAttachmentReference color_reference = {
        .attachment = 0,
        .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
    };
    VkSubpassDescription subpass = {
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1,
        .pColorAttachments = &color_reference,
    };
    VkSubpassDependency dependency = {
        .srcSubpass = VK_SUBPASS_EXTERNAL,
        .dstSubpass = 0,
        .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
    };
    VkRenderPassCreateInfo render_pass_info = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &color_attachment,
        .subpassCount = 1,
        .pSubpasses = &subpass,
        .dependencyCount = 1,
        .pDependencies = &dependency,
    };
    if (vkCreateRenderPass(renderer->device, &render_pass_info, NULL, &renderer->render_pass) != VK_SUCCESS) {
        return 0;
    }

    VkShaderModule vertex = create_shader_module(
        renderer,
        linux_hdr_projection_vert_spv,
        linux_hdr_projection_vert_spv_size
    );
    VkShaderModule fragment = create_shader_module(
        renderer,
        linux_hdr_projection_frag_spv,
        linux_hdr_projection_frag_spv_size
    );
    if (!vertex || !fragment) {
        if (vertex) vkDestroyShaderModule(renderer->device, vertex, NULL);
        if (fragment) vkDestroyShaderModule(renderer->device, fragment, NULL);
        return 0;
    }
    VkPipelineShaderStageCreateInfo shader_stages[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertex,
            .pName = "main",
        },
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragment,
            .pName = "main",
        },
    };
    VkPipelineVertexInputStateCreateInfo vertex_input = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
    };
    VkPipelineInputAssemblyStateCreateInfo input_assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
    };
    VkPipelineViewportStateCreateInfo viewport_state = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1,
        .scissorCount = 1,
    };
    VkPipelineRasterizationStateCreateInfo rasterization = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .lineWidth = 1.0f,
    };
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
    };
    VkPipelineColorBlendAttachmentState color_blend_attachment = {
        .colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT,
    };
    VkPipelineColorBlendStateCreateInfo color_blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &color_blend_attachment,
    };
    VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic_state = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = 2,
        .pDynamicStates = dynamic_states,
    };
    VkGraphicsPipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2,
        .pStages = shader_stages,
        .pVertexInputState = &vertex_input,
        .pInputAssemblyState = &input_assembly,
        .pViewportState = &viewport_state,
        .pRasterizationState = &rasterization,
        .pMultisampleState = &multisample,
        .pColorBlendState = &color_blend,
        .pDynamicState = &dynamic_state,
        .layout = renderer->pipeline_layout,
        .renderPass = renderer->render_pass,
        .subpass = 0,
    };
    VkResult result = vkCreateGraphicsPipelines(
        renderer->device,
        VK_NULL_HANDLE,
        1,
        &pipeline_info,
        NULL,
        &renderer->pipeline
    );
    vkDestroyShaderModule(renderer->device, vertex, NULL);
    vkDestroyShaderModule(renderer->device, fragment, NULL);
    return result == VK_SUCCESS;
}

static int create_swapchain(LinuxVulkanProjection* renderer) {
    VkSurfaceCapabilitiesKHR capabilities;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
            renderer->physical_device,
            renderer->vk_surface,
            &capabilities) != VK_SUCCESS) return 0;
    uint32_t format_count = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(
            renderer->physical_device,
            renderer->vk_surface,
            &format_count,
            NULL) != VK_SUCCESS || format_count == 0) return 0;
    VkSurfaceFormatKHR* formats = calloc(format_count, sizeof(*formats));
    if (!formats) return 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(
            renderer->physical_device,
            renderer->vk_surface,
            &format_count,
            formats) != VK_SUCCESS) {
        free(formats);
        return 0;
    }
    VkSurfaceFormatKHR selected;
    int selected_ok = choose_surface_format(renderer, formats, format_count, &selected);
    free(formats);
    if (!selected_ok) return 0;

    VkExtent2D extent = {
        .width = (uint32_t)renderer->width,
        .height = (uint32_t)renderer->height,
    };
    if (capabilities.currentExtent.width != UINT32_MAX) {
        extent = capabilities.currentExtent;
    } else {
        if (extent.width < capabilities.minImageExtent.width) extent.width = capabilities.minImageExtent.width;
        if (extent.width > capabilities.maxImageExtent.width) extent.width = capabilities.maxImageExtent.width;
        if (extent.height < capabilities.minImageExtent.height) extent.height = capabilities.minImageExtent.height;
        if (extent.height > capabilities.maxImageExtent.height) extent.height = capabilities.maxImageExtent.height;
    }
    uint32_t image_count = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && image_count > capabilities.maxImageCount) {
        image_count = capabilities.maxImageCount;
    }
    VkCompositeAlphaFlagBitsKHR composite_alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    const VkCompositeAlphaFlagBitsKHR candidates[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
    };
    for (size_t index = 0; index < sizeof(candidates) / sizeof(candidates[0]); index++) {
        if (capabilities.supportedCompositeAlpha & candidates[index]) {
            composite_alpha = candidates[index];
            break;
        }
    }
    VkSwapchainCreateInfoKHR swapchain_info = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = renderer->vk_surface,
        .minImageCount = image_count,
        .imageFormat = selected.format,
        .imageColorSpace = selected.colorSpace,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = capabilities.currentTransform,
        .compositeAlpha = composite_alpha,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR,
        .clipped = VK_TRUE,
    };
    if (vkCreateSwapchainKHR(renderer->device, &swapchain_info, NULL, &renderer->swapchain) != VK_SUCCESS) {
        return 0;
    }
    renderer->swapchain_format = selected.format;
    renderer->swapchain_color_space = selected.colorSpace;
    renderer->extent = extent;
    if (vkGetSwapchainImagesKHR(renderer->device, renderer->swapchain, &image_count, NULL) != VK_SUCCESS) {
        return 0;
    }
    renderer->swapchain_images = calloc(image_count, sizeof(*renderer->swapchain_images));
    renderer->swapchain_views = calloc(image_count, sizeof(*renderer->swapchain_views));
    renderer->framebuffers = calloc(image_count, sizeof(*renderer->framebuffers));
    if (!renderer->swapchain_images || !renderer->swapchain_views || !renderer->framebuffers) return 0;
    renderer->swapchain_image_count = image_count;
    if (vkGetSwapchainImagesKHR(
            renderer->device,
            renderer->swapchain,
            &image_count,
            renderer->swapchain_images) != VK_SUCCESS) return 0;
    for (uint32_t index = 0; index < image_count; index++) {
        VkImageViewCreateInfo view_info = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = renderer->swapchain_images[index],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = renderer->swapchain_format,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        if (vkCreateImageView(
                renderer->device,
                &view_info,
                NULL,
                &renderer->swapchain_views[index]) != VK_SUCCESS) return 0;
    }
    if (!create_graphics_pipeline(renderer)) return 0;
    for (uint32_t index = 0; index < image_count; index++) {
        VkFramebufferCreateInfo framebuffer_info = {
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = renderer->render_pass,
            .attachmentCount = 1,
            .pAttachments = &renderer->swapchain_views[index],
            .width = renderer->extent.width,
            .height = renderer->extent.height,
            .layers = 1,
        };
        if (vkCreateFramebuffer(
                renderer->device,
                &framebuffer_info,
                NULL,
                &renderer->framebuffers[index]) != VK_SUCCESS) return 0;
    }
    set_hdr_metadata(renderer);
    return 1;
}

static int create_vulkan_instance(LinuxVulkanProjection* renderer) {
    uint32_t extension_count = 0;
    if (vkEnumerateInstanceExtensionProperties(NULL, &extension_count, NULL) != VK_SUCCESS) return 0;
    VkExtensionProperties* extensions = calloc(extension_count, sizeof(*extensions));
    if (!extensions) return 0;
    if (vkEnumerateInstanceExtensionProperties(NULL, &extension_count, extensions) != VK_SUCCESS) {
        free(extensions);
        return 0;
    }
    const char* required[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME,
        VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME,
    };
    for (size_t index = 0; index < sizeof(required) / sizeof(required[0]); index++) {
        if (!extension_available(extensions, extension_count, required[index])) {
            free(extensions);
            return 0;
        }
    }
    free(extensions);
    VkApplicationInfo application_info = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "KMediaPlayer Linux HDR projection",
        .applicationVersion = VK_MAKE_VERSION(2, 0, 0),
        .pEngineName = "KMediaPlayer",
        .engineVersion = VK_MAKE_VERSION(2, 0, 0),
        .apiVersion = VK_API_VERSION_1_1,
    };
    VkInstanceCreateInfo create_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &application_info,
        .enabledExtensionCount = sizeof(required) / sizeof(required[0]),
        .ppEnabledExtensionNames = required,
    };
    if (vkCreateInstance(&create_info, NULL, &renderer->instance) != VK_SUCCESS) return 0;
    VkWaylandSurfaceCreateInfoKHR surface_info = {
        .sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR,
        .display = renderer->display,
        .surface = renderer->surface,
    };
    return vkCreateWaylandSurfaceKHR(renderer->instance, &surface_info, NULL, &renderer->vk_surface) == VK_SUCCESS;
}

static int create_vulkan_device(LinuxVulkanProjection* renderer) {
    uint32_t device_count = 0;
    if (vkEnumeratePhysicalDevices(renderer->instance, &device_count, NULL) != VK_SUCCESS || device_count == 0) {
        return 0;
    }
    VkPhysicalDevice* devices = calloc(device_count, sizeof(*devices));
    if (!devices) return 0;
    if (vkEnumeratePhysicalDevices(renderer->instance, &device_count, devices) != VK_SUCCESS) {
        free(devices);
        return 0;
    }
    int found = 0;
    for (uint32_t device_index = 0; device_index < device_count && !found; device_index++) {
        uint32_t queue_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[device_index], &queue_count, NULL);
        VkQueueFamilyProperties* queues = calloc(queue_count, sizeof(*queues));
        if (!queues) continue;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[device_index], &queue_count, queues);
        for (uint32_t queue_index = 0; queue_index < queue_count; queue_index++) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(
                devices[device_index],
                queue_index,
                renderer->vk_surface,
                &present
            );
            if ((queues[queue_index].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                renderer->physical_device = devices[device_index];
                renderer->queue_family = queue_index;
                found = 1;
                break;
            }
        }
        free(queues);
    }
    free(devices);
    if (!found) return 0;

    uint32_t extension_count = 0;
    vkEnumerateDeviceExtensionProperties(renderer->physical_device, NULL, &extension_count, NULL);
    VkExtensionProperties* extensions = calloc(extension_count, sizeof(*extensions));
    if (!extensions) return 0;
    vkEnumerateDeviceExtensionProperties(renderer->physical_device, NULL, &extension_count, extensions);
    if (!extension_available(extensions, extension_count, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        free(extensions);
        return 0;
    }
    const int has_hdr_metadata = extension_available(extensions, extension_count, VK_EXT_HDR_METADATA_EXTENSION_NAME);
    if (renderer->configuration.output_transfer == 0 && !has_hdr_metadata) {
        free(extensions);
        return 0;
    }
    free(extensions);
    const char* device_extensions[2] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_EXT_HDR_METADATA_EXTENSION_NAME};
    uint32_t enabled_extension_count = has_hdr_metadata ? 2u : 1u;
    float queue_priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = renderer->queue_family,
        .queueCount = 1,
        .pQueuePriorities = &queue_priority,
    };
    VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue_info,
        .enabledExtensionCount = enabled_extension_count,
        .ppEnabledExtensionNames = device_extensions,
    };
    if (vkCreateDevice(renderer->physical_device, &device_info, NULL, &renderer->device) != VK_SUCCESS) {
        return 0;
    }
    vkGetDeviceQueue(renderer->device, renderer->queue_family, 0, &renderer->queue);
    return renderer->queue != VK_NULL_HANDLE;
}

static int create_renderer_resources(LinuxVulkanProjection* renderer) {
    VkDescriptorSetLayoutBinding bindings[3] = {
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        },
        {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        },
        {
            .binding = 2,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        },
    };
    VkDescriptorSetLayoutCreateInfo descriptor_layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 3,
        .pBindings = bindings,
    };
    if (vkCreateDescriptorSetLayout(
            renderer->device,
            &descriptor_layout_info,
            NULL,
            &renderer->descriptor_layout) != VK_SUCCESS) return 0;
    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &renderer->descriptor_layout,
    };
    if (vkCreatePipelineLayout(
            renderer->device,
            &pipeline_layout_info,
            NULL,
            &renderer->pipeline_layout) != VK_SUCCESS) return 0;
    VkDescriptorPoolSize pool_sizes[2] = {
        {.type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .descriptorCount = 1},
        {.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .descriptorCount = 2},
    };
    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 1,
        .poolSizeCount = 2,
        .pPoolSizes = pool_sizes,
    };
    if (vkCreateDescriptorPool(renderer->device, &pool_info, NULL, &renderer->descriptor_pool) != VK_SUCCESS) {
        return 0;
    }
    VkDescriptorSetAllocateInfo descriptor_allocation = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = renderer->descriptor_pool,
        .descriptorSetCount = 1,
        .pSetLayouts = &renderer->descriptor_layout,
    };
    if (vkAllocateDescriptorSets(
            renderer->device,
            &descriptor_allocation,
            &renderer->descriptor_set) != VK_SUCCESS) return 0;
    if (!create_buffer(
            renderer,
            sizeof(HdrUniforms),
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &renderer->uniform_buffer,
            &renderer->uniform_memory,
            &renderer->uniform_mapped)) return 0;
    VkDescriptorBufferInfo buffer_info = {
        .buffer = renderer->uniform_buffer,
        .offset = 0,
        .range = sizeof(HdrUniforms),
    };
    VkWriteDescriptorSet uniform_write = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstSet = renderer->descriptor_set,
        .dstBinding = 0,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
        .pBufferInfo = &buffer_info,
    };
    vkUpdateDescriptorSets(renderer->device, 1, &uniform_write, 0, NULL);
    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .maxLod = 0.0f,
    };
    if (vkCreateSampler(renderer->device, &sampler_info, NULL, &renderer->sampler) != VK_SUCCESS) return 0;
    VkCommandPoolCreateInfo command_pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = renderer->queue_family,
    };
    if (vkCreateCommandPool(renderer->device, &command_pool_info, NULL, &renderer->command_pool) != VK_SUCCESS) {
        return 0;
    }
    VkCommandBufferAllocateInfo command_buffer_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = renderer->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    if (vkAllocateCommandBuffers(
            renderer->device,
            &command_buffer_info,
            &renderer->command_buffer) != VK_SUCCESS) return 0;
    VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    if (vkCreateSemaphore(renderer->device, &semaphore_info, NULL, &renderer->image_available) != VK_SUCCESS ||
        vkCreateSemaphore(renderer->device, &semaphore_info, NULL, &renderer->render_finished) != VK_SUCCESS) {
        return 0;
    }
    VkFenceCreateInfo fence_info = {
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
        .flags = VK_FENCE_CREATE_SIGNALED_BIT,
    };
    if (vkCreateFence(renderer->device, &fence_info, NULL, &renderer->render_fence) != VK_SUCCESS) return 0;
    return create_swapchain(renderer);
}

static HdrUniforms build_uniforms(const LinuxVulkanProjection* renderer) {
    const LinuxVulkanProjectionConfiguration* configuration = &renderer->configuration;
    HdrUniforms uniforms;
    memset(&uniforms, 0, sizeof(uniforms));
    uniforms.modes[0] = configuration->transfer;
    uniforms.modes[1] = configuration->projection;
    uniforms.modes[2] = configuration->stereo;
    uniforms.modes[3] = configuration->rotation;
    uniforms.flags[0] = configuration->eye_order;
    uniforms.flags[1] = configuration->output_transfer;
    uniforms.color[0] = configuration->color_range;
    uniforms.color[1] = configuration->color_matrix;
    uniforms.color[2] = configuration->color_primaries;
    uniforms.projection[0] = configuration->fov_degrees;
    uniforms.projection[1] = configuration->yaw_degrees;
    uniforms.projection[2] = configuration->pitch_degrees;
    uniforms.projection[3] = configuration->roll_degrees;
    uniforms.view[0] = configuration->zoom;
    uniforms.view[1] = configuration->source_peak_nits;
    uniforms.view[2] = configuration->target_peak_nits;
    uniforms.view[3] = configuration->reference_white_nits;
    uniforms.crop[0] = configuration->crop_left;
    uniforms.crop[1] = configuration->crop_top;
    uniforms.crop[2] = configuration->crop_right;
    uniforms.crop[3] = configuration->crop_bottom;
    if (configuration->applies_hdr10_plus && renderer->hdr10_plus_curve_valid) {
        uniforms.hdr10_plus_header[0] = 1.0f;
        uniforms.hdr10_plus_header[1] = renderer->hdr10_plus_source_peak_nits;
        for (uint32_t index = 0; index < KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT; index++) {
            uniforms.hdr10_plus_curve[index / 4][index % 4] = renderer->hdr10_plus_curve[index];
        }
    }
    return uniforms;
}

static void image_barrier(
    VkCommandBuffer command_buffer,
    VkImage image,
    VkImageLayout old_layout,
    VkImageLayout new_layout,
    VkAccessFlags source_access,
    VkAccessFlags destination_access,
    VkPipelineStageFlags source_stage,
    VkPipelineStageFlags destination_stage
) {
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = source_access,
        .dstAccessMask = destination_access,
        .oldLayout = old_layout,
        .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    vkCmdPipelineBarrier(
        command_buffer,
        source_stage,
        destination_stage,
        0,
        0,
        NULL,
        0,
        NULL,
        1,
        &barrier
    );
}

static int recreate_swapchain(LinuxVulkanProjection* renderer) {
    if (renderer->width <= 0 || renderer->height <= 0) return 0;
    vkDeviceWaitIdle(renderer->device);
    destroy_swapchain(renderer);
    return create_swapchain(renderer);
}

LinuxVulkanProjection* linux_vulkan_projection_create(
    uintptr_t display,
    uintptr_t parent_surface,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    const LinuxVulkanProjectionConfiguration* configuration
) {
    if (!display || !parent_surface || width <= 0 || height <= 0 || !configuration) return NULL;
    LinuxVulkanProjection* renderer = calloc(1, sizeof(*renderer));
    if (!renderer) return NULL;
    pthread_mutex_init(&renderer->lock, NULL);
    renderer->display = (struct wl_display*)display;
    renderer->parent_surface = (struct wl_surface*)parent_surface;
    renderer->x = x;
    renderer->y = y;
    renderer->width = width;
    renderer->height = height;
    renderer->configuration = *configuration;
    if (!create_wayland_surface(renderer) ||
        !create_vulkan_instance(renderer) ||
        !create_vulkan_device(renderer) ||
        !create_renderer_resources(renderer)) {
        linux_vulkan_projection_destroy(renderer);
        return NULL;
    }
    renderer->state = NVP_WAYLAND_OUTPUT_ATTACHED;
    return renderer;
}

int linux_vulkan_projection_update_geometry(
    LinuxVulkanProjection* renderer,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height
) {
    if (!renderer || width <= 0 || height <= 0) return 0;
    pthread_mutex_lock(&renderer->lock);
    int size_changed = renderer->width != width || renderer->height != height;
    renderer->x = x;
    renderer->y = y;
    renderer->width = width;
    renderer->height = height;
    wl_subsurface_set_position(renderer->subsurface, x, y);
    wl_surface_commit(renderer->surface);
    wl_surface_commit(renderer->parent_surface);
    wl_display_flush(renderer->display);
    int result = !size_changed || recreate_swapchain(renderer);
    renderer->state = NVP_WAYLAND_OUTPUT_ATTACHED;
    if (!result) renderer->failed = 1;
    pthread_mutex_unlock(&renderer->lock);
    return result;
}

void linux_vulkan_projection_update_configuration(
    LinuxVulkanProjection* renderer,
    const LinuxVulkanProjectionConfiguration* configuration
) {
    if (!renderer || !configuration) return;
    pthread_mutex_lock(&renderer->lock);
    int output_transfer_changed = renderer->configuration.output_transfer != configuration->output_transfer;
    int hdr10_plus_changed =
        renderer->configuration.applies_hdr10_plus != configuration->applies_hdr10_plus;
    renderer->configuration = *configuration;
    if (hdr10_plus_changed || !configuration->applies_hdr10_plus) {
        renderer->hdr10_plus_curve_valid = 0;
        renderer->hdr10_plus_source_peak_nits = 0.0f;
        memset(renderer->hdr10_plus_curve, 0, sizeof(renderer->hdr10_plus_curve));
        renderer->state &= ~(
            NVP_WAYLAND_OUTPUT_HDR10_PLUS_APPLIED |
            NVP_WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE
        );
    }
    if (output_transfer_changed && !recreate_swapchain(renderer)) renderer->failed = 1;
    else set_hdr_metadata(renderer);
    pthread_mutex_unlock(&renderer->lock);
}

int linux_vulkan_projection_update_hdr10_plus_metadata(
    LinuxVulkanProjection* renderer,
    const uint8_t* payload,
    size_t payload_size
) {
    if (!renderer) return 0;
    pthread_mutex_lock(&renderer->lock);
    if (!renderer->configuration.applies_hdr10_plus) {
        renderer->hdr10_plus_curve_valid = 0;
        renderer->state &= ~(
            NVP_WAYLAND_OUTPUT_HDR10_PLUS_APPLIED |
            NVP_WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE
        );
        pthread_mutex_unlock(&renderer->lock);
        return 1;
    }

    char error[256];
    float source_peak_nits = 0.0f;
    float curve[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT];
    const double display_peak_nits =
        isfinite(renderer->configuration.target_peak_nits) &&
        renderer->configuration.target_peak_nits > 0.0f
            ? renderer->configuration.target_peak_nits
            : 1000.0;
    int parsed = payload && payload_size > 0 &&
        kmp_hdr10_plus_parse_tone_curve(
            payload,
            payload_size,
            display_peak_nits,
            &source_peak_nits,
            curve,
            error,
            sizeof(error)
        );
    if (!parsed) {
        renderer->hdr10_plus_curve_valid = 0;
        renderer->state &= ~(
            NVP_WAYLAND_OUTPUT_HDR10_PLUS_APPLIED |
            NVP_WAYLAND_OUTPUT_FIRST_FRAME
        );
        renderer->state |= NVP_WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }

    renderer->hdr10_plus_source_peak_nits = source_peak_nits;
    memcpy(renderer->hdr10_plus_curve, curve, sizeof(curve));
    renderer->hdr10_plus_curve_valid = 1;
    renderer->state &= ~NVP_WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE;
    pthread_mutex_unlock(&renderer->lock);
    return 1;
}

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
) {
    if (!renderer || !luma || !chroma || width <= 0 || height <= 0) return 0;
    const size_t luma_row_bytes = (size_t)(uint32_t)width * 2u;
    const size_t chroma_width = ((size_t)(uint32_t)width + 1u) / 2u;
    const size_t chroma_height = ((size_t)(uint32_t)height + 1u) / 2u;
    const size_t chroma_row_bytes = chroma_width * 4u;
    if (luma_stride < 0 || chroma_stride < 0 ||
        (size_t)luma_stride < luma_row_bytes || (size_t)chroma_stride < chroma_row_bytes) return 0;

    pthread_mutex_lock(&renderer->lock);
    if (
        (renderer->configuration.applies_hdr10_plus && !renderer->hdr10_plus_curve_valid) ||
        input_transfer != renderer->configuration.transfer ||
        renderer->failed ||
        !create_input_images(renderer, width, height)) {
        renderer->failed = 1;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }
    if (vkWaitForFences(renderer->device, 1, &renderer->render_fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) {
        renderer->failed = 1;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }
    uint8_t* destination = renderer->staging_mapped;
    for (int32_t row = 0; row < height; row++) {
        memcpy(destination + (size_t)row * luma_row_bytes, luma + (size_t)row * (size_t)luma_stride, luma_row_bytes);
    }
    const size_t luma_size = luma_row_bytes * (size_t)height;
    for (size_t row = 0; row < chroma_height; row++) {
        memcpy(
            destination + luma_size + row * chroma_row_bytes,
            chroma + row * (size_t)chroma_stride,
            chroma_row_bytes
        );
    }
    HdrUniforms uniforms = build_uniforms(renderer);
    memcpy(renderer->uniform_mapped, &uniforms, sizeof(uniforms));

    uint32_t image_index = 0;
    VkResult acquire = vkAcquireNextImageKHR(
        renderer->device,
        renderer->swapchain,
        UINT64_MAX,
        renderer->image_available,
        VK_NULL_HANDLE,
        &image_index
    );
    if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
        int recreated = recreate_swapchain(renderer);
        pthread_mutex_unlock(&renderer->lock);
        return recreated;
    }
    if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
        renderer->failed = 1;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }
    vkResetFences(renderer->device, 1, &renderer->render_fence);
    vkResetCommandBuffer(renderer->command_buffer, 0);
    VkCommandBufferBeginInfo begin_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (vkBeginCommandBuffer(renderer->command_buffer, &begin_info) != VK_SUCCESS) {
        renderer->failed = 1;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }
    VkImageLayout old_layout = renderer->input_initialized
        ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        : VK_IMAGE_LAYOUT_UNDEFINED;
    VkAccessFlags old_access = renderer->input_initialized ? VK_ACCESS_SHADER_READ_BIT : 0;
    VkPipelineStageFlags old_stage = renderer->input_initialized
        ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
        : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    image_barrier(
        renderer->command_buffer,
        renderer->luma_image,
        old_layout,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        old_access,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        old_stage,
        VK_PIPELINE_STAGE_TRANSFER_BIT
    );
    image_barrier(
        renderer->command_buffer,
        renderer->chroma_image,
        old_layout,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        old_access,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        old_stage,
        VK_PIPELINE_STAGE_TRANSFER_BIT
    );
    VkBufferImageCopy luma_copy = {
        .bufferOffset = 0,
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageExtent = {(uint32_t)width, (uint32_t)height, 1},
    };
    VkBufferImageCopy chroma_copy = {
        .bufferOffset = luma_size,
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageExtent = {(uint32_t)chroma_width, (uint32_t)chroma_height, 1},
    };
    vkCmdCopyBufferToImage(
        renderer->command_buffer,
        renderer->staging_buffer,
        renderer->luma_image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &luma_copy
    );
    vkCmdCopyBufferToImage(
        renderer->command_buffer,
        renderer->staging_buffer,
        renderer->chroma_image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &chroma_copy
    );
    image_barrier(
        renderer->command_buffer,
        renderer->luma_image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
    );
    image_barrier(
        renderer->command_buffer,
        renderer->chroma_image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
    );
    VkClearValue clear = {.color = {{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo render_pass_info = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
        .renderPass = renderer->render_pass,
        .framebuffer = renderer->framebuffers[image_index],
        .renderArea = {.offset = {0, 0}, .extent = renderer->extent},
        .clearValueCount = 1,
        .pClearValues = &clear,
    };
    vkCmdBeginRenderPass(renderer->command_buffer, &render_pass_info, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(renderer->command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, renderer->pipeline);
    vkCmdBindDescriptorSets(
        renderer->command_buffer,
        VK_PIPELINE_BIND_POINT_GRAPHICS,
        renderer->pipeline_layout,
        0,
        1,
        &renderer->descriptor_set,
        0,
        NULL
    );
    VkViewport viewport = {
        .x = 0.0f,
        .y = 0.0f,
        .width = (float)renderer->extent.width,
        .height = (float)renderer->extent.height,
        .minDepth = 0.0f,
        .maxDepth = 1.0f,
    };
    VkRect2D scissor = {.offset = {0, 0}, .extent = renderer->extent};
    vkCmdSetViewport(renderer->command_buffer, 0, 1, &viewport);
    vkCmdSetScissor(renderer->command_buffer, 0, 1, &scissor);
    vkCmdDraw(renderer->command_buffer, 3, 1, 0, 0);
    vkCmdEndRenderPass(renderer->command_buffer);
    if (vkEndCommandBuffer(renderer->command_buffer) != VK_SUCCESS) {
        renderer->failed = 1;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit_info = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderer->image_available,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1,
        .pCommandBuffers = &renderer->command_buffer,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &renderer->render_finished,
    };
    if (vkQueueSubmit(renderer->queue, 1, &submit_info, renderer->render_fence) != VK_SUCCESS) {
        renderer->failed = 1;
        pthread_mutex_unlock(&renderer->lock);
        return 0;
    }
    VkPresentInfoKHR present_info = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderer->render_finished,
        .swapchainCount = 1,
        .pSwapchains = &renderer->swapchain,
        .pImageIndices = &image_index,
    };
    VkResult present = vkQueuePresentKHR(renderer->queue, &present_info);
    if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR) {
        if (!recreate_swapchain(renderer)) renderer->failed = 1;
    } else if (present != VK_SUCCESS) {
        renderer->failed = 1;
    }
    renderer->input_initialized = 1;
    if (!renderer->failed) {
        renderer->state =
            NVP_WAYLAND_OUTPUT_ATTACHED |
            NVP_WAYLAND_OUTPUT_CAPS_NEGOTIATED |
            NVP_WAYLAND_OUTPUT_TEN_BIT |
            NVP_WAYLAND_OUTPUT_FIRST_FRAME |
            (renderer->configuration.output_transfer == 1
                ? NVP_WAYLAND_OUTPUT_HLG
                : NVP_WAYLAND_OUTPUT_PQ) |
            (renderer->configuration.applies_hdr10_plus && renderer->hdr10_plus_curve_valid
                ? NVP_WAYLAND_OUTPUT_HDR10_PLUS_APPLIED
                : 0) |
            (input_is_dmabuf ? NVP_WAYLAND_OUTPUT_DMABUF : 0);
    }
    int success = !renderer->failed;
    pthread_mutex_unlock(&renderer->lock);
    return success;
}

int32_t linux_vulkan_projection_get_state(LinuxVulkanProjection* renderer) {
    if (!renderer) return 0;
    pthread_mutex_lock(&renderer->lock);
    int32_t state = renderer->state | (renderer->failed ? NVP_WAYLAND_OUTPUT_ERROR : 0);
    pthread_mutex_unlock(&renderer->lock);
    return state;
}

void linux_vulkan_projection_destroy(LinuxVulkanProjection* renderer) {
    if (!renderer) return;
    pthread_mutex_lock(&renderer->lock);
    if (renderer->device) vkDeviceWaitIdle(renderer->device);
    destroy_input_images(renderer);
    destroy_swapchain(renderer);
    if (renderer->render_fence) vkDestroyFence(renderer->device, renderer->render_fence, NULL);
    if (renderer->render_finished) vkDestroySemaphore(renderer->device, renderer->render_finished, NULL);
    if (renderer->image_available) vkDestroySemaphore(renderer->device, renderer->image_available, NULL);
    if (renderer->command_pool) vkDestroyCommandPool(renderer->device, renderer->command_pool, NULL);
    if (renderer->sampler) vkDestroySampler(renderer->device, renderer->sampler, NULL);
    if (renderer->uniform_mapped) vkUnmapMemory(renderer->device, renderer->uniform_memory);
    if (renderer->uniform_buffer) vkDestroyBuffer(renderer->device, renderer->uniform_buffer, NULL);
    if (renderer->uniform_memory) vkFreeMemory(renderer->device, renderer->uniform_memory, NULL);
    if (renderer->descriptor_pool) vkDestroyDescriptorPool(renderer->device, renderer->descriptor_pool, NULL);
    if (renderer->pipeline_layout) vkDestroyPipelineLayout(renderer->device, renderer->pipeline_layout, NULL);
    if (renderer->descriptor_layout) vkDestroyDescriptorSetLayout(renderer->device, renderer->descriptor_layout, NULL);
    if (renderer->device) vkDestroyDevice(renderer->device, NULL);
    if (renderer->vk_surface) vkDestroySurfaceKHR(renderer->instance, renderer->vk_surface, NULL);
    if (renderer->instance) vkDestroyInstance(renderer->instance, NULL);
    if (renderer->subsurface) wl_subsurface_destroy(renderer->subsurface);
    if (renderer->surface) wl_surface_destroy(renderer->surface);
    if (renderer->subcompositor) wl_subcompositor_destroy(renderer->subcompositor);
    if (renderer->compositor) wl_compositor_destroy(renderer->compositor);
    if (renderer->registry) wl_registry_destroy(renderer->registry);
    if (renderer->event_queue) wl_event_queue_destroy(renderer->event_queue);
    if (renderer->display) wl_display_flush(renderer->display);
    pthread_mutex_unlock(&renderer->lock);
    pthread_mutex_destroy(&renderer->lock);
    free(renderer);
}
