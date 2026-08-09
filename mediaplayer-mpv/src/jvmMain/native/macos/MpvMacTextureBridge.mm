#include <jni.h>
#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <time.h>

#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <new>

#import <CoreFoundation/CoreFoundation.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>

struct mpv_handle;
struct mpv_render_context;

struct mpv_render_param {
    int type;
    void* data;
};

struct mpv_opengl_init_params {
    void* (*get_proc_address)(void* context, const char* name);
    void* get_proc_address_context;
};

struct mpv_opengl_fbo {
    int fbo;
    int width;
    int height;
    int internal_format;
};

struct mpv_render_frame_info {
    uint64_t flags;
    int64_t target_time;
};

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
    KMP_MPV_TEXTURE_POOL_SIZE = 6,
};

using MpvCreate = int (*)(mpv_render_context**, mpv_handle*, mpv_render_param*);
using MpvSetUpdateCallback = void (*)(mpv_render_context*, void (*)(void*), void*);
using MpvUpdate = uint64_t (*)(mpv_render_context*);
using MpvGetInfo = int (*)(mpv_render_context*, mpv_render_param);
using MpvRender = int (*)(mpv_render_context*, mpv_render_param*);
using MpvReportSwap = void (*)(mpv_render_context*);
using MpvFree = void (*)(mpv_render_context*);

struct MpvApi {
    MpvCreate create = nullptr;
    MpvSetUpdateCallback setUpdateCallback = nullptr;
    MpvUpdate update = nullptr;
    MpvGetInfo getInfo = nullptr;
    MpvRender render = nullptr;
    MpvReportSwap reportSwap = nullptr;
    MpvFree free = nullptr;
};

struct IOSurfaceSlot {
    IOSurfaceRef surface = nullptr;
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int width = 0;
    int height = 0;
    int depth = 0;
    uint64_t generation = 0;
    uint64_t serial = 0;
    bool inUse = false;
};

struct Renderer {
    std::atomic<int> references{1};
    std::atomic<bool> shuttingDown{false};
    std::atomic<bool> updatePending{true};
    std::atomic<bool> redrawRequested{true};
    std::atomic<uint64_t> updateCallbackCount{0};
    std::atomic<uint64_t> drawCount{0};
    std::atomic<uint64_t> renderedFrameCount{0};
    std::atomic<uint64_t> presentedFrameCount{0};
    std::atomic<uint64_t> newFrameCount{0};
    std::atomic<uint64_t> repeatedFrameCount{0};
    std::atomic<uint64_t> redrawFrameCount{0};
    std::atomic<uint64_t> emptyDrawCount{0};
    std::atomic<uint64_t> renderTimeNanos{0};
    std::atomic<uint64_t> maximumRenderTimeNanos{0};
    std::atomic<uint64_t> finishTimeNanos{0};
    std::atomic<uint64_t> maximumFinishTimeNanos{0};
    std::mutex lock;
    int colorMode = 0;
    int contentScaleMode = KMP_MPV_CONTENT_SCALE_FIT;
    float mediaAspect = 16.0f / 9.0f;
    mpv_handle* mpv = nullptr;
    void* library = nullptr;
    MpvApi api;
    mpv_render_context* renderContext = nullptr;
    CGLPixelFormatObj pixelFormat = nullptr;
    CGLContextObj glContext = nullptr;
    std::array<float, KMP_MPV_PROJECTION_PARAMETER_COUNT> projection{};
    GLuint projectionProgram = 0;
    GLuint projectionVertexArray = 0;
    GLuint projectionTexture = 0;
    GLuint projectionFramebuffer = 0;
    int projectionWidth = 0;
    int projectionHeight = 0;
    int projectionDepth = 0;
    std::array<IOSurfaceSlot, KMP_MPV_TEXTURE_POOL_SIZE> slots{};
    int outputWidth = 0;
    int outputHeight = 0;
    int outputDepth = 0;
    int outputSlot = -1;
    uint64_t outputGeneration = 0;
    uint64_t outputSerial = 0;
    uint64_t reportedSerial = 0;
};

static Renderer* retainRenderer(Renderer* renderer) {
    if (renderer) renderer->references.fetch_add(1, std::memory_order_relaxed);
    return renderer;
}

static void releaseRenderer(Renderer* renderer) {
    if (renderer && renderer->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        delete renderer;
    }
}

static uint64_t monotonicNanos() {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<uint64_t>(value.tv_sec) * 1000000000ULL +
        static_cast<uint64_t>(value.tv_nsec);
}

