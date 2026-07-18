#include "JbrWaylandSurface.h"
#include "WaylandOverlaySurface.h"

#include <stdlib.h>
#include <string.h>
#include <wayland-client.h>

typedef struct JbrWaylandApi {
    jclass component_class;
    jclass graphics_configuration_class;
    jclass graphics_device_class;
    jclass graphics_environment_class;
    jclass point_class;
    jclass swing_utilities_class;
    jclass toolkit_class;
    jclass wl_toolkit_class;
    jclass wl_component_peer_class;
    jclass wl_display_class;
    jclass wl_graphics_device_class;
    jclass wl_subsurface_class;
    jclass wl_surface_class;
    jclass sun_toolkit_class;
    jmethodID component_get_location_on_screen;
    jmethodID component_get_width;
    jmethodID component_get_height;
    jmethodID component_get_graphics_configuration;
    jmethodID graphics_configuration_get_device;
    jmethodID graphics_environment_get_local;
    jmethodID graphics_environment_get_default_device;
    jmethodID swing_get_window_ancestor;
    jmethodID toolkit_get_default;
    jmethodID wl_toolkit_peer_for_target;
    jmethodID wl_peer_get_surface;
    jmethodID wl_peer_java_units_to_surface_units;
    jmethodID wl_peer_java_units_to_surface_size;
    jmethodID wl_peer_java_size_to_buffer_size;
    jmethodID wl_display_get_instance;
    jmethodID wl_display_get_ptr;
    jmethodID wl_surface_get_ptr;
    jmethodID wl_surface_commit;
    jmethodID wl_surface_dispose;
    jmethodID wl_surface_set_size;
    jmethodID wl_subsurface_constructor;
    jmethodID wl_graphics_device_get_id;
    jmethodID awt_lock;
    jmethodID awt_unlock;
    jfieldID point_x;
    jfieldID point_y;
    jfieldID wl_subsurface_ptr;
} JbrWaylandApi;

static int clear_exception(JNIEnv* env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    return 1;
}

