#include <jni.h>

#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <wrl/client.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace {

using EGLDisplay = void*;
using EGLConfig = void*;
using EGLContext = void*;
using EGLSurface = void*;
using EGLClientBuffer = void*;
using EGLDeviceEXT = void*;
using EGLBoolean = unsigned int;
using EGLenum = unsigned int;
using EGLint = int;
using EGLAttrib = intptr_t;

constexpr EGLDisplay EGL_NO_DISPLAY_VALUE = nullptr;
constexpr EGLContext EGL_NO_CONTEXT_VALUE = nullptr;
constexpr EGLSurface EGL_NO_SURFACE_VALUE = nullptr;
constexpr EGLint EGL_NONE_VALUE = 0x3038;
constexpr EGLint EGL_SURFACE_TYPE_VALUE = 0x3033;
constexpr EGLint EGL_PBUFFER_BIT_VALUE = 0x0001;
constexpr EGLint EGL_RENDERABLE_TYPE_VALUE = 0x3040;
constexpr EGLint EGL_OPENGL_ES3_BIT_VALUE = 0x0040;
constexpr EGLint EGL_RED_SIZE_VALUE = 0x3024;
constexpr EGLint EGL_GREEN_SIZE_VALUE = 0x3023;
constexpr EGLint EGL_BLUE_SIZE_VALUE = 0x3022;
constexpr EGLint EGL_ALPHA_SIZE_VALUE = 0x3021;
constexpr EGLint EGL_COLOR_COMPONENT_TYPE_EXT_VALUE = 0x3339;
constexpr EGLint EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT_VALUE = 0x333B;
constexpr EGLint EGL_CONTEXT_CLIENT_VERSION_VALUE = 0x3098;
constexpr EGLint EGL_TEXTURE_FORMAT_VALUE = 0x3080;
constexpr EGLint EGL_TEXTURE_RGBA_VALUE = 0x305E;
constexpr EGLint EGL_TEXTURE_TARGET_VALUE = 0x3081;
constexpr EGLint EGL_TEXTURE_2D_VALUE = 0x305F;
constexpr EGLint EGL_WIDTH_VALUE = 0x3057;
constexpr EGLint EGL_HEIGHT_VALUE = 0x3056;
constexpr EGLenum EGL_OPENGL_ES_API_VALUE = 0x30A0;
constexpr EGLenum EGL_PLATFORM_ANGLE_ANGLE_VALUE = 0x3202;
constexpr EGLint EGL_PLATFORM_ANGLE_TYPE_ANGLE_VALUE = 0x3203;
constexpr EGLint EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE_VALUE = 0x3208;
constexpr EGLint EGL_PLATFORM_ANGLE_D3D_LUID_HIGH_ANGLE_VALUE = 0x34A0;
constexpr EGLint EGL_PLATFORM_ANGLE_D3D_LUID_LOW_ANGLE_VALUE = 0x34A1;
constexpr EGLenum EGL_D3D_TEXTURE_ANGLE_VALUE = 0x33A3;
constexpr EGLint EGL_DEVICE_EXT_VALUE = 0x322C;
constexpr EGLint EGL_D3D11_DEVICE_ANGLE_VALUE = 0x33A1;
constexpr int GL_RGBA8_VALUE = 0x8058;
constexpr int GL_RGBA16F_VALUE = 0x881A;
constexpr size_t TEXTURE_POOL_SIZE = 4;
constexpr size_t RETAINED_POOL_GENERATIONS = 2;

using EglGetProcAddress = void* (__stdcall*)(const char*);
using EglGetPlatformDisplay = EGLDisplay (__stdcall*)(EGLenum, void*, const EGLint*);
using EglInitialize = EGLBoolean (__stdcall*)(EGLDisplay, EGLint*, EGLint*);
using EglTerminate = EGLBoolean (__stdcall*)(EGLDisplay);
using EglBindApi = EGLBoolean (__stdcall*)(EGLenum);
using EglChooseConfig = EGLBoolean (__stdcall*)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
using EglCreateContext = EGLContext (__stdcall*)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
using EglDestroyContext = EGLBoolean (__stdcall*)(EGLDisplay, EGLContext);
using EglCreatePbufferSurface = EGLSurface (__stdcall*)(EGLDisplay, EGLConfig, const EGLint*);
using EglCreatePbufferFromClientBuffer = EGLSurface (__stdcall*)(
    EGLDisplay, EGLenum, EGLClientBuffer, EGLConfig, const EGLint*);
using EglDestroySurface = EGLBoolean (__stdcall*)(EGLDisplay, EGLSurface);
using EglMakeCurrent = EGLBoolean (__stdcall*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
using EglGetCurrentContext = EGLContext (__stdcall*)();
using EglQueryDisplayAttrib = EGLBoolean (__stdcall*)(EGLDisplay, EGLint, EGLAttrib*);
using EglQueryDeviceAttrib = EGLBoolean (__stdcall*)(EGLDeviceEXT, EGLint, EGLAttrib*);
using GlFinish = void (__stdcall*)();
using GlFlush = void (__stdcall*)();
using GlViewport = void (__stdcall*)(int, int, int, int);

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
};

