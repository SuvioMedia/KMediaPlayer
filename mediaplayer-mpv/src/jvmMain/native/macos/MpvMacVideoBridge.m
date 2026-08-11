#include <jni.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#import <AppKit/AppKit.h>
#import <CoreFoundation/CoreFoundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <CoreVideo/CoreVideo.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/QuartzCore.h>

typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;

typedef struct {
    int type;
    void* data;
} mpv_render_param;

typedef struct {
    void* (*get_proc_address)(void* context, const char* name);
    void* get_proc_address_context;
} mpv_opengl_init_params;

typedef struct {
    int fbo;
    int width;
    int height;
    int internal_format;
} mpv_opengl_fbo;

typedef struct {
    uint64_t flags;
    int64_t target_time;
} mpv_render_frame_info;

enum {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y = 4,
    MPV_RENDER_PARAM_DEPTH = 5,
    MPV_RENDER_PARAM_ADVANCED_CONTROL = 10,
    MPV_RENDER_PARAM_NEXT_FRAME_INFO = 11,
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12,
};

enum {
    MPV_RENDER_UPDATE_FRAME = 1 << 0,
};

enum {
    MPV_RENDER_FRAME_INFO_PRESENT = 1 << 0,
    MPV_RENDER_FRAME_INFO_REDRAW = 1 << 1,
    MPV_RENDER_FRAME_INFO_REPEAT = 1 << 2,
};

enum {
    KMP_MPV_PROJECTION_ENABLED = 0,
    KMP_MPV_PROJECTION_TYPE = 1,
    KMP_MPV_PROJECTION_FOV = 2,
    KMP_MPV_PROJECTION_STEREO = 3,
    KMP_MPV_PROJECTION_LEFT_WINDOW = 4,
    KMP_MPV_PROJECTION_LEFT_ROTATION = 8,
    KMP_MPV_PROJECTION_RIGHT_WINDOW = 9,
    KMP_MPV_PROJECTION_RIGHT_ROTATION = 13,
    KMP_MPV_PROJECTION_YAW = 14,
    KMP_MPV_PROJECTION_PITCH = 15,
    KMP_MPV_PROJECTION_ROLL = 16,
    KMP_MPV_PROJECTION_ZOOM = 17,
    KMP_MPV_PROJECTION_PARAMETER_COUNT = 18,
};

enum {
    KMP_MPV_CONTENT_SCALE_FIT = 0,
    KMP_MPV_CONTENT_SCALE_CROP = 1,
    KMP_MPV_CONTENT_SCALE_FILL = 2,
};

typedef int (*mpv_render_context_create_fn)(
    mpv_render_context** result,
    mpv_handle* handle,
    mpv_render_param* parameters
);
typedef void (*mpv_render_context_set_update_callback_fn)(
    mpv_render_context* context,
    void (*callback)(void* callback_context),
    void* callback_context
);
typedef uint64_t (*mpv_render_context_update_fn)(mpv_render_context* context);
typedef int (*mpv_render_context_get_info_fn)(
    mpv_render_context* context,
    mpv_render_param parameter
);
typedef int (*mpv_render_context_render_fn)(
    mpv_render_context* context,
    mpv_render_param* parameters
);
typedef void (*mpv_render_context_report_swap_fn)(mpv_render_context* context);
typedef void (*mpv_render_context_free_fn)(mpv_render_context* context);

typedef struct {
    mpv_render_context_create_fn create;
    mpv_render_context_set_update_callback_fn set_update_callback;
    mpv_render_context_update_fn update;
    mpv_render_context_get_info_fn get_info;
    mpv_render_context_render_fn render;
    mpv_render_context_report_swap_fn report_swap;
    mpv_render_context_free_fn free;
} KMPMpvRenderApi;

typedef struct KMPMpvNativeRenderer KMPMpvNativeRenderer;
@class KMPMpvOpenGLLayer;
@class KMPMpvVideoView;

struct KMPMpvNativeRenderer {
    atomic_int references;
    atomic_bool shutting_down;
    atomic_bool update_pending;
    atomic_bool redraw_requested;
    atomic_bool display_request_queued;
    atomic_bool live_resize_active;
    atomic_uint_fast64_t update_callback_count;
    atomic_uint_fast64_t draw_callback_count;
    atomic_uint_fast64_t rendered_frame_count;
    atomic_uint_fast64_t presented_frame_count;
    atomic_uint_fast64_t new_video_frame_count;
    atomic_uint_fast64_t repeated_video_frame_count;
    atomic_uint_fast64_t redraw_frame_count;
    atomic_uint_fast64_t empty_draw_count;
    atomic_uint_fast64_t display_wakeup_count;
    atomic_uint_fast64_t live_resize_display_wakeup_count;
    atomic_uint_fast64_t live_resize_geometry_update_count;
    atomic_uint_fast64_t maximum_live_resize_aspect_error_ppm;
    atomic_uint_fast64_t render_time_ns;
    atomic_uint_fast64_t maximum_render_time_ns;
    atomic_uint_fast64_t flush_time_ns;
    atomic_uint_fast64_t maximum_flush_time_ns;
    pthread_mutex_t render_mutex;
    int color_mode;
    int content_scale_mode;
    float media_aspect;
    int buffer_depth;
    int last_framebuffer;
    mpv_handle* mpv;
    void* libmpv_library;
    KMPMpvRenderApi api;
    mpv_render_context* render_context;
    CGLPixelFormatObj pixel_format;
    CGLContextObj gl_context;
    float projection_parameters[KMP_MPV_PROJECTION_PARAMETER_COUNT];
    GLuint projection_program;
    GLuint projection_vertex_array;
    GLuint projection_texture;
    GLuint projection_framebuffer;
    int projection_width;
    int projection_height;
    int projection_depth;
    // The renderer owns the native view and layer while Nucleus Tao mounts the view.
    KMPMpvOpenGLLayer* layer;
    KMPMpvVideoView* view;
};

static KMPMpvNativeRenderer* renderer_retain(KMPMpvNativeRenderer* renderer) {
    if (renderer) atomic_fetch_add_explicit(&renderer->references, 1, memory_order_relaxed);
    return renderer;
}

static void renderer_release(KMPMpvNativeRenderer* renderer) {
    if (!renderer) return;
    if (atomic_fetch_sub_explicit(&renderer->references, 1, memory_order_acq_rel) == 1) {
        pthread_mutex_destroy(&renderer->render_mutex);
        free(renderer);
    }
}

static void run_on_appkit_main_sync(dispatch_function_t operation, void* context) {
    if (pthread_main_np()) {
        operation(context);
    } else {
        dispatch_sync_f(dispatch_get_main_queue(), context, operation);
    }
}

static void gl_flush_noop(void) {
}

static void* resolve_opengl_function(void* context, const char* name) {
    (void)context;
    if (!name) return NULL;
    // CAOpenGLLayer is flushed exactly once after mpv finishes drawing. Letting libmpv flush its
    // command stream first makes every 8K frame wait twice for the same GPU work on macOS.
    if (strcmp(name, "glFlush") == 0) return (void*)&gl_flush_noop;

    CFBundleRef bundle = CFBundleGetBundleWithIdentifier(CFSTR("com.apple.opengl"));
    if (!bundle) return NULL;
    CFStringRef symbol = CFStringCreateWithCString(kCFAllocatorDefault, name, kCFStringEncodingASCII);
    if (!symbol) return NULL;
    void* address = CFBundleGetFunctionPointerForName(bundle, symbol);
    CFRelease(symbol);
    return address;
}

static uint64_t monotonic_nanos(void) {
    struct timespec time = {0};
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) return 0;
    return ((uint64_t)time.tv_sec * 1000000000ULL) + (uint64_t)time.tv_nsec;
}

static void atomic_update_maximum(atomic_uint_fast64_t* maximum, uint64_t value) {
    uint64_t observed = atomic_load_explicit(maximum, memory_order_relaxed);
    while (observed < value &&
           !atomic_compare_exchange_weak_explicit(
               maximum,
               &observed,
               value,
               memory_order_relaxed,
               memory_order_relaxed
           )) {
    }
}

