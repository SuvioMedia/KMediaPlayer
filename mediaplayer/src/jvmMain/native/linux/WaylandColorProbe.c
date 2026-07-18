/*
 * Minimal client for the version-1 subset of staging/color-management-v1.
 * Keeping the wire declarations here avoids a runtime dependency on
 * wayland-info and a build-time dependency on wayland-scanner/protocol XML.
 */
#include "WaylandColorProbe.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <wayland-client.h>

struct wp_color_manager_v1;
struct wp_color_management_output_v1;
struct wp_image_description_v1;
struct wp_image_description_info_v1;

static const struct wl_interface color_manager_interface;
static const struct wl_interface color_output_interface;
static const struct wl_interface image_description_interface;
static const struct wl_interface image_description_info_interface;

static const struct wl_interface* manager_get_output_types[] = {
    &color_output_interface,
    &wl_output_interface,
};
static const struct wl_interface* output_get_description_types[] = {
    &image_description_interface,
};
static const struct wl_interface* description_get_information_types[] = {
    &image_description_info_interface,
};

static const struct wl_message color_manager_requests[] = {
    { "destroy", "", NULL },
    { "get_output", "no", manager_get_output_types },
};
static const struct wl_message color_manager_events[] = {
    { "supported_intent", "u", NULL },
    { "supported_feature", "u", NULL },
    { "supported_tf_named", "u", NULL },
    { "supported_primaries_named", "u", NULL },
    { "done", "", NULL },
};
static const struct wl_interface color_manager_interface = {
    "wp_color_manager_v1", 1,
    2, color_manager_requests,
    5, color_manager_events,
};

static const struct wl_message color_output_requests[] = {
    { "destroy", "", NULL },
    { "get_image_description", "n", output_get_description_types },
};
static const struct wl_message color_output_events[] = {
    { "image_description_changed", "", NULL },
};
static const struct wl_interface color_output_interface = {
    "wp_color_management_output_v1", 1,
    2, color_output_requests,
    1, color_output_events,
};

static const struct wl_message image_description_requests[] = {
    { "destroy", "", NULL },
    { "get_information", "n", description_get_information_types },
};
static const struct wl_message image_description_events[] = {
    { "failed", "us", NULL },
    { "ready", "u", NULL },
};
static const struct wl_interface image_description_interface = {
    "wp_image_description_v1", 1,
    2, image_description_requests,
    2, image_description_events,
};

static const struct wl_message image_description_info_events[] = {
    { "done", "", NULL },
    { "icc_file", "hu", NULL },
    { "primaries", "iiiiiiii", NULL },
    { "primaries_named", "u", NULL },
    { "tf_power", "u", NULL },
    { "tf_named", "u", NULL },
    { "luminances", "uuu", NULL },
    { "target_primaries", "iiiiiiii", NULL },
    { "target_luminance", "uu", NULL },
    { "target_max_cll", "u", NULL },
    { "target_max_fall", "u", NULL },
};
static const struct wl_interface image_description_info_interface = {
    "wp_image_description_info_v1", 1,
    0, NULL,
    11, image_description_info_events,
};

enum {
    COLOR_MANAGER_FEATURE_PARAMETRIC = 1,
    COLOR_MANAGER_PRIMARIES_BT2020 = 6,
    COLOR_MANAGER_TRANSFER_ST2084_PQ = 11,
    COLOR_MANAGER_TRANSFER_HLG = 13,
};

typedef struct ProbeContext {
    WaylandColorProbeResult result;
    int32_t requested_output_id;
    uint32_t manager_global;
    uint32_t manager_version;
    uint32_t first_output_global;
    uint32_t selected_output_global;
    uint32_t selected_output_version;
    struct wp_color_manager_v1* manager;
    struct wl_output* output;
    struct wp_color_management_output_v1* color_output;
    struct wp_image_description_v1* description;
    struct wp_image_description_info_v1* information;
} ProbeContext;

struct color_manager_listener {
    void (*supported_intent)(void*, struct wp_color_manager_v1*, uint32_t);
    void (*supported_feature)(void*, struct wp_color_manager_v1*, uint32_t);
    void (*supported_tf_named)(void*, struct wp_color_manager_v1*, uint32_t);
    void (*supported_primaries_named)(void*, struct wp_color_manager_v1*, uint32_t);
    void (*done)(void*, struct wp_color_manager_v1*);
};

struct color_output_listener {
    void (*image_description_changed)(void*, struct wp_color_management_output_v1*);
};

struct image_description_listener {
    void (*failed)(void*, struct wp_image_description_v1*, uint32_t, const char*);
    void (*ready)(void*, struct wp_image_description_v1*, uint32_t);
};

