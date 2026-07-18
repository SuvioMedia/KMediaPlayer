#include "Hdr10PlusToneCurve.h"

#include <assert.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

typedef struct BitWriter {
    uint8_t bytes[256];
    size_t bit_count;
} BitWriter;

static void write_bits(BitWriter* writer, uint32_t value, uint32_t count) {
    uint32_t shift;
    for (shift = count; shift > 0; --shift) {
        const uint32_t bit = (value >> (shift - 1)) & 1u;
        const size_t byte_index = writer->bit_count >> 3;
        const uint32_t bit_index = 7u - (uint32_t)(writer->bit_count & 7u);
        if (bit != 0) writer->bytes[byte_index] |= (uint8_t)(1u << bit_index);
        writer->bit_count += 1;
    }
}

static size_t build_profile_b_payload(
    uint8_t* output,
    size_t capacity,
    const uint32_t max_scl[3],
    uint32_t knee_y,
    const uint32_t* anchors,
    uint32_t anchor_count
) {
    static const uint8_t percentile_indexes[] = {1, 5, 10, 25, 50, 75, 90, 95, 99};
    BitWriter writer;
    size_t index;
    size_t size;
    memset(&writer, 0, sizeof(writer));
    write_bits(&writer, 0xb5, 8);
    write_bits(&writer, 0x003c, 16);
    write_bits(&writer, 0x0001, 16);
    write_bits(&writer, 4, 8);
    write_bits(&writer, 1, 8);
    write_bits(&writer, 1, 2);
    write_bits(&writer, 1000, 27);
    write_bits(&writer, 0, 1);
    write_bits(&writer, max_scl[0], 17);
    write_bits(&writer, max_scl[1], 17);
    write_bits(&writer, max_scl[2], 17);
    write_bits(&writer, 4000, 17);
    write_bits(&writer, 9, 4);
    for (index = 0; index < 9; ++index) {
        write_bits(&writer, percentile_indexes[index], 7);
        write_bits(&writer, (uint32_t)(index + 1) * 1000u, 17);
    }
    write_bits(&writer, 64, 10);
    write_bits(&writer, 0, 1);
    write_bits(&writer, 1, 1);
    write_bits(&writer, 1024, 12);
    write_bits(&writer, knee_y, 12);
    write_bits(&writer, anchor_count, 4);
    for (index = 0; index < anchor_count; ++index) write_bits(&writer, anchors[index], 10);
    write_bits(&writer, 0, 1);
    size = (writer.bit_count + 7) / 8;
    assert(size <= capacity);
    memcpy(output, writer.bytes, size);
    return size;
}

int main(void) {
    static const uint32_t max_scl[] = {10000, 11000, 12000};
    static const uint32_t anchors[] = {320, 700};
    static const uint32_t reference_max_scl[] = {10000, 10000, 10000};
    static const uint32_t reference_anchor[] = {512};
    uint8_t payload[256];
    uint8_t malformed[256];
    float curve[KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT];
    float source_peak = 0.0f;
    char error[256];
    size_t size = build_profile_b_payload(
        payload,
        sizeof(payload),
        max_scl,
        1200,
        anchors,
        2
    );
    size_t index;

    assert(kmp_hdr10_plus_parse_tone_curve(
        payload,
        size,
        600.0,
        &source_peak,
        curve,
        error,
        sizeof(error)
    ));
    assert(fabsf(source_peak - 1079.66f) < 0.01f);
    assert(fabsf(curve[0]) < 0.000001f);
    for (index = 0; index < KMP_HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT; ++index) {
        assert(isfinite(curve[index]));
        assert(curve[index] >= 0.0f && curve[index] <= 0.060001f);
        if (index > 0) assert(curve[index] + 0.0000001f >= curve[index - 1]);
    }

    size = build_profile_b_payload(
        payload,
        sizeof(payload),
        reference_max_scl,
        2048,
        reference_anchor,
        1
    );
    assert(kmp_hdr10_plus_parse_tone_curve(
        payload,
        size,
        600.0,
        &source_peak,
        curve,
        error,
        sizeof(error)
    ));
    assert(fabsf(source_peak - 1000.0f) < 0.001f);
    assert(fabsf(curve[16] * 10000.0f - 387.80682f) < 0.001f);

    assert(!kmp_hdr10_plus_parse_tone_curve(
        payload,
        size / 2,
        600.0,
        &source_peak,
        curve,
        error,
        sizeof(error)
    ));
    assert(error[0] != '\0');

    memcpy(malformed, payload, size);
    malformed[0] = 0;
    assert(!kmp_hdr10_plus_parse_tone_curve(
        malformed,
        size,
        600.0,
        &source_peak,
        curve,
        error,
        sizeof(error)
    ));
    return 0;
}
