#define _POSIX_C_SOURCE 200809L

#include "WaylandOverlaySurface.h"

#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <wayland-client.h>

#define OVERLAY_BUFFER_COUNT 3
#define OVERLAY_BYTES_PER_PIXEL 4
#define OVERLAY_MAX_DIMENSION 16384
#define OVERLAY_MAX_TOTAL_BYTES (512u * 1024u * 1024u)

typedef struct WaylandOverlayBuffer {
    struct wl_buffer* buffer;
    void* mapping;
    size_t size;
    _Atomic int busy;
    _Atomic int retired;
    _Atomic int cleaned;
} WaylandOverlayBuffer;

struct WaylandOverlaySurface {
    struct wl_display* display;
    struct wl_surface* surface;
    struct wl_event_queue* event_queue;
    struct wl_registry* registry;
    struct wl_compositor* compositor;
    struct wl_shm* shm;
    int32_t width;
    int32_t height;
    int32_t stride;
    WaylandOverlayBuffer* buffers[OVERLAY_BUFFER_COUNT];
};

static _Atomic uint32_t g_shm_sequence = 1;

static void core_registry_global(
    void* data,
    struct wl_registry* registry,
    uint32_t name,
    const char* interface,
    uint32_t version
) {
    WaylandOverlaySurface* renderer = (WaylandOverlaySurface*)data;
    if (!renderer->compositor && strcmp(interface, wl_compositor_interface.name) == 0) {
        uint32_t bind_version = version < 4 ? version : 4;
        renderer->compositor =
            (struct wl_compositor*)wl_registry_bind(
                registry,
                name,
                &wl_compositor_interface,
                bind_version
            );
    } else if (!renderer->shm && strcmp(interface, wl_shm_interface.name) == 0) {
        renderer->shm =
            (struct wl_shm*)wl_registry_bind(
                registry,
                name,
                &wl_shm_interface,
                1
            );
    }
}

static void core_registry_global_remove(
    void* data,
    struct wl_registry* registry,
    uint32_t name
) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener core_registry_listener = {
    .global = core_registry_global,
    .global_remove = core_registry_global_remove,
};

static void core_shm_format(
    void* data,
    struct wl_shm* shm,
    uint32_t format
) {
    (void)data;
    (void)shm;
    (void)format;
}

static const struct wl_shm_listener core_shm_listener = {
    .format = core_shm_format,
};

static void overlay_buffer_cleanup(WaylandOverlayBuffer* buffer) {
    if (!buffer || atomic_exchange_explicit(&buffer->cleaned, 1, memory_order_acq_rel)) return;
    if (buffer->buffer) wl_buffer_destroy(buffer->buffer);
    if (buffer->mapping && buffer->mapping != MAP_FAILED) munmap(buffer->mapping, buffer->size);
    free(buffer);
}

static void overlay_buffer_release(
    void* data,
    struct wl_buffer* wl_buffer
) {
    (void)wl_buffer;
    WaylandOverlayBuffer* buffer = (WaylandOverlayBuffer*)data;
    if (!buffer) return;
    atomic_store_explicit(&buffer->busy, 0, memory_order_release);
    if (atomic_load_explicit(&buffer->retired, memory_order_acquire)) {
        overlay_buffer_cleanup(buffer);
    }
}

static const struct wl_buffer_listener overlay_buffer_listener = {
    .release = overlay_buffer_release,
};

static int create_anonymous_file(size_t size) {
    char name[96];
    for (int attempt = 0; attempt < 32; attempt++) {
        uint32_t sequence = atomic_fetch_add_explicit(
            &g_shm_sequence,
            1,
            memory_order_relaxed
        );
        int length = snprintf(
            name,
            sizeof(name),
            "/kmediaplayer-overlay-%ld-%u",
            (long)getpid(),
            sequence
        );
        if (length <= 0 || (size_t)length >= sizeof(name)) return -1;
        int fd = shm_open(name, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0600);
        if (fd < 0) continue;
        shm_unlink(name);
        if (ftruncate(fd, (off_t)size) == 0) return fd;
        close(fd);
        return -1;
    }
    return -1;
}