static int load_api(JNIEnv* env, JbrWaylandApi* api) {
    memset(api, 0, sizeof(*api));

#define FIND_CLASS(field, name) \
    do { \
        api->field = (*env)->FindClass(env, name); \
        if (!api->field || clear_exception(env)) return 0; \
    } while (0)
#define GET_METHOD(field, cls, name, signature) \
    do { \
        api->field = (*env)->GetMethodID(env, api->cls, name, signature); \
        if (!api->field || clear_exception(env)) return 0; \
    } while (0)
#define GET_STATIC_METHOD(field, cls, name, signature) \
    do { \
        api->field = (*env)->GetStaticMethodID(env, api->cls, name, signature); \
        if (!api->field || clear_exception(env)) return 0; \
    } while (0)

    FIND_CLASS(component_class, "java/awt/Component");
    FIND_CLASS(graphics_configuration_class, "java/awt/GraphicsConfiguration");
    FIND_CLASS(graphics_device_class, "java/awt/GraphicsDevice");
    FIND_CLASS(graphics_environment_class, "java/awt/GraphicsEnvironment");
    FIND_CLASS(point_class, "java/awt/Point");
    FIND_CLASS(swing_utilities_class, "javax/swing/SwingUtilities");
    FIND_CLASS(toolkit_class, "java/awt/Toolkit");
    FIND_CLASS(wl_toolkit_class, "sun/awt/wl/WLToolkit");
    FIND_CLASS(wl_component_peer_class, "sun/awt/wl/WLComponentPeer");
    FIND_CLASS(wl_display_class, "sun/awt/wl/WLDisplay");
    FIND_CLASS(wl_graphics_device_class, "sun/awt/wl/WLGraphicsDevice");
    FIND_CLASS(wl_subsurface_class, "sun/awt/wl/WLSubSurface");
    FIND_CLASS(wl_surface_class, "sun/awt/wl/WLSurface");
    FIND_CLASS(sun_toolkit_class, "sun/awt/SunToolkit");

    GET_METHOD(component_get_location_on_screen, component_class, "getLocationOnScreen", "()Ljava/awt/Point;");
    GET_METHOD(component_get_width, component_class, "getWidth", "()I");
    GET_METHOD(component_get_height, component_class, "getHeight", "()I");
    GET_METHOD(component_get_graphics_configuration, component_class, "getGraphicsConfiguration", "()Ljava/awt/GraphicsConfiguration;");
    GET_METHOD(graphics_configuration_get_device, graphics_configuration_class, "getDevice", "()Ljava/awt/GraphicsDevice;");
    GET_STATIC_METHOD(
        graphics_environment_get_local,
        graphics_environment_class,
        "getLocalGraphicsEnvironment",
        "()Ljava/awt/GraphicsEnvironment;"
    );
    GET_METHOD(
        graphics_environment_get_default_device,
        graphics_environment_class,
        "getDefaultScreenDevice",
        "()Ljava/awt/GraphicsDevice;"
    );
    GET_STATIC_METHOD(swing_get_window_ancestor, swing_utilities_class, "getWindowAncestor", "(Ljava/awt/Component;)Ljava/awt/Window;");
    GET_STATIC_METHOD(toolkit_get_default, toolkit_class, "getDefaultToolkit", "()Ljava/awt/Toolkit;");
    GET_METHOD(wl_toolkit_peer_for_target, wl_toolkit_class, "peerForTarget", "(Ljava/lang/Object;)Ljava/lang/Object;");
    GET_METHOD(wl_peer_get_surface, wl_component_peer_class, "getSurface", "()Lsun/awt/wl/WLMainSurface;");
    GET_METHOD(wl_peer_java_units_to_surface_units, wl_component_peer_class, "javaUnitsToSurfaceUnits", "(I)I");
    GET_METHOD(wl_peer_java_units_to_surface_size, wl_component_peer_class, "javaUnitsToSurfaceSize", "(I)I");
    GET_METHOD(wl_peer_java_size_to_buffer_size, wl_component_peer_class, "javaSizeToBufferSize", "(I)I");
    GET_STATIC_METHOD(wl_display_get_instance, wl_display_class, "getInstance", "()Lsun/awt/wl/WLDisplay;");
    GET_METHOD(wl_display_get_ptr, wl_display_class, "getDisplayPtr", "()J");
    GET_METHOD(wl_surface_get_ptr, wl_surface_class, "getWlSurfacePtr", "()J");
    GET_METHOD(wl_surface_commit, wl_surface_class, "commit", "()V");
    GET_METHOD(wl_surface_dispose, wl_surface_class, "dispose", "()V");
    GET_METHOD(wl_surface_set_size, wl_surface_class, "setSize", "(II)V");
    api->wl_subsurface_constructor =
        (*env)->GetMethodID(
            env,
            api->wl_subsurface_class,
            "<init>",
            "(Lsun/awt/wl/WLMainSurface;II)V"
        );
    if (!api->wl_subsurface_constructor || clear_exception(env)) return 0;
    GET_METHOD(wl_graphics_device_get_id, wl_graphics_device_class, "getID", "()I");
    GET_STATIC_METHOD(awt_lock, sun_toolkit_class, "awtLock", "()V");
    GET_STATIC_METHOD(awt_unlock, sun_toolkit_class, "awtUnlock", "()V");

    api->point_x = (*env)->GetFieldID(env, api->point_class, "x", "I");
    api->point_y = (*env)->GetFieldID(env, api->point_class, "y", "I");
    api->wl_subsurface_ptr =
        (*env)->GetFieldID(env, api->wl_subsurface_class, "wlSubSurfacePtr", "J");
    if (!api->point_x || !api->point_y || !api->wl_subsurface_ptr || clear_exception(env)) return 0;

#undef FIND_CLASS
#undef GET_METHOD
#undef GET_STATIC_METHOD
    return 1;
}

