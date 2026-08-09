#include <jni.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

namespace {

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

enum {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y = 4,
    MPV_RENDER_PARAM_DEPTH = 5,
    MPV_RENDER_PARAM_ADVANCED_CONTROL = 10,
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12,
};

enum { MPV_RENDER_UPDATE_FRAME = 1 << 0 };

using EGLDisplay = void*;
using EGLConfig = void*;
using EGLContext = void*;
using EGLSurface = void*;
using EGLImageKHR = void*;
using EGLSyncKHR = void*;
using EGLClientBuffer = void*;
using EGLBoolean = int;
using EGLint = int;
using EGLenum = unsigned int;
using GLenum = unsigned int;
using GLuint = unsigned int;
using GLint = int;
using GLsizei = int;

constexpr EGLBoolean EGL_TRUE_VALUE = 1;
constexpr EGLint EGL_NONE_VALUE = 0x3038;
constexpr EGLDisplay EGL_NO_DISPLAY_VALUE = nullptr;
constexpr EGLContext EGL_NO_CONTEXT_VALUE = nullptr;
constexpr EGLSurface EGL_NO_SURFACE_VALUE = nullptr;
constexpr EGLImageKHR EGL_NO_IMAGE_VALUE = nullptr;
constexpr EGLSyncKHR EGL_NO_SYNC_VALUE = nullptr;
constexpr EGLenum EGL_PLATFORM_GBM_KHR = 0x31D7;
constexpr EGLenum EGL_OPENGL_API = 0x30A2;
constexpr EGLint EGL_SURFACE_TYPE = 0x3033;
constexpr EGLint EGL_PBUFFER_BIT = 0x0001;
constexpr EGLint EGL_RENDERABLE_TYPE = 0x3040;
constexpr EGLint EGL_OPENGL_BIT = 0x0008;
constexpr EGLint EGL_RED_SIZE = 0x3024;
constexpr EGLint EGL_CONTEXT_MAJOR_VERSION = 0x3098;
constexpr EGLint EGL_CONTEXT_MINOR_VERSION = 0x30FB;
constexpr EGLint EGL_CONTEXT_OPENGL_PROFILE_MASK = 0x30FD;
constexpr EGLint EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT = 0x00000002;
constexpr EGLint EGL_EXTENSIONS = 0x3055;
constexpr EGLint EGL_WIDTH = 0x3057;
constexpr EGLint EGL_HEIGHT = 0x3056;
constexpr EGLenum EGL_LINUX_DMA_BUF_EXT = 0x3270;
constexpr EGLint EGL_LINUX_DRM_FOURCC_EXT = 0x3271;
constexpr EGLint EGL_DMA_BUF_PLANE0_FD_EXT = 0x3272;
constexpr EGLint EGL_DMA_BUF_PLANE0_OFFSET_EXT = 0x3273;
constexpr EGLint EGL_DMA_BUF_PLANE0_PITCH_EXT = 0x3274;
constexpr EGLint EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT = 0x3443;
constexpr EGLint EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT = 0x3444;
constexpr EGLint EGL_IMAGE_PRESERVED_KHR = 0x30D2;
constexpr EGLenum EGL_SYNC_NATIVE_FENCE_ANDROID = 0x3144;
constexpr EGLint EGL_SYNC_NATIVE_FENCE_FD_ANDROID = 0x3145;
constexpr EGLint EGL_NO_NATIVE_FENCE_FD_ANDROID = -1;

constexpr GLenum GL_TEXTURE_2D = 0x0DE1;
constexpr GLenum GL_TEXTURE_MIN_FILTER = 0x2801;
constexpr GLenum GL_TEXTURE_MAG_FILTER = 0x2800;
constexpr GLenum GL_TEXTURE_WRAP_S = 0x2802;
constexpr GLenum GL_TEXTURE_WRAP_T = 0x2803;
constexpr GLint GL_LINEAR = 0x2601;
constexpr GLint GL_CLAMP_TO_EDGE = 0x812F;
constexpr GLenum GL_FRAMEBUFFER = 0x8D40;
constexpr GLenum GL_COLOR_ATTACHMENT0 = 0x8CE0;
constexpr GLenum GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
constexpr GLenum GL_RGBA8 = 0x8058;
constexpr GLenum GL_RGBA16F = 0x881A;

constexpr uint32_t DRM_FORMAT_ARGB8888 = 0x34325241u;
constexpr uint32_t DRM_FORMAT_ABGR16161616F = 0x48344241u;
constexpr uint64_t DRM_FORMAT_MOD_INVALID = 0x00FFFFFFFFFFFFFFULL;
constexpr uint32_t GBM_BO_USE_RENDERING = 1u << 2;
constexpr size_t TEXTURE_POOL_SIZE = 6;

using MpvCreate = int (*)(mpv_render_context**, mpv_handle*, mpv_render_param*);
using MpvSetUpdateCallback = void (*)(mpv_render_context*, void (*)(void*), void*);
using MpvUpdate = uint64_t (*)(mpv_render_context*);
using MpvRender = int (*)(mpv_render_context*, mpv_render_param*);
using MpvReportSwap = void (*)(mpv_render_context*);
using MpvFree = void (*)(mpv_render_context*);

struct MpvApi {
    MpvCreate create = nullptr;
    MpvSetUpdateCallback setUpdateCallback = nullptr;
    MpvUpdate update = nullptr;
    MpvRender render = nullptr;
    MpvReportSwap reportSwap = nullptr;
    MpvFree free = nullptr;
};

using EglGetProcAddress = void* (*)(const char*);
using EglGetPlatformDisplay = EGLDisplay (*)(EGLenum, void*, const EGLint*);
using EglInitialize = EGLBoolean (*)(EGLDisplay, EGLint*, EGLint*);
using EglTerminate = EGLBoolean (*)(EGLDisplay);
using EglBindApi = EGLBoolean (*)(EGLenum);
using EglChooseConfig = EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
using EglCreateContext = EGLContext (*)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
using EglDestroyContext = EGLBoolean (*)(EGLDisplay, EGLContext);
using EglMakeCurrent = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
using EglQueryString = const char* (*)(EGLDisplay, EGLint);
using EglCreateImage = EGLImageKHR (*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*);
using EglDestroyImage = EGLBoolean (*)(EGLDisplay, EGLImageKHR);
using EglCreateSync = EGLSyncKHR (*)(EGLDisplay, EGLenum, const EGLint*);
using EglDestroySync = EGLBoolean (*)(EGLDisplay, EGLSyncKHR);
using EglDupFenceFd = EGLint (*)(EGLDisplay, EGLSyncKHR);

struct EglApi {
    EglGetProcAddress getProcAddress = nullptr;
    EglGetPlatformDisplay getPlatformDisplay = nullptr;
    EglInitialize initialize = nullptr;
    EglTerminate terminate = nullptr;
    EglBindApi bindApi = nullptr;
    EglChooseConfig chooseConfig = nullptr;
    EglCreateContext createContext = nullptr;
    EglDestroyContext destroyContext = nullptr;
    EglMakeCurrent makeCurrent = nullptr;
    EglQueryString queryString = nullptr;
    EglCreateImage createImage = nullptr;
    EglDestroyImage destroyImage = nullptr;
    EglCreateSync createSync = nullptr;
    EglDestroySync destroySync = nullptr;
    EglDupFenceFd dupFenceFd = nullptr;
};

using GbmCreateDevice = void* (*)(int);
using GbmDestroyDevice = void (*)(void*);
using GbmCreateBo = void* (*)(void*, uint32_t, uint32_t, uint32_t, uint32_t);
using GbmCreateBoWithModifiers = void* (*)(void*, uint32_t, uint32_t, uint32_t, const uint64_t*, unsigned);
using GbmCreateBoWithModifiers2 =
    void* (*)(void*, uint32_t, uint32_t, uint32_t, const uint64_t*, unsigned, uint32_t);
using GbmDestroyBo = void (*)(void*);
using GbmBoGetFd = int (*)(void*);
using GbmBoGetStride = uint32_t (*)(void*);
using GbmBoGetOffset = uint32_t (*)(void*, int);
using GbmBoGetModifier = uint64_t (*)(void*);

struct GbmApi {
    GbmCreateDevice createDevice = nullptr;
    GbmDestroyDevice destroyDevice = nullptr;
    GbmCreateBo createBo = nullptr;
    GbmCreateBoWithModifiers createBoWithModifiers = nullptr;
    GbmCreateBoWithModifiers2 createBoWithModifiers2 = nullptr;
    GbmDestroyBo destroyBo = nullptr;
    GbmBoGetFd getFd = nullptr;
    GbmBoGetStride getStride = nullptr;
    GbmBoGetOffset getOffset = nullptr;
    GbmBoGetModifier getModifier = nullptr;
};

using GlEglImageTargetTexture = void (*)(GLenum, EGLImageKHR);
using GlGenTextures = void (*)(GLsizei, GLuint*);
using GlDeleteTextures = void (*)(GLsizei, const GLuint*);
using GlBindTexture = void (*)(GLenum, GLuint);
using GlTexParameteri = void (*)(GLenum, GLenum, GLint);
using GlGenFramebuffers = void (*)(GLsizei, GLuint*);
using GlDeleteFramebuffers = void (*)(GLsizei, const GLuint*);
using GlBindFramebuffer = void (*)(GLenum, GLuint);
using GlFramebufferTexture2D = void (*)(GLenum, GLenum, GLenum, GLuint, GLint);
using GlCheckFramebufferStatus = GLenum (*)(GLenum);
using GlFlush = void (*)();
using GlFinish = void (*)();

struct GlApi {
    GlEglImageTargetTexture imageTargetTexture = nullptr;
    GlGenTextures genTextures = nullptr;
    GlDeleteTextures deleteTextures = nullptr;
    GlBindTexture bindTexture = nullptr;
    GlTexParameteri texParameteri = nullptr;
    GlGenFramebuffers genFramebuffers = nullptr;
    GlDeleteFramebuffers deleteFramebuffers = nullptr;
    GlBindFramebuffer bindFramebuffer = nullptr;
    GlFramebufferTexture2D framebufferTexture2D = nullptr;
    GlCheckFramebufferStatus checkFramebufferStatus = nullptr;
    GlFlush flush = nullptr;
    GlFinish finish = nullptr;
};

struct TextureSlot {
    void* bo = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_VALUE;
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int width = 0;
    int height = 0;
    uint32_t fourcc = 0;
    int stride = 0;
    int offset = 0;
    uint64_t modifier = DRM_FORMAT_MOD_INVALID;
    uint64_t generation = 0;
    uint64_t serial = 0;
    int acquireFenceFd = -1;
    int releaseFenceFd = -1;
    bool inUse = false;
    bool exported = false;
};

struct Renderer {
    std::atomic<int> references{1};
    std::atomic<bool> shuttingDown{false};
    std::atomic<bool> updatePending{true};
    std::atomic<bool> redrawRequested{true};
    std::mutex lock;
    std::string failure;
    std::string renderNode;
    std::vector<uint64_t> allowedModifiers;
    uint32_t desiredFourcc = DRM_FORMAT_ARGB8888;
    bool extendedLinear = false;
    mpv_handle* mpv = nullptr;
    void* mpvLibrary = nullptr;
    void* eglLibrary = nullptr;
    void* glLibrary = nullptr;
    void* gbmLibrary = nullptr;
    MpvApi mpvApi;
    EglApi egl;
    GbmApi gbm;
    GlApi gl;
    mpv_render_context* renderContext = nullptr;
    int drmFd = -1;
    void* gbmDevice = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY_VALUE;
    EGLContext context = EGL_NO_CONTEXT_VALUE;
    std::array<TextureSlot, TEXTURE_POOL_SIZE> slots{};
    int outputWidth = 0;
    int outputHeight = 0;
    uint32_t outputFourcc = 0;
    int outputSlot = -1;
    uint64_t outputGeneration = 0;
    uint64_t outputSerial = 0;
    uint64_t reportedSerial = 0;
};

template <typename T>
T symbol(void* library, const char* name) {
    return reinterpret_cast<T>(library ? dlsym(library, name) : nullptr);
}

static bool hasExtension(const char* extensions, const char* extension) {
    if (!extensions || !extension || std::strchr(extension, ' ')) return false;
    const size_t length = std::strlen(extension);
    const char* cursor = extensions;
    while ((cursor = std::strstr(cursor, extension)) != nullptr) {
        const bool begins = cursor == extensions || cursor[-1] == ' ';
        const bool ends = cursor[length] == '\0' || cursor[length] == ' ';
        if (begins && ends) return true;
        cursor += length;
    }
    return false;
}

static void retainRenderer(Renderer* renderer) {
    renderer->references.fetch_add(1, std::memory_order_relaxed);
}

static bool fenceSignalled(int fd, int timeoutMs) {
    if (fd < 0) return true;
    pollfd descriptor{fd, POLLIN, 0};
    const int result = poll(&descriptor, 1, timeoutMs);
    return result > 0 && (descriptor.revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)) != 0;
}