static WaylandOverlayBuffer* create_overlay_buffer(
    struct wl_shm* shm,
    int32_t width,
    int32_t height,
    int32_t stride,
    size_t size
) {
    int fd = create_anonymous_file(size);
    if (fd < 0) return NULL;
    void* mapping = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        close(fd);
        return NULL;
    }
    struct wl_shm_pool* pool = wl_shm_create_pool(shm, fd, (int32_t)size);
    if (!pool) {
        munmap(mapping, size);
        close(fd);
        return NULL;
    }
    struct wl_buffer* wl_buffer = wl_shm_pool_create_buffer(
        pool,
        0,
        width,
        height,
        stride,
        WL_SHM_FORMAT_ARGB8888
    );
    wl_shm_pool_destroy(pool);
    close(fd);
    if (!wl_buffer) {
        munmap(mapping, size);
        return NULL;
    }

    WaylandOverlayBuffer* buffer = calloc(1, sizeof(*buffer));
    if (!buffer) {
        wl_buffer_destroy(wl_buffer);
        munmap(mapping, size);
        return NULL;
    }
    buffer->buffer = wl_buffer;
    buffer->mapping = mapping;
    buffer->size = size;
    if (wl_buffer_add_listener(wl_buffer, &overlay_buffer_listener, buffer) != 0) {
        overlay_buffer_cleanup(buffer);
        return NULL;
    }
    return buffer;
}

static void retire_buffer(WaylandOverlayBuffer* buffer) {
    if (!buffer) return;
    atomic_store_explicit(&buffer->retired, 1, memory_order_release);
    if (!atomic_load_explicit(&buffer->busy, memory_order_acquire)) {
        overlay_buffer_cleanup(buffer);
    }
}

static int all_buffers_available(const WaylandOverlaySurface* renderer) {
    for (int index = 0; index < OVERLAY_BUFFER_COUNT; index++) {
        WaylandOverlayBuffer* buffer = renderer->buffers[index];
        if (buffer && atomic_load_explicit(&buffer->busy, memory_order_acquire)) return 0;
    }
    return 1;
}

static void retire_all_buffers(WaylandOverlaySurface* renderer) {
    for (int index = 0; index < OVERLAY_BUFFER_COUNT; index++) {
        WaylandOverlayBuffer* buffer = renderer->buffers[index];
        renderer->buffers[index] = NULL;
        retire_buffer(buffer);
    }
    renderer->width = 0;
    renderer->height = 0;
    renderer->stride = 0;
}

static int allocate_buffers(
    WaylandOverlaySurface* renderer,
    int32_t width,
    int32_t height
) {
    if (width <= 0 || height <= 0 ||
        width > OVERLAY_MAX_DIMENSION || height > OVERLAY_MAX_DIMENSION) return 0;
    if ((size_t)width > SIZE_MAX / OVERLAY_BYTES_PER_PIXEL) return 0;
    size_t stride = (size_t)width * OVERLAY_BYTES_PER_PIXEL;
    if ((size_t)height > SIZE_MAX / stride) return 0;
    size_t size = stride * (size_t)height;
    if (size == 0 ||
        size > OVERLAY_MAX_TOTAL_BYTES / OVERLAY_BUFFER_COUNT ||
        stride > INT32_MAX || size > INT32_MAX) return 0;

    for (int index = 0; index < OVERLAY_BUFFER_COUNT; index++) {
        renderer->buffers[index] = create_overlay_buffer(
            renderer->shm,
            width,
            height,
            (int32_t)stride,
            size
        );
        if (!renderer->buffers[index]) {
            retire_all_buffers(renderer);
            return 0;
        }
    }
    renderer->width = width;
    renderer->height = height;
    renderer->stride = (int32_t)stride;
    return 1;
}

WaylandOverlaySurface* wayland_overlay_surface_create(
    uintptr_t display,
    uintptr_t surface
) {
    if (!display || !surface) return NULL;
    WaylandOverlaySurface* renderer = calloc(1, sizeof(*renderer));
    if (!renderer) return NULL;
    renderer->display = (struct wl_display*)display;
    renderer->surface = (struct wl_surface*)surface;
    renderer->event_queue = wl_display_create_queue(renderer->display);
    if (!renderer->event_queue) {
        free(renderer);
        return NULL;
    }
    struct wl_proxy* display_wrapper = wl_proxy_create_wrapper(renderer->display);
    if (!display_wrapper) {
        wl_event_queue_destroy(renderer->event_queue);
        free(renderer);
        return NULL;
    }
    wl_proxy_set_queue(display_wrapper, renderer->event_queue);
    renderer->registry =
        wl_display_get_registry((struct wl_display*)display_wrapper);
    wl_proxy_wrapper_destroy(display_wrapper);
    if (!renderer->registry ||
        wl_registry_add_listener(
            renderer->registry,
            &core_registry_listener,
            renderer
        ) != 0 ||
        wl_display_roundtrip_queue(renderer->display, renderer->event_queue) < 0 ||
        !renderer->compositor || !renderer->shm) {
        if (renderer->shm) wl_shm_destroy(renderer->shm);
        if (renderer->compositor) wl_compositor_destroy(renderer->compositor);
        if (renderer->registry) wl_registry_destroy(renderer->registry);
        wl_event_queue_destroy(renderer->event_queue);
        free(renderer);
        return NULL;
    }
    if (wl_shm_add_listener(renderer->shm, &core_shm_listener, renderer) != 0 ||
        wl_display_roundtrip_queue(renderer->display, renderer->event_queue) < 0) {
        wl_shm_destroy(renderer->shm);
        wl_compositor_destroy(renderer->compositor);
        wl_registry_destroy(renderer->registry);
        wl_event_queue_destroy(renderer->event_queue);
        free(renderer);
        return NULL;
    }
    if (!wayland_overlay_surface_make_input_transparent(renderer, surface)) {
        wayland_overlay_surface_destroy(renderer);
        return NULL;
    }
    return renderer;
}