static int create_subsurface_pair_locked(
    JNIEnv* env,
    const JbrWaylandApi* api,
    jobject main_surface,
    int64_t main_surface_ptr,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    jobject* video_surface_out,
    jobject* overlay_surface_out,
    int64_t* video_surface_ptr_out,
    int64_t* overlay_surface_ptr_out,
    int64_t* video_subsurface_ptr_out,
    int64_t* overlay_subsurface_ptr_out
) {
    jobject video_surface =
        (*env)->NewObject(
            env,
            api->wl_subsurface_class,
            api->wl_subsurface_constructor,
            main_surface,
            (jint)x,
            (jint)y
        );
    if (!video_surface || clear_exception(env)) return 0;

    jobject overlay_surface =
        (*env)->NewObject(
            env,
            api->wl_subsurface_class,
            api->wl_subsurface_constructor,
            main_surface,
            (jint)x,
            (jint)y
        );
    if (!overlay_surface || clear_exception(env)) {
        (*env)->CallVoidMethod(env, video_surface, api->wl_surface_dispose);
        clear_exception(env);
        return 0;
    }

    jlong video_surface_ptr =
        (*env)->CallLongMethod(env, video_surface, api->wl_surface_get_ptr);
    jlong overlay_surface_ptr =
        (*env)->CallLongMethod(env, overlay_surface, api->wl_surface_get_ptr);
    jlong video_subsurface_ptr =
        (*env)->GetLongField(env, video_surface, api->wl_subsurface_ptr);
    jlong overlay_subsurface_ptr =
        (*env)->GetLongField(env, overlay_surface, api->wl_subsurface_ptr);
    if (clear_exception(env) || !video_surface_ptr || !overlay_surface_ptr ||
        !video_subsurface_ptr || !overlay_subsurface_ptr) {
        (*env)->CallVoidMethod(env, overlay_surface, api->wl_surface_dispose);
        (*env)->CallVoidMethod(env, video_surface, api->wl_surface_dispose);
        clear_exception(env);
        return 0;
    }

    struct wl_surface* main_wl_surface = (struct wl_surface*)(uintptr_t)main_surface_ptr;
    struct wl_surface* video_wl_surface = (struct wl_surface*)(uintptr_t)video_surface_ptr;
    struct wl_subsurface* video_wl_subsurface =
        (struct wl_subsurface*)(uintptr_t)video_subsurface_ptr;
    struct wl_subsurface* overlay_wl_subsurface =
        (struct wl_subsurface*)(uintptr_t)overlay_subsurface_ptr;

    wl_subsurface_set_desync(video_wl_subsurface);
    wl_subsurface_set_desync(overlay_wl_subsurface);
    wl_subsurface_place_above(video_wl_subsurface, main_wl_surface);
    wl_subsurface_place_above(overlay_wl_subsurface, video_wl_surface);
    (*env)->CallVoidMethod(env, video_surface, api->wl_surface_set_size, width, height);
    (*env)->CallVoidMethod(env, overlay_surface, api->wl_surface_set_size, width, height);
    (*env)->CallVoidMethod(env, main_surface, api->wl_surface_commit);
    if (clear_exception(env)) {
        (*env)->CallVoidMethod(env, overlay_surface, api->wl_surface_dispose);
        (*env)->CallVoidMethod(env, video_surface, api->wl_surface_dispose);
        clear_exception(env);
        return 0;
    }

    *video_surface_out = video_surface;
    *overlay_surface_out = overlay_surface;
    *video_surface_ptr_out = (int64_t)video_surface_ptr;
    *overlay_surface_ptr_out = (int64_t)overlay_surface_ptr;
    *video_subsurface_ptr_out = (int64_t)video_subsurface_ptr;
    *overlay_subsurface_ptr_out = (int64_t)overlay_subsurface_ptr;
    return 1;
}