enum {
    MPV_RENDER_UPDATE_FRAME = 1 << 0,
};

using MpvCreate = int (*)(mpv_render_context**, mpv_handle*, mpv_render_param*);
using MpvSetUpdateCallback = void (*)(mpv_render_context*, void (*)(void*), void*);
using MpvUpdate = uint64_t (*)(mpv_render_context*);
using MpvRender = int (*)(mpv_render_context*, mpv_render_param*);
using MpvReportSwap = void (*)(mpv_render_context*);
using MpvFree = void (*)(mpv_render_context*);

struct EglApi {
    HMODULE egl = nullptr;
    HMODULE gles = nullptr;
    EglGetProcAddress getProcAddress = nullptr;
    EglGetPlatformDisplay getPlatformDisplay = nullptr;
    EglInitialize initialize = nullptr;
    EglTerminate terminate = nullptr;
    EglBindApi bindApi = nullptr;
    EglChooseConfig chooseConfig = nullptr;
    EglCreateContext createContext = nullptr;
    EglDestroyContext destroyContext = nullptr;
    EglCreatePbufferSurface createPbufferSurface = nullptr;
    EglCreatePbufferFromClientBuffer createPbufferFromClientBuffer = nullptr;
    EglDestroySurface destroySurface = nullptr;
    EglMakeCurrent makeCurrent = nullptr;
    EglGetCurrentContext getCurrentContext = nullptr;
    EglQueryDisplayAttrib queryDisplayAttrib = nullptr;
    EglQueryDeviceAttrib queryDeviceAttrib = nullptr;
    GlFinish finish = nullptr;
    GlFlush flush = nullptr;
    GlViewport viewport = nullptr;
};

struct MpvApi {
    HMODULE module = nullptr;
    MpvCreate create = nullptr;
    MpvSetUpdateCallback setUpdateCallback = nullptr;
    MpvUpdate update = nullptr;
    MpvRender render = nullptr;
    MpvReportSwap reportSwap = nullptr;
    MpvFree free = nullptr;
};

struct TextureTarget {
    EGLSurface pbuffer = EGL_NO_SURFACE_VALUE;
    ComPtr<ID3D11Texture2D> texture;
    ComPtr<IDXGIKeyedMutex> keyedMutex;
    HANDLE sharedHandle = nullptr;
    int width = 0;
    int height = 0;
    uint64_t generation = 0;
    uint64_t serial = 0;
};

struct Renderer {
    std::mutex lock;
    EglApi egl;
    MpvApi mpv;
    EGLDisplay display = EGL_NO_DISPLAY_VALUE;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT_VALUE;
    EGLSurface controlPbuffer = EGL_NO_SURFACE_VALUE;
    ComPtr<ID3D11Device> device;
    std::vector<std::unique_ptr<TextureTarget>> activeTargets;
    std::vector<std::unique_ptr<TextureTarget>> retiredTargets;
    TextureTarget* lastOutput = nullptr;
    size_t targetCursor = 0;
    LUID adapterLuid{};
    DXGI_FORMAT textureFormat = DXGI_FORMAT_R8G8B8A8_UNORM;
    int glInternalFormat = GL_RGBA8_VALUE;
    bool extendedLinear = false;
    mpv_render_context* renderContext = nullptr;
    int width = 0;
    int height = 0;
    uint64_t generation = 0;
    uint64_t frameSerial = 0;
    uint64_t lastReportedSerial = 0;
    DWORD renderThreadId = 0;
    std::atomic<bool> updateRequested{true};
    std::atomic<bool> redrawRequested{true};
    std::string failure;
};

template <typename T>
T moduleSymbol(HMODULE module, const char* name) {
    return reinterpret_cast<T>(module ? GetProcAddress(module, name) : nullptr);
}

template <typename T>
T eglSymbol(EglApi& api, const char* name) {
    void* symbol = moduleSymbol<void*>(api.egl, name);
    if (!symbol && api.getProcAddress) symbol = api.getProcAddress(name);
    return reinterpret_cast<T>(symbol);
}

void setFailure(Renderer* renderer, const char* message) {
    if (renderer) renderer->failure = message ? message : "Unknown MPV texture renderer failure";
}

bool isRenderThread(Renderer* renderer) {
    return renderer && renderer->renderThreadId == GetCurrentThreadId();
}