static void* resolveGl(Renderer* renderer, const char* name) {
    if (!renderer || !name) return nullptr;
    void* address = renderer->egl.getProcAddress ? renderer->egl.getProcAddress(name) : nullptr;
    if (!address && renderer->glLibrary) address = dlsym(renderer->glLibrary, name);
    return address;
}

static void* resolveMpvGl(void* context, const char* name) {
    return resolveGl(static_cast<Renderer*>(context), name);
}

static bool bindContext(Renderer* renderer) {
    return renderer->egl.makeCurrent &&
        renderer->egl.makeCurrent(
            renderer->display,
            EGL_NO_SURFACE_VALUE,
            EGL_NO_SURFACE_VALUE,
            renderer->context) == EGL_TRUE_VALUE;
}

static void unbindContext(Renderer* renderer) {
    if (renderer->egl.makeCurrent && renderer->display != EGL_NO_DISPLAY_VALUE) {
        renderer->egl.makeCurrent(
            renderer->display,
            EGL_NO_SURFACE_VALUE,
            EGL_NO_SURFACE_VALUE,
            EGL_NO_CONTEXT_VALUE);
    }
}

static void destroySlot(Renderer* renderer, TextureSlot& slot) {
    if (slot.acquireFenceFd >= 0) close(slot.acquireFenceFd);
    if (slot.releaseFenceFd >= 0) close(slot.releaseFenceFd);
    if (slot.framebuffer && renderer->gl.deleteFramebuffers) {
        renderer->gl.deleteFramebuffers(1, &slot.framebuffer);
    }
    if (slot.texture && renderer->gl.deleteTextures) renderer->gl.deleteTextures(1, &slot.texture);
    if (slot.image != EGL_NO_IMAGE_VALUE && renderer->egl.destroyImage) {
        renderer->egl.destroyImage(renderer->display, slot.image);
    }
    if (slot.bo && renderer->gbm.destroyBo) renderer->gbm.destroyBo(slot.bo);
    slot = TextureSlot{};
}