struct image_description_info_listener {
    void (*done)(void*, struct wp_image_description_info_v1*);
    void (*icc_file)(void*, struct wp_image_description_info_v1*, int32_t, uint32_t);
    void (*primaries)(void*, struct wp_image_description_info_v1*,
                      int32_t, int32_t, int32_t, int32_t,
                      int32_t, int32_t, int32_t, int32_t);
    void (*primaries_named)(void*, struct wp_image_description_info_v1*, uint32_t);
    void (*tf_power)(void*, struct wp_image_description_info_v1*, uint32_t);
    void (*tf_named)(void*, struct wp_image_description_info_v1*, uint32_t);
    void (*luminances)(void*, struct wp_image_description_info_v1*, uint32_t, uint32_t, uint32_t);
    void (*target_primaries)(void*, struct wp_image_description_info_v1*,
                             int32_t, int32_t, int32_t, int32_t,
                             int32_t, int32_t, int32_t, int32_t);
    void (*target_luminance)(void*, struct wp_image_description_info_v1*, uint32_t, uint32_t);
    void (*target_max_cll)(void*, struct wp_image_description_info_v1*, uint32_t);
    void (*target_max_fall)(void*, struct wp_image_description_info_v1*, uint32_t);
};

static void destroy_protocol_object(struct wl_proxy* proxy, uint32_t opcode) {
    if (!proxy) return;
    wl_proxy_marshal_flags(
        proxy,
        opcode,
        NULL,
        wl_proxy_get_version(proxy),
        WL_MARSHAL_FLAG_DESTROY
    );
}

static int add_listener(struct wl_proxy* proxy, const void* listener, void* data) {
    return wl_proxy_add_listener(proxy, (void (**)(void))listener, data);
}

static void manager_supported_intent(
    void* data,
    struct wp_color_manager_v1* manager,
    uint32_t intent
) {
    (void)data;
    (void)manager;
    (void)intent;
}

static void manager_supported_feature(
    void* data,
    struct wp_color_manager_v1* manager,
    uint32_t feature
) {
    (void)manager;
    ProbeContext* probe = data;
    if (feature == COLOR_MANAGER_FEATURE_PARAMETRIC) probe->result.flags |= WCP_PARAMETRIC;
}

static void manager_supported_tf(
    void* data,
    struct wp_color_manager_v1* manager,
    uint32_t transfer
) {
    (void)manager;
    ProbeContext* probe = data;
    if (transfer == COLOR_MANAGER_TRANSFER_ST2084_PQ) probe->result.flags |= WCP_PQ;
    if (transfer == COLOR_MANAGER_TRANSFER_HLG) probe->result.flags |= WCP_HLG;
}

static void manager_supported_primaries(
    void* data,
    struct wp_color_manager_v1* manager,
    uint32_t primaries
) {
    (void)manager;
    ProbeContext* probe = data;
    if (primaries == COLOR_MANAGER_PRIMARIES_BT2020) probe->result.flags |= WCP_BT2020;
}

static void manager_done(void* data, struct wp_color_manager_v1* manager) {
    (void)data;
    (void)manager;
}

static const struct color_manager_listener manager_listener = {
    manager_supported_intent,
    manager_supported_feature,
    manager_supported_tf,
    manager_supported_primaries,
    manager_done,
};

static void color_output_changed(void* data, struct wp_color_management_output_v1* output) {
    (void)data;
    (void)output;
}

static const struct color_output_listener output_listener = {
    color_output_changed,
};

static int approximately(int32_t value, int32_t expected) {
    int32_t difference = value > expected ? value - expected : expected - value;
    return difference <= 1000;
}

static void info_done(void* data, struct wp_image_description_info_v1* information) {
    ProbeContext* probe = data;
    probe->result.flags |= WCP_OUTPUT_DESCRIPTION;
    if (probe->information == information) probe->information = NULL;
}

static void info_icc(
    void* data,
    struct wp_image_description_info_v1* information,
    int32_t fd,
    uint32_t size
) {
    (void)data;
    (void)information;
    (void)size;
    if (fd >= 0) close(fd);
}

static void info_primaries(
    void* data,
    struct wp_image_description_info_v1* information,
    int32_t r_x,
    int32_t r_y,
    int32_t g_x,
    int32_t g_y,
    int32_t b_x,
    int32_t b_y,
    int32_t w_x,
    int32_t w_y
) {
    (void)information;
    ProbeContext* probe = data;
    if (approximately(r_x, 708000) && approximately(r_y, 292000) &&
        approximately(g_x, 170000) && approximately(g_y, 797000) &&
        approximately(b_x, 131000) && approximately(b_y, 46000) &&
        approximately(w_x, 312700) && approximately(w_y, 329000)) {
        probe->result.flags |= WCP_OUTPUT_BT2020;
    }
}