static void updateMaximum(std::atomic<uint64_t>& maximum, uint64_t value) {
    uint64_t observed = maximum.load(std::memory_order_relaxed);
    while (observed < value &&
           !maximum.compare_exchange_weak(observed, value, std::memory_order_relaxed)) {
    }
}

static void glFlushNoop() {
}

static void* resolveOpenGlFunction(void*, const char* name) {
    if (!name) return nullptr;
    if (std::strcmp(name, "glFlush") == 0) return reinterpret_cast<void*>(&glFlushNoop);
    CFBundleRef bundle = CFBundleGetBundleWithIdentifier(CFSTR("com.apple.opengl"));
    if (!bundle) return nullptr;
    CFStringRef symbol =
        CFStringCreateWithCString(kCFAllocatorDefault, name, kCFStringEncodingASCII);
    if (!symbol) return nullptr;
    void* address = CFBundleGetFunctionPointerForName(bundle, symbol);
    CFRelease(symbol);
    return address;
}

template <typename T>
static T librarySymbol(void* library, const char* name) {
    return reinterpret_cast<T>(library ? dlsym(library, name) : nullptr);
}

static bool loadMpvApi(Renderer* renderer, const char* libraryName) {
    renderer->library = dlopen(libraryName, RTLD_LAZY | RTLD_LOCAL);
    if (!renderer->library) return false;
    renderer->api.create = librarySymbol<MpvCreate>(renderer->library, "mpv_render_context_create");
    renderer->api.setUpdateCallback =
        librarySymbol<MpvSetUpdateCallback>(renderer->library, "mpv_render_context_set_update_callback");
    renderer->api.update = librarySymbol<MpvUpdate>(renderer->library, "mpv_render_context_update");
    renderer->api.getInfo = librarySymbol<MpvGetInfo>(renderer->library, "mpv_render_context_get_info");
    renderer->api.render = librarySymbol<MpvRender>(renderer->library, "mpv_render_context_render");
    renderer->api.reportSwap =
        librarySymbol<MpvReportSwap>(renderer->library, "mpv_render_context_report_swap");
    renderer->api.free = librarySymbol<MpvFree>(renderer->library, "mpv_render_context_free");
    return renderer->api.create && renderer->api.setUpdateCallback && renderer->api.update &&
        renderer->api.getInfo && renderer->api.render && renderer->api.reportSwap && renderer->api.free;
}

static bool choosePixelFormat(CGLPixelFormatObj* output) {
    GLint count = 0;
    const CGLPixelFormatAttribute floatAttributes[] = {
        kCGLPFAOpenGLProfile,
        static_cast<CGLPixelFormatAttribute>(kCGLOGLPVersion_3_2_Core),
        kCGLPFAAccelerated,
        kCGLPFAColorSize,
        static_cast<CGLPixelFormatAttribute>(64),
        kCGLPFAColorFloat,
        kCGLPFAAllowOfflineRenderers,
        static_cast<CGLPixelFormatAttribute>(0),
    };
    if (CGLChoosePixelFormat(floatAttributes, output, &count) == kCGLNoError && *output) {
        return true;
    }
    const CGLPixelFormatAttribute fallbackAttributes[] = {
        kCGLPFAOpenGLProfile,
        static_cast<CGLPixelFormatAttribute>(kCGLOGLPVersion_3_2_Core),
        kCGLPFAAccelerated,
        kCGLPFAAllowOfflineRenderers,
        static_cast<CGLPixelFormatAttribute>(0),
    };
    return CGLChoosePixelFormat(fallbackAttributes, output, &count) == kCGLNoError && *output;
}

static const GLchar* kProjectionVertexShader = R"GLSL(
#version 150 core
out vec2 vUv;
void main() {
    vec2 positions[4] = vec2[4](vec2(-1,-1), vec2(1,-1), vec2(-1,1), vec2(1,1));
    vec2 p = positions[gl_VertexID];
    gl_Position = vec4(p, 0, 1);
    vUv = (p + 1.0) * 0.5;
}
)GLSL";

