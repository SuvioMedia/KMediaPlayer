cmake_minimum_required(VERSION 3.20)
project(ComposeMediaPlayerMpvLinux LANGUAGES CXX)

find_package(JNI REQUIRED)

add_library(ComposeMediaPlayerMpvLinux SHARED MpvLinuxTextureBridge.cpp)
target_compile_features(ComposeMediaPlayerMpvLinux PRIVATE cxx_std_17)
target_include_directories(ComposeMediaPlayerMpvLinux PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(ComposeMediaPlayerMpvLinux PRIVATE dl pthread)
set_target_properties(
    ComposeMediaPlayerMpvLinux
    PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)