bool loadEgl(Renderer* renderer, const wchar_t* eglLibrary, const wchar_t* glesLibrary) {
    if (!renderer || !eglLibrary || !glesLibrary) return false;
    constexpr DWORD loadFlags =
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32;
    // MPV must not reuse Nucleus/Skia's process-global ANGLE modules. A second,
    // uniquely named copy keeps EGL/GL thread-local dispatch and renderer state
    // isolated; the only cross-runtime object is the keyed D3D11 texture.
    renderer->egl.gles = LoadLibraryExW(glesLibrary, nullptr, loadFlags);
    renderer->egl.egl = LoadLibraryExW(eglLibrary, nullptr, loadFlags);
    if (!renderer->egl.egl || !renderer->egl.gles) {
        setFailure(renderer, "The private MPV ANGLE runtime could not be loaded");
        return false;
    }
    EglApi& api = renderer->egl;
    api.getProcAddress = moduleSymbol<EglGetProcAddress>(api.egl, "eglGetProcAddress");
    api.getPlatformDisplay = eglSymbol<EglGetPlatformDisplay>(api, "eglGetPlatformDisplayEXT");
    api.initialize = eglSymbol<EglInitialize>(api, "eglInitialize");
    api.terminate = eglSymbol<EglTerminate>(api, "eglTerminate");
    api.bindApi = eglSymbol<EglBindApi>(api, "eglBindAPI");
    api.chooseConfig = eglSymbol<EglChooseConfig>(api, "eglChooseConfig");
    api.createContext = eglSymbol<EglCreateContext>(api, "eglCreateContext");
    api.destroyContext = eglSymbol<EglDestroyContext>(api, "eglDestroyContext");
    api.createPbufferSurface = eglSymbol<EglCreatePbufferSurface>(api, "eglCreatePbufferSurface");
    api.createPbufferFromClientBuffer =
        eglSymbol<EglCreatePbufferFromClientBuffer>(api, "eglCreatePbufferFromClientBuffer");
    api.destroySurface = eglSymbol<EglDestroySurface>(api, "eglDestroySurface");
    api.makeCurrent = eglSymbol<EglMakeCurrent>(api, "eglMakeCurrent");
    api.getCurrentContext = eglSymbol<EglGetCurrentContext>(api, "eglGetCurrentContext");
    api.queryDisplayAttrib = eglSymbol<EglQueryDisplayAttrib>(api, "eglQueryDisplayAttribEXT");
    api.queryDeviceAttrib = eglSymbol<EglQueryDeviceAttrib>(api, "eglQueryDeviceAttribEXT");
    api.finish = eglSymbol<GlFinish>(api, "glFinish");
    api.flush = eglSymbol<GlFlush>(api, "glFlush");
    api.viewport = eglSymbol<GlViewport>(api, "glViewport");
    if (!api.getPlatformDisplay || !api.initialize || !api.terminate || !api.bindApi ||
        !api.chooseConfig || !api.createContext || !api.destroyContext ||
        !api.createPbufferSurface ||
        !api.createPbufferFromClientBuffer ||
        !api.destroySurface || !api.makeCurrent ||
        !api.getCurrentContext ||
        !api.queryDisplayAttrib || !api.queryDeviceAttrib || !api.finish || !api.flush ||
        !api.viewport) {
        setFailure(renderer, "ANGLE is missing required shared-texture functions");
        return false;
    }
    return true;
}

bool loadMpv(Renderer* renderer, const wchar_t* libraryName) {
    renderer->mpv.module = LoadLibraryW(libraryName);
    if (!renderer->mpv.module) {
        setFailure(renderer, "libmpv could not be loaded by the texture renderer");
        return false;
    }
    MpvApi& api = renderer->mpv;
    api.create = moduleSymbol<MpvCreate>(api.module, "mpv_render_context_create");
    api.setUpdateCallback =
        moduleSymbol<MpvSetUpdateCallback>(api.module, "mpv_render_context_set_update_callback");
    api.update = moduleSymbol<MpvUpdate>(api.module, "mpv_render_context_update");
    api.render = moduleSymbol<MpvRender>(api.module, "mpv_render_context_render");
    api.reportSwap = moduleSymbol<MpvReportSwap>(api.module, "mpv_render_context_report_swap");
    api.free = moduleSymbol<MpvFree>(api.module, "mpv_render_context_free");
    if (!api.create || !api.setUpdateCallback || !api.update || !api.render ||
        !api.reportSwap || !api.free) {
        setFailure(renderer, "libmpv is missing OpenGL render API symbols");
        return false;
    }
    return true;
}

void* getGlProc(void* context, const char* name) {
    Renderer* renderer = static_cast<Renderer*>(context);
    if (!renderer || !name) return nullptr;
    void* result = renderer->egl.getProcAddress ? renderer->egl.getProcAddress(name) : nullptr;
    if (!result) result = moduleSymbol<void*>(renderer->egl.gles, name);
    return result;
}

void onMpvUpdate(void* context) {
    Renderer* renderer = static_cast<Renderer*>(context);
    if (renderer) renderer->updateRequested.store(true, std::memory_order_release);
}

bool makeCurrent(Renderer* renderer, EGLSurface surface) {
    return renderer && renderer->egl.makeCurrent &&
        renderer->display != EGL_NO_DISPLAY_VALUE &&
        renderer->context != EGL_NO_CONTEXT_VALUE &&
        surface != EGL_NO_SURFACE_VALUE &&
        renderer->egl.makeCurrent(renderer->display, surface, surface, renderer->context);
}

