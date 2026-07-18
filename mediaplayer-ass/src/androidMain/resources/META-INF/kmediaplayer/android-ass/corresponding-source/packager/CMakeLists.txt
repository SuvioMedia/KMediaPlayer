cmake_minimum_required(VERSION 3.22)
project(kmedia_ass_native_packager LANGUAGES C)

if(NOT ANDROID)
    message(FATAL_ERROR "This packager must use the Android NDK toolchain")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CORE_STATIC_ROOT "" CACHE PATH
    "ABI-specific libass-cmake install root containing lib/*.a")
set(CORE_LIB_DIR "${CORE_STATIC_ROOT}/lib")
set(FRIBIDI_STATIC_ARCHIVE "${CORE_LIB_DIR}/libfribidi.a" CACHE FILEPATH
    "PIC FriBidi archive to wrap as replaceable libkmediafribidi.so")
option(BUILD_KMEDIA_ASS_CORE
    "Also link the namespaced libass core and its permissive dependencies" ON)

if(NOT EXISTS "${FRIBIDI_STATIC_ARCHIVE}")
    message(FATAL_ERROR
        "Set FRIBIDI_STATIC_ARCHIVE to an ABI-compatible PIC libfribidi.a")
endif()

set(REQUIRED_ARCHIVES
    libass.a
    libexpat.a
    libfontconfig.a
    libfreetype.a
    libharfbuzz.a
    libunibreak.a
)
if(BUILD_KMEDIA_ASS_CORE)
    if(NOT CORE_STATIC_ROOT)
        message(FATAL_ERROR "Set CORE_STATIC_ROOT to an ABI-specific core build")
    endif()
    foreach(archive IN LISTS REQUIRED_ARCHIVES)
        if(NOT EXISTS "${CORE_LIB_DIR}/${archive}")
            message(FATAL_ERROR "Missing ${CORE_LIB_DIR}/${archive}")
        endif()
    endforeach()
endif()

# FriBidi remains unmodified, but is isolated in a replaceable shared object.
# Its public API keeps the standard fribidi_* names while the file and SONAME
# are namespaced to avoid collisions during Android mergeNativeLibs.
add_library(kmediafribidi SHARED anchor.c)
set_target_properties(kmediafribidi PROPERTIES
    OUTPUT_NAME kmediafribidi
    C_VISIBILITY_PRESET hidden
)
target_link_libraries(kmediafribidi PRIVATE
    "-Wl,--whole-archive"
    "${FRIBIDI_STATIC_ARCHIVE}"
    "-Wl,--no-whole-archive"
)
target_link_options(kmediafribidi PRIVATE
    "-Wl,--no-undefined"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/fribidi.exports.map"
)
set_property(TARGET kmediafribidi APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/fribidi.exports.map")

# libass and all permissively licensed dependencies remain in the core. FriBidi
# is deliberately omitted from the archive group and resolved through the
# replaceable shared target above.
if(BUILD_KMEDIA_ASS_CORE)
    add_library(kmediaasscore SHARED anchor.c)
    set_target_properties(kmediaasscore PROPERTIES
        OUTPUT_NAME kmediaasscore
        C_VISIBILITY_PRESET hidden
    )
    target_link_libraries(kmediaasscore PRIVATE
        "-Wl,--whole-archive"
        "${CORE_LIB_DIR}/libass.a"
        "-Wl,--no-whole-archive"
        "-Wl,--start-group"
        "${CORE_LIB_DIR}/libfontconfig.a"
        "${CORE_LIB_DIR}/libexpat.a"
        "${CORE_LIB_DIR}/libfreetype.a"
        "${CORE_LIB_DIR}/libharfbuzz.a"
        "${CORE_LIB_DIR}/libunibreak.a"
        "-Wl,--end-group"
        kmediafribidi
        z
    )
    target_link_options(kmediaasscore PRIVATE
        "-Wl,--no-undefined"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/libass.exports.map"
    )
    set_property(TARGET kmediaasscore APPEND PROPERTY LINK_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/libass.exports.map")
endif()