static void finalDestroy(Renderer* renderer) {
    if (!renderer) return;
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        if (renderer->context != EGL_NO_CONTEXT_VALUE && bindContext(renderer)) {
            for (TextureSlot& slot : renderer->slots) destroySlot(renderer, slot);
            unbindContext(renderer);
        } else {
            for (TextureSlot& slot : renderer->slots) {
                if (slot.acquireFenceFd >= 0) close(slot.acquireFenceFd);
                if (slot.releaseFenceFd >= 0) close(slot.releaseFenceFd);
                if (slot.bo && renderer->gbm.destroyBo) renderer->gbm.destroyBo(slot.bo);
                slot = TextureSlot{};
            }
        }
    }
    if (renderer->context != EGL_NO_CONTEXT_VALUE && renderer->egl.destroyContext) {
        renderer->egl.destroyContext(renderer->display, renderer->context);
    }
    if (renderer->display != EGL_NO_DISPLAY_VALUE && renderer->egl.terminate) {
        renderer->egl.terminate(renderer->display);
    }
    if (renderer->gbmDevice && renderer->gbm.destroyDevice) {
        renderer->gbm.destroyDevice(renderer->gbmDevice);
    }
    if (renderer->drmFd >= 0) close(renderer->drmFd);
    if (renderer->mpvLibrary) dlclose(renderer->mpvLibrary);
    if (renderer->gbmLibrary) dlclose(renderer->gbmLibrary);
    if (renderer->glLibrary) dlclose(renderer->glLibrary);
    if (renderer->eglLibrary) dlclose(renderer->eglLibrary);
    delete renderer;
}

static void releaseRenderer(Renderer* renderer) {
    if (renderer && renderer->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        finalDestroy(renderer);
    }
}

