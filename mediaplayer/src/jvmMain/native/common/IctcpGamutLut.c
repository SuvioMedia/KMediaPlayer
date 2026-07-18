#include "IctcpGamutLut.h"

#include <math.h>
#include <stdint.h>

#define KMP_GAMUT_SEARCH_STEPS 16

typedef struct KmpRgb {
    double red;
    double green;
    double blue;
} KmpRgb;

typedef struct KmpIctcp {
    double intensity;
    double tritan;
    double protan;
} KmpIctcp;

static double clamp_value(double value, double minimum, double maximum) {
    if (value < minimum) return minimum;
    if (value > maximum) return maximum;
    return value;
}

static double pq_oetf(double nits) {
    const double m1 = 2610.0 / 16384.0;
    const double m2 = 2523.0 / 32.0;
    const double c1 = 3424.0 / 4096.0;
    const double c2 = 2413.0 / 128.0;
    const double c3 = 2392.0 / 128.0;
    const double normalized = pow(clamp_value(nits, 0.0, 10000.0) / 10000.0, m1);
    return pow((c1 + c2 * normalized) / (1.0 + c3 * normalized), m2);
}

static double pq_eotf(double signal) {
    const double m1 = 2610.0 / 16384.0;
    const double m2 = 2523.0 / 32.0;
    const double c1 = 3424.0 / 4096.0;
    const double c2 = 2413.0 / 128.0;
    const double c3 = 2392.0 / 128.0;
    const double powered = pow(clamp_value(signal, 0.0, 1.0), 1.0 / m2);
    const double denominator = fmax(c2 - c3 * powered, 1.0e-20);
    return pow(fmax(powered - c1, 0.0) / denominator, 1.0 / m1) * 10000.0;
}

static KmpRgb bt2020_to_bt709(KmpRgb input) {
    const KmpRgb output = {
        1.660491 * input.red - 0.587641 * input.green - 0.072850 * input.blue,
        -0.124550 * input.red + 1.132900 * input.green - 0.008349 * input.blue,
        -0.018151 * input.red - 0.100579 * input.green + 1.118730 * input.blue,
    };
    return output;
}

static int is_in_unit_gamut(KmpRgb color) {
    return color.red >= 0.0 && color.red <= 1.0 &&
        color.green >= 0.0 && color.green <= 1.0 &&
        color.blue >= 0.0 && color.blue <= 1.0;
}

static KmpRgb clamp_rgb(KmpRgb color) {
    color.red = clamp_value(color.red, 0.0, 1.0);
    color.green = clamp_value(color.green, 0.0, 1.0);
    color.blue = clamp_value(color.blue, 0.0, 1.0);
    return color;
}

static KmpIctcp bt2020_to_ictcp(KmpRgb input, double peak_nits) {
    const double l = (1688.0 * input.red + 2146.0 * input.green + 262.0 * input.blue) / 4096.0;
    const double m = (683.0 * input.red + 2951.0 * input.green + 462.0 * input.blue) / 4096.0;
    const double s = (99.0 * input.red + 309.0 * input.green + 3688.0 * input.blue) / 4096.0;
    const double lp = pq_oetf(fmax(l, 0.0) * peak_nits);
    const double mp = pq_oetf(fmax(m, 0.0) * peak_nits);
    const double sp = pq_oetf(fmax(s, 0.0) * peak_nits);
    const KmpIctcp output = {
        0.5 * lp + 0.5 * mp,
        (6610.0 * lp - 13613.0 * mp + 7003.0 * sp) / 4096.0,
        (17933.0 * lp - 17390.0 * mp - 543.0 * sp) / 4096.0,
    };
    return output;
}

static KmpRgb ictcp_to_bt2020(KmpIctcp input, double peak_nits) {
    const double lp = input.intensity + 0.008609037 * input.tritan + 0.111029625 * input.protan;
    const double mp = input.intensity - 0.008609037 * input.tritan - 0.111029625 * input.protan;
    const double sp = input.intensity + 0.560031336 * input.tritan - 0.320627175 * input.protan;
    const double l = pq_eotf(clamp_value(lp, 0.0, 1.0)) / peak_nits;
    const double m = pq_eotf(clamp_value(mp, 0.0, 1.0)) / peak_nits;
    const double s = pq_eotf(clamp_value(sp, 0.0, 1.0)) / peak_nits;
    const KmpRgb output = {
        3.43660669 * l - 2.50645212 * m + 0.06984542 * s,
        -0.79132956 * l + 1.98360045 * m - 0.19227090 * s,
        -0.02594990 * l - 0.09891371 * m + 1.12486361 * s,
    };
    return output;
}

static KmpRgb gamut_map(KmpRgb input, double peak_nits) {
    const KmpRgb direct = bt2020_to_bt709(input);
    if (is_in_unit_gamut(direct)) return clamp_rgb(direct);

    const KmpIctcp source = bt2020_to_ictcp(input, peak_nits);
    KmpIctcp candidate_ictcp = source;
    candidate_ictcp.tritan = 0.0;
    candidate_ictcp.protan = 0.0;
    KmpRgb best = bt2020_to_bt709(ictcp_to_bt2020(candidate_ictcp, peak_nits));
    double low = 0.0;
    double high = 1.0;
    for (int step = 0; step < KMP_GAMUT_SEARCH_STEPS; ++step) {
        const double scale = (low + high) * 0.5;
        candidate_ictcp.tritan = source.tritan * scale;
        candidate_ictcp.protan = source.protan * scale;
        const KmpRgb candidate = bt2020_to_bt709(ictcp_to_bt2020(candidate_ictcp, peak_nits));
        if (is_in_unit_gamut(candidate)) {
            best = candidate;
            low = scale;
        } else {
            high = scale;
        }
    }
    return clamp_rgb(best);
}

size_t kmp_ictcp_gamut_lut_value_count(uint32_t edge) {
    if (edge < 2u) return 0u;
    const size_t maximum = SIZE_MAX / KMP_ICTCP_GAMUT_LUT_CHANNELS;
    if ((size_t)edge > maximum / (size_t)edge) return 0u;
    const size_t plane = (size_t)edge * (size_t)edge;
    if (plane > maximum / (size_t)edge) return 0u;
    return plane * (size_t)edge * KMP_ICTCP_GAMUT_LUT_CHANNELS;
}

int kmp_generate_ictcp_gamut_lut_rgba32f(
    float* output,
    size_t output_value_count,
    uint32_t edge,
    float nominal_peak_nits
) {
    const size_t required = kmp_ictcp_gamut_lut_value_count(edge);
    if (!output || required == 0u || output_value_count < required ||
        !isfinite(nominal_peak_nits) || nominal_peak_nits <= 0.0f || nominal_peak_nits > 10000.0f) {
        return 0;
    }
    const double denominator = (double)(edge - 1u);
    size_t output_index = 0u;
    for (uint32_t blue = 0u; blue < edge; ++blue) {
        for (uint32_t green = 0u; green < edge; ++green) {
            for (uint32_t red = 0u; red < edge; ++red) {
                const KmpRgb input = {
                    (double)red / denominator,
                    (double)green / denominator,
                    (double)blue / denominator,
                };
                const KmpRgb mapped = gamut_map(input, (double)nominal_peak_nits);
                output[output_index++] = (float)mapped.red;
                output[output_index++] = (float)mapped.green;
                output[output_index++] = (float)mapped.blue;
                output[output_index++] = 1.0f;
            }
        }
    }
    return 1;
}
