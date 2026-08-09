cmake_minimum_required(VERSION 3.20)
project(ComposeMediaPlayerMpvWindows LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(ComposeMediaPlayerMpvWindows SHARED MpvWindowsTextureBridge.cpp)
target_include_directories(ComposeMediaPlayerMpvWindows PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(ComposeMediaPlayerMpvWindows PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_compile_options(ComposeMediaPlayerMpvWindows PRIVATE /W4 /WX /permissive-)
target_link_libraries(ComposeMediaPlayerMpvWindows PRIVATE d3d11 dxgi)

if(CMAKE_GENERATOR_PLATFORM STREQUAL "ARM64")
    set(KMP_MPV_WINDOWS_ARCH "win32-arm64")
else()
    set(KMP_MPV_WINDOWS_ARCH "win32-x86-64")
endif()

set_target_properties(
    ComposeMediaPlayerMpvWindows
    PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY
            "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/composemediaplayer/native/${KMP_MPV_WINDOWS_ARCH}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE
            "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/composemediaplayer/native/${KMP_MPV_WINDOWS_ARCH}"
)