static const GLchar* KMP_MPV_PROJECTION_VERTEX_SHADER =
    "#version 150 core\n"
    "out vec2 vUv;\n"
    "void main() {\n"
    "    vec2 positions[4] = vec2[4](\n"
    "        vec2(-1.0, -1.0), vec2(1.0, -1.0),\n"
    "        vec2(-1.0, 1.0), vec2(1.0, 1.0)\n"
    "    );\n"
    "    vec2 position = positions[gl_VertexID];\n"
    "    gl_Position = vec4(position, 0.0, 1.0);\n"
    "    vUv = (position + 1.0) * 0.5;\n"
    "}\n";

static const GLchar* KMP_MPV_PROJECTION_FRAGMENT_SHADER =
    "#version 150 core\n"
    "uniform sampler2D uTexture;\n"
    "uniform int uProjectionType;\n"
    "uniform float uFovDegrees;\n"
    "uniform int uStereo;\n"
    "uniform vec4 uLeftWindow;\n"
    "uniform int uLeftRotation;\n"
    "uniform vec4 uRightWindow;\n"
    "uniform int uRightRotation;\n"
    "uniform float uViewYawDegrees;\n"
    "uniform float uViewPitchDegrees;\n"
    "uniform float uViewRollDegrees;\n"
    "uniform float uViewZoom;\n"
    "uniform float uViewportAspect;\n"
    "uniform float uDestinationAspect;\n"
    "uniform float uContentAspect;\n"
    "uniform int uContentScaleMode;\n"
    "in vec2 vUv;\n"
    "out vec4 fragmentColor;\n"
    "const float PI = 3.14159265358979323846264;\n"
    "const float CAMERA_FOV_DEGREES = 95.0;\n"
    "vec2 rotateUv(vec2 uv, int rotation) {\n"
    "    if (rotation == 1) return vec2(1.0 - uv.y, uv.x);\n"
    "    if (rotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);\n"
    "    if (rotation == 3) return vec2(uv.y, 1.0 - uv.x);\n"
    "    return uv;\n"
    "}\n"
    "vec4 sampleLocal(vec2 localUv, bool rightEye) {\n"
    "    if (localUv.x < 0.0 || localUv.x > 1.0 || localUv.y < 0.0 || localUv.y > 1.0) {\n"
    "        return vec4(0.0, 0.0, 0.0, 1.0);\n"
    "    }\n"
    "    vec4 window = rightEye ? uRightWindow : uLeftWindow;\n"
    "    int rotation = rightEye ? uRightRotation : uLeftRotation;\n"
    "    vec2 sourceUv = mix(window.xy, window.zw, rotateUv(localUv, rotation));\n"
    // Public projection UVs use a top-left origin. The OpenGL texture uses a bottom-left origin.
    "    return texture(uTexture, vec2(sourceUv.x, 1.0 - sourceUv.y));\n"
    "}\n"
    "vec3 rayForScreenUv(vec2 screenUv) {\n"
    "    vec2 p = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);\n"
    "    float tanHalfFov = tan((CAMERA_FOV_DEGREES * PI / 180.0) * 0.5 / max(uViewZoom, 0.01));\n"
    "    vec3 direction = normalize(vec3(p.x * uViewportAspect * tanHalfFov, p.y * tanHalfFov, -1.0));\n"
    "    float yaw = uViewYawDegrees * PI / 180.0;\n"
    "    float pitch = uViewPitchDegrees * PI / 180.0;\n"
    "    float roll = uViewRollDegrees * PI / 180.0;\n"
    "    float cy = cos(yaw);\n"
    "    float sy = sin(yaw);\n"
    "    direction = vec3(cy * direction.x + sy * direction.z, direction.y, -sy * direction.x + cy * direction.z);\n"
    "    float cp = cos(pitch);\n"
    "    float sp = sin(pitch);\n"
    "    direction = vec3(direction.x, cp * direction.y - sp * direction.z, sp * direction.y + cp * direction.z);\n"
    "    float cr = cos(roll);\n"
    "    float sr = sin(roll);\n"
    "    return normalize(vec3(cr * direction.x - sr * direction.y, sr * direction.x + cr * direction.y, direction.z));\n"
    "}\n"
    "vec2 eacFaceUv(float sc, float tc, float cellX, float cellY) {\n"
    "    vec2 local = vec2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));\n"
    "    return vec2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);\n"
    "}\n"
    "vec2 eacUv(vec3 direction) {\n"
    "    vec3 ad = abs(direction);\n"
    "    if (ad.z >= ad.x && ad.z >= ad.y) {\n"
    "        if (direction.z < 0.0) return eacFaceUv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);\n"
    "        return eacFaceUv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);\n"
    "    }\n"
    "    if (ad.x >= ad.y) {\n"
    "        if (direction.x > 0.0) return eacFaceUv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);\n"
    "        return eacFaceUv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);\n"
    "    }\n"
    "    if (direction.y > 0.0) return eacFaceUv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);\n"
    "    return eacFaceUv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);\n"
    "}\n"
    "vec2 contentUv(vec2 outputUv) {\n"
    "    float destinationAspect = max(uDestinationAspect, 0.001);\n"
    "    float contentAspect = max(uContentAspect, 0.001);\n"
    "    if (uContentScaleMode == 2) return outputUv;\n"
    "    if (uContentScaleMode == 1) {\n"
    "        if (destinationAspect > contentAspect) {\n"
    "            outputUv.y = (outputUv.y - 0.5) * (contentAspect / destinationAspect) + 0.5;\n"
    "        } else {\n"
    "            outputUv.x = (outputUv.x - 0.5) * (destinationAspect / contentAspect) + 0.5;\n"
    "        }\n"
    "        return outputUv;\n"
    "    }\n"
    "    if (destinationAspect > contentAspect) {\n"
    "        float occupiedWidth = contentAspect / destinationAspect;\n"
    "        outputUv.x = (outputUv.x - 0.5) / occupiedWidth + 0.5;\n"
    "    } else {\n"
    "        float occupiedHeight = destinationAspect / contentAspect;\n"
    "        outputUv.y = (outputUv.y - 0.5) / occupiedHeight + 0.5;\n"
    "    }\n"
    "    return outputUv;\n"
    "}\n"
    "void main() {\n"
    // vUv is bottom-up because it is generated in OpenGL clip space; projection screen UV is top-down.
    "    vec2 screenUv = contentUv(vec2(vUv.x, 1.0 - vUv.y));\n"
    "    if (screenUv.x < 0.0 || screenUv.x > 1.0 || screenUv.y < 0.0 || screenUv.y > 1.0) {\n"
    "        fragmentColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
    "        return;\n"
    "    }\n"
    "    bool rightEye = false;\n"
    "    if (uStereo != 0) {\n"
    "        if (screenUv.x < 0.5) screenUv.x *= 2.0;\n"
    "        else { screenUv.x = (screenUv.x - 0.5) * 2.0; rightEye = true; }\n"
    "    }\n"
    "    if (uProjectionType == 0) { fragmentColor = sampleLocal(screenUv, rightEye); return; }\n"
    "    vec3 direction = rayForScreenUv(screenUv);\n"
    "    if (uProjectionType == 1 || uProjectionType == 2) {\n"
    "        float horizontalFov = max(uFovDegrees, 1.0) * PI / 180.0;\n"
    "        float yaw = atan(direction.x, -direction.z);\n"
    "        float pitch = asin(clamp(direction.y, -1.0, 1.0));\n"
    "        if (abs(yaw) > horizontalFov * 0.5) fragmentColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
    "        else fragmentColor = sampleLocal(vec2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI), rightEye);\n"
    "        return;\n"
    "    }\n"
    "    if (uProjectionType >= 3 && uProjectionType <= 6) {\n"
    "        float maxTheta = max(uFovDegrees, 1.0) * PI / 180.0 * 0.5;\n"
    "        float theta = acos(clamp(-direction.z, -1.0, 1.0));\n"
    "        if (theta > maxTheta) fragmentColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
    "        else {\n"
    "            float phi = atan(direction.y, direction.x);\n"
    "            float radius = theta / maxTheta * 0.5;\n"
    "            fragmentColor = sampleLocal(vec2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius), rightEye);\n"
    "        }\n"
    "        return;\n"
    "    }\n"
    "    fragmentColor = sampleLocal(eacUv(direction), rightEye);\n"
    "}\n";