static void info_primaries_named(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t primaries
) {
    (void)information;
    ProbeContext* probe = data;
    if (primaries == COLOR_MANAGER_PRIMARIES_BT2020) probe->result.flags |= WCP_OUTPUT_BT2020;
}

static void info_tf_power(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t exponent
) {
    (void)information;
    (void)exponent;
    ProbeContext* probe = data;
    probe->result.flags |= WCP_OUTPUT_SDR;
}

static void info_tf_named(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t transfer
) {
    (void)information;
    ProbeContext* probe = data;
    if (transfer == COLOR_MANAGER_TRANSFER_ST2084_PQ) probe->result.flags |= WCP_OUTPUT_PQ;
    else if (transfer == COLOR_MANAGER_TRANSFER_HLG) probe->result.flags |= WCP_OUTPUT_HLG;
    else probe->result.flags |= WCP_OUTPUT_SDR;
}

static void info_luminances(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t minimum,
    uint32_t maximum,
    uint32_t reference
) {
    (void)information;
    ProbeContext* probe = data;
    probe->result.min_luminance_x10000 = minimum;
    probe->result.max_luminance = maximum;
    probe->result.reference_luminance = reference;
}

static void info_target_primaries(
    void* data,
    struct wp_image_description_info_v1* information,
    int32_t r_x,
    int32_t r_y,
    int32_t g_x,
    int32_t g_y,
    int32_t b_x,
    int32_t b_y,
    int32_t w_x,
    int32_t w_y
) {
    (void)data;
    (void)information;
    (void)r_x;
    (void)r_y;
    (void)g_x;
    (void)g_y;
    (void)b_x;
    (void)b_y;
    (void)w_x;
    (void)w_y;
}

static void info_target_luminance(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t minimum,
    uint32_t maximum
) {
    (void)data;
    (void)information;
    (void)minimum;
    (void)maximum;
}

static void info_target_max_cll(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t maximum
) {
    (void)data;
    (void)information;
    (void)maximum;
}

static void info_target_max_fall(
    void* data,
    struct wp_image_description_info_v1* information,
    uint32_t maximum
) {
    (void)data;
    (void)information;
    (void)maximum;
}

static const struct image_description_info_listener information_listener = {
    info_done,
    info_icc,
    info_primaries,
    info_primaries_named,
    info_tf_power,
    info_tf_named,
    info_luminances,
    info_target_primaries,
    info_target_luminance,
    info_target_max_cll,
    info_target_max_fall,
};

static void description_failed(
    void* data,
    struct wp_image_description_v1* description,
    uint32_t cause,
    const char* message
) {
    (void)data;
    (void)description;
    (void)cause;
    (void)message;
}

static void description_ready(
    void* data,
    struct wp_image_description_v1* description,
    uint32_t identity
) {
    (void)identity;
    ProbeContext* probe = data;
    struct wl_proxy* information =
        wl_proxy_marshal_flags(
            (struct wl_proxy*)description,
            1,
            &image_description_info_interface,
            wl_proxy_get_version((struct wl_proxy*)description),
            0,
            NULL
        );
    if (!information) return;
    probe->information = (struct wp_image_description_info_v1*)information;
    if (add_listener(information, &information_listener, probe) != 0) {
        wl_proxy_destroy(information);
        probe->information = NULL;
    }
}

static const struct image_description_listener description_listener = {
    description_failed,
    description_ready,
};

static void output_geometry(
    void* data,
    struct wl_output* output,
    int32_t x,
    int32_t y,
    int32_t physical_width,
    int32_t physical_height,
    int32_t subpixel,
    const char* make,
    const char* model,
    int32_t transform
) {
    (void)data;
    (void)output;
    (void)x;
    (void)y;
    (void)physical_width;
    (void)physical_height;
    (void)subpixel;
    (void)make;
    (void)model;
    (void)transform;
}

static void output_mode(
    void* data,
    struct wl_output* output,
    uint32_t flags,
    int32_t width,
    int32_t height,
    int32_t refresh
) {
    (void)data;
    (void)output;
    (void)flags;
    (void)width;
    (void)height;
    (void)refresh;
}

static const struct wl_output_listener wl_output_listener_v1 = {
    .geometry = output_geometry,
    .mode = output_mode,
};

static void registry_global(
    void* data,
    struct wl_registry* registry,
    uint32_t name,
    const char* interface,
    uint32_t version
) {
    (void)registry;
    ProbeContext* probe = data;
    if (strcmp(interface, color_manager_interface.name) == 0) {
        probe->manager_global = name;
        probe->manager_version = version;
    } else if (strcmp(interface, wl_output_interface.name) == 0) {
        if (probe->first_output_global == 0) probe->first_output_global = name;
        if ((int32_t)name == probe->requested_output_id) {
            probe->selected_output_global = name;
            probe->selected_output_version = version;
        }
    }
}