static bool loadMpv(Renderer* renderer, const char* libraryName) {
    renderer->mpvLibrary = dlopen(libraryName, RTLD_LAZY | RTLD_LOCAL);
    if (!renderer->mpvLibrary) {
        const char* error = dlerror();
        renderer->failure = error ? error : "Unable to load libmpv";
        return false;
    }
    renderer->mpvApi.create = symbol<MpvCreate>(renderer->mpvLibrary, "mpv_render_context_create");
    renderer->mpvApi.setUpdateCallback =
        symbol<MpvSetUpdateCallback>(renderer->mpvLibrary, "mpv_render_context_set_update_callback");
    renderer->mpvApi.update = symbol<MpvUpdate>(renderer->mpvLibrary, "mpv_render_context_update");
    renderer->mpvApi.render = symbol<MpvRender>(renderer->mpvLibrary, "mpv_render_context_render");
    renderer->mpvApi.reportSwap =
        symbol<MpvReportSwap>(renderer->mpvLibrary, "mpv_render_context_report_swap");
    renderer->mpvApi.free = symbol<MpvFree>(renderer->mpvLibrary, "mpv_render_context_free");
    return renderer->mpvApi.create && renderer->mpvApi.setUpdateCallback &&
        renderer->mpvApi.update && renderer->mpvApi.render &&
        renderer->mpvApi.reportSwap && renderer->mpvApi.free;
}

static bool loadGraphicsApis(Renderer* renderer) {
    renderer->eglLibrary = dlopen("libEGL.so.1", RTLD_LAZY | RTLD_LOCAL);
    renderer->glLibrary = dlopen("libGL.so.1", RTLD_LAZY | RTLD_LOCAL);
    renderer->gbmLibrary = dlopen("libgbm.so.1", RTLD_LAZY | RTLD_LOCAL);
    if (!renderer->eglLibrary || !renderer->gbmLibrary) {
        renderer->failure = "libEGL or libgbm is unavailable";
        return false;
    }

    renderer->egl.getProcAddress = symbol<EglGetProcAddress>(renderer->eglLibrary, "eglGetProcAddress");
    renderer->egl.getPlatformDisplay =
        symbol<EglGetPlatformDisplay>(renderer->eglLibrary, "eglGetPlatformDisplayEXT");
    if (!renderer->egl.getPlatformDisplay && renderer->egl.getProcAddress) {
        renderer->egl.getPlatformDisplay = reinterpret_cast<EglGetPlatformDisplay>(
            renderer->egl.getProcAddress("eglGetPlatformDisplayEXT"));
    }
    renderer->egl.initialize = symbol<EglInitialize>(renderer->eglLibrary, "eglInitialize");
    renderer->egl.terminate = symbol<EglTerminate>(renderer->eglLibrary, "eglTerminate");
    renderer->egl.bindApi = symbol<EglBindApi>(renderer->eglLibrary, "eglBindAPI");
    renderer->egl.chooseConfig = symbol<EglChooseConfig>(renderer->eglLibrary, "eglChooseConfig");
    renderer->egl.createContext = symbol<EglCreateContext>(renderer->eglLibrary, "eglCreateContext");
    renderer->egl.destroyContext = symbol<EglDestroyContext>(renderer->eglLibrary, "eglDestroyContext");
    renderer->egl.makeCurrent = symbol<EglMakeCurrent>(renderer->eglLibrary, "eglMakeCurrent");
    renderer->egl.queryString = symbol<EglQueryString>(renderer->eglLibrary, "eglQueryString");

    renderer->gbm.createDevice = symbol<GbmCreateDevice>(renderer->gbmLibrary, "gbm_create_device");
    renderer->gbm.destroyDevice = symbol<GbmDestroyDevice>(renderer->gbmLibrary, "gbm_device_destroy");
    renderer->gbm.createBo = symbol<GbmCreateBo>(renderer->gbmLibrary, "gbm_bo_create");
    renderer->gbm.createBoWithModifiers =
        symbol<GbmCreateBoWithModifiers>(renderer->gbmLibrary, "gbm_bo_create_with_modifiers");
    renderer->gbm.createBoWithModifiers2 =
        symbol<GbmCreateBoWithModifiers2>(renderer->gbmLibrary, "gbm_bo_create_with_modifiers2");
    renderer->gbm.destroyBo = symbol<GbmDestroyBo>(renderer->gbmLibrary, "gbm_bo_destroy");
    renderer->gbm.getFd = symbol<GbmBoGetFd>(renderer->gbmLibrary, "gbm_bo_get_fd");
    renderer->gbm.getStride = symbol<GbmBoGetStride>(renderer->gbmLibrary, "gbm_bo_get_stride");
    renderer->gbm.getOffset = symbol<GbmBoGetOffset>(renderer->gbmLibrary, "gbm_bo_get_offset");
    renderer->gbm.getModifier = symbol<GbmBoGetModifier>(renderer->gbmLibrary, "gbm_bo_get_modifier");

    return renderer->egl.getPlatformDisplay && renderer->egl.initialize && renderer->egl.terminate &&
        renderer->egl.bindApi && renderer->egl.chooseConfig && renderer->egl.createContext &&
        renderer->egl.destroyContext && renderer->egl.makeCurrent && renderer->egl.queryString &&
        renderer->gbm.createDevice && renderer->gbm.destroyDevice && renderer->gbm.createBo &&
        renderer->gbm.destroyBo && renderer->gbm.getFd && renderer->gbm.getStride;
}