static int read_java_geometry(
    JNIEnv* env,
    const JbrWaylandApi* api,
    jobject component,
    jobject* window_out,
    jint* x_out,
    jint* y_out,
    jint* width_out,
    jint* height_out,
    jint* output_id_out
) {
    jobject window = (*env)->CallStaticObjectMethod(env, api->swing_utilities_class, api->swing_get_window_ancestor, component);
    if (!window || clear_exception(env)) return 0;

    jobject component_point = (*env)->CallObjectMethod(env, component, api->component_get_location_on_screen);
    jobject window_point = (*env)->CallObjectMethod(env, window, api->component_get_location_on_screen);
    if (!component_point || !window_point || clear_exception(env)) return 0;

    jint component_x = (*env)->GetIntField(env, component_point, api->point_x);
    jint component_y = (*env)->GetIntField(env, component_point, api->point_y);
    jint window_x = (*env)->GetIntField(env, window_point, api->point_x);
    jint window_y = (*env)->GetIntField(env, window_point, api->point_y);
    jint width = (*env)->CallIntMethod(env, component, api->component_get_width);
    jint height = (*env)->CallIntMethod(env, component, api->component_get_height);
    if (clear_exception(env) || width <= 0 || height <= 0) return 0;

    jint output_id = -1;
    jobject configuration = (*env)->CallObjectMethod(env, component, api->component_get_graphics_configuration);
    if (configuration && !clear_exception(env)) {
        jobject device = (*env)->CallObjectMethod(env, configuration, api->graphics_configuration_get_device);
        if (device && !clear_exception(env) && (*env)->IsInstanceOf(env, device, api->wl_graphics_device_class)) {
            output_id = (*env)->CallIntMethod(env, device, api->wl_graphics_device_get_id);
            if (clear_exception(env)) output_id = -1;
        }
    } else {
        clear_exception(env);
    }

    *window_out = window;
    *x_out = component_x - window_x;
    *y_out = component_y - window_y;
    *width_out = width;
    *height_out = height;
    *output_id_out = output_id;
    return 1;
}

static int capture_locked(
    JNIEnv* env,
    const JbrWaylandApi* api,
    jobject window,
    jint java_x,
    jint java_y,
    jint java_width,
    jint java_height,
    jobject* main_surface_out,
    int64_t* display_ptr_out,
    int64_t* surface_ptr_out,
    int32_t* x_out,
    int32_t* y_out,
    int32_t* width_out,
    int32_t* height_out,
    int32_t* buffer_width_out,
    int32_t* buffer_height_out
) {
    jobject toolkit = (*env)->CallStaticObjectMethod(env, api->toolkit_class, api->toolkit_get_default);
    if (!toolkit || clear_exception(env) || !(*env)->IsInstanceOf(env, toolkit, api->wl_toolkit_class)) return 0;

    jobject peer = (*env)->CallObjectMethod(env, toolkit, api->wl_toolkit_peer_for_target, window);
    if (!peer || clear_exception(env) || !(*env)->IsInstanceOf(env, peer, api->wl_component_peer_class)) return 0;

    jobject main_surface = (*env)->CallObjectMethod(env, peer, api->wl_peer_get_surface);
    if (!main_surface || clear_exception(env)) return 0;

    jobject display = (*env)->CallStaticObjectMethod(env, api->wl_display_class, api->wl_display_get_instance);
    if (!display || clear_exception(env)) return 0;

    jlong display_ptr = (*env)->CallLongMethod(env, display, api->wl_display_get_ptr);
    jlong surface_ptr = (*env)->CallLongMethod(env, main_surface, api->wl_surface_get_ptr);
    jint x = (*env)->CallIntMethod(env, peer, api->wl_peer_java_units_to_surface_units, java_x);
    jint y = (*env)->CallIntMethod(env, peer, api->wl_peer_java_units_to_surface_units, java_y);
    jint width = (*env)->CallIntMethod(env, peer, api->wl_peer_java_units_to_surface_size, java_width);
    jint height = (*env)->CallIntMethod(env, peer, api->wl_peer_java_units_to_surface_size, java_height);
    jint buffer_width =
        (*env)->CallIntMethod(env, peer, api->wl_peer_java_size_to_buffer_size, java_width);
    jint buffer_height =
        (*env)->CallIntMethod(env, peer, api->wl_peer_java_size_to_buffer_size, java_height);
    if (clear_exception(env) || display_ptr == 0 || surface_ptr == 0 ||
        width <= 0 || height <= 0 || buffer_width <= 0 || buffer_height <= 0) return 0;

    *main_surface_out = main_surface;
    *display_ptr_out = (int64_t)display_ptr;
    *surface_ptr_out = (int64_t)surface_ptr;
    *x_out = (int32_t)x;
    *y_out = (int32_t)y;
    *width_out = (int32_t)width;
    *height_out = (int32_t)height;
    *buffer_width_out = (int32_t)buffer_width;
    *buffer_height_out = (int32_t)buffer_height;
    return 1;
}

