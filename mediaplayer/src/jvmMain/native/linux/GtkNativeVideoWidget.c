#include "GtkNativeVideoWidget.h"

#include <gdk/gdk.h>
#include <gdk/gdkwayland.h>
#include <gdk/gdkx.h>
#include <gtk/gtk.h>
#include <stdlib.h>
#include <string.h>

typedef struct KmpGtkVideoWidgetState {
    VideoPlayer* player;
    LibVlcCanvasPlayer* libvlc;
    int has_projection_configuration;
    LinuxVulkanProjectionConfiguration projection_configuration;
    int attached;
} KmpGtkVideoWidgetState;

static int attach_video_target(GtkWidget* widget, KmpGtkVideoWidgetState* state) {
    if (!widget || !state) return 0;
    GdkWindow* window = gtk_widget_get_window(widget);
    if (!window) return 0;

    if (state->libvlc) {
        if (!GDK_IS_X11_WINDOW(window)) return 0;
        const uint32_t xid = (uint32_t)gdk_x11_window_get_xid(window);
        state->attached = xid != 0 && lvc_set_native_window(state->libvlc, xid) != 0;
        return state->attached;
    }

    if (!state->player || !GDK_IS_WAYLAND_WINDOW(window)) return 0;
    GdkDisplay* gdk_display = gdk_window_get_display(window);
    struct wl_display* display = gdk_wayland_display_get_wl_display(gdk_display);
    struct wl_surface* surface = gdk_wayland_window_get_wl_surface(window);
    GtkAllocation allocation;
    gtk_widget_get_allocation(widget, &allocation);
    const int32_t width = allocation.width > 0 ? allocation.width : 1;
    const int32_t height = allocation.height > 0 ? allocation.height : 1;

    if (state->has_projection_configuration) {
        state->attached = nvp_attach_wayland_projection_output(
            state->player,
            (uintptr_t)display,
            (uintptr_t)surface,
            0,
            0,
            width,
            height,
            &state->projection_configuration);
    } else {
        state->attached = nvp_attach_wayland_output(
            state->player,
            (uintptr_t)display,
            (uintptr_t)surface,
            0,
            0,
            width,
            height);
    }
    return state->attached;
}

static void on_widget_realize(GtkWidget* widget, gpointer user_data) {
    attach_video_target(widget, (KmpGtkVideoWidgetState*)user_data);
}

static void on_widget_size_allocate(
    GtkWidget* widget,
    GtkAllocation* allocation,
    gpointer user_data
) {
    (void)allocation;
    KmpGtkVideoWidgetState* state = (KmpGtkVideoWidgetState*)user_data;
    if (gtk_widget_get_realized(widget) && state && state->player) {
        attach_video_target(widget, state);
    }
}

static void detach_video_target(KmpGtkVideoWidgetState* state) {
    if (!state || !state->attached) return;
    if (state->libvlc) {
        lvc_set_native_window(state->libvlc, 0);
    } else if (state->player) {
        nvp_detach_wayland_output(state->player);
    }
    state->attached = 0;
}

static void on_widget_unrealize(GtkWidget* widget, gpointer user_data) {
    (void)widget;
    detach_video_target((KmpGtkVideoWidgetState*)user_data);
}

static void destroy_widget_state(gpointer user_data) {
    KmpGtkVideoWidgetState* state = (KmpGtkVideoWidgetState*)user_data;
    detach_video_target(state);
    free(state);
}

void* kmp_gtk_video_widget_create(
    VideoPlayer* player,
    LibVlcCanvasPlayer* libvlc,
    const LinuxVulkanProjectionConfiguration* projection_configuration
) {
    if (!player && !libvlc) return NULL;
    if (!gtk_init_check(NULL, NULL)) return NULL;

    KmpGtkVideoWidgetState* state = calloc(1, sizeof(*state));
    if (!state) return NULL;
    state->player = player;
    state->libvlc = libvlc;
    if (projection_configuration) {
        state->has_projection_configuration = 1;
        memcpy(
            &state->projection_configuration,
            projection_configuration,
            sizeof(state->projection_configuration));
    }

    GtkWidget* widget = gtk_drawing_area_new();
    if (!widget) {
        free(state);
        return NULL;
    }
    g_object_ref_sink(widget);
    gtk_widget_set_hexpand(widget, TRUE);
    gtk_widget_set_vexpand(widget, TRUE);
    gtk_widget_set_size_request(widget, 1, 1);
    g_object_set_data_full(G_OBJECT(widget), "kmp-video-state", state, destroy_widget_state);
    g_signal_connect(widget, "realize", G_CALLBACK(on_widget_realize), state);
    g_signal_connect(widget, "unrealize", G_CALLBACK(on_widget_unrealize), state);
    g_signal_connect(widget, "size-allocate", G_CALLBACK(on_widget_size_allocate), state);
    gtk_widget_show(widget);
    return widget;
}

void kmp_gtk_video_widget_destroy(void* widget_ptr) {
    GtkWidget* widget = GTK_WIDGET(widget_ptr);
    if (!widget) return;
    KmpGtkVideoWidgetState* state =
        (KmpGtkVideoWidgetState*)g_object_get_data(G_OBJECT(widget), "kmp-video-state");
    detach_video_target(state);
    gtk_widget_destroy(widget);
    g_object_unref(widget);
}

int kmp_gtk_wayland_available(void) {
    GdkDisplay* display = gdk_display_get_default();
    return display && GDK_IS_WAYLAND_DISPLAY(display);
}

int kmp_gtk_x11_available(void) {
    GdkDisplay* display = gdk_display_get_default();
    return display && GDK_IS_X11_DISPLAY(display);
}

uintptr_t kmp_gtk_wayland_display(void) {
    GdkDisplay* display = gdk_display_get_default();
    return display && GDK_IS_WAYLAND_DISPLAY(display)
        ? (uintptr_t)gdk_wayland_display_get_wl_display(display)
        : 0;
}
