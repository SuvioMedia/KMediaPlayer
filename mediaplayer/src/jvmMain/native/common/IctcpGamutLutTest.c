#include "IctcpGamutLut.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static int close_to(float actual, float expected, float tolerance) {
    return isfinite(actual) && fabsf(actual - expected) <= tolerance;
}

int main(void) {
    const uint32_t edge = 3u;
    const size_t count = kmp_ictcp_gamut_lut_value_count(edge);
    float* values = (float*)calloc(count, sizeof(float));
    if (!values || !kmp_generate_ictcp_gamut_lut_rgba32f(values, count, edge, 100.0f)) {
        fprintf(stderr, "ICtCp gamut LUT generation failed\n");
        free(values);
        return 1;
    }
    for (size_t index = 0u; index < count; index += 4u) {
        for (size_t channel = 0u; channel < 3u; ++channel) {
            if (!isfinite(values[index + channel]) || values[index + channel] < 0.0f ||
                values[index + channel] > 1.0f) {
                fprintf(stderr, "ICtCp gamut LUT contains an out-of-range value\n");
                free(values);
                return 1;
            }
        }
        if (values[index + 3u] != 1.0f) {
            fprintf(stderr, "ICtCp gamut LUT alpha is not one\n");
            free(values);
            return 1;
        }
    }

    const size_t grey_index = (((size_t)1u * edge + 1u) * edge + 1u) * 4u;
    if (!close_to(values[grey_index], 0.5f, 0.0001f) ||
        !close_to(values[grey_index + 1u], 0.5f, 0.0001f) ||
        !close_to(values[grey_index + 2u], 0.5f, 0.0001f)) {
        fprintf(stderr, "ICtCp gamut LUT does not preserve neutral grey\n");
        free(values);
        return 1;
    }

    const size_t red_index = 2u * 4u;
    if (!close_to(values[red_index], 0.9999923f, 0.00003f) ||
        !close_to(values[red_index + 1u], 0.0621531f, 0.00003f) ||
        !close_to(values[red_index + 2u], 0.0435478f, 0.00003f)) {
        fprintf(stderr, "ICtCp red-corner reference mismatch\n");
        free(values);
        return 1;
    }

    free(values);
    if (kmp_ictcp_gamut_lut_value_count(1u) != 0u ||
        kmp_generate_ictcp_gamut_lut_rgba32f(NULL, 0u, edge, 100.0f)) {
        fprintf(stderr, "ICtCp gamut LUT invalid-input contract failed\n");
        return 1;
    }
    printf("ICTCP_GAMUT_LUT_TEST_OK\n");
    return 0;
}
