#include "VulkanCapabilityProbe.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#define VK_USE_PLATFORM_WAYLAND_KHR 1
#include <vulkan/vulkan.h>

static int has_extension(
    const VkExtensionProperties* extensions,
    uint32_t count,
    const char* expected
) {
    for (uint32_t index = 0; index < count; index++) {
        if (strcmp(extensions[index].extensionName, expected) == 0) return 1;
    }
    return 0;
}

static unsigned int bit_count(uint32_t value) {
    unsigned int count = 0;
    while (value) {
        count += value & 1U;
        value >>= 1U;
    }
    return count;
}

uint32_t vulkan_capability_probe_query(void) {
    void* library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!library) return 0;

    PFN_vkGetInstanceProcAddr get_instance_proc_addr =
        (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
    if (!get_instance_proc_addr) {
        dlclose(library);
        return 0;
    }

    PFN_vkEnumerateInstanceVersion enumerate_instance_version =
        (PFN_vkEnumerateInstanceVersion)get_instance_proc_addr(NULL, "vkEnumerateInstanceVersion");
    uint32_t loader_version = VK_API_VERSION_1_0;
    if (enumerate_instance_version) enumerate_instance_version(&loader_version);

    PFN_vkEnumerateInstanceExtensionProperties enumerate_instance_extensions =
        (PFN_vkEnumerateInstanceExtensionProperties)get_instance_proc_addr(
            NULL,
            "vkEnumerateInstanceExtensionProperties"
        );
    PFN_vkCreateInstance create_instance =
        (PFN_vkCreateInstance)get_instance_proc_addr(NULL, "vkCreateInstance");
    if (!enumerate_instance_extensions || !create_instance) {
        dlclose(library);
        return 0;
    }

    uint32_t instance_extension_count = 0;
    if (enumerate_instance_extensions(NULL, &instance_extension_count, NULL) != VK_SUCCESS) {
        dlclose(library);
        return 0;
    }
    VkExtensionProperties* instance_extensions =
        instance_extension_count
            ? calloc(instance_extension_count, sizeof(*instance_extensions))
            : NULL;
    if (instance_extension_count && !instance_extensions) {
        dlclose(library);
        return 0;
    }
    if (instance_extension_count &&
        enumerate_instance_extensions(NULL, &instance_extension_count, instance_extensions) != VK_SUCCESS) {
        free(instance_extensions);
        dlclose(library);
        return 0;
    }

    uint32_t result = 0;
    if (has_extension(
            instance_extensions,
            instance_extension_count,
            VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME)) {
        result |= VCP_WAYLAND_SURFACE;
    }
    free(instance_extensions);

    uint32_t requested_api = loader_version >= VK_API_VERSION_1_2
        ? VK_API_VERSION_1_2
        : loader_version >= VK_API_VERSION_1_1
            ? VK_API_VERSION_1_1
            : VK_API_VERSION_1_0;
    const VkApplicationInfo application_info = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "ComposeMediaPlayer capability probe",
        .applicationVersion = 1,
        .pEngineName = "ComposeMediaPlayer",
        .engineVersion = 1,
        .apiVersion = requested_api,
    };
    const VkInstanceCreateInfo create_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &application_info,
    };
    VkInstance instance = VK_NULL_HANDLE;
    if (create_instance(&create_info, NULL, &instance) != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        dlclose(library);
        return 0;
    }
    result |= VCP_AVAILABLE;

    PFN_vkDestroyInstance destroy_instance =
        (PFN_vkDestroyInstance)get_instance_proc_addr(instance, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices enumerate_physical_devices =
        (PFN_vkEnumeratePhysicalDevices)get_instance_proc_addr(instance, "vkEnumeratePhysicalDevices");
    PFN_vkEnumerateDeviceExtensionProperties enumerate_device_extensions =
        (PFN_vkEnumerateDeviceExtensionProperties)get_instance_proc_addr(
            instance,
            "vkEnumerateDeviceExtensionProperties"
        );
    PFN_vkGetPhysicalDeviceProperties get_physical_device_properties =
        (PFN_vkGetPhysicalDeviceProperties)get_instance_proc_addr(instance, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceFeatures2 get_physical_device_features2 =
        (PFN_vkGetPhysicalDeviceFeatures2)get_instance_proc_addr(instance, "vkGetPhysicalDeviceFeatures2");

    if (!enumerate_physical_devices || !enumerate_device_extensions ||
        !get_physical_device_properties) {
        if (destroy_instance) destroy_instance(instance, NULL);
        dlclose(library);
        return result;
    }

    uint32_t device_count = 0;
    if (enumerate_physical_devices(instance, &device_count, NULL) != VK_SUCCESS || device_count == 0) {
        if (destroy_instance) destroy_instance(instance, NULL);
        dlclose(library);
        return result;
    }
    VkPhysicalDevice* devices = calloc(device_count, sizeof(*devices));
    if (!devices || enumerate_physical_devices(instance, &device_count, devices) != VK_SUCCESS) {
        free(devices);
        if (destroy_instance) destroy_instance(instance, NULL);
        dlclose(library);
        return result;
    }

    uint32_t best_device_flags = 0;
    for (uint32_t device_index = 0; device_index < device_count; device_index++) {
        uint32_t extension_count = 0;
        if (enumerate_device_extensions(
                devices[device_index],
                NULL,
                &extension_count,
                NULL) != VK_SUCCESS) {
            continue;
        }
        VkExtensionProperties* extensions =
            extension_count ? calloc(extension_count, sizeof(*extensions)) : NULL;
        if (extension_count && !extensions) continue;
        if (extension_count &&
            enumerate_device_extensions(
                devices[device_index],
                NULL,
                &extension_count,
                extensions) != VK_SUCCESS) {
            free(extensions);
            continue;
        }

        uint32_t flags = 0;
        if (has_extension(extensions, extension_count, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME)) {
            flags |= VCP_EXTERNAL_MEMORY_DMA_BUF;
        }
        if (has_extension(extensions, extension_count, VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME)) {
            flags |= VCP_IMAGE_DRM_FORMAT_MODIFIER;
        }
        if (has_extension(extensions, extension_count, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)) {
            flags |= VCP_EXTERNAL_MEMORY_FD;
        }
        free(extensions);

        VkPhysicalDeviceProperties properties;
        memset(&properties, 0, sizeof(properties));
        get_physical_device_properties(devices[device_index], &properties);
        if (get_physical_device_features2 && properties.apiVersion >= VK_API_VERSION_1_1) {
            VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr = {
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
            };
            VkPhysicalDeviceShaderFloat16Int8Features float16 = {
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES,
                .pNext = &ycbcr,
            };
            VkPhysicalDeviceFeatures2 features = {
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                .pNext = &float16,
            };
            get_physical_device_features2(devices[device_index], &features);
            if (float16.shaderFloat16) flags |= VCP_SHADER_FLOAT16;
            if (ycbcr.samplerYcbcrConversion) flags |= VCP_SAMPLER_YCBCR_CONVERSION;
        }

        if (bit_count(flags) > bit_count(best_device_flags)) best_device_flags = flags;
    }
    free(devices);
    if (destroy_instance) destroy_instance(instance, NULL);
    dlclose(library);
    return result | best_device_flags;
}
