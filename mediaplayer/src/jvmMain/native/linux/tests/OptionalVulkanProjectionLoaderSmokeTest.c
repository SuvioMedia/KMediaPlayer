#include <dlfcn.h>
#include <stdio.h>

#include "LinuxVulkanProjection.h"

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s VULKAN_PROJECTION_LIBRARY\n", argv[0]);
        return 2;
    }
    void* optional_library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!optional_library) {
        fprintf(stderr, "failed to load optional Vulkan renderer: %s\n", dlerror());
        return 1;
    }
    if (!linux_vulkan_projection_library_available()) {
        fprintf(stderr, "core library could not resolve the preloaded optional Vulkan renderer\n");
        dlclose(optional_library);
        return 1;
    }
    puts("OPTIONAL_VULKAN_PROJECTION_LOADER_OK");
    dlclose(optional_library);
    return 0;
}
