#include "NativeVideoPlayer.h"

#include <cstdio>

int main() {
    const HRESULT result = ValidateHdrPresenterShaders();
    if (FAILED(result)) {
        std::fprintf(stderr, "HDR presenter CPU/GPU reference validation failed: 0x%08lx\n",
                     static_cast<unsigned long>(result));
        return 1;
    }
    std::puts("HDR_PRESENTER_CPU_GPU_REFERENCE_OK");
    return 0;
}
