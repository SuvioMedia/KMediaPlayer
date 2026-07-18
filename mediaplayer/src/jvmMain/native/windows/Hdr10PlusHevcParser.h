#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace Hdr10PlusHevc {

constexpr size_t kMaximumPayloadSize = 1024;

/** Extracts one User Data Registered ITU-T T.35 payload from HEVC prefix/suffix SEI NAL units. */
bool ExtractPayload(
    const uint8_t* sample,
    size_t sampleSize,
    uint8_t nalLengthSize,
    std::vector<uint8_t>& payload);

} // namespace Hdr10PlusHevc