static GLuint compile_projection_shader(GLenum type, const GLchar* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static BOOL ensure_projection_program(KMPMpvNativeRenderer* renderer) {
    if (!renderer) return NO;
    if (renderer->projection_program && renderer->projection_vertex_array) return YES;

    GLuint vertex_shader =
        compile_projection_shader(GL_VERTEX_SHADER, KMP_MPV_PROJECTION_VERTEX_SHADER);
    GLuint fragment_shader =
        compile_projection_shader(GL_FRAGMENT_SHADER, KMP_MPV_PROJECTION_FRAGMENT_SHADER);
    if (!vertex_shader || !fragment_shader) {
        if (vertex_shader) glDeleteShader(vertex_shader);
        if (fragment_shader) glDeleteShader(fragment_shader);
        return NO;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertex_shader);
    glAttachShader(program, fragment_shader);
    glLinkProgram(program);
    glDeleteShader(vertex_shader);
    glDeleteShader(fragment_shader);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        glDeleteProgram(program);
        return NO;
    }

    GLuint vertex_array = 0;
    glGenVertexArrays(1, &vertex_array);
    if (!vertex_array) {
        glDeleteProgram(program);
        return NO;
    }
    renderer->projection_program = program;
    renderer->projection_vertex_array = vertex_array;
    return YES;
}

static void destroy_projection_target(KMPMpvNativeRenderer* renderer) {
    if (!renderer) return;
    if (renderer->projection_framebuffer) {
        glDeleteFramebuffers(1, &renderer->projection_framebuffer);
        renderer->projection_framebuffer = 0;
    }
    if (renderer->projection_texture) {
        glDeleteTextures(1, &renderer->projection_texture);
        renderer->projection_texture = 0;
    }
    renderer->projection_width = 0;
    renderer->projection_height = 0;
    renderer->projection_depth = 0;
}

static void destroy_projection_resources(KMPMpvNativeRenderer* renderer) {
    if (!renderer) return;
    destroy_projection_target(renderer);
    if (renderer->projection_vertex_array) {
        glDeleteVertexArrays(1, &renderer->projection_vertex_array);
        renderer->projection_vertex_array = 0;
    }
    if (renderer->projection_program) {
        glDeleteProgram(renderer->projection_program);
        renderer->projection_program = 0;
    }
}

static BOOL ensure_projection_target(
    KMPMpvNativeRenderer* renderer,
    int width,
    int height,
    int depth
) {
    if (!renderer || width <= 0 || height <= 0) return NO;
    if (!ensure_projection_program(renderer)) return NO;
    if (renderer->projection_framebuffer && renderer->projection_texture &&
        renderer->projection_width == width && renderer->projection_height == height &&
        renderer->projection_depth == depth) {
        return YES;
    }

    destroy_projection_target(renderer);
    GLenum internal_format = depth > 8 ? GL_RGBA16F : GL_RGBA8;
    GLenum component_type = depth > 8 ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE;
    glGenTextures(1, &renderer->projection_texture);
    glBindTexture(GL_TEXTURE_2D, renderer->projection_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        (GLint)internal_format,
        width,
        height,
        0,
        GL_RGBA,
        component_type,
        NULL
    );

    glGenFramebuffers(1, &renderer->projection_framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, renderer->projection_framebuffer);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        renderer->projection_texture,
        0
    );
    BOOL complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!complete) {
        destroy_projection_target(renderer);
        return NO;
    }
    renderer->projection_width = width;
    renderer->projection_height = height;
    renderer->projection_depth = depth;
    return YES;
}

static void render_projected_frame(
    KMPMpvNativeRenderer* renderer,
    GLuint destination_framebuffer,
    int width,
    int height
) {
    if (!renderer || !renderer->projection_program || !renderer->projection_texture) return;
    const float* p = renderer->projection_parameters;
    BOOL stereo = p[KMP_MPV_PROJECTION_STEREO] > 0.5f;

    glBindFramebuffer(GL_FRAMEBUFFER, destination_framebuffer);
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(renderer->projection_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, renderer->projection_texture);
    glUniform1i(glGetUniformLocation(renderer->projection_program, "uTexture"), 0);
    glUniform1i(
        glGetUniformLocation(renderer->projection_program, "uProjectionType"),
        (GLint)llroundf(p[KMP_MPV_PROJECTION_TYPE])
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uFovDegrees"),
        p[KMP_MPV_PROJECTION_FOV]
    );
    glUniform1i(
        glGetUniformLocation(renderer->projection_program, "uStereo"),
        stereo ? 1 : 0
    );
    glUniform4fv(
        glGetUniformLocation(renderer->projection_program, "uLeftWindow"),
        1,
        &p[KMP_MPV_PROJECTION_LEFT_WINDOW]
    );
    glUniform1i(
        glGetUniformLocation(renderer->projection_program, "uLeftRotation"),
        (GLint)llroundf(p[KMP_MPV_PROJECTION_LEFT_ROTATION])
    );
    glUniform4fv(
        glGetUniformLocation(renderer->projection_program, "uRightWindow"),
        1,
        &p[KMP_MPV_PROJECTION_RIGHT_WINDOW]
    );
    glUniform1i(
        glGetUniformLocation(renderer->projection_program, "uRightRotation"),
        (GLint)llroundf(p[KMP_MPV_PROJECTION_RIGHT_ROTATION])
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uViewYawDegrees"),
        p[KMP_MPV_PROJECTION_YAW]
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uViewPitchDegrees"),
        p[KMP_MPV_PROJECTION_PITCH]
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uViewRollDegrees"),
        p[KMP_MPV_PROJECTION_ROLL]
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uViewZoom"),
        p[KMP_MPV_PROJECTION_ZOOM]
    );
    float destination_aspect = (float)width / fmaxf((float)height, 1.0f);
    float content_aspect = fmaxf(renderer->media_aspect, 0.001f);
    float viewport_aspect = stereo ? content_aspect * 0.5f : content_aspect;
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uViewportAspect"),
        viewport_aspect
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uDestinationAspect"),
        destination_aspect
    );
    glUniform1f(
        glGetUniformLocation(renderer->projection_program, "uContentAspect"),
        content_aspect
    );
    glUniform1i(
        glGetUniformLocation(renderer->projection_program, "uContentScaleMode"),
        renderer->content_scale_mode
    );
    glBindVertexArray(renderer->projection_vertex_array);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