static const GLchar* kProjectionFragmentShader = R"GLSL(
#version 150 core
uniform sampler2D uTexture;
uniform int uProjectionType;
uniform float uFovDegrees;
uniform int uStereo;
uniform vec4 uLeftWindow;
uniform int uLeftRotation;
uniform vec4 uRightWindow;
uniform int uRightRotation;
uniform float uViewYawDegrees;
uniform float uViewPitchDegrees;
uniform float uViewRollDegrees;
uniform float uViewZoom;
uniform float uViewportAspect;
uniform float uDestinationAspect;
uniform float uContentAspect;
uniform int uContentScaleMode;
in vec2 vUv;
out vec4 fragmentColor;
const float PI = 3.14159265358979323846264;
vec2 rotateUv(vec2 uv, int rotation) {
    if (rotation == 1) return vec2(1.0 - uv.y, uv.x);
    if (rotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (rotation == 3) return vec2(uv.y, 1.0 - uv.x);
    return uv;
}
vec4 sampleLocal(vec2 uv, bool rightEye) {
    if (any(lessThan(uv, vec2(0))) || any(greaterThan(uv, vec2(1)))) return vec4(0,0,0,1);
    vec4 window = rightEye ? uRightWindow : uLeftWindow;
    int rotation = rightEye ? uRightRotation : uLeftRotation;
    vec2 sourceUv = mix(window.xy, window.zw, rotateUv(uv, rotation));
    return texture(uTexture, vec2(sourceUv.x, 1.0 - sourceUv.y));
}
vec3 viewRay(vec2 uv) {
    vec2 p = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    float tangent = tan(radians(95.0) * 0.5 / max(uViewZoom, 0.01));
    vec3 d = normalize(vec3(p.x * uViewportAspect * tangent, p.y * tangent, -1));
    float y = radians(uViewYawDegrees), x = radians(uViewPitchDegrees), z = radians(uViewRollDegrees);
    d = vec3(cos(y)*d.x + sin(y)*d.z, d.y, -sin(y)*d.x + cos(y)*d.z);
    d = vec3(d.x, cos(x)*d.y - sin(x)*d.z, sin(x)*d.y + cos(x)*d.z);
    return normalize(vec3(cos(z)*d.x - sin(z)*d.y, sin(z)*d.x + cos(z)*d.y, d.z));
}
vec2 eacFace(float sc, float tc, float cx, float cy) {
    vec2 local = vec2(0.5 + atan(sc)/(0.5*PI), 0.5 - atan(tc)/(0.5*PI));
    return vec2((cx + local.x)/3.0, (cy + local.y)/2.0);
}
vec2 eacUv(vec3 d) {
    vec3 a = abs(d);
    if (a.z >= a.x && a.z >= a.y)
        return d.z < 0 ? eacFace(d.x/-d.z,d.y/-d.z,0,0) : eacFace(-d.x/d.z,d.y/d.z,2,0);
    if (a.x >= a.y)
        return d.x > 0 ? eacFace(d.z/d.x,d.y/d.x,1,0) : eacFace(-d.z/-d.x,d.y/-d.x,0,1);
    return d.y > 0 ? eacFace(d.x/d.y,d.z/d.y,1,1) : eacFace(d.x/-d.y,-d.z/-d.y,2,1);
}
vec2 contentUv(vec2 uv) {
    float da = max(uDestinationAspect, 0.001), ca = max(uContentAspect, 0.001);
    if (uContentScaleMode == 2) return uv;
    if (uContentScaleMode == 1) {
        if (da > ca) uv.y = (uv.y - 0.5) * ca/da + 0.5;
        else uv.x = (uv.x - 0.5) * da/ca + 0.5;
    } else {
        if (da > ca) uv.x = (uv.x - 0.5) / (ca/da) + 0.5;
        else uv.y = (uv.y - 0.5) / (da/ca) + 0.5;
    }
    return uv;
}
void main() {
    vec2 uv = contentUv(vec2(vUv.x, 1.0-vUv.y));
    if (any(lessThan(uv,vec2(0))) || any(greaterThan(uv,vec2(1)))) { fragmentColor=vec4(0,0,0,1); return; }
    bool rightEye = false;
    if (uStereo != 0) {
        if (uv.x < 0.5) uv.x *= 2.0;
        else { uv.x = (uv.x - 0.5) * 2.0; rightEye = true; }
    }
    if (uProjectionType == 0) { fragmentColor = sampleLocal(uv,rightEye); return; }
    vec3 d = viewRay(uv);
    if (uProjectionType == 1 || uProjectionType == 2) {
        float fov = max(radians(uFovDegrees), radians(1.0));
        float yaw = atan(d.x,-d.z), pitch = asin(clamp(d.y,-1.0,1.0));
        fragmentColor = abs(yaw) > fov*0.5 ? vec4(0,0,0,1) : sampleLocal(vec2(yaw/fov+0.5,0.5-pitch/PI),rightEye);
        return;
    }
    if (uProjectionType >= 3 && uProjectionType <= 6) {
        float maxTheta = max(radians(uFovDegrees),radians(1.0))*0.5;
        float theta = acos(clamp(-d.z,-1.0,1.0));
        if (theta > maxTheta) fragmentColor=vec4(0,0,0,1);
        else { float phi=atan(d.y,d.x), radius=theta/maxTheta*0.5;
            fragmentColor=sampleLocal(vec2(0.5+cos(phi)*radius,0.5-sin(phi)*radius),rightEye); }
        return;
    }
    fragmentColor = sampleLocal(eacUv(d),rightEye);
}
)GLSL";

