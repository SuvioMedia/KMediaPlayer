#include "Hdr10PlusToneCurve.h"

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define KMP_HDR10_PLUS_COUNTRY_CODE 0xb5u
#define KMP_HDR10_PLUS_PROVIDER_CODE 0x003cu
#define KMP_HDR10_PLUS_PROVIDER_ORIENTED_CODE 0x0001u
#define KMP_HDR10_PLUS_APPLICATION_IDENTIFIER 4u
#define KMP_HDR10_PLUS_APPLICATION_VERSION 1u
#define KMP_HDR10_PLUS_MAX_LUMINANCE_NITS 10000u
#define KMP_HDR10_PLUS_MAX_MEASUREMENT 100000u
#define KMP_HDR10_PLUS_MAX_PERCENTILES 15u
#define KMP_HDR10_PLUS_MAX_BEZIER_ANCHORS 9u

#define KMP_PQ_M1 (2610.0 / 16384.0)
#define KMP_PQ_M2 (2523.0 / 32.0)
#define KMP_PQ_C1 (3424.0 / 4096.0)
#define KMP_PQ_C2 (2413.0 / 128.0)
#define KMP_PQ_C3 (2392.0 / 128.0)
#define KMP_PQ_MAX_NITS 10000.0
#define KMP_HDR10_PLUS_KNEE_DENOMINATOR 4095.0
#define KMP_HDR10_PLUS_BEZIER_DENOMINATOR 1023.0
#define KMP_HDR10_PLUS_BRIGHT_DISPLAY_EXPONENT 1.4

typedef struct KmpBitReader {
    const uint8_t* bytes;
    size_t size;
    size_t bit_offset;
    int failed;
} KmpBitReader;

typedef struct KmpHdr10PlusMetadata {
    uint32_t target_peak_nits;
    uint32_t max_scl[3];
    uint32_t average_max_rgb;
    uint32_t percentile_values[KMP_HDR10_PLUS_MAX_PERCENTILES];
    uint8_t percentile_indexes[KMP_HDR10_PLUS_MAX_PERCENTILES];
    uint32_t percentile_count;
    uint32_t fraction_bright_pixels;
    int has_tone_mapping;
    uint32_t knee_x;
    uint32_t knee_y;
    uint32_t anchor_count;
    uint32_t anchors[KMP_HDR10_PLUS_MAX_BEZIER_ANCHORS];
} KmpHdr10PlusMetadata;

static void kmp_set_error(char* error, size_t capacity, const char* format, ...) {
    va_list arguments;
    if (error == NULL || capacity == 0) return;
    va_start(arguments, format);
    (void)vsnprintf(error, capacity, format, arguments);
    va_end(arguments);
    error[capacity - 1] = '\0';
}

static uint32_t kmp_read_bits(KmpBitReader* reader, uint32_t count) {
    uint32_t value = 0;
    uint32_t index;
    if (reader->failed || count == 0 || count > 31 ||
        reader->bit_offset + count > reader->size * 8) {
        reader->failed = 1;
        return 0;
    }
    for (index = 0; index < count; ++index) {
        const size_t byte_index = reader->bit_offset >> 3;
        const uint32_t bit_index = 7u - (uint32_t)(reader->bit_offset & 7u);
        value = (value << 1u) | ((reader->bytes[byte_index] >> bit_index) & 1u);
        reader->bit_offset += 1;
    }
    return value;
}

static int kmp_has_only_alignment_padding(KmpBitReader* reader) {
    const size_t remaining = reader->size * 8 - reader->bit_offset;
    size_t index;
    if (reader->failed || remaining > 7) return 0;
    for (index = 0; index < remaining; ++index) {
        if (kmp_read_bits(reader, 1) != 0) return 0;
    }
    return !reader->failed;
}

static int kmp_percentile_profile_is_valid(const KmpHdr10PlusMetadata* metadata) {
    static const uint8_t profile_nine[] = {1, 5, 10, 25, 50, 75, 90, 95, 99};
    static const uint8_t profile_ten[] = {1, 5, 10, 25, 50, 75, 90, 95, 98, 99};
    const uint8_t* expected;
    uint32_t index;
    if (metadata->percentile_count == 9) {
        expected = profile_nine;
    } else if (metadata->percentile_count == 10) {
        expected = profile_ten;
    } else {
        return 0;
    }
    for (index = 0; index < metadata->percentile_count; ++index) {
        if (metadata->percentile_indexes[index] != expected[index]) return 0;
    }
    return 1;
}

