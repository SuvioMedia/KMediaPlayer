#ifndef COMPOSEMEDIAPLAYER_LIBDOVI_H
#define COMPOSEMEDIAPLAYER_LIBDOVI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

uint8_t *cmp_dovi_allocate_buffer(size_t len);

uint8_t *cmp_dovi_convert_profile7_to81(
    const uint8_t *input,
    size_t input_len,
    size_t *output_len
);

void cmp_dovi_free_buffer(uint8_t *buffer, size_t len);

#ifdef __cplusplus
}
#endif

#endif
