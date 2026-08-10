// SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

#include "ComposeMediaPlayerMpvBridge.h"

#include <dlfcn.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
 * The minimal declarations and numeric constants below mirror mpv 0.41's
 * ISC-licensed client.h and render.h API. No libmpv implementation is bundled
 * in this bridge.
 */
typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;
typedef struct mpv_node_list mpv_node_list;

typedef union mpv_node_union {
    char *string;
    int flag;
    int64_t int64;
    double double_value;
    mpv_node_list *list;
    void *byte_array;
} mpv_node_union;

typedef struct mpv_node {
    mpv_node_union u;
    int format;
} mpv_node;

struct mpv_node_list {
    int num;
    mpv_node *values;
    char **keys;
};

typedef struct mpv_render_param {
    int type;
    void *data;
} mpv_render_param;

typedef struct mpv_event {
    int event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;

typedef struct mpv_event_end_file_prefix {
    int reason;
    int error;
} mpv_event_end_file_prefix;

/*
 * Keep the upstream header notice in the native object embedded in the iOS
 * cinterop KLIB. The same notice is also published in the source artifacts.
 */
__attribute__((used))
static const char cmp_mpv_client_header_isc_notice[] =
    "Copyright (C) 2017-2018 the mpv developers\n"
    "\n"
    "Permission to use, copy, modify, and/or distribute this software for any\n"
    "purpose with or without fee is hereby granted, provided that the above\n"
    "copyright notice and this permission notice appear in all copies.\n"
    "\n"
    "THE SOFTWARE IS PROVIDED \"AS IS\" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH\n"
    "REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY\n"
    "AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,\n"
    "INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM\n"
    "LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR\n"
    "OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR\n"
    "PERFORMANCE OF THIS SOFTWARE.\n";

enum {
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_NODE = 6,
    MPV_FORMAT_NODE_ARRAY = 7,
};

enum {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_SW_SIZE = 17,
    MPV_RENDER_PARAM_SW_FORMAT = 18,
    MPV_RENDER_PARAM_SW_STRIDE = 19,
    MPV_RENDER_PARAM_SW_POINTER = 20,
};

typedef uint64_t (*mpv_client_api_version_fn)(void);
typedef int (*kmediampv_embedded_iosvk_api_version_fn)(void);
typedef mpv_handle *(*mpv_create_fn)(void);
typedef int (*mpv_initialize_fn)(mpv_handle *);
typedef void (*mpv_terminate_destroy_fn)(mpv_handle *);
typedef int (*mpv_set_option_string_fn)(mpv_handle *, const char *, const char *);
typedef int (*mpv_set_property_string_fn)(mpv_handle *, const char *, const char *);
typedef int (*mpv_set_property_fn)(mpv_handle *, const char *, int, void *);
typedef char *(*mpv_get_property_string_fn)(mpv_handle *, const char *);
typedef void (*mpv_free_fn)(void *);
typedef int (*mpv_command_fn)(mpv_handle *, const char *const *);
typedef mpv_event *(*mpv_wait_event_fn)(mpv_handle *, double);
typedef void (*mpv_wakeup_fn)(mpv_handle *);
typedef int (*mpv_render_context_create_fn)(
    mpv_render_context **,
    mpv_handle *,
    mpv_render_param *
);
typedef int (*mpv_render_context_render_fn)(
    mpv_render_context *,
    mpv_render_param *
);
typedef void (*mpv_render_context_free_fn)(mpv_render_context *);

typedef struct cmp_mpv_api {
    void *library;
    int owns_library;
    mpv_client_api_version_fn client_api_version;
    mpv_create_fn create;
    mpv_initialize_fn initialize;
    mpv_terminate_destroy_fn terminate_destroy;
    mpv_set_option_string_fn set_option_string;
    mpv_set_property_string_fn set_property_string;
    mpv_set_property_fn set_property;
    mpv_get_property_string_fn get_property_string;
    mpv_free_fn free_value;
    mpv_command_fn command;
    mpv_wait_event_fn wait_event;
    mpv_wakeup_fn wakeup;
    mpv_render_context_create_fn render_context_create;
    mpv_render_context_render_fn render_context_render;
    mpv_render_context_free_fn render_context_free;
} cmp_mpv_api;

struct cmp_mpv_player {
    cmp_mpv_api api;
    mpv_handle *handle;
    mpv_render_context *render_context;
    int renderer;
};

static int cmp_mpv_symbol(
    void *library,
    const char *name,
    void *target,
    size_t target_size
);

static int cmp_mpv_has_iosvk_capability(const cmp_mpv_api *api) {
    if (api == NULL || api->library == NULL) {
        return 0;
    }
    kmediampv_embedded_iosvk_api_version_fn version = NULL;
    if (!cmp_mpv_symbol(
            api->library,
            "kmediampv_embedded_iosvk_api_version",
            &version,
            sizeof(version))) {
        return 0;
    }
    return version() == 1;
}

static void cmp_mpv_close_api(cmp_mpv_api *api) {
    if (api == NULL) {
        return;
    }
    if (api->owns_library && api->library != NULL) {
        dlclose(api->library);
    }
    memset(api, 0, sizeof(*api));
}

static int cmp_mpv_symbol(
    void *library,
    const char *name,
    void *target,
    size_t target_size
) {
    void *symbol = dlsym(library, name);
    if (symbol == NULL || target_size != sizeof(symbol)) {
        return 0;
    }
    memcpy(target, &symbol, sizeof(symbol));
    return 1;
}

#define CMP_MPV_LOAD(api, field, symbol_name)                                      \
    do {                                                                            \
        if (!cmp_mpv_symbol(                                                        \
                (api)->library,                                                     \
                (symbol_name),                                                      \
                &(api)->field,                                                      \
                sizeof((api)->field))) {                                            \
            cmp_mpv_close_api((api));                                               \
            return CMP_MPV_REQUIRED_SYMBOL_MISSING;                                 \
        }                                                                           \
    } while (0)

static int cmp_mpv_open_api(const char *library_path, cmp_mpv_api *api) {
    if (api == NULL) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    memset(api, 0, sizeof(*api));

    if (library_path == NULL) {
        api->library = RTLD_DEFAULT;
        api->owns_library = 0;
        if (dlsym(api->library, "mpv_client_api_version") == NULL) {
            return CMP_MPV_LIBRARY_NOT_FOUND;
        }
    } else {
        if (library_path[0] == '\0') {
            return CMP_MPV_INVALID_ARGUMENT;
        }
        api->library = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
        if (api->library == NULL) {
            return CMP_MPV_LIBRARY_NOT_FOUND;
        }
        api->owns_library = 1;
    }

    CMP_MPV_LOAD(api, client_api_version, "mpv_client_api_version");
    CMP_MPV_LOAD(api, create, "mpv_create");
    CMP_MPV_LOAD(api, initialize, "mpv_initialize");
    CMP_MPV_LOAD(api, terminate_destroy, "mpv_terminate_destroy");
    CMP_MPV_LOAD(api, set_option_string, "mpv_set_option_string");
    CMP_MPV_LOAD(api, set_property_string, "mpv_set_property_string");
    CMP_MPV_LOAD(api, set_property, "mpv_set_property");
    CMP_MPV_LOAD(api, get_property_string, "mpv_get_property_string");
    CMP_MPV_LOAD(api, free_value, "mpv_free");
    CMP_MPV_LOAD(api, command, "mpv_command");
    CMP_MPV_LOAD(api, wait_event, "mpv_wait_event");
    CMP_MPV_LOAD(api, wakeup, "mpv_wakeup");
    CMP_MPV_LOAD(api, render_context_create, "mpv_render_context_create");
    CMP_MPV_LOAD(api, render_context_render, "mpv_render_context_render");
    CMP_MPV_LOAD(api, render_context_free, "mpv_render_context_free");

    uint64_t version = api->client_api_version();
    if (((version >> 16U) & 0xffffU) != 2U) {
        cmp_mpv_close_api(api);
        return CMP_MPV_INCOMPATIBLE_CLIENT_API;
    }
    return CMP_MPV_OK;
}

static int cmp_mpv_set_option(
    cmp_mpv_player *player,
    const char *name,
    const char *value
) {
    return player->api.set_option_string(player->handle, name, value) < 0
        ? CMP_MPV_INITIALIZATION_FAILED
        : CMP_MPV_OK;
}

int cmp_mpv_probe(
    const char *library_path,
    int *client_api_major,
    int *client_api_minor
) {
    if (client_api_major == NULL || client_api_minor == NULL) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    cmp_mpv_api api;
    int status = cmp_mpv_open_api(library_path, &api);
    if (status != CMP_MPV_OK) {
        return status;
    }
    uint64_t version = api.client_api_version();
    *client_api_major = (int)((version >> 16U) & 0xffffU);
    *client_api_minor = (int)(version & 0xffffU);
    cmp_mpv_close_api(&api);
    return CMP_MPV_OK;
}

cmp_mpv_player *cmp_mpv_player_create(
    const char *library_path,
    const char *subtitle_fonts_directory,
    int preserve_ass_styles,
    int use_embedded_fonts,
    int renderer,
    uintptr_t surface_layer,
    int *status
) {
    if (status == NULL ||
        (renderer != CMP_MPV_RENDERER_SOFTWARE &&
         renderer != CMP_MPV_RENDERER_IOSVK) ||
        (renderer == CMP_MPV_RENDERER_IOSVK && surface_layer == 0)) {
        if (status != NULL) {
            *status = CMP_MPV_INVALID_ARGUMENT;
        }
        return NULL;
    }
    *status = CMP_MPV_INITIALIZATION_FAILED;

    cmp_mpv_player *player = calloc(1, sizeof(*player));
    if (player == NULL) {
        return NULL;
    }
    *status = cmp_mpv_open_api(library_path, &player->api);
    if (*status != CMP_MPV_OK) {
        free(player);
        return NULL;
    }
    if (renderer == CMP_MPV_RENDERER_IOSVK &&
        !cmp_mpv_has_iosvk_capability(&player->api)) {
        *status = CMP_MPV_INITIALIZATION_FAILED;
        cmp_mpv_close_api(&player->api);
        free(player);
        return NULL;
    }

    player->handle = player->api.create();
    if (player->handle == NULL) {
        cmp_mpv_close_api(&player->api);
        free(player);
        return NULL;
    }

    const char *const_options[][2] = {
        {"config", "no"},
        {"load-scripts", "no"},
        {"input-default-bindings", "no"},
        {"input-vo-keyboard", "no"},
        {"osc", "no"},
        {"keep-open", "yes"},
        {"terminal", "no"},
        {"tls-verify", "yes"},
        {"sub-ass-override", preserve_ass_styles ? "no" : "strip"},
        {"embeddedfonts", use_embedded_fonts ? "yes" : "no"},
    };
    const size_t option_count = sizeof(const_options) / sizeof(const_options[0]);
    for (size_t index = 0; index < option_count; ++index) {
        *status = cmp_mpv_set_option(
            player,
            const_options[index][0],
            const_options[index][1]
        );
        if (*status != CMP_MPV_OK) {
            cmp_mpv_player_destroy(player);
            return NULL;
        }
    }
    const char *renderer_options[][2] = {
        {"vo", renderer == CMP_MPV_RENDERER_IOSVK ? "gpu-next" : "libmpv"},
        {"hwdec", renderer == CMP_MPV_RENDERER_IOSVK
            ? "auto-safe"
            : "auto-copy-safe"},
    };
    const size_t renderer_option_count =
        sizeof(renderer_options) / sizeof(renderer_options[0]);
    for (size_t index = 0; index < renderer_option_count; ++index) {
        *status = cmp_mpv_set_option(
            player,
            renderer_options[index][0],
            renderer_options[index][1]
        );
        if (*status != CMP_MPV_OK) {
            cmp_mpv_player_destroy(player);
            return NULL;
        }
    }
    if (renderer == CMP_MPV_RENDERER_IOSVK) {
        const char *iosvk_options[][2] = {
            {"gpu-api", "vulkan"},
            {"gpu-context", "iosvk"},
        };
        const size_t iosvk_option_count =
            sizeof(iosvk_options) / sizeof(iosvk_options[0]);
        for (size_t index = 0; index < iosvk_option_count; ++index) {
            *status = cmp_mpv_set_option(
                player,
                iosvk_options[index][0],
                iosvk_options[index][1]
            );
            if (*status != CMP_MPV_OK) {
                cmp_mpv_player_destroy(player);
                return NULL;
            }
        }
        char wid[3U * sizeof(uintptr_t) + 1U];
        int length = snprintf(wid, sizeof(wid), "%" PRIuPTR, surface_layer);
        if (length <= 0 || (size_t)length >= sizeof(wid)) {
            *status = CMP_MPV_INVALID_ARGUMENT;
            cmp_mpv_player_destroy(player);
            return NULL;
        }
        *status = cmp_mpv_set_option(player, "wid", wid);
        if (*status != CMP_MPV_OK) {
            cmp_mpv_player_destroy(player);
            return NULL;
        }
    }
    if (subtitle_fonts_directory != NULL) {
        *status = cmp_mpv_set_option(
            player,
            "sub-fonts-dir",
            subtitle_fonts_directory
        );
        if (*status != CMP_MPV_OK) {
            cmp_mpv_player_destroy(player);
            return NULL;
        }
    }
    if (player->api.initialize(player->handle) < 0) {
        cmp_mpv_player_destroy(player);
        return NULL;
    }

    if (renderer == CMP_MPV_RENDERER_SOFTWARE) {
        const char *api_type = "sw";
        mpv_render_param render_parameters[] = {
            {MPV_RENDER_PARAM_API_TYPE, (void *)api_type},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        if (player->api.render_context_create(
                &player->render_context,
                player->handle,
                render_parameters) < 0 ||
            player->render_context == NULL) {
            cmp_mpv_player_destroy(player);
            return NULL;
        }
    }

    player->renderer = renderer;
    *status = CMP_MPV_OK;
    return player;
}

void cmp_mpv_player_destroy(cmp_mpv_player *player) {
    if (player == NULL) {
        return;
    }
    if (player->render_context != NULL) {
        player->api.render_context_free(player->render_context);
        player->render_context = NULL;
    }
    if (player->handle != NULL) {
        player->api.terminate_destroy(player->handle);
        player->handle = NULL;
    }
    cmp_mpv_close_api(&player->api);
    free(player);
}

int cmp_mpv_player_command(
    cmp_mpv_player *player,
    const char *const *arguments
) {
    if (player == NULL || player->handle == NULL ||
        arguments == NULL || arguments[0] == NULL) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    return player->api.command(player->handle, arguments) < 0
        ? CMP_MPV_COMMAND_FAILED
        : CMP_MPV_OK;
}

int cmp_mpv_player_set_property(
    cmp_mpv_player *player,
    const char *name,
    const char *value
) {
    if (player == NULL || player->handle == NULL ||
        name == NULL || value == NULL) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    return player->api.set_property_string(player->handle, name, value) < 0
        ? CMP_MPV_COMMAND_FAILED
        : CMP_MPV_OK;
}

int cmp_mpv_player_set_string_list_property(
    cmp_mpv_player *player,
    const char *name,
    const char *const *values,
    size_t count
) {
    if (player == NULL || player->handle == NULL || name == NULL ||
        count > (size_t)INT_MAX || (count > 0 && values == NULL)) {
        return CMP_MPV_INVALID_ARGUMENT;
    }

    mpv_node *nodes = NULL;
    if (count > 0) {
        nodes = calloc(count, sizeof(*nodes));
        if (nodes == NULL) {
            return CMP_MPV_COMMAND_FAILED;
        }
        for (size_t index = 0; index < count; ++index) {
            if (values[index] == NULL) {
                free(nodes);
                return CMP_MPV_INVALID_ARGUMENT;
            }
            nodes[index].u.string = (char *)values[index];
            nodes[index].format = MPV_FORMAT_STRING;
        }
    }

    mpv_node_list list = {
        .num = (int)count,
        .values = nodes,
        .keys = NULL,
    };
    mpv_node root = {
        .u.list = &list,
        .format = MPV_FORMAT_NODE_ARRAY,
    };
    const int result = player->api.set_property(
        player->handle,
        name,
        MPV_FORMAT_NODE,
        &root
    );
    free(nodes);
    return result < 0 ? CMP_MPV_COMMAND_FAILED : CMP_MPV_OK;
}

char *cmp_mpv_player_get_property(
    cmp_mpv_player *player,
    const char *name
) {
    if (player == NULL || player->handle == NULL || name == NULL) {
        return NULL;
    }
    return player->api.get_property_string(player->handle, name);
}

void cmp_mpv_player_free_property(
    cmp_mpv_player *player,
    char *value
) {
    if (player != NULL && value != NULL) {
        player->api.free_value(value);
    }
}

int cmp_mpv_player_wait_event(
    cmp_mpv_player *player,
    double timeout_seconds,
    cmp_mpv_event *event
) {
    if (player == NULL || player->handle == NULL ||
        event == NULL || timeout_seconds < 0.0) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    event->event_id = CMP_MPV_EVENT_NONE;
    event->end_file_reason = 0;
    event->error_code = 0;

    mpv_event *native_event =
        player->api.wait_event(player->handle, timeout_seconds);
    if (native_event == NULL) {
        return CMP_MPV_OK;
    }
    event->event_id = native_event->event_id;
    if (native_event->event_id == CMP_MPV_EVENT_END_FILE &&
        native_event->data != NULL) {
        mpv_event_end_file_prefix *end_file =
            (mpv_event_end_file_prefix *)native_event->data;
        event->end_file_reason = end_file->reason;
        event->error_code = end_file->error;
    }
    return CMP_MPV_OK;
}

void cmp_mpv_player_wakeup(cmp_mpv_player *player) {
    if (player != NULL && player->handle != NULL) {
        player->api.wakeup(player->handle);
    }
}

int cmp_mpv_player_render_bgr0(
    cmp_mpv_player *player,
    int width,
    int height,
    size_t row_bytes,
    void *pixels
) {
    if (player == NULL || player->render_context == NULL ||
        player->renderer != CMP_MPV_RENDERER_SOFTWARE ||
        width <= 0 || height <= 0 || pixels == NULL ||
        row_bytes < (size_t)width * 4U) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    int size[] = {width, height};
    const char *format = "bgr0";
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_SW_SIZE, size},
        {MPV_RENDER_PARAM_SW_FORMAT, (void *)format},
        {MPV_RENDER_PARAM_SW_STRIDE, &row_bytes},
        {MPV_RENDER_PARAM_SW_POINTER, pixels},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    return player->api.render_context_render(
               player->render_context,
               parameters) < 0
        ? CMP_MPV_RENDER_FAILED
        : CMP_MPV_OK;
}

int cmp_mpv_player_switch_to_software(cmp_mpv_player *player) {
    if (player == NULL || player->handle == NULL ||
        player->render_context != NULL ||
        player->renderer != CMP_MPV_RENDERER_IOSVK) {
        return CMP_MPV_INVALID_ARGUMENT;
    }
    if (player->api.set_property_string(
            player->handle,
            "vo",
            "libmpv") < 0) {
        return CMP_MPV_COMMAND_FAILED;
    }
    if (player->api.set_property_string(
            player->handle,
            "hwdec",
            "auto-copy-safe") < 0) {
        return CMP_MPV_COMMAND_FAILED;
    }
    const char *api_type = "sw";
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)api_type},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    if (player->api.render_context_create(
            &player->render_context,
            player->handle,
            parameters) < 0 ||
        player->render_context == NULL) {
        return CMP_MPV_RENDER_FAILED;
    }
    player->renderer = CMP_MPV_RENDERER_SOFTWARE;
    return CMP_MPV_OK;
}
