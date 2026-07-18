#include "LinuxVulkanProjection.h"

#include <dlfcn.h>
#include <pthread.h>

typedef LinuxVulkanProjection* (*CreateFunction)(
    uintptr_t,
    uintptr_t,
    int32_t,
    int32_t,
    int32_t,
    int32_t,
    const LinuxVulkanProjectionConfiguration*
);
typedef int (*UpdateGeometryFunction)(LinuxVulkanProjection*, int32_t, int32_t, int32_t, int32_t);
typedef void (*UpdateConfigurationFunction)(
    LinuxVulkanProjection*,
    const LinuxVulkanProjectionConfiguration*
);
typedef int (*UpdateHdr10PlusMetadataFunction)(
    LinuxVulkanProjection*,
    const uint8_t*,
    size_t
);
typedef int (*RenderFunction)(
    LinuxVulkanProjection*,
    const uint8_t*,
    int32_t,
    const uint8_t*,
    int32_t,
    int32_t,
    int32_t,
    int32_t,
    int32_t
);
typedef int32_t (*GetStateFunction)(LinuxVulkanProjection*);
typedef void (*DestroyFunction)(LinuxVulkanProjection*);

typedef struct ProjectionApi {
    void* library;
    CreateFunction create;
    UpdateGeometryFunction update_geometry;
    UpdateConfigurationFunction update_configuration;
    UpdateHdr10PlusMetadataFunction update_hdr10_plus_metadata;
    RenderFunction render;
    GetStateFunction get_state;
    DestroyFunction destroy;
    int attempted;
} ProjectionApi;

static pthread_mutex_t projection_api_lock = PTHREAD_MUTEX_INITIALIZER;
static ProjectionApi projection_api;

static int load_projection_api(void) {
    pthread_mutex_lock(&projection_api_lock);
    if (!projection_api.attempted) {
        projection_api.attempted = 1;
        projection_api.library = dlopen(
            "libKMediaPlayerVulkanProjection.so",
            RTLD_NOW | RTLD_LOCAL
        );
        if (projection_api.library) {
            projection_api.create =
                (CreateFunction)dlsym(projection_api.library, "kmp_vulkan_projection_create");
            projection_api.update_geometry =
                (UpdateGeometryFunction)dlsym(
                    projection_api.library,
                    "kmp_vulkan_projection_update_geometry"
                );
            projection_api.update_configuration =
                (UpdateConfigurationFunction)dlsym(
                    projection_api.library,
                    "kmp_vulkan_projection_update_configuration"
                );
            projection_api.update_hdr10_plus_metadata =
                (UpdateHdr10PlusMetadataFunction)dlsym(
                    projection_api.library,
                    "kmp_vulkan_projection_update_hdr10_plus_metadata"
                );
            projection_api.render =
                (RenderFunction)dlsym(projection_api.library, "kmp_vulkan_projection_render_p010");
            projection_api.get_state =
                (GetStateFunction)dlsym(projection_api.library, "kmp_vulkan_projection_get_state");
            projection_api.destroy =
                (DestroyFunction)dlsym(projection_api.library, "kmp_vulkan_projection_destroy");
            if (!projection_api.create ||
                !projection_api.update_geometry ||
                !projection_api.update_configuration ||
                !projection_api.update_hdr10_plus_metadata ||
                !projection_api.render ||
                !projection_api.get_state ||
                !projection_api.destroy) {
                dlclose(projection_api.library);
                projection_api.library = NULL;
            }
        }
    }
    int available = projection_api.library != NULL;
    pthread_mutex_unlock(&projection_api_lock);
    return available;
}

int linux_vulkan_projection_library_available(void) {
    return load_projection_api();
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
    if (!load_projection_api()) return NULL;
    return projection_api.create(
        display,
        parent_surface,
        x,
        y,
        width,
        height,
        configuration
    );
}

int linux_vulkan_projection_update_geometry(
    LinuxVulkanProjection* renderer,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height
) {
    return load_projection_api()
        ? projection_api.update_geometry(renderer, x, y, width, height)
        : 0;
}

void linux_vulkan_projection_update_configuration(
    LinuxVulkanProjection* renderer,
    const LinuxVulkanProjectionConfiguration* configuration
) {
    if (load_projection_api()) projection_api.update_configuration(renderer, configuration);
}

int linux_vulkan_projection_update_hdr10_plus_metadata(
    LinuxVulkanProjection* renderer,
    const uint8_t* payload,
    size_t payload_size
) {
    return load_projection_api()
        ? projection_api.update_hdr10_plus_metadata(renderer, payload, payload_size)
        : 0;
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
    return load_projection_api()
        ? projection_api.render(
            renderer,
            luma,
            luma_stride,
            chroma,
            chroma_stride,
            width,
            height,
            input_transfer,
            input_is_dmabuf
        )
        : 0;
}

int32_t linux_vulkan_projection_get_state(LinuxVulkanProjection* renderer) {
    return load_projection_api() ? projection_api.get_state(renderer) : 0;
}

void linux_vulkan_projection_destroy(LinuxVulkanProjection* renderer) {
    if (renderer && load_projection_api()) projection_api.destroy(renderer);
}
