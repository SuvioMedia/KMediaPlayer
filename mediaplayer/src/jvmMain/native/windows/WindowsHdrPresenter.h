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
 * Owns the controlled Windows color-managed texture producer.
 *
 * Decoded P010 HDR and NV12 SDR surfaces stay on the D3D11 device. The presenter
 * copies the decoder surface into a shader-readable texture, applies projection
 * and color processing, and renders into a legacy shared keyed-mutex texture for
 * Nucleus TextureView. The window swapchain and the system Present belong only
 * to Nucleus.
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
    void ResetOutput();
    HRESULT Render(
        IMFSample* sample,
        UINT32 width,
        UINT32 height,
        const uint8_t* hdr10PlusPayload,
        size_t hdr10PlusPayloadSize);
    bool RequiresHdr10PlusMetadata() const;
    bool RequiresP010Input() const;
    HdrOutputStatus GetStatus() const;
    bool GetTextureOutputInfo(HdrTextureOutputInfo* output) const;
    static HRESULT ValidateShaders();

private:
    struct ShaderConfiguration {
        int32_t modes[4] = {0, 0, 0, 0};
        int32_t flags[4] = {0, 0, 0, 0};
        int32_t color[4] = {0, 0, 0, 10};
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

    HRESULT CreateDeviceResources();
    HRESULT EnsureOutputTexture(UINT width, UINT height);
    HRESULT CreateInputViews(ID3D11Texture2D* sourceTexture, UINT sourceSubresource);
    HRESULT UploadConfiguration();
    void ReleaseOutputTexture();
    void RecordError(HRESULT error);

    mutable std::mutex mutex_;
    UINT inputWidth_ = 0;
    UINT inputHeight_ = 0;
    DXGI_FORMAT inputFormat_ = DXGI_FORMAT_UNKNOWN;
    UINT outputWidth_ = 0;
    UINT outputHeight_ = 0;
    DXGI_FORMAT outputFormat_ = DXGI_FORMAT_UNKNOWN;
    HANDLE sharedHandle_ = nullptr;
    UINT64 outputGeneration_ = 0;
    UINT64 frameSerial_ = 0;
    LUID adapterLuid_{};
    ShaderConfiguration shaderConfiguration_;
    MasteringConfiguration masteringConfiguration_;
    HdrOutputStatus status_{};

    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> outputTexture_;
    Microsoft::WRL::ComPtr<IDXGIKeyedMutex> outputMutex_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> renderTargetView_;
    Microsoft::WRL::ComPtr<ID3D11VertexShader> vertexShader_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader> pixelShader_;
    Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
    Microsoft::WRL::ComPtr<ID3D11Buffer> configurationBuffer_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> shaderInputTexture_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> lumaView_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> chromaView_;
};
