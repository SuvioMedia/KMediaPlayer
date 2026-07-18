#ifndef JBR_WAYLAND_SURFACE_H
#define JBR_WAYLAND_SURFACE_H

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

struct WaylandOverlaySurface;

typedef struct JbrWaylandSurface {
    jobject component;
    jobject main_surface;
    jobject video_surface;
    jobject overlay_surface;
    int64_t display_ptr;
    int64_t surface_ptr;
    int64_t video_surface_ptr;
    int64_t overlay_surface_ptr;
    int64_t video_subsurface_ptr;
    int64_t overlay_subsurface_ptr;
    int32_t has_subsurface_pair;
    struct WaylandOverlaySurface* overlay_renderer;
    int32_t output_id;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    int32_t buffer_width;
    int32_t buffer_height;
} JbrWaylandSurface;

typedef int (*JbrWaylandDisplayCallback)(
    uintptr_t display_ptr,
    int32_t output_id,
    void* user_data
);

int jbr_wayland_api_available(JNIEnv* env);
int jbr_wayland_with_display(
    JNIEnv* env,
    int32_t requested_output_id,
    JbrWaylandDisplayCallback callback,
    void* user_data
);
JbrWaylandSurface* jbr_wayland_surface_capture(JNIEnv* env, jobject component);
int jbr_wayland_surface_refresh(JNIEnv* env, JbrWaylandSurface* surface);
int jbr_wayland_surface_update_overlay(
    JNIEnv* env,
    JbrWaylandSurface* surface,
    const void* pixels,
    size_t row_bytes,
    int32_t width,
    int32_t height
);
void jbr_wayland_surface_clear_overlay(JNIEnv* env, JbrWaylandSurface* surface);
void jbr_wayland_surface_destroy(JNIEnv* env, JbrWaylandSurface* surface);

#endif