static int kmp_parse_metadata(
    const uint8_t* payload,
    size_t payload_size,
    KmpHdr10PlusMetadata* metadata,
    char* error,
    size_t error_capacity
) {
    KmpBitReader reader;
    uint32_t index;
    uint32_t window_count;
    int saturation_mapping;

    memset(metadata, 0, sizeof(*metadata));
    reader.bytes = payload;
    reader.size = payload_size;
    reader.bit_offset = 0;
    reader.failed = 0;

    if (kmp_read_bits(&reader, 8) != KMP_HDR10_PLUS_COUNTRY_CODE) {
        kmp_set_error(error, error_capacity, "Not an HDR10+ ITU-T T.35 country code.");
        return 0;
    }
    if (kmp_read_bits(&reader, 16) != KMP_HDR10_PLUS_PROVIDER_CODE ||
        kmp_read_bits(&reader, 16) != KMP_HDR10_PLUS_PROVIDER_ORIENTED_CODE) {
        kmp_set_error(error, error_capacity, "Unexpected HDR10+ provider registration.");
        return 0;
    }
    if (kmp_read_bits(&reader, 8) != KMP_HDR10_PLUS_APPLICATION_IDENTIFIER ||
        kmp_read_bits(&reader, 8) != KMP_HDR10_PLUS_APPLICATION_VERSION) {
        kmp_set_error(error, error_capacity, "Unsupported HDR10+ application identifier or version.");
        return 0;
    }
    window_count = kmp_read_bits(&reader, 2);
    if (window_count != 1) {
        kmp_set_error(error, error_capacity, "HDR10+ Application 4 Version 1 requires one processing window.");
        return 0;
    }

    metadata->target_peak_nits = kmp_read_bits(&reader, 27);
    if (kmp_read_bits(&reader, 1) != 0) {
        kmp_set_error(error, error_capacity, "HDR10+ Application 4 Version 1 does not permit target peak grids.");
        return 0;
    }
    for (index = 0; index < 3; ++index) metadata->max_scl[index] = kmp_read_bits(&reader, 17);
    metadata->average_max_rgb = kmp_read_bits(&reader, 17);
    metadata->percentile_count = kmp_read_bits(&reader, 4);
    if (metadata->percentile_count > KMP_HDR10_PLUS_MAX_PERCENTILES) {
        kmp_set_error(error, error_capacity, "Invalid HDR10+ distribution percentile count.");
        return 0;
    }
    for (index = 0; index < metadata->percentile_count; ++index) {
        metadata->percentile_indexes[index] = (uint8_t)kmp_read_bits(&reader, 7);
        metadata->percentile_values[index] = kmp_read_bits(&reader, 17);
    }
    metadata->fraction_bright_pixels = kmp_read_bits(&reader, 10);
    if (kmp_read_bits(&reader, 1) != 0) {
        kmp_set_error(error, error_capacity, "HDR10+ Application 4 Version 1 does not permit mastering peak grids.");
        return 0;
    }
    metadata->has_tone_mapping = kmp_read_bits(&reader, 1) != 0;
    if (metadata->has_tone_mapping) {
        metadata->knee_x = kmp_read_bits(&reader, 12);
        metadata->knee_y = kmp_read_bits(&reader, 12);
        metadata->anchor_count = kmp_read_bits(&reader, 4);
        if (metadata->anchor_count > KMP_HDR10_PLUS_MAX_BEZIER_ANCHORS) {
            kmp_set_error(error, error_capacity, "HDR10+ tone mapping contains more than nine Bezier anchors.");
            return 0;
        }
        for (index = 0; index < metadata->anchor_count; ++index) {
            metadata->anchors[index] = kmp_read_bits(&reader, 10);
        }
    }
    saturation_mapping = kmp_read_bits(&reader, 1) != 0;
    if (saturation_mapping) {
        (void)kmp_read_bits(&reader, 6);
        kmp_set_error(error, error_capacity, "HDR10+ Application 4 Version 1 does not permit saturation mapping.");
        return 0;
    }
    if (reader.failed) {
        kmp_set_error(error, error_capacity, "Truncated HDR10+ ST 2094-40 payload.");
        return 0;
    }
    if (!kmp_has_only_alignment_padding(&reader)) {
        kmp_set_error(error, error_capacity, "Unexpected trailing HDR10+ ST 2094-40 data.");
        return 0;
    }
    if (metadata->target_peak_nits > KMP_HDR10_PLUS_MAX_LUMINANCE_NITS) {
        kmp_set_error(error, error_capacity, "HDR10+ target luminance exceeds 10,000 nits.");
        return 0;
    }
    for (index = 0; index < 3; ++index) {
        if (metadata->max_scl[index] > KMP_HDR10_PLUS_MAX_MEASUREMENT) {
            kmp_set_error(error, error_capacity, "HDR10+ MaxSCL exceeds its profile bound.");
            return 0;
        }
    }
    if (metadata->average_max_rgb > KMP_HDR10_PLUS_MAX_MEASUREMENT ||
        !kmp_percentile_profile_is_valid(metadata)) {
        kmp_set_error(error, error_capacity, "HDR10+ luminance distribution does not match the profile.");
        return 0;
    }
    for (index = 0; index < metadata->percentile_count; ++index) {
        if (metadata->percentile_values[index] > KMP_HDR10_PLUS_MAX_MEASUREMENT) {
            kmp_set_error(error, error_capacity, "HDR10+ distribution percentile exceeds its profile bound.");
            return 0;
        }
    }
    if (!metadata->has_tone_mapping && metadata->target_peak_nits != 0) {
        kmp_set_error(error, error_capacity, "HDR10+ Profile A requires zero target luminance.");
        return 0;
    }
    if (metadata->has_tone_mapping &&
        (metadata->target_peak_nits == 0 || metadata->anchor_count == 0)) {
        kmp_set_error(error, error_capacity, "HDR10+ Profile B requires a target luminance and Bezier anchors.");
        return 0;
    }
    return 1;
}

