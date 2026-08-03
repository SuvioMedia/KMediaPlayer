#ifndef KMP_GTK_NATIVE_VIDEO_WIDGET_H
#define KMP_GTK_NATIVE_VIDEO_WIDGET_H

#include <stdint.h>
#include "LibVlcCanvas.h"
#include "NativeVideoPlayer.h"

void* kmp_gtk_video_widget_create(
    VideoPlayer* player,
    LibVlcCanvasPlayer* libvlc,
    const LinuxVulkanProjectionConfiguration* projection_configuration
);

void kmp_gtk_video_widget_destroy(void* widget_ptr);
int kmp_gtk_wayland_available(void);
int kmp_gtk_x11_available(void);
uintptr_t kmp_gtk_wayland_display(void);

#endif