bool releaseCurrent(Renderer* renderer) {
    return renderer && renderer->egl.makeCurrent &&
        renderer->display != EGL_NO_DISPLAY_VALUE &&
        renderer->egl.makeCurrent(
            renderer->display,
            EGL_NO_SURFACE_VALUE,
            EGL_NO_SURFACE_VALUE,
            EGL_NO_CONTEXT_VALUE);
}

void destroyTarget(Renderer* renderer, std::unique_ptr<TextureTarget>& target) {
    if (!renderer || !target) return;
    if (target->pbuffer != EGL_NO_SURFACE_VALUE && renderer->egl.destroySurface) {
        renderer->egl.destroySurface(renderer->display, target->pbuffer);
    }
    target->pbuffer = EGL_NO_SURFACE_VALUE;
    target->keyedMutex.Reset();
    target->texture.Reset();
    target->sharedHandle = nullptr;
    target.reset();
}

void destroyTargets(Renderer* renderer, std::vector<std::unique_ptr<TextureTarget>>& targets) {
    if (!renderer) return;
    if (renderer->egl.getCurrentContext &&
        renderer->egl.getCurrentContext() == renderer->context) {
        if (renderer->controlPbuffer != EGL_NO_SURFACE_VALUE) {
            makeCurrent(renderer, renderer->controlPbuffer);
        } else {
            releaseCurrent(renderer);
        }
    }
    for (auto& target : targets) destroyTarget(renderer, target);
    targets.clear();
}

std::unique_ptr<TextureTarget> createTarget(
    Renderer* renderer,
    int width,
    int height,
    uint64_t generation) {
    if (!renderer || width <= 0 || height <= 0) return nullptr;
    auto target = std::make_unique<TextureTarget>();
    D3D11_TEXTURE2D_DESC description{};
    description.Width = static_cast<UINT>(width);
    description.Height = static_cast<UINT>(height);
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = renderer->textureFormat;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    description.MiscFlags = D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;
    HRESULT hr = renderer->device->CreateTexture2D(&description, nullptr, target->texture.GetAddressOf());
    if (SUCCEEDED(hr)) hr = target->texture.As(&target->keyedMutex);
    ComPtr<IDXGIResource> sharedResource;
    if (SUCCEEDED(hr)) hr = target->texture.As(&sharedResource);
    if (SUCCEEDED(hr)) hr = sharedResource->GetSharedHandle(&target->sharedHandle);
    if (FAILED(hr) || !target->sharedHandle) {
        setFailure(renderer, "D3D11 could not create the shared MPV video texture");
        return nullptr;
    }

    const EGLint pbufferAttributes[] = {
        EGL_TEXTURE_FORMAT_VALUE, EGL_TEXTURE_RGBA_VALUE,
        EGL_TEXTURE_TARGET_VALUE, EGL_TEXTURE_2D_VALUE,
        EGL_NONE_VALUE,
    };
    target->pbuffer = renderer->egl.createPbufferFromClientBuffer(
        renderer->display,
        EGL_D3D_TEXTURE_ANGLE_VALUE,
        static_cast<EGLClientBuffer>(target->texture.Get()),
        renderer->config,
        pbufferAttributes);
    if (target->pbuffer == EGL_NO_SURFACE_VALUE) {
        setFailure(renderer, "ANGLE rejected the shared MPV video pbuffer");
        return nullptr;
    }
    target->width = width;
    target->height = height;
    target->generation = generation;
    return target;
}

bool ensureTargets(Renderer* renderer, int width, int height) {
    if (!renderer || width <= 0 || height <= 0) return false;
    if (renderer->activeTargets.size() == TEXTURE_POOL_SIZE &&
        renderer->width == width && renderer->height == height) {
        return true;
    }
    if (renderer->controlPbuffer != EGL_NO_SURFACE_VALUE) {
        makeCurrent(renderer, renderer->controlPbuffer);
    }
    for (auto& target : renderer->activeTargets) {
        renderer->retiredTargets.push_back(std::move(target));
    }
    renderer->activeTargets.clear();
    while (renderer->retiredTargets.size() > TEXTURE_POOL_SIZE * RETAINED_POOL_GENERATIONS) {
        destroyTarget(renderer, renderer->retiredTargets.front());
        renderer->retiredTargets.erase(renderer->retiredTargets.begin());
    }

    const uint64_t generation = ++renderer->generation;
    for (size_t index = 0; index < TEXTURE_POOL_SIZE; ++index) {
        auto target = createTarget(renderer, width, height, generation);
        if (!target) {
            destroyTargets(renderer, renderer->activeTargets);
            return false;
        }
        renderer->activeTargets.push_back(std::move(target));
    }
    renderer->width = width;
    renderer->height = height;
    renderer->targetCursor = 0;
    renderer->lastOutput = nullptr;
    renderer->updateRequested.store(true, std::memory_order_release);
    renderer->redrawRequested.store(true, std::memory_order_release);
    return true;
}

