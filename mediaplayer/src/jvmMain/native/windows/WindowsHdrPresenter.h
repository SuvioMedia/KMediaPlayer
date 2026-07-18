#pragma once

#include "NativeVideoPlayer.h"

#include <d3d11.h>
#include <dxgi1_6.h>
#include <mfapi.h>
#include <mfidl.h>
#include <wrl/client.h>

#include <cstddef>
#include <cstdint>
#include <mutex>

/**
 * Owns the controlled Windows HDR output route.
 *
 * Decoded P010 surfaces stay on the D3D11 device. The presenter copies the
 * decoder surface into a shader-readable P010 texture, applies projection and
 * color processing, and presents through an explicitly tagged flip-model
 * swapchain. It only reports a configured HDR route after the active output is
 * queried through IDXGIOutput6 and the first Present succeeds.
 */
class WindowsHdrPresenter final {
public:
    WindowsHdrPresenter();
    ~WindowsHdrPresenter();

    WindowsHdrPresenter(const WindowsHdrPresenter&) = delete;
    WindowsHdrPresenter& operator=(const WindowsHdrPresenter&) = delete;

    HRESULT Configure(
        const int32_t* integerConfiguration,
        size_t integerCount,
        const float* floatingConfiguration,
        size_t floatingCount);
    HRESULT Attach(HWND hwnd);
    void Detach();
    HRESULT Render(
        IMFSample* sample,
        UINT32 width,
        UINT32 height,
        const uint8_t* hdr10PlusPayload,
        size_t hdr10PlusPayloadSize);
    bool RequiresHdr10PlusMetadata() const;
    HdrOutputStatus GetStatus() const;
    static HRESULT ValidateShaders();

private:
    struct ShaderConfiguration {
        int32_t modes[4] = {0, 0, 0, 0};
        int32_t flags[4] = {0, 0, 0, 0};
        int32_t color[4] = {0, 0, 0, 0};
        float projection[4] = {360.0f, 0.0f, 0.0f, 0.0f};
        float view[4] = {1.0f, 1000.0f, 1000.0f, 80.0f};
        float crop[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        float hdr10PlusHeader[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        float hdr10PlusCurve[9][4] = {};
    };

    struct MasteringConfiguration {
        float redX = 0.708f;
        float redY = 0.292f;
        float greenX = 0.170f;
        float greenY = 0.797f;
        float blueX = 0.131f;
        float blueY = 0.046f;
        float whiteX = 0.3127f;
        float whiteY = 0.3290f;
        float minLuminanceNits = 0.005f;
        float maxLuminanceNits = 1000.0f;
        float maxContentLightLevelNits = 1000.0f;
        float maxFrameAverageLightLevelNits = 400.0f;
    };

    HRESULT RefreshOutputAndSwapChain(bool forceRecreate);
    HRESULT QueryActiveOutput();
    HRESULT CreateDeviceResources();
    HRESULT CreateSwapChain(UINT width, UINT height);
    HRESULT CreateBackBufferView();
    HRESULT ResizeSwapChainIfNeeded();
    HRESULT CreateInputViews(ID3D11Texture2D* sourceTexture, UINT sourceSubresource);
    HRESULT UploadConfiguration();
    HRESULT ApplySwapChainMetadata();
    void ReleaseSwapChainResources();
    void RecordError(HRESULT error);

    mutable std::mutex mutex_;
    HWND hwnd_ = nullptr;
    HMONITOR monitor_ = nullptr;
    UINT frameCounter_ = 0;
    UINT inputWidth_ = 0;
    UINT inputHeight_ = 0;
    UINT swapChainWidth_ = 0;
    UINT swapChainHeight_ = 0;
    DXGI_FORMAT swapChainFormat_ = DXGI_FORMAT_UNKNOWN;
    DXGI_COLOR_SPACE_TYPE swapChainColorSpace_ = DXGI_COLOR_SPACE_CUSTOM;
    ShaderConfiguration shaderConfiguration_;
    MasteringConfiguration masteringConfiguration_;
    HdrOutputStatus status_{};
    DXGI_OUTPUT_DESC1 outputDescription_{};

    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
    Microsoft::WRL::ComPtr<IDXGIFactory2> factory_;
    Microsoft::WRL::ComPtr<IDXGISwapChain4> swapChain_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> renderTargetView_;
    Microsoft::WRL::ComPtr<ID3D11VertexShader> vertexShader_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader> pixelShader_;
    Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
    Microsoft::WRL::ComPtr<ID3D11Buffer> configurationBuffer_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> shaderInputTexture_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> lumaView_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> chromaView_;
};
