cmake_minimum_required(VERSION 3.15)
project(NativeVideoPlayer C)

set(CMAKE_C_STANDARD 11)
set(HDR10_PLUS_COMMON_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../common")

# Find GStreamer via pkg-config
find_package(PkgConfig REQUIRED)
find_package(Vulkan REQUIRED)
find_program(GLSLC_EXECUTABLE glslc REQUIRED)
pkg_check_modules(GST REQUIRED
    gstreamer-1.0
    gstreamer-app-1.0
    gstreamer-allocators-1.0
    gstreamer-video-1.0
)
pkg_check_modules(WAYLAND REQUIRED wayland-client)
pkg_check_modules(GTK3 REQUIRED gtk+-3.0)

# Find JNI
find_package(JNI REQUIRED)

set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

function(compile_shader SOURCE_FILE SYMBOL OUTPUT_HEADER)
    set(SPIRV_FILE "${GENERATED_SHADER_DIR}/${SOURCE_FILE}.spv")
    set(HEADER_FILE "${GENERATED_SHADER_DIR}/${OUTPUT_HEADER}")
    add_custom_command(
        OUTPUT "${HEADER_FILE}"
        COMMAND "${GLSLC_EXECUTABLE}"
            --target-env=vulkan1.1
            "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SOURCE_FILE}"
            -o "${SPIRV_FILE}"
        COMMAND "${CMAKE_COMMAND}"
            -DINPUT=${SPIRV_FILE}
            -DOUTPUT=${HEADER_FILE}
            -DSYMBOL=${SYMBOL}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/BinaryToHeader.cmake"
        DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SOURCE_FILE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/cmake/BinaryToHeader.cmake"
        VERBATIM
    )
    set(${OUTPUT_HEADER} "${HEADER_FILE}" PARENT_SCOPE)
endfunction()

compile_shader(
    "LinuxHdrProjection.vert"
    "linux_hdr_projection_vert_spv"
    "LinuxHdrProjection.vert.spv.h"
)
compile_shader(
    "LinuxHdrProjection.frag"
    "linux_hdr_projection_frag_spv"
    "LinuxHdrProjection.frag.spv.h"
)

# Source files
set(SOURCES
    NativeVideoPlayer.c
    GtkNativeVideoWidget.c
    WaylandColorProbe.c
    VulkanCapabilityProbe.c
    LinuxVulkanProjectionLoader.c
    LibVlcCanvas.c
    jni_bridge.c
    "${HDR10_PLUS_COMMON_DIR}/Hdr10PlusToneCurve.c"
)

add_library(NativeVideoPlayer SHARED ${SOURCES})

add_library(KMediaPlayerVulkanProjection SHARED
    LinuxVulkanProjection.c
    "${HDR10_PLUS_COMMON_DIR}/Hdr10PlusToneCurve.c"
    "${LinuxHdrProjection.vert.spv.h}"
    "${LinuxHdrProjection.frag.spv.h}"
)
target_compile_definitions(KMediaPlayerVulkanProjection PRIVATE
    linux_vulkan_texture_create=kmp_vulkan_texture_create
    linux_vulkan_texture_update=kmp_vulkan_texture_update
    linux_vulkan_texture_acquire_frame=kmp_vulkan_texture_acquire_frame
    linux_vulkan_texture_release_frame=kmp_vulkan_texture_release_frame
    linux_vulkan_texture_render_bgra=kmp_vulkan_texture_render_bgra
    linux_vulkan_projection_create=kmp_vulkan_projection_create
    linux_vulkan_projection_update_geometry=kmp_vulkan_projection_update_geometry
    linux_vulkan_projection_update_configuration=kmp_vulkan_projection_update_configuration
    linux_vulkan_projection_update_hdr10_plus_metadata=kmp_vulkan_projection_update_hdr10_plus_metadata
    linux_vulkan_projection_render_p010=kmp_vulkan_projection_render_p010
    linux_vulkan_projection_get_state=kmp_vulkan_projection_get_state
    linux_vulkan_projection_destroy=kmp_vulkan_projection_destroy
)

target_include_directories(NativeVideoPlayer PRIVATE
    ${GST_INCLUDE_DIRS}
    ${WAYLAND_INCLUDE_DIRS}
    ${GTK3_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
    "${GENERATED_SHADER_DIR}"
    "${HDR10_PLUS_COMMON_DIR}"
)

target_include_directories(KMediaPlayerVulkanProjection PRIVATE
    ${WAYLAND_INCLUDE_DIRS}
    "${GENERATED_SHADER_DIR}"
    "${HDR10_PLUS_COMMON_DIR}"
)

target_link_libraries(NativeVideoPlayer PRIVATE
    ${GST_LIBRARIES}
    ${WAYLAND_LIBRARIES}
    ${GTK3_LIBRARIES}
    dl
    m
    pthread
)

target_link_libraries(KMediaPlayerVulkanProjection PRIVATE
    ${WAYLAND_LIBRARIES}
    Vulkan::Vulkan
    m
    pthread
)

target_compile_options(NativeVideoPlayer PRIVATE
    ${GST_CFLAGS_OTHER}
    ${WAYLAND_CFLAGS_OTHER}
    ${GTK3_CFLAGS_OTHER}
    -Wall -Wextra -Wno-unused-parameter
    -O2
    -fPIC
)

target_compile_options(KMediaPlayerVulkanProjection PRIVATE
    ${WAYLAND_CFLAGS_OTHER}
    -Wall -Wextra -Wno-unused-parameter
    -O2
    -fPIC
)

# Determine output directory based on architecture
if(DEFINED ENV{NATIVE_LIBS_OUTPUT_DIR})
    set(BASE_OUTPUT_DIR "$ENV{NATIVE_LIBS_OUTPUT_DIR}")
else()
    set(BASE_OUTPUT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/composemediaplayer/native")
endif()

execute_process(COMMAND uname -m OUTPUT_VARIABLE ARCH OUTPUT_STRIP_TRAILING_WHITESPACE)
if(ARCH STREQUAL "x86_64" OR ARCH STREQUAL "amd64")
    set(RESOURCE_DIR "${BASE_OUTPUT_DIR}/linux-x86-64")
elseif(ARCH STREQUAL "aarch64" OR ARCH STREQUAL "arm64")
    set(RESOURCE_DIR "${BASE_OUTPUT_DIR}/linux-aarch64")
else()
    set(RESOURCE_DIR "${BASE_OUTPUT_DIR}/linux-${ARCH}")
endif()

# Copy library to resources after build
add_custom_command(TARGET NativeVideoPlayer POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${RESOURCE_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:NativeVideoPlayer>" "${RESOURCE_DIR}/"
    COMMENT "Copying libNativeVideoPlayer.so to ${RESOURCE_DIR}"
)

add_custom_command(TARGET KMediaPlayerVulkanProjection POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${RESOURCE_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:KMediaPlayerVulkanProjection>" "${RESOURCE_DIR}/"
    COMMENT "Copying libKMediaPlayerVulkanProjection.so to ${RESOURCE_DIR}"
)

enable_testing()
add_executable(Hdr10PlusToneCurveTest
    "${HDR10_PLUS_COMMON_DIR}/Hdr10PlusToneCurve.c"
    "${HDR10_PLUS_COMMON_DIR}/Hdr10PlusToneCurveTest.c"
)
target_include_directories(Hdr10PlusToneCurveTest PRIVATE "${HDR10_PLUS_COMMON_DIR}")
target_link_libraries(Hdr10PlusToneCurveTest PRIVATE m)
add_test(NAME Hdr10PlusToneCurveTest COMMAND Hdr10PlusToneCurveTest)

add_executable(IctcpGamutLutTest
    "${HDR10_PLUS_COMMON_DIR}/IctcpGamutLut.c"
    "${HDR10_PLUS_COMMON_DIR}/IctcpGamutLutTest.c"
)
target_include_directories(IctcpGamutLutTest PRIVATE "${HDR10_PLUS_COMMON_DIR}")
target_link_libraries(IctcpGamutLutTest PRIVATE m)
add_test(NAME IctcpGamutLutTest COMMAND IctcpGamutLutTest)