int wayland_overlay_surface_make_input_transparent(
    WaylandOverlaySurface* renderer,
    uintptr_t surface
) {
    if (!renderer || !renderer->compositor || !surface) return 0;
    struct wl_region* empty_region =
        wl_compositor_create_region(renderer->compositor);
    if (!empty_region) return 0;
    wl_surface_set_input_region((struct wl_surface*)surface, empty_region);
    wl_region_destroy(empty_region);
    wl_surface_commit((struct wl_surface*)surface);
    return wl_display_flush(renderer->display) >= 0 || errno == EAGAIN;
}

int wayland_overlay_surface_update(
    WaylandOverlaySurface* renderer,
    const void* pixels,
    size_t row_bytes,
    int32_t width,
    int32_t height
) {
    if (!renderer || !pixels || width <= 0 || height <= 0) return 0;
    if ((size_t)width > SIZE_MAX / OVERLAY_BYTES_PER_PIXEL) return 0;
    size_t source_bytes = (size_t)width * OVERLAY_BYTES_PER_PIXEL;
    if (row_bytes < source_bytes) return 0;

    wl_display_dispatch_queue_pending(renderer->display, renderer->event_queue);
    if (renderer->width != width || renderer->height != height) {
        /* Keep memory bounded to one triple buffer while the compositor owns frames. */
        if (!all_buffers_available(renderer)) {
            if (wl_display_roundtrip_queue(renderer->display, renderer->event_queue) < 0) return 0;
            if (!all_buffers_available(renderer)) return 2;
        }
        retire_all_buffers(renderer);
        if (!allocate_buffers(renderer, width, height)) return 0;
    }

    WaylandOverlayBuffer* target = NULL;
    for (int index = 0; index < OVERLAY_BUFFER_COUNT; index++) {
        WaylandOverlayBuffer* candidate = renderer->buffers[index];
        if (candidate &&
            !atomic_exchange_explicit(&candidate->busy, 1, memory_order_acq_rel)) {
            target = candidate;
            break;
        }
    }
    if (!target) {
        if (wl_display_roundtrip_queue(renderer->display, renderer->event_queue) < 0) return 0;
        for (int index = 0; index < OVERLAY_BUFFER_COUNT; index++) {
            WaylandOverlayBuffer* candidate = renderer->buffers[index];
            if (candidate &&
                !atomic_exchange_explicit(&candidate->busy, 1, memory_order_acq_rel)) {
                target = candidate;
                break;
            }
        }
        if (!target) return 2;
    }

    const uint8_t* source = (const uint8_t*)pixels;
    uint8_t* destination = (uint8_t*)target->mapping;
    for (int32_t row = 0; row < height; row++) {
        memcpy(
            destination + (size_t)row * (size_t)renderer->stride,
            source + (size_t)row * row_bytes,
            source_bytes
        );
    }

    wl_surface_attach(renderer->surface, target->buffer, 0, 0);
    wl_surface_damage(renderer->surface, 0, 0, width, height);
    wl_surface_commit(renderer->surface);
    int flush_result = wl_display_flush(renderer->display);
    return flush_result >= 0 || errno == EAGAIN;
}

void wayland_overlay_surface_clear(WaylandOverlaySurface* renderer) {
    if (!renderer) return;
    wl_surface_attach(renderer->surface, NULL, 0, 0);
    wl_surface_commit(renderer->surface);
    wl_display_flush(renderer->display);
}

void wayland_overlay_surface_destroy(WaylandOverlaySurface* renderer) {
    if (!renderer) return;
    wayland_overlay_surface_clear(renderer);
    wl_display_roundtrip_queue(renderer->display, renderer->event_queue);
    wl_display_dispatch_queue_pending(renderer->display, renderer->event_queue);
    retire_all_buffers(renderer);
    if (renderer->shm) wl_shm_destroy(renderer->shm);
    if (renderer->compositor) wl_compositor_destroy(renderer->compositor);
    if (renderer->registry) wl_registry_destroy(renderer->registry);
    if (renderer->event_queue) wl_event_queue_destroy(renderer->event_queue);
    free(renderer);
}