static BOOL load_render_api(KMPMpvNativeRenderer* renderer, const char* library_name) {
    if (!renderer || !library_name || !library_name[0]) return NO;
    renderer->libmpv_library = dlopen(library_name, RTLD_LAZY | RTLD_LOCAL);
    if (!renderer->libmpv_library) return NO;

#define KMP_LOAD_MPV_SYMBOL(field, symbol) \
    renderer->api.field = (symbol##_fn)dlsym(renderer->libmpv_library, #symbol); \
    if (!renderer->api.field) return NO

    KMP_LOAD_MPV_SYMBOL(create, mpv_render_context_create);
    KMP_LOAD_MPV_SYMBOL(set_update_callback, mpv_render_context_set_update_callback);
    KMP_LOAD_MPV_SYMBOL(update, mpv_render_context_update);
    KMP_LOAD_MPV_SYMBOL(get_info, mpv_render_context_get_info);
    KMP_LOAD_MPV_SYMBOL(render, mpv_render_context_render);
    KMP_LOAD_MPV_SYMBOL(report_swap, mpv_render_context_report_swap);
    KMP_LOAD_MPV_SYMBOL(free, mpv_render_context_free);
#undef KMP_LOAD_MPV_SYMBOL
    return YES;
}

static BOOL choose_pixel_format(
    int color_mode,
    CGLPixelFormatObj* output,
    GLint* output_depth
) {
    if (!output || !output_depth) return NO;

    GLint count = 0;
    CGLError error = kCGLNoError;
    if (color_mode != 0) {
        const CGLPixelFormatAttribute hdr_attributes[] = {
            kCGLPFAOpenGLProfile,
            (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
            kCGLPFAAccelerated,
            kCGLPFADoubleBuffer,
            kCGLPFAColorSize,
            (CGLPixelFormatAttribute)64,
            kCGLPFAColorFloat,
            kCGLPFAAllowOfflineRenderers,
            (CGLPixelFormatAttribute)0,
        };
        error = CGLChoosePixelFormat(hdr_attributes, output, &count);
        if (error == kCGLNoError && *output) {
            *output_depth = 16;
            return YES;
        }
    }

    const CGLPixelFormatAttribute sdr_attributes[] = {
        kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated,
        kCGLPFADoubleBuffer,
        kCGLPFAAllowOfflineRenderers,
        (CGLPixelFormatAttribute)0,
    };
    error = CGLChoosePixelFormat(sdr_attributes, output, &count);
    if (error == kCGLNoError && *output) {
        *output_depth = 8;
        return YES;
    }

    const CGLPixelFormatAttribute legacy_attributes[] = {
        kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_Legacy,
        kCGLPFAAccelerated,
        kCGLPFADoubleBuffer,
        (CGLPixelFormatAttribute)0,
    };
    error = CGLChoosePixelFormat(legacy_attributes, output, &count);
    if (error == kCGLNoError && *output) {
        *output_depth = 8;
        return YES;
    }
    return NO;
}

@interface KMPMpvOpenGLLayer : CAOpenGLLayer {
    KMPMpvNativeRenderer* _renderer;
}
- (instancetype)initWithRenderer:(KMPMpvNativeRenderer*)renderer;
- (void)setRenderer:(KMPMpvNativeRenderer*)renderer;
- (void)requestFrame;
@end

@interface KMPMpvVideoView : NSView {
    KMPMpvOpenGLLayer* _videoLayer;
    BOOL _liveResizeActive;
    NSSize _liveResizeRenderSize;
}
@property(nonatomic, retain) KMPMpvOpenGLLayer* videoLayer;
- (void)beginLiveResizeIfNeeded;
- (void)endLiveResizeIfNeeded;
- (void)refreshNativeFrameAndGeometry;
- (void)refreshNativeGeometry;
@end

/** Compose-owned host used by the capability-marked, windowless macvk VO. */
@interface KMPMpvMacVkHostView : KMPMpvVideoView {
    NSInteger _embeddedColorMode;
}
- (void)setEmbeddedMetalColorMode:(NSInteger)colorMode;
- (void)refreshEmbeddedMetalGeometry;
@end

static CGColorSpaceRef create_output_color_space_for_view(
    NSView* view,
    int color_mode
) {
    switch (color_mode) {
        case 1:
            return CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_PQ);
        case 2:
            return CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_HLG);
        default: {
            NSScreen* screen = [[view window] screen];
            CGColorSpaceRef screen_space = [[screen colorSpace] CGColorSpace];
            if (screen_space) return CGColorSpaceRetain(screen_space);
            return CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        }
    }
}

static void apply_output_color_space(KMPMpvNativeRenderer* renderer) {
    if (!renderer || !renderer->layer) return;
    BOOL extended_range = renderer->color_mode != 0;
    CGColorSpaceRef color_space = create_output_color_space_for_view(
        renderer->view,
        renderer->color_mode
    );
    if (color_space) {
        [renderer->layer setColorspace:color_space];
        CGColorSpaceRelease(color_space);
    }
    [renderer->layer setWantsExtendedDynamicRangeContent:extended_range];
    if (renderer->view) {
        [renderer->view setWantsExtendedDynamicRangeOpenGLSurface:extended_range];
    }
}

static void schedule_layer_display(KMPMpvNativeRenderer* renderer) {
    if (!renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) ||
        atomic_exchange_explicit(
            &renderer->display_request_queued,
            true,
            memory_order_acq_rel
        )) {
        return;
    }

    renderer_retain(renderer);
    void (^display_layer)(void) = ^{
        if (!atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) &&
            renderer->layer) {
            [renderer->layer setNeedsDisplay];
            atomic_fetch_add_explicit(
                &renderer->display_wakeup_count,
                1,
                memory_order_relaxed
            );
            if (atomic_load_explicit(&renderer->live_resize_active, memory_order_acquire)) {
                atomic_fetch_add_explicit(
                    &renderer->live_resize_display_wakeup_count,
                    1,
                    memory_order_relaxed
                );
            }
        }
        atomic_store_explicit(
            &renderer->display_request_queued,
            false,
            memory_order_release
        );
        renderer_release(renderer);
    };

    if (pthread_main_np()) {
        display_layer();
        return;
    }

    // AppKit runs a nested NSEventTrackingRunLoopMode while the user drags a window edge.
    // Blocks sent to the ordinary GCD main queue can remain pending until that tracking loop
    // exits, leaving CAOpenGLLayer on its last drawable even though libmpv keeps decoding.
    // Cocoa includes event-tracking mode in its common modes, so this wake-up continues to run
    // during live resize while all CALayer mutations still stay on the AppKit main thread.
    CFRunLoopRef main_run_loop = CFRunLoopGetMain();
    CFRunLoopPerformBlock(main_run_loop, kCFRunLoopCommonModes, display_layer);
    CFRunLoopWakeUp(main_run_loop);
}

static void renderer_update_callback(void* context) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)context;
    if (!renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        return;
    }

    atomic_fetch_add_explicit(
        &renderer->update_callback_count,
        1,
        memory_order_relaxed
    );
    atomic_store_explicit(&renderer->update_pending, true, memory_order_release);

    // Wake the asynchronous layer as soon as libmpv publishes work. Coalesce AppKit requests so
    // the main queue cannot grow without bound during a 60 fps presentation.
    schedule_layer_display(renderer);
}

@implementation KMPMpvOpenGLLayer

- (instancetype)initWithRenderer:(KMPMpvNativeRenderer*)renderer {
    self = [super init];
    if (self) {
        _renderer = NULL;
        [self setRenderer:renderer];
        [self setBackgroundColor:[[NSColor blackColor] CGColor]];
        [self setOpaque:YES];
        // CAOpenGLLayer owns a render thread. Keeping rendering off AppKit's main thread makes
        // live resize and Compose input independent from libmpv's GPU work.
        [self setAsynchronous:YES];
        if (renderer && renderer->buffer_depth > 8) {
            [self setContentsFormat:kCAContentsFormatRGBA16Float];
        }
    }
    return self;
}

- (void)dealloc {
    [self setRenderer:NULL];
    [super dealloc];
}

- (void)setRenderer:(KMPMpvNativeRenderer*)renderer {
    @synchronized(self) {
        if (_renderer == renderer) return;
        KMPMpvNativeRenderer* previous = _renderer;
        _renderer = renderer_retain(renderer);
        renderer_release(previous);
    }
}

- (KMPMpvNativeRenderer*)retainedRenderer {
    @synchronized(self) {
        return renderer_retain(_renderer);
    }
}

- (CGLPixelFormatObj)copyCGLPixelFormatForDisplayMask:(uint32_t)mask {
    (void)mask;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    CGLPixelFormatObj format = renderer ? renderer->pixel_format : NULL;
    if (format) CGLRetainPixelFormat(format);
    renderer_release(renderer);
    return format;
}

- (CGLContextObj)copyCGLContextForPixelFormat:(CGLPixelFormatObj)pixelFormat {
    (void)pixelFormat;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    CGLContextObj context = renderer ? renderer->gl_context : NULL;
    if (context) CGLRetainContext(context);
    renderer_release(renderer);
    return context;
}

- (BOOL)canDrawInCGLContext:(CGLContextObj)context
                pixelFormat:(CGLPixelFormatObj)pixelFormat
               forLayerTime:(CFTimeInterval)timeInterval
                displayTime:(const CVTimeStamp*)timeStamp {
    (void)context;
    (void)pixelFormat;
    (void)timeInterval;
    (void)timeStamp;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    BOOL can_draw =
        renderer &&
        !atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) &&
        renderer->render_context &&
        (atomic_load_explicit(&renderer->update_pending, memory_order_acquire) ||
            atomic_load_explicit(&renderer->redraw_requested, memory_order_acquire));
    renderer_release(renderer);
    return can_draw;
}

