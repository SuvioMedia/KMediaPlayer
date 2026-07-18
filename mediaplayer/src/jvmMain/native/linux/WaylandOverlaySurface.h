#ifndef WAYLAND_OVERLAY_SURFACE_H
#define WAYLAND_OVERLAY_SURFACE_H

#include <stddef.h>
#include <stdint.h>

typedef struct WaylandOverlaySurface WaylandOverlaySurface;

WaylandOverlaySurface* wayland_overlay_surface_create(
    uintptr_t display,
    uintptr_t surface
);

int wayland_overlay_surface_make_input_transparent(
    WaylandOverlaySurface* renderer,
    uintptr_t surface
);

/*
 * Uploads premultiplied native-order BGRA pixels to an ARGB8888 wl_shm buffer.
 * Returns 1 when the frame was committed, 2 when deliberately dropped because
 * compositor-owned buffers are busy, and 0 for an unrecoverable error.
 */
int wayland_overlay_surface_update(
    WaylandOverlaySurface* renderer,
    const void* pixels,
    size_t row_bytes,
    int32_t width,
    int32_t height
);

void wayland_overlay_surface_clear(WaylandOverlaySurface* renderer);
void wayland_overlay_surface_destroy(WaylandOverlaySurface* renderer);

#endif