static bool createGraphicsContext(Renderer* renderer) {
    renderer->drmFd = open(renderer->renderNode.c_str(), O_RDWR | O_CLOEXEC);
    if (renderer->drmFd < 0) {
        renderer->failure = "The active EGL render node could not be opened";
        return false;
    }
    renderer->gbmDevice = renderer->gbm.createDevice(renderer->drmFd);
    if (!renderer->gbmDevice) {
        renderer->failure = "GBM rejected the active EGL render node";
        return false;
    }
    renderer->display =
        renderer->egl.getPlatformDisplay(EGL_PLATFORM_GBM_KHR, renderer->gbmDevice, nullptr);
    if (renderer->display == EGL_NO_DISPLAY_VALUE) return false;
    EGLint major = 0;
    EGLint minor = 0;
    if (renderer->egl.initialize(renderer->display, &major, &minor) != EGL_TRUE_VALUE ||
        renderer->egl.bindApi(EGL_OPENGL_API) != EGL_TRUE_VALUE) {
        renderer->failure = "A GBM OpenGL EGLDisplay could not be initialized";
        return false;
    }
    const char* extensions = renderer->egl.queryString(renderer->display, EGL_EXTENSIONS);
    if (!hasExtension(extensions, "EGL_EXT_image_dma_buf_import")) {
        renderer->failure = "The active EGL device cannot import DMA-BUF render targets";
        return false;
    }
    const EGLint configAttributes[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE, 8,
        EGL_NONE_VALUE,
    };
    EGLConfig config = nullptr;
    EGLint count = 0;
    if (renderer->egl.chooseConfig(renderer->display, configAttributes, &config, 1, &count) !=
            EGL_TRUE_VALUE ||
        count < 1) {
        renderer->failure = "No compatible desktop OpenGL EGLConfig is available";
        return false;
    }
    const EGLint contextAttributes[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 0,
        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT,
        EGL_NONE_VALUE,
    };
    renderer->context = renderer->egl.createContext(
        renderer->display, config, EGL_NO_CONTEXT_VALUE, contextAttributes);
    if (renderer->context == EGL_NO_CONTEXT_VALUE || !bindContext(renderer)) {
        renderer->failure = "The GBM OpenGL context could not be created";
        return false;
    }

    renderer->egl.createImage = reinterpret_cast<EglCreateImage>(resolveGl(renderer, "eglCreateImageKHR"));
    renderer->egl.destroyImage = reinterpret_cast<EglDestroyImage>(resolveGl(renderer, "eglDestroyImageKHR"));
    renderer->egl.createSync = reinterpret_cast<EglCreateSync>(resolveGl(renderer, "eglCreateSyncKHR"));
    renderer->egl.destroySync = reinterpret_cast<EglDestroySync>(resolveGl(renderer, "eglDestroySyncKHR"));
    renderer->egl.dupFenceFd =
        reinterpret_cast<EglDupFenceFd>(resolveGl(renderer, "eglDupNativeFenceFDANDROID"));
    renderer->gl.imageTargetTexture =
        reinterpret_cast<GlEglImageTargetTexture>(resolveGl(renderer, "glEGLImageTargetTexture2DOES"));
    renderer->gl.genTextures = reinterpret_cast<GlGenTextures>(resolveGl(renderer, "glGenTextures"));
    renderer->gl.deleteTextures = reinterpret_cast<GlDeleteTextures>(resolveGl(renderer, "glDeleteTextures"));
    renderer->gl.bindTexture = reinterpret_cast<GlBindTexture>(resolveGl(renderer, "glBindTexture"));
    renderer->gl.texParameteri = reinterpret_cast<GlTexParameteri>(resolveGl(renderer, "glTexParameteri"));
    renderer->gl.genFramebuffers =
        reinterpret_cast<GlGenFramebuffers>(resolveGl(renderer, "glGenFramebuffers"));
    renderer->gl.deleteFramebuffers =
        reinterpret_cast<GlDeleteFramebuffers>(resolveGl(renderer, "glDeleteFramebuffers"));
    renderer->gl.bindFramebuffer =
        reinterpret_cast<GlBindFramebuffer>(resolveGl(renderer, "glBindFramebuffer"));
    renderer->gl.framebufferTexture2D =
        reinterpret_cast<GlFramebufferTexture2D>(resolveGl(renderer, "glFramebufferTexture2D"));
    renderer->gl.checkFramebufferStatus =
        reinterpret_cast<GlCheckFramebufferStatus>(resolveGl(renderer, "glCheckFramebufferStatus"));
    renderer->gl.flush = reinterpret_cast<GlFlush>(resolveGl(renderer, "glFlush"));
    renderer->gl.finish = reinterpret_cast<GlFinish>(resolveGl(renderer, "glFinish"));
    const bool complete = renderer->egl.createImage && renderer->egl.destroyImage &&
        renderer->gl.imageTargetTexture && renderer->gl.genTextures && renderer->gl.deleteTextures &&
        renderer->gl.bindTexture && renderer->gl.texParameteri && renderer->gl.genFramebuffers &&
        renderer->gl.deleteFramebuffers && renderer->gl.bindFramebuffer &&
        renderer->gl.framebufferTexture2D && renderer->gl.checkFramebufferStatus &&
        renderer->gl.flush && renderer->gl.finish;
    if (!complete) renderer->failure = "Required EGLImage/OpenGL entry points are unavailable";
    unbindContext(renderer);
    return complete;
}

static void updateCallback(void* context) {
    Renderer* renderer = static_cast<Renderer*>(context);
    if (renderer && !renderer->shuttingDown.load(std::memory_order_acquire)) {
        renderer->updatePending.store(true, std::memory_order_release);
    }
}

static bool createMpvRenderContext(Renderer* renderer) {
    if (!bindContext(renderer)) return false;
    const char* apiType = "opengl";
    int advancedControl = 1;
    mpv_opengl_init_params openGl{resolveMpvGl, renderer};
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char*>(apiType)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &openGl},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advancedControl},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const bool created =
        renderer->mpvApi.create(&renderer->renderContext, renderer->mpv, parameters) >= 0 &&
        renderer->renderContext;
    if (created) renderer->mpvApi.setUpdateCallback(renderer->renderContext, updateCallback, renderer);
    unbindContext(renderer);
    if (!created) renderer->failure = "libmpv rejected the GBM OpenGL render context";
    return created;
}