bool initializeEgl(
    Renderer* renderer,
    uint64_t requestedAdapterLuid,
    bool extendedLinear,
    int initialWidth,
    int initialHeight,
    const wchar_t* eglLibrary,
    const wchar_t* glesLibrary) {
    if (!loadEgl(renderer, eglLibrary, glesLibrary)) return false;
    EGLint displayAttributes[8] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE_VALUE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE_VALUE,
        EGL_NONE_VALUE, EGL_NONE_VALUE, EGL_NONE_VALUE, EGL_NONE_VALUE,
        EGL_NONE_VALUE, EGL_NONE_VALUE,
    };
    if (requestedAdapterLuid != 0) {
        displayAttributes[2] = EGL_PLATFORM_ANGLE_D3D_LUID_HIGH_ANGLE_VALUE;
        displayAttributes[3] = static_cast<EGLint>(requestedAdapterLuid >> 32u);
        displayAttributes[4] = EGL_PLATFORM_ANGLE_D3D_LUID_LOW_ANGLE_VALUE;
        displayAttributes[5] = static_cast<EGLint>(requestedAdapterLuid & 0xFFFFFFFFu);
        displayAttributes[6] = EGL_NONE_VALUE;
    }
    renderer->display = renderer->egl.getPlatformDisplay(
        EGL_PLATFORM_ANGLE_ANGLE_VALUE, nullptr, displayAttributes);
    EGLint major = 0;
    EGLint minor = 0;
    if (renderer->display == EGL_NO_DISPLAY_VALUE ||
        !renderer->egl.initialize(renderer->display, &major, &minor) ||
        !renderer->egl.bindApi(EGL_OPENGL_ES_API_VALUE)) {
        setFailure(renderer, "ANGLE D3D11 display initialization failed");
        return false;
    }

    const EGLint fp16ConfigAttributes[] = {
        EGL_SURFACE_TYPE_VALUE, EGL_PBUFFER_BIT_VALUE,
        EGL_RENDERABLE_TYPE_VALUE, EGL_OPENGL_ES3_BIT_VALUE,
        EGL_RED_SIZE_VALUE, 16,
        EGL_GREEN_SIZE_VALUE, 16,
        EGL_BLUE_SIZE_VALUE, 16,
        EGL_ALPHA_SIZE_VALUE, 16,
        EGL_COLOR_COMPONENT_TYPE_EXT_VALUE, EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT_VALUE,
        EGL_NONE_VALUE,
    };
    const EGLint rgba8ConfigAttributes[] = {
        EGL_SURFACE_TYPE_VALUE, EGL_PBUFFER_BIT_VALUE,
        EGL_RENDERABLE_TYPE_VALUE, EGL_OPENGL_ES3_BIT_VALUE,
        EGL_RED_SIZE_VALUE, 8,
        EGL_GREEN_SIZE_VALUE, 8,
        EGL_BLUE_SIZE_VALUE, 8,
        EGL_ALPHA_SIZE_VALUE, 8,
        EGL_NONE_VALUE,
    };
    const EGLint* configAttributes =
        extendedLinear ? fp16ConfigAttributes : rgba8ConfigAttributes;
    EGLint configCount = 0;
    if (!renderer->egl.chooseConfig(
            renderer->display, configAttributes, &renderer->config, 1, &configCount) ||
        configCount < 1) {
        setFailure(
            renderer,
            extendedLinear
                ? "ANGLE exposes no RGBA16F pbuffer configuration"
                : "ANGLE exposes no RGBA8 pbuffer configuration");
        return false;
    }
    renderer->extendedLinear = extendedLinear;
    renderer->textureFormat =
        extendedLinear ? DXGI_FORMAT_R16G16B16A16_FLOAT : DXGI_FORMAT_R8G8B8A8_UNORM;
    renderer->glInternalFormat = extendedLinear ? GL_RGBA16F_VALUE : GL_RGBA8_VALUE;
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION_VALUE, 3, EGL_NONE_VALUE};
    renderer->context = renderer->egl.createContext(
        renderer->display, renderer->config, EGL_NO_CONTEXT_VALUE, contextAttributes);
    if (renderer->context == EGL_NO_CONTEXT_VALUE) {
        setFailure(renderer, "ANGLE could not create the MPV GLES3 context");
        return false;
    }
    const EGLint controlPbufferAttributes[] = {
        EGL_WIDTH_VALUE, 1,
        EGL_HEIGHT_VALUE, 1,
        EGL_NONE_VALUE,
    };
    renderer->controlPbuffer = renderer->egl.createPbufferSurface(
        renderer->display, renderer->config, controlPbufferAttributes);
    if (renderer->controlPbuffer == EGL_NO_SURFACE_VALUE) {
        setFailure(renderer, "ANGLE could not create the MPV control pbuffer");
        return false;
    }

    EGLAttrib deviceAttribute = 0;
    EGLAttrib d3dAttribute = 0;
    if (!renderer->egl.queryDisplayAttrib(
            renderer->display, EGL_DEVICE_EXT_VALUE, &deviceAttribute) ||
        !deviceAttribute ||
        !renderer->egl.queryDeviceAttrib(
            reinterpret_cast<EGLDeviceEXT>(deviceAttribute),
            EGL_D3D11_DEVICE_ANGLE_VALUE,
            &d3dAttribute) ||
        !d3dAttribute) {
        setFailure(renderer, "ANGLE did not expose its D3D11 device");
        return false;
    }
    renderer->device = reinterpret_cast<ID3D11Device*>(d3dAttribute);
    ComPtr<IDXGIDevice> dxgiDevice;
    ComPtr<IDXGIAdapter> adapter;
    DXGI_ADAPTER_DESC adapterDescription{};
    HRESULT hr = renderer->device.As(&dxgiDevice);
    if (SUCCEEDED(hr)) hr = dxgiDevice->GetAdapter(adapter.GetAddressOf());
    if (SUCCEEDED(hr)) hr = adapter->GetDesc(&adapterDescription);
    if (FAILED(hr)) {
        setFailure(renderer, "ANGLE D3D11 adapter identity could not be queried");
        return false;
    }
    renderer->adapterLuid = adapterDescription.AdapterLuid;
    const uint64_t actualAdapterLuid =
        (static_cast<uint64_t>(static_cast<uint32_t>(renderer->adapterLuid.HighPart)) << 32u) |
        static_cast<uint32_t>(renderer->adapterLuid.LowPart);
    if (requestedAdapterLuid != 0 && actualAdapterLuid != requestedAdapterLuid) {
        setFailure(renderer, "ANGLE selected a different adapter than the Nucleus TextureView host");
        return false;
    }
    (void)initialWidth;
    (void)initialHeight;
    return true;
}