int jbr_wayland_api_available(JNIEnv* env) {
    JbrWaylandApi api;
    return env && load_api(env, &api);
}

int jbr_wayland_with_display(
    JNIEnv* env,
    int32_t requested_output_id,
    JbrWaylandDisplayCallback callback,
    void* user_data
) {
    if (!env || !callback) return 0;

    JbrWaylandApi api;
    if (!load_api(env, &api)) return 0;

    jobject toolkit = (*env)->CallStaticObjectMethod(env, api.toolkit_class, api.toolkit_get_default);
    if (!toolkit || clear_exception(env) || !(*env)->IsInstanceOf(env, toolkit, api.wl_toolkit_class)) return 0;

    jobject display = (*env)->CallStaticObjectMethod(env, api.wl_display_class, api.wl_display_get_instance);
    if (!display || clear_exception(env)) return 0;
    jlong display_ptr = (*env)->CallLongMethod(env, display, api.wl_display_get_ptr);
    if (clear_exception(env) || display_ptr == 0) return 0;

    int32_t output_id = requested_output_id;
    if (output_id < 0) {
        jobject graphics_environment =
            (*env)->CallStaticObjectMethod(
                env,
                api.graphics_environment_class,
                api.graphics_environment_get_local
            );
        jobject device =
            graphics_environment
                ? (*env)->CallObjectMethod(
                    env,
                    graphics_environment,
                    api.graphics_environment_get_default_device
                )
                : NULL;
        if (!clear_exception(env) && device &&
            (*env)->IsInstanceOf(env, device, api.wl_graphics_device_class)) {
            output_id =
                (int32_t)(*env)->CallIntMethod(env, device, api.wl_graphics_device_get_id);
            if (clear_exception(env)) output_id = -1;
        } else {
            clear_exception(env);
        }
    }

    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_lock);
    if (clear_exception(env)) return 0;
    int result = callback((uintptr_t)display_ptr, output_id, user_data);
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_unlock);
    return clear_exception(env) ? 0 : result;
}