static double kmp_clamp(double value, double minimum, double maximum) {
    return fmin(fmax(value, minimum), maximum);
}

static double kmp_mix(double left, double right, double amount) {
    return left * (1.0 - amount) + right * amount;
}

static double kmp_pq_eotf(double encoded) {
    const double signal = kmp_clamp(encoded, 0.0, 1.0);
    const double powered = pow(signal, 1.0 / KMP_PQ_M2);
    const double numerator = fmax(powered - KMP_PQ_C1, 0.0);
    const double denominator = KMP_PQ_C2 - KMP_PQ_C3 * powered;
    if (denominator <= 0.0) return KMP_PQ_MAX_NITS;
    return pow(numerator / denominator, 1.0 / KMP_PQ_M1) * KMP_PQ_MAX_NITS;
}

static double kmp_pq_oetf(double nits) {
    const double normalized = pow(kmp_clamp(nits, 0.0, KMP_PQ_MAX_NITS) / KMP_PQ_MAX_NITS, KMP_PQ_M1);
    return pow(
        (KMP_PQ_C1 + KMP_PQ_C2 * normalized) / (1.0 + KMP_PQ_C3 * normalized),
        KMP_PQ_M2
    );
}

static double kmp_tone_map_bt2390(double nits, double source_peak, double target_peak) {
    double source_code;
    double target_code;
    double normalized_target;
    double knee;
    double input;
    double output;
    if (target_peak >= source_peak) return kmp_clamp(nits, 0.0, source_peak);
    source_code = kmp_pq_oetf(source_peak);
    target_code = kmp_pq_oetf(target_peak);
    normalized_target = kmp_clamp(target_code / fmax(source_code, 0.000001), 0.0, 1.0);
    knee = kmp_clamp(1.5 * normalized_target - 0.5, 0.0, 1.0);
    input = kmp_clamp(kmp_pq_oetf(nits) / fmax(source_code, 0.000001), 0.0, 1.0);
    if (input <= knee || knee >= 1.0) {
        output = input;
    } else {
        const double t = kmp_clamp((input - knee) / fmax(1.0 - knee, 0.000001), 0.0, 1.0);
        const double t2 = t * t;
        const double t3 = t2 * t;
        output =
            (2.0 * t3 - 3.0 * t2 + 1.0) * knee +
            (t3 - 2.0 * t2 + t) * (1.0 - knee) +
            (-2.0 * t3 + 3.0 * t2) * normalized_target;
    }
    return kmp_clamp(kmp_pq_eotf(kmp_clamp(output * source_code, 0.0, 1.0)), 0.0, target_peak);
}