bool initializeMpv(Renderer* renderer, mpv_handle* handle) {
    if (!renderer || !handle || !renderer->mpv.create) return false;
    mpv_opengl_init_params initParams{getGlProc, renderer};
    const char* apiType = "opengl";
    int advancedControl = 1;
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char*>(apiType)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &initParams},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advancedControl},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = renderer->mpv.create(&renderer->renderContext, handle, parameters);
    if (result < 0 || !renderer->renderContext) {
        setFailure(renderer, "libmpv rejected the ANGLE video render context");
        return false;
    }
    renderer->mpv.setUpdateCallback(renderer->renderContext, onMpvUpdate, renderer);
    return true;
}

void destroyRenderer(Renderer* renderer) {
    if (!renderer) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    if (!isRenderThread(renderer)) {
        setFailure(renderer, "MPV cleanup moved away from its dedicated EGL thread");
        return;
    }
    if (renderer->renderContext && renderer->mpv.free) {
        const bool current = makeCurrent(renderer, renderer->controlPbuffer);
        if (!current) {
            setFailure(renderer, "ANGLE could not activate the MPV cleanup context");
        }
        renderer->mpv.setUpdateCallback(renderer->renderContext, nullptr, nullptr);
        renderer->mpv.free(renderer->renderContext);
        renderer->renderContext = nullptr;
        if (current) {
            renderer->egl.finish();
            releaseCurrent(renderer);
        }
    }
    destroyTargets(renderer, renderer->activeTargets);
    destroyTargets(renderer, renderer->retiredTargets);
    renderer->lastOutput = nullptr;
    renderer->device.Reset();
    if (renderer->controlPbuffer != EGL_NO_SURFACE_VALUE && renderer->egl.destroySurface) {
        renderer->egl.destroySurface(renderer->display, renderer->controlPbuffer);
    }
    renderer->controlPbuffer = EGL_NO_SURFACE_VALUE;
    if (renderer->context != EGL_NO_CONTEXT_VALUE && renderer->egl.destroyContext) {
        renderer->egl.destroyContext(renderer->display, renderer->context);
    }
    renderer->context = EGL_NO_CONTEXT_VALUE;
    // This display belongs to the isolated MPV ANGLE module, so it cannot
    // invalidate the still-live Nucleus/Skia display when it is terminated.
    if (renderer->display != EGL_NO_DISPLAY_VALUE && renderer->egl.terminate) {
        renderer->egl.terminate(renderer->display);
    }
    renderer->display = EGL_NO_DISPLAY_VALUE;
    if (renderer->mpv.module) FreeLibrary(renderer->mpv.module);
    renderer->mpv.module = nullptr;
    if (renderer->egl.egl) FreeLibrary(renderer->egl.egl);
    renderer->egl.egl = nullptr;
    if (renderer->egl.gles) FreeLibrary(renderer->egl.gles);
    renderer->egl.gles = nullptr;
}