- (void)drawInCGLContext:(CGLContextObj)context
             pixelFormat:(CGLPixelFormatObj)pixelFormat
            forLayerTime:(CFTimeInterval)timeInterval
             displayTime:(const CVTimeStamp*)timeStamp {
    (void)pixelFormat;
    (void)timeInterval;
    (void)timeStamp;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    if (!renderer) return;

    atomic_fetch_add_explicit(
        &renderer->draw_callback_count,
        1,
        memory_order_relaxed
    );

    pthread_mutex_lock(&renderer->render_mutex);
    if (!atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) &&
        renderer->render_context && context) {
        CGLSetCurrentContext(context);
        BOOL update_pending = atomic_exchange_explicit(
            &renderer->update_pending,
            false,
            memory_order_acq_rel
        );
        BOOL redraw_requested = atomic_exchange_explicit(
            &renderer->redraw_requested,
            false,
            memory_order_acq_rel
        );
        // With advanced control enabled every callback must be acknowledged with update().
        // Rendering a reused 8K frame on every Core Animation poll starves VideoToolbox and the
        // GPU; draw only when libmpv publishes a frame or the host explicitly requests a redraw.
        uint64_t update_flags = update_pending
            ? renderer->api.update(renderer->render_context)
            : 0;
        BOOL should_render =
            redraw_requested || (update_flags & MPV_RENDER_UPDATE_FRAME) != 0;

        if (should_render) {
            mpv_render_frame_info frame_info = {0};
            mpv_render_param frame_info_parameter = {
                MPV_RENDER_PARAM_NEXT_FRAME_INFO,
                &frame_info,
            };
            if (renderer->api.get_info(
                    renderer->render_context,
                    frame_info_parameter
                ) >= 0 &&
                (frame_info.flags & MPV_RENDER_FRAME_INFO_PRESENT) != 0) {
                if ((frame_info.flags & MPV_RENDER_FRAME_INFO_REDRAW) != 0) {
                    atomic_fetch_add_explicit(
                        &renderer->redraw_frame_count,
                        1,
                        memory_order_relaxed
                    );
                } else if ((frame_info.flags & MPV_RENDER_FRAME_INFO_REPEAT) != 0) {
                    atomic_fetch_add_explicit(
                        &renderer->repeated_video_frame_count,
                        1,
                        memory_order_relaxed
                    );
                } else {
                    atomic_fetch_add_explicit(
                        &renderer->new_video_frame_count,
                        1,
                        memory_order_relaxed
                    );
                }
            } else {
                atomic_fetch_add_explicit(
                    &renderer->empty_draw_count,
                    1,
                    memory_order_relaxed
                );
            }

            GLint viewport[4] = {0, 0, 0, 0};
            glGetIntegerv(GL_VIEWPORT, viewport);
            GLint framebuffer = 0;
            glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &framebuffer);
            if (framebuffer != 0) renderer->last_framebuffer = framebuffer;

            int width = viewport[2];
            int height = viewport[3];
            if (width <= 0 || height <= 0) {
                width = (int)llround(self.bounds.size.width * self.contentsScale);
                height = (int)llround(self.bounds.size.height * self.contentsScale);
            }
            if (width > 0 && height > 0) {
                GLuint destination_framebuffer = (GLuint)renderer->last_framebuffer;
                BOOL projection_active =
                    renderer->projection_parameters[KMP_MPV_PROJECTION_ENABLED] > 0.5f &&
                    ensure_projection_target(renderer, width, height, renderer->buffer_depth);
                GLenum projection_internal_format =
                    renderer->buffer_depth > 8 ? GL_RGBA16F : GL_RGBA8;
                mpv_opengl_fbo target = {
                    .fbo =
                        projection_active
                            ? (int)renderer->projection_framebuffer
                            : (int)destination_framebuffer,
                    .width = width,
                    .height = height,
                    .internal_format = projection_active ? (int)projection_internal_format : 0,
                };
                int flip_y = 1;
                int depth = renderer->buffer_depth;
                // CAOpenGLLayer already invokes this draw on its display-driven render thread,
                // while libmpv's update callback tells us when a frame is ready. Waiting again
                // inside mpv_render_context_render() holds this native render callback for
                // almost a complete refresh interval. That competes with the sibling Tao scene
                // which presents Compose controls and makes ordinary UI interactions visibly
                // late. Keep presentation timing in Core Animation and audio synchronization in
                // libmpv, but never sleep inside the layer callback.
                int block_for_target_time = 0;
                mpv_render_param parameters[] = {
                    {MPV_RENDER_PARAM_OPENGL_FBO, &target},
                    {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
                    {MPV_RENDER_PARAM_DEPTH, &depth},
                    {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block_for_target_time},
                    {MPV_RENDER_PARAM_INVALID, NULL},
                };
                uint64_t render_started_ns = monotonic_nanos();
                if (renderer->api.render(renderer->render_context, parameters) >= 0) {
                    if (projection_active) {
                        render_projected_frame(
                            renderer,
                            destination_framebuffer,
                            width,
                            height
                        );
                    }
                    uint64_t render_finished_ns = monotonic_nanos();
                    uint64_t render_elapsed_ns = render_finished_ns - render_started_ns;
                    atomic_fetch_add_explicit(
                        &renderer->render_time_ns,
                        render_elapsed_ns,
                        memory_order_relaxed
                    );
                    atomic_update_maximum(
                        &renderer->maximum_render_time_ns,
                        render_elapsed_ns
                    );
                    atomic_fetch_add_explicit(
                        &renderer->rendered_frame_count,
                        1,
                        memory_order_relaxed
                    );
                    uint64_t flush_started_ns = monotonic_nanos();
                    CGLFlushDrawable(context);
                    uint64_t flush_elapsed_ns = monotonic_nanos() - flush_started_ns;
                    atomic_fetch_add_explicit(
                        &renderer->flush_time_ns,
                        flush_elapsed_ns,
                        memory_order_relaxed
                    );
                    atomic_update_maximum(
                        &renderer->maximum_flush_time_ns,
                        flush_elapsed_ns
                    );
                    renderer->api.report_swap(renderer->render_context);
                    atomic_fetch_add_explicit(
                        &renderer->presented_frame_count,
                        1,
                        memory_order_relaxed
                    );
                }
            }
        }
    }
    pthread_mutex_unlock(&renderer->render_mutex);
    if (atomic_load_explicit(&renderer->update_pending, memory_order_acquire)) {
        schedule_layer_display(renderer);
    }
    renderer_release(renderer);
}

- (void)requestFrame {
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    if (renderer &&
        !atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        atomic_store_explicit(&renderer->redraw_requested, true, memory_order_release);
    }
    renderer_release(renderer);
    [self setNeedsDisplay];
}

@end

@implementation KMPMpvVideoView

@synthesize videoLayer = _videoLayer;

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [_videoLayer release];
    [super dealloc];
}

- (NSView*)hitTest:(NSPoint)point {
    (void)point;
    return nil;
}

- (BOOL)acceptsFirstResponder {
    return NO;
}

- (void)setVideoLayer:(KMPMpvOpenGLLayer*)videoLayer {
    if (_videoLayer == videoLayer) return;
    [_videoLayer removeFromSuperlayer];
    [_videoLayer release];
    _videoLayer = [videoLayer retain];
    [self setWantsLayer:YES];
    [[self layer] setMasksToBounds:YES];
    [[self layer] setBackgroundColor:[[NSColor blackColor] CGColor]];
    if (_videoLayer) [[self layer] addSublayer:_videoLayer];
    [self refreshNativeGeometry];
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    NSWindow* window = [self window];
    if (window) {
        NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidChangeScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidChangeBackingPropertiesNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidEnterFullScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidExitFullScreenNotification
                     object:window];
    }
    [self refreshNativeFrameAndGeometry];
}

- (void)viewDidChangeBackingProperties {
    [super viewDidChangeBackingProperties];
    [self refreshNativeGeometry];
}

- (void)setFrameSize:(NSSize)newSize {
    [super setFrameSize:newSize];
    [self refreshNativeGeometry];
}

- (void)viewWillStartLiveResize {
    [super viewWillStartLiveResize];
    [self beginLiveResizeIfNeeded];
    [self refreshNativeGeometry];
}

- (void)viewDidEndLiveResize {
    [super viewDidEndLiveResize];
    [self endLiveResizeIfNeeded];
}

- (void)windowColorOrGeometryChanged:(NSNotification*)notification {
    (void)notification;
    [self refreshNativeFrameAndGeometry];
}

- (void)refreshNativeFrameAndGeometry {
    // Nucleus owns the NSView frame. The backend only updates its rendering layer.
    [self refreshNativeGeometry];
}