static void* allocateBo(Renderer* renderer, int width, int height, uint32_t fourcc) {
    std::vector<uint64_t> explicitModifiers;
    bool permitsImplicit = renderer->allowedModifiers.empty();
    for (uint64_t modifier : renderer->allowedModifiers) {
        if (modifier == DRM_FORMAT_MOD_INVALID) permitsImplicit = true;
        else explicitModifiers.push_back(modifier);
    }
    void* bo = nullptr;
    if (!explicitModifiers.empty() && renderer->gbm.createBoWithModifiers2) {
        bo = renderer->gbm.createBoWithModifiers2(
            renderer->gbmDevice,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            fourcc,
            explicitModifiers.data(),
            static_cast<unsigned>(explicitModifiers.size()),
            GBM_BO_USE_RENDERING);
    }
    if (!bo && !explicitModifiers.empty() && renderer->gbm.createBoWithModifiers) {
        bo = renderer->gbm.createBoWithModifiers(
            renderer->gbmDevice,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            fourcc,
            explicitModifiers.data(),
            static_cast<unsigned>(explicitModifiers.size()));
    }
    if (!bo && permitsImplicit) {
        bo = renderer->gbm.createBo(
            renderer->gbmDevice,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            fourcc,
            GBM_BO_USE_RENDERING);
    }
    return bo;
}

static bool configureSlot(
    Renderer* renderer,
    TextureSlot& slot,
    int width,
    int height,
    uint32_t fourcc,
    uint64_t generation) {
    destroySlot(renderer, slot);
    slot.bo = allocateBo(renderer, width, height, fourcc);
    if (!slot.bo) return false;
    const int imageFd = renderer->gbm.getFd(slot.bo);
    if (imageFd < 0) {
        destroySlot(renderer, slot);
        return false;
    }
    slot.stride = static_cast<int>(renderer->gbm.getStride(slot.bo));
    slot.offset = renderer->gbm.getOffset ? static_cast<int>(renderer->gbm.getOffset(slot.bo, 0)) : 0;
    slot.modifier = renderer->gbm.getModifier ? renderer->gbm.getModifier(slot.bo) : DRM_FORMAT_MOD_INVALID;
    EGLint attributes[20];
    int index = 0;
    attributes[index++] = EGL_WIDTH;
    attributes[index++] = width;
    attributes[index++] = EGL_HEIGHT;
    attributes[index++] = height;
    attributes[index++] = EGL_LINUX_DRM_FOURCC_EXT;
    attributes[index++] = static_cast<EGLint>(fourcc);
    attributes[index++] = EGL_DMA_BUF_PLANE0_FD_EXT;
    attributes[index++] = imageFd;
    attributes[index++] = EGL_DMA_BUF_PLANE0_OFFSET_EXT;
    attributes[index++] = slot.offset;
    attributes[index++] = EGL_DMA_BUF_PLANE0_PITCH_EXT;
    attributes[index++] = slot.stride;
    if (slot.modifier != DRM_FORMAT_MOD_INVALID) {
        attributes[index++] = EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT;
        attributes[index++] = static_cast<EGLint>(slot.modifier & 0xFFFFFFFFULL);
        attributes[index++] = EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT;
        attributes[index++] = static_cast<EGLint>(slot.modifier >> 32);
    }
    attributes[index++] = EGL_IMAGE_PRESERVED_KHR;
    attributes[index++] = EGL_TRUE_VALUE;
    attributes[index++] = EGL_NONE_VALUE;
    slot.image = renderer->egl.createImage(
        renderer->display,
        EGL_NO_CONTEXT_VALUE,
        EGL_LINUX_DMA_BUF_EXT,
        nullptr,
        attributes);
    close(imageFd);
    if (slot.image == EGL_NO_IMAGE_VALUE) {
        destroySlot(renderer, slot);
        return false;
    }
    renderer->gl.genTextures(1, &slot.texture);
    renderer->gl.bindTexture(GL_TEXTURE_2D, slot.texture);
    renderer->gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    renderer->gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    renderer->gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    renderer->gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    renderer->gl.imageTargetTexture(GL_TEXTURE_2D, slot.image);
    renderer->gl.genFramebuffers(1, &slot.framebuffer);
    renderer->gl.bindFramebuffer(GL_FRAMEBUFFER, slot.framebuffer);
    renderer->gl.framebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, slot.texture, 0);
    const bool complete =
        renderer->gl.checkFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    renderer->gl.bindFramebuffer(GL_FRAMEBUFFER, 0);
    renderer->gl.bindTexture(GL_TEXTURE_2D, 0);
    if (!complete) {
        destroySlot(renderer, slot);
        return false;
    }
    slot.width = width;
    slot.height = height;
    slot.fourcc = fourcc;
    slot.generation = generation;
    return true;
}

static int reapReleasedSlotsLocked(Renderer* renderer, int timeoutMs) {
    int releasedReferences = 0;
    for (TextureSlot& slot : renderer->slots) {
        if (!slot.inUse || slot.releaseFenceFd < 0) continue;
        if (!fenceSignalled(slot.releaseFenceFd, timeoutMs)) continue;
        close(slot.releaseFenceFd);
        slot.releaseFenceFd = -1;
        slot.inUse = false;
        slot.exported = false;
        releasedReferences++;
    }
    return releasedReferences;
}