jlong createRenderer(
    JNIEnv* env,
    jlong mpvHandle,
    jstring libraryName,
    jlong requestedAdapterLuid,
    jboolean extendedLinear,
    jint initialWidth,
    jint initialHeight,
    jstring eglLibraryName,
    jstring glesLibraryName) {
    if (!mpvHandle || !libraryName || !eglLibraryName || !glesLibraryName ||
        initialWidth <= 0 || initialHeight <= 0) {
        return 0;
    }
    auto readString = [env](jstring value, std::wstring* output) -> bool {
        const jchar* chars = env->GetStringChars(value, nullptr);
        if (!chars) return false;
        output->assign(
            reinterpret_cast<const wchar_t*>(chars),
            static_cast<size_t>(env->GetStringLength(value)));
        env->ReleaseStringChars(value, chars);
        return true;
    };
    std::wstring path;
    std::wstring eglPath;
    std::wstring glesPath;
    if (!readString(libraryName, &path) ||
        !readString(eglLibraryName, &eglPath) ||
        !readString(glesLibraryName, &glesPath)) {
        return 0;
    }

    Renderer* renderer = new (std::nothrow) Renderer();
    if (!renderer) return 0;
    renderer->renderThreadId = GetCurrentThreadId();
    bool initialized =
        loadMpv(renderer, path.c_str()) &&
        initializeEgl(
            renderer,
            static_cast<uint64_t>(requestedAdapterLuid),
            extendedLinear == JNI_TRUE,
            initialWidth,
            initialHeight,
            eglPath.c_str(),
            glesPath.c_str());
    if (initialized) {
        initialized = makeCurrent(renderer, renderer->controlPbuffer);
        if (!initialized) {
            setFailure(renderer, "ANGLE could not activate the MPV initialization context");
        } else {
            initialized = ensureTargets(renderer, initialWidth, initialHeight) &&
                makeCurrent(renderer, renderer->controlPbuffer) &&
                initializeMpv(
                    renderer,
                    reinterpret_cast<mpv_handle*>(static_cast<intptr_t>(mpvHandle)));
            renderer->egl.finish();
        }
    }
    if (!initialized) {
        destroyRenderer(renderer);
        delete renderer;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer));
}

jlong renderFrame(jlong rendererHandle, jint width, jint height) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<intptr_t>(rendererHandle));
    if (!renderer || width <= 0 || height <= 0) return -1;
    std::lock_guard<std::mutex> guard(renderer->lock);
    if (!isRenderThread(renderer)) {
        setFailure(renderer, "MPV rendering moved away from its dedicated EGL thread");
        return -1;
    }
    if (!renderer->renderContext ||
        !ensureTargets(renderer, width, height) ||
        !makeCurrent(renderer, renderer->controlPbuffer)) {
        setFailure(renderer, "ANGLE could not activate the MPV texture target");
        return -1;
    }
    const uint64_t updates = renderer->mpv.update(renderer->renderContext);
    renderer->updateRequested.exchange(false, std::memory_order_acq_rel);
    const bool redraw = renderer->redrawRequested.exchange(false, std::memory_order_acq_rel);
    // With MPV_RENDER_PARAM_ADVANCED_CONTROL the update callback also wakes us
    // for core dispatch work when there is no frame to draw. Publishing a
    // redundant pooled target for that wakeup can alternate it with the latest
    // decoded target and become visible as flicker. Render without a new frame
    // only when target recreation explicitly requires the current frame to be
    // redrawn (for example, while resizing a paused video).
    if (!redraw && (updates & MPV_RENDER_UPDATE_FRAME) == 0) {
        return renderer->frameSerial > 0
            ? static_cast<jlong>(renderer->frameSerial)
            : 0;
    }
    TextureTarget* target = nullptr;
    const size_t targetCount = renderer->activeTargets.size();
    for (size_t attempt = 0; attempt < targetCount; ++attempt) {
        const size_t index = (renderer->targetCursor + attempt) % targetCount;
        TextureTarget* candidate = renderer->activeTargets[index].get();
        if (candidate && candidate->keyedMutex->AcquireSync(0, 0) == S_OK) {
            target = candidate;
            renderer->targetCursor = (index + 1) % targetCount;
            break;
        }
    }
    if (!target) {
        renderer->updateRequested.store(true, std::memory_order_release);
        return 0;
    }
    if (!makeCurrent(renderer, target->pbuffer)) {
        target->keyedMutex->ReleaseSync(0);
        setFailure(renderer, "ANGLE could not activate a pooled MPV texture target");
        return -1;
    }
    renderer->egl.viewport(0, 0, width, height);
    mpv_opengl_fbo fbo{
        0,
        width,
        height,
        renderer->glInternalFormat};
    int flipY = 1;
    int depth = renderer->extendedLinear ? 16 : 8;
    mpv_render_param parameters[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flipY},
        {MPV_RENDER_PARAM_DEPTH, &depth},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = renderer->mpv.render(renderer->renderContext, parameters);
    // Submit ANGLE's D3D11 work before handing the shared resource to the
    // consumer. ReleaseSync provides the cross-device ownership boundary;
    // waiting for the whole GPU with eglFinish while holding the mutex makes
    // interactive window resize serialize video rendering with composition.
    renderer->egl.flush();
    const HRESULT releaseResult = target->keyedMutex->ReleaseSync(0);
    if (result < 0) {
        setFailure(renderer, "libmpv failed to render into the shared video texture");
        return -1;
    }
    if (FAILED(releaseResult)) {
        setFailure(renderer, "D3D11 could not release the shared MPV video texture");
        return -1;
    }
    target->serial = ++renderer->frameSerial;
    renderer->lastOutput = target;
    // Advanced-control pacing must follow producer handoff, not a Compose
    // callback. During WM_ENTERSIZEMOVE the UI callback that confirms the
    // eventual system Present may be delayed even though the TextureView
    // stream is consuming frames. HDR verification remains based solely on
    // Nucleus' later Present counter; this call only lets mpv schedule decode.
    renderer->mpv.reportSwap(renderer->renderContext);
    renderer->lastReportedSerial = renderer->frameSerial;
    return static_cast<jlong>(renderer->frameSerial);
}