- (void)beginLiveResizeIfNeeded {
    if (_liveResizeActive || !_videoLayer) return;
    _liveResizeActive = YES;
    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        atomic_store_explicit(&renderer->live_resize_active, true, memory_order_release);
        renderer_release(renderer);
    }
    _liveResizeRenderSize = [_videoLayer bounds].size;
    if (_liveResizeRenderSize.width <= 0.0 || _liveResizeRenderSize.height <= 0.0) {
        _liveResizeRenderSize = [self bounds].size;
    }
}

- (void)endLiveResizeIfNeeded {
    if (!_liveResizeActive) return;
    _liveResizeActive = NO;
    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        atomic_store_explicit(&renderer->live_resize_active, false, memory_order_release);
        renderer_release(renderer);
    }
    _liveResizeRenderSize = NSZeroSize;
    [self refreshNativeGeometry];
}

- (void)refreshNativeGeometry {
    if (!_videoLayer) return;
    if ([self inLiveResize]) [self beginLiveResizeIfNeeded];

    NSRect bounds = [self bounds];
    CGFloat scale = [[self window] backingScaleFactor];
    if (scale <= 0.0) scale = 1.0;

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [_videoLayer setAnchorPoint:CGPointMake(0.5, 0.5)];
    [_videoLayer setPosition:CGPointMake(NSMidX(bounds), NSMidY(bounds))];
    if (_liveResizeActive &&
        _liveResizeRenderSize.width > 0.0 &&
        _liveResizeRenderSize.height > 0.0) {
        // Keep libmpv's OpenGL drawable stable while AppKit is delivering intermediate resize
        // steps. Core Animation scales the most recent surface, so playback keeps presenting
        // without reallocating and rerendering a different FBO for every mouse movement.
        //
        // The transform must be uniform. Scaling X and Y independently makes the cached frame
        // temporarily inherit the window's intermediate aspect ratio; libmpv only corrects it
        // after live resize ends and the drawable is rebuilt. A centered aspect-fit transform
        // preserves the exact pixel aspect throughout the gesture, with the host's black layer
        // covering any temporary letterbox/pillarbox area.
        [_videoLayer setBounds:CGRectMake(
            0.0,
            0.0,
            _liveResizeRenderSize.width,
            _liveResizeRenderSize.height
        )];
        CGFloat scale_x = bounds.size.width / _liveResizeRenderSize.width;
        CGFloat scale_y = bounds.size.height / _liveResizeRenderSize.height;
        CGFloat uniform_scale = MIN(scale_x, scale_y);
        if (!isfinite(uniform_scale) || uniform_scale <= 0.0) uniform_scale = 1.0;
        [_videoLayer setTransform:CATransform3DMakeScale(
            uniform_scale,
            uniform_scale,
            1.0
        )];

        KMPMpvNativeRenderer* geometry_renderer = [_videoLayer retainedRenderer];
        if (geometry_renderer) {
            atomic_fetch_add_explicit(
                &geometry_renderer->live_resize_geometry_update_count,
                1,
                memory_order_relaxed
            );
            CATransform3D applied_transform = [_videoLayer transform];
            double applied_x = fabs(applied_transform.m11);
            double applied_y = fabs(applied_transform.m22);
            uint64_t aspect_error_ppm =
                applied_y > 0.0
                    ? (uint64_t)llround(fabs((applied_x / applied_y) - 1.0) * 1000000.0)
                    : UINT64_MAX;
            atomic_update_maximum(
                &geometry_renderer->maximum_live_resize_aspect_error_ppm,
                aspect_error_ppm
            );
            renderer_release(geometry_renderer);
        }
    } else {
        [_videoLayer setTransform:CATransform3DIdentity];
        [_videoLayer setBounds:CGRectMake(0.0, 0.0, bounds.size.width, bounds.size.height)];
        [_videoLayer setContentsScale:scale];
    }
    [CATransaction commit];

    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        apply_output_color_space(renderer);
        renderer_release(renderer);
    }
    if (!_liveResizeActive) [_videoLayer requestFrame];
}

@end

@implementation KMPMpvMacVkHostView

- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        _embeddedColorMode = 0;
        [self setWantsLayer:YES];
        [[self layer] setMasksToBounds:YES];
        [[self layer] setBackgroundColor:[[NSColor blackColor] CGColor]];
        [self setAutoresizingMask:NSViewNotSizable];
    }
    return self;
}

- (NSView*)hitTest:(NSPoint)point {
    (void)point;
    return nil;
}

- (BOOL)acceptsFirstResponder {
    return NO;
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    [self refreshEmbeddedMetalGeometry];
}

- (void)viewDidChangeBackingProperties {
    [super viewDidChangeBackingProperties];
    [self refreshEmbeddedMetalGeometry];
}

- (void)setFrameSize:(NSSize)newSize {
    [super setFrameSize:newSize];
    [self refreshEmbeddedMetalGeometry];
}

- (void)layout {
    [super layout];
    [self refreshEmbeddedMetalGeometry];
}

- (void)setEmbeddedMetalColorMode:(NSInteger)colorMode {
    _embeddedColorMode = colorMode;
    [self refreshEmbeddedMetalGeometry];
}

- (void)refreshEmbeddedMetalGeometry {
    NSRect bounds = [self bounds];
    CGFloat scale = [[self window] backingScaleFactor];
    if (scale <= 0.0) scale = 1.0;
    BOOL extended_range = _embeddedColorMode != 0;
    CGColorSpaceRef color_space = create_output_color_space_for_view(
        self,
        (int)_embeddedColorMode
    );

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    for (CALayer* child in [[self layer] sublayers]) {
        [child setFrame:bounds];
        [child setContentsScale:scale];
        if ([child isKindOfClass:[CAMetalLayer class]]) {
            CAMetalLayer* metal = (CAMetalLayer*)child;
            CGSize drawable_size = CGSizeMake(
                MAX(1.0, bounds.size.width * scale),
                MAX(1.0, bounds.size.height * scale)
            );
            [metal setDrawableSize:drawable_size];
            if (color_space) [metal setColorspace:color_space];
            [metal setWantsExtendedDynamicRangeContent:extended_range];
        }
    }
    [CATransaction commit];
    if (color_space) CGColorSpaceRelease(color_space);
}

@end


typedef struct {
    KMPMpvNativeRenderer* renderer;
    KMPMpvVideoView* host_view;
    BOOL created;
} KMPMpvCreateContext;

static void create_renderer_on_main(void* raw_context) {
    KMPMpvCreateContext* context = (KMPMpvCreateContext*)raw_context;
    @autoreleasepool {
        if (!context || !context->renderer) return;
        KMPMpvNativeRenderer* renderer = context->renderer;

        if (!choose_pixel_format(
                renderer->color_mode,
                &renderer->pixel_format,
                &renderer->buffer_depth
            )) {
            return;
        }
        if (CGLCreateContext(renderer->pixel_format, NULL, &renderer->gl_context) != kCGLNoError ||
            !renderer->gl_context) {
            return;
        }
        // Audio-timed libmpv rendering already blocks each frame until its presentation target.
        // Core Animation owns the actual display transaction, so a second OpenGL swap wait only
        // serializes the render thread during live resize without improving presentation timing.
        GLint swap_interval = 0;
        CGLSetParameter(renderer->gl_context, kCGLCPSwapInterval, &swap_interval);
        CGLSetCurrentContext(renderer->gl_context);

        const char* api_type = "opengl";
        int advanced_control = 1;
        mpv_opengl_init_params open_gl = {
            .get_proc_address = resolve_opengl_function,
            .get_proc_address_context = NULL,
        };
        mpv_render_param create_parameters[] = {
            {MPV_RENDER_PARAM_API_TYPE, (void*)api_type},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &open_gl},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced_control},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        if (renderer->api.create(
                &renderer->render_context,
                renderer->mpv,
                create_parameters
            ) < 0 || !renderer->render_context) {
            return;
        }

        KMPMpvOpenGLLayer* layer = [[KMPMpvOpenGLLayer alloc] initWithRenderer:renderer];
        KMPMpvVideoView* view = context->host_view
            ? [context->host_view retain]
            : [[KMPMpvVideoView alloc] initWithFrame:NSMakeRect(0.0, 0.0, 1.0, 1.0)];
        renderer->layer = [layer retain];
        renderer->view = [view retain];
        // Nucleus is the only frame writer. AppKit autoresizing would race its deferred
        // interop transaction during live resize and briefly apply a different rectangle.
        [view setAutoresizingMask:NSViewNotSizable];
        [view setVideoLayer:layer];
        apply_output_color_space(renderer);
        renderer->api.set_update_callback(
            renderer->render_context,
            renderer_update_callback,
            renderer
        );
        [view refreshNativeGeometry];
        context->created = YES;
        [layer release];
        [view release];
    }
}