JbrWaylandSurface* jbr_wayland_surface_capture(JNIEnv* env, jobject component) {
    if (!env || !component) return NULL;

    JbrWaylandApi api;
    if (!load_api(env, &api)) return NULL;

    jobject window = NULL;
    jint java_x = 0;
    jint java_y = 0;
    jint java_width = 0;
    jint java_height = 0;
    jint output_id = -1;
    if (!read_java_geometry(
            env,
            &api,
            component,
            &window,
            &java_x,
            &java_y,
            &java_width,
            &java_height,
            &output_id)) {
        return NULL;
    }

    JbrWaylandSurface* result = calloc(1, sizeof(*result));
    if (!result) return NULL;

    jobject main_surface = NULL;
    jobject video_surface = NULL;
    jobject overlay_surface = NULL;
    int64_t display_ptr = 0;
    int64_t surface_ptr = 0;
    int64_t video_surface_ptr = 0;
    int64_t overlay_surface_ptr = 0;
    int64_t video_subsurface_ptr = 0;
    int64_t overlay_subsurface_ptr = 0;
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t buffer_width = 0;
    int32_t buffer_height = 0;
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_lock);
    if (clear_exception(env)) {
        free(result);
        return NULL;
    }
    int captured = capture_locked(
        env,
        &api,
        window,
        java_x,
        java_y,
        java_width,
        java_height,
        &main_surface,
        &display_ptr,
        &surface_ptr,
        &x,
        &y,
        &width,
        &height,
        &buffer_width,
        &buffer_height
    );
    int has_subsurface_pair =
        captured &&
        create_subsurface_pair_locked(
            env,
            &api,
            main_surface,
            surface_ptr,
            x,
            y,
            width,
            height,
            &video_surface,
            &overlay_surface,
            &video_surface_ptr,
            &overlay_surface_ptr,
            &video_subsurface_ptr,
            &overlay_subsurface_ptr
        );
    WaylandOverlaySurface* overlay_renderer = NULL;
    if (has_subsurface_pair) {
        overlay_renderer = wayland_overlay_surface_create(
            (uintptr_t)display_ptr,
            (uintptr_t)overlay_surface_ptr
        );
        if (!overlay_renderer ||
            !wayland_overlay_surface_make_input_transparent(
                overlay_renderer,
                (uintptr_t)video_surface_ptr
            )) {
            if (overlay_renderer) wayland_overlay_surface_destroy(overlay_renderer);
            overlay_renderer = NULL;
            (*env)->CallVoidMethod(env, overlay_surface, api.wl_surface_dispose);
            (*env)->CallVoidMethod(env, video_surface, api.wl_surface_dispose);
            clear_exception(env);
            has_subsurface_pair = 0;
            video_surface = NULL;
            overlay_surface = NULL;
            video_surface_ptr = 0;
            overlay_surface_ptr = 0;
            video_subsurface_ptr = 0;
            overlay_subsurface_ptr = 0;
        }
    }
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_unlock);
    if (clear_exception(env) || !captured) {
        if (overlay_renderer) wayland_overlay_surface_destroy(overlay_renderer);
        free(result);
        return NULL;
    }

    result->has_subsurface_pair = has_subsurface_pair;
    result->overlay_renderer = overlay_renderer;
    result->component = (*env)->NewGlobalRef(env, component);
    result->main_surface = (*env)->NewGlobalRef(env, main_surface);
    if (has_subsurface_pair) {
        result->video_surface = (*env)->NewGlobalRef(env, video_surface);
        result->overlay_surface = (*env)->NewGlobalRef(env, overlay_surface);
    }
    if (!result->component || !result->main_surface ||
        (has_subsurface_pair && (!result->video_surface || !result->overlay_surface)) ||
        clear_exception(env)) {
        jbr_wayland_surface_destroy(env, result);
        return NULL;
    }
    result->display_ptr = display_ptr;
    result->surface_ptr = surface_ptr;
    result->video_surface_ptr = video_surface_ptr;
    result->overlay_surface_ptr = overlay_surface_ptr;
    result->video_subsurface_ptr = video_subsurface_ptr;
    result->overlay_subsurface_ptr = overlay_subsurface_ptr;
    result->output_id = (int32_t)output_id;
    result->x = x;
    result->y = y;
    result->width = width;
    result->height = height;
    result->buffer_width = buffer_width;
    result->buffer_height = buffer_height;
    return result;
}

