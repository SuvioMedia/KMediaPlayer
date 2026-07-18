#ifndef KMP_HDR10_PLUS_TONE_CURVE_H
#define KMP_HDR10_PLUS_TONE_CURVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT 33

/**
 * Parses one complete HDR10+ User Data Registered ITU-T T.35 payload and
 * samples its ST 2094-40 Application 4 Version 1 OOTF in linear luminance.
 *
 * Curve values are normalized to the 10,000-nit PQ domain. The caller owns
 * every output buffer. Returns 1 on success and 0 for malformed, unsupported,
 * or incomplete metadata. No partially parsed curve is ever returned.
 */
int kmp_hdr10_plus_parse_tone_curve(
    const uint8_t* payload,
    size_t payload_size,
    double display_peak_nits,
    float* source_peak_nits,
    float output_luminance[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT],
    char* error,
    size_t error_capacity
);

#ifdef __cplusplus
}
#endif

#endif