jlongArray textureInfo(JNIEnv* env, jlong rendererHandle) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<intptr_t>(rendererHandle));
    if (!renderer) return nullptr;
    std::lock_guard<std::mutex> guard(renderer->lock);
    TextureTarget* target = renderer->lastOutput;
    if (!target || !target->texture || !target->sharedHandle || target->serial == 0) return nullptr;
    const uint64_t luid =
        (static_cast<uint64_t>(static_cast<uint32_t>(renderer->adapterLuid.HighPart)) << 32u) |
        static_cast<uint32_t>(renderer->adapterLuid.LowPart);
    const jlong values[8] = {
        static_cast<jlong>(reinterpret_cast<intptr_t>(target->sharedHandle)),
        target->width,
        target->height,
        static_cast<jlong>(renderer->textureFormat),
        static_cast<jlong>(target->generation),
        static_cast<jlong>(target->serial),
        static_cast<jlong>(luid),
        renderer->extendedLinear ? 1 : 0,
    };
    jlongArray result = env->NewLongArray(8);
    if (result) env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}

void reportPresented(jlong rendererHandle, jlong frameSerial) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<intptr_t>(rendererHandle));
    if (!renderer || frameSerial <= 0) return;
    std::lock_guard<std::mutex> guard(renderer->lock);
    if (!isRenderThread(renderer)) {
        setFailure(renderer, "MPV presentation moved away from its dedicated EGL thread");
        return;
    }
    if (renderer->renderContext && static_cast<uint64_t>(frameSerial) <= renderer->frameSerial &&
        static_cast<uint64_t>(frameSerial) > renderer->lastReportedSerial) {
        const EGLSurface surface =
            renderer->lastOutput ? renderer->lastOutput->pbuffer : renderer->controlPbuffer;
        if (!makeCurrent(renderer, surface)) {
            setFailure(renderer, "ANGLE could not activate the MPV presentation context");
            return;
        }
        renderer->mpv.reportSwap(renderer->renderContext);
        renderer->lastReportedSerial = static_cast<uint64_t>(frameSerial);
    }
}

jstring failureMessage(JNIEnv* env, jlong rendererHandle) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<intptr_t>(rendererHandle));
    if (!renderer) return nullptr;
    std::lock_guard<std::mutex> guard(renderer->lock);
    return renderer->failure.empty() ? nullptr : env->NewStringUTF(renderer->failure.c_str());
}

void detach(jlong rendererHandle) {
    Renderer* renderer = reinterpret_cast<Renderer*>(static_cast<intptr_t>(rendererHandle));
    if (!renderer) return;
    destroyRenderer(renderer);
    delete renderer;
}

JNINativeMethod methods[] = {
    {const_cast<char*>("nCreateRenderer"),
     const_cast<char*>("(JLjava/lang/String;JZIILjava/lang/String;Ljava/lang/String;)J"),
     reinterpret_cast<void*>(+[](
         JNIEnv* env,
         jclass,
         jlong handle,
         jstring library,
         jlong luid,
         jboolean extendedLinear,
         jint width,
         jint height,
         jstring eglLibrary,
         jstring glesLibrary) {
         return createRenderer(
             env,
             handle,
             library,
             luid,
             extendedLinear,
             width,
             height,
             eglLibrary,
             glesLibrary);
     })},
    {const_cast<char*>("nRenderFrame"), const_cast<char*>("(JII)J"),
     reinterpret_cast<void*>(+[](JNIEnv*, jclass, jlong renderer, jint width, jint height) {
         return renderFrame(renderer, width, height);
     })},
    {const_cast<char*>("nGetTextureOutputInfo"), const_cast<char*>("(J)[J"),
     reinterpret_cast<void*>(+[](JNIEnv* env, jclass, jlong renderer) {
         return textureInfo(env, renderer);
     })},
    {const_cast<char*>("nReportPresented"), const_cast<char*>("(JJ)V"),
     reinterpret_cast<void*>(+[](JNIEnv*, jclass, jlong renderer, jlong serial) {
         reportPresented(renderer, serial);
     })},
    {const_cast<char*>("nGetFailure"), const_cast<char*>("(J)Ljava/lang/String;"),
     reinterpret_cast<void*>(+[](JNIEnv* env, jclass, jlong renderer) {
         return failureMessage(env, renderer);
     })},
    {const_cast<char*>("nDetach"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(+[](JNIEnv*, jclass, jlong renderer) { detach(renderer); })},
};

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || !env) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass(
        "io/github/kdroidfilter/composemediaplayer/mpv/MpvWindowsTextureBridge");
    if (!bridge || env->RegisterNatives(
            bridge, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0]))) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_8;
}