static double kmp_binomial(uint32_t n, uint32_t k) {
    const uint32_t reduced = k < n - k ? k : n - k;
    double result = 1.0;
    uint32_t index;
    for (index = 1; index <= reduced; ++index) {
        result = result * (n - reduced + index) / index;
    }
    return result;
}

static double kmp_bezier(const double* points, uint32_t degree, double t) {
    double result = 0.0;
    uint32_t index;
    for (index = 0; index <= degree; ++index) {
        result +=
            kmp_binomial(degree, index) *
            pow(1.0 - t, degree - index) *
            pow(t, index) *
            points[index];
    }
    return result;
}

static double kmp_st2094_intercept(uint32_t degree, double knee_x, double knee_y) {
    double slope;
    if (knee_x <= 0.0 || knee_y >= 1.0) return 1.0 / degree;
    slope = knee_y / knee_x * (1.0 - knee_x) / (1.0 - knee_y);
    return fmin(slope / degree, 1.0);
}

static double kmp_scene_peak_nits(const KmpHdr10PlusMetadata* metadata, int* available) {
    uint32_t histogram_fallback = 0;
    uint32_t channels[3];
    uint32_t index;
    for (index = 0; index < metadata->percentile_count; ++index) {
        if (metadata->percentile_values[index] > histogram_fallback) {
            histogram_fallback = metadata->percentile_values[index];
        }
    }
    for (index = 0; index < 3; ++index) {
        channels[index] = metadata->max_scl[index] != 0
            ? metadata->max_scl[index]
            : histogram_fallback;
    }
    if (channels[0] == 0 && channels[1] == 0 && channels[2] == 0) {
        *available = 0;
        return 0.0;
    }
    *available = 1;
    return (0.2627 * channels[0] + 0.6780 * channels[1] + 0.0593 * channels[2]) / 10.0;
}

