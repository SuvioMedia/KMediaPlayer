cmake_minimum_required(VERSION 3.22)
project(kmediaass LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(KMEDIA_ASS_COMMON_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native/common")

add_library(kmedia_ass_compositor STATIC
    "${KMEDIA_ASS_COMMON_NATIVE_DIR}/AssRgbaCompositor.c"
)
target_include_directories(kmedia_ass_compositor PUBLIC
    "${KMEDIA_ASS_COMMON_NATIVE_DIR}"
)
target_compile_options(kmedia_ass_compositor PRIVATE
    -Wall -Wextra -Wpedantic
)

# A normal host configure intentionally builds only the pure-C compositor,
# which keeps its clipping/blending/overflow behavior easy to smoke-test.
if(NOT ANDROID)
    include(CTest)
    if(BUILD_TESTING)
        add_executable(kmedia_ass_compositor_smoke
            tests/compositor_smoke.c
        )
        target_link_libraries(kmedia_ass_compositor_smoke PRIVATE
            kmedia_ass_compositor
        )
        target_compile_options(kmedia_ass_compositor_smoke PRIVATE
            -Wall -Wextra -Wpedantic
        )
        add_test(NAME kmedia_ass_compositor_smoke
            COMMAND kmedia_ass_compositor_smoke)
    endif()
    message(STATUS "Host build: only kmedia_ass_compositor will be generated")
    return()
endif()

set(LIBASS_INCLUDE_DIR "" CACHE PATH
    "Directory containing ass/ass.h from the Android libass build")
set(LIBASS_SHARED_LIBRARY "" CACHE FILEPATH
    "Path to the ABI-specific Android libkmediaasscore.so")

if(NOT LIBASS_INCLUDE_DIR)
    find_path(LIBASS_INCLUDE_DIR ass/ass.h)
endif()
if(NOT LIBASS_SHARED_LIBRARY)
    find_library(LIBASS_SHARED_LIBRARY NAMES kmediaasscore)
endif()
if(NOT EXISTS "${LIBASS_INCLUDE_DIR}/ass/ass.h")
    message(FATAL_ERROR "Set LIBASS_INCLUDE_DIR to a directory containing ass/ass.h")
endif()
if(NOT EXISTS "${LIBASS_SHARED_LIBRARY}")
    message(FATAL_ERROR "Set LIBASS_SHARED_LIBRARY to the ABI-specific libass.so")
endif()
get_filename_component(LIBASS_LIBRARY_NAME "${LIBASS_SHARED_LIBRARY}" NAME)
if(NOT LIBASS_LIBRARY_NAME STREQUAL "libkmediaasscore.so")
    message(FATAL_ERROR
        "LIBASS_SHARED_LIBRARY must be libkmediaasscore.so; generic/static libass is intentionally unsupported")
endif()

add_library(kmedia_libass SHARED IMPORTED)
set_target_properties(kmedia_libass PROPERTIES
    IMPORTED_LOCATION "${LIBASS_SHARED_LIBRARY}"
)

find_library(ANDROID_LOG_LIBRARY log)
if(NOT ANDROID_LOG_LIBRARY)
    message(FATAL_ERROR "Android liblog was not found")
endif()

add_library(kmediaass SHARED
    AndroidAssNativeBridge.c
)
set_target_properties(kmediaass PROPERTIES
    OUTPUT_NAME kmediaass
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
target_include_directories(kmediaass PRIVATE
    "${LIBASS_INCLUDE_DIR}"
)
target_compile_options(kmediaass PRIVATE
    -Wall -Wextra
)
target_link_libraries(kmediaass PRIVATE
    kmedia_ass_compositor
    kmedia_libass
    "${ANDROID_LOG_LIBRARY}"
)
target_link_options(kmediaass PRIVATE
    "-Wl,--no-undefined"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/kmediaass.map.txt"
)
set_property(TARGET kmediaass APPEND PROPERTY
    LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/kmediaass.map.txt")