static void detach_renderer_on_main(void* raw_renderer) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)raw_renderer;
    if (!renderer) return;
    @autoreleasepool {
        atomic_store_explicit(&renderer->shutting_down, true, memory_order_release);
        pthread_mutex_lock(&renderer->render_mutex);
        if (renderer->render_context) {
            CGLSetCurrentContext(renderer->gl_context);
            renderer->api.set_update_callback(renderer->render_context, NULL, NULL);
            renderer->api.free(renderer->render_context);
            renderer->render_context = NULL;
        }
        if (renderer->gl_context) {
            CGLSetCurrentContext(renderer->gl_context);
            destroy_projection_resources(renderer);
        }
        pthread_mutex_unlock(&renderer->render_mutex);

        KMPMpvOpenGLLayer* layer = renderer->layer;
        KMPMpvVideoView* view = renderer->view;
        renderer->layer = nil;
        renderer->view = nil;
        [layer setRenderer:NULL];
        [view setVideoLayer:nil];
        // Nucleus owns the native hierarchy and removes this child in its queued detach
        // transaction. Keeping the superview's retain until then also keeps older queued
        // setFrame transactions from dereferencing a freed NSView.

        if (renderer->gl_context) {
            CGLSetCurrentContext(NULL);
            CGLReleaseContext(renderer->gl_context);
            renderer->gl_context = NULL;
        }
        if (renderer->pixel_format) {
            CGLReleasePixelFormat(renderer->pixel_format);
            renderer->pixel_format = NULL;
        }
        if (renderer->libmpv_library) {
            dlclose(renderer->libmpv_library);
            renderer->libmpv_library = NULL;
        }
        [layer release];
        [view release];
    }
}

typedef struct {
    KMPMpvNativeRenderer* renderer;
    int color_mode;
} KMPMpvColorContext;

typedef struct {
    KMPMpvNativeRenderer* renderer;
    double refresh_rate;
} KMPMpvRefreshRateContext;

typedef struct {
    KMPMpvMacVkHostView* view;
} KMPMpvMacVkHostCreateContext;

static void create_macvk_host_on_main(void* raw_context) {
    KMPMpvMacVkHostCreateContext* context = (KMPMpvMacVkHostCreateContext*)raw_context;
    if (!context) return;
    @autoreleasepool {
        context->view = [[KMPMpvMacVkHostView alloc]
            initWithFrame:NSMakeRect(0.0, 0.0, 1.0, 1.0)];
    }
}

static void destroy_macvk_host_on_main(void* raw_view) {
    KMPMpvMacVkHostView* view = (KMPMpvMacVkHostView*)raw_view;
    if (!view) return;
    @autoreleasepool {
        [view removeFromSuperview];
        [view release];
    }
}

typedef struct {
    KMPMpvMacVkHostView* view;
    double refresh_rate;
} KMPMpvMacVkRefreshRateContext;

typedef struct {
    KMPMpvMacVkHostView* view;
    NSInteger color_mode;
} KMPMpvMacVkColorContext;

static void update_macvk_color_mode_on_main(void* raw_context) {
    KMPMpvMacVkColorContext* context = (KMPMpvMacVkColorContext*)raw_context;
    if (!context || !context->view) return;
    [context->view setEmbeddedMetalColorMode:context->color_mode];
}

static void read_macvk_refresh_rate_on_main(void* raw_context) {
    KMPMpvMacVkRefreshRateContext* context =
        (KMPMpvMacVkRefreshRateContext*)raw_context;
    if (!context || !context->view) return;
    NSScreen* screen = [[context->view window] screen] ?: [NSScreen mainScreen];
    if (!screen) return;
    if (@available(macOS 12.0, *)) {
        NSInteger frames_per_second = [screen maximumFramesPerSecond];
        if (frames_per_second > 0) context->refresh_rate = (double)frames_per_second;
    }
    if (context->refresh_rate > 0.0) return;

    NSNumber* screen_number = [[screen deviceDescription] objectForKey:@"NSScreenNumber"];
    if (!screen_number) return;
    CGDisplayModeRef mode = CGDisplayCopyDisplayMode([screen_number unsignedIntValue]);
    if (!mode) return;
    double refresh_rate = CGDisplayModeGetRefreshRate(mode);
    CGDisplayModeRelease(mode);
    if (refresh_rate > 0.0) context->refresh_rate = refresh_rate;
}

static void update_color_mode_on_main(void* raw_context) {
    KMPMpvColorContext* context = (KMPMpvColorContext*)raw_context;
    if (!context || !context->renderer ||
        atomic_load_explicit(&context->renderer->shutting_down, memory_order_acquire)) {
        return;
    }
    context->renderer->color_mode = context->color_mode;
    apply_output_color_space(context->renderer);
    [context->renderer->layer requestFrame];
}

static void request_redraw_on_main(void* raw_renderer) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)raw_renderer;
    if (renderer &&
        !atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        [renderer->layer requestFrame];
    }
}