static GLuint compileShader(GLenum type, const GLchar* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static bool ensureProjectionProgram(Renderer* renderer) {
    if (renderer->projectionProgram && renderer->projectionVertexArray) return true;
    GLuint vertex = compileShader(GL_VERTEX_SHADER, kProjectionVertexShader);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kProjectionFragmentShader);
    if (!vertex || !fragment) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        glDeleteProgram(program);
        return false;
    }
    GLuint vertexArray = 0;
    glGenVertexArrays(1, &vertexArray);
    if (!vertexArray) {
        glDeleteProgram(program);
        return false;
    }
    renderer->projectionProgram = program;
    renderer->projectionVertexArray = vertexArray;
    return true;
}

static void destroyProjectionTarget(Renderer* renderer) {
    if (renderer->projectionFramebuffer) glDeleteFramebuffers(1, &renderer->projectionFramebuffer);
    if (renderer->projectionTexture) glDeleteTextures(1, &renderer->projectionTexture);
    renderer->projectionFramebuffer = 0;
    renderer->projectionTexture = 0;
    renderer->projectionWidth = 0;
    renderer->projectionHeight = 0;
    renderer->projectionDepth = 0;
}

static void destroyProjectionResources(Renderer* renderer) {
    destroyProjectionTarget(renderer);
    if (renderer->projectionVertexArray) glDeleteVertexArrays(1, &renderer->projectionVertexArray);
    if (renderer->projectionProgram) glDeleteProgram(renderer->projectionProgram);
    renderer->projectionVertexArray = 0;
    renderer->projectionProgram = 0;
}

static bool ensureProjectionTarget(Renderer* renderer, int width, int height, int depth) {
    if (!ensureProjectionProgram(renderer)) return false;
    if (renderer->projectionFramebuffer && renderer->projectionWidth == width &&
        renderer->projectionHeight == height && renderer->projectionDepth == depth) {
        return true;
    }
    destroyProjectionTarget(renderer);
    GLenum internalFormat = depth > 8 ? GL_RGBA16F : GL_RGBA8;
    GLenum type = depth > 8 ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE;
    glGenTextures(1, &renderer->projectionTexture);
    glBindTexture(GL_TEXTURE_2D, renderer->projectionTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GL_RGBA, type, nullptr);
    glGenFramebuffers(1, &renderer->projectionFramebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, renderer->projectionFramebuffer);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, renderer->projectionTexture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        destroyProjectionTarget(renderer);
        return false;
    }
    renderer->projectionWidth = width;
    renderer->projectionHeight = height;
    renderer->projectionDepth = depth;
    return true;
}