int jbr_wayland_surface_refresh(JNIEnv* env, JbrWaylandSurface* surface) {
    if (!env || !surface || !surface->component) return 0;
    JbrWaylandApi api;
    if (!load_api(env, &api)) return 0;

    jobject window = NULL;
    jint java_x = 0;
    jint java_y = 0;
    jint java_width = 0;
    jint java_height = 0;
    jint output_id = -1;
    if (!read_java_geometry(
            env,
            &api,
            surface->component,
            &window,
            &java_x,
            &java_y,
            &java_width,
            &java_height,
            &output_id)) {
        return 0;
    }

    jobject current_main_surface = NULL;
    int64_t display_ptr = 0;
    int64_t surface_ptr = 0;
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t buffer_width = 0;
    int32_t buffer_height = 0;
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_lock);
    if (clear_exception(env)) return 0;
    int captured = capture_locked(
        env,
        &api,
        window,
        java_x,
        java_y,
        java_width,
        java_height,
        &current_main_surface,
        &display_ptr,
        &surface_ptr,
        &x,
        &y,
        &width,
        &height,
        &buffer_width,
        &buffer_height
    );
    int same_parent =
        captured &&
        display_ptr == surface->display_ptr &&
        surface_ptr == surface->surface_ptr;
    if (same_parent && surface->has_subsurface_pair) {
        wl_subsurface_set_position(
            (struct wl_subsurface*)(uintptr_t)surface->video_subsurface_ptr,
            x,
            y
        );
        wl_subsurface_set_position(
            (struct wl_subsurface*)(uintptr_t)surface->overlay_subsurface_ptr,
            x,
            y
        );
        (*env)->CallVoidMethod(
            env,
            surface->video_surface,
            api.wl_surface_set_size,
            width,
            height
        );
        (*env)->CallVoidMethod(
            env,
            surface->overlay_surface,
            api.wl_surface_set_size,
            width,
            height
        );
        (*env)->CallVoidMethod(env, surface->main_surface, api.wl_surface_commit);
        if (clear_exception(env)) same_parent = 0;
    }
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_unlock);
    if (clear_exception(env) || !captured) return 0;
    if (!same_parent) return -1;

    surface->output_id = (int32_t)output_id;
    surface->x = x;
    surface->y = y;
    surface->width = width;
    surface->height = height;
    surface->buffer_width = buffer_width;
    surface->buffer_height = buffer_height;
    return 1;
}

int jbr_wayland_surface_update_overlay(
    JNIEnv* env,
    JbrWaylandSurface* surface,
    const void* pixels,
    size_t row_bytes,
    int32_t width,
    int32_t height
) {
    if (!env || !surface || !surface->has_subsurface_pair ||
        !surface->display_ptr || !surface->overlay_surface_ptr) return 0;
    JbrWaylandApi api;
    if (!load_api(env, &api)) return 0;
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_lock);
    if (clear_exception(env)) return 0;
    int result =
        surface->overlay_renderer
            ? wayland_overlay_surface_update(
                surface->overlay_renderer,
                pixels,
                row_bytes,
                width,
                height
            )
            : 0;
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_unlock);
    return clear_exception(env) ? 0 : result;
}

void jbr_wayland_surface_clear_overlay(JNIEnv* env, JbrWaylandSurface* surface) {
    if (!env || !surface || !surface->overlay_renderer) return;
    JbrWaylandApi api;
    if (!load_api(env, &api)) return;
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_lock);
    if (clear_exception(env)) return;
    wayland_overlay_surface_clear(surface->overlay_renderer);
    (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_unlock);
    clear_exception(env);
}

void jbr_wayland_surface_destroy(JNIEnv* env, JbrWaylandSurface* surface) {
    if (!surface) return;
    if (env && surface->has_subsurface_pair) {
        JbrWaylandApi api;
        if (load_api(env, &api)) {
            (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_lock);
            if (!clear_exception(env)) {
                if (surface->overlay_renderer) {
                    wayland_overlay_surface_destroy(surface->overlay_renderer);
                    surface->overlay_renderer = NULL;
                }
                if (surface->overlay_surface) {
                    (*env)->CallVoidMethod(
                        env,
                        surface->overlay_surface,
                        api.wl_surface_dispose
                    );
                }
                if (surface->video_surface) {
                    (*env)->CallVoidMethod(
                        env,
                        surface->video_surface,
                        api.wl_surface_dispose
                    );
                }
                clear_exception(env);
                (*env)->CallStaticVoidMethod(env, api.sun_toolkit_class, api.awt_unlock);
                clear_exception(env);
            }
        }
    }
    if (env && surface->component) (*env)->DeleteGlobalRef(env, surface->component);
    if (env && surface->main_surface) (*env)->DeleteGlobalRef(env, surface->main_surface);
    if (env && surface->video_surface) (*env)->DeleteGlobalRef(env, surface->video_surface);
    if (env && surface->overlay_surface) (*env)->DeleteGlobalRef(env, surface->overlay_surface);
    free(surface);
}