static void read_refresh_rate_on_main(void* raw_context) {
    KMPMpvRefreshRateContext* context = (KMPMpvRefreshRateContext*)raw_context;
    if (!context || !context->renderer ||
        atomic_load_explicit(&context->renderer->shutting_down, memory_order_acquire)) {
        return;
    }
    NSScreen* screen = [[context->renderer->view window] screen] ?: [NSScreen mainScreen];
    if (!screen) return;
    if (@available(macOS 12.0, *)) {
        NSInteger frames_per_second = [screen maximumFramesPerSecond];
        if (frames_per_second > 0) context->refresh_rate = (double)frames_per_second;
    }
    if (context->refresh_rate > 0.0) return;

    NSNumber* screen_number = [[screen deviceDescription] objectForKey:@"NSScreenNumber"];
    if (!screen_number) return;
    CGDisplayModeRef mode = CGDisplayCopyDisplayMode([screen_number unsignedIntValue]);
    if (!mode) return;
    double refresh_rate = CGDisplayModeGetRefreshRate(mode);
    CGDisplayModeRelease(mode);
    if (refresh_rate > 0.0) context->refresh_rate = refresh_rate;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nCreateMacVkHost(
    JNIEnv* environment,
    jclass bridge_class
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvMacVkHostCreateContext context = { .view = nil };
    run_on_appkit_main_sync(create_macvk_host_on_main, &context);
    return (jlong)(uintptr_t)context.view;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nDestroyMacVkHost(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_view
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvMacVkHostView* view = (KMPMpvMacVkHostView*)(uintptr_t)native_view;
    if (!view) return;
    run_on_appkit_main_sync(destroy_macvk_host_on_main, view);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nRequestMacVkRedraw(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_view
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvMacVkHostView* view = (KMPMpvMacVkHostView*)(uintptr_t)native_view;
    if (!view) return;
    void (^redraw)(void) = ^{
        [view refreshEmbeddedMetalGeometry];
        [view setNeedsDisplay:YES];
    };
    if (pthread_main_np()) {
        redraw();
    } else {
        dispatch_async(dispatch_get_main_queue(), redraw);
    }
}

JNIEXPORT jdouble JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetMacVkDisplayRefreshRate(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_view
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvMacVkHostView* view = (KMPMpvMacVkHostView*)(uintptr_t)native_view;
    if (!view) return 0.0;
    KMPMpvMacVkRefreshRateContext context = {
        .view = view,
        .refresh_rate = 0.0,
    };
    run_on_appkit_main_sync(read_macvk_refresh_rate_on_main, &context);
    return context.refresh_rate;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetMacVkColorMode(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_view,
    jint color_mode
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvMacVkHostView* view = (KMPMpvMacVkHostView*)(uintptr_t)native_view;
    if (!view) return;
    KMPMpvMacVkColorContext context = {
        .view = view,
        .color_mode = (NSInteger)color_mode,
    };
    run_on_appkit_main_sync(update_macvk_color_mode_on_main, &context);
}

static jlong create_renderer(
    JNIEnv* environment,
    jlong raw_mpv_handle,
    jstring library_load_name,
    jint color_mode,
    KMPMpvVideoView* host_view
) {
    if (!environment || !library_load_name || raw_mpv_handle == 0) return 0;

    const char* library_name = (*environment)->GetStringUTFChars(
        environment,
        library_load_name,
        NULL
    );
    if (!library_name) return 0;
    KMPMpvNativeRenderer* renderer = calloc(1, sizeof(KMPMpvNativeRenderer));
    if (!renderer) {
        (*environment)->ReleaseStringUTFChars(environment, library_load_name, library_name);
        return 0;
    }
    atomic_init(&renderer->references, 1);
    atomic_init(&renderer->shutting_down, false);
    atomic_init(&renderer->update_pending, false);
    atomic_init(&renderer->redraw_requested, true);
    atomic_init(&renderer->display_request_queued, false);
    atomic_init(&renderer->live_resize_active, false);
    atomic_init(&renderer->update_callback_count, 0);
    atomic_init(&renderer->draw_callback_count, 0);
    atomic_init(&renderer->rendered_frame_count, 0);
    atomic_init(&renderer->presented_frame_count, 0);
    atomic_init(&renderer->new_video_frame_count, 0);
    atomic_init(&renderer->repeated_video_frame_count, 0);
    atomic_init(&renderer->redraw_frame_count, 0);
    atomic_init(&renderer->empty_draw_count, 0);
    atomic_init(&renderer->display_wakeup_count, 0);
    atomic_init(&renderer->live_resize_display_wakeup_count, 0);
    atomic_init(&renderer->live_resize_geometry_update_count, 0);
    atomic_init(&renderer->maximum_live_resize_aspect_error_ppm, 0);
    atomic_init(&renderer->render_time_ns, 0);
    atomic_init(&renderer->maximum_render_time_ns, 0);
    atomic_init(&renderer->flush_time_ns, 0);
    atomic_init(&renderer->maximum_flush_time_ns, 0);
    pthread_mutex_init(&renderer->render_mutex, NULL);
    renderer->color_mode = color_mode;
    renderer->content_scale_mode = KMP_MPV_CONTENT_SCALE_FIT;
    renderer->media_aspect = 16.0f / 9.0f;
    renderer->mpv = (mpv_handle*)(uintptr_t)raw_mpv_handle;

    BOOL api_loaded = load_render_api(renderer, library_name);
    (*environment)->ReleaseStringUTFChars(environment, library_load_name, library_name);
    if (!api_loaded) {
        if (renderer->libmpv_library) dlclose(renderer->libmpv_library);
        renderer_release(renderer);
        return 0;
    }

    KMPMpvCreateContext context = {
        .renderer = renderer,
        .host_view = host_view,
        .created = NO,
    };
    run_on_appkit_main_sync(create_renderer_on_main, &context);
    if (!context.created) {
        run_on_appkit_main_sync(detach_renderer_on_main, renderer);
        renderer_release(renderer);
        return 0;
    }
    return (jlong)(uintptr_t)renderer;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nCreateRenderer(
    JNIEnv* environment,
    jclass bridge_class,
    jlong raw_mpv_handle,
    jstring library_load_name,
    jint color_mode
) {
    (void)bridge_class;
    return create_renderer(
        environment,
        raw_mpv_handle,
        library_load_name,
        color_mode,
        nil
    );
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nCreateRendererInHost(
    JNIEnv* environment,
    jclass bridge_class,
    jlong raw_mpv_handle,
    jstring library_load_name,
    jint color_mode,
    jlong native_view
) {
    (void)bridge_class;
    KMPMpvMacVkHostView* host_view =
        (KMPMpvMacVkHostView*)(uintptr_t)native_view;
    if (!host_view) return 0;
    return create_renderer(
        environment,
        raw_mpv_handle,
        library_load_name,
        color_mode,
        host_view
    );
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetViewHandle(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) ||
        !renderer->view) {
        return 0;
    }
    return (jlong)(uintptr_t)renderer->view;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nDetach(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return;
    run_on_appkit_main_sync(detach_renderer_on_main, renderer);
    renderer_release(renderer);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetColorMode(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer,
    jint color_mode
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return;
    KMPMpvColorContext context = {.renderer = renderer, .color_mode = color_mode};
    run_on_appkit_main_sync(update_color_mode_on_main, &context);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetProjection(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer,
    jfloatArray parameters
) {
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!environment || !renderer || !parameters ||
        (*environment)->GetArrayLength(environment, parameters) <
            KMP_MPV_PROJECTION_PARAMETER_COUNT ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        return;
    }

    jfloat values[KMP_MPV_PROJECTION_PARAMETER_COUNT] = {0};
    (*environment)->GetFloatArrayRegion(
        environment,
        parameters,
        0,
        KMP_MPV_PROJECTION_PARAMETER_COUNT,
        values
    );
    if ((*environment)->ExceptionCheck(environment)) return;

    pthread_mutex_lock(&renderer->render_mutex);
    if (!atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        for (int index = 0; index < KMP_MPV_PROJECTION_PARAMETER_COUNT; ++index) {
            renderer->projection_parameters[index] =
                isfinite(values[index]) ? values[index] : 0.0f;
        }
        if (renderer->projection_parameters[KMP_MPV_PROJECTION_ZOOM] <= 0.0f) {
            renderer->projection_parameters[KMP_MPV_PROJECTION_ZOOM] = 1.0f;
        }
    }
    pthread_mutex_unlock(&renderer->render_mutex);
    run_on_appkit_main_sync(request_redraw_on_main, renderer);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetContentScale(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer,
    jint content_scale_mode,
    jfloat media_aspect
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        return;
    }

    int normalized_mode = content_scale_mode;
    if (normalized_mode < KMP_MPV_CONTENT_SCALE_FIT ||
        normalized_mode > KMP_MPV_CONTENT_SCALE_FILL) {
        normalized_mode = KMP_MPV_CONTENT_SCALE_FIT;
    }
    float normalized_aspect =
        isfinite(media_aspect) && media_aspect > 0.0f ? media_aspect : (16.0f / 9.0f);

    pthread_mutex_lock(&renderer->render_mutex);
    if (!atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        renderer->content_scale_mode = normalized_mode;
        renderer->media_aspect = normalized_aspect;
    }
    pthread_mutex_unlock(&renderer->render_mutex);
    run_on_appkit_main_sync(request_redraw_on_main, renderer);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nRequestRedraw(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return;
    run_on_appkit_main_sync(request_redraw_on_main, renderer);
}

JNIEXPORT jdouble JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetDisplayRefreshRate(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return 0.0;
    KMPMpvRefreshRateContext context = {
        .renderer = renderer,
        .refresh_rate = 0.0,
    };
    run_on_appkit_main_sync(read_refresh_rate_on_main, &context);
    return context.refresh_rate;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetPresentationDiagnostics(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!environment || !renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        return NULL;
    }

    jlong values[] = {
        (jlong)atomic_load_explicit(&renderer->update_callback_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->draw_callback_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->rendered_frame_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->presented_frame_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->new_video_frame_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->repeated_video_frame_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->redraw_frame_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->empty_draw_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->display_wakeup_count, memory_order_relaxed),
        (jlong)atomic_load_explicit(
            &renderer->live_resize_display_wakeup_count,
            memory_order_relaxed
        ),
        (jlong)atomic_load_explicit(&renderer->render_time_ns, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->maximum_render_time_ns, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->flush_time_ns, memory_order_relaxed),
        (jlong)atomic_load_explicit(&renderer->maximum_flush_time_ns, memory_order_relaxed),
        (jlong)atomic_load_explicit(
            &renderer->live_resize_geometry_update_count,
            memory_order_relaxed
        ),
        (jlong)atomic_load_explicit(
            &renderer->maximum_live_resize_aspect_error_ppm,
            memory_order_relaxed
        ),
    };
    jlongArray result = (*environment)->NewLongArray(environment, 16);
    if (!result) return NULL;
    (*environment)->SetLongArrayRegion(environment, result, 0, 16, values);
    return result;
}
