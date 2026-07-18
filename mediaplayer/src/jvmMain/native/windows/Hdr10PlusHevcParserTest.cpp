#include "Hdr10PlusHevcParser.h"

#include <cassert>
#include <cstdint>
#include <vector>

namespace {

std::vector<uint8_t> SeiNal() {
    // HEVC prefix SEI, payload type 4. 00 00 03 01 verifies RBSP unescaping.
    return {
        0x4e, 0x01, 0x04, 0x0a,
        0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04, 0x01, 0x00, 0x00, 0x03, 0x01,
        0x80,
    };
}

} // namespace

int main() {
    const std::vector<uint8_t> expected = {
        0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04, 0x01, 0x00, 0x00, 0x01,
    };
    const std::vector<uint8_t> nal = SeiNal();
    std::vector<uint8_t> payload;

    std::vector<uint8_t> lengthPrefixed = {
        0x00, 0x00, 0x00, static_cast<uint8_t>(nal.size()),
    };
    lengthPrefixed.insert(lengthPrefixed.end(), nal.begin(), nal.end());
    assert(Hdr10PlusHevc::ExtractPayload(
        lengthPrefixed.data(), lengthPrefixed.size(), 4, payload));
    assert(payload == expected);

    std::vector<uint8_t> annexB = {0x00, 0x00, 0x00, 0x01};
    annexB.insert(annexB.end(), nal.begin(), nal.end());
    assert(Hdr10PlusHevc::ExtractPayload(annexB.data(), annexB.size(), 4, payload));
    assert(payload == expected);

    std::vector<uint8_t> unrelated = nal;
    unrelated[9] = 0x00; // application identifier is no longer HDR10+.
    std::vector<uint8_t> unrelatedSample = {
        0x00, 0x00, 0x00, static_cast<uint8_t>(unrelated.size()),
    };
    unrelatedSample.insert(unrelatedSample.end(), unrelated.begin(), unrelated.end());
    assert(!Hdr10PlusHevc::ExtractPayload(
        unrelatedSample.data(), unrelatedSample.size(), 4, payload));

    lengthPrefixed.pop_back();
    assert(!Hdr10PlusHevc::ExtractPayload(
        lengthPrefixed.data(), lengthPrefixed.size(), 4, payload));
    return 0;
}