static int acquireSlotLocked(Renderer* renderer, int width, int height, uint32_t fourcc) {
    if (renderer->outputWidth != width || renderer->outputHeight != height ||
        renderer->outputFourcc != fourcc) {
        renderer->outputWidth = width;
        renderer->outputHeight = height;
        renderer->outputFourcc = fourcc;
        renderer->outputSlot = -1;
        ++renderer->outputGeneration;
    }
    for (size_t index = 0; index < renderer->slots.size(); ++index) {
        TextureSlot& slot = renderer->slots[index];
        if (slot.inUse) continue;
        if (slot.width != width || slot.height != height || slot.fourcc != fourcc ||
            slot.generation != renderer->outputGeneration) {
            if (!configureSlot(renderer, slot, width, height, fourcc, renderer->outputGeneration)) {
                continue;
            }
        }
        return static_cast<int>(index);
    }
    return -1;
}

static int createAcquireFence(Renderer* renderer) {
    const char* extensions = renderer->egl.queryString(renderer->display, EGL_EXTENSIONS);
    if (!renderer->egl.createSync || !renderer->egl.destroySync || !renderer->egl.dupFenceFd ||
        !hasExtension(extensions, "EGL_ANDROID_native_fence_sync")) {
        renderer->gl.finish();
        return -1;
    }
    const EGLint attributes[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
        EGL_NO_NATIVE_FENCE_FD_ANDROID,
        EGL_NONE_VALUE,
    };
    EGLSyncKHR sync =
        renderer->egl.createSync(renderer->display, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (sync == EGL_NO_SYNC_VALUE) {
        renderer->gl.finish();
        return -1;
    }
    renderer->gl.flush();
    const int fd = renderer->egl.dupFenceFd(renderer->display, sync);
    renderer->egl.destroySync(renderer->display, sync);
    if (fd < 0) renderer->gl.finish();
    return fd;
}

static void beginShutdown(Renderer* renderer) {
    if (!renderer || renderer->shuttingDown.exchange(true, std::memory_order_acq_rel)) return;
    int releasedReferences = 0;
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        if (renderer->context != EGL_NO_CONTEXT_VALUE && bindContext(renderer)) {
            if (renderer->renderContext) {
                renderer->mpvApi.setUpdateCallback(renderer->renderContext, nullptr, nullptr);
                renderer->mpvApi.free(renderer->renderContext);
                renderer->renderContext = nullptr;
            }
            unbindContext(renderer);
        }
        releasedReferences = reapReleasedSlotsLocked(renderer, -1);
    }
    while (releasedReferences-- > 0) releaseRenderer(renderer);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nCreateRenderer(
    JNIEnv* environment,
    jclass,
    jlong rawMpvHandle,
    jstring libraryLoadName,
    jstring renderNode,
    jint fourcc,
    jlongArray modifiers,
    jboolean extendedLinear) {
    if (!environment || rawMpvHandle == 0 || !libraryLoadName || !renderNode) return 0;
    const char* libraryName = environment->GetStringUTFChars(libraryLoadName, nullptr);
    const char* node = environment->GetStringUTFChars(renderNode, nullptr);
    if (!libraryName || !node) {
        if (libraryName) environment->ReleaseStringUTFChars(libraryLoadName, libraryName);
        if (node) environment->ReleaseStringUTFChars(renderNode, node);
        return 0;
    }
    Renderer* renderer = new (std::nothrow) Renderer();
    if (!renderer) {
        environment->ReleaseStringUTFChars(libraryLoadName, libraryName);
        environment->ReleaseStringUTFChars(renderNode, node);
        return 0;
    }
    renderer->mpv = reinterpret_cast<mpv_handle*>(static_cast<uintptr_t>(rawMpvHandle));
    renderer->renderNode = node;
    renderer->desiredFourcc = static_cast<uint32_t>(fourcc);
    renderer->extendedLinear = extendedLinear == JNI_TRUE;
    if (modifiers) {
        const jsize count = environment->GetArrayLength(modifiers);
        std::vector<jlong> values(static_cast<size_t>(count));
        if (count > 0) environment->GetLongArrayRegion(modifiers, 0, count, values.data());
        for (jlong value : values) renderer->allowedModifiers.push_back(static_cast<uint64_t>(value));
    }
    const bool ready =
        (renderer->desiredFourcc == DRM_FORMAT_ARGB8888 ||
         renderer->desiredFourcc == DRM_FORMAT_ABGR16161616F) &&
        loadMpv(renderer, libraryName) && loadGraphicsApis(renderer) &&
        createGraphicsContext(renderer) && createMpvRenderContext(renderer);
    environment->ReleaseStringUTFChars(libraryLoadName, libraryName);
    environment->ReleaseStringUTFChars(renderNode, node);
    if (!ready) {
        beginShutdown(renderer);
        releaseRenderer(renderer);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(renderer));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nRenderFrame(
    JNIEnv*, jclass, jlong nativeRenderer, jint width, jint height) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || width <= 0 || height <= 0 ||
        renderer->shuttingDown.load(std::memory_order_acquire)) {
        return -1;
    }
    int releasedReferences = 0;
    jlong result = -1;
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        if (!renderer->renderContext || !bindContext(renderer)) return -1;
        releasedReferences = reapReleasedSlotsLocked(renderer, 0);
        const bool update = renderer->updatePending.exchange(false, std::memory_order_acq_rel);
        const bool redraw = renderer->redrawRequested.exchange(false, std::memory_order_acq_rel);
        const uint64_t updates = update ? renderer->mpvApi.update(renderer->renderContext) : 0;
        if (!redraw && (updates & MPV_RENDER_UPDATE_FRAME) == 0 && renderer->outputSerial > 0) {
            result = static_cast<jlong>(renderer->outputSerial);
            unbindContext(renderer);
        } else {
            const int slotIndex =
                acquireSlotLocked(renderer, width, height, renderer->desiredFourcc);
            if (slotIndex < 0) {
                renderer->updatePending.store(true, std::memory_order_release);
                result = static_cast<jlong>(renderer->outputSerial);
                unbindContext(renderer);
            } else {
                TextureSlot& slot = renderer->slots[static_cast<size_t>(slotIndex)];
                int depth = renderer->desiredFourcc == DRM_FORMAT_ABGR16161616F ? 16 : 8;
                mpv_opengl_fbo target{
                    static_cast<int>(slot.framebuffer),
                    width,
                    height,
                    static_cast<int>(depth > 8 ? GL_RGBA16F : GL_RGBA8),
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
                if (renderer->mpvApi.render(renderer->renderContext, parameters) < 0) {
                    renderer->failure = "libmpv failed to render into the DMA-BUF FBO";
                    result = -1;
                    unbindContext(renderer);
                } else {
                    slot.acquireFenceFd = createAcquireFence(renderer);
                    slot.inUse = true;
                    slot.exported = false;
                    slot.releaseFenceFd = -1;
                    slot.serial = ++renderer->outputSerial;
                    slot.generation = renderer->outputGeneration;
                    renderer->outputSlot = slotIndex;
                    retainRenderer(renderer);
                    result = static_cast<jlong>(slot.serial);
                    unbindContext(renderer);
                }
            }
        }
    }
    while (releasedReferences-- > 0) releaseRenderer(renderer);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nAcquireTextureFrame(
    JNIEnv* environment, jclass, jlong nativeRenderer, jlong serial) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!environment || !renderer || serial <= 0 ||
        renderer->shuttingDown.load(std::memory_order_acquire)) {
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(renderer->lock);
    for (TextureSlot& slot : renderer->slots) {
        if (!slot.inUse || slot.exported || slot.serial != static_cast<uint64_t>(serial)) continue;
        const int frameFd = renderer->gbm.getFd(slot.bo);
        if (frameFd < 0) return nullptr;
        const int acquireFence = slot.acquireFenceFd;
        const jlong values[] = {
            frameFd,
            slot.width,
            slot.height,
            slot.stride,
            static_cast<jlong>(slot.fourcc),
            slot.offset,
            static_cast<jlong>(slot.modifier),
            static_cast<jlong>(slot.generation),
            static_cast<jlong>(slot.serial),
            acquireFence,
            renderer->extendedLinear ? 1 : 0,
        };
        jlongArray result = environment->NewLongArray(11);
        if (!result) {
            close(frameFd);
            return nullptr;
        }
        slot.acquireFenceFd = -1;
        slot.exported = true;
        environment->SetLongArrayRegion(result, 0, 11, values);
        return result;
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nDiscardTextureFrame(
    JNIEnv*, jclass, jlong nativeRenderer, jlong serial) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || serial <= 0) return;
    bool releasedReference = false;
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        for (TextureSlot& slot : renderer->slots) {
            if (!slot.inUse || slot.exported || slot.serial != static_cast<uint64_t>(serial)) continue;
            if (slot.acquireFenceFd >= 0) {
                fenceSignalled(slot.acquireFenceFd, -1);
                close(slot.acquireFenceFd);
                slot.acquireFenceFd = -1;
            }
            slot.inUse = false;
            releasedReference = true;
            break;
        }
    }
    if (releasedReference) releaseRenderer(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nReleaseTextureFrame(
    JNIEnv*,
    jclass,
    jlong nativeRenderer,
    jlong generation,
    jlong serial,
    jint frameFd,
    jint releaseFenceFd) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (frameFd >= 0) close(frameFd);
    if (!renderer || generation <= 0 || serial <= 0) {
        if (releaseFenceFd >= 0) close(releaseFenceFd);
        return;
    }
    bool releasedReference = false;
    {
        std::lock_guard<std::mutex> guard(renderer->lock);
        for (TextureSlot& slot : renderer->slots) {
            if (!slot.inUse || slot.generation != static_cast<uint64_t>(generation) ||
                slot.serial != static_cast<uint64_t>(serial)) {
                continue;
            }
            slot.exported = false;
            if (releaseFenceFd < 0 ||
                (renderer->shuttingDown.load(std::memory_order_acquire) &&
                 fenceSignalled(releaseFenceFd, -1)) ||
                fenceSignalled(releaseFenceFd, 0)) {
                if (releaseFenceFd >= 0) close(releaseFenceFd);
                slot.inUse = false;
                releasedReference = true;
            } else {
                slot.releaseFenceFd = releaseFenceFd;
            }
            releaseFenceFd = -1;
            break;
        }
    }
    if (releaseFenceFd >= 0) close(releaseFenceFd);
    if (releasedReference) releaseRenderer(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nReportPresented(
    JNIEnv*, jclass, jlong nativeRenderer, jlong serial) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer || serial <= 0 || renderer->shuttingDown.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    const uint64_t value = static_cast<uint64_t>(serial);
    if (renderer->renderContext && value <= renderer->outputSerial && value > renderer->reportedSerial) {
        renderer->mpvApi.reportSwap(renderer->renderContext);
        renderer->reportedSerial = value;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nGetFailure(
    JNIEnv* environment, jclass, jlong nativeRenderer) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!environment || !renderer) return nullptr;
    std::lock_guard<std::mutex> guard(renderer->lock);
    return renderer->failure.empty() ? nullptr : environment->NewStringUTF(renderer->failure.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvLinuxTextureBridge_nDetach(
    JNIEnv*, jclass, jlong nativeRenderer) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<uintptr_t>(nativeRenderer));
    if (!renderer) return;
    beginShutdown(renderer);
    releaseRenderer(renderer);
}
