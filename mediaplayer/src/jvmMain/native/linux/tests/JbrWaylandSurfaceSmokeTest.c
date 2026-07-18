#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "JbrWaylandSurface.h"
#include "WaylandColorProbe.h"

static int query_color(
    uintptr_t display_ptr,
    int32_t output_id,
    void* user_data
) {
    return wayland_color_probe_query(
        display_ptr,
        output_id,
        (WaylandColorProbeResult*)user_data
    );
}

JNIEXPORT jlongArray JNICALL Java_JbrWaylandSurfaceSmokeTest_capture(
    JNIEnv* env,
    jclass cls,
    jobject component
) {
    (void)cls;
    JbrWaylandSurface* surface = jbr_wayland_surface_capture(env, component);
    if (!surface) return NULL;

    WaylandColorProbeResult color;
    memset(&color, 0, sizeof(color));
    color.output_id = surface->output_id;
    int color_queried =
        jbr_wayland_with_display(env, surface->output_id, query_color, &color);

    int overlay_result = 0;
    if (surface->has_subsurface_pair &&
        surface->buffer_width > 0 && surface->buffer_height > 0) {
        size_t row_bytes = (size_t)surface->buffer_width * 4u;
        size_t byte_count = row_bytes * (size_t)surface->buffer_height;
        uint8_t* pixels = calloc(1, byte_count);
        if (pixels) {
            for (size_t offset = 0; offset < byte_count; offset += 4) {
                pixels[offset + 0] = 0x20;
                pixels[offset + 1] = 0x40;
                pixels[offset + 2] = 0x80;
                pixels[offset + 3] = 0x80;
            }
            overlay_result = jbr_wayland_surface_update_overlay(
                env,
                surface,
                pixels,
                row_bytes,
                surface->buffer_width,
                surface->buffer_height
            );
            jbr_wayland_surface_clear_overlay(env, surface);
            free(pixels);
        }
    }

    jlong values[17] = {
        (jlong)surface->display_ptr,
        (jlong)surface->surface_ptr,
        (jlong)surface->output_id,
        (jlong)surface->x,
        (jlong)surface->y,
        (jlong)surface->width,
        (jlong)surface->height,
        (jlong)color_queried,
        (jlong)color.flags,
        (jlong)color.output_id,
        (jlong)jbr_wayland_surface_refresh(env, surface),
        (jlong)surface->has_subsurface_pair,
        (jlong)surface->video_surface_ptr,
        (jlong)surface->overlay_surface_ptr,
        (jlong)surface->buffer_width,
        (jlong)surface->buffer_height,
        (jlong)overlay_result,
    };
    jbr_wayland_surface_destroy(env, surface);

    jlongArray result = (*env)->NewLongArray(env, 17);
    if (!result) return NULL;
    (*env)->SetLongArrayRegion(env, result, 0, 17, values);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}