static void renderProjection(Renderer* renderer, GLuint destination, int width, int height) {
    const float* p = renderer->projection.data();
    bool stereo = p[KMP_MPV_PROJECTION_STEREO] > 0.5f;
    glBindFramebuffer(GL_FRAMEBUFFER, destination);
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(renderer->projectionProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, renderer->projectionTexture);
    glUniform1i(glGetUniformLocation(renderer->projectionProgram, "uTexture"), 0);
    glUniform1i(glGetUniformLocation(renderer->projectionProgram, "uProjectionType"),
                static_cast<GLint>(llroundf(p[KMP_MPV_PROJECTION_TYPE])));
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uFovDegrees"),
                p[KMP_MPV_PROJECTION_FOV]);
    glUniform1i(glGetUniformLocation(renderer->projectionProgram, "uStereo"), stereo ? 1 : 0);
    glUniform4fv(glGetUniformLocation(renderer->projectionProgram, "uLeftWindow"), 1,
                 &p[KMP_MPV_PROJECTION_LEFT_WINDOW]);
    glUniform1i(glGetUniformLocation(renderer->projectionProgram, "uLeftRotation"),
                static_cast<GLint>(llroundf(p[KMP_MPV_PROJECTION_LEFT_ROTATION])));
    glUniform4fv(glGetUniformLocation(renderer->projectionProgram, "uRightWindow"), 1,
                 &p[KMP_MPV_PROJECTION_RIGHT_WINDOW]);
    glUniform1i(glGetUniformLocation(renderer->projectionProgram, "uRightRotation"),
                static_cast<GLint>(llroundf(p[KMP_MPV_PROJECTION_RIGHT_ROTATION])));
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uViewYawDegrees"),
                p[KMP_MPV_PROJECTION_YAW]);
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uViewPitchDegrees"),
                p[KMP_MPV_PROJECTION_PITCH]);
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uViewRollDegrees"),
                p[KMP_MPV_PROJECTION_ROLL]);
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uViewZoom"),
                p[KMP_MPV_PROJECTION_ZOOM]);
    float destinationAspect = static_cast<float>(width) / fmaxf(static_cast<float>(height), 1.0f);
    float contentAspect = fmaxf(renderer->mediaAspect, 0.001f);
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uViewportAspect"),
                stereo ? contentAspect * 0.5f : contentAspect);
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uDestinationAspect"),
                destinationAspect);
    glUniform1f(glGetUniformLocation(renderer->projectionProgram, "uContentAspect"), contentAspect);
    glUniform1i(glGetUniformLocation(renderer->projectionProgram, "uContentScaleMode"),
                renderer->contentScaleMode);
    glBindVertexArray(renderer->projectionVertexArray);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

static void destroySlotResources(IOSurfaceSlot& slot, bool preserveLease) {
    if (slot.framebuffer) glDeleteFramebuffers(1, &slot.framebuffer);
    if (slot.texture) glDeleteTextures(1, &slot.texture);
    if (slot.surface) CFRelease(slot.surface);
    slot.surface = nullptr;
    slot.texture = 0;
    slot.framebuffer = 0;
    slot.width = 0;
    slot.height = 0;
    slot.depth = 0;
    if (!preserveLease) {
        slot.generation = 0;
        slot.serial = 0;
        slot.inUse = false;
    }
}

static bool configureSlot(
    Renderer* renderer,
    IOSurfaceSlot& slot,
    int width,
    int height,
    int depth,
    uint64_t generation) {
    destroySlotResources(slot, false);
    @autoreleasepool {
        NSDictionary* properties = @{
            (NSString*)kIOSurfaceWidth: @(width),
            (NSString*)kIOSurfaceHeight: @(height),
            (NSString*)kIOSurfaceBytesPerElement: @(depth > 8 ? 8 : 4),
            (NSString*)kIOSurfacePixelFormat:
                @(depth > 8
                    ? static_cast<int>(kCVPixelFormatType_64RGBAHalf)
                    : static_cast<int>(kCVPixelFormatType_32BGRA)),
        };
        slot.surface = IOSurfaceCreate((CFDictionaryRef)properties);
    }
    if (!slot.surface) return false;

    const GLenum internalFormat = depth > 8 ? GL_RGBA16F : GL_RGBA8;
    const GLenum format = depth > 8 ? GL_RGBA : GL_BGRA;
    const GLenum type = depth > 8 ? GL_HALF_FLOAT : GL_UNSIGNED_INT_8_8_8_8_REV;
    glGenTextures(1, &slot.texture);
    glBindTexture(GL_TEXTURE_RECTANGLE, slot.texture);
    glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (CGLTexImageIOSurface2D(
            renderer->glContext,
            GL_TEXTURE_RECTANGLE,
            internalFormat,
            width,
            height,
            format,
            type,
            slot.surface,
            0) != kCGLNoError) {
        destroySlotResources(slot, false);
        return false;
    }
    glGenFramebuffers(1, &slot.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, slot.framebuffer);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, slot.texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        destroySlotResources(slot, false);
        return false;
    }
    slot.width = width;
    slot.height = height;
    slot.depth = depth;
    slot.generation = generation;
    return true;
}