static void registry_global_remove(void* data, struct wl_registry* registry, uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    registry_global,
    registry_global_remove,
};

static int bind_color_objects(ProbeContext* probe, struct wl_registry* registry) {
    if (!probe->manager_global) return 1;

    probe->manager =
        (struct wp_color_manager_v1*)wl_registry_bind(
            registry,
            probe->manager_global,
            &color_manager_interface,
            probe->manager_version < 1 ? probe->manager_version : 1
        );
    if (!probe->manager ||
        add_listener((struct wl_proxy*)probe->manager, &manager_listener, probe) != 0) {
        return 0;
    }
    probe->result.flags |= WCP_COLOR_MANAGER;

    if (!probe->selected_output_global && probe->requested_output_id < 0) {
        probe->selected_output_global = probe->first_output_global;
        probe->selected_output_version = 1;
    }
    if (!probe->selected_output_global) return 1;
    probe->result.output_id = (int32_t)probe->selected_output_global;

    uint32_t output_version = probe->selected_output_version;
    if (output_version == 0 || output_version > 1) output_version = 1;
    probe->output =
        (struct wl_output*)wl_registry_bind(
            registry,
            probe->selected_output_global,
            &wl_output_interface,
            output_version
        );
    if (!probe->output || wl_output_add_listener(probe->output, &wl_output_listener_v1, probe) != 0) return 0;

    probe->color_output =
        (struct wp_color_management_output_v1*)wl_proxy_marshal_flags(
            (struct wl_proxy*)probe->manager,
            1,
            &color_output_interface,
            wl_proxy_get_version((struct wl_proxy*)probe->manager),
            0,
            NULL,
            probe->output
        );
    if (!probe->color_output ||
        add_listener((struct wl_proxy*)probe->color_output, &output_listener, probe) != 0) {
        return 0;
    }

    probe->description =
        (struct wp_image_description_v1*)wl_proxy_marshal_flags(
            (struct wl_proxy*)probe->color_output,
            1,
            &image_description_interface,
            wl_proxy_get_version((struct wl_proxy*)probe->color_output),
            0,
            NULL
        );
    if (!probe->description ||
        add_listener((struct wl_proxy*)probe->description, &description_listener, probe) != 0) {
        return 0;
    }
    return 1;
}

static void cleanup_probe(ProbeContext* probe) {
    if (probe->information) wl_proxy_destroy((struct wl_proxy*)probe->information);
    destroy_protocol_object((struct wl_proxy*)probe->description, 0);
    destroy_protocol_object((struct wl_proxy*)probe->color_output, 0);
    if (probe->output) wl_output_destroy(probe->output);
    destroy_protocol_object((struct wl_proxy*)probe->manager, 0);
}

int wayland_color_probe_query(
    uintptr_t display_ptr,
    int32_t requested_output_id,
    WaylandColorProbeResult* result
) {
    if (!display_ptr || !result) return 0;
    memset(result, 0, sizeof(*result));
    result->output_id = requested_output_id;

    struct wl_display* display = (struct wl_display*)display_ptr;
    struct wl_event_queue* queue = wl_display_create_queue(display);
    if (!queue) return 0;
    struct wl_proxy* display_wrapper = wl_proxy_create_wrapper(display);
    if (!display_wrapper) {
        wl_event_queue_destroy(queue);
        return 0;
    }
    wl_proxy_set_queue(display_wrapper, queue);
    struct wl_registry* registry = wl_display_get_registry((struct wl_display*)display_wrapper);
    wl_proxy_wrapper_destroy(display_wrapper);
    if (!registry) {
        wl_event_queue_destroy(queue);
        return 0;
    }

    ProbeContext probe;
    memset(&probe, 0, sizeof(probe));
    probe.requested_output_id = requested_output_id;
    probe.result.output_id = requested_output_id;
    wl_registry_add_listener(registry, &registry_listener, &probe);

    int ok = wl_display_roundtrip_queue(display, queue) >= 0;
    if (ok) {
        probe.result.flags |= WCP_PROBE_COMPLETED;
        ok = bind_color_objects(&probe, registry);
    }
    if (ok && probe.manager) ok = wl_display_roundtrip_queue(display, queue) >= 0;
    if (ok && probe.information) ok = wl_display_roundtrip_queue(display, queue) >= 0;

    cleanup_probe(&probe);
    wl_registry_destroy(registry);
    wl_event_queue_destroy(queue);
    wl_display_flush(display);

    if (!ok || wl_display_get_error(display) != 0) return 0;
    *result = probe.result;
    return 1;
}
