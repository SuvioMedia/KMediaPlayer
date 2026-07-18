#ifndef WAYLAND_COLOR_PROBE_H
#define WAYLAND_COLOR_PROBE_H

#include <stdint.h>

#define WCP_PROBE_COMPLETED        (1ULL << 0)
#define WCP_COLOR_MANAGER          (1ULL << 1)
#define WCP_PARAMETRIC             (1ULL << 2)
#define WCP_BT2020                 (1ULL << 3)
#define WCP_PQ                     (1ULL << 4)
#define WCP_HLG                    (1ULL << 5)
#define WCP_OUTPUT_DESCRIPTION     (1ULL << 6)
#define WCP_OUTPUT_PQ              (1ULL << 7)
#define WCP_OUTPUT_HLG             (1ULL << 8)
#define WCP_OUTPUT_BT2020          (1ULL << 9)
#define WCP_OUTPUT_SDR             (1ULL << 10)

typedef struct WaylandColorProbeResult {
    uint64_t flags;
    int32_t output_id;
    uint32_t min_luminance_x10000;
    uint32_t max_luminance;
    uint32_t reference_luminance;
} WaylandColorProbeResult;

int wayland_color_probe_query(
    uintptr_t display_ptr,
    int32_t requested_output_id,
    WaylandColorProbeResult* result
);

#endif