static int acquireOutputSlot(Renderer* renderer, int width, int height, int depth) {
    if (renderer->outputWidth != width || renderer->outputHeight != height ||
        renderer->outputDepth != depth) {
        renderer->outputWidth = width;
        renderer->outputHeight = height;
        renderer->outputDepth = depth;
        ++renderer->outputGeneration;
        renderer->outputSlot = -1;
        destroyProjectionTarget(renderer);
    }
    for (size_t index = 0; index < renderer->slots.size(); ++index) {
        IOSurfaceSlot& slot = renderer->slots[index];
        if (slot.inUse) continue;
        if (slot.width != width || slot.height != height || slot.depth != depth ||
            slot.generation != renderer->outputGeneration) {
            if (!configureSlot(renderer, slot, width, height, depth, renderer->outputGeneration)) {
                continue;
            }
        }
        return static_cast<int>(index);
    }
    return -1;
}

static void updateCallback(void* context) {
    Renderer* renderer = static_cast<Renderer*>(context);
    if (!renderer || renderer->shuttingDown.load(std::memory_order_acquire)) return;
    renderer->updateCallbackCount.fetch_add(1, std::memory_order_relaxed);
    renderer->updatePending.store(true, std::memory_order_release);
}

static bool initializeRenderer(Renderer* renderer) {
    if (!choosePixelFormat(&renderer->pixelFormat)) return false;
    if (CGLCreateContext(renderer->pixelFormat, nullptr, &renderer->glContext) != kCGLNoError ||
        !renderer->glContext) {
        return false;
    }
    CGLSetCurrentContext(renderer->glContext);
    const char* apiType = "opengl";
    int advancedControl = 1;
    mpv_opengl_init_params openGl{resolveOpenGlFunction, nullptr};
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char*>(apiType)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &openGl},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advancedControl},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    if (renderer->api.create(&renderer->renderContext, renderer->mpv, parameters) < 0 ||
        !renderer->renderContext) {
        return false;
    }
    renderer->projection[KMP_MPV_PROJECTION_ZOOM] = 1.0f;
    renderer->api.setUpdateCallback(renderer->renderContext, updateCallback, renderer);
    return true;
}