static double kmp_apply_tone_mapping(
    double nits,
    const KmpHdr10PlusMetadata* metadata,
    double display_peak,
    double estimated_peak,
    int has_estimated_peak
) {
    const double metadata_target = metadata->target_peak_nits > 0
        ? metadata->target_peak_nits
        : display_peak;
    const double minimum_source = fmin(display_peak, metadata_target);
    double source_peak = has_estimated_peak ? estimated_peak : fmax(display_peak, metadata_target);
    double output_peak;
    double knee_x;
    double knee_y;
    double points[KMP_HDR10_PLUS_MAX_BEZIER_ANCHORS + 2];
    uint32_t degree;
    uint32_t index;
    double input;
    double output;

    source_peak = kmp_clamp(fmax(source_peak, minimum_source), 1.0, KMP_PQ_MAX_NITS);
    output_peak = fmin(display_peak, source_peak);
    if (!metadata->has_tone_mapping) {
        return kmp_tone_map_bt2390(nits, fmax(source_peak, output_peak), output_peak);
    }

    knee_x = kmp_clamp(metadata->knee_x / KMP_HDR10_PLUS_KNEE_DENOMINATOR, 0.0, 1.0);
    knee_y = kmp_clamp(metadata->knee_y / KMP_HDR10_PLUS_KNEE_DENOMINATOR, 0.0, 1.0);
    points[0] = 0.0;
    for (index = 0; index < metadata->anchor_count; ++index) {
        points[index + 1] = kmp_clamp(metadata->anchors[index] / KMP_HDR10_PLUS_BEZIER_DENOMINATOR, 0.0, 1.0);
    }
    degree = metadata->anchor_count + 1;
    points[degree] = 1.0;

    {
        const double reference_target = kmp_clamp(metadata_target, 1.0, source_peak);
        if (output_peak < reference_target) {
            const double adaptation = kmp_clamp(output_peak / reference_target, 0.0, 1.0);
            double beta;
            double slope_bound;
            double linear_knee;
            knee_x *= adaptation;
            knee_y *= adaptation;
            beta = knee_x >= 1.0 ? INFINITY : degree * knee_x / (1.0 - knee_x);
            slope_bound = isfinite(beta) ? beta / (beta + 1.0) : 1.0;
            linear_knee = fmin(knee_x * source_peak / output_peak, slope_bound);
            knee_y = kmp_mix(linear_knee, knee_y, adaptation);
            for (index = 2; index <= degree; ++index) {
                points[index] = kmp_mix(1.0, points[index], adaptation);
            }
            points[1] = kmp_mix(
                kmp_st2094_intercept(degree, knee_x, knee_y),
                points[1],
                adaptation
            );
        } else if (output_peak > reference_target && source_peak > reference_target) {
            const double adaptation = pow(
                kmp_clamp(
                    1.0 - (output_peak - reference_target) / (source_peak - reference_target),
                    0.0,
                    1.0
                ),
                KMP_HDR10_PLUS_BRIGHT_DISPLAY_EXPONENT
            );
            const double linear_knee = knee_x * output_peak / source_peak;
            knee_y *= reference_target / output_peak;
            knee_y = kmp_mix(linear_knee, knee_y, adaptation);
            for (index = 2; index < degree; ++index) {
                points[index] = kmp_mix((double)index / degree, points[index], adaptation);
            }
            points[1] = kmp_mix(
                kmp_st2094_intercept(degree, knee_x, knee_y),
                points[1],
                adaptation
            );
        }
    }

    input = kmp_clamp(nits / source_peak, 0.0, 1.0);
    if (input <= knee_x && knee_x > 0.0) {
        output = input * knee_y / knee_x;
    } else if (knee_x >= 1.0) {
        output = input;
    } else {
        const double t = kmp_clamp((input - knee_x) / (1.0 - knee_x), 0.0, 1.0);
        output = knee_y + (1.0 - knee_y) * kmp_bezier(points, degree, t);
    }
    return kmp_clamp(kmp_clamp(output, 0.0, 1.0) * output_peak, 0.0, output_peak);
}

int kmp_hdr10_plus_parse_tone_curve(
    const uint8_t* payload,
    size_t payload_size,
    double display_peak_nits,
    float* source_peak_nits,
    float output_luminance[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT],
    char* error,
    size_t error_capacity
) {
    KmpHdr10PlusMetadata metadata;
    double estimated_peak;
    double target_peak;
    double curve_source_peak;
    int has_estimated_peak;
    uint32_t index;

    if (error != NULL && error_capacity > 0) error[0] = '\0';
    if (payload == NULL || payload_size == 0 || source_peak_nits == NULL || output_luminance == NULL) {
        kmp_set_error(error, error_capacity, "HDR10+ parser received an empty input or output buffer.");
        return 0;
    }
    if (!isfinite(display_peak_nits) || display_peak_nits <= 0.0 || display_peak_nits > KMP_PQ_MAX_NITS) {
        kmp_set_error(error, error_capacity, "Display peak must be finite and in (0, 10,000] nits.");
        return 0;
    }
    if (!kmp_parse_metadata(payload, payload_size, &metadata, error, error_capacity)) return 0;

    estimated_peak = kmp_scene_peak_nits(&metadata, &has_estimated_peak);
    target_peak = metadata.target_peak_nits > 0
        ? fmin(display_peak_nits, metadata.target_peak_nits)
        : display_peak_nits;
    target_peak = fmax(target_peak, 1.0);
    curve_source_peak = has_estimated_peak ? estimated_peak : target_peak;
    curve_source_peak = fmax(curve_source_peak, target_peak);
    *source_peak_nits = (float)curve_source_peak;
    for (index = 0; index < KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT; ++index) {
        const double input_nits = curve_source_peak * index /
            (KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT - 1.0);
        output_luminance[index] = (float)(
            kmp_apply_tone_mapping(
                input_nits,
                &metadata,
                display_peak_nits,
                estimated_peak,
                has_estimated_peak
            ) / KMP_PQ_MAX_NITS
        );
    }
    return 1;
}
