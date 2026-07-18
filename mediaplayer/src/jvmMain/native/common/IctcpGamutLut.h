#ifndef KMP_ICTCP_GAMUT_LUT_H
#define KMP_ICTCP_GAMUT_LUT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KMP_ICTCP_GAMUT_LUT_DEFAULT_EDGE 33u
#define KMP_ICTCP_GAMUT_LUT_CHANNELS 4u

/** Number of RGBA float values required for an edge^3 LUT, or zero on overflow/invalid input. */
size_t kmp_ictcp_gamut_lut_value_count(uint32_t edge);

/**
 * Generates a linear BT.2020 -> linear BT.709 3D LUT using PQ ICtCp chroma
 * reduction. Values are stored with red changing fastest, then green, blue;
 * every texel is RGBA32F with alpha 1.
 */
int kmp_generate_ictcp_gamut_lut_rgba32f(
    float* output,
    size_t output_value_count,
    uint32_t edge,
    float nominal_peak_nits
);

#ifdef __cplusplus
}
#endif

#endif