static void destroyRendererResources(Renderer* renderer) {
    if (!renderer) return;
    renderer->shuttingDown.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        if (renderer->glContext) CGLSetCurrentContext(renderer->glContext);
        if (renderer->renderContext) {
            renderer->api.setUpdateCallback(renderer->renderContext, nullptr, nullptr);
            renderer->api.free(renderer->renderContext);
            renderer->renderContext = nullptr;
        }
        if (renderer->glContext) {
            destroyProjectionResources(renderer);
            for (IOSurfaceSlot& slot : renderer->slots) {
                destroySlotResources(slot, slot.inUse);
            }
            CGLSetCurrentContext(nullptr);
            CGLReleaseContext(renderer->glContext);
            renderer->glContext = nullptr;
        }
        if (renderer->pixelFormat) {
            CGLReleasePixelFormat(renderer->pixelFormat);
            renderer->pixelFormat = nullptr;
        }
        if (renderer->library) {
            dlclose(renderer->library);
            renderer->library = nullptr;
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nCreateRenderer(
    JNIEnv* environment,
    jclass,
    jlong rawMpvHandle,
    jstring libraryLoadName,
    jint colorMode) {
    if (!environment || !libraryLoadName || rawMpvHandle == 0) return 0;
    const char* libraryName = environment->GetStringUTFChars(libraryLoadName, nullptr);
    if (!libraryName) return 0;
    Renderer* renderer = new (std::nothrow) Renderer();
    if (!renderer) {
        environment->ReleaseStringUTFChars(libraryLoadName, libraryName);
        return 0;
    }
    renderer->mpv = reinterpret_cast<mpv_handle*>(static_cast<uintptr_t>(rawMpvHandle));
    renderer->colorMode = colorMode;
    bool loaded = loadMpvApi(renderer, libraryName);
    environment->ReleaseStringUTFChars(libraryLoadName, libraryName);
    if (!loaded || !initializeRenderer(renderer)) {
        destroyRendererResources(renderer);
        releaseRenderer(renderer);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(renderer));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nRenderFrame(
    JNIEnv*, jclass, jlong nativeRenderer, jint width, jint height) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || width <= 0 || height <= 0 ||
        renderer->shuttingDown.load(std::memory_order_acquire)) {
        return -1;
    }
    std::lock_guard<std::mutex> guard(renderer->lock);
    if (!renderer->renderContext || !renderer->glContext) return -1;
    CGLSetCurrentContext(renderer->glContext);
    renderer->drawCount.fetch_add(1, std::memory_order_relaxed);
    bool updatePending = renderer->updatePending.exchange(false, std::memory_order_acq_rel);
    bool redraw = renderer->redrawRequested.exchange(false, std::memory_order_acq_rel);
    uint64_t updates = updatePending ? renderer->api.update(renderer->renderContext) : 0;
    if (!redraw && (updates & MPV_RENDER_UPDATE_FRAME) == 0 && renderer->outputSerial > 0) {
        return static_cast<jlong>(renderer->outputSerial);
    }

    const int depth = renderer->colorMode == 0 ? 8 : 16;
    int slotIndex = acquireOutputSlot(renderer, width, height, depth);
    if (slotIndex < 0) {
        renderer->updatePending.store(true, std::memory_order_release);
        return static_cast<jlong>(renderer->outputSerial);
    }
    IOSurfaceSlot& slot = renderer->slots[static_cast<size_t>(slotIndex)];
    bool projectionActive =
        renderer->projection[KMP_MPV_PROJECTION_ENABLED] > 0.5f &&
        ensureProjectionTarget(renderer, width, height, depth);
    GLenum internalFormat = depth > 8 ? GL_RGBA16F : GL_RGBA8;
    mpv_opengl_fbo target{
        static_cast<int>(projectionActive ? renderer->projectionFramebuffer : slot.framebuffer),
        width,
        height,
        static_cast<int>(internalFormat),
    };
    int flipY = 1;
    int blockForTargetTime = 0;
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &target},
        {MPV_RENDER_PARAM_FLIP_Y, &flipY},
        {MPV_RENDER_PARAM_DEPTH, &depth},
        {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &blockForTargetTime},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_frame_info frameInfo{};
    mpv_render_param infoParameter{MPV_RENDER_PARAM_NEXT_FRAME_INFO, &frameInfo};
    bool hasFrameInfo = renderer->api.getInfo(renderer->renderContext, infoParameter) >= 0 &&
        (frameInfo.flags & MPV_RENDER_FRAME_INFO_PRESENT) != 0;
    if (!hasFrameInfo) renderer->emptyDrawCount.fetch_add(1, std::memory_order_relaxed);
    else if ((frameInfo.flags & MPV_RENDER_FRAME_INFO_REDRAW) != 0)
        renderer->redrawFrameCount.fetch_add(1, std::memory_order_relaxed);
    else if ((frameInfo.flags & MPV_RENDER_FRAME_INFO_REPEAT) != 0)
        renderer->repeatedFrameCount.fetch_add(1, std::memory_order_relaxed);
    else renderer->newFrameCount.fetch_add(1, std::memory_order_relaxed);

    uint64_t started = monotonicNanos();
    if (renderer->api.render(renderer->renderContext, parameters) < 0) return -1;
    if (projectionActive) renderProjection(renderer, slot.framebuffer, width, height);
    uint64_t renderElapsed = monotonicNanos() - started;
    renderer->renderTimeNanos.fetch_add(renderElapsed, std::memory_order_relaxed);
    updateMaximum(renderer->maximumRenderTimeNanos, renderElapsed);
    uint64_t finishStarted = monotonicNanos();
    glFinish();
    uint64_t finishElapsed = monotonicNanos() - finishStarted;
    renderer->finishTimeNanos.fetch_add(finishElapsed, std::memory_order_relaxed);
    updateMaximum(renderer->maximumFinishTimeNanos, finishElapsed);

    slot.inUse = true;
    slot.serial = ++renderer->outputSerial;
    slot.generation = renderer->outputGeneration;
    renderer->outputSlot = slotIndex;
    retainRenderer(renderer);
    renderer->renderedFrameCount.fetch_add(1, std::memory_order_relaxed);
    return static_cast<jlong>(slot.serial);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetTextureOutputInfo(
    JNIEnv* environment, jclass, jlong nativeRenderer) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!environment || !renderer || renderer->shuttingDown.load(std::memory_order_acquire)) return nullptr;
    std::lock_guard<std::mutex> guard(renderer->lock);
    if (renderer->outputSlot < 0) return nullptr;
    IOSurfaceSlot& slot = renderer->slots[static_cast<size_t>(renderer->outputSlot)];
    if (!slot.surface || !slot.inUse || slot.serial == 0) return nullptr;
    jlong values[] = {
        static_cast<jlong>(reinterpret_cast<uintptr_t>(slot.surface)),
        slot.width,
        slot.height,
        static_cast<jlong>(IOSurfaceGetPixelFormat(slot.surface)),
        static_cast<jlong>(slot.generation),
        static_cast<jlong>(slot.serial),
        slot.depth > 8 ? 1 : 0,
    };
    jlongArray result = environment->NewLongArray(7);
    if (result) environment->SetLongArrayRegion(result, 0, 7, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nReleaseTextureFrame(
    JNIEnv*, jclass, jlong nativeRenderer, jlong generation, jlong serial) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || generation <= 0 || serial <= 0) return;
    bool releaseReference = false;
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        for (IOSurfaceSlot& slot : renderer->slots) {
            if (slot.inUse && slot.generation == static_cast<uint64_t>(generation) &&
                slot.serial == static_cast<uint64_t>(serial)) {
                slot.inUse = false;
                releaseReference = true;
                break;
            }
        }
    }
    if (releaseReference) releaseRenderer(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nReportPresented(
    JNIEnv*, jclass, jlong nativeRenderer, jlong serial) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || serial <= 0 || renderer->shuttingDown.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    uint64_t value = static_cast<uint64_t>(serial);
    if (renderer->renderContext && value <= renderer->outputSerial && value > renderer->reportedSerial) {
        renderer->api.reportSwap(renderer->renderContext);
        renderer->reportedSerial = value;
        renderer->presentedFrameCount.fetch_add(1, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nDetach(
    JNIEnv*, jclass, jlong nativeRenderer) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer) return;
    destroyRendererResources(renderer);
    releaseRenderer(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetColorMode(
    JNIEnv*, jclass, jlong nativeRenderer, jint colorMode) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || renderer->shuttingDown.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    if (renderer->colorMode != colorMode) {
        renderer->colorMode = colorMode;
        renderer->redrawRequested.store(true, std::memory_order_release);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetProjection(
    JNIEnv* environment, jclass, jlong nativeRenderer, jfloatArray parameters) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!environment || !renderer || !parameters ||
        environment->GetArrayLength(parameters) < KMP_MPV_PROJECTION_PARAMETER_COUNT ||
        renderer->shuttingDown.load(std::memory_order_acquire)) return;
    std::array<jfloat, KMP_MPV_PROJECTION_PARAMETER_COUNT> values{};
    environment->GetFloatArrayRegion(
        parameters,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    if (environment->ExceptionCheck()) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    for (size_t index = 0; index < values.size(); ++index) {
        renderer->projection[index] = isfinite(values[index]) ? values[index] : 0.0f;
    }
    if (renderer->projection[KMP_MPV_PROJECTION_ZOOM] <= 0.0f)
        renderer->projection[KMP_MPV_PROJECTION_ZOOM] = 1.0f;
    renderer->redrawRequested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetContentScale(
    JNIEnv*, jclass, jlong nativeRenderer, jint contentScaleMode, jfloat mediaAspect) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || renderer->shuttingDown.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    renderer->contentScaleMode =
        contentScaleMode >= KMP_MPV_CONTENT_SCALE_FIT && contentScaleMode <= KMP_MPV_CONTENT_SCALE_FILL
            ? contentScaleMode : KMP_MPV_CONTENT_SCALE_FIT;
    renderer->mediaAspect = isfinite(mediaAspect) && mediaAspect > 0 ? mediaAspect : 16.0f / 9.0f;
    renderer->redrawRequested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nRequestRedraw(
    JNIEnv*, jclass, jlong nativeRenderer) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (renderer && !renderer->shuttingDown.load(std::memory_order_acquire))
        renderer->redrawRequested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetDisplayRefreshRate(
    JNIEnv*, jclass, jlong) {
    return 0.0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetPresentationDiagnostics(
    JNIEnv* environment, jclass, jlong nativeRenderer) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!environment || !renderer) return nullptr;
    jlong values[] = {
        static_cast<jlong>(renderer->updateCallbackCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->drawCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->renderedFrameCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->presentedFrameCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->newFrameCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->repeatedFrameCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->redrawFrameCount.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->emptyDrawCount.load(std::memory_order_relaxed)),
        0, 0,
        static_cast<jlong>(renderer->renderTimeNanos.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->maximumRenderTimeNanos.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->finishTimeNanos.load(std::memory_order_relaxed)),
        static_cast<jlong>(renderer->maximumFinishTimeNanos.load(std::memory_order_relaxed)),
        0, 0,
    };
    jlongArray result = environment->NewLongArray(16);
    if (result) environment->SetLongArrayRegion(result, 0, 16, values);
    return result;
}
